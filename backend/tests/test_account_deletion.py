"""
tests/test_account_deletion.py — API and service tests for ST35 account deletion init & resume.
"""
from __future__ import annotations

from datetime import datetime, timedelta, timezone
from typing import Any, Dict, List

import firebase_admin.auth as fb_auth
from fastapi.testclient import TestClient
import pytest

from app.main import create_app
from tests.conftest import FakeFirestoreClient


def _mock_auth(
    monkeypatch: pytest.MonkeyPatch,
    tokens: Dict[str, Dict[str, Any]],
    *,
    revoke_calls: List[str] | None = None,
    revoke_error: Exception | None = None,
) -> None:
    def _verify(token: str, check_revoked: bool = False) -> Dict[str, Any]:
        assert check_revoked is True
        if token not in tokens:
            raise fb_auth.InvalidIdTokenError("unknown token")
        return dict(tokens[token])

    def _revoke(uid: str) -> None:
        if revoke_calls is not None:
            revoke_calls.append(uid)
        if revoke_error is not None:
            raise revoke_error

    monkeypatch.setattr(fb_auth, "verify_id_token", _verify)
    monkeypatch.setattr(fb_auth, "revoke_refresh_tokens", _revoke)


class _SpyWorkerLoop:
    def __init__(self) -> None:
        self.wakes = 0

    def wake(self) -> None:
        self.wakes += 1


def test_st35_initiate_locks_deleting_revokes_session_and_wakes_worker(
    monkeypatch: pytest.MonkeyPatch,
    fake_db: FakeFirestoreClient,
    test_settings_env: None,
) -> None:
    revoked: List[str] = []
    _mock_auth(
        monkeypatch,
        {"tok-1": {"uid": "u-del-1", "email": "sv1@ut.edu.vn", "email_verified": True}},
        revoke_calls=revoked,
    )

    app = create_app()
    spy = _SpyWorkerLoop()
    app.state.worker_loop = spy
    client = TestClient(app)
    headers = {"Authorization": "Bearer tok-1"}

    resp = client.post("/api/v1/account/deletion", headers=headers)
    assert resp.status_code == 202
    body = resp.json()
    assert body["uid"] == "u-del-1"
    assert body["status"] == "PENDING"
    assert body["steps"]["sessionRevoked"] == "DONE"
    assert body["steps"]["mediaAssets"]["status"] == "PENDING"
    assert body["steps"]["firestorePosts"]["status"] == "PENDING"
    assert revoked == ["u-del-1"]
    assert spy.wakes == 1

    # Account is now locked as DELETING
    state_doc = fake_db.collection("account_states").document("u-del-1").get().to_dict()
    assert state_doc["status"] == "DELETING"

    # Active-only routes immediately block this user
    r_session = client.get("/api/v1/session", headers=headers)
    assert r_session.status_code == 403
    assert r_session.json()["code"] == "ACCOUNT_INACTIVE"

    # Status route stays readable for DELETING user
    r_status = client.get("/api/v1/account/deletion", headers=headers)
    assert r_status.status_code == 200
    assert r_status.json()["status"] == "PENDING"


def test_st35_initiate_is_idempotent_and_preserves_progress(
    monkeypatch: pytest.MonkeyPatch,
    fake_db: FakeFirestoreClient,
    test_settings_env: None,
) -> None:
    revoked: List[str] = []
    _mock_auth(
        monkeypatch,
        {"tok-2": {"uid": "u-del-2", "email": "sv2@ut.edu.vn", "email_verified": True}},
        revoke_calls=revoked,
    )
    now = datetime.now(tz=timezone.utc)
    fake_db.collection("account_states").document("u-del-2").set({"status": "DELETING", "updatedAt": now})
    fake_db.collection("deletion_requests").document("u-del-2").set(
        {
            "uid": "u-del-2",
            "status": "IN_PROGRESS",
            "steps": {
                "sessionRevoked": "DONE",
                "firestorePosts": {"status": "DONE", "deleted": 4},
                "mediaAssets": {"status": "IN_PROGRESS", "deleted": 1, "failed": 0},
            },
            "createdAt": now,
            "updatedAt": now,
        }
    )

    client = TestClient(create_app())
    resp = client.post("/api/v1/account/deletion", headers={"Authorization": "Bearer tok-2"})
    assert resp.status_code == 202
    body = resp.json()
    assert body["status"] == "IN_PROGRESS"
    assert body["steps"]["firestorePosts"] == {"status": "DONE", "deleted": 4}
    assert revoked == []  # Already revoked earlier


def test_st35_revoke_failure_keeps_deleting_lock_and_resume_recovers(
    monkeypatch: pytest.MonkeyPatch,
    fake_db: FakeFirestoreClient,
    test_settings_env: None,
) -> None:
    revoked: List[str] = []
    _mock_auth(
        monkeypatch,
        {"tok-3": {"uid": "u-del-3", "email": "sv3@ut.edu.vn", "email_verified": True}},
        revoke_calls=revoked,
        revoke_error=RuntimeError("transient auth outage"),
    )

    app = create_app()
    spy = _SpyWorkerLoop()
    app.state.worker_loop = spy
    client = TestClient(app)
    headers = {"Authorization": "Bearer tok-3"}

    r1 = client.post("/api/v1/account/deletion", headers=headers)
    assert r1.status_code == 202
    assert r1.json()["steps"]["sessionRevoked"] == "FAILED"
    assert fake_db.collection("account_states").document("u-del-3").get().to_dict()["status"] == "DELETING"

    # Simulate failed worker state then resume with healthy Auth
    fake_db.collection("deletion_requests").document("u-del-3").update(
        {
            "status": "FAILED",
            "attempts": 3,
            "lastError": "cloudinary timeout",
            "nextRetryAt": datetime.now(tz=timezone.utc) + timedelta(minutes=10),
            "steps.firestorePosts": {"status": "DONE", "deleted": 2},
        }
    )
    _mock_auth(
        monkeypatch,
        {"tok-3": {"uid": "u-del-3", "email": "sv3@ut.edu.vn", "email_verified": True}},
        revoke_calls=revoked,
        revoke_error=None,
    )

    r2 = client.post("/api/v1/account/deletion/resume", headers=headers)
    assert r2.status_code == 202
    body2 = r2.json()
    assert body2["status"] == "PENDING"
    assert body2["steps"]["sessionRevoked"] == "DONE"
    assert body2["steps"]["firestorePosts"] == {"status": "DONE", "deleted": 2}
    assert body2["lastError"] is None
    doc = fake_db.collection("deletion_requests").document("u-del-3").get().to_dict()
    assert doc["attempts"] == 0
    assert doc["nextRetryAt"] is None
    assert spy.wakes == 2


def test_st35_conflict_when_already_deleted_and_404_resume_when_missing(
    monkeypatch: pytest.MonkeyPatch,
    fake_db: FakeFirestoreClient,
    test_settings_env: None,
) -> None:
    _mock_auth(
        monkeypatch,
        {
            "tok-done": {"uid": "u-done", "email": "done@ut.edu.vn", "email_verified": True},
            "tok-none": {"uid": "u-none", "email": "none@ut.edu.vn", "email_verified": True},
        },
    )
    fake_db.collection("account_states").document("u-done").set({"status": "DELETED"})

    client = TestClient(create_app())

    r_conflict = client.post("/api/v1/account/deletion", headers={"Authorization": "Bearer tok-done"})
    assert r_conflict.status_code == 409
    assert r_conflict.json()["code"] == "ACCOUNT_ALREADY_DELETED"

    r_missing = client.post("/api/v1/account/deletion/resume", headers={"Authorization": "Bearer tok-none"})
    assert r_missing.status_code == 404
    assert r_missing.json()["code"] == "DELETION_REQUEST_NOT_FOUND"
