"""Effort evaluation is an isolated, bounded model call over supplied task text (§10.11)."""
from __future__ import annotations

import asyncio
import json

import pytest

from voxarium_studio.models.teams import TeamMember
from voxarium_studio.teams import effort_solver_runner as runner
from voxarium_studio.teams.backends.base import AgentEvent
from voxarium_studio.teams.effort_solver import DEFAULT_SYSTEM_PROMPT


SCORES = {"complexity": 9, "creativity": 6, "depth": 8, "coverage": 8}


class SolverBackend:
    def __init__(self, events=None, *, available=True, wait=False):
        self.events = events if events is not None else [
            AgentEvent(kind="thinking", text="Internal assessment"),
            AgentEvent(kind="text", text=json.dumps(SCORES)),
            AgentEvent(kind="result", text=json.dumps(SCORES), is_final=True),
        ]
        self.available = available
        self.wait = wait
        self.started = asyncio.Event()
        self.closed = False
        self.request = None

    def is_available(self):
        return self.available, "Solver runtime is offline"

    async def run(self, request):
        self.request = request
        assert request.workdir.is_dir()
        assert not list(request.workdir.iterdir())
        self.started.set()
        try:
            if self.wait:
                await asyncio.Event().wait()
            for event in self.events:
                yield event
        finally:
            self.closed = True


@pytest.fixture
def member():
    return TeamMember(
        id="team__studio-effort-solver", team_id="team", name="Effort Solver",
        role_title="Effort Solver", builtin="effort_solver", agent_backend="test_solver",
        model="chosen-model", effort="high", responsibility=DEFAULT_SYSTEM_PROMPT,
        allowed_tools=["Read", "Write"], skills=["project-instructions"], agent="named-persona",
    )


async def test_evaluation_uses_only_title_and_description_with_solver_settings(member, monkeypatch):
    backend = SolverBackend()
    selected = []

    def get_backend(name):
        selected.append(name)
        return backend

    monkeypatch.setattr(runner, "get_backend", get_backend)
    solution = await runner.solve_effort(member, "Исправить прыжок", "Jump once on Space.", max_message_bytes=12345)
    assert selected == ["test_solver"]
    assert solution.scores.model_dump() == SCORES
    assert solution.effort == 3 and solution.effort_level == "high"
    request = backend.request
    assert json.loads(request.prompt) == {"title": "Исправить прыжок", "description": "Jump once on Space."}
    assert request.system_prompt == DEFAULT_SYSTEM_PROMPT
    assert request.model == "chosen-model" and request.effort == "high"
    assert request.text_only and request.max_turns == 1
    assert request.max_message_bytes == 12345
    assert request.task is None and request.resume_session_id is None
    assert not request.mcp_servers and not request.extra_allowed_tools
    assert not request.plugin_dirs and not request.writable_roots
    assert not request.member.allowed_tools and not request.member.skills
    assert request.member.agent is None
    assert member.allowed_tools == ["Read", "Write"] and member.agent == "named-persona"
    assert backend.closed and not request.workdir.exists()


async def test_custom_prompt_and_weights_are_applied(member, monkeypatch):
    member.responsibility = "Score only the supplied task, using the four required integer keys."
    member.effort_solver_weights = {"complexity": 0, "creativity": 0, "depth": 0, "coverage": 1}
    backend = SolverBackend()
    monkeypatch.setattr(runner, "get_backend", lambda _name: backend)
    solution = await runner.solve_effort(member, "Title", "Brief")
    assert backend.request.system_prompt == member.responsibility
    assert solution.effort == 2 and solution.effort_level == "medium"


async def test_usage_reconciles_repeated_assistant_frames_and_final_result_once(member, monkeypatch):
    usage = {"input_tokens": 10, "cache_creation_input_tokens": 5,
             "cache_read_input_tokens": 7, "output_tokens": 3}
    repeated_usage = {"provider_response_id": "solver-response", "usage": usage}
    backend = SolverBackend([
        AgentEvent(kind="thinking", text="Internal assessment", data=repeated_usage),
        AgentEvent(kind="text", text=json.dumps(SCORES), data=repeated_usage),
        AgentEvent(kind="result", text=json.dumps(SCORES), data={
            "usage": usage, "total_tokens": 25, "total_cost_usd": 0.0123, "num_turns": 1,
        }, is_final=True),
    ])
    monkeypatch.setattr(runner, "get_backend", lambda _name: backend)
    solution = await runner.solve_effort(member, "Title", "Brief")
    assert solution.usage["total_tokens"] == 25
    assert solution.usage["total_uncached_tokens"] == 18
    assert solution.usage["total_cached_tokens"] == 7
    assert solution.usage["total_input_tokens"] == 15
    assert solution.usage["total_output_tokens"] == 3
    assert solution.usage["total_cost_usd"] == pytest.approx(0.0123)
    assert solution.usage["num_turns"] == 1


async def test_text_response_is_used_when_result_has_no_text(member, monkeypatch):
    backend = SolverBackend([
        AgentEvent(kind="text", text="```json\n" + json.dumps(SCORES) + "\n```"),
        AgentEvent(kind="result", text="", is_final=True),
    ])
    monkeypatch.setattr(runner, "get_backend", lambda _name: backend)
    assert (await runner.solve_effort(member, "Title", "Brief")).effort == 3


@pytest.mark.parametrize("events", [
    [],
    [AgentEvent(kind="result", text="not json")],
    [AgentEvent(kind="result", text=json.dumps({**SCORES, "depth": 0}))],
    [AgentEvent(kind="result", text=json.dumps({**SCORES, "depth": True}))],
])
async def test_invalid_model_outputs_do_not_get_a_fallback_effort(member, monkeypatch, events):
    backend = SolverBackend(events)
    monkeypatch.setattr(runner, "get_backend", lambda _name: backend)
    with pytest.raises(ValueError):
        await runner.solve_effort(member, "Title", "Brief")
    assert backend.closed and not backend.request.workdir.exists()


@pytest.mark.parametrize("event", [
    AgentEvent(kind="error", text="Solver request failed"),
    AgentEvent(kind="result", text="Solver request failed", data={"is_error": True}),
])
async def test_backend_failure_closes_stream_and_is_reported(member, monkeypatch, event):
    backend = SolverBackend([event])
    monkeypatch.setattr(runner, "get_backend", lambda _name: backend)
    with pytest.raises(RuntimeError, match="Solver request failed"):
        await runner.solve_effort(member, "Title", "Brief")
    assert backend.closed and not backend.request.workdir.exists()


async def test_unavailable_backend_does_not_start(member, monkeypatch):
    backend = SolverBackend(available=False)
    monkeypatch.setattr(runner, "get_backend", lambda _name: backend)
    with pytest.raises(RuntimeError, match="offline"):
        await runner.solve_effort(member, "Title", "Brief")
    assert backend.request is None


async def test_timeout_closes_stream_and_removes_workdir(member, monkeypatch):
    backend = SolverBackend(wait=True)
    monkeypatch.setattr(runner, "get_backend", lambda _name: backend)
    monkeypatch.setattr(runner, "SOLVER_TIMEOUT", 0.05)
    with pytest.raises(RuntimeError, match="did not answer"):
        await runner.solve_effort(member, "Title", "Brief")
    assert backend.closed and not backend.request.workdir.exists()


async def test_cancellation_propagates_and_closes_stream(member, monkeypatch):
    backend = SolverBackend(wait=True)
    monkeypatch.setattr(runner, "get_backend", lambda _name: backend)
    task = asyncio.create_task(runner.solve_effort(member, "Title", "Brief"))
    await asyncio.wait_for(backend.started.wait(), timeout=1)
    task.cancel()
    with pytest.raises(asyncio.CancelledError):
        await task
    assert backend.closed and not backend.request.workdir.exists()
