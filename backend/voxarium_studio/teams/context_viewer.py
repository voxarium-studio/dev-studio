"""Source inventory, not an invented provider token breakdown (§10.4).

Persist what Studio actually dispatched, rather than re-reading today's settings as
historical input. Providers do not expose their complete rendered request; text sizes
are estimates and the unexplained part of reported occupancy stays unattributed.
"""

from __future__ import annotations

import asyncio
import hashlib
import json
import re
from collections.abc import Sequence
from datetime import datetime
from typing import Any

from ..models.base import utcnow
from ..models.task_context import ChatSessionContext, SessionContext, TaskContext
from .backends.base import AgentRunRequest
from .context_sources import JournalContext, bounded_detail, count, prompt_sources, source


async def capture_request(db, owner_id: str, request: AgentRunRequest, *, input_at: datetime | None = None) -> None:
    """One row per task or chat; resumes retain the original startup materials (§10.4, §10.9)."""
    # Stopping while SQLite is flushing must release its writer before the caller
    # writes the cancellation to the transcript on another connection.
    write = asyncio.create_task(_save_request(db, owner_id, request, input_at))
    try:
        await asyncio.shield(write)
    except asyncio.CancelledError:
        await write
        raise


async def _save_request(db, owner_id: str, request: AgentRunRequest, input_at: datetime | None) -> None:
    async with db.session() as session:
        is_chat = request.task is None
        previous = await session.get(ChatSessionContext if is_chat else TaskContext, owner_id)
        fresh = (not request.resume_session_id or previous is None
                 or bool(previous.backend and previous.backend != request.member.agent_backend))
        row = previous or (ChatSessionContext(chat_id=owner_id) if is_chat else TaskContext(task_id=owner_id))
        if fresh:
            # On the first captured resume the raw user message is already in the chat
            # transcript. It belongs to this prompt, not to the unsnapshotted history.
            row.started_at = input_at or utcnow()
            row.partial = bool(request.resume_session_id)
        parts = [] if fresh else [part for part in row.sources if part["category"] != "system"]
        # The Studio's system prompt (Codex's model_instructions_file, Claude's and
        # Qwen's replaced prompt) is supplied again on resume; required materials
        # inside the first user turn stay.
        # Store raw fields. Only the read path classifies them, so updated readers
        # can inspect earlier dispatches without rewriting either field (§10.4).
        key = "studio.request[" + utcnow().isoformat() + "]"
        if request.system_prompt:
            parts.insert(0, source("system", request.system_prompt, origin=key + ".system_prompt"))
        parts.append(source("task", request.prompt, origin=key + ".prompt"))
        row.sources = parts
        row.backend = request.member.agent_backend
        row.captured_at = utcnow()
        session.add(row)


def _snapshot_sources(snapshot: SessionContext) -> list[dict]:
    parts = []
    for index, item in enumerate(snapshot.sources):
        origin = item.get("origin") or f"studio.snapshot.sources[{index}].text"
        category = item["category"]
        # Previous snapshots stored required materials as separate entries. Keep
        # those boundaries; reconstructing the earlier request would be guesswork.
        if category in {"system", "task"}:
            kind = "snapshot"
            if origin.startswith("studio.request["):
                kind = "request_system" if category == "system" else "request_input"
            parts.extend(prompt_sources(item["text"], category, origin=origin,
                                        instructions=category == "system", source_kind=kind))
        else:
            label = item.get("label")
            required = {"skills": "required_skill", "documents": "required_document"}
            kind = required.get(category, "snapshot") if label else "snapshot"
            parts.append({**item, "origin": origin, "source_kind": kind,
                          "source_detail": bounded_detail(label or category)})
    return parts


def retained_context(snapshot: SessionContext | None, messages: Sequence, journal: JournalContext | None = None,
                     *, backend: str = "") -> tuple[list[dict], dict]:
    """Use one evidence source per view. A journal is never amended from the Studio timeline."""
    summary_kinds = {"compressed summary", "context_summary", "producer_compacted"}
    summary = next((m for m in reversed(messages) if m.kind in summary_kinds
                    and not m.data.get("parent_tool_use_id")), None)
    if snapshot and summary and summary.created_at > snapshot.captured_at:
        snapshot = journal = None  # A manual compaction invalidated the captured session.
    captured_at = snapshot.captured_at.isoformat() if snapshot else None
    if journal is not None:
        return journal.sources, {"reported": journal.reported_tokens, "window": journal.context_window,
                                 "origin": f"{backend}_journal", "journal_path": journal.journal_path, "partial": journal.partial,
                                 "has_studio_view": snapshot is not None}

    cutoff = snapshot.started_at if snapshot and not snapshot.partial else summary.created_at if summary else None
    parts = _snapshot_sources(snapshot) if snapshot else []
    reported = window = None
    for message in reversed(messages):
        if cutoff and message.created_at < cutoff:
            break
        if message.data.get("parent_tool_use_id"):
            continue
        if message.kind in summary_kinds:
            break
        if message.kind == "system" and message.text == "usage_update":
            reported = count(message.data.get("context_tokens"))
            window = count(message.data.get("context_window"))
            break
        if message.role == "assistant" and message.kind != "result":
            usage = message.data.get("usage")
            if isinstance(usage, dict):
                counts = [count(usage.get(key)) for key in
                          ("input_tokens", "cache_creation_input_tokens", "cache_read_input_tokens")]
                if any(count is not None for count in counts):
                    reported = sum(count or 0 for count in counts)
                    break
    for message in messages:
        if (cutoff and message.created_at < cutoff) or message.data.get("parent_tool_use_id"):
            continue
        category = None
        detail = name = ""
        if message.kind in summary_kinds:
            # On a fresh dispatch this summary is already embedded in request.prompt.
            if not snapshot:
                category, kind, detail = "summary", "compaction", message.kind
        elif message.kind in {"tool_use", "tool_result"}:
            category = "tools"
            kind = "tool_call" if message.kind == "tool_use" else "tool_result"
            name = message.data.get("name") or ""
            identifier = name or (message.text if message.kind == "tool_use" else message.data.get("tool_use_id"))
            identifier = identifier or message.data.get("id")
            if isinstance(identifier, str):
                detail = identifier
        elif message.kind == "thinking":
            category = kind = "thinking"
        elif message.kind == "text" and message.role == "assistant":
            category, kind, detail = "history", "assistant_message", message.member_id
        elif (message.kind == "text" and message.role in {"user", "human"}
              and (not snapshot or snapshot.partial and message.created_at < snapshot.started_at)):
            # Messages before the first captured resume are historical evidence,
            # not additional fields of the captured dispatch.
            category = "task" if message.role == "user" and snapshot is None else "history"
            kind = "user_message"
            if message.role == "human":
                detail = message.member_id
        elif not snapshot and message.kind in {"note", "review", "summary"}:
            category, kind = "history", "transcript"
            detail = f"{message.role} · {message.kind}"
        if category:
            locator = f"studio.messages[{message.id}]"
            if message.kind == "tool_use" and "input" in message.data:
                # The label is metadata, not a prefix of the tool's arguments.
                value = message.data["input"]
                is_text = isinstance(value, str)
                parts.append(source(category, value if is_text else json.dumps(value, ensure_ascii=False),
                                    name or message.text, origin=locator + ".data.input",
                                    representation="text" if is_text else "json",
                                    source_kind=kind, source_detail=detail))
            else:
                parts.append(source(category, message.text, name, origin=locator + ".text",
                                    source_kind=kind, source_detail=detail))
    return parts, {"reported": reported, "window": window, "origin": "studio",
                   "partial": snapshot is None or snapshot.partial,
                   "captured_at": captured_at, "has_studio_view": snapshot is not None}


def inventory(snapshot: SessionContext | None, messages: list, journal=None, *, backend: str = "") -> dict[str, Any]:
    parts, metrics = retained_context(snapshot, messages, journal, backend=backend)
    return present(parts, **metrics)


def fragment_key(part: dict) -> str:
    """Guard a deferred read against a different record occupying the same position."""
    digest = hashlib.sha256()
    for key in ("origin", "category", "label", "representation", "provided_by",
                "source_kind", "source_detail", "text"):
        value = part.get(key, "").encode("utf-8", errors="surrogatepass")
        digest.update(len(value).to_bytes(8, "big"))
        digest.update(value)
    return digest.hexdigest()


def present(parts: list[dict], reported: int | None, window: int | None, *,
            origin: str, partial: bool, captured_at: str | None = None, basis: str = "estimated",
            has_studio_view: bool = False, journal_path: str | None = None) -> dict[str, Any]:
    estimated = sum(part["tokens"] for part in parts)
    # Size the blocks from the complete text, but keep opening a long-running session
    # cheap for the browser. The preview budget never changes its token estimate.
    budget = 512_000
    previews = []
    for index, part in enumerate(parts):
        text = part["text"]
        # The explicit live inspection is a one-time report; keep its supplied
        # metadata readable without repeating a runtime call when a tile is opened.
        if basis != "runtime":
            text = text[:min(32_000, budget)]
            budget -= len(text)
        shortened = len(text) < len(part["text"])
        preview = {**part, "id": str(index), "text": text, "truncated": shortened}
        if shortened:
            preview["text_key"] = fragment_key(part)
        previews.append(preview)
    # Accounting is a display row, never appended to the cached source inventory.
    if reported is not None and reported > estimated:
        previews.append({"category": "unattributed", "label": "", "text": "", "tokens": reported - estimated,
                         "id": "unattributed", "truncated": False})
    return {
        "sources": previews, "has_studio_view": has_studio_view,
        **({"journal_path": journal_path} if journal_path is not None else {}),
        "reported_tokens": reported, "context_window": window or None,
        "estimated_tokens": estimated,
        "captured_at": captured_at, "partial": partial, "origin": origin, "basis": basis,
    }


def _native_fragments(category: str, item: dict, index: int, details: dict) -> list[dict]:
    """Only break down a runtime count if its loaded records account for it exactly."""
    def metadata(row, label, tokens, path, kind, detail, *, builtin=False):
        return {**source(category, json.dumps(row, ensure_ascii=False, indent=2), label,
                         origin="runtime." + path, representation="metadata",
                         source_kind=kind, source_detail=detail,
                         provided_by="claude_code" if builtin else None), "tokens": tokens}

    tokens = item["tokens"]
    if len(details) == 1:
        key, records = next(iter(details.items()))
        if isinstance(records, dict):
            return [metadata(records, item["name"], tokens, key, "sdk_entry", item["name"])]
        if isinstance(records, list) and all(isinstance(row, dict) for row in records):
            loaded = [(i, row) for i, row in enumerate(records)
                      if row.get("isLoaded") is not False and not row.get("isDeferred")]
            counts = [count(row.get("tokens")) for _, row in loaded]
            if None not in counts and sum(counts) == tokens:
                fragments = []
                for i, row in loaded:
                    label = row.get("name") or row.get("path") or row.get("agentType") or ""
                    builtin = key == "systemTools" or (key == "agents" and row.get("source") == "built-in")
                    # /context exposes different identifiers for a tool, agent and file (§10.4).
                    values = [item["name"], row.get("serverName"), row.get("source"), row.get("type"),
                              row.get("path"), row.get("agentType"), row.get("name")]
                    detail = " · ".join(dict.fromkeys(v for v in values if isinstance(v, str) and v))
                    fragments.append(metadata(row, label, row["tokens"], f"{key}[{i}]", "sdk_entry", detail,
                                              builtin=builtin))
                return fragments
    # No defensible per-record counts. The category itself is one original SDK
    # record: don't manufacture a new document out of several detail collections.
    return [metadata(item, item["name"], tokens, f"categories[{index}]", "sdk_category", item["name"],
                     builtin=category == "tool_definitions" and item.get("id") == "system_tools")]


def native_inventory(report: dict) -> dict[str, Any]:
    """Claude's /context categories are authoritative; do not add our estimates again."""
    total = count(report.get("totalTokens"))
    if total is None or not isinstance(report.get("categories"), list):
        raise ValueError("The runtime returned no context breakdown")
    parts = []
    for index, item in enumerate(report["categories"]):
        if not isinstance(item, dict) or not isinstance(item.get("name"), str):
            continue
        name = item["name"]
        key = re.sub(r"[^a-z]", "", name.lower())
        tokens = count(item.get("tokens"))
        if not tokens or item.get("isDeferred") or any(s in key for s in ("freespace", "autocompact", "buffer", "available")):
            continue
        category = "runtime"
        detail_keys: tuple[str, ...] = ()
        if "systemprompt" in key:
            category, detail_keys = "system", ("systemPromptSections",)
        elif "tool" in key:
            category = "tool_definitions"
            if "mcp" in key:
                detail_keys = ("mcpTools",)
            elif item.get("id", "system_tools") == "system_tools" and key == "systemtools":
                detail_keys = ("systemTools",)
        elif "memor" in key:
            category, detail_keys = "memory", ("memoryFiles",)
        elif "skill" in key or "command" in key:
            category, detail_keys = "skills", ("skills", "slashCommands")
        elif "agent" in key:
            category, detail_keys = "agents", ("agents",)
        elif "message" in key or "conversation" in key:
            category, detail_keys = "history", ("messageBreakdown",)
        details = {key: report[key] for key in detail_keys if report.get(key)}
        parts.extend(_native_fragments(category, item, index, details))
    if sum(part["tokens"] for part in parts) > total:
        raise ValueError("The runtime context categories exceed its reported total")
    return present(parts, total, count(report.get("rawMaxTokens")) or count(report.get("maxTokens")),
                   origin="claude_code_runtime", partial=False, captured_at=utcnow().isoformat(), basis="runtime")


_QWEN_ROW = re.compile(r"(?P<indent>\s*(?:└ )?)(?P<label>\S.*?)\s+(?P<size>\d+(?:\.\d+)?[km]?) tokens \(\d+(?:\.\d+)?%\)")
_QWEN_CATEGORIES = {
    "system prompt": "system", "built-in tools": "tool_definitions", "mcp tools": "tool_definitions",
    "instruction files": "memory", "memory files": "memory", "auto memory": "memory",
    "skills": "skills", "startup context": "runtime", "messages": "history",
}
# Totals and capacity, plus the remainder Qwen 0.26 printed: Studio derives its own from "Used".
_QWEN_NOT_SOURCES = {"used", "free", "unattributed"}


def _qwen_size(text: str) -> tuple[int, int]:
    """A printed count and how far rounding to one decimal may have moved it."""
    scale = {"k": 1_000, "m": 1_000_000}.get(text[-1], 1)
    return round(float(text.rstrip("km")) * scale), scale // 20


def qwen_inventory(report: str) -> dict[str, Any]:
    """Qwen's `/context detail` text, as printed in headless mode (§10.4).

    The CLI prints rounded counts (`12.3k`), so a category is only split into its rows
    when they fit inside it within that rounding. Nothing here is re-estimated by Studio.
    """
    categories: list[tuple[int, str, str, int, int]] = []
    details: dict[str, list[tuple[int, str, str, int, int]]] = {}
    section = ""
    owner = ""
    for index, line in enumerate(report.splitlines()):
        heading = re.fullmatch(r"\*\*(.+)\*\*", line.strip())
        if heading:
            section = heading[1].lower()
            continue
        row = _QWEN_ROW.fullmatch(line)
        if not row:
            continue
        label = row["label"]
        tokens, slack = _qwen_size(row["size"])
        if "└" not in row["indent"]:
            categories.append((index, line.strip(), label, tokens, slack))
        elif section in _QWEN_CATEGORIES:
            if len(row["indent"]) > 4:  # "body loaded" under the skill that owns it
                label = f"{owner} · {label}"
            else:
                owner = label
            details.setdefault(section, []).append((index, line.strip(), label, tokens, slack))

    def fragment(category, row, kind, group):
        index, text, label, tokens, _ = row
        return {**source(category, text, label, origin=f"runtime.context[{index}]",
                         representation="metadata", source_kind=kind,
                         source_detail=label if kind == "sdk_category" else f"{group} · {label}",
                         provided_by="qwen" if group.lower() == "built-in tools" else None),
                "tokens": tokens}

    parts = []
    reported = None
    for row in categories:
        _, _, name, tokens, slack = row
        key = name.lower()
        if key == "used":
            reported = tokens
        if key in _QWEN_NOT_SOURCES or not tokens:
            continue
        category = _QWEN_CATEGORIES.get(key, "runtime")
        rows = details.get(key, [])
        listed = sum(item[3] for item in rows)
        slack += sum(item[4] for item in rows)
        if not rows or listed > tokens + slack:
            parts.append(fragment(category, row, "sdk_category", name))
            continue
        parts.extend(fragment(category, item, "sdk_entry", name) for item in rows if item[3])
        if tokens - listed > slack:
            # What the category holds besides its rows, e.g. the skill tool's own schema.
            parts.append(fragment(category, (*row[:3], tokens - listed, 0), "sdk_category", name))
    if not parts:
        raise ValueError("Qwen Code returned no context breakdown")

    window = None
    exact = re.search(r"Effective window:\s+([\d,]+)\s+\(window − ([\d,]+) reserve\)", report)
    printed = re.search(r"Context window: (\d+(?:\.\d+)?[km]?) tokens", report)
    if exact:
        window = sum(int(group.replace(",", "")) for group in exact.groups())
    elif printed:
        window = _qwen_size(printed[1])[0]
    return present(parts, reported, window, origin="qwen_runtime", partial=False,
                   captured_at=utcnow().isoformat(), basis="runtime")
