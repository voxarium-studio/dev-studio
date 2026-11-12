<script setup lang="ts">
/** Application preferences, stored in the user config (spec §5.1, §7). */
import {
  AlertTriangle,
  ArrowUpCircle,
  CheckCircle2,
  Loader2,
  Plus,
  RefreshCw,
  X,
} from 'lucide-vue-next'
import { computed, ref, watch } from 'vue'

import { api } from '@/api/client'
import type { ThemeConfig } from '@/api/types'
import StartupSettings from '@/components/settings/StartupSettings.vue'
import SettingsPage from '@/components/settings/SettingsPage.vue'
import SegmentedControl from '@/components/ui/SegmentedControl.vue'
import { LOCALES, locale, setLocale, t, type Locale, type MessageKey } from '@/i18n'
import I18nT from '@/i18n/I18nT.vue'
import {
  CANVAS_GRIDS,
  FONTS,
  MODES,
  MONO_FONTS,
  NODE_TINT,
  SIDEBARS,
  STATUS_PALETTES,
  TEXT_SIZE,
  exportApplicationTheme,
  parseImportedApplicationTheme,
} from '@/lib/appearance'
import { formatTime, statusColor, statusLabel } from '@/lib/status'
import { useSessionStore } from '@/stores/session'
import { useThemeStore } from '@/stores/theme'
import { useUpdatesStore } from '@/stores/updates'

const session = useSessionStore()
const theme = useThemeStore()
const updates = useUpdatesStore()

const saveError = ref<string | null>(null)
const savingOther = ref(false)

const config = computed(() => session.project?.config ?? null)

const SECTIONS: readonly { id: string; titleKey: MessageKey }[] = [
  { id: 'settings-startup', titleKey: 'settings.startup.title' },
  { id: 'settings-language', titleKey: 'settings.language.title' },
  { id: 'settings-appearance', titleKey: 'settings.appearance.title' },
  { id: 'settings-explorer', titleKey: 'settings.explorer.title' },
  { id: 'settings-execution', titleKey: 'settings.execution.title' },
  { id: 'settings-updates', titleKey: 'settings.updates.title' },
]

const languageError = ref<string | null>(null)

/** Persist the interface language for every project on this machine (§6.4). */
async function chooseLocale(next: Locale) {
  languageError.value = null
  try {
    const updated = await api.updateApplicationSettings({ language: next })
    if (session.project) session.project = { ...session.project, config: updated }
    await setLocale(next)
  } catch (cause) {
    languageError.value = cause instanceof Error ? cause.message : String(cause)
  }
}

/** Free-form response preference is independent from the interface locale (§6.4). */
const savedRespondLanguage = computed(() => config.value?.respond_language ?? 'English')
const respondLanguage = ref(savedRespondLanguage.value)
const respondLanguageError = ref<string | null>(null)
const savingRespondLanguage = ref(false)
const respondLanguageDirty = computed(() => respondLanguage.value.trim() !== savedRespondLanguage.value)
watch(savedRespondLanguage, (value) => {
  respondLanguage.value = value
})

async function saveRespondLanguage() {
  const value = respondLanguage.value.trim()
  if (!value || !respondLanguageDirty.value || savingRespondLanguage.value) return
  savingRespondLanguage.value = true
  respondLanguageError.value = null
  try {
    const updated = await api.updateApplicationSettings({ respond_language: value })
    if (session.project) session.project = { ...session.project, config: updated }
    respondLanguage.value = updated.respond_language ?? value
  } catch (cause) {
    respondLanguageError.value = cause instanceof Error ? cause.message : String(cause)
  } finally {
    savingRespondLanguage.value = false
  }
}

async function setTheme(patch: Parameters<typeof theme.update>[0]) {
  saveError.value = null
  try {
    await theme.update(patch)
  } catch (cause) {
    saveError.value = cause instanceof Error ? cause.message : String(cause)
  }
}

/**
 * What `tokens.css` sets `--vxs-text-size` to when the user has not picked one, so the
 * slider can show the inherited value. Restated here rather than read off the DOM because
 * the slider has to know it even while the page is showing somebody else's override.
 */
const BASE_TEXT_SIZE = 14
const effectiveTextSize = computed(() => theme.theme.text_size ?? BASE_TEXT_SIZE)

/** The board's seven columns, as the row of dots beside the status-palette knob. */
const STATUS_PREVIEW = ['todo', 'pending', 'in_progress', 'needs_review', 'done', 'blocked', 'cancelled']

/**
 * Custom tokens are edited as rows and written back as one map. The draft is re-seeded from
 * the stored map after every save, so a row that did not make it (no `--` name, no value)
 * disappears rather than lingering as something that looks saved and is not.
 */
const tokenDraft = ref<{ name: string; value: string }[]>([])
watch(
  () => theme.theme.custom_tokens,
  (tokens) => {
    tokenDraft.value = Object.entries(tokens).map(([name, value]) => ({ name, value }))
  },
  { immediate: true, deep: true },
)

function addToken() {
  tokenDraft.value.push({ name: '--vxs-', value: '' })
}

function removeToken(index: number) {
  tokenDraft.value.splice(index, 1)
  void commitTokens()
}

async function commitTokens() {
  const tokens: Record<string, string> = {}
  for (const row of tokenDraft.value) {
    const name = row.name.trim()
    const value = row.value.trim()
    if (/^--[a-zA-Z0-9_-]+$/.test(name) && value && !/[;{}]/.test(value)) tokens[name] = value
  }
  if (JSON.stringify(tokens) === JSON.stringify(theme.theme.custom_tokens)) return
  await setTheme({ custom_tokens: tokens })
}

/**
 * Studio updates (§12.1). The rail carries the button; this is where the setting behind
 * it lives, and where the answer is when there is nothing to offer — a rail that shows
 * nothing cannot tell "up to date" from "never checked".
 */
const UPDATE_INTERVALS = computed(() => [
  { value: 15, label: t('settings.updates.every15Minutes') },
  { value: 60, label: t('settings.updates.hourly') },
  { value: 180, label: t('settings.updates.every3Hours') },
  { value: 720, label: t('settings.updates.twiceADay') },
  { value: 1440, label: t('settings.updates.daily') },
])

const updatesSummary = computed(() => {
  const info = updates.status
  if (!info) return t('settings.updates.reading')
  if (!info.supported) return info.reason ?? t('settings.updates.cannotUpdate')
  if (info.available) return t('settings.updates.available', { version: info.latest ?? '' })
  if (updates.desktop) return t(info.error ? 'updates.dialog.stopped' : !info.checked_at ? 'updates.desktop.unchecked' : 'updates.desktop.upToDate')
  if (info.current) {
    return t('settings.updates.onLatest', {
      current: info.current,
      upstream: info.upstream ?? t('settings.updates.thisBranch'),
    })
  }
  return t('settings.updates.noRelease')
})

/** What follows the build's name on its line: when it was last checked, and a dirty tree. */
const buildNotes = computed(() => {
  const notes: string[] = []
  const info = updates.status
  if (info?.checked_at) notes.push(t('settings.updates.checked', { time: formatTime(info.checked_at) }))
  if (info?.dirty) notes.push(t('settings.updates.dirty'))
  return notes
})

async function setUpdates(patch: { enabled?: boolean; check_interval_minutes?: number }) {
  savingOther.value = true
  saveError.value = null
  try {
    const updated = await api.updateApplicationSettings({ updates: patch })
    if (session.project) session.project = { ...session.project, config: updated }
    // The poller was restarted on the backend; re-read so "checked" and the interval on
    // screen are the ones now in force.
    await updates.load()
  } catch (cause) {
    saveError.value = cause instanceof Error ? cause.message : String(cause)
  } finally {
    savingOther.value = false
  }
}

const fileInput = ref<HTMLInputElement | null>(null)

/** Export application appearance without the current project’s accent (§7). */
function downloadTheme() {
  const blob = new Blob([exportApplicationTheme(theme.theme)], { type: 'application/json' })
  const url = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = 'voxarium-studio-theme.json'
  anchor.click()
  URL.revokeObjectURL(url)
}

/** Read a theme file back; whatever it holds is checked field by field before it is applied. */
async function importTheme(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  saveError.value = null
  try {
    await theme.update(parseImportedApplicationTheme(await file.text()))
  } catch (cause) {
    saveError.value = cause instanceof Error ? cause.message : String(cause)
  }
}

async function setShowDataDir(value: boolean) {
  savingOther.value = true
  saveError.value = null
  try {
    const updated = await api.updateApplicationSettings({ show_data_dir: value })
    if (session.project) session.project = { ...session.project, config: updated }
  } catch (cause) {
    saveError.value = cause instanceof Error ? cause.message : String(cause)
  } finally {
    savingOther.value = false
  }
}

async function setNodeCache(value: boolean) {
  savingOther.value = true
  saveError.value = null
  try {
    const updated = await api.updateApplicationSettings({ node_cache_enabled: value })
    if (session.project) session.project = { ...session.project, config: updated }
  } catch (cause) {
    saveError.value = cause instanceof Error ? cause.message : String(cause)
  } finally {
    savingOther.value = false
  }
}

</script>

<template>
  <SettingsPage
    title-key="settings.title"
    :subtitle="t('settings.scopeHint')"
    :sections="SECTIONS"
    :available="!!config"
    :message="saveError"
  >
    <template v-if="config">
          <section id="settings-startup" class="vxs-enter scroll-mt-4"><StartupSettings /></section>

          <section id="settings-language" class="vxs-enter scroll-mt-4">
            <h2 class="mb-2 font-medium">{{ t('settings.language.title') }}</h2>

            <div class="vxs-card space-y-4 rounded-lg border border-card bg-surface p-4 shadow-card">
              <div class="flex items-start gap-3">
                <span class="w-28 shrink-0 pt-1 text-muted">{{ t('settings.language.label') }}</span>
                <div class="min-w-0 flex-1 space-y-1.5">
                  <SegmentedControl
                    :model-value="locale"
                    :options="LOCALES"
                    @update:model-value="chooseLocale"
                  />
                  <p class="text-small text-faint">
                    <I18nT keypath="settings.language.hint">
                      <template #file><code class="font-mono">config.yaml</code></template>
                    </I18nT>
                  </p>
                  <p v-if="languageError" class="text-small text-warning">{{ languageError }}</p>
                </div>
              </div>
              <form class="flex items-start gap-3" @submit.prevent="saveRespondLanguage">
                <label for="respond-language" class="w-28 shrink-0 pt-1 text-muted">
                  {{ t('settings.language.respondLabel') }}
                </label>
                <div class="min-w-0 flex-1 space-y-1.5">
                  <div class="flex flex-wrap items-center gap-2">
                    <input
                      id="respond-language"
                      v-model="respondLanguage"
                      type="text"
                      required
                      :disabled="savingRespondLanguage"
                      aria-describedby="respond-language-hint"
                      class="min-w-0 rounded-md border border-line bg-inset px-2 py-1 outline-none focus:border-accent disabled:opacity-50"
                    />
                    <button
                      type="submit"
                      class="vxs-btn-primary px-3 py-1 disabled:opacity-50"
                      :disabled="savingRespondLanguage || !respondLanguage.trim() || !respondLanguageDirty"
                    >
                      {{ t(savingRespondLanguage ? 'common.saving' : 'common.save') }}
                    </button>
                  </div>
                  <p id="respond-language-hint" class="text-small text-faint">
                    {{ t('settings.language.respondHint') }}
                  </p>
                  <p v-if="respondLanguageError" role="alert" class="text-small text-warning">
                    {{ respondLanguageError }}
                  </p>
                </div>
              </form>
            </div>
          </section>

          <section id="settings-appearance" class="vxs-enter scroll-mt-4">
            <h2 class="mb-2 font-medium">{{ t('settings.appearance.title') }}</h2>

            <div class="vxs-card space-y-4 rounded-lg border border-card bg-surface p-4 shadow-card">
              <div class="flex items-start gap-3">
                <span class="w-28 shrink-0 pt-1 text-muted">{{ t('settings.appearance.mode') }}</span>
                <SegmentedControl
                  :model-value="theme.theme.mode"
                  :options="MODES"
                  @update:model-value="setTheme({ mode: $event })"
                />
              </div>

              <div class="flex items-start gap-3">
                <span class="w-28 shrink-0 pt-1 text-muted">{{ t('settings.appearance.textSize') }}</span>
                <div class="min-w-0 flex-1 space-y-1.5">
                  <div class="flex items-center gap-3">
                    <input
                      type="range"
                      :min="TEXT_SIZE.min"
                      :max="TEXT_SIZE.max"
                      :value="effectiveTextSize"
                      class="w-56 accent-[var(--vxs-accent)]"
                      @change="setTheme({ text_size: Number(($event.target as HTMLInputElement).value) })"
                    />
                    <span class="tabular-nums">
                      {{ t('settings.appearance.pixels', { size: effectiveTextSize }) }}
                    </span>
                  </div>
                  <p class="text-small text-faint">
                    <template v-if="theme.theme.text_size === null">
                      {{ t('settings.appearance.textSizeInherited') }}
                    </template>
                    <template v-else>
                      {{ t('settings.appearance.textSizePicked') }}
                      <button
                        type="button"
                        class="underline hover:text-primary"
                        @click="setTheme({ text_size: null })"
                      >
                        {{ t('settings.appearance.useDefaultSize') }}
                      </button>
                    </template>
                  </p>
                </div>
              </div>

              <div class="flex items-start gap-3">
                <span class="w-28 shrink-0 pt-1 text-muted">{{ t('settings.appearance.fonts') }}</span>
                <div class="flex flex-wrap gap-3">
                  <label class="flex flex-col gap-1">
                    <span class="text-small text-faint">{{ t('settings.appearance.fontInterface') }}</span>
                    <select
                      :value="theme.theme.font"
                      class="rounded-md border border-line bg-inset px-2 py-1 outline-none focus:border-accent"
                      @change="
                        setTheme({ font: ($event.target as HTMLSelectElement).value as ThemeConfig['font'] })
                      "
                    >
                      <option v-for="face in FONTS" :key="face.value" :value="face.value">
                        {{ face.label }}
                      </option>
                    </select>
                  </label>
                  <label class="flex flex-col gap-1">
                    <span class="text-small text-faint">{{ t('settings.appearance.fontCode') }}</span>
                    <select
                      :value="theme.theme.mono_font"
                      class="rounded-md border border-line bg-inset px-2 py-1 outline-none focus:border-accent"
                      @change="
                        setTheme({
                          mono_font: ($event.target as HTMLSelectElement).value as ThemeConfig['mono_font'],
                        })
                      "
                    >
                      <option v-for="face in MONO_FONTS" :key="face.value" :value="face.value">
                        {{ face.label }}
                      </option>
                    </select>
                  </label>
                </div>
              </div>

              <div class="flex items-start gap-3">
                <span class="w-28 shrink-0 pt-1 text-muted">{{ t('settings.appearance.sidebar') }}</span>
                <div class="min-w-0 flex-1 space-y-1.5">
                  <SegmentedControl
                    :model-value="theme.theme.sidebar"
                    :options="SIDEBARS"
                    @update:model-value="setTheme({ sidebar: $event })"
                  />
                  <p class="text-small text-faint">{{ t('settings.appearance.sidebarHint') }}</p>
                </div>
              </div>

              <div class="flex items-start gap-3">
                <span class="w-28 shrink-0 pt-1 text-muted">{{ t('settings.appearance.canvas') }}</span>
                <div class="flex flex-wrap items-end gap-4">
                  <label class="flex flex-col gap-1">
                    <span class="text-small text-faint">{{ t('settings.appearance.grid') }}</span>
                    <SegmentedControl
                      :model-value="theme.theme.canvas_grid"
                      :options="CANVAS_GRIDS"
                      @update:model-value="setTheme({ canvas_grid: $event })"
                    />
                  </label>
                  <label class="flex flex-col gap-1">
                    <span class="text-small text-faint">{{ t('settings.appearance.nodeTint') }}</span>
                    <span class="flex items-center gap-2">
                      <input
                        type="range"
                        :min="NODE_TINT.min"
                        :max="NODE_TINT.max"
                        :value="theme.theme.node_tint"
                        class="w-36 accent-[var(--vxs-accent)]"
                        @change="setTheme({ node_tint: Number(($event.target as HTMLInputElement).value) })"
                      />
                      <span class="tabular-nums">{{ theme.theme.node_tint }}%</span>
                    </span>
                  </label>
                </div>
              </div>

              <div class="flex items-start gap-3">
                <span class="w-28 shrink-0 pt-1 text-muted">{{ t('settings.appearance.statusColours') }}</span>
                <div class="min-w-0 flex-1 space-y-1.5">
                  <div class="flex flex-wrap items-center gap-3">
                    <SegmentedControl
                      :model-value="theme.theme.status_palette"
                      :options="STATUS_PALETTES"
                      @update:model-value="setTheme({ status_palette: $event })"
                    />
                    <span class="flex items-center gap-1.5">
                      <span
                        v-for="status in STATUS_PREVIEW"
                        :key="status"
                        class="block size-2.5 rounded-full"
                        :style="{ background: statusColor(status) }"
                        :title="statusLabel(status)"
                      />
                    </span>
                  </div>
                  <p class="text-small text-faint">{{ t('settings.appearance.statusColoursHint') }}</p>
                </div>
              </div>

              <div class="flex items-start gap-3">
                <span class="w-28 shrink-0 pt-1 text-muted">{{ t('settings.appearance.customTokens') }}</span>
                <div class="min-w-0 flex-1 space-y-2">
                  <div v-for="(row, index) in tokenDraft" :key="index" class="flex gap-1.5">
                    <input
                      v-model="row.name"
                      class="min-w-0 flex-1 rounded-md border border-line bg-inset px-2 py-1 font-mono outline-none focus:border-accent"
                      placeholder="--vxs-bg-surface"
                      spellcheck="false"
                      @blur="commitTokens"
                      @keyup.enter="commitTokens"
                    />
                    <input
                      v-model="row.value"
                      class="min-w-0 flex-1 rounded-md border border-line bg-inset px-2 py-1 font-mono outline-none focus:border-accent"
                      placeholder="#101114"
                      spellcheck="false"
                      @blur="commitTokens"
                      @keyup.enter="commitTokens"
                    />
                    <button
                      type="button"
                      class="grid size-7 shrink-0 place-items-center rounded-md text-faint hover:bg-hover hover:text-primary"
                      :title="t('settings.appearance.removeToken')"
                      @click="removeToken(index)"
                    >
                      <X class="size-3.5" />
                    </button>
                  </div>
                  <div class="flex flex-wrap gap-2">
                    <button
                      type="button"
                      class="flex items-center gap-1 rounded-md border border-dashed border-line-strong px-3 py-1 text-muted hover:bg-hover hover:text-primary"
                      @click="addToken"
                    >
                      <Plus class="size-3.5" /> {{ t('settings.appearance.addToken') }}
                    </button>
                    <button
                      type="button"
                      class="rounded-md border border-line px-3 py-1 hover:bg-hover"
                      @click="downloadTheme"
                    >
                      {{ t('settings.appearance.exportTheme') }}
                    </button>
                    <button
                      type="button"
                      class="rounded-md border border-line px-3 py-1 hover:bg-hover"
                      @click="fileInput?.click()"
                    >
                      {{ t('settings.appearance.importTheme') }}
                    </button>
                    <input
                      ref="fileInput"
                      type="file"
                      accept="application/json,.json"
                      class="hidden"
                      @change="importTheme"
                    />
                  </div>
                  <p class="text-small text-faint">
                    <I18nT keypath="settings.appearance.customTokensHint">
                      <template #file><code class="font-mono">config.yaml</code></template>
                    </I18nT>
                  </p>
                </div>
              </div>
            </div>
          </section>

          <section id="settings-explorer" class="vxs-enter scroll-mt-4">
            <h2 class="mb-2 font-medium">{{ t('settings.explorer.title') }}</h2>
            <label class="vxs-card flex cursor-pointer items-center gap-2 rounded-lg border border-card bg-surface p-4 shadow-card">
              <input
                type="checkbox"
                :checked="config.explorer.show_data_dir"
                :disabled="savingOther"
                class="accent-[var(--vxs-accent)]"
                @change="setShowDataDir(($event.target as HTMLInputElement).checked)"
              />
              <span>
                <I18nT keypath="settings.explorer.showDataDir">
                  <template #dir><code class="font-mono">.voxarium_studio/</code></template>
                </I18nT>
                <span class="block text-small text-faint">
                  {{ t('settings.explorer.showDataDirHint') }}
                </span>
              </span>
            </label>
          </section>

          <section id="settings-execution" class="vxs-enter scroll-mt-4">
            <h2 class="mb-2 font-medium">{{ t('settings.execution.title') }}</h2>
            <div class="vxs-card space-y-3 rounded-lg border border-card bg-surface p-4 shadow-card">
              <label class="flex cursor-pointer items-center gap-2">
                <input
                  type="checkbox"
                  :checked="config.node_cache_enabled"
                  :disabled="savingOther"
                  class="accent-[var(--vxs-accent)]"
                  @change="setNodeCache(($event.target as HTMLInputElement).checked)"
                />
                <span>
                  {{ t('settings.execution.reuseCache') }}
                  <span class="block text-small text-faint">
                    {{ t('settings.execution.reuseCacheHint') }}
                  </span>
                </span>
              </label>

            </div>
          </section>

          <section id="settings-updates" class="vxs-enter scroll-mt-4">
            <h2 class="mb-2 font-medium">{{ t('settings.updates.title') }}</h2>
            <div class="vxs-card space-y-3 rounded-lg border border-card bg-surface p-4 shadow-card">
              <div class="flex items-start gap-2">
                <Loader2 v-if="updates.running" class="mt-0.5 size-4 shrink-0 animate-spin text-accent" />
                <ArrowUpCircle
                  v-else-if="updates.available"
                  class="mt-0.5 size-4 shrink-0 text-accent"
                />
                <AlertTriangle
                  v-else-if="updates.status && !updates.status.supported"
                  class="mt-0.5 size-4 shrink-0 text-faint"
                />
                <CheckCircle2 v-else class="mt-0.5 size-4 shrink-0 text-success" />
                <div class="min-w-0 flex-1">
                  <p>{{ updatesSummary }}</p>
                  <p class="text-small text-faint">
                    <I18nT keypath="settings.updates.thisBuild">
                      <template #version>
                        <span class="font-mono">{{ session.project?.version?.display }}</span>
                      </template>
                    </I18nT>
                    <!-- The separator is literal text in the loop, not part of the message: a
                         block holding nothing but an interpolation loses the whitespace around it,
                         which ran the notes into the version. -->
                    <template v-for="note in buildNotes" :key="note"> · {{ note }}</template>
                  </p>
                  <p v-if="updates.status?.error" class="text-small text-warning">
                    {{ updates.status.error }}
                  </p>
                </div>
              </div>

              <div v-if="updates.status?.supported" class="flex flex-wrap gap-2">
                <button
                  v-if="updates.available || updates.running"
                  class="vxs-btn-primary flex items-center gap-2 px-3 py-1.5"
                  @click="updates.running ? (updates.dialogOpen = true) : updates.startFromButton()"
                >
                  <ArrowUpCircle class="size-4" />
                  {{ updates.running ? t('settings.updates.updating') : updates.label }}
                </button>
                <button
                  class="flex items-center gap-2 rounded-md border border-line px-3 py-1.5 hover:bg-hover disabled:opacity-50"
                  :disabled="updates.busy || updates.running"
                  @click="updates.check()"
                >
                  <RefreshCw class="size-4" :class="{ 'animate-spin': updates.busy }" />
                  {{ t('settings.updates.checkNow') }}
                </button>
              </div>

              <label v-if="!updates.desktop" class="flex cursor-pointer items-center gap-2">
                <input
                  type="checkbox"
                  :checked="config.updates.enabled"
                  :disabled="savingOther"
                  class="accent-[var(--vxs-accent)]"
                  @change="setUpdates({ enabled: ($event.target as HTMLInputElement).checked })"
                />
                <span>
                  {{ t('settings.updates.automatic') }}
                  <span class="block text-small text-faint">
                    {{ t('settings.updates.automaticHint') }}
                  </span>
                </span>
              </label>

              <label v-if="!updates.desktop" class="block">
                <span class="mb-1 block text-muted">{{ t('settings.updates.howOften') }}</span>
                <select
                  class="rounded-md border border-line bg-inset px-2 py-1 outline-none focus:border-accent disabled:opacity-50"
                  :value="config.updates.check_interval_minutes"
                  :disabled="savingOther || !config.updates.enabled"
                  @change="
                    setUpdates({
                      check_interval_minutes: Number(($event.target as HTMLSelectElement).value),
                    })
                  "
                >
                  <option v-for="option in UPDATE_INTERVALS" :key="option.value" :value="option.value">
                    {{ option.label }}
                  </option>
                </select>
              </label>

              <p class="text-small text-faint">
                <span v-if="updates.desktop">{{ t('updates.desktop.schedule') }}</span>
                <I18nT v-else keypath="settings.updates.howItWorks">
                  <template #command><code class="font-mono">git pull</code></template>
                </I18nT>
              </p>
            </div>
          </section>
    </template>
  </SettingsPage>
</template>
