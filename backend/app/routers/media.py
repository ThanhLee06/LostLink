"""
app/routers/media.py — Media upload endpoints.

POST /api/v1/media/uploads
POST /api/v1/media/uploads/{assetId}/complete
"""
from __future__ import annotations

from typing import Any, Dict

from fastapi import APIRouter, Depends, Path

from app.core.auth import require_active_student
from app.schemas.media import (
    CompleteUploadResponse,
    SignedUploadRequest,
    SignedUploadResponse,
)
from app.services.media_service import complete_upload, generate_signed_upload

router = APIRouter(prefix="/api/v1/media", tags=["media"])


@router.post("/uploads", response_model=SignedUploadResponse)
async def request_upload(
    body: SignedUploadRequest,
    decoded: Dict[str, Any] = Depends(require_active_student),
) -> SignedUploadResponse:
    """
    Generate Cloudinary signed upload parameters and register a media_assets
    document bound to the required non-empty postId.
    """
    uid: str = decoded["uid"]
    return await generate_signed_upload(uid=uid, post_id=body.post_id)


@router.post("/uploads/{asset_id}/complete", response_model=CompleteUploadResponse)
async def complete_upload_endpoint(
    asset_id: str = Path(..., min_length=1, description="The assetId returned from /uploads"),
    decoded: Dict[str, Any] = Depends(require_active_student),
) -> CompleteUploadResponse:
    """
    Confirm a completed Cloudinary upload via Admin API, check ownership/limits,
    and mark media_assets/{assetId} READY.
    """
    uid: str = decoded["uid"]
    return await complete_upload(asset_id=asset_id, caller_uid=uid)
