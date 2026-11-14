"""
app/schemas/common.py — Shared base models and common HTTP response schemas.

- CamelModel: base Pydantic v2 model configured with camelCase aliases,
  populate_by_name=True, serialize_by_alias=True, and str_strip_whitespace=True.
- StrictCamelModel: extra="forbid" for canonical write/persisted/API schemas.
- LegacyCamelModel: extra="ignore" strictly for pre-B0 legacy read/migration projections.
"""
from __future__ import annotations

from enum import Enum
from typing import Any, Dict, Generic, List, Optional, TypeVar

from pydantic import BaseModel, ConfigDict
from pydantic.alias_generators import to_camel

T = TypeVar("T")


def _normalize_firestore_value(value: Any) -> Any:
    """Recursively convert Enum subclasses to primitive values while keeping datetime/Vector intact."""
    if isinstance(value, Enum):
        return value.value
    if isinstance(value, dict):
        return {k: _normalize_firestore_value(v) for k, v in value.items()}
    if isinstance(value, list):
        return [_normalize_firestore_value(v) for v in value]
    return value


class CamelModel(BaseModel):
    """Base Pydantic model configured for camelCase serialization."""

    model_config = ConfigDict(
        alias_generator=to_camel,
        populate_by_name=True,
        serialize_by_alias=True,
        str_strip_whitespace=True,
    )

    def to_firestore_dict(self, *, exclude_none: bool = False) -> Dict[str, Any]:
        """
        Dump model to a camelCase dict suitable for Firestore writes.

        Preserves native Python datetime (Firestore Timestamp) and Firestore
        objects rather than converting timestamps to ISO strings via mode='json'.
        """
        raw = self.model_dump(by_alias=True, exclude_none=exclude_none)
        return _normalize_firestore_value(raw)


class StrictCamelModel(CamelModel):
    """Strict canonical/API DTO model rejecting unknown fields."""

    model_config = ConfigDict(
        alias_generator=to_camel,
        populate_by_name=True,
        serialize_by_alias=True,
        str_strip_whitespace=True,
        extra="forbid",
    )


class LegacyCamelModel(CamelModel):
    """Permissive projection model strictly for reading/migrating pre-B0 legacy documents."""

    model_config = ConfigDict(
        alias_generator=to_camel,
        populate_by_name=True,
        serialize_by_alias=True,
        str_strip_whitespace=True,
        extra="ignore",
    )


class ErrorResponse(StrictCamelModel):
    detail: str
    code: Optional[str] = None
    trace_id: Optional[str] = None


class PaginatedResponse(StrictCamelModel, Generic[T]):
    items: List[T]
    total: Optional[int] = None
    next_page_token: Optional[str] = None


class MessageResponse(StrictCamelModel):
    message: str


class HealthResponse(StrictCamelModel):
    status: str
    version: str
