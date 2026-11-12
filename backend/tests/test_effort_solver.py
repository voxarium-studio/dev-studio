"""Deterministic Effort Solver scores, protected identity and portable weights (§10.11)."""
import io
import json
import sqlite3
import subprocess
import sys

import pytest
import yaml
from fastapi.testclient import TestClient
from pydantic import ValidationError
from sqlalchemy import text

from voxarium_studio.core.db import Database
from voxarium_studio.main import create_app
from voxarium_studio.models.teams import TeamMember
from voxarium_studio.teams.effort_solver import (
    DEFAULT_SYSTEM_PROMPT,
    EFFORT_LEVELS,
    EFFORT_SOLVER_ID,
    EffortWeights,
    calculate_effort,
    main,
    parse_scores,
)

TEAM = "team_default"
SOLVER = f"{TEAM}__{EFFORT_SOLVER_ID}"
MEMBER_URL = f"/api/teams/{TEAM}/members/{SOLVER}"
SCORES = {"complexity": 9, "creativity": 6, "depth": 8, "coverage": 8}


def solver_member(client):
    return next(member for member in client.get(f"/api/teams/{TEAM}").json()["members"]
                if member["id"] == SOLVER)


def test_calculation_matches_requested_example_and_extremes():
    assert calculate_effort(SCORES) == 3
    assert EFFORT_LEVELS[calculate_effort(SCORES) - 1] == "high"
    # The defaults are 1.5, 1.5, 3, 2.5; the earlier 2, 1.5, 3, 2 scored this example 4.
    assert EffortWeights().model_dump() == dict(complexity=1.5, creativity=1.5, depth=3, coverage=2.5)
    assert calculate_effort(SCORES, dict(complexity=2, creativity=1.5, depth=3, coverage=2)) == 4
    assert calculate_effort(dict(complexity=1, creativity=1, depth=1, coverage=10)) == 1
    assert calculate_effort(dict(complexity=10, creativity=10, depth=10, coverage=1)) == 5


def test_custom_weights_normalise_scale_and_invert_coverage():
    weights = dict(complexity=0, creativity=0, depth=0, coverage=4)
    assert calculate_effort(SCORES, weights) == 2
    assert calculate_effort(SCORES, {**weights, "coverage": 40}) == 2
    assert calculate_effort({**SCORES, "coverage": 1}, weights) == 5
    assert calculate_effort({**SCORES, "coverage": 10}, weights) == 1


@pytest.mark.parametrize("value", [0, 11, 1.0, True, "5", None])
def test_scores_require_integers_in_range(value):
    with pytest.raises(ValidationError):
        calculate_effort({**SCORES, "depth": value})


def test_json_and_fenced_json_are_accepted_but_missing_or_extra_scores_fail():
    assert parse_scores(json.dumps(SCORES)).model_dump() == SCORES
    assert calculate_effort(f"```json\n{json.dumps(SCORES)}\n```") == 3
    with pytest.raises(ValidationError):
        parse_scores({"complexity": 5})
    with pytest.raises(ValidationError):
        parse_scores({**SCORES, "effort": 5})
    with pytest.raises(ValueError):
        parse_scores("Here are the scores: " + json.dumps(SCORES))


@pytest.mark.parametrize("weights", [
    {"depth": -1}, {"depth": float("inf")}, {"depth": float("nan")},
    {"depth": True}, {"depth": "3"}, {"other": 2},
    dict(complexity=0, creativity=0, depth=0, coverage=0),
])
def test_invalid_weights_are_rejected(weights):
    with pytest.raises(ValidationError):
        EffortWeights.model_validate(weights)


def test_cli_reads_stdin_and_prints_numeric_effort(monkeypatch, capsys):
    monkeypatch.setattr("sys.argv", ["effort_solver"])
    monkeypatch.setattr("sys.stdin", io.StringIO(json.dumps(SCORES)))
    main()
    assert capsys.readouterr().out == "3\n"


def test_package_cli_accepts_stdin_without_runtime_warnings():
    result = subprocess.run(
        [sys.executable, "-m", "voxarium_studio.effort_solver"],
        input=json.dumps(SCORES), text=True, capture_output=True, timeout=30,
    )
    assert result.returncode == 0, result.stderr
    assert result.stdout == "3\n"
    assert result.stderr == ""


def test_builtin_is_added_with_prompt_and_cannot_be_deleted_or_made_human(client):
    solver = solver_member(client)
    assert solver["builtin"] == "effort_solver"
    assert solver["kind"] == "agent"
    assert solver["responsibility"] == DEFAULT_SYSTEM_PROMPT
    assert solver["effort_solver_weights"] is None
    renamed = client.patch(MEMBER_URL, json={"name": "Task scorer", "role_title": "Estimator"})
    assert renamed.status_code == 200
    assert client.patch(MEMBER_URL, json={"kind": "human"}).status_code == 400
    for query in ("", "?reassign_to_director=true"):
        assert client.delete(MEMBER_URL + query).status_code == 409


def test_weights_are_solver_only_validated_atomically_and_resettable(client, project):
    ordinary = f"/api/teams/{TEAM}/members/{TEAM}__dev"
    assert client.patch(ordinary, json={"effort_solver_weights": {"depth": 5}}).status_code == 400
    before = solver_member(client)
    invalid = client.patch(MEMBER_URL, json={"name": "Do not save", "effort_solver_weights": {"depth": -1}})
    assert invalid.status_code == 400
    assert solver_member(client) == before
    custom = client.patch(MEMBER_URL, json={"effort_solver_weights": {"depth": 5}})
    assert custom.status_code == 200
    assert custom.json()["effort_solver_weights"] == dict(complexity=1.5, creativity=1.5, depth=5, coverage=2.5)
    cleared = client.patch(MEMBER_URL, json={"effort_solver_weights": None})
    assert cleared.status_code == 200
    assert cleared.json()["effort_solver_weights"] is None
    roster = yaml.safe_load((project / ".voxarium_studio/teams/default-team.yaml").read_text("utf-8"))
    assert "effort_solver_weights" not in next(m for m in roster["members"] if m["id"] == EFFORT_SOLVER_ID)


def test_solver_settings_survive_restart(project):
    settings = dict(model="custom-model", responsibility="Custom scorer instructions",
                    effort_solver_weights=dict(complexity=1, creativity=1, depth=5, coverage=0))
    with TestClient(create_app(str(project)), base_url="http://127.0.0.1") as client:
        assert client.patch(MEMBER_URL, json=settings).status_code == 200
    with TestClient(create_app(str(project)), base_url="http://127.0.0.1") as client:
        saved = solver_member(client)
        assert all(saved[key] == value for key, value in settings.items())


async def test_yaml_restores_removed_solver_with_custom_settings_and_normalises_kind(ctx):
    path = ctx.teams.store.file_for(TEAM)
    roster = yaml.safe_load(path.read_text("utf-8"))
    solver = next(member for member in roster["members"] if member["id"] == EFFORT_SOLVER_ID)
    solver.update(kind="human", name="Custom scorer", responsibility="Custom instructions",
                  effort_solver_weights=dict(complexity=1, creativity=1, depth=5, coverage=0))
    path.write_text(yaml.safe_dump(roster), encoding="utf-8")
    await ctx.teams.sync_definitions()
    async with ctx.db.session() as session:
        saved = await session.get(TeamMember, SOLVER)
        assert saved.kind == "agent" and saved.builtin == "effort_solver"
    roster["members"] = [member for member in roster["members"] if member["id"] != EFFORT_SOLVER_ID]
    path.write_text(yaml.safe_dump(roster), encoding="utf-8")
    await ctx.teams.sync_definitions()
    restored = next(member for member in yaml.safe_load(path.read_text("utf-8"))["members"]
                    if member["id"] == EFFORT_SOLVER_ID)
    assert restored["name"] == "Custom scorer"
    assert restored["responsibility"] == "Custom instructions"
    assert restored["effort_solver_weights"]["depth"] == 5
    assert restored.get("kind", "agent") == "agent"
    empty = ctx.teams.store.directory / "empty.yaml"
    empty.write_text("id: empty\nname: Empty\nmembers: []\n", encoding="utf-8")
    await ctx.teams.sync_definitions()
    async with ctx.db.session() as session:
        assert (await session.get(TeamMember, f"empty__{EFFORT_SOLVER_ID}")).kind == "agent"


async def test_existing_database_gains_nullable_weights_without_changing_list_defaults(tmp_path):
    path = tmp_path / "old.db"
    with sqlite3.connect(path) as connection:
        connection.execute("CREATE TABLE team_member (id TEXT PRIMARY KEY, team_id TEXT, name TEXT, role_title TEXT)")
        connection.execute("INSERT INTO team_member VALUES ('old', 'team', 'Old', 'Role')")
    db = Database(path)
    try:
        await db.create_all()
        async with db.session() as session:
            row = (await session.exec(text(
                "SELECT effort_solver_weights, skills FROM team_member WHERE id = 'old'"
            ))).one()
        assert row.effort_solver_weights is None
        assert row.skills == "[]"
    finally:
        await db.close()
