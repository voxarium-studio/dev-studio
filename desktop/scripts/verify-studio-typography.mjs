// Compile the production shell and a fixture mounting the real editor and a built-in plugin.
// Read drawn glyph runs offline; no test server or Studio process is involved.
import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { existsSync, readFileSync, writeFileSync } from 'node:fs'
import { extname, dirname, join, resolve, sep } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'

assert.ok(process.argv[2], 'Supply a disposable working directory for the probe')
const temp = resolve(process.argv[2])
const dist = join(temp, 'web-dist')
const editorDist = join(temp, 'web-editor-probe-dist')
const chrome = process.env.CHROME_EXE || 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe'
const origin = 'http://studio-fixture.invalid'
const editorOrigin = 'http://studio-editor-fixture.invalid'
const sample = { mixed: 'Voxarium Студия Ёж', latin: 'Voxarium Studio', cyrillic: 'СтудияЁж', missing: '∂', system: '🚀' }

const frontend = resolve(dirname(fileURLToPath(import.meta.url)), '../../frontend')
const { build } = await import(pathToFileURL(join(frontend, 'node_modules/vite/dist/node/index.js')))
const virtualPath = join(frontend, '__studio-font-probe.mjs').replaceAll('\\', '/')
const id = 'virtual:studio-font-probe'
process.chdir(frontend)
await build({
  configFile: join(frontend, 'vite.config.ts'),
  build: { outDir: dist, emptyOutDir: true },
})
await build({
  configFile: join(frontend, 'vite.config.ts'),
  build: {
    outDir: join(temp, 'web-editor-probe-dist'),
    emptyOutDir: true,
    manifest: true,
    rollupOptions: { input: { 'studio-font-probe': id } },
  },
  plugins: [{ name: 'offline-editor-probe',
    resolveId(source) { return source === id ? virtualPath : null },
    load(source) {
      if (source !== virtualPath) return null
      return `import * as vue from 'vue'
        import BlockEditor from '@/components/docs/BlockEditor.vue'
        import graphifySettings from '../backend/plugins_builtin/graphify/frontend/SettingsPage.js'
        import '@/styles/index.css'
        const { createApp, h, ref } = vue
        const sample = ${JSON.stringify(sample.mixed)}
        const Graphify = graphifySettings({
          vue,
          api: { memberOptions: async () => null },
          settings: { get: async () => ({ documents: false }) },
          action: async () => ({
            resolved: 'graphify', launch_root: sample, command: ['graphify'],
            indexer: { error: sample },
          }),
          onEvent: () => () => {},
          i18n: {
            locale: ref('ru'),
            translator: messages => key => key.split('.').reduce((value, part) => value?.[part], messages.ru) ?? key,
          },
        })
        createApp({ setup: () => () => h('main', { class: 'desktop-project' }, [
          h(BlockEditor, { path: 'probe.md', source: window.probeMarkdown, documents: [] }),
          h('button', { class: 'font-mono', id: 'mono-button' }, 'code Студия'),
          h('div', { id: 'graphify' }, [h(Graphify)]),
        ]) }).mount('#app')`
    },
  }],
})

// The assets inspected by build-studio-fonts.py are the bytes Chromium will receive.
for (const path of ['allo/Allo-Rond100.woff2', 'allo/OFL.txt', 'inter/InterVariable.woff2',
  'inter/InterVariable-Italic.woff2', 'inter/LICENSE.txt']) {
  const source = readFileSync(join(frontend, 'public', 'fonts', path))
  for (const output of [dist, editorDist]) {
    assert.ok(source.equals(readFileSync(join(output, 'fonts', path))), path)
  }
}

const chromeProcess = spawn(chrome, [
  '--headless', '--remote-debugging-pipe', '--no-first-run', '--no-default-browser-check',
  '--disable-background-networking', '--disable-component-update', '--disable-sync',
  '--host-resolver-rules=MAP * ~NOTFOUND', `--user-data-dir=${join(temp, 'chrome-web-profile')}`,
  '--window-size=1280,880', 'about:blank',
], { stdio: ['ignore', 'ignore', 'ignore', 'pipe', 'pipe'], windowsHide: true })

chromeProcess.on('error', error => {
  console.error(error)
  process.exit(1)
})
const pending = new Map()
const eventHandlers = new Map()
let pipeBuffer = ''
let nextMessageId = 0
let sessionId
const timeout = setTimeout(() => {
  chromeProcess.kill()
  console.error('Timed out waiting for Chromium CDP')
  process.exit(1)
}, 55000)
chromeProcess.stdio[4].setEncoding('utf8')
chromeProcess.stdio[4].on('data', chunk => {
  pipeBuffer += chunk
  for (let end; (end = pipeBuffer.indexOf('\0')) !== -1;) {
    const msg = JSON.parse(pipeBuffer.slice(0, end))
    pipeBuffer = pipeBuffer.slice(end + 1)
    if (msg.id) {
      const { resolve, reject } = pending.get(msg.id) ?? {}
      pending.delete(msg.id)
      if (msg.error) reject?.(new Error(JSON.stringify(msg.error)))
      else resolve?.(msg.result)
    } else eventHandlers.get(msg.method)?.(msg.params)
  }
})

const send = (method, params = {}, scope = sessionId) => new Promise((resolve, reject) => {
  const id = ++nextMessageId
  pending.set(id, { resolve, reject })
  chromeProcess.stdio[3].write(JSON.stringify({ id, method, params, sessionId: scope }) + '\0')
})
const evaluate = async expression => {
  const { exceptionDetails, result } = await send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true })
  if (exceptionDetails) throw new Error(exceptionDetails.exception?.description || exceptionDetails.text)
  return result.value
}
const waitFor = async expression => {
  for (let i = 0; i < 100; i++) {
    if (await evaluate(expression)) return
    await new Promise(resolve => setTimeout(resolve, 100))
  }
  throw new Error(`Timed out waiting for ${expression}`)
}
const awaitFonts = () => evaluate('document.fonts.ready.then(() => new Promise(r => requestAnimationFrame(() => requestAnimationFrame(() => r(true)))))')

const mime = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.css': 'text/css', '.woff2': 'font/woff2', '.png': 'image/png', '.ico': 'image/x-icon' }
const fulfilledFonts = new Set()
const declined = new Set()
const editorManifest = JSON.parse(readFileSync(join(editorDist, '.vite/manifest.json'), 'utf8'))
const entry = Object.values(editorManifest).find(value => value.isEntry && value.name === 'studio-font-probe')
assert.ok(entry, 'missing standalone editor entry')
const editorStyles = new Set()
function collectStyles(value) {
  for (const path of value.css ?? []) editorStyles.add(path)
  for (const dependency of value.imports ?? []) collectStyles(editorManifest[dependency])
}
collectStyles(entry)
const markdown = [...Object.values(sample).flatMap(value => [value, `**${value}**`]), `*${sample.mixed}*`, '`code Студия`', '```\ncode Студия\n```'].join('\n\n') + '\n'
const fixture = `<!doctype html><html lang="en" data-theme="dark" data-mono="system"><meta charset="utf-8">${[...editorStyles].map(path => `<link rel="stylesheet" href="/${path}">`).join('')}<body><div id="app"></div><script type="module" src="/${entry.file}"></script></body></html>`

const expected = { allo: 'Allo Round 100', inter: 'Inter Variable' }
const results = []
const fontAt = async (scenario, selector, families, weight, extraAssertions = () => {}) => {
  const { root } = await send('DOM.getDocument')
  const { nodeId } = await send('DOM.querySelector', { nodeId: root.nodeId, selector })
  assert.ok(nodeId, selector)
  const { fonts } = await send('CSS.getPlatformFontsForNode', { nodeId })
  const info = await evaluate(`(() => { const e = document.querySelector(${JSON.stringify(selector)}); const s = getComputedStyle(e); return {text:e.textContent, fontFamily:s.fontFamily, fontWeight:s.fontWeight, fontStyle:s.fontStyle}; })()`)
  const found = [...new Set(fonts.map(f => f.familyName))].sort()
  if (families) assert.deepEqual(found, [...families].sort(), `${scenario} ${selector}: ${JSON.stringify(fonts)}`)
  if (weight) assert.equal(info.fontWeight, weight, `${scenario} ${selector}`)
  if (families?.length && families.every(f => Object.values(expected).includes(f))) {
    assert.ok(fonts.every(f => f.isCustomFont && f.glyphCount > 0), `${scenario}/${selector} did not use bundled font`)
    const variant = { '600': 'SemiBold', '700': 'Bold' }[weight]
    if (variant) assert.ok(fonts.every(f => f.postScriptName.includes(`-${variant}`)), JSON.stringify(fonts))
  }
  extraAssertions(fonts, info)
  results.push({ scenario, selector, ...info, fonts })
}

try {
  const browser = await send('Browser.getVersion')
  const { targetId } = await send('Target.createTarget', { url: 'about:blank' })
  ;({ sessionId } = await send('Target.attachToTarget', { targetId, flatten: true }))
  await send('DOM.enable')
  await send('CSS.enable')
  await send('Page.enable')
  await send('Network.enable')
  await send('Network.setCacheDisabled', { cacheDisabled: true })

  // All bytes come from files; even the synthetic origin is fulfilled only inside CDP.
  // Unknown requests (including all external origins) are explicitly blocked.
  eventHandlers.set('Fetch.requestPaused', ({ requestId, request }) => {
    void (async () => {
      const url = new URL(request.url)
      if ([origin, editorOrigin].includes(url.origin)) {
        const requested = url.pathname === '/' ? '/index.html' : url.pathname
        const directory = url.origin === editorOrigin ? editorDist : dist
        const path = resolve(directory, `.${requested}`)
        const type = mime[extname(path)]
        const body = (url.origin === editorOrigin && requested === '/index.html') ? Buffer.from(fixture) : path.startsWith(directory + sep) && type && existsSync(path) ? readFileSync(path) : null
        if (body) {
          if (type === 'font/woff2') fulfilledFonts.add(requested)
          await send('Fetch.fulfillRequest', { requestId, responseCode: 200, responseHeaders: [ { name: 'Content-Type', value: type } ], body: body.toString('base64') })
          return
        }
      }
      declined.add(request.url)
      await send('Fetch.failRequest', { requestId, errorReason: 'InternetDisconnected' })
    })().catch(error => { console.error(error); process.exitCode = 1 })
  })
  await send('Fetch.enable', { patterns: [{ urlPattern: '*' }] })

  const state = { role: 'shell', window: { windowId: 'offline', revision: 1, activeTabId: 'mixed' },
    tabs: Object.entries(sample).slice(0, 3).map(([tabId, name]) => ({ tabId, ownerWindowId: 'offline', state: 'ready', revision: 1, project: { root: 'D:\\Demo\\' + name } })) }
  await send('Page.addScriptToEvaluateOnNewDocument', { source: `
    if (location.origin === ${JSON.stringify(editorOrigin)}) {
      window.probeMarkdown = ${JSON.stringify(markdown)};
    } else if (location.origin === ${JSON.stringify(origin)} && location.pathname === '/') {
      const state = ${JSON.stringify(state)};
      window.voxariumTabs = { bootstrap: async () => state, getState: async () => state, onStateChange: () => () => {},
        recentProjects: async () => [], pickerStatus: async () => ({ available: true }), setDragGeometry: async () => {} };
      window.voxariumWindow = { onStateChange: () => () => {}, getState: async () => ({ maximized: false, focused: true }) };
    }`
  })
  await send('Page.navigate', { url: origin + '/' })
  await waitFor(`document.querySelector('[data-tab-id="cyrillic"] .project-tab-strip__name')?.textContent === ${JSON.stringify(sample.cyrillic)}`)
  await awaitFonts()
  for (const [key, families] of Object.entries({ mixed: [expected.allo, expected.inter], latin: [expected.allo], cyrillic: [expected.inter] })) {
    await fontAt('compiled Vue Electron shell', `[data-tab-id="${key}"] .project-tab-strip__name`, families, '400')
  }
  await fontAt('compiled Vue Electron shell', '[data-detach-tab-id="mixed"]', [expected.allo], '400', (_, info) => assert.equal(info.text, '↗'))
  await fontAt('compiled Vue Electron shell', '[data-close-tab-id="mixed"]', [expected.allo], '400', (_, info) => assert.equal(info.text, '×'))
  await fontAt('compiled Vue Electron shell', '.desktop-titlebar__controls svg.lucide-x', [], null, () => {})
  assert.equal(await evaluate(`document.querySelector('.desktop-titlebar__controls svg.lucide-x').getBBox().width > 0`), true)
  writeFileSync(join(temp, 'web-shell-chromium.png'), Buffer.from((await send('Page.captureScreenshot')).data, 'base64'))

  await send('Page.navigate', { url: editorOrigin + '/' })
  await waitFor(`!!document.querySelector('.ProseMirror.markdown-body pre code') && document.styleSheets.length > 0`)
  const editor = 'compiled Vue/Tiptap BlockEditor'
  const parent = '.ProseMirror.markdown-body'
  const editorParagraphs = await evaluate(`Array.from(document.querySelectorAll('${parent} > p')).map(p => p.textContent)`)
  assert.deepEqual(editorParagraphs.slice(0, 10), Object.values(sample).flatMap(value => [value, value]))
  const paragraph = (key, bold = false) => `${parent} > p:nth-of-type(${2 * Object.keys(sample).indexOf(key) + (bold ? 2 : 1)})${bold ? ' > strong' : ''}`
  await awaitFonts()
  for (const [key, families] of Object.entries({ mixed: [expected.allo, expected.inter], latin: [expected.allo], cyrillic: [expected.inter], missing: [expected.inter], system: null })) {
    const extra = families ? undefined : (fonts => assert.ok(fonts.length && fonts.every(f => !f.isCustomFont), 'expected system glyph fallback'))
    await fontAt(editor, paragraph(key), families, '400', extra)
    await fontAt(editor, paragraph(key, true), families, '600', extra)
  }
  await evaluate(`(() => { const sheet = document.createElement('style'); sheet.textContent = '${paragraph('mixed', true)} { font-weight: 700; }'; document.head.append(sheet); return true; })()`)
  await awaitFonts()
  await fontAt(editor, paragraph('mixed', true), [expected.allo, expected.inter], '700')
  await fontAt(editor, `${parent} > p > em`, [expected.allo, expected.inter], '400', (_, info) => assert.equal(info.fontStyle, 'italic'))
  for (const selector of [`${parent} p code`, `${parent} pre code`, '#mono-button']) {
    await fontAt(editor, selector, null, '400', (fonts, info) => {
      assert.match(info.fontFamily, /monospace/)
      assert.ok(fonts.length && fonts.every(f => /Consolas|Cascadia|Courier/.test(f.familyName)), `wrong mono: ${JSON.stringify(fonts)}`)
    })
  }
  const builtIn = 'compiled Graphify settings'
  await waitFor(`Array.from(document.querySelectorAll('#graphify code')).some(e => e.textContent === ${JSON.stringify(sample.mixed)})`)
  await evaluate(`(() => {
    const text = ${JSON.stringify(sample.mixed)};
    for (const [tag, id] of [['div', 'graphify-message'], ['code', 'graphify-path']]) {
      const element = Array.from(document.querySelectorAll('#graphify ' + tag))
        .find(e => e.childElementCount === 0 && e.textContent === text);
      if (!element) throw new Error('Part of the built-in page is missing: ' + id);
      element.id = id;
    }
  })()`)
  await awaitFonts()
  await fontAt(builtIn, '#graphify-message', [expected.allo, expected.inter], '400')
  await fontAt(builtIn, '#graphify-path', null, '400', (fonts, info) => {
    assert.match(info.fontFamily, /monospace/)
    assert.ok(fonts.length && fonts.every(f => /Consolas|Cascadia|Courier/.test(f.familyName)))
  })
  const measure = await evaluate(`(() => {
    const e = document.createElement('canvas').getContext('2d');
    return [400, 700].map(w => { e.font = w + ' 32px "Allo Round 100"'; return e.measureText('Voxarium Studio').width; });
  })()`)
  assert.notEqual(measure[0], measure[1], '400 and 700 should render distinct glyph advances')
  for (const path of ['/fonts/allo/Allo-Rond100.woff2', '/fonts/inter/InterVariable.woff2', '/fonts/inter/InterVariable-Italic.woff2']) assert.ok(fulfilledFonts.has(path), path)
  assert.deepEqual([...declined].filter(url => url !== editorOrigin + '/favicon.ico'), [], 'no requests missed or outside the fixture')
  writeFileSync(join(temp, 'web-editor-chromium.png'), Buffer.from((await send('Page.captureScreenshot')).data, 'base64'))
  const report = { browser, method: 'CDP CSS.getPlatformFontsForNode. Every request fulfilled from compiled local files; no listener or backend.', fulfilledFonts: [...fulfilledFonts], declined: [...declined], alloCanvasWidthsAt32px: { weight400: measure[0], weight700: measure[1] }, results }
  writeFileSync(join(temp, 'web-render-results.json'), JSON.stringify(report, null, 2) + '\n')
  console.log(`${browser.product}: ${results.length} rendering checks passed`)
  for (const row of results) console.log(`${row.scenario} ${row.selector} ${row.fontWeight} (${row.text}): ${row.fonts.map(f => `${f.familyName}/${f.postScriptName}(${f.glyphCount})`).join(' + ') || 'SVG (no font)'}`)
} finally {
  clearTimeout(timeout)
  try { await send('Browser.close', {}, null) } catch {}
  chromeProcess.kill()
}
