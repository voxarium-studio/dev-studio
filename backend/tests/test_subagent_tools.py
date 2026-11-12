"""MCP authority and lifecycle contract for one task's subagents (§10.3)."""

from __future__ import annotations

import json
from types import SimpleNamespace
from unittest.mock import AsyncMock, Mock

import pytest

from voxarium_studio.api.mcp import BUILTIN_TOOLS
from voxarium_studio.core.config import AppConfig
from voxarium_studio.models.teams import TeamMember, TeamTask
from voxarium_studio.teams.tools import build_toolset


SUBAGENT_TOOLS = {
    "spawn_subagent", "list_subagents", "get_subagent", "wait_subagents",
    "stop_subagent", "send_subagent_message",
}


@pytest.fixture
def bound_tools():
    member = TeamMember(id="member_owner", team_id="team", name="Owner", role_title="Developer")
    task = TeamTask(id="task_owner", team_id="team", assignee_id=member.id, title="Task", brief="Work")
    child = {"id": "child_1", "status": "completed", "total_tokens": 100, "total_cost_usd": 0.01}
    manager = SimpleNamespace(
        require_run=Mock(), has_active=Mock(return_value=False),
        create=AsyncMock(return_value=child), list=AsyncMock(return_value=[child]),
        get=AsyncMock(return_value={**child, "messages": []}),
        wait=AsyncMock(return_value=[child]), stop=AsyncMock(return_value=child),
        send=AsyncMock(return_value={**child, "status": "queued"}),
    )
    orchestrator = SimpleNamespace(
        config=AppConfig(), db=object(), docs=object(), memory=object(), authoring=None,
        subagents=manager, report_result=AsyncMock(),
    )

    def build(**kwargs):
        toolset = build_toolset(
            orchestrator, member=member, task=kwargs.pop("task", task),
            run_id="run_current", **kwargs,
        )
        if toolset is None:
            pytest.skip("claude-agent-sdk is not installed")
        return toolset

    return orchestrator, manager, build


def definitions(toolset):
    return {definition.name: definition for definition in toolset.definitions}


def body(result):
    return "\n".join(part["text"] for part in result["content"] if part["type"] == "text")


async def test_spawn_binds_task_member_and_run_not_model_supplied_authority(bound_tools):
    _, manager, build = bound_tools
    result = await definitions(build())["spawn_subagent"].handler({
        "description": "Review parser", "prompt": "Read the parser and report bugs.",
        "backend": "codex", "model": "chosen-model", "effort": "high",
        "task_id": "other_task", "member_id": "other_member", "run_id": "other_run",
    })
    assert not result.get("is_error")
    assert json.loads(body(result))["id"] == "child_1"
    manager.require_run.assert_called_once_with("task_owner", "member_owner", "run_current")
    # §10.11: Effort Solver chooses the effort, so one sent anyway — by a caller that
    # remembers the earlier schema — is not passed on, and the schema no longer offers it.
    manager.create.assert_awaited_once_with(
        "task_owner", "member_owner", description="Review parser",
        prompt="Read the parser and report bugs.", backend="codex", model="chosen-model",
    )
    assert "effort" not in definitions(build())["spawn_subagent"].input_schema["properties"]


async def test_current_task_controls_return_results_and_usage(bound_tools):
    _, manager, build = bound_tools
    tools = definitions(build())
    listed = await tools["list_subagents"].handler({})
    assert json.loads(body(listed))[0]["total_cost_usd"] == 0.01
    read = await tools["get_subagent"].handler({"subagent_id": "child_1"})
    assert json.loads(body(read))["messages"] == []
    await tools["wait_subagents"].handler({"subagent_id": "child_1", "timeout": 5})
    manager.wait.assert_awaited_once_with("task_owner", "member_owner", "child_1", timeout=5)
    manager.stop.assert_not_awaited()
    await tools["stop_subagent"].handler({"subagent_id": "child_1"})
    manager.stop.assert_awaited_once_with("task_owner", "member_owner", "child_1")
    await tools["send_subagent_message"].handler({"subagent_id": "child_1", "message": "Check one more case."})
    manager.send.assert_awaited_once_with("task_owner", "member_owner", "child_1", "Check one more case.")


async def test_wait_defaults_to_all_without_cancelling_on_timeout(bound_tools):
    _, manager, build = bound_tools
    manager.wait.return_value = [{"id": "child_1", "status": "running"}]
    result = await definitions(build())["wait_subagents"].handler({})
    assert json.loads(body(result))[0]["status"] == "running"
    manager.wait.assert_awaited_once_with("task_owner", "member_owner", None, timeout=30)
    manager.stop.assert_not_awaited()


@pytest.mark.parametrize("timeout", [-1, 61, float("nan"), float("inf"), True, "30", None])
async def test_wait_rejects_unbounded_or_malformed_timeout(bound_tools, timeout):
    _, manager, build = bound_tools
    result = await definitions(build())["wait_subagents"].handler({"timeout": timeout})
    assert result["is_error"] and "between 0 and 60" in body(result)
    manager.wait.assert_not_awaited()


@pytest.mark.parametrize("name", sorted(SUBAGENT_TOOLS))
async def test_chats_cannot_create_or_control_task_subagents(bound_tools, name):
    _, manager, build = bound_tools
    result = await definitions(build(task=None))[name].handler({})
    assert result["is_error"] and "chat has no task" in body(result)
    manager.require_run.assert_not_called()


@pytest.mark.parametrize("name", sorted(SUBAGENT_TOOLS))
async def test_closed_run_tool_cannot_control_the_next_run(bound_tools, name):
    _, manager, build = bound_tools
    tools = definitions(build())
    manager.require_run.side_effect = ValueError("This run is no longer active")
    result = await tools[name].handler({"subagent_id": "child_1", "prompt": "Read", "description": "Read"})
    assert result["is_error"] and "no longer active" in body(result)
    for method in (manager.create, manager.list, manager.get, manager.wait, manager.stop, manager.send):
        method.assert_not_awaited()


def test_child_toolset_exposes_only_read_context_and_never_parent_mutations(bound_tools):
    orchestrator, _, build = bound_tools
    orchestrator.authoring = SimpleNamespace(tools=Mock(return_value=[]))
    child_tools = build(subagent_id="child_1")
    # §10.12: project memory is read-only for a child, like the board.
    assert set(definitions(child_tools)) == {
        "list_team", "list_tasks", "get_task", "list_docs", "read_doc", "recall",
    }
    assert all("subagent" not in name for name in child_tools.allowed_tools)
    orchestrator.authoring.tools.assert_not_called()


async def test_finish_task_requires_children_to_stop_before_recording_result(bound_tools):
    orchestrator, manager, build = bound_tools
    manager.has_active.return_value = True
    result = await definitions(build())["finish_task"].handler({"result_type": "done", "summary": "Finished."})
    assert result["is_error"] and "wait_subagents" in body(result)
    orchestrator.report_result.assert_not_awaited()


def test_subagent_tools_are_listed_in_mcp_settings(bound_tools):
    _, _, build = bound_tools
    assert SUBAGENT_TOOLS <= set(definitions(build()))
    assert SUBAGENT_TOOLS <= set(BUILTIN_TOOLS)
