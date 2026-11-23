"""Pipeline definitions on disk.

Definitions are YAML rather than DB rows so they stay reviewable and diffable in git —
they are the description of the project's AI workflow (§2.1/§5). Runtime state (runs,
logs, cache) is what lives in SQLite.
"""

from __future__ import annotations

import logging
import re
from pathlib import Path

import yaml

from ..core.paths import ProjectPaths
from ..schemas.pipeline import PipelineDefinition

log = logging.getLogger(__name__)

_SLUG_RE = re.compile(r"[^a-zA-Z0-9_-]+")


def slugify(value: str) -> str:
    slug = _SLUG_RE.sub("-", value.strip()).strip("-").lower()
    return slug or "pipeline"


class PipelineNotFound(KeyError):
    pass


class PipelineStore:
    def __init__(self, paths: ProjectPaths) -> None:
        self.paths = paths

    @property
    def directory(self) -> Path:
        return self.paths.pipelines

    def _path_for(self, pipeline_id: str) -> Path:
        return self.directory / f"{slugify(pipeline_id)}.yaml"

    def list(self) -> list[PipelineDefinition]:
        self.directory.mkdir(parents=True, exist_ok=True)
        pipelines: list[PipelineDefinition] = []
        for path in sorted(self.directory.glob("*.y*ml")):
            try:
                pipelines.append(self._load_file(path))
            except Exception as exc:
                log.error("Skipping invalid pipeline %s: %s", path.name, exc)
        return pipelines

    def _load_file(self, path: Path) -> PipelineDefinition:
        raw = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
        if not isinstance(raw, dict):
            raise ValueError("pipeline file must contain a mapping")
        raw.setdefault("id", path.stem)
        raw.setdefault("name", path.stem.replace("-", " ").title())
        return PipelineDefinition.model_validate(raw)

    def get(self, pipeline_id: str) -> PipelineDefinition:
        path = self._path_for(pipeline_id)
        if not path.exists():
            # Fall back to a scan: the id inside a file may differ from its filename.
            for candidate in self.directory.glob("*.y*ml"):
                try:
                    pipeline = self._load_file(candidate)
                except Exception:
                    continue
                if pipeline.id == pipeline_id:
                    return pipeline
            raise PipelineNotFound(pipeline_id)
        return self._load_file(path)

    def exists(self, pipeline_id: str) -> bool:
        try:
            self.get(pipeline_id)
        except PipelineNotFound:
            return False
        return True

    def save(self, pipeline: PipelineDefinition) -> PipelineDefinition:
        self.directory.mkdir(parents=True, exist_ok=True)
        path = self._path_for(pipeline.id)
        text = yaml.safe_dump(
            pipeline.to_yaml_dict(), sort_keys=False, allow_unicode=True, indent=2
        )
        tmp = path.with_suffix(".yaml.tmp")
        tmp.write_text(text, encoding="utf-8")
        tmp.replace(path)
        return pipeline

    def delete(self, pipeline_id: str) -> None:
        path = self._path_for(pipeline_id)
        if path.exists():
            path.unlink()
            return
        for candidate in list(self.directory.glob("*.y*ml")):
            try:
                if self._load_file(candidate).id == pipeline_id:
                    candidate.unlink()
                    return
            except Exception:
                continue
        raise PipelineNotFound(pipeline_id)
