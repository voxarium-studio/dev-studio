"""Pipeline definitions on disk.

Definitions are YAML rather than DB rows so they stay reviewable and diffable in git —
they are the description of the project's AI workflow (§2.1/§5). Runtime state (runs,
logs, cache) is what lives in SQLite.
"""

from __future__ import annotations

import hashlib
import logging
import re
from os import linesep
from pathlib import Path
from threading import Lock

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


class PipelineRevisionConflict(Exception):
    pass


class PipelineStore:
    def __init__(self, paths: ProjectPaths) -> None:
        self.paths = paths
        self._mutations = Lock()

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
                pipeline, _ = self._load_file(path)
                pipelines.append(pipeline)
            except Exception as exc:
                log.error("Skipping invalid pipeline %s: %s", path.name, exc)
        return pipelines

    def _load_file(self, path: Path) -> tuple[PipelineDefinition, str]:
        source = path.read_bytes()
        raw = yaml.safe_load(source.decode("utf-8")) or {}
        if not isinstance(raw, dict):
            raise ValueError("pipeline file must contain a mapping")
        raw.setdefault("id", path.stem)
        raw.setdefault("name", path.stem.replace("-", " ").title())
        return PipelineDefinition.model_validate(raw), hashlib.sha256(source).hexdigest()

    def get(self, pipeline_id: str) -> PipelineDefinition:
        return self.get_with_revision(pipeline_id)[0]

    def get_with_revision(self, pipeline_id: str) -> tuple[PipelineDefinition, str]:
        _, pipeline, revision = self._entry(pipeline_id)
        return pipeline, revision

    def _entry(self, pipeline_id: str) -> tuple[Path, PipelineDefinition, str]:
        path = self._path_for(pipeline_id)
        try:
            pipeline, revision = self._load_file(path)
            return path, pipeline, revision
        except FileNotFoundError:
            # Fall back to a scan: the id inside a file may differ from its filename.
            for candidate in self.directory.glob("*.y*ml"):
                try:
                    pipeline, revision = self._load_file(candidate)
                except Exception:
                    continue
                if pipeline.id == pipeline_id:
                    return candidate, pipeline, revision
            raise PipelineNotFound(pipeline_id) from None

    def exists(self, pipeline_id: str) -> bool:
        try:
            self.get(pipeline_id)
        except PipelineNotFound:
            return False
        return True

    def save(self, pipeline: PipelineDefinition) -> PipelineDefinition:
        self.save_with_revision(pipeline)
        return pipeline

    def save_with_revision(self, pipeline: PipelineDefinition, *, expected_revision: str | None = None) -> str:
        text = yaml.safe_dump(
            pipeline.to_yaml_dict(), sort_keys=False, allow_unicode=True, indent=2
        )
        source = text.replace("\n", linesep).encode("utf-8")
        with self._mutations:
            self.directory.mkdir(parents=True, exist_ok=True)
            path = self._path_for(pipeline.id)
            if expected_revision is not None:
                # §2.7.1: a native edit must target the same on-disk definition
                # it read. Honor legacy filenames and refuse to recreate a deletion.
                try:
                    path, current, revision = self._entry(pipeline.id)
                except (PipelineNotFound, ValueError, yaml.YAMLError) as exc:
                    raise PipelineRevisionConflict(pipeline.id) from exc
                if revision != expected_revision or current.id != pipeline.id:
                    raise PipelineRevisionConflict(pipeline.id)
            tmp = path.with_suffix(path.suffix + ".tmp")
            try:
                tmp.write_bytes(source)
                tmp.replace(path)
            except OSError:
                tmp.unlink(missing_ok=True)
                raise
        return hashlib.sha256(source).hexdigest()

    def delete(self, pipeline_id: str) -> None:
        with self._mutations:
            path = self._path_for(pipeline_id)
            if path.exists():
                path.unlink()
                return
            for candidate in self.directory.glob("*.y*ml"):
                try:
                    pipeline, _ = self._load_file(candidate)
                    if pipeline.id == pipeline_id:
                        candidate.unlink()
                        return
                except Exception:
                    continue
            raise PipelineNotFound(pipeline_id)
