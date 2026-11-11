"""
app/routers/account.py — /api/v1/account/* endpoints.

Accessible to verified @ut.edu.vn students INCLUDING accounts in DELETING
state (`require_verified_student`), per docs/Contract-B0-Schema.md §3.7 & §4.2.
"""
from __future__ import annotations

import asyncio
from datetime import datetime
from typing import Any, Dict, Optional

from fastapi import APIRouter, Depends, status

from app.core.auth import get_account_status, require_verified_student
from app.core.errors import ApiHTTPException
from app.core.firebase import get_firestore
from app.schemas.account import (
    AccountStateResponse,
    AccountStateStatus,
    DeletionRequestResponse,
)

router = APIRouter(prefix="/api/v1/account", tags=["account"])


def _read_account_state_doc(uid: str) -> AccountStateResponse:
    db = get_firestore()
    snap = db.collection("account_states").document(uid).get()
    if not snap.exists:
        return AccountStateResponse(
            uid=uid,
            status=AccountStateStatus.ACTIVE,
            updated_at=None,
        )
    data = snap.to_dict() or {}
    account_status = get_account_status(db, uid)
    updated_at: Optional[datetime] = data.get("updatedAt")
    return AccountStateResponse(
        uid=uid,
        status=account_status,
        updated_at=updated_at,
    )


def _read_deletion_request_doc(uid: str) -> Optional[DeletionRequestResponse]:
    db = get_firestore()
    snap = db.collection("deletion_requests").document(uid).get()
    if not snap.exists:
        return None
    data = snap.to_dict() or {}
    return DeletionRequestResponse(
        uid=uid,
        status=str(data.get("status") or "PENDING"),
        steps=dict(data.get("steps") or {}),
        created_at=data.get("createdAt"),
        updated_at=data.get("updatedAt"),
    )


@router.get("/state", response_model=AccountStateResponse)
async def get_account_state(
    decoded: Dict[str, Any] = Depends(require_verified_student),
) -> AccountStateResponse:
    """Return the caller's current account state (ACTIVE/DELETING/DELETED)."""
    uid: str = decoded["uid"]
    return await asyncio.to_thread(_read_account_state_doc, uid)


@router.get("/deletion", response_model=DeletionRequestResponse)
async def get_deletion_request(
    decoded: Dict[str, Any] = Depends(require_verified_student),
) -> DeletionRequestResponse:
    """Return the caller's deletion_requests/{uid} progress document."""
    uid: str = decoded["uid"]
    result = await asyncio.to_thread(_read_deletion_request_doc, uid)
    if result is None:
        raise ApiHTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="No deletion request found for this account.",
            code="DELETION_REQUEST_NOT_FOUND",
        )
    return result
