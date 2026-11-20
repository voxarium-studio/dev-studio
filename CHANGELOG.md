# Changelog

## v6.1.0 — 2026-11-20

### Agent backends and context

- Added Antigravity (`agy`) for tasks and chats through Agent Client Protocol, with per-session MCP servers, model and effort selection, permission handling, and session recovery from saved context. Added Antigravity to the status bar and Orchestrator settings.
- Added multiple OpenAI-compatible connections in global Settings. Each connection has its own URL, API key, icon, model catalog, default model, and context-window setting. Connections are available to teams, tasks, chats, and subagents. API keys stay in the user's local configuration.
- Added streaming Responses API and Chat Completions support, including reasoning, function tools, image input, and local session resumption. Auto mode uses Responses for `api.openai.com` and Chat Completions for other hosts. Either API can be selected explicitly.
- Added an optional per-connection tokens-per-minute limit shared across projects and runs. Studio reserves capacity before sending requests and waits when the budget is exhausted.
- Added context-window-aware compaction for OpenAI-compatible sessions, including chunked summarization, bounded tool output, and paginated access to large results. Studio can recover from provider context overflows and keeps the full conversation available in the UI.
- Added bounded retries for transient streaming failures and a larger output budget when generation is cut short. Tool calls execute only after a complete response. Streamed text and reasoning update a single transcript block each, including when viewing older fragmented transcripts.
- Extended Context Viewer to show retained OpenAI API session history, including the system instructions, enabled tool definitions, and the summary and messages kept after compaction. The latest request's input token count is shown separately.
- Made Orchestrator remap automatically chosen models when a task or chat changes backends, while preserving explicit model choices. Continuations start a session on the new backend using saved conversation context.
- Updated Claude Code quota reporting and context-window detection. Automatic task compaction uses a configurable percentage of the reported window, with a token threshold for backends that omit the window. Task and Producer conversations now show when compaction is in progress.

### Producer and desktop

- Linked new and reused tasks to stable Producer Plan items. The plan shows linked task statuses and reopen counts. Producer requires justification for adding more work to an item and progressively more justification for reopening a completed task in place.
- Added a chat launcher with an unread count across Studio pages. It can open the docked Producer conversation in a floating popup while retaining its draft and attachments.
- Added recent measured output tokens per second to the status bar for backends that report enough data. Samples are scoped to the current project and expire when no fresh measurement is available.
- Switched the interface to bundled Allo with Inter as the Cyrillic fallback. Appearance settings still offer a choice of monospace font.
- Fixed desktop session restore to wait for the project's initial route to settle and to open the default page when no specific page was saved.
- Fixed Windows backend restart detection. The launcher checks that the port is free and waits for a health response from the current launch before reporting readiness.

### Remote Documents and administration

- Enabled Documents sync with Remote v2. Project Settings separates selecting a Remote project, previewing reconciliation, and confirming activation. The preview shows planned uploads, downloads, deletions, and conflicts. The v2 client checks the project, server instance, account, and sync cursor before syncing or opening a collaborative room.
- Added a Studio wizard for migrating an explicitly selected legacy project to Remote v2. It verifies the existing Remote identity and leaves sync disabled until the user previews and confirms reconciliation.
- Added explicit recovery for interrupted binding changes and checkpoint archiving for recreated Remote projects or rolled-back cursors. These operations preserve local Markdown and require a fresh preview before reactivation.
- Added confirmation of which local projects use each Remote URL, with separate actions for replacing or removing keys, changing URLs, and deleting profiles. Active projects must disconnect before credential changes, and bound projects prevent URL changes or profile deletion.
- Updated Remote Admin with Docker installation discovery, SSH connection testing, in-place updates with health checks and rollback, and an explicit **Migrate & Update** action that backs up v1 data before upgrading it to v2.
- Added public HTTP-by-IP installation and an option to enable it on existing loopback installs. Studio accepts bare IP addresses and connects over HTTP and WebSocket. HTTP sends credentials and data without encryption. Remote Admin also supports HTTPS by domain and SSH-tunnel access.

### Remote AI

- Added opt-in Remote AI control between Studio installations. Owners register a device, publish a project, and grant specific users and devices access to selected teams, members, and resources. Remote AI is off by default on the server and has its own opt-in controls in Studio.
- Added a Remote AI workspace for viewing task and chat status and transcripts, creating and editing task drafts, adding notes, starting or cancelling work, and sending chat messages within the granted permissions. Commands execute on the owner's Studio. Command receipts help recover the outcome after a disconnect and prevent duplicate execution.
- Added grant revocation and device invalidation on personal-key rotation, revocation, or account deletion. The relay stores only authorization records and command audit metadata. The executing Studio stores prompts, transcripts, and results. Android interoperability has separate acceptance requirements.

## v6.0.0 — 2026-11-17

- Added desktop project tabs and multiple windows, with tab transfer between windows and session restore. Each project keeps its own interface, connections, plugins and agent sessions while sharing one backend; moving a live tab preserves its renderer without reloading.
- Made desktop window closing leave project work running, with Studio available from the tray after the last window closes. Explicit Quit checks active work across all open projects; reopening a project focuses its existing tab.
- Refreshed the Studio interface with compact icon navigation, rounded tonal panels, a unified desktop header, project tab strip, bottom status bar and a global background gradient.
- Replaced Effort Solver's weighted scoring with Orchestrator, which selects model strength and reasoning effort independently. Added global per-backend mappings for three model strengths; Auto model choices use those mappings for tasks, chats and subagents.
- Kept Producer Plans stable across continuations and aligned tasks with the plan's stages. Refined task sizing guidance to avoid unnecessary fragmentation.
- Added recent Claude Code and Codex account usage indicators to the status bar, alongside active task counts across resident projects. Missing or stale quota data is shown as unavailable.
- Improved task remote-control access with connection controls and a popover for opening or copying the session link.
- Added global Remote server profiles and personal-key management, explicit server verification, and project catalog browsing and creation. Project selection and read-only comparison are separate steps; the new Enable sync flow remains gated pending runtime, editor and credential-security acceptance.
- Added persistent project identity and guarded Remote binding changes, with checks for unsaved editors, active consumers and duplicate local working copies. Personal server keys remain in the user's local configuration rather than portable project settings.
- Added a standalone Windows Remote administration tool with SSH deployment, Docker installation, optional Caddy HTTPS setup, and server, user and project administration.
- Staged Remote 0.2.0 with wire/schema v2 and admin RPC v1. Existing server data require an explicit offline migration; Studio's legacy v1 sync runner is incompatible with the v2 server, and Studio/Android interoperability remains separate acceptance work.
- Improved Windows installer upgrades and reinstalls, including installed/incoming version labels, preserved installation paths and explicit handling of running Studio processes. Added packaged DevTools shortcuts and expanded English and Russian interface text.

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
