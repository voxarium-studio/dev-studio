"""Documents: the wiki-link graph, search, and the moves the editor needs (§10.4).

Links are the interesting part. A document is addressed by *name* — an agent writing a
brief says `[[dash-ability]]` without knowing where that file ended up — so resolution is
a search, and it has to agree with what the editor's preview decided or the backlinks
panel and the link itself would disagree.
"""

from __future__ import annotations

import asyncio
import os
from pathlib import Path
import subprocess
import sys

import pytest
from fastapi.testclient import TestClient

from voxarium_studio.core.context import AppContext
from voxarium_studio.explorer.service import PathOutsideProject
from voxarium_studio.teams.docs import MAX_ASSET_BYTES, DocStore, DocumentChangedOnDisk


def test_docstore_import_does_not_depend_on_context_import_order() -> None:
    """Conftest preloads AppContext; a clean interpreter catches sync/docs cycles."""
    process = subprocess.run(
        [sys.executable, "-c", "from voxarium_studio.teams.docs import DocStore"],
        capture_output=True,
        text=True,
        env={**os.environ, "PYTHONDONTWRITEBYTECODE": "1"},
        timeout=15,
    )
    assert process.returncode == 0, process.stderr


async def test_cancelled_final_snapshot_restores_document_admission(ctx: AppContext) -> None:
    """Cancellation while waiting is not a generation change or a stranded fence."""
    transitions = ctx.remote_transitions
    admission = await transitions.admit()
    entered = False

    async def snapshot() -> None:
        nonlocal entered
        async with transitions.final_snapshot():
            entered = True

    waiting = asyncio.create_task(snapshot())
    await asyncio.sleep(0)
    assert transitions.snapshot_pending
    assert not entered
    waiting.cancel()
    with pytest.raises(asyncio.CancelledError):
        await waiting
    assert not transitions.snapshot_pending
    assert transitions.generation == 0
    admission.release()
    await ctx.docs.write("after-cancel.md", "# Still writable\n")
    assert transitions.active_mutations == 0


async def test_cancelled_critical_wait_releases_document_admission(ctx: AppContext) -> None:
    """The mutation's finally owns cleanup even when it never gets the FS lock."""
    transitions = ctx.remote_transitions
    first = await transitions.admit()
    second = await transitions.admit()
    waiting_started = asyncio.Event()

    async def blocked() -> None:
        try:
            waiting_started.set()
            async with second.critical():
                pytest.fail("Cancelled writer acquired the filesystem section")
        finally:
            second.release()

    async with first.critical():
        task = asyncio.create_task(blocked())
        await waiting_started.wait()
        task.cancel()
        with pytest.raises(asyncio.CancelledError):
            await task
    first.release()
    assert transitions.active_mutations == 0
    async with transitions.final_snapshot() as fence:
        assert fence.advance_generation() == 1


async def test_final_snapshot_generation_capability_cannot_be_reused(ctx: AppContext) -> None:
    transitions = ctx.remote_transitions
    async with transitions.final_snapshot() as fence:
        assert fence.generation == 0
        assert fence.advance_generation() == 1
        with pytest.raises(RuntimeError, match="snapshot_fence_closed"):
            fence.advance_generation()
    with pytest.raises(RuntimeError, match="snapshot_fence_closed"):
        fence.advance_generation()
    assert transitions.generation == 1
    assert not transitions.snapshot_pending


@pytest.fixture
async def docs(ctx: AppContext) -> AppContext:
    await ctx.docs.write("features/dash.md", "# Dash ability\n\nSee [[test-plan]] and [[nothing]].\n")
    await ctx.docs.write("qa/test-plan.md", "# Test plan\n\nVerifies [[features/dash]] once.\n")
    await ctx.docs.write("notes/loose.md", "# Loose note\n\nNo links here.\n")
    return ctx


# ------------------------------------------------------------------ link resolution


async def test_links_resolve_by_path_stem_and_title(docs: AppContext) -> None:
    resolve = docs.docs.resolve_link
    assert resolve("features/dash.md") == "features/dash.md", "exact path"
    assert resolve("features/dash") == "features/dash.md", "path without the suffix"
    assert resolve("dash") == "features/dash.md", "bare stem anywhere in the tree"
    assert resolve("Dash ability") == "features/dash.md", "the document's title"
    assert resolve("nothing") is None
    assert resolve("") is None


async def test_link_resolution_prefers_the_most_specific_reference(ctx: AppContext) -> None:
    """Exact path, then path plus suffix, then stem, then title — in one corpus where every
    stage could fire, so a reordering shows up as a different document rather than as None."""
    await ctx.docs.write("dash.md", "# Dash top\n")
    await ctx.docs.write("features/dash.md", "# Dash ability\n")
    await ctx.docs.write("titled.md", "# features/dash\n")
    resolve = ctx.docs.resolve_link

    assert resolve("features/dash.md") == "features/dash.md", "the exact path wins outright"
    # `features/dash` is a title too, and the suffix stage runs first.
    assert resolve("features/dash") == "features/dash.md", "path plus suffix beats a title"
    # `dash` is the stem of both documents, and again the suffix stage answers first.
    assert resolve("dash") == "dash.md", "path plus suffix beats a stem"
    assert resolve("Dash ability") == "features/dash.md", "only a title reaches the title stage"


async def test_a_stem_and_a_path_are_one_pass_not_two(ctx: AppContext) -> None:
    """The stem stage and the path stage are one loop joined by ``or``, not two passes.

    Two maps consulted in sequence — every stem, then every path — answer differently the
    moment an earlier document matches by *path* while a later one matches by *stem*. The
    corpus below is contrived because it has to be: a path always carries a suffix, so the
    only query that can reach the path key is one that carries the suffix itself, and the only
    stem that can collide with it belongs to a document named ``<something>.md.md``.
    """
    await ctx.docs.write("beta.md", "# Beta\n")
    await ctx.docs.write("zeta/beta.md.md", "# Zeta beta\n")

    # `sorted(rglob("*"))` puts `beta.md` first, and its lowered *path* is the match.
    assert ctx.docs.resolve_link("BETA.MD") == "beta.md"
    # A stems-first pass would have answered with the other document's stem.
    assert ctx.docs.resolve_link("beta") == "beta.md", "the suffix stage still runs first"


async def test_a_suffixless_link_picks_the_suffix_in_a_fixed_order(ctx: AppContext) -> None:
    """``SUFFIX_ORDER`` is what decides which file ``[[plan]]`` means when three exist."""
    await ctx.docs.write("plan.markdown", "# Plan, markdown\n")
    await ctx.docs.write("plan.md", "# Plan, md\n")
    await ctx.docs.write("plan.txt", "# Plan, txt\n")

    assert ctx.docs.resolve_link("plan") == "plan.markdown"


async def test_the_title_stage_runs_only_after_every_stem(ctx: AppContext) -> None:
    """A title sorting first must still lose to a stem sorting last."""
    await ctx.docs.write("aaa.md", "# notes\n")
    await ctx.docs.write("zzz/notes.md", "# Release notes\n")

    assert ctx.docs.resolve_link("notes") == "zzz/notes.md"


async def test_a_document_reports_its_outgoing_links(docs: AppContext) -> None:
    doc = docs.docs.read("features/dash.md")
    by_target = {link["target"]: link for link in doc["links"]}

    assert by_target["test-plan"]["path"] == "qa/test-plan.md"
    assert by_target["test-plan"]["resolved"] is True
    # An unresolved link is not an error — it is an invitation to create the document.
    assert by_target["nothing"]["resolved"] is False
    assert by_target["nothing"]["path"] is None


async def test_labelled_links_keep_their_label(ctx: AppContext) -> None:
    await ctx.docs.write("a.md", "# A\n\n[[b|the other one]]\n")
    await ctx.docs.write("b.md", "# B\n")
    link = ctx.docs.read("a.md")["links"][0]
    assert link["target"] == "b" and link["label"] == "the other one"


async def test_backlinks_carry_the_line_they_sit_on(docs: AppContext) -> None:
    backlinks = docs.docs.backlinks("qa/test-plan.md")
    assert [entry["path"] for entry in backlinks] == ["features/dash.md"]
    assert backlinks[0]["contexts"] == ["See [[test-plan]] and [[nothing]]."]

    assert docs.docs.backlinks("notes/loose.md") == [], "nothing points at it"


async def test_a_document_does_not_link_to_itself(ctx: AppContext) -> None:
    await ctx.docs.write("self.md", "# Self\n\n[[self]]\n")
    assert ctx.docs.backlinks("self.md") == []
    assert ctx.docs.read("self.md")["links"][0]["path"] is None


async def test_a_line_naming_one_target_twice_is_one_context(ctx: AppContext) -> None:
    """The old scan asked ``any(...)`` per line, which short-circuits: the panel showed such a
    line once, and the index's per-line ``matched`` set is what reproduces that."""
    await ctx.docs.write("target.md", "# Target\n")
    await ctx.docs.write("src.md", "# Src\n\n[[target]] and [[target]] again.\n")

    contexts = ctx.docs.backlinks("target.md")[0]["contexts"]
    assert contexts == ["[[target]] and [[target]] again."]


async def test_a_line_naming_two_targets_appears_under_both(ctx: AppContext) -> None:
    await ctx.docs.write("one.md", "# One\n")
    await ctx.docs.write("two.md", "# Two\n")
    await ctx.docs.write("src.md", "# Src\n\nSee [[one]] and [[two]].\n")

    line = "See [[one]] and [[two]]."
    assert ctx.docs.backlinks("one.md")[0]["contexts"] == [line]
    assert ctx.docs.backlinks("two.md")[0]["contexts"] == [line]


async def test_a_backlink_context_is_stripped_and_truncated(ctx: AppContext) -> None:
    await ctx.docs.write("target.md", "# Target\n")
    await ctx.docs.write("src.md", "# Src\n\n   [[target]] " + ("x" * 400) + "   \n")

    context = ctx.docs.backlinks("target.md")[0]["contexts"][0]
    assert len(context) == 200
    assert context.startswith("[[target]] xxx")


# ------------------------------------------------------------------ search


async def test_search_ranks_title_hits_above_body_hits(docs: AppContext) -> None:
    results = docs.docs.search("dash")
    assert [entry["path"] for entry in results] == ["features/dash.md", "qa/test-plan.md"]
    assert results[0]["title_match"] is True
    assert results[1]["title_match"] is False


async def test_search_returns_the_matching_lines(docs: AppContext) -> None:
    results = docs.docs.search("verifies")
    assert results[0]["matches"][0]["text"] == "Verifies [[features/dash]] once."
    assert results[0]["matches"][0]["line"] == 3


async def test_search_is_case_insensitive_and_ignores_a_blank_query(docs: AppContext) -> None:
    assert docs.docs.search("DASH ABILITY")[0]["path"] == "features/dash.md"
    assert docs.docs.search("   ") == []


# ------------------------------------------------------------------ move and delete


async def test_moving_reports_the_documents_left_pointing_at_the_old_name(
    docs: AppContext,
) -> None:
    """Rewriting the referrers automatically would edit files nobody asked to touch."""
    moved = await docs.docs.move("qa/test-plan.md", "qa/plans/test-plan.md")

    assert moved["path"] == "qa/plans/test-plan.md"
    assert [entry["path"] for entry in moved["referrers"]] == ["features/dash.md"]
    # The stem is unchanged, so `[[test-plan]]` happens to still resolve — which is
    # exactly why the report matters when it does not.
    assert docs.docs.resolve_link("test-plan") == "qa/plans/test-plan.md"


async def test_moving_cleans_up_the_folder_it_emptied(docs: AppContext) -> None:
    await docs.docs.move("notes/loose.md", "loose.md")
    assert not (docs.paths.docs / "notes").exists()


async def test_moving_onto_an_existing_document_is_refused(docs: AppContext) -> None:
    with pytest.raises(FileExistsError):
        await docs.docs.move("notes/loose.md", "features/dash.md")


async def test_deleting_removes_the_file_and_its_empty_folders(docs: AppContext) -> None:
    await docs.docs.delete("notes/loose.md")
    assert not (docs.paths.docs / "notes").exists()
    assert "notes/loose.md" not in {entry.path for entry in docs.docs.list_docs()}

    with pytest.raises(FileNotFoundError):
        await docs.docs.delete("notes/loose.md")


# ------------------------------------------------------------------ boundary


@pytest.mark.parametrize("escape", ["../config.yaml", "../../app.db", "a/../../b.md"])
async def test_every_operation_refuses_to_climb_out_of_the_docs_directory(
    docs: AppContext, escape: str
) -> None:
    with pytest.raises(PathOutsideProject):
        docs.docs.resolve(escape)
    with pytest.raises(PathOutsideProject):
        await docs.docs.write(escape, "x")
    with pytest.raises(PathOutsideProject):
        await docs.docs.delete(escape)


@pytest.mark.parametrize("rooted", ["/notes/a.md", "\\notes\\b.md", "//notes/c.md"])
async def test_a_leading_separator_means_the_docs_root_not_the_filesystem_root(
    docs: AppContext, rooted: str
) -> None:
    """Obsidian reads `/thing` as "from the vault root", and so does this."""
    written = await docs.docs.write(rooted, "# Rooted\n")
    assert written["path"].startswith("notes/")
    assert docs.paths.is_inside_data(docs.docs.resolve(rooted))


async def test_only_text_documents_are_allowed(docs: AppContext) -> None:
    with pytest.raises(ValueError, match="Documents must be one of"):
        await docs.docs.write("script.exe", "x")
    # A path with no suffix at all gets one, rather than being refused.
    written = await docs.docs.write("bare", "# Bare\n")
    assert written["path"] == "bare.md"


# ------------------------------------------------------------------ over HTTP


def test_the_literal_routes_are_not_swallowed_by_the_catch_all(client: TestClient) -> None:
    """`/{path:path}` would happily match `search`, so declaration order carries weight."""
    client.put("/api/docs/features/dash.md", json={"content": "# Dash\n\n[[plan]]\n"})
    client.put("/api/docs/qa/plan.md", json={"content": "# Plan\n"})

    found = client.get("/api/docs/search", params={"q": "dash"})
    assert found.status_code == 200
    assert [entry["path"] for entry in found.json()] == ["features/dash.md"]

    moved = client.post("/api/docs/move", json={"source": "qa/plan.md", "target": "qa/plan-2.md"})
    assert moved.status_code == 200
    assert moved.json()["path"] == "qa/plan-2.md"


def test_the_http_surface_maps_failures_to_the_codes_the_editor_branches_on(
    client: TestClient,
) -> None:
    client.put("/api/docs/a.md", json={"content": "# A\n"})

    assert client.get("/api/docs/ghost.md").status_code == 404
    assert client.delete("/api/docs/ghost.md").status_code == 404
    assert client.post("/api/docs/move", json={"source": "ghost.md", "target": "x.md"}).status_code == 404

    client.put("/api/docs/b.md", json={"content": "# B\n"})
    conflict = client.post("/api/docs/move", json={"source": "a.md", "target": "b.md"})
    assert conflict.status_code == 409

    assert client.delete("/api/docs/a.md").json() == {"path": "a.md", "deleted": True}


def test_reading_a_document_carries_its_links_and_backlinks(client: TestClient) -> None:
    client.put("/api/docs/spec.md", json={"content": "# Spec\n\nSee [[plan]].\n"})
    client.put("/api/docs/plan.md", json={"content": "# Plan\n"})

    spec = client.get("/api/docs/spec.md").json()
    assert spec["links"][0]["path"] == "plan.md"

    plan = client.get("/api/docs/plan.md").json()
    assert [entry["path"] for entry in plan["backlinks"]] == ["spec.md"]


# ------------------------------------------------------------------ conditional writes


async def test_a_conditional_write_refuses_to_clobber_a_newer_file(ctx: AppContext) -> None:
    """The block editor holds a whole parsed document, so a blind save loses the other write.

    One user, and still reachable: an agent's ``write_doc`` lands while the document is open,
    or a branch switch rewrites the working copy (block-editor spec §14.5).
    """
    first = await ctx.docs.write("spec.md", "# Spec\n")

    # Someone else writes. `updated_at` comes from mtime, so it has to actually move.
    path = ctx.docs.resolve("spec.md")
    stat = path.stat()
    os.utime(path, (stat.st_atime, stat.st_mtime + 2))
    await ctx.docs.write("spec.md", "# Spec, from an agent\n")

    with pytest.raises(DocumentChangedOnDisk):
        await ctx.docs.write("spec.md", "# Mine\n", expected_updated_at=str(first["updated_at"]))

    assert ctx.docs.read("spec.md")["content"] == "# Spec, from an agent\n"


async def test_a_conditional_write_goes_through_when_nothing_moved(ctx: AppContext) -> None:
    written = await ctx.docs.write("spec.md", "# Spec\n")
    await ctx.docs.write("spec.md", "# Edited\n", expected_updated_at=str(written["updated_at"]))
    assert ctx.docs.read("spec.md")["content"] == "# Edited\n"


async def test_an_unconditional_write_still_overwrites(ctx: AppContext) -> None:
    """Agents pass no expectation: their write is the authority on their own document."""
    await ctx.docs.write("spec.md", "# Spec\n")
    await ctx.docs.write("spec.md", "# From an agent\n")
    assert ctx.docs.read("spec.md")["content"] == "# From an agent\n"


async def test_creating_a_document_conditionally_is_not_a_conflict(ctx: AppContext) -> None:
    """There is nothing to clobber when the file does not exist yet."""
    await ctx.docs.write("new.md", "# New\n", expected_updated_at="1970-01-01T00:00:00+00:00")
    assert ctx.docs.read("new.md")["content"] == "# New\n"


async def test_creating_a_document_never_writes_over_one_already_there(ctx: AppContext) -> None:
    """"New document" given a taken name would otherwise empty that document down to a heading."""
    notes = "# Dash ability\n\nThree weeks of notes.\n"
    await ctx.docs.write("features/dash.md", notes)
    seq = ctx.bus.current_seq

    with pytest.raises(FileExistsError, match="'features/dash.md' already exists"):
        await ctx.docs.write("features/dash.md", "# dash\n\n", exclusive=True)
    if os.name == "nt":
        # The filesystem decides what is taken, and on Windows the case of a name does not.
        with pytest.raises(FileExistsError):
            await ctx.docs.write("Features/Dash.md", "# Dash\n\n", exclusive=True)

    assert ctx.docs.read("features/dash.md")["content"] == notes
    assert not [event for event in ctx.bus.replay(seq) if event.type == "team.doc.changed"]

    created = await ctx.docs.write("features/combat tuning.md", "# tuning\n\n", exclusive=True)
    assert created["path"] == "features/combat tuning.md"


def test_the_http_surface_answers_409_for_a_stale_save(client: TestClient) -> None:
    created = client.put("/api/docs/spec.md", json={"content": "# Spec\n"}).json()

    stale = client.put(
        "/api/docs/spec.md",
        json={"content": "# Mine\n", "expected_updated_at": "1999-01-01T00:00:00+00:00"},
    )
    assert stale.status_code == 409

    fresh = client.put(
        "/api/docs/spec.md",
        json={"content": "# Mine\n", "expected_updated_at": created["updated_at"]},
    )
    assert fresh.status_code == 200

    taken = client.put("/api/docs/spec.md", json={"content": "# spec\n\n", "exclusive": True})
    assert taken.status_code == 409
    assert taken.json()["detail"] == "'spec.md' already exists"
    assert client.get("/api/docs/spec.md").json()["content"] == "# Mine\n"
    new = client.put("/api/docs/plan.md", json={"content": "# plan\n\n", "exclusive": True})
    assert new.status_code == 200


async def test_a_write_answers_with_the_same_shape_a_read_does(ctx: AppContext) -> None:
    """The editor adopts the payload it gets back, so a save has to rebuild both panels.

    Backlinks change *because* of a save — that is the moment a link is added or removed —
    and a response without them left the side panel showing the state before the edit
    (block-editor spec §5).
    """
    await ctx.docs.write("qa/plan.md", "# Plan\n")
    written = await ctx.docs.write("spec.md", "# Spec\n\nSee [[plan]] and [[nothing]].\n")

    assert {"links", "backlinks", "content"} <= set(written)
    assert [link["target"] for link in written["links"]] == ["plan", "nothing"]
    assert [link["resolved"] for link in written["links"]] == [True, False]

    # And the document being linked *to* sees it on its next write.
    plan = await ctx.docs.write("qa/plan.md", "# Plan\n\nEdited.\n")
    assert [entry["path"] for entry in plan["backlinks"]] == ["spec.md"]


# ------------------------------------------------------------------ the bytes on disk


async def test_reading_keeps_the_file_s_own_line_endings(ctx: AppContext) -> None:
    """A CRLF document must come back as CRLF (block-editor spec §7).

    Python translates line endings on read by default, and the editor writes back what it
    was given — so a document authored on Windows came back LF, and the first save rewrote
    every line of it. §8.2 asks for the opposite: a save moves only what was edited.
    """
    path = ctx.paths.docs / "windows.md"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(b"# Title\r\n\r\nA line.\r\n")

    assert ctx.docs.read("windows.md")["content"] == "# Title\r\n\r\nA line.\r\n"


async def test_writing_leaves_the_line_endings_it_was_given(ctx: AppContext) -> None:
    await ctx.docs.write("windows.md", "# Title\r\n\r\nEdited.\r\n")
    assert (ctx.paths.docs / "windows.md").read_bytes() == b"# Title\r\n\r\nEdited.\r\n"


async def test_a_write_that_fails_leaves_no_half_file_behind(ctx: AppContext) -> None:
    """The temporary file goes with the failure.

    The write is a write-then-rename so a reader never sees a half-written document (§7).
    When the rename is what fails — a read-only file, one held open by another program —
    the temporary copy is left in the user's docs folder, where it turns up in their next
    commit.
    """
    await ctx.docs.write("spec.md", "# Spec\n")
    target = ctx.paths.docs / "spec.md"

    def refuse(self: object, other: object) -> None:
        raise PermissionError(13, "Access is denied")

    with pytest.MonkeyPatch.context() as patch:
        patch.setattr(Path, "replace", refuse)
        with pytest.raises(PermissionError):
            await ctx.docs.write("spec.md", "# Edited\n")

    assert target.read_text(encoding="utf-8") == "# Spec\n", "the original is untouched"
    assert [entry.name for entry in ctx.paths.docs.iterdir()] == ["spec.md"]


# ------------------------------------------------------------------ the index


async def test_an_external_write_is_seen_on_the_next_call(ctx: AppContext) -> None:
    """The index is validated against a fresh stat, so nothing survives a write we did not make.

    An agent's tool call, the user's own editor and a branch switch all rewrite these files
    behind the Studio's back — which is why only the file *bodies* are cached and the
    ``(path, mtime_ns, size)`` signature is recomputed on every call.
    """
    await ctx.docs.write("target.md", "# Target\n")
    await ctx.docs.write("src.md", "# Src\n\nNothing yet.\n")
    assert ctx.docs.read("target.md")["backlinks"] == [], "warms the index"

    path = ctx.paths.docs / "src.md"
    stat = path.stat()
    path.write_text("# Src\n\nSee [[target]] now.\n", encoding="utf-8")
    # `updated_at` and the signature both come from mtime, so it has to actually move.
    os.utime(path, (stat.st_atime, stat.st_mtime + 2))

    assert [entry["path"] for entry in ctx.docs.backlinks("target.md")] == ["src.md"]
    assert [entry["path"] for entry in ctx.docs.search("now")] == ["src.md"]
    assert ctx.docs.graph()["edges"] == [{"source": "src.md", "target": "target.md"}]
    sizes = {entry.path: entry.size_bytes for entry in ctx.docs.list_docs()}
    assert sizes["src.md"] == len(path.read_bytes())


async def test_an_externally_added_or_deleted_document_is_seen(ctx: AppContext) -> None:
    await ctx.docs.write("keep.md", "# Keep\n\n[[gone]]\n")
    await ctx.docs.write("gone.md", "# Gone\n")
    assert ctx.docs.read("keep.md")["links"][0]["resolved"] is True, "warms the index"

    (ctx.paths.docs / "gone.md").unlink()
    assert ctx.docs.read("keep.md")["links"][0]["resolved"] is False
    assert [entry.path for entry in ctx.docs.list_docs()] == ["keep.md"]

    (ctx.paths.docs / "gone.md").write_text("# Gone again\n", encoding="utf-8")
    assert ctx.docs.read("keep.md")["links"][0]["path"] == "gone.md"


async def test_a_document_windows_resolves_with_a_prefix_is_still_inside_the_tree(
    ctx: AppContext, monkeypatch: pytest.MonkeyPatch
) -> None:
    """``Path.resolve`` may keep Windows' ``\\\\?\\`` prefix, and only on the locked file.

    ``ntpath.realpath`` drops that prefix only if a second query verifies the stripped form,
    which fails while another process holds the file — the user's editor, Defender, a Plastic
    update. The docs root beside it resolves without one, so the two spellings disagree and
    every comparison between them reads as "outside the tree": a 500 out of the scan for the
    whole section, and a 403 on a path that is perfectly legal.

    Simulated rather than provoked, because holding a handle the way Windows would is not
    something a test can arrange portably — and the comparison is what is being pinned.
    """
    await ctx.docs.write("features/dash.md", "# Dash\n\n[[spec]]\n")
    await ctx.docs.write("spec.md", "# Spec\n")

    real = Path.resolve
    locked = ctx.paths.docs / "features" / "dash.md"

    def resolve(self: Path, strict: bool = False) -> Path:
        resolved = real(self, strict) if strict else real(self)
        return Path("\\\\?\\" + str(resolved)) if resolved == real(locked) else resolved

    monkeypatch.setattr(Path, "resolve", resolve)

    assert [entry.path for entry in ctx.docs.list_docs()] == ["features/dash.md", "spec.md"]
    assert ctx.docs.read("features/dash.md")["links"][0]["path"] == "spec.md"
    assert ctx.docs.read("spec.md")["backlinks"][0]["path"] == "features/dash.md"


async def test_a_link_out_of_the_docs_tree_is_still_refused(ctx: AppContext) -> None:
    """The prefix is a spelling; escaping the tree is not. Normalising one must not excuse
    the other, so the traversal guard is asserted beside its fix."""
    with pytest.raises(PathOutsideProject):
        ctx.docs.resolve("../../secrets.md")


async def test_a_conditional_write_still_compares_a_live_stat(ctx: AppContext) -> None:
    """Staleness is the one question that may never be answered from the index.

    ``write`` takes ``current`` from ``_entry``, i.e. a real ``stat``; routing it through the
    cache would let a blind save clobber exactly the agent write the check exists to refuse.
    """
    first = await ctx.docs.write("spec.md", "# Spec\n")
    ctx.docs.read("spec.md")  # warms the index against the file as it is now

    path = ctx.docs.resolve("spec.md")
    stat = path.stat()
    os.utime(path, (stat.st_atime, stat.st_mtime + 2))

    with pytest.raises(DocumentChangedOnDisk):
        await ctx.docs.write("spec.md", "# Mine\n", expected_updated_at=str(first["updated_at"]))


async def test_reading_a_document_opens_each_file_once(ctx: AppContext) -> None:
    """A read must cost one open per document, not one per link *per* document.

    ``resolve_link`` used to relist the whole tree for every ``[[wiki link]]`` in the corpus,
    so a sample project with 49 documents opened 27,931 files and spent 10.4 s answering one read — on the
    event loop, since the docs routes are ``async def``. Wall clock does not fail loudly
    enough to keep that from coming back; the open count does.
    """
    for number in range(12):
        links = "".join(f"[[doc-{other}]] " for other in range(6))
        await ctx.docs.write(f"doc-{number}.md", f"# Doc {number}\n\n{links}\n")

    # A store of its own, so the first read below really is a cold one: every ``write`` above
    # left ``ctx.docs`` holding a freshly built index.
    store = DocStore(ctx.paths)
    opens = 0
    real_open = Path.open

    def counted(self: Path, *args: object, **kwargs: object):  # type: ignore[no-untyped-def]
        nonlocal opens
        opens += 1
        return real_open(self, *args, **kwargs)

    # ``Path.read_text`` goes through ``Path.open``, so this counts every body read too.
    with pytest.MonkeyPatch.context() as patch:
        patch.setattr(Path, "open", counted)
        store.read("doc-0.md")
        first = opens
        opens = 0
        store.read("doc-0.md")
        second = opens

    assert first <= 12 + 3, "one body read per document, plus the document being read"
    assert second <= 3, "nothing changed on disk, so the second read reuses the index"


# ------------------------------------------------------------------ the link graph


async def test_the_graph_lists_every_document_and_its_resolved_links(docs: AppContext) -> None:
    graph = docs.docs.graph()

    assert [node["path"] for node in graph["nodes"]] == [
        "features/dash.md",
        "notes/loose.md",
        "qa/test-plan.md",
    ], "sorted by path, and a document nothing points at is still a node"
    assert graph["edges"] == [
        {"source": "features/dash.md", "target": "qa/test-plan.md"},
        {"source": "qa/test-plan.md", "target": "features/dash.md"},
    ]
    degrees = {node["path"]: (node["in_degree"], node["out_degree"]) for node in graph["nodes"]}
    assert degrees == {
        "features/dash.md": (1, 1),
        "notes/loose.md": (0, 0),
        "qa/test-plan.md": (1, 1),
    }


async def test_the_graph_excludes_a_self_link(ctx: AppContext) -> None:
    await ctx.docs.write("self.md", "# Self\n\n[[self]]\n")
    graph = ctx.docs.graph()

    assert graph["edges"] == []
    assert graph["nodes"] == [
        {"path": "self.md", "title": "Self", "in_degree": 0, "out_degree": 0}
    ]


async def test_the_graph_excludes_an_unresolved_link(ctx: AppContext) -> None:
    """An unresolved link is an invitation to create a document, not an edge to nowhere."""
    await ctx.docs.write("spec.md", "# Spec\n\n[[nothing]]\n")
    graph = ctx.docs.graph()

    assert graph["edges"] == []
    assert graph["nodes"][0]["out_degree"] == 0


async def test_the_graph_counts_a_repeated_link_once(ctx: AppContext) -> None:
    await ctx.docs.write("plan.md", "# Plan\n")
    await ctx.docs.write("spec.md", "# Spec\n\n[[plan]] and again [[plan]].\n")
    graph = ctx.docs.graph()

    assert graph["edges"] == [{"source": "spec.md", "target": "plan.md"}]
    degrees = {node["path"]: (node["in_degree"], node["out_degree"]) for node in graph["nodes"]}
    assert degrees == {"plan.md": (1, 0), "spec.md": (0, 1)}


def test_the_graph_route_is_not_swallowed_by_the_catch_all(client: TestClient) -> None:
    """`/graph` is declared above `/{path:path}`, and a real `graph.md` still reads as a document."""
    client.put("/api/docs/spec.md", json={"content": "# Spec\n\n[[plan]]\n"})
    client.put("/api/docs/plan.md", json={"content": "# Plan\n"})

    graph = client.get("/api/docs/graph")
    assert graph.status_code == 200
    assert graph.json()["edges"] == [{"source": "spec.md", "target": "plan.md"}]

    client.put("/api/docs/graph.md", json={"content": "# A document called graph\n"})
    document = client.get("/api/docs/graph.md")
    assert document.status_code == 200
    assert document.json()["title"] == "A document called graph"

    assert set(client.get("/api/docs/graph").json()) == {"nodes", "edges"}


# ------------------------------------------------------------------ pictures

PNG = b"\x89PNG\r\n\x1a\n"


async def test_a_picture_resolves_against_the_document_s_own_folder(ctx: AppContext) -> None:
    """Images sit beside the document, so `![](shot.png)` means the file in *that* folder."""
    await ctx.docs.write("guide/spec.md", "# Spec\n\n![shot](shot.png)\n")
    await ctx.docs.write("qa/plan.md", "# Plan\n")
    (ctx.docs.directory / "guide" / "shot.png").write_bytes(PNG)

    assert ctx.docs.asset("guide/spec.md", "shot.png").read_bytes() == PNG
    with pytest.raises(FileNotFoundError):
        # Same reference, a document one folder over: it is not the same picture.
        ctx.docs.asset("qa/plan.md", "shot.png")


async def test_a_picture_may_live_outside_the_docs_tree_but_not_outside_the_project(
    ctx: AppContext,
) -> None:
    """A design document pointing at the game's art is a reference, not an escape."""
    await ctx.docs.write("guide/spec.md", "# Spec\n")
    art = ctx.paths.root / "Assets"
    art.mkdir(parents=True, exist_ok=True)
    (art / "hero.png").write_bytes(PNG)

    # guide -> docs -> .voxarium_studio -> the project root, where the game keeps its art.
    assert ctx.docs.asset("guide/spec.md", "../../../Assets/hero.png").read_bytes() == PNG

    outside = ctx.paths.root.parent / "elsewhere.png"
    outside.write_bytes(PNG)
    with pytest.raises(PathOutsideProject):
        ctx.docs.asset("guide/spec.md", "../../../../elsewhere.png")


async def test_a_leading_separator_in_a_reference_means_the_docs_root(ctx: AppContext) -> None:
    await ctx.docs.write("guide/spec.md", "# Spec\n")
    (ctx.docs.directory / "shared.png").write_bytes(PNG)

    assert ctx.docs.asset("guide/spec.md", "/shared.png").read_bytes() == PNG


async def test_an_encoded_reference_is_decoded_and_a_literal_percent_still_resolves(
    ctx: AppContext,
) -> None:
    """An exported document writes a space as `%20`; a file may also just be named that way."""
    await ctx.docs.write("spec.md", "# Spec\n")
    (ctx.docs.directory / "a shot.png").write_bytes(PNG)
    (ctx.docs.directory / "raw%20name.png").write_bytes(b"raw")

    assert ctx.docs.asset("spec.md", "a%20shot.png").read_bytes() == PNG
    assert ctx.docs.asset("spec.md", "raw%20name.png").read_bytes() == b"raw"


async def test_a_reference_that_is_not_a_project_picture_is_refused(ctx: AppContext) -> None:
    await ctx.docs.write("spec.md", "# Spec\n")
    (ctx.docs.directory / "notes.txt").write_bytes(b"text")

    for remote in ("https://example.invalid/i.png", "data:image/png;base64,AAAA", "C:/x.png"):
        with pytest.raises(ValueError):
            ctx.docs.asset("spec.md", remote)

    with pytest.raises(ValueError):
        # Present, inside the project, and not a picture: the route serves images only.
        ctx.docs.asset("spec.md", "notes.txt")
    with pytest.raises(FileNotFoundError):
        ctx.docs.asset("spec.md", "ghost.png")
    with pytest.raises(ValueError):
        ctx.docs.asset("spec.md", "")


def test_the_asset_route_is_not_swallowed_by_the_catch_all(
    client: TestClient, project: Path
) -> None:
    """`/asset` joins `/search` and `/graph` above `/{path:path}`, brackets in a name and all."""
    client.put("/api/docs/guide/spec.md", json={"content": "# Spec\n"})
    (project / ".voxarium_studio" / "docs" / "guide" / "Shot (1).png").write_bytes(PNG)

    served = client.get(
        "/api/docs/asset", params={"path": "guide/spec.md", "src": "Shot (1).png"}
    )
    assert served.status_code == 200
    assert served.content == PNG

    client.put("/api/docs/asset.md", json={"content": "# A document called asset\n"})
    document = client.get("/api/docs/asset.md")
    assert document.status_code == 200
    assert document.json()["title"] == "A document called asset"


def test_a_picture_is_answered_conditionally(client: TestClient, project: Path) -> None:
    """A document holding a dozen photographs must not re-download them on every remount."""
    client.put("/api/docs/spec.md", json={"content": "# Spec\n"})
    picture = project / ".voxarium_studio" / "docs" / "shot.png"
    picture.write_bytes(PNG)

    first = client.get("/api/docs/asset", params={"path": "spec.md", "src": "shot.png"})
    etag = first.headers["etag"]
    assert first.headers["cache-control"] == "no-cache"
    # `inline`, unlike the Explorer's `/raw`: this URL is only ever an `<img>` source.
    assert first.headers["content-disposition"].startswith("inline")

    again = client.get(
        "/api/docs/asset",
        params={"path": "spec.md", "src": "shot.png"},
        headers={"If-None-Match": etag},
    )
    assert again.status_code == 304
    assert again.content == b""

    # Replaced on disk, so the tag has to move — otherwise the editor shows the old picture.
    os.utime(picture, ns=(1, 2_000_000_000))
    picture.write_bytes(PNG + b"more")
    changed = client.get(
        "/api/docs/asset",
        params={"path": "spec.md", "src": "shot.png"},
        headers={"If-None-Match": etag},
    )
    assert changed.status_code == 200
    assert changed.content == PNG + b"more"


async def test_a_dropped_picture_lands_beside_the_document(ctx: AppContext) -> None:
    """The mirror of `asset`: what `add_asset` writes is what a reference then resolves."""
    await ctx.docs.write("guide/spec.md", "# Spec\n")

    added = ctx.docs.add_asset("guide/spec.md", "shot.png", PNG)
    assert added == {
        "src": "shot.png",
        "alt": "shot",
        "name": "shot.png",
        "path": "guide/shot.png",
        "size": len(PNG),
    }
    assert (ctx.docs.directory / "guide" / "shot.png").read_bytes() == PNG
    # The reference it hands back is one `asset` resolves — the two halves have to agree.
    assert ctx.docs.asset("guide/spec.md", str(added["src"])).read_bytes() == PNG


async def test_a_dropped_name_is_kept_but_never_trusted(ctx: AppContext) -> None:
    await ctx.docs.write("guide/spec.md", "# Spec\n")
    add = lambda name: ctx.docs.add_asset("guide/spec.md", name, PNG)["name"]  # noqa: E731

    assert add("Screen shot.png") == "Screen shot.png", "the name people recognise is kept"
    assert add("../../escape.png") == "escape.png", "only the last component survives"
    assert add(r"C:\Users\me\hero.png") == "hero.png", "a full Windows path, as some browsers send"
    assert add("a<b>c:d.png") == "a-b-c-d.png"
    assert add(".hidden.png") == "hidden.png", "a dropped picture is not a dotfile"

    for path in ("escape.png", "hero.png"):
        assert not (ctx.docs.directory / path).exists(), "nothing landed outside the folder"


async def test_a_dropped_picture_never_overwrites_one_already_there(ctx: AppContext) -> None:
    await ctx.docs.write("spec.md", "# Spec\n")
    first = ctx.docs.add_asset("spec.md", "shot.png", PNG)
    second = ctx.docs.add_asset("spec.md", "shot.png", b"different bytes entirely")

    assert (first["name"], second["name"]) == ("shot.png", "shot-2.png")
    assert (ctx.docs.directory / "shot.png").read_bytes() == PNG, "the first one is untouched"


async def test_a_name_with_a_space_comes_back_as_a_usable_reference(ctx: AppContext) -> None:
    """A bare space would end the Markdown link, so the reference is percent-encoded — and
    `asset` decodes it again, which is what keeps the round trip closed."""
    await ctx.docs.write("spec.md", "# Spec\n")
    added = ctx.docs.add_asset("spec.md", "a shot.png", PNG)

    assert (added["src"], added["name"]) == ("a%20shot.png", "a shot.png")
    assert ctx.docs.asset("spec.md", str(added["src"])).read_bytes() == PNG


async def test_only_a_picture_may_be_dropped_in(ctx: AppContext) -> None:
    await ctx.docs.write("spec.md", "# Spec\n")

    with pytest.raises(ValueError, match="Only images"):
        ctx.docs.add_asset("spec.md", "notes.txt", b"text")
    with pytest.raises(ValueError, match="Only images"):
        # Nothing survives sanitising, so there is no suffix to judge the bytes by — and
        # inventing one would write a file claiming to be a picture on no evidence.
        ctx.docs.add_asset("spec.md", "...", PNG)
    with pytest.raises(ValueError, match="Only images"):
        ctx.docs.add_asset("spec.md", "screenshot", PNG)
    with pytest.raises(ValueError, match="empty"):
        ctx.docs.add_asset("spec.md", "shot.png", b"")
    with pytest.raises(ValueError, match="exceeds"):
        ctx.docs.add_asset("spec.md", "shot.png", b"x" * (MAX_ASSET_BYTES + 1))

    assert list(ctx.docs.directory.glob("*.png")) == [], "nothing was written for any of them"


def test_the_upload_route_writes_the_file_and_answers_with_the_reference(
    client: TestClient, project: Path
) -> None:
    client.put("/api/docs/guide/spec.md", json={"content": "# Spec\n"})

    added = client.post(
        "/api/docs/asset",
        data={"path": "guide/spec.md"},
        files={"file": ("dropped shot.png", PNG, "image/png")},
    )
    assert added.status_code == 201
    assert added.json()["src"] == "dropped%20shot.png"
    assert (project / ".voxarium_studio" / "docs" / "guide" / "dropped shot.png").read_bytes() == PNG

    # And the picture is immediately servable under the reference it answered with.
    served = client.get(
        "/api/docs/asset", params={"path": "guide/spec.md", "src": added.json()["src"]}
    )
    assert served.status_code == 200 and served.content == PNG

    refused = client.post(
        "/api/docs/asset",
        data={"path": "guide/spec.md"},
        files={"file": ("notes.txt", b"text", "text/plain")},
    )
    assert refused.status_code == 400


def test_the_asset_route_maps_failures_to_the_codes_the_editor_branches_on(
    client: TestClient,
) -> None:
    client.put("/api/docs/spec.md", json={"content": "# Spec\n"})

    missing = client.get("/api/docs/asset", params={"path": "spec.md", "src": "ghost.png"})
    assert missing.status_code == 404

    remote = client.get(
        "/api/docs/asset", params={"path": "spec.md", "src": "https://example.invalid/i.png"}
    )
    assert remote.status_code == 400

    # `httpx` normalises `..` out of a URL before sending, so the traversal has to be encoded.
    escaping = client.get("/api/docs/asset?path=spec.md&src=%2e%2e%2f%2e%2e%2f%2e%2e%2fhero.png")
    assert escaping.status_code == 403
