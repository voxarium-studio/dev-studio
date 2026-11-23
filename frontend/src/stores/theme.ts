/**
 * Applies the persisted theme (spec §7) to the document.
 *
 * Appearance lives in the user config; only the accent travels in the managed project's
 * `.voxarium_studio/config.yaml` (§5.1, §7). The API returns the combined theme.
 * Applying one is only ever a matter of setting attributes and a few
 * custom properties on `<html>`; nothing is rebuilt and no classes are regenerated,
 * because every utility resolves through `var(--vxs-*)` at runtime.
 *
 * Every knob is an attribute (`data-sidebar`, `data-mono`, …) whose values are blocks in
 * `tokens.css`; the store writes exactly three custom properties of its own — the accent
 * *seed*, the node tint and, when the user has picked one, the text size — plus whatever
 * `custom_tokens` names. A monospace face that is not on the machine is fetched when first
 * selected; the interface face is always Allo, bundled.
 *
 * The seed rather than the accent itself: `:root` points `--vxs-accent` at
 * `--vxs-accent-seed`, so a `custom_tokens` override of `--vxs-accent` still wins — it is
 * inline, and the pointer is not.
 */

import { defineStore } from 'pinia'
import { computed, ref } from 'vue'

import { api } from '@/api/client'
import type { AppConfig, ThemeConfig } from '@/api/types'
import { DEFAULT_THEME, fontStylesheet } from '@/lib/appearance'

export const useThemeStore = defineStore('theme', () => {
  const theme = ref<ThemeConfig>({ ...DEFAULT_THEME, custom_tokens: {} })
  const saving = ref(false)

  /** Tracks the OS preference so `mode: "system"` keeps following it after load. */
  let mediaQuery: MediaQueryList | null = null
  const systemDark = ref(false)

  /** Custom properties the last `apply` wrote from `custom_tokens`, so a removed one is unset. */
  const appliedTokens = new Set<string>()

  /**
   * Dark or light *right now*, with `system` already resolved — and reactive, which is
   * what makes it publishable. Anything that has to re-render on a theme change reads
   * this: the Explorer's syntax highlighting picks its Shiki theme from it, and it must
   * reflow without a reload. The alternative for a consumer is reading
   * `<html data-theme>`, which holds the same answer and never tells anyone it changed.
   */
  const resolvedMode = computed<'dark' | 'light'>(() =>
    theme.value.mode === 'system' ? (systemDark.value ? 'dark' : 'light') : theme.value.mode,
  )

  /** The pipeline canvas and the org chart draw their background from this (§6.3). */
  const canvasGrid = computed(() => theme.value.canvas_grid)

  /**
   * Fetch a Google Fonts face the first time the user asks for it. Idempotent: the link
   * is keyed by the choice, and a face `index.html` or the OS already provides has no
   * stylesheet to fetch.
   */
  function ensureFontLoaded(choice: string): void {
    const href = fontStylesheet(choice)
    if (!href || document.head.querySelector(`link[data-vxs-font="${choice}"]`)) return
    const link = document.createElement('link')
    link.rel = 'stylesheet'
    link.href = href
    link.dataset.vxsFont = choice
    document.head.appendChild(link)
  }

  function apply(next: ThemeConfig): void {
    theme.value = next
    const root = document.documentElement

    root.dataset.theme = resolvedMode.value
    root.dataset.sidebar = next.sidebar
    root.dataset.mono = next.mono_font
    root.dataset.statusPalette = next.status_palette

    // A token dropped from `custom_tokens` has to leave the element too, or the editor's
    // remove button would appear to do nothing until the next reload. Done before the
    // built-ins below, so removing an override restores the built-in value.
    for (const property of appliedTokens) {
      if (!(property in next.custom_tokens)) {
        root.style.removeProperty(property)
        appliedTokens.delete(property)
      }
    }

    root.style.setProperty('--vxs-accent-seed', next.accent)
    root.style.setProperty('--vxs-node-tint', `${next.node_tint}%`)
    // Null means "follow the density knob", and following is what an absent property does.
    if (next.text_size) root.style.setProperty('--vxs-text-size', `${next.text_size}px`)
    else root.style.removeProperty('--vxs-text-size')

    ensureFontLoaded(next.mono_font)

    // Escape-hatch overrides from config.yaml, e.g. {"--vxs-bg-surface": "#101114"}.
    // Applied last so user overrides take precedence over the presets.
    for (const [property, value] of Object.entries(next.custom_tokens)) {
      if (!property.startsWith('--')) continue
      root.style.setProperty(property, value)
      appliedTokens.add(property)
    }
  }

  function watchSystemPreference(): void {
    if (mediaQuery) return
    mediaQuery = window.matchMedia('(prefers-color-scheme: dark)')
    systemDark.value = mediaQuery.matches
    mediaQuery.addEventListener('change', (event) => {
      systemDark.value = event.matches
      // `resolvedMode` has already followed; this is what re-stamps the DOM attribute.
      if (theme.value.mode === 'system') apply(theme.value)
    })
  }

  /** Apply the config that came back with `GET /api/project`. */
  function hydrate(next: ThemeConfig | undefined | null): void {
    watchSystemPreference()
    apply({ ...DEFAULT_THEME, ...(next ?? {}) })
  }

  /** Optimistically apply, then accept the validated effective theme or roll back. */
  async function persist(patch: Partial<ThemeConfig>, save: () => Promise<AppConfig>): Promise<void> {
    const previous = theme.value
    apply({ ...previous, ...patch })
    saving.value = true
    try {
      const config = await save()
      apply({ ...DEFAULT_THEME, ...config.theme })
    } catch (error) {
      apply(previous)
      throw error
    } finally {
      saving.value = false
    }
  }

  /** Appearance settings belong to the user, independently of the open project (§7). */
  async function update(patch: Partial<Omit<ThemeConfig, 'accent'>>): Promise<void> {
    // A legacy imported object may still contain accent at runtime despite its TS type.
    const { accent: _accent, ...applicationTheme } = patch as Partial<ThemeConfig>
    await persist(applicationTheme, () => api.updateApplicationSettings({ theme: applicationTheme }))
  }

  /** The one theme field that identifies the current project (§7). */
  async function updateAccent(accent: string): Promise<void> {
    await persist({ accent }, () => api.updateSettings({ theme: { accent } }))
  }

  return { theme, saving, resolvedMode, canvasGrid, hydrate, update, updateAccent }
})
