/**
 * The sub-agent list's rows (§10.3): each Studio-managed worker shows the effort Effort
 * Solver chose for it (§10.11), on the control every other run's effort is read from.
 */
import { createSSRApp } from 'vue'
import { renderToString } from 'vue/server-renderer'
import { afterEach, describe, expect, it, vi } from 'vitest'

import type { AgentMessage, TaskSubagent } from '@/api/types'
import { setLocale } from '@/i18n'
import { subagents, type Subagent } from '@/lib/subagents'
import SubagentSummary from './SubagentSummary.vue'

const saved = (overrides: Partial<TaskSubagent> = {}): TaskSubagent => ({
  id: 'studio_1', task_id: 'task_1', member_id: 'team_default__dev',
  description: 'Review implementation', backend: 'codex', model: 'gpt-test', effort: 'xhigh',
  status: 'completed', session_id: 'child_session', summary: 'Review complete.',
  total_tokens: 1500, total_cost_usd: 0.03, num_turns: 2, tool_uses: 4,
  created_at: '2026-09-04T10:00:00Z', updated_at: '2026-09-04T10:01:00Z',
  finished_at: '2026-09-04T10:01:00Z', ...overrides,
})

const nativeStart: AgentMessage = {
  id: 'msg_native', task_id: 'task_1', member_id: 'team_default__dev', role: 'system', kind: 'system',
  text: 'task_started', created_at: '2026-09-04T10:00:00',
  data: { task_id: 'native_1', task_type: 'local_agent', description: 'Reading group 1' },
}

/** The list is folded until its reader opens it, and that choice is read from storage. */
async function render(entries: Subagent[], locale: 'en' | 'ru' = 'en'): Promise<string> {
  await setLocale(locale)
  vi.stubGlobal('window', { localStorage: { getItem: () => 'open', setItem: () => undefined } })
  return renderToString(createSSRApp(SubagentSummary, { entries }))
}

const efforts = (html: string, label: string) =>
  [...html.matchAll(new RegExp(`aria-label="${label}: ([^"]*)"`, 'g'))].map((match) => match[1])

describe('effort in the sub-agent list', () => {
  afterEach(async () => {
    vi.unstubAllGlobals()
    await setLocale('en')
  })

  it('shows the effort chosen for each Studio sub-agent', async () => {
    const html = await render(subagents([], [
      saved(),
      saved({ id: 'studio_2', description: 'Write tests', effort: 'low', status: 'running', finished_at: null }),
    ]))
    expect(efforts(html, 'Sub-agent effort')).toEqual(['xhigh', 'low'])
    // Read-only: the same widget as a task's effort, with nothing to drag.
    expect(html).not.toContain('type="range"')
  })

  it('draws nothing for work that reports no effort', async () => {
    // A runtime's own sub-agent, and a row saved before effort was recorded for workers.
    const html = await render(subagents([nativeStart], [saved({ effort: null })]))
    expect(html).toContain('Reading group 1')
    expect(html).toContain('Review implementation')
    expect(efforts(html, 'Sub-agent effort')).toEqual([])
  })

  it('names the control in the reader’s language', async () => {
    const html = await render(subagents([], [saved({ effort: 'medium' })]), 'ru')
    expect(efforts(html, 'Усилие субагента')).toEqual(['medium'])
  })
})
