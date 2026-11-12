"""
app/schemas/post.py — Canonical post, moderation, outbox event, tombstone, and DTO schemas.

Matches docs/Contract-B0-Schema.md §2–§4.
"""
from __future__ import annotations

from datetime import datetime
from enum import Enum
from typing import Dict, List, Literal, Optional, Union

from pydantic import Field, field_validator, model_validator

from app.schemas.common import LegacyCamelModel, StrictCamelModel
from app.schemas.taxonomy import AreaChoice, PostAreaId, PostCategoryId, TimeMode


class PostType(str, Enum):
    LOST = "LOST"
    FOUND = "FOUND"


class PostStatus(str, Enum):
    OPEN = "OPEN"
    RESOLVED = "RESOLVED"
    CLOSED = "CLOSED"


POST_STATUS_LEGACY_ALIASES: Dict[str, PostStatus] = {
    "ACTIVE": PostStatus.OPEN,
}


class ModerationStatus(str, Enum):
    VISIBLE = "VISIBLE"
    HIDDEN = "HIDDEN"


class EventChangeType(str, Enum):
    CREATE = "CREATE"
    UPDATE = "UPDATE"
    STATUS = "STATUS"
    DELETE = "DELETE"
    MODERATION = "MODERATION"
    BACKFILL = "BACKFILL"


class JobStatus(str, Enum):
    PENDING = "PENDING"
    PROCESSING = "PROCESSING"
    DONE = "DONE"
    FAILED = "FAILED"
    SKIPPED = "SKIPPED"


class JobState(StrictCamelModel):
    """Independent per-job state stored inside post_events/{eventId}."""

    status: JobStatus
    attempts: int = Field(..., ge=0)
    claim_token: Optional[str] = None
    lock_owner: Optional[str] = None
    lock_expires_at: Optional[datetime] = None
    next_retry_at: Optional[datetime] = None
    last_error: Optional[str] = None

    @classmethod
    def initial(cls) -> "JobState":
        """Explicit factory for creating an unstarted PENDING job state."""
        return cls(
            status=JobStatus.PENDING,
            attempts=0,
            claim_token=None,
            lock_owner=None,
            lock_expires_at=None,
            next_retry_at=None,
            last_error=None,
        )


class PostSummary(StrictCamelModel):
    """Lightweight post summary returned from search/list projections."""

    id: str = Field(..., min_length=1)
    title: str = Field(..., min_length=1, max_length=80)
    type: PostType
    status: PostStatus
    moderation_status: ModerationStatus = ModerationStatus.VISIBLE
    area_id: Union[PostAreaId, Literal[""]] = ""
    category_id: PostCategoryId
    created_at: datetime


class PostCanonical(StrictCamelModel):
    """
    Strict canonical schema for posts/{postId}.

    Note: moderationReason lives in post_moderations/{postId} (R1) and
    embeddings live in post_embeddings/{postId} (R3); neither is allowed here.
    """

    id: Optional[str] = Field(default=None, min_length=1)
    title: str = Field(..., min_length=1, max_length=80)
    type: PostType
    category_id: PostCategoryId
    category: Optional[str] = None
    description: str = Field(..., min_length=10, max_length=500)
    area_choice: AreaChoice
    area_id: Union[PostAreaId, Literal[""]]
    area_other_text: str = ""
    location: str = ""
    location_note: str = ""
    occurred_at: str = ""
    time_mode: TimeMode
    exact_at_millis: Optional[int] = None
    range_start_millis: Optional[int] = None
    range_end_millis: Optional[int] = None
    time_note: str = ""
    status: PostStatus
    moderation_status: ModerationStatus
    version: int = Field(..., ge=1)
    created_at: datetime
    updated_at: datetime
    author_id: str = Field(..., min_length=1)
    author_name: str = ""
    contact_email: str = ""
    allow_contact: bool = False
    media_asset_ids: List[str] = Field(default_factory=list, max_length=1)
    image_url: Optional[str] = None
    image_urls: List[str] = Field(default_factory=list)
    image_public_id: Optional[str] = None

    @model_validator(mode="after")
    def _validate_cross_fields(self) -> "PostCanonical":
        # Area choice <-> areaId / areaOtherText invariants
        if self.area_choice == AreaChoice.KNOWN:
            if not isinstance(self.area_id, PostAreaId):
                raise ValueError("areaChoice='KNOWN' requires a canonical non-empty areaId.")
        elif self.area_choice == AreaChoice.OTHER:
            if self.area_id != "":
                raise ValueError("areaChoice='OTHER' requires areaId == ''.")
            if not self.area_other_text:
                raise ValueError("areaChoice='OTHER' requires non-empty areaOtherText.")
        elif self.area_choice == AreaChoice.UNKNOWN:
            if self.area_id != "":
                raise ValueError("areaChoice='UNKNOWN' requires areaId == ''.")

        # Time mode invariants
        if self.time_mode == TimeMode.EXACT:
            if self.exact_at_millis is None:
                raise ValueError("timeMode='EXACT' requires exactAtMillis.")
        elif self.time_mode == TimeMode.RANGE:
            if self.range_start_millis is None or self.range_end_millis is None:
                raise ValueError("timeMode='RANGE' requires rangeStartMillis and rangeEndMillis.")
            if self.range_start_millis > self.range_end_millis:
                raise ValueError("rangeStartMillis must be <= rangeEndMillis.")
        elif self.time_mode == TimeMode.DESCRIBED:
            if not self.time_note:
                raise ValueError("timeMode='DESCRIBED' requires non-empty timeNote.")

        # Media asset <-> image URL/publicId projection invariants
        cleaned_assets = [asset_id.strip() for asset_id in self.media_asset_ids]
        if any(not asset_id for asset_id in cleaned_assets):
            raise ValueError("mediaAssetIds cannot contain empty strings.")
        self.media_asset_ids = cleaned_assets

        if len(self.media_asset_ids) == 0:
            if self.image_url is not None or self.image_urls or self.image_public_id is not None:
                raise ValueError(
                    "imageUrl, imageUrls, and imagePublicId must be empty/null when mediaAssetIds is empty."
                )
        else:
            if self.image_url is None or not self.image_url.startswith("https://"):
                raise ValueError("imageUrl must be an https:// URL when mediaAssetIds is non-empty.")
            if self.image_urls != [self.image_url]:
                raise ValueError("imageUrls must equal [imageUrl] when mediaAssetIds has 1 asset.")
            if self.image_public_id is not None and not self.image_public_id:
                raise ValueError("imagePublicId cannot be empty when provided.")

        return self


class LegacyPostProjection(LegacyCamelModel):
    """
    Permissive read/migration projection ONLY for pre-B0 legacy posts.

    Do NOT use this model to validate canonical writes or API payloads.
    """

    id: Optional[str] = None
    title: str = ""
    type: str = ""
    category_id: str = ""
    category: Optional[str] = None
    description: str = ""
    area_choice: Optional[str] = None
    area_id: str = ""
    area_other_text: str = ""
    location: str = ""
    location_note: str = ""
    occurred_at: str = ""
    time_mode: Optional[str] = None
    exact_at_millis: Optional[int] = None
    range_start_millis: Optional[int] = None
    range_end_millis: Optional[int] = None
    time_note: str = ""
    status: str = "OPEN"
    moderation_status: Optional[str] = None
    moderation_reason: Optional[str] = None
    version: Optional[int] = None
    created_at: Optional[datetime] = None
    updated_at: Optional[datetime] = None
    author_id: str = ""
    author_name: str = ""
    contact_email: str = ""
    allow_contact: Optional[bool] = None
    allow_email_contact: Optional[bool] = None
    media_asset_ids: List[str] = Field(default_factory=list)
    image_url: Optional[str] = None
    image_urls: List[str] = Field(default_factory=list)
    image_uri: Optional[str] = None
    image_public_id: Optional[str] = None


class PersistedPostEvent(StrictCamelModel):
    """
    Strict persisted schema for post_events/{eventId}.

    All 3 independent job sub-maps MUST be present; missing jobs are rejected.
    Use build_initial() when constructing a new event.
    """

    post_id: str = Field(..., min_length=1)
    author_id: str = Field(..., min_length=1)
    post_version: int = Field(..., ge=1)
    change_type: EventChangeType
    created_at: datetime
    image_asset_refs: List[str] = Field(default_factory=list, max_length=1)
    search_job: JobState
    notify_job: JobState
    media_cleanup_job: JobState

    @field_validator("image_asset_refs")
    @classmethod
    def _validate_image_asset_refs(cls, value: List[str]) -> List[str]:
        cleaned = [ref.strip() for ref in value]
        if any(not ref for ref in cleaned):
            raise ValueError("imageAssetRefs cannot contain empty strings.")
        return cleaned

    @classmethod
    def build_initial(
        cls,
        *,
        post_id: str,
        author_id: str,
        post_version: int,
        change_type: EventChangeType,
        created_at: datetime,
        image_asset_refs: Optional[List[str]] = None,
    ) -> "PersistedPostEvent":
        """Explicit factory initializing all 3 job sub-maps as PENDING."""
        return cls(
            post_id=post_id,
            author_id=author_id,
            post_version=post_version,
            change_type=change_type,
            created_at=created_at,
            image_asset_refs=list(image_asset_refs or []),
            search_job=JobState.initial(),
            notify_job=JobState.initial(),
            media_cleanup_job=JobState.initial(),
        )


class PostModeration(StrictCamelModel):
    """Author-readable + server-managed moderation status/reason at post_moderations/{postId}."""

    post_id: str = Field(..., min_length=1)
    author_id: str = Field(..., min_length=1)
    moderation_status: ModerationStatus
    reason: Optional[str] = Field(default=None, max_length=500)
    updated_at: datetime


class AdminModerationNote(StrictCamelModel):
    """Server-only internal admin note at admin_moderation_notes/{postId}."""

    post_id: str = Field(..., min_length=1)
    note: str = Field(..., min_length=1, max_length=2000)
    updated_by: str = Field(..., min_length=1)
    updated_at: datetime


class PostTombstone(StrictCamelModel):
    """Minimal anti-ID-reuse record at post_tombstones/{postId}."""

    author_id: str = Field(..., min_length=1)
    deleted_at: datetime


class WakeResponse(StrictCamelModel):
    processed: int
    message: str
