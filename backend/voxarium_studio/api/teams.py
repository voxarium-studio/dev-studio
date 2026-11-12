"""Teams: org chart, task board, agent transcripts (§10.4, §11.1)."""

from __future__ import annotations

import asyncio
import re
from typing import Any, Literal

from fastapi import APIRouter, File, HTTPException, Query, Response, UploadFile
from fastapi.responses import FileResponse
from pydantic import BaseModel, Field
from sqlmodel import select

from ..core.context import AppContext
from ..core.security import UNTRUSTED_FILE_HEADERS
from .session_context import ContextView
from ..models.subagents import TaskSubagent
from ..models.teams import (
    TERMINAL_TASK_STATUSES,
    AgentChat,
    AgentMessage,
    Team,
    TeamMember,
    TeamTask,
    TaskResultType,
    TaskStatus,
    format_task_id,
)
from ..teams import project_skills
from ..teams.attachments import MAX_ATTACHMENT_BYTES, media_type_of
from ..teams.backends import codex, qwen
from ..teams.backends.base import (
    RemoteControlUnavailable,
    available_backends,
    get_backend,
)
from ..teams.orchestrator import DelegationLimitError, RoutingError
from ..teams.tools import SERVER_NAME
from ..vcs import VcsError
from .deps import Ctx

router = APIRouter(prefix="/teams", tags=["teams"])


def _task_json(ctx: AppContext, task: TeamTask) -> dict[str, Any]:
    """One task, as every endpoint here returns it.

    ``display_id`` is composed on the backend rather than by the client, for the reason the
    build label is (§10.2): the project code lives in ``config.yaml`` and the padding rule
    in :data:`~voxarium_studio.models.teams.TASK_NUMBER_DIGITS`, so a second implementation in the
    SPA would be a second answer to "what is this task called". The agent tools read the
    same pair, which is what lets a person and an agent quote the same ``VS-01``.
    """
    return {
        **task.model_dump(mode="json"),
        "display_id": format_task_id(ctx.task_code, task.number),
        "is_running": ctx.jobs.is_running(task.id),
        "is_compacting": bool(ctx.jobs.get(task.id) and ctx.jobs.get(task.id).kind == "task_compaction"),
    }


class TaskCreate(BaseModel):
    summary_locale: Literal["en", "ru"] | None = None
    assignee_id: str
    title: str
    brief: str = ""
    write_paths: list[str] = Field(default_factory=list, description=(
        "Exact files this task will write, create or delete, including both paths of a "
        "rename. Paths are relative to the managed project root or absolute; ``dir/*`` "
        "declares everything inside a directory. Exclude "
        "read-only references; an empty list takes no path locks."
    ))
    attachments: list[str] = Field(default_factory=list)
    created_by: str | None = None
    depends_on: list[str] = []
    auto_start: bool = True
    """What happens when the task is *released*, not on creation (§10.4).

    Every task created here lands in ``todo`` and starts nothing. This says whether moving
    it to ``pending`` should then run it as soon as it is unblocked, or leave it for an
    explicit Start.
    """

    agent_backend: str | None = None
    model: str | None = None
    effort: str | None = None
    """Run this one task on something other than the assignee's model or effort (§10.3).

    Null on both — the usual case — leaves the pair to the member, which is what a roster
    tuned once for the work it usually does wants. A card that is unusually hard, or
    unusually cheap, says so here instead of by editing the role and putting it back.
    """


class TaskUpdate(BaseModel):
    """Partial edit of a task, applied with ``exclude_unset`` like ``MemberUpdate``.

    ``status`` is the manual move behind the board's drag-and-drop and status picker; the
    lifecycle endpoints below stay the ones that carry side effects (a session start, a
    review hand-off), and this is what covers everything they do not — which for a person's
    task is most of it.
    """

    summary_locale: Literal["en", "ru"] | None = None
    title: str | None = None
    brief: str | None = None
    write_paths: list[str] = Field(default_factory=list, description=(
        "Replace the exact files this task will write (§10.5). Include files created "
        "or deleted and both paths of a rename, relative to the managed project root "
        "or absolute; ``dir/*`` declares everything inside a directory. Exclude read-only "
        "references. An empty list clears the path locks. While the task is running "
        "only its executor may change this, and only to paths no other running task holds."
    ))
    attachments: list[str] | None = None
    assignee_id: str | None = None
    depends_on: list[str] | None = None
    auto_start: bool | None = None
    resume_on_limit: bool | None = None
    """Wait out a spent usage limit and start again after it resets (§10.3) — on by default.

    Turning it back on is retroactive: a task already blocked by a limit begins waiting for
    the reset it was stopped at, rather than for the next one. Turning it off ends a wait in
    flight — this is the only control over one.
    """

    status: TaskStatus | None = None
    agent_backend: str | None = None
    model: str | None = None
    effort: str | None = None
    """The task's model and effort overrides (§10.3) — and the two fields here where an
    explicit null *is* the edit: it drops the override and hands the task back to the
    assignee's setting. See :data:`_NULLABLE_TASK_FIELDS`."""

    archived: bool | None = None
    """Put a finished task away, or bring it back (§10.4).

    ``true`` is refused (400) on anything but a task in ``done``; ``false`` un-archives.
    The board hides an archived card and the cost figures keep counting it, which is the
    whole difference between this and a delete.
    """

    actor_id: str | None = None
    """Who is editing. Null means the user, who is not bound by the hierarchy (§10.4)."""


class TeamUpdate(BaseModel):
    autostart_tasks: bool


#: The only keys on a task where an explicit null means something. Everything else is
#: dropped by :func:`update_task`, since a null title or a null assignee is a request to
#: break the row rather than to clear a field — see the comment there.
_NULLABLE_TASK_FIELDS = frozenset({"agent_backend", "model", "effort"})


class ReviewAction(BaseModel):
    summary_locale: Literal["en", "ru"] | None = None
    comment: str = ""
    actor_id: str | None = None
    attachments: list[str] = Field(default_factory=list)
    """Names answered by ``POST …/attachments`` — files already stored on this task."""


class ActorAction(BaseModel):
    """A human member's own move on their task — or the user's, when ``actor_id`` is null."""

    actor_id: str | None = None


class TaskRunAction(BaseModel):
    """Capture the reader's language without changing project settings (§10.4)."""

    summary_locale: Literal["en", "ru"] | None = None


class NoteCreate(ActorAction):
    text: str


class CompleteAction(ActorAction):
    summary: str = ""
    result_type: TaskResultType
    """Required, as it is on the agent's ``finish_task``: a card lands in the review queue
    with its colour whoever carried it (§10.4)."""


class MemberUpdate(BaseModel):
    """Partial edit of a role.

    Applied with ``exclude_unset``, not ``exclude_none``: the difference is that a field
    the caller *sent as null* is applied (clearing a manager makes someone a root, clearing
    the colour falls back to the accent), while a field the caller simply left out is
    untouched. Dropping every null would make the nullable fields uneditable.
    """

    name: str | None = None
    role_title: str | None = None
    kind: Literal["agent", "human"] | None = None
    manager_id: str | None = None
    responsibility: str | None = None
    can_assign_to: list[str] | None = None
    tags: list[str] | None = None
    agent_backend: str | None = None
    agent: str | None = None
    model: str | None = None
    effort: str | None = None
    effort_solver_weights: dict[str, Any] | None = None
    allowed_tools: list[str] | None = None
    skills: list[str] | None = None
    """Folder names under ``.voxarium_studio/skills/`` — the whole list, as it should be (§10.10). Null
    is the same edit as an empty list: the member is given no skills."""

    required_skills: list[str] | None = None
    required_documents: list[str] | None = None

    run_settings_override: bool = False
    max_turns: int | None = Field(default=None, ge=1, le=1000)
    permission_mode: str | None = None
    isolate_worktree: bool | None = None
    solo_mode: bool | None = None
    avatar_color: str | None = None
    avatar_icon: str | None = None
    """A Lucide icon name for what this member does; null clears it back to no icon."""


class NodePosition(BaseModel):
    x: float
    y: float


class LayoutUpdate(BaseModel):
    """Where the org chart's nodes sit, keyed by member id.

    One request for a whole move rather than a ``MemberUpdate`` each: dragging a selection
    moves several nodes at once, and every member write rewrites the team's YAML file.
    A null position drops the hand placement, which is what "Tidy up" does — the member
    goes back under the derived tidy-tree layout instead of being pinned to wherever the
    tidy happened to put them.
    """

    positions: dict[str, NodePosition | None]


class MemberCreate(BaseModel):
    name: str
    role_title: str = ""
    kind: Literal["agent", "human"] = "agent"
    manager_id: str | None = None
    id: str | None = None
    """Short id inside the team; derived from the name when omitted."""


def _permission_modes() -> list[str]:
    """Read the modes off the installed SDK so the UI cannot offer one it would reject."""
    from ..core.config import permission_modes

    return permission_modes()


def _effort_levels() -> list[str]:
    """Effort levels the installed SDK accepts, read the same way as the modes above."""
    from ..teams.effort import effort_levels

    return effort_levels()


def _check_task_overrides(fields: dict[str, Any]) -> None:
    """Guard a task's ``model``/``effort`` overrides. Raises HTTPException(400).

    The same two checks the member editor gets, and deliberately the same shape: ``effort``
    has to be a level the installed SDK accepts, while ``model`` is passed to the CLI
    verbatim and so is only checked for being a plausible id — a model released after this
    build must stay usable by typing it in (see :data:`SUGGESTED_MODELS`).

    Null is not a value to validate here, on either: it is the override being dropped.
    """
    effort = fields.get("effort")
    if effort is not None and effort not in _effort_levels():
        raise HTTPException(
            status_code=400,
            detail=f"Unknown effort level. Available: {', '.join(_effort_levels())}",
        )
    model = fields.get("model")
    if isinstance(model, str) and model.strip() and not _MODEL_NAME.fullmatch(model.strip()):
        raise HTTPException(
            status_code=400,
            detail="A model id may only contain letters, digits, dots, dashes and underscores",
        )


#: What could plausibly be a model id. Not a whitelist — `SUGGESTED_MODELS` is the picker's
#: list, not the set of legal answers — only a guard against a value that could never be one.
_MODEL_NAME = re.compile(r"[A-Za-z0-9._-]+")

#: What could plausibly be an agent name — the `plugin:agent` form included. Deliberately
#: not an existence check: see `_validate_member`.
_AGENT_NAME = re.compile(r"[A-Za-z0-9._-]+(?::[A-Za-z0-9._-]+)?")

#: The longest icon name that could be one, so a field nobody validates cannot become a
#: place to store a paragraph.
MAX_ICON_NAME = 64

#: What a Lucide icon name looks like — `palette`, `gamepad-2`. Only the shape is checked,
#: for the reason a folder's icon is checked the same way (`teams/docs.py`): which icons
#: exist is the catalogue in `lib/member-icons.ts`, and a name this build cannot draw draws
#: nothing rather than failing the save — so a roster written by a newer Studio still loads.
_ICON_NAME = re.compile(r"[a-z0-9]+(?:-[a-z0-9]+)*")

#: Claude Code's built-in tools, for the editor's checklist. Not a whitelist — a member's
#: `allowed_tools` may name anything the backend understands, so the UI also accepts free
#: text. The Studio's own tools (mcp__voxarium_studio__*) are always allowed and never listed here.
COMMON_TOOLS = [
    "Read",
    "Write",
    "Edit",
    "Bash",
    "Grep",
    "Glob",
    "WebSearch",
    "WebFetch",
    "TodoWrite",
    "Task",
]

#: Models offered in the member editor's picker, in two kinds. An **alias** is resolved by
#: the `claude` CLI itself and follows Anthropic's releases — `opus` is whatever the latest
#: Opus is on the day the session runs. A **pinned id** never moves, which is what a member
#: whose behaviour was tuned against one model wants.
#:
#: Deliberately not a whitelist: `member.model` is passed to the backend verbatim and the
#: CLI is what validates it, so a model released after this build still works by typing it
#: in. Keeping this list from going stale is therefore cosmetic, not load-bearing.
SUGGESTED_MODELS: list[dict[str, str]] = [
    {"id": "opus", "label": "Opus (latest)", "group": "Aliases", "note": "Follows releases"},
    {"id": "sonnet", "label": "Sonnet (latest)", "group": "Aliases", "note": "Follows releases"},
    {"id": "haiku", "label": "Haiku (latest)", "group": "Aliases", "note": "Follows releases"},
    {"id": "opusplan", "label": "Opus for planning", "group": "Aliases",
     "note": "Opus while planning, Sonnet to execute"},
    {"id": "claude-opus-5-5", "label": "Opus 5.5", "group": "Pinned",
     "note": "Agentic coding and long-horizon work"},
    {"id": "claude-opus-5", "label": "Opus 5", "group": "Pinned", "note": ""},
    {"id": "claude-opus-4-8", "label": "Opus 4.8", "group": "Pinned", "note": ""},
    {"id": "claude-opus-4-7", "label": "Opus 4.7", "group": "Pinned", "note": ""},
    {"id": "claude-sonnet-5", "label": "Sonnet 5", "group": "Pinned",
     "note": "Near-Opus quality, Sonnet cost"},
    {"id": "claude-sonnet-4-6", "label": "Sonnet 4.6", "group": "Pinned", "note": ""},
    {"id": "claude-haiku-4-5", "label": "Haiku 4.5", "group": "Pinned",
     "note": "Fastest and cheapest"},
    {"id": "claude-fable-5-2", "label": "Fable 5.2", "group": "Pinned",
     "note": "Most capable; priced above Opus"},
    {"id": "claude-fable-5-1", "label": "Fable 5.1", "group": "Pinned", "note": ""},
    {"id": "claude-fable-5", "label": "Fable 5", "group": "Pinned", "note": ""},
]
# Each suggestion names the backend it is for, so the editor offers Claude models to a
# Claude Code member and Codex's catalogue to a Codex one (§10.6); the field itself still
# takes anything typed in.
for _entry in SUGGESTED_MODELS:
    _entry.setdefault("backend", "claude_code")


@router.get("/member-options")
async def member_options(ctx: Ctx) -> dict[str, Any]:
    """Everything the member editor needs to render, resolved against this install."""
    backends: dict[str, dict[str, Any]] = {}
    for name in available_backends():
        backend = None
        try:
            backend = get_backend(name)
            usable, reason = backend.is_available()
        except Exception as exc:  # a broken backend must not break the editor
            usable, reason = False, f"{type(exc).__name__}: {exc}"
        backends[name] = {
            "available": usable,
            "reason": reason,
            # Whether the member can run as a named agent definition (`claude --agent`);
            # the editor hides the "Runs as" field for a backend that cannot (§10.6).
            "named_agents": bool(getattr(backend, "named_agents", True)),
        }
    return {
        "agent_backends": backends,
        "permission_modes": _permission_modes(),
        "effort_levels": _effort_levels(),
        "common_tools": COMMON_TOOLS,
        "suggested_models": [*SUGGESTED_MODELS, *codex.suggested_models(), *qwen.suggested_models()],
        "studio_tool_prefix": f"mcp__{SERVER_NAME}__",
    }


@router.get("")
async def list_teams(ctx: Ctx) -> list[dict[str, Any]]:
    async with ctx.db.session() as session:
        teams = list((await session.exec(select(Team))).all())
        members = list((await session.exec(select(TeamMember))).all())
    by_team: dict[str, list[TeamMember]] = {}
    for member in members:
        by_team.setdefault(member.team_id, []).append(member)
    return [
        {
            **team.model_dump(mode="json"),
            "members": [
                m.model_dump(mode="json")
                for m in sorted(by_team.get(team.id, []), key=lambda x: (x.sort_order, x.name))
            ],
        }
        for team in teams
    ]


@router.get("/{team_id}")
async def get_team(team_id: str, ctx: Ctx) -> dict[str, Any]:
    async with ctx.db.session() as session:
        team = await session.get(Team, team_id)
        if team is None:
            raise HTTPException(status_code=404, detail=f"No team {team_id!r}")
        members = list(
            (await session.exec(select(TeamMember).where(TeamMember.team_id == team_id))).all()
        )
        tasks = list(
            (await session.exec(select(TeamTask).where(TeamTask.team_id == team_id))).all()
        )
    return {
        **team.model_dump(mode="json"),
        "members": [
            m.model_dump(mode="json")
            for m in sorted(members, key=lambda x: (x.sort_order, x.name))
        ],
        "tasks": [
            _task_json(ctx, t)
            for t in sorted(tasks, key=lambda x: x.created_at, reverse=True)
        ],
    }



@router.patch("/{team_id}")
async def update_team(team_id: str, payload: TeamUpdate, ctx: Ctx) -> dict[str, Any]:
    """Save the board's automatic Todo release policy (§10.4)."""
    try:
        await ctx.teams.set_autostart_tasks(team_id, payload.autostart_tasks)
    except KeyError as exc:
        raise HTTPException(status_code=404, detail=f"No team {team_id!r}") from exc
    return await get_team(team_id, ctx)


@router.post("/{team_id}/tasks/start-all", status_code=202)
async def start_all_tasks(
    team_id: str, ctx: Ctx, payload: TaskRunAction | None = None
) -> dict[str, Any]:
    """Release Todo/Pending agent tasks once; queued tasks wait for their usual gates (§10.4)."""
    try:
        task_ids = await ctx.teams.start_all_tasks(
            team_id, summary_locale=payload.summary_locale if payload else None
        )
    except KeyError as exc:
        raise HTTPException(status_code=404, detail=f"No team {team_id!r}") from exc
    return {"team_id": team_id, "task_ids": task_ids}


async def _validate_member(
    session, *, team_id: str, member: TeamMember, fields: dict[str, Any]
) -> None:
    """Guard the invariants the org chart depends on. Raises HTTPException(400)."""
    from ..teams.producer_identity import is_producer
    from ..teams.director_identity import is_director
    from ..teams.effort_solver import EffortWeights, is_effort_solver
    if is_director(member) and fields.get("kind", member.kind) != "human":
        raise HTTPException(400, "The built-in Director must remain a person.")
    if is_producer(member) and fields.get("kind", "agent") != "agent":
        raise HTTPException(400, "The built-in Producer must remain an agent.")
    if is_effort_solver(member) and fields.get("kind", "agent") != "agent":
        raise HTTPException(400, "The built-in Effort Solver must remain an agent.")
    if "effort_solver_weights" in fields:
        if not is_effort_solver(member):
            raise HTTPException(400, "Component weights are only available on Effort Solver.")
        if fields["effort_solver_weights"] is not None:
            try:
                fields["effort_solver_weights"] = EffortWeights.model_validate(fields["effort_solver_weights"]).model_dump()
            except ValueError as exc:
                raise HTTPException(400, str(exc)) from exc
    if is_producer(member) and "responsibility" in fields:
        # §10.11: the built-in role mirrors the Producer System Prompt in Team Settings.
        raise HTTPException(
            400, "The built-in Producer responsibility is read-only. Edit Producer System Prompt in Teams → Team Settings.",
        )
    if "name" in fields and not str(fields["name"] or "").strip():
        raise HTTPException(status_code=400, detail="A member needs a name")

    if "agent_backend" in fields and fields["agent_backend"] not in available_backends():
        raise HTTPException(
            status_code=400,
            detail=f"Unknown agent backend. Available: {', '.join(available_backends())}",
        )

    if "permission_mode" in fields and fields["permission_mode"] not in _permission_modes():
        raise HTTPException(
            status_code=400,
            detail=f"Unknown permission mode. Available: {', '.join(_permission_modes())}",
        )

    if "max_turns" in fields and fields["max_turns"] is None:
        raise HTTPException(status_code=400, detail="Max turns must be between 1 and 1000")

    # Which agents exist is Claude Code's answer, not ours — it reads the project, the
    # user's home and every plugin, and it says so plainly when a name is missing. So this
    # only rejects what could never be a name, and leaves the lookup where it belongs.
    agent = fields.get("agent")
    if isinstance(agent, str) and agent.strip() and not _AGENT_NAME.fullmatch(agent.strip()):
        raise HTTPException(
            status_code=400,
            detail="An agent name may only contain letters, digits, dots, dashes and "
            "underscores (a `plugin:agent` name is allowed too)",
        )

    # Null is the icon being taken off, which is always allowed; a blank string is the same
    # edit and is normalised to null in `update_member` rather than refused here.
    icon = fields.get("avatar_icon")
    if isinstance(icon, str) and icon.strip():
        if len(icon) > MAX_ICON_NAME or not _ICON_NAME.fullmatch(icon):
            raise HTTPException(
                status_code=400,
                detail="An icon name is lower-case words joined by hyphens, like 'pen-tool'",
            )

    # Null is a value here, not an omission: it means "leave the effort to the model".
    if fields.get("effort") is not None and fields["effort"] not in _effort_levels():
        raise HTTPException(
            status_code=400,
            detail=f"Unknown effort level. Available: {', '.join(_effort_levels())}",
        )

    roster = list(
        (await session.exec(select(TeamMember).where(TeamMember.team_id == team_id))).all()
    )
    by_id = {row.id: row for row in roster}

    if "manager_id" in fields:
        manager_id = fields["manager_id"]
        if manager_id is not None:
            if manager_id == member.id:
                raise HTTPException(status_code=400, detail="A member cannot manage themselves")
            if manager_id not in by_id:
                raise HTTPException(
                    status_code=400, detail=f"No member {manager_id!r} on this team"
                )
            # Walk up the proposed chain: a cycle would drop everyone in it out of the org
            # tree entirely, since a tree is built by following manager_id to a root.
            seen = {member.id}
            cursor: str | None = manager_id
            while cursor is not None:
                if cursor in seen:
                    raise HTTPException(
                        status_code=400,
                        detail="That would make the reporting line circular",
                    )
                seen.add(cursor)
                cursor = by_id[cursor].manager_id if cursor in by_id else None

    if "can_assign_to" in fields:
        for entry in fields["can_assign_to"] or []:
            if entry != "*" and entry not in by_id:
                raise HTTPException(status_code=400, detail=f"No member {entry!r} on this team")


def _checked_skills(ctx: AppContext, member: TeamMember, names: list[str] | None) -> list[str]:
    """A member's new skill list, each name once (§10.10). Raises HTTPException(400).

    Every name has to be one a folder could carry, and every name being **added** has to be a
    skill the project has. Only additions: a skill renamed or deleted on disk stays on the
    members it was given to until somebody takes it off, and taking a *different* one off sends
    the whole list — refusing that over the missing name would leave the list uneditable.
    """
    wanted = project_skills.normalise_names(names or [])
    for name in wanted:
        try:
            project_skills.check_name(name)
        except ValueError as exc:
            raise HTTPException(status_code=400, detail=str(exc)) from exc
    had = set(member.skills or [])
    for name in wanted:
        if name not in had and ctx.skills.get(name) is None:
            where = ctx.skills.relative(ctx.skills.directory)
            raise HTTPException(status_code=400, detail=f"No skill {name!r} in {where}")
    return wanted


@router.patch("/{team_id}/members/{member_id}")
async def update_member(
    team_id: str, member_id: str, payload: MemberUpdate, ctx: Ctx
) -> dict[str, Any]:
    """Edit a role, then write the team back to YAML so the change is committable."""
    fields = payload.model_dump(exclude_unset=True)
    async with ctx.db.session() as session:
        member = await session.get(TeamMember, member_id)
        if member is None or member.team_id != team_id:
            raise HTTPException(status_code=404, detail=f"No member {member_id!r}")
        await _validate_member(session, team_id=team_id, member=member, fields=fields)
        if "skills" in fields:
            fields["skills"] = _checked_skills(ctx, member, fields["skills"])
        from ..teams.required_context import checked_documents, checked_skills
        try:
            if "required_skills" in fields:
                fields["required_skills"] = checked_skills(
                    ctx.skills, fields["required_skills"] or [], member.required_skills)
            if "required_documents" in fields:
                fields["required_documents"] = checked_documents(
                    ctx.teams.docs, fields["required_documents"] or [], member.required_documents)
        except (ValueError, OSError) as exc:
            raise HTTPException(status_code=400, detail=str(exc)) from exc
        for field, value in fields.items():
            if field in ("agent", "avatar_icon") and isinstance(value, str):
                # Emptying the field in the UI means "go back to the generated agent" (or to
                # no icon), not "run as the agent called ''" — the two are a blank input away.
                value = value.strip() or None
            setattr(member, field, value)
        session.add(member)

    async with ctx.db.session() as session:
        await ctx.teams.store.save_from_db(session, team_id)
        refreshed = await session.get(TeamMember, member_id)
    # The roster is also a set of Claude Code agents (§10.3) — a renamed member, a new
    # manager or a borrowed agent all change what `claude --agent` should offer.
    await ctx.teams.sync_agent_definitions()
    # Solo mode is the one setting that can be *holding* work: a released task parked
    # behind a running one is waiting on exactly this flag, so turning it off is a release.
    if fields.get("solo_mode") is False:
        await ctx.teams.dispatch_member_queue(member_id)
    return refreshed.model_dump(mode="json")  # type: ignore[union-attr]


@router.patch("/{team_id}/layout")
async def update_layout(team_id: str, payload: LayoutUpdate, ctx: Ctx) -> dict[str, Any]:
    """Persist the org chart's node positions, then write the team back to YAML."""
    async with ctx.db.session() as session:
        if await session.get(Team, team_id) is None:
            raise HTTPException(status_code=404, detail=f"No team {team_id!r}")
        for member_id, position in payload.positions.items():
            member = await session.get(TeamMember, member_id)
            if member is None or member.team_id != team_id:
                raise HTTPException(status_code=404, detail=f"No member {member_id!r}")
            member.pos_x = position.x if position is not None else None
            member.pos_y = position.y if position is not None else None
            session.add(member)

    async with ctx.db.session() as session:
        await ctx.teams.store.save_from_db(session, team_id)
    # "updated", not "moved": "Tidy up" sends the whole roster as nulls and moves nobody.
    return {"team_id": team_id, "updated": len(payload.positions)}


@router.post("/{team_id}/members", status_code=201)
async def create_member(team_id: str, payload: MemberCreate, ctx: Ctx) -> dict[str, Any]:
    """Add a member. Ids are namespaced by team, exactly as the YAML sync does it."""
    if not payload.name.strip():
        raise HTTPException(status_code=400, detail="A member needs a name")

    short = (payload.id or re.sub(r"[^a-z0-9]+", "-", payload.name.lower())).strip("-")
    if not short:
        raise HTTPException(status_code=400, detail="Could not derive an id from that name")
    member_id = f"{team_id}__{short}"

    async with ctx.db.session() as session:
        team = await session.get(Team, team_id)
        if team is None:
            raise HTTPException(status_code=404, detail=f"No team {team_id!r}")
        if await session.get(TeamMember, member_id) is not None:
            raise HTTPException(status_code=409, detail=f"{short!r} is already taken")

        roster = list(
            (await session.exec(select(TeamMember).where(TeamMember.team_id == team_id))).all()
        )
        member = TeamMember(
            id=member_id,
            team_id=team_id,
            name=payload.name.strip(),
            role_title=payload.role_title.strip(),
            kind=payload.kind,
            sort_order=max((row.sort_order for row in roster), default=-1) + 1,
        )
        await _validate_member(
            session, team_id=team_id, member=member, fields={"manager_id": payload.manager_id}
        )
        member.manager_id = payload.manager_id
        session.add(member)

    async with ctx.db.session() as session:
        await ctx.teams.store.save_from_db(session, team_id)
    await ctx.teams.sync_agent_definitions()
    return member.model_dump(mode="json")


@router.delete("/{team_id}/members/{member_id}")
async def delete_member(
    team_id: str, member_id: str, ctx: Ctx, reassign_to_director: bool = False,
) -> dict[str, Any]:
    """Confirmed removal transfers work and chat history to the human Director (§10.2)."""
    from ..teams.director_identity import DIRECTOR_ID, is_director
    from ..teams.producer_identity import is_producer
    from ..teams.effort_solver import is_effort_solver

    if member_id in ctx.teams.removing_members:
        raise HTTPException(409, "This member is already being removed.")
    async with ctx.db.session() as session:
        member = await session.get(TeamMember, member_id)
        if member is None or member.team_id != team_id:
            raise HTTPException(status_code=404, detail=f"No member {member_id!r}")

        if is_producer(member):
            raise HTTPException(409, "The built-in Producer cannot be deleted.")
        if is_director(member):
            raise HTTPException(409, "The built-in Director cannot be deleted.")
        if is_effort_solver(member):
            raise HTTPException(409, "The built-in Effort Solver cannot be deleted.")

        open_tasks = list(
            (await session.exec(select(TeamTask).where(TeamTask.assignee_id == member_id))).all()
        )
        if open_tasks and not reassign_to_director:
            unfinished = [t for t in open_tasks if t.status not in TERMINAL_TASK_STATUSES]
            detail = (
                f"{member.name} has {len(open_tasks)} task(s) on the board"
                + (f", {len(unfinished)} of them unfinished" if unfinished else "")
                + ". Reassign or remove them first."
            )
            raise HTTPException(status_code=409, detail=detail)

        reports = list(
            (await session.exec(select(TeamMember).where(TeamMember.manager_id == member_id))).all()
        )
        if reports and not reassign_to_director:
            who = ", ".join(report.name for report in reports)
            raise HTTPException(
                status_code=409,
                detail=f"{who} report(s) to {member.name}. Give them another manager first.",
            )

        chats = list((await session.exec(select(AgentChat).where(AgentChat.member_id == member_id))).all())
        if chats and not reassign_to_director:
            raise HTTPException(409, "Confirm removal to preserve this member's chat history under Director.")
        director_id = f"{team_id}__{DIRECTOR_ID}"
        director = await session.get(TeamMember, director_id)
        if reassign_to_director and (director is None or director.kind != "human"):
            raise HTTPException(409, "This team needs its human Director before removing members.")

    # Block dispatch before cancelling: each finished job normally releases the next one.
    # Keep the gate until the DB transaction and portable roster are both written.
    if member_id in ctx.teams.removing_members:
        raise HTTPException(409, "This member is already being removed.")
    ctx.teams.removing_members.add(member_id)
    try:
        running = [handle for handle in ctx.jobs.jobs() if handle.meta.get("member_id") == member_id]
        for handle in running:
            await ctx.jobs.cancel(handle.id)
        await asyncio.gather(*(h.task for h in running if h.task), return_exceptions=True)
        for task in open_tasks:
            ctx.teams._cancel_limit_wait(task.id)

        async with ctx.db.session() as session:
            member = await session.get(TeamMember, member_id)
            if member is None:
                raise HTTPException(404, f"No member {member_id!r}")
            # Re-read after cancellation so final run writes (status, cost, history) survive.
            tasks = list((await session.exec(select(TeamTask).where(TeamTask.assignee_id == member_id))).all())
            chats = list((await session.exec(select(AgentChat).where(AgentChat.member_id == member_id))).all())
            roster = list((await session.exec(select(TeamMember).where(TeamMember.team_id == team_id))).all())
            # An assignment may have committed during the initial preflight, before the
            # removal gate closed. It still needs explicit permission to transfer.
            if not reassign_to_director and (
                tasks or chats or any(other.manager_id == member_id for other in roster)
            ):
                raise HTTPException(409, "This member now has tasks, chats or reports. Confirm removal to transfer them to Director.")
            for task in tasks:
                task.assignee_id = director_id
                task.agent_session_id = None
                task.auto_start = False
                task.pause_resume_pending = False
                task.limit_resume_at = None
                task.limit_reset_at = None
                task.remote_control_url = None
                if task.status == TaskStatus.IN_PROGRESS:
                    task.status = TaskStatus.PENDING
                session.add(task)
            for chat in chats:
                chat.member_id = director_id
                chat.agent_session_id = None
                session.add(chat)
            by_id = {other.id: other for other in roster}
            director = by_id.get(director_id)
            # A hand-edited hierarchy may place Director below the removed manager.
            # Detach that chain before moving reports to Director, avoiding a cycle.
            cursor = director
            seen: set[str] = set()
            while cursor is not None and cursor.id not in seen:
                seen.add(cursor.id)
                if cursor.manager_id == member_id:
                    director.manager_id = None
                    session.add(director)
                    break
                cursor = by_id.get(cursor.manager_id)
            for other in roster:
                if other.id == member_id:
                    continue
                if other.manager_id == member_id:
                    other.manager_id = director_id if other.id != director_id else None
                if member_id in (other.can_assign_to or []):
                    other.can_assign_to = [entry for entry in other.can_assign_to if entry != member_id]
                session.add(other)
            await session.flush()  # Move foreign keys before deleting their former parent.
            await session.delete(member)

        async with ctx.db.session() as session:
            await ctx.teams.store.save_from_db(session, team_id)
        await ctx.teams.sync_agent_definitions()
        for task in tasks:
            await ctx.teams._record_edit(
                task.id, {"assignee_id": (member_id, director_id)}, member_id=director_id, actor_id=None,
            )
            await ctx.teams._publish_task_changed(task.id)
        for chat in chats:
            await ctx.chats._publish_chat(chat)
        return {"member_id": member_id, "removed": True, "reassigned_tasks": len(tasks)}
    finally:
        ctx.teams.removing_members.discard(member_id)


@router.post("/{team_id}/tasks", status_code=201)
async def create_task(team_id: str, payload: TaskCreate, ctx: Ctx) -> dict[str, Any]:
    """Put a task on the board. It lands in ``todo`` and no session starts (§10.4)."""
    _check_task_overrides({"model": payload.model, "effort": payload.effort})
    if payload.agent_backend is not None and payload.agent_backend not in available_backends():
        raise HTTPException(400, "Unknown agent backend")
    try:
        task = await ctx.teams.create_task(
            team_id=team_id,
            assignee_id=payload.assignee_id,
            title=payload.title,
            brief=payload.brief,
            write_paths=payload.write_paths,
            attachments=payload.attachments,
            created_by=payload.created_by,
            depends_on=payload.depends_on,
            auto_start=payload.auto_start,
            model=payload.model,
            agent_backend=payload.agent_backend,
            effort=payload.effort,
            summary_locale=payload.summary_locale,
        )
    except RoutingError as exc:
        raise HTTPException(status_code=403, detail=str(exc)) from exc
    except DelegationLimitError as exc:
        raise HTTPException(status_code=409, detail=str(exc)) from exc
    except KeyError as exc:
        raise HTTPException(status_code=404, detail=str(exc)) from exc
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    return _task_json(ctx, task)


@router.get("/{team_id}/tasks")
async def list_tasks(
    team_id: str,
    ctx: Ctx,
    status: list[TaskStatus] | None = Query(default=None),
    assignee_id: str | None = Query(default=None),
    created_by: str | None = Query(default=None),
    depends_on: str | None = Query(default=None),
    search: str | None = Query(default=None),
    limit: int | None = Query(default=None, ge=1, le=500),
    archived: bool | None = Query(default=None),
) -> list[dict[str, Any]]:
    """The board, filtered. Same query layer the agents' ``list_tasks`` tool goes through.

    ``status`` repeats (``?status=todo&status=pending``); ``depends_on`` takes an opaque id
    or a display id and answers with the tasks *waiting on* it; ``archived`` picks one side
    of the archive and is omitted for both.
    """
    tasks = await ctx.teams.query_tasks(
        team_id=team_id,
        statuses=status,
        assignee_id=assignee_id,
        created_by=created_by,
        depends_on=depends_on,
        search=search,
        limit=limit,
        archived=archived,
    )
    return [_task_json(ctx, task) for task in tasks]


@router.get("/{team_id}/stats")
async def team_stats(team_id: str, ctx: Ctx) -> dict[str, Any]:
    """What the team's work has cost, over time and by member — the Statistics page.

    Archived tasks are in every figure here; hiding a card from the board must not make
    the project look cheaper (§10.4). The shape is in :mod:`voxarium_studio.teams.stats`.
    """
    async with ctx.db.session() as session:
        if await session.get(Team, team_id) is None:
            raise HTTPException(status_code=404, detail=f"No team {team_id!r}")
    return await ctx.teams.stats(team_id)


@router.get("/{team_id}/tasks/{task_id}")
async def get_task(team_id: str, task_id: str, ctx: Ctx) -> dict[str, Any]:
    async with ctx.db.session() as session:
        task = await session.get(TeamTask, task_id)
        if task is None or task.team_id != team_id:
            raise HTTPException(status_code=404, detail=f"No task {task_id!r}")
        messages = list(
            (
                await session.exec(
                    select(AgentMessage)
                    .where(AgentMessage.task_id == task_id)
                    .order_by(AgentMessage.created_at)  # type: ignore[arg-type]
                )
            ).all()
        )
        assignee = await session.get(TeamMember, task.assignee_id)
        # §10.3: worker counters survive task transcript compaction and archiving.
        subagents = (await session.exec(
            select(TaskSubagent).where(TaskSubagent.task_id == task_id)
            .order_by(TaskSubagent.created_at, TaskSubagent.id)
        )).all()
    return {
        **_task_json(ctx, task),
        "assignee": assignee.model_dump(mode="json") if assignee else None,
        "messages": [m.model_dump(mode="json") for m in messages],
        "subagents": [row.model_dump(mode="json") for row in subagents],
    }


async def _retained_task_context(team_id: str, task_id: str, ctx: Ctx, view: ContextView,
                                 ) -> tuple[list[dict], dict, str | None]:
    """Read the captured context without dispatching an agent or reloading materials (§10.4)."""
    from ..models.task_context import TaskContext
    from .session_context import read_context

    async with ctx.db.session() as session:
        task = await session.get(TeamTask, task_id)
        if task is None or task.team_id != team_id:
            raise HTTPException(status_code=404, detail=f"No task {task_id!r}")
        snapshot = await session.get(TaskContext, task_id)
        member = await session.get(TeamMember, task.assignee_id) if task.assignee_id else None
        messages = list((await session.exec(select(AgentMessage).where(
            AgentMessage.task_id == task_id,
        ).order_by(AgentMessage.created_at, AgentMessage.id))).all())
    parts, metrics = await read_context(snapshot, messages, member, task.agent_session_id, view)
    return parts, metrics, task.agent_session_id


@router.get("/{team_id}/tasks/{task_id}/context")
async def get_task_context(team_id: str, task_id: str, ctx: Ctx, view: ContextView = "auto") -> dict[str, Any]:
    from .session_context import inventory_response

    parts, metrics, session_id = await _retained_task_context(team_id, task_id, ctx, view)
    return inventory_response(task_id, parts, metrics, session_id)


@router.get("/{team_id}/tasks/{task_id}/context/fragments/{source_id}")
async def get_context_fragment(team_id: str, task_id: str, source_id: int, ctx: Ctx,
                               key: str = Query(pattern=r"^[a-f0-9]{64}$"),
                               view: ContextView = "auto") -> dict[str, str]:
    from .session_context import fragment_response

    parts, _, _ = await _retained_task_context(team_id, task_id, ctx, view)
    return fragment_response(parts, source_id, key)


@router.post("/{team_id}/tasks/{task_id}/context/inspect")
async def inspect_task_context(team_id: str, task_id: str, ctx: Ctx) -> dict[str, Any]:
    """An explicit request to the existing runtime, never a new agent turn (§10.4)."""
    from .session_context import inspect_session

    async with ctx.db.session() as session:
        task = await session.get(TeamTask, task_id)
        if task is None or task.team_id != team_id:
            raise HTTPException(status_code=404, detail=f"No task {task_id!r}")
    return await inspect_session(task_id, task.agent_session_id)


@router.patch("/{team_id}/tasks/{task_id}")
async def update_task(
    team_id: str, task_id: str, payload: TaskUpdate, ctx: Ctx
) -> dict[str, Any]:
    """Edit a task's fields, move it between columns, or both."""
    fields = payload.model_dump(exclude_unset=True)
    actor_id = fields.pop("actor_id", None)
    summary_locale = fields.pop("summary_locale", None)
    # Almost none of a task's editable fields are nullable, so an explicit null carries no
    # meaning a `None` default does not — drop both rather than let one through to clear a
    # title or orphan an assignee. The exception is the pair of overrides, where a null is
    # the whole edit: it hands the task back to the assignee's model and effort.
    fields = {
        field: value
        for field, value in fields.items()
        if value is not None or field in _NULLABLE_TASK_FIELDS
    }
    _check_task_overrides(fields)
    if fields.get("agent_backend") is not None and fields["agent_backend"] not in available_backends():
        raise HTTPException(400, "Unknown agent backend")
    async with ctx.db.session() as session:
        task = await session.get(TeamTask, task_id)
        if task is None or task.team_id != team_id:
            raise HTTPException(status_code=404, detail=f"No task {task_id!r}")
    try:
        task = await ctx.teams.update_task(
            task_id, fields, actor_id=actor_id, summary_locale=summary_locale, from_board=True,
        )
    except RoutingError as exc:
        raise HTTPException(status_code=403, detail=str(exc)) from exc
    except KeyError as exc:
        raise HTTPException(status_code=404, detail=str(exc)) from exc
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    return _task_json(ctx, task)


@router.delete("/{team_id}/tasks/{task_id}")
async def delete_task(
    team_id: str, task_id: str, ctx: Ctx, actor_id: str | None = Query(default=None)
) -> dict[str, Any]:
    async with ctx.db.session() as session:
        task = await session.get(TeamTask, task_id)
        if task is None or task.team_id != team_id:
            raise HTTPException(status_code=404, detail=f"No task {task_id!r}")
    try:
        await ctx.teams.delete_task(task_id, actor_id=actor_id)
    except RoutingError as exc:
        raise HTTPException(status_code=403, detail=str(exc)) from exc
    except KeyError as exc:
        raise HTTPException(status_code=404, detail=str(exc)) from exc
    except ValueError as exc:
        raise HTTPException(status_code=409, detail=str(exc)) from exc
    return {"task_id": task_id, "deleted": True}


@router.post("/{team_id}/tasks/{task_id}/compress")
async def compress_task(
    team_id: str, task_id: str, payload: ActorAction, ctx: Ctx
) -> dict[str, Any]:
    await _require_task(ctx, team_id, task_id)
    try:
        task = await ctx.teams.compress_task(task_id, actor_id=payload.actor_id)
    except RoutingError as exc:
        raise HTTPException(status_code=403, detail=str(exc)) from exc
    except KeyError as exc:
        raise HTTPException(status_code=404, detail=str(exc)) from exc
    except ValueError as exc:
        raise HTTPException(status_code=409, detail=str(exc)) from exc
    except (RuntimeError, TimeoutError) as exc:
        raise HTTPException(status_code=502, detail=str(exc) or "Context compression timed out") from exc
    return _task_json(ctx, task)


@router.post("/{team_id}/tasks/{task_id}/reset")
async def reset_task(
    team_id: str, task_id: str, payload: ActorAction, ctx: Ctx
) -> dict[str, Any]:
    """Clear what a run left behind — session, transcript, summary, cost — and back to Todo.

    The task itself stays: title, brief, assignee, dependencies and auto-start are untouched,
    so this is "run it again from scratch" rather than "delete and retype it".

    409 while a session is running: the job engine owns the row until the run ends.
    """
    async with ctx.db.session() as session:
        task = await session.get(TeamTask, task_id)
        if task is None or task.team_id != team_id:
            raise HTTPException(status_code=404, detail=f"No task {task_id!r}")
    try:
        task = await ctx.teams.reset_task(task_id, actor_id=payload.actor_id)
    except RoutingError as exc:
        raise HTTPException(status_code=403, detail=str(exc)) from exc
    except KeyError as exc:
        raise HTTPException(status_code=404, detail=str(exc)) from exc
    except ValueError as exc:
        raise HTTPException(status_code=409, detail=str(exc)) from exc
    return _task_json(ctx, task)


@router.post("/{team_id}/tasks/{task_id}/start", status_code=202)
async def start_task(
    team_id: str, task_id: str, ctx: Ctx, payload: TaskRunAction | None = None
) -> dict[str, Any]:
    """Start the assignee's session, or resume the one it already has (§10.3).

    Start sends the brief; resume does not — the session it reopens already holds it, and
    carries only the notes written since that session stopped.

    409 covers every refusal, and the detail says which: the task is not in ``pending`` (a
    session runs from that column and nowhere else, §10.4), it is blocked by an unfinished
    dependency, it is already running, or the assignee is a person.
    """
    try:
        task = await ctx.teams.start_task(
            task_id, summary_locale=payload.summary_locale if payload else None, from_board=True,
        )
    except KeyError as exc:
        raise HTTPException(status_code=404, detail=str(exc)) from exc
    except ValueError as exc:
        raise HTTPException(status_code=409, detail=str(exc)) from exc
    return {"task_id": task.id, "status": str(task.status)}


@router.post("/{team_id}/tasks/{task_id}/claim")
async def claim_task(
    team_id: str, task_id: str, payload: ActorAction, ctx: Ctx
) -> dict[str, Any]:
    """A human member takes a task off their inbox (§10.4). Agents never need this."""
    try:
        task = await ctx.teams.claim_task(task_id, actor_id=payload.actor_id)
    except RoutingError as exc:
        raise HTTPException(status_code=403, detail=str(exc)) from exc
    except KeyError as exc:
        raise HTTPException(status_code=404, detail=str(exc)) from exc
    except ValueError as exc:
        raise HTTPException(status_code=409, detail=str(exc)) from exc
    return _task_json(ctx, task)


@router.post("/{team_id}/tasks/{task_id}/notes", status_code=201)
async def add_note(
    team_id: str, task_id: str, payload: NoteCreate, ctx: Ctx
) -> dict[str, Any]:
    """Append a note to the transcript. An agent assignee sees it on its next resume — or,
    written while its session is working, at that session's next step (§10.8)."""
    try:
        message = await ctx.teams.add_note(task_id, payload.text, author_id=payload.actor_id)
    except RoutingError as exc:
        raise HTTPException(status_code=403, detail=str(exc)) from exc
    except KeyError as exc:
        raise HTTPException(status_code=404, detail=str(exc)) from exc
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    return message.model_dump(mode="json")


@router.post("/{team_id}/tasks/{task_id}/complete")
async def complete_task(
    team_id: str, task_id: str, payload: CompleteAction, ctx: Ctx
) -> dict[str, Any]:
    """A human declares their work done: on to review, or straight to done if unmanaged."""
    try:
        task = await ctx.teams.complete_task(
            task_id,
            payload.summary,
            result_type=str(payload.result_type),
            actor_id=payload.actor_id,
        )
    except RoutingError as exc:
        raise HTTPException(status_code=403, detail=str(exc)) from exc
    except KeyError as exc:
        raise HTTPException(status_code=404, detail=str(exc)) from exc
    except ValueError as exc:
        raise HTTPException(status_code=409, detail=str(exc)) from exc
    return _task_json(ctx, task)


@router.post("/{team_id}/tasks/{task_id}/approve")
async def approve_task(
    team_id: str, task_id: str, payload: ReviewAction, ctx: Ctx
) -> dict[str, Any]:
    try:
        task = await ctx.teams.approve(task_id, actor_id=payload.actor_id)
    except RoutingError as exc:
        raise HTTPException(status_code=403, detail=str(exc)) from exc
    except KeyError as exc:
        raise HTTPException(status_code=404, detail=str(exc)) from exc
    return _task_json(ctx, task)


@router.post("/{team_id}/tasks/{task_id}/request-changes")
async def request_changes(
    team_id: str, task_id: str, payload: ReviewAction, ctx: Ctx
) -> dict[str, Any]:
    if not payload.comment.strip():
        raise HTTPException(status_code=400, detail="A review comment is required")
    try:
        task = await ctx.teams.request_changes(
            task_id,
            payload.comment,
            actor_id=payload.actor_id,
            attachments=payload.attachments,
            summary_locale=payload.summary_locale,
            from_board=True,
        )
    except RoutingError as exc:
        raise HTTPException(status_code=403, detail=str(exc)) from exc
    except (KeyError, FileNotFoundError) as exc:
        raise HTTPException(status_code=404, detail=str(exc)) from exc
    except ValueError as exc:
        raise HTTPException(status_code=409, detail=str(exc)) from exc
    return _task_json(ctx, task)


# ------------------------------------------------------------------ review attachments


@router.post("/{team_id}/tasks/{task_id}/attachments", status_code=201)
async def add_task_attachment(
    team_id: str,
    task_id: str,
    ctx: Ctx,
    file: UploadFile = File(..., description="A picture or a file for the review comment"),
) -> dict[str, Any]:
    """Store a file for a review comment and answer with its record (§10.4).

    Multipart, like a picture dropped into a document, and for the same reason: the bytes
    are already on the client. The review itself (``request-changes``) names the stored
    files afterwards, so a failed send leaves a spare file rather than a half-sent review.
    """
    if await ctx.teams.find_task(task_id, team_id=team_id) is None:
        raise HTTPException(status_code=404, detail=f"No task {task_id}")
    try:
        data = await file.read(MAX_ATTACHMENT_BYTES + 1)
        return ctx.teams.attachments.add(task_id, file.filename or "", data)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    except OSError as exc:
        raise HTTPException(status_code=500, detail=str(exc)) from exc
    finally:
        await file.close()


@router.get("/{team_id}/tasks/{task_id}/attachments/{name}")
async def read_task_attachment(team_id: str, task_id: str, name: str, ctx: Ctx) -> Response:
    """The bytes of a stored attachment — an ``<img>`` source or a download.

    The token may ride the query string (``core/security.py``), because an ``<img>`` cannot
    carry a header; that is how the transcript shows a review's pictures.
    """
    try:
        target = ctx.teams.attachments.path_for(task_id, name)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    except FileNotFoundError as exc:
        raise HTTPException(status_code=404, detail=str(exc)) from exc
    media_type = media_type_of(target.name)
    return FileResponse(
        target,
        filename=target.name,
        media_type=media_type or "application/octet-stream",
        content_disposition_type="inline" if media_type else "attachment",
        headers={**UNTRUSTED_FILE_HEADERS, "Cache-Control": "no-cache"},
    )


@router.delete("/{team_id}/tasks/{task_id}/attachments/{name}", status_code=204)
async def remove_task_attachment(team_id: str, task_id: str, name: str, ctx: Ctx) -> Response:
    """Take back a file uploaded for a review that was not sent with it."""
    try:
        ctx.teams.attachments.remove(task_id, name)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    except FileNotFoundError as exc:
        raise HTTPException(status_code=404, detail=str(exc)) from exc
    return Response(status_code=204)


@router.post("/{team_id}/tasks/{task_id}/cancel")
async def cancel_task(team_id: str, task_id: str, ctx: Ctx) -> dict[str, Any]:
    cancelled = await ctx.teams.cancel_task(task_id)
    return {"task_id": task_id, "cancelled": cancelled}


@router.post("/{team_id}/tasks/{task_id}/remote-control")
async def enable_remote_control(team_id: str, task_id: str, ctx: Ctx) -> dict[str, Any]:
    """Hand this task's running session to Remote Control (§10.8) and answer with its link.

    409 means there is no session to hand over — the task is not running. 502 means the
    session refused, and the detail is the CLI's own reason (no claude.ai subscription auth,
    disabled by organisation policy), which is what the user has to act on.
    """
    async with ctx.db.session() as session:
        task = await session.get(TeamTask, task_id)
        if task is None or task.team_id != team_id:
            raise HTTPException(status_code=404, detail=f"No task {task_id!r}")
    try:
        link = await ctx.teams.enable_remote_control(task_id)
    except KeyError as exc:
        raise HTTPException(status_code=404, detail=str(exc)) from exc
    except ValueError as exc:
        raise HTTPException(status_code=409, detail=str(exc)) from exc
    except RemoteControlUnavailable as exc:
        raise HTTPException(status_code=502, detail=str(exc)) from exc
    return {
        "task_id": task_id,
        "session_url": link.session_url,
        "connect_url": link.connect_url,
        "environment_id": link.environment_id,
    }


@router.delete("/{team_id}/tasks/{task_id}/remote-control")
async def disable_remote_control(team_id: str, task_id: str, ctx: Ctx) -> dict[str, Any]:
    async with ctx.db.session() as session:
        task = await session.get(TeamTask, task_id)
        if task is None or task.team_id != team_id:
            raise HTTPException(status_code=404, detail=f"No task {task_id!r}")
    try:
        await ctx.teams.disable_remote_control(task_id)
    except KeyError as exc:
        raise HTTPException(status_code=404, detail=str(exc)) from exc
    except RemoteControlUnavailable as exc:
        raise HTTPException(status_code=502, detail=str(exc)) from exc
    return {"task_id": task_id, "session_url": None}


async def _require_task(ctx: AppContext, team_id: str, task_id: str) -> TeamTask:
    """The task, or a 404 — the existence check the routing guards cannot do (§10.4).

    Same shape as the inline check the older handlers spell out for themselves; it is a
    function here because the two endpoints below need it before touching the filesystem,
    and an unknown id reaching a `git` invocation is a 500 where it should be a 404.
    """
    async with ctx.db.session() as session:
        task = await session.get(TeamTask, task_id)
    if task is None or task.team_id != team_id:
        raise HTTPException(status_code=404, detail=f"No task {task_id!r}")
    return task


@router.get("/{team_id}/tasks/{task_id}/changes")
async def get_task_changes(team_id: str, task_id: str, ctx: Ctx) -> dict[str, Any]:
    """What this task changed — its own checkout where it has one, its tool calls where not.

    Never a 409 for "no version control": a task with no checkout still answers, from its
    transcript, and the payload's ``source`` says which question was actually answered. The
    column that reads this is beside the transcript on every task, and a section that
    disappears for half of them is worse than one that says what it knows.
    """
    await _require_task(ctx, team_id, task_id)
    try:
        return await ctx.teams.task_changes(task_id)
    except ValueError as exc:
        raise HTTPException(status_code=404, detail=str(exc)) from exc


class TaskCommitInput(BaseModel):
    """What the Commit button sends: the body as the reviewer left it, and the files, or
    neither to commit the draft as drawn."""

    message: str | None = None
    files: list[str] | None = None


@router.get("/{team_id}/tasks/{task_id}/commit")
async def get_task_commit(team_id: str, task_id: str, ctx: Ctx) -> dict[str, Any]:
    """The commit drafted for this task (§10.4): title, description, files, and whether the
    project has anything to commit with. Never a 409 for "no version control" — the section
    is drawn on every task and says so itself through ``vcs``."""
    await _require_task(ctx, team_id, task_id)
    try:
        return await ctx.teams.commit_proposal(task_id)
    except ValueError as exc:
        raise HTTPException(status_code=404, detail=str(exc)) from exc


@router.post("/{team_id}/tasks/{task_id}/commit/regenerate")
async def regenerate_task_commit(team_id: str, task_id: str, ctx: Ctx) -> dict[str, Any]:
    """Draft again — the same thing arriving in review does, on request."""
    await _require_task(ctx, team_id, task_id)
    ctx.teams.start_commit_proposal(task_id)
    return await ctx.teams.commit_proposal(task_id)


@router.post("/{team_id}/tasks/{task_id}/commit")
async def commit_task(
    team_id: str, task_id: str, payload: TaskCommitInput, ctx: Ctx
) -> dict[str, Any]:
    """Commit the task's files through the active version control backend (§9.6).

    ``committed: false`` is a normal answer — the files were already committed, or undone.
    A tool that is missing is 503 and one that refused is 409, exactly as ``/api/vcs/commit``
    maps them; a request the Studio itself refuses (no backend, a path outside the task's
    directory, nothing listed) is a 409 with the reason.
    """
    await _require_task(ctx, team_id, task_id)
    try:
        result = await ctx.teams.commit_task(
            task_id, message=payload.message, files=payload.files
        )
    except ValueError as exc:
        raise HTTPException(status_code=409, detail=str(exc)) from exc
    except VcsError as exc:
        backend = ctx.vcs
        if backend is not None:
            available, reason = backend.is_available()
            if not available:
                raise HTTPException(
                    status_code=503, detail=f"{backend.name} is not usable: {reason}"
                ) from exc
        raise HTTPException(status_code=409, detail=str(exc)) from exc
    return result.to_json()


@router.get("/{team_id}/tasks/{task_id}/changes/patch")
async def get_task_patch(team_id: str, task_id: str, path: str, ctx: Ctx) -> dict[str, str]:
    """One file's change as a patch. ``path`` is as `changes` reported it, verbatim."""
    await _require_task(ctx, team_id, task_id)
    try:
        return {"path": path, "patch": await ctx.teams.task_patch(task_id, path)}
    except ValueError as exc:
        raise HTTPException(status_code=409, detail=str(exc)) from exc


@router.delete("/{team_id}/tasks/{task_id}/worktree")
async def cleanup_worktree(
    team_id: str, task_id: str, ctx: Ctx, force: bool = False
) -> dict[str, Any]:
    await ctx.teams.cleanup_worktree(task_id, force=force)
    return {"task_id": task_id, "removed": True}
