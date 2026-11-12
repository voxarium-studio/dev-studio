"""Qwen Code headless CLI backend (§10.6), protocol checked against 0.26.3.

Each task/chat turn is a subprocess. Auth and provider configuration belong to Qwen;
Studio supplies the role, prompt, permissions and its existing MCP bridge per run.
"""

from __future__ import annotations

import asyncio
import json
import os
import re
import shutil
import signal
import subprocess
import tempfile
import time
from collections import deque
from collections.abc import AsyncIterator
from contextlib import aclosing
from dataclasses import replace
from pathlib import Path
from typing import Any

from ..changes import edit_stat
from .base import (
    AgentBackend,
    AgentEvent,
    AgentRunRequest,
    register_backend,
    register_context_inspector,
)
from .cli_stream import _Oversized, _drain, _frames
from .mcp_bridge import bridge_servers
from .recovery import recovery_prompt

_FOREIGN_MODEL = re.compile(r"^(claude-|opus|sonnet|haiku|fable|gpt-|o[134](?:-|$))", re.I)
_MODES = {
    "plan": "plan", "default": "default", "dontAsk": "default",
    "acceptEdits": "auto-edit", "auto": "auto", "bypassPermissions": "yolo",
}
_TOOLS = {
    "read_file": "Read", "write_file": "Write", "edit": "Edit",
    "run_shell_command": "Bash", "grep_search": "Grep", "glob": "Glob",
    "web_search": "WebSearch", "web_fetch": "WebFetch", "todo_write": "TodoWrite",
    "agent": "Task",
}
_TOKEN_FIELDS = (
    "input_tokens", "output_tokens", "cache_read_input_tokens", "cache_creation_input_tokens",
)


def _normalise_usage(value: Any) -> dict[str, int]:
    """Qwen's input includes cache reads; Studio accounts for them separately (§10.6)."""
    if not isinstance(value, dict):
        return {}
    usage = {
        key: value[key] for key in _TOKEN_FIELDS
        if isinstance(value.get(key), int) and not isinstance(value[key], bool) and value[key] >= 0
    }
    if "input_tokens" in usage:
        usage["input_tokens"] = max(
            0, usage["input_tokens"] - usage.get("cache_read_input_tokens", 0)
        )
    return usage


def _launcher() -> list[str] | None:
    found = shutil.which("qwen")
    if not found:
        return None
    path = Path(found)
    if path.suffix.lower() in {".cmd", ".bat", ".ps1"}:
        # Avoid cmd.exe reinterpreting quotes, Unicode and shell metacharacters.
        package = path.parent / "node_modules" / "@qwen-code" / "qwen-code"
        try:
            metadata = json.loads((package / "package.json").read_text(encoding="utf-8"))
            entry = metadata["bin"]
            entry = entry["qwen"] if isinstance(entry, dict) else entry
            script = (package / entry).resolve()
        except (OSError, ValueError, KeyError, TypeError):
            return None
        node = shutil.which("node")
        if node and script.is_file() and script.is_relative_to(package.resolve()):
            return [node, str(script)]
        return None
    return [str(path)]


#: Models for the member editor's picker (§10.6), mirroring Qwen Code's own catalogue
#: (its models.dev projection): every chat-capable Qwen family model, newest generation
#: first within each group. Speciality endpoints an agent member cannot drive — math,
#: doc, character and realtime models — are left out. Not a whitelist, exactly like
#: `SUGGESTED_MODELS` in `api/teams.py`: `member.model` reaches `qwen --model` verbatim,
#: so a release newer than this list stays usable by typing it in, and keeping the list
#: current is cosmetic rather than load-bearing.
_SUGGESTED_MODELS: list[dict[str, str]] = [
    # An empty model selects Qwen's configured provider/model, without pinning a release.
    {"id": "", "label": "Qwen Code default", "group": "Qwen Code",
     "note": "Uses Qwen Code's configured model"},
    {"id": "qwen3-coder-plus", "label": "Qwen3 Coder Plus", "group": "Qwen Code",
     "note": "Flagship coding model; 1M context"},
    {"id": "qwen3-coder-flash", "label": "Qwen3 Coder Flash", "group": "Qwen Code",
     "note": "Fast and cheap coding; 1M context"},
    {"id": "qwen3-coder-480b-a35b-instruct", "label": "Qwen3 Coder 480B (open-weight)",
     "group": "Qwen Code", "note": "256K context"},
    {"id": "qwen3-coder-30b-a3b-instruct", "label": "Qwen3 Coder 30B (open-weight)",
     "group": "Qwen Code", "note": "256K context"},

    {"id": "qwen3.8-max", "label": "Qwen3.8 Max", "group": "Qwen",
     "note": "Newest flagship; 1M context, image/PDF/video"},
    {"id": "qwen3.8-flash", "label": "Qwen3.8 Flash", "group": "Qwen",
     "note": "1M context, image/video"},
    {"id": "qwen3.7-plus", "label": "Qwen3.7 Plus", "group": "Qwen",
     "note": "Image/video"},
    {"id": "qwen3.7-flash", "label": "Qwen3.7 Flash", "group": "Qwen",
     "note": "1M context, image/video"},
    {"id": "qwen3.6-plus", "label": "Qwen3.6 Plus", "group": "Qwen",
     "note": "1M context, image/video"},
    {"id": "qwen3.6-flash", "label": "Qwen3.6 Flash", "group": "Qwen",
     "note": "1M context, image/video"},
    {"id": "qwen3.5-plus", "label": "Qwen3.5 Plus", "group": "Qwen",
     "note": "1M context, image/video"},
    {"id": "qwen3.5-flash", "label": "Qwen3.5 Flash", "group": "Qwen",
     "note": "1M context, image/video"},
    {"id": "qwen3-max", "label": "Qwen3 Max", "group": "Qwen", "note": "256K context"},
    {"id": "qwen-plus", "label": "Qwen Plus", "group": "Qwen",
     "note": "Rolling alias; follows the current Plus release"},
    {"id": "qwen-flash", "label": "Qwen Flash", "group": "Qwen",
     "note": "Rolling alias; follows the current Flash release"},
    {"id": "qwen-turbo", "label": "Qwen Turbo", "group": "Qwen",
     "note": "Cheapest hosted tier; 1M context"},

    {"id": "qwen3.5-397b-a17b", "label": "Qwen3.5 397B-A17B", "group": "Qwen Open-weight",
     "note": "Largest open-weight; image/audio/video"},
    {"id": "qwen3.5-122b-a10b", "label": "Qwen3.5 122B-A10B", "group": "Qwen Open-weight",
     "note": "Image/audio/video"},
    {"id": "qwen3.6-35b-a3b", "label": "Qwen3.6 35B-A3B", "group": "Qwen Open-weight",
     "note": "Image/audio/video"},
    {"id": "qwen3.6-27b", "label": "Qwen3.6 27B", "group": "Qwen Open-weight",
     "note": "Dense; image/audio/video"},
    {"id": "qwen3.5-35b-a3b", "label": "Qwen3.5 35B-A3B", "group": "Qwen Open-weight",
     "note": "Image/audio/video"},
    {"id": "qwen3.5-27b", "label": "Qwen3.5 27B", "group": "Qwen Open-weight",
     "note": "Dense; image/audio/video"},
    {"id": "qwen3-235b-a22b", "label": "Qwen3 235B-A22B", "group": "Qwen Open-weight",
     "note": ""},
    {"id": "qwen3-32b", "label": "Qwen3 32B", "group": "Qwen Open-weight", "note": ""},
    {"id": "qwen3-14b", "label": "Qwen3 14B", "group": "Qwen Open-weight", "note": ""},
    {"id": "qwen3-8b", "label": "Qwen3 8B", "group": "Qwen Open-weight", "note": ""},
    {"id": "qwen3-next-80b-a3b-thinking", "label": "Qwen3-Next 80B Thinking",
     "group": "Qwen Open-weight", "note": "Reasoning variant"},
    {"id": "qwen3-next-80b-a3b-instruct", "label": "Qwen3-Next 80B Instruct",
     "group": "Qwen Open-weight", "note": ""},

    {"id": "qwen3.8-omni-flash", "label": "Qwen3.8 Omni Flash",
     "group": "Qwen Vision & Omni", "note": "Image/audio/video; 1M context"},
    {"id": "qwen3-omni-flash", "label": "Qwen3 Omni Flash", "group": "Qwen Vision & Omni",
     "note": "Image/audio/video"},
    {"id": "qwen3-vl-plus", "label": "Qwen3 VL Plus", "group": "Qwen Vision & Omni",
     "note": "Vision; 256K context"},
    {"id": "qwen3-vl-235b-a22b", "label": "Qwen3 VL 235B-A22B",
     "group": "Qwen Vision & Omni", "note": "Vision, open-weight"},
    {"id": "qwen3-vl-30b-a3b", "label": "Qwen3 VL 30B-A3B", "group": "Qwen Vision & Omni",
     "note": "Vision, open-weight"},
    {"id": "qwen-vl-max", "label": "Qwen VL Max", "group": "Qwen Vision & Omni",
     "note": "Vision"},
    {"id": "qwen-vl-plus", "label": "Qwen VL Plus", "group": "Qwen Vision & Omni",
     "note": "Vision"},
    {"id": "qvq-max", "label": "QVQ Max", "group": "Qwen Vision & Omni",
     "note": "Visual reasoning"},

    {"id": "qwq-plus", "label": "QwQ Plus", "group": "Qwen Reasoning",
     "note": "Reasoning model"},
    {"id": "qwq-32b", "label": "QwQ 32B", "group": "Qwen Reasoning",
     "note": "Reasoning, open-weight"},

    {"id": "qwen-long", "label": "Qwen Long", "group": "Qwen Special",
     "note": "10M-context document analysis"},
    {"id": "qwen-deep-research", "label": "Qwen Deep Research", "group": "Qwen Special",
     "note": "Autonomous research agent"},

    {"id": "qwen2.5-72b-instruct", "label": "Qwen2.5 72B Instruct", "group": "Qwen 2.5",
     "note": "Legacy"},
    {"id": "qwen2.5-32b-instruct", "label": "Qwen2.5 32B Instruct", "group": "Qwen 2.5",
     "note": "Legacy"},
    {"id": "qwen2.5-14b-instruct", "label": "Qwen2.5 14B Instruct", "group": "Qwen 2.5",
     "note": "Legacy"},
    {"id": "qwen2.5-7b-instruct", "label": "Qwen2.5 7B Instruct", "group": "Qwen 2.5",
     "note": "Legacy"},
    {"id": "qwen2.5-coder-32b-instruct", "label": "Qwen2.5 Coder 32B Instruct",
     "group": "Qwen 2.5", "note": "Legacy coder"},
    {"id": "qwen2.5-coder-7b-instruct", "label": "Qwen2.5 Coder 7B Instruct",
     "group": "Qwen 2.5", "note": "Legacy coder"},
    {"id": "qwen2.5-vl-72b-instruct", "label": "Qwen2.5 VL 72B Instruct",
     "group": "Qwen 2.5", "note": "Legacy vision"},
    {"id": "qwen2.5-vl-7b-instruct", "label": "Qwen2.5 VL 7B Instruct",
     "group": "Qwen 2.5", "note": "Legacy vision"},
    {"id": "qwen2.5-omni-7b", "label": "Qwen2.5 Omni 7B", "group": "Qwen 2.5",
     "note": "Legacy; image/audio/video"},
]


def suggested_models() -> list[dict[str, str]]:
    return [dict(entry, backend="qwen") for entry in _SUGGESTED_MODELS]


def _command(launcher: list[str], request: AgentRunRequest, config: Path
             ) -> tuple[list[str], str, list[AgentEvent]]:
    argv = [*launcher, "--output-format", "stream-json", "--input-format", "text",
            "--chat-recording", "--approval-mode",
            _MODES.get(request.permission_mode, "default"), "--mcp-config", str(config)]
    if request.strict_mcp_config:
        # An empty array explicitly disables all implicit servers; same-name configs
        # supplied by --mcp-config take precedence over Qwen's discovered configs.
        argv += ["--allowed-mcp-server-names", *request.mcp_servers.keys()]
    if request.resume_session_id:
        argv += ["--resume", request.resume_session_id]
    for root in request.writable_roots:
        argv += ["--include-directories", str(root.resolve())]
    allowed = _allowed_tools([*request.member.allowed_tools, *request.extra_allowed_tools])
    if allowed and request.permission_mode != "plan":
        argv += ["--allowed-tools", *allowed]
    notes = []
    model = request.model.strip()
    if model and _FOREIGN_MODEL.match(model):
        notes.append(AgentEvent(kind="system", text=f"The model {model} belongs to another "
                               "backend; using Qwen Code's configured model.",
                               data={"subtype": "model_fallback", "model": model}))
    elif model:
        argv += ["--model", model]
    if request.effort or request.max_turns:
        notes.append(AgentEvent(kind="system", text="Qwen Code uses its own reasoning and "
                               "turn limits; Studio effort and Max turns do not apply.",
                               data={"subtype": "unsupported_settings"}))
    prompt = request.prompt
    if request.images:
        prompt += "\n\nAttached image files:\n" + "\n".join(
            str(image["path"]) for image in request.images
        )
    if request.system_prompt:
        # Full replacement, not an append: the prompt the Studio assembles —
        # Responsibility, identity, run context — is the session's whole system
        # prompt, and Qwen Code's default one is not sent alongside it.
        candidate = [*argv, "--system-prompt", request.system_prompt]
        if len(subprocess.list2cmdline(candidate).encode("utf-16-le")) // 2 < 30_000:
            argv = candidate
        else:
            prompt = f"Studio instructions:\n{request.system_prompt}\n\nUser message:\n{prompt}"
            notes.append(AgentEvent(kind="system", text="The Studio instructions exceed "
                                   "the command-line limit and were included in the prompt.",
                                   data={"subtype": "system_prompt_in_message"}))
    return argv, prompt, notes


def _allowed_tools(names: list[str]) -> list[str]:
    reverse = {value: key for key, value in _TOOLS.items()}
    result = []
    for name in names:
        tool, separator, rule = name.partition("(")
        result.append(reverse.get(tool, tool) + separator + rule)
    return result


class QwenBackend(AgentBackend):
    name = "qwen"
    named_agents = False
    session_recovery = True
    # Qwen Code ignores Studio effort, so a task left on "Auto" is not sent to the
    # Effort Solver on this backend (§10.3, §10.11).
    supports_effort = False

    def is_available(self) -> tuple[bool, str]:
        if _launcher() is None:
            return False, ("The `qwen` CLI was not found. Install it with "
                           "`npm install -g @qwen-code/qwen-code`, then run `qwen` "
                           "once to configure authentication.")
        # Qwen supports OAuth, several API providers and project .env files. Only its
        # runtime can validate those without falsely rejecting a configured provider.
        return True, "Qwen Code installed; authentication is checked when a run starts."

    async def run(self, request: AgentRunRequest) -> AsyncIterator[AgentEvent]:
        available, reason = self.is_available()
        if not available:
            yield AgentEvent(kind="error", text=reason, is_final=True)
            return
        inspector = None
        if request.session_key and not request.text_only:
            inspector = _ContextInspector(request)
            register_context_inspector(request.session_key, inspector)
        try:
            async with aclosing(self._run(request)) as events:
                async for event in events:
                    if inspector is not None and event.session_id:
                        inspector.session_id = event.session_id
                    yield event
        finally:
            if inspector is not None:
                inspector.running = False

    async def _run(self, request: AgentRunRequest) -> AsyncIterator[AgentEvent]:
        try:
            async with bridge_servers(request.mcp_servers, request.max_message_bytes) as servers:
                current = replace(request, mcp_servers=servers)
                for attempt in range(2):
                    pending: list[AgentEvent] = []
                    retryable = bool(current.resume_session_id)
                    async for event in self._run_once(current):
                        if event.kind in {"text", "thinking", "tool_use", "tool_result"}:
                            retryable = False
                            for previous in pending:
                                yield previous
                            pending.clear()
                        if retryable and event.kind in {"error", "result"}:
                            pending.append(event)
                        else:
                            yield event
                    missing = any(
                        any(marker in event.text for marker in (
                            f"No saved session found with ID {current.resume_session_id}.",
                            f'No saved session found with title "{current.resume_session_id}".',
                        ))
                        for event in pending if event.kind == "error"
                    )
                    if not attempt and retryable and missing:
                        yield AgentEvent(kind="system", text="The saved Qwen session is "
                                         "unavailable. Starting a new session with saved context.",
                                         data={"subtype": "session_recovered",
                                               "previous_session_id": current.resume_session_id})
                        current = replace(current, resume_session_id=None,
                                          prompt=request.recovery_prompt or recovery_prompt(
                                              request.prompt, [], request.task))
                        continue
                    for event in pending:
                        yield event
                    break
        except asyncio.CancelledError:
            raise
        except Exception as exc:
            yield AgentEvent(kind="error", text=f"{type(exc).__name__}: {exc}", is_final=True)

    async def _run_once(self, request: AgentRunRequest) -> AsyncIterator[AgentEvent]:
        state = _RunState(request.resume_session_id)
        started = time.monotonic()
        process = None
        stderr_task = None
        writer_task = None
        tail: deque[str] = deque(maxlen=4000)
        # Ephemeral config avoids command-line limits and does not modify the user's
        # .qwen/settings.json or managed project's .mcp.json (§10.6, §10.7).
        with tempfile.TemporaryDirectory(prefix="voxarium_studio-qwen-") as temporary:
            config = Path(temporary) / "mcp.json"
            config.write_text(json.dumps({"mcpServers": _mcp_servers(request.mcp_servers)}),
                              encoding="utf-8")
            argv, prompt, notes = _command(_launcher() or [], request, config)
            for note in notes:
                yield note
            env = dict(os.environ)
            env.pop("CLAUDECODE", None)
            env.setdefault("PYTHONIOENCODING", "utf-8")
            try:
                process = await asyncio.create_subprocess_exec(
                    *argv, cwd=str(request.workdir), env=env,
                    stdin=asyncio.subprocess.PIPE, stdout=asyncio.subprocess.PIPE,
                    stderr=asyncio.subprocess.PIPE, limit=max(65536, request.max_message_bytes),
                    start_new_session=os.name != "nt",
                )
                assert process.stdin and process.stdout and process.stderr
                stderr_task = asyncio.create_task(_drain(process.stderr, tail))
                # Read stdout while feeding long prompts so neither pipe can deadlock.
                writer_task = asyncio.create_task(_write_prompt(process.stdin, prompt))
                async for frame in _frames(process.stdout, request.max_message_bytes):
                    if isinstance(frame, _Oversized):
                        yield AgentEvent(kind="system", text=f"Skipped a {frame.size}-byte "
                                         "Qwen message exceeding teams.max_message_bytes.",
                                         data={"subtype": "oversized_line", "bytes": frame.size})
                    else:
                        for event in state.events(frame):
                            yield event
                exit_code = await process.wait()
                await stderr_task
                await writer_task
                if exit_code or state.result is None:
                    reason = (f"qwen exited with code {exit_code}" if exit_code else
                              "Qwen Code ended without a result event")
                    stderr = "".join(tail).strip()
                    state.errors.append(reason + (f": {stderr}" if stderr else ""))
                    yield AgentEvent(kind="error", text=state.errors[-1],
                                     session_id=state.session_id)
                yield state.finish(exit_code, round((time.monotonic() - started) * 1000))
            finally:
                if process is not None and process.returncode is None:
                    await _kill_tree(process)
                pending = [task for task in (stderr_task, writer_task) if task is not None]
                for task in pending:
                    if not task.done():
                        task.cancel()
                await asyncio.gather(*pending, return_exceptions=True)


#: The subcommand is tried as the user would type it. Up to at least 0.27 its headless
#: action discards the report and only acknowledges, so the flag form follows (§10.4).
_CONTEXT_COMMANDS = ("/context detail", "/context -d")
_CONTEXT_HEADING = "## Context Usage"


class _ContextInspector:
    """Qwen's own `/context` for a session between turns (§10.4).

    Qwen keeps no process to ask, so the saved session is reopened with the settings of
    its last turn — model, system prompt, tools and MCP servers are what the report
    measures. A slash command is answered by the CLI itself: no model request is made.
    """

    def __init__(self, request: AgentRunRequest) -> None:
        # Settings only: the turn's own text and attachments are not kept alive here.
        self._request = replace(request, prompt="", recovery_prompt="", images=[], inbox=None)
        self.session_id = request.resume_session_id
        self.running = True
        self._lock = asyncio.Lock()

    @property
    def ready(self) -> bool:
        return not self.running and bool(self.session_id)

    async def context_report(self) -> str:
        if self.running:
            raise RuntimeError("Wait for the current Qwen Code turn to finish")
        if not self.session_id:
            raise RuntimeError("The Qwen Code session was never started")
        if self._lock.locked():
            raise RuntimeError("Context inspection is already running")
        async with self._lock:
            base = self._request
            async with bridge_servers(base.mcp_servers, base.max_message_bytes) as servers:
                for command in _CONTEXT_COMMANDS:
                    report = await self._ask(replace(
                        base, mcp_servers=servers, prompt=command,
                        resume_session_id=self.session_id))
                    if _CONTEXT_HEADING in report:
                        return report
        raise RuntimeError("Qwen Code returned no /context report in headless mode")

    async def _ask(self, request: AgentRunRequest) -> str:
        if _command([], request, Path("mcp.json"))[1] != request.prompt:
            # A role too long for the command line travelled inside each message, so the
            # session ran on Qwen's own system prompt. Wrapped here, the command would
            # stop being one and reach the model as text.
            request = replace(request, system_prompt="")
        text = ""
        errors: list[str] = []
        async with aclosing(QwenBackend()._run_once(request)) as events:
            async for event in events:
                if event.kind == "text" and not event.data.get("parent_tool_use_id"):
                    text = event.text
                elif event.kind == "error":
                    errors.append(event.text)
                elif event.kind == "result" and event.data.get("num_turns"):
                    raise RuntimeError("This Qwen Code version does not answer /context in "
                                       "headless mode: the command was sent to the model")
        if errors and _CONTEXT_HEADING not in text:
            raise RuntimeError(errors[-1])
        return text


def _mcp_servers(servers: dict[str, Any]) -> dict[str, Any]:
    result = {}
    for name, server in servers.items():
        config = dict(server)
        kind = config.pop("type", None)
        if kind == "http" and "url" in config:
            config["httpUrl"] = config.pop("url")
        result[name] = config
    return result


async def _write_prompt(writer: asyncio.StreamWriter, prompt: str) -> None:
    try:
        writer.write(prompt.encode("utf-8"))
        await writer.drain()
    except (BrokenPipeError, ConnectionResetError):
        pass  # Early CLI failures are reported by stderr/the exit code.
    finally:
        writer.close()


async def _kill_tree(process: asyncio.subprocess.Process) -> None:
    if os.name == "nt":
        killer = await asyncio.create_subprocess_exec(
            "taskkill", "/PID", str(process.pid), "/T", "/F",
            stdout=asyncio.subprocess.DEVNULL, stderr=asyncio.subprocess.DEVNULL,
        )
        await killer.wait()
    else:
        try:
            os.killpg(process.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
    if process.returncode is None:
        try:
            process.kill()
        except ProcessLookupError:
            pass
    await process.wait()


def _text(value: Any) -> str:
    if isinstance(value, str):
        return value
    if isinstance(value, list):
        return "\n".join(_text(item) for item in value)
    if isinstance(value, dict):
        return _text(value.get("text", value.get("message", value.get("content", ""))))
    return str(value or "")


class _RunState:
    def __init__(self, session_id: str | None) -> None:
        self.session_id = session_id
        self.result: dict[str, Any] | None = None
        self.last_text = ""
        self.errors: list[str] = []

    def events(self, frame: dict[str, Any]) -> list[AgentEvent]:
        if frame.get("session_id"):
            self.session_id = str(frame["session_id"])
        kind = frame.get("type")
        events = []
        if kind == "system":
            # Named by its subtype, as Claude Code's frames are: `init` is what the Status
            # column reads MCP server states from, whichever runtime sent it (§10.4).
            events.append(AgentEvent(kind="system", text=str(frame.get("subtype") or "system"),
                                     data=frame))
        elif kind in {"assistant", "user"}:
            message = frame.get("message") or {}
            for block in message.get("content", []):
                if not isinstance(block, dict):
                    continue
                block_type = block.get("type")
                if block_type == "text" and kind == "assistant":
                    text = _text(block.get("text"))
                    if not frame.get("parent_tool_use_id"):
                        self.last_text = text
                    events.append(AgentEvent(kind="text", text=text))
                elif block_type == "thinking":
                    events.append(AgentEvent(kind="thinking", text=_text(block.get("thinking"))))
                elif block_type == "tool_use":
                    name = str(block.get("name", ""))
                    args = dict(block.get("input") or {})
                    if "file_path" not in args and "absolute_path" in args:
                        args["file_path"] = args["absolute_path"]
                    name = _TOOLS.get(name, name)
                    data = {"id": block.get("id"), "name": name,
                            "input": {key: (value if isinstance(value, str) else
                                            json.dumps(value, ensure_ascii=False))[:500]
                                      for key, value in args.items()}}
                    stat = edit_stat(name, args)
                    if stat is not None:
                        data["edit"] = stat
                    events.append(AgentEvent(kind="tool_use", text=name, data=data))
                elif block_type == "tool_result":
                    events.append(AgentEvent(kind="tool_result", text=_text(block.get("content"))[:2000],
                                             data={"tool_use_id": block.get("tool_use_id"),
                                                   "is_error": bool(block.get("is_error"))}))
            if kind == "assistant" and not frame.get("parent_tool_use_id"):
                # §10.4: one API response can yield several transcript entries. Attach
                # its accounting once so the shared live counter does not multiply it.
                # Subagent usage is reconciled by the root result, not counted again.
                usage = _normalise_usage(message.get("usage"))
                # Qwen starts each intermediate block with an all-zero placeholder;
                # it is not a new measurement of an empty context window.
                if any(usage.values()) and events:
                    events[0].data["usage"] = usage
                    if message.get("id"):
                        events[0].data["provider_response_id"] = str(message["id"])
        elif kind == "result" and not frame.get("parent_tool_use_id"):
            self.result = frame
            if frame.get("is_error"):
                self.errors.append(_text(frame.get("error")) or "Qwen Code reported an error")
                events.append(AgentEvent(kind="error", text=self.errors[-1]))
        elif kind == "error":
            self.errors.append(_text(frame.get("error", frame.get("message"))) or "Qwen Code error")
            events.append(AgentEvent(kind="error", text=self.errors[-1]))
        for event in events:
            event.session_id = self.session_id
            if frame.get("parent_tool_use_id"):
                event.data["parent_tool_use_id"] = frame["parent_tool_use_id"]
        return events

    def finish(self, exit_code: int, duration_ms: int) -> AgentEvent:
        result = self.result or {}
        usage = _normalise_usage(result.get("usage"))
        failed = bool(self.errors or result.get("is_error") or exit_code)
        return AgentEvent(kind="result", text=_text(result.get("result")) or self.last_text,
                          session_id=self.session_id, is_final=True, data={
                              "subtype": "error" if failed else "success", "is_error": failed,
                              "num_turns": result.get("num_turns", 0), "usage": usage,
                              "total_tokens": sum(usage.values()),
                              "total_cached_tokens": usage.get("cache_read_input_tokens", 0),
                              "total_uncached_tokens": sum(value for key, value in usage.items()
                                                           if key != "cache_read_input_tokens"),
                              "accounting_version": 2,
                              "total_cost_usd": 0.0, "cost_unavailable": True,
                              "duration_ms": duration_ms, "exit_code": exit_code,
                              "errors": self.errors,
                          })


register_backend("qwen", QwenBackend)
