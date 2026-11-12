"""Claude Agent SDK backend (§10.3).

Each active team member is one ``claude`` CLI subprocess driven through
``ClaudeSDKClient``. The SDK's own ``AgentDefinition`` sub-agents are deliberately not
used as the basis of the hierarchy — the org chart and Task Board are ours and must
survive restarts, which the CLI's session-scoped team state does not (§10.1).

The API surface used here was verified against claude-agent-sdk 0.2.x:
``ClaudeAgentOptions(cwd, allowed_tools, model, resume, max_turns, system_prompt,
permission_mode, mcp_servers, strict_mcp_config)`` and ``session_id`` on assistant/result
messages. ``effort`` arrived later (0.2.128 has it) and, like ``max_buffer_size`` and
``plugins`` (0.2.152, how the Studio skill is loaded), is passed only when the installed SDK
declares it — see :func:`_option_fields`.
"""

from __future__ import annotations

import asyncio
import base64
import dataclasses
import json
import logging
import math
import os
import re
import shutil
import uuid
from collections.abc import AsyncIterator, Callable
from functools import lru_cache
from pathlib import Path
from typing import Any

from ..changes import edit_stat
from .base import (
    AgentBackend,
    AgentEvent,
    AgentRunRequest,
    Inbox,
    QueuedMessage,
    RemoteControl,
    RemoteControlUnavailable,
    forget_live_session,
    register_backend,
    register_live_session,
)

log = logging.getLogger(__name__)


def _session_cost_baseline(session_id: str | None) -> float | None:
    """Read the last persisted cumulative cost before resuming a Claude subprocess.

    Read backwards in bounded blocks: a long session can be hundreds of MB. A missing
    journal is unknown, never a zero baseline that would recharge all prior work.
    """
    if not session_id:
        return 0.0
    if not re.fullmatch(r"[A-Za-z0-9_-]+", session_id):
        return None
    root = Path(os.environ.get("CLAUDE_CONFIG_DIR") or Path.home() / ".claude") / "projects"
    for path in root.glob(f"*/{session_id}.jsonl"):
        try:
            with path.open("rb") as stream:
                stream.seek(0, os.SEEK_END)
                position, pending = stream.tell(), b""
                while position:
                    size = min(position, 65536)
                    position -= size
                    stream.seek(position)
                    lines = (stream.read(size) + pending).split(b"\n")
                    pending = lines[0]
                    if position == 0:
                        lines[0], pending = pending, b""
                    else:
                        lines = lines[1:]
                    for line in reversed(lines):
                        if b'cost-state' not in line:
                            continue
                        try:
                            row = json.loads(line)
                        except (ValueError, UnicodeDecodeError):
                            continue
                        cost = row.get("totalCostUSD")
                        if (row.get("type") == "cost-state" and isinstance(cost, (int, float))
                                and not isinstance(cost, bool) and math.isfinite(cost) and cost >= 0):
                            return float(cost)
        except OSError:
            continue
    return None


class _SessionCost:
    """Claude result cost is session-cumulative; its token usage is per invocation."""

    def __init__(self, session_id: str | None, baseline: float | None) -> None:
        self.session_id, self.baseline = session_id, baseline

    def apply(self, data: dict[str, Any], session_id: str | None) -> dict[str, Any]:
        if session_id != self.session_id:
            self.session_id, self.baseline = session_id, 0.0
        raw = float(data.get("total_cost_usd") or 0)
        baseline = self.baseline
        valid = math.isfinite(raw) and raw >= 0 and not data.get("cost_unavailable")
        data = {**data, "accounting_version": 2, "provider_session_id": session_id,
                "session_total_cost_usd": raw if valid else None,
                "session_cost_baseline_usd": baseline,
                "total_cost_usd": max(0.0, raw - baseline) if valid and baseline is not None else 0.0}
        if baseline is None or not valid:
            data["cost_unavailable"] = True
        if valid:
            # A stale/empty result must not lower the watermark and recharge history
            # when the next cumulative result arrives for this same session.
            self.baseline = max(baseline or 0.0, raw)
        return data

#: The CLI's own phrase for a result nobody asked the model to produce. Not what a stalled
#: run looks like through the SDK — that arrives as an **empty** result — so it is one test
#: among several rather than the rule, but it is the same event and reads the same way.
_NO_RESPONSE_RESULT = "no response requested"


def _said_nothing(text: str) -> bool:
    """No summary at all: empty, or the CLI's phrase for having nothing to say."""
    stripped = text.strip()
    return not stripped or stripped.rstrip(".").casefold() == _NO_RESPONSE_RESULT


def _stopped_mid_work(data: dict[str, Any], text: str) -> bool:
    """Did the session stop with the job unfinished, rather than come to an end?

    Read off real runs rather than guessed: a session that spends its turns answers
    ``subtype="error_max_turns"``, ``is_error=True``, ``stop_reason="tool_use"``, an empty
    ``result`` and ``num_turns`` one past the member's ``max_turns``. Nothing in it says
    "No response requested" — that phrase is the CLI's, and the SDK sends the empty string.

    **``is_error`` is deliberately not a veto here**, which is the whole correction: it is
    true for exactly this case, so treating an error as an ending — the first version of
    this did — meant the check could never fire. A failure that is not the turn cap still
    ends the run, because it either says what went wrong or did not stop on a tool call.
    """
    if data.get("subtype") == "error_max_turns":
        return True
    if not _said_nothing(text):
        return False
    # Nothing said, and a tool call was in flight when the lights went out.
    return data.get("stop_reason") == "tool_use"


#: Kinds of delegated work whose completion wakes the parent session for another turn.
#:
#: Mirrors the SDK's own ``DEFERRING_TASK_TYPES`` (``_internal/query.py``), which is the set
#: it holds the CLI's stdin open for, and the omissions are the point rather than an
#: oversight: a background shell (``Bash(run_in_background=True)`` on a dev server), a
#: monitor and a teammate are all reported through the same frames and none of them reliably
#: reaches a terminal status — waiting on one would hold the run open for the whole session
#: instead of briefly. Anything added here must be work that ends.
_DEFERRING_TASK_TYPES = frozenset({"local_agent", "local_workflow"})

#: A task in any of these is over. Two vocabularies meet: ``task_notification`` reports the
#: CLI's mapped ``stopped`` while a ``task_updated`` patch carries the raw ``killed``, and a
#: terminal state can arrive as *either* frame — clearing on both is what stops the ledger
#: leaking an id that would then hold the run open forever.
_TERMINAL_TASK_STATUSES = frozenset({"completed", "failed", "stopped", "killed"})


class _DelegatedWork:
    """The sub-agents and workflows a session has in flight, from the CLI's own frames.

    Read off ``system`` lifecycle frames rather than from tool calls, because a member can
    start delegated work several ways (``Agent``, ``SendMessage`` into an idle agent,
    ``Workflow``) and only the runtime knows which of them actually left something running.

    It is a **mitigation, not a proof** — the SDK says the same of its own copy. A task that
    settles *before* the result frame of the turn that started it leaves this empty at that
    result, so the run ends there even though a follow-up turn may still be coming. Closing
    that needs a run-boundary signal the CLI does not send; what this does fix is the common
    ordering, where the work outlives the turn that spawned it.
    """

    __slots__ = ("_running",)

    def __init__(self) -> None:
        self._running: dict[str, str] = {}

    def observe(self, subtype: str, payload: dict[str, Any]) -> None:
        task_id = payload.get("task_id")
        if not isinstance(task_id, str) or not task_id:
            return
        if subtype == "task_started":
            if payload.get("task_type") in _DEFERRING_TASK_TYPES:
                description = str(payload.get("description") or "").strip()
                self._running[task_id] = description or "delegated work"
        elif subtype == "task_notification":
            # Every status this frame carries is terminal, which is why it needs no test.
            self._running.pop(task_id, None)
        elif subtype == "task_updated":
            patch = payload.get("patch")
            status = patch.get("status") if isinstance(patch, dict) else None
            if status in _TERMINAL_TASK_STATUSES:
                self._running.pop(task_id, None)

    def __len__(self) -> int:
        return len(self._running)

    def summary(self) -> str:
        """What is still running, for the transcript — the first few by name."""
        names = list(self._running.values())
        shown = ", ".join(names[:3])
        if len(names) > 3:
            shown += f", and {len(names) - 3} more"
        return shown


async def _turn(client: Any, idle_timeout: Callable[[], float]) -> AsyncIterator[Any]:
    """One turn's messages, ending at its result frame — with a bound on total silence.

    ``receive_response()`` stops at the first ``ResultMessage`` and waits indefinitely until
    then, which is exactly right while the member is working and exactly wrong once the run
    is only holding the session open for delegated work: nothing else in the Studio would
    ever end that wait. ``idle_timeout`` answering 0 keeps the SDK's own behaviour, which is
    what a turn the member is actually driving gets. It is asked before every frame rather
    than once, because the answer changes inside a turn: a run held for a message the CLI
    has not echoed is bounded only until the echo arrives (§10.8).

    The bound is on **silence between frames**, not on the wait as a whole: a sub-agent
    reports progress as it works, so one that legitimately runs for hours keeps resetting it.

    Timing out abandons the stream rather than closing it, deliberately: ``wait_for`` has
    already cancelled the generator mid-``__anext__``, where ``aclose()`` can answer
    "already running", and the caller tears the whole client down on the next line anyway.
    """
    stream = client.receive_response()
    while True:
        try:
            limit = idle_timeout()
            if limit > 0:
                message = await asyncio.wait_for(stream.__anext__(), limit)
            else:
                message = await stream.__anext__()
        except StopAsyncIteration:
            return
        yield message


def _sdk_available() -> bool:
    try:
        import claude_agent_sdk  # noqa: F401
    except ImportError:
        return False
    return True


@lru_cache(maxsize=1)
def _option_fields() -> frozenset[str]:
    """What ``ClaudeAgentOptions`` accepts in the SDK that is actually installed.

    The ``teams`` floor is checked only when the extra is installed, so an environment built
    before it was raised still runs an older SDK, and a member configured with a setting a
    newer SDK grew — ``effort`` is the first — must degrade to running without it rather
    than to a ``TypeError`` that fails the task.
    """
    from claude_agent_sdk import ClaudeAgentOptions

    return frozenset(field.name for field in dataclasses.fields(ClaudeAgentOptions))


class ClaudeCodeBackend(AgentBackend):
    name = "claude_code"

    def is_available(self) -> tuple[bool, str]:
        if not _sdk_available():
            return False, (
                "claude-agent-sdk is not installed. Install the Studio's `teams` extra: "
                "`pip install 'voxarium_studio[teams]'`."
            )
        if shutil.which("claude") is None:
            return False, (
                "The `claude` CLI was not found on PATH. The Agent SDK runs it as a "
                "subprocess per session."
            )
        has_auth = bool(os.environ.get("ANTHROPIC_API_KEY")) or _has_cli_credentials()
        if not has_auth:
            return False, (
                "No Claude credentials found. Set ANTHROPIC_API_KEY in the Studio's "
                "environment, or log in once with `claude` (§10.3). Never commit the key "
                "into .voxarium_studio/config.yaml."
            )
        return True, "Ready"

    async def run(self, request: AgentRunRequest) -> AsyncIterator[AgentEvent]:
        available, reason = self.is_available()
        if not available:
            yield AgentEvent(kind="error", text=reason, is_final=True)
            return

        from claude_agent_sdk import (
            AssistantMessage,
            ClaudeAgentOptions,
            ClaudeSDKClient,
            ResultMessage,
            SystemMessage,
            UserMessage,
        )

        # Only what was actually chosen: leaving `effort` out is not the same as sending a
        # level, and the model's own default is the right answer for a member nobody has
        # tuned. The request carries the resolved value — the task's override where there is
        # one, the member's otherwise — so which of the two won is settled before here.
        extra: dict[str, Any] = {}
        # §10.4: an isolated checkout still uses the managed project's task temp
        # directory. Give the CLI the same write roots as the sandboxed backends.
        if request.writable_roots and "add_dirs" in _option_fields():
            extra["add_dirs"] = [str(root.resolve()) for root in request.writable_roots]
        effort = (request.effort or "").strip()
        if effort:
            if "effort" in _option_fields():
                extra["effort"] = effort
            else:
                log.warning(
                    "Ignoring effort=%r for member %s: the installed claude-agent-sdk has "
                    "no such option. Upgrade the `teams` extra to use it.",
                    effort,
                    request.member.id,
                )

        # The SDK's own ceiling is 1 MiB per NDJSON line, and a `Read` of any real
        # screenshot is several times that once base64'd: the stream then dies with
        # "JSON message exceeded maximum buffer size" mid-run and the task is Blocked with
        # the work half done (§10.3). The Studio decides this, not the SDK's default.
        if request.max_message_bytes > 0:
            if "max_buffer_size" in _option_fields():
                extra["max_buffer_size"] = request.max_message_bytes
            else:
                log.warning(
                    "The installed claude-agent-sdk has no max_buffer_size option, so runs "
                    "keep its 1 MiB per-message limit: reading a large image will fail the "
                    "task. Upgrade the `teams` extra.",
                )

        # The Studio's skill travels as a plugin directory, the one way Claude Code takes a
        # skill from outside the project (`teams/skill.py`); the rules that let the session
        # open it are already in `extra_allowed_tools`. The `teams` floor declares the option,
        # so the guard is for an install below it, which runs without the guide, not failing.
        if request.plugin_dirs:
            if "plugins" in _option_fields():
                extra["plugins"] = [
                    {"type": "local", "path": str(path)} for path in request.plugin_dirs
                ]
            else:
                log.warning(
                    "The installed claude-agent-sdk has no plugins option, so sessions run "
                    "without the Studio skill their prompt points at. Upgrade the `teams` "
                    "extra.",
                )

        # `--agent <name>`, which the SDK has no field for. It has to be the CLI flag: the
        # SDK's own `agents=` option describes sub-agents the session may delegate to and
        # registers no name `--agent` will accept, so passing the member there would put it
        # everywhere except where it is selected from (§10.3).
        extra_args: dict[str, str | None] = {}
        if request.agent_name:
            extra_args["agent"] = request.agent_name
        # What the user says while the session works (§10.8) is sent as it comes — a real
        # user message into the busy process, which the CLI hands to the model after the
        # tool call in flight, or as a turn of its own when the turn has no more of them.
        # `--replay-user-messages` is what makes that safe to do: the CLI echoes each
        # message, under the uuid it was sent with, at the moment the model receives it.
        # That echo is the delivery receipt, and the only thing that tells a result frame
        # that ends the run from one with a queued message still behind it.
        inbox = None if request.text_only else request.inbox
        if inbox is not None:
            extra_args["replay-user-messages"] = None

        # Everything that does not change between an attempt and its continuation. `resume`
        # does, so it is applied per attempt inside the loop.
        base_options: dict[str, Any] = dict(
            cwd=str(request.workdir),
            # The Studio's own tools are always allowed: they are how a member reaches the
            # task board and the shared docs, and they are gated by the same routing rules
            # as the UI, so there is nothing here for a per-member allowlist to protect.
            allowed_tools=[*request.member.allowed_tools, *request.extra_allowed_tools],
            mcp_servers=dict(request.mcp_servers),
            # The Studio decides the server list, so the CLI must not add to it — see
            # `AgentRunRequest.strict_mcp_config` and §10.7. Verified against 0.2.128;
            # the flag is what makes the MCP page's on/off switch mean anything.
            strict_mcp_config=request.strict_mcp_config,
            model=request.model,
            max_turns=request.max_turns,
            permission_mode=request.permission_mode,
            # Full replacement (2026-11-12): the Studio-built prompt — Responsibility,
            # identity, run context, all of it from Team Settings — is the session's *whole*
            # system prompt; the stock Claude Code preset is no longer sent underneath.
            # Checked against the bundled CLI with a captured request: `--system-prompt`
            # also discards a `--agent` definition's own prompt, which is why the run's
            # prompt now always carries the identity itself (orchestrator/chat side).
            system_prompt=request.system_prompt,
            extra_args=extra_args,
            **extra,
        )
        if request.text_only:
            # §10.4: compression uses the task's resolved model. Plan mode can route
            # to Sonnet even when another model was requested (see commit_proposal).
            base_options.update(
                tools=[], allowed_tools=[], mcp_servers={}, strict_mcp_config=True,
                permission_mode="default", system_prompt=request.system_prompt,
                setting_sources=[], plugins=[], extra_args={},
            )

        session_id: str | None = request.resume_session_id
        cost_counter = _SessionCost(session_id, await asyncio.to_thread(_session_cost_baseline, session_id))
        prompt = request.prompt
        continuations = 0
        try:
            while True:
                # A continuation is a **new CLI subprocess resuming the same session**, not
                # another `query()` on the exhausted one. That is the point: `max_turns` is
                # counted per invocation — three separate runs of one task each ended at 41
                # against a cap of 40 — so a second query into the same process would come
                # straight back with the same empty result. This is the click a person makes.
                options = ClaudeAgentOptions(  # type: ignore[arg-type]
                    resume=session_id, **base_options
                )
                stalled = False
                mid_work = False
                waiting = False
                holding = False
                timed_out = False
                stall_data: dict[str, Any] = {}
                delegated = _DelegatedWork()
                async with ClaudeSDKClient(options=options) as client:
                    # Published for exactly as long as the CLI subprocess exists, which is
                    # what `POST …/remote-control` needs a handle on (§10.8).
                    register_live_session(request.session_key, _SessionControl(client))
                    # Only the run's opening message carries pictures: a continuation
                    # resumes a session that has already seen them.
                    images = request.images if continuations == 0 else []
                    if images:
                        await client.query(_user_message(prompt, images))
                    else:
                        await client.query(prompt)
                    # From here until this process stops taking them, a message for the
                    # session goes straight in — those that waited for it to start included.
                    receipts: dict[str, str] = {}
                    expecting = False
                    if inbox is not None:
                        inbox.attach(_inbox_sender(client, inbox, receipts))

                    def silence_limit() -> float:
                        # A message the CLI was sent and has not echoed is picked up within
                        # moments of the turn ending; one that is not was lost with it.
                        if expecting and inbox is not None and inbox.unacknowledged:
                            return _RECEIPT_TIMEOUT
                        return request.delegated_work_idle_timeout if holding else 0.0

                    # One pass per **turn**, not per run. A result frame ends a turn, and a
                    # turn is the run's ending only when nothing the member delegated is
                    # still going: Claude Code wakes the session again as each sub-agent or
                    # workflow finishes, and reading only to the first result took that
                    # follow-up work for a finished task — and then killed it with the
                    # subprocess on the way out (§10.3).
                    while True:
                        try:
                            async for message in _turn(client, silence_limit):
                                found = getattr(message, "session_id", None)
                                if found:
                                    session_id = found

                                rate_limit = getattr(message, "rate_limit_info", None)
                                if rate_limit is not None:
                                    # `RateLimitEvent`, and read by duck typing rather than
                                    # by class: it arrived later than the SDK floor this
                                    # package pins, and an older one has no such name to
                                    # import. It is the only frame that dates a limit —
                                    # everything downstream of a spent window can say what
                                    # happened and not when it lifts (§10.3).
                                    yield AgentEvent(
                                        kind="rate_limit",
                                        text=_rate_limit_line(rate_limit),
                                        data=_rate_limit_data(rate_limit),
                                        session_id=session_id,
                                    )
                                elif isinstance(message, AssistantMessage):
                                    for event in _assistant_events(message, session_id):
                                        yield event
                                elif isinstance(message, UserMessage):
                                    receipt = receipts.pop(getattr(message, "uuid", None) or "", None)
                                    if receipt is not None and inbox is not None:
                                        # The echo of a message sent mid-run (§10.8): the
                                        # model has it now. Not an event — the transcript
                                        # already holds the user's own entry.
                                        await inbox.acknowledge(receipt)
                                        continue
                                    for event in _tool_result_events(message, session_id):
                                        yield event
                                elif isinstance(message, SystemMessage):
                                    subtype = str(getattr(message, "subtype", ""))
                                    payload = _as_dict(getattr(message, "data", {}))
                                    delegated.observe(subtype, payload)
                                    yield AgentEvent(
                                        kind="system",
                                        text=subtype,
                                        data=payload,
                                        session_id=session_id,
                                    )
                                elif isinstance(message, ResultMessage):
                                    text = getattr(message, "result", "") or ""
                                    data = cost_counter.apply(_result_data(message), session_id)
                                    mid_work = _stopped_mid_work(data, text)
                                    stalled = (
                                        mid_work
                                        and continuations < request.max_continuations
                                        # Without a session id there is nothing to resume
                                        # into, and starting over would run the whole brief
                                        # a second time.
                                        and session_id is not None
                                    )
                                    # `mid_work` wins over waiting, and has to: the turn
                                    # budget is counted per invocation, so a session that
                                    # spent it cannot take the follow-up turn a finishing
                                    # task would wake it for. Holding it open would wait on
                                    # a session that can no longer speak.
                                    # A message sent and not yet echoed holds the run
                                    # open the same way (§10.8): the CLI opens another turn
                                    # on it, and its result is the one that ends the run.
                                    unread = inbox.unacknowledged if inbox is not None else 0
                                    waiting = (bool(delegated) or unread > 0) and not mid_work
                                    if inbox is not None and not waiting:
                                        # This process takes no more: what arrives from here
                                        # waits for the continuation, or for the caller.
                                        inbox.detach()
                                    # Cost and turns still count — they were spent — but a
                                    # turn that ends neither the work nor the run describes
                                    # neither its outcome nor its summary, and its
                                    # `is_error` is the turn cap rather than a failed run.
                                    data["continued"] = stalled or waiting
                                    data["pending_tasks"] = len(delegated)
                                    if unread:
                                        data["pending_messages"] = unread
                                    stall_data = data
                                    yield AgentEvent(
                                        kind="result",
                                        text=text,
                                        data=data,
                                        session_id=session_id,
                                        is_final=not (stalled or waiting),
                                    )
                        except TimeoutError:
                            timed_out = True
                        if timed_out or not waiting:
                            break
                        expecting = True
                        if not len(delegated):
                            # Held for a message alone: nothing to say in the transcript,
                            # the user's own entry is there and turns `delivered` shortly.
                            waiting = False
                            continue
                        if not holding:
                            # Said once per attempt rather than on every follow-up turn: the
                            # result cards that follow are the evidence, and the live list of
                            # what is running is on the task itself.
                            yield AgentEvent(
                                kind="continued",
                                text=(
                                    f"The turn ended with {len(delegated)} delegated "
                                    f"task(s) still running ({delegated.summary()}). "
                                    f"Holding the session open — it is woken again as each "
                                    f"of them finishes."
                                ),
                                data={
                                    "subtype": "waiting_for_tasks",
                                    "pending_tasks": len(delegated),
                                },
                                session_id=session_id,
                            )
                        holding = True
                        # The next result frame decides again, against whatever is left
                        # running by then.
                        waiting = False
                # The subprocess is gone the moment its context exits, so its handle must
                # go with it — between attempts there is briefly no session to steer, and
                # offering one that controls nothing is the §10.8 defect in miniature.
                forget_live_session(request.session_key)
                if inbox is not None:
                    inbox.detach()
                if len(delegated):
                    # The attempt ended with delegated work still outstanding, so the
                    # transcript has to say the run stopped watching — the subprocess dies
                    # with this block and takes those tasks with it, which is not something
                    # to leave a reader to infer from a session that simply went quiet. It
                    # covers the stall path too, where the resume abandons them the same way
                    # without ever having held the session open for them.
                    yield AgentEvent(
                        kind="continued",
                        text=(
                            f"Gave up waiting on {len(delegated)} delegated task(s) "
                            f"({delegated.summary()}): "
                            + (
                                f"nothing was heard from the session for "
                                f"{request.delegated_work_idle_timeout:g}s "
                                f"(teams.delegated_work_idle_timeout)"
                                if timed_out
                                else "the session ended while they were still running"
                            )
                            + ". Resuming the task opens the same session again."
                        ),
                        data={
                            "subtype": "background_wait_gave_up",
                            "pending_tasks": len(delegated),
                            "timed_out": timed_out,
                        },
                        session_id=session_id,
                    )
                if not stalled:
                    # Ending here is a fine outcome; ending here *silently* is not. The
                    # result the UI just drew is an empty card, so the reason it stopped
                    # has to be said in the transcript rather than left to be inferred.
                    if mid_work:
                        yield AgentEvent(
                            kind="continued",
                            text=(
                                f"The session stopped with work unfinished "
                                f"({_stall_reason(stall_data)}), and was left there: "
                                f"{_not_continuing(request, continuations, session_id)}."
                            ),
                            data={
                                "subtype": "stall_not_continued",
                                "attempts": continuations,
                            },
                            session_id=session_id,
                        )
                    break
                continuations += 1
                # The first message of a continued session (§10.3): the `stall_continue`
                # template, resolved by the orchestrator like every line the Studio speaks.
                prompt = request.continue_prompt
                # This goes in the transcript because the empty result above is the part a
                # reader cannot otherwise account for — and because the message sent on the
                # member's behalf is not the member's own words.
                yield AgentEvent(
                    kind="continued",
                    text=(
                        f"The session stopped with work unfinished "
                        f"({_stall_reason(stall_data)}). Resuming it — continuation "
                        f"{continuations} of {request.max_continuations}."
                    ),
                    data={
                        "subtype": "continue_after_stall",
                        "attempt": continuations,
                        "prompt": request.continue_prompt,
                    },
                    session_id=session_id,
                )
        except Exception as exc:  # SDK/CLI failures must surface on the task, not crash the server
            log.exception("Agent run failed for member %s", request.member.id)
            yield AgentEvent(
                kind="error",
                text=_run_failure(exc, request),
                session_id=session_id,
                is_final=True,
            )
        finally:
            forget_live_session(request.session_key)
            if inbox is not None:
                # Cancelled or failed with the process still attached: what it was sent and
                # never echoed goes back to waiting, for the caller to deliver or keep.
                inbox.detach()


#: Seconds of silence allowed while a message the CLI was sent has not been echoed back
#: and its turn has ended. The echo normally follows the result frame at once.
_RECEIPT_TIMEOUT = 120.0


def _inbox_sender(client: Any, inbox: Inbox, receipts: dict[str, str]) -> Any:
    """How a queued message enters a live session: one user message, under a uuid of ours.

    The uuid is what the CLI echoes it back with (``--replay-user-messages``), so the echo
    can be told from every other user frame in the stream — a tool result, a sub-agent's
    prompt — and matched to the transcript entry it delivers. Registered **before** the
    write: the echo can arrive on the reader the moment the line is flushed.
    """

    async def send(item: QueuedMessage) -> None:
        receipt = str(uuid.uuid4())
        receipts[receipt] = item.id
        try:
            await client.query(_user_message(inbox.compose([item]), item.images, receipt=receipt))
        except BaseException:
            receipts.pop(receipt, None)
            raise

    return send


class _SessionControl:
    """Remote Control over the SDK's control channel (§10.8).

    The CLI answers a ``remote_control`` control request by standing up its bridge — the
    same thing ``/remote-control`` does in an interactive session, which is why that
    command itself is no use here: it is a ``local-jsx`` command and renders a terminal UI
    the Studio has no terminal for. The control request is the SDK-mode equivalent, and it
    replies with the session's URL on claude.ai.

    ``_send_control_request`` is private to the SDK, so it is reached defensively: an SDK
    without it must produce a message about the SDK rather than an ``AttributeError``.
    """

    def __init__(self, client: Any) -> None:
        self._client = client
        self._context_lock = asyncio.Lock()

    async def get_context_usage(self) -> dict[str, Any]:
        """Explicit inspection only: the runtime may ask the provider to count tokens."""
        get_usage = getattr(self._client, "get_context_usage", None)
        if get_usage is None:
            raise RuntimeError("The installed Claude Agent SDK does not support context inspection")
        if self._context_lock.locked():
            raise RuntimeError("Context inspection is already running")
        async with self._context_lock:
            return await get_usage()

    async def enable_remote_control(self, name: str | None = None) -> RemoteControl:
        request: dict[str, Any] = {"subtype": "remote_control", "enabled": True}
        if name:
            request["name"] = name
        response = await self._control(request)
        url = response.get("session_url")
        if not isinstance(url, str) or not url:
            raise RemoteControlUnavailable(
                "The session was handed to Remote Control but no session URL came back, so "
                "there is nothing to open. Check `claude` is signed in to a claude.ai account."
            )
        return RemoteControl(
            session_url=url,
            connect_url=response.get("connect_url") or None,
            environment_id=response.get("environment_id") or None,
        )

    async def disable_remote_control(self) -> None:
        await self._control({"subtype": "remote_control", "enabled": False})

    async def _control(self, request: dict[str, Any]) -> dict[str, Any]:
        query = getattr(self._client, "_query", None)
        send = getattr(query, "_send_control_request", None)
        if send is None:
            raise RemoteControlUnavailable(
                "The installed claude-agent-sdk exposes no control channel, so Remote "
                "Control cannot be switched on for a running session. Upgrade the Studio's "
                "`teams` extra."
            )
        try:
            response = await send(request)
        except Exception as exc:  # the CLI's refusal is the useful half — see the class docstring
            raise RemoteControlUnavailable(str(exc) or f"{type(exc).__name__}") from exc
        return response if isinstance(response, dict) else {}


async def _user_message(
    prompt: str, images: list[dict[str, Any]], *, receipt: str | None = None
) -> AsyncIterator[dict[str, Any]]:
    """The opening message as content blocks: the text, then each picture as an image block.

    ``ClaudeSDKClient.query`` takes either a string or a stream of user-message dicts; the
    second form is the only one that can carry anything but text. The blocks are the API's
    own shape (``base64`` source with its media type), which the CLI passes through. A file
    that cannot be read is skipped rather than failing the run: the prompt already names it
    by path, so the agent can still open it with ``Read``.
    """
    content: list[dict[str, Any]] = [{"type": "text", "text": prompt}]
    for image in images:
        try:
            data = base64.b64encode(Path(str(image["path"])).read_bytes()).decode("ascii")
        except (OSError, KeyError) as exc:
            log.warning("Attached image %s not sent: %s", image.get("path"), exc)
            continue
        content.append(
            {
                "type": "image",
                "source": {
                    "type": "base64",
                    "media_type": str(image.get("media_type") or "image/png"),
                    "data": data,
                },
            }
        )
    message: dict[str, Any] = {
        "type": "user",
        "message": {"role": "user", "content": content if len(content) > 1 else prompt},
        "parent_tool_use_id": None,
    }
    if receipt:
        # Echoed back by `--replay-user-messages` when the model receives it (§10.8).
        message["uuid"] = receipt
    yield message


#: The four halves of the SDK's ``usage`` dict that are tokens. Cache reads count: they are
#: what a long session mostly consists of, and a figure without them would say a 40-turn
#: run read less than its own first prompt.
_TOKEN_FIELDS = (
    "input_tokens",
    "cache_creation_input_tokens",
    "cache_read_input_tokens",
    "output_tokens",
)


def _usage_tokens(usage: dict[str, Any]) -> int:
    """Everything the turn read or wrote, as the one number the task header shows."""
    return sum(int(usage.get(key) or 0) for key in _TOKEN_FIELDS)


def _result_data(message: Any) -> dict[str, Any]:
    """A turn's accounting, plus the two fields that say *why* it ended.

    ``subtype`` is the one that matters and the one that was missing: without it an empty
    result cannot be told from a session that genuinely failed, which is how the turn cap
    went unnoticed. It is stored on the task's transcript entry too, so a run that ends this
    way can be read back afterwards.

    ``usage`` is kept whole for the transcript and summed into ``total_tokens`` for the
    orchestrator, which adds it to the task the way it adds the cost and the turns — and
    which must not have to know what the SDK calls a cache read.
    """
    usage = getattr(message, "usage", None)
    usage = dict(usage) if isinstance(usage, dict) else {}
    return {
        "subtype": getattr(message, "subtype", None),
        "is_error": bool(getattr(message, "is_error", False)),
        "num_turns": getattr(message, "num_turns", 0) or 0,
        "total_cost_usd": getattr(message, "total_cost_usd", 0.0) or 0.0,
        "cost_unavailable": getattr(message, "total_cost_usd", None) is None,
        "total_tokens": _usage_tokens(usage),
        "total_cached_tokens": int(usage.get("cache_read_input_tokens") or 0),
        "total_uncached_tokens": sum(int(usage.get(key) or 0) for key in _TOKEN_FIELDS
                                     if key != "cache_read_input_tokens"),
        "accounting_version": 2,
        "usage": usage,
        "duration_ms": getattr(message, "duration_ms", 0) or 0,
        "stop_reason": getattr(message, "stop_reason", None),
        # Why a *failed* turn failed, as far as the CLI will say. `api_error_status` is the
        # HTTP status of the call that failed (429 for a spent usage limit) and `errors` is
        # its own wording; both are how a run that ended on the account rather than on the
        # work is told from one that ended on a bug (`teams/limits.py`).
        "api_error_status": getattr(message, "api_error_status", None),
        "errors": [str(entry) for entry in (getattr(message, "errors", None) or [])],
    }


def _rate_limit_data(info: Any) -> dict[str, Any]:
    """The SDK's ``RateLimitInfo`` as a plain dict, the raw frame kept underneath.

    Flattened rather than passed through as ``raw`` alone, because the two spell everything
    differently — ``resets_at`` here, ``resetsAt`` in the CLI's own JSON — and the reader is
    the orchestrator, which should not have to know both.
    """
    fields = (
        "status",
        "resets_at",
        "rate_limit_type",
        "utilization",
        "overage_status",
        "overage_resets_at",
        "overage_disabled_reason",
    )
    data: dict[str, Any] = {field: getattr(info, field, None) for field in fields}
    data["raw"] = _as_dict(getattr(info, "raw", {}))
    return data


def _rate_limit_line(info: Any) -> str:
    """One line for the transcript: which window, how full, and what it means for the run."""
    status = str(getattr(info, "status", "") or "unknown")
    window = str(getattr(info, "rate_limit_type", "") or "").replace("_", "-")
    utilization = getattr(info, "utilization", None)
    parts = [f"Usage {status}"]
    if window:
        parts.append(f"on the {window} window")
    if isinstance(utilization, (int, float)):
        parts.append(f"at {utilization * 100:.0f}%")
    return " ".join(parts)


def _not_continuing(request: AgentRunRequest, spent: int, session_id: str | None) -> str:
    """Why this stall is the end of the run, in the words the setting is spelled in."""
    if request.max_continuations <= 0:
        return "continuing a stalled session is switched off (teams.max_run_continuations)"
    if session_id is None:
        return "the session reported no id, so there is nothing to resume"
    return f"all {spent} continuations allowed by teams.max_run_continuations are spent"


def _run_failure(exc: Exception, request: AgentRunRequest) -> str:
    """The exception, plus the setting behind it when there is one.

    ``CLIJSONDecodeError`` truncates its line at 100 characters, so what reaches the task
    is ``Failed to decode JSON: JSON message exceeded maximum buffer size of N bytes...`` —
    true, and no help at all to someone whose only clue is that the run died just after a
    screenshot. Name the setting instead; the cause is nearly always one file read.
    """
    text = f"{type(exc).__name__}: {exc}"
    if "maximum buffer size" not in str(exc):
        return text
    return (
        f"{text} — one message from the CLI was larger than the "
        f"{request.max_message_bytes} bytes allowed by teams.max_message_bytes. Reading a "
        "large image or file is the usual cause; raise the setting to read files that size."
    )


def _stall_reason(data: dict[str, Any]) -> str:
    """Plain words for the transcript — a reader should not have to know the CLI's enums."""
    if data.get("subtype") == "error_max_turns":
        return f"it used all {data.get('num_turns') or 0} turns it was given"
    return "it ended on a tool call without saying anything"


def _assistant_events(message: Any, session_id: str | None) -> list[AgentEvent]:
    events: list[AgentEvent] = []
    for block in getattr(message, "content", []) or []:
        block_type = type(block).__name__
        if block_type == "TextBlock":
            text = getattr(block, "text", "")
            if text:
                events.append(AgentEvent(kind="text", text=text, session_id=session_id))
        elif block_type == "ThinkingBlock":
            events.append(
                AgentEvent(
                    kind="thinking",
                    text=getattr(block, "thinking", ""),
                    session_id=session_id,
                )
            )
        elif block_type == "ToolUseBlock":
            name = getattr(block, "name", None)
            raw_input = getattr(block, "input", {})
            data: dict[str, Any] = {
                "id": getattr(block, "id", None),
                "name": name,
                "input": _summarise_tool_input(raw_input),
            }
            # Counted from the untruncated input, and only here: by the time the row is
            # read back, `_summarise_tool_input` has cut both sides of the edit to 500
            # characters and there is nothing left to diff.
            stat = edit_stat(name or "", raw_input)
            if stat is not None:
                data["edit"] = stat
            events.append(
                AgentEvent(
                    kind="tool_use",
                    text=name or "tool",
                    data=data,
                    session_id=session_id,
                )
            )
    _attach_usage(events, message)
    parent = getattr(message, "parent_tool_use_id", None)
    if parent:
        for event in events:
            event.data["parent_tool_use_id"] = parent
    return events


def _attach_usage(events: list[AgentEvent], message: Any) -> None:
    """Put this message's accounting on **one** of the events it produced.

    An assistant message that answers in prose and then calls two tools is one API response
    with one ``usage``, so copying it onto each event would have the transcript count the
    same turn three times over — and the transcript sums them, because there is no running
    total in the frame to take a maximum of instead.

    Only the four token fields survive, and only the ones the runtime actually sent: an
    absent count and a zero are different answers, and the transcript says nothing rather
    than "0 tokens" for a run whose SDK reported none.
    """
    usage = getattr(message, "usage", None)
    if not events or not isinstance(usage, dict):
        return
    counted = {key: int(usage[key] or 0) for key in _TOKEN_FIELDS if key in usage}
    if counted:
        events[0].data = {**events[0].data, "usage": counted}
        response_id = getattr(message, "message_id", None)
        if response_id:
            events[0].data["provider_response_id"] = str(response_id)


def _tool_result_events(message: Any, session_id: str | None) -> list[AgentEvent]:
    events: list[AgentEvent] = []
    for block in getattr(message, "content", []) or []:
        if type(block).__name__ != "ToolResultBlock":
            continue
        text = _tool_result_text(getattr(block, "content", ""))
        events.append(
            AgentEvent(
                kind="tool_result",
                text=text[:2000],
                data={
                    "tool_use_id": getattr(block, "tool_use_id", None),
                    "is_error": bool(getattr(block, "is_error", False)),
                },
                session_id=session_id,
            )
        )
    return events


def _tool_result_text(content: Any) -> str:
    """What a tool answered, as a line a person can read.

    A result is a string for most tools and a list of content blocks for the ones that
    return more than text — which in practice means an image. ``str()`` on that list is the
    repr of a base64 payload: megabytes built only to be cut to 2000 characters of noise,
    stored on the transcript and drawn in the UI. The picture itself is the model's to look
    at, so what the transcript needs is that there was one, and how big.
    """
    if isinstance(content, str):
        return content
    if not isinstance(content, list):
        return str(content)
    return "\n".join(_tool_result_part(part) for part in content)


def _tool_result_part(part: Any) -> str:
    if not isinstance(part, dict):
        return str(part)
    if part.get("type") == "text":
        return str(part.get("text", ""))
    if part.get("type") == "image":
        source = part.get("source") if isinstance(part.get("source"), dict) else {}
        kind = source.get("media_type") or "image"
        payload = source.get("data")
        # Base64, so the file on disk is about three quarters of this.
        size = len(payload) if isinstance(payload, str) else 0
        return f"[{kind}, {size} base64 bytes]"
    return f"[{part.get('type') or 'content'}]"


def _summarise_tool_input(value: Any) -> dict[str, Any]:
    """Tool inputs can be huge (whole file bodies); keep the transcript readable."""
    if not isinstance(value, dict):
        return {"value": str(value)[:500]}
    summary: dict[str, Any] = {}
    for key, item in value.items():
        text = item if isinstance(item, str) else str(item)
        summary[key] = text[:500] + ("…" if len(text) > 500 else "")
    return summary


def _as_dict(value: Any) -> dict[str, Any]:
    if isinstance(value, dict):
        return {k: v for k, v in value.items() if isinstance(k, str)}
    return {}


def _has_cli_credentials() -> bool:
    """Best-effort check for an existing `claude` login."""
    from pathlib import Path

    candidates = [
        Path.home() / ".claude" / ".credentials.json",
        Path.home() / ".claude.json",
    ]
    return any(path.exists() for path in candidates)


register_backend("claude_code", ClaudeCodeBackend)
