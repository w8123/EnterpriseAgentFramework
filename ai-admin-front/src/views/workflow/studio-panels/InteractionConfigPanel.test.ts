import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import InteractionConfigPanel from './InteractionConfigPanel.vue'
import { businessMethodFixture } from '@/test/fixtures/businessMethod'
import type { BusinessMethodInfo } from '@/types/businessMethod'
import type { CanvasNodeData } from '@/types/studio'

function data(): CanvasNodeData {
  return { label: '订单结果', kind: 'interaction', configVersion: 2, inputs: [], outputs: [],
    interactionConfig: { interactionType: 'PRESENT_OUTPUT', binding: { sourceKind: 'TOOL', ref: 'orders:read',
      assetType: 'BUSINESS_METHOD', assetId: 701, autoCreateCallNode: true },
      fields: [], component: 'DETAIL', outputAlias: 'result', title: '订单结果',
      dataExpression: 'lastOutput', dataSources: {}, behavior: {}, renderSchema: {} } }
}

describe('interaction business-method source identity', () => {
  it('generates a call request with the accepted method identity', async () => {
    const owner = businessMethodFixture({ parameters: [{ name: 'orderId', type: 'string', required: true, description: '订单号' }] })
    const wrapper = mount(InteractionConfigPanel, { props: { data: data(), projectId: 7, projectCode: 'orders', toolOptions: [owner] },
      global: { plugins: [ElementPlus] } })
    const state = (wrapper.vm as unknown as { $: { setupState: { generateFieldsFromBinding: () => Promise<void> } } }).$.setupState
    await state.generateFieldsFromBinding()
    expect(wrapper.emitted('createCallNode')?.[0]?.[0]).toMatchObject({ sourceKind: 'TOOL', assetType: 'BUSINESS_METHOD',
      assetId: 701, qualifiedName: 'orders:read', projectCode: 'orders', ref: owner.name })
    expect(wrapper.text()).toContain('选择业务方法')
    expect(wrapper.text()).not.toContain('工具、组合或项目接口')
    wrapper.unmount()
  })

  it.each([
    { name: 'projection', qualifiedName: 'orders:read', projectId: 7, parameters: [], enabled: true, assetType: 'BUSINESS_METHOD' },
    businessMethodFixture({ projectId: 8, assetId: 701, projectCode: 'billing' }),
  ])('does not generate calls from a projection or another project: %j', async (record) => {
    const wrapper = mount(InteractionConfigPanel, { props: { data: data(), projectId: 7, projectCode: 'orders',
      toolOptions: [record as BusinessMethodInfo] }, global: { plugins: [ElementPlus] } })
    const state = (wrapper.vm as unknown as { $: { setupState: { generateFieldsFromBinding: () => Promise<void> } } }).$.setupState
    await state.generateFieldsFromBinding()
    expect(wrapper.emitted('createCallNode')).toBeUndefined()
    wrapper.unmount()
  })
})
