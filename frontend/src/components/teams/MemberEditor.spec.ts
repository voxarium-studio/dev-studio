import { createRenderer, h, nextTick, reactive, ssrContextKey } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import type { EffortSolverWeights, MemberKind, MemberUpdate, TeamMember } from '@/api/types'
import MemberEditor from './MemberEditor.vue'

const mocks = vi.hoisted(() => ({ saveMember: vi.fn() }))

vi.mock('@/stores/teams', () => ({ useTeamsStore: () => ({
  options: { common_tools: [] },
  members: [],
  saveMember: mocks.saveMember,
  managerCandidates: () => [],
  tasksFor: () => [],
}) }))
vi.mock('@/stores/skills', () => ({ useSkillsStore: () => ({
  subscribe: () => {}, load: async () => {}, skills: [], overview: null,
}) }))
vi.mock('@/stores/session', () => ({ useSessionStore: () => ({ project: null }) }))
vi.mock('@/i18n', () => ({ t: (key: string) => key }))

interface HostNode {
  children: HostNode[]
  parent: HostNode | null
}

const node = (): HostNode => ({ children: [], parent: null })
const renderer = createRenderer<HostNode, HostNode>({
  createElement: node,
  createText: node,
  createComment: node,
  setText: () => {},
  setElementText: () => {},
  parentNode: target => target.parent,
  nextSibling: target => target.parent?.children[target.parent.children.indexOf(target) + 1] ?? null,
  insert(target, parent, anchor) {
    target.parent = parent
    const index = anchor ? parent.children.indexOf(anchor) : -1
    if (index < 0) parent.children.push(target)
    else parent.children.splice(index, 0, target)
  },
  remove(target) {
    if (target.parent) target.parent.children = target.parent.children.filter(child => child !== target)
  },
  patchProp: () => {},
})

function member(): TeamMember {
  return {
    id: 'team__artist', team_id: 'team', name: 'Artist', role_title: 'Voxel Artist',
    kind: 'agent', manager_id: null, responsibility: '', can_assign_to: [], tags: [],
    agent_backend: 'codex', agent: null, model: 'model', effort: null, allowed_tools: [],
    skills: [], run_settings_override: false, max_turns: 40, permission_mode: 'bypassPermissions',
    isolate_worktree: false, solo_mode: true, avatar_color: null, avatar_icon: null,
    pos_x: null, pos_y: null, sort_order: 0, created_at: '2026-11-05T00:00:00Z',
  }
}

interface EditorState {
  draft: { name: string; role_title: string; required_skills: string[]; required_documents: string[]; effort_solver_weights: EffortSolverWeights }
  kindDisabled: (kind: MemberKind) => boolean
  dirty: boolean
  saving: boolean
  failed: boolean
}

async function settle(): Promise<void> {
  for (let index = 0; index < 8; index += 1) {
    await Promise.resolve()
    await nextTick()
  }
}

async function mount(overrides: Partial<TeamMember> = {}) {
  const roster = reactive({ member: { ...member(), ...overrides } })
  // SFC types expose a constructor; at runtime this is the same options object with
  // only rendering suppressed so the real setup and lifecycle run without a DOM.
  const editor = { ...MemberEditor, render: () => null } as unknown as typeof MemberEditor
  const app = renderer.createApp({ render: () => h(editor, { member: roster.member }) })
  app.provide(ssrContextKey, {})
  app.mount(node())
  await settle()
  const state = (app._instance!.subTree.component as unknown as { setupState: EditorState }).setupState
  const persist = async (memberId: string, patch: MemberUpdate) => {
    expect(memberId).toBe(roster.member.id)
    roster.member = { ...roster.member, ...patch }
    // saveMember awaits refreshTeam; the real parent passes the refreshed row back as props.
    await nextTick()
    return true
  }
  mocks.saveMember.mockImplementation(persist)
  return { app, state, roster, persist }
}

describe('member editor autosave', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    mocks.saveMember.mockReset()
  })

  afterEach(() => {
    vi.clearAllTimers()
    vi.useRealTimers()
  })

  it('keeps Effort Solver an agent and persists its independent weight settings', async () => {
    const { app, state, roster } = await mount({ builtin: 'effort_solver' })
    expect(state.kindDisabled('human')).toBe(true)
    expect(state.kindDisabled('agent')).toBe(false)
    expect(state.draft.effort_solver_weights).toEqual({ complexity: 1.5, creativity: 1.5, depth: 3, coverage: 2.5 })
    state.draft.effort_solver_weights.depth = 4.5
    await nextTick()
    await vi.advanceTimersByTimeAsync(600)
    await settle()
    expect(mocks.saveMember).toHaveBeenCalledExactlyOnceWith('team__artist', {
      effort_solver_weights: { complexity: 1.5, creativity: 1.5, depth: 4.5, coverage: 2.5 },
    })
    expect(roster.member.effort_solver_weights?.depth).toBe(4.5)
    expect(state.dirty).toBe(false)
    app.unmount()
  })

  it('holds invalid weights until the user enters a valid set', async () => {
    const { app, state } = await mount({ builtin: 'effort_solver' })
    state.draft.effort_solver_weights = { complexity: 0, creativity: 0, depth: 0, coverage: 0 }
    await nextTick()
    await vi.advanceTimersByTimeAsync(600)
    expect(mocks.saveMember).not.toHaveBeenCalled()
    state.draft.effort_solver_weights.depth = 1
    await nextTick()
    await vi.advanceTimersByTimeAsync(600)
    await settle()
    expect(mocks.saveMember).toHaveBeenCalledExactlyOnceWith('team__artist', {
      effort_solver_weights: { complexity: 0, creativity: 0, depth: 1, coverage: 0 },
    })
    app.unmount()
  })

  it('never sends solver weights for an ordinary member', async () => {
    const { app, state } = await mount()
    state.draft.effort_solver_weights.depth = 9
    state.draft.name = 'Updated'
    await nextTick()
    await vi.advanceTimersByTimeAsync(600)
    await settle()
    expect(mocks.saveMember).toHaveBeenCalledExactlyOnceWith('team__artist', { name: 'Updated' })
    app.unmount()
  })

  it('persists a return to the old value while its earlier save is in flight', async () => {
    const { app, state, roster, persist } = await mount()
    let release!: () => void
    mocks.saveMember.mockImplementationOnce(async (memberId, patch) => {
      await new Promise<void>(resolve => { release = resolve })
      return persist(memberId, patch)
    })
    state.draft.name = 'New name'
    await nextTick()
    await vi.advanceTimersByTimeAsync(600)
    expect(mocks.saveMember).toHaveBeenCalledExactlyOnceWith('team__artist', { name: 'New name' })

    state.draft.name = 'Artist'
    await nextTick()
    release()
    await settle()
    expect(state.draft.name).toBe('Artist')
    expect(roster.member.name).toBe('New name')
    await vi.advanceTimersByTimeAsync(600)
    await settle()
    expect(mocks.saveMember).toHaveBeenCalledTimes(2)
    expect(mocks.saveMember).toHaveBeenLastCalledWith('team__artist', { name: 'Artist' })
    expect(roster.member.name).toBe('Artist')
    expect(state.dirty).toBe(false)
    expect(state.saving).toBe(false)
    app.unmount()
  })

  it('flushes the latest pending edit once when the editor closes', async () => {
    const { app, state, roster } = await mount()
    state.draft.role_title = 'First draft'
    await nextTick()
    await vi.advanceTimersByTimeAsync(300)
    state.draft.role_title = 'Final draft'
    await nextTick()
    expect(mocks.saveMember).not.toHaveBeenCalled()
    app.unmount()
    await settle()
    expect(mocks.saveMember).toHaveBeenCalledExactlyOnceWith('team__artist', { role_title: 'Final draft' })
    expect(roster.member.role_title).toBe('Final draft')
    await vi.advanceTimersByTimeAsync(3_000)
    expect(mocks.saveMember).toHaveBeenCalledTimes(1)
  })

  it('retains a failed draft without an automatic retry loop', async () => {
    const { app, state } = await mount()
    mocks.saveMember.mockResolvedValueOnce(false)
    state.draft.name = 'Unsaved name'
    await nextTick()
    await vi.advanceTimersByTimeAsync(600)
    await settle()
    expect(state.draft.name).toBe('Unsaved name')
    expect(state.failed).toBe(true)
    expect(state.saving).toBe(false)
    await vi.advanceTimersByTimeAsync(3_000)
    expect(mocks.saveMember).toHaveBeenCalledTimes(1)
    app.unmount()
  })

  it('keeps a reverted value when closed before its earlier save finishes', async () => {
    const { app, state, roster, persist } = await mount()
    let release!: () => void
    mocks.saveMember.mockImplementationOnce(async (memberId, patch) => {
      await new Promise<void>(resolve => { release = resolve })
      return persist(memberId, patch)
    })
    state.draft.name = 'Earlier save'
    await nextTick()
    await vi.advanceTimersByTimeAsync(600)
    state.draft.name = 'Artist'
    await nextTick()
    app.unmount()
    release()
    await settle()
    await vi.advanceTimersByTimeAsync(3_000)
    expect(roster.member.name).toBe('Artist')
    expect(mocks.saveMember).toHaveBeenCalledTimes(2)
    expect(mocks.saveMember).toHaveBeenLastCalledWith('team__artist', { name: 'Artist' })
  })

  it('saves required context independently from optional skills and preserves additions on refresh', async () => {
    const { app, state, roster } = await mount()
    state.draft.required_skills = ['shared-guide', 'artist-guide']
    state.draft.required_documents = ['design/art.md']
    await nextTick()
    await vi.advanceTimersByTimeAsync(600)
    await settle()
    expect(mocks.saveMember).toHaveBeenCalledExactlyOnceWith('team__artist', {
      required_skills: ['shared-guide', 'artist-guide'], required_documents: ['design/art.md'],
    })
    expect(roster.member.skills).toEqual([])
    expect(state.draft.required_skills).toEqual(['shared-guide', 'artist-guide'])
    expect(state.draft.required_documents).toEqual(['design/art.md'])
    expect(state.dirty).toBe(false)
    app.unmount()
  })

  it('persists removing a requirement while the add request is still in flight', async () => {
    const { app, state, roster, persist } = await mount()
    let release!: () => void
    mocks.saveMember.mockImplementationOnce(async (memberId, patch) => {
      await new Promise<void>(resolve => { release = resolve })
      return persist(memberId, patch)
    })
    state.draft.required_skills = ['artist-guide']
    await nextTick()
    await vi.advanceTimersByTimeAsync(600)
    state.draft.required_skills = []
    state.draft.required_documents = ['design/art.md']
    await nextTick()
    release()
    await settle()
    await vi.advanceTimersByTimeAsync(600)
    await settle()
    expect(roster.member.required_skills).toEqual([])
    expect(roster.member.required_documents).toEqual(['design/art.md'])
    expect(mocks.saveMember).toHaveBeenLastCalledWith('team__artist', {
      required_skills: [], required_documents: ['design/art.md'],
    })
    expect(state.dirty).toBe(false)
    app.unmount()
  })
})
