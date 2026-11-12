// @vitest-environment jsdom
import { createPinia } from 'pinia'
import { createApp, createSSRApp, nextTick } from 'vue'
import { renderToString } from 'vue/server-renderer'
import { describe, expect, it, vi } from 'vitest'

import type { AgentMessage } from '@/api/types'
import ConversationPanel from './ConversationPanel.vue'
import TaskChanges from './TaskChanges.vue'
import { useVerbosity } from '@/lib/transcript-verbosity'

const incomplete = 'File observation incomplete: scan time limit reached; tool evidence remains available.'
const unavailable = 'File observation unavailable: access denied.'
const sharedCheckout = 'This task ran in the project itself rather than its own checkout, so its changes cannot be told from anyone else\'s.'
vi.mock('@/api/client', () => ({ api: {
  taskChanges: vi.fn(async () => ({
    source: 'tools', files: [], temp_files: [], reason: sharedCheckout,
    observation_errors: [incomplete, unavailable], temp_errors: ['Temp listing failed.'],
  })),
  taskCommit: vi.fn(async () => { throw new Error('No commit draft') }),
} }))
vi.mock('@/ws/connection', () => ({ onEvent: () => () => {}, ws: { state: 'connected' } }))

describe('incomplete observation warnings are presentation-only hidden (§10.4)', () => {
  it.each(['default', 'verbose'] as const)('hides historical warnings and preparation at %s without hiding other errors', async (level) => {
    useVerbosity().value = level
    const messages = [
      ['file_observation_error', incomplete],
      ['file_observation_status', 'Preparing file observation.'],
      ['file_observation_error', unavailable],
    ].map(([kind, text], index) => ({
      id: `message_${index}`, task_id: 'task_1', member_id: 'dev', role: 'system',
      kind, text, data: {}, created_at: '2026-11-12T10:00:00',
    })) as AgentMessage[]
    const html = await renderToString(createSSRApp(ConversationPanel, {
      conversationId: 'task_1', messages, emptyText: 'Empty',
    }).use(createPinia()))
    expect(html).not.toContain(incomplete)
    expect(html).not.toContain('Preparing file observation.')
    expect(html).toContain(unavailable)
    expect(messages[0]!.text).toBe(incomplete)
  })

  it('hides the changes-panel warning while retaining unrelated and temp errors', async () => {
    localStorage.setItem('voxarium_studio.status.files', 'open')
    const host = document.createElement('div')
    const app = createApp(TaskChanges, { teamId: 'team', taskId: 'task_1', selected: null })
    app.use(createPinia()).mount(host)
    try {
      await Promise.resolve()
      await nextTick()
      expect(host.textContent).not.toContain(incomplete)
      expect(host.textContent).not.toContain(sharedCheckout)
      expect(host.textContent).toContain(unavailable)
      expect(host.textContent).toContain('Temp listing failed.')
    } finally {
      app.unmount()
      localStorage.clear()
    }
  })

  it.each(['file_observation_status', 'file_observation_error'])('does not leak %s through the live footer', async (kind) => {
    useVerbosity().value = 'default'
    const text = kind === 'file_observation_status' ? 'Preparing file observation.' : incomplete
    const html = await renderToString(createSSRApp(ConversationPanel, {
      conversationId: 'task_1', emptyText: 'Empty', messages: [{
        id: 'notice', task_id: 'task_1', member_id: 'dev', role: 'system',
        kind, text, data: {}, created_at: '2026-11-12T10:00:00',
      }],
    }).use(createPinia()))
    expect(html).not.toContain(text)
  })
})
