/**
 * The import parser is the one place a file somebody downloaded reaches the stylesheet, so
 * what it lets through — and what it names when it refuses — is pinned here.
 */
import { describe, expect, it } from 'vitest'

import {
  ACCENTS, DEFAULT_THEME, exportApplicationTheme, exportTheme, fontStylesheet,
  parseImportedApplicationTheme, parseImportedTheme,
} from '@/lib/appearance'

describe('application appearance import/export', () => {
  it('exports shared appearance without the project accent', () => {
    const { accent: _accent, ...shared } = DEFAULT_THEME
    expect(JSON.parse(exportApplicationTheme({ ...DEFAULT_THEME, accent: '#ff0000' }))).toEqual(shared)
    expect(parseImportedApplicationTheme(exportApplicationTheme(DEFAULT_THEME))).toEqual(shared)
  })

  it('imports legacy exports without changing the project accent', () => {
    expect(parseImportedApplicationTheme('{"mode":"light","accent":"#ff0000"}'))
      .toEqual({ mode: 'light' })
    expect(() => parseImportedApplicationTheme('{"accent":"#ff0000"}')).toThrow(/no theme fields/)
    expect(() => parseImportedApplicationTheme('{"mode":"invalid","accent":"#ff0000"}'))
      .toThrow(/"mode" must be one of/)
  })
})

describe('exportTheme / parseImportedTheme', () => {
  it('round-trips the whole theme', () => {
    const theme = {
      ...DEFAULT_THEME,
      mode: 'light' as const,
      accent: '#3FB950',
      text_size: 15,
      custom_tokens: { '--vxs-bg-surface': '#101114' },
    }
    expect(parseImportedTheme(exportTheme(theme))).toEqual({ ...theme, accent: '#3fb950' })
  })

  it('refuses what the backend would refuse, naming the field', () => {
    expect(() => parseImportedTheme('{"sidebar": "hidden"}')).toThrow(/"sidebar" must be one of/)
    expect(() => parseImportedTheme('{"accent": "purple"}')).toThrow(/"accent"/)
    expect(() => parseImportedTheme('{"text_size": 40}')).toThrow(/"text_size"/)
    expect(() => parseImportedTheme('{"text_size": 12.5}')).toThrow(/"text_size"/)
    expect(() => parseImportedTheme('{"node_tint": -1}')).toThrow(/"node_tint"/)
  })

  it('keeps a custom token to the shape of a declaration', () => {
    expect(() => parseImportedTheme('{"custom_tokens": {"color": "red"}}')).toThrow(/--vxs-bg-surface/)
    expect(() =>
      parseImportedTheme('{"custom_tokens": {"--vxs-bg-surface": "red; } body { display: none"}}'),
    ).toThrow(/without ; or braces/)
    expect(parseImportedTheme('{"custom_tokens": {"--vxs-radius-md": " 10px "}}')).toEqual({
      custom_tokens: { '--vxs-radius-md': '10px' },
    })
  })

  it('ignores keys it does not know and refuses a file with nothing in it', () => {
    expect(parseImportedTheme('{"mode": "dark", "future_knob": 3}')).toEqual({ mode: 'dark' })
    expect(() => parseImportedTheme('{"future_knob": 3}')).toThrow(/no theme fields/)
    expect(() => parseImportedTheme('[1, 2]')).toThrow(/theme object/)
    expect(() => parseImportedTheme('not json')).toThrow(/not JSON/)
  })

  it('applies null to text_size rather than reading it as omitted', () => {
    expect(parseImportedTheme('{"text_size": null}')).toEqual({ text_size: null })
  })

  it('ignores the knobs that no longer exist rather than refusing the file', () => {
    // A theme exported before 2026-09-11 names four knobs folded into the look, and one exported
    // before 2026-09-16 names the look itself — gone with every look but Graphite. Both are
    // ignored like any other unknown key: the file still carries an accent, fonts and custom
    // tokens worth importing.
    expect(
      parseImportedTheme('{"palette": "warm", "surfaces": "tonal", "radius": "sharp", "mode": "light"}'),
    ).toEqual({ mode: 'light' })
    // The interface font has been Allo alone since 2026-11-17, so an old `font` goes the same way.
    expect(
      parseImportedTheme('{"look": "material", "font": "roboto", "mono_font": "fira"}'),
    ).toEqual({ mono_font: 'fira' })
  })

  it('starts a project on the Graphite design’s own accent and monospace face', () => {
    expect(DEFAULT_THEME).toMatchObject({ accent: '#a394e8', mono_font: 'jetbrains' })
    expect(DEFAULT_THEME).not.toHaveProperty('look')
    // The default is the first swatch, so a new project's accent reads as picked on the page.
    expect(ACCENTS[0]).toBe(DEFAULT_THEME.accent)
  })
})

describe('fontStylesheet', () => {
  it('names a stylesheet only for faces the page has to fetch', () => {
    expect(fontStylesheet('cascadia')).toBeNull()
    expect(fontStylesheet('system')).toBeNull()
    expect(fontStylesheet('fira')).toMatch(/fonts\.googleapis\.com/)
    expect(fontStylesheet('jetbrains')).toMatch(/JetBrains\+Mono/)
    // The interface face is bundled, never a choice the page fetches.
    expect(fontStylesheet('inter')).toBeNull()
    expect(fontStylesheet('nope')).toBeNull()
  })
})
