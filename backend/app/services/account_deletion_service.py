"""
app/services/account_deletion_service.py — ST35: initiate account deletion.

Phase "lock" (this module), executed by POST /api/v1/account/deletion:

1. One Firestore transaction atomically:
   - flips `account_states/{uid}.status` -> DELETING (Firestore Rules `isAccountActive()`
     and backend `require_active_student` immediately reject every new write), and
   - creates the idempotent tracking job `deletion_requests/{uid}` (doc id == uid, so
     double-submits / concurrent requests can never create a second job).
2. Hide every VISIBLE post of the account (`moderationStatus=HIDDEN`, version bump,
   `post_moderations` reason ACCOUNT_DELETING, `post_events` MODERATION outbox event,
   `post_embeddings.moderationStatus=HIDDEN`). Home feed, client-side search and the
   vector matching index all filter on `moderationStatus == VISIBLE`, so this is the
   "stop displaying" signal for Trang chủ / Tìm kiếm / Ghép cặp.
3. Revoke all Firebase refresh tokens (`revoke_refresh_tokens`). Old ID tokens are
   rejected by the backend (`verify_id_token(check_revoked=True)`); Firestore Rules
   do not check revocation, but writes are already blocked by the DELETING lock.

Every step is recorded in `deletion_requests/{uid}.steps` and re-runs on retry, so a
crash between steps is healed by simply re-submitting the same request. The actual
data purge is left to the cleanup worker (`cleanupJob`, next task).
"""
from __future__ import annotations

import asyncio
import logging
import uuid
from datetime import datetime, timezone
from typing import Any, Dict, Optional, Tuple

import firebase_admin.auth as fb_auth
from fastapi import status
from google.cloud import firestore as _fs
from google.cloud.firestore import Client as FirestoreClient
from google.cloud.firestore_v1 import FieldFilter

from app.core.auth import parse_account_state_snapshot
from app.core.errors import ApiHTTPException, redact_sensitive_text
from app.core.firebase import get_firestore
from app.schemas.account import (
    AccountDeletionInitResponse,
    AccountStateStatus,
    DeletionRequestStatus,
    DeletionStepStatus,
)
from app.schemas.post import (
    EventChangeType,
    JobState,
    JobStatus,
    ModerationStatus,
    PersistedPostEvent,
)

logger = logging.getLogger(__name__)

ACCOUNT_DELETING_MODERATION_REASON = "ACCOUNT_DELETING"
STEP_POSTS_HIDDEN = "postsHidden"
STEP_SESSIONS_REVOKED = "sessionsRevoked"


def _initial_steps() -> Dict[str, Any]:
    return {
        STEP_POSTS_HIDDEN: {
            "status": DeletionStepStatus.PENDING.value,
            "hiddenCount": 0,
            "completedAt": None,
            "lastError": None,
        },
        STEP_SESSIONS_REVOKED: {
            "status": DeletionStepStatus.PENDING.value,
            "completedAt": None,
            "lastError": None,
        },
    }


def _step_done(steps: Dict[str, Any], name: str) -> bool:
    step = steps.get(name)
    return isinstance(step, dict) and step.get("status") == DeletionStepStatus.DONE.value


# ─── 1. Atomic lock + idempotent job creation ────────────────────────────────


def _lock_account_and_create_job(
    db: FirestoreClient,
    *,
    uid: str,
    email: str,
    reason: Optional[str],
) -> Tuple[bool, Dict[str, Any]]:
    """
    Transactionally set account_states/{uid}=DELETING and create deletion_requests/{uid}.

    Returns (created, deletion_request_data). Idempotent: if the job already exists
    the existing document is returned with created=False and nothing is overwritten.
    """
    account_ref = db.collection("account_states").document(uid)
    request_ref = db.collection("deletion_requests").document(uid)

    @_fs.transactional
    def _txn(transaction: _fs.Transaction) -> Tuple[bool, Dict[str, Any]]:
        acct_snap = account_ref.get(transaction=transaction)
        req_snap = request_ref.get(transaction=transaction)
        current = parse_account_state_snapshot(acct_snap, uid=uid)

        if current == AccountStateStatus.DELETED:
            raise ApiHTTPException(
                status_code=status.HTTP_409_CONFLICT,
                detail="This account has already been deleted.",
                code="ACCOUNT_ALREADY_DELETED",
            )

        if req_snap.exists:
            existing = req_snap.to_dict() or {}
            # Self-heal: job exists but lock missing/reverted -> re-assert DELETING.
            if current != AccountStateStatus.DELETING or not acct_snap.exists:
                transaction.set(
                    account_ref,
                    {"status": AccountStateStatus.DELETING.value, "updatedAt": _fs.SERVER_TIMESTAMP},
                    merge=True,
                )
            return False, existing

        now_marker = _fs.SERVER_TIMESTAMP
        transaction.set(
            account_ref,
            {
                "status": AccountStateStatus.DELETING.value,
                "deletionRequestedAt": now_marker,
                "updatedAt": now_marker,
            },
            merge=True,
        )
        job = {
            "uid": uid,
            "requestId": uuid.uuid4().hex,
            "status": DeletionRequestStatus.LOCKING.value,
            "emailHint": _mask_email(email),
            "reason": reason,
            "steps": _initial_steps(),
            # Tracked by the cleanup worker (next task) with the same lease semantics
            # as post_events jobs.
            "cleanupJob": JobState.initial().to_firestore_dict(),
            "createdAt": now_marker,
            "updatedAt": now_marker,
        }
        transaction.set(request_ref, job)
        return True, job

    try:
        created, data = _txn(db.transaction())
    except ApiHTTPException:
        raise
    except Exception as exc:
        logger.error(
            "Account deletion transaction failed for uid=%s: %s (%s)",
            uid,
            type(exc).__name__,
            redact_sensitive_text(str(exc)),
        )
        raise ApiHTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="Database service is unavailable. Please ensure Firebase Firestore / Emulator is running.",
            code="DATABASE_UNAVAILABLE",
        ) from exc

    if created:
        # Re-read to materialize server timestamps.
        snap = request_ref.get()
        data = snap.to_dict() or data
    return created, data


def _mask_email(email: str) -> str:
    cleaned = (email or "").strip().lower()
    if "@" not in cleaned:
        return ""
    local, domain = cleaned.split("@", 1)
    return f"{local[:2]}***@{domain}"


# ─── 2. Hide posts (feed / search / matching signal) ─────────────────────────


def _hide_one_post(db: FirestoreClient, *, uid: str, post_id: str) -> bool:
    """Transactionally hide one VISIBLE post owned by uid. Returns True if it changed."""
    post_ref = db.collection("posts").document(post_id)
    moderation_ref = db.collection("post_moderations").document(post_id)
    embedding_ref = db.collection("post_embeddings").document(post_id)

    @_fs.transactional
    def _txn(transaction: _fs.Transaction) -> bool:
        post_snap = post_ref.get(transaction=transaction)
        if not post_snap.exists:
            return False
        data = post_snap.to_dict() or {}
        if data.get("authorId") != uid:
            return False
        if data.get("moderationStatus") == ModerationStatus.HIDDEN.value:
            return False

        raw_version = data.get("version")
        prev_version = raw_version if isinstance(raw_version, int) and not isinstance(raw_version, bool) else 1
        next_version = max(prev_version, 1) + 1
        event_id = f"{post_id}_v{next_version}"
        event_ref = db.collection("post_events").document(event_id)
        event_snap = event_ref.get(transaction=transaction)
        embedding_snap = embedding_ref.get(transaction=transaction)
        if event_snap.exists:
            # Version slot taken by a concurrent writer; let the retry recompute.
            raise RuntimeError(f"post_events/{event_id} already exists")

        now = datetime.now(tz=timezone.utc)
        transaction.update(
            post_ref,
            {
                "moderationStatus": ModerationStatus.HIDDEN.value,
                "version": next_version,
                "updatedAt": _fs.SERVER_TIMESTAMP,
            },
        )
        transaction.set(
            moderation_ref,
            {
                "postId": post_id,
                "authorId": uid,
                "moderationStatus": ModerationStatus.HIDDEN.value,
                "reason": ACCOUNT_DELETING_MODERATION_REASON,
                "updatedAt": _fs.SERVER_TIMESTAMP,
            },
        )
        event = PersistedPostEvent.build_initial(
            post_id=post_id,
            author_id=uid,
            post_version=next_version,
            change_type=EventChangeType.MODERATION,
            created_at=now,
        )
        payload = event.to_firestore_dict()
        payload["createdAt"] = _fs.SERVER_TIMESTAMP
        # searchJob stays PENDING so the search/matching index drops the post;
        # no notifications and no media to clean for a visibility change.
        skipped = JobState.initial().model_copy(update={"status": JobStatus.SKIPPED})
        payload["notifyJob"] = skipped.to_firestore_dict()
        payload["mediaCleanupJob"] = skipped.to_firestore_dict()
        transaction.set(event_ref, payload)
        if embedding_snap.exists:
            transaction.update(
                embedding_ref,
                {"moderationStatus": ModerationStatus.HIDDEN.value},
            )
        return True

    return _txn(db.transaction())


def _hide_all_posts(db: FirestoreClient, uid: str) -> int:
    query = db.collection("posts").where(filter=FieldFilter("authorId", "==", uid))
    hidden = 0
    for snap in query.stream():
        data = snap.to_dict() or {}
        if data.get("moderationStatus") == ModerationStatus.HIDDEN.value:
            continue
        if _hide_one_post(db, uid=uid, post_id=snap.id):
            hidden += 1
    return hidden


# ─── 3. Revoke sessions ──────────────────────────────────────────────────────


def _revoke_sessions(uid: str) -> None:
    try:
        fb_auth.revoke_refresh_tokens(uid)
    except fb_auth.UserNotFoundError:
        # Auth user already gone: nothing left to revoke.
        logger.info("revoke_refresh_tokens: auth user not found for uid=%s", uid)


# ─── Step bookkeeping ────────────────────────────────────────────────────────


def _record_step(
    db: FirestoreClient,
    uid: str,
    step: str,
    *,
    ok: bool,
    error: Optional[str] = None,
    extra: Optional[Dict[str, Any]] = None,
) -> None:
    updates: Dict[str, Any] = {
        f"steps.{step}.status": (DeletionStepStatus.DONE if ok else DeletionStepStatus.FAILED).value,
        f"steps.{step}.completedAt": _fs.SERVER_TIMESTAMP if ok else None,
        f"steps.{step}.lastError": None if ok else redact_sensitive_text(error or "", max_length=300),
        "updatedAt": _fs.SERVER_TIMESTAMP,
    }
    for key, value in (extra or {}).items():
        updates[f"steps.{step}.{key}"] = value
    db.collection("deletion_requests").document(uid).update(updates)


def _mark_queued_if_locked(db: FirestoreClient, uid: str) -> Dict[str, Any]:
    ref = db.collection("deletion_requests").document(uid)

    @_fs.transactional
    def _txn(transaction: _fs.Transaction) -> Dict[str, Any]:
        snap = ref.get(transaction=transaction)
        data = snap.to_dict() or {}
        steps = data.get("steps") or {}
        if (
            data.get("status") == DeletionRequestStatus.LOCKING.value
            and _step_done(steps, STEP_POSTS_HIDDEN)
            and _step_done(steps, STEP_SESSIONS_REVOKED)
        ):
            transaction.update(
                ref,
                {"status": DeletionRequestStatus.QUEUED.value, "updatedAt": _fs.SERVER_TIMESTAMP},
            )
        return data

    try:
        _txn(db.transaction())
    except ApiHTTPException:
        raise
    except Exception as exc:
        logger.error(
            "Account deletion queuing update failed for uid=%s: %s (%s)",
            uid,
            type(exc).__name__,
            redact_sensitive_text(str(exc)),
        )
        raise ApiHTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="Database service is unavailable. Please ensure Firebase Firestore / Emulator is running.",
            code="DATABASE_UNAVAILABLE",
        ) from exc
    return ref.get().to_dict() or {}


# ─── Orchestration ───────────────────────────────────────────────────────────


def _run_lock_phase_sync(
    db: FirestoreClient, *, uid: str, email: str, reason: Optional[str]
) -> Tuple[bool, Dict[str, Any], Optional[str]]:
    created, data = _lock_account_and_create_job(db, uid=uid, email=email, reason=reason)
    steps = data.get("steps") or {}
    failed_step: Optional[str] = None

    # Hide posts BEFORE revoking sessions so a client retry with the same token
    # still works if hiding fails midway.
    if not _step_done(steps, STEP_POSTS_HIDDEN):
        try:
            count = _hide_all_posts(db, uid)
            prev = (steps.get(STEP_POSTS_HIDDEN) or {}).get("hiddenCount") or 0
            _record_step(db, uid, STEP_POSTS_HIDDEN, ok=True, extra={"hiddenCount": int(prev) + count})
        except Exception as exc:  # noqa: BLE001 — recorded and surfaced as 503
            logger.warning("Account deletion: hiding posts failed for uid=%s (%s)", uid, type(exc).__name__)
            _record_step(db, uid, STEP_POSTS_HIDDEN, ok=False, error=f"{type(exc).__name__}: {exc}")
            failed_step = STEP_POSTS_HIDDEN

    if failed_step is None and not _step_done(steps, STEP_SESSIONS_REVOKED):
        try:
            _revoke_sessions(uid)
            _record_step(db, uid, STEP_SESSIONS_REVOKED, ok=True)
        except Exception as exc:  # noqa: BLE001
            logger.warning("Account deletion: session revoke failed for uid=%s (%s)", uid, type(exc).__name__)
            _record_step(db, uid, STEP_SESSIONS_REVOKED, ok=False, error=type(exc).__name__)
            failed_step = STEP_SESSIONS_REVOKED

    final = _mark_queued_if_locked(db, uid)
    return created, final, failed_step


async def initiate_account_deletion(
    *, uid: str, email: str, reason: Optional[str] = None
) -> Tuple[int, AccountDeletionInitResponse]:
    """
    Run the lock phase. Returns (http_status, response):
    202 when a new request was created, 200 when re-submitted idempotently.
    Raises 503 DELETION_STEP_FAILED (account stays DELETING) if a step must be retried.
    """
    db = get_firestore()
    cleaned_reason = reason.strip() if isinstance(reason, str) and reason.strip() else None
    created, data, failed_step = await asyncio.to_thread(
        _run_lock_phase_sync, db, uid=uid, email=email, reason=cleaned_reason
    )
    if failed_step is not None:
        raise ApiHTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail=(
                "Account is locked for deletion but a step did not finish "
                f"({failed_step}). Retry the same request to resume."
            ),
            code="DELETION_STEP_FAILED",
        )

    raw_status = data.get("status") or DeletionRequestStatus.LOCKING.value
    try:
        deletion_status = DeletionRequestStatus(raw_status)
    except ValueError:
        deletion_status = DeletionRequestStatus.LOCKING

    response = AccountDeletionInitResponse(
        uid=uid,
        account_status=AccountStateStatus.DELETING,
        request_id=str(data.get("requestId") or uid),
        deletion_status=deletion_status,
        created=created,
        steps=dict(data.get("steps") or {}),
        created_at=data.get("createdAt"),
        updated_at=data.get("updatedAt"),
    )
    return (status.HTTP_202_ACCEPTED if created else status.HTTP_200_OK), response
