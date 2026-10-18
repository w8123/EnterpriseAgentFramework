import { flushPromises, mount } from '@vue/test-utils'
import { h, nextTick, reactive } from 'vue'
import ElementPlus from 'element-plus'
import { describe, expect, it } from 'vitest'
import NodeConfigPanel from '../studio-panels/NodeConfigPanel.vue'
import StudioPropertyPanel from './StudioPropertyPanel.vue'
import { clampPropertyWidth, STUDIO_PROPERTY_WIDTH_MAX, STUDIO_PROPERTY_WIDTH_MIN } from './studioPropertyPanel'
import type { CanvasNode } from '@/types/studio'

function variableNode(): CanvasNode {
  return reactive({
    id: 'variable-node',
    type: 'variable',
    position: { x: 0, y: 0 },
    data: { label: '下游状态', kind: 'variable', configVersion: 2, outputAlias: 'variable_output',
      inputs: [], outputs: [], assignments: { order_state: 'nodeOutput.api-node.state' } },
  }) as unknown as CanvasNode
}

function render(node: CanvasNode | null, extra: Record<string, unknown> = {}) {
  return mount(StudioPropertyPanel, {
    attachTo: document.body,
    props: {
      collapsed: false, width: 480, tab: 'config', readOnly: false, node, edge: null, edgeLabel: '',
      edgeConditionOptions: [], edgeAllowsCustomRoute: false, canvasNodes: node ? [node] : [],
      meta: reactive({ name: '产品库存查询', keySlug: 'product-stock-query', workflowKind: 'GENERAL', description: '', defaultModelInstanceId: '' }),
      modelOptions: [], modelOptionsLoading: false, modelOptionsLoadError: false,
      kindColor: () => ({ bg: '#fff', border: '#000' }), nodeIcon: () => undefined,
      debugMessage: '', debugStateText: '{}', debugLoading: false, debugResult: null, trace: null,
      canReadPublishedRuns: false, ...extra,
    },
    slots: {
      'node-config': ({ node: current }: { node: CanvasNode }) => h(NodeConfigPanel, {
        data: current.data, nodeId: current.id, modelOptions: [], modelOptionsLoading: false,
        modelOptionsLoadError: false, knowledgeOptions: [], toolOptions: [],
        variableOptions: ['nodeOutput.api-node.state'], credentialOptions: [],
      }),
    },
    global: { plugins: [ElementPlus], stubs: { ModelSelectEmptyState: true } },
  })
}

describe('StudioPropertyPanel', () => {
  it('edits the selected node in place with config / test / trace tabs', async () => {
    const node = variableNode()
    const wrapper = render(node)
    try {
      await nextTick(); await flushPromises()
      const tabs = wrapper.find('.node-property-tabs').findAll('.el-tabs__item').slice(0, 3).map((item) => item.text())
      expect(tabs).toEqual(['配置', '测试', '调试记录'])
      expect(document.body.querySelector('[role="dialog"]')).toBeNull()
      const inputs = wrapper.findAll('input')
      expect(inputs.map((input) => (input.element as HTMLInputElement).value)).toContain('order_state')
      await inputs[0].setValue('库存状态')
      await inputs[0].trigger('change')
      expect(node.data.label).toBe('库存状态')
      expect(wrapper.emitted('mark-dirty')).toBeTruthy()
    } finally { wrapper.unmount() }
  })

  it('shows workflow info with a readable kind picker when nothing is selected', async () => {
    const wrapper = render(null)
    try {
      await nextTick()
      const text = wrapper.text()
      expect(text).toContain('标识')
      expect(text).not.toContain('keySlug')
      expect(wrapper.find('.el-select').exists()).toBe(true)
    } finally { wrapper.unmount() }
  })

  it('keeps the panel width inside a usable range', () => {
    expect(clampPropertyWidth(100, 1600)).toBe(STUDIO_PROPERTY_WIDTH_MIN)
    expect(clampPropertyWidth(5000, 2600)).toBe(STUDIO_PROPERTY_WIDTH_MAX)
    expect(clampPropertyWidth(900, 1200)).toBe(720)
    expect(clampPropertyWidth(Number.NaN, 1600)).toBe(480)
  })
})
