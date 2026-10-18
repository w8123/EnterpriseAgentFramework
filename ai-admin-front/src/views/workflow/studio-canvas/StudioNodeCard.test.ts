import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import type { CanvasNode, CanvasNodeKind } from '@/types/studio'
import StudioNodeCard from './StudioNodeCard.vue'
import { studioNodeCardModel } from './studioNodeCardModel'

const HandleStub = { name: 'Handle', props: ['id', 'type', 'position'], template: '<i class="handle-stub" :data-type="type" :data-id="id" />' }

function data(partial: Partial<CanvasNode['data']> = {}): CanvasNode['data'] {
  return { kind: 'tool', label: '查库存', inputs: [], outputs: [], ...partial } as CanvasNode['data']
}

function render(kind: CanvasNodeKind, nodeData: CanvasNode['data'], runLabel = '') {
  return mount(StudioNodeCard, {
    props: { model: studioNodeCardModel(kind, 'n1', nodeData, { projectCode: 'yudao-erp' }), runLabel },
    global: { stubs: { Handle: HandleStub, 'el-icon': { template: '<span><slot /></span>' } } },
  })
}

describe('StudioNodeCard', () => {
  it('renders every canvas node kind with a label and kind class', () => {
    const kinds: CanvasNodeKind[] = [
      'start', 'end', 'userInput', 'interaction', 'pageAction', 'llm', 'tool', 'knowledge', 'condition',
      'variable', 'template', 'parameter', 'http', 'answer', 'code', 'classifier', 'aggregate', 'approval',
      'loop', 'knowledgeWrite', 'documentExtract', 'mcp',
    ]
    for (const kind of kinds) {
      const model = studioNodeCardModel(kind, 'n1', data({ kind }), {})
      expect(model.kindClass, kind).toMatch(/-node$/)
      expect(render(kind, data({ kind })).find('.node-label').text(), kind).not.toBe('')
    }
  })

  it('shows business-method names in the product vocabulary', () => {
    const wrapper = render('tool', data({ toolConfig: { ref: 'erpStockPage' } as CanvasNode['data']['toolConfig'] }))
    expect(wrapper.find('.node-kind').text()).toBe('业务方法')
    expect(wrapper.find('.node-label').text()).toBe('查库存')
    expect(wrapper.find('.node-desc').text()).toBe('erpStockPage')
    expect(wrapper.find('.node-state').text()).toBe('yudao-erp')
  })

  it('branch nodes expose one source handle per route instead of a plain output', () => {
    const wrapper = render('approval', data({ kind: 'approval' }))
    const sources = wrapper.findAll('.handle-stub[data-type="source"]')
    expect(sources.map((handle) => handle.attributes('data-id'))).toEqual(['approved', 'rejected', 'timeout'])
  })

  it('emits open-trace from the run badge only when a run label exists', async () => {
    expect(render('llm', data({ kind: 'llm' })).find('.node-runtime').exists()).toBe(false)
    const wrapper = render('llm', data({ kind: 'llm' }), '成功 · 120ms')
    await wrapper.find('.node-runtime').trigger('click')
    expect(wrapper.emitted('open-trace')).toHaveLength(1)
  })
})
