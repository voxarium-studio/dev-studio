"""Effective config and the project-owned ``.voxarium_studio/config.yaml`` (§5.2)."""

from __future__ import annotations

import logging
import re
from pathlib import Path
from typing import Any, Literal, get_args

import yaml
from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

from .paths import ProjectPaths, current_data_path
from .prompts import AgentPromptsConfig

log = logging.getLogger(__name__)

CONFIG_VERSION = 1

#: What a project code may look like once uppercased — 1–6 alphanumerics with at least one
#: letter (§10.2). The letter is not decoration: a task is displayed as ``<code>-<number>``
#: and referred to by that string, so an all-digit code would make ``12-3`` ambiguous
#: against the bare number ``123``.
PROJECT_CODE_PATTERN = re.compile(r"^(?=.*[A-Z])[A-Z0-9]{1,6}$")

#: Used when nothing sensible can be derived from the project's name.
FALLBACK_PROJECT_CODE = "TSK"

#: Default ceiling on one message from an agent runtime (``TeamsConfig.max_message_bytes``).
#: Shared with ``AgentRunRequest`` so a request built without an opinion — a test, another
#: backend's caller — is bounded by the same number the config hands the orchestrator.
MAX_MESSAGE_BYTES_DEFAULT = 32 * 1024 * 1024


def normalise_project_code(value: str | None) -> str | None:
    """Uppercase and validate a project code. ``None`` for "not set" — never an error.

    Raises ``ValueError`` for a value that is set and unusable, which is what the settings
    endpoint turns into a 400. Blank means "unset", so clearing the field in the UI takes
    the code back to the one derived from the project's name.
    """
    text = (value or "").strip().upper()
    if not text:
        return None
    if not PROJECT_CODE_PATTERN.match(text):
        raise ValueError(
            "A project code is 1–6 letters and digits with at least one letter — "
            "it prefixes every task id, as in VS-01."
        )
    return text


def derive_project_code(name: str | None) -> str:
    """The code a project gets before anyone chooses one: initials, or the first letters.

    ``demo-project`` → ``DP``, ``game`` → ``GAM``. Multi-word names take one letter per
    word because that is what people write on a whiteboard; a single word has no initials
    to take, so it gives up its first three characters instead.
    """
    words = re.findall(r"[A-Za-z0-9]+", name or "")
    if not words:
        return FALLBACK_PROJECT_CODE
    code = (
        "".join(word[0] for word in words[:4]) if len(words) > 1 else words[0][:3]
    ).upper()
    return code if PROJECT_CODE_PATTERN.match(code) else FALLBACK_PROJECT_CODE


class ApplicationThemeConfig(BaseModel):
    """User-wide appearance, excluding the project's accent (§5.2, §7).

    Settings → Appearance writes these fields to the user's application config. The
    frontend stamps attributes and custom properties on ``<html>``; colours themselves
    live in ``tokens.css``. ThemeConfig accepts the obsolete appearance fields in older
    project files by ignoring them, and the first loaded project seeds user preferences.
    """

    model_config = ConfigDict(extra="forbid")

    mode: Literal["dark", "light", "system"] = "dark"

    text_size: int | None = Field(default=None, ge=11, le=18)
    """Body text in px, or ``None`` to take the app's own size — the same arrangement as
    :attr:`DocsConfig.font_size`, and for the same reason: a default number would silently
    override the app's stylesheet size without the user choosing it."""

    font: Literal["google-sans", "inter", "ibm-plex", "roboto", "system"] = "google-sans"
    mono_font: Literal["cascadia", "jetbrains", "fira", "system"] = "jetbrains"
    """JetBrains Mono by default, because that is what the Graphite design is drawn in: a log
    and a commit are read here, not skimmed."""
    sidebar: Literal["labels", "icons", "auto"] = "labels"
    """The rail: labels beside the icons, icons alone, or icons that widen on hover."""

    canvas_grid: Literal["dots", "lines", "none"] = "dots"
    node_tint: int = Field(default=16, ge=0, le=60)
    """How strongly a canvas node's header takes its category colour, in percent."""

    status_palette: Literal["default", "accessible"] = "default"
    """``accessible`` swaps the whole status vocabulary — board, canvas and VCS marks — for
    hues a colour-blind reader can tell apart. One knob for all three surfaces, because a
    board and a canvas that disagreed about "running" would be worse than either palette."""

    custom_tokens: dict[str, str] = Field(default_factory=dict)
    """Raw CSS custom property overrides, e.g. ``{"--vxs-bg-surface": "#101114"}``. Applied
    last, so they win over every preset."""


class ThemeConfig(ApplicationThemeConfig):
    """Effective theme: application appearance plus the project accent (§5.2, §7)."""

    # Old project files may still contain the appearance controls removed in §7.
    model_config = ConfigDict(extra="ignore")

    accent: str = "#a394e8"
    """One ``#rrggbb``, painted as a mark, never as a ground. Stored with the project."""


class ConcurrencyConfig(BaseModel):
    """Semaphore sizes per resource class (§8.2) plus the agent-session cap (§10.3).

    ``gpu: 1`` is deliberate: parallel GPU nodes on one machine exhaust VRAM.
    ``agents`` follows the SDK planning guidance of ~1 GiB RAM / 1 CPU per active agent.
    """

    gpu: int = Field(default=1, ge=1)
    cpu: int = Field(default=4, ge=1)
    io: int = Field(default=8, ge=1)
    agents: int = Field(default=2, ge=1)

    def as_dict(self) -> dict[str, int]:
        # Keyed by ``ResourceClass`` — note the singular "agent", which is what
        # TeamOrchestrator asks the Job Engine for.
        return {"gpu": self.gpu, "cpu": self.cpu, "io": self.io, "agent": self.agents}


def permission_modes() -> list[str]:
    """Modes supported by the installed runtime, shared by config and member editing."""
    try:
        from claude_agent_sdk.types import PermissionMode

        return [str(value) for value in get_args(PermissionMode)]
    except Exception:
        return ["default", "acceptEdits", "plan", "bypassPermissions"]


class TeamsConfig(BaseModel):
    """Bounds on autonomous agent work (§10.4).

    An agent that can put work on a colleague's board can be handed work back, and every
    hop is a billed Claude session — so delegation needs a ceiling that does not depend on
    the models choosing to stop. ``agents`` in ``concurrency`` caps how many run at once;
    these cap how far the work is allowed to spread.
    """

    required_skills: list[str] = Field(default_factory=list)
    required_documents: list[str] = Field(default_factory=list)
    """Initial context for every agent, supplemented by the member's requirements (§10.10)."""

    memory_enabled: bool = True
    """Project memory (§10.12): its index in every run's system prompt, and the ``recall`` /
    ``remember`` / ``forget`` tools. Off, agents neither see nor touch it; the files stay."""

    memory_index_chars: int = Field(default=8000, ge=500, le=100_000)
    """The most of the memory index a run is sent. Entries past it are counted, not listed,
    and a run reads them with ``recall``."""

    permission_mode: str = "bypassPermissions"
    max_turns: int = Field(default=40, ge=1, le=1000)
    """Shared run settings, unless a member explicitly enables its override (§10.3)."""

    @field_validator("permission_mode")
    @classmethod
    def _permission_mode(cls, value: str) -> str:
        if value not in permission_modes():
            raise ValueError(f"Unknown permission mode. Available: {', '.join(permission_modes())}")
        return value

    max_delegation_depth: int = Field(default=3, ge=0)
    """How many times a task may be delegated onward. 0 forbids agent-created tasks."""

    max_delegated_tasks_per_task: int = Field(default=8, ge=1)
    """Tasks one agent run may create, so a loop inside a single session is bounded too."""

    max_run_continuations: int = Field(default=3, ge=0)
    """How many times a run may be resumed automatically after stopping mid-work (§10.3).

    A session that hits `max_turns` with a tool call in flight ends with an empty result and
    nothing done — the work is unfinished and the only thing wrong is the turn budget. This
    is how many times the Studio restarts it by itself before leaving it to a person, which
    is the same click they would make. `0` turns it off; every continuation is a billed
    session, which is why it is bounded and configurable rather than "keep going".
    """

    context_compression_tokens: int = Field(default=100_000, ge=0)
    """Compact task history at this context size between runs (§10.4).

    Uses the latest request's input/cache tokens, or a character estimate when the
    backend supplies no usage. Zero disables automatic compression; Compress still works.
    """

    result_follow_up: bool = True
    """Ask a session that ended without reporting its result for it, once (§10.4).

    A task reaches review with a result type — done, question, error — that the assignee
    states through `finish_task`, and the reviewer's queue is read by that colour before
    anything else. A run that ends without the call is resumed one time with the
    `result_required` prompt; a session that still says nothing goes to review unlabelled,
    with the transcript saying so. One extra billed turn at most, and only when the model
    skipped the call, which is why it is on by default and still a switch.
    """

    max_message_bytes: int = Field(default=MAX_MESSAGE_BYTES_DEFAULT, ge=64 * 1024)
    """The largest single message the Studio will accept from an agent runtime (§10.3).

    The Agent SDK reads the CLI's stdout as NDJSON — one line per message — and aborts the
    stream on a line longer than this. Its own default is 1 MiB, which any screenshot walks
    straight through: `Read` on a 3.5 MB PNG comes back as ~4.8 MB of base64 on one line,
    the run dies with `Failed to decode JSON: JSON message exceeded maximum buffer size of
    1048576 bytes`, and the task lands in Blocked with the work half done. Images are the
    reason this is not 1 MiB; it stays bounded because a runaway line must not be able to
    eat the server's memory.
    """

    delegated_work_idle_timeout: float = Field(default=1800.0, ge=0)
    """How long a run may hear nothing at all while delegated work is still running (§10.3).

    A member that starts a sub-agent or a workflow gets a result frame the moment its own
    turn ends, and Claude Code wakes the session again as each of them finishes — so the
    run is held open past that result rather than ended on it. This bounds the hold: a
    session that has said nothing for this long, no progress and no completion, is one
    nobody is going to hear from, and holding it forever would keep an `agent` concurrency
    slot and leave the task In progress with no way back except Stop.

    It measures **silence**, not the work: any frame at all resets it, so a sub-agent that
    legitimately runs for hours keeps the run alive as long as it reports. `0` waits
    indefinitely, which is the right setting for a project that would rather stop a run by
    hand than have one cut short.
    """

    limit_resume_grace: float = Field(default=120.0, ge=0)
    """How long after a usage limit resets a waiting task starts again (§10.3).

    Only consulted for a task with ``resume_on_limit`` on. Two minutes rather than none,
    because the reset instant is the API's clock and this is ours: a session opened on the
    exact second comes straight back refused, which spends the whole wait and leaves the
    task blocked anyway. Two minutes is slack for the clocks and for a window that refills
    a moment after it is due; `0` starts the instant the reset passes.
    """

    limit_fallback_wait: float = Field(default=0.0, ge=0)
    """How long to wait when a usage limit was hit and its reset was never reported.

    Off by default, and off means "leave the task blocked with the reason on it". The
    runtime nearly always dates the limit (``rate_limit`` carries ``resets_at``), so this
    covers the case where it did not — and retrying blind is a guess about somebody's
    account, which is the user's to make rather than the Studio's. Set it to the interval
    a blind retry should use.
    """

    launch_root: str = "."
    """Where an agent session starts, relative to the managed project's root.

    ``.`` is the project itself, which is what nearly every project wants. ``..`` starts
    sessions one level up — the case this exists for: a game whose Studio-managed project
    is one folder of a larger checkout, where an agent cannot do its job without seeing
    the siblings. An absolute path is taken as given.

    Pointing it *outside* the project turns per-task checkout isolation off (§10.5): a
    worktree is a second checkout of the repository, and there is no such thing for a
    directory the repository does not contain. Those runs fall back to path locks, exactly
    as a project with no version control does.
    """

    prompts: AgentPromptsConfig = Field(default_factory=AgentPromptsConfig)
    """Overrides for the fixed lines the Studio says to an agent — see ``core/prompts.py``.

    Only what the user changed is stored (``save_config`` drops the ``None`` fields), so an
    untouched project follows the built-in wording as it evolves. Edited in Teams → Team
    Settings, which reads the defaults from ``GET /api/settings/prompts``.
    """

    commit_proposals: bool = True
    """Whether a task reaching ``needs_review`` gets a commit drafted for it (§10.4).

    The draft is a title (``VS-01 | <task title>``), the files the task changed and a
    description written by :attr:`commit_model` from the task's own summary and diff. Off,
    the Commit section still lists the files and the title, and the description stays empty
    for the reviewer to write — no model is called.
    """

    commit_backend: str = "claude_code"
    """Backend used for commit descriptions, independently of task members (§10.4)."""

    commit_model: str = "haiku"
    """The model that writes a commit description — a cheap one, since the work is a
    paragraph over a diff that is already summarised. Reaches the CLI verbatim, like a
    member's ``model``; empty selects the backend default. Claude runs at low effort with thinking off
    (:data:`~voxarium_studio.teams.commit_proposal.DESCRIBE_EFFORT`)."""


def resolve_launch_root(project_root: Path, value: str | None) -> Path:
    """The absolute directory a ``teams.launch_root`` value names.

    Never raises and never reports a problem: the setting travels with the project in
    ``config.yaml``, so a path that is fine on one machine can be missing on the next, and
    that is a thing to fall back from at run time rather than to crash the session over.
    Callers check ``is_dir()`` and :meth:`ProjectPaths.is_inside_project` themselves,
    because what each wants to do about the answer differs.
    """
    text = (value or ".").strip()
    if not text:
        return project_root
    try:
        # `root / "/abs"` yields the absolute path, so this covers both spellings.
        return (project_root / Path(current_data_path(text, project_root)).expanduser()).resolve()
    except (OSError, ValueError, RuntimeError):
        return project_root


def effective_launch_root(project_root: Path, value: str | None) -> Path:
    """Where a session *would* start on this machine: the configured root, or the project.

    :func:`resolve_launch_root` answers what the setting names; this answers what to use,
    applying the one fallback every caller applies — a configured directory that is not
    there falls back to the project root. The orchestrator does the same and writes a note
    on the task about it; a plugin hook or a search provider (§9.7) just needs the answer.
    """
    resolved = resolve_launch_root(project_root, value)
    try:
        return resolved if resolved.is_dir() else project_root
    except OSError:
        return project_root


class McpConfig(BaseModel):
    """Which of the project's MCP servers agents actually get (§10.7).

    The servers themselves live in ``.mcp.json`` at the project root, in Claude Code's
    own format — that file is shared with the developer's terminal, so switching a
    server off for the Studio must not mean deleting it from there. The on/off state is
    therefore *here*, the same split plugins use between the global registry and
    per-project ``enabled`` (§9.4).
    """

    disabled: list[str] = Field(default_factory=list)
    """Server names present in ``.mcp.json`` that are withheld from agent sessions."""

    strict: bool = True
    """Pass only the servers listed here to the agent (``--strict-mcp-config``).

    Off, the CLI adds whatever else it would load on its own — user-level and global
    config, plugin-provided servers — and a server switched off above comes back through
    the CLI's own reading of ``.mcp.json``. On, this page describes what agents get.
    """


class VcsConfig(BaseModel):
    """Which version control system this project is under (§10.5).

    Left unset the Studio auto-detects Plastic and Perforce workspaces before Git. Name a backend here when detection would pick the wrong one — a
    Plastic workspace living inside a git checkout, say — or to turn the integration off
    entirely with ``backend: none``.
    """

    backend: str | None = None

    settings: dict[str, dict[str, Any]] = Field(default_factory=dict)
    """Per-backend settings keyed by backend id — ``{"git": {"executable": "…/git.exe"}}``.

    These settings are owned exclusively by the entity's Version Control card (§9.6).
    """

    def selected(self) -> str | None:
        choice = (self.backend or "").strip().lower()
        return None if choice in ("", "auto") else choice

    @property
    def disabled(self) -> bool:
        return (self.backend or "").strip().lower() in ("none", "off")


class UpdatesConfig(BaseModel):
    """Application-wide checks for a newer Studio release (§5.2, §12.1)."""

    enabled: bool = True
    check_interval_minutes: int = Field(default=180, ge=5)
    """How often to fetch. The floor is not politeness to the remote — it is that a shorter
    interval cannot tell you anything, since a release is tagged at most a few times a day."""


class ExplorerConfig(BaseModel):
    show_data_dir: bool = False
    """Application preference: show ``.voxarium_studio/`` in every Explorer tree (§5.2)."""

    ignore: list[str] = Field(
        default_factory=lambda: [
            ".git",
            "node_modules",
            "__pycache__",
            ".venv",
            "Library",
            "Temp",
            "obj",
            ".import",
        ]
    )
    # There is deliberately no preview size limit here. How much a browser can be handed is
    # the Studio's own number and applies to every project it opens — `MAX_PREVIEW_BYTES` in
    # `explorer/service.py`. The ignore list remains per-project. An older config still
    # carrying `max_preview_bytes` loads fine and is
    # ignored, and the next `save_config` drops it.


class DocsConfig(BaseModel):
    """How the Documents section renders a document (block-editor spec §4.10)."""

    font_size: int | None = Field(default=None, ge=10, le=28)
    """Body text size of the block editor, in px, or ``None`` for "nobody chose one".

    Unset does not mean "the app's size": it means **one point above** it. The rest of the
    Studio is chrome to be scanned and a document is prose to be read, so they want
    different sizes — but the offset is what is durable, because the app's own size is
    :attr:`ThemeConfig.text_size` (or, unset, the one ``tokens.css`` states) and a hard
    default here would quietly override it. A project that has chosen nothing reads its
    documents at 15px against the app's 14px, and follows that size until someone picks a
    number of their own.

    Everything inside the editor is sized in ``em``, so this one value carries the
    headings, code and tables with it.
    """

    line_height: float | None = Field(default=None, ge=1.0, le=2.5)
    """Leading of the block editor's body text, unitless, or ``None`` for "nobody chose one".

    Unset renders at ``editor.css``'s own default (1.5 — a document is prose, but it is
    also edited, and a tall leading makes a page of blocks feel sparse). Unitless on
    purpose: the value multiplies ``font_size``, so the two settings do not fight.
    Headings keep their tighter leading regardless — only body text is stepped.
    """


class SyncConfig(BaseModel):
    """Online sync of the project's Documents through a Voxarium Studio Remote (§16.5).

    Project-owned and committed: *which* server and space this project syncs with is a fact
    about the project, and teammates who pull it should not have to be told. The key that
    says *who is asking* is not here — it is the user's, and lives in their config
    directory (``sync/credentials.py``).
    """

    enabled: bool = False
    url: str = ""
    """The server's address as it was entered; normalised when used."""
    space: str = ""
    """The shared set of documents on that server — one per project."""


class PluginConfig(BaseModel):
    """Per-project plugin state. Plugins install globally, enable per project (§9.4)."""

    enabled: bool = True
    settings: dict[str, Any] = Field(default_factory=dict)


class AppConfig(BaseModel):
    """Effective configuration; persistence separates application and project fields (§5.2)."""

    version: int = CONFIG_VERSION
    language: Literal["en", "ru"] | None = None
    """UI language; null allows first-use migration from this browser's choice (§6.4)."""
    respond_language: str = "English"
    """Free-form global language for prompt {language} substitutions (§6.4)."""
    project_name: str | None = None
    project_code: str | None = None
    """Short code every task id is displayed with — ``VS`` in ``VS-01`` (§10.2).

    Null means "not chosen", and the code is then derived from the project's name rather
    than the field being empty: a task board with no ids at all would be worse than one
    with ids nobody picked. Changing it renames every task on screen and nothing in the
    database — the number underneath is the identity, the code is only how it reads.
    """

    theme: ThemeConfig = Field(default_factory=ThemeConfig)
    concurrency: ConcurrencyConfig = Field(default_factory=ConcurrencyConfig)
    teams: TeamsConfig = Field(default_factory=TeamsConfig)
    mcp: McpConfig = Field(default_factory=McpConfig)
    vcs: VcsConfig = Field(default_factory=VcsConfig)
    explorer: ExplorerConfig = Field(default_factory=ExplorerConfig)
    docs: DocsConfig = Field(default_factory=DocsConfig)
    sync: SyncConfig = Field(default_factory=SyncConfig)
    updates: UpdatesConfig = Field(default_factory=UpdatesConfig)
    plugins: dict[str, PluginConfig] = Field(default_factory=dict)
    node_cache_enabled: bool = True

    @model_validator(mode="after")
    def migrate_plastic_plugin(self) -> "AppConfig":
        """Move legacy settings once; clearing a card must never revive them (§9.6)."""
        legacy = self.plugins.pop("plastic", None)
        if legacy is not None:
            merged = {**legacy.settings, **self.vcs.settings.get("plastic", {})}
            if merged:
                self.vcs.settings["plastic"] = merged
        return self

    def task_code(self, fallback_name: str = "") -> str:
        """The code to prefix task ids with, always a usable one (§10.2).

        Deliberately forgiving where :func:`normalise_project_code` is not: this is read on
        every task the API returns, and a hand-edited ``config.yaml`` holding nonsense must
        not turn the whole board into a 500. The settings endpoint is where a bad value is
        refused, at the moment somebody types it.
        """
        try:
            chosen = normalise_project_code(self.project_code)
        except ValueError:
            log.warning("Ignoring unusable project_code %r in config.yaml", self.project_code)
            chosen = None
        return chosen or derive_project_code(self.project_name or fallback_name)

    def plugin_state(self, plugin_id: str) -> PluginConfig:
        return self.plugins.setdefault(plugin_id, PluginConfig())

    def is_plugin_enabled(self, plugin_id: str, *, default: bool = True) -> bool:
        state = self.plugins.get(plugin_id)
        return default if state is None else state.enabled


DEFAULT_GITIGNORE = """\
# Generated by Voxarium Studio.
# app.db, pipelines, teams, docs and config.yaml are intentionally NOT ignored — they describe the
# project's AI workflow and what the team decided, and are worth keeping in version
# control (spec §5).
app.db-wal
app.db-shm
assets/
attachments/
runs/
temp/
worktrees/
plugins/*/.venv/
"""


# Plastic reads ignore.conf from the workspace root, so scope every rule to .voxarium_studio (§5).
DEFAULT_PLASTIC_IGNORE = """\
# Voxarium Studio runtime files (spec §5). Keep .voxarium_studio/app.db in version control.
/.voxarium_studio/app.db-wal
/.voxarium_studio/app.db-shm
/.voxarium_studio/assets
/.voxarium_studio/attachments
/.voxarium_studio/runs
/.voxarium_studio/temp
/.voxarium_studio/worktrees
/.voxarium_studio/plugins/*/.venv
"""


def load_config(paths: ProjectPaths, *, migrate_application: bool = False) -> AppConfig:
    """Read effective settings; migration is opt-in for actual project opens (§5.2).

    Diagnostic reads (including ``studio info``) never establish user preferences. A
    missing or unreadable project file must not seed defaults and suppress migration of
    a later valid legacy project, even when an actual open requested migration.
    """
    from .application_config import (
        application_config_file, apply_application_config, load_application_config,
    )

    raw, successfully_read = _read_project_config(paths)
    if application_config_file().exists():
        # Ignore obsolete global values before validation: an old text size or update
        # interval outside today's bounds does not invalidate this project's own data.
        raw = _project_fields(raw)
    config = AppConfig.model_validate(raw)
    apply_application_config(config, load_application_config(
        legacy=config, migrate=migrate_application and successfully_read,
    ))
    return config


def _read_project_config(paths: ProjectPaths) -> tuple[dict[str, Any], bool]:
    """Keep successful reads distinct from fallback defaults for one-time migration."""
    fallback = {"project_name": paths.root.name}
    if not paths.config_file.exists():
        return fallback, False
    try:
        raw = yaml.safe_load(paths.config_file.read_text(encoding="utf-8")) or {}
    except (OSError, UnicodeError, yaml.YAMLError) as exc:
        log.warning("Could not read %s (%s) — falling back to defaults", paths.config_file, exc)
        return fallback, False
    if not isinstance(raw, dict):
        log.warning("%s is not a mapping — falling back to defaults", paths.config_file)
        return fallback, False
    return raw, True


def _project_fields(raw: dict[str, Any]) -> dict[str, Any]:
    """The disk schema owns accent/ignore, but none of their global siblings (§5.2)."""
    payload = dict(raw)
    if "theme" in payload:
        theme = payload["theme"]
        payload["theme"] = (
            {"accent": theme["accent"]} if isinstance(theme, dict) and "accent" in theme else {}
        )
    for key in ("language", "respond_language", "updates", "node_cache_enabled"):
        payload.pop(key, None)
    if isinstance(payload.get("explorer"), dict):
        payload["explorer"] = {
            key: value for key, value in payload["explorer"].items() if key != "show_data_dir"
        }
    return payload


def save_config(paths: ProjectPaths, config: AppConfig) -> None:
    """Write only project-owned fields; global saves use application_config (§5.2)."""
    paths.data.mkdir(parents=True, exist_ok=True)
    payload = _project_fields(config.model_dump(mode="json", exclude_none=True))
    text = yaml.safe_dump(payload, sort_keys=False, allow_unicode=True, indent=2)
    tmp = paths.config_file.with_suffix(".yaml.tmp")
    tmp.write_text(text, encoding="utf-8")
    tmp.replace(paths.config_file)
