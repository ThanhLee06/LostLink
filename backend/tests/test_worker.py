"""Focused foundation regression tests; extended suite is archived locally."""


from __future__ import annotations


from datetime import datetime, timedelta, timezone


from typing import Any, Dict, List


import cloudinary.uploader


import pytest


from app.schemas.post import EventChangeType, PersistedPostEvent


from app.worker.claim import (
    ClaimError,
    MAX_ATTEMPTS,
    TokenMismatchError,
    claim_job,
    complete_job,
    fail_job,
)


from app.worker.handlers.media_cleanup_handler import handle_media_cleanup_job


from app.worker.runner import run_batch


from tests.conftest import FakeFirestoreClient


def _seed_event(
    db: FakeFirestoreClient,
    *,
    event_id: str = "post-1_v1",
    post_id: str = "post-1",
    author_id: str = "uid-1",
    post_version: int = 1,
    change_type: EventChangeType = EventChangeType.CREATE,
    image_asset_refs: List[str] | None = None,
) -> Dict[str, Any]:
    event = PersistedPostEvent.build_initial(
        post_id=post_id,
        author_id=author_id,
        post_version=post_version,
        change_type=change_type,
        created_at=datetime.now(tz=timezone.utc),
        image_asset_refs=image_asset_refs,
    )
    payload = event.to_firestore_dict()
    db.collection("post_events").document(event_id).set(payload)
    return payload


def _seed_post(
    db: FakeFirestoreClient,
    *,
    post_id: str = "post-1",
    author_id: str = "uid-1",
    version: int = 1,
    moderation_status: str = "VISIBLE",
    status: str = "OPEN",
    media_asset_ids: List[str] | None = None,
) -> None:
    db.collection("posts").document(post_id).set(
        {
            "id": post_id,
            "type": "LOST",
            "title": "Mất ví màu đen",
            "description": "Rơi gần thư viện cơ sở 1, liên hệ sv@ut.edu.vn",
            "categoryId": "wallet",
            "areaId": "library",
            "status": status,
            "moderationStatus": moderation_status,
            "version": version,
            "authorId": author_id,
            "mediaAssetIds": list(media_asset_ids or []),
            "createdAt": datetime.now(tz=timezone.utc),
            "updatedAt": datetime.now(tz=timezone.utc),
        }
    )


def test_claim_lease_exclusivity_naive_utc_and_max_attempts_reclaim(
    fake_db: FakeFirestoreClient,
) -> None:
    _seed_event(fake_db, event_id="ev-1")

    token1 = claim_job(fake_db, "ev-1", "searchJob", "worker-a", lease_seconds=60)  # type: ignore[arg-type]
    assert token1

    # Active lease cannot be stolen by worker-b
    with pytest.raises(ClaimError):
        claim_job(fake_db, "ev-1", "searchJob", "worker-b", lease_seconds=60)  # type: ignore[arg-type]

    # Expired naive UTC timestamp (no tzinfo) is normalized safely & reclaim increments attempts
    past_naive = (datetime.now(tz=timezone.utc) - timedelta(seconds=10)).replace(tzinfo=None)
    fake_db.collection("post_events").document("ev-1").update(
        {"searchJob.lockExpiresAt": past_naive}
    )

    token2 = claim_job(fake_db, "ev-1", "searchJob", "worker-b", lease_seconds=60)  # type: ignore[arg-type]
    assert token2 != token1
    doc = fake_db.collection("post_events").document("ev-1").get().to_dict()
    assert doc is not None
    assert doc["searchJob"]["attempts"] == 1
    assert doc["searchJob"]["lockOwner"] == "worker-b"

    # B0-T04: Stale worker-a tries to complete or fail with token1 -> must NOT clobber worker-b
    with pytest.raises(TokenMismatchError):
        complete_job(fake_db, "ev-1", "searchJob", token1)  # type: ignore[arg-type]

    fail_job(fake_db, "ev-1", "searchJob", token1, "stale error from crashed worker")  # type: ignore[arg-type]
    doc_after_stale_fail = fake_db.collection("post_events").document("ev-1").get().to_dict()
    assert doc_after_stale_fail is not None
    assert doc_after_stale_fail["searchJob"]["status"] == "PROCESSING"
    assert doc_after_stale_fail["searchJob"]["claimToken"] == token2
    assert doc_after_stale_fail["searchJob"]["attempts"] == 1

    # Exceeding MAX_ATTEMPTS via repeated lease expirations transitions job to FAILED
    past_aware = datetime.now(tz=timezone.utc) - timedelta(seconds=5)
    fake_db.collection("post_events").document("ev-1").update(
        {"searchJob.lockExpiresAt": past_aware}
    )
    claim_job(fake_db, "ev-1", "searchJob", "worker-c", lease_seconds=60)  # type: ignore[arg-type]
    # Now attempts == 2; expiring again reaches attempts == MAX_ATTEMPTS (3) -> FAILED
    fake_db.collection("post_events").document("ev-1").update(
        {"searchJob.lockExpiresAt": past_aware}
    )
    with pytest.raises(ClaimError, match="exceeded max attempts"):
        claim_job(fake_db, "ev-1", "searchJob", "worker-d", lease_seconds=60)  # type: ignore[arg-type]

    final_doc = fake_db.collection("post_events").document("ev-1").get().to_dict()
    assert final_doc is not None
    assert final_doc["searchJob"]["status"] == "FAILED"
    assert final_doc["searchJob"]["attempts"] == MAX_ATTEMPTS
    assert final_doc["searchJob"]["lastError"]


def test_fail_job_backoff_and_error_truncation(fake_db: FakeFirestoreClient) -> None:
    _seed_event(fake_db, event_id="ev-fail")
    tok1 = claim_job(fake_db, "ev-fail", "searchJob", "w1")  # type: ignore[arg-type]
    long_err = "E" * 900
    fail_job(fake_db, "ev-fail", "searchJob", tok1, long_err)  # type: ignore[arg-type]

    doc = fake_db.collection("post_events").document("ev-fail").get().to_dict()
    assert doc is not None
    job = doc["searchJob"]
    assert job["status"] == "PENDING"
    assert job["attempts"] == 1
    assert len(job["lastError"]) <= 500
    assert job["nextRetryAt"] is not None

    # Attempting to claim before nextRetryAt fails with ClaimError
    with pytest.raises(ClaimError, match="PENDING until"):
        claim_job(fake_db, "ev-fail", "searchJob", "w2")  # type: ignore[arg-type]


@pytest.mark.asyncio
async def test_media_cleanup_skips_empty_attached_foreign_and_deletes_unreferenced_owner(
    monkeypatch: pytest.MonkeyPatch,
    fake_db: FakeFirestoreClient,
    test_settings_env: None,
) -> None:
    destroyed: List[str] = []

    def _fake_destroy(public_id: str, **_kwargs: Any) -> Dict[str, str]:
        destroyed.append(public_id)
        return {"result": "ok"}

    monkeypatch.setattr(cloudinary.uploader, "destroy", _fake_destroy)

    # 1. Empty imageAssetRefs -> SKIPPED without destroy
    ev_empty = _seed_event(fake_db, event_id="ev-empty", image_asset_refs=[])
    tok_empty = claim_job(fake_db, "ev-empty", "mediaCleanupJob", "w1")  # type: ignore[arg-type]
    assert await handle_media_cleanup_job(fake_db, "ev-empty", ev_empty, tok_empty) == "SKIPPED"  # type: ignore[arg-type]
    assert destroyed == []

    # 2. Asset still attached on post -> skipped, asset remains READY
    _seed_post(fake_db, post_id="p-att", author_id="uid-1", media_asset_ids=["asset-attached"])
    fake_db.collection("media_assets").document("asset-attached").set(
        {
            "assetId": "asset-attached",
            "ownerUid": "uid-1",
            "postId": "p-att",
            "cloudinaryPublicId": "lostlink/uid-1/asset-attached",
            "status": "READY",
        }
    )
    ev_att = _seed_event(
        fake_db, event_id="ev-att", post_id="p-att", image_asset_refs=["asset-attached"]
    )
    tok_att = claim_job(fake_db, "ev-att", "mediaCleanupJob", "w1")  # type: ignore[arg-type]
    assert await handle_media_cleanup_job(fake_db, "ev-att", ev_att, tok_att) == "SKIPPED"  # type: ignore[arg-type]
    assert destroyed == []
    assert fake_db.collection("media_assets").document("asset-attached").get().to_dict()["status"] == "READY"  # type: ignore[index]

    # 3. Foreign asset referenced in forged event -> NEVER deleted (B0-T11)
    fake_db.collection("media_assets").document("asset-foreign").set(
        {
            "assetId": "asset-foreign",
            "ownerUid": "victim-uid",
            "postId": "p-victim",
            "cloudinaryPublicId": "lostlink/victim-uid/asset-foreign",
            "status": "READY",
        }
    )
    ev_forged = _seed_event(
        fake_db,
        event_id="ev-forged",
        post_id="p-attacker",
        author_id="attacker-uid",
        image_asset_refs=["asset-foreign"],
    )
    tok_forged = claim_job(fake_db, "ev-forged", "mediaCleanupJob", "w1")  # type: ignore[arg-type]
    await handle_media_cleanup_job(fake_db, "ev-forged", ev_forged, tok_forged)  # type: ignore[arg-type]
    assert destroyed == []
    assert fake_db.collection("media_assets").document("asset-foreign").get().to_dict()["status"] == "READY"  # type: ignore[index]

    # 4. Unreferenced owner asset -> transitions READY -> DELETING -> DELETED + Cloudinary destroy (B0-T06)
    _seed_post(fake_db, post_id="p-clean", author_id="uid-1", media_asset_ids=[])
    fake_db.collection("media_assets").document("asset-orphan").set(
        {
            "assetId": "asset-orphan",
            "ownerUid": "uid-1",
            "postId": "p-clean",
            "cloudinaryPublicId": "lostlink/uid-1/asset-orphan",
            "status": "READY",
        }
    )
    ev_clean = _seed_event(
        fake_db,
        event_id="ev-clean",
        post_id="p-clean",
        author_id="uid-1",
        image_asset_refs=["asset-orphan"],
    )
    tok_clean = claim_job(fake_db, "ev-clean", "mediaCleanupJob", "w1")  # type: ignore[arg-type]
    await handle_media_cleanup_job(fake_db, "ev-clean", ev_clean, tok_clean)  # type: ignore[arg-type]
    assert destroyed == ["lostlink/uid-1/asset-orphan"]
    assert fake_db.collection("media_assets").document("asset-orphan").get().to_dict()["status"] == "DELETED"  # type: ignore[index]


@pytest.mark.asyncio
async def test_claim_expired_lease_fences_complete_fail_renew_and_handlers_c2_r3(
    monkeypatch: pytest.MonkeyPatch,
    fake_db: FakeFirestoreClient,
    test_settings_env: None,
) -> None:
    from app.worker.claim import renew_lease

    _seed_event(fake_db, event_id="ev-exp", post_id="p-exp-1")
    tok = claim_job(fake_db, "ev-exp", "searchJob", "w1", lease_seconds=60)  # type: ignore[arg-type]

    # Expire lease WITHOUT any worker reclaiming yet
    expired_at = datetime.now(tz=timezone.utc) - timedelta(seconds=10)
    fake_db.collection("post_events").document("ev-exp").update(
        {"searchJob.lockExpiresAt": expired_at}
    )

    with pytest.raises(TokenMismatchError):
        complete_job(fake_db, "ev-exp", "searchJob", tok)  # type: ignore[arg-type]

    with pytest.raises(TokenMismatchError):
        renew_lease(fake_db, "ev-exp", "searchJob", tok, lease_seconds=120)  # type: ignore[arg-type]

    # Expired fail_job MUST NOT increment attempts or alter status
    fail_job(fake_db, "ev-exp", "searchJob", tok, "late error")  # type: ignore[arg-type]
    doc_exp = fake_db.collection("post_events").document("ev-exp").get().to_dict()
    assert doc_exp is not None
    assert doc_exp["searchJob"]["status"] == "PROCESSING"
    assert doc_exp["searchJob"]["attempts"] == 0

    # Active lease renew extends lockExpiresAt
    _seed_event(fake_db, event_id="ev-renew", post_id="p-renew")
    tok_r = claim_job(fake_db, "ev-renew", "searchJob", "w1", lease_seconds=10)  # type: ignore[arg-type]
    new_exp = renew_lease(fake_db, "ev-renew", "searchJob", tok_r, lease_seconds=300)  # type: ignore[arg-type]
    assert new_exp > datetime.now(tz=timezone.utc) + timedelta(seconds=200)

    # media_cleanup_handler checks lease before destroying Cloudinary asset
    destroyed: List[str] = []
    monkeypatch.setattr(
        cloudinary.uploader,
        "destroy",
        lambda public_id, **_kw: destroyed.append(public_id) or {"result": "ok"},
    )
    fake_db.collection("media_assets").document("asset-lease").set(
        {
            "assetId": "asset-lease",
            "ownerUid": "uid-1",
            "postId": "p-mc-lease",
            "cloudinaryPublicId": "lostlink/uid-1/asset-lease",
            "status": "READY",
        }
    )
    ev_mc = _seed_event(
        fake_db,
        event_id="ev-mc-lease",
        post_id="p-mc-lease",
        author_id="uid-1",
        image_asset_refs=["asset-lease"],
    )
    tok_mc = claim_job(fake_db, "ev-mc-lease", "mediaCleanupJob", "w1")  # type: ignore[arg-type]
    fake_db.collection("post_events").document("ev-mc-lease").update(
        {"mediaCleanupJob.lockExpiresAt": datetime.now(tz=timezone.utc) - timedelta(seconds=5)}
    )
    with pytest.raises(TokenMismatchError):
        await handle_media_cleanup_job(fake_db, "ev-mc-lease", ev_mc, tok_mc)  # type: ignore[arg-type]
    assert destroyed == []


def test_fail_job_redacts_secrets_in_last_error_c2_h1(fake_db: FakeFirestoreClient) -> None:
    _seed_event(fake_db, event_id="ev-redact")
    tok = claim_job(fake_db, "ev-redact", "searchJob", "w1")  # type: ignore[arg-type]
    fail_job(
        fake_db,  # type: ignore[arg-type]
        "ev-redact",
        "searchJob",
        tok,
        (
            "fail Bearer secret-jwt-xyz AIzaSyFakeKey1234567890 api_key=rawkey999 "
            'https://u:pass999@host/db?key=urlsecret888 {"api_key": "jsonsec111", "signature": "sigsec222"}'
        ),
    )
    doc = fake_db.collection("post_events").document("ev-redact").get().to_dict()
    assert doc is not None
    err_msg = doc["searchJob"]["lastError"]
    for leaked in (
        "secret-jwt-xyz",
        "AIzaSyFakeKey1234567890",
        "rawkey999",
        "pass999",
        "urlsecret888",
        "jsonsec111",
        "sigsec222",
    ):
        assert leaked not in err_msg


@pytest.mark.asyncio
async def test_sweep_abandoned_media_and_run_batch_sweep_c2_r5(
    monkeypatch: pytest.MonkeyPatch,
    fake_db: FakeFirestoreClient,
    test_settings_env: None,
) -> None:
    now = datetime.now(tz=timezone.utc)
    destroyed: List[str] = []
    monkeypatch.setattr(
        cloudinary.uploader,
        "destroy",
        lambda public_id, **_kw: destroyed.append(public_id) or {"result": "ok"},
    )

    # 1. Expired unposted draft (> 24h) -> MUST be cleaned up
    fake_db.collection("media_assets").document("asset-expired-draft").set(
        {
            "assetId": "asset-expired-draft",
            "ownerUid": "u1",
            "postId": "p-exp-draft",
            "cloudinaryPublicId": "lostlink/u1/asset-expired-draft",
            "status": "UPLOADING",
            "cleanupPending": False,
            "createdAt": now - timedelta(hours=25),
        }
    )
    # 2. Failed cleanup retry (< 24h, cleanupPending=True) -> MUST be retried and deleted
    fake_db.collection("media_assets").document("asset-pending-retry").set(
        {
            "assetId": "asset-pending-retry",
            "ownerUid": "u1",
            "postId": "p-pend",
            "cloudinaryPublicId": "lostlink/u1/asset-pending-retry",
            "status": "DELETING",
            "cleanupPending": True,
            "createdAt": now - timedelta(hours=1),
        }
    )
    # 3. Fresh draft (< 24h, cleanupPending=False) -> MUST stay UPLOADING
    fake_db.collection("media_assets").document("asset-fresh-draft").set(
        {
            "assetId": "asset-fresh-draft",
            "ownerUid": "u1",
            "postId": "p-fresh",
            "cloudinaryPublicId": "lostlink/u1/asset-fresh-draft",
            "status": "UPLOADING",
            "cleanupPending": False,
            "createdAt": now - timedelta(hours=1),
        }
    )
    # 4. Old asset (> 24h) attached to posts.mediaAssetIds -> MUST stay READY
    fake_db.collection("posts").document("p-old-att").set(
        {"authorId": "u1", "moderationStatus": "VISIBLE", "mediaAssetIds": ["asset-old-att"]}
    )
    fake_db.collection("media_assets").document("asset-old-att").set(
        {
            "assetId": "asset-old-att",
            "ownerUid": "u1",
            "postId": "p-old-att",
            "cloudinaryPublicId": "lostlink/u1/asset-old-att",
            "status": "READY",
            "cleanupPending": False,
            "createdAt": now - timedelta(hours=48),
        }
    )

    cleaned = await run_batch(fake_db, batch_size=10)  # type: ignore[arg-type]
    assert cleaned == 2
    assert set(destroyed) == {
        "lostlink/u1/asset-expired-draft",
        "lostlink/u1/asset-pending-retry",
    }
    assert (
        fake_db.collection("media_assets").document("asset-expired-draft").get().to_dict()["status"]  # type: ignore[index]
        == "DELETED"
    )
    assert (
        fake_db.collection("media_assets").document("asset-pending-retry").get().to_dict()["status"]  # type: ignore[index]
        == "DELETED"
    )
    assert (
        fake_db.collection("media_assets").document("asset-fresh-draft").get().to_dict()["status"]  # type: ignore[index]
        == "UPLOADING"
    )
    assert (
        fake_db.collection("media_assets").document("asset-old-att").get().to_dict()["status"]  # type: ignore[index]
        == "READY"
    )

    # 5. Many historical attached READY & DELETED docs MUST NOT starve newly expired drafts
    for idx in range(15):
        post_id = f"p-hist-att-{idx:02d}"
        att_id = f"asset-hist-att-{idx:02d}"
        del_id = f"asset-hist-del-{idx:02d}"
        fake_db.collection("posts").document(post_id).set(
            {"authorId": "u1", "moderationStatus": "VISIBLE", "mediaAssetIds": [att_id]}
        )
        fake_db.collection("media_assets").document(att_id).set(
            {
                "assetId": att_id,
                "ownerUid": "u1",
                "postId": post_id,
                "cloudinaryPublicId": f"lostlink/u1/{att_id}",
                "status": "READY",
                "cleanupPending": False,
                "createdAt": now - timedelta(hours=72 + idx),
            }
        )
        fake_db.collection("media_assets").document(del_id).set(
            {
                "assetId": del_id,
                "ownerUid": "u1",
                "postId": f"p-del-{idx:02d}",
                "cloudinaryPublicId": f"lostlink/u1/{del_id}",
                "status": "DELETED",
                "cleanupPending": False,
                "createdAt": now - timedelta(hours=96 + idx),
            }
        )

    fake_db.collection("media_assets").document("asset-new-orphan").set(
        {
            "assetId": "asset-new-orphan",
            "ownerUid": "u1",
            "postId": "p-new-orphan",
            "cloudinaryPublicId": "lostlink/u1/asset-new-orphan",
            "status": "UPLOADING",
            "cleanupPending": False,
            "createdAt": now - timedelta(hours=25),
        }
    )
    cleaned_small_budget = await run_batch(fake_db, batch_size=2)  # type: ignore[arg-type]
    assert cleaned_small_budget == 1
    assert "lostlink/u1/asset-new-orphan" in destroyed
    assert (
        fake_db.collection("media_assets").document("asset-new-orphan").get().to_dict()["status"]  # type: ignore[index]
        == "DELETED"
    )
    assert (
        fake_db.collection("media_assets").document("asset-hist-att-00").get().to_dict()["status"]  # type: ignore[index]
        == "READY"
    )


@pytest.mark.asyncio
async def test_signed_upload_window_reconciliation_and_attached_protection_c2_r5(
    monkeypatch: pytest.MonkeyPatch,
    fake_db: FakeFirestoreClient,
    test_settings_env: None,
) -> None:
    from app.worker.handlers import media_cleanup_handler as cleanup

    now = datetime.now(tz=timezone.utc)
    remote_assets: set[str] = set()
    destroyed: List[str] = []

    async def _destroy(public_id: str, _settings: Any) -> bool:
        destroyed.append(public_id)
        remote_assets.discard(public_id)
        return True

    monkeypatch.setattr(cleanup, "delete_cloudinary_asset", _destroy)

    # Fresh unattached asset inside signature window (now)
    fake_db.collection("media_assets").document("asset-window").set(
        {
            "assetId": "asset-window",
            "ownerUid": "u1",
            "postId": "p-win",
            "cloudinaryPublicId": "lostlink/u1/asset-window",
            "status": "DELETING",
            "cleanupPending": True,
            "createdAt": now,
        }
    )
    # Attached asset even with stale cleanupPending=True MUST NOT be destroyed
    fake_db.collection("posts").document("p-keep-att").set(
        {"authorId": "u1", "moderationStatus": "VISIBLE", "mediaAssetIds": ["asset-keep"]}
    )
    fake_db.collection("media_assets").document("asset-keep").set(
        {
            "assetId": "asset-keep",
            "ownerUid": "u1",
            "postId": "p-keep-att",
            "cloudinaryPublicId": "lostlink/u1/asset-keep",
            "status": "READY",
            "cleanupPending": True,
            "createdAt": now - timedelta(days=2),
        }
    )

    # 1. Initial cleanup returns not_found (empty remote store) -> keeps cleanupPending=True in window
    assert await cleanup.sweep_abandoned_media(fake_db, limit=10) == 1  # type: ignore[arg-type]
    assert destroyed == ["lostlink/u1/asset-window"]
    s1 = fake_db.collection("media_assets").document("asset-window").get().to_dict()
    assert s1 is not None
    assert s1["status"] == "DELETED"
    assert s1["cleanupPending"] is True
    assert s1["reconcileAttempts"] == 1
    assert s1["nextCleanupAt"] is not None

    # 2. In-flight signed upload lands later without /complete
    remote_assets.add("lostlink/u1/asset-window")
    # Immediate sweep waits for nextCleanupAt (no hot-loop), cleanupPending remains True
    assert await cleanup.sweep_abandoned_media(fake_db, limit=10) == 0  # type: ignore[arg-type]
    assert (
        fake_db.collection("media_assets").document("asset-window").get().to_dict()["cleanupPending"]  # type: ignore[index]
        is True
    )

    # 3. Advance to nextCleanupAt inside window -> destroys late remote upload even from DELETED state!
    fake_db.collection("media_assets").document("asset-window").update(
        {"nextCleanupAt": now - timedelta(seconds=1)}
    )
    assert await cleanup.sweep_abandoned_media(fake_db, limit=10) == 1  # type: ignore[arg-type]
    assert "lostlink/u1/asset-window" not in remote_assets
    s2 = fake_db.collection("media_assets").document("asset-window").get().to_dict()
    assert s2 is not None and s2["cleanupPending"] is True and s2["reconcileAttempts"] == 2

    # 4. Advance past signed-upload window (3600s) -> final reconcile closes cleanupPending=False
    fake_db.collection("media_assets").document("asset-window").update(
        {
            "createdAt": now - timedelta(seconds=4000),
            "nextCleanupAt": now - timedelta(seconds=1),
        }
    )
    assert await cleanup.sweep_abandoned_media(fake_db, limit=10) == 1  # type: ignore[arg-type]
    s3 = fake_db.collection("media_assets").document("asset-window").get().to_dict()
    assert s3 is not None and s3["status"] == "DELETED"
    assert s3["cleanupPending"] is False
    assert s3["nextCleanupAt"] is None
    assert (
        fake_db.collection("media_assets").document("asset-keep").get().to_dict()["status"]  # type: ignore[index]
        == "READY"
    )


@pytest.mark.asyncio
@pytest.mark.parametrize("batch_size", [1, 2])
async def test_abandoned_sweep_fair_rotation_under_continuous_backlog_c2_r4(
    monkeypatch: pytest.MonkeyPatch,
    fake_db: FakeFirestoreClient,
    test_settings_env: None,
    batch_size: int,
) -> None:
    from app.worker.handlers import media_cleanup_handler as cleanup
    from app.worker.runner import reset_worker_scheduler_for_tests

    reset_worker_scheduler_for_tests()
    now = datetime.now(tz=timezone.utc)
    destroyed: List[str] = []

    async def _destroy(public_id: str, _settings: Any) -> bool:
        destroyed.append(public_id)
        return True

    monkeypatch.setattr(cleanup, "delete_cloudinary_asset", _destroy)

    # Expired orphan waiting behind continuous searchJob + mediaCleanupJob backlog
    fake_db.collection("media_assets").document("orphan-fair").set(
        {
            "assetId": "orphan-fair",
            "ownerUid": "u1",
            "postId": "p-orphan",
            "cloudinaryPublicId": "lostlink/u1/orphan-fair",
            "status": "READY",
            "cleanupPending": False,
            "createdAt": now - timedelta(days=2),
        }
    )
    for i in range(12):
        _seed_event(fake_db, event_id=f"fair-ev-{i:02d}", post_id=f"p-fair-{i:02d}")

    def _ok(job_name: str):
        async def _run(db: Any, event_id: str, _data: Dict[str, Any], token: str) -> str:
            complete_job(db, event_id, job_name, token)
            return "DONE"

        return _run

    for _ in range(3):
        count = await run_batch(
            fake_db,  # type: ignore[arg-type]
            batch_size=batch_size,
            handlers={
                "searchJob": _ok("searchJob"),
                "mediaCleanupJob": _ok("mediaCleanupJob"),
            },
        )
        assert count <= batch_size

    assert destroyed == ["lostlink/u1/orphan-fair"]
