"""
app/routers/health.py — GET /health readiness check.

Returns minimal liveness info without exposing secrets or internal configuration.
"""
from __future__ import annotations

from fastapi import APIRouter

from app.schemas.common import HealthResponse

router = APIRouter(tags=["health"])


@router.get("/health", response_model=HealthResponse)
async def health_check() -> HealthResponse:
    """Minimal readiness check returning HTTP 200."""
    return HealthResponse(status="ok", version="0.1.0")
