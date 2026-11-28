"""Quản lý lưu trữ, truy vấn vector và dọn vector mồ côi trên collection post_embeddings."""
from __future__ import annotations

from datetime import datetime, timezone
from typing import Any, Dict, List, Optional
from google.cloud import firestore as _fs
from google.cloud.firestore import Client as FirestoreClient
from google.cloud.firestore_v1 import FieldFilter
from google.cloud.firestore_v1.base_vector_query import DistanceMeasure
from google.cloud.firestore_v1.vector import Vector

from app.core.auth import AccountStateStatus, parse_account_state_snapshot
from app.core.config import Settings, get_settings


def _to_utc(value: Any) -> Optional[datetime]:
    if not isinstance(value, datetime):
        return None
    return value.replace(tzinfo=timezone.utc) if value.tzinfo is None else value.astimezone(timezone.utc)


def is_post_indexable(
    post_data: Optional[Dict[str, Any]],
    *,
    author_active: bool = True,
    has_tombstone: bool = False,
) -> bool:
    """Chỉ lập chỉ mục bài đang OPEN, VISIBLE, chưa xóa và tác giả còn ACTIVE."""
    if not isinstance(post_data, dict) or has_tombstone or not author_active:
        return False
    return post_data.get("moderationStatus") == "VISIBLE" and post_data.get("status") == "OPEN"


def build_embedding_doc(
    post_id: str,
    post_version: int,
    post_data: Dict[str, Any],
    vector: List[float],
    settings: Settings,
) -> Dict[str, Any]:
    """Tạo document lưu chỉ mục vector, tuyệt đối không chứa thông tin PII."""
    raw_area = post_data.get("areaId")
    return {
        "postId": post_id,
        "postVersion": post_version,
        "indexVersion": settings.embedding_index_version,
        "embeddingModel": settings.embedding_model,
        "embeddingModelRevision": settings.embedding_model_revision,
        "type": str(post_data.get("type") or ""),
        "status": "OPEN",
        "moderationStatus": "VISIBLE",
        "categoryId": str(post_data.get("categoryId") or ""),
        "areaId": raw_area if isinstance(raw_area, str) else "",
        "postCreatedAt": _to_utc(post_data.get("createdAt")),
        "vector": Vector(vector),
        "updatedAt": _fs.SERVER_TIMESTAMP,
    }


def upsert_post_embedding(
    db: FirestoreClient,
    *,
    post_id: str,
    post_version: int,
    post_data: Dict[str, Any],
    vector: List[float],
    settings: Optional[Settings] = None,
) -> bool:
    """Lưu hoặc cập nhật vector trong transaction; bỏ qua nếu đã có version mới hơn."""
    cfg = settings or get_settings()
    ref = db.collection("post_embeddings").document(post_id)

    @_fs.transactional
    def _txn(transaction: _fs.Transaction) -> bool:
        snap = ref.get(transaction=transaction)
        if snap.exists:
            existing_ver = int((snap.to_dict() or {}).get("postVersion") or 0)
            if existing_ver > post_version:
                return False
        transaction.set(ref, build_embedding_doc(post_id, post_version, post_data, vector, cfg))
        return True

    return _txn(db.transaction())


def delete_post_embedding(
    db: FirestoreClient,
    *,
    post_id: str,
    min_version: Optional[int] = None,
) -> bool:
    """Xóa vector trong transaction; tôn trọng min_version để tránh xóa nhầm bản mới hơn."""
    ref = db.collection("post_embeddings").document(post_id)

    @_fs.transactional
    def _txn(transaction: _fs.Transaction) -> bool:
        snap = ref.get(transaction=transaction)
        if not snap.exists:
            return False
        if min_version is not None:
            existing_ver = int((snap.to_dict() or {}).get("postVersion") or 0)
            if existing_ver > min_version:
                return False
        transaction.delete(ref)
        return True

    return _txn(db.transaction())


def query_similar_posts(
    db: FirestoreClient,
    *,
    query_vector: List[float],
    limit: int = 20,
    post_type: Optional[str] = None,
    category_ids: Optional[List[str]] = None,
    area_ids: Optional[List[str]] = None,
    min_created_at: Optional[datetime] = None,
    max_created_at: Optional[datetime] = None,
) -> List[Dict[str, Any]]:
    """Truy vấn gần đúng theo cosine trên các bài OPEN/VISIBLE kèm hậu lọc metadata."""
    if limit <= 0:
        return []
    candidate_limit = min(max(limit * 4, 40), 200)
    base_query = (
        db.collection("post_embeddings")
        .where(filter=FieldFilter("moderationStatus", "==", "VISIBLE"))
        .where(filter=FieldFilter("status", "==", "OPEN"))
    )
    vector_query = base_query.find_nearest(
        vector_field="vector",
        query_vector=Vector(query_vector),
        distance_measure=DistanceMeasure.COSINE,
        limit=candidate_limit,
        distance_result_field="vectorDistance",
    )
    min_utc = _to_utc(min_created_at)
    max_utc = _to_utc(max_created_at)
    cat_set = set(category_ids) if category_ids else None
    area_set = set(area_ids) if area_ids else None
    results: List[Dict[str, Any]] = []
    for snap in vector_query.stream():
        data = snap.to_dict() or {}
        if data.get("moderationStatus") != "VISIBLE" or data.get("status") != "OPEN":
            continue
        if post_type and data.get("type") != post_type:
            continue
        if cat_set is not None and data.get("categoryId") not in cat_set:
            continue
        if area_set is not None and data.get("areaId") not in area_set:
            continue
        created_dt = _to_utc(data.get("postCreatedAt"))
        if (min_utc or max_utc) and created_dt is None:
            continue
        if min_utc and created_dt and created_dt < min_utc:
            continue
        if max_utc and created_dt and created_dt > max_utc:
            continue
        raw_dist = data.get("vectorDistance")
        dist = float(raw_dist) if isinstance(raw_dist, (int, float)) else 1.0
        results.append(
            {
                "postId": str(data.get("postId") or snap.id),
                "score": round(max(0.0, min(1.0, 1.0 - dist)), 4),
                "type": data.get("type"),
                "status": data.get("status"),
                "categoryId": data.get("categoryId"),
                "areaId": data.get("areaId") or "",
                "postCreatedAt": created_dt,
            }
        )
        if len(results) >= limit:
            break
    return results


def cleanup_orphan_embeddings(db: FirestoreClient, *, batch_limit: Optional[int] = None) -> int:
    """Quét và xóa các vector mồ côi (bài đã xóa, ẩn, đóng, hoặc tác giả ngừng hoạt động)."""
    query: Any = db.collection("post_embeddings")
    if batch_limit is not None and batch_limit > 0:
        query = query.limit(batch_limit)
    deleted = 0
    for emb_snap in query.stream():
        post_id = emb_snap.id
        post_snap = db.collection("posts").document(post_id).get()
        tomb_snap = db.collection("post_tombstones").document(post_id).get()
        post_data = post_snap.to_dict() if getattr(post_snap, "exists", False) else None
        author_active = False
        if isinstance(post_data, dict):
            author_id = str(post_data.get("authorId") or "").strip()
            if author_id:
                acc_snap = db.collection("account_states").document(author_id).get()
                author_active = parse_account_state_snapshot(acc_snap, uid=author_id) == AccountStateStatus.ACTIVE
        if not is_post_indexable(post_data, author_active=author_active, has_tombstone=bool(getattr(tomb_snap, "exists", False))):
            emb_ver = int((emb_snap.to_dict() or {}).get("postVersion") or 0)
            if delete_post_embedding(db, post_id=post_id, min_version=emb_ver if emb_ver > 0 else None):
                deleted += 1
    return deleted
