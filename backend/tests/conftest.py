"""
tests/conftest.py — Shared pytest fixtures and in-memory Firestore double.

Provides `FakeFirestoreClient` supporting nested collections, dot-notation
updates, `FieldFilter` queries, `order_by`, `limit`, `SERVER_TIMESTAMP`,
and `@firestore.transactional` optimistic concurrency / rollback semantics.
"""
from __future__ import annotations

import copy
from datetime import datetime, timezone
import threading
from typing import Any, Callable, Dict, Iterable, Iterator, List, Optional, Tuple
import uuid

from google.api_core import exceptions as api_exceptions
from google.cloud import firestore as _fs
import pytest

from app.core.config import Settings, get_settings

Settings.model_config["env_file"] = None
get_settings.cache_clear()

from app.core.firebase import reset_firebase_for_tests  # noqa: E402
from app.routers.events import reset_wake_rate_limiter_for_tests  # noqa: E402
from app.worker.runner import reset_worker_scheduler_for_tests  # noqa: E402


def _now_utc() -> datetime:
    return datetime.now(tz=timezone.utc)


def _normalize_comparable(value: Any) -> Any:
    """Normalize naive/aware datetimes to UTC so comparisons don't raise TypeError."""
    if isinstance(value, datetime):
        if value.tzinfo is None:
            return value.replace(tzinfo=timezone.utc)
        return value.astimezone(timezone.utc)
    return value


def _resolve_sentinels(value: Any, *, now: Optional[datetime] = None) -> Any:
    """Recursively deep-copy data and materialize Firestore sentinel values."""
    ts = now or _now_utc()
    if value is _fs.SERVER_TIMESTAMP:
        return ts
    if isinstance(value, dict):
        return {k: _resolve_sentinels(v, now=ts) for k, v in value.items()}
    if isinstance(value, list):
        return [_resolve_sentinels(v, now=ts) for v in value]
    return copy.deepcopy(value)


def _get_by_path(data: Dict[str, Any], path: str) -> Tuple[bool, Any]:
    current: Any = data
    for segment in path.split("."):
        if not isinstance(current, dict) or segment not in current:
            return False, None
        current = current[segment]
    return True, current


def _set_by_path(data: Dict[str, Any], path: str, value: Any) -> None:
    parts = path.split(".")
    current = data
    for segment in parts[:-1]:
        nxt = current.get(segment)
        if not isinstance(nxt, dict):
            nxt = {}
            current[segment] = nxt
        current = nxt
    leaf = parts[-1]
    if value is _fs.DELETE_FIELD:
        current.pop(leaf, None)
    else:
        current[leaf] = value


def _deep_merge(target: Dict[str, Any], patch: Dict[str, Any]) -> Dict[str, Any]:
    for key, val in patch.items():
        if isinstance(val, dict) and isinstance(target.get(key), dict):
            _deep_merge(target[key], val)
        else:
            target[key] = val
    return target


class FakeDocumentSnapshot:
    def __init__(
        self,
        doc_id: str,
        reference: "FakeDocumentReference",
        data: Optional[Dict[str, Any]],
    ) -> None:
        self.id = doc_id
        self.reference = reference
        self._data = copy.deepcopy(data) if data is not None else None

    @property
    def exists(self) -> bool:
        return self._data is not None

    def to_dict(self) -> Optional[Dict[str, Any]]:
        if self._data is None:
            return None
        return copy.deepcopy(self._data)

    def get(self, field_path: str) -> Any:
        if self._data is None:
            return None
        found, val = _get_by_path(self._data, field_path)
        return copy.deepcopy(val) if found else None


class FakeQuery:
    def __init__(
        self,
        collection: "FakeCollectionReference",
        filters: Optional[List[Tuple[str, str, Any]]] = None,
        orders: Optional[List[Tuple[str, bool]]] = None,
        limit_val: Optional[int] = None,
        start_after_cursor: Any = None,
    ) -> None:
        self._collection = collection
        self._filters: List[Tuple[str, str, Any]] = list(filters or [])
        self._orders: List[Tuple[str, bool]] = list(orders or [])
        self._limit = limit_val
        self._start_after = start_after_cursor

    def where(
        self,
        field_path: Optional[str] = None,
        op_string: Optional[str] = None,
        value: Any = None,
        *,
        filter: Any = None,
    ) -> "FakeQuery":
        if filter is not None:
            f_path = filter.field_path
            f_op = filter.op_string
            f_val = filter.value
        else:
            if field_path is None or op_string is None:
                raise ValueError("where() requires filter=FieldFilter or positional args.")
            f_path, f_op, f_val = field_path, op_string, value
        return FakeQuery(
            self._collection,
            filters=[*self._filters, (f_path, f_op, f_val)],
            orders=self._orders,
            limit_val=self._limit,
            start_after_cursor=self._start_after,
        )

    def order_by(self, field_path: str, direction: str = "ASCENDING") -> "FakeQuery":
        descending = str(direction).upper() == "DESCENDING"
        return FakeQuery(
            self._collection,
            filters=self._filters,
            orders=[*self._orders, (field_path, descending)],
            limit_val=self._limit,
            start_after_cursor=self._start_after,
        )

    def limit(self, count: int) -> "FakeQuery":
        return FakeQuery(
            self._collection,
            filters=self._filters,
            orders=self._orders,
            limit_val=count,
            start_after_cursor=self._start_after,
        )

    def start_after(self, cursor: Any) -> "FakeQuery":
        return FakeQuery(
            self._collection,
            filters=self._filters,
            orders=self._orders,
            limit_val=self._limit,
            start_after_cursor=cursor,
        )

    @staticmethod
    def _matches_filter(doc_data: Dict[str, Any], field_path: str, op: str, expected: Any) -> bool:
        found, actual = _get_by_path(doc_data, field_path)
        if not found:
            actual = None
        norm_actual = _normalize_comparable(actual)
        norm_expected = _normalize_comparable(expected)

        if op == "==":
            return norm_actual == norm_expected
        if op == "!=":
            return found and norm_actual != norm_expected
        if op == "in":
            candidates = [_normalize_comparable(v) for v in (expected or [])]
            return found and norm_actual in candidates
        if op == "not-in":
            candidates = [_normalize_comparable(v) for v in (expected or [])]
            return found and norm_actual not in candidates
        if op == "array_contains":
            return isinstance(actual, list) and expected in actual
        if op == "array_contains_any":
            return isinstance(actual, list) and any(item in actual for item in (expected or []))
        if op in ("<", "<=", ">", ">="):
            if norm_actual is None or norm_expected is None:
                return False
            try:
                if op == "<":
                    return norm_actual < norm_expected
                if op == "<=":
                    return norm_actual <= norm_expected
                if op == ">":
                    return norm_actual > norm_expected
                if op == ">=":
                    return norm_actual >= norm_expected
            except TypeError:
                return False
        raise ValueError(f"Unsupported filter operator in FakeQuery: {op!r}")

    def _is_after_cursor(self, item_id: str, item_data: Dict[str, Any]) -> bool:
        cursor = self._start_after
        if cursor is None:
            return True

        if isinstance(cursor, FakeDocumentSnapshot):
            cursor_data = cursor.to_dict() or {}
            cursor_id: Optional[str] = cursor.id
            cursor_values = [
                _normalize_comparable(_get_by_path(cursor_data, field_path)[1])
                for field_path, _ in self._orders
            ]
        elif isinstance(cursor, (list, tuple)):
            cursor_id = None
            cursor_values = [_normalize_comparable(v) for v in cursor]
        elif isinstance(cursor, dict):
            cursor_id = None
            cursor_values = [
                _normalize_comparable(_get_by_path(cursor, field_path)[1])
                for field_path, _ in self._orders
            ]
        else:
            return True

        for idx, (field_path, descending) in enumerate(self._orders):
            if idx >= len(cursor_values):
                break
            found, raw_item = _get_by_path(item_data, field_path)
            item_val = _normalize_comparable(raw_item) if found else None
            c_val = cursor_values[idx]
            if item_val == c_val:
                continue
            if item_val is None:
                return not descending
            if c_val is None:
                return descending
            return (item_val < c_val) if descending else (item_val > c_val)

        if cursor_id is not None:
            return item_id > cursor_id
        return False

    def stream(self) -> Iterator[FakeDocumentSnapshot]:
        items = self._collection._client._snapshot_collection(self._collection._name)
        matched: List[Tuple[str, Dict[str, Any]]] = []
        for doc_id, data in items:
            if all(
                self._matches_filter(data, path, op, val)
                for path, op, val in self._filters
            ):
                matched.append((doc_id, data))

        # Deterministic __name__ ASC tiebreaker before applying order_by clauses
        matched.sort(key=lambda it: it[0])

        # Apply order_by clauses (last order_by first for Python stable multi-key sort)
        for field_path, descending in reversed(self._orders):
            # Split None vs non-None so heterogenous comparisons never raise TypeError
            non_null = [
                it
                for it in matched
                if _get_by_path(it[1], field_path)[0]
                and _get_by_path(it[1], field_path)[1] is not None
            ]
            nulls = [it for it in matched if it not in non_null]
            non_null.sort(
                key=lambda it, _p=field_path: _normalize_comparable(_get_by_path(it[1], _p)[1]),
                reverse=descending,
            )
            matched = (nulls + non_null) if descending else (non_null + nulls)

        if self._start_after is not None:
            matched = [
                (doc_id, data)
                for doc_id, data in matched
                if self._is_after_cursor(doc_id, data)
            ]

        if self._limit is not None:
            matched = matched[: self._limit]

        snaps = [
            FakeDocumentSnapshot(
                doc_id,
                self._collection.document(doc_id),
                data,
            )
            for doc_id, data in matched
        ]
        return iter(snaps)

    def get(self) -> List[FakeDocumentSnapshot]:
        return list(self.stream())


class FakeDocumentReference:
    def __init__(self, client: "FakeFirestoreClient", collection_name: str, doc_id: str) -> None:
        self._client = client
        self._collection_name = collection_name
        self.id = doc_id

    @property
    def path(self) -> str:
        return f"{self._collection_name}/{self.id}"

    def get(self, transaction: Optional["FakeTransaction"] = None) -> FakeDocumentSnapshot:
        if transaction is not None:
            return transaction._get(self)
        data = self._client._raw_get(self._collection_name, self.id)
        return FakeDocumentSnapshot(self.id, self, data)

    def set(self, document_data: Dict[str, Any], merge: bool = False) -> None:
        self._client._raw_set(self._collection_name, self.id, document_data, merge=merge)

    def update(self, field_updates: Dict[str, Any]) -> None:
        self._client._raw_update(self._collection_name, self.id, field_updates)

    def delete(self) -> None:
        self._client._raw_delete(self._collection_name, self.id)


class FakeCollectionReference(FakeQuery):
    def __init__(self, client: "FakeFirestoreClient", name: str) -> None:
        self._client = client
        self._name = name
        super().__init__(self)

    def document(self, document_id: Optional[str] = None) -> FakeDocumentReference:
        doc_id = document_id if document_id is not None else uuid.uuid4().hex[:20]
        return FakeDocumentReference(self._client, self._name, doc_id)


class FakeTransaction:
    def __init__(self, client: "FakeFirestoreClient", max_attempts: int = 5) -> None:
        self._client = client
        self._max_attempts = max_attempts
        self._read_only = False
        self._id: Optional[str] = None
        self._read_versions: Dict[Tuple[str, str], int] = {}
        self._ops: List[Tuple[str, FakeDocumentReference, Any, bool]] = []

    def _clean_up(self) -> None:
        self._read_versions.clear()
        self._ops.clear()
        self._id = None

    def _begin(self, retry_id: Optional[str] = None) -> None:
        self._id = retry_id or uuid.uuid4().hex[:12]

    def _rollback(self) -> None:
        self._clean_up()

    def _get(self, doc_ref: FakeDocumentReference) -> FakeDocumentSnapshot:
        key = (doc_ref._collection_name, doc_ref.id)
        data, ver = self._client._raw_get_with_version(*key)
        # Keep the first version read during this transaction attempt
        self._read_versions.setdefault(key, ver)
        return FakeDocumentSnapshot(doc_ref.id, doc_ref, data)

    def set(
        self,
        reference: FakeDocumentReference,
        document_data: Dict[str, Any],
        merge: bool = False,
    ) -> None:
        self._ops.append(("set", reference, copy.deepcopy(document_data), merge))

    def update(
        self,
        reference: FakeDocumentReference,
        field_updates: Dict[str, Any],
    ) -> None:
        self._ops.append(("update", reference, copy.deepcopy(field_updates), False))

    def delete(self, reference: FakeDocumentReference) -> None:
        self._ops.append(("delete", reference, None, False))

    def _commit(self) -> None:
        self._client._commit_transaction(self._read_versions, self._ops)
        self._clean_up()


class FakeFirestoreClient:
    """Thread-safe in-memory Firestore fake implementing transactional semantics."""

    def __init__(self, *, project: str = "demo-lostlink") -> None:
        self.project = project
        self._lock = threading.RLock()
        self._collections: Dict[str, Dict[str, Dict[str, Any]]] = {}
        self._versions: Dict[Tuple[str, str], int] = {}
        self.before_commit_hook: Optional[Callable[[], None]] = None

    def collection(self, collection_path: str) -> FakeCollectionReference:
        return FakeCollectionReference(self, collection_path)

    def transaction(self, max_attempts: int = 5) -> FakeTransaction:
        return FakeTransaction(self, max_attempts=max_attempts)

    def _snapshot_collection(self, collection_name: str) -> List[Tuple[str, Dict[str, Any]]]:
        with self._lock:
            col = self._collections.get(collection_name, {})
            return [(doc_id, copy.deepcopy(data)) for doc_id, data in col.items()]

    def _raw_get(self, col: str, doc_id: str) -> Optional[Dict[str, Any]]:
        with self._lock:
            doc = self._collections.get(col, {}).get(doc_id)
            return copy.deepcopy(doc) if doc is not None else None

    def _raw_get_with_version(
        self, col: str, doc_id: str
    ) -> Tuple[Optional[Dict[str, Any]], int]:
        with self._lock:
            doc = self._collections.get(col, {}).get(doc_id)
            ver = self._versions.get((col, doc_id), 0)
            return (copy.deepcopy(doc) if doc is not None else None), ver

    def _raw_set(
        self, col: str, doc_id: str, data: Dict[str, Any], *, merge: bool = False
    ) -> None:
        with self._lock:
            resolved = _resolve_sentinels(data)
            bucket = self._collections.setdefault(col, {})
            if merge and doc_id in bucket:
                _deep_merge(bucket[doc_id], resolved)
            else:
                bucket[doc_id] = resolved
            key = (col, doc_id)
            self._versions[key] = self._versions.get(key, 0) + 1

    def _raw_update(self, col: str, doc_id: str, updates: Dict[str, Any]) -> None:
        with self._lock:
            bucket = self._collections.setdefault(col, {})
            if doc_id not in bucket:
                raise api_exceptions.NotFound(f"Document {col}/{doc_id} does not exist.")
            doc = bucket[doc_id]
            now = _now_utc()
            for path, val in updates.items():
                resolved = _resolve_sentinels(val, now=now)
                _set_by_path(doc, path, resolved)
            key = (col, doc_id)
            self._versions[key] = self._versions.get(key, 0) + 1

    def _raw_delete(self, col: str, doc_id: str) -> None:
        with self._lock:
            bucket = self._collections.get(col, {})
            if doc_id in bucket:
                del bucket[doc_id]
                key = (col, doc_id)
                self._versions[key] = self._versions.get(key, 0) + 1

    def _commit_transaction(
        self,
        read_versions: Dict[Tuple[str, str], int],
        ops: List[Tuple[str, FakeDocumentReference, Any, bool]],
    ) -> None:
        if self.before_commit_hook is not None:
            hook = self.before_commit_hook
            self.before_commit_hook = None
            hook()

        with self._lock:
            for key, expected_ver in read_versions.items():
                actual_ver = self._versions.get(key, 0)
                if actual_ver != expected_ver:
                    raise api_exceptions.Aborted(
                        f"Concurrent modification on {key[0]}/{key[1]}"
                    )

            now = _now_utc()
            for op_type, ref, payload, merge in ops:
                col, doc_id = ref._collection_name, ref.id
                bucket = self._collections.setdefault(col, {})
                key = (col, doc_id)
                if op_type == "set":
                    resolved = _resolve_sentinels(payload, now=now)
                    if merge and doc_id in bucket:
                        _deep_merge(bucket[doc_id], resolved)
                    else:
                        bucket[doc_id] = resolved
                    self._versions[key] = self._versions.get(key, 0) + 1
                elif op_type == "update":
                    if doc_id not in bucket:
                        raise api_exceptions.NotFound(
                            f"Document {col}/{doc_id} does not exist."
                        )
                    for path, val in payload.items():
                        resolved = _resolve_sentinels(val, now=now)
                        _set_by_path(bucket[doc_id], path, resolved)
                    self._versions[key] = self._versions.get(key, 0) + 1
                elif op_type == "delete":
                    if doc_id in bucket:
                        del bucket[doc_id]
                    self._versions[key] = self._versions.get(key, 0) + 1


@pytest.fixture(autouse=True)
def _clean_runtime_singletons() -> Iterator[None]:
    """Ensure cached settings, Firebase singleton, wake limiter, and scheduler state don't leak."""
    get_settings.cache_clear()
    reset_firebase_for_tests()
    reset_wake_rate_limiter_for_tests()
    reset_worker_scheduler_for_tests()
    yield
    get_settings.cache_clear()
    reset_firebase_for_tests()
    reset_wake_rate_limiter_for_tests()
    reset_worker_scheduler_for_tests()


@pytest.fixture
def fake_db() -> FakeFirestoreClient:
    """Return an isolated FakeFirestoreClient wired into firebase.get_firestore()."""
    from app.core.firebase import set_firestore_for_tests

    db = FakeFirestoreClient()
    set_firestore_for_tests(db)  # type: ignore[arg-type]
    return db


@pytest.fixture
def test_settings_env(monkeypatch: pytest.MonkeyPatch) -> None:
    """Configure deterministic non-production Settings for unit & API route tests."""
    monkeypatch.setenv("APP_ENV", "test")
    monkeypatch.setenv("USE_FIREBASE_EMULATOR", "true")
    monkeypatch.setenv("FIREBASE_PROJECT_ID", "demo-lostlink")
    monkeypatch.setenv("CLOUDINARY_CLOUD_NAME", "demo-cloud")
    monkeypatch.setenv("CLOUDINARY_API_KEY", "demo-api-key")
    monkeypatch.setenv("CLOUDINARY_API_SECRET", "demo-api-secret")
    monkeypatch.setenv("CLOUDINARY_SIGNATURE_ALGORITHM", "sha256")
    monkeypatch.setenv("BACKEND_API_KEY", "test-wake-secret-key-98765")
    monkeypatch.setenv("GEMINI_API_KEY", "")
    monkeypatch.setenv("RUN_BOOT_RECOVERY", "false")
    get_settings.cache_clear()
