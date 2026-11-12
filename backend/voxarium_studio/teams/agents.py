"""The roster projected into Claude Code agent definitions (§10.3).

``claude --agent <name>`` is how a task runs as its assignee rather than as a generic
coding agent. The flag resolves a name **only against definitions on disk** — a project's
``.claude/agents/*.md``, the user's own, plugins, built-ins. Agents handed to the CLI
another way are not candidates: ``--agents <json>``, and the Agent SDK's ``agents=``
option that rides the initialize request, both describe *sub*-agents a session may
delegate to, and neither registers a name ``--agent`` will accept. Both were checked
against the bundled CLI (2.1.229) before this module was written.

So a member has to exist as a file, and this is the module that writes it. Two rules
follow from the fact that the directory is the user's, not ours:

**A file we did not write is never touched.** Ownership is a ``voxarium_studio_member`` key in the
frontmatter naming the member it projects (the CLI ignores keys it does not know). A
member whose name collides with a hand-written agent falls back to running without
``--agent``, with a note on the task, rather than overwriting somebody's work.

**A member may opt out entirely.** ``TeamMember.agent`` names an existing agent to run as;
the Studio then writes nothing and only passes the name through. Since full replacement
(2026-11-12) the Studio's prompt is the session's *whole* system prompt — Claude's
``--system-prompt`` discards a ``--agent`` definition's own prompt (checked against the
bundled CLI with a captured request) — so a borrowed agent contributes its frontmatter's
non-prompt effects, while the team context and the member's identity reach it in the run's
system prompt like anywhere else.
"""

from __future__ import annotations

import logging
import re
from pathlib import Path

from ..core.paths import ProjectPaths
from ..core.prompts import AgentPromptsConfig
from ..models.teams import MemberKind, Team, TeamMember
from .producer_identity import is_producer

log = logging.getLogger(__name__)

#: Frontmatter key marking a definition as this Studio's to rewrite and to delete.
OWNER_KEY = "voxarium_studio_member"

_SLUG_STRIP = re.compile(r"[^a-z0-9]+")


def slug_for(member: TeamMember) -> str:
    """The name a member is offered under, both to ``--agent`` and in a terminal.

    The member's own name rather than its id: ``claude --agent sofia`` is what somebody
    would actually type, and the id (``team_default__designer``) is a database key nobody
    should have to know. The id is the fallback for a name that slugs to nothing at all.
    """
    slug = _SLUG_STRIP.sub("-", member.name.strip().lower()).strip("-")
    return slug or _SLUG_STRIP.sub("-", member.id.lower()).strip("-")


def describe(member: TeamMember) -> str:
    suffix = " — a person, not an agent" if member.kind == MemberKind.HUMAN else ""
    return f"{member.name} ({member.role_title}, id {member.id}{suffix})"


def identity_lines(
    *,
    member: TeamMember,
    team: Team | None,
    manager: TeamMember | None,
    reports: list[TeamMember],
    peers: list[TeamMember],
    prompts: AgentPromptsConfig,
) -> list[str]:
    """Who this member is and where they sit — the part of the prompt that outlives a run.

    Everything here is derived from the roster alone, which is what makes it safe to bake
    into a file on disk. Anything that depends on the task or on where this particular
    session happens to be started stays in the run's appended prompt instead
    (``orchestrator._build_system_prompt``), so a definition never claims a working
    directory or a checkout that only existed for one run.

    The sentences are the ``identity_*`` templates from ``teams.prompts`` (§10.3); only
    the data between them is the roster's. The member's Responsibility leads the block; a
    line whose data is absent — no manager, no reports — is skipped, and so is one the
    user blanked in Settings. The built-in Producer's responsibility mirrors its System
    Prompt (§10.11), which belongs only to an active Producer chat and is resolved there
    with the chat's language.
    """
    candidates = [
        prompts.text("identity_responsibility", responsibility=member.responsibility)
        if member.responsibility and not is_producer(member)
        else "",
        prompts.text(
            "identity",
            name=member.name,
            role=member.role_title,
            team=team.name if team else "this project",
        ),
        prompts.text("identity_manager", manager=describe(manager)) if manager else "",
        prompts.text(
            "identity_reports", reports=", ".join(describe(report) for report in reports)
        )
        if reports
        else "",
        prompts.text("identity_peers", peers=", ".join(describe(peer) for peer in peers))
        if peers
        else "",
    ]
    return [line for line in candidates if line]


def definition_text(
    *,
    member: TeamMember,
    team: Team | None,
    manager: TeamMember | None,
    reports: list[TeamMember],
    peers: list[TeamMember],
    prompts: AgentPromptsConfig,
) -> str:
    """The ``.md`` a member projects to: Claude Code frontmatter plus the role.

    Only ``description`` and the prompt are written. The model, tools, permission mode and
    turn limit stay on the request — they are edited in the Studio and travel as flags, and
    duplicating them here would create a second copy that silently loses every argument.
    """
    role = member.role_title or "team member"
    description = (
        f"{member.name} — {role}"
        + (f". {member.responsibility}" if member.responsibility and not is_producer(member) else ".")
    )
    front = [
        "---",
        f"name: {slug_for(member)}",
        f"description: {_yaml_scalar(description)}",
        f"{OWNER_KEY}: {member.id}",
        "---",
        "",
        "",
    ]
    body = identity_lines(
        member=member, team=team, manager=manager, reports=reports, peers=peers,
        prompts=prompts,
    )
    body.append(
        "This definition is written by Voxarium Studio from the team roster. Edits here "
        "are overwritten on the next roster change — change the member in the Studio "
        "instead, or drop the file and give the member an agent of its own."
    )
    return "\n".join(front) + "\n\n".join(body) + "\n"


def _yaml_scalar(text: str) -> str:
    """One-line YAML scalar. Quoted always: a description ends in ``:`` often enough."""
    return '"' + text.replace("\\", "\\\\").replace('"', '\\"').replace("\n", " ") + '"'


def owner_of(path: Path) -> str | None:
    """The member id a definition claims, or ``None`` for a file that is not ours.

    Deliberately a cheap scan of the frontmatter rather than a YAML parse: this runs
    against files the user may have written, and a definition that does not parse is
    somebody else's problem to fix, not a reason to fail a run.
    """
    try:
        with path.open("r", encoding="utf-8", newline="") as handle:
            if handle.readline().strip() != "---":
                return None
            for _ in range(40):
                line = handle.readline()
                if not line or line.strip() == "---":
                    return None
                key, _, value = line.partition(":")
                if key.strip() == OWNER_KEY:
                    return value.strip().strip("\"'") or None
    except OSError:
        return None
    return None


def write_member(
    paths: ProjectPaths,
    *,
    member: TeamMember,
    team: Team | None,
    manager: TeamMember | None,
    reports: list[TeamMember],
    peers: list[TeamMember],
    prompts: AgentPromptsConfig,
) -> str | None:
    """Project one member onto disk. Returns the ``--agent`` name, or ``None``.

    ``None`` means the name is taken by a definition the Studio does not own — the caller
    runs without ``--agent`` rather than clobbering it.
    """
    if member.agent:
        # Pointing at somebody else's agent: nothing of ours to write, and the name is
        # theirs to be right about. An unknown one fails loudly in the CLI, which is a
        # better error than anything we could invent by guessing at their directory. Any
        # definition we wrote before the member borrowed one goes now — leaving it would
        # offer a stale copy of this member's role in the terminal's agent list.
        forget_member(paths, member.id)
        return member.agent
    if member.kind == MemberKind.HUMAN:
        forget_member(paths, member.id)
        return None

    name = slug_for(member)
    path = paths.claude_agents / f"{name}.md"
    # An absent file is ours to create; a present one only if it says so. `None` — no
    # marker at all — is somebody's hand-written agent that happens to share the name.
    owner = owner_of(path) if path.exists() else member.id
    if owner != member.id:
        log.warning(
            "Leaving %s alone: it belongs to %s, so %s runs without --agent",
            path,
            owner or "no member (hand-written)",
            member.id,
        )
        return None

    text = definition_text(
        member=member, team=team, manager=manager, reports=reports, peers=peers,
        prompts=prompts,
    )
    try:
        paths.claude_agents.mkdir(parents=True, exist_ok=True)
        if path.exists() and path.read_text(encoding="utf-8") == text:
            return name  # Unchanged — leave the mtime alone so watchers stay quiet.
        path.write_text(text, encoding="utf-8", newline="\n")
    except OSError as exc:
        log.warning("Could not write the agent definition for %s: %s", member.id, exc)
        return None
    return name


def forget_member(paths: ProjectPaths, member_id: str) -> None:
    """Remove the definition for a member that is gone. Ours only, by the marker."""
    directory = paths.claude_agents
    if not directory.is_dir():
        return
    for path in directory.glob("*.md"):
        if owner_of(path) == member_id:
            _unlink_quietly(path)


def _unlink_quietly(path: Path) -> None:
    try:
        path.unlink()
    except OSError as exc:  # a read-only file is the user's to sort out, not a run failure
        log.warning("Could not remove the stale agent definition %s: %s", path, exc)


def prune(paths: ProjectPaths, *, keep: set[str]) -> None:
    """Drop definitions for members who no longer exist.

    Keyed on the marker, so a rename that changes the slug leaves no orphan behind and a
    hand-written agent is never in scope.
    """
    directory = paths.claude_agents
    if not directory.is_dir():
        return
    for path in directory.glob("*.md"):
        owner = owner_of(path)
        if owner is not None and owner not in keep:
            _unlink_quietly(path)
