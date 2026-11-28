"""Kiểm thử ST32: pipeline embedding, chỉ mục vector và đồng bộ worker post_events."""
from __future__ import annotations

from datetime import datetime, timedelta, timezone
import json
from typing import Any, Dict, List

import httpx
import pytest

from app.core.config import get_settings
from app.schemas.post import EventChangeType, PersistedPostEvent
from app.services.embedding_service import EmbeddingError, build_post_embedding_text, embed_post, embed_query, embed_text
from app.services.migration_service import run_backfill
from app.services.vector_index_service import cleanup_orphan_embeddings, delete_post_embedding, query_similar_posts, upsert_post_embedding
from app.worker.handlers.search_index_handler import handle_search_job
from app.worker.runner import run_batch
from scripts.backfill_b0 import main as backfill_main
from tests.conftest import FakeFirestoreClient


def _unit_vec(idx: int, dims: int = 768, second_idx: int | None = None, second_w: float = 0.0) -> List[float]:
    v = [0.0] * dims
    v[idx] = 1.0
    if second_idx is not None:
        v[second_idx] = second_w
    norm = sum(x * x for x in v) ** 0.5
    return [x / norm for x in v]


def _seed_post(
    db: FakeFirestoreClient,
    post_id: str,
    *,
    title: str = "Mất ví da đen tại thư viện",
    post_type: str = "LOST",
    status: str = "OPEN",
    moderation_status: str = "VISIBLE",
    category_id: str = "wallet",
    area_id: str = "library",
    version: int = 1,
    author_id: str = "u-author",
    created_at: datetime | None = None,
) -> datetime:
    ts = created_at or datetime(2026, 11, 10, 8, 0, 0, tzinfo=timezone.utc)
    db.collection("posts").document(post_id).set(
        {
            "title": title,
            "description": "Ví màu đen có thẻ sinh viên bên trong",
            "type": post_type,
            "status": status,
            "moderationStatus": moderation_status,
            "categoryId": category_id,
            "areaId": area_id,
            "areaChoice": "KNOWN",
            "location": "Tầng 2 thư viện",
            "version": version,
            "authorId": author_id,
            "authorName": "Nguyen Van A",
            "contactEmail": "a@ut.edu.vn",
            "createdAt": ts,
            "updatedAt": ts,
        }
    )
    return ts


def test_build_post_embedding_text_vietnamese_and_embed_text(monkeypatch: pytest.MonkeyPatch, test_settings_env: None) -> None:
    text = build_post_embedding_text(
        {
            "type": "LOST",
            "title": "Rơi thẻ sinh viên",
            "description": "Gần cổng chính",
            "categoryId": "student_card",
            "areaId": "campus_1",
            "location": "Nhà xe A",
        }
    )
    assert "Mất đồ" in text
    assert "Thẻ sinh viên" in text
    assert "Cơ sở 1 (Võ Oanh)" in text

    monkeypatch.setenv("GEMINI_API_KEY", "test-gemini-key")
    get_settings.cache_clear()
    captured: Dict[str, Any] = {}

    def _handler(request: httpx.Request) -> httpx.Response:
        captured["headers"] = dict(request.headers)
        captured["body"] = json.loads(request.content.decode("utf-8"))
        return httpx.Response(200, json={"embedding": {"values": [3.0, 4.0] + [0.0] * 766}})

    real_async_client = httpx.AsyncClient
    monkeypatch.setattr(httpx, "AsyncClient", lambda **kw: real_async_client(transport=httpx.MockTransport(_handler), **kw))

    import asyncio

    vec = asyncio.run(embed_text("thẻ sinh viên", task_type="RETRIEVAL_QUERY"))
    assert captured["headers"]["x-goog-api-key"] == "test-gemini-key"
    assert captured["body"]["taskType"] == "RETRIEVAL_QUERY"
    assert captured["body"]["outputDimensionality"] == 768
    assert pytest.approx(sum(x * x for x in vec), abs=1e-6) == 1.0
    assert pytest.approx(vec[0], abs=1e-6) == 0.6


@pytest.mark.asyncio
async def test_embed_text_free_local_fallback_and_gemini_error_redaction(
    monkeypatch: pytest.MonkeyPatch, test_settings_env: None
) -> None:
    # Khi không có GEMINI_API_KEY, tự động dùng Vectorizer nội bộ 0 đồng (768 chiều, chuẩn hóa L2)
    v_vi = await embed_query("ví da")
    for variant in ("bóp", "túi tiền", "vis", "vsi", "viiiiii"):
        v_var = await embed_query(variant)
        assert len(v_var) == 768
        dot = sum(a * b for a, b in zip(v_vi, v_var))
        assert dot > 0.6, f"Biến thể {variant!r} phải có độ tương đồng vector cao với 'ví da'"

    monkeypatch.setenv("GEMINI_API_KEY", "AIzaSySecretKey1234567890")
    get_settings.cache_clear()
    real_async_client = httpx.AsyncClient
    monkeypatch.setattr(
        httpx,
        "AsyncClient",
        lambda **kw: real_async_client(
            transport=httpx.MockTransport(lambda req: httpx.Response(500, text="fail key=AIzaSySecretKey1234567890")),
            **kw,
        ),
    )
    with pytest.raises(EmbeddingError) as exc_info:
        await embed_post({"title": "Mất chìa khóa"})
    assert "AIzaSySecretKey1234567890" not in str(exc_info.value)


def test_vector_upsert_delete_version_guard_and_query_filters(fake_db: FakeFirestoreClient, test_settings_env: None) -> None:
    settings = get_settings()
    t_now = datetime(2026, 11, 12, 10, 0, 0, tzinfo=timezone.utc)
    t_old = t_now - timedelta(days=5)

    p1 = {"type": "LOST", "categoryId": "wallet", "areaId": "library", "createdAt": t_now}
    p2 = {"type": "FOUND", "categoryId": "keys", "areaId": "parking", "createdAt": t_now}
    p3 = {"type": "LOST", "categoryId": "wallet", "areaId": "canteen", "createdAt": t_old}

    assert upsert_post_embedding(fake_db, post_id="p1", post_version=2, post_data=p1, vector=_unit_vec(0), settings=settings) is True
    # Không cho phép version cũ ghi đè version mới
    assert upsert_post_embedding(fake_db, post_id="p1", post_version=1, post_data=p1, vector=_unit_vec(1), settings=settings) is False
    assert delete_post_embedding(fake_db, post_id="p1", min_version=1) is False

    upsert_post_embedding(fake_db, post_id="p2", post_version=1, post_data=p2, vector=_unit_vec(0, second_idx=1, second_w=0.5), settings=settings)
    upsert_post_embedding(fake_db, post_id="p3", post_version=1, post_data=p3, vector=_unit_vec(2), settings=settings)
    # Bản ghi không hợp lệ (RESOLVED) phải bị bỏ qua khi query
    fake_db.collection("post_embeddings").document("p-res").set(
        {
            "postId": "p-res",
            "status": "RESOLVED",
            "moderationStatus": "VISIBLE",
            "type": "LOST",
            "categoryId": "wallet",
            "areaId": "library",
            "postCreatedAt": t_now,
            "vector": _unit_vec(0),
        }
    )

    hits = query_similar_posts(
        fake_db,
        query_vector=_unit_vec(0),
        post_type="LOST",
        category_ids=["wallet"],
        area_ids=["library", "canteen"],
        min_created_at=t_now - timedelta(days=1),
    )
    assert [h["postId"] for h in hits] == ["p1"]
    assert hits[0]["score"] == 1.0


@pytest.mark.asyncio
async def test_search_job_lifecycle_sync_and_stale_version_protection(
    monkeypatch: pytest.MonkeyPatch, fake_db: FakeFirestoreClient, test_settings_env: None
) -> None:
    calls: List[str] = []

    async def _fake_embed(post_data: Dict[str, Any], settings: Any = None) -> List[float]:
        calls.append(str(post_data.get("title")))
        return _unit_vec(0 if "ví" in str(post_data.get("title")).lower() else 1)

    monkeypatch.setattr("app.worker.handlers.search_index_handler.embed_post", _fake_embed)
    now = datetime.now(tz=timezone.utc)
    _seed_post(fake_db, "p1", version=1)
    fake_db.collection("post_events").document("p1_v1").set(
        PersistedPostEvent.build_initial(post_id="p1", author_id="u-author", post_version=1, change_type=EventChangeType.CREATE, created_at=now).to_firestore_dict()
    )

    assert await run_batch(fake_db, batch_size=5, handlers={"searchJob": handle_search_job}, include_media_sweep=False) == 1
    emb = fake_db.collection("post_embeddings").document("p1").get().to_dict()
    assert emb is not None
    assert emb["postVersion"] == 1
    assert "authorId" not in emb and "contactEmail" not in emb

    # Event cũ (v1) khi bài đã lên v2 -> SKIPPED và không ghi đè
    fake_db.collection("posts").document("p1").update({"version": 2, "title": "Mất ví nâu"})
    fake_db.collection("post_events").document("p1_v1_late").set(
        PersistedPostEvent.build_initial(post_id="p1", author_id="u-author", post_version=1, change_type=EventChangeType.UPDATE, created_at=now).to_firestore_dict()
    )
    assert await run_batch(fake_db, batch_size=5, handlers={"searchJob": handle_search_job}, include_media_sweep=False) == 1
    assert fake_db.collection("post_events").document("p1_v1_late").get().to_dict()["searchJob"]["status"] == "SKIPPED"  # type: ignore[index]

    # Race condition: bài lên v3 trong lúc đang gọi Gemini cho v2 -> không ghi đè
    async def _race_embed(post_data: Dict[str, Any], settings: Any = None) -> List[float]:
        fake_db._raw_update("posts", "p1", {"version": 3})
        return _unit_vec(5)

    monkeypatch.setattr("app.worker.handlers.search_index_handler.embed_post", _race_embed)
    fake_db.collection("post_events").document("p1_v2").set(
        PersistedPostEvent.build_initial(post_id="p1", author_id="u-author", post_version=2, change_type=EventChangeType.UPDATE, created_at=now).to_firestore_dict()
    )
    assert await run_batch(fake_db, batch_size=5, handlers={"searchJob": handle_search_job}, include_media_sweep=False) == 1
    assert fake_db.collection("post_events").document("p1_v2").get().to_dict()["searchJob"]["status"] == "SKIPPED"  # type: ignore[index]
    assert fake_db.collection("post_embeddings").document("p1").get().to_dict()["postVersion"] == 1  # type: ignore[index]

    # Event RESOLVED v2 đến trễ khi bài đã mở lại OPEN ở v3 -> SKIPPED và giữ nguyên vector
    fake_db.collection("posts").document("p1").update({"status": "OPEN", "version": 3})
    fake_db.collection("post_embeddings").document("p1").set(
        {"postId": "p1", "postVersion": 1, "status": "OPEN", "moderationStatus": "VISIBLE", "vector": _unit_vec(0)}
    )
    fake_db.collection("post_events").document("p1_v2_stale_res").set(
        PersistedPostEvent.build_initial(post_id="p1", author_id="u-author", post_version=2, change_type=EventChangeType.STATUS, created_at=now).to_firestore_dict()
    )
    assert await run_batch(fake_db, batch_size=5, handlers={"searchJob": handle_search_job}, include_media_sweep=False) == 1
    assert fake_db.collection("post_events").document("p1_v2_stale_res").get().to_dict()["searchJob"]["status"] == "SKIPPED"  # type: ignore[index]
    assert fake_db.collection("post_embeddings").document("p1").get().exists is True

    # Chuyển RESOLVED ở v4 -> xóa vector
    fake_db.collection("posts").document("p1").update({"status": "RESOLVED", "version": 4})
    fake_db.collection("post_events").document("p1_v4").set(
        PersistedPostEvent.build_initial(post_id="p1", author_id="u-author", post_version=4, change_type=EventChangeType.STATUS, created_at=now).to_firestore_dict()
    )
    assert await run_batch(fake_db, batch_size=5, handlers={"searchJob": handle_search_job}, include_media_sweep=False) == 1
    assert fake_db.collection("post_embeddings").document("p1").get().exists is False


@pytest.mark.asyncio
async def test_hidden_delete_deleting_author_backfill_and_orphan_cleanup(
    monkeypatch: pytest.MonkeyPatch, fake_db: FakeFirestoreClient, test_settings_env: None
) -> None:
    embed_calls = 0

    async def _fake_embed(post_data: Dict[str, Any], settings: Any = None) -> List[float]:
        nonlocal embed_calls
        embed_calls += 1
        return _unit_vec(3)

    monkeypatch.setattr("app.worker.handlers.search_index_handler.embed_post", _fake_embed)
    now = datetime.now(tz=timezone.utc)

    # 1. Backfill bài OPEN + VISIBLE tạo vector, chạy lại lần nữa thì SKIPPED (idempotent)
    _seed_post(fake_db, "p-open", version=1)
    summary = run_backfill(fake_db, apply=True, rebuild_embeddings=True, run_id="st32")  # type: ignore[arg-type]
    assert summary.backfill_events_created == 1
    assert await run_batch(fake_db, batch_size=5, handlers={"searchJob": handle_search_job}, include_media_sweep=False) == 1
    assert embed_calls == 1
    assert fake_db.collection("post_embeddings").document("p-open").get().exists is True

    run_backfill(fake_db, apply=True, rebuild_embeddings=True, run_id="st32-2")  # type: ignore[arg-type]
    assert await run_batch(fake_db, batch_size=5, handlers={"searchJob": handle_search_job}, include_media_sweep=False) == 1
    assert embed_calls == 1  # Không gọi lại Gemini

    # 2. Tác giả chuyển sang DELETING hoặc bài bị HIDDEN -> cleanup_orphan_embeddings dọn sạch
    fake_db.collection("account_states").document("u-author").set({"status": "DELETING", "updatedAt": now})
    fake_db.collection("post_embeddings").document("p-ghost").set({"postId": "p-ghost", "vector": _unit_vec(1)})
    assert cleanup_orphan_embeddings(fake_db) == 2
    assert fake_db.collection("post_embeddings").document("p-open").get().exists is False
    assert fake_db.collection("post_embeddings").document("p-ghost").get().exists is False


def test_backfill_cli_drain_search(monkeypatch: pytest.MonkeyPatch, fake_db: FakeFirestoreClient, test_settings_env: None, capsys: pytest.CaptureFixture[str]) -> None:
    async def _fake_embed(post_data: Dict[str, Any], settings: Any = None) -> List[float]:
        return _unit_vec(0)

    monkeypatch.setattr("app.worker.handlers.search_index_handler.embed_post", _fake_embed)
    monkeypatch.setattr("scripts.backfill_b0.init_firebase", lambda: None)
    monkeypatch.setattr("scripts.backfill_b0.get_firestore", lambda: fake_db)
    _seed_post(fake_db, "p-cli-1", version=1)
    fake_db.collection("post_embeddings").document("p-orphan").set({"postId": "p-orphan", "vector": _unit_vec(2)})

    rc = backfill_main(["--apply", "--rebuild-embeddings", "--drain-search", "--run-id", "cli1"])
    out = capsys.readouterr().out
    assert rc == 0
    assert "searchJobsProcessed:      1" in out
    assert "orphanVectorsDeleted:     1" in out
    assert fake_db.collection("post_embeddings").document("p-cli-1").get().exists is True
    assert fake_db.collection("post_embeddings").document("p-orphan").get().exists is False
