# Changelog

## v5.7.0 — 2026-11-12

- Added selective import of Claude Code, Codex and Qwen Code memory into project memory, with previews and explicit replacement of conflicting entries.
- Added a shared model selector and per-chat and per-task backend/model overrides. Plugins can use the same selector through the frontend runtime.
- Reworked agent startup prompts so Studio composes the run's system prompt consistently across backends. Studio-equipped agents are prompted to delegate suitable work to subagents; Effort Solver chooses each subagent's effort and scores tasks left on Auto when they start.
- Extended Graphify's document pass to Claude Code, Codex and Qwen Code, with a model choice on its settings page. Added Qwen Code session context to Context Viewer.
- Added a global Respond Language setting, independent of the UI language, for agent responses, task results and Producer prompts. It accepts any language name and defaults to English.
- Refined transcripts: subagent launches stay expanded with visible Effort Solver progress, task creation uses the violet Studio block, and other Studio tools join ordinary tool blocks.
- Added a right-click Delete action to chat lists and made chat deletion immediate across chat and Producer views. File-observation progress and incomplete-scan warnings remain in stored evidence without cluttering the conversation or changes panel.
- Improved task status and file-change panels, fixed pipeline and connection edge cases, and sped up Windows installer builds.

## v5.6.0 — 2026-11-11

- Added online Documents sync through the separately deployed Voxarium Studio Remote server. Each person keeps a local Studio and project checkout; Markdown documents sync across them, and open documents support live collaborative editing with shared cursors. Project Settings now has an Online sync connection panel.
- Added revision-based reconciliation for offline and external edits, including conflict copies when both sides change the same document. Remote stores the latest Markdown and deletion markers; images, folder appearance and document history are outside this release's sync scope.
- Made plugin Python environments build automatically in the background when a project opens, a plugin is enabled or its dependencies change. The Plugins page shows build progress and errors, and pipeline nodes wait for their environment before running.
- Unified task, Active tasks, chat and Producer transcripts in one conversation panel. A shared Default / Verbose control folds thinking and tool activity into expandable blocks.
- Replaced the Files touched sidebar with a Status column showing delegated work, session skills and MCP servers alongside touched files. New team members now start with Solo Mode and separate-checkout isolation disabled; existing explicit settings remain in effect.
- Added messages to running tasks and chats. Messages are queued without interrupting the agent and marked delivered when the backend receives them.
- Extended task write paths to cover an entire directory with `path/*`. An active task's assignee can update its write paths when the new paths are free; conflicting path locks are acquired atomically.
- Removed local checkout and builder home paths from packaged desktop runtimes. Updated the available Qwen model list.

## v5.5.0 — 2026-11-09

- Added project memory shared across agent backends and task checkouts. Agents receive a bounded index at startup and can recall, create and remove individual Markdown entries; the new Memory page lets people manage the same entries.
- Added Context Viewer for tasks and chats, with token usage, a proportional map of context fragments and drill-down into prompts, Studio snapshots and supported backend journals. Source labels and locations make the displayed content traceable.
- Added model, effort, context and token-usage chips to agent conversations. Producer task cards now show the same cumulative usage as task details.
- Improved Producer workflows: conversations have separate plans and pause state while sharing the team board; paused conversations still allow manual task starts under the existing checks. Producer can split substantial goals into independently verifiable tasks and reuse matching board tasks.
- Loaded the Studio skill at the start of every agent session and the Producer skill for active Producer conversations. Required skills are included once, and Codex transcripts identify explicit reads of offered skill files.
- Added an Unreal Build pipeline node with playable Package builds through AutomationTool and Compile mode through UnrealBuildTool. Builds expose their output directory to later nodes and support target, platform, configuration, architecture and output-folder settings.
- Migrated managed-project data from `voxarium_studio/` to `.voxarium_studio/` when projects are opened or initialized, including existing database paths and legacy document folders.
- Added copy controls to code blocks in documents, Markdown, file previews, task diffs and update logs.
- Improved the Producer conversation and task-card layout, skill filtering, and English and Russian interface text.

## v5.4.0 — 2026-11-06

- Added the built-in Effort Solver agent with configurable scoring weights. Producer Auto effort now evaluates each proposed task before creation, maps the result to Low–Max and shows the four scores and final effort in a purple conversation block.
- Split global Settings from Project Settings. Language, appearance, Explorer visibility, node-cache reuse and update preferences follow the user across projects; the accent and project workflow settings remain project-specific. Existing preferences migrate automatically.
- Added required startup skills and full Documents content at team and member level, including task, chat and subagent runs. Missing requirements prevent a run from starting, and required skills are protected from deletion.
- Moved shared agent run settings and prompts to Team Settings, with explicit per-member overrides. Added discovery of installed Studio, global, project and backend-specific skills, plus a searchable picker with source and backend filters.
- Added a protected human Director role and safer member removal: active work is stopped and tasks, reporting relationships and chat history can be transferred to Director after confirmation.
- Made Teams continuously editable and improved autosave for member settings, team defaults and prompts, including changes made while a save is in progress.
- Improved Producer's first-message lifecycle so empty conversations do not pause or resume teams. Updated policy changes, startup-state migration, controls and task-card model/effort details.
- Added a shared effort slider for member and task settings, and backend readiness indicators for Claude Code, Codex and Qwen Code.
- Corrected duplicate token accounting and separated input, output, cache-read and unclassified usage. Added an offline repair tool with evidence and partial-recovery reporting for historical records.
- Unified token-usage chips across task details, Producer cards and subagents. Added daily average task duration and clearer loading, retry and partial-data states in Statistics.
- Fixed installer version labels for commit builds and added confirmed switching between commit and release backends while preserving the open project.
- Updated English and Russian interface text and expanded coverage for settings migration, agent lifecycle, required context, effort calculation and usage accounting.

## v5.3.0 — 2026-11-05

- Added persistent task subagents across agent backends, with MCP lifecycle controls, restart recovery and combined usage accounting.
- Added live token usage to tasks and Producer cards, plus token and API-cost statistics with daily averages.
- Added independent Git, Plastic SCM and Perforce settings, including task-scoped Perforce submits.
- Changed task conflict locks to use explicitly declared write paths, allowing tasks that only reference the same files to run concurrently.
- Added a single task summary in the selected interface language, preserved through continuations and delegation.
- Unified desktop, backend and frontend version identity using the exact Git tag or commit, and improved startup recovery diagnostics.
- Bound the built-in Producer's read-only responsibility to its configured system prompt.

## v5.2.0 — 2026-11-04

- Added a Windows installer build script and local build instructions.
- Fixed installer packaging to include the backend, web interface and built-in plugins.
- Bounded file observation before agent startup, with preparation progress, cancellation and safe handling of incomplete scans.

## v5.0.0 — 2026-11-04

Public release of Voxarium Studio.

- AI pipelines, plugin support and project file previews.
- Claude Code and Codex agent teams, task management and Producer Mode.
- English/Russian web interface and Windows desktop application.
