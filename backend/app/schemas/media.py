"""
app/schemas/media.py — Media asset registry, post reservation, and signed upload DTOs.

Matches docs/Contract-B0-Schema.md §3.6 and §4.2 (R4).
"""
from __future__ import annotations

from datetime import datetime
from enum import Enum
from typing import Any, Dict, Optional

from pydantic import Field

from app.schemas.common import StrictCamelModel


class MediaAssetStatus(str, Enum):
    UPLOADING = "UPLOADING"
    READY = "READY"
    DELETING = "DELETING"
    DELETED = "DELETED"


class PostReservation(StrictCamelModel):
    """Server-managed draft post ID reservation at post_reservations/{postId}."""

    post_id: str = Field(..., min_length=1)
    owner_uid: str = Field(..., min_length=1)
    created_at: datetime


class MediaAsset(StrictCamelModel):
    """Canonical Firestore schema for media_assets/{assetId}. postId is mandatory."""

    asset_id: str = Field(..., min_length=1)
    owner_uid: str = Field(..., min_length=1)
    post_id: str = Field(..., min_length=1)
    cloudinary_public_id: str = Field(..., min_length=1)
    cloudinary_asset_id: Optional[str] = None
    secure_url: Optional[str] = None
    status: MediaAssetStatus
    created_at: datetime


class SignedUploadRequest(StrictCamelModel):
    """Request body for POST /api/v1/media/uploads. postId (clientPostId or existing ID) is required."""

    post_id: str = Field(..., min_length=1)


class SignedUploadResponse(StrictCamelModel):
    """
    Response from POST /api/v1/media/uploads.
    The inner `params` dictionary retains Cloudinary's multipart parameter keys.
    """

    asset_id: str = Field(..., min_length=1)
    upload_url: str = Field(..., min_length=1)
    public_id: str = Field(..., min_length=1)
    params: Dict[str, Any]


class CompleteUploadResponse(StrictCamelModel):
    """Response from POST /api/v1/media/uploads/{assetId}/complete."""

    asset_id: str = Field(..., min_length=1)
    secure_url: str = Field(..., min_length=1)
    public_id: str = Field(..., min_length=1)
    status: MediaAssetStatus
