/**
 * The plugin frontend contract, as types.
 *
 * A plugin's UI is a separate ES module loaded at runtime, so it shares no build with the
 * Studio and cannot import the app's types. This file is what it gets instead: copy it into
 * your plugin project (or point `tsconfig.json` at it) and the three things a plugin can
 * contribute — a page, a custom canvas node and a file previewer — have real shapes.
 *
 * The Studio checks this file against the runtime it actually passes
 * (`src/plugin-runtime/contract.ts`), so a member that is removed or renamed here fails the
 * app's own type check rather than silently misleading a plugin author.
 *
 * `vue`, `vue-router` and `@vue-flow/core` are needed as *dev* dependencies for these types.
 * They must never be bundled into the plugin: the host passes its own live instances in, and
 * a second copy of Vue shares no reactivity, and a second Vue Flow registers handles with a
 * store the canvas does not read.
 */

import type { Component } from 'vue'

/**
 * The subset of the Studio's REST client that plugins are expected to call.
 *
 * Payloads are `object` rather than named shapes: they are the API's own JSON, they change
 * with the Studio's version, and a plugin that pins their fields breaks on an upgrade it did
 * not ask for. Narrow them yourself, at the one place you read them.
 */
export interface VoxariumStudioApi {
  project(): Promise<object>
  status(): Promise<object>
  plugins(): Promise<object[]>
  nodeTypes(): Promise<{ node_types: object[] }>
  pluginSettings(id: string): Promise<{ settings: Record<string, unknown> }>
  savePluginSettings(id: string, settings: Record<string, unknown>): Promise<unknown>
  /**
   * The agent backends and the suggested models per backend — the `options` prop of
   * `components.ModelSelector`. Pass it through whole; do not read its fields.
   */
  memberOptions(): Promise<object>
  installPluginEnv(id: string, force?: boolean): Promise<unknown>
}

/** What every plugin module's factory receives. */
export interface PluginRuntime {
  vue: typeof import('vue')
  vueRouter: typeof import('vue-router')
  /**
   * The host's Vue Flow, for a custom node's `Handle`/`Position`. It has to be this one:
   * handles registered from a bundled copy talk to a different store and never connect.
   */
  vueFlow: typeof import('@vue-flow/core')
  api: VoxariumStudioApi
  /** URL of a project file, without auth — for an `<img src>`, not for `fetch`. */
  rawFileUrl(path: string): string
  /** Bytes of a project file with the token attached — what a previewer parses. */
  rawFileBytes(path: string): Promise<ArrayBuffer>
  /**
   * The Explorer's previewer seam, reusable: the `/api/explorer/file` payload for `path`
   * together with the component whichever plugin claimed that file type registered for it.
   *
   * This is how a canvas node shows a file the way the Explorer shows it while knowing no
   * formats at all. `component` is null when nothing claimed the suffix — say so rather
   * than rendering an empty box. Mount it with `path` and `preview` as props, inside a
   * container that has a size: a previewer's root is `width: 100%; height: 100%`.
   */
  filePreviewer(path: string): Promise<{ preview: object; component: Component | null }>
  /**
   * Merge parameters into a node instance, exactly as the config panel does — including
   * marking the pipeline dirty, which is what makes the edit survive a Save.
   *
   * This is the only supported way for a custom node component to change what it is
   * showing. `data.params` is the instance's own object, so assigning to it appears to
   * work and then quietly loses the edit. Pass your own `props.id`; a node writing another
   * node's parameters is possible and is somebody's bug.
   *
   * Only the pipeline editor provides it: called from a page, it warns and does nothing.
   */
  setNodeParams(nodeId: string, patch: Record<string, unknown>): void
  /**
   * Subscribe to a backend event; the return value unsubscribes. The payload is the event's
   * own JSON — the host types it per event, a plugin narrows it where it reads it.
   */
  onEvent(type: string, handler: (payload: unknown) => void): () => void
  /** Settings persistence, already bound to this plugin's id. */
  settings: {
    get(): Promise<Record<string, unknown>>
    save(values: Record<string, unknown>): Promise<void>
  }
  /**
   * Call one of this plugin's own backend actions (`registry.add_action()` in `plugin.py`),
   * already bound to this plugin's id. The payload is the handler's argument and the answer
   * is whatever it returned — the plugin's own shapes on both sides. How a settings page
   * starts work on the server, or reads state only the plugin has.
   */
  action(id: string, payload?: object): Promise<unknown>
  /**
   * The language the Studio's interface is shown in, and a way to speak it.
   *
   * A plugin ships its own messages — it cannot add keys to the Studio's locale files — and
   * `translator` turns them into a `t` that follows the Studio's language reactively, so a
   * render function that calls it re-renders when the reader switches language. Call it once,
   * outside `setup`:
   *
   *   const t = i18n.translator({
   *     en: { save: 'Save', files: { one: '{count} file', other: '{count} files' } },
   *     ru: { save: 'Сохранить', files: { one: '{count} файл', few: '{count} файла', many: '{count} файлов', other: '{count} файла' } },
   *   })
   *   h('button', t('save'))
   *
   * The format is the Studio's own: `{name}` is filled from the parameters and any other brace
   * is text; a message that varies by number has one text per `Intl.PluralRules` category and
   * is chosen by `count`, which is also printed with the language's digit grouping. A message
   * missing in the current language falls back to the catalogue's `en`, then to the key.
   */
  i18n: {
    /** `'en'`, `'ru'`, … — reactive; pass `locale.value` to `toLocaleString` and friends. */
    readonly locale: { readonly value: string }
    translator(catalogue: Record<string, PluginMessages>): (
      key: string,
      params?: Record<string, string | number>,
    ) => string
  }
  /**
   * The Studio's own widgets, so a plugin's controls look and behave like the app's.
   *
   * `ModelSelector` is the backend/model picker of members, tasks and chats — one row: an
   * icon per agent backend, then the suggested models for the chosen one, with "Something
   * else…" for typing any id. Props: `backend` and `model` (string or null), `options` (from
   * `api.memberOptions()`; null while loading), and optionally `backends` — the backend ids
   * to offer, e.g. `['claude_code']` when the plugin's model call goes through `claude -p` —
   * plus `disabled` and `label`. It emits `change(backend, model)`; `model` is `''` for the
   * backend's default model. Mount it with `h(components.ModelSelector, { …, onChange })`.
   */
  components: {
    ModelSelector: Component
  }
}

/** A plugin's messages for one language: nested sections, texts, and plural objects. */
export interface PluginMessages {
  [key: string]: string | PluginMessages
}

/**
 * `data` on a canvas node.
 *
 * The canvas *paints* run status onto this object rather than rebuilding the node, so
 * `status`, `error` and `fromCache` appear and change while a run streams — read them
 * reactively, and do not cache them in `setup`.
 */
export interface PluginNodeData {
  /** The node type's schema, as `GET /api/node-types` returns it. */
  schema: object | null
  label: string
  typeId: string
  /** The category colour, to be spent as `--node-accent` rather than a literal. */
  accent: string
  params?: Record<string, unknown>
  /**
   * Which inputs this node shows a handle for, overriding each port's `connectable`. A port
   * with no entry follows the schema. A custom component that draws its own port rows should
   * read this, or a pin the user added is one the canvas offers and the node does not.
   */
  pins?: Record<string, boolean>
  /** Input ports that currently have an edge into them — painted on like `status`. */
  connected?: string[]
  /** Set when the node holds the run open once it succeeds. */
  pauseOnComplete?: boolean
  status?:
    | 'pending'
    | 'running'
    /** Finished, and holding the run open until the user continues it. */
    | 'paused'
    | 'success'
    | 'error'
    | 'cancelled'
    | 'skipped'
    | 'cached'
  error?: string | null
  fromCache?: boolean
  /**
   * What the node emitted on its last run, painted on by the canvas exactly as `status` is —
   * so it appears when a node finishes and must be read reactively, not cached in `setup`.
   * Absent until this node has produced something in the run currently on screen.
   */
  outputs?: Record<string, unknown>
  /**
   * How far along the node says it is, while it is running — the `node.progress` event,
   * painted on like `status` and reset to null the moment the node stops running.
   *
   * Drawing it is optional but nearly free: emit `.vxs-node-progress` with a
   * `.vxs-node-progress-track` and a `.vxs-node-progress-fill` whose width is `percent`,
   * and the host's stylesheet gives you the same bar the generic node has. Every field can
   * be null — a node that knows only its stage sends `label` alone, and a null `percent`
   * with the class `vxs-node-progress-unknown` is what draws the indeterminate sweep.
   */
  progress?: {
    percent: number | null
    label: string | null
    detail: string | null
    eta_s: number | null
    step: number | null
    steps: number | null
  } | null
  disabled?: boolean
  [key: string]: unknown
}

/**
 * Props a custom canvas node is mounted with (`frontend.nodes[].component`).
 *
 * Vue Flow passes more than this, but these three are what the Studio guarantees; declare
 * them on your component exactly as below, since a plain component's inferred props never
 * structurally match Vue Flow's own `NodeProps`.
 */
export interface PluginNodeProps {
  id: string
  data: PluginNodeData
  selected: boolean
}

/** Props a file previewer is mounted with (`frontend.previewers[]`). */
export interface PluginPreviewerProps {
  /** Project-relative path of the file being previewed. */
  path: string
  /** The `/api/explorer/file` payload for it. */
  preview: object | null
}

/** The default export of any plugin frontend module. */
export type PluginModuleFactory = (runtime: PluginRuntime) => Component
