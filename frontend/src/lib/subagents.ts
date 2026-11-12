/**
 * The sub-agents and workflows a member's session started, folded out of its transcript
 * (spec §10.3, §10.4).
 *
 * Studio-managed sub-agents and a runtime's own delegation share `system` lifecycle
 * frames. Studio can run any configured backend; native delegation includes Claude Code's
 * `Agent` and `Workflow` tools. The parent stays open while delegated work runs, and this
 * fold reconstructs that work from the durable task transcript.
 *
 * It reads the frames rather than the tool calls on purpose. A `Agent` call in the
 * transcript says work was *requested*; only the runtime knows whether something is still
 * running, and only these frames carry the id that ties a start to its ending.
 *
 * Being plain data over the message list, it is testable without a DOM — the same seam
 * `lib/transcript.ts` and `lib/activity.ts` sit on.
 */

import type { AgentMessage, TaskSubagent } from '@/api/types'
import { t, type MessageKey } from '@/i18n'
import { formatCost, formatTokens, parseUtc } from '@/lib/status'
import { tokenDirections, usageRecord, type TokenDirections } from '@/lib/token-directions'

/**
 * Work whose completion wakes the session for another turn, so the run is held open for it.
 *
 * The native types mirror `_DEFERRING_TASK_TYPES` in `teams/backends/claude_code.py`;
 * Studio-managed agents hold the parent run open too. Other work is shown as well, but a
 * background dev server does not keep the task In progress (§10.3).
 */
export const HOLDING_TASK_TYPES = new Set(['local_agent', 'local_workflow', 'studio_agent'])

/** A task in any of these is over; `task_updated` reports `killed` where a notification
 *  reports the CLI's mapped `stopped`, and a terminal state can arrive as either frame. */
const TERMINAL_STATUSES = new Set(['completed', 'failed', 'stopped', 'killed'])

/** Message kinds that end a run, and with it anything the run had still running. */
const RUN_ENDING_KINDS = new Set(['error', 'cancelled'])

export type SubagentStatus = 'running' | 'completed' | 'failed' | 'stopped'

/** One piece of delegated work, from `task_started` to whichever frame ended it. */
export interface Subagent extends TokenDirections {
  id: string
  description: string
  /** The runtime's own word: `local_agent`, `local_workflow`, `background_shell`, … */
  type: string
  typeLabel: string
  /** Studio-managed agents identify the selected runtime and model (§10.3). */
  backend: string
  model: string
  /**
   * The effort Effort Solver chose for a Studio-managed agent (§10.3, §10.11). Empty for a
   * runtime's own delegation, which does not report one — unknown, not "default".
   */
  effort: string
  status: SubagentStatus
  /** Whether the member's run is held open until this one finishes. */
  holdsRun: boolean
  startedAt: string
  endedAt: string | null
  tokens: number
  uncachedTokens: number
  cachedTokens: number
  /** Native runtimes can omit cost; zero is a known value, not missing telemetry. */
  costUsd: number | null
  toolUses: number
  lastTool: string
  summary: string
}

/**
 * What each kind of work is called on screen. Keys, resolved every time a label is built, so
 * the words follow the interface language; a type this table does not know is the runtime's
 * own word, spaced out, which is data and is shown as it came.
 */
const TYPE_LABELS: Record<string, MessageKey> = {
  local_agent: 'taskDetail.subagent.type.local_agent',
  studio_agent: 'taskDetail.subagent.type.local_agent',
  local_workflow: 'taskDetail.subagent.type.local_workflow',
  background_shell: 'taskDetail.subagent.type.background_shell',
  remote_agent: 'taskDetail.subagent.type.remote_agent',
  teammate: 'taskDetail.subagent.type.teammate',
  observer: 'taskDetail.subagent.type.observer',
  monitor: 'taskDetail.subagent.type.monitor',
}

function typeLabel(type: string): string {
  const key = TYPE_LABELS[type]
  if (key) return t(key)
  return type ? type.replace(/_/g, ' ') : t('taskDetail.subagent.untyped')
}

/** How a notification's status reads after the label — "sub-agent completed". */
const ENDINGS: Record<string, MessageKey> = {
  completed: 'taskDetail.subagent.ended.completed',
  failed: 'taskDetail.subagent.ended.failed',
  stopped: 'taskDetail.subagent.ended.stopped',
  killed: 'taskDetail.subagent.ended.killed',
}

/** A status the runtime did not send reads "finished"; one this table lacks, as it came. */
function endingLine(label: string, status: string): string {
  if (!status) return t('taskDetail.subagent.finished', { label })
  const key = ENDINGS[status]
  return key ? t(key, { label }) : t('taskDetail.subagent.endedAs', { label, status })
}

/** `killed` is the raw form of what a notification calls `stopped`; the UI says one thing. */
function asStatus(raw: string): SubagentStatus {
  if (raw === 'completed' || raw === 'failed') return raw
  return 'stopped'
}

function text(value: unknown): string {
  return typeof value === 'string' ? value : ''
}

function count(value: unknown): number | null {
  return typeof value === 'number' && Number.isFinite(value) && value >= 0 ? value : null
}

function record(value: unknown): Record<string, unknown> | null {
  return value && typeof value === 'object' ? (value as Record<string, unknown>) : null
}

/** Python stores microseconds; a completion can arrive in the same JS millisecond as a snapshot. */
function timestamp(value: string): number {
  const fraction = /\.(\d+)/.exec(value)?.[1] ?? ''
  return parseUtc(value) * 1000 + Number(fraction.padEnd(6, '0').slice(3, 6))
}

/** Explicit per-agent counters, including a reported zero spend (§10.3, §10.4). */
export function subagentUsage(entry: Pick<Subagent, 'tokens' | 'uncachedTokens' | 'cachedTokens' | 'costUsd'>): string {
  const unknown = Math.max(0, entry.tokens - entry.uncachedTokens - entry.cachedTokens)
  const parts = [
    t('tokenUsage.uncachedValue', { tokens: formatTokens(entry.uncachedTokens) }),
    t('tokenUsage.cachedValue', { tokens: formatTokens(entry.cachedTokens) }),
  ]
  if (unknown > 0) parts.push(t('tokenUsage.unclassifiedValue', { tokens: formatTokens(unknown) }))
  parts.push(t('taskDetail.delegated.apiCost', {
      cost: entry.costUsd === null ? t('taskDetail.delegated.usageUnavailable') : formatCost(entry.costUsd),
  }))
  return parts.join(' · ')
}

/** Native lifecycle events may report only a total; the missing split stays unknown. */
function tokenBreakdown(
  usage: Record<string, unknown> | null,
  previous?: Pick<Subagent, 'tokens' | 'uncachedTokens' | 'cachedTokens'> & TokenDirections,
): Pick<Subagent, 'tokens' | 'uncachedTokens' | 'cachedTokens'> & TokenDirections {
  const tokens = count(usage?.total_tokens) ?? previous?.tokens ?? 0
  const uncachedTokens = count(usage?.total_uncached_tokens) ?? previous?.uncachedTokens ?? 0
  const cachedTokens = count(usage?.total_cached_tokens) ?? previous?.cachedTokens ?? 0
  const groups = uncachedTokens + cachedTokens <= tokens
    ? { tokens, uncachedTokens, cachedTokens }
    : { tokens, uncachedTokens: 0, cachedTokens: 0 }
  const directions = tokenDirections({ ...usage,
    usage: usageRecord(usage?.usage) ?? usage,
    total_uncached_tokens: groups.uncachedTokens,
    total_cached_tokens: groups.cachedTokens,
  }, tokens)
  if (previous && tokens === previous.tokens && groups.uncachedTokens === previous.uncachedTokens
    && groups.cachedTokens === previous.cachedTokens) {
    directions.inputTokens ??= previous.inputTokens
    directions.outputTokens ??= previous.outputTokens
    directions.cacheReadTokens ??= previous.cacheReadTokens
  }
  return { ...groups, ...directions }
}

/** The fields a lifecycle frame is read from — an `AgentMessage`, or the event that will
 *  become one, which is the shape the store hands the board card. */
export type LifecycleMessage = Pick<AgentMessage, 'kind' | 'text' | 'data'>

/** The lifecycle frames all arrive as `system` messages whose text is the subtype. */
function frame(
  message: LifecycleMessage,
): { subtype: string; data: Record<string, unknown> } | null {
  if (message.kind !== 'system') return null
  const data = record(message.data)
  return data ? { subtype: message.text, data } : null
}

/**
 * Every piece of delegated work the transcript knows about, oldest first.
 *
 * A run ending closes whatever it still had open, and that is not tidying: the CLI
 * subprocess dies with the run and takes its sub-agents with it, so work still marked
 * running at that point was **stopped**, not left going. Without this a task resumed after
 * a cut-off run would show the old run's sub-agents as live forever.
 */
export function subagents(messages: (LifecycleMessage & Pick<AgentMessage, 'created_at'>)[], persisted: TaskSubagent[] = []): Subagent[] {
  const byId = new Map<string, Subagent>()
  const updatedAt = new Map<string, number>()
  // §10.3: durable rows keep the panel and its counters after transcript cleanup. Their
  // timestamps let newer socket events override the snapshot without replaying old runs.
  for (const row of persisted) {
    byId.set(row.id, {
      id: row.id, description: row.description, type: 'studio_agent',
      typeLabel: typeLabel('studio_agent'), backend: row.backend, model: row.model,
      effort: row.effort ?? '',
      status: row.status === 'queued' || row.status === 'running' ? 'running' : asStatus(row.status),
      holdsRun: true, startedAt: row.created_at, endedAt: row.finished_at,
      ...tokenBreakdown({ total_tokens: row.total_tokens, total_uncached_tokens: row.total_uncached_tokens,
        total_cached_tokens: row.total_cached_tokens, total_input_tokens: row.total_input_tokens,
        total_output_tokens: row.total_output_tokens }),
      costUsd: count(row.total_cost_usd),
      toolUses: count(row.tool_uses) ?? 0, lastTool: '', summary: row.summary,
    })
    updatedAt.set(row.id, timestamp(row.updated_at))
  }

  const closeAll = (when: number) => {
    for (const found of byId.values()) {
      if (found.status === 'running' && when > (updatedAt.get(found.id) ?? -Infinity)) {
        found.status = 'stopped'
      }
    }
  }

  for (const message of messages) {
    const when = timestamp(message.created_at)
    // A `continued` result ended a turn, not the run — that is exactly the case where the
    // session is held open *because* something is still running (§10.3).
    if (message.kind === 'result' && !message.data?.continued) {
      closeAll(when)
      continue
    }
    if (RUN_ENDING_KINDS.has(message.kind)) {
      closeAll(when)
      continue
    }

    const parsed = frame(message)
    if (!parsed) continue
    const { subtype, data } = parsed
    const id = text(data.task_id)
    if (!id) continue
    if (when <= (updatedAt.get(id) ?? -Infinity)) {
      // Tool activity is not part of the durable row; old frames may still fill it in
      // without replacing its newer status or accounting snapshot.
      const found = byId.get(id)
      if (found && subtype === 'task_progress' && text(data.last_tool_name)) {
        found.lastTool = text(data.last_tool_name)
      }
      continue
    }

    if (subtype === 'task_started') {
      const type = text(data.task_type)
      const usage = record(data.usage)
      const previous = byId.get(id)
      byId.set(id, {
        id,
        description: text(data.description) || t('taskDetail.subagent.unnamed'),
        type,
        typeLabel: typeLabel(type),
        backend: text(data.backend),
        model: text(data.model),
        // A follow-up turn restarts the same worker; a frame recorded before effort was
        // reported must not blank what the durable row already says.
        effort: text(data.effort) || previous?.effort || '',
        status: 'running',
        holdsRun: HOLDING_TASK_TYPES.has(type),
        startedAt: message.created_at,
        endedAt: null,
        ...tokenBreakdown((count(usage?.total_tokens) ?? 0) >= (previous?.tokens ?? 0) ? usage : null, previous),
        costUsd: count(usage?.total_cost_usd) ?? previous?.costUsd ?? (type === 'studio_agent' ? 0 : null),
        toolUses: Math.max(count(usage?.tool_uses) ?? 0, previous?.toolUses ?? 0),
        lastTool: '',
        summary: '',
      })
      continue
    }

    // A frame with no start belongs to work from before this transcript begins; there is
    // nothing truthful to draw for it, so it is dropped rather than invented.
    const found = byId.get(id)
    if (!found) continue

    if (subtype === 'task_progress') {
      const usage = record(data.usage)
      Object.assign(found, tokenBreakdown(usage, found))
      found.costUsd = count(usage?.total_cost_usd) ?? found.costUsd
      found.toolUses = count(usage?.tool_uses) ?? found.toolUses
      found.lastTool = text(data.last_tool_name) || found.lastTool
      found.description = text(data.description) || found.description
    } else if (subtype === 'task_notification') {
      found.status = asStatus(text(data.status))
      found.endedAt = message.created_at
      found.summary = text(data.summary)
      const usage = record(data.usage)
      Object.assign(found, tokenBreakdown(usage, found))
      found.costUsd = count(usage?.total_cost_usd) ?? found.costUsd
      found.toolUses = count(usage?.tool_uses) ?? found.toolUses
    } else if (subtype === 'task_updated') {
      const status = text(record(data.patch)?.status)
      if (TERMINAL_STATUSES.has(status)) {
        found.status = asStatus(status)
        found.endedAt = message.created_at
      }
    }
  }

  return [...byId.values()]
}

export function running(list: Subagent[]): Subagent[] {
  return list.filter((entry) => entry.status === 'running')
}

/**
 * The live half, for a board card: which ids a task still has in flight after this message.
 *
 * `null` means "this message says nothing about delegated work", which is most of them —
 * the store leaves its state alone rather than writing an identical value and waking every
 * card that watches it.
 *
 * Ids rather than a counter because a completion can be reported twice, once as a
 * notification and once as a `task_updated` patch; a decrement per frame would go negative.
 * Only work that holds the run open is counted: a card saying "1 running" about a dev
 * server nothing waits for would be a worse answer than saying nothing.
 */
export function trackDelegated(current: string[], message: LifecycleMessage): string[] | null {
  // The run ending takes its delegated work with it — see `subagents` above.
  if (message.kind === 'result' && !message.data?.continued) return []
  if (RUN_ENDING_KINDS.has(message.kind)) return []

  const parsed = frame(message)
  if (!parsed) return null
  const { subtype, data } = parsed
  const id = text(data.task_id)
  if (!id) return null

  if (subtype === 'task_started') {
    if (!HOLDING_TASK_TYPES.has(text(data.task_type))) return null
    return current.includes(id) ? null : [...current, id]
  }
  const ends =
    subtype === 'task_notification' ||
    (subtype === 'task_updated' && TERMINAL_STATUSES.has(text(record(data.patch)?.status)))
  if (!ends || !current.includes(id)) return null
  return current.filter((candidate) => candidate !== id)
}

/**
 * The board card's line for a lifecycle frame — "sub-agent · Reading group 1 · Grep".
 *
 * Without it a card whose session is busy delegating showed the raw subtype (`task_progress`
 * over and over), which says nothing about what is happening. Every other `system` frame gets
 * `''` here — an `init` is the session announcing its own configuration, not delegated work —
 * and `tailLine` falls back to the frame's own text, as Verbose draws it.
 */
export function subagentActivity(message: LifecycleMessage): string {
  const parsed = frame(message)
  if (!parsed) return ''
  const { subtype, data } = parsed
  const label = typeLabel(text(data.task_type))
  if (subtype === 'task_started') {
    return [t('taskDetail.subagent.started', { label }), text(data.description)]
      .filter(Boolean)
      .join(' · ')
  }
  if (subtype === 'task_progress') {
    return [label, text(data.description), text(data.last_tool_name)].filter(Boolean).join(' · ')
  }
  if (subtype === 'task_notification') {
    return [endingLine(label, text(data.status)), text(data.summary)].filter(Boolean).join(' · ')
  }
  return ''
}
