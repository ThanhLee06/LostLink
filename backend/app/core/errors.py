"""
app/core/errors.py — Request trace-ID middleware and safe JSON error handlers.

Security guarantees:
- Every response includes an `X-Trace-Id` header.
- Errors serialize cleanly as `ErrorResponse` (`detail`, `code`, `traceId`).
- NEVER log or return Authorization headers, tokens, credentials, or raw stack traces.
"""
from __future__ import annotations

import logging
import re
import time
import uuid
from contextvars import ContextVar
from typing import Any, Dict, Optional

from fastapi import FastAPI, HTTPException, Request, status
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from starlette.exceptions import HTTPException as StarletteHTTPException
from starlette.middleware.base import BaseHTTPMiddleware
from starlette.responses import Response

from app.schemas.common import ErrorResponse

logger = logging.getLogger(__name__)

_TRACE_ID_PATTERN = re.compile(r"^[A-Za-z0-9._-]{1,64}$")
_trace_id_var: ContextVar[str] = ContextVar("trace_id", default="")

_SENSITIVE_PATTERNS = (
    (re.compile(r"\bBearer\s+[A-Za-z0-9\-._~+/]+=*", re.IGNORECASE), "Bearer [REDACTED]"),
    (re.compile(r"\beyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]*"), "[REDACTED_JWT]"),
    (re.compile(r"\bAIza[0-9A-Za-z\-_]{16,}"), "[REDACTED_API_KEY]"),
    (re.compile(r"(://)[^\s/@:]+:[^\s/@]+@"), r"\1[REDACTED]@"),
    (
        re.compile(
            r"(?i)([\"']?\b(?:api_key|apikey|x-backend-api-key|gemini_api_key|cloudinary_api_secret|authorization|password|passwd|secret|token|signature)\b[\"']?\s*[:=]\s*)(?:\"[^\"]*\"|'[^']*'|[^\s,&;}\]]+)"
        ),
        r"\1[REDACTED]",
    ),
    (
        re.compile(r"(?i)([?&]key=)[^\s&#]+"),
        r"\1[REDACTED]",
    ),
)


def redact_sensitive_text(text: Optional[str], max_length: int = 500) -> str:
    """Strip credentials, tokens, API keys, and DSN passwords before logging or persisting."""
    if not text:
        return ""
    cleaned = str(text)
    for pattern, replacement in _SENSITIVE_PATTERNS:
        cleaned = pattern.sub(replacement, cleaned)
    cleaned = re.sub(r"[\r\n]+", " ", cleaned).strip()
    if max_length > 0 and len(cleaned) > max_length:
        return cleaned[:max_length]
    return cleaned


def get_current_trace_id() -> str:
    """Return the active request trace ID if inside a request context."""
    return _trace_id_var.get()


def _resolve_trace_id(request: Request) -> str:
    existing = getattr(request.state, "trace_id", None)
    if isinstance(existing, str) and existing:
        return existing
    raw_header = request.headers.get("X-Trace-Id", "").strip()
    if raw_header and _TRACE_ID_PATTERN.match(raw_header):
        return raw_header
    return str(uuid.uuid4())


class ApiHTTPException(HTTPException):
    """HTTPException carrying an optional machine-readable error code."""

    def __init__(
        self,
        status_code: int,
        detail: str,
        *,
        code: Optional[str] = None,
        headers: Optional[Dict[str, str]] = None,
    ) -> None:
        super().__init__(status_code=status_code, detail=detail, headers=headers)
        self.code = code


class TraceIdMiddleware(BaseHTTPMiddleware):
    """Attach X-Trace-Id to every request/response and log safe request metadata."""

    async def dispatch(self, request: Request, call_next: Any) -> Response:
        trace_id = _resolve_trace_id(request)
        request.state.trace_id = trace_id
        token = _trace_id_var.set(trace_id)
        started = time.perf_counter()
        try:
            response = await call_next(request)
        except Exception:
            # Ensure trace_id remains attached until exception handlers respond
            raise
        else:
            duration_ms = (time.perf_counter() - started) * 1000.0
            response.headers["X-Trace-Id"] = trace_id
            logger.info(
                "http_request method=%s path=%s status=%d durationMs=%.1f traceId=%s",
                request.method,
                request.url.path,
                response.status_code,
                duration_ms,
                trace_id,
            )
            return response
        finally:
            _trace_id_var.reset(token)


def _error_json_response(
    *,
    status_code: int,
    detail: str,
    code: Optional[str],
    trace_id: str,
    headers: Optional[Dict[str, str]] = None,
) -> JSONResponse:
    payload = ErrorResponse(detail=detail, code=code, trace_id=trace_id).model_dump(
        mode="json"
    )
    merged_headers = dict(headers or {})
    merged_headers["X-Trace-Id"] = trace_id
    return JSONResponse(
        status_code=status_code,
        content=payload,
        headers=merged_headers,
    )


def register_exception_handlers(app: FastAPI) -> None:
    """Register global exception handlers for consistent ErrorResponse payloads."""

    @app.exception_handler(ApiHTTPException)
    async def _api_http_exception_handler(
        request: Request, exc: ApiHTTPException
    ) -> JSONResponse:
        trace_id = _resolve_trace_id(request)
        detail = exc.detail if isinstance(exc.detail, str) else "Request failed."
        return _error_json_response(
            status_code=exc.status_code,
            detail=detail,
            code=exc.code,
            trace_id=trace_id,
            headers=exc.headers,
        )

    @app.exception_handler(StarletteHTTPException)
    async def _http_exception_handler(
        request: Request, exc: StarletteHTTPException
    ) -> JSONResponse:
        trace_id = _resolve_trace_id(request)
        detail = exc.detail if isinstance(exc.detail, str) else "Request failed."
        code = getattr(exc, "code", None)
        return _error_json_response(
            status_code=exc.status_code,
            detail=detail,
            code=code,
            trace_id=trace_id,
            headers=getattr(exc, "headers", None),
        )

    @app.exception_handler(RequestValidationError)
    async def _validation_exception_handler(
        request: Request, exc: RequestValidationError
    ) -> JSONResponse:
        trace_id = _resolve_trace_id(request)
        # Emit field locations and messages ONLY — never echo raw secret/header values.
        parts = []
        for err in exc.errors():
            loc = ".".join(str(p) for p in err.get("loc", ()) if p != "body")
            msg = str(err.get("msg", "Invalid value"))
            parts.append(f"{loc}: {msg}" if loc else msg)
        detail = "; ".join(parts) if parts else "Request validation failed."
        return _error_json_response(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=detail,
            code="VALIDATION_ERROR",
            trace_id=trace_id,
        )

    @app.exception_handler(Exception)
    async def _unhandled_exception_handler(
        request: Request, exc: Exception
    ) -> JSONResponse:
        trace_id = _resolve_trace_id(request)
        logger.error(
            "unhandled_exception method=%s path=%s excType=%s traceId=%s",
            request.method,
            request.url.path,
            type(exc).__name__,
            trace_id,
        )
        return _error_json_response(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail="Internal server error.",
            code="INTERNAL_ERROR",
            trace_id=trace_id,
        )
