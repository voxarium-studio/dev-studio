"""User-wide settings, stored beside the plugin registry and recent projects (§5.2)."""

from __future__ import annotations

import logging
import tempfile
from pathlib import Path
from threading import RLock
from typing import Literal

import yaml
from pydantic import BaseModel, Field, ValidationError

from .config import AppConfig, ApplicationThemeConfig, ThemeConfig, UpdatesConfig
from .paths import user_config_dir

log = logging.getLogger(__name__)
_file_lock = RLock()


class ApplicationConfig(BaseModel):
    theme: ApplicationThemeConfig = Field(default_factory=ApplicationThemeConfig)
    updates: UpdatesConfig = Field(default_factory=UpdatesConfig)
    show_data_dir: bool = False
    node_cache_enabled: bool = True
    language: Literal["en", "ru"] | None = None
    respond_language: str = "English"

    @classmethod
    def from_effective(cls, config: AppConfig) -> ApplicationConfig:
        """Seed old preferences once; the accent never leaves its project."""
        return cls(
            theme=ApplicationThemeConfig.model_validate(
                config.theme.model_dump(exclude={"accent"})
            ),
            updates=config.updates.model_copy(deep=True),
            show_data_dir=config.explorer.show_data_dir,
            node_cache_enabled=config.node_cache_enabled,
            language=config.language,
            respond_language=config.respond_language,
        )


def application_config_file() -> Path:
    return user_config_dir() / "config.yaml"


def load_application_config(
    *, legacy: AppConfig | None = None, migrate: bool = False,
) -> ApplicationConfig:
    """Read global preferences; only an explicit project open may persist migration.

    An empty or malformed existing file is still an application file: another project's
    old values must never take over. A bad file is kept untouched until an explicit save.
    """
    with _file_lock:
        path = application_config_file()
        if not path.exists():
            config = ApplicationConfig.from_effective(legacy) if legacy else ApplicationConfig()
            if migrate and legacy is not None:
                save_application_config(config)
            return config
        try:
            raw = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
            return ApplicationConfig.model_validate(raw)
        except (OSError, UnicodeError, yaml.YAMLError, ValidationError) as exc:
            log.warning("Could not read application settings %s (%s); using defaults", path, exc)
            return ApplicationConfig()


def save_application_config(config: ApplicationConfig) -> None:
    """Replace the user file atomically; never write a managed project's config."""
    with _file_lock:
        path = application_config_file()
        path.parent.mkdir(parents=True, exist_ok=True)
        text = yaml.safe_dump(config.model_dump(mode="json"), sort_keys=False, allow_unicode=True)
        temporary: Path | None = None
        try:
            with tempfile.NamedTemporaryFile(
                mode="w", encoding="utf-8", dir=path.parent, prefix=".config-", suffix=".tmp",
                delete=False,
            ) as stream:
                temporary = Path(stream.name)
                stream.write(text)
            temporary.replace(path)
        finally:
            if temporary is not None:
                temporary.unlink(missing_ok=True)


def apply_application_config(config: AppConfig, application: ApplicationConfig) -> None:
    """Update the shared effective object so live services see global preferences (§5.2)."""
    config.theme = ThemeConfig.model_validate({
        **application.theme.model_dump(), "accent": config.theme.accent,
    })
    config.updates = application.updates.model_copy(deep=True)
    config.explorer.show_data_dir = application.show_data_dir
    config.node_cache_enabled = application.node_cache_enabled
    config.language = application.language
    config.respond_language = application.respond_language
