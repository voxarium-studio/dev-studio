"""Application preferences never leak into project config or overwrite its accent (§5.2)."""

import asyncio
from pathlib import Path
from types import SimpleNamespace

import pytest
import yaml
from fastapi import Request
from fastapi.testclient import TestClient
from pydantic import ValidationError
from typer.testing import CliRunner

from voxarium_studio.core.application_config import (
    application_config_file,
    load_application_config,
)
from voxarium_studio.api.project import ApplicationSettingsUpdate, update_application_settings
from voxarium_studio.cli import app as cli_app
from voxarium_studio.core.config import load_config, save_config
from voxarium_studio.core.context import AppContext
from voxarium_studio.core.paths import ProjectPaths
from voxarium_studio.core.project import ensure_project, init_project
from voxarium_studio.core.workspace import initialise_workspace, open_project


def read_yaml(path: Path) -> dict:
    return yaml.safe_load(path.read_text(encoding="utf-8"))


def test_first_legacy_project_seeds_global_preferences_only_once(tmp_path: Path) -> None:
    first = tmp_path / "first"
    first.mkdir()
    paths = init_project(first, with_samples=False)
    paths.config_file.write_text(
        "theme:\n  mode: light\n  accent: '#112233'\n  text_size: 16\n"
        "explorer:\n  show_data_dir: true\n  ignore: [build]\n"
        "updates:\n  enabled: false\nnode_cache_enabled: false\nproject_code: ABC\n",
        encoding="utf-8",
    )

    config = load_config(paths, migrate_application=True)
    assert (config.theme.mode, config.theme.accent) == ("light", "#112233")
    global_file = application_config_file()
    global_bytes = global_file.read_bytes()
    saved = read_yaml(global_file)
    assert saved["theme"]["text_size"] == 16
    assert "accent" not in saved["theme"]
    assert saved["show_data_dir"] is True
    assert saved["updates"]["enabled"] is False
    assert saved["node_cache_enabled"] is False
    assert "project_code" not in saved

    second = tmp_path / "second"
    second.mkdir()
    other = init_project(second, with_samples=False)
    other.config_file.write_text(
        "theme:\n  mode: dark\n  accent: '#445566'\n  text_size: 12\n"
        "project_code: XYZ\nexplorer:\n  ignore: [custom]\n",
        encoding="utf-8",
    )
    other_config = load_config(other)
    assert (other_config.theme.mode, other_config.theme.text_size) == ("light", 16)
    assert other_config.theme.accent == "#445566"
    assert other_config.project_code == "XYZ"
    assert other_config.explorer.ignore == ["custom"]
    assert global_file.read_bytes() == global_bytes

    save_config(paths, config)
    project_data = read_yaml(paths.config_file)
    assert project_data["theme"] == {"accent": "#112233"}
    assert project_data["explorer"] == {"ignore": ["build"]}
    assert project_data["project_code"] == "ABC"
    for key in ("updates", "language", "respond_language", "node_cache_enabled"):
        assert key not in project_data
    assert load_config(paths).theme.mode == "light"


def test_diagnostic_reads_and_cli_info_do_not_migrate_application_preferences(project: Path) -> None:
    paths = ProjectPaths.for_project(project)
    paths.config_file.write_text("theme:\n  mode: light\n  accent: '#123456'\n", encoding="utf-8")
    assert load_config(paths).theme.mode == "light"
    assert not application_config_file().exists()
    result = CliRunner().invoke(cli_app, ["info", str(project)])
    assert result.exit_code == 0, result.output
    assert not application_config_file().exists(), "info is read-only, not a first project open"

    _, opened = ensure_project(project)
    assert opened.theme.mode == "light"
    assert read_yaml(application_config_file())["theme"]["mode"] == "light"


@pytest.mark.parametrize("contents", [None, "not a mapping", "theme: [unterminated"])
def test_missing_or_unreadable_project_cannot_suppress_later_migration(
    tmp_path: Path, contents: str | None,
) -> None:
    paths = ProjectPaths.for_project(tmp_path / "missing-or-unreadable")
    paths.data.mkdir(parents=True)
    if contents is not None:
        paths.config_file.write_text(contents, encoding="utf-8")
    assert load_config(paths, migrate_application=True).theme.mode == "dark"
    assert not application_config_file().exists()

    valid = ProjectPaths.for_project(tmp_path / "valid-legacy")
    valid.data.mkdir(parents=True)
    valid.config_file.write_text("theme:\n  mode: light\n", encoding="utf-8")
    _, opened = ensure_project(valid.root)
    assert opened.theme.mode == "light"
    assert read_yaml(application_config_file())["theme"]["mode"] == "light"


def test_existing_application_settings_ignore_invalid_legacy_global_fields_before_validation(
    project: Path,
) -> None:
    global_file = application_config_file()
    global_file.write_text("theme:\n  mode: light\n  text_size: 17\nlanguage: ru\n", encoding="utf-8")
    global_before = global_file.read_bytes()
    paths = ProjectPaths.for_project(project)
    paths.config_file.write_text(
        "theme:\n  mode: invalid\n  text_size: 999\n  accent: '#123456'\n"
        "updates:\n  check_interval_minutes: -10\n"
        "language: invalid\nnode_cache_enabled: invalid\n"
        "explorer:\n  show_data_dir: invalid\n  ignore: [build]\n"
        "concurrency:\n  agents: 3\nproject_code: ABC\n",
        encoding="utf-8",
    )
    config = load_config(paths, migrate_application=True)
    assert (config.theme.mode, config.theme.text_size, config.language) == ("light", 17, "ru")
    assert config.theme.accent == "#123456"
    assert config.explorer.ignore == ["build"]
    assert config.concurrency.agents == 3
    assert config.project_code == "ABC"
    assert global_file.read_bytes() == global_before
    save_config(paths, config)
    assert read_yaml(paths.config_file)["theme"] == {"accent": "#123456"}

    # Project-owned values still undergo normal validation.
    paths.config_file.write_text("concurrency:\n  agents: 0\n", encoding="utf-8")
    with pytest.raises(ValidationError):
        load_config(paths)


def test_global_save_changes_user_file_without_writing_project(client: TestClient) -> None:
    paths = client.app.state.ctx.paths
    project_before = paths.config_file.read_bytes()
    accent = client.get("/api/settings").json()["theme"]["accent"]
    response = client.put("/api/settings/application", json={
        "theme": {"mode": "light", "font": "inter", "text_size": 17},
        "language": "ru", "show_data_dir": True, "node_cache_enabled": False,
        "updates": {"enabled": False, "check_interval_minutes": 60},
    })
    assert response.status_code == 200
    config = response.json()
    assert config["theme"]["accent"] == accent
    assert config["language"] == "ru"
    assert config["explorer"]["show_data_dir"] is True
    assert paths.config_file.read_bytes() == project_before
    saved = read_yaml(application_config_file())
    assert saved["theme"]["font"] == "inter"
    assert "accent" not in saved["theme"]
    assert saved["language"] == "ru"
    assert load_config(paths).theme.text_size == 17
    assert load_config(paths).node_cache_enabled is False
    assert client.get("/api/project").json()["config"]["language"] == "ru"


def test_response_language_is_free_text_and_independent_of_ui(client: TestClient) -> None:
    paths = client.app.state.ctx.paths
    assert client.get("/api/settings").json()["respond_language"] == "English"
    before = paths.config_file.read_bytes()
    response = client.put("/api/settings/application", json={
        "language": "ru", "respond_language": "Português (Brasil)",
    })
    assert response.status_code == 200
    assert response.json()["respond_language"] == "Português (Brasil)"
    assert response.json()["language"] == "ru"
    assert read_yaml(application_config_file())["respond_language"] == "Português (Brasil)"
    assert load_config(paths).respond_language == "Português (Brasil)"
    assert paths.config_file.read_bytes() == before
    assert client.put("/api/settings/application", json={"language": "en"}).status_code == 200
    assert client.get("/api/settings").json()["respond_language"] == "Português (Brasil)"
    # Project saves must not persist the effective global response preference.
    save_config(paths, client.app.state.ctx.config)
    assert "respond_language" not in read_yaml(paths.config_file)


def test_existing_ui_preference_does_not_set_response_language(project: Path) -> None:
    application_config_file().write_text("language: ru\n", encoding="utf-8")
    config = load_config(ProjectPaths.for_project(project))
    assert config.language == "ru"
    assert config.respond_language == "English"


@pytest.mark.parametrize("patch", [
    {"theme": {"accent": "#123456"}}, {"project_code": "OTHER"},
    {"teams": {"launch_root": "."}}, {"concurrency": {"agents": 7}},
])
def test_global_endpoint_rejects_project_fields(client: TestClient, patch: dict) -> None:
    before = client.get("/api/settings").json()
    assert client.put("/api/settings/application", json=patch).status_code == 422
    assert client.get("/api/settings").json() == before


def test_shared_preferences_update_resident_projects_without_touching_their_data(
    client: TestClient, tmp_path: Path,
) -> None:
    first = client.app.state.ctx
    client.put("/api/settings", json={
        "theme": {"accent": "#112233"}, "project_code": "FIRST",
        "concurrency": {"agents": 3},
    })
    second_root = tmp_path / "another-game"
    second_root.mkdir()
    assert client.post("/api/project/open", json={"path": str(second_root)}).status_code == 200
    second = client.app.state.ctx
    client.put("/api/settings", json={"theme": {"accent": "#445566"}, "project_code": "NEXT"})
    files_before = {ctx.paths.config_file: ctx.paths.config_file.read_bytes() for ctx in (first, second)}

    response = client.put("/api/settings/application", json={
        "theme": {"sidebar": "icons", "mode": "light"}, "language": "ru",
        "respond_language": "日本語",
        "show_data_dir": True, "node_cache_enabled": False, "updates": {"enabled": False},
    })
    assert response.status_code == 200
    for ctx in (first, second):
        assert ctx.config.theme.mode == "light"
        assert ctx.config.theme.sidebar == "icons"
        assert ctx.explorer.config.explorer.show_data_dir is True
        assert ctx.runner.config.node_cache_enabled is False
        assert ctx.config.language == "ru"
        assert ctx.config.respond_language == "日本語"
        assert ctx.updates.config.enabled is False
        assert ctx.paths.config_file.read_bytes() == files_before[ctx.paths.config_file]
        assert load_config(ctx.paths).theme.mode == "light"
    assert first.config.theme.accent == "#112233"
    assert first.config.project_code == "FIRST"
    assert first.jobs.limits["agent"] == 3
    assert second.config.theme.accent == "#445566"
    assert second.config.project_code == "NEXT"
    assert second.jobs.limits["agent"] == 2
    switched = client.post("/api/project/open", json={"path": str(first.paths.root)}).json()
    assert switched["config"]["theme"]["accent"] == "#112233"
    assert switched["config"]["theme"]["mode"] == "light"


async def test_global_save_waits_for_an_in_flight_project_startup(
    ctx: AppContext, tmp_path: Path, monkeypatch: pytest.MonkeyPatch,
) -> None:
    """A context not yet registered must still receive the global save and update policy."""
    state = SimpleNamespace(ctx=ctx, project_lock=asyncio.Lock())
    initialise_workspace(state)
    request = Request({"type": "http", "app": SimpleNamespace(state=state)})
    other = tmp_path / "starting-project"
    other.mkdir()
    started = asyncio.Event()
    resume = asyncio.Event()
    save_started = asyncio.Event()
    startup = AppContext.startup

    async def delayed_startup(replacement: AppContext) -> None:
        started.set()  # The replacement already loaded the old application config.
        await resume.wait()
        await startup(replacement)

    monkeypatch.setattr(AppContext, "startup", delayed_startup)
    opening = asyncio.create_task(open_project(state, str(other)))
    saving = None

    async def save() -> dict:
        save_started.set()
        return await update_application_settings(ApplicationSettingsUpdate(
            theme={"mode": "light"}, language="ru", updates={"enabled": False},
        ), request, ctx)

    try:
        await asyncio.wait_for(started.wait(), timeout=5)
        saving = asyncio.create_task(save())
        await asyncio.wait_for(save_started.wait(), timeout=5)
        await asyncio.sleep(0)
        assert not saving.done(), "a global save must wait for the replacement to be registered"
        assert ctx.config.theme.mode == "dark"
        resume.set()
        replacement, saved = await asyncio.wait_for(asyncio.gather(opening, saving), timeout=10)
        assert saved["language"] == "ru"
        assert state.ctx is replacement
        for resident in (ctx, replacement):
            assert resident.config.theme.mode == "light"
            assert resident.config.language == "ru"
            assert resident.config.updates.enabled is False
            assert resident.updates.config.enabled is False
    finally:
        resume.set()
        await asyncio.gather(opening, *([saving] if saving is not None else []), return_exceptions=True)
        for resident in state.project_contexts.values():
            if resident is not ctx:
                await resident.shutdown()


@pytest.mark.parametrize("invalid", [
    {"teams": {"launch_root": "does-not-exist"}},
    {"teams": {"commit_backend": "unknown-backend"}},
    {"project_code": "123"},
])
def test_invalid_mixed_patch_leaves_both_files_and_live_state_unchanged(
    client: TestClient, invalid: dict,
) -> None:
    before = client.get("/api/settings").json()
    ctx = client.app.state.ctx
    limits_before = dict(ctx.jobs.limits)
    files = (ctx.paths.config_file, application_config_file())
    saved = {path: path.read_bytes() for path in files}
    response = client.put("/api/settings", json={
        "theme": {"mode": "light", "accent": "#123456"},
        "concurrency": {"agents": 7}, "language": "ru", **invalid,
    })
    assert response.status_code == 400
    assert client.get("/api/settings").json() == before
    assert dict(ctx.jobs.limits) == limits_before
    assert all(path.read_bytes() == saved[path] for path in files)


def test_legacy_mixed_patch_routes_fields_and_merges_global_preferences(client: TestClient) -> None:
    response = client.put("/api/settings", json={
        "theme": {"mode": "light", "accent": "#123456"}, "project_code": "TASK",
        "language": "ru", "show_data_dir": True,
    })
    assert response.status_code == 200
    client.put("/api/settings/application", json={"theme": {"text_size": None}, "language": None})
    project_data = read_yaml(client.app.state.ctx.paths.config_file)
    global_data = read_yaml(application_config_file())
    assert project_data["theme"] == {"accent": "#123456"}
    assert project_data["project_code"] == "TASK"
    assert "show_data_dir" not in project_data["explorer"]
    assert global_data["theme"]["mode"] == "light"
    assert global_data["theme"]["text_size"] is None
    assert global_data["language"] is None
    assert global_data["show_data_dir"] is True


@pytest.mark.parametrize("text", ["{}", "not: [valid", "theme:\n  text_size: 999\n"])
def test_existing_global_file_is_never_reseeded_from_another_project(
    project: Path, text: str,
) -> None:
    global_file = application_config_file()
    global_file.write_text(text, encoding="utf-8")
    paths = ProjectPaths.for_project(project)
    paths.config_file.write_text("theme:\n  mode: light\n  accent: '#123456'\n", encoding="utf-8")
    config = load_config(paths)
    assert config.theme.mode == "dark"
    assert config.theme.accent == "#123456"
    assert global_file.read_text(encoding="utf-8") == text
    assert load_application_config().theme.mode == "dark"


@pytest.mark.parametrize("contents", [b"\x80broken utf8", "theme:\n  mode: light\n".encode("utf-16")])
@pytest.mark.parametrize("target", ["application", "project"])
def test_invalid_config_encoding_preserves_bytes_and_never_seeds_defaults(
    project: Path, contents: bytes, target: str,
) -> None:
    paths = ProjectPaths.for_project(project)
    paths.config_file.write_text("theme:\n  mode: light\n  accent: '#123456'\n", encoding="utf-8")
    damaged = application_config_file() if target == "application" else paths.config_file
    damaged.write_bytes(contents)

    config = load_config(paths, migrate_application=True)
    assert config.theme.mode == "dark"
    assert damaged.read_bytes() == contents
    if target == "application":
        assert config.theme.accent == "#123456"
        assert load_application_config().theme.mode == "dark"
    else:
        assert not application_config_file().exists(), "unreadable project data cannot seed user defaults"
