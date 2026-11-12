"""
app/routers/events.py — POST /api/v1/events/wake

Triggers one bounded worker sweep over pending/expired post_events.
Protected by `require_wake_caller` and a thread-safe in-memory IP rate limiter.
"""
from __future__ import annotations

import threading
import time
from collections import defaultdict
from typing import Any, Dict, List

from fastapi import APIRouter, Depends, Request, status

from app.core.auth import require_wake_caller
from app.core.config import get_settings
from app.core.errors import ApiHTTPException
from app.core.firebase import get_firestore
from app.schemas.post import WakeResponse
from app.worker.runner import run_batch

router = APIRouter(prefix="/api/v1/events", tags=["events"])


class _WakeRateLimiter:
    """Thread-safe sliding-window in-memory rate limiter for /events/wake."""

    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._buckets: Dict[str, List[float]] = defaultdict(list)

    def check(self, key: str, *, max_calls: int, window_seconds: int) -> None:
        now = time.monotonic()
        cutoff = now - window_seconds
        with self._lock:
            # Prune expired timestamps for caller and stale empty keys periodically
            recent = [ts for ts in self._buckets.get(key, ()) if ts > cutoff]
            if len(recent) >= max_calls:
                self._buckets[key] = recent
                raise ApiHTTPException(
                    status_code=status.HTTP_429_TOO_MANY_REQUESTS,
                    detail=f"Rate limit exceeded: max {max_calls} calls per {window_seconds}s.",
                    code="RATE_LIMIT_EXCEEDED",
                )
            recent.append(now)
            self._buckets[key] = recent

            if len(self._buckets) > 1024:
                stale_keys = [
                    k for k, vals in self._buckets.items() if not vals or vals[-1] <= cutoff
                ]
                for stale in stale_keys:
                    self._buckets.pop(stale, None)

    def reset(self) -> None:
        with self._lock:
            self._buckets.clear()


_rate_limiter = _WakeRateLimiter()


def reset_wake_rate_limiter_for_tests() -> None:
    """Clear in-memory rate limit buckets between unit/integration tests."""
    _rate_limiter.reset()


@router.post("/wake", response_model=WakeResponse)
async def wake_worker(
    request: Request,
    caller: Dict[str, Any] = Depends(require_wake_caller),
) -> WakeResponse:
    """
    Kick the worker to run one bounded batch sweep over post_events.
    Ignores any client body payload (client never injects event mutations).
    """
    settings = get_settings()
    client_ip = request.client.host if request.client else "unknown"
    _rate_limiter.check(
        client_ip,
        max_calls=settings.wake_rate_limit_max,
        window_seconds=settings.wake_rate_limit_window_seconds,
    )

    db = get_firestore()
    processed = await run_batch(db)
    worker_loop = getattr(request.app.state, "worker_loop", None)
    if worker_loop is not None:
        worker_loop.wake()
    return WakeResponse(
        processed=processed,
        message=f"Worker sweep complete. {processed} job(s) processed.",
    )
