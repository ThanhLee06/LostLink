"""
tests/test_search_service.py — Unit tests for search service, cursor, and matching.

Covers R1-R5 and service acceptance:
- R1: Lossless cursor anchor precision ({s, ns}), exact anchor compare across pages.
- R2: Hostile cursor inputs (empty, v1.0, huge seconds, bad nanos, invalid IDs, mismatch, TTL/future).
- R3: Real SDK contract (timeout/retry=None required), 503 datastore / 504 deadline errors.
- R4: Two-stage batch reads, omitted batch response -> 503, tombstone/author checks, exact UID.
- R5: Corrupt row ValidationError isolated without crashing healthy rows.
- Vietnamese text normalization, GMT+7 time boundaries, pagination, and empty window advancement.
"""
from __future__ import annotations

import base64
from datetime import datetime, timedelta, timezone
import json
from typing import Any, Dict, List, Optional
from unittest.mock import MagicMock

from google.api_core.datetime_helpers import DatetimeWithNanoseconds
from google.api_core.exceptions import DeadlineExceeded, GoogleAPICallError
import pytest

from app.core.errors import ApiHTTPException
from app.schemas.search import SearchRequest, SearchTimeFilter
from app.schemas.taxonomy import PostCategoryId
from app.services.search_service import (
    compute_fingerprint,
    decode_cursor,
    encode_cursor,
    get_time_bounds,
    matches_and_score,
    normalize_terms,
    normalize_text,
    search_posts,
)
import sys
from tests.conftest import FakeDocumentReference, FakeFirestoreClient, FakeQuery

_orig_stream = FakeQuery.stream
_orig_get = FakeDocumentReference.get
_orig_is_after = FakeQuery._is_after_cursor


def _fake_stream(self: Any, *args: Any, timeout: Optional[float] = None, retry: Any = None, **kwargs: Any) -> Any:
    assert retry is None
    return _orig_stream(self, *args, **kwargs)


def _fake_get(self: Any, *args: Any, timeout: Optional[float] = None, retry: Any = None, **kwargs: Any) -> Any:
    assert retry is None
    return _orig_get(self, *args, **kwargs)


def _fake_get_all(self: Any, refs: List[Any], timeout: Optional[float] = None, retry: Any = None, **kwargs: Any) -> Any:
    assert retry is None
    for ref in refs:
        yield ref.get(timeout=timeout, retry=retry)


def _patched_is_after(self: FakeQuery, item_id: str, item_data: Dict[str, Any]) -> bool:
    data_with_name = dict(item_data)
    data_with_name["__name__"] = f"{self._collection._name}/{item_id}"
    cursor = self._start_after
    if isinstance(cursor, dict) and "__name__" in cursor:
        cursor_copy = dict(cursor)
        ref_val = cursor_copy["__name__"]
        if hasattr(ref_val, "path"):
            cursor_copy["__name__"] = ref_val.path
        self._start_after = cursor_copy
    return _orig_is_after(self, item_id, data_with_name)


@pytest.fixture(autouse=True)
def _patch_fake_db(monkeypatch: pytest.MonkeyPatch) -> None:
    for mod_name in list(sys.modules.keys()):
        if "conftest" in mod_name:
            mod = sys.modules[mod_name]
            for cls_name in ("FakeQuery", "FakeCollectionReference", "FakeDocumentReference", "FakeFirestoreClient"):
                if hasattr(mod, cls_name):
                    target_cls = getattr(mod, cls_name)
                    if cls_name in ("FakeQuery", "FakeCollectionReference"):
                        monkeypatch.setattr(target_cls, "stream", _fake_stream)
                        monkeypatch.setattr(target_cls, "_is_after_cursor", _patched_is_after)
                    elif cls_name == "FakeDocumentReference":
                        monkeypatch.setattr(target_cls, "get", _fake_get)
                    elif cls_name == "FakeFirestoreClient":
                        monkeypatch.setattr(target_cls, "get_all", _fake_get_all, raising=False)


def test_text_normalization_and_scoring() -> None:
    assert normalize_text("Đại học Giao thông Vận tải") == "dai hoc giao thong van tai"
    assert normalize_terms("  Ví Nâu, ví Đen!  ") == ["vi", "nau", "den"]

    data = {
        "title": "Nhặt được ví nâu",
        "location": "Thư viện Cơ sở 1",
        "categoryId": "wallet",
        "areaId": "library",
        "description": "Bên trong có tiền và thẻ",
    }
    match, score = matches_and_score(data, ["vi", "nau"])
    assert match is True and score == 1.0

    match_partial, score_partial = matches_and_score(data, ["vi", "the"])
    assert match_partial is True and 0.0 < score_partial < 1.0

    no_match, score_zero = matches_and_score(data, ["laptop"])
    assert no_match is False and score_zero == 0.0

    empty_match, empty_score = matches_and_score(data, [])
    assert empty_match is True and empty_score == 0.0


def test_time_filter_vn_boundaries() -> None:
    dt_before = datetime(2026, 11, 14, 16, 59, 59, tzinfo=timezone.utc)
    _, cutoff_today_before = get_time_bounds(SearchTimeFilter.TODAY, dt_before)
    assert cutoff_today_before == datetime(2026, 11, 13, 17, 0, 0, tzinfo=timezone.utc)

    dt_after = datetime(2026, 11, 14, 17, 0, 0, tzinfo=timezone.utc)
    _, cutoff_today_after = get_time_bounds(SearchTimeFilter.TODAY, dt_after)
    assert cutoff_today_after == datetime(2026, 11, 14, 17, 0, 0, tzinfo=timezone.utc)

    _, cutoff_3d = get_time_bounds(SearchTimeFilter.THREE_DAYS, dt_after)
    assert cutoff_3d == datetime(2026, 11, 12, 17, 0, 0, tzinfo=timezone.utc)

    _, cutoff_month = get_time_bounds(SearchTimeFilter.THIS_MONTH, dt_after)
    assert cutoff_month == datetime(2026, 10, 31, 17, 0, 0, tzinfo=timezone.utc)


def test_cursor_lossless_precision_and_continuation(fake_db: FakeFirestoreClient) -> None:
    now = datetime(2026, 11, 14, 12, 0, 0, tzinfo=timezone.utc)
    fake_db.collection("account_states").document("caller").set({"status": "ACTIVE"})
    fake_db.collection("account_states").document("author").set({"status": "ACTIVE"})

    # R1 regression: anchor at 12:00:00.900000Z, p1 at .800000, p2 at .700000
    anchor_dt = DatetimeWithNanoseconds(2026, 11, 14, 12, 0, 0, 900000, tzinfo=timezone.utc)
    p1_dt = DatetimeWithNanoseconds(2026, 11, 14, 12, 0, 0, 800000, tzinfo=timezone.utc)
    p2_dt = DatetimeWithNanoseconds(2026, 11, 14, 12, 0, 0, 700000, tzinfo=timezone.utc)

    fake_db.collection("posts").document("p1").set({
        "title": "Bài 1", "type": "LOST", "status": "OPEN", "moderationStatus": "VISIBLE",
        "categoryId": "wallet", "createdAt": p1_dt, "authorId": "author",
    })
    fake_db.collection("posts").document("p2").set({
        "title": "Bài 2", "type": "LOST", "status": "OPEN", "moderationStatus": "VISIBLE",
        "categoryId": "wallet", "createdAt": p2_dt, "authorId": "author",
    })

    req_p1 = SearchRequest(pageSize=1)
    resp_p1 = search_posts(fake_db, req_p1, "caller", now=anchor_dt)
    assert len(resp_p1.items) == 1 and resp_p1.items[0].post_id == "p1"
    assert resp_p1.has_more is True

    # Page 2 must include p2, restoring exact anchor
    req_p2 = SearchRequest(pageSize=1, cursor=resp_p1.next_cursor)
    resp_p2 = search_posts(fake_db, req_p2, "caller", now=now)
    assert len(resp_p2.items) == 1 and resp_p2.items[0].post_id == "p2"


def test_cursor_hostile_inputs_rejected() -> None:
    now = datetime(2026, 11, 14, 12, 0, 0, tzinfo=timezone.utc)
    req = SearchRequest(query="ví")
    fp = compute_fingerprint("user1", req)

    # Empty cursor
    with pytest.raises(ApiHTTPException) as exc_empty:
        search_posts(MagicMock(), SearchRequest(cursor=""), "user1", now=now)
    assert exc_empty.value.status_code == 400

    valid_token = encode_cursor(fp, now, "post1", now)
    # Future anchor (>60s)
    with pytest.raises(ApiHTTPException):
        decode_cursor(valid_token, fp, now - timedelta(seconds=70))

    # Expired anchor (>30m)
    with pytest.raises(ApiHTTPException):
        decode_cursor(valid_token, fp, now + timedelta(minutes=31))

    # Fingerprint mismatch
    with pytest.raises(ApiHTTPException) as exc_mismatch:
        decode_cursor(valid_token, "other_fp", now)
    assert exc_mismatch.value.code == "CURSOR_MISMATCH"

    # Malformed base64
    with pytest.raises(ApiHTTPException):
        decode_cursor("not-base64???", fp, now)

    # Hostile JSON shapes & types
    def make_token(payload: Dict[str, Any]) -> str:
        return base64.urlsafe_b64encode(json.dumps(payload).encode()).decode()

    # v=1.0, v=True, huge seconds, negative nanos, invalid doc id
    for bad_dict in [
        {"v": 1.0, "fp": fp, "a": {"s": int(now.timestamp()), "ns": 0}, "pid": "p1", "s": 0, "ns": 0},
        {"v": True, "fp": fp, "a": {"s": int(now.timestamp()), "ns": 0}, "pid": "p1", "s": 0, "ns": 0},
        {"v": 1, "fp": fp, "a": {"s": 300000000000, "ns": 0}, "pid": "p1", "s": 0, "ns": 0},
        {"v": 1, "fp": fp, "a": {"s": int(now.timestamp()), "ns": -1}, "pid": "p1", "s": 0, "ns": 0},
        {"v": 1, "fp": fp, "a": {"s": int(now.timestamp()), "ns": 0}, "pid": "../etc/passwd", "s": 0, "ns": 0},
        {"v": 1, "fp": fp, "a": {"s": int(now.timestamp()), "ns": 0}, "pid": "dir/post", "s": 0, "ns": 0},
        {"v": 1, "fp": fp, "a": 12345, "pid": "p1", "s": 0, "ns": 0},
    ]:
        with pytest.raises(ApiHTTPException):
            decode_cursor(make_token(bad_dict), fp, now)


def test_search_posts_two_stage_batch_and_privacy(fake_db: FakeFirestoreClient) -> None:
    now = datetime(2026, 11, 14, 12, 0, 0, tzinfo=timezone.utc)
    fake_db.collection("account_states").document("caller").set({"status": "ACTIVE"})
    fake_db.collection("account_states").document("student_1").set({"status": "ACTIVE"})
    fake_db.collection("account_states").document(" student_spaced ").set({"status": "ACTIVE"})
    fake_db.collection("account_states").document("del_user").set({"status": "DELETING"})

    # Post with exact spaced author UID
    fake_db.collection("posts").document("post_spaced").set({
        "title": "Ví của spaced author", "type": "LOST", "status": "OPEN",
        "moderationStatus": "VISIBLE", "categoryId": "wallet",
        "createdAt": now - timedelta(minutes=5), "authorId": " student_spaced ",
    })
    # Post with tombstone
    fake_db.collection("posts").document("post_tomb").set({
        "title": "Ví bị xóa", "type": "LOST", "status": "OPEN",
        "moderationStatus": "VISIBLE", "categoryId": "wallet",
        "createdAt": now - timedelta(minutes=10), "authorId": "student_1",
    })
    fake_db.collection("post_tombstones").document("post_tomb").set({"deletedAt": now})

    # Post with author deleting
    fake_db.collection("posts").document("post_del_author").set({
        "title": "Ví tác giả đang xóa", "type": "LOST", "status": "OPEN",
        "moderationStatus": "VISIBLE", "categoryId": "wallet",
        "createdAt": now - timedelta(minutes=15), "authorId": "del_user",
    })

    resp = search_posts(fake_db, SearchRequest(), "caller", now=now)
    assert len(resp.items) == 1
    assert resp.items[0].post_id == "post_spaced"


def test_search_posts_corrupt_row_isolation(fake_db: FakeFirestoreClient) -> None:
    now = datetime(2026, 11, 14, 12, 0, 0, tzinfo=timezone.utc)
    fake_db.collection("account_states").document("caller").set({"status": "ACTIVE"})
    fake_db.collection("account_states").document("author").set({"status": "ACTIVE"})

    # Corrupt row 1: unhashable list categoryId with query
    fake_db.collection("posts").document("corrupt_cat_list").set({
        "title": "Ví lỗi category list", "type": "LOST", "status": "OPEN",
        "moderationStatus": "VISIBLE", "categoryId": [],
        "createdAt": now - timedelta(minutes=1), "authorId": "author",
    })
    # Corrupt row 2: invalid authorId path
    fake_db.collection("posts").document("corrupt_bad_author").set({
        "title": "Ví lỗi authorId path", "type": "LOST", "status": "OPEN",
        "moderationStatus": "VISIBLE", "categoryId": "wallet",
        "createdAt": now - timedelta(minutes=2), "authorId": "bad/uid",
    })
    # Corrupt row 3: invalid categoryId string causing SearchItem ValidationError
    fake_db.collection("posts").document("corrupt_post").set({
        "title": "Ví lỗi category string", "type": "LOST", "status": "OPEN",
        "moderationStatus": "VISIBLE", "categoryId": "invalid_category_xyz",
        "createdAt": now - timedelta(minutes=3), "authorId": "author",
    })
    # Healthy row
    fake_db.collection("posts").document("healthy_post").set({
        "title": "Nhặt được ví da", "type": "LOST", "status": "OPEN",
        "moderationStatus": "VISIBLE", "categoryId": "wallet",
        "createdAt": now - timedelta(minutes=4), "authorId": "author",
    })

    resp = search_posts(fake_db, SearchRequest(query="vi"), "caller", now=now)
    assert len(resp.items) == 1
    assert resp.items[0].post_id == "healthy_post"


def test_search_posts_author_state_and_caller_deleting(fake_db: FakeFirestoreClient) -> None:
    now = datetime(2026, 11, 14, 12, 0, 0, tzinfo=timezone.utc)
    fake_db.collection("account_states").document("caller").set({"status": "ACTIVE"})

    fake_db.collection("posts").document("legacy_post").set({
        "title": "Bài của legacy author chưa có doc", "type": "LOST", "status": "OPEN",
        "moderationStatus": "VISIBLE", "categoryId": "wallet",
        "createdAt": now - timedelta(minutes=5), "authorId": "missing_doc_author",
    })

    resp = search_posts(fake_db, SearchRequest(), "caller", now=now)
    assert len(resp.items) == 1
    assert resp.items[0].post_id == "legacy_post"

    fake_db.collection("account_states").document("caller").set({"status": "DELETING"})
    with pytest.raises(ApiHTTPException) as exc:
        search_posts(fake_db, SearchRequest(), "caller", now=now)
    assert exc.value.status_code == 403
    assert exc.value.code == "ACCOUNT_INACTIVE"


def test_search_posts_pagination_and_empty_window(fake_db: FakeFirestoreClient) -> None:
    now = datetime(2026, 11, 14, 12, 0, 0, tzinfo=timezone.utc)
    fake_db.collection("account_states").document("caller").set({"status": "ACTIVE"})
    fake_db.collection("account_states").document("author").set({"status": "ACTIVE"})

    for i in range(41):
        fake_db.collection("posts").document(f"key_{i:02d}").set({
            "title": f"Chìa khóa {i}", "type": "LOST", "status": "OPEN",
            "moderationStatus": "VISIBLE", "categoryId": "keys",
            "createdAt": now - timedelta(minutes=i + 1), "authorId": "author",
        })
    fake_db.collection("posts").document("wallet_target").set({
        "title": "Ví tiền mục tiêu", "type": "LOST", "status": "OPEN",
        "moderationStatus": "VISIBLE", "categoryId": "wallet",
        "createdAt": now - timedelta(minutes=100), "authorId": "author",
    })

    # Window of 40 keys rejected, lookahead exists -> empty items, hasMore=True, cursor advanced
    req = SearchRequest(pageSize=20, categoryIds=[PostCategoryId.WALLET])
    resp = search_posts(fake_db, req, "caller", now=now)
    assert len(resp.items) == 0
    assert resp.has_more is True
    assert resp.next_cursor is not None

    req_next = SearchRequest(pageSize=20, categoryIds=[PostCategoryId.WALLET], cursor=resp.next_cursor)
    resp_next = search_posts(fake_db, req_next, "caller", now=now)
    assert len(resp_next.items) == 1
    assert resp_next.items[0].post_id == "wallet_target"


def test_search_posts_deleted_cursor_doc(fake_db: FakeFirestoreClient) -> None:
    now = datetime(2026, 11, 14, 12, 0, 0, tzinfo=timezone.utc)
    fake_db.collection("account_states").document("caller").set({"status": "ACTIVE"})
    fake_db.collection("account_states").document("author").set({"status": "ACTIVE"})

    cursor_ts = now - timedelta(hours=1)
    fake_db.collection("posts").document("surviving_post").set({
        "title": "Bài sống", "type": "LOST", "status": "OPEN",
        "moderationStatus": "VISIBLE", "categoryId": "wallet",
        "createdAt": cursor_ts - timedelta(minutes=5), "authorId": "author",
    })

    req = SearchRequest()
    fp = compute_fingerprint("caller", req)
    token = encode_cursor(fp, now, "deleted_post_id", cursor_ts)

    resp = search_posts(fake_db, SearchRequest(cursor=token), "caller", now=now)
    assert len(resp.items) == 1
    assert resp.items[0].post_id == "surviving_post"


def test_search_posts_errors_and_omitted_batch(fake_db: FakeFirestoreClient) -> None:
    req = SearchRequest()

    # Omitted batch response -> 503
    bad_db = MagicMock()
    bad_db.collection.return_value.where.return_value.where.return_value.where.return_value.order_by.return_value.order_by.return_value.limit.return_value.stream.return_value = [
        MagicMock(reference=MagicMock(path="posts/p1"), id="p1")
    ]
    # get_all returns empty list (omitted path)
    bad_db.get_all.return_value = []
    with pytest.raises(ApiHTTPException) as exc_omitted:
        search_posts(bad_db, req, "caller")
    assert exc_omitted.value.status_code == 503
    assert exc_omitted.value.code == "DATASTORE_ERROR"

    # Datastore error -> 503
    err_db = MagicMock()
    err_db.collection.side_effect = GoogleAPICallError("Network failure")
    with pytest.raises(ApiHTTPException) as exc_ds:
        search_posts(err_db, req, "caller")
    assert exc_ds.value.status_code == 503

    # DeadlineExceeded without 'timeout' or 'deadline' in message -> 504
    timeout_db = MagicMock()
    timeout_db.collection.side_effect = DeadlineExceeded("RPC budget exhausted")
    with pytest.raises(ApiHTTPException) as exc_dl:
        search_posts(timeout_db, req, "caller")
    assert exc_dl.value.status_code == 504
    assert exc_dl.value.code == "DEADLINE_EXCEEDED"

    # TimeoutError with empty message -> 504
    timeout_empty = MagicMock()
    timeout_empty.collection.side_effect = TimeoutError()
    with pytest.raises(ApiHTTPException) as exc_to_empty:
        search_posts(timeout_empty, req, "caller")
    assert exc_to_empty.value.status_code == 504
    assert exc_to_empty.value.code == "DEADLINE_EXCEEDED"

    # TimeoutError with message -> 504
    timeout_msg = MagicMock()
    timeout_msg.collection.side_effect = TimeoutError("socket timed out")
    with pytest.raises(ApiHTTPException) as exc_to_msg:
        search_posts(timeout_msg, req, "caller")
    assert exc_to_msg.value.status_code == 504

    # Caller recheck timeout mapping -> 504
    caller_timeout_db = MagicMock()
    caller_timeout_db.collection.return_value.where.return_value.where.return_value.where.return_value.order_by.return_value.order_by.return_value.limit.return_value.stream.return_value = []
    caller_timeout_db.collection.return_value.document.return_value.get.side_effect = DeadlineExceeded("RPC budget exhausted")
    with pytest.raises(ApiHTTPException) as exc_caller_timeout:
        search_posts(caller_timeout_db, req, "caller")
    assert exc_caller_timeout.value.status_code == 504
    assert exc_caller_timeout.value.code == "DEADLINE_EXCEEDED"
