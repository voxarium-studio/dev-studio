"""The graphify plugin, without graphify (§9.7).

The CLI is a `uv tool` in its own environment, so what is covered here is everything on
this side of the process boundary: the command line the plugin builds and where it runs
it, how a running extract and a second request behave, how ``graph.json`` is read and
ranked for the search field, and how a missing or failing CLI is reported. The one thing
not covered is whether a given graphify build accepts the flags — that needs the CLI, and
the two flags used (``--code-only``, ``--out``) are checked against 0.9.49's own help.
"""

from __future__ import annotations

import asyncio
import importlib.util
import json
import sys
from pathlib import Path
from types import SimpleNamespace

import pytest
from fastapi.testclient import TestClient

from voxarium_studio.plugin_api import SearchQuery
from voxarium_studio.plugins.registry import Registry
from voxarium_studio.teams.orchestrator import code_graph_file
from voxarium_studio.ws.bus import Event

PLUGIN_ROOT = Path(__file__).resolve().parent.parent / "plugins_builtin" / "graphify"


def _load(module_name: str, file_name: str):
    spec = importlib.util.spec_from_file_location(module_name, PLUGIN_ROOT / file_name)
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    # As the loader does: the plugin root is on sys.path while the entry module executes,
    # so its sibling `graphify_runtimes` imports.
    sys.path.insert(0, str(PLUGIN_ROOT))
    try:
        spec.loader.exec_module(module)
    finally:
        sys.path.remove(str(PLUGIN_ROOT))
    return module


graphify = _load("voxarium_studio_test_graphify_plugin", "plugin.py")
runtimes = graphify.runtimes


# --------------------------------------------------------------------------- a fake host


class FakeHost:
    """What the context would hand over, minus the context."""

    def __init__(self, project_root: Path, *, launch_root: Path | None = None, settings=None):
        self.plugin_id = "graphify"
        self.project_root = project_root
        self.data_dir = project_root / ".voxarium_studio"
        self.launch_root = launch_root or project_root
        self.settings = dict(settings or {})
        self.state: dict = {}
        self.events: list[tuple[str, dict]] = []
        self.tasks: list[asyncio.Task] = []

    async def publish(self, name: str, data=None) -> None:
        self.events.append((name, dict(data or {})))

    def spawn(self, coro, *, name: str = ""):
        task = asyncio.get_running_loop().create_task(coro, name=name)
        self.tasks.append(task)
        return task


NODES = [
    {
        "id": "config_appconfig",
        "label": "AppConfig",
        "_callable": True,
        "_callable_class": True,
        "file_type": "code",
        "community": 0,
        "source_file": "config.py",
        "source_location": "L388",
    },
    {
        "id": "config_resolve_launch_root",
        "label": "resolve_launch_root()",
        "_callable": True,
        "file_type": "code",
        "community": 0,
        "source_file": "config.py",
        "source_location": "L243",
    },
    {
        "id": "run_launch",
        "label": "launch()",
        "_callable": True,
        "file_type": "code",
        "community": 1,
        "source_file": "sub/run.py",
        "source_location": "L10",
    },
    {
        "id": "config_rationale_244",
        "label": "The absolute directory a launch root names.",
        "file_type": "rationale",
        "community": 0,
        "source_file": "config.py",
        "source_location": "L244",
    },
]
LINKS = [
    {"source": "config_appconfig", "target": "config_resolve_launch_root", "relation": "calls"},
    {"source": "config_resolve_launch_root", "target": "config_rationale_244", "relation": "rationale_for"},
]


DOC_NODES = [
    {
        "id": "documents::features_dash_dash_ability",
        "label": "Dash ability",
        "file_type": "document",
        "community": 0,
        "source_file": "features/dash.md",
        "repo": "documents",
        "rationale": "Cooldown mechanic (2s, cannot be cancelled)   with i-frames; uses the ability budget",
    },
    {
        "id": "documents::ability_budget",
        "label": "Ability Resource Budget",
        "file_type": "concept",
        "community": 0,
        "source_file": "features/dash.md",
        "repo": "documents",
    },
]


def write_graph(launch_root: Path, nodes=NODES, links=LINKS, *, path: Path | None = None) -> Path:
    path = path or graphify.graph_file(launch_root)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps({"directed": False, "nodes": nodes, "links": links, "built_at_commit": "abc1234def"}),
        encoding="utf-8",
    )
    return path


# --------------------------------------------------------------------------- the command


def test_the_extract_command_is_code_only_and_pins_the_graph_to_the_launch_root(tmp_path: Path) -> None:
    """`--code-only` is what makes the index free — local AST, no model, no key — and
    `--out` is what puts the graph where an agent session starts, whatever is scanned."""
    assert graphify.extract_command({}, tmp_path) == [
        "graphify",
        "extract",
        ".",
        "--code-only",
        "--out",
        str(tmp_path),
    ]


def test_a_forced_rebuild_drops_the_incremental_gate(tmp_path: Path) -> None:
    assert graphify.extract_command({}, tmp_path, force=True)[-1] == "--force"
    assert "--force" not in graphify.extract_command({}, tmp_path)


def test_settings_choose_the_executable_and_the_scan_path(tmp_path: Path) -> None:
    command = graphify.extract_command({"executable": " C:/tools/graphify.exe ", "path": "src"}, tmp_path)
    assert command[:3] == ["C:/tools/graphify.exe", "extract", "src"]
    assert graphify.extract_command({"executable": "", "path": "  "}, tmp_path)[:3] == ["graphify", "extract", "."]


def test_boolean_settings_read_what_a_page_may_have_saved() -> None:
    assert graphify._flag({}, "index_on_open", True) is True
    assert graphify._flag({"index_on_open": False}, "index_on_open", True) is False
    assert graphify._flag({"index_on_open": "false"}, "index_on_open", True) is False
    assert graphify._flag({"index_on_open": "yes"}, "index_on_open", False) is True


# --------------------------------------------------------------------------- the graph


def test_the_graph_is_read_into_names_kinds_and_lines(tmp_path: Path) -> None:
    index = graphify.load_graph(write_graph(tmp_path))

    assert index.node_count == 4 and index.edge_count == 2 and index.community_count == 2
    assert index.built_at_commit == "abc1234def"
    assert dict(index.counts) == {"class": 1, "function": 2, "note": 1}

    by_label = {node.label: node for node in index.nodes}
    assert by_label["AppConfig"].kind == "class" and by_label["AppConfig"].line == 388
    assert by_label["resolve_launch_root()"].norm == "resolve_launch_root"
    assert by_label["resolve_launch_root()"].words == ("resolve", "launch", "root")
    assert by_label["resolve_launch_root()"].degree == 2, "one call in, one rationale out"
    assert by_label["launch()"].file == "sub/run.py"


def test_labels_in_any_alphabet_are_found_by_their_words(tmp_path: Path) -> None:
    """A Russian design document gets Russian labels; the tokenizer must not be ASCII."""
    nodes = [
        {"id": "hist_toa", "label": "Тоа Норрингтон (Toa Norrington)", "file_type": "concept", "community": 0,
         "source_file": "history.md", "rationale": "Семнадцатилетний протагонист; брат Сары"},
        {"id": "hist_complex", "label": "КомплексА0", "file_type": "concept", "community": 0,
         "source_file": "history.md"},
        {"id": "hist_sc23", "label": "Образец SC-23", "file_type": "concept", "community": 0,
         "source_file": "history.md", "rationale": "Минерал с планеты BW-112R, заражает носителей"},
    ]
    index = graphify.load_graph(write_graph(tmp_path, nodes, []))
    by_label = {node.label: node for node in index.nodes}
    assert by_label["Тоа Норрингтон (Toa Norrington)"].words == ("тоа", "норрингтон", "toa", "norrington")
    assert by_label["КомплексА0"].words == ("комплекс", "а0"), "CamelCase splits in Cyrillic too"

    def found(query: str) -> list[str]:
        return [node.label for node, _ in graphify.search_index(index, query, 5)]

    assert found("норрингтон") == ["Тоа Норрингтон (Toa Norrington)"], "a word of the label, in Russian"
    assert found("Norrington") == ["Тоа Норрингтон (Toa Norrington)"], "and the same word in English"
    assert found("ТОА") == ["Тоа Норрингтон (Toa Norrington)"], "case-folded"
    assert found("sc-23") == ["Образец SC-23"]
    assert found("сары") == ["Тоа Норрингтон (Toa Norrington)"], "a word found only in the summary"
    assert found("планеты bw") == ["Образец SC-23"], "several words, all in the summary"
    assert found("морковь") == []


def test_names_rank_above_prose_and_exact_above_partial(tmp_path: Path) -> None:
    index = graphify.load_graph(write_graph(tmp_path))

    labels = [node.label for node, _ in graphify.search_index(index, "launch", 10)]
    assert labels == [
        "launch()",  # the name itself
        "resolve_launch_root()",  # a word of the name
        "The absolute directory a launch root names.",  # prose mentioning it, last
    ]
    assert [n.label for n, _ in graphify.search_index(index, "AppConfig", 5)] == ["AppConfig"]
    assert [n.label for n, _ in graphify.search_index(index, "app config", 5)] == ["AppConfig"], (
        "words of a CamelCase name match separately"
    )
    assert graphify.search_index(index, "   ", 5) == []
    assert graphify.search_index(index, "zzz", 5) == []
    assert len(graphify.search_index(index, "launch", 1)) == 1, "the limit is honoured"


async def test_search_answers_from_the_graph_at_the_launch_root(tmp_path: Path, monkeypatch) -> None:
    write_graph(tmp_path)
    host = FakeHost(tmp_path)

    response = await graphify.search_code(host, SearchQuery(text="launch", limit=5))
    first = response.results[0]
    assert (first.title, first.path, first.line, first.kind) == ("launch()", "sub/run.py", 10, "function")
    assert first.subtitle == "sub/run.py:10"
    assert response.note.startswith("4 nodes · 2 edges · indexed ")
    assert response.answer is None, "a keystroke does not run the CLI"
    assert "graph_index" in host.state, "parsed once, kept until the file changes"

    # Enter asks the CLI, and its absence is a line in the note rather than a failure.
    monkeypatch.setattr(graphify, "resolve_executable", lambda settings: None)
    deep = await graphify.search_code(host, SearchQuery(text="launch", deep=True))
    assert deep.answer is None
    assert "not on PATH" in deep.note and deep.results[0].title == "launch()"


async def test_search_without_a_graph_says_so(tmp_path: Path) -> None:
    response = await graphify.search_code(FakeHost(tmp_path), SearchQuery(text="anything"))
    assert response.results == []
    assert response.note.startswith("No code graph yet")


async def test_a_rewritten_graph_is_read_again(tmp_path: Path) -> None:
    host = FakeHost(tmp_path)
    write_graph(tmp_path)
    assert len((await graphify.search_code(host, SearchQuery(text="launch"))).results) == 3

    path = write_graph(tmp_path, nodes=NODES[:1], links=[])
    # Same second on a coarse filesystem clock would hide the change; make it unmistakable.
    import os

    stat = path.stat()
    os.utime(path, (stat.st_atime, stat.st_mtime + 5))
    assert (await graphify.search_code(host, SearchQuery(text="launch"))).results == []


def test_hits_map_to_explorer_paths_only_inside_the_project(tmp_path: Path) -> None:
    """graphify's paths are relative to what it scanned; the Explorer's are relative to
    the project — and the launch root may sit above the project (§10.5)."""
    project = tmp_path / "game"
    project.mkdir()
    above = FakeHost(project, launch_root=tmp_path)
    assert graphify.project_relative(above, "game/src/a.py") == "src/a.py"
    assert graphify.project_relative(above, "engine/b.py") is None, "a sibling is not the project's"
    assert graphify.project_relative(above, None) is None

    scanned = FakeHost(project, settings={"path": "src"})
    assert graphify.project_relative(scanned, "a.py") == "src/a.py", "the scan path is prepended"


# --------------------------------------------------------------------------- indexing


class FakeStream:
    def __init__(self, lines: list[str], gate: asyncio.Event | None = None) -> None:
        self._lines = [line.encode() for line in lines]
        self._gate = gate

    def __aiter__(self):
        return self

    async def __anext__(self) -> bytes:
        if self._gate is not None:
            await self._gate.wait()
        if not self._lines:
            raise StopAsyncIteration
        await asyncio.sleep(0)
        return self._lines.pop(0)


class FakeProcess:
    def __init__(self, lines: list[str], code: int = 0, gate: asyncio.Event | None = None) -> None:
        self.stdout = FakeStream(lines, gate)
        self.returncode: int | None = None
        self._code = code
        self.killed = False

    async def wait(self) -> int:
        self.returncode = self._code
        return self._code

    def kill(self) -> None:
        self.killed = True
        self.returncode = -9


@pytest.fixture
def cli(monkeypatch):
    """A graphify that is found and answers with a scripted process, every call recorded."""
    calls: list[dict] = []
    script: dict = {"lines": ["[graphify extract] scanning", "wrote graph.json: 3 nodes"], "code": 0, "gate": None}

    async def fake_exec(program, *args, **kwargs):
        # `queue` scripts one answer per call, in order — a code pass and a documents pass
        # differ; without it every call answers with `lines` and `code`.
        queue = script.get("queue") or []
        queued = queue[len(calls)] if len(calls) < len(queue) else {}
        process = FakeProcess(
            list(queued.get("lines", script["lines"])),
            queued.get("code", script["code"]),
            script["gate"],
        )
        calls.append(
            {
                "program": program,
                "args": list(args),
                "cwd": kwargs.get("cwd"),
                "env": kwargs.get("env") or {},
                "process": process,
            }
        )
        return process

    monkeypatch.setattr(graphify, "resolve_executable", lambda settings: "C:/fake/graphify.exe")
    # No `claude` unless a test says so: the documents pass is then skipped, and every
    # `index` is exactly one run — which is what the tests written before it expect.
    monkeypatch.setattr(graphify, "claude_executable", lambda: None)
    monkeypatch.setattr(asyncio, "create_subprocess_exec", fake_exec)
    return SimpleNamespace(calls=calls, script=script)


@pytest.fixture
def claude(monkeypatch):
    """A `claude` on PATH, so the documents pass runs."""
    monkeypatch.setattr(graphify, "claude_executable", lambda: "C:/fake/claude.cmd")
    return "C:/fake/claude.cmd"


async def test_indexing_runs_the_cli_at_the_launch_root_and_keeps_its_log(tmp_path: Path, cli) -> None:
    host = FakeHost(tmp_path, settings={"path": "src"})
    status = await graphify.index(host, "project opened")

    call = cli.calls[0]
    assert call["program"] == "C:/fake/graphify.exe"
    assert call["args"] == ["extract", "src", "--code-only", "--out", str(tmp_path)]
    assert call["cwd"] == str(tmp_path)
    # Unbuffered, or the stage lines a Python child prints into a pipe arrive together at
    # exit and the bar sits at 0% until it jumps to 100%.
    assert call["env"]["PYTHONUNBUFFERED"] == "1"
    assert status["exit_code"] == 0 and status["error"] is None and status["running"] is False
    assert status["reason"] == "project opened"
    assert status["log"][0].startswith("[voxarium_studio] documents not indexed: the `claude` command")
    assert status["log"][1:] == ["[graphify extract] scanning", "wrote graph.json: 3 nodes"]
    assert {name for name, _ in host.events} == {"index"}
    assert host.events[0][1]["running"] is True and host.events[-1][1]["running"] is False
    assert status["progress"] == {"percent": 100.0, "stage": "Done", "detail": None}
    assert status["documents"]["skipped"].startswith("the `claude` command is not on PATH")
    assert status["documents"]["ran"] is False and status["documents"]["enabled"] is True


def test_the_child_is_not_a_nested_claude_session(monkeypatch) -> None:
    """A Studio started from a Claude Code terminal inherits CLAUDECODE, and a `claude -p`
    inside the documents pass would refuse to start under it."""
    monkeypatch.setenv("CLAUDECODE", "1")
    monkeypatch.setenv("CLAUDE_CODE_ENTRYPOINT", "cli")
    env = graphify.subprocess_env({"documents_model": "sonnet"})
    assert "CLAUDECODE" not in env and "CLAUDE_CODE_ENTRYPOINT" not in env
    assert env["GRAPHIFY_CLAUDE_CLI_MODEL"] == "sonnet"
    assert graphify.subprocess_env({})["GRAPHIFY_CLAUDE_CLI_MODEL"] == "haiku", "the default model"


# --------------------------------------------------------------------------- documents


def test_the_documents_command_scans_the_documents_folder_into_its_own_graph(tmp_path: Path) -> None:
    """A different scan root writing into the code's graphify-out would prune the code as
    "deleted", so the documents get an out directory of their own — named for the repo tag
    merge-graphs will stamp on their nodes."""
    docs = tmp_path / ".voxarium_studio" / "docs"
    command = graphify.documents_command({}, tmp_path, docs)
    assert command[:7] == [
        "graphify",
        "extract",
        str(docs),
        "--out",
        str(tmp_path / "graphify-out" / "documents"),
        "--backend",
        "claude-cli",
    ]
    assert "--code-only" not in command and "--force" not in command
    excluded = [command[i + 1] for i, arg in enumerate(command) if arg == "--exclude"]
    assert {"*.png", "*.jpg", "*.pdf", "*.yaml", "*.html"} <= set(excluded), "pictures and structured files"
    assert {"vxs-folder.json", "tds-folder.json"} <= set(excluded), "folder styles are Studio metadata"
    assert "*.md" not in excluded and "*.txt" not in excluded
    assert graphify.documents_graph_file(tmp_path) == tmp_path / "graphify-out" / "documents" / "graphify-out" / "graph.json"
    assert graphify.merge_command({}, tmp_path) == [
        "graphify",
        "merge-graphs",
        str(tmp_path / "graphify-out" / "graph.json"),
        str(graphify.documents_graph_file(tmp_path)),
        "--out",
        str(tmp_path / "graphify-out" / "merged-graph.json"),
    ]


def with_documents(project: Path) -> Path:
    docs = project / ".voxarium_studio" / "docs" / "features"
    docs.mkdir(parents=True, exist_ok=True)
    (docs / "dash.md").write_text("# Dash ability\n", encoding="utf-8")
    return project / ".voxarium_studio" / "docs"


async def test_documents_follow_the_code_pass_on_one_bar(tmp_path: Path, cli, claude) -> None:
    docs = with_documents(tmp_path)
    write_graph(tmp_path, DOC_NODES, [], path=graphify.documents_graph_file(tmp_path))
    cli.script["queue"] = [
        {
            "lines": [
                "[graphify extract] scanning D:/game",
                "[graphify extract] found 200 code, 8 docs, 0 papers, 0 images",
                "[graphify extract] AST extraction on 200 code files...",
                "  AST extraction: 200/200 uncached files (100%) [4 workers]",
                "[graphify extract] wrote D:/game/graphify-out/graph.json: 9 nodes, 8 edges, 1 communities",
                "[graphify extract] next: run `graphify cluster-only D:/game`",
            ]
        },
        {
            "lines": [
                "[graphify extract] incremental scan of D:/game",
                "[graphify extract] 0 code, 8 docs, 0 papers, 0 images changed; 200 unchanged; 0 deleted",
                "[graphify extract] semantic cache: 5 hit / 3 miss",
                "[graphify extract] semantic extraction on 3 files via claude-cli...",
                "[graphify extract] chunk 1/2 done",
                "[graphify extract] chunk 2/2 done",
                "[graphify extract] wrote D:/game/graphify-out/graph.json: 40 nodes, 60 edges, 3 communities",
                "[graphify extract] incremental summary: 200 files cached/unchanged, 8 re-extracted, 0 deleted",
            ]
        },
    ]
    host = FakeHost(tmp_path, settings={"documents_model": "sonnet"})

    status = await graphify.index(host, "task done")

    assert [call["args"][0] for call in cli.calls] == ["extract", "extract", "merge-graphs"]
    assert cli.calls[0]["args"][1] == "." and "--code-only" in cli.calls[0]["args"]
    assert cli.calls[1]["args"][1:3] == [str(docs), "--out"]
    assert cli.calls[1]["args"][4:6] == ["--backend", "claude-cli"]
    assert cli.calls[1]["env"]["GRAPHIFY_CLAUDE_CLI_MODEL"] == "sonnet"
    # The pass runs from its out directory, where the language rule sits as project memory
    # for the `claude -p` graphify launches.
    workdir = graphify.documents_out(tmp_path)
    assert cli.calls[1]["cwd"] == str(workdir) and cli.calls[0]["cwd"] == str(tmp_path)
    memory = (workdir / "CLAUDE.md").read_text(encoding="utf-8")
    assert "in the language the source document is written in" in memory
    assert "Тоа Норрингтон (Toa Norrington)" in memory
    assert cli.calls[2]["args"][-2:] == ["--out", str(graphify.merged_graph_file(tmp_path))]
    assert status["documents"] == {"enabled": True, "ran": True, "skipped": None, "exit_code": 0, "error": None}
    assert status["error"] is None and status["progress"]["percent"] == 100.0

    frames = [data["progress"] for _, data in host.events]
    percents = [frame["percent"] for frame in frames]
    assert percents == sorted(percents), "one bar, never backwards"
    code_frames = [f for f in frames if f["stage"] and f["stage"].startswith("Code:")]
    doc_frames = [f for f in frames if f["stage"] and f["stage"].startswith("Documents:")]
    assert code_frames and max(f["percent"] for f in code_frames) <= 40.0
    assert doc_frames and min(f["percent"] for f in doc_frames) >= 40.0
    assert doc_frames[-3]["stage"] == "Documents: Reading documents" and doc_frames[-3]["detail"] == "2/2 chunks"
    assert any(f["stage"] == "Merging code and documents" for f in frames)
    assert any(
        line.startswith(f"[voxarium_studio] reading the documents in {docs} through Claude Code")
        and line.endswith("in the document's own language")
        for line in status["log"]
    )
    assert "[voxarium_studio] merging the code graph and the documents graph" in status["log"]


async def test_the_language_rule_can_ask_for_english_instead(tmp_path: Path, cli, claude) -> None:
    with_documents(tmp_path)
    await graphify.index(FakeHost(tmp_path, settings={"documents_language": "English"}), "test")
    memory = (graphify.documents_out(tmp_path) / "CLAUDE.md").read_text(encoding="utf-8")
    assert "in English, whatever language the document is written in" in memory
    assert graphify.documents_language({"documents_language": "klingon"}) == "source", "unknown means the default"


async def test_documents_can_be_switched_off(tmp_path: Path, cli, claude) -> None:
    with_documents(tmp_path)
    merged = graphify.merged_graph_file(tmp_path)
    write_graph(tmp_path, path=merged)
    status = await graphify.index(FakeHost(tmp_path, settings={"documents": False}), "test")
    assert len(cli.calls) == 1
    assert status["documents"]["enabled"] is False and status["documents"]["skipped"] is None
    assert status["progress"]["percent"] == 100.0, "the code pass has the whole bar to itself"
    assert not merged.exists(), "what agents read falls back to the code graph alone"


async def test_no_documents_yet_is_a_skip_not_a_failure(tmp_path: Path, cli, claude) -> None:
    (tmp_path / ".voxarium_studio" / "docs").mkdir(parents=True)
    status = await graphify.index(FakeHost(tmp_path), "test")
    assert len(cli.calls) == 1
    assert status["documents"]["skipped"].startswith("there are no documents in")
    assert status["error"] is None and status["progress"]["percent"] == 100.0


async def test_an_older_documents_graph_is_still_merged_when_the_pass_is_skipped(tmp_path: Path, cli) -> None:
    """No `claude` today does not unmake yesterday's reading of the documents."""
    write_graph(tmp_path, DOC_NODES, [], path=graphify.documents_graph_file(tmp_path))
    status = await graphify.index(FakeHost(tmp_path), "test")
    assert [call["args"][0] for call in cli.calls] == ["extract", "merge-graphs"]
    assert status["documents"]["skipped"].startswith("the `claude` command")


async def test_a_failing_documents_pass_keeps_the_code_graph(tmp_path: Path, cli, claude) -> None:
    with_documents(tmp_path)
    cli.script["queue"] = [
        {"lines": ["[graphify extract] wrote D:/game/graphify-out/graph.json: 9 nodes, 8 edges, 1 communities"]},
        {"lines": ["error: backend 'claude-cli' requires the `claude` CLI on $PATH"], "code": 1},
    ]
    status = await graphify.index(FakeHost(tmp_path), "test")
    assert len(cli.calls) == 2, "no merge after a failed pass"
    assert status["documents"]["ran"] is True and status["documents"]["exit_code"] == 1
    assert status["documents"]["error"].startswith("graphify extract exited with 1")
    assert status["error"].startswith("documents: "), "named as the documents pass's, not the run's"
    assert status["progress"]["percent"] < 100.0


async def test_a_failing_code_pass_never_reaches_the_documents(tmp_path: Path, cli, claude) -> None:
    with_documents(tmp_path)
    cli.script.update(lines=["error: path not found"], code=1)
    status = await graphify.index(FakeHost(tmp_path), "test")
    assert len(cli.calls) == 1
    assert status["documents"]["ran"] is False and status["error"].startswith("graphify extract exited")


# --------------------------------------------------------------------------- the documents' runtime


def test_an_old_setting_with_only_a_model_reads_as_claude_code() -> None:
    """Before the page could pick a backend it saved a model alone, and that model was a
    Claude Code one; nothing at all is the old default, Claude Code with haiku."""
    assert graphify.documents_backend({}) == "claude_code"
    assert graphify.documents_model({}) == "haiku"
    assert graphify.documents_backend({"documents_model": "sonnet"}) == "claude_code"
    assert graphify.documents_model({"documents_model": "sonnet"}) == "sonnet"
    assert graphify.documents_backend({"documents_backend": "klingon"}) == "claude_code", "unknown means the default"
    assert graphify.documents_backend({"documents_backend": " Codex "}) == "codex"


def test_codex_and_qwen_default_to_their_own_configured_model() -> None:
    assert graphify.documents_model({"documents_backend": "codex"}) == ""
    assert graphify.documents_model({"documents_backend": "codex", "documents_model": "gpt-5.5"}) == "gpt-5.5"
    assert graphify.documents_model({"documents_backend": "qwen", "documents_model": "qwen3-coder-plus"}) == "qwen3-coder-plus"
    # A Claude alias left from before the backend was switched means nothing to them.
    assert graphify.documents_model({"documents_backend": "codex", "documents_model": "haiku"}) == ""
    assert graphify.documents_model({"documents_backend": "qwen", "documents_model": "sonnet"}) == ""
    assert graphify.documents_model({"documents_backend": "qwen", "documents_model": "gpt-5"}) == ""


@pytest.mark.parametrize(
    ("backend", "expected"),
    [
        ("claude_code", ["--backend", "claude-cli"]),
        ("codex", ["--backend", "openai", "--api-timeout", "1800", "--max-concurrency", "2"]),
        ("qwen", ["--backend", "openai", "--api-timeout", "1800", "--max-concurrency", "2"]),
    ],
)
def test_the_documents_command_follows_the_chosen_backend(tmp_path: Path, backend: str, expected: list[str]) -> None:
    docs = tmp_path / ".voxarium_studio" / "docs"
    command = graphify.documents_command({"documents_backend": backend}, tmp_path, docs)
    assert command[:5] == ["graphify", "extract", str(docs), "--out", str(graphify.documents_out(tmp_path))]
    assert command[5 : 5 + len(expected)] == expected
    assert command[5 + len(expected)] == "--exclude"
    assert "--force" not in command and "--code-only" not in command


@pytest.mark.parametrize("backend", ["claude_code", "codex", "qwen"])
def test_no_child_runs_as_a_nested_agent_session(monkeypatch, backend: str) -> None:
    for marker in ("CLAUDECODE", "CLAUDE_CODE_ENTRYPOINT", "CODEX_SANDBOX", "CODEX_THREAD_ID", "QWEN_CODE"):
        monkeypatch.setenv(marker, "1")
    monkeypatch.setenv("CODEX_HOME", "C:/codex-home")
    env = graphify.subprocess_env({"documents_backend": backend})
    assert not {"CLAUDECODE", "CLAUDE_CODE_ENTRYPOINT", "CODEX_SANDBOX", "CODEX_THREAD_ID", "QWEN_CODE"} & set(env)
    assert env["CODEX_HOME"] == "C:/codex-home", "only the markers go, not the CLI's own configuration"
    assert ("GRAPHIFY_CLAUDE_CLI_MODEL" in env) == (backend == "claude_code")


def test_the_cli_is_found_as_the_teams_backends_find_it(monkeypatch) -> None:
    from voxarium_studio.teams.backends import codex, qwen

    monkeypatch.setattr(codex, "_launcher", lambda: ["C:/npm/node_modules/@openai/codex/codex.exe"])
    monkeypatch.setattr(qwen, "_launcher", lambda: None)
    assert graphify.documents_launcher(runtimes.RUNTIMES["codex"]) == ["C:/npm/node_modules/@openai/codex/codex.exe"]
    assert graphify.documents_launcher(runtimes.RUNTIMES["qwen"]) is None
    assert graphify.documents_cli({"documents_backend": "codex"}) == "C:/npm/node_modules/@openai/codex/codex.exe"


@pytest.mark.parametrize(
    ("backend", "command", "hint"),
    [("codex", "codex", "codex login"), ("qwen", "qwen", "@qwen-code/qwen-code")],
)
async def test_a_missing_cli_skips_the_documents_and_names_it(
    tmp_path: Path, cli, monkeypatch, backend: str, command: str, hint: str
) -> None:
    with_documents(tmp_path)
    monkeypatch.setattr(graphify, "documents_launcher", lambda runtime: None)
    status = await graphify.index(FakeHost(tmp_path, settings={"documents_backend": backend}), "test")
    assert [call["args"][0] for call in cli.calls] == ["extract"], "the code pass still runs"
    assert status["error"] is None and status["progress"]["percent"] == 100.0
    skipped = status["documents"]["skipped"]
    assert skipped.startswith(f"the `{command}` command is not on PATH") and hint in skipped


def fake_runner(seen: list[tuple[str, str]], answer: str = '{"nodes": [], "edges": []}'):
    async def run(system: str, prompt: str) -> str:
        seen.append((system, prompt))
        return answer

    return run


async def post(url: str, body: dict | bytes, token: str | None, path: str = "/chat/completions") -> tuple[int, dict]:
    """A bare HTTP/1.1 request, as graphify's OpenAI client would send it."""
    from urllib.parse import urlsplit

    parts = urlsplit(url)
    reader, writer = await asyncio.open_connection(parts.hostname, parts.port)
    payload = body if isinstance(body, bytes) else json.dumps(body).encode()
    head = f"POST {parts.path}{path} HTTP/1.1\r\nHost: {parts.netloc}\r\nContent-Type: application/json\r\n"
    if token is not None:
        head += f"Authorization: Bearer {token}\r\n"
    head += f"Content-Length: {len(payload)}\r\n\r\n"
    writer.write(head.encode() + payload)
    await writer.drain()
    raw = await reader.read()
    writer.close()
    status_line, _, rest = raw.partition(b"\r\n")
    _, _, body_bytes = rest.partition(b"\r\n\r\n")
    return int(status_line.split()[1]), json.loads(body_bytes.decode())


CHAT = {
    "model": "gpt-5.5",
    "messages": [
        {"role": "system", "content": "You are a graphify extraction subagent."},
        {"role": "user", "content": [{"type": "text", "text": "<file>dash.md</file>"}]},
    ],
    "max_completion_tokens": 16384,
    "stream": False,
}


async def test_the_endpoint_answers_an_openai_chat_completion_through_the_cli() -> None:
    seen: list[tuple[str, str]] = []
    rule = graphify.language_rule("source")
    async with runtimes.DocumentsEndpoint(fake_runner(seen), model="gpt-5.5", rule=rule, label="Codex") as endpoint:
        assert endpoint.base_url.startswith("http://127.0.0.1:") and endpoint.base_url.endswith("/v1")
        status, body = await post(endpoint.base_url, CHAT, endpoint.token)
    assert status == 200
    assert body["object"] == "chat.completion" and body["model"] == "gpt-5.5"
    assert body["choices"][0]["message"] == {"role": "assistant", "content": '{"nodes": [], "edges": []}'}
    assert body["choices"][0]["finish_reason"] == "stop"
    system, prompt = seen[0]
    assert system.startswith("You are a graphify extraction subagent.")
    assert system.endswith(rule.strip()), "the language rule rides every system prompt"
    assert prompt == "<file>dash.md</file>"
    assert endpoint.answered == 1


async def test_the_endpoint_refuses_without_the_token() -> None:
    seen: list[tuple[str, str]] = []
    async with runtimes.DocumentsEndpoint(fake_runner(seen), model="m") as endpoint:
        no_token = await post(endpoint.base_url, CHAT, None)
        wrong = await post(endpoint.base_url, CHAT, "not-the-token")
        elsewhere = await post(endpoint.base_url, CHAT, endpoint.token, path="/embeddings")
    assert no_token[0] == 401 and no_token[1]["error"]["type"] == "invalid_api_key"
    assert wrong[0] == 401
    assert elsewhere[0] == 404
    assert seen == [], "nothing reached the CLI"


async def test_the_endpoint_reports_a_cli_failure_as_a_server_error() -> None:
    lines: list[str] = []

    async def failing(system: str, prompt: str) -> str:
        raise runtimes.CliError("codex exec exited with 1: not logged in")

    async with runtimes.DocumentsEndpoint(failing, model="m", label="Codex", on_line=lines.append) as endpoint:
        status, body = await post(endpoint.base_url, CHAT, endpoint.token)
        bad = await post(endpoint.base_url, b"{not json", endpoint.token)
    assert status == 502 and "not logged in" in body["error"]["message"]
    assert bad[0] == 400
    assert lines == ["Codex gave no answer: codex exec exited with 1: not logged in"]


async def test_the_endpoint_lives_only_as_long_as_the_pass() -> None:
    async with runtimes.DocumentsEndpoint(fake_runner([]), model="m") as endpoint:
        url = endpoint.base_url
    with pytest.raises(OSError):
        await post(url, CHAT, endpoint.token)


def test_graphify_is_pointed_at_the_endpoint_not_at_openai() -> None:
    endpoint = runtimes.DocumentsEndpoint(fake_runner([]), model="", token="secret")
    endpoint.port = 4321
    env = endpoint.graphify_env()
    assert env["OPENAI_BASE_URL"] == "http://127.0.0.1:4321/v1"
    assert env["OPENAI_API_KEY"] == "secret"
    assert env["OPENAI_MODEL"] == env["GRAPHIFY_OPENAI_MODEL"] == "default", "graphify needs a name; the CLI picks"
    assert "127.0.0.1" in env["NO_PROXY"].split(",")
    assert env["GRAPHIFY_MAX_RETRIES"] == "1", "a retry is a whole agent run"


def test_codex_reads_one_chunk_headless_with_graphifys_prompt_as_its_instructions(tmp_path: Path) -> None:
    argv = runtimes.codex_command(["codex.exe"], "gpt-5.5", tmp_path / "i.md", tmp_path / "out.txt")
    assert argv[:2] == ["codex.exe", "exec"]
    assert {"--skip-git-repo-check", "--ephemeral"} <= set(argv)
    assert argv[argv.index("--sandbox") + 1] == "read-only"
    assert f"model_instructions_file={json.dumps(str(tmp_path / 'i.md'))}" in argv
    assert argv[argv.index("--output-last-message") + 1] == str(tmp_path / "out.txt")
    assert argv[argv.index("-m") + 1] == "gpt-5.5" and argv[-1] == "-"
    assert "-m" not in runtimes.codex_command(["codex.exe"], "", tmp_path / "i.md", tmp_path / "o.txt")


def test_qwen_reads_one_chunk_headless_and_answers_from_its_result_frame() -> None:
    argv, stdin = runtimes.qwen_command(["node", "qwen.js"], "qwen3-coder-plus", "SYSTEM", "CHUNK")
    assert argv[:2] == ["node", "qwen.js"] and stdin == "CHUNK"
    assert argv[argv.index("--approval-mode") + 1] == "plan"
    assert argv[argv.index("--model") + 1] == "qwen3-coder-plus"
    assert argv[argv.index("--system-prompt") + 1] == "SYSTEM"
    long_argv, long_stdin = runtimes.qwen_command(["qwen"], "", "x" * 40_000, "CHUNK")
    assert "--system-prompt" not in long_argv and "--model" not in long_argv
    assert long_stdin.startswith("Instructions:\n" + "x" * 10) and long_stdin.endswith("CHUNK")

    stream = "\n".join(
        json.dumps(frame)
        for frame in [
            {"type": "system", "subtype": "init"},
            {"type": "assistant", "message": {"content": [{"type": "text", "text": "draft"}]}},
            {"type": "result", "result": '{"nodes": []}', "is_error": False},
        ]
    )
    assert runtimes.qwen_answer(stream) == '{"nodes": []}'
    with pytest.raises(runtimes.CliError):
        runtimes.qwen_answer(json.dumps({"type": "result", "is_error": True, "error": "quota"}))


@pytest.mark.parametrize("language", ["source", "english"])
async def test_codex_reads_the_documents_through_the_studios_endpoint(
    tmp_path: Path, cli, monkeypatch, language: str
) -> None:
    """The documents pass reaches graphify's `openai` backend at the endpoint, which is up
    for exactly that pass and hands the chunk to the CLI with the language rule."""
    docs = with_documents(tmp_path)
    monkeypatch.setattr(graphify, "documents_launcher", lambda runtime: ["C:/fake/codex.exe"])
    seen: list[tuple[str, str]] = []
    made: list[tuple] = []

    def runner_for(runtime, prefix, model):
        made.append((runtime.id, prefix, model))
        return fake_runner(seen)

    monkeypatch.setattr(runtimes, "runner_for", runner_for)
    answers: list[int] = []
    original = asyncio.create_subprocess_exec  # the `cli` fixture's fake

    async def exec_and_ask(program, *args, **kwargs):
        process = await original(program, *args, **kwargs)
        env = kwargs.get("env") or {}
        if "OPENAI_BASE_URL" in env:
            status, _ = await post(env["OPENAI_BASE_URL"], CHAT, env["OPENAI_API_KEY"])
            answers.append(status)
        return process

    monkeypatch.setattr(asyncio, "create_subprocess_exec", exec_and_ask)
    write_graph(tmp_path, DOC_NODES, [], path=graphify.documents_graph_file(tmp_path))
    host = FakeHost(tmp_path, settings={"documents_backend": "codex", "documents_model": "gpt-5.5", "documents_language": language})

    status = await graphify.index(host, "test")

    assert [call["args"][0] for call in cli.calls] == ["extract", "extract", "merge-graphs"]
    documents = cli.calls[1]
    assert documents["args"][1] == str(docs)
    assert documents["args"][4:6] == ["--backend", "openai"]
    assert documents["env"]["OPENAI_BASE_URL"].startswith("http://127.0.0.1:")
    assert documents["env"]["GRAPHIFY_OPENAI_MODEL"] == "gpt-5.5"
    assert "GRAPHIFY_CLAUDE_CLI_MODEL" not in documents["env"]
    assert "OPENAI_BASE_URL" not in cli.calls[0]["env"], "the code pass never sees the endpoint"
    assert answers == [200]
    assert made == [("codex", ["C:/fake/codex.exe"], "gpt-5.5")]
    assert seen[0][1] == "<file>dash.md</file>"
    assert seen[0][0].endswith(graphify.language_rule(language).strip())
    assert not (graphify.documents_out(tmp_path) / "CLAUDE.md").exists(), "Codex reads no CLAUDE.md"
    assert status["error"] is None and status["documents"]["ran"] is True
    assert any(line.startswith(f"[voxarium_studio] reading the documents in {docs} through Codex (gpt-5.5)") for line in status["log"])
    assert "[voxarium_studio] Codex answered a chunk in 0 s" in status["log"]
    # The endpoint was the pass's alone: it is gone now.
    with pytest.raises(OSError):
        await post(documents["env"]["OPENAI_BASE_URL"], CHAT, documents["env"]["OPENAI_API_KEY"])


async def test_graphify_without_its_openai_extra_is_told_how_to_get_it(tmp_path: Path, cli, monkeypatch) -> None:
    with_documents(tmp_path)
    monkeypatch.setattr(graphify, "documents_launcher", lambda runtime: ["C:/fake/qwen"])
    cli.script["queue"] = [
        {"lines": ["[graphify extract] wrote D:/game/graphify-out/graph.json: 9 nodes, 8 edges, 1 communities"]},
        {
            "lines": [
                "[graphify] chunk 1/1 failed: the 'openai' package is required for this backend but is not installed.",
                "[graphify extract] error: all semantic chunks failed for backend 'openai' (1 uncached files)",
            ],
            "code": 0,
        },
    ]
    status = await graphify.index(FakeHost(tmp_path, settings={"documents_backend": "qwen"}), "test")
    assert len(cli.calls) == 2, "no merge of a pass that read nothing"
    assert 'uv tool install "graphifyy[openai]" --force' in status["documents"]["error"]
    assert status["error"].startswith("documents: graphify lacks the 'openai' package")


async def test_the_status_names_the_chosen_cli(tmp_path: Path, monkeypatch) -> None:
    monkeypatch.setattr(graphify, "documents_launcher", lambda runtime: ["node", "C:/npm/qwen.js"] if runtime.id == "qwen" else None)
    monkeypatch.setattr(graphify, "resolve_executable", lambda settings: None)
    status = await graphify.action_status(FakeHost(tmp_path, settings={"documents_backend": "qwen"}), {})
    assert status["documents_backend"] == "qwen" and status["documents_backend_label"] == "Qwen Code"
    assert status["documents_model"] == "" and status["documents_cli"] == "C:/npm/qwen.js"
    assert status["documents_cli_command"] == "qwen" and status["documents_endpoint"] is True
    assert status["documents_memory"] is None and status["documents_extra_hint"].startswith("uv tool install")
    assert status["documents_command"][5:7] == ["--backend", "openai"]
    old = await graphify.action_status(FakeHost(tmp_path, settings={"documents_model": "sonnet"}), {})
    assert old["documents_backend"] == "claude_code" and old["documents_model"] == "sonnet"
    assert old["documents_memory"].endswith("CLAUDE.md") and old["documents_endpoint"] is False


def test_a_document_hit_is_relative_to_the_documents_folder(tmp_path: Path) -> None:
    host = FakeHost(tmp_path)
    assert graphify.project_relative(host, "features/dash.md", "documents") == ".voxarium_studio/docs/features/dash.md"
    assert graphify.project_relative(host, "src/a.py", tmp_path.name) == "src/a.py", "the code's own tag"
    assert graphify.project_relative(host, "src/a.py") == "src/a.py"


async def test_search_answers_from_the_merged_graph_when_there_is_one(tmp_path: Path) -> None:
    host = FakeHost(tmp_path)
    write_graph(tmp_path)
    assert (await graphify.search_code(host, SearchQuery(text="dash"))).results == []

    write_graph(tmp_path, NODES + DOC_NODES, LINKS, path=graphify.merged_graph_file(tmp_path))
    hit = (await graphify.search_code(host, SearchQuery(text="dash"))).results[0]
    assert (hit.title, hit.kind, hit.path, hit.line) == ("Dash ability", "document", ".voxarium_studio/docs/features/dash.md", None)
    assert hit.snippet == "Cooldown mechanic (2s, cannot be cancelled) with i-frames; uses the ability budget", (
        "the model's one line about it, whitespace folded"
    )
    concept = (await graphify.search_code(host, SearchQuery(text="budget"))).results[0]
    assert (concept.title, concept.kind, concept.snippet) == ("Ability Resource Budget", "concept", None)
    assert graphify.graph_status(host)["merged"] is True


def test_document_lines_move_the_bar_inside_the_semantic_band() -> None:
    parse = graphify.progress_from_line
    Progress = graphify.Progress
    assert parse("[graphify extract] 0 code, 8 docs, 0 papers, 0 images changed; 200 unchanged; 0 deleted") == Progress(
        10.0, "Detecting files", "0 code files, 8 documents"
    )
    assert parse("[graphify extract] semantic cache: 5 hit / 3 miss") == Progress(18.0, "Reading documents", "5 cached, 3 to read")
    assert parse("[graphify extract] semantic extraction on 3 files via claude-cli...") == Progress(
        20.0, "Reading documents", "3 files via claude-cli"
    )
    assert parse("[graphify extract] chunk 1/2 done") == Progress(54.0, "Reading documents", "1/2 chunks")
    assert parse("[graphify extract] chunk 2/2 done").percent == 88.0
    assert Progress(50.0, "x").within((40.0, 100.0)).percent == 70.0


# --------------------------------------------------------------------------- progress


def test_progress_is_read_from_the_clis_own_lines() -> None:
    """Every stage graphify 0.9.49 prints, plus the per-hundred-files line inside the AST
    pass; anything else says nothing about where the run is."""
    parse = graphify.progress_from_line
    Progress = graphify.Progress

    assert parse("[graphify extract] scanning D:/game") == Progress(5.0, "Scanning", None)
    assert parse("[graphify extract] incremental scan of D:/game").percent == 5.0
    assert parse("[graphify extract] found 13 code, 0 docs, 0 papers, 0 images") == Progress(
        10.0, "Detecting files", "13 code files"
    )
    incremental = "[graphify extract] 2 code, 0 docs, 0 papers, 0 images changed; 6 unchanged; 0 deleted"
    assert parse(incremental) == Progress(10.0, "Detecting files", "2 code files")
    assert parse("[graphify extract] AST extraction on 291 code files...") == Progress(
        15.0, "AST extraction", "291 files"
    )
    half = parse("  AST extraction: 150/300 uncached files (50%) [8 workers]")
    assert half == Progress(37.5, "AST extraction", "150/300 files")
    # The last file read is the start of the silent part — build and cluster print nothing,
    # and on a large corpus take longer than the reading did, hence the band ending at 60.
    assert parse("  AST extraction: 300/300 uncached files (100%)") == Progress(
        60.0, "Building the graph", "300 files read"
    )
    wrote = "[graphify extract] wrote D:/game/graphify-out/graph.json: 291 nodes, 506 edges, 12 communities"
    assert parse(wrote) == Progress(92.0, "Writing the graph", None)
    assert parse("[graphify extract] wrote D:/game/graphify-out/.graphify_analysis.json").percent == 97.0
    assert parse("[graphify extract] incremental summary: 3 files cached/unchanged").percent == 100.0
    assert parse("[graphify extract] next: run `graphify cluster-only D:/game`").percent == 100.0
    assert parse("[graphify extract] no incremental changes detected; outputs left untouched.").percent == 100.0

    for silent in (
        "[graphify extract] --code-only: skipping 40 non-code file(s) (23 docs) — no LLM extraction",
        "[graphify extract] 20 file(s) not classified (no supported extension or shebang), skipped: x",
        "  warning: worker failed for a.py: boom",
        "",
    ):
        assert parse(silent) is None, silent


async def test_progress_rides_the_index_events_and_never_goes_backwards(tmp_path: Path, cli) -> None:
    cli.script["lines"] = [
        "[graphify extract] scanning D:/game",
        "[graphify extract] found 200 code, 0 docs, 0 papers, 0 images",
        "[graphify extract] AST extraction on 200 code files...",
        "  AST extraction: 100/200 uncached files (50%) [4 workers]",
        "[graphify extract] scanning D:/game/again",  # out of order: must not pull the bar back
        "  AST extraction: 200/200 uncached files (100%) [4 workers]",
        "[graphify extract] wrote D:/game/graphify-out/graph.json: 9 nodes, 8 edges, 1 communities",
        "[graphify extract] wrote D:/game/graphify-out/.graphify_analysis.json",
        "[graphify extract] next: run `graphify cluster-only D:/game`",
    ]
    host = FakeHost(tmp_path)

    status = await graphify.index(host, "test")

    frames = [data["progress"] for _, data in host.events]
    assert [frame["percent"] for frame in frames] == [
        0.0,  # started
        5.0,
        10.0,
        15.0,
        37.5,
        60.0,
        92.0,
        97.0,
        100.0,  # the closing tip
        100.0,  # finished
    ]
    assert frames[4] == {"percent": 37.5, "stage": "AST extraction", "detail": "100/200 files"}
    assert all(data["running"] for _, data in host.events[:-1]) and not host.events[-1][1]["running"]
    assert status["progress"] == {"percent": 100.0, "stage": "Done", "detail": None}


async def test_a_failed_run_keeps_the_progress_it_reached(tmp_path: Path, cli) -> None:
    cli.script.update(lines=["[graphify extract] scanning D:/game", "error: path not found"], code=1)
    status = await graphify.index(FakeHost(tmp_path), "test")
    assert status["progress"] == {"percent": 5.0, "stage": "Scanning", "detail": None}


async def test_the_skill_version_warning_is_not_part_of_the_log(tmp_path: Path, cli) -> None:
    cli.script["lines"] = ["  warning: skill is from graphify 0.9.48, package is 0.9.49.", "done"]
    status = await graphify.index(FakeHost(tmp_path, settings={"documents": False}), "test")
    assert status["log"] == ["done"]


async def test_a_second_request_during_a_run_is_queued_not_overlapped(tmp_path: Path, cli) -> None:
    """Two tasks finishing together mean one follow-up extract, after the current one."""
    gate = asyncio.Event()
    cli.script["gate"] = gate
    host = FakeHost(tmp_path)

    first = asyncio.ensure_future(graphify.index(host, "task A done"))
    await asyncio.sleep(0.01)
    assert len(cli.calls) == 1 and graphify.indexer_of(host).running

    queued = await graphify.index(host, "task B done")
    assert queued["pending"] == "task B done", "answered at once, with the request queued"
    third = await graphify.index(host, "task C done")
    assert third["pending"] == "task C done", "the queue is one deep; the newest reason wins"
    assert len(cli.calls) == 1, "nothing started over the running extract"

    cli.script["gate"] = None
    gate.set()
    status = await first
    assert len(cli.calls) == 2, "exactly one follow-up run"
    assert status["reason"] == "task C done" and status["pending"] is None


async def test_a_forced_request_in_the_queue_makes_the_follow_up_run_forced(tmp_path: Path, cli) -> None:
    gate = asyncio.Event()
    cli.script["gate"] = gate
    host = FakeHost(tmp_path)
    first = asyncio.ensure_future(graphify.index(host, "task A done"))
    await asyncio.sleep(0.01)
    await graphify.index(host, "rebuild", force=True)
    await graphify.index(host, "task B done")  # later, and not forced — the force still stands
    cli.script["gate"] = None
    gate.set()
    await first
    assert "--force" not in cli.calls[0]["args"]
    assert cli.calls[1]["args"][-1] == "--force"


async def test_the_index_action_takes_a_force_flag(tmp_path: Path, cli) -> None:
    host = FakeHost(tmp_path)
    await graphify.action_index(host, {"force": True})
    await host.tasks[0]
    assert cli.calls[0]["args"][-1] == "--force"
    assert graphify.indexer_of(host).reason.startswith("rebuild of everything")
    with pytest.raises(ValueError):
        await graphify.action_index(host, {"force": "yes"})


async def test_a_forced_run_forgets_the_previous_scan_first(tmp_path: Path, cli) -> None:
    """graphify's own `--force` leaves unchanged code files unread in 0.9.49, so a rebuild
    of everything is the manifest and the AST cache gone before the run — and only then."""
    out = tmp_path / "graphify-out"
    (out / "cache" / "ast").mkdir(parents=True)
    (out / "cache" / "ast" / "entry.json").write_text("{}", encoding="utf-8")
    (out / "cache" / "semantic").mkdir()
    (out / "cache" / "semantic" / "doc.json").write_text("{}", encoding="utf-8")
    (out / "cache" / "stat-index.json").write_text("{}", encoding="utf-8")
    (out / "manifest.json").write_text("{}", encoding="utf-8")
    write_graph(tmp_path)
    host = FakeHost(tmp_path)

    await graphify.index(host, "task done")
    assert (out / "manifest.json").exists() and (out / "cache" / "ast").is_dir(), "incremental keeps both"

    status = await graphify.index(host, "rebuild", force=True)
    assert not (out / "manifest.json").exists() and not (out / "cache" / "ast").exists()
    assert not (out / "cache" / "stat-index.json").exists()
    assert (out / "cache" / "semantic" / "doc.json").exists(), "a model call per document — kept"
    assert graphify.graph_file(tmp_path).exists(), "the graph itself is the CLI's to replace"
    assert status["log"][0] == (
        "[voxarium_studio] removed manifest.json, stat-index.json, cache/ast/ so every file is read again"
    )


async def test_a_failing_cli_is_reported_not_raised(tmp_path: Path, cli) -> None:
    cli.script.update(lines=["scanning", "error: path not found"], code=1)
    status = await graphify.index(FakeHost(tmp_path), "test")
    assert status["exit_code"] == 1
    assert status["error"] == "graphify extract exited with 1: error: path not found"


async def test_a_missing_cli_is_reported_without_a_run(tmp_path: Path, monkeypatch) -> None:
    calls = []

    async def never(*args, **kwargs):
        calls.append(args)

    monkeypatch.setattr(graphify, "resolve_executable", lambda settings: None)
    monkeypatch.setattr(asyncio, "create_subprocess_exec", never)
    status = await graphify.index(FakeHost(tmp_path), "test")
    assert calls == []
    assert "not on PATH" in status["error"] and graphify.INSTALL_HINT in status["error"]
    assert status["running"] is False and status["finished_at"] is not None


async def test_cancelling_the_run_kills_the_process(tmp_path: Path, cli) -> None:
    gate = asyncio.Event()
    cli.script["gate"] = gate
    host = FakeHost(tmp_path)
    run = asyncio.ensure_future(graphify.index(host, "test"))
    await asyncio.sleep(0.01)
    run.cancel()
    with pytest.raises(asyncio.CancelledError):
        await run
    process = cli.calls[0]["process"]
    assert process.killed
    assert graphify.indexer_of(host).running is False


async def test_the_cancel_action_stops_a_running_extract(tmp_path: Path, cli) -> None:
    gate = asyncio.Event()
    cli.script["gate"] = gate
    host = FakeHost(tmp_path)
    run = asyncio.ensure_future(graphify.index(host, "test"))
    await asyncio.sleep(0.01)
    status = await graphify.action_cancel(host, {})
    assert cli.calls[0]["process"].killed
    assert status["pending"] is None
    gate.set()
    await run


# --------------------------------------------------------------------------- hooks


async def test_only_a_task_reaching_done_rebuilds(tmp_path: Path, monkeypatch) -> None:
    reasons: list[str] = []
    occasions: list[str | None] = []

    async def record(host, reason, occasion=None):
        reasons.append(reason)
        occasions.append(occasion)
        return {}

    monkeypatch.setattr(graphify, "index", record)
    host = FakeHost(tmp_path)
    changed = "team.task.status_changed"
    await graphify.on_task_status_changed(host, Event(type=changed, data={"task_id": "t1", "status": "in_progress"}))
    await graphify.on_task_status_changed(host, Event(type=changed, data={"task_id": "t1", "status": "done"}))
    assert reasons == ["task t1 done"]
    assert occasions == ["task:t1:done"], "the occasion an automation for the same task joins"

    host.settings["index_on_task_done"] = False
    await graphify.on_task_status_changed(host, Event(type=changed, data={"task_id": "t2", "status": "done"}))
    assert reasons == ["task t1 done"], "the toggle turns it off"


async def test_opening_the_project_indexes_unless_told_not_to(tmp_path: Path, monkeypatch) -> None:
    reasons: list[str] = []

    async def record(host, reason):
        reasons.append(reason)
        return {}

    monkeypatch.setattr(graphify, "index", record)
    await graphify.on_project_opened(FakeHost(tmp_path))
    assert reasons == ["project opened"]
    await graphify.on_project_opened(FakeHost(tmp_path, settings={"index_on_open": False}))
    assert reasons == ["project opened"]


async def test_reset_wipes_the_output_and_starts_over(tmp_path: Path, cli, claude) -> None:
    """Everything under graphify-out goes — both graphs, the merged one, the manifests and
    every cache, the semantic one included — and a fresh run follows, both passes."""
    with_documents(tmp_path)
    out = tmp_path / "graphify-out"
    write_graph(tmp_path)
    write_graph(tmp_path, DOC_NODES, [], path=graphify.documents_graph_file(tmp_path))
    write_graph(tmp_path, NODES + DOC_NODES, LINKS, path=graphify.merged_graph_file(tmp_path))
    (out / "manifest.json").write_text("{}", encoding="utf-8")
    (out / "cache" / "semantic").mkdir(parents=True)
    (out / "cache" / "semantic" / "doc.json").write_text("{}", encoding="utf-8")
    host = FakeHost(tmp_path)
    host.state["graph_index"] = object()

    status = await graphify.action_reset(host, {})
    assert status["running"] is False, "answered before anything happened"
    await host.tasks[-1]

    assert not out.exists() or not any(out.rglob("*.json")), "the old output is gone"
    assert "graph_index" not in host.state
    assert [call["args"][0] for call in cli.calls] == ["extract", "extract"], "a fresh run, both passes"
    final = graphify.indexer_of(host).status()
    assert final["reason"].startswith("reset from the Graphify page")
    assert final["log"][0].startswith("[voxarium_studio] reset: removed") and "every cache" in final["log"][0]
    assert final["error"] is None and final["progress"]["percent"] == 100.0


async def test_reset_stops_a_running_extract_first(tmp_path: Path, cli) -> None:
    gate = asyncio.Event()
    cli.script["gate"] = gate
    host = FakeHost(tmp_path)
    write_graph(tmp_path)
    running = asyncio.ensure_future(graphify.index(host, "task done"))
    await asyncio.sleep(0.01)
    await graphify.index(host, "task B done")  # queued behind the run — a reset must drop it
    assert graphify.indexer_of(host).pending == "task B done"

    cli.script["gate"] = None
    await graphify.action_reset(host, {})
    assert cli.calls[0]["process"].killed
    assert graphify.indexer_of(host).pending is None
    gate.set()
    await running
    await host.tasks[-1]

    assert len(cli.calls) == 2, "the killed run, then the fresh one — not the queued follow-up"
    assert not graphify.graph_file(tmp_path).exists()
    assert graphify.indexer_of(host).reason.startswith("reset from the Graphify page")


def test_only_a_graphify_out_directory_is_ever_removed(tmp_path: Path) -> None:
    keep = tmp_path / "docs"
    keep.mkdir()
    with pytest.raises(ValueError, match="refusing"):
        graphify.remove_output(keep)
    assert keep.exists()
    assert graphify.remove_output(tmp_path / "graphify-out") == "nothing to remove"


# --------------------------------------------------------------------------- the automation action


def done_by_automation(task_id: str = "t1") -> dict:
    """What the Automations page hands the action for "On task → Done → Graphify Update"."""
    return {
        "automation": {"id": "auto_1", "name": "Keep the graph fresh"},
        "trigger": "task.status_changed",
        "values": {"task": "DP-01", "task_id": task_id, "status": "done"},
    }


MANUAL = {"automation": {"id": "auto_1", "name": ""}, "trigger": "manual", "values": {}}


async def test_the_automation_lasts_as_long_as_its_run(tmp_path: Path, cli) -> None:
    """The point of it: the Automations page shows the update in progress until the graph is
    written, not "done" the moment an index was asked for."""
    gate = asyncio.Event()
    cli.script["gate"] = gate
    host = FakeHost(tmp_path)

    update = asyncio.ensure_future(graphify.automation_update(host, MANUAL))
    await asyncio.sleep(0.01)
    assert len(cli.calls) == 1 and graphify.indexer_of(host).running
    assert not update.done(), "still waiting on the run it started"

    gate.set()
    assert await update == "Graph updated: 3 nodes"
    assert graphify.indexer_of(host).reason == "automation: an automation"
    await asyncio.gather(*host.tasks)


async def test_a_task_done_heard_twice_is_indexed_once(tmp_path: Path, cli) -> None:
    """The plugin's own *index when a task is done* and an automation for the same task both
    ask; the second joins the first's run instead of queueing a repeat behind it — whichever
    of the two arrives first."""
    gate = asyncio.Event()
    cli.script["gate"] = gate
    changed = "team.task.status_changed"
    done = Event(type=changed, data={"task_id": "t1", "status": "done"})

    # The hook first: its run is going when the automation asks.
    host = FakeHost(tmp_path)
    hook = asyncio.ensure_future(graphify.on_task_status_changed(host, done))
    await asyncio.sleep(0.01)
    update = asyncio.ensure_future(graphify.automation_update(host, done_by_automation()))
    await asyncio.sleep(0.01)
    assert graphify.indexer_of(host).pending is None, "joined, not queued"
    gate.set()
    await hook
    assert await update == "Graph updated: 3 nodes"
    assert len(cli.calls) == 1

    # The automation first: its run is claimed and has read nothing when the hook asks.
    gate.clear()
    host = FakeHost(tmp_path)
    update = asyncio.ensure_future(graphify.automation_update(host, done_by_automation("t2")))
    await asyncio.sleep(0)
    await graphify.on_task_status_changed(host, Event(type=changed, data={"task_id": "t2", "status": "done"}))
    await asyncio.sleep(0.01)
    gate.set()
    await update
    await asyncio.gather(*host.tasks)
    assert len(cli.calls) == 2, "one run for t2 as well"


async def test_an_automation_during_another_run_waits_for_the_follow_up(tmp_path: Path, cli) -> None:
    """A run already going read its files before the automation asked, so it does not cover
    it: the follow-up does, and the automation is in progress until that one ends."""
    gate = asyncio.Event()
    cli.script["gate"] = gate
    host = FakeHost(tmp_path)
    first = asyncio.ensure_future(graphify.index(host, "project opened"))
    await asyncio.sleep(0.01)

    update = asyncio.ensure_future(graphify.automation_update(host, done_by_automation()))
    await asyncio.sleep(0.01)
    assert graphify.indexer_of(host).pending == "automation: Keep the graph fresh"

    gate.set()
    await first
    assert await update == "Graph updated: 3 nodes"
    assert len(cli.calls) == 2, "the running run, then the follow-up the automation waited for"


async def test_cancel_drops_an_automation_still_waiting(tmp_path: Path, cli) -> None:
    gate = asyncio.Event()
    cli.script["gate"] = gate
    host = FakeHost(tmp_path)
    running = asyncio.ensure_future(graphify.index(host, "project opened"))
    await asyncio.sleep(0.01)
    queued = asyncio.ensure_future(graphify.automation_update(host, MANUAL))
    await asyncio.sleep(0.01)

    await graphify.action_cancel(host, {})
    gate.set()
    await running
    with pytest.raises(graphify.AutomationCancelled):
        await queued
    assert len(cli.calls) == 1, "the queued follow-up never ran"

    # And a run asked for but not yet begun is dropped with the queue.
    claimed = asyncio.ensure_future(graphify.automation_update(host, MANUAL))
    await asyncio.sleep(0)
    await graphify.action_cancel(host, {})
    with pytest.raises(graphify.AutomationCancelled):
        await claimed
    await asyncio.gather(*host.tasks)
    assert len(cli.calls) == 1


async def test_a_run_that_fails_fails_the_automation(tmp_path: Path, cli) -> None:
    cli.script.update(lines=["scanning", "error: path not found"], code=1)
    host = FakeHost(tmp_path)
    with pytest.raises(RuntimeError, match="exited with 1: error: path not found"):
        await graphify.automation_update(host, MANUAL)
    await asyncio.gather(*host.tasks)


async def test_the_index_action_returns_at_once_and_leaves_the_run_in_the_background(tmp_path: Path, cli) -> None:
    gate = asyncio.Event()
    cli.script["gate"] = gate
    host = FakeHost(tmp_path)
    status = await graphify.action_index(host, {})
    assert status["running"] is True
    assert len(host.tasks) == 1 and not host.tasks[0].done()
    gate.set()
    await host.tasks[0]
    assert graphify.indexer_of(host).exit_code == 0


# --------------------------------------------------------------------------- registered


def test_the_builtin_plugin_registers_hooks_actions_and_the_provider(registry: Registry) -> None:
    plugin = registry.plugin("graphify")
    assert plugin.status == "loaded", plugin.error
    listed = plugin.to_json()
    assert listed["hooks"] == [
        "project.opened:on_project_opened",
        "team.task.status_changed:on_task_status_changed",
    ]
    assert listed["actions"] == ["status", "index", "cancel", "reset"]
    assert listed["search_providers"] == ["code"]
    assert listed["pages"] == ["graphify-settings"]
    provider = next(p for p in registry.search_providers() if p.key == "graphify.code")
    assert provider.label == "Code graph"


def test_the_status_action_describes_the_cli_the_graph_and_the_last_run(client: TestClient) -> None:
    """With the CLI stubbed absent by conftest, the opening hook has run and found nothing
    to start — which is the state the settings page has to explain."""
    status = client.post("/api/plugins/graphify/actions/status").json()
    assert status["executable"] == "graphify" and status["resolved"] is None
    assert status["install_hint"] == "uv tool install graphifyy"
    assert status["command"][1:4] == ["extract", ".", "--code-only"]
    assert status["documents_command"][5:7] == ["--backend", "claude-cli"]
    assert status["documents_dir"] == str(client.app.state.ctx.paths.docs)
    assert status["documents_command"][2] == status["documents_dir"]
    assert status["merge_command"][1] == "merge-graphs"
    assert status["documents"] is True and status["documents_model"] == "haiku"
    assert "claude" in status and "claude_hint" in status
    assert status["launch_root"] == str(client.app.state.ctx.paths.root)
    assert status["graph"]["exists"] is False
    assert status["index_on_open"] is True and status["index_on_task_done"] is True

    body = client.get("/api/search", params={"q": "anything"}).json()
    group = next(g for g in body["providers"] if g["id"] == "graphify.code")
    assert group["label"] == "Code graph"
    assert group["results"] == [] and group["note"].startswith("No code graph yet")


# --------------------------------------------------------------------------- the prompt


def test_the_prompt_fragment_follows_the_file_not_the_plugin(tmp_path: Path) -> None:
    assert code_graph_file(tmp_path) is None
    graph = tmp_path / "graphify-out" / "graph.json"
    graph.parent.mkdir()
    graph.write_text("{}", encoding="utf-8")
    assert code_graph_file(tmp_path) == graph
    merged = tmp_path / "graphify-out" / "merged-graph.json"
    merged.write_text("{}", encoding="utf-8")
    assert code_graph_file(tmp_path) == merged, "code and documents together, when there is such a file"
