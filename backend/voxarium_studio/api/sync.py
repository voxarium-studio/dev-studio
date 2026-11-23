"""Online sync settings and status (§16.5).

Kept apart from ``PUT /api/settings`` because one of its fields is not a setting of the
project at all: the key is the user's and is stored in their own config directory. This
endpoint takes it, stores it there, and never gives it back — a status says only whether one
is stored.
"""

from __future__ import annotations

import hashlib
from collections.abc import Callable
from typing import Any, Literal

from fastapi import APIRouter, Depends, HTTPException, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from fastapi.routing import APIRoute
from pydantic import BaseModel, ConfigDict, Field, StrictBool, StrictInt, field_validator, model_validator

from ..core.config import save_config
from ..core.project_identity import ProjectIdentity, _PlanConfigLoader
from ..explorer.service import PathOutsideProject
from ..sync import credentials
from ..sync.protocol import check_space_name, normalise_url
from ..sync.transitions import MutationGateError
from ..ws.events import EventType
from .deps import Ctx

class FencedRoute(APIRoute):
    """I4: fencing errors are typed, and sensitive responses are never cached.

    Wrap dependency resolution too: auth, invalid handles and validation must
    receive the same no-store policy as a successful editor lease response.
    Documents retain their existing read/asset caching policy.
    """

    def get_route_handler(self) -> Callable:
        original = super().get_route_handler()

        async def handler(request: Request):
            sensitive = "/docs" not in self.path or request.method not in {"GET", "HEAD"}
            try:
                response = await original(request)
            except MutationGateError as exc:
                code = "unknown_sid" if exc.code == "unknown_collab_session" else exc.code
                response = JSONResponse({"code": code, "details": {}}, status_code=409)
                sensitive = True
            except HTTPException as exc:
                if isinstance(exc.detail, dict) and "code" in exc.detail:
                    return JSONResponse(exc.detail, status_code=exc.status_code,
                                        headers={"Cache-Control": "no-store"})
                if sensitive:
                    exc.headers = {**(exc.headers or {}), "Cache-Control": "no-store"}
                raise
            except RequestValidationError:
                if not sensitive:
                    raise
                # Do not echo arbitrary submitted tokens or invalid lease bodies.
                return JSONResponse({"code": "invalid_request", "details": {}},
                                    status_code=422, headers={"Cache-Control": "no-store"})
            if sensitive:
                response.headers["Cache-Control"] = "no-store"
            return response

        return handler

    async def handle(self, scope, receive, send) -> None:
        if self.methods and scope["method"] not in self.methods:
            await JSONResponse({"code": "method_not_allowed", "details": {}}, status_code=405,
                               headers={"Cache-Control": "no-store",
                                        "Allow": ", ".join(sorted(self.methods))})(scope, receive, send)
            return
        await super().handle(scope, receive, send)


async def bind_http_editor_inventory(request: Request, ctx: Ctx) -> None:
    # Resolve only an already-resident Ctx. The callback reads the shared legacy
    # browser inventory at cutover time, not just its initial hello/selection.
    from ..ws.routes import bind_editor_inventory

    bind_editor_inventory(request.app.state, ctx)


router = APIRouter(prefix="/sync", tags=["sync"], route_class=FencedRoute,
                   dependencies=[Depends(bind_http_editor_inventory)])
# Only mounted below /api/projects/{project_id}; never a global profiles bypass.
remote_router = APIRouter(prefix="/remote", tags=["remote-project"], route_class=FencedRoute,
                          dependencies=[Depends(bind_http_editor_inventory)])


def activated_model(ctx: Any) -> bool:
    """Observe only trusted activation evidence, not identity metadata or clients.

    I6 owns durable evidence. I4 consumes the existing I2 seam and latches a
    positive/unreadable observation; later disappearance cannot revive legacy.
    """
    transitions = ctx.docs.transitions
    active = transitions.activated or getattr(ctx.sync, "_activated_model_seen", False)
    try:
        active = active or ctx.remote_sync_activation() is not None
    except Exception:
        active = True
    if active:
        ctx.sync._activated_model_seen = True
        transitions.observe_activated_model()
    return active


class EditorRegister(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True, hide_input_in_errors=True)
    document_path: str
    client_instance_id: str = Field(min_length=1, max_length=200)
    backend_epoch: str
    expected_binding_generation: StrictInt
    sid: str | None = None


class EditorHeartbeat(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True, hide_input_in_errors=True)
    backend_epoch: str
    expected_binding_generation: StrictInt
    client_instance_id: str | None = None
    document_path: str | None = None
    sid: str | None = None
    dirty: bool | None = None
    version: StrictInt | None = None
    last_saved_version: StrictInt | None = None


class EditorMaterialization(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True, hide_input_in_errors=True)
    backend_epoch: str
    expected_binding_generation: StrictInt
    document_path: str
    client_instance_id: str
    sid: str
    version: StrictInt
    revision: StrictInt


class EditorMaterializationConfirm(EditorMaterialization):
    proof_id: str = Field(min_length=1, max_length=128)
    content: str


def document_path(ctx: Any, path: str) -> str:
    try:
        return ctx.docs.relative(ctx.docs.resolve(path))
    except PathOutsideProject as exc:
        raise HTTPException(403, "Invalid document path.") from exc
    except ValueError as exc:
        raise HTTPException(400, "Invalid document path.") from exc


def _disabled_commit_published(ctx: Any, identity: Any, url: str) -> bool:
    """Did a completed disabled (non-Enable) change publish the current (ID, R)?

    VS-188: so did an explicit recovery whose sync-off target is exactly the
    current (ID, R, URL) — a recovered record is never activation evidence.
    """
    from ..sync.transitions import (ENABLE_JOURNAL_VERSION, completed_activation,
                                    read_binding_change_journals)
    try:
        records = read_binding_change_journals(ctx.paths.root)
        record = completed_activation(records, persistent_id=identity.id,
                                      identity_revision=identity.revision)
    except Exception:
        return False
    if any(row["phase"] == "recovered" and row["recovery"]["to"] == {
            "persistent_id": identity.id, "identity_revision": identity.revision,
            "url": url, "enabled": False} for row in records):
        return True
    return (record is not None and record["version"] != ENABLE_JOURNAL_VERSION
            and record["commit"].get("enabled") is False and record["commit"].get("url") == url)


@remote_router.get("")
async def get_remote(request: Request, ctx: Ctx) -> dict[str, Any]:
    """Local observation only; no selection, activation, Verify or connection."""
    active = activated_model(ctx)
    identity = ctx.config.project_identity
    scope = request.state.project_scope
    try:
        fingerprint = hashlib.sha256(ctx.paths.config_file.read_bytes()).hexdigest()
    except OSError:
        raise HTTPException(503, "Remote metadata unavailable.") from None
    status = ctx.sync.status()
    state = status["state"]
    try:
        url = normalise_url(ctx.config.sync.url) if ctx.config.sync.url else ""
    except ValueError:
        raise HTTPException(503, "Remote metadata unavailable.") from None
    if not active and (ctx.config.sync.url or ctx.config.sync.space):
        state = "migration_required"
    change = getattr(ctx.docs.transitions, "_change", None)
    change_state = ctx.docs.transitions.change_status(change.change_id) if change is not None else None
    if ctx.docs.transitions.binding_quiescing:
        state = "blocked" if change_state["state"] in {"blocked", "paused"} else "quiescing"
        reason = change_state["reason"]
    else:
        # I12: a runner's fixed typed stop code (recovery_required, *_mismatch…).
        reason = status["message"] if active and state in {"blocked", "unauthorised"} else None
    if (state == "blocked" and reason == "activation_unavailable" and identity is not None
            and ctx.config.sync.enabled is False and _disabled_commit_published(ctx, identity, url)):
        # VS-186: after a restart the fail-closed start classifier (core/context.py)
        # reports a root whose current (ID, R) came from a completed disabled I6
        # commit as activation_unavailable. That is a deliberate, settled disable,
        # not damage: display it as such. Read-only; the runner stays refused.
        state, reason = "disabled", None
    # VS-188: what an explicit recovery would do. Classification only — GET
    # never writes a journal, config, private row or checkpoint.
    recovery = None
    try:
        plan = _recovery_plan(ctx)[0]
        recovery = plan.dto() if plan is not None else None
    except Exception:
        recovery = None
    if recovery is not None and not ctx.docs.transitions.binding_quiescing:
        state, reason = "blocked", "recovery_required"
    elif (identity is None and ctx.config.sync.space and state in {"blocked", "disabled"}
            and reason in {None, "activation_unavailable"} and active
            and _legacy_settled(ctx)):
        # VS-188: a legacy root whose interrupted migration was recovered keeps
        # its old settings, inert; it is offered the migration again.
        state, reason = "migration_required", None
    # I13: the UI shows which global profile and catalog incarnation this root
    # is bound to. A local read of the private store only; a row that does not
    # exactly match the project's current ID/URL/revision is not reported, and
    # an unreadable store is reported as no binding (Preview/Enable re-check it).
    profile_id = space_instance_id = None
    if identity is not None:
        try:
            from ..sync.bindings import RemoteBindings
            row = RemoteBindings().read().get(ctx.paths.root)
        except Exception:
            row = None
        if (row is not None and row.persistent_id == identity.id and row.url == url
                and row.identity_revision == identity.revision):
            profile_id, space_instance_id = row.profile_id, row.space_instance_id
    return {
        "project_id": scope.project_id, "epoch": scope.epoch,
        "backend_epoch": ctx.docs.transitions.backend_epoch,
        "persistent_id": identity.id if identity else None,
        "identity_revision": identity.revision if identity else None,
        "config_fingerprint": fingerprint,
        "url": url, "enabled": ctx.config.sync.enabled,
        "has_token": status["has_token"],
        "profile_id": profile_id, "space_instance_id": space_instance_id,
        "binding_generation": ctx.docs.transitions.generation,
        "state": state,
        "reason": reason,
        "active_rooms": len(ctx.sync._sessions),
        "pending_writes": len(ctx.sync._pending) + len(ctx.sync._live_writes),
        "binding_change": change_state,
        "recovery": recovery,
    }


@remote_router.post("/editors", status_code=201)
async def register_editor(payload: EditorRegister, ctx: Ctx) -> dict[str, Any]:
    activated_model(ctx)
    row = ctx.docs.transitions.register_editor(
        document_path(ctx, payload.document_path), payload.client_instance_id,
        payload.backend_epoch, payload.expected_binding_generation, sid=payload.sid,
    )
    if payload.sid is not None:
        ctx.sync.bind_collab_editor(payload.sid, row["editor_id"])
    if payload.sid is None:
        try:
            # Clean open writes nothing. Only an untouched ordinary lease may
            # receive initial evidence, from the bound server read we return.
            return ctx.docs.transitions.confirm_loaded_server_version(
                row["editor_id"], payload.backend_epoch, payload.expected_binding_generation,
                docs=ctx.docs, document_path=row["document_path"],
                client_instance_id=payload.client_instance_id,
            )
        except FileNotFoundError:
            pass  # New document: no existing server version to acknowledge.
        except (OSError, ValueError):
            raise HTTPException(503, "Remote metadata unavailable.") from None
    return row


def _editor_arguments(ctx: Any, payload: EditorHeartbeat) -> dict[str, Any]:
    args = payload.model_dump()
    if payload.document_path is not None:
        args["document_path"] = document_path(ctx, payload.document_path)
    return args


@remote_router.post("/editors/{editor_id}/heartbeat")
async def heartbeat_editor(editor_id: str, payload: EditorHeartbeat, ctx: Ctx) -> dict[str, Any]:
    activated_model(ctx)
    return ctx.docs.transitions.heartbeat_editor(editor_id, **_editor_arguments(ctx, payload))


@remote_router.delete("/editors/{editor_id}")
async def close_editor(editor_id: str, payload: EditorHeartbeat, ctx: Ctx) -> dict[str, Any]:
    activated_model(ctx)
    return ctx.docs.transitions.close_editor(editor_id, **_editor_arguments(ctx, payload))


@remote_router.post("/editors/{editor_id}/materialization")
async def editor_materialization(editor_id: str, payload: EditorMaterialization, ctx: Ctx) -> dict[str, Any]:
    activated_model(ctx)
    args = payload.model_dump()
    args["document_path"] = document_path(ctx, payload.document_path)
    return ctx.sync.materialization(editor_id, **args)


@remote_router.post("/editors/{editor_id}/materialization/confirm")
async def confirm_editor_materialization(
    editor_id: str, payload: EditorMaterializationConfirm, ctx: Ctx,
) -> dict[str, Any]:
    activated_model(ctx)
    args = payload.model_dump()
    args["document_path"] = document_path(ctx, payload.document_path)
    return ctx.sync.materialization(editor_id, **args)


class RemotePrepare(BaseModel):
    """Read-only intention; no catalog identity supplied by a browser is trusted."""

    model_config = ConfigDict(extra="forbid", strict=True, hide_input_in_errors=True)
    # 0 only for migrate: a legacy root has no project_identity yet (I15).
    expected_revision: StrictInt = Field(ge=0)
    config_fingerprint: str = Field(pattern=r"^[0-9a-f]{64}$")
    persistent_id: str
    operation: Literal["edit_id", "select", "disable", "unbind", "migrate"]
    profile_id: str | None = None
    url: str | None = None
    catalog_id: str | None = None
    catalog_instance_id: str | None = None
    migration_plan_id: str | None = None
    backend_epoch: str | None = None

    @field_validator("persistent_id")
    @classmethod
    def valid_id(cls, value: str) -> str:
        return ProjectIdentity.canonical_id(value)

    @model_validator(mode="after")
    def identity_revision(self) -> RemotePrepare:
        if self.operation != "migrate" and self.expected_revision < 1:
            raise ValueError("A project identity revision is required")
        return self


class RemoteQuiesce(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True, hide_input_in_errors=True)
    expected_generation: StrictInt


class RemoteEditorAck(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True, hide_input_in_errors=True)
    editor_id: str = Field(min_length=1, max_length=256)
    old_generation: StrictInt
    last_saved_version: StrictInt


class RemoteConfirmation(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True, hide_input_in_errors=True)
    url: str
    persistent_id: str


class RemoteCommit(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True, hide_input_in_errors=True)
    # 0 matches only a migrate change prepared from a root without identity.
    expected_revision: StrictInt = Field(ge=0)
    expected_generation: StrictInt
    config_fingerprint: str = Field(pattern=r"^[0-9a-f]{64}$")
    confirmation: RemoteConfirmation


def _remote_refusal(code: str, status: int = 409) -> None:
    raise HTTPException(status, {"code": code, "details": {}},
                        headers={"Cache-Control": "no-store"})


def _prepare_observation(ctx: Any, *, legacy: bool = False) -> dict[str, Any]:
    """Read owning evidence, never create storage, look up a key or connect (§3.4).

    A copy of this observation is captured by prepare and rechecked at every
    later phase. In particular in-memory config alone is not a CAS fingerprint.
    ``legacy`` is the I15 migrate observation of a not-yet-migrated root: its
    project_identity may be absent (revision 0), its raw legacy URL/selector
    are compared byte-exactly and it must hold no new-model private row.
    """
    import yaml
    from ..core.paths import user_config_dir
    from ..core.project_scope import canonical_root
    from ..sync.bindings import RemoteBindings
    from ..sync.profiles import RemoteProfiles

    try:
        home = user_config_dir()
        if home != getattr(ctx.sync, "_remote_home", home):
            raise ValueError
        content = ctx.paths.config_file.read_bytes()
        raw = yaml.load(content.decode("utf-8"), Loader=_PlanConfigLoader)
        if not isinstance(raw, dict) or not isinstance(raw.get("sync", {}), dict):
            raise ValueError
        if legacy:
            return _legacy_observation(ctx, raw, content, home)
        identity = ProjectIdentity.model_validate(raw.get("project_identity"))
        sync = raw.get("sync", {})
        url = normalise_url(sync.get("url")) if sync.get("url") else ""
        if (identity != ctx.config.project_identity or url != ctx.config.sync.url
                or sync.get("enabled", False) != ctx.config.sync.enabled
                or sync.get("space", "") != ctx.config.sync.space):
            raise ValueError
        private = RemoteBindings().read()
        row = private.get(ctx.paths.root)
        profile = None
        credential = None
        if row is not None:
            selected = RemoteProfiles().get(row.profile_id)
            if (row.url != url or row.persistent_id != identity.id
                    or row.identity_revision != identity.revision or selected.url != row.url):
                raise ValueError
            profile = selected.model_dump()
            credential = {
                "revision": credentials.profile_token_revision(row.profile_id, row.url),
                "source": credentials.profile_token_source(row.profile_id, row.url),
            }
        return {
            "canonical_root": str(canonical_root(ctx.paths.root)),
            "personal_home": str(home),
            "backend_epoch": ctx.docs.transitions.backend_epoch,
            "identity_revision": identity.revision,
            "persistent_id": identity.id,
            "url": url,
            "config_fingerprint": hashlib.sha256(content).hexdigest(),
            "enabled": ctx.config.sync.enabled,
            "private_fingerprint": private.fingerprint,
            "binding": row.model_dump() if row else None,
            "profile": profile, "credential": credential,
        }
    except Exception:
        _remote_refusal("stale_binding", 409)


def _legacy_observation(ctx: Any, raw: dict, content: bytes, home: Any) -> dict[str, Any]:
    """I15: the same CAS components for a legacy root; raises on any drift."""
    from ..core.project_scope import canonical_root
    from ..sync.bindings import RemoteBindings

    identity = (None if raw.get("project_identity") is None
                else ProjectIdentity.model_validate(raw["project_identity"]))
    sync = raw.get("sync", {})
    if (identity != ctx.config.project_identity
            or sync.get("url", "") != ctx.config.sync.url
            or sync.get("enabled", False) != ctx.config.sync.enabled
            or sync.get("space", "") != ctx.config.sync.space
            or not isinstance(sync.get("space"), str) or not sync["space"]):
        raise ValueError
    url = normalise_url(sync["url"]) if sync.get("url") else ""
    private = RemoteBindings().read()
    if private.get(ctx.paths.root) is not None:
        raise ValueError  # A new-model row on a legacy root is not migration input.
    return {
        "canonical_root": str(canonical_root(ctx.paths.root)),
        "personal_home": str(home),
        "backend_epoch": ctx.docs.transitions.backend_epoch,
        "identity_revision": identity.revision if identity else 0,
        "persistent_id": identity.id if identity else None,
        "url": url,
        "config_fingerprint": hashlib.sha256(content).hexdigest(),
        "enabled": ctx.config.sync.enabled,
        "legacy_selector": check_space_name(sync["space"]),
        "private_fingerprint": private.fingerprint,
        "binding": None, "profile": None, "credential": None,
    }


def _prepare_destination(ctx: Any, payload: RemotePrepare, observed: dict[str, Any],
                         selection: dict | None = None) -> dict[str, Any]:
    # I9 owns authoritative catalog observations. A browser's ID/incarnation
    # is not one; do not probe the v1 server to manufacture a successful plan.
    if payload.operation == "select":
        selection = selection or _select_observation(ctx, payload)
        destination = {"persistent_id": selection["catalog"]["persistent_id"],
                       "url": selection["profile"]["url"], "enabled": False}
        _check_destination_reservation(ctx, observed, destination)
        return destination
    if payload.operation == "migrate":
        _remote_refusal("migration_required", 409)
    if (payload.catalog_id is not None or payload.catalog_instance_id is not None
            or payload.migration_plan_id is not None):
        _remote_refusal("invalid_request", 422)
    binding = observed["binding"]
    if payload.profile_id is not None and (binding is None or payload.profile_id != binding["profile_id"]):
        _remote_refusal("stale_binding")
    if payload.url is not None:
        try:
            matches = (normalise_url(payload.url) if payload.url else "") == observed["url"]
        except ValueError:
            matches = False
        if not matches:
            _remote_refusal("stale_binding")
    if payload.operation != "edit_id" and payload.persistent_id != observed["persistent_id"]:
        _remote_refusal("stale_binding")
    destination = {"persistent_id": payload.persistent_id,
                   "url": "" if payload.operation == "unbind" else observed["url"],
                   "enabled": False}
    # Advisory read of the SAME app-owned registry; prepare never reserves and
    # does not release an incumbent. I6/Enable must perform actual admission.
    _check_destination_reservation(ctx, observed, destination)
    return destination


def _check_destination_reservation(ctx, observed, destination):
    owner = getattr(ctx, "remote_reservations", None)
    if owner is not None and destination["url"]:
        with owner._lock:
            incumbent = owner._reservations.get((destination["url"], destination["persistent_id"]))
            if incumbent is not None and str(incumbent.root) != observed["canonical_root"]:
                _remote_refusal("duplicate_local_binding")


def _select_observation(ctx: Any, payload: RemotePrepare,
                        observed: dict[str, Any] | None = None) -> dict[str, Any]:
    """Validate one exact Select observation against live local state (I6/I9).

    Production passes ``observed`` from ``_catalog_select_source`` (the app
    owner's strict-v2 read). The injected ``ctx.remote_select_observer`` stays a
    test/in-process override and takes precedence. No request flag, browser
    catalog identity, v1 fallback or fabrication is ever a source; the profile
    and credential are re-read here, so every call is also a CAS recheck.
    """
    from ..core.application_config import RemoteServerProfile
    from ..sync.bindings import BindingRecord
    from ..sync.profiles import RemoteProfiles
    observer = getattr(ctx, "remote_select_observer", None)
    if observer is None and observed is None:
        _remote_refusal("catalog_unavailable", 503)
    try:
        source = (observer(payload.profile_id, payload.persistent_id)
                  if observer is not None else observed)
        if not isinstance(source, dict) or set(source) != {"profile", "credential", "catalog"}:
            raise ValueError
        profile = RemoteServerProfile.model_validate(source["profile"],
                                                     context={"stored_profile_metadata": True})
        catalog = source["catalog"]
        credential = source["credential"]
        if (profile != RemoteProfiles().get(profile.profile_id)
                or profile.profile_id != payload.profile_id
                or payload.url is not None and payload.url != profile.url
                or not isinstance(catalog, dict)
                or set(catalog) != {"persistent_id", "space_instance_id", "snapshot_seq"}
                or catalog["persistent_id"] != payload.persistent_id
                or payload.catalog_id != payload.persistent_id
                or payload.catalog_instance_id != catalog["space_instance_id"]
                or type(catalog["snapshot_seq"]) is not int or catalog["snapshot_seq"] < 0
                or credential != {
                    "revision": credentials.profile_token_revision(profile.profile_id, profile.url),
                    "source": credentials.profile_token_source(profile.profile_id, profile.url)}
                or credential["source"] not in {"stored", "environment"}):
            raise ValueError
        BindingRecord.for_root(ctx.paths.root, profile=profile,
            identity=ProjectIdentity(id=payload.persistent_id, revision=payload.expected_revision),
            selection_state="selected", space_instance_id=catalog["space_instance_id"])
        if payload.migration_plan_id is not None:
            raise ValueError
        return {"profile": profile.model_dump(), "credential": dict(credential),
                "catalog": dict(catalog)}
    except Exception:
        _remote_refusal("stale_binding")


# Local/browser staleness (the chosen profile, key or exact project no longer
# matches) is a 409; everything else about the Remote read is unavailability.
_SELECT_STALE_CODES = frozenset({
    "profile_not_found", "profile_revision_conflict", "profile_recovery_required",
    "credential_binding_conflict", "credential_required", "invalid_credential",
    "project_not_found", "identity_mismatch", "snapshot_changed", "invalid_id",
    "invalid_request",
})


async def _catalog_select_source(request: Request, ctx: Any, profile_id: str | None,
                                 persistent_id: str) -> dict[str, Any] | None:
    """I9: observe the exact catalog project through the app owner for Select.

    Reached via ``request.app.state.remote_catalog`` (the lifespan holder), never
    app.state.ctx or a new singleton. The profile/credential are captured
    BEFORE the read, so ``_select_observation``'s live re-read detects drift
    during it. The owner's read is strict v2 with a fresh revision/URL-bound
    provider and a lifecycle lease; there is no v1 probe, Create or Enable.
    Returns None when the injected I6 test observer is installed.
    """
    from ..sync.catalog import CatalogError
    from ..sync.profiles import ProfileError
    if getattr(ctx, "remote_select_observer", None) is not None:
        return None
    holder = getattr(request.app.state, "remote_catalog", None)
    if holder is None:
        _remote_refusal("catalog_unavailable", 503)
    if profile_id is None:
        _remote_refusal("stale_binding")
    code = None
    profile = credential = catalog = None
    try:
        profile = holder.get(profile_id)
        credential = {"revision": credentials.profile_token_revision(profile.profile_id, profile.url),
                      "source": credentials.profile_token_source(profile.profile_id, profile.url)}
    except CatalogError as error:
        code = "catalog_unavailable" if error.code == "service_unavailable" else "stale_binding"
    except Exception:
        code = "stale_binding"
    if code is None:
        try:
            catalog = await holder.select_observation(
                profile.profile_id, persistent_id, expected_revision=profile.revision,
            )
        except CatalogError as error:
            code = "stale_binding" if error.code in _SELECT_STALE_CODES else "catalog_unavailable"
        except ProfileError as error:
            code = ("stale_binding" if len(error.args) == 1 and error.args[0] in _SELECT_STALE_CODES
                    else "catalog_unavailable")
        except Exception:
            code = "catalog_unavailable"
    # Refuse outside the handlers: no provider/transport exception is retained.
    if code is not None:
        _remote_refusal(code, 503 if code == "catalog_unavailable" else 409)
    return {"profile": profile.model_dump(), "credential": credential, "catalog": catalog}


def _check_change_observation(ctx: Any, change_id: str) -> None:
    expected = ctx.docs.transitions.expected_change_evidence(change_id)
    legacy = expected.get("operation") == "migrate"
    if _prepare_observation(ctx, legacy=legacy) != expected["observation"]:
        _remote_refusal("stale_binding")


def _bind_change_owners(ctx: Any) -> None:
    """One bound service and one Ctx event stream; no selected-project lookup."""
    transitions = ctx.docs.transitions
    if transitions.binding_quiescing:
        return

    async def drain(change_id: str, timeout: float) -> dict[str, Any]:
        _check_change_observation(ctx, change_id)
        if any(not transitions.room_drain_ready(
                room.sid, room.path, room.editor_id, room.binding_generation,
        ) for room in ctx.sync._sessions.values()):
            return {"drained": False, "fenced": False, "reason": "pending_edits"}
        proof = await ctx.sync.drain_binding_change(change_id, timeout)
        _check_change_observation(ctx, change_id)
        return {"drained": ctx.sync.validate_binding_drain(proof, change_id),
                "fenced": proof.fenced, "reason": proof.reason}

    async def event(body: dict[str, Any]) -> None:
        await ctx.bus.publish(EventType.REMOTE_BINDING_CHANGE, body)

    transitions.set_change_callbacks(
        begin_quiesce=ctx.sync.begin_binding_quiesce, drain=drain,
        cancel_quiesce=ctx.sync.cancel_binding_quiesce, event=event,
        fence_status=lambda: ctx.sync.binding_fenced,
    )


@remote_router.post("/prepare")
async def prepare_remote(payload: RemotePrepare, request: Request, ctx: Ctx) -> dict[str, Any]:
    """Read-only preparation for the separate disabled identity/config commit."""
    activated_model(ctx)
    from ..sync.transitions import journal_unresolved, read_binding_change_journals
    if any(journal_unresolved(row) for row in read_binding_change_journals(ctx.paths.root)):
        _remote_refusal("recovery_required")
    if payload.backend_epoch is not None:
        ctx.docs.transitions._require_epoch(payload.backend_epoch)
    if payload.operation == "migrate":
        # I15: only after an explicit profile command and an exact resolve.
        return _prepare_migration(ctx, payload)
    observed = _prepare_observation(ctx)
    if (observed["identity_revision"] != payload.expected_revision
            or observed["config_fingerprint"] != payload.config_fingerprint):
        _remote_refusal("stale_binding")
    selection = None
    if payload.operation == "select":
        # Local refusals above are cheap and come first; only then the network.
        source = await _catalog_select_source(request, ctx, payload.profile_id, payload.persistent_id)
        if source is not None and _prepare_observation(ctx) != observed:
            _remote_refusal("stale_binding")  # Local state moved during the read.
        selection = _select_observation(ctx, payload, source)
    destination = _prepare_destination(ctx, payload, observed, selection)
    _bind_change_owners(ctx)
    expected = {"observation": observed, "from": {
                    "persistent_id": observed["persistent_id"], "url": observed["url"]},
                "to": destination, "operation": payload.operation,
                "binding_generation": ctx.docs.transitions.generation,
                "backend_epoch": ctx.docs.transitions.backend_epoch}
    if payload.operation == "select":
        expected["selection"] = selection
    result = ctx.docs.transitions.prepare_change(expected)
    return {**result, "from": expected["from"], "to": destination,
            "plan": {"operation": payload.operation, "enabled": False}, "conflicts": []}


@remote_router.post("/changes/{change_id}/quiesce")
async def quiesce_remote(change_id: str, payload: RemoteQuiesce, ctx: Ctx) -> dict[str, Any]:
    _check_change_observation(ctx, change_id)
    # I5's durable evidence sink is installed without writing during prepare.
    from ..sync.transitions import binding_change_evidence_writer
    expected = ctx.docs.transitions.expected_change_evidence(change_id)
    if not ctx.sync._binding_quiescing:
        ctx.sync.set_binding_evidence_sink(binding_change_evidence_writer(
            ctx.paths.root, change_id, expected,
        ))
    return await ctx.docs.transitions.quiesce_change(change_id, payload.expected_generation)


@remote_router.post("/changes/{change_id}/editor-ack")
async def acknowledge_remote_editor(change_id: str, payload: RemoteEditorAck, ctx: Ctx) -> dict[str, Any]:
    _check_change_observation(ctx, change_id)
    return ctx.docs.transitions.editor_ack(
        change_id, payload.editor_id, payload.old_generation, payload.last_saved_version,
    )


@remote_router.delete("/changes/{change_id}")
async def cancel_remote_change(change_id: str, ctx: Ctx) -> dict[str, Any]:
    # Cancellation never needs a successful CAS read; stale/unavailable config
    # cannot trap the editor in a wait or convert cancellation into a commit.
    from ..sync.transitions import read_binding_change_journals
    if any(row["change_id"] == change_id and row["version"] == 2 and row["phase"] == "completed"
           for row in read_binding_change_journals(ctx.paths.root)):
        _remote_refusal("already_committed")
    result = ctx.docs.transitions.cancel_change(change_id)
    await ctx.bus.publish(EventType.REMOTE_BINDING_CHANGE, result)
    return result


def _commit_selection(ctx, expected, fresh=None):
    """Repeat profile/credential/catalog CAS, including label revision drift.

    ``fresh`` is the I9 catalog observation re-read just before the commit's
    synchronous locks (no network under them). Profile and credential are
    re-read live at every call; the catalog part must equal prepare's exactly.
    """
    from ..sync.profiles import RemoteProfiles
    if expected.get("operation") == "migrate":
        _commit_migration_source(ctx, expected, fresh)
        return
    source = expected.get("selection")
    if source is not None:
        catalog, profile = source["catalog"], source["profile"]
        payload = RemotePrepare(
            expected_revision=expected["observation"]["identity_revision"],
            config_fingerprint=expected["observation"]["config_fingerprint"],
            persistent_id=catalog["persistent_id"], profile_id=profile["profile_id"],
            url=profile["url"], operation="select", catalog_id=catalog["persistent_id"],
            catalog_instance_id=catalog["space_instance_id"],
        )
        if _select_observation(ctx, payload, fresh) != source:
            _remote_refusal("stale_binding")
    else:
        source = expected["observation"]
        profile = source.get("profile")
        if profile is not None:
            if (RemoteProfiles().get(profile["profile_id"]).model_dump() != profile
                    or source["credential"] != {
                        "revision": credentials.profile_token_revision(profile["profile_id"], profile["url"]),
                        "source": credentials.profile_token_source(profile["profile_id"], profile["url"])}):
                _remote_refusal("stale_binding")


def _commit_target_binding(ctx, expected):
    from ..sync.bindings import BindingRecord
    source = expected.get("selection")
    if source is not None:
        return BindingRecord(
            canonical_root=expected["observation"]["canonical_root"],
            profile_id=source["profile"]["profile_id"], url=expected["to"]["url"],
            persistent_id=expected["to"]["persistent_id"],
            identity_revision=expected["observation"]["identity_revision"] + 1,
            selection_state="selected", space_instance_id=source["catalog"]["space_instance_id"],
        )
    old = expected["observation"]["binding"]
    if expected["operation"] == "disable" and old is not None:
        return BindingRecord.model_validate({**dict(old), "selection_state": "selected",
            "identity_revision": expected["observation"]["identity_revision"] + 1})
    return None


def _commit_corpus_matches(ctx, change):
    from ..sync.service import BindingDrainProof
    proof = ctx.sync._binding_proof
    if (type(proof) is not BindingDrainProof or proof.change_id != change.change_id or proof._owner is not ctx.sync
            or proof.generation != change.generation or not proof.fenced
            or proof.target != ctx.sync._binding_target
            or not proof.runner_stopped or not proof.reconciled or proof.reason is not None
            or not ctx.sync._binding_fenced or not ctx.sync._binding_quiescing
            or ctx.sync._binding_draining or ctx.sync._binding_failure or ctx.sync._cleanup_failed
            or ctx.sync._synced or ctx.sync._skip
            or ctx.sync._profile_lease is not None or ctx.sync._reservation_lease is not None
            or ctx.sync._runner is not None or ctx.sync._ws is not None
            or ctx.sync._pending or ctx.sync._pending_paths or ctx.sync._live_writes
            or ctx.sync._sessions or ctx.sync._tasks or ctx.sync._retained_evidence
            or ctx.sync._binding_corpus()[1] != proof.corpus_fingerprint
            or ctx.sync._binding_checkpoint_fingerprint() != proof.checkpoint_fingerprint):
        _remote_refusal("stale_binding")


def _committed_response(request, ctx, change_id, result, expected=None):
    """Historical command result, not a mixture with a later target's status."""
    scope = request.state.project_scope
    binding = result["binding"]
    extra = {}
    if expected is not None and expected.get("operation") == "migrate":
        # I15: what this migration did with the old v1 base (never Enable).
        migration = expected["migration"]
        state = migration["base"]["state"]
        extra = {"migration": {"migration_plan_id": migration["migration_plan_id"],
                               "legacy_selector": migration["legacy_selector"],
                               "base": "imported" if state == "import" else state}}
    return {**extra, "project_id": scope.project_id, "epoch": scope.epoch,
            "backend_epoch": ctx.docs.transitions.backend_epoch,
            **{key: result[key] for key in ("persistent_id", "identity_revision", "url", "enabled",
                                           "config_fingerprint", "binding_generation")},
            "profile_id": binding["profile_id"] if binding else None,
            "space_instance_id": binding["space_instance_id"] if binding else None,
            "state": "disabled", "reason": None, "has_token": False,
            "active_rooms": 0, "pending_writes": 0,
            "binding_change": {"change_id": change_id, "state": "committed"},
            "change_id": change_id, "committed": True}


def _confirmed_completed_result(ctx, payload, completed):
    expected, result = completed["expected"], completed["commit"]
    # A retained receipt is historical evidence, not restart authorization.
    # Never relabel an old-epoch G with the new backend epoch, or rewind RAM G
    # when an earlier same-epoch command is explicitly retried.
    transitions = ctx.docs.transitions
    transitions._require_epoch(expected["backend_epoch"])
    from ..sync.transitions import MAX_BINDING_GENERATION
    if (not 0 <= result["binding_generation"] <= MAX_BINDING_GENERATION
            or transitions.generation < result["binding_generation"]):
        _remote_refusal("recovery_required")
    observation = expected["observation"]
    if (payload.expected_revision != observation["identity_revision"]
            or payload.config_fingerprint != observation["config_fingerprint"]
            or payload.expected_generation != expected["binding_generation"] + 1
            or payload.confirmation.model_dump() != {
                "url": result["url"], "persistent_id": result["persistent_id"]}):
        _remote_refusal("confirmation_required")
    return result


@remote_router.post("/changes/{change_id}/commit")
async def commit_remote_change(change_id: str, payload: RemoteCommit, request: Request,
                               ctx: Ctx) -> dict[str, Any]:
    """I6 disabled-only commit, no network/Enable/rollback/automatic recovery (§7).

    The only awaits are short Ctx/DocStore admission and response publication.
    Lifecycle → config → credential locks cover the synchronous final CAS.
    Explicit same-process retries can finish a matching partial publication;
    restart merely classifies it blocked and never repairs either store.
    """
    import json
    import yaml
    from ..core import config as config_owner
    from ..core.application_config import application_config_transaction
    from ..core.paths import user_config_dir
    from ..sync.bindings import BindingError, RemoteBindings, root_key
    from ..sync.transitions import (BindingChangeError, read_binding_change_journals,
                                    publish_binding_change_commit, next_binding_generation)

    transitions = ctx.docs.transitions
    confirmation = payload.confirmation.model_dump()
    # A completed journal is retained forever, even if another change replaced
    # the RAM slot. This read cannot repair config/private state or adopt G.
    records = read_binding_change_journals(ctx.paths.root)
    completed = next((row for row in records if row["change_id"] == change_id
                      and row["version"] == 2 and row["phase"] == "completed"), None)
    if completed is not None:
        result = _confirmed_completed_result(ctx, payload, completed)
        return _committed_response(request, ctx, change_id, result, completed["expected"])

    change = transitions.require_ready_commit(change_id, **payload.model_dump(exclude={"confirmation"}),
                                               confirmation=confirmation)
    expected = change.expected
    migrate = expected.get("operation") == "migrate"
    fresh = None
    if migrate:
        # I15: re-read the exact legacy resolution BEFORE any lock (as Select).
        fresh = await _migration_catalog_source(request, ctx, expected)
    elif expected.get("selection") is not None:
        # I9: re-observe the exact project BEFORE any lock (the read is async;
        # lifecycle/config/credential locks below are synchronous). A failed
        # read refuses with nothing written; the change stays retryable.
        selected = expected["selection"]
        fresh = await _catalog_select_source(request, ctx, selected["profile"]["profile_id"],
                                             selected["catalog"]["persistent_id"])
    # All fallible I/O below leaves admission fenced; never resurrect old leases.
    async with request.app.state.project_lock:
        # A same-change request may have committed while this one waited for
        # an ordinary settings writer or another command. Return its durable
        # result instead of trying to drain/advance the identity a second time.
        completed = next((row for row in read_binding_change_journals(ctx.paths.root)
                          if row["change_id"] == change_id and row["version"] == 2
                          and row["phase"] == "completed"), None)
        if completed is not None:
            return _committed_response(request, ctx, change_id,
                                       _confirmed_completed_result(ctx, payload, completed),
                                       completed["expected"])
        async with transitions.final_snapshot():
            transitions.require_ready_commit(change_id, **payload.model_dump(exclude={"confirmation"}),
                                               confirmation=confirmation)
            lifecycle = ctx.remote_profile_lifecycle
            if lifecycle is None:
                _remote_refusal("consumer_lifecycle_unavailable", 503)
            with lifecycle._critical():
                with application_config_transaction():
                    with credentials._file_lock:
                        if str(user_config_dir()) != expected["observation"]["personal_home"]:
                            _remote_refusal("stale_binding")
                        _commit_selection(ctx, expected, fresh)
                        _check_destination_reservation(ctx, expected["observation"], expected["to"])
                        _commit_corpus_matches(ctx, change)
                        store = RemoteBindings()
                        snapshot = store.read()
                        target = _commit_target_binding(ctx, expected)
                        previous = next((row for row in read_binding_change_journals(ctx.paths.root)
                                         if row["change_id"] == change_id and row["version"] == 2), None)
                        raw, disk_config, fingerprint = config_owner._project_config_document(ctx.paths)
                        if previous is None:
                            _check_change_observation(ctx, change_id)
                            if migrate:
                                _check_migration_base(ctx, expected)
                            identity = ProjectIdentity(id=expected["to"]["persistent_id"],
                                revision=payload.expected_revision + 1)
                            document = config_owner._project_fields(raw)
                            document["project_identity"] = identity.model_dump(mode="json")
                            document["sync"] = {"enabled": False, "url": expected["to"]["url"]}
                            content = yaml.safe_dump(document, sort_keys=False, allow_unicode=True,
                                                     indent=2).encode("utf-8")
                            roots = {root_key(row.canonical_root): row.model_dump()
                                     for row in snapshot.records}
                            if target is None:
                                roots.pop(root_key(ctx.paths.root), None)
                            else:
                                roots[root_key(ctx.paths.root)] = target.model_dump()
                            private_bytes = (json.dumps({"version": 1, "roots": roots},
                                ensure_ascii=False, indent=2, allow_nan=False) + "\n").encode("utf-8")
                            private_fingerprint = (snapshot.fingerprint if target is None
                                and snapshot.get(ctx.paths.root) is None else hashlib.sha256(private_bytes).hexdigest())
                            result = {"persistent_id": identity.id, "identity_revision": identity.revision,
                                "url": expected["to"]["url"], "enabled": False,
                                "config_fingerprint": hashlib.sha256(content).hexdigest(),
                                "private_fingerprint": private_fingerprint,
                                "binding": target.model_dump() if target else None,
                                "binding_generation": next_binding_generation(transitions.generation)}
                        else:
                            transitions._require_epoch(previous["expected"]["backend_epoch"])
                            result = previous["commit"]
                            identity = ProjectIdentity(id=result["persistent_id"], revision=result["identity_revision"])
                        # A partial-publication retry must finish this exact
                        # fence, never restore an arbitrary durable counter.
                        if result["binding_generation"] != next_binding_generation(transitions.generation):
                            _remote_refusal("stale_binding")
                        if (fingerprint not in {payload.config_fingerprint, result["config_fingerprint"]}
                                or snapshot.fingerprint not in {
                                    expected["observation"]["private_fingerprint"], result["private_fingerprint"]}):
                            _remote_refusal("stale_binding")
                        try:
                            publish_binding_change_commit(ctx.paths.root, change_id, expected, result, "committing")
                            # Journal fsync/staging can be slow, and external
                            # writers do not respect our locks. Repeat EVERY
                            # observation at the actual config commit boundary.
                            def before_replace():
                                transitions.require_ready_commit(change_id,
                                    **payload.model_dump(exclude={"confirmation"}), confirmation=confirmation)
                                _commit_corpus_matches(ctx, change)
                                _commit_selection(ctx, expected, fresh)
                                _check_destination_reservation(ctx, expected["observation"], expected["to"])
                                if migrate and previous is None:
                                    _check_migration_base(ctx, expected)
                                if store.read().fingerprint != snapshot.fingerprint:
                                    raise BindingChangeError("stale_binding")
                            before_replace()
                            if fingerprint == payload.config_fingerprint:
                                committed = config_owner._commit_disabled_identity_in_transaction(
                                    ctx.paths, identity=identity, url=result["url"],
                                    expected_fingerprint=payload.config_fingerprint,
                                    before_replace=before_replace)
                                if committed.fingerprint != result["config_fingerprint"]:
                                    raise BindingChangeError("stale_binding")
                                disk_config = committed.config
                            # Metadata only: never replace Ctx, paths, jobs, MCP or descriptor.
                            ctx.config.project_identity = disk_config.project_identity
                            ctx.config.sync = disk_config.sync
                            transitions.observe_activated_model()
                            ctx.sync._activated_model_seen = True
                            publish_binding_change_commit(ctx.paths.root, change_id, expected, result, "config_committed")
                            if config_owner.read_project_config_snapshot(ctx.paths).fingerprint != result["config_fingerprint"]:
                                raise BindingChangeError("stale_binding")
                            _commit_selection(ctx, expected, fresh)
                            if snapshot.fingerprint != result["private_fingerprint"]:
                                if target is None:
                                    snapshot = store._remove_in_transaction(ctx.paths.root,
                                        expected_fingerprint=expected["observation"]["private_fingerprint"])
                                else:
                                    snapshot = store._save_in_transaction(target,
                                        expected_fingerprint=expected["observation"]["private_fingerprint"])
                            if snapshot.fingerprint != result["private_fingerprint"]:
                                raise BindingError("binding_revision_conflict")
                            if config_owner.read_project_config_snapshot(ctx.paths).fingerprint != result["config_fingerprint"]:
                                raise BindingChangeError("stale_binding")
                            if migrate:
                                # Proven base only; the old v1 file is never touched.
                                _import_migration_base(ctx, expected)
                            publish_binding_change_commit(ctx.paths.root, change_id, expected, result, "completed")
                            transitions.complete_commit(change_id, result["binding_generation"])
                            # Old-target evidence is now durably retained in the
                            # completed record. Shutdown must not rewrite it as
                            # a fresh I5 draining record or erase activation.
                            ctx.sync.complete_disabled_binding_change(change_id)
                        except Exception:
                            change.phase, change.reason = "blocked", "recovery_required"
                            _remote_refusal("recovery_required")
    await ctx.sync._set_status("disabled")
    await ctx.bus.publish(EventType.REMOTE_BINDING_CHANGE, transitions.change_status(change_id))
    if migrate:
        transitions.migration_resolutions.clear()
    return _committed_response(request, ctx, change_id, result, expected)


# --- I15: explicit migration of ONE legacy root (studio-design §5.2/§9, plan I15) ---
#
# GET P/remote/migration (api/project.py) stays the read-only, network-free
# plan. These commands each require that exact plan id + config fingerprint:
# profile (personal store only) -> resolve (read-only catalog) -> prepare
# (operation="migrate") -> quiesce -> commit (disabled, selected). Nothing here
# runs on GET/startup/info/list, opens a WebSocket, enables sync or proves the
# global legacy completeness L.

class MigrationConfirmation(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True, hide_input_in_errors=True)
    url: str


class MigrationProfile(BaseModel):
    """Choose (profile_id) or create (name) the personal profile of the legacy URL."""

    model_config = ConfigDict(extra="forbid", strict=True, hide_input_in_errors=True)
    config_fingerprint: str = Field(pattern=r"^[0-9a-f]{64}$")
    profile_id: str | None = Field(default=None, min_length=1, max_length=64)
    name: str | None = Field(default=None, max_length=1024)
    import_legacy_credential: StrictBool
    confirmation: MigrationConfirmation

    @model_validator(mode="after")
    def one_profile(self) -> MigrationProfile:
        if (self.profile_id is None) == (self.name is None):
            raise ValueError("Exactly one of profile_id and name is required")
        return self


class MigrationResolve(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True, hide_input_in_errors=True)
    profile_id: str = Field(min_length=1, max_length=64)
    expected_profile_revision: StrictInt = Field(ge=1)
    config_fingerprint: str = Field(pattern=r"^[0-9a-f]{64}$")


# Fixed local vocabulary of the personal-store owners (S2/C7), never raw errors.
_MIGRATION_PROFILE_STATUS = {
    "profile_not_found": 404,
    "profile_url_collision": 409, "profile_url_mismatch": 409,
    "profile_revision_conflict": 409, "profile_recovery_required": 409,
    "profile_consumers_not_drained": 409, "profile_in_use": 409,
    "credential_conflict": 409, "credential_required": 409,
    "invalid_profile_metadata": 422, "invalid_server_url": 422,
    # Gate L: the C7 guard has no positive legacy completeness owner yet.
    "consumer_lifecycle_unavailable": 503, "profile_recovery_unavailable": 503,
    "application_store_unavailable": 503, "credential_store_unavailable": 503,
    "profile_store_unavailable": 503, "catalog_unavailable": 503,
}


def _typed_refusal(code: str, status: int, details: dict[str, str] | None = None) -> None:
    raise HTTPException(status, {"code": code, "details": details or {}},
                        headers={"Cache-Control": "no-store"})


def _profile_refusal(code: str, profile_id: str | None = None) -> None:
    status = _MIGRATION_PROFILE_STATUS.get(code)
    if status is None:
        code, status = "profile_store_unavailable", 503
    _typed_refusal(code, status, {"profile_id": profile_id} if profile_id else None)


def _owner_code(error: BaseException) -> str:
    """Sanitize an S2/C7/catalog owner exception to its fixed code only."""
    from ..sync.catalog import CatalogError
    from ..sync.profiles import ProfileError
    if type(error) is ProfileError and len(error.args) == 1 and type(error.args[0]) is str:
        return error.args[0]
    if isinstance(error, credentials.CredentialConflict):
        return "credential_conflict"
    if isinstance(error, credentials.CredentialsError):
        return "credential_store_unavailable"
    if isinstance(error, CatalogError):
        return ("catalog_unavailable" if error.code == "service_unavailable"
                else "profile_store_unavailable")
    return "profile_store_unavailable"


def _migration_plan(ctx: Any, migration_plan_id: str, config_fingerprint: str) -> Any:
    """Re-read the read-only plan; any drift needs a new GET plan (§5.2)."""
    from ..core.project_identity import identity_migration_plan
    plan = identity_migration_plan(ctx.paths)
    if (plan.migration_plan_id != migration_plan_id
            or plan.config_fingerprint != config_fingerprint):
        _remote_refusal("migration_plan_stale")
    if plan.state in {"invalid_config", "migration_conflict"}:
        _remote_refusal(plan.state)  # Malformed/conflicting config: never repaired.
    if activated_model(ctx) and not _legacy_settled(ctx):
        # Already in the new model. VS-188: a migrate recovered back to legacy
        # published no identity, so it may be applied again from scratch.
        _remote_refusal("migration_not_applicable")
    if plan.state != "needs_profile" or not plan.legacy_url or plan.legacy_selector is None:
        _remote_refusal("migration_not_applicable")
    return plan


def _migration_admission(ctx: Any) -> None:
    from ..sync.transitions import journal_unresolved, read_binding_change_journals
    if any(journal_unresolved(row) for row in read_binding_change_journals(ctx.paths.root)):
        _remote_refusal("recovery_required")
    if ctx.docs.transitions.binding_quiescing:
        _remote_refusal("change_in_progress")


def _catalog_holder(request: Request) -> Any:
    holder = getattr(request.app.state, "remote_catalog", None)
    if holder is None or getattr(holder, "runtime", None) is None:
        _remote_refusal("catalog_unavailable", 503)
    return holder


def _credential_observation(profile_id: str, url: str) -> dict[str, Any]:
    return {"revision": credentials.profile_token_revision(profile_id, url),
            "source": credentials.profile_token_source(profile_id, url)}


@remote_router.post("/migration/{migration_plan_id}/profile")
async def migration_profile(migration_plan_id: str, payload: MigrationProfile,
                            request: Request, ctx: Ctx) -> dict[str, Any]:
    """Explicit personal-profile command for the bound legacy URL (§9.1-9.2).

    Reuses the profile with exactly that URL or creates one through the app's
    existing profile owner; optionally imports the legacy per-URL key through
    the existing C7-guarded S2 import, never overwriting a key. Writes neither
    project config nor base, and never reaches the Remote. Idempotent repeat.
    """
    plan = _migration_plan(ctx, migration_plan_id, payload.config_fingerprint)
    if payload.confirmation.url != plan.legacy_url:
        _remote_refusal("confirmation_required")
    holder = _catalog_holder(request)
    code = profile = None
    try:
        if payload.profile_id is not None:
            profile = holder.get(payload.profile_id)
        else:
            profile = holder.get(holder.create(payload.name, plan.legacy_url).profile_id)
    except Exception as error:
        code = _owner_code(error)
    if code == "profile_url_collision":
        # One profile per exact URL: name the existing one, never merge or overwrite.
        existing = None
        try:
            from ..sync.profiles import RemoteProfiles
            existing = next((p.profile_id for p in RemoteProfiles().list()
                             if p.url == plan.legacy_url), None)
        except Exception:
            pass
        _profile_refusal(code, existing)
    if code is not None:
        _profile_refusal(code)
    profile_id = profile.profile_id
    if profile.url != plan.legacy_url:
        _profile_refusal("profile_url_mismatch", profile_id)
    if payload.import_legacy_credential:
        try:
            revision = credentials.profile_token_revision(profile_id, profile.url)
            # Read-only: a different stored key or two legacy spellings conflict.
            if credentials.preflight_legacy_import(profile_id, profile.url,
                                                   expected_revision=revision) != revision:
                # The app owner's own repository and C7 consumer guard (Gate L):
                # an unknown legacy inventory refuses; it is never weakened here.
                runtime = holder.runtime
                runtime._check_mutation_locations()
                runtime._check()
                runtime._repo.import_legacy_credential(profile_id, expected_revision=profile.revision)
        except Exception as error:
            code = _owner_code(error)
        if code is not None:
            _profile_refusal(code, profile_id)
    try:
        profile = holder.get(profile_id)
        source = credentials.profile_token_source(profile_id, profile.url)
    except Exception as error:
        code = _owner_code(error)
    if code is not None:
        _profile_refusal(code, profile_id)
    # The managed project must still be exactly the planned one.
    _migration_plan(ctx, migration_plan_id, payload.config_fingerprint)
    return {"migration_plan_id": migration_plan_id, "profile_id": profile_id,
            "profile_revision": profile.revision, "url": profile.url,
            "credential": source if source in {"stored", "environment"} else "missing"}


def _resolution_code(error: BaseException) -> tuple[str, int]:
    from ..sync.catalog import CatalogError
    from ..sync.profiles import ProfileError
    code = None
    if isinstance(error, CatalogError):
        code = error.code
    elif type(error) is ProfileError and len(error.args) == 1:
        code = error.args[0]
    if code == "legacy_space_not_found":
        return "legacy_space_not_found", 409
    if code == "unauthorised":
        return "remote_unauthorised", 409
    if code in _SELECT_STALE_CODES:
        return "stale_binding", 409
    return "catalog_unavailable", 503


def _migration_base(ctx: Any, *, url: str, catalog_id: str, epoch: str,
                    seq: int) -> dict[str, Any]:
    """Classify the old v1 base: import ONLY with root/URL/mapping/incarnation proof.

    Root: the v1 file is the one keyed by this root. URL: its exact normalized
    URL. Mapping/incarnation: its v1 hello space id equals the resolved catalog
    ID and storage_epoch (both the old UUID) and its cursor is not ahead of
    the current seq. An existing v2 checkpoint is kept, never overwritten.
    """
    from ..core.project_identity import LegacyBaseError, read_legacy_base
    from ..sync.state import SyncStateError, SyncStateV2
    code = legacy = existing = None
    try:
        legacy = read_legacy_base(ctx.paths)
    except LegacyBaseError as error:
        code = error.code
    if code is None:
        try:
            existing = SyncStateV2.load(ctx.paths.root, url=url, persistent_id=catalog_id)
        except SyncStateError:
            code = "checkpoint_unavailable"
    if code is not None:
        _remote_refusal(code)
    if existing is not None:
        state = "existing"
    elif legacy is None:
        state = "none"
    elif (legacy["url"] == url and legacy["space_id"] == catalog_id == epoch
          and legacy["cursor"] <= seq):
        state = "import"
    else:
        state = "unproven"
    return {"state": state,
            "legacy_fingerprint": legacy["fingerprint"] if legacy else None,
            "cursor": legacy["cursor"] if legacy else None,
            "documents": len(legacy["docs"]) if legacy else None,
            "checkpoint_fingerprint": _checkpoint_fingerprint(existing)}


def _migration_conflicts(ctx: Any, observed: dict[str, Any], url: str,
                         catalog_id: str) -> list[dict[str, str]]:
    """Local collisions that forbid apply; reported by resolve, refused by prepare."""
    from ..sync.bindings import RemoteBindings
    conflicts = []
    others = RemoteBindings().read().records
    owner = getattr(ctx, "remote_reservations", None)
    incumbent = None
    if owner is not None:
        with owner._lock:
            incumbent = owner._reservations.get((url, catalog_id))
    if (any(row.canonical_root != observed["canonical_root"]
            and (row.url, row.persistent_id) == (url, catalog_id) for row in others)
            or incumbent is not None and str(incumbent.root) != observed["canonical_root"]):
        conflicts.append({"code": "duplicate_local_binding"})
    return conflicts


@remote_router.post("/migration/{migration_plan_id}/resolve")
async def resolve_migration(migration_plan_id: str, payload: MigrationResolve,
                            request: Request, ctx: Ctx) -> dict[str, Any]:
    """Read-only exact legacy resolution through the app catalog owner (§9.1).

    GET /api/v2/legacy-spaces/resolve + the exact project's snapshot seq only;
    no v1 WS existence probe, Create, config/base/private write or Enable.
    """
    from ..sync.profiles import RemoteProfiles
    transitions = ctx.docs.transitions
    _migration_admission(ctx)
    plan = _migration_plan(ctx, migration_plan_id, payload.config_fingerprint)
    observed = _prepare_observation(ctx, legacy=True)
    if observed["config_fingerprint"] != payload.config_fingerprint:
        _remote_refusal("stale_binding")
    holder = _catalog_holder(request)
    code = profile = credential = None
    try:
        profile = RemoteProfiles().get(payload.profile_id)
        credential = _credential_observation(profile.profile_id, profile.url)
    except Exception as error:
        code = _owner_code(error)
    if code is not None:
        _profile_refusal(code)
    if profile.url != plan.legacy_url:
        _profile_refusal("profile_url_mismatch", profile.profile_id)
    if profile.revision != payload.expected_profile_revision:
        _profile_refusal("profile_revision_conflict", profile.profile_id)
    if credential["source"] not in {"stored", "environment"}:
        _profile_refusal("credential_required", profile.profile_id)
    resolved = project = observation = None
    status = 409
    try:
        resolved = await holder.resolve_legacy(profile.profile_id, plan.legacy_selector,
                                               expected_revision=profile.revision)
        project = resolved.project
        if (resolved.legacy_space_name != plan.legacy_selector
                or project.id != project.storage_epoch):
            # Contract VS-89/90: canonical ID = old UUID = storage_epoch, byte for byte.
            code = "legacy_mapping_mismatch"
        else:
            observation = await holder.select_observation(
                profile.profile_id, project.id, expected_revision=profile.revision)
            if (observation.get("persistent_id") != project.id
                    or observation.get("space_instance_id") != project.storage_epoch
                    or type(observation.get("snapshot_seq")) is not int):
                code = "instance_mismatch"
    except Exception as error:
        code, status = _resolution_code(error)
    if code is not None:
        _remote_refusal(code, status)
    # Local state moved during the network read: discard, never mix observations.
    try:
        moved = (_prepare_observation(ctx, legacy=True) != observed
                 or RemoteProfiles().get(profile.profile_id) != profile
                 or _credential_observation(profile.profile_id, profile.url) != credential)
    except HTTPException:
        raise
    except Exception:
        moved = True
    if moved:
        _remote_refusal("stale_binding")
    _migration_plan(ctx, migration_plan_id, payload.config_fingerprint)
    catalog_id, epoch = project.id, project.storage_epoch
    base = _migration_base(ctx, url=plan.legacy_url, catalog_id=catalog_id, epoch=epoch,
                           seq=observation["snapshot_seq"])
    conflicts = _migration_conflicts(ctx, observed, plan.legacy_url, catalog_id)
    evidence = {
        "canonical_root": observed["canonical_root"], "migration_plan_id": migration_plan_id,
        "observation": observed, "legacy_url": plan.legacy_url,
        "legacy_selector": plan.legacy_selector,
        "selection": {"profile": profile.model_dump(), "credential": credential,
                      "catalog": {"persistent_id": catalog_id, "space_instance_id": epoch,
                                  "snapshot_seq": observation["snapshot_seq"]}},
        "resolution": {"legacy_space_name": resolved.legacy_space_name, "id": catalog_id,
                       "storage_epoch": epoch},
        "base": base, "conflicts": conflicts, "backend_epoch": transitions.backend_epoch,
    }
    published = transitions.migration_resolutions.publish(migration_plan_id, evidence)
    changes = [
        {"kind": "identity", "from": observed["persistent_id"], "to": catalog_id,
         "revision": observed["identity_revision"] + 1},
        {"kind": "remove_legacy_selector", "value": plan.legacy_selector},
        {"kind": "binding", "profile_id": profile.profile_id, "selection_state": "selected",
         "space_instance_id": epoch},
        {"kind": "base", "state": base["state"], "documents": base["documents"],
         "cursor": base["cursor"]},
    ]
    if observed["enabled"]:
        changes.append({"kind": "disable", "enabled": False})
    return {"migration_plan_id": migration_plan_id, "legacy_selector": plan.legacy_selector,
            "catalog_id": catalog_id, "space_instance_id": epoch,
            "display_name": project.display_name, "profile_id": profile.profile_id,
            "profile_revision": profile.revision, "url": profile.url,
            "changes": changes, "conflicts": conflicts, "expires_at": published.expires_at}


def _migration_local_recheck(ctx: Any, evidence: dict[str, Any]) -> bool:
    """Every LOCAL component of a resolution, re-read now (no network)."""
    from ..sync.profiles import RemoteProfiles
    selection = evidence["selection"]
    profile = selection["profile"]
    catalog = selection["catalog"]
    try:
        return (_prepare_observation(ctx, legacy=True) == evidence["observation"]
                and RemoteProfiles().get(profile["profile_id"]).model_dump() == profile
                and _credential_observation(profile["profile_id"], profile["url"])
                == selection["credential"]
                and selection["credential"]["source"] in {"stored", "environment"}
                and _migration_base(ctx, url=profile["url"], catalog_id=catalog["persistent_id"],
                                    epoch=catalog["space_instance_id"],
                                    seq=catalog["snapshot_seq"]) == evidence["base"])
    except HTTPException as error:
        if isinstance(error.detail, dict) and error.detail.get("code") in {
                "legacy_base_invalid", "legacy_base_unavailable", "checkpoint_unavailable"}:
            raise
        return False
    except Exception:
        return False


def _prepare_migration(ctx: Any, payload: RemotePrepare) -> dict[str, Any]:
    """prepare(operation="migrate"): local CAS against the stored resolution only."""
    from ..sync.transitions import MigrationResolutionError, _plain_change_evidence
    transitions = ctx.docs.transitions
    if (payload.migration_plan_id is None or payload.profile_id is None
            or payload.catalog_id is None or payload.catalog_instance_id is None):
        _remote_refusal("invalid_request", 422)
    if transitions.binding_quiescing:
        _remote_refusal("change_in_progress")
    _migration_plan(ctx, payload.migration_plan_id, payload.config_fingerprint)
    observed = _prepare_observation(ctx, legacy=True)
    if (observed["identity_revision"] != payload.expected_revision
            or observed["config_fingerprint"] != payload.config_fingerprint):
        _remote_refusal("stale_binding")
    resolutions = transitions.migration_resolutions
    evidence = _plain_change_evidence(resolutions.get(payload.migration_plan_id).evidence)
    selection = evidence["selection"]
    profile, catalog = selection["profile"], selection["catalog"]
    if (evidence["observation"] != observed
            or evidence["backend_epoch"] != transitions.backend_epoch
            or payload.profile_id != profile["profile_id"]
            or payload.persistent_id != catalog["persistent_id"]
            or payload.catalog_id != catalog["persistent_id"]
            or payload.catalog_instance_id != catalog["space_instance_id"]
            or payload.url is not None and payload.url != profile["url"]
            or not _migration_local_recheck(ctx, evidence)):
        resolutions.discard(payload.migration_plan_id)
        raise MigrationResolutionError("resolution_stale")
    destination = {"persistent_id": catalog["persistent_id"], "url": profile["url"],
                   "enabled": False}
    conflicts = _migration_conflicts(ctx, observed, profile["url"], catalog["persistent_id"])
    if conflicts or evidence["conflicts"]:
        _remote_refusal((conflicts or evidence["conflicts"])[0]["code"])
    _bind_change_owners(ctx)
    expected = {"observation": observed,
                "from": {"persistent_id": observed["persistent_id"], "url": observed["url"]},
                "to": destination, "operation": "migrate",
                "binding_generation": transitions.generation,
                "backend_epoch": transitions.backend_epoch,
                "selection": selection,
                "migration": {"migration_plan_id": payload.migration_plan_id,
                              "legacy_selector": evidence["legacy_selector"],
                              "resolution": evidence["resolution"], "base": evidence["base"]}}
    result = transitions.prepare_change(expected)
    return {**result, "from": expected["from"], "to": destination,
            "plan": {"operation": "migrate", "enabled": False,
                     "migration_plan_id": payload.migration_plan_id,
                     "legacy_selector": evidence["legacy_selector"],
                     "base": evidence["base"]["state"]},
            "conflicts": []}


async def _migration_catalog_source(request: Request, ctx: Any,
                                    expected: Any) -> dict[str, Any]:
    """Commit's fresh exact resolution, read outside every lock (like Select)."""
    holder = getattr(request.app.state, "remote_catalog", None)
    if holder is None:
        _remote_refusal("catalog_unavailable", 503)
    profile = expected["selection"]["profile"]
    code, status, fresh = None, 409, None
    try:
        resolved = await holder.resolve_legacy(
            profile["profile_id"], expected["migration"]["legacy_selector"],
            expected_revision=profile["revision"])
        fresh = {"legacy_space_name": resolved.legacy_space_name, "id": resolved.project.id,
                 "storage_epoch": resolved.project.storage_epoch}
    except Exception as error:
        code, status = _resolution_code(error)
        if code == "legacy_space_not_found":
            code = "stale_binding"  # The mapping prepare relied on is gone.
    if code is not None:
        _remote_refusal(code, status)
    return fresh


def _commit_migration_source(ctx: Any, expected: Any, fresh: Any) -> None:
    """Exact equality with prepare: catalog resolution, profile and credential."""
    from ..sync.profiles import RemoteProfiles
    from ..sync.transitions import _plain_change_evidence
    plain = _plain_change_evidence(expected)
    profile = plain["selection"]["profile"]
    try:
        stale = (fresh != plain["migration"]["resolution"]
                 or RemoteProfiles().get(profile["profile_id"]).model_dump() != profile
                 or _credential_observation(profile["profile_id"], profile["url"])
                 != plain["selection"]["credential"])
    except Exception:
        stale = True
    if stale:
        _remote_refusal("stale_binding")


def _check_migration_base(ctx: Any, expected: Any) -> None:
    """Before the commit point only: the old/new bases are still as resolved."""
    from ..sync.transitions import _plain_change_evidence
    plain = _plain_change_evidence(expected)
    catalog = plain["selection"]["catalog"]
    if _migration_base(ctx, url=plain["to"]["url"], catalog_id=catalog["persistent_id"],
                       epoch=catalog["space_instance_id"],
                       seq=catalog["snapshot_seq"]) != plain["migration"]["base"]:
        _remote_refusal("stale_binding")


def _import_migration_base(ctx: Any, expected: Any) -> None:
    """Copy the PROVEN v1 base into the new namespace, then verify the copy.

    Idempotent for a partial-publication retry: an identical checkpoint is
    accepted, anything else fails (recovery_required). The old v1 file is
    only read; it is never rewritten, moved or deleted.
    """
    from ..core.project_identity import read_legacy_base
    from ..sync.state import Base, SyncNamespace, SyncStateV2
    from ..sync.transitions import BindingChangeError, _plain_change_evidence
    plain = _plain_change_evidence(expected)
    base = plain["migration"]["base"]
    if base["state"] != "import":
        return
    catalog = plain["selection"]["catalog"]
    url, catalog_id = plain["to"]["url"], catalog["persistent_id"]
    legacy = read_legacy_base(ctx.paths)
    if (legacy is None or legacy["fingerprint"] != base["legacy_fingerprint"]
            or legacy["url"] != url or legacy["space_id"] != catalog_id
            or catalog["space_instance_id"] != catalog_id):
        raise BindingChangeError("stale_binding")
    candidate = SyncStateV2(
        SyncNamespace.for_project(ctx.paths.root, url=url, persistent_id=catalog_id),
        catalog["space_instance_id"], legacy["cursor"],
        {path: Base(rev, digest) for path, (rev, digest) in legacy["docs"].items()})
    existing = SyncStateV2.load(ctx.paths.root, url=url, persistent_id=catalog_id)
    if existing is None:
        candidate.save()
    elif existing != candidate:
        raise BindingChangeError("stale_binding")
    if SyncStateV2.load(ctx.paths.root, url=url, persistent_id=catalog_id) != candidate:
        raise BindingChangeError("stale_binding")
    if read_legacy_base(ctx.paths) != legacy:
        raise BindingChangeError("stale_binding")  # The old base must survive intact.


# --- I11: read-only reconcile preview (studio-design §5.2/§6, server-design §3.3) ---

class RemoteReconcilePreview(BaseModel):
    """CAS of the observation the reader is looking at; no target is accepted."""

    model_config = ConfigDict(extra="forbid", strict=True, hide_input_in_errors=True)
    expected_revision: StrictInt = Field(ge=1)
    config_fingerprint: str = Field(pattern=r"^[0-9a-f]{64}$")
    backend_epoch: str | None = None


# Remote/owner codes meaning "the selected project or snapshot moved": 409 and a
# new preview (or a new Select). Everything else is unavailability (503).
_PREVIEW_REFUSALS = {
    "snapshot_changed": (409, "snapshot_changed"),
    "instance_mismatch": (409, "instance_mismatch"),
    "project_not_found": (409, "project_not_found"),
    "identity_mismatch": (409, "instance_mismatch"),
    "pagination_limit": (413, "snapshot_too_large"),
    "snapshot_size_limit": (413, "snapshot_too_large"),
    "payload_too_large": (413, "snapshot_too_large"),
}


def _preview_selected_binding(ctx: Any) -> Any:
    """The exact selected tuple of THIS canonical root, with typed refusals.

    Reads only: binding-change journals, the private bindings store, profiles
    and the in-memory config. Copied roots and same-ID/other-URL tuples are
    refused before any network access; ``_prepare_observation`` then captures
    every remaining nonsecret component for CAS and the fingerprint.
    """
    from ..core.project_scope import canonical_root
    from ..sync.bindings import RemoteBindings
    from ..sync.profiles import RemoteProfiles
    from ..sync.transitions import journal_unresolved, read_binding_change_journals

    if any(journal_unresolved(row) for row in read_binding_change_journals(ctx.paths.root)):
        _remote_refusal("recovery_required")
    if ctx.docs.transitions.binding_quiescing:
        _remote_refusal("change_in_progress")
    code = None
    row = None
    try:
        root = str(canonical_root(ctx.paths.root))
        private = RemoteBindings().read()
        row = private.get(root)
        identity = ctx.config.project_identity
        url = normalise_url(ctx.config.sync.url) if ctx.config.sync.url else ""
        if identity is None:
            code = "binding_required"
        elif any(other.canonical_root != root and other.persistent_id == identity.id
                 and other.url in {url, row.url if row is not None else url}
                 for other in private.records):
            # A copied checkout carries the same portable identity/URL; the
            # binding belongs to the original root and is never shared (§4.3).
            code = "duplicate_local_binding"
        elif row is None or row.space_instance_id is None:
            code = "binding_required"
        elif row.persistent_id == identity.id and (
                row.url != url or RemoteProfiles().get(row.profile_id).url != row.url):
            code = "url_mismatch"
    except Exception:
        code = "stale_binding"
    if code is not None:
        _remote_refusal(code)
    return row


async def _preview_snapshot(request: Request, row: Any, profile: dict[str, Any]) -> Any:
    """One complete consistent snapshot through the app owner: GET only (§6)."""
    from ..sync.catalog import CatalogError
    from ..sync.profiles import ProfileError

    holder = getattr(request.app.state, "remote_catalog", None)
    if holder is None:
        _remote_refusal("catalog_unavailable", 503)
    code = shot = None
    try:
        shot = await holder.snapshot(row.profile_id, row.persistent_id,
                                     expected_revision=profile["revision"],
                                     expected_epoch=row.space_instance_id)
    except CatalogError as error:
        code = error.code
    except ProfileError as error:
        code = error.args[0] if len(error.args) == 1 and isinstance(error.args[0], str) else "unavailable"
    except Exception:
        code = "unavailable"
    if code == "snapshot_changed":
        # The first page is bound to the selected incarnation: tell a replaced
        # project (new Select) from drift between pages (new preview).
        try:
            project = await holder.get_project(row.profile_id, row.persistent_id,
                                               expected_revision=profile["revision"])
            if project.storage_epoch != row.space_instance_id:
                code = "instance_mismatch"
        except Exception:
            pass
    if code is not None:
        status, refusal = _PREVIEW_REFUSALS.get(code, (None, None))
        if status is None:
            status, refusal = ((409, "stale_binding") if code in _SELECT_STALE_CODES
                               else (503, "catalog_unavailable"))
        _remote_refusal(refusal, status)
    return shot


def _checkpoint_fingerprint(checkpoint: Any) -> str | None:
    """Nonsecret digest of one v2 checkpoint's incarnation, cursor and bases."""
    import json
    if checkpoint is None:
        return None
    return hashlib.sha256(json.dumps(
        [checkpoint.space_instance_id, checkpoint.cursor,
         [[p, b.rev, b.hash] for p, b in sorted(checkpoint.docs.items())]],
        ensure_ascii=False).encode("utf-8")).hexdigest()


async def reconcile_preview_evidence(request: Request, ctx: Any, *, expected_revision: int,
                                     config_fingerprint: str,
                                     backend_epoch: str | None = None) -> dict[str, Any]:
    """Compute a FRESH read-only preview observation (I11; reused by I12 recheck).

    Only GETs reach the Remote. No DocStore/config/private/base/checkpoint/queue
    write, room or WS. The returned ``evidence`` is the complete fingerprint
    input; ``counts``/``conflicts`` follow the §16.3 engine policy.
    """
    import json
    from ..sync.state import SyncStateError, SyncStateV2
    from ..sync.transitions import classify_reconcile

    transitions = ctx.docs.transitions
    if backend_epoch is not None:
        transitions._require_epoch(backend_epoch)
    row = _preview_selected_binding(ctx)
    observed = _prepare_observation(ctx)
    generation = transitions.generation
    if (observed["identity_revision"] != expected_revision
            or observed["config_fingerprint"] != config_fingerprint
            or observed["binding"] != row.model_dump() or observed["profile"] is None):
        _remote_refusal("stale_binding")
    _check_destination_reservation(ctx, observed, {"url": row.url, "persistent_id": row.persistent_id})
    shot = await _preview_snapshot(request, row, observed["profile"])
    # Local state moved during the network read: discard, never mix observations.
    if (shot.id != row.persistent_id or shot.storage_epoch != row.space_instance_id
            or _prepare_observation(ctx) != observed or transitions.generation != generation
            or transitions.binding_quiescing):
        _remote_refusal("stale_binding")
    try:
        local, local_fingerprint = ctx.sync._binding_corpus()
    except Exception:
        _remote_refusal("documents_unavailable")
    try:
        checkpoint = SyncStateV2.load(ctx.paths.root, url=row.url, persistent_id=row.persistent_id)
    except SyncStateError:
        _remote_refusal("checkpoint_unavailable")
    bases: dict[str, tuple[int, str | None]] = {}
    checkpoint_fingerprint = _checkpoint_fingerprint(checkpoint)
    if checkpoint is None:
        base_state = "none"
    else:
        # Old bases are preserved, but they only apply to the SAME incarnation
        # whose seq has not gone backwards (§6); otherwise full reconciliation.
        if checkpoint.space_instance_id != shot.storage_epoch:
            base_state = "epoch_mismatch"
        elif checkpoint.cursor > shot.seq:
            base_state = "cursor_rollback"
        else:
            base_state = "matched"
            bases = {p: (b.rev, b.hash) for p, b in checkpoint.docs.items()}
    remote = {d.path: (d.rev, None if d.deleted else d.hash) for d in shot.documents}
    counts, conflicts = classify_reconcile(local, remote, bases)
    remote_digest = hashlib.sha256(json.dumps(
        [[d.path, d.rev, d.hash, d.deleted] for d in shot.documents],
        ensure_ascii=False).encode("utf-8")).hexdigest()
    evidence = {
        "observation": observed, "binding_generation": generation,
        "backend_epoch": transitions.backend_epoch,
        "snapshot": {"id": shot.id, "storage_epoch": shot.storage_epoch, "seq": shot.seq,
                     "documents": remote_digest},
        "local_fingerprint": local_fingerprint,
        "checkpoint": {"state": base_state, "fingerprint": checkpoint_fingerprint},
        "ttl": transitions.reconcile_previews.ttl,
    }
    return {"evidence": evidence, "counts": counts, "conflicts": conflicts,
            "base": base_state, "incarnation": {"space_instance_id": shot.storage_epoch,
                                                "seq": shot.seq}}


async def recheck_reconcile_preview(request: Request, ctx: Any, preview_id: str, fingerprint: str, *,
                                    expected_revision: int, config_fingerprint: str,
                                    backend_epoch: str | None = None) -> Any:
    """I12 Enable gate: re-read everything and reproduce the stored fingerprint.

    Raises 409 ``preview_not_found``/``preview_expired``/``preview_stale`` or the
    same typed refusals as the preview itself; any refusal discards ONLY that
    temporary preview. Success returns the ReconcilePreview without consuming
    it. Still not a lock: Enable keeps its own CAS/conflict handling.
    """
    previews = ctx.docs.transitions.reconcile_previews
    previews.get(preview_id)
    try:
        fresh = await reconcile_preview_evidence(
            request, ctx, expected_revision=expected_revision,
            config_fingerprint=config_fingerprint, backend_epoch=backend_epoch)
    except BaseException:
        previews.discard(preview_id)
        raise
    return previews.require(preview_id, fingerprint, fresh["evidence"])


@remote_router.post("/reconcile-preview")
async def reconcile_preview(payload: RemoteReconcilePreview, request: Request,
                            ctx: Ctx) -> dict[str, Any]:
    """Read-only Preview of the exact selected tuple; never Enable or a lock."""
    fresh = await reconcile_preview_evidence(
        request, ctx, expected_revision=payload.expected_revision,
        config_fingerprint=payload.config_fingerprint, backend_epoch=payload.backend_epoch)
    preview = ctx.docs.transitions.reconcile_previews.publish(
        fresh["evidence"], fresh["counts"], fresh["conflicts"])
    return {**preview.dto(), "base": fresh["base"], "incarnation": fresh["incarnation"]}


# --- I12: separate Enable/catch-up (studio-design §5.2/§7.7, runtime-integration-plan I12) ---

class RemoteEnableConfirmation(BaseModel):
    """The exact target the reader saw in the preview, and the reconcile action."""

    model_config = ConfigDict(extra="forbid", strict=True, hide_input_in_errors=True)
    url: str
    persistent_id: str
    action: Literal["reconcile"]


class RemoteEnable(BaseModel):
    """A current preview plus exact confirmation; no target is accepted from the browser."""

    model_config = ConfigDict(extra="forbid", strict=True, hide_input_in_errors=True)
    expected_revision: StrictInt = Field(ge=1)
    expected_generation: StrictInt = Field(ge=0)
    preview_id: str = Field(pattern=r"^[0-9a-f]{32}$")
    fingerprint: str = Field(pattern=r"^[0-9a-f]{64}$")
    confirmation: RemoteEnableConfirmation
    config_fingerprint: str | None = Field(default=None, pattern=r"^[0-9a-f]{64}$")
    backend_epoch: str | None = None


class _EnableRefused(Exception):
    """A typed refusal from inside the synchronous publication section."""

    def __init__(self, code: str, status: int = 409) -> None:
        super().__init__(code)
        self.code, self.status = code, status


_VERIFY_REFUSALS = {"unauthorised": (409, "unauthorised"),
                    "incompatible": (409, "protocol_unavailable"),
                    "insecure_blocked": (409, "insecure_blocked")}


async def _enable_verified_account(request: Request, profile: dict[str, Any]) -> str:
    """(c) Verify the exact profile revision via the app owner; return me.user.id.

    GET /healthz + /api/v2/me only. The account is recorded in the Enable
    journal and every hello (also after restart) must present it (§6).
    """
    from ..sync.catalog import CatalogError
    from ..sync.profiles import ProfileError

    holder = getattr(request.app.state, "remote_catalog", None)
    if holder is None:
        _remote_refusal("catalog_unavailable", 503)
    code, status, verified = None, 409, None
    try:
        verified = await holder.verify(profile["profile_id"], expected_revision=profile["revision"])
    except CatalogError as error:
        code = "stale_binding" if error.code in _SELECT_STALE_CODES else "catalog_unavailable"
    except ProfileError as error:
        code = ("stale_binding" if len(error.args) == 1 and error.args[0] in _SELECT_STALE_CODES
                else "catalog_unavailable")
    except Exception:
        code = "catalog_unavailable"
    if code is None:
        account = getattr(getattr(verified, "user", None), "id", None)
        if (getattr(verified, "state", None) == "valid" and verified.wire_protocol == 2
                and verified.catalog_version == 2 and verified.error_code is None
                and isinstance(account, str) and 0 < len(account) <= 256):
            return account
        status, code = _VERIFY_REFUSALS.get(getattr(verified, "state", None),
                                            (503, "catalog_unavailable"))
    # Refuse outside the handlers: no provider/transport exception is retained.
    _remote_refusal(code, 503 if code == "catalog_unavailable" else status)


def _publish_enable(ctx: Any, preview: Any, account: str, fence: Any, owner: Any) -> dict[str, Any]:
    """Steps (d)–(i), synchronously, under project → snapshot → lifecycle → config → credential.

    No await/network. Before the project config replace nothing is published:
    a refusal settles the intent as ``aborted`` and releases only this
    reservation. After it, every failure is blocked ``recovery_required``:
    no rollback, retry or reset (studio-design §7.5/§7.7).
    """
    import json
    from uuid import uuid4

    import yaml
    from ..core import config as config_owner
    from ..core.paths import user_config_dir
    from ..sync.bindings import BindingRecord, RemoteBindings, root_key
    from ..sync.reservations import ReservationError
    from ..sync.state import SyncStateV2
    from ..sync.transitions import (_plain_preview_evidence, journal_unresolved, next_binding_generation,
                                    publish_enable_journal, read_binding_change_journals)

    transitions = ctx.docs.transitions
    evidence = preview.evidence
    observation = _plain_preview_evidence(evidence["observation"])
    seen = evidence["snapshot"]
    row = BindingRecord.model_validate(dict(observation["binding"]))
    root = observation["canonical_root"]
    destination = (row.url, row.persistent_id)

    def local_cas(own_change: str | None) -> None:
        """Every LOCAL component the preview observed, re-read now (no network)."""
        try:
            stale = (any(journal_unresolved(r) and r["change_id"] != own_change
                         for r in read_binding_change_journals(ctx.paths.root))
                     or transitions.binding_quiescing
                     or transitions.generation != evidence["binding_generation"]
                     or transitions.backend_epoch != evidence["backend_epoch"]
                     or str(user_config_dir()) != observation["personal_home"]
                     or (ctx.config.sync.enabled and own_change is None))
            if not stale:
                # Config bytes, R, private store, profile revision and credential.
                stale = _plain_preview_evidence(_prepare_observation(ctx)) != observation
            if not stale:
                checkpoint = SyncStateV2.load(ctx.paths.root, url=row.url, persistent_id=row.persistent_id)
                stale = (ctx.sync._binding_corpus()[1] != evidence["local_fingerprint"]
                         or _checkpoint_fingerprint(checkpoint) != evidence["checkpoint"]["fingerprint"])
            others = RemoteBindings().read().records if not stale else ()
        except Exception:
            stale = True
        if stale:
            raise _EnableRefused("preview_stale")
        # One working copy per (URL, ID): no other root may hold this destination.
        if any(other.canonical_root != root and (other.url, other.persistent_id) == destination
               for other in others):
            raise _EnableRefused("duplicate_local_binding")

    def reserved(lease: object) -> None:
        with owner._lock:
            incumbent = owner._reservations.get(destination)
        if incumbent is None or incumbent.lease is not lease or incumbent.consumer is not ctx.sync:
            raise _EnableRefused("duplicate_local_binding")

    local_cas(None)
    # (d) Reserve: the same consumer the runner uses, so it adopts THIS lease.
    try:
        lease = owner.reserve(root=root, url=row.url, persistent_id=row.persistent_id, consumer=ctx.sync)
    except ReservationError as error:
        if error.code == "duplicate_local_binding":
            raise _EnableRefused("duplicate_local_binding") from None
        raise _EnableRefused("consumer_lifecycle_unavailable", 503) from None
    adopted = lease is getattr(ctx.sync, "_reservation_lease", None)

    def release() -> None:
        if not adopted:
            owner.release(lease)

    change_id = expected = commit = None
    try:
        raw, _, fingerprint = config_owner._project_config_document(ctx.paths)
        if fingerprint != observation["config_fingerprint"]:
            raise _EnableRefused("preview_stale")
        identity = ProjectIdentity(id=row.persistent_id, revision=observation["identity_revision"] + 1)
        document = config_owner._project_fields(raw)
        document["project_identity"] = identity.model_dump(mode="json")
        document["sync"] = {"enabled": True, "url": row.url}
        content = yaml.safe_dump(document, sort_keys=False, allow_unicode=True, indent=2).encode("utf-8")
        confirmed = BindingRecord.model_validate({**row.model_dump(), "selection_state": "confirmed",
                                                  "identity_revision": identity.revision})
        store = RemoteBindings()
        private = store.read()
        if private.fingerprint != observation["private_fingerprint"]:
            raise _EnableRefused("preview_stale")
        roots = {root_key(r.canonical_root): r.model_dump() for r in private.records}
        roots[root_key(ctx.paths.root)] = confirmed.model_dump()
        private_bytes = (json.dumps({"version": 1, "roots": roots}, ensure_ascii=False, indent=2,
                                    allow_nan=False) + "\n").encode("utf-8")
        checkpoint = SyncStateV2.load(ctx.paths.root, url=row.url, persistent_id=row.persistent_id)
        if checkpoint is not None and (checkpoint.space_instance_id != row.space_instance_id
                                       or checkpoint.cursor > seen["seq"]):
            raise _EnableRefused("preview_stale")
        change_id = uuid4().hex
        expected = {"operation": "enable", "observation": observation,
                    "to": {"persistent_id": identity.id, "url": row.url, "enabled": True},
                    "snapshot": {"id": seen["id"], "storage_epoch": seen["storage_epoch"],
                                 "seq": seen["seq"]},
                    "binding_generation": transitions.generation,
                    "backend_epoch": transitions.backend_epoch, "account_id": account}
        commit = {"identity_revision": identity.revision, "persistent_id": identity.id,
                  "url": row.url, "enabled": True,
                  "config_fingerprint": hashlib.sha256(content).hexdigest(),
                  "private_fingerprint": hashlib.sha256(private_bytes).hexdigest(),
                  "binding": confirmed.model_dump(),
                  "binding_generation": next_binding_generation(transitions.generation)}
        # (e) Durable intent BEFORE any publication.
        publish_enable_journal(ctx.paths.root, change_id, expected, commit, "enabling")
    except Exception as error:
        release()
        if (change_id is not None and any(r["change_id"] == change_id for r in _journals_or_empty(ctx))
                and not _abort_enable(ctx, change_id, expected, commit)):
            # The intent became durable although its write reported failure, and
            # it cannot be settled: unresolved, i.e. blocked recovery_required.
            raise _EnableRefused("recovery_required") from None
        if isinstance(error, _EnableRefused):
            raise
        raise _EnableRefused("binding_journal_unavailable", 503) from None

    def abort(code: str, status: int = 409) -> None:
        """Nothing of ours was published: settle the intent, release, refuse."""
        release()
        if not _abort_enable(ctx, change_id, expected, commit):
            raise _EnableRefused("recovery_required")
        raise _EnableRefused(code, status)

    # (f) Project config: R+1, enabled, same URL — the commit point.
    def before_replace() -> None:
        local_cas(change_id)
        reserved(lease)

    try:
        written = config_owner._write_project_document_in_transaction(
            ctx.paths, document, expected_fingerprint=observation["config_fingerprint"],
            before_replace=before_replace)
    except Exception as error:
        try:
            current = config_owner._project_config_bytes(ctx.paths)
            ours = current is not None and hashlib.sha256(current).hexdigest() == commit["config_fingerprint"]
        except Exception:
            ours = None  # Unknown: never guess that nothing was published.
        if ours is False:
            if isinstance(error, _EnableRefused):
                abort(error.code, error.status)
            if getattr(error, "code", None) == "config_revision_conflict":
                abort("preview_stale")
            abort("config_store_unavailable", 503)
        raise _EnableRefused("recovery_required") from None
    try:
        if written != commit["config_fingerprint"]:
            raise ValueError
        published = config_owner.read_project_config_snapshot(ctx.paths)
        if published.fingerprint != commit["config_fingerprint"]:
            raise ValueError
        # Metadata only: never replace Ctx, paths, jobs, MCP or descriptor (as I6).
        ctx.config.project_identity = published.config.project_identity
        ctx.config.sync = published.config.sync
        transitions.observe_activated_model()
        ctx.sync._activated_model_seen = True
        # Enable's own G: old leases/callbacks can no longer save or join.
        if fence.advance_generation() != commit["binding_generation"]:
            raise ValueError
        publish_enable_journal(ctx.paths.root, change_id, expected, commit, "config_committed")
        # (g) Private confirmed binding of the SAME R+1.
        reserved(lease)
        snapshot = store._save_in_transaction(confirmed,
                                              expected_fingerprint=observation["private_fingerprint"])
        if snapshot.fingerprint != commit["private_fingerprint"]:
            raise ValueError
        publish_enable_journal(ctx.paths.root, change_id, expected, commit, "binding_committed")
        # (h) The namespace's checkpoint: a fresh one for this incarnation, or the
        # same-incarnation one the preview's bases came from. save() itself
        # refuses to overwrite a foreign/newer state: never a fresh reset.
        if checkpoint is None:
            SyncStateV2.fresh(ctx.paths.root, url=row.url, persistent_id=row.persistent_id,
                              space_instance_id=row.space_instance_id).save()
        current = SyncStateV2.load(ctx.paths.root, url=row.url, persistent_id=row.persistent_id)
        if (current is None or current.space_instance_id != row.space_instance_id
                or checkpoint is not None and current != checkpoint):
            raise ValueError
        # (i) Only a completed record is activation evidence for the runner.
        publish_enable_journal(ctx.paths.root, change_id, expected, commit, "completed")
    except Exception:
        raise _EnableRefused("recovery_required") from None
    return {"change_id": change_id, "persistent_id": identity.id,
            "identity_revision": identity.revision, "url": row.url, "enabled": True,
            "config_fingerprint": commit["config_fingerprint"],
            "binding_generation": commit["binding_generation"],
            "profile_id": row.profile_id, "space_instance_id": row.space_instance_id}


def _journals_or_empty(ctx: Any) -> tuple:
    from ..sync.transitions import read_binding_change_journals
    try:
        return read_binding_change_journals(ctx.paths.root)
    except Exception:
        return ()


def _abort_enable(ctx: Any, change_id: str, expected: Any, commit: Any) -> bool:
    """Settle a proven-unpublished intent; False leaves it unresolved (fail closed)."""
    from ..sync.transitions import publish_enable_journal
    try:
        publish_enable_journal(ctx.paths.root, change_id, expected, commit, "aborted")
    except Exception:
        return False
    return True


async def enable_remote_binding(request: Request, ctx: Any, payload: RemoteEnable) -> dict[str, Any]:
    """I12 Enable/catch-up, without the HTTP envelope (studio-design §5.2/§7.7).

    Local admission → (b) preview recheck incl. snapshot (GET only) and CAS of
    R/G/config/private/profile/credential → (c) Verify (GET only) → one
    synchronous section: (d) reserve, (e) journal intent, (f) project R+1
    enabled, (g) private confirmed R+1, (h) v2 checkpoint, (i) journal
    completed. Only then, outside every lock, the runner starts: strict hello
    (ID/epoch/account/cursor) and catch-up. Rooms are offered once connected.
    """
    from ..core.application_config import application_config_transaction
    from ..sync.profiles import ProfileError
    from ..sync.transitions import journal_unresolved, read_binding_change_journals

    activated_model(ctx)
    transitions = ctx.docs.transitions
    previews = transitions.reconcile_previews
    if payload.backend_epoch is not None:
        transitions._require_epoch(payload.backend_epoch)
    if any(journal_unresolved(row) for row in read_binding_change_journals(ctx.paths.root)):
        _remote_refusal("recovery_required")
    if transitions.binding_quiescing:
        _remote_refusal("change_in_progress")
    if ctx.config.sync.enabled:
        _remote_refusal("already_enabled")
    preview = previews.get(payload.preview_id)
    evidence = preview.evidence
    observation = evidence["observation"]
    binding = observation.get("binding") or {}
    if (payload.expected_revision != observation["identity_revision"]
            or payload.config_fingerprint not in {None, observation["config_fingerprint"]}
            or payload.expected_generation != evidence["binding_generation"]
            or payload.confirmation.model_dump() != {
                "url": binding.get("url"), "persistent_id": binding.get("persistent_id"),
                "action": "reconcile"}):
        _remote_refusal("confirmation_required")
    transitions._require_generation(payload.expected_generation)
    base = evidence["checkpoint"]["state"]
    if base not in {"none", "matched"}:
        # Another incarnation's/rolled-back checkpoint owns this namespace. It is
        # retained as recovery evidence, never reset into a fresh one (§4.3/§6).
        _remote_refusal("instance_mismatch" if base == "epoch_mismatch" else "cursor_rollback")
    # (b) Re-read everything, the snapshot included; a refusal discards only the preview.
    await recheck_reconcile_preview(
        request, ctx, payload.preview_id, payload.fingerprint,
        expected_revision=payload.expected_revision,
        config_fingerprint=observation["config_fingerprint"], backend_epoch=payload.backend_epoch)
    # (c) The account the hello must present.
    account = await _enable_verified_account(request, dict(observation["profile"]))
    lifecycle, owner = ctx.remote_profile_lifecycle, ctx.remote_reservations
    if lifecycle is None or owner is None:
        _remote_refusal("consumer_lifecycle_unavailable", 503)
    refused = result = None
    try:
        async with request.app.state.project_lock:
            async with transitions.final_snapshot() as fence:
                # A concurrent Enable may have consumed it while this one waited.
                previews.get(payload.preview_id)
                with lifecycle._critical():
                    with application_config_transaction():
                        with credentials._file_lock:
                            result = _publish_enable(ctx, preview, account, fence, owner)
    except _EnableRefused as error:
        refused = error
    except ProfileError:
        refused = _EnableRefused("consumer_lifecycle_unavailable", 503)
    if refused is not None:
        if refused.code == "preview_stale":
            previews.discard(payload.preview_id)
        _remote_refusal(refused.code, refused.status)
    previews.discard(payload.preview_id)
    await ctx.bus.publish(EventType.REMOTE_BINDING_CHANGE, {
        "change_id": result["change_id"], "state": "enabled", "operation": "enable",
        "binding_generation": result["binding_generation"],
        "expected_generation": payload.expected_generation, "fenced": False,
        "required_editor_acks": [], "missing_acks": [], "reason": None})
    try:
        # Runner → strict hello → `sync since cursor`. Never before completion.
        await ctx.sync.reconfigure()
    except Exception:
        await ctx.sync._set_status("blocked", "runner_unavailable")
    status = ctx.sync.status()
    return {**result, "backend_epoch": transitions.backend_epoch,
            "state": status["state"],
            "reason": status["message"] if status["state"] in {"blocked", "unauthorised"} else None,
            "has_token": status["has_token"], "active_rooms": len(ctx.sync._sessions),
            "pending_writes": len(ctx.sync._pending) + len(ctx.sync._live_writes)}


@remote_router.post("/enable")
async def enable_remote(payload: RemoteEnable, request: Request, ctx: Ctx) -> dict[str, Any]:
    """Separate Enable after a current preview and exact confirmation (I12)."""
    result = await enable_remote_binding(request, ctx, payload)
    scope = request.state.project_scope
    return {"project_id": scope.project_id, "epoch": scope.epoch, **result}


# --- VS-188: explicit recovery and checkpoint archive (studio-design §7, plan §3.5) ---
#
# GET/startup only classify (``plan_binding_recovery``). ``POST …/recover``
# settles ONE unresolved record after an exact confirmation of the target ID
# and URL: back to the previous destination with sync off, or — for an I6
# disabled commit already past its commit point — that commit completed. Never
# Enable, Remote I/O, push, cursor reset or journal deletion.
# ``POST …/checkpoint/archive`` renames a checkpoint that Enable refuses
# (another incarnation / rolled-back cursor) and requires a new Preview.


def _recovery_observation(ctx: Any) -> tuple[dict | None, str | None, Any, dict | None]:
    """Disk config tuple + bytes fingerprint, private snapshot and this root's row.

    Read-only, never creates storage. ``None`` tuple = unreadable/invalid config;
    ``None`` snapshot = unreadable private store (both refuse the recovery).
    """
    import yaml
    from ..core import config as config_owner
    from ..sync.bindings import RemoteBindings

    current = fingerprint = None
    try:
        content = config_owner._project_config_bytes(ctx.paths)
        if content is not None:
            fingerprint = hashlib.sha256(content).hexdigest()
            raw = yaml.load(content.decode("utf-8"), Loader=_PlanConfigLoader)
            if not isinstance(raw, dict) or not isinstance(raw.get("sync", {}), dict):
                raise ValueError
            sync = raw.get("sync", {})
            identity = (None if raw.get("project_identity") is None
                        else ProjectIdentity.model_validate(raw["project_identity"]))
            enabled = sync.get("enabled", False)
            if type(enabled) is not bool or "url" in sync and type(sync["url"]) is not str:
                raise ValueError
            current = {"persistent_id": identity.id if identity else None,
                       "identity_revision": identity.revision if identity else 0,
                       "url": normalise_url(sync["url"]) if sync.get("url") else "",
                       "enabled": enabled}
    except Exception:
        current = None
    snapshot = binding = None
    try:
        snapshot = RemoteBindings().read()
        row = snapshot.get(ctx.paths.root)
        binding = row.model_dump() if row is not None else None
    except Exception:
        snapshot = binding = None
    return current, fingerprint, snapshot, binding


def _recovery_plan(ctx: Any) -> tuple[Any, tuple, tuple]:
    """(plan or None, journal records, observation). Raises on unreadable journals."""
    from dataclasses import replace
    from ..sync.transitions import (journal_unresolved, plan_binding_recovery,
                                    read_binding_change_journals)

    records = read_binding_change_journals(ctx.paths.root)
    if not any(journal_unresolved(row) for row in records):
        return None, records, ()
    observed = _recovery_observation(ctx)
    current, fingerprint, snapshot, binding = observed
    plan = plan_binding_recovery(records, config=current, binding=binding,
                                 config_fingerprint=fingerprint,
                                 backend_epoch=ctx.docs.transitions.backend_epoch)
    if plan is not None and plan.available and snapshot is None:
        plan = replace(plan, available=False, code="binding_store_unavailable", kind=None)
    return plan, records, observed


def _legacy_settled(ctx: Any) -> bool:
    """Every record of this root is an interrupted migrate recovered to legacy.

    Such a root never left the legacy model (no identity was published), so
    the migration may be offered again. Any other record — completed, aborted,
    unresolved or unreadable — keeps the fail-closed activated classification.
    """
    from ..sync.transitions import read_binding_change_journals
    try:
        records = read_binding_change_journals(ctx.paths.root)
    except Exception:
        return False
    return bool(records) and all(
        row["phase"] == "recovered" and row["recovery"]["to"]["persistent_id"] is None
        for row in records)


def _settled_target(record: Any) -> dict | None:
    """The sync-off destination a terminal record left, for an idempotent receipt."""
    if record["phase"] == "recovered":
        return dict(record["recovery"]["to"])
    commit = record.get("commit")
    if record["phase"] == "completed" and record["version"] == 2:
        return {"persistent_id": commit["persistent_id"], "url": commit["url"],
                "identity_revision": commit["identity_revision"], "enabled": False}
    if record["phase"] == "aborted":
        observation = record["expected"]["observation"]
        return {"persistent_id": observation["persistent_id"], "url": observation["url"],
                "identity_revision": observation["identity_revision"], "enabled": False}
    return None


class RemoteRecoverConfirmation(BaseModel):
    """The exact sync-off destination the reader saw (GET ``recovery.target``)."""

    model_config = ConfigDict(extra="forbid", strict=True, hide_input_in_errors=True)
    url: str
    persistent_id: str | None
    action: Literal["recover"]


class RemoteRecover(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True, hide_input_in_errors=True)
    change_id: str = Field(pattern=r"^[0-9a-f]{32}$")
    # 0 only for a legacy root without project_identity (interrupted migrate).
    expected_revision: StrictInt = Field(ge=0)
    config_fingerprint: str = Field(pattern=r"^[0-9a-f]{64}$")
    confirmation: RemoteRecoverConfirmation
    backend_epoch: str | None = None


class _RecoveryRefused(Exception):
    def __init__(self, code: str, status: int = 409) -> None:
        super().__init__(code)
        self.code, self.status = code, status


def _write_recovered_config(ctx: Any, recovery: Any, fingerprint: str) -> None:
    """Publish the previous destination with sync off: only identity R and enabled.

    Every other byte-level field of the document (including legacy sync keys)
    is kept; exact-bytes CAS against the observation; no defaults on error.
    """
    from ..core import config as config_owner

    raw, _, current = config_owner._project_config_document(ctx.paths)
    if current != fingerprint:
        raise _RecoveryRefused("stale_binding")
    target = recovery["to"]
    document = config_owner._project_fields(raw)
    if target["persistent_id"] is not None:
        document["project_identity"] = ProjectIdentity(
            id=target["persistent_id"], revision=target["identity_revision"]).model_dump(mode="json")
    document["sync"] = {**(raw.get("sync") or {}), "enabled": False}
    config_owner._write_project_document_in_transaction(ctx.paths, document,
                                                        expected_fingerprint=fingerprint)


def _execute_recovery(ctx: Any, plan: Any) -> None:
    """Synchronous, under project → snapshot → lifecycle → config → credential.

    Each step re-observes disk and is skipped when already done, so a crash at
    any point leaves a record that the same command finishes (idempotent).
    """
    from ..sync.bindings import BindingRecord, RemoteBindings
    from ..sync.transitions import (publish_binding_change_commit, publish_enable_journal,
                                    publish_journal_recovery)

    root, record = ctx.paths.root, plan.record
    if plan.kind == "abort_enable":
        publish_enable_journal(root, plan.change_id, record["expected"], record["commit"], "aborted")
        return
    if plan.kind == "settle":
        publish_journal_recovery(root, plan.change_id, plan.recovery, "recovered")
        return
    store = RemoteBindings()

    def row_of(snapshot: Any) -> dict | None:
        row = snapshot.get(root)
        return row.model_dump() if row is not None else None

    def put_row(snapshot: Any, target: Any) -> Any:
        if target is None:
            return store._remove_in_transaction(root, expected_fingerprint=snapshot.fingerprint)
        return store._save_in_transaction(BindingRecord.model_validate(dict(target)),
                                          expected_fingerprint=snapshot.fingerprint)

    if plan.kind == "complete":
        commit = record["commit"]
        snapshot = store.read()
        if row_of(snapshot) != commit["binding"]:
            snapshot = put_row(snapshot, commit["binding"])
        if row_of(snapshot) != commit["binding"]:
            raise _RecoveryRefused("recovery_conflict")
        if record["expected"].get("operation") == "migrate":
            # The proven v1 base only; an identical checkpoint is accepted, the
            # old v1 file is never touched (I15).
            _import_migration_base(ctx, record["expected"])
        publish_binding_change_commit(root, plan.change_id, record["expected"],
                                      {**commit, "private_fingerprint": snapshot.fingerprint}, "completed")
        return
    recovery = plan.recovery
    if record["phase"] != "recovering":
        publish_journal_recovery(root, plan.change_id, recovery, "recovering")
    current, fingerprint, _, _ = _recovery_observation(ctx)
    if current != recovery["to"]:
        if current != recovery["from"] or fingerprint is None:
            raise _RecoveryRefused("recovery_conflict")
        _write_recovered_config(ctx, recovery, fingerprint)
        if _recovery_observation(ctx)[0] != recovery["to"]:
            raise _RecoveryRefused("recovery_conflict")
    snapshot = store.read()
    if row_of(snapshot) != recovery["binding_to"]:
        if row_of(snapshot) != recovery["binding_from"]:
            raise _RecoveryRefused("recovery_conflict")
        snapshot = put_row(snapshot, recovery["binding_to"])
        if row_of(snapshot) != recovery["binding_to"]:
            raise _RecoveryRefused("recovery_conflict")
    publish_journal_recovery(root, plan.change_id, recovery, "recovered")


def _recovery_runtime_idle(ctx: Any) -> bool:
    """No live drain, transport or room may race the recovery (in-process case)."""
    sync = ctx.sync
    runner = getattr(sync, "_runner", None)
    return (ctx.docs.transitions.recovery_admissible() and not sync._binding_draining
            and (runner is None or runner.done()) and sync._ws is None and not sync._sessions
            and not getattr(sync, "_stopping_service", False))


def _settle_recovered_runtime(ctx: Any, plan: Any, fence: Any) -> None:
    """Retire this Ctx's in-memory fence after a durable recovery; no I/O.

    Equivalent to what a restart forgets: the old target's evidence is kept
    durably in the record, never replayed. A changed identity gets a new G so
    old leases/callbacks cannot save into it.
    """
    from ..core import config as config_owner

    transitions = ctx.docs.transitions
    if plan.kind in {"restore", "complete"}:
        fence.advance_generation()
    transitions.settle_recovered_change()
    published = config_owner.read_project_config_snapshot(ctx.paths)
    # Metadata only: never replace Ctx, paths, jobs, MCP or descriptor (as I6).
    ctx.config.project_identity = published.config.project_identity
    ctx.config.sync = published.config.sync
    sync = ctx.sync
    sync._binding_evidence_sink = None
    sync._binding_proof = None
    sync._binding_quiescing = False
    sync._binding_fenced = False
    sync._binding_target = None
    sync._binding_generation = None
    sync._binding_failure = None
    sync._retained_evidence = []
    sync._pending.clear()
    sync._pending_paths.clear()
    sync._live_writes.clear()
    sync._skip.clear()
    sync._binding_resumed.set()
    sync._activated_model_seen = True
    transitions.observe_activated_model()


def _recovery_receipt(request: Request, ctx: Any, change_id: str, outcome: str) -> dict[str, Any]:
    scope = request.state.project_scope
    current, fingerprint, snapshot, binding = _recovery_observation(ctx)
    if current is None or fingerprint is None:
        _remote_refusal("config_store_unavailable", 503)
    legacy = current["persistent_id"] is None
    return {"project_id": scope.project_id, "epoch": scope.epoch,
            "backend_epoch": ctx.docs.transitions.backend_epoch,
            "change_id": change_id, "outcome": outcome, "recovered": True,
            "persistent_id": current["persistent_id"],
            "identity_revision": None if legacy else current["identity_revision"],
            "url": current["url"], "enabled": current["enabled"],
            "config_fingerprint": fingerprint,
            "binding_generation": ctx.docs.transitions.generation,
            "profile_id": binding["profile_id"] if binding else None,
            "space_instance_id": binding["space_instance_id"] if binding else None,
            "state": "migration_required" if legacy and ctx.config.sync.space else "disabled",
            "reason": None}


@remote_router.post("/recover")
async def recover_remote(payload: RemoteRecover, request: Request, ctx: Ctx) -> dict[str, Any]:
    """Explicit recovery of the root's ONE unresolved binding-change record (VS-188).

    Local only: no Remote request, credential, socket, Enable or push. The
    record is advanced in place and kept. A repeat after success is a receipt.
    """
    from ..core.application_config import application_config_transaction
    from ..sync.transitions import (BindingChangeError, journal_unresolved,
                                    read_binding_change_journals)

    activated_model(ctx)
    transitions = ctx.docs.transitions
    if payload.backend_epoch is not None:
        transitions._require_epoch(payload.backend_epoch)
    confirmation = payload.confirmation.model_dump(exclude={"action"})

    def matches(target: Any) -> bool:
        return {"url": target["url"], "persistent_id": target["persistent_id"]} == confirmation

    try:
        records = read_binding_change_journals(ctx.paths.root)
    except BindingChangeError:
        _remote_refusal("binding_journal_unavailable", 503)
    record = next((row for row in records if row["change_id"] == payload.change_id), None)
    if record is None:
        _remote_refusal("unknown_change")
    settled = _settled_target(record) if not journal_unresolved(record) else None
    if settled is None and not journal_unresolved(record):
        _remote_refusal("unknown_change")  # e.g. a completed Enable: nothing to recover.
    if settled is not None:
        # Idempotent: the record is terminal; nothing is written again.
        if not matches(settled):
            _remote_refusal("confirmation_required")
        outcome = "commit" if record["phase"] == "completed" else "previous"
        return _recovery_receipt(request, ctx, payload.change_id, outcome)
    plan, _, observed = _recovery_plan(ctx)
    current, fingerprint = observed[0], observed[1]
    if current is None or fingerprint is None:
        _remote_refusal("recovery_conflict")
    # The CAS is the exact disk bytes GET showed; its revision is the resident
    # metadata GET reports (after a failed in-process Enable the two may differ).
    shown = ctx.config.project_identity
    if (fingerprint != payload.config_fingerprint
            or payload.expected_revision not in {current["identity_revision"],
                                                 shown.revision if shown is not None else 0}):
        _remote_refusal("stale_binding")
    if plan is None or plan.change_id != payload.change_id:
        _remote_refusal("recovery_ambiguous")
    if not plan.available:
        _remote_refusal(plan.code, 503 if plan.code in {"binding_journal_unavailable",
                                                        "binding_store_unavailable"} else 409)
    if not matches(plan.target):
        _remote_refusal("confirmation_required")
    if not _recovery_runtime_idle(ctx):
        _remote_refusal("change_in_progress")
    lifecycle = ctx.remote_profile_lifecycle
    if lifecycle is None:
        _remote_refusal("consumer_lifecycle_unavailable", 503)
    refused = None
    try:
        async with request.app.state.project_lock:
            async with transitions.final_snapshot() as fence:
                with lifecycle._critical():
                    with application_config_transaction():
                        with credentials._file_lock:
                            # Everything again under the locks: the same plan or nothing.
                            again, _, seen = _recovery_plan(ctx)
                            if (again != plan or seen[1] != payload.config_fingerprint
                                    or not _recovery_runtime_idle(ctx)):
                                raise _RecoveryRefused("stale_binding")
                            try:
                                _execute_recovery(ctx, plan)
                            except _RecoveryRefused:
                                raise
                            except Exception:
                                # The record stays unresolved (or recovering):
                                # the same command can finish it later.
                                raise _RecoveryRefused("recovery_failed", 503) from None
                            _settle_recovered_runtime(ctx, plan, fence)
    except _RecoveryRefused as error:
        refused = error
    if refused is not None:
        _remote_refusal(refused.code, refused.status)
    await ctx.sync._set_status("disabled")
    await ctx.bus.publish(EventType.REMOTE_BINDING_CHANGE, {
        "change_id": payload.change_id, "state": "recovered", "operation": plan.operation,
        "binding_generation": transitions.generation, "fenced": False,
        "required_editor_acks": [], "missing_acks": [], "reason": None})
    return _recovery_receipt(request, ctx, payload.change_id, plan.outcome)


class RemoteArchiveConfirmation(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True, hide_input_in_errors=True)
    url: str
    persistent_id: str
    action: Literal["archive_checkpoint"]


class RemoteArchiveCheckpoint(BaseModel):
    """The preview that showed the conflicting checkpoint, plus exact confirmation."""

    model_config = ConfigDict(extra="forbid", strict=True, hide_input_in_errors=True)
    expected_revision: StrictInt = Field(ge=1)
    expected_generation: StrictInt = Field(ge=0)
    preview_id: str = Field(pattern=r"^[0-9a-f]{32}$")
    fingerprint: str = Field(pattern=r"^[0-9a-f]{64}$")
    confirmation: RemoteArchiveConfirmation
    config_fingerprint: str | None = Field(default=None, pattern=r"^[0-9a-f]{64}$")
    backend_epoch: str | None = None


@remote_router.post("/checkpoint/archive")
async def archive_remote_checkpoint(payload: RemoteArchiveCheckpoint, request: Request,
                                    ctx: Ctx) -> dict[str, Any]:
    """Rename the namespace's conflicting v2 checkpoint; a new Preview is required.

    Only for a preview whose base was ``epoch_mismatch``/``cursor_rollback``
    (Enable refuses those). Local only, never Markdown, never a reset in place:
    the old file is kept beside the namespace under an archive name.
    """
    from ..sync.state import SyncStateError, SyncStateV2
    from ..sync.transitions import (_plain_preview_evidence, journal_unresolved,
                                    read_binding_change_journals)

    activated_model(ctx)
    transitions = ctx.docs.transitions
    previews = transitions.reconcile_previews
    if payload.backend_epoch is not None:
        transitions._require_epoch(payload.backend_epoch)
    if any(journal_unresolved(row) for row in read_binding_change_journals(ctx.paths.root)):
        _remote_refusal("recovery_required")
    if transitions.binding_quiescing:
        _remote_refusal("change_in_progress")
    if ctx.config.sync.enabled:
        _remote_refusal("already_enabled")
    preview = previews.get(payload.preview_id)
    evidence = preview.evidence
    observation = evidence["observation"]
    binding = observation.get("binding") or {}
    if (payload.expected_revision != observation["identity_revision"]
            or payload.config_fingerprint not in {None, observation["config_fingerprint"]}
            or payload.expected_generation != evidence["binding_generation"]
            or payload.confirmation.model_dump() != {
                "url": binding.get("url"), "persistent_id": binding.get("persistent_id"),
                "action": "archive_checkpoint"}):
        _remote_refusal("confirmation_required")
    if payload.fingerprint != preview.fingerprint:
        previews.discard(payload.preview_id)
        _remote_refusal("preview_stale")
    base = evidence["checkpoint"]["state"]
    if base not in {"epoch_mismatch", "cursor_rollback"}:
        _remote_refusal("checkpoint_current")  # The preview stays usable for Enable.
    refused = None
    async with request.app.state.project_lock:
        try:
            previews.get(payload.preview_id)
            plain = _plain_preview_evidence(observation)
            checkpoint = None
            stale = (transitions.binding_quiescing or ctx.config.sync.enabled
                     or transitions.generation != evidence["binding_generation"]
                     or transitions.backend_epoch != evidence["backend_epoch"]
                     or any(journal_unresolved(row)
                            for row in read_binding_change_journals(ctx.paths.root)))
            if not stale:
                stale = _plain_preview_evidence(_prepare_observation(ctx)) != plain
            if not stale:
                checkpoint = SyncStateV2.load(ctx.paths.root, url=binding["url"],
                                              persistent_id=binding["persistent_id"])
                stale = (checkpoint is None or _checkpoint_fingerprint(checkpoint)
                         != evidence["checkpoint"]["fingerprint"])
            if stale:
                refused = ("preview_stale", 409)
            else:
                try:
                    SyncStateV2.archive(checkpoint)
                except SyncStateError:
                    refused = ("checkpoint_unavailable", 503)
        except HTTPException:
            refused = ("preview_stale", 409)
        except MutationGateError as error:
            refused = (error.code, 409)
        except Exception:
            refused = ("checkpoint_unavailable", 503)
    # Single use whatever happened: a new Preview observes the result.
    previews.discard(payload.preview_id)
    if refused is not None:
        _remote_refusal(*refused)
    scope = request.state.project_scope
    return {"project_id": scope.project_id, "epoch": scope.epoch,
            "backend_epoch": transitions.backend_epoch, "archived": True, "base": base,
            "preview_required": True}


class SyncUpdate(BaseModel):
    """A patch: a field left out is left alone."""

    model_config = ConfigDict(extra="forbid")

    enabled: bool | None = None
    url: str | None = None
    space: str | None = None
    token: str | None = None
    """The user's key for the server at ``url``. An empty string forgets the stored one."""


@router.get("")
async def get_sync(ctx: Ctx) -> dict[str, Any]:
    active = activated_model(ctx)
    status = ctx.sync.status()
    if active:
        status = {**status, "space": ctx.config.project_identity.id if ctx.config.project_identity else ""}
    return status


@router.put("")
async def update_sync(payload: SyncUpdate, request: Request, ctx: Ctx) -> dict[str, Any]:
    """Save the settings and reconnect with them.

    Validated whole before anything is stored: an address that does not parse must not leave
    a key filed under it. The address and the space go to the project's ``config.yaml``; the
    key goes to the user's config directory, under the address it is for.
    """
    sent = payload.model_fields_set
    # The lock every other settings write holds (§5.2), so two saves cannot interleave.
    async with request.app.state.project_lock:
        if activated_model(ctx) or ctx.docs.transitions.binding_quiescing:
            # Disable is a guarded transition too, never the old cancel path.
            raise HTTPException(409, {"code": "use_remote_settings", "details": {}},
                                headers={"Cache-Control": "no-store"})
        draft = ctx.config.sync.model_copy()
        if "url" in sent and payload.url is not None:
            draft.url = payload.url.strip()
        if "space" in sent and payload.space is not None:
            draft.space = payload.space.strip()
        if "enabled" in sent and payload.enabled is not None:
            draft.enabled = payload.enabled

        url: str | None = None
        try:
            if draft.url:
                url = normalise_url(draft.url)
            if draft.space:
                draft.space = check_space_name(draft.space)
            if draft.enabled and (url is None or not draft.space):
                raise ValueError("Online sync needs a server address and a space")
        except ValueError as exc:
            raise HTTPException(status_code=400, detail=str(exc)) from exc
        if "token" in sent and payload.token is not None:
            if url is None:
                raise HTTPException(status_code=400, detail="A key is stored for a server address")
            credentials.save_token(url, payload.token.strip() or None)

        if draft != ctx.config.sync:
            ctx.config.sync = draft
            save_config(ctx.paths, ctx.config)
        return await ctx.sync.reconfigure()
