"""
app/core/config.py — Application settings loaded from environment variables.
"""
from __future__ import annotations

from functools import lru_cache
from pathlib import Path
from typing import List, Literal, Optional

from pydantic import Field, field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict

_BACKEND_DIR = Path(__file__).resolve().parents[2]
_PLACEHOLDER_API_KEYS = frozenset({"", "change_me_random_string", "changeme"})
_PLACEHOLDER_GEMINI_KEYS = frozenset({"", "your_gemini_api_key", "changeme", "change_me"})


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=str(_BACKEND_DIR / ".env"),
        env_file_encoding="utf-8",
        case_sensitive=False,
        extra="ignore",
    )

    # Runtime environment
    app_env: Literal["development", "test", "production"] = "development"
    log_level: str = "INFO"

    # Firebase
    firebase_project_id: str = "demo-lostlink"
    google_application_credentials: str = ""

    # Firebase Emulator
    use_firebase_emulator: bool = False
    firebase_auth_emulator_host: str = "localhost:9099"
    firestore_emulator_host: str = "localhost:8088"

    # Cloudinary
    cloudinary_cloud_name: str = ""
    cloudinary_api_key: str = ""
    cloudinary_api_secret: str = ""
    cloudinary_signature_algorithm: Literal["sha1", "sha256"] = "sha1"
    cloudinary_upload_preset: str = ""

    # Gemini & Embeddings
    gemini_api_key: str = ""
    embedding_model: str = "gemini-embedding-001"
    embedding_model_revision: str = "2026-01"
    embedding_dimensions: int = Field(default=768, gt=0)
    embedding_index_version: int = Field(default=1, ge=1)

    # Backend internal
    backend_api_key: str = "change_me_random_string"

    # CORS — comma-separated list of allowed origins
    cors_origins: str = "http://localhost:3000"

    # Rate limit, media TTL & worker tuning
    wake_rate_limit_max: int = Field(default=10, ge=1)
    wake_rate_limit_window_seconds: int = Field(default=60, ge=1)
    worker_batch_limit: int = Field(default=10, ge=1, le=100)
    worker_lease_seconds: int = Field(default=60, ge=5)
    worker_poll_min_seconds: float = Field(default=5.0, ge=0.2)
    worker_poll_max_seconds: float = Field(default=60.0, ge=1.0)
    media_abandoned_ttl_seconds: int = Field(default=86400, ge=60)
    media_signed_upload_window_seconds: int = Field(default=3600, ge=60)
    run_boot_recovery: bool = True

    @field_validator("app_env", "cloudinary_signature_algorithm", mode="before")
    @classmethod
    def _normalize_lower(cls, value: object) -> object:
        if isinstance(value, str):
            return value.strip().lower()
        return value

    @field_validator("log_level", mode="before")
    @classmethod
    def _normalize_log_level(cls, value: object) -> object:
        if isinstance(value, str):
            return value.strip().upper()
        return value

    @property
    def cors_origins_list(self) -> List[str]:
        return [o.strip() for o in self.cors_origins.split(",") if o.strip()]

    @property
    def effective_backend_api_key(self) -> Optional[str]:
        """Return configured API key, or None if unset/still a placeholder."""
        cleaned = self.backend_api_key.strip()
        if cleaned.lower() in _PLACEHOLDER_API_KEYS:
            return None
        return cleaned

    @property
    def effective_gemini_api_key(self) -> Optional[str]:
        """Return configured Gemini API key, or None if unset/still a placeholder."""
        cleaned = self.gemini_api_key.strip()
        if cleaned.lower() in _PLACEHOLDER_GEMINI_KEYS:
            return None
        return cleaned


@lru_cache(maxsize=1)
def get_settings() -> Settings:
    """Return cached Settings singleton."""
    return Settings()
