"""Automations: ``.voxarium_studio/automations.yaml``, the engine that runs it off the bus, the API (§6.5).

The engine is driven the way the Studio drives it — a real status move through the
orchestrator, a real pipeline run — except for file changes, which are published on the bus
directly: the watcher's own latency is the watcher's test, and what matters here is what the
engine does with a batch once one arrives.
"""

from __future__ import annotations

import asyncio
import contextlib
import time
from collections.abc import Callable
from pathlib import Path
from textwrap import dedent

import pytest
import yaml
from fastapi.testclient import TestClient

from voxarium_studio.automations import engine as automation_engine
from voxarium_studio.automations.schema import Automation, PathTrigger, normalise_path
from voxarium_studio.automations.store import AutomationStore
from voxarium_studio.core.context import AppContext
from voxarium_studio.core.paths import ProjectPaths
from voxarium_studio.core.security import AuthSettings
from voxarium_studio.models.automation import AutomationRun
from voxarium_studio.models.teams import TaskStatus
from voxarium_studio.schemas.pipeline import PipelineDefinition
from voxarium_studio.ws.events import EventType

TEAM = "team_default"
DIRECTOR = f"{TEAM}__director"  # a person: no session is ever started for their tasks

NOTIFY_ON_REVIEW = {
    "id": "notify-review",
    "name": "Review ping",
    "trigger": {"kind": "task.status_changed", "status": "needs_review"},
    "action": {"kind": "notify", "title": "{task} needs review", "message": "{title} — {assignee}"},
}


def write_automations(project: Path, *entries: dict) -> None:
    ProjectPaths.for_project(project).automations_file.write_text(
        yaml.safe_dump({"automations": list(entries)}, sort_keys=False), encoding="utf-8"
    )


async def eventually(predicate: Callable[[], bool], timeout: float = 5.0) -> None:
    deadline = time.monotonic() + timeout
    while not predicate():
        if time.monotonic() > deadline:
            raise AssertionError("condition not met in time")
        await asyncio.sleep(0.01)


def events(ctx: AppContext, event_type: EventType) -> list[dict]:
    return [event.data for event in ctx.bus.replay(0) if event.type == event_type]


async def logged(ctx: AppContext, count: int) -> list:
    """Wait for ``count`` log rows; newest first, as the page lists them."""
    rows: list = []

    async def enough() -> bool:
        nonlocal rows
        rows = await ctx.automations.recent()
        return len(rows) >= count

    deadline = time.monotonic() + 5.0
    while not await enough():
        if time.monotonic() > deadline:
            raise AssertionError(f"expected {count} log rows, have {len(rows)}")
        await asyncio.sleep(0.01)
    return rows


async def settled_row(ctx: AppContext, row_id: str, timeout: float = 5.0) -> AutomationRun:
    """The row once its work has ended — whatever it ended as."""
    deadline = time.monotonic() + timeout
    while True:
        async with ctx.db.session() as session:
            row = await session.get(AutomationRun, row_id)
        if row is not None and row.status != "running":
            return row
        if time.monotonic() > deadline:
            raise AssertionError(f"row {row_id} is still {row.status if row else 'missing'}")
        await asyncio.sleep(0.01)


async def row_where(ctx: AppContext, row_id: str, check: Callable[[AutomationRun], bool]) -> AutomationRun:
    """The row once ``check`` holds — an action's report lands on a row already in the log."""
    deadline = time.monotonic() + 5.0
    while True:
        async with ctx.db.session() as session:
            row = await session.get(AutomationRun, row_id)
        if row is not None and check(row):
            return row
        if time.monotonic() > deadline:
            raise AssertionError(f"row {row_id} never got there: {row}")
        await asyncio.sleep(0.01)


@contextlib.asynccontextmanager
async def opened(project: Path):
    ctx = AppContext.create(str(project), AuthSettings())
    await ctx.startup()
    try:
        yield ctx
    finally:
        await ctx.shutdown()


@pytest.fixture
def quick_settle(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(automation_engine, "PATH_SETTLE_SECONDS", 0.05)
    monkeypatch.setattr(automation_engine, "RUN_ECHO_SECONDS", 0.2)
    monkeypatch.setattr(automation_engine, "RUNNING_AFTER", 0.05)


def pipeline(pipeline_id: str = "bake", seconds: float = 0) -> PipelineDefinition:
    return PipelineDefinition.model_validate(
        {
            "id": pipeline_id,
            "name": "Bake models",
            "nodes": [{"id": "a", "type": "core.delay", "params": {"seconds": seconds, "value": "x"}}],
            "edges": [],
        }
    )


# ------------------------------------------------------------------ the file


def test_a_path_is_spelled_one_way_and_may_not_leave_the_project() -> None:
    assert normalise_path("./models/") == "models"
    assert normalise_path("Assets\\Models\\") == "Assets/Models"
    assert normalise_path("./") == "" and normalise_path("") == ""
    for escaping in ("../elsewhere", "models/../../x", "/etc", "D:/Unreal", "D:\\Unreal"):
        with pytest.raises(ValueError):
            normalise_path(escaping)


def test_a_path_trigger_matches_its_folder_its_pattern_and_its_changes() -> None:
    trigger = PathTrigger(path="./models/", pattern="*.FBX", changes=["modified", "added"])
    assert trigger.changes == ["added", "modified"], "stored in one order, so a save is stable"
    assert trigger.matches("models/hero.fbx", "added")
    assert trigger.matches("models/props/Crate.fbx", "modified"), "the name, at any depth"
    assert not trigger.matches("models/hero.fbx", "deleted")
    assert not trigger.matches("models/hero.png", "added")
    assert not trigger.matches("models-old/hero.fbx", "added"), "a prefix is not a parent"

    nested = PathTrigger(path="models", pattern="props/*.fbx")
    assert nested.matches("models/props/crate.fbx", "added")
    assert not nested.matches("models/hero.fbx", "added"), "a pattern with a slash is a path"

    everything = PathTrigger()
    assert everything.matches("anything/at/all.txt", "deleted")


def test_an_entry_that_does_not_validate_is_listed_and_survives_a_write(project: Path) -> None:
    """A file written by a newer Studio, or by hand: what cannot be read is named, and is
    still there after the page saves something else."""
    future = {"id": "from-the-future", "trigger": {"kind": "moon.phase"}, "action": {"kind": "notify"}}
    write_automations(project, NOTIFY_ON_REVIEW, future, {**NOTIFY_ON_REVIEW, "name": "again"})
    store = AutomationStore(ProjectPaths.for_project(project))

    loaded = store.load()
    assert [a.id for a in loaded.automations] == ["notify-review"]
    assert [(entry.index, entry.id) for entry in loaded.invalid] == [
        (1, "from-the-future"),
        (2, "notify-review"),
    ]
    assert "used twice" in loaded.invalid[1].error

    store.upsert(store.get("notify-review").model_copy(update={"enabled": False}))
    raw = yaml.safe_load(store.file.read_text(encoding="utf-8"))["automations"]
    assert raw[0]["enabled"] is False and raw[1] == future, "kept verbatim, in place"


def test_a_document_that_does_not_parse_is_never_written_over(client: TestClient, project: Path) -> None:
    file = ProjectPaths.for_project(project).automations_file
    file.write_text("automations: [unclosed", encoding="utf-8")

    overview = client.get("/api/automations").json()
    assert overview["error"] and overview["automations"] == []

    refused = client.post(
        "/api/automations",
        json={"trigger": {"kind": "task.created"}, "action": {"kind": "notify"}},
    )
    assert refused.status_code == 409
    assert file.read_text(encoding="utf-8") == "automations: [unclosed"


# ------------------------------------------------------------------ the API


def test_the_page_creates_edits_switches_and_deletes(client: TestClient, project: Path) -> None:
    created = client.post(
        "/api/automations",
        json={
            "name": "Bake on change",
            "trigger": {"kind": "path.changed", "path": "./models/", "pattern": "*.fbx"},
            "action": {"kind": "pipeline.run", "pipeline_id": "no-such-pipeline"},
        },
    )
    assert created.status_code == 201
    (entry,) = created.json()["automations"]
    assert entry["trigger"]["path"] == "models", "normalised on the way in"
    assert entry["problem"] == "No pipeline 'no-such-pipeline'", "said on the page, not refused"
    assert created.json()["path"] == ".voxarium_studio/automations.yaml"

    off = client.patch(f"/api/automations/{entry['id']}", json={"enabled": False}).json()
    assert off["automations"][0]["enabled"] is False
    assert off["automations"][0]["trigger"]["pattern"] == "*.fbx", "the switch touches nothing else"

    edited = client.put(
        f"/api/automations/{entry['id']}",
        json={"trigger": {"kind": "task.created"}, "action": {"kind": "notify"}},
    ).json()
    assert [a["id"] for a in edited["automations"]] == [entry["id"]]
    assert edited["automations"][0]["problem"] is None

    text = ProjectPaths.for_project(project).automations_file.read_text(encoding="utf-8")
    assert text.startswith("# Automations for this project") and "task.created" in text
    assert "''" not in text, "fields nobody filled in are not written: the file lives in a diff"

    assert client.put("/api/automations/ghost", json=edited["automations"][0]).status_code == 404
    assert client.delete(f"/api/automations/{entry['id']}").json()["automations"] == []
    assert client.delete(f"/api/automations/{entry['id']}").status_code == 404

    outside = client.post(
        "/api/automations",
        json={"trigger": {"kind": "path.changed", "path": "../elsewhere"}, "action": {"kind": "notify"}},
    )
    assert outside.status_code == 422


def test_run_now_works_on_a_disabled_automation_and_clearing_the_log_empties_it(
    client: TestClient, project: Path
) -> None:
    write_automations(project, {**NOTIFY_ON_REVIEW, "enabled": False})
    answer = client.post("/api/automations/notify-review/run").json()["run"]
    assert (answer["status"], answer["data"]["trigger"]) == ("ok", "manual")

    runs = client.get("/api/automations/runs").json()["runs"]
    assert [run["automation_name"] for run in runs] == ["Review ping"]
    assert client.delete("/api/automations/runs").json()["runs"] == []


# ------------------------------------------------------------------ tasks


async def test_a_status_change_fires_once_and_the_notification_names_the_task(
    ctx: AppContext, project: Path
) -> None:
    write_automations(
        project,
        NOTIFY_ON_REVIEW,
        {**NOTIFY_ON_REVIEW, "id": "disabled", "enabled": False},
        {"id": "on-done", "trigger": {"kind": "task.status_changed", "status": "done"}, "action": {"kind": "notify"}},
        {"id": "any-move", "trigger": {"kind": "task.status_changed"}, "action": {"kind": "notify"}},
    )
    task = await ctx.teams.create_task(team_id=TEAM, assignee_id=DIRECTOR, title="Dash", brief="b")
    await ctx.teams.update_task(task.id, {"status": TaskStatus.NEEDS_REVIEW})

    rows = await logged(ctx, 2)
    assert sorted(row.automation_id for row in rows) == ["any-move", "notify-review"]
    display_id = ctx.teams.display_id(task)
    assert {row.cause for row in rows} == {f"{display_id} → needs_review"}

    sent = [n for n in events(ctx, EventType.AUTOMATION_NOTIFICATION) if n["automation_id"] == "notify-review"]
    assert len(sent) == 1
    (notification,) = sent
    assert notification["title"] == f"{display_id} needs review"
    assert notification["message"].startswith("Dash — ")
    assert notification["route"] == f"/tasks?task={task.id}"
    assert notification["values"]["previous"] == "todo"

    # The status event is re-published when nothing moved — a summary landing does it — and
    # "changed to" must not hear that as a change.
    await ctx.bus.publish(
        EventType.TEAM_TASK_STATUS_CHANGED,
        {"task_id": task.id, "status": "needs_review", "previous": "needs_review"},
    )
    await ctx.bus.publish(
        EventType.TEAM_TASK_STATUS_CHANGED,
        {"task_id": "task_of_another_project", "status": "needs_review", "previous": "todo"},
    )
    await asyncio.sleep(0.2)
    assert len(await ctx.automations.recent()) == 2


async def test_a_new_task_fires_the_created_trigger(ctx: AppContext, project: Path) -> None:
    write_automations(
        project, {"id": "hello", "trigger": {"kind": "task.created"}, "action": {"kind": "notify"}}
    )
    task = await ctx.teams.create_task(team_id=TEAM, assignee_id=DIRECTOR, title="New", brief="b")
    (row,) = await logged(ctx, 1)
    assert row.cause == f"{ctx.teams.display_id(task)} created"
    (notification,) = events(ctx, EventType.AUTOMATION_NOTIFICATION)
    assert (notification["title"], notification["trigger"]) == ("", "task.created"), (
        "texts left empty stay empty: the window words the default, in its own language"
    )


# ------------------------------------------------------------------ folders and pipelines


def changed(*paths: str, change: str = "modified", root: Path | None = None) -> dict:
    payload: dict = {
        "changes": [{"change": change, "path": path, "is_dir": False} for path in paths],
        "truncated": False,
    }
    if root is not None:
        payload["root"] = str(root)
    return payload


async def test_a_burst_under_a_folder_is_one_firing_that_names_the_files(
    ctx: AppContext, project: Path, quick_settle: None
) -> None:
    write_automations(
        project,
        {
            "id": "models",
            "trigger": {"kind": "path.changed", "path": "./models/"},
            "action": {"kind": "notify", "message": "{count}: {paths}"},
        },
    )
    await ctx.bus.publish(EventType.EXPLORER_FILE_CHANGED, changed("models/a.fbx", "src/main.py"))
    await ctx.bus.publish(EventType.EXPLORER_FILE_CHANGED, changed("models/b.fbx", "models/a.fbx"))
    # A folder's own timestamp moving is the echo of the files written into it, not a third file.
    echo = {"change": "modified", "path": "models", "is_dir": True}
    await ctx.bus.publish(EventType.EXPLORER_FILE_CHANGED, {"changes": [echo], "truncated": False})
    # The project being closed during a switch shares the bus for a moment (§5.1).
    await ctx.bus.publish(
        EventType.EXPLORER_FILE_CHANGED, changed("models/c.fbx", root=project.parent / "other")
    )

    (row,) = await logged(ctx, 1)
    assert row.cause == "2 files under ./models/"
    (notification,) = events(ctx, EventType.AUTOMATION_NOTIFICATION)
    assert notification["message"] == "2: models/a.fbx\nmodels/b.fbx"
    await asyncio.sleep(0.2)
    assert len(await ctx.automations.recent()) == 1


async def test_a_folder_runs_a_pipeline_and_does_not_hear_its_own_run(
    ctx: AppContext, project: Path, quick_settle: None, wait_for
) -> None:
    """The loop this guards against: a pipeline set off by ./models/ that writes ./models/.
    And the firing lasts as long as the run — in progress while it runs, done when it is."""
    ctx.pipelines.save(pipeline(seconds=0.6))
    write_automations(
        project,
        {
            "id": "bake",
            "trigger": {"kind": "path.changed", "path": "models"},
            "action": {"kind": "pipeline.run", "pipeline_id": "bake"},
        },
    )
    await ctx.bus.publish(EventType.EXPLORER_FILE_CHANGED, changed("models/hero.fbx", root=project))
    (row,) = await logged(ctx, 1)
    row = await row_where(ctx, row.id, lambda r: "run_id" in r.data)
    assert (row.status, row.detail) == ("running", "Running Bake models")
    assert row.data["action"] == "pipeline.run"
    assert row.data["definition"]["trigger"]["path"] == "models", "readable after a delete"
    run_id = row.data["run_id"]

    # What the run "writes" while it is live, and just after it ends.
    await ctx.bus.publish(EventType.EXPLORER_FILE_CHANGED, changed("models/hero.baked.fbx"))
    await wait_for(run_id)
    await ctx.bus.publish(EventType.EXPLORER_FILE_CHANGED, changed("models/hero.baked.fbx"))
    finished = await settled_row(ctx, row.id)
    assert (finished.status, finished.detail) == ("ok", "Finished Bake models")
    assert finished.data["run_id"] == run_id, "the link to the run outlives the run"
    await asyncio.sleep(0.1)
    assert len(await ctx.automations.recent()) == 1, "its own output did not start it again"
    await eventually(lambda: events(ctx, EventType.AUTOMATION_RUN_UPDATED))
    written = [
        entry["status"]
        for kind in (EventType.AUTOMATION_FIRED, EventType.AUTOMATION_RUN_UPDATED)
        for entry in events(ctx, kind)
        if entry["id"] == row.id
    ]
    # One row: in progress from its first write — a report may update it while it runs — and
    # ok once, at the end.
    assert written[0] == "running" and written[-1] == "ok" and written.count("ok") == 1

    async with ctx.db.session() as session:
        from voxarium_studio.models.pipeline import Run

        run = await session.get(Run, run_id)
    assert (run.trigger, run.created_by) == ("automation", "automation:bake")

    await asyncio.sleep(0.3)  # past RUN_ECHO_SECONDS: a person's edit counts again
    await ctx.bus.publish(EventType.EXPLORER_FILE_CHANGED, changed("models/other.fbx"))
    assert len(await logged(ctx, 2)) == 2


async def test_a_missing_pipeline_is_a_failed_firing_not_a_crash(ctx: AppContext, project: Path) -> None:
    write_automations(
        project,
        {
            "id": "broken",
            "trigger": {"kind": "task.created"},
            "action": {"kind": "pipeline.run", "pipeline_id": "gone"},
        },
        {"id": "fine", "trigger": {"kind": "task.created"}, "action": {"kind": "notify"}},
    )
    await ctx.teams.create_task(team_id=TEAM, assignee_id=DIRECTOR, title="t", brief="b")
    rows = {row.automation_id: row for row in await logged(ctx, 2)}
    assert (rows["broken"].status, rows["broken"].detail) == ("error", "No pipeline 'gone'")
    assert rows["fine"].status == "ok"
    # The row is written before its event is published, so the event is waited for in turn.
    await eventually(lambda: len(events(ctx, EventType.AUTOMATION_FIRED)) == 2)
    fired = events(ctx, EventType.AUTOMATION_FIRED)
    assert {entry["automation_id"] for entry in fired} == {"broken", "fine"}


async def test_a_run_slow_to_start_still_gets_onto_its_row(
    ctx: AppContext, project: Path, quick_settle: None, monkeypatch: pytest.MonkeyPatch
) -> None:
    """The row goes into the log after a moment whatever the action has said by then; what it
    says afterwards — a pipeline's run, once the runner has it going — lands on that row."""
    ctx.pipelines.save(pipeline(seconds=0.3))
    write_automations(
        project,
        {"id": "bake", "trigger": {"kind": "task.created"}, "action": {"kind": "pipeline.run", "pipeline_id": "bake"}},
    )
    go = asyncio.Event()
    start = ctx.runner.start

    async def held_start(*args, **kwargs):
        await go.wait()
        return await start(*args, **kwargs)

    monkeypatch.setattr(ctx.runner, "start", held_start)
    await ctx.teams.create_task(team_id=TEAM, assignee_id=DIRECTOR, title="t", brief="b")
    (row,) = await logged(ctx, 1)
    assert (row.status, row.detail) == ("running", "") and "run_id" not in row.data

    go.set()
    row = await row_where(ctx, row.id, lambda r: "run_id" in r.data)
    assert (row.status, row.detail) == ("running", "Running Bake models")
    await eventually(lambda: events(ctx, EventType.AUTOMATION_RUN_UPDATED))
    assert events(ctx, EventType.AUTOMATION_RUN_UPDATED)[0]["detail"] == "Running Bake models"
    assert (await settled_row(ctx, row.id)).status == "ok"


async def test_a_pipeline_that_fails_or_is_cancelled_says_so(
    ctx: AppContext, project: Path, quick_settle: None
) -> None:
    ctx.pipelines.save(pipeline(seconds=5))
    ctx.pipelines.save(
        PipelineDefinition.model_validate(
            {
                "id": "broken",
                "name": "Broken",
                "nodes": [{"id": "read", "type": "core.read_text_file", "params": {"path": "missing.txt"}}],
                "edges": [],
            }
        )
    )
    write_automations(
        project,
        {"id": "bake", "trigger": {"kind": "task.created"}, "action": {"kind": "pipeline.run", "pipeline_id": "bake"}},
        {"id": "broken", "trigger": {"kind": "task.created"}, "action": {"kind": "pipeline.run", "pipeline_id": "broken"}},
    )
    await ctx.teams.create_task(team_id=TEAM, assignee_id=DIRECTOR, title="t", brief="b")
    rows = {row.automation_id: row for row in await logged(ctx, 2)}

    failed = await settled_row(ctx, rows["broken"].id)
    assert failed.status == "error" and failed.detail, "the run's own reason, not a bare 'failed'"

    assert rows["bake"].status == "running"
    bake = await row_where(ctx, rows["bake"].id, lambda r: "run_id" in r.data)
    await ctx.runner.cancel(bake.data["run_id"])
    cancelled = await settled_row(ctx, rows["bake"].id)
    assert (cancelled.status, cancelled.detail) == ("cancelled", "Bake models was cancelled")


async def test_a_chain_of_runs_ends(ctx: AppContext, project: Path, wait_for) -> None:
    """"When any run finishes, run the pipeline" restarts itself for ever without a ceiling."""
    ctx.pipelines.save(pipeline())
    write_automations(
        project,
        {
            "id": "again",
            "trigger": {"kind": "run.finished", "outcome": "success"},
            "action": {"kind": "pipeline.run", "pipeline_id": "bake"},
        },
    )
    await ctx.runner.start(pipeline())
    rows = await logged(ctx, automation_engine.MAX_CHAIN_DEPTH + 1)
    await asyncio.sleep(0.3)
    rows = await ctx.automations.recent()
    assert [row.status for row in reversed(rows)] == ["ok"] * automation_engine.MAX_CHAIN_DEPTH + ["skipped"]
    assert "chain" in rows[0].detail


async def test_a_flood_is_skipped_and_said_once(
    ctx: AppContext, project: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setattr(automation_engine, "MAX_FIRES_PER_WINDOW", 3)
    write_automations(
        project, {"id": "hello", "trigger": {"kind": "task.created"}, "action": {"kind": "notify"}}
    )
    for index in range(6):
        await ctx.teams.create_task(team_id=TEAM, assignee_id=DIRECTOR, title=f"t{index}", brief="b")
    await logged(ctx, 4)
    await asyncio.sleep(0.2)
    rows = await ctx.automations.recent()
    assert sorted(row.status for row in rows) == ["ok", "ok", "ok", "skipped"]
    assert len(events(ctx, EventType.AUTOMATION_NOTIFICATION)) == 3


# ------------------------------------------------------------------ plugins

OFFERING = dedent(
    '''
    import json

    from voxarium_studio.plugin_api import AutomationCancelled, PluginRegistry


    async def refresh(host, trigger):
        (host.data_dir / "refreshed.json").write_text(json.dumps(trigger), encoding="utf-8")
        return {"queued": True}


    async def explode(host, trigger):
        raise RuntimeError("no index here")


    async def stopped(host, trigger):
        raise AutomationCancelled("stopped by hand")


    async def slow(host, trigger):
        await host.state["gate"].wait()
        return "slow done"


    async def settings_only(host, payload):
        return "never offered"


    def register(registry: PluginRegistry) -> None:
        registry.add_action("settings_only", settings_only)
        registry.add_automation_action("refresh", refresh, label="Refresh index", description="d")
        registry.add_automation_action("explode", explode, label="Explode")
        registry.add_automation_action("stopped", stopped, label="Stopped")
        registry.add_automation_action("slow", slow, label="Slow")
    '''
)


@pytest.fixture
def offering(project: Path) -> Path:
    root = ProjectPaths.for_project(project).plugins / "offering"
    root.mkdir(parents=True)
    manifest = {"id": "offering", "name": "Offering", "backend": {"entry": "plugin.py:register"}}
    (root / "plugin.yaml").write_text(yaml.safe_dump(manifest, sort_keys=False), encoding="utf-8")
    (root / "plugin.py").write_text(OFFERING, encoding="utf-8")
    return project


async def test_a_plugin_offers_actions_one_by_one_and_is_told_what_happened(offering: Path) -> None:
    write_automations(
        offering,
        {
            "id": "refresh-on-done",
            "name": "Refresh",
            "trigger": {"kind": "task.status_changed", "status": "done"},
            "action": {"kind": "plugin.action", "plugin_id": "offering", "action_id": "refresh"},
        },
        {
            "id": "explodes",
            "trigger": {"kind": "task.status_changed", "status": "done"},
            "action": {"kind": "plugin.action", "plugin_id": "offering", "action_id": "explode"},
        },
        {
            "id": "not-offered",
            "trigger": {"kind": "task.status_changed", "status": "done"},
            "action": {"kind": "plugin.action", "plugin_id": "offering", "action_id": "settings_only"},
        },
        {
            "id": "stops",
            "trigger": {"kind": "task.status_changed", "status": "done"},
            "action": {"kind": "plugin.action", "plugin_id": "offering", "action_id": "stopped"},
        },
    )
    ctx = AppContext.create(str(offering), AuthSettings())
    await ctx.startup()
    try:
        plugin = ctx.registry.plugin("offering")
        assert plugin.status == "loaded", plugin.error
        assert plugin.to_json()["automation_actions"] == ["refresh", "explode", "stopped", "slow"]
        assert [a.label for a in ctx.registry.automation_actions() if a.plugin_id == "offering"] == [
            "Explode",
            "Refresh index",
            "Slow",
            "Stopped",
        ]

        task = await ctx.teams.create_task(team_id=TEAM, assignee_id=DIRECTOR, title="Dash", brief="b")
        await ctx.teams.update_task(task.id, {"status": TaskStatus.DONE})
        rows = {row.automation_id: row for row in await logged(ctx, 4)}

        assert (rows["refresh-on-done"].status, rows["refresh-on-done"].detail) == ("ok", '{"queued": true}')
        told = yaml.safe_load((ctx.paths.data / "refreshed.json").read_text(encoding="utf-8"))
        assert told["automation"] == {"id": "refresh-on-done", "name": "Refresh"}
        assert (told["trigger"], told["values"]["status"]) == ("task.status_changed", "done")

        assert (rows["explodes"].status, rows["explodes"].detail) == ("error", "no index here")
        assert rows["not-offered"].status == "error", "a settings page's endpoint is not an action"
        assert "offers no action 'settings_only'" in rows["not-offered"].detail
        assert (rows["stops"].status, rows["stops"].detail) == ("cancelled", "stopped by hand")

        ctx.registry.remove_plugin("offering")
        assert ctx.registry.automation_actions() == [] or all(
            a.plugin_id != "offering" for a in ctx.registry.automation_actions()
        )
    finally:
        await ctx.shutdown()


SLOW = {
    "id": "slow",
    "trigger": {"kind": "task.created"},
    "action": {"kind": "plugin.action", "plugin_id": "offering", "action_id": "slow"},
}


async def test_a_long_plugin_action_is_in_progress_until_it_returns(offering: Path, quick_settle: None) -> None:
    write_automations(offering, SLOW)
    async with opened(offering) as ctx:
        gate = ctx.plugin_host("offering").state["gate"] = asyncio.Event()
        await ctx.teams.create_task(team_id=TEAM, assignee_id=DIRECTOR, title="t", brief="b")
        (row,) = await logged(ctx, 1)
        assert (row.status, row.detail) == ("running", "")
        await eventually(lambda: events(ctx, EventType.AUTOMATION_FIRED))
        assert [entry["status"] for entry in events(ctx, EventType.AUTOMATION_FIRED)] == ["running"]

        gate.set()
        finished = await settled_row(ctx, row.id)
        assert (finished.status, finished.detail) == ("ok", "slow done")
        await eventually(lambda: events(ctx, EventType.AUTOMATION_RUN_UPDATED))
        (updated,) = events(ctx, EventType.AUTOMATION_RUN_UPDATED)
        assert (updated["id"], updated["status"], updated["detail"]) == (row.id, "ok", "slow done")


async def test_run_now_answers_before_a_long_action_ends(offering: Path, quick_settle: None) -> None:
    """A request must not be held open for the length of the work it started."""
    write_automations(offering, {**SLOW, "enabled": False})
    async with opened(offering) as ctx:
        gate = ctx.plugin_host("offering").state["gate"] = asyncio.Event()
        row = await asyncio.wait_for(ctx.automations.run_now(ctx.automation_store.get("slow")), timeout=2)
        assert (row.status, row.data["trigger"]) == ("running", "manual")
        gate.set()
        assert (await settled_row(ctx, row.id)).status == "ok"


async def test_clearing_the_log_keeps_what_is_still_running(offering: Path, quick_settle: None) -> None:
    write_automations(
        offering,
        SLOW,
        {"id": "hello", "trigger": {"kind": "task.created"}, "action": {"kind": "notify"}},
    )
    async with opened(offering) as ctx:
        gate = ctx.plugin_host("offering").state["gate"] = asyncio.Event()
        await ctx.teams.create_task(team_id=TEAM, assignee_id=DIRECTOR, title="t", brief="b")
        await logged(ctx, 2)
        await ctx.automations.clear_log()
        (kept,) = await ctx.automations.recent()
        assert (kept.automation_id, kept.status) == ("slow", "running")
        gate.set()
        assert (await settled_row(ctx, kept.id)).status == "ok"


async def test_work_the_studio_stops_under_is_not_left_in_progress(offering: Path, quick_settle: None) -> None:
    write_automations(offering, SLOW)
    async with opened(offering) as ctx:
        ctx.plugin_host("offering").state["gate"] = asyncio.Event()  # never set
        await ctx.teams.create_task(team_id=TEAM, assignee_id=DIRECTOR, title="t", brief="b")
        (row,) = await logged(ctx, 1)
        assert row.status == "running"

    # The firing closed its own row on its way out, and said so to whoever was watching —
    # without waiting for the next start to find it…
    (closed,) = events(ctx, EventType.AUTOMATION_RUN_UPDATED)
    assert (closed["id"], closed["status"], closed["data"]["interrupted"]) == (row.id, "cancelled", True)
    async with opened(offering) as ctx:
        (row,) = await ctx.automations.recent()
        assert (row.status, row.detail) == ("cancelled", automation_engine.INTERRUPTED)
        assert row.data["interrupted"] is True
        # …and a row a Studio that died outright left behind is closed on the next start.
        async with ctx.db.session() as session:
            session.add(AutomationRun(automation_id="slow", status="running", cause="left behind"))

    async with opened(offering) as ctx:
        left = next(row for row in await ctx.automations.recent() if row.cause == "left behind")
        assert (left.status, left.detail, left.data["interrupted"]) == (
            "cancelled",
            automation_engine.INTERRUPTED,
            True,
        )


def test_graphify_offers_its_update(client: TestClient) -> None:
    offered = client.get("/api/automations").json()["plugin_actions"]
    assert {"plugin_id": "graphify", "action_id": "update", "label": "Graphify Update"}.items() <= next(
        entry for entry in offered if entry["plugin_id"] == "graphify"
    ).items()


def test_validation_of_a_whole_automation() -> None:
    with pytest.raises(ValueError):
        Automation.model_validate({**NOTIFY_ON_REVIEW, "id": "has spaces"})
    with pytest.raises(ValueError):
        Automation.model_validate({**NOTIFY_ON_REVIEW, "trigger": {"kind": "task.status_changed", "status": "nope"}})
    with pytest.raises(ValueError):
        Automation.model_validate({**NOTIFY_ON_REVIEW, "trigger": {"kind": "path.changed", "changes": []}})
    assert Automation.model_validate({k: v for k, v in NOTIFY_ON_REVIEW.items() if k != "id"}).id.startswith("auto_")
