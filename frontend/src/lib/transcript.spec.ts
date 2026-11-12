/**
 * What each verbosity level puts on the screen, how it groups it into blocks, and what
 * the one line under them says.
 *
 * The grouping is the part worth pinning: it decides what a reader sees of a run, and it
 * is plain data over a message list, so it can be checked without a transcript to scroll.
 */
import { describe, expect, it } from 'vitest'

import type { AgentMessage } from '@/api/types'
import {
  editStat,
  isFileEdit,
  isStudioCall,
  isSubagentSpawn,
  isVerbosity,
  liveLine,
  readVerbosity,
  resultsByCall,
  transcriptBlocks,
  transcriptRows,
  upsertTranscript,
  isQueued,
} from '@/lib/transcript'

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

/** The tool calls between two things anyone said — what a tools block holds. */
const call = (name: string, input: Record<string, unknown> = {}, data = {}) =>
  message({ kind: 'tool_use', text: name, data: { name, input, ...data } })

/** A turn the runtime priced, as `_attach_usage` writes it onto the first of its events. */
const spent = (output: number, overrides: Partial<AgentMessage> = {}) =>
  message({ text: 'Working.', data: { usage: { output_tokens: output } }, ...overrides })

const kinds = (rows: ReturnType<typeof transcriptRows>) => rows.map((row) => row.message.kind)

describe('compressed task context (§10.4)', () => {
  it.each(['default', 'verbose'] as const)('hides archive accounting metadata in %s', (level) => {
    const accounting = message({ kind: 'accounting', role: 'system', data: { evidence: [] } })
    expect(transcriptRows([accounting], level)).toEqual([])
    expect(liveLine([accounting], level).activity).toBe('')
  })

  it.each(['default', 'verbose'] as const)('keeps the handoff visible in %s', (level) => {
    const summary = message({ kind: 'compressed summary', text: 'Completed work. Next steps.' })
    expect(kinds(transcriptRows([summary, message({ kind: 'tool_result', role: 'system' })], level)))
      .toContain('compressed summary')
  })
})

describe('bounded file observation (§10.4)', () => {
  it.each(['default', 'verbose'] as const)('keeps preparation and incomplete evidence visible in %s', (level) => {
    const feed = [
      message({ role: 'system', kind: 'file_observation_status', text: 'Preparing file observation.' }),
      message({ role: 'system', kind: 'file_observation_status', text: 'Still scanning the project.' }),
      message({ role: 'system', kind: 'file_observation_error', text: 'Scan time limit reached; continuing with tool evidence.' }),
      message({ role: 'system', kind: 'file_observation', data: { paths: ['file.txt'] } }),
    ]
    expect(kinds(transcriptRows(feed, level)).slice(0, 3)).toEqual([
      'file_observation_status', 'file_observation_status', 'file_observation_error',
    ])
    expect(transcriptRows(feed, level).some((row) => row.message.kind === 'file_observation'))
      .toBe(level === 'verbose')
  })
})

describe('closing summary (§10.4)', () => {
  const report = () => message({ kind: 'summary', text: 'Detailed report.', data: { result_type: 'done' } })

  it.each(['default', 'verbose'] as const)('highlights the closing reply in %s without changing stored messages', (level) => {
    const original = report()
    const closing = message({ text: 'Done. Tests passed.', data: { usage: { output_tokens: 12 } } })
    const feed = [original, message({ kind: 'tool_result', role: 'system' }), closing,
      message({ kind: 'result', role: 'system' }), message({ kind: 'status', role: 'system' })]
    const rows = transcriptRows(feed, level)
    expect(rows.find((row) => row.key === original.id)?.message.kind).toBe('text')
    expect(rows.find((row) => row.key === closing.id)?.message).toEqual({
      ...closing, kind: 'summary', data: { ...closing.data, result_type: 'done' },
    })
    expect(original.kind).toBe('summary')
    expect(closing.kind).toBe('text')
    expect(closing.data).not.toHaveProperty('result_type')
  })

  it('shows an identical report only once', () => {
    expect(kinds(transcriptRows([report(), message({ text: 'Detailed report.' })], 'default'))).toEqual(['summary'])
  })

  it.each(['status', 'continued', 'result', 'error', 'cancelled', 'result_missing', 'note', 'review'])('does not borrow a closing reply across %s', (kind) => {
    const closing = message({ text: 'A later reply.' })
    const rows = transcriptRows([report(), message({ kind, role: 'system' }), closing], 'verbose')
    expect(rows.at(-1)?.message.kind).toBe('text')
    expect(rows[0]?.message.kind).toBe('summary')
  })

  it('keeps the report when there is no closing reply or work continues after the text', () => {
    const original = report()
    expect(transcriptRows([original], 'default')[0]?.message).toEqual(original)
    const rows = transcriptRows([original, message({ text: 'Still working.' }), call('Bash')], 'verbose')
    expect(rows[0]?.message.kind).toBe('summary')
    expect(rows[1]?.message.kind).toBe('text')
  })

  it('does not promote another author or a human submission', () => {
    for (const role of ['human', 'user', 'assistant'] as const) {
      const rows = transcriptRows([report(), message({ role, member_id: 'other', text: 'Reply.' })], 'default')
      expect(kinds(rows)).toEqual(['summary', 'text'])
    }
    expect(kinds(transcriptRows([message({ ...report(), role: 'human' }), message({ text: 'Reply.' })], 'default'))).toEqual(['summary', 'text'])
  })

  it('uses the last reply and preserves the result type for each run', () => {
    const rows = transcriptRows([
      report(), message({ text: 'More detail.' }), message({ text: 'First done.' }),
      message({ kind: 'status', role: 'system' }),
      message({ ...report(), data: { result_type: 'question' } }), message({ text: 'Which option?' }),
    ], 'default')
    const summaries = rows.filter((row) => row.message.kind === 'summary')
    expect(summaries.map((row) => [row.message.text, row.message.data?.result_type])).toEqual([
      ['First done.', 'done'], ['Which option?', 'question'],
    ])
  })
})

describe('what a level shows', () => {
  const feed = [
    message({ role: 'system', kind: 'status', text: 'created → todo' }),
    message({ kind: 'text', text: 'Starting.' }),
    call('Bash', { command: 'npm test' }),
    message({ role: 'system', kind: 'tool_result', text: '12 passed' }),
    message({ role: 'system', kind: 'system', text: 'init' }),
  ]

  it('keeps the task’s own history at every level, and the tool calls from Default up', () => {
    // The audit trail carries `role: "system"` like the noise does, and hiding it would
    // defeat the point of the transcript being the history (§10.4).
    for (const level of ['default', 'verbose'] as const) {
      expect(kinds(transcriptRows(feed, level))).toContain('status')
    }
    expect(kinds(transcriptRows(feed, 'default'))).toEqual([
      'status',
      'text',
      'tool_use',
      /* tool_result and the init frame are the bulky half */
    ])
  })

  it('adds the bulky half rather than rearranging anything', () => {
    expect(kinds(transcriptRows(feed, 'verbose'))).toEqual([
      'status',
      'text',
      'tool_use',
      'tool_result',
      'system',
    ])
  })

  it('carries the tail of the whole feed below Verbose, and nothing at Verbose', () => {
    // The `init` frame is the last thing logged and the last thing both levels below Verbose
    // hide, which is the case the line exists for. At Verbose it is already the last row,
    // and a copy of it under the scroller would say nothing.
    expect(liveLine(feed, 'default').activity).toBe('init')
    expect(liveLine(feed, 'verbose').activity).toBe('')
  })

  it('recognises only the two levels', () => {
    expect(isVerbosity('default')).toBe(true)
    expect(isVerbosity('verbose')).toBe(true)
    expect(isVerbosity('compact')).toBe(false)
    expect(isVerbosity('quiet')).toBe(false)
    expect(isVerbosity(null)).toBe(false)
  })

  it('reads a remembered Advanced or Compact as Default, and anything unknown as Default', () => {
    expect(readVerbosity('advanced')).toBe('default')
    expect(readVerbosity('compact')).toBe('default')
    expect(readVerbosity('verbose')).toBe('verbose')
    expect(readVerbosity('quiet')).toBe('default')
    expect(readVerbosity(null)).toBe('default')
  })
})

describe('blocks', () => {
  const blockTypes = (blocks: ReturnType<typeof transcriptBlocks>) => blocks.map((block) => block.type)

  type Tools = Extract<ReturnType<typeof transcriptBlocks>[number], { type: 'tools' }>

  it('folds consecutive tool calls into one block, whatever the tools, and counts the calls', () => {
    const feed = [
      message({ kind: 'text', text: 'Looking.' }),
      call('Read', { file_path: 'a.ts' }),
      call('Grep', { pattern: 'dash' }),
      call('mcp__other__lookup'),
      call('Skill', { skill: 'voxarium_studio:studio' }),
      message({ kind: 'text', text: 'Found it.' }),
      call('Bash', { command: 'npm test' }),
    ]
    const blocks = transcriptBlocks(feed, 'default')

    expect(blockTypes(blocks)).toEqual(['message', 'tools', 'message', 'tools'])
    const first = blocks[1] as Tools
    expect(first.calls).toBe(4)
    expect(first.key).toBe(feed[1]!.id)
    expect(first.studio).toBe(false)
  })

  it('gives a run of Studio tools a block of its own, results and all', () => {
    const feed = [
      call('Read', { file_path: 'a.ts' }, { id: 'toolu_1' }),
      call('mcp__voxarium_studio__get_task', {}, { id: 'toolu_2' }),
      message({ role: 'system', kind: 'tool_result', text: 'DS-05', data: { tool_use_id: 'toolu_2' } }),
      call('mcp__voxarium_studio__spawn_subagent', {}, { id: 'toolu_3' }),
      call('Bash', { command: 'npm test' }),
    ]
    for (const level of ['default', 'verbose'] as const) {
      const blocks = transcriptBlocks(feed, level) as Tools[]
      expect(blocks.map((block) => [block.studio, block.calls])).toEqual([[false, 1], [true, 2], [false, 1]])
    }
    const studio = transcriptBlocks(feed, 'verbose')[1] as Tools
    expect(studio.entries[0]!.results.map((result) => result.text)).toEqual(['DS-05'])
  })

  it('keeps a frame before the first call in the block the call opens, whichever family', () => {
    const feed = [
      call('Read', { file_path: 'a.ts' }),
      message({ kind: 'text', text: 'Checking the board.' }),
      message({ role: 'system', kind: 'tool_result', text: 'orphan' }),
      call('mcp__voxarium_studio__list_tasks'),
    ]
    const blocks = transcriptBlocks(feed, 'verbose')
    expect(blockTypes(blocks)).toEqual(['tools', 'message', 'tools'])
    expect((blocks[2] as Tools).studio).toBe(true)
    expect((blocks[2] as Tools).entries).toHaveLength(2)
  })

  it('draws thinking as a block of its own, and drops the empty blocks a runtime redacts', () => {
    const feed = [
      call('Read', { file_path: 'a.ts' }),
      message({ kind: 'thinking', text: 'The cooldown is off by one.' }),
      message({ kind: 'thinking', text: '   ' }),
      call('Edit', { file_path: 'a.ts' }),
    ]
    expect(blockTypes(transcriptBlocks(feed, 'default'))).toEqual(['tools', 'thinking', 'tools'])
    expect(blockTypes(transcriptBlocks(feed, 'verbose'))).toEqual(['tools', 'thinking', 'tools'])
  })

  it('puts each result under its call at Verbose, and keeps a busy run in one block', () => {
    const read = call('Read', { file_path: 'a.ts' }, { id: 'toolu_1' })
    const bash = call('Bash', { command: 'npm test' }, { id: 'toolu_2' })
    const feed = [
      message({ role: 'system', kind: 'system', text: 'init' }),
      read,
      bash,
      message({ role: 'system', kind: 'tool_result', text: 'export const a = 1', data: { tool_use_id: 'toolu_1' } }),
      message({ role: 'system', kind: 'system', text: 'task_progress' }),
      message({ role: 'system', kind: 'tool_result', text: '12 passed', data: { tool_use_id: 'toolu_2' } }),
      message({ kind: 'text', text: 'Tests pass.' }),
    ]
    const blocks = transcriptBlocks(feed, 'verbose')

    // The init frame comes before any call, so it is a row; the progress frame between two
    // results stays in the block rather than splitting it.
    expect(blockTypes(blocks)).toEqual(['message', 'tools', 'message'])
    const tools = blocks[1] as Extract<(typeof blocks)[number], { type: 'tools' }>
    expect(tools.calls).toBe(2)
    expect(tools.entries.map((entry) => entry.message.kind)).toEqual(['tool_use', 'tool_use', 'system'])
    expect(tools.entries[0]!.results.map((result) => result.text)).toEqual(['export const a = 1'])
    expect(tools.entries[1]!.results.map((result) => result.text)).toEqual(['12 passed'])
    // Default draws the same calls in the same block, without the results.
    const quieter = transcriptBlocks(feed, 'default')
    expect(blockTypes(quieter)).toEqual(['tools', 'message'])
  })

  it('keeps a delegated agent’s work inside the block of the call that started it', () => {
    const feed = [
      call('Agent', { description: 'Review' }, { id: 'toolu_9' }),
      message({ kind: 'text', text: 'Reviewing.', data: { parent_tool_use_id: 'toolu_9' } }),
      call('Read', { file_path: 'a.ts' }, { parent_tool_use_id: 'toolu_9' }),
      message({ kind: 'text', text: 'The review is done.' }),
    ]
    const blocks = transcriptBlocks(feed, 'default')

    expect(blockTypes(blocks)).toEqual(['tools', 'message'])
    const tools = blocks[0] as Extract<(typeof blocks)[number], { type: 'tools' }>
    expect(tools.calls).toBe(2)
    expect(tools.entries).toHaveLength(3)
  })
})

describe('Studio calls (§10.3, §10.4)', () => {
  it('recognises the Studio server by its prefix, and a spawn by its bare name', () => {
    const spawn = call('mcp__voxarium_studio__spawn_subagent', { description: 'Review' })
    expect(isStudioCall(spawn)).toBe(true)
    expect(isSubagentSpawn(spawn)).toBe(true)
    expect(isStudioCall(call('mcp__voxarium_studio__list_tasks'))).toBe(true)
    expect(isSubagentSpawn(call('mcp__voxarium_studio__list_tasks'))).toBe(false)
    expect(isStudioCall(call('mcp__other__spawn_subagent'))).toBe(false)
    expect(isSubagentSpawn(call('spawn_subagent'))).toBe(false)
    expect(isStudioCall(call('Agent'))).toBe(false)
  })

  it('finds every call’s result whatever the level draws', () => {
    const result = message({ role: 'system', kind: 'tool_result', text: '{}', data: { tool_use_id: 'toolu_7' } })
    const map = resultsByCall([call('Read', {}, { id: 'toolu_7' }), result, message({ kind: 'text' })])
    expect([...map.keys()]).toEqual(['toolu_7'])
    expect(map.get('toolu_7')).toBe(result)
  })
})

describe('Default', () => {
  it('keeps a person’s words, the history, errors and the Producer’s cards beside the work', () => {
    // Compact used to be the level that kept only these; since 2026-11-10 it is gone, and
    // Default is the quietest reading there is.
    const feed = [
      message({ role: 'human', kind: 'note', text: 'Check the edge case.' }),
      message({ role: 'system', kind: 'status', text: 'In progress → Needs review' }),
      message({ role: 'system', kind: 'error', text: 'The session failed.' }),
      message({ role: 'system', kind: 'effort_solver', data: {} }),
      message({ role: 'system', kind: 'system', text: 'init' }),
      call('Edit', { file_path: 'dash.ts' }, { edit: { insertions: 4, deletions: 2 } }),
    ]
    expect(kinds(transcriptRows(feed, 'default'))).toEqual(['note', 'status', 'error', 'effort_solver', 'tool_use'])
  })
})

describe('the line under the rows', () => {
  it.each(['default'] as const)('keeps structured model metadata without textual effort in %s', (level) => {
    const entry = message({
      role: 'system', kind: 'model',
      text: 'Running this task on model model-test, effort high — set on the task, not on Developer.',
      data: { model: 'model-test', effort: 'high', source: 'task' },
    })
    expect(liveLine([entry], level).activity).toBe('Model: model-test · Set on this task')
    expect(liveLine([entry], level).activity).not.toContain('high')
    expect(transcriptRows([entry], 'verbose')[0]?.message).toEqual(entry)
  })

  it('retains the source when a structured model notice has no model name', () => {
    const entry = message({
      role: 'system', kind: 'model', text: 'Running with effort high.',
      data: { model: null, effort: 'high', source: 'task' },
    })
    expect(liveLine([entry], 'default').activity).toBe('Set on this task')
    expect(liveLine([{ ...entry, data: { model: 'model-test', effort: 'high' } }], 'default').activity)
      .toBe('Model: model-test')
  })

  it.each(['default'] as const)('preserves unstructured historical model text and prose in %s', (level) => {
    const text = 'Running this task on model model-test, effort high.'
    const historical = message({ role: 'system', kind: 'model', text })
    expect(liveLine([historical], level).activity).toBe(text)
    const prose = message({ kind: 'text', text, data: { model: 'model-test', effort: 'high' } })
    expect(liveLine([prose], level).activity).toBe(text)
  })

  it('names the newest thing logged, at Default, which hides some of it', () => {
    const feed = [
      message({ kind: 'text', text: 'Looking at the movement code.' }),
      call('Read', { file_path: 'a.ts' }),
      call('Bash', { command: 'npm test' }),
    ]

    expect(liveLine(feed, 'default').activity).toBe('Bash · npm test')
  })

  it('shows what the level is hiding — which is the whole reason to look at it', () => {
    // The result of the call that just ran is a Verbose row and nothing else, so below
    // Verbose this line is the only place the answer appears at all.
    const feed = [
      call('Bash', { command: 'npm test' }),
      message({ role: 'system', kind: 'tool_result', text: '12 passed' }),
    ]

    expect(liveLine(feed, 'default').activity).toBe('12 passed')
    expect(liveLine(feed, 'verbose').activity).toBe('')
  })

  it('is the tail of the log, so a block above it takes nothing away', () => {
    const feed = [
      message({ kind: 'text', text: 'Done.' }),
      message({ role: 'system', kind: 'status', text: 'In progress → Needs review' }),
      call('Bash', { command: 'git log' }),
    ]

    expect(liveLine(feed, 'default').activity).toBe('Bash · git log')
  })

  it('counts every line, so the same tool twice still blinks twice', () => {
    const feed = [
      message({ kind: 'text', text: 'Running it again.' }),
      call('Bash', { command: 'npm test' }),
      call('Bash', { command: 'npm test' }),
    ]

    // Same words, different count — the view keys its animation on the number, and an
    // element that is not replaced never replays the flash.
    const line = liveLine(feed, 'default')
    expect(line.activity).toBe('Bash · npm test')
    expect(line.seq).toBe(3)
  })

  it('flattens a payload onto one line and clips it', () => {
    // A tool result is many lines and a traceback is more. Wrapped, they would push the
    // token count beside them out of the box before CSS had a single line to truncate.
    const traceback = `Traceback (most recent call last):\n  File "a.py", line 1\n${'x'.repeat(200)}`
    const activity = liveLine([message({ kind: 'tool_result', text: traceback })], 'default')
      .activity

    expect(activity).not.toContain('\n')
    expect(activity.startsWith('Traceback (most recent call last): File "a.py", line 1 x')).toBe(
      true,
    )
    expect(activity).toHaveLength(161)
    expect(activity.endsWith('…')).toBe(true)
  })

  it('says nothing about a message that carries no text', () => {
    // A `doc` entry is `text`-bearing and a heartbeat frame is not; an empty line would
    // blank the tail rather than leave the last real one standing.
    const feed = [
      message({ kind: 'doc', text: 'Wrote document qa/notes.md' }),
      message({ role: 'system', kind: 'system', text: '' }),
    ]

    expect(liveLine(feed, 'default').activity).toBe('Wrote document qa/notes.md')
  })

  it('sums what the model wrote, at every level', () => {
    // One accounting per assistant message, so these add up — a turn that answered and then
    // called two tools is one API response, and the backend puts its usage on one event.
    const feed = [spent(1200), call('Bash', { command: 'npm test' }), spent(340)]

    for (const level of ['default', 'verbose'] as const) {
      expect(liveLine(feed, level).tokens).toBe(1540)
    }
  })

  it('reconciles streamed assistant output with the result instead of counting it twice', () => {
    const feed = [spent(10), spent(20), spent(30, { role: 'system', kind: 'result' })]
    for (const level of ['default', 'verbose'] as const) {
      expect(liveLine(feed, level).tokens).toBe(30)
    }
    expect(liveLine([spent(10), spent(40, { role: 'system', kind: 'result' })], 'default').tokens)
      .toBe(40)
  })

  it('counts output increments per provider response across repeated frames and continued results', () => {
    const reply = (id: string, output: number) => spent(output, {
      data: { provider_response_id: id, usage: { output_tokens: output } },
    })
    const feed = [reply('response_a', 10), reply('response_a', 20), reply('response_a', 12),
      reply('response_b', 7)]
    for (const level of ['default', 'verbose'] as const) {
      expect(liveLine(feed, level).tokens).toBe(27)
    }
    feed.push(message({ role: 'system', kind: 'result',
      data: { continued: true, usage: { output_tokens: 27 } } }),
      reply('response_a', 20), reply('response_c', 5), spent(5, { role: 'system', kind: 'result' }))
    expect(liveLine(feed, 'default').tokens).toBe(32)
  })

  it('uses the latest repaired output before the cutoff while retaining dialogue and new output', () => {
    const evidence = (output: number) => [{ kind: 'result', role: 'system', text: '',
      created_at: '2026-09-04T10:00:00Z', data: { usage: { output_tokens: output } } }]
    const repair = (output: number, created_at: string) => message({ kind: 'accounting', role: 'system',
      created_at, data: { repair: { cutoff_at: '2026-09-04T10:01:00' }, evidence: evidence(output) } })
    const feed = [spent(200, { text: 'Original answer' }), spent(400, { role: 'system', kind: 'result' }),
      repair(25, '2026-09-04T11:00:00Z'), repair(30, '2026-09-04T12:00:00Z'),
      spent(5, { text: 'Fresh answer', created_at: '2026-09-04T13:00:00Z' })]
    expect(liveLine(feed, 'default')).toMatchObject({ tokens: 35, activity: 'Fresh answer' })
    expect(transcriptRows(feed, 'default').some(row => row.message.text === 'Original answer')).toBe(true)
  })

  it('retains canonical output after compression or archive without charging retained results twice', () => {
    for (const kind of ['accounting', 'compressed summary', 'context_summary']) {
      const retained = message({ kind, data: { evidence: [
        { kind: 'result', role: 'system', created_at: '2026-09-04T10:00:00Z', data: { usage: { output_tokens: 30 } } },
        { kind: 'system', text: 'usage_update', created_at: '2026-09-04T10:00:01Z', data: { usage: { output_tokens: 30 } } },
      ] } })
      expect(liveLine([retained, spent(5, { created_at: '2026-09-04T11:00:00Z' })], 'default').tokens).toBe(35)
    }
  })

  it('closes continued segments separately before summing their output', () => {
    const feed = [spent(10), spent(20),
      message({ role: 'system', kind: 'result', data: { continued: true, usage: { output_tokens: 30 } } }),
      message({ role: 'system', kind: 'continued' }),
      spent(5), spent(7), spent(12, { role: 'system', kind: 'result' })]
    expect(liveLine(feed, 'default').tokens).toBe(42)
  })

  it('counts Codex turn deltas once when the run result repeats their total', () => {
    const turn = (output: number) => spent(output, { role: 'system', kind: 'system', text: 'turn.completed' })
    const feed = [turn(10), turn(20)]
    expect(liveLine(feed, 'default').tokens).toBe(30)
    expect(liveLine([...feed, spent(30, { role: 'system', kind: 'result' })], 'default').tokens).toBe(30)
  })

  it('retains output totals for older transcripts that only recorded results', () => {
    const feed = [spent(30, { role: 'system', kind: 'result' }),
      spent(12, { role: 'system', kind: 'result' })]
    expect(liveLine(feed, 'default').tokens).toBe(42)
  })

  it('counts nothing for a transcript written before the backend recorded any', () => {
    // Zero is what makes the view say nothing at all, rather than "0 tokens" about a run
    // whose runtime never reported a figure.
    const feed = [
      message({ kind: 'text', text: 'Working.' }),
      message({ kind: 'text', text: 'Still working.', data: { usage: {} } }),
      message({ kind: 'text', text: 'Done.', data: { usage: { output_tokens: 'lots' } } }),
    ]

    expect(liveLine(feed, 'default').tokens).toBe(0)
  })

  it('words history from its data, the way its row does, and not from the stored English', () => {
    const status = (data: Record<string, unknown>, text = 'in_progress → needs_review — submitted') =>
      message({ role: 'system', kind: 'status', text, data })
    const edited = (data: Record<string, unknown>) =>
      message({ role: 'system', kind: 'edited', text: 'Edited brief, title', data })

    const line = (entry: AgentMessage) => liveLine([entry], 'default').activity
    expect(line(status({ from: 'in_progress', to: 'needs_review', reason: 'submitted' }))).toBe(
      'In progress → Needs review · submitted',
    )
    expect(line(status({ from: null, to: 'todo', reason: '' }, 'created → todo'))).toBe('Todo · created')
    expect(line(edited({ fields: { title: {}, brief: {} } }))).toBe('Edited brief, title')
    // An entry from before either carried its data keeps the words it was stored with.
    expect(line(status({}))).toBe('in_progress → needs_review — submitted')
    expect(line(edited({}))).toBe('Edited brief, title')
  })
})

describe('a writing call', () => {
  it('is recognised by its own name, MCP server or not', () => {
    expect(isFileEdit(call('Edit', { file_path: 'a.ts' }))).toBe(true)
    expect(isFileEdit(call('mcp__voxarium_studio__write_doc', { path: 'qa/notes.md' }))).toBe(true)
    expect(isFileEdit(call('Read', { file_path: 'a.ts' }))).toBe(false)
    // A shell command may have written anything at all; claiming it wrote a file is a guess.
    expect(isFileEdit(call('Bash', { command: 'sed -i s/a/b/ a.ts' }))).toBe(false)
  })

  it('reports the size the backend counted, and nothing when nobody counted', () => {
    expect(editStat(call('Edit', {}, { edit: { insertions: 12, deletions: 3 } }))).toEqual({
      insertions: 12,
      deletions: 3,
    })
    // Both sides of an edit reach the transcript clipped to 500 characters, so a figure
    // derived here would be a guess — an older transcript shows the edit and no numbers.
    expect(editStat(call('Edit', { old_string: 'a', new_string: 'b' }))).toBeNull()
    expect(editStat(call('Edit', {}, { edit: { insertions: 12 } }))).toBeNull()
  })
})


it('shows current thinking in the counter, replaces it with final usage, and hides telemetry', () => {
  const prior = message({ data: { usage: { output_tokens: 10 } } })
  const counter = message({ role: 'system', kind: 'system', text: 'thinking_tokens', data: { estimated_tokens: 25 } })
  expect(liveLine([prior, counter], 'default').tokens).toBe(35)
  expect(transcriptRows([prior, counter], 'verbose').map((row) => row.message.id)).toEqual([prior.id])
  const answer = message({ data: { usage: { output_tokens: 40 } } })
  expect(liveLine([prior, counter, answer], 'default').tokens).toBe(50)
})


it('upserts newer state and ignores old replay without reordering ordinary messages', () => {
  const first = message({ id: 'state_counter', created_at: '2026-10-29T10:00:00', data: { estimated_tokens: 5 } })
  const next = message({ id: first.id, created_at: '2026-10-29T10:00:02', data: { estimated_tokens: 9 } })
  const ordinary = message({ created_at: '2026-10-29T10:00:01' })
  const feed = upsertTranscript([first, ordinary], next)
  expect(feed).toEqual([ordinary, next])
  expect(upsertTranscript(feed, first)).toBe(feed)
  expect(upsertTranscript(feed, ordinary)).toBe(feed)
})

it('replaces a queued message in place when its delivery arrives, and never the other way', () => {
  const before = message({ created_at: '2026-11-11T10:00:00' })
  const queued = message({ role: 'user', kind: 'note', text: 'use the blue one', created_at: '2026-11-11T10:00:01', data: { delivery: 'queued' } })
  const after = message({ created_at: '2026-11-11T10:00:02' })
  const delivered = { ...queued, data: { delivery: 'delivered' } }
  expect(isQueued(queued)).toBe(true)
  const feed = upsertTranscript([before, queued, after], delivered)
  expect(feed).toEqual([before, delivered, after])
  expect(isQueued(feed[1]!)).toBe(false)
  // The REST answer to the send can land after the socket has already said "delivered".
  expect(upsertTranscript(feed, queued)).toBe(feed)
})
