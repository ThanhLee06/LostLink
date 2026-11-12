"""Focused foundation regression tests; extended suite is archived locally."""


from __future__ import annotations


from typing import Any, Dict, List


import cloudinary.uploader


import cloudinary.utils


import httpx


import pytest


from app.core.config import get_settings


from app.core.errors import ApiHTTPException


from app.schemas.media import MediaAssetStatus


from app.services import media_service


from app.services.media_service import complete_upload, generate_signed_upload


from tests.conftest import FakeFirestoreClient


def _patch_cloudinary_admin_response(
    monkeypatch: pytest.MonkeyPatch,
    status_code: int,
    payload: Dict[str, Any],
    calls: List[str] | None = None,
) -> None:
    def _handler(request: httpx.Request) -> httpx.Response:
        if calls is not None:
            calls.append(str(request.url))
        return httpx.Response(status_code=status_code, json=payload)

    transport = httpx.MockTransport(_handler)
    real_async_client = httpx.AsyncClient

    def _mock_client(*args: Any, **kwargs: Any) -> httpx.AsyncClient:
        kwargs["transport"] = transport
        return real_async_client(*args, **kwargs)

    monkeypatch.setattr(media_service.httpx, "AsyncClient", _mock_client)


@pytest.mark.asyncio
@pytest.mark.parametrize("algorithm", ["sha1", "sha256"])
async def test_generate_signed_upload_omits_max_bytes_and_signs_correctly(
    monkeypatch: pytest.MonkeyPatch,
    fake_db: FakeFirestoreClient,
    test_settings_env: None,
    algorithm: str,
) -> None:
    monkeypatch.setenv("CLOUDINARY_SIGNATURE_ALGORITHM", algorithm)
    get_settings.cache_clear()

    res = await generate_signed_upload(uid="uid-1", post_id="draft-post-1")
    assert res.public_id == f"lostlink/uid-1/{res.asset_id}"
    assert "max_bytes" not in res.params
    assert res.params["public_id"] == res.public_id
    assert res.params["allowed_formats"] == "jpg,png,webp"
    assert res.params["overwrite"] == "false"

    # Signature must match Cloudinary's algorithm over the signed subset
    expected_sign_subset = {
        k: v for k, v in res.params.items() if k not in ("api_key", "signature")
    }
    expected_sig = cloudinary.utils.api_sign_request(
        expected_sign_subset, "demo-api-secret", algorithm=algorithm
    )
    assert res.params["signature"] == expected_sig

    # Asset and reservation created in Firestore
    asset_doc = fake_db.collection("media_assets").document(res.asset_id).get().to_dict()
    assert asset_doc is not None
    assert asset_doc["ownerUid"] == "uid-1"
    assert asset_doc["postId"] == "draft-post-1"
    assert asset_doc["status"] == MediaAssetStatus.UPLOADING.value

    res_doc = fake_db.collection("post_reservations").document("draft-post-1").get().to_dict()
    assert res_doc is not None
    assert res_doc["ownerUid"] == "uid-1"


@pytest.mark.asyncio
async def test_generate_signed_upload_enforces_reservation_tombstone_and_post_owner(
    fake_db: FakeFirestoreClient,
    test_settings_env: None,
) -> None:
    # First user reserves draft-1
    await generate_signed_upload(uid="owner-1", post_id="draft-1")

    # Foreign user trying to reserve same draft-1 -> 403 RESERVATION_CONFLICT
    with pytest.raises(ApiHTTPException) as conflict_err:
        await generate_signed_upload(uid="attacker-2", post_id="draft-1")
    assert conflict_err.value.status_code == 403
    assert conflict_err.value.code == "RESERVATION_CONFLICT"

    # Tombstoned postId -> 409 POST_TOMBSTONED
    fake_db.collection("post_tombstones").document("deleted-post").set({"authorId": "owner-1"})
    with pytest.raises(ApiHTTPException) as tomb_err:
        await generate_signed_upload(uid="owner-1", post_id="deleted-post")
    assert tomb_err.value.status_code == 409
    assert tomb_err.value.code == "POST_TOMBSTONED"

    # Existing post owned by another user -> 403 FORBIDDEN
    fake_db.collection("posts").document("existing-post").set(
        {"authorId": "owner-1", "moderationStatus": "VISIBLE"}
    )
    with pytest.raises(ApiHTTPException) as non_owner_err:
        await generate_signed_upload(uid="other-user", post_id="existing-post")
    assert non_owner_err.value.status_code == 403
    assert non_owner_err.value.code == "FORBIDDEN"

    # Existing post HIDDEN -> 403 POST_HIDDEN even for owner
    fake_db.collection("posts").document("hidden-post").set(
        {"authorId": "owner-1", "moderationStatus": "HIDDEN"}
    )
    with pytest.raises(ApiHTTPException) as hidden_err:
        await generate_signed_upload(uid="owner-1", post_id="hidden-post")
    assert hidden_err.value.status_code == 403
    assert hidden_err.value.code == "POST_HIDDEN"


@pytest.mark.asyncio
async def test_complete_upload_ownership_state_and_idempotency(
    monkeypatch: pytest.MonkeyPatch,
    fake_db: FakeFirestoreClient,
    test_settings_env: None,
) -> None:
    signed = await generate_signed_upload(uid="uid-1", post_id="p-comp-1")

    # Foreign caller rejected 403
    with pytest.raises(ApiHTTPException) as err_owner:
        await complete_upload(signed.asset_id, caller_uid="uid-other")
    assert err_owner.value.status_code == 403
    assert err_owner.value.code == "FORBIDDEN"

    # DELETING asset rejected 409
    fake_db.collection("media_assets").document(signed.asset_id).update(
        {"status": MediaAssetStatus.DELETING.value}
    )
    with pytest.raises(ApiHTTPException) as err_del:
        await complete_upload(signed.asset_id, caller_uid="uid-1")
    assert err_del.value.status_code == 409
    assert err_del.value.code == "ASSET_DELETING"

    # Restore UPLOADING and mock valid Cloudinary resource
    fake_db.collection("media_assets").document(signed.asset_id).update(
        {"status": MediaAssetStatus.UPLOADING.value}
    )
    admin_calls: List[str] = []
    secure_url = f"https://res.cloudinary.com/demo-cloud/image/upload/v1/{signed.public_id}.jpg"
    _patch_cloudinary_admin_response(
        monkeypatch,
        200,
        {
            "public_id": signed.public_id,
            "asset_id": "cld-asset-999",
            "resource_type": "image",
            "format": "jpg",
            "bytes": 102400,
            "secure_url": secure_url,
        },
        calls=admin_calls,
    )

    completed = await complete_upload(signed.asset_id, caller_uid="uid-1")
    assert completed.status == MediaAssetStatus.READY
    assert completed.secure_url == secure_url
    assert len(admin_calls) == 1

    # Second call is idempotent and skips Cloudinary Admin API
    completed_again = await complete_upload(signed.asset_id, caller_uid="uid-1")
    assert completed_again.status == MediaAssetStatus.READY
    assert completed_again.secure_url == secure_url
    assert len(admin_calls) == 1


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "bad_resource",
    [
        {"resource_type": "image", "format": "png", "bytes": 6 * 1024 * 1024, "secure_url": "https://res.cloudinary.com/demo/x.png"},
        {"resource_type": "image", "format": "gif", "bytes": 2048, "secure_url": "https://res.cloudinary.com/demo/x.gif"},
        {"resource_type": "raw", "format": "jpg", "bytes": 2048, "secure_url": "https://res.cloudinary.com/demo/x.jpg"},
        {"resource_type": "image", "format": "jpg", "bytes": 2048, "secure_url": "http://insecure/x.jpg"},
    ],
)
async def test_complete_upload_rejects_invalid_media_and_destroys_orphan(
    monkeypatch: pytest.MonkeyPatch,
    fake_db: FakeFirestoreClient,
    test_settings_env: None,
    bad_resource: Dict[str, Any],
) -> None:
    signed = await generate_signed_upload(uid="uid-1", post_id="p-bad-media")
    _patch_cloudinary_admin_response(
        monkeypatch,
        200,
        {"public_id": signed.public_id, "asset_id": "cld-bad", **bad_resource},
    )

    destroyed: List[str] = []

    def _fake_destroy(public_id: str, **_kwargs: Any) -> Dict[str, str]:
        destroyed.append(public_id)
        return {"result": "ok"}

    monkeypatch.setattr(cloudinary.uploader, "destroy", _fake_destroy)

    with pytest.raises(ApiHTTPException) as err:
        await complete_upload(signed.asset_id, caller_uid="uid-1")
    assert err.value.status_code == 422
    assert err.value.code == "INVALID_MEDIA_ASSET"
    assert destroyed == [signed.public_id]
    # Remains UPLOADING (never marked READY)
    doc = fake_db.collection("media_assets").document(signed.asset_id).get().to_dict()
    assert doc is not None and doc["status"] == MediaAssetStatus.UPLOADING.value


@pytest.mark.asyncio
@pytest.mark.parametrize("midflight_state", [{"status": "DELETING"}, {}])
async def test_generate_signed_upload_rechecks_account_state_in_txn_c2_r2_r6(
    fake_db: FakeFirestoreClient,
    test_settings_env: None,
    midflight_state: Dict[str, Any],
) -> None:
    def _flip_account_state() -> None:
        fake_db._raw_set("account_states", "uid-race", midflight_state)

    fake_db.before_commit_hook = _flip_account_state

    with pytest.raises(ApiHTTPException) as err:
        await generate_signed_upload(uid="uid-race", post_id="draft-race")
    assert err.value.status_code == 403
    assert err.value.code == "ACCOUNT_INACTIVE"
    assert list(fake_db.collection("media_assets").stream()) == []
    assert list(fake_db.collection("post_reservations").stream()) == []


@pytest.mark.asyncio
async def test_ready_complete_enforces_authoritative_checks_without_admin_http_c2_r2(
    monkeypatch: pytest.MonkeyPatch,
    fake_db: FakeFirestoreClient,
    test_settings_env: None,
) -> None:
    from datetime import datetime, timezone

    admin_calls: List[str] = []
    destroyed: List[str] = []

    _patch_cloudinary_admin_response(monkeypatch, 500, {}, calls=admin_calls)
    monkeypatch.setattr(
        cloudinary.uploader,
        "destroy",
        lambda public_id, **_kw: destroyed.append(public_id) or {"result": "ok"},
    )

    def _seed_ready(asset_id: str, post_id: str) -> str:
        pid = f"lostlink/uid-1/{asset_id}"
        fake_db.collection("media_assets").document(asset_id).set(
            {
                "assetId": asset_id,
                "ownerUid": "uid-1",
                "postId": post_id,
                "cloudinaryPublicId": pid,
                "status": MediaAssetStatus.READY.value,
                "cleanupPending": False,
                "secureUrl": f"https://res.cloudinary.com/demo-cloud/image/upload/{pid}.jpg",
                "createdAt": datetime.now(tz=timezone.utc),
            }
        )
        return pid

    # 1. READY + HIDDEN post (unattached OR attached) -> 403 POST_HIDDEN, NO destroy, NO Admin HTTP
    _seed_ready("ready-hidden-unatt", "p-hid-1")
    fake_db.collection("posts").document("p-hid-1").set(
        {"authorId": "uid-1", "moderationStatus": "HIDDEN", "mediaAssetIds": []}
    )
    with pytest.raises(ApiHTTPException) as err_hid:
        await complete_upload("ready-hidden-unatt", caller_uid="uid-1")
    assert err_hid.value.status_code == 403
    assert err_hid.value.code == "POST_HIDDEN"
    doc_hid = fake_db.collection("media_assets").document("ready-hidden-unatt").get().to_dict()
    assert doc_hid is not None and doc_hid["status"] == MediaAssetStatus.READY.value
    assert destroyed == []
    assert admin_calls == []

    # 2. READY + Tombstoned post while ALREADY attached -> 409 POST_TOMBSTONED, NO destroy
    _seed_ready("ready-tomb-att", "p-tomb-att")
    fake_db.collection("posts").document("p-tomb-att").set(
        {
            "authorId": "uid-1",
            "moderationStatus": "VISIBLE",
            "mediaAssetIds": ["ready-tomb-att"],
        }
    )
    fake_db.collection("post_tombstones").document("p-tomb-att").set({"authorId": "uid-1"})
    with pytest.raises(ApiHTTPException) as err_tomb_att:
        await complete_upload("ready-tomb-att", caller_uid="uid-1")
    assert err_tomb_att.value.status_code == 409
    assert err_tomb_att.value.code == "POST_TOMBSTONED"
    assert destroyed == []

    # 3. READY + Tombstoned unattached -> 409 + destroyed
    pid_tomb_unatt = _seed_ready("ready-tomb-unatt", "p-tomb-unatt")
    fake_db.collection("post_tombstones").document("p-tomb-unatt").set({"authorId": "uid-1"})
    with pytest.raises(ApiHTTPException) as err_tomb_unatt:
        await complete_upload("ready-tomb-unatt", caller_uid="uid-1")
    assert err_tomb_unatt.value.status_code == 409
    assert destroyed == [pid_tomb_unatt]

    # 4. READY + Deleted target (no post, no reservation) -> 409 POST_DELETED + destroyed
    pid_del = _seed_ready("ready-post-del", "p-gone")
    with pytest.raises(ApiHTTPException) as err_del:
        await complete_upload("ready-post-del", caller_uid="uid-1")
    assert err_del.value.status_code == 409
    assert err_del.value.code == "POST_DELETED"
    assert destroyed == [pid_tomb_unatt, pid_del]
    assert admin_calls == []


@pytest.mark.asyncio
async def test_failed_late_complete_rearms_durable_cleanup_and_recovers_c2_r5(
    monkeypatch: pytest.MonkeyPatch,
    fake_db: FakeFirestoreClient,
    test_settings_env: None,
) -> None:
    from datetime import datetime, timedelta, timezone
    from app.worker.handlers import media_cleanup_handler as cleanup

    now = datetime.now(tz=timezone.utc)
    fake_db.collection("media_assets").document("late-err-1").set(
        {
            "assetId": "late-err-1",
            "ownerUid": "uid-1",
            "postId": "p-late-err",
            "cloudinaryPublicId": "lostlink/uid-1/late-err-1",
            "status": MediaAssetStatus.DELETED.value,
            "cleanupPending": False,
            "createdAt": now - timedelta(days=2),
            "secureUrl": "https://res.cloudinary.com/demo-cloud/image/upload/late-err-1.jpg",
        }
    )

    async def _outage(*_a: Any, **_k: Any) -> bool:
        raise RuntimeError("synthetic Cloudinary outage")

    monkeypatch.setattr(cleanup, "delete_cloudinary_asset", _outage)
    with pytest.raises(ApiHTTPException) as err:
        await complete_upload("late-err-1", caller_uid="uid-1")
    assert err.value.status_code == 409

    after_fail = fake_db.collection("media_assets").document("late-err-1").get().to_dict()
    assert after_fail is not None
    assert after_fail["status"] == MediaAssetStatus.DELETING.value
    assert after_fail["cleanupPending"] is True
    assert after_fail["cleanupAttempts"] == 1
    assert after_fail["nextCleanupAt"] is not None

    # Outage clears, but backoff is respected until nextCleanupAt arrives
    destroyed: List[str] = []

    async def _recover(public_id: str, _settings: Any) -> bool:
        destroyed.append(public_id)
        return True

    monkeypatch.setattr(cleanup, "delete_cloudinary_asset", _recover)
    assert await cleanup.sweep_abandoned_media(fake_db, limit=10) == 0  # type: ignore[arg-type]

    # Time advances past nextCleanupAt -> worker cleans up & closes durable record
    fake_db.collection("media_assets").document("late-err-1").update(
        {"nextCleanupAt": now - timedelta(seconds=1)}
    )
    assert await cleanup.sweep_abandoned_media(fake_db, limit=10) == 1  # type: ignore[arg-type]
    assert destroyed == ["lostlink/uid-1/late-err-1"]
    final_doc = fake_db.collection("media_assets").document("late-err-1").get().to_dict()
    assert final_doc is not None
    assert final_doc["status"] == MediaAssetStatus.DELETED.value
    assert final_doc["cleanupPending"] is False
    assert final_doc["nextCleanupAt"] is None


@pytest.mark.asyncio
async def test_api_cleanup_preserves_window_past_expiry_and_enforces_guards_c2_r5(
    monkeypatch: pytest.MonkeyPatch,
    fake_db: FakeFirestoreClient,
    test_settings_env: None,
) -> None:
    from datetime import datetime, timedelta, timezone
    from app.worker.handlers import media_cleanup_handler as cleanup

    now = datetime.now(tz=timezone.utc)
    remote_assets: set[str] = set()
    destroy_calls: List[str] = []

    def _destroy_sync(public_id: str, **_kwargs: Any) -> Dict[str, str]:
        destroy_calls.append(public_id)
        existed = public_id in remote_assets
        remote_assets.discard(public_id)
        return {"result": "ok" if existed else "not found"}

    monkeypatch.setattr(cloudinary.uploader, "destroy", _destroy_sync)

    # 1. Late /complete inside window preserves worker reconciliation even when destroy returns not found
    win_id = "late-win-1"
    win_pid = f"lostlink/uid-1/{win_id}"
    fake_db.collection("media_assets").document(win_id).set(
        {
            "assetId": win_id,
            "ownerUid": "uid-1",
            "postId": "p-win-1",
            "cloudinaryPublicId": win_pid,
            "status": MediaAssetStatus.DELETING.value,
            "cleanupPending": True,
            "createdAt": now,
        }
    )

    # Foreign caller rejected without mutating asset or calling destroy
    with pytest.raises(ApiHTTPException) as err_foreign:
        await complete_upload(win_id, caller_uid="uid-other")
    assert err_foreign.value.status_code == 403
    assert destroy_calls == []

    # Worker runs first -> keeps reconciliation window pending
    assert await cleanup.sweep_abandoned_media(fake_db, limit=10) == 1  # type: ignore[arg-type]
    after_worker = fake_db.collection("media_assets").document(win_id).get().to_dict()
    assert after_worker is not None and after_worker["cleanupPending"] is True
    assert after_worker["reconcileAttempts"] == 1

    # Owner calls late /complete -> 409 ASSET_DELETING, destroy returns not_found, window MUST stay open
    with pytest.raises(ApiHTTPException) as err_api:
        await complete_upload(win_id, caller_uid="uid-1")
    assert err_api.value.status_code == 409
    assert err_api.value.code == "ASSET_DELETING"
    after_api = fake_db.collection("media_assets").document(win_id).get().to_dict()
    assert after_api is not None
    assert after_api["status"] == MediaAssetStatus.DELETED.value
    assert after_api["cleanupPending"] is True
    assert after_api["reconcileAttempts"] == 2
    assert after_api["nextCleanupAt"] is not None

    # Signed upload lands late without client ACK -> next worker sweep destroys remote upload
    remote_assets.add(win_pid)
    fake_db.collection("media_assets").document(win_id).update(
        {"nextCleanupAt": now - timedelta(seconds=1)}
    )
    assert await cleanup.sweep_abandoned_media(fake_db, limit=10) == 1  # type: ignore[arg-type]
    assert win_pid not in remote_assets

    # 2. Past signed-upload window (> 3600s) -> API destroy ("ok") closes cleanupPending=False
    exp_id = "late-exp-1"
    exp_pid = f"lostlink/uid-1/{exp_id}"
    remote_assets.add(exp_pid)
    fake_db.collection("media_assets").document(exp_id).set(
        {
            "assetId": exp_id,
            "ownerUid": "uid-1",
            "postId": "p-exp-1",
            "cloudinaryPublicId": exp_pid,
            "status": MediaAssetStatus.DELETED.value,
            "cleanupPending": True,
            "createdAt": now - timedelta(seconds=3605),
        }
    )
    with pytest.raises(ApiHTTPException) as err_exp_late:
        await complete_upload(exp_id, caller_uid="uid-1")
    assert err_exp_late.value.status_code == 409
    assert exp_pid not in remote_assets
    after_exp = fake_db.collection("media_assets").document(exp_id).get().to_dict()
    assert after_exp is not None
    assert after_exp["status"] == MediaAssetStatus.DELETED.value
    assert after_exp["cleanupPending"] is False
    assert after_exp["nextCleanupAt"] is None

    # 3. Shared finalizer guards: owner mismatch, binding mismatch, and post attachment
    att_id = "guard-att-1"
    fake_db.collection("posts").document("p-guard").set(
        {"authorId": "uid-1", "moderationStatus": "VISIBLE", "mediaAssetIds": [att_id]}
    )
    fake_db.collection("media_assets").document(att_id).set(
        {
            "assetId": att_id,
            "ownerUid": "uid-1",
            "postId": "p-guard",
            "cloudinaryPublicId": "lostlink/uid-1/guard-att-1",
            "status": MediaAssetStatus.READY.value,
            "cleanupPending": True,
            "nextCleanupAt": now,
            "createdAt": now,
        }
    )
    # Wrong owner or wrong post/publicId binding refuses to mutate
    cleanup._mark_asset_deleted_sync(
        fake_db,  # type: ignore[arg-type]
        asset_id=att_id,
        author_id="uid-other",
        expected_post_id="p-guard",
        expected_public_id="lostlink/uid-1/guard-att-1",
    )
    cleanup._mark_asset_deleted_sync(
        fake_db,  # type: ignore[arg-type]
        asset_id=att_id,
        author_id="uid-1",
        expected_post_id="p-wrong",
        expected_public_id="lostlink/uid-1/guard-att-1",
    )
    cleanup._mark_asset_deleted_sync(
        fake_db,  # type: ignore[arg-type]
        asset_id=att_id,
        author_id="uid-1",
        expected_post_id="p-guard",
        expected_public_id="lostlink/uid-1/other-pid",
    )
    # Defer-retry path enforces the same owner/binding/attachment guards
    cleanup._defer_asset_cleanup_retry_sync(
        fake_db,  # type: ignore[arg-type]
        asset_id=att_id,
        author_id="uid-other",
        expected_post_id="p-guard",
        expected_public_id="lostlink/uid-1/guard-att-1",
        error_message="boom",
    )
    still_att = fake_db.collection("media_assets").document(att_id).get().to_dict()
    assert still_att is not None and still_att["cleanupPending"] is True
    assert still_att["status"] == MediaAssetStatus.READY.value

    # Attached asset with valid owner & binding clears cleanupPending without marking DELETED
    cleanup._mark_asset_deleted_sync(
        fake_db,  # type: ignore[arg-type]
        asset_id=att_id,
        author_id="uid-1",
        expected_post_id="p-guard",
        expected_public_id="lostlink/uid-1/guard-att-1",
    )
    cleared_att = fake_db.collection("media_assets").document(att_id).get().to_dict()
    assert cleared_att is not None
    assert cleared_att["status"] == MediaAssetStatus.READY.value
    assert cleared_att["cleanupPending"] is False
    assert cleared_att["nextCleanupAt"] is None

    # Re-seed pending attached asset to confirm defer-retry also clears rather than marking DELETING
    fake_db.collection("media_assets").document(att_id).update(
        {"cleanupPending": True, "nextCleanupAt": now}
    )
    cleanup._defer_asset_cleanup_retry_sync(
        fake_db,  # type: ignore[arg-type]
        asset_id=att_id,
        author_id="uid-1",
        expected_post_id="p-guard",
        expected_public_id="lostlink/uid-1/guard-att-1",
        error_message="transient error",
    )
    cleared_retry_att = fake_db.collection("media_assets").document(att_id).get().to_dict()
    assert cleared_retry_att is not None
    assert cleared_retry_att["status"] == MediaAssetStatus.READY.value
    assert cleared_retry_att["cleanupPending"] is False
    assert cleared_retry_att["nextCleanupAt"] is None
