"""
tests/test_account_deletion_worker.py — Worker tests for ST36 (Firestore chunked cleanup)
and ST37 (Cloudinary media_assets cleanup + legacy URL audit).
"""
from __future__ import annotations

from datetime import datetime, timedelta, timezone
from typing import Any, List

import firebase_admin.auth as fb_auth
import pytest

from app.services.account_deletion_service import initiate_account_deletion
from app.worker.handlers import account_deletion_handler as handler_mod
from app.worker.handlers.account_deletion_handler import (
    claim_deletion_request,
    process_one_account_deletion,
)
from app.worker.runner import drain_until_idle
from tests.conftest import FakeFirestoreClient


def _seed_user_footprint(db: FakeFirestoreClient, uid: str) -> None:
    now = datetime.now(tz=timezone.utc)
    db.collection("users").document(uid).set({"displayName": f"User {uid}", "email": f"{uid}@ut.edu.vn"})
    db.collection("account_states").document(uid).set({"status": "ACTIVE", "updatedAt": now})

    # 2 managed posts + 1 legacy post (no mediaAssetIds)
    for i in (1, 2):
        pid = f"{uid}-post-{i}"
        db.collection("posts").document(pid).set(
            {
                "authorId": uid,
                "title": f"Post {i}",
                "status": "OPEN",
                "moderationStatus": "VISIBLE",
                "mediaAssetIds": [f"{uid}-asset-{i}"],
                "version": 1,
                "createdAt": now,
            }
        )
        db.collection("post_embeddings").document(pid).set({"postId": pid, "vector": [0.1, 0.2]})
        db.collection("post_moderations").document(pid).set({"postId": pid, "authorId": uid})
        db.collection("media_assets").document(f"{uid}-asset-{i}").set(
            {
                "ownerUid": uid,
                "postId": pid,
                "status": "READY",
                "cloudinaryPublicId": f"lostlink/{uid}/img-{i}",
                "cleanupPending": False,
            }
        )

    legacy_pid = f"{uid}-legacy-post"
    db.collection("posts").document(legacy_pid).set(
        {
            "authorId": uid,
            "title": "Legacy post with raw URL",
            "status": "OPEN",
            "moderationStatus": "VISIBLE",
            "imageUrl": f"https://res.cloudinary.com/demo/image/upload/{uid}_old.jpg",
            "imagePublicId": f"legacy/{uid}_old",
            "version": 1,
            "createdAt": now,
        }
    )

    db.collection("post_reservations").document(f"{uid}-draft").set({"ownerUid": uid})
    db.collection("saved_posts").document(uid).collection("items").document("other-post-1").set({"savedAt": now})


@pytest.mark.asyncio
async def test_st36_st37_chunked_deletion_and_legacy_audit_preserves_other_users(
    fake_db: FakeFirestoreClient,
    monkeypatch: pytest.MonkeyPatch,
    test_settings_env: None,
) -> None:
    _seed_user_footprint(fake_db, "u-victim")
    _seed_user_footprint(fake_db, "u-other")

    revoked: List[str] = []
    deleted_auth: List[str] = []
    destroyed_public_ids: List[str] = []

    monkeypatch.setattr(fb_auth, "revoke_refresh_tokens", lambda u: revoked.append(u))
    monkeypatch.setattr(fb_auth, "delete_user", lambda u: deleted_auth.append(u))

    async def _fake_destroy(public_id: str, settings: Any) -> bool:
        destroyed_public_ids.append(public_id)
        return True

    monkeypatch.setattr(handler_mod, "delete_cloudinary_asset", _fake_destroy)

    await initiate_account_deletion(fake_db, "u-victim")

    # Run with chunk_size=2 to force multi-turn chunking across 3 posts & 2 assets
    turns = 0
    while turns < 10:
        did_work = await process_one_account_deletion(
            fake_db,
            uid="u-victim",
            worker_id="w-chunk",
            chunk_size=2,
        )
        if not did_work:
            break
        turns += 1

    assert turns >= 2  # Verified chunked progression across multiple turns

    # Final state assertions
    state_doc = fake_db.collection("account_states").document("u-victim").get().to_dict()
    assert state_doc["status"] == "DELETED"

    req_doc = fake_db.collection("deletion_requests").document("u-victim").get().to_dict()
    assert req_doc["status"] == "COMPLETED"
    assert req_doc["claimToken"] is None
    assert req_doc["steps"]["sessionRevoked"] == "DONE"
    assert req_doc["steps"]["firestorePosts"] == {"status": "DONE", "deleted": 3}
    assert req_doc["steps"]["mediaAssets"] == {"status": "DONE", "deleted": 2, "failed": 0}
    assert req_doc["steps"]["firestoreUserData"] == {"status": "DONE"}
    assert req_doc["steps"]["firebaseAuth"] == {"status": "DONE"}

    # Legacy audit recorded cleanly without touching Cloudinary for legacy URL
    audit = req_doc["steps"]["legacyMediaAudit"]
    assert audit["status"] == "DONE"
    assert audit["count"] == 1
    assert audit["legacyUrls"][0]["postId"] == "u-victim-legacy-post"
    assert "u-victim_old.jpg" in audit["legacyUrls"][0]["url"]

    # Victim Firestore data deleted + tombstones written
    assert not fake_db.collection("users").document("u-victim").get().exists
    assert not fake_db.collection("posts").document("u-victim-post-1").get().exists
    assert not fake_db.collection("post_embeddings").document("u-victim-post-1").get().exists
    assert fake_db.collection("post_tombstones").document("u-victim-post-1").get().exists
    assert not fake_db.collection("post_reservations").document("u-victim-draft").get().exists
    assert not fake_db.collection("saved_posts").document("u-victim").collection("items").document("other-post-1").get().exists

    # Victim media_assets marked DELETED and destroyed on Cloudinary
    assert sorted(destroyed_public_ids) == ["lostlink/u-victim/img-1", "lostlink/u-victim/img-2"]
    assert fake_db.collection("media_assets").document("u-victim-asset-1").get().to_dict()["status"] == "DELETED"
    assert fake_db.collection("media_assets").document("u-victim-asset-2").get().to_dict()["status"] == "DELETED"
    assert deleted_auth == ["u-victim"]

    # Foreign user is 100% untouched
    assert fake_db.collection("users").document("u-other").get().exists
    assert fake_db.collection("posts").document("u-other-post-1").get().exists
    assert fake_db.collection("media_assets").document("u-other-asset-1").get().to_dict()["status"] == "READY"
    assert fake_db.collection("account_states").document("u-other").get().to_dict()["status"] == "ACTIVE"


@pytest.mark.asyncio
async def test_st37_cloudinary_error_retries_without_deleting_auth_or_marking_done(
    fake_db: FakeFirestoreClient,
    monkeypatch: pytest.MonkeyPatch,
    test_settings_env: None,
) -> None:
    _seed_user_footprint(fake_db, "u-retry")
    monkeypatch.setattr(fb_auth, "revoke_refresh_tokens", lambda u: None)

    deleted_auth: List[str] = []
    monkeypatch.setattr(fb_auth, "delete_user", lambda u: deleted_auth.append(u))

    fail_once = {"flag": True}
    destroyed: List[str] = []

    async def _flaky_destroy(public_id: str, settings: Any) -> bool:
        if fail_once["flag"] and public_id.endswith("img-2"):
            fail_once["flag"] = False
            raise RuntimeError("Cloudinary 502 token=secret_xyz")
        destroyed.append(public_id)
        return True

    monkeypatch.setattr(handler_mod, "delete_cloudinary_asset", _flaky_destroy)

    await initiate_account_deletion(fake_db, "u-retry")

    # Turn 1 fails on img-2: must NOT delete auth or mark COMPLETED
    await process_one_account_deletion(fake_db, uid="u-retry", worker_id="w1")
    req1 = fake_db.collection("deletion_requests").document("u-retry").get().to_dict()
    assert req1["status"] == "PENDING"
    assert req1["attempts"] == 1
    assert req1["nextRetryAt"] is not None
    assert "secret_xyz" not in (req1.get("lastError") or "")
    assert req1["steps"]["mediaAssets"]["failed"] == 1
    assert req1["steps"]["mediaAssets"]["deleted"] == 1
    assert deleted_auth == []
    assert fake_db.collection("account_states").document("u-retry").get().to_dict()["status"] == "DELETING"

    # Simulate backoff elapsed and run via Worker runner `drain_until_idle`
    fake_db.collection("deletion_requests").document("u-retry").update(
        {"nextRetryAt": datetime.now(tz=timezone.utc) - timedelta(seconds=1)}
    )
    processed = await drain_until_idle(fake_db, max_batches=5, worker_id="w2")
    assert processed >= 1

    req2 = fake_db.collection("deletion_requests").document("u-retry").get().to_dict()
    assert req2["status"] == "COMPLETED"
    assert fake_db.collection("account_states").document("u-retry").get().to_dict()["status"] == "DELETED"
    assert deleted_auth == ["u-retry"]
    # img-1 was NOT destroyed a second time
    assert destroyed == ["lostlink/u-retry/img-1", "lostlink/u-retry/img-2"]


@pytest.mark.asyncio
async def test_st36_expired_lease_reclaim_resumes_from_saved_checkpoint(
    fake_db: FakeFirestoreClient,
    monkeypatch: pytest.MonkeyPatch,
    test_settings_env: None,
) -> None:
    _seed_user_footprint(fake_db, "u-reclaim")
    monkeypatch.setattr(fb_auth, "revoke_refresh_tokens", lambda u: None)
    deleted_auth: List[str] = []
    monkeypatch.setattr(fb_auth, "delete_user", lambda u: deleted_auth.append(u))

    async def _ok_destroy(public_id: str, settings: Any) -> bool:
        return True

    monkeypatch.setattr(handler_mod, "delete_cloudinary_asset", _ok_destroy)

    await initiate_account_deletion(fake_db, "u-reclaim")
    # Worker 1 claims and then crashes (lease expires)
    claim_deletion_request(fake_db, "u-reclaim", "w-crashed", lease_seconds=30)
    fake_db.collection("deletion_requests").document("u-reclaim").update(
        {"lockExpiresAt": datetime.now(tz=timezone.utc) - timedelta(seconds=5)}
    )

    # Worker 2 reclaims via runner
    processed = await drain_until_idle(fake_db, max_batches=5, worker_id="w-healthy")
    assert processed >= 1
    assert fake_db.collection("deletion_requests").document("u-reclaim").get().to_dict()["status"] == "COMPLETED"
    assert deleted_auth == ["u-reclaim"]
