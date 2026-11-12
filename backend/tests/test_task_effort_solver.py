"""A task left on "Auto" effort is scored by Effort Solver when its run starts (§10.3, §10.11)."""
from __future__ import annotations

import json

import pytest
from sqlmodel import select

from voxarium_studio.core.context import AppContext
from voxarium_studio.models.teams import AgentMessage, TaskStatus, TeamMember
from voxarium_studio.teams import effort_solver_runner
from voxarium_studio.teams.backends.base import AgentEvent, register_backend

from test_teams import DEV, TEAM, load, release, teams  # noqa: F401 — `teams` is a fixture

SCORES = {"complexity": 9, "creativity": 6, "depth": 8, "coverage": 8}  # → 3, "high"


class Solver:
    """The Solver's backend: records what it was asked and answers with fixed scores."""

    def __init__(self) -> None:
        self.requests: list = []
        self.error: str | None = None

    def is_available(self):
        return True, "solver"

    async def run(self, request):
        self.requests.append(request)
        if self.error:
            yield AgentEvent(kind="error", text=self.error)
            return
        answer = json.dumps(SCORES)
        yield AgentEvent(kind="text", text=answer)
        yield AgentEvent(kind="result", text=answer, is_final=True,
                         data={"total_cost_usd": 0.5, "num_turns": 1, "total_tokens": 100})


class Worker:
    """The assignee's backend: remembers the effort each run was handed."""

    name = "effort_worker"
    efforts: list[str | None] = []

    def is_available(self):
        return True, "worker"

    async def run(self, request):
        Worker.efforts.append(request.effort)
        yield AgentEvent(kind="result", text="Done.", session_id="sess_auto", is_final=True,
                         data={"total_cost_usd": 0.25, "num_turns": 2})


class NoEffortWorker(Worker):
    """A runtime that ignores Studio effort, as Qwen Code does."""

    name = "effort_worker_qwenlike"
    supports_effort = False


register_backend("effort_worker", Worker)
register_backend("effort_worker_qwenlike", NoEffortWorker)


@pytest.fixture
def solver(monkeypatch) -> Solver:
    backend = Solver()
    monkeypatch.setattr(effort_solver_runner, "get_backend", lambda _name: backend)
    return backend


async def _dev(ctx: AppContext, *, backend: str = "effort_worker", effort: str | None = None) -> None:
    Worker.efforts.clear()
    async with ctx.db.session() as session:
        member = await session.get(TeamMember, DEV)
        member.agent_backend = backend
        member.effort = effort
        session.add(member)


async def _run(ctx: AppContext, wait_for, *, effort: str | None = None):
    task = await ctx.teams.create_task(
        team_id=TEAM, assignee_id=DEV, title="Implement movement",
        brief="Arrow keys move the player.", effort=effort, auto_start=True,
    )
    await release(ctx, task.id)
    await wait_for(task.id, timeout=20)
    return await load(ctx, task.id)


async def _messages(ctx: AppContext, task_id: str, kind: str) -> list[AgentMessage]:
    async with ctx.db.session() as session:
        return list((await session.exec(
            select(AgentMessage).where(AgentMessage.task_id == task_id, AgentMessage.kind == kind)
        )).all())


async def test_auto_effort_is_solved_at_start_and_kept_on_the_task(
    teams: AppContext, wait_for, solver: Solver,  # noqa: F811
) -> None:
    await _dev(teams)
    task = await _run(teams, wait_for)

    assert task.status == TaskStatus.NEEDS_REVIEW
    assert Worker.efforts == ["high"]
    assert task.effort == "high", "stored as the task's override, like the Producer's Auto"
    assert len(solver.requests) == 1
    assert json.loads(solver.requests[0].prompt) == {
        "title": "Implement movement", "description": "Arrow keys move the player.",
    }
    [scored] = await _messages(teams, task.id, "effort_solver")
    assert scored.data == {"scores": SCORES, "effort": 3, "effort_level": "high"}
    assert task.total_cost_usd == pytest.approx(0.75), "the evaluation is the task's spend"
    assert task.num_turns == 2, "the Solver's turn is not a turn of the task's session"


async def test_a_resumed_run_keeps_the_solved_effort(
    teams: AppContext, wait_for, solver: Solver,  # noqa: F811
) -> None:
    await _dev(teams)
    task = await _run(teams, wait_for)
    await teams.teams.request_changes(task.id, "Once more.")
    await wait_for(task.id, timeout=20)

    assert Worker.efforts == ["high", "high"]
    assert len(solver.requests) == 1


@pytest.mark.parametrize("member_effort, task_effort, expected", [
    ("low", None, "low"),
    (None, "max", "max"),
    ("low", "medium", "medium"),
])
async def test_a_chosen_effort_is_never_sent_to_the_solver(
    teams: AppContext, wait_for, solver: Solver,  # noqa: F811
    member_effort, task_effort, expected,
) -> None:
    await _dev(teams, effort=member_effort)
    task = await _run(teams, wait_for, effort=task_effort)

    assert Worker.efforts == [expected]
    assert solver.requests == []
    assert task.effort == task_effort
    assert await _messages(teams, task.id, "effort_solver") == []


async def test_a_runtime_without_effort_is_not_scored(
    teams: AppContext, wait_for, solver: Solver,  # noqa: F811
) -> None:
    await _dev(teams, backend="effort_worker_qwenlike")
    task = await _run(teams, wait_for)

    assert Worker.efforts == [None]
    assert solver.requests == []
    assert task.effort is None


async def test_solver_failure_blocks_the_run_without_a_substituted_effort(
    teams: AppContext, wait_for, solver: Solver,  # noqa: F811
) -> None:
    await _dev(teams)
    solver.error = "model unavailable"
    task = await _run(teams, wait_for)

    assert task.status == TaskStatus.BLOCKED
    assert "Effort Solver" in (task.error or "")
    assert Worker.efforts == [], "no session starts on a default effort"
    assert task.effort is None, "the next start asks the Solver again"
