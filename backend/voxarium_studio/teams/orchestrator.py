"""Team orchestration: the Task Board, routing rules and agent session lifecycle (§10.4).

The board is ours rather than Claude Code's built-in team/mailbox state, because the
latter is scoped to one interactive session and is discarded when that session ends —
the Studio needs a team that survives restarts and is driven from a UI (§10.1).
"""

from __future__ import annotations

import asyncio
import contextlib
import logging
import os
from collections.abc import Iterable, Sequence
from dataclasses import dataclass, replace
from datetime import datetime, timedelta
from pathlib import Path
from typing import Any

from sqlalchemy import delete, func, update
from sqlalchemy.exc import IntegrityError
from sqlmodel import select

from ..core.config import AppConfig, resolve_launch_root
from ..core.db import Database
from ..core.mcp import allowed_tool_names as mcp_allowed_tools
from ..core.mcp import enabled_servers as project_mcp_servers
from ..core.paths import ProjectPaths, current_data_path
from ..core.prompts import AgentPromptsConfig
from ..engine.jobs import JobEngine, JobHandle
from ..models.base import new_id, utcnow
from ..models.teams import (
    TERMINAL_TASK_STATUSES,
    AgentChat,
    AgentChatMessage,
    AgentMessage,
    MemberKind,
    ProjectCounter,
    Team,
    TeamMember,
    TeamTask,
    TaskResultType,
    TaskStatus,
    format_task_id,
    parse_task_number,
)
from ..vcs import CommitResult, VcsBackend, VcsError, project_relative
from ..ws.bus import EventBus
from ..ws.events import EventType
from . import agents as agent_defs
from . import changes as task_changes_source
from . import commit_proposal
from . import compaction
from . import limits as usage_limits
from . import skill as studio_skill
from . import stats as cost_stats_source
from . import temp as task_temp
from . import temp_changes
from . import worktree as wt
from .attachments import AttachmentStore, with_trailer
from .authoring import ProjectAuthoring
from .transcript import ALWAYS_KINDS, compact_state, verbose_only
from .backends.base import (
    DELIVERED,
    QUEUED,
    AgentEvent,
    AgentRunRequest,
    Inbox,
    QueuedMessage,
    RemoteControl,
    get_backend,
    live_session,
    member_run_settings,
)
from .backends.recovery import recovery_prompt
from .docs import DocStore
from .effort_solver import EFFORT_SOLVER_ID
from .effort_solver_runner import solve_effort
from .file_observer import FileObserver
from .memory import MemoryStore, memory_prompt
from .project_skills import MemberSkills, Skill, SkillStore
from .required_context import load_required_context
from .project_skills import prompt_list as skill_prompt_list
from .slots import agent_slot, span
from .subagents import SubagentManager, results_prompt
from .store import TeamStore
from .tools import build_toolset
from .usage import RunUsage, usage_components, usage_directions

log = logging.getLogger(__name__)


def summary_language(locale: str | None) -> str:
    """Resolve the per-task locale without inventing a global project language (§10.4)."""
    return {"ru": "Russian", "en": "English"}.get(
        locale or "", "the language used by the user in the task brief and conversation"
    )


def _summary_locale(locale: str | None) -> str | None:
    if locale is not None and locale not in {"en", "ru"}:
        raise ValueError("Summary locale must be en or ru")
    return locale


COMMIT_DRAFT_SHUTDOWN_GRACE = 5.0
"""Seconds a shutdown lets a commit draft finish before cancelling it (§10.4)."""


class RoutingError(PermissionError):
    """Raised when an assignment or review breaks the management vertical."""


class DelegationLimitError(RuntimeError):
    """Raised when agent-to-agent delegation would exceed the configured ceiling.

    Distinct from RoutingError: the route is legitimate, the work has just spread far
    enough. Every hop costs a billed session, so this bound cannot rely on the models
    deciding to stop on their own.
    """


@dataclass(frozen=True, slots=True)
class _RunOutcome:
    """What one ``backend.run`` came to: whether it failed, the limit that stopped it if
    one did, and its closing words — the summary when nothing better was reported."""

    errored: bool
    limit: usage_limits.UsageLimit | None
    final_text: str


class TeamOrchestrator:
    def __init__(
        self,
        *,
        paths: ProjectPaths,
        db: Database,
        bus: EventBus,
        jobs: JobEngine,
        config: AppConfig,
        docs: DocStore | None = None,
        skills: SkillStore | None = None,
        memory: MemoryStore | None = None,
    ) -> None:
        self.paths = paths
        self.db = db
        self.bus = bus
        self.jobs = jobs
        self.config = config
        self.store = TeamStore(paths, prompts=lambda: self.config.teams.prompts)
        self.docs = docs if docs is not None else DocStore(paths, bus)
        self.skills = skills if skills is not None else SkillStore(paths)
        """``.voxarium_studio/skills/`` — what a member's ``skills`` names resolve against (§10.10)."""
        self.memory = memory if memory is not None else MemoryStore(paths, bus)
        """``.voxarium_studio/memory/`` — the index every run is sent and the tools keep (§10.12)."""
        self.authoring: ProjectAuthoring | None = None
        """Live project authoring services, wired by AppContext for MCP (§10.7)."""
        self.attachments = AttachmentStore(paths)
        """``.voxarium_studio/attachments/`` — what a reviewer attached to a review comment (§10.4)."""
        self.vcs: VcsBackend | None = None
        """Set by :class:`AppContext` once detection has run — the orchestrator is built
        before that, and a task started without it simply falls back to path locks."""

        self._path_locks = wt.PathLockManager()
        # §10.5: publishing a new write set and taking a run's snapshot are atomic.
        self._write_paths_guards: dict[str, asyncio.Lock] = {}
        #: Runs whose session may change its own write scope (§10.5): the claims it holds
        #: in the shared copy, or ``None`` for an isolated checkout, which holds none.
        self._run_writes: dict[str, list[wt.PathClaim] | None] = {}
        self._temp_locks: dict[str, asyncio.Lock] = {}
        self.subagents = SubagentManager(self)
        self.paused_producer_chats: dict[str, set[str]] = {}
        """Paused chat IDs and the tasks explicitly released on the board during each pause (§10.11).

        A release may wait for a dependency or a Solo slot. It belongs to this pause only:
        a fresh Pause (including project startup) holds the chat's tasks again.
        """
        self._inboxes: dict[str, Inbox] = {}
        """Notes written to each task while its run is in flight (§10.8), for the length of
        it: the session gets them at its next tool boundary or as its next turn, instead of
        on the resume after this one."""
        self._ending: set[str] = set()
        """Runs in their last lines — locks released, the member's queue being dispatched.

        A job stays in the engine's registry until its coroutine returns, and the tail of
        ``_run_task`` is inside that coroutine; without this, the run that has just freed a
        solo-mode member would count as still holding them (``_solo_holder``).
        """
        self.removing_members: set[str] = set()
        """Members being removed: no new assignments or sessions during the transfer (§10.2)."""
        self._number_lock = asyncio.Lock()
        """Serialises task-number allocation (§10.2). ``MAX(number) + 1`` read in one
        session and committed in another would hand two concurrent creations the same
        number — two agents delegating in the same instant is exactly the case. The unique
        index is the backstop; this is what stops it ever having to fire."""

        self._release_lock = asyncio.Lock()
        """Serialises bulk release and incoming Todo autostarts (§10.4)."""

        self._limit_waits: dict[str, asyncio.Task[None]] = {}
        """Tasks sleeping out a usage limit, keyed by task id (§10.3).

        Deliberately **not** in the job engine, though a wait is the only other thing here
        that outlives a request. A job holds an ``agent`` concurrency slot and reads as a
        run in flight — ``is_running`` gates Start, Delete, Reset and the solo queue on it —
        and a task waiting five hours for a window to refill is doing none of that. The
        durable half of the wait is ``TeamTask.limit_resume_at`` on the row, which is what
        re-arms these across a restart (:meth:`resume_limit_waits`); this dict is only the
        timer.
        """

        self._commit_jobs: dict[str, asyncio.Task[None]] = {}
        """Commit drafts being written, keyed by task id (§10.4).

        Not in the job engine for the reason the limit waits are not: a draft is a cheap
        model call that must not read as the task running. One per task — a resubmission
        while the previous draft is still being written cancels it, since it would describe
        work that has since changed.
        """

        self.commit_describer: commit_proposal.Describer | None = None
        """What writes a commit description; ``None`` means the Agent SDK
        (:func:`~voxarium_studio.teams.commit_proposal.describe_with_claude`). A test sets a fake
        here, and the suite's conftest patches the module default so nothing reaches a
        billed model by accident."""

    # ------------------------------------------------------------------ setup

    async def sync_definitions(self) -> None:
        async with self.db.session() as session:
            await self.store.sync_to_db(session)
        await self.sync_agent_definitions()

    async def set_autostart_tasks(self, team_id: str, enabled: bool) -> None:
        """Persist the board's opt-in and release its current Todo tasks (§10.4)."""
        async with self.db.session() as session:
            team = await session.get(Team, team_id)
            if team is None:
                raise KeyError(team_id)
            team.autostart_tasks = enabled
            session.add(team)
            await session.flush()
            await self.store.save_from_db(session, team_id)
        await self.bus.publish(
            EventType.TEAM_UPDATED, {"team_id": team_id, "autostart_tasks": enabled}
        )
        if enabled:
            await self._release_tasks(team_id, automatic=True)

    async def resume_autostart_tasks(self) -> None:
        """Restore enabled teams' intake and released queues after opening a project (§10.4)."""
        async with self.db.session() as session:
            teams = list((await session.exec(select(Team))).all())
            pending = list((await session.exec(
                select(TeamTask).where(TeamTask.status == TaskStatus.PENDING)
            )).all())
        for team in teams:
            if team.autostart_tasks:
                await self._release_tasks(team.id, automatic=True)
                await self._start_if_ready([
                    task.id for task in pending if task.team_id == team.id
                ])

    async def start_all_tasks(self, team_id: str, *, summary_locale: str | None = None) -> list[str]:
        """Release this team's Todo/Pending agent tasks, keeping dependency/Solo gates (§10.4)."""
        return await self._release_tasks(team_id, summary_locale=summary_locale)

    def producer_holds(self, task: TeamTask) -> bool:
        released = self.paused_producer_chats.get(task.producer_chat_id)
        return released is not None and task.id not in released

    def _set_board_release(self, task: TeamTask, enabled: bool) -> None:
        released = self.paused_producer_chats.get(task.producer_chat_id)
        if released is not None:
            if enabled:
                released.add(task.id)
            else:
                released.discard(task.id)

    async def _autostart_todo(self, task: TeamTask) -> None:
        if task.status == TaskStatus.TODO:
            await self._release_tasks(task.team_id, automatic=True, task_id=task.id)

    async def _release_tasks(
        self, team_id: str, *, automatic: bool = False, task_id: str | None = None,
        summary_locale: str | None = None,
    ) -> list[str]:
        async with self._release_lock:
            async with self.db.session() as session:
                team = await session.get(Team, team_id)
                if team is None:
                    raise KeyError(team_id)
                if automatic and not team.autostart_tasks:
                    return []
                statuses = [TaskStatus.TODO] if automatic else [TaskStatus.TODO, TaskStatus.PENDING]
                query = (
                    select(TeamTask)
                    .join(TeamMember, TeamTask.assignee_id == TeamMember.id)
                    .where(TeamTask.team_id == team_id)
                    .where(TeamTask.status.in_(statuses))
                    .where(TeamTask.archived == False)  # noqa: E712
                    .where(TeamMember.kind == MemberKind.AGENT)
                    .order_by(TeamTask.number)
                )
                if task_id is not None:
                    query = query.where(TeamTask.id == task_id)
                candidates = list((await session.exec(query)).all())
                candidates = [t for t in candidates if not t.producer_chat_id or t.producer_approved]

            released: list[str] = []
            for candidate in candidates:
                try:
                    # Earlier releases can finish a session or dispatch this task while
                    # the batch is being processed. Never restart its completed result.
                    current = await self._reload(candidate.id)
                    if current.status not in statuses or self.jobs.is_running(candidate.id):
                        continue
                    if automatic and self.producer_holds(current):
                        continue
                    # Keep the normal edit/status history and dispatch path. Enabling the
                    # per-task flag also queues dependencies and a busy solo member.
                    await self.update_task(
                        candidate.id, {"status": TaskStatus.PENDING, "auto_start": True},
                        summary_locale=summary_locale, from_board=not automatic,
                    )
                    if current.status == TaskStatus.PENDING and current.auto_start:
                        # An unchanged Pending task has no edit to trigger dispatch.
                        await self._start_if_ready([candidate.id])
                    released.append(candidate.id)
                except (KeyError, ValueError):
                    # A concurrent delete/start must not prevent the rest of the batch.
                    log.exception("Could not release task %s", candidate.id)
            return released

    @property
    def task_code(self) -> str:
        """The project's code, as it appears in front of every task number (§10.2)."""
        return self.config.task_code(self.paths.root.name)

    def display_id(self, task: TeamTask) -> str:
        return format_task_id(self.task_code, task.number)

    # ------------------------------------------------------------------ task numbers

    TASK_NUMBER_COUNTER = "task_number"

    async def _next_task_number(self, session) -> int:
        """One past the highest number this project has *ever* used, and record that.

        Both halves matter. ``MAX(number)`` alone would hand the next task a number a
        deleted one already spent — deleting the newest task lowers the maximum, and the id
        is by then in briefs and commit messages. A stored counter alone would be wrong on
        a database that predates it, and immediately after :meth:`ensure_task_numbers`. So
        the counter is the answer and the maximum is the floor it is dragged up to.

        Called under ``_number_lock``; the caller's session is what commits the increment,
        so the allocation and the INSERT that uses it land together or not at all.
        """
        highest = int((await session.exec(select(func.max(TeamTask.number)))).one() or 0)
        counter = await session.get(ProjectCounter, self.TASK_NUMBER_COUNTER)
        if counter is None:
            counter = ProjectCounter(name=self.TASK_NUMBER_COUNTER, value=highest)
        counter.value = max(counter.value, highest) + 1
        session.add(counter)
        return counter.value

    async def ensure_task_numbers(self) -> int:
        """Number the tasks that predate the column, oldest first, and arm the counter (§10.2).

        Runs once per startup, between the schema step that adds the column and the one
        that creates the unique index over it — a database opened by an older Studio has
        every task at ``0``, and the index cannot exist until they differ.

        The order is creation time, tie-broken by id: it is the order the board was built
        in, so the numbers a project gains retroactively read as the history it actually
        had. Returns how many rows it touched, which is zero on every startup after the
        first.

        **The counter is seeded even when nothing is stale**, and that is not tidiness. A
        project can reach a numbered board with no counter row — a Studio that backfilled
        before this table existed, a database restored from elsewhere — and there the
        no-reuse guarantee quietly does not hold: delete the highest task and the next one
        created seeds a fresh counter from a maximum that has just come down. Seeding here
        makes the guarantee true from the first startup rather than from the first
        creation.
        """
        async with self._number_lock:
            async with self.db.session() as session:
                stale = list(
                    (
                        await session.exec(
                            select(TeamTask)
                            .where(TeamTask.number <= 0)
                            .order_by(TeamTask.created_at, TeamTask.id)  # type: ignore[arg-type]
                        )
                    ).all()
                )
                for task in stale:
                    task.number = await self._next_task_number(session)
                    session.add(task)
                await self._seed_task_counter(session)
        if stale:
            log.info("Numbered %d task(s) that predate the sequential id", len(stale))
        return len(stale)

    async def _seed_task_counter(self, session) -> None:
        """Make sure the counter exists and is not behind the board. Never lowers it."""
        highest = int((await session.exec(select(func.max(TeamTask.number)))).one() or 0)
        counter = await session.get(ProjectCounter, self.TASK_NUMBER_COUNTER)
        if counter is None:
            counter = ProjectCounter(name=self.TASK_NUMBER_COUNTER, value=highest)
        elif counter.value >= highest:
            return
        counter.value = highest
        session.add(counter)

    # ------------------------------------------------------------------ queries

    async def find_task(
        self, reference: str, *, team_id: str | None = None
    ) -> TeamTask | None:
        """Resolve a task from whatever a caller has: ``VS-01``, ``12``, or ``task_…``.

        The opaque id is tried first because it is what every URL and event carries; a
        human-readable reference is then parsed for its **number** and the code in front of
        it ignored, since renaming a project's code must not orphan the ids already written
        down (:func:`parse_task_number`).
        """
        reference = (reference or "").strip()
        if not reference:
            return None
        async with self.db.session() as session:
            task = await session.get(TeamTask, reference)
            if task is None:
                number = parse_task_number(reference)
                if number is None:
                    return None
                task = (
                    await session.exec(select(TeamTask).where(TeamTask.number == number))
                ).first()
        if task is None or (team_id is not None and task.team_id != team_id):
            return None
        return task

    async def query_tasks(
        self,
        *,
        team_id: str | None = None,
        statuses: list[TaskStatus] | None = None,
        assignee_id: str | None = None,
        created_by: str | None = None,
        depends_on: str | None = None,
        search: str | None = None,
        limit: int | None = None,
        archived: bool | None = None,
    ) -> list[TeamTask]:
        """The board, filtered — one implementation behind both REST and the agent tools.

        Newest first, which is the order the board and the rail already read in. ``search``
        is a case-insensitive substring of the title or the brief; ``depends_on`` selects
        the tasks *waiting on* the one named, which is the question worth asking (the
        inverse is already a field on the row). ``archived`` narrows to one side of the
        archive; ``None`` — the default — answers with both, because the board hides
        archived cards itself and the cost figures have to see them (§10.4).
        """
        statement = select(TeamTask)
        if team_id is not None:
            statement = statement.where(TeamTask.team_id == team_id)
        if statuses:
            statement = statement.where(TeamTask.status.in_([str(s) for s in statuses]))  # type: ignore[attr-defined]
        if archived is not None:
            statement = statement.where(TeamTask.archived == archived)
        if assignee_id:
            statement = statement.where(TeamTask.assignee_id == assignee_id)
        if created_by:
            statement = statement.where(TeamTask.created_by == created_by)

        async with self.db.session() as session:
            rows = list((await session.exec(statement)).all())

        if search and search.strip():
            needle = search.strip().casefold()
            rows = [
                row
                for row in rows
                if needle in row.title.casefold() or needle in (row.brief or "").casefold()
            ]
        if depends_on:
            # `depends_on` is a JSON column, so the membership test is in Python rather
            # than in SQL — a board is hundreds of rows, not millions.
            upstream = await self.find_task(depends_on, team_id=team_id)
            wanted = upstream.id if upstream is not None else depends_on
            rows = [row for row in rows if wanted in (row.depends_on or [])]

        rows.sort(key=lambda row: row.created_at, reverse=True)
        return rows[:limit] if limit else rows

    async def stats(self, team_id: str) -> dict[str, Any]:
        """What this team's work has cost — every task, archived ones included (§10.4).

        The reads happen here and the arithmetic in :mod:`voxarium_studio.teams.stats`, so the
        rules about what counts can be checked without a database. Result entries carry
        cost; model and system entries identify the historical run model (§10.4).
        """
        async with self.db.session() as session:
            tasks = list(
                (await session.exec(select(TeamTask).where(TeamTask.team_id == team_id))).all()
            )
            members = list(
                (
                    await session.exec(select(TeamMember).where(TeamMember.team_id == team_id))
                ).all()
            )
            task_ids = [task.id for task in tasks]
            messages: list[AgentMessage] = []
            if task_ids:
                messages = list(
                    (
                        await session.exec(
                            select(AgentMessage)
                            .where(AgentMessage.kind.in_(["result", "model", "system", *compaction.EVIDENCE_KINDS]))
                            .where(AgentMessage.task_id.in_(task_ids))  # type: ignore[attr-defined]
                        )
                    ).all()
                )
        return {"team_id": team_id, **cost_stats_source.cost_stats(
            tasks, compaction.expand_evidence(messages), members
        )}

    # ------------------------------------------------------------------ routing rules

    async def _direct_reports(self, session, manager_id: str) -> list[TeamMember]:
        result = await session.exec(
            select(TeamMember).where(TeamMember.manager_id == manager_id)
        )
        return list(result.all())

    async def _assignable_peers(self, session, member: TeamMember) -> list[TeamMember]:
        """Sideways routes only — reports and the manager are described separately."""
        allowed = set(member.can_assign_to or [])
        if not allowed:
            return []
        result = await session.exec(
            select(TeamMember).where(TeamMember.team_id == member.team_id)
        )
        return [
            candidate
            for candidate in result.all()
            if candidate.id != member.id
            and candidate.manager_id != member.id
            and ("*" in allowed or candidate.id in allowed)
        ]

    async def check_can_assign(
        self, session, *, assignee_id: str, created_by: str | None
    ) -> None:
        """The user may assign to anyone; a member only within the routes they are given.

        Three routes, all of them deliberate (§10.4):

        * *down* — to a direct report, the manager delegating work;
        * *up* — to their own manager, escalating or handing a finished thing back;
        * *sideways* — to a peer listed in ``can_assign_to`` (``"*"`` for anyone), which is
          what lets a QA member send a defect straight to the developer or the designer
          instead of routing every correction through the lead.
        """
        if assignee_id in self.removing_members or created_by in self.removing_members:
            raise ValueError("This member is being removed. Wait for the team to refresh.")
        if created_by is None:
            return
        assignee = await session.get(TeamMember, assignee_id)
        if assignee is None:
            raise KeyError(f"Unknown member {assignee_id!r}")
        actor = await session.get(TeamMember, created_by)
        if actor is None:
            raise KeyError(f"Unknown member {created_by!r}")

        if assignee.manager_id == actor.id or actor.manager_id == assignee.id:
            return
        allowed = set(actor.can_assign_to or [])
        if "*" in allowed or assignee.id in allowed:
            return

        raise RoutingError(
            f"{actor.name} may only assign work to their own reports, their manager, or a "
            f"peer listed in can_assign_to — {assignee.name} is none of those."
        )

    async def check_can_review(self, session, *, task: TeamTask, actor_id: str | None) -> None:
        """The assignee's manager, team Producer or user can approve/reject a review."""
        if actor_id is None:
            return
        if await self._producer_can_manage(session, task, actor_id):
            return
        assignee = await session.get(TeamMember, task.assignee_id)
        if assignee is None or assignee.manager_id != actor_id:
            raise RoutingError(
                "Only the assignee's manager (or you) can approve or reject this task."
            )

    async def check_can_act(self, session, *, task: TeamTask, actor_id: str | None) -> None:
        """Claiming, noting and completing are the assignee's own moves (or the user's)."""
        if actor_id is None or actor_id == task.assignee_id:
            return
        actor = await session.get(TeamMember, actor_id)
        name = actor.name if actor else actor_id
        raise RoutingError(f"{name} is not the assignee of this task.")

    async def check_can_edit(self, session, *, task: TeamTask, actor_id: str | None) -> None:
        """The assignee, their manager, team Producer or user may maintain a task.

        Wider than :meth:`check_can_act`, which is about doing the *work*: dragging a card
        to another column or fixing a typo in a brief is board maintenance, and the person
        who will review the result has as much standing to do it as the one carrying it out.
        Still narrower than nothing — and, like every check here, it returns early for the
        user, who is not bound by the hierarchy at all.
        """
        if actor_id is None or actor_id == task.assignee_id:
            return
        if await self._producer_can_manage(session, task, actor_id):
            return
        assignee = await session.get(TeamMember, task.assignee_id)
        if assignee is not None and assignee.manager_id == actor_id:
            return
        actor = await session.get(TeamMember, actor_id)
        name = actor.name if actor else actor_id
        raise RoutingError(f"{name} is neither the assignee of this task nor their manager.")

    async def _producer_can_manage(self, session, task: TeamTask, actor_id: str) -> bool:
        # §10.11: the Producer coordinates the whole team board, regardless of origin.
        from .producer_identity import is_producer
        actor = await session.get(TeamMember, actor_id)
        return bool(actor and is_producer(actor)
                    and actor.team_id == task.team_id)

    # ------------------------------------------------------------------ task board

    async def create_task(
        self,
        *,
        team_id: str,
        assignee_id: str,
        title: str,
        brief: str,
        write_paths: Sequence[str] = (),
        created_by: str | None = None,
        depends_on: list[str] | None = None,
        auto_start: bool = False,
        agent_backend: str | None = None,
        model: str | None = None,
        effort: str | None = None,
        parent_task: TeamTask | None = None,
        attachments: list[str] | None = None,
        producer_chat_id: str | None = None,
        summary_locale: str | None = None,
    ) -> TeamTask:
        """Put a task on someone's board, initially in ``todo`` (§10.4).

        Every route in ends here: the composer, ``POST …/tasks`` and an agent's
        ``create_task`` tool. The team's opt-in autostart releases incoming agent tasks to
        ``pending``; otherwise they wait for a manual release or Start All.

        ``auto_start`` is therefore stored rather than acted on: it decides what happens at
        that release — run as soon as the dependencies clear, or wait for an explicit Start.
        A human assignee is never started either way; they claim the task themselves.

        ``model`` and ``effort`` override the assignee's for this one task (§10.3); null —
        the usual case — leaves both to the member. Blank is normalised to null here for the
        same reason ``update_task`` does it: an empty string would read as an override.

        ``parent_task`` marks this as one agent delegating to another, which is what the
        depth ceiling is counted against. The user creating a task always starts at 0.

        This is also the only place a ``TeamTask`` row is ever built, which is what makes
        the sequential number (§10.2) assignable exactly once: it is read and written under
        ``_number_lock``, and nothing downstream has a route to change it afterwards.
        """
        declared_writes = wt.normalize_write_paths(write_paths, root=self.paths.root)
        producer_chat_id = producer_chat_id or (parent_task.producer_chat_id if parent_task else None)
        solution = None
        solver = None
        solver_message = None
        if producer_chat_id:
            # §10.11: do the model call before opening the numbering transaction. One
            # slow evaluation must not hold the database or block other task creation.
            async with self.db.session() as session:
                chat = await session.get(AgentChat, producer_chat_id)
                if chat is not None and created_by == chat.member_id and chat.producer_effort_mode == "auto":
                    await self.check_can_assign(session, assignee_id=assignee_id, created_by=created_by)
                    assignee = await session.get(TeamMember, assignee_id)
                    if assignee is None:
                        raise KeyError(f"Unknown member {assignee_id!r}")
                    creator = await session.get(TeamMember, chat.member_id)
                    if assignee.team_id != team_id or creator is None or creator.team_id != team_id:
                        raise ValueError("That member or Producer chat is on a different team")
                    if parent_task and parent_task.delegation_depth + 1 > self.config.teams.max_delegation_depth:
                        raise DelegationLimitError("Delegation depth limit reached")
                    solver = await session.get(TeamMember, f"{team_id}__{EFFORT_SOLVER_ID}")
                    if solver is None:
                        raise RuntimeError("The built-in Effort Solver is missing. Reload the team and retry.")
            if solver is not None:
                solution = await solve_effort(
                    solver, title, brief, max_message_bytes=self.config.teams.max_message_bytes,
                )
        async with self._number_lock, self.db.session() as session:
            await self.check_can_assign(
                session, assignee_id=assignee_id, created_by=created_by
            )
            # check_can_assign returns early for the user, who may assign to anyone — so
            # the existence check has to live here or an unknown id reaches the INSERT and
            # surfaces as a foreign-key error instead of a 404.
            assignee = await session.get(TeamMember, assignee_id)
            if assignee is None:
                raise KeyError(f"Unknown member {assignee_id!r}")
            if await session.get(Team, team_id) is None:
                raise KeyError(f"Unknown team {team_id!r}")
            if assignee.team_id != team_id:
                raise ValueError("That member is on a different team")
            is_human = assignee.kind == MemberKind.HUMAN
            depth = 0 if parent_task is None else parent_task.delegation_depth + 1
            if depth > self.config.teams.max_delegation_depth:
                raise DelegationLimitError(
                    f"Delegation depth limit reached ({self.config.teams.max_delegation_depth}). "
                    "Describe what still needs doing in your summary instead of handing it on."
                )
            producer_chat = await session.get(AgentChat, producer_chat_id) if producer_chat_id else None
            if producer_chat is not None and created_by == producer_chat.member_id:
                # §10.11: enforce the saved policy at creation, including stale toolsets.
                # Delegation by an assignee and manual task overrides keep their normal rules.
                if producer_chat.producer_effort_mode == "auto":
                    if solution is None:
                        raise RuntimeError("Producer effort mode changed. Retry task creation for Effort Solver.")
                    effort = solution.effort_level
                    solver_message = AgentChatMessage(
                        chat_id=producer_chat.id, member_id=producer_chat.member_id,
                        role="assistant", kind="effort_solver", text="",
                        data={"scores": solution.scores.model_dump(), "effort": solution.effort,
                              "effort_level": solution.effort_level},
                    )
                    session.add(solver_message)
                    producer_chat.last_message_at = solver_message.created_at
                    # The separate scoring call is part of the chat's cost, without
                    # exposing its internal transcript in the result block (§10.11).
                    producer_chat.total_cost_usd += float(solution.usage.get("total_cost_usd") or 0)
                    for field_name in (
                        "num_turns", "total_tokens", "total_uncached_tokens", "total_cached_tokens",
                        "total_input_tokens", "total_output_tokens",
                    ):
                        setattr(producer_chat, field_name, getattr(producer_chat, field_name)
                                + int(solution.usage.get(field_name) or 0))
                    session.add(producer_chat)
                else:
                    effort = None
            locale = _summary_locale(summary_locale)
            if locale is None and parent_task is not None:
                locale = parent_task.summary_locale
            if locale is None and producer_chat is not None:
                locale = _summary_locale(producer_chat.producer_locale)
            task = TeamTask(
                producer_chat_id=producer_chat_id,
                summary_locale=locale,
                producer_approved=bool(parent_task and parent_task.producer_approved),
                number=await self._next_task_number(session),
                team_id=team_id,
                assignee_id=assignee_id,
                created_by=created_by,
                title=title,
                brief=brief,
                write_paths=declared_writes,
                attachments=self.attachments.resolve_uploads(attachments or []),
                depends_on=depends_on or [],
                status=TaskStatus.TODO,
                auto_start=auto_start and not is_human,
                agent_backend=agent_backend,
                model=str(model or "").strip() or None,
                effort=str(effort or "").strip() or None,
                delegation_depth=depth,
            )
            session.add(task)
            try:
                await session.flush()
            except IntegrityError as exc:
                # The unique index firing means the lock above was not the only writer —
                # another process on the same database, which the Studio does not support
                # but SQLite happily allows. Say so rather than surfacing the raw SQL.
                raise RuntimeError(
                    "Could not allocate a task number — another process is writing to this "
                    "project's database. Close the other Studio instance and try again."
                ) from exc

        if solver_message is not None:
            await self.bus.publish(
                EventType.TEAM_CHAT_MESSAGE,
                {"id": solver_message.id, "chat_id": solver_message.chat_id,
                 "member_id": solver_message.member_id, "role": solver_message.role,
                 "kind": solver_message.kind, "text": solver_message.text,
                 "data": solver_message.data, "created_at": solver_message.created_at.isoformat()},
                channel=f"team.{solver_message.member_id}.chat",
            )
            await self.bus.publish(
                EventType.TEAM_CHAT_UPDATED,
                {**producer_chat.model_dump(mode="json"),
                 "is_running": self.jobs.is_running(producer_chat.id)},
            )

        # Opens the task's history with the state it was born in, so the timeline in the UI
        # starts at `todo` rather than at whatever happened to it first (§10.4).
        await self._record_status_change(
            task_id=task.id,
            member_id=created_by or assignee_id,
            previous=None,
            status=TaskStatus.TODO,
            actor_id=created_by,
        )
        await self.bus.publish(
            EventType.TEAM_TASK_CREATED,
            {
                "task_id": task.id,
                "team_id": team_id,
                "assignee_id": assignee_id,
                "title": title,
                "created_by": created_by,
            },
        )
        await self._autostart_todo(task)
        return await self._reload(task.id)

    async def start_task(
        self,
        task_id: str,
        *,
        message: str | None = None,
        images: Sequence[dict[str, Any]] = (),
        summary_locale: str | None = None,
        from_board: bool = False,
    ) -> TeamTask:
        """Run (or resume) the agent session for a task — only from ``pending`` (§10.4).

        The status check is the gate the whole ``todo`` column exists for, and it lives here
        rather than in the API because this is the single door every start goes through: the
        Start button, an auto-start on a released dependency, and a reviewer sending work
        back all arrive at this method.

        ``images`` ride with an explicit ``message`` only — a reviewer's screenshots
        (``teams/attachments.py``); a run opened with no message of its own gets whatever
        pictures the reviews it reads back carried.
        """
        async with self.db.session() as session:
            task = await session.get(TeamTask, task_id)
            if task is None:
                raise KeyError(task_id)
            if task.producer_chat_id and not task.producer_approved:
                raise ValueError("Approve this task's plan in a Producer chat before starting tasks.")
            member = await session.get(TeamMember, task.assignee_id)
            if member is None:
                raise KeyError(f"Task {task_id} has no valid assignee")
            if member.kind == MemberKind.HUMAN:
                raise ValueError(
                    f"{member.name} is a human team member — this task waits in their inbox "
                    "until they claim it, there is no session to start."
                )
            if TaskStatus(task.status) != TaskStatus.PENDING:
                raise ValueError(
                    f"A task runs only from Pending, and this one is {task.status}. Move it "
                    "to Pending first — that is the step that releases work to the agents."
                )
            if self.jobs.is_running(task_id):
                raise ValueError("This task is already running")
            unmet = await self._unmet_dependencies(session, task)
            if unmet:
                raise ValueError(
                    "Task is blocked by unfinished dependencies: " + ", ".join(unmet)
                )
            if message:
                prompt, pictures = message, list(images)
            else:
                prompt, pictures = await self._opening_turn(session, task)
            if summary_locale is not None:
                task.summary_locale = _summary_locale(summary_locale)
                session.add(task)

        if self.jobs.is_running(task_id):
            raise ValueError("This task is already running")

        if member.id in self.removing_members:
            raise ValueError("This member is being removed. Wait for the team to refresh.")
        # Two concurrent releases can finish loading their prompts together. Check the
        # live Solo holder in the same event-loop turn as submit, before granting a board release.
        holder_id = self._solo_holder_id(member)
        if holder_id is not None:
            holder = await self._reload(holder_id)
            if task.auto_start and not self.producer_holds(task):
                follow_up = "This task starts on its own when that one ends."
            else:
                follow_up = f"Start it again once {member.name} is free."
                if not task.auto_start:
                    follow_up += " Turn on auto-start to queue it."
            raise ValueError(
                f"{member.name} runs one task at a time (Solo Mode) and is busy with "
                f"{self.display_id(holder)}. {follow_up}"
            )
        # Check after the database awaits, just before submitting (§10.11).
        if from_board:
            self._set_board_release(task, True)
        elif self.producer_holds(task):
            raise ValueError("This task's Producer chat is paused.")
        self.jobs.submit(
            task_id,
            "agent_task",
            lambda handle: self._run_task(handle, task.id, member.id, prompt, pictures),
            member_id=member.id,
        )
        return task

    def _resolve_agent(
        self,
        *,
        member: TeamMember,
        team: Team | None,
        manager: TeamMember | None,
        reports: list[TeamMember],
        peers: list[TeamMember],
        workdir: Path,
    ) -> str | None:
        """The name this run passes to ``--agent``, writing the definition if it is ours.

        Returns ``None`` when the member has to run without one, which is not a failure:
        the role then travels in the appended system prompt exactly as it did before agents
        existed. Two cases reach it — a name already taken by a hand-written agent, and a
        working directory outside the project, where nothing walking up from the session's
        cwd can reach the project's ``.claude/agents/`` at all.
        """
        if not self.paths.is_inside_project(workdir):
            return None
        return agent_defs.write_member(
            self.paths,
            member=member,
            team=team,
            manager=manager,
            reports=reports,
            peers=peers,
            prompts=self.config.teams.prompts,
        )

    async def sync_agent_definitions(self) -> None:
        """Rewrite ``.claude/agents/`` from the roster, and drop members who are gone.

        Called wherever the roster changes rather than only before a run, so the developer's
        terminal offers the same team the Studio does — ``claude --agent sofia`` without the
        Studio having to have run her first.
        """
        async with self.db.session() as session:
            members = list((await session.exec(select(TeamMember))).all())
            teams = {team.id: team for team in (await session.exec(select(Team))).all()}
            by_id = {member.id: member for member in members}
            for member in members:
                # §10.11: a Settings edit also updates the Producer's read-only roster data.
                if self.store.sync_producer_responsibility(member):
                    session.add(member)
                if member.kind == MemberKind.HUMAN or member.agent:
                    # Nothing of ours on disk for either: a person has no session to start,
                    # and a borrowed agent is somebody else's file.
                    agent_defs.forget_member(self.paths, member.id)
                    continue
                agent_defs.write_member(
                    self.paths,
                    member=member,
                    team=teams.get(member.team_id),
                    manager=by_id.get(member.manager_id) if member.manager_id else None,
                    reports=await self._direct_reports(session, member.id),
                    peers=await self._assignable_peers(session, member),
                    prompts=self.config.teams.prompts,
                )
        agent_defs.prune(self.paths, keep={member.id for member in members})

    async def _opening_turn(
        self, session, task: TeamTask
    ) -> tuple[str, list[dict[str, Any]]]:
        """What a run opens with when the caller named no message of its own, and the
        pictures that go with it.

        On a first run that is the brief — the only place the agent can learn what it was
        asked to do. On a **resume** it must not be: `resume` hands the backend the session
        the previous run left behind, so the brief and every turn taken against it are
        already in the model's context (§10.3). Sending it again pays for a second copy and
        reads as a fresh assignment, which is how a resume ends up redoing finished work.

        What that session has genuinely never seen is anything written on the board while it
        was not running, so notes left since it stopped ride along. That is the whole point
        of the note field — the UI and ``POST …/notes`` both describe it as the text an
        agent sees on its next resume, and until this it was the one thing a resume dropped.

        The words themselves are the ``resume`` / ``resume_notes`` templates from
        ``teams.prompts`` (``core/prompts.py``), like every other line the Studio speaks.
        """
        if not task.agent_session_id:
            return with_trailer(task.brief, self.attachments.trailer(task.attachments or [])), self.attachments.images(task.attachments or [])
        prompts = self.config.teams.prompts
        # A review the session has not seen leads, in the words `request_changes` would
        # have opened with had the member been free at the time (§10.4); notes follow. Its
        # attachments come too — the trailer naming the files and the pictures themselves.
        reviews = await self._reviews_since_last_run(session, task)
        review_turns: list[str] = []
        pictures: list[dict[str, Any]] = self.attachments.images(task.attachments or [])
        for comment, attachments in reviews:
            wording = with_trailer(comment, self.attachments.trailer(attachments))
            review_turns.append(prompts.text("review_changes", comment=wording))
            pictures.extend(self.attachments.images(attachments))
        pending = await self._notes_since_last_run(session, task)
        resume = with_trailer(prompts.text("resume"), self.attachments.trailer(task.attachments or []))
        if not pending:
            text = "\n\n".join([*review_turns, resume]) if review_turns else resume
            return text, pictures
        preamble = prompts.text("resume_notes")
        head = f"{resume}\n\n{preamble}" if preamble else resume
        return "\n\n".join([*review_turns, head, *pending]), pictures

    async def _notes_since_last_run(self, session, task: TeamTask) -> list[str]:
        """Notes written after this task's last run began, oldest first.

        ``started_at`` is rewritten on every run, so it is the cut: anything older either
        went out with an earlier prompt or predates the session entirely. Only ``note``
        entries — the rest of the transcript is the agent's own output, or a review comment
        that ``request_changes`` passes as an explicit message when it can start the run
        itself (:meth:`_reviews_since_last_run` covers the case where it cannot).
        """
        return await self._entries_since_last_run(session, task, kind="note")

    async def _reviews_since_last_run(
        self, session, task: TeamTask
    ) -> list[tuple[str, list[dict[str, Any]]]]:
        """Review comments the session has not been shown, oldest first, each with the
        attachment records its entry carries.

        Normally none: ``request_changes`` resumes the run itself with the comment as its
        opening message, and ``started_at`` moves past the entry. A member in solo mode who
        was busy elsewhere gets the task parked in ``pending`` instead, and the queue starts
        it later with no message of its own — this is what keeps the review from being
        the one thing that resume forgot.
        """
        entries = await self._rows_since_last_run(session, task, kind="review")
        return [
            (entry.text.strip(), _attachment_records(entry.data))
            for entry in entries
            if entry.text.strip()
        ]

    async def _entries_since_last_run(self, session, task: TeamTask, *, kind: str) -> list[str]:
        entries = await self._rows_since_last_run(session, task, kind=kind)
        return [entry.text.strip() for entry in entries if entry.text.strip()]

    async def _rows_since_last_run(
        self, session, task: TeamTask, *, kind: str
    ) -> list[AgentMessage]:
        statement = select(AgentMessage).where(
            AgentMessage.task_id == task.id, AgentMessage.kind == kind
        )
        if task.started_at is not None:
            statement = statement.where(AgentMessage.created_at > task.started_at)
        result = await session.exec(statement.order_by(AgentMessage.created_at))
        # A note the running session was handed while it worked (§10.8) is newer than
        # `started_at` and yet not news: sending it again would read as a second request.
        return [row for row in result.all() if row.data.get("delivery") != DELIVERED]

    def _solo_holder_id(self, member: TeamMember) -> str | None:
        """The task a solo-mode member is working on, or ``None`` when they are free — or
        not in solo mode at all (§10.4).

        "Working on" is a live run in the job engine, not the ``in_progress`` status on a
        row, and the two differ at both ends. A job just submitted has not yet reached
        ``_run_task``'s first status write, and two releases in one gesture land inside
        that gap — the handle's ``member_id`` is what catches the second. A run in its last
        lines (``_ending``) has already written its final status but is still registered,
        and must not hold the member it just freed. And a row a crashed server left in
        ``in_progress`` has no run behind it at all: reading it as busy would park every
        later task behind a session that will never end.
        """
        if not member.solo_mode or member.kind == MemberKind.HUMAN:
            return None
        for handle in self.jobs.jobs():
            if (
                handle.kind == "agent_task"
                and handle.meta.get("member_id") == member.id
                and handle.id not in self._ending
                and self.jobs.is_running(handle.id)
            ):
                return handle.id
        return None

    async def _solo_holder(self, session, member: TeamMember) -> TeamTask | None:
        holder_id = self._solo_holder_id(member)
        return await session.get(TeamTask, holder_id) if holder_id is not None else None

    async def dispatch_member_queue(
        self, member_id: str, *, except_task: str | None = None
    ) -> None:
        """Start the next of a member's released tasks now that they may be free (§10.4).

        The queue is everything of theirs in ``pending`` with ``auto_start``, lowest number
        first; ``_start_if_ready`` applies every other rule, the solo gate included, so for
        a member who is not in solo mode this starts whatever was already runnable and
        nothing else. Called when one of their runs ends, and when Solo Mode is switched
        off with work parked behind it.
        """
        if member_id in self.removing_members:
            return
        async with self.db.session() as session:
            rows = await session.exec(
                select(TeamTask)
                .where(TeamTask.assignee_id == member_id)
                .where(TeamTask.status == TaskStatus.PENDING)
                .where((TeamTask.auto_start == True) | (TeamTask.pause_resume_pending == True))  # noqa: E712
                .order_by(TeamTask.number)
            )
            queued = [row.id for row in rows.all() if row.id != except_task]
        if queued:
            await self._start_if_ready(queued)

    async def _unmet_dependencies(self, session, task: TeamTask) -> list[str]:
        unmet: list[str] = []
        for dependency_id in task.depends_on:
            dependency = await session.get(TeamTask, dependency_id)
            if dependency is not None and dependency.status != TaskStatus.DONE:
                unmet.append(dependency.title or dependency_id)
        return unmet

    async def submit_for_review(
        self, task_id: str, summary: str | None = None, result_type: str | None = None
    ) -> None:
        await self._set_task_status(
            task_id, TaskStatus.NEEDS_REVIEW, result_summary=summary, result_type=result_type
        )

    async def approve(self, task_id: str, *, actor_id: str | None = None) -> TeamTask:
        async with self.db.session() as session:
            task = await session.get(TeamTask, task_id)
            if task is None:
                raise KeyError(task_id)
            await self.check_can_review(session, task=task, actor_id=actor_id)
        await self._set_task_status(
            task_id, TaskStatus.DONE, finished=True, actor_id=actor_id, reason="approved"
        )
        return await self._reload(task_id)

    async def request_changes(
        self,
        task_id: str,
        comment: str,
        *,
        actor_id: str | None = None,
        attachments: Sequence[str] = (),
        summary_locale: str | None = None,
        from_board: bool = False,
    ) -> TeamTask:
        """Send a task back with a comment, and the files attached to it.

        An agent's session resumes with the comment as its next turn; a human's task simply
        returns to ``in_progress`` with the comment in their transcript.

        ``attachments`` names files already stored on the task (``teams/attachments.py``):
        the review entry records them, the comment the session gets names each by its
        absolute path, and the pictures among them travel as image blocks beside it.

        The agent's half goes back through ``pending`` rather than straight into a run: a
        session starts from that column and nowhere else, so the trail reads
        ``needs_review → pending → in_progress`` and the rule holds for the review loop too.
        Requesting changes *is* the release here — the reviewer has just looked at the work,
        which is exactly the deliberate act ``todo`` asks for on a new task.
        """
        async with self.db.session() as session:
            task = await session.get(TeamTask, task_id)
            if task is None:
                raise KeyError(task_id)
            await self.check_can_review(session, task=task, actor_id=actor_id)
            # A name that is not on disk fails the whole review before anything is written.
            records = self.attachments.resolve(task_id, list(attachments))
            task.review_comment = comment
            if summary_locale is not None:
                task.summary_locale = _summary_locale(summary_locale)
            session.add(task)
            assignee = await session.get(TeamMember, task.assignee_id)
            is_human = assignee is not None and assignee.kind == MemberKind.HUMAN

        await self.record_message(
            task_id,
            actor_id or task.assignee_id,
            role="human" if actor_id else "user",
            kind="review",
            text=comment,
            data={"attachments": records} if records else None,
        )
        wording = with_trailer(comment, self.attachments.trailer(records))
        if is_human:
            await self._set_task_status(
                task_id, TaskStatus.IN_PROGRESS, actor_id=actor_id, reason="changes requested"
            )
        else:
            await self._set_task_status(
                task_id, TaskStatus.PENDING, actor_id=actor_id, reason="changes requested"
            )
            if from_board:
                self._set_board_release(task, True)
            async with self.db.session() as session:
                held_by = await self._solo_holder(session, assignee) if assignee else None
            if held_by is None:
                await self.start_task(
                    task_id,
                    message=self.config.teams.prompts.text("review_changes", comment=wording),
                    images=self.attachments.images(records),
                )
            else:
                # A solo member busy elsewhere (§10.4): the task waits in `pending` like any
                # other of theirs and the queue resumes it, carrying the review — which is
                # what `_opening_turn` reads reviews written since the last run for. A
                # review always resumes, so a card released without auto-start is queued
                # anyway, and the flag is edited on the record rather than around it.
                async with self.db.session() as session:
                    parked = await session.get(TeamTask, task_id)
                    queue_it = parked is not None and not parked.auto_start
                    if queue_it:
                        parked.auto_start = True  # type: ignore[union-attr]
                        session.add(parked)
                if queue_it:
                    await self._record_edit(
                        task_id,
                        {"auto_start": (False, True)},
                        member_id=actor_id or task.assignee_id,
                        actor_id=actor_id,
                    )
                await self.record_message(
                    task_id,
                    task.assignee_id,
                    role="system",
                    kind="queued",
                    text=(
                        f"{assignee.name} runs one task at a time and is busy with "  # type: ignore[union-attr]
                        f"{self.display_id(held_by)} — this task resumes when that one ends."
                    ),
                )
        return await self._reload(task_id)

    # ------------------------------------------------------------------ board maintenance

    #: What ``update_task`` will take. Everything else on the row is either the orchestrator's
    #: bookkeeping (session id, cost, timestamps) or has a lifecycle move of its own.
    #:
    #: ``number`` is absent and must stay absent: the sequential id is what a person quotes
    #: in a brief or a commit message, so an editable one would let a card silently become a
    #: different card (§10.2). Everything that patches a task funnels through here, so this
    #: set *is* the immutability rule — there is nowhere else to enforce it.
    EDITABLE_FIELDS = frozenset(
        {
            "title",
            "brief",
            "write_paths",
            "attachments",
            "assignee_id",
            "depends_on",
            "auto_start",
            "resume_on_limit",
            "status",
            "agent_backend",
            "model",
            "effort",
            "archived",
        }
    )

    COALESCED_EDIT_FIELDS = frozenset({"brief"})
    """Fields that arrive as a *stream* of patches rather than as one gesture, and whose
    transcript entries are therefore folded together (:meth:`_record_edit`).

    The brief is the only one: it saves itself as it is typed (§10.4). Everything else is
    one deliberate act per patch — archiving a task and then moving it off Done are two
    things that happened, and the history has to keep saying so.
    """

    EDIT_COALESCE_WINDOW = timedelta(minutes=2)
    """How long one sitting at the edit form is taken to last (:meth:`_record_edit`).

    Wide enough to cover the pauses in writing a brief — which arrives here as a patch every
    time the typing stops — and short enough that coming back to the same task later is a
    second entry rather than a rewrite of the first.
    """

    async def update_task(
        self, task_id: str, fields: dict[str, Any], *, actor_id: str | None = None,
        summary_locale: str | None = None, from_board: bool = False,
    ) -> TeamTask:
        """Edit a task in place — title, brief, assignee, dependencies, auto-start, status.

        Takes a patch rather than a whole task, for the reason the member editor does
        (§10.2): only the keys the caller actually sent are applied, so two edits to
        different fields of the same card do not overwrite each other.

        ``status`` here is the manual move — the board's drag-and-drop and status picker.
        The lifecycle endpoints (``claim``/``complete``/``approve``) remain the ones that
        carry meaning and side effects; this is the escape hatch for everything they do not
        cover, which for a person's task is most of it.

        One of those moves does carry a side effect, and deliberately: dragging a card from
        ``todo`` into ``pending`` is how work is released, so an agent task that is unblocked
        and marked ``auto_start`` starts on the drop. With team autostart enabled, arriving
        in ``todo`` also releases the task, through the same Pending queue.
        """
        unknown = set(fields) - self.EDITABLE_FIELDS
        if unknown:
            raise ValueError(f"Not editable: {', '.join(sorted(unknown))}")

        fields = dict(fields)  # normalised and consumed below; the caller's patch is theirs
        running = self.jobs.is_running(task_id)
        rescoping = False
        changes: dict[str, tuple[Any, Any]] = {}
        new_status: TaskStatus | None = None

        async with (
            self._write_paths_guards.setdefault(task_id, asyncio.Lock()),
            self.db.session() as session,
        ):
            task = await session.get(TeamTask, task_id)
            if task is None:
                raise KeyError(task_id)
            await self.check_can_edit(session, task=task, actor_id=actor_id)
            if task.assignee_id in self.removing_members:
                raise ValueError("This task's member is being removed. Wait for the team to refresh.")

            if summary_locale is not None:
                task.summary_locale = _summary_locale(summary_locale)

            if "write_paths" in fields:
                fields["write_paths"] = wt.normalize_write_paths(
                    fields["write_paths"], root=self.paths.root,
                )
                rescoping = (
                    self.jobs.is_running(task_id) and fields["write_paths"] != task.write_paths
                )
                # §10.5: the one who may move a live write scope is the executor, from inside
                # its own session — it knows what the work turned out to need. Before the
                # session exists (queued for paths or a slot) there is nobody to ask.
                if rescoping and not (
                    actor_id is not None and actor_id == task.assignee_id
                    and task_id in self._run_writes
                ):
                    raise ValueError(
                        "This task is running — stop it before changing write_paths. "
                        "Only its executor may change them during the run."
                    )
                if task.worktree_path and not wt.can_isolate_write_paths(
                    fields["write_paths"], self.paths.root,
                ):
                    raise ValueError(
                        "This task has an isolated checkout — clean it up or reset the task "
                        "before declaring shared write files. Its existing work is preserved."
                    )

            if (task.producer_chat_id and not task.producer_approved and
                    fields.get("status") not in (None, TaskStatus.TODO, TaskStatus.CANCELLED)):
                raise ValueError("Approve this task's plan in a Producer chat before releasing tasks.")

            if "attachments" in fields:
                fields["attachments"] = self.attachments.resolve_uploads(fields["attachments"] or [])

            if "title" in fields:
                title = str(fields["title"] or "").strip()
                if not title:
                    raise ValueError("A task needs a title")
                fields["title"] = title

            if "assignee_id" in fields and fields["assignee_id"] != task.assignee_id:
                if running:
                    raise ValueError(
                        "This task is running — cancel it before handing it to someone else, "
                        "or the session and its worktree would outlive the assignment."
                    )
                await self.check_can_assign(
                    session, assignee_id=fields["assignee_id"], created_by=actor_id
                )
                new_assignee = await session.get(TeamMember, fields["assignee_id"])
                if new_assignee is None:
                    raise KeyError(f"Unknown member {fields['assignee_id']!r}")
                if new_assignee.team_id != task.team_id:
                    raise ValueError("That member is on a different team")
                # The session belongs to whoever ran it; the next assignee starts their own
                # rather than resuming a transcript they never wrote.
                task.agent_session_id = None
                if new_assignee.kind == MemberKind.HUMAN:
                    fields.setdefault("auto_start", False)

            # Blank and null are the same wish — "back to the assignee's setting" — and
            # storing the empty string instead of null would make `task.model or
            # member.model` read as an override to a model with no name.
            for override in ("agent_backend", "model", "effort"):
                if override in fields:
                    fields[override] = str(fields[override] or "").strip() or None
            if any(key in fields and fields[key] != getattr(task, key) for key in ("agent_backend", "model")):
                if running:
                    raise ValueError("Cancel the running task before changing its model or backend")
                task.agent_session_id = None

            if "depends_on" in fields:
                fields["depends_on"] = await self._validated_dependencies(
                    session, task, list(fields["depends_on"] or [])
                )

            if "status" in fields:
                new_status = TaskStatus(fields.pop("status"))
                if new_status == TaskStatus(task.status):
                    new_status = None
                elif running:
                    raise ValueError(
                        "This task is running — cancel it before moving it by hand, or the "
                        "job engine and the board would disagree about what is happening."
                    )

            # `archived` only ever describes a finished task (§10.4). The status this patch
            # lands on is what counts — archiving and approving in one call is fine — and a
            # move *off* `done` takes the flag with it, so a card the board has to show is
            # never one it has been told to hide.
            landing_status = new_status or TaskStatus(task.status)
            if fields.get("archived"):
                if landing_status != TaskStatus.DONE:
                    raise ValueError(
                        "Only a task in Done can be archived — finish or approve it first."
                    )
            elif task.archived and landing_status != TaskStatus.DONE:
                fields["archived"] = False

            # Last, after everything that can still reject the patch: the swap is immediate
            # and all-or-nothing, and refuses a path another running task holds (§10.5). An
            # isolated run holds no locks, so there the list simply changes.
            if rescoping and (held := self._run_writes[task_id]) is not None:
                self._path_locks.replace(
                    held, fields["write_paths"], root=self.paths.root,
                    owner=self.display_id(task),
                )

            for field, value in fields.items():
                current = getattr(task, field)
                if current != value:
                    changes[field] = (current, value)
                    setattr(task, field, value)

            # A person has no session to start, so the flag would be a promise nothing keeps.
            assignee = await session.get(TeamMember, task.assignee_id)
            if assignee is not None and assignee.kind == MemberKind.HUMAN and task.auto_start:
                task.auto_start = False
                changes["auto_start"] = (True, False)

            session.add(task)
            if fields.get("archived"):
                # spec §10.4: discard verbose rows but retain compact accounting so
                # archiving cannot move historical spend to the task's finishing day.
                accounting = list((await session.exec(select(AgentMessage).where(
                    AgentMessage.task_id == task_id,
                    AgentMessage.role == "system",
                    AgentMessage.kind.in_(["result", "model", "system", compaction.ACCOUNTING_KIND]),
                ))).all())
                summaries = list((await session.exec(select(AgentMessage).where(
                    AgentMessage.task_id == task_id,
                    AgentMessage.kind.in_(compaction.SUMMARY_KINDS),
                    AgentMessage.role != "system",
                ).order_by(AgentMessage.created_at))).all())
                # Canonicalize the repair marker together with old summary evidence
                # before deleting it; otherwise the summary resurrects replaced totals.
                evidence = compaction.retain_evidence([*accounting, *summaries])
                await session.exec(delete(AgentMessage).where(
                    AgentMessage.task_id == task_id,
                    AgentMessage.role == "system",
                    AgentMessage.kind.not_in(ALWAYS_KINDS),
                ))
                if summaries:
                    for summary in summaries:
                        summary.data = {**summary.data,
                                        "evidence": evidence if summary is summaries[-1] else []}
                        session.add(summary)
                elif evidence:
                    session.add(AgentMessage(
                        task_id=task_id, member_id=task.assignee_id, role="system",
                        kind=compaction.ACCOUNTING_KIND, data={"evidence": evidence},
                    ))
            started_before = task.started_at is not None
            unblocked = not await self._unmet_dependencies(session, task)
            assignee_id = task.assignee_id

        # A board release also applies if the assignee is busy or a dependency is open.
        # An unrelated edit to the brief must not wake a paused Producer's work (§10.11).
        if (from_board and landing_status == TaskStatus.PENDING
                and (new_status == TaskStatus.PENDING or "auto_start" in fields)):
            self._set_board_release(task, task.auto_start)

        if changes:
            await self._record_edit(
                task_id, changes, member_id=actor_id or assignee_id, actor_id=actor_id
            )

        if new_status is not None:
            await self._set_task_status(
                task_id,
                new_status,
                actor_id=actor_id,
                started=new_status == TaskStatus.IN_PROGRESS and not started_before,
                finished=new_status in TERMINAL_TASK_STATUSES,
            )
        elif changes:
            await self._publish_task_changed(task_id)

        # Ticking the box is retroactive on purpose (§10.3): a task already blocked by a
        # limit carries the reset it was stopped at, so the answer to "wait for it" is to
        # wait for *that* one rather than for the next time it happens. Unticking is the
        # only control over a wait in flight, which is why it has to reach this far.
        if "resume_on_limit" in changes:
            if changes["resume_on_limit"][1]:
                await self._arm_limit_wait(task_id)
            else:
                await self._clear_limit_wait(task_id)

        # Three edits can leave a task runnable that nothing would otherwise wake up —
        # `_dispatch_dependents` only ever fires on `done`: releasing it into `pending`,
        # loosening a dependency, and turning auto-start on.
        refreshed = await self._reload(task_id)
        if (
            unblocked
            and refreshed.auto_start
            and refreshed.status == TaskStatus.PENDING
            and (
                new_status == TaskStatus.PENDING
                or "depends_on" in changes
                or "auto_start" in changes
            )
        ):
            await self._start_if_ready([task_id])
            refreshed = await self._reload(task_id)
        if refreshed.status == TaskStatus.TODO:
            await self._autostart_todo(refreshed)
            refreshed = await self._reload(task_id)
        return refreshed

    async def _validated_dependencies(
        self, session, task: TeamTask, depends_on: list[str]
    ) -> list[str]:
        """Dependencies must exist, be on this board, and not close a loop.

        A cycle is not merely odd: `_unmet_dependencies` would keep every task in it
        blocked forever, and nothing on the board could ever release them.
        """
        seen: list[str] = []
        for dependency_id in depends_on:
            if dependency_id in seen:
                continue
            if dependency_id == task.id:
                raise ValueError("A task cannot wait for itself")
            dependency = await session.get(TeamTask, dependency_id)
            if dependency is None:
                raise KeyError(f"Unknown task {dependency_id!r}")
            if dependency.team_id != task.team_id:
                raise ValueError("A task can only wait for another task on the same board")
            seen.append(dependency_id)

        pending = list(seen)
        visited: set[str] = set()
        while pending:
            current = pending.pop()
            if current == task.id:
                raise ValueError("That would make the dependencies circular")
            if current in visited:
                continue
            visited.add(current)
            upstream = await session.get(TeamTask, current)
            if upstream is not None:
                pending.extend(upstream.depends_on or [])
        return seen

    async def compress_task(self, task_id: str, *, actor_id: str | None = None) -> TeamTask:
        """Manual Compress shares the task's job guard with Start/Reset/Delete (§10.4)."""
        async with self.db.session() as session:
            task = await session.get(TeamTask, task_id)
            if task is None:
                raise KeyError(task_id)
            await self.check_can_edit(session, task=task, actor_id=actor_id)
            member = await session.get(TeamMember, task.assignee_id)
            if member is None or member.kind == MemberKind.HUMAN:
                raise ValueError("Only an agent task has a context to compress")
        if self.jobs.is_running(task_id):
            raise ValueError("This task is running — wait for it to finish or stop it first")
        messages = await self._messages_of(task_id)
        if not compaction.has_dialogue(messages):
            raise ValueError("There is no new dialogue to compress")
        backend_name = task.agent_backend or member.agent_backend
        runtime_member = member.model_copy(update={
            "agent_backend": backend_name,
            "model": member.model if backend_name == member.agent_backend else "",
        })
        request = AgentRunRequest(
            member=runtime_member, task=task, workdir=self._recorded_workdir(messages),
            **member_run_settings(member, self.config.teams),
            prompt="", system_prompt="", model=task.model or runtime_member.model,
            effort=task.effort or member.effort,
            max_message_bytes=self.config.teams.max_message_bytes,
        )

        async def run(_handle: JobHandle) -> None:
            await self._publish_task_changed(task_id)
            async with self.jobs.slot("agents"):
                await self._compress_context(get_backend(request.member.agent_backend), request, force=True)

        if member.id in self.removing_members:
            raise ValueError("This member is being removed. Wait for the team to refresh.")
        handle = self.jobs.submit(task_id, "task_compaction", run, member_id=member.id)
        try:
            await asyncio.shield(handle.task)
        except asyncio.CancelledError:
            if handle.task.cancelled():
                raise ValueError("Context compression cancelled; the previous history is unchanged") from None
            raise
        finally:
            await self._publish_task_changed(task_id)
        return await self._reload(task_id)

    async def _compress_context(
        self, backend: Any, request: AgentRunRequest, *, force: bool = False
    ) -> bool:
        """Atomically replace a snapshot with one summary and rotate the session (§10.4).

        New notes arriving while the model summarizes are outside the snapshot and survive.
        Failed/empty summaries leave both the dialogue and the session usable.
        """
        task_id = request.session_key
        threshold = self.config.teams.context_compression_tokens
        if not force and not threshold:
            return False
        messages = await self._messages_of(task_id)
        if not compaction.has_dialogue(messages):
            return False
        if not force and compaction.context_tokens(messages) < threshold:
            return False
        summary, results = await compaction.summarize(
            backend, request, messages, self.config.teams.prompts.text("task_compact"),
            lambda data: self._store_result_stats(task_id, data),
        )
        evidence = compaction.retain_evidence(messages)
        for data in results:
            evidence.append({"kind": "result", "role": "system", "text": "",
                             "created_at": utcnow().isoformat(),
                             "data": {"total_cost_usd": data.get("total_cost_usd", 0),
                                      "purpose": "context_compaction",
                                      **{key: data[key] for key in (
                                          "provider_session_id", "session_total_cost_usd",
                                          "session_cost_baseline_usd", "cost_unavailable",
                                      ) if key in data},
                                      "total_tokens": data.get("total_tokens", 0),
                                      "total_uncached_tokens": usage_components(data)[0],
                                      "total_cached_tokens": usage_components(data)[1],
                                      "total_input_tokens": usage_directions(data)[0],
                                      "total_output_tokens": usage_directions(data)[1],
                                      "accounting_version": 2,
                                      "usage": data.get("usage", {}),
                                      "model": data.get("model") or request.model}})
        entry = AgentMessage(
            task_id=task_id, member_id=request.member.id, role="assistant",
            kind=compaction.SUMMARY_KIND, text=summary, created_at=messages[-1].created_at,
            data={"evidence": evidence, "replaced_messages": len(messages)},
        )
        async with self.db.session() as session:
            task = await session.get(TeamTask, task_id)
            if task is None:
                raise KeyError(task_id)
            # Delete just the captured ids, in bounded batches for SQLite parameter limits.
            for offset in range(0, len(messages), 400):
                await session.exec(delete(AgentMessage).where(
                    AgentMessage.task_id == task_id,
                    AgentMessage.id.in_([m.id for m in messages[offset:offset + 400]]),
                ))
            session.add(entry)
            task.agent_session_id = None
            task.remote_control_url = None
            session.add(task)
        await self._publish_task_changed(task_id)
        return True

    async def _auto_compress(self, backend: Any, request: AgentRunRequest) -> None:
        try:
            await self._compress_context(backend, request)
        except Exception as exc:
            log.exception("Context compression failed for task %s", request.session_key)
            await self.record_message(
                request.session_key, request.member.id, role="system", kind="continued",
                text=f"Context compression failed; the previous history is unchanged: {exc}",
            )

    async def reset_task(self, task_id: str, *, actor_id: str | None = None) -> TeamTask:
        """Erase everything a run left on a task, keeping the task itself (§10.4).

        The card, its brief, its assignee and its dependencies survive; the session id, the
        transcript, the summary, the review comment, the error, the cost and the timestamps
        do not — and the task goes back to ``todo``, where team autostart may release it.

        Dropping ``agent_session_id`` is the point of the whole operation: a resume hands the
        backend the session the last run left, so as long as that id is on the row there is
        no way to ask for a genuinely fresh start — Start becomes Resume and the model picks
        up context the user has just decided was wrong. Everything else cleared here is the
        same decision applied consistently: a transcript from a run that is being disowned,
        a summary describing work the next run has not done, and a cost that would otherwise
        keep accruing across attempts nobody is counting together.

        Refused while a session is running, for the reason ``delete_task`` is: the job engine
        owns the row until the run ends, and clearing the session id out from under it would
        leave the finishing run writing into a task that no longer claims to have started.
        """
        async with self.db.session() as session:
            task = await session.get(TeamTask, task_id)
            if task is None:
                raise KeyError(task_id)
            await self.check_can_edit(session, task=task, actor_id=actor_id)
            previous = TaskStatus(task.status)
            assignee_id = task.assignee_id
        if self.jobs.is_running(task_id):
            raise ValueError("This task is running — cancel it first")

        # The checkout belongs to the run being erased. Left behind, the next session would
        # inherit a branch full of work this task no longer says it did.
        await self.cleanup_worktree(task_id, force=True)

        async with self.db.session() as session:
            task = await session.get(TeamTask, task_id)
            if task is None:
                raise KeyError(task_id)
            task.agent_session_id = None
            task.remote_control_url = None
            task.result_summary = None
            task.result_type = None
            task.review_comment = None
            task.error = None
            task.total_cost_usd = 0.0
            task.num_turns = 0
            task.total_tokens = 0
            task.total_uncached_tokens = 0
            task.total_cached_tokens = 0
            task.total_input_tokens = 0
            task.total_output_tokens = 0
            task.started_at = None
            task.finished_at = None
            session.add(task)
            await self.subagents.delete_for_task(session, task_id)
            from ..models.task_context import TaskContext
            await session.exec(delete(TaskContext).where(TaskContext.task_id == task_id))
            for message in (
                await session.exec(select(AgentMessage).where(AgentMessage.task_id == task_id))
            ).all():
                await session.delete(message)

        await self._set_task_status(task_id, TaskStatus.TODO, actor_id=actor_id, reason="reset")
        if previous == TaskStatus.TODO:
            # `_set_task_status` writes nothing when the status does not actually move, and
            # the transcript has just been emptied — so a task reset while still in `todo`
            # would come back with no history at all. Open it the way a new task opens.
            await self._record_status_change(
                task_id=task_id,
                member_id=actor_id or assignee_id,
                previous=None,
                status=TaskStatus.TODO,
                actor_id=actor_id,
                reason="reset",
            )
        await self._autostart_todo(await self._reload(task_id))
        return await self._reload(task_id)

    async def delete_task(self, task_id: str, *, actor_id: str | None = None) -> None:
        """Remove a task, its transcript, and every reference other tasks hold to it.

        Refused while the assignee's session is running: the job engine owns the row until
        it finishes, and a half-deleted task is worse than a cancelled one. Dependents are
        released rather than left waiting on something that can never reach ``done``.
        """
        async with self.db.session() as session:
            task = await session.get(TeamTask, task_id)
            if task is None:
                raise KeyError(task_id)
            await self.check_can_edit(session, task=task, actor_id=actor_id)
            team_id = task.team_id
            assignee_id = task.assignee_id
        if self.jobs.is_running(task_id):
            raise ValueError("This task is running — cancel it first")

        # A wait is not in the job engine, so nothing above catches it: left running, its
        # timer would wake for a row that is gone (§10.3).
        self._cancel_limit_wait(task_id)
        await self.cleanup_worktree(task_id, force=True)

        async with self.db.session() as session:
            dependents = [
                candidate
                for candidate in (await session.exec(select(TeamTask))).all()
                if task_id in (candidate.depends_on or [])
            ]
            for candidate in dependents:
                candidate.depends_on = [
                    entry for entry in candidate.depends_on if entry != task_id
                ]
                session.add(candidate)
            for message in (
                await session.exec(select(AgentMessage).where(AgentMessage.task_id == task_id))
            ).all():
                await session.delete(message)
            doomed = await session.get(TeamTask, task_id)
            if doomed is not None:
                await self.subagents.delete_for_task(session, task_id)
                await session.delete(doomed)

        await self.bus.publish(
            EventType.TEAM_TASK_DELETED,
            {"task_id": task_id, "team_id": team_id, "assignee_id": assignee_id},
        )
        await self._start_if_ready([candidate.id for candidate in dependents])

    async def _publish_task_changed(self, task_id: str) -> None:
        async with self.db.session() as session:
            task = await session.get(TeamTask, task_id)
        if task is None:
            return
        await self.bus.publish(
            EventType.TEAM_TASK_UPDATED,
            {
                "task_id": task.id,
                "team_id": task.team_id,
                "assignee_id": task.assignee_id,
                "title": task.title,
                "status": str(task.status),
            },
        )

    # ------------------------------------------------------------------ human members

    async def claim_task(self, task_id: str, *, actor_id: str | None = None) -> TeamTask:
        """A human picks a task up off their inbox. No session, no worktree — just a state.

        Claimable straight from ``todo``, unlike an agent's task: the column exists to stop
        sessions starting on their own, and a person deciding to pick their own work up is
        neither automatic nor billed. Making them stage it through ``pending`` first would be
        a drag with nothing on the other side of it.
        """
        async with self.db.session() as session:
            task = await session.get(TeamTask, task_id)
            if task is None:
                raise KeyError(task_id)
            await self.check_can_act(session, task=task, actor_id=actor_id)
            if task.status not in (TaskStatus.TODO, TaskStatus.PENDING, TaskStatus.BLOCKED):
                raise ValueError(f"A {task.status} task cannot be claimed")
            unmet = await self._unmet_dependencies(session, task)
            assignee_id = task.assignee_id
        if unmet:
            raise ValueError("Task is blocked by unfinished dependencies: " + ", ".join(unmet))

        await self._set_task_status(
            task_id,
            TaskStatus.IN_PROGRESS,
            started=True,
            actor_id=actor_id,
            reason="claimed",
        )
        await self.record_message(
            task_id, assignee_id, role="human", kind="claimed", text="Picked this up."
        )
        return await self._reload(task_id)

    async def add_note(
        self, task_id: str, text: str, *, author_id: str | None = None
    ) -> AgentMessage:
        """Append a human's note to the transcript.

        On a task whose assignee is an agent this is also what the note is *for*: the next
        resume carries every note left since the last run as its prompt (`_opening_turn`),
        so a comment written here is how a person steers a session between runs.

        And **during** one (§10.8): a note written while the task's session is working is
        queued on the run's inbox and reaches it at the next opportunity, without stopping
        it — between two tool calls where the runtime can take it there, otherwise as the
        next turn before the run is called finished. Its entry carries ``data.delivery``,
        ``queued`` until then. A run that is stopped first leaves it queued, and it rides
        the next resume like any other note.
        """
        if not text.strip():
            raise ValueError("A note needs some text")
        async with self.db.session() as session:
            task = await session.get(TeamTask, task_id)
            if task is None:
                raise KeyError(task_id)
            # A note is read back to the agent on the next resume and is part of the task's
            # audit trail, so an entry may not be attributed to someone who cannot act on
            # the task — or to a member id that does not exist at all.
            writer = await session.get(TeamMember, author_id) if author_id is not None else None
            if author_id is not None and writer is None:
                raise KeyError(f"Unknown member {author_id!r}")
            await self.check_can_act(session, task=task, actor_id=author_id)
            author = task.assignee_id if author_id is None else author_id
        # Only what a person wrote is a message *to* the session. An agent notes on its own
        # task through `add_task_note` while it runs, and handing that back to it as
        # something the user said would have it answering itself.
        from_person = writer is None or writer.kind == MemberKind.HUMAN
        inbox = self._inboxes.get(task_id) if from_person else None
        live = inbox is not None and not inbox.closed
        message = await self.record_message(
            task_id,
            author,
            role="human" if author_id is not None else "user",
            kind="note",
            text=text,
            data={"delivery": QUEUED} if live else None,
        )
        # Checked again, with nothing awaited before the put: a run that closed its inbox
        # while the note was being stored will not read it, and the note is then simply
        # one written since the last run — which the next resume carries.
        if inbox is not None and not inbox.closed:
            inbox.put(QueuedMessage(id=message.id, text=text.strip()))
        return message

    async def _mark_notes_delivered(self, items: list[QueuedMessage]) -> None:
        """The session has these notes now: say so on their entries and to the UI (§10.8)."""
        ids = [item.id for item in items]
        async with self.db.session() as session:
            rows = list((await session.exec(
                select(AgentMessage).where(AgentMessage.id.in_(ids))  # type: ignore[attr-defined]
            )).all())
            for row in rows:
                # A new dict, not a mutation: the JSON column only notices assignment.
                row.data = {**row.data, "delivery": DELIVERED}
                session.add(row)
        for row in rows:
            await self.bus.publish(
                EventType.TEAM_MEMBER_MESSAGE,
                {
                    "task_id": row.task_id,
                    "member_id": row.member_id,
                    "role": row.role,
                    "kind": row.kind,
                    "text": row.text,
                    "data": row.data,
                    "id": row.id,
                    "created_at": row.created_at.isoformat(),
                },
                channel=f"team.{row.member_id}.message",
            )

    async def report_result(
        self, task_id: str, *, result_type: str, summary: str, actor_id: str | None = None,
    ) -> TeamTask:
        # Serialize completion with spawning: parallel MCP calls must not publish a
        # finished parent while a worker is being registered (§10.3).
        run = self.subagents.runs.get(task_id)
        async with run.lock if run else contextlib.nullcontext():
            if run and (run.closing or self.subagents.runs.get(task_id) is not run):
                raise ValueError("This task run is closing")
            if self.subagents.has_active(task_id) or self.subagents.has_pending(task_id):
                raise ValueError("Wait for or stop subagents and read their results before finish_task")
            return await self._report_result(
                task_id, result_type=result_type, summary=summary, actor_id=actor_id,
            )

    async def _report_result(
        self,
        task_id: str,
        *,
        result_type: str,
        summary: str,
        actor_id: str | None = None,
    ) -> TeamTask:
        """The assignee says how the work ended — ``done``, ``question`` or ``error`` — and
        sums it up (§10.4). The ``finish_task`` tool's half; the status move is not here.

        Written on the row *and* into the transcript: the row is what the header chip and
        the board read, the ``summary`` entry is where the words sit in the conversation
        with the type beside them, so the reviewer reads the colour before the text. A
        session may call it more than once — a run that corrects itself — and the last
        call stands, as the last message would. Routed through ``check_can_act`` like a
        note, because the entry is replayed to the session on resume.
        """
        try:
            kind = TaskResultType(result_type)
        except ValueError:
            raise ValueError(
                f"Unknown result type {result_type!r}. Use one of: "
                + ", ".join(str(value) for value in TaskResultType)
            ) from None
        if not summary.strip():
            raise ValueError("A result needs a summary — say what you did, ask, or what failed")

        async with self.db.session() as session:
            task = await session.get(TeamTask, task_id)
            if task is None:
                raise KeyError(task_id)
            await self.check_can_act(session, task=task, actor_id=actor_id)
            if task.status in TERMINAL_TASK_STATUSES:
                raise ValueError(f"This task is already {task.status}")
            author = await session.get(TeamMember, actor_id or task.assignee_id)
            task.result_type = str(kind)
            task.result_summary = summary[:4000]
            session.add(task)
            author_id = task.assignee_id if author is None else author.id
            human = author is not None and author.kind == MemberKind.HUMAN

        await self.record_message(
            task_id,
            author_id,
            role="human" if human else "assistant",
            kind="summary",
            text=summary,
            data={"result_type": str(kind)},
        )
        await self._publish_task_changed(task_id)
        return await self._reload(task_id)

    async def complete_task(
        self,
        task_id: str,
        summary: str = "",
        *,
        result_type: str,
        actor_id: str | None = None,
    ) -> TeamTask:
        """A human declares their work done: to their manager's review queue, or straight
        to ``done`` when they have no manager (§10.4 step 3 has nobody to hand it to).

        ``result_type`` is required for the same reason it is on the tool: the reviewer's
        queue is read by colour first, and a person's card lands in it exactly as an
        agent's does.
        """
        try:
            kind = TaskResultType(result_type)
        except ValueError:
            raise ValueError(
                f"Unknown result type {result_type!r}. Use one of: "
                + ", ".join(str(value) for value in TaskResultType)
            ) from None
        async with self.db.session() as session:
            task = await session.get(TeamTask, task_id)
            if task is None:
                raise KeyError(task_id)
            await self.check_can_act(session, task=task, actor_id=actor_id)
            if task.status in TERMINAL_TASK_STATUSES:
                raise ValueError(f"This task is already {task.status}")
            assignee = await session.get(TeamMember, task.assignee_id)
            has_manager = assignee is not None and assignee.manager_id is not None
            assignee_id = task.assignee_id

        if summary.strip():
            await self.record_message(
                task_id,
                assignee_id,
                role="human",
                kind="summary",
                text=summary,
                data={"result_type": str(kind)},
            )
        await self._set_task_status(
            task_id,
            TaskStatus.NEEDS_REVIEW if has_manager else TaskStatus.DONE,
            result_summary=summary or None,
            result_type=str(kind),
            finished=True,
            actor_id=actor_id,
            reason="submitted",
        )
        return await self._reload(task_id)

    # ------------------------------------------------------------------ dependencies

    async def _dispatch_dependents(self, task_id: str) -> None:
        """Release tasks that were only waiting on ``task_id`` (§10.4).

        This is what turns a decomposed brief into a pipeline: the designer's task is
        approved, and the developer's task — created with ``depends_on`` pointing at it —
        starts without anyone clicking anything.

        Only ones already in ``pending``. A dependent still sitting in ``todo`` is not
        waiting on the dependency, it is waiting on a person, and clearing the dependency
        does not answer that. It stays put until somebody releases it.
        """
        async with self.db.session() as session:
            pending = list(
                (
                    await session.exec(
                        select(TeamTask).where(TeamTask.status == TaskStatus.PENDING)
                    )
                ).all()
            )
            candidates = [
                candidate.id
                for candidate in pending
                if task_id in (candidate.depends_on or [])
            ]
        await self._start_if_ready(candidates)

    async def _start_if_ready(self, candidate_ids: list[str]) -> None:
        """Start each of these tasks if nothing is holding it back any more.

        Shared by the ways a task can become runnable without anyone pressing Start: being
        released into ``pending``, a dependency reaching ``done``, a dependency being deleted
        out from under it, an edit that drops the dependency or turns auto-start on, and a
        solo-mode member finishing the run that was holding their queue.

        The ``pending`` check below is the ceiling on all of them — nothing here can wake a
        task that is still in ``todo``. Candidates are taken lowest number first, so when
        several of a solo member's tasks are runnable at once the oldest goes and the rest
        wait — and they wait *here*, by remembering which members this pass has already
        started, because the first start has not yet written ``in_progress`` when the
        second candidate is examined.
        """
        ready: list[str] = []
        claimed: set[str] = set()
        async with self.db.session() as session:
            candidates = [await session.get(TeamTask, task_id) for task_id in candidate_ids]
            for candidate in sorted(
                (row for row in candidates if row is not None), key=lambda row: row.number
            ):
                if candidate.status != TaskStatus.PENDING:
                    continue
                if self.producer_holds(candidate) or candidate.archived:
                    continue
                if self.jobs.is_running(candidate.id):
                    continue
                if not candidate.auto_start and not candidate.pause_resume_pending:
                    continue
                if await self._unmet_dependencies(session, candidate):
                    continue
                assignee = await session.get(TeamMember, candidate.assignee_id)
                if assignee is None or assignee.kind == MemberKind.HUMAN:
                    continue
                if assignee.solo_mode and (
                    assignee.id in claimed
                    or await self._solo_holder(session, assignee) is not None
                ):
                    continue
                claimed.add(assignee.id)
                ready.append(candidate.id)

        for candidate_id in ready:
            try:
                await self.start_task(candidate_id)
            except Exception:
                log.exception("Could not auto-start dependent task %s", candidate_id)

    async def _reload(self, task_id: str) -> TeamTask:
        async with self.db.session() as session:
            task = await session.get(TeamTask, task_id)
        if task is None:
            raise KeyError(task_id)
        return task

    # ------------------------------------------------------------------ remote control

    async def enable_remote_control(self, task_id: str) -> RemoteControl:
        """Hand this task's running session to Remote Control and answer with its link (§10.8).

        What ``/remote-control`` does in a terminal, for a session the Studio is driving:
        the session becomes visible and steerable on claude.ai/code and in the Claude mobile
        app, so a long run can be redirected from a phone instead of only cancelled here.

        It attaches to a session that **is running** — the bridge belongs to the CLI
        subprocess, so there is nothing to hand over on a task that is waiting, blocked or
        finished. That is a refusal with an explanation rather than a silent no-op, because
        the difference between "not running" and "your account cannot do this" is the whole
        of what the user needs to know.
        """
        async with self.db.session() as session:
            task = await session.get(TeamTask, task_id)
            if task is None:
                raise KeyError(task_id)
            member_id, title = task.assignee_id, task.title

        control = live_session(task_id)
        if control is None:
            raise ValueError(
                "Remote Control attaches to a session that is running, and this task has "
                "none. Start or resume it first, then connect."
            )

        # Named after the task, so a phone showing several sessions says which is which.
        link = await control.enable_remote_control(name=title[:80] or None)

        async with self.db.session() as session:
            stored = await session.get(TeamTask, task_id)
            if stored is not None:
                stored.remote_control_url = link.session_url
                session.add(stored)

        await self.record_message(
            task_id,
            member_id,
            role="system",
            kind="remote_control",
            text=f"Remote Control on — this session can be driven from {link.session_url}",
            data={
                "session_url": link.session_url,
                "connect_url": link.connect_url,
                "environment_id": link.environment_id,
                "enabled": True,
            },
        )
        await self._publish_task_changed(task_id)
        return link

    async def disable_remote_control(self, task_id: str) -> None:
        """Take the session back off claude.ai. Safe on a run that has already ended."""
        async with self.db.session() as session:
            task = await session.get(TeamTask, task_id)
            if task is None:
                raise KeyError(task_id)
            member_id = task.assignee_id

        control = live_session(task_id)
        if control is not None:
            await control.disable_remote_control()
        if await self._clear_remote_control(task_id):
            await self.record_message(
                task_id,
                member_id,
                role="system",
                kind="remote_control",
                text="Remote Control off.",
                data={"enabled": False},
            )
            await self._publish_task_changed(task_id)

    async def _clear_remote_control(self, task_id: str) -> bool:
        """Drop the stored link. True when there was one — the caller decides what to say."""
        async with self.db.session() as session:
            task = await session.get(TeamTask, task_id)
            if task is None or not task.remote_control_url:
                return False
            task.remote_control_url = None
            session.add(task)
        return True

    async def cancel_task(self, task_id: str) -> bool:
        # Cancel withdraws even a manual release that is still waiting in the Solo queue.
        for released in self.paused_producer_chats.values():
            released.discard(task_id)
        cancelled = await self.jobs.cancel(task_id)
        if not cancelled:
            async with self.db.session() as session:
                task = await session.get(TeamTask, task_id)
                if task and task.status == TaskStatus.IN_PROGRESS:
                    task.status = TaskStatus.PENDING
                    session.add(task)
        return cancelled

    # ------------------------------------------------------------------ execution

    def _launch_root(self) -> tuple[Path, str]:
        """Where sessions start, plus a line for the transcript when that is worth saying.

        The note is not logging: `config.teams.launch_root` travels with the project, so a
        path that is missing on this machine, or one that deliberately points outside the
        project and therefore gives up checkout isolation, is something the person reading
        the task later needs to see next to the run it changed.
        """
        configured = self.config.teams.launch_root
        resolved = resolve_launch_root(self.paths.root, configured)
        if resolved == self.paths.root:
            return resolved, ""
        if not resolved.is_dir():
            return self.paths.root, (
                f"The configured agent launch root {configured!r} is not a directory on "
                f"this machine ({resolved}). Starting in the project root instead."
            )
        if not self.paths.is_inside_project(resolved):
            return resolved, (
                f"Starting in {resolved}, outside the project (agent launch root "
                f"{configured!r}). Isolated checkouts do not apply there, so overlapping "
                "tasks are serialised by path instead (§10.5)."
            )
        return resolved, f"Starting in {resolved} (agent launch root {configured!r})."

    async def _run_task(
        self,
        handle: JobHandle,
        task_id: str,
        member_id: str,
        prompt: str,
        images: Sequence[dict[str, Any]] = (),
    ) -> None:
        async with (
            self._write_paths_guards.setdefault(task_id, asyncio.Lock()),
            self.db.session() as session,
        ):
            task = await session.get(TeamTask, task_id)
            member = await session.get(TeamMember, member_id)
            team = await session.get(Team, task.team_id) if task else None
            if task is None or member is None:
                return
            if self.producer_holds(task):
                # A submitted job may enter only after Pause closes the gate. Keep
                # its explicit start intent even if it leaves the registry before
                # the pause scan reaches this still-Pending task (§10.11).
                task.pause_resume_pending = True
                session.add(task)
                return
            task.pause_resume_pending = False
            session.add(task)
            reports = await self._direct_reports(session, member.id)
            manager = (
                await session.get(TeamMember, member.manager_id) if member.manager_id else None
            )
            peers = await self._assignable_peers(session, member)
            resume_session_id = task.agent_session_id
            write_paths = list(task.write_paths)

        await self._set_task_status(task_id, TaskStatus.IN_PROGRESS, started=True)

        launch_root, launch_note = self._launch_root()
        if launch_note:
            await self.record_message(
                task_id,
                member_id,
                role="system",
                kind="workdir",
                text=launch_note,
                data={"path": str(launch_root)},
            )

        # Where inside the project the session starts, so an isolated checkout can start at
        # the same place inside *itself* rather than at its root.
        offset = (
            launch_root.relative_to(self.paths.root)
            if self.paths.is_inside_project(launch_root)
            else None
        )
        workdir = launch_root
        worktree_created: Path | None = None
        locks: list[wt.PathClaim] = []
        # Open for the whole run, preparation included: a note written while the session is
        # still waiting for a path or a slot is one it should start out knowing (§10.8).
        inbox = self._inboxes[task_id] = Inbox(
            preamble=self.config.teams.prompts.text("mid_run_message"),
            on_delivered=self._mark_notes_delivered,
        )

        try:
            can_isolate_writes = wt.can_isolate_write_paths(write_paths, self.paths.root)
            if task.worktree_path and not can_isolate_writes:
                raise ValueError(
                    "The declared writes require the shared working copy, but this task "
                    "still has an isolated checkout. Clean it up or reset the task first."
                )
            if member.isolate_worktree and offset is not None and not can_isolate_writes:
                await self.record_message(
                    task_id, member_id, role="system", kind="workdir",
                    text=("The declared write files include absolute paths, parent traversal or links. "
                          "Using the shared working copy with write-file locks (§10.5)."),
                    data={"path": str(workdir)},
                )
            if member.isolate_worktree and offset is not None and can_isolate_writes:
                created = await wt.create_isolated_workspace(
                    self.vcs, self.paths.worktrees, task_id, member_id
                )
                if created is not None:
                    workdir, branch = created.path / offset, created.branch
                    worktree_created = created.path
                    await self.record_message(
                        task_id,
                        member_id,
                        role="system",
                        kind="worktree",
                        text=f"Working in an isolated worktree on branch {branch}",
                        data={"path": str(workdir), "branch": branch},
                    )
                    async with self.db.session() as session:
                        stored = await session.get(TeamTask, task_id)
                        if stored is not None:
                            # The checkout, not the launch directory inside it: this is
                            # what cleanup removes and what the diff is taken against.
                            stored.worktree_path = str(created.path)
                            session.add(stored)

            if worktree_created is None:
                # §10.5: only explicit writes contend; reading references in a brief is free.
                loop = asyncio.get_running_loop()
                queued_at: float | None = None

                async def waiting_for_path(path: str, owner: str | None) -> None:
                    nonlocal queued_at
                    if queued_at is None:
                        queued_at = loop.time()
                    holder = f"task {owner}" if owner else "another task"
                    await self.record_message(
                        task_id, member_id, role="system", kind="queued",
                        text=(f"Waiting for {holder} to release {path}. "
                              "The session starts when the path is released."),
                        data={"resource": "path", "path": path, "blocking_task": owner},
                    )

                locks = await self._path_locks.acquire(
                    write_paths, root=self.paths.root,
                    owner=self.display_id(task), on_wait=waiting_for_path,
                )
                if queued_at is not None:
                    waited = loop.time() - queued_at
                    await self.record_message(
                        task_id, member_id, role="system", kind="queued",
                        text=f"Paths are free after {span(waited)} — starting the session.",
                        data={"resource": "path", "waited_s": round(waited, 1)},
                    )
            # From here the session exists, and its executor may re-declare (§10.5).
            self._run_writes[task_id] = locks if worktree_created is None else None

            run_backend = task.agent_backend or member.agent_backend
            runtime_member = member.model_copy(update={
                "agent_backend": run_backend,
                "model": member.model if run_backend == member.agent_backend else "",
            })
            backend = get_backend(run_backend)
            # `--agent` is Claude Code's. A backend with no notion of named personas gets
            # `None`. Since full replacement (2026-11-12) the system prompt carries the
            # member's identity on *every* backend — Claude's `--system-prompt` discards
            # the definition's prompt — so the name now only selects the definition's
            # non-prompt effects and keeps the file fresh (§10.3, §10.6).
            agent_name = (
                self._resolve_agent(
                    member=member,
                    team=team,
                    manager=manager,
                    reports=reports,
                    peers=peers,
                    workdir=workdir,
                )
                if getattr(backend, "named_agents", True)
                else None
            )
            if agent_name and agent_name != member.agent:
                await self.record_message(
                    task_id,
                    member_id,
                    role="system",
                    kind="agent",
                    text=f"Running as the agent {agent_name}.",
                    data={"agent": agent_name, "source": "roster"},
                )
            elif agent_name:
                await self.record_message(
                    task_id,
                    member_id,
                    role="system",
                    kind="agent",
                    text=f"Running as {agent_name}, an agent this project already defines.",
                    data={"agent": agent_name, "source": "project"},
                )

            # The task's overrides, resolved here rather than in the backend: which of the
            # two settings wins is a rule of the board, and it has to read the same however
            # the session is run (§10.3). Noted in the transcript exactly as the agent line
            # above is — a service entry, so Verbose — because what a run *was* is worth
            # keeping beside it: the task's field is editable and says only what the next
            # run will use, and the header chip alongside the cost is the visible surface.
            run_model = task.model or (member.model if run_backend == member.agent_backend else "")
            run_effort = task.effort or member.effort
            if task.agent_backend or task.model or task.effort:
                spent = f"backend {run_backend}, model {run_model or 'default'}"
                if run_effort:
                    spent += f", effort {run_effort}"
                await self.record_message(
                    task_id,
                    member_id,
                    role="system",
                    kind="model",
                    text=f"Running this task on {spent} — set on the task, not on {member.name}.",
                    data={"backend": run_backend, "model": run_model, "effort": run_effort, "source": "task"},
                )

            # Resolved per run rather than once per member: a skill is a folder somebody may
            # have edited, renamed or deleted since the last one (§10.10). Said in the
            # transcript beside the agent and model lines for the same reason they are — which
            # skills a run was told about is part of what it was, and the roster says only what
            # the next run will get.
            required = load_required_context(self.config.teams, member, self.skills, self.docs)
            member_skills = self.skills.for_session([
                *(member.skills or []), *(skill.name for skill in required.skills.found),
            ])
            if member_skills.found or member_skills.missing:
                await self.record_message(
                    task_id,
                    member_id,
                    role="system",
                    kind="skills",
                    text=_skills_note(member_skills),
                    data=_skills_data(member_skills, required.startup_skills),
                )

            run_id = new_id("run")
            toolset = build_toolset(self, member=member, task=task, run_id=run_id)
            async with self._temp_locks.setdefault(task_id, asyncio.Lock()):
                temp_dir = task_temp.ensure_task_temp(self.paths, task)
            system_prompt = _build_system_prompt(
                member=member,
                team=team,
                manager=manager,
                reports=reports,
                peers=peers,
                task_id=self.display_id(task),
                project_root=self.paths.root,
                workdir=workdir,
                isolated=worktree_created is not None,
                docs_dir=self.docs.directory,
                temp_dir=temp_dir,
                has_studio_tools=toolset is not None,
                # §10.12: read per run, resumed sessions included, so every run opens with
                # what the team has recorded up to now.
                memory=memory_prompt(self.memory, self.config.teams, has_tools=toolset is not None),
                prompts=self.config.teams.prompts,
                code_graph=code_graph_file(launch_root),
                language=summary_language(task.summary_locale),
                skills=member_skills.found,
            )
            # §10.5: every run, including resumed sessions, sees the current write contract.
            system_prompt += (
                "\n\nDeclared write files (write_paths):\n"
                + ("\n".join(f"- {path}" for path in write_paths) if write_paths else "(none)")
                + f"\nFor this run, resolve relative write paths from {worktree_created or self.paths.root}, "
                "regardless of the session working directory. In an isolated checkout this "
                "maps project-relative declarations to their checkout counterparts."
                + "\nAn entry is an exact file, or a directory written as dir/* — everything "
                "inside it. Only these receive coordination locks when this task has no isolated "
                "checkout. Read-only references never take locks. Declare files you will create, "
                "edit, delete or rename, including both rename paths. Do not edit undeclared "
                "project files: "
                + (
                    "if the write scope must change, first replace the list yourself — "
                    f"update_task on {self.display_id(task)} with the full new write_paths. It "
                    "takes effect at once unless another running task holds one of the new "
                    "paths; then nothing changes, so do not write there — work on something "
                    "else or report the paths you need. "
                    if toolset is not None else
                    "if the write scope must change, report the required paths for an update "
                    "before the next run. "
                )
                + "Task-private temporary files are separate."
            )
            # The project's own MCP servers (§10.7) go in first, so a project server that
            # happens to be called `voxarium_studio` cannot displace the Studio's tools — those
            # are how the member reaches its own task board.
            project_mcp = project_mcp_servers(self.paths, self.config)
            recovered_prompt = (
                required.prepend(recovery_prompt(prompt, await self._messages_of(task_id), task))
                if resume_session_id and getattr(backend, "session_recovery", False)
                else ""
            )
            fresh_prompt = (required.prepend(recovery_prompt(prompt, await self._messages_of(task_id), task))
                            if not resume_session_id and task.num_turns else required.prepend(prompt))
            request = AgentRunRequest(
                member=runtime_member,
                **member_run_settings(member, self.config.teams),
                task=task,
                proactive_subagents=True,
                workdir=workdir,
                writable_roots=[worktree_created, temp_dir] if worktree_created else [self.paths.root],
                prompt=fresh_prompt if not resume_session_id else prompt,
                images=list(images),
                system_prompt=system_prompt,
                agent_name=agent_name,
                resume_session_id=resume_session_id,
                recovery_prompt=recovered_prompt,
                model=run_model,
                effort=run_effort,
                mcp_servers={**project_mcp, **(toolset.mcp_servers if toolset else {})},
                # Keep the Claude plugin available for reference files and native skill calls;
                # required_context already supplied SKILL.md at session start (§10.3).
                plugin_dirs=[studio_skill.BUNDLE],
                skill_files={skill.name: skill.skill_file for skill in member_skills.found},
                extra_allowed_tools=[
                    *mcp_allowed_tools(project_mcp),
                    *(toolset.allowed_tools if toolset else []),
                    *studio_skill.allowed_tools(),
                    # Read on each given skill's folder: an isolated checkout starts outside
                    # `.voxarium_studio/skills/`, where the files would otherwise be refused (§10.10).
                    *member_skills.allowed_tools(),
                    # And on the review attachments, which live under `.voxarium_studio/` where an
                    # isolated worktree cannot otherwise reach (`teams/attachments.py`).
                    *self.attachments.allowed_tools(),
                    *task_temp.allowed_tools(temp_dir),
                ],
                strict_mcp_config=self.config.mcp.strict,
                max_continuations=self.config.teams.max_run_continuations,
                continue_prompt=self.config.teams.prompts.text("stall_continue"),
                max_message_bytes=self.config.teams.max_message_bytes,
                delegated_work_idle_timeout=self.config.teams.delegated_work_idle_timeout,
                inbox=inbox,
            )
            # §10.4: the Status column lists the servers the Studio handed this run. Recorded
            # here rather than read from the runtime, because only Claude Code and Qwen Code
            # report their servers at start-up; Codex says nothing, and the list must not
            # depend on which backend ran the task.
            if request.mcp_servers:
                await self.record_message(
                    task_id,
                    member_id,
                    role="system",
                    kind="mcp",
                    text=_mcp_note(request.mcp_servers),
                    data={"servers": sorted(request.mcp_servers)},
                )

            # Says so in the transcript when the run has to queue behind `concurrency.agents`:
            # the task already reads In progress, and a silent wait looks like a hang.
            async with agent_slot(self.jobs, self.record_message, task_id, member_id) as lease:
                # §10.3, §10.11: an effort nobody chose ("Auto" on the card) is the Effort
                # Solver's to pick, once, when the session is about to start — inside this
                # slot, as the Producer's and a subagent's evaluations are. Failure raises
                # into the run's error path: no session starts on a substituted default.
                if request.effort is None and getattr(backend, "supports_effort", True):
                    request.effort = await self._solve_task_effort(task, member_id)
                self.subagents.open_run(request, lease, run_id)
                try:
                    limit = await self._consume(backend, request, task_id, member_id)
                    if limit is None:
                        await self._auto_compress(backend, request)
                finally:
                    # Workers must be gone before the task releases its write locks,
                    # temporary files or checkout (§10.3, §10.5).
                    await self.subagents.close_run(task_id)
            # Outside the slot, and deliberately: a task waiting hours for a window to
            # refill must not be holding a concurrency slot the other members need (§10.3).
            if limit is not None:
                await self._record_limit(task_id, member_id, limit)

        except asyncio.CancelledError:
            # Pause can arrive after finish_task saved a result but before the session
            # finished its cleanup/compaction. Never release that completed work again.
            saved = await self._reload(task_id)
            paused = handle.meta.get("producer_pause", False)
            if (saved.status != TaskStatus.DONE
                    and (not paused or saved.status in {TaskStatus.PENDING, TaskStatus.IN_PROGRESS})):
                await self._set_task_status(task_id, TaskStatus.PENDING)
            await self.record_message(
                task_id, member_id, role="system", kind="cancelled",
                text="Run paused; session and work preserved." if paused else "Run cancelled."
            )
            raise
        except Exception as exc:
            log.exception("Agent task %s failed", task_id)
            await self._set_task_status(task_id, TaskStatus.BLOCKED, error=str(exc))
            await self.record_message(
                task_id, member_id, role="system", kind="error", text=str(exc)
            )
        finally:
            # However the run ended. A note it never took stays `queued` on its entry and is
            # carried by the next resume, as a note written between runs is (§10.8).
            inbox.close()
            if self._inboxes.get(task_id) is inbox:
                self._inboxes.pop(task_id, None)
            self._run_writes.pop(task_id, None)
            self._path_locks.release(locks)
            # An approval can arrive while the completed run is still compacting. Wait
            # until its backend has stopped before deleting files it may still be using.
            saved = await self._reload(task_id)
            if saved.status == TaskStatus.DONE:
                await self._cleanup_task_temp(saved)
            # The Remote Control bridge belongs to the CLI subprocess, which is gone by now
            # — keeping its URL on the task would offer a link that controls nothing (§10.8).
            if await self._clear_remote_control(task_id):
                await self._publish_task_changed(task_id)
            # However the run ended, this member is free: whatever waited behind it in solo
            # mode may go (§10.4). This job is still in the registry until the coroutine
            # returns, so it is marked as ending rather than counted as holding — and it is
            # left out of the queue itself, or a cancelled run would restart on the spot.
            self._ending.add(task_id)
            try:
                async with self.db.session() as session:
                    fresh = await session.get(TeamMember, member_id)
                    solo = fresh is not None and fresh.solo_mode
                if solo:
                    await self.dispatch_member_queue(member_id, except_task=task_id)
            except Exception:
                log.exception("Could not dispatch %s's queue after task %s", member_id, task_id)
            finally:
                self._ending.discard(task_id)

    async def _solve_task_effort(self, task: TeamTask, member_id: str) -> str:
        """Score a task left on "Auto" and keep the level as its override (§10.3, §10.11).

        Stored on the row like the Producer's Auto result, so a resume, a continuation or
        a rerun after review keeps the effort the first run had; putting the slider back on
        "From member" asks the Solver again at the next start. The scores go into the task's
        transcript, and the evaluation's spend into the task's totals.
        """
        async with self.db.session() as session:
            solver = await session.get(TeamMember, f"{task.team_id}__{EFFORT_SOLVER_ID}")
        if solver is None:
            raise RuntimeError("The built-in Effort Solver is missing. Reload the team and retry.")
        try:
            solution = await solve_effort(
                solver, task.title, task.brief, max_message_bytes=self.config.teams.max_message_bytes,
            )
        except asyncio.CancelledError:
            raise
        except Exception as exc:
            raise RuntimeError(f"Effort Solver could not choose this task's effort: {exc}") from exc
        # Tokens and cost only: the task's `num_turns` means "a session has worked here" and
        # decides whether a fresh start is handed the saved history (§10.6).
        spent = {key: int(solution.usage.get(key) or 0) for key in (
            "total_tokens", "total_uncached_tokens", "total_cached_tokens",
            "total_input_tokens", "total_output_tokens",
        )}
        cost = float(solution.usage.get("total_cost_usd") or 0)
        async with self.db.session() as session:
            await session.exec(update(TeamTask).where(TeamTask.id == task.id).values(
                effort=solution.effort_level,
                total_cost_usd=TeamTask.total_cost_usd + cost,
                **{key: getattr(TeamTask, key) + value for key, value in spent.items()},
            ))
        task.effort = solution.effort_level
        await self.record_message(
            task.id, member_id, role="system", kind="effort_solver",
            text=f"Effort Solver set this task's effort to {solution.effort_level}.",
            data={"scores": solution.scores.model_dump(), "effort": solution.effort,
                  "effort_level": solution.effort_level},
        )
        await self._publish_task_changed(task.id)
        return solution.effort_level

    async def _consume(
        self, backend: Any, request: AgentRunRequest, task_id: str, member_id: str
    ) -> usage_limits.UsageLimit | None:
        """Drive one run to its end, and say whether the account's usage is what ended it.

        The limit is returned rather than acted on here, because acting on it is a decision
        about the *task* — whether it waits — and this method's job is the run. The caller
        holds the ``agent`` concurrency slot while this is in flight, which a wait must not.

        The result type is required (§10.4): a run that ends without having called
        ``finish_task`` is resumed once with the ``result_required`` prompt, when
        ``teams.result_follow_up`` says so. The type is checked on the row rather than
        inferred from the transcript, because the tool writes the row — and it was cleared
        when this run started, so whatever is there now was reported by this run.
        """
        outcome = await self._drain(backend, request, task_id, member_id)
        inbox = request.inbox
        asked = False
        # One more message into the same session for as long as there is one to send: a
        # note the user wrote while it worked and the runtime did not take mid-turn (§10.8),
        # then — once — the request for a result type. Notes first: what the user said may
        # be exactly what the result depends on.
        while not outcome.errored:
            follow_up = ""
            if (
                not asked
                and self.config.teams.result_follow_up
                # §10.4: only ask for a report the session was equipped to submit.
                # Disabled Studio tools (or a missing SDK) use the plain-text finish prompt.
                and "mcp__voxarium_studio__finish_task" in request.extra_allowed_tools
                and (await self._reload(task_id)).result_type is None
            ):
                follow_up = self.config.teams.prompts.text(
                    "result_required", language=summary_language((await self._reload(task_id)).summary_locale)
                )
            session_id = (await self._reload(task_id)).agent_session_id
            # Nothing is awaited between this test and the close below, so a note the inbox
            # accepted is one this loop sends.
            if inbox is not None and len(inbox):
                notes = await inbox.take()
                text = inbox.compose(notes)
                # A backend without resumption has no session to add to, so it gets the
                # task again with the note under it, as it does for a worker's results.
                prompt = text if session_id else f"{request.prompt}\n\n{text}"
            elif follow_up and session_id:
                asked = True
                await self.record_message(
                    task_id,
                    member_id,
                    role="system",
                    kind="result_missing",
                    text=(
                        "The run ended without calling finish_task — asking once for the "
                        "result type."
                    ),
                )
                prompt = follow_up
            else:
                if inbox is not None:
                    inbox.close()
                break
            # The same session, one more message: the pictures and the recovery context
            # belong to the opening turn and are not sent again.
            outcome = await self._drain(
                backend,
                replace(
                    request,
                    prompt=prompt,
                    images=[],
                    resume_session_id=session_id,
                    recovery_prompt="",
                ),
                task_id,
                member_id,
            )
        if outcome.errored:
            return outcome.limit

        # Work finished — hand it to the manager's review queue (§10.4 step 3).
        # `None` when this run said nothing at all, which leaves the previous run's
        # summary in place rather than blanking it on a resume that only used tools — and
        # when `finish_task` already wrote the summary, which is the one the type belongs
        # to rather than the run's closing words.
        saved = await self._reload(task_id)
        if saved.status != TaskStatus.DONE:
            await self._set_task_status(
                task_id,
                TaskStatus.NEEDS_REVIEW,
                result_summary=None if saved.result_type else (outcome.final_text[:4000] or None),
                finished=True,
            )
        return None

    async def _drain_parent_events(
        self, backend: Any, request: AgentRunRequest, task_id: str, member_id: str,
    ) -> _RunOutcome:
        """A closing parent turn joins workers and consumes their results (§10.3)."""
        while True:
            # §10.10: compaction and a backend recovery can start a fresh session on
            # an internal continuation too. Keep full requirements outside transcript
            # summarisation, and never repeat them during ordinary successful resumes.
            required = load_required_context(self.config.teams, request.member, self.skills, self.docs)
            if not request.resume_session_id and required.text and required.text not in request.prompt:
                request = replace(request, prompt=required.prepend(request.prompt))
            if request.resume_session_id and getattr(backend, "session_recovery", False):
                recovered = request.recovery_prompt or recovery_prompt(
                    request.prompt, await self._messages_of(task_id), request.task)
                if required.text and required.text not in recovered:
                    recovered = required.prepend(recovered)
                request = replace(request, recovery_prompt=recovered)
            outcome = await self._drain_events(backend, request, task_id, member_id)
            if outcome.errored:
                return outcome
            results = await self.subagents.collect_pending(task_id)
            if not results:
                return outcome
            saved = await self._reload(task_id)
            prompt = results_prompt(results)
            # A backend without resumption still gets the original task and workspace
            # instructions, plus worker results. Each additional round spends a launch
            # from the bounded per-run worker budget, so this loop is finite.
            if not saved.agent_session_id:
                prompt = request.prompt + "\n\n" + prompt
            await self.record_message(
                task_id, member_id, role="system", kind="continued",
                text="Subagents finished; returning their results to the parent agent.",
            )
            request = replace(request, prompt=prompt, images=[], recovery_prompt="",
                              resume_session_id=saved.agent_session_id)

    async def _drain(
        self, backend: Any, request: AgentRunRequest, task_id: str, member_id: str
    ) -> _RunOutcome:
        """Observe disk writes around each actual session, including failed ones (§10.4)."""
        await self._auto_compress(backend, request)
        task = await self.find_task(task_id)
        if task is not None and task.agent_session_id is None:
            messages = await self._messages_of(task_id)
            summary = next((m for m in messages if m.kind in compaction.SUMMARY_KINDS), None)
            if summary is not None:
                pending = [m.text for m in messages if m.kind in {"note", "review"}]
                request = replace(
                    request, resume_session_id=None, recovery_prompt="",
                    prompt=("Continue the existing task from this compressed history. Do not "
                            "repeat completed work.\n\n" + summary.text +
                            "\n\nNew notes and reviews:\n" + "\n\n".join(pending) +
                            "\n\nCurrent request:\n" + request.prompt),
                )
        if task and task.worktree_path and Path(task.worktree_path).exists():
            return await self._drain_parent_events(backend, request, task_id, member_id)

        async def record(paths: list[str], error: str | None) -> None:
            # Chunk for storage/transport, never truncate the observation as Explorer does.
            for offset in range(0, len(paths), 200):
                await self.record_message(
                    task_id, member_id, role="system", kind="file_observation",
                    data={"paths": paths[offset:offset + 200], "root": str(self.paths.root)},
                )
            if error:
                await self.record_message(
                    task_id, member_id, role="system", kind="file_observation_error", text=error,
                )

        async def progress(text: str, data: dict[str, Any]) -> None:
            await self.record_message(
                task_id, member_id, role="system", kind="file_observation_status",
                text=text, data=data,
            )

        observer = FileObserver(
            self.paths.root, self.config.explorer.ignore, record, progress=progress,
        )
        cancelled = False
        try:
            try:
                await observer.start()
            except Exception as exc:
                await observer.cancel()
                await record([], f"File observation unavailable: {exc}")
            # Observe the whole parent/worker group, including the interval where
            # the parent's CLI has ended and children are still writing (§10.3).
            return await self._drain_parent_events(backend, request, task_id, member_id)
        except asyncio.CancelledError:
            # Cancellation covers preparation too (§10.4). Do not start another disk
            # traversal after Stop; already persisted observations remain available.
            cancelled = True
            await observer.cancel()
            raise
        finally:
            if not cancelled:
                try:
                    await observer.finish()
                except asyncio.CancelledError:
                    await observer.cancel()
                    raise
                except Exception as exc:
                    log.exception("Could not finish file observation for %s", task_id)
                    await observer.cancel()
                    await record([], f"File observation incomplete: {exc}")

    async def _drain_events(
        self, backend: Any, request: AgentRunRequest, task_id: str, member_id: str
    ) -> _RunOutcome:
        """Read one ``backend.run`` to its end, recording every event on the task."""
        from .context_viewer import capture_request

        await capture_request(self.db, task_id, request)
        # Two candidates for the summary, tracked apart on purpose. The backend's final
        # `result` event is the authoritative one, but it comes back empty when a run stops
        # on `max_turns` or ends on a tool call, and an empty summary on a task waiting for
        # review tells the reviewer nothing — so the agent's last spoken message stands in.
        last_assistant_text = ""
        result_text = ""
        errored = False
        # The last `rejected` frame the run saw, if any: it is where the reset instant comes
        # from, and it arrives *before* the failure it explains (§10.3).
        reported: usage_limits.UsageLimit | None = None
        limit: usage_limits.UsageLimit | None = None
        usage = RunUsage()
        credited_tokens = 0
        credited_uncached = credited_cached = 0
        credited_input = credited_output = 0

        async for event in backend.run(request):
            if event.data.get("subtype") == "session_recovered":
                await capture_request(self.db, task_id, replace(
                    request, resume_session_id=None,
                    prompt=request.recovery_prompt or recovery_prompt(request.prompt, [], request.task),
                ))
            # §10.4: persist reported spend while work is running, including a run
            # cancelled before its result. The result only adds the uncredited balance.
            live_usage = usage.observe(event)
            if event.kind == "result":
                usage.finish(event.data)
                event = replace(event, data={**event.data, **usage.counters()})
            if not (event.kind == "system" and event.data.get("subtype") == "usage_update"):
                visible = event
                if event.kind == "result" and self.subagents.has_pending(task_id):
                    visible = replace(event, data={**event.data, "continued": True})
                await self._publish_message(task_id, member_id, visible)
            if live_usage:
                await self._publish_usage_update(
                    task_id, member_id, usage, delta=usage.total - credited_tokens,
                    uncached_delta=usage.uncached - credited_uncached,
                    cached_delta=usage.cached - credited_cached,
                    input_delta=usage.input - credited_input,
                    output_delta=usage.output - credited_output,
                )
                credited_tokens = usage.total
                credited_uncached, credited_cached = usage.uncached, usage.cached
                credited_input, credited_output = usage.input, usage.output
            if event.session_id:
                await self._store_session_id(task_id, event.session_id)
            if event.kind == "text" and event.text:
                last_assistant_text = event.text
            if event.kind == "rate_limit":
                reported = usage_limits.from_rate_limit(event.data) or reported
            if event.kind == "error":
                errored = True
                limit = limit or usage_limits.stopped_by(event.text, event.data, reported)
                await self._set_task_status(task_id, TaskStatus.BLOCKED, error=event.text)
            if event.kind == "result":
                usage.finish(event.data)
                await self._store_result_stats(task_id, {
                    **event.data, "total_tokens": usage.total - credited_tokens,
                    "total_uncached_tokens": usage.uncached - credited_uncached,
                    "total_cached_tokens": usage.cached - credited_cached,
                    "total_input_tokens": usage.input - credited_input,
                    "total_output_tokens": usage.output - credited_output,
                })
                await self._publish_usage_update(task_id, member_id, usage)
                usage.next_segment()
                credited_tokens = 0
                credited_uncached = credited_cached = 0
                credited_input = credited_output = 0
                # A `continued` result ended a turn and not the run — a session that
                # stopped mid-work and is being resumed, or one held open because the
                # sub-agents and workflows the member started are still running (§10.3).
                # Either way it describes neither the run's outcome nor its summary, and
                # its `is_error` is the turn cap rather than a failure. The cost and turns
                # above still count — they were spent.
                if event.data.get("continued"):
                    continue
                result_text = event.text or result_text
                if event.data.get("is_error"):
                    errored = True
                    found = usage_limits.stopped_by(event.text, event.data, reported)
                    if found is not None and limit is None:
                        limit = found
                        # A usage limit arrives on the **result** frame and brings no
                        # `error` event with it — `api_error_status` is set exactly there
                        # (§10.3) — so this is the only place a run ended by one can be
                        # given an ending at all. Blocked rather than left In progress: a
                        # wait attaches to a blocked task, and a task that is *not* waiting
                        # has to be somewhere a person can act on it.
                        await self._set_task_status(
                            task_id, TaskStatus.BLOCKED, error=event.text or found.detail
                        )

        return _RunOutcome(
            errored=errored, limit=limit, final_text=result_text or last_assistant_text
        )

    # ------------------------------------------------------------------ usage limits

    async def _record_limit(
        self, task_id: str, member_id: str, limit: usage_limits.UsageLimit
    ) -> None:
        """Note that the account's usage ended this run, and wait it out if asked (§10.3).

        The reset is written onto the task whether or not ``resume_on_limit`` is on. It is
        the answer to "why is this blocked and when can it go again", which is worth showing
        either way, and it is what lets the box be ticked *afterwards* and still know what
        to wait for.
        """
        async with self.db.session() as session:
            task = await session.get(TeamTask, task_id)
            if task is None:
                return
            task.limit_reset_at = limit.reset_at
            session.add(task)

        resume_at = await self._arm_limit_wait(task_id)
        reset = _when(limit.reset_at)
        if resume_at is not None:
            what_next = f"Waiting for it — the task starts again at {_when(resume_at)}."
        elif limit.reset_at is None:
            what_next = (
                "The runtime did not say when it resets, so the task stays blocked. Set "
                "teams.limit_fallback_wait to retry blind after a fixed wait."
            )
        else:
            what_next = (
                "Resume on Limit reset is off on this task, so it stays blocked until "
                "somebody starts it again."
            )
        await self.record_message(
            task_id,
            member_id,
            role="system",
            kind="continued",
            text=(
                f"The run stopped because {limit.detail}"
                + (f", which resets at {reset}" if limit.reset_at else "")
                + f". {what_next}"
            ),
            data={
                "subtype": "usage_limit",
                "detail": limit.detail,
                "reset_at": limit.reset_at.isoformat() if limit.reset_at else None,
                "resume_at": resume_at.isoformat() if resume_at else None,
            },
        )
        if resume_at is None:
            # The arming path publishes for itself; this is the case where only the reset
            # landed on the row, and the card would otherwise not show it.
            await self._publish_task_changed(task_id)

    async def _arm_limit_wait(self, task_id: str) -> datetime | None:
        """Schedule the restart of a task a usage limit stopped, or decline to.

        Returns when the task will start again, or ``None`` when nothing was armed: the box
        is off, the task is not sitting in ``blocked`` after all, or the limit was never
        dated and ``teams.limit_fallback_wait`` is off.

        Idempotent — an existing timer for the task is replaced — which is what makes it
        safe to call from the end of a run, from the checkbox, and from startup.
        """
        async with self.db.session() as session:
            task = await session.get(TeamTask, task_id)
            if task is None or not task.resume_on_limit:
                return None
            if TaskStatus(task.status) != TaskStatus.BLOCKED:
                # A wait undoes one specific ending. A task somebody has since moved on is
                # not in it, and starting that one later would be a second opinion.
                return None
            resume_at = usage_limits.UsageLimit(
                detail="", reset_at=task.limit_reset_at
            ).resume_at(
                grace=self.config.teams.limit_resume_grace,
                fallback=self.config.teams.limit_fallback_wait,
            )
            if resume_at is None:
                return None
            task.limit_resume_at = resume_at
            session.add(task)

        if not self.producer_holds(task):
            self._schedule_limit_wait(task_id, resume_at)
        await self._publish_task_changed(task_id)
        return resume_at

    async def _clear_limit_wait(self, task_id: str) -> None:
        """Stop waiting, keeping the reset itself: the task is still blocked *by* a limit
        and should still say when it lifts — it has simply stopped acting on it."""
        if not self._cancel_limit_wait(task_id):
            return
        async with self.db.session() as session:
            task = await session.get(TeamTask, task_id)
            if task is not None:
                task.limit_resume_at = None
                session.add(task)
        await self._publish_task_changed(task_id)

    def _schedule_limit_wait(self, task_id: str, resume_at: datetime) -> None:
        self._cancel_limit_wait(task_id)
        self._limit_waits[task_id] = asyncio.create_task(
            self._wait_out_limit(task_id, resume_at), name=f"limit_wait:{task_id}"
        )

    def _cancel_limit_wait(self, task_id: str) -> bool:
        """Drop a task's timer. Returns whether there was one."""
        waiting = self._limit_waits.pop(task_id, None)
        if waiting is None:
            return False
        # Cancelling the wait *from inside* the wait is the resume path clearing up after
        # itself — the coroutine is mid-flight and must not be shot at its own last line.
        if waiting is not asyncio.current_task() and not waiting.done():
            waiting.cancel()
        return True

    def cancel_limit_waits(self) -> None:
        """Drop every timer, for shutdown. The rows keep ``limit_resume_at``, so the next
        startup re-arms whatever is still due (:meth:`resume_limit_waits`)."""
        for task_id in list(self._limit_waits):
            self._cancel_limit_wait(task_id)

    async def resume_limit_waits(self) -> int:
        """Re-arm the waits a restart interrupted, and return how many (§10.3).

        The stored ``limit_resume_at`` is used as it stands rather than recomputed: it is
        the instant the wait was armed for, the countdown was drawn against it, and
        ``teams.limit_resume_grace`` may have been edited since. One already past fires
        immediately, which is right — the window refilled while the Studio was down.
        """
        async with self.db.session() as session:
            rows = await session.exec(
                select(TeamTask)
                .where(TeamTask.status == TaskStatus.BLOCKED)
                .where(TeamTask.resume_on_limit == True)  # noqa: E712 — SQL, not Python
            )
            due = [(row.id, row.limit_resume_at) for row in rows.all()
                   if row.limit_resume_at and not self.producer_holds(row)]
        for task_id, resume_at in due:
            self._schedule_limit_wait(task_id, resume_at)
        if due:
            log.info("Re-armed %d task(s) waiting for a usage limit to reset", len(due))
        return len(due)

    async def _wait_out_limit(self, task_id: str, resume_at: datetime) -> None:
        try:
            delay = (resume_at - utcnow()).total_seconds()
            if delay > 0:
                await asyncio.sleep(delay)
            await self._run_ended(task_id)
            await self._resume_after_limit(task_id)
        except asyncio.CancelledError:
            raise
        except Exception:
            # A failure here must not take the server's event loop with it: the task is
            # still Blocked, which is a state a person can act on.
            log.exception("Could not resume task %s after its usage limit reset", task_id)
        finally:
            if self._limit_waits.get(task_id) is asyncio.current_task():
                self._limit_waits.pop(task_id, None)

    async def _run_ended(self, task_id: str) -> None:
        """Let the run that armed a wait finish before another is started (§10.3).

        A wait for a reset that has **already passed** comes due while the run that armed it
        is still in the job engine's registry — the usual case after a restart — and
        ``start_task`` refuses a task that is already running. Nothing here cancels
        anything: that run is on its last lines by the time this is reached, and the bound
        is only so a wedged one cannot hold the wait for ever.
        """
        handle = self.jobs.get(task_id)
        run = handle.task if handle is not None else None
        if run is None or run.done() or run is asyncio.current_task():
            return
        await asyncio.wait([run], timeout=60.0)

    async def _resume_after_limit(self, task_id: str) -> None:
        """The wait is over: release the task and start it, exactly as a person would.

        Everything is re-read rather than remembered from when the wait was armed — hours
        have passed, and the box may have been unticked, the task moved, or the whole thing
        cancelled in the meantime.
        """
        async with self.db.session() as session:
            task = await session.get(TeamTask, task_id)
            if task is None or not task.resume_on_limit:
                return
            if self.producer_holds(task):
                return
            if TaskStatus(task.status) != TaskStatus.BLOCKED:
                return
            member_id = task.assignee_id
            reset = _when(task.limit_reset_at)

        await self.record_message(
            task_id,
            member_id,
            role="system",
            kind="continued",
            text=f"The usage limit reset at {reset}. Starting the task again.",
            data={"subtype": "usage_limit_over"},
        )
        # Through Pending like every other start (§10.4): `start_task` runs from there and
        # nowhere else, and this is the same pair of moves a person makes by hand.
        await self._set_task_status(
            task_id, TaskStatus.PENDING, reason="usage limit reset"
        )
        try:
            await self.start_task(task_id)
        except ValueError as exc:
            # Refused rather than broken — the member is busy in solo mode, or a dependency
            # came undone while the task waited. It stays in Pending, where auto-start or a
            # click picks it up; saying so beats a task that quietly did not restart.
            await self.record_message(
                task_id,
                member_id,
                role="system",
                kind="continued",
                text=f"Left in Pending rather than started: {exc}",
                data={"subtype": "usage_limit_not_started"},
            )

    async def _publish_message(self, task_id: str, member_id: str, event: AgentEvent) -> None:
        await self.record_message(
            task_id,
            member_id,
            role="assistant" if event.kind in ("text", "thinking", "tool_use") else "system",
            kind=event.kind,
            text=event.text,
            data=event.data,
        )

    async def record_message(
        self,
        task_id: str,
        member_id: str,
        *,
        role: str,
        kind: str,
        text: str = "",
        data: dict[str, Any] | None = None,
    ) -> AgentMessage:
        """Persist one transcript entry and stream it to the UI. Returns the stored row."""
        message = AgentMessage(
            task_id=task_id,
            member_id=member_id,
            role=role,
            kind=kind,
            text=text,
            data=data or {},
        )
        state = compact_state(message, task_id)
        async with self.db.session() as session:
            task = await session.get(TeamTask, task_id)
            if task is not None and task.archived and verbose_only(role, kind):
                return message
            if state:
                await session.merge(message)
            else:
                session.add(message)

        await self.bus.publish(
            EventType.TEAM_MEMBER_MESSAGE,
            {
                "task_id": task_id,
                "member_id": member_id,
                "role": role,
                "kind": kind,
                "text": text,
                "data": message.data,
                "id": message.id,
                "created_at": message.created_at.isoformat(),
            },
            channel=f"team.{member_id}.message",
        )
        return message

    async def _record_status_change(
        self,
        *,
        task_id: str,
        member_id: str,
        previous: TaskStatus | None,
        status: TaskStatus,
        actor_id: str | None = None,
        reason: str = "",
    ) -> AgentMessage:
        """Write one status transition into the task's transcript.

        The transcript is the task's history, so status lives there rather than in a table
        of its own: what a task went through and what was said about it read as one
        timeline, and the UI that already streams `team.member.message` needs nothing new
        to show it. ``previous`` is ``None`` for the entry a task is created with.
        """
        label = "created" if previous is None else str(previous)
        text = f"{label} → {status}" + (f" — {reason.strip()}" if reason.strip() else "")
        return await self.record_message(
            task_id,
            member_id,
            role="system",
            kind="status",
            text=text,
            data={
                "from": str(previous) if previous is not None else None,
                "to": str(status),
                "actor_id": actor_id,
                "reason": reason,
            },
        )

    async def _record_edit(
        self,
        task_id: str,
        changes: dict[str, tuple[Any, Any]],
        *,
        member_id: str,
        actor_id: str | None,
    ) -> AgentMessage:
        """Note a field edit in the transcript, next to the status changes it sits between.

        **Writing the brief is one entry, not one per pause.** It saves itself as it is
        typed (§10.4), so a brief written in one go arrives as a patch every time the typing
        stops — and a dozen "Edited brief" lines would bury the run they sit between while
        telling the reader nothing the last one does not. Those entries fold into the one
        they continue: it keeps the value the brief *started* at and takes the latest, so
        the row still describes exactly what changed.

        Nothing else folds (:attr:`COALESCED_EDIT_FIELDS`), and only the task's newest
        message is ever extended. Once anything else has been said — the agent, a status
        change, a review — the next edit stands on its own, because rewriting an entry
        someone has already read past is not a history.
        """
        folded = await self._extend_last_edit(
            task_id, changes, member_id=member_id, actor_id=actor_id
        )
        if folded is not None:
            return folded
        return await self.record_message(
            task_id,
            member_id,
            role="human" if actor_id else "user",
            kind="edited",
            text="Edited " + ", ".join(sorted(changes)),
            data={
                "fields": {
                    field: {"from": before, "to": after}
                    for field, (before, after) in changes.items()
                },
                "actor_id": actor_id,
            },
        )

    async def _extend_last_edit(
        self,
        task_id: str,
        changes: dict[str, tuple[Any, Any]],
        *,
        member_id: str,
        actor_id: str | None,
    ) -> AgentMessage | None:
        """Fold ``changes`` into the edit entry they continue, or ``None`` if they start one.

        The two entries have to be about the same foldable fields — a patch carrying the
        brief *and* an assignee is a deliberate Save, and gets a line of its own.

        No event is published for a fold: the row's id is already on every client, and the
        edit reaches them as ``team.task.updated``, which refetches the transcript — the
        same path a first edit takes for everything except the open task's own appended row.
        """
        touched = set(changes)
        if not touched or not touched <= self.COALESCED_EDIT_FIELDS:
            return None

        async with self.db.session() as session:
            last = (
                await session.exec(
                    select(AgentMessage)
                    .where(AgentMessage.task_id == task_id)
                    .order_by(AgentMessage.created_at.desc())  # type: ignore[attr-defined]
                    .limit(1)
                )
            ).first()
            if last is None or last.kind != "edited" or last.member_id != member_id:
                return None
            previous = last.data or {}
            if previous.get("actor_id") != actor_id:
                return None
            fields = {
                field: dict(value)
                for field, value in (previous.get("fields") or {}).items()
                if isinstance(value, dict)
            }
            if set(fields) != touched:
                return None
            if utcnow() - last.created_at > self.EDIT_COALESCE_WINDOW:
                return None

            for field, (_before, after) in changes.items():
                # Only the destination moves: the entry keeps where the field was when this
                # sitting began, which is what makes one row describe the whole of it.
                fields[field] = {**fields[field], "to": after}

            # Assigned rather than mutated: a JSON column is not tracked in place, so an
            # edit to the dict already on the row would never reach the database.
            last.data = {**previous, "fields": fields}
            session.add(last)
            # `text` is unchanged — the same fields are named — and so is `created_at`: the
            # entry sits at the moment the editing started, and moving it would shuffle the
            # transcript under whoever is reading it.
            return last

    async def _store_session_id(self, task_id: str, session_id: str) -> None:
        async with self.db.session() as session:
            task = await session.get(TeamTask, task_id)
            if task is not None and task.agent_session_id != session_id:
                task.agent_session_id = session_id
                session.add(task)

    async def _store_result_stats(self, task_id: str, data: dict[str, Any]) -> None:
        uncached, cached = usage_components(data)
        sent, received = usage_directions(data)
        async with self.db.session() as session:
            await session.exec(update(TeamTask).where(TeamTask.id == task_id).values(
                total_cost_usd=TeamTask.total_cost_usd + float(data.get("total_cost_usd") or 0.0),
                num_turns=TeamTask.num_turns + int(data.get("num_turns") or 0),
                total_tokens=TeamTask.total_tokens + int(data.get("total_tokens") or 0),
                total_uncached_tokens=TeamTask.total_uncached_tokens + uncached,
                total_cached_tokens=TeamTask.total_cached_tokens + cached,
                total_input_tokens=TeamTask.total_input_tokens + sent,
                total_output_tokens=TeamTask.total_output_tokens + received,
            ))

    async def _publish_usage_update(
        self, task_id: str, member_id: str, usage: RunUsage, *, delta: int = 0,
        uncached_delta: int = 0, cached_delta: int = 0,
        input_delta: int = 0, output_delta: int = 0,
    ) -> None:
        """One persisted counter and one replaceable WS row, independent of backend (§10.4)."""
        async with self.db.session() as session:
            await session.exec(update(TeamTask).where(TeamTask.id == task_id).values(
                total_tokens=TeamTask.total_tokens + delta,
                total_uncached_tokens=TeamTask.total_uncached_tokens + uncached_delta,
                total_cached_tokens=TeamTask.total_cached_tokens + cached_delta,
                total_input_tokens=TeamTask.total_input_tokens + input_delta,
                total_output_tokens=TeamTask.total_output_tokens + output_delta,
            ))
            task = await session.get(TeamTask, task_id)
            if task is None:
                return
            task_total = task.total_tokens
            task_uncached, task_cached = task.total_uncached_tokens, task.total_cached_tokens
            task_input, task_output = task.total_input_tokens, task.total_output_tokens
        await self._publish_message(task_id, member_id, usage.snapshot(
            task_total, task_uncached, task_cached, task_input, task_output))

    async def _cleanup_task_temp(self, task: TeamTask) -> None:
        try:
            async with self._temp_locks.setdefault(task.id, asyncio.Lock()):
                # A reopen may race with cleanup; the next run acquires this same lock
                # before creating its directory, so it never loses newly written files.
                if (await self._reload(task.id)).status != TaskStatus.DONE:
                    return
                await asyncio.to_thread(task_temp.remove_task_temp, self.paths, task)
            await self._publish_task_changed(task.id)
        except (OSError, ValueError) as exc:
            log.exception("Could not remove task %s temporary files", task.id)
            await self.record_message(
                task.id, task.assignee_id, role="system", kind="error",
                text=f"Could not remove this task's temporary files: {exc}",
            )

    async def _set_task_status(
        self,
        task_id: str,
        status: TaskStatus,
        *,
        result_summary: str | None = None,
        result_type: str | None = None,
        error: str | None = None,
        started: bool = False,
        finished: bool = False,
        actor_id: str | None = None,
        reason: str = "",
    ) -> None:
        """The one place a task's status changes — and therefore the one place its history
        is written. Every route in this module funnels through here, so an entry lands
        whether the move came from a person on the board or from an agent finishing a run.
        """
        async with self.db.session() as session:
            task = await session.get(TeamTask, task_id)
            if task is None:
                return
            previous = TaskStatus(task.status)
            task.status = status
            if (task.producer_chat_id and not task.producer_approved
                    and status not in {TaskStatus.TODO, TaskStatus.CANCELLED}):
                raise ValueError("Approve this task's plan in a Producer chat before releasing tasks.")
            if result_summary is not None:
                task.result_summary = result_summary
            if result_type is not None:
                task.result_type = result_type
            if error is not None:
                task.error = error
            if started:
                task.started_at = utcnow()
                task.finished_at = None
                task.error = None
                # The type describes one arrival in review (§10.4); a run starting is the
                # work being redone, so the next arrival has to be reported afresh.
                task.result_type = None
            if finished:
                task.finished_at = utcnow()
            # A usage-limit wait belongs to one blocked run (§10.3), so any move off
            # `blocked` ends it — the resume itself, a person taking the task back, a
            # cancel. Leaving the times behind would draw a countdown for a wait that is
            # over.
            waited = task.limit_reset_at is not None or task.limit_resume_at is not None
            if status != TaskStatus.BLOCKED and waited:
                task.limit_reset_at = None
                task.limit_resume_at = None
            session.add(task)
            assignee_id = task.assignee_id
            team_id = task.team_id

        if status in {TaskStatus.TODO, TaskStatus.NEEDS_REVIEW, TaskStatus.DONE, TaskStatus.CANCELLED}:
            self._set_board_release(task, False)

        if status != TaskStatus.BLOCKED:
            self._cancel_limit_wait(task_id)

        if status == TaskStatus.DONE and not self.jobs.is_running(task_id):
            await self._cleanup_task_temp(task)

        if previous != status:
            await self._record_status_change(
                task_id=task_id,
                member_id=actor_id or assignee_id,
                previous=previous,
                status=status,
                actor_id=actor_id,
                reason=reason,
            )

        await self.bus.publish(
            EventType.TEAM_TASK_STATUS_CHANGED,
            {
                "task_id": task_id,
                "team_id": team_id,
                "assignee_id": assignee_id,
                "status": str(status),
                # Published whether or not the value moved — a summary landing re-sends the
                # status — so a listener that means "changed to" compares the two (§6.5).
                "previous": str(previous),
                "error": error,
            },
        )

        if status == TaskStatus.DONE:
            # Every route to `done` runs through here, so this is the one place a follow-up
            # task can be released from — whether a manager approved it or a human closed it.
            await self._dispatch_dependents(task_id)

        # Arriving in review is when the work is whole and about to be read, so it is when
        # a commit is drafted for it (§10.4). "Arriving", not "being in": a summary landing
        # re-sends the status, and that must not redraft — or rebill — anything.
        if previous != status and status == TaskStatus.NEEDS_REVIEW:
            self.start_commit_proposal(task_id)

    # ------------------------------------------------------------------ what changed

    async def task_changes(self, task_id: str) -> dict[str, Any]:
        """The files one task changed, and where the answer came from (§10.5).

        ``source`` is the part a caller must not ignore. ``workspace`` is the real thing —
        this task's own checkout, diffed against the commit it was branched from, so what
        the agent committed itself counts too. ``tools`` is the fallback for a task that
        never had a checkout: tool paths plus separately labelled disk observations.
        Tool-only counts are requested edits; observations establish timing but cannot
        identify the writer. The per-file ``evidence`` preserves this distinction (§10.4).
        """
        task = await self.find_task(task_id)
        if task is None:
            raise ValueError(f"No task {task_id!r}")

        worktree = Path(task.worktree_path) if task.worktree_path else None
        if worktree is not None and worktree.exists():
            files, branch, error = await task_changes_source.workspace_changes(self.vcs, worktree)
            # A checkout's status is a statement about that checkout, so every path in it
            # is inside the directory the session worked in by construction.
            return {
                "source": "workspace",
                "root": str(worktree),
                "workdir": str(worktree),
                "branch": branch,
                "files": [
                    {**change, "outside": False} for change in files
                    if not temp_changes.is_temp_path(change["path"], self.paths, worktree)
                ],
                "error": error,
                "reason": None,
                **await asyncio.to_thread(temp_changes.list_temp_files, self.paths, task),
            }

        messages = compaction.expand_evidence(await self._messages_of(task.id))
        touched = task_changes_source.files_touched(
            messages, self.paths.root, docs=self.paths.docs
        )
        totals = task_changes_source.edit_totals(messages, self.paths.root, docs=self.paths.docs)
        observed = task_changes_source.observed_files(messages, self.paths.root)
        tool_paths = set(touched)
        touched = list(dict.fromkeys([*touched, *observed]))
        workdir = self._recorded_workdir(messages)
        return {
            "source": "tools",
            "root": str(self.paths.root),
            "workdir": str(workdir),
            "branch": None,
            "files": [
                {
                    "path": path,
                    "evidence": (
                        "tools_and_observed" if path in tool_paths and path in observed
                        else "tools" if path in tool_paths else "observed"
                    ),
                    "kind": "unknown",
                    "insertions": (totals.get(path) or {}).get("insertions") if path not in observed else None,
                    "deletions": (totals.get(path) or {}).get("deletions") if path not in observed else None,
                    "outside": task_changes_source.is_outside(path, self.paths.root, workdir),
                }
                for path in touched
                if not temp_changes.is_temp_path(path, self.paths, self.paths.root)
            ],
            "error": None,
            "reason": self._no_workspace_reason(task),
            "observation_errors": [m.text for m in messages if m.kind == "file_observation_error"],
            **await asyncio.to_thread(temp_changes.list_temp_files, self.paths, task),
        }

    async def _messages_of(self, task_id: str) -> list[AgentMessage]:
        async with self.db.session() as session:
            return list(
                (
                    await session.exec(
                        select(AgentMessage)
                        .where(AgentMessage.task_id == task_id)
                        .order_by(AgentMessage.created_at)  # type: ignore[arg-type]
                    )
                ).all()
            )

    def _recorded_workdir(self, messages: list[AgentMessage]) -> Path:
        """Where this task's session was started — the frame the Project / Outside split
        is drawn in (§10.4).

        A run that started anywhere but the project root wrote a ``workdir`` entry naming
        the place (:meth:`_run_task`), and that entry is the answer even after the setting
        changed: the split describes the run that happened. A task with no such entry ran
        at the launch root of its day, which for every ordinary project is the root itself.
        """
        for message in reversed(compaction.expand_evidence(messages)):
            if message.kind != "workdir":
                continue
            data = message.data if isinstance(message.data, dict) else {}
            path = data.get("path")
            if isinstance(path, str) and path:
                return Path(current_data_path(path, self.paths.root))
        return self._launch_root()[0]

    def _no_workspace_reason(self, task: TeamTask) -> str:
        """Why this task has no checkout of its own — the four ways that happens."""
        if task.worktree_path:
            return (
                "This task's checkout is gone from disk. What is listed is the files its "
                "own tool calls named, plus separately labelled disk observations during runs."
            )
        if self.vcs is None:
            return (
                "This project is under no version control, so work cannot be isolated per "
                "task. Listed files come from tool calls and separately labelled disk observations during runs."
            )
        if not self.vcs.supports_isolation:
            return (
                f"{self.vcs.name} cannot give a task its own checkout, so tasks share the "
                "working copy. Listed files come from tool calls and separately labelled disk observations during runs."
            )
        return (
            "This task ran in the project itself rather than its own checkout, so its "
            "changes cannot be told from anyone else's. What is listed is the files its "
            "own tool calls named, plus separately labelled disk observations during runs."
        )

    async def task_patch(self, task_id: str, path: str) -> str:
        """One file of a task's change, as a patch to render.

        Falls back to the project's own working copy for a task with no checkout — which is
        where that file's changes actually are, and is why the answer can legitimately be
        empty: the edit may have been committed, or undone, since.
        """
        task = await self.find_task(task_id)
        if task is None:
            raise ValueError(f"No task {task_id!r}")
        if temp_changes.is_temp_path(path, self.paths, self.paths.root):
            return await asyncio.to_thread(temp_changes.temp_patch, self.paths, task, path)
        if self.vcs is None:
            raise ValueError("This project is under no version control, so there is no diff.")

        worktree = Path(task.worktree_path) if task.worktree_path else None
        try:
            if worktree is not None and worktree.exists():
                return await self.vcs.workspace_patch(worktree, path)
            return await self.vcs.workspace_patch(self.paths.root, path)
        except VcsError as exc:
            raise ValueError(str(exc)) from exc

    # ------------------------------------------------------------------ commit (§10.4)

    def start_commit_proposal(self, task_id: str) -> None:
        """Draft a commit for the task in the background, replacing any draft in progress."""
        self.cancel_commit_proposal(task_id)
        self._commit_jobs[task_id] = asyncio.create_task(self._draft_commit(task_id))

    def cancel_commit_proposal(self, task_id: str) -> None:
        job = self._commit_jobs.pop(task_id, None)
        if job is not None and not job.done():
            job.cancel()

    async def cancel_commit_jobs(self) -> None:
        """For shutdown. A draft is cheap to redo, so nothing is kept to resume it.

        Awaited, not fire-and-forget: ``cancel()`` only schedules the interruption, and a
        draft interrupted *while* the database is being closed under it hangs on a session
        that has nowhere to commit to. Waiting here is what lets ``AppContext.shutdown``
        close the database after the last draft has let go of it.
        """
        jobs = [job for job in self._commit_jobs.values() if not job.done()]
        if jobs:
            # A moment to finish on their own before anything is cancelled: a draft that is
            # interrupted *inside* a database call leaves aiosqlite's worker thread answering
            # a loop that has closed by the time it does. The grace covers every draft that
            # is past its model call; one still waiting on the model is cancelled, which is
            # safe there — nothing of the database is in flight during that wait.
            _, pending = await asyncio.wait(jobs, timeout=COMMIT_DRAFT_SHUTDOWN_GRACE)
            for job in pending:
                job.cancel()
            for job in pending:
                with contextlib.suppress(asyncio.CancelledError, Exception):
                    await job
        self._commit_jobs.clear()

    def commit_drafting(self, task_id: str) -> bool:
        job = self._commit_jobs.get(task_id)
        return job is not None and not job.done()

    async def commit_proposal(self, task_id: str) -> dict[str, Any]:
        """The Commit section's payload: title, draft, files, and whether anything can be
        committed at all."""
        task = await self.find_task(task_id)
        if task is None:
            raise ValueError(f"No task {task_id!r}")
        backend = self.vcs
        available, reason = backend.is_available() if backend is not None else (False, "")
        return {
            "task_id": task.id,
            "title": commit_proposal.commit_title(
                format_task_id(self.task_code, task.number), task.title
            ),
            "message": task.commit_message or "",
            "files": list(task.commit_files or []),
            "generated_at": task.commit_generated_at,
            "generating": self.commit_drafting(task.id),
            "error": task.commit_error,
            "revision": task.commit_revision,
            "enabled": self.config.teams.commit_proposals,
            "model": self.config.teams.commit_model,
            "vcs": {
                "backend": backend.id if backend is not None else None,
                "name": backend.name if backend is not None else None,
                "available": available,
                "reason": reason,
            },
        }

    async def _draft_commit(self, task_id: str) -> None:
        """Gather what the task changed, ask the cheap model for a paragraph, store both.

        No files inside the session's directory means no call: a human's task, or a run
        that only read, has nothing to describe, and a model call per such card would be
        money spent on an empty section. The description is attempted once; a refusal is
        written onto the row, where the section shows it beside a Retry.
        """
        task = await self.find_task(task_id)
        if task is None:
            return
        team_id = task.team_id
        await self._publish_commit_state(task_id, team_id, generating=True)
        error: str | None = None
        description = ""
        files: list[str] = []
        try:
            changes = await self.task_changes(task_id)
            listed = [
                change for change in changes["files"]
                if not change.get("outside") and change.get("evidence") != "observed"
            ]
            files = [change["path"] for change in listed]
            if files and self.config.teams.commit_proposals:
                patch = await self._commit_patch(task, changes["source"], files)
                work = commit_proposal.CommitWork(
                    display_id=format_task_id(self.task_code, task.number),
                    title=task.title,
                    brief=task.brief or "",
                    summary=task.result_summary or "",
                    files=listed,
                    patch=patch,
                )
                try:
                    prompt = commit_proposal.build_prompt(work)
                    if self.commit_describer is not None:
                        description = await self.commit_describer(
                            prompt, self.config.teams.commit_model, self.paths.root,
                        )
                    else:
                        description = await commit_proposal.describe_with_backend(
                            prompt, self.config.teams.commit_model, self.paths.root,
                            self.config.teams.commit_backend,
                        )
                except asyncio.CancelledError:
                    raise
                except Exception as exc:  # noqa: BLE001 - the row carries the reason
                    log.warning("Could not draft a commit description for %s: %s", task_id, exc)
                    error = str(exc) or type(exc).__name__
            async with self.db.session() as session:
                row = await session.get(TeamTask, task_id)
                if row is None:
                    return
                row.commit_message = description
                row.commit_files = files
                row.commit_generated_at = utcnow()
                row.commit_error = error
                row.commit_revision = None
                session.add(row)
        except asyncio.CancelledError:
            # Replaced by a newer draft, or the Studio is stopping: nobody is waiting for
            # the "finished" frame, and publishing from a task being torn down is how a
            # shutdown gets wedged.
            raise
        finally:
            if self._commit_jobs.get(task_id) is asyncio.current_task():
                self._commit_jobs.pop(task_id, None)
        await self._publish_commit_state(task_id, team_id, generating=False, error=error)

    async def _commit_patch(self, task: TeamTask, source: str, files: list[str]) -> str:
        """The diff the description is written from — the checkout's, or each listed file's
        in the shared working copy. Bounded, since the prompt clips it anyway, and never a
        reason to fail the draft: a diff the tool refuses leaves the summary to speak."""
        if self.vcs is None:
            return ""
        worktree = Path(task.worktree_path) if task.worktree_path else None
        try:
            if source == "workspace" and worktree is not None:
                return await self.vcs.workspace_patch(worktree)
            parts: list[str] = []
            size = 0
            for path in files:
                parts.append(await self.vcs.workspace_patch(self.paths.root, path))
                size += len(parts[-1])
                if size > commit_proposal.PATCH_LIMIT:
                    break
            return "\n".join(part for part in parts if part)
        except VcsError as exc:
            log.info("No diff for the commit draft of %s: %s", task.id, exc)
            return ""

    async def commit_task(
        self,
        task_id: str,
        *,
        message: str | None = None,
        files: Sequence[str] | None = None,
    ) -> CommitResult:
        """Commit the task's files through the project's version control (§9.6, §10.4).

        The title is always the board's (``VS-01 | …``); the body is what the reviewer sent,
        or the draft. The files are the draft's unless the reviewer narrowed them, and each
        must lie inside the directory the task worked in — a path outside it is refused
        rather than handed to a tool that would stage it from wherever it is. Committed in
        the task's own checkout when it still has one, since that is where the changes are.
        """
        task = await self.find_task(task_id)
        if task is None:
            raise ValueError(f"No task {task_id!r}")
        if self.vcs is None:
            raise ValueError(
                "This project is under no version control the Studio recognises, so there "
                "is nothing to commit with."
            )
        paths = [p.strip() for p in (files if files is not None else task.commit_files) if p]
        if not paths:
            raise ValueError("Nothing to commit: the task lists no changed files inside the project.")

        worktree = Path(task.worktree_path) if task.worktree_path else None
        in_checkout = worktree is not None and worktree.exists()
        workdir = worktree if in_checkout else self._recorded_workdir(
            await self._messages_of(task.id)
        )
        root = worktree if in_checkout and worktree is not None else self.paths.root
        temporary = [p for p in paths if temp_changes.is_temp_path(p, self.paths, root)]
        if temporary:
            raise ValueError("Temporary task files cannot be committed: " + ", ".join(temporary))
        outside = [p for p in paths if task_changes_source.is_outside(p, root, workdir)]
        if outside:
            raise ValueError(
                "These files are outside the directory the task worked in and cannot be "
                f"committed from it: {', '.join(outside)}"
            )

        title = commit_proposal.commit_title(format_task_id(self.task_code, task.number), task.title)
        body = (message if message is not None else task.commit_message or "").strip()
        full = f"{title}\n\n{body}" if body else title

        backend = (
            type(self.vcs)(worktree, dict(self.vcs.settings))
            if in_checkout and worktree is not None
            else self.vcs
        )

        # Only what the workspace still lists as changed goes to the tool. The draft was
        # taken when the task reached review, and a file in it may since have been
        # committed by hand, reverted, or never have differed at all — and a tool handed
        # such a path refuses the *whole* commit ("not changed in current workspace" from
        # `cm checkin`, "pathspec did not match" from `git add`). Skipped files are
        # reported, not swallowed: the section says what it left out.
        status = await backend.status()
        listed = {
            _path_key(relative)
            for change in status.changes
            if (relative := project_relative(change.path, workspace=status.root, project=root))
        }
        present = [p for p in paths if _path_key(p) in listed]
        skipped = [p for p in paths if _path_key(p) not in listed]
        if not present:
            return CommitResult(
                committed=False, branch=status.branch, message=full, skipped=skipped
            )
        result = await backend.commit(full, paths=present, add_untracked=True)
        result.skipped = skipped

        if result.committed:
            async with self.db.session() as session:
                row = await session.get(TeamTask, task_id)
                if row is not None:
                    row.commit_revision = result.revision or "committed"
                    session.add(row)
            await self.record_message(
                task_id,
                task.assignee_id,
                role="system",
                kind="commit",
                text=(
                    f"Committed {len(result.files)} file(s)"
                    + (f" as {result.revision}" if result.revision else "")
                    + (f" on {result.branch}" if result.branch else "")
                ),
                data={
                    "revision": result.revision,
                    "branch": result.branch,
                    "files": list(result.files),
                    "title": title,
                    "backend": self.vcs.id,
                },
            )
            await self._publish_commit_state(task_id, task.team_id, generating=False)
        return result

    async def _publish_commit_state(
        self, task_id: str, team_id: str, *, generating: bool, error: str | None = None
    ) -> None:
        await self.bus.publish(
            EventType.TEAM_TASK_COMMIT_CHANGED,
            {"task_id": task_id, "team_id": team_id, "generating": generating, "error": error},
        )

    # ------------------------------------------------------------------ cleanup

    async def cleanup_worktree(self, task_id: str, *, force: bool = False) -> None:
        async with self.db.session() as session:
            task = await session.get(TeamTask, task_id)
            if task is None or not task.worktree_path:
                return
            path = Path(task.worktree_path)
            task.worktree_path = None
            session.add(task)
        with contextlib.suppress(Exception):
            await wt.remove_isolated_workspace(self.vcs, path, force=force)


def _path_key(path: str) -> str:
    """Comparable form of a project-relative path: Windows folds case, and a draft written
    by a tool may spell the separator either way."""
    return os.path.normcase(path.replace("\\", "/").strip("/"))


def _attachment_records(data: dict[str, Any] | None) -> list[dict[str, Any]]:
    """The attachment records a review entry carries, or none — never a crash on an entry
    written by hand or by an older Studio."""
    raw = (data or {}).get("attachments")
    if not isinstance(raw, list):
        return []
    return [record for record in raw if isinstance(record, dict) and record.get("path")]


def _when(value: datetime | None) -> str:
    """A timestamp for the transcript. UTC and spelled out, because the reader may not be
    on the machine's clock and a usage window is quoted in the account's own terms."""
    return value.strftime("%Y-%m-%d %H:%M UTC") if value else "an unknown time"


def _skills_note(skills: MemberSkills) -> str:
    """The transcript line saying which skills a run was told about, and which it was not."""
    parts = []
    if skills.found:
        parts.append("Skills this run may use: " + ", ".join(s.name for s in skills.found) + ".")
    if skills.missing:
        parts.append(
            "Not in .voxarium_studio/skills, so not offered: " + ", ".join(skills.missing) + "."
        )
    return " ".join(parts)


def _skills_data(skills: MemberSkills, startup: Iterable[str]) -> dict[str, Any]:
    """The skills record's data, for the transcript and the Status column (§10.4, §10.10).

    ``loaded`` names the skills whose ``SKILL.md`` the session was handed at start-up
    (``required_context``); the rest were only offered. ``labels`` carries the names people
    read, since a native installation is stored under an opaque ``installed:<id>``.
    """
    started = set(startup)
    return {
        "skills": [skill.name for skill in skills.found],
        "missing": list(skills.missing),
        "loaded": [skill.name for skill in skills.found if skill.name in started],
        "labels": {
            skill.name: skill.display_name
            for skill in skills.found
            if skill.display_name and skill.display_name != skill.name
        },
    }


def _mcp_note(servers: dict[str, Any]) -> str:
    """The transcript line naming the MCP servers a run was handed (§10.7)."""
    return "MCP servers this run may use: " + ", ".join(sorted(servers)) + "."


#: Where graphify keeps its knowledge graphs, relative to the directory it indexed, in the
#: order to prefer them. The built-in `graphify` plugin writes them at the agent launch
#: root (§9.7): `merged-graph.json` is the code and the Documents section together, and
#: `graph.json` the code alone — also where the CLI's own defaults look, so a session
#: started there needs no flag for that one.
CODE_GRAPH_CANDIDATES = (
    Path("graphify-out") / "merged-graph.json",
    Path("graphify-out") / "graph.json",
)


def code_graph_file(launch_root: Path) -> Path | None:
    """The graphify graph an agent starting at ``launch_root`` could query, if there is one.

    The *file* is the condition, not the plugin: a graph built by hand with `/graphify`
    counts, and an enabled plugin that has not indexed yet does not — telling a session to
    query a graph that is not there would only cost it a failed command.
    """
    for relative in CODE_GRAPH_CANDIDATES:
        graph = launch_root / relative
        try:
            if graph.is_file():
                return graph
        except OSError:
            continue
    return None


def _build_system_prompt(
    *,
    member: TeamMember,
    team: Team | None,
    manager: TeamMember | None,
    reports: list[TeamMember],
    peers: list[TeamMember],
    task_id: str,
    project_root: Path,
    workdir: Path,
    isolated: bool,
    docs_dir: Path,
    has_studio_tools: bool,
    prompts: AgentPromptsConfig,
    memory: str | None = None,
    temp_dir: Path | None = None,
    code_graph: Path | None = None,
    chat: bool = False,
    skills: Sequence[Skill] = (),
    language: str = summary_language(None),
) -> str:
    """What this *run* tells the agent, as the session's whole system prompt (§10.3).

    Every sentence is a template from ``teams.prompts`` (``core/prompts.py``), resolved
    here with what only this run knows — the task id, the checkout, the docs directory.
    This function decides *which* fragments apply; the words are the user's to change on
    the Settings page, and a fragment blanked there is simply not sent.

    The identity half — who the member is and where they sit in the org chart — is always
    part of this prompt. It used to be left out when the session ran under ``--agent``,
    because the definition on disk carried it; full replacement changed that (2026-11-12).
    Every backend now sends this prompt as the session's *whole* system prompt, and
    Claude's ``--system-prompt`` discards the definition's own prompt (verified against
    the bundled CLI with a captured request), so ``.claude/agents/*.md`` serve the
    developer's terminal and this prompt is the single carrier for runs.

    ``chat`` is a conversation with the member rather than a task (``teams/chat.py``): the
    ``chat_mode`` fragment stands where the task id would, and the closing instruction to
    summarise for a reviewer is left out — there is no reviewer. Everything in between is
    the same, because the member has the same tools and the same project in front of it.

    ``skills`` are the ones the member was given that this checkout has (§10.10). They are
    here and never in the agent definition, though a member's list is roster data: what each
    skill *says* lives in its folder and changes without the roster changing, and the path
    that tells the session where to read it is this machine's.

    ``memory`` is the project memory index (§10.12) as :func:`memory_prompt` renders it, or
    ``None`` when no memory goes to this run — switched off, or no Studio tools to use it with.
    """
    lines = agent_defs.identity_lines(
        member=member, team=team, manager=manager, reports=reports, peers=peers,
        prompts=prompts,
    )

    def say(key: str, **values: str) -> None:
        line = prompts.text(key, **values)
        if line:
            lines.append(line)

    # The one thing this run knows that nothing else can tell the agent: which card on the
    # board it is (§10.2). Without it a summary or a commit message names the work in prose,
    # and the person reading it has to work out which task that was.
    if chat:
        say("chat_mode")
    else:
        say("task_context", task_id=task_id)
        if temp_dir is not None:
            say("task_temp", temp_dir=str(temp_dir))

    # How to use the tools — and who may be handed work, which `list_team` answers per member —
    # is the Studio skill's to explain (`teams/skill.py`); startup context loads it.
    if has_studio_tools:
        say("studio_skill", skill=studio_skill.SKILL_NAME)
    elif reports or peers:
        say("no_studio_tools")

    if skills:
        say("member_skills", skills=skill_prompt_list(skills))

    say("docs_location", docs_dir=str(docs_dir))

    if memory is not None:
        say("project_memory", entries=memory)

    # §10.3: only a task has subagents — `spawn_subagent` refuses a chat, which has no
    # task to split — and only with the Studio's tools is there anything to start one with.
    if has_studio_tools and not chat:
        say("subagents")

    # Absolute, because an isolated checkout starts the session somewhere the CLI's
    # default `graphify-out/graph.json` is not (§9.7): the graph describes the main
    # checkout, which is close enough for a branch of it.
    if code_graph is not None:
        say("code_graph", graph=str(code_graph), skill=studio_skill.SKILL_NAME)

    if isolated:
        say("isolated_checkout", workdir=str(workdir))
    elif workdir != project_root:
        # A launch root outside the project is the case this exists for: the agent needs
        # to be told the project is one folder among the siblings it can now see.
        say("outside_project", workdir=str(workdir), project_root=str(project_root))

    if not chat:
        # With the Studio's tools the summary goes through `finish_task`, where the result
        # type is a required argument (§10.4); without them the run can only speak it.
        say("finish_tool" if has_studio_tools else "finish", language=language)
    return "\n\n".join(lines)
