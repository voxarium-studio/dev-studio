"""graphify — a knowledge graph of the project's code and documents, kept fresh by the
Studio (§9.7).

What the plugin owns is three files under ``<launch root>/graphify-out/``: ``graph.json``,
the code, written by ``graphify extract <path> --code-only``; ``documents/…/graph.json``,
the Documents section, written by a second extract over ``.voxarium_studio/docs`` through the
agent CLI the Graphify page names — Claude Code, Codex or Qwen Code (``graphify_runtimes``);
and ``merged-graph.json``, the two folded together by ``graphify merge-graphs`` — what agents
and the search field read. Three things hang off them, and each is one of the extension points this plugin was the reason for:

* **Hooks** rebuild them — when the Studio opens the project, and each time a task reaches
  *done*, because that is when the code has changed and the next task will ask about it.
  ``--code-only`` is what keeps the code pass free: local AST, no model, no key, seconds
  not minutes. The documents pass is the one that costs something, and it runs second.
* **A search provider** answers the rail's search field from them — a ranked lookup over
  the graph's nodes as you type, and on Enter the CLI's own ``graphify query`` traversal.
* **Actions** are what the settings page calls: index now, cancel, status.
* **An automation action**, *Graphify Update*, is the same run offered to the Automations page
  (§6.5). It waits for the run that covers it (`request`, `wait_for_run`), so the page shows it
  in progress for exactly as long as the graph is being rebuilt.

The prompt fragment that tells agents to ask the graph before grepping is *not* here — it
is ``code_graph`` in ``core/prompts.py``, sent whenever the file exists at the launch
root, so it stays editable beside every other line the Studio says to an agent.

The graph lands at the launch root and not under ``.voxarium_studio/`` on purpose: that directory
is where an agent session starts, so ``graphify query`` run from there finds the graph
with no flag, and it is where the graphify skill's own fast path looks.
"""

from __future__ import annotations

import asyncio
import json
import logging
import math
import re
import shutil
import time
from collections import Counter, deque
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

# A sibling module: the loader has the plugin root on sys.path while this file executes.
import graphify_runtimes as runtimes
from voxarium_studio.plugin_api import (
    AutomationCancelled,
    Event,
    PluginHost,
    PluginRegistry,
    SearchQuery,
    SearchResponse,
    SearchResult,
)

log = logging.getLogger("voxarium_studio.plugins.graphify")

OUT_DIRNAME = "graphify-out"
GRAPH_FILENAME = "graph.json"
MERGED_FILENAME = "merged-graph.json"
DOCUMENTS_OUT_DIRNAME = "documents"
"""Where the documents pass writes, under the code's ``graphify-out/``. Also the *repo tag*
``merge-graphs`` stamps on every document node (``documents::<id>``, ``repo: documents``):
the tag is the name of the directory two levels above a ``graph.json``, and the CLI writes
``<out>/graphify-out/graph.json``, so the out directory's name is the tag."""
DOCUMENTS_TAG = DOCUMENTS_OUT_DIRNAME
DEFAULT_EXECUTABLE = "graphify"
DEFAULT_SCAN_PATH = "."
DEFAULT_DOCUMENTS_BACKEND = runtimes.DEFAULT_RUNTIME
DEFAULT_DOCUMENTS_MODEL = runtimes.RUNTIMES[runtimes.DEFAULT_RUNTIME].default_model
DEFAULT_DOCUMENTS_LANGUAGE = "source"

#: What the model reading the documents is told about language, per setting. graphify's
#: own extraction prompt says nothing about it, and left alone the model translates every
#: label into English — a Russian design document then answers only English queries.
#:
#: How the rule reaches the model depends on the runtime. ``claude -p`` gets it as project
#: memory: a ``CLAUDE.md`` in the directory the documents pass runs from, which Claude Code
#: loads like any project's. (A wrapper adding ``--append-system-prompt`` was the first
#: idea; a ``.cmd`` shim on Windows breaks on the quotes in graphify's ``--json-schema``
#: argument — cmd.exe has no escape for them — and graphify offers no other hook.) Codex
#: and Qwen Code are answered by the Studio's own endpoint, which appends the rule to every
#: system prompt (``graphify_runtimes.DocumentsEndpoint``). Either way graphify's prompt —
#: what its semantic cache is keyed on — is untouched. Node ids stay ASCII: graphify's id
#: format requires it, and it is labels people search by.
LANGUAGE_MEMORY: dict[str, str] = {
    "source": (
        "# LANGUAGE RULE FOR THE EXTRACTION OUTPUT (mandatory — overrides any example in the task)\n\n"
        "Voxarium Studio asks you, on graphify's behalf, to read the team's documents. "
        "The JSON you output describes those documents, and every `label` — of a "
        "node and of a hyperedge — and every `rationale` MUST be written in the language the "
        "source document is written in: Russian source text gives Russian labels and "
        "rationales, English gives English, and a document that mixes the two gets each "
        "label in the language of the passage it comes from. NEVER translate a label or a "
        "rationale into English. The example value \"Human Readable Name\" in the task is a "
        "placeholder, not a language requirement. When the document itself gives a name or a "
        "term in two languages, keep both in the label, the original first: "
        "«Тоа Норрингтон (Toa Norrington)». Only the `id` fields stay ASCII `[a-z0-9_]`, as "
        "the format requires — transliterate.\n"
    ),
    "english": (
        "# LANGUAGE RULE FOR THE EXTRACTION OUTPUT (mandatory — overrides any example in the task)\n\n"
        "Voxarium Studio asks you, on graphify's behalf, to read the team's documents. "
        "Every `label` — of a node and of a hyperedge — and every `rationale` "
        "MUST be written in English, whatever language the document is written in. Keep a "
        "proper name or a term the document gives in another language in parentheses after "
        "the English: «Toa Norrington (Тоа Норрингтон)». The `id` fields stay ASCII "
        "`[a-z0-9_]`.\n"
    ),
}
TASK_DONE = "done"
LOG_TAIL = 120
QUERY_TIMEOUT = 90.0
QUERY_BUDGET = 1500
INSTALL_HINT = "uv tool install graphifyy"
CLAUDE_HINT = runtimes.RUNTIMES[runtimes.CLAUDE_CODE].hint
#: What graphify needs installed to reach the Studio's endpoint (``--backend openai``).
OPENAI_EXTRA_HINT = 'uv tool install "graphifyy[openai]" --force'
_MISSING_PACKAGE = re.compile(r"the '([\w.-]+)' package is required for this backend")

#: What the documents pass does *not* send to the model even inside the Documents folder:
#: a picture pasted into a document is a vision call the team's writing does not need, and
#: YAML and HTML are structured or generated, not prose. What is left is the documents
#: themselves — Markdown and text. ``vxs-folder.json`` is the Studio's own note of how a
#: folder looks (block-editor spec §5.2): graphify reads ``.json`` as code, and a colour is
#: not something the team wrote.
DOCUMENT_EXCLUDES = (
    "*.png",
    "*.jpg",
    "*.jpeg",
    "*.gif",
    "*.webp",
    "*.svg",
    "*.pdf",
    "*.yaml",
    "*.yml",
    "*.html",
    "vxs-folder.json",
    "tds-folder.json",
)

# The CLI warns on every run when the skill it installed for the agent is a version behind
# the package; that is advice for a person's terminal, not a line of the index's log.
_CLI_NOISE = re.compile(r"^\s*warning: skill is from graphify ")


# ------------------------------------------------------------------ progress

#: Where the AST pass sits on the bar. Scanning and detection are quick and come before
#: it; building and clustering the graph, then writing it and the analysis, come after.
#: Inside the band the bar follows the per-file lines graphify prints — every 100 files,
#: once the corpus has 100 — and a small corpus, which prints none, moves stage by stage,
#: which for a three-second run is enough. The band ends at 60 rather than higher because
#: what follows it is silent and, on a large corpus, the longest part: 300 files were read
#: in 1.5 s and clustered for 16 s (measured 2026-09-13), and a bar parked at 85% through
#: that reads as "almost done" when it is not.
AST_BAND = (15.0, 60.0)

#: The documents pass prints nothing per file — the model reads a chunk of documents at a
#: time and graphify reports each chunk as it lands — so inside this band the bar moves a
#: chunk at a time. It starts above the AST band's floor so the pass's own lines, which
#: arrive after that pass's "0 code files", never read as the bar going back.
SEMANTIC_BAND = (20.0, 88.0)

#: How the two passes share one bar when both run: the code pass is seconds of local
#: parsing, the documents pass is model calls, so the second gets the larger share.
CODE_BAND_WITH_DOCUMENTS = (0.0, 40.0)
#: Ends short of 100 so the merge that follows has somewhere to stand on the bar.
DOCUMENTS_BAND = (40.0, 97.0)
WHOLE_BAND = (0.0, 100.0)

_SCAN = re.compile(r"^\[graphify extract\] (?:scanning|incremental scan of) ")
# `found 13 code, 0 docs…` on a full scan; `2 code, 0 docs, … changed; …` on an incremental one.
_FOUND = re.compile(r"^\[graphify extract\] (?:found )?(\d+) code, (\d+) docs")
_AST_TOTAL = re.compile(r"^\[graphify extract\] AST extraction on (\d+) code files")
_AST_STEP = re.compile(r"^AST extraction: (\d+)/(\d+) uncached files \((\d+)%\)")
_SEMANTIC_CACHE = re.compile(r"^\[graphify extract\] semantic cache: (\d+) hit / (\d+) miss")
_SEMANTIC_START = re.compile(r"^\[graphify extract\] semantic extraction on (\d+) files via (\S+?)\.*$")
_CHUNK = re.compile(r"^\[graphify extract\] chunk (\d+)/(\d+) done")
_WROTE = re.compile(r"^\[graphify extract\] wrote (.+)")
_FINISHED = re.compile(
    r"^\[graphify extract\] (?:incremental summary|next:|no incremental changes)"
)


@dataclass(slots=True)
class Progress:
    """How far the running extract has got, as its own output says."""

    percent: float | None = None
    stage: str | None = None
    detail: str | None = None

    def to_json(self) -> dict[str, Any]:
        return {"percent": self.percent, "stage": self.stage, "detail": self.detail}

    def within(self, band: tuple[float, float]) -> "Progress":
        """The same frame with its percent mapped from 0–100 onto ``band``."""
        if self.percent is None:
            return self
        low, high = band
        return Progress(low + (high - low) * self.percent / 100.0, self.stage, self.detail)


def progress_from_line(line: str) -> Progress | None:
    """The frame one line of ``graphify extract`` output implies.

    ``None`` for a line that says nothing about where the run is — a skip notice, a
    warning, the closing tip. The stages are the CLI's own prints (checked against
    0.9.49); a line it stops printing costs a step on the bar, never a wrong one.
    """
    text = line.strip()
    if _SCAN.match(text):
        return Progress(5.0, "Scanning")
    found = _FOUND.match(text)
    if found:
        code, docs = found.group(1), found.group(2)
        detail = f"{code} code files" if docs == "0" else f"{code} code files, {docs} documents"
        return Progress(10.0, "Detecting files", detail)
    cached = _SEMANTIC_CACHE.match(text)
    if cached:
        return Progress(18.0, "Reading documents", f"{cached.group(1)} cached, {cached.group(2)} to read")
    semantic = _SEMANTIC_START.match(text)
    if semantic:
        return Progress(SEMANTIC_BAND[0], "Reading documents", f"{semantic.group(1)} files via {semantic.group(2)}")
    chunk = _CHUNK.match(text)
    if chunk:
        done, count = int(chunk.group(1)), max(int(chunk.group(2)), 1)
        low, high = SEMANTIC_BAND
        percent = low + (high - low) * min(done, count) / count
        return Progress(percent, "Reading documents", f"{done}/{count} chunks")
    total = _AST_TOTAL.match(text)
    if total:
        return Progress(AST_BAND[0], "AST extraction", f"{total.group(1)} files")
    step = _AST_STEP.match(text)
    if step:
        done, count = int(step.group(1)), max(int(step.group(2)), 1)
        low, high = AST_BAND
        percent = low + (high - low) * min(done, count) / count
        if done >= count:
            # The last file is read; what follows — merge, build, cluster — prints nothing
            # until the graph is written, and on a large corpus that is the longest wait.
            return Progress(percent, "Building the graph", f"{count} files read")
        return Progress(percent, "AST extraction", f"{done}/{count} files")
    wrote = _WROTE.match(text)
    if wrote:
        if "graph.json" in wrote.group(1):
            return Progress(92.0, "Writing the graph")
        return Progress(97.0, "Writing the analysis")
    if _FINISHED.match(text):
        return Progress(100.0, "Done")
    return None


# ------------------------------------------------------------------ settings


def _text(settings: dict[str, Any], key: str, default: str) -> str:
    value = str(settings.get(key) or "").strip()
    return value or default


def _flag(settings: dict[str, Any], key: str, default: bool) -> bool:
    """A boolean setting as the page may have saved it — a bool, or a string of one."""
    value = settings.get(key)
    if value is None:
        return default
    if isinstance(value, bool):
        return value
    return str(value).strip().lower() in ("1", "true", "yes", "on")


def executable(settings: dict[str, Any]) -> str:
    return _text(settings, "executable", DEFAULT_EXECUTABLE)


def scan_path(settings: dict[str, Any]) -> str:
    return _text(settings, "path", DEFAULT_SCAN_PATH)


def documents_enabled(settings: dict[str, Any]) -> bool:
    return _flag(settings, "documents", True)


def documents_runtime(settings: dict[str, Any]) -> runtimes.Runtime:
    """The agent CLI the documents are read through (``documents_backend``).

    A configuration saved before the page could choose one has a model and no backend;
    that model was a Claude Code one, so no backend reads as Claude Code — the behaviour
    it was saved under.
    """
    return runtimes.runtime_of(settings.get("documents_backend"))


def documents_backend(settings: dict[str, Any]) -> str:
    return documents_runtime(settings).id


def documents_model(settings: dict[str, Any]) -> str:
    """The model the documents are read with — an alias or a full id; ``""`` is the CLI's
    own configured model (Codex and Qwen Code by default). A model that belongs to another
    runtime is dropped for the runtime's default rather than handed to a CLI that cannot
    run it."""
    return runtimes.model_for(documents_runtime(settings), settings.get("documents_model"))


def documents_language(settings: dict[str, Any]) -> str:
    """``source`` — labels in the document's own language — or ``english``."""
    choice = _text(settings, "documents_language", DEFAULT_DOCUMENTS_LANGUAGE).lower()
    return choice if choice in LANGUAGE_MEMORY else DEFAULT_DOCUMENTS_LANGUAGE


def language_rule(choice: str) -> str:
    return LANGUAGE_MEMORY[choice]


def write_language_memory(directory: Path, choice: str) -> Path:
    """Put the language rule where ``claude -p`` will read it: ``CLAUDE.md`` in its cwd."""
    directory.mkdir(parents=True, exist_ok=True)
    memory = directory / "CLAUDE.md"
    text = LANGUAGE_MEMORY[choice]
    try:
        if memory.read_text(encoding="utf-8") == text:
            return memory
    except OSError:
        pass
    memory.write_text(text, encoding="utf-8")
    return memory


def graph_file(launch_root: Path) -> Path:
    """The code graph — where the CLI's own defaults look for a graph."""
    return launch_root / OUT_DIRNAME / GRAPH_FILENAME


def documents_out(launch_root: Path) -> Path:
    """The ``--out`` of the documents pass; the CLI puts its ``graphify-out/`` inside."""
    return launch_root / OUT_DIRNAME / DOCUMENTS_OUT_DIRNAME


def documents_graph_file(launch_root: Path) -> Path:
    return documents_out(launch_root) / OUT_DIRNAME / GRAPH_FILENAME


def merged_graph_file(launch_root: Path) -> Path:
    """Code and documents in one graph — what agents and the search field read."""
    return launch_root / OUT_DIRNAME / MERGED_FILENAME


def effective_graph_file(launch_root: Path) -> Path:
    """The graph to answer from: the merged one while it exists, the code graph otherwise."""
    merged = merged_graph_file(launch_root)
    try:
        return merged if merged.is_file() else graph_file(launch_root)
    except OSError:
        return graph_file(launch_root)


def documents_dir(host: PluginHost) -> Path:
    """The Documents section's folder — the one thing the documents pass reads."""
    return host.data_dir / "docs"


def documents_present(directory: Path) -> bool:
    """Whether there is anything to read: a graphify run over an empty folder is a failure,
    not an empty graph, and an empty Documents section is the normal state of a new project."""
    try:
        return any(path.is_file() for path in directory.rglob("*"))
    except OSError:
        return False


def extract_command(
    settings: dict[str, Any], launch_root: Path, *, force: bool = False
) -> list[str]:
    """``graphify extract <path> --code-only --out <launch root>``.

    ``--out`` pins the graph to the launch root whatever the scan path is: the CLI would
    otherwise write it inside the scanned folder, and an agent starting one level up would
    not find it. ``--code-only`` is what keeps this free — docs, papers and images are the
    part that needs a model, and they are skipped. ``--force`` drops the incremental gate
    and re-reads every file — what graphify itself recommends after a refactor that
    deleted code, and the page's "Rebuild all".
    """
    command = [
        executable(settings),
        "extract",
        scan_path(settings),
        "--code-only",
        "--out",
        str(launch_root),
    ]
    if force:
        command.append("--force")
    return command


def documents_command(settings: dict[str, Any], launch_root: Path, docs: Path) -> list[str]:
    """The documents pass: ``.voxarium_studio/docs/`` alone, through the chosen agent CLI,
    into its own graph.

    graphify indexes documents only semantically — a model reads them and names the
    concepts, decisions and entities inside — so this is the run that costs something.
    Claude Code is graphify's own ``--backend claude-cli``: the ``claude`` command on the
    user's subscription. Codex and Qwen Code are ``--backend openai`` pointed at the
    Studio's endpoint for the pass (``graphify_runtimes``), with a request timeout fit for
    an agent run rather than an API call and fewer chunks at once.

    Its scan root is the Documents folder and its ``--out`` is a folder of its own, and the
    two go together: graphify prunes from a graph every node whose file the scan no longer
    covers, so a documents scan writing into the code's ``graphify-out/`` would take the
    code out of it. Two graphs, each kept incrementally by its own scan, and
    ``merge-graphs`` folds them into the one agents read. Never ``--force``: the semantic
    cache is what keeps an unchanged document free.
    """
    command = [
        executable(settings),
        "extract",
        str(docs),
        "--out",
        str(documents_out(launch_root)),
    ]
    if documents_runtime(settings).native:
        command += ["--backend", "claude-cli"]
    else:
        command += [
            "--backend",
            "openai",
            "--api-timeout",
            str(runtimes.API_TIMEOUT_SECONDS),
            "--max-concurrency",
            str(runtimes.CONCURRENCY),
        ]
    for pattern in DOCUMENT_EXCLUDES:
        command.extend(["--exclude", pattern])
    return command


def merge_command(settings: dict[str, Any], launch_root: Path) -> list[str]:
    """``graphify merge-graphs``: the code graph and the documents graph into the merged one.

    Every node keeps its label and ``source_file`` and gains ``repo`` — the code's is the
    launch root's directory name, the documents' is :data:`DOCUMENTS_TAG` — which is how
    a search hit knows which folder its ``source_file`` is relative to.
    """
    return [
        executable(settings),
        "merge-graphs",
        str(graph_file(launch_root)),
        str(documents_graph_file(launch_root)),
        "--out",
        str(merged_graph_file(launch_root)),
    ]


def claude_executable() -> str | None:
    """Where ``claude`` is, or ``None``."""
    return shutil.which("claude") or shutil.which("claude.cmd")


def documents_launcher(runtime: runtimes.Runtime) -> list[str] | None:
    """How the chosen CLI is started, or ``None`` — the documents pass has nothing to read
    with. Codex and Qwen Code are found as the Teams backends find them."""
    if runtime.id == runtimes.CLAUDE_CODE:
        found = claude_executable()
        return [found] if found else None
    return runtimes.launcher(runtime)


def documents_cli(settings: dict[str, Any]) -> str | None:
    """The chosen CLI's executable, for the page."""
    found = documents_launcher(documents_runtime(settings))
    return found[-1] if found else None


def subprocess_env(settings: dict[str, Any] | None = None) -> dict[str, str]:
    """The environment the CLI runs in.

    ``PYTHONUNBUFFERED`` is the one that matters: graphify prints its stage lines without
    flushing, and a Python child writing to a pipe block-buffers them — every line then
    arrives together at exit, and the bar sits at 0% until it jumps to 100%. Unbuffered,
    each line lands as it is printed, which is what makes the percent live.

    Every agent CLI's nested-session marker is dropped (``CLAUDECODE`` and the like,
    ``graphify_runtimes.NESTED_MARKERS``) because the documents pass runs one of those CLIs,
    and a Studio started from inside such a session inherits them — a nested ``claude``
    refuses to start. The Studio is not that session; its children are not nested in it.
    """
    env = runtimes.child_environment()
    env.update(PYTHONIOENCODING="utf-8", PYTHONUNBUFFERED="1", GRAPHIFY_NO_TIPS="1")
    if settings is not None and documents_runtime(settings).id == runtimes.CLAUDE_CODE:
        env["GRAPHIFY_CLAUDE_CLI_MODEL"] = documents_model(settings)
    return env


def query_command(settings: dict[str, Any], question: str, graph: Path) -> list[str]:
    return [
        executable(settings),
        "query",
        question,
        "--graph",
        str(graph),
        "--budget",
        str(QUERY_BUDGET),
    ]


def resolve_executable(settings: dict[str, Any]) -> str | None:
    """Absolute path of the CLI, or ``None`` when it is not installed."""
    return shutil.which(executable(settings))


def forget_previous_scan(out: Path) -> list[str]:
    """Make the next extract into ``out`` read every file: drop its manifest and AST cache.

    ``--force`` alone does not do it for a code-only run in 0.9.49 — its log announces a
    "full AST re-scan" and then reports every unchanged file as unchanged and re-reads
    two. The manifest is the incremental gate and the AST cache holds each file's parsed
    result, both keyed on content and both rebuilt by the run that follows, so nothing is
    lost by removing them. ``--force`` is still passed: it is what lifts the shrink guard
    when the rebuilt graph is honestly smaller.

    The *semantic* cache stays. It is keyed on content too, but refilling it is a model
    call per document — the documents pass after this reads the changed ones and takes
    the rest from the cache, which is what makes a rebuild cost what the diff costs.
    """
    removed: list[str] = []
    for target in (out / "manifest.json", out / "cache" / "stat-index.json"):
        try:
            target.unlink()
            removed.append(target.name)
        except FileNotFoundError:
            pass
        except OSError as exc:
            log.warning("graphify: could not remove %s: %s", target, exc)
    ast_cache = out / "cache" / "ast"
    if ast_cache.is_dir():
        try:
            shutil.rmtree(ast_cache)
            removed.append("cache/ast/")
        except OSError as exc:
            log.warning("graphify: could not remove %s: %s", ast_cache, exc)
    return removed


# ------------------------------------------------------------------ the indexer


class Indexer:
    """One extract at a time per project, with the next one queued rather than overlapped.

    Lives in ``host.state`` so it survives a reload of this module (a settings save re-runs
    ``register()``): the lock guarding a running ``graphify extract`` has to be the same
    lock afterwards, or the reload would start a second extract over the first.
    """

    def __init__(self) -> None:
        self.lock = asyncio.Lock()
        self.pending: str | None = None
        self.pending_force = False
        """Whether any request queued behind the running extract asked for ``--force``;
        the one follow-up run then does, since a forced run covers an incremental one."""

        self.running = False
        self.reason: str | None = None
        self.command: list[str] = []
        self.started_at: float | None = None
        self.finished_at: float | None = None
        self.exit_code: int | None = None
        self.error: str | None = None
        self.log: deque[str] = deque(maxlen=LOG_TAIL)
        self.process: asyncio.subprocess.Process | None = None
        self.progress = Progress()
        """Where the current (or last) run got to, read off the CLI's own lines. Rides the
        same ``index`` event as the rest of the status, so the page's button can show it."""

        self.phase: str | None = None
        """``code`` or ``documents`` while a run is on — which pass is speaking."""

        self.documents: dict[str, Any] = self.documents_summary(enabled=False)
        """How the documents pass went on the last run: whether it was enabled, whether it
        ran, why it was skipped if it was, and how it ended."""

        # ---- who is waiting on which run (see `request`)

        self.runs_begun = 0
        """How many runs have begun since the project opened. A request is covered by the
        first run to *begin* after it, so a run's number is what a waiter waits on."""

        self.draining = False
        """A loop of runs is going (`_drain`): a request now queues or joins, never starts."""

        self.claimed: dict[str, Any] | None = None
        """A run asked for while nothing ran, whose loop has not begun it yet — reason, force,
        preface and occasions. It has read nothing, so a request arriving now joins it."""

        self.occasions: set[str] = set()
        """What the running run began for (`occasion`); empty while none runs."""

        self.pending_occasions: set[str] = set()
        """What the queued follow-up covers, beside ``pending``'s reason."""

        self.outcomes: dict[int, dict[str, Any]] = {}
        """How the latest runs ended, by number — ``error`` and ``summary`` — for waiters."""

        self.settled = asyncio.Condition()
        """Notified when a run ends and when the loop stops."""

    @staticmethod
    def documents_summary(
        *, enabled: bool, skipped: str | None = None
    ) -> dict[str, Any]:
        return {
            "enabled": enabled,
            "ran": False,
            "skipped": skipped,
            "exit_code": None,
            "error": None,
        }

    def status(self) -> dict[str, Any]:
        return {
            "running": self.running,
            "pending": self.pending,
            "reason": self.reason,
            "phase": self.phase,
            "command": list(self.command),
            "started_at": self.started_at,
            "finished_at": self.finished_at,
            "exit_code": self.exit_code,
            "error": self.error,
            "log": list(self.log),
            "progress": self.progress.to_json(),
            "documents": dict(self.documents),
        }

    def advance(self, frame: Progress) -> bool:
        """Adopt a frame unless it would move the bar backwards; says whether it did."""
        current = self.progress.percent
        if frame.percent is not None and current is not None and frame.percent < current:
            return False
        self.progress = frame
        return True


def indexer_of(host: PluginHost) -> Indexer:
    found = host.state.get("indexer")
    if found is None:
        found = host.state["indexer"] = Indexer()
    return found


KEPT_OUTCOMES = 32


def done_occasion(task_id: str) -> str:
    """The occasion a task reaching Done is: heard by this plugin's own hook and by an
    automation both, and indexed once."""
    return f"task:{task_id}:done"


def request(
    indexer: Indexer,
    reason: str,
    *,
    force: bool = False,
    preface: tuple[str, ...] = (),
    occasion: str | None = None,
) -> tuple[int, bool]:
    """Take a request for a run — awaiting nothing, so nothing can move between looking at the
    indexer and acting on what it said.

    Answers the number of the run that will cover the request, and whether the caller has to
    start the loop (`_drain`). The first run to *begin* after a request covers it, because that
    is the run that reads the files as they are now:

    - a run claimed but not begun has read nothing yet, so the request joins it;
    - a running one read its files before the request, so it is queued behind it — every
      request arriving meanwhile shares that one follow-up, and the newest reason names it;
    - unless it is for the very ``occasion`` the running one began for, which it joins rather
      than repeats: a task reaching Done is heard by this plugin's hook and by an automation.
    """
    claimed = indexer.claimed
    if claimed is not None:
        claimed["reason"] = reason
        claimed["force"] = claimed["force"] or force
        claimed["preface"] = preface or claimed["preface"]
        if occasion is not None:
            claimed["occasions"].add(occasion)
        return indexer.runs_begun + 1, False
    if indexer.draining:
        if occasion is not None and occasion in indexer.occasions:
            return indexer.runs_begun, False
        indexer.pending = reason
        indexer.pending_force = indexer.pending_force or force
        if occasion is not None:
            indexer.pending_occasions.add(occasion)
        return indexer.runs_begun + 1, False
    indexer.claimed = {
        "reason": reason,
        "force": force,
        "preface": preface,
        "occasions": {occasion} if occasion is not None else set(),
    }
    return indexer.runs_begun + 1, True


async def _drain(host: PluginHost, indexer: Indexer) -> None:
    """Run the claimed run, then each follow-up queued while one ran, until none is left.

    Holding the lock is what a reset waits on, so the lock is taken even when the claim turns
    out to have been dropped — by a Cancel, before it began.
    """
    try:
        async with indexer.lock:
            claimed, indexer.claimed = indexer.claimed, None
            if claimed is None:
                return
            indexer.draining = True
            try:
                current: str | None = claimed["reason"]
                current_force: bool = claimed["force"]
                current_preface: tuple[str, ...] = claimed["preface"]
                occasions: set[str] = claimed["occasions"]
                while current is not None:
                    indexer.runs_begun += 1
                    number = indexer.runs_begun
                    indexer.occasions = occasions
                    try:
                        await _extract_once(
                            host, indexer, current, force=current_force, preface=current_preface
                        )
                    finally:
                        indexer.occasions = set()
                        indexer.outcomes[number] = _outcome(indexer)
                        for old in sorted(indexer.outcomes)[:-KEPT_OUTCOMES]:
                            del indexer.outcomes[old]
                        await _wake(indexer)
                    current, current_force, current_preface = indexer.pending, indexer.pending_force, ()
                    occasions = indexer.pending_occasions
                    indexer.pending, indexer.pending_force = None, False
                    indexer.pending_occasions = set()
            finally:
                indexer.draining = False
    finally:
        await _wake(indexer)


async def _wake(indexer: Indexer) -> None:
    async with indexer.settled:
        indexer.settled.notify_all()


_WROTE_GRAPH = re.compile(r"graph\.json: (.+)$")
"""The CLI's closing line for a graph it wrote — ``…/graph.json: 9 nodes, 8 edges, …``."""


def _outcome(indexer: Indexer) -> dict[str, Any]:
    """How the run that just ended went, in the words a waiting automation's log will use."""
    counts = next(
        (match.group(1) for line in reversed(indexer.log) if (match := _WROTE_GRAPH.search(line))),
        None,
    )
    return {"error": indexer.error, "summary": f"Graph updated: {counts}" if counts else "Graph updated"}


async def wait_for_run(indexer: Indexer, number: int) -> dict[str, Any] | None:
    """How run ``number`` ended, once it has — or ``None`` when the indexer went quiet without
    beginning it: a Cancel dropped the request it would have been."""

    def settled() -> bool:
        if number in indexer.outcomes:
            return True
        quiet = not indexer.draining and indexer.claimed is None
        return quiet and indexer.runs_begun < number

    async with indexer.settled:
        await indexer.settled.wait_for(settled)
    return indexer.outcomes.get(number)


async def index(
    host: PluginHost,
    reason: str,
    *,
    force: bool = False,
    preface: tuple[str, ...] = (),
    occasion: str | None = None,
) -> dict[str, Any]:
    """Rebuild the graph now, or have a run that is coming cover this too (`request`).

    Two triggers arriving during one extract — two tasks finishing together — collapse
    into a single follow-up run: the code that changed is on disk by then, and the extract
    is incremental, so the second run has only the delta to do. ``preface`` is what the
    run's log opens with — a reset says there what it removed before the passes speak.
    """
    indexer = indexer_of(host)
    _number, must_run = request(indexer, reason, force=force, preface=preface, occasion=occasion)
    if must_run:
        await _drain(host, indexer)
    return indexer.status()


def remove_output(out: Path) -> str:
    """Delete graphify's output directory — graphs, manifests, every cache — for a reset.

    The one destructive thing the plugin does, so it is narrow: only a directory called
    ``graphify-out`` is ever removed, whatever path it is asked about. Windows may still
    hold a file of a process killed a moment ago, hence the retries.
    """
    if out.name != OUT_DIRNAME:
        raise ValueError(f"refusing to remove {out}: not a {OUT_DIRNAME} directory")
    if not out.exists():
        return "nothing to remove"
    last: OSError | None = None
    for attempt in range(5):
        try:
            shutil.rmtree(out)
            return "removed"
        except OSError as exc:
            last = exc
            time.sleep(0.4 * (attempt + 1))
    assert last is not None
    raise last


async def _reset_and_index(host: PluginHost) -> None:
    """Wait for the run being stopped to wind down, wipe the output, start over.

    Taking the lock is what waits: the killed extract ends its pass, ``index`` sees no
    follow-up queued and releases it. The removal happens under the lock, so no trigger
    can start a run on a half-deleted directory; the fresh run then takes the lock itself.
    """
    indexer = indexer_of(host)
    launch_root = host.launch_root
    out = launch_root / OUT_DIRNAME
    async with indexer.lock:
        try:
            removed = await asyncio.to_thread(remove_output, out)
        except (OSError, ValueError) as exc:
            indexer.error = f"reset: could not remove {out}: {exc}"
            indexer.log.append(f"[voxarium_studio] {indexer.error}")
            log.warning("graphify: %s", indexer.error)
            await host.publish("index", indexer.status())
            return
        host.state.pop("graph_index", None)
    note = f"[voxarium_studio] reset: {removed} {out} — graphs, manifests and every cache; reading everything again"
    log.info("graphify: %s", note)
    await index(host, "reset from the Graphify page — everything read again from scratch", preface=(note,))


async def _extract_once(
    host: PluginHost,
    indexer: Indexer,
    reason: str,
    *,
    force: bool = False,
    preface: tuple[str, ...] = (),
) -> None:
    """One run: the code pass, then — when it is on and the chosen CLI is there — the
    documents pass, on one bar. The code pass is what the graph cannot do without, so it goes first
    and a documents pass that fails leaves the code graph it just wrote standing."""
    settings = host.settings
    launch_root = host.launch_root
    indexer.reason = reason
    indexer.log.clear()
    indexer.log.extend(preface)
    indexer.error = None
    indexer.exit_code = None
    indexer.phase = None
    indexer.started_at = time.time()
    indexer.finished_at = None
    indexer.progress = Progress(0.0, "Starting")
    indexer.command = extract_command(settings, launch_root, force=force)

    with_documents = documents_enabled(settings)
    docs = documents_dir(host)
    runtime = documents_runtime(settings)
    prefix = documents_launcher(runtime) if with_documents else None
    skipped = None
    if with_documents and prefix is None:
        skipped = f"the `{runtime.command}` command is not on PATH — {runtime.hint}"
        with_documents = False
    elif with_documents and not documents_present(docs):
        skipped = f"there are no documents in {docs} yet"
        with_documents = False
    indexer.documents = indexer.documents_summary(enabled=documents_enabled(settings), skipped=skipped)

    resolved = resolve_executable(settings)
    if resolved is None:
        indexer.error = (
            f"{indexer.command[0]!r} is not on PATH — install it with `{INSTALL_HINT}`, or "
            "point the Graphify page at the executable."
        )
        indexer.finished_at = time.time()
        log.warning("graphify: %s", indexer.error)
        await host.publish("index", indexer.status())
        return

    indexer.running = True
    await host.publish("index", indexer.status())
    log.info("graphify: indexing %s (%s)", launch_root, reason)
    if force:
        removed = forget_previous_scan(launch_root / OUT_DIRNAME)
        if removed:
            indexer.log.append(f"[voxarium_studio] removed {', '.join(removed)} so every file is read again")
        # The documents graph keeps its semantic cache either way; only its gate goes.
        forget_previous_scan(documents_out(launch_root) / OUT_DIRNAME)
    if skipped:
        indexer.log.append(f"[voxarium_studio] documents not indexed: {skipped}")
    try:
        code_band = CODE_BAND_WITH_DOCUMENTS if with_documents else WHOLE_BAND
        command = extract_command(settings, launch_root, force=force)
        ok = await _run_pass(host, indexer, resolved, command, phase="code", band=code_band)
        if ok and with_documents:
            assert prefix is not None
            ok = await _documents_pass(host, indexer, resolved, runtime, prefix, docs)
        if ok:
            ok = await _combine(host, indexer, resolved)
        if ok:
            indexer.advance(Progress(100.0, "Done"))
    except asyncio.CancelledError:
        indexer.error = "cancelled"
        raise
    finally:
        indexer.running = False
        indexer.phase = None
        indexer.process = None
        indexer.finished_at = time.time()
        await host.publish("index", indexer.status())
        if indexer.error:
            log.warning("graphify: %s", indexer.error)
        else:
            log.info("graphify: index updated at %s", graph_file(launch_root))


async def _documents_pass(
    host: PluginHost,
    indexer: Indexer,
    resolved: str,
    runtime: runtimes.Runtime,
    prefix: list[str],
    docs: Path,
) -> bool:
    """The documents pass through ``runtime``; ``True`` when graphify exited 0."""
    settings = host.settings
    launch_root = host.launch_root
    language = documents_language(settings)
    model = documents_model(settings)
    indexer.log.append(
        f"[voxarium_studio] reading the documents in {docs} through {runtime.label}"
        + (f" ({model})" if model else " (its default model)")
        + ", labels "
        + ("in the document's own language" if language == "source" else "in English")
    )
    indexer.documents["ran"] = True
    # The pass runs *from* its out directory. For Claude Code that is what puts the
    # CLAUDE.md written there in front of `claude -p` as project memory; everything on the
    # command line is absolute, so graphify itself does not care where it runs.
    workdir = documents_out(launch_root)
    command = documents_command(settings, launch_root, docs)
    if runtime.native:
        write_language_memory(workdir, language)
        return await _run_pass(
            host, indexer, resolved, command, phase="documents", band=DOCUMENTS_BAND, cwd=workdir
        )
    workdir.mkdir(parents=True, exist_ok=True)
    endpoint = runtimes.DocumentsEndpoint(
        runtimes.runner_for(runtime, prefix, model),
        model=model,
        rule=language_rule(language),
        label=runtime.label,
        on_line=lambda line: indexer.log.append(f"[voxarium_studio] {line}"),
    )
    async with endpoint:
        indexer.log.append(
            f"[voxarium_studio] {runtime.label} answers graphify at {endpoint.base_url} for this pass"
        )
        ok = await _run_pass(
            host,
            indexer,
            resolved,
            command,
            phase="documents",
            band=DOCUMENTS_BAND,
            cwd=workdir,
            extra_env=endpoint.graphify_env(),
        )
    if not ok or endpoint.answered == 0:
        # graphify reports a missing SDK per chunk and may still exit 0 with nothing read;
        # the line names the package, and the fix is graphify's own extra.
        missing = next(
            (match.group(1) for line in indexer.log if (match := _MISSING_PACKAGE.search(line))), None
        )
        if missing:
            problem = (
                f"graphify lacks the '{missing}' package it needs to reach {runtime.label} through "
                f"the Studio — install it with `{OPENAI_EXTRA_HINT}`"
            )
            indexer.documents["error"] = problem
            indexer.error = f"documents: {problem}"
            indexer.log.append(f"[voxarium_studio] {problem}")
            return False
    return ok


async def _combine(host: PluginHost, indexer: Indexer, resolved: str) -> bool:
    """Keep the merged graph in step with the two it is made of, or take it away.

    With the documents on and their graph on disk — from this run, or from an earlier one
    when this run skipped them — the merged graph is rebuilt; it is cheap, two files read
    and one written. With the documents off it is removed, so that what agents and the
    search field fall back to is the code graph alone, not a merged file that still holds
    documents nobody asked for. The documents graph itself stays, for when they come back.
    """
    launch_root = host.launch_root
    merged = merged_graph_file(launch_root)
    if not documents_enabled(host.settings) or not documents_graph_file(launch_root).is_file():
        try:
            merged.unlink()
            indexer.log.append("[voxarium_studio] merged graph removed — the code graph stands alone")
            host.state.pop("graph_index", None)
        except FileNotFoundError:
            pass
        except OSError as exc:
            log.warning("graphify: could not remove %s: %s", merged, exc)
        return True
    indexer.log.append("[voxarium_studio] merging the code graph and the documents graph")
    if indexer.advance(Progress(97.0, "Merging code and documents")):
        await host.publish("index", indexer.status())
    command = merge_command(host.settings, launch_root)
    return await _run_pass(host, indexer, resolved, command, phase="merge", band=(97.0, 100.0))


async def _run_pass(
    host: PluginHost,
    indexer: Indexer,
    resolved: str,
    command: list[str],
    *,
    phase: str,
    band: tuple[float, float],
    cwd: Path | None = None,
    extra_env: dict[str, str] | None = None,
) -> bool:
    """Run one ``graphify extract`` and follow its output; ``True`` when it exited 0.

    The pass's own 0–100 lands inside ``band`` on the shared bar, and its stage names are
    prefixed with the pass when both passes share the run, so "Detecting files" says which
    detection it is. A failure is recorded where it belongs — on the run for the code
    pass, on the documents summary for that one — and the graph cache is dropped either
    way, since even a pass that failed late may have written the file.
    """
    settings = host.settings
    launch_root = cwd or host.launch_root
    label = {"documents": "Documents", "merge": "Merge"}.get(phase, "Code")
    both = band != WHOLE_BAND
    indexer.phase = phase
    indexer.command = command
    process: asyncio.subprocess.Process | None = None
    exit_code: int | None = None
    error: str | None = None
    try:
        process = await asyncio.create_subprocess_exec(
            resolved,
            *command[1:],
            cwd=str(launch_root),
            env={**subprocess_env(settings), **(extra_env or {})},
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.STDOUT,
        )
        indexer.process = process
        assert process.stdout is not None
        async for raw in process.stdout:
            line = raw.decode("utf-8", errors="replace").rstrip()
            if not line or _CLI_NOISE.match(line):
                continue
            indexer.log.append(line)
            frame = progress_from_line(line)
            if frame is None:
                continue
            if both and frame.stage:
                frame = Progress(frame.percent, f"{label}: {frame.stage}", frame.detail)
            if indexer.advance(frame.within(band)):
                # A frame per stage, one per hundred files inside the AST pass and one per
                # chunk of documents — rare enough to send the whole status each time.
                await host.publish("index", indexer.status())
        exit_code = await process.wait()
        if exit_code != 0:
            tail = next((line for line in reversed(indexer.log) if line.strip()), "")
            error = f"graphify extract exited with {exit_code}: {tail}"
        # The graph on disk changed, so the next search must not answer from the old one.
        host.state.pop("graph_index", None)
    except asyncio.CancelledError:
        if process is not None and process.returncode is None:
            process.kill()
            await process.wait()
        raise
    except OSError as exc:
        error = f"could not run {command[0]!r}: {exc}"
    finally:
        indexer.process = None
        indexer.exit_code = exit_code
        if phase == "documents":
            indexer.documents["exit_code"] = exit_code
            indexer.documents["error"] = error
            if error:
                indexer.error = f"documents: {error}"
        elif phase == "merge" and error:
            indexer.error = f"merge: {error}"
        elif error:
            indexer.error = error
    return error is None


# ------------------------------------------------------------------ hooks


async def on_project_opened(host: PluginHost) -> None:
    if _flag(host.settings, "index_on_open", True):
        await index(host, "project opened")


async def on_task_status_changed(host: PluginHost, event: Event) -> None:
    if event.data.get("status") != TASK_DONE:
        return
    if _flag(host.settings, "index_on_task_done", True):
        task_id = str(event.data.get("task_id") or "")
        await index(
            host,
            f"task {task_id} done".strip(),
            occasion=done_occasion(task_id) if task_id else None,
        )


# ------------------------------------------------------------------ the graph, for search

# Words in any script — Cyrillic as much as Latin — but not the underscore `\w` includes,
# so `resolve_launch_root` is three words and a query for one of them finds it.
_WORD = re.compile(r"[^\W_]+")
_LOCATION = re.compile(r"L(\d+)")


@dataclass(slots=True)
class GraphNode:
    label: str
    norm: str
    """The label lower-cased with a trailing ``()`` dropped — what a query is matched on."""

    words: tuple[str, ...]
    """The label split on case and punctuation, so ``launch`` finds ``resolve_launch_root``."""

    file: str | None
    line: int | None
    kind: str
    degree: int = 0
    repo: str | None = None
    """Which graph a merged node came from — ``merge-graphs`` stamps it. ``None`` in the
    plain code graph. It decides what ``file`` is relative to."""

    summary: str | None = None
    """The documents pass's one line about the node; code nodes have none."""


@dataclass(slots=True)
class GraphIndex:
    """``graph.json`` read once and kept until the file changes."""

    nodes: list[GraphNode]
    edge_count: int
    community_count: int
    mtime: float
    built_at_commit: str | None = None
    counts: Counter[str] = field(default_factory=Counter)
    path: Path | None = None
    """Which file this was read from — the merged graph or the code graph — so a switch
    between the two is a reload even when their mtimes happen to agree."""

    @property
    def node_count(self) -> int:
        return len(self.nodes)


def _split_words(label: str) -> tuple[str, ...]:
    """The label's words, CamelCase split — in whatever alphabet it is written.

    A regex range of `[a-z]`/`[A-Z]` would not see a Cyrillic case change, so the split is
    done by asking each character: a lower-case or digit followed by an upper-case letter
    is a boundary, in any script Unicode knows the case of.
    """
    pieces: list[str] = []
    previous = ""
    for char in label:
        if char.isupper() and previous and (previous.islower() or previous.isdigit()):
            pieces.append(" ")
        pieces.append(char)
        previous = char
    return tuple(word.casefold() for word in _WORD.findall("".join(pieces)))


def _node_kind(raw: dict[str, Any]) -> str:
    file_type = raw.get("file_type")
    if file_type == "rationale":
        return "note"
    if file_type in ("document", "paper", "concept"):
        # The documents pass names what it read: a document itself, or a concept — a
        # mechanic, a character, a system — the model found inside one.
        return str(file_type)
    if raw.get("_callable_class"):
        return "class"
    if raw.get("_callable"):
        return "function"
    return "symbol"


def _summary_of(raw: dict[str, Any]) -> str | None:
    """The one line the documents pass writes about a node — what a hit can say for itself."""
    rationale = raw.get("rationale")
    if not isinstance(rationale, str):
        return None
    text = " ".join(rationale.split())
    if not text:
        return None
    return text if len(text) <= 160 else text[:157].rstrip() + "…"


def _line_of(location: Any) -> int | None:
    match = _LOCATION.search(str(location or ""))
    return int(match.group(1)) if match else None


def load_graph(path: Path) -> GraphIndex:
    raw = json.loads(path.read_text(encoding="utf-8"))
    links = raw.get("links") or raw.get("edges") or []
    degree: Counter[str] = Counter()
    for link in links:
        degree[str(link.get("source"))] += 1
        degree[str(link.get("target"))] += 1
    nodes: list[GraphNode] = []
    communities: set[Any] = set()
    counts: Counter[str] = Counter()
    for item in raw.get("nodes") or []:
        label = str(item.get("label") or item.get("id") or "").strip()
        if not label:
            continue
        kind = _node_kind(item)
        counts[kind] += 1
        communities.add(item.get("community"))
        norm = label.casefold()
        if norm.endswith("()"):
            norm = norm[:-2]
        nodes.append(
            GraphNode(
                label=label,
                norm=norm,
                words=_split_words(label),
                file=str(item["source_file"]) if item.get("source_file") else None,
                line=_line_of(item.get("source_location")),
                kind=kind,
                degree=degree[str(item.get("id"))],
                repo=str(item["repo"]) if item.get("repo") else None,
                summary=_summary_of(item),
            )
        )
    return GraphIndex(
        nodes=nodes,
        edge_count=len(links),
        community_count=len(communities),
        mtime=path.stat().st_mtime,
        built_at_commit=raw.get("built_at_commit"),
        counts=counts,
        path=path,
    )


def score(node: GraphNode, full: str, terms: list[str]) -> float:
    """How well a node answers a query — a name match first, then how central it is.

    Names beat prose: a docstring that happens to contain the word is worth half of a
    symbol that *is* the word, and a match only in the one-line summary the documents pass
    wrote is worth less again — it is there so a query in the document's own words still
    finds the document. The degree tie-break puts ``AppConfig`` above a helper of the same
    name, since the hub is what somebody typing the name most likely wants.
    """
    norm = node.norm
    if norm == full:
        base = 100.0
    elif norm.startswith(full):
        base = 70.0
    elif any(word.startswith(full) for word in node.words):
        base = 50.0
    elif full in norm:
        base = 30.0
    else:
        hits = sum(1 for term in terms if term in norm)
        if hits:
            base = 12.0 * hits / len(terms)
        else:
            summary = node.summary.casefold() if node.summary else ""
            if not summary:
                return 0.0
            if full in summary:
                base = 8.0
            else:
                found = sum(1 for term in terms if term in summary)
                if not found:
                    return 0.0
                base = 4.0 * found / len(terms)
    if node.kind == "note":
        base *= 0.5
    return base + min(10.0, 2.0 * math.log1p(node.degree))


def search_index(index: GraphIndex, text: str, limit: int) -> list[tuple[GraphNode, float]]:
    full = text.strip().casefold()
    terms = [term for term in _WORD.findall(full) if term]
    if not full:
        return []
    scored = [(node, score(node, full, terms)) for node in index.nodes]
    ranked = [pair for pair in scored if pair[1] > 0]
    ranked.sort(key=lambda pair: (-pair[1], pair[0].label))
    return ranked[:limit]


def project_relative(host: PluginHost, source_file: str | None, repo: str | None = None) -> str | None:
    """The Explorer's path for a node's file, or ``None`` when the file is outside the project.

    graphify writes ``source_file`` relative to the folder it scanned: the scan path under
    the launch root for the code — and the launch root may sit above the project (§10.5) —
    or the Documents folder for a node the documents pass produced, which ``merge-graphs``
    marks with its ``repo``.
    """
    if not source_file:
        return None
    base = documents_dir(host) if repo == DOCUMENTS_TAG else host.launch_root / scan_path(host.settings)
    absolute = (base / source_file).resolve()
    try:
        return absolute.relative_to(host.project_root.resolve()).as_posix()
    except ValueError:
        return None


def _ago(seconds: float) -> str:
    seconds = max(0.0, seconds)
    if seconds < 60:
        return "just now"
    minutes = seconds / 60
    if minutes < 60:
        return f"{int(minutes)} min ago"
    hours = minutes / 60
    if hours < 48:
        return f"{int(hours)} h ago"
    return f"{int(hours / 24)} d ago"


async def graph_index(host: PluginHost) -> GraphIndex | None:
    """The cached index, reloaded when the graph it answers from has changed on disk."""
    path = effective_graph_file(host.launch_root)
    try:
        mtime = path.stat().st_mtime
    except OSError:
        host.state.pop("graph_index", None)
        return None
    cached = host.state.get("graph_index")
    if (
        cached is not None
        and getattr(cached, "mtime", None) == mtime
        and getattr(cached, "path", None) == path
    ):
        return cached
    # A large graph is a few megabytes of JSON; parsing it must not stall the event loop.
    try:
        loaded = await asyncio.to_thread(load_graph, path)
    except (OSError, ValueError) as exc:
        log.warning("graphify: cannot read %s: %s", path, exc)
        return None
    host.state["graph_index"] = loaded
    return loaded


async def ask_graph(host: PluginHost, question: str) -> tuple[str | None, str | None]:
    """``graphify query`` — the traversal answer, or a line saying why there is none."""
    settings = host.settings
    resolved = resolve_executable(settings)
    if resolved is None:
        return None, f"{executable(settings)!r} is not on PATH — {INSTALL_HINT}"
    graph = effective_graph_file(host.launch_root)
    command = query_command(settings, question, graph)
    try:
        process = await asyncio.create_subprocess_exec(
            resolved,
            *command[1:],
            cwd=str(host.launch_root),
            env=subprocess_env(settings),
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE,
        )
    except OSError as exc:
        return None, f"could not run {command[0]!r}: {exc}"
    try:
        out, err = await asyncio.wait_for(process.communicate(), timeout=QUERY_TIMEOUT)
    except asyncio.TimeoutError:
        process.kill()
        await process.wait()
        return None, f"graphify query gave no answer within {int(QUERY_TIMEOUT)} s"
    except asyncio.CancelledError:
        process.kill()
        await process.wait()
        raise
    stdout = out.decode("utf-8", errors="replace")
    stderr = "\n".join(
        line
        for line in err.decode("utf-8", errors="replace").splitlines()
        if line.strip() and not _CLI_NOISE.match(line)
    )
    if process.returncode != 0:
        return None, stderr.strip() or f"graphify query exited with {process.returncode}"
    answer = "\n".join(
        line for line in stdout.splitlines() if not _CLI_NOISE.match(line)
    ).strip()
    return (answer or None), None


async def search_code(host: PluginHost, query: SearchQuery) -> SearchResponse:
    index = await graph_index(host)
    if index is None:
        return SearchResponse(
            note="No code graph yet — index the project on the Graphify page.",
        )
    results = [
        SearchResult(
            title=node.label if node.kind != "note" else node.label[:160],
            subtitle=(
                f"{node.file}:{node.line}"
                if node.file and node.line
                else node.file
            ),
            path=project_relative(host, node.file, node.repo),
            line=node.line,
            snippet=node.summary,
            kind=node.kind,
            score=round(value, 1),
        )
        for node, value in search_index(index, query.text, query.limit)
    ]
    note = f"{index.node_count} nodes · {index.edge_count} edges · indexed {_ago(time.time() - index.mtime)}"
    response = SearchResponse(results=results, note=note)
    if query.deep:
        answer, problem = await ask_graph(host, query.text)
        response.answer = answer
        if problem:
            response.note = f"{note} · {problem}"
    return response


# ------------------------------------------------------------------ actions


def graph_status(host: PluginHost) -> dict[str, Any]:
    launch_root = host.launch_root
    path = effective_graph_file(launch_root)
    try:
        stat = path.stat()
    except OSError:
        return {"path": str(path), "exists": False}
    info: dict[str, Any] = {
        "path": str(path),
        "exists": True,
        "merged": path == merged_graph_file(launch_root),
        "code_graph": str(graph_file(launch_root)),
        "documents_graph": str(documents_graph_file(launch_root)),
        "documents_graph_exists": documents_graph_file(launch_root).is_file(),
        "modified_at": stat.st_mtime,
        "size": stat.st_size,
    }
    index = host.state.get("graph_index")
    if index is not None and getattr(index, "mtime", None) == stat.st_mtime:
        info.update(
            nodes=index.node_count,
            edges=index.edge_count,
            communities=index.community_count,
            kinds=dict(index.counts),
            built_at_commit=index.built_at_commit,
        )
    return info


async def action_status(host: PluginHost, _payload: dict[str, Any]) -> dict[str, Any]:
    """Everything the settings page shows: the CLI, the graph, the last run."""
    settings = host.settings
    runtime = documents_runtime(settings)
    index = await graph_index(host)
    del index  # loaded for the counts `graph_status` reads from the cache
    return {
        "executable": executable(settings),
        "resolved": resolve_executable(settings),
        "install_hint": INSTALL_HINT,
        "launch_root": str(host.launch_root),
        "scan_path": scan_path(settings),
        "command": extract_command(settings, host.launch_root),
        "documents_dir": str(documents_dir(host)),
        "documents_command": documents_command(settings, host.launch_root, documents_dir(host)),
        "merge_command": merge_command(settings, host.launch_root),
        "documents": documents_enabled(settings),
        "documents_backend": runtime.id,
        "documents_backend_label": runtime.label,
        "documents_model": documents_model(settings),
        "documents_language": documents_language(settings),
        "documents_memory": str(documents_out(host.launch_root) / "CLAUDE.md") if runtime.native else None,
        "documents_endpoint": not runtime.native,
        "documents_extra_hint": None if runtime.native else OPENAI_EXTRA_HINT,
        "documents_cli": documents_cli(settings),
        "documents_cli_command": runtime.command,
        "documents_cli_hint": runtime.hint,
        "claude": claude_executable(),
        "claude_hint": CLAUDE_HINT,
        "index_on_open": _flag(settings, "index_on_open", True),
        "index_on_task_done": _flag(settings, "index_on_task_done", True),
        "graph": graph_status(host),
        "indexer": indexer_of(host).status(),
    }


async def action_index(host: PluginHost, payload: dict[str, Any]) -> dict[str, Any]:
    """Start (or queue) a rebuild and return at once; progress arrives as events.

    ``{"force": true}`` re-reads every file instead of only what changed — the page's
    "Rebuild all", for a graph that has gone stale under a refactor.
    """
    force = payload.get("force", False)
    if not isinstance(force, bool):
        raise ValueError("force must be a boolean")
    reason = "rebuild of everything from the Graphify page" if force else "requested from the Graphify page"
    host.spawn(index(host, reason, force=force), name="graphify:index")
    # Let the spawned task reach the lock before answering, so the page sees `running`.
    await asyncio.sleep(0)
    return indexer_of(host).status()


async def action_cancel(host: PluginHost, _payload: dict[str, Any]) -> dict[str, Any]:
    """Stop the running extract and drop what was waiting for one — the queued follow-up and
    a run asked for but not begun. An automation waiting on a dropped run hears it was
    cancelled (`wait_for_run`)."""
    indexer = indexer_of(host)
    process = indexer.process
    if process is not None and process.returncode is None:
        process.kill()
    indexer.pending = None
    indexer.pending_force = False
    indexer.pending_occasions = set()
    indexer.claimed = None
    return indexer.status()


async def action_reset(host: PluginHost, _payload: dict[str, Any]) -> dict[str, Any]:
    """Stop whatever runs, wipe ``graphify-out/`` at the launch root, index from scratch.

    Everything goes: the code graph, the documents graph and its semantic cache — so every
    document is read through the chosen agent CLI again, which is what the page's warning says —
    the merged graph, the manifests, the AST cache. The answer is immediate; the removal
    and the fresh run follow in the background and report as events like any run.
    """
    indexer = indexer_of(host)
    indexer.pending = None
    indexer.pending_force = False
    indexer.pending_occasions = set()
    process = indexer.process
    if process is not None and process.returncode is None:
        process.kill()
    host.spawn(_reset_and_index(host), name="graphify:reset")
    await asyncio.sleep(0)
    return indexer.status()


async def automation_update(host: PluginHost, trigger: dict[str, Any]) -> str:
    """*Graphify Update* on the Automations page (§6.5): the same incremental run as Index now,
    and the firing lasts until **the run that covers it** has ended — the automation's log
    shows it in progress until the graph is actually up to date.

    That run is the first to begin after the request (`request`): the one it starts, the next
    one when an extract is already going, or — for a task reaching Done, which this plugin's
    own *index when a task is done* setting reacts to as well — the one that began for that
    same occasion, which it joins instead of queueing a second index behind it. The loop is the
    plugin's (`host.spawn`), so a firing that stops waiting stops nothing.
    """
    indexer = indexer_of(host)
    values = trigger.get("values") or {}
    occasion = None
    task_id = str(values.get("task_id") or "")
    if trigger.get("trigger") == "task.status_changed" and values.get("status") == TASK_DONE and task_id:
        occasion = done_occasion(task_id)
    name = (trigger.get("automation") or {}).get("name") or "an automation"
    number, must_run = request(indexer, f"automation: {name}", occasion=occasion)
    if must_run:
        host.spawn(_drain(host, indexer), name="graphify:index")
    outcome = await wait_for_run(indexer, number)
    if outcome is None:
        raise AutomationCancelled("The update was cancelled before it ran")
    if outcome["error"]:
        raise RuntimeError(outcome["error"])
    return str(outcome["summary"])


# ------------------------------------------------------------------ registration


def register(registry: PluginRegistry) -> None:
    registry.on_project_opened(on_project_opened)
    registry.on_event("team.task.status_changed", on_task_status_changed)
    registry.add_action("status", action_status)
    registry.add_action("index", action_index)
    registry.add_action("cancel", action_cancel)
    registry.add_action("reset", action_reset)
    registry.add_search_provider("code", search_code, label="Code graph")
    registry.add_automation_action(
        "update",
        automation_update,
        label="Graphify Update",
        description="Re-index what changed: the code graph, then the documents.",
    )
