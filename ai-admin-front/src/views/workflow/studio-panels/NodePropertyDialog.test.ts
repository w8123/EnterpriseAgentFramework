import { flushPromises, mount } from '@vue/test-utils'
import { h, nextTick } from 'vue'
import ElementPlus from 'element-plus'
import { describe, expect, it } from 'vitest'
import AppDialog from '@/components/common/AppDialog.vue'
import NodeConfigPanel from './NodeConfigPanel.vue'
import type { CanvasNodeData } from '@/types/studio'

describe('shared node property dialog', () => {
  it('keeps a named, closable overlay and the variable input/config/output tabs intact', async () => {
    const data = { label: '下游状态', kind: 'variable', configVersion: 2, outputAlias: 'variable_output',
      inputs: [], outputs: [], assignments: { order_state: 'nodeOutput.api-node.state' } } as CanvasNodeData
    const wrapper = mount(AppDialog, { attachTo: document.body,
      props: { modelValue: true, title: '下游状态 · 节点配置', width: '920px' },
      attrs: { class: 'node-property-dialog', 'destroy-on-close': true, 'align-center': true },
      slots: { default: () => h(NodeConfigPanel, { data, nodeId: 'variable-node', modelOptions: [],
        modelOptionsLoading: false, modelOptionsLoadError: false, knowledgeOptions: [], toolOptions: [],
        variableOptions: ['nodeOutput.api-node.state'], credentialOptions: [], paramSourceHints: [] }) },
      global: { plugins: [ElementPlus], stubs: { transition: false } },
    })
    try {
      await nextTick(); await flushPromises()
      const dialog = document.body.querySelector('.node-property-dialog')!
      expect(dialog.classList.contains('app-dialog')).toBe(true)
      expect(dialog.closest('[role="dialog"]')?.getAttribute('aria-label')).toBe('下游状态 · 节点配置')
      expect(dialog.textContent).toContain('输入')
      expect(dialog.textContent).toContain('配置')
      expect(dialog.textContent).toContain('输出')
      expect(Array.from(dialog.querySelectorAll('input')).map(input => input.value)).toContain('order_state')
      expect(data.assignments?.order_state).toBe('nodeOutput.api-node.state')
      ;(dialog.querySelector('.el-dialog__headerbtn') as HTMLButtonElement).click()
      await new Promise(resolve => setTimeout(resolve, 400))
      await flushPromises()
      expect(wrapper.emitted('update:modelValue')?.[0]).toEqual([false])
    } finally { wrapper.unmount() }
  })
})
