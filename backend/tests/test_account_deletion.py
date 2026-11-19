"""ST35 — account deletion initiation: re-auth, DELETING lock, idempotent job, revoke, hide posts."""
from __future__ import annotations

import time
from datetime import datetime, timezone
from typing import Any, Dict, List

import firebase_admin.auth as fb_auth
import pytest
from fastapi.testclient import TestClient

from app.main import create_app
from tests.conftest import FakeFirestoreClient

UID = "u-del"
EMAIL = "deleter@ut.edu.vn"


def _token(auth_age_seconds: float | None = 10, **overrides: Any) -> Dict[str, Any]:
    tok: Dict[str, Any] = {"uid": UID, "email": EMAIL, "email_verified": True}
    if auth_age_seconds is not None:
        tok["auth_time"] = int(time.time() - auth_age_seconds)
    tok.update(overrides)
    return tok


@pytest.fixture
def auth_setup(monkeypatch: pytest.MonkeyPatch) -> Dict[str, Any]:
    state: Dict[str, Any] = {"revoked": [], "revoke_error": None}
    tokens = {
        "fresh": _token(10),
        "stale": _token(3600),
        "no-auth-time": _token(None),
        "future": _token(-3600),
        "other": {"uid": "u-other", "email": "other@ut.edu.vn", "email_verified": True,
                  "auth_time": int(time.time())},
    }

    def _verify(token: str, check_revoked: bool = False) -> Dict[str, Any]:
        assert check_revoked is True
        if token not in tokens:
            raise fb_auth.InvalidIdTokenError("unknown test token")
        return dict(tokens[token])

    def _revoke(uid: str, app: Any = None) -> None:
        if state["revoke_error"] is not None:
            raise state["revoke_error"]
        state["revoked"].append(uid)

    monkeypatch.setattr(fb_auth, "verify_id_token", _verify)
    monkeypatch.setattr(fb_auth, "revoke_refresh_tokens", _revoke)
    return state


def _seed_post(db: FakeFirestoreClient, post_id: str, author: str, *, mod: str = "VISIBLE",
               version: int = 1) -> None:
    now = datetime.now(tz=timezone.utc)
    db.collection("posts").document(post_id).set(
        {"authorId": author, "moderationStatus": mod, "status": "OPEN", "version": version,
         "createdAt": now, "updatedAt": now, "title": "t", "mediaAssetIds": []}
    )


def _post(client: TestClient, token: str = "fresh", body: Dict[str, Any] | None = None):
    return client.post(
        "/api/v1/account/deletion",
        json={"confirm": True} if body is None else body,
        headers={"Authorization": f"Bearer {token}"},
    )


@pytest.mark.parametrize("token", ["stale", "no-auth-time", "future"])
def test_requires_recent_reauthentication(
    fake_db: FakeFirestoreClient, test_settings_env: None, auth_setup: Dict[str, Any], token: str
) -> None:
    client = TestClient(create_app())
    resp = _post(client, token)
    assert resp.status_code == 403
    assert resp.json()["code"] == "RECENT_AUTH_REQUIRED"
    assert fake_db.collection("account_states").document(UID).get().exists is False
    assert fake_db.collection("deletion_requests").document(UID).get().exists is False
    assert auth_setup["revoked"] == []


def test_requires_explicit_confirmation_and_auth(
    fake_db: FakeFirestoreClient, test_settings_env: None, auth_setup: Dict[str, Any]
) -> None:
    client = TestClient(create_app())
    assert client.post("/api/v1/account/deletion", json={"confirm": True}).status_code == 401
    resp = _post(client, body={"confirm": False})
    assert resp.status_code == 400
    assert resp.json()["code"] == "DELETION_NOT_CONFIRMED"
    assert _post(client, body={}).status_code == 422
    assert _post(client, body={"confirm": True, "uid": "victim"}).status_code == 422
    assert fake_db.collection("account_states").document(UID).get().exists is False


def test_locks_deleting_creates_job_hides_posts_and_revokes(
    fake_db: FakeFirestoreClient, test_settings_env: None, auth_setup: Dict[str, Any]
) -> None:
    _seed_post(fake_db, "p-open", UID, version=3)
    _seed_post(fake_db, "p-hidden", UID, mod="HIDDEN", version=2)
    _seed_post(fake_db, "p-foreign", "u-other")
    fake_db.collection("post_embeddings").document("p-open").set(
        {"moderationStatus": "VISIBLE", "status": "OPEN"}
    )

    client = TestClient(create_app())
    resp = _post(client, body={"confirm": True, "reason": "  graduated  "})
    assert resp.status_code == 202, resp.text
    body = resp.json()
    assert body["accountStatus"] == "DELETING"
    assert body["created"] is True
    assert body["deletionStatus"] == "QUEUED"
    assert body["steps"]["postsHidden"]["status"] == "DONE"
    assert body["steps"]["postsHidden"]["hiddenCount"] == 1
    assert body["steps"]["sessionsRevoked"]["status"] == "DONE"

    acct = fake_db.collection("account_states").document(UID).get().to_dict()
    assert acct["status"] == "DELETING"
    job = fake_db.collection("deletion_requests").document(UID).get().to_dict()
    assert job["requestId"] == body["requestId"]
    assert job["reason"] == "graduated"
    assert job["cleanupJob"]["status"] == "PENDING"
    assert job["emailHint"] == "de***@ut.edu.vn"
    assert auth_setup["revoked"] == [UID]

    # Hidden from feed/search/matching (all filter moderationStatus == VISIBLE)
    p_open = fake_db.collection("posts").document("p-open").get().to_dict()
    assert p_open["moderationStatus"] == "HIDDEN"
    assert p_open["version"] == 4
    mod = fake_db.collection("post_moderations").document("p-open").get().to_dict()
    assert mod["reason"] == "ACCOUNT_DELETING" and mod["authorId"] == UID
    ev = fake_db.collection("post_events").document("p-open_v4").get().to_dict()
    assert ev["changeType"] == "MODERATION"
    assert ev["searchJob"]["status"] == "PENDING"
    assert ev["notifyJob"]["status"] == "SKIPPED"
    assert ev["mediaCleanupJob"]["status"] == "SKIPPED"
    emb = fake_db.collection("post_embeddings").document("p-open").get().to_dict()
    assert emb["moderationStatus"] == "HIDDEN"

    # Already-hidden & foreign posts untouched
    assert fake_db.collection("posts").document("p-hidden").get().to_dict()["version"] == 2
    assert fake_db.collection("post_moderations").document("p-hidden").get().exists is False
    assert fake_db.collection("posts").document("p-foreign").get().to_dict()["moderationStatus"] == "VISIBLE"

    # Account is now blocked from ACTIVE-only routes but can read its deletion state
    assert client.get("/api/v1/session", headers={"Authorization": "Bearer fresh"}).json()["code"] == "ACCOUNT_INACTIVE"
    state = client.get("/api/v1/account/state", headers={"Authorization": "Bearer fresh"})
    assert state.status_code == 200 and state.json()["status"] == "DELETING"
    progress = client.get("/api/v1/account/deletion", headers={"Authorization": "Bearer fresh"})
    assert progress.status_code == 200 and progress.json()["status"] == "QUEUED"


def test_resubmission_is_idempotent(
    fake_db: FakeFirestoreClient, test_settings_env: None, auth_setup: Dict[str, Any]
) -> None:
    _seed_post(fake_db, "p1", UID)
    client = TestClient(create_app())
    first = _post(client)
    assert first.status_code == 202
    second = _post(client)
    assert second.status_code == 200
    assert second.json()["created"] is False
    assert second.json()["requestId"] == first.json()["requestId"]
    assert auth_setup["revoked"] == [UID]  # not revoked again
    events: List[str] = [eid for eid, _ in fake_db._snapshot_collection("post_events")]
    assert events == ["p1_v2"]


def test_failed_step_keeps_lock_and_retry_resumes(
    fake_db: FakeFirestoreClient, test_settings_env: None, auth_setup: Dict[str, Any]
) -> None:
    _seed_post(fake_db, "p1", UID)
    auth_setup["revoke_error"] = RuntimeError("auth backend down")
    client = TestClient(create_app())

    failed = _post(client)
    assert failed.status_code == 503
    assert failed.json()["code"] == "DELETION_STEP_FAILED"
    assert "auth backend down" not in failed.text
    # Lock is already in place even though a step failed
    assert fake_db.collection("account_states").document(UID).get().to_dict()["status"] == "DELETING"
    job = fake_db.collection("deletion_requests").document(UID).get().to_dict()
    assert job["status"] == "LOCKING"
    assert job["steps"]["postsHidden"]["status"] == "DONE"
    assert job["steps"]["sessionsRevoked"]["status"] == "FAILED"

    auth_setup["revoke_error"] = None
    retry = _post(client)
    assert retry.status_code == 200
    assert retry.json()["deletionStatus"] == "QUEUED"
    assert retry.json()["steps"]["postsHidden"]["hiddenCount"] == 1
    assert auth_setup["revoked"] == [UID]


def test_deleted_account_conflict_and_user_not_found_revoke(
    fake_db: FakeFirestoreClient, test_settings_env: None, auth_setup: Dict[str, Any]
) -> None:
    client = TestClient(create_app())
    fake_db.collection("account_states").document(UID).set({"status": "DELETED"})
    resp = _post(client)
    assert resp.status_code == 409
    assert resp.json()["code"] == "ACCOUNT_ALREADY_DELETED"

    fake_db.collection("account_states").document(UID).set({"status": "ACTIVE"})
    auth_setup["revoke_error"] = fb_auth.UserNotFoundError("gone")
    ok = _post(client)
    assert ok.status_code == 202
    assert ok.json()["steps"]["sessionsRevoked"]["status"] == "DONE"


def test_other_user_cannot_affect_caller(
    fake_db: FakeFirestoreClient, test_settings_env: None, auth_setup: Dict[str, Any]
) -> None:
    _seed_post(fake_db, "p-mine", UID)
    client = TestClient(create_app())
    assert _post(client, "other").status_code == 202
    assert fake_db.collection("account_states").document(UID).get().exists is False
    assert fake_db.collection("posts").document("p-mine").get().to_dict()["moderationStatus"] == "VISIBLE"
    assert auth_setup["revoked"] == ["u-other"]
