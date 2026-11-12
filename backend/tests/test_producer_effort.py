"""Producer effort mode is persisted and enforced before a task exists (§10.11)."""
from __future__ import annotations

import asyncio
import json

import pytest
from sqlalchemy import text
from sqlmodel import select

from voxarium_studio.core.db import Database
from voxarium_studio.models.teams import AgentChat, TeamMember, TeamTask
from voxarium_studio.teams.backends.base import AgentEvent
from voxarium_studio.teams.chat import ChatBusy
from voxarium_studio.teams import effort_solver_runner, orchestrator as orchestrator_module
from voxarium_studio.teams.tools import build_toolset

from test_producer import DEV, PRODUCER, TEAM, ProducerBackend, allow, finished, opened, producer, tools_for


SCORES = {"complexity": 9, "creativity": 6, "depth": 8, "coverage": 8}
SOLVER = f"{TEAM}__studio-effort-solver"


class SolverBackend:
    def __init__(self):
        self.requests = []
        self.answer = json.dumps(SCORES)
        self.error = None
        self.assistant_data = {}
        self.result_data = {}

    def is_available(self):
        return True, "test backend"

    async def run(self, request):
        self.requests.append(request)
        if self.error:
            yield AgentEvent(kind="error", text=self.error)
            return
        yield AgentEvent(kind="thinking", text="Private assessment", data=self.assistant_data)
        yield AgentEvent(kind="text", text=self.answer, data=self.assistant_data)
        yield AgentEvent(kind="result", text=self.answer, data=self.result_data, is_final=True)


@pytest.fixture
def solver(monkeypatch):
    backend = SolverBackend()
    monkeypatch.setattr(effort_solver_runner, "get_backend", lambda _name: backend)
    return backend


def test_effort_mode_api_defaults_persists_and_validates_without_starting(client):
    chat = client.post("/api/chats", json={"member_id": PRODUCER, "producer_mode": True}).json()
    path = f'/api/chats/{chat["id"]}/producer/effort'
    assert chat["producer_effort_mode"] == "agent"
    saved = client.patch(path, json={"mode": "auto"})
    assert saved.status_code == 200
    assert saved.json()["producer_effort_mode"] == "auto"
    assert not saved.json()["producer_started"] and not saved.json()["is_running"]
    assert client.get(f'/api/chats/{chat["id"]}').json()["producer_effort_mode"] == "auto"
    for value in ("", "high", None, 1):
        assert client.patch(path, json={"mode": value}).status_code == 422
    assert client.patch(path, json={}).status_code == 422
    assert client.patch('/api/chats/missing/producer/effort', json={"mode": "auto"}).status_code == 404
    other = client.post("/api/chats", json={"member_id": DEV}).json()
    assert client.patch(f'/api/chats/{other["id"]}/producer/effort',
                        json={"mode": "auto"}).status_code == 409


async def test_effort_mode_migration_defaults_existing_chats_to_agent(ctx):
    await ctx.chats.producer.shutdown()
    chat = await ctx.chats.create_chat(PRODUCER)
    async with ctx.db._engine.begin() as conn:
        await conn.execute(text("ALTER TABLE agent_chat DROP COLUMN producer_effort_mode"))
    reopened = Database(ctx.db.path)
    try:
        await reopened.create_all()
        async with reopened.session() as session:
            assert (await session.get(AgentChat, chat.id)).producer_effort_mode == "agent"
    finally:
        await reopened.close()


async def test_producer_schema_leaves_effort_to_solver_or_assignee(producer):
    chat = await opened(producer)
    async with producer.db.session() as session:
        member = await session.get(TeamMember, PRODUCER)
    for mode in ("agent", "auto"):
        toolset = build_toolset(producer.teams, member=member, producer=producer.chats.producer,
                               chat_id=chat.id, producer_effort_mode=mode)
        schema = next(tool for tool in toolset.definitions if tool.name == "create_task").input_schema
        assert "effort" not in schema["required"]
        assert "effort" not in schema["properties"]
    ordinary = build_toolset(producer.teams, member=member, producer_effort_mode="auto")
    schema = next(tool for tool in ordinary.definitions if tool.name == "create_task").input_schema
    assert "effort" not in schema["required"]


@pytest.mark.parametrize("choice", [{}, {"effort": None}, {"effort": ""}, {"effort": "unknown"},
                                    {"effort": 1}, {"effort": []}, {"effort": "low"}])
async def test_auto_uses_solver_even_for_stale_toolsets_and_explicit_choices(producer, solver, choice):
    chat = await opened(producer)
    # The saved mode governs creation even when this toolset predates Auto.
    tools = await tools_for(producer, chat)
    await producer.chats.producer.set_effort_mode(chat.id, "auto")
    async with producer.db.session() as session:
        member = await session.get(TeamMember, DEV)
        member.effort = "high"
        session.add(member)
    result = await tools["create_task"]({"assignee_id": DEV, "title": "Implement movement",
                                         "brief": "Arrow keys move the player.", **choice})
    assert not result.get("is_error"), result
    async with producer.db.session() as session:
        task = (await session.exec(select(TeamTask))).one()
    assert task.effort == "high"
    assert len(solver.requests) == 1
    assert solver.requests[0].member.id == SOLVER
    assert "Implement movement" in solver.requests[0].prompt
    assert "Arrow keys move the player." in solver.requests[0].prompt
    messages = [message for message in await producer.chats.messages(chat.id)
                if message.kind == "effort_solver"]
    assert len(messages) == 1
    assert messages[0].text == ""
    assert messages[0].data["scores"] == SCORES
    assert messages[0].data["effort"] == 3
    assert messages[0].data["effort_level"] == "high"
    assert all("Private assessment" not in message.text for message in await producer.chats.messages(chat.id))


@pytest.mark.parametrize("mode, expected", [("agent", "medium"), ("auto", "high")])
async def test_task_execution_uses_agent_default_or_solver_effort(producer, solver, mode, expected):
    chat = await opened(producer)
    await producer.chats.producer.set_effort_mode(chat.id, mode)
    async with producer.db.session() as session:
        member = await session.get(TeamMember, DEV)
        # Not the level the solver computes, so the two sources stay distinguishable.
        member.effort = "medium"
        session.add(member)
    tools = await tools_for(producer, chat)
    result = await tools["create_task"]({"assignee_id": DEV, "title": "Implement movement",
                                         "brief": "Arrow keys move the player.", "effort": "low"})
    assert not result.get("is_error"), result
    async with producer.db.session() as session:
        task = (await session.exec(select(TeamTask))).one()
    assert task.effort == ("high" if mode == "auto" else None)
    assert len(solver.requests) == (1 if mode == "auto" else 0)
    await allow(producer, task)
    await producer.teams.start_task(task.id)
    await finished(producer, task.id)
    assert next(r for r in ProducerBackend.requests if r.session_key == task.id).effort == expected
    await producer.chats.producer.set_effort_mode(chat.id, "agent" if mode == "auto" else "auto")
    assert (await producer.teams._reload(task.id)).effort == task.effort


async def test_each_new_task_reads_the_solvers_current_weights(producer, solver):
    chat = await opened(producer)
    await producer.chats.producer.set_effort_mode(chat.id, "auto")
    tools = await tools_for(producer, chat)
    arguments = {"assignee_id": DEV, "title": "Implement movement", "brief": "Arrow keys move the player."}
    assert not (await tools["create_task"](arguments)).get("is_error")
    async with producer.db.session() as session:
        member = await session.get(TeamMember, SOLVER)
        member.effort_solver_weights = {"complexity": 0, "creativity": 0, "depth": 0, "coverage": 1}
        session.add(member)
    assert not (await tools["create_task"]({**arguments, "title": "Implement jumping"})).get("is_error")
    assert len(solver.requests) == 2
    async with producer.db.session() as session:
        tasks = list((await session.exec(select(TeamTask).order_by(TeamTask.number))).all())
    assert [task.effort for task in tasks] == ["high", "medium"]
    assert [message.data["effort"] for message in await producer.chats.messages(chat.id)
            if message.kind == "effort_solver"] == [3, 2]


async def test_successful_evaluations_add_usage_once_to_chat_without_exposing_raw_telemetry(producer, solver):
    chat = await opened(producer)
    await producer.chats.producer.set_effort_mode(chat.id, "auto")
    before = await producer.chats.get_chat(chat.id)
    usage = {"input_tokens": 10, "cache_creation_input_tokens": 5,
             "cache_read_input_tokens": 7, "output_tokens": 3}
    solver.assistant_data = {"provider_response_id": "solver-response", "usage": usage}
    solver.result_data = {"usage": usage, "total_tokens": 25, "total_cost_usd": 0.0123, "num_turns": 1}
    tools = await tools_for(producer, chat)
    for title in ("Implement movement", "Implement jumping"):
        result = await tools["create_task"]({"assignee_id": DEV, "title": title, "brief": "Instructions"})
        assert not result.get("is_error"), result
    after = await producer.chats.get_chat(chat.id)
    assert after.total_tokens == before.total_tokens + 50
    assert after.total_uncached_tokens == before.total_uncached_tokens + 36
    assert after.total_cached_tokens == before.total_cached_tokens + 14
    assert after.total_input_tokens == before.total_input_tokens + 30
    assert after.total_output_tokens == before.total_output_tokens + 6
    assert after.total_cost_usd == pytest.approx(before.total_cost_usd + 0.0246)
    assert after.num_turns == before.num_turns + 2
    messages = [message for message in await producer.chats.messages(chat.id) if message.kind == "effort_solver"]
    assert len(messages) == 2
    assert all(message.text == "" for message in messages)
    assert all(set(message.data) == {"scores", "effort", "effort_level"} for message in messages)


@pytest.mark.parametrize("failure", ["invalid_json", "out_of_range", "backend_error"])
async def test_solver_failure_leaves_no_task_or_success_block(producer, solver, failure):
    chat = await opened(producer)
    await producer.chats.producer.set_effort_mode(chat.id, "auto")
    if failure == "invalid_json":
        solver.answer = "I think this is a simple task."
    elif failure == "out_of_range":
        solver.answer = json.dumps({**SCORES, "depth": 11})
    else:
        solver.error = "Solver unavailable"
    result = await (await tools_for(producer, chat))["create_task"](
        {"assignee_id": DEV, "title": "Implement movement", "brief": "Arrow keys move the player."})
    assert result.get("is_error"), result
    async with producer.db.session() as session:
        assert not list((await session.exec(select(TeamTask))).all())
    assert not [message for message in await producer.chats.messages(chat.id) if message.kind == "effort_solver"]


async def test_auto_does_not_override_manual_task_creation(producer, solver):
    chat = await opened(producer)
    await producer.chats.producer.set_effort_mode(chat.id, "auto")
    task = await producer.teams.create_task(
        team_id=TEAM, assignee_id=DEV, title="Manual task", brief="User supplied instructions",
        producer_chat_id=chat.id, effort="low",
    )
    assert task.effort == "low"
    assert not solver.requests


async def test_delegation_by_assignee_keeps_ordinary_effort_rules(producer, solver):
    chat = await opened(producer)
    await producer.chats.producer.set_effort_mode(chat.id, "auto")
    parent = await producer.teams.create_task(
        team_id=TEAM, assignee_id=DEV, title="Parent", brief="User instructions",
        producer_chat_id=chat.id,
    )
    delegated = await producer.teams.create_task(
        team_id=TEAM, assignee_id=f"{TEAM}__director", created_by=DEV,
        title="Clarify requirements", brief="Review this implementation decision.",
        parent_task=parent, effort="high",
    )
    assert delegated.producer_chat_id == chat.id
    assert delegated.effort == "high"
    assert not solver.requests


async def test_waiting_solver_does_not_lock_task_creation_and_cancellation_leaves_no_partial_task(
    producer, monkeypatch,
):
    chat = await opened(producer)
    await producer.chats.producer.set_effort_mode(chat.id, "auto")
    started = asyncio.Event()

    async def wait_for_solver(*_args, **_kwargs):
        started.set()
        await asyncio.Event().wait()

    monkeypatch.setattr(orchestrator_module, "solve_effort", wait_for_solver)
    waiting = asyncio.create_task(producer.teams.create_task(
        team_id=TEAM, assignee_id=DEV, created_by=PRODUCER,
        title="Awaiting evaluation", brief="Instructions", producer_chat_id=chat.id,
    ))
    try:
        await asyncio.wait_for(started.wait(), timeout=1)
        other = await asyncio.wait_for(producer.teams.create_task(
            team_id=TEAM, assignee_id=DEV, title="Independent task", brief="Instructions",
        ), timeout=2)
        assert other.number == 1
    finally:
        waiting.cancel()
        with pytest.raises(asyncio.CancelledError):
            await waiting
    async with producer.db.session() as session:
        assert [task.id for task in (await session.exec(select(TeamTask))).all()] == [other.id]
    assert not [message for message in await producer.chats.messages(chat.id) if message.kind == "effort_solver"]


async def test_solver_summary_and_task_are_rolled_back_together_if_creation_fails(producer, solver):
    chat = await opened(producer)
    await producer.chats.producer.set_effort_mode(chat.id, "auto")
    with pytest.raises(ValueError, match="Invalid attachment"):
        await producer.teams.create_task(
            team_id=TEAM, assignee_id=DEV, created_by=PRODUCER,
            title="Invalid attachment", brief="Instructions", producer_chat_id=chat.id,
            attachments=["not-an-upload"],
        )
    assert len(solver.requests) == 1
    async with producer.db.session() as session:
        assert not list((await session.exec(select(TeamTask))).all())
    assert not [message for message in await producer.chats.messages(chat.id) if message.kind == "effort_solver"]
    next_task = await producer.teams.create_task(
        team_id=TEAM, assignee_id=DEV, title="Next task", brief="Instructions",
    )
    assert next_task.number == 1


async def test_effort_mode_survives_stop_start_and_reaches_next_turn(producer):
    chat = await opened(producer)
    await producer.chats.producer.pause(chat.id)
    saved = await producer.chats.producer.set_effort_mode(chat.id, "auto")
    assert saved.producer_paused
    await producer.chats.producer.disable(chat.id)
    await producer.chats.producer.enable(chat.id)
    await finished(producer, chat.id)
    assert (await producer.chats.get_chat(chat.id)).producer_effort_mode == "auto"
    context = json.loads(await producer.chats.producer.context(chat.id))
    assert context["effort_mode"] == "auto"
    assert "Effort Solver" in context["effort_instructions"]
    assert '"effort_mode": "auto"' in ProducerBackend.requests[-1].prompt
    await producer.chats.producer.disable(chat.id)
    await producer.chats.producer.enable(chat.id, effort_mode="agent")
    await finished(producer, chat.id)
    assert (await producer.chats.get_chat(chat.id)).producer_effort_mode == "agent"


async def test_effort_mode_cannot_change_during_a_producer_turn(producer, monkeypatch):
    chat = await opened(producer)
    monkeypatch.setattr(producer.chats.jobs, "is_running", lambda key: key == chat.id)
    with pytest.raises(ChatBusy):
        await producer.chats.producer.set_effort_mode(chat.id, "auto")
    assert (await producer.chats.get_chat(chat.id)).producer_effort_mode == "agent"
