"""Agent backend interface (§10.6).

``TeamMember.agent_backend`` selects an implementation, so the Task Board, hierarchy and
UI never depend on one runtime. Claude Code, Codex and Qwen Code implement the
contract; the registry below is the extension point for others.
"""

from __future__ import annotations

import asyncio
import logging
from collections.abc import AsyncIterator, Awaitable, Callable
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Protocol, runtime_checkable

from ...core.config import MAX_MESSAGE_BYTES_DEFAULT, TeamsConfig
from ...core.prompts import default as _default_prompt
from ...models.teams import TeamMember, TeamTask

log = logging.getLogger(__name__)


def member_run_settings(member: TeamMember, settings: TeamsConfig) -> dict[str, Any]:
    """Resolve shared run settings once, without rewriting stored member overrides (§10.3)."""
    source = member if member.run_settings_override else settings
    return {"permission_mode": source.permission_mode, "max_turns": source.max_turns}


#: ``data.delivery`` on the transcript entry of something sent to a session while it was
#: running (§10.8): ``queued`` until the member has it, ``delivered`` after. Absent on a
#: message that opened a turn itself — the member had that one from the start.
QUEUED, DELIVERED = "queued", "delivered"


@dataclass(slots=True)
class QueuedMessage:
    """One message sent to a session while it was running (§10.8)."""

    id: str
    """The transcript row it was recorded as, which is how its delivery is marked."""

    text: str
    """What the member receives — composed by the caller, context and trailers included."""

    images: list[dict[str, Any]] = field(default_factory=list)
    """Pictures sent with it, in :attr:`AgentRunRequest.images`' shape. A runtime that
    cannot take an image block ignores them; the text names each file by its path."""


class Inbox:
    """Messages for a session that is busy, delivered at the next opportunity (§10.8).

    The user does not have to wait for a run to end — or stop it — to say something to it.
    What they send while it works is put here, and reaches the member in one of two ways,
    neither of which interrupts anything:

    - **Inside the turn**, by a backend whose runtime takes a user message while it is
      working. Such a backend :meth:`attach`\\ es a sender for as long as it has a live
      process; every message — those already waiting, and each one put afterwards — is
      handed to it at once, and counts as delivered when the backend
      :meth:`acknowledge`\\ s it: the runtime's own word that the model has the text.
    - **As the next turn**, by the caller. Whatever is still waiting when ``backend.run``
      returns is sent as a message of its own into the same session before the run is
      called over.

    So a backend that cannot be steered ignores this entirely and loses nothing: the
    message arrives one turn later instead of one tool call later.

    A message is in exactly one of three places: *waiting* (``len``), *sent* to a runtime
    that has not acknowledged it (:attr:`unacknowledged`), or delivered and gone. A sender
    that fails, and a :meth:`detach` with messages still unacknowledged, put them back to
    waiting — a message may then reach the model twice in the worst case, never not at all.

    ``closed`` is the caller's promise that nothing put from now on would be delivered.
    Checking emptiness and closing happen with no ``await`` between them, which is what
    makes "accepted" and "will be delivered or handed back" the same thing.
    """

    def __init__(
        self,
        *,
        preamble: str = "",
        on_delivered: Callable[[list[QueuedMessage]], Awaitable[None]] | None = None,
    ) -> None:
        self.preamble = preamble
        """The ``mid_run_message`` template around the messages, ``{message}`` unfilled."""
        self.closed = False
        self._items: list[QueuedMessage] = []
        self._sent: dict[str, QueuedMessage] = {}
        self._sender: Callable[[QueuedMessage], Awaitable[None]] | None = None
        self._sending: set[asyncio.Task[None]] = set()
        self._on_delivered = on_delivered

    def __len__(self) -> int:
        """Messages waiting — not handed to any runtime."""
        return len(self._items)

    @property
    def unacknowledged(self) -> int:
        """Messages a runtime was sent and has not said the model received."""
        return len(self._sent)

    def put(self, message: QueuedMessage) -> None:
        if self.closed:
            raise RuntimeError("The session is no longer taking messages")
        self._items.append(message)
        self._forward()

    def discard(self, ids: set[str]) -> None:
        """Forget messages the caller is delivering some other way."""
        self._items = [item for item in self._items if item.id not in ids]

    # ---- a runtime that takes messages while it works ----

    def attach(self, sender: Callable[[QueuedMessage], Awaitable[None]]) -> None:
        """Hand every waiting message, and each later one, to ``sender`` as it comes."""
        self._sender = sender
        self._forward()

    def detach(self) -> None:
        """The runtime is gone, or done taking messages. What it never acknowledged waits again."""
        self._sender = None
        if self._sent:
            self._items = [*self._sent.values(), *self._items]
            self._sent = {}

    def _forward(self) -> None:
        if self._sender is None or not self._items:
            return
        items, self._items = self._items, []
        for item in items:
            self._sent[item.id] = item
            task = asyncio.ensure_future(self._send(self._sender, item))
            self._sending.add(task)
            task.add_done_callback(self._sending.discard)

    async def _send(
        self, sender: Callable[[QueuedMessage], Awaitable[None]], item: QueuedMessage
    ) -> None:
        try:
            await sender(item)
        except Exception:  # a dead process must cost a retry, not the message
            log.warning("Could not hand message %s to the running session", item.id, exc_info=True)
            if self._sent.pop(item.id, None) is not None:
                self._items.insert(0, item)

    async def acknowledge(self, message_id: str) -> bool:
        """The runtime says the model has this message. False for one it was not sent."""
        item = self._sent.pop(message_id, None)
        if item is None:
            return False
        if self._on_delivered is not None:
            await self._on_delivered([item])
        return True

    # ---- the caller, between turns ----

    async def take(self) -> list[QueuedMessage]:
        """Everything waiting, oldest first — taken, and reported as delivered."""
        items, self._items = self._items, []
        if items and self._on_delivered is not None:
            await self._on_delivered(items)
        return items

    def compose(self, items: list[QueuedMessage]) -> str:
        """The messages as the one text the member is handed, inside the preamble."""
        body = "\n\n".join(item.text for item in items)
        if "{message}" in self.preamble:
            return self.preamble.replace("{message}", body)
        return f"{self.preamble}\n\n{body}" if self.preamble else body

    def close(self) -> list[QueuedMessage]:
        """Stop taking messages; returns what was never delivered (a stopped or failed run)."""
        self.closed = True
        self.detach()
        items, self._items = self._items, []
        return items


@dataclass(slots=True)
class AgentRunRequest:
    member: TeamMember
    task: TeamTask | None
    """The card this run works, or ``None`` for a chat with the member (``teams/chat.py``).

    A chat is the same session machinery with nothing on the board: what the member is told
    is where the user is and what they said, not a brief. The backend never reads the task
    itself — what it needs from it is :attr:`session_key`.
    """

    workdir: Path
    prompt: str
    """The message to send.

    The brief on a first run only. A resume opens the session it left behind, which already
    holds the brief and every turn taken against it, so this is instead whatever is new:
    review feedback, notes left since the run stopped, or a bare instruction to carry on.
    """

    system_prompt: str
    writable_roots: list[Path] = field(default_factory=list)
    """Project or isolated checkout roots that need write access in a sandbox (§10.6).

    Independent of ``workdir``: a session may start above the project, or in one of its
    subdirectories. Backends that do not sandbox filesystem access may ignore this.
    """

    images: list[dict[str, Any]] = field(default_factory=list)
    """Pictures sent *with* :attr:`prompt`, each ``{"path": str, "media_type": str}``.

    A reviewer's screenshot attached to "request changes" (§10.4, ``teams/attachments.py``).
    Backend-agnostic by intent: a runtime that can take an image block puts them beside the
    text, and one that cannot ignores them — the prompt already names every file by its path,
    which is what the agent's own ``Read`` opens.
    """

    agent_name: str | None = None
    """A named agent to run **as**, rather than as a generic coding agent (§10.3).

    Backend-agnostic by intent even though the name comes from Claude Code's own
    ``.claude/agents/`` directory: a backend with no notion of named personas ignores it
    and its member simply runs with ``system_prompt`` carrying the whole role, which is
    also what happens when the definition could not be written. Never `None` and empty at
    the same time as an incomplete ``system_prompt`` — one of the two always carries the
    member's identity.
    """

    resume_session_id: str | None = None

    recovery_prompt: str = ""
    """Full saved context used only if the runtime cannot find the resumed session.

    The ordinary resume prompt remains incremental. A backend may retry once with this
    context only when resumption fails before any work starts (§10.6).
    """

    model: str = ""
    """Which model to run on — the member's, unless this task overrides it (§10.3).

    Resolved by the caller rather than left to the backend: which of the two settings wins
    is the orchestrator's rule, and a second backend deciding it for itself would make one
    override mean two things. Empty falls back to ``member.model`` below, so a caller with
    no opinion still hands the backend something it can use.
    """

    effort: str | None = None
    """How hard the model works per turn, resolved the same way (§10.3).

    Null after resolution means the model's own default, which is what a member nobody has
    tuned has too — so a backend passes it on only when it is set, and never has to know
    whether the level came from the task or the role.
    """

    permission_mode: str | None = None
    max_turns: int | None = None
    """Effective Team Settings or the member's enabled override (§10.3).

    Callers resolve these with :func:`member_run_settings`. A direct backend caller can
    omit them to use its member's values; internal text-only requests set their own bounds.
    """

    mcp_servers: dict[str, Any] = field(default_factory=dict)
    """Studio-side tools (delegation, project docs — see ``teams/tools.py``), plus the
    project's own MCP servers from ``.mcp.json`` (§10.7).

    The Studio entry is an SDK MCP server: Claude hosts it directly, Codex connects to it
    through a per-run stdio bridge. The project's entries are plain config dictionaries.
    A backend without tool support may still ignore them and describe hand-offs in its
    summary.
    """

    extra_allowed_tools: list[str] = field(default_factory=list)
    """Names for the above, to be allowed on top of ``member.allowed_tools`` — plus the rules
    that open :attr:`plugin_dirs` to the session."""

    plugin_dirs: list[Path] = field(default_factory=list)
    """Claude Code plugin directories the session loads (``--plugin-dir``).

    How the Studio's own skill reaches a session (``teams/skill.py``, §10.3): Claude Code
    takes a skill from outside the project only as part of a plugin. A backend with no notion
    of plugins ignores it, as it ignores servers in :attr:`mcp_servers` it cannot host.
    """

    skill_files: dict[str, Path] = field(default_factory=dict)
    """Skill names and instruction files offered to this session (§10.10).

    Codex uses these to identify an actual read of a known ``SKILL.md`` in its command
    stream. This is transcript metadata; it does not grant access or load a skill.
    """

    max_continuations: int = 0
    """How many times this run may be continued after the session stopped mid-work (§10.3).

    Backend-agnostic by intent: "the session ran out of budget with the job half done" is a
    thing any agent runtime can do, and a backend that cannot tell simply never continues.
    Defaults to off, so a caller that has not thought about the cost does not pay for it —
    the orchestrator passes ``config.teams.max_run_continuations``.
    """

    continue_prompt: str = _default_prompt("stall_continue")
    """The message that opens each of those continuations.

    Sent on the Studio's behalf rather than the member's, so it is configurable like every
    other line the Studio speaks (``teams.prompts.stall_continue``); the default keeps a
    backend usable for a caller that never thought about stalls.
    """

    strict_mcp_config: bool = True
    """Use only ``mcp_servers``, ignoring whatever else the backend would find itself.

    Off, Claude Code also loads user-level, global and plugin MCP config — and re-reads
    ``.mcp.json`` on its own, which brings back a server the user switched off in the
    Studio. On, the MCP page describes what a session actually gets (§10.7).
    """

    max_message_bytes: int = MAX_MESSAGE_BYTES_DEFAULT
    """The largest single message the backend may accept from its agent runtime.

    Backend-agnostic by intent: any runtime streaming a framed protocol has a line it will
    not read past, and one holding a whole image is how that limit gets found. The
    orchestrator passes ``config.teams.max_message_bytes``; the default here keeps a caller
    that never thought about it out of the 1 MiB hole the Agent SDK defaults into (§10.3).
    """

    delegated_work_idle_timeout: float = 1800.0
    """Seconds of complete silence before a run waiting on delegated work gives up (§10.3).

    Backend-agnostic by intent: "the member handed work to a sub-agent and its own turn
    ended first" is a thing any agent runtime with delegation can do, and a backend that
    cannot tell simply never waits and never consults this. The orchestrator passes
    ``config.teams.delegated_work_idle_timeout``; `0` waits indefinitely, and the default
    here is bounded rather than off because the cost of guessing wrong is a task stuck In
    progress holding a concurrency slot.
    """

    session_key: str = ""
    """What the live session is registered under while the run lasts (§10.8).

    The task id for a task run, the chat id for a chat — whichever the REST handler that
    wants to steer the session knows the run by. Filled from ``task`` when the caller left
    it empty, so every request built before chats existed keeps its key.
    """

    text_only: bool = False
    """Generate text from supplied context, without a coding persona (§10.4).

    Claude disables tools directly instead of using plan mode, which can switch models.
    Other backends retain the caller's read-only policy when no tool-free mode exists.
    """

    proactive_subagents: bool = False
    """Allow an interactive team member to initiate delegation at any effort (§10.6).

    Task and chat turns opt in. Studio workers and internal one-shot requests work alone.
    """

    inbox: Inbox | None = None
    """Messages the user sends while this run is in flight (§10.8), or ``None`` for a run
    nobody can talk to — a worker, a text-only request.

    Backend-agnostic by intent: a runtime that takes a user message while it works
    attaches to it, and one that cannot leaves it alone — the caller delivers what is left
    as the next turn of the same session.
    """

    def __post_init__(self) -> None:
        # The member is the fallback for both, so a request built without them runs exactly
        # as it did before either existed — which is what the tests and any backend written
        # against the older shape rely on.
        if not self.model:
            self.model = self.member.model
        if self.effort is None:
            self.effort = self.member.effort
        if self.permission_mode is None:
            self.permission_mode = self.member.permission_mode
        if self.max_turns is None:
            self.max_turns = self.member.max_turns
        if not self.session_key:
            if self.task is None:
                raise ValueError("AgentRunRequest needs a task or a session_key")
            self.session_key = self.task.id


@dataclass(slots=True)
class AgentEvent:
    """One streamed step of an agent's work, normalised across backends."""

    kind: str
    """``text`` | ``thinking`` | ``tool_use`` | ``tool_result`` | ``system`` | ``result`` |
    ``continued`` | ``rate_limit`` | ``error``.

    ``rate_limit`` is the account rather than the work: how full the usage window is and,
    once it is spent, when it refills (``data["resets_at"]``, unix epoch seconds).
    Backend-agnostic by intent even though Claude Code is the runtime that reports it — any
    metered runtime has a window that can run out — and a backend that cannot tell simply
    never sends one, which leaves ``teams.resume_on_limit`` with nothing to wait for and
    the task blocked as it was before (§10.3).

    ``continued`` is the Studio speaking rather than the member: a session that stopped
    mid-work was resumed, or was left where it stopped and why, or a turn that ended while
    delegated work it started is still running and the run is being held open for it
    (§10.3). It is not ``system`` because the transcript hides that by default, and this is
    the line that explains the result card next to it — one that ends nothing.

    Live usage (§10.4): attach Claude-shaped ``data.usage`` once per assistant response,
    or emit ``system`` with text/subtype ``usage_update`` and cumulative ``total_tokens``
    since the previous result. Optional ``context_tokens``/``context_window`` describe the
    latest request, not lifetime input. Final result totals include already reported usage.
    """

    text: str = ""
    data: dict[str, Any] = field(default_factory=dict)
    session_id: str | None = None
    is_final: bool = False


class RemoteControlUnavailable(RuntimeError):
    """A running session could not be handed to Remote Control, with the reason it gave.

    Part of the contract rather than one backend's error: the reasons are configuration
    facts the user has to act on — Remote Control wants claude.ai subscription auth rather
    than an ``ANTHROPIC_API_KEY``, and an organisation can switch it off by policy — so the
    message travels to the UI verbatim instead of being replaced with one of ours.
    """


@dataclass(slots=True)
class RemoteControl:
    """Where a session that has been handed to Remote Control can be driven from (§10.8)."""

    session_url: str
    """The one the user needs: this session on claude.ai/code and in the Claude mobile app."""

    connect_url: str | None = None
    environment_id: str | None = None


@runtime_checkable
class AgentBackend(Protocol):
    """Streaming execution of one task by one team member."""

    name: str

    def is_available(self) -> tuple[bool, str]:
        """(usable, human-readable reason) — surfaced in Settings before a run starts."""
        ...

    def run(self, request: AgentRunRequest) -> AsyncIterator[AgentEvent]:
        """Yield events as the agent works. Cancellation is via task cancellation."""
        ...


@runtime_checkable
class SessionControl(Protocol):
    """Steering a session **while it runs**, out of band from the event stream (§10.8).

    Separate from :class:`AgentBackend` because it is optional and lives on a different
    timescale: ``run`` is one call that lasts the whole session, while this is called from
    a REST handler in the middle of it. A backend that cannot be steered simply never
    registers one, and the orchestrator reports that there is nothing to steer.
    """

    async def enable_remote_control(self, name: str | None = None) -> RemoteControl: ...

    async def disable_remote_control(self) -> None: ...


#: Live sessions, keyed by task id. Written by the backend for the length of one run — the
#: registry exists because ``get_backend`` builds a fresh instance per call, so there is no
#: backend object for a REST handler to find the running session on.
_LIVE_SESSIONS: dict[str, SessionControl] = {}


def register_live_session(task_id: str, control: SessionControl) -> None:
    _LIVE_SESSIONS[task_id] = control


def forget_live_session(task_id: str) -> None:
    """Must run in a ``finally``: a stale entry offers control of a process that is gone."""
    _LIVE_SESSIONS.pop(task_id, None)


def live_session(task_id: str) -> SessionControl | None:
    return _LIVE_SESSIONS.get(task_id)


@runtime_checkable
class ContextInspector(Protocol):
    """Asks a runtime that keeps no process between turns for its own context report (§10.4).

    The counterpart of ``SessionControl.get_context_usage`` for a backend whose session is
    a saved journal rather than a live process: the inspector outlives the run and reopens
    that session for one local command. ``ready`` is False while a turn is in flight —
    two processes must not hold one session.
    """

    session_id: str | None

    @property
    def ready(self) -> bool: ...

    async def context_report(self) -> str: ...


#: Keyed like ``_LIVE_SESSIONS``, but kept after the run: bounded, oldest dropped first.
_CONTEXT_INSPECTORS: dict[str, ContextInspector] = {}
_MAX_CONTEXT_INSPECTORS = 256


def register_context_inspector(key: str, inspector: ContextInspector) -> None:
    _CONTEXT_INSPECTORS.pop(key, None)
    _CONTEXT_INSPECTORS[key] = inspector
    while len(_CONTEXT_INSPECTORS) > _MAX_CONTEXT_INSPECTORS:
        _CONTEXT_INSPECTORS.pop(next(iter(_CONTEXT_INSPECTORS)))


def context_inspector(key: str, session_id: str | None) -> ContextInspector | None:
    """Only for the session the owner is on now: a chat may have moved to another backend."""
    inspector = _CONTEXT_INSPECTORS.get(key)
    if inspector is None or not session_id or inspector.session_id != session_id:
        return None
    return inspector


_BACKENDS: dict[str, Callable[[], AgentBackend]] = {}


def register_backend(name: str, factory: Callable[[], AgentBackend]) -> None:
    _BACKENDS[name] = factory


def get_backend(name: str) -> AgentBackend:
    factory = _BACKENDS.get(name)
    if factory is None:
        raise KeyError(
            f"Unknown agent backend {name!r}. Available: {', '.join(sorted(_BACKENDS)) or 'none'}"
        )
    return factory()


def available_backends() -> list[str]:
    return sorted(_BACKENDS)
