"""Chats with a team member, outside the task board (§10.9).

A chat is the other thing a member's session is for. A task run hands the member a brief
and expects work back through review; a chat hands the member the *user* — where they are
in the Studio right now and what they typed — and expects an answer. Everything else is
the same machinery on purpose: the same ``AgentBackend`` (§10.6), the same ``--agent``
definition, the same Studio tools and the same project MCP servers a task run gets
(§10.4, §10.7), so a member asked about the board in a chat can read the board, and one
asked to leave a note can write the document.

What differs, and why:

- **No card, no column, no review.** A chat has a title, a transcript and a session id,
  and none of the status machinery — ``_set_task_status`` and everything that hangs off
  it describe work that ends, and a conversation does not.
- **No isolated checkout.** The user is talking about the project they can see, so the
  session starts at the launch root itself (§10.5); a member allowed to edit files edits
  the real ones. That is the point rather than a gap, and the reason a chat is not a
  task in disguise.
- **The context rides on every message, not in the system prompt.** Where the user is
  changes between one message and the next, and a resumed session has the earlier
  messages in front of it already, so each turn opens with the current page, document
  and selection (the ``chat_context`` template) and then the words.
- **One turn at a time, and a message never waits for it.** Two subprocesses resuming one
  session would race each other for it, so a turn is never started beside a running one —
  but what the user sends meanwhile is not refused either. It is queued on the turn's
  :class:`~.backends.base.Inbox` and reaches the member at the next opportunity without
  stopping anything: between two tool calls where the runtime can take it there, otherwise
  as the next turn of the same session (§10.8). The transcript row carries
  ``data.delivery`` — ``queued`` until the member has it, ``delivered`` after.

One chat is one Claude Agent SDK session, resumed turn after turn through
``AgentChat.agent_session_id`` (§10.3), which is what makes the member remember what was
said. The transcript is its own table (``agent_chat_message``) in ``AgentMessage``'s shape,
so the SPA draws both with one component.
"""

from __future__ import annotations

import asyncio
import logging
import re
from contextlib import suppress
from dataclasses import dataclass, replace
from datetime import datetime
from typing import TYPE_CHECKING, Any

from sqlmodel import col, delete, select

from .transcript import compact_state

from ..core.mcp import allowed_tool_names as mcp_allowed_tools
from ..core.mcp import enabled_servers as project_mcp_servers
from ..models.base import utcnow
from ..models.teams import AgentChat, AgentChatMessage, MemberKind, Team, TeamMember
from ..ws.events import EventType
from . import skill as studio_skill
from .backends.base import (
    DELIVERED,
    QUEUED,
    AgentEvent,
    AgentRunRequest,
    Inbox,
    QueuedMessage,
    get_backend,
    member_run_settings,
)
from .backends.recovery import recovery_prompt
from .slots import agent_slot
from .tools import build_toolset
from .usage import RunUsage

if TYPE_CHECKING:  # pragma: no cover - import cycle only matters to type checkers
    from .orchestrator import TeamOrchestrator

log = logging.getLogger(__name__)

#: Job kind under which a turn runs, beside ``agent_task`` and ``pipeline_run``. The job id
#: is the chat id, which is what makes ``is_running`` / ``cancel`` one lookup.
JOB_KIND = "agent_chat"

#: How much selected text a message may carry to the member. A selection is context, not
#: a document: past this the tail is dropped and the member is told how much was.
MAX_SELECTION_CHARS = 8_000

#: Characters of the first message a chat is titled with when nobody named it.
TITLE_CHARS = 60


#: Seconds a message waits for a turn that is already closing before it is refused.
CLOSING_WAIT = 15.0


class ChatBusy(RuntimeError):
    """The chat cannot take this right now — a setting changed mid-turn, or a message
    that arrived as a turn was closing and could not follow it."""


@dataclass(slots=True)
class UserContext:
    """Where the user is in the Studio at the moment they press Send.

    Filled by the SPA (``stores/chat.ts``) and described to the member in words by
    :func:`describe_context`. Every field is optional because every page has less of it
    than the Documents section: the board has a page and nothing else, the Explorer a
    file, Documents a document and possibly a selection.
    """

    page: str = ""
    """The section's name as the rail shows it — ``Documents``, ``Tasks``."""

    route: str = ""
    """The address bar's path and query, ``/documents?path=features/dash.md``."""

    title: str = ""
    """What the page calls what is open — a document's heading, a task's title."""

    document: str | None = None
    """A shared document's path under the docs folder, when one is open (§10.4)."""

    file: str | None = None
    """A project file's path from the project root, when the Explorer has one open."""

    selection: str | None = None
    """Text the user has selected on the page, if any."""

    def to_json(self) -> dict[str, Any]:
        return {
            "page": self.page,
            "route": self.route,
            "title": self.title,
            "document": self.document,
            "file": self.file,
            "selection": self.selection,
        }


def describe_context(context: UserContext, *, docs_dir: Any, project_root: Any) -> str:
    """The ``{context}`` lines of the ``chat_context`` template — one per thing known.

    Paths are given both as the Studio names them and as files on disk, because the member
    has two ways to open a document — ``read_doc`` by its docs-relative path and ``Read``
    by its absolute one — and should not have to work out which is which.
    """
    lines: list[str] = []
    page = context.page.strip() or "the Studio"
    where = f"- Page: {page}"
    if context.title.strip():
        where += f" — {context.title.strip()}"
    if context.route.strip():
        where += f" ({context.route.strip()})"
    lines.append(where)
    if context.document:
        lines.append(
            f"- Open document: {context.document} "
            f"(read_doc path; on disk at {docs_dir / context.document})"
        )
    if context.file:
        lines.append(f"- Open file: {context.file} (on disk at {project_root / context.file})")
    if context.selection and context.selection.strip():
        selection = context.selection
        note = ""
        if len(selection) > MAX_SELECTION_CHARS:
            note = f" (first {MAX_SELECTION_CHARS} of {len(selection)} characters)"
            selection = selection[:MAX_SELECTION_CHARS]
        lines.append(f"- Selected text{note}:\n```\n{selection}\n```")
    return "\n".join(lines)


def title_from(text: str) -> str:
    """The first line of a message, cut to a title's length."""
    first = next((line.strip() for line in text.splitlines() if line.strip()), "")
    if len(first) <= TITLE_CHARS:
        return first
    return first[: TITLE_CHARS - 1].rstrip() + "…"


class ChatService:
    """Chats and their turns, over the orchestrator's database, bus, jobs and config.

    Built once per :class:`~voxarium_studio.core.context.AppContext` and handed the orchestrator
    rather than its parts: the config is re-read through it on every turn, so a settings
    save reaches the next message without any wiring here, and the Studio tools it builds
    are the orchestrator's own.
    """

    def __init__(self, orchestrator: "TeamOrchestrator") -> None:
        self.orchestrator = orchestrator
        from .producer import ProducerService
        self.producer = ProducerService(self)
        self._locks: dict[str, asyncio.Lock] = {}
        self.closing = False
        self._ending: set[str] = set()
        """Chats whose turn is in its ``finally`` — still in the job registry, no longer
        answering. ``is_running`` reads False for them, the way the orchestrator's
        ``_ending`` works for tasks."""
        self._inboxes: dict[str, Inbox] = {}
        """What was sent to each chat while its turn runs (§10.8), for the length of it."""

    # ---- what the orchestrator owns ----

    @property
    def db(self):  # noqa: ANN201 - the orchestrator's, typed there
        return self.orchestrator.db

    @property
    def bus(self):  # noqa: ANN201
        return self.orchestrator.bus

    @property
    def jobs(self):  # noqa: ANN201
        return self.orchestrator.jobs

    @property
    def config(self):  # noqa: ANN201
        return self.orchestrator.config

    @property
    def paths(self):  # noqa: ANN201
        return self.orchestrator.paths

    # ---- reading ----

    def lock(self, chat_id: str) -> asyncio.Lock:
        return self._locks.setdefault(chat_id, asyncio.Lock())

    def is_running(self, chat_id: str) -> bool:
        return self.jobs.is_running(chat_id) and chat_id not in self._ending

    def to_json(self, chat: AgentChat, *, running: bool | None = None) -> dict[str, Any]:
        """One chat as every endpoint and event carries it."""
        return {
            **chat.model_dump(mode="json"),
            "is_running": self.is_running(chat.id) if running is None else running,
        }

    async def list_chats(self) -> list[AgentChat]:
        async with self.db.session() as session:
            rows = await session.exec(
                select(AgentChat).order_by(col(AgentChat.last_message_at).desc())
            )
            return list(rows.all())

    async def get_chat(self, chat_id: str) -> AgentChat | None:
        async with self.db.session() as session:
            return await session.get(AgentChat, chat_id)

    async def set_model(self, chat_id: str, backend_name: str | None, model: str | None) -> AgentChat:
        """Save a chat's runtime choice and start a fresh session when it changes (§10.9)."""
        from .backends.base import available_backends
        if backend_name is not None and backend_name not in available_backends():
            raise ValueError("Unknown agent backend")
        model = model.strip() if model is not None else None
        if model and not re.fullmatch(r"[A-Za-z0-9._-]+", model):
            raise ValueError("A model id may only contain letters, digits, dots, dashes and underscores")
        async with self.lock(chat_id):
            if self.is_running(chat_id):
                raise ChatBusy("Wait for the current turn before changing the model")
            async with self.db.session() as session:
                chat = await session.get(AgentChat, chat_id)
                if chat is None:
                    raise KeyError(chat_id)
                if (chat.agent_backend, chat.model) != (backend_name, model):
                    chat.agent_backend = backend_name
                    chat.model = model
                    chat.agent_session_id = None
                    session.add(chat)
            await self._publish_chat(chat)
            return chat

    async def messages(self, chat_id: str) -> list[AgentChatMessage]:
        async with self.db.session() as session:
            rows = await session.exec(
                select(AgentChatMessage)
                .where(AgentChatMessage.chat_id == chat_id)
                .order_by(col(AgentChatMessage.created_at), col(AgentChatMessage.id))
            )
            return list(rows.all())

    # ---- lifecycle ----

    def _validate_producer_workspace(self, member: TeamMember, locale: str) -> None:
        from .producer_identity import is_producer
        from .tools import SERVER_NAME
        if member.kind != MemberKind.AGENT or not is_producer(member):
            raise ValueError("Producer Mode is available only with the built-in Producer.")
        if locale not in {"en", "ru"}:
            raise ValueError("Unknown Producer language.")
        if SERVER_NAME in self.config.mcp.disabled:
            raise ValueError("Enable the built-in Studio MCP server to use Producer Mode.")
        try:
            import claude_agent_sdk  # noqa: F401
        except ImportError as exc:
            raise ValueError("Install the Studio teams extra to use Producer Mode.") from exc

    async def create_chat(self, member_id: str, *, title: str = "", producer_mode: bool = False,
                          locale: str = "en") -> AgentChat:
        """Open a chat with an agent member. ``KeyError`` for nobody, ``ValueError`` for a person."""
        if member_id in self.orchestrator.removing_members:
            raise ValueError("This member is being removed. Wait for the team to refresh.")
        async with self.db.session() as session:
            member = await session.get(TeamMember, member_id)
            if member is None:
                raise KeyError(f"No team member {member_id!r}")
            if member.kind == MemberKind.HUMAN:
                raise ValueError(
                    f"{member.name} is a person, not an agent — there is no session to chat with."
                )
            if producer_mode:
                self._validate_producer_workspace(member, locale)
            # The workspace enables the mode at creation, without spending a turn on
            # a greeting. Its first user message starts the Producer session (§10.11).
            chat = AgentChat(
                member_id=member_id, title=title.strip(), producer_mode=producer_mode,
                producer_workspace=producer_mode, producer_locale=locale,
                producer_state="discussing" if producer_mode else "off",
            )
            session.add(chat)
        await self._publish_chat(chat)
        return chat

    async def use_producer_workspace(self, chat_id: str, *, locale: str = "en") -> AgentChat:
        """Adopt an older Producer chat without discarding its active session (§10.11)."""
        async with self.lock(chat_id):
            chat = await self.producer._chat(chat_id, active=False)
            if chat.producer_workspace and chat.producer_mode:
                return chat
            async with self.db.session() as session:
                member = await session.get(TeamMember, chat.member_id)
            self._validate_producer_workspace(member, locale)
            if chat.producer_mode:
                return await self.producer._save(chat_id, producer_workspace=True)
            self.producer._idle(chat_id)
            return await self.producer._save(
                chat_id, producer_workspace=True, producer_mode=True, producer_locale=locale,
                producer_state="discussing", error=None, producer_paused=False,
            )

    async def delete_chat(self, chat_id: str) -> bool:
        async with self.lock(chat_id):
            return await self._delete_chat(chat_id)

    async def _delete_chat(self, chat_id: str) -> bool:
        """Remove a chat and its transcript, stopping the turn in flight if there is one."""
        # §10.11: another Producer chat can pick up these tasks, including held drafts.
        chat = await self.get_chat(chat_id)
        if chat and chat.producer_mode:
            await self.producer.disable(chat_id, cancel=False, deleting=True)
        await self.cancel(chat_id)
        async with self.db.session() as session:
            chat = await session.get(AgentChat, chat_id)
            if chat is None:
                return False
            member_id = chat.member_id
            await session.exec(  # type: ignore[call-overload]
                delete(AgentChatMessage).where(col(AgentChatMessage.chat_id) == chat_id)
            )
            await session.delete(chat)
        await self.bus.publish(
            EventType.TEAM_CHAT_DELETED, {"chat_id": chat_id, "member_id": member_id}
        )
        return True

    async def cancel(self, chat_id: str) -> bool:
        """Stop the member mid-answer. False when nothing was running."""
        chat = await self.get_chat(chat_id)
        if chat and chat.producer_mode:
            await self.producer._save(chat_id, producer_state="discussing", producer_watch=[])
        return await self._cancel_turn(chat_id)

    async def _cancel_turn(self, chat_id: str) -> bool:
        handle = self.jobs.get(chat_id)
        cancelled = await self.jobs.cancel(chat_id)
        if handle and handle.task:
            # Let the final transcript/state writes finish before deletion or restart.
            # The subprocess backends clean up their children in cancellation handlers.
            with suppress(asyncio.CancelledError):
                await handle.task
        return cancelled

    async def send(self, chat_id: str, text: str, context: UserContext, *, attachments: list[str] | None = None) -> AgentChatMessage:
        async with self.lock(chat_id):
            return await self._send(chat_id, text, context, attachments=attachments)

    async def _send(self, chat_id: str, text: str, context: UserContext, *, attachments: list[str] | None = None) -> AgentChatMessage:
        """Record what the user said and get it to the member.

        The user's own words are stored as they typed them, with the context beside them
        in ``data`` for the UI to show; what the member receives is the two composed
        through the ``chat_context`` template. An idle chat starts a turn on the message.
        A chat whose member is still answering queues it instead (§10.8): nothing is
        interrupted, and the member has it at the next tool boundary or as the next turn.
        Raises ``KeyError`` for an unknown chat, ``ValueError`` for an empty message and
        :class:`ChatBusy` only when a closing turn could not be followed.
        """
        body = text.strip()
        records = self.orchestrator.attachments.resolve_uploads(attachments or [])
        if not body and not records:
            raise ValueError("A message needs some text.")
        if self.jobs.is_running(chat_id):
            queued = await self._queue(chat_id, body, context, records)
            if queued is not None:
                return queued
            if self.jobs.is_running(chat_id):
                raise ChatBusy("The member is still answering — wait for the reply or stop it.")

        async with self.db.session() as session:
            chat = await session.get(AgentChat, chat_id)
            if chat is None:
                raise KeyError(f"No chat {chat_id!r}")
            member = await session.get(TeamMember, chat.member_id)
            if member is None or member.kind == MemberKind.HUMAN:
                raise ValueError("This conversation is read-only because its agent was removed.")
            if member.id in self.orchestrator.removing_members:
                raise ValueError("This member is being removed. Wait for the team to refresh.")
            if chat.producer_mode:
                self.producer.require_resumed(chat_id)
            if not chat.title:
                chat.title = title_from(body or records[0]["name"])
            chat.error = None
            chat.last_message_at = utcnow()
            session.add(chat)
            member_id = chat.member_id

        message = await self.record(
            chat_id,
            member_id,
            role="user",
            kind="text",
            text=body,
            data={"context": context.to_json(), "attachments": records},
        )
        producer_skill_on_resume = chat.producer_mode and not chat.producer_started
        await self.producer.on_user_message(chat, message)
        await self._publish_chat_by_id(chat_id, running=True)

        prompt = self._compose(body, context, records)

        if chat.producer_mode:
            self.producer.require_resumed(chat_id)
        if member_id in self.orchestrator.removing_members:
            raise ValueError("This member is being removed. Wait for the team to refresh.")
        self.jobs.submit(
            chat_id,
            JOB_KIND,
            lambda handle: self._run_turn(chat_id, member_id, prompt, self.orchestrator.attachments.images(records),
                                          input_at=message.created_at,
                                          producer_skill_on_resume=producer_skill_on_resume),
            member_id=member_id,
            chat_id=chat_id,
        )
        return message

    def _compose(self, body: str, context: UserContext | None, records: list[dict[str, Any]]) -> str:
        """A user's message as the member receives it: where they are, the words, the files."""
        from .attachments import with_trailer

        prompt = body
        if context is not None:
            described = describe_context(
                context,
                docs_dir=self.orchestrator.docs.directory,
                project_root=self.paths.root,
            )
            opening = self.config.teams.prompts.text("chat_context", context=described)
            prompt = f"{opening}\n\n{body}" if opening else body
        return with_trailer(prompt, self.orchestrator.attachments.trailer(records))

    # ---- messages sent while the member is busy (§10.8) ----

    def _new_inbox(self, chat_id: str) -> Inbox:
        inbox = self._inboxes[chat_id] = Inbox(
            preamble=self.config.teams.prompts.text("mid_run_message"),
            on_delivered=self._mark_delivered,
        )
        return inbox

    async def _queue(
        self, chat_id: str, body: str, context: UserContext, records: list[dict[str, Any]],
    ) -> AgentChatMessage | None:
        """Hand a message to the turn in flight. ``None`` when that turn is over after all.

        A turn that has stopped taking messages is in its last moments — the backend has
        answered and only bookkeeping is left — so the message waits those out and the
        caller starts a turn of its own on it, rather than bouncing off a member who has,
        as far as the user can see, already finished.
        """
        inbox = self._inboxes.get(chat_id)
        if chat_id in self._ending or (inbox is not None and inbox.closed):
            handle = self.jobs.get(chat_id)
            if handle is not None and handle.task is not None:
                with suppress(asyncio.TimeoutError, asyncio.CancelledError, Exception):
                    await asyncio.wait_for(asyncio.shield(handle.task), CLOSING_WAIT)
            return None
        async with self.db.session() as session:
            chat = await session.get(AgentChat, chat_id)
            if chat is None:
                raise KeyError(f"No chat {chat_id!r}")
            member = await session.get(TeamMember, chat.member_id)
            if member is None or member.kind == MemberKind.HUMAN:
                raise ValueError("This conversation is read-only because its agent was removed.")
            if chat.producer_mode:
                self.producer.require_resumed(chat_id)
        # Re-read after the awaits above: the turn may have closed while the chat was loaded.
        inbox = self._inboxes.get(chat_id)
        if not self.jobs.is_running(chat_id) or (inbox is not None and inbox.closed):
            return None
        if inbox is None:
            # The job is registered and its coroutine has not run a line yet; it adopts this.
            inbox = self._new_inbox(chat_id)
        message = AgentChatMessage(
            chat_id=chat_id,
            member_id=chat.member_id,
            role="user",
            kind="text",
            text=body,
            data={"context": context.to_json(), "attachments": records, "delivery": QUEUED},
        )
        # Queued before it is stored, with no await between the check above and here, so a
        # message the inbox accepted is one its turn will deliver or hand back.
        inbox.put(QueuedMessage(
            id=message.id,
            text=self._compose(body, context, records),
            images=self.orchestrator.attachments.images(records),
        ))
        async with self.db.session() as session:
            session.add(message)
            stored = await session.get(AgentChat, chat_id)
            if stored is not None:
                stored.last_message_at = message.created_at
                session.add(stored)
        await self._publish_message(message)
        await self.producer.on_user_message(chat, message)
        return message

    async def _mark_delivered(self, items: list[QueuedMessage]) -> None:
        """The member has these now: say so on their rows, and to whoever is watching."""
        ids = [item.id for item in items]
        async with self.db.session() as session:
            rows = list((await session.exec(
                select(AgentChatMessage).where(col(AgentChatMessage.id).in_(ids))
            )).all())
            for row in rows:
                # A new dict, not a mutation: the JSON column only notices assignment.
                row.data = {**row.data, "delivery": DELIVERED}
                session.add(row)
        for row in rows:
            await self._publish_message(row)

    async def _undelivered(self, chat_id: str) -> list[QueuedMessage]:
        """Messages a stopped or failed turn never got to hand over, oldest first.

        Read from the transcript rather than kept in memory, so they survive the turn —
        and a restart — and go out with whatever opens the next one. They are recomposed
        without the place the user was in when they wrote them: that was then.
        """
        async with self.db.session() as session:
            rows = (await session.exec(
                select(AgentChatMessage)
                .where(AgentChatMessage.chat_id == chat_id, AgentChatMessage.role == "user")
                .order_by(col(AgentChatMessage.created_at), col(AgentChatMessage.id))
            )).all()
        return [
            QueuedMessage(
                id=row.id,
                text=self._compose(row.text, None, _records(row.data)),
                images=self.orchestrator.attachments.images(_records(row.data)),
            )
            for row in rows
            if row.data.get("delivery") == QUEUED
        ]

    # ---- the turn ----

    async def _run_turn(self, chat_id: str, member_id: str, prompt: str, images: list[dict[str, Any]] | None = None,
                        *, input_at: datetime | None = None,
                        producer_skill_on_resume: bool = False) -> None:
        orchestrator = self.orchestrator
        # Before the first await, so "the job is running" and "it takes messages" start
        # together; one a sender opened a moment earlier is adopted rather than replaced.
        inbox = self._inboxes.get(chat_id)
        if inbox is None or inbox.closed:
            inbox = self._new_inbox(chat_id)
        async with self.db.session() as session:
            chat = await session.get(AgentChat, chat_id)
            member = await session.get(TeamMember, member_id)
            if chat is None or member is None:
                inbox.close()
                self._inboxes.pop(chat_id, None)
                return
            team = await session.get(Team, member.team_id)
            reports = await orchestrator._direct_reports(session, member.id)
            manager = (
                await session.get(TeamMember, member.manager_id) if member.manager_id else None
            )
            peers = await orchestrator._assignable_peers(session, member)
            resume_session_id = chat.agent_session_id

        try:
            # The launch root itself, never an isolated checkout (see the module docstring).
            # A launch root that is missing on this machine falls back to the project root
            # with the same note a task run writes, once per chat rather than per turn.
            workdir, launch_note = orchestrator._launch_root()
            if launch_note and resume_session_id is None:
                await self.record(
                    chat_id,
                    member_id,
                    role="system",
                    kind="workdir",
                    text=launch_note,
                    data={"path": str(workdir)},
                )

            runtime_member = member.model_copy(update={
                "agent_backend": chat.agent_backend or member.agent_backend,
                "model": chat.model if chat.model is not None else (
                    member.model if not chat.agent_backend or chat.agent_backend == member.agent_backend else ""),
            })
            backend = get_backend(runtime_member.agent_backend)
            # `None` for a backend without named personas. The identity travels in the
            # system prompt on every backend — full replacement discards a definition's
            # prompt — the same rule a task run applies (§10.6).
            agent_name = (
                orchestrator._resolve_agent(
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
            toolset = build_toolset(orchestrator, member=member, task=None,
                                    producer=self.producer if chat.producer_mode else None,
                                    chat_id=chat_id, producer_effort_mode=chat.producer_effort_mode)
            if chat.producer_mode and toolset is None:
                raise ValueError("Producer Mode needs the built-in Studio MCP tools enabled.")
            from .memory import memory_prompt
            from .orchestrator import _build_system_prompt, code_graph_file

            # The member's skills reach a chat as they reach a task (§10.10) — the same folders,
            # read fresh each turn, with no transcript line: a chat is not a record of what a run
            # was told, and the page it lives on shows the conversation, not the service notes.
            from .required_context import load_required_context
            required = load_required_context(
                self.config.teams, member, orchestrator.skills, orchestrator.docs,
                producer_mode=chat.producer_mode,
            )
            member_skills = orchestrator.skills.for_session([
                *(member.skills or []), *(skill.name for skill in required.skills.found),
            ], producer_mode=chat.producer_mode)
            system_prompt = _build_system_prompt(
                member=member,
                team=team,
                manager=manager,
                reports=reports,
                peers=peers,
                task_id="",
                project_root=self.paths.root,
                workdir=workdir,
                isolated=False,
                docs_dir=orchestrator.docs.directory,
                has_studio_tools=toolset is not None,
                # §10.12: a chat, the Producer's included, is told the project memory too.
                memory=memory_prompt(orchestrator.memory, self.config.teams,
                                     has_tools=toolset is not None),
                prompts=self.config.teams.prompts,
                code_graph=code_graph_file(workdir),
                chat=True,
                skills=member_skills.found,
                language=self.config.respond_language,
            )
            if chat.producer_mode:
                # §10.11: the Producer's read-only responsibility mirrors this prompt;
                # identity/agent files omit it so it reaches only active chats, once,
                # with the current global response preference (§10.4), not legacy chat metadata.
                system_prompt += "\n\n" + self.config.teams.prompts.text(
                    "producer_mode", language=self.config.respond_language)
                system_prompt += "\n\n" + self.config.teams.prompts.text(f"producer_{chat.producer_policy}")
            # The project's servers first and the Studio's own over them, as a task run
            # merges them (§10.7): a project server called `voxarium_studio` cannot displace the
            # tools the member reaches the board with.
            project_mcp = project_mcp_servers(self.paths, self.config)
            recovered_prompt = (
                required.prepend(recovery_prompt(prompt, await self.messages(chat_id)))
                if resume_session_id and getattr(backend, "session_recovery", False)
                else ""
            )
            # §10.9: a model/backend switch drops the runtime session but keeps the chat.
            # Rebuild the next turn's context from its saved transcript.
            fresh_prompt = (required.prepend(recovery_prompt(prompt, await self.messages(chat_id)))
                            if not resume_session_id and chat.num_turns else required.prepend(prompt))
            request = AgentRunRequest(
                member=runtime_member,
                **member_run_settings(member, self.config.teams),
                task=None,
                session_key=chat_id,
                proactive_subagents=True,
                workdir=workdir,
                writable_roots=[self.paths.root],
                prompt=fresh_prompt if not resume_session_id else prompt,
                images=images or [],
                system_prompt=system_prompt,
                agent_name=agent_name,
                resume_session_id=resume_session_id,
                recovery_prompt=recovered_prompt,
                mcp_servers={**project_mcp, **(toolset.mcp_servers if toolset else {})},
                plugin_dirs=[studio_skill.BUNDLE],
                skill_files={skill.name: skill.skill_file for skill in member_skills.found},
                extra_allowed_tools=[
                    *mcp_allowed_tools(project_mcp),
                    *(toolset.allowed_tools if toolset else []),
                    *studio_skill.allowed_tools(),
                    *(studio_skill.producer_allowed_tools() if chat.producer_mode else ()),
                    *orchestrator.attachments.allowed_tools(),
                    *member_skills.allowed_tools(),
                ],
                strict_mcp_config=self.config.mcp.strict,
                max_continuations=self.config.teams.max_run_continuations,
                continue_prompt=self.config.teams.prompts.text("stall_continue"),
                max_message_bytes=self.config.teams.max_message_bytes,
                delegated_work_idle_timeout=self.config.teams.delegated_work_idle_timeout,
                inbox=inbox,
            )

            async with agent_slot(self.jobs, self.record, chat_id, member_id):
                # What an earlier turn was stopped before delivering goes out with this one
                # (§10.8). Taken here, with the slot held, so "delivered" is not said of a
                # turn that was cancelled while it was still waiting to start.
                earlier = await self._undelivered(chat_id)
                if earlier:
                    inbox.discard({item.id for item in earlier})
                    prompt = "\n\n".join([*(item.text for item in earlier), prompt])
                    request.prompt = required.prepend(prompt) if not resume_session_id else prompt
                    request.images = [*(image for item in earlier for image in item.images), *request.images]
                    await self._mark_delivered(earlier)
                if chat.producer_mode:
                    await self.producer.compact(chat_id, backend, request)
                    fresh = await self.get_chat(chat_id)
                    request.resume_session_id = fresh.agent_session_id
                    state = await self.producer.context(chat_id)
                    request.prompt = self.config.teams.prompts.text("producer_state", state=state, prompt=prompt)
                    if not request.resume_session_id and fresh.producer_summary:
                        request.prompt = self.config.teams.prompts.text(
                            "producer_summary", summary=fresh.producer_summary, prompt=request.prompt)
                    request.recovery_prompt = required.prepend(recovery_prompt(
                        self.config.teams.prompts.text(
                            "producer_summary", summary=fresh.producer_summary, prompt=request.prompt),
                        await self.messages(chat_id)))
                    # §10.11: activating Producer in an existing chat keeps its model
                    # session, so its first Producer turn must receive the new skill.
                    if not request.resume_session_id or producer_skill_on_resume:
                        request.prompt = required.prepend(request.prompt)
                try:
                    await self._consume(backend, request, chat_id, member_id, input_at=input_at)
                except Exception as exc:
                    from .producer import context_overflow
                    if not chat.producer_mode or not context_overflow(str(exc)):
                        raise
                    await self._set_error(chat_id, str(exc))
                fresh = await self.get_chat(chat_id)
                if fresh and fresh.producer_mode and fresh.error:
                    from .producer import context_overflow
                    if context_overflow(fresh.error):
                        await self.producer.compact(chat_id, backend, request, force=True)
                        fresh = await self.get_chat(chat_id)
                        request.resume_session_id = None
                        state_prompt = self.config.teams.prompts.text(
                            "producer_state", state=await self.producer.context(chat_id), prompt=prompt)
                        request.prompt = self.config.teams.prompts.text(
                            "producer_summary", summary=fresh.producer_summary, prompt=state_prompt)
                        request.prompt = required.prepend(request.prompt)
                        await self._set_error(chat_id, None)
                        await self._consume(backend, request, chat_id, member_id)
                # Whatever was sent during the turn and not taken mid-way is the next turn
                # of the same session (§10.8) — the whole delivery for a backend that cannot
                # be handed text between tool calls. A turn that failed delivers nothing
                # more: those messages stay queued and open the next one.
                while True:
                    fresh = await self.get_chat(chat_id)
                    if fresh is None or fresh.error or not len(inbox):
                        inbox.close()
                        break
                    items = await inbox.take()
                    text = inbox.compose(items)
                    if fresh.producer_mode:
                        text = self.config.teams.prompts.text(
                            "producer_state", state=await self.producer.context(chat_id), prompt=text)
                    request.resume_session_id = fresh.agent_session_id
                    request.prompt = text if request.resume_session_id else required.prepend(text)
                    request.images = [image for item in items for image in item.images]
                    request.recovery_prompt = (
                        required.prepend(recovery_prompt(text, await self.messages(chat_id)))
                        if request.resume_session_id and getattr(backend, "session_recovery", False)
                        else ""
                    )
                    await self._consume(backend, request, chat_id, member_id)
        except asyncio.CancelledError:
            await self.record(
                chat_id, member_id, role="system", kind="cancelled", text="Stopped."
            )
            raise
        except Exception as exc:  # a failure is the chat's to show, never the server's to die of
            log.exception("Chat turn %s failed", chat_id)
            await self._set_error(chat_id, str(exc))
            await self.record(chat_id, member_id, role="system", kind="error", text=str(exc))
        finally:
            # Still in the job registry until this coroutine returns, so the event has to
            # say what `is_running` cannot yet: the member is no longer answering.
            self._ending.add(chat_id)
            # Closed however the turn ended. What it holds stays `queued` on its rows.
            inbox.close()
            if self._inboxes.get(chat_id) is inbox:
                self._inboxes.pop(chat_id, None)
            try:
                await self.producer.turn_finished(chat_id)
                await self._publish_chat_by_id(chat_id, running=False)
            finally:
                self._ending.discard(chat_id)

    async def _consume(
        self, backend: Any, request: AgentRunRequest, chat_id: str, member_id: str, *, input_at: datetime | None = None,
    ) -> None:
        from .context_viewer import capture_request

        await capture_request(self.db, chat_id, request, input_at=input_at if request.resume_session_id else None)
        # §10.11: publish durable live chat usage; results add only the uncredited balance.
        usage = RunUsage()
        credited = usage.counters()
        async for event in backend.run(request):
            if event.data.get("subtype") == "session_recovered":
                await capture_request(self.db, chat_id, replace(
                    request, resume_session_id=None,
                    prompt=request.recovery_prompt or recovery_prompt(request.prompt, []),
                ))
            changed = usage.observe(event)
            if event.kind == "result":
                usage.finish(event.data)
                event = replace(event, data={**event.data, **usage.counters()})
            await self._publish_event(chat_id, member_id, event)
            if changed or event.kind == "result":
                counters = usage.counters()
                await self._store_result_stats(chat_id, {
                    **(event.data if event.kind == "result" else {}),
                    **{key: value - credited[key] for key, value in counters.items()
                       if key != "accounting_version"},
                })
                credited = counters
            if event.kind == "result":
                usage.next_segment()
                credited = usage.counters()
            if event.session_id:
                await self._store_session_id(chat_id, event.session_id)
            if event.kind == "error":
                await self._set_error(chat_id, event.text)
            if event.kind == "result":
                if event.data.get("is_error") and not event.data.get("continued"):
                    await self._set_error(chat_id, event.text or "The session ended with an error.")

    async def _publish_event(self, chat_id: str, member_id: str, event: AgentEvent) -> None:
        await self.record(
            chat_id,
            member_id,
            role="assistant" if event.kind in ("text", "thinking", "tool_use") else "system",
            kind=event.kind,
            text=event.text,
            data=event.data,
        )

    # ---- persistence + events ----

    async def record(
        self,
        chat_id: str,
        member_id: str,
        *,
        role: str,
        kind: str,
        text: str = "",
        data: dict[str, Any] | None = None,
    ) -> AgentChatMessage:
        """Persist one transcript entry and stream it to the UI. Returns the stored row."""
        message = AgentChatMessage(
            chat_id=chat_id,
            member_id=member_id,
            role=role,
            kind=kind,
            text=text,
            data=data or {},
        )
        state = compact_state(message, chat_id)
        async with self.db.session() as session:
            if state:
                await session.merge(message)
            else:
                session.add(message)
            chat = await session.get(AgentChat, chat_id)
            if chat is not None:
                chat.last_message_at = message.created_at
                session.add(chat)

        await self._publish_message(message)
        return message

    async def _publish_message(self, message: AgentChatMessage) -> None:
        await self.bus.publish(
            EventType.TEAM_CHAT_MESSAGE,
            {
                "id": message.id,
                "chat_id": message.chat_id,
                "member_id": message.member_id,
                "role": message.role,
                "kind": message.kind,
                "text": message.text,
                "data": message.data,
                "created_at": message.created_at.isoformat(),
            },
            channel=f"team.{message.member_id}.chat",
        )

    async def _store_session_id(self, chat_id: str, session_id: str) -> None:
        async with self.db.session() as session:
            chat = await session.get(AgentChat, chat_id)
            if chat is not None and chat.agent_session_id != session_id:
                chat.agent_session_id = session_id
                session.add(chat)

    async def _store_result_stats(self, chat_id: str, data: dict[str, Any]) -> None:
        from .usage import usage_components, usage_directions

        uncached, cached = usage_components(data)
        sent, received = usage_directions(data)
        async with self.db.session() as session:
            chat = await session.get(AgentChat, chat_id)
            if chat is None:
                return
            chat.total_cost_usd += float(data.get("total_cost_usd") or 0.0)
            chat.num_turns += int(data.get("num_turns") or 0)
            chat.total_tokens += int(data.get("total_tokens") or 0)
            chat.total_uncached_tokens += uncached
            chat.total_cached_tokens += cached
            chat.total_input_tokens += sent
            chat.total_output_tokens += received
            session.add(chat)
        await self._publish_chat_by_id(chat_id)

    async def _set_error(self, chat_id: str, error: str | None) -> None:
        async with self.db.session() as session:
            chat = await session.get(AgentChat, chat_id)
            if chat is not None:
                chat.error = error
                session.add(chat)

    async def _publish_chat(self, chat: AgentChat, *, running: bool | None = None) -> None:
        await self.bus.publish(EventType.TEAM_CHAT_UPDATED, self.to_json(chat, running=running))

    async def _publish_chat_by_id(self, chat_id: str, *, running: bool | None = None) -> None:
        chat = await self.get_chat(chat_id)
        if chat is not None:
            await self._publish_chat(chat, running=running)


def _records(data: dict[str, Any] | None) -> list[dict[str, Any]]:
    """The attachment records a stored message carries, read defensively."""
    records = (data or {}).get("attachments")
    return [record for record in records if isinstance(record, dict)] if isinstance(records, list) else []
