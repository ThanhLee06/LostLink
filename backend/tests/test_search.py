"""
tests/test_search.py — Unit tests for search DTO schemas and validation.

Regression test matrix covering S1 requirements:
- defaults/camelCase/exact allowed response keys; extra uid/email/token rejected;
- pageSize True/False/0/51/float/string invalid, 1 and 50 valid;
- categoryIds/areaIds: valid dedup order, unknown IDs, string container, null,
  dict/list item -> ValidationError, raw duplicates over 7/13 -> ValidationError;
- query null rejected, absent/whitespace query works, max 200/201 boundary;
- OPEN/RESOLVED labels canonical even supplied wrong; CLOSED rejected; input
  dictionary unchanged; postId/title blank rejected;
- aware +07 time serialized UTC, naive rejected; invalid image (missing host,
  credentials, malformed HTTPS/HTTP) -> null with valid text retained;
- score bounds/nonfinite, response hasMore/cursor invariant and KEYWORD literal.
"""
from __future__ import annotations

from datetime import datetime, timedelta, timezone
from typing import Any, Dict

from pydantic import ValidationError
import pytest

from app.schemas.post import PostStatus, PostType
from app.schemas.search import (
    SearchItem,
    SearchRequest,
    SearchResponse,
    SearchTimeFilter,
)
from app.schemas.taxonomy import PostAreaId, PostCategoryId


def test_search_request_defaults_and_camel_case() -> None:
    req = SearchRequest.model_validate({})
    assert req.query == ""
    assert req.type is None
    assert req.category_ids == []
    assert req.area_ids == []
    assert req.time_filter == SearchTimeFilter.ALL
    assert req.page_size == 20
    assert req.cursor is None

    dumped = req.model_dump(by_alias=True)
    assert dumped == {
        "query": "",
        "type": None,
        "categoryIds": [],
        "areaIds": [],
        "timeFilter": "ALL",
        "pageSize": 20,
        "cursor": None,
    }


@pytest.mark.parametrize("forbidden_key", ["uid", "email", "token", "authorId", "unknownField"])
def test_search_request_extra_fields_forbidden(forbidden_key: str) -> None:
    with pytest.raises(ValidationError):
        SearchRequest.model_validate({forbidden_key: "not_allowed"})


@pytest.mark.parametrize("bad_page_size", [True, False, 0, -1, 51, 100, 20.5, "20"])
def test_search_request_page_size_invalid(bad_page_size: Any) -> None:
    with pytest.raises(ValidationError):
        SearchRequest(pageSize=bad_page_size)


@pytest.mark.parametrize("valid_page_size", [1, 20, 50])
def test_search_request_page_size_valid(valid_page_size: int) -> None:
    req = SearchRequest(pageSize=valid_page_size)
    assert req.page_size == valid_page_size


def test_search_request_query_bounds_and_null() -> None:
    with pytest.raises(ValidationError):
        SearchRequest.model_validate({"query": None})

    assert SearchRequest().query == ""
    assert SearchRequest(query="  laptop uth  ").query == "laptop uth"
    assert SearchRequest(query="   ").query == ""

    valid_200 = "a" * 200
    assert SearchRequest(query=valid_200).query == valid_200

    with pytest.raises(ValidationError):
        SearchRequest(query="a" * 201)


def test_search_request_cursor_handling() -> None:
    assert SearchRequest().cursor is None
    assert SearchRequest(cursor="").cursor == ""
    assert SearchRequest(cursor="   ").cursor == ""

    valid_2048 = "c" * 2048
    assert SearchRequest(cursor=valid_2048).cursor == valid_2048

    with pytest.raises(ValidationError):
        SearchRequest(cursor="c" * 2049)


def test_search_request_category_and_area_validation() -> None:
    req = SearchRequest.model_validate({
        "categoryIds": ["wallet", "wallet", "keys"],
        "areaIds": ["library", "library", "campus_1"],
    })
    assert req.category_ids == [PostCategoryId.WALLET, PostCategoryId.KEYS]
    assert req.area_ids == [PostAreaId.LIBRARY, PostAreaId.CAMPUS_1]

    for bad_container in ["wallet", {"categoryIds": "wallet"}]:
        with pytest.raises(ValidationError):
            SearchRequest.model_validate({"categoryIds": bad_container})

    with pytest.raises(ValidationError):
        SearchRequest.model_validate({"categoryIds": None})

    for bad_elem in [{"obj": 1}, ["nested"], 123, "invalid_cat"]:
        with pytest.raises(ValidationError):
            SearchRequest.model_validate({"categoryIds": [bad_elem]})

    for bad_elem in [{"obj": 1}, ["nested"], 456, "invalid_area"]:
        with pytest.raises(ValidationError):
            SearchRequest.model_validate({"areaIds": [bad_elem]})

    with pytest.raises(ValidationError):
        SearchRequest.model_validate({"categoryIds": ["wallet"] * 8})

    with pytest.raises(ValidationError):
        SearchRequest.model_validate({"areaIds": ["library"] * 14})


def test_search_item_status_labels_and_closed_rejected() -> None:
    now = datetime(2026, 11, 14, 12, 0, 0, tzinfo=timezone.utc)
    raw_dict: Dict[str, Any] = {
        "postId": "p1",
        "title": "Ví da",
        "type": "LOST",
        "status": "OPEN",
        "statusLabel": "Nhãn sai từ client",
        "categoryId": "wallet",
        "createdAt": now,
    }
    item_open = SearchItem.model_validate(raw_dict)
    assert item_open.status_label == "Đang mở"
    assert raw_dict["statusLabel"] == "Nhãn sai từ client"

    item_resolved = SearchItem.model_validate({
        "postId": "p2",
        "title": "Thẻ sinh viên",
        "type": "FOUND",
        "status": "RESOLVED",
        "statusLabel": "",
        "categoryId": "student_card",
        "createdAt": now,
    })
    assert item_resolved.status_label == "Đã hoàn tất"

    with pytest.raises(ValidationError):
        SearchItem.model_validate({
            "postId": "p3",
            "title": "Chìa khóa",
            "type": "LOST",
            "status": "CLOSED",
            "categoryId": "keys",
            "createdAt": now,
        })


@pytest.mark.parametrize("blank_value", ["", "   "])
def test_search_item_post_id_and_title_nonblank(blank_value: str) -> None:
    now = datetime(2026, 11, 14, 12, 0, 0, tzinfo=timezone.utc)
    with pytest.raises(ValidationError):
        SearchItem.model_validate({
            "postId": blank_value,
            "title": "Title",
            "type": "LOST",
            "status": "OPEN",
            "categoryId": "wallet",
            "createdAt": now,
        })
    with pytest.raises(ValidationError):
        SearchItem.model_validate({
            "postId": "p1",
            "title": blank_value,
            "type": "LOST",
            "status": "OPEN",
            "categoryId": "wallet",
            "createdAt": now,
        })


def test_search_item_created_at_timezone() -> None:
    naive_dt = datetime(2026, 11, 14, 12, 0, 0)
    with pytest.raises(ValidationError):
        SearchItem.model_validate({
            "postId": "p1",
            "title": "Title",
            "type": "LOST",
            "status": "OPEN",
            "categoryId": "wallet",
            "createdAt": naive_dt,
        })

    tz_gmt7 = timezone(timedelta(hours=7))
    aware_dt = datetime(2026, 11, 14, 19, 0, 0, tzinfo=tz_gmt7)
    item = SearchItem.model_validate({
        "postId": "p1",
        "title": "Title",
        "type": "LOST",
        "status": "OPEN",
        "categoryId": "wallet",
        "createdAt": aware_dt,
    })
    assert item.created_at.tzinfo == timezone.utc
    assert item.created_at.hour == 12


@pytest.mark.parametrize("bad_url", [
    "http://insecure.com/pic.jpg",
    "https://",
    "https:///only-path",
    "https://user:pass@secure.com/pic.jpg",
    "https://@host/image.jpg",
    "https://host:abc/image.jpg",
    "https://host:65536/image.jpg",
    "https://ho st/image.jpg",
    "https://host/image\n.jpg",
    "https://::malformed::",
    "",
    "   ",
])
def test_search_item_image_url_sanitization(bad_url: str) -> None:
    now = datetime(2026, 11, 14, 12, 0, 0, tzinfo=timezone.utc)
    item = SearchItem.model_validate({
        "postId": "p1",
        "title": "Vẫn giữ text hợp lệ",
        "type": "LOST",
        "status": "OPEN",
        "categoryId": "wallet",
        "createdAt": now,
        "imageUrl": bad_url,
    })
    assert item.image_url is None
    assert item.title == "Vẫn giữ text hợp lệ"

    valid_url = "https://res.cloudinary.com/demo/image/upload/sample.jpg"
    item_valid = SearchItem.model_validate({
        "postId": "p1",
        "title": "Vẫn giữ text hợp lệ",
        "type": "LOST",
        "status": "OPEN",
        "categoryId": "wallet",
        "createdAt": now,
        "imageUrl": valid_url,
    })
    assert item_valid.image_url == valid_url


@pytest.mark.parametrize("bad_score", [-0.01, 1.01, float("nan"), float("inf"), float("-inf")])
def test_search_item_score_bounds_and_nonfinite(bad_score: float) -> None:
    now = datetime(2026, 11, 14, 12, 0, 0, tzinfo=timezone.utc)
    with pytest.raises(ValidationError):
        SearchItem.model_validate({
            "postId": "p1",
            "title": "Title",
            "type": "LOST",
            "status": "OPEN",
            "categoryId": "wallet",
            "createdAt": now,
            "score": bad_score,
        })


def test_search_item_exact_allowed_response_keys() -> None:
    now = datetime(2026, 11, 14, 12, 0, 0, tzinfo=timezone.utc)
    item = SearchItem.model_validate({
        "postId": "p1",
        "title": "Ví nâu",
        "type": "LOST",
        "status": "OPEN",
        "categoryId": "wallet",
        "areaId": "library",
        "location": "Thư viện tầng 2",
        "createdAt": now,
        "imageUrl": "https://secure.com/pic.jpg",
        "score": 0.9,
    })
    dumped = item.model_dump(by_alias=True)
    assert set(dumped.keys()) == {
        "postId", "title", "type", "status", "statusLabel",
        "categoryId", "areaId", "location", "createdAt",
        "imageUrl", "score",
    }

    with pytest.raises(ValidationError):
        SearchItem.model_validate({
            "postId": "p1",
            "title": "Title",
            "type": "LOST",
            "status": "OPEN",
            "categoryId": "wallet",
            "createdAt": now,
            "authorId": "attacker",
        })


def test_search_response_has_more_cursor_invariant_and_search_mode() -> None:
    valid_resp = SearchResponse(hasMore=True, nextCursor="opaque_cursor_token")
    assert valid_resp.has_more is True
    assert valid_resp.next_cursor == "opaque_cursor_token"
    assert valid_resp.search_mode == "KEYWORD"

    empty_resp = SearchResponse(hasMore=False, nextCursor=None)
    assert empty_resp.has_more is False
    assert empty_resp.next_cursor is None

    with pytest.raises(ValidationError):
        SearchResponse(hasMore=True, nextCursor=None)

    with pytest.raises(ValidationError):
        SearchResponse(hasMore=True, nextCursor="")

    with pytest.raises(ValidationError):
        SearchResponse(hasMore=False, nextCursor="stray_cursor")

    with pytest.raises(ValidationError):
        SearchResponse.model_validate({"searchMode": "VECTOR"})
