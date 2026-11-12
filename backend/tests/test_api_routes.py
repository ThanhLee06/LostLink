"""Focused foundation regression tests; extended suite is archived locally."""


from __future__ import annotations


from datetime import datetime, timezone


from typing import Any, Dict


import firebase_admin.auth as fb_auth


from fastapi.testclient import TestClient


import pytest


from app.core.config import get_settings


from app.main import create_app


from app.schemas.post import EventChangeType, PersistedPostEvent


from tests.conftest import FakeFirestoreClient


def _mock_auth_tokens(
    monkeypatch: pytest.MonkeyPatch, token_map: Dict[str, Dict[str, Any]]
) -> None:
    def _verify(token: str, check_revoked: bool = False) -> Dict[str, Any]:
        assert check_revoked is True
        if token not in token_map:
            raise fb_auth.InvalidIdTokenError("unknown test token")
        return dict(token_map[token])

    monkeypatch.setattr(fb_auth, "verify_id_token", _verify)


def test_error_responses_401_403_422_500_never_leak_secrets_or_stack_traces(
    monkeypatch: pytest.MonkeyPatch,
    fake_db: FakeFirestoreClient,
    test_settings_env: None,
) -> None:
    _mock_auth_tokens(
        monkeypatch,
        {
            "student-tok": {
                "uid": "u1",
                "email": "u1@ut.edu.vn",
                "email_verified": True,
            },
            "bad-domain-tok": {
                "uid": "u2",
                "email": "u2@gmail.com",
                "email_verified": True,
            },
        },
    )

    test_app = create_app()

    @test_app.get("/_test_boom")
    async def _boom() -> None:
        raise RuntimeError("SECRET_DB_DSN=postgres://admin:supersecret/db traceback")

    client = TestClient(test_app, raise_server_exceptions=False)

    # 401 MISSING_TOKEN
    r401 = client.get("/api/v1/session")
    assert r401.status_code == 401
    assert r401.headers["X-Trace-Id"]
    b401 = r401.json()
    assert b401["code"] == "MISSING_TOKEN"
    assert b401["traceId"] == r401.headers["X-Trace-Id"]

    # 403 DOMAIN_NOT_ALLOWED
    r403 = client.get(
        "/api/v1/session",
        headers={"Authorization": "Bearer bad-domain-tok"},
    )
    assert r403.status_code == 403
    assert r403.json()["code"] == "DOMAIN_NOT_ALLOWED"
    assert "bad-domain-tok" not in r403.text

    # 422 VALIDATION_ERROR (e.g., /media/uploads missing required postId)
    r422 = client.post(
        "/api/v1/media/uploads",
        json={"postId": "   "},
        headers={"Authorization": "Bearer student-tok"},
    )
    assert r422.status_code == 422
    assert r422.json()["code"] == "VALIDATION_ERROR"
    assert r422.json()["traceId"] == r422.headers["X-Trace-Id"]

    # 500 INTERNAL_ERROR — never returns stack trace or internal error string
    r500 = client.get("/_test_boom", headers={"X-Trace-Id": "boom-trace-1"})
    assert r500.status_code == 500
    b500 = r500.json()
    assert b500["code"] == "INTERNAL_ERROR"
    assert b500["traceId"] == "boom-trace-1"
    assert "Traceback" not in r500.text
    assert "supersecret" not in r500.text


def test_events_wake_auth_rate_limit_and_ignores_forged_payload(
    monkeypatch: pytest.MonkeyPatch,
    fake_db: FakeFirestoreClient,
    test_settings_env: None,
) -> None:
    monkeypatch.setenv("WAKE_RATE_LIMIT_MAX", "2")
    monkeypatch.setenv("WAKE_RATE_LIMIT_WINDOW_SECONDS", "60")
    get_settings.cache_clear()

    ev = PersistedPostEvent.build_initial(
        post_id="p-wake-1",
        author_id="u-wake",
        post_version=1,
        change_type=EventChangeType.CREATE,
        created_at=datetime.now(tz=timezone.utc),
    )
    fake_db.collection("post_events").document("p-wake-1_v1").set(ev.to_firestore_dict())
    fake_db.collection("posts").document("p-wake-1").set(
        {"authorId": "u-wake", "moderationStatus": "VISIBLE", "version": 1}
    )

    client = TestClient(create_app())

    # Unauthenticated -> 401
    assert client.post("/api/v1/events/wake").status_code == 401

    # Placeholder API key -> 401
    monkeypatch.setenv("BACKEND_API_KEY", "change_me_random_string")
    get_settings.cache_clear()
    assert (
        client.post(
            "/api/v1/events/wake",
            headers={"X-Backend-Api-Key": "change_me_random_string"},
        ).status_code
        == 401
    )

    # Valid API key executes sweep and ignores forged client payload
    monkeypatch.setenv("BACKEND_API_KEY", "valid-wake-key-xyz")
    get_settings.cache_clear()
    r1 = client.post(
        "/api/v1/events/wake",
        headers={"X-Backend-Api-Key": "valid-wake-key-xyz"},
        json={"eventId": "forged-ev", "authorId": "attacker", "forceDone": True},
    )
    assert r1.status_code == 200
    assert r1.json()["processed"] == 1  # mediaCleanupJob (DONE), searchJob deferred to ST32
    assert fake_db.collection("post_events").document("forged-ev").get().exists is False

    r2 = client.post(
        "/api/v1/events/wake",
        headers={"X-Backend-Api-Key": "valid-wake-key-xyz"},
    )
    assert r2.status_code == 200

    # 3rd call within window hits 429 RATE_LIMIT_EXCEEDED
    r3 = client.post(
        "/api/v1/events/wake",
        headers={"X-Backend-Api-Key": "valid-wake-key-xyz"},
    )
    assert r3.status_code == 429
    assert r3.json()["code"] == "RATE_LIMIT_EXCEEDED"


def test_b0_t07_deleting_account_allowed_on_account_routes_blocked_on_active_routes(
    monkeypatch: pytest.MonkeyPatch,
    fake_db: FakeFirestoreClient,
    test_settings_env: None,
) -> None:
    _mock_auth_tokens(
        monkeypatch,
        {
            "del-tok": {
                "uid": "u-deleting",
                "email": "deleting@ut.edu.vn",
                "email_verified": True,
            }
        },
    )
    now = datetime.now(tz=timezone.utc)
    fake_db.collection("account_states").document("u-deleting").set(
        {"status": "DELETING", "updatedAt": now}
    )
    fake_db.collection("deletion_requests").document("u-deleting").set(
        {"status": "IN_PROGRESS", "steps": {"posts": "DONE"}, "createdAt": now}
    )

    client = TestClient(create_app())
    headers = {"Authorization": "Bearer del-tok"}

    # Blocked from normal student routes
    r_session = client.get("/api/v1/session", headers=headers)
    assert r_session.status_code == 403
    assert r_session.json()["code"] == "ACCOUNT_INACTIVE"

    r_media = client.post(
        "/api/v1/media/uploads", json={"postId": "p1"}, headers=headers
    )
    assert r_media.status_code == 403
    assert r_media.json()["code"] == "ACCOUNT_INACTIVE"

    # Allowed on /api/v1/account/*
    r_state = client.get("/api/v1/account/state", headers=headers)
    assert r_state.status_code == 200
    assert r_state.json()["uid"] == "u-deleting"
    assert r_state.json()["status"] == "DELETING"

    r_del = client.get("/api/v1/account/deletion", headers=headers)
    assert r_del.status_code == 200
    assert r_del.json()["steps"] == {"posts": "DONE"}


@pytest.mark.parametrize(
    ("method", "path"),
    [
        ("GET", "/api/v1/admin/posts"),
        ("PATCH", "/api/v1/admin/posts/p1/moderate"),
        ("GET", "/api/v1/admin/reports"),
        ("POST", "/api/v1/search/smoke"),
        ("POST", "/api/v1/account/deletion/resume"),
    ],
)
def test_unmounted_future_routes_return_404_in_b0_main(
    test_settings_env: None,
    method: str,
    path: str,
) -> None:
    client = TestClient(create_app())
    response = client.request(method, path)
    assert response.status_code == 404


def test_events_wake_notifies_attached_worker_loop_c2_r4(
    fake_db: FakeFirestoreClient,
    test_settings_env: None,
) -> None:
    class _SpyLoop:
        def __init__(self) -> None:
            self.wakes = 0

        def wake(self) -> None:
            self.wakes += 1

    app = create_app()
    spy = _SpyLoop()
    app.state.worker_loop = spy
    client = TestClient(app)

    resp = client.post(
        "/api/v1/events/wake",
        headers={"X-Backend-Api-Key": "test-wake-secret-key-98765"},
    )
    assert resp.status_code == 200
    assert spy.wakes == 1
