"""
app/services/migration_service.py — Safe, idempotent B0 backfill & embedding rebuild service.

Design invariants (Contract-B0 §5, B0-T12):
- Dry-run by default (`apply=False`); never mutates Firestore unless `apply=True`.
- Preserves `createdAt`, `authorId`, `moderationStatus="HIDDEN"`, higher `version`,
  and distinct `RESOLVED` vs `CLOSED` statuses.
- Normalizes `ACTIVE` -> `OPEN`, `phone`/`laptop` -> `electronics`, and
  `Auditorium` -> `auditorium`; NEVER maps `documents` -> `student_card` and
  NEVER guesses `campus_1..3` or `UNKNOWN`/`OTHER`.
- Audits missing `createdAt` (never fabricates a timestamp), unknown taxonomy values,
  and unverified legacy media (`imageUrl`/`imageUrls`/`imageUri` without `mediaAssetIds`).
- Never logs or prints PII (emails, titles, descriptions, author names).
- Uses Firestore transactions with optimistic preconditions so concurrent edits
  (e.g., moderation -> HIDDEN, post edit bumping version) are never overwritten.
- `--rebuild-embeddings` emits `post_events/{postId}_v{currentVersion}_backfill_{runId}`
  with `changeType="BACKFILL"`, `searchJob=PENDING`, `notifyJob=SKIPPED`, and
  `mediaCleanupJob=SKIPPED` without bumping `posts.version`.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime, timezone
import logging
import re
from typing import Any, Dict, List, Optional
import uuid

from google.cloud import firestore as _fs
from google.cloud.firestore import Client as FirestoreClient
from pydantic import BaseModel, Field

from app.schemas.post import (
    EventChangeType,
    JobState,
    JobStatus,
    ModerationStatus,
    POST_STATUS_LEGACY_ALIASES,
    PersistedPostEvent,
    PostStatus,
)
from app.schemas.taxonomy import (
    AreaChoice,
    CATEGORY_LABELS,
    normalize_area_id,
    normalize_category_id,
)

logger = logging.getLogger(__name__)

_SAFE_RUN_ID_RE = re.compile(r"[^A-Za-z0-9_-]+")


def sanitize_run_id(raw_run_id: Optional[str]) -> str:
    """Produce a deterministic, Firestore-doc-ID-safe run identifier."""
    if raw_run_id and raw_run_id.strip():
        cleaned = _SAFE_RUN_ID_RE.sub("_", raw_run_id.strip()).strip("_")
        if cleaned:
            return cleaned[:48]
    stamp = datetime.now(tz=timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    return f"{stamp}_{uuid.uuid4().hex[:8]}"


class BackfillSummary(BaseModel):
    """Non-PII structured audit & execution summary for B0 migration runs."""

    mode: str
    run_id: str
    scanned: int = 0
    would_update: int = 0
    updated: int = 0
    unchanged: int = 0
    skipped_conflict: int = 0
    skipped_deleted: int = 0
    preserved_hidden: int = 0
    preserved_higher_version: int = 0
    normalized_statuses: int = 0
    normalized_categories: int = 0
    normalized_areas: int = 0
    missing_created_at_ids: List[str] = Field(default_factory=list)
    missing_author_id_posts: List[str] = Field(default_factory=list)
    unknown_category_posts: Dict[str, str] = Field(default_factory=dict)
    unknown_area_posts: Dict[str, str] = Field(default_factory=dict)
    unknown_status_posts: Dict[str, str] = Field(default_factory=dict)
    legacy_unverified_media_ids: List[str] = Field(default_factory=list)
    backfill_events_planned: int = 0
    backfill_events_created: int = 0
    backfill_events_skipped: int = 0
    backfill_events_skipped_reasons: Dict[str, int] = Field(default_factory=dict)
    sample_changes: List[Dict[str, Any]] = Field(default_factory=list)


def _bump_skip_reason(reasons: Dict[str, int], reason: str) -> None:
    reasons[reason] = reasons.get(reason, 0) + 1


@dataclass
class PostBackfillPlan:
    """Planned per-document patch and preconditions without PII."""

    post_id: str
    updates: Dict[str, Any]
    effective_version: int
    effective_moderation_status: Optional[str]
    effective_author_id: str
    precondition: Dict[str, Any]
    expected_update_time: Any = None
    preserved_hidden: bool = False
    preserved_higher_version: bool = False
    normalized_status: bool = False
    normalized_category: bool = False
    normalized_area: bool = False
    missing_created_at: bool = False
    missing_author_id: bool = False
    unknown_category: Optional[str] = None
    unknown_area: Optional[str] = None
    unknown_status: Optional[str] = None
    legacy_unverified_media: bool = False

    @property
    def has_updates(self) -> bool:
        return bool(self.updates)


def plan_post_backfill(
    post_id: str,
    data: Dict[str, Any],
    *,
    expected_update_time: Any = None,
) -> PostBackfillPlan:
    """
    Compute non-destructive B0 backfill updates for one post document.

    Pure function (except referencing `firestore.SERVER_TIMESTAMP` sentinel when
    writes are planned) so it can be unit-tested independently.
    """
    updates: Dict[str, Any] = {}

    preserved_hidden = False
    preserved_higher_version = False
    normalized_status = False
    normalized_category = False
    normalized_area = False
    unknown_category: Optional[str] = None
    unknown_area: Optional[str] = None
    unknown_status: Optional[str] = None

    # 1. Immutable identity / audit fields (NEVER overwrite or fabricate)
    created_at_val = data.get("createdAt")
    missing_created_at = not isinstance(created_at_val, datetime)

    raw_author = data.get("authorId")
    author_id = raw_author.strip() if isinstance(raw_author, str) else ""
    missing_author_id = not bool(author_id)

    # 2. moderationStatus — fill ONLY when missing; NEVER overwrite HIDDEN or existing
    raw_mod = data.get("moderationStatus")
    if raw_mod is None or (isinstance(raw_mod, str) and not raw_mod.strip()):
        updates["moderationStatus"] = ModerationStatus.VISIBLE.value
        effective_moderation_status: Optional[str] = ModerationStatus.VISIBLE.value
    else:
        mod_str = str(raw_mod).strip()
        effective_moderation_status = mod_str
        if mod_str == ModerationStatus.HIDDEN.value:
            preserved_hidden = True

    # 3. version — fill ONLY when missing (<1 invalid edge also normalized to 1);
    # NEVER downgrade higher version.
    raw_version = data.get("version")
    if raw_version is None or isinstance(raw_version, bool):
        updates["version"] = 1
        effective_version = 1
    else:
        try:
            parsed_version = int(raw_version)
        except (TypeError, ValueError):
            parsed_version = 0
        if parsed_version < 1:
            updates["version"] = 1
            effective_version = 1
        else:
            effective_version = parsed_version
            if parsed_version > 1:
                preserved_higher_version = True

    # 4. status — ACTIVE -> OPEN; keep OPEN/RESOLVED/CLOSED distinct
    raw_status = data.get("status")
    if raw_status is None or (isinstance(raw_status, str) and not raw_status.strip()):
        updates["status"] = PostStatus.OPEN.value
    else:
        status_str = str(raw_status).strip()
        if status_str in POST_STATUS_LEGACY_ALIASES:
            canonical_status = POST_STATUS_LEGACY_ALIASES[status_str].value
            if raw_status != canonical_status:
                updates["status"] = canonical_status
                normalized_status = True
        else:
            try:
                canonical_status_enum = PostStatus(status_str)
                if raw_status != canonical_status_enum.value:
                    updates["status"] = canonical_status_enum.value
            except ValueError:
                unknown_status = status_str[:40]

    # 5. categoryId — phone/laptop -> electronics; NEVER map documents -> student_card
    raw_cat = data.get("categoryId")
    cat_str = raw_cat.strip() if isinstance(raw_cat, str) else ""
    canonical_cat = normalize_category_id(cat_str) if cat_str else None
    if canonical_cat is not None:
        if raw_cat != canonical_cat.value:
            updates["categoryId"] = canonical_cat.value
            normalized_category = True
        raw_cat_label = data.get("category")
        if (
            not isinstance(raw_cat_label, str)
            or not raw_cat_label.strip()
            or normalized_category
        ):
            target_label = CATEGORY_LABELS[canonical_cat]
            if raw_cat_label != target_label:
                updates["category"] = target_label
    else:
        unknown_category = "<missing>" if not cat_str else cat_str[:40]

    # 6. areaId & areaChoice — Auditorium -> auditorium; NEVER guess campus_1..3 or UNKNOWN/OTHER
    raw_area_choice = data.get("areaChoice")
    area_choice_str = (
        raw_area_choice.strip() if isinstance(raw_area_choice, str) else ""
    )
    raw_area_id = data.get("areaId")
    area_id_str = raw_area_id.strip() if isinstance(raw_area_id, str) else ""
    canonical_area = normalize_area_id(area_id_str) if area_id_str else None

    if canonical_area is not None:
        if raw_area_id != canonical_area.value:
            updates["areaId"] = canonical_area.value
            normalized_area = True
        if not area_choice_str:
            updates["areaChoice"] = AreaChoice.KNOWN.value
    elif area_id_str:
        unknown_area = area_id_str[:40]
    else:
        if area_choice_str not in (AreaChoice.OTHER.value, AreaChoice.UNKNOWN.value):
            unknown_area = (
                "<missing>" if not area_choice_str else f"choice:{area_choice_str[:20]}"
            )

    # 7. mediaAssetIds & unverified legacy media audit (never drop imageUrl/imageUrls)
    raw_media_ids = data.get("mediaAssetIds")
    if raw_media_ids is None:
        updates["mediaAssetIds"] = []
        effective_media_ids: List[str] = []
    else:
        effective_media_ids = (
            [m for m in raw_media_ids if isinstance(m, str) and m.strip()]
            if isinstance(raw_media_ids, list)
            else []
        )

    has_legacy_url = bool(
        (isinstance(data.get("imageUrl"), str) and data["imageUrl"].strip())
        or (isinstance(data.get("imageUri"), str) and data["imageUri"].strip())
        or (
            isinstance(data.get("imageUrls"), list)
            and any(isinstance(u, str) and u.strip() for u in data["imageUrls"])
        )
    )
    legacy_unverified_media = has_legacy_url and len(effective_media_ids) == 0

    # 8. updatedAt — set ONLY if missing OR if any field updates are applied
    if data.get("updatedAt") is None or updates:
        updates["updatedAt"] = _fs.SERVER_TIMESTAMP

    precondition = {
        "createdAt": data.get("createdAt"),
        "authorId": data.get("authorId"),
        "moderationStatus": data.get("moderationStatus"),
        "version": data.get("version"),
        "status": data.get("status"),
        "categoryId": data.get("categoryId"),
        "category": data.get("category"),
        "areaChoice": data.get("areaChoice"),
        "areaId": data.get("areaId"),
        "mediaAssetIds": data.get("mediaAssetIds"),
        "updatedAt": data.get("updatedAt"),
    }

    return PostBackfillPlan(
        post_id=post_id,
        updates=updates,
        effective_version=effective_version,
        effective_moderation_status=effective_moderation_status,
        effective_author_id=author_id,
        precondition=precondition,
        expected_update_time=expected_update_time,
        preserved_hidden=preserved_hidden,
        preserved_higher_version=preserved_higher_version,
        normalized_status=normalized_status,
        normalized_category=normalized_category,
        normalized_area=normalized_area,
        missing_created_at=missing_created_at,
        missing_author_id=missing_author_id,
        unknown_category=unknown_category,
        unknown_area=unknown_area,
        unknown_status=unknown_status,
        legacy_unverified_media=legacy_unverified_media,
    )


def _apply_post_update_txn(
    db: FirestoreClient,
    plan: PostBackfillPlan,
) -> str:
    """
    Atomically apply post updates after checking snapshot update_time and all
    source precondition fields inside a Firestore transaction (C2-R1 TOCTOU guard).
    Returns 'updated', 'skipped_conflict', or 'skipped_deleted'.
    """
    post_ref = db.collection("posts").document(plan.post_id)

    @_fs.transactional
    def _txn(transaction: _fs.Transaction) -> str:
        fresh = post_ref.get(transaction=transaction)
        if not fresh.exists:
            return "skipped_deleted"
        if (
            plan.expected_update_time is not None
            and getattr(fresh, "update_time", None) != plan.expected_update_time
        ):
            return "skipped_conflict"

        fresh_data = fresh.to_dict() or {}
        for key, expected in plan.precondition.items():
            if fresh_data.get(key) != expected:
                return "skipped_conflict"

        recomputed = plan_post_backfill(plan.post_id, fresh_data)
        if not recomputed.has_updates:
            return "skipped_conflict"

        transaction.update(post_ref, dict(recomputed.updates))
        return "updated"

    return _txn(db.transaction())


def _build_backfill_event_doc(plan: PostBackfillPlan) -> Dict[str, Any]:
    """Construct validated BACKFILL event payload without bumping posts.version."""
    now = datetime.now(tz=timezone.utc)
    skipped_job = JobState(
        status=JobStatus.SKIPPED,
        attempts=0,
        claim_token=None,
        lock_owner=None,
        lock_expires_at=None,
        next_retry_at=None,
        last_error=None,
    )
    event = PersistedPostEvent(
        post_id=plan.post_id,
        author_id=plan.effective_author_id,
        post_version=plan.effective_version,
        change_type=EventChangeType.BACKFILL,
        created_at=now,
        image_asset_refs=[],
        search_job=JobState.initial(),
        notify_job=skipped_job,
        media_cleanup_job=skipped_job,
    )
    payload = event.to_firestore_dict()
    payload["createdAt"] = _fs.SERVER_TIMESTAMP
    return payload


def _create_backfill_event_if_absent_txn(
    db: FirestoreClient,
    event_id: str,
    plan: PostBackfillPlan,
) -> str:
    """
    Idempotently create `post_events/{postId}_v{ver}_backfill_{runId}` if absent.
    Checks inside transaction that post still exists, is VISIBLE, and has matching version.
    """
    event_ref = db.collection("post_events").document(event_id)
    post_ref = db.collection("posts").document(plan.post_id)

    @_fs.transactional
    def _txn(transaction: _fs.Transaction) -> str:
        existing_event = event_ref.get(transaction=transaction)
        if existing_event.exists:
            return "skipped_existing"

        post_snap = post_ref.get(transaction=transaction)
        if not post_snap.exists:
            return "skipped_missing_post"

        post_data = post_snap.to_dict() or {}
        if not isinstance(post_data.get("createdAt"), datetime):
            return "missing_created_at"
        cur_mod = post_data.get("moderationStatus")
        cur_ver = int(post_data.get("version") or 1)
        cur_author = (
            post_data.get("authorId").strip()
            if isinstance(post_data.get("authorId"), str)
            else ""
        )
        if cur_mod != ModerationStatus.VISIBLE.value or not cur_author:
            return "ineligible"
        if cur_ver != plan.effective_version:
            return "version_changed"

        payload = _build_backfill_event_doc(
            PostBackfillPlan(
                post_id=plan.post_id,
                updates={},
                effective_version=cur_ver,
                effective_moderation_status=cur_mod,
                effective_author_id=cur_author,
                precondition={},
            )
        )
        transaction.set(event_ref, payload)
        return "created"

    return _txn(db.transaction())


def run_backfill(
    db: FirestoreClient,
    *,
    apply: bool = False,
    rebuild_embeddings: bool = False,
    batch_limit: Optional[int] = None,
    run_id: Optional[str] = None,
) -> BackfillSummary:
    """
    Scan `posts`, audit legacy anomalies, optionally apply transactional updates,
    and optionally enqueue idempotent `BACKFILL` search events.
    """
    if batch_limit is not None and batch_limit <= 0:
        raise ValueError("batch_limit must be > 0 when provided.")

    effective_run_id = sanitize_run_id(run_id)
    summary = BackfillSummary(
        mode="apply" if apply else "dry-run",
        run_id=effective_run_id,
    )

    query: Any = db.collection("posts")
    if batch_limit is not None:
        query = query.limit(batch_limit)

    for snap in query.stream():
        if not snap.exists:
            continue
        data = snap.to_dict() or {}
        plan = plan_post_backfill(
            snap.id,
            data,
            expected_update_time=getattr(snap, "update_time", None),
        )
        summary.scanned += 1

        if plan.preserved_hidden:
            summary.preserved_hidden += 1
        if plan.preserved_higher_version:
            summary.preserved_higher_version += 1
        if plan.normalized_status:
            summary.normalized_statuses += 1
        if plan.normalized_category:
            summary.normalized_categories += 1
        if plan.normalized_area:
            summary.normalized_areas += 1
        if plan.missing_created_at:
            summary.missing_created_at_ids.append(plan.post_id)
        if plan.missing_author_id:
            summary.missing_author_id_posts.append(plan.post_id)
        if plan.unknown_category is not None:
            summary.unknown_category_posts[plan.post_id] = plan.unknown_category
        if plan.unknown_area is not None:
            summary.unknown_area_posts[plan.post_id] = plan.unknown_area
        if plan.unknown_status is not None:
            summary.unknown_status_posts[plan.post_id] = plan.unknown_status
        if plan.legacy_unverified_media:
            summary.legacy_unverified_media_ids.append(plan.post_id)

        if plan.has_updates:
            summary.would_update += 1
            if len(summary.sample_changes) < 10:
                summary.sample_changes.append(
                    {
                        "postId": plan.post_id,
                        "fields": sorted(plan.updates.keys()),
                    }
                )
            if apply:
                outcome = _apply_post_update_txn(db, plan)
                if outcome == "updated":
                    summary.updated += 1
                elif outcome == "skipped_conflict":
                    summary.skipped_conflict += 1
                    logger.warning(
                        "Backfill skipped postId=%s due to concurrent modification.",
                        plan.post_id,
                    )
                    continue
                elif outcome == "skipped_deleted":
                    summary.skipped_deleted += 1
                    continue
        else:
            summary.unchanged += 1

        if rebuild_embeddings:
            skip_reason: Optional[str] = None
            if plan.missing_created_at:
                skip_reason = "missing_created_at"
            elif plan.effective_moderation_status != ModerationStatus.VISIBLE.value:
                skip_reason = "hidden_or_non_visible"
            elif plan.missing_author_id:
                skip_reason = "missing_author_id"

            if skip_reason is not None:
                summary.backfill_events_skipped += 1
                _bump_skip_reason(summary.backfill_events_skipped_reasons, skip_reason)
                continue

            summary.backfill_events_planned += 1
            if apply:
                event_id = (
                    f"{plan.post_id}_v{plan.effective_version}"
                    f"_backfill_{effective_run_id}"
                )
                ev_outcome = _create_backfill_event_if_absent_txn(db, event_id, plan)
                if ev_outcome == "created":
                    summary.backfill_events_created += 1
                else:
                    summary.backfill_events_skipped += 1
                    _bump_skip_reason(summary.backfill_events_skipped_reasons, ev_outcome)

    if summary.missing_created_at_ids:
        logger.warning(
            "Backfill audit: %d posts missing createdAt (timestamp not fabricated).",
            len(summary.missing_created_at_ids),
        )
    if summary.legacy_unverified_media_ids:
        logger.warning(
            "Backfill audit: %d posts have legacy image URLs without mediaAssetIds.",
            len(summary.legacy_unverified_media_ids),
        )

    return summary
