"""
scripts/backfill_b0.py — Safe, idempotent B0 Firestore backfill & embedding rebuild CLI.

Usage (from project root or backend/):
  python backend/scripts/backfill_b0.py --dry-run
  python backend/scripts/backfill_b0.py --project demo-lostlink --apply
  python backend/scripts/backfill_b0.py --apply --rebuild-embeddings --run-id 20261112
"""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import sys
from typing import List, Optional

_BACKEND_DIR = Path(__file__).resolve().parents[1]
if str(_BACKEND_DIR) not in sys.path:
    sys.path.insert(0, str(_BACKEND_DIR))

from app.core.config import get_settings  # noqa: E402
from app.core.firebase import get_firestore, init_firebase  # noqa: E402
from app.services.migration_service import BackfillSummary, run_backfill  # noqa: E402


def parse_args(argv: Optional[List[str]] = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="B0 idempotent post schema backfill and optional embedding rebuild CLI."
    )
    parser.add_argument(
        "--project",
        type=str,
        default=None,
        help="Override FIREBASE_PROJECT_ID for this migration run.",
    )

    mode_group = parser.add_mutually_exclusive_group()
    mode_group.add_argument(
        "--dry-run",
        action="store_true",
        default=True,
        help="Inspect and print summary without writing to Firestore (default).",
    )
    mode_group.add_argument(
        "--apply",
        action="store_true",
        default=False,
        help="Apply transactional updates (and BACKFILL events if requested).",
    )

    parser.add_argument(
        "--rebuild-embeddings",
        action="store_true",
        default=False,
        help="Enqueue idempotent BACKFILL searchJob events without bumping posts.version.",
    )
    parser.add_argument(
        "--batch-limit",
        type=int,
        default=None,
        help="Optional maximum number of posts to scan in this run.",
    )
    parser.add_argument(
        "--run-id",
        type=str,
        default=None,
        help="Deterministic suffix for {postId}_v{version}_backfill_{runId} event IDs.",
    )
    return parser.parse_args(argv)


def format_summary(summary: BackfillSummary) -> str:
    """Render a strictly non-PII execution and audit summary."""
    lines = [
        f"=== LostLink B0 Backfill ({summary.mode}) ===",
        f"runId:                    {summary.run_id}",
        f"scanned:                  {summary.scanned}",
        f"wouldUpdate:              {summary.would_update}",
        f"updated:                  {summary.updated}",
        f"unchanged:                {summary.unchanged}",
        f"skippedConflict:          {summary.skipped_conflict}",
        f"skippedDeleted:           {summary.skipped_deleted}",
        f"preservedHidden:          {summary.preserved_hidden}",
        f"preservedHigherVersion:   {summary.preserved_higher_version}",
        f"normalizedStatuses:       {summary.normalized_statuses}",
        f"normalizedCategories:     {summary.normalized_categories}",
        f"normalizedAreas:          {summary.normalized_areas}",
        f"missingCreatedAtCount:    {len(summary.missing_created_at_ids)}",
        f"missingAuthorIdCount:     {len(summary.missing_author_id_posts)}",
        f"unknownCategoryCount:     {len(summary.unknown_category_posts)}",
        f"unknownAreaCount:         {len(summary.unknown_area_posts)}",
        f"unknownStatusCount:       {len(summary.unknown_status_posts)}",
        f"legacyUnverifiedMedia:    {len(summary.legacy_unverified_media_ids)}",
        f"backfillEventsPlanned:    {summary.backfill_events_planned}",
        f"backfillEventsCreated:    {summary.backfill_events_created}",
        f"backfillEventsSkipped:    {summary.backfill_events_skipped}",
    ]
    if summary.backfill_events_skipped_reasons:
        lines.append(
            "backfillSkippedReasons:   "
            + json.dumps(summary.backfill_events_skipped_reasons)
        )
    if summary.missing_created_at_ids:
        lines.append(
            "auditMissingCreatedAtIds: "
            + json.dumps(summary.missing_created_at_ids[:20])
        )
    if summary.unknown_category_posts:
        lines.append(
            "auditUnknownCategories:   "
            + json.dumps(summary.unknown_category_posts)
        )
    if summary.unknown_area_posts:
        lines.append(
            "auditUnknownAreas:        "
            + json.dumps(summary.unknown_area_posts)
        )
    if summary.legacy_unverified_media_ids:
        lines.append(
            "auditLegacyMediaPostIds:  "
            + json.dumps(summary.legacy_unverified_media_ids[:20])
        )
    if summary.sample_changes:
        lines.append(
            "sampleChanges:            "
            + json.dumps(summary.sample_changes)
        )
    return "\n".join(lines)


def main(argv: Optional[List[str]] = None) -> int:
    args = parse_args(argv)
    if args.project:
        os.environ["FIREBASE_PROJECT_ID"] = args.project
        get_settings.cache_clear()

    init_firebase()
    db = get_firestore()
    summary = run_backfill(
        db,
        apply=bool(args.apply),
        rebuild_embeddings=bool(args.rebuild_embeddings),
        batch_limit=args.batch_limit,
        run_id=args.run_id,
    )
    print(format_summary(summary))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
