"""Effort Solver's identity, task scores and deterministic calculation (§10.11)."""
from __future__ import annotations

import argparse
import json
import math
import re
import sys
from typing import Any

from pydantic import BaseModel, ConfigDict, Field, model_validator

EFFORT_SOLVER_ID = "studio-effort-solver"
EFFORT_LEVELS = ("low", "medium", "high", "xhigh", "max")

DEFAULT_SYSTEM_PROMPT = """# Role
You are **Effort Solver Agent**. Evaluate the input task across four independent dimensions, scoring each from 1 to 10.

# Criteria
- **Complexity:** 1 = trivial changes or simple steps; 10 = complex features or system architecture.
- **Creativity:** 1 = fully specified implementation; 10 = solution must be designed from scratch.
- **Depth:** 1 = clear, sequential execution regardless of complexity; 10 = unknown causes or solutions requiring investigation.
- **Coverage:** 1 = no instructions, paths, links, or documentation; 10 = precise instructions and comprehensive references.

# Rules
- Evaluate only the provided task description.
- Score each dimension independently.
- Do not assume missing information.
- Return only JSON with integer scores.

# Output
```json
{
  "complexity": 1,
  "creativity": 1,
  "depth": 1,
  "coverage": 1
}
```"""


def is_effort_solver(member: Any) -> bool:
    return member.id == f"{member.team_id}__{EFFORT_SOLVER_ID}"


class EffortScores(BaseModel):
    model_config = ConfigDict(extra="forbid")

    complexity: int = Field(ge=1, le=10, strict=True)
    creativity: int = Field(ge=1, le=10, strict=True)
    depth: int = Field(ge=1, le=10, strict=True)
    coverage: int = Field(ge=1, le=10, strict=True)


class EffortWeights(BaseModel):
    model_config = ConfigDict(extra="forbid")

    complexity: float = Field(default=1.5, ge=0, strict=True, allow_inf_nan=False)
    creativity: float = Field(default=1.5, ge=0, strict=True, allow_inf_nan=False)
    depth: float = Field(default=3, ge=0, strict=True, allow_inf_nan=False)
    coverage: float = Field(default=2.5, ge=0, strict=True, allow_inf_nan=False)

    @model_validator(mode="after")
    def positive_total(self) -> EffortWeights:
        total = sum(self.model_dump().values())
        if not math.isfinite(total) or total <= 0:
            raise ValueError("Effort Solver weights must have a finite, positive total.")
        return self


def parse_scores(value: str | dict[str, Any] | EffortScores) -> EffortScores:
    """Accept the JSON object returned by the agent, optionally in a JSON code fence."""
    if isinstance(value, str):
        value = value.strip()
        fenced = re.fullmatch(r"```(?:json)?\s*\n?(.*?)\n?```", value, re.DOTALL)
        if fenced:
            value = fenced.group(1).strip()
        value = json.loads(value)
    return EffortScores.model_validate(value)


def calculate_effort(
    scores: EffortScores | dict[str, Any] | str,
    weights: EffortWeights | dict[str, Any] | None = None,
) -> int:
    """Map independent 1–10 scores to 1–5, normalising by the configured weights.

    The default divisor is 19.125. Coverage is inverted: a fully documented task
    needs less effort. Python's round implements the requested rounding rule.
    """
    scores = parse_scores(scores)
    weights = EffortWeights.model_validate(weights if weights is not None else {})
    values = weights.model_dump()
    total = sum(values.values())
    # Normalise each weight first to avoid overflowing intermediate products.
    weighted = sum(
        (values[name] / total) * (10 - score if name == "coverage" else score - 1)
        for name, score in scores.model_dump().items()
    )
    return max(1, min(5, round(1 + weighted * 4 / 9)))


def main() -> None:
    parser = argparse.ArgumentParser(description="Calculate a task's Effort (1–5) from JSON scores.")
    parser.add_argument("scores", nargs="?", help="JSON scores; omitted reads standard input")
    parser.add_argument("--weights", help="JSON weight overrides; defaults to 1.5, 1.5, 3, 2.5")
    args = parser.parse_args()
    try:
        weights = json.loads(args.weights) if args.weights is not None else None
        result = calculate_effort(args.scores if args.scores is not None else sys.stdin.read(), weights)
    except (ValueError, TypeError) as exc:
        parser.error(str(exc))
    print(result)


if __name__ == "__main__":
    main()
