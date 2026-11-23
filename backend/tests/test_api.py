"""The HTTP surface the SPA talks to (§11.1), including the codes the UI branches on."""

from __future__ import annotations

from pathlib import Path

from fastapi.testclient import TestClient


# ------------------------------------------------------------------ project & status


def test_project_describes_the_managed_project(client: TestClient) -> None:
    body = client.get("/api/project").json()
    assert body["data_dir"].endswith(".voxarium_studio")
    assert body["counts"]["plugins"] >= 4
    assert body["auth_required"] is False


def test_status_reports_the_healthcheck_as_a_boolean(client: TestClient) -> None:
    body = client.get("/api/status").json()
    assert body["database"] is True, "a boolean; src/api/types.ts mirrors this"
    assert "claude_code" in body["agent_backends"]


def test_healthz_needs_no_auth_even_in_token_mode(token_client: TestClient) -> None:
    assert token_client.get("/healthz").status_code == 200


def test_healthz_identifies_the_desktop_owner(client: TestClient, monkeypatch) -> None:
    monkeypatch.delenv("VOXARIUM_STUDIO_DESKTOP_INSTANCE", raising=False)
    assert client.get("/healthz").json()["service"] == "voxarium_studio"
    assert client.get("/healthz").json()["desktop_instance"] is None
    monkeypatch.setenv("VOXARIUM_STUDIO_DESKTOP_INSTANCE", "test-owner")
    assert client.get("/healthz").json()["desktop_instance"] == "test-owner"


def test_healthz_identifies_the_backend_launch(client: TestClient, monkeypatch) -> None:
    monkeypatch.delenv("VOXARIUM_STUDIO_LAUNCH_ID", raising=False)
    assert client.get("/healthz").json()["launch_id"] is None
    monkeypatch.setenv("VOXARIUM_STUDIO_LAUNCH_ID", "test-launch")
    assert client.get("/healthz").json()["launch_id"] == "test-launch"


# ------------------------------------------------------------------ auth


def test_the_api_is_closed_without_a_token(token_client: TestClient) -> None:
    assert token_client.get("/api/project").status_code == 401
    assert token_client.get("/api/project", headers={"Authorization": "Bearer wrong"}).status_code == 401


def test_every_accepted_token_form_works(token_client: TestClient) -> None:
    for kwargs in (
        {"headers": {"Authorization": "Bearer t0ken"}},
        {"headers": {"x-voxarium_studio-token": "t0ken"}},
        {"params": {"token": "t0ken"}},
    ):
        assert token_client.get("/api/project", **kwargs).status_code == 200


def test_plugin_assets_are_behind_auth_but_reachable_by_query(token_client: TestClient) -> None:
    """A dynamic `import()` cannot set a header, which is why `?token=` is accepted."""
    url = "/api/plugins/vox_preview/assets/frontend/VoxPreview.js"
    assert token_client.get(url).status_code == 401
    assert token_client.get(url, params={"token": "t0ken"}).status_code == 200


# ------------------------------------------------------------------ explorer


def test_explorer_tree_and_preview(client: TestClient) -> None:
    tree = client.get("/api/explorer/tree", params={"path": "", "depth": 1}).json()
    assert ".voxarium_studio" not in {entry["name"] for entry in tree["entries"]}

    client.put("/api/docs/note.md", json={"content": "# Note\n"})
    assert client.get("/api/explorer/file", params={"path": "nope.txt"}).status_code == 404


def test_explorer_refuses_paths_outside_the_project(client: TestClient) -> None:
    for escape in ("../../etc/hosts", "C:/Windows/win.ini"):
        assert client.get("/api/explorer/file", params={"path": escape}).status_code == 403
        assert client.get("/api/explorer/raw", params={"path": escape}).status_code == 403


def test_tree_depth_is_capped(client: TestClient) -> None:
    assert client.get("/api/explorer/tree", params={"depth": 9}).status_code == 422


def test_raw_files_are_never_cached(client: TestClient) -> None:
    """Project files change under the client — that is what the watcher is for."""
    (client.app.state.ctx.paths.root / "asset.txt").write_text("v1", encoding="utf-8")
    response = client.get("/api/explorer/raw", params={"path": "asset.txt"})
    assert response.status_code == 200
    assert response.headers["cache-control"] == "no-cache"


def test_a_claimed_file_type_carries_its_previewer(client: TestClient) -> None:
    root = client.app.state.ctx.paths.root
    (root / "model.vox").write_bytes(b"VOX ")
    # `.ply` is a 3D suffix the Explorer classifies and no bundled previewer claims.
    (root / "cloud.ply").write_bytes(b"ply")

    claimed = client.get("/api/explorer/file", params={"path": "model.vox"}).json()
    assert claimed["previewer"]["plugin_id"] == "vox_preview"
    assert claimed["previewer"]["component_url"].startswith("/api/plugins/vox_preview/assets/")
    assert claimed["message"] is None, "the built-in 'no viewer' note would contradict it"

    unclaimed = client.get("/api/explorer/file", params={"path": "cloud.ply"}).json()
    assert unclaimed["previewer"] is None
    assert unclaimed["message"]


# ------------------------------------------------------------------ node types & plugins


def test_node_types_expose_categories_and_resolved_component_urls(client: TestClient) -> None:
    assert client.post("/api/plugins/hunyuan3d/enable").status_code == 200
    body = client.get("/api/node-types").json()
    by_id = {schema["id"]: schema for schema in body["node_types"]}

    assert "3D Generation" in body["categories"]
    assert by_id["core.echo"]["ui_component_url"] is None
    # The `?v=` stamp is deliberate — it is what stops a browser importing a stale module.
    stamped = "/api/plugins/hunyuan3d/assets/frontend/GenerateMeshNode.js?v="
    assert by_id["hunyuan3d.image_to_mesh"]["ui_component_url"].startswith(stamped)
    # Two node types, one component file: it draws whichever image ports its schema has.
    assert by_id["hunyuan3d.multiview_to_mesh"]["ui_component_url"].startswith(stamped)


def test_plugin_listing_reports_what_each_one_contributes(client: TestClient) -> None:
    plugins = {plugin["id"]: plugin for plugin in client.get("/api/plugins").json()}
    assert plugins["vox_preview"]["previewers"] == ["vox-model"]
    assert plugins["hunyuan3d"]["enabled"] is False
    assert plugins["hunyuan3d"]["pages"] == []
    assert client.post("/api/plugins/hunyuan3d/enable").status_code == 200
    plugins = {plugin["id"]: plugin for plugin in client.get("/api/plugins").json()}
    assert plugins["hunyuan3d"]["enabled"] is True
    assert plugins["hunyuan3d"]["pages"] == ["hunyuan3d-settings"]
    assert all(plugin["status"] != "error" for plugin in plugins.values())


def test_disabling_a_plugin_withdraws_its_previewer(client: TestClient) -> None:
    (client.app.state.ctx.paths.root / "model.vox").write_bytes(b"VOX ")

    assert client.post("/api/plugins/vox_preview/disable").status_code == 200
    assert client.get("/api/explorer/file", params={"path": "model.vox"}).json()["previewer"] is None

    assert client.post("/api/plugins/vox_preview/enable").status_code == 200
    assert client.get("/api/explorer/file", params={"path": "model.vox"}).json()["previewer"]


def test_saving_plugin_settings_re_registers_the_plugin(client: TestClient) -> None:
    """A plugin shapes what it contributes from its settings, so saving must re-run it."""
    client.put("/api/plugins/hunyuan3d/settings", json={"settings": {"default_resolution": 128}})

    schema = next(
        s for s in client.get("/api/node-types").json()["node_types"] if s["id"] == "hunyuan3d.voxelize"
    )
    resolution = next(port for port in schema["inputs"] if port["id"] == "resolution")
    assert resolution["default"] == 128


def test_unknown_plugins_and_assets_are_404(client: TestClient) -> None:
    assert client.post("/api/plugins/nope/enable").status_code == 404
    assert client.get("/api/plugins/nope/assets/x.js").status_code == 404
    assert client.get("/api/plugins/hunyuan3d/assets/plugin.yaml").status_code == 403, "not servable"
    # httpx normalises `..` out of a URL, so the escape has to be percent-encoded to
    # reach the server at all.
    assert client.get("/api/plugins/hunyuan3d/assets/%2e%2e%2f%2e%2e%2fpyproject.toml").status_code == 403


def test_plugin_assets_are_revalidated_not_cached_blind(client: TestClient) -> None:
    """Editing a plugin's frontend and reloading the browser is the documented way to see
    the change, so the response may not be served from cache without asking first."""
    response = client.get("/api/plugins/vox_preview/assets/frontend/VoxPreview.js")
    assert response.status_code == 200
    assert "no-cache" in response.headers["cache-control"]
    # The ETag is what makes revalidation cheap; without it every reload refetches in full.
    assert response.headers["etag"]


# ------------------------------------------------------------------ pipelines & runs


def test_pipeline_crud(client: TestClient) -> None:
    assert len(client.get("/api/pipelines").json()) == 1

    created = client.post(
        "/api/pipelines",
        json={"id": "new", "name": "New", "nodes": [{"id": "a", "type": "core.echo"}], "edges": []},
    )
    assert created.status_code == 201
    assert client.post("/api/pipelines", json={"id": "new", "name": "Dup"}).status_code == 409
    assert client.get("/api/pipelines/new").status_code == 200
    assert client.get("/api/pipelines/ghost").status_code == 404
    assert client.put("/api/pipelines/new", json={"id": "other", "name": "x"}).status_code == 400
    assert client.delete("/api/pipelines/new").status_code == 204
    assert client.delete("/api/pipelines/new").status_code == 404


def test_edges_serialise_with_from_and_to_on_the_wire(client: TestClient) -> None:
    assert client.post("/api/plugins/hunyuan3d/enable").status_code == 200
    body = client.get("/api/pipelines/prompt-to-voxel-asset").json()
    assert set(body["pipeline"]["edges"][0]) == {"id", "from", "to"}
    assert [issue for issue in body["issues"] if issue["level"] == "error"] == []


def test_a_dangling_edge_is_rejected_by_the_schema(client: TestClient) -> None:
    response = client.post(
        "/api/pipelines",
        json={
            "id": "dangling",
            "name": "d",
            "nodes": [{"id": "a", "type": "core.echo"}],
            "edges": [{"from": {"node": "ghost", "port": "value"}, "to": {"node": "a", "port": "value"}}],
        },
    )
    assert response.status_code == 422


def test_running_an_unrunnable_graph_is_a_400_with_a_reason(client: TestClient) -> None:
    client.post(
        "/api/pipelines",
        json={"id": "bad", "name": "b", "nodes": [{"id": "a", "type": "nope.missing"}], "edges": []},
    )
    response = client.post("/api/pipelines/bad/run", json={})
    assert response.status_code == 400
    assert "Unknown node type" in response.json()["detail"]


def test_run_endpoints(client: TestClient) -> None:
    assert client.get("/api/runs", params={"limit": 9999}).status_code == 422
    assert client.get("/api/runs/ghost").status_code == 404
    assert client.post("/api/runs/ghost/cancel").status_code == 409
    assert client.post("/api/pipelines/ghost/run", json={}).status_code == 404


def test_node_log_paths_are_bounded(client: TestClient) -> None:
    """Both ids come off the URL, so the composed path is checked like any other."""
    assert client.get("/api/runs/nope/nodes/n1/log").json()["exists"] is False
    assert client.get("/api/runs/%2e%2e/nodes/n1/log").status_code == 400


# ------------------------------------------------------------------ teams


def test_team_listing_omits_tasks_but_the_detail_carries_them(client: TestClient) -> None:
    teams = client.get("/api/teams").json()
    assert "tasks" not in teams[0]

    detail = client.get(f"/api/teams/{teams[0]['id']}").json()
    assert isinstance(detail["tasks"], list)
    assert len(detail["members"]) == 6
    assert sum(m.get("builtin") == "producer" for m in detail["members"]) == 1
    assert sum(m.get("builtin") == "effort_solver" for m in detail["members"]) == 1
    assert client.get("/api/teams/ghost").status_code == 404


def test_task_creation_maps_failures_to_the_codes_the_ui_expects(client: TestClient) -> None:
    team = "team_default"
    assert client.post(
        f"/api/teams/{team}/tasks",
        json={"assignee_id": "ghost", "title": "t", "brief": "b"},
    ).status_code == 404, "an unknown member is a lookup failure, not a database error"

    assert client.post(
        f"/api/teams/{team}/tasks",
        json={"assignee_id": f"{team}__qa", "title": "t", "brief": "b",
              "created_by": f"{team}__dev"},
    ).status_code == 403, "dev has no route to qa"

    created = client.post(
        f"/api/teams/{team}/tasks", json={"assignee_id": f"{team}__qa", "title": "t", "brief": "b"}
    )
    assert created.status_code == 201
    assert created.json()["delegation_depth"] == 0
    assert created.json()["status"] == "todo", "created work runs nothing until released"

    task_id = created.json()["id"]
    assert client.post(f"/api/teams/{team}/tasks/{task_id}/start").status_code == 409, "a person"
    assert client.post(f"/api/teams/{team}/tasks/ghost/start").status_code == 404

    # An agent's task, which could otherwise run: refused while it is still in Todo (§10.4).
    parked = client.post(
        f"/api/teams/{team}/tasks", json={"assignee_id": f"{team}__dev", "title": "t", "brief": "b"}
    ).json()["id"]
    refused = client.post(f"/api/teams/{team}/tasks/{parked}/start")
    assert refused.status_code == 409 and "Pending" in refused.json()["detail"]


def test_a_task_can_be_edited_moved_and_deleted_over_http(client: TestClient) -> None:
    team = "team_default"
    created = client.post(
        f"/api/teams/{team}/tasks", json={"assignee_id": f"{team}__qa", "title": "t", "brief": "b"}
    ).json()
    task = f"/api/teams/{team}/tasks/{created['id']}"

    edited = client.patch(task, json={"title": "sharper", "status": "in_progress"})
    assert edited.status_code == 200
    assert edited.json()["title"] == "sharper" and edited.json()["status"] == "in_progress"
    assert edited.json()["brief"] == "b", "a field left out of the patch is untouched"

    assert client.patch(task, json={"title": "  "}).status_code == 400
    assert client.patch(task, json={"assignee_id": "ghost"}).status_code == 404
    assert client.patch(
        task, json={"assignee_id": f"{team}__qa", "actor_id": f"{team}__dev"}
    ).status_code == 403, "dev is neither the assignee nor their manager"
    assert client.patch(f"/api/teams/{team}/tasks/ghost", json={"title": "x"}).status_code == 404

    history = [
        message
        for message in client.get(task).json()["messages"]
        if message["kind"] == "status"
    ]
    assert [entry["data"]["to"] for entry in history] == ["todo", "in_progress"]

    assert client.delete(task).status_code == 200
    assert client.get(task).status_code == 404


def test_the_org_chart_layout_round_trips_through_the_team_file(
    client: TestClient, project: Path
) -> None:
    """A dragged node is a saved position; tidying up takes it back out of the YAML."""
    import yaml as pyyaml

    from voxarium_studio.main import create_app

    team = "team_default"
    dev, qa = f"{team}__dev", f"{team}__qa"
    layout = f"/api/teams/{team}/layout"
    roster = project / ".voxarium_studio" / "teams" / "default-team.yaml"

    def members(http: TestClient) -> dict[str, dict]:
        return {m["id"]: m for m in http.get(f"/api/teams/{team}").json()["members"]}

    def written() -> dict[str, dict]:
        raw = pyyaml.safe_load(roster.read_text(encoding="utf-8"))
        return {member["id"]: member for member in raw["members"]}

    assert all(member["pos_x"] is None for member in members(client).values())

    # Two in one request: a drag can carry a selection, and doing the whole move in one
    # write is the endpoint's reason to exist.
    moved = client.patch(
        layout, json={"positions": {dev: {"x": 120.5, "y": -40}, qa: {"x": 0, "y": 60}}}
    )
    assert moved.status_code == 200 and moved.json()["updated"] == 2

    placed = members(client)
    assert (placed[dev]["pos_x"], placed[dev]["pos_y"]) == (120.5, -40.0)
    assert (placed[qa]["pos_x"], placed[qa]["pos_y"]) == (0.0, 60.0)

    on_disk = written()
    assert on_disk["dev"]["pos_x"] == 120.5
    assert "pos_x" not in on_disk["designer"], "an untouched member carries no layout at all"

    # Reopening the project is the real test of "saved": the roster is re-read from YAML on
    # startup, so a position the sync does not carry back would be silently wiped here.
    with TestClient(create_app(str(project)), base_url="http://127.0.0.1") as reopened:
        assert members(reopened)[dev]["pos_x"] == 120.5

    # An unknown member in the batch takes the whole request down — the session rolls back,
    # so a partly-applied move never reaches the file either.
    refused = client.patch(
        layout, json={"positions": {dev: {"x": 999, "y": 999}, "ghost": {"x": 0, "y": 0}}}
    )
    assert refused.status_code == 404
    assert members(client)[dev]["pos_x"] == 120.5 and written()["dev"]["pos_x"] == 120.5

    # Tidying up: nulls clear the placement, and the keys leave the file rather than
    # standing in it as `pos_x: null`.
    assert client.patch(layout, json={"positions": {dev: None, qa: None}}).status_code == 200
    assert members(client)[dev]["pos_x"] is None and members(client)[dev]["pos_y"] is None
    assert "pos_x" not in written()["dev"] and "pos_y" not in written()["dev"]

    assert client.patch("/api/teams/ghost/layout", json={"positions": {}}).status_code == 404


def test_transcript_authorship_is_enforced_over_http(client: TestClient) -> None:
    team = "team_default"
    task_id = client.post(
        f"/api/teams/{team}/tasks", json={"assignee_id": f"{team}__dev", "title": "t", "brief": "b"}
    ).json()["id"]
    notes = f"/api/teams/{team}/tasks/{task_id}/notes"

    assert client.post(notes, json={"text": "hi"}).status_code == 201
    assert client.post(notes, json={"text": "hi", "actor_id": f"{team}__qa"}).status_code == 403
    assert client.post(notes, json={"text": "hi", "actor_id": "ghost"}).status_code == 404
    assert client.post(notes, json={"text": "  "}).status_code == 400


# ------------------------------------------------------------------ docs & settings


def test_documents_round_trip_and_stay_inside_their_directory(client: TestClient) -> None:
    written = client.put("/api/docs/features/dash.md", json={"content": "# Dash ability\n"})
    assert written.status_code == 200
    assert written.json()["title"] == "Dash ability"

    assert client.get("/api/docs/features/dash.md").json()["content"] == "# Dash ability\n"
    assert client.get("/api/docs/features/ghost.md").status_code == 404
    assert client.put("/api/docs/%2e%2e%2fescape.md", json={"content": "x"}).status_code == 403
    assert client.put("/api/docs/thing.exe", json={"content": "x"}).status_code == 400


def test_settings_round_trip(client: TestClient) -> None:
    before = client.get("/api/settings").json()
    updated = client.put("/api/settings", json={"theme": {"accent": "#00ff88"}})
    assert updated.json()["theme"]["accent"] == "#00ff88"

    client.put("/api/settings", json={"theme": {"accent": before["theme"]["accent"]}})
    assert client.get("/api/settings").json()["theme"]["accent"] == before["theme"]["accent"]


def test_a_partial_theme_patch_leaves_the_other_fields_alone(client: TestClient) -> None:
    """The UI sends one field at a time. ThemeConfig has a default for every field, so
    replacing it wholesale made "set the look" also reset the mode back to dark."""
    client.put("/api/settings", json={"theme": {"mode": "light", "accent": "#00ff88"}})

    after = client.put("/api/settings", json={"theme": {"sidebar": "icons"}}).json()["theme"]

    assert after["sidebar"] == "icons"
    assert after["mode"] == "light", "changing the sidebar must not put dark mode back"
    assert after["accent"] == "#00ff88"
    # And it is the stored config that changed, not just the response.
    assert client.get("/api/settings").json()["theme"]["mode"] == "light"


def test_the_theme_defaults_are_the_graphite_design(client: TestClient) -> None:
    """The Graphite design's violet and JetBrains Mono are what a project gets before it
    chooses anything (§7, 2026-09-16), and every knob the looks replaced — the look itself
    included, gone with every look but Graphite — is gone from the payload."""
    theme = client.get("/api/settings").json()["theme"]

    assert (theme["accent"], theme["mono_font"]) == ("#a394e8", "jetbrains")
    assert theme["text_size"] is None, "unset takes the app's own size"
    assert theme["sidebar"] == "icons"
    for gone in ("look", "palette", "surfaces", "radius", "density", "reduce_motion", "font"):
        assert gone not in theme, gone


def test_the_new_theme_knobs_round_trip_and_are_bounded(client: TestClient) -> None:
    after = client.put(
        "/api/settings",
        json={
            "theme": {
                "text_size": 15,
                "sidebar": "icons",
                "node_tint": 30,
                "mono_font": "jetbrains",
                "status_palette": "accessible",
                "canvas_grid": "lines",
            }
        },
    ).json()["theme"]
    assert after["text_size"] == 15
    stored = client.get("/api/settings").json()["theme"]
    assert (stored["sidebar"], stored["node_tint"], stored["mono_font"]) == ("icons", 30, "jetbrains")

    for bad in ({"text_size": 40}, {"node_tint": 200}, {"sidebar": "hidden"}):
        assert client.put("/api/settings", json={"theme": bad}).status_code == 422, bad
    assert client.get("/api/settings").json()["theme"]["text_size"] == 15

    # Retired fields remain readable from historical disk config, not writable
    # through the strict API boundary (§5.2.1). Reject the whole patch.
    before = client.get("/api/settings").json()
    response = client.put("/api/settings", json={"theme": {"look": "material", "font": "roboto"}})
    assert response.status_code == 422
    assert response.json() == {"detail": [{
        "type": "value_error", "loc": ["body"], "msg": "Invalid settings request.",
    }]}
    assert client.get("/api/settings").json() == before

    # Null is applied, not read as omitted — it is the slider's "follow density again".
    after = client.put("/api/settings", json={"theme": {"text_size": None}}).json()["theme"]
    assert after["text_size"] is None
    assert client.get("/api/settings").json()["theme"]["text_size"] is None


def test_a_partial_concurrency_patch_keeps_the_other_limits(client: TestClient) -> None:
    before = client.get("/api/settings").json()["concurrency"]

    after = client.put("/api/settings", json={"concurrency": {"cpu": 7}}).json()["concurrency"]

    assert after["cpu"] == 7
    assert after["gpu"] == before["gpu"], "gpu: 1 is deliberate — do not reset it (§8.2)"
    assert after["io"] == before["io"]
    assert after["agents"] == before["agents"]


def test_the_agent_limit_is_saved_and_applied_without_a_restart(
    client: TestClient, project: Path
) -> None:
    """Settings → AI agents → Parallel sessions: the file, and the engine's live limit."""
    saved = client.put("/api/settings", json={"concurrency": {"agents": 4}})
    assert saved.status_code == 200 and saved.json()["concurrency"]["agents"] == 4
    assert client.get("/api/project").json()["concurrency_limits"]["agent"] == 4
    assert "agents: 4" in (project / ".voxarium_studio" / "config.yaml").read_text(encoding="utf-8")
    assert client.put("/api/settings", json={"concurrency": {"agents": 0}}).status_code == 422


def test_the_agent_launch_root_accepts_a_path_above_the_project(client: TestClient) -> None:
    """The case the setting exists for: a project that is one folder of a larger checkout."""
    after = client.put("/api/settings", json={"teams": {"launch_root": "../"}})

    assert after.status_code == 200
    assert after.json()["teams"]["launch_root"] == "../"
    assert client.get("/api/settings").json()["teams"]["launch_root"] == "../"


def test_the_agent_launch_root_must_name_a_directory(client: TestClient) -> None:
    refused = client.put("/api/settings", json={"teams": {"launch_root": "no/such/place"}})

    assert refused.status_code == 400
    assert "not a directory" in refused.json()["detail"]
    assert client.get("/api/settings").json()["teams"]["launch_root"] == "."


def test_a_partial_teams_patch_keeps_the_delegation_ceilings(client: TestClient) -> None:
    """`TeamsConfig` has a default for every field, so a wholesale assignment would reset
    the §10.4 bounds every time someone edited the launch root."""
    client.put("/api/settings", json={"teams": {"max_delegation_depth": 1}})

    after = client.put("/api/settings", json={"teams": {"launch_root": "."}}).json()["teams"]

    assert after["launch_root"] == "."
    assert after["max_delegation_depth"] == 1, "editing the launch root must not lift the cap"


def test_nobody_has_chosen_a_documents_reading_size_by_default(client: TestClient) -> None:
    """Unset is a value, not a gap: the editor renders a point above `--vxs-text-size`, so a
    hard number here would override the §7 density knob for every project that never asked."""
    assert client.get("/api/settings").json()["docs"]["font_size"] is None


def test_the_documents_reading_size_round_trips_and_is_bounded(client: TestClient) -> None:
    after = client.put("/api/settings", json={"docs": {"font_size": 18}})

    assert after.status_code == 200
    assert after.json()["docs"]["font_size"] == 18
    assert client.get("/api/settings").json()["docs"]["font_size"] == 18

    refused = client.put("/api/settings", json={"docs": {"font_size": 200}})
    assert refused.status_code == 422
    assert client.get("/api/settings").json()["docs"]["font_size"] == 18


def test_the_documents_reading_size_can_be_given_back_to_the_density_knob(
    client: TestClient,
) -> None:
    """The stepper's middle button. `null` has to be applied rather than read as "omitted",
    or a size once picked could never be unpicked."""
    client.put("/api/settings", json={"docs": {"font_size": 18}})

    after = client.put("/api/settings", json={"docs": {"font_size": None}})

    assert after.json()["docs"]["font_size"] is None
    assert client.get("/api/settings").json()["docs"]["font_size"] is None


def test_the_documents_leading_is_unset_bounded_and_unpickable(client: TestClient) -> None:
    """`docs.line_height` follows `font_size`'s arrangement exactly: unset by default so the
    stylesheet's own leading applies, bounded so the stepper cannot offer a rejected value,
    and `null` applied rather than read as omitted."""
    assert client.get("/api/settings").json()["docs"]["line_height"] is None

    after = client.put("/api/settings", json={"docs": {"line_height": 1.3}})
    assert after.status_code == 200
    assert after.json()["docs"]["line_height"] == 1.3
    assert client.get("/api/settings").json()["docs"]["line_height"] == 1.3

    refused = client.put("/api/settings", json={"docs": {"line_height": 5}})
    assert refused.status_code == 422
    assert client.get("/api/settings").json()["docs"]["line_height"] == 1.3

    cleared = client.put("/api/settings", json={"docs": {"line_height": None}})
    assert cleared.json()["docs"]["line_height"] is None
    assert client.get("/api/settings").json()["docs"]["line_height"] is None


def test_archiving_over_http_needs_done_and_stays_in_the_figures(client: TestClient) -> None:
    team = "team_default"
    created = client.post(
        f"/api/teams/{team}/tasks", json={"assignee_id": f"{team}__qa", "title": "t", "brief": "b"}
    ).json()
    task = f"/api/teams/{team}/tasks/{created['id']}"
    assert created["archived"] is False

    assert client.patch(task, json={"archived": True}).status_code == 400, "not in Done yet"
    closed = client.patch(task, json={"status": "done", "archived": True})
    assert closed.status_code == 200 and closed.json()["archived"] is True

    board = client.get(f"/api/teams/{team}/tasks", params={"archived": "false"}).json()
    assert created["id"] not in {row["id"] for row in board}
    archive = client.get(f"/api/teams/{team}/tasks", params={"archived": "true"}).json()
    assert [row["id"] for row in archive] == [created["id"]]
    everything = client.get(f"/api/teams/{team}").json()["tasks"]
    assert created["id"] in {row["id"] for row in everything}, "the board hides it itself"

    stats = client.get(f"/api/teams/{team}/stats").json()
    assert stats["team_id"] == team
    assert stats["totals"]["archived"] == 1 and stats["totals"]["done"] == 1
    assert {"spent_usd", "done_usd", "in_flight_usd", "cancelled_usd", "tasks"} <= set(
        stats["totals"]
    )
    assert isinstance(stats["series"], list) and isinstance(stats["by_member"], list)
    assert client.get("/api/teams/ghost/stats").status_code == 404


def test_timestamps_carry_a_timezone(client: TestClient) -> None:
    """SQLite stores none, so they are re-attached on load — otherwise every other
    client reads them shifted by its own UTC offset."""
    team = "team_default"
    created = client.post(
        f"/api/teams/{team}/tasks", json={"assignee_id": f"{team}__dev", "title": "t", "brief": "b"}
    ).json()
    assert created["created_at"].endswith("Z") or "+00:00" in created["created_at"]
