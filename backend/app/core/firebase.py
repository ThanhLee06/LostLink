"""
app/core/firebase.py — Firebase Admin SDK initialisation (singleton, emulator-aware).

Rules:
- Initialised once at startup via `init_firebase()`.
- In `app_env == "production"` (or whenever `USE_FIREBASE_EMULATOR=false`), any
  emulator configuration or emulator env vars (`FIREBASE_AUTH_EMULATOR_HOST`,
  `FIRESTORE_EMULATOR_HOST`) fail fast with `RuntimeError`.
- In emulator mode without a local credential file, falls back to
  `google.auth.credentials.AnonymousCredentials` so local emulator/dev workflows
  do not require GCP Application Default Credentials.
"""
from __future__ import annotations

import logging
import os
from contextlib import suppress
from typing import TYPE_CHECKING, Any

import firebase_admin
from firebase_admin import credentials
from google.auth.credentials import AnonymousCredentials

if TYPE_CHECKING:
    from google.cloud.firestore import Client as FirestoreClient

from app.core.config import get_settings

logger = logging.getLogger(__name__)

_app: firebase_admin.App | None = None
_firestore_client: FirestoreClient | None = None


class _EmulatorAnonymousCredential(credentials.Base):
    """Credential adapter using AnonymousCredentials for Firebase Emulator mode."""

    def get_credential(self) -> Any:
        return AnonymousCredentials()


def init_firebase() -> None:
    """Initialise Firebase Admin SDK once, enforcing cloud/emulator isolation."""
    global _app, _firestore_client

    if _app is not None:
        return

    settings = get_settings()
    auth_emu_env = os.environ.get("FIREBASE_AUTH_EMULATOR_HOST", "").strip()
    fs_emu_env = os.environ.get("FIRESTORE_EMULATOR_HOST", "").strip()

    if settings.app_env == "production":
        if settings.use_firebase_emulator or auth_emu_env or fs_emu_env:
            raise RuntimeError(
                "Firebase emulator settings and env vars are forbidden when app_env='production'."
            )
    elif not settings.use_firebase_emulator and (auth_emu_env or fs_emu_env):
        raise RuntimeError(
            "Emulator env vars (FIREBASE_AUTH_EMULATOR_HOST / FIRESTORE_EMULATOR_HOST) "
            "cannot be set when USE_FIREBASE_EMULATOR=false."
        )

    if settings.use_firebase_emulator:
        logger.info("Firebase emulator mode enabled.")
        os.environ.setdefault(
            "FIREBASE_AUTH_EMULATOR_HOST", settings.firebase_auth_emulator_host
        )
        os.environ.setdefault(
            "FIRESTORE_EMULATOR_HOST", settings.firestore_emulator_host
        )

    cred_path = settings.google_application_credentials.strip()
    if cred_path and os.path.isfile(cred_path):
        cred: credentials.Base = credentials.Certificate(cred_path)
        logger.info("Firebase: using service account credential file")
    elif settings.use_firebase_emulator:
        cred = _EmulatorAnonymousCredential()
        logger.info("Firebase: using AnonymousCredentials for emulator mode")
    else:
        cred = credentials.ApplicationDefault()
        logger.info("Firebase: using Application Default Credentials")

    from firebase_admin import firestore as admin_firestore

    app_instance = firebase_admin.initialize_app(
        cred,
        {"projectId": settings.firebase_project_id},
    )
    try:
        client_instance = admin_firestore.client(app_instance)
    except Exception:
        with suppress(Exception):
            firebase_admin.delete_app(app_instance)
        raise

    _app = app_instance
    _firestore_client = client_instance
    logger.info(
        "Firebase Admin SDK initialised (project=%s, emulator=%s, env=%s)",
        settings.firebase_project_id,
        settings.use_firebase_emulator,
        settings.app_env,
    )


def get_firestore() -> FirestoreClient:
    """Return the Firestore client. Raises RuntimeError if not yet initialised."""
    if _firestore_client is None:
        raise RuntimeError(
            "Firestore client is not initialised. Call init_firebase() first."
        )
    return _firestore_client


def get_firebase_app() -> firebase_admin.App:
    """Return the Firebase Admin app. Raises RuntimeError if not yet initialised."""
    if _app is None:
        raise RuntimeError(
            "Firebase app is not initialised. Call init_firebase() first."
        )
    return _app


def set_firestore_for_tests(client: FirestoreClient | None) -> None:
    """Override the Firestore client singleton in isolated tests."""
    global _firestore_client
    _firestore_client = client


def reset_firebase_for_tests() -> None:
    """Reset Firebase Admin singleton and cached settings cleanly between tests."""
    global _app, _firestore_client
    if _app is not None:
        with suppress(Exception):
            firebase_admin.delete_app(_app)
    _app = None
    _firestore_client = None
    get_settings.cache_clear()
