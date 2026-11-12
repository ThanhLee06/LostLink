"""
app/schemas/report.py — Report enums and schemas for reports/{postId}_{reporterUid}.

Matches docs/Contract-B0-Schema.md §3.8.
"""
from __future__ import annotations

from datetime import datetime
from enum import Enum
from typing import Optional

from pydantic import Field

from app.schemas.common import StrictCamelModel


class ReportReason(str, Enum):
    INAPPROPRIATE = "INAPPROPRIATE"
    WRONG_INFO = "WRONG_INFO"
    SPAM = "SPAM"
    OTHER = "OTHER"


class ReportStatus(str, Enum):
    PENDING = "PENDING"
    RESOLVED_HIDDEN = "RESOLVED_HIDDEN"
    REJECTED = "REJECTED"


class ReportCreate(StrictCamelModel):
    reason: ReportReason
    description: Optional[str] = Field(default=None, max_length=500)


class ReportRead(ReportCreate):
    post_id: str = Field(..., min_length=1)
    reporter_uid: str = Field(..., min_length=1)
    status: ReportStatus = ReportStatus.PENDING
    created_at: datetime
    public_note: Optional[str] = Field(default=None, max_length=500)
