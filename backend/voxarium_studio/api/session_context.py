"""Shared Context Viewer responses for task and chat sessions (§10.4, §10.9)."""

import asyncio
from collections.abc import Sequence
from typing import Any, Literal

from fastapi import HTTPException

from ..models import AgentChatMessage, AgentMessage, TeamMember
from ..models.task_context import SessionContext
from ..teams.backends.base import context_inspector, live_session
from ..teams.context_journals import read_session
from ..teams.context_viewer import (
    fragment_key,
    native_inventory,
    present,
    qwen_inventory,
    retained_context,
)

ContextView = Literal["auto", "studio"]


async def read_context(
    snapshot: SessionContext | None, messages: Sequence[AgentMessage | AgentChatMessage],
    member: TeamMember | None, session_id: str | None, view: ContextView = "auto",
) -> tuple[list[dict], dict]:
    backend = snapshot.backend if snapshot and snapshot.backend else member.agent_backend if member else ""
    journal = None
    if view == "auto":
        notice = {"codex": "instructions_in_prompt", "qwen": "system_prompt_in_message"}.get(backend)
        hint = ""
        if snapshot and notice and any(m.kind == "system" and m.data.get("subtype") == notice
                                       and m.created_at >= snapshot.started_at for m in messages):
            hint = next((p["text"] for p in snapshot.sources if p["category"] == "system"), "")
        journal = await asyncio.to_thread(read_session, backend, session_id, studio_system=hint)
    return retained_context(snapshot, messages, journal, backend=backend)


def _runtime_report(owner_id: str, session_id: str | None):
    """The runtime's own breakdown and its reader: a live Claude session, or Qwen between turns."""
    usage = getattr(live_session(owner_id), "get_context_usage", None)
    if callable(usage):
        return usage, native_inventory
    inspector = context_inspector(owner_id, session_id)
    if inspector is not None and inspector.ready:
        return inspector.context_report, qwen_inventory
    return None, None


def inventory_response(owner_id: str, parts: list[dict], metrics: dict,
                       session_id: str | None = None) -> dict[str, Any]:
    return {**present(parts, **metrics),
            "can_inspect": _runtime_report(owner_id, session_id)[0] is not None}


def fragment_response(parts: list[dict], source_id: int, key: str) -> dict[str, str]:
    if 0 <= source_id < len(parts):
        part = parts[source_id]
        if fragment_key(part) == key:
            return {"text": part["text"]}
    raise HTTPException(status_code=409, detail="The context fragment changed. Refresh the map.")


async def inspect_session(owner_id: str, session_id: str | None = None) -> dict[str, Any]:
    """An explicit request to the session's own runtime, never a new agent turn."""
    inspect, read = _runtime_report(owner_id, session_id)
    if inspect is None:
        raise HTTPException(status_code=409, detail="Context inspection requires an active Claude Code "
                            "session or a Qwen Code session between turns")
    try:
        report = await asyncio.wait_for(inspect(), timeout=45 if read is native_inventory else 120)
        if not isinstance(report, dict if read is native_inventory else str):
            raise ValueError("The runtime returned an invalid context breakdown")
        result = read(report)
    except TimeoutError as exc:
        raise HTTPException(status_code=504, detail="Context inspection timed out") from exc
    except Exception as exc:
        raise HTTPException(status_code=502, detail=str(exc)) from exc
    return {**result, "can_inspect": True}
