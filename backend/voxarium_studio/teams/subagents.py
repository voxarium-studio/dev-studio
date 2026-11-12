"""Studio-managed, backend-neutral workers within a task (§10.3).

Workers share their parent's checkout and write contract, but never its session ID,
result, context window, or board authority. Lifecycle frames reuse the Tasks UI protocol.
"""

from __future__ import annotations

import asyncio
from contextlib import AsyncExitStack
import json
import math
from dataclasses import dataclass, field, replace
from typing import TYPE_CHECKING, Any

from sqlalchemy import delete, update
from sqlmodel import select

from ..models.base import utcnow
from ..models.subagents import SubagentMessage, TaskSubagent
from ..models.teams import TaskStatus, TeamMember, TeamTask
from .backends.base import AgentRunRequest, get_backend
from .effort_solver import EFFORT_SOLVER_ID
from .effort_solver_runner import EffortSolution, solve_effort
from .tools import SERVER_NAME, build_toolset
from .usage import RunUsage

if TYPE_CHECKING:
    from .orchestrator import TeamOrchestrator
    from .slots import AgentSlotLease

ACTIVE = {"queued", "running"}


@dataclass
class ParentRun:
    request: AgentRunRequest
    lease: AgentSlotLease
    run_id: str
    launches: int = 0
    closing: bool = False
    jobs: dict[str, asyncio.Task] = field(default_factory=dict)
    children: set[str] = field(default_factory=set)
    pending: set[str] = field(default_factory=set)
    lock: asyncio.Lock = field(default_factory=asyncio.Lock)


class SubagentManager:
    def __init__(self, orchestrator: TeamOrchestrator) -> None:
        self.owner = orchestrator
        self.runs: dict[str, ParentRun] = {}

    def open_run(self, request: AgentRunRequest, lease: AgentSlotLease, run_id: str) -> None:
        self.runs[request.task.id] = ParentRun(request, lease, run_id)

    def require_run(self, task_id: str, member_id: str, run_id: str | None) -> ParentRun:
        run = self._run(task_id, member_id)
        if not run_id or run.run_id != run_id:
            raise ValueError("This tool belongs to an expired task run")
        return run

    def _run(self, task_id: str, member_id: str) -> ParentRun:
        run = self.runs.get(task_id)
        if run is None or run.closing or run.request.member.id != member_id:
            raise ValueError("Subagents are available only to the current running task")
        return run

    def has_active(self, task_id: str) -> bool:
        run = self.runs.get(task_id)
        return bool(run and any(not job.done() for job in run.jobs.values()))

    def has_pending(self, task_id: str) -> bool:
        run = self.runs.get(task_id)
        return bool(run and run.pending)

    async def _row(self, task_id: str, subagent_id: str) -> TaskSubagent:
        async with self.owner.db.session() as session:
            row = await session.get(TaskSubagent, subagent_id)
            if row is None or row.task_id != task_id:
                raise ValueError("No such subagent in this task")
            return row

    async def _launch_allowed(self, task_id: str, run: ParentRun) -> None:
        saved = await self.owner._reload(task_id)
        if saved.status != TaskStatus.IN_PROGRESS or saved.result_type is not None:
            raise ValueError("Cannot start subagents after reporting the task result")
        if run.launches >= self.owner.config.teams.max_delegated_tasks_per_task:
            raise ValueError("Subagent launch limit reached for this task run")
        if run.closing:
            raise ValueError("This task run is closing")

    async def _solve_effort(self, run: ParentRun, description: str, prompt: str) -> EffortSolution:
        """Score one assignment with the team's Effort Solver, read fresh each time (§10.11)."""
        async with self.owner.db.session() as session:
            solver = await session.get(TeamMember, f"{run.request.task.team_id}__{EFFORT_SOLVER_ID}")
        if solver is None:
            raise RuntimeError("The built-in Effort Solver is missing. Reload the team and retry.")
        return await solve_effort(
            solver, description, prompt, max_message_bytes=self.owner.config.teams.max_message_bytes,
        )

    async def create(
        self, task_id: str, member_id: str, *, prompt: str, description: str,
        backend: str | None = None, model: str | None = None,
    ) -> dict:
        run = self._run(task_id, member_id)
        if not prompt.strip() or not description.strip():
            raise ValueError("A subagent needs a description and a nonempty prompt")
        if len(prompt) > 100_000 or len(description) > 300:
            raise ValueError("Prompt is limited to 100000 characters; description to 300")
        backend = backend or run.request.member.agent_backend
        if backend != run.request.member.agent_backend and not model:
            raise ValueError("Specify a model when selecting a different backend")
        implementation = get_backend(backend)
        available, reason = implementation.is_available()
        if not available:
            raise ValueError(reason or f"Backend {backend} is unavailable")
        # §10.3, §10.11: Effort Solver, not the parent, chooses a worker's effort from its
        # description and prompt. A launch already known to be refused is not paid for, and
        # the model call stays outside `run.lock`: a slow evaluation must not hold up
        # stopping or closing the run. It is awaited inside the parent's own slot, like the
        # Producer's — taking another would deadlock at capacity. Failure raises: no worker
        # starts on a substituted effort.
        await self._launch_allowed(task_id, run)
        solution = await self._solve_effort(run, description.strip(), prompt)
        spent = {key: int(solution.usage.get(key) or 0) for key in (
            "total_tokens", "total_uncached_tokens", "total_cached_tokens",
            "total_input_tokens", "total_output_tokens", "num_turns",
        )}
        cost = float(solution.usage.get("total_cost_usd") or 0)
        async with run.lock:
            # Checked again: the run may have closed, or parallel spawns used up the limit.
            await self._launch_allowed(task_id, run)
            row = TaskSubagent(
                task_id=task_id, member_id=member_id, description=description.strip(),
                backend=backend, model=model or run.request.model, effort=solution.effort_level,
            )
            # Track ownership before the first cancellable INSERT/publish. A parent
            # stopped during spawn must also close a row whose job never started.
            run.children.add(row.id)
            async with self.owner.db.session() as session:
                session.add(row)
                # The evaluation is the parent's spend, as a Producer's is its chat's: it
                # joins the task totals with the worker it launched, and stays out of the
                # worker's own counters (§10.4).
                if cost or any(spent.values()):
                    await session.exec(update(TeamTask).where(TeamTask.id == task_id).values(
                        total_cost_usd=TeamTask.total_cost_usd + cost,
                        **{key: getattr(TeamTask, key) + value for key, value in spent.items()},
                    ))
            await self._start(run, row, prompt)
        if cost or any(spent.values()):
            await self.owner._publish_task_changed(task_id)
        # §10.3: the four scores travel in this answer only — the transcript keeps the call's
        # result, which is where the spawn row draws them; `TaskSubagent` stores the effort.
        return {**row.model_dump(mode="json"), "effort_solver": {
            "scores": solution.scores.model_dump(), "effort": solution.effort,
            "effort_level": solution.effort_level,
        }}

    async def _start(self, run: ParentRun, row: TaskSubagent, prompt: str) -> None:
        # Persist/publish before submitting, so an immediate stop still has a start row.
        await self._emit(row, "task_started")
        run.launches += 1
        run.pending.add(row.id)
        handle = self.owner.jobs.submit(
            row.id, "subagent", lambda _: self._execute(run, row, prompt), parent_task=row.task_id,
        )
        run.jobs[row.id] = handle.task
        parent = self.owner.jobs.get(row.task_id)
        if parent:
            parent.children.add(row.id)

    async def list(self, task_id: str, member_id: str) -> list[dict]:
        self._run(task_id, member_id)
        async with self.owner.db.session() as session:
            rows = (await session.exec(select(TaskSubagent).where(
                TaskSubagent.task_id == task_id,
            ).order_by(TaskSubagent.created_at, TaskSubagent.id))).all()
        return [row.model_dump(mode="json") for row in rows]

    async def get(self, task_id: str, member_id: str, subagent_id: str) -> dict:
        run = self._run(task_id, member_id)
        row = await self._row(task_id, subagent_id)
        async with self.owner.db.session() as session:
            messages = (await session.exec(select(SubagentMessage).where(
                SubagentMessage.subagent_id == subagent_id,
            ).order_by(SubagentMessage.created_at.desc(), SubagentMessage.id.desc()).limit(50))).all()
        if row.status not in ACTIVE:
            run.pending.discard(row.id)
        return {**row.model_dump(mode="json"), "messages": [
            message.model_dump(mode="json") for message in reversed(messages)
        ]}

    async def wait(
        self, task_id: str, member_id: str, subagent_id: str | None = None, timeout: float = 30,
    ) -> list[dict]:
        run = self._run(task_id, member_id)
        if not math.isfinite(timeout) or not 0 <= timeout <= 60:
            raise ValueError("Timeout must be between 0 and 60 seconds")
        if subagent_id:
            await self._row(task_id, subagent_id)
        ids = [subagent_id] if subagent_id else list(run.jobs)
        jobs = [run.jobs[key] for key in ids if key in run.jobs and not run.jobs[key].done()]
        if jobs and timeout:
            # The parent is idle inside its MCP call. Lending its slot prevents a
            # deadlock even when the shared agent concurrency is one (§10.3).
            async with run.lease.suspended():
                await asyncio.wait(jobs, timeout=timeout)
        return [await self.get(task_id, member_id, key) for key in ids]

    async def stop(self, task_id: str, member_id: str, subagent_id: str) -> dict:
        run = self._run(task_id, member_id)
        row = await self._row(task_id, subagent_id)
        async with run.lock:
            if run.closing or self.runs.get(task_id) is not run:
                raise ValueError("This task run is closing")
            job = run.jobs.get(row.id)
            if job and not job.done():
                if not job.cancelling():
                    job.cancel()
                await asyncio.gather(job, return_exceptions=True)
            await self._finish_if_active(row.id, "Stopped by the parent agent.")
        return await self.get(task_id, member_id, row.id)

    async def send(self, task_id: str, member_id: str, subagent_id: str, message: str) -> dict:
        run = self._run(task_id, member_id)
        if not message.strip() or len(message) > 100_000:
            raise ValueError("Follow-up message must contain 1..100000 characters")
        async with run.lock:
            await self._launch_allowed(task_id, run)
            row = await self._row(task_id, subagent_id)
            if row.status in ACTIVE or (row.id in run.jobs and not run.jobs[row.id].done()):
                raise ValueError("Wait for or stop this subagent before sending a follow-up")
            row.status, row.finished_at = "queued", None
            row.updated_at = utcnow()
            run.children.add(row.id)
            async with self.owner.db.session() as session:
                session.add(row)
            await self._start(run, row, message)
        return row.model_dump(mode="json")

    async def collect_pending(self, task_id: str) -> list[dict]:
        run = self.runs.get(task_id)
        if not run or not run.pending:
            return []
        async with run.lease.suspended():
            jobs = [job for job in run.jobs.values() if not job.done()]
            if jobs:
                await asyncio.gather(*jobs, return_exceptions=True)
        ids = sorted(run.pending)
        return [await self.get(task_id, run.request.member.id, key) for key in ids]

    async def close_run(self, task_id: str) -> None:
        run = self.runs.get(task_id)
        if run is None:
            return
        run.closing = True
        async with run.lock:
            jobs = list(run.jobs.values())
            for job in jobs:
                if not job.done() and not job.cancelling():
                    job.cancel()
            await asyncio.gather(*jobs, return_exceptions=True)
            for key in run.children:
                await self._finish_if_active(key, "Parent run ended.")
        self.runs.pop(task_id, None)

    async def _emit(self, row: TaskSubagent, subtype: str, *, last_tool: str = "") -> None:
        await self.owner.record_message(
            row.task_id, row.member_id, role="system", kind="system", text=subtype,
            data={
                "task_id": row.id, "task_type": "studio_agent", "description": row.description,
                "backend": row.backend, "model": row.model, "effort": row.effort,
                "status": row.status,
                "summary": row.summary, "last_tool_name": last_tool,
                "usage": {"total_tokens": row.total_tokens, "total_cost_usd": row.total_cost_usd,
                          "total_uncached_tokens": row.total_uncached_tokens,
                          "total_cached_tokens": row.total_cached_tokens,
                          "total_input_tokens": row.total_input_tokens,
                          "total_output_tokens": row.total_output_tokens,
                          "tool_uses": row.tool_uses},
            },
        )

    async def _finish_if_active(self, subagent_id: str, reason: str) -> None:
        async with self.owner.db.session() as session:
            row = await session.get(TaskSubagent, subagent_id)
            if row is None or row.status not in ACTIVE:
                return
            row.status, row.summary, row.finished_at = "stopped", reason, utcnow()
            row.updated_at = row.finished_at
            session.add(row)
        await self._emit(row, "task_notification")

    async def recover(self) -> None:
        """A process restart cannot resume worker processes behind the user's back."""
        async with self.owner.db.session() as session:
            rows = (await session.exec(select(TaskSubagent).where(TaskSubagent.status.in_(ACTIVE)))).all()
        for row in rows:
            await self._finish_if_active(row.id, "Studio restarted; worker session stopped.")

    async def delete_for_task(self, session, task_id: str) -> None:
        ids = select(TaskSubagent.id).where(TaskSubagent.task_id == task_id)
        await session.exec(delete(SubagentMessage).where(SubagentMessage.subagent_id.in_(ids)))
        await session.exec(delete(TaskSubagent).where(TaskSubagent.task_id == task_id))

    async def _execute(self, run: ParentRun, row: TaskSubagent, prompt: str) -> None:
        try:
            async with self.owner.jobs.slot("agent"):
                member = run.request.member.model_copy(update={
                    "agent_backend": row.backend, "model": row.model, "effort": row.effort,
                })
                toolset = build_toolset(self.owner, member=member, task=run.request.task, subagent_id=row.id)
                servers = {key: value for key, value in run.request.mcp_servers.items() if key != SERVER_NAME}
                if toolset:
                    servers.update(toolset.mcp_servers)
                allowed = [name for name in run.request.extra_allowed_tools
                           if not name.startswith(f"mcp__{SERVER_NAME}__")]
                if toolset:
                    allowed.extend(toolset.allowed_tools)
                instructions = (
                    f"You are Studio subagent {row.id}, working on part of task {self.owner.display_id(run.request.task)}.\n"
                    f"Assignment: {row.description}\n"
                    "Complete only the delegated assignment. Return your findings, changes, verification and blockers "
                    "in your final response to the parent. Do not call finish_task or change the parent task's status. "
                    "Do not create further subagents. Coordinate edits with the parent: other workers share this checkout.\n"
                    "The following parent instructions supply workspace, permissions and project context; "
                    "their board, delegation, completion and memory-writing (remember, forget) "
                    "instructions apply only to the parent:\n\n"
                    + run.request.system_prompt
                )
                request = replace(
                    run.request, member=member, prompt=prompt, system_prompt=instructions,
                    session_key=row.id, resume_session_id=row.session_id, agent_name=None, images=[],
                    proactive_subagents=False,
                    recovery_prompt=(f"Delegated assignment: {row.description}\nPrevious result: {row.summary}\n\n{prompt}"),
                    model=row.model, effort=row.effort, mcp_servers=servers, extra_allowed_tools=allowed,
                )
                # §10.10: a worker is a fresh use of its parent member, including when
                # its backend differs. Inherited system instructions contain only the
                # skill catalog; required documents and skill bodies, including Studio's,
                # accompany this turn.
                from .required_context import load_required_context
                required = load_required_context(
                    self.owner.config.teams, member, self.owner.skills, self.owner.docs)
                request.extra_allowed_tools = list(dict.fromkeys([
                    *request.extra_allowed_tools, *required.skills.allowed_tools(),
                ]))
                if not row.session_id:
                    request.prompt = required.prepend(request.prompt)
                request.recovery_prompt = required.prepend(request.recovery_prompt)
                row.status, row.summary = "running", ""
                row.updated_at = utcnow()
                async with self.owner.db.session() as session:
                    session.add(row)
                    session.add(SubagentMessage(subagent_id=row.id, kind="user", text=prompt))
                await self._emit(row, "task_progress")
                usage = RunUsage()
                credited = 0
                credited_uncached = credited_cached = 0
                credited_input = credited_output = 0
                credited_cost = 0.0
                last_text, result_text = "", ""
                failed = False
                async with AsyncExitStack() as cleanup:
                    stream = get_backend(row.backend).run(request)
                    # Bundled backends are async generators; plugins may implement
                    # only AsyncIterator. Close explicitly wherever it is supported.
                    close = getattr(stream, "aclose", None)
                    if close is not None:
                        cleanup.push_async_callback(close)
                    async for event in stream:
                        usage.observe(event)
                        if event.kind == "result":
                            usage.finish(event.data)
                            event = replace(event, data={**event.data, **usage.counters()})
                        delta = usage.total - credited
                        uncached_delta = usage.uncached - credited_uncached
                        cached_delta = usage.cached - credited_cached
                        input_delta = usage.input - credited_input
                        output_delta = usage.output - credited_output
                        credited = usage.total
                        credited_uncached, credited_cached = usage.uncached, usage.cached
                        credited_input, credited_output = usage.input, usage.output
                        raw_cost = event.data.get("total_cost_usd")
                        cost = (float(raw_cost) if isinstance(raw_cost, (int, float))
                                and not isinstance(raw_cost, bool) and math.isfinite(raw_cost) and raw_cost >= 0 else None)
                        cost_delta = max(0.0, cost - credited_cost) if cost is not None else 0.0
                        credited_cost += cost_delta
                        turns = max(0, int(event.data.get("num_turns") or 0)) if event.kind == "result" else 0
                        row.total_tokens += delta
                        row.total_uncached_tokens += uncached_delta
                        row.total_cached_tokens += cached_delta
                        row.total_input_tokens += input_delta
                        row.total_output_tokens += output_delta
                        row.total_cost_usd += cost_delta
                        row.num_turns += turns
                        row.updated_at = utcnow()
                        if event.session_id:
                            row.session_id = event.session_id
                        if event.kind == "tool_use":
                            row.tool_uses += 1
                        if event.kind == "text" and event.text:
                            last_text = event.text
                        if event.kind == "error":
                            failed, result_text = True, event.text or "Subagent backend failed"
                        if event.kind == "result":
                            if not event.data.get("continued"):
                                failed = failed or bool(event.data.get("is_error"))
                                result_text = event.text or result_text
                            usage.next_segment()
                            credited, credited_cost = 0, 0.0
                            credited_uncached = credited_cached = 0
                            credited_input = credited_output = 0
                        async with self.owner.db.session() as session:
                            session.add(row)
                            session.add(SubagentMessage(subagent_id=row.id, kind=event.kind, text=event.text, data=event.data))
                            # Atomic increments protect concurrent parent/worker accounting (§10.4).
                            await session.exec(update(TeamTask).where(TeamTask.id == row.task_id).values(
                                total_tokens=TeamTask.total_tokens + delta,
                                total_uncached_tokens=TeamTask.total_uncached_tokens + uncached_delta,
                                total_cached_tokens=TeamTask.total_cached_tokens + cached_delta,
                                total_input_tokens=TeamTask.total_input_tokens + input_delta,
                                total_output_tokens=TeamTask.total_output_tokens + output_delta,
                                total_cost_usd=TeamTask.total_cost_usd + cost_delta,
                                num_turns=TeamTask.num_turns + turns,
                            ))
                        last_tool = str(event.data.get("name") or event.text) if event.kind == "tool_use" else ""
                        await self._emit(row, "task_progress", last_tool=last_tool)
                        if delta or uncached_delta or cached_delta or input_delta or output_delta or cost_delta or turns:
                            await self.owner._publish_task_changed(row.task_id)
                row.status = "failed" if failed else "completed"
                row.summary = result_text or last_text
                row.finished_at = utcnow()
                row.updated_at = row.finished_at
                async with self.owner.db.session() as session:
                    session.add(row)
                await self._emit(row, "task_notification")
        except asyncio.CancelledError:
            await self._finish_if_active(row.id, "Subagent stopped.")
            raise
        except Exception as exc:
            async with self.owner.db.session() as session:
                saved = await session.get(TaskSubagent, row.id)
                if saved:
                    saved.status, saved.summary, saved.finished_at = "failed", str(exc), utcnow()
                    saved.updated_at = saved.finished_at
                    session.add(saved)
            if saved:
                await self._emit(saved, "task_notification")


def results_prompt(results: list[dict]) -> str:
    """Child output is context for the parent to verify, never a task result itself."""
    compact = [{key: row[key] for key in ("id", "description", "status", "summary")} for row in results]
    return ("Your Studio subagents have finished. Review their results, integrate and verify the work, "
            "then finish the parent task. Use get_subagent for their transcripts if needed.\n\n"
            + json.dumps(compact, ensure_ascii=False))
