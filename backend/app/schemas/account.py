"""
app/schemas/account.py — Account state and deletion request schemas.

Matches docs/Contract-B0-Schema.md §3.7 and §4.2.
"""
from __future__ import annotations

from datetime import datetime
from enum import Enum
from typing import Any, Dict, Optional

from pydantic import Field

from app.schemas.common import StrictCamelModel


class AccountStateStatus(str, Enum):
    ACTIVE = "ACTIVE"
    DELETING = "DELETING"
    DELETED = "DELETED"


class AccountStateResponse(StrictCamelModel):
    uid: str = Field(..., min_length=1)
    status: AccountStateStatus
    updated_at: Optional[datetime] = None


class DeletionRequestResponse(StrictCamelModel):
    uid: str = Field(..., min_length=1)
    status: str = Field(..., min_length=1)
    steps: Dict[str, Any] = Field(default_factory=dict)
    created_at: Optional[datetime] = None
    updated_at: Optional[datetime] = None


class DeletionRequestStatus(str, Enum):
    """
    Lifecycle of deletion_requests/{uid}.

    - LOCKING: account_states is DELETING; posts hiding + session revocation in progress.
    - QUEUED: lock phase finished; waiting for the cleanup worker (cleanupJob=PENDING).
    - CLEANING / COMPLETED / FAILED: reserved for the cleanup worker (next task).
    """

    LOCKING = "LOCKING"
    QUEUED = "QUEUED"
    CLEANING = "CLEANING"
    COMPLETED = "COMPLETED"
    FAILED = "FAILED"


class DeletionStepStatus(str, Enum):
    PENDING = "PENDING"
    DONE = "DONE"
    FAILED = "FAILED"


class AccountDeletionInitRequest(StrictCamelModel):
    """Body of POST /api/v1/account/deletion — explicit user confirmation is mandatory."""

    confirm: bool = Field(..., description="Must be true to confirm irreversible deletion.")
    reason: Optional[str] = Field(default=None, max_length=500)


class AccountDeletionInitResponse(StrictCamelModel):
    uid: str = Field(..., min_length=1)
    account_status: AccountStateStatus
    request_id: str = Field(..., min_length=1)
    deletion_status: DeletionRequestStatus
    created: bool
    steps: Dict[str, Any] = Field(default_factory=dict)
    created_at: Optional[datetime] = None
    updated_at: Optional[datetime] = None
