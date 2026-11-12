"""Project memory: the store, the index every run is sent, the tools and the page (§10.12)."""

from __future__ import annotations

from pathlib import Path
from unittest.mock import AsyncMock

import pytest
from fastapi.testclient import TestClient
from sqlmodel import select

from voxarium_studio.core.config import TeamsConfig
from voxarium_studio.core.context import AppContext
from voxarium_studio.core.paths import ProjectPaths
from voxarium_studio.core.prompts import AgentPromptsConfig
from voxarium_studio.models.teams import TeamMember
from voxarium_studio.teams.memory import (
    EMPTY_INDEX,
    MAX_DESCRIPTION_CHARS,
    MemoryChangedOnDisk,
    MemoryStore,
    memory_prompt,
    render_index,
)
from voxarium_studio.teams.orchestrator import _build_system_prompt
from voxarium_studio.teams.tools import build_toolset
from voxarium_studio.ws.events import EventType


@pytest.fixture
def store(tmp_path: Path) -> MemoryStore:
    return MemoryStore(ProjectPaths.for_project(tmp_path), bus=AsyncMock())


# ------------------------------------------------------------------ the store


async def test_an_entry_round_trips_through_its_file(store: MemoryStore) -> None:
    entry = await store.write(
        "build-uses-ue-5-4",
        description="  The game builds   with Unreal 5.4 —\nnot 5.5.  ",
        content="Plugins pin 5.4.\r\nUpgrade together.\n",
        author="team__dev",
    )
    assert entry.description == "The game builds with Unreal 5.4 — not 5.5."
    assert entry.content == "Plugins pin 5.4.\nUpgrade together."
    assert entry.author == "team__dev"

    text = (store.directory / "build-uses-ue-5-4.md").read_text(encoding="utf-8")
    assert text.startswith("---\ndescription: The game builds with Unreal 5.4 — not 5.5.\n")
    assert "author: team__dev\n---\n\nPlugins pin 5.4.\nUpgrade together.\n" in text
    assert store.get("build-uses-ue-5-4") == entry
    store.bus.publish.assert_awaited_with(
        EventType.TEAM_MEMORY_CHANGED, {"name": "build-uses-ue-5-4", "author": "team__dev"}
    )


async def test_a_user_written_entry_has_no_author(store: MemoryStore) -> None:
    entry = await store.write("art-style", description="Low-poly, flat shaded.", content="")
    assert entry.author is None
    assert "author" not in (store.directory / "art-style.md").read_text(encoding="utf-8")


@pytest.mark.parametrize(
    "name", ["", "Build", "-lead", "with space", "a/b", "x" * 65, "dot.md", "fact\n", "aux", "com1"]
)
async def test_names_are_lowercase_dashed_ids(store: MemoryStore, name: str) -> None:
    with pytest.raises(ValueError):
        await store.write(name, description="d", content="")


async def test_a_description_is_required_and_short(store: MemoryStore) -> None:
    with pytest.raises(ValueError, match="description"):
        await store.write("fact", description="   ", content="text")
    with pytest.raises(ValueError, match=str(MAX_DESCRIPTION_CHARS)):
        await store.write("fact", description="x" * (MAX_DESCRIPTION_CHARS + 1), content="")


async def test_creating_a_taken_name_is_refused(store: MemoryStore) -> None:
    await store.write("fact", description="first", content="", create=True)
    with pytest.raises(FileExistsError):
        await store.write("fact", description="second", content="", create=True)
    assert store.get("fact").description == "first"
    # Without `create` the write is the replace it asks for.
    await store.write("fact", description="second", content="")
    assert store.get("fact").description == "second"


async def test_an_editor_does_not_overwrite_a_newer_entry(store: MemoryStore) -> None:
    first = await store.write("fact", description="first", content="")
    with pytest.raises(MemoryChangedOnDisk):
        await store.write(
            "fact", description="mine", content="", expected_updated_at="2000-01-01T00:00:00+00:00"
        )
    saved = await store.write(
        "fact", description="mine", content="", expected_updated_at=first.updated_at
    )
    assert saved.description == "mine"
    await store.delete("fact")
    with pytest.raises(FileNotFoundError):
        await store.write("fact", description="x", content="", expected_updated_at=saved.updated_at)


async def test_delete_removes_the_file_and_says_so(store: MemoryStore) -> None:
    await store.write("fact", description="d", content="")
    await store.delete("fact", author="team__qa")
    assert store.get("fact") is None
    store.bus.publish.assert_awaited_with(
        EventType.TEAM_MEMORY_CHANGED, {"name": "fact", "author": "team__qa", "deleted": True}
    )
    with pytest.raises(FileNotFoundError):
        await store.delete("fact")


def test_hand_written_files_are_entries_or_named_as_invalid(store: MemoryStore) -> None:
    folder = store.directory
    folder.mkdir(parents=True)
    (folder / "plain-note.md").write_text("Just text, no frontmatter.\n", encoding="utf-8")
    (folder / "no-description.md").write_text("---\nauthor: x\n---\nBody\n", encoding="utf-8")
    (folder / "broken.md").write_text("---\ndescription: [unclosed\n---\n", encoding="utf-8")
    (folder / "Capital.md").write_text("---\ndescription: d\n---\n", encoding="utf-8")
    (folder / "half.md.tmp").write_text("partial", encoding="utf-8")
    (folder / "notes.txt").write_text("not markdown", encoding="utf-8")

    entries, invalid = store.scan()
    assert [entry.name for entry in entries] == ["no-description", "plain-note"]
    assert all(entry.warning for entry in entries)
    assert entries[1].content == "Just text, no frontmatter."
    assert {item.file for item in invalid} == {"broken.md", "Capital.md"}


def test_frontmatter_variants_a_person_might_write(store: MemoryStore) -> None:
    store.directory.mkdir(parents=True)
    (store.directory / "spaced.md").write_text("--- \ndescription: d\n---\nBody\n", encoding="utf-8")
    (store.directory / "empty.md").write_text("---\n---\nBody\n", encoding="utf-8")
    entries, invalid = store.scan()
    assert not invalid
    spaced = next(entry for entry in entries if entry.name == "spaced")
    assert (spaced.description, spaced.content) == ("d", "Body")
    assert next(entry for entry in entries if entry.name == "empty").content == "Body"


def test_a_long_hand_written_description_is_cut_for_agents_only(store: MemoryStore) -> None:
    long = "x" * (MAX_DESCRIPTION_CHARS + 50)
    store.directory.mkdir(parents=True)
    (store.directory / "long.md").write_text(f"---\ndescription: {long}\n---\nBody\n", encoding="utf-8")
    entry = store.get("long")
    assert entry.description == long, "the editor must not be handed a shortened copy to save back"
    assert entry.warning
    line = entry.index_line()
    assert line.endswith("…") and len(line) <= len("- long — ") + MAX_DESCRIPTION_CHARS


async def test_search_matches_every_word_and_ranks_headings_first(store: MemoryStore) -> None:
    await store.write("save-format", description="Saves are JSON", content="Use the save schema.")
    await store.write("autosave", description="Autosave every 5 minutes", content="")
    await store.write("unrelated", description="Audio mixing", content="")
    assert [e.name for e in store.search("save")] == ["autosave", "save-format"]
    assert [e.name for e in store.search("schema save")] == ["save-format"]
    assert store.search("   ") == []


# ------------------------------------------------------------------ the index


async def test_the_index_lists_by_name_and_says_what_the_cap_left_out(store: MemoryStore) -> None:
    for name in ("charlie", "alpha", "bravo"):
        await store.write(name, description=f"about {name}", content="body text")
    full = store.index()
    assert full.text == "- alpha — about alpha\n- bravo — about bravo\n- charlie — about charlie"
    assert (full.total, full.shown, full.truncated) == (3, 3, False)

    capped = store.index(len("- alpha — about alpha\n- bravo — about bravo"))
    assert capped.text.splitlines()[:2] == ["- alpha — about alpha", "- bravo — about bravo"]
    assert "1 more entry not shown" in capped.text and "recall" in capped.text
    assert (capped.shown, capped.truncated) == (2, True)


def test_an_empty_memory_still_asks_runs_to_record(store: MemoryStore) -> None:
    assert render_index([]).text == ""
    assert memory_prompt(store, TeamsConfig(), has_tools=True) == EMPTY_INDEX


async def test_memory_reaches_a_run_only_when_on_and_usable(store: MemoryStore) -> None:
    await store.write("fact", description="A fact", content="")
    assert memory_prompt(store, TeamsConfig(), has_tools=True) == "- fact — A fact"
    assert memory_prompt(store, TeamsConfig(memory_enabled=False), has_tools=True) is None
    assert memory_prompt(store, TeamsConfig(), has_tools=False) is None


def _system_prompt(memory: str | None, prompts: AgentPromptsConfig | None = None) -> str:
    member = TeamMember(id="m", team_id="t", name="Ada", role_title="Developer")
    return _build_system_prompt(
        member=member, team=None, manager=None, reports=[], peers=[], task_id="VS-1",
        project_root=Path("/p"), workdir=Path("/p"), isolated=False, docs_dir=Path("/p/docs"),
        has_studio_tools=True, prompts=prompts or AgentPromptsConfig(), memory=memory,
    )


def test_the_system_prompt_carries_the_index_after_the_documents() -> None:
    prompt = _system_prompt("- fact — A fact")
    assert "Project memory holds the team's lasting facts" in prompt
    # The prompt sends the agent to the Studio's tools by name, and away from its own.
    assert "only through the Studio's tools, never your own memory" in prompt
    assert all(tool in prompt for tool in ("recall", "remember", "forget"))
    assert prompt.index("shared documents live") < prompt.index("Project memory") < prompt.index(
        "- fact — A fact"
    )
    assert "Project memory" not in _system_prompt(None)
    custom = _system_prompt("- fact — A fact", AgentPromptsConfig(project_memory="Known:\n{entries}"))
    assert "Known:\n- fact — A fact" in custom
    assert "Project memory" not in _system_prompt("- fact", AgentPromptsConfig(project_memory=""))


# ------------------------------------------------------------------ the tools


def _texts(result: dict) -> str:
    return "\n".join(part["text"] for part in result["content"] if part["type"] == "text")


@pytest.fixture
async def agent(ctx: AppContext) -> TeamMember:
    pytest.importorskip("claude_agent_sdk")
    async with ctx.db.session() as session:
        members = (await session.exec(select(TeamMember))).all()
    return next(member for member in members if member.kind == "agent")


def _tools(ctx: AppContext, member: TeamMember, **kwargs) -> dict:
    toolset = build_toolset(ctx.teams, member=member, **kwargs)
    assert toolset is not None
    return {definition.name: definition.handler for definition in toolset.definitions}


async def test_agents_remember_recall_and_forget(ctx: AppContext, agent: TeamMember) -> None:
    tools = _tools(ctx, agent)
    saved = await tools["remember"]({
        "name": "levels-live-in-content", "description": "Levels are in Content/Maps",
        "content": "Not in Content/Levels, which is legacy.",
    })
    assert not saved.get("is_error"), saved
    assert ctx.memory.get("levels-live-in-content").author == agent.id

    taken = await tools["remember"]({"name": "levels-live-in-content", "description": "Other", "content": ""})
    assert taken["is_error"] and "replace=true" in _texts(taken)
    replaced = await tools["remember"]({
        "name": "levels-live-in-content", "description": "Levels are in Content/Maps only",
        "content": "", "replace": True,
    })
    assert not replaced.get("is_error"), replaced
    assert ctx.memory.get("levels-live-in-content").description == "Levels are in Content/Maps only"

    whole = _texts(await tools["recall"]({"name": "levels-live-in-content"}))
    assert "description: Levels are in Content/Maps only" in whole and agent.name in whole
    assert "levels-live-in-content" in _texts(await tools["recall"]({"query": "maps"}))
    assert _texts(await tools["recall"]({})) == "- levels-live-in-content — Levels are in Content/Maps only"
    missing = await tools["recall"]({"name": "nope"})
    assert missing["is_error"]
    invalid = await tools["remember"]({"name": "Bad Name", "description": "d", "content": ""})
    assert invalid["is_error"] and "lowercase" in _texts(invalid)

    gone = await tools["forget"]({"name": "levels-live-in-content"})
    assert not gone.get("is_error")
    assert ctx.memory.get("levels-live-in-content") is None
    assert (await tools["forget"]({"name": "levels-live-in-content"}))["is_error"]


async def test_switched_off_memory_is_out_of_reach(ctx: AppContext, agent: TeamMember) -> None:
    ctx.config.teams.memory_enabled = False
    assert not {"recall", "remember", "forget"} & set(_tools(ctx, agent))
    ctx.config.teams.memory_enabled = True
    assert {"recall", "remember", "forget"} <= set(_tools(ctx, agent))


# ------------------------------------------------------------------ the page


def test_the_page_creates_edits_and_deletes(client: TestClient) -> None:
    empty = client.get("/api/memory").json()
    assert empty["enabled"] is True and empty["entries"] == []
    assert empty["path"] == ".voxarium_studio/memory"
    assert empty["index"] == {"text": "", "chars": 0, "total": 0, "shown": 0}
    assert empty["index_chars"] == 8000

    created = client.put(
        "/api/memory/art-style", json={"description": "Low-poly", "content": "Flat", "create": True}
    )
    assert created.status_code == 200, created.text
    entry = created.json()
    assert entry["content"] == "Flat" and entry["author"] is None
    assert client.put(
        "/api/memory/art-style", json={"description": "x", "create": True}
    ).status_code == 409

    stale = client.put(
        "/api/memory/art-style",
        json={"description": "x", "expected_updated_at": "2000-01-01T00:00:00+00:00"},
    )
    assert stale.status_code == 409
    edited = client.put(
        "/api/memory/art-style",
        json={"description": "Low-poly, flat", "content": "", "expected_updated_at": entry["updated_at"]},
    )
    assert edited.status_code == 200 and edited.json()["description"] == "Low-poly, flat"

    overview = client.get("/api/memory").json()
    assert [e["name"] for e in overview["entries"]] == ["art-style"]
    assert overview["index"]["text"] == "- art-style — Low-poly, flat"
    assert client.get("/api/memory/art-style").json()["description"] == "Low-poly, flat"

    assert client.get("/api/memory/nope").status_code == 404
    assert client.get("/api/memory/Bad%20Name").status_code == 400
    assert client.put("/api/memory/Bad%20Name", json={"description": "d"}).status_code == 400

    after = client.delete("/api/memory/art-style")
    assert after.status_code == 200 and after.json()["entries"] == []
    assert client.delete("/api/memory/art-style").status_code == 404


async def test_the_page_names_the_member_behind_an_entry(ctx: AppContext, agent: TeamMember) -> None:
    from voxarium_studio.api.memory import _overview

    await ctx.memory.write("fact", description="d", content="", author=agent.id)
    overview = await _overview(ctx)
    assert overview["entries"][0]["author_member"]["name"] == agent.name


def test_the_switch_and_cap_are_team_settings(client: TestClient) -> None:
    saved = client.put("/api/settings", json={"teams": {"memory_enabled": False, "memory_index_chars": 1200}})
    assert saved.status_code == 200, saved.text
    overview = client.get("/api/memory").json()
    assert overview["enabled"] is False and overview["index_chars"] == 1200
    assert client.put("/api/settings", json={"teams": {"memory_index_chars": 10}}).status_code == 422
