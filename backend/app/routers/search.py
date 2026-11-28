"""
app/routers/search.py — POST /api/v1/search endpoint.

Implements PLAN.md §6:
- Requires verified active student via Depends(require_active_student).
- Offloads bounded synchronous search operation to thread with asyncio timeout.
- Returns strict public SearchResponse model.
"""
from __future__ import annotations

import asyncio
import logging
import time
from typing import Any, Dict

from fastapi import APIRouter, Depends, status

from app.core.auth import require_active_student
from app.core.errors import ApiHTTPException
from app.core.firebase import get_firestore
from app.schemas.search import SearchRequest, SearchResponse
from app.services.search_service import search_posts

logger = logging.getLogger(__name__)
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
    started = time.perf_counter()

    try:
        response = await asyncio.wait_for(
            asyncio.to_thread(search_posts, db, request, caller_uid),
            timeout=10.0,
        )
    except asyncio.TimeoutError:
        raise ApiHTTPException(
            status_code=status.HTTP_504_GATEWAY_TIMEOUT,
            detail="Search request timed out.",
            code="DEADLINE_EXCEEDED",
        )

    latency_ms = (time.perf_counter() - started) * 1000.0
    top_score = max((item.score for item in response.items), default=0.0)
    # Log ẩn danh phục vụ theo dõi chất lượng tìm kiếm (không ghi từ khóa hay UID)
    logger.info(
        "search_query latencyMs=%.1f resultCount=%d topScore=%.4f searchMode=%s hasQuery=%s hasFilters=%s",
        latency_ms,
        len(response.items),
        top_score,
        response.search_mode,
        bool(request.query.strip()),
        bool(request.type or request.category_ids or request.area_ids or request.time_filter.value != "ALL"),
    )
    return response
