"""Which agent CLI reads the documents for the graphify plugin, and how (§9.7).

graphify itself speaks to one subscription CLI only: ``--backend claude-cli`` runs
``claude -p``. Everything else it reaches is an HTTP API, and of those ``--backend openai``
takes any OpenAI-compatible server through ``OPENAI_BASE_URL``. So Codex and Qwen Code are
reached through a server the Studio runs itself, for the length of one documents pass:
:class:`DocumentsEndpoint` listens on ``127.0.0.1`` only, on a port the OS picks, admits
nothing without the pass's one-off bearer token (graphify sends it as ``OPENAI_API_KEY``),
and answers ``POST /v1/chat/completions`` by running the chosen CLI once, headless, with
graphify's own system prompt as the model's instructions and its chunk of documents as the
prompt. The installed graphify is not patched and no fake ``claude`` is put on PATH; the
one thing graphify needs for it is its documented ``openai`` extra.

Claude Code keeps graphify's native ``claude-cli`` backend: it is what the pass was built
and measured on, and it needs no server.

This module is a sibling of ``plugin.py`` and is imported by it while the loader has the
plugin root on ``sys.path`` (``plugins/loader.py``).
"""

from __future__ import annotations

import asyncio
import hmac
import json
import os
import re
import secrets
import shutil
import subprocess
import sys
import tempfile
import time
from collections.abc import Awaitable, Callable
from dataclasses import dataclass
from pathlib import Path
from typing import Any

CLAUDE_CODE = "claude_code"
CODEX = "codex"
QWEN = "qwen"


@dataclass(frozen=True, slots=True)
class Runtime:
    """One agent CLI the documents pass can read through."""

    id: str
    """The Studio's backend id (§10.6) — what the page's model picker names."""

    label: str
    command: str
    """The command looked for on PATH; named in the reason a pass is skipped."""

    hint: str
    """What to do when :attr:`command` is missing."""

    default_model: str
    """The model when the page leaves it empty. ``""`` is the CLI's own configured one."""

    native: bool
    """Whether graphify reaches it by itself (``claude-cli``) rather than through
    :class:`DocumentsEndpoint`."""

    nested_markers: tuple[str, ...]
    """Variables the CLI sets for whatever runs inside one of its sessions. A Studio
    started from such a session inherits them, and a CLI that sees its own marker takes
    itself for nested — ``claude`` refuses to start at all. The Studio is not that
    session, so every child of the documents pass goes without them."""


RUNTIMES: dict[str, Runtime] = {
    CLAUDE_CODE: Runtime(
        id=CLAUDE_CODE,
        label="Claude Code",
        command="claude",
        hint="install Claude Code and run `claude` once to sign in",
        default_model="haiku",
        native=True,
        nested_markers=("CLAUDECODE", "CLAUDE_CODE_ENTRYPOINT"),
    ),
    CODEX: Runtime(
        id=CODEX,
        label="Codex",
        command="codex",
        hint="install Codex CLI (`npm install -g @openai/codex` or the native installer) and run `codex login` once",
        default_model="",
        native=False,
        nested_markers=("CODEX_SANDBOX", "CODEX_SANDBOX_NETWORK_DISABLED", "CODEX_THREAD_ID"),
    ),
    QWEN: Runtime(
        id=QWEN,
        label="Qwen Code",
        command="qwen",
        hint="install Qwen Code (`npm install -g @qwen-code/qwen-code`) and run `qwen` once to sign in",
        default_model="",
        native=False,
        nested_markers=("QWEN_CODE", "QWEN_CODE_NO_RELAUNCH"),
    ),
}
DEFAULT_RUNTIME = CLAUDE_CODE

NESTED_MARKERS: tuple[str, ...] = tuple(
    marker for runtime in RUNTIMES.values() for marker in runtime.nested_markers
)

#: A model id that belongs to another runtime: a Claude alias left over from before the
#: page could pick a backend means nothing to Codex or Qwen, and is dropped for theirs.
FOREIGN_MODELS: dict[str, re.Pattern[str]] = {
    CODEX: re.compile(r"^(claude-|opus|sonnet|haiku|fable|opusplan|qwen)", re.I),
    QWEN: re.compile(r"^(claude-|opus|sonnet|haiku|fable|opusplan|gpt-|o[134](?:-|$))", re.I),
}

#: The pass's ceiling per request. A CLI agent reading graphify's 60k-token chunk and
#: writing its JSON takes minutes, not graphify's default ten for an API call.
API_TIMEOUT_SECONDS = 1800
#: How many CLI runs answer at once. graphify would send four chunks together; four
#: agent sessions on one subscription is more than its rate limits like.
CONCURRENCY = 2
#: graphify's HTTP client retries a failed request up to six times; each retry here is a
#: whole CLI run, and graphify retries a failed chunk itself.
CLIENT_RETRIES = 1

MAX_BODY_BYTES = 64 * 1024 * 1024
MAX_HEADER_LINES = 100
#: Windows' command-line ceiling is 32 767 UTF-16 units; the rest is the argv around it.
COMMAND_LINE_BUDGET = 30_000


def runtime_of(name: str | None) -> Runtime:
    return RUNTIMES.get(str(name or "").strip().lower(), RUNTIMES[DEFAULT_RUNTIME])


def model_for(runtime: Runtime, model: str | None) -> str:
    """The model the runtime is asked for — the page's choice unless it is another runtime's."""
    chosen = str(model or "").strip()
    foreign = FOREIGN_MODELS.get(runtime.id)
    if foreign is not None and foreign.match(chosen):
        chosen = ""
    return chosen or runtime.default_model


# ------------------------------------------------------------------ finding the CLI


def launcher(runtime: Runtime) -> list[str] | None:
    """The argv prefix that starts the runtime's CLI, or ``None`` when it is not installed.

    Codex and Qwen are found the way the Teams backends find them (``teams/backends``):
    on Windows an npm install answers ``shutil.which`` with a ``.cmd`` shim, and a batch
    file re-parses every argument through ``cmd.exe`` — so Codex's native binary beside
    the shim, and ``node`` with Qwen's script, are what run. A Studio installed without
    the Teams extra falls back to PATH.
    """
    if runtime.id == CLAUDE_CODE:
        found = shutil.which("claude") or shutil.which("claude.cmd")
        return [found] if found else None
    try:
        if runtime.id == CODEX:
            from voxarium_studio.teams.backends.codex import _launcher
        else:
            from voxarium_studio.teams.backends.qwen import _launcher
    except Exception:  # noqa: BLE001 — the Teams backends are an optional extra
        found = shutil.which(runtime.command)
        return [found] if found else None
    return _launcher()


def child_environment(base: dict[str, str] | None = None) -> dict[str, str]:
    """The environment without any agent CLI's nested-session markers."""
    env = dict(os.environ if base is None else base)
    for marker in NESTED_MARKERS:
        env.pop(marker, None)
    env.setdefault("PYTHONIOENCODING", "utf-8")
    return env


def _no_window() -> dict[str, Any]:
    """The Studio may run without a console (Electron starts it hidden); a console child
    would then flash a window per chunk."""
    if sys.platform == "win32":
        return {"creationflags": subprocess.CREATE_NO_WINDOW}
    return {}


# ------------------------------------------------------------------ running it once


class CliError(RuntimeError):
    """The CLI ran and gave no answer; the message is what it said about why."""


Runner = Callable[[str, str], Awaitable[str]]


def _tail(text: str, limit: int = 600) -> str:
    text = text.strip()
    return text if len(text) <= limit else "…" + text[-limit:]


async def _communicate(argv: list[str], stdin: str, cwd: Path) -> tuple[str, str, int]:
    process = await asyncio.create_subprocess_exec(
        *argv,
        cwd=str(cwd),
        env=child_environment(),
        stdin=asyncio.subprocess.PIPE,
        stdout=asyncio.subprocess.PIPE,
        stderr=asyncio.subprocess.PIPE,
        start_new_session=os.name != "nt",
        **_no_window(),
    )
    try:
        out, err = await process.communicate(stdin.encode("utf-8"))
    except BaseException:
        if process.returncode is None:
            try:
                from voxarium_studio.core.processes import kill_process_tree

                await kill_process_tree(process)
            except Exception:  # noqa: BLE001 — fall back to the process itself
                process.kill()
                await process.wait()
        raise
    return (
        out.decode("utf-8", errors="replace"),
        err.decode("utf-8", errors="replace"),
        process.returncode if process.returncode is not None else -1,
    )


def _toml_string(text: str) -> str:
    return json.dumps(text, ensure_ascii=False)


def codex_command(prefix: list[str], model: str, instructions: Path, last_message: Path) -> list[str]:
    """``codex exec`` for one chunk: no session kept, no git check, nothing it may change.

    ``model_instructions_file`` replaces Codex's own base instructions with graphify's
    extraction prompt (the Teams backend does the same, §10.6); the chunk arrives on stdin
    (``-``), and the answer is read from ``--output-last-message`` rather than picked out
    of the event stream.
    """
    argv = [
        *prefix,
        "exec",
        "--skip-git-repo-check",
        "--ephemeral",
        "--color",
        "never",
        "--sandbox",
        "read-only",
        "-c",
        'approval_policy="never"',
        "-c",
        f"model_instructions_file={_toml_string(str(instructions))}",
        "--output-last-message",
        str(last_message),
    ]
    if model:
        argv += ["-m", model]
    argv.append("-")
    return argv


async def run_codex(prefix: list[str], model: str, system: str, prompt: str) -> str:
    # An empty working directory of its own: the project's AGENTS.md is not the model's
    # business while it reads documents, and there is nothing there to touch.
    with tempfile.TemporaryDirectory(prefix="vxs-graphify-codex-") as scratch:
        root = Path(scratch)
        instructions = root / "instructions.md"
        instructions.write_text(system, encoding="utf-8")
        last_message = root / "last-message.txt"
        argv = codex_command(prefix, model, instructions, last_message)
        out, err, code = await _communicate(argv, prompt, root)
        try:
            answer = last_message.read_text(encoding="utf-8")
        except OSError:
            answer = ""
        if code != 0 or not answer.strip():
            said = _tail(err) or _tail(out) or "no output"
            raise CliError(f"codex exec exited with {code}: {said}")
        return answer


def qwen_command(prefix: list[str], model: str, system: str, prompt: str) -> tuple[list[str], str]:
    """Qwen Code headless, as the Teams backend runs it (§10.6): the prompt on stdin,
    ``stream-json`` out. ``plan`` approval keeps it from changing anything. The system
    prompt replaces Qwen's own when it fits on the command line, and opens the prompt
    when it does not."""
    argv = [*prefix, "--output-format", "stream-json", "--input-format", "text", "--approval-mode", "plan"]
    if model:
        argv += ["--model", model]
    candidate = [*argv, "--system-prompt", system]
    if len(subprocess.list2cmdline(candidate).encode("utf-16-le")) // 2 < COMMAND_LINE_BUDGET:
        return candidate, prompt
    return argv, f"Instructions:\n{system}\n\nInput:\n{prompt}"


def qwen_answer(stdout: str) -> str:
    """The run's answer from Qwen's ``stream-json``: the ``result`` frame's text, or the
    last top-level assistant text. An error frame is the run's failure."""
    result: str | None = None
    last_text = ""
    for line in stdout.splitlines():
        line = line.strip()
        if not line.startswith("{"):
            continue
        try:
            frame = json.loads(line)
        except ValueError:
            continue
        if not isinstance(frame, dict) or frame.get("parent_tool_use_id"):
            continue
        kind = frame.get("type")
        if kind == "assistant":
            for block in (frame.get("message") or {}).get("content") or []:
                if isinstance(block, dict) and block.get("type") == "text" and isinstance(block.get("text"), str):
                    last_text = block["text"]
        elif kind == "result":
            if frame.get("is_error"):
                raise CliError(str(frame.get("error") or frame.get("result") or "Qwen Code reported an error"))
            if isinstance(frame.get("result"), str):
                result = frame["result"]
        elif kind == "error":
            raise CliError(str(frame.get("error") or frame.get("message") or "Qwen Code error"))
    return result if result and result.strip() else last_text


async def run_qwen(prefix: list[str], model: str, system: str, prompt: str) -> str:
    with tempfile.TemporaryDirectory(prefix="vxs-graphify-qwen-") as scratch:
        argv, stdin = qwen_command(prefix, model, system, prompt)
        out, err, code = await _communicate(argv, stdin, Path(scratch))
        answer = qwen_answer(out)
        if code != 0 or not answer.strip():
            raise CliError(f"qwen exited with {code}: {_tail(err) or _tail(out) or 'no output'}")
        return answer


def runner_for(runtime: Runtime, prefix: list[str], model: str) -> Runner:
    """``(system, prompt) -> answer`` through the runtime's CLI."""
    if runtime.id == CODEX:
        return lambda system, prompt: run_codex(prefix, model, system, prompt)
    if runtime.id == QWEN:
        return lambda system, prompt: run_qwen(prefix, model, system, prompt)
    raise ValueError(f"{runtime.label} is read through graphify's own backend, not the endpoint")


# ------------------------------------------------------------------ the endpoint


def _content_text(content: Any) -> str:
    """A chat message's content as text — a string, or the parts of a multimodal one."""
    if isinstance(content, str):
        return content
    if isinstance(content, list):
        parts = []
        for part in content:
            if isinstance(part, dict) and part.get("type") == "text" and isinstance(part.get("text"), str):
                parts.append(part["text"])
            elif isinstance(part, str):
                parts.append(part)
        return "\n".join(parts)
    return ""


def split_messages(messages: Any) -> tuple[str, str]:
    """``(system, prompt)`` from an OpenAI ``messages`` list.

    graphify sends one system message (its extraction prompt) and one user message (the
    chunk). Anything longer is laid out as a transcript, so nothing sent is dropped.
    """
    if not isinstance(messages, list) or not messages:
        raise ValueError("messages must be a non-empty list")
    system: list[str] = []
    turns: list[tuple[str, str]] = []
    for message in messages:
        if not isinstance(message, dict):
            raise ValueError("every message must be an object")
        role = str(message.get("role") or "user")
        text = _content_text(message.get("content"))
        if role in ("system", "developer"):
            system.append(text)
        else:
            turns.append((role, text))
    if not turns:
        raise ValueError("there is no user message")
    if len(turns) == 1:
        prompt = turns[0][1]
    else:
        prompt = "\n\n".join(f"[{role}]\n{text}" for role, text in turns)
    return "\n\n".join(part for part in system if part), prompt


class DocumentsEndpoint:
    """An OpenAI-compatible ``/v1/chat/completions`` on ``127.0.0.1`` for one documents pass.

    ``async with`` starts it and stops it; stopping cancels the requests still being
    answered, which kills their CLI runs. ``rule`` — the plugin's language rule — is
    appended to every system prompt, which is how it reaches a model that reads no
    ``CLAUDE.md``.
    """

    def __init__(
        self,
        runner: Runner,
        *,
        model: str,
        rule: str = "",
        label: str = "the CLI",
        concurrency: int = CONCURRENCY,
        on_line: Callable[[str], None] | None = None,
        token: str | None = None,
    ) -> None:
        self.runner = runner
        self.model = model or "default"
        self.rule = rule.strip()
        self.label = label
        self.token = token or secrets.token_urlsafe(32)
        self.on_line = on_line
        self._slots = asyncio.Semaphore(max(1, concurrency))
        self._server: asyncio.base_events.Server | None = None
        self._handlers: set[asyncio.Task[Any]] = set()
        self.port: int | None = None
        self.answered = 0
        self.failed = 0

    @property
    def base_url(self) -> str:
        if self.port is None:
            raise RuntimeError("the endpoint is not listening")
        return f"http://127.0.0.1:{self.port}/v1"

    def graphify_env(self) -> dict[str, str]:
        """What points graphify's ``openai`` backend here instead of at OpenAI."""
        no_proxy = ",".join(
            part
            for part in [os.environ.get("NO_PROXY") or os.environ.get("no_proxy") or "", "127.0.0.1", "localhost"]
            if part
        )
        return {
            "OPENAI_BASE_URL": self.base_url,
            "OPENAI_API_KEY": self.token,
            "OPENAI_MODEL": self.model,
            "GRAPHIFY_OPENAI_MODEL": self.model,
            "GRAPHIFY_MAX_RETRIES": str(CLIENT_RETRIES),
            # graphify's HTTP client honours a proxy from the environment; a corporate one
            # must not be asked for 127.0.0.1.
            "NO_PROXY": no_proxy,
            "no_proxy": no_proxy,
        }

    async def start(self) -> "DocumentsEndpoint":
        self._server = await asyncio.start_server(self._accept, host="127.0.0.1", port=0)
        self.port = self._server.sockets[0].getsockname()[1]
        return self

    async def stop(self) -> None:
        server, self._server = self._server, None
        if server is not None:
            server.close()
        handlers = list(self._handlers)
        for task in handlers:
            task.cancel()
        if handlers:
            await asyncio.gather(*handlers, return_exceptions=True)
        if server is not None:
            try:
                await asyncio.wait_for(server.wait_closed(), timeout=5)
            except (asyncio.TimeoutError, OSError):
                pass

    async def __aenter__(self) -> "DocumentsEndpoint":
        return await self.start()

    async def __aexit__(self, *exc: object) -> None:
        await self.stop()

    # ---- HTTP

    async def _accept(self, reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> None:
        task = asyncio.current_task()
        if task is not None:
            self._handlers.add(task)
        try:
            status, body = await self._handle(reader)
            await self._respond(writer, status, body)
        except (asyncio.IncompleteReadError, ConnectionError, ValueError):
            pass
        finally:
            if task is not None:
                self._handlers.discard(task)
            writer.close()
            try:
                await writer.wait_closed()
            except (ConnectionError, OSError):
                pass

    async def _handle(self, reader: asyncio.StreamReader) -> tuple[int, dict[str, Any]]:
        request_line = (await reader.readline()).decode("latin-1").strip()
        parts = request_line.split(" ")
        if len(parts) != 3:
            return 400, _error("malformed request line", "invalid_request_error")
        method, target, _version = parts
        headers: dict[str, str] = {}
        for _ in range(MAX_HEADER_LINES):
            line = (await reader.readline()).decode("latin-1")
            if line in ("\r\n", "\n", ""):
                break
            name, _, value = line.partition(":")
            headers[name.strip().lower()] = value.strip()
        else:
            return 431, _error("too many headers", "invalid_request_error")

        if not self._authorised(headers.get("authorization", "")):
            return 401, _error("a valid bearer token is required", "invalid_api_key")
        path = target.split("?", 1)[0].rstrip("/")
        if path in ("/v1/models", "/models"):
            if method != "GET":
                return 405, _error("use GET", "invalid_request_error")
            return 200, {"object": "list", "data": [{"id": self.model, "object": "model", "owned_by": "voxarium-studio"}]}
        if path not in ("/v1/chat/completions", "/chat/completions"):
            return 404, _error(f"no route {path}", "invalid_request_error")
        if method != "POST":
            return 405, _error("use POST", "invalid_request_error")
        if "chunked" in headers.get("transfer-encoding", "").lower():
            return 411, _error("send a Content-Length", "invalid_request_error")
        try:
            length = int(headers.get("content-length", ""))
        except ValueError:
            return 411, _error("send a Content-Length", "invalid_request_error")
        if length < 0 or length > MAX_BODY_BYTES:
            return 413, _error("request too large", "invalid_request_error")
        raw = await reader.readexactly(length)
        try:
            request = json.loads(raw.decode("utf-8"))
            if not isinstance(request, dict):
                raise ValueError("the body must be a JSON object")
            if request.get("stream"):
                raise ValueError("streaming is not supported")
            system, prompt = split_messages(request.get("messages"))
        except (ValueError, UnicodeDecodeError) as exc:
            return 400, _error(str(exc), "invalid_request_error")
        return await self._complete(system, prompt)

    def _authorised(self, header: str) -> bool:
        scheme, _, credential = header.partition(" ")
        if scheme.lower() != "bearer" or not credential:
            return False
        return hmac.compare_digest(credential.strip().encode(), self.token.encode())

    async def _complete(self, system: str, prompt: str) -> tuple[int, dict[str, Any]]:
        if self.rule:
            system = f"{system}\n\n{self.rule}" if system else self.rule
        async with self._slots:
            started = time.monotonic()
            try:
                answer = await self.runner(system, prompt)
            except (CliError, OSError) as exc:
                self.failed += 1
                self._say(f"{self.label} gave no answer: {exc}")
                return 502, _error(str(exc), "server_error")
        self.answered += 1
        self._say(f"{self.label} answered a chunk in {time.monotonic() - started:.0f} s")
        return 200, {
            "id": f"chatcmpl-vxs-{secrets.token_hex(8)}",
            "object": "chat.completion",
            "created": int(time.time()),
            "model": self.model,
            "choices": [
                {
                    "index": 0,
                    "message": {"role": "assistant", "content": answer},
                    "finish_reason": "stop",
                }
            ],
            # The CLIs report usage in their own terms, if at all; graphify only sums it.
            "usage": {"prompt_tokens": 0, "completion_tokens": 0, "total_tokens": 0},
        }

    def _say(self, line: str) -> None:
        if self.on_line is not None:
            self.on_line(line)

    @staticmethod
    async def _respond(writer: asyncio.StreamWriter, status: int, body: dict[str, Any]) -> None:
        payload = json.dumps(body, ensure_ascii=False).encode("utf-8")
        reason = _REASONS.get(status, "Error")
        head = (
            f"HTTP/1.1 {status} {reason}\r\n"
            "Content-Type: application/json; charset=utf-8\r\n"
            f"Content-Length: {len(payload)}\r\n"
            "Connection: close\r\n"
        )
        if status == 401:
            head += 'WWW-Authenticate: Bearer realm="voxarium-studio-graphify"\r\n'
        writer.write(head.encode("latin-1") + b"\r\n" + payload)
        await writer.drain()


_REASONS = {
    200: "OK",
    400: "Bad Request",
    401: "Unauthorized",
    404: "Not Found",
    405: "Method Not Allowed",
    411: "Length Required",
    413: "Payload Too Large",
    431: "Request Header Fields Too Large",
    502: "Bad Gateway",
}


def _error(message: str, kind: str) -> dict[str, Any]:
    return {"error": {"message": message, "type": kind, "code": kind}}
