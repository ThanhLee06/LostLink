"""
app/services/search_service.py — Bounded read-only search operation and cursor helpers.

Implements PLAN.md §4, §5, §7 and S2 remediation:
- Pure text normalization, term matching, and Vietnamese boundary time filter.
- Lossless versioned base64url JSON cursor with exact anchor {s, ns} and post timestamp.
- Strict validation against hostile cursor inputs (rejects floats, bools, huge ints, invalid IDs).
- Two-stage batch re-read: fresh posts first, then exact author UIDs + tombstones.
- Omitted batch response fails 503; explicit missing account doc is ACTIVE legacy.
- Corrupt row ValidationError is isolated without breaking healthy rows.
- Rechecks caller active state before returning.
"""
from __future__ import annotations

import asyncio
import base64
import binascii
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta, timezone
import hashlib
import json
import re
import time
from typing import Any, Dict, List, Optional, Set, Tuple
import unicodedata

from google.api_core.datetime_helpers import DatetimeWithNanoseconds
from google.api_core.exceptions import DeadlineExceeded, GoogleAPICallError
from google.cloud.firestore_v1 import FieldFilter, Query
from google.protobuf.timestamp_pb2 import Timestamp as PbTimestamp
from pydantic import ValidationError

from app.core.auth import AccountStateStatus, parse_account_state_snapshot
from app.core.config import get_settings
from app.core.errors import ApiHTTPException
from app.schemas.post import PostStatus, PostType
from app.schemas.search import (
    SearchItem,
    SearchRequest,
    SearchResponse,
    SearchTimeFilter,
)
from app.schemas.taxonomy import AREA_LABELS, CATEGORY_LABELS, normalize_area_id
from app.services.embedding_service import embed_query, expand_search_term_groups
from app.services.vector_index_service import query_similar_posts

VN_TZ = timezone(timedelta(hours=7))
MAX_TIMESTAMP_SECONDS = 253402300799


def normalize_text(text: str) -> str:
    if not text:
        return ""
    text = text.replace("đ", "d").replace("Đ", "D")
    decomposed = unicodedata.normalize("NFD", text)
    stripped = "".join(c for c in decomposed if unicodedata.category(c) != "Mn")
    return stripped.lower()


def normalize_terms(query: str) -> List[str]:
    norm = normalize_text(query)
    words = re.findall(r"\w+", norm, re.UNICODE)
    seen: Set[str] = set()
    terms: List[str] = []
    for w in words:
        if w not in seen:
            seen.add(w)
            terms.append(w)
    return terms


def _is_valid_doc_id(doc_id: Any) -> bool:
    return isinstance(doc_id, str) and bool(doc_id) and "/" not in doc_id and doc_id not in {".", ".."}


def _has_phrase(haystack: str, needle: str) -> bool:
    return bool(needle) and f" {needle} " in f" {haystack} "


def matches_and_score(data: Dict[str, Any], terms: List[str]) -> Tuple[bool, float]:
    if not terms:
        return True, 0.0

    title_val = data.get("title")
    title_norm = normalize_text(str(title_val)) if isinstance(title_val, str) else ""
    loc_val = data.get("location")
    loc_norm = normalize_text(str(loc_val)) if isinstance(loc_val, str) else ""
    desc_val = data.get("description")
    desc_norm = normalize_text(str(desc_val)) if isinstance(desc_val, str) else ""

    cat_id = data.get("categoryId")
    area_id = data.get("areaId")
    cat_label = normalize_text(CATEGORY_LABELS.get(cat_id, "")) if isinstance(cat_id, str) and cat_id in CATEGORY_LABELS else ""
    area_label = normalize_text(AREA_LABELS.get(area_id, "")) if isinstance(area_id, str) and area_id in AREA_LABELS else ""
    taxonomy_loc = f"{loc_norm} {cat_label} {area_label} {cat_id or ''} {area_id or ''}"

    groups = expand_search_term_groups(terms)
    total_weight = 0.0
    for primary, equivalents in groups:
        if primary in title_norm:
            weight = 3.0
        elif primary in taxonomy_loc:
            weight = 2.0
        elif primary in desc_norm:
            weight = 1.0
        elif any(_has_phrase(title_norm, eq) for eq in equivalents):
            weight = 2.7
        elif any(_has_phrase(taxonomy_loc, eq) for eq in equivalents):
            weight = 1.8
        elif any(_has_phrase(desc_norm, eq) for eq in equivalents):
            weight = 0.9
        else:
            return False, 0.0
        total_weight += weight

    score = total_weight / (3.0 * len(groups))
    return True, round(min(1.0, max(0.0, score)), 4)


def get_time_bounds(filter_type: SearchTimeFilter, anchor_utc: datetime) -> Tuple[datetime, Optional[datetime]]:
    anchor_vn = anchor_utc.astimezone(VN_TZ)
    today_vn = datetime(anchor_vn.year, anchor_vn.month, anchor_vn.day, tzinfo=VN_TZ)

    if filter_type == SearchTimeFilter.ALL:
        cutoff = None
    elif filter_type == SearchTimeFilter.TODAY:
        cutoff = today_vn.astimezone(timezone.utc)
    elif filter_type == SearchTimeFilter.THREE_DAYS:
        cutoff = (today_vn - timedelta(days=2)).astimezone(timezone.utc)
    elif filter_type == SearchTimeFilter.THIS_WEEK:
        cutoff = (today_vn - timedelta(days=anchor_vn.weekday())).astimezone(timezone.utc)
    elif filter_type == SearchTimeFilter.THIS_MONTH:
        cutoff = datetime(anchor_vn.year, anchor_vn.month, 1, tzinfo=VN_TZ).astimezone(timezone.utc)
    else:
        cutoff = None

    return anchor_utc, cutoff


def compute_fingerprint(caller_uid: str, request: SearchRequest) -> str:
    norm_query = " ".join(normalize_terms(request.query))
    sorted_cats = ",".join(sorted(c.value for c in request.category_ids))
    sorted_areas = ",".join(sorted(a.value for a in request.area_ids))
    type_str = request.type.value if request.type else ""
    payload = f"{caller_uid}|{norm_query}|{type_str}|{sorted_cats}|{sorted_areas}|{request.time_filter.value}"
    return hashlib.sha256(payload.encode("utf-8")).hexdigest()


def _extract_s_ns(dt: Any) -> Tuple[int, int]:
    if hasattr(dt, "timestamp_pb"):
        pb = dt.timestamp_pb()
        return pb.seconds, pb.nanos
    aware = dt.astimezone(timezone.utc) if getattr(dt, "tzinfo", None) else dt.replace(tzinfo=timezone.utc)
    epoch = datetime(1970, 1, 1, tzinfo=timezone.utc)
    s = int((aware - epoch).total_seconds())
    ns = getattr(aware, "nanosecond", aware.microsecond * 1000)
    return s, ns


def _rebuild_dt(s: int, ns: int) -> DatetimeWithNanoseconds:
    pb = PbTimestamp(seconds=s, nanos=ns)
    return DatetimeWithNanoseconds.from_timestamp_pb(pb)


def encode_cursor(fingerprint: str, anchor_utc: datetime, post_id: str, created_at: Any) -> str:
    a_s, a_ns = _extract_s_ns(anchor_utc)
    p_s, p_ns = _extract_s_ns(created_at)
    data = {
        "v": 1,
        "fp": fingerprint,
        "a": {"s": a_s, "ns": a_ns},
        "pid": post_id,
        "s": p_s,
        "ns": p_ns,
    }
    raw = json.dumps(data, separators=(",", ":")).encode("utf-8")
    return base64.urlsafe_b64encode(raw).decode("ascii")


def decode_cursor(cursor_str: str, expected_fp: str, now: datetime) -> Tuple[DatetimeWithNanoseconds, str, DatetimeWithNanoseconds]:
    if not cursor_str or not isinstance(cursor_str, str) or len(cursor_str) > 2048:
        raise ApiHTTPException(status_code=400, detail="Invalid cursor token.", code="INVALID_CURSOR")
    try:
        padded = cursor_str + "=" * (-len(cursor_str) % 4)
        raw = base64.urlsafe_b64decode(padded.encode("ascii"))
        data = json.loads(raw.decode("utf-8"))
    except (ValueError, binascii.Error, UnicodeDecodeError, json.JSONDecodeError):
        raise ApiHTTPException(status_code=400, detail="Malformed cursor token.", code="INVALID_CURSOR")

    if not isinstance(data, dict) or set(data.keys()) != {"v", "fp", "a", "pid", "s", "ns"}:
        raise ApiHTTPException(status_code=400, detail="Invalid cursor structure.", code="INVALID_CURSOR")

    v, fp, a, pid, s, ns = data["v"], data["fp"], data["a"], data["pid"], data["s"], data["ns"]
    if type(v) is not int or v != 1 or type(fp) is not str or type(pid) is not str:
        raise ApiHTTPException(status_code=400, detail="Invalid cursor fields.", code="INVALID_CURSOR")
    if type(s) is not int or type(ns) is not int:
        raise ApiHTTPException(status_code=400, detail="Invalid cursor timestamp types.", code="INVALID_CURSOR")
    if not (0 <= s <= MAX_TIMESTAMP_SECONDS) or not (0 <= ns < 1_000_000_000):
        raise ApiHTTPException(status_code=400, detail="Cursor timestamp out of range.", code="INVALID_CURSOR")

    if not isinstance(a, dict) or set(a.keys()) != {"s", "ns"}:
        raise ApiHTTPException(status_code=400, detail="Invalid cursor anchor structure.", code="INVALID_CURSOR")
    a_s, a_ns = a["s"], a["ns"]
    if type(a_s) is not int or type(a_ns) is not int:
        raise ApiHTTPException(status_code=400, detail="Invalid cursor anchor types.", code="INVALID_CURSOR")
    if not (0 <= a_s <= MAX_TIMESTAMP_SECONDS) or not (0 <= a_ns < 1_000_000_000):
        raise ApiHTTPException(status_code=400, detail="Cursor anchor out of range.", code="INVALID_CURSOR")

    if fp != expected_fp:
        raise ApiHTTPException(status_code=400, detail="Cursor mismatch.", code="CURSOR_MISMATCH")
    if not pid or "/" in pid or pid in {".", ".."}:
        raise ApiHTTPException(status_code=400, detail="Invalid document ID in cursor.", code="INVALID_CURSOR")

    now_s, now_ns = _extract_s_ns(now)
    if a_s > now_s + 60 or (a_s == now_s + 60 and a_ns > now_ns):
        raise ApiHTTPException(status_code=400, detail="Cursor timestamp in future.", code="EXPIRED_CURSOR")
    diff_s = now_s - a_s
    diff_ns = now_ns - a_ns
    if diff_s > 1800 or (diff_s == 1800 and diff_ns > 0):
        raise ApiHTTPException(status_code=400, detail="Cursor has expired (>30m).", code="EXPIRED_CURSOR")

    return _rebuild_dt(a_s, a_ns), pid, _rebuild_dt(s, ns)


def _batch_get(db: Any, refs: List[Any], timeout: float) -> Dict[str, Any]:
    if not refs:
        return {}
    unique_refs: List[Any] = []
    seen: Set[str] = set()
    for ref in refs:
        if ref.path not in seen:
            seen.add(ref.path)
            unique_refs.append(ref)

    try:
        snaps = list(db.get_all(unique_refs, timeout=timeout, retry=None))
    except (TimeoutError, DeadlineExceeded):
        raise ApiHTTPException(status_code=504, detail="Search request timed out.", code="DEADLINE_EXCEEDED")
    except ApiHTTPException:
        raise
    except (GoogleAPICallError, Exception):
        raise ApiHTTPException(status_code=503, detail="Datastore error.", code="DATASTORE_ERROR")

    result = {snap.reference.path: snap for snap in snaps}
    for ref in unique_refs:
        if ref.path not in result:
            raise ApiHTTPException(status_code=503, detail="Incomplete datastore batch response.", code="DATASTORE_ERROR")
    return result


def search_posts(db: Any, request: SearchRequest, caller_uid: str, now: Optional[datetime] = None) -> SearchResponse:
    if request.cursor is not None and not request.cursor.strip():
        raise ApiHTTPException(status_code=400, detail="Empty cursor token is invalid.", code="INVALID_CURSOR")

    start_time = time.monotonic()
    total_timeout = 10.0

    def remaining_time() -> float:
        rem = total_timeout - (time.monotonic() - start_time)
        if rem <= 0:
            raise ApiHTTPException(status_code=504, detail="Search request timed out.", code="DEADLINE_EXCEEDED")
        return min(5.0, rem)

    now_utc = now or datetime.now(tz=timezone.utc)
    now_utc = now_utc.astimezone(timezone.utc) if now_utc.tzinfo else now_utc.replace(tzinfo=timezone.utc)
    fingerprint = compute_fingerprint(caller_uid, request)

    if request.cursor:
        anchor_dt, cursor_pid, cursor_dt = decode_cursor(request.cursor, fingerprint, now_utc)
        has_cursor = True
    else:
        anchor_dt, cursor_pid, cursor_dt = now_utc, None, None
        has_cursor = False

    _, lower_cutoff_utc = get_time_bounds(request.time_filter, anchor_dt)
    raw_limit = min(100, max(40, request.page_size * 2))
    read_limit = raw_limit + 1

    try:
        posts_col = db.collection("posts")
        query = (
            posts_col
            .where(filter=FieldFilter("moderationStatus", "==", "VISIBLE"))
            .where(filter=FieldFilter("status", "in", ["OPEN", "RESOLVED"]))
        )
        if request.type:
            query = query.where(filter=FieldFilter("type", "==", request.type.value))
        query = query.where(filter=FieldFilter("createdAt", "<=", anchor_dt))
        if lower_cutoff_utc:
            query = query.where(filter=FieldFilter("createdAt", ">=", lower_cutoff_utc))
        query = query.order_by("createdAt", direction=Query.DESCENDING).order_by("__name__", direction=Query.DESCENDING)

        if has_cursor:
            query = query.start_after({"createdAt": cursor_dt, "__name__": posts_col.document(cursor_pid)})
        query = query.limit(read_limit)

        t = remaining_time()
        raw_docs = list(query.stream(timeout=t, retry=None))
    except (TimeoutError, DeadlineExceeded):
        raise ApiHTTPException(status_code=504, detail="Search request timed out.", code="DEADLINE_EXCEEDED")
    except ApiHTTPException:
        raise
    except (GoogleAPICallError, Exception):
        raise ApiHTTPException(status_code=503, detail="Datastore error.", code="DATASTORE_ERROR")

    if len(raw_docs) > raw_limit:
        raw_candidates, has_lookahead = raw_docs[:raw_limit], True
    else:
        raw_candidates, has_lookahead = raw_docs, False

    # Stage 1: Batch re-read fresh posts
    post_refs = [doc.reference for doc in raw_candidates]
    fresh_posts_map = _batch_get(db, post_refs, remaining_time())

    # Stage 2: Collect exact author UIDs from fresh posts + tombstones
    auth_refs: List[Any] = []
    tomb_refs: List[Any] = []
    for cand in raw_candidates:
        tomb_refs.append(db.collection("post_tombstones").document(cand.id))
        p_snap = fresh_posts_map.get(cand.reference.path)
        if p_snap and getattr(p_snap, "exists", False):
            f_data = p_snap.to_dict()
            if isinstance(f_data, dict):
                raw_aid = f_data.get("authorId")
                if _is_valid_doc_id(raw_aid):
                    auth_refs.append(db.collection("account_states").document(raw_aid))

    state_tomb_map = _batch_get(db, auth_refs + tomb_refs, remaining_time())
    query_terms = normalize_terms(request.query)
    matched_items: List[SearchItem] = []
    last_consumed_doc: Optional[Any] = None

    for cand in raw_candidates:
        last_consumed_doc = cand
        post_snap = fresh_posts_map.get(cand.reference.path)
        if not post_snap or not getattr(post_snap, "exists", False):
            continue
        tomb_snap = state_tomb_map.get(f"post_tombstones/{cand.id}")
        if tomb_snap and getattr(tomb_snap, "exists", False):
            continue

        fresh_data = post_snap.to_dict()
        if not isinstance(fresh_data, dict):
            continue
        author_id = fresh_data.get("authorId")
        if not _is_valid_doc_id(author_id):
            continue

        author_snap = state_tomb_map.get(f"account_states/{author_id}")
        if not author_snap:
            continue
        if parse_account_state_snapshot(author_snap, uid=author_id) != AccountStateStatus.ACTIVE:
            continue

        if fresh_data.get("moderationStatus") != "VISIBLE":
            continue
        fresh_status = fresh_data.get("status")
        if fresh_status not in ("OPEN", "RESOLVED"):
            continue
        if request.type and fresh_data.get("type") != request.type.value:
            continue

        raw_cat = fresh_data.get("categoryId")
        if not isinstance(raw_cat, str) or not raw_cat:
            continue
        if request.category_ids and raw_cat not in [c.value for c in request.category_ids]:
            continue

        raw_area = fresh_data.get("areaId")
        if request.area_ids and (not isinstance(raw_area, str) or raw_area not in [a.value for a in request.area_ids]):
            continue

        created_raw = fresh_data.get("createdAt")
        if not isinstance(created_raw, datetime):
            continue
        post_dt = created_raw.astimezone(timezone.utc) if created_raw.tzinfo else created_raw.replace(tzinfo=timezone.utc)
        if post_dt > anchor_dt or (lower_cutoff_utc and post_dt < lower_cutoff_utc):
            continue

        is_match, score = matches_and_score(fresh_data, query_terms)
        if not is_match:
            continue

        try:
            item = SearchItem.model_validate({
                "postId": cand.id,
                "title": fresh_data.get("title"),
                "type": fresh_data.get("type"),
                "status": fresh_status,
                "categoryId": fresh_data.get("categoryId"),
                "areaId": normalize_area_id(fresh_data.get("areaId")),
                "location": fresh_data.get("location", ""),
                "createdAt": post_dt,
                "imageUrl": fresh_data.get("imageUrl"),
                "score": score,
            })
            matched_items.append(item)
        except ValidationError:
            continue

        if len(matched_items) >= request.page_size:
            break

    has_more = False
    next_cursor = None
    if last_consumed_doc is not None:
        consumed_idx = raw_candidates.index(last_consumed_doc)
        if consumed_idx < len(raw_candidates) - 1 or has_lookahead:
            has_more = True
            c_data = last_consumed_doc.to_dict() or {}
            next_cursor = encode_cursor(fingerprint, anchor_dt, last_consumed_doc.id, c_data.get("createdAt"))

    final_items, search_mode = _blend_semantic_results(
        db,
        request=request,
        keyword_items=matched_items,
        anchor_dt=anchor_dt,
        lower_cutoff_utc=lower_cutoff_utc,
        has_cursor=has_cursor,
        remaining_time=remaining_time,
    )
    if search_mode == "HYBRID" and matched_items:
        returned_ids = {item.post_id for item in final_items}
        kept_kw = [item for item in matched_items if item.post_id in returned_ids]
        if len(kept_kw) < len(matched_items):
            if not kept_kw and final_items:
                final_items = final_items[:-1] + [matched_items[0]]
                kept_kw = [matched_items[0]]
            if kept_kw:
                has_more = True
                last_kw_id = kept_kw[-1].post_id
                raw_map = {doc.id: doc for doc in raw_candidates}
                raw_doc = raw_map.get(last_kw_id)
                raw_ts = (raw_doc.to_dict() or {}).get("createdAt") if raw_doc else kept_kw[-1].created_at
                next_cursor = encode_cursor(fingerprint, anchor_dt, last_kw_id, raw_ts)

    try:
        rem_t = remaining_time()
        caller_snap = db.collection("account_states").document(caller_uid).get(timeout=rem_t, retry=None)
    except (TimeoutError, DeadlineExceeded):
        raise ApiHTTPException(status_code=504, detail="Search request timed out.", code="DEADLINE_EXCEEDED")
    except ApiHTTPException:
        raise
    except (GoogleAPICallError, Exception):
        raise ApiHTTPException(status_code=503, detail="Datastore error.", code="DATASTORE_ERROR")

    if parse_account_state_snapshot(caller_snap, uid=caller_uid) != AccountStateStatus.ACTIVE:
        raise ApiHTTPException(status_code=403, detail="Account is scheduled for deletion and cannot perform this action.", code="ACCOUNT_INACTIVE")

    return SearchResponse(items=final_items, hasMore=has_more, nextCursor=next_cursor, searchMode=search_mode)


def _run_coro_sync(coro: Any) -> Any:
    try:
        asyncio.get_running_loop()
    except RuntimeError:
        return asyncio.run(coro)
    with ThreadPoolExecutor(max_workers=1) as pool:
        return pool.submit(asyncio.run, coro).result()


def _blend_semantic_results(
    db: Any,
    *,
    request: SearchRequest,
    keyword_items: List[SearchItem],
    anchor_dt: datetime,
    lower_cutoff_utc: Optional[datetime],
    has_cursor: bool,
    remaining_time: Any,
) -> Tuple[List[SearchItem], str]:
    """Bổ sung kết quả vector ngữ nghĩa (hỗ trợ cả vectorizer nội bộ 0 đồng và Gemini)."""
    if has_cursor or not request.query.strip():
        return keyword_items, "KEYWORD"
    has_gemini_key = bool(get_settings().effective_gemini_api_key)
    try:
        q_vec = _run_coro_sync(embed_query(request.query, timeout=min(3.5, remaining_time())))
        hits = query_similar_posts(
            db,
            query_vector=q_vec,
            limit=request.page_size,
            post_type=request.type.value if request.type else None,
            category_ids=[c.value for c in request.category_ids] or None,
            area_ids=[a.value for a in request.area_ids] or None,
            min_created_at=lower_cutoff_utc,
            max_created_at=anchor_dt,
        )
    except Exception:
        return keyword_items, "KEYWORD"
    if not hits and not has_gemini_key:
        return keyword_items, "KEYWORD"

    by_id: Dict[str, SearchItem] = {item.post_id: item for item in keyword_items}
    pending_hits: List[Tuple[str, float]] = []
    for hit in hits:
        pid = hit["postId"]
        sem_score = float(hit["score"])
        if sem_score < 0.45:
            continue
        if pid in by_id:
            prev = by_id[pid]
            by_id[pid] = prev.model_copy(update={"score": round(min(1.0, max(prev.score, sem_score)), 4)})
        elif _is_valid_doc_id(pid):
            pending_hits.append((pid, sem_score))

    if pending_hits:
        refs = [db.collection("posts").document(pid) for pid, _ in pending_hits] + [
            db.collection("post_tombstones").document(pid) for pid, _ in pending_hits
        ]
        post_tomb_map = _batch_get(db, refs, remaining_time())
        auth_refs: List[Any] = []
        for pid, _ in pending_hits:
            p_snap = post_tomb_map.get(f"posts/{pid}")
            f_data = p_snap.to_dict() if p_snap and getattr(p_snap, "exists", False) else None
            if isinstance(f_data, dict) and _is_valid_doc_id(f_data.get("authorId")):
                auth_refs.append(db.collection("account_states").document(f_data["authorId"]))
        auth_map = _batch_get(db, auth_refs, remaining_time())
        cat_set = {c.value for c in request.category_ids}
        area_set = {a.value for a in request.area_ids}

        for pid, sem_score in pending_hits:
            tomb_snap = post_tomb_map.get(f"post_tombstones/{pid}")
            if tomb_snap and getattr(tomb_snap, "exists", False):
                continue
            p_snap = post_tomb_map.get(f"posts/{pid}")
            fresh = p_snap.to_dict() if p_snap and getattr(p_snap, "exists", False) else None
            if not isinstance(fresh, dict):
                continue
            author_id = fresh.get("authorId")
            if not _is_valid_doc_id(author_id):
                continue
            acc_snap = auth_map.get(f"account_states/{author_id}")
            if not acc_snap or parse_account_state_snapshot(acc_snap, uid=author_id) != AccountStateStatus.ACTIVE:
                continue
            if fresh.get("moderationStatus") != "VISIBLE" or fresh.get("status") not in ("OPEN", "RESOLVED"):
                continue
            if request.type and fresh.get("type") != request.type.value:
                continue
            raw_cat = fresh.get("categoryId")
            if not isinstance(raw_cat, str) or not raw_cat or (cat_set and raw_cat not in cat_set):
                continue
            raw_area = fresh.get("areaId")
            if area_set and (not isinstance(raw_area, str) or raw_area not in area_set):
                continue
            created_raw = fresh.get("createdAt")
            if not isinstance(created_raw, datetime):
                continue
            post_dt = created_raw.astimezone(timezone.utc) if created_raw.tzinfo else created_raw.replace(tzinfo=timezone.utc)
            if post_dt > anchor_dt or (lower_cutoff_utc and post_dt < lower_cutoff_utc):
                continue
            try:
                by_id[pid] = SearchItem.model_validate({
                    "postId": pid,
                    "title": fresh.get("title"),
                    "type": fresh.get("type"),
                    "status": fresh.get("status"),
                    "categoryId": raw_cat,
                    "areaId": normalize_area_id(raw_area),
                    "location": fresh.get("location", ""),
                    "createdAt": post_dt,
                    "imageUrl": fresh.get("imageUrl"),
                    "score": round(sem_score, 4),
                })
            except ValidationError:
                continue

    merged = sorted(by_id.values(), key=lambda i: (-i.score, -i.created_at.timestamp(), i.post_id))
    return merged[: request.page_size], "HYBRID"
