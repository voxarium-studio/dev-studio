<script setup lang="ts">
/** Settings and maintenance belonging to the managed project (spec §5.1, §7). */
import { FolderOpen } from 'lucide-vue-next'
import { computed, ref, watch } from 'vue'

import { api } from '@/api/client'
import CommitDescriptionSettings from '@/components/settings/CommitDescriptionSettings.vue'
import SettingsPage from '@/components/settings/SettingsPage.vue'
import SyncSettings from '@/components/settings/SyncSettings.vue'
import RemoteAiAccessSettings from '@/components/settings/RemoteAiAccessSettings.vue'
import { t, type MessageKey } from '@/i18n'
import I18nT from '@/i18n/I18nT.vue'
import { ACCENTS } from '@/lib/appearance'
import { useSessionStore } from '@/stores/session'
import { useThemeStore } from '@/stores/theme'

const session = useSessionStore()
const theme = useThemeStore()
const config = computed(() => session.project?.config ?? null)
const saveError = ref<string | null>(null)
const savingOther = ref(false)
const SECTIONS: readonly { id: string; titleKey: MessageKey }[] = [
  { id: 'settings-project', titleKey: 'settings.project.title' },
  { id: 'settings-agents', titleKey: 'settings.agents.title' },
  { id: 'settings-tasks', titleKey: 'settings.tasks.title' },
  { id: 'settings-sync', titleKey: 'sync.settings.title' },
  { id: 'project-remote-ai', titleKey: 'aiAccess.projectTitle' },
  { id: 'settings-project-execution', titleKey: 'projectSettings.maintenance' },
]

function openProjectMenu() { window.dispatchEvent(new Event('studio:open-project')) }

async function setAccent(accent: string) {
  saveError.value = null
  try {
    await theme.updateAccent(accent)
  } catch (cause) {
    saveError.value = cause instanceof Error ? cause.message : String(cause)
  }
}

/** Which system the project is under, and whether agents get isolated checkouts (§10.5). */
const vcsSummary = computed(() => {
  const vcs = session.project?.vcs
  if (!vcs) return t('settings.project.vcsNone')
  const isolation = vcs.supports_isolation
    ? t('settings.project.vcsIsolated')
    : t('settings.project.vcsShared')
  return t('settings.project.vcsLine', {
    name: vcs.name,
    detail: vcs.available ? isolation : (vcs.reason ?? ''),
  })
})

/**
 * Where agent sessions start (§10.5). Edited as text with an explicit Save rather than
 * saved on every keystroke, because a half-typed path names a directory that does not
 * exist and the backend refuses those.
 */
const savedLaunchRoot = computed(() => config.value?.teams.launch_root ?? '.')
const launchRoot = ref(savedLaunchRoot.value)
watch(savedLaunchRoot, (value) => {
  launchRoot.value = value
})
const launchRootDirty = computed(() => (launchRoot.value.trim() || '.') !== savedLaunchRoot.value)

/**
 * What the path will mean, resolved against the project root. An approximation of the
 * backend's own `resolve()` — no symlinks, no case folding — but it answers the question
 * the setting actually raises, which is where `../` lands.
 */
const resolvedLaunchRoot = computed(() => {
  const root = session.project?.root ?? ''
  const value = launchRoot.value.trim() || '.'
  if (!root) return ''
  if (/^([a-zA-Z]:[\\/]|[\\/])/.test(value)) return value
  const sep = root.includes('\\') ? '\\' : '/'
  const parts = root.split(/[\\/]/)
  for (const segment of value.split(/[\\/]/)) {
    if (!segment || segment === '.') continue
    if (segment === '..') {
      if (parts.length > 1) parts.pop()
    } else {
      parts.push(segment)
    }
  }
  // A lone `D:` is a drive, not a directory — the trailing separator is what makes it one.
  if (parts.length === 1) return parts[0] + sep
  return parts.join(sep)
})

/** True once the path leaves the project, which is what turns isolated checkouts off. */
const launchRootOutside = computed(() => {
  const root = session.project?.root
  if (!root) return false
  const resolved = resolvedLaunchRoot.value
  const normalise = (value: string) => value.replace(/[\\/]+$/, '').toLowerCase()
  const base = normalise(root)
  const target = normalise(resolved)
  return target !== base && !target.startsWith(base + (root.includes('\\') ? '\\' : '/'))
})

async function saveLaunchRoot() {
  savingOther.value = true
  saveError.value = null
  try {
    const updated = await api.updateSettings({
      teams: { launch_root: launchRoot.value.trim() || '.' },
    })
    if (session.project) session.project = { ...session.project, config: updated }
  } catch (cause) {
    saveError.value = cause instanceof Error ? cause.message : String(cause)
  } finally {
    savingOther.value = false
  }
}

/**
 * How many agent sessions — task runs and chats — hold a slot at once (`concurrency.agents`,
 * §8.2). Saved explicitly like the launch root, and applied by the backend without a
 * restart: raising it starts whatever is queued behind the old limit on the spot.
 */
const MAX_PARALLEL_AGENTS = 16
const savedParallelAgents = computed(() => config.value?.concurrency.agents ?? 8)
const parallelAgents = ref(savedParallelAgents.value)
watch(savedParallelAgents, (value) => {
  parallelAgents.value = value
})
const parallelAgentsValid = computed(
  () =>
    Number.isInteger(parallelAgents.value) &&
    parallelAgents.value >= 1 &&
    parallelAgents.value <= MAX_PARALLEL_AGENTS,
)
const parallelAgentsDirty = computed(() => parallelAgents.value !== savedParallelAgents.value)

async function saveParallelAgents() {
  if (!parallelAgentsValid.value || !parallelAgentsDirty.value) return
  savingOther.value = true
  saveError.value = null
  try {
    const updated = await api.updateSettings({ concurrency: { agents: parallelAgents.value } })
    if (session.project) {
      session.project = {
        ...session.project,
        config: updated,
        // The Execution section lists the engine's live limits, which this save just moved.
        concurrency_limits: { ...session.project.concurrency_limits, agent: updated.concurrency.agents },
      }
    }
  } catch (cause) {
    saveError.value = cause instanceof Error ? cause.message : String(cause)
  } finally {
    savingOther.value = false
  }
}

/**
 * The code every task id is displayed with — `VS` in `VS-01` (§10.2).
 *
 * Edited with an explicit Save for the reason the launch root is: half a code is a valid
 * string the backend would happily store, and every task on the board would change its
 * name on each keystroke. Cleared, it falls back to a code derived from the project's
 * name, which is why the field can be emptied at all.
 */
const savedProjectCode = computed(() => config.value?.project_code ?? '')
const projectCode = ref(savedProjectCode.value)
watch(savedProjectCode, (value) => {
  projectCode.value = value
})
const projectCodeDirty = computed(
  () => projectCode.value.trim().toUpperCase() !== savedProjectCode.value,
)

/** What the first task will read as, so the effect of the field is visible before saving. */
const sampleTaskId = computed(() => {
  const typed = projectCode.value.trim().toUpperCase()
  if (typed) return `${typed}-01`
  // Mirrors the backend's `derive_project_code`: initials for a multi-word name, the
  // first three letters otherwise. Only a preview — the backend is what decides.
  const words = (session.project?.name ?? '').match(/[A-Za-z0-9]+/g) ?? []
  if (!words.length) return 'TSK-01'
  const derived = (words.length > 1 ? words.map((w) => w[0]).slice(0, 4).join('') : words[0]!.slice(0, 3))
    .toUpperCase()
  return `${/[A-Z]/.test(derived) ? derived : 'TSK'}-01`
})

async function saveProjectCode() {
  savingOther.value = true
  saveError.value = null
  try {
    const updated = await api.updateSettings({ project_code: projectCode.value.trim() })
    if (session.project) session.project = { ...session.project, config: updated }
  } catch (cause) {
    saveError.value = cause instanceof Error ? cause.message : String(cause)
  } finally {
    savingOther.value = false
  }
}

async function clearAssetCache() {
  saveError.value = null
  if (!window.confirm(t('settings.execution.assetCacheConfirm'))) return
  savingOther.value = true
  try {
    const result = await api.clearAssetCache()
    saveError.value = t('settings.execution.assetCacheCleared', {
      count: result.removed, size: (result.bytes / 1048576).toFixed(1), failed: result.failed,
    })
  } catch (error) {
    saveError.value = String(error)
  } finally {
    savingOther.value = false
  }
}

async function clearCache() {
  saveError.value = null
  savingOther.value = true
  try {
    const result = await api.clearNodeCache()
    saveError.value = t('settings.execution.cacheCleared', { count: result.removed })
  } catch (cause) {
    saveError.value = cause instanceof Error ? cause.message : String(cause)
  } finally {
    savingOther.value = false
  }
}
</script>

<template>
  <SettingsPage
    title-key="projectSettings.title"
    :subtitle="`${session.project?.data_dir ?? ''}/config.yaml`"
    :sections="SECTIONS"
    :available="!!config"
    :message="saveError"
  >
    <template v-if="config">
          <section id="settings-project" class="vxs-enter scroll-mt-4">
            <h2 class="mb-2 font-medium">{{ t('settings.project.title') }}</h2>
            <div class="vxs-card space-y-3 rounded-lg border border-card bg-surface p-4 shadow-card">
              <div class="flex items-start gap-3">
                <span class="w-28 shrink-0 pt-1 text-muted">{{ t('projectSettings.accent') }}</span>
                <div class="flex items-center gap-2">
                  <button
                    v-for="accent in ACCENTS"
                    :key="accent"
                    type="button"
                    class="size-6 rounded-full border-2"
                    :style="{
                      background: accent,
                      borderColor: theme.theme.accent === accent ? 'var(--vxs-text-primary)' : 'transparent',
                    }"
                    :title="accent"
                    :aria-label="accent"
                    :aria-pressed="theme.theme.accent === accent"
                    :disabled="theme.saving"
                    @click="setAccent(accent)"
                  />
                  <input
                    type="color"
                    :value="theme.theme.accent"
                    :disabled="theme.saving"
                    class="size-6 cursor-pointer rounded-full border-0 bg-transparent p-0"
                    :title="t('settings.appearance.customAccent')"
                    @change="setAccent(($event.target as HTMLInputElement).value)"
                  />
                </div>
              </div>

              <dl class="grid grid-cols-[8rem_1fr] gap-1">
                <dt class="text-muted">{{ t('settings.project.root') }}</dt>
                <dd class="font-mono break-all">{{ session.project?.root }}</dd>
                <dt class="text-muted">{{ t('settings.project.versionControl') }}</dt>
                <dd>{{ vcsSummary }}</dd>
                <dt class="text-muted">{{ t('settings.project.authentication') }}</dt>
                <dd>
                  {{
                    session.project?.auth_required
                      ? t('settings.project.tokenRequired')
                      : t('settings.project.open')
                  }}
                </dd>
              </dl>

              <button
                class="flex items-center gap-2 rounded-md border border-line px-3 py-1.5 hover:bg-hover"
                @click="openProjectMenu"
              >
                <FolderOpen class="size-4" />
                {{ t('settings.project.openAnother') }}
              </button>
              <p class="text-small text-faint">{{ t('settings.project.openAnotherHint') }}</p>
            </div>
          </section>

          <section id="settings-agents" class="vxs-enter scroll-mt-4">
            <h2 class="mb-2 font-medium">{{ t('settings.agents.title') }}</h2>
            <div class="vxs-card space-y-3 rounded-lg border border-card bg-surface p-4 shadow-card">
              <div>
                <label class="block">
                  <span class="mb-1 block text-muted">{{ t('settings.agents.parallel') }}</span>
                  <div class="flex gap-2">
                    <input
                      v-model.number="parallelAgents"
                      type="number"
                      min="1"
                      :max="MAX_PARALLEL_AGENTS"
                      step="1"
                      class="w-20 min-w-0 rounded-md border bg-inset px-2 py-1 font-mono outline-none focus:border-accent"
                      :class="parallelAgentsValid ? 'border-line' : 'border-danger'"
                      @keyup.enter="saveParallelAgents"
                    />
                    <button
                      class="shrink-0 rounded-md border border-line px-3 py-1 hover:bg-hover disabled:opacity-50"
                      :disabled="savingOther || !parallelAgentsDirty || !parallelAgentsValid"
                      @click="saveParallelAgents"
                    >
                      {{ t('common.save') }}
                    </button>
                  </div>
                </label>
                <p class="mt-1 text-small text-faint">{{ t('settings.agents.parallelHint') }}</p>
              </div>

              <div>
                <label class="block">
                  <span class="mb-1 block text-muted">{{ t('settings.agents.launchRoot') }}</span>
                  <div class="flex gap-2">
                    <input
                      v-model="launchRoot"
                      class="min-w-0 flex-1 rounded-md border border-line bg-inset px-2 py-1 font-mono outline-none focus:border-accent"
                      placeholder="."
                      spellcheck="false"
                      @keyup.enter="saveLaunchRoot"
                    />
                    <button
                      class="shrink-0 rounded-md border border-line px-3 py-1 hover:bg-hover disabled:opacity-50"
                      :disabled="savingOther || !launchRootDirty"
                      @click="saveLaunchRoot"
                    >
                      {{ t('common.save') }}
                    </button>
                  </div>
                </label>
                <p class="mt-1 text-small text-faint">
                  <I18nT keypath="settings.agents.launchRootHint">
                    <template #here><code class="font-mono">.</code></template>
                    <template #up><code class="font-mono">../</code></template>
                  </I18nT>
                </p>
                <dl class="mt-2 grid grid-cols-[8rem_1fr] gap-1 text-small">
                  <dt class="text-muted">{{ t('settings.agents.startIn') }}</dt>
                  <dd class="font-mono break-all" :class="launchRootDirty ? 'text-warning' : ''">
                    {{
                      launchRootDirty
                        ? t('settings.unsavedValue', { value: resolvedLaunchRoot })
                        : resolvedLaunchRoot
                    }}
                  </dd>
                </dl>
              </div>

              <p v-if="launchRootOutside" class="rounded-md bg-warning-soft p-2 text-small text-warning">
                {{ t('settings.agents.outside') }}
              </p>
              <CommitDescriptionSettings />
            </div>
          </section>

          <section id="settings-tasks" class="vxs-enter scroll-mt-4">
            <h2 class="mb-2 font-medium">{{ t('settings.tasks.title') }}</h2>
            <div class="vxs-card space-y-3 rounded-lg border border-card bg-surface p-4 shadow-card">
              <div>
                <label class="block">
                  <span class="mb-1 block text-muted">{{ t('settings.tasks.projectCode') }}</span>
                  <div class="flex gap-2">
                    <input
                      v-model="projectCode"
                      maxlength="6"
                      class="w-28 min-w-0 rounded-md border border-line bg-inset px-2 py-1 font-mono uppercase outline-none focus:border-accent"
                      :placeholder="sampleTaskId.split('-')[0]"
                      spellcheck="false"
                      @keyup.enter="saveProjectCode"
                    />
                    <button
                      class="shrink-0 rounded-md border border-line px-3 py-1 hover:bg-hover disabled:opacity-50"
                      :disabled="savingOther || !projectCodeDirty"
                      @click="saveProjectCode"
                    >
                      {{ t('common.save') }}
                    </button>
                  </div>
                </label>
                <p class="mt-1 text-small text-faint">{{ t('settings.tasks.projectCodeHint') }}</p>
                <dl class="mt-2 grid grid-cols-[8rem_1fr] gap-1 text-small">
                  <dt class="text-muted">{{ t('settings.tasks.readAs') }}</dt>
                  <dd class="font-mono" :class="projectCodeDirty ? 'text-warning' : ''">
                    {{
                      projectCodeDirty ? t('settings.unsavedValue', { value: sampleTaskId }) : sampleTaskId
                    }}
                  </dd>
                </dl>
                <p class="mt-1 text-small text-faint">{{ t('settings.tasks.renamesNothing') }}</p>
              </div>
            </div>
          </section>

          <section id="settings-sync" class="vxs-enter scroll-mt-4">
            <h2 class="mb-2 font-medium">{{ t('sync.settings.title') }}</h2>
            <SyncSettings />
          </section>

          <section id="project-remote-ai" class="vxs-enter scroll-mt-4 space-y-3">
            <h2 class="font-medium">{{ t('aiAccess.projectTitle') }}</h2>
            <RemoteAiAccessSettings project />
          </section>

          <section id="settings-project-execution" class="vxs-enter scroll-mt-4">
            <h2 class="mb-2 font-medium">{{ t('projectSettings.maintenance') }}</h2>
            <div class="vxs-card space-y-3 rounded-lg border border-card bg-surface p-4 shadow-card">
              <button
                class="rounded-md border border-line px-3 py-1.5 hover:bg-hover disabled:opacity-50"
                :disabled="savingOther"
                @click="clearCache"
              >
                {{ t('settings.execution.clearCache') }}
              </button>

              <button
                class="rounded-md border border-line px-3 py-1.5 hover:bg-hover disabled:opacity-50"
                :disabled="savingOther"
                @click="clearAssetCache"
              >
                {{ t('settings.execution.clearAssetCache') }}
              </button>
              <p class="text-small text-faint">{{ t('settings.execution.assetCacheHint') }}</p>

              <dl class="grid grid-cols-2 gap-x-4 gap-y-1 text-small text-faint">
                <template v-for="(limit, name) in session.project?.concurrency_limits ?? {}" :key="name">
                  <dt>{{ t('settings.execution.concurrentJobs', { name }) }}</dt>
                  <dd>{{ limit }}</dd>
                </template>
              </dl>
              <p class="text-small text-faint">
                <I18nT keypath="settings.execution.concurrencyHint">
                  <template #file><code class="font-mono">config.yaml</code></template>
                </I18nT>
              </p>
            </div>
          </section>
    </template>
  </SettingsPage>
</template>
