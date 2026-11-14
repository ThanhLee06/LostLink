"""Focused foundation regression tests; extended suite is archived locally."""


from __future__ import annotations


from datetime import datetime, timezone


from app.services.migration_service import run_backfill


from scripts.backfill_b0 import format_summary, parse_args


from tests.conftest import FakeFirestoreClient


def _seed_legacy_corpus(db: FakeFirestoreClient) -> datetime:
    t0 = datetime(2026, 9, 1, 8, 30, 0, tzinfo=timezone.utc)

    # 1. Legacy ACTIVE + phone + Auditorium + legacy imageUrl (unverified media) + PII
    db.collection("posts").document("p-legacy").set(
        {
            "title": "Mất điện thoại PII_TITLE_CHECK",
            "description": "Liên hệ pii-leak-check@ut.edu.vn tại Hội trường",
            "contactEmail": "pii-leak-check@ut.edu.vn",
            "authorName": "Nguyen Van Secret",
            "categoryId": "phone",
            "areaId": "Auditorium",
            "status": "ACTIVE",
            "authorId": "author-1",
            "createdAt": t0,
            "imageUrl": "https://old.example.com/photo1.jpg",
        }
    )

    # 2. Legacy laptop + documents must stay untouched + RESOLVED preserved
    db.collection("posts").document("p-docs-resolved").set(
        {
            "title": "Tìm thấy giấy tờ",
            "description": "Đã trả cho chủ nhân",
            "categoryId": "documents",
            "areaId": "library",
            "areaChoice": "KNOWN",
            "status": "RESOLVED",
            "authorId": "author-2",
            "createdAt": t0,
        }
    )

    # 3. Already HIDDEN + higher version=3 + CLOSED must be preserved strictly
    db.collection("posts").document("p-hidden-v3").set(
        {
            "title": "Bài bị ẩn",
            "description": "Nội dung đã ẩn",
            "categoryId": "laptop",
            "areaChoice": "OTHER",
            "areaId": "",
            "areaOtherText": "Sân bóng",
            "status": "CLOSED",
            "moderationStatus": "HIDDEN",
            "version": 3,
            "authorId": "author-3",
            "createdAt": t0,
            "updatedAt": t0,
            "mediaAssetIds": [],
        }
    )

    # 4. Anomalous legacy doc: missing createdAt, unknown category/area, missing authorId
    db.collection("posts").document("p-anomaly").set(
        {
            "title": "Bài thiếu dữ liệu",
            "description": "Không rõ ngày đăng",
            "categoryId": "mystery_cat",
            "areaId": "mystery_area",
            "status": "OPEN",
        }
    )
    return t0


def test_backfill_dry_run_makes_no_writes_and_audits_without_pii(
    fake_db: FakeFirestoreClient,
) -> None:
    _seed_legacy_corpus(fake_db)

    summary = run_backfill(
        fake_db,  # type: ignore[arg-type]
        apply=False,
        rebuild_embeddings=True,
        run_id="dry-check",
    )
    rendered = format_summary(summary)

    assert summary.mode == "dry-run"
    assert summary.scanned == 4
    assert summary.would_update == 4
    assert summary.updated == 0
    assert summary.preserved_hidden == 1
    assert summary.preserved_higher_version == 1
    assert summary.normalized_statuses == 1
    assert summary.normalized_categories == 2  # phone + laptop
    assert summary.normalized_areas == 1  # Auditorium
    assert summary.missing_created_at_ids == ["p-anomaly"]
    assert summary.missing_author_id_posts == ["p-anomaly"]
    assert summary.unknown_category_posts == {"p-anomaly": "mystery_cat"}
    assert summary.unknown_area_posts == {"p-anomaly": "mystery_area"}
    assert summary.legacy_unverified_media_ids == ["p-legacy"]
    # Eligible VISIBLE with authorId: p-legacy, p-docs-resolved (p-hidden-v3 is HIDDEN, p-anomaly lacks authorId)
    assert summary.backfill_events_planned == 2
    assert summary.backfill_events_created == 0
    assert summary.backfill_events_skipped == 2

    # Dry-run MUST NOT mutate Firestore
    p_legacy = fake_db.collection("posts").document("p-legacy").get().to_dict()
    assert p_legacy is not None
    assert p_legacy["status"] == "ACTIVE"
    assert p_legacy["categoryId"] == "phone"
    assert "moderationStatus" not in p_legacy
    assert list(fake_db.collection("post_events").stream()) == []

    # Rendered output & JSON summary MUST NEVER leak PII
    dumped = summary.model_dump_json() + "\n" + rendered
    for secret in ("pii-leak-check@ut.edu.vn", "PII_TITLE_CHECK", "Nguyen Van Secret"):
        assert secret not in dumped


def test_backfill_apply_preserves_invariants_and_is_idempotent(
    fake_db: FakeFirestoreClient,
) -> None:
    t0 = _seed_legacy_corpus(fake_db)

    first = run_backfill(
        fake_db,  # type: ignore[arg-type]
        apply=True,
        rebuild_embeddings=False,
        run_id="run-1",
    )
    assert first.mode == "apply"
    assert first.scanned == 4
    assert first.updated == 4
    assert first.skipped_conflict == 0

    p_legacy = fake_db.collection("posts").document("p-legacy").get().to_dict()
    assert p_legacy is not None
    assert p_legacy["status"] == "OPEN"
    assert p_legacy["categoryId"] == "electronics"
    assert p_legacy["areaId"] == "auditorium"
    assert p_legacy["areaChoice"] == "KNOWN"
    assert p_legacy["moderationStatus"] == "VISIBLE"
    assert p_legacy["version"] == 1
    assert p_legacy["authorId"] == "author-1"
    assert p_legacy["createdAt"] == t0
    assert p_legacy["imageUrl"] == "https://old.example.com/photo1.jpg"
    assert "moderationReason" not in p_legacy
    assert "embedding" not in p_legacy

    p_docs = fake_db.collection("posts").document("p-docs-resolved").get().to_dict()
    assert p_docs is not None
    assert p_docs["categoryId"] == "documents"  # NEVER coerced to student_card
    assert p_docs["status"] == "RESOLVED"
    assert p_docs["createdAt"] == t0

    p_hidden = fake_db.collection("posts").document("p-hidden-v3").get().to_dict()
    assert p_hidden is not None
    assert p_hidden["moderationStatus"] == "HIDDEN"
    assert p_hidden["version"] == 3
    assert p_hidden["status"] == "CLOSED"
    assert p_hidden["categoryId"] == "electronics"  # laptop -> electronics
    assert p_hidden["areaChoice"] == "OTHER"
    assert p_hidden["areaId"] == ""

    # Missing createdAt MUST remain absent (never fabricated); unknown taxonomy untouched
    p_anom = fake_db.collection("posts").document("p-anomaly").get().to_dict()
    assert p_anom is not None
    assert "createdAt" not in p_anom
    assert p_anom["categoryId"] == "mystery_cat"
    assert p_anom["areaId"] == "mystery_area"

    # Second run on clean posts is idempotent (updated=0 for p-legacy, p-docs-resolved, p-hidden-v3)
    # Remove p-anomaly (already has canonical B0 fields filled except unmappable category/area)
    second = run_backfill(fake_db, apply=True, rebuild_embeddings=False, run_id="run-2")  # type: ignore[arg-type]
    assert second.updated == 0
    assert second.unchanged == 4


def test_backfill_skips_on_concurrent_update_precondition_conflict(
    fake_db: FakeFirestoreClient,
) -> None:
    t0 = datetime(2026, 9, 5, 10, 0, 0, tzinfo=timezone.utc)
    fake_db.collection("posts").document("p-race").set(
        {
            "title": "Ví bị mất",
            "categoryId": "phone",
            "areaId": "a",
            "status": "ACTIVE",
            "authorId": "author-race",
            "createdAt": t0,
        }
    )

    # Simulate an admin hiding the post & bumping version=2 mid-transaction
    def _concurrent_hide() -> None:
        fake_db._raw_update(
            "posts",
            "p-race",
            {"moderationStatus": "HIDDEN", "version": 2},
        )

    fake_db.before_commit_hook = _concurrent_hide

    summary = run_backfill(fake_db, apply=True, run_id="race-run")  # type: ignore[arg-type]
    assert summary.scanned == 1
    assert summary.skipped_conflict == 1
    assert summary.updated == 0

    # Concurrent HIDDEN & version=2 MUST NOT be overwritten
    doc = fake_db.collection("posts").document("p-race").get().to_dict()
    assert doc is not None
    assert doc["moderationStatus"] == "HIDDEN"
    assert doc["version"] == 2
    assert doc["status"] == "ACTIVE"  # conflicting update aborted safely


def test_backfill_cli_parse_args_defaults_to_dry_run() -> None:
    args_default = parse_args([])
    assert args_default.apply is False
    assert args_default.dry_run is True

    args_apply = parse_args(
        ["--project", "lostlink-prod", "--apply", "--rebuild-embeddings", "--batch-limit", "50"]
    )
    assert args_apply.project == "lostlink-prod"
    assert args_apply.apply is True
    assert args_apply.rebuild_embeddings is True
    assert args_apply.batch_limit == 50


def test_backfill_aborts_on_unversioned_concurrent_status_and_category_mutation_c2_r1(
    fake_db: FakeFirestoreClient,
) -> None:
    t0 = datetime(2026, 9, 5, 10, 0, 0, tzinfo=timezone.utc)

    # 1. Unversioned post flips ACTIVE -> CLOSED concurrently mid-transaction
    fake_db.collection("posts").document("p-status-race").set(
        {
            "title": "Ví race status",
            "categoryId": "phone",
            "areaId": "a",
            "status": "ACTIVE",
            "authorId": "author-1",
            "createdAt": t0,
        }
    )
    fake_db.before_commit_hook = lambda: fake_db._raw_update(
        "posts", "p-status-race", {"status": "CLOSED"}
    )
    s1 = run_backfill(fake_db, apply=True, run_id="race-status")  # type: ignore[arg-type]
    fake_db.before_commit_hook = None
    assert s1.skipped_conflict == 1
    assert s1.updated == 0
    doc1 = fake_db.collection("posts").document("p-status-race").get().to_dict()
    assert doc1 is not None
    assert doc1["status"] == "CLOSED"
    assert "version" not in doc1

    # Clean re-run preserves CLOSED instead of coercing back to OPEN
    s1_retry = run_backfill(fake_db, apply=True, run_id="race-status-2")  # type: ignore[arg-type]
    assert s1_retry.updated == 1
    doc1_after = fake_db.collection("posts").document("p-status-race").get().to_dict()
    assert doc1_after is not None
    assert doc1_after["status"] == "CLOSED"
    assert doc1_after["version"] == 1

    # 2. Unversioned post mutates categoryId concurrently mid-transaction -> aborted cleanly
    fake_db.collection("posts").document("p-cat-race").set(
        {
            "title": "Ví race category",
            "categoryId": "phone",
            "areaId": "a",
            "status": "OPEN",
            "authorId": "author-2",
            "createdAt": t0,
        }
    )
    fake_db.before_commit_hook = lambda: fake_db._raw_update(
        "posts", "p-cat-race", {"categoryId": "keys"}
    )
    s2 = run_backfill(fake_db, apply=True, run_id="race-cat")  # type: ignore[arg-type]
    fake_db.before_commit_hook = None
    assert s2.skipped_conflict == 1
    doc2 = fake_db.collection("posts").document("p-cat-race").get().to_dict()
    assert doc2 is not None and doc2["categoryId"] == "keys"
