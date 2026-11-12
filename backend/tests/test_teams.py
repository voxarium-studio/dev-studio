"""Team orchestration: routing, delegation bounds, the human lifecycle, YAML round-trip.

Agent execution runs against a stub backend, so nothing is billed and no credentials are
needed — the real ClaudeCodeBackend is only checked for availability.
"""

from __future__ import annotations

import asyncio
import dataclasses
import sys
import types
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any

import pytest
import yaml
from sqlmodel import select

from voxarium_studio.core.config import MAX_MESSAGE_BYTES_DEFAULT
from voxarium_studio.core.context import AppContext
from voxarium_studio.core.prompts import AgentPromptsConfig
from voxarium_studio.core.prompts import default as default_prompt
from voxarium_studio.models.base import utcnow
from voxarium_studio.models.teams import (
    AgentChat,
    AgentMessage,
    MemberKind,
    ProjectCounter,
    TaskStatus,
    Team,
    TeamMember,
    TeamTask,
)
from voxarium_studio.teams import limits as usage_limits
from voxarium_studio.teams import project_skills
from voxarium_studio.teams import skill as studio_skill
from voxarium_studio.teams.backends import claude_code
from voxarium_studio.teams.backends.base import (
    AgentEvent,
    AgentRunRequest,
    RemoteControl,
    RemoteControlUnavailable,
    forget_live_session,
    live_session,
    register_backend,
    register_live_session,
)
from voxarium_studio.teams.orchestrator import DelegationLimitError, RoutingError
from voxarium_studio.teams.tools import build_toolset

TEAM = "team_default"
DIRECTOR = f"{TEAM}__director"  # human, org-chart root
DESIGNER = f"{TEAM}__designer"  # agent, reports to the director, may assign to dev
DEV = f"{TEAM}__dev"  # agent, reports to the director
QA = f"{TEAM}__qa"  # human, may assign to designer and dev


def _summary_prompt(key: str, language: str = "English") -> str:
    return AgentPromptsConfig().text(key, language=language)


class StubBackend:
    """Stands in for a Claude Code session: a few events, then a clean result."""

    name = "stub"

    def is_available(self):
        return True, "stub"

    async def run(self, request):
        yield AgentEvent(kind="text", text=f"working: {request.prompt[:30]}", session_id="sess_1")
        yield AgentEvent(
            kind="result",
            text="Done.",
            session_id="sess_1",
            data={"is_error": False, "num_turns": 2, "total_cost_usd": 0.02, "total_tokens": 1500},
            is_final=True,
        )


register_backend("stub", StubBackend)


class AlternateTaskBackend(StubBackend):
    name = "stub_alt"
    requests: list[AgentRunRequest] = []

    async def run(self, request):
        self.requests.append(request)
        async for event in super().run(request):
            yield event


register_backend("stub_alt", AlternateTaskBackend)


@pytest.fixture
async def teams(ctx: AppContext) -> AppContext:
    """Point every agent member at the stub backend and off the git worktree path."""
    async with ctx.db.session() as session:
        for member in (await session.exec(select(TeamMember))).all():
            member.agent_backend = "stub"
            member.isolate_worktree = False
            session.add(member)
    return ctx


async def load(ctx: AppContext, task_id: str) -> TeamTask:
    async with ctx.db.session() as session:
        return await session.get(TeamTask, task_id)


async def release(ctx: AppContext, task_id: str) -> TeamTask:
    """Move a task out of `todo` into `pending` — the drag that lets work run (§10.4).

    Every agent run in this file goes through it, because creation no longer starts
    anything: a task with `auto_start` runs from the moment a person releases it, and one
    without waits in `pending` for an explicit `start_task`.
    """
    return await ctx.teams.update_task(task_id, {"status": TaskStatus.PENDING})


async def test_task_backend_override_reaches_runtime(teams: AppContext, wait_for) -> None:
    AlternateTaskBackend.requests.clear()
    task = await teams.teams.create_task(
        team_id=TEAM, assignee_id=DEV, title="other backend", brief="work",
        agent_backend="stub_alt", model="alt-model", auto_start=True,
    )
    await release(teams, task.id)
    await wait_for(task.id)
    request = AlternateTaskBackend.requests[-1]
    assert request.member.agent_backend == "stub_alt"
    assert request.model == "alt-model"
    assert (await load(teams, task.id)).agent_backend == "stub_alt"


# ------------------------------------------------------------------ board-wide release (§10.4)


async def test_autostart_releases_existing_and_incoming_todo_and_can_be_disabled(
    teams: AppContext, wait_for
) -> None:
    first = await _dev_task(teams, "existing", auto_start=False)
    assert not teams.jobs.is_running(first.id)
    assert (await load(teams, first.id)).status == TaskStatus.TODO

    await teams.teams.set_autostart_tasks(TEAM, True)
    await wait_for(first.id)
    assert (await load(teams, first.id)).status == TaskStatus.NEEDS_REVIEW
    assert (await load(teams, first.id)).auto_start

    incoming = await _dev_task(teams, "incoming", auto_start=False)
    await wait_for(incoming.id)
    assert (await load(teams, incoming.id)).status == TaskStatus.NEEDS_REVIEW
    human = await teams.teams.create_task(
        team_id=TEAM, assignee_id=QA, title="human", brief="b", auto_start=True
    )
    assert human.status == TaskStatus.TODO and not human.auto_start

    await teams.teams.set_autostart_tasks(TEAM, False)
    stopped = await _dev_task(teams, "stays in Todo")
    assert stopped.status == TaskStatus.TODO
    assert not teams.jobs.is_running(stopped.id)


async def test_start_all_queues_solo_and_dependencies_without_touching_other_work(
    teams: AppContext, wait_for
) -> None:
    gates, prompts = await _hold_dev_runs(teams, "bulk_held")
    await _solo_on(teams)
    first = await _dev_task(teams, "first", auto_start=False)
    second = await _dev_task(teams, "second", auto_start=False)
    await release(teams, second.id)
    dependent = await teams.teams.create_task(
        team_id=TEAM, assignee_id=DESIGNER, title="dependent", brief="b",
        depends_on=[second.id], auto_start=False,
    )
    human = await teams.teams.create_task(
        team_id=TEAM, assignee_id=QA, title="human", brief="b"
    )
    untouched = []
    for status in (TaskStatus.DONE, TaskStatus.NEEDS_REVIEW, TaskStatus.BLOCKED, TaskStatus.CANCELLED):
        task = await _dev_task(teams, str(status))
        await teams.teams.update_task(task.id, {"status": status})
        untouched.append((task.id, status))
    async with teams.db.session() as session:
        session.add(Team(id="other", name="Other"))
        session.add(TeamMember(id="other_dev", team_id="other", name="Other dev", role_title="Dev"))
    other = await teams.teams.create_task(
        team_id="other", assignee_id="other_dev", title="other", brief="b"
    )

    assert await teams.teams.start_all_tasks(TEAM) == [first.id, second.id, dependent.id]
    await _wait_until(lambda: _running(teams, first.id))
    assert (await load(teams, second.id)).status == TaskStatus.PENDING
    assert (await load(teams, dependent.id)).status == TaskStatus.PENDING
    assert (await load(teams, human.id)).status == TaskStatus.TODO
    assert (await load(teams, other.id)).status == TaskStatus.TODO
    for task_id, status in untouched:
        assert (await load(teams, task_id)).status == status
    async with teams.db.session() as session:
        assert not (await session.get(Team, TEAM)).autostart_tasks

    await asyncio.gather(teams.teams.start_all_tasks(TEAM), teams.teams.start_all_tasks(TEAM))
    assert len(prompts) == 1
    _let_go(gates, first.id)
    await wait_for(first.id)
    await _wait_until(lambda: _running(teams, second.id))
    _let_go(gates, second.id)
    await wait_for(second.id)
    assert len(prompts) == 2
    assert (await load(teams, dependent.id)).status == TaskStatus.PENDING
    await teams.teams.approve(second.id)
    await wait_for(dependent.id)
    assert (await load(teams, dependent.id)).status == TaskStatus.NEEDS_REVIEW


async def test_autostart_handles_return_to_todo_and_reset(teams: AppContext, wait_for) -> None:
    await teams.teams.set_autostart_tasks(TEAM, True)
    task = await _dev_task(teams, "repeat")
    await wait_for(task.id)
    await teams.teams.update_task(task.id, {"status": TaskStatus.TODO})
    await wait_for(task.id)
    assert (await load(teams, task.id)).status == TaskStatus.NEEDS_REVIEW
    await teams.teams.reset_task(task.id)
    await wait_for(task.id)
    assert (await load(teams, task.id)).status == TaskStatus.NEEDS_REVIEW
    assert [entry[2] for entry in await history(teams, task.id)] == [
        "todo", "pending", "in_progress", "needs_review"
    ]


async def test_disabling_autostart_leaves_running_and_released_tasks_alone(
    teams: AppContext, wait_for
) -> None:
    gates, prompts = await _hold_dev_runs(teams, "autostart_held")
    await teams.teams.set_autostart_tasks(TEAM, True)
    first, second = await asyncio.gather(
        _dev_task(teams, "first"), _dev_task(teams, "second")
    )
    await _wait_until(lambda: _running(teams, first.id))
    await teams.teams.set_autostart_tasks(TEAM, False)
    assert teams.jobs.is_running(first.id)
    assert (await load(teams, second.id)).status == TaskStatus.PENDING
    third = await _dev_task(teams, "third")
    _let_go(gates, first.id)
    await wait_for(first.id)
    await _wait_until(lambda: _running(teams, second.id))
    _let_go(gates, second.id)
    await wait_for(second.id)
    assert len(prompts) == 2
    assert (await load(teams, third.id)).status == TaskStatus.TODO


async def test_autostart_round_trip_and_reopening_release_todo_and_pending(
    teams: AppContext, wait_for
) -> None:
    todo = await _dev_task(teams, "todo", auto_start=False)
    pending = await _dev_task(teams, "pending", auto_start=False)
    await release(teams, pending.id)
    parked = await _dev_task(teams, "parked", auto_start=False)
    await release(teams, parked.id)
    async with teams.db.session() as session:
        team = await session.get(Team, TEAM)
        assert not team.autostart_tasks
        team.autostart_tasks = True
        session.add(team)
        task = await session.get(TeamTask, pending.id)
        task.auto_start = True
        session.add(task)
        await session.flush()
        await teams.teams.store.save_from_db(session, TEAM)
    assert "autostart_tasks: true" in teams.teams.store.file_for(TEAM).read_text(encoding="utf-8")
    await teams.teams.sync_definitions()
    await teams.teams.resume_autostart_tasks()
    await wait_for(todo.id)
    await wait_for(pending.id)
    assert (await load(teams, todo.id)).status == TaskStatus.NEEDS_REVIEW
    assert (await load(teams, pending.id)).status == TaskStatus.NEEDS_REVIEW
    assert (await load(teams, parked.id)).status == TaskStatus.PENDING


def test_board_controls_over_rest_are_persisted_and_scope_checked(client, project: Path) -> None:
    human = client.post(f"/api/teams/{TEAM}/tasks", json={
        "assignee_id": QA, "title": "dependency", "brief": "b",
    }).json()
    task = client.post(f"/api/teams/{TEAM}/tasks", json={
        "assignee_id": DEV, "title": "blocked by dependency", "brief": "b",
        "depends_on": [human["id"]], "auto_start": False,
    }).json()
    saved = client.patch(f"/api/teams/{TEAM}", json={"autostart_tasks": True})
    assert saved.status_code == 200
    assert saved.json()["autostart_tasks"] is True
    assert client.get(f"/api/teams/{TEAM}/tasks/{task['id']}").json()["status"] == "pending"
    assert "autostart_tasks: true" in (
        project / ".voxarium_studio/teams/default-team.yaml"
    ).read_text(encoding="utf-8")
    assert client.patch(f"/api/teams/{TEAM}", json={"autostart_tasks": False}).status_code == 200
    started = client.post(f"/api/teams/{TEAM}/tasks/start-all")
    assert started.status_code == 202
    assert started.json()["task_ids"] == [task["id"]]
    assert client.get(f"/api/teams/{TEAM}/tasks/{human['id']}").json()["status"] == "todo"
    assert client.patch("/api/teams/missing", json={"autostart_tasks": True}).status_code == 404
    assert client.post("/api/teams/missing/tasks/start-all").status_code == 404


# ------------------------------------------------------------------ roster


def test_new_members_default_to_full_access_in_database_and_yaml() -> None:
    from voxarium_studio.teams.store import MemberDefinition

    assert TeamMember(team_id=TEAM, name="New agent").permission_mode == "bypassPermissions"
    assert MemberDefinition(id="new", name="New agent").permission_mode == "bypassPermissions"


async def test_the_sample_roster_syncs_with_its_kinds_and_routes(teams: AppContext) -> None:
    async with teams.db.session() as session:
        director = await session.get(TeamMember, DIRECTOR)
        designer = await session.get(TeamMember, DESIGNER)
        qa = await session.get(TeamMember, QA)

    assert director.kind == MemberKind.HUMAN and director.manager_id is None
    assert designer.kind == MemberKind.AGENT and designer.manager_id == DIRECTOR
    assert qa.can_assign_to == [DESIGNER, DEV], "ids are namespaced by team"


# ------------------------------------------------------------------ routing


async def test_the_user_may_assign_to_anyone(teams: AppContext) -> None:
    task = await teams.teams.create_task(
        team_id=TEAM, assignee_id=DEV, title="t", brief="b", created_by=None
    )
    assert task.status == TaskStatus.TODO
    assert task.delegation_depth == 0


async def test_a_manager_may_assign_down_and_a_report_may_escalate_up(teams: AppContext) -> None:
    await teams.teams.create_task(
        team_id=TEAM, assignee_id=DEV, title="down", brief="b", created_by=DIRECTOR
    )
    await teams.teams.create_task(
        team_id=TEAM, assignee_id=DIRECTOR, title="up", brief="b", created_by=DEV
    )


async def test_a_declared_sideways_route_is_allowed(teams: AppContext) -> None:
    await teams.teams.create_task(
        team_id=TEAM, assignee_id=DEV, title="sideways", brief="b", created_by=QA
    )


async def test_an_undeclared_peer_is_refused(teams: AppContext) -> None:
    with pytest.raises(RoutingError):
        await teams.teams.create_task(
            team_id=TEAM, assignee_id=QA, title="no route", brief="b", created_by=DEV
        )


async def test_an_unknown_assignee_is_a_lookup_failure_not_a_database_error(
    teams: AppContext,
) -> None:
    """The routing guard short-circuits for the user, so existence is checked separately."""
    with pytest.raises(KeyError):
        await teams.teams.create_task(
            team_id=TEAM, assignee_id="ghost", title="t", brief="b", created_by=None
        )
    with pytest.raises(KeyError):
        await teams.teams.create_task(
            team_id="ghost_team", assignee_id=DEV, title="t", brief="b", created_by=None
        )


async def test_only_the_assignees_manager_reviews(teams: AppContext) -> None:
    task = await teams.teams.create_task(team_id=TEAM, assignee_id=DEV, title="t", brief="b")
    await teams.teams.submit_for_review(task.id)

    with pytest.raises(RoutingError):
        await teams.teams.approve(task.id, actor_id=DESIGNER)
    with pytest.raises(RoutingError):  # not your own work
        await teams.teams.approve(task.id, actor_id=DEV)

    approved = await teams.teams.approve(task.id, actor_id=DIRECTOR)
    assert approved.status == TaskStatus.DONE


# ------------------------------------------------------------------ transcript


async def test_only_the_assignee_or_the_user_may_write_to_the_transcript(
    teams: AppContext,
) -> None:
    """It is replayed to the agent on resume, so a forged author is an injection point."""
    task = await teams.teams.create_task(team_id=TEAM, assignee_id=DEV, title="t", brief="b")

    await teams.teams.add_note(task.id, "from the user")
    await teams.teams.add_note(task.id, "from the assignee", author_id=DEV)

    with pytest.raises(RoutingError):
        await teams.teams.add_note(task.id, "not mine", author_id=QA)
    with pytest.raises(KeyError):
        await teams.teams.add_note(task.id, "who?", author_id="ghost_member")
    with pytest.raises(ValueError):
        await teams.teams.add_note(task.id, "   ", author_id=DEV)

    # Scoped to notes: the same transcript also carries the task's own history (the
    # `status` entry it was created with), which nobody authored.
    async with teams.db.session() as session:
        rows = (
            await session.exec(
                select(AgentMessage)
                .where(AgentMessage.task_id == task.id)
                .where(AgentMessage.kind == "note")
            )
        ).all()
    assert [row.role for row in rows] == ["user", "human"]


# ------------------------------------------------------------------ delegation bounds


async def test_delegation_depth_is_capped(teams: AppContext) -> None:
    """Every hop is a billed session, so the ceiling cannot rely on a model stopping."""
    teams.config.teams.max_delegation_depth = 2

    root = await teams.teams.create_task(
        team_id=TEAM, assignee_id=DEV, title="root", brief="b", auto_start=False
    )
    first = await teams.teams.create_task(
        team_id=TEAM, assignee_id=DIRECTOR, title="1", brief="b",
        created_by=DEV, auto_start=False, parent_task=root,
    )
    second = await teams.teams.create_task(
        team_id=TEAM, assignee_id=DEV, title="2", brief="b",
        created_by=DIRECTOR, auto_start=False, parent_task=first,
    )
    assert (root.delegation_depth, first.delegation_depth, second.delegation_depth) == (0, 1, 2)

    with pytest.raises(DelegationLimitError):
        await teams.teams.create_task(
            team_id=TEAM, assignee_id=DIRECTOR, title="3", brief="b",
            created_by=DEV, auto_start=False, parent_task=second,
        )


async def test_a_zero_ceiling_forbids_agent_created_tasks_entirely(teams: AppContext) -> None:
    teams.config.teams.max_delegation_depth = 0
    root = await teams.teams.create_task(
        team_id=TEAM, assignee_id=DEV, title="root", brief="b", auto_start=False
    )
    with pytest.raises(DelegationLimitError):
        await teams.teams.create_task(
            team_id=TEAM, assignee_id=DIRECTOR, title="x", brief="b",
            created_by=DEV, auto_start=False, parent_task=root,
        )


# ------------------------------------------------------------------ the todo column (§10.4)


async def test_a_new_task_lands_in_todo_and_starts_nothing(teams: AppContext) -> None:
    """`auto_start` describes the release, not the creation — nothing runs off the board."""
    task = await teams.teams.create_task(
        team_id=TEAM, assignee_id=DEV, title="t", brief="b", auto_start=True
    )

    assert task.status == TaskStatus.TODO
    assert task.auto_start is True, "kept, and acted on only once someone releases it"
    assert not teams.jobs.is_running(task.id)
    with pytest.raises(ValueError, match="only from Pending"):
        await teams.teams.start_task(task.id)


async def test_an_agent_created_task_lands_in_todo_too(teams: AppContext) -> None:
    """The MCP tool is the route that most needs this: an agent cannot release its own work."""
    async with teams.db.session() as session:
        member = await session.get(TeamMember, DEV)
        parent = await session.get(TeamTask, (
            await teams.teams.create_task(team_id=TEAM, assignee_id=DEV, title="t", brief="b")
        ).id)

    toolset = build_toolset(teams.teams, member=member, task=parent)
    if toolset is None:
        pytest.skip("claude-agent-sdk is not installed")
    definition = next(d for d in toolset.definitions if d.name == "create_task")
    result = await definition.handler(
        {"assignee_id": DIRECTOR, "title": "escalated", "brief": "b"}
    )
    assert not result.get("is_error")

    async with teams.db.session() as session:
        created = (
            await session.exec(select(TeamTask).where(TeamTask.title == "escalated"))
        ).one()
    assert created.status == TaskStatus.TODO
    assert not teams.jobs.is_running(created.id)


async def test_releasing_a_task_to_pending_is_what_runs_it(
    teams: AppContext, wait_for
) -> None:
    task = await teams.teams.create_task(
        team_id=TEAM, assignee_id=DEV, title="t", brief="b", auto_start=True
    )
    released = await release(teams, task.id)
    assert released.status in (TaskStatus.PENDING, TaskStatus.IN_PROGRESS)

    await wait_for(task.id, timeout=20)
    assert (await load(teams, task.id)).status == TaskStatus.NEEDS_REVIEW


async def test_a_released_task_without_auto_start_waits_for_an_explicit_start(
    teams: AppContext, wait_for
) -> None:
    """Two ways out of Todo, and this is the one that still asks for a click."""
    task = await teams.teams.create_task(
        team_id=TEAM, assignee_id=DEV, title="t", brief="b", auto_start=False
    )
    await release(teams, task.id)

    assert (await load(teams, task.id)).status == TaskStatus.PENDING
    assert not teams.jobs.is_running(task.id)

    await teams.teams.start_task(task.id)
    await wait_for(task.id, timeout=20)
    assert (await load(teams, task.id)).status == TaskStatus.NEEDS_REVIEW


async def test_a_dependent_left_in_todo_is_not_woken_by_its_dependency(
    teams: AppContext,
) -> None:
    """A task in Todo waits on a person, and approving its dependency does not answer that."""
    blocker = await teams.teams.create_task(
        team_id=TEAM, assignee_id=DESIGNER, title="design", brief="b", auto_start=False
    )
    waiting = await teams.teams.create_task(
        team_id=TEAM, assignee_id=DEV, title="build", brief="b",
        depends_on=[blocker.id], auto_start=True,
    )

    await teams.teams.submit_for_review(blocker.id)
    await teams.teams.approve(blocker.id, actor_id=DIRECTOR)

    assert (await load(teams, waiting.id)).status == TaskStatus.TODO
    assert not teams.jobs.is_running(waiting.id)


async def test_requesting_changes_takes_an_agent_task_back_through_pending(
    teams: AppContext, wait_for
) -> None:
    """The review loop obeys the same rule: a session starts from Pending and nowhere else."""
    task = await teams.teams.create_task(
        team_id=TEAM, assignee_id=DEV, title="t", brief="b", auto_start=True
    )
    await release(teams, task.id)
    await wait_for(task.id, timeout=20)

    await teams.teams.request_changes(task.id, "try again", actor_id=DIRECTOR)
    await wait_for(task.id, timeout=20)

    trail = [entry[2] for entry in await history(teams, task.id)]
    assert trail == [
        "todo",
        "pending",
        "in_progress",
        "needs_review",
        "pending",
        "in_progress",
        "needs_review",
    ]


# ------------------------------------------------------------------ agent execution


async def test_an_agent_task_runs_and_lands_in_the_review_queue(
    teams: AppContext, wait_for
) -> None:
    task = await teams.teams.create_task(
        team_id=TEAM, assignee_id=DEV, title="t", brief="do the thing", auto_start=True
    )
    await release(teams, task.id)
    await wait_for(task.id, timeout=20)

    finished = await load(teams, task.id)
    assert finished.status == TaskStatus.NEEDS_REVIEW
    assert finished.agent_session_id == "sess_1", "captured so the session can be resumed"
    assert finished.num_turns == 2 and finished.total_cost_usd == pytest.approx(0.02)
    assert finished.total_tokens == 1500, "summed onto the task like the turns and the cost"


async def _record_prompts(teams: AppContext, name: str) -> list[str]:
    """Point DEV at a backend that only remembers the message each run opened with."""
    seen: list[str] = []

    class RecordingPrompt:
        def is_available(self):
            return True, "recording"

        async def run(self, request):
            seen.append(request.prompt)
            yield AgentEvent(kind="result", text="Done.", session_id="sess_1", is_final=True)

    RecordingPrompt.name = name
    register_backend(name, RecordingPrompt)
    async with teams.db.session() as session:
        member = await session.get(TeamMember, DEV)
        member.agent_backend = name
        session.add(member)
    return seen


async def test_a_resume_asks_the_session_to_continue_rather_than_resending_the_brief(
    teams: AppContext, wait_for
) -> None:
    prompts = await _record_prompts(teams, "recording_prompt_resume")
    task = await teams.teams.create_task(
        team_id=TEAM, assignee_id=DEV, title="t", brief="do the thing", auto_start=True
    )
    await release(teams, task.id)
    await wait_for(task.id, timeout=20)
    assert len(prompts) == 1
    assert prompts[0].startswith("Required startup skills\n")
    assert prompts[0].endswith("\n\ndo the thing"), "a first run is the only place the brief is sent"

    # The finished run left the task in `needs_review`, and a session starts from `pending`
    # alone — so a resume is released exactly like a first run.
    await release(teams, task.id)
    await wait_for(task.id, timeout=20)

    assert len(prompts) == 2
    assert "do the thing" not in prompts[1], "the resumed session already holds the brief"
    assert "Continue." in prompts[1]


async def test_starting_a_finished_task_clears_the_previous_finish_time(teams: AppContext) -> None:
    task = await teams.teams.create_task(team_id=TEAM, assignee_id=DEV, title="t", brief="b")
    await teams.teams._set_task_status(task.id, TaskStatus.NEEDS_REVIEW, finished=True)
    finished = await load(teams, task.id)
    assert finished.finished_at is not None

    await teams.teams._set_task_status(task.id, TaskStatus.IN_PROGRESS, started=True)
    running = await load(teams, task.id)
    assert running.started_at >= finished.finished_at
    assert running.finished_at is None, "otherwise a resumed run's timer clamps to zero"


async def test_a_resume_carries_the_notes_written_since_the_last_run(
    teams: AppContext, wait_for
) -> None:
    prompts = await _record_prompts(teams, "recording_prompt_notes")
    task = await teams.teams.create_task(
        team_id=TEAM, assignee_id=DEV, title="t", brief="do the thing", auto_start=True
    )
    await release(teams, task.id)
    await wait_for(task.id, timeout=20)

    await teams.teams.add_note(task.id, "use the new API")
    await release(teams, task.id)
    await wait_for(task.id, timeout=20)
    assert "use the new API" in prompts[1], "the one thing the session has not seen"

    await release(teams, task.id)
    await wait_for(task.id, timeout=20)
    assert "use the new API" not in prompts[2], "delivered once, not on every later resume"


async def test_a_resume_speaks_the_configured_words(teams: AppContext, wait_for) -> None:
    """Every line the Studio says to an agent is a `teams.prompts` template (§10.3) —
    here the plain-Resume message; the system prompt has a test of its own below."""
    teams.config.teams.prompts.resume = "Carry on, quietly."
    prompts = await _record_prompts(teams, "recording_prompt_configured_resume")
    task = await teams.teams.create_task(
        team_id=TEAM, assignee_id=DEV, title="t", brief="do the thing", auto_start=True
    )
    await release(teams, task.id)
    await wait_for(task.id, timeout=20)
    await release(teams, task.id)
    await wait_for(task.id, timeout=20)

    assert prompts[1] == "Carry on, quietly."


async def test_review_feedback_is_delivered_through_its_template(
    teams: AppContext, wait_for
) -> None:
    teams.config.teams.prompts.review_changes = "REDO: {comment}"
    prompts = await _record_prompts(teams, "recording_review_template")
    task = await teams.teams.create_task(
        team_id=TEAM, assignee_id=DEV, title="t", brief="b", auto_start=True
    )
    await release(teams, task.id)
    await wait_for(task.id, timeout=20)

    await teams.teams.request_changes(task.id, "tighten it", actor_id=DIRECTOR)
    await wait_for(task.id, timeout=20)

    assert prompts[1] == "REDO: tighten it"


async def test_a_dependent_task_is_released_when_its_dependency_is_approved(
    teams: AppContext, wait_for
) -> None:
    first = await teams.teams.create_task(
        team_id=TEAM, assignee_id=DESIGNER, title="design", brief="b", auto_start=False
    )
    second = await teams.teams.create_task(
        team_id=TEAM, assignee_id=DEV, title="build", brief="b",
        depends_on=[first.id], auto_start=True,
    )
    # Released, so it is waiting on the dependency rather than on a person — which is what
    # makes the approval below the thing that starts it.
    await release(teams, second.id)
    assert (await load(teams, second.id)).status == TaskStatus.PENDING, "blocked on its dependency"

    with pytest.raises(ValueError, match="blocked by unfinished dependencies"):
        await teams.teams.start_task(second.id)

    await teams.teams.submit_for_review(first.id)
    await teams.teams.approve(first.id, actor_id=DIRECTOR)
    await wait_for(second.id, timeout=20)

    assert (await load(teams, second.id)).status == TaskStatus.NEEDS_REVIEW


# ------------------------------------------------------------------ human members


# ------------------------------------------------------------------ solo mode (§10.4)


async def _hold_dev_runs(
    teams: AppContext, name: str
) -> tuple[dict[str, asyncio.Event], list[str]]:
    """Point DEV at a backend whose runs wait to be let go, one gate per task.

    The stub backend finishes in a tick, which is no use for a rule about what happens
    *while* a run is going. Every run here opens, remembers its prompt, and then holds on
    ``gates[task_id]`` until the test sets it — so the test decides which run ends first.
    """
    gates: dict[str, asyncio.Event] = {}
    prompts: list[str] = []

    class Held:
        def is_available(self):
            return True, "held"

        async def run(self, request):
            prompts.append(request.prompt)
            gate = gates.setdefault(request.task.id, asyncio.Event())
            yield AgentEvent(kind="text", text="working", session_id=f"sess_{request.task.id}")
            await gate.wait()
            yield AgentEvent(
                kind="result",
                text="Done.",
                session_id=f"sess_{request.task.id}",
                data={"is_error": False, "num_turns": 1, "total_cost_usd": 0.01},
                is_final=True,
            )

    Held.name = name
    register_backend(name, Held)
    async with teams.db.session() as session:
        member = await session.get(TeamMember, DEV)
        member.agent_backend = name
        session.add(member)
    return gates, prompts


async def _wait_until(condition, *, timeout: float = 10.0) -> None:
    """Poll a coroutine predicate; a start is scheduled, never immediate."""
    loop = asyncio.get_running_loop()
    deadline = loop.time() + timeout
    while not await condition():
        assert loop.time() < deadline, "the condition was not met in time"
        await asyncio.sleep(0.02)


async def _running(teams: AppContext, task_id: str) -> bool:
    """A live job *and* the row saying so — the two halves a reader of the board sees."""
    return (
        teams.jobs.is_running(task_id)
        and (await load(teams, task_id)).status == TaskStatus.IN_PROGRESS
    )


def _let_go(gates: dict[str, asyncio.Event], task_id: str) -> None:
    """Release a held run — whether or not the backend has reached its gate yet."""
    gates.setdefault(task_id, asyncio.Event()).set()


async def _solo_on(teams: AppContext, member_id: str = DEV) -> None:
    """Switch Solo Mode on for one member; every member starts with it off (§10.4)."""
    async with teams.db.session() as session:
        member = await session.get(TeamMember, member_id)
        member.solo_mode = True
        session.add(member)


async def _dev_task(
    teams: AppContext, title: str, *, auto_start: bool = True,
    write_paths: list[str] | tuple[str, ...] = (),
) -> TeamTask:
    return await teams.teams.create_task(
        team_id=TEAM, assignee_id=DEV, title=title, brief="b", auto_start=auto_start,
        write_paths=write_paths,
    )


async def test_solo_mode_and_isolation_are_off_by_default_and_round_trip_through_the_team_file(
    teams: AppContext,
) -> None:
    """A roster that never names either flag runs tasks side by side in the project itself,
    and both flags are written back like every other member setting."""
    path = teams.paths.teams / "default-team.yaml"
    text = path.read_text(encoding="utf-8")
    assert "solo_mode" not in text and "isolate_worktree" not in text, "the sample names neither"
    async with teams.db.session() as session:
        dev = await session.get(TeamMember, DEV)
        assert dev.solo_mode is False
        assert dev.isolate_worktree is False
        dev.solo_mode = True
        dev.isolate_worktree = True
        session.add(dev)
    async with teams.db.session() as session:
        await teams.teams.store.save_from_db(session, TEAM)
    text = path.read_text(encoding="utf-8")
    assert "solo_mode: true" in text and "isolate_worktree: true" in text

    await teams.teams.sync_definitions()
    async with teams.db.session() as session:
        dev = await session.get(TeamMember, DEV)
        assert dev.solo_mode is True and dev.isolate_worktree is True, "the file is the truth"


def test_a_new_member_starts_with_solo_mode_and_isolation_off(project: Path, client) -> None:
    created = client.post(f"/api/teams/{TEAM}/members", json={"name": "Newcomer"})
    assert created.status_code == 201
    assert created.json()["solo_mode"] is False
    assert created.json()["isolate_worktree"] is False


def test_solo_mode_is_editable_over_rest(project: Path, client) -> None:
    roster = project / ".voxarium_studio" / "teams" / "default-team.yaml"
    saved = client.patch(f"/api/teams/{TEAM}/members/{DEV}", json={"solo_mode": True})
    assert saved.status_code == 200 and saved.json()["solo_mode"] is True
    assert "solo_mode: true" in roster.read_text(encoding="utf-8")
    members = {m["id"]: m for m in client.get(f"/api/teams/{TEAM}").json()["members"]}
    assert members[DEV]["solo_mode"] is True
    assert members[DESIGNER]["solo_mode"] is False, "everyone else is untouched"


async def test_a_solo_member_runs_one_released_task_at_a_time(
    teams: AppContext, wait_for
) -> None:
    """The rule itself: a second released task waits in `pending` until the first run
    ends, and then goes without anyone clicking — whoever asks for it in between."""
    gates, _prompts = await _hold_dev_runs(teams, "held_solo")
    await _solo_on(teams)
    first = await _dev_task(teams, "first")
    second = await _dev_task(teams, "second")

    await release(teams, first.id)
    await _wait_until(lambda: _running(teams, first.id))

    await release(teams, second.id)
    await asyncio.sleep(0.1)  # room for a wrongful start to show itself
    parked = await load(teams, second.id)
    assert parked.status == TaskStatus.PENDING and not teams.jobs.is_running(second.id)

    with pytest.raises(ValueError, match="one task at a time") as refused:
        await teams.teams.start_task(second.id)
    assert "starts on its own" in str(refused.value), "the card looks the same either way"

    _let_go(gates, first.id)
    await wait_for(first.id, timeout=20)
    assert (await load(teams, first.id)).status == TaskStatus.NEEDS_REVIEW

    await _wait_until(lambda: _running(teams, second.id))
    _let_go(gates, second.id)
    await wait_for(second.id, timeout=20)
    assert (await load(teams, second.id)).status == TaskStatus.NEEDS_REVIEW


async def test_the_queue_goes_lowest_number_first(teams: AppContext, wait_for) -> None:
    """Three released at once, ended one by one: the order is the task number's, not the
    order the cards were dragged in."""
    gates, _prompts = await _hold_dev_runs(teams, "held_order")
    await _solo_on(teams)
    first = await _dev_task(teams, "a")
    second = await _dev_task(teams, "b")
    third = await _dev_task(teams, "c")

    await release(teams, first.id)
    await _wait_until(lambda: _running(teams, first.id))
    await release(teams, third.id)
    await release(teams, second.id)

    _let_go(gates, first.id)
    await wait_for(first.id, timeout=20)
    await _wait_until(lambda: _running(teams, second.id))
    assert (await load(teams, third.id)).status == TaskStatus.PENDING
    assert not teams.jobs.is_running(third.id)

    _let_go(gates, second.id)
    await wait_for(second.id, timeout=20)
    await _wait_until(lambda: _running(teams, third.id))
    _let_go(gates, third.id)
    await wait_for(third.id, timeout=20)


async def test_a_task_released_without_auto_start_is_not_in_the_queue(
    teams: AppContext, wait_for
) -> None:
    gates, _prompts = await _hold_dev_runs(teams, "held_manual")
    await _solo_on(teams)
    first = await _dev_task(teams, "first")
    manual = await _dev_task(teams, "manual", auto_start=False)

    await release(teams, first.id)
    await _wait_until(lambda: _running(teams, first.id))
    await release(teams, manual.id)
    with pytest.raises(ValueError, match="Start it again") :
        await teams.teams.start_task(manual.id)

    _let_go(gates, first.id)
    await wait_for(first.id, timeout=20)
    await asyncio.sleep(0.1)
    assert (await load(teams, manual.id)).status == TaskStatus.PENDING
    assert not teams.jobs.is_running(manual.id), "it waits for a Start, as it always did"

    await teams.teams.start_task(manual.id)
    await _wait_until(lambda: _running(teams, manual.id))
    _let_go(gates, manual.id)
    await wait_for(manual.id, timeout=20)


async def test_with_solo_mode_off_released_tasks_run_side_by_side(
    teams: AppContext, wait_for
) -> None:
    gates, _prompts = await _hold_dev_runs(teams, "held_parallel")
    async with teams.db.session() as session:
        dev = await session.get(TeamMember, DEV)
        dev.solo_mode = False
        session.add(dev)
    first = await _dev_task(teams, "first")
    second = await _dev_task(teams, "second")

    await release(teams, first.id)
    await release(teams, second.id)
    await _wait_until(lambda: _running(teams, first.id))
    await _wait_until(lambda: _running(teams, second.id))

    _let_go(gates, first.id)
    _let_go(gates, second.id)
    await wait_for(first.id, timeout=20)
    await wait_for(second.id, timeout=20)


async def _queued_entries(teams: AppContext, task_id: str) -> list[AgentMessage]:
    async with teams.db.session() as session:
        return list(
            (
                await session.exec(
                    select(AgentMessage)
                    .where(AgentMessage.task_id == task_id, AgentMessage.kind == "queued")
                    .order_by(AgentMessage.created_at)
                )
            ).all()
        )


async def test_a_run_queued_behind_the_agent_limit_says_so_and_then_starts(
    teams: AppContext, wait_for
) -> None:
    """Past `concurrency.agents` a released task reads In progress with no session behind
    it, and its last line was the workdir note — a hang, to anyone looking. It says why,
    and says again when the slot frees (§10.3)."""
    gates, _prompts = await _hold_dev_runs(teams, "held_slots")
    async with teams.db.session() as session:
        dev = await session.get(TeamMember, DEV)
        dev.solo_mode = False
        session.add(dev)
    assert teams.jobs.limits["agent"] == 2, "the default this test is about"
    tasks = [await _dev_task(teams, name) for name in ("a", "b", "c")]
    for task in tasks:
        await release(teams, task.id)

    # A gate exists once its run has reached the backend, i.e. holds a slot.
    async def two_sessions() -> bool:
        return len(gates) == 2

    await _wait_until(two_sessions)
    queued = next(task for task in tasks if task.id not in gates)

    async def says_why() -> bool:
        return bool(await _queued_entries(teams, queued.id))

    await _wait_until(says_why)
    [waiting] = await _queued_entries(teams, queued.id)
    assert "2 of 2 in use" in waiting.text and "Parallel sessions" in waiting.text
    assert waiting.data == {"resource": "agent", "limit": 2}
    assert await _running(teams, queued.id), "In progress, as the board shows it"
    await asyncio.sleep(0.1)
    assert queued.id not in gates, "no session while every slot is taken"
    for held in (task for task in tasks if task.id in gates):
        assert not await _queued_entries(teams, held.id), "a run that never waited says nothing"

    first = next(task for task in tasks if task.id in gates)
    _let_go(gates, first.id)
    await wait_for(first.id, timeout=20)

    async def third_session() -> bool:
        return queued.id in gates

    await _wait_until(third_session)
    waiting, started = await _queued_entries(teams, queued.id)
    assert started.text.startswith("An agent slot is free after ")
    assert started.data["resource"] == "agent" and started.data["waited_s"] >= 0

    for task in tasks:
        _let_go(gates, task.id)
    for task in tasks:
        await wait_for(task.id, timeout=20)


async def test_path_wait_names_blocker_and_releases_without_taking_an_agent_slot(
    teams: AppContext, wait_for
) -> None:
    gates, _ = await _hold_dev_runs(teams, "held_paths")
    teams.config.teams.launch_root = ".."
    async with teams.db.session() as session:
        dev = await session.get(TeamMember, DEV)
        dev.solo_mode = False
        session.add(dev)
    # The write declaration alone must create the conflict; the brief names no files.
    first = await _dev_task(teams, "First writer", write_paths=["src/shared.py"])
    second = await _dev_task(teams, "Second writer", write_paths=["src/shared.py"])
    third = await _dev_task(teams, "Independent writer", write_paths=["src/other.py"])

    async def started(task):
        return task.id in gates

    await release(teams, first.id)
    await _wait_until(lambda: started(first))
    await release(teams, second.id)

    async def says_why():
        return bool(await _queued_entries(teams, second.id))

    await _wait_until(says_why)
    [waiting] = await _queued_entries(teams, second.id)
    assert waiting.data == {
        "resource": "path", "path": "src/shared.py",
        "blocking_task": teams.teams.display_id(first),
    }
    assert teams.teams.display_id(first) in waiting.text
    assert "src/shared.py" in waiting.text
    assert second.id not in gates
    assert (await load(teams, second.id)).agent_session_id is None
    with pytest.raises(ValueError):
        await teams.teams.update_task(second.id, {"write_paths": []})
    assert (await load(teams, second.id)).write_paths == ["src/shared.py"]
    assert not await _queued_entries(teams, first.id)

    await release(teams, third.id)
    await _wait_until(lambda: started(third))
    assert not await _queued_entries(teams, third.id), "path waiting uses no agent slot"
    _let_go(gates, first.id)
    await wait_for(first.id, timeout=20)
    await _wait_until(lambda: started(second))
    _, resumed = await _queued_entries(teams, second.id)
    assert resumed.data["resource"] == "path" and resumed.data["waited_s"] >= 0
    for task in (second, third):
        _let_go(gates, task.id)
        await wait_for(task.id, timeout=20)


@pytest.mark.parametrize("write_sets", [
    ([], []),
    (["src/a.py"], ["src/b.py"]),
    (["src/shared.py"], []),
], ids=["two-readers", "shared-context-separate-writes", "reader-during-write"])
async def test_read_references_never_queue_a_task(
    teams: AppContext, wait_for, write_sets: tuple[list[str], list[str]],
) -> None:
    gates, _ = await _hold_dev_runs(teams, "held_read_references")
    # Declarations stay project-relative even if sessions start outside the project.
    teams.config.teams.launch_root = ".."
    async with teams.db.session() as session:
        dev = await session.get(TeamMember, DEV)
        dev.solo_mode = False
        session.add(dev)
    tasks = [
        await teams.teams.create_task(
            team_id=TEAM, assignee_id=DEV,
            title=f"Read docs/design.md for task {index}",
            brief="Read docs/design.md and src/shared.py for context only; do not edit them.",
            write_paths=paths, auto_start=True,
        )
        for index, paths in enumerate(write_sets)
    ]
    for task in tasks:
        await release(teams, task.id)

    async def both_started():
        return len(gates) == 2

    await _wait_until(both_started)
    for task in tasks:
        assert not await _queued_entries(teams, task.id)
        _let_go(gates, task.id)
    for task in tasks:
        await wait_for(task.id, timeout=20)


async def test_write_paths_can_be_edited_and_cleared_before_a_run(teams: AppContext) -> None:
    task = await _dev_task(teams, "Read docs/design.md")
    assert task.write_paths == [], "omitted declarations must never be inferred from text"
    updated = await teams.teams.update_task(task.id, {"write_paths": ["src/a.py", "Makefile"]})
    assert updated.write_paths == ["src/a.py", "Makefile"]
    assert (await load(teams, task.id)).write_paths == updated.write_paths
    cleared = await teams.teams.update_task(task.id, {"write_paths": []})
    assert cleared.write_paths == []
    assert (await load(teams, task.id)).write_paths == []


async def test_write_paths_cannot_change_during_a_run(teams: AppContext, wait_for) -> None:
    gates, _ = await _hold_dev_runs(teams, "held_write_declaration")
    task = await _dev_task(teams, "Writer", write_paths=["src/a.py"])
    await release(teams, task.id)

    async def started():
        return task.id in gates

    await _wait_until(started)
    for changed in ([], ["src/b.py"]):
        with pytest.raises(ValueError):
            await teams.teams.update_task(task.id, {"write_paths": changed})
        assert (await load(teams, task.id)).write_paths == ["src/a.py"]
    # Saving unchanged task details is harmless while the acquired set stays the same.
    same = await teams.teams.update_task(task.id, {"write_paths": ["src/a.py"]})
    assert same.write_paths == ["src/a.py"]
    _let_go(gates, task.id)
    await wait_for(task.id, timeout=20)
    changed = await teams.teams.update_task(task.id, {"write_paths": ["src/b.py"]})
    assert changed.write_paths == ["src/b.py"]


async def test_executor_rescopes_its_running_task_unless_paths_are_held(
    teams: AppContext, wait_for,
) -> None:
    gates, _ = await _hold_dev_runs(teams, "held_rescope")
    async with teams.db.session() as session:
        dev = await session.get(TeamMember, DEV)
        dev.solo_mode = False
        session.add(dev)
    mine = await _dev_task(teams, "Writer", write_paths=["src/a.py"])
    other = await _dev_task(teams, "Artist", write_paths=["assets/*"])
    late = await _dev_task(teams, "Late writer", write_paths=["src/gen/out.py"])

    async def started(task):
        return task.id in gates

    for task in (mine, other):
        await release(teams, task.id)
        await _wait_until(lambda task=task: started(task))

    # Inside another running task's directory: refused, and nothing changes.
    with pytest.raises(ValueError, match=teams.teams.display_id(other)):
        await teams.teams.update_task(
            mine.id, {"write_paths": ["src/a.py", "assets/hero.vox"]}, actor_id=DEV,
        )
    assert (await load(teams, mine.id)).write_paths == ["src/a.py"]

    # Free paths, a directory among them: taken at once, mid-run.
    updated = await teams.teams.update_task(
        mine.id, {"write_paths": ["src/a.py", "src/gen/*"]}, actor_id=DEV,
    )
    assert updated.write_paths == ["src/a.py", "src/gen/*"]
    await release(teams, late.id)

    async def queued():
        return bool(await _queued_entries(teams, late.id))

    await _wait_until(queued)
    [waiting] = await _queued_entries(teams, late.id)
    assert waiting.data["blocking_task"] == teams.teams.display_id(mine)
    # Not yet in a session, so even its own assignee cannot move a queued task's scope.
    with pytest.raises(ValueError):
        await teams.teams.update_task(late.id, {"write_paths": []}, actor_id=DEV)

    # Giving the directory back lets the queued task in (once an agent slot is free).
    _let_go(gates, other.id)
    await wait_for(other.id, timeout=20)
    assert late.id not in gates
    await teams.teams.update_task(mine.id, {"write_paths": ["src/a.py"]}, actor_id=DEV)
    await _wait_until(lambda: started(late))
    for task in (mine, late):
        _let_go(gates, task.id)
        await wait_for(task.id, timeout=20)
    assert not teams.teams._path_locks._held and not teams.teams._run_writes


async def test_old_tasks_gain_empty_write_paths_without_inferring_brief(teams: AppContext) -> None:
    from sqlalchemy import text

    from voxarium_studio.core.db import Database

    task = await _dev_task(teams, "Read src/shared.py")
    async with teams.db._engine.begin() as conn:
        await conn.execute(text("ALTER TABLE team_task DROP COLUMN write_paths"))
    reopened = Database(teams.db.path)
    try:
        await reopened.create_all()
        await reopened.create_all()  # The additive migration is safe on later startups too.
        async with reopened.session() as session:
            saved = await session.get(TeamTask, task.id)
            assert saved.write_paths == []
            assert saved.title == task.title and saved.status == TaskStatus.TODO
    finally:
        await reopened.close()


async def test_common_brief_prose_does_not_queue_unrelated_runs(
    teams: AppContext, wait_for
) -> None:
    """BT-543 waited for BT-546 on dates and prohibition text, before any session existed."""
    gates, _ = await _hold_dev_runs(teams, "held_prose_paths")
    teams.config.teams.launch_root = ".."
    async with teams.db.session() as session:
        dev = await session.get(TeamMember, DEV)
        dev.solo_mode = False
        session.add(dev)
    tasks = [
        await teams.teams.create_task(
            team_id=TEAM, assignee_id=DEV, title=title,
            brief=("Решение 03.11.2026. BT-561/563 и spawn/destroy запрещены. "
                   f"Работа: {path}."),
            auto_start=True,
        )
        for title, path in (("Clothing", "src/clothing.as"), ("Art", "art/clothing.md"))
    ]
    for task in tasks:
        await release(teams, task.id)

    async def both_started():
        return len(gates) == 2

    await _wait_until(both_started)
    for task in tasks:
        assert not await _queued_entries(teams, task.id)
        _let_go(gates, task.id)
    for task in tasks:
        await wait_for(task.id, timeout=20)


async def test_raising_the_agent_limit_starts_a_queued_run_at_once(
    teams: AppContext, wait_for
) -> None:
    """The Settings field's promise: the waiting run goes on the save, not on a restart.
    `update_limits` is what `PUT /api/settings` calls with a concurrency patch."""
    gates, _prompts = await _hold_dev_runs(teams, "held_raise")
    async with teams.db.session() as session:
        dev = await session.get(TeamMember, DEV)
        dev.solo_mode = False
        session.add(dev)
    tasks = [await _dev_task(teams, name) for name in ("a", "b", "c")]
    for task in tasks:
        await release(teams, task.id)
    queued = None

    async def one_waits() -> bool:
        nonlocal queued
        for task in tasks:
            if await _queued_entries(teams, task.id):
                queued = task
                return True
        return False

    await _wait_until(one_waits)
    assert queued is not None and queued.id not in gates

    teams.jobs.update_limits({"agent": 3})

    async def third_session() -> bool:
        return queued.id in gates

    await _wait_until(third_session)
    assert all(task.id in gates for task in tasks)
    for task in tasks:
        _let_go(gates, task.id)
    for task in tasks:
        await wait_for(task.id, timeout=20)


async def test_switching_solo_mode_off_releases_what_was_parked_behind_it(
    teams: AppContext, wait_for
) -> None:
    """The flag is the one member setting that can be holding work, so clearing it is a
    release — `PATCH …/members/{id}` calls the same dispatch."""
    gates, _prompts = await _hold_dev_runs(teams, "held_flip")
    await _solo_on(teams)
    first = await _dev_task(teams, "first")
    second = await _dev_task(teams, "second")
    await release(teams, first.id)
    await _wait_until(lambda: _running(teams, first.id))
    await release(teams, second.id)
    await asyncio.sleep(0.05)
    assert not teams.jobs.is_running(second.id)

    async with teams.db.session() as session:
        dev = await session.get(TeamMember, DEV)
        dev.solo_mode = False
        session.add(dev)
    await teams.teams.dispatch_member_queue(DEV)
    await _wait_until(lambda: _running(teams, second.id))

    _let_go(gates, first.id)
    _let_go(gates, second.id)
    await wait_for(first.id, timeout=20)
    await wait_for(second.id, timeout=20)


async def test_a_cancelled_run_frees_the_queue_without_restarting_itself(
    teams: AppContext, wait_for
) -> None:
    gates, _prompts = await _hold_dev_runs(teams, "held_cancel")
    first = await _dev_task(teams, "first")
    second = await _dev_task(teams, "second")
    await release(teams, first.id)
    await _wait_until(lambda: _running(teams, first.id))
    await release(teams, second.id)

    assert await teams.teams.cancel_task(first.id)
    await wait_for(first.id, timeout=20)
    await _wait_until(lambda: _running(teams, second.id))
    cancelled = await load(teams, first.id)
    assert cancelled.status == TaskStatus.PENDING and cancelled.auto_start
    assert not teams.jobs.is_running(first.id), "back in `pending`, not back in the run"

    _let_go(gates, second.id)
    await wait_for(second.id, timeout=20)


async def test_a_review_sent_to_a_busy_solo_member_waits_and_resumes_with_the_comment(
    teams: AppContext, wait_for
) -> None:
    """`request_changes` normally resumes the session with the comment as its opening
    message. When the member is busy elsewhere the task is parked instead — and the
    comment must not be the one thing the later resume forgets."""
    gates, prompts = await _hold_dev_runs(teams, "held_review")
    await _solo_on(teams)
    reviewed = await _dev_task(teams, "reviewed", auto_start=False)
    other = await _dev_task(teams, "other")

    await release(teams, reviewed.id)
    await teams.teams.start_task(reviewed.id)
    _let_go(gates, reviewed.id)
    await wait_for(reviewed.id, timeout=20)
    assert (await load(teams, reviewed.id)).status == TaskStatus.NEEDS_REVIEW

    await release(teams, other.id)
    await _wait_until(lambda: _running(teams, other.id))
    # Job registration precedes the backend receiving its prompt.
    async def received_other_prompt():
        return len(prompts) == 2

    await _wait_until(received_other_prompt)

    returned = await teams.teams.request_changes(
        reviewed.id, "Fix the tests too", actor_id=DIRECTOR
    )
    assert returned.status == TaskStatus.PENDING and not teams.jobs.is_running(reviewed.id)
    assert returned.auto_start is True, "a review always resumes, so the card is queued"
    async with teams.db.session() as session:
        entries = (
            await session.exec(
                select(AgentMessage).where(AgentMessage.task_id == reviewed.id)
            )
        ).all()
    assert any(e.kind == "queued" and "one task at a time" in e.text for e in entries)
    assert any(
        e.kind == "edited" and e.data["fields"].get("auto_start") == {"from": False, "to": True}
        for e in entries
    ), "the flip is on the record, like any other edit"
    assert len(prompts) == 2, "nothing started"

    _let_go(gates, other.id)
    await wait_for(other.id, timeout=20)
    await _wait_until(lambda: _running(teams, reviewed.id))
    _let_go(gates, reviewed.id)
    await wait_for(reviewed.id, timeout=20)

    assert len(prompts) == 3
    resumed = prompts[2]
    assert "Fix the tests too" in resumed, "the review comment leads"
    assert "Continue." in resumed, "a resume, not a fresh brief"
    assert "b" != resumed.split("\n")[0], "the brief is not resent"


def test_the_activity_grid_counts_runs_and_finished_tasks_per_day() -> None:
    """The heatmap's cells: every result entry is a run whatever it cost, a task reaching
    `done` counts on the day it finished, and a residual booked on a quiet day is money
    with no run behind it."""
    from datetime import datetime, timezone

    from voxarium_studio.teams.stats import cost_stats

    def at(day: int, hour: int = 12) -> datetime:
        return datetime(2026, 9, day, hour, tzinfo=timezone.utc)

    def result(task_id: str, when: datetime, cost: float | None) -> AgentMessage:
        return AgentMessage(
            task_id=task_id, member_id=DEV, role="system", kind="result",
            data={"total_cost_usd": cost} if cost is not None else {}, created_at=when,
        )

    finished = TeamTask(
        id="task_done", team_id=TEAM, assignee_id=DEV, title="a", status=TaskStatus.DONE,
        total_cost_usd=0.30, created_at=at(1), started_at=at(1), finished_at=at(3),
    )
    free = TeamTask(
        id="task_free", team_id=TEAM, assignee_id=DEV, title="b", status=TaskStatus.PENDING,
        total_cost_usd=0.0, created_at=at(5),
    )
    messages = [
        result("task_done", at(1, 9), 0.10),
        result("task_done", at(2, 9), 0.15),
        result("task_free", at(5, 9), None),
        AgentMessage(task_id="task_done", member_id=DEV, role="assistant", kind="text",
                     text="not a run", created_at=at(1, 8)),
    ]

    activity = cost_stats([finished, free], messages, [])["activity"]
    assert [
        (d["day"], d["runs"], d["finished"], d["spent_usd"], d["activity"]) for d in activity
    ] == [
        ("2026-09-01", 1, 0, 0.10, 1),
        ("2026-09-02", 1, 0, 0.15, 1),
        ("2026-09-03", 0, 1, 0.05, 1),
        ("2026-09-05", 1, 0, 0.0, 1),
    ]


async def test_a_human_task_waits_in_an_inbox_and_cannot_be_started(teams: AppContext) -> None:
    task = await teams.teams.create_task(
        team_id=TEAM, assignee_id=QA, title="verify", brief="b", auto_start=True
    )
    assert task.auto_start is False, "there is no session to start for a person"

    # Refused before the status is even looked at: `kind` decides this, not the column.
    with pytest.raises(ValueError, match="human team member"):
        await teams.teams.start_task(task.id)
    await release(teams, task.id)
    with pytest.raises(ValueError, match="human team member"):
        await teams.teams.start_task(task.id)


async def test_the_human_lifecycle_is_claim_note_complete(teams: AppContext) -> None:
    task = await teams.teams.create_task(team_id=TEAM, assignee_id=QA, title="verify", brief="b")

    claimed = await teams.teams.claim_task(task.id, actor_id=QA)
    assert claimed.status == TaskStatus.IN_PROGRESS
    with pytest.raises(ValueError, match="cannot be claimed"):
        await teams.teams.claim_task(task.id, actor_id=QA)

    await teams.teams.add_note(task.id, "found a defect", author_id=QA)
    done = await teams.teams.complete_task(
        task.id, "verified", result_type="done", actor_id=QA
    )
    assert done.status == TaskStatus.NEEDS_REVIEW, "QA has a manager to hand it to"

    approved = await teams.teams.approve(task.id, actor_id=DIRECTOR)
    assert approved.status == TaskStatus.DONE


async def test_a_member_with_no_manager_completes_straight_to_done(teams: AppContext) -> None:
    task = await teams.teams.create_task(team_id=TEAM, assignee_id=DIRECTOR, title="t", brief="b")
    await teams.teams.claim_task(task.id, actor_id=DIRECTOR)
    done = await teams.teams.complete_task(
        task.id, "", result_type="done", actor_id=DIRECTOR
    )
    assert done.status == TaskStatus.DONE, "nobody to review it"


async def test_another_member_cannot_act_on_someone_elses_task(teams: AppContext) -> None:
    task = await teams.teams.create_task(team_id=TEAM, assignee_id=QA, title="t", brief="b")
    with pytest.raises(RoutingError):
        await teams.teams.claim_task(task.id, actor_id=DEV)


# ------------------------------------------------------------------ studio tools


async def test_the_studio_toolset_re_checks_routing_on_every_call(teams: AppContext) -> None:
    """Tool arguments are model output and carry no authority of their own."""
    async with teams.db.session() as session:
        member = await session.get(TeamMember, DEV)
        task = await session.get(TeamTask, (
            await teams.teams.create_task(team_id=TEAM, assignee_id=DEV, title="t", brief="b")
        ).id)

    toolset = build_toolset(teams.teams, member=member, task=task)
    if toolset is None:
        pytest.skip("claude-agent-sdk is not installed")

    async def call(name: str, args: dict) -> tuple[bool, str]:
        definition = next(d for d in toolset.definitions if d.name == name)
        result = await definition.handler(args)
        text = "\n".join(
            block.get("text", "") for block in result.get("content", []) if isinstance(block, dict)
        )
        return bool(result.get("is_error")), text

    failed, text = await call("create_task", {"assignee_id": QA, "title": "x", "brief": "y"})
    assert failed and "RoutingError" in text, "dev has no route to qa"

    failed, text = await call("create_task", {"assignee_id": DIRECTOR, "title": "x", "brief": "y"})
    assert not failed, "escalating to its own manager is allowed"


# ------------------------------------------------------------------ sequential task ids


async def test_task_numbers_are_sequential_and_start_at_one(teams: AppContext) -> None:
    first = await teams.teams.create_task(team_id=TEAM, assignee_id=DEV, title="a", brief="")
    second = await teams.teams.create_task(team_id=TEAM, assignee_id=DEV, title="b", brief="")
    third = await teams.teams.create_task(team_id=TEAM, assignee_id=QA, title="c", brief="")

    assert [first.number, second.number, third.number] == [1, 2, 3]
    assert teams.teams.display_id(first) == "GAM-01", "derived from the project folder's name"
    assert teams.teams.display_id(third) == "GAM-03"


async def test_concurrent_creations_never_share_a_number(teams: AppContext) -> None:
    """Two agents delegating in the same instant is the case the allocation lock exists for."""
    created = await asyncio.gather(
        *(
            teams.teams.create_task(team_id=TEAM, assignee_id=DEV, title=f"t{i}", brief="")
            for i in range(12)
        )
    )
    numbers = sorted(task.number for task in created)
    assert numbers == list(range(1, 13))


async def test_deleting_the_newest_task_does_not_free_its_number(teams: AppContext) -> None:
    """The id is already in briefs and commit messages — reuse would point at other work.

    This is exactly what `MAX(number) + 1` gets wrong on its own, which is why there is a
    counter: deleting the highest-numbered task lowers the maximum.
    """
    first = await teams.teams.create_task(team_id=TEAM, assignee_id=DEV, title="a", brief="")
    second = await teams.teams.create_task(team_id=TEAM, assignee_id=DEV, title="b", brief="")
    await teams.teams.delete_task(second.id)

    third = await teams.teams.create_task(team_id=TEAM, assignee_id=DEV, title="c", brief="")
    assert (first.number, third.number) == (1, 3)


async def test_the_number_is_not_editable_by_any_route(teams: AppContext) -> None:
    task = await teams.teams.create_task(team_id=TEAM, assignee_id=DEV, title="a", brief="")
    with pytest.raises(ValueError, match="Not editable"):
        await teams.teams.update_task(task.id, {"number": 99})
    assert (await load(teams, task.id)).number == 1


async def test_a_task_is_found_by_its_display_id_a_bare_number_or_its_row_id(
    teams: AppContext,
) -> None:
    task = await teams.teams.create_task(team_id=TEAM, assignee_id=DEV, title="a", brief="")
    for reference in ("GAM-01", "gam 1", "1", task.id):
        found = await teams.teams.find_task(reference, team_id=TEAM)
        assert found is not None and found.id == task.id, reference
    assert await teams.teams.find_task("GAM-99", team_id=TEAM) is None
    assert await teams.teams.find_task("", team_id=TEAM) is None


async def test_the_project_code_is_a_setting_and_renames_nothing(
    teams: AppContext,
) -> None:
    """The number is the identity; the code is how it reads (§10.2)."""
    task = await teams.teams.create_task(team_id=TEAM, assignee_id=DEV, title="a", brief="")
    teams.config.project_code = "VS"
    assert teams.teams.display_id(task) == "VS-01"
    # …and the old spelling still resolves, because the number is what was written down.
    found = await teams.teams.find_task("GAM-01", team_id=TEAM)
    assert found is not None and found.id == task.id


async def test_padding_is_a_minimum_of_two_digits_not_a_width() -> None:
    from voxarium_studio.models.teams import format_task_id

    assert format_task_id("VS", 1) == "VS-01"
    assert format_task_id("VS", 99) == "VS-99"
    assert format_task_id("VS", 100) == "VS-100", "the number grows rather than being clipped"


async def test_tasks_that_predate_the_column_are_numbered_in_creation_order(
    teams: AppContext,
) -> None:
    """The whole migration path, in the order `AppContext.startup` runs it (§10.2).

    A database opened by an older Studio has the column added with its default — every row
    at 0 — and no index over it, because SQLite cannot add a UNIQUE column and
    `metadata.create_all` builds a table's indexes only when it builds the table. So the
    backfill has to happen *before* the unique index can exist, and this reproduces that
    shape rather than asserting against the tidy one.
    """
    from datetime import timedelta

    from sqlalchemy import text

    from voxarium_studio.models.base import utcnow

    created = []
    for index in range(3):
        created.append(
            await teams.teams.create_task(
                team_id=TEAM, assignee_id=DEV, title=f"t{index}", brief=""
            )
        )

    base = utcnow()
    async with teams.db.session() as session:
        await session.exec(text("DROP INDEX IF EXISTS ix_team_task_number"))
        # Rows in a creation order that is not the insertion order, so the backfill's
        # ordering is actually exercised rather than coinciding with the row ids.
        for offset, task in zip((2, 0, 1), created):
            row = await session.get(TeamTask, task.id)
            row.number = 0
            row.created_at = base + timedelta(minutes=offset)
            session.add(row)
        counter = await session.get(ProjectCounter, "task_number")
        await session.delete(counter)

    assert await teams.teams.ensure_task_numbers() == 3
    assert await teams.teams.ensure_task_numbers() == 0, "idempotent on every later startup"
    await teams.db.create_missing_indexes()

    numbers = {task.title: (await load(teams, task.id)).number for task in created}
    assert numbers == {"t1": 1, "t2": 2, "t0": 3}

    # The index is back, and the counter picked up where the backfill left off.
    async with teams.db.session() as session:
        assert (await session.get(ProjectCounter, "task_number")).value == 3
    fresh = await teams.teams.create_task(team_id=TEAM, assignee_id=DEV, title="new", brief="")
    assert fresh.number == 4
    async with teams.db.session() as session:
        rows = (
            await session.exec(text("PRAGMA index_list('team_task')"))
        ).all()
    assert any(row[1] == "ix_team_task_number" and row[2] == 1 for row in rows), "unique"


async def test_startup_arms_the_counter_on_a_board_that_has_no_counter_row(
    teams: AppContext,
) -> None:
    """Found by probing the demo project, not by a test — the gap was real.

    A project can arrive with numbered tasks and no counter: a Studio that backfilled
    before the table existed, or a database copied from one. There, the no-reuse guarantee
    silently did not hold, because the counter was only ever created by a *creation*: drop
    the highest task and the next one seeds a fresh counter from a maximum that has just
    come down. `ensure_task_numbers` therefore seeds it whether or not anything was stale.
    """
    first = await teams.teams.create_task(team_id=TEAM, assignee_id=DEV, title="a", brief="")
    second = await teams.teams.create_task(team_id=TEAM, assignee_id=DEV, title="b", brief="")
    assert (first.number, second.number) == (1, 2)

    async with teams.db.session() as session:
        await session.delete(await session.get(ProjectCounter, "task_number"))

    assert await teams.teams.ensure_task_numbers() == 0, "nothing to number"
    async with teams.db.session() as session:
        assert (await session.get(ProjectCounter, "task_number")).value == 2

    await teams.teams.delete_task(second.id)
    third = await teams.teams.create_task(team_id=TEAM, assignee_id=DEV, title="c", brief="")
    assert third.number == 3, "2 belonged to a task that existed; it is not handed out twice"


def test_the_project_code_is_validated_and_uppercased(client) -> None:
    assert client.put("/api/settings", json={"project_code": " vs "}).json()["project_code"] == "VS"

    refused = client.put("/api/settings", json={"project_code": "far too long"})
    assert refused.status_code == 400 and "1–6" in refused.json()["detail"]
    assert client.put("/api/settings", json={"project_code": "123"}).status_code == 400

    # Blank clears it back to the code derived from the project's name.
    assert client.put("/api/settings", json={"project_code": ""}).json()["project_code"] is None


def test_every_task_payload_carries_its_display_id(client) -> None:
    created = client.post(
        f"/api/teams/{TEAM}/tasks", json={"assignee_id": DEV, "title": "a"}
    ).json()
    assert created["display_id"] == "GAM-01"
    assert created["number"] == 1

    listed = client.get(f"/api/teams/{TEAM}/tasks").json()
    assert [entry["display_id"] for entry in listed] == ["GAM-01"]
    assert client.get(f"/api/teams/{TEAM}/tasks/{created['id']}").json()["display_id"] == "GAM-01"
    assert client.get(f"/api/teams/{TEAM}").json()["tasks"][0]["display_id"] == "GAM-01"

    client.put("/api/settings", json={"project_code": "VS"})
    assert client.get(f"/api/teams/{TEAM}/tasks").json()[0]["display_id"] == "VS-01"


def test_the_board_can_be_filtered_over_rest(client) -> None:
    first = client.post(
        f"/api/teams/{TEAM}/tasks",
        json={"assignee_id": DEV, "title": "Tune the grapple", "brief": "physics"},
    ).json()
    client.post(
        f"/api/teams/{TEAM}/tasks",
        json={"assignee_id": QA, "title": "Regression pass", "depends_on": [first["id"]]},
    )

    def names(**params) -> list[str]:
        return [
            entry["title"]
            for entry in client.get(f"/api/teams/{TEAM}/tasks", params=params).json()
        ]

    assert names(assignee_id=QA) == ["Regression pass"]
    assert names(search="grapple") == ["Tune the grapple"]
    assert names(search="physics") == ["Tune the grapple"], "the brief is searched too"
    assert names(depends_on="GAM-01") == ["Regression pass"], "by display id"
    assert names(status="todo") == ["Regression pass", "Tune the grapple"]
    assert names(status="done") == []
    assert len(names(limit=1)) == 1


# ------------------------------------------------------------------ the board over MCP


async def toolset_for(ctx: AppContext, member_id: str, task_id: str):
    """Build the toolset a run would get, plus a caller that returns (failed, text)."""
    async with ctx.db.session() as session:
        member = await session.get(TeamMember, member_id)
        task = await session.get(TeamTask, task_id)

    toolset = build_toolset(ctx.teams, member=member, task=task)
    if toolset is None:
        pytest.skip("claude-agent-sdk is not installed")

    async def call(name: str, args: dict) -> tuple[bool, str]:
        definition = next(d for d in toolset.definitions if d.name == name)
        result = await definition.handler(args)
        text = "\n".join(
            block.get("text", "") for block in result.get("content", []) if isinstance(block, dict)
        )
        return bool(result.get("is_error")), text

    return toolset, call


async def test_the_toolset_publishes_the_board_tools(teams: AppContext) -> None:
    task = await teams.teams.create_task(team_id=TEAM, assignee_id=DEV, title="a", brief="")
    toolset, _ = await toolset_for(teams, DEV, task.id)

    assert {definition.name for definition in toolset.definitions} >= {
        "list_team",
        "list_tasks",
        "get_task",
        "create_task",
        "update_task",
        "add_task_note",
        "finish_task",
        "list_docs",
        "read_doc",
        "write_doc",
    }
    assert "mcp__voxarium_studio__list_tasks" in toolset.allowed_tools


async def test_an_agent_lists_and_filters_the_board(teams: AppContext) -> None:
    mine = await teams.teams.create_task(
        team_id=TEAM, assignee_id=DEV, title="Tune the grapple", brief="physics work"
    )
    theirs = await teams.teams.create_task(
        team_id=TEAM, assignee_id=QA, title="Regression pass", brief=""
    )
    _, call = await toolset_for(teams, DEV, mine.id)

    failed, text = await call("list_tasks", {})
    assert not failed
    assert "GAM-01 — Tune the grapple" in text and "GAM-02 — Regression pass" in text

    _, text = await call("list_tasks", {"mine": True})
    assert "Tune the grapple" in text and "Regression pass" not in text

    _, text = await call("list_tasks", {"search": "physics"})
    assert "Tune the grapple" in text and "Regression pass" not in text

    _, text = await call("list_tasks", {"assignee_id": QA})
    assert "Regression pass" in text and "Tune the grapple" not in text

    _, text = await call("list_tasks", {"status": ["done"]})
    assert text == "No tasks match that."

    failed, text = await call("list_tasks", {"status": ["nonsense"]})
    assert failed and "Unknown status" in text
    assert theirs.number == 2


async def test_an_agent_reads_one_task_by_its_display_id(teams: AppContext) -> None:
    upstream = await teams.teams.create_task(
        team_id=TEAM, assignee_id=DESIGNER, title="Write the spec", brief=""
    )
    mine = await teams.teams.create_task(
        team_id=TEAM,
        assignee_id=DEV,
        title="Build it",
        brief="follow the spec",
        depends_on=[upstream.id],
    )
    _, call = await toolset_for(teams, DEV, mine.id)

    failed, text = await call("get_task", {"task": "GAM-02"})
    assert not failed
    assert "GAM-02 — Build it" in text
    assert "follow the spec" in text
    assert "waits for: GAM-01 (todo)" in text
    assert mine.id in text, "the opaque id is still there for anything that needs it"

    failed, text = await call("get_task", {"task": "GAM-99"})
    assert failed and "No task" in text


async def test_an_agent_edits_a_task_and_the_edit_is_routed(teams: AppContext) -> None:
    mine = await teams.teams.create_task(team_id=TEAM, assignee_id=DEV, title="a", brief="")
    not_mine = await teams.teams.create_task(team_id=TEAM, assignee_id=QA, title="b", brief="")
    _, call = await toolset_for(teams, DEV, mine.id)

    failed, text = await call("update_task", {"task": "GAM-01", "brief": "sharpened"})
    assert not failed and "Updated GAM-01" in text
    assert (await load(teams, mine.id)).brief == "sharpened"

    failed, text = await call("update_task", {"task": "GAM-02", "title": "not yours"})
    assert failed and "RoutingError" in text
    assert (await load(teams, not_mine.id)).title == "b"

    failed, text = await call("update_task", {"task": "GAM-01"})
    assert failed and "Nothing to change" in text


async def test_an_agent_cannot_close_its_own_task(teams: AppContext) -> None:
    """Approving is the manager's call (§10.4) — and finishing the run already asks for it."""
    mine = await teams.teams.create_task(team_id=TEAM, assignee_id=DEV, title="a", brief="")
    _, call = await toolset_for(teams, DEV, mine.id)

    for status in ("done", "cancelled"):
        failed, text = await call("update_task", {"task": "GAM-01", "status": status})
        assert failed and "your manager's review queue" in text
    assert (await load(teams, mine.id)).status == TaskStatus.TODO

    failed, _ = await call("update_task", {"task": "GAM-01", "status": "pending"})
    assert not failed, "releasing it is still an ordinary move"


async def test_an_agent_may_close_a_reports_task_but_not_edit_a_number(
    teams: AppContext,
) -> None:
    """`check_can_edit` is assignee-or-manager, and the number is in no tool's schema."""
    theirs = await teams.teams.create_task(
        team_id=TEAM, assignee_id=DEV, title="delegated", brief=""
    )
    parent = await teams.teams.create_task(
        team_id=TEAM, assignee_id=DIRECTOR, title="own", brief=""
    )
    toolset, call = await toolset_for(teams, DIRECTOR, parent.id)

    failed, _ = await call("update_task", {"task": "GAM-01", "status": "done"})
    assert not failed, "the director manages dev"
    assert (await load(teams, theirs.id)).status == TaskStatus.DONE

    schema = next(d for d in toolset.definitions if d.name == "update_task").input_schema
    assert "number" not in schema["properties"] and "display_id" not in schema["properties"]


async def test_an_agent_notes_on_its_own_task_only(teams: AppContext) -> None:
    mine = await teams.teams.create_task(team_id=TEAM, assignee_id=DEV, title="a", brief="")
    not_mine = await teams.teams.create_task(team_id=TEAM, assignee_id=QA, title="b", brief="")
    _, call = await toolset_for(teams, DEV, mine.id)

    failed, text = await call("add_task_note", {"task": "1", "text": "the collider is off by 2"})
    assert not failed and "Noted on GAM-01" in text

    failed, text = await call("add_task_note", {"task": "GAM-02", "text": "nope"})
    assert failed and "RoutingError" in text

    async with teams.db.session() as session:
        rows = (
            await session.exec(
                select(AgentMessage)
                .where(AgentMessage.task_id == mine.id)
                .where(AgentMessage.kind == "note")
            )
        ).all()
    assert [(row.role, row.member_id) for row in rows] == [("human", DEV)]
    assert not_mine.number == 2


async def test_a_delegated_dependency_may_be_named_by_display_id(teams: AppContext) -> None:
    upstream = await teams.teams.create_task(
        team_id=TEAM, assignee_id=DEV, title="first", brief=""
    )
    _, call = await toolset_for(teams, DEV, upstream.id)

    failed, text = await call(
        "create_task",
        {"assignee_id": DIRECTOR, "title": "second", "brief": "b", "depends_on": ["GAM-01"]},
    )
    assert not failed and "Created GAM-02" in text

    async with teams.db.session() as session:
        created = (
            await session.exec(select(TeamTask).where(TeamTask.title == "second"))
        ).one()
    assert created.depends_on == [upstream.id], "stored as the opaque id the board uses"

    failed, text = await call(
        "create_task",
        {"assignee_id": DIRECTOR, "title": "third", "brief": "b", "depends_on": ["GAM-99"]},
    )
    assert failed and "No task" in text


# ------------------------------------------------------------------ editing the board


async def history(ctx: AppContext, task_id: str) -> list[tuple[str, str | None, str]]:
    """The task's status trail: (kind, from, to) for every transition it went through."""
    async with ctx.db.session() as session:
        rows = (
            await session.exec(
                select(AgentMessage)
                .where(AgentMessage.task_id == task_id)
                .where(AgentMessage.kind == "status")
                .order_by(AgentMessage.created_at)
            )
        ).all()
    return [(row.kind, row.data.get("from"), row.data.get("to")) for row in rows]


async def test_every_status_change_lands_in_the_task_history(
    teams: AppContext, wait_for
) -> None:
    """Manual moves and the ones the orchestrator makes on its own both get recorded."""
    task = await teams.teams.create_task(
        team_id=TEAM, assignee_id=DEV, title="t", brief="b", auto_start=True
    )
    await release(teams, task.id)
    await wait_for(task.id, timeout=20)
    await teams.teams.approve(task.id, actor_id=DIRECTOR)

    assert await history(teams, task.id) == [
        ("status", None, "todo"),
        ("status", "todo", "pending"),
        ("status", "pending", "in_progress"),
        ("status", "in_progress", "needs_review"),
        ("status", "needs_review", "done"),
    ]


async def test_a_manual_move_is_attributed_and_recorded(teams: AppContext) -> None:
    task = await teams.teams.create_task(team_id=TEAM, assignee_id=QA, title="t", brief="b")
    moved = await teams.teams.update_task(
        task.id, {"status": TaskStatus.IN_PROGRESS}, actor_id=QA
    )
    assert moved.status == TaskStatus.IN_PROGRESS
    assert moved.started_at is not None, "moving into in_progress starts the clock"

    async with teams.db.session() as session:
        entry = (
            await session.exec(
                select(AgentMessage)
                .where(AgentMessage.task_id == task.id)
                .where(AgentMessage.kind == "status")
                .order_by(AgentMessage.created_at.desc())  # type: ignore[union-attr]
            )
        ).first()
    assert entry.data["actor_id"] == QA and entry.data["to"] == "in_progress"


async def test_editing_a_task_records_what_changed(teams: AppContext) -> None:
    task = await teams.teams.create_task(team_id=TEAM, assignee_id=DEV, title="old", brief="b")
    edited = await teams.teams.update_task(task.id, {"title": "new", "brief": "sharper"})
    assert (edited.title, edited.brief) == ("new", "sharper")

    async with teams.db.session() as session:
        entry = (
            await session.exec(
                select(AgentMessage)
                .where(AgentMessage.task_id == task.id)
                .where(AgentMessage.kind == "edited")
            )
        ).first()
    assert entry.data["fields"]["title"] == {"from": "old", "to": "new"}


async def edits(ctx: AppContext, task_id: str) -> list[AgentMessage]:
    """Every "edited" entry on a task's transcript, oldest first."""
    async with ctx.db.session() as session:
        return list(
            (
                await session.exec(
                    select(AgentMessage)
                    .where(AgentMessage.task_id == task_id)
                    .where(AgentMessage.kind == "edited")
                    .order_by(AgentMessage.created_at)  # type: ignore[arg-type]
                )
            ).all()
        )


async def test_a_brief_written_in_one_sitting_is_one_entry(teams: AppContext) -> None:
    """The brief autosaves, so it arrives as a patch per pause in the typing (§10.4)."""
    task = await teams.teams.create_task(team_id=TEAM, assignee_id=DEV, title="t", brief="")
    for draft in ("Fix", "Fix the", "Fix the crash on load"):
        await teams.teams.update_task(task.id, {"brief": draft})

    entries = await edits(teams, task.id)
    assert len(entries) == 1, "a dozen 'Edited brief' lines would bury the run around them"
    assert entries[0].text == "Edited brief"
    assert entries[0].data["fields"]["brief"] == {"from": "", "to": "Fix the crash on load"}


async def test_only_the_brief_folds_and_only_into_a_brief(teams: AppContext) -> None:
    """Everything else is one deliberate act per patch, and keeps its own line."""
    task = await teams.teams.create_task(team_id=TEAM, assignee_id=DEV, title="t", brief="b")
    await teams.teams.update_task(task.id, {"brief": "sharper"})
    await teams.teams.update_task(task.id, {"title": "renamed"})
    await teams.teams.update_task(task.id, {"title": "renamed twice"})
    await teams.teams.update_task(task.id, {"brief": "sharper still"})
    # A patch carrying the brief *and* something else is a deliberate Save, not a keystroke.
    await teams.teams.update_task(task.id, {"brief": "final", "auto_start": True})

    assert [entry.text for entry in await edits(teams, task.id)] == [
        "Edited brief",
        "Edited title",
        "Edited title",
        "Edited brief",
        "Edited auto_start, brief",
    ]


async def test_a_brief_edited_after_something_was_said_starts_a_new_entry(
    teams: AppContext,
) -> None:
    """Rewriting an entry someone has already read past is not a history."""
    task = await teams.teams.create_task(team_id=TEAM, assignee_id=DEV, title="t", brief="b")
    await teams.teams.update_task(task.id, {"brief": "first"})
    await teams.teams.record_message(task.id, DEV, role="assistant", kind="text", text="on it")
    await teams.teams.update_task(task.id, {"brief": "second"})

    entries = await edits(teams, task.id)
    assert [entry.data["fields"]["brief"] for entry in entries] == [
        {"from": "b", "to": "first"},
        {"from": "first", "to": "second"},
    ]


async def test_a_brief_edited_after_a_long_pause_starts_a_new_entry(teams: AppContext) -> None:
    """Coming back to a task later is a second visit, not a rewrite of the first."""
    task = await teams.teams.create_task(team_id=TEAM, assignee_id=DEV, title="t", brief="b")
    await teams.teams.update_task(task.id, {"brief": "first"})

    entry = (await edits(teams, task.id))[0]
    stale = utcnow() - teams.teams.EDIT_COALESCE_WINDOW - timedelta(seconds=1)
    async with teams.db.session() as session:
        stored = await session.get(AgentMessage, entry.id)
        stored.created_at = stale
        session.add(stored)

    await teams.teams.update_task(task.id, {"brief": "second"})
    assert len(await edits(teams, task.id)) == 2


async def test_a_reassignment_is_routed_and_drops_the_session(teams: AppContext) -> None:
    task = await teams.teams.create_task(team_id=TEAM, assignee_id=DEV, title="t", brief="b")
    async with teams.db.session() as session:
        stored = await session.get(TeamTask, task.id)
        stored.agent_session_id = "sess_1"
        session.add(stored)

    with pytest.raises(RoutingError):
        await teams.teams.update_task(task.id, {"assignee_id": QA}, actor_id=DEV)

    moved = await teams.teams.update_task(task.id, {"assignee_id": DESIGNER})
    assert moved.assignee_id == DESIGNER
    assert moved.agent_session_id is None, "the next assignee starts their own session"


async def test_a_task_assigned_to_a_person_never_keeps_auto_start(teams: AppContext) -> None:
    task = await teams.teams.create_task(
        team_id=TEAM, assignee_id=DEV, title="t", brief="b", auto_start=False
    )
    moved = await teams.teams.update_task(task.id, {"assignee_id": QA, "auto_start": True})
    assert moved.auto_start is False, "there is no session to start for a person"


# ------------------------------------------------------------------ archiving and the cost figures


async def finish(ctx: AppContext, task_id: str) -> TeamTask:
    """Close a person's task by hand — the manual move the board's status picker makes."""
    return await ctx.teams.update_task(task_id, {"status": TaskStatus.DONE})


async def test_only_a_task_in_done_can_be_archived(teams: AppContext) -> None:
    task = await teams.teams.create_task(team_id=TEAM, assignee_id=QA, title="t", brief="b")
    with pytest.raises(ValueError, match="Done"):
        await teams.teams.update_task(task.id, {"archived": True})
    assert (await load(teams, task.id)).archived is False

    await finish(teams, task.id)
    archived = await teams.teams.update_task(task.id, {"archived": True})
    assert archived.archived is True and archived.status == TaskStatus.DONE


async def test_closing_and_archiving_in_one_patch_is_allowed(teams: AppContext) -> None:
    """The status the patch *lands on* is what is checked, not the one it starts from."""
    task = await teams.teams.create_task(team_id=TEAM, assignee_id=QA, title="t", brief="b")
    archived = await teams.teams.update_task(
        task.id, {"status": TaskStatus.DONE, "archived": True}
    )
    assert archived.archived is True and archived.status == TaskStatus.DONE


async def test_moving_an_archived_task_off_done_brings_it_back(teams: AppContext) -> None:
    task = await teams.teams.create_task(team_id=TEAM, assignee_id=QA, title="t", brief="b")
    await finish(teams, task.id)
    await teams.teams.update_task(task.id, {"archived": True})

    reopened = await teams.teams.update_task(task.id, {"status": TaskStatus.TODO})
    assert reopened.archived is False, "a card the board has to show is never hidden"

    async with teams.db.session() as session:
        entries = (
            await session.exec(
                select(AgentMessage)
                .where(AgentMessage.task_id == task.id)
                .where(AgentMessage.kind == "edited")
            )
        ).all()
    assert any(entry.data["fields"].get("archived") == {"from": True, "to": False} for entry in entries)


async def test_unarchiving_leaves_the_task_in_done(teams: AppContext) -> None:
    task = await teams.teams.create_task(team_id=TEAM, assignee_id=QA, title="t", brief="b")
    await finish(teams, task.id)
    await teams.teams.update_task(task.id, {"archived": True})
    restored = await teams.teams.update_task(task.id, {"archived": False})
    assert restored.archived is False and restored.status == TaskStatus.DONE


async def test_the_archive_filter_splits_the_board_and_the_default_sees_both(
    teams: AppContext,
) -> None:
    shown = await teams.teams.create_task(team_id=TEAM, assignee_id=QA, title="shown", brief="b")
    hidden = await teams.teams.create_task(team_id=TEAM, assignee_id=QA, title="hidden", brief="b")
    await finish(teams, hidden.id)
    await teams.teams.update_task(hidden.id, {"archived": True})

    ids = lambda rows: {row.id for row in rows}  # noqa: E731
    assert hidden.id not in ids(await teams.teams.query_tasks(team_id=TEAM, archived=False))
    assert shown.id in ids(await teams.teams.query_tasks(team_id=TEAM, archived=False))
    assert ids(await teams.teams.query_tasks(team_id=TEAM, archived=True)) == {hidden.id}
    assert {shown.id, hidden.id} <= ids(await teams.teams.query_tasks(team_id=TEAM))


async def test_an_archived_task_still_counts_toward_the_cost(
    teams: AppContext, wait_for
) -> None:
    """Archiving takes a card off the board and nothing off the books."""
    task = await teams.teams.create_task(
        team_id=TEAM, assignee_id=DEV, title="t", brief="b", auto_start=True
    )
    await release(teams, task.id)
    await wait_for(task.id, timeout=20)
    await teams.teams.approve(task.id, actor_id=DIRECTOR)
    await teams.teams.update_task(task.id, {"archived": True})

    stats = await teams.teams.stats(TEAM)
    assert stats["totals"]["archived"] == 1
    assert stats["totals"]["done_usd"] == pytest.approx(0.02), "the stub run's cost"
    assert stats["totals"]["spent_usd"] == pytest.approx(0.02)
    assert stats["series"][-1]["cumulative_usd"] == pytest.approx(0.02)
    assert sum(point["spent_usd"] for point in stats["series"]) == pytest.approx(0.02)
    dev = next(row for row in stats["by_member"] if row["member_id"] == DEV)
    assert dev["spent_usd"] == pytest.approx(0.02) and dev["done"] == 1


def test_the_cost_curve_is_built_from_the_runs_and_topped_up_from_the_task() -> None:
    """Pure arithmetic over rows: what the Statistics page plots, without a database."""
    from datetime import datetime, timezone

    from voxarium_studio.teams.stats import cost_events, cost_stats

    def at(day: int, hour: int = 12) -> datetime:
        return datetime(2026, 9, day, hour, tzinfo=timezone.utc)

    def result(task_id: str, when: datetime, cost: float) -> AgentMessage:
        return AgentMessage(
            task_id=task_id, member_id=DEV, role="system", kind="result",
            data={"total_cost_usd": cost}, created_at=when,
        )

    # Resumed across two days, and 0.05 the transcript does not explain: that lands on the
    # day the task finished, so the curve climbs to exactly the task's own figure.
    resumed = TeamTask(
        id="task_resumed", team_id=TEAM, assignee_id=DEV, title="a", status=TaskStatus.DONE,
        total_cost_usd=0.30, created_at=at(1), started_at=at(1), finished_at=at(3),
        archived=True,
    )
    # Results claiming more than the row holds are not trusted over it.
    trimmed = TeamTask(
        id="task_trimmed", team_id=TEAM, assignee_id=QA, title="b", status=TaskStatus.CANCELLED,
        total_cost_usd=0.20, created_at=at(2), started_at=at(2), finished_at=at(4),
    )
    # In flight and unpaid so far: contributes a row to the totals and nothing to the curve.
    fresh = TeamTask(
        id="task_fresh", team_id=TEAM, assignee_id=DEV, title="c", status=TaskStatus.PENDING,
        total_cost_usd=0.0, created_at=at(5),
    )
    messages = [
        result("task_resumed", at(1, 9), 0.10),
        result("task_resumed", at(2, 9), 0.15),
        result("task_trimmed", at(2, 10), 0.50),
        AgentMessage(task_id="task_resumed", member_id=DEV, role="assistant", kind="text",
                     text="not a result", created_at=at(1, 8)),
    ]

    events = cost_events([resumed, trimmed, fresh], messages)
    assert [(when.day, round(cost, 6), task) for when, cost, task in events] == [
        (1, 0.10, "task_resumed"),
        (2, 0.15, "task_resumed"),
        (3, 0.05, "task_resumed"),
        (4, 0.20, "task_trimmed"),
    ]

    stats = cost_stats([resumed, trimmed, fresh], messages, [])
    assert [(p["day"], p["spent_usd"], p["cumulative_usd"], p["runs"]) for p in stats["series"]] == [
        ("2026-09-01", 0.10, 0.10, 1),
        ("2026-09-02", 0.15, 0.25, 1),
        ("2026-09-03", 0.05, 0.30, 1),
        ("2026-09-04", 0.20, 0.50, 1),
    ]
    totals = stats["totals"]
    assert totals["spent_usd"] == pytest.approx(0.50)
    assert totals["done_usd"] == pytest.approx(0.30)
    assert totals["cancelled_usd"] == pytest.approx(0.20)
    assert totals["in_flight_usd"] == 0.0
    assert (totals["tasks"], totals["done"], totals["archived"]) == (3, 1, 1)
    assert [(row["member_id"], row["spent_usd"], row["tasks"], row["done"]) for row in stats["by_member"]] == [
        (DEV, 0.30, 2, 1),
        (QA, 0.20, 1, 0),
    ]


async def test_dependencies_are_validated_against_the_board(teams: AppContext) -> None:
    first = await teams.teams.create_task(team_id=TEAM, assignee_id=DEV, title="a", brief="b")
    second = await teams.teams.create_task(
        team_id=TEAM, assignee_id=DEV, title="b", brief="b", depends_on=[first.id]
    )

    with pytest.raises(ValueError, match="wait for itself"):
        await teams.teams.update_task(first.id, {"depends_on": [first.id]})
    with pytest.raises(ValueError, match="circular"):
        await teams.teams.update_task(first.id, {"depends_on": [second.id]})
    with pytest.raises(KeyError):
        await teams.teams.update_task(first.id, {"depends_on": ["task_ghost"]})


async def test_dropping_the_last_dependency_releases_a_waiting_task(
    teams: AppContext, wait_for
) -> None:
    blocker = await teams.teams.create_task(
        team_id=TEAM, assignee_id=DESIGNER, title="design", brief="b", auto_start=False
    )
    waiting = await teams.teams.create_task(
        team_id=TEAM, assignee_id=DEV, title="build", brief="b",
        depends_on=[blocker.id], auto_start=True,
    )
    await release(teams, waiting.id)
    assert (await load(teams, waiting.id)).status == TaskStatus.PENDING

    await teams.teams.update_task(waiting.id, {"depends_on": []})
    await wait_for(waiting.id, timeout=20)
    assert (await load(teams, waiting.id)).status == TaskStatus.NEEDS_REVIEW


async def test_resetting_a_task_erases_the_run_and_keeps_the_task(
    teams: AppContext, wait_for
) -> None:
    task = await teams.teams.create_task(
        team_id=TEAM,
        assignee_id=DEV,
        title="build",
        brief="the brief",
        depends_on=[],
        auto_start=True,
    )
    await release(teams, task.id)
    await wait_for(task.id, timeout=20)
    ran = await load(teams, task.id)
    assert ran.agent_session_id == "sess_1" and ran.num_turns == 2 and ran.result_summary

    reset = await teams.teams.reset_task(task.id)

    assert reset.status == TaskStatus.TODO
    assert reset.agent_session_id is None, "the next Start must be a start, not a resume"
    assert (reset.result_summary, reset.review_comment, reset.error) == (None, None, None)
    assert (reset.total_cost_usd, reset.num_turns, reset.total_tokens) == (0.0, 0, 0)
    assert (reset.started_at, reset.finished_at) == (None, None)
    assert (reset.title, reset.brief, reset.assignee_id) == ("build", "the brief", DEV)
    assert reset.auto_start is True

    async with teams.db.session() as session:
        left = (
            await session.exec(select(AgentMessage).where(AgentMessage.task_id == task.id))
        ).all()
    assert [row.kind for row in left] == ["status"], "the run's transcript goes with it"
    assert await history(teams, task.id) == [("status", "needs_review", "todo")]


async def test_resetting_a_task_that_never_ran_still_opens_its_history(
    teams: AppContext,
) -> None:
    """The wipe empties the transcript, so a reset in `todo` has to write the opening entry
    itself — `_set_task_status` writes nothing when the status does not move."""
    task = await teams.teams.create_task(team_id=TEAM, assignee_id=DEV, title="t", brief="b")

    await teams.teams.reset_task(task.id)

    assert await history(teams, task.id) == [("status", None, "todo")]


async def test_a_reset_is_the_assignees_or_their_managers_call(teams: AppContext) -> None:
    task = await teams.teams.create_task(team_id=TEAM, assignee_id=DEV, title="t", brief="b")

    with pytest.raises(RoutingError):
        await teams.teams.reset_task(task.id, actor_id=DESIGNER)

    assert (await teams.teams.reset_task(task.id, actor_id=DIRECTOR)).status == TaskStatus.TODO


def test_the_reset_endpoint_answers_404_for_an_unknown_task(client) -> None:
    assert client.post(f"/api/teams/{TEAM}/tasks/ghost/reset", json={}).status_code == 404


async def test_deleting_a_task_takes_its_transcript_and_frees_its_dependents(
    teams: AppContext, wait_for
) -> None:
    blocker = await teams.teams.create_task(
        team_id=TEAM, assignee_id=QA, title="verify", brief="b"
    )
    waiting = await teams.teams.create_task(
        team_id=TEAM, assignee_id=DEV, title="build", brief="b",
        depends_on=[blocker.id], auto_start=True,
    )
    await release(teams, waiting.id)

    await teams.teams.delete_task(blocker.id)
    assert await load(teams, blocker.id) is None
    async with teams.db.session() as session:
        left = (
            await session.exec(select(AgentMessage).where(AgentMessage.task_id == blocker.id))
        ).all()
    assert not left, "a deleted task leaves no orphan transcript behind"

    await wait_for(waiting.id, timeout=20)
    released = await load(teams, waiting.id)
    assert released.depends_on == [] and released.status == TaskStatus.NEEDS_REVIEW


async def test_a_running_task_is_not_moved_or_deleted_out_from_under_its_session(
    teams: AppContext,
) -> None:
    task = await teams.teams.create_task(team_id=TEAM, assignee_id=DEV, title="t", brief="b")

    class Never:
        """A run that never finishes, so the job is still holding the row."""

        name = "never"

        def is_available(self):
            return True, "stub"

        async def run(self, request):
            await asyncio.sleep(30)
            yield AgentEvent(kind="result", text="", is_final=True)

    register_backend("never", Never)
    async with teams.db.session() as session:
        member = await session.get(TeamMember, DEV)
        member.agent_backend = "never"
        session.add(member)

    await teams.teams.update_task(task.id, {"status": TaskStatus.PENDING})
    await teams.teams.start_task(task.id)
    while not teams.jobs.is_running(task.id):  # the submit is scheduled, not immediate
        await asyncio.sleep(0.05)

    with pytest.raises(ValueError, match="running"):
        await teams.teams.update_task(task.id, {"status": TaskStatus.DONE})
    with pytest.raises(ValueError, match="running"):
        await teams.teams.delete_task(task.id)
    with pytest.raises(ValueError, match="running"):
        # Same reason: clearing the session id under a live run would leave it writing into
        # a task that no longer says it started.
        await teams.teams.reset_task(task.id)

    # Cancellation only signals; the job still has a status write and a transcript entry to
    # make on its way out, and leaving those in flight is a database call after teardown.
    await teams.teams.cancel_task(task.id)
    while teams.jobs.is_running(task.id):
        await asyncio.sleep(0.05)


async def test_documents_reject_paths_that_leave_the_docs_directory(teams: AppContext) -> None:
    await teams.docs.write("features/dash.md", "# Dash\n")
    assert teams.docs.read("features/dash.md")["title"] == "Dash"

    with pytest.raises(PermissionError):
        teams.docs.resolve("../../config.yaml")
    with pytest.raises(ValueError):
        teams.docs.resolve("notes.exe")


# ------------------------------------------------------------------ roster on disk


async def test_saving_a_member_keeps_the_comments_in_the_team_file(teams: AppContext) -> None:
    """The roster is meant to be committed and read, so a UI edit must not strip it."""
    path = teams.paths.teams / "default-team.yaml"
    before = path.read_text(encoding="utf-8")
    comments = [line for line in before.splitlines() if line.strip().startswith("#")]
    assert comments, "the sample ships with explanatory comments"

    async with teams.db.session() as session:
        member = await session.get(TeamMember, DEV)
        member.name = "Renamed"
        member.max_turns = 55
        session.add(member)
    async with teams.db.session() as session:
        await teams.teams.store.save_from_db(session, TEAM)

    after = path.read_text(encoding="utf-8")
    assert [line for line in after.splitlines() if line.strip().startswith("#")] == comments
    assert "Renamed" in after and "max_turns: 55" in after


async def test_a_member_removed_from_yaml_but_owning_tasks_is_kept(teams: AppContext) -> None:
    """`team_task.assignee_id` is a real foreign key — deleting would break the sync."""
    await teams.teams.create_task(team_id=TEAM, assignee_id=DEV, title="t", brief="b")

    path = teams.paths.teams / "default-team.yaml"
    import yaml as pyyaml

    raw = pyyaml.safe_load(path.read_text(encoding="utf-8"))
    raw["members"] = [m for m in raw["members"] if m["id"] != "dev"]
    path.write_text(pyyaml.safe_dump(raw, sort_keys=False), encoding="utf-8")

    await teams.teams.sync_definitions()
    async with teams.db.session() as session:
        assert await session.get(TeamMember, DEV) is not None


# ------------------------------------------------------------------ a member's icon


def test_the_icon_round_trips_through_the_team_file(project: Path, client) -> None:
    """Written only once chosen and removed again when cleared — `effort`'s rule, and for the
    same reason: an `avatar_icon: null` on every row of a file meant to be read is noise."""
    roster = project / ".voxarium_studio" / "teams" / "default-team.yaml"

    saved = client.patch(f"/api/teams/{TEAM}/members/{DEV}", json={"avatar_icon": "pen-tool"})
    assert saved.status_code == 200 and saved.json()["avatar_icon"] == "pen-tool"
    assert "avatar_icon: pen-tool" in roster.read_text(encoding="utf-8")

    cleared = client.patch(f"/api/teams/{TEAM}/members/{DEV}", json={"avatar_icon": None})
    assert cleared.json()["avatar_icon"] is None, "null is a value here — back to no icon"
    body = roster.read_text(encoding="utf-8")
    assert "pen-tool" not in body


def test_an_icon_name_that_is_not_one_is_refused(client) -> None:
    """Only the shape, deliberately: the catalogue is `lib/member-icons.ts`, and a Studio that
    adds an icon must not need a backend that agrees with it first."""
    for value in ("Pen Tool", "pen_tool", "../etc", "x" * 65):
        refused = client.patch(f"/api/teams/{TEAM}/members/{DEV}", json={"avatar_icon": value})
        assert refused.status_code == 400, value
        assert "icon" in refused.json()["detail"].lower()

    # A name this build has never heard of still saves — it is the frontend that decides
    # whether it can draw one, and it falls back to the initials when it cannot.
    ahead = client.patch(f"/api/teams/{TEAM}/members/{DEV}", json={"avatar_icon": "space-elevator"})
    assert ahead.status_code == 200 and ahead.json()["avatar_icon"] == "space-elevator"


def test_a_blank_icon_means_no_icon_rather_than_one_called_nothing(client) -> None:
    """The same normalisation `agent` gets: an emptied field is the choice being taken off."""
    client.patch(f"/api/teams/{TEAM}/members/{DEV}", json={"avatar_icon": "bug"})

    blanked = client.patch(f"/api/teams/{TEAM}/members/{DEV}", json={"avatar_icon": "  "})

    assert blanked.status_code == 200 and blanked.json()["avatar_icon"] is None


def test_the_sample_roster_names_what_each_member_does(project: Path, client) -> None:
    """`voxarium_studio init` ships icons, so the feature is visible without anyone finding it."""
    members = {m["id"]: m for m in client.get(f"/api/teams/{TEAM}").json()["members"]}

    assert members[DIRECTOR]["avatar_icon"] == "crown"
    assert members[DESIGNER]["avatar_icon"] == "gamepad-2"
    assert members[DEV]["avatar_icon"] == "code"
    assert members[QA]["avatar_icon"] == "bug"


# ------------------------------------------------------------------ model and effort


def test_the_member_editor_is_offered_models_and_effort_levels(client) -> None:
    options = client.get("/api/teams/member-options").json()

    ids = [entry["id"] for entry in options["suggested_models"]]
    assert "sonnet" in ids, "the aliases the CLI resolves stay on the list"
    assert "claude-opus-5" in ids, "and pinned ids, which a tuned member wants"
    assert "claude-opus-5-5" in ids, "including the newest, which is why the list is kept"
    assert "claude-fable-5-2" in ids
    claude = [e for e in options["suggested_models"] if e["backend"] == "claude_code"]
    assert {entry["group"] for entry in claude} == {"Aliases", "Pinned"}
    # Other catalogues are tagged for their backend (§10.6). Codex's cache may be empty;
    # Qwen always offers its configured default first, then its full model list.
    assert all(e["backend"] in {"codex", "qwen"}
               for e in options["suggested_models"] if e not in claude)
    qwen = [e for e in options["suggested_models"] if e["backend"] == "qwen"]
    assert qwen[0]["id"] == "", "the default row stays first"
    assert any(e["id"] == "qwen3-coder-plus" for e in qwen)
    assert options["effort_levels"][0] == "low" and options["effort_levels"][-1] == "max"


def test_effort_round_trips_through_the_team_file(project: Path, client) -> None:
    """Written only once chosen, and removed again when cleared — the `pos_*` rule, and for
    the same reason: a null on every row of a file meant to be read is noise."""
    roster = project / ".voxarium_studio" / "teams" / "default-team.yaml"
    assert all("effort" not in member for member in yaml.safe_load(roster.read_text("utf-8"))["members"])

    saved = client.patch(f"/api/teams/{TEAM}/members/{DEV}", json={"effort": "xhigh"})
    assert saved.status_code == 200 and saved.json()["effort"] == "xhigh"
    assert "effort: xhigh" in roster.read_text(encoding="utf-8")

    cleared = client.patch(f"/api/teams/{TEAM}/members/{DEV}", json={"effort": None})
    assert cleared.json()["effort"] is None, "null is a value here — back to the model's default"
    assert all("effort" not in member for member in yaml.safe_load(roster.read_text("utf-8"))["members"])


def test_an_unknown_effort_level_is_refused(client) -> None:
    refused = client.patch(f"/api/teams/{TEAM}/members/{DEV}", json={"effort": "ludicrous"})

    assert refused.status_code == 400
    assert "effort" in refused.json()["detail"].lower()


async def test_a_members_effort_reaches_its_session(teams: AppContext, wait_for) -> None:
    seen: list = []

    class RecordingEffort:
        name = "recording_effort"

        def is_available(self):
            return True, "recording"

        async def run(self, request):
            seen.append(request.member.effort)
            yield AgentEvent(kind="result", text="Done.", is_final=True)

    register_backend("recording_effort", RecordingEffort)
    async with teams.db.session() as session:
        member = await session.get(TeamMember, DEV)
        member.agent_backend = "recording_effort"
        member.effort = "low"
        session.add(member)

    task = await teams.teams.create_task(
        team_id=TEAM, assignee_id=DEV, title="t", brief="b", auto_start=True
    )
    await release(teams, task.id)
    await wait_for(task.id, timeout=20)

    assert seen == ["low"]


async def _record_run(teams: AppContext, wait_for, task_id: str) -> list[tuple[str, str | None]]:
    """Run one task on a backend that only remembers the model and effort it was handed."""
    seen: list[tuple[str, str | None]] = []

    class RecordingRun:
        name = "recording_run"

        def is_available(self):
            return True, "recording"

        async def run(self, request):
            seen.append((request.model, request.effort))
            yield AgentEvent(kind="result", text="Done.", is_final=True)

    register_backend("recording_run", RecordingRun)
    async with teams.db.session() as session:
        member = await session.get(TeamMember, DEV)
        member.agent_backend = "recording_run"
        session.add(member)

    await release(teams, task_id)
    await wait_for(task_id, timeout=20)
    return seen


async def test_a_task_may_override_the_members_model_and_effort(
    teams: AppContext, wait_for
) -> None:
    """The point of the pair: one card is harder than the role it was assigned to (§10.3)."""
    async with teams.db.session() as session:
        member = await session.get(TeamMember, DEV)
        member.model, member.effort = "sonnet", "low"
        session.add(member)

    task = await teams.teams.create_task(
        team_id=TEAM,
        assignee_id=DEV,
        title="t",
        brief="b",
        auto_start=True,
        model="claude-fable-5-1",
        effort="max",
    )

    assert await _record_run(teams, wait_for, task.id) == [("claude-fable-5-1", "max")]


async def test_a_task_without_overrides_still_runs_on_the_members_pair(
    teams: AppContext, wait_for
) -> None:
    async with teams.db.session() as session:
        member = await session.get(TeamMember, DEV)
        member.model, member.effort = "claude-opus-5", "high"
        session.add(member)

    task = await teams.teams.create_task(
        team_id=TEAM, assignee_id=DEV, title="t", brief="b", auto_start=True
    )

    assert await _record_run(teams, wait_for, task.id) == [("claude-opus-5", "high")]


async def test_one_override_leaves_the_other_to_the_member(teams: AppContext, wait_for) -> None:
    """They are two settings, not a pair — overriding effort must not drop the model."""
    async with teams.db.session() as session:
        member = await session.get(TeamMember, DEV)
        member.model, member.effort = "claude-sonnet-5", None
        session.add(member)

    task = await teams.teams.create_task(
        team_id=TEAM, assignee_id=DEV, title="t", brief="b", auto_start=True, effort="xhigh"
    )

    assert await _record_run(teams, wait_for, task.id) == [("claude-sonnet-5", "xhigh")]


async def test_a_run_on_an_overridden_model_says_so_in_the_transcript(
    teams: AppContext, wait_for
) -> None:
    task = await teams.teams.create_task(
        team_id=TEAM, assignee_id=DEV, title="t", brief="b", auto_start=True, model="claude-opus-5"
    )
    await _record_run(teams, wait_for, task.id)

    async with teams.db.session() as session:
        entry = (
            await session.exec(
                select(AgentMessage)
                .where(AgentMessage.task_id == task.id)
                .where(AgentMessage.kind == "model")
            )
        ).first()
    assert entry is not None, "an unusual cost should be explained where the cost is shown"
    assert entry.data["model"] == "claude-opus-5"


def test_the_task_overrides_round_trip_and_clear(client) -> None:
    """Null is the edit here, not an omission: it hands the task back to the assignee."""
    created = client.post(
        f"/api/teams/{TEAM}/tasks",
        json={"assignee_id": DEV, "title": "t", "model": "claude-fable-5-1", "effort": "max"},
    ).json()
    assert (created["model"], created["effort"]) == ("claude-fable-5-1", "max")

    cleared = client.patch(
        f"/api/teams/{TEAM}/tasks/{created['id']}", json={"model": None, "effort": None}
    ).json()
    assert (cleared["model"], cleared["effort"]) == (None, None)
    assert cleared["title"] == "t", "a null elsewhere in the same patch is still dropped"


def test_a_blank_override_is_stored_as_no_override(client) -> None:
    created = client.post(
        f"/api/teams/{TEAM}/tasks", json={"assignee_id": DEV, "title": "t", "model": "  "}
    ).json()
    assert created["model"] is None

    patched = client.patch(
        f"/api/teams/{TEAM}/tasks/{created['id']}", json={"model": ""}
    ).json()
    assert patched["model"] is None


def test_an_unknown_effort_level_is_refused_on_a_task_too(client) -> None:
    task = client.post(f"/api/teams/{TEAM}/tasks", json={"assignee_id": DEV, "title": "t"}).json()

    refused = client.patch(f"/api/teams/{TEAM}/tasks/{task['id']}", json={"effort": "ludicrous"})

    assert refused.status_code == 400
    assert "effort" in refused.json()["detail"].lower()


def test_a_model_released_after_this_build_is_still_settable_on_a_task(client) -> None:
    """`SUGGESTED_MODELS` is the picker's list, not the set of legal answers."""
    task = client.post(f"/api/teams/{TEAM}/tasks", json={"assignee_id": DEV, "title": "t"}).json()

    saved = client.patch(
        f"/api/teams/{TEAM}/tasks/{task['id']}", json={"model": "claude-something-9"}
    )

    assert saved.status_code == 200 and saved.json()["model"] == "claude-something-9"


# ------------------------------------------------------------------ agent launch root


async def _enable_test_isolation(teams: AppContext) -> list:
    """A VCS that can create checkouts, with no repository or external process."""
    from voxarium_studio.vcs import IsolatedWorkspace, VcsStatus

    created: list[IsolatedWorkspace] = []

    class IsolatingVcs:
        id = "test-isolation"
        name = "Test isolation"
        supports_isolation = True

        async def create_workspace(self, path, branch):
            path.mkdir(parents=True)
            workspace = IsolatedWorkspace(path, branch)
            created.append(workspace)
            return workspace

        async def workspace_changes(self, path):
            return VcsStatus(backend=self.id, root=Path(path))

        async def diff(self, path=None):
            return ""

    teams.teams.vcs = IsolatingVcs()
    async with teams.db.session() as session:
        dev = await session.get(TeamMember, DEV)
        dev.isolate_worktree = True
        dev.solo_mode = False
        session.add(dev)
    return created


@pytest.mark.parametrize("target_kind", [
    "absolute-inside", "absolute-outside", "relative-outside", "relative-parent-inside",
    "symlink-outside",
])
async def test_shared_write_locations_use_locks_even_when_isolation_is_available(
    teams: AppContext, wait_for, target_kind: str,
) -> None:
    if target_kind == "symlink-outside" and sys.platform == "win32":
        pytest.skip("POSIX symlink coverage; Windows may require elevated privileges")
    outside = teams.paths.root.parent / "outside"
    outside.mkdir()
    if target_kind == "absolute-inside":
        declaration = str(teams.paths.root / "src" / "shared.py")
    elif target_kind == "absolute-outside":
        declaration = str(outside / "shared.py")
    elif target_kind == "relative-outside":
        declaration = "../outside/shared.py"
    elif target_kind == "relative-parent-inside":
        # Contained in the original project, but this spelling escapes a nested checkout.
        declaration = f"../{teams.paths.root.name}/src/shared.py"
    else:
        try:
            (teams.paths.root / "linked").symlink_to(outside, target_is_directory=True)
        except OSError as exc:
            pytest.skip(f"Symlinks unavailable: {exc}")
        declaration = "linked/shared.py"
    gates, _ = await _hold_dev_runs(teams, "held_external_writes")
    created = await _enable_test_isolation(teams)
    first = await _dev_task(teams, "First writer", write_paths=[declaration])
    second = await _dev_task(teams, "Second writer", write_paths=[declaration])

    async def started(task):
        return task.id in gates

    await release(teams, first.id)
    await _wait_until(lambda: started(first))
    assert created == [], "a shared location cannot follow an isolated checkout"
    await release(teams, second.id)

    async def waiting():
        return bool(await _queued_entries(teams, second.id))

    await _wait_until(waiting)
    [notice] = await _queued_entries(teams, second.id)
    assert notice.data["resource"] == "path"
    assert notice.data["blocking_task"] == teams.teams.display_id(first)
    assert second.id not in gates and created == []
    assert (await load(teams, first.id)).worktree_path is None
    _let_go(gates, first.id)
    await wait_for(first.id, timeout=20)
    await _wait_until(lambda: started(second))
    _let_go(gates, second.id)
    await wait_for(second.id, timeout=20)


async def test_project_relative_write_locations_keep_checkout_isolation(
    teams: AppContext, wait_for, monkeypatch,
) -> None:
    gates, _ = await _hold_dev_runs(teams, "held_isolated_writes")
    created = await _enable_test_isolation(teams)
    tasks = [
        await _dev_task(teams, title, write_paths=["src/shared.py"])
        for title in ("First writer", "Second writer")
    ]
    seen: list[AgentRunRequest] = []
    original_consume = teams.teams._consume

    async def consume(backend, request, *args):
        seen.append(request)
        return await original_consume(backend, request, *args)

    monkeypatch.setattr(teams.teams, "_consume", consume)
    for task in tasks:
        await release(teams, task.id)

    async def both_started():
        return len(gates) == 2

    await _wait_until(both_started)
    assert len(created) == 2 and created[0].path != created[1].path
    for request in seen:
        assert any(request.workdir == workspace.path for workspace in created)
        assert f"resolve relative write paths from {request.workdir}," in request.system_prompt
    for task in tasks:
        assert not await _queued_entries(teams, task.id), "each writer has its own shared.py"
        assert (await load(teams, task.id)).worktree_path
        _let_go(gates, task.id)
    for task in tasks:
        await wait_for(task.id, timeout=20)


async def test_stopped_task_with_checkout_rejects_shared_write_scope(
    teams: AppContext, wait_for,
) -> None:
    await _enable_test_isolation(teams)
    await _record_requests(teams, "recording_checkout_scope")
    task = await _dev_task(teams, "Isolated writer", write_paths=["src/a.py"])
    await release(teams, task.id)
    await wait_for(task.id, timeout=20)
    stopped = await load(teams, task.id)
    assert not teams.jobs.is_running(task.id)
    assert stopped.worktree_path and Path(stopped.worktree_path).is_dir()

    # The original checkout and its review must not silently switch to shared files.
    with pytest.raises(ValueError):
        await teams.teams.update_task(task.id, {
            "write_paths": [str(teams.paths.root / "src" / "b.py")],
        })
    preserved = await load(teams, task.id)
    assert preserved.write_paths == ["src/a.py"]
    assert preserved.worktree_path == stopped.worktree_path
    assert Path(preserved.worktree_path).is_dir()


async def _record_workdir(teams: AppContext, wait_for, name: str):
    """Run one task on a backend that only remembers where it was started."""
    seen: list = []

    class RecordingWorkdir:
        def is_available(self):
            return True, "recording"

        async def run(self, request):
            assert request.writable_roots == [teams.paths.root]
            seen.append(request.workdir)
            yield AgentEvent(kind="result", text="Done.", is_final=True)

    RecordingWorkdir.name = name
    register_backend(name, RecordingWorkdir)
    async with teams.db.session() as session:
        member = await session.get(TeamMember, DEV)
        member.agent_backend = name
        session.add(member)

    task = await teams.teams.create_task(
        team_id=TEAM, assignee_id=DEV, title="t", brief="b", auto_start=True
    )
    await release(teams, task.id)
    await wait_for(task.id, timeout=20)
    return seen[0], task


async def test_a_session_starts_in_the_project_root_by_default(teams: AppContext, wait_for) -> None:
    workdir, task = await _record_workdir(teams, wait_for, "recording_wd_default")

    assert workdir == teams.paths.root
    async with teams.db.session() as session:
        notes = (
            await session.exec(select(AgentMessage).where(AgentMessage.task_id == task.id))
        ).all()
    assert not any(note.kind == "workdir" for note in notes), "nothing to say about the default"


async def test_the_launch_root_moves_the_session_above_the_project(
    teams: AppContext, wait_for
) -> None:
    """A project that is one folder of a larger checkout — the case §10.5's note is about."""
    teams.config.teams.launch_root = ".."

    workdir, task = await _record_workdir(teams, wait_for, "recording_wd_above")

    assert workdir == teams.paths.root.parent
    async with teams.db.session() as session:
        notes = (
            await session.exec(
                select(AgentMessage).where(AgentMessage.task_id == task.id)
            )
        ).all()
    assert any(
        note.kind == "workdir" and "outside the project" in note.text for note in notes
    ), "the transcript says why this run got no isolated checkout"


async def test_a_launch_root_that_is_gone_falls_back_to_the_project(
    teams: AppContext, wait_for
) -> None:
    """The setting travels with the project, so it can name a path this machine lacks."""
    teams.config.teams.launch_root = "vanished"

    workdir, task = await _record_workdir(teams, wait_for, "recording_wd_missing")

    assert workdir == teams.paths.root
    async with teams.db.session() as session:
        notes = (
            await session.exec(
                select(AgentMessage).where(AgentMessage.task_id == task.id)
            )
        ).all()
    assert any(note.kind == "workdir" and "not a directory" in note.text for note in notes)


async def test_a_launch_root_inside_the_project_is_only_an_offset(
    teams: AppContext, wait_for
) -> None:
    """Inside the project it moves the session without giving up isolated checkouts —
    the offset is applied inside the worktree instead (see `_run_task`)."""
    (teams.paths.root / "engine").mkdir(exist_ok=True)
    teams.config.teams.launch_root = "engine"

    workdir, task = await _record_workdir(teams, wait_for, "recording_wd_inside")

    assert workdir == teams.paths.root / "engine"
    async with teams.db.session() as session:
        notes = (
            await session.exec(select(AgentMessage).where(AgentMessage.task_id == task.id))
        ).all()
    assert not any("outside the project" in note.text for note in notes)


# ------------------------------------------------------------------ project MCP servers


async def test_a_session_gets_the_projects_enabled_mcp_servers(
    teams: AppContext, wait_for
) -> None:
    """`.mcp.json` reaches the backend as config, allowed, and without the disabled one (§10.7)."""
    import json

    (teams.paths.root / ".mcp.json").write_text(
        json.dumps(
            {
                "mcpServers": {
                    "docs": {"command": "npx", "args": ["-y", "docs-server"]},
                    "muted": {"type": "http", "url": "https://example.test/mcp"},
                }
            }
        ),
        encoding="utf-8",
    )
    teams.config.mcp.disabled = ["muted"]

    seen: list = []

    class Recording:
        name = "recording"

        def is_available(self):
            return True, "recording"

        async def run(self, request):
            seen.append(request)
            yield AgentEvent(kind="result", text="Done.", is_final=True)

    register_backend("recording", Recording)
    async with teams.db.session() as session:
        member = await session.get(TeamMember, DEV)
        member.agent_backend = "recording"
        session.add(member)

    task = await teams.teams.create_task(
        team_id=TEAM, assignee_id=DEV, title="t", brief="b", auto_start=True
    )
    await release(teams, task.id)
    await wait_for(task.id, timeout=20)

    request = seen[0]
    assert request.mcp_servers["docs"] == {
        "type": "stdio",
        "command": "npx",
        "args": ["-y", "docs-server"],
    }
    assert "muted" not in request.mcp_servers, "a disabled server never reaches the session"
    assert "mcp__docs" in request.extra_allowed_tools
    assert request.strict_mcp_config is True


# ------------------------------------------------------------------ agent definitions (§10.3)

DESIGNER_AGENT = "marina"  # the sample roster's designer, slugged from her name


async def _record_requests(teams: AppContext, name: str) -> list:
    """Point DEV and DESIGNER at a backend that only remembers the request it was given."""
    seen: list = []

    class RecordingRequest:
        def is_available(self):
            return True, "recording"

        async def run(self, request):
            seen.append(request)
            yield AgentEvent(kind="result", text="Done.", is_final=True)

    RecordingRequest.name = name
    register_backend(name, RecordingRequest)
    async with teams.db.session() as session:
        for member_id in (DEV, DESIGNER):
            member = await session.get(TeamMember, member_id)
            member.agent_backend = name
            session.add(member)
    return seen


def _definitions(teams: AppContext) -> dict[str, str]:
    directory = teams.paths.claude_agents
    return {p.stem: p.read_text(encoding="utf-8") for p in directory.glob("*.md")}


async def test_the_roster_is_projected_into_claude_code_agents(teams: AppContext) -> None:
    await teams.teams.sync_agent_definitions()

    written = _definitions(teams)
    assert set(written) == {DESIGNER_AGENT, "igor", "producer", "effort-solver"}, "agents only, including built-in agents"
    assert f"name: {DESIGNER_AGENT}" in written[DESIGNER_AGENT]
    assert f"voxarium_studio_member: {DESIGNER}" in written[DESIGNER_AGENT], "ours to rewrite"
    assert "Game Designer" in written[DESIGNER_AGENT]


async def test_a_run_hands_the_session_its_members_agent(teams: AppContext, wait_for) -> None:
    seen = await _record_requests(teams, "recording_agent")
    task = await teams.teams.create_task(
        team_id=TEAM, assignee_id=DESIGNER, title="t", brief="b", auto_start=True
    )
    await release(teams, task.id)
    await wait_for(task.id, timeout=20)

    assert seen[0].agent_name == DESIGNER_AGENT
    assert "You are Marina" in seen[0].system_prompt, (
        "full replacement discards the definition's prompt, so the identity travels here"
    )
    assert "shared documents" in seen[0].system_prompt, "what only this run knows still is"


@pytest.mark.parametrize("new_scope", [["src/b.py"], []], ids=["replace", "clear"])
async def test_resumed_run_gets_updated_write_contract(
    teams: AppContext, wait_for, monkeypatch, new_scope: list[str],
) -> None:
    seen = await _record_requests(teams, "recording_write_contract")
    acquired: list[list[str]] = []
    original_acquire = teams.teams._path_locks.acquire

    async def acquire(paths, **kwargs):
        acquired.append(list(paths))
        return await original_acquire(paths, **kwargs)

    monkeypatch.setattr(teams.teams._path_locks, "acquire", acquire)
    task = await _dev_task(teams, "Read docs/design.md", write_paths=["src/a.py"])
    await release(teams, task.id)
    await wait_for(task.id, timeout=20)
    assert "- src/a.py" in seen[0].system_prompt
    assert "Read-only references never take locks" in seen[0].system_prompt
    assert "Do not edit undeclared" in seen[0].system_prompt

    # Model a stored session from the previous run, then revise its next-run scope.
    async with teams.db.session() as session:
        saved = await session.get(TeamTask, task.id)
        saved.agent_session_id = "previous-session"
        session.add(saved)
    await teams.teams.update_task(task.id, {"write_paths": new_scope})
    await teams.teams.request_changes(task.id, "Continue with the new scope", actor_id=DIRECTOR)
    await wait_for(task.id, timeout=20)

    assert len(seen) == 2
    assert seen[1].resume_session_id == "previous-session"
    assert seen[1].task.write_paths == new_scope
    assert "- src/a.py" not in seen[1].system_prompt
    assert ("- src/b.py" if new_scope else "Declared write files (write_paths):\n(none)") in seen[1].system_prompt
    assert acquired == [["src/a.py"], new_scope]


async def test_a_run_is_pointed_at_the_studio_skill_and_handed_it(
    teams: AppContext, wait_for
) -> None:
    """The prompt names the skill rather than describing the tools, and the request carries
    the skill with the rules that open it: a pointer to a skill the session was not handed
    would send every member looking for nothing (§10.3). With the Studio's tools switched off
    the pointer goes and the skill stays — the document dialect and the code graph still
    apply to a member working with files alone."""
    pytest.importorskip("claude_agent_sdk")
    seen = await _record_requests(teams, "recording_studio_skill")
    pointer = AgentPromptsConfig().text("studio_skill", skill=studio_skill.SKILL_NAME)

    task = await teams.teams.create_task(
        team_id=TEAM, assignee_id=DESIGNER, title="t", brief="b", auto_start=True
    )
    await release(teams, task.id)
    await wait_for(task.id, timeout=20)

    request = seen[0]
    assert request.plugin_dirs == [studio_skill.BUNDLE]
    assert set(studio_skill.allowed_tools()) <= set(request.extra_allowed_tools)
    assert "mcp__voxarium_studio__create_task" in request.extra_allowed_tools
    assert pointer in request.system_prompt
    assert "Every task has a short id" not in request.system_prompt, "the skill body stays on disk"
    assert str(studio_skill.BUNDLE / "skills" / "studio" / "SKILL.md") in request.system_prompt

    teams.config.mcp.disabled = ["voxarium_studio"]
    again = await teams.teams.create_task(
        team_id=TEAM, assignee_id=DESIGNER, title="t2", brief="b", auto_start=True
    )
    await release(teams, again.id)
    await wait_for(again.id, timeout=20)

    off = seen[1]
    assert off.plugin_dirs == [studio_skill.BUNDLE]
    assert set(studio_skill.allowed_tools()) <= set(off.extra_allowed_tools)
    assert pointer not in off.system_prompt
    assert default_prompt("no_studio_tools") in off.system_prompt, "Marina has a route to Igor"


async def test_a_run_is_told_its_members_skills_and_may_read_them(
    teams: AppContext, wait_for
) -> None:
    """A skill reaches exactly the member given it (§10.10). The prompt names each one with its
    description and the SKILL.md to read; the request carries a Read rule scoped to each folder,
    because an isolated checkout starts outside `.voxarium_studio/skills/`; and the transcript says which
    skills the run was told about. A name the folder no longer has is told to nobody."""
    seen = await _record_requests(teams, "recording_member_skills")
    folder = teams.paths.skills / "level-prototyping"
    folder.mkdir()
    (folder / "SKILL.md").write_text(
        "---\ndescription: Blocks out a level from a brief.\n---\n\nSteps.\n", encoding="utf-8"
    )
    async with teams.db.session() as session:
        dev = await session.get(TeamMember, DEV)
        dev.skills = ["level-prototyping", "renamed-away"]
        session.add(dev)

    given = await teams.teams.create_task(
        team_id=TEAM, assignee_id=DEV, title="t", brief="b", auto_start=True
    )
    await release(teams, given.id)
    await wait_for(given.id, timeout=20)

    request = seen[0]
    listing = project_skills.prompt_list(teams.skills.for_member(["level-prototyping"]).found)
    assert AgentPromptsConfig().text("member_skills", skills=listing) in request.system_prompt
    assert "level-prototyping: Blocks out a level from a brief." in request.system_prompt
    assert str(folder / "SKILL.md") in request.system_prompt
    assert "renamed-away" not in request.system_prompt
    rule = studio_skill.rule_path(folder)
    if rule is not None:
        assert f"Read({rule}/**)" in request.extra_allowed_tools

    async with teams.db.session() as session:
        notes = (
            await session.exec(
                select(AgentMessage).where(
                    AgentMessage.task_id == given.id, AgentMessage.kind == "skills"
                )
            )
        ).all()
    # §10.4: the Status column reads which of them were handed over whole at start-up — the
    # Studio's own skill always is — apart from the ones only named to the agent.
    assert [note.data for note in notes] == [
        {"skills": ["level-prototyping", "voxarium_studio:studio", "graphify"], "missing": ["renamed-away"],
         "loaded": ["voxarium_studio:studio"], "labels": {}}
    ]
    async with teams.db.session() as session:
        servers = (
            await session.exec(
                select(AgentMessage).where(AgentMessage.task_id == given.id, AgentMessage.kind == "mcp")
            )
        ).all()
    # The servers the run was handed, recorded by the Studio whichever backend runs it.
    assert request.mcp_servers
    assert [entry.data for entry in servers] == [{"servers": sorted(request.mcp_servers)}]
    assert {entry.role for entry in servers} == {"system"}

    # Marina was given nothing, so she is told nothing and may read nothing of it.
    other = await teams.teams.create_task(
        team_id=TEAM, assignee_id=DESIGNER, title="t2", brief="b", auto_start=True
    )
    await release(teams, other.id)
    await wait_for(other.id, timeout=20)
    assert str(folder / "SKILL.md") not in seen[1].system_prompt
    rule = studio_skill.rule_path(folder)
    if rule is not None:
        assert f"Read({rule}/**)" not in seen[1].extra_allowed_tools


async def test_the_run_prompts_follow_the_settings(teams: AppContext, wait_for) -> None:
    """The §10.3 append is assembled from `teams.prompts`, not from literals: an override
    reaches the session with its placeholders filled, a blanked fragment is not sent at
    all, and the stall message rides the request for the backend to speak."""
    teams.config.teams.prompts.task_context = "Your card is {task_id}; quote it."
    teams.config.teams.prompts.finish_tool = "Finish with the word DONE."
    teams.config.teams.prompts.docs_location = ""
    teams.config.teams.prompts.stall_continue = "Keep going."
    seen = await _record_requests(teams, "recording_prompt_overrides")

    task = await teams.teams.create_task(
        team_id=TEAM, assignee_id=DEV, title="t", brief="b", auto_start=True
    )
    await release(teams, task.id)
    await wait_for(task.id, timeout=20)

    prompt = seen[0].system_prompt
    finished = await load(teams, task.id)
    assert f"Your card is {teams.teams.display_id(finished)}; quote it." in prompt
    assert "Finish with the word DONE." in prompt
    assert "shared documents live" not in prompt, "a blanked fragment disappears"
    assert seen[0].continue_prompt == "Keep going."


async def test_every_run_opens_with_the_current_project_memory(teams: AppContext, wait_for) -> None:
    """§10.12: the index is read per run, so an entry written between two runs reaches the
    second; switched off, the run is told nothing about memory."""
    pytest.importorskip("claude_agent_sdk")
    seen = await _record_requests(teams, "recording_memory")

    first = await teams.teams.create_task(
        team_id=TEAM, assignee_id=DEV, title="t", brief="b", auto_start=True
    )
    await release(teams, first.id)
    await wait_for(first.id, timeout=20)
    assert "Project memory holds the team's lasting facts" in seen[0].system_prompt
    assert "(no entries yet)" in seen[0].system_prompt

    await teams.memory.write("build-uses-ue-5-4", description="Unreal 5.4 only", content="")
    second = await teams.teams.create_task(
        team_id=TEAM, assignee_id=DEV, title="t2", brief="b", auto_start=True
    )
    await release(teams, second.id)
    await wait_for(second.id, timeout=20)
    assert "- build-uses-ue-5-4 — Unreal 5.4 only" in seen[1].system_prompt

    teams.config.teams.memory_enabled = False
    third = await teams.teams.create_task(
        team_id=TEAM, assignee_id=DEV, title="t3", brief="b", auto_start=True
    )
    await release(teams, third.id)
    await wait_for(third.id, timeout=20)
    assert "Project memory" not in seen[2].system_prompt
    assert "build-uses-ue-5-4" not in seen[2].system_prompt


async def test_a_code_graph_at_the_launch_root_reaches_the_prompt(
    teams: AppContext, wait_for
) -> None:
    """`code_graph` is sent on the file, not on the plugin (§9.7): a graph the Studio has
    not built yet earns no instruction, and one that is there is named by its absolute
    path, since an isolated checkout starts the session somewhere the CLI's default
    `graphify-out/graph.json` is not."""
    seen = await _record_requests(teams, "recording_code_graph")
    task = await teams.teams.create_task(
        team_id=TEAM, assignee_id=DEV, title="t", brief="b", auto_start=True
    )
    await release(teams, task.id)
    await wait_for(task.id, timeout=20)
    assert "knowledge graph at" not in seen[0].system_prompt, "no graph, no index-location instruction"
    assert "- graphify:" in seen[0].system_prompt, "the plugin skill is available before indexing"

    graph = teams.paths.root / "graphify-out" / "graph.json"
    graph.parent.mkdir()
    graph.write_text("{}", encoding="utf-8")
    again = await teams.teams.create_task(
        team_id=TEAM, assignee_id=DEV, title="t2", brief="b", auto_start=True
    )
    await release(teams, again.id)
    await wait_for(again.id, timeout=20)

    prompt = seen[1].system_prompt
    assert (
        AgentPromptsConfig().text("code_graph", graph=str(graph), skill=studio_skill.SKILL_NAME)
        in prompt
    ), "the graph by its path, and the skill that says how to ask it"
    assert "graphify query" not in prompt, "the commands are the skill's"
    assert prompt.index("graphify") < prompt.index(_summary_prompt("finish_tool")), (
        "before the closing line, with the other things the run knows"
    )


async def test_the_identity_templates_reach_the_agent_definitions(teams: AppContext) -> None:
    """The sentences in `.claude/agents/*.md` are `teams.prompts` templates too — what a
    developer's terminal reads has to say the same thing the Studio's runs do (§10.3)."""
    teams.config.teams.prompts.identity = 'You are {name} ({role}) on "{team}".'
    teams.config.teams.prompts.identity_manager = ""
    await teams.teams.sync_agent_definitions()

    definition = _definitions(teams)[DESIGNER_AGENT]
    assert "You are Marina (Game Designer) on" in definition
    assert "You report to" not in definition, "a blanked line is dropped from the file"


async def test_a_hand_written_agent_of_the_same_name_is_never_overwritten(
    teams: AppContext, wait_for
) -> None:
    mine = teams.paths.claude_agents / f"{DESIGNER_AGENT}.md"
    mine.parent.mkdir(parents=True, exist_ok=True)
    original = "---\nname: marina\ndescription: Written by hand.\n---\n\nMine, not yours.\n"
    mine.write_text(original, encoding="utf-8")

    seen = await _record_requests(teams, "recording_agent_collision")
    task = await teams.teams.create_task(
        team_id=TEAM, assignee_id=DESIGNER, title="t", brief="b", auto_start=True
    )
    await release(teams, task.id)
    await wait_for(task.id, timeout=20)

    assert mine.read_text(encoding="utf-8") == original
    assert seen[0].agent_name is None, "no --agent rather than somebody else's agent"
    assert "You are Marina" in seen[0].system_prompt, "so the role travels the old way"


async def test_a_member_may_run_as_an_agent_the_project_already_defines(
    teams: AppContext, wait_for
) -> None:
    seen = await _record_requests(teams, "recording_agent_borrowed")
    async with teams.db.session() as session:
        member = await session.get(TeamMember, DESIGNER)
        member.agent = "code-reviewer"
        session.add(member)

    task = await teams.teams.create_task(
        team_id=TEAM, assignee_id=DESIGNER, title="t", brief="b", auto_start=True
    )
    await release(teams, task.id)
    await wait_for(task.id, timeout=20)

    assert seen[0].agent_name == "code-reviewer"
    assert DESIGNER_AGENT not in _definitions(teams), "nothing of ours written for her"


async def test_a_launch_root_outside_the_project_runs_without_an_agent(
    teams: AppContext, wait_for
) -> None:
    """Agent discovery walks up from the session's cwd, and from there it never arrives."""
    teams.config.teams.launch_root = ".."
    seen = await _record_requests(teams, "recording_agent_outside")

    task = await teams.teams.create_task(
        team_id=TEAM, assignee_id=DESIGNER, title="t", brief="b", auto_start=True
    )
    await release(teams, task.id)
    await wait_for(task.id, timeout=20)

    assert seen[0].agent_name is None
    assert "You are Marina" in seen[0].system_prompt


async def test_a_removed_member_takes_its_definition_with_it(teams: AppContext) -> None:
    await teams.teams.sync_agent_definitions()
    assert DESIGNER_AGENT in _definitions(teams)

    async with teams.db.session() as session:
        await session.delete(await session.get(TeamMember, DESIGNER))
    await teams.teams.sync_agent_definitions()

    assert DESIGNER_AGENT not in _definitions(teams)
    assert "igor" in _definitions(teams), "and only that member's"


# ------------------------------------------------------------------ remote control (§10.8)

STUB_SESSION_URL = "https://claude.ai/code/session_stub"


class StubControl:
    """Stands in for a live CLI's control channel: what the SDK bridge would answer."""

    def __init__(self, refusal: str | None = None) -> None:
        self.refusal = refusal
        self.enabled = False
        self.name: str | None = None

    async def enable_remote_control(self, name: str | None = None) -> RemoteControl:
        if self.refusal:
            raise RemoteControlUnavailable(self.refusal)
        self.enabled, self.name = True, name
        return RemoteControl(
            session_url=STUB_SESSION_URL,
            connect_url="https://claude.ai/code",
            environment_id="env_stub",
        )

    async def disable_remote_control(self) -> None:
        self.enabled = False


async def _steerable_task(
    teams: AppContext, name: str, *, refusal: str | None = None
) -> tuple[TeamTask, StubControl]:
    """Start a task on a session that stays open and can be steered, and hand both back."""
    control = StubControl(refusal)

    class Steerable:
        def is_available(self):
            return True, "stub"

        async def run(self, request):
            register_live_session(request.task.id, control)
            try:
                await asyncio.sleep(30)
                yield AgentEvent(kind="result", text="", is_final=True)
            finally:
                forget_live_session(request.task.id)

    Steerable.name = name
    register_backend(name, Steerable)
    async with teams.db.session() as session:
        member = await session.get(TeamMember, DEV)
        member.agent_backend = name
        session.add(member)

    task = await teams.teams.create_task(
        team_id=TEAM, assignee_id=DEV, title="Tune combat", brief="b"
    )
    await release(teams, task.id)
    await teams.teams.start_task(task.id)
    # `is_running` goes true at submit, which is earlier than the session existing: the run
    # still has its worktree and status writes to do before the backend is reached.
    for _ in range(200):
        if live_session(task.id) is not None:
            break
        await asyncio.sleep(0.05)
    else:
        raise AssertionError("the stub session never registered itself")
    return task, control


async def _stop(teams: AppContext, task_id: str) -> None:
    await teams.teams.cancel_task(task_id)
    while teams.jobs.is_running(task_id):
        await asyncio.sleep(0.05)
    await asyncio.sleep(0.2)  # the cancel path still has writes to land


async def test_remote_control_hands_a_running_session_over_and_stores_its_link(
    teams: AppContext,
) -> None:
    task, control = await _steerable_task(teams, "steerable_ok")
    try:
        link = await teams.teams.enable_remote_control(task.id)
    finally:
        await _stop(teams, task.id)

    assert link.session_url == STUB_SESSION_URL
    assert control.enabled and control.name == "Tune combat", "the session is named after the task"

    async with teams.db.session() as session:
        notes = (
            await session.exec(select(AgentMessage).where(AgentMessage.task_id == task.id))
        ).all()
    entry = next(note for note in notes if note.kind == "remote_control")
    assert entry.data["session_url"] == STUB_SESSION_URL and entry.data["enabled"] is True


async def test_the_link_is_dropped_when_the_session_ends(teams: AppContext) -> None:
    """The bridge dies with the CLI subprocess, so a kept URL would control nothing."""
    task, _ = await _steerable_task(teams, "steerable_ends")
    await teams.teams.enable_remote_control(task.id)
    assert (await load(teams, task.id)).remote_control_url == STUB_SESSION_URL

    await _stop(teams, task.id)

    assert (await load(teams, task.id)).remote_control_url is None


async def test_disconnecting_takes_the_session_back_off_claude_ai(teams: AppContext) -> None:
    task, control = await _steerable_task(teams, "steerable_off")
    try:
        await teams.teams.enable_remote_control(task.id)
        await teams.teams.disable_remote_control(task.id)

        assert control.enabled is False
        assert (await load(teams, task.id)).remote_control_url is None
    finally:
        await _stop(teams, task.id)


async def test_a_task_with_no_running_session_has_nothing_to_hand_over(
    teams: AppContext,
) -> None:
    task = await teams.teams.create_task(
        team_id=TEAM, assignee_id=DEV, title="t", brief="b", auto_start=False
    )

    with pytest.raises(ValueError, match="running"):
        await teams.teams.enable_remote_control(task.id)


async def test_the_sessions_own_refusal_reaches_the_caller(teams: AppContext) -> None:
    """Its reasons are configuration facts — the message travels verbatim, not replaced."""
    task, _ = await _steerable_task(
        teams, "steerable_refuses", refusal="Remote Control requires claude.ai subscription auth."
    )
    try:
        with pytest.raises(RemoteControlUnavailable, match="subscription"):
            await teams.teams.enable_remote_control(task.id)
        assert (await load(teams, task.id)).remote_control_url is None
    finally:
        await _stop(teams, task.id)


def test_the_endpoint_answers_409_for_a_task_that_is_not_running(client) -> None:
    created = client.post(
        f"/api/teams/{TEAM}/tasks",
        json={"assignee_id": DEV, "title": "t", "brief": "b", "auto_start": False},
    )
    task_id = created.json()["id"]

    refused = client.post(f"/api/teams/{TEAM}/tasks/{task_id}/remote-control")

    assert refused.status_code == 409
    assert "running" in refused.json()["detail"]


def test_the_endpoint_answers_404_for_an_unknown_task(client) -> None:
    missing = client.post(f"/api/teams/{TEAM}/tasks/ghost/remote-control")

    assert missing.status_code == 404


# ------------------------------------------------------ a session that stops mid-work
#
# Covered against a fake `claude_agent_sdk` module rather than the real one: what is under
# test is what the run loop does with a scripted sequence of sessions, and the SDK is
# exactly the thing tests here must never reach.


class _TextBlock:
    def __init__(self, text: str) -> None:
        self.text = text


class _AssistantMessage:
    def __init__(self, text: str, session_id: str = "sess_1") -> None:
        self.content = [_TextBlock(text)]
        self.session_id = session_id


class _UserMessage:
    content: list[Any] = []


class _SystemMessage:
    """A `system` frame. The task-lifecycle ones are what tell a turn from a run.

    Built from the raw payload the CLI sends rather than from the SDK's typed subclasses:
    the backend reads `subtype` and `data` off the base class for exactly that reason —
    an SDK too old to have `TaskStartedMessage` still delivers the frame.
    """

    def __init__(self, subtype: str = "init", data: dict[str, Any] | None = None) -> None:
        self.subtype = subtype
        self.data = data or {}

    @classmethod
    def task_started(
        cls,
        task_id: str,
        *,
        task_type: str = "local_agent",
        description: str = "Reading the spec",
    ) -> "_SystemMessage":
        return cls(
            "task_started",
            {"task_id": task_id, "task_type": task_type, "description": description},
        )

    @classmethod
    def task_notification(
        cls, task_id: str, *, status: str = "completed"
    ) -> "_SystemMessage":
        return cls("task_notification", {"task_id": task_id, "status": status})

    @classmethod
    def task_updated(cls, task_id: str, *, status: str = "killed") -> "_SystemMessage":
        return cls("task_updated", {"task_id": task_id, "patch": {"status": status}})


class _Silence:
    """A session that goes quiet: the fake stops answering instead of ending the stream."""


class _ResultMessage:
    """One turn's ending. The defaults are a clean one; `stalled()` is the real case."""

    def __init__(
        self,
        result: str = "Done.",
        *,
        subtype: str = "success",
        is_error: bool = False,
        stop_reason: str | None = "end_turn",
        num_turns: int = 3,
        cost: float = 0.01,
        session_id: str = "sess_1",
        usage: dict[str, Any] | None = None,
    ) -> None:
        self.result = result
        self.subtype = subtype
        self.is_error = is_error
        self.stop_reason = stop_reason
        self.num_turns = num_turns
        self.total_cost_usd = cost
        self.duration_ms = 5
        self.session_id = session_id
        # The shape the CLI reports: cache reads dominate a real session's total.
        self.usage = (
            usage
            if usage is not None
            else {
                "input_tokens": 12,
                "cache_creation_input_tokens": 4_000,
                "cache_read_input_tokens": 120_000,
                "output_tokens": 900,
                "server_tool_use": {"web_search_requests": 0},
            }
        )

    @classmethod
    def stalled(cls, **kwargs: Any) -> "_ResultMessage":
        """What a real run answers when it spends its turns: nothing said, mid tool call.

        Copied from a live project's transcript — `num_turns` 41 against a `max_turns` of
        40, `is_error` true, and an empty result.
        """
        fields: dict[str, Any] = dict(
            result="",
            subtype="error_max_turns",
            is_error=True,
            stop_reason="tool_use",
            num_turns=41,
        )
        fields.update(kwargs)
        return cls(**fields)


def _fake_sdk(
    sessions: list[list[Any]], *, buffer_option: bool = True
) -> tuple[Any, list[str], list[Any]]:
    """A stand-in for `claude_agent_sdk`, the prompts it is sent, and the options it got.

    `sessions` is one list of messages per `ClaudeSDKClient` — a continuation is a new
    client, so a run continued twice must be given three. An `Exception` among a session's
    messages is raised where it sits, which is how the SDK reports a stream it could not
    read. `buffer_option=False` is an older SDK, whose options have no `max_buffer_size`.
    """
    sent: list[str] = []
    seen: list[Any] = []
    module = types.ModuleType("claude_agent_sdk")

    @dataclasses.dataclass
    class ClaudeAgentOptions:
        cwd: str = ""
        allowed_tools: list[str] = dataclasses.field(default_factory=list)
        mcp_servers: dict[str, Any] = dataclasses.field(default_factory=dict)
        strict_mcp_config: bool = True
        model: str = "sonnet"
        max_turns: int = 10
        permission_mode: str | None = None
        resume: str | None = None
        system_prompt: Any = None
        extra_args: dict[str, str | None] = dataclasses.field(default_factory=dict)
        effort: str | None = None
        max_buffer_size: int | None = None
        plugins: list[Any] = dataclasses.field(default_factory=list)
        tools: list[str] | None = None
        setting_sources: list[str] | None = None

    class ClaudeSDKClient:
        def __init__(self, options: Any) -> None:
            seen.append(options)
            self._pending: list[Any] = []

        async def __aenter__(self) -> "ClaudeSDKClient":
            return self

        async def __aexit__(self, *exc: Any) -> bool:
            return False

        async def query(self, prompt: str) -> None:
            sent.append(prompt)
            self._pending = list(sessions.pop(0)) if sessions else []

        async def receive_response(self):
            """The real contract: stop at the result frame, and pick up after it next call.

            A turn is not a session — a session that started a sub-agent is read again once
            its own turn has ended — so the messages are consumed rather than replayed.
            """
            while self._pending:
                message = self._pending.pop(0)
                if isinstance(message, Exception):
                    raise message
                if isinstance(message, _Silence):
                    await asyncio.sleep(3600)
                yield message
                if isinstance(message, _ResultMessage):
                    return

    if not buffer_option:
        ClaudeAgentOptions = dataclasses.make_dataclass(
            "ClaudeAgentOptions",
            [
                (f.name, f.type, f)
                for f in dataclasses.fields(ClaudeAgentOptions)
                if f.name != "max_buffer_size"
            ],
        )

    module.ClaudeAgentOptions = ClaudeAgentOptions
    module.ClaudeSDKClient = ClaudeSDKClient
    module.AssistantMessage = _AssistantMessage
    module.UserMessage = _UserMessage
    module.SystemMessage = _SystemMessage
    module.ResultMessage = _ResultMessage
    return module, sent, seen


async def _run_fake(
    monkeypatch,
    sessions: list[list[Any]],
    *,
    allowed: int = 3,
    buffer_option: bool = True,
    **request_overrides: Any,
) -> tuple[list[AgentEvent], list[str], list[Any]]:
    module, sent, seen = _fake_sdk(sessions, buffer_option=buffer_option)
    monkeypatch.setitem(sys.modules, "claude_agent_sdk", module)
    monkeypatch.setattr(claude_code.shutil, "which", lambda name: f"/usr/bin/{name}")
    monkeypatch.setenv("ANTHROPIC_API_KEY", "test-key")
    claude_code._option_fields.cache_clear()

    request = AgentRunRequest(
        member=TeamMember(team_id=TEAM, name="Dev", role_title="Developer"),
        task=TeamTask(team_id=TEAM, title="t", brief="b"),
        workdir=Path("."),
        prompt="the brief",
        system_prompt="you are a member",
        max_continuations=allowed,
        **request_overrides,
    )
    events = [event async for event in claude_code.ClaudeCodeBackend().run(request)]
    claude_code._option_fields.cache_clear()
    return events, sent, seen


async def test_claude_uses_resolved_team_run_settings(monkeypatch):
    _, _, seen = await _run_fake(
        monkeypatch, [[_ResultMessage("Done.")]], permission_mode="plan", max_turns=87,
    )
    assert seen[0].permission_mode == "plan"
    assert seen[0].max_turns == 87


@pytest.mark.parametrize("model", ["haiku", "opus", "claude-opus-5-5"])
async def test_text_generation_keeps_the_selected_model_without_plan_mode(monkeypatch, model):
    events, _, seen = await _run_fake(
        monkeypatch, [[_ResultMessage("Compressed.")]], model=model, effort="high",
        text_only=True, allowed=0,
    )
    options = seen[0]
    assert options.model == model
    assert options.effort == "high"
    assert options.permission_mode == "default"
    assert options.tools == [] and options.allowed_tools == []
    assert options.mcp_servers == {} and options.strict_mcp_config
    assert options.setting_sources == [] and options.plugins == []
    assert options.system_prompt == "you are a member"
    assert events[-1].text == "Compressed."


def test_a_result_counts_every_kind_of_token() -> None:
    """Cache reads are most of what a long session reads, so leaving them out would say a
    40-turn run consumed less than its own first prompt. The dict is kept whole beside it."""
    data = claude_code._result_data(_ResultMessage("Done."))
    assert data["total_tokens"] == 12 + 4_000 + 120_000 + 900
    assert data["usage"]["cache_read_input_tokens"] == 120_000

    # A result with no usage at all — an older CLI, or a failure before the first turn.
    bare = _ResultMessage("Done.", usage={})
    bare.usage = None
    assert claude_code._result_data(bare)["total_tokens"] == 0
    assert claude_code._result_data(bare)["usage"] == {}


class TextBlock:
    def __init__(self, text: str) -> None:
        self.text = text


class ToolUseBlock:
    def __init__(self, name: str) -> None:
        self.id = f"call_{name}"
        self.name = name
        self.input = {"command": "npm test"}


class _PricedTurn:
    def __init__(self, usage: Any) -> None:
        self.content = [TextBlock("Running the tests."), ToolUseBlock("Bash"), ToolUseBlock("Bash")]
        self.usage = usage


def test_a_turns_accounting_rides_one_of_its_events() -> None:
    """One API response is one `usage`, and the transcript sums what it is given.

    A turn that answers in prose and then calls two tools becomes three transcript rows, so
    a copy on each would have the reader's token count treat one turn as three."""
    events = claude_code._assistant_events(
        _PricedTurn({"output_tokens": 900, "input_tokens": 12, "service_tier": "standard"}),
        "sess_1",
    )

    assert [event.kind for event in events] == ["text", "tool_use", "tool_use"]
    # Only the token fields survive; the SDK's other keys are not the transcript's business.
    assert events[0].data["usage"] == {"output_tokens": 900, "input_tokens": 12}
    assert [event.data.get("usage") for event in events[1:]] == [None, None]
    # And the tool call's own summary is still intact beside it.
    assert events[1].data["name"] == "Bash"


def test_a_turn_the_runtime_never_priced_carries_no_zero() -> None:
    """An absent count and a count of zero are different answers, and the line under the
    transcript says nothing at all rather than "0 tokens" for the first."""
    for usage in (None, {}, "lots"):
        events = claude_code._assistant_events(_PricedTurn(usage), "sess_1")
        assert all("usage" not in event.data for event in events)


def test_what_counts_as_stopping_mid_work() -> None:
    """The turn cap, or silence with a tool call in flight — and nothing else."""
    cap = {"subtype": "error_max_turns", "is_error": True, "stop_reason": "tool_use"}
    assert claude_code._stopped_mid_work(cap, "")
    # The phrase the CLI uses in its own transcript, should it ever arrive here.
    assert claude_code._stopped_mid_work({"stop_reason": "tool_use"}, "No response requested.")
    assert claude_code._stopped_mid_work({"stop_reason": "tool_use"}, "   ")

    # A model that chose to stop, and a failure that says what went wrong, are endings.
    assert not claude_code._stopped_mid_work({"stop_reason": "end_turn"}, "")
    assert not claude_code._stopped_mid_work({"stop_reason": "tool_use"}, "Refactored it.")
    assert not claude_code._stopped_mid_work(
        {"subtype": "error_during_execution", "is_error": True}, "Disk full"
    )


# ------------------------------------------------- one message bigger than the buffer
#
# The SDK reads the CLI's stdout as NDJSON and refuses a line past `max_buffer_size`,
# whose own default is 1 MiB. A `Read` of a screenshot is several times that once base64'd,
# so on the default the stream died mid-run and the task landed in Blocked (§10.3).


async def test_a_session_is_given_room_for_a_message_carrying_an_image(monkeypatch) -> None:
    events, _, seen = await _run_fake(monkeypatch, [[_ResultMessage("Done.")]])

    # A 4 MB screenshot is ~5.4 MB of base64 on one line; the ceiling has to clear that by
    # enough that the next screenshot does too.
    assert seen[0].max_buffer_size == MAX_MESSAGE_BYTES_DEFAULT > 8 * 1024 * 1024
    assert [event.kind for event in events][-1] == "result"


async def test_the_ceiling_is_the_projects_setting(monkeypatch) -> None:
    _, _, seen = await _run_fake(
        monkeypatch, [[_ResultMessage("Done.")]], max_message_bytes=5 * 1024 * 1024
    )

    assert seen[0].max_buffer_size == 5 * 1024 * 1024


async def test_an_sdk_without_the_option_runs_without_it(monkeypatch) -> None:
    """An install from before the `teams` floor rose keeps its older SDK: it must run, not
    `TypeError`."""
    events, _, seen = await _run_fake(
        monkeypatch, [[_ResultMessage("Done.")]], buffer_option=False
    )

    assert not hasattr(seen[0], "max_buffer_size")
    assert [(e.kind, e.text) for e in events] == [("result", "Done.")]


async def test_the_studio_skill_reaches_the_sdk_as_a_local_plugin(monkeypatch) -> None:
    """Claude Code takes a skill from outside the project only inside a plugin directory, so
    `plugin_dirs` becomes the SDK's `plugins` (§10.3). A request with none leaves the option
    alone, as every request built before the skill existed did."""
    _, _, seen = await _run_fake(
        monkeypatch, [[_ResultMessage("Done.")]], plugin_dirs=[studio_skill.BUNDLE]
    )
    assert seen[0].plugins == [{"type": "local", "path": str(studio_skill.BUNDLE)}]

    _, _, bare = await _run_fake(monkeypatch, [[_ResultMessage("Done.")]])
    assert bare[0].plugins == []


async def test_a_message_past_the_ceiling_names_the_setting_that_bounds_it(
    monkeypatch,
) -> None:
    """What the SDK says is true and useless: it names a number and no way to change it.

    The line it quotes is truncated at 100 characters, so the run's own error is the only
    place the cause can be spelled out — and the cause is nearly always one file read.
    """
    failure = RuntimeError(
        "Failed to decode JSON: JSON message exceeded maximum buffer size of 1048576 bytes..."
    )
    events, _, _ = await _run_fake(
        monkeypatch, [[_AssistantMessage("Reading the screenshot."), failure]]
    )

    error = [event for event in events if event.kind == "error"]
    assert len(error) == 1 and error[0].is_final
    assert "teams.max_message_bytes" in error[0].text
    assert "image" in error[0].text
    # The session it died in, so a retry resumes rather than starting the brief again.
    assert error[0].session_id == "sess_1"


def test_an_image_result_is_recorded_as_a_picture_rather_than_as_base64() -> None:
    """A tool result is a string for most tools and a list of blocks for one that answers
    with a picture. `str()` on that list is megabytes of base64 built only to be cut to
    2000 characters of noise — stored on the transcript and drawn in the UI."""

    class ToolResultBlock:
        tool_use_id = "call_1"
        is_error = False
        content = [
            {"type": "text", "text": "Read image (3.4MB)"},
            {
                "type": "image",
                "source": {"type": "base64", "media_type": "image/png", "data": "A" * 4_755_868},
            },
        ]

    class _Message:
        content = [ToolResultBlock()]

    (event,) = claude_code._tool_result_events(_Message(), "sess_1")

    assert event.text == "Read image (3.4MB)\n[image/png, 4755868 base64 bytes]"
    assert "AAAA" not in event.text


async def test_a_run_that_spends_its_turns_is_resumed_and_continued(monkeypatch) -> None:
    """The observed failure: `max_turns` reached, empty result, work half done.

    The continuation is a **second CLI session resuming the first**, because the turn budget
    is counted per invocation — re-querying the exhausted process would change nothing.
    """
    events, sent, seen = await _run_fake(
        monkeypatch,
        [
            [_AssistantMessage("Started."), _ResultMessage.stalled()],
            [_AssistantMessage("Finished the refactor."), _ResultMessage("Done.")],
        ],
    )

    assert sent == ["the brief", default_prompt("stall_continue")]
    # A fresh client per attempt, the second one resuming the session the first reported.
    assert len(seen) == 2
    assert seen[0].resume is None and seen[1].resume == "sess_1"

    results = [event for event in events if event.kind == "result"]
    assert [(event.text, event.is_final) for event in results] == [("", False), ("Done.", True)]
    assert results[0].data["continued"] is True
    assert results[0].data["subtype"] == "error_max_turns"
    assert results[1].data["continued"] is False

    # The continuation is in the transcript, in words, because the empty result is not.
    notice = [e for e in events if e.data.get("subtype") == "continue_after_stall"]
    assert len(notice) == 1
    # Its own kind, not `system`: the transcript hides system messages by default, and this
    # is the line that explains the empty result card beside it.
    assert notice[0].kind == "continued"
    assert "used all 41 turns" in notice[0].text
    assert notice[0].data == {
        "subtype": "continue_after_stall",
        "attempt": 1,
        "prompt": default_prompt("stall_continue"),
    }


async def test_continuing_stops_at_the_configured_ceiling(monkeypatch) -> None:
    """Every continuation is a billed session, so the ceiling cannot depend on the model."""
    events, sent, _ = await _run_fake(
        monkeypatch,
        [[_ResultMessage.stalled()] for _ in range(5)],
        allowed=2,
    )

    continue_prompt = default_prompt("stall_continue")
    assert sent == ["the brief", continue_prompt, continue_prompt]
    results = [event for event in events if event.kind == "result"]
    assert [event.data["continued"] for event in results] == [True, True, False]
    assert results[-1].is_final is True

    # Giving up is said out loud too — the run ends on an empty result either way, and the
    # difference between "it finished" and "it ran out of continuations" is the whole point.
    gave_up = [e for e in events if e.data.get("subtype") == "stall_not_continued"]
    assert len(gave_up) == 1 and gave_up[0].kind == "continued"
    assert "all 2 continuations" in gave_up[0].text


async def test_a_stall_is_continued_in_the_words_the_request_carries(monkeypatch) -> None:
    """`stall_continue` is a `teams.prompts` setting; the backend speaks what it is handed
    rather than a literal of its own."""
    events, sent, _ = await _run_fake(
        monkeypatch,
        [[_ResultMessage.stalled()], [_ResultMessage("Done.")]],
        continue_prompt="Go on.",
    )

    assert sent == ["the brief", "Go on."]
    notice = [e for e in events if e.data.get("subtype") == "continue_after_stall"]
    assert notice[0].data["prompt"] == "Go on."


async def test_a_run_is_not_continued_when_the_project_turns_it_off(monkeypatch) -> None:
    events, sent, _ = await _run_fake(monkeypatch, [[_ResultMessage.stalled()]], allowed=0)

    assert sent == ["the brief"]
    assert [e.data["continued"] for e in events if e.kind == "result"] == [False]
    off = [e for e in events if e.kind == "continued"]
    assert len(off) == 1 and "switched off" in off[0].text


async def test_a_session_that_finished_is_left_alone(monkeypatch) -> None:
    """The ordinary ending: something said, on purpose. Nothing here may resume it."""
    events, sent, seen = await _run_fake(
        monkeypatch, [[_AssistantMessage("All set."), _ResultMessage("Summary.")]]
    )

    assert sent == ["the brief"] and len(seen) == 1
    assert [e.is_final for e in events if e.kind == "result"] == [True]
    assert not [e for e in events if e.kind == "continued"], "nothing stalled, nothing to say"


async def test_a_continued_run_is_summarised_by_its_work_and_is_not_blocked(
    teams: AppContext, wait_for
) -> None:
    """A stalled turn carries `is_error` — the turn cap sets it — and an empty summary.
    Neither may reach the task, or a run that went on to succeed lands blocked and blank."""

    class Continued:
        name = "continued"

        def is_available(self):
            return True, "stub"

        async def run(self, request):
            yield AgentEvent(kind="text", text="Refactored the loader.", session_id="sess_1")
            yield AgentEvent(
                kind="result",
                text="",
                data={
                    "subtype": "error_max_turns",
                    "is_error": True,
                    "num_turns": 41,
                    "total_cost_usd": 0.01,
                    "total_tokens": 400_000,
                    "continued": True,
                },
            )
            yield AgentEvent(
                kind="result",
                text="",  # the continuation ended on a tool call too, and said nothing
                data={"is_error": False, "num_turns": 2, "total_cost_usd": 0.02, "total_tokens": 25_000},
                is_final=True,
            )

    register_backend("continued", Continued)
    async with teams.db.session() as session:
        member = await session.get(TeamMember, DEV)
        member.agent_backend = "continued"
        session.add(member)

    task = await teams.teams.create_task(
        team_id=TEAM, assignee_id=DEV, title="t", brief="b", auto_start=True
    )
    await release(teams, task.id)
    await wait_for(task.id, timeout=20)

    finished = await load(teams, task.id)
    assert finished.status == TaskStatus.NEEDS_REVIEW
    assert finished.result_summary == "Refactored the loader."
    # Both turns were paid for, so both are counted.
    assert finished.num_turns == 43
    assert finished.total_cost_usd == pytest.approx(0.03)
    assert finished.total_tokens == 425_000


# ------------------------------------------ a turn that ends while sub-agents keep going
#
# The member starts delegated work — `Agent`, `SendMessage` into an idle one, `Workflow` —
# and Claude Code closes the *turn* the moment its own reply is done, waking the session
# again as each task finishes. Reading only to the first result took that for a finished
# task: it went to the manager's review queue and the CLI subprocess was torn down under
# the work that was still running (§10.3).


async def test_a_turn_that_ends_with_a_sub_agent_running_does_not_end_the_run(
    monkeypatch,
) -> None:
    events, sent, seen = await _run_fake(
        monkeypatch,
        [
            [
                _AssistantMessage("Started five readers."),
                _SystemMessage.task_started("task_a", description="Reading group 1"),
                _ResultMessage("Started five readers."),
                _SystemMessage.task_notification("task_a"),
                _AssistantMessage("All five reported."),
                _ResultMessage("Here is the summary."),
            ]
        ],
    )

    # One CLI process and one prompt: the follow-up turn is the session being woken by the
    # runtime, not the Studio resuming it — that is the difference from a stall.
    assert sent == ["the brief"]
    assert len(seen) == 1

    results = [event for event in events if event.kind == "result"]
    assert [(event.text, event.is_final) for event in results] == [
        ("Started five readers.", False),
        ("Here is the summary.", True),
    ]
    assert results[0].data["continued"] is True
    assert results[0].data["pending_tasks"] == 1
    assert results[1].data["continued"] is False
    assert results[1].data["pending_tasks"] == 0

    held = [e for e in events if e.data.get("subtype") == "waiting_for_tasks"]
    assert len(held) == 1 and held[0].kind == "continued"
    assert "Reading group 1" in held[0].text
    assert not [e for e in events if e.data.get("subtype") == "background_wait_gave_up"]


async def test_a_background_shell_does_not_hold_the_run_open(monkeypatch) -> None:
    """A dev server started with `Bash(run_in_background=True)` never reaches a terminal
    status, so waiting on one would hold the session for its whole life rather than
    briefly. Only work whose completion wakes the parent counts."""
    events, _, _ = await _run_fake(
        monkeypatch,
        [
            [
                _SystemMessage.task_started(
                    "shell_1", task_type="background_shell", description="npm run dev"
                ),
                _ResultMessage("Server is up."),
            ]
        ],
    )

    results = [event for event in events if event.kind == "result"]
    assert [(event.text, event.is_final) for event in results] == [("Server is up.", True)]
    assert results[0].data["continued"] is False
    assert results[0].data["pending_tasks"] == 0
    assert not [e for e in events if e.kind == "continued"]


async def test_delegated_work_that_ends_only_as_a_patch_still_releases_the_run(
    monkeypatch,
) -> None:
    """A terminal state can arrive as a `task_updated` patch with no notification beside it
    — a workflow stopped with `TaskStop` reports `killed` that way. Clearing on only one of
    the two frames leaks the id and holds the run open until the idle timeout."""
    events, _, seen = await _run_fake(
        monkeypatch,
        [
            [
                _SystemMessage.task_started(
                    "wf_1", task_type="local_workflow", description="review-changes"
                ),
                _ResultMessage("Workflow launched."),
                _SystemMessage.task_updated("wf_1", status="killed"),
                _ResultMessage("It was stopped."),
            ]
        ],
    )

    assert len(seen) == 1
    results = [event for event in events if event.kind == "result"]
    assert [event.data["continued"] for event in results] == [True, False]
    assert not [e for e in events if e.data.get("subtype") == "background_wait_gave_up"]


async def test_the_hold_gives_up_after_the_configured_silence(monkeypatch) -> None:
    """The hold cannot be unbounded: a task that never reports again would keep the run In
    progress and hold an `agent` slot with no way back except Stop."""
    events, _, _ = await _run_fake(
        monkeypatch,
        [
            [
                _SystemMessage.task_started("task_a", description="Reading group 1"),
                _ResultMessage("Started."),
                _Silence(),
            ]
        ],
        delegated_work_idle_timeout=0.05,
    )

    gave_up = [e for e in events if e.data.get("subtype") == "background_wait_gave_up"]
    assert len(gave_up) == 1 and gave_up[0].kind == "continued"
    assert gave_up[0].data == {
        "subtype": "background_wait_gave_up",
        "pending_tasks": 1,
        "timed_out": True,
    }
    # Named in the words the setting is spelled in, like every other ceiling here.
    assert "teams.delegated_work_idle_timeout" in gave_up[0].text
    # Giving up ends the run rather than failing it — the work may well have been done.
    assert not [e for e in events if e.kind == "error"]


async def test_a_stall_wins_over_waiting_for_delegated_work(monkeypatch) -> None:
    """`max_turns` is counted per invocation, so a session that spent it cannot take the
    follow-up turn a finishing task would wake it for. Waiting there would hold a session
    that can no longer speak; the transcript says the resume abandoned the work instead."""
    events, sent, seen = await _run_fake(
        monkeypatch,
        [
            [_SystemMessage.task_started("task_a"), _ResultMessage.stalled()],
            [_ResultMessage("Done.")],
        ],
    )

    assert sent == ["the brief", default_prompt("stall_continue")]
    assert len(seen) == 2
    assert not [e for e in events if e.data.get("subtype") == "waiting_for_tasks"]
    gave_up = [e for e in events if e.data.get("subtype") == "background_wait_gave_up"]
    assert len(gave_up) == 1 and gave_up[0].data["timed_out"] is False


async def test_a_run_held_open_for_sub_agents_reaches_review_once(
    teams: AppContext, wait_for
) -> None:
    """The orchestrator's half: a result that ends a turn is not the run's summary, and the
    task moves once, at the end — not on the first turn, with the work still going."""

    class Held:
        name = "held_for_subagents"

        def is_available(self):
            return True, "stub"

        async def run(self, request):
            yield AgentEvent(kind="text", text="Started five readers.", session_id="sess_1")
            yield AgentEvent(
                kind="result",
                text="Started five readers.",
                data={
                    "is_error": False,
                    "num_turns": 3,
                    "total_cost_usd": 0.01,
                    "continued": True,
                    "pending_tasks": 5,
                },
            )
            yield AgentEvent(
                kind="result",
                text="All five reported; here is the summary.",
                data={"is_error": False, "num_turns": 4, "total_cost_usd": 0.02},
                is_final=True,
            )

    register_backend("held_for_subagents", Held)
    async with teams.db.session() as session:
        member = await session.get(TeamMember, DEV)
        member.agent_backend = "held_for_subagents"
        session.add(member)

    task = await teams.teams.create_task(
        team_id=TEAM, assignee_id=DEV, title="t", brief="b", auto_start=True
    )
    await release(teams, task.id)
    await wait_for(task.id, timeout=20)

    finished = await load(teams, task.id)
    assert finished.status == TaskStatus.NEEDS_REVIEW
    assert finished.result_summary == "All five reported; here is the summary."
    # Every turn was billed, so every turn is counted.
    assert finished.num_turns == 7
    assert finished.total_cost_usd == pytest.approx(0.03)


async def test_the_run_request_carries_the_projects_ceilings(
    teams: AppContext, wait_for
) -> None:
    seen: list[tuple[int, int, float]] = []

    class Recording:
        name = "recording_continuations"

        def is_available(self):
            return True, "stub"

        async def run(self, request):
            seen.append(
                (
                    request.max_continuations,
                    request.max_message_bytes,
                    request.delegated_work_idle_timeout,
                )
            )
            yield AgentEvent(kind="result", text="Done.", is_final=True)

    register_backend("recording_continuations", Recording)
    teams.config.teams = teams.config.teams.model_copy(
        update={
            "max_run_continuations": 1,
            "max_message_bytes": 2 * 1024 * 1024,
            "delegated_work_idle_timeout": 120.0,
        }
    )
    async with teams.db.session() as session:
        member = await session.get(TeamMember, DEV)
        member.agent_backend = "recording_continuations"
        session.add(member)

    task = await teams.teams.create_task(
        team_id=TEAM, assignee_id=DEV, title="t", brief="b", auto_start=True
    )
    await release(teams, task.id)
    await wait_for(task.id, timeout=20)

    assert seen == [(1, 2 * 1024 * 1024, 120.0)]


# ------------------------------------------------------------------ usage limits


def test_only_a_rejected_rate_limit_frame_stops_a_run() -> None:
    """`allowed_warning` is a window filling up; only `rejected` is one that is spent."""
    at = datetime(2026, 9, 8, 15, 0, tzinfo=timezone.utc)
    limit = usage_limits.from_rate_limit(
        {"status": "rejected", "resets_at": int(at.timestamp()), "rate_limit_type": "five_hour"}
    )
    assert limit is not None and limit.reset_at == at
    assert "five-hour" in limit.detail
    assert (
        usage_limits.from_rate_limit(
            {"status": "allowed_warning", "resets_at": int(at.timestamp())}
        )
        is None
    )


def test_a_429_is_a_usage_limit_and_the_studios_own_ceilings_are_not() -> None:
    """The word "limit" is in four other messages here, and a wait fixes none of them."""
    assert usage_limits.from_failure("", {"api_error_status": 429}) is not None
    assert usage_limits.from_failure("Delegation depth limit reached (3).", {}) is None
    assert usage_limits.from_failure("Reached maximum number of turns (40)", {}) is None
    assert usage_limits.from_failure("JSON message exceeded maximum buffer size", {}) is None


def test_a_reset_quoted_in_prose_is_read_and_a_duration_is_not() -> None:
    """`…usage limit reached|1789…` is the shape the CLI prints; "retry after 900" is a
    duration, and reading it as an epoch would date the reset to 1970."""
    at = datetime(2026, 9, 8, 15, 0, tzinfo=timezone.utc)
    found = usage_limits.from_failure(f"Claude AI usage limit reached|{int(at.timestamp())}", {})
    assert found is not None and found.reset_at == at

    blind = usage_limits.from_failure("Rate limit exceeded, retry after 900", {})
    assert blind is not None and blind.reset_at is None


def test_the_frame_dates_a_failure_that_could_not_date_itself() -> None:
    """Two halves of one event: the failure says the account ran out, and the frame before
    it says when the window refills."""
    at = datetime(2026, 9, 8, 15, 0, tzinfo=timezone.utc)
    reported = usage_limits.UsageLimit(detail="the five-hour usage limit is spent", reset_at=at)

    stopped = usage_limits.stopped_by("Claude usage limit reached", {}, reported)
    assert stopped is not None and stopped.reset_at == at

    assert usage_limits.stopped_by("Something else broke", {}, reported) is reported
    assert usage_limits.stopped_by("Something else broke", {}, None) is None


def test_the_wait_is_the_reset_plus_a_grace_and_is_never_in_the_past() -> None:
    """A reset that has been and gone is measured from now: the window refilled while
    nobody was watching, which is exactly what a restart finds."""
    now = datetime.now(timezone.utc)
    ahead = usage_limits.UsageLimit(detail="", reset_at=now + timedelta(hours=1))
    assert ahead.resume_at(grace=120, fallback=0) == now + timedelta(hours=1, seconds=120)

    behind = usage_limits.UsageLimit(detail="", reset_at=now - timedelta(hours=1))
    resumed = behind.resume_at(grace=120, fallback=0)
    assert resumed is not None and resumed > now

    undated = usage_limits.UsageLimit(detail="")
    assert undated.resume_at(grace=120, fallback=0) is None, "nothing to wait for"
    assert undated.resume_at(grace=120, fallback=60) is not None, "unless told to guess"


async def _limited_dev(teams: AppContext, name: str, *, resets_at: int | None) -> list[str]:
    """Point DEV at a backend whose first run is refused by a usage limit.

    The refusal has the shape a real one has (§10.3): a `rate_limit` frame carrying the
    reset, then a **result** with `is_error` and a 429 — and no `error` event at all, which
    is why a run stopped this way used to be left In progress. Every run after the first
    works, so a test can tell whether the wait actually started one.
    """
    prompts: list[str] = []

    class Limited:
        def is_available(self):
            return True, "limited"

        async def run(self, request):
            prompts.append(request.prompt)
            if len(prompts) > 1:
                yield AgentEvent(kind="result", text="Done.", session_id="sess_1", is_final=True)
                return
            if resets_at is not None:
                yield AgentEvent(
                    kind="rate_limit",
                    text="Usage rejected on the five-hour window",
                    session_id="sess_1",
                    data={
                        "status": "rejected",
                        "resets_at": resets_at,
                        "rate_limit_type": "five_hour",
                    },
                )
            yield AgentEvent(
                kind="result",
                text="Claude usage limit reached",
                session_id="sess_1",
                data={"is_error": True, "api_error_status": 429, "num_turns": 1},
                is_final=True,
            )

    Limited.name = name
    register_backend(name, Limited)
    async with teams.db.session() as session:
        member = await session.get(TeamMember, DEV)
        member.agent_backend = name
        session.add(member)
    return prompts


async def _limited_task(teams: AppContext, **fields: Any) -> TeamTask:
    """A task on DEV, patched and released — the caller waits for the run itself."""
    task = await teams.teams.create_task(
        team_id=TEAM, assignee_id=DEV, title="t", brief="b", auto_start=True
    )
    if fields:
        await teams.teams.update_task(task.id, dict(fields))
    await release(teams, task.id)
    return task


async def _entries(teams: AppContext, task_id: str) -> list[AgentMessage]:
    async with teams.db.session() as session:
        return list(
            (
                await session.exec(
                    select(AgentMessage)
                    .where(AgentMessage.task_id == task_id)
                    .order_by(AgentMessage.created_at)
                )
            ).all()
        )


async def _in_review(teams: AppContext, task_id: str) -> bool:
    return (await load(teams, task_id)).status == TaskStatus.NEEDS_REVIEW


async def test_a_usage_limit_blocks_the_task_and_records_when_it_resets(
    teams: AppContext, wait_for
) -> None:
    """The reset is recorded whether or not anything waits for it: it answers "when can
    this go again", and it is what a later tick of the box has to act on."""
    resets = utcnow() + timedelta(hours=2)
    prompts = await _limited_dev(teams, "limited_off", resets_at=int(resets.timestamp()))

    task = await _limited_task(teams, resume_on_limit=False)
    await wait_for(task.id, timeout=20)

    stored = await load(teams, task.id)
    assert stored.status == TaskStatus.BLOCKED, "a failed result used to leave it In progress"
    assert stored.limit_reset_at is not None
    assert abs((stored.limit_reset_at - resets).total_seconds()) < 1
    assert stored.limit_resume_at is None, "nothing waits with the box unticked"
    assert len(prompts) == 1, "and nothing ran again"

    said = [entry.text for entry in await _entries(teams, task.id) if entry.kind == "continued"]
    assert any("Resume on Limit reset is off" in text for text in said)


async def test_with_the_box_ticked_the_task_waits_and_then_runs_itself_again(
    teams: AppContext, wait_for
) -> None:
    """The whole feature, with a reset already in the past so the wait is instant: the run
    is refused, the task is parked in Blocked, and it comes back on its own."""
    teams.config.teams = teams.config.teams.model_copy(update={"limit_resume_grace": 0.0})
    resets = utcnow() - timedelta(minutes=1)
    prompts = await _limited_dev(teams, "limited_on", resets_at=int(resets.timestamp()))

    task = await _limited_task(teams)
    assert (await load(teams, task.id)).resume_on_limit, "waiting is what a new task does"
    await wait_for(task.id, timeout=20)
    await _wait_until(lambda: _in_review(teams, task.id), timeout=20)

    assert len(prompts) == 2, "the wait started the task again, once"
    finished = await load(teams, task.id)
    assert finished.limit_reset_at is None and finished.limit_resume_at is None
    assert [entry[2] for entry in await history(teams, task.id)] == [
        "todo",
        "pending",
        "in_progress",
        "blocked",
        "pending",
        "in_progress",
        "needs_review",
    ]
    assert any(
        "Starting the task again" in entry.text for entry in await _entries(teams, task.id)
    )


async def test_ticking_the_box_afterwards_waits_for_the_limit_already_recorded(
    teams: AppContext, wait_for
) -> None:
    """Retroactive on purpose: "wait for it" means wait for *that* one, not the next."""
    teams.config.teams = teams.config.teams.model_copy(update={"limit_resume_grace": 120.0})
    resets = utcnow() + timedelta(hours=3)
    prompts = await _limited_dev(teams, "limited_late", resets_at=int(resets.timestamp()))

    task = await _limited_task(teams, resume_on_limit=False)
    await wait_for(task.id, timeout=20)

    await teams.teams.update_task(task.id, {"resume_on_limit": True})
    armed = await load(teams, task.id)
    assert armed.limit_resume_at is not None
    assert abs((armed.limit_resume_at - resets).total_seconds() - 120) < 1
    assert armed.status == TaskStatus.BLOCKED, "waiting is not running"

    await teams.teams.update_task(task.id, {"resume_on_limit": False})
    stopped = await load(teams, task.id)
    assert stopped.limit_resume_at is None, "unticking is the only control over a wait"
    assert stopped.limit_reset_at is not None, "the limit itself is still why it is blocked"
    assert len(prompts) == 1


async def test_a_limit_with_no_reset_leaves_the_task_blocked_unless_told_to_guess(
    teams: AppContext, wait_for
) -> None:
    """Retrying blind is a guess about somebody's account, so it is opt-in (§10.3)."""
    prompts = await _limited_dev(teams, "limited_undated", resets_at=None)

    task = await _limited_task(teams)
    await wait_for(task.id, timeout=20)

    stored = await load(teams, task.id)
    assert stored.status == TaskStatus.BLOCKED
    assert stored.limit_reset_at is None and stored.limit_resume_at is None
    assert len(prompts) == 1
    assert any(
        "did not say when it resets" in entry.text for entry in await _entries(teams, task.id)
    )


async def test_a_wait_survives_a_restart_and_ends_when_a_person_takes_the_task_back(
    teams: AppContext, wait_for
) -> None:
    """The timer is process state and the row is not, so the row is what it is rebuilt from."""
    teams.config.teams = teams.config.teams.model_copy(update={"limit_resume_grace": 3600.0})
    resets = utcnow() + timedelta(hours=1)
    prompts = await _limited_dev(teams, "limited_restart", resets_at=int(resets.timestamp()))

    task = await _limited_task(teams)
    await wait_for(task.id, timeout=20)
    assert (await load(teams, task.id)).limit_resume_at is not None

    teams.teams.cancel_limit_waits()
    assert await teams.teams.resume_limit_waits() == 1, "the row is enough to rebuild it"

    # Moving the card by hand ends the wait: it undid one blocked run, and this is not it.
    await teams.teams.update_task(task.id, {"status": "todo"})
    moved = await load(teams, task.id)
    assert moved.limit_reset_at is None and moved.limit_resume_at is None
    assert await teams.teams.resume_limit_waits() == 0
    assert len(prompts) == 1


# ------------------------------------------------------------------ result type (§10.4)
#
# A task reaches review with a result type — done, question, error — reported by whoever did
# the work: an agent through `finish_task`, a person through Submit. The type is required,
# which for a session means one follow-up turn when the call was skipped; the suite keeps that
# follow-up off (`no_result_follow_up` in conftest) and turns it on where it is the subject.


async def test_finish_task_records_the_result_type_and_the_summary(teams: AppContext) -> None:
    task = await teams.teams.create_task(team_id=TEAM, assignee_id=DEV, title="t", brief="b")
    _, call = await toolset_for(teams, DEV, task.id)

    failed, text = await call(
        "finish_task", {"result_type": "question", "summary": "Which font do we ship?"}
    )
    assert not failed and "question" in text

    stored = await load(teams, task.id)
    assert (stored.result_type, stored.result_summary) == ("question", "Which font do we ship?")
    assert stored.status == TaskStatus.TODO, "the report moves nothing — the run's end does"
    async with teams.db.session() as session:
        entries = (
            await session.exec(
                select(AgentMessage)
                .where(AgentMessage.task_id == task.id)
                .where(AgentMessage.kind == "summary")
            )
        ).all()
    assert [(e.role, e.text, e.data) for e in entries] == [
        ("assistant", "Which font do we ship?", {"result_type": "question"})
    ]

    # The last report stands, as the last message would.
    failed, _ = await call("finish_task", {"result_type": "done", "summary": "Shipped Inter."})
    assert not failed
    assert (await load(teams, task.id)).result_type == "done"

    failed, text = await call("finish_task", {"result_type": "maybe", "summary": "x"})
    assert failed and "Unknown result type" in text
    failed, text = await call("finish_task", {"result_type": "done", "summary": "  "})
    assert failed and "needs a summary" in text


async def test_an_agent_reaches_review_through_finish_task_and_not_by_moving_the_status(
    teams: AppContext,
) -> None:
    task = await teams.teams.create_task(team_id=TEAM, assignee_id=DEV, title="t", brief="b")
    _, call = await toolset_for(teams, DEV, task.id)

    failed, text = await call("update_task", {"task": task.id, "status": "needs_review"})
    assert failed and "finish_task" in text
    assert (await load(teams, task.id)).status == TaskStatus.TODO


async def test_finish_task_has_no_card_to_report_on_in_a_chat(teams: AppContext) -> None:
    async with teams.db.session() as session:
        member = await session.get(TeamMember, DEV)
    toolset = build_toolset(teams.teams, member=member, task=None)
    if toolset is None:
        pytest.skip("claude-agent-sdk is not installed")
    definition = next(d for d in toolset.definitions if d.name == "finish_task")

    result = await definition.handler({"result_type": "done", "summary": "x"})

    assert result.get("is_error") and "chat" in result["content"][0]["text"]


def _reporting_backend(
    teams: AppContext, name: str, *, report: list[tuple[str, str] | None], silent: bool = False,
):
    """A session that calls `finish_task` the way the prompt asks — or does not.

    ``report`` is one entry per run the backend will be asked for: ``(type, summary)`` to
    make the call during that run, ``None`` to end the run without it. The requests are
    recorded so a test can read what the follow-up turn was sent.
    """
    seen: list[AgentRunRequest] = []

    class Reporting:
        def is_available(self):
            return True, "stub"

        async def run(self, request):
            seen.append(request)
            if not silent:
                yield AgentEvent(kind="text", text="Lowered the cooldown.", session_id="sess_1")
            wanted = report[len(seen) - 1] if len(seen) <= len(report) else None
            if wanted is not None:
                toolset = build_toolset(teams.teams, member=request.member, task=request.task)
                finish = next(d for d in toolset.definitions if d.name == "finish_task")
                await finish.handler({"result_type": wanted[0], "summary": wanted[1]})
            yield AgentEvent(
                kind="result",
                text="" if silent else "Closing words.",
                session_id="sess_1",
                data={"is_error": False, "num_turns": 1, "total_cost_usd": 0.01, "total_tokens": 10},
                is_final=True,
            )

    Reporting.name = name
    register_backend(name, Reporting)
    return seen


async def _run_on(
    teams: AppContext, wait_for, backend: str, member_id: str = DEV,
    *, summary_locale: str | None = None,
) -> TeamTask:
    async with teams.db.session() as session:
        member = await session.get(TeamMember, member_id)
        member.agent_backend = backend
        session.add(member)
    task = await teams.teams.create_task(
        team_id=TEAM, assignee_id=member_id, title="t", brief="b", auto_start=True,
        summary_locale=summary_locale,
    )
    await release(teams, task.id)
    await wait_for(task.id, timeout=20)
    return await load(teams, task.id)


async def test_a_run_that_reported_lands_in_review_with_its_type_and_the_reported_summary(
    teams: AppContext, wait_for
) -> None:
    pytest.importorskip("claude_agent_sdk")
    teams.config.teams.result_follow_up = True
    seen = _reporting_backend(
        teams, "reporting_once", report=[("error", "The reference asset is missing.")]
    )

    finished = await _run_on(teams, wait_for, "reporting_once")

    assert finished.status == TaskStatus.NEEDS_REVIEW
    assert finished.result_type == "error"
    assert finished.result_summary == "The reference asset is missing.", (
        "the report is the summary, not the run's closing words"
    )
    assert len(seen) == 1, "a run that reported is not asked again"


@pytest.mark.parametrize("locale", ["ru", "en", None])
@pytest.mark.parametrize("response_language", [None, "日本語"])
async def test_short_summary_uses_response_preference_not_legacy_locale(
    teams: AppContext, wait_for, locale: str | None, response_language: str | None,
) -> None:
    pytest.importorskip("claude_agent_sdk")
    teams.config.language = "ru"
    if response_language is not None:
        teams.config.respond_language = response_language
    language = response_language or "English"
    teams.config.teams.result_follow_up = True
    seen = _reporting_backend(
        teams, "silent_summary", report=[("done", "Fixed. Checks passed.")], silent=True,
    )
    finished = await _run_on(teams, wait_for, "silent_summary", summary_locale=locale)
    assert finished.status == TaskStatus.NEEDS_REVIEW
    assert finished.summary_locale == locale
    assert finished.result_summary == "Fixed. Checks passed."
    assert len(seen) == 1, "the stored summary completes the task without another model turn"
    assert _summary_prompt("finish_tool", language) in seen[0].system_prompt
    assert "{language}" not in seen[0].system_prompt
    async with teams.db.session() as session:
        messages = (await session.exec(select(AgentMessage).where(
            AgentMessage.task_id == finished.id,
            AgentMessage.kind.in_(["text", "summary"]),
        ))).all()
    assert [(message.kind, message.text) for message in messages] == [
        ("summary", "Fixed. Checks passed.")
    ]


async def test_review_resume_reads_current_response_preference_not_summary_locale(
    teams: AppContext, wait_for,
) -> None:
    seen = await _record_requests(teams, "locale_resume")
    teams.config.language = "ru"
    task = await teams.teams.create_task(
        team_id=TEAM, assignee_id=DESIGNER, title="English brief", brief="Use the response preference.",
        auto_start=True, summary_locale="ru",
    )
    await release(teams, task.id)
    await wait_for(task.id, timeout=20)
    assert (await load(teams, task.id)).summary_locale == "ru"
    assert _summary_prompt("finish_tool", "English") in seen[0].system_prompt
    teams.config.respond_language = "Español"
    await teams.teams.request_changes(task.id, "Revise it.", summary_locale="en")
    await wait_for(task.id, timeout=20)
    assert (await load(teams, task.id)).summary_locale == "en"
    assert _summary_prompt("finish_tool", "Español") in seen[1].system_prompt


async def test_legacy_summary_locale_metadata_is_inherited_from_parent_or_producer(teams: AppContext) -> None:
    parent = await teams.teams.create_task(
        team_id=TEAM, assignee_id=DESIGNER, title="Parent", brief="b", summary_locale="ru",
    )
    child = await teams.teams.create_task(
        team_id=TEAM, assignee_id=DEV, title="Child", brief="b", parent_task=parent, created_by=DESIGNER,
    )
    assert (await load(teams, child.id)).summary_locale == "ru"
    async with teams.db.session() as session:
        chat = AgentChat(member_id=DESIGNER, producer_mode=True, producer_locale="ru")
        session.add(chat)
    drafted = await teams.teams.create_task(
        team_id=TEAM, assignee_id=DEV, title="Producer draft", brief="b", producer_chat_id=chat.id,
    )
    assert (await load(teams, drafted.id)).summary_locale == "ru"


@pytest.mark.parametrize("locale", ["ru", "en", None])
@pytest.mark.parametrize("response_language", [None, "日本語"])
async def test_a_run_that_skipped_the_report_is_asked_once_in_the_same_session(
    teams: AppContext, wait_for, locale: str | None, response_language: str | None,
) -> None:
    pytest.importorskip("claude_agent_sdk")
    teams.config.language = "ru"
    if response_language is not None:
        teams.config.respond_language = response_language
    language = response_language or "English"
    teams.config.teams.result_follow_up = True
    seen = _reporting_backend(
        teams, "reporting_late", report=[None, ("question", "Which cooldown do you want?")]
    )

    finished = await _run_on(teams, wait_for, "reporting_late", summary_locale=locale)

    assert len(seen) == 2
    follow_up = seen[1]
    assert _summary_prompt("finish_tool", language) in seen[0].system_prompt
    assert follow_up.prompt == _summary_prompt("result_required", language)
    assert "{language}" not in follow_up.prompt
    assert follow_up.resume_session_id == "sess_1", "the same session, one more message"
    assert follow_up.images == [] and follow_up.recovery_prompt == ""
    assert finished.status == TaskStatus.NEEDS_REVIEW
    assert (finished.result_type, finished.result_summary) == (
        "question",
        "Which cooldown do you want?",
    )
    async with teams.db.session() as session:
        kinds = [
            row.kind
            for row in (
                await session.exec(
                    select(AgentMessage)
                    .where(AgentMessage.task_id == finished.id)
                    .order_by(AgentMessage.created_at)
                )
            ).all()
        ]
    assert "result_missing" in kinds, "the transcript says why there was a second turn"
    # Both turns were paid for.
    assert finished.num_turns == 2


async def test_a_run_that_never_reports_reaches_review_unlabelled(
    teams: AppContext, wait_for
) -> None:
    pytest.importorskip("claude_agent_sdk")
    teams.config.teams.result_follow_up = True
    seen = _reporting_backend(teams, "reporting_never", report=[None, None])

    finished = await _run_on(teams, wait_for, "reporting_never")

    assert len(seen) == 2, "asked once, never twice"
    assert finished.status == TaskStatus.NEEDS_REVIEW
    assert finished.result_type is None
    assert finished.result_summary == "Closing words.", "the run's own words stand in"


async def test_the_follow_up_is_a_setting(teams: AppContext, wait_for) -> None:
    pytest.importorskip("claude_agent_sdk")
    assert teams.config.teams.result_follow_up is False, "the suite's default"
    seen = _reporting_backend(teams, "reporting_off", report=[None])

    finished = await _run_on(teams, wait_for, "reporting_off")

    assert len(seen) == 1
    assert finished.status == TaskStatus.NEEDS_REVIEW and finished.result_type is None


@pytest.mark.parametrize("missing_tools", ["disabled", "unavailable"])
@pytest.mark.parametrize("language", ["English", "日本語"])
async def test_no_result_follow_up_without_studio_tools(
    teams: AppContext, wait_for, monkeypatch, missing_tools: str, language: str,
) -> None:
    teams.config.language = "ru"
    teams.config.respond_language = language
    teams.config.teams.result_follow_up = True
    if missing_tools == "disabled":
        teams.config.mcp.disabled = ["voxarium_studio"]
    else:
        monkeypatch.setattr("voxarium_studio.teams.orchestrator.build_toolset", lambda *a, **kw: None)
    seen = _reporting_backend(teams, "reporting_without_tools", report=[None])

    finished = await _run_on(teams, wait_for, "reporting_without_tools", summary_locale="ru")

    assert len(seen) == 1, "do not spend another turn asking for an unavailable tool"
    assert _summary_prompt("finish", language) in seen[0].system_prompt
    assert _summary_prompt("finish_tool", language) not in seen[0].system_prompt
    assert finished.status == TaskStatus.NEEDS_REVIEW
    assert finished.result_type is None
    assert finished.result_summary == "Closing words."
    async with teams.db.session() as session:
        messages = (await session.exec(
            select(AgentMessage).where(AgentMessage.task_id == finished.id)
        )).all()
    assert not any(message.kind == "result_missing" for message in messages)


async def test_a_new_run_clears_the_type_and_reset_clears_it_too(
    teams: AppContext, wait_for
) -> None:
    """The type describes one arrival in review; a run starting is the work being redone."""
    task = await teams.teams.create_task(
        team_id=TEAM, assignee_id=DEV, title="t", brief="b", auto_start=True
    )
    await teams.teams.report_result(
        task.id, result_type="done", summary="Reported before the run.", actor_id=DEV
    )
    assert (await load(teams, task.id)).result_type == "done"

    await release(teams, task.id)  # the stub backend reports nothing
    await wait_for(task.id, timeout=20)
    after = await load(teams, task.id)
    assert after.status == TaskStatus.NEEDS_REVIEW and after.result_type is None

    await teams.teams.report_result(task.id, result_type="error", summary="Broke.", actor_id=DEV)
    reset = await teams.teams.reset_task(task.id)
    assert reset.result_type is None and reset.result_summary is None


async def test_a_person_submits_with_a_result_type(teams: AppContext) -> None:
    task = await teams.teams.create_task(team_id=TEAM, assignee_id=QA, title="t", brief="b")
    await teams.teams.claim_task(task.id, actor_id=QA)

    with pytest.raises(ValueError, match="Unknown result type"):
        await teams.teams.complete_task(task.id, "x", result_type="shrug", actor_id=QA)

    done = await teams.teams.complete_task(
        task.id, "Found two defects.", result_type="error", actor_id=QA
    )
    assert done.status == TaskStatus.NEEDS_REVIEW
    assert (done.result_type, done.result_summary) == ("error", "Found two defects.")
    async with teams.db.session() as session:
        entry = (
            await session.exec(
                select(AgentMessage)
                .where(AgentMessage.task_id == task.id)
                .where(AgentMessage.kind == "summary")
            )
        ).one()
    assert (entry.role, entry.data) == ("human", {"result_type": "error"})


def test_the_complete_endpoint_requires_a_result_type(client) -> None:
    task = client.post(f"/api/teams/{TEAM}/tasks", json={"assignee_id": QA, "title": "t"}).json()
    assert client.post(
        f"/api/teams/{TEAM}/tasks/{task['id']}/claim", json={"actor_id": QA}
    ).status_code == 200

    refused = client.post(
        f"/api/teams/{TEAM}/tasks/{task['id']}/complete",
        json={"actor_id": QA, "summary": "x"},
    )
    assert refused.status_code == 422

    bad = client.post(
        f"/api/teams/{TEAM}/tasks/{task['id']}/complete",
        json={"actor_id": QA, "summary": "x", "result_type": "shrug"},
    )
    assert bad.status_code == 422

    done = client.post(
        f"/api/teams/{TEAM}/tasks/{task['id']}/complete",
        json={"actor_id": QA, "summary": "Checked.", "result_type": "question"},
    )
    assert done.status_code == 200
    assert done.json()["result_type"] == "question"
    assert done.json()["status"] == "needs_review"


async def test_the_prompt_asks_for_finish_task_only_when_the_tools_are_there(
    teams: AppContext, wait_for
) -> None:
    pytest.importorskip("claude_agent_sdk")
    seen = await _record_requests(teams, "recording_finish_prompt")

    task = await teams.teams.create_task(
        team_id=TEAM, assignee_id=DESIGNER, title="t", brief="b", auto_start=True
    )
    await release(teams, task.id)
    await wait_for(task.id, timeout=20)
    assert _summary_prompt("finish_tool") in seen[0].system_prompt
    assert _summary_prompt("finish") not in seen[0].system_prompt

    teams.config.mcp.disabled = ["voxarium_studio"]
    again = await teams.teams.create_task(
        team_id=TEAM, assignee_id=DESIGNER, title="t2", brief="b", auto_start=True
    )
    await release(teams, again.id)
    await wait_for(again.id, timeout=20)
    assert _summary_prompt("finish") in seen[1].system_prompt
    assert _summary_prompt("finish_tool") not in seen[1].system_prompt


async def test_archive_discards_only_verbose_rows_and_blocks_late_noise(teams: AppContext):
    task = await teams.teams.create_task(team_id=TEAM, assignee_id=QA, title="t", brief="b")
    for role, kind in [("system", "tool_result"), ("system", "system"),
                       ("assistant", "text"), ("assistant", "thinking"),
                       ("system", "tool_use"), ("system", "continued"),
                       ("system", "file_observation_status"), ("system", "file_observation_error"),
                       ("human", "note"), ("system", "commit")]:
        await teams.teams.record_message(task.id, QA, role=role, kind=kind, text=kind)
    await teams.teams.update_task(task.id, {"status": TaskStatus.DONE, "archived": True})
    await teams.teams.record_message(task.id, QA, role="system", kind="tool_result", text="late")
    async with teams.db.session() as session:
        rows = (await session.exec(select(AgentMessage).where(AgentMessage.task_id == task.id))).all()
    kinds = {row.kind for row in rows}
    assert not {"tool_result", "system"} & kinds
    assert {"text", "thinking", "tool_use", "continued", "note", "commit", "status", "edited"} <= kinds
    assert {"file_observation_status", "file_observation_error"} <= kinds
    await teams.teams.update_task(task.id, {"archived": False})
    assert (await load(teams, task.id)).status == TaskStatus.DONE


async def test_telemetry_updates_one_row_and_drops_command_payload(teams: AppContext):
    task = await teams.teams.create_task(team_id=TEAM, assignee_id=QA, title="t", brief="b")
    ids = []
    for count in range(5):
        row = await teams.teams.record_message(task.id, QA, role="system", kind="system",
            text="thinking_tokens", data={"estimated_tokens": count, "estimated_tokens_delta": 1})
        ids.append(row.id)
    for count in range(3):
        await teams.teams.record_message(task.id, QA, role="system", kind="system",
            text="commands_changed", data={"commands": ["large description" * 1000] * count})
    async with teams.db.session() as session:
        rows = (await session.exec(select(AgentMessage).where(
            AgentMessage.task_id == task.id, AgentMessage.kind == "system"))).all()
    assert len(set(ids)) == 1
    assert len(rows) == 2
    assert {r.text: r.data for r in rows} == {
        "thinking_tokens": {"estimated_tokens": 4}, "commands_changed": {"command_count": 2}}


# ------------------------------------------------------------------ notes to a running session (§10.8)


async def test_a_note_written_during_a_run_is_delivered_before_it_is_called_finished(
    teams: AppContext, wait_for
) -> None:
    """A backend that cannot be handed text mid-turn finishes its turn, and the note opens
    one more in the same session — the task does not reach review without it."""
    gates, prompts = await _hold_dev_runs(teams, "held_for_a_note")
    task = await teams.teams.create_task(
        team_id=TEAM, assignee_id=DEV, title="t", brief="do the thing", auto_start=True
    )
    await release(teams, task.id)
    await _wait_until(lambda: _running(teams, task.id))
    await _wait_until(lambda: _async(len(prompts) == 1))

    note = await teams.teams.add_note(task.id, "use the new API")
    assert note.data["delivery"] == "queued"
    assert len(prompts) == 1 and teams.jobs.is_running(task.id), "nothing was interrupted"

    _let_go(gates, task.id)
    await wait_for(task.id, timeout=20)
    assert len(prompts) == 2
    assert prompts[1].startswith("The user sent you this while you were working.")
    assert prompts[1].endswith("use the new API")
    assert (await load(teams, task.id)).status == TaskStatus.NEEDS_REVIEW
    async with teams.db.session() as session:
        stored = await session.get(AgentMessage, note.id)
    assert stored.data["delivery"] == "delivered"

    # And it was delivered once: the resume after this one must not carry it again.
    await release(teams, task.id)
    await wait_for(task.id, timeout=20)
    assert "use the new API" not in prompts[2]


async def test_a_note_a_stopped_run_never_took_rides_the_next_resume(
    teams: AppContext, wait_for
) -> None:
    gates, prompts = await _hold_dev_runs(teams, "held_then_stopped")
    task = await teams.teams.create_task(
        team_id=TEAM, assignee_id=DEV, title="t", brief="do the thing", auto_start=True
    )
    await release(teams, task.id)
    await _wait_until(lambda: _running(teams, task.id))
    await _wait_until(lambda: _async(len(prompts) == 1))
    note = await teams.teams.add_note(task.id, "mind the tests")
    await teams.teams.cancel_task(task.id)
    await wait_for(task.id, timeout=20)
    async with teams.db.session() as session:
        assert (await session.get(AgentMessage, note.id)).data["delivery"] == "queued"

    _let_go(gates, task.id)
    await teams.teams.start_task(task.id)
    await wait_for(task.id, timeout=20)
    assert "mind the tests" in prompts[1], "carried like any note written between runs"


async def test_an_agents_own_note_is_not_handed_back_to_it(teams: AppContext, wait_for) -> None:
    gates, prompts = await _hold_dev_runs(teams, "held_noting_itself")
    task = await teams.teams.create_task(
        team_id=TEAM, assignee_id=DEV, title="t", brief="do the thing", auto_start=True
    )
    await release(teams, task.id)
    await _wait_until(lambda: _running(teams, task.id))
    await _wait_until(lambda: _async(len(prompts) == 1))
    note = await teams.teams.add_note(task.id, "the collider is off by 2", author_id=DEV)
    assert "delivery" not in note.data
    _let_go(gates, task.id)
    await wait_for(task.id, timeout=20)
    assert len(prompts) == 1


async def _async(value: bool) -> bool:
    return value


async def _run_with_inbox(
    monkeypatch, turns: list[list[Any]], *, receipt_timeout: float | None = None
) -> tuple[list[AgentEvent], list[Any], Any, list[str]]:
    """Run the Claude backend against a CLI that echoes a mid-run message when told to.

    ``turns`` is one list of frames per ``receive_response`` call. ``"echo"`` among them is
    the CLI's replay of the message the run was sent mid-way, under the uuid it was sent
    with — which the fake waits to have been sent before it goes on, as the real one would.
    """
    from voxarium_studio.teams.backends.base import Inbox, QueuedMessage

    module, _, seen = _fake_sdk([])
    frames: list[dict[str, Any]] = []
    arrived = asyncio.Event()

    class EchoingClient:
        def __init__(self, options: Any) -> None:
            seen.append(options)

        async def __aenter__(self) -> "EchoingClient":
            return self

        async def __aexit__(self, *exc: Any) -> bool:
            return False

        async def query(self, prompt: Any) -> None:
            if isinstance(prompt, str):
                return
            async for frame in prompt:
                frames.append(frame)
                arrived.set()

        async def receive_response(self):
            for frame in turns.pop(0) if turns else []:
                if isinstance(frame, _Silence):
                    await asyncio.sleep(3600)
                if frame == "echo":
                    await asyncio.wait_for(arrived.wait(), 5)
                    frame = _UserMessage()
                    frame.content = frames[-1]["message"]["content"]
                    frame.uuid = frames[-1]["uuid"]
                yield frame
                if isinstance(frame, _ResultMessage):
                    return

    module.ClaudeSDKClient = EchoingClient
    monkeypatch.setitem(sys.modules, "claude_agent_sdk", module)
    monkeypatch.setattr(claude_code.shutil, "which", lambda name: f"/usr/bin/{name}")
    monkeypatch.setenv("ANTHROPIC_API_KEY", "test-key")
    if receipt_timeout is not None:
        monkeypatch.setattr(claude_code, "_RECEIPT_TIMEOUT", receipt_timeout)
    claude_code._option_fields.cache_clear()

    delivered: list[str] = []

    async def on_delivered(items):
        delivered.extend(item.id for item in items)

    inbox = Inbox(preamble="While you worked:\n\n{message}", on_delivered=on_delivered)
    inbox.put(QueuedMessage(id="m1", text="use the blue one"))
    request = AgentRunRequest(
        member=TeamMember(team_id=TEAM, name="Dev", role_title="Developer"),
        task=TeamTask(team_id=TEAM, title="t", brief="b"),
        workdir=Path("."),
        prompt="the brief",
        system_prompt="you are a member",
        inbox=inbox,
    )
    events = [event async for event in claude_code.ClaudeCodeBackend().run(request)]
    claude_code._option_fields.cache_clear()
    assert "replay-user-messages" in seen[0].extra_args, "without the echo there is no receipt"
    return events, frames, inbox, delivered


async def test_claude_is_sent_a_waiting_message_and_its_echo_is_the_receipt(monkeypatch) -> None:
    """§10.8, the common case: the CLI folds the message into the running turn after the
    tool call in flight and echoes it — one result, and that result ends the run."""
    events, frames, inbox, delivered = await _run_with_inbox(
        monkeypatch, [["echo", _ResultMessage("Done, in blue.")]]
    )
    (frame,) = frames
    assert frame["message"]["content"] == "While you worked:\n\nuse the blue one"
    assert delivered == ["m1"] and not len(inbox) and not inbox.unacknowledged
    (result,) = [event for event in events if event.kind == "result"]
    assert result.is_final and not result.data["continued"]
    assert not any(event.kind == "tool_result" for event in events), "the echo is not an event"


async def test_a_result_with_a_message_still_unread_does_not_end_the_claude_run(monkeypatch) -> None:
    """A turn with no tool call left ends first; the CLI then opens another on the message.
    The first result is a turn's, not the run's — reading stops at the second."""
    events, _, inbox, delivered = await _run_with_inbox(
        monkeypatch, [[_ResultMessage("First.")], ["echo", _ResultMessage("Second, in blue.")]]
    )
    first, second = [event for event in events if event.kind == "result"]
    assert first.data["continued"] and not first.is_final and first.data["pending_messages"] == 1
    assert second.is_final and second.text == "Second, in blue."
    assert delivered == ["m1"]
    assert not any(event.kind == "continued" for event in events), "nothing delegated to announce"


async def test_a_message_claude_never_echoes_goes_back_to_waiting(monkeypatch) -> None:
    """The CLI went quiet with the message unread: the run ends, the message is not called
    delivered, and it waits in the inbox for the caller to send as the next turn."""
    events, _, inbox, delivered = await _run_with_inbox(
        monkeypatch, [[_ResultMessage("First.")], [_Silence()]], receipt_timeout=0.05
    )
    assert [event.kind for event in events if event.kind == "result"] == ["result"]
    assert delivered == [] and len(inbox) == 1 and not inbox.unacknowledged
