/**
 * The appearance catalogue behind Settings → Appearance (spec §7): every choice a project's
 * `theme` block can hold, what it is called on screen, and the two conversions the page needs
 * — a theme to a file, and a file back to a theme.
 *
 * Plain data and plain functions, no DOM, so the import parser can be tested for what it is: a
 * reader of untrusted JSON that must never let a stray value reach `<html>`. The store
 * applies; this module only describes and checks.
 *
 * A label is looked up each time it is read (`choice`), not once when this module loads, so
 * a control rendering `option.label` follows the interface language without knowing it has
 * one. Font names are names, and stay as they are.
 */
import type { ThemeConfig } from '@/api/types'
import { t, type MessageKey } from '@/i18n'

/**
 * What a project gets before it chooses anything: the Graphite design's own violet accent and
 * JetBrains Mono, which the design is drawn in. The backend's `ThemeConfig` carries the same
 * defaults; this copy exists so the page renders sensibly before `GET /api/project` has
 * answered.
 */
export const DEFAULT_THEME: ThemeConfig = {
  mode: 'dark',
  accent: '#a394e8',
  text_size: null,
  mono_font: 'jetbrains',
  sidebar: 'icons',
  canvas_grid: 'dots',
  node_tint: 16,
  status_palette: 'default',
  custom_tokens: {},
}

export interface Choice<T extends string> {
  value: T
  label: string
  /** One line under the control, when the label alone does not say what changes. */
  hint?: string
}

/**
 * A choice whose label is resolved in the language on screen whenever it is read. Extra fields
 * go through `extra` rather than a spread of the result: spreading an object reads its getter
 * once and copies the string, which is exactly the frozen label this exists to avoid.
 */
export function choice<T extends string, E extends object = object>(
  value: T,
  labelKey: MessageKey,
  extra?: E,
): Choice<T> & E {
  return {
    ...(extra as E),
    value,
    get label() {
      return t(labelKey)
    },
  }
}

export const MODES: Choice<ThemeConfig['mode']>[] = [
  choice('dark', 'appearance.mode.dark'),
  choice('light', 'appearance.mode.light'),
  choice('system', 'appearance.mode.system'),
]

/**
 * The seven swatches the page offers; anything else is the custom colour well's job. The first
 * is the default, the Graphite canvas's violet.
 */
export const ACCENTS = ['#a394e8', '#58a6ff', '#39c5cf', '#3fb950', '#d29922', '#f85149', '#db61a2']

/** Bounds for `text_size`, mirrored from the backend's field so the slider offers nothing refused. */
export const TEXT_SIZE = { min: 11, max: 18 }

/** Bounds for `node_tint`, the header wash on a canvas node, in percent of its category colour. */
export const NODE_TINT = { min: 0, max: 60 }

export interface FontFace<T extends string> extends Choice<T> {
  /**
   * Google Fonts stylesheet to load when chosen, or null for a face the OS already provides.
   * Loaded by the store on demand, so a project on the default pays for nothing it does not
   * use.
   */
  stylesheet: string | null
}

/*
 * No interface-font list: the interface is set in Allo alone (§7, 2026-11-17), bundled from
 * the `allo-font` package in `main.ts`. Only the monospace face is still a choice.
 */
export const MONO_FONTS: FontFace<ThemeConfig['mono_font']>[] = [
  { value: 'cascadia', label: 'Cascadia Code', stylesheet: null },
  {
    value: 'jetbrains',
    label: 'JetBrains Mono',
    stylesheet: 'https://fonts.googleapis.com/css2?family=JetBrains+Mono:wght@400;500&display=swap',
  },
  {
    value: 'fira',
    label: 'Fira Code',
    stylesheet: 'https://fonts.googleapis.com/css2?family=Fira+Code:wght@400;500&display=swap',
  },
  choice('system', 'appearance.font.systemMono', { stylesheet: null }),
]

export const SIDEBARS: Choice<ThemeConfig['sidebar']>[] = [
  choice('labels', 'appearance.sidebar.labels'),
  choice('icons', 'appearance.sidebar.icons'),
  choice('auto', 'appearance.sidebar.auto'),
]

export const CANVAS_GRIDS: Choice<ThemeConfig['canvas_grid']>[] = [
  choice('dots', 'appearance.canvasGrid.dots'),
  choice('lines', 'appearance.canvasGrid.lines'),
  choice('none', 'appearance.canvasGrid.none'),
]

export const STATUS_PALETTES: Choice<ThemeConfig['status_palette']>[] = [
  choice('default', 'appearance.statusPalette.default'),
  choice('accessible', 'appearance.statusPalette.accessible'),
]

/** The stylesheet a monospace choice needs, if any. */
export function fontStylesheet(value: string): string | null {
  return MONO_FONTS.find((entry) => entry.value === value)?.stylesheet ?? null
}

/** The whole theme as the file "Export theme…" hands over — the same shape config.yaml holds. */
export function exportTheme(theme: ThemeConfig): string {
  return JSON.stringify(theme, null, 2) + '\n'
}

/** Appearance export belongs to Settings; the project accent stays in its project (§7). */
export function exportApplicationTheme(theme: ThemeConfig): string {
  const { accent: _accent, ...applicationTheme } = theme
  return JSON.stringify(applicationTheme, null, 2) + '\n'
}

/** Accept old theme exports while leaving the current project's accent untouched (§7). */
export function parseImportedApplicationTheme(text: string): Partial<Omit<ThemeConfig, 'accent'>> {
  const { accent: _accent, ...applicationTheme } = parseImportedTheme(text)
  if (!Object.keys(applicationTheme).length) throw new Error(t('appearance.import.empty'))
  return applicationTheme
}

const HEX_COLOUR = /^#[0-9a-fA-F]{6}$/
const TOKEN_NAME = /^--[a-zA-Z0-9_-]+$/

/**
 * Read a theme back out of a file somebody imported.
 *
 * Every field is checked against the same closed sets the backend's `ThemeConfig` declares,
 * so a file that would be refused with a 422 is refused here first, with the field named;
 * a key the theme does not know is ignored rather than fatal, because a file from a newer
 * build may well carry one. Custom tokens are the one place a string reaches a stylesheet
 * verbatim, so their names are held to the `--name` shape and their values to plain text
 * without a `;` or `}` — a value cannot end the declaration it is in.
 */
export function parseImportedTheme(text: string): Partial<ThemeConfig> {
  let raw: unknown
  try {
    raw = JSON.parse(text)
  } catch {
    throw new Error(t('appearance.import.notJson'))
  }
  if (typeof raw !== 'object' || raw === null || Array.isArray(raw)) {
    throw new Error(t('appearance.import.notObject'))
  }
  const source = raw as Record<string, unknown>
  const out: Partial<ThemeConfig> = {}

  const pick = <K extends keyof ThemeConfig>(key: K, choices: readonly Choice<string>[]) => {
    if (!(key in source)) return
    const value = source[key]
    if (typeof value !== 'string' || !choices.some((choice) => choice.value === value)) {
      throw new Error(
        t('appearance.import.oneOf', { key, choices: choices.map((c) => c.value).join(', ') }),
      )
    }
    out[key] = value as ThemeConfig[K]
  }
  pick('mode', MODES)
  pick('mono_font', MONO_FONTS)
  pick('sidebar', SIDEBARS)
  pick('canvas_grid', CANVAS_GRIDS)
  pick('status_palette', STATUS_PALETTES)

  if ('accent' in source) {
    if (typeof source.accent !== 'string' || !HEX_COLOUR.test(source.accent)) {
      throw new Error(t('appearance.import.accent'))
    }
    out.accent = source.accent.toLowerCase()
  }
  if ('text_size' in source) {
    const value = source.text_size
    if (value === null) out.text_size = null
    else if (
      typeof value !== 'number' ||
      !Number.isInteger(value) ||
      value < TEXT_SIZE.min ||
      value > TEXT_SIZE.max
    ) {
      throw new Error(t('appearance.import.textSize', TEXT_SIZE))
    } else out.text_size = value
  }
  if ('node_tint' in source) {
    const value = source.node_tint
    if (
      typeof value !== 'number' ||
      !Number.isInteger(value) ||
      value < NODE_TINT.min ||
      value > NODE_TINT.max
    ) {
      throw new Error(t('appearance.import.nodeTint', NODE_TINT))
    }
    out.node_tint = value
  }
  if ('custom_tokens' in source) {
    const tokens = source.custom_tokens
    if (typeof tokens !== 'object' || tokens === null || Array.isArray(tokens)) {
      throw new Error(t('appearance.import.customTokens'))
    }
    const clean: Record<string, string> = {}
    for (const [name, value] of Object.entries(tokens as Record<string, unknown>)) {
      if (!TOKEN_NAME.test(name)) throw new Error(t('appearance.import.tokenName', { name }))
      if (typeof value !== 'string' || /[;{}]/.test(value) || value.length > 200) {
        throw new Error(t('appearance.import.tokenValue', { name }))
      }
      clean[name] = value.trim()
    }
    out.custom_tokens = clean
  }
  if (!Object.keys(out).length) throw new Error(t('appearance.import.empty'))
  return out
}
