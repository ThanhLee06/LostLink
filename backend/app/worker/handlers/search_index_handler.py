"""Handler xử lý searchJob từ post_events để đồng bộ chỉ mục vector."""
from __future__ import annotations

import asyncio
from datetime import datetime, timezone
from typing import Any, Dict, Optional, Tuple
from google.cloud import firestore as _fs
from google.cloud.firestore import Client as FirestoreClient

from app.core.auth import AccountStateStatus, parse_account_state_snapshot
from app.core.config import Settings, get_settings
from app.services.embedding_service import embed_post
from app.services.vector_index_service import build_embedding_doc, is_post_indexable
from app.worker.claim import assert_lease_valid, complete_job


def _mark_job_updates(status: str) -> Dict[str, Any]:
    return {
        "searchJob.status": status,
        "searchJob.claimToken": None,
        "searchJob.lockOwner": None,
        "searchJob.lockExpiresAt": None,
        "searchJob.nextRetryAt": None,
        "searchJob.lastError": None,
    }


def _load_post_state_sync(
    db: FirestoreClient, post_id: str
) -> Tuple[Optional[Dict[str, Any]], bool, bool, Optional[Dict[str, Any]]]:
    post_snap = db.collection("posts").document(post_id).get()
    tomb_snap = db.collection("post_tombstones").document(post_id).get()
    emb_snap = db.collection("post_embeddings").document(post_id).get()
    post_data = post_snap.to_dict() if getattr(post_snap, "exists", False) else None
    has_tombstone = bool(getattr(tomb_snap, "exists", False))
    emb_data = emb_snap.to_dict() if getattr(emb_snap, "exists", False) else None
    author_active = False
    if isinstance(post_data, dict):
        author_id = str(post_data.get("authorId") or "").strip()
        if author_id:
            acc_snap = db.collection("account_states").document(author_id).get()
            author_active = parse_account_state_snapshot(acc_snap, uid=author_id) == AccountStateStatus.ACTIVE
    return post_data, has_tombstone, author_active, emb_data


def _commit_delete_and_complete_sync(
    db: FirestoreClient,
    *,
    event_id: str,
    post_id: str,
    claim_token: str,
    event_version: int,
) -> str:
    event_ref = db.collection("post_events").document(event_id)
    post_ref = db.collection("posts").document(post_id)
    tomb_ref = db.collection("post_tombstones").document(post_id)
    emb_ref = db.collection("post_embeddings").document(post_id)

    @_fs.transactional
    def _txn(transaction: _fs.Transaction) -> str:
        ev_snap = event_ref.get(transaction=transaction)
        if not ev_snap.exists:
            return "SKIPPED"
        assert_lease_valid((ev_snap.to_dict() or {}).get("searchJob") or {}, claim_token, now=datetime.now(tz=timezone.utc), event_id=event_id, job_name="searchJob")
        post_snap = post_ref.get(transaction=transaction)
        tomb_snap = tomb_ref.get(transaction=transaction)
        emb_snap = emb_ref.get(transaction=transaction)
        fresh_post = post_snap.to_dict() if getattr(post_snap, "exists", False) else None
        has_tomb = bool(getattr(tomb_snap, "exists", False))
        emb_ver = int((emb_snap.to_dict() or {}).get("postVersion") or 0) if getattr(emb_snap, "exists", False) else 0
        if fresh_post is not None and not has_tomb:
            fresh_ver = int(fresh_post.get("version") or 1)
            author_id = str(fresh_post.get("authorId") or "").strip()
            author_active = False
            if author_id:
                acc_snap = db.collection("account_states").document(author_id).get(transaction=transaction)
                author_active = parse_account_state_snapshot(acc_snap, uid=author_id) == AccountStateStatus.ACTIVE
            # Không xóa nếu bài đã lên version mới hơn hoặc đang mở hợp lệ trở lại
            if fresh_ver > event_version or is_post_indexable(fresh_post, author_active=author_active, has_tombstone=False) or emb_ver > event_version:
                transaction.update(event_ref, _mark_job_updates("SKIPPED"))
                return "SKIPPED"
        if getattr(emb_snap, "exists", False):
            transaction.delete(emb_ref)
        transaction.update(event_ref, _mark_job_updates("DONE"))
        return "DONE"

    return _txn(db.transaction())


def _commit_upsert_and_complete_sync(
    db: FirestoreClient,
    *,
    event_id: str,
    post_id: str,
    claim_token: str,
    target_version: int,
    vector: list[float],
    settings: Settings,
) -> str:
    event_ref = db.collection("post_events").document(event_id)
    post_ref = db.collection("posts").document(post_id)
    tomb_ref = db.collection("post_tombstones").document(post_id)
    emb_ref = db.collection("post_embeddings").document(post_id)

    @_fs.transactional
    def _txn(transaction: _fs.Transaction) -> str:
        ev_snap = event_ref.get(transaction=transaction)
        if not ev_snap.exists:
            return "SKIPPED"
        assert_lease_valid((ev_snap.to_dict() or {}).get("searchJob") or {}, claim_token, now=datetime.now(tz=timezone.utc), event_id=event_id, job_name="searchJob")
        post_snap = post_ref.get(transaction=transaction)
        tomb_snap = tomb_ref.get(transaction=transaction)
        emb_snap = emb_ref.get(transaction=transaction)
        fresh_post = post_snap.to_dict() if getattr(post_snap, "exists", False) else None
        has_tomb = bool(getattr(tomb_snap, "exists", False))
        author_active = False
        if isinstance(fresh_post, dict):
            author_id = str(fresh_post.get("authorId") or "").strip()
            if author_id:
                acc_snap = db.collection("account_states").document(author_id).get(transaction=transaction)
                author_active = parse_account_state_snapshot(acc_snap, uid=author_id) == AccountStateStatus.ACTIVE
        if not is_post_indexable(fresh_post, author_active=author_active, has_tombstone=has_tomb):
            if getattr(emb_snap, "exists", False):
                transaction.delete(emb_ref)
            transaction.update(event_ref, _mark_job_updates("DONE"))
            return "DONE"
        fresh_version = int(fresh_post.get("version") or 1)
        emb_ver = int((emb_snap.to_dict() or {}).get("postVersion") or 0) if getattr(emb_snap, "exists", False) else 0
        # Chống ghi đè nếu bài đã tăng version trong lúc chờ gọi Gemini
        if fresh_version > target_version or emb_ver > target_version:
            transaction.update(event_ref, _mark_job_updates("SKIPPED"))
            return "SKIPPED"
        transaction.set(emb_ref, build_embedding_doc(post_id, target_version, fresh_post, vector, settings))
        transaction.update(event_ref, _mark_job_updates("DONE"))
        return "DONE"

    return _txn(db.transaction())


async def handle_search_job(
    db: FirestoreClient,
    event_id: str,
    event_data: Dict[str, Any],
    claim_token: str,
) -> str:
    """Đồng bộ chỉ mục vector post_embeddings/{postId} theo sự kiện post_events."""
    post_id = str(event_data.get("postId") or "").strip()
    event_version = int(event_data.get("postVersion") or 0)
    change_type = str(event_data.get("changeType") or "")
    if not post_id or event_version < 1:
        await asyncio.to_thread(complete_job, db, event_id, "searchJob", claim_token, "SKIPPED")
        return "SKIPPED"
    post_data, has_tombstone, author_active, emb_data = await asyncio.to_thread(_load_post_state_sync, db, post_id)
    if change_type == "DELETE" or has_tombstone or post_data is None:
        return await asyncio.to_thread(
            _commit_delete_and_complete_sync,
            db,
            event_id=event_id,
            post_id=post_id,
            claim_token=claim_token,
            event_version=event_version,
        )
    current_version = int(post_data.get("version") or 1)
    if event_version < current_version:
        await asyncio.to_thread(complete_job, db, event_id, "searchJob", claim_token, "SKIPPED")
        return "SKIPPED"
    if not is_post_indexable(post_data, author_active=author_active, has_tombstone=False):
        return await asyncio.to_thread(
            _commit_delete_and_complete_sync,
            db,
            event_id=event_id,
            post_id=post_id,
            claim_token=claim_token,
            event_version=current_version,
        )
    settings = get_settings()
    if (
        change_type == "BACKFILL"
        and isinstance(emb_data, dict)
        and int(emb_data.get("postVersion") or 0) == current_version
        and int(emb_data.get("indexVersion") or 0) == settings.embedding_index_version
        and emb_data.get("embeddingModel") == settings.embedding_model
        and emb_data.get("embeddingModelRevision") == settings.embedding_model_revision
    ):
        await asyncio.to_thread(complete_job, db, event_id, "searchJob", claim_token, "SKIPPED")
        return "SKIPPED"
    vector = await embed_post(post_data, settings=settings)
    return await asyncio.to_thread(
        _commit_upsert_and_complete_sync,
        db,
        event_id=event_id,
        post_id=post_id,
        claim_token=claim_token,
        target_version=current_version,
        vector=vector,
        settings=settings,
    )
