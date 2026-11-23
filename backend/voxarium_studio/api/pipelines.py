"""Pipelines, node types and runs (§11.1)."""

from __future__ import annotations

from typing import Any

from fastapi import APIRouter, HTTPException, Query, Request
from pydantic import BaseModel
from sqlmodel import select

from ..engine import cache as node_cache
from ..engine import dag, asset_cache
from ..engine.store import PipelineNotFound
from ..models.pipeline import NodeRun, Run
from ..schemas.pipeline import PipelineDefinition
from .deps import Ctx

router = APIRouter(tags=["pipelines"])


class RunRequest(BaseModel):
    start_node_id: str | None = None
    use_cache: bool = True
    params: dict[str, Any] = {}


# --------------------------------------------------------------------------- node types


@router.get("/node-types")
async def list_node_types(ctx: Ctx, request: Request) -> dict[str, Any]:
    """Palette data: every node type from built-ins and enabled plugins."""
    schemas = ctx.registry.node_types()
    categories: dict[str, list[str]] = {}
    node_types = []
    for schema in schemas:
        categories.setdefault(schema.category, []).append(schema.id)
        payload = schema.model_dump(mode="json")
        # Resolved here rather than composed by the client, so the asset URL shape lives
        # in one place alongside pages and previewers.
        payload["ui_component_url"] = (
            ctx.registry.asset_url(
                schema.plugin_id, schema.ui_component,
                project_id=request.path_params.get("project_id"),
            )
            if schema.ui_component
            else None
        )
        node_types.append(payload)
    return {"node_types": node_types, "categories": categories}


# --------------------------------------------------------------------------- pipelines


@router.get("/pipelines")
async def list_pipelines(ctx: Ctx) -> list[dict[str, Any]]:
    return [
        {
            "id": pipeline.id,
            "name": pipeline.name,
            "description": pipeline.description,
            "node_count": len(pipeline.nodes),
            "edge_count": len(pipeline.edges),
        }
        for pipeline in ctx.pipelines.list()
    ]


@router.post("/pipelines", status_code=201)
async def create_pipeline(pipeline: PipelineDefinition, ctx: Ctx) -> dict[str, Any]:
    if ctx.pipelines.exists(pipeline.id):
        raise HTTPException(status_code=409, detail=f"Pipeline {pipeline.id!r} already exists")
    ctx.pipelines.save(pipeline)
    return pipeline.model_dump(mode="json", by_alias=True)


@router.get("/pipelines/{pipeline_id}")
async def get_pipeline(pipeline_id: str, ctx: Ctx) -> dict[str, Any]:
    try:
        pipeline = ctx.pipelines.get(pipeline_id)
    except PipelineNotFound as exc:
        raise HTTPException(status_code=404, detail=f"No pipeline {pipeline_id!r}") from exc
    issues = dag.validate(pipeline, ctx.registry)
    return {
        "pipeline": pipeline.model_dump(mode="json", by_alias=True),
        "issues": [
            {"level": i.level, "message": i.message, "node_id": i.node_id} for i in issues
        ],
    }


@router.put("/pipelines/{pipeline_id}")
async def update_pipeline(
    pipeline_id: str, pipeline: PipelineDefinition, ctx: Ctx
) -> dict[str, Any]:
    if pipeline.id != pipeline_id:
        raise HTTPException(
            status_code=400, detail="Pipeline id in the body does not match the URL"
        )
    ctx.pipelines.save(pipeline)
    issues = dag.validate(pipeline, ctx.registry)
    return {
        "pipeline": pipeline.model_dump(mode="json", by_alias=True),
        "issues": [
            {"level": i.level, "message": i.message, "node_id": i.node_id} for i in issues
        ],
    }


@router.delete("/pipelines/{pipeline_id}", status_code=204)
async def delete_pipeline(pipeline_id: str, ctx: Ctx) -> None:
    try:
        ctx.pipelines.delete(pipeline_id)
    except PipelineNotFound as exc:
        raise HTTPException(status_code=404, detail=f"No pipeline {pipeline_id!r}") from exc


@router.post("/pipelines/{pipeline_id}/run", status_code=202)
async def run_pipeline(pipeline_id: str, payload: RunRequest, ctx: Ctx) -> dict[str, Any]:
    try:
        pipeline = ctx.pipelines.get(pipeline_id)
    except PipelineNotFound as exc:
        raise HTTPException(status_code=404, detail=f"No pipeline {pipeline_id!r}") from exc
    try:
        run = await ctx.runner.start(
            pipeline,
            start_node_id=payload.start_node_id,
            use_cache=payload.use_cache,
            params=payload.params,
        )
    except (ValueError, KeyError) as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    return {"run_id": run.id, "status": str(run.status), "pipeline_id": pipeline.id}


# --------------------------------------------------------------------------- runs


@router.get("/runs")
async def list_runs(
    ctx: Ctx,
    pipeline_id: str | None = Query(default=None),
    limit: int = Query(default=50, ge=1, le=500),
) -> list[dict[str, Any]]:
    async with ctx.db.session() as session:
        statement = select(Run).order_by(Run.created_at.desc()).limit(limit)  # type: ignore[attr-defined]
        if pipeline_id:
            statement = statement.where(Run.pipeline_id == pipeline_id)
        result = await session.exec(statement)
        runs = list(result.all())
    return [
        {
            **run.model_dump(mode="json"),
            "is_running": ctx.runner.is_running(run.id),
        }
        for run in runs
    ]


@router.get("/runs/{run_id}")
async def get_run(run_id: str, ctx: Ctx) -> dict[str, Any]:
    async with ctx.db.session() as session:
        run = await session.get(Run, run_id)
        if run is None:
            raise HTTPException(status_code=404, detail=f"No run {run_id!r}")
        result = await session.exec(select(NodeRun).where(NodeRun.run_id == run_id))
        node_runs = list(result.all())
    return {
        "run": run.model_dump(mode="json"),
        "nodes": [node_run.model_dump(mode="json") for node_run in node_runs],
        "is_running": ctx.runner.is_running(run_id),
        # Live state, so it comes from the runner rather than the row: a client that
        # arrives mid-pause has to learn what a `run.paused` event would have told it.
        "paused_nodes": ctx.runner.paused_nodes(run_id),
    }


@router.get("/runs/{run_id}/nodes/{node_id}/log")
async def get_node_log(run_id: str, node_id: str, ctx: Ctx) -> dict[str, Any]:
    """Full log for a node, for when the streamed tail is not enough."""
    # Both ids come straight off the URL, so the composed path gets the same containment
    # check as everything else that takes a client-supplied path.
    path = (ctx.paths.run_dir(run_id) / f"{node_id}.log").resolve()
    if not ctx.paths.is_inside_data(path) or path.parent.parent != ctx.paths.runs:
        raise HTTPException(status_code=400, detail="Invalid run or node id")
    if not path.exists():
        return {"run_id": run_id, "node_id": node_id, "text": "", "exists": False}
    return {
        "run_id": run_id,
        "node_id": node_id,
        "text": path.read_text(encoding="utf-8", errors="replace"),
        "exists": True,
    }


@router.post("/runs/{run_id}/resume")
async def resume_run(run_id: str, ctx: Ctx) -> dict[str, Any]:
    """Continue a run held at a node whose ``pause_on_complete`` is set."""
    resumed = await ctx.runner.resume(run_id)
    if not resumed:
        raise HTTPException(status_code=409, detail="Run is not paused")
    return {"run_id": run_id, "resumed": True}


@router.post("/runs/{run_id}/cancel")
async def cancel_run(run_id: str, ctx: Ctx) -> dict[str, Any]:
    cancelled = await ctx.runner.cancel(run_id)
    if not cancelled:
        raise HTTPException(status_code=409, detail="Run is not active")
    return {"run_id": run_id, "cancelled": True}


# --------------------------------------------------------------------------- cache


@router.delete("/node-cache")
async def clear_node_cache(ctx: Ctx, node_type: str | None = Query(default=None)) -> dict[str, int]:
    async with ctx.db.session() as session:
        removed = await node_cache.clear(session, node_type)
    return {"removed": removed}


@router.delete("/asset-cache")
async def clear_asset_cache(ctx: Ctx) -> dict[str, int]:
    # spec §8.2: historical outputs are candidates, saved inputs remain protected.
    async with ctx.db.session() as session:
        outputs = list((await session.exec(select(NodeRun.outputs))).all())
    # No awaits between this guard and deletion: another Studio job cannot start midway.
    if any(ctx.jobs.is_running(job.id) for job in ctx.jobs.jobs()):
        raise HTTPException(status_code=409, detail="Stop running jobs before clearing the asset cache")
    try:
        return asset_cache.clear_unused(ctx.paths, outputs)
    except (OSError, ValueError, asset_cache.yaml.YAMLError) as exc:
        raise HTTPException(status_code=409, detail=str(exc)) from exc
