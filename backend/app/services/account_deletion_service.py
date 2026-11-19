"""
app/services/account_deletion_service.py — ST35 account deletion initiation & resume.

Security invariants:
- Identity `uid` comes exclusively from verified Firebase ID token.
- Atomically locks `account_states/{uid}` to DELETING and creates `deletion_requests/{uid}`
  inside a Firestore transaction.
- Idempotent: repeated calls never wipe already-completed step checkpoints.
- Revokes Firebase refresh tokens outside the Firestore transaction; transient auth errors
  never undo the DELETING lock and are retried by the background worker.
"""
from __future__ import annotations

import asyncio
from datetime import datetime, timezone
import logging
from typing import Any, Dict, Optional, Tuple

import firebase_admin.auth as fb_auth
from fastapi import status
from google.cloud import firestore as _fs
from google.cloud.firestore import Client as FirestoreClient

from app.core.auth import parse_account_state_snapshot
from app.core.errors import ApiHTTPException, redact_sensitive_text
from app.schemas.account import (
    AccountStateStatus,
    DeletionRequestResponse,
    DeletionStatus,
)

logger = logging.getLogger(__name__)


def default_deletion_steps() -> Dict[str, Any]:
    """Return canonical initial checkpoint map for a new deletion request."""
    return {
        "sessionRevoked": "PENDING",
        "mediaAssets": {"status": "PENDING", "deleted": 0, "failed": 0},
        "legacyMediaAudit": {"status": "PENDING", "legacyUrls": [], "count": 0},
        "firestorePosts": {"status": "PENDING", "deleted": 0},
        "firestoreUserData": {"status": "PENDING"},
        "firebaseAuth": {"status": "PENDING"},
    }


def _to_utc(value: Any) -> Optional[datetime]:
    if not isinstance(value, datetime):
        return None
    if value.tzinfo is None:
        return value.replace(tzinfo=timezone.utc)
    return value.astimezone(timezone.utc)


def _snapshot_to_response(uid: str, data: Dict[str, Any]) -> DeletionRequestResponse:
    return DeletionRequestResponse(
        uid=uid,
        status=str(data.get("status") or DeletionStatus.PENDING.value),
        steps=dict(data.get("steps") or {}),
        last_error=data.get("lastError"),
        created_at=data.get("createdAt"),
        updated_at=data.get("updatedAt"),
    )


def _initiate_deletion_txn(db: FirestoreClient, uid: str) -> Tuple[Dict[str, Any], bool]:
    """
    Atomically transition account_states/{uid} -> DELETING and create deletion_requests/{uid}.
    Returns `(request_data, needs_revoke)`.
    """
    state_ref = db.collection("account_states").document(uid)
    req_ref = db.collection("deletion_requests").document(uid)

    @_fs.transactional
    def _txn(transaction: _fs.Transaction) -> Tuple[Dict[str, Any], bool]:
        state_snap = state_ref.get(transaction=transaction)
        req_snap = req_ref.get(transaction=transaction)

        account_status = parse_account_state_snapshot(state_snap, uid=uid)
        if account_status == AccountStateStatus.DELETED:
            raise ApiHTTPException(
                status_code=status.HTTP_409_CONFLICT,
                detail="Account has already been deleted.",
                code="ACCOUNT_ALREADY_DELETED",
            )

        if req_snap.exists:
            existing = dict(req_snap.to_dict() or {})
            existing_status = str(existing.get("status") or "")
            if existing_status == DeletionStatus.COMPLETED.value:
                raise ApiHTTPException(
                    status_code=status.HTTP_409_CONFLICT,
                    detail="Account deletion has already completed.",
                    code="ACCOUNT_ALREADY_DELETED",
                )
            if account_status != AccountStateStatus.DELETING:
                transaction.set(
                    state_ref,
                    {
                        "status": AccountStateStatus.DELETING.value,
                        "updatedAt": _fs.SERVER_TIMESTAMP,
                    },
                    merge=True,
                )
            steps = existing.get("steps") if isinstance(existing.get("steps"), dict) else {}
            needs_revoke = steps.get("sessionRevoked") != "DONE"
            return existing, needs_revoke

        steps = default_deletion_steps()
        new_doc: Dict[str, Any] = {
            "uid": uid,
            "status": DeletionStatus.PENDING.value,
            "steps": steps,
            "claimToken": None,
            "lockOwner": None,
            "lockExpiresAt": None,
            "attempts": 0,
            "nextRetryAt": None,
            "lastError": None,
            "createdAt": _fs.SERVER_TIMESTAMP,
            "updatedAt": _fs.SERVER_TIMESTAMP,
        }
        transaction.set(
            state_ref,
            {
                "status": AccountStateStatus.DELETING.value,
                "updatedAt": _fs.SERVER_TIMESTAMP,
            },
            merge=True,
        )
        transaction.set(req_ref, new_doc)
        return new_doc, True

    return _txn(db.transaction())


def _resume_deletion_txn(db: FirestoreClient, uid: str) -> Tuple[Dict[str, Any], bool]:
    """
    Re-queue a FAILED or stalled deletion_requests/{uid} without wiping finished steps.
    """
    state_ref = db.collection("account_states").document(uid)
    req_ref = db.collection("deletion_requests").document(uid)

    @_fs.transactional
    def _txn(transaction: _fs.Transaction) -> Tuple[Dict[str, Any], bool]:
        state_snap = state_ref.get(transaction=transaction)
        req_snap = req_ref.get(transaction=transaction)

        account_status = parse_account_state_snapshot(state_snap, uid=uid)
        if account_status == AccountStateStatus.DELETED:
            raise ApiHTTPException(
                status_code=status.HTTP_409_CONFLICT,
                detail="Account has already been deleted.",
                code="ACCOUNT_ALREADY_DELETED",
            )

        if not req_snap.exists:
            raise ApiHTTPException(
                status_code=status.HTTP_404_NOT_FOUND,
                detail="No deletion request found for this account.",
                code="DELETION_REQUEST_NOT_FOUND",
            )

        existing = dict(req_snap.to_dict() or {})
        existing_status = str(existing.get("status") or "")
        if existing_status == DeletionStatus.COMPLETED.value:
            raise ApiHTTPException(
                status_code=status.HTTP_409_CONFLICT,
                detail="Account deletion has already completed.",
                code="ACCOUNT_ALREADY_DELETED",
            )

        if account_status != AccountStateStatus.DELETING:
            transaction.set(
                state_ref,
                {
                    "status": AccountStateStatus.DELETING.value,
                    "updatedAt": _fs.SERVER_TIMESTAMP,
                },
                merge=True,
            )

        now = datetime.now(tz=timezone.utc)
        lock_expires = _to_utc(existing.get("lockExpiresAt"))
        is_actively_locked = (
            existing_status == DeletionStatus.IN_PROGRESS.value
            and lock_expires is not None
            and now < lock_expires
        )

        if not is_actively_locked:
            patch: Dict[str, Any] = {
                "status": DeletionStatus.PENDING.value,
                "claimToken": None,
                "lockOwner": None,
                "lockExpiresAt": None,
                "nextRetryAt": None,
                "lastError": None,
                "updatedAt": _fs.SERVER_TIMESTAMP,
            }
            if existing_status == DeletionStatus.FAILED.value:
                patch["attempts"] = 0
            transaction.update(req_ref, patch)

        steps = existing.get("steps") if isinstance(existing.get("steps"), dict) else {}
        needs_revoke = steps.get("sessionRevoked") != "DONE"
        return existing, needs_revoke

    return _txn(db.transaction())


def revoke_user_sessions_sync(db: FirestoreClient, uid: str) -> str:
    """
    Revoke Firebase refresh tokens for *uid* and persist checkpoint to deletion_requests/{uid}.
    Returns "DONE" or "FAILED".
    """
    req_ref = db.collection("deletion_requests").document(uid)
    try:
        fb_auth.revoke_refresh_tokens(uid)
        result = "DONE"
    except fb_auth.UserNotFoundError:
        result = "DONE"
    except Exception as exc:
        logger.warning(
            "Failed to revoke refresh tokens for uid=%s (%s: %s)",
            uid,
            type(exc).__name__,
            redact_sensitive_text(str(exc), max_length=200),
        )
        result = "FAILED"

    try:
        req_ref.update(
            {
                "steps.sessionRevoked": result,
                "updatedAt": _fs.SERVER_TIMESTAMP,
            }
        )
    except Exception as exc:
        logger.warning(
            "Failed to persist sessionRevoked=%s for uid=%s (%s)",
            result,
            uid,
            type(exc).__name__,
        )
    return result


def _read_fresh_request_sync(db: FirestoreClient, uid: str) -> Dict[str, Any]:
    snap = db.collection("deletion_requests").document(uid).get()
    return dict(snap.to_dict() or {}) if snap.exists else {}


async def initiate_account_deletion(
    db: FirestoreClient,
    uid: str,
) -> DeletionRequestResponse:
    """Initiate account deletion (ST35): lock DELETING, create request, revoke sessions."""
    _, needs_revoke = await asyncio.to_thread(_initiate_deletion_txn, db, uid)
    if needs_revoke:
        await asyncio.to_thread(revoke_user_sessions_sync, db, uid)
    fresh = await asyncio.to_thread(_read_fresh_request_sync, db, uid)
    return _snapshot_to_response(uid, fresh)


async def resume_account_deletion(
    db: FirestoreClient,
    uid: str,
) -> DeletionRequestResponse:
    """Resume a FAILED or stalled deletion request without losing completed checkpoints."""
    _, needs_revoke = await asyncio.to_thread(_resume_deletion_txn, db, uid)
    if needs_revoke:
        await asyncio.to_thread(revoke_user_sessions_sync, db, uid)
    fresh = await asyncio.to_thread(_read_fresh_request_sync, db, uid)
    return _snapshot_to_response(uid, fresh)
