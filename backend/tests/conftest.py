"""Shared fixtures.

Every test gets its own managed project and application home under ``tmp_path`` (§5.2):
database, config, user preferences, pipelines, roster and artifacts are all isolated.

``asyncio_mode = "auto"`` is set in ``pyproject.toml``, so async tests need no decorator.
"""

from __future__ import annotations

from collections.abc import AsyncIterator, Iterator
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from voxarium_studio.core import context as context_module
from voxarium_studio.core.config import AppConfig, load_config
from voxarium_studio.core.context import AppContext
from voxarium_studio.core.project import init_project
from voxarium_studio.core.security import AuthSettings
from voxarium_studio.main import create_app
from voxarium_studio.plugins import loader as plugin_loader
from voxarium_studio.plugins.registry import Registry
from voxarium_studio.updates import UpdateService


@pytest.fixture(autouse=True)
def agent_skills_home(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> Path:
    """Installed skill catalogs must never depend on the developer's real home."""
    from voxarium_studio.teams import installed_skills

    home = tmp_path / "agent-home"
    home.mkdir()
    monkeypatch.setattr(installed_skills, "user_home", lambda: home)
    monkeypatch.setattr(installed_skills, "admin_skill_roots", lambda: ())
    monkeypatch.delenv("CODEX_HOME", raising=False)
    monkeypatch.delenv("CLAUDE_CONFIG_DIR", raising=False)
    return home


@pytest.fixture(autouse=True)
def studio_home(tmp_path: Path) -> Iterator[Path]:
    """Isolate user settings, the plugin registry and recent projects for each test.

    Without this, starting the app writes every throwaway `tmp_path` project into the
    developer's real recent-projects list, and the globally installed plugins on the
    machine would load into tests that never asked for them.
    """
    import os

    from voxarium_studio.core.paths import HOME_ENV_VAR

    home = tmp_path / "studio-home"
    home.mkdir()
    previous = os.environ.get(HOME_ENV_VAR)
    os.environ[HOME_ENV_VAR] = str(home)
    try:
        yield home
    finally:
        if previous is None:
            os.environ.pop(HOME_ENV_VAR, None)
        else:
            os.environ[HOME_ENV_VAR] = previous


@pytest.fixture(autouse=True)
def no_machine_version_control(
    request: pytest.FixtureRequest, monkeypatch: pytest.MonkeyPatch
) -> None:
    """Answer "under no version control" without asking the machine.

    A `tmp_path` project is under nothing, and every `AppContext` spends two external
    processes finding that out: `git rev-parse` from :func:`is_git_repo`, then the
    detection pass in :meth:`AppContext.detect_vcs`, which walks the registered backends
    in turn. On a machine with Unity Version Control installed that second one alone
    costs ~150ms, because `cm getworkspacefrompath` is a .NET process start. Multiplied
    by the several hundred contexts this suite builds, it was most of the run.

    Determinism is the better reason. Which backends get probed depends on what happens
    to be installed on the developer's machine, so the suite was doing different work —
    and, for anything that reads `ctx.vcs`, potentially reaching a different answer — on
    two checkouts of the same commit. Pinning it here makes every test that does not care
    about version control see the same project.

    Tests that *do* care mark themselves `@pytest.mark.real_vcs` and get the real probes;
    `tests/test_vcs.py` marks its whole module.
    """
    if request.node.get_closest_marker("real_vcs"):
        return

    async def no_backend(*_args: object, **_kwargs: object) -> None:
        return None

    monkeypatch.setattr(context_module, "detect_backend", no_backend)
    monkeypatch.setattr(context_module, "is_git_repo", lambda _root: False)


@pytest.fixture(autouse=True)
def no_graphify_cli(request: pytest.FixtureRequest, monkeypatch: pytest.MonkeyPatch) -> None:
    """Answer "graphify is not installed" without asking the machine.

    The built-in `graphify` plugin indexes the project in the background the moment a
    context starts (§9.7). On a machine with the CLI installed that is a real
    `graphify extract` per `ctx` and per `client` — several hundred per run, each writing a
    `graphify-out/` into a throwaway project. Same argument as `no_machine_version_control`:
    the suite should do the same work on every checkout. The hooks still run — a missing
    CLI is a path the plugin has to handle — they just find nothing to start.

    Tests that mean the CLI mark themselves `@pytest.mark.real_graphify`.
    """
    if request.node.get_closest_marker("real_graphify"):
        return

    import shutil

    real_which = shutil.which

    def which(cmd, *args, **kwargs):
        if Path(str(cmd)).stem.lower() == "graphify":
            return None
        return real_which(cmd, *args, **kwargs)

    monkeypatch.setattr(shutil, "which", which)


@pytest.fixture(autouse=True)
def no_plugin_env_builds(request: pytest.FixtureRequest, monkeypatch: pytest.MonkeyPatch) -> None:
    """Never let a test build a plugin's isolated environment (§2.1).

    A started context builds the environment of every enabled plugin that declares one, and
    `image_ops` is enabled by default: without this, every `ctx` and `client` would run `uv`
    to install Pillow into `plugins_builtin/image_ops/.venv` — inside this checkout — and
    every pipeline test with one of its nodes would wait for the download. With `schedule`
    answering "nothing to do", nodes run on whatever interpreter they find, as they did
    before environments built themselves.

    The build state is per process, so it is also reset for every test. Tests of the
    builder itself mark themselves `@pytest.mark.real_plugin_envs` and fake the `uv` runner.
    """
    from voxarium_studio.plugins import envs as plugin_envs

    monkeypatch.setattr(plugin_envs, "_builds", {})
    monkeypatch.setattr(plugin_envs, "_failures", {})
    monkeypatch.setattr(plugin_envs, "_watchers", [])
    if request.node.get_closest_marker("real_plugin_envs"):
        return
    monkeypatch.setattr(plugin_envs, "schedule", lambda _manifest, *, force=False: None)


@pytest.fixture(autouse=True)
def no_commit_model(monkeypatch: pytest.MonkeyPatch) -> None:
    """Never let a commit draft reach the Agent SDK (§10.4).

    A task arriving in `needs_review` drafts a commit in the background, and the draft's
    description is a billed model call whenever the task changed a file. The orchestrator
    resolves its describer lazily from `commit_proposal.describe_with_claude`, so patching
    the module default here covers every context the suite builds; a test that wants to see
    the prompt sets `ctx.teams.commit_describer` to its own fake instead.
    """
    from voxarium_studio.teams import commit_proposal

    async def canned(prompt: str, model: str, cwd: Path) -> str:
        return f"Described by {model}."

    monkeypatch.setattr(commit_proposal, "describe_with_claude", canned)


@pytest.fixture(autouse=True)
def canned_effort_solver(monkeypatch: pytest.MonkeyPatch) -> None:
    """Answer every Effort Solver evaluation with fixed scores by default (§10.11).

    A task whose effort is unset ("Auto") is scored when its run starts, and in the suite
    the Solver member sits on the same stub backend as everyone else, which answers in
    prose. Patching the runner's backend lookup keeps those stub runs starting; a test that
    inspects or fails the evaluation patches ``effort_solver_runner.get_backend`` itself,
    which overrides this.
    """
    from voxarium_studio.teams import effort_solver_runner
    from voxarium_studio.teams.backends.base import AgentEvent

    class Canned:
        def is_available(self) -> tuple[bool, str]:
            return True, "canned"

        async def run(self, request):
            answer = '{"complexity": 3, "creativity": 2, "depth": 3, "coverage": 9}'
            yield AgentEvent(kind="result", text=answer, data={}, is_final=True)

    monkeypatch.setattr(effort_solver_runner, "get_backend", lambda _name: Canned())


@pytest.fixture(autouse=True)
def no_result_follow_up(monkeypatch: pytest.MonkeyPatch) -> None:
    """Never resume a stub session for its result type by default (§10.4).

    A run that ends without calling `finish_task` is resumed once with the `result_required`
    prompt, which doubles every stub run in the suite and puts a second prompt into every
    backend that records what it was sent. The default is flipped on the config field, so
    every project the suite initialises loads with it off; a test that wants the follow-up
    sets `ctx.teams.config.teams.result_follow_up = True` on its own context.
    """
    from voxarium_studio.core.config import TeamsConfig

    field = TeamsConfig.model_fields["result_follow_up"]
    monkeypatch.setattr(field, "default", False)
    TeamsConfig.model_rebuild(force=True)
    yield
    TeamsConfig.model_rebuild(force=True)


@pytest.fixture(autouse=True)
def no_update_checks(request: pytest.FixtureRequest, monkeypatch: pytest.MonkeyPatch) -> None:
    """Never let a test fetch from the Studio's own remote (12.1).

    `studio_repo_root()` answers with this checkout when the package is installed editable,
    which it is for the suite - so every started `AppContext` would arm a poller against the
    developer's real `origin`. It waits 15s before its first fetch, so this only bites a test
    that holds a context longer than that, which is exactly the kind of accident that is
    invisible until a suite is run offline.

    Tests that mean the checker mark themselves `@pytest.mark.real_updates`; `test_updates.py`
    builds the service directly against a repository in `tmp_path` and needs nothing here.
    """
    if request.node.get_closest_marker("real_updates"):
        return

    async def do_not_poll(_self: UpdateService) -> None:
        return None

    monkeypatch.setattr(UpdateService, "start", do_not_poll)


@pytest.fixture
def project(tmp_path: Path) -> Path:
    """An initialised managed project with the sample pipeline and team."""
    root = tmp_path / "game"
    root.mkdir()
    init_project(root)
    return root


@pytest.fixture
def bare_project(tmp_path: Path) -> Path:
    """An initialised project with no samples — for tests that count what they created."""
    root = tmp_path / "bare"
    root.mkdir()
    init_project(root, with_samples=False)
    return root


@pytest.fixture
def config(project: Path) -> AppConfig:
    from voxarium_studio.core.paths import ProjectPaths

    return load_config(ProjectPaths.for_project(project))


@pytest.fixture
def registry(project: Path, config: AppConfig) -> Registry:
    from voxarium_studio.core.paths import ProjectPaths

    return plugin_loader.load_all(ProjectPaths.for_project(project), config)


@pytest.fixture
async def ctx(project: Path) -> AsyncIterator[AppContext]:
    """A fully started application context, torn down afterwards."""
    context = AppContext.create(str(project), AuthSettings())
    await context.startup()
    try:
        yield context
    finally:
        await context.shutdown()


@pytest.fixture
def client(project: Path) -> Iterator[TestClient]:
    """The HTTP surface. The context manager is what runs the app's lifespan."""
    with TestClient(create_app(str(project)), base_url="http://127.0.0.1") as test_client:
        yield test_client


@pytest.fixture
def token_client(project: Path) -> Iterator[TestClient]:
    with TestClient(create_app(str(project), auth=AuthSettings(token="t0ken")), base_url="http://127.0.0.1") as test_client:
        yield test_client


@pytest.fixture
def wait_for(ctx: AppContext):
    """Block until a run or agent task leaves the job engine.

    Awaits the job's own task rather than polling for its absence. That is exact, and it
    is what lets this end without the fixed settle it used to sleep off: everything a run
    or a session writes — the final status, the summary, the events — is awaited *inside*
    the job's coroutine, so the task being done is the writes having landed. Polling could
    not say that, because :meth:`JobEngine.is_running` answers False both for a job that
    has finished and for one that was never submitted.

    No handle means exactly those two cases, and returning is right for both: the caller
    submitted before calling, or is waiting on something already over.

    A fixture rather than an importable helper: `tests/` is not a package, and making it
    one just to share two functions is not worth the import ceremony.
    """
    import asyncio

    async def _wait(job_id: str, timeout: float = 60.0) -> None:
        handle = ctx.jobs.get(job_id)
        task = handle.task if handle is not None else None
        if task is None:
            return
        done, _pending = await asyncio.wait([task], timeout=timeout)
        if not done:
            raise AssertionError(f"job {job_id} did not finish within {timeout}s")

    return _wait
