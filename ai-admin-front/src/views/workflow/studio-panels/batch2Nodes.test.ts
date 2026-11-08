import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createDefaultNodeData } from '@/utils/studio/defaultNode'
import { canvasNodeToGraphNode } from '@/utils/studio/toGraphNode'
import { graphConfigToNodeData } from '@/utils/studio/fromGraphNode'
import type { AgentForm } from '@/types/agent'
import type { CanvasNode, CanvasNodeData } from '@/types/studio'
import ListOperatorConfigPanel from './ListOperatorConfigPanel.vue'
import CodeConfigPanel from './CodeConfigPanel.vue'
import { CONDITION_OPERATORS, isUnaryOperator } from './conditionOperators'

const base = {} as AgentForm

function node(kind: CanvasNode['data']['kind']): CanvasNode {
  return { id: `${kind}_1`, type: kind, position: { x: 0, y: 0 }, data: createDefaultNodeData(kind, kind) } as CanvasNode
}

describe('batch 2 workflow nodes', () => {
  it('round-trips list operator and sub-workflow configs through GraphSpec', () => {
    const list = node('listOperator')
    list.data.listConfig = { source: 'var.orders', operations: [{ type: 'limit', count: 3 }] }
    const graphList = canvasNodeToGraphNode(list, base)
    expect(graphList.type).toBe('LIST_OPERATOR')
    expect(graphList.config).toMatchObject({ source: 'var.orders', operations: [{ type: 'limit', count: 3 }] })
    const restored: CanvasNodeData = graphConfigToNodeData('listOperator', 'x', graphList.config as Record<string, unknown>, {} as never)
    expect(restored.listConfig).toEqual({ source: 'var.orders', operations: [{ type: 'limit', count: 3 }] })

    const sub = node('subWorkflow')
    sub.data.subWorkflowConfig = { workflowId: 'wf-2', workflowName: '查库存', versionId: 7, inputs: { sku: 'params.sku' } }
    const graphSub = canvasNodeToGraphNode(sub, base)
    expect(graphSub.type).toBe('SUB_WORKFLOW')
    expect(graphSub.config).toMatchObject({ workflowId: 'wf-2', versionId: 7, inputs: { sku: 'params.sku' } })
    const restoredSub: CanvasNodeData = graphConfigToNodeData('subWorkflow', 'x', graphSub.config as Record<string, unknown>, {} as never)
    expect(restoredSub.subWorkflowConfig).toMatchObject({ workflowId: 'wf-2', versionId: 7, workflowName: '查库存' })
  })

  it('defaults new code nodes to sandboxed JavaScript and keeps legacy expression nodes', () => {
    const code = node('code')
    const graph = canvasNodeToGraphNode(code, base)
    expect(graph.config).toMatchObject({ language: 'javascript', inputs: { text: 'lastOutput' } })
    expect(String(graph.config?.code)).toContain('function main(inputs)')
    const legacy: CanvasNodeData = graphConfigToNodeData('code', 'x', { language: 'expression', outputs: { a: 'lastOutput' } }, {} as never)
    expect(legacy.codeConfig).toEqual({ language: 'expression', code: '', outputs: { a: 'lastOutput' } })
  })

  it('opening the panels does not write into the node', () => {
    const listData = createDefaultNodeData('listOperator', 'x')
    const listBefore = JSON.stringify(listData)
    mount(ListOperatorConfigPanel, { props: { data: listData }, global: { plugins: [ElementPlus] } })
    expect(JSON.stringify(listData)).toBe(listBefore)

    const codeData = graphConfigToNodeData('code', 'x', { language: 'expression', outputs: { a: 'lastOutput' } }, {} as never)
    const codeBefore = JSON.stringify(codeData)
    mount(CodeConfigPanel, { props: { data: codeData as never }, global: { plugins: [ElementPlus] } })
    expect(JSON.stringify(codeData)).toBe(codeBefore)
  })

  it('lists the new comparison operators', () => {
    const values = CONDITION_OPERATORS.map((item) => item.value)
    expect(values).toEqual(expect.arrayContaining(['starts_with', 'ends_with', 'in', 'not_in', 'regex', 'is_number', 'is_list']))
    expect(isUnaryOperator('is_number')).toBe(true)
    expect(isUnaryOperator('regex')).toBe(false)
  })
})
