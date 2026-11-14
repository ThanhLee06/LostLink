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
