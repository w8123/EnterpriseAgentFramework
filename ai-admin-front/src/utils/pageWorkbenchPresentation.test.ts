import { describe, expect, it } from 'vitest'
import type { ProjectPage } from '@/types/pageWorkbench'
import {
  pageWorkbenchHumanText,
  pageWorkbenchModuleName,
  pageWorkbenchPageDescription,
  pageWorkbenchPageName,
  pageWorkbenchRiskLabel,
  pageWorkbenchSourceLabel,
  pageWorkbenchTaskSummary,
  pageWorkbenchTaskTitle,
} from './pageWorkbenchPresentation'

const page = {
  id: 1,
  projectId: 7,
  projectCode: 'orders',
  pageKey: 'assessment-scenarios',
  moduleKey: 'assessment',
  moduleName: 'Assessment Scenarios',
  name: 'Assessment Scenarios',
  description: 'Browse assessment scenarios.',
  routePattern: '/assessment-scenarios',
  sourceType: 'AI_SCAN',
  lifecycleStatus: 'ACTIVE',
  resources: [],
  actions: [],
} satisfies ProjectPage

describe('pageWorkbenchPresentation', () => {
  it('keeps Chinese human text and common technical acronyms', () => {
    expect(pageWorkbenchHumanText('评估场景', '兜底')).toBe('评估场景')
    expect(pageWorkbenchHumanText('AI / MCP', '兜底')).toBe('AI / MCP')
    expect(
      pageWorkbenchHumanText(
        '结果已回传，但 Runtime 验证仍是 PENDING',
        '兜底',
      ),
    ).toBe('结果已回传，但运行服务验证仍是待确认')
  })

  it('replaces English-only generated copy without hiding technical identity', () => {
    expect(pageWorkbenchPageName(page)).toBe('页面 · /assessment-scenarios')
    expect(pageWorkbenchPageDescription(page)).toBe('尚未提供中文页面说明。')
    expect(pageWorkbenchModuleName(page.moduleName, page.moduleKey)).toBe(
      '业务模块 · assessment',
    )
  })

  it('localizes enum values and weak task output', () => {
    expect(pageWorkbenchSourceLabel('AI_SCAN')).toBe('AI 扫描')
    expect(pageWorkbenchRiskLabel('IRREVERSIBLE')).toBe('不可逆')
    expect(pageWorkbenchTaskTitle('Connect QMSSMP to ReachAI', '项目接入')).toBe(
      '项目接入任务',
    )
    expect(
      pageWorkbenchTaskSummary(
        'Integration finished.',
        'Connect system.',
        '待平台验证',
      ),
    ).toBe('当前进度：待平台验证')
  })
})
