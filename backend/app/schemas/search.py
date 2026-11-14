"""
app/schemas/search.py — Strict request and response DTO schemas for search API.

Matches PLAN.md §3, §5 and Contract-B0-Schema.
"""
from __future__ import annotations

from datetime import datetime, timezone
from enum import Enum
from typing import Any, Dict, List, Literal, Optional
from urllib.parse import urlsplit

from pydantic import Field, field_validator, model_validator

from app.schemas.common import StrictCamelModel
from app.schemas.post import PostStatus, PostType
from app.schemas.taxonomy import PostAreaId, PostCategoryId


class SearchTimeFilter(str, Enum):
    ALL = "ALL"
    TODAY = "TODAY"
    THREE_DAYS = "THREE_DAYS"
    THIS_WEEK = "THIS_WEEK"
    THIS_MONTH = "THIS_MONTH"


SEARCH_STATUS_LABELS: Dict[PostStatus, str] = {
    PostStatus.OPEN: "Đang mở",
    PostStatus.RESOLVED: "Đã hoàn tất",
}


class SearchRequest(StrictCamelModel):
    query: str = Field(default="", max_length=200)
    type: Optional[PostType] = None
    category_ids: List[PostCategoryId] = Field(default_factory=list, max_length=7)
    area_ids: List[PostAreaId] = Field(default_factory=list, max_length=13)
    time_filter: SearchTimeFilter = SearchTimeFilter.ALL
    page_size: int = Field(default=20, ge=1, le=50, strict=True)
    cursor: Optional[str] = Field(default=None, max_length=2048)

    @field_validator("category_ids", "area_ids", mode="after")
    @classmethod
    def _dedup_enums(cls, items: List[Any]) -> List[Any]:
        deduped: List[Any] = []
        seen = set()
        for item in items:
            if item not in seen:
                seen.add(item)
                deduped.append(item)
        return deduped


class SearchItem(StrictCamelModel):
    post_id: str = Field(..., min_length=1)
    title: str = Field(..., min_length=1)
    type: PostType
    status: PostStatus
    status_label: str = ""
    category_id: PostCategoryId
    area_id: Optional[PostAreaId] = None
    location: str = ""
    created_at: datetime
    image_url: Optional[str] = None
    score: float = Field(default=0.0, ge=0.0, le=1.0)

    @field_validator("status")
    @classmethod
    def _validate_status(cls, v: PostStatus) -> PostStatus:
        if v not in (PostStatus.OPEN, PostStatus.RESOLVED):
            raise ValueError("status must be OPEN or RESOLVED")
        return v

    @field_validator("area_id", mode="before")
    @classmethod
    def _clean_area_id(cls, v: Any) -> Optional[Any]:
        if not v or v == "":
            return None
        return v

    @field_validator("created_at")
    @classmethod
    def _validate_created_at(cls, v: datetime) -> datetime:
        if v.tzinfo is None or v.tzinfo.utcoffset(v) is None:
            raise ValueError("createdAt must be timezone-aware")
        return v.astimezone(timezone.utc)

    @field_validator("image_url", mode="before")
    @classmethod
    def _validate_image_url(cls, v: Any) -> Optional[str]:
        if not isinstance(v, str):
            return None
        cleaned = v.strip()
        if not cleaned or any(c.isspace() or c == "\\" or ord(c) < 32 for c in cleaned):
            return None
        try:
            parts = urlsplit(cleaned)
            if parts.scheme.lower() != "https" or not parts.netloc or not parts.hostname:
                return None
            if parts.username is not None or parts.password is not None:
                return None
            _ = parts.port
            return cleaned
        except ValueError:
            return None

    @model_validator(mode="after")
    def _set_status_label(self) -> "SearchItem":
        self.status_label = SEARCH_STATUS_LABELS[self.status]
        return self


class SearchResponse(StrictCamelModel):
    items: List[SearchItem] = Field(default_factory=list)
    has_more: bool = False
    next_cursor: Optional[str] = None
    search_mode: Literal["KEYWORD"] = "KEYWORD"

    @model_validator(mode="after")
    def _validate_continuation_invariant(self) -> "SearchResponse":
        if self.has_more:
            if not self.next_cursor or not self.next_cursor.strip():
                raise ValueError("hasMore=true requires a non-empty nextCursor")
        else:
            if self.next_cursor is not None:
                raise ValueError("hasMore=false requires nextCursor to be None")
        return self
