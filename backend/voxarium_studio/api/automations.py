"""The project's automations: ``.voxarium_studio/automations.yaml`` and what it has done (§6.5, §11.1).

Every write answers with the whole page, as the Skills endpoints do and for their reason: the
file is edited by hand and by merges as often as from here, so a client patching its own list
from a narrower answer would drift from the file.
"""

from __future__ import annotations

from typing import Any

from fastapi import APIRouter, HTTPException, Query
from pydantic import BaseModel

from ..automations.engine import run_json
from ..automations.schema import (
    Automation,
    AutomationInput,
    PluginAction,
    RunPipelineAction,
)
from ..automations.store import AutomationNotFound, AutomationsUnreadable
from ..core.context import AppContext
from ..ws.events import EventType
from .deps import Ctx

router = APIRouter(prefix="/automations", tags=["automations"])


class EnabledPatch(BaseModel):
    enabled: bool


def _problem(ctx: AppContext, automation: Automation) -> str | None:
    """Why this automation's action cannot run as things stand — checked when the page is
    read rather than when the automation is saved, because a pipeline is deleted and a
    plugin disabled long after, and the page is where somebody would find out."""
    action = automation.action
    if isinstance(action, RunPipelineAction) and not ctx.pipelines.exists(action.pipeline_id):
        return f"No pipeline {action.pipeline_id!r}"
    if isinstance(action, PluginAction):
        if ctx.registry.automation_action(action.plugin_id, action.action_id) is None:
            return f"Plugin {action.plugin_id!r} offers no action {action.action_id!r}"
    return None


async def _overview(ctx: AppContext) -> dict[str, Any]:
    loaded = ctx.automation_store.load()
    return {
        "path": ctx.automation_store.relative(),
        "exists": ctx.automation_store.file.exists(),
        "error": loaded.error,
        "automations": [
            {**automation.model_dump(mode="json"), "problem": _problem(ctx, automation)}
            for automation in loaded.automations
        ],
        "invalid": [
            {"index": entry.index, "id": entry.id, "error": entry.error}
            for entry in loaded.invalid
        ],
        # What plugins offer as actions — data, like every manifest string (§6.4).
        "plugin_actions": [
            {
                "plugin_id": offered.plugin_id,
                "action_id": offered.id,
                "label": offered.label,
                "description": offered.description,
            }
            for offered in ctx.registry.automation_actions()
        ],
        "runs": [run_json(row) for row in await ctx.automations.recent()],
    }


async def _written(ctx: AppContext) -> dict[str, Any]:
    await ctx.bus.publish(EventType.AUTOMATION_CHANGED, {})
    return await _overview(ctx)


@router.get("")
async def list_automations(ctx: Ctx) -> dict[str, Any]:
    return await _overview(ctx)


# The log's routes come before `/{automation_id}`: declared after it, `DELETE /runs` would be
# read as deleting an automation called "runs".


@router.get("/runs")
async def list_runs(ctx: Ctx, limit: int = Query(default=50, ge=1, le=500)) -> dict[str, Any]:
    return {"runs": [run_json(row) for row in await ctx.automations.recent(limit)]}


@router.delete("/runs")
async def clear_runs(ctx: Ctx) -> dict[str, Any]:
    await ctx.automations.clear_log()
    return await _overview(ctx)


@router.post("", status_code=201)
async def create_automation(payload: AutomationInput, ctx: Ctx) -> dict[str, Any]:
    try:
        ctx.automation_store.upsert(Automation(**payload.model_dump()))
    except AutomationsUnreadable as exc:
        raise HTTPException(status_code=409, detail=str(exc)) from exc
    return await _written(ctx)


@router.put("/{automation_id}")
async def update_automation(
    automation_id: str, payload: AutomationInput, ctx: Ctx
) -> dict[str, Any]:
    try:
        ctx.automation_store.get(automation_id)
        ctx.automation_store.upsert(Automation(id=automation_id, **payload.model_dump()))
    except AutomationNotFound as exc:
        raise HTTPException(status_code=404, detail=f"No automation {automation_id!r}") from exc
    except AutomationsUnreadable as exc:
        raise HTTPException(status_code=409, detail=str(exc)) from exc
    return await _written(ctx)


@router.patch("/{automation_id}")
async def set_enabled(automation_id: str, payload: EnabledPatch, ctx: Ctx) -> dict[str, Any]:
    """The card's switch. Its own route so a toggle cannot write back a stale form."""
    try:
        automation = ctx.automation_store.get(automation_id)
        ctx.automation_store.upsert(automation.model_copy(update={"enabled": payload.enabled}))
    except AutomationNotFound as exc:
        raise HTTPException(status_code=404, detail=f"No automation {automation_id!r}") from exc
    except AutomationsUnreadable as exc:
        raise HTTPException(status_code=409, detail=str(exc)) from exc
    return await _written(ctx)


@router.delete("/{automation_id}")
async def delete_automation(automation_id: str, ctx: Ctx) -> dict[str, Any]:
    try:
        ctx.automation_store.delete(automation_id)
    except AutomationNotFound as exc:
        raise HTTPException(status_code=404, detail=f"No automation {automation_id!r}") from exc
    except AutomationsUnreadable as exc:
        raise HTTPException(status_code=409, detail=str(exc)) from exc
    return await _written(ctx)


@router.post("/{automation_id}/run")
async def run_automation(automation_id: str, ctx: Ctx) -> dict[str, Any]:
    """Run the action now, with nothing having happened — to see what it does. Works on a
    disabled automation too: trying one out before switching it on is the point."""
    try:
        automation = ctx.automation_store.get(automation_id)
    except AutomationNotFound as exc:
        raise HTTPException(status_code=404, detail=f"No automation {automation_id!r}") from exc
    row = await ctx.automations.run_now(automation)
    return {"run": run_json(row)}
