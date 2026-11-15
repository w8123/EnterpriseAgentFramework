import { describe, expect, it } from 'vitest'
import type { AiCodingTask } from '@/types/aiCodingTask'
import { getPageActiveTasks, getPageImplementationTask } from './pageWorkbenchGoals'

function task(id: string, taskKind: string, executionStatus = 'RUNNING', pageKey = 'mall.home') {
  return {
    taskId: id, taskKind, executionStatus, createdAt: `2026-11-11T0${id}:00:00`,
    targets: [{ targetRole: 'PRIMARY', targetType: 'PAGE', targetKey: pageKey }],
  } as AiCodingTask
}

describe('page goal task selection', () => {
  it('uses the newest implementation snapshot regardless of the current analysis/workflow task', () => {
    const older = task('1', 'CODE_IMPLEMENTATION', 'COMPLETED')
    const latest = task('2', 'CODE_IMPLEMENTATION', 'FAILED')
    expect(getPageImplementationTask([
      task('3', 'WORKFLOW_ENGINEERING'), older, latest,
      task('4', 'CODE_IMPLEMENTATION', 'RUNNING', 'mall.orders'),
    ], 'mall.home')).toBe(latest)
    expect(getPageImplementationTask([task('1', 'PAGE_READONLY_ANALYSIS')], 'mall.home')).toBeNull()
  })

  it('only includes unfinished primary-page activities in the restart confirmation', () => {
    const analysis = task('1', 'PAGE_READONLY_ANALYSIS')
    const workflow = task('2', 'WORKFLOW_ENGINEERING', 'WAITING_USER')
    const acceptance = task('3', 'BROWSER_ACCEPTANCE', 'ACCEPTANCE_READY')
    const related = task('4', 'CODE_IMPLEMENTATION')
    related.targets[0].targetRole = 'RELATED'
    expect(getPageActiveTasks([
      analysis, workflow, acceptance, related,
      task('5', 'CODE_IMPLEMENTATION', 'COMPLETED'),
      task('6', 'CODE_IMPLEMENTATION', 'FAILED'),
      task('7', 'CODE_IMPLEMENTATION', 'CANCELLED'),
      task('8', 'CODE_IMPLEMENTATION', 'RUNNING', 'mall.orders'),
    ], 'mall.home')).toEqual([analysis, workflow, acceptance])
  })
})
