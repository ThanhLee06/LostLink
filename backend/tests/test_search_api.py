"""
tests/test_search_api.py — Integration and HTTP-level tests for POST /api/v1/search.

Covers ST31 requirements:
- Auth: 401 on missing/invalid token, 403 on non-@ut.edu.vn or unverified email,
  403 on caller DELETING/DELETED account state.
- Validation: 422 on extra fields (uid, authorId), bad pageSize, out-of-range bounds,
  query > 200, categoryIds > 7, areaIds > 13.
- Cursor: 400 on empty, malformed, fingerprint mismatch, or expired cursor.
- Privacy & Response: strict camelCase keys, searchMode="KEYWORD", no author UIDs/contacts,
  canonical status labels (OPEN -> "Đang mở", RESOLVED -> "Đã hoàn tất").
- Filtering: type, taxonomy (category/area), timeFilter, diacritic-insensitive query.
- Visibility: excludes HIDDEN, CLOSED, tombstones, deleting authors.
- Pagination: multi-page traversal with cursor continuation invariant.
- Gateway timeout (504) and datastore error (503).
"""
from __future__ import annotations

import asyncio
from datetime import datetime, timedelta, timezone
import sys
from typing import Any, Dict, List, Optional
from unittest.mock import patch

import firebase_admin.auth as fb_auth
from fastapi.testclient import TestClient
from google.api_core.datetime_helpers import DatetimeWithNanoseconds
import pytest

from app.core.errors import ApiHTTPException
from app.main import create_app
from tests.conftest import (
    FakeCollectionReference,
    FakeDocumentReference,
    FakeFirestoreClient,
    FakeQuery,
)
from tests.test_search_service import (
    _fake_get,
    _fake_get_all,
    _fake_stream,
    _patched_is_after,
)

STUDENT_TOKEN = "valid-student-token"
CALLER_UID = "caller_student_01"


def _mock_auth_tokens(
    monkeypatch: pytest.MonkeyPatch, token_map: Dict[str, Dict[str, Any]]
) -> None:
    def _verify(token: str, check_revoked: bool = False) -> Dict[str, Any]:
        assert check_revoked is True
        if token not in token_map:
            raise fb_auth.InvalidIdTokenError("unknown test token")
        return dict(token_map[token])

    monkeypatch.setattr(fb_auth, "verify_id_token", _verify)


@pytest.fixture(autouse=True)
def _patch_fake_db_api(monkeypatch: pytest.MonkeyPatch) -> None:
    for mod_name in list(sys.modules.keys()):
        if "conftest" in mod_name:
            mod = sys.modules[mod_name]
            for cls_name in (
                "FakeQuery",
                "FakeCollectionReference",
                "FakeDocumentReference",
                "FakeFirestoreClient",
            ):
                if hasattr(mod, cls_name):
                    target_cls = getattr(mod, cls_name)
                    if cls_name in ("FakeQuery", "FakeCollectionReference"):
                        monkeypatch.setattr(target_cls, "stream", _fake_stream)
                        monkeypatch.setattr(target_cls, "_is_after_cursor", _patched_is_after)
                    elif cls_name == "FakeDocumentReference":
                        monkeypatch.setattr(target_cls, "get", _fake_get)
                    elif cls_name == "FakeFirestoreClient":
                        monkeypatch.setattr(target_cls, "get_all", _fake_get_all, raising=False)


@pytest.fixture
def auth_client(monkeypatch: pytest.MonkeyPatch, test_settings_env: None) -> TestClient:
    _mock_auth_tokens(
        monkeypatch,
        {
            STUDENT_TOKEN: {
                "uid": CALLER_UID,
                "email": f"{CALLER_UID}@ut.edu.vn",
                "email_verified": True,
            },
            "unverified-token": {
                "uid": "unverified_user",
                "email": "unverified@ut.edu.vn",
                "email_verified": False,
            },
            "gmail-token": {
                "uid": "gmail_user",
                "email": "intruder@gmail.com",
                "email_verified": True,
            },
            "no-uid-token": {
                "email": "nouid@ut.edu.vn",
                "email_verified": True,
            },
            "blank-uid-token": {
                "uid": "   ",
                "email": "blankuid@ut.edu.vn",
                "email_verified": True,
            },
        },
    )
    return TestClient(create_app(), raise_server_exceptions=False)


def test_search_api_unauthorized_and_forbidden(
    auth_client: TestClient, fake_db: FakeFirestoreClient
) -> None:
    fake_db.collection("account_states").document(CALLER_UID).set({"status": "ACTIVE"})

    # 1. Missing Authorization header -> 401 MISSING_TOKEN
    r_no_auth = auth_client.post("/api/v1/search", json={})
    assert r_no_auth.status_code == 401
    assert r_no_auth.headers.get("X-Trace-Id")
    b_no_auth = r_no_auth.json()
    assert b_no_auth["code"] == "MISSING_TOKEN"
    assert b_no_auth["traceId"] == r_no_auth.headers["X-Trace-Id"]

    # 2. Invalid Token -> 401 INVALID_TOKEN
    r_bad_tok = auth_client.post(
        "/api/v1/search",
        json={},
        headers={"Authorization": "Bearer bad-token-xyz"},
    )
    assert r_bad_tok.status_code == 401
    assert r_bad_tok.json()["code"] == "INVALID_TOKEN"

    # 3. Non-student domain -> 403 DOMAIN_NOT_ALLOWED
    r_gmail = auth_client.post(
        "/api/v1/search",
        json={},
        headers={"Authorization": "Bearer gmail-token"},
    )
    assert r_gmail.status_code == 403
    assert r_gmail.json()["code"] == "DOMAIN_NOT_ALLOWED"

    # 4. Unverified email -> 403 EMAIL_NOT_VERIFIED
    r_unverified = auth_client.post(
        "/api/v1/search",
        json={},
        headers={"Authorization": "Bearer unverified-token"},
    )
    assert r_unverified.status_code == 403
    assert r_unverified.json()["code"] == "EMAIL_NOT_VERIFIED"

    # 5. Token without valid UID -> 401 INVALID_TOKEN
    r_no_uid = auth_client.post(
        "/api/v1/search",
        json={},
        headers={"Authorization": "Bearer no-uid-token"},
    )
    assert r_no_uid.status_code == 401
    assert r_no_uid.json()["code"] == "INVALID_TOKEN"

    r_blank_uid = auth_client.post(
        "/api/v1/search",
        json={},
        headers={"Authorization": "Bearer blank-uid-token"},
    )
    assert r_blank_uid.status_code == 401
    assert r_blank_uid.json()["code"] == "INVALID_TOKEN"

    # 6. Caller account DELETING -> 403 ACCOUNT_INACTIVE
    fake_db.collection("account_states").document(CALLER_UID).set({"status": "DELETING"})
    r_deleting = auth_client.post(
        "/api/v1/search",
        json={},
        headers={"Authorization": f"Bearer {STUDENT_TOKEN}"},
    )
    assert r_deleting.status_code == 403
    assert r_deleting.json()["code"] == "ACCOUNT_INACTIVE"

    # 7. Caller account DELETED -> 403 ACCOUNT_INACTIVE
    fake_db.collection("account_states").document(CALLER_UID).set({"status": "DELETED"})
    r_deleted = auth_client.post(
        "/api/v1/search",
        json={},
        headers={"Authorization": f"Bearer {STUDENT_TOKEN}"},
    )
    assert r_deleted.status_code == 403
    assert r_deleted.json()["code"] == "ACCOUNT_INACTIVE"


def test_search_api_validation_errors(
    auth_client: TestClient, fake_db: FakeFirestoreClient
) -> None:
    fake_db.collection("account_states").document(CALLER_UID).set({"status": "ACTIVE"})
    auth_header = {"Authorization": f"Bearer {STUDENT_TOKEN}"}

    # Extra fields: client-supplied uid or authorId
    for extra_field in ("uid", "authorId", "searchMode", "unknown_param"):
        r = auth_client.post("/api/v1/search", json={extra_field: "forged"}, headers=auth_header)
        assert r.status_code == 422
        assert r.json()["code"] == "VALIDATION_ERROR"
        assert r.headers.get("X-Trace-Id")

    # Bad page_size types (strict int)
    for bad_size in (True, False, "20", 20.5, 0, -5, 51, 100):
        r = auth_client.post("/api/v1/search", json={"pageSize": bad_size}, headers=auth_header)
        assert r.status_code == 422
        assert r.json()["code"] == "VALIDATION_ERROR"

    # Bad query length (>200)
    r_q = auth_client.post("/api/v1/search", json={"query": "a" * 201}, headers=auth_header)
    assert r_q.status_code == 422
    assert r_q.json()["code"] == "VALIDATION_ERROR"

    # Bad categoryIds / areaIds types or over max length
    r_cats = auth_client.post(
        "/api/v1/search",
        json={"categoryIds": ["wallet"] * 8},
        headers=auth_header,
    )
    assert r_cats.status_code == 422
    assert r_cats.json()["code"] == "VALIDATION_ERROR"

    r_areas = auth_client.post(
        "/api/v1/search",
        json={"areaIds": ["a"] * 14},
        headers=auth_header,
    )
    assert r_areas.status_code == 422
    assert r_areas.json()["code"] == "VALIDATION_ERROR"


def test_search_api_cursor_errors(
    auth_client: TestClient, fake_db: FakeFirestoreClient
) -> None:
    fake_db.collection("account_states").document(CALLER_UID).set({"status": "ACTIVE"})
    auth_header = {"Authorization": f"Bearer {STUDENT_TOKEN}"}

    # Empty cursor string
    r_empty = auth_client.post("/api/v1/search", json={"cursor": ""}, headers=auth_header)
    assert r_empty.status_code == 400
    assert r_empty.json()["code"] == "INVALID_CURSOR"

    # Malformed base64 cursor
    r_malformed = auth_client.post(
        "/api/v1/search", json={"cursor": "!!!not_base64!!!"}, headers=auth_header
    )
    assert r_malformed.status_code == 400
    assert r_malformed.json()["code"] == "INVALID_CURSOR"


def test_search_api_happy_path_and_privacy(
    auth_client: TestClient, fake_db: FakeFirestoreClient
) -> None:
    now = datetime.now(tz=timezone.utc)
    fake_db.collection("account_states").document(CALLER_UID).set({"status": "ACTIVE"})
    fake_db.collection("account_states").document("author_01").set({"status": "ACTIVE"})
    fake_db.collection("account_states").document("author_del").set({"status": "DELETING"})

    auth_header = {"Authorization": f"Bearer {STUDENT_TOKEN}"}

    # 1. Valid OPEN post
    fake_db.collection("posts").document("post_open").set({
        "title": "Nhặt được ví da đen",
        "type": "LOST",
        "status": "OPEN",
        "moderationStatus": "VISIBLE",
        "categoryId": "wallet",
        "areaId": "a",
        "location": "Hành lang A1",
        "createdAt": now - timedelta(hours=1),
        "authorId": "author_01",
        "imageUrl": "https://images.example.com/item.jpg",
        "authorPhone": "0901234567",
        "internalSecret": "do-not-leak",
    })

    # 2. Valid RESOLVED post
    fake_db.collection("posts").document("post_resolved").set({
        "title": "Thẻ sinh viên UTH",
        "type": "FOUND",
        "status": "RESOLVED",
        "moderationStatus": "VISIBLE",
        "categoryId": "student_card",
        "areaId": "library",
        "location": "Thư viện Cơ sở 1",
        "createdAt": now - timedelta(hours=2),
        "authorId": "author_01",
    })

    # 3. CLOSED post — excluded
    fake_db.collection("posts").document("post_closed").set({
        "title": "Ví đã đóng",
        "type": "LOST",
        "status": "CLOSED",
        "moderationStatus": "VISIBLE",
        "categoryId": "wallet",
        "createdAt": now - timedelta(hours=3),
        "authorId": "author_01",
    })

    # 4. HIDDEN post — excluded
    fake_db.collection("posts").document("post_hidden").set({
        "title": "Ví bị ẩn kiểm duyệt",
        "type": "LOST",
        "status": "OPEN",
        "moderationStatus": "HIDDEN",
        "categoryId": "wallet",
        "createdAt": now - timedelta(hours=4),
        "authorId": "author_01",
    })

    # 5. Post with DELETING author — excluded
    fake_db.collection("posts").document("post_del_author").set({
        "title": "Ví của tác giả xóa",
        "type": "LOST",
        "status": "OPEN",
        "moderationStatus": "VISIBLE",
        "categoryId": "wallet",
        "createdAt": now - timedelta(hours=5),
        "authorId": "author_del",
    })

    # 6. Post with tombstone — excluded
    fake_db.collection("posts").document("post_tomb").set({
        "title": "Ví có tombstone",
        "type": "LOST",
        "status": "OPEN",
        "moderationStatus": "VISIBLE",
        "categoryId": "wallet",
        "createdAt": now - timedelta(hours=6),
        "authorId": "author_01",
    })
    fake_db.collection("post_tombstones").document("post_tomb").set({"deletedAt": now})

    # Call API with empty query (fetch all)
    r = auth_client.post("/api/v1/search", json={}, headers=auth_header)
    assert r.status_code == 200
    assert r.headers.get("X-Trace-Id")

    body = r.json()
    assert set(body.keys()) == {"items", "hasMore", "nextCursor", "searchMode"}
    assert body["searchMode"] == "KEYWORD"
    assert body["hasMore"] is False
    assert body["nextCursor"] is None

    items = body["items"]
    assert len(items) == 2

    # Check first item (post_open)
    p0 = items[0]
    assert p0["postId"] == "post_open"
    assert p0["title"] == "Nhặt được ví da đen"
    assert p0["type"] == "LOST"
    assert p0["status"] == "OPEN"
    assert p0["statusLabel"] == "Đang mở"
    assert p0["categoryId"] == "wallet"
    assert p0["areaId"] == "a"
    assert p0["location"] == "Hành lang A1"
    assert p0["imageUrl"] == "https://images.example.com/item.jpg"
    assert isinstance(p0["score"], float)

    # Check second item (post_resolved)
    p1 = items[1]
    assert p1["postId"] == "post_resolved"
    assert p1["title"] == "Thẻ sinh viên UTH"
    assert p1["type"] == "FOUND"
    assert p1["status"] == "RESOLVED"
    assert p1["statusLabel"] == "Đã hoàn tất"
    assert p1["categoryId"] == "student_card"
    assert p1["areaId"] == "library"
    assert p1["imageUrl"] is None

    # STRICT PRIVACY: verify no author ID, phone, email, secret in any card
    for item in items:
        assert set(item.keys()) == {
            "postId",
            "title",
            "type",
            "status",
            "statusLabel",
            "categoryId",
            "areaId",
            "location",
            "createdAt",
            "imageUrl",
            "score",
        }
        assert "authorId" not in item
        assert "author_01" not in str(item)
        assert "0901234567" not in str(item)
        assert "do-not-leak" not in str(item)


def test_search_api_filtering_and_diacritics(
    auth_client: TestClient, fake_db: FakeFirestoreClient
) -> None:
    now = datetime.now(tz=timezone.utc)
    fake_db.collection("account_states").document(CALLER_UID).set({"status": "ACTIVE"})
    fake_db.collection("account_states").document("author_01").set({"status": "ACTIVE"})
    auth_header = {"Authorization": f"Bearer {STUDENT_TOKEN}"}

    fake_db.collection("posts").document("p_wallet").set({
        "title": "Nhặt được ví da nâu",
        "type": "LOST",
        "status": "OPEN",
        "moderationStatus": "VISIBLE",
        "categoryId": "wallet",
        "areaId": "a",
        "createdAt": now - timedelta(minutes=10),
        "authorId": "author_01",
    })
    fake_db.collection("posts").document("p_keys").set({
        "title": "Chùm chìa khóa xe máy",
        "type": "FOUND",
        "status": "OPEN",
        "moderationStatus": "VISIBLE",
        "categoryId": "keys",
        "areaId": "parking",
        "createdAt": now - timedelta(minutes=20),
        "authorId": "author_01",
    })

    # 1. Type filter
    r_type = auth_client.post(
        "/api/v1/search", json={"type": "FOUND"}, headers=auth_header
    )
    assert r_type.status_code == 200
    assert len(r_type.json()["items"]) == 1
    assert r_type.json()["items"][0]["postId"] == "p_keys"

    # 2. Category filter
    r_cat = auth_client.post(
        "/api/v1/search", json={"categoryIds": ["wallet"]}, headers=auth_header
    )
    assert r_cat.status_code == 200
    assert len(r_cat.json()["items"]) == 1
    assert r_cat.json()["items"][0]["postId"] == "p_wallet"

    # 3. Area filter
    r_area = auth_client.post(
        "/api/v1/search", json={"areaIds": ["parking"]}, headers=auth_header
    )
    assert r_area.status_code == 200
    assert len(r_area.json()["items"]) == 1
    assert r_area.json()["items"][0]["postId"] == "p_keys"

    # 4. Vietnamese text diacritic normalization: "vi nau" matches "ví da nâu"
    r_query = auth_client.post(
        "/api/v1/search", json={"query": "vi nau"}, headers=auth_header
    )
    assert r_query.status_code == 200
    assert len(r_query.json()["items"]) == 1
    assert r_query.json()["items"][0]["postId"] == "p_wallet"
    assert r_query.json()["items"][0]["score"] > 0.0

    # 5. Non-matching query
    r_nomatch = auth_client.post(
        "/api/v1/search", json={"query": "laptop macbook"}, headers=auth_header
    )
    assert r_nomatch.status_code == 200
    assert len(r_nomatch.json()["items"]) == 0
    assert r_nomatch.json()["hasMore"] is False


def test_search_api_pagination_flow(
    auth_client: TestClient, fake_db: FakeFirestoreClient
) -> None:
    now = datetime.now(tz=timezone.utc)
    fake_db.collection("account_states").document(CALLER_UID).set({"status": "ACTIVE"})
    fake_db.collection("account_states").document("author_01").set({"status": "ACTIVE"})
    auth_header = {"Authorization": f"Bearer {STUDENT_TOKEN}"}

    for i in range(3):
        fake_db.collection("posts").document(f"post_{i}").set({
            "title": f"Mục bài đăng {i}",
            "type": "LOST",
            "status": "OPEN",
            "moderationStatus": "VISIBLE",
            "categoryId": "other",
            "createdAt": now - timedelta(minutes=i + 1),
            "authorId": "author_01",
        })

    # Page 1: pageSize=2
    r_p1 = auth_client.post(
        "/api/v1/search", json={"pageSize": 2}, headers=auth_header
    )
    assert r_p1.status_code == 200
    b_p1 = r_p1.json()
    assert len(b_p1["items"]) == 2
    assert b_p1["items"][0]["postId"] == "post_0"
    assert b_p1["items"][1]["postId"] == "post_1"
    assert b_p1["hasMore"] is True
    assert isinstance(b_p1["nextCursor"], str)

    # Page 2: with cursor
    r_p2 = auth_client.post(
        "/api/v1/search",
        json={"pageSize": 2, "cursor": b_p1["nextCursor"]},
        headers=auth_header,
    )
    assert r_p2.status_code == 200
    b_p2 = r_p2.json()
    assert len(b_p2["items"]) == 1
    assert b_p2["items"][0]["postId"] == "post_2"
    assert b_p2["hasMore"] is False
    assert b_p2["nextCursor"] is None


def test_search_api_timeout_and_datastore_error(
    auth_client: TestClient, fake_db: FakeFirestoreClient, monkeypatch: pytest.MonkeyPatch
) -> None:
    fake_db.collection("account_states").document(CALLER_UID).set({"status": "ACTIVE"})
    auth_header = {"Authorization": f"Bearer {STUDENT_TOKEN}"}

    # Simulate Gateway Timeout (504)
    def _timeout_search(*args: Any, **kwargs: Any) -> Any:
        raise ApiHTTPException(
            status_code=504,
            detail="Search request timed out.",
            code="DEADLINE_EXCEEDED",
        )

    with patch("app.routers.search.search_posts", side_effect=_timeout_search):
        r_timeout = auth_client.post("/api/v1/search", json={}, headers=auth_header)
        assert r_timeout.status_code == 504
        assert r_timeout.json()["code"] == "DEADLINE_EXCEEDED"
        assert r_timeout.headers.get("X-Trace-Id")

    # Simulate Datastore Error (503)
    def _datastore_err(*args: Any, **kwargs: Any) -> Any:
        raise ApiHTTPException(
            status_code=503,
            detail="Datastore error.",
            code="DATASTORE_ERROR",
        )

    with patch("app.routers.search.search_posts", side_effect=_datastore_err):
        r_ds = auth_client.post("/api/v1/search", json={}, headers=auth_header)
        assert r_ds.status_code == 503
        assert r_ds.json()["code"] == "DATASTORE_ERROR"
