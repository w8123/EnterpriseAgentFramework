import { flushPromises, mount } from '@vue/test-utils'
import { h, nextTick, reactive } from 'vue'
import ElementPlus, { ElForm } from 'element-plus'
import { afterEach, describe, expect, it } from 'vitest'
import LlmConfigPanel from './LlmConfigPanel.vue'
import type { CanvasNodeData, LlmNodeConfig } from '@/types/studio'

const wrappers: Array<ReturnType<typeof mount>> = []
afterEach(() => { wrappers.splice(0).forEach((wrapper) => wrapper.unmount()) })

function render(config: Partial<LlmNodeConfig> = {}, disabled = false) {
  const data = reactive<CanvasNodeData>({
    label: '整理回答', kind: 'llm', configVersion: 2,
    llmConfig: { modelInstanceId: '', systemPrompt: '规则', userPrompt: '问题', ...config },
  })
  const wrapper = mount({
    setup: () => () => h(ElForm, { disabled, labelPosition: 'top' }, () => h(LlmConfigPanel, {
      data, modelOptions: [], modelOptionsLoading: false, modelOptionsLoadError: false,
      variableOptions: ['nodeOutput.lookup.name'],
    })),
  }, { attachTo: document.body, global: { plugins: [ElementPlus], stubs: { ModelSelectEmptyState: true } } })
  wrappers.push(wrapper)
  return { wrapper, data }
}

async function insertQuestionVariable(wrapper: ReturnType<typeof mount>) {
  await wrapper.findAll('button').find((button) => button.text() === '插入变量')!.trigger('click')
  await nextTick(); await flushPromises()
  const choice = Array.from(document.querySelectorAll<HTMLButtonElement>('.prompt-variable-group button'))
    .find((button) => button.textContent?.includes('params.question'))!
  expect(choice).toBeTruthy()
  choice.click()
  await nextTick()
}

describe('LLM prompt editing', () => {
  it('inserts a variable at the selected position of the focused message and keeps the other messages', async () => {
    const { wrapper, data } = render({ messages: [
      { id: 's', role: 'system', content: '规则', enabled: true },
      { id: 'u', role: 'user', content: '查询订单状态', enabled: true },
    ] })
    const input = wrapper.get('textarea[aria-label="第 2 条消息内容"]')
    await input.trigger('focus')
    ;(input.element as HTMLTextAreaElement).setSelectionRange(2, 4)
    await insertQuestionVariable(wrapper)
    expect(data.llmConfig!.messages!.map((message) => message.content)).toEqual(['规则', '查询{{ params.question }}状态'])
    expect(data.llmConfig!.userPrompt).toBe('查询{{ params.question }}状态')
    expect(data.llmConfig!.contextVariables).toContain('params.question')
  })

  it('inserts into the focused simple system prompt and synchronizes executable messages', async () => {
    const { wrapper, data } = render({ promptTemplateMode: 'simple', systemPrompt: '请核对', userPrompt: '原始问题' })
    const input = wrapper.get('textarea[placeholder="定义角色、任务和回答约束"]')
    await input.trigger('focus')
    ;(input.element as HTMLTextAreaElement).setSelectionRange(1, 1)
    await insertQuestionVariable(wrapper)
    expect(data.llmConfig!.systemPrompt).toBe('请{{ params.question }}核对')
    expect(data.llmConfig!.messages!.find((message) => message.role === 'system')!.content).toBe('请{{ params.question }}核对')
    expect(data.llmConfig!.userPrompt).toBe('原始问题')
  })

  it.each([
    [
      { id: 'u1', role: 'user' as const, content: '第一条' },
      { id: 'u2', role: 'user' as const, content: '第二条' },
    ],
    [{ id: 'a', role: 'assistant' as const, content: '示例回答' }],
    [{ id: 's', role: 'system' as const, content: '暂时停用', enabled: false }],
  ])('prevents switching to simple mode when it would discard message information: %j', async (...messages) => {
    const { wrapper, data } = render({ messages })
    await nextTick()
    const simple = wrapper.findAll('.prompt-toolbar label').find((item) => item.text() === '简洁')!
    expect(simple.get('input').attributes('disabled')).toBeDefined()
    expect(data.llmConfig!.messages).toEqual(messages)
  })

  it('keeps the shortcut prompt in sync when messages are reordered or disabled', async () => {
    const { wrapper, data } = render({ messages: [
      { id: 'u1', role: 'user', content: '第一条', enabled: true },
      { id: 'u2', role: 'user', content: '第二条', enabled: true },
    ] })
    await wrapper.get('button[aria-label="下移消息"]').trigger('click')
    expect(data.llmConfig!.messages!.map((message) => message.id)).toEqual(['u2', 'u1'])
    expect(data.llmConfig!.userPrompt).toBe('第一条')
    await wrapper.findAll('input[role="switch"]')[1].setValue(false)
    expect(data.llmConfig!.userPrompt).toBe('第二条')
  })

  it('disables node editing under the read-only inspector form', async () => {
    const { wrapper, data } = render({}, true)
    await nextTick()
    expect(wrapper.get('textarea').attributes('disabled')).toBeDefined()
    expect(wrapper.findAll('button').find((button) => button.text() === '插入变量')!.attributes('disabled')).toBeDefined()
    expect(wrapper.findAll('button').find((button) => button.text() === '添加消息')!.attributes('disabled')).toBeDefined()
    expect(data.llmConfig!.messages).toHaveLength(2)
  })
})
