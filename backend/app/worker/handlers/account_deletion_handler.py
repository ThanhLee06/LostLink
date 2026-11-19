"""
app/worker/handlers/account_deletion_handler.py — Chunked account deletion worker (ST36 & ST37).

Implements:
- ST36: Chunked Firestore cleanup (posts, embeddings, moderations, reservations,
  saved_posts, users) with durable step checkpoints in `deletion_requests/{uid}.steps`,
  transactional lease claiming/fencing, and resumable retries.
- ST37: Cloudinary cleanup driven strictly by `media_assets` where `ownerUid == uid`
  (reusing `media_service.delete_cloudinary_asset`), plus legacy URL audit recording
  for old posts lacking `mediaAssetIds`.
- Finalization: deletes Firebase Auth user only after all Firestore and Cloudinary steps
  complete, then transitions `account_states/{uid} -> DELETED` and
  `deletion_requests/{uid} -> COMPLETED`.
"""
from __future__ import annotations

import asyncio
from datetime import datetime, timedelta, timezone
import logging
from typing import Any, Dict, List, Optional, Tuple
import uuid

import firebase_admin.auth as fb_auth
from google.cloud import firestore as _fs
from google.cloud.firestore import Client as FirestoreClient
from google.cloud.firestore_v1 import FieldFilter

from app.core.config import Settings, get_settings
from app.core.errors import redact_sensitive_text
from app.schemas.account import AccountStateStatus, DeletionStatus
from app.schemas.media import MediaAssetStatus
from app.services import media_service as _media_service
from app.services.account_deletion_service import (
    default_deletion_steps,
    revoke_user_sessions_sync,
)
from app.services.media_service import delete_cloudinary_asset
from app.worker.claim import ClaimError, TokenMismatchError

logger = logging.getLogger(__name__)

_IMPORTED_DELETE_CLOUDINARY_ASSET = delete_cloudinary_asset
_DEFAULT_MAX_ATTEMPTS = 5
_BACKOFF_BASE_SECONDS = 30
_MAX_LEGACY_AUDIT_ITEMS = 200
_DEFAULT_CHUNK_SIZE = 25


def _now_utc() -> datetime:
    return datetime.now(tz=timezone.utc)


def _to_utc(value: Any) -> Optional[datetime]:
    if not isinstance(value, datetime):
        return None
    if value.tzinfo is None:
        return value.replace(tzinfo=timezone.utc)
    return value.astimezone(timezone.utc)


def _normalize_steps(raw_steps: Any) -> Dict[str, Any]:
    steps = default_deletion_steps()
    if isinstance(raw_steps, dict):
        for key, value in raw_steps.items():
            if isinstance(value, dict) and isinstance(steps.get(key), dict):
                merged = dict(steps[key])
                merged.update(value)
                steps[key] = merged
            else:
                steps[key] = value
    return steps


async def _call_delete_cloudinary_asset(public_id: str, settings: Settings) -> bool:
    """Invoke Cloudinary destroy while honoring monkeypatches on either module."""
    if delete_cloudinary_asset is not _IMPORTED_DELETE_CLOUDINARY_ASSET:
        return await delete_cloudinary_asset(public_id, settings)
    return await _media_service.delete_cloudinary_asset(public_id, settings)


def _assert_deletion_lease(
    data: Dict[str, Any],
    claim_token: str,
    *,
    uid: str,
    now: Optional[datetime] = None,
) -> None:
    eff_now = now or _now_utc()
    if (
        data.get("status") != DeletionStatus.IN_PROGRESS.value
        or data.get("claimToken") != claim_token
    ):
        raise TokenMismatchError(f"Stale or invalid claimToken for deletion_requests/{uid}.")
    lock_expires = _to_utc(data.get("lockExpiresAt"))
    if lock_expires is None or eff_now >= lock_expires:
        raise TokenMismatchError(f"Expired worker lease for deletion_requests/{uid}.")


def query_pending_deletion_uids(
    db: FirestoreClient,
    *,
    limit: int,
    now: Optional[datetime] = None,
) -> List[str]:
    """
    Find claimable deletion_requests/{uid} using a single-field status filter
    (no composite index required).
    """
    if limit <= 0:
        return []
    eff_now = now or _now_utc()
    scan_limit = max(limit * 4, 25)
    snaps = list(
        db.collection("deletion_requests")
        .where(
            filter=FieldFilter(
                "status",
                "in",
                [DeletionStatus.PENDING.value, DeletionStatus.IN_PROGRESS.value],
            )
        )
        .limit(scan_limit)
        .stream()
    )
    uids: List[str] = []
    for snap in snaps:
        data = snap.to_dict() or {}
        req_status = str(data.get("status") or "")
        if req_status == DeletionStatus.PENDING.value:
            next_retry = _to_utc(data.get("nextRetryAt"))
            if next_retry is not None and next_retry > eff_now:
                continue
        elif req_status == DeletionStatus.IN_PROGRESS.value:
            lock_expires = _to_utc(data.get("lockExpiresAt"))
            if lock_expires is not None and lock_expires > eff_now:
                continue
        else:
            continue
        uids.append(snap.id)
        if len(uids) >= limit:
            break
    return uids


def claim_deletion_request(
    db: FirestoreClient,
    uid: str,
    worker_id: str,
    lease_seconds: int = 60,
    max_attempts: int = _DEFAULT_MAX_ATTEMPTS,
) -> Tuple[str, Dict[str, Any]]:
    """Atomically claim `deletion_requests/{uid}` with lease fencing and reclaim support."""
    req_ref = db.collection("deletion_requests").document(uid)

    @_fs.transactional
    def _txn(transaction: _fs.Transaction) -> Tuple[bool, Any]:
        snap = req_ref.get(transaction=transaction)
        if not snap.exists:
            raise ClaimError(f"deletion_requests/{uid} does not exist.")
        data = dict(snap.to_dict() or {})
        req_status = str(data.get("status") or "")
        attempts = int(data.get("attempts") or 0)
        now = _now_utc()

        if req_status == DeletionStatus.PENDING.value:
            if attempts >= max_attempts:
                transaction.update(
                    req_ref,
                    {
                        "status": DeletionStatus.FAILED.value,
                        "claimToken": None,
                        "lockOwner": None,
                        "lockExpiresAt": None,
                        "nextRetryAt": None,
                        "lastError": data.get("lastError") or "Max attempts reached.",
                        "updatedAt": _fs.SERVER_TIMESTAMP,
                    },
                )
                return False, f"deletion_requests/{uid} reached max attempts."
            next_retry = _to_utc(data.get("nextRetryAt"))
            if next_retry is not None and now < next_retry:
                raise ClaimError(f"deletion_requests/{uid} backed off until {next_retry.isoformat()}.")
            new_attempts = attempts
        elif req_status == DeletionStatus.IN_PROGRESS.value:
            lock_expires = _to_utc(data.get("lockExpiresAt"))
            if lock_expires is not None and now < lock_expires:
                raise ClaimError(f"deletion_requests/{uid} is actively locked.")
            new_attempts = attempts + 1
            if new_attempts >= max_attempts:
                transaction.update(
                    req_ref,
                    {
                        "status": DeletionStatus.FAILED.value,
                        "attempts": new_attempts,
                        "claimToken": None,
                        "lockOwner": None,
                        "lockExpiresAt": None,
                        "nextRetryAt": None,
                        "lastError": "Max attempts reached after expired worker lease.",
                        "updatedAt": _fs.SERVER_TIMESTAMP,
                    },
                )
                return False, f"deletion_requests/{uid} exceeded max attempts after expired lease."
        else:
            raise ClaimError(f"deletion_requests/{uid} not claimable (status={req_status}).")

        claim_token = str(uuid.uuid4())
        new_expires = now + timedelta(seconds=lease_seconds)
        steps = _normalize_steps(data.get("steps"))
        transaction.update(
            req_ref,
            {
                "status": DeletionStatus.IN_PROGRESS.value,
                "attempts": new_attempts,
                "claimToken": claim_token,
                "lockOwner": worker_id,
                "lockExpiresAt": new_expires,
                "nextRetryAt": None,
                "steps": steps,
                "updatedAt": _fs.SERVER_TIMESTAMP,
            },
        )
        data.update(
            {
                "status": DeletionStatus.IN_PROGRESS.value,
                "attempts": new_attempts,
                "claimToken": claim_token,
                "lockOwner": worker_id,
                "lockExpiresAt": new_expires,
                "steps": steps,
            }
        )
        return True, (claim_token, data)

    claimed, payload = _txn(db.transaction())
    if not claimed:
        raise ClaimError(str(payload))
    return payload


def _save_checkpoint_and_optionally_yield(
    db: FirestoreClient,
    *,
    uid: str,
    claim_token: str,
    steps: Dict[str, Any],
    yield_for_next_sweep: bool = False,
) -> None:
    """Persist step checkpoints atomically if the lease is still valid."""
    req_ref = db.collection("deletion_requests").document(uid)

    @_fs.transactional
    def _txn(transaction: _fs.Transaction) -> None:
        snap = req_ref.get(transaction=transaction)
        if not snap.exists:
            raise TokenMismatchError(f"deletion_requests/{uid} missing.")
        _assert_deletion_lease(snap.to_dict() or {}, claim_token, uid=uid)
        patch: Dict[str, Any] = {
            "steps": steps,
            "updatedAt": _fs.SERVER_TIMESTAMP,
        }
        if yield_for_next_sweep:
            patch.update(
                {
                    "status": DeletionStatus.PENDING.value,
                    "claimToken": None,
                    "lockOwner": None,
                    "lockExpiresAt": None,
                    "nextRetryAt": None,
                }
            )
        transaction.update(req_ref, patch)

    _txn(db.transaction())


def fail_deletion_request(
    db: FirestoreClient,
    *,
    uid: str,
    claim_token: str,
    error_message: str,
    steps: Optional[Dict[str, Any]] = None,
    max_attempts: int = _DEFAULT_MAX_ATTEMPTS,
) -> str:
    """Release lease and schedule backoff retry (or mark FAILED at max attempts)."""
    req_ref = db.collection("deletion_requests").document(uid)
    clean_err = redact_sensitive_text(error_message, max_length=300)

    @_fs.transactional
    def _txn(transaction: _fs.Transaction) -> str:
        snap = req_ref.get(transaction=transaction)
        if not snap.exists:
            raise TokenMismatchError(f"deletion_requests/{uid} missing.")
        data = snap.to_dict() or {}
        _assert_deletion_lease(data, claim_token, uid=uid)

        new_attempts = int(data.get("attempts") or 0) + 1
        now = _now_utc()
        if new_attempts >= max_attempts:
            final_status = DeletionStatus.FAILED.value
            next_retry = None
        else:
            final_status = DeletionStatus.PENDING.value
            delay = _BACKOFF_BASE_SECONDS * (2 ** (new_attempts - 1))
            next_retry = now + timedelta(seconds=delay)

        patch: Dict[str, Any] = {
            "status": final_status,
            "attempts": new_attempts,
            "claimToken": None,
            "lockOwner": None,
            "lockExpiresAt": None,
            "nextRetryAt": next_retry,
            "lastError": clean_err,
            "updatedAt": _fs.SERVER_TIMESTAMP,
        }
        if steps is not None:
            patch["steps"] = steps
        transaction.update(req_ref, patch)
        return final_status

    return _txn(db.transaction())


def _extract_legacy_image_records(post_id: str, post_data: Dict[str, Any]) -> List[Dict[str, str]]:
    """
    Identify legacy image URLs on posts that lack managed `mediaAssetIds` (ST37 audit).
    """
    media_asset_ids = post_data.get("mediaAssetIds")
    if isinstance(media_asset_ids, list) and any(str(x).strip() for x in media_asset_ids):
        return []

    raw_urls: List[str] = []
    single_url = post_data.get("imageUrl")
    if isinstance(single_url, str) and single_url.strip():
        raw_urls.append(single_url.strip())
    multi_urls = post_data.get("imageUrls")
    if isinstance(multi_urls, list):
        for item in multi_urls:
            if isinstance(item, str) and item.strip():
                raw_urls.append(item.strip())

    legacy_pub = post_data.get("imagePublicId")
    pub_str = legacy_pub.strip() if isinstance(legacy_pub, str) and legacy_pub.strip() else ""

    records: List[Dict[str, str]] = []
    seen: set[str] = set()
    for url in raw_urls:
        if url in seen:
            continue
        seen.add(url)
        rec = {"postId": post_id, "url": url[:300]}
        if pub_str:
            rec["publicId"] = pub_str[:200]
        records.append(rec)

    if not records and pub_str:
        records.append({"postId": post_id, "publicId": pub_str[:200]})
    return records


def _delete_posts_chunk_sync(
    db: FirestoreClient,
    *,
    uid: str,
    claim_token: str,
    chunk_size: int,
    steps: Dict[str, Any],
) -> Tuple[bool, Dict[str, Any]]:
    """
    ST36 + ST37 legacy audit: delete up to *chunk_size* posts owned by *uid*,
    write tombstones, remove derived docs, and record legacy image URLs.
    Returns `(has_more_posts, updated_steps)`.
    """
    snaps = list(
        db.collection("posts")
        .where(filter=FieldFilter("authorId", "==", uid))
        .limit(chunk_size)
        .stream()
    )

    posts_step = dict(steps.get("firestorePosts") or {"status": "PENDING", "deleted": 0})
    legacy_step = dict(
        steps.get("legacyMediaAudit") or {"status": "PENDING", "legacyUrls": [], "count": 0}
    )
    existing_legacy: List[Dict[str, str]] = list(legacy_step.get("legacyUrls") or [])
    seen_keys = {
        (str(item.get("postId") or ""), str(item.get("url") or ""), str(item.get("publicId") or ""))
        for item in existing_legacy
        if isinstance(item, dict)
    }

    deleted_in_chunk = 0
    for snap in snaps:
        post_id = snap.id
        post_data = snap.to_dict() or {}
        if post_data.get("authorId") != uid:
            continue

        # Audit legacy URLs BEFORE removing the post document
        for rec in _extract_legacy_image_records(post_id, post_data):
            key = (rec.get("postId", ""), rec.get("url", ""), rec.get("publicId", ""))
            if key not in seen_keys:
                seen_keys.add(key)
                legacy_step["count"] = int(legacy_step.get("count") or 0) + 1
                if len(existing_legacy) < _MAX_LEGACY_AUDIT_ITEMS:
                    existing_legacy.append(rec)

        # Verify lease is still active before deleting each post
        req_snap = db.collection("deletion_requests").document(uid).get()
        if not req_snap.exists:
            raise TokenMismatchError(f"deletion_requests/{uid} missing.")
        _assert_deletion_lease(req_snap.to_dict() or {}, claim_token, uid=uid)

        db.collection("post_tombstones").document(post_id).set(
            {
                "postId": post_id,
                "authorId": uid,
                "reason": "ACCOUNT_DELETION",
                "deletedAt": _fs.SERVER_TIMESTAMP,
            },
            merge=True,
        )
        db.collection("posts").document(post_id).delete()
        db.collection("post_embeddings").document(post_id).delete()
        db.collection("post_moderations").document(post_id).delete()
        db.collection("admin_moderation_notes").document(post_id).delete()
        deleted_in_chunk += 1

    has_more = len(snaps) >= chunk_size
    posts_step["deleted"] = int(posts_step.get("deleted") or 0) + deleted_in_chunk
    posts_step["status"] = "IN_PROGRESS" if has_more else "DONE"
    legacy_step["legacyUrls"] = existing_legacy
    legacy_step["status"] = "IN_PROGRESS" if has_more else "DONE"

    updated_steps = dict(steps)
    updated_steps["firestorePosts"] = posts_step
    updated_steps["legacyMediaAudit"] = legacy_step
    return has_more, updated_steps


def _prepare_user_asset_for_deletion_sync(
    db: FirestoreClient,
    *,
    uid: str,
    asset_id: str,
    claim_token: str,
) -> Tuple[bool, Optional[str]]:
    """
    Transactionally verify deletion lease and strict `ownerUid == uid`, then
    transition `media_assets/{assetId}` -> DELETING (`cleanupPending=True`).
    """
    req_ref = db.collection("deletion_requests").document(uid)
    asset_ref = db.collection("media_assets").document(asset_id)

    @_fs.transactional
    def _txn(transaction: _fs.Transaction) -> Tuple[bool, Optional[str]]:
        req_snap = req_ref.get(transaction=transaction)
        if not req_snap.exists:
            raise TokenMismatchError(f"deletion_requests/{uid} missing.")
        _assert_deletion_lease(req_snap.to_dict() or {}, claim_token, uid=uid)

        asset_snap = asset_ref.get(transaction=transaction)
        if not asset_snap.exists:
            return False, None
        asset_data = asset_snap.to_dict() or {}
        if asset_data.get("ownerUid") != uid:
            logger.warning(
                "Account deletion refused foreign media_asset=%s for uid=%s",
                asset_id,
                uid,
            )
            return False, None
        if asset_data.get("status") == MediaAssetStatus.DELETED.value:
            return False, None

        public_id = str(asset_data.get("cloudinaryPublicId") or "")
        transaction.update(
            asset_ref,
            {
                "status": MediaAssetStatus.DELETING.value,
                "cleanupPending": True,
            },
        )
        return True, public_id

    return _txn(db.transaction())


def _mark_asset_deleted_sync(
    db: FirestoreClient,
    *,
    uid: str,
    asset_id: str,
    claim_token: str,
) -> None:
    req_ref = db.collection("deletion_requests").document(uid)
    asset_ref = db.collection("media_assets").document(asset_id)

    @_fs.transactional
    def _txn(transaction: _fs.Transaction) -> None:
        req_snap = req_ref.get(transaction=transaction)
        if not req_snap.exists:
            raise TokenMismatchError(f"deletion_requests/{uid} missing.")
        _assert_deletion_lease(req_snap.to_dict() or {}, claim_token, uid=uid)
        asset_snap = asset_ref.get(transaction=transaction)
        if not asset_snap.exists:
            return
        if (asset_snap.to_dict() or {}).get("ownerUid") != uid:
            return
        transaction.update(
            asset_ref,
            {
                "status": MediaAssetStatus.DELETED.value,
                "cleanupPending": False,
                "deletedAt": _fs.SERVER_TIMESTAMP,
            },
        )

    _txn(db.transaction())


def _query_pending_user_media_asset_ids(
    db: FirestoreClient,
    *,
    uid: str,
    chunk_size: int,
) -> Tuple[List[str], bool]:
    """Query `media_assets` owned by *uid* that are not yet DELETED."""
    snaps = list(
        db.collection("media_assets")
        .where(filter=FieldFilter("ownerUid", "==", uid))
        .stream()
    )
    pending_ids: List[str] = []
    for snap in snaps:
        data = snap.to_dict() or {}
        if data.get("ownerUid") != uid:
            continue
        if data.get("status") == MediaAssetStatus.DELETED.value:
            continue
        pending_ids.append(snap.id)
    has_more = len(pending_ids) > chunk_size
    return pending_ids[:chunk_size], has_more


async def _cleanup_media_assets_chunk(
    db: FirestoreClient,
    *,
    uid: str,
    claim_token: str,
    chunk_size: int,
    steps: Dict[str, Any],
    settings: Settings,
) -> Tuple[bool, Dict[str, Any]]:
    """
    ST37: Destroy up to *chunk_size* Cloudinary images tracked in `media_assets` for *uid*.
    Raises on any Cloudinary failure so the job retries and never falsely completes.
    """
    asset_ids, has_more = await asyncio.to_thread(
        _query_pending_user_media_asset_ids,
        db,
        uid=uid,
        chunk_size=chunk_size,
    )
    media_step = dict(steps.get("mediaAssets") or {"status": "PENDING", "deleted": 0, "failed": 0})
    deleted_in_chunk = 0
    failed_in_chunk = 0
    first_error: Optional[Exception] = None

    for asset_id in asset_ids:
        should_destroy, public_id = await asyncio.to_thread(
            _prepare_user_asset_for_deletion_sync,
            db,
            uid=uid,
            asset_id=asset_id,
            claim_token=claim_token,
        )
        if not should_destroy:
            continue
        try:
            has_cloudinary_creds = bool(
                settings.cloudinary_cloud_name.strip()
                and settings.cloudinary_api_key.strip()
                and settings.cloudinary_api_secret.strip()
            )
            if public_id and (has_cloudinary_creds or settings.app_env != "development"):
                await _call_delete_cloudinary_asset(public_id, settings)
            elif public_id:
                logger.warning(
                    "Skipping Cloudinary remote destroy for asset=%s in development mode (no Cloudinary credentials configured)",
                    asset_id,
                )
            await asyncio.to_thread(
                _mark_asset_deleted_sync,
                db,
                uid=uid,
                asset_id=asset_id,
                claim_token=claim_token,
            )
            deleted_in_chunk += 1
        except TokenMismatchError:
            raise
        except Exception as exc:
            failed_in_chunk += 1
            if first_error is None:
                first_error = exc

    media_step["deleted"] = int(media_step.get("deleted") or 0) + deleted_in_chunk
    media_step["failed"] = failed_in_chunk
    updated_steps = dict(steps)

    if first_error is not None:
        media_step["status"] = "IN_PROGRESS"
        updated_steps["mediaAssets"] = media_step
        raise _ChunkCleanupError(first_error, updated_steps)

    media_step["status"] = "IN_PROGRESS" if has_more else "DONE"
    updated_steps["mediaAssets"] = media_step
    return has_more, updated_steps


def _cleanup_user_data_chunk_sync(
    db: FirestoreClient,
    *,
    uid: str,
    claim_token: str,
    chunk_size: int,
    steps: Dict[str, Any],
) -> Tuple[bool, Dict[str, Any]]:
    """ST36: Remove post_reservations, saved_posts/{uid}/items, and users/{uid}."""
    req_snap = db.collection("deletion_requests").document(uid).get()
    if not req_snap.exists:
        raise TokenMismatchError(f"deletion_requests/{uid} missing.")
    _assert_deletion_lease(req_snap.to_dict() or {}, claim_token, uid=uid)

    reservations = list(
        db.collection("post_reservations")
        .where(filter=FieldFilter("ownerUid", "==", uid))
        .limit(chunk_size)
        .stream()
    )
    for snap in reservations:
        if (snap.to_dict() or {}).get("ownerUid") == uid:
            db.collection("post_reservations").document(snap.id).delete()
    if len(reservations) >= chunk_size:
        updated = dict(steps)
        updated["firestoreUserData"] = {"status": "IN_PROGRESS"}
        return True, updated

    saved_col = db.collection("saved_posts").document(uid).collection("items")
    saved_items = list(saved_col.limit(chunk_size).stream())
    for snap in saved_items:
        saved_col.document(snap.id).delete()
    if len(saved_items) >= chunk_size:
        updated = dict(steps)
        updated["firestoreUserData"] = {"status": "IN_PROGRESS"}
        return True, updated

    db.collection("users").document(uid).delete()

    updated = dict(steps)
    updated["firestoreUserData"] = {"status": "DONE"}
    return False, updated


def _delete_firebase_auth_user_sync(uid: str) -> None:
    try:
        fb_auth.delete_user(uid)
    except fb_auth.UserNotFoundError:
        return


def _finalize_deletion_txn(
    db: FirestoreClient,
    *,
    uid: str,
    claim_token: str,
    steps: Dict[str, Any],
) -> None:
    state_ref = db.collection("account_states").document(uid)
    req_ref = db.collection("deletion_requests").document(uid)

    @_fs.transactional
    def _txn(transaction: _fs.Transaction) -> None:
        req_snap = req_ref.get(transaction=transaction)
        if not req_snap.exists:
            raise TokenMismatchError(f"deletion_requests/{uid} missing.")
        _assert_deletion_lease(req_snap.to_dict() or {}, claim_token, uid=uid)

        final_steps = dict(steps)
        final_steps["firebaseAuth"] = {"status": "DONE"}
        transaction.set(
            state_ref,
            {
                "status": AccountStateStatus.DELETED.value,
                "updatedAt": _fs.SERVER_TIMESTAMP,
            },
            merge=True,
        )
        transaction.update(
            req_ref,
            {
                "status": DeletionStatus.COMPLETED.value,
                "steps": final_steps,
                "claimToken": None,
                "lockOwner": None,
                "lockExpiresAt": None,
                "nextRetryAt": None,
                "lastError": None,
                "updatedAt": _fs.SERVER_TIMESTAMP,
            },
        )

    _txn(db.transaction())


class _ChunkCleanupError(Exception):
    def __init__(self, cause: Exception, steps: Dict[str, Any]) -> None:
        super().__init__(str(cause))
        self.cause = cause
        self.steps = steps


async def process_one_account_deletion(
    db: FirestoreClient,
    *,
    uid: str,
    worker_id: str,
    lease_seconds: int = 60,
    chunk_size: int = _DEFAULT_CHUNK_SIZE,
    settings: Optional[Settings] = None,
) -> bool:
    """
    Claim and advance one `deletion_requests/{uid}` by up to one bounded chunk per phase.
    Returns True if a claim was acquired and work was attempted, False if not claimable.
    """
    eff_settings = settings or get_settings()
    try:
        claim_token, req_data = await asyncio.to_thread(
            claim_deletion_request,
            db,
            uid,
            worker_id,
            lease_seconds,
        )
    except ClaimError:
        return False

    steps = _normalize_steps(req_data.get("steps"))
    try:
        # Phase 0: Ensure Firebase refresh tokens are revoked
        if steps.get("sessionRevoked") != "DONE":
            revoke_res = await asyncio.to_thread(revoke_user_sessions_sync, db, uid)
            steps["sessionRevoked"] = revoke_res
            if revoke_res != "DONE":
                raise RuntimeError("Failed to revoke Firebase Auth sessions.")

        # Phase 1: ST36 Firestore posts chunk + ST37 legacy URL audit
        if (steps.get("firestorePosts") or {}).get("status") != "DONE":
            has_more_posts, steps = await asyncio.to_thread(
                _delete_posts_chunk_sync,
                db,
                uid=uid,
                claim_token=claim_token,
                chunk_size=chunk_size,
                steps=steps,
            )
            await asyncio.to_thread(
                _save_checkpoint_and_optionally_yield,
                db,
                uid=uid,
                claim_token=claim_token,
                steps=steps,
                yield_for_next_sweep=has_more_posts,
            )
            if has_more_posts:
                return True

        # Phase 2: ST37 Cloudinary media_assets cleanup
        if (steps.get("mediaAssets") or {}).get("status") != "DONE":
            has_more_media, steps = await _cleanup_media_assets_chunk(
                db,
                uid=uid,
                claim_token=claim_token,
                chunk_size=chunk_size,
                steps=steps,
                settings=eff_settings,
            )
            await asyncio.to_thread(
                _save_checkpoint_and_optionally_yield,
                db,
                uid=uid,
                claim_token=claim_token,
                steps=steps,
                yield_for_next_sweep=has_more_media,
            )
            if has_more_media:
                return True

        # Phase 3: ST36 remaining Firestore user data (reservations, saved_posts, users)
        if (steps.get("firestoreUserData") or {}).get("status") != "DONE":
            has_more_user_data, steps = await asyncio.to_thread(
                _cleanup_user_data_chunk_sync,
                db,
                uid=uid,
                claim_token=claim_token,
                chunk_size=chunk_size,
                steps=steps,
            )
            await asyncio.to_thread(
                _save_checkpoint_and_optionally_yield,
                db,
                uid=uid,
                claim_token=claim_token,
                steps=steps,
                yield_for_next_sweep=has_more_user_data,
            )
            if has_more_user_data:
                return True

        # Phase 4: Delete Firebase Auth user & finalize DELETED/COMPLETED
        await asyncio.to_thread(_delete_firebase_auth_user_sync, uid)
        await asyncio.to_thread(
            _finalize_deletion_txn,
            db,
            uid=uid,
            claim_token=claim_token,
            steps=steps,
        )
        return True
    except TokenMismatchError as exc:
        logger.warning(
            "Worker %s lost/expired lease for deletion_requests/%s (%s)",
            worker_id,
            uid,
            type(exc).__name__,
        )
        return True
    except _ChunkCleanupError as exc:
        await asyncio.to_thread(
            fail_deletion_request,
            db,
            uid=uid,
            claim_token=claim_token,
            error_message=f"{type(exc.cause).__name__}: {exc.cause}",
            steps=exc.steps,
        )
        return True
    except Exception as exc:
        await asyncio.to_thread(
            fail_deletion_request,
            db,
            uid=uid,
            claim_token=claim_token,
            error_message=f"{type(exc).__name__}: {exc}",
            steps=steps,
        )
        return True
