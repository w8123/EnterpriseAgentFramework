import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import InteractionConfigPanel from './InteractionConfigPanel.vue'
import type { CanvasNodeData } from '@/types/studio'

describe('retired scan API interaction metadata', () => {
  it('explains the existing binding without rewriting it or offering another call generator', () => {
    const binding = { sourceKind: 'API' as const, ref: 'old_name', qualifiedName: 'orders:old_name',
      projectId: 7, projectCode: 'orders', autoCreateCallNode: true, autoCreateDisplayNode: true,
      callNodeId: 'old-call', displayNodeId: 'old-display', apiMethod: 'GET', apiPath: '/orders' }
    const original = JSON.stringify(binding)
    const data = { label: '旧结果展示', kind: 'interaction', configVersion: 2, inputs: [], outputs: [],
      interactionConfig: { interactionType: 'PRESENT_OUTPUT', binding, fields: [], component: 'DETAIL',
        outputAlias: 'result', title: '旧展示', dataExpression: 'nodeOutput.old-call', dataSources: {}, behavior: {}, renderSchema: {} },
    } as CanvasNodeData
    const wrapper = mount(InteractionConfigPanel, { props: { data, projectId: 7, projectCode: 'orders', toolOptions: [] },
      global: { plugins: [ElementPlus] } })
    expect(wrapper.text()).toContain('旧项目接口绑定仅保留原元数据')
    expect(wrapper.text()).toContain('先用现有 API 节点')
    expect(wrapper.text()).toContain('orders:old_name')
    expect(wrapper.text()).not.toContain('从项目接口选')
    expect(wrapper.findAll('.interaction-inline-option')).toHaveLength(0)
    expect(JSON.stringify(data.interactionConfig!.binding)).toBe(original)
    expect(wrapper.emitted('createCallNode')).toBeUndefined()
    wrapper.unmount()
  })
})
