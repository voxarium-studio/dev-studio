// @vitest-environment jsdom
import { createApp, nextTick, type App } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import ChatList from './ChatList.vue'

const { chat, teams } = vi.hoisted(() => ({
  chat: {
    chats: [
      { id: 'one', member_id: 'agent', title: 'First chat' },
      { id: 'two', member_id: 'agent', title: 'Second chat' },
    ],
    unread: new Set(), view: 'list', activeChatId: 'one',
    openChat: vi.fn(), remove: vi.fn(),
  },
  teams: { members: [], memberById: new Map() },
}))
vi.mock('@/stores/chat', () => ({ useChatStore: () => chat }))
vi.mock('@/stores/teams', () => ({ useTeamsStore: () => teams }))
vi.mock('@/components/teams/MemberMark.vue', () => ({ default: { template: '<span />' } }))
vi.mock('@/i18n', () => ({ t: (key: string) => key }))

let app: App
let host: HTMLElement
const row = () => host.querySelectorAll('li button')[1] as HTMLButtonElement
const menu = () => document.querySelector<HTMLElement>('[role="menu"]')
const item = () => document.querySelector<HTMLButtonElement>('[role="menuitem"]')!
async function open() {
  row().dispatchEvent(new MouseEvent('contextmenu', { bubbles: true, cancelable: true, clientX: 100, clientY: 100 }))
  await nextTick()
}
beforeEach(() => {
  vi.clearAllMocks()
  host = document.createElement('div')
  document.body.append(host)
  app = createApp(ChatList)
  app.component('RouterLink', { template: '<a><slot /></a>' })
  app.mount(host)
})
afterEach(() => {
  app.unmount()
  host.remove()
  vi.restoreAllMocks()
})

describe('chat row context menu', () => {
  it('does not open/select the right-clicked chat and deletes that row directly', async () => {
    await open()
    expect(chat.openChat).not.toHaveBeenCalled()
    expect(menu()?.getAttribute('aria-label')).toBe('Second chat')
    expect(document.activeElement).toBe(item())
    item().click()
    await nextTick()
    expect(chat.remove).toHaveBeenCalledExactlyOnceWith('two')
    expect(menu()).toBeNull()
  })

  it('opens from the keyboard and restores focus on Escape', async () => {
    row().focus()
    row().dispatchEvent(new KeyboardEvent('keydown', { key: 'F10', shiftKey: true, bubbles: true, cancelable: true }))
    await nextTick()
    expect(menu()).not.toBeNull()
    item().dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown', bubbles: true, cancelable: true }))
    expect(document.activeElement).toBe(item())
    item().dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }))
    await nextTick()
    expect(menu()).toBeNull()
    expect(document.activeElement).toBe(row())
    expect(chat.openChat).not.toHaveBeenCalled()
    expect(chat.remove).not.toHaveBeenCalled()
  })

  it('dismisses on outside pointer, scroll and resize', async () => {
    await open()
    menu()!.previousElementSibling!.dispatchEvent(new Event('pointerdown', { bubbles: true }))
    await nextTick()
    expect(menu()).toBeNull()
    await open()
    host.dispatchEvent(new Event('scroll'))
    await nextTick()
    expect(menu()).toBeNull()
    await open()
    window.dispatchEvent(new Event('resize'))
    await nextTick()
    expect(menu()).toBeNull()
    expect(chat.remove).not.toHaveBeenCalled()
  })

  it('clamps the measured menu to the viewport', async () => {
    vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockReturnValue({ width: 176, height: 40 } as DOMRect)
    row().dispatchEvent(new MouseEvent('contextmenu', {
      bubbles: true, clientX: window.innerWidth - 1, clientY: window.innerHeight - 1,
    }))
    await nextTick()
    await nextTick()
    expect(menu()!.style.left).toBe(`${window.innerWidth - 176 - 8}px`)
    expect(menu()!.style.top).toBe(`${window.innerHeight - 40 - 8}px`)
  })

  it('keeps normal left-click opening unchanged and removes the teleport on unmount', async () => {
    row().click()
    expect(chat.openChat).toHaveBeenCalledExactlyOnceWith('two')
    await open()
    app.unmount()
    expect(menu()).toBeNull()
    // Avoid a second unmount in cleanup.
    app = { unmount() {} } as App
  })
})
