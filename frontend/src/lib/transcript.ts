/**
 * How much of a transcript is on screen, and how it is grouped (spec §10.4).
 *
 * Every conversation with an agent goes through here — a task, an ordinary chat and the
 * Producer's — so all of them read the same way at the same setting (§10.9).
 *
 * One dial with two detents (Compact was removed on 2026-11-10, by the user's request).
 * **Default** is what was said, with the work around it as folded blocks — each thinking
 * block, and each run of consecutive tool calls as one "Tools used: N" — both drawn
 * collapsed, except permanently open subagent launches (§10.4). **Verbose** filters nothing:
 * tool results sit under their calls, the Studio's own system notices are rows, and the
 * same folds are drawn open.
 *
 * Below Verbose, one line at the bottom carries the tail of the *whole* feed — the newest
 * thing logged, whether or not this level draws a row for it, flattened to one line and
 * flashed. It is a window onto Verbose rather than an entry in the log: nothing about it is
 * kept, and turning the dial up is what puts the same lines in the transcript for good.
 *
 * The levels nest — default ⊂ verbose — so turning the dial up never takes anything off
 * the screen. Nothing here touches the DOM: what a level shows is a property
 * of the messages, and is checked as one.
 */

import type { AgentMessage } from '@/api/types'
import { t, type MessageKey } from '@/i18n'

/**
 * What every function here reads of a message. `AgentMessage` minus the task it belongs
 * to, so a chat's transcript (`AgentChatMessage`, keyed by chat) goes through the same
 * rows, the same tail and the same fold as a task's — one rule for both feeds.
 */
export type TranscriptMessage = Omit<AgentMessage, 'task_id'>
import { oneLine, toolName, toolSubject } from '@/lib/activity'
import { parseUtc, statusLabel } from '@/lib/status'
import { subagentActivity } from '@/lib/subagents'
import { resultTypeOf } from '@/lib/task-result'

/**
 * Whether a message was sent to a session that was busy and has not reached it yet (§10.8).
 * The backend stores `data.delivery` on such an entry — `queued`, then `delivered`.
 */
export function isQueued(message: Pick<TranscriptMessage, 'data'>): boolean {
  return message.data?.delivery === 'queued'
}

/**
 * Current telemetry replaces its row; ordinary socket replay never reorders history (§10.4).
 * One other entry is published twice: a queued message, again when it is delivered (§10.8).
 * That one is replaced where it stands — it was said when it was said.
 */
export function upsertTranscript<M extends TranscriptMessage>(messages: M[], message: M): M[] {
  const previous = messages.find((entry) => entry.id === message.id)
  if (previous && isQueued(previous) && message.data?.delivery === 'delivered') {
    return messages.map((entry) => (entry.id === message.id ? message : entry))
  }
  const timestamp = (value: string) => value.replace(/(?:Z|\+00:00)$/, '')
  if (previous && (!message.id.startsWith('state_') ||
    timestamp(previous.created_at) >= timestamp(message.created_at))) return messages
  return [...messages.filter((entry) => entry.id !== message.id), message]
}

/** Quietest first — the order the picker offers them in, and the order they nest in. */
export const VERBOSITY_LEVELS = ['default', 'verbose'] as const

export type Verbosity = (typeof VERBOSITY_LEVELS)[number]

/** What a reader who never touched the dial sees — the level named for it. */
export const DEFAULT_VERBOSITY: Verbosity = 'default'

/**
 * The picker's own words. Spelled out rather than capitalised in CSS: the option list is
 * drawn by the platform, which is not obliged to apply `text-transform` inside it.
 *
 * Message keys rather than the words themselves, resolved with `t()` where the picker renders,
 * so the options follow the interface language instead of the one this module loaded in.
 */
export const VERBOSITY_LABELS: Record<Verbosity, MessageKey> = {
  default: 'taskDetail.verbosity.level.default',
  verbose: 'taskDetail.verbosity.level.verbose',
}

/** What each level answers, for the picker's tooltip — keys, like the labels. */
export const VERBOSITY_HINTS: Record<Verbosity, MessageKey> = {
  default: 'taskDetail.verbosity.hint.default',
  verbose: 'taskDetail.verbosity.hint.verbose',
}

/** Guards a remembered choice: a stored level from an older build may no longer exist. */
export function isVerbosity(value: unknown): value is Verbosity {
  return VERBOSITY_LEVELS.includes(value as Verbosity)
}

/**
 * A remembered level, read back. `advanced` is Default's name from before 2026-11-10, and
 * `compact` a level that no longer exists; both read as Default, the nearest one there is.
 */
export function readVerbosity(value: unknown): Verbosity {
  return isVerbosity(value) ? value : DEFAULT_VERBOSITY
}

/**
 * History entries no level hides — they are the task's audit trail, and it carries
 * `role: "system"` like the noise does (§10.4).
 */
const HISTORY_KINDS = new Set([
  'status', 'edited', 'commit', 'file_observation_status', 'file_observation_error', 'cancelled', 'error',
])

/**
 * The history plus the tool calls, and `continued` with them — what Default keeps of the
 * `system` role.
 *
 * A tool call is what the agent actually *did*: hidden, a run that spent ten minutes
 * reading and writing files looked like a session that went quiet after its last sentence.
 * `continued` is here for a sharper reason — it is the line that explains the empty
 * `result` beside it when a session stopped mid-work and the Studio resumed it (§10.3).
 */
const PRODUCER_EVENTS = new Set(['effort_solver', 'producer_compacted', 'producer_plan'])
const ALWAYS_KINDS = new Set([...HISTORY_KINDS, 'tool_use', 'continued', ...PRODUCER_EVENTS])

/** Rows drawn as a timeline line rather than a card: nobody is speaking in them. */
export const TIMELINE_KINDS = new Set([...HISTORY_KINDS, 'continued'])

/**
 * Entries whose text is *prose*, and therefore Markdown — an agent writes headings, lists
 * and fenced code into most answers, and reading the asterisks was worse than reading the
 * sentences plainly.
 *
 * Everything else keeps `whitespace-pre-wrap`: a tool result and a traceback are payloads,
 * where a `*` is a character and column alignment carries meaning Markdown would eat.
 */
export const PROSE_KINDS = new Set(['text', 'thinking', 'result', 'note', 'summary', 'review', 'context_summary', 'compressed summary'])

/**
 * Tools whose call means "this file now differs" — the same set the changes column matches
 * on (`teams/changes.py`), so the two agree about what counts as writing. Matched on the
 * last segment of the name, which is what makes an MCP tool recognisable as itself.
 */
const WRITING_TOOLS = new Set(['edit', 'write', 'multiedit', 'notebookedit', 'write_doc'])

/** Lines a writing call added and removed, as the backend counted them at capture time. */
export interface EditStat {
  insertions: number
  deletions: number
}

/** One row of the transcript, at whichever level is showing. */
export interface TranscriptRow<M extends TranscriptMessage = TranscriptMessage> {
  key: string
  message: M
}

/** One call inside a "Tools used" block, with the results that answered it (Verbose). */
export interface ToolEntry<M extends TranscriptMessage = TranscriptMessage> {
  key: string
  message: M
  results: M[]
}

/**
 * What the transcript draws, at whichever level is showing: a message on its own, a
 * thinking block, or a run of consecutive tool calls folded into one "Tools used: N" block.
 * The two folds start collapsed at Default and open at Verbose — task, chat and Producer alike.
 */
export type TranscriptBlock<M extends TranscriptMessage = TranscriptMessage> =
  | { type: 'message'; key: string; message: M }
  | { type: 'thinking'; key: string; message: M }
  | { type: 'tools'; key: string; entries: ToolEntry<M>[]; calls: number; studio: boolean; spawn: boolean }

/**
 * The last line of the transcript: what was logged most recently, and what the run has
 * produced.
 *
 * One line, at the bottom — which is the whole difference from the lines the transcript used
 * to leave folded onto every block. Left behind on each one they were the tool feed again,
 * which is what Default is for, and they read as history: "Bash · npm test" still sitting
 * under a block the run walked away from ten minutes ago, indistinguishable from the line
 * that is actually live.
 *
 * `activity` is the tail of everything Verbose would log, and is empty *at* Verbose, where
 * the newest row is already the newest line and a copy of it under the scroller would say
 * nothing. `tokens` is every level's: what the model has *written* across this task — its
 * thinking, its answers and its tool calls. The header separately shows lifetime spending,
 * including input and cached input, as the runtime reports it.
 * `seq` counts the lines that have gone by, so the view can key the element on it: a CSS
 * animation restarts when its element does, which is what turns a changing line into a
 * blinking one, and counting rather than comparing is what makes the same tool twice blink
 * twice.
 */
export interface LiveLine {
  activity: string
  seq: number
  tokens: number
}

/** The tool's own name, MCP server stripped — mirrors `tool_name` in `teams/changes.py`. */
export function bareToolName(message: TranscriptMessage): string {
  const raw = typeof message.data?.name === 'string' ? message.data.name : message.text
  if (!raw) return ''
  return raw.startsWith('mcp__') ? (raw.split('__').pop() ?? '') : raw
}

/** Whether this call wrote a file, whatever it managed to say about how much. */
export function isFileEdit(message: TranscriptMessage): boolean {
  return message.kind === 'tool_use' && WRITING_TOOLS.has(bareToolName(message).toLowerCase())
}

/** Whether this call went to an MCP server — `mcp__<server>__<tool>`, as the runtimes name them. */
export function isMcpCall(message: TranscriptMessage): boolean {
  if (message.kind !== 'tool_use') return false
  const raw = typeof message.data?.name === 'string' ? message.data.name : message.text
  return typeof raw === 'string' && raw.startsWith('mcp__')
}

/** How every runtime names a Studio tool — mirrors `studio_tool_prefix` in `api/teams.py`. */
export const STUDIO_TOOL_PREFIX = 'mcp__voxarium_studio__'

/** Whether this call went to the Studio's own MCP server — the board, documents, subagents (§10.4). */
export function isStudioCall(message: TranscriptMessage): boolean {
  if (message.kind !== 'tool_use') return false
  const raw = typeof message.data?.name === 'string' ? message.data.name : message.text
  return typeof raw === 'string' && raw.startsWith(STUDIO_TOOL_PREFIX)
}

/** Only delegation/creation gets the violet Studio block (§10.4). Other Studio calls stay standard. */
function isHighlightedStudioCall(message: TranscriptMessage): boolean {
  return isStudioCall(message) && ['spawn_subagent', 'create_task'].includes(bareToolName(message))
}

/** Whether this call started a Studio-managed subagent (§10.3). */
export function isSubagentSpawn(message: TranscriptMessage): boolean {
  return isStudioCall(message) && bareToolName(message) === 'spawn_subagent'
}

/**
 * Every call's results by the call's id, from the whole feed rather than the level's rows —
 * a call row may need what its result said (a spawn's Effort Solver scores) at Default too,
 * where the results themselves are not drawn.
 */
export function resultsByCall<M extends TranscriptMessage>(messages: M[]): Map<string, M> {
  const results = new Map<string, M>()
  for (const message of messages) {
    const id = message.kind === 'tool_result' ? message.data?.tool_use_id : undefined
    if (typeof id === 'string' && id) results.set(id, message)
  }
  return results
}

/**
 * Whether this call loaded a skill — the runtime's `Skill` tool, `voxarium_studio:studio` or one of
 * the project's own. Codex has no such tool; its backend names a shell read of a known
 * `SKILL.md` as a `Skill` call, so the three runtimes read alike here (§10.10).
 */
export function isSkillCall(message: TranscriptMessage): boolean {
  return message.kind === 'tool_use' && bareToolName(message) === 'Skill'
}

/**
 * What the call said it added and removed, or `null` when nobody counted.
 *
 * Read, never derived: the transcript stores each tool argument clipped to 500 characters,
 * so the two sides of an edit are not here to be diffed — `edit_stat` in
 * `teams/changes.py` does it at capture time, where the whole input still exists. A
 * transcript written before that existed therefore shows the edit and no figures, which is
 * the honest answer rather than a guess made from half a string.
 */
export function editStat(message: TranscriptMessage): EditStat | null {
  if (message.kind !== 'tool_use') return null
  const stat = message.data?.edit
  if (!stat || typeof stat !== 'object') return null
  const { insertions, deletions } = stat as Record<string, unknown>
  if (typeof insertions !== 'number' || typeof deletions !== 'number') return null
  return { insertions, deletions }
}

/**
 * Tokens the model produced on this turn, or 0 for a turn that reported none.
 *
 * A provider response can emit several assistant frames with repeated or advancing
 * usage. liveLine counts only the increment for its provider_response_id. A result
 * carries the segment's total and fills gaps instead of charging the stream again.
 *
 * `output_tokens` is the half that counts the thinking. The prompt and the cache reads are
 * in the task header's lifetime total, and they dwarf this one without saying anything
 * about what the agent has been doing — a long session is mostly cache reads.
 */
function outputTokens(message: Pick<TranscriptMessage, 'kind' | 'text' | 'data'>): number {
  if (message.kind === 'system' && message.text === 'usage_update') return 0
  const usage = message.data?.usage
  if (!usage || typeof usage !== 'object') return 0
  const value = (usage as Record<string, unknown>).output_tokens
  return typeof value === 'number' && Number.isFinite(value) ? Math.max(0, value) : 0
}

type OutputMessage = Pick<TranscriptMessage, 'kind' | 'role' | 'text' | 'data' | 'created_at'>
const EVIDENCE_KINDS = new Set(['accounting', 'compressed summary', 'context_summary'])

/** §10.4: repairs replace historical accounting while leaving the original dialogue visible. */
export function outputAccounting(messages: TranscriptMessage[]): OutputMessage[] {
  let latestRepair: { message: TranscriptMessage; cutoff: number } | null = null
  const repairs = new Set<TranscriptMessage>()
  for (const message of messages) {
    const repair = message.data?.repair
    if (message.kind !== 'accounting' || !repair || typeof repair !== 'object') continue
    const raw = (repair as Record<string, unknown>).cutoff_at
    const cutoff = typeof raw === 'string' ? parseUtc(raw) : NaN
    if (!Number.isFinite(cutoff)) continue
    repairs.add(message)
    if (!latestRepair || parseUtc(message.created_at) > parseUtc(latestRepair.message.created_at)) {
      latestRepair = { message, cutoff }
    }
  }
  const evidence = (message: TranscriptMessage): OutputMessage[] => {
    const rows = message.data?.evidence
    if (!Array.isArray(rows)) return []
    return rows.flatMap((row): OutputMessage[] => {
      if (!row || typeof row !== 'object' || typeof row.kind !== 'string'
        || typeof row.created_at !== 'string' || !Number.isFinite(parseUtc(row.created_at))) return []
      return [{ kind: row.kind, role: row.role === 'assistant' ? 'assistant' : 'system',
        text: typeof row.text === 'string' ? row.text : '', created_at: row.created_at,
        data: row.data && typeof row.data === 'object' ? row.data : {} }]
    })
  }
  const feed = messages.flatMap((message): OutputMessage[] => repairs.has(message)
    ? [] : EVIDENCE_KINDS.has(message.kind) ? evidence(message) : [message])
  const canonical = latestRepair
    ? [...feed.filter(message => parseUtc(message.created_at) > latestRepair.cutoff), ...evidence(latestRepair.message)]
    : feed
  return canonical.sort((a, b) => parseUtc(a.created_at) - parseUtc(b.created_at))
}

/**
 * Work a delegated agent did inside the member's session — the runtime streams it with the
 * call that started it (`parent_tool_use_id`). It is the member's tool call doing its job,
 * not the member speaking, so it folds into the tools block with the rest of the work.
 */
export function isDelegatedWork(message: TranscriptMessage): boolean {
  const parent = message.data?.parent_tool_use_id
  return typeof parent === 'string' && parent !== ''
}

/** Whether a message survives this level. Default hides the bulky half; Verbose nothing. */
function shows(message: TranscriptMessage, verbosity: Verbosity): boolean {
  if (message.kind === 'accounting') return false
  if (message.kind === 'system' && ['thinking_tokens', 'usage_update'].includes(message.text)) return false
  // The runtimes redact most thinking to an empty block; a fold with nothing inside says nothing.
  if (message.kind === 'thinking' && !message.text.trim()) return false
  if (verbosity === 'verbose') return true
  return ALWAYS_KINDS.has(message.kind) || message.role !== 'system'
}

/**
 * The transcript as the chosen level filters it, one row per message, nothing grouped.
 *
 * Default keeps what was said, the history, the thinking and the tool calls; Verbose
 * everything. `transcriptBlocks` is the same list as the panel draws it.
 */
export function transcriptRows<M extends TranscriptMessage>(
  messages: M[],
  verbosity: Verbosity,
): TranscriptRow<M>[] {
  return closingSummaries(messages)
    .filter((message) => shows(message, verbosity))
    .map((message) => ({ key: message.id, message }))
}

/**
 * The rows grouped as every transcript panel draws them (§10.4, §10.9).
 *
 * A thinking message becomes a thinking block. Consecutive tool calls become one tools
 * block, whatever the tools were — `Bash`, `Grep`, an MCP call, a skill being loaded — so
 * a run that read twelve files between two sentences is one folded line rather than twelve.
 * Studio create_task calls (`studio`) split into violet blocks. Each spawn_subagent gets
 * its own permanently expanded block so its Solver process stays visible (§10.4).
 * All other Studio tools join ordinary calls in standard-colour blocks
 * (§10.4; 2026-11-12, at the user's request).
 * At Verbose each call keeps its results under it, matched on the call's id; a result whose
 * call is not in sight, a delegated agent's work, and a system frame arriving between two
 * calls stay inside the block too, so the noise of a busy run does not split it into a
 * dozen one-call blocks. Anything else — someone speaking, a history line — ends the block.
 *
 * The count is of calls, not entries: results and frames are what the calls produced.
 */
export function transcriptBlocks<M extends TranscriptMessage>(
  messages: M[],
  verbosity: Verbosity,
): TranscriptBlock<M>[] {
  type ToolsBlock = Extract<TranscriptBlock<M>, { type: 'tools' }>
  const blocks: TranscriptBlock<M>[] = []
  const calls = new Map<string, ToolEntry<M>>()
  let group: ToolsBlock | null = null

  for (const { key, message } of transcriptRows(messages, verbosity)) {
    const work =
      message.kind === 'tool_use' ||
      message.kind === 'tool_result' ||
      isDelegatedWork(message) ||
      (group !== null && message.role === 'system' && message.kind === 'system')
    if (!work) {
      group = null
      blocks.push(message.kind === 'thinking'
        ? { type: 'thinking', key, message }
        : { type: 'message', key, message })
      continue
    }
    if (message.kind === 'tool_result') {
      const id = message.data?.tool_use_id
      const owner = typeof id === 'string' ? calls.get(id) : undefined
      if (owner) {
        owner.results.push(message)
        continue
      }
    }
    // A call of the other family starts a block of its own; results, frames and delegated
    // work carry on in whichever block is open. A block holding no call yet takes the
    // family of the first one rather than leaving an empty block behind.
    const own = message.kind === 'tool_use' && !isDelegatedWork(message)
    const studio = own && isHighlightedStudioCall(message)
    const spawn = own && isSubagentSpawn(message)
    if (group !== null && own && (group.studio !== studio || spawn || group.spawn)) {
      if (group.calls || spawn || group.spawn) group = null
      else group.studio = studio
    }
    if (group === null) {
      group = { type: 'tools', key, entries: [], calls: 0, studio, spawn }
      blocks.push(group)
    }
    const entry: ToolEntry<M> = { key, message, results: [] }
    group.entries.push(entry)
    if (message.kind === 'tool_use') {
      group.calls += 1
      const id = message.data?.id
      if (typeof id === 'string' && id) calls.set(id, entry)
    }
  }
  return blocks
}

/**
 * §10.4: finish_task reports the type before the runtime emits the closing reply.
 * Give that reply the summary styling, retaining the detailed report as ordinary prose.
 * This is a display projection, including for old runs; the stored audit trail is intact.
 * Never carry a result type across a run boundary or another person's contribution.
 */
function closingSummaries<M extends TranscriptMessage>(messages: M[]): M[] {
  const projected = [...messages]
  const omitted = new Set<number>()
  let report = -1
  let closing = -1
  const flush = () => {
    if (report >= 0 && closing >= 0) {
      const original = messages[report]!
      const last = messages[closing]!
      if (original.text.trim() === last.text.trim()) omitted.add(report)
      else projected[report] = { ...original, kind: 'text' }
      projected[closing] = {
        ...last,
        kind: 'summary',
        data: { ...last.data, result_type: original.data?.result_type },
      }
    }
    report = closing = -1
  }
  for (let index = 0; index < messages.length; index += 1) {
    const message = messages[index]!
    if (message.kind === 'summary') {
      flush()
      if (message.role === 'assistant' && resultTypeOf(message)) report = index
    } else if (
      message.kind === 'status' || message.kind === 'continued' ||
      message.kind === 'result' || message.kind === 'error' ||
      message.kind === 'cancelled' || message.kind === 'result_missing' ||
      message.kind === 'note' || message.kind === 'review' ||
      message.role === 'user' || message.role === 'human' ||
      (report >= 0 && message.kind === 'text' &&
        message.member_id !== messages[report]!.member_id)
    ) {
      flush()
    } else if (report >= 0 && message.member_id === messages[report]!.member_id) {
      if (message.kind === 'text' && message.role === 'assistant' && message.text.trim()) {
        closing = index
      } else if (message.kind === 'tool_use') {
        // A reply followed by more work is commentary, not the closing summary.
        closing = -1
      }
    }
  }
  flush()
  return projected.filter((_, index) => !omitted.has(index))
}

/** As long as the board card's line: past this nobody is reading, they are glancing. */
const TAIL_LIMIT = 160

/**
 * Any message as one line, whatever the level would make of it.
 *
 * Nothing is silent here: a `tool_result`, a person's note and a `system` frame each get a
 * line, because the whole reason to look at the tail is to see what the level is *not*
 * showing. A tool call reads as its own row does, name and subject; a lifecycle frame gets
 * `subagentActivity`'s sentence rather than the bare subtype it carries as text; everything
 * else is its text.
 *
 * The board card shows the same line for a running task (the store's `activity`), one
 * message at a time off the socket, so a card and the open transcript never disagree about
 * what just happened.
 *
 * Flattened and clipped here rather than in CSS alone. A tool result is many lines and a
 * traceback is more, and both would push the token count beside them out of the box before
 * `truncate` ever saw a line to truncate.
 */
export function tailLine(message: TranscriptMessage): string {
  if (message.kind === 'effort_solver') return t('effortSolver.name')
  if (message.kind === 'system' && message.text === 'usage_update') return ''
  // History is worded from its data, as its row is — the stored text is the backend's English.
  if (message.kind === 'status') return oneLine(statusText(message), TAIL_LIMIT)
  if (message.kind === 'edited') return oneLine(editedText(message), TAIL_LIMIT)
  if (message.kind === 'commit') return oneLine(commitText(message), TAIL_LIMIT)
  // §10.3: effort belongs to the shared slider in run metadata and the detailed record.
  // Keep a structured model notice useful in the short activity line without duplicating
  // its effort as prose; older records without data keep their original wording below.
  if (message.kind === 'model' && typeof message.data?.effort === 'string') {
    const model = typeof message.data.model === 'string' ? message.data.model : ''
    return oneLine([
      model ? `${t('tasks.overrides.model')}: ${model}` : null,
      message.data.source === 'task' ? t('taskDetail.runSettings.overridden') : null,
    ].filter(Boolean).join(' · '), TAIL_LIMIT)
  }
  if (message.kind === 'tool_use') {
    return [toolName(message), toolSubject(message)].filter(Boolean).join(' · ')
  }
  if (message.kind === 'system') {
    return oneLine(subagentActivity(message) || message.text, TAIL_LIMIT)
  }
  return oneLine(message.text, TAIL_LIMIT)
}

/**
 * The one line under the rows: the last thing logged, and the tokens behind it.
 *
 * The tail is taken over the **whole** feed, not over the rows this level draws — that is
 * what makes it worth having below Verbose, where the messages it names are the ones the
 * level is hiding: the result of the call that just ran, the frame from a sub-agent nobody
 * asked to see. It follows the log and never the blocks, so it does not go blank when the
 * agent speaks; on a live session it is whatever arrived last, and on a finished one it is
 * how the run ended. Nothing here is kept — turning the dial to Verbose is what puts these
 * lines in the transcript as rows, and at Verbose the tail is empty for exactly that reason.
 *
 * Tokens are counted at every level and over every message, since nothing about a level
 * changes what a run has cost.
 */
export function liveLine(messages: TranscriptMessage[], verbosity: Verbosity): LiveLine {
  const line: LiveLine = { activity: '', seq: 0, tokens: 0 }
  let thinking = 0
  let segmentOutput = 0
  const responses = new Map<string, number>()
  for (const message of outputAccounting(messages)) {
    const output = outputTokens(message)
    if (message.kind === 'result') {
      // Each result closes one segment, including continued results. Its aggregate
      // fills gaps in older transcripts without charging the streamed output again.
      line.tokens += Math.max(segmentOutput, output)
      segmentOutput = 0
    } else if (message.role === 'assistant' ||
      (message.kind === 'system' && message.text === 'turn.completed')) {
      // Response ids survive continued results: replaying an earlier response is not
      // new output, while a new id starts its own counter. Codex turn usage is a delta.
      const id = message.data?.provider_response_id
      if (typeof id === 'string' && id) {
        const previous = responses.get(id) ?? 0
        segmentOutput += Math.max(0, output - previous)
        responses.set(id, Math.max(previous, output))
      } else {
        segmentOutput += output
      }
    }
    if (output > 0 || message.kind === 'result') thinking = 0
    if (message.kind === 'system' && message.text === 'thinking_tokens') {
      const estimate = message.data?.estimated_tokens
      thinking = typeof estimate === 'number' && Number.isFinite(estimate) ? Math.max(0, estimate) : 0
    }
  }
  line.tokens += segmentOutput + thinking
  for (const message of messages) {
    if (message.kind === 'system' && message.text === 'thinking_tokens') continue
    if (verbosity === 'verbose') continue
    const text = tailLine(message)
    if (!text) continue
    line.activity = text
    line.seq += 1
  }
  return line
}

/**
 * The reasons `_set_task_status` records, as words in the interface language. It is a closed
 * set the backend writes, not prose; a reason this table does not know is shown as it came.
 */
const HISTORY_REASONS = new Map<string, MessageKey>([
  ['approved', 'taskDetail.history.reason.approved'],
  ['changes requested', 'taskDetail.history.reason.changesRequested'],
  ['claimed', 'taskDetail.history.reason.claimed'],
  ['reset', 'taskDetail.history.reason.reset'],
  ['submitted', 'taskDetail.history.reason.submitted'],
  ['usage limit reset', 'taskDetail.history.reason.usageLimitReset'],
])

export function historyReason(reason: string): string {
  const key = HISTORY_REASONS.get(reason)
  return key ? t(key) : reason
}

/**
 * An edit entry's sentence. The backend stores it as "Edited " and the changed field names,
 * sorted (`_record_edit`), and keeps the same names in `data.fields` — so the sentence is
 * rebuilt from those in the interface language. The names themselves are the API's and stay
 * as they are; an entry without them keeps the text it was stored with.
 */
export function editedText(message: TranscriptMessage): string {
  const fields = message.data?.fields
  if (!fields || typeof fields !== 'object' || Array.isArray(fields)) return message.text
  const names = Object.keys(fields).sort()
  return names.length ? t('taskDetail.history.edited', { fields: names.join(', ') }) : message.text
}

/**
 * A commit entry as one line — `Committed 3 files as a1b2c3d on main` — worded from the
 * data the Commit button stored (§10.4). An entry without it keeps its stored text.
 */
export function commitText(message: TranscriptMessage): string {
  const files = message.data?.files
  if (!Array.isArray(files)) return message.text
  const revision = typeof message.data?.revision === 'string' ? message.data.revision : ''
  const branch = typeof message.data?.branch === 'string' ? message.data.branch : ''
  const line = t('taskDetail.history.committed', { count: files.length })
  const where = [revision, branch].filter(Boolean).join(' · ')
  return where ? `${line} · ${where}` : line
}

/**
 * A status entry as one line — `In progress → Needs review · submitted`, or `Todo · created`
 * for the entry a task is born with — worded from `data` the way the dialog's row words it.
 * An entry without that data keeps the text it was stored with.
 */
export function statusText(message: TranscriptMessage): string {
  const to = message.data?.to
  if (typeof to !== 'string') return message.text
  const from = message.data?.from
  if (typeof from !== 'string') return `${statusLabel(to)} · ${t('taskDetail.history.created')}`
  const reason = message.data?.reason
  const line = `${statusLabel(from)} → ${statusLabel(to)}`
  return typeof reason === 'string' && reason ? `${line} · ${historyReason(reason)}` : line
}
