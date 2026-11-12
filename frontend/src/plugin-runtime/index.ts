/**
 * The object handed to every plugin-supplied UI module.
 *
 * Plugins must not bundle their own copy of Vue: two Vue instances on one page do not
 * share reactivity, provide/inject or the router, so a plugin page built that way would
 * subtly misbehave rather than fail loudly. Bare `import … from 'vue'` in a separately
 * built ES module cannot resolve in the browser either, and an import map would still
 * hand the plugin a *different* instance than the bundled app uses.
 *
 * Passing the host's live module namespaces into a factory sidesteps all of it: one
 * Vue, one router, one API client, and the plugin bundle ends up with no external
 * imports at all.
 */

import * as vueFlow from '@vue-flow/core'
import * as vue from 'vue'
import * as vueRouter from 'vue-router'
import type { Component, ComputedRef } from 'vue'

import { api, rawFileBytes, rawFileUrl } from '@/api/client'
import type { PreviewResult } from '@/api/types'
import { createTranslator, locale, type Locale } from '@/i18n'
import { onEvent } from '@/ws/connection'
import ModelSelector from '@/components/ui/ModelSelector.vue'

import { setNodeParams } from './node-params'

export interface PluginRuntime {
  vue: typeof vue
  vueRouter: typeof vueRouter
  /**
   * The host's Vue Flow. A custom node component needs `Handle`/`Position` to draw its
   * ports, and those must come from the same instance that owns the canvas — a bundled
   * copy would register handles with a different store and never connect.
   */
  vueFlow: typeof vueFlow
  api: typeof api
  rawFileUrl: typeof rawFileUrl
  /** Bytes of a project file, auth included — what a file previewer parses. */
  rawFileBytes: typeof rawFileBytes
  /**
   * The Explorer's previewer seam, reusable (§6.2): the `/api/explorer/file` payload for
   * `path`, plus the component whichever plugin claimed that file type registered for it.
   *
   * It exists so a canvas node can show a file the way the Explorer shows it without
   * knowing a single format — `preview3d.view` is the reason, and the reason that node
   * covers `.vox` today and every type a previewer is added for tomorrow. `component` is
   * null when nothing claimed the suffix, which is a thing worth *saying* rather than an
   * error; mount it with `path` and `preview` as props, in a sized container.
   */
  filePreviewer: (path: string) => Promise<{ preview: PreviewResult; component: Component | null }>
  /**
   * Write parameters back onto a node instance, the way the config panel does — the only
   * supported way for a custom node component to edit what it is showing. `remove_background`
   * is why: its eyedropper produces a colour the document has to keep.
   */
  setNodeParams: typeof setNodeParams
  /** Subscribe to backend events; returns an unsubscribe function. */
  onEvent: typeof onEvent
  /** Settings persistence for this plugin, pre-bound to its id. */
  settings: {
    get: () => Promise<Record<string, unknown>>
    save: (values: Record<string, unknown>) => Promise<void>
  }
  /**
   * Call one of this plugin's own actions — `registry.add_action()` on the backend, reached
   * as `POST /api/plugins/{id}/actions/{action}` (§9.7). The one way a plugin page talks to
   * its own Python: the payload is the handler's argument, the answer is what it returned.
   */
  action: (id: string, payload?: object) => Promise<unknown>
  /**
   * The interface language, for a plugin's own strings. A plugin cannot add keys to the
   * Studio's locale files, so it ships its messages itself and `translator` turns them into a
   * `t` that follows the Studio's language reactively — same message format, plurals and
   * `{placeholders}` included (`@/i18n`).
   */
  i18n: {
    locale: ComputedRef<Locale>
    translator: typeof createTranslator
  }
  /**
   * The Studio's own widgets, so a plugin's controls look and behave like the app's (§9.3).
   * `ModelSelector` is the backend/model picker of members, tasks and chats; it takes
   * `options` from `api.memberOptions()` and emits `change(backend, model)`.
   */
  components: {
    ModelSelector: Component
  }
}

export function createRuntime(pluginId: string): PluginRuntime {
  return {
    vue,
    vueRouter,
    vueFlow,
    api,
    rawFileUrl,
    rawFileBytes,
    filePreviewer: async (path) => {
      const preview = await api.preview(path)
      const previewer = preview.previewer
      if (!previewer) return { preview, component: null }
      // Imported here rather than at the top of the file: `loader.ts` is what turns a plugin
      // module URL into a component, and it does that by calling back into this one. A static
      // import would close that loop at module-evaluation time to no purpose, since nothing
      // in this file needs the loader until a plugin actually asks for a previewer.
      //
      // The build's `INEFFECTIVE_DYNAMIC_IMPORT` warning about this line is expected and
      // correct: the loader is statically imported by half the app, so it cannot move into a
      // chunk of its own. Splitting the bundle was never the point — breaking the cycle was.
      const { pluginComponent } = await import('./loader')
      return {
        preview,
        component: pluginComponent(
          previewer.component_url,
          previewer.plugin_id,
          `Previewer ${previewer.id}`,
        ),
      }
    },
    setNodeParams,
    onEvent,
    settings: {
      get: async () => (await api.pluginSettings(pluginId)).settings,
      save: async (values) => {
        await api.savePluginSettings(pluginId, values)
      },
    },
    action: (id, payload) => api.pluginAction(pluginId, id, payload),
    i18n: { locale, translator: createTranslator },
    components: { ModelSelector },
  }
}
