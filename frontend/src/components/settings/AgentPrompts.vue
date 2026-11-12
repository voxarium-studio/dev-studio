<script setup lang="ts">
/**
 * Every line the Studio itself says to an agent, as editable settings (§10.3, §10.4).
 *
 * The defaults come from `GET /api/settings/prompts` — the same inventory the
 * orchestrator resolves at run time — so what this section shows *is* what a session is
 * told, not a description of it. Overrides go to `teams.prompts` in `config.yaml`
 * through the ordinary settings endpoint: `null` means the built-in text (and keeps
 * following it as the Studio evolves), `''` removes an optional fragment, and a
 * `required` one falls back to its default rather than opening a run with nothing.
 */
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'

import { api } from '@/api/client'
import type { AgentPromptsConfig, PromptSpec } from '@/api/types'
import { hasMessage, t, type MessageKey } from '@/i18n'
import { useSessionStore } from '@/stores/session'

const props = withDefaults(defineProps<{
  disabled?: boolean
  compact?: boolean
  autosave?: boolean
  saveSettings?: typeof api.updateSettings
}>(), {
  disabled: false,
  compact: false,
  autosave: false,
})
const emit = defineEmits<{ saving: [boolean] }>()
const session = useSessionStore()

const specs = ref<Record<string, PromptSpec> | null>(null)
const loadError = ref<string | null>(null)
const saveError = ref<string | null>(null)
const saving = ref<string | null>(null)
const failedKeys = reactive(new Set<string>())

const drafts = reactive<Record<string, string>>({})
/** What each draft was last seeded from, so an external config change only refreshes clean rows. */
const seeded = reactive<Record<string, string>>({})

/**
 * UI copy lives in the locale files (`agentPrompts.fragments.<key>`) rather than in the API
 * payload: the backend owns the text that is sent, the page owns how to explain it. A key the
 * backend adds later still renders, under "Other", with its key for a label. Producer
 * fragments are collected by prefix so new ones always stay in their own section (§10.11).
 *
 * The groups hold message keys, not text, so a title follows the interface language; the
 * prompts themselves and their `{placeholders}` are what agents are sent, and stay as they are.
 */
const GROUPS: { title: MessageKey; note: MessageKey; keys: string[] }[] = [
  {
    title: 'agentPrompts.groups.identity.title',
    note: 'agentPrompts.groups.identity.note',
    keys: [
      'identity',
      'identity_responsibility',
      'identity_manager',
      'identity_reports',
      'identity_peers',
    ],
  },
  {
    title: 'agentPrompts.groups.system.title',
    note: 'agentPrompts.groups.system.note',
    keys: [
      'task_context',
      'task_temp',
      'studio_skill',
      'no_studio_tools',
      'member_skills',
      'docs_location',
      'project_memory',
      'subagents',
      'code_graph',
      'isolated_checkout',
      'outside_project',
      'finish',
      'finish_tool',
    ],
  },
  {
    title: 'agentPrompts.groups.openers.title',
    note: 'agentPrompts.groups.openers.note',
    keys: ['resume', 'resume_notes', 'review_changes', 'stall_continue', 'mid_run_message', 'result_required', 'task_compact'],
  },
  {
    title: 'agentPrompts.groups.chat.title',
    note: 'agentPrompts.groups.chat.note',
    keys: ['chat_mode', 'chat_context'],
  },
]

const overrides = computed(
  () => (session.project?.config.teams.prompts ?? {}) as Partial<AgentPromptsConfig>,
)

const PRODUCER_ORDER = [
  'producer_mode', 'producer_careful', 'producer_independent', 'producer_task_cost',
  'producer_review', 'producer_start', 'producer_wake', 'producer_state',
  'producer_summary', 'producer_compact',
]

const grouped = computed(() => {
  const known = specs.value
  if (!known) return []
  const groups = GROUPS.map((group) => ({
    title: t(group.title),
    note: t(group.note),
    keys: group.keys.filter((key) => key in known),
  }))
  groups.push({
    title: t('agentPrompts.groups.producer.title'),
    note: t('agentPrompts.groups.producer.note'),
    keys: [...PRODUCER_ORDER.filter(key => key in known),
      ...Object.keys(known).filter(key => key.startsWith('producer_') && !PRODUCER_ORDER.includes(key))],
  })
  const placed = new Set(groups.flatMap(group => group.keys))
  const extra = Object.keys(known).filter((key) => !placed.has(key))
  if (extra.length) groups.push({ title: t('agentPrompts.groups.extra'), note: '', keys: extra })
  return groups.filter((group) => group.keys.length)
})

const changedCount = computed(
  () => Object.keys(specs.value ?? {}).filter((key) => statusOf(key) !== 'default').length,
)

function copyOf(key: string): { label: string; hint: string } {
  const label = `agentPrompts.fragments.${key}.label`
  const hint = `agentPrompts.fragments.${key}.hint`
  return {
    label: hasMessage(label) ? t(label) : key,
    hint: hasMessage(hint) ? t(hint) : '',
  }
}

function overrideOf(key: string): string | null {
  return (overrides.value as Record<string, string | null | undefined>)[key] ?? null
}

function effectiveOf(key: string): string {
  return overrideOf(key) ?? specs.value?.[key]?.default ?? ''
}

function statusOf(key: string): 'default' | 'edited' | 'removed' {
  const override = overrideOf(key)
  if (override === null) return 'default'
  return override.trim() === '' ? 'removed' : 'edited'
}

function isDirty(key: string): boolean {
  return key in seeded && drafts[key] !== seeded[key]
}

function rowsFor(key: string): number {
  const lines = (drafts[key] ?? '').split('\n').length
  return Math.min(12, Math.max(3, lines + 1))
}

function seed(key: string) {
  const value = effectiveOf(key)
  drafts[key] = value
  seeded[key] = value
}

api.promptSpecs().then(
  (result) => {
    specs.value = result
    for (const key of Object.keys(result)) seed(key)
  },
  (cause) => {
    loadError.value = cause instanceof Error ? cause.message : String(cause)
  },
)

// A change that arrived from outside — another tab, a config reload — refreshes every
// row the user is not mid-edit in; a dirty draft is theirs until they save or reset it.
watch(overrides, () => {
  for (const key of Object.keys(specs.value ?? {})) {
    if (!isDirty(key)) seed(key)
  }
})

async function push(key: string, value: string | null, submitted: string) {
  saving.value = key
  emit('saving', true)
  failedKeys.delete(key)
  saveError.value = null
  try {
    const updated = await (props.saveSettings ?? api.updateSettings)({
      teams: { prompts: { [key]: value } as Partial<AgentPromptsConfig> },
    })
    if (session.project) session.project = { ...session.project, config: updated }
    // A response for an earlier draft must not erase text typed while it was saving.
    seeded[key] = effectiveOf(key)
    if (drafts[key] === submitted) drafts[key] = seeded[key]
  } catch (cause) {
    failedKeys.add(key)
    saveError.value = cause instanceof Error ? cause.message : String(cause)
  } finally {
    saving.value = null
    emit('saving', false)
  }
}

// §10.2: Team edits save after a pause in typing. Keep only the newest queued value per
// prompt and send requests serially so config responses cannot arrive out of order.
const pending = new Map<string, { value: string | null; draft: string }>()
let saveTimer: ReturnType<typeof setTimeout> | null = null
let flushing: Promise<void> | null = null
let leaving = false

function valueToSave(key: string): string | null {
  const spec = specs.value![key]!
  const draft = drafts[key] ?? ''
  if (draft === spec.default) return null
  if (draft.trim() === '') return spec.required ? null : ''
  return draft
}

function scheduleSave(key: string): void {
  if (!props.autosave || !specs.value?.[key]) return
  failedKeys.delete(key)
  // While this key is in flight, even a return to its old value needs a follow-up save.
  if (isDirty(key) || saving.value === key) {
    pending.set(key, { value: valueToSave(key), draft: drafts[key] ?? '' })
  } else pending.delete(key)
  if (saveTimer) clearTimeout(saveTimer)
  saveTimer = setTimeout(() => void flush(), 600)
}

function flush(): Promise<void> {
  if (saveTimer) clearTimeout(saveTimer)
  saveTimer = null
  if (flushing) return flushing
  if (props.disabled && !leaving) return Promise.resolve()
  flushing = (async () => {
    while (pending.size && (!props.disabled || leaving)) {
      const [key, job] = pending.entries().next().value!
      pending.delete(key)
      await push(key, job.value, job.draft)
    }
  })().finally(() => { flushing = null })
  return flushing
}

watch(() => props.disabled, (disabled) => {
  if (!disabled && pending.size) void flush()
})

onBeforeUnmount(() => {
  leaving = true
  void flush()
})

/** Retyping the default stores nothing; blank stores a removal (or resets a required one). */
async function save(key: string) {
  if (!specs.value?.[key] || props.disabled) return
  pending.set(key, { value: valueToSave(key), draft: drafts[key] ?? '' })
  await flush()
}

async function reset(key: string) {
  if (props.disabled) return
  drafts[key] = specs.value?.[key]?.default ?? ''
  pending.set(key, { value: null, draft: drafts[key]! })
  await flush()
}
</script>

<template>
  <section>
    <h2 class="mb-2 font-medium">{{ t('agentPrompts.title') }}</h2>
    <div :class="compact ? 'space-y-3' : 'vxs-card space-y-3 rounded-lg border border-card bg-surface p-4 shadow-card'">
      <p class="text-small text-faint">
        {{ t('agentPrompts.intro') }}
        <template v-if="specs">
          <span v-if="changedCount" class="text-muted">
            {{ t('agentPrompts.changed', { count: changedCount }) }}
          </span>
          <span v-else>{{ t('agentPrompts.allBuiltIn') }}</span>
        </template>
      </p>

      <p v-if="loadError" class="rounded-md bg-inset p-2 text-muted">
        {{ t('agentPrompts.loadError', { error: loadError }) }}
      </p>
      <p v-if="saveError" class="rounded-md bg-warning-soft p-2 text-small text-warning">
        {{ saveError }}
      </p>

      <div v-for="group in grouped" :key="group.title">
        <h3 class="mb-1 text-muted">{{ group.title }}</h3>
        <p v-if="group.note" class="mb-2 text-small text-faint">{{ group.note }}</p>

        <div class="space-y-1.5">
          <details
            v-for="key in group.keys"
            :key="key"
            class="rounded-md border border-line bg-inset/40"
          >
            <summary class="flex cursor-pointer flex-wrap items-center gap-2 px-2 py-1.5">
              <span class="min-w-0 break-words">{{ copyOf(key).label }}</span>
              <span
                v-if="statusOf(key) === 'edited'"
                class="shrink-0 rounded-sm bg-accent-soft px-1 text-[10px] text-accent"
                >{{ t('agentPrompts.edited') }}</span
              >
              <span
                v-else-if="statusOf(key) === 'removed'"
                class="shrink-0 rounded-sm bg-warning-soft px-1 text-[10px] text-warning"
                >{{ t('agentPrompts.removed') }}</span
              >
              <span class="min-w-0 flex-1 truncate text-small text-faint">
                {{
                  statusOf(key) === 'removed'
                    ? t('agentPrompts.notSent')
                    : effectiveOf(key).split('\n')[0]
                }}
              </span>
            </summary>

            <div class="space-y-2 border-t border-line p-2">
              <p class="text-small text-faint">{{ copyOf(key).hint }}</p>
              <p v-if="specs?.[key]?.placeholders.length" class="text-small text-faint">
                {{ t('agentPrompts.placeholders') }}
                <code
                  v-for="name in specs[key]!.placeholders"
                  :key="name"
                  class="mr-1 rounded-sm bg-inset px-1 font-mono"
                  >{{ '{' + name + '}' }}</code
                >
              </p>
              <textarea
                v-model="drafts[key]"
                @input="scheduleSave(key)"
                :disabled="disabled"
                :aria-label="copyOf(key).label"
                :rows="rowsFor(key)"
                spellcheck="false"
                class="w-full resize-y rounded-md border border-line bg-inset p-2 font-mono text-[12px] leading-relaxed outline-none focus:border-accent"
              />
              <div class="flex flex-wrap items-center gap-2">
                <button
                  v-if="!autosave || failedKeys.has(key)"
                  class="rounded-md border border-line px-3 py-1 hover:bg-hover disabled:opacity-50"
                  :disabled="disabled || saving !== null || !isDirty(key)"
                  @click="save(key)"
                >
                  {{ saving === key ? t('common.saving') : t(autosave ? 'common.retry' : 'common.save') }}
                </button>
                <span v-else-if="saving === key" class="text-small text-faint" role="status">
                  {{ t('common.saving') }}
                </span>
                <button
                  v-if="statusOf(key) !== 'default' || isDirty(key)"
                  class="rounded-md border border-line px-3 py-1 hover:bg-hover disabled:opacity-50"
                  :disabled="disabled || saving !== null"
                  @click="reset(key)"
                >
                  {{ t('agentPrompts.resetToDefault') }}
                </button>
                <span v-if="specs?.[key]?.required" class="text-small text-faint">
                  {{ t('agentPrompts.cannotRemove') }}
                </span>
                <span v-else class="text-small text-faint">
                  {{ t('agentPrompts.saveBlankToRemove') }}
                </span>
              </div>
            </div>
          </details>
        </div>
      </div>
    </div>
  </section>
</template>
