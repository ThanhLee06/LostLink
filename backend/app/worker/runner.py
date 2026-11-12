"""
app/worker/runner.py — Fair round-robin outbox event sweep, abandoned-media cleanup,
bounded drain helper, and cooperative background WorkerLoop.

Operational note (Render Free / single-instance hosting):
- `WorkerLoop` uses `asyncio.Event` with exponential backoff + jitter and runs ONLY
  while the FastAPI process / standalone worker process is awake. When an instance
  sleeps, work resumes on startup recovery (`recover_pending_on_boot`) or external
  `POST /api/v1/events/wake` triggers.
"""
from __future__ import annotations

import asyncio
from collections import deque
from contextlib import suppress
from datetime import datetime, timezone
import logging
import random
import threading
from typing import Any, Awaitable, Callable, Deque, Dict, List, Optional, Tuple
import uuid

from google.cloud.firestore import Client as FirestoreClient
from google.cloud.firestore_v1 import FieldFilter, Query

from app.core.config import get_settings
from app.core.errors import redact_sensitive_text
from app.worker.claim import ClaimError, TokenMismatchError, claim_job, fail_job
from app.worker.handlers.media_cleanup_handler import (
    _cleanup_claimed_abandoned_asset,
    _query_abandoned_or_pending_media_ids,
    handle_media_cleanup_job,
)

logger = logging.getLogger(__name__)

_ABANDONED_MEDIA_LANE = "__abandonedMedia__"
_batch_rotation_lock = threading.Lock()
_batch_rotation_cursor: int = 0


def reset_worker_scheduler_for_tests() -> None:
    """Reset the fair-rotation lane cursor between unit tests."""
    global _batch_rotation_cursor
    with _batch_rotation_lock:
        _batch_rotation_cursor = 0

JobHandler = Callable[[FirestoreClient, str, Dict[str, Any], str], Awaitable[str]]

# searchJob is deferred to ST32; notifyJob is intentionally disabled (None) until Task 12.
DEFAULT_HANDLERS: Dict[str, Optional[JobHandler]] = {
    "searchJob": None,
    "notifyJob": None,
    "mediaCleanupJob": handle_media_cleanup_job,
}


def _to_utc(value: Any) -> Optional[datetime]:
    if not isinstance(value, datetime):
        return None
    if value.tzinfo is None:
        return value.replace(tzinfo=timezone.utc)
    return value.astimezone(timezone.utc)


def _query_candidates_for_job(
    db: FirestoreClient,
    job_name: str,
    limit: int,
    now: datetime,
) -> List[Tuple[str, Dict[str, Any]]]:
    """
    Paginate PENDING (respecting nextRetryAt without head-of-line starvation)
    and expired PROCESSING events for *job_name* within *limit*.
    """
    if limit <= 0:
        return []

    results: List[Tuple[str, Dict[str, Any]]] = []
    seen_ids: set[str] = set()
    col = db.collection("post_events")

    page_size = min(max(limit * 4, 25), 50)
    max_scan = max(limit * 50, 500)

    # 1. Paginate PENDING events ordered by createdAt, skipping backoff head docs.
    scanned_pending = 0
    last_pending_snap: Any = None
    while len(results) < limit and scanned_pending < max_scan:
        query = (
            col.where(filter=FieldFilter(f"{job_name}.status", "==", "PENDING"))
            .order_by("createdAt", direction=Query.ASCENDING)
        )
        if last_pending_snap is not None:
            query = query.start_after(last_pending_snap)
        page = list(query.limit(page_size).stream())
        if not page:
            break
        scanned_pending += len(page)
        last_pending_snap = page[-1]

        for snap in page:
            if snap.id in seen_ids:
                continue
            data = snap.to_dict() or {}
            job_data = data.get(job_name)
            if not isinstance(job_data, dict):
                continue
            next_retry_at = _to_utc(job_data.get("nextRetryAt"))
            if next_retry_at is not None and next_retry_at > now:
                continue
            seen_ids.add(snap.id)
            results.append((snap.id, data))
            if len(results) >= limit:
                break

        if len(page) < page_size:
            break

    # 2. Paginate expired PROCESSING leases if capacity remains.
    scanned_expired = 0
    last_expired_snap: Any = None
    while len(results) < limit and scanned_expired < max_scan:
        query = (
            col.where(filter=FieldFilter(f"{job_name}.status", "==", "PROCESSING"))
            .where(filter=FieldFilter(f"{job_name}.lockExpiresAt", "<=", now))
            .order_by(f"{job_name}.lockExpiresAt", direction=Query.ASCENDING)
        )
        if last_expired_snap is not None:
            query = query.start_after(last_expired_snap)
        page = list(query.limit(page_size).stream())
        if not page:
            break
        scanned_expired += len(page)
        last_expired_snap = page[-1]

        for snap in page:
            if snap.id in seen_ids:
                continue
            seen_ids.add(snap.id)
            results.append((snap.id, snap.to_dict() or {}))
            if len(results) >= limit:
                break

        if len(page) < page_size:
            break

    return results


async def _execute_one_abandoned_asset(
    db: FirestoreClient,
    *,
    asset_id: str,
    cutoff: datetime,
    now: Optional[datetime] = None,
) -> bool:
    """Attempt transactional claim + Cloudinary cleanup for one abandoned/pending asset."""
    return await _cleanup_claimed_abandoned_asset(
        db,
        asset_id=asset_id,
        cutoff=cutoff,
        now=now,
    )


async def run_batch(
    db: FirestoreClient,
    *,
    batch_size: Optional[int] = None,
    worker_id: Optional[str] = None,
    lease_seconds: Optional[int] = None,
    handlers: Optional[Dict[str, Optional[JobHandler]]] = None,
    include_media_sweep: bool = True,
) -> int:
    """
    Claim and execute up to *batch_size* eligible jobs using round-robin fairness
    across enabled handlers (and abandoned draft media when mediaCleanupJob is active).
    """
    settings = get_settings()
    effective_limit = batch_size if batch_size is not None else settings.worker_batch_limit
    effective_lease = (
        lease_seconds if lease_seconds is not None else settings.worker_lease_seconds
    )
    effective_worker_id = worker_id or f"worker-{uuid.uuid4().hex[:8]}"

    registry = handlers if handlers is not None else DEFAULT_HANDLERS
    active_handlers = {
        name: fn for name, fn in registry.items() if fn is not None
    }
    if not active_handlers or effective_limit <= 0:
        return 0

    now = datetime.now(tz=timezone.utc)
    queues: Dict[str, Deque[Tuple[str, Dict[str, Any]]]] = {}
    for job_name in active_handlers:
        items = await asyncio.to_thread(
            _query_candidates_for_job, db, job_name, effective_limit, now
        )
        queues[job_name] = deque(items)

    abandoned_queue: Deque[str] = deque()
    abandoned_cutoff = now
    sweep_enabled = include_media_sweep and "mediaCleanupJob" in active_handlers
    if sweep_enabled:
        from datetime import timedelta

        abandoned_cutoff = now - timedelta(seconds=settings.media_abandoned_ttl_seconds)
        abandoned_ids = await asyncio.to_thread(
            _query_abandoned_or_pending_media_ids,
            db,
            cutoff=abandoned_cutoff,
            limit=effective_limit,
            now=now,
        )
        abandoned_queue = deque(abandoned_ids)

    lanes = list(active_handlers.keys())
    if sweep_enabled:
        lanes.append(_ABANDONED_MEDIA_LANE)

    global _batch_rotation_cursor
    with _batch_rotation_lock:
        start_idx = _batch_rotation_cursor % len(lanes)
        _batch_rotation_cursor = (_batch_rotation_cursor + 1) % 1_000_000
    ordered_lanes = lanes[start_idx:] + lanes[:start_idx]

    processed = 0

    while processed < effective_limit and (
        any(queues.values()) or bool(abandoned_queue)
    ):
        made_progress = False
        for lane_name in ordered_lanes:
            if processed >= effective_limit:
                break

            if lane_name == _ABANDONED_MEDIA_LANE:
                while abandoned_queue:
                    asset_id = abandoned_queue.popleft()
                    cleaned = await _execute_one_abandoned_asset(
                        db, asset_id=asset_id, cutoff=abandoned_cutoff, now=now
                    )
                    if cleaned:
                        processed += 1
                        made_progress = True
                        break
                continue

            q = queues[lane_name]
            while q:
                event_id, event_data = q.popleft()
                try:
                    claim_token = await asyncio.to_thread(
                        claim_job,
                        db,
                        event_id,
                        lane_name,
                        effective_worker_id,
                        effective_lease,
                    )
                except ClaimError:
                    continue

                processed += 1
                made_progress = True
                try:
                    await active_handlers[lane_name](db, event_id, event_data, claim_token)
                except TokenMismatchError as exc:
                    logger.warning(
                        "Worker %s lost/expired lease for %s/%s (%s)",
                        effective_worker_id,
                        event_id,
                        lane_name,
                        type(exc).__name__,
                    )
                except Exception as exc:
                    redacted_err = redact_sensitive_text(str(exc), max_length=300)
                    logger.warning(
                        "Worker %s failed %s/%s (%s: %s)",
                        effective_worker_id,
                        event_id,
                        lane_name,
                        type(exc).__name__,
                        redacted_err,
                    )
                    await asyncio.to_thread(
                        fail_job,
                        db,
                        event_id,
                        lane_name,
                        claim_token,
                        redacted_err,
                    )
                break

        if not made_progress:
            break

    return processed


async def drain_until_idle(
    db: FirestoreClient,
    *,
    max_batches: int = 10,
    batch_size: Optional[int] = None,
    worker_id: Optional[str] = None,
    lease_seconds: Optional[int] = None,
    handlers: Optional[Dict[str, Optional[JobHandler]]] = None,
    include_media_sweep: bool = True,
) -> int:
    """
    Repeatedly execute bounded `run_batch` sweeps until a sweep processes 0 items
    or `max_batches` is reached.
    """
    total = 0
    for _ in range(max(1, max_batches)):
        count = await run_batch(
            db,
            batch_size=batch_size,
            worker_id=worker_id,
            lease_seconds=lease_seconds,
            handlers=handlers,
            include_media_sweep=include_media_sweep,
        )
        total += count
        if count == 0:
            break
        await asyncio.sleep(0)
    return total


async def recover_pending_on_boot(db: FirestoreClient) -> int:
    """Run one bounded recovery sweep on server startup without crashing boot on transient error."""
    try:
        processed = await run_batch(db)
        logger.info("Boot recovery sweep processed %d job(s)", processed)
        return processed
    except Exception as exc:
        logger.warning("Boot recovery sweep skipped/failed (%s)", type(exc).__name__)
        return 0


class WorkerLoop:
    """
    Cooperative asyncio worker loop driven by wake/stop Events with exponential
    backoff + jitter when idle (no busy-looping).

    Note: In-process loops only execute while the host instance is awake (e.g., on
    Render Free, sleeping instances rely on startup recovery and `/events/wake`).
    """

    def __init__(
        self,
        db: FirestoreClient,
        *,
        batch_size: Optional[int] = None,
        worker_id: Optional[str] = None,
        lease_seconds: Optional[int] = None,
        handlers: Optional[Dict[str, Optional[JobHandler]]] = None,
        min_poll_seconds: Optional[float] = None,
        max_poll_seconds: Optional[float] = None,
        max_batches_per_wake: int = 5,
    ) -> None:
        settings = get_settings()
        self.db = db
        self.batch_size = batch_size
        self.worker_id = worker_id or f"loop-{uuid.uuid4().hex[:8]}"
        self.lease_seconds = lease_seconds
        self.handlers = handlers
        self.min_poll_seconds = (
            min_poll_seconds
            if min_poll_seconds is not None
            else settings.worker_poll_min_seconds
        )
        self.max_poll_seconds = max(
            self.min_poll_seconds,
            max_poll_seconds
            if max_poll_seconds is not None
            else settings.worker_poll_max_seconds,
        )
        self.max_batches_per_wake = max(1, max_batches_per_wake)
        self._wake_event = asyncio.Event()
        self._stop_event = asyncio.Event()
        self._task: Optional[asyncio.Task[None]] = None

    @property
    def is_running(self) -> bool:
        return self._task is not None and not self._task.done()

    def start(self) -> asyncio.Task[None]:
        if self._task is None or self._task.done():
            self._stop_event.clear()
            self._task = asyncio.create_task(self._run(), name=f"worker-loop-{self.worker_id}")
        return self._task

    def wake(self) -> None:
        self._wake_event.set()

    async def stop(self, timeout: float = 5.0) -> None:
        self._stop_event.set()
        self._wake_event.set()
        if self._task is not None:
            try:
                await asyncio.wait_for(self._task, timeout=timeout)
            except asyncio.TimeoutError:
                self._task.cancel()
                with suppress(asyncio.CancelledError):
                    await self._task
            finally:
                self._task = None

    async def _wait_for_wake_or_stop(self, delay_seconds: float) -> None:
        wake_task = asyncio.create_task(self._wake_event.wait())
        stop_task = asyncio.create_task(self._stop_event.wait())
        try:
            done, pending = await asyncio.wait(
                {wake_task, stop_task},
                timeout=delay_seconds,
                return_when=asyncio.FIRST_COMPLETED,
            )
            for task in pending:
                task.cancel()
            for task in done:
                with suppress(Exception):
                    task.result()
        finally:
            for task in (wake_task, stop_task):
                if not task.done():
                    task.cancel()
                    with suppress(asyncio.CancelledError):
                        await task

    async def _run(self) -> None:
        backoff = self.min_poll_seconds
        while not self._stop_event.is_set():
            self._wake_event.clear()
            processed = 0
            try:
                processed = await drain_until_idle(
                    self.db,
                    max_batches=self.max_batches_per_wake,
                    batch_size=self.batch_size,
                    worker_id=self.worker_id,
                    lease_seconds=self.lease_seconds,
                    handlers=self.handlers,
                )
            except Exception as exc:
                logger.warning(
                    "WorkerLoop cycle failed (%s: %s)",
                    type(exc).__name__,
                    redact_sensitive_text(str(exc), max_length=200),
                )

            if self._stop_event.is_set():
                break

            if processed > 0:
                backoff = self.min_poll_seconds
                # If wake was signaled mid-drain, continue promptly without sleeping.
                if self._wake_event.is_set():
                    await asyncio.sleep(0)
                    continue

            jitter = random.uniform(0.0, 0.25 * backoff)
            await self._wait_for_wake_or_stop(backoff + jitter)
            if processed == 0:
                backoff = min(backoff * 2.0, self.max_poll_seconds)
