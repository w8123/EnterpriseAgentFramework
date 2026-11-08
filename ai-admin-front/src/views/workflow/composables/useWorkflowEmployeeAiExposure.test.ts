import { describe, expect, it } from 'vitest'
import type { CanvasNode } from '@/types/studio'
import { suggestWorkflowRiskLevel, workflowToolDescription } from './useWorkflowEmployeeAiExposure'

const node = (kind: string, data: Record<string, unknown> = {}) => ({ id: kind, data: { kind, ...data } }) as unknown as CanvasNode

describe('employee AI exposure', () => {
  it('suggests WRITE whenever a node can change a business system', () => {
    expect(suggestWorkflowRiskLevel([node('llm'), node('code'), node('listOperator')])).toBe('READ')
    expect(suggestWorkflowRiskLevel([node('llm'), node('tool')])).toBe('WRITE')
    expect(suggestWorkflowRiskLevel([node('knowledgeWrite')])).toBe('WRITE')
    expect(suggestWorkflowRiskLevel([node('tool'), node('pageAction')])).toBe('PAGE_ACTION')
  })

  it('describes the tool from the workflow and its start form', () => {
    const text = workflowToolDescription('建客户', '在 ERP 新建客户', [
      node('userInput', { userInputConfig: { outputAlias: 'params', fields: [
        { name: 'name', type: 'string', required: true, description: '客户名称' },
      ] } }),
    ])
    expect(text).toBe('在 ERP 新建客户\n参数：name，客户名称，必填')
  })
})
