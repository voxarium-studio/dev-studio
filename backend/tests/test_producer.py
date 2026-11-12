"""Producer Mode (§10.11), with deterministic backends and no agent credits."""
from __future__ import annotations

import asyncio
import json
from pathlib import Path

import pytest
import yaml

from voxarium_studio.models.teams import AgentChat, Team, TeamMember, TeamTask
from voxarium_studio.teams.backends.base import AgentEvent, register_backend
from voxarium_studio.teams.chat import ChatBusy, ChatService, UserContext
from voxarium_studio.teams.orchestrator import RoutingError
from voxarium_studio.teams.tools import build_toolset

TEAM = "team_default"
PRODUCER = f"{TEAM}__studio-producer"
DEV = f"{TEAM}__dev"


class ProducerBackend:
    named_agents = False
    requests = []
    fail = False
    overflow = False

    def is_available(self):
        return True, "test backend"

    async def run(self, request):
        self.requests.append(request)
        summary = request.session_key.endswith(":summary")
        if self.fail or (self.overflow and not summary):
            type(self).overflow = False
            yield AgentEvent(kind="error", text="context window exceeded" if not self.fail else "offline")
            return
        yield AgentEvent(kind="text", text="Summary: build a small game; preserve approved tasks."
                         if summary else "What would you like to do next?", session_id="producer-session")
        yield AgentEvent(kind="result", text="Done", data={"num_turns": 1}, is_final=True)


register_backend("producer_test", ProducerBackend)


async def finished(ctx, job_id):
    handle = ctx.jobs.get(job_id)
    if handle and handle.task:
        await asyncio.wait_for(asyncio.shield(handle.task), timeout=10)


@pytest.fixture
async def producer(ctx):
    await ctx.chats.producer.shutdown()  # reconciliation is explicit except in its own test
    ProducerBackend.requests = []
    ProducerBackend.fail = False
    ProducerBackend.overflow = False
    async with ctx.db.session() as session:
        for key in (PRODUCER, DEV):
            member = await session.get(TeamMember, key)
            member.agent_backend = "producer_test"
            session.add(member)
    return ctx


async def solo_on(ctx):
    """Switch Solo Mode on for DEV; every member starts with it off (§10.4)."""
    async with ctx.db.session() as session:
        member = await session.get(TeamMember, DEV)
        member.solo_mode = True
        session.add(member)


async def opened(ctx):
    chat = await ctx.chats.create_chat(PRODUCER)
    await ctx.chats.producer.enable(chat.id, "ru")
    await finished(ctx, chat.id)
    return await ctx.chats.get_chat(chat.id)


async def tools_for(ctx, chat):
    async with ctx.db.session() as session:
        member = await session.get(TeamMember, PRODUCER)
    current = await ctx.chats.get_chat(chat.id)
    toolset = build_toolset(ctx.teams, member=member, producer=ctx.chats.producer, chat_id=chat.id,
                           producer_effort_mode=current.producer_effort_mode)
    return {tool.name: tool.handler for tool in toolset.definitions}


async def draft(ctx, chat, title="Implement movement", dependencies=None):
    return await ctx.teams.create_task(team_id=TEAM, assignee_id=DEV, created_by=PRODUCER,
                                       title=title, brief="Arrow keys move the player.",
                                       depends_on=dependencies, producer_chat_id=chat.id)


async def allow(ctx, task, status="pending"):
    async with ctx.db.session() as session:
        stored = await session.get(TeamTask, task.id)
        stored.producer_approved = True
        stored.status = status
        session.add(stored)


async def test_builtin_is_restored_without_overwriting_settings_or_other_roster_entries(producer):
    store = producer.teams.store
    path = store.file_for(TEAM)
    before = path.read_text(encoding="utf-8")
    assert "studio-producer" in before
    async with producer.db.session() as session:
        member = await session.get(TeamMember, PRODUCER)
        member.model = "custom-model"
        member.responsibility = "My production instructions"
        session.add(member)
    payload = yaml.safe_load(before)
    payload["members"] = [m for m in payload["members"] if m["id"] != "studio-producer"]
    path.write_text(yaml.safe_dump(payload), encoding="utf-8")
    await producer.teams.sync_definitions()
    await producer.teams.sync_definitions()
    after = yaml.safe_load(path.read_text(encoding="utf-8"))
    saved = [m for m in after["members"] if m["id"] == "studio-producer"]
    assert len(saved) == 1 and saved[0]["model"] == "custom-model"
    assert "responsibility" not in saved[0], "the Producer prompt belongs to Settings"
    async with producer.db.session() as session:
        restored = await session.get(TeamMember, PRODUCER)
        assert restored.responsibility == producer.config.teams.prompts.text("producer_mode")
    assert [m for m in after["members"] if m["id"] != "studio-producer"] == payload["members"]


def test_builtin_cannot_be_deleted_or_changed_into_a_person_but_settings_are_editable(client):
    url = f"/api/teams/{TEAM}/members/{PRODUCER}"
    assert client.delete(url).status_code == 409
    assert client.patch(url, json={"kind": "human"}).status_code == 400
    changed = client.patch(url, json={"name": "Producer Alice", "model": "custom-model"})
    assert changed.status_code == 200
    assert changed.json()["builtin"] == "producer"
    assert changed.json()["model"] == "custom-model"


async def test_start_is_only_for_producer_and_opens_a_localized_conversation(producer):
    other = await producer.chats.create_chat(DEV)
    with pytest.raises(ValueError, match="built-in"):
        await producer.chats.producer.enable(other.id)
    chat = await opened(producer)
    assert chat.producer_mode and chat.producer_state == "awaiting_user"
    assert chat.producer_started
    assert chat.producer_policy == "careful"
    request = ProducerBackend.requests[-1]
    assert request.prompt.count("--- BEGIN REQUIRED SKILL: voxarium_studio:studio ---") == 1
    assert request.prompt.count("--- BEGIN REQUIRED SKILL: voxarium_studio:producer ---") == 1
    assert request.prompt.index("voxarium_studio:studio") < request.prompt.index("voxarium_studio:producer")
    assert "Break a substantial goal into **many small, concrete tasks**" in request.prompt
    assert "Skill(voxarium_studio:producer)" in request.extra_allowed_tools
    assert "voxarium_studio:producer" in request.skill_files
    assert "Speak English" in request.system_prompt
    assert "Decision policy: Careful" in request.system_prompt
    assert "Ask the user what" in request.prompt
    assert "mcp__voxarium_studio__propose_producer_plan" in request.extra_allowed_tools
    await producer.chats.producer.enable(chat.id)
    assert len(ProducerBackend.requests) == 1, "double Start must not create another turn"


async def test_start_in_existing_chat_loads_producer_skill_on_resumed_session(producer):
    chat = await producer.chats.create_chat(PRODUCER)
    await producer.chats.send(chat.id, "Ordinary conversation", UserContext())
    await finished(producer, chat.id)
    ordinary = ProducerBackend.requests[-1]
    assert ordinary.resume_session_id is None
    assert "--- BEGIN REQUIRED SKILL: voxarium_studio:producer ---" not in ordinary.prompt

    await producer.chats.producer.enable(chat.id)
    await finished(producer, chat.id)
    started = ProducerBackend.requests[-1]
    assert started.resume_session_id == "producer-session"
    assert started.prompt.count("--- BEGIN REQUIRED SKILL: voxarium_studio:producer ---") == 1

    await producer.chats.send(chat.id, "Continue", UserContext())
    await finished(producer, chat.id)
    continued = ProducerBackend.requests[-1]
    assert "--- BEGIN REQUIRED SKILL: voxarium_studio:producer ---" not in continued.prompt


@pytest.mark.parametrize("policy", ["careful", "independent"])
async def test_workspace_policy_waits_for_first_message_then_notifies_both_switches(producer, policy):
    chat = await producer.chats.create_chat(PRODUCER, producer_mode=True, locale="ru")
    for selection in ("independent", "careful", policy):
        saved = await producer.chats.producer.set_policy(chat.id, selection)
        assert not saved.producer_started and not producer.chats.is_running(chat.id)
    await producer.chats.producer.reconcile()
    assert ProducerBackend.requests == []
    assert await producer.chats.messages(chat.id) == []

    await producer.chats.send(chat.id, "Build a small game", UserContext())
    assert (await producer.chats.get_chat(chat.id)).producer_started
    await finished(producer, chat.id)
    assert len(ProducerBackend.requests) == 1
    assert "Build a small game" in ProducerBackend.requests[-1].prompt
    assert f"Decision policy: {policy.title()}" in ProducerBackend.requests[-1].system_prompt

    for changed in (("independent", "careful") if policy == "careful" else ("careful", "independent")):
        count = len(ProducerBackend.requests)
        await producer.chats.producer.set_policy(chat.id, changed)
        await finished(producer, chat.id)
        assert len(ProducerBackend.requests) == count + 1
        assert f"Decision policy: {changed.title()}" in ProducerBackend.requests[-1].system_prompt
        await producer.chats.producer.set_policy(chat.id, changed)
        assert len(ProducerBackend.requests) == count + 1


async def test_new_workspace_can_work_while_another_producer_is_paused(producer):
    active = await opened(producer)
    await producer.chats.producer.pause(active.id)
    empty = await producer.chats.create_chat(PRODUCER, producer_mode=True)
    saved = await producer.chats.get_chat(empty.id)
    assert not saved.producer_started and not saved.producer_paused
    for action in (producer.chats.producer.pause, producer.chats.producer.resume):
        with pytest.raises(ValueError, match="not started"):
            await action(empty.id)
    selected = await producer.chats.producer.set_policy(empty.id, "independent")
    assert selected.producer_policy == "independent" and not selected.producer_started
    await producer.chats.send(empty.id, "Start this conversation", UserContext())
    await finished(producer, empty.id)
    assert (await producer.chats.get_chat(empty.id)).producer_started
    assert (await producer.chats.get_chat(active.id)).producer_paused
    await producer.chats.producer.pause(empty.id)
    await producer.chats.delete_chat(active.id)
    with pytest.raises(ValueError, match="Resume"):
        await producer.chats.send(empty.id, "Still paused", UserContext())
    await producer.chats.producer.resume(empty.id)
    await producer.chats.send(empty.id, "Continue this conversation", UserContext())
    await finished(producer, empty.id)
    assert [r.session_key for r in ProducerBackend.requests] == [active.id, empty.id, empty.id]


async def test_pause_during_overlapping_producer_turns_only_cancels_selected_chat(producer, monkeypatch):
    first = await producer.chats.create_chat(PRODUCER, producer_mode=True)
    started = {}
    finish = asyncio.Event()

    async def hold(self, request):
        yield AgentEvent(kind="text", text="Working", session_id=f"session-{request.session_key}")
        started.setdefault(request.session_key, asyncio.Event()).set()
        await finish.wait()
        yield AgentEvent(kind="result", text="Done", is_final=True)

    monkeypatch.setattr(ProducerBackend, "run", hold)
    await producer.chats.send(first.id, "First goal", UserContext())
    await asyncio.wait_for(started.setdefault(first.id, asyncio.Event()).wait(), timeout=5)
    second = await producer.chats.create_chat(PRODUCER, producer_mode=True)
    await producer.chats.send(second.id, "Second goal", UserContext())
    await asyncio.wait_for(started.setdefault(second.id, asyncio.Event()).wait(), timeout=5)
    await producer.chats.producer.pause(first.id)
    assert not producer.jobs.is_running(first.id)
    assert producer.jobs.is_running(second.id)
    finish.set()
    await finished(producer, second.id)
    saved = await producer.chats.get_chat(second.id)
    assert not saved.producer_paused and saved.producer_state == "awaiting_user"
    assert saved.agent_session_id == f"session-{second.id}"
    saved = await producer.chats.get_chat(first.id)
    assert saved.producer_paused and saved.agent_session_id == f"session-{first.id}"


async def test_failed_first_turn_is_started_even_without_backend_session(producer):
    chat = await producer.chats.create_chat(PRODUCER, producer_mode=True)
    ProducerBackend.fail = True
    await producer.chats.send(chat.id, "Try starting", UserContext())
    await finished(producer, chat.id)
    saved = await producer.chats.get_chat(chat.id)
    assert saved.producer_started and saved.agent_session_id is None
    assert saved.producer_state == "error"
    paused = await producer.chats.producer.pause(chat.id)
    assert paused.producer_paused and paused.producer_started


@pytest.mark.parametrize("named_agent", [False, True])
async def test_producer_language_uses_current_global_preference(producer, monkeypatch, named_agent):
    monkeypatch.setattr(ProducerBackend, "named_agents", named_agent)
    producer.config.respond_language = "Português (Brasil)"
    chat = await producer.chats.create_chat(PRODUCER, producer_mode=True, locale="ru")
    for language in ("Português (Brasil)", "日本語"):
        producer.config.respond_language = language
        await producer.chats.send(chat.id, "Continue", UserContext())
        await finished(producer, chat.id)
        resolved = producer.config.teams.prompts.text("producer_mode", language=language)
        assert resolved in ProducerBackend.requests[-1].system_prompt
        assert "{language}" not in ProducerBackend.requests[-1].system_prompt


@pytest.mark.parametrize("named_agent", [False, True])
@pytest.mark.parametrize("override", [None, "Custom Producer instructions. Speak {language}."])
async def test_producer_system_prompt_is_resolved_once_only_in_active_mode(
    producer, monkeypatch, named_agent, override,
):
    monkeypatch.setattr(ProducerBackend, "named_agents", named_agent)
    producer.config.teams.prompts.producer_mode = override
    await producer.teams.sync_agent_definitions()
    raw = producer.config.teams.prompts.text("producer_mode")
    resolved = producer.config.teams.prompts.text("producer_mode", language="English")
    chat = await producer.chats.create_chat(PRODUCER)

    await producer.chats.send(chat.id, "Hello", UserContext())
    await finished(producer, chat.id)
    ordinary = ProducerBackend.requests[-1]
    assert raw not in ordinary.system_prompt
    assert resolved not in ordinary.system_prompt

    await producer.chats.producer.enable(chat.id, "ru")
    await finished(producer, chat.id)
    active = ProducerBackend.requests[-1]
    assert active.system_prompt.count(resolved) == 1
    assert "{language}" not in active.system_prompt
    assert bool(active.agent_name) == named_agent
    definition = (producer.paths.root / ".claude" / "agents" / "producer.md").read_text(
        encoding="utf-8",
    )
    assert raw not in definition
    assert "{language}" not in definition
    assert "Producer Mode is active" not in definition
    assert "Custom Producer instructions" not in definition

    await producer.chats.producer.disable(chat.id)
    await producer.chats.send(chat.id, "Hello again", UserContext())
    await finished(producer, chat.id)
    stopped = ProducerBackend.requests[-1]
    assert raw not in stopped.system_prompt
    assert resolved not in stopped.system_prompt


async def test_drafts_do_not_escape_through_autostart_start_all_or_manual_release(producer):
    await producer.teams.set_autostart_tasks(TEAM, True)
    chat = await opened(producer)
    tools = await tools_for(producer, chat)
    result = await tools["create_task"]({"assignee_id": DEV, "title": "Draft", "brief": "A draft"})
    assert not result.get("is_error")
    task = (await producer.chats.producer.tasks(chat.id))[0]
    assert task.status == "todo" and not task.producer_approved
    assert await producer.teams.start_all_tasks(TEAM) == []
    with pytest.raises(ValueError, match="Approve"):
        await producer.teams.start_task(task.id)
    with pytest.raises(ValueError, match="Approve"):
        await producer.teams.update_task(task.id, {"status": "pending"})
    result = await tools["update_task"]({"task": task.id, "status": "pending"})
    assert result["is_error"]
    assert not producer.jobs.is_running(task.id)


@pytest.mark.parametrize("character", ["x", "я", "🎮", "\n"])
async def test_producer_brief_limit_on_create_and_update(producer, character):
    chat = await opened(producer)
    tools = await tools_for(producer, chat)
    # Include whitespace and non-BMP Unicode; count characters, not bytes.
    brief = "A" + character * 1498 + "Z"
    result = await tools["create_task"]({"assignee_id": DEV, "title": "Too long", "brief": brief + "!"})
    assert result["is_error"] and "1500" in result["content"][0]["text"]
    assert await producer.chats.producer.tasks(chat.id) == []

    result = await tools["create_task"]({"assignee_id": DEV, "title": "Valid", "brief": brief})
    assert not result.get("is_error")
    task = (await producer.chats.producer.tasks(chat.id))[0]
    assert task.brief == brief

    result = await tools["update_task"]({"task": task.id, "title": "Must not change", "brief": brief + "!"})
    assert result["is_error"]
    saved = await producer.teams._reload(task.id)
    assert saved.brief == brief and saved.title == "Valid"

    replacement = "B" + character * 1498 + "Y"
    result = await tools["update_task"]({"task": task.id, "brief": replacement})
    assert not result.get("is_error")
    assert (await producer.teams._reload(task.id)).brief == replacement


async def test_regular_tools_keep_long_briefs(producer):
    async with producer.db.session() as session:
        member = await session.get(TeamMember, PRODUCER)
    toolset = build_toolset(producer.teams, member=member)
    tools = {tool.name: tool.handler for tool in toolset.definitions}
    for tool in toolset.definitions:
        if tool.name in {"create_task", "update_task"}:
            assert "maxLength" not in tool.input_schema["properties"]["brief"]
    brief = "A" * 1501
    result = await tools["create_task"]({"assignee_id": DEV, "title": "Regular task", "brief": brief})
    assert not result.get("is_error")
    async with producer.db.session() as session:
        from sqlmodel import select
        task = (await session.exec(select(TeamTask).where(TeamTask.title == "Regular task"))).one()
    assert task.brief == brief
    result = await tools["update_task"]({"task": task.id, "brief": brief + "B"})
    assert not result.get("is_error")
    assert (await producer.teams._reload(task.id)).brief == brief + "B"


async def test_stale_plan_or_changed_task_is_not_approved(producer):
    chat = await opened(producer)
    task = await draft(producer, chat)
    await producer.chats.producer.propose(chat.id, [task.id], "Build movement")
    with pytest.raises(ValueError, match="changed"):
        await producer.chats.producer.approve_plan(chat.id, 0)
    await producer.teams.update_task(task.id, {"brief": "Different scope"})
    with pytest.raises(ValueError, match="changed"):
        await producer.chats.producer.approve_plan(chat.id, 1)
    assert not (await producer.teams._reload(task.id)).producer_approved


async def test_approval_releases_only_its_plan_and_honors_dependencies(producer):
    chat = await opened(producer)
    first = await draft(producer, chat)
    second = await draft(producer, chat, "Add animation", [first.id])
    unrelated = await draft(producer, chat, "Future work")
    await producer.chats.producer.propose(chat.id, [first.id, second.id], "Movement, then animation")
    await producer.chats.producer.approve_plan(chat.id, 1)
    await finished(producer, first.id)
    await finished(producer, chat.id)
    assert (await producer.teams._reload(first.id)).status == "needs_review"
    assert (await producer.teams._reload(second.id)).status == "pending"
    assert (await producer.teams._reload(unrelated.id)).status == "todo"
    with pytest.raises(ValueError):
        await producer.chats.producer.approve_plan(chat.id, 1)
    await producer.chats.producer.review(chat.id, first.id, "approve", "")
    await finished(producer, second.id)
    assert (await producer.teams._reload(first.id)).status == "done"
    assert (await producer.teams._reload(second.id)).status == "needs_review"
    assert (await producer.chats.get_chat(chat.id)).producer_mode


@pytest.mark.parametrize("policy", ["careful", "independent"])
@pytest.mark.parametrize("origin", ["manual", "same_chat", "other_chat"])
async def test_done_result_can_only_be_approved_in_producer_mode(producer, policy, origin):
    chat = await opened(producer)
    await producer.chats.producer.set_policy(chat.id, policy)
    await finished(producer, chat.id)
    if origin == "manual":
        task = await producer.teams.create_task(team_id=TEAM, assignee_id=DEV,
                                                title="Existing work", brief="Movement")
    else:
        owner = await opened(producer) if origin == "other_chat" else chat
        task = await draft(producer, owner)
        await allow(producer, task)
    await producer.teams.submit_for_review(task.id, "Movement implemented", "done")
    tools = await tools_for(producer, chat)

    # Both on arrival and after approval, no action may reopen or cancel the work.
    for status in ("needs_review", "done"):
        if job := producer.teams._commit_jobs.get(task.id):
            await asyncio.wait_for(asyncio.shield(job), timeout=10)
        before = (await producer.teams._reload(task.id)).model_dump()
        requests = len(ProducerBackend.requests)
        for action in ("request_changes", "start", "cancel"):
            result = await tools["review_producer_task"]({
                "task": task.id, "action": action, "comment": "Fix the remaining animation issue",
            })
            assert result["is_error"], result
            assert "Create a new task" in result["content"][0]["text"]
            assert (await producer.teams._reload(task.id)).model_dump() == before
            assert not producer.jobs.is_running(task.id)
            assert len(ProducerBackend.requests) == requests
        for target in ("todo", "pending", "in_progress", "cancelled"):
            result = await tools["update_task"]({"task": task.id, "status": target})
            assert result["is_error"], result
            assert (await producer.teams._reload(task.id)).model_dump() == before
        if status == "needs_review":
            result = await tools["review_producer_task"]({"task": task.id, "action": "approve"})
            assert not result.get("is_error"), result
            saved = await producer.teams._reload(task.id)
            assert saved.status == "done" and saved.result_type == "done"
            assert saved.result_summary == "Movement implemented"


@pytest.mark.parametrize("policy", ["careful", "independent"])
@pytest.mark.parametrize("original_approved", [False, True])
async def test_done_result_corrections_use_a_new_task_and_normal_plan_approval(
    producer, policy, original_approved,
):
    chat = await opened(producer)
    await producer.chats.producer.set_policy(chat.id, policy)
    await finished(producer, chat.id)
    task = await draft(producer, chat)
    await allow(producer, task)
    await producer.teams.submit_for_review(task.id, "Movement implemented", "done")
    tools = await tools_for(producer, chat)
    if original_approved:
        result = await tools["review_producer_task"]({"task": task.id, "action": "approve"})
        assert not result.get("is_error"), result
    if job := producer.teams._commit_jobs.get(task.id):
        await asyncio.wait_for(asyncio.shield(job), timeout=10)
    completed = (await producer.teams._reload(task.id)).model_dump()

    result = await tools["create_task"]({
        "assignee_id": DEV, "title": "Correct movement animation",
        "brief": "Fix the animation issue identified when reviewing the completed movement task.",
        "depends_on": [task.id] if original_approved else [],
    })
    assert not result.get("is_error"), result
    correction, = [t for t in await producer.chats.producer.tasks(chat.id) if t.id != task.id]
    assert correction.status == "todo" and not correction.producer_approved
    assert correction.depends_on == ([task.id] if original_approved else [])
    result = await tools["propose_producer_plan"]({"tasks": [correction.id], "summary": "Fix animation"})
    assert not result.get("is_error"), result
    if policy == "careful":
        held = await producer.teams._reload(correction.id)
        assert held.status == "todo" and not held.producer_approved
        plan = await producer.chats.get_chat(chat.id)
        await producer.chats.producer.approve_plan(chat.id, plan.producer_plan_version)
        await finished(producer, chat.id)
    await finished(producer, correction.id)
    saved = await producer.teams._reload(correction.id)
    assert saved.producer_approved and saved.status == "needs_review"
    assert (await producer.teams._reload(task.id)).model_dump() == completed


async def test_questions_can_be_resolved_without_reconfirming_existing_instructions(producer):
    chat = await opened(producer)
    task = await draft(producer, chat)
    await allow(producer, task)
    await producer.teams.submit_for_review(task.id, "Which input keys?", "question")
    await producer.chats.send(chat.id, "Use WASD", UserContext())
    await finished(producer, chat.id)
    await producer.chats.producer.review(chat.id, task.id, "request_changes", "Use WASD")
    await finished(producer, task.id)
    assert (await producer.teams._reload(task.id)).review_comment == "Use WASD"
    await producer.teams.submit_for_review(task.id, "Should Shift sprint?", "question")
    await producer.chats.producer.review(chat.id, task.id, "request_changes", "Keep the agreed movement scope; no sprint")
    await finished(producer, task.id)
    assert not (await producer.chats.get_chat(chat.id)).producer_questions


async def test_wait_is_idle_wakes_once_and_recovers_from_service_recreation(producer):
    chat = await opened(producer)
    task = await draft(producer, chat)
    await allow(producer, task)
    await producer.chats.producer.wait_for_tasks(chat.id, [task.id])
    await producer.chats.producer.reconcile()
    assert len(ProducerBackend.requests) == 1
    assert not producer.jobs.is_running(chat.id)
    await producer.teams.submit_for_review(task.id, "Movement done", "done")
    producer.chats = ChatService(producer.teams)
    await producer.chats.producer.reconcile()
    await finished(producer, chat.id)
    assert len(ProducerBackend.requests) == 2
    assert "Movement done" in ProducerBackend.requests[-1].prompt
    await producer.chats.producer.reconcile()
    assert len(ProducerBackend.requests) == 2


@pytest.mark.parametrize("result_type", ["question", "error"])
@pytest.mark.parametrize("linked_question", [False, True])
async def test_blocked_task_can_be_cancelled_on_direct_instruction_without_linked_answer(producer, result_type, linked_question):
    chat = await opened(producer)
    task = await draft(producer, chat)
    dependent = await draft(producer, chat, "Wait for a verified fix", [task.id])
    await allow(producer, task)
    await allow(producer, dependent)
    await producer.teams.submit_for_review(task.id, "Cannot continue", result_type)
    await producer.teams.update_task(task.id, {"status": "blocked"})
    await producer.chats.send(chat.id, "Cancel the blocked task; keep the dependent stopped", UserContext())
    await finished(producer, chat.id)
    if linked_question:
        # An older Producer turn may already have asked for redundant confirmation.
        await producer.chats.producer.ask(chat.id, [task.id], "Confirm cancellation again?")
    tools = await tools_for(producer, chat)
    result = await tools["review_producer_task"]({"task": task.id, "action": "cancel"})
    assert not result.get("is_error"), result
    assert (await producer.teams._reload(task.id)).status == "cancelled"
    assert (await producer.teams._reload(dependent.id)).status == "pending"
    assert not producer.jobs.is_running(dependent.id)
    assert not (await producer.chats.get_chat(chat.id)).producer_questions


async def test_cancelling_draft_withdraws_it_from_all_plans_without_approving_other_work(producer):
    chat, other = await opened(producer), await opened(producer)
    task = await draft(producer, chat)
    retained = await draft(producer, chat, "Keep this draft")
    await producer.chats.producer.propose(chat.id, [task.id, retained.id], "Two drafts")
    await producer.chats.producer.propose(other.id, [task.id], "Reuse draft")
    await producer.chats.producer.review(other.id, task.id, "cancel", "")
    first, second = await producer.chats.get_chat(chat.id), await producer.chats.get_chat(other.id)
    assert [entry["id"] for entry in first.producer_plan] == [retained.id]
    assert first.producer_plan_version == 2 and first.producer_state == "awaiting_approval"
    assert not second.producer_plan and not second.producer_plan_summary
    assert second.producer_state == "discussing"
    assert not (await producer.teams._reload(retained.id)).producer_approved
    with pytest.raises(ValueError, match="changed"):
        await producer.chats.producer.approve_plan(chat.id, 1)
    await producer.chats.producer.approve_plan(chat.id, 2)
    await finished(producer, retained.id)
    await finished(producer, chat.id)
    assert (await producer.teams._reload(task.id)).status == "cancelled"
    assert (await producer.teams._reload(retained.id)).status == "needs_review"


async def test_producer_can_correct_errors_without_a_user_round_trip(producer):
    chat = await opened(producer)
    task = await draft(producer, chat)
    await allow(producer, task)
    await producer.teams.submit_for_review(task.id, "Build failed", "error")
    await producer.teams.update_task(task.id, {"status": "blocked"})
    await producer.chats.producer.review(chat.id, task.id, "request_changes", "Correct the failing import and rerun the build")
    await finished(producer, task.id)
    assert (await producer.teams._reload(task.id)).review_comment == "Correct the failing import and rerun the build"
    assert not (await producer.chats.get_chat(chat.id)).producer_questions


async def test_live_event_listener_wakes_without_a_browser(producer):
    chat = await opened(producer)
    task = await draft(producer, chat)
    await allow(producer, task)
    await producer.chats.producer.wait_for_tasks(chat.id, [task.id])
    await producer.chats.producer.start()
    await producer.teams.submit_for_review(task.id, "Ready", "done")
    async with asyncio.timeout(5):
        while len(ProducerBackend.requests) < 2:
            await asyncio.sleep(.01)
    await finished(producer, chat.id)
    assert "Ready" in ProducerBackend.requests[-1].prompt


async def test_stopped_mode_revokes_tools_and_never_wakes(producer):
    chat = await opened(producer)
    task = await draft(producer, chat)
    await allow(producer, task)
    tools = await tools_for(producer, chat)
    await producer.chats.producer.wait_for_tasks(chat.id, [task.id])
    assert not (await tools["stop_producer_mode"]({})).get("is_error")
    result = await tools["create_task"]({"assignee_id": DEV, "title": "Too late", "brief": "x"})
    assert result["is_error"]
    await producer.teams.submit_for_review(task.id, "Ready", "done")
    await producer.chats.producer.reconcile()
    assert len(ProducerBackend.requests) == 1
    assert not (await producer.chats.get_chat(chat.id)).producer_mode


async def test_only_paused_producers_bound_tools_lose_access(producer):
    first = await opened(producer)
    second = await opened(producer)
    first_tools = await tools_for(producer, first)
    second_tools = await tools_for(producer, second)
    await producer.chats.producer.pause(first.id)
    args = {"assignee_id": DEV, "title": "Work on the shared board", "brief": "x"}
    denied = await first_tools["create_task"](args)
    assert denied["is_error"] and "paused" in denied["content"][0]["text"]
    assert not (await second_tools["create_task"](args)).get("is_error")
    board = await producer.chats.producer.tasks(second.id)
    assert [t.producer_chat_id for t in board] == [second.id]


async def test_other_chats_drafts_can_be_edited_planned_started_and_reviewed(producer):
    chat = await opened(producer)
    other = await opened(producer)
    task = await draft(producer, other)
    tools = await tools_for(producer, chat)
    result = await tools["update_task"]({"task": task.id, "brief": "Reuse this existing work"})
    assert not result.get("is_error"), result
    assert (await producer.teams._reload(task.id)).brief == "Reuse this existing work"
    with pytest.raises(ValueError, match="not been approved"):
        await producer.chats.producer.review(chat.id, task.id, "start", "")
    await producer.chats.producer.propose(chat.id, [task.id], "Continue existing draft")
    await producer.chats.producer.approve_plan(chat.id, 1)
    await finished(producer, task.id)
    await finished(producer, chat.id)
    await producer.chats.producer.review(chat.id, task.id, "approve", "")
    saved = await producer.teams._reload(task.id)
    assert saved.status == "done"
    assert saved.producer_chat_id == other.id, "coordination must not rewrite task origin"


@pytest.mark.parametrize(("action", "status"), [("start", "todo"), ("request_changes", "needs_review")])
async def test_cross_chat_review_preserves_work_held_by_paused_producer(producer, action, status):
    owner = await opened(producer)
    other = await opened(producer)
    task = await draft(producer, owner)
    await allow(producer, task, status)
    await producer.chats.producer.pause(owner.id)
    board = json.loads(await producer.chats.producer.context(other.id))
    assert board["tasks"][0]["automatic_dispatch_paused"]
    with pytest.raises(ValueError, match="Resume that chat"):
        await producer.chats.producer.review(other.id, task.id, action, "Change this next")
    stored = await producer.teams._reload(task.id)
    assert stored.status == status and not stored.review_comment
    assert not producer.jobs.is_running(task.id)


@pytest.mark.parametrize("created_by", [None, f"{TEAM}__designer"])
async def test_existing_tasks_need_no_producer_approval_to_edit_start_or_review(producer, created_by):
    chat = await opened(producer)
    task = await producer.teams.create_task(team_id=TEAM, assignee_id=DEV,
                                            title="Existing task", brief="User's existing work",
                                            created_by=created_by)
    tools = await tools_for(producer, chat)
    result = await tools["update_task"]({"task": task.id, "title": "Updated existing task"})
    assert not result.get("is_error"), result
    assert (await producer.teams._reload(task.id)).title == "Updated existing task"
    await producer.chats.producer.review(chat.id, task.id, "start", "")
    await finished(producer, task.id)
    await producer.teams.submit_for_review(task.id, "Which keys?", "question")
    bypass = await tools["update_task"]({"task": task.id, "status": "done"})
    assert bypass["is_error"]
    await producer.chats.producer.ask(chat.id, [task.id], "Which keys should be used?")
    await producer.chats.send(chat.id, "WASD", UserContext())
    await finished(producer, chat.id)
    await producer.chats.producer.review(chat.id, task.id, "request_changes", "Use WASD")
    await finished(producer, task.id)
    await producer.chats.producer.review(chat.id, task.id, "approve", "")
    saved = await producer.teams._reload(task.id)
    assert saved.status == "done" and saved.review_comment == "Use WASD"
    assert not saved.producer_chat_id and not saved.producer_approved


async def test_wait_and_context_include_existing_work_from_the_whole_team(producer):
    manual = await producer.teams.create_task(team_id=TEAM, assignee_id=DEV,
                                              title="Manual task", brief="Existing work")
    await producer.teams.update_task(manual.id, {"status": "pending"})
    other = await opened(producer)
    task = await draft(producer, other, "Other chat's work")
    await allow(producer, task)
    held = await draft(producer, other, "Still needs approval")
    chat = await opened(producer)
    board = json.loads(await producer.chats.producer.context(chat.id))
    assert {t["id"] for t in board["tasks"]} == {manual.id, task.id, held.id}
    assert next(t for t in board["tasks"] if t["id"] == manual.id)["approved"]
    await producer.chats.producer.wait_for_tasks(chat.id, [])
    saved = await producer.chats.get_chat(chat.id)
    assert {t["id"] for t in saved.producer_watch} == {manual.id, task.id}
    await producer.teams.submit_for_review(manual.id, "Existing work ready", "done")
    before = len(ProducerBackend.requests)
    producer.chats = ChatService(producer.teams)
    await producer.chats.producer.reconcile()
    await finished(producer, chat.id)
    assert len(ProducerBackend.requests) == before + 1
    assert "Existing work ready" in ProducerBackend.requests[-1].prompt
    await producer.chats.producer.reconcile()
    assert len(ProducerBackend.requests) == before + 1


async def test_two_chats_cannot_dispatch_the_same_existing_task_twice(producer):
    first, second = await opened(producer), await opened(producer)
    task = await producer.teams.create_task(team_id=TEAM, assignee_id=DEV,
                                            title="Existing Todo", brief="Only run once")
    for chat in (first, second):
        await producer.chats.producer.propose(chat.id, [task.id], "Use this existing task")
    results = await asyncio.gather(
        producer.chats.producer.approve_plan(first.id, 1),
        producer.chats.producer.approve_plan(second.id, 1), return_exceptions=True,
    )
    assert sum(isinstance(r, ValueError) for r in results) == 1
    assert sum(isinstance(r, AgentChat) for r in results) == 1
    for job_id in (first.id, second.id, task.id):
        await finished(producer, job_id)
    assert (await producer.teams._reload(task.id)).status == "needs_review"
    assert sum(r.session_key == task.id for r in ProducerBackend.requests) == 1


async def test_team_boundary_is_preserved_for_producer_tools_and_routing(producer):
    async with producer.db.session() as session:
        session.add(Team(id="team_other", name="Other team"))
    async with producer.db.session() as session:
        session.add(TeamMember(id="other_dev", team_id="team_other", name="Other dev", role_title="Dev"))
    task = await producer.teams.create_task(team_id="team_other", assignee_id="other_dev",
                                            title="Other team's task", brief="Unrelated")
    chat = await opened(producer)
    with pytest.raises(ValueError, match="team board"):
        await producer.chats.producer.propose(chat.id, [task.id], "Wrong team")
    with pytest.raises(ValueError, match="team board"):
        await producer.chats.producer.review(chat.id, task.id, "start", "")
    assert task.id not in {t.id for t in await producer.chats.producer.tasks(chat.id)}
    tools = await tools_for(producer, chat)
    assert (await tools["update_task"]({"task": task.id, "brief": "Wrong team"}))["is_error"]
    with pytest.raises(RoutingError):
        await producer.teams.update_task(task.id, {"brief": "Wrong team"}, actor_id=PRODUCER)
    with pytest.raises(RoutingError):
        await producer.teams.approve(task.id, actor_id=PRODUCER)


async def test_compaction_replaces_history_and_continues_with_one_summary(producer, monkeypatch):
    chat = await opened(producer)
    previous_ids = {m.id for m in await producer.chats.messages(chat.id)}
    other = await producer.chats.create_chat(PRODUCER)
    untouched = await producer.chats.record(other.id, PRODUCER, role="user", kind="text", text="Other chat")
    monkeypatch.setattr("voxarium_studio.teams.producer.COMPACT_CHARS", 50)
    await producer.chats.send(chat.id, "Please keep the approved scope and the movement task ids.", UserContext())
    await finished(producer, chat.id)
    summary, resumed = ProducerBackend.requests[-2:]
    assert summary.session_key.endswith(":summary")
    assert summary.mcp_servers == {} and not summary.resume_session_id
    assert summary.text_only
    assert resumed.resume_session_id is None
    assert "Summary: build a small game" in resumed.prompt
    saved = await producer.chats.get_chat(chat.id)
    assert saved.producer_summary and saved.producer_compacted_through
    transcript = await producer.chats.messages(chat.id)
    assert not previous_ids.intersection(m.id for m in transcript)
    assert not any("Please keep the approved scope" in m.text for m in transcript)
    assert transcript[0].kind == "producer_compacted"
    assert transcript[0].text == saved.producer_summary
    assert transcript[0].data == {"replaces_history": True}
    assert saved.producer_compacted_through == transcript[0].id
    assert any(m.kind == "text" for m in transcript[1:])
    assert [m.id for m in await producer.chats.messages(other.id)] == [untouched.id]

    previous_ids = {m.id for m in transcript}
    await producer.chats.send(chat.id, "Continue with the next step", UserContext())
    await finished(producer, chat.id)
    summary_request = ProducerBackend.requests[-2]
    assert summary_request.max_turns == 2
    assert f"Previous summary:\n{saved.producer_summary}" in summary_request.prompt
    assert "[system/producer_compacted]" not in summary_request.prompt
    transcript = await producer.chats.messages(chat.id)
    assert not previous_ids.intersection(m.id for m in transcript)
    assert sum(m.kind == "producer_compacted" for m in transcript) == 1
    # A new service reads the replacement from SQLite, not an in-memory projection.
    assert [m.id for m in await ChatService(producer.teams).messages(chat.id)] == [m.id for m in transcript]


async def test_compaction_keeps_messages_recorded_while_summarizing(producer):
    chat = await opened(producer)
    request = ProducerBackend.requests[-1]

    class ConcurrentBackend:
        async def run(self, request):
            await producer.chats.record(chat.id, PRODUCER, role="user", kind="text", text="New requirements")
            yield AgentEvent(kind="text", text="Captured conversation summary")

    await producer.chats.producer.compact(chat.id, ConcurrentBackend(), request, force=True)
    transcript = await producer.chats.messages(chat.id)
    assert [(m.kind, m.text) for m in transcript] == [
        ("producer_compacted", "Captured conversation summary"), ("text", "New requirements"),
    ]


@pytest.mark.parametrize("cancel", [False, True])
async def test_empty_or_cancelled_compaction_keeps_history(producer, cancel):
    chat = await opened(producer)
    previous = await producer.chats.messages(chat.id)

    class UnfinishedBackend:
        async def run(self, request):
            if cancel:
                yield AgentEvent(kind="text", text="Partial summary")
                raise asyncio.CancelledError()
            yield AgentEvent(kind="result", text="", is_final=True)

    with pytest.raises(asyncio.CancelledError if cancel else RuntimeError):
        await producer.chats.producer.compact(chat.id, UnfinishedBackend(), ProducerBackend.requests[-1], force=True)
    saved = await producer.chats.get_chat(chat.id)
    assert saved.agent_session_id == chat.agent_session_id
    assert not saved.producer_summary and not saved.producer_compacted_through
    assert [m.id for m in await producer.chats.messages(chat.id)] == [m.id for m in previous]


async def test_overflow_compacts_and_retries_once_but_ordinary_errors_do_not_loop(producer):
    chat = await opened(producer)
    ProducerBackend.overflow = True
    await producer.chats.send(chat.id, "Continue", UserContext())
    await finished(producer, chat.id)
    assert len(ProducerBackend.requests) == 4  # start, overflow, summary, fresh turn
    assert (await producer.chats.get_chat(chat.id)).error is None
    ProducerBackend.fail = True
    await producer.chats.send(chat.id, "Continue", UserContext())
    await finished(producer, chat.id)
    await producer.chats.producer.reconcile()
    assert len(ProducerBackend.requests) == 5
    assert (await producer.chats.get_chat(chat.id)).producer_state == "error"


@pytest.mark.parametrize("overflow", [False, True])
async def test_required_context_is_restored_after_producer_compaction(producer, monkeypatch, overflow):
    folder = producer.paths.skills / "producer-required"
    folder.mkdir(parents=True)
    (folder / "SKILL.md").write_text("# Required\nAPPLY PRODUCER SKILL", encoding="utf-8")
    producer.paths.docs.mkdir(parents=True, exist_ok=True)
    (producer.paths.docs / "producer-required.md").write_text("FULL PRODUCER DOCUMENT", encoding="utf-8")
    producer.config.teams.required_skills = ["producer-required"]
    producer.config.teams.required_documents = ["producer-required.md"]
    chat = await opened(producer)
    initial = ProducerBackend.requests[-1]
    assert initial.prompt.count("APPLY PRODUCER SKILL") == 1
    assert initial.prompt.count("FULL PRODUCER DOCUMENT") == 1
    if overflow:
        ProducerBackend.overflow = True
    else:
        monkeypatch.setattr("voxarium_studio.teams.producer.COMPACT_CHARS", 50)
    await producer.chats.send(chat.id, "Continue the approved scope", UserContext())
    await finished(producer, chat.id)
    summary, fresh = ProducerBackend.requests[-2:]
    assert summary.session_key.endswith(":summary")
    assert "APPLY PRODUCER SKILL" not in summary.prompt
    assert fresh.resume_session_id is None
    assert fresh.prompt.count("APPLY PRODUCER SKILL") == 1
    assert fresh.prompt.count("--- BEGIN REQUIRED SKILL: voxarium_studio:producer ---") == 1
    assert fresh.prompt.count("FULL PRODUCER DOCUMENT") == 1


async def test_new_tools_are_documented_and_do_not_leak_to_regular_chats(producer):
    chat = await opened(producer)
    toolset = await tools_for(producer, chat)
    skills = Path(__file__).parents[1] / "voxarium_studio/teams/skill_bundle/skills"
    text = (skills / "producer/SKILL.md").read_text(encoding="utf-8")
    studio = (skills / "studio/SKILL.md").read_text(encoding="utf-8")
    special = {"propose_producer_plan", "wait_producer_tasks", "ask_producer_user",
               "review_producer_task", "stop_producer_mode"}
    assert special <= toolset.keys()
    assert all(name in text for name in special)
    assert all(name not in studio for name in special)
    assert "Producer Mode" not in studio
    async with producer.db.session() as session:
        member = await session.get(TeamMember, PRODUCER)
    ordinary = build_toolset(producer.teams, member=member)
    assert not special.intersection(t.name for t in ordinary.definitions)


async def test_shutdown_preserves_an_interrupted_turn_but_user_cancel_does_not(producer):
    gate = asyncio.Event()

    class SlowBackend(ProducerBackend):
        async def run(self, request):
            yield AgentEvent(kind="text", text="Working", session_id="slow-session")
            await gate.wait()

    register_backend("producer_slow", SlowBackend)
    chat = await opened(producer)
    async with producer.db.session() as session:
        member = await session.get(TeamMember, PRODUCER)
        member.agent_backend = "producer_slow"
        session.add(member)
    await producer.chats.send(chat.id, "Plan the work", UserContext())
    async with asyncio.timeout(5):
        while not any(m.text == "Working" for m in await producer.chats.messages(chat.id)):
            await asyncio.sleep(.01)
    producer.chats.closing = True
    await producer.chats._cancel_turn(chat.id)
    assert (await producer.chats.get_chat(chat.id)).producer_state == "running"
    producer.chats.closing = False
    await producer.chats.producer.reconcile()
    assert producer.jobs.is_running(chat.id)
    # §10.8: a message to a working Producer is queued for it, not refused.
    queued = await producer.chats.send(chat.id, "Second message", UserContext())
    assert queued.data["delivery"] == "queued"
    await producer.chats.cancel(chat.id)
    assert (await producer.chats.get_chat(chat.id)).producer_state == "discussing"
    assert not producer.jobs.is_running(chat.id)


async def test_restart_finishes_approved_dispatch_without_restarting_completed_tasks(producer):
    chat = await opened(producer)
    completed = await draft(producer, chat, "Completed")
    pending = await draft(producer, chat, "Accepted but not dispatched")
    await allow(producer, completed, "done")
    await allow(producer, pending, "todo")
    await producer.chats.producer._save(chat.id, producer_state="running",
                                        producer_release_pending=[completed.id, pending.id])
    producer.chats = ChatService(producer.teams)
    await producer.chats.producer.reconcile()
    await finished(producer, chat.id)
    await finished(producer, pending.id)
    assert (await producer.teams._reload(completed.id)).status == "done"
    assert (await producer.teams._reload(pending.id)).status == "needs_review"
    assert not (await producer.chats.get_chat(chat.id)).producer_release_pending
    assert not any(r.session_key == completed.id for r in ProducerBackend.requests)


async def test_interrupted_agent_task_is_reported_instead_of_waiting_forever(producer):
    chat = await opened(producer)
    task = await draft(producer, chat)
    await allow(producer, task, "in_progress")
    reply = await producer.chats.producer.wait_for_tasks(chat.id, [task.id])
    assert "interrupted" in reply
    await producer.chats.producer.review(chat.id, task.id, "request_changes", "Continue the approved task")
    await finished(producer, task.id)
    assert (await producer.teams._reload(task.id)).status == "needs_review"


async def test_failed_compaction_keeps_the_existing_session_and_history(producer, monkeypatch):
    chat = await opened(producer)
    previous_session = chat.agent_session_id
    monkeypatch.setattr("voxarium_studio.teams.producer.COMPACT_CHARS", 1)
    ProducerBackend.fail = True
    await producer.chats.send(chat.id, "Preserve these requirements", UserContext())
    await finished(producer, chat.id)
    saved = await producer.chats.get_chat(chat.id)
    assert saved.agent_session_id == previous_session
    assert not saved.producer_compacted_through
    assert saved.producer_state == "error"
    assert any(m.text == "Preserve these requirements" for m in await producer.chats.messages(chat.id))


async def test_immediate_stop_does_not_leave_the_chat_id_registered():
    from voxarium_studio.engine.jobs import JobEngine
    jobs = JobEngine()

    async def turn(handle):
        await asyncio.Event().wait()

    handle = jobs.submit("producer-chat", "agent_chat", turn)
    await jobs.cancel("producer-chat")
    with pytest.raises(asyncio.CancelledError):
        await handle.task
    assert jobs.get("producer-chat") is None
    next_handle = jobs.submit("producer-chat", "agent_chat", turn)
    await jobs.cancel("producer-chat")
    with pytest.raises(asyncio.CancelledError):
        await next_handle.task


async def test_drafts_from_a_deleted_chat_can_be_approved_in_another_chat(producer):
    chat = await opened(producer)
    task = await draft(producer, chat)
    assert await producer.chats.delete_chat(chat.id)
    assert (await producer.teams._reload(task.id)).status == "todo"
    other = await opened(producer)
    await producer.chats.producer.propose(other.id, [task.id], "Continue the surviving draft")
    await producer.chats.producer.approve_plan(other.id, 1)
    await finished(producer, task.id)
    await finished(producer, other.id)
    assert (await producer.teams._reload(task.id)).status == "needs_review"


async def test_independent_plan_releases_only_its_tasks_without_a_user_approval(producer):
    chat = await opened(producer)
    await producer.chats.producer.set_policy(chat.id, "independent")
    await finished(producer, chat.id)
    assert "Decision policy: Independent" in ProducerBackend.requests[-1].system_prompt
    first = await draft(producer, chat)
    dependent = await draft(producer, chat, "Animation", [first.id])
    unrelated = await draft(producer, chat, "Unrelated draft")
    result = await producer.chats.producer.propose(chat.id, [first.id, dependent.id], "Build movement")
    assert "Continue coordinating" in result
    await finished(producer, first.id)
    assert (await producer.teams._reload(first.id)).status == "needs_review"
    assert (await producer.teams._reload(dependent.id)).status == "pending"
    assert not (await producer.teams._reload(unrelated.id)).producer_approved
    saved = await producer.chats.get_chat(chat.id)
    assert not saved.producer_plan and not saved.producer_release_pending
    approval = next(m for m in await producer.chats.messages(chat.id) if m.kind == "producer_plan")
    assert approval.role == "assistant" and approval.data["automatic"]
    producer.chats = ChatService(producer.teams)
    assert (await producer.chats.get_chat(chat.id)).producer_policy == "independent"


async def test_independent_plan_tool_can_release_during_the_current_producer_job(producer):
    chat = await opened(producer)
    await producer.chats.producer.set_policy(chat.id, "independent")
    await finished(producer, chat.id)
    task = await draft(producer, chat)
    tools = await tools_for(producer, chat)
    results = []
    async def turn(handle):
        results.append(await tools["propose_producer_plan"]({"tasks": [task.id], "summary": "Build movement"}))
    producer.jobs.submit(chat.id, "agent_chat", turn)
    await finished(producer, chat.id)
    await finished(producer, task.id)
    assert not results[0].get("is_error"), results
    assert (await producer.teams._reload(task.id)).producer_approved
    assert len([m for m in await producer.chats.messages(chat.id) if m.kind == "producer_plan"]) == 1


async def test_switching_an_awaiting_plan_to_independent_resumes_it_once(producer):
    chat = await opened(producer)
    task = await draft(producer, chat)
    await producer.chats.producer.propose(chat.id, [task.id], "Build movement")
    await producer.chats.producer.set_policy(chat.id, "independent")
    await finished(producer, chat.id)
    await finished(producer, task.id)
    turns = len(ProducerBackend.requests)
    await producer.chats.producer.set_policy(chat.id, "independent")
    assert len(ProducerBackend.requests) == turns
    assert (await producer.teams._reload(task.id)).producer_approved
    assert not (await producer.chats.get_chat(chat.id)).producer_plan


async def test_independent_switch_preserves_careful_when_the_pending_plan_is_stale(producer):
    chat = await opened(producer)
    task = await draft(producer, chat)
    await producer.chats.producer.propose(chat.id, [task.id], "Build movement")
    await producer.teams.update_task(task.id, {"brief": "Changed scope"})
    with pytest.raises(ValueError, match="changed"):
        await producer.chats.producer.set_policy(chat.id, "independent")
    saved = await producer.chats.get_chat(chat.id)
    assert saved.producer_policy == "careful" and saved.producer_state == "awaiting_approval"
    assert not (await producer.teams._reload(task.id)).producer_approved


async def test_general_question_is_durable_and_answer_clears_wait_state(producer):
    chat = await opened(producer)
    await producer.chats.producer.ask(chat.id, [], "Which platform?")
    producer.chats = ChatService(producer.teams)
    saved = await producer.chats.get_chat(chat.id)
    assert saved.producer_state == "awaiting_user"
    assert saved.producer_questions[0]["task"] is None
    assert json.loads(await producer.chats.producer.context(chat.id))["questions"][0]["task_id"] is None
    task = await draft(producer, chat)
    await producer.chats.producer.ask(chat.id, [task.id], "Which controls?")
    saved = await producer.chats.get_chat(chat.id)
    assert len(saved.producer_questions) == 2, "a task question must preserve an unresolved general question"
    # The state transition happens before a potentially queued agent receives its slot.
    from types import SimpleNamespace
    await producer.chats.producer.on_user_message(saved, SimpleNamespace(id="reply"))
    answered = await producer.chats.get_chat(chat.id)
    assert answered.producer_state == "running"
    assert answered.producer_questions[0]["answer_message_id"] == "reply"
    await producer.chats.producer.disable(chat.id)
    assert not (await producer.chats.get_chat(chat.id)).producer_questions


async def test_independent_escalates_only_critical_blockers_even_without_linked_tasks(producer):
    chat = await opened(producer)
    await producer.chats.producer.set_policy(chat.id, "independent")
    await finished(producer, chat.id)
    task = await draft(producer, chat)
    await allow(producer, task)
    await producer.chats.producer.wait_for_tasks(chat.id, [task.id])
    before = len(await producer.chats.messages(chat.id))
    answer = await producer.chats.producer.ask(chat.id, [], "Which naming style?")
    assert "No question was sent" in answer
    assert len(await producer.chats.messages(chat.id)) == before
    assert (await producer.chats.get_chat(chat.id)).producer_state == "waiting"
    await producer.chats.producer.ask(chat.id, [], "Please grant access",
        "Existing credentials were rejected and the alternative account has no access.")
    saved = await producer.chats.get_chat(chat.id)
    assert saved.producer_state == "awaiting_user" and saved.producer_mode
    assert saved.producer_questions[0]["critical_reason"].startswith("Existing credentials")
    await producer.chats.producer.reconcile()
    assert not producer.jobs.is_running(chat.id), "waiting for a person must never auto-wake"


async def test_mode_change_during_a_turn_is_rejected_and_stop_preserves_selection(producer):
    chat = await opened(producer)
    running = asyncio.Event()
    async def turn(handle):
        running.set()
        await asyncio.Event().wait()
    producer.jobs.submit(chat.id, "agent_chat", turn)
    await running.wait()
    with pytest.raises(ChatBusy):
        await producer.chats.producer.set_policy(chat.id, "independent")
    assert (await producer.chats.get_chat(chat.id)).producer_policy == "careful"
    await producer.chats.producer.disable(chat.id)
    await producer.chats.producer.set_policy(chat.id, "independent")
    await producer.chats.producer.enable(chat.id)
    await finished(producer, chat.id)
    assert (await producer.chats.get_chat(chat.id)).producer_policy == "independent"


def test_policy_api_validates_values_and_builtin_member(client):
    chat = client.post("/api/chats", json={"member_id": PRODUCER}).json()
    path = f'/api/chats/{chat["id"]}/producer/policy'
    assert chat["producer_policy"] == "careful"
    changed = client.patch(path, json={"policy": "independent"})
    assert changed.status_code == 200 and changed.json()["producer_policy"] == "independent"
    assert not changed.json()["is_running"], "choosing a policy before Start must not run an agent"
    assert client.patch(path, json={"policy": "unknown"}).status_code == 422
    other = client.post("/api/chats", json={"member_id": DEV}).json()
    assert client.patch(f'/api/chats/{other["id"]}/producer/policy',
                        json={"policy": "careful"}).status_code == 409


async def test_independent_can_request_initial_goal_with_existing_team_tasks(producer):
    chat = await opened(producer)
    await producer.chats.producer.set_policy(chat.id, "independent")
    await finished(producer, chat.id)
    await draft(producer, chat, "Existing work on the team board")
    result = await producer.chats.producer.ask(chat.id, [], "What would you like to accomplish?", purpose="goal")
    assert "Question shown" in result
    assert (await producer.chats.get_chat(chat.id)).producer_state == "awaiting_user"


async def test_existing_database_gains_careful_policy_without_changing_waits(producer):
    from sqlalchemy import text
    from voxarium_studio.core.db import Database
    chat = await opened(producer)
    task = await draft(producer, chat)
    await allow(producer, task)
    await producer.chats.producer.wait_for_tasks(chat.id, [task.id])
    async with producer.db._engine.begin() as conn:
        await conn.execute(text('ALTER TABLE agent_chat DROP COLUMN producer_policy'))
        await conn.execute(text('ALTER TABLE agent_chat DROP COLUMN producer_paused'))
        await conn.execute(text('ALTER TABLE agent_chat DROP COLUMN estimate_task_cost_usd'))
        await conn.execute(text('ALTER TABLE team_task DROP COLUMN pause_resume_pending'))
    reopened = Database(producer.db.path)
    try:
        await reopened.create_all()
        async with reopened.session() as session:
            restored = await session.get(AgentChat, chat.id)
            assert restored.producer_policy == "careful"
            assert restored.producer_paused is False
            assert restored.estimate_task_cost_usd == 150.0
            assert (await session.get(TeamTask, task.id)).pause_resume_pending is False
            assert restored.producer_state == "waiting"
            assert restored.producer_watch[0]["id"] == task.id
    finally:
        await reopened.close()


async def test_pause_preserves_owned_sessions_while_manual_work_uses_the_same_solo_queue(producer, monkeypatch):
    await solo_on(producer)
    chat = await opened(producer)
    other = await opened(producer)
    task = await producer.teams.create_task(team_id=TEAM, assignee_id=DEV,
                                           title="Planned task", brief="Keep working", auto_start=False,
                                           producer_chat_id=chat.id)
    await allow(producer, task)
    queued = await draft(producer, chat, "Next planned task")
    await allow(producer, queued, "todo")
    started: dict[str, asyncio.Event] = {}
    requests = []

    async def hold(self, request):
        requests.append(request)
        yield AgentEvent(kind="text", text="Saved progress", session_id=f"session-{request.session_key}")
        started.setdefault(request.session_key, asyncio.Event()).set()
        await asyncio.Event().wait()

    monkeypatch.setattr(ProducerBackend, "run", hold)
    await producer.teams.start_task(task.id)
    await producer.chats.send(chat.id, "Continue the goal", UserContext())
    async with asyncio.timeout(5):
        while not all(started.get(key) and started[key].is_set() for key in (chat.id, task.id)):
            await asyncio.sleep(.01)
    await producer.teams.update_task(queued.id, {"status": "pending", "auto_start": True})
    assert not producer.jobs.is_running(queued.id)
    saved = await producer.chats.producer.pause(chat.id)
    assert saved.producer_mode and saved.producer_paused and saved.producer_state == "running"
    assert saved.agent_session_id == f"session-{chat.id}"
    assert not (await producer.chats.get_chat(other.id)).producer_paused
    parked = await producer.teams._reload(task.id)
    assert parked.status == "pending" and parked.pause_resume_pending and not parked.auto_start
    assert parked.agent_session_id == f"session-{task.id}"
    assert not any(producer.jobs.is_running(key) for key in (chat.id, task.id, queued.id))
    with pytest.raises(ValueError, match="paused"):
        await producer.teams.start_task(task.id)
    with pytest.raises(ValueError, match="Resume"):
        await producer.chats.send(chat.id, "Do not bypass Pause", UserContext())
    with pytest.raises(ValueError, match="Resume"):
        await producer.chats.producer.set_policy(chat.id, "independent")
    await producer.chats.producer.reconcile()
    await producer.teams._start_if_ready([task.id, queued.id])
    assert len(requests) == 2

    manual = await producer.teams.create_task(team_id=TEAM, assignee_id=DEV,
                                             title="Manual task", brief="Run now", auto_start=False)
    await producer.teams.update_task(manual.id, {"status": "pending"}, from_board=True)
    await producer.teams.start_task(manual.id, from_board=True)
    await producer.chats.producer.resume(chat.id)
    async with asyncio.timeout(5):
        while len(requests) < 4:
            await asyncio.sleep(.01)
    await producer.chats.producer.resume(chat.id)
    assert len(requests) == 4
    assert producer.jobs.is_running(manual.id)
    assert not any(producer.jobs.is_running(k) for k in (task.id, queued.id))
    await producer.jobs.cancel(manual.id)
    async with asyncio.timeout(5):
        while len(requests) < 5:
            await asyncio.sleep(.01)
    assert not producer.jobs.is_running(queued.id), "Resume still respects Solo Mode order"
    resumed = {request.session_key: request for request in requests[2:]}
    assert resumed[task.id].resume_session_id == f"session-{task.id}"
    assert resumed[chat.id].resume_session_id == f"session-{chat.id}"
    await producer.chats.producer.pause(chat.id)


async def test_project_startup_holds_producer_tasks_timers_and_approved_dispatch(producer):
    from datetime import timedelta
    from voxarium_studio.core.context import AppContext
    from voxarium_studio.core.security import AuthSettings
    from voxarium_studio.models.base import utcnow

    chat = await opened(producer)
    other = await opened(producer)
    other_task = await draft(producer, other, "Keep the second chat paused")
    await allow(producer, other_task, "todo")
    interrupted = await draft(producer, chat, "Interrupted before restart")
    await allow(producer, interrupted, "in_progress")
    queued = await draft(producer, chat, "Approved but not dispatched")
    await allow(producer, queued, "todo")
    completed = await draft(producer, chat, "Already done")
    await allow(producer, completed, "done")
    limited = await draft(producer, chat, "Usage limit expired")
    await allow(producer, limited, "blocked")
    async with producer.db.session() as session:
        row = await session.get(TeamTask, interrupted.id)
        row.agent_session_id = "task-before-restart"
        session.add(row)
        row = await session.get(TeamTask, limited.id)
        row.limit_resume_at = utcnow() - timedelta(seconds=5)
        session.add(row)
        team = await session.get(Team, TEAM)
        team.autostart_tasks = True
        session.add(team)
        await session.flush()
        await producer.teams.store.save_from_db(session, TEAM)
    await producer.chats.producer._save(chat.id, producer_state="running",
                                        producer_release_pending=[queued.id, completed.id])
    # A second context models opening the persisted project after a crashed process.
    restored = AppContext.create(str(producer.paths.root), AuthSettings())
    try:
        before = len(ProducerBackend.requests)
        await restored.startup()
        await restored.chats.producer.reconcile()
        await restored.teams._resume_after_limit(limited.id)
        assert not restored.jobs.jobs()
        assert len(ProducerBackend.requests) == before
        saved = await restored.chats.get_chat(chat.id)
        assert saved.producer_paused and saved.producer_release_pending
        assert (await restored.chats.get_chat(other.id)).producer_paused
        assert (await restored.teams._reload(interrupted.id)).status == "pending"
        assert (await restored.teams._reload(limited.id)).status == "blocked"
        assert (await restored.teams._reload(queued.id)).status == "todo"
        assert (await restored.teams._reload(completed.id)).status == "done"
        await restored.chats.producer.resume(chat.id)
        await finished(restored, chat.id)
        await finished(restored, interrupted.id)
        assert any(r.session_key == interrupted.id and r.resume_session_id == "task-before-restart"
                   for r in ProducerBackend.requests[before:])
        assert not any(r.session_key == completed.id for r in ProducerBackend.requests[before:])
        assert (await restored.chats.get_chat(other.id)).producer_paused
        assert (await restored.teams._reload(other_task.id)).status == "todo"
    finally:
        await restored.shutdown()


async def test_pause_keeps_unapproved_plan_and_rejects_approval_until_resume(producer):
    chat = await opened(producer)
    task = await draft(producer, chat)
    await producer.chats.producer.propose(chat.id, [task.id], "Build the feature")
    saved = await producer.chats.producer.pause(chat.id)
    with pytest.raises(ValueError, match="Resume"):
        await producer.chats.producer.approve_plan(chat.id, saved.producer_plan_version)
    await producer.chats.producer.resume(chat.id)
    resumed = await producer.chats.get_chat(chat.id)
    assert resumed.producer_plan == saved.producer_plan
    assert resumed.producer_state == "awaiting_approval"
    assert not producer.jobs.is_running(task.id)


def test_pause_resume_api_validates_chat_and_returns_durable_state(client):
    chat = client.post("/api/chats", json={"member_id": DEV}).json()
    assert client.post(f"/api/chats/{chat['id']}/producer/pause").status_code == 409
    assert client.post("/api/chats/missing/producer/resume").status_code == 404
    chat = client.post("/api/chats", json={"member_id": PRODUCER}).json()
    ctx = client.app.state.ctx

    async def activate_without_model():
        await ctx.chats.producer._save(chat["id"], producer_mode=True, producer_started=True,
                                      producer_state="awaiting_user")

    client.portal.call(activate_without_model)
    paused = client.post(f"/api/chats/{chat['id']}/producer/pause")
    assert paused.status_code == 200 and paused.json()["producer_paused"]
    assert client.get(f"/api/chats/{chat['id']}").json()["producer_paused"]
    resumed = client.post(f"/api/chats/{chat['id']}/producer/resume")
    assert resumed.status_code == 200 and not resumed.json()["producer_paused"]
    assert not resumed.json()["is_running"]


def test_board_start_and_second_chat_work_while_first_chat_stays_paused(client, monkeypatch):
    class Backend(ProducerBackend):
        requests = []
        fail = overflow = False

    monkeypatch.setattr("voxarium_studio.teams.chat.get_backend", lambda _: Backend())
    monkeypatch.setattr("voxarium_studio.teams.orchestrator.get_backend", lambda _: Backend())
    ctx = client.app.state.ctx
    workspace = {"member_id": PRODUCER, "producer_mode": True}
    first = client.post("/api/chats", json=workspace).json()["id"]
    assert client.post(f"/api/chats/{first}/messages", json={"text": "First goal"}).status_code == 202
    client.portal.call(finished, ctx, first)
    assert client.post(f"/api/chats/{first}/producer/pause").json()["producer_paused"]

    board = f"/api/teams/{TEAM}/tasks"
    task = client.post(board, json={"assignee_id": DEV, "title": "Manual work", "auto_start": False})
    assert task.status_code == 201
    task_id = task.json()["id"]
    assert client.patch(f"{board}/{task_id}", json={"status": "pending"}).status_code == 200
    assert client.post(f"{board}/{task_id}/start").status_code == 202
    client.portal.call(finished, ctx, task_id)
    assert client.get(f"{board}/{task_id}").json()["status"] == "needs_review"

    second = client.post("/api/chats", json=workspace).json()["id"]
    assert client.post(f"/api/chats/{second}/messages", json={"text": "Second goal"}).status_code == 202
    client.portal.call(finished, ctx, second)
    assert client.get(f"/api/chats/{second}").json()["producer_state"] == "awaiting_user"
    assert client.get(f"/api/chats/{first}").json()["producer_paused"]
    assert [request.session_key for request in Backend.requests] == [first, task_id, second]


async def test_pause_during_final_cleanup_does_not_requeue_saved_result(producer, monkeypatch):
    chat = await opened(producer)
    task = await draft(producer, chat, "Finishing")
    await allow(producer, task)
    saved = asyncio.Event()

    async def hold_after_result(self, request):
        yield AgentEvent(kind="text", text="Result saved", session_id="finished-session")
        await producer.teams._set_task_status(task.id, "needs_review")
        saved.set()
        await asyncio.Event().wait()

    monkeypatch.setattr(ProducerBackend, "run", hold_after_result)
    await producer.teams.start_task(task.id)
    await asyncio.wait_for(saved.wait(), 5)
    await producer.chats.producer.pause(chat.id)
    assert (await producer.teams._reload(task.id)).status == "needs_review"
    await producer.chats.producer.resume(chat.id)
    assert not producer.jobs.is_running(task.id)


async def test_pause_before_submitted_job_enters_keeps_manual_start_intent(producer):
    chat = await opened(producer)
    task = await producer.teams.create_task(team_id=TEAM, assignee_id=DEV,
                                           title="Just submitted", brief="Resume this", auto_start=False,
                                           producer_chat_id=chat.id)
    await allow(producer, task)
    await producer.teams.start_task(task.id, from_board=True)
    # Exactly the first operation of Pause, before its asynchronous database scan.
    producer.teams.paused_producer_chats[chat.id] = set()
    await finished(producer, task.id)
    stored = await producer.teams._reload(task.id)
    assert stored.status == "pending" and stored.pause_resume_pending
    assert not any(r.session_key == task.id for r in ProducerBackend.requests)
    await producer.chats.producer.pause(chat.id)
    await producer.chats.producer.resume(chat.id)
    await finished(producer, task.id)
    assert any(r.session_key == task.id for r in ProducerBackend.requests)


async def test_start_all_releases_approved_paused_work_after_its_dependency(producer):
    chat = await opened(producer)
    await producer.chats.producer.pause(chat.id)
    dependency = await producer.teams.create_task(team_id=TEAM, assignee_id=DEV, title="Prerequisite",
                                                  brief="Wait for review")
    await producer.teams.update_task(dependency.id, {"status": "needs_review"})
    queued = await draft(producer, chat, "Wait for review", dependencies=[dependency.id])
    await allow(producer, queued, "todo")
    unapproved = await draft(producer, chat, "Still a draft")

    await producer.teams.set_autostart_tasks(TEAM, True)
    assert (await producer.teams._reload(queued.id)).status == "todo"
    assert await producer.teams.start_all_tasks(TEAM) == [queued.id]
    assert (await producer.teams._reload(queued.id)).status == "pending"
    assert (await producer.teams._reload(unapproved.id)).status == "todo"
    assert not producer.jobs.is_running(queued.id)
    await producer.teams.approve(dependency.id)
    await finished(producer, queued.id)
    assert any(r.session_key == queued.id for r in ProducerBackend.requests)
    assert (await producer.chats.get_chat(chat.id)).producer_paused


async def test_board_cancel_withdraws_release_during_pause_and_allows_explicit_retry(producer, monkeypatch):
    chat = await opened(producer)
    task = await draft(producer, chat)
    await allow(producer, task, "todo")
    await producer.chats.producer.pause(chat.id)
    started = asyncio.Event()
    calls = []

    async def hold(self, request):
        calls.append(request.session_key)
        yield AgentEvent(kind="text", text="Started")
        started.set()
        await asyncio.Event().wait()

    monkeypatch.setattr(ProducerBackend, "run", hold)
    await producer.teams.update_task(task.id, {"status": "pending", "auto_start": True}, from_board=True)
    await asyncio.wait_for(started.wait(), 5)
    handle = producer.jobs.get(task.id)
    assert await producer.teams.cancel_task(task.id)
    await asyncio.gather(handle.task, return_exceptions=True)
    await producer.teams.dispatch_member_queue(DEV)
    assert not producer.jobs.is_running(task.id)

    started.clear()
    await producer.teams.start_task(task.id, from_board=True)
    await asyncio.wait_for(started.wait(), 5)
    assert calls == [task.id, task.id]
    assert (await producer.chats.get_chat(chat.id)).producer_paused
    await producer.teams.cancel_task(task.id)


async def test_concurrent_board_starts_keep_solo_and_a_refused_task_stays_paused(producer, monkeypatch):
    await solo_on(producer)
    chat = await opened(producer)
    await producer.chats.producer.pause(chat.id)
    tasks = [await draft(producer, chat, title) for title in ("First", "Second")]
    for task in tasks:
        await allow(producer, task)
        await producer.teams.update_task(task.id, {"auto_start": True})

    # Both starts enter the database checks before either has registered its job.
    both = asyncio.Barrier(2)
    opening_turn = producer.teams._opening_turn

    async def prepare(session, task):
        prompt = await opening_turn(session, task)
        await both.wait()
        return prompt

    monkeypatch.setattr(producer.teams, "_opening_turn", prepare)
    results = await asyncio.wait_for(asyncio.gather(
        *(producer.teams.start_task(t.id, from_board=True) for t in tasks), return_exceptions=True,
    ), 5)
    winner, = [r for r in results if isinstance(r, TeamTask)]
    refusal, = [r for r in results if isinstance(r, ValueError)]
    assert "Solo Mode" in str(refusal)
    monkeypatch.setattr(producer.teams, "_opening_turn", opening_turn)
    await finished(producer, winner.id)
    await producer.teams.dispatch_member_queue(DEV)
    loser, = [task for task in tasks if task.id != winner.id]
    assert not producer.jobs.is_running(loser.id)
    assert not any(r.session_key == loser.id for r in ProducerBackend.requests)


def test_estimate_task_cost_api_persists_validates_and_does_not_start_agents(client):
    chat = client.post("/api/chats", json={"member_id": PRODUCER}).json()
    path = f'/api/chats/{chat["id"]}/producer/settings'
    assert chat["estimate_task_cost_usd"] == 150
    saved = client.patch(path, json={"estimate_task_cost_usd": 12.5})
    assert saved.status_code == 200
    assert saved.json()["estimate_task_cost_usd"] == 12.5
    assert not saved.json()["is_running"]
    assert client.get(f'/api/chats/{chat["id"]}').json()["estimate_task_cost_usd"] == 12.5
    for value in (0, -1, None, "NaN", "Infinity", "-Infinity"):
        assert client.patch(path, json={"estimate_task_cost_usd": value}).status_code == 422
        assert client.post(f'/api/chats/{chat["id"]}/producer/start',
                           json={"estimate_task_cost_usd": value}).status_code == 422
    assert client.patch(path, json={}).status_code == 422
    other = client.post("/api/chats", json={"member_id": DEV}).json()
    assert client.patch(f'/api/chats/{other["id"]}/producer/settings',
                        json={"estimate_task_cost_usd": 20}).status_code == 409


async def test_estimate_task_cost_survives_stop_start_and_can_change_while_paused(producer):
    chat = await opened(producer)
    await producer.chats.producer.set_estimate_task_cost(chat.id, 42.5)
    await producer.chats.producer.disable(chat.id)
    await producer.chats.producer.enable(chat.id)
    await finished(producer, chat.id)
    assert (await producer.chats.get_chat(chat.id)).estimate_task_cost_usd == 42.5
    await producer.chats.producer.pause(chat.id)
    saved = await producer.chats.producer.set_estimate_task_cost(chat.id, 60)
    assert saved.producer_paused and saved.estimate_task_cost_usd == 60
    await producer.chats.producer.disable(chat.id)
    await producer.chats.producer.enable(chat.id, estimate_task_cost_usd=90)
    await finished(producer, chat.id)
    assert (await producer.chats.get_chat(chat.id)).estimate_task_cost_usd == 90


@pytest.mark.parametrize("cost,estimate", [(150, 0), (155, 0), (140, 11)])
@pytest.mark.parametrize("action,status", [("request_changes", "needs_review"), ("start", "pending")])
async def test_estimate_task_cost_blocks_continuation_without_changing_work(producer, cost, estimate, action, status):
    chat = await opened(producer)
    task = await draft(producer, chat)
    await allow(producer, task, status)
    async with producer.db.session() as session:
        saved = await session.get(TeamTask, task.id)
        saved.total_cost_usd = cost
        saved.result_type = "question"
        saved.result_summary = "Movement partly implemented. Need input decision."
        session.add(saved)
    before = (await producer.teams._reload(task.id)).model_dump()
    tools = await tools_for(producer, chat)
    result = await tools["review_producer_task"]({
        "task": task.id, "action": action, "comment": "Continue with WASD",
        "estimated_additional_cost_usd": estimate,
    })
    assert result["is_error"] and "Estimate Task Cost" in result["content"][0]["text"]
    assert "new tasks" in result["content"][0]["text"]
    assert (await producer.teams._reload(task.id)).model_dump() == before
    assert not any(request.session_key == task.id for request in ProducerBackend.requests)
    # Closing unfinished work preserves evidence and does not falsely label it Done.
    await producer.chats.producer.review(chat.id, task.id, "cancel", "Cost limit; move remaining work to a new task.")
    closed = await producer.teams._reload(task.id)
    assert closed.status == "cancelled" and closed.result_type == "question"
    assert closed.total_cost_usd == cost and closed.result_summary == before["result_summary"]


async def test_estimate_task_cost_context_is_cumulative_and_under_budget_review_can_continue(producer):
    chat = await opened(producer)
    task = await draft(producer, chat)
    await allow(producer, task, "needs_review")
    # Same accounting path as real results: cost spans multiple sessions.
    await producer.teams._store_result_stats(task.id, {"total_cost_usd": 60})
    await producer.teams._store_result_stats(task.id, {"total_cost_usd": 40})
    state = json.loads(await producer.chats.producer.context(chat.id))
    assert state["estimate_task_cost_usd"] == 150
    entry = next(item for item in state["tasks"] if item["id"] == task.id)
    assert entry["total_cost_usd"] == 100 and entry["remaining_task_cost_usd"] == 50
    await producer.chats.producer.review(chat.id, task.id, "request_changes", "Use WASD", 50)
    await finished(producer, task.id)
    assert any(request.session_key == task.id for request in ProducerBackend.requests)


async def test_over_budget_verified_done_result_can_still_be_approved(producer):
    chat = await opened(producer)
    task = await draft(producer, chat)
    await allow(producer, task)
    await producer.teams.submit_for_review(task.id, "Acceptance checks passed", "done")
    await producer.teams._store_result_stats(task.id, {"total_cost_usd": 175})
    await producer.chats.producer.review(chat.id, task.id, "approve", "")
    assert (await producer.teams._reload(task.id)).status == "done"


@pytest.mark.parametrize("edit", [{"auto_start": True}, {"depends_on": []}])
async def test_cost_guard_also_covers_producer_edits_that_dispatch_pending_tasks(producer, edit):
    chat = await opened(producer)
    task = await draft(producer, chat)
    await allow(producer, task, "pending")
    async with producer.db.session() as session:
        stored = await session.get(TeamTask, task.id)
        stored.total_cost_usd = 150
        stored.auto_start = "depends_on" in edit
        session.add(stored)
    before = (await producer.teams._reload(task.id)).model_dump()
    tools = await tools_for(producer, chat)
    result = await tools["update_task"]({"task": task.id, **edit})
    assert result["is_error"] and "Estimate Task Cost" in result["content"][0]["text"]
    assert (await producer.teams._reload(task.id)).model_dump() == before
    assert not any(r.session_key == task.id for r in ProducerBackend.requests)


async def test_cost_guard_also_covers_approval_of_previously_spent_todo_tasks(producer):
    chat = await opened(producer)
    task = await draft(producer, chat)
    await producer.teams._store_result_stats(task.id, {"total_cost_usd": 160})
    await producer.chats.producer.propose(chat.id, [task.id], "Resume existing work")
    saved = await producer.chats.get_chat(chat.id)
    with pytest.raises(ValueError, match="Estimate Task Cost"):
        await producer.chats.producer.approve_plan(chat.id, saved.producer_plan_version)
    assert not (await producer.teams._reload(task.id)).producer_approved
    assert not any(r.session_key == task.id for r in ProducerBackend.requests)


async def test_editable_producer_fragments_reach_turn_context_and_compaction(producer, monkeypatch):
    prompts = producer.config.teams.prompts
    prompts.producer_task_cost = "CUSTOM COST POLICY"
    prompts.producer_review = "CUSTOM REVIEW POLICY"
    prompts.producer_state = "CUSTOM STATE {state}\nINPUT {prompt}"
    prompts.producer_summary = "CUSTOM SUMMARY {summary}\nCONTINUE {prompt}"
    prompts.producer_compact = "CUSTOM COMPACTION: preserve exact task ids."
    chat = await opened(producer)
    initial = ProducerBackend.requests[-1]
    assert "CUSTOM STATE" in initial.prompt
    assert "CUSTOM COST POLICY" in initial.prompt and "CUSTOM REVIEW POLICY" in initial.prompt
    state = json.loads(await producer.chats.producer.context(chat.id))
    assert state["cost_instructions"] == "CUSTOM COST POLICY"
    assert state["result_policy"] == "CUSTOM REVIEW POLICY"
    monkeypatch.setattr("voxarium_studio.teams.producer.COMPACT_CHARS", 50)
    await producer.chats.send(chat.id, "Continue the approved goal with the updated prompts.", UserContext())
    await finished(producer, chat.id)
    summary, continued = ProducerBackend.requests[-2:]
    assert summary.session_key.endswith(":summary")
    assert summary.system_prompt == prompts.producer_compact
    assert summary.prompt.startswith(prompts.producer_compact)
    assert "CUSTOM SUMMARY Summary:" in continued.prompt
    assert "CUSTOM STATE" in continued.prompt
    assert "CUSTOM COST POLICY" in continued.prompt and "CUSTOM REVIEW POLICY" in continued.prompt
