"""
app/routers/search.py — POST /api/v1/search endpoint.

Implements PLAN.md §6:
- Requires verified active student via Depends(require_active_student).
- Offloads bounded synchronous search operation to thread with asyncio timeout.
- Returns strict public SearchResponse model.
"""
from __future__ import annotations

import asyncio
from typing import Any, Dict

from fastapi import APIRouter, Depends, status

from app.core.auth import require_active_student
from app.core.errors import ApiHTTPException
from app.core.firebase import get_firestore
from app.schemas.search import SearchRequest, SearchResponse
from app.services.search_service import search_posts

router = APIRouter(prefix="/api/v1", tags=["search"])


@router.post(
    "/search",
    response_model=SearchResponse,
    status_code=status.HTTP_200_OK,
    summary="Search lost and found posts by keyword, taxonomy, and time bounds",
)
async def search(
    request: SearchRequest,
    decoded: Dict[str, Any] = Depends(require_active_student),
) -> SearchResponse:
    caller_uid: str = decoded["uid"]
    db = get_firestore()

    try:
        return await asyncio.wait_for(
            asyncio.to_thread(search_posts, db, request, caller_uid),
            timeout=10.0,
        )
    except asyncio.TimeoutError:
        raise ApiHTTPException(
            status_code=status.HTTP_504_GATEWAY_TIMEOUT,
            detail="Search request timed out.",
            code="DEADLINE_EXCEEDED",
        )
