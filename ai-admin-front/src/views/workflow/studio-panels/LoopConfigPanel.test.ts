import { flushPromises, mount } from '@vue/test-utils'
import { nextTick, reactive } from 'vue'
import ElementPlus from 'element-plus'
import { describe, expect, it, vi } from 'vitest'
import LoopConfigPanel from './LoopConfigPanel.vue'
import { createDefaultNodeData } from '@/utils/studio'
import type { CanvasNode } from '@/types/studio'

describe('loop inspector membership', () => {
  it('opens a saved loop without rewriting its membership and follows body node removal', async () => {
    const loop: CanvasNode = { id: 'loop', type: 'loop', position: { x: 0, y: 0 }, data: createDefaultNodeData('loop', '逐项处理') }
    loop.data.loopConfig!.bodyNodeIds = ['work']
    loop.data.loopConfig!.bodyEntry = 'work'
    loop.data.loopConfig!.bodyExit = 'work'
    const nodes = reactive<CanvasNode[]>([
      loop,
      { id: 'work', type: 'template', position: { x: 0, y: 0 }, data: createDefaultNodeData('template', '转换') },
    ])
    const before = JSON.stringify(nodes[0].data)
    const errors = vi.fn()
    const wrapper = mount(LoopConfigPanel, {
      props: { data: nodes[0].data, nodeId: 'loop', canvasNodes: nodes },
      global: { plugins: [ElementPlus], config: { errorHandler: errors } },
    })
    try {
      await nextTick(); await flushPromises()
      expect(errors).not.toHaveBeenCalled()
      expect(JSON.stringify(nodes[0].data)).toBe(before)
      nodes.splice(1, 1)
      await nextTick(); await flushPromises()
      expect(nodes[0].data.loopConfig).toMatchObject({ bodyNodeIds: [], bodyEntry: '', bodyExit: '' })
      expect(errors).not.toHaveBeenCalled()
    } finally { wrapper.unmount() }
  })
})
