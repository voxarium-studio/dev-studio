import { createApp } from 'vue'

import { captureTokenFromUrl } from './api/auth'
import { initI18n } from './i18n'
import { checkedDesktopBootstrap } from './desktop/tabs'

// Vue Flow's own styles first, so `base.css` can restyle its internals afterwards.
// These are CSS only; the project runtime stays behind the checked role below.
import '@vue-flow/core/dist/style.css'
import '@vue-flow/core/dist/theme-default.css'
import '@vue-flow/controls/dist/style.css'
import '@vue-flow/minimap/dist/style.css'
import './styles/index.css'

// Must run before the first request goes out, so the token is available to the API
// client and the WebSocket handshake alike.
captureTokenFromUrl()

// The interface language is restored before the first render, so a session in another
// language never paints a frame of English first.
void initI18n().then(async () => {
  // §5.3.4: choose the IPC-authorized role BEFORE importing the project graph.
  // A denied desktop bootstrap fails closed, never into the legacy adapter.
  let desktop
  try {
    desktop = await checkedDesktopBootstrap(window.voxariumTabs, window.location.pathname)
  } catch {
    const { default: DesktopShell } = await import('./DesktopShell.vue')
    createApp(DesktopShell).mount('#app')
    return
  }
  if (desktop?.role === 'shell') {
    const { default: DesktopShell } = await import('./DesktopShell.vue')
    createApp(DesktopShell).mount('#app')
    return
  }
  if (desktop?.role === 'project') document.body.classList.add('desktop-project')
  // Only browser/project renderers import Pinia, routing, sockets and plugins.
  const [{ default: App }, { createPinia }, { router }] = await Promise.all([
    import('./App.vue'), import('pinia'), import('./router'),
  ])
  createApp(App).use(createPinia()).use(router).mount('#app')
})
