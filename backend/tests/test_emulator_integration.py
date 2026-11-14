"""
Stage 5 integration suite against REAL Firebase Auth & Firestore Emulators.

Opt-in via `LOSTLINK_RUN_EMULATOR_INTEGRATION=1` (executed inside
`firebase emulators:exec --only auth,firestore --project demo-lostlink ...`).
Never reads local `.env` or credential files and never targets cloud Firebase.
"""
from __future__ import annotations

from datetime import datetime, timedelta, timezone
import os
from typing import Any, Dict, List
from unittest.mock import patch

import cloudinary.utils
from fastapi.testclient import TestClient
from firebase_admin import auth as fb_auth
from google.cloud import firestore as _fs
from google.cloud.firestore_v1.vector import Vector
import httpx
import pytest

from app.core.config import Settings, get_settings

# Isolate configuration before importing app.main (which instantiates Settings at module level).
Settings.model_config["env_file"] = None
get_settings.cache_clear()

from app.core.firebase import get_firestore, init_firebase  # noqa: E402
from app.main import create_app  # noqa: E402
from app.services import media_service  # noqa: E402
from app.services.migration_service import run_backfill  # noqa: E402
from app.worker.runner import drain_until_idle  # noqa: E402

pytestmark = pytest.mark.skipif(
    os.environ.get("LOSTLINK_RUN_EMULATOR_INTEGRATION") != "1",
    reason="Emulator integration suite requires LOSTLINK_RUN_EMULATOR_INTEGRATION=1",
)

_API_KEY = "fake-emulator-key"
_PROJECT_ID = "demo-lostlink"


def _auth_host() -> str:
    return os.environ.get("FIREBASE_AUTH_EMULATOR_HOST", "127.0.0.1:9099").strip()


def _firestore_host() -> str:
    return os.environ.get("FIRESTORE_EMULATOR_HOST", "127.0.0.1:8088").strip()


def _clear_emulators() -> None:
    fs_url = f"http://{_firestore_host()}/emulator/v1/projects/{_PROJECT_ID}/databases/(default)/documents"
    auth_url = f"http://{_auth_host()}/emulator/v1/projects/{_PROJECT_ID}/accounts"
    with httpx.Client(timeout=10.0) as client:
        r1 = client.delete(fs_url)
        r1.raise_for_status()
        r2 = client.delete(auth_url)
        r2.raise_for_status()


def _create_emulator_user(
    email: str,
    *,
    password: str = "TestPass123!",
    email_verified: bool = True,
    disabled: bool = False,
    admin: bool = False,
) -> tuple[str, str]:
    """Create an Auth Emulator account and return `(uid, fresh_id_token)`."""
    base = f"http://{_auth_host()}/identitytoolkit.googleapis.com/v1"
    with httpx.Client(timeout=10.0) as client:
        sign_up = client.post(
            f"{base}/accounts:signUp?key={_API_KEY}",
            json={"email": email, "password": password, "returnSecureToken": True},
        )
        sign_up.raise_for_status()
        uid = sign_up.json()["localId"]

        client.post(
            f"{base}/accounts:update?key={_API_KEY}",
            headers={"Authorization": "Bearer owner"},
            json={
                "localId": uid,
                "emailVerified": email_verified,
                "disableUser": disabled,
            },
        ).raise_for_status()

        if admin:
            fb_auth.set_custom_user_claims(uid, {"admin": True})

        sign_in = client.post(
            f"{base}/accounts:signInWithPassword?key={_API_KEY}",
            json={"email": email, "password": password, "returnSecureToken": True},
        )
        sign_in.raise_for_status()
        return uid, sign_in.json()["idToken"]


@pytest.fixture
def emulator_app(monkeypatch: pytest.MonkeyPatch):
    """Initialize real Firebase Admin + Firestore Emulator with env_file disabled."""
    orig_env_file = Settings.model_config.get("env_file")
    Settings.model_config["env_file"] = None
    try:
        monkeypatch.setenv("APP_ENV", "test")
        monkeypatch.setenv("USE_FIREBASE_EMULATOR", "true")
        monkeypatch.setenv("FIREBASE_PROJECT_ID", _PROJECT_ID)
        monkeypatch.setenv("FIREBASE_AUTH_EMULATOR_HOST", _auth_host())
        monkeypatch.setenv("FIRESTORE_EMULATOR_HOST", _firestore_host())
        monkeypatch.setenv("CLOUDINARY_CLOUD_NAME", "demo-cloud")
        monkeypatch.setenv("CLOUDINARY_API_KEY", "demo-api-key")
        monkeypatch.setenv("CLOUDINARY_API_SECRET", "demo-api-secret")
        monkeypatch.setenv("CLOUDINARY_SIGNATURE_ALGORITHM", "sha256")
        monkeypatch.setenv("CLOUDINARY_UPLOAD_PRESET", "lostlink_signed_e2e")
        monkeypatch.setenv("BACKEND_API_KEY", "stage5-wake-secret-key")
        monkeypatch.setenv("GEMINI_API_KEY", "AIzaSyFakeStage5IntegrationKeyOnly123")
        monkeypatch.setenv("RUN_BOOT_RECOVERY", "false")
        get_settings.cache_clear()

        _clear_emulators()
        init_firebase()
        app = create_app()
        db = get_firestore()
        yield app, db
    finally:
        Settings.model_config["env_file"] = orig_env_file


def _seed_visible_post_with_event(
    db: Any,
    *,
    post_id: str,
    author_id: str,
    title: str = "Vi da den bi roi",
    status: str = "OPEN",
    moderation_status: str = "VISIBLE",
    media_asset_ids: List[str] | None = None,
    change_type: str = "CREATE",
    version: int = 1,
) -> None:
    pending_job = {
        "status": "PENDING",
        "attempts": 0,
        "claimToken": None,
        "lockOwner": None,
        "lockExpiresAt": None,
        "nextRetryAt": None,
        "lastError": None,
    }
    now = datetime.now(tz=timezone.utc)
    db.collection("posts").document(post_id).set(
        {
            "title": title,
            "type": "LOST",
            "categoryId": "wallet",
            "category": "Ví tiền",
            "description": "Mo ta hop le co email user@ut.edu.vn va so 0912345678 can duoc che PII.",
            "areaChoice": "KNOWN",
            "areaId": "library",
            "areaOtherText": "",
            "location": "Thư viện",
            "locationNote": "Tang 2",
            "occurredAt": "2026-11-12 09:00",
            "timeMode": "EXACT",
            "exactAtMillis": 1762912800000,
            "rangeStartMillis": None,
            "rangeEndMillis": None,
            "timeNote": "",
            "status": status,
            "moderationStatus": moderation_status,
            "version": version,
            "createdAt": now,
            "updatedAt": now,
            "authorId": author_id,
            "authorName": "Sinh vien Test",
            "contactEmail": "student@ut.edu.vn",
            "allowContact": True,
            "mediaAssetIds": media_asset_ids or [],
            "imageUrl": None,
            "imageUrls": [],
            "imagePublicId": None,
        }
    )
    db.collection("post_events").document(f"{post_id}_v{version}").set(
        {
            "postId": post_id,
            "authorId": author_id,
            "postVersion": version,
            "changeType": change_type,
            "createdAt": now,
            "imageAssetRefs": media_asset_ids or [],
            "searchJob": dict(pending_job),
            "notifyJob": dict(pending_job),
            "mediaCleanupJob": dict(pending_job),
        }
    )


def test_b0_t13_t07_real_auth_emulator_tokens_and_deleting_isolation(emulator_app) -> None:
    app, db = emulator_app
    with TestClient(app) as client:
        # 1. Missing and malformed Bearer tokens
        r_missing = client.post("/api/v1/media/uploads", json={"postId": "p-auth-1"})
        assert r_missing.status_code == 401
        assert r_missing.json()["code"] == "MISSING_TOKEN"
        assert r_missing.headers.get("X-Trace-Id")

        r_bad = client.post(
            "/api/v1/media/uploads",
            headers={"Authorization": "Bearer not-a-real-jwt"},
            json={"postId": "p-auth-1"},
        )
        assert r_bad.status_code == 401
        assert r_bad.json()["code"] == "INVALID_TOKEN"

        # 2. Unverified @ut.edu.vn account rejected (403)
        _, unv_token = _create_emulator_user("unverified@ut.edu.vn", email_verified=False)
        r_unv = client.post(
            "/api/v1/media/uploads",
            headers={"Authorization": f"Bearer {unv_token}"},
            json={"postId": "p-auth-2"},
        )
        assert r_unv.status_code == 403
        assert r_unv.json()["code"] == "EMAIL_NOT_VERIFIED"

        # 3. Non-@ut.edu.vn domain rejected (403)
        _, gmail_token = _create_emulator_user("student@gmail.com", email_verified=True)
        r_gmail = client.post(
            "/api/v1/media/uploads",
            headers={"Authorization": f"Bearer {gmail_token}"},
            json={"postId": "p-auth-3"},
        )
        assert r_gmail.status_code == 403
        assert r_gmail.json()["code"] == "DOMAIN_NOT_ALLOWED"

        # 4. Disabled Auth user rejected (403 USER_DISABLED) — obtain token while active, then disable user
        dis_uid, disabled_token = _create_emulator_user(
            "disabled@ut.edu.vn", email_verified=True, disabled=False
        )
        fb_auth.update_user(dis_uid, disabled=True)
        r_dis = client.post(
            "/api/v1/media/uploads",
            headers={"Authorization": f"Bearer {disabled_token}"},
            json={"postId": "p-auth-4"},
        )
        assert r_dis.status_code == 403
        assert r_dis.json()["code"] == "USER_DISABLED"

        # 5. B0-T07: DELETING account blocked from media/wake/search, allowed on /account/state & /deletion/resume
        del_uid, del_token = _create_emulator_user("deleting@ut.edu.vn", email_verified=True)
        db.collection("account_states").document(del_uid).set(
            {"uid": del_uid, "status": "DELETING", "updatedAt": _fs.SERVER_TIMESTAMP}
        )
        r_up_del = client.post(
            "/api/v1/media/uploads",
            headers={"Authorization": f"Bearer {del_token}"},
            json={"postId": "p-auth-5"},
        )
        assert r_up_del.status_code == 403
        assert r_up_del.json()["code"] == "ACCOUNT_INACTIVE"

        r_wake_del = client.post(
            "/api/v1/events/wake",
            headers={"Authorization": f"Bearer {del_token}"},
        )
        assert r_wake_del.status_code == 403

        r_acct = client.get(
            "/api/v1/account/state",
            headers={"Authorization": f"Bearer {del_token}"},
        )
        assert r_acct.status_code == 200
        assert r_acct.json()["status"] == "DELETING"

        r_resume = client.post(
            "/api/v1/account/deletion/resume",
            headers={"Authorization": f"Bearer {del_token}"},
        )
        assert r_resume.status_code == 404

        # 6. Verify unmounted future routes return 404 in B0 foundation
        _, student_token = _create_emulator_user("normal@ut.edu.vn", email_verified=True)
        r_admin_404 = client.get(
            "/api/v1/admin/posts",
            headers={"Authorization": f"Bearer {student_token}"},
        )
        assert r_admin_404.status_code == 404


def test_b0_t06_t11_signed_upload_and_complete_on_real_firestore_emulator(
    emulator_app, monkeypatch: pytest.MonkeyPatch
) -> None:
    app, db = emulator_app
    uid_a, token_a = _create_emulator_user("ownera@ut.edu.vn", email_verified=True)
    _, token_b = _create_emulator_user("otherb@ut.edu.vn", email_verified=True)

    with TestClient(app) as client:
        # Step 1: Student A reserves postId and receives signed Cloudinary upload_preset params
        res_ticket = client.post(
            "/api/v1/media/uploads",
            headers={"Authorization": f"Bearer {token_a}"},
            json={"postId": "draft-e2e-001"},
        )
        assert res_ticket.status_code == 200
        ticket = res_ticket.json()
        asset_id = ticket["assetId"]
        public_id = ticket["publicId"]
        params = ticket["params"]
        assert params["upload_preset"] == "lostlink_signed_e2e"
        assert "api_secret" not in params

        # Verify mathematical Cloudinary SHA-256 signature parity
        signable = {
            k: v
            for k, v in params.items()
            if k not in ("api_key", "signature")
        }
        expected_sig = cloudinary.utils.api_sign_request(
            signable, "demo-api-secret", algorithm="sha256"
        )
        assert params["signature"] == expected_sig

        # Check real Firestore docs written by backend transaction
        res_doc = db.collection("post_reservations").document("draft-e2e-001").get()
        assert res_doc.exists
        assert res_doc.to_dict()["ownerUid"] == uid_a
        asset_doc = db.collection("media_assets").document(asset_id).get()
        assert asset_doc.exists
        assert asset_doc.to_dict()["status"] == "UPLOADING"

        # Foreign student B blocked from reserving same postId
        res_foreign = client.post(
            "/api/v1/media/uploads",
            headers={"Authorization": f"Bearer {token_b}"},
            json={"postId": "draft-e2e-001"},
        )
        assert res_foreign.status_code == 403
        assert res_foreign.json()["code"] == "RESERVATION_CONFLICT"

        # Foreign student B blocked from completing A's asset
        res_foreign_complete = client.post(
            f"/api/v1/media/uploads/{asset_id}/complete",
            headers={"Authorization": f"Bearer {token_b}"},
        )
        assert res_foreign_complete.status_code == 403

        # Mock only external Cloudinary Admin HTTP endpoint while running real Firestore transaction
        def _cloudinary_admin_handler(request: httpx.Request) -> httpx.Response:
            assert request.method == "GET"
            assert public_id in str(request.url)
            return httpx.Response(
                200,
                json={
                    "asset_id": "cld-e2e-99",
                    "public_id": public_id,
                    "resource_type": "image",
                    "format": "jpg",
                    "bytes": 42000,
                    "secure_url": f"https://res.cloudinary.com/demo-cloud/image/upload/v1/{public_id}.jpg",
                },
            )

        real_async_client = httpx.AsyncClient
        monkeypatch.setattr(
            media_service.httpx,
            "AsyncClient",
            lambda *args, **kwargs: real_async_client(
                transport=httpx.MockTransport(_cloudinary_admin_handler)
            ),
        )

        # Step 2: Complete promotes media_assets/{assetId} to READY in real Firestore Emulator
        res_complete = client.post(
            f"/api/v1/media/uploads/{asset_id}/complete",
            headers={"Authorization": f"Bearer {token_a}"},
        )
        assert res_complete.status_code == 200
        assert res_complete.json()["status"] == "READY"
        assert db.collection("media_assets").document(asset_id).get().to_dict()["status"] == "READY"

        # Idempotent /complete call succeeds without extra failure
        res_idem = client.post(
            f"/api/v1/media/uploads/{asset_id}/complete",
            headers={"Authorization": f"Bearer {token_a}"},
        )
        assert res_idem.status_code == 200
        assert res_idem.json()["status"] == "READY"


def test_b0_t12_backfill_dry_run_and_idempotent_apply_on_real_emulator(emulator_app) -> None:
    _, db = emulator_app
    created_at = datetime(2026, 10, 1, 8, 30, tzinfo=timezone.utc)

    db.collection("posts").document("legacy-active-1").set(
        {
            "title": "Quen dien thoai",
            "type": "LOST",
            "categoryId": "phone",
            "description": "Dien thoai de quen o hoi truong",
            "areaId": "Auditorium",
            "status": "ACTIVE",
            "moderationStatus": "HIDDEN",
            "createdAt": created_at,
            "authorId": "student-legacy",
        }
    )

    dry_summary = run_backfill(db, apply=False, run_id="stage5-dry")
    assert dry_summary.scanned == 1
    assert dry_summary.would_update == 1
    assert dry_summary.updated == 0
    assert db.collection("posts").document("legacy-active-1").get().to_dict()["status"] == "ACTIVE"

    apply_1 = run_backfill(db, apply=True, run_id="stage5-apply-1")
    assert apply_1.updated == 1
    assert apply_1.preserved_hidden == 1

    migrated = db.collection("posts").document("legacy-active-1").get().to_dict()
    assert migrated["status"] == "OPEN"
    assert migrated["moderationStatus"] == "HIDDEN"
    assert migrated["categoryId"] == "electronics"
    assert migrated["areaId"] == "auditorium"
    assert migrated["version"] == 1
    assert migrated["createdAt"] == created_at

    apply_2 = run_backfill(db, apply=True, run_id="stage5-apply-2")
    assert apply_2.updated == 0
    assert apply_2.unchanged == 1
