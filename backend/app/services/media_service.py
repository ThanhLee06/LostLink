"""
app/services/media_service.py — Cloudinary signed upload generation, verification, and cleanup.

Contract §3.6 & §4.2:
- `postId` is mandatory on every upload & asset record (forbids null `postId`).
- Signed upload parameters omit unsupported `max_bytes`; /complete verifies
  `bytes <= 5MB`, `format`, `resource_type`, `public_id`, and `https://` URL
  against the Cloudinary Admin API.
"""
from __future__ import annotations

import asyncio
import logging
import time
import uuid
from contextlib import suppress
from datetime import datetime, timezone
from typing import Any, Dict, Tuple

import cloudinary.uploader
import cloudinary.utils
import httpx
from fastapi import status
from google.cloud import firestore as _fs
from google.cloud.firestore import Client as FirestoreClient

from app.core.auth import parse_account_state_snapshot
from app.core.config import Settings, get_settings
from app.core.errors import ApiHTTPException, redact_sensitive_text
from app.core.firebase import get_firestore
from app.schemas.account import AccountStateStatus
from app.schemas.media import (
    CompleteUploadResponse,
    MediaAssetStatus,
    SignedUploadResponse,
)

logger = logging.getLogger(__name__)

_MAX_BYTES = 5 * 1024 * 1024  # 5 MB
_ALLOWED_FORMATS_STR = "jpg,png,webp"
_ALLOWED_FORMATS_SET = frozenset({"jpg", "jpeg", "png", "webp"})
_CLOUDINARY_UPLOAD_URL = "https://api.cloudinary.com/v1_1/{cloud_name}/image/upload"


def _require_cloudinary_config(settings: Settings) -> None:
    if (
        not settings.cloudinary_cloud_name.strip()
        or not settings.cloudinary_api_key.strip()
        or not settings.cloudinary_api_secret.strip()
    ):
        raise ApiHTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="Media upload service is not configured.",
            code="MEDIA_NOT_CONFIGURED",
        )


def _reserve_and_create_asset(
    db: FirestoreClient,
    *,
    uid: str,
    post_id: str,
    asset_id: str,
    public_id: str,
) -> None:
    """
    Atomically verify active account state, tombstone absence, post ownership /
    draft reservation, and register media_assets/{assetId}. All reads precede writes.
    """
    account_ref = db.collection("account_states").document(uid)
    tombstone_ref = db.collection("post_tombstones").document(post_id)
    post_ref = db.collection("posts").document(post_id)
    reservation_ref = db.collection("post_reservations").document(post_id)
    asset_ref = db.collection("media_assets").document(asset_id)

    @_fs.transactional
    def _txn(transaction: _fs.Transaction) -> None:
        # Read ALL authoritative documents inside the transaction before writing.
        acct_snap = account_ref.get(transaction=transaction)
        tomb_snap = tombstone_ref.get(transaction=transaction)
        post_snap = post_ref.get(transaction=transaction)
        res_snap = reservation_ref.get(transaction=transaction)

        if parse_account_state_snapshot(acct_snap, uid=uid) != AccountStateStatus.ACTIVE:
            raise ApiHTTPException(
                status_code=status.HTTP_403_FORBIDDEN,
                detail="Account is scheduled for deletion and cannot upload media.",
                code="ACCOUNT_INACTIVE",
            )

        if tomb_snap.exists:
            raise ApiHTTPException(
                status_code=status.HTTP_409_CONFLICT,
                detail="Cannot upload media for a deleted post ID.",
                code="POST_TOMBSTONED",
            )

        if post_snap.exists:
            post_data = post_snap.to_dict() or {}
            if post_data.get("authorId") != uid:
                raise ApiHTTPException(
                    status_code=status.HTTP_403_FORBIDDEN,
                    detail="You do not own this post.",
                    code="FORBIDDEN",
                )
            if post_data.get("moderationStatus") != "VISIBLE":
                raise ApiHTTPException(
                    status_code=status.HTTP_403_FORBIDDEN,
                    detail="Hidden posts cannot be edited or receive new media.",
                    code="POST_HIDDEN",
                )
        else:
            if res_snap.exists:
                res_data = res_snap.to_dict() or {}
                if res_data.get("ownerUid") != uid:
                    raise ApiHTTPException(
                        status_code=status.HTTP_403_FORBIDDEN,
                        detail="Post ID is reserved by another account.",
                        code="RESERVATION_CONFLICT",
                    )
            else:
                transaction.set(
                    reservation_ref,
                    {
                        "postId": post_id,
                        "ownerUid": uid,
                        "createdAt": _fs.SERVER_TIMESTAMP,
                    },
                )

        transaction.set(
            asset_ref,
            {
                "assetId": asset_id,
                "ownerUid": uid,
                "postId": post_id,
                "cloudinaryPublicId": public_id,
                "cloudinaryAssetId": None,
                "secureUrl": None,
                "status": MediaAssetStatus.UPLOADING.value,
                "cleanupPending": False,
                "createdAt": _fs.SERVER_TIMESTAMP,
            },
        )

    _txn(db.transaction())


async def generate_signed_upload(uid: str, post_id: str) -> SignedUploadResponse:
    """
    Validate post ownership or draft reservation, register media_assets/{assetId},
    and return Cloudinary signed upload parameters.
    """
    settings = get_settings()
    _require_cloudinary_config(settings)

    cleaned_post_id = post_id.strip()
    if not cleaned_post_id:
        raise ApiHTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail="postId must be a non-empty string.",
            code="VALIDATION_ERROR",
        )

    asset_id = str(uuid.uuid4())
    public_id = f"lostlink/{uid}/{asset_id}"
    timestamp = int(time.time())

    db = get_firestore()
    await asyncio.to_thread(
        _reserve_and_create_asset,
        db,
        uid=uid,
        post_id=cleaned_post_id,
        asset_id=asset_id,
        public_id=public_id,
    )

    sign_params: Dict[str, Any] = {
        "public_id": public_id,
        "timestamp": timestamp,
        "allowed_formats": _ALLOWED_FORMATS_STR,
        "overwrite": "false",
    }
    preset = settings.cloudinary_upload_preset.strip()
    if preset:
        sign_params["upload_preset"] = preset

    signature = cloudinary.utils.api_sign_request(
        sign_params,
        settings.cloudinary_api_secret,
        algorithm=settings.cloudinary_signature_algorithm,
    )

    upload_params: Dict[str, Any] = {
        **sign_params,
        "api_key": settings.cloudinary_api_key,
        "signature": signature,
    }
    upload_url = _CLOUDINARY_UPLOAD_URL.format(cloud_name=settings.cloudinary_cloud_name)

    logger.info("media_assets/%s created for uid=%s postId=%s", asset_id, uid, cleaned_post_id)
    return SignedUploadResponse(
        asset_id=asset_id,
        upload_url=upload_url,
        public_id=public_id,
        params=upload_params,
    )


def _preflight_complete(
    db: FirestoreClient, asset_id: str, caller_uid: str
) -> Tuple[str, str | None, str, bool]:
    """
    Read media_assets/{asset_id} and enforce ownership, state, and binding
    before calling Cloudinary Admin API.
    Returns `(public_id, existing_ready_url_or_None, status_str, cleanup_late_upload)`.
    """
    snap = db.collection("media_assets").document(asset_id).get()
    if not snap.exists:
        raise ApiHTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"Asset {asset_id} not found.",
            code="ASSET_NOT_FOUND",
        )
    data = snap.to_dict() or {}
    if data.get("ownerUid") != caller_uid:
        raise ApiHTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="You do not own this asset.",
            code="FORBIDDEN",
        )

    expected_public_id = str(data.get("cloudinaryPublicId") or "")
    cur_status = str(data.get("status") or "")
    post_id = str(data.get("postId") or "")

    post_snap = (
        db.collection("posts").document(post_id).get() if post_id else None
    )
    attached_to_post = bool(
        post_snap is not None
        and post_snap.exists
        and asset_id in set((post_snap.to_dict() or {}).get("mediaAssetIds") or [])
    )

    if cur_status == MediaAssetStatus.DELETED.value:
        # Late /complete after sweep/cleanup: signal durable cleanup if unattached.
        return expected_public_id, None, cur_status, not attached_to_post

    if cur_status == MediaAssetStatus.DELETING.value:
        if data.get("cleanupPending") is True and not attached_to_post:
            return expected_public_id, None, cur_status, True
        raise ApiHTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail=f"Asset {asset_id} is {cur_status} and cannot be completed.",
            code="ASSET_DELETING",
        )

    existing_url = data.get("secureUrl")
    if cur_status == MediaAssetStatus.READY.value and isinstance(existing_url, str) and existing_url:
        # Never bypass authoritative checks via early return: signal existing_url so
        # complete_upload skips Cloudinary Admin HTTP but still runs _finalize_ready_transaction.
        return expected_public_id, existing_url, MediaAssetStatus.READY.value, False

    acct_snap = db.collection("account_states").document(caller_uid).get()
    if parse_account_state_snapshot(acct_snap, uid=caller_uid) != AccountStateStatus.ACTIVE:
        if cur_status == MediaAssetStatus.UPLOADING.value and not attached_to_post:
            _mark_uploading_asset_deleting_if_unattached(
                db, asset_id=asset_id, post_id=post_id
            )
        raise ApiHTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Account is scheduled for deletion and cannot complete media upload.",
            code="ACCOUNT_INACTIVE",
        )
    if post_id and db.collection("post_tombstones").document(post_id).get().exists:
        if cur_status == MediaAssetStatus.UPLOADING.value and not attached_to_post:
            _mark_uploading_asset_deleting_if_unattached(
                db, asset_id=asset_id, post_id=post_id
            )
        raise ApiHTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="Post has been deleted.",
            code="POST_TOMBSTONED",
        )

    return expected_public_id, None, cur_status, False


def _mark_uploading_asset_deleting_if_unattached(
    db: FirestoreClient, *, asset_id: str, post_id: str
) -> None:
    """Safely flag an unattached UPLOADING asset as DELETING + cleanupPending=True."""
    asset_ref = db.collection("media_assets").document(asset_id)

    @_fs.transactional
    def _txn(transaction: _fs.Transaction) -> None:
        asset_snap = asset_ref.get(transaction=transaction)
        if not asset_snap.exists:
            return
        asset_data = asset_snap.to_dict() or {}
        if asset_data.get("status") != MediaAssetStatus.UPLOADING.value:
            return
        if post_id:
            post_snap = db.collection("posts").document(post_id).get(
                transaction=transaction
            )
            if post_snap.exists and asset_id in set(
                (post_snap.to_dict() or {}).get("mediaAssetIds") or []
            ):
                return
        transaction.update(
            asset_ref,
            {
                "status": MediaAssetStatus.DELETING.value,
                "cleanupPending": True,
            },
        )

    _txn(db.transaction())


def _evaluate_authoritative_rejection(
    *,
    asset_id: str,
    caller_uid: str,
    asset_data: Dict[str, Any],
    acct_snap: Any,
    tomb_snap: Any,
    post_snap: Any,
    res_snap: Any,
    ttl_seconds: int | None = None,
) -> Tuple[int, str, str] | None:
    """Return `(http_status, code, detail)` if completion must be rejected, else None."""
    cur_status = str(asset_data.get("status") or "")
    if cur_status in (MediaAssetStatus.DELETING.value, MediaAssetStatus.DELETED.value):
        return (
            status.HTTP_409_CONFLICT,
            "ASSET_DELETING",
            f"Asset {asset_id} is {cur_status} and cannot be marked READY.",
        )

    if parse_account_state_snapshot(acct_snap, uid=caller_uid) != AccountStateStatus.ACTIVE:
        return (
            status.HTTP_403_FORBIDDEN,
            "ACCOUNT_INACTIVE",
            "Account is scheduled for deletion and cannot complete media upload.",
        )

    if tomb_snap.exists:
        return (
            status.HTTP_409_CONFLICT,
            "POST_TOMBSTONED",
            "Cannot attach media to a deleted post.",
        )

    if post_snap.exists:
        post_data = post_snap.to_dict() or {}
        if post_data.get("authorId") != caller_uid:
            return (
                status.HTTP_403_FORBIDDEN,
                "FORBIDDEN",
                "You do not own the target post.",
            )
        if post_data.get("moderationStatus") != "VISIBLE":
            return (
                status.HTTP_403_FORBIDDEN,
                "POST_HIDDEN",
                "Hidden posts cannot receive new media.",
            )
        return None

    if not res_snap.exists:
        return (
            status.HTTP_409_CONFLICT,
            "POST_DELETED",
            "Target post or draft reservation no longer exists.",
        )

    res_data = res_snap.to_dict() or {}
    if res_data.get("ownerUid") != caller_uid:
        return (
            status.HTTP_403_FORBIDDEN,
            "RESERVATION_CONFLICT",
            "Post ID is reserved by another account.",
        )

    if ttl_seconds is not None:
        created_at = asset_data.get("createdAt")
        if isinstance(created_at, datetime):
            norm_created = (
                created_at.replace(tzinfo=timezone.utc)
                if created_at.tzinfo is None
                else created_at.astimezone(timezone.utc)
            )
            if (datetime.now(tz=timezone.utc) - norm_created).total_seconds() >= ttl_seconds:
                return (
                    status.HTTP_409_CONFLICT,
                    "ASSET_EXPIRED",
                    "Draft upload reservation expired before post submission.",
                )
    return None


def _finalize_ready_transaction(
    db: FirestoreClient,
    *,
    asset_id: str,
    caller_uid: str,
    cloudinary_asset_id: str,
    secure_url: str,
) -> Tuple[str, Any]:
    """
    Atomically re-read authoritative account/tombstone/post/reservation state
    inside the transaction before marking READY.

    On authoritative rejection after Cloudinary verification, persists
    `status="DELETING", cleanupPending=True` (unless already attached to a post)
    and returns `("REJECT", (http_status, code, detail, should_cleanup, public_id))`
    so the caller can clean up Cloudinary safely outside the transaction.
    """
    asset_ref = db.collection("media_assets").document(asset_id)

    @_fs.transactional
    def _txn(transaction: _fs.Transaction) -> Tuple[str, Any]:
        asset_snap = asset_ref.get(transaction=transaction)
        if not asset_snap.exists:
            raise ApiHTTPException(
                status_code=status.HTTP_404_NOT_FOUND,
                detail=f"Asset {asset_id} not found.",
                code="ASSET_NOT_FOUND",
            )
        asset_data = asset_snap.to_dict() or {}
        if asset_data.get("ownerUid") != caller_uid:
            raise ApiHTTPException(
                status_code=status.HTTP_403_FORBIDDEN,
                detail="You do not own this asset.",
                code="FORBIDDEN",
            )

        post_id = str(asset_data.get("postId") or "")
        if not post_id.strip():
            raise ApiHTTPException(
                status_code=status.HTTP_409_CONFLICT,
                detail="Asset is missing required postId.",
                code="INVALID_MEDIA_ASSET",
            )

        # Read all authoritative refs BEFORE any transaction write.
        acct_snap = db.collection("account_states").document(caller_uid).get(
            transaction=transaction
        )
        tomb_snap = db.collection("post_tombstones").document(post_id).get(
            transaction=transaction
        )
        post_snap = db.collection("posts").document(post_id).get(
            transaction=transaction
        )
        res_snap = db.collection("post_reservations").document(post_id).get(
            transaction=transaction
        )

        attached_to_post = bool(
            post_snap.exists
            and asset_id in set((post_snap.to_dict() or {}).get("mediaAssetIds") or [])
        )
        public_id = str(asset_data.get("cloudinaryPublicId") or "")

        rejection = _evaluate_authoritative_rejection(
            asset_id=asset_id,
            caller_uid=caller_uid,
            asset_data=asset_data,
            acct_snap=acct_snap,
            tomb_snap=tomb_snap,
            post_snap=post_snap,
            res_snap=res_snap,
            ttl_seconds=get_settings().media_abandoned_ttl_seconds,
        )
        if rejection is not None:
            rej_status, rej_code, rej_detail = rejection
            cur_status = str(asset_data.get("status") or "")
            # Protect assets already attached to a post, and do not auto-destroy/mark DELETING
            # an already-READY asset merely rejected because the target post is HIDDEN.
            should_cleanup = (
                not attached_to_post
                and not (
                    cur_status == MediaAssetStatus.READY.value
                    and rej_code == "POST_HIDDEN"
                )
            )
            if should_cleanup:
                transaction.update(
                    asset_ref,
                    {
                        "status": MediaAssetStatus.DELETING.value,
                        "cleanupPending": True,
                        "nextCleanupAt": None,
                    },
                )
            return (
                "REJECT",
                (rej_status, rej_code, rej_detail, should_cleanup, public_id, post_id),
            )

        if asset_data.get("status") == MediaAssetStatus.READY.value and asset_data.get(
            "secureUrl"
        ):
            return ("OK", str(asset_data["secureUrl"]))

        transaction.update(
            asset_ref,
            {
                "status": MediaAssetStatus.READY.value,
                "cleanupPending": False,
                "cloudinaryAssetId": cloudinary_asset_id,
                "secureUrl": secure_url,
            },
        )
        return ("OK", secure_url)

    return _txn(db.transaction())


def _arm_late_cleanup_sync(
    db: FirestoreClient, *, asset_id: str, caller_uid: str
) -> Tuple[bool, str, str, str]:
    """
    Atomically re-arm `status="DELETING", cleanupPending=True` BEFORE invoking
    external Cloudinary cleanup on a late `/complete` call for a DELETED/DELETING
    unattached asset.
    """
    asset_ref = db.collection("media_assets").document(asset_id)

    @_fs.transactional
    def _txn(transaction: _fs.Transaction) -> Tuple[bool, str, str, str]:
        asset_snap = asset_ref.get(transaction=transaction)
        if not asset_snap.exists:
            raise ApiHTTPException(
                status_code=status.HTTP_404_NOT_FOUND,
                detail=f"Asset {asset_id} not found.",
                code="ASSET_NOT_FOUND",
            )
        asset_data = asset_snap.to_dict() or {}
        if asset_data.get("ownerUid") != caller_uid:
            raise ApiHTTPException(
                status_code=status.HTTP_403_FORBIDDEN,
                detail="You do not own this asset.",
                code="FORBIDDEN",
            )
        post_id = str(asset_data.get("postId") or "")
        if post_id:
            post_snap = db.collection("posts").document(post_id).get(
                transaction=transaction
            )
            if post_snap.exists and asset_id in set(
                (post_snap.to_dict() or {}).get("mediaAssetIds") or []
            ):
                if (
                    asset_data.get("cleanupPending") is True
                    or asset_data.get("nextCleanupAt") is not None
                ):
                    transaction.update(
                        asset_ref,
                        {"cleanupPending": False, "nextCleanupAt": None},
                    )
                return False, str(asset_data.get("status") or ""), "", ""

        cur_status = str(asset_data.get("status") or "")
        if cur_status not in (
            MediaAssetStatus.DELETING.value,
            MediaAssetStatus.DELETED.value,
        ):
            return False, cur_status, "", ""

        transaction.update(
            asset_ref,
            {
                "status": MediaAssetStatus.DELETING.value,
                "cleanupPending": True,
                "nextCleanupAt": None,
            },
        )
        return (
            True,
            MediaAssetStatus.DELETING.value,
            str(asset_data.get("cloudinaryPublicId") or ""),
            post_id,
        )

    return _txn(db.transaction())


async def _cleanup_rejected_upload(
    db: FirestoreClient,
    *,
    asset_id: str,
    caller_uid: str,
    public_id: str,
    post_id: str,
    settings: Settings,
) -> None:
    """
    Destroy an unattached rejected/late Cloudinary asset and finalize registry state
    via `media_cleanup_handler.execute_asset_cleanup`, keeping signed-upload
    reconciliation and durable backoff retry uniform with worker cleanup.
    """
    from app.worker.handlers import media_cleanup_handler

    await media_cleanup_handler.execute_asset_cleanup(
        db,
        asset_id=asset_id,
        author_id=caller_uid,
        public_id=public_id,
        expected_post_id=post_id,
        expected_public_id=public_id,
        require_owner=True,
        settings=settings,
        reraise=False,
        failure_context="rejected/late API cleanup",
    )


async def complete_upload(asset_id: str, caller_uid: str) -> CompleteUploadResponse:
    """
    Verify uploaded asset in Cloudinary, validate format/size/publicId, and
    atomically re-check authoritative account/post/tombstone state before returning.
    Idempotent for active READY assets without re-calling Cloudinary Admin API.
    """
    settings = get_settings()
    _require_cloudinary_config(settings)
    db = get_firestore()

    expected_public_id, existing_url, cur_status, cleanup_late = await asyncio.to_thread(
        _preflight_complete, db, asset_id, caller_uid
    )
    if cleanup_late:
        should_cleanup, armed_status, late_public_id, late_post_id = await asyncio.to_thread(
            _arm_late_cleanup_sync,
            db,
            asset_id=asset_id,
            caller_uid=caller_uid,
        )
        if should_cleanup:
            await _cleanup_rejected_upload(
                db,
                asset_id=asset_id,
                caller_uid=caller_uid,
                public_id=late_public_id,
                post_id=late_post_id,
                settings=settings,
            )
        raise ApiHTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail=f"Asset {asset_id} is {armed_status or cur_status} and cannot be completed.",
            code="ASSET_DELETING",
        )

    if existing_url is not None:
        cloudinary_asset_id = ""
        secure_url = existing_url
    else:
        cloudinary_asset_id, secure_url = await _verify_cloudinary_asset(
            public_id=expected_public_id,
            settings=settings,
        )

    outcome, payload = await asyncio.to_thread(
        _finalize_ready_transaction,
        db,
        asset_id=asset_id,
        caller_uid=caller_uid,
        cloudinary_asset_id=cloudinary_asset_id,
        secure_url=secure_url,
    )
    if outcome == "REJECT":
        (
            rej_status,
            rej_code,
            rej_detail,
            should_cleanup,
            cleanup_public_id,
            cleanup_post_id,
        ) = payload
        if should_cleanup:
            await _cleanup_rejected_upload(
                db,
                asset_id=asset_id,
                caller_uid=caller_uid,
                public_id=cleanup_public_id,
                post_id=cleanup_post_id,
                settings=settings,
            )
        raise ApiHTTPException(
            status_code=rej_status,
            detail=rej_detail,
            code=rej_code,
        )

    final_url = str(payload)
    logger.info("media_assets/%s marked READY", asset_id)
    return CompleteUploadResponse(
        asset_id=asset_id,
        secure_url=final_url,
        public_id=expected_public_id,
        status=MediaAssetStatus.READY,
    )


async def _verify_cloudinary_asset(
    *,
    public_id: str,
    settings: Settings,
) -> Tuple[str, str]:
    """Query Cloudinary Admin API and strictly validate uploaded image constraints."""
    url = (
        f"https://api.cloudinary.com/v1_1/{settings.cloudinary_cloud_name}"
        f"/resources/image/upload/{public_id}"
    )
    async with httpx.AsyncClient() as client:
        response = await client.get(
            url,
            auth=(settings.cloudinary_api_key, settings.cloudinary_api_secret),
            timeout=10.0,
        )

    if response.status_code == 404:
        raise ApiHTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Asset not found in Cloudinary. Upload may not have completed.",
            code="CLOUDINARY_ASSET_NOT_FOUND",
        )
    if not response.is_success:
        logger.error("Cloudinary Admin API error status=%d", response.status_code)
        raise ApiHTTPException(
            status_code=status.HTTP_502_BAD_GATEWAY,
            detail="Failed to verify asset with Cloudinary.",
            code="CLOUDINARY_UPSTREAM_ERROR",
        )

    data: Dict[str, Any] = response.json()
    returned_public_id = str(data.get("public_id") or "")
    if returned_public_id != public_id:
        raise ApiHTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail="Cloudinary public_id mismatch.",
            code="INVALID_MEDIA_ASSET",
        )

    resource_type = str(data.get("resource_type") or "")
    fmt = str(data.get("format") or "").lower()
    size_bytes = data.get("bytes")
    secure_url = str(data.get("secure_url") or "")

    invalid_reason: str | None = None
    if resource_type != "image":
        invalid_reason = f"Unsupported resource_type: {resource_type or 'unknown'}"
    elif fmt not in _ALLOWED_FORMATS_SET:
        invalid_reason = f"Unsupported image format: {fmt or 'unknown'}"
    elif (
        isinstance(size_bytes, bool)
        or not isinstance(size_bytes, int)
        or size_bytes <= 0
        or size_bytes > _MAX_BYTES
    ):
        invalid_reason = f"Image size exceeds allowed limit ({_MAX_BYTES} bytes)."
    elif not secure_url.startswith("https://"):
        invalid_reason = "Cloudinary response missing valid https secure_url."

    if invalid_reason is not None:
        with suppress(Exception):
            await delete_cloudinary_asset(public_id, settings)
        raise ApiHTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=invalid_reason,
            code="INVALID_MEDIA_ASSET",
        )

    return str(data.get("asset_id") or ""), secure_url


async def delete_cloudinary_asset(public_id: str, settings: Settings) -> bool:
    """
    Idempotently destroy a Cloudinary image asset by public_id.
    Returns True if deleted (`ok`) or already absent (`not found`).
    """
    if not public_id.strip():
        return True

    if (
        not settings.cloudinary_cloud_name.strip()
        or not settings.cloudinary_api_key.strip()
        or not settings.cloudinary_api_secret.strip()
    ):
        raise RuntimeError("Cloudinary credentials are not configured.")

    response = await asyncio.to_thread(
        cloudinary.uploader.destroy,
        public_id,
        cloud_name=settings.cloudinary_cloud_name,
        api_key=settings.cloudinary_api_key,
        api_secret=settings.cloudinary_api_secret,
        invalidate=True,
        resource_type="image",
    )
    result = str((response or {}).get("result") or "")
    if result in ("ok", "not found"):
        return True

    raise RuntimeError(f"Unexpected Cloudinary destroy result: {result!r}")
