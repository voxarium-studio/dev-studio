"""The lines the Studio says to an agent are settings, not literals (§10.3, §10.4).

``core/prompts.py`` holds the inventory, ``teams.prompts`` in ``config.yaml`` holds only
the overrides, and ``GET /api/settings/prompts`` serves the defaults the Settings page
shows. The run-level half — what actually reaches a session — is covered beside the other
execution tests in ``test_teams.py``.
"""

from __future__ import annotations

from pathlib import Path

from fastapi.testclient import TestClient

from voxarium_studio.core.config import load_config, save_config
from voxarium_studio.core.paths import ProjectPaths
from voxarium_studio.core.prompts import SPECS, AgentPromptsConfig, fill
from voxarium_studio.models.teams import TeamMember
from voxarium_studio.teams.agents import identity_lines
from voxarium_studio.teams.orchestrator import _build_system_prompt

# ------------------------------------------------------------------ resolution


def test_every_spec_has_a_config_field_and_nothing_else() -> None:
    """A spec without a field could never be overridden, and a field without a spec could
    never resolve. The module asserts this at import too; ``-O`` strips that, this stays."""
    assert set(SPECS) == set(AgentPromptsConfig.model_fields)


def test_the_defaults_resolve_with_their_placeholders_filled() -> None:
    prompts = AgentPromptsConfig()
    line = prompts.text("task_context", task_id="VS-07")
    assert line.startswith("You are working on task VS-07.")
    assert "{task_id}" not in line


def test_an_override_replaces_the_default() -> None:
    prompts = AgentPromptsConfig(task_context="Task {task_id}. Keep it short.")
    assert prompts.text("task_context", task_id="VS-07") == "Task VS-07. Keep it short."


def test_blanking_an_optional_fragment_removes_it() -> None:
    prompts = AgentPromptsConfig(studio_skill="", docs_location="   ")
    assert prompts.text("studio_skill", skill="voxarium_studio:studio") == ""
    assert prompts.text("docs_location", docs_dir="somewhere") == ""


def test_responsibility_leads_the_identity_block() -> None:
    """The member's Responsibility opens the identity block and the introduction follows
    it (§10.3). Nothing shared sits above it: the System Prompt Base that did was retired
    on 2026-11-12."""
    member = TeamMember(team_id="team", name="Marina", role_title="Game Designer",
                        responsibility="Levels and narrative.")

    built = identity_lines(member=member, team=None, manager=None, reports=[], peers=[],
                           prompts=AgentPromptsConfig())
    assert built[0] == "Levels and narrative."
    assert built[1].startswith("You are Marina")
    assert "system_prompt_base" not in SPECS


def test_a_config_still_carrying_the_retired_base_loads_and_loses_it(project: Path) -> None:
    """A project that wrote a System Prompt Base keeps the key in ``config.yaml`` until its
    next save; it has to load rather than fail the Studio's start, and the text is no
    longer sent."""
    paths = ProjectPaths.for_project(project)
    paths.config_file.write_text(
        "teams:\n"
        "  prompts:\n"
        "    finish: Wrap up.\n"
        "    system_prompt_base: Common rules for everyone.\n",
        encoding="utf-8",
    )
    config = load_config(paths)
    assert config.teams.prompts.finish == "Wrap up."
    assert not hasattr(config.teams.prompts, "system_prompt_base")

    save_config(paths, config)
    assert "system_prompt_base" not in paths.config_file.read_text(encoding="utf-8")


def test_a_task_with_studio_tools_is_told_to_delegate_through_them() -> None:
    """§10.3: the ``subagents`` fragment names the Studio's tools and rules out the
    runtime's own. It goes only where those tools work — a task run they reach; a chat
    has no task to split, and a run without them has nothing to spawn with."""
    member = TeamMember(id="m", team_id="t", name="Ada", role_title="Developer")

    def prompt(prompts: AgentPromptsConfig | None = None, **overrides: bool) -> str:
        return _build_system_prompt(
            member=member, team=None, manager=None, reports=[], peers=[], task_id="VS-1",
            project_root=Path("/p"), workdir=Path("/p"), isolated=False,
            docs_dir=Path("/p/docs"), prompts=prompts or AgentPromptsConfig(),
            **{"has_studio_tools": True, **overrides},
        )

    task = prompt()
    assert all(tool in task for tool in ("spawn_subagent", "wait_subagents", "get_subagent"))
    assert "Never use your own agent or task tools." in task
    assert "do not create subagents" in task
    assert "spawn_subagent" not in prompt(chat=True)
    assert "spawn_subagent" not in prompt(has_studio_tools=False)
    assert "spawn_subagent" not in prompt(AgentPromptsConfig(subagents=""))


def test_blanking_a_required_message_falls_back_to_the_default() -> None:
    """``resume``, ``review_changes`` and ``stall_continue`` each *open* a run — there is
    no run to have with an empty message, so a blank override means the default."""
    prompts = AgentPromptsConfig(resume="  ", review_changes="", stall_continue="")
    assert prompts.text("resume") == SPECS["resume"].default
    assert "tighten it" in prompts.text("review_changes", comment="tighten it")
    assert prompts.text("stall_continue") == SPECS["stall_continue"].default


def test_templates_are_user_input_and_stray_braces_survive() -> None:
    """``str.format`` would raise on an unknown name; a reworded prompt must reach the
    model as typed rather than fail the run it opens."""
    assert fill("fix {this} and {that}", {"this": "X"}) == "fix X and {that}"
    prompts = AgentPromptsConfig(review_changes="Braces {like so} stay. {comment}")
    assert prompts.text("review_changes", comment="c") == "Braces {like so} stay. c"


# ------------------------------------------------------------------ config.yaml


def test_prompt_overrides_round_trip_through_config_yaml(project: Path) -> None:
    """An empty string is a *removal* and must survive the trip: ``save_config`` drops
    ``None`` (absent, meaning default), so YAML has to keep ``''`` distinct from that."""
    paths = ProjectPaths.for_project(project)
    config = load_config(paths)
    config.teams.prompts.finish = "Wrap up."
    config.teams.prompts.studio_skill = ""
    save_config(paths, config)

    read = load_config(paths)
    assert read.teams.prompts.finish == "Wrap up."
    assert read.teams.prompts.studio_skill == ""
    assert read.teams.prompts.resume is None, "untouched fragments are not written out"
    assert "resume:" not in paths.config_file.read_text(encoding="utf-8"), (
        "config.yaml records only what the user changed"
    )


def test_a_config_still_overriding_the_retired_tool_prompts_loads_and_loses_them(
    project: Path,
) -> None:
    """``tools_board``, ``tools_delegation``, ``tools_docs`` and ``no_delegation`` left the
    inventory on 2026-09-16, when the Studio skill took over describing the tools (§10.3).

    A project that reworded one still carries the key, so it has to load rather than fail the
    Studio's start; nothing migrates the wording, because there is no fragment left that says
    the same thing. The next ``save_config`` writes the file without it.
    """
    paths = ProjectPaths.for_project(project)
    paths.config_file.write_text(
        "teams:\n"
        "  prompts:\n"
        "    finish: Wrap up.\n"
        "    tools_board: Our own board wording.\n"
        "    no_delegation: ''\n",
        encoding="utf-8",
    )
    config = load_config(paths)
    assert config.teams.prompts.finish == "Wrap up.", "the rest of the section still loads"
    assert not hasattr(config.teams.prompts, "tools_board")

    save_config(paths, config)
    written = paths.config_file.read_text(encoding="utf-8")
    assert "tools_board" not in written and "no_delegation" not in written
    assert "Wrap up." in written


# ------------------------------------------------------------------ the settings API


def test_the_prompt_defaults_are_served_to_the_settings_page(client: TestClient) -> None:
    specs = client.get("/api/settings/prompts").json()
    assert list(specs) == list(SPECS), "same keys, same order — the page renders it as is"
    assert "{skill}" in specs["studio_skill"]["default"]
    assert specs["studio_skill"]["placeholders"] == ["skill"]
    assert specs["review_changes"]["placeholders"] == ["comment"]
    assert specs["resume"]["required"] is True
    assert specs["studio_skill"]["required"] is False


def test_a_prompt_patch_merges_with_the_stored_overrides(client: TestClient) -> None:
    """The same partial-update rule as theme and concurrency, one level deeper: editing
    one prompt must not reset another, or the teams section around them."""
    client.put("/api/settings", json={"teams": {"prompts": {"finish": "Wrap up."}}})

    after = client.put(
        "/api/settings", json={"teams": {"prompts": {"studio_skill": ""}}}
    ).json()["teams"]

    assert after["prompts"]["finish"] == "Wrap up."
    assert after["prompts"]["studio_skill"] == "", "an empty string is a removal, stored as such"
    assert after["prompts"]["resume"] is None
    assert after["launch_root"] == ".", "the section around the prompts survived"
    # And it is the stored config that changed, not just the response.
    assert client.get("/api/settings").json()["teams"]["prompts"]["finish"] == "Wrap up."


def test_a_prompt_resets_to_null_rather_than_to_a_copy_of_the_default(
    client: TestClient,
) -> None:
    """Reset stores nothing, so the project keeps following the built-in wording as the
    Studio evolves — a stored copy would freeze today's text forever."""
    client.put("/api/settings", json={"teams": {"prompts": {"finish": "Wrap up."}}})

    after = client.put(
        "/api/settings", json={"teams": {"prompts": {"finish": None}}}
    ).json()["teams"]["prompts"]

    assert after["finish"] is None


def test_an_identity_edit_reaches_the_agent_definitions(
    client: TestClient, project: Path
) -> None:
    """The identity sentences are baked into ``.claude/agents/*.md`` (§10.3), so the
    settings endpoint re-syncs the files a developer's terminal reads too."""
    client.put(
        "/api/settings",
        json={"teams": {"prompts": {"identity": "You are {name}, our {role}."}}},
    )

    definition = (project / ".claude" / "agents" / "marina.md").read_text(encoding="utf-8")
    assert "You are Marina, our Game Designer." in definition
