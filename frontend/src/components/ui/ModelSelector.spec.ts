import { createSSRApp } from 'vue'
import { renderToString } from 'vue/server-renderer'
import { describe, expect, it } from 'vitest'
import type { MemberOptions } from '@/api/types'
import ModelSelector from './ModelSelector.vue'

const options: MemberOptions = {
  agent_backends: {}, permission_modes: [], effort_levels: [], common_tools: [], studio_tool_prefix: '',
  suggested_models: [
    { id: 'sonnet', label: 'Sonnet', group: 'Aliases', note: '', backend: 'claude_code' },
    { id: 'gpt-test', label: 'GPT Test', group: 'Models', note: '', backend: 'codex' },
    { id: 'qwen-test', label: 'Qwen Test', group: 'Models', note: '', backend: 'qwen' },
  ],
}

async function markup(backend: string | null, model: string | null): Promise<string> {
  return renderToString(createSSRApp(ModelSelector, { backend, model, options }))
}

describe('model selector', () => {
  it('shows three icon-only backend buttons and only the selected backend models', async () => {
    const html = await markup('codex', 'gpt-test')
    expect((html.match(/<button\b/g) ?? []).length).toBe(3)
    expect(html).toContain('aria-label="codex" aria-pressed="true"')
    expect(html).toContain('GPT Test')
    expect(html).not.toContain('Sonnet')
    expect(html).not.toContain('Qwen Test')
  })

  it('offers only the backends the caller can run', async () => {
    const html = await renderToString(createSSRApp(ModelSelector, {
      backend: 'claude_code', model: 'sonnet', options, backends: ['claude_code'],
    }))
    expect((html.match(/<button\b/g) ?? []).length).toBe(1)
    expect(html).toContain('aria-label="claude_code" aria-pressed="true"')
    expect(html).toContain('Sonnet')
    expect(html).not.toContain('GPT Test')
  })

  it('keeps an unlisted model editable in the same row', async () => {
    const html = await markup('codex', 'future-model')
    expect(html).toContain('value="future-model"')
    expect(html).toContain('<input')
  })
})
