import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import type { AgentChat, AgentChatMessage, ChatCreate } from '@/api/types'
import { ApiError, api } from '@/api/client'

import { NEW_DRAFT_KEY, draftKey, readPopupState, useChatStore } from './chat'

type Handler = (data: unknown) => void
const handlers = new Map<string, Handler>()

vi.mock('@/ws/connection', () => ({
  onEvent: (type: string, handler: Handler) => {
    handlers.set(type, handler)
    return () => handlers.delete(type)
  },
}))

const calls: { name: string; args: unknown[] }[] = []
let nextChatId = 1

function chatRow(overrides: Partial<AgentChat> = {}): AgentChat {
  return {
    id: `chat_${nextChatId++}`,
    member_id: 'team_default__dev',
    title: '',
    agent_session_id: null,
    error: null,
    total_cost_usd: 0,
    num_turns: 0,
    total_tokens: 0,
    created_at: '2026-09-16T10:00:00',
    last_message_at: '2026-09-16T10:00:00',
    is_running: false,
    ...overrides,
  }
}

function messageRow(chatId: string, overrides: Partial<AgentChatMessage> = {}): AgentChatMessage {
  return {
    id: `cmsg_${Math.random().toString(16).slice(2)}`,
    chat_id: chatId,
    member_id: 'team_default__dev',
    role: 'assistant',
    kind: 'text',
    text: 'hello',
    data: {},
    created_at: '2026-09-16T10:00:01',
    ...overrides,
  }
}

let listed: AgentChat[] = []

vi.mock('@/api/client', () => {
  class ApiError extends Error {
    status: number
    constructor(status: number, detail: string) {
      super(detail)
      this.status = status
    }
  }
  return {
    ApiError,
    api: {
      chats: async () => {
        calls.push({ name: 'chats', args: [] })
        return listed
      },
      chat: async (id: string) => {
        calls.push({ name: 'chat', args: [id] })
        const chat = listed.find((entry) => entry.id === id)
        if (!chat) throw new ApiError(404, 'no such chat')
        return { ...chat, messages: [messageRow(id, { id: 'cmsg_first', role: 'user' })] }
      },
      createChat: async (body: ChatCreate) => {
        calls.push({ name: 'createChat', args: [body] })
        const chat = chatRow({ member_id: body.member_id, ...(body.producer_mode ? {
          producer_mode: true, producer_workspace: true, producer_state: 'discussing' as const,
        } : {}) })
        listed = [...listed, chat]
        return chat
      },
      useProducerWorkspace: async (id: string, locale: string) => {
        calls.push({ name: 'useProducerWorkspace', args: [id, locale] })
        const chat = { ...listed.find(entry => entry.id === id)!, producer_mode: true, producer_workspace: true }
        listed = listed.map(entry => entry.id === id ? chat : entry)
        return chat
      },
      sendChatMessage: async (id: string, text: string, context: unknown) => {
        calls.push({ name: 'sendChatMessage', args: [id, text, context] })
        if (text === 'busy') throw new ApiError(409, 'still answering')
        const chat = { ...listed.find((entry) => entry.id === id)!, is_running: true, title: text }
        return { chat, message: messageRow(id, { role: 'user', text, data: { context } }) }
      },
      deleteChat: async (id: string) => {
        calls.push({ name: 'deleteChat', args: [id] })
        listed = listed.filter((entry) => entry.id !== id)
        return { chat_id: id, deleted: true }
      },
      cancelChat: async (id: string) => ({ chat_id: id, cancelled: true }),
      startProducer: async (id: string, locale: string, policy?: AgentChat['producer_policy'], estimateTaskCostUsd?: number, effortMode?: AgentChat['producer_effort_mode']) => {
        calls.push({ name: 'startProducer', args: effortMode !== undefined ? [id, locale, policy, estimateTaskCostUsd, effortMode] : estimateTaskCostUsd === undefined ? [id, locale, policy] : [id, locale, policy, estimateTaskCostUsd] })
        const row = listed.find(entry => entry.id === id)!
        return { ...row, estimate_task_cost_usd: estimateTaskCostUsd ?? row.estimate_task_cost_usd ?? 150, producer_effort_mode: effortMode ?? row.producer_effort_mode ?? 'agent', producer_policy: policy ?? 'careful', producer_mode: true, producer_state: 'running', is_running: true }
      },
      setProducerSettings: async (id: string, estimateTaskCostUsd: number) => {
        calls.push({ name: 'setProducerSettings', args: [id, estimateTaskCostUsd] })
        const row = listed.find(entry => entry.id === id)!
        if (row.is_running) throw new ApiError(409, 'Producer is answering')
        return { ...row, estimate_task_cost_usd: estimateTaskCostUsd }
      },
      setProducerPolicy: async (id: string, policy: AgentChat['producer_policy']) => {
        calls.push({ name: 'setProducerPolicy', args: [id, policy] })
        const row = listed.find(entry => entry.id === id)!
        if (row.is_running) throw new ApiError(409, 'Producer is answering')
        return { ...row, producer_policy: policy }
      },
      setProducerEffortMode: async (id: string, mode: AgentChat['producer_effort_mode']) => {
        calls.push({ name: 'setProducerEffortMode', args: [id, mode] })
        const row = listed.find(entry => entry.id === id)!
        if (row.is_running) throw new ApiError(409, 'Producer is answering')
        return { ...row, producer_effort_mode: mode }
      },
      stopProducer: async (id: string) => {
        calls.push({ name: 'stopProducer', args: [id] })
        return { ...listed.find(entry => entry.id === id)!, producer_mode: false, producer_state: 'off', is_running: false }
      },
      approveProducerPlan: async (id: string, version: number) => {
        calls.push({ name: 'approveProducerPlan', args: [id, version] })
        if (version === 0) throw new ApiError(409, 'Plan changed')
        return { ...listed.find(entry => entry.id === id)!, producer_mode: true, producer_plan: [], producer_state: 'running', is_running: true }
      },
      pauseProducer: async (id: string) => {
        calls.push({ name: 'pauseProducer', args: [id] })
        return { ...listed.find(entry => entry.id === id)!, producer_paused: true, is_running: false }
      },
      resumeProducer: async (id: string) => {
        calls.push({ name: 'resumeProducer', args: [id] })
        return { ...listed.find(entry => entry.id === id)!, producer_paused: false, is_running: true }
      },
    },
  }
})

/** A `localStorage` in memory, so the browser half of the store can be exercised here. */
function installStorage(): Map<string, string> {
  const backing = new Map<string, string>()
  const storage = {
    getItem: (key: string) => backing.get(key) ?? null,
    setItem: (key: string, value: string) => void backing.set(key, String(value)),
    removeItem: (key: string) => void backing.delete(key),
  }
  vi.stubGlobal('window', { localStorage: storage })
  return backing
}

const CONTEXT = { page: 'Tasks', route: '/tasks', title: '', document: null, file: null, selection: null }

beforeEach(() => {
  setActivePinia(createPinia())
  handlers.clear()
  calls.length = 0
  listed = []
  nextChatId = 1
})

describe('readPopupState', () => {
  it('is closed on the list when nothing or nonsense was stored', () => {
    expect(readPopupState(null)).toEqual({ open: false, view: 'list', chatId: null, memberId: null })
    expect(readPopupState('{not json')).toEqual({ open: false, view: 'list', chatId: null, memberId: null })
    expect(readPopupState('{"view":"elsewhere","open":"yes","chatId":7}')).toEqual({
      open: false,
      view: 'list',
      chatId: null,
      memberId: null,
    })
  })

  it('brings back what was stored', () => {
    expect(
      readPopupState('{"open":true,"view":"chat","chatId":"chat_9","memberId":"m1"}'),
    ).toEqual({ open: true, view: 'chat', chatId: 'chat_9', memberId: 'm1' })
  })
})

describe('draftKey', () => {
  it('is one key per chat, one for the chat not yet opened, and none for the list', () => {
    expect(draftKey('new', null)).toBe(NEW_DRAFT_KEY)
    expect(draftKey('chat', 'chat_1')).toBe('voxarium_studio.chat.draft.chat_1')
    expect(draftKey('chat', null)).toBeNull()
    expect(draftKey('list', 'chat_1')).toBeNull()
  })
})

describe('the chat store', () => {
  it('adopts an old Producer chat once without dropping its plan', async () => {
    installStorage()
    listed = [chatRow({ id: 'producer', producer_plan_summary: 'Keep this plan' })]
    const store = useChatStore()
    await store.load()
    await store.openChat('producer')
    expect(await store.ensureProducerChat('producer')).toBe(true)
    expect(await store.ensureProducerChat('producer')).toBe(true)
    expect(store.activeChat).toMatchObject({ producer_mode: true, producer_workspace: true,
      producer_plan_summary: 'Keep this plan' })
    expect(calls.filter(call => call.name === 'useProducerWorkspace')).toEqual([
      { name: 'useProducerWorkspace', args: ['producer', 'en'] },
    ])
  })

  it('creates an enabled idle Producer workspace chat without a separate start request', async () => {
    installStorage()
    const store = useChatStore()
    const id = await store.createProducerChat('team_default__studio-producer')
    expect(id).toBe(store.activeChatId)
    expect(store.view).toBe('chat')
    expect(store.activeChat).toMatchObject({ producer_mode: true, producer_workspace: true,
      producer_state: 'discussing', is_running: false })
    expect(calls).toEqual([{ name: 'createChat', args: [{ member_id: 'team_default__studio-producer',
      producer_mode: true, locale: 'en' }] }])
    expect(store.busy).toBe(false)
  })

  it('automatically enables a Producer draft before sending its first message', async () => {
    installStorage()
    const store = useChatStore()
    store.startNew('team_default__studio-producer')
    expect(await store.send('Make a game', CONTEXT, [], true)).toBe(true)
    expect(calls[0]).toEqual({ name: 'createChat', args: [{ member_id: 'team_default__studio-producer',
      producer_mode: true, locale: 'en' }] })
    expect(calls[1]?.name).toBe('sendChatMessage')
    expect(store.activeChat?.producer_workspace).toBe(true)
  })

  it.each(['running', 'paused'])('works in a second Producer chat while the first is %s', async state => {
    installStorage()
    const first = chatRow({ member_id: 'team_default__studio-producer', producer_mode: true,
      producer_workspace: true, producer_started: true, producer_paused: state === 'paused',
      is_running: state === 'running', producer_plan_summary: 'First plan' })
    listed = [first]
    const store = useChatStore()
    await store.load()
    await store.openChat(first.id)
    const second = await store.createProducerChat(first.member_id)
    expect(second).not.toBe(first.id)
    expect(await store.send('Second goal', CONTEXT, [], true)).toBe(true)

    handlers.get('team.chat.message')!(messageRow(first.id, { text: 'First reply' }))
    handlers.get('team.chat.updated')!({ ...first, is_running: false })
    expect(store.activeChat).toMatchObject({ id: second, is_running: true })
    expect(store.activeMessages.map(message => message.text)).toEqual(['Second goal'])
    expect(store.messages[first.id]?.at(-1)?.text).toBe('First reply')
    expect(store.chats.find(chat => chat.id === first.id)).toMatchObject({
      producer_paused: state === 'paused', producer_plan_summary: 'First plan',
    })
    expect(calls.map(call => call.name)).toEqual(['chats', 'chat', 'createChat', 'sendChatMessage'])
  })

  it('adopts an existing off chat before its next Producer page message', async () => {
    installStorage()
    listed = [chatRow({ id: 'old-producer', producer_mode: false })]
    const store = useChatStore()
    await store.load()
    await store.openChat('old-producer')
    expect(await store.send('Continue', CONTEXT, [], true)).toBe(true)
    expect(calls.slice(-2).map(call => call.name)).toEqual(['useProducerWorkspace', 'sendChatMessage'])
    expect(store.activeChat).toMatchObject({ producer_workspace: true, producer_mode: true })
  })

  it('sends the first message to its new chat even if another chat is opened during creation', async () => {
    installStorage()
    const other = chatRow()
    const created = chatRow({ member_id: 'producer', producer_mode: true, producer_workspace: true })
    listed = [other]
    const store = useChatStore()
    await store.load()
    store.startNew(created.member_id)
    let finish!: (row: AgentChat) => void
    const create = vi.spyOn(api, 'createChat').mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
    try {
      const first = store.send('Original goal', CONTEXT, [], true)
      await store.openChat(other.id)
      store.setSelection('Selected in the other chat')
      listed.push(created)
      finish(created)
      expect(await first).toBe(true)
      expect(store.activeChatId).toBe(other.id)
      expect(store.selection).toBe('Selected in the other chat')
      expect(store.messages[created.id]?.map(row => row.text)).toEqual(['Original goal'])
      expect(store.activeMessages.some(row => row.text === 'Original goal')).toBe(false)
    } finally { create.mockRestore() }
  })

  it('lets a second chat send while Pause is pending and keeps a late error out of that chat', async () => {
    installStorage()
    const first = chatRow({ producer_mode: true, producer_started: true })
    listed = [first]
    const store = useChatStore()
    await store.load()
    await store.openChat(first.id)
    let fail!: (error: Error) => void
    const pause = vi.spyOn(api, 'pauseProducer').mockImplementationOnce(() => new Promise((_resolve, reject) => { fail = reject }))
    try {
      const pausing = store.producerAction('pause')
      expect(store.busy).toBe(true)
      const second = await store.createProducerChat(first.member_id)
      expect(store.busy).toBe(false)
      expect(await store.send('Second goal', CONTEXT, [], true)).toBe(true)
      fail(new Error('Pause failed in first chat'))
      await pausing
      expect(store.error).toBeNull()
      expect(store.activeChat).toMatchObject({ id: second, is_running: true })
      expect(pause).toHaveBeenCalledWith(first.id)
      await store.openChat(first.id)
      expect(store.error).toBe('Pause failed in first chat')
    } finally { pause.mockRestore() }
  })

  it('does not resurrect a deleted chat when its pending pause completes', async () => {
    installStorage()
    const first = chatRow({ producer_mode: true, producer_started: true })
    const second = chatRow()
    listed = [first, second]
    const store = useChatStore()
    await store.load()
    await store.openChat(first.id)
    let finish!: (row: AgentChat) => void
    const pause = vi.spyOn(api, 'pauseProducer').mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
    try {
      const pausing = store.producerAction('pause')
      await store.openChat(second.id)
      handlers.get('team.chat.deleted')!({ chat_id: first.id })
      finish({ ...first, producer_paused: true })
      await pausing
      expect(store.chats.map(row => row.id)).toEqual([second.id])
      expect(store.activeChatId).toBe(second.id)
    } finally { pause.mockRestore() }
  })

  it('keeps the live status and reply received while the selected chat is opening', async () => {
    installStorage()
    const row = chatRow({ producer_mode: true, is_running: true })
    listed = [row]
    const store = useChatStore()
    await store.load()
    let finish!: (detail: AgentChat & { messages: AgentChatMessage[] }) => void
    const fetch = vi.spyOn(api, 'chat').mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
    try {
      const opening = store.openChat(row.id)
      handlers.get('team.chat.message')!(messageRow(row.id, { text: 'Latest reply' }))
      handlers.get('team.chat.updated')!({ ...row, producer_paused: true, is_running: false })
      finish({ ...row, messages: [] })
      await opening
      expect(store.activeChat).toMatchObject({ producer_paused: true, is_running: false })
      expect(store.activeMessages.map(message => message.text)).toEqual(['Latest reply'])
    } finally { fetch.mockRestore() }
  })

  it('does not resurrect a deleted chat when its earlier transcript fetch completes', async () => {
    installStorage()
    const row = chatRow({ id: 'producer', producer_workspace: true })
    listed = [row]
    const store = useChatStore()
    await store.load()
    let finish!: (value: AgentChat & { messages: AgentChatMessage[] }) => void
    const fetch = vi.spyOn(api, 'chat').mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
    try {
      const pending = store.openChat(row.id)
      expect(await store.remove(row.id)).toBe(true)
      finish({ ...row, messages: [messageRow(row.id)] })
      await pending
      expect(store.chats).toEqual([])
      expect(store.messages[row.id]).toBeUndefined()
      expect(store.activeChatId).toBeNull()
    } finally { fetch.mockRestore() }
  })

  it('keeps the newer selected chat when an earlier transcript fetch reports deletion', async () => {
    installStorage()
    listed = [chatRow({ id: 'old' }), chatRow({ id: 'new' })]
    const store = useChatStore()
    await store.load()
    let fail!: (cause: Error) => void
    const fetch = vi.spyOn(api, 'chat').mockImplementationOnce(() => new Promise((_resolve, reject) => { fail = reject }))
    try {
      const pending = store.openChat('old')
      await store.openChat('new')
      fail(new ApiError(404, 'Deleted'))
      await pending
      expect(store.activeChatId).toBe('new')
      expect(store.view).toBe('chat')
      expect(store.error).toBeNull()
    } finally { fetch.mockRestore() }
  })

  it('keeps the current chat selected when creating a Producer chat fails', async () => {
    installStorage()
    listed = [chatRow({ id: 'current' })]
    const store = useChatStore()
    await store.load()
    await store.openChat('current')
    const create = vi.spyOn(api, 'createChat').mockRejectedValueOnce(new Error('MCP disabled'))
    try {
      expect(await store.createProducerChat('team_default__studio-producer')).toBeNull()
      expect(store.activeChatId).toBe('current')
      expect(store.error).toBe('MCP disabled')
      expect(store.busy).toBe(false)
    } finally { create.mockRestore() }
  })

  it('deletes a running Producer chat through the atomic server deletion and preserves a newer selection', async () => {
    installStorage()
    listed = [chatRow({ id: 'producer', producer_mode: true, producer_workspace: true, is_running: true }),
      chatRow({ id: 'other' })]
    const store = useChatStore()
    await store.load()
    await store.openChat('producer')
    let complete!: (value: { chat_id: string; deleted: boolean }) => void
    const deletion = vi.spyOn(api, 'deleteChat').mockImplementationOnce(() => new Promise(resolve => { complete = resolve }))
    try {
      const pending = store.remove('producer')
      await store.openChat('other')
      complete({ chat_id: 'producer', deleted: true })
      expect(await pending).toBe(true)
      expect(store.activeChatId).toBe('other')
      expect(store.chats.map(chat => chat.id)).toEqual(['other'])
      expect(calls.some(call => call.name === 'stopProducer')).toBe(false)
    } finally { deletion.mockRestore() }
  })

  it('deletes an unselected chat while a different chat has a pending action', async () => {
    installStorage()
    listed = [chatRow({ id: 'current' }), chatRow({ id: 'other' })]
    const store = useChatStore()
    await store.load()
    await store.openChat('current')
    let complete!: (value: { chat_id: string; deleted: boolean }) => void
    const deletion = vi.spyOn(api, 'deleteChat').mockImplementationOnce(() => new Promise(resolve => { complete = resolve }))
    try {
      const pending = store.remove('current')
      expect(store.busy).toBe(true)
      expect(await store.remove('other')).toBe(true)
      expect(store.activeChatId).toBe('current')
      expect(store.chats.map(chat => chat.id)).toEqual(['current'])
      expect(deletion).toHaveBeenCalledWith('other')
      complete({ chat_id: 'current', deleted: true })
      expect(await pending).toBe(true)
      expect(store.view).toBe('list')
    } finally { deletion.mockRestore() }
  })

  it('keeps a Producer chat and reports the error when deletion fails', async () => {
    installStorage()
    listed = [chatRow({ id: 'producer', producer_mode: true })]
    const store = useChatStore()
    await store.load()
    await store.openChat('producer')
    const deletion = vi.spyOn(api, 'deleteChat').mockRejectedValueOnce(new Error('Offline'))
    try {
      expect(await store.remove('producer')).toBe(false)
      expect(store.activeChatId).toBe('producer')
      expect(store.error).toBe('Offline')
      expect(store.busy).toBe(false)
    } finally { deletion.mockRestore() }
  })

  it('shares an in-flight load until both the chat list and restored transcript are ready', async () => {
    const backing = installStorage()
    backing.set('voxarium_studio.chat.state', '{"open":false,"view":"chat","chatId":"producer","memberId":null}')
    const row = chatRow({ id: 'producer' })
    let finishList!: (value: AgentChat[]) => void
    let finishTranscript!: (value: AgentChat & { messages: AgentChatMessage[] }) => void
    const listFetch = vi.spyOn(api, 'chats').mockImplementationOnce(() => new Promise(resolve => { finishList = resolve }))
    const detailFetch = vi.spyOn(api, 'chat').mockImplementationOnce(() => new Promise(resolve => { finishTranscript = resolve }))
    try {
      const store = useChatStore()
      const first = store.load()
      let secondFinished = false
      const second = store.load().then(() => { secondFinished = true })
      await Promise.resolve()
      await Promise.resolve()
      expect(secondFinished).toBe(false)
      expect(listFetch).toHaveBeenCalledTimes(1)
      finishList([row])
      await Promise.resolve()
      await Promise.resolve()
      expect(detailFetch).toHaveBeenCalledTimes(1)
      expect(secondFinished).toBe(false)
      expect(store.loading).toBe(true)
      const message = messageRow(row.id)
      finishTranscript({ ...row, messages: [message] })
      await Promise.all([first, second])
      expect(secondFinished).toBe(true)
      expect(store.activeMessages).toEqual([message])
      expect(store.loading).toBe(false)
      listed = [row]
      await store.load()
      expect(listFetch).toHaveBeenCalledTimes(2)
    } finally {
      listFetch.mockRestore()
      detailFetch.mockRestore()
    }
  })

  it('allows a fresh load after a shared request fails', async () => {
    installStorage()
    const fetch = vi.spyOn(api, 'chats').mockRejectedValueOnce(new Error('Offline'))
    try {
      const store = useChatStore()
      await Promise.all([store.load(), store.load()])
      expect(fetch).toHaveBeenCalledTimes(1)
      expect(store.error).toBe('Offline')
      listed = [chatRow()]
      await store.load()
      expect(fetch).toHaveBeenCalledTimes(2)
      expect(store.error).toBeNull()
      expect(store.chats).toEqual(listed)
    } finally {
      fetch.mockRestore()
    }
  })

  it('replaces Producer history with one summary and rejects old socket replay', async () => {
    installStorage()
    listed = [chatRow()]
    const store = useChatStore()
    await store.load()
    const chat = listed[0]!
    await store.openChat(chat.id)
    const emit = (message: AgentChatMessage) => handlers.get('team.chat.message')?.(message)
    const old = store.activeMessages[0]!
    const summary = messageRow(chat.id, {
      id: 'summary1', role: 'system', kind: 'producer_compacted', text: 'First summary',
      data: { replaces_history: true }, created_at: '2026-09-16T10:00:02.000100Z',
    })
    emit(summary)
    expect(store.activeMessages).toEqual([summary])
    const reply = messageRow(chat.id, { id: 'reply', created_at: '2026-09-16T10:00:02.000200+00:00' })
    emit(reply)
    emit(old)
    emit(summary)
    expect(store.activeMessages).toEqual([summary, reply])
    const next = { ...summary, id: 'summary2', text: 'Next summary', created_at: reply.created_at }
    emit(next)
    emit(summary)
    emit(reply)
    expect(store.activeMessages).toEqual([next])
  })

  it('does not resurrect history from a stale fetch or a cached transcript', async () => {
    installStorage()
    listed = [chatRow()]
    const store = useChatStore()
    await store.load()
    const chat = listed[0]!
    const old = [messageRow(chat.id, { id: 'old', role: 'user' })]
    const summary = messageRow(chat.id, {
      id: 'summary', role: 'system', kind: 'producer_compacted', text: 'Summary',
      data: { replaces_history: true }, created_at: '2026-09-16T10:00:02',
    })
    let finishFetch!: (value: AgentChat & { messages: AgentChatMessage[] }) => void
    const fetch = vi.spyOn(api, 'chat').mockImplementationOnce(() => new Promise(resolve => { finishFetch = resolve }))
    const opening = store.openChat(chat.id)
    handlers.get('team.chat.message')?.(summary)
    const reply = messageRow(chat.id, { id: 'reply', created_at: '2026-09-16T10:00:03' })
    handlers.get('team.chat.message')?.(reply)
    finishFetch({ ...chat, messages: old })
    await opening
    expect(store.activeMessages).toEqual([summary, reply])

    // Missed compaction event: the next fetch must also drop pre-summary cached rows.
    store.messages[chat.id] = old
    fetch.mockResolvedValueOnce({ ...chat, messages: [summary, reply] })
    await store.openChat(chat.id)
    expect(store.activeMessages).toEqual([summary, reply])
    fetch.mockRestore()
  })

  it('sends the selected policy on Start and retains the server selection after a rejected change', async () => {
    installStorage()
    const store = useChatStore()
    await store.load()
    store.startNew('team_default__studio-producer')
    await store.producerAction('start', 'independent')
    expect(store.activeChat?.producer_policy).toBe('independent')
    expect(calls.find(call => call.name === 'startProducer')?.args[2]).toBe('independent')
    await store.setProducerPolicy('careful')
    expect(store.activeChat?.producer_policy).toBe('careful')
    listed[0]!.is_running = true
    await store.setProducerPolicy('independent')
    expect(store.activeChat?.producer_policy).toBe('careful')
    expect(store.error).toBe('Producer is answering')
    expect(store.busy).toBe(false)
  })

  it('starts Producer Mode from a new chat and shares its state between page and popup', async () => {
    installStorage()
    const store = useChatStore()
    await store.load()
    store.startNew('team_default__studio-producer')
    await store.producerAction('start')
    expect(calls.filter(call => call.name === 'createChat')).toHaveLength(1)
    expect(calls.find(call => call.name === 'startProducer')?.args[0]).toBe(store.activeChatId)
    expect(store.activeChat?.producer_mode).toBe(true)
    store.pageVisible = true
    store.hide()
    expect(store.activeChat?.producer_state).toBe('running')
    await store.producerAction('stop')
    expect(store.activeChat?.producer_mode).toBe(false)
    expect(store.activeChat?.is_running).toBe(false)
  })

  it('approves the displayed plan version and reports stale approvals without clearing it', async () => {
    installStorage()
    listed = [chatRow({ id: 'producer-chat', producer_mode: true, producer_plan_version: 0 })]
    const store = useChatStore()
    await store.load()
    await store.openChat('producer-chat')
    await store.producerAction('approve')
    expect(store.error).toBe('Plan changed')
    expect(store.activeChat?.producer_mode).toBe(true)
    handlers.get('team.chat.updated')!({ ...listed[0]!, producer_plan_version: 3 })
    await store.producerAction('approve')
    expect(calls.filter(call => call.name === 'approveProducerPlan').at(-1)?.args).toEqual(['producer-chat', 3])
    expect(store.error).toBeNull()
    expect(store.activeChat?.producer_state).toBe('running')
  })

  it('remembers the popup across a reload, and falls back when its chat is gone', async () => {
    const backing = installStorage()
    backing.set('voxarium_studio.chat.state', '{"open":true,"view":"chat","chatId":"chat_1","memberId":"m"}')
    listed = [chatRow()]
    const store = useChatStore()
    expect(store.open).toBe(true)
    expect(store.view).toBe('chat')
    await store.load()
    expect(calls.map((call) => call.name)).toEqual(['chats', 'chat'])
    expect(store.activeMessages.map((message) => message.id)).toEqual(['cmsg_first'])

    store.hide()
    store.showList()
    expect(JSON.parse(backing.get('voxarium_studio.chat.state')!)).toMatchObject({ open: false, view: 'list' })

    // A second store, whose remembered chat no longer exists, lands on the list.
    setActivePinia(createPinia())
    backing.set('voxarium_studio.chat.state', '{"open":true,"view":"chat","chatId":"chat_gone","memberId":null}')
    listed = []
    const again = useChatStore()
    await again.load()
    expect(again.view).toBe('list')
    expect(again.activeChatId).toBeNull()
  })

  it('opens the chat on the first message and appends what the socket brings', async () => {
    installStorage()
    const store = useChatStore()
    await store.load()
    store.show()
    store.startNew('team_default__dev')
    expect(await store.send('  Why is the dash slow?  ', CONTEXT)).toBe(true)
    expect(calls.map((call) => call.name)).toEqual(['chats', 'createChat', 'sendChatMessage'])
    expect(store.view).toBe('chat')
    const chatId = store.activeChatId!
    expect(store.activeChat?.is_running).toBe(true)
    expect(store.activeMessages.map((message) => message.text)).toEqual(['Why is the dash slow?'])

    // A reply arrives over the socket, once — a replay of the same id is not a second reply.
    const reply = messageRow(chatId, { id: 'cmsg_reply', text: 'Because…', created_at: '2026-09-16T10:00:05' })
    handlers.get('team.chat.message')!(reply)
    handlers.get('team.chat.message')!(reply)
    expect(store.activeMessages.map((message) => message.id)).toEqual([
      store.activeMessages[0]!.id,
      'cmsg_reply',
    ])
    expect(store.activeChat?.last_message_at).toBe('2026-09-16T10:00:05')
    // Seen, because the popup is open on this chat.
    expect(store.unreadCount).toBe(0)

    handlers.get('team.chat.updated')!({ ...store.activeChat!, is_running: false, num_turns: 1 })
    expect(store.activeChat?.is_running).toBe(false)
    expect(store.activeChat?.num_turns).toBe(1)
  })

  it('counts a reply as unread while the popup is not showing that chat', async () => {
    installStorage()
    listed = [chatRow({ id: 'chat_a' }), chatRow({ id: 'chat_b' })]
    const store = useChatStore()
    await store.load()
    store.show()
    await store.openChat('chat_a')
    handlers.get('team.chat.message')!(messageRow('chat_b', { id: 'r1' }))
    handlers.get('team.chat.message')!(messageRow('chat_b', { id: 'r2', kind: 'tool_use' }))
    handlers.get('team.chat.message')!(messageRow('chat_a', { id: 'r3' }))
    expect(store.unreadCount).toBe(1)
    expect(store.unread.has('chat_b')).toBe(true)
    store.hide()
    handlers.get('team.chat.message')!(messageRow('chat_a', { id: 'r4' }))
    expect(store.unreadCount).toBe(2)
    store.show()
    expect(store.unread.has('chat_a')).toBe(false)
    await store.openChat('chat_b')
    expect(store.unreadCount).toBe(0)
  })

  it('marks only the conversation visible on the Chats page as read without opening the popup', async () => {
    const backing = installStorage()
    listed = [chatRow({ id: 'chat_a' }), chatRow({ id: 'chat_b' })]
    const store = useChatStore()
    await store.load()
    await store.openChat('chat_a')
    handlers.get('team.chat.message')!(messageRow('chat_a'))
    expect(store.unread.has('chat_a')).toBe(true)

    store.pageVisible = true
    expect(store.unreadCount).toBe(0)
    handlers.get('team.chat.message')!(messageRow('chat_a'))
    handlers.get('team.chat.message')!(messageRow('chat_b'))
    expect([...store.unread]).toEqual(['chat_b'])
    expect(store.open).toBe(false)
    expect(JSON.parse(backing.get('voxarium_studio.chat.state')!).open).toBe(false)

    store.pageVisible = false
    handlers.get('team.chat.message')!(messageRow('chat_a'))
    expect(store.unreadCount).toBe(2)
    store.pageVisible = true
    await store.openChat('chat_b')
    expect(store.unreadCount).toBe(0)
    store.showList()
    handlers.get('team.chat.message')!(messageRow('chat_b'))
    expect(store.unread.has('chat_b')).toBe(true)
  })

  it('keeps the list newest first as activity arrives', async () => {
    installStorage()
    listed = [
      chatRow({ id: 'chat_old', last_message_at: '2026-09-16T09:00:00' }),
      chatRow({ id: 'chat_new', last_message_at: '2026-09-16T11:00:00' }),
    ]
    const store = useChatStore()
    await store.load()
    expect(store.chats.map((chat) => chat.id)).toEqual(['chat_new', 'chat_old'])
    handlers.get('team.chat.message')!(
      messageRow('chat_old', { id: 'late', created_at: '2026-09-16T12:00:00' }),
    )
    expect(store.chats.map((chat) => chat.id)).toEqual(['chat_old', 'chat_new'])
  })

  it('pauses a running Producer and resumes the same conversation without losing its plan', async () => {
    installStorage()
    listed = [chatRow({ id: 'producer', producer_mode: true, is_running: true,
      producer_state: 'running', producer_plan_summary: 'Keep this plan' })]
    const store = useChatStore()
    await store.load()
    await store.openChat('producer')
    await store.producerAction('pause')
    expect(store.activeChat?.producer_paused).toBe(true)
    expect(store.activeChat?.is_running).toBe(false)
    expect(store.activeChat?.producer_plan_summary).toBe('Keep this plan')
    await store.producerAction('resume')
    expect(store.activeChat?.producer_paused).toBe(false)
    expect(calls.filter(call => call.name.endsWith('Producer'))).toEqual([
      { name: 'pauseProducer', args: ['producer'] },
      { name: 'resumeProducer', args: ['producer'] },
    ])
    handlers.get('team.chat.updated')!({ ...store.activeChat!, producer_paused: true, is_running: false })
    expect(store.activeChat?.producer_paused).toBe(true)
  })

  it('starts a new Producer chat with the selected task estimate and uses the $150 default', async () => {
    installStorage()
    const store = useChatStore()
    await store.load()
    store.startNew('team_default__producer')
    await store.producerAction('start', 'independent', 150)
    expect(calls.find(call => call.name === 'startProducer')?.args).toEqual([store.activeChatId, 'en', 'independent', 150])
    expect(store.activeChat?.estimate_task_cost_usd).toBe(150)
  })

  it('saves task estimates to the selected chat, validates them and keeps server refusal visible', async () => {
    installStorage()
    listed = [chatRow({ id: 'producer', estimate_task_cost_usd: 150, producer_paused: true })]
    const store = useChatStore()
    await store.load()
    await store.openChat('producer')
    await store.setProducerEstimateTaskCost(125.5)
    expect(store.activeChat?.estimate_task_cost_usd).toBe(125.5)
    expect(calls.filter(call => call.name === 'setProducerSettings')).toEqual([
      { name: 'setProducerSettings', args: ['producer', 125.5] },
    ])
    for (const invalid of [0, -1, NaN, Infinity]) await store.setProducerEstimateTaskCost(invalid)
    expect(calls.filter(call => call.name === 'setProducerSettings')).toHaveLength(1)
    expect(store.error).toMatch(/greater than zero/)
    expect(store.activeChat?.estimate_task_cost_usd).toBe(125.5)
    listed[0]!.is_running = true
    await store.setProducerEstimateTaskCost(200)
    expect(store.error).toBe('Producer is answering')
    expect(store.activeChat?.estimate_task_cost_usd).toBe(125.5)
    expect(store.busy).toBe(false)
  })

  it('sends the chosen effort mode when starting a new Producer chat', async () => {
    installStorage()
    const store = useChatStore()
    await store.load()
    store.startNew('team_default__producer')
    await store.producerAction('start', 'careful', 150, 'auto')
    expect(calls.find(call => call.name === 'startProducer')?.args).toEqual([store.activeChatId, 'en', 'careful', 150, 'auto'])
    expect(store.activeChat?.producer_effort_mode).toBe('auto')
  })

  it('saves effort mode per conversation and keeps the old value on server refusal', async () => {
    installStorage()
    listed = [chatRow({ id: 'producer', producer_effort_mode: 'agent' }), chatRow({ id: 'other', producer_effort_mode: 'agent' })]
    const store = useChatStore()
    await store.load()
    await store.openChat('producer')
    await store.setProducerEffortMode('auto')
    expect(store.activeChat?.producer_effort_mode).toBe('auto')
    expect(store.chats.find(chat => chat.id === 'other')?.producer_effort_mode).toBe('agent')
    expect(calls.filter(call => call.name === 'setProducerEffortMode')).toEqual([
      { name: 'setProducerEffortMode', args: ['producer', 'auto'] },
    ])
    listed[0]!.is_running = true
    await store.setProducerEffortMode('agent')
    expect(store.error).toBe('Producer is answering')
    expect(store.activeChat?.producer_effort_mode).toBe('auto')
    expect(store.busy).toBe(false)
  })

  it('refuses an invalid estimate before creating a Producer chat', async () => {
    installStorage()
    const store = useChatStore()
    await store.load()
    store.startNew('team_default__producer')
    await store.producerAction('start', 'careful', 0)
    expect(calls.map(call => call.name)).toEqual(['chats'])
    expect(store.error).toMatch(/greater than zero/)
  })

  it('reports a member still answering instead of sending twice', async () => {
    installStorage()
    listed = [chatRow({ id: 'chat_1' })]
    const store = useChatStore()
    await store.load()
    await store.openChat('chat_1')
    expect(await store.send('busy', CONTEXT)).toBe(false)
    expect(store.error).toMatch(/still answering/)
  })

  it('forgets a chat deleted here or elsewhere', async () => {
    const backing = installStorage()
    listed = [chatRow({ id: 'chat_1' }), chatRow({ id: 'chat_2' })]
    backing.set('voxarium_studio.chat.draft.chat_1', 'half a thought')
    const store = useChatStore()
    await store.load()
    await store.openChat('chat_1')
    await store.remove('chat_1')
    expect(store.chats.map((chat) => chat.id)).toEqual(['chat_2'])
    expect(store.view).toBe('list')
    expect(backing.has('voxarium_studio.chat.draft.chat_1')).toBe(false)

    await store.openChat('chat_2')
    handlers.get('team.chat.deleted')!({ chat_id: 'chat_2', member_id: 'm' })
    expect(store.chats).toEqual([])
    expect(store.activeChatId).toBeNull()
  })
})
