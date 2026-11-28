"""Kiểm thử ST34: đánh giá Top-K trên tập mẫu, lọc quyền hiển thị và log truy vấn ẩn danh."""
from __future__ import annotations

from datetime import datetime, timezone
import logging
from typing import Any, Dict, List

import firebase_admin.auth as fb_auth
from fastapi.testclient import TestClient
import pytest

from app.core.config import get_settings
from app.main import create_app
from app.schemas.search import SearchRequest
from app.services.search_eval import evaluate_top_k, seed_evaluation_corpus
from app.services.search_service import search_posts
from app.services.vector_index_service import upsert_post_embedding
from tests.conftest import FakeFirestoreClient


def test_top_k_evaluation_keyword_and_hybrid_semantic(
    monkeypatch: pytest.MonkeyPatch, fake_db: FakeFirestoreClient, test_settings_env: None
) -> None:
    now = datetime(2026, 11, 20, 10, 0, 0, tzinfo=timezone.utc)
    seed_evaluation_corpus(fake_db, now=now)

    # 1. Đánh giá chế độ KEYWORD trên tập mẫu + các biến thể gõ (bóp, túi tiền, vis, vsi, viiii)
    kw_metrics = evaluate_top_k(lambda req: search_posts(fake_db, req, "u-caller", now=now), k=3)
    assert kw_metrics["hitRateAtK"] == 1.0
    assert kw_metrics["mrrAtK"] == 1.0
    assert kw_metrics["unmatchedAccuracy"] == 1.0
    assert kw_metrics["leakedRestrictedCount"] == 0

    for variant in ("bóp", "túi tiền", "vis", "vsi", "viiiiii"):
        v_resp = search_posts(fake_db, SearchRequest(query=variant), "u-caller", now=now)
        assert [i.post_id for i in v_resp.items] == ["post-wallet"], f"Truy vấn {variant!r} phải tìm ra post-wallet"

    # 2. Đánh giá chế độ HYBRID khi đồng nghĩa ("bóp da" <-> "ví da")
    settings = get_settings()
    wallet_vec = [1.0, 0.0] + [0.0] * 766
    upsert_post_embedding(
        fake_db,
        post_id="post-wallet",
        post_version=1,
        post_data={"type": "LOST", "categoryId": "wallet", "areaId": "canteen", "createdAt": now},
        vector=wallet_vec,
        settings=settings,
    )
    monkeypatch.setenv("GEMINI_API_KEY", "eval-key")
    get_settings.cache_clear()

    async def _fake_embed_query(query: str, **kw: Any) -> List[float]:
        if "bóp" in query.lower() or "ví" in query.lower():
            return wallet_vec
        return [0.0, 1.0] + [0.0] * 766

    monkeypatch.setattr("app.services.search_service.embed_query", _fake_embed_query)
    resp = search_posts(fake_db, SearchRequest(query="bóp đựng tiền"), "u-caller", now=now)
    assert resp.search_mode == "HYBRID"
    assert [i.post_id for i in resp.items[:1]] == ["post-wallet"]

    # Hit vector có metadata cũ (keys) nhưng bài thật đã đổi sang bag -> phải bị lọc khi re-read
    upsert_post_embedding(
        fake_db,
        post_id="post-bag-resolved",
        post_version=1,
        post_data={"type": "FOUND", "categoryId": "keys", "areaId": "auditorium", "createdAt": now},
        vector=wallet_vec,
        settings=settings,
    )
    resp_filtered = search_posts(
        fake_db,
        SearchRequest(query="bóp đựng tiền", categoryIds=["keys"]),
        "u-caller",
        now=now,
    )
    assert "post-bag-resolved" not in [i.post_id for i in resp_filtered.items]


def test_search_api_anonymized_query_logging_and_permissions(
    monkeypatch: pytest.MonkeyPatch,
    fake_db: FakeFirestoreClient,
    test_settings_env: None,
    caplog: pytest.LogCaptureFixture,
) -> None:
    now = datetime.now(tz=timezone.utc)
    seed_evaluation_corpus(fake_db, now=now)
    monkeypatch.setattr(
        fb_auth,
        "verify_id_token",
        lambda tok, check_revoked=True: {"uid": "u-student-secret", "email": "sv@ut.edu.vn", "email_verified": True}
        if tok == "valid-tok"
        else (_ for _ in ()).throw(fb_auth.InvalidIdTokenError("bad")),
    )
    client = TestClient(create_app())

    assert client.post("/api/v1/search", json={"query": "ví da"}).status_code == 401
    caplog.set_level(logging.INFO, logger="app.routers.search")
    r = client.post(
        "/api/v1/search",
        headers={"Authorization": "Bearer valid-tok"},
        json={"query": "ví da nâu căn tin"},
    )
    assert r.status_code == 200
    body = r.json()
    assert [i["postId"] for i in body["items"]] == ["post-wallet"]
    assert body["items"][0]["statusLabel"] == "Đang mở"

    logs = "\n".join(rec.getMessage() for rec in caplog.records if rec.name == "app.routers.search")
    assert "search_query" in logs
    assert "latencyMs=" in logs and "resultCount=1" in logs and "topScore=" in logs
    assert "ví da nâu" not in logs
    assert "căn tin" not in logs
    assert "u-student-secret" not in logs
