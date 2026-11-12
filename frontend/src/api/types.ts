/**
 * TypeScript mirrors of the backend's Pydantic/SQLModel schemas.
 *
 * Hand-written rather than generated from the OpenAPI document: the surface is small,
 * and a generated client would bury the couple of places where the wire shape differs
 * from the Python one (notably pipeline edges, which serialise with `by_alias=True`
 * and therefore arrive as `from`/`to`, not `from_`/`to`).
 */

// ---------------------------------------------------------------- node type schemas

export type PortType =
  | 'string'
  | 'text'
  | 'number'
  | 'integer'
  | 'boolean'
  | 'file'
  | 'directory'
  /** A file *or* a directory — the caller has not decided which, and the picker allows both. */
  | 'path'
  | 'enum'
  | 'json'
  /** A `#rrggbb` string, edited through a swatch and an eyedropper. */
  | 'color'

export type ResourceClass = 'cpu' | 'gpu' | 'io' | 'agent'
export type ExecutionKind = 'python_script' | 'python_function' | 'http_api'

export interface PortSchema {
  id: string
  label: string
  type: PortType
  required: boolean
  default: unknown
  description: string
  options: string[]
  accept: string[]
  minimum: number | null
  maximum: number | null
  step: number | null
  /**
   * Whether this port shows a canvas handle *by default*. `NodeInstance.pins` overrides it
   * per node, so a config field can still be driven by an upstream node and an unused pin
   * can be folded away.
   */
  connectable: boolean
}

export interface ExecutionSpec {
  kind: ExecutionKind
  entry: string
  env: string | null
  resource_class: ResourceClass
  timeout_seconds: number | null
  method: string
  headers: Record<string, string>
  cacheable: boolean
}

export interface NodeTypeSchema {
  id: string
  name: string
  category: string
  version: string
  description: string
  color: string | null
  icon: string | null
  inputs: PortSchema[]
  outputs: PortSchema[]
  execution: ExecutionSpec
  plugin_id: string
  ui_component: string | null
  /** Resolved asset URL for `ui_component`; null when the node uses the generic renderer. */
  ui_component_url: string | null
  /**
   * The output port this node fans out over: everything downstream of it on the canvas
   * runs once per item (§8.2).
   *
   * The node answers with a list on that port and the engine hands the body one element at
   * a time, so what the canvas draws is a loop whose body is simply the nodes to its right
   * — there is no second edge kind and no closing node.
   */
  iterates: string | null
}

export interface NodeTypesResponse {
  node_types: NodeTypeSchema[]
  categories: Record<string, string[]>
}

// ---------------------------------------------------------------- pipelines

export interface Position {
  x: number
  y: number
}

export interface NodeInstance {
  id: string
  type: string
  label: string | null
  position: Position
  params: Record<string, unknown>
  /**
   * Per-node overrides of `PortSchema.connectable`, keyed by port id.
   *
   * A missing key means "whatever the node type says", which is what keeps a pipeline
   * following a plugin that later changes its mind about a port; `false` is the user
   * folding a declared pin away. Only divergences are stored, so the YAML stays quiet.
   */
  pins: Record<string, boolean>
  /** Hold the run at this node once it succeeds, until the user continues it. */
  pause_on_complete: boolean
  disabled: boolean
}

export interface EdgeEndpoint {
  node: string
  port: string
}

export interface PipelineEdge {
  id: string | null
  from: EdgeEndpoint
  to: EdgeEndpoint
}

export interface PipelineDefinition {
  id: string
  name: string
  description: string
  version: string
  nodes: NodeInstance[]
  edges: PipelineEdge[]
  viewport: Record<string, number>
}

export interface PipelineSummary {
  id: string
  name: string
  description: string
  node_count: number
  edge_count: number
}

export interface PipelineIssue {
  level: string
  message: string
  node_id?: string | null
}

export interface PipelineDetail {
  pipeline: PipelineDefinition
  issues: PipelineIssue[]
}

// ---------------------------------------------------------------- runs

export type RunStatus =
  | 'pending'
  | 'running'
  /** Held at a node whose `pause_on_complete` is set, waiting to be continued. */
  | 'paused'
  | 'success'
  | 'error'
  | 'cancelled'

export type NodeStatus =
  | 'pending'
  | 'running'
  /**
   * Finished, and holding the run open. Its outputs are already on the payload — the node
   * did its work — so this only says which node the Continue button belongs to.
   */
  | 'paused'
  | 'success'
  | 'error'
  | 'cancelled'
  | 'skipped'
  | 'cached'

/**
 * How far along a running node says it is (`node.progress`).
 *
 * Live-only: it arrives over the WebSocket while the node runs and is never part of a
 * `NodeRun` row, so it is here for its shape rather than because REST answers with it.
 * Every field is optional — a node that knows only which stage it reached sends `label`
 * alone, and one counting files sends `step`/`steps` with no percentage.
 */
export interface NodeProgress {
  /** Overall completion, 0-100. */
  percent: number | null
  /** Human name of the stage the node is in ("Multiview diffusion"). */
  label: string | null
  /** Position inside that stage, as the node words it ("9/15"). */
  detail: string | null
  /** Seconds the node expects to still need. */
  eta_s: number | null
  step: number | null
  steps: number | null
}

export interface Run {
  id: string
  pipeline_id: string
  pipeline_name: string
  status: RunStatus
  trigger: string
  created_by: string | null
  start_node_id: string | null
  use_cache: boolean
  params: Record<string, unknown>
  error: string | null
  created_at: string
  started_at: string | null
  finished_at: string | null
  is_running?: boolean
}

export interface NodeRun {
  id: string
  run_id: string
  node_id: string
  node_type: string
  status: NodeStatus
  cache_key: string | null
  from_cache: boolean
  inputs: Record<string, unknown>
  outputs: Record<string, unknown>
  error: string | null
  exit_code: number | null
  log_path: string | null
  started_at: string | null
  finished_at: string | null
}

export interface RunDetail {
  run: Run
  nodes: NodeRun[]
  is_running: boolean
  /**
   * Nodes currently holding the run open, from the runner rather than from a stored column:
   * it is the same kind of live state as `is_running`, and this is how a client that arrives
   * mid-pause learns what a `run.paused` event would have told it.
   */
  paused_nodes: string[]
}

export interface RunRequest {
  start_node_id?: string | null
  use_cache?: boolean
  params?: Record<string, unknown>
}

export interface RunStartedResponse {
  run_id: string
  status: string
  pipeline_id: string
}

// ---------------------------------------------------------------- explorer

export type FileKind = 'text' | 'image' | 'model' | 'binary' | 'directory'

export interface FileEntry {
  name: string
  path: string
  is_dir: boolean
  size: number
  modified: string | null
  kind: FileKind
  has_children: boolean
}

export interface TreeNode {
  path: string
  name: string
  entries: FileEntry[]
  children: Record<string, TreeNode>
}

export interface PreviewResult {
  kind: FileKind
  path: string
  size: number
  modified: string | null
  mime: string | null
  text: string | null
  data_url: string | null
  truncated: boolean
  message: string | null
  /**
   * How `text` was decoded — `utf-8`, `utf-16-le`, `cp1251`… `null` for anything that
   * is not text. The backend sniffs content rather than trusting the suffix, so a file
   * with no extension at all still comes back as text when it is one.
   */
  encoding: string | null
  /** Hex dump of the first bytes, so a binary file is still inspectable (§6.2). */
  hex: string | null
  /** Plugin-supplied viewer claiming this file type, if one is installed. */
  previewer: FilePreviewer | null
}

/** A viewer a plugin registered for a file type (spec §6.2). */
export interface FilePreviewer {
  id: string
  plugin_id: string
  label: string | null
  extensions: string[]
  component_url: string
}

// ------------------------------------------------- explorer: file operations
//
// Every path is project-relative and forward-slashed, exactly as the tree reports it.
// The backend resolves each one through `ExplorerService.resolve()`, so an attempt to
// escape the project comes back 403 rather than touching the filesystem.

export type EntryKind = 'file' | 'directory'

export interface CreateEntryRequest {
  /** Directory the new entry goes in. The empty string is the project root. */
  parent: string
  name: string
  kind: EntryKind
}

export interface RenameRequest {
  path: string
  /** New basename — not a path. A separator in it is refused with 400. */
  name: string
}

export interface TransferRequest {
  paths: string[]
  /** Destination *directory*, project-relative. */
  destination: string
  overwrite?: boolean
}

export interface DeleteRequest {
  paths: string[]
}

/** What an operation produced, so the client can select or reveal it. */
export interface EntryPathsResponse {
  paths: string[]
}

/**
 * Result of showing an entry in the machine's own file manager.
 *
 * `selected` is false where the platform can only open a folder — Linux's `xdg-open`
 * takes a location and not a selection — so the UI can say "opened the folder" rather
 * than claiming a highlight that is not there.
 */
export interface RevealResult {
  /** What was opened: the file's containing folder, or the directory itself. */
  path: string
  selected: boolean
}

// ---------------------------------------------------------------- plugins

export type PluginScope = 'builtin' | 'global' | 'project'
export type PluginStatus = 'loaded' | 'disabled' | 'error'

/**
 * Where a plugin's isolated environment stands (§2.1, §9.4). The Studio builds it by itself
 * — on opening the project, on enabling the plugin, after its requirements change — so this
 * is something to show, never something to act on. `pending` is "not built for the current
 * requirements and not being built": a disabled plugin's environment, until it is enabled.
 */
export interface PluginEnv {
  state: 'ready' | 'building' | 'pending' | 'failed'
  /** What uv said, when `failed`. */
  error: string | null
}

export interface LoadedPlugin {
  id: string
  name: string
  version: string
  author: string
  description: string
  scope: PluginScope
  enabled: boolean
  status: PluginStatus
  error: string | null
  node_types: string[]
  pages: string[]
  previewers: string[]
  grammars: string[]
  /** Functions the plugin runs on the project opening or on an event (§9.7). */
  hooks: string[]
  /** Its own endpoints — `POST /api/plugins/{id}/actions/{action}`. */
  actions: string[]
  /** Answerers for the rail's search field. */
  search_providers: string[]
  /** What it offers the Automations page as actions (§6.5). */
  automation_actions: string[]
  has_backend: boolean
  has_venv: boolean
  /** Null when the plugin declares no isolated environment. */
  env: PluginEnv | null
  root: string
}

// ---------------------------------------------------------------- search

/** Who answers the search field in the rail (§6.1). None means no field. */
export interface SearchProviderInfo {
  /** Unique across plugins: `<plugin id>.<provider id>`. */
  id: string
  plugin_id: string
  label: string
}

export interface SearchResult {
  title: string
  subtitle: string | null
  /** Project-relative; opens the file in the Explorer. Null when it is outside the project. */
  path: string | null
  line: number | null
  snippet: string | null
  /** A short word for what this is — `function`, `class`, `note` — shown as a tag. */
  kind: string | null
  score: number | null
}

/** One provider's answer to one query. */
export interface SearchGroup extends SearchProviderInfo {
  results: SearchResult[]
  /** Prose for the deep form of the query, shown under the hits. */
  answer: string | null
  /** One line about the source — how fresh it is, or why there is nothing yet. */
  note: string | null
  /** The provider raised; the others' hits still stand. */
  error: string | null
}

export interface SearchResponse {
  query: string
  deep: boolean
  providers: SearchGroup[]
}

export interface PluginPage {
  id: string
  plugin_id: string
  title: string
  route: string
  icon: string | null
  /** Absolute URL of a prebuilt ES module, or null for a schema-only page. */
  component_url: string | null
}

/**
 * A language a plugin taught the Studio to highlight (§6.2).
 *
 * `grammar_url` serves a TextMate grammar — the format VS Code uses, so an existing
 * `.tmLanguage.json` works unmodified and a plugin author writes no highlighting code.
 */
export interface SyntaxGrammar {
  id: string
  plugin_id: string
  name: string
  /** File suffixes this grammar claims, lower-cased and dot-prefixed. */
  extensions: string[]
  /** TextMate scope, e.g. `source.angelscript`. */
  scope_name: string
  aliases: string[]
  grammar_url: string
}

// ---------------------------------------------------------------- teams

/**
 * `todo` is where every task is created and the one column nothing runs from; `pending`
 * means released, and is the only status a session starts from (§10.4).
 */
export type TaskStatus =
  | 'todo'
  | 'pending'
  | 'in_progress'
  | 'needs_review'
  | 'done'
  | 'blocked'
  | 'cancelled'

/**
 * How a task's work ended, said by whoever did it when the card reached review (§10.4):
 * `done` green, `question` blue, `error` red. Required of an agent (`finish_task`) and of a
 * person (Submit) alike.
 */
export type TaskResultType = 'done' | 'question' | 'error'

/** `human` members have no agent session — their tasks wait in an inbox (§10.2). */
export type MemberKind = 'agent' | 'human'

/** The four relative weights used by the built-in Effort Solver (§10.11). */
export interface EffortSolverWeights {
  complexity: number
  creativity: number
  depth: number
  coverage: number
}

export interface TeamMember {
  builtin?: 'producer' | 'director' | 'effort_solver' | null
  effort_solver_weights?: EffortSolverWeights | null
  id: string
  team_id: string
  name: string
  role_title: string
  kind: MemberKind
  manager_id: string | null
  responsibility: string
  /** Sideways routes: peer ids, or `['*']` for anyone on the team. */
  can_assign_to: string[]
  tags: string[]
  agent_backend: string
  /**
   * An agent this project (or the machine) already defines, to run as instead of the one
   * the Studio writes from this member. Null is the default and the common case.
   */
  agent: string | null
  model: string
  /** How hard the model works per turn, or null to leave it to the model's own default. */
  effort: string | null
  allowed_tools: string[]
  /**
   * Skills from `.voxarium_studio/skills/` this member may use, by folder name (§10.10). Empty unless
   * somebody gave them some — a skill in the project reaches nobody by default. A name the
   * folder no longer has stays here until it is taken off, and is not offered to the session.
   */
  skills: string[]
  /** Mandatory startup skills, added to the shared Team Settings requirements (§10.10). */
  required_skills?: string[]
  /** Documents paths, relative to .voxarium_studio/docs, included in full on startup. */
  required_documents?: string[]
  /** Use this member's permission mode and turn budget instead of Team Settings (§10.2). */
  run_settings_override: boolean
  max_turns: number
  permission_mode: string
  isolate_worktree: boolean
  /**
   * One task at a time (§10.4): a released task waits in `pending` while another of this
   * member's is in progress, and starts by itself when that one ends. On by default.
   */
  solo_mode: boolean
  avatar_color: string | null
  /**
   * A Lucide icon name standing for what this member does — `palette`, `code`, `bug`. Null
   * is no icon: initials on the org chart and a plain dot elsewhere. The catalogue is
   * `lib/member-icons.ts`, and a name it does not know reads as null.
   */
  avatar_icon: string | null
  /**
   * Hand-placed position on the org chart. Null on both when nobody has moved this node,
   * in which case the chart derives one (`lib/org-layout.ts`).
   */
  pos_x: number | null
  pos_y: number | null
  sort_order: number
  created_at: string
}

export interface TeamTask {
  /** Producer draft approval gate, returned by the ordinary task API (§10.11). */
  producer_chat_id?: string | null
  producer_approved?: boolean
  attachments?: (TaskAttachment & { reference: string })[]
  id: string
  /**
   * Sequential per managed project, assigned on creation and never editable (§10.2).
   *
   * The opaque `id` stays the key everything else references; this is the identity a
   * person can say out loud, and `display_id` is it with the project's code in front.
   */
  number: number
  /**
   * `VS-01` — the project code from Settings, a dash, and the number padded to at least
   * two digits. Composed by the backend so the SPA, the agent tools and anything reading
   * the API cannot disagree about what a task is called. Read-only everywhere.
   */
  display_id: string
  team_id: string
  assignee_id: string
  created_by: string | null
  title: string
  brief: string
  /** Explicit files to write; read-only references never acquire locks (§10.5). */
  write_paths: string[]
  status: TaskStatus
  depends_on: string[]
  auto_start: boolean
  /**
   * Wait out a spent usage limit instead of ending the run on it (§10.3).
   *
   * On by default: a run refused because the account is out of usage is parked in `blocked`
   * with `limit_reset_at` recorded, and starts itself again shortly after that reset — the
   * same click a person would make, without having to be awake for it.
   */
  resume_on_limit: boolean
  /**
   * When the usage limit that stopped this task refills, as the runtime reported it.
   *
   * Recorded whether or not the wait is on, so a blocked task can always say when it could
   * go again; null on a limit the runtime did not date, and cleared when the task moves on.
   */
  limit_reset_at: string | null
  /**
   * When the wait ends and the task starts again — the reset plus a grace period, and the
   * instant the countdown is drawn against. Null when nothing is waiting.
   */
  limit_resume_at: string | null
  /**
   * Run this one task on something other than the assignee's model or effort (§10.3).
   *
   * Null on both — the usual case — means the member's. A role is configured once for the
   * work it usually does; what varies underneath it is the card.
   */
  agent_backend?: string | null
  model: string | null
  effort: string | null
  agent_session_id: string | null
  /**
   * Where this session is reachable on claude.ai while Remote Control is on (§10.8).
   *
   * Live only for as long as the run that opened the bridge: the backend clears it when
   * the session ends, so a link on screen is always one that still controls something.
   */
  remote_control_url: string | null
  worktree_path: string | null
  review_comment: string | null
  result_summary: string | null
  /** Reader's language captured for task summaries; absent on older servers (§10.4). */
  summary_locale?: 'en' | 'ru' | null
  /**
   * How the work ended, said by whoever did it when the task reached review (§10.4):
   * `done` (green), `question` (blue) or `error` (red). Null until reported — and on a
   * task in review, null means the session ended without saying.
   */
  result_type: TaskResultType | null
  error: string | null
  total_cost_usd: number
  num_turns: number
  /** Input, output and cache tokens across every session, summed — shown beside the turns. */
  total_tokens: number
  /** Input, cache creation and output; cache reads are tracked separately (§10.4). */
  total_uncached_tokens?: number
  total_cached_tokens?: number
  total_input_tokens?: number
  total_output_tokens?: number
  /**
   * Off the board, still on the books (§10.4). Only a `done` task can be archived, and a
   * status move off `done` un-archives it, so this never describes a card the board has to
   * show. The cost figures count it; the board hides it.
   */
  archived: boolean
  created_at: string
  started_at: string | null
  finished_at: string | null
  is_running?: boolean
  is_compacting?: boolean
}

/**
 * A file a reviewer attached to a review comment (§10.4). Stored under
 * `.voxarium_studio/attachments/<task>/`; a `review` entry's `data.attachments` lists them, and the
 * session that resumes gets the pictures as image blocks and every file by its path.
 */
export interface TaskAttachment {
  name: string
  /** Project-relative, forward slashes. */
  path: string
  size: number
  media_type: string | null
  image: boolean
}

export interface AgentMessage {
  id: string
  task_id: string
  member_id: string
  /** `user` is the Studio's operator; `human` is a human team member speaking as themselves. */
  role: 'user' | 'human' | 'assistant' | 'system' | 'tool' | 'result'
  kind: string
  text: string
  data: Record<string, unknown>
  created_at: string
}

/**
 * A conversation with one team member outside the board (§10.9): the same session, tools
 * and MCP servers a task run gets, with the user's whereabouts in place of a brief.
 */
export type ProducerPolicy = 'careful' | 'independent'
export type ProducerEffortMode = 'agent' | 'auto'

export interface AgentChat {
  agent_backend?: string | null
  model?: string | null
  producer_mode?: boolean
  /** Producer workspace chats keep their mode enabled until deletion (§10.11). */
  producer_workspace?: boolean
  /** The first Producer turn has been accepted; remains true across restarts (§10.11). */
  producer_started?: boolean
  producer_paused?: boolean
  producer_policy?: ProducerPolicy
  /** Task effort comes from the assignee unless the Producer must choose it (§10.11). */
  producer_effort_mode?: ProducerEffortMode
  /** Approximate task API budget in USD; old servers use the $150 default (§10.11). */
  estimate_task_cost_usd?: number
  producer_state?: 'off' | 'discussing' | 'running' | 'awaiting_approval' | 'awaiting_user' | 'waiting' | 'error'
  producer_watch?: { id: string }[]
  producer_plan?: ProducerPlanTask[]
  producer_plan_summary?: string
  producer_plan_version?: number
  producer_checklist?: { text: string; done: boolean }[]
  id: string
  member_id: string
  /** The first message's first line, unless somebody named it. */
  title: string
  agent_session_id: string | null
  /** What ended the last turn early, if anything did — cleared by the next message. */
  error: string | null
  total_cost_usd: number
  num_turns: number
  total_tokens: number
  total_uncached_tokens?: number
  total_cached_tokens?: number
  total_input_tokens?: number
  total_output_tokens?: number
  created_at: string
  last_message_at: string
  /** The member is answering right now. */
  is_running: boolean
}

export interface ProducerPlanTask {
  id: string
  display_id: string
  title: string
  brief: string
  assignee_id: string
  depends_on: string[]
}

/** A chat's transcript entry — `AgentMessage` with `chat_id` where `task_id` is. */
export interface AgentChatMessage {
  id: string
  chat_id: string
  member_id: string
  role: 'user' | 'human' | 'assistant' | 'system' | 'tool' | 'result'
  kind: string
  text: string
  data: Record<string, unknown>
  created_at: string
}

export interface ChatDetail extends AgentChat {
  messages: AgentChatMessage[]
}

/** Where the user is in the Studio when they send — what the member is told before the words. */
export interface ChatContext {
  /** The section as the rail names it: `Documents`, `Tasks`. */
  page: string
  /** The address bar's path and query. */
  route: string
  /** What the page calls what is open — a document's heading, a task's title. */
  title: string
  /** A shared document's path under the docs folder, when one is open. */
  document: string | null
  /** A project file's path from the project root, when the Explorer has one open. */
  file: string | null
  /** Text selected on the page, if any. */
  selection: string | null
}

export interface ChatCreate {
  member_id: string
  title?: string
  producer_mode?: boolean
  locale?: 'en' | 'ru'
}

export interface Team {
  id: string
  name: string
  description: string
  autostart_tasks: boolean
  created_at: string
  members: TeamMember[]
}

export interface TeamDetail extends Team {
  tasks: TeamTask[]
}

export interface TaskDetail extends TeamTask {
  assignee: TeamMember | null
  messages: AgentMessage[]
  /** §10.3: durable worker state, including after transcript compression or archiving. */
  subagents?: TaskSubagent[]
}

export interface TaskSubagent {
  id: string
  task_id: string
  member_id: string
  description: string
  backend: string
  model: string
  effort: string | null
  status: 'queued' | 'running' | 'completed' | 'failed' | 'stopped'
  session_id: string | null
  summary: string
  total_tokens: number
  total_uncached_tokens?: number
  total_cached_tokens?: number
  total_input_tokens?: number
  total_output_tokens?: number
  total_cost_usd: number
  num_turns: number
  tool_uses: number
  created_at: string
  updated_at: string
  finished_at: string | null
}

/** What `POST …/tasks/{id}/remote-control` answers with (§10.8). */
export interface RemoteControlLink {
  task_id: string
  /** The one the user opens: this session on claude.ai/code and in the Claude mobile app. */
  session_url: string | null
  connect_url?: string | null
  environment_id?: string | null
}

export interface TaskCreate {
  summary_locale?: 'en' | 'ru' | null
  attachments?: string[]
  assignee_id: string
  title: string
  brief?: string
  write_paths?: string[]
  created_by?: string | null
  depends_on?: string[]
  /**
   * What happens when the task is *released* to `pending` — run it as soon as it is
   * unblocked, or wait for an explicit Start. Creation never starts anything (§10.4).
   */
  auto_start?: boolean
  /** Runtime overrides for this task alone; omitted or null inherits the assignee (§10.3). */
  agent_backend?: string | null
  model?: string | null
  effort?: string | null
}

/**
 * Partial edit of a task. Applied with `exclude_unset`, so an omitted field is left alone.
 * The backend drops an explicit null on every field but `model` and `effort` — a null title
 * or assignee would break the row, while a null override is the edit itself.
 *
 * `status` is the manual move behind drag-and-drop and the status picker; the lifecycle
 * endpoints (claim/complete/approve) remain the ones with side effects.
 */
export interface TaskUpdate {
  summary_locale?: 'en' | 'ru' | null
  attachments?: string[]
  title?: string
  brief?: string
  /** Replaces the write list; [] clears it. Cannot change during a run (§10.5). */
  write_paths?: string[]
  assignee_id?: string
  depends_on?: string[]
  auto_start?: boolean
  /**
   * Wait out a spent usage limit (§10.3). Turning it on is retroactive — a task already
   * blocked by one starts waiting for the reset it was stopped at — and turning it off is
   * the only way to end a wait in flight.
   */
  resume_on_limit?: boolean
  status?: TaskStatus
  /** Null clears the override and hands the task back to the assignee's setting. */
  agent_backend?: string | null
  model?: string | null
  effort?: string | null
  /** `true` is refused on anything but a task in Done; `false` brings one back (§10.4). */
  archived?: boolean
  /** Who is editing. Omitted means the user, who is not bound by the hierarchy (§10.4). */
  actor_id?: string | null
}

/** One day on which something was spent, and the running total up to it. */
export interface CostPoint {
  /** ISO calendar date, UTC. Days with no spend are absent. */
  day: string
  spent_usd: number
  cumulative_usd: number
  /** Agent runs (result entries) that landed that day. */
  runs: number
}

export interface TokenPoint {
  day: string
  spent_tokens: number
  cumulative_tokens: number
  spent_uncached_tokens: number
  spent_cached_tokens: number
  spent_unclassified_tokens: number
  cumulative_uncached_tokens: number
  cumulative_cached_tokens: number
  cumulative_unclassified_tokens: number
  runs: number
}

/** Lifetime totals averaged over tasks in Done with a finish date on this UTC day (§10.4). */
export interface AverageTaskPoint {
  day: string
  tasks: number
  average_usd: number
  average_tokens: number
  average_uncached_tokens: number
  average_cached_tokens: number
  average_unclassified_tokens: number
}

export interface MemberSpend {
  member_id: string
  name: string
  kind: MemberKind | null
  avatar_color: string | null
  avatar_icon: string | null
  spent_usd: number
  spent_tokens: number
  spent_uncached_tokens: number
  spent_cached_tokens: number
  spent_unclassified_tokens: number
  tasks: number
  done: number
}

/**
 * What `GET /api/teams/{id}/stats` answers — every task counted, archived ones included
 * (§10.4). `spent_usd` is the whole project; the other three partition it by whether the
 * work finished, is still in flight, or was cancelled.
 */
export interface TeamStats {
  team_id: string
  totals: {
    spent_usd: number
    done_usd: number
    in_flight_usd: number
    cancelled_usd: number
    spent_tokens: number
    done_tokens: number
    in_flight_tokens: number
    cancelled_tokens: number
    spent_uncached_tokens: number
    spent_cached_tokens: number
    spent_unclassified_tokens: number
    done_uncached_tokens: number
    done_cached_tokens: number
    done_unclassified_tokens: number
    in_flight_uncached_tokens: number
    in_flight_cached_tokens: number
    in_flight_unclassified_tokens: number
    cancelled_uncached_tokens: number
    cancelled_cached_tokens: number
    cancelled_unclassified_tokens: number
    tasks: number
    done: number
    archived: number
  }
  series: CostPoint[]
  token_series: TokenPoint[]
  average_task_series: AverageTaskPoint[]
  /** Measured execution time of Done tasks, grouped by their completion day (UTC). */
  average_task_duration_series: { day: string; average_duration_ms: number; tasks: number }[]
  by_member: MemberSpend[]
  by_model: { model: string | null; spent_usd: number }[]
  by_model_tokens: {
    model: string | null
    spent_tokens: number
    spent_uncached_tokens: number
    spent_cached_tokens: number
    spent_unclassified_tokens: number
  }[]
  /** One entry per UTC day on which anything happened — the heatmap's cells. */
  activity: ActivityDay[]
}

/** What the team did on one UTC day. Days with nothing are absent. */
export interface ActivityDay {
  day: string
  /** Agent runs that landed (result entries), whatever they cost. */
  runs: number
  /** Tasks that reached `done` that day. */
  finished: number
  spent_usd: number
  spent_tokens: number
  spent_uncached_tokens: number
  spent_cached_tokens: number
  spent_unclassified_tokens: number
  /** `runs + finished` — what the cell's shade is drawn from. */
  activity: number
}

export interface MemberUpdate {
  effort_solver_weights?: EffortSolverWeights | null
  name?: string
  role_title?: string
  kind?: MemberKind
  manager_id?: string | null
  responsibility?: string
  can_assign_to?: string[]
  tags?: string[]
  agent_backend?: string
  /** Empty or null goes back to the agent the Studio writes from this member. */
  agent?: string | null
  model?: string
  /** Null clears it back to the model's default — sent, not omitted (`exclude_unset`). */
  effort?: string | null
  allowed_tools?: string[]
  /** The whole list as it should be. A name being *added* must be a skill the project has. */
  skills?: string[]
  required_skills?: string[]
  required_documents?: string[]
  run_settings_override?: boolean
  max_turns?: number
  permission_mode?: string
  isolate_worktree?: boolean
  solo_mode?: boolean
  avatar_color?: string | null
  /** Null clears the icon — sent, not omitted, like the other nullable fields. */
  avatar_icon?: string | null
}

/**
 * Node positions for the org chart, keyed by member id. A null drops the hand placement
 * and puts the member back under the derived layout.
 */
export interface LayoutUpdate {
  positions: Record<string, { x: number; y: number } | null>
}

export interface MemberCreate {
  name: string
  role_title?: string
  kind?: MemberKind
  manager_id?: string | null
  /** Short id inside the team; derived from the name when omitted. */
  id?: string
}

/**
 * One entry in the model picker. `group` splits aliases the CLI resolves at run time
 * (`opus` — follows releases) from pinned ids that never move.
 */
export interface SuggestedModel {
  id: string
  label: string
  group: string
  note: string
  /** The backend this model runs on; the picker shows only the current backend's (§10.6). */
  backend?: string
}

/** One agent backend as the editor sees it: usable or not, and whether it runs a member as a
 *  named agent definition (`claude --agent`) — Codex does not, so "Runs as" is hidden. */
export interface AgentBackendInfo {
  available: boolean
  reason: string
  named_agents?: boolean
}

/** Choices for the member editor, resolved against this install (backends, SDK modes). */
export interface MemberOptions {
  agent_backends: Record<string, AgentBackendInfo>
  permission_modes: string[]
  /** `low` … `max`, read off the installed Agent SDK like the permission modes. */
  effort_levels: string[]
  common_tools: string[]
  /** A suggestion list, not a whitelist: the field also takes anything typed into it. */
  suggested_models: SuggestedModel[]
  studio_tool_prefix: string
}

// ---------------------------------------------------------------- project docs

/** A document in `.voxarium_studio/docs` — the team's shared specs and notes (§10.4). */
export interface DocEntry {
  path: string
  title: string
  size_bytes: number
  updated_at: string
}

/** A `[[wiki link]]` found in a document. `path` is null when nothing matches it yet. */
export interface DocLink {
  target: string
  label: string | null
  path: string | null
  resolved: boolean
}

export interface DocBacklink {
  path: string
  title: string
  /** The lines the mention sits on, so the panel can show why it links here. */
  contexts: string[]
}

export interface DocDetail extends DocEntry {
  content: string
  links: DocLink[]
  backlinks: DocBacklink[]
}

export interface DocSearchResult {
  path: string
  title: string
  title_match: boolean
  matches: { line: number; text: string }[]
  match_count: number
}

export interface DocMoveResult extends DocEntry {
  moved_from: string
  /** Documents that linked to the old name — a rename can strand them. */
  referrers: DocBacklink[]
}

/** One document in the corpus's link graph. Nodes arrive sorted by path, deterministically. */
export interface DocGraphNode {
  path: string
  title: string
  in_degree: number
  out_degree: number
}

/** One resolved link. De-duplicated by (source, target); a self-link is not an edge. */
export interface DocGraphEdge {
  source: string
  target: string
}

/**
 * The corpus's link graph — every document, and every resolved `[[wiki link]]` between two
 * of them (§5).
 *
 * Whole-corpus rather than per-document on purpose: the picture is a property of the *set*,
 * so re-centring it on another document costs no request. It is also a separate lazy
 * endpoint rather than a field on `DocDetail` precisely so that nothing on the
 * document-open path ever waits for a walk over every file.
 */
export interface DocGraph {
  nodes: DocGraphNode[]
  edges: DocGraphEdge[]
}

/**
 * How a folder of the Documents tree looks — its `vxs-folder.json` (block-editor spec §5.2).
 *
 * `color` is one of the ten text colours and `icon` a Lucide identifier; the backend sends
 * `null` for anything it cannot vouch for, and the catalogue in `lib/folder-style.ts` draws the
 * plain folder for an icon name it does not know.
 */
export interface DocFolderStyle {
  path: string
  color: string | null
  icon: string | null
}

/** A patch to a folder's look: `null` gives a key back to the default, an absent key is kept. */
export interface DocFolderStyleUpdate {
  path: string
  color?: string | null
  icon?: string | null
}

/** A document whose links a folder rename mends, as the rename planned it. */
export interface DocRelink {
  /** Where the document is now. */
  path: string
  /** Where it was, when it moved with the folder; `null` for a document outside it. */
  previous_path: string | null
  title: string
  links: number
}

/**
 * One line of `POST /api/docs/folders/rename`'s answer, which is newline-delimited JSON.
 *
 * `renamed` arrives first — the folder has moved by then — with every document still to be
 * mended; a `document` frame follows as each one is written; `done` or `error` is last.
 */
export type DocFolderRenameFrame =
  | {
      type: 'renamed'
      path: string
      previous_path: string
      /** Documents that moved with the folder. */
      moved: number
      documents: DocRelink[]
    }
  | (DocRelink & {
      type: 'document'
      status: 'updated' | 'unchanged' | 'failed'
      error: string | null
    })
  | {
      type: 'done'
      path: string
      previous_path: string
      moved: number
      updated: number
      links: number
      failed: number
    }
  | { type: 'error'; error: string }

export type DocFolderRenameDone = Extract<DocFolderRenameFrame, { type: 'done' }>

/** What deleting a folder answers: the folder, and every document that went with it (§5.2). */
export interface DocFolderDeleted {
  path: string
  documents: string[]
}

// ---------------------------------------------------------------- project & config

/**
 * Every knob on Settings → Appearance (§7). Each value is an attribute the theme store stamps
 * on `<html>`; the colours themselves are blocks in `tokens.css`. The catalogue of choices,
 * with their labels, is `lib/appearance.ts`.
 */
export interface ThemeConfig {
  mode: 'dark' | 'light' | 'system'
  /**
   * One `#rrggbb`, painted as a mark — a focus ring, a selected edge, a run's progress, a link
   * — and never as a ground (§7).
   */
  accent: string
  /** Body text in px, or null to take the app's own size — `docs.font_size`'s arrangement. */
  text_size: number | null
  font: 'google-sans' | 'inter' | 'ibm-plex' | 'roboto' | 'system'
  mono_font: 'cascadia' | 'jetbrains' | 'fira' | 'system'
  sidebar: 'labels' | 'icons' | 'auto'
  canvas_grid: 'dots' | 'lines' | 'none'
  /** How strongly a canvas node's header takes its category colour, in percent. */
  node_tint: number
  status_palette: 'default' | 'accessible'
  custom_tokens: Record<string, string>
}

export interface ConcurrencyConfig {
  gpu: number
  cpu: number
  io: number
  agents: number
}

export interface ExplorerConfig {
  show_data_dir: boolean
  ignore: string[]
}

/** How the Documents section renders a document (block-editor spec §4.10). */
export interface DocsConfig {
  /**
   * Body text size of the block editor, in px, or `null` for "nobody chose one" — which
   * means one point *above* the app's own `--vxs-text-size`, not equal to it, so the §7
   * density knob still decides and a document still reads larger than the chrome.
   */
  font_size: number | null
  /**
   * Leading of the body text, unitless (multiplies `font_size`), or `null` for "nobody
   * chose one" — `editor.css` then renders at its own default.
   */
  line_height: number | null
}

/** Online sync of the project's Documents through a remote server (§16.5). */
export interface SyncConfig {
  enabled: boolean
  /** The server's address as it was entered. */
  url: string
  /** The shared set of documents on that server. */
  space: string
}

/** Somebody on the server: how they are named and what colour their cursor is. */
export interface SyncUser {
  id: string
  name: string
  color: string
}

/** One person connected to the space, with the documents they have open. */
export interface SyncPeer {
  user: SyncUser
  /** How many Studios they are connected from. */
  connections: number
  docs: string[]
  /** Whether this is the person looking at the page. */
  self: boolean
}

/**
 * - `disabled` — switched off for this project.
 * - `unconfigured` — switched on, but an address, a space or a key is missing.
 * - `connecting` / `syncing` — on its way; documents are not in step yet.
 * - `connected` — in step. The only state live editing is offered in.
 * - `offline` — the server cannot be reached; retrying.
 * - `unauthorised` — the server refused the key. Not retried.
 * - `error` — something retrying cannot fix; `message` says what.
 */
export type SyncState =
  | 'disabled'
  | 'unconfigured'
  | 'connecting'
  | 'syncing'
  | 'connected'
  | 'offline'
  | 'unauthorised'
  | 'error'

/** Everything `GET /api/sync` answers, and what `sync.status` carries (§16.5). */
export interface SyncStatus extends SyncConfig {
  /** Whether a key is stored for this address. The key itself is never sent back. */
  has_token: boolean
  /** The address is plain HTTP to another machine: the key would travel in the clear. */
  insecure: boolean
  state: SyncState
  message: string | null
  user: SyncUser | null
  peers: SyncPeer[]
}

/** A patch to the sync settings; a key left out is left alone. */
export interface SyncUpdate {
  enabled?: boolean
  url?: string
  space?: string
  /** The user's key. An empty string forgets the stored one. */
  token?: string
}

export interface AppConfig {
  version: number
  /** User-wide preference; absent/null keeps the browser's startup language (§6.4). */
  language?: 'en' | 'ru' | null
  project_name: string | null
  /**
   * Short code in front of every task id — `VS` in `VS-01` (§10.2). Null means "not
   * chosen", and the backend then derives one from the project's name, so a task always
   * has a readable id whether or not anyone has picked a code.
   */
  project_code: string | null
  theme: ThemeConfig
  concurrency: ConcurrencyConfig
  teams: TeamsConfig
  mcp: McpConfig
  vcs: VcsConfig
  explorer: ExplorerConfig
  docs: DocsConfig
  sync: SyncConfig
  updates: UpdatesConfig
  plugins: Record<string, { enabled: boolean; settings: Record<string, unknown> }>
  node_cache_enabled: boolean
}

/** How often the Studio looks at its own repository for a newer release tag (§12.1). */
export interface UpdatesConfig {
  enabled: boolean
  /** Floored at 5 by the backend — a release is tagged a few times a day at most. */
  check_interval_minutes: number
}

/** Which of `.mcp.json`'s servers agents get — the servers themselves live there (§10.7). */
export interface McpConfig {
  disabled: string[]
  strict: boolean
}

/** Empty means auto-detect; `none` turns the integration off (§10.5). */
export interface VcsConfig {
  backend: string | null
  /**
   * Per-backend settings, keyed by backend id — `{ git: { executable: 'C:/…/git.exe' } }`.
   * A plugin backend is still configured through its plugin's settings; this is what
   * gives the built-in ones somewhere to live.
   */
  settings: Record<string, Record<string, unknown>>
}

/**
 * Overrides for the fixed lines the Studio injects into an agent's context (§10.3,
 * §10.4): the run's system-prompt fragments, the identity sentences baked into
 * `.claude/agents/*.md`, and the messages a run is opened with. `null` (or absent) means
 * the built-in default; `''` removes an optional fragment, while a `required` one falls
 * back to its default. The defaults themselves come from `GET /api/settings/prompts`.
 */
export interface AgentPromptsConfig {
  identity: string | null
  identity_responsibility: string | null
  identity_manager: string | null
  identity_reports: string | null
  identity_peers: string | null
  task_context: string | null
  task_temp: string | null
  studio_skill: string | null
  no_studio_tools: string | null
  docs_location: string | null
  project_memory?: string | null
  subagents?: string | null
  code_graph: string | null
  isolated_checkout: string | null
  outside_project: string | null
  finish: string | null
  finish_tool: string | null
  chat_mode: string | null
  chat_context: string | null
  producer_mode: string | null
  producer_careful: string | null
  producer_independent: string | null
  producer_start: string | null
  producer_wake: string | null
  producer_compact: string | null
  producer_task_cost: string | null
  producer_review: string | null
  producer_state: string | null
  producer_summary: string | null
  task_compact: string | null
  resume: string | null
  resume_notes: string | null
  review_changes: string | null
  stall_continue: string | null
  mid_run_message: string | null
  result_required: string | null
}

/** One built-in prompt fragment, from `GET /api/settings/prompts` (ordered as sent). */
export interface PromptSpec {
  default: string
  /** The `{name}` substitutions the fragment is resolved with; anything else in braces stays literal. */
  placeholders: string[]
  /** A message a run is opened with — blanking it falls back to the default instead of removing it. */
  required: boolean
}

/** Ceilings on autonomous delegation — every hop is a billed agent session (§10.4). */
export interface TeamsConfig {
  permission_mode: string
  max_turns: number
  /** Mandatory startup context for every agent, supplemented by each member (§10.10). */
  required_skills?: string[]
  required_documents?: string[]
  /** Project memory's index in every run, and the recall/remember/forget tools (§10.12). */
  memory_enabled?: boolean
  /** The most of the memory index a run is sent; past it entries are counted, not listed. */
  memory_index_chars?: number
  commit_backend: string
  commit_model: string
  commit_proposals: boolean
  max_delegation_depth: number
  max_delegated_tasks_per_task: number
  /** Automatic resumes of a session that stopped mid-work, e.g. on `max_turns` (§10.3). */
  max_run_continuations: number
  /** Automatic task compression threshold; zero disables automatic compression (§10.4). */
  context_compression_tokens?: number
  /**
   * The largest single message the Studio accepts from an agent runtime (§10.3). The Agent
   * SDK's own limit is 1 MiB per message, which one screenshot read is several times over.
   */
  max_message_bytes: number
  /**
   * Seconds of complete silence before a run waiting on its sub-agents gives up (§10.3). A
   * turn ends while delegated work keeps going and the session is woken again as each task
   * finishes; this bounds the hold. `0` waits indefinitely.
   */
  delegated_work_idle_timeout: number
  /**
   * Where agent sessions start, relative to the project root. `.` is the project itself;
   * `..` starts them one level up, for a project that is one folder of a larger checkout.
   * Outside the project, per-task checkout isolation does not apply (§10.5).
   */
  launch_root: string
  prompts: AgentPromptsConfig
}

export interface SettingsUpdate {
  project_name?: string
  /** Uppercased and validated by the backend; `''` clears it back to the derived code. */
  project_code?: string
  theme?: Partial<ThemeConfig>
  concurrency?: Partial<ConcurrencyConfig>
  /** Merged field by field, `prompts` one level deeper still — omitted keys keep their value. */
  teams?: Partial<Omit<TeamsConfig, 'prompts'>> & { prompts?: Partial<AgentPromptsConfig> }
  docs?: Partial<DocsConfig>
  updates?: Partial<UpdatesConfig>
  node_cache_enabled?: boolean
  show_data_dir?: boolean
}

/** Settings shared by every managed project, persisted in the user config (§5.1). */
export interface ApplicationSettingsUpdate {
  theme?: Partial<Omit<ThemeConfig, 'accent'>>
  updates?: Partial<UpdatesConfig>
  node_cache_enabled?: boolean
  show_data_dir?: boolean
  language?: 'en' | 'ru' | null
}

/** The version control system the managed project is under, if any (§10.5). */
export interface VcsInfo {
  id: string
  name: string
  root: string
  available: boolean
  /** Why the command-line tool is unusable, when `available` is false. */
  reason: string
  /** Whether this backend can give an agent task its own checkout. */
  supports_isolation: boolean
}

// ---------------------------------------------------------------- version control
//
// Everything the Studio does to a repository goes through `VcsBackend` (§9.6), so this
// section describes *a* version control system rather than git specifically. `kind` is
// normalised by the backend — a client never parses one tool's status letters.

export type VcsChangeKind =
  | 'added'
  | 'modified'
  | 'deleted'
  | 'renamed'
  | 'copied'
  | 'untracked'
  | 'conflicted'
  | 'unknown'

export interface VcsFileChange {
  /** Relative to the workspace root, forward slashes. */
  path: string
  kind: VcsChangeKind
  staged: boolean
  /** Previous path for a rename or a copy. */
  origin: string | null
  /**
   * Lines added and removed, where the backend counted them — `GET /api/vcs/status` does
   * not, a task's own changes do (measured off its workspace, or summed off what its tool
   * calls asked for under the `tools` source). `null` and `0` are different answers and are
   * shown differently: nothing known, versus a file that changed without gaining or losing
   * a line. A binary file is `null` on both, with `binary` saying why.
   */
  insertions?: number | null
  deletions?: number | null
  binary?: boolean
  /**
   * Outside the directory the task's session worked in (`TaskChanges.workdir`) — the
   * column lists such a file apart, under *Outside project*. Only a task's own changes
   * carry it; `GET /api/vcs/status` answers in the project's frame and has no session.
   */
  outside?: boolean
  /** Disk observation establishes timing, not authorship (§10.4). */
  evidence?: 'tools' | 'observed' | 'tools_and_observed'
}

/**
 * What one task changed (§10.5), and — this is the field that must not be ignored —
 * which question was actually answered.
 *
 * `workspace` is the real thing: the task's own checkout, compared with the commit it was
 * branched from, so work the agent committed itself counts. `tools` is the fallback for a
 * task that never had a checkout: tool paths plus disk observations labelled by `evidence`.
 * Tool-only counts are requested edits; observed paths establish timing, not authorship.
 * `reason` explains the fallback; `error` is a version control tool refusing, which is not
 * the same as a task having changed nothing.
 */
export interface TaskChanges {
  source: 'workspace' | 'tools'
  root: string
  /** Where the task's session was started — the frame `outside` on each file is judged in. */
  workdir: string
  branch: string | null
  files: VcsFileChange[]
  /** Current scratch files, relative to the managed project root, excluded from commits (§10.4). */
  temp_files: VcsFileChange[]
  /** Absolute task scratch directory; null when its path cannot be safely resolved. */
  temp_root: string | null
  /** Directory entries that could not safely be listed. */
  temp_errors?: string[]
  error: string | null
  reason: string | null
  observation_errors?: string[]
}

/**
 * The commit drafted for a task when it reached review (§10.4): the board's title, a
 * description a cheap model wrote, and the files inside the task's directory. `generating`
 * is live state — the draft is being written now — and `revision` is what the Commit button
 * last produced, so the section can say the work is in version control.
 */
export interface TaskCommitProposal {
  task_id: string
  title: string
  message: string
  files: string[]
  generated_at: string | null
  generating: boolean
  error: string | null
  revision: string | null
  /** `teams.commit_proposals` — off, the description stays empty and no model is called. */
  enabled: boolean
  model: string
  vcs: {
    backend: string | null
    name: string | null
    available: boolean
    reason: string
  }
}

export interface VcsStatus {
  /** `null` when the project is under nothing the Studio recognises. */
  backend: string | null
  root: string
  branch: string | null
  revision: string | null
  clean: boolean
  changes: VcsFileChange[]
}

/** A setting declared by an independent VCS entity (§9.6). */
export interface VcsSettingsField {
  key: string
  type: 'string' | 'path'
  default?: string
}

/** Every VCS entity is listed, whether its CLI is installed or not. */
export interface VcsBackendOption extends VcsInfo {
  /** The command-line tool it drives, before any configured override. */
  program: string
  /** What it will actually run — the configured path, or `program`. */
  executable: string
  /** Configured values; empty fields use the entity's defaults or CLI environment. */
  settings: Record<string, string>
  settings_fields: VcsSettingsField[]
  /** Whether this backend claims the open project. */
  detected: boolean
  /** Whether it is the one currently driving. */
  active: boolean
}

export interface VcsOverview {
  active: VcsInfo | null
  /** `auto`, `none`, or a backend id — what `config.yaml` asks for. */
  configured: string
  backends: VcsBackendOption[]
  branch: string | null
  revision: string | null
}

export interface VcsSettingsUpdate {
  backend?: string
  /** Merged per backend, so sending one key does not clear the others. */
  settings?: Record<string, Record<string, unknown>>
}

export interface VcsCommitRequest {
  message: string
  paths?: string[]
  add_untracked?: boolean
}

export interface VcsCommitResult {
  /** False when there was nothing to commit — an outcome, not an error (§9.6). */
  committed: boolean
  revision: string | null
  branch: string | null
  files: string[]
  message: string
  /**
   * Paths the request named that the workspace did not list as changed — already
   * committed, reverted, or never different — and so were left out rather than handed to a
   * tool that refuses the whole commit over one of them (a task's commit, §10.4). Absent
   * from a backend older than the Commit section.
   */
  skipped?: string[]
}

/**
 * The Studio's own build — the commit it was made from, not the managed project's
 * history. Baked into the wheel at build time, read from git in a source tree; the
 * commit fields are all null when neither could answer.
 */
export interface StudioVersion {
  /** Shared runtime identity: normalized HEAD tag, else short commit; package fallback without git/stamp. */
  version: string
  /** What this build is called: its tag (`v0.2.0`), else the short commit (`c3fd2b1`),
   * else the bare version. Composed on the backend so the UI and `voxarium_studio version`
   * cannot disagree. */
  display: string
  commit: string | null
  short_commit: string | null
  /** A tag on this exact commit, if it has one. */
  tag: string | null
  branch: string | null
  /** ISO-8601 date of the commit itself, not of the build. */
  committed_at: string | null
  /** Built from a tree with uncommitted changes — the hash does not fully describe it. */
  dirty: boolean
  /** `stamp` (from the wheel) | `git` (a source tree) | `unknown`. */
  source: string
}

/**
 * What the Studio's own repository says about newer releases (§12.1).
 *
 * `current` and `latest` are **tags**, because only a tagged commit is a release somebody
 * decided to ship — `origin/main` moving is not an update.
 *
 * `current` is the newest release the **running build** contains, not the newest one in the
 * working tree: pull in a terminal without restarting and the two part company, and only
 * one of them is the code answering this request. Nor is it a tag *on* that commit — a build
 * three commits past v1.0 is on v1.0 as far as updating is concerned, and
 * `StudioVersion.tag` is that other question.
 */
export interface UpdateStatus {
  /** False for a Studio installed as a package — `reason` says so in words. */
  supported: boolean
  reason: string | null
  state: UpdateState
  enabled: boolean
  interval_minutes: number
  current: string | null
  latest: string | null
  available: boolean
  /** When the last check ran, whether or not it found anything. */
  checked_at: string | null
  branch: string | null
  /** The remote branch a pull would take, e.g. `origin/main`. Null means nothing to pull. */
  upstream: string | null
  /** The Studio's checkout has uncommitted changes, so an update needs `force`. */
  dirty: boolean
  /** The command running right now, while `state` is `updating`. */
  step: string | null
  /** Why the last check or update failed. A check keeps its answer alongside this. */
  error: string | null
  log: string[]
}

/**
 * `restarting` is the interesting one: the rebuild is done and the backend is on its way
 * out, so the client holds that state until `/healthz` answers again and then reloads.
 */
export type UpdateState = 'idle' | 'checking' | 'updating' | 'restarting' | 'failed'

/**
 * What each release of the Studio changed — `CHANGELOG.md`, as its Markdown source.
 *
 * The Studio's own file, never the managed project's, which is why it arrives from
 * `/api/changelog` rather than from anything under `/api/docs`.
 */
export interface StudioChangelog {
  /** Where the file is on this machine — the checkout's, or the wheel's own copy. */
  path: string
  text: string
}

export interface ProjectInfo {
  root: string
  name: string
  data_dir: string
  is_git_repo: boolean
  vcs: VcsInfo | null
  auth_required: boolean
  version: StudioVersion
  config: AppConfig
  concurrency_limits: Record<string, number>
  counts: { plugins: number; node_types: number; pipelines: number }
}

/** Live counts across every team in a managed project (§5.1). */
export interface ProjectTaskCounts {
  in_progress: number
  needs_review: number
}

/** An entry in the machine's recently-opened list, enriched with live task counts. */
export interface RecentProject {
  root: string
  name: string
  opened_at: string
  /** Resolved when the list is read: a project the user has since moved comes back dead. */
  exists: boolean
  initialised: boolean
  task_counts: ProjectTaskCounts | null
}

/** A subdirectory in the in-app folder browser. Directories only — never files. */
export interface DirectoryEntry {
  path: string
  name: string
  /** Already holds a `.voxarium_studio/`, so opening it resumes rather than initialises. */
  is_project: boolean
  hidden: boolean
}

export interface DirectoryListing {
  path: string
  name: string
  /** `null` at the top of the tree; `''` means "up to the list of drives". */
  parent: string | null
  is_project: boolean
  entries: DirectoryEntry[]
  truncated: boolean
}

export interface PathInspection {
  path: string
  name: string
  exists: boolean
  is_dir: boolean
  is_project: boolean
}

export interface PickerStatus {
  available: boolean
  /** Why the native dialog is not on offer, when `available` is false. */
  reason: string
  home: string
  default_start: string
}

export interface PickResult {
  path: string | null
  cancelled: boolean
  available: boolean
  reason: string
  inspection?: PathInspection
}

export interface StatusInfo {
  /** Result of the database healthcheck, not a description of it. */
  database: boolean
  event_seq: number
  ws_clients: number
  jobs: { id: string; kind: string; meta: Record<string, unknown> }[]
  agent_backends: Record<string, { available: boolean; reason: string }>
  vcs_backends: Record<
    string,
    { name: string; available: boolean; reason: string; active: boolean }
  >
}

// ---------------------------------------------------------------- MCP servers (§10.7)

export type McpTransport = 'stdio' | 'http' | 'sse'

/**
 * What an entry's badge says. `builtin` is the Studio's own in-process server (§10.4) —
 * not a transport, because there is no channel: the tools run inside this process. It is
 * deliberately part of the same union rather than a separate list, so a component that
 * renders "the servers" cannot render only the ones that came out of a file.
 */
export type McpEntryKind = McpTransport | 'builtin'

/**
 * One entry of `.mcp.json`'s `mcpServers`, as the MCP page renders it. `command`/`args`/
 * `env` belong to `stdio`; `url`/`headers` to `http` and `sse` — the backend sends only
 * the pair that applies, and the empty collections are filled in so the form can bind.
 *
 * …plus one that is in no file at all: the Studio's own `voxarium_studio` server arrives with
 * `builtin: true` and heads the list. It can be disabled like any other (the switch is
 * `config.mcp.disabled`, which is Studio-side anyway) and cannot be edited or removed —
 * there is nothing in `.mcp.json` to edit or remove, and the backend refuses both.
 */
export interface McpServerEntry {
  name: string
  type: McpEntryKind
  /** True only for the Studio's own server: no file entry, no delete, no edit form. */
  builtin: boolean
  /** Tool names the server provides, known ahead of time only for the built-in one. */
  tools: string[]
  enabled: boolean
  /** Whether the command is on PATH. `null` for remote servers — nothing is dialled. */
  available: boolean | null
  /** Where the command resolved, or why it did not; the URL for a remote server. */
  detail: string
  /** `mcp__<name>__` — what this server's tools are called in a permission rule. */
  tool_prefix: string
  command?: string
  args: string[]
  env: Record<string, string>
  url?: string
  headers: Record<string, string>
}

/** Everything the page needs, returned by every endpoint that touches the file. */
export interface McpOverview {
  path: string
  exists: boolean
  /** Set when the document itself will not parse — every write is refused until it does. */
  error: string | null
  /** The file verbatim, for the raw editor. */
  text: string
  strict: boolean
  servers: McpServerEntry[]
  /** Entries present in the file that the Studio cannot read, rather than silently dropped. */
  invalid: { name: string; error: string }[]
}

/** A server as submitted; `type` may be omitted and is inferred as it is in the file. */
export interface McpServerInput {
  type?: McpTransport
  command?: string
  args?: string[]
  env?: Record<string, string>
  url?: string
  headers?: Record<string, string>
}

// ---------------------------------------------------------------- skills (§10.10)

/** A member a skill was given to — enough to draw them with `MemberMark`. */
export interface SkillUser {
  id: string
  team_id: string
  name: string
  kind: MemberKind
  avatar_color: string | null
  avatar_icon: string | null
}

/**
 * A folder of `.voxarium_studio/skills/` holding a `SKILL.md`. Its name is the folder's — the frontmatter's
 * `name` is not read — and it is what members are given and what the prompt calls it.
 */
export interface SkillEntry {
  name: string
  /** Human-facing name; installed skills keep a separate stable assignment id in `name`. */
  display_name?: string
  scope?: 'builtin' | 'project' | 'global'
  /** Native discovery origins. Empty means shared by all backends. */
  backends?: string[]
  readonly?: boolean
  /** Package skills are included automatically; installed skills can be assigned explicitly. */
  automatic?: boolean
  /** Package/plugin or native agent installation; absent for Studio project skills. */
  source?: string | null
  /** Project-relative folder, or an absolute package folder for a built-in. */
  path: string
  /** From the frontmatter, folded onto one line — what a session reads to decide to open it. */
  description: string
  /** Why agents are told less than they could be: no description, a frontmatter that will not parse. */
  warning: string | null
  /** What the folder holds besides `SKILL.md`, shallowest first, up to a hundred. */
  files: string[]
  file_count: number
  /** The count stopped at its ceiling — there are at least this many. */
  file_count_capped: boolean
  /** `SKILL.md`'s modification time; a save sends it back as `expected_updated_at`. */
  updated_at: string
  used_by: SkillUser[]
}

/** A skill with its `SKILL.md`, for the page's editor. */
export interface SkillDetail extends SkillEntry {
  /** The whole file with `\n` line endings, frontmatter included. */
  content: string
  /** The file on disk uses `\r\n` — which a save keeps, so the editor need not care. */
  crlf: boolean
}

/** The Skills page, answered by every endpoint that changes the folder or who has what. */
export interface SkillsOverview {
  /** `.voxarium_studio/skills`, relative to the project root. */
  path: string
  absolute_path: string
  skills: SkillEntry[]
  /** Folders that are not skills yet — no `SKILL.md` — named rather than hidden. */
  invalid: { name: string; error: string; used_by: SkillUser[] }[]
  /** Given to somebody and not in the folder: renamed or deleted outside the Studio. */
  missing: { name: string; used_by: SkillUser[] }[]
}

// ---------------------------------------------------------------- project memory (§10.12)

/** One `.voxarium_studio/memory/<name>.md`: a fact every agent is shown by its description. */
export interface MemoryEntry {
  name: string
  /** The one line every agent's index carries. */
  description: string
  /** Member id of whoever wrote it last; `null` is the user. */
  author: string | null
  /** That member, when the roster still has them — enough to draw with `MemberMark`. */
  author_member: SkillUser | null
  updated_at: string
  /** Length of the text under the frontmatter. */
  chars: number
  /** Why agents are told less than they could be — a hand-written file without a description. */
  warning: string | null
}

export interface MemoryEntryDetail extends MemoryEntry {
  content: string
}

export interface MemoryOverview {
  /** `.voxarium_studio/memory`, relative to the project root. */
  path: string
  enabled: boolean
  /** `teams.memory_index_chars` — the cap on what a run is sent. */
  index_chars: number
  /** The index exactly as a run gets it under the cap, with how many entries made it in. */
  index: { text: string; chars: number; total: number; shown: number }
  entries: MemoryEntry[]
  /** Files in the folder agents cannot be shown — a bad name or unreadable frontmatter. */
  invalid: { file: string; error: string }[]
}

export interface MemoryWrite {
  description: string
  content: string
  /** Refuse with 409 when the name is taken. */
  create?: boolean
  /** Refuse with 409 when the entry changed since this was read. */
  expected_updated_at?: string | null
}

/** One thing an agent runtime remembers on its own, as the entry an import would write. */
export interface AgentMemoryCandidate {
  /** What an import names it by; stable while the runtime's file does not change. */
  id: string
  backend: string
  /** The entry name it would be saved under — settled by the backend, unique in a listing. */
  name: string
  description: string
  content: string
  /** The file it was read from, home written as `~`. */
  source: string
  /** `project` — recorded for this project; `global` — the runtime's memory of everything. */
  scope: 'project' | 'global'
  /** The runtime's own label (`feedback`, `project`, `user`…), when it has one. */
  kind: string | null
  /** Whether a `global` one mentions this project; always true for `project`. */
  related: boolean
  /** The text was longer than an entry may be and was cut. */
  truncated: boolean
  /** `same`: an entry of that name already says this. `differs`: the name is taken by other text. */
  state: 'new' | 'same' | 'differs'
}

export interface AgentMemorySource {
  backend: string
  /** Where it was looked for, shown when nothing was found. */
  locations: string[]
  candidates: AgentMemoryCandidate[]
  /** The list was cut at the backend's limit. */
  truncated: boolean
}

export interface AgentMemoryImportResult {
  imported: string[]
  skipped: { id: string; name: string; reason: 'gone' | 'same' | 'exists' | 'error'; error?: string }[]
  memory: MemoryOverview
}

// ---------------------------------------------------------------- automations (§6.5)

export type AutomationTrigger =
  /** `status: null` is "to anything". */
  | { kind: 'task.status_changed'; status: TaskStatus | null }
  | { kind: 'task.created' }
  /** `path` is project-relative with no `./` — `models`; empty is the whole project. */
  | {
      kind: 'path.changed'
      path: string
      pattern: string
      changes: ('added' | 'modified' | 'deleted')[]
    }
  | { kind: 'run.finished'; pipeline_id: string | null; outcome: 'any' | 'success' | 'failed' }

export type AutomationAction =
  /** Both texts take `{placeholders}`; empty means the window words it itself. */
  | { kind: 'notify'; title: string; message: string }
  | { kind: 'pipeline.run'; pipeline_id: string; use_cache: boolean }
  | { kind: 'plugin.action'; plugin_id: string; action_id: string }

export type AutomationTriggerKind = AutomationTrigger['kind']
export type AutomationActionKind = AutomationAction['kind']

/** What the page sends; the backend chooses the id. */
export interface AutomationInput {
  name: string
  enabled: boolean
  trigger: AutomationTrigger
  action: AutomationAction
}

export interface Automation extends AutomationInput {
  id: string
  /** Why the action cannot run as things stand — a deleted pipeline, a disabled plugin. */
  problem: string | null
}

/** Something a plugin offers as an action (`registry.add_automation_action`). Data, not copy. */
export interface AutomationPluginAction {
  plugin_id: string
  action_id: string
  label: string
  description: string | null
}

/** One firing, as the log keeps it. */
export interface AutomationRun {
  id: string
  automation_id: string
  automation_name: string
  /**
   * `running` while the action's work goes on — a pipeline run, a graph update — and then how it
   * ended; `skipped` is a guard refusing (a loop, a flood) rather than the action failing.
   */
  status: 'running' | 'ok' | 'error' | 'cancelled' | 'skipped'
  cause: string
  detail: string
  /**
   * What the row is worded from, in the reader's language: `cause` and `detail` above are the
   * backend's English and are shown only where nothing here says it (a plugin's answer, an error).
   */
  data: {
    trigger?: AutomationTriggerKind | 'manual'
    action?: AutomationActionKind
    /** The automation as it was when it fired — how a row names one deleted since. */
    definition?: Pick<AutomationInput, 'trigger' | 'action'>
    /** Which guard refused, when `status` is `skipped`. */
    guard?: 'flood' | 'chain'
    /** The Studio stopped while the work was going, so it never finished (`cancelled`). */
    interrupted?: boolean
    values?: Record<string, string>
    run_id?: string
    pipeline_id?: string
  }
  created_at: string
}

/** The Automations page, answered by every endpoint that writes `.voxarium_studio/automations.yaml`. */
export interface AutomationsOverview {
  /** `.voxarium_studio/automations.yaml`, relative to the project root. */
  path: string
  exists: boolean
  /** Why the file could not be read at all; the page then offers no form over it. */
  error: string | null
  automations: Automation[]
  /** Entries of the file that do not validate — kept by every write, run by nothing. */
  invalid: { index: number; id: string | null; error: string }[]
  plugin_actions: AutomationPluginAction[]
  runs: AutomationRun[]
}

/** The *notify* action as it arrives (`automation.notification`). */
export interface AutomationNotification {
  automation_id: string
  automation_name: string
  title: string
  message: string
  trigger: AutomationTriggerKind | 'manual'
  values: Record<string, string>
  /** Where in the Studio a click leads, when the trigger names a place. */
  route: string | null
}

/** Saved Studio input and retained session text (§10.4); per-source counts are estimates. */
export type ContextCategory = 'system' | 'developer' | 'skills' | 'documents' | 'task' | 'history' | 'tools' | 'thinking' | 'summary' | 'memory' | 'environment' | 'tool_definitions' | 'agents' | 'runtime' | 'unattributed'
export interface ContextSource {
  id: string
  category: ContextCategory
  label: string
  text: string
  tokens: number
  truncated: boolean
  text_key?: string
  /** The journal/snapshot field supplying this fragment (§10.4). */
  origin?: string
  /** Kind of record and its recorded name, path or other identifying metadata (§10.4). */
  source_kind?: string
  source_detail?: string
  representation?: 'text' | 'json' | 'metadata'
  provided_by?: 'codex' | 'claude_code' | 'qwen'
}
export type ContextTarget = { chatId: string } | { teamId: string; taskId: string }
export type ContextView = 'auto' | 'studio'
export interface ContextInventory {
  sources: ContextSource[]
  reported_tokens: number | null
  context_window: number | null
  estimated_tokens: number
  captured_at: string | null
  partial: boolean
  origin: 'studio' | 'codex_journal' | 'claude_code_journal' | 'qwen_journal' | 'claude_code_runtime' | 'qwen_runtime'
  /** Local JSONL file supplying the journal view (§10.4). */
  journal_path?: string
  basis: 'estimated' | 'runtime'
  can_inspect: boolean
  has_studio_view?: boolean
}
