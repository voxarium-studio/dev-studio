import type { EffortSolverWeights } from '@/api/types'

export const EFFORT_SOLVER_DIMENSIONS = ['complexity', 'creativity', 'depth', 'coverage'] as const
export const DEFAULT_EFFORT_SOLVER_WEIGHTS: Readonly<EffortSolverWeights> = {
  complexity: 1.5, creativity: 1.5, depth: 3, coverage: 2.5,
}

export function validEffortSolverWeights(weights: EffortSolverWeights): boolean {
  const values = EFFORT_SOLVER_DIMENSIONS.map(key => weights[key])
  const total = values.reduce((sum, value) => sum + value, 0)
  return values.every(value => typeof value === 'number' && Number.isFinite(value) && value >= 0)
    && Number.isFinite(total) && total > 0
}

/** Only the validated scores and result enter the Producer dialogue (§10.11). */
export function effortSolverResult(data: Record<string, unknown>): {
  scores: EffortSolverWeights; effort: 1 | 2 | 3 | 4 | 5
} | null {
  if (!data.scores || typeof data.scores !== 'object') return null
  const scores = data.scores as EffortSolverWeights
  if (!EFFORT_SOLVER_DIMENSIONS.every(key => Number.isInteger(scores[key]) && scores[key] >= 1 && scores[key] <= 10)) return null
  if (typeof data.effort !== 'number' || !Number.isInteger(data.effort) || data.effort < 1 || data.effort > 5) return null
  return { scores, effort: data.effort as 1 | 2 | 3 | 4 | 5 }
}

/**
 * The evaluation `spawn_subagent` answered with (§10.3): its result is the subagent row as
 * JSON with `effort_solver` beside it. A result that is an error, clipped, or written before
 * the scores were returned has none, and the call row then draws no evaluation.
 */
export function spawnEffortSolver(resultText: string | undefined): ReturnType<typeof effortSolverResult> {
  if (!resultText) return null
  try {
    const parsed: unknown = JSON.parse(resultText)
    if (!parsed || typeof parsed !== 'object') return null
    const solver = (parsed as Record<string, unknown>).effort_solver
    return solver && typeof solver === 'object' ? effortSolverResult(solver as Record<string, unknown>) : null
  } catch {
    return null
  }
}
