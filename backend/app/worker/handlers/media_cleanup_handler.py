"""
app/worker/handlers/media_cleanup_handler.py — mediaCleanupJob & abandoned draft sweep (B0-T06 / B0-T11).

Security invariants:
- Never trust event `imageAssetRefs` alone as proof of ownership.
- Verify `media_assets/{assetId}` has `ownerUid == event.authorId` AND
  `postId == event.postId` AND is NOT still attached to `posts/{postId}.mediaAssetIds`.
- Verify worker lease (`claimToken` AND unexpired `lockExpiresAt`) inside Firestore transaction.
- Transition asset to `DELETING` (`cleanupPending=True`) inside Firestore transaction,
  destroy on Cloudinary outside transaction via `delete_cloudinary_asset`, then
  retain the registry document with `status="DELETED"` so late `/complete` calls
  still fail closed with 409 and cannot re-expose an orphaned asset.
- Abandoned draft sweep uses single-field Firestore indexes only (`createdAt <= cutoff`
  and `cleanupPending == True`), requiring no new composite indexes.
"""
from __future__ import annotations

import asyncio
from datetime import datetime, timedelta, timezone
import logging
from typing import Any, Dict, List, Optional, Tuple

from google.cloud import firestore as _fs
from google.cloud.firestore import Client as FirestoreClient
from google.cloud.firestore_v1 import FieldFilter, Query

from app.core.config import Settings, get_settings
from app.core.errors import redact_sensitive_text
from app.schemas.media import MediaAssetStatus
from app.services import media_service as _media_service
from app.services.media_service import delete_cloudinary_asset
from app.worker.claim import TokenMismatchError, assert_lease_valid, complete_job

logger = logging.getLogger(__name__)

_IMPORTED_DELETE_CLOUDINARY_ASSET = delete_cloudinary_asset
_CLEANUP_RETRY_BACKOFF_SECONDS: Tuple[int, ...] = (60, 180, 600, 1800, 3600)


def _compute_cleanup_backoff_seconds(attempt: int) -> int:
    idx = max(0, min(attempt - 1, len(_CLEANUP_RETRY_BACKOFF_SECONDS) - 1))
    return _CLEANUP_RETRY_BACKOFF_SECONDS[idx]


def _is_asset_attached(db: FirestoreClient, *, asset_id: str, post_id: str, transaction: Any) -> bool:
    if not post_id:
        return False
    post_snap = db.collection("posts").document(post_id).get(transaction=transaction)
    if not post_snap.exists:
        return False
    return asset_id in set((post_snap.to_dict() or {}).get("mediaAssetIds") or [])


async def _call_delete_cloudinary_asset(public_id: str, settings: Settings) -> bool:
    """
    Invoke Cloudinary destroy while honoring monkeypatches on either
    `media_cleanup_handler.delete_cloudinary_asset` or `media_service.delete_cloudinary_asset`.
    """
    if delete_cloudinary_asset is not _IMPORTED_DELETE_CLOUDINARY_ASSET:
        return await delete_cloudinary_asset(public_id, settings)
    return await _media_service.delete_cloudinary_asset(public_id, settings)


def _to_utc(value: Any) -> Optional[datetime]:
    if not isinstance(value, datetime):
        return None
    if value.tzinfo is None:
        return value.replace(tzinfo=timezone.utc)
    return value.astimezone(timezone.utc)


def _prepare_asset_for_deletion_sync(
    db: FirestoreClient,
    *,
    event_id: str,
    asset_id: str,
    post_id: str,
    author_id: str,
    claim_token: str,
) -> Tuple[bool, Optional[str]]:
    """
    Verify unexpired claimToken, ownership, postId binding, and non-attachment
    before transitioning media_assets/{assetId} to DELETING + cleanupPending=True.
    """
    event_ref = db.collection("post_events").document(event_id)
    post_ref = db.collection("posts").document(post_id)
    asset_ref = db.collection("media_assets").document(asset_id)

    @_fs.transactional
    def _txn(transaction: _fs.Transaction) -> Tuple[bool, Optional[str]]:
        event_snap = event_ref.get(transaction=transaction)
        if not event_snap.exists:
            raise TokenMismatchError(f"Event {event_id} missing.")
        job = (event_snap.to_dict() or {}).get("mediaCleanupJob") or {}
        assert_lease_valid(
            job, claim_token, event_id=event_id, job_name="mediaCleanupJob"
        )

        post_snap = post_ref.get(transaction=transaction)
        asset_snap = asset_ref.get(transaction=transaction)

        if post_snap.exists:
            attached_ids = set((post_snap.to_dict() or {}).get("mediaAssetIds") or [])
            if asset_id in attached_ids:
                logger.info(
                    "mediaCleanupJob skipping asset=%s because it is still attached to post=%s",
                    asset_id,
                    post_id,
                )
                return False, None

        if not asset_snap.exists:
            return False, None

        asset_data = asset_snap.to_dict() or {}
        if asset_data.get("ownerUid") != author_id or asset_data.get("postId") != post_id:
            logger.warning(
                "mediaCleanupJob refused foreign/mismatched asset=%s for post=%s author=%s",
                asset_id,
                post_id,
                author_id,
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


def _read_cleanup_target_sync(
    db: FirestoreClient,
    *,
    asset_id: str,
    transaction: _fs.Transaction,
    author_id: str,
    expected_post_id: Optional[str] = None,
    expected_public_id: Optional[str] = None,
    require_owner: bool = True,
) -> Tuple[str, Optional[Dict[str, Any]]]:
    """
    Load `media_assets/{asset_id}` in *transaction* and enforce owner, post/publicId
    bindings, and `posts/{postId}.mediaAssetIds` non-attachment guards.

    Returns `("OK", asset_data)`, `("ATTACHED", asset_data)`, or `("SKIP", None)`.
    """
    asset_ref = db.collection("media_assets").document(asset_id)
    snap = asset_ref.get(transaction=transaction)
    if not snap.exists:
        return "SKIP", None

    asset_data = snap.to_dict() or {}
    actual_owner = asset_data.get("ownerUid")
    if require_owner:
        if not author_id or actual_owner != author_id:
            return "SKIP", None
    elif str(actual_owner or "") != str(author_id or ""):
        return "SKIP", None

    actual_post_id = str(asset_data.get("postId") or "")
    if expected_post_id is not None and actual_post_id != expected_post_id:
        return "SKIP", None

    actual_public_id = str(asset_data.get("cloudinaryPublicId") or "")
    if expected_public_id is not None and actual_public_id != expected_public_id:
        return "SKIP", None

    if _is_asset_attached(
        db, asset_id=asset_id, post_id=actual_post_id, transaction=transaction
    ):
        return "ATTACHED", asset_data

    return "OK", asset_data


def _defer_asset_cleanup_retry_sync(
    db: FirestoreClient,
    *,
    asset_id: str,
    author_id: str = "",
    expected_post_id: Optional[str] = None,
    expected_public_id: Optional[str] = None,
    require_owner: bool = True,
    error_message: str = "",
    now: Optional[datetime] = None,
) -> None:
    """
    Record transient Cloudinary cleanup failure with exponential backoff (`nextCleanupAt`)
    while preserving `status="DELETING", cleanupPending=True` and guarding owner, binding,
    and post-attached assets.
    """
    asset_ref = db.collection("media_assets").document(asset_id)
    effective_now = now or datetime.now(tz=timezone.utc)

    @_fs.transactional
    def _txn(transaction: _fs.Transaction) -> None:
        state, asset_data = _read_cleanup_target_sync(
            db,
            asset_id=asset_id,
            transaction=transaction,
            author_id=author_id,
            expected_post_id=expected_post_id,
            expected_public_id=expected_public_id,
            require_owner=require_owner,
        )
        if state == "SKIP" or asset_data is None:
            return
        if state == "ATTACHED":
            transaction.update(
                asset_ref,
                {"cleanupPending": False, "nextCleanupAt": None},
            )
            return

        attempts = int(asset_data.get("cleanupAttempts") or 0) + 1
        delay = _compute_cleanup_backoff_seconds(attempts)
        transaction.update(
            asset_ref,
            {
                "status": MediaAssetStatus.DELETING.value,
                "cleanupPending": True,
                "cleanupAttempts": attempts,
                "nextCleanupAt": effective_now + timedelta(seconds=delay),
                "lastCleanupError": redact_sensitive_text(error_message, max_length=300),
            },
        )

    _txn(db.transaction())


def _mark_asset_deleted_sync(
    db: FirestoreClient,
    *,
    asset_id: str,
    author_id: str,
    expected_post_id: Optional[str] = None,
    expected_public_id: Optional[str] = None,
    require_owner: bool = True,
    now: Optional[datetime] = None,
) -> None:
    """
    Persist DELETED status (keeping registry doc) unless attached to an active post
    or failing owner/binding guards.
    When `createdAt` is still inside Cloudinary's signed-upload window, keeps
    `cleanupPending=True` with scheduled `nextCleanupAt` reconciliation until `window_end`.
    """
    asset_ref = db.collection("media_assets").document(asset_id)
    effective_now = now or datetime.now(tz=timezone.utc)
    window_seconds = get_settings().media_signed_upload_window_seconds

    @_fs.transactional
    def _txn(transaction: _fs.Transaction) -> None:
        state, asset_data = _read_cleanup_target_sync(
            db,
            asset_id=asset_id,
            transaction=transaction,
            author_id=author_id,
            expected_post_id=expected_post_id,
            expected_public_id=expected_public_id,
            require_owner=require_owner,
        )
        if state == "SKIP" or asset_data is None:
            return
        if state == "ATTACHED":
            transaction.update(
                asset_ref,
                {"cleanupPending": False, "nextCleanupAt": None},
            )
            return

        created_at = _to_utc(asset_data.get("createdAt"))
        window_end = (
            created_at + timedelta(seconds=window_seconds)
            if created_at is not None
            else None
        )
        if window_end is not None and effective_now < window_end:
            reconcile_attempts = int(asset_data.get("reconcileAttempts") or 0) + 1
            delay = _compute_cleanup_backoff_seconds(reconcile_attempts)
            next_reconcile = min(
                window_end, effective_now + timedelta(seconds=delay)
            )
            transaction.update(
                asset_ref,
                {
                    "status": MediaAssetStatus.DELETED.value,
                    "cleanupPending": True,
                    "reconcileAttempts": reconcile_attempts,
                    "cleanupAttempts": 0,
                    "nextCleanupAt": next_reconcile,
                    "lastCleanupError": None,
                },
            )
            return

        transaction.update(
            asset_ref,
            {
                "status": MediaAssetStatus.DELETED.value,
                "cleanupPending": False,
                "cleanupAttempts": 0,
                "nextCleanupAt": None,
                "lastCleanupError": None,
            },
        )

    _txn(db.transaction())


async def execute_asset_cleanup(
    db: FirestoreClient,
    *,
    asset_id: str,
    author_id: str,
    public_id: str,
    expected_post_id: Optional[str] = None,
    expected_public_id: Optional[str] = None,
    require_owner: bool = True,
    now: Optional[datetime] = None,
    settings: Optional[Settings] = None,
    reraise: bool = False,
    failure_context: str = "asset cleanup",
) -> bool:
    """
    Shared external Cloudinary destroy + post-destroy Firestore finalization for both
    API rejection/late-cleanup paths and background worker cleanup/reconciliation paths.
    """
    cfg = settings or get_settings()
    clean_public_id = public_id.strip()
    try:
        if clean_public_id:
            await _call_delete_cloudinary_asset(clean_public_id, cfg)
        commit_now = now or datetime.now(tz=timezone.utc)
        await asyncio.to_thread(
            _mark_asset_deleted_sync,
            db,
            asset_id=asset_id,
            author_id=author_id,
            expected_post_id=expected_post_id,
            expected_public_id=expected_public_id,
            require_owner=require_owner,
            now=commit_now,
        )
        return True
    except Exception as exc:
        redacted = redact_sensitive_text(str(exc), max_length=200)
        commit_now = now or datetime.now(tz=timezone.utc)
        await asyncio.to_thread(
            _defer_asset_cleanup_retry_sync,
            db,
            asset_id=asset_id,
            author_id=author_id,
            expected_post_id=expected_post_id,
            expected_public_id=expected_public_id,
            require_owner=require_owner,
            error_message=f"{type(exc).__name__}: {redacted}",
            now=commit_now,
        )
        if reraise:
            raise
        logger.warning(
            "Deferred %s pending for media_assets/%s (%s: %s)",
            failure_context,
            asset_id,
            type(exc).__name__,
            redacted,
        )
        return False


async def handle_media_cleanup_job(
    db: FirestoreClient,
    event_id: str,
    event_data: Dict[str, Any],
    claim_token: str,
) -> str:
    """Clean up unreferenced Cloudinary media assets referenced by an event."""
    raw_refs = event_data.get("imageAssetRefs") or []
    image_refs: List[str] = [str(ref).strip() for ref in raw_refs if str(ref).strip()]

    if not image_refs:
        await asyncio.to_thread(
            complete_job, db, event_id, "mediaCleanupJob", claim_token, "SKIPPED"
        )
        return "SKIPPED"

    post_id = str(event_data.get("postId") or "")
    author_id = str(event_data.get("authorId") or "")
    settings = get_settings()
    deleted_any = False

    for asset_id in image_refs:
        should_delete, public_id = await asyncio.to_thread(
            _prepare_asset_for_deletion_sync,
            db,
            event_id=event_id,
            asset_id=asset_id,
            post_id=post_id,
            author_id=author_id,
            claim_token=claim_token,
        )
        if not should_delete:
            continue

        await execute_asset_cleanup(
            db,
            asset_id=asset_id,
            author_id=author_id,
            public_id=public_id or "",
            expected_post_id=post_id,
            expected_public_id=public_id or "",
            require_owner=True,
            settings=settings,
            reraise=True,
            failure_context="event media cleanup",
        )
        deleted_any = True

    final_status = "DONE" if deleted_any else "SKIPPED"
    await asyncio.to_thread(
        complete_job, db, event_id, "mediaCleanupJob", claim_token, final_status
    )
    return final_status


def _query_abandoned_or_pending_media_ids(
    db: FirestoreClient,
    *,
    cutoff: datetime,
    limit: int,
    now: Optional[datetime] = None,
) -> List[str]:
    """
    Query up to *limit* candidate media_assets IDs using Firestore single-field indexes only:
    1) Paginate `cleanupPending == True` with `.start_after(last_snap)` (no extra order_by),
       skipping docs in backoff/reconcile wait (`nextCleanupAt > now`) or attached to posts.
    2) Paginate `createdAt <= cutoff` (abandoned unattached UPLOADING/READY/DELETING drafts).
    """
    if limit <= 0:
        return []

    effective_now = now or datetime.now(tz=timezone.utc)
    candidates: List[str] = []
    seen: set[str] = set()
    col = db.collection("media_assets")

    page_size = min(max(limit * 4, 25), 50)
    max_scan = max(limit * 50, 500)
    post_attached_cache: Dict[str, set[str]] = {}

    def _is_attached_cached(asset_id: str, data: Dict[str, Any]) -> bool:
        post_id = str(data.get("postId") or "").strip()
        if not post_id:
            return False
        if post_id not in post_attached_cache:
            post_snap = db.collection("posts").document(post_id).get()
            post_attached_cache[post_id] = (
                set((post_snap.to_dict() or {}).get("mediaAssetIds") or [])
                if post_snap.exists
                else set()
            )
        return asset_id in post_attached_cache[post_id]

    # 1. Paginate pending retry/reconcile docs (includes DELETED+cleanupPending=True).
    scanned_pending = 0
    last_pending_snap: Any = None
    while len(candidates) < limit and scanned_pending < max_scan:
        query = col.where(filter=FieldFilter("cleanupPending", "==", True))
        if last_pending_snap is not None:
            query = query.start_after(last_pending_snap)
        page = list(query.limit(page_size).stream())
        if not page:
            break
        scanned_pending += len(page)
        last_pending_snap = page[-1]

        for snap in page:
            if not snap.exists or snap.id in seen:
                continue
            data = snap.to_dict() or {}
            if data.get("cleanupPending") is not True:
                continue
            next_cleanup_at = _to_utc(data.get("nextCleanupAt"))
            if next_cleanup_at is not None and next_cleanup_at > effective_now:
                continue
            if _is_attached_cached(snap.id, data):
                continue
            seen.add(snap.id)
            candidates.append(snap.id)
            if len(candidates) >= limit:
                break

        if len(page) < page_size:
            break

    if len(candidates) >= limit:
        return candidates[:limit]

    scanned = 0
    last_snap: Any = None

    while len(candidates) < limit and scanned < max_scan:
        # Scan DESCENDING from cutoff so newly expired drafts sit at the head rather
        # than being buried behind historical DELETED or attached READY records.
        query = (
            col.where(filter=FieldFilter("createdAt", "<=", cutoff))
            .order_by("createdAt", direction=Query.DESCENDING)
        )
        if last_snap is not None:
            query = query.start_after(last_snap)
        page = list(query.limit(page_size).stream())
        if not page:
            break
        scanned += len(page)
        last_snap = page[-1]

        for snap in page:
            if snap.id in seen:
                continue
            data = snap.to_dict() or {}
            status_val = str(data.get("status") or "")
            cleanup_pending = data.get("cleanupPending") is True
            if status_val == MediaAssetStatus.DELETED.value and not cleanup_pending:
                continue
            if not cleanup_pending and status_val not in (
                MediaAssetStatus.UPLOADING.value,
                MediaAssetStatus.READY.value,
                MediaAssetStatus.DELETING.value,
            ):
                continue
            next_cleanup_at = _to_utc(data.get("nextCleanupAt"))
            if cleanup_pending and next_cleanup_at is not None and next_cleanup_at > effective_now:
                continue
            if _is_attached_cached(snap.id, data):
                continue

            seen.add(snap.id)
            candidates.append(snap.id)
            if len(candidates) >= limit:
                break

        if len(page) < page_size:
            break

    return candidates


def _claim_abandoned_or_pending_asset_sync(
    db: FirestoreClient,
    *,
    asset_id: str,
    cutoff: datetime,
    now: Optional[datetime] = None,
) -> Tuple[bool, Optional[str], str, str]:
    """
    Atomically verify inside a transaction that *asset_id* is NOT attached to
    `posts/{postId}.mediaAssetIds` and is eligible for cleanup (`cleanupPending=True`
    with `nextCleanupAt <= now`, tombstoned post, or expired draft `createdAt <= cutoff`),
    transitioning it to `DELETING` + `cleanupPending=True`.
    """
    asset_ref = db.collection("media_assets").document(asset_id)
    effective_now = now or datetime.now(tz=timezone.utc)

    @_fs.transactional
    def _txn(transaction: _fs.Transaction) -> Tuple[bool, Optional[str], str, str]:
        asset_snap = asset_ref.get(transaction=transaction)
        if not asset_snap.exists:
            return False, None, "", ""
        asset_data = asset_snap.to_dict() or {}
        owner_uid = str(asset_data.get("ownerUid") or "")
        post_id = str(asset_data.get("postId") or "")
        public_id = str(asset_data.get("cloudinaryPublicId") or "")
        cur_status = str(asset_data.get("status") or "")
        cleanup_pending = asset_data.get("cleanupPending") is True

        post_snap = (
            db.collection("posts").document(post_id).get(transaction=transaction)
            if post_id
            else None
        )
        tomb_snap = (
            db.collection("post_tombstones").document(post_id).get(transaction=transaction)
            if post_id
            else None
        )

        if post_snap is not None and post_snap.exists:
            attached_ids = set((post_snap.to_dict() or {}).get("mediaAssetIds") or [])
            if asset_id in attached_ids:
                if cleanup_pending or asset_data.get("nextCleanupAt") is not None:
                    transaction.update(
                        asset_ref, {"cleanupPending": False, "nextCleanupAt": None}
                    )
                return False, None, "", ""

        if cur_status == MediaAssetStatus.DELETED.value and not cleanup_pending:
            return False, None, "", ""

        next_cleanup_at = _to_utc(asset_data.get("nextCleanupAt"))
        tombstoned = bool(tomb_snap is not None and tomb_snap.exists)
        created_at = _to_utc(asset_data.get("createdAt"))
        expired_unattached = created_at is not None and created_at <= cutoff

        if cleanup_pending:
            if next_cleanup_at is not None and next_cleanup_at > effective_now:
                if not (tombstoned or expired_unattached):
                    return False, None, "", ""
        elif not (tombstoned or expired_unattached):
            return False, None, "", ""

        transaction.update(
            asset_ref,
            {
                "status": MediaAssetStatus.DELETING.value,
                "cleanupPending": True,
            },
        )
        return True, public_id, owner_uid, post_id

    return _txn(db.transaction())


async def _cleanup_claimed_abandoned_asset(
    db: FirestoreClient,
    *,
    asset_id: str,
    cutoff: datetime,
    now: Optional[datetime] = None,
    settings: Optional[Settings] = None,
) -> bool:
    """Shared claim + Cloudinary destroy/reconcile/retry-deferral step for one asset."""
    effective_now = now or datetime.now(tz=timezone.utc)
    cfg = settings or get_settings()
    should_delete, public_id, owner_uid, post_id = await asyncio.to_thread(
        _claim_abandoned_or_pending_asset_sync,
        db,
        asset_id=asset_id,
        cutoff=cutoff,
        now=effective_now,
    )
    if not should_delete:
        return False

    return await execute_asset_cleanup(
        db,
        asset_id=asset_id,
        author_id=owner_uid,
        public_id=public_id or "",
        expected_post_id=post_id,
        expected_public_id=public_id or "",
        require_owner=bool(owner_uid),
        now=now,
        settings=cfg,
        reraise=False,
        failure_context="abandoned media cleanup",
    )


async def sweep_abandoned_media(
    db: FirestoreClient,
    *,
    limit: int = 10,
    ttl_seconds: Optional[int] = None,
) -> int:
    """
    Bounded sweep cleaning abandoned draft uploads (`createdAt <= now - ttl`) and
    retrying/reconciling `cleanupPending=True` assets while protecting attached assets.
    """
    if limit <= 0:
        return 0

    settings = get_settings()
    effective_ttl = (
        ttl_seconds if ttl_seconds is not None else settings.media_abandoned_ttl_seconds
    )
    now = datetime.now(tz=timezone.utc)
    cutoff = now - timedelta(seconds=effective_ttl)

    candidate_ids = await asyncio.to_thread(
        _query_abandoned_or_pending_media_ids,
        db,
        cutoff=cutoff,
        limit=limit,
        now=now,
    )
    cleaned = 0

    for asset_id in candidate_ids:
        if await _cleanup_claimed_abandoned_asset(
            db,
            asset_id=asset_id,
            cutoff=cutoff,
            now=now,
            settings=settings,
        ):
            cleaned += 1

    return cleaned
