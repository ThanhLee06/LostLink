"""Focused foundation regression tests; extended suite is archived locally."""


from __future__ import annotations


from typing import Any, Dict


import firebase_admin.auth as fb_auth


import pytest


from app.core.auth import get_account_status, require_active_student, require_verified_student, verify_token


from app.core.config import get_settings


from app.core.errors import ApiHTTPException


from app.core.firebase import get_firestore, init_firebase


from app.schemas.account import AccountStateStatus


from tests.conftest import FakeFirestoreClient


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("exc", "expected_status", "expected_code"),
    [
        (fb_auth.UserDisabledError("disabled"), 403, "USER_DISABLED"),
        (fb_auth.RevokedIdTokenError("revoked"), 401, "TOKEN_REVOKED"),
        (fb_auth.ExpiredIdTokenError("expired", cause=None), 401, "TOKEN_EXPIRED"),
        (fb_auth.InvalidIdTokenError("invalid"), 401, "INVALID_TOKEN"),
        (RuntimeError("unexpected backend error"), 401, "AUTH_FAILED"),
    ],
)
async def test_verify_token_maps_firebase_errors(
    monkeypatch: pytest.MonkeyPatch,
    exc: Exception,
    expected_status: int,
    expected_code: str,
) -> None:
    def _raise(*_args: Any, **_kwargs: Any) -> None:
        raise exc

    monkeypatch.setattr(fb_auth, "verify_id_token", _raise)
    with pytest.raises(ApiHTTPException) as err:
        await verify_token("bad-token")
    assert err.value.status_code == expected_status
    assert err.value.code == expected_code


@pytest.mark.asyncio
@pytest.mark.parametrize("bad_uid", [None, "", "   ", 123])
async def test_verify_token_rejects_missing_or_blank_uid(
    monkeypatch: pytest.MonkeyPatch,
    bad_uid: Any,
) -> None:
    monkeypatch.setattr(
        fb_auth,
        "verify_id_token",
        lambda *_args, **_kwargs: {"uid": bad_uid, "email": "sv@ut.edu.vn"},
    )
    with pytest.raises(ApiHTTPException) as err:
        await verify_token("tok")
    assert err.value.status_code == 401
    assert err.value.code == "INVALID_TOKEN"


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "malformed_payload",
    [
        {},
        {"status": None},
        {"status": ""},
        {"status": "   "},
        {"status": "CORRUPT_STATUS"},
        {"status": 123},
    ],
)
async def test_account_state_fails_closed_for_malformed_existing_doc_c2_r6(
    fake_db: FakeFirestoreClient,
    malformed_payload: Dict[str, Any],
) -> None:
    from app.core.auth import parse_account_state_snapshot

    # Missing document remains ACTIVE (legacy backward compat)
    missing_snap = fake_db.collection("account_states").document("missing-user").get()
    assert parse_account_state_snapshot(missing_snap, uid="missing-user") == AccountStateStatus.ACTIVE

    # Existing document missing/invalid status MUST fail closed as DELETING
    fake_db.collection("account_states").document("u-bad").set(malformed_payload)
    bad_snap = fake_db.collection("account_states").document("u-bad").get()
    assert parse_account_state_snapshot(bad_snap, uid="u-bad") == AccountStateStatus.DELETING
    assert get_account_status(fake_db, "u-bad") == AccountStateStatus.DELETING  # type: ignore[arg-type]

    claims = {"uid": "u-bad", "email": "ubad@ut.edu.vn", "email_verified": True}
    assert (await require_verified_student(claims))["uid"] == "u-bad"
    with pytest.raises(ApiHTTPException) as err:
        await require_active_student(claims)
    assert err.value.status_code == 403
    assert err.value.code == "ACCOUNT_INACTIVE"


def test_init_firebase_enforces_production_emulator_isolation(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    # Production + USE_FIREBASE_EMULATOR=true -> fail fast
    monkeypatch.setenv("APP_ENV", "production")
    monkeypatch.setenv("USE_FIREBASE_EMULATOR", "true")
    monkeypatch.delenv("FIREBASE_AUTH_EMULATOR_HOST", raising=False)
    monkeypatch.delenv("FIRESTORE_EMULATOR_HOST", raising=False)
    get_settings.cache_clear()
    with pytest.raises(RuntimeError, match="production"):
        init_firebase()

    # Production + emulator host env vars -> fail fast even with USE_FIREBASE_EMULATOR=false
    monkeypatch.setenv("APP_ENV", "production")
    monkeypatch.setenv("USE_FIREBASE_EMULATOR", "false")
    monkeypatch.setenv("FIREBASE_AUTH_EMULATOR_HOST", "127.0.0.1:9099")
    get_settings.cache_clear()
    with pytest.raises(RuntimeError, match="production"):
        init_firebase()

    # Non-production with USE_FIREBASE_EMULATOR=false + emulator env vars -> fail fast
    monkeypatch.setenv("APP_ENV", "development")
    monkeypatch.setenv("USE_FIREBASE_EMULATOR", "false")
    monkeypatch.delenv("FIREBASE_AUTH_EMULATOR_HOST", raising=False)
    monkeypatch.setenv("FIRESTORE_EMULATOR_HOST", "127.0.0.1:8080")
    get_settings.cache_clear()
    with pytest.raises(RuntimeError, match="USE_FIREBASE_EMULATOR=false"):
        init_firebase()


def test_init_firebase_emulator_succeeds_with_anonymous_credentials(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setenv("APP_ENV", "test")
    monkeypatch.setenv("USE_FIREBASE_EMULATOR", "true")
    monkeypatch.setenv("FIREBASE_PROJECT_ID", "demo-lostlink")
    monkeypatch.setenv("GOOGLE_APPLICATION_CREDENTIALS", "")
    monkeypatch.setenv("FIREBASE_AUTH_EMULATOR_HOST", "127.0.0.1:9099")
    monkeypatch.setenv("FIRESTORE_EMULATOR_HOST", "127.0.0.1:8080")
    get_settings.cache_clear()

    init_firebase()
    db = get_firestore()
    assert db.project == "demo-lostlink"
