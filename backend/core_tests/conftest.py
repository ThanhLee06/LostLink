"""Core-only tests must not import feature fixtures or local dotenv configuration."""
import pytest

from app.core.config import Settings, get_settings

Settings.model_config["env_file"] = None
get_settings.cache_clear()


@pytest.fixture(autouse=True)
def isolated_core_environment(monkeypatch):
    monkeypatch.setenv("APP_ENV", "test")
    monkeypatch.setenv("USE_FIREBASE_EMULATOR", "true")
    monkeypatch.setenv("FIREBASE_PROJECT_ID", "demo-lostlink")
    monkeypatch.setenv("GOOGLE_APPLICATION_CREDENTIALS", "")
    get_settings.cache_clear()
    yield
    get_settings.cache_clear()
