"""Tập dữ liệu mẫu và hàm đánh giá chất lượng tìm kiếm Top-K cho ST34."""
from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
import time
from typing import Any, Callable, Dict, List, Optional

from app.schemas.post import PostType
from app.schemas.search import SearchRequest, SearchResponse, SearchTimeFilter
from app.schemas.taxonomy import PostAreaId, PostCategoryId


@dataclass(frozen=True)
class EvalQueryCase:
    id: str
    category: str
    request: SearchRequest
    expected_ids: List[str]
    forbidden_ids: List[str]


def seed_evaluation_corpus(db: Any, *, now: Optional[datetime] = None) -> None:
    """Nạp tập bài đăng mẫu thực tế LostLink (gồm OPEN, RESOLVED, HIDDEN, CLOSED, tombstone, DELETING)."""
    base = now or datetime(2026, 11, 20, 10, 0, 0, tzinfo=timezone.utc)
    posts = [
        ("post-wallet", {"title": "Mất ví da nâu có giấy tờ", "description": "Rơi ví da nâu gần căn tin cơ sở 1", "type": "LOST", "status": "OPEN", "moderationStatus": "VISIBLE", "categoryId": "wallet", "areaId": "canteen", "location": "Căn tin CS1", "authorId": "u-1", "createdAt": base - timedelta(hours=2)}),
        ("post-card", {"title": "Nhặt được thẻ sinh viên UTH", "description": "Thẻ tên Nguyễn Minh Anh tại thư viện tầng 2", "type": "FOUND", "status": "OPEN", "moderationStatus": "VISIBLE", "categoryId": "student_card", "areaId": "library", "location": "Thư viện tầng 2", "authorId": "u-2", "createdAt": base - timedelta(hours=3)}),
        ("post-laptop", {"title": "Quên máy tính xách tay Dell màu bạc", "description": "Để quên laptop trong ba lô ở phòng B204", "type": "LOST", "status": "OPEN", "moderationStatus": "VISIBLE", "categoryId": "electronics", "areaId": "b", "location": "Phòng B204 Tòa B", "authorId": "u-3", "createdAt": base - timedelta(hours=5)}),
        ("post-keys", {"title": "Rơi chùm chìa khóa xe máy Honda", "description": "Chìa khóa có móc hình gấu ở bãi giữ xe", "type": "LOST", "status": "OPEN", "moderationStatus": "VISIBLE", "categoryId": "keys", "areaId": "parking", "location": "Bãi giữ xe khu A", "authorId": "u-4", "createdAt": base - timedelta(hours=6)}),
        ("post-bag-resolved", {"title": "Tìm thấy balo đen tại hội trường", "description": "Đã trả lại cho bạn sinh viên", "type": "FOUND", "status": "RESOLVED", "moderationStatus": "VISIBLE", "categoryId": "bag", "areaId": "auditorium", "location": "Hội trường lớn", "authorId": "u-5", "createdAt": base - timedelta(days=1)}),
        # Các bài bị chặn hiển thị theo chính sách
        ("post-hidden", {"title": "Mất ví da nâu giả mạo", "description": "Bài vi phạm đã bị ẩn bởi kiểm duyệt", "type": "LOST", "status": "OPEN", "moderationStatus": "HIDDEN", "categoryId": "wallet", "areaId": "canteen", "location": "Căn tin", "authorId": "u-1", "createdAt": base - timedelta(hours=1)}),
        ("post-closed", {"title": "Mất ví da nâu đã đóng tin", "description": "Tin đã tạm đóng bởi tác giả", "type": "LOST", "status": "CLOSED", "moderationStatus": "VISIBLE", "categoryId": "wallet", "areaId": "canteen", "location": "Căn tin", "authorId": "u-1", "createdAt": base - timedelta(hours=1)}),
        ("post-deleting", {"title": "Mất ví da nâu tác giả đang xóa", "description": "Tác giả đang chờ xóa tài khoản", "type": "LOST", "status": "OPEN", "moderationStatus": "VISIBLE", "categoryId": "wallet", "areaId": "canteen", "location": "Căn tin", "authorId": "u-del", "createdAt": base - timedelta(hours=1)}),
        ("post-tombstone", {"title": "Mất ví da nâu đã có tombstone", "description": "Bài đã xóa có tombstone", "type": "LOST", "status": "OPEN", "moderationStatus": "VISIBLE", "categoryId": "wallet", "areaId": "canteen", "location": "Căn tin", "authorId": "u-1", "createdAt": base - timedelta(hours=1)}),
    ]
    for pid, data in posts:
        db.collection("posts").document(pid).set(data)
    db.collection("account_states").document("u-del").set({"status": "DELETING", "updatedAt": base})
    db.collection("post_tombstones").document("post-tombstone").set({"authorId": "u-1", "deletedAt": base})


def build_eval_queries() -> List[EvalQueryCase]:
    restricted = ["post-hidden", "post-closed", "post-deleting", "post-tombstone"]
    return [
        EvalQueryCase("short_keyword", "từ khóa ngắn", SearchRequest(query="ví da"), ["post-wallet"], restricted),
        EvalQueryCase("synonym_semantic", "mô tả đồng nghĩa", SearchRequest(query="máy tính xách tay"), ["post-laptop"], restricted),
        EvalQueryCase("campus_location", "địa danh trong trường", SearchRequest(query="thư viện"), ["post-card"], restricted),
        EvalQueryCase(
            "filtered_combo",
            "kết hợp bộ lọc",
            SearchRequest(query="balo", type=PostType.FOUND, categoryIds=[PostCategoryId.BAG], areaIds=[PostAreaId.AUDITORIUM], timeFilter=SearchTimeFilter.THREE_DAYS),
            ["post-bag-resolved"],
            restricted,
        ),
        EvalQueryCase("no_match", "truy vấn không khớp", SearchRequest(query="máy ảnh sony alpha"), [], restricted),
    ]


def evaluate_top_k(search_fn: Callable[[SearchRequest], SearchResponse], *, k: int = 3) -> Dict[str, Any]:
    """Đo HitRate@K, MRR@K, tỷ lệ lọc truy vấn rỗng, độ trễ TB và kiểm tra rò rỉ dữ liệu cấm."""
    cases = build_eval_queries()
    positive_hits = 0
    positive_total = 0
    mrr_sum = 0.0
    unmatched_ok = 0
    unmatched_total = 0
    leaked = 0
    latencies: List[float] = []

    for case in cases:
        t0 = time.perf_counter()
        resp = search_fn(case.request)
        latencies.append((time.perf_counter() - t0) * 1000.0)
        top_ids = [item.post_id for item in resp.items[:k]]
        if any(pid in set(case.forbidden_ids) for pid in [i.post_id for i in resp.items]):
            leaked += 1
        if not case.expected_ids:
            unmatched_total += 1
            if not resp.items:
                unmatched_ok += 1
        else:
            positive_total += 1
            expected = set(case.expected_ids)
            if any(pid in expected for pid in top_ids):
                positive_hits += 1
            for rank, pid in enumerate(top_ids, start=1):
                if pid in expected:
                    mrr_sum += 1.0 / rank
                    break

    return {
        "totalCases": len(cases),
        "hitRateAtK": round(positive_hits / positive_total, 4) if positive_total else 1.0,
        "mrrAtK": round(mrr_sum / positive_total, 4) if positive_total else 1.0,
        "unmatchedAccuracy": round(unmatched_ok / unmatched_total, 4) if unmatched_total else 1.0,
        "avgLatencyMs": round(sum(latencies) / len(latencies), 2) if latencies else 0.0,
        "leakedRestrictedCount": leaked,
    }
