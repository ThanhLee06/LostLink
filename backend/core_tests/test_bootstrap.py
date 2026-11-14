from types import SimpleNamespace
from unittest.mock import Mock

from fastapi.testclient import TestClient
import pytest

from app import bootstrap
from app.core import auth


@pytest.fixture
def core_client(monkeypatch):
    profile = {"uid": "core-student", "email": "student@ut.edu.vn", "email_verified": True}
    account = {"status": "ACTIVE"}
    snapshot = SimpleNamespace(exists=True, to_dict=lambda: account)
    database = SimpleNamespace(collection=lambda _: SimpleNamespace(
        document=lambda _: SimpleNamespace(get=lambda: snapshot)))
    verify = Mock(side_effect=lambda *args, **kwargs: dict(profile))
    initialize = Mock()
    monkeypatch.setattr(bootstrap, "init_firebase", initialize)
    monkeypatch.setattr(auth, "get_firestore", lambda: database)
    monkeypatch.setattr(auth.fb_auth, "verify_id_token", verify)
    with TestClient(bootstrap.create_app()) as client:
        yield client, profile, account, verify, initialize


def test_core_health_and_startup(core_client):
    client, _, _, _, initialize = core_client
    response = client.get("/health")
    assert response.status_code == 200
    assert response.json() == {"status": "ok", "version": "0.1.0"}
    assert response.headers["X-Trace-Id"]
    initialize.assert_called_once_with()


def test_session_requires_token(core_client):
    client, _, _, verify, _ = core_client
    assert client.get("/api/v1/session").status_code == 401
    verify.assert_not_called()


def test_session_verifies_token_and_returns_only_uid(core_client):
    client, _, _, verify, _ = core_client
    response = client.get("/api/v1/session", headers={"Authorization": "Bearer core-token"})
    assert response.status_code == 200
    assert response.json() == {"uid": "core-student"}
    verify.assert_called_once_with("core-token", check_revoked=True)


@pytest.mark.parametrize("change,code", [
    ({"email_verified": False}, "EMAIL_NOT_VERIFIED"),
    ({"email": "student@example.com"}, "DOMAIN_NOT_ALLOWED"),
])
def test_session_rejects_ineligible_identity(core_client, change, code):
    client, profile, _, _, _ = core_client
    profile.update(change)
    response = client.get("/api/v1/session", headers={"Authorization": "Bearer core-token"})
    assert response.status_code == 403
    assert response.json()["code"] == code


@pytest.mark.parametrize("account_status", ["DELETING", "DELETED", None])
def test_session_rejects_inactive_or_malformed_account(core_client, account_status):
    client, _, account, _, _ = core_client
    account["status"] = account_status
    response = client.get("/api/v1/session", headers={"Authorization": "Bearer core-token"})
    assert response.status_code == 403
    assert response.json()["code"] == "ACCOUNT_INACTIVE"


@pytest.mark.parametrize("path", ["/api/v1/search/smoke", "/api/v1/events/wake", "/api/v1/media/uploads", "/api/v1/admin/posts"])
def test_core_does_not_mount_future_apis(core_client, path):
    client, _, _, _, _ = core_client
    assert client.post(path, json={}).status_code == 404


def test_failed_lazy_firestore_import_does_not_publish_partial_singleton(monkeypatch):
    import builtins
    from app.core import firebase

    monkeypatch.setattr(firebase, "_app", None)
    monkeypatch.setattr(firebase, "_firestore_client", None)
    monkeypatch.setenv("FIREBASE_AUTH_EMULATOR_HOST", "127.0.0.1:9099")
    monkeypatch.setenv("FIRESTORE_EMULATOR_HOST", "127.0.0.1:8088")
    monkeypatch.setattr(
        firebase,
        "get_settings",
        lambda: SimpleNamespace(
            app_env="test",
            use_firebase_emulator=True,
            google_application_credentials="",
            firebase_project_id="demo-lostlink",
            firebase_auth_emulator_host="127.0.0.1:9099",
            firestore_emulator_host="127.0.0.1:8088",
        ),
    )
    monkeypatch.setattr(firebase.firebase_admin, "initialize_app", lambda *args, **kwargs: object())
    monkeypatch.setattr(firebase.firebase_admin, "delete_app", lambda app: None)
    real_import = builtins.__import__

    def unavailable_sdk(name, globals=None, locals=None, fromlist=(), level=0):
        if name == "firebase_admin" and "firestore" in (fromlist or ()):
            raise ImportError("SIMULATED_SDK_IMPORT_FAILURE")
        return real_import(name, globals, locals, fromlist, level)

    monkeypatch.setattr(builtins, "__import__", unavailable_sdk)
    with pytest.raises(ImportError, match="SIMULATED_SDK_IMPORT_FAILURE"):
        firebase.init_firebase()
    assert firebase._app is None, "A failed SDK import must not publish an initialized app singleton"
    assert firebase._firestore_client is None


def test_failed_client_creation_cleans_up_app_and_does_not_publish(monkeypatch):
    from app.core import firebase

    deleted = []
    dummy_app = object()

    monkeypatch.setattr(firebase, "_app", None)
    monkeypatch.setattr(firebase, "_firestore_client", None)
    monkeypatch.setenv("FIREBASE_AUTH_EMULATOR_HOST", "127.0.0.1:9099")
    monkeypatch.setenv("FIRESTORE_EMULATOR_HOST", "127.0.0.1:8088")
    monkeypatch.setattr(
        firebase,
        "get_settings",
        lambda: SimpleNamespace(
            app_env="test",
            use_firebase_emulator=True,
            google_application_credentials="",
            firebase_project_id="demo-lostlink",
            firebase_auth_emulator_host="127.0.0.1:9099",
            firestore_emulator_host="127.0.0.1:8088",
        ),
    )
    monkeypatch.setattr(firebase.firebase_admin, "initialize_app", lambda *args, **kwargs: dummy_app)
    monkeypatch.setattr(firebase.firebase_admin, "delete_app", lambda app: deleted.append(app))

    import sys
    fake_firestore_mod = SimpleNamespace(client=Mock(side_effect=RuntimeError("SIMULATED_CLIENT_CREATION_FAILURE")))
    monkeypatch.setitem(sys.modules, "firebase_admin.firestore", fake_firestore_mod)

    with pytest.raises(RuntimeError, match="SIMULATED_CLIENT_CREATION_FAILURE"):
        firebase.init_firebase()

    assert deleted == [dummy_app], "Newly registered app must be deleted if client creation fails"
    assert firebase._app is None
    assert firebase._firestore_client is None


def test_init_firebase_successful_retry_after_failure(monkeypatch):
    from app.core import firebase

    dummy_app = object()
    dummy_client = object()
    call_count = 0

    monkeypatch.setattr(firebase, "_app", None)
    monkeypatch.setattr(firebase, "_firestore_client", None)
    monkeypatch.setenv("FIREBASE_AUTH_EMULATOR_HOST", "127.0.0.1:9099")
    monkeypatch.setenv("FIRESTORE_EMULATOR_HOST", "127.0.0.1:8088")
    monkeypatch.setattr(
        firebase,
        "get_settings",
        lambda: SimpleNamespace(
            app_env="test",
            use_firebase_emulator=True,
            google_application_credentials="",
            firebase_project_id="demo-lostlink",
            firebase_auth_emulator_host="127.0.0.1:9099",
            firestore_emulator_host="127.0.0.1:8088",
        ),
    )
    monkeypatch.setattr(firebase.firebase_admin, "initialize_app", lambda *args, **kwargs: dummy_app)
    monkeypatch.setattr(firebase.firebase_admin, "delete_app", lambda app: None)

    def flaky_client(app):
        nonlocal call_count
        call_count += 1
        if call_count == 1:
            raise RuntimeError("TRANSIENT_FAILURE")
        return dummy_client

    import sys
    fake_firestore_mod = SimpleNamespace(client=flaky_client)
    monkeypatch.setitem(sys.modules, "firebase_admin.firestore", fake_firestore_mod)

    # First attempt fails
    with pytest.raises(RuntimeError, match="TRANSIENT_FAILURE"):
        firebase.init_firebase()
    assert firebase._app is None
    assert firebase._firestore_client is None

    # Second attempt succeeds
    firebase.init_firebase()
    assert firebase._app is dummy_app
    assert firebase._firestore_client is dummy_client
    assert firebase.get_firestore() is dummy_client
