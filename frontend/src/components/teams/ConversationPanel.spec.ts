/**
 * The one transcript widget, rendered (§10.4, §10.9): the folds it draws at each level, the
 * same for a task and for a chat, and the Status column beside a task's transcript.
 */
import { createPinia } from 'pinia'
import { createSSRApp } from 'vue'
import { renderToString } from 'vue/server-renderer'
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest'

import type { AgentMessage } from '@/api/types'
import ConversationPanel from '@/components/teams/ConversationPanel.vue'
import SessionStatus from '@/components/teams/SessionStatus.vue'
import { setLocale } from '@/i18n'
import type { Verbosity } from '@/lib/transcript'
import { useVerbosity } from '@/lib/transcript-verbosity'

let sequence = 0
const message = (overrides: Partial<AgentMessage> = {}): AgentMessage => ({
  id: `msg_${(sequence += 1)}`,
  task_id: 'task_1',
  member_id: 'team_default__dev',
  role: 'assistant',
  kind: 'text',
  text: '',
  data: {},
  created_at: '2026-11-10T10:00:00',
  ...overrides,
})
const call = (name: string, input: Record<string, unknown>, id: string) =>
  message({ kind: 'tool_use', text: name, data: { id, name, input } })

const feed = [
  message({ role: 'user', text: 'Fix the dash cooldown.' }),
  message({ kind: 'thinking', text: 'The cooldown is checked before the timer ticks.' }),
  call('Read', { file_path: 'dash.ts' }, 'toolu_1'),
  call('Bash', { command: 'npm test' }, 'toolu_2'),
  message({ role: 'system', kind: 'tool_result', text: '12 passed', data: { tool_use_id: 'toolu_2' } }),
  message({ text: 'Fixed: the timer now ticks first.' }),
]

async function render(level: Verbosity, chat = false): Promise<string> {
  useVerbosity().value = level
  const app = createSSRApp(ConversationPanel, {
    conversationId: 'task_1', messages: feed, emptyText: 'Nothing yet.',
    ...(chat ? { chat: { name: 'Dev', running: false } } : {}),
  })
  return renderToString(app.use(createPinia()))
}

beforeAll(async () => {
  await setLocale('en')
})

describe('the transcript widget', () => {
  it.each([false, true])('draws thinking and the tool calls as collapsed dashed folds at Default (chat: %s)', async (chat) => {
    const html = await render('default', chat)
    expect(html.match(/data-transcript-fold/g)).toHaveLength(2)
    expect(html).toContain('border-dashed')
    expect(html).toContain('Thinking')
    expect(html).toContain('Tools used: 2')
    expect(html).toContain('Read, Bash')
    // Collapsed: the header names the work, the content is not rendered until opened.
    expect(html).not.toContain('The cooldown is checked')
    expect(html).not.toContain('dash.ts')
    expect(html).toContain('aria-expanded="false"')
    expect(html).toContain('Fixed: the timer now ticks first.')
    expect(html).toContain('<option value="default" selected')
  })

  it.each([false, true])('draws the same folds open at Verbose, the results inside them (chat: %s)', async (chat) => {
    const html = await render('verbose', chat)
    expect(html.match(/data-transcript-fold/g)).toHaveLength(2)
    expect(html).not.toContain('aria-expanded="false"')
    expect(html.match(/aria-expanded="true"/g)).toHaveLength(2)
    expect(html).toContain('The cooldown is checked before the timer ticks.')
    expect(html).toContain('dash.ts')
    expect(html).toContain('12 passed')
  })

  const studioFeed = (result?: AgentMessage) => [
    message({ text: 'Splitting the work.' }),
    call('Read', { file_path: 'dash.ts' }, 'toolu_1'),
    call('mcp__voxarium_studio__spawn_subagent', { description: 'Review dash.ts' }, 'toolu_2'),
    ...(result ? [result] : []),
    message({ text: 'Waiting for the review.' }),
  ]
  const spawned = message({
    role: 'system', kind: 'tool_result', data: { tool_use_id: 'toolu_2' },
    text: JSON.stringify({ id: 'subagent_1', effort: 'medium', effort_solver: {
      scores: { complexity: 2, creativity: 4, depth: 3, coverage: 7 }, effort: 2, effort_level: 'medium',
    } }),
  })

  async function renderFeed(messages: AgentMessage[], level: Verbosity, taskStatus = 'done'): Promise<string> {
    useVerbosity().value = level
    const app = createSSRApp(ConversationPanel, {
      conversationId: 'task_1', messages, emptyText: 'Nothing yet.', taskStatus,
    })
    return renderToString(app.use(createPinia()))
  }

  it('draws Studio tools as a violet dashed fold of their own', async () => {
    const html = await renderFeed(studioFeed(spawned), 'default')
    expect(html.match(/data-transcript-fold/g)).toHaveLength(2)
    expect(html.match(/data-studio-tools/g)).toHaveLength(1)
    expect(html).toContain('Tools used: 1')
    expect(html).toContain('Studio tools used: 1')
    expect(html).toContain('border-violet-500/60')
  })

  it('draws a spawn with the robot and its Effort Solver evaluation inside the fold', async () => {
    // Default keeps the fold shut, the robot on its header; Verbose opens it onto the row.
    const shut = await renderFeed(studioFeed(spawned), 'default')
    expect(shut).not.toContain('data-subagent-spawn')
    expect(shut).not.toContain('data-spawn-evaluation')
    const open = await renderFeed(studioFeed(spawned), 'verbose')
    expect(open).toContain('data-subagent-spawn')
    expect(open.match(/data-spawn-evaluation/g)).toHaveLength(1)
    expect(open).toContain('Complexity')
    expect(open).toContain('Mid')
  })

  it('says the evaluation is running while the spawn has not answered', async () => {
    expect(await renderFeed(studioFeed(), 'verbose', 'in_progress')).toContain('Effort Solver is scoring this subagent')
    const ended = await renderFeed(studioFeed(), 'verbose', 'cancelled')
    expect(ended).not.toContain('Effort Solver is scoring')
    expect(ended).not.toContain('data-spawn-evaluation')
  })

  it('offers only Default and Verbose', async () => {
    const html = await render('default')
    expect(html).toContain('<option value="default"')
    expect(html).toContain('<option value="verbose"')
    expect(html).not.toContain('value="compact"')
  })

  it('draws the Effort Solver evaluation of an Auto task at its start, outside a chat too', async () => {
    const evaluation = message({
      role: 'system', kind: 'effort_solver', text: 'high',
      data: { scores: { complexity: 6, creativity: 3, depth: 5, coverage: 8 }, effort: 3, effort_level: 'high' },
    })
    const html = await renderFeed([evaluation, message({ text: 'Starting.' })], 'default')
    expect(html).toContain('aria-label="Effort Solver"')
    expect(html).toContain('Complexity')
    expect(html).toContain('Starting.')
  })
})

describe('the Status column', () => {
  const statusMessages = () => [
    message({ role: 'system', kind: 'skills', data: {
      skills: ['voxarium_studio:studio', 'level-design'], loaded: ['voxarium_studio:studio'], missing: [], labels: {},
    } }),
    message({ role: 'system', kind: 'mcp', data: { servers: ['voxarium_studio'] } }),
    message({ role: 'system', kind: 'system', text: 'init', data: {
      subtype: 'init', mcp_servers: [{ name: 'voxarium_studio', status: 'connected' }],
    } }),
    call('Skill', { skill: 'level-design' }, 'toolu_3'),
  ]
  const renderStatus = () => {
    const messages = statusMessages()
    const app = createSSRApp({
      components: { SessionStatus },
      setup: () => ({ messages, writePaths: ['src/dash.ts', 'docs/dash.md'] }),
      template: '<SessionStatus :messages="messages" :subagents="[]" :write-paths="writePaths"><p>FILES</p></SessionStatus>',
    })
    return renderToString(app.use(createPinia()))
  }

  afterEach(() => vi.unstubAllGlobals())

  it('folds the skills, MCP servers and files to write to their headings by default, above the files', async () => {
    const html = await renderStatus()
    for (const text of ['Status', 'Delegated work', 'none started', 'Skills', 'MCP servers', 'Files to write'])
      expect(html).toContain(text)
    expect(html.match(/aria-expanded="false"/g)).toHaveLength(4)
    for (const text of ['level-design', 'voxarium_studio:studio', 'connected', 'src/dash.ts']) expect(html).not.toContain(text)
    expect(html.indexOf('MCP servers')).toBeLessThan(html.indexOf('Files to write'))
    expect(html.indexOf('Files to write')).toBeLessThan(html.indexOf('FILES'))
  })

  it('lists them when the reader left them open', async () => {
    const stored: Record<string, string> = {
      'voxarium_studio.status.skills': 'open',
      'voxarium_studio.status.mcp': 'open',
      'voxarium_studio.status.writePaths': 'open',
    }
    vi.stubGlobal('window', { localStorage: {
      getItem: (key: string) => stored[key] ?? null,
      setItem: (key: string, value: string) => { stored[key] = value },
    } })
    const html = await renderStatus()
    for (const text of ['level-design', 'used', 'voxarium_studio:studio', 'loaded', 'voxarium_studio', 'connected',
      'src/dash.ts', 'docs/dash.md']) expect(html).toContain(text)
  })
})
