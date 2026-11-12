/**
 * What a task says it has running, folded out of the runtime's own lifecycle frames.
 *
 * The fold is where the truth is decided — which work holds the run open, which frame ends
 * a piece of it, and what happens to work that was still running when the run stopped — so
 * it is checked as data rather than by looking at the panel that draws it.
 */
import { describe, expect, it } from 'vitest'

import type { AgentMessage, TaskSubagent } from '@/api/types'
import { running, subagentActivity, subagents, subagentUsage, trackDelegated } from '@/lib/subagents'

let sequence = 0

const message = (overrides: Partial<AgentMessage> = {}): AgentMessage => ({
  id: `msg_${(sequence += 1)}`,
  task_id: 'task_1',
  member_id: 'team_default__dev',
  role: 'assistant',
  kind: 'text',
  text: '',
  data: {},
  created_at: '2026-09-04T10:00:00',
  ...overrides,
})

/** A `system` frame as the backend stores one: the subtype in `text`, the payload verbatim. */
const frame = (subtype: string, data: Record<string, unknown>) =>
  message({ role: 'system', kind: 'system', text: subtype, data })

const started = (id: string, overrides: Record<string, unknown> = {}) =>
  frame('task_started', {
    task_id: id,
    task_type: 'local_agent',
    description: 'Reading group 1',
    ...overrides,
  })

/** The result frame that ends a *turn* while delegated work keeps going (§10.3). */
const heldResult = () => message({ role: 'system', kind: 'result', data: { continued: true } })
const finalResult = () => message({ role: 'system', kind: 'result', data: {} })

const saved = (overrides: Partial<TaskSubagent> = {}): TaskSubagent => ({
  id: 'studio_1', task_id: 'task_1', member_id: 'team_default__dev',
  description: 'Review implementation', backend: 'codex', model: 'gpt-test', effort: null,
  status: 'completed', session_id: 'child_session', summary: 'Review complete.',
  total_tokens: 1500, total_cost_usd: 0.03, num_turns: 2, tool_uses: 4,
  created_at: '2026-09-04T10:00:00Z', updated_at: '2026-09-04T10:01:00Z',
  finished_at: '2026-09-04T10:01:00Z', ...overrides,
})

describe('durable Studio-managed work (§10.3)', () => {
  it('uses complete saved directions and replaces them with newer live counters', () => {
    const row = saved({ total_tokens: 1500, total_uncached_tokens: 500, total_cached_tokens: 1000,
      total_input_tokens: 400, total_output_tokens: 100 })
    expect(subagents([], [row])[0]).toMatchObject({ inputTokens: 400, outputTokens: 100, cacheReadTokens: 1000 })
    const progress = { ...frame('task_progress', { task_id: row.id, usage: {
      total_tokens: 2000, total_uncached_tokens: 700, total_cached_tokens: 1300,
      total_input_tokens: 550, total_output_tokens: 150,
    } }), created_at: '2026-09-04T10:02:00Z' }
    expect(subagents([progress], [row])[0]).toMatchObject({ inputTokens: 550, outputTokens: 150, cacheReadTokens: 1300 })
    const finish = { ...frame('task_notification', { task_id: row.id, status: 'completed' }),
      created_at: '2026-09-04T10:03:00Z' }
    expect(subagents([progress, finish], [row])[0])
      .toMatchObject({ inputTokens: 550, outputTokens: 150, cacheReadTokens: 1300 })
  })

  it('does not infer a native agent direction split from a combined total', () => {
    const progress = frame('task_progress', { task_id: 'native_1', usage: { total_tokens: 250 } })
    expect(subagents([started('native_1'), progress])[0])
      .toMatchObject({ inputTokens: null, outputTokens: null, cacheReadTokens: null })
    expect(subagents([], [saved({ total_input_tokens: 0, total_output_tokens: 0 })])[0])
      .toMatchObject({ inputTokens: null, outputTokens: null, cacheReadTokens: null })
  })

  it('drops old directions when a same-total report changes the cache partition', () => {
    const row = saved({ total_tokens: 1500, total_uncached_tokens: 500, total_cached_tokens: 1000,
      total_input_tokens: 400, total_output_tokens: 100 })
    const progress = { ...frame('task_progress', { task_id: row.id, usage: {
      total_tokens: 1500, total_uncached_tokens: 700, total_cached_tokens: 800,
    } }), created_at: '2026-09-04T10:02:00Z' }
    expect(subagents([progress], [row])[0]).toMatchObject({ inputTokens: null, outputTokens: null, cacheReadTokens: 800 })
  })

  it('carries the effort Effort Solver chose, from the saved row and from live frames', () => {
    // §10.11: the durable row keeps it after the transcript is cleaned up …
    expect(subagents([], [saved({ effort: 'xhigh' })])[0]?.effort).toBe('xhigh')
    // … a worker spawned while the dialog is open has only its start frame …
    const live = started('studio_2', { task_type: 'studio_agent', backend: 'codex', effort: 'low' })
    expect(subagents([live])[0]?.effort).toBe('low')
    // … and a follow-up's restart frame recorded without one does not blank the row's.
    const restarted = { ...started('studio_1', { task_type: 'studio_agent' }), created_at: '2026-09-04T10:02:00Z' }
    expect(subagents([restarted], [saved({ effort: 'xhigh' })])[0]).toMatchObject({ status: 'running', effort: 'xhigh' })
    // A runtime's own sub-agent reports no effort: unknown, so nothing is drawn for it.
    expect(subagents([started('native_1')])[0]?.effort).toBe('')
    expect(subagents([], [saved()])[0]?.effort).toBe('')
  })

  it('retains archived or compressed workers when lifecycle messages are gone', () => {
    for (const messages of [[], [message({ kind: 'compressed summary', text: 'Task summary' })]]) {
      const list = subagents(messages, [saved()])
      expect(list).toHaveLength(1)
      expect(list[0]).toMatchObject({
        id: 'studio_1', backend: 'codex', model: 'gpt-test', status: 'completed',
        tokens: 1500, costUsd: 0.03, toolUses: 4, summary: 'Review complete.',
      })
    }
  })

  it('keeps a saved follow-up running despite older completion and parent result frames', () => {
    const row = saved({ status: 'running', finished_at: null, summary: '' })
    const list = subagents([
      started('studio_1', { task_type: 'studio_agent' }),
      frame('task_notification', { task_id: 'studio_1', status: 'completed' }),
      finalResult(),
    ], [row])
    expect(list[0]).toMatchObject({ status: 'running', tokens: 1500, costUsd: 0.03 })
  })

  it('uses newer live progress and completion instead of the durable snapshot', () => {
    const row = saved({ status: 'running', finished_at: null, summary: '' })
    const progress = {
      ...frame('task_progress', {
        task_id: 'studio_1', task_type: 'studio_agent', last_tool_name: 'Read',
        usage: { total_tokens: 2000, total_cost_usd: 0.05, tool_uses: 6 },
      }),
      created_at: '2026-09-04T10:02:00Z',
    }
    const finish = {
      ...frame('task_notification', { task_id: 'studio_1', status: 'completed', summary: 'Updated review.' }),
      created_at: '2026-09-04T10:03:00Z',
    }
    expect(subagents([progress, finish], [row])[0]).toMatchObject({
      status: 'completed', tokens: 2000, costUsd: 0.05, toolUses: 6, summary: 'Updated review.',
    })
  })

  it('reopens a completed worker on a newer start while retaining its lifetime counters', () => {
    const restarted = {
      ...started('studio_1', { task_type: 'studio_agent', backend: 'codex', model: 'gpt-test' }),
      created_at: '2026-09-04T10:02:00Z',
    }
    expect(subagents([restarted], [saved()])[0]).toMatchObject({
      status: 'running', endedAt: null, tokens: 1500, costUsd: 0.03, toolUses: 4,
    })
  })

  it('accepts a completion newer than the snapshot by less than a millisecond', () => {
    const row = saved({
      status: 'running', finished_at: null, summary: '', updated_at: '2026-09-04T10:01:00.000100Z',
    })
    const finish = {
      ...frame('task_notification', { task_id: 'studio_1', status: 'completed' }),
      created_at: '2026-09-04T10:01:00.000200Z',
    }
    expect(subagents([finish], [row])[0]?.status).toBe('completed')
  })
})

describe('what a session has running', () => {
  it('tracks Studio-managed work from different backends alongside native delegation', () => {
    const start = started('studio_1', {
      task_type: 'studio_agent', backend: 'codex', model: 'gpt-test',
      description: 'Review the implementation',
    })
    const progress = frame('task_progress', {
      task_id: 'studio_1', task_type: 'studio_agent', last_tool_name: 'Read',
      usage: { total_tokens: 1200, total_cost_usd: 0.025, tool_uses: 3 },
    })
    const finish = frame('task_notification', {
      task_id: 'studio_1', task_type: 'studio_agent', status: 'completed',
      summary: 'Review complete.', usage: { total_tokens: 1500, total_cost_usd: 0.03, tool_uses: 4 },
    })
    const feed = [started('native_1'), start, progress, heldResult()]
    expect(subagents(feed)[1]).toMatchObject({
      id: 'studio_1', typeLabel: 'sub-agent', backend: 'codex', model: 'gpt-test',
      status: 'running', holdsRun: true, tokens: 1200, costUsd: 0.025, toolUses: 3, lastTool: 'Read',
    })
    const live = trackDelegated(['native_1'], start)!
    expect(live).toEqual(['native_1', 'studio_1'])
    expect(trackDelegated(live, heldResult())).toBeNull()
    expect(trackDelegated(live, finish)).toEqual(['native_1'])
    expect(subagents([...feed, finish])[1]).toMatchObject({
      status: 'completed', summary: 'Review complete.', tokens: 1500, costUsd: 0.03, toolUses: 4,
    })
    expect(running(subagents([...feed, finish])).map((entry) => entry.id)).toEqual(['native_1'])
    expect(subagentActivity(start)).toBe('sub-agent started · Review the implementation')
  })

  it('shows both counters before spending and distinguishes unknown native cost from zero', () => {
    const studio = subagents([started('studio_1', { task_type: 'studio_agent' })])[0]!
    const native = subagents([started('native_1')])[0]!
    expect(subagentUsage(studio)).toBe('Without cache reads: 0 · Cache reads: 0 · API Cost: $0')
    expect(subagentUsage(native)).toBe('Without cache reads: 0 · Cache reads: 0 · API Cost: unavailable')
    const reported = subagents([
      started('native_1'),
      frame('task_progress', {
        task_id: 'native_1', usage: { total_tokens: 25, total_cost_usd: 0 },
      }),
    ])[0]!
    expect(subagentUsage(reported)).toBe('Without cache reads: 0 · Cache reads: 0 · Unclassified: 25 · API Cost: $0')
  })

  it('keeps compact uncached/cache counters separate after archiving and newer live updates', () => {
    const row = saved({ total_tokens: 12_200_000, total_uncached_tokens: 185_000, total_cached_tokens: 12_000_000 })
    expect(subagentUsage(subagents([], [row])[0]!))
      .toBe('Without cache reads: 185K · Cache reads: 12M · Unclassified: 15K · API Cost: $0.03')
    const progress = { ...frame('task_progress', { task_id: row.id, usage: {
      total_tokens: 12_400_000, total_uncached_tokens: 200_000, total_cached_tokens: 12_000_000,
    } }), created_at: '2026-09-04T10:02:00Z' }
    expect(subagents([progress], [row])[0]).toMatchObject({ tokens: 12_400_000,
      uncachedTokens: 200_000, cachedTokens: 12_000_000 })
  })

  it('retains last valid counters when a terminal report omits or contains invalid telemetry', () => {
    const list = subagents([
      started('studio_1', { task_type: 'studio_agent' }),
      frame('task_progress', {
        task_id: 'studio_1', usage: { total_tokens: 2100, total_cost_usd: 0.12, tool_uses: 5 },
      }),
      frame('task_progress', {
        task_id: 'studio_1', usage: { total_tokens: NaN, total_cost_usd: -1, tool_uses: Infinity },
      }),
      frame('task_notification', { task_id: 'studio_1', status: 'stopped' }),
    ])
    expect(list[0]).toMatchObject({ status: 'stopped', tokens: 2100, costUsd: 0.12, toolUses: 5 })
    expect(subagentUsage(list[0]!)).toContain('API Cost: $0.12')
  })

  it('opens on task_started and closes on the notification', () => {
    const list = subagents([
      started('task_a'),
      frame('task_progress', {
        task_id: 'task_a',
        last_tool_name: 'Grep',
        usage: { total_tokens: 4200, tool_uses: 7 },
      }),
      heldResult(),
      frame('task_notification', {
        task_id: 'task_a',
        status: 'completed',
        summary: 'Read 20 files.',
      }),
    ])

    expect(list).toHaveLength(1)
    expect(list[0]).toMatchObject({
      id: 'task_a',
      description: 'Reading group 1',
      typeLabel: 'sub-agent',
      status: 'completed',
      holdsRun: true,
      tokens: 4200,
      toolUses: 7,
      lastTool: 'Grep',
      summary: 'Read 20 files.',
    })
    expect(running(list)).toEqual([])
  })

  it('takes a terminal state from a task_updated patch as well', () => {
    // A workflow stopped with `TaskStop` reports `killed` there and sometimes nowhere else.
    const list = subagents([
      started('wf_1', { task_type: 'local_workflow', description: 'review-changes' }),
      frame('task_updated', { task_id: 'wf_1', patch: { status: 'killed' } }),
    ])

    expect(list[0].typeLabel).toBe('workflow')
    // `killed` and `stopped` are the two vocabularies for one thing; the UI says one.
    expect(list[0].status).toBe('stopped')
  })

  it('ignores a non-terminal patch', () => {
    const list = subagents([
      started('task_a'),
      frame('task_updated', { task_id: 'task_a', patch: { status: 'running' } }),
    ])

    expect(running(list)).toHaveLength(1)
  })

  it('shows a background shell without claiming the run waits for it', () => {
    // It is worth seeing — a dev server is running — but it never settles, so nothing is
    // held open for it. That is the same set the backend defers on.
    const list = subagents([
      started('shell_1', { task_type: 'background_shell', description: 'npm run dev' }),
    ])

    expect(list[0]).toMatchObject({ typeLabel: 'shell', status: 'running', holdsRun: false })
  })

  it('does not invent work whose start is not in the transcript', () => {
    expect(subagents([frame('task_notification', { task_id: 'gone', status: 'completed' })])).toEqual(
      [],
    )
  })
})

describe('what the end of a run does to it', () => {
  it('leaves work running across a result that only ended a turn', () => {
    const list = subagents([started('task_a'), heldResult()])
    expect(running(list)).toHaveLength(1)
  })

  it('stops work that was still running when the run ended', () => {
    // The CLI subprocess dies with the run and takes its sub-agents with it, so "running"
    // would be a claim about a process that is gone — and on a resumed task it would stay
    // on the screen forever.
    const list = subagents([started('task_a'), finalResult()])
    expect(list[0].status).toBe('stopped')
    expect(running(list)).toEqual([])
  })

  it('stops it on an error or a cancellation too', () => {
    for (const kind of ['error', 'cancelled']) {
      const list = subagents([started('task_a'), message({ role: 'system', kind })])
      expect(running(list)).toEqual([])
    }
  })

  it('keeps a run that started work again after the last one ended', () => {
    const list = subagents([
      started('task_a'),
      finalResult(),
      started('task_b', { description: 'Reading group 2' }),
    ])

    expect(list.map((entry) => entry.status)).toEqual(['stopped', 'running'])
  })
})

describe('the line a board card shows', () => {
  it('names the work rather than the frame', () => {
    expect(subagentActivity(started('task_a'))).toBe('sub-agent started · Reading group 1')
    expect(
      subagentActivity(
        frame('task_progress', {
          task_id: 'task_a',
          task_type: 'local_agent',
          description: 'Reading group 1',
          last_tool_name: 'Grep',
        }),
      ),
    ).toBe('sub-agent · Reading group 1 · Grep')
    expect(
      subagentActivity(
        frame('task_notification', {
          task_id: 'task_a',
          status: 'completed',
          summary: 'Read 20 files.',
        }),
      ),
    ).toBe('task completed · Read 20 files.')
  })

  it('stays silent for a frame that is not delegated work', () => {
    // An `init` is the session describing its own configuration, not doing anything.
    expect(subagentActivity(frame('init', { tools: ['Read'] }))).toBe('')
    expect(subagentActivity(message({ kind: 'text', text: 'Starting.' }))).toBe('')
  })
})
