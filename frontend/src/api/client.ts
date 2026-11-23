/**
 * Typed fetch clients for immutable project renderers and the legacy `/api` adapter (§5.3).
 *
 * All URLs are relative. In production the SPA is served by the same FastAPI process,
 * and in development Vite proxies `/api` and `/ws` to the backend (see
 * `vite.config.ts`) — so there is exactly one URL shape to reason about, no CORS in
 * the picture, and plugin modules load same-origin in both modes.
 */

import { locale, t } from '@/i18n'
import { authHeaders, withTokenParam } from './auth'
import { ProjectSession, rendererSession, StaleProjectResponseError } from '@/session/project-session'
import { scopedApiUrl, type Query } from './project-scope'
import type {
  ApiBackendConnection,
  ContextInventory,
  ContextTarget,
  ContextView,
  TaskResultType,
  AutomationInput,
  AutomationRun,
  AutomationsOverview,
  AgentChat,
  ProducerPolicy,
  ProducerEffortMode,
  AgentChatMessage,
  AgentMessage,
  ChatContext,
  ChatCreate,
  ChatDetail,
  AppConfig,
  ApplicationSettingsUpdate,
  CreateEntryRequest,
  DeleteRequest,
  DocDetail,
  DocSaved,
  DocumentFence,
  ProjectRemoteStatus,
  ProjectRemoteCommitReceipt,
  ProjectRemoteEditorRegister,
  ProjectRemoteEditorHeartbeat,
  ProjectRemoteEditorLease,
  ProjectRemoteEditorMaterialization,
  ProjectRemoteEditorMaterializationTicket,
  ProjectRemoteEditorMaterializationConfirm,
  ProjectRemoteEditorClosed,
  ProjectRemotePrepareRequest,
  ProjectRemotePreparedChange,
  ProjectRemoteQuiesceRequest,
  ProjectRemoteDrainStatus,
  ProjectRemoteEditorAckRequest,
  ProjectRemoteCommitRequest,
  ProjectRemotePreview,
  ProjectRemotePreviewRequest,
  ProjectRemoteEnableRequest,
  ProjectRemoteRecoverRequest,
  ProjectRemoteRecoverResult,
  ProjectRemoteArchiveRequest,
  ProjectRemoteArchiveResult,
  ProjectRemoteMigrationPlan,
  ProjectRemoteMigrationProfileRequest,
  ProjectRemoteMigrationProfile,
  ProjectRemoteMigrationResolveRequest,
  ProjectRemoteMigrationResolution,
  DocEntry,
  DocFolderDeleted,
  DocFolderRenameDone,
  DocFolderRenameFrame,
  DocFolderStyle,
  DocFolderStyleUpdate,
  DocGraph,
  DocMoveResult,
  DocSearchResult,
  DirectoryListing,
  EntryPathsResponse,
  LayoutUpdate,
  MemberCreate,
  MemberOptions,
  LoadedPlugin,
  McpOverview,
  McpServerInput,
  NodeTypesResponse,
  PathInspection,
  PickResult,
  PickerStatus,
  RecentProject,
  ProjectTaskCounts,
  RemoteControlLink,
  PipelineDefinition,
  PipelineDetail,
  PipelineSummary,
  PluginPage,
  PreviewResult,
  ProjectInfo,
  ScopedProjectInfo,
  AgentMemoryImportResult,
  AgentMemorySource,
  MemoryEntryDetail,
  MemoryOverview,
  MemoryWrite,
  PromptSpec,
  RenameRequest,
  RevealResult,
  Run,
  RunDetail,
  RunRequest,
  RunStartedResponse,
  SearchProviderInfo,
  SearchResponse,
  SettingsUpdate,
  SyncStatus,
  SyncUpdate,
  SkillDetail,
  SkillsOverview,
  StatusInfo,
  StudioChangelog,
  SyntaxGrammar,
  TaskCreate,
  TaskChanges,
  TaskCommitProposal,
  TaskDetail,
  TaskUpdate,
  Team,
  TeamDetail,
  TaskAttachment,
  TeamMember,
  TeamStats,
  TeamTask,
  TransferRequest,
  TreeNode,
  UpdateStatus,
  VcsCommitRequest,
  VcsCommitResult,
  VcsOverview,
  VcsSettingsUpdate,
  VcsStatus,
  MemberUpdate,
} from './types'

type ApiBackendWrite = Omit<ApiBackendConnection, 'id' | 'has_api_key' | 'tpm'> & {
  api_key?: string
  tpm: number | undefined
}

export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly detail: string,
    readonly code: string | null = null,
    readonly details: Record<string, unknown> = {},
  ) {
    super(detail)
    this.name = 'ApiError'
  }

  /** The backend answers 401 only when a token is configured and ours is wrong. */
  get isAuthFailure(): boolean {
    return this.status === 401
  }

  /** Admission refusals are never an invitation to overwrite or adopt a newer G. */
  get isFenceConflict(): boolean {
    return this.status === 409 && this.code !== null && documentFenceErrorCodes.has(this.code)
  }

  /** Legacy disk/name conflicts have no typed admission code (studio-design §5.3). */
  get isDiskConflict(): boolean {
    return this.status === 409 && this.code === null
  }
}

const documentFenceErrorCodes = new Set([
  'reload_required', 'stale_backend_epoch', 'stale_binding_generation', 'mutation_busy',
  'unknown_editor', 'editor_mismatch', 'editor_lease_expired', 'unknown_sid',
  'unknown_collab_session', 'pending_edits', 'stale_editor_version', 'invalid_editor',
  'use_remote_settings',
])

export function sessionContextPath(target: ContextTarget): string {
  return 'chatId' in target
    ? `/chats/${encodeURIComponent(target.chatId)}/context`
    : `/teams/${encodeURIComponent(target.teamId)}/tasks/${encodeURIComponent(target.taskId)}/context`
}

export interface UploadedAttachment extends TaskAttachment { reference: string }

export interface DocAssetAdded {
  /** The reference to write into `![](…)` — percent-encoded, so a space survives it. */
  src: string
  /** The file's name without its suffix, which is what the alt text is set to. */
  alt: string
  name: string
  path: string
  size: number
}


/** A client and all resource helpers share one captured renderer binding (§5.3.2). */
export function createApiClient(session: ProjectSession = rendererSession) {
  /** Uses the same authentication and origin as every other Studio operation (§2.5). */
  function recoverRuntime(frontendVersion: string, backendVersion: string) {
    return request<{ action: 'restart' | 'reload'; version: string }>('POST', '/runtime/recover', {
      body: { frontend_version: frontendVersion, backend_version: backendVersion },
    })
  }


  /** Doc paths are nested (`features/dash.md`), so slashes must survive but nothing else. */
  function encodePath(path: string): string {
    return path
      .split('/')
      .filter(Boolean)
      .map((segment) => encodeURIComponent(segment))
      .join('/')
  }

  function buildUrl(path: string, query?: Query): string {
    return scopedApiUrl(session, path, query)
  }

  const guards = new WeakMap<Response, () => void>()
  async function fetchBound(path: string, init: RequestInit = {}, query?: Query): Promise<Response> {
    const current = session.capture()
    try {
      const response = await fetch(buildUrl(path, query), init)
      current()
      guards.set(response, current)
      return response
    } catch (cause) {
      current()
      throw cause
    }
  }
  async function json<T>(response: Response): Promise<T> {
    try {
      return await response.json() as T
    } finally { guards.get(response)?.() }
  }


  async function request<T>(
    method: string,
    path: string,
    options: { query?: Query; body?: unknown; cache?: RequestCache } = {},
  ): Promise<T> {
    const headers: Record<string, string> = { ...authHeaders() }
    if (options.body !== undefined) headers['Content-Type'] = 'application/json'

    let response: Response
    try {
      response = await fetchBound(path, {
        method,
        headers,
        cache: options.cache,
        body: options.body === undefined ? undefined : JSON.stringify(options.body),
      }, options.query)
    } catch (cause) {
      if (cause instanceof StaleProjectResponseError) throw cause
      // Network-level failure: the backend is down, or was restarted mid-request.
      throw new ApiError(0, cause instanceof Error ? cause.message : t('api.networkError'))
    }

    if (!response.ok) {
      throw await readApiError(response)
    }
    if (response.status === 204) return undefined as T
    return await json<T>(response)
  }

  async function readApiError(response: Response): Promise<ApiError> {
    try {
      const payload = await json<unknown>(response)
      if (typeof payload === 'object' && payload !== null && !Array.isArray(payload)) {
        const { detail } = payload as { detail?: unknown }
        // I4 uses a top-level envelope; accept FastAPI's nested form as well.
        const envelope = typeof detail === 'object' && detail !== null && !Array.isArray(detail)
          ? detail : payload
        const { code, details } = envelope as { code?: unknown; details?: unknown }
        if (typeof code === 'string') {
          return new ApiError(response.status, typeof detail === 'string' ? detail : code, code,
            typeof details === 'object' && details !== null && !Array.isArray(details)
              ? details as Record<string, unknown> : {})
        }
        if (typeof detail === 'string') return new ApiError(response.status, detail)
        if (detail) return new ApiError(response.status, JSON.stringify(detail))
      }
    } catch (cause) {
      if (cause instanceof StaleProjectResponseError) throw cause
      /* fall through to the status text */
    }
    return new ApiError(response.status, response.statusText || `HTTP ${response.status}`)
  }

  /** Project-only local surface; constructing a client never invokes or activates it. */
  function remoteRequest<T>(method: string, path = '', body?: unknown): Promise<T> {
    if (session.isLegacy) return Promise.reject(new ApiError(409, 'reload_required', 'reload_required'))
    return request<T>(method, `/remote${path}`, { body, cache: 'no-store' })
  }

  /** Absolute URL for a raw project file — used for `<img>` and download links. */
  function rawFileUrl(path: string): string {
    return withTokenParam(buildUrl('/explorer/raw', { path }))
  }

  /**
   * URL for a picture a document points at, resolved against that document's own folder.
   *
   * `src` travels exactly as it stands in the Markdown — resolving it is the backend's job
   * (`DocStore.asset`), and rewriting it here would be the editor deciding what the file says.
   *
   * The token rides the query string, as it does for the WebSocket: an `<img>` cannot carry a
   * header, so `rawFileUrl`'s shape would 401 under `--token` — the configuration least likely
   * to be the one anybody tests in.
   */
  function docAssetUrl(document: string, src: string): string {
    return withTokenParam(buildUrl('/docs/asset', { path: document, src }))
  }

  /**
   * Where a review attachment's bytes are (§10.4) — an `<img>` source or a download link, so
   * the token rides the query string as it does for `docAssetUrl`.
   */
  function taskAttachmentUrl(teamId: string, taskId: string, name: string): string {
    return withTokenParam(
      buildUrl(`/teams/${teamId}/tasks/${taskId}/attachments/${encodeURIComponent(name)}`),
    )
  }

  /**
   * Store a file for a review comment; the review names it afterwards (`requestChanges`).
   * Multipart for the reason `uploadDocAsset` is, and with the same rule about `Content-Type`.
   */
  async function uploadTaskAttachment(
    teamId: string,
    taskId: string,
    file: File,
  ): Promise<TaskAttachment> {
    const body = new FormData()
    body.append('file', file)
    let response: Response
    try {
      response = await fetchBound(`/teams/${teamId}/tasks/${taskId}/attachments`, {
        method: 'POST',
        headers: authHeaders(),
        body,
      })
    } catch (cause) {
      if (cause instanceof StaleProjectResponseError) throw cause
      throw new ApiError(0, cause instanceof Error ? cause.message : t('api.networkError'))
    }
    if (!response.ok) throw await readApiError(response)
    return await json<TaskAttachment>(response)
  }



  function attachmentUrl(reference: string): string {
    return withTokenParam(buildUrl(`/attachments/${reference.split('/').map(encodeURIComponent).join('/')}`))
  }

  async function uploadAttachment(file: File): Promise<UploadedAttachment> {
    const body = new FormData()
    body.append('file', file)
    const response = await fetchBound('/attachments', { method: 'POST', headers: authHeaders(), body })
    if (!response.ok) throw await readApiError(response)
    return await json<UploadedAttachment>(response)
  }

  /** What `uploadDocAsset` answers with: the picture as the Markdown should reference it. */

  /**
   * Put a dropped picture beside a document.
   *
   * Multipart rather than JSON: the file is already bytes here, and base64 would inflate a
   * 20 MiB screenshot by a third on the way out. `Content-Type` is deliberately **not** set —
   * the browser has to write it itself, because only it knows the multipart boundary.
   */
  async function uploadDocAsset(document: string, file: File): Promise<DocAssetAdded> {
    const body = new FormData()
    body.append('path', document)
    body.append('file', file)

    let response: Response
    try {
      response = await fetchBound('/docs/asset', {
        method: 'POST',
        headers: authHeaders(),
        body,
      })
    } catch (cause) {
      if (cause instanceof StaleProjectResponseError) throw cause
      throw new ApiError(0, cause instanceof Error ? cause.message : t('api.networkError'))
    }
    if (!response.ok) throw await readApiError(response)
    return await json<DocAssetAdded>(response)
  }

  /**
   * Every JSON value in a newline-delimited stream, handed over as each line completes.
   *
   * A chunk ends wherever the network cut it, not at a line, so what follows the last newline
   * is carried into the next read — and a multi-byte character split across two chunks is put
   * back together by the decoder's `stream` mode rather than turning into two replacement
   * characters. A final line with no newline after it still counts.
   */
  async function readNdjson(
    body: ReadableStream<Uint8Array>,
    onValue: (value: unknown) => void,
  ): Promise<void> {
    const reader = body.getReader()
    const decoder = new TextDecoder()
    let pending = ''
    const flush = (text: string) => {
      const lines = text.split('\n')
      pending = lines.pop() ?? ''
      for (const line of lines) if (line.trim()) onValue(JSON.parse(line))
    }
    try {
      for (;;) {
        const { done, value } = await reader.read()
        if (done) break
        flush(pending + decoder.decode(value, { stream: true }))
      }
      flush(`${pending}${decoder.decode()}\n`)
    } catch (cause) {
      // Stop consuming a disposed renderer's stream; server-side continuations remain owned
      // by the original context. Cancelling transport must not redirect them (§5.3.2).
      void reader.cancel().catch(() => {})
      throw cause
    } finally { reader.releaseLock() }
  }

  /**
   * Rename a folder of the Documents tree and follow the links being mended (§5.2).
   *
   * The answer is a stream, so this is not a `request`: every frame reaches `onFrame` as it
   * arrives, which is what lets the dialog tick documents off while they are written. A refusal
   * — a taken name, a folder the system will not let go of — comes back as an ordinary status
   * and throws `ApiError` before any frame. Resolves with the `done` frame; a stream that ended
   * without one, or said `error`, throws, because the rename then stopped somewhere in the middle.
   */
  async function renameDocFolder(
    path: string,
    name: string,
    onFrame: (frame: DocFolderRenameFrame) => void,
    fence?: DocumentFence,
  ): Promise<DocFolderRenameDone> {
    let response: Response
    try {
      response = await fetchBound('/docs/folders/rename', {
        method: 'POST',
        headers: { ...authHeaders(), 'Content-Type': 'application/json' },
        body: JSON.stringify({ path, name, ...fence }),
      })
    } catch (cause) {
      if (cause instanceof StaleProjectResponseError) throw cause
      throw new ApiError(0, cause instanceof Error ? cause.message : t('api.networkError'))
    }
    if (!response.ok) throw await readApiError(response)
    if (!response.body) throw new ApiError(0, t('api.renameNoBody'))

    // Filled in by the callback, which is why it is an object: a `let` assigned inside a closure
    // stays narrowed to its initial `null` for the type checker.
    const outcome: { done: DocFolderRenameDone | null; error: string | null } = {
      done: null,
      error: null,
    }
    try {
      await readNdjson(response.body, (value) => {
        guards.get(response)?.()
        const frame = value as DocFolderRenameFrame
        if (frame.type === 'done') outcome.done = frame
        if (frame.type === 'error') outcome.error = frame.error
        onFrame(frame)
      })
    } catch (cause) {
      guards.get(response)?.()
      if (cause instanceof StaleProjectResponseError) throw cause
      // The connection dropped part-way. The backend carries on mending regardless — it runs
      // apart from this request — so what is on disk is ahead of what was heard here.
      throw new ApiError(0, cause instanceof Error ? cause.message : t('api.streamCutOff'))
    }
    guards.get(response)?.()
    if (outcome.error !== null) throw new ApiError(500, outcome.error)
    if (outcome.done === null) {
      throw new ApiError(0, t('api.renameUnfinished'))
    }
    return outcome.done
  }

  /**
   * Bytes of a project file. Plugin previewers parse binary formats client-side, and
   * unlike `rawFileUrl` this carries the auth header, so it also works in token mode.
   */
  async function rawFileBytes(path: string): Promise<ArrayBuffer> {
    let response: Response
    try {
      response = await fetchBound('/explorer/raw', { headers: authHeaders() }, { path })
    } catch (cause) {
      if (cause instanceof StaleProjectResponseError) throw cause
      throw new ApiError(0, cause instanceof Error ? cause.message : t('api.networkError'))
    }
    if (!response.ok) throw await readApiError(response)
    try { return await response.arrayBuffer() }
    finally { guards.get(response)?.() }
  }

  const api = {
    // ------------------------------------------------------------ project & settings
    project: async () => {
      const info = await request<ProjectInfo>('GET', '/project', { cache: 'no-store' })
      if (!session.isLegacy) {
        if (typeof info.project_id !== 'string' || typeof info.epoch !== 'string') throw new Error('Missing project scope')
        session.bind({ project_id: info.project_id, epoch: info.epoch })
      }
      return info
    },
    status: () => request<StatusInfo>('GET', '/status'),
    settings: () => request<AppConfig>('GET', '/settings'),
    updateSettings: (body: SettingsUpdate) => request<AppConfig>('PUT', '/settings', { body }),
    updateApplicationSettings: async (body: ApplicationSettingsUpdate) => {
      const updated = await request<AppConfig>('PUT', '/settings/application', { body })
      // The global write's legacy response includes that selection's project fields.
      // Keep preferences global, but never hydrate A with B's accent/agents (§5.3.3).
      return session.isLegacy ? updated : request<AppConfig>('GET', '/settings')
    },
    /** The built-in text behind every line the Studio says to an agent, in send order. */
    apiBackends: () => request<ApiBackendConnection[]>('GET', '/settings/api-backends', { cache: 'no-store' }),
    saveApiBackend: (body: ApiBackendWrite & { id: string }) =>
      request<ApiBackendConnection>('PUT', `/settings/api-backends/${body.id}`, {
        body: {
          name: body.name, base_url: body.base_url, model: body.model, models: body.models,
          api_mode: body.api_mode, icon: body.icon, context_window: body.context_window,
          use_limits: body.use_limits, tpm: body.tpm,
          ...(body.api_key !== undefined ? { api_key: body.api_key } : {}),
        } satisfies ApiBackendWrite,
      }),
    deleteApiBackend: (id: string) => request<{ deleted: boolean }>('DELETE', `/settings/api-backends/${id}`),
    promptSpecs: () => request<Record<string, PromptSpec>>('GET', '/settings/prompts'),
    reloadSettings: () =>
      request<{ config: AppConfig; plugins: LoadedPlugin[] }>('POST', '/settings/reload'),
    /** What each release changed, as Markdown. The Studio's file, not the project's (§12.1). */
    changelog: () => request<StudioChangelog>('GET', '/changelog'),

    // ------------------------------------------------------------ updating the Studio
    // The Studio's own repository, never the managed project's (§12.1).
    updates: () => request<UpdateStatus>('GET', '/updates'),
    /** Fetches now instead of waiting for the next scheduled check. */
    checkForUpdates: () => request<UpdateStatus>('POST', '/updates/check'),
    /**
     * Pull, rebuild, restart. Answers as soon as it starts — the rest arrives as
     * `update.status` and `update.log` events, and the server goes away at the end of it.
     * A 409 means the Studio refused: uncommitted changes, or work a restart would kill.
     */
    applyUpdate: (force = false) => request<UpdateStatus>('POST', '/updates/apply', { body: { force } }),

    // ------------------------------------------------------------ opening a project
    ensureProject: async (path: string, create = false) => {
      const info = await request<ScopedProjectInfo>('POST', '/projects/open', { body: { path, create } })
      // Validate the descriptor without rebinding this renderer or legacy selection.
      new ProjectSession(info.project_id).bind(info)
      return info
    },
    recentProjects: () => request<RecentProject[]>('GET', '/project/recent'),
    projectTaskCounts: () => request<ProjectTaskCounts | null>('GET', '/project/task-counts'),
    forgetRecentProject: (path: string) =>
      request<RecentProject[]>('DELETE', '/project/recent', { query: { path } }),
    /** Swaps the whole backend context. Everything fetched before it is stale afterwards. */
    openProject: (path: string, create = true) =>
      request<ProjectInfo>('POST', '/project/open', { body: { path, create } }),

    browseDirectory: (path = '') =>
      request<DirectoryListing>('GET', '/fs/browse', { query: { path } }),
    inspectPath: (path: string) => request<PathInspection>('GET', '/fs/inspect', { query: { path } }),
    pickerStatus: () => request<PickerStatus>('GET', '/fs/picker'),
    /**
     * Opens the machine's own folder dialog and resolves when the user answers it — so
     * this call is pending for as long as that window is on screen, by design.
     */
    pickDirectory: (startPath?: string) =>
      request<PickResult>('POST', '/fs/pick', { body: { start_path: startPath ?? null } }),

    // ------------------------------------------------------------ explorer
    tree: (path = '', depth = 1) => request<TreeNode>('GET', '/explorer/tree', { query: { path, depth } }),
    preview: (path: string) => request<PreviewResult>('GET', '/explorer/file', { query: { path } }),

    // Operations on the project's own files. None of them publish an event of their own —
    // the file watcher sees the change and `explorer.file_changed` follows, which is the
    // same path an external edit or a pipeline node takes.
    createEntry: (body: CreateEntryRequest) =>
      request<EntryPathsResponse>('POST', '/explorer/create', { body }),
    renameEntry: (body: RenameRequest) =>
      request<EntryPathsResponse>('POST', '/explorer/rename', { body }),
    moveEntries: (body: TransferRequest) =>
      request<EntryPathsResponse>('POST', '/explorer/move', { body }),
    copyEntries: (body: TransferRequest) =>
      request<EntryPathsResponse>('POST', '/explorer/copy', { body }),
    deleteEntries: (body: DeleteRequest) =>
      request<EntryPathsResponse>('POST', '/explorer/delete', { body }),
    /** Show the entry in the machine's file manager. Loopback-only — 409 from elsewhere. */
    revealEntry: (path: string) =>
      request<RevealResult>('POST', '/explorer/reveal', { body: { path } }),

    // ------------------------------------------------------------ version control
    vcs: () => request<VcsOverview>('GET', '/vcs'),
    vcsStatus: () => request<VcsStatus>('GET', '/vcs/status'),
    updateVcs: (body: VcsSettingsUpdate) => request<VcsOverview>('PUT', '/vcs/settings', { body }),
    vcsCommit: (body: VcsCommitRequest) => request<VcsCommitResult>('POST', '/vcs/commit', { body }),
    vcsDiff: (path?: string) =>
      request<{ diff: string }>('GET', '/vcs/diff', { query: { path: path ?? null } }),

    // ------------------------------------------------------------ pipelines
    nodeTypes: () => request<NodeTypesResponse>('GET', '/node-types'),
    pipelines: () => request<PipelineSummary[]>('GET', '/pipelines'),
    pipeline: (id: string) => request<PipelineDetail>('GET', `/pipelines/${id}`),
    createPipeline: (body: PipelineDefinition) =>
      request<PipelineDefinition>('POST', '/pipelines', { body }),
    savePipeline: (id: string, body: PipelineDefinition) =>
      request<PipelineDetail>('PUT', `/pipelines/${id}`, { body }),
    deletePipeline: (id: string) => request<void>('DELETE', `/pipelines/${id}`),
    runPipeline: (id: string, body: RunRequest = {}) =>
      request<RunStartedResponse>('POST', `/pipelines/${id}/run`, { body }),

    // ------------------------------------------------------------ runs
    runs: (query: { pipeline_id?: string; limit?: number } = {}) =>
      request<Run[]>('GET', '/runs', { query }),
    run: (runId: string) => request<RunDetail>('GET', `/runs/${runId}`),
    nodeLog: (runId: string, nodeId: string) =>
      request<{ run_id: string; node_id: string; text: string; exists: boolean }>(
        'GET',
        `/runs/${runId}/nodes/${nodeId}/log`,
      ),
    resumeRun: (runId: string) =>
      request<{ run_id: string; resumed: boolean }>('POST', `/runs/${runId}/resume`),
    cancelRun: (runId: string) =>
      request<{ run_id: string; cancelled: boolean }>('POST', `/runs/${runId}/cancel`),
    clearAssetCache: () =>
      request<{ removed: number; bytes: number; kept: number; failed: number }>('DELETE', '/asset-cache'),
    clearNodeCache: (nodeType?: string) =>
      request<{ removed: number }>('DELETE', '/node-cache', { query: { node_type: nodeType } }),

    // ------------------------------------------------------------ plugins
    plugins: () => request<LoadedPlugin[]>('GET', '/plugins'),
    pluginPages: () => request<PluginPage[]>('GET', '/plugins/pages'),
    /** Languages plugins taught the Studio to highlight — TextMate grammars (§6.2). */
    pluginGrammars: () => request<SyntaxGrammar[]>('GET', '/plugins/grammars'),
    enablePlugin: (id: string) => request<LoadedPlugin>('POST', `/plugins/${id}/enable`),
    disablePlugin: (id: string) => request<LoadedPlugin>('POST', `/plugins/${id}/disable`),
    pluginSettings: (id: string) =>
      request<{ id: string; settings: Record<string, unknown> }>('GET', `/plugins/${id}/settings`),
    savePluginSettings: (id: string, settings: Record<string, unknown>) =>
      request<{ id: string; settings: Record<string, unknown> }>('PUT', `/plugins/${id}/settings`, {
        body: { settings },
      }),
    installPluginEnv: (id: string, force = false) =>
      request<{ id: string; provisioned: boolean; python?: string; log: string[]; detail?: string }>(
        'POST',
        `/plugins/${id}/install-env`,
        { query: { force } },
      ),
    /**
     * Call an action a plugin registered — its own endpoint (§9.7). The answer is whatever
     * the handler returned, unwrapped: the plugin's page knows its own shapes.
     */
    pluginAction: (id: string, action: string, payload?: object) =>
      request<unknown>('POST', `/plugins/${id}/actions/${action}`, { body: payload ?? {} }),

    // ------------------------------------------------------------ search
    /** Who answers the rail's search field. Empty means the field is not shown (§6.1). */
    searchProviders: () => request<SearchProviderInfo[]>('GET', '/search/providers'),
    /** `deep` is Enter rather than a keystroke — the provider may take seconds and answer in prose. */
    search: (q: string, options: { limit?: number; deep?: boolean } = {}) =>
      request<SearchResponse>('GET', '/search', {
        query: { q, limit: options.limit, deep: options.deep ? true : undefined },
      }),

    // ------------------------------------------------------------ chats (§10.9)
    /** Every chat, newest activity first. */
    chats: () => request<AgentChat[]>('GET', '/chats'),
    chat: (chatId: string) => request<ChatDetail>('GET', `/chats/${chatId}`),
    createChat: (body: ChatCreate) => request<AgentChat>('POST', '/chats', { body }),
    setChatModel: (chatId: string, agentBackend: string | null, model: string | null) =>
      request<AgentChat>('PATCH', `/chats/${chatId}/model`, { body: { agent_backend: agentBackend, model } }),
    useProducerWorkspace: (chatId: string, locale: string) =>
      request<AgentChat>('POST', `/chats/${chatId}/producer/workspace`, { body: { locale } }),
    startProducer: (chatId: string, locale: 'en' | 'ru', policy?: ProducerPolicy, estimateTaskCostUsd?: number, effortMode?: ProducerEffortMode) =>
      request<AgentChat>('POST', `/chats/${chatId}/producer/start`, { body: { locale, policy, estimate_task_cost_usd: estimateTaskCostUsd, effort_mode: effortMode } }),
    setProducerSettings: (chatId: string, estimateTaskCostUsd: number) =>
      request<AgentChat>('PATCH', `/chats/${chatId}/producer/settings`, { body: { estimate_task_cost_usd: estimateTaskCostUsd } }),
    setProducerPolicy: (chatId: string, policy: ProducerPolicy) =>
      request<AgentChat>('PATCH', `/chats/${chatId}/producer/policy`, { body: { policy } }),
    setProducerEffortMode: (chatId: string, mode: ProducerEffortMode) =>
      request<AgentChat>('PATCH', `/chats/${chatId}/producer/effort`, { body: { mode } }),
    stopProducer: (chatId: string) => request<AgentChat>('POST', `/chats/${chatId}/producer/stop`),
    pauseProducer: (chatId: string) => request<AgentChat>('POST', `/chats/${chatId}/producer/pause`),
    resumeProducer: (chatId: string) => request<AgentChat>('POST', `/chats/${chatId}/producer/resume`),
    approveProducerPlan: (chatId: string, version: number) =>
      request<AgentChat>('POST', `/chats/${chatId}/producer/approve`, { body: { version } }),
    deleteChat: (chatId: string) =>
      request<{ chat_id: string; deleted: boolean }>('DELETE', `/chats/${chatId}`),
    /**
     * Say something to the member. Answers as soon as the turn has started (202); the reply
     * streams as `team.chat.message` events. A 409 means the member is still answering.
     */
    sendChatMessage: (chatId: string, text: string, context: ChatContext, attachments: string[] = []) =>
      request<{ chat: AgentChat; message: AgentChatMessage }>(
        'POST',
        `/chats/${chatId}/messages`,
        { body: { text, context, attachments } },
      ),
    cancelChat: (chatId: string) =>
      request<{ chat_id: string; cancelled: boolean }>('POST', `/chats/${chatId}/cancel`),

    // ------------------------------------------------------------ teams
    /** Note: the list endpoint returns members but no tasks — fetch a team for those. */
    teams: () => request<Team[]>('GET', '/teams'),
    team: (teamId: string) => request<TeamDetail>('GET', `/teams/${teamId}`),
    setAutostartTasks: (teamId: string, enabled: boolean) =>
      request<TeamDetail>('PATCH', `/teams/${teamId}`, { body: { autostart_tasks: enabled } }),
    startAllTasks: (teamId: string) =>
      request<{ team_id: string; task_ids: string[] }>('POST', `/teams/${teamId}/tasks/start-all`, {
        body: { summary_locale: locale.value },
      }),
    /** What the team's work has cost, over time and by member — archived tasks included. */
    teamStats: (teamId: string) => request<TeamStats>('GET', `/teams/${teamId}/stats`),
    memberOptions: () => request<MemberOptions>('GET', '/teams/member-options'),
    updateMember: (teamId: string, memberId: string, body: MemberUpdate) =>
      request<TeamMember>('PATCH', `/teams/${teamId}/members/${memberId}`, { body }),
    saveLayout: (teamId: string, positions: LayoutUpdate['positions']) =>
      request<{ team_id: string; updated: number }>('PATCH', `/teams/${teamId}/layout`, {
        body: { positions },
      }),
    createMember: (teamId: string, body: MemberCreate) =>
      request<TeamMember>('POST', `/teams/${teamId}/members`, { body }),
    deleteMember: (teamId: string, memberId: string, reassignToDirector = false) =>
      request<{ member_id: string; removed: boolean }>(
        'DELETE',
        `/teams/${teamId}/members/${memberId}`,
        { query: { reassign_to_director: reassignToDirector } },
      ),
    createTask: (teamId: string, body: TaskCreate) =>
      request<TeamTask>('POST', `/teams/${teamId}/tasks`, {
        body: { ...body, summary_locale: locale.value },
      }),
    tasks: (teamId: string, status?: string) =>
      request<TeamTask[]>('GET', `/teams/${teamId}/tasks`, { query: { status } }),
    task: (teamId: string, taskId: string) =>
      request<TaskDetail>('GET', `/teams/${teamId}/tasks/${taskId}`),
    sessionContext: (target: ContextTarget, view: ContextView = 'auto') =>
      request<ContextInventory>('GET', sessionContextPath(target), { query: { view } }),
    sessionContextFragment: (target: ContextTarget, sourceId: string, key: string, view: ContextView = 'auto') =>
      request<{ text: string }>('GET', `${sessionContextPath(target)}/fragments/${sourceId}`, { query: { key, view } }),
    inspectSessionContext: (target: ContextTarget) =>
      request<ContextInventory>('POST', `${sessionContextPath(target)}/inspect`),
    updateTask: (teamId: string, taskId: string, body: TaskUpdate) =>
      request<TeamTask>('PATCH', `/teams/${teamId}/tasks/${taskId}`, {
        // Release captures the reader's language for the deferred run (§10.4).
        body: { ...body, ...(body.status === 'pending' ? { summary_locale: locale.value } : {}) },
      }),
    deleteTask: (teamId: string, taskId: string, actorId: string | null = null) =>
      request<{ task_id: string; deleted: boolean }>(
        'DELETE',
        `/teams/${teamId}/tasks/${taskId}`,
        { query: { actor_id: actorId } },
      ),
    startTask: (teamId: string, taskId: string) =>
      request<{ task_id: string; status: string }>('POST', `/teams/${teamId}/tasks/${taskId}/start`, {
        body: { summary_locale: locale.value },
      }),
    /**
     * Wipe the session, transcript, summary and cost, keeping the task and its brief (§10.4).
     * The task comes back in `todo`, so the next run starts from nothing.
     */
    resetTask: (teamId: string, taskId: string, actorId: string | null = null) =>
      request<TeamTask>('POST', `/teams/${teamId}/tasks/${taskId}/reset`, {
        body: { actor_id: actorId },
      }),
    compressTask: (teamId: string, taskId: string, actorId: string | null = null) =>
      request<TeamTask>('POST', `/teams/${teamId}/tasks/${taskId}/compress`, {
        body: { actor_id: actorId },
      }),
    approveTask: (teamId: string, taskId: string, comment = '', actorId: string | null = null) =>
      request<TeamTask>('POST', `/teams/${teamId}/tasks/${taskId}/approve`, {
        body: { comment, actor_id: actorId },
      }),
    requestChanges: (
      teamId: string,
      taskId: string,
      comment: string,
      actorId: string | null = null,
      attachments: string[] = [],
    ) =>
      request<TeamTask>('POST', `/teams/${teamId}/tasks/${taskId}/request-changes`, {
        body: { comment, actor_id: actorId, attachments, summary_locale: locale.value },
      }),
    /** Take back a file uploaded for a review that was not sent with it. */
    removeTaskAttachment: (teamId: string, taskId: string, name: string) =>
      request<void>(
        'DELETE',
        `/teams/${teamId}/tasks/${taskId}/attachments/${encodeURIComponent(name)}`,
      ),

    // Human members: claim / note / complete stand in for an agent's session lifecycle.
    claimTask: (teamId: string, taskId: string, actorId: string | null = null) =>
      request<TeamTask>('POST', `/teams/${teamId}/tasks/${taskId}/claim`, {
        body: { actor_id: actorId },
      }),
    addNote: (teamId: string, taskId: string, text: string, actorId: string | null = null) =>
      request<AgentMessage>('POST', `/teams/${teamId}/tasks/${taskId}/notes`, {
        body: { text, actor_id: actorId },
      }),
    completeTask: (
      teamId: string,
      taskId: string,
      summary = '',
      actorId: string | null = null,
      resultType: TaskResultType = 'done',
    ) =>
      request<TeamTask>('POST', `/teams/${teamId}/tasks/${taskId}/complete`, {
        body: { summary, actor_id: actorId, result_type: resultType },
      }),
    cancelTask: (teamId: string, taskId: string) =>
      request<{ task_id: string; cancelled: boolean }>(
        'POST',
        `/teams/${teamId}/tasks/${taskId}/cancel`,
      ),
    // Remote Control: the running session, driven from claude.ai/code or the phone (§10.8).
    enableRemoteControl: (teamId: string, taskId: string) =>
      request<RemoteControlLink>('POST', `/teams/${teamId}/tasks/${taskId}/remote-control`),
    disableRemoteControl: (teamId: string, taskId: string) =>
      request<RemoteControlLink>('DELETE', `/teams/${teamId}/tasks/${taskId}/remote-control`),
    /** What this task changed — its own checkout where it has one, its tool calls where not. */
    taskChanges: (teamId: string, taskId: string) =>
      request<TaskChanges>('GET', `/teams/${teamId}/tasks/${taskId}/changes`),
    /** One file of that, as a patch to render. `path` is as `taskChanges` reported it. */
    taskPatch: (teamId: string, taskId: string, path: string) =>
      request<{ path: string; patch: string }>(
        'GET',
        `/teams/${teamId}/tasks/${taskId}/changes/patch`,
        { query: { path } },
      ),
    /** The commit drafted for the task when it reached review (§10.4). */
    taskCommit: (teamId: string, taskId: string) =>
      request<TaskCommitProposal>('GET', `/teams/${teamId}/tasks/${taskId}/commit`),
    /** Draft it again — what arriving in review does, on request. */
    regenerateTaskCommit: (teamId: string, taskId: string) =>
      request<TaskCommitProposal>('POST', `/teams/${teamId}/tasks/${taskId}/commit/regenerate`),
    /** Commit through the project's version control; `committed: false` means nothing was left. */
    commitTask: (teamId: string, taskId: string, body: { message?: string; files?: string[] }) =>
      request<VcsCommitResult>('POST', `/teams/${teamId}/tasks/${taskId}/commit`, { body }),
    removeWorktree: (teamId: string, taskId: string, force = false) =>
      request<{ task_id: string; removed: boolean }>(
        'DELETE',
        `/teams/${teamId}/tasks/${taskId}/worktree`,
        { query: { force } },
      ),

    // ------------------------------------------------------------ online sync (§16)
    sync: () => request<SyncStatus>('GET', '/sync'),
    updateSync: (body: SyncUpdate) => request<SyncStatus>('PUT', '/sync', { body }),

    // studio-design §5.2: project observation/leases exist at I4; the remaining
    // commands are transport contracts only, consumed after their release gates.
    projectRemote: () => remoteRequest<ProjectRemoteStatus>('GET'),
    registerRemoteEditor: (body: ProjectRemoteEditorRegister) =>
      remoteRequest<ProjectRemoteEditorLease>('POST', '/editors', body),
    heartbeatRemoteEditor: (editorId: string, body: ProjectRemoteEditorHeartbeat) =>
      remoteRequest<ProjectRemoteEditorLease>('POST', `/editors/${encodeURIComponent(editorId)}/heartbeat`, body),
    materializeRemoteEditor: (editorId: string, body: ProjectRemoteEditorMaterialization) =>
      remoteRequest<ProjectRemoteEditorMaterializationTicket>('POST', `/editors/${encodeURIComponent(editorId)}/materialization`, body),
    confirmRemoteEditorMaterialization: (editorId: string, body: ProjectRemoteEditorMaterializationConfirm) =>
      remoteRequest<ProjectRemoteEditorLease>('POST', `/editors/${encodeURIComponent(editorId)}/materialization/confirm`, body),
    deleteRemoteEditor: (editorId: string, body: ProjectRemoteEditorHeartbeat) =>
      remoteRequest<ProjectRemoteEditorClosed>('DELETE', `/editors/${encodeURIComponent(editorId)}`, body),
    prepareRemoteChange: (body: ProjectRemotePrepareRequest) =>
      remoteRequest<ProjectRemotePreparedChange>('POST', '/prepare', body),
    quiesceRemoteChange: (changeId: string, body: ProjectRemoteQuiesceRequest) =>
      remoteRequest<ProjectRemoteDrainStatus>('POST', `/changes/${encodeURIComponent(changeId)}/quiesce`, body),
    ackRemoteEditor: (changeId: string, body: ProjectRemoteEditorAckRequest) =>
      remoteRequest<void>('POST', `/changes/${encodeURIComponent(changeId)}/editor-ack`, body),
    commitRemoteChange: (changeId: string, body: ProjectRemoteCommitRequest) =>
      remoteRequest<ProjectRemoteCommitReceipt>('POST', `/changes/${encodeURIComponent(changeId)}/commit`, body),
    cancelRemoteChange: (changeId: string) =>
      remoteRequest<void>('DELETE', `/changes/${encodeURIComponent(changeId)}`),
    previewRemoteReconciliation: (body: ProjectRemotePreviewRequest) =>
      remoteRequest<ProjectRemotePreview>('POST', '/reconcile-preview', body),
    enableProjectRemote: (body: ProjectRemoteEnableRequest) =>
      remoteRequest<ProjectRemoteStatus>('POST', '/enable', body),
    /** Explicit recovery of an interrupted binding change; never enables or transfers (§16.10). */
    recoverProjectRemote: (body: ProjectRemoteRecoverRequest) =>
      remoteRequest<ProjectRemoteRecoverResult>('POST', '/recover', body),
    /** Renames (keeps) a v2 checkpoint of another Remote incarnation; consumes the preview. */
    archiveProjectRemoteCheckpoint: (body: ProjectRemoteArchiveRequest) =>
      remoteRequest<ProjectRemoteArchiveResult>('POST', '/checkpoint/archive', body),
    remoteMigration: () => remoteRequest<ProjectRemoteMigrationPlan>('GET', '/migration'),
    selectRemoteMigrationProfile: (planId: string, body: ProjectRemoteMigrationProfileRequest) =>
      remoteRequest<ProjectRemoteMigrationProfile>('POST', `/migration/${encodeURIComponent(planId)}/profile`, body),
    resolveRemoteMigration: (planId: string, body: ProjectRemoteMigrationResolveRequest) =>
      remoteRequest<ProjectRemoteMigrationResolution>('POST', `/migration/${encodeURIComponent(planId)}/resolve`, body),

    // ------------------------------------------------------------ project docs
    docs: () => request<DocEntry[]>('GET', '/docs'),
    doc: (path: string) => request<DocDetail>('GET', `/docs/${encodePath(path)}`),
    /**
     * `expectedUpdatedAt` makes the write conditional — the backend answers 409 rather than
     * overwriting a file that changed since it was read. The editor sends it; anything
     * creating a document from scratch has nothing to compare against, and uses `createDoc`.
     */
    saveDoc: (
      path: string,
      content: string,
      author: string | null = null,
      expectedUpdatedAt: string | null = null,
      /**
       * The live room this Markdown was written from, when the editor saving it is the
       * room's scribe (§16.4) — so sync pushes it as the room's own text, not as an edit
       * from outside that would end the room for everyone in it.
       */
      collabSession: string | null = null,
      fence?: DocumentFence,
    ) =>
      request<DocSaved>('PUT', `/docs/${encodePath(path)}`, {
        body: {
          content,
          author,
          expected_updated_at: expectedUpdatedAt,
          collab_session: collabSession,
          ...fence,
        },
      }),
    /**
     * A new document, refused with 409 when anything is already at the path. A write with no
     * expectation replaces what is there, which is right for a save and loses a document for a
     * "New document" given a name that is taken.
     */
    createDoc: (path: string, content: string, fence?: DocumentFence) =>
      request<DocSaved>('PUT', `/docs/${encodePath(path)}`, {
        body: { content, exclusive: true, ...fence },
      }),
    deleteDoc: (path: string, fence?: DocumentFence) =>
      request<{ path: string; deleted: boolean }>('DELETE', `/docs/${encodePath(path)}`, {
        query: fence === undefined ? undefined : {
          backend_epoch: fence.backend_epoch,
          expected_binding_generation: fence.expected_binding_generation,
          editor_id: fence.editor_id, client_instance_id: fence.client_instance_id, sid: fence.sid,
        },
      }),
    moveDoc: (source: string, target: string, fence?: DocumentFence) =>
      request<DocMoveResult>('POST', '/docs/move', { body: { source, target, ...fence } }),
    searchDocs: (q: string) => request<DocSearchResult[]>('GET', '/docs/search', { query: { q } }),
    /**
     * The whole corpus's link graph, for the side panel's picture (§5). Declared before the
     * `/{path:path}` catch-all on the backend, exactly as `/search` is, or the route would
     * be read as a document called `graph`.
     */
    docsGraph: () => request<DocGraph>('GET', '/docs/graph'),
    /** The folders of the tree that have a colour or an icon — no others are listed (§5.2). */
    docFolders: () => request<DocFolderStyle[]>('GET', '/docs/folders'),
    setDocFolderStyle: (body: DocFolderStyleUpdate, fence?: DocumentFence) =>
      request<DocFolderStyle>('POST', '/docs/folders/style', { body: { ...body, ...fence } }),
    renameDocFolder,
    /**
     * Delete a folder with everything in it. 409 means the system would not let go of it and
     * nothing was removed; a 500 may have stopped part-way.
     */
    deleteDocFolder: (path: string, fence?: DocumentFence) =>
      request<DocFolderDeleted>('POST', '/docs/folders/delete', { body: { path, ...fence } }),

    // ------------------------------------------------------------ MCP servers (§10.7)
    /**
     * Every call answers with the whole page. `.mcp.json` is edited by hand and by the
     * `claude` CLI as well as from here, so patching local state from a narrower response
     * would drift from the file within one edit.
     */
    mcp: () => request<McpOverview>('GET', '/mcp'),
    saveMcpServer: (name: string, body: McpServerInput) =>
      request<McpOverview>('PUT', `/mcp/servers/${encodeURIComponent(name)}`, { body }),
    deleteMcpServer: (name: string) =>
      request<McpOverview>('DELETE', `/mcp/servers/${encodeURIComponent(name)}`),
    /** Withholds the server from agent sessions; it stays in the file for the terminal. */
    setMcpServerEnabled: (name: string, enabled: boolean) =>
      request<McpOverview>(
        'POST',
        `/mcp/servers/${encodeURIComponent(name)}/${enabled ? 'enable' : 'disable'}`,
      ),
    saveMcpFile: (content: string) => request<McpOverview>('PUT', '/mcp/file', { body: { content } }),
    setMcpStrict: (strict: boolean) =>
      request<McpOverview>('PATCH', '/mcp/settings', { body: { strict } }),

    // ------------------------------------------------------------ skills (§10.10)
    /**
     * `.voxarium_studio/skills/` with who has been given what. The folder is changed by copies and
     * checkouts as often as from here, so the calls that change it answer with the whole page.
     */
    skills: () => request<SkillsOverview>('GET', '/skills'),
    /** A new skill folder with its `SKILL.md`. Given to nobody — that is the member editor's. */
    createSkill: (name: string, description: string) =>
      request<SkillsOverview>('POST', '/skills', { body: { name, description } }),
    skill: (name: string) => request<SkillDetail>('GET', `/skills/${encodeURIComponent(name)}`),
    /** 409 when `SKILL.md` changed on disk since `expectedUpdatedAt` was read. */
    saveSkill: (name: string, content: string, expectedUpdatedAt: string | null) =>
      request<SkillDetail>('PUT', `/skills/${encodeURIComponent(name)}`, {
        body: { content, expected_updated_at: expectedUpdatedAt },
      }),
    /** The folder with everything in it, and the name off every member who had it. */
    deleteSkill: (name: string) =>
      request<SkillsOverview>('DELETE', `/skills/${encodeURIComponent(name)}`),

    // ------------------------------------------------------------ project memory (§10.12)
    /** `.voxarium_studio/memory/` with the index agents are sent; the switch and cap are team settings. */
    memory: () => request<MemoryOverview>('GET', '/memory'),
    memoryEntry: (name: string) =>
      request<MemoryEntryDetail>('GET', `/memory/${encodeURIComponent(name)}`),
    /** 409 when `create` finds the name taken, or the entry changed since `expected_updated_at`. */
    saveMemoryEntry: (name: string, body: MemoryWrite) =>
      request<MemoryEntryDetail>('PUT', `/memory/${encodeURIComponent(name)}`, { body }),
    deleteMemoryEntry: (name: string) =>
      request<MemoryOverview>('DELETE', `/memory/${encodeURIComponent(name)}`),
    /** What Claude Code, Codex and Qwen Code remember on their own, as entries to import. */
    agentMemory: () => request<{ sources: AgentMemorySource[] }>('GET', '/agent-memory'),
    /** Writes the chosen ones; `replace` lets one overwrite the entry already under its name. */
    importAgentMemory: (items: { id: string; replace: boolean }[]) =>
      request<AgentMemoryImportResult>('POST', '/agent-memory/import', { body: { items } }),

    // ------------------------------------------------------------ automations (§6.5)
    /**
     * `.voxarium_studio/automations.yaml` with what it has done. Every write answers with the whole page:
     * the file is edited by hand and by merges too, so the list is never patched client-side.
     */
    automations: () => request<AutomationsOverview>('GET', '/automations'),
    createAutomation: (body: AutomationInput) =>
      request<AutomationsOverview>('POST', '/automations', { body }),
    updateAutomation: (id: string, body: AutomationInput) =>
      request<AutomationsOverview>('PUT', `/automations/${encodeURIComponent(id)}`, { body }),
    /** The card's switch — its own route, so a toggle never writes back a stale form. */
    setAutomationEnabled: (id: string, enabled: boolean) =>
      request<AutomationsOverview>('PATCH', `/automations/${encodeURIComponent(id)}`, {
        body: { enabled },
      }),
    deleteAutomation: (id: string) =>
      request<AutomationsOverview>('DELETE', `/automations/${encodeURIComponent(id)}`),
    /** The action, now, with nothing having happened — works on a disabled automation too. */
    runAutomation: (id: string) =>
      request<{ run: AutomationRun }>('POST', `/automations/${encodeURIComponent(id)}/run`),
    clearAutomationRuns: () => request<AutomationsOverview>('DELETE', '/automations/runs'),
  }

    return { ...api, recoverRuntime, rawFileUrl, docAssetUrl, taskAttachmentUrl,
      uploadTaskAttachment, attachmentUrl, uploadAttachment, uploadDocAsset,
      readNdjson, renameDocFolder, rawFileBytes }
}

export const api = createApiClient()
export const { recoverRuntime, rawFileUrl, docAssetUrl, taskAttachmentUrl,
  uploadTaskAttachment, attachmentUrl, uploadAttachment, uploadDocAsset,
  readNdjson, renameDocFolder, rawFileBytes } = api
