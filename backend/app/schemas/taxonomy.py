"""
app/schemas/taxonomy.py — Canonical category/area IDs, display labels, and legacy aliases.

Matches docs/Contract-B0-Schema.md §1.
"""
from __future__ import annotations

from enum import Enum
from typing import Dict, Optional


class PostCategoryId(str, Enum):
    WALLET = "wallet"
    STUDENT_CARD = "student_card"
    ELECTRONICS = "electronics"
    KEYS = "keys"
    BAG = "bag"
    DOCUMENTS = "documents"
    OTHER = "other"


class PostAreaId(str, Enum):
    CAMPUS_1 = "campus_1"
    CAMPUS_2 = "campus_2"
    CAMPUS_3 = "campus_3"
    DORMITORY = "dormitory"
    A = "a"
    B = "b"
    C = "c"
    D = "d"
    E = "e"
    CANTEEN = "canteen"
    LIBRARY = "library"
    PARKING = "parking"
    AUDITORIUM = "auditorium"


class AreaChoice(str, Enum):
    KNOWN = "KNOWN"
    OTHER = "OTHER"
    UNKNOWN = "UNKNOWN"


class TimeMode(str, Enum):
    EXACT = "EXACT"
    RANGE = "RANGE"
    DESCRIBED = "DESCRIBED"
    UNKNOWN = "UNKNOWN"


CATEGORY_LABELS: Dict[PostCategoryId, str] = {
    PostCategoryId.WALLET: "Ví tiền",
    PostCategoryId.STUDENT_CARD: "Thẻ sinh viên",
    PostCategoryId.ELECTRONICS: "Điện thoại/Laptop",
    PostCategoryId.KEYS: "Chìa khóa",
    PostCategoryId.BAG: "Balo/Túi xách",
    PostCategoryId.DOCUMENTS: "Giấy tờ",
    PostCategoryId.OTHER: "Đồ dùng khác",
}

# Note: NEVER map "documents" to "student_card".
CATEGORY_LEGACY_ALIASES: Dict[str, PostCategoryId] = {
    "phone": PostCategoryId.ELECTRONICS,
    "laptop": PostCategoryId.ELECTRONICS,
}

AREA_LABELS: Dict[PostAreaId, str] = {
    PostAreaId.CAMPUS_1: "Cơ sở 1 (Võ Oanh)",
    PostAreaId.CAMPUS_2: "Cơ sở 2 (Quận 12)",
    PostAreaId.CAMPUS_3: "Cơ sở 3 (Thủ Đức)",
    PostAreaId.DORMITORY: "Ký túc xá",
    PostAreaId.A: "Tòa A",
    PostAreaId.B: "Tòa B",
    PostAreaId.C: "Tòa C",
    PostAreaId.D: "Tòa D",
    PostAreaId.E: "Tòa E",
    PostAreaId.CANTEEN: "Căn tin",
    PostAreaId.LIBRARY: "Thư viện",
    PostAreaId.PARKING: "Bãi giữ xe",
    PostAreaId.AUDITORIUM: "Hội trường",
}

AREA_LEGACY_ALIASES: Dict[str, PostAreaId] = {
    "Auditorium": PostAreaId.AUDITORIUM,
}


def normalize_category_id(raw: Optional[str]) -> Optional[PostCategoryId]:
    """Resolve canonical PostCategoryId or approved legacy alias, else None."""
    if not raw:
        return None
    cleaned = raw.strip()
    if cleaned in CATEGORY_LEGACY_ALIASES:
        return CATEGORY_LEGACY_ALIASES[cleaned]
    try:
        return PostCategoryId(cleaned)
    except ValueError:
        return None


def normalize_area_id(raw: Optional[str]) -> Optional[PostAreaId]:
    """Resolve canonical PostAreaId or approved legacy alias, else None."""
    if not raw:
        return None
    cleaned = raw.strip()
    if cleaned in AREA_LEGACY_ALIASES:
        return AREA_LEGACY_ALIASES[cleaned]
    try:
        return PostAreaId(cleaned)
    except ValueError:
        return None
