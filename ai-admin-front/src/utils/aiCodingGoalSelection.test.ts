import { describe, expect, it } from 'vitest'
import type { AiCodingTask } from '@/types/aiCodingTask'
import type { PageAnalysisFinding } from '@/types/pageWorkbench'
import {
  buildPageImplementationGoalSelection,
  pageImplementationObjective,
  readPageImplementationGoalSelection,
} from './aiCodingGoalSelection'

function finding(id: number, status: PageAnalysisFinding['status'] = 'KEPT') {
  return {
    id,
    title: `建议 ${id}`,
    useCase: `用例 ${id}`,
    confirmedFact: `事实 ${id}`,
    status,
  } as PageAnalysisFinding
}

function task(objective: string, goalSelection?: unknown) {
  return {
    taskId: 'ait_goal_selection',
    objective,
    targets: [{
      targetType: 'PAGE',
      targetKey: 'demo.page',
      targetRole: 'PRIMARY',
      accessMode: 'READ_WRITE',
      snapshot: {
        pageId: 1,
        ...(goalSelection ? { goalSelection } : {}),
      },
    }],
  } as unknown as AiCodingTask
}

describe('AI coding page goal selection', () => {
  it('builds a structured task snapshot without losing manual or AI goals', () => {
    const selection = buildPageImplementationGoalSelection(
      [finding(7), finding(8, 'UNREAD')],
      { manualGoal: '保留人工目标', findingIds: [8, 7, 8] },
    )

    expect(selection.findingIds).toEqual([8, 7])
    expect(selection.items).toEqual([
      { source: 'MANUAL', content: '保留人工目标' },
      { source: 'AI_FINDING', findingId: 8, content: '建议 8：用例 8' },
      { source: 'AI_FINDING', findingId: 7, content: '建议 7：用例 7' },
    ])
    expect(pageImplementationObjective(selection)).toContain('人工描述：保留人工目标')
    expect(pageImplementationObjective(selection)).toContain('AI 建议：建议 8：用例 8')
  })

  it('restores the exact structured selection even when current finding status changed', () => {
    const findings = [finding(7, 'UNREAD')]
    const selection = buildPageImplementationGoalSelection(findings, {
      manualGoal: '原人工目标',
      findingIds: [7],
    })

    expect(readPageImplementationGoalSelection(
      task('旧目标文本', selection),
      findings,
    )).toEqual(selection)
  })

  it('restores legacy objectives and matches their AI finding ids', () => {
    const restored = readPageImplementationGoalSelection(task([
      '人工描述：旧人工目标',
      'AI 建议：建议 7：用例 7',
      '完成业务页面代码、页面操作登记、相关测试和真实浏览器自检；不要发布工作流。',
    ].join('\n')), [finding(7, 'UNREAD')])

    expect(restored.manualGoal).toBe('旧人工目标')
    expect(restored.findingIds).toEqual([7])
    expect(restored.items).toHaveLength(2)
  })
})
