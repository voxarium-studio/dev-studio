import { describe, expect, it } from 'vitest'

import {
  DEFAULT_EFFORT_SOLVER_WEIGHTS, effortSolverResult, spawnEffortSolver, validEffortSolverWeights,
} from './effort-solver'

describe('Effort Solver input and result validation', () => {
  it('accepts default weights and zeroing individual criteria', () => {
    expect(validEffortSolverWeights(DEFAULT_EFFORT_SOLVER_WEIGHTS)).toBe(true)
    expect(validEffortSolverWeights({ complexity: 0, creativity: 0, depth: 0, coverage: 1 })).toBe(true)
  })

  it.each([-1, NaN, Infinity, ''])('refuses invalid weight %s', depth => {
    expect(validEffortSolverWeights({ ...DEFAULT_EFFORT_SOLVER_WEIGHTS, depth: depth as number })).toBe(false)
  })

  it('refuses a zero total', () => {
    expect(validEffortSolverWeights({ complexity: 0, creativity: 0, depth: 0, coverage: 0 })).toBe(false)
  })

  it.each([0, 11, 1.5, '8', true])('does not interpret invalid score %s as a valid result', depth => {
    expect(effortSolverResult({ scores: { complexity: 9, creativity: 6, depth, coverage: 8 }, effort: 4 })).toBeNull()
  })
})

describe('a spawn_subagent result (§10.3)', () => {
  const scores = { complexity: 2, creativity: 2, depth: 3, coverage: 3 }

  it('reads the evaluation the spawn answered with beside the subagent row', () => {
    const text = JSON.stringify({ id: 'subagent_1', effort: 'medium',
      effort_solver: { scores, effort: 2, effort_level: 'medium' } })
    expect(spawnEffortSolver(text)).toEqual({ scores, effort: 2 })
  })

  it.each([
    ['nothing', undefined],
    ['an error', 'ValueError: Backend codex is unavailable'],
    ['a clipped answer', JSON.stringify({ effort_solver: { scores, effort: 2 } }).slice(0, 40)],
    ['a row from before the scores', JSON.stringify({ id: 'subagent_1', effort: 'medium' })],
    ['invalid scores', JSON.stringify({ effort_solver: { scores: { ...scores, depth: 11 }, effort: 2 } })],
    ['a list', '[]'],
  ])('draws no evaluation from %s', (_case, text) => {
    expect(spawnEffortSolver(text)).toBeNull()
  })
})
