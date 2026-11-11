"""app/schemas/__init__.py — Initial exports for B0 common and taxonomy."""
from __future__ import annotations

from app.schemas.common import CamelModel, ErrorResponse, HealthResponse, LegacyCamelModel, MessageResponse, PaginatedResponse, StrictCamelModel
from app.schemas.taxonomy import AREA_LABELS, AREA_LEGACY_ALIASES, CATEGORY_LABELS, CATEGORY_LEGACY_ALIASES, AreaChoice, PostAreaId, PostCategoryId, TimeMode, normalize_area_id, normalize_category_id

__all__ = [
    "AREA_LABELS", "AREA_LEGACY_ALIASES", "AreaChoice", "CATEGORY_LABELS", "CATEGORY_LEGACY_ALIASES",
    "CamelModel", "ErrorResponse", "HealthResponse", "LegacyCamelModel", "MessageResponse",
    "PaginatedResponse", "PostAreaId", "PostCategoryId", "StrictCamelModel", "TimeMode",
    "normalize_area_id", "normalize_category_id",
]
