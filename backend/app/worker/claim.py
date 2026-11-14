"""
app/worker/claim.py — Atomic lease/claim logic using Firestore transactions.

Job sub-document schema inside post_events/{eventId}:
  {job_name}: {
      status: PENDING | PROCESSING | DONE | FAILED | SKIPPED,
      attempts: int,
      claimToken: str | null,
      lockOwner: str | null,
      lockExpiresAt: datetime | null,
      nextRetryAt: datetime | null,
      lastError: str | null,
  }
"""
from __future__ import annotations

import logging
from datetime import datetime, timedelta, timezone
from typing import Any, Optional, Tuple
import uuid

from google.cloud import firestore as _fs
from google.cloud.firestore import Client as FirestoreClient

from app.core.errors import redact_sensitive_text

logger = logging.getLogger(__name__)

VALID_JOBS = frozenset({"searchJob", "notifyJob", "mediaCleanupJob"})
MAX_ATTEMPTS = 3
_BACKOFF_BASE_SECONDS = 30
_MAX_ERROR_LENGTH = 500


class ClaimError(Exception):
    """Raised when a job cannot be claimed."""


class TokenMismatchError(Exception):
    """Raised when the provided claimToken/state no longer matches."""


def _validate_job_name(job_name: str) -> None:
    if job_name not in VALID_JOBS:
        raise ValueError(f"Unsupported job name: {job_name!r}")


def _now_utc() -> datetime:
    return datetime.now(tz=timezone.utc)


def _to_utc(value: Any) -> Optional[datetime]:
    """Normalize naive or aware Firestore/Python datetimes to UTC-aware datetime."""
    if value is None:
        return None
    if isinstance(value, datetime):
        if value.tzinfo is None:
            return value.replace(tzinfo=timezone.utc)
        return value.astimezone(timezone.utc)
    raise TypeError(f"Expected datetime or None for timestamp field, got {type(value).__name__}")


def claim_job(
    db: FirestoreClient,
    event_id: str,
    job_name: str,
    worker_id: str,
    lease_seconds: int = 60,
    max_attempts: int = MAX_ATTEMPTS,
) -> str:
    """
    Atomically claim *job_name* inside post_events/{event_id}.

    - Reclaims expired PROCESSING leases, incrementing attempts and permanently
      failing at `>= max_attempts` to prevent infinite crash loops.
    - Respects `nextRetryAt` backoff on PENDING jobs.
    """
    _validate_job_name(job_name)
    event_ref = db.collection("post_events").document(event_id)

    @_fs.transactional
    def _txn(transaction: _fs.Transaction) -> Tuple[bool, str]:
        snap = event_ref.get(transaction=transaction)
        if not snap.exists:
            raise ClaimError(f"Event document {event_id} does not exist.")

        data = snap.to_dict() or {}
        job = data.get(job_name)
        if not isinstance(job, dict):
            raise ClaimError(f"Event {event_id} is missing job map {job_name}.")

        job_status = str(job.get("status") or "")
        attempts = int(job.get("attempts") or 0)
        now = _now_utc()

        if job_status == "PENDING":
            if attempts >= max_attempts:
                transaction.update(
                    event_ref,
                    {
                        f"{job_name}.status": "FAILED",
                        f"{job_name}.claimToken": None,
                        f"{job_name}.lockOwner": None,
                        f"{job_name}.lockExpiresAt": None,
                        f"{job_name}.nextRetryAt": None,
                        f"{job_name}.lastError": job.get("lastError")
                        or "Max attempts reached.",
                    },
                )
                return False, f"Job {job_name} in {event_id} reached max attempts."

            next_retry_at = _to_utc(job.get("nextRetryAt"))
            if next_retry_at is not None and now < next_retry_at:
                raise ClaimError(
                    f"Job {job_name} in {event_id} is PENDING until {next_retry_at.isoformat()}."
                )
            new_attempts = attempts

        elif job_status == "PROCESSING":
            lock_expires_at = _to_utc(job.get("lockExpiresAt"))
            if lock_expires_at is None or now < lock_expires_at:
                raise ClaimError(
                    f"Job {job_name} in {event_id} is actively PROCESSING."
                )
            # Reclaim expired lease and count the crashed attempt
            new_attempts = attempts + 1
            if new_attempts >= max_attempts:
                transaction.update(
                    event_ref,
                    {
                        f"{job_name}.status": "FAILED",
                        f"{job_name}.attempts": new_attempts,
                        f"{job_name}.claimToken": None,
                        f"{job_name}.lockOwner": None,
                        f"{job_name}.lockExpiresAt": None,
                        f"{job_name}.nextRetryAt": None,
                        f"{job_name}.lastError": "Max attempts reached after expired worker lease.",
                    },
                )
                return False, (
                    f"Job {job_name} in {event_id} exceeded max attempts after expired lease."
                )
        else:
            raise ClaimError(
                f"Job {job_name} in {event_id} is not claimable (status={job_status})."
            )

        claim_token = str(uuid.uuid4())
        new_expires = now + timedelta(seconds=lease_seconds)

        transaction.update(
            event_ref,
            {
                f"{job_name}.status": "PROCESSING",
                f"{job_name}.attempts": new_attempts,
                f"{job_name}.claimToken": claim_token,
                f"{job_name}.lockOwner": worker_id,
                f"{job_name}.lockExpiresAt": new_expires,
                f"{job_name}.nextRetryAt": None,
            },
        )
        return True, claim_token

    claimed, payload = _txn(db.transaction())
    if not claimed:
        raise ClaimError(payload)
    return payload


def assert_lease_valid(
    job: Dict[str, Any],
    claim_token: str,
    *,
    now: Optional[datetime] = None,
    event_id: str = "",
    job_name: str = "",
) -> None:
    """
    Verify that a job sub-map still holds an active, unexpired lease for *claim_token*.
    Raises TokenMismatchError when status, claimToken, or lockExpiresAt is invalid/expired.
    """
    effective_now = now or _now_utc()
    if job.get("status") != "PROCESSING" or job.get("claimToken") != claim_token:
        raise TokenMismatchError(
            f"Stale or invalid claimToken for {job_name or 'job'} in {event_id or 'event'}."
        )
    lock_expires_at = _to_utc(job.get("lockExpiresAt"))
    if lock_expires_at is None or effective_now >= lock_expires_at:
        raise TokenMismatchError(
            f"Expired worker lease for {job_name or 'job'} in {event_id or 'event'}."
        )


def renew_lease(
    db: FirestoreClient,
    event_id: str,
    job_name: str,
    claim_token: str,
    lease_seconds: int = 60,
) -> datetime:
    """
    Extend an active lease inside a Firestore transaction iff status==PROCESSING,
    claimToken matches, and lockExpiresAt has not yet expired.
    """
    _validate_job_name(job_name)
    event_ref = db.collection("post_events").document(event_id)

    @_fs.transactional
    def _txn(transaction: _fs.Transaction) -> datetime:
        snap = event_ref.get(transaction=transaction)
        if not snap.exists:
            raise TokenMismatchError(f"Event document {event_id} does not exist.")
        data = snap.to_dict() or {}
        job = data.get(job_name) or {}
        now = _now_utc()
        assert_lease_valid(job, claim_token, now=now, event_id=event_id, job_name=job_name)
        new_expires = now + timedelta(seconds=lease_seconds)
        transaction.update(event_ref, {f"{job_name}.lockExpiresAt": new_expires})
        return new_expires

    return _txn(db.transaction())


def complete_job(
    db: FirestoreClient,
    event_id: str,
    job_name: str,
    claim_token: str,
    final_status: str = "DONE",
) -> None:
    """
    Mark *job_name* as DONE or SKIPPED iff status == PROCESSING, claimToken matches,
    and lockExpiresAt has not expired. Clears claimToken, lockOwner, lockExpiresAt,
    and nextRetryAt on finish.
    """
    _validate_job_name(job_name)
    if final_status not in ("DONE", "SKIPPED"):
        raise ValueError(f"Invalid completion status: {final_status!r}")

    event_ref = db.collection("post_events").document(event_id)

    @_fs.transactional
    def _txn(transaction: _fs.Transaction) -> None:
        snap = event_ref.get(transaction=transaction)
        if not snap.exists:
            raise TokenMismatchError(f"Event document {event_id} does not exist.")

        data = snap.to_dict() or {}
        job = data.get(job_name) or {}
        assert_lease_valid(
            job,
            claim_token,
            now=_now_utc(),
            event_id=event_id,
            job_name=job_name,
        )

        transaction.update(
            event_ref,
            {
                f"{job_name}.status": final_status,
                f"{job_name}.claimToken": None,
                f"{job_name}.lockOwner": None,
                f"{job_name}.lockExpiresAt": None,
                f"{job_name}.nextRetryAt": None,
                f"{job_name}.lastError": None,
            },
        )

    _txn(db.transaction())


def fail_job(
    db: FirestoreClient,
    event_id: str,
    job_name: str,
    claim_token: str,
    error_message: str,
    max_attempts: int = MAX_ATTEMPTS,
) -> None:
    """
    Record job failure iff status == PROCESSING, claimToken matches, and lease is active.
    Clears lock fields and schedules backoff retry or marks FAILED permanently.
    """
    _validate_job_name(job_name)
    event_ref = db.collection("post_events").document(event_id)

    @_fs.transactional
    def _txn(transaction: _fs.Transaction) -> None:
        snap = event_ref.get(transaction=transaction)
        if not snap.exists:
            logger.warning("fail_job: event %s not found", event_id)
            return

        data = snap.to_dict() or {}
        job = data.get(job_name) or {}
        now = _now_utc()
        try:
            assert_lease_valid(
                job, claim_token, now=now, event_id=event_id, job_name=job_name
            )
        except TokenMismatchError:
            logger.warning(
                "fail_job: stale/expired lease for %s in %s — ignoring",
                job_name,
                event_id,
            )
            return

        attempts = int(job.get("attempts") or 0) + 1
        truncated_error = (
            redact_sensitive_text(error_message, max_length=_MAX_ERROR_LENGTH)
            or "Unknown worker error"
        )

        updates = {
            f"{job_name}.attempts": attempts,
            f"{job_name}.lastError": truncated_error,
            f"{job_name}.claimToken": None,
            f"{job_name}.lockOwner": None,
            f"{job_name}.lockExpiresAt": None,
        }
        if attempts >= max_attempts:
            updates[f"{job_name}.status"] = "FAILED"
            updates[f"{job_name}.nextRetryAt"] = None
        else:
            backoff_seconds = _BACKOFF_BASE_SECONDS * (2 ** (attempts - 1))
            updates[f"{job_name}.status"] = "PENDING"
            updates[f"{job_name}.nextRetryAt"] = now + timedelta(seconds=backoff_seconds)

        transaction.update(event_ref, updates)
        logger.info(
            "fail_job: %s/%s attempts=%d nextStatus=%s",
            event_id,
            job_name,
            attempts,
            updates[f"{job_name}.status"],
        )

    _txn(db.transaction())
