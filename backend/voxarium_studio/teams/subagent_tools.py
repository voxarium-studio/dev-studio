"""Task-scoped controls for Studio-managed subagents (spec §10.3)."""

from __future__ import annotations

import json
import math
from typing import Any


def build_subagent_tools(
    orchestrator, *, member_id: str, task_id: str | None, run_id: str | None,
) -> list[Any]:
    """Bind authority to the current task; callers cannot choose another task's children."""
    from claude_agent_sdk import tool

    def text(body: str, *, error: bool = False) -> dict[str, Any]:
        result: dict[str, Any] = {"content": [{"type": "text", "text": body}]}
        if error:
            result["is_error"] = True
        return result

    def required(args: dict[str, Any], key: str) -> str:
        value = args.get(key)
        if not isinstance(value, str) or not value.strip():
            raise ValueError(f"{key} must be a non-empty string")
        return value.strip()

    def optional(args: dict[str, Any], key: str) -> str | None:
        if args.get(key) is None:
            return None
        return required(args, key)

    async def call(method: str, args: dict[str, Any]) -> dict[str, Any]:
        if task_id is None:
            return text("Subagents belong to a running task. This chat has no task to split.", error=True)
        try:
            service = orchestrator.subagents
            service.require_run(task_id, member_id, run_id)
            if method == "create":
                result = await service.create(
                    task_id, member_id,
                    prompt=required(args, "prompt"),
                    description=required(args, "description"),
                    backend=optional(args, "backend"),
                    model=optional(args, "model"),
                    # §10.11: no `effort` — Effort Solver computes it, and one sent by a
                    # caller that still remembers the old schema is not read.
                )
            elif method == "list":
                result = await service.list(task_id, member_id)
            elif method == "wait":
                timeout = args.get("timeout", 30)
                if (not isinstance(timeout, (int, float)) or isinstance(timeout, bool)
                        or not math.isfinite(timeout) or not 0 <= timeout <= 60):
                    raise ValueError("timeout must be a finite number between 0 and 60 seconds")
                result = await service.wait(
                    task_id, member_id, optional(args, "subagent_id"), timeout=timeout,
                )
            elif method == "send":
                result = await service.send(
                    task_id, member_id, required(args, "subagent_id"), required(args, "message"),
                )
            else:
                result = await getattr(service, method)(
                    task_id, member_id, required(args, "subagent_id"),
                )
            return text(json.dumps(result, ensure_ascii=False, default=str))
        except Exception as exc:
            return text(f"{type(exc).__name__}: {exc}", error=True)

    identity = {
        "subagent_id": {"type": "string", "description": "ID returned by spawn_subagent."},
    }

    @tool(
        "spawn_subagent",
        "Start a Studio-managed subagent for a bounded part of your current task, on any "
        "registered agent backend. It shares your working directory and permitted write "
        "scope. Give separate file ownership to parallel workers. Backend and model default "
        "to your run; a different backend requires an explicit compatible model. You do not "
        "choose its effort: Effort Solver scores the description and prompt and sets it, so "
        "make them complete. Returns its ID and chosen effort once that evaluation is done, "
        "without waiting for the work. Use wait_subagents before finish_task. "
        "Children cannot spawn children or change the task board.",
        {
            "type": "object",
            "properties": {
                "description": {"type": "string", "description": "Short label shown in the task UI."},
                "prompt": {"type": "string", "description": "Complete assignment, context and expected result."},
                "backend": {"type": "string", "description": "Optional registered backend, e.g. claude_code, codex, qwen."},
                "model": {"type": "string", "description": "Optional model; required when changing backend."},
            },
            "required": ["description", "prompt"],
        },
    )
    async def spawn_subagent(args: dict[str, Any]) -> dict[str, Any]:
        return await call("create", args)

    @tool(
        "list_subagents",
        "List your current task's Studio-managed subagents, including status, result, the "
        "effort chosen for each, token count and API cost. Their spend is also included in "
        "the parent task totals.",
        {"type": "object", "properties": {}},
    )
    async def list_subagents(args: dict[str, Any]) -> dict[str, Any]:
        return await call("list", args)

    @tool(
        "get_subagent",
        "Read one Studio-managed subagent's status, result, usage and recorded transcript "
        "within your current task.",
        {"type": "object", "properties": identity, "required": ["subagent_id"]},
    )
    async def get_subagent(args: dict[str, Any]) -> dict[str, Any]:
        return await call("get", args)

    @tool(
        "wait_subagents",
        "Wait up to timeout seconds for one subagent or all of your current task's subagents. "
        "Returns their current statuses and results; timeout does not stop them. This releases "
        "your agent slot while waiting so children can run even with a single parallel slot. "
        "Returning also waits to reacquire your slot, which may take longer than timeout. "
        "Use this instead of shell sleep or polling and inspect returned statuses before finishing.",
        {
            "type": "object",
            "properties": {
                **identity,
                "timeout": {"type": "number", "minimum": 0, "maximum": 60, "default": 30},
            },
        },
    )
    async def wait_subagents(args: dict[str, Any]) -> dict[str, Any]:
        return await call("wait", args)

    @tool(
        "stop_subagent",
        "Stop a queued or running Studio-managed subagent in your current task and preserve "
        "its transcript, files and recorded usage. Already finished subagents remain finished.",
        {"type": "object", "properties": identity, "required": ["subagent_id"]},
    )
    async def stop_subagent(args: dict[str, Any]) -> dict[str, Any]:
        return await call("stop", args)

    @tool(
        "send_subagent_message",
        "Start a follow-up turn for a completed, failed or stopped Studio-managed subagent, "
        "reusing its session when available. It cannot receive a message while running: wait "
        "for completion or stop it first. Returns immediately; use wait_subagents for the result.",
        {
            "type": "object",
            "properties": {**identity, "message": {"type": "string", "description": "Follow-up assignment."}},
            "required": ["subagent_id", "message"],
        },
    )
    async def send_subagent_message(args: dict[str, Any]) -> dict[str, Any]:
        return await call("send", args)

    return [spawn_subagent, list_subagents, get_subagent, wait_subagents, stop_subagent, send_subagent_message]
