"""
app/core/auth.py — FastAPI authentication & authorization dependencies.

Principles:
- Identity is ALWAYS established from a verified Firebase ID token
  (or constant-time `X-Backend-Api-Key` for /events/wake internal triggers).
  Client-supplied uids are NEVER trusted as identity.
- `verify_token`: calls `firebase_admin.auth.verify_id_token(token, check_revoked=True)`.
  NOTE: On the Firebase Auth Emulator, `check_revoked=True` is accepted by the
  SDK, but the emulator does not enforce a revocation index — revocation must
  be unit-tested via mocks or verified against production Firebase Auth.
"""
from __future__ import annotations

import asyncio
import hmac
import logging
import time
from typing import TYPE_CHECKING, Any, Dict, Optional

import firebase_admin.auth as fb_auth
from fastapi import Depends, Header, status
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer

if TYPE_CHECKING:
    from google.cloud.firestore import Client as FirestoreClient

from app.core.config import get_settings
from app.core.errors import ApiHTTPException
from app.core.firebase import get_firestore
from app.schemas.account import AccountStateStatus

logger = logging.getLogger(__name__)

_bearer_scheme = HTTPBearer(auto_error=False)
_STUDENT_DOMAIN = "@ut.edu.vn"


async def _extract_token(
    credentials: HTTPAuthorizationCredentials | None = Depends(_bearer_scheme),
) -> str:
    """Extract raw Bearer token from Authorization header."""
    if credentials is None or not credentials.credentials:
        raise ApiHTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Missing or invalid Authorization header.",
            code="MISSING_TOKEN",
        )
    return credentials.credentials


def parse_account_state_snapshot(snap: Any, *, uid: str = "") -> AccountStateStatus:
    """
    Parse an account_states/{uid} DocumentSnapshot authoritatively.

    - Missing document is treated as ACTIVE (backward compat for legacy accounts).
    - Existing document with missing, null, empty/whitespace, non-string, or
      unrecognized status fails closed as DELETING.
    """
    if not getattr(snap, "exists", False):
        return AccountStateStatus.ACTIVE

    data = snap.to_dict()
    if not isinstance(data, dict) or "status" not in data:
        logger.warning(
            "Existing account_states doc missing 'status' for uid=%s; failing closed",
            uid or getattr(snap, "id", "unknown"),
        )
        return AccountStateStatus.DELETING

    raw_status = data.get("status")
    if not isinstance(raw_status, str) or not raw_status.strip():
        logger.warning(
            "Existing account_states doc has null/empty 'status' for uid=%s; failing closed",
            uid or getattr(snap, "id", "unknown"),
        )
        return AccountStateStatus.DELETING

    try:
        return AccountStateStatus(raw_status.strip().upper())
    except ValueError:
        logger.warning(
            "Unknown account_states status for uid=%s; failing closed",
            uid or getattr(snap, "id", "unknown"),
        )
        return AccountStateStatus.DELETING


def get_account_status(db: FirestoreClient, uid: str) -> AccountStateStatus:
    """Look up account_states/{uid} using fail-closed snapshot parsing."""
    doc = db.collection("account_states").document(uid).get()
    return parse_account_state_snapshot(doc, uid=uid)


async def verify_token(token: str = Depends(_extract_token)) -> Dict[str, Any]:
    """
    Verify a Firebase ID token with check_revoked=True and validate non-empty uid.
    """
    try:
        decoded: Dict[str, Any] = await asyncio.to_thread(
            fb_auth.verify_id_token, token, check_revoked=True
        )
    except fb_auth.UserDisabledError:
        raise ApiHTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="User account is disabled.",
            code="USER_DISABLED",
        )
    except fb_auth.RevokedIdTokenError:
        raise ApiHTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Token has been revoked.",
            code="TOKEN_REVOKED",
        )
    except fb_auth.ExpiredIdTokenError:
        raise ApiHTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Token has expired.",
            code="TOKEN_EXPIRED",
        )
    except fb_auth.InvalidIdTokenError:
        raise ApiHTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid ID token.",
            code="INVALID_TOKEN",
        )
    except Exception as exc:
        logger.warning("Token verification failed (%s)", type(exc).__name__)
        raise ApiHTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Could not verify token.",
            code="AUTH_FAILED",
        )

    uid = decoded.get("uid") if isinstance(decoded, dict) else None
    if not isinstance(uid, str) or not uid.strip():
        raise ApiHTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Token is missing a valid uid.",
            code="INVALID_TOKEN",
        )
    return decoded


async def require_verified_student(
    decoded: Dict[str, Any] = Depends(verify_token),
) -> Dict[str, Any]:
    """
    Require verified @ut.edu.vn email.
    Permits DELETING accounts so /api/v1/account/* status/resume remains accessible.
    """
    email = decoded.get("email", "")
    email_verified = decoded.get("email_verified") is True

    if not email_verified:
        raise ApiHTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Email address is not verified.",
            code="EMAIL_NOT_VERIFIED",
        )

    cleaned_email = email.strip().lower() if isinstance(email, str) else ""
    if not cleaned_email.endswith(_STUDENT_DOMAIN) or len(cleaned_email) <= len(
        _STUDENT_DOMAIN
    ):
        raise ApiHTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail=f"Only {_STUDENT_DOMAIN} accounts are allowed.",
            code="DOMAIN_NOT_ALLOWED",
        )

    return decoded


async def require_active_student(
    decoded: Dict[str, Any] = Depends(require_verified_student),
) -> Dict[str, Any]:
    """
    Require verified @ut.edu.vn student whose account_states/{uid} is ACTIVE.
    Rejects DELETING or DELETED accounts with 403.
    """
    uid: str = decoded["uid"]
    db = get_firestore()
    account_status = await asyncio.to_thread(get_account_status, db, uid)
    if account_status != AccountStateStatus.ACTIVE:
        raise ApiHTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Account is scheduled for deletion and cannot perform this action.",
            code="ACCOUNT_INACTIVE",
        )
    return decoded


async def require_admin(
    decoded: Dict[str, Any] = Depends(verify_token),
) -> Dict[str, Any]:
    """Require strict Firebase custom claim admin=True."""
    if decoded.get("admin") is not True:
        raise ApiHTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Admin privileges required.",
            code="ADMIN_REQUIRED",
        )
    return decoded


def require_recent_auth(max_age_seconds: int = 300):
    """
    Dependency factory requiring recent authentication (`auth_time` within max_age_seconds).
    Used for sensitive operations (e.g. account deletion), NOT search/wake.
    """

    async def _guard(
        decoded: Dict[str, Any] = Depends(verify_token),
    ) -> Dict[str, Any]:
        auth_time = decoded.get("auth_time")
        if (
            isinstance(auth_time, bool)
            or not isinstance(auth_time, (int, float))
            or auth_time <= 0
            or (time.time() - float(auth_time)) > max_age_seconds
        ):
            raise ApiHTTPException(
                status_code=status.HTTP_403_FORBIDDEN,
                detail=(
                    f"Re-authentication required. "
                    f"Please sign in again within the last {max_age_seconds} seconds."
                ),
                code="RECENT_AUTH_REQUIRED",
            )
        return decoded

    return _guard


async def require_wake_caller(
    x_backend_api_key: Optional[str] = Header(default=None, alias="X-Backend-Api-Key"),
    credentials: HTTPAuthorizationCredentials | None = Depends(_bearer_scheme),
) -> Dict[str, Any]:
    """
    Authorize /api/v1/events/wake via either:
    1. Configured X-Backend-Api-Key (constant-time hmac.compare_digest; placeholders rejected)
    2. Verified ACTIVE @ut.edu.vn student Bearer token
    """
    if x_backend_api_key is not None:
        expected = get_settings().effective_backend_api_key
        if expected is not None and hmac.compare_digest(
            x_backend_api_key.strip(), expected
        ):
            return {"uid": "system:wake", "authType": "api_key"}
        raise ApiHTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid backend API key.",
            code="INVALID_API_KEY",
        )

    if credentials is not None and credentials.credentials:
        decoded = await verify_token(credentials.credentials)
        decoded = await require_verified_student(decoded)
        return await require_active_student(decoded)

    raise ApiHTTPException(
        status_code=status.HTTP_401_UNAUTHORIZED,
        detail="Missing authentication credentials.",
        code="MISSING_CREDENTIALS",
    )
