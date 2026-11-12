"""Real orchestration with fake backends: delegation never bills an external model (§10.3)."""

from __future__ import annotations

import asyncio
import json

import pytest
from sqlmodel import select

from voxarium_studio.models.subagents import SubagentMessage, TaskSubagent
from voxarium_studio.models.teams import AgentMessage, TeamMember, TeamTask, TaskStatus
from voxarium_studio.teams import effort_solver_runner, subagents as subagents_module
from voxarium_studio.teams.backends import base
from voxarium_studio.teams.backends.base import AgentEvent


TEAM = "team_default"
MEMBER = f"{TEAM}__dev"
SOLVER = f"{TEAM}__studio-effort-solver"
# 1 + (1.5*1 + 1.5*1 + 3*2 + 2.5*7) / 19.125 rounds to 2: "medium", which is neither the
# parent's "high" nor a model default, so an inherited effort cannot pass for a computed one.
SCORES = {"complexity": 2, "creativity": 2, "depth": 3, "coverage": 3}


class SolverBackend:
    """Effort Solver's model call (§10.11), answered without billing anything."""

    def __init__(self):
        self.requests = []
        self.answer = json.dumps(SCORES)
        self.error = None
        self.result_data = {}

    def is_available(self):
        return True, "test backend"

    async def run(self, request):
        self.requests.append(request)
        if self.error:
            yield AgentEvent(kind="error", text=self.error)
            return
        yield AgentEvent(kind="result", text=self.answer, data=self.result_data, is_final=True)


@pytest.fixture
def solver(monkeypatch):
    backend = SolverBackend()
    monkeypatch.setattr(effort_solver_runner, "get_backend", lambda _name: backend)
    return backend


def result(text="Done", *, tokens=0, cost=0.0, session="parent-session", **data):
    return AgentEvent(kind="result", text=text, session_id=session, is_final=True, data={
        "total_tokens": tokens, "total_cost_usd": cost, "num_turns": 1, **data,
    })


async def load(ctx, task_id):
    async with ctx.db.session() as session:
        return await session.get(TeamTask, task_id)


async def children(ctx, task_id):
    async with ctx.db.session() as session:
        return list((await session.exec(select(TaskSubagent).where(TaskSubagent.task_id == task_id))).all())


async def messages(ctx, task_id):
    async with ctx.db.session() as session:
        return list((await session.exec(select(AgentMessage).where(
            AgentMessage.task_id == task_id,
        ).order_by(AgentMessage.created_at, AgentMessage.id))).all())


@pytest.fixture
async def runner(ctx, monkeypatch, solver):
    ctx.config.teams.context_compression_tokens = 0
    ctx.config.teams.result_follow_up = False

    def install(name, action):
        class Backend:
            named_agents = False

            def is_available(self):
                return True, "test backend"

            def run(self, request):
                return action(request)

        monkeypatch.setitem(base._BACKENDS, name, Backend)

    async def start(action, *, slots=1, backend="test_subagent_parent"):
        install(backend, action)
        ctx.jobs.update_limits({"agent": slots})
        async with ctx.db.session() as session:
            member = await session.get(TeamMember, MEMBER)
            member.agent_backend = backend
            member.model = "parent-model"
            member.effort = "high"
            member.isolate_worktree = False
            member.permission_mode = "plan"
            session.add(member)
        task = await ctx.teams.create_task(
            team_id=TEAM, assignee_id=MEMBER, title="Split task", brief="Implement and verify.",
            auto_start=False, write_paths=["source.py"],
        )
        await ctx.teams.update_task(task.id, {"status": TaskStatus.PENDING})
        await ctx.teams.start_task(task.id)
        return task, ctx.jobs.get(task.id).task

    return ctx, install, start


async def test_any_backend_autojoins_with_own_session_context_and_aggregate_usage(runner):
    ctx, install, start = runner
    ctx.config.teams.permission_mode = "acceptEdits"
    ctx.config.teams.max_turns = 67
    parent_requests, child_requests = [], []

    async def child(request):
        child_requests.append(request)
        yield AgentEvent(kind="system", text="usage_update", session_id="child-session", data={
            "subtype": "usage_update", "total_tokens": 40, "context_tokens": 900,
            "total_uncached_tokens": 10, "total_cached_tokens": 30,
            "total_input_tokens": 7, "total_output_tokens": 3,
            "context_window": 9999, "total_cost_usd": 0.02,
        })
        yield AgentEvent(kind="tool_use", text="Read", data={"name": "Read"})
        yield result("Child findings", tokens=100, cost=0.05, session="child-session",
                     total_uncached_tokens=20, total_cached_tokens=80,
                     total_input_tokens=12, total_output_tokens=8)

    install("test_other_backend", child)

    async def parent(request):
        parent_requests.append(request)
        if len(parent_requests) == 1:
            await ctx.teams.subagents.create(
                request.task.id, MEMBER, description="Inspect implementation", prompt="Read source.py",
                backend="test_other_backend", model="child-model",
            )
            yield result("Waiting for findings", tokens=10, cost=0.01,
                         total_uncached_tokens=4, total_cached_tokens=6,
                         total_input_tokens=3, total_output_tokens=1)
        else:
            assert "Child findings" in request.prompt
            yield AgentEvent(kind="system", text="usage_update", data={
                "subtype": "usage_update", "total_tokens": 20, "context_tokens": 45,
                "total_uncached_tokens": 7, "total_cached_tokens": 13,
                "total_input_tokens": 5, "total_output_tokens": 2,
                "context_window": 2000,
            })
            yield result("Integrated findings", tokens=20, cost=0.02,
                         total_uncached_tokens=7, total_cached_tokens=13,
                         total_input_tokens=5, total_output_tokens=2)

    task, job = await start(parent)
    await asyncio.wait_for(job, 8)
    saved = await load(ctx, task.id)
    rows = await children(ctx, task.id)
    assert saved.status == TaskStatus.NEEDS_REVIEW
    assert saved.result_summary == "Integrated findings"
    assert saved.agent_session_id == "parent-session"
    assert saved.total_tokens == 130
    assert (saved.total_uncached_tokens, saved.total_cached_tokens) == (31, 99)
    assert (saved.total_input_tokens, saved.total_output_tokens) == (20, 11)
    assert saved.total_cost_usd == pytest.approx(0.08)
    assert saved.num_turns == 3
    assert len(parent_requests) == 2 and len(child_requests) == 1
    assert parent_requests[1].resume_session_id == "parent-session"
    worker, parent_request = child_requests[0], parent_requests[0]
    assert all(req.proactive_subagents for req in parent_requests)
    assert not worker.proactive_subagents
    assert worker.session_key == rows[0].id and worker.session_key != task.id
    assert worker.model == "child-model" and worker.member.agent_backend == "test_other_backend"
    # §10.11: another backend's worker gets the solver's effort too, not a model default.
    assert worker.effort == worker.member.effort == rows[0].effort == "medium"
    assert worker.member.permission_mode == parent_request.member.permission_mode
    assert worker.permission_mode == parent_request.permission_mode == "acceptEdits"
    assert worker.max_turns == parent_request.max_turns == 67
    assert worker.workdir == parent_request.workdir
    assert worker.writable_roots == parent_request.writable_roots
    assert "source.py" in worker.system_prompt
    assert "mcp__voxarium_studio__finish_task" not in worker.extra_allowed_tools
    assert "mcp__voxarium_studio__spawn_subagent" not in worker.extra_allowed_tools
    assert rows[0].status == "completed" and rows[0].session_id == "child-session"
    assert rows[0].total_tokens == 100 and rows[0].total_cost_usd == pytest.approx(0.05)
    assert (rows[0].total_uncached_tokens, rows[0].total_cached_tokens) == (20, 80)
    assert (rows[0].total_input_tokens, rows[0].total_output_tokens) == (12, 8)
    assert rows[0].tool_uses == 1
    feed = await messages(ctx, task.id)
    parent_results = [message for message in feed if message.kind == "result"]
    assert len(parent_results) == 2
    assert parent_results[0].data["continued"] is True
    assert not parent_results[1].data.get("continued")
    snapshot = next(message for message in feed if message.text == "usage_update")
    assert snapshot.data["context_tokens"] == 45
    assert snapshot.data["task_total_tokens"] == 130
    assert snapshot.data["task_total_uncached_tokens"] == 31
    assert snapshot.data["task_total_cached_tokens"] == 99
    assert (snapshot.data["task_total_input_tokens"], snapshot.data["task_total_output_tokens"]) == (20, 11)
    child_progress = [message for message in feed if message.text == "task_progress"][-1]
    assert child_progress.data["usage"]["total_input_tokens"] == 12
    assert child_progress.data["usage"]["total_output_tokens"] == 8
    assert not ctx.teams.subagents.runs and not ctx.jobs.is_running(rows[0].id)


async def test_spawn_runs_the_worker_on_the_effort_solvers_choice(runner, solver):
    ctx, _, start = runner
    usage = {"input_tokens": 10, "cache_creation_input_tokens": 5,
             "cache_read_input_tokens": 7, "output_tokens": 3}
    solver.result_data = {"usage": usage, "total_tokens": 25, "total_cost_usd": 0.0123, "num_turns": 1}
    child_requests, spawned, listed = [], [], []

    async def backend(request):
        if request.session_key != request.task.id:
            child_requests.append(request)
            yield result("Reviewed", tokens=100, cost=0.05, session="worker-session")
            return
        # The caller has no effort to pass, and the parent's own ("high") is not inherited.
        child = await ctx.teams.subagents.create(
            request.task.id, MEMBER, description="Review parser", prompt="Read parser.py and report bugs.",
        )
        spawned.append(child)
        listed.extend(await ctx.teams.subagents.list(request.task.id, MEMBER))
        await ctx.teams.subagents.wait(request.task.id, MEMBER, child["id"], timeout=3)
        await ctx.teams.subagents.send(request.task.id, MEMBER, child["id"], "Check one more case")
        await ctx.teams.subagents.wait(request.task.id, MEMBER, timeout=3)
        yield result("Integrated", tokens=10, cost=0.01)

    task, job = await start(backend)
    await asyncio.wait_for(job, 8)
    saved = await load(ctx, task.id)
    assert saved.status == TaskStatus.NEEDS_REVIEW
    assert spawned[0]["effort"] == "medium"
    # The spawn's answer carries the evaluation the transcript draws in the call row.
    assert spawned[0]["effort_solver"] == {"scores": SCORES, "effort": 2, "effort_level": "medium"}
    assert [row["effort"] for row in listed] == ["medium"]
    assert "effort_solver" not in listed[0]
    assert [request.effort for request in child_requests] == ["medium", "medium"]
    assert all(request.member.effort == "medium" for request in child_requests)
    # One evaluation per spawn, over the label and the assignment alone; a follow-up
    # turn keeps the effort its worker was started with.
    assert len(solver.requests) == 1
    assert solver.requests[0].member.id == SOLVER
    assert json.loads(solver.requests[0].prompt) == {
        "title": "Review parser", "description": "Read parser.py and report bugs.",
    }
    row = (await children(ctx, task.id))[0]
    assert row.effort == "medium"
    frames = [message for message in await messages(ctx, task.id)
              if message.kind == "system" and message.data.get("task_id") == row.id]
    assert {"task_started", "task_notification"} <= {frame.text for frame in frames}
    assert all(frame.data["effort"] == "medium" for frame in frames)
    # The evaluation is the parent task's spend and stays out of the worker's counters.
    assert row.total_tokens == 200 and row.total_cost_usd == pytest.approx(0.10)
    assert saved.total_tokens == 200 + 10 + 25
    assert saved.total_cost_usd == pytest.approx(0.10 + 0.01 + 0.0123)
    assert (saved.total_uncached_tokens, saved.total_cached_tokens) == (18, 7)
    assert (saved.total_input_tokens, saved.total_output_tokens) == (15, 3)
    assert saved.num_turns == 4


@pytest.mark.parametrize("failure", ["invalid_json", "out_of_range", "backend_error"])
async def test_solver_failure_starts_no_worker_and_substitutes_no_effort(runner, solver, failure):
    ctx, _, start = runner
    if failure == "invalid_json":
        solver.answer = "This looks like a simple task."
    elif failure == "out_of_range":
        solver.answer = json.dumps({**SCORES, "depth": 11})
    else:
        solver.error = "Solver unavailable"
    child_started = False
    errors = []

    async def backend(request):
        nonlocal child_started
        if request.session_key != request.task.id:
            child_started = True
            yield result("Should not run", tokens=99)
            return
        try:
            await ctx.teams.subagents.create(request.task.id, MEMBER, description="Review", prompt="Read")
        except (ValueError, RuntimeError) as exc:
            errors.append(exc)
        yield result("Did the work without a worker", tokens=5)

    task, job = await start(backend)
    await asyncio.wait_for(job, 8)
    assert len(errors) == 1 and len(solver.requests) == 1
    assert not child_started and await children(ctx, task.id) == []
    assert not [message for message in await messages(ctx, task.id) if message.text == "task_started"]
    saved = await load(ctx, task.id)
    assert saved.status == TaskStatus.NEEDS_REVIEW and saved.total_tokens == 5


async def test_a_launch_the_limit_refuses_is_not_sent_to_the_solver(runner, solver):
    ctx, _, start = runner
    ctx.config.teams.max_delegated_tasks_per_task = 1
    refused = []

    async def backend(request):
        if request.session_key != request.task.id:
            yield result("Reviewed", session="worker-session")
            return
        await ctx.teams.subagents.create(request.task.id, MEMBER, description="First", prompt="Read")
        try:
            await ctx.teams.subagents.create(request.task.id, MEMBER, description="Second", prompt="Read")
        except ValueError as exc:
            refused.append(str(exc))
        await ctx.teams.subagents.wait(request.task.id, MEMBER, timeout=3)
        yield result("Budget respected")

    task, job = await start(backend)
    await asyncio.wait_for(job, 8)
    assert len(refused) == 1 and "launch limit" in refused[0]
    assert len(solver.requests) == 1
    assert len(await children(ctx, task.id)) == 1


async def test_cancel_during_evaluation_leaves_no_worker_and_releases_the_run(runner, monkeypatch):
    ctx, _, start = runner
    evaluating = asyncio.Event()

    async def wait_for_solver(*_args, **_kwargs):
        evaluating.set()
        await asyncio.Event().wait()

    monkeypatch.setattr(subagents_module, "solve_effort", wait_for_solver)

    async def backend(request):
        await ctx.teams.subagents.create(request.task.id, MEMBER, description="Spawn", prompt="Work")
        yield result()

    task, job = await start(backend)
    await asyncio.wait_for(evaluating.wait(), 5)
    # The evaluation holds no run lock, so stopping the parent does not wait for it.
    await asyncio.wait_for(ctx.teams.cancel_task(task.id), 5)
    with pytest.raises(asyncio.CancelledError):
        await asyncio.wait_for(job, 5)
    assert await children(ctx, task.id) == []
    assert not ctx.teams.subagents.runs


async def test_subagent_receives_required_materials_with_its_first_message(runner):
    ctx, install, start = runner
    folder = ctx.paths.skills / "mandatory"
    folder.mkdir(parents=True)
    (folder / "SKILL.md").write_text("# Required\nAPPLY ALL STEPS", encoding="utf-8")
    ctx.paths.docs.mkdir(parents=True, exist_ok=True)
    (ctx.paths.docs / "mandatory.md").write_text("COMPLETE DOCUMENT", encoding="utf-8")
    ctx.config.teams.required_skills = ["mandatory"]
    ctx.config.teams.required_documents = ["mandatory.md"]
    child_requests = []

    async def child(request):
        child_requests.append(request)
        yield result("Child findings", session="child-requirements")

    install("test_required_worker", child)
    calls = 0

    async def parent(request):
        nonlocal calls
        calls += 1
        if calls == 1:
            await ctx.teams.subagents.create(request.task.id, MEMBER,
                description="Inspect with required context", prompt="Inspect files",
                backend="test_required_worker", model="worker-model")
        yield result("Waiting" if calls == 1 else "Complete")

    _, job = await start(parent)
    await asyncio.wait_for(job, 8)
    assert len(child_requests) == 1
    child_request = child_requests[0]
    assert child_request.resume_session_id is None
    assert child_request.prompt.count("APPLY ALL STEPS") == 1
    assert child_request.prompt.count("COMPLETE DOCUMENT") == 1
    assert child_request.recovery_prompt.count("COMPLETE DOCUMENT") == 1


async def test_wait_then_followup_works_with_one_slot_and_counts_each_turn_once(runner):
    ctx, _, start = runner
    child_requests = []
    parent_calls = 0

    async def backend(request):
        nonlocal parent_calls
        if request.session_key != request.task.id:
            child_requests.append(request)
            tokens = 100 if len(child_requests) == 1 else 50
            yield AgentEvent(kind="text", text="Progress", data={
                "usage": {"input_tokens": tokens // 2, "output_tokens": 0},
            })
            yield result("Child turn finished", tokens=tokens, cost=0.03, session="worker-session")
            return
        parent_calls += 1
        child = await ctx.teams.subagents.create(
            request.task.id, MEMBER, description="Inspect", prompt="Inspect source.py",
        )
        finished = await ctx.teams.subagents.wait(request.task.id, MEMBER, child["id"], timeout=3)
        assert finished[0]["status"] == "completed"
        await ctx.teams.subagents.send(request.task.id, MEMBER, child["id"], "Inspect another case")
        finished = await ctx.teams.subagents.wait(request.task.id, MEMBER, timeout=3)
        assert finished[0]["status"] == "completed" and finished[0]["total_tokens"] == 150
        yield result("All results reviewed", tokens=10, cost=0.01)

    task, job = await start(backend)
    await asyncio.wait_for(job, 8)
    saved = await load(ctx, task.id)
    assert saved.status == TaskStatus.NEEDS_REVIEW
    assert parent_calls == 1 and len(child_requests) == 2
    assert child_requests[0].resume_session_id is None
    assert child_requests[1].resume_session_id == "worker-session"
    assert child_requests[1].prompt == "Inspect another case"
    assert saved.total_tokens == 160 and saved.total_cost_usd == pytest.approx(0.07)


async def test_stop_queued_child_does_not_start_backend_or_leave_it_running(runner):
    ctx, _, start = runner
    child_started = False

    async def backend(request):
        nonlocal child_started
        if request.session_key != request.task.id:
            child_started = True
            yield result("Should not run", tokens=99)
            return
        child = await ctx.teams.subagents.create(request.task.id, MEMBER, description="Queued", prompt="Work")
        stopped = await ctx.teams.subagents.stop(request.task.id, MEMBER, child["id"])
        assert stopped["status"] == "stopped"
        assert stopped["total_tokens"] == 0
        yield result("Unneeded worker stopped", tokens=5)

    task, job = await start(backend)
    await asyncio.wait_for(job, 8)
    assert not child_started
    assert (await load(ctx, task.id)).status == TaskStatus.NEEDS_REVIEW
    assert (await children(ctx, task.id))[0].status == "stopped"


async def test_child_writes_during_autojoin_are_observed(runner):
    ctx, _, start = runner
    calls = 0

    async def backend(request):
        nonlocal calls
        if request.session_key != request.task.id:
            (request.workdir / "source.py").write_text("child implementation\n", encoding="utf-8")
            yield result("Created source.py", session="child")
            return
        calls += 1
        if calls == 1:
            await ctx.teams.subagents.create(request.task.id, MEMBER, description="Implement", prompt="Write source.py")
        yield result("Reviewed" if calls > 1 else "Waiting")

    task, job = await start(backend, slots=1)
    await asyncio.wait_for(job, 8)
    observed = [path for message in await messages(ctx, task.id)
                if message.kind == "file_observation" for path in message.data.get("paths", [])]
    assert "source.py" in observed


async def test_cancel_during_spawn_closes_persisted_row_before_job_registration(runner, monkeypatch):
    ctx, _, start = runner
    persisted = asyncio.Event()
    emit = ctx.teams.subagents._emit

    async def hold_start(row, subtype, **kwargs):
        if subtype == "task_started":
            persisted.set()
            await asyncio.Event().wait()
        await emit(row, subtype, **kwargs)

    monkeypatch.setattr(ctx.teams.subagents, "_emit", hold_start)

    async def backend(request):
        await ctx.teams.subagents.create(request.task.id, MEMBER, description="Spawn", prompt="Work")
        yield result()

    task, job = await start(backend)
    await asyncio.wait_for(persisted.wait(), 5)
    await ctx.teams.cancel_task(task.id)
    with pytest.raises(asyncio.CancelledError):
        await asyncio.wait_for(job, 5)
    assert (await children(ctx, task.id))[0].status == "stopped"
    assert not ctx.teams.subagents.runs


async def test_parallel_workers_reconcile_live_and_final_spending_without_lost_updates(runner):
    ctx, _, start = runner
    calls = 0

    async def backend(request):
        nonlocal calls
        if request.session_key != request.task.id:
            for total in (10, 30, 70):
                yield AgentEvent(kind="system", text="usage_update", data={
                    "subtype": "usage_update", "total_tokens": total, "total_cost_usd": total / 1000,
                })
                await asyncio.sleep(0)
            yield result("Child complete", tokens=100, cost=0.1, session=request.session_key)
            return
        calls += 1
        for label in ("First", "Second"):
            await ctx.teams.subagents.create(request.task.id, MEMBER, description=label, prompt="Work")
        yield AgentEvent(kind="text", text="Parent working", data={"usage": {"input_tokens": 5}})
        await ctx.teams.subagents.wait(request.task.id, MEMBER, timeout=5)
        yield result("Integrated", tokens=10, cost=0.03)

    task, job = await start(backend, slots=3)
    await asyncio.wait_for(job, 8)
    saved = await load(ctx, task.id)
    assert calls == 1
    assert saved.total_tokens == 210
    assert saved.total_cost_usd == pytest.approx(0.23)
    assert sum(row.total_tokens for row in await children(ctx, task.id)) == 200


@pytest.mark.parametrize("waiting", [False, True])
async def test_parent_cancellation_joins_child_and_preserves_live_spend(runner, waiting):
    ctx, _, start = runner
    child_ready = asyncio.Event()
    child_stopped = asyncio.Event()

    async def backend(request):
        if request.session_key != request.task.id:
            try:
                yield AgentEvent(kind="system", text="usage_update", data={
                    "subtype": "usage_update", "total_tokens": 70, "total_cost_usd": 0.04,
                })
                child_ready.set()
                await asyncio.Event().wait()
            finally:
                child_stopped.set()
            return
        child = await ctx.teams.subagents.create(request.task.id, MEMBER, description="Long-running", prompt="Work")
        if waiting:
            await ctx.teams.subagents.wait(request.task.id, MEMBER, child["id"], timeout=60)
        else:
            await asyncio.Event().wait()
        yield result()

    task, job = await start(backend, slots=1 if waiting else 2)
    await asyncio.wait_for(child_ready.wait(), 8)
    assert await ctx.teams.cancel_task(task.id)
    with pytest.raises(asyncio.CancelledError):
        await asyncio.wait_for(job, 8)
    saved = await load(ctx, task.id)
    child = (await children(ctx, task.id))[0]
    assert child_stopped.is_set() and child.status == "stopped"
    assert saved.status == TaskStatus.PENDING
    assert saved.total_tokens == child.total_tokens == 70
    assert saved.total_cost_usd == child.total_cost_usd == pytest.approx(0.04)
    assert not ctx.teams.subagents.runs and not ctx.jobs.is_running(child.id)
    async with asyncio.timeout(1), ctx.jobs.slot("agent"):
        if not waiting:
            async with ctx.jobs.slot("agent"):
                pass


async def test_parallel_launches_and_followups_share_per_run_budget(runner):
    ctx, _, start = runner
    ctx.config.teams.max_delegated_tasks_per_task = 1

    async def backend(request):
        if request.session_key != request.task.id:
            yield result("Reviewed", tokens=10, session="worker-session")
            return
        outcomes = await asyncio.gather(*(
            ctx.teams.subagents.create(request.task.id, MEMBER, description=f"Worker {n}", prompt="Read")
            for n in range(2)
        ), return_exceptions=True)
        assert sum(isinstance(item, dict) for item in outcomes) == 1
        assert sum(isinstance(item, ValueError) for item in outcomes) == 1
        child = next(item for item in outcomes if isinstance(item, dict))
        await ctx.teams.subagents.wait(request.task.id, MEMBER, timeout=3)
        with pytest.raises(ValueError, match="launch limit"):
            await ctx.teams.subagents.send(request.task.id, MEMBER, child["id"], "One more turn")
        yield result("Budget respected", tokens=5)

    task, job = await start(backend)
    await asyncio.wait_for(job, 8)
    assert (await load(ctx, task.id)).status == TaskStatus.NEEDS_REVIEW
    assert len(await children(ctx, task.id)) == 1


async def test_worker_failure_is_reported_to_parent_without_overwriting_parent_result(runner):
    ctx, _, start = runner
    parent_calls = 0

    async def backend(request):
        nonlocal parent_calls
        if request.session_key != request.task.id:
            yield AgentEvent(kind="error", text="Worker tool failed", session_id="failed-worker")
            yield result("Worker tool failed", tokens=25, cost=0.01, session="failed-worker", is_error=True)
            return
        parent_calls += 1
        if parent_calls == 1:
            await ctx.teams.subagents.create(request.task.id, MEMBER, description="May fail", prompt="Try")
            yield result("Await worker", tokens=5)
        else:
            assert "Worker tool failed" in request.prompt and '"status": "failed"' in request.prompt
            yield result("Parent handled the failure", tokens=5)

    task, job = await start(backend)
    await asyncio.wait_for(job, 8)
    saved = await load(ctx, task.id)
    assert saved.status == TaskStatus.NEEDS_REVIEW and saved.error is None
    assert saved.result_summary == "Parent handled the failure"
    assert saved.agent_session_id == "parent-session"
    assert saved.total_tokens == 35 and saved.total_cost_usd == pytest.approx(0.01)
    assert (await children(ctx, task.id))[0].status == "failed"


@pytest.mark.parametrize("operation", ["reset", "delete"])
async def test_restart_stops_orphans_and_reset_delete_remove_child_history(ctx, operation):
    task = await ctx.teams.create_task(team_id=TEAM, assignee_id=MEMBER, title="Old run", brief="Work")
    row = TaskSubagent(
        task_id=task.id, member_id=MEMBER, description="Interrupted", backend="codex", model="model",
        status="running", total_tokens=20, total_cost_usd=0.02,
    )
    async with ctx.db.session() as session:
        session.add(row)
        await session.flush()
        session.add(SubagentMessage(subagent_id=row.id, kind="text", text="Saved work"))
    await ctx.teams.subagents.recover()
    recovered = (await children(ctx, task.id))[0]
    assert recovered.status == "stopped" and recovered.total_tokens == 20
    assert recovered.total_cost_usd == pytest.approx(0.02)
    assert any(message.text == "task_notification" for message in await messages(ctx, task.id))
    await getattr(ctx.teams, f"{operation}_task")(task.id)
    assert await children(ctx, task.id) == []
    async with ctx.db.session() as session:
        assert (await session.exec(select(SubagentMessage).where(SubagentMessage.subagent_id == row.id))).all() == []
    if operation == "reset":
        saved = await load(ctx, task.id)
        assert saved.status == TaskStatus.TODO and saved.total_tokens == 0
    else:
        assert await load(ctx, task.id) is None
