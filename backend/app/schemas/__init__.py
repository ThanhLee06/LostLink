"""app/schemas/__init__.py — B0 schemas up to outbox."""
from __future__ import annotations

from app.schemas.account import AccountStateResponse, AccountStateStatus, DeletionRequestResponse
from app.schemas.common import CamelModel, ErrorResponse, HealthResponse, LegacyCamelModel, MessageResponse, PaginatedResponse, StrictCamelModel
from app.schemas.post import (
    POST_STATUS_LEGACY_ALIASES, AdminModerationNote, EventChangeType, JobState, JobStatus,
    LegacyPostProjection, ModerationStatus, PersistedPostEvent, PostCanonical, PostModeration,
    PostStatus, PostSummary, PostTombstone, PostType, WakeResponse,
)
from app.schemas.report import ReportCreate, ReportRead, ReportReason, ReportStatus
from app.schemas.taxonomy import (
    AREA_LABELS, AREA_LEGACY_ALIASES, CATEGORY_LABELS, CATEGORY_LEGACY_ALIASES,
    AreaChoice, PostAreaId, PostCategoryId, TimeMode, normalize_area_id, normalize_category_id,
)

__all__ = [
    "AREA_LABELS", "AREA_LEGACY_ALIASES", "AccountStateResponse", "AccountStateStatus", "AdminModerationNote",
    "AreaChoice", "CATEGORY_LABELS", "CATEGORY_LEGACY_ALIASES", "CamelModel", "DeletionRequestResponse",
    "ErrorResponse", "EventChangeType", "HealthResponse", "JobState", "JobStatus", "LegacyCamelModel",
    "LegacyPostProjection", "MessageResponse", "ModerationStatus", "POST_STATUS_LEGACY_ALIASES",
    "PaginatedResponse", "PersistedPostEvent", "PostAreaId", "PostCanonical", "PostCategoryId",
    "PostModeration", "PostStatus", "PostSummary", "PostTombstone", "PostType", "ReportCreate",
    "ReportRead", "ReportReason", "ReportStatus", "StrictCamelModel", "TimeMode", "WakeResponse",
    "normalize_area_id", "normalize_category_id",
]
