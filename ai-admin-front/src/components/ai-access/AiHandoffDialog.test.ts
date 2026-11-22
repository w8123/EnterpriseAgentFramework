import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import ElementPlus from 'element-plus'
import AiHandoffDialog from './AiHandoffDialog.vue'

const mocks = vi.hoisted(() => ({ list: vi.fn(), create: vi.fn(), copy: vi.fn() }))
vi.mock('@/api/platformAiConnections', () => ({
  listPlatformAiConnections: mocks.list,
  createPlatformAiConnection: mocks.create,
}))
vi.mock('@/utils/aiCodingClipboard', () => ({ copyAiCodingText: mocks.copy }))

let wrapper: ReturnType<typeof mount> | undefined

async function open(context: Record<string, string> = {}, scenario = 'doctor-project') {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: ['/workflows', '/me/ai-connections'].map(path => ({ path, component: { template: '<div />' } })),
  })
  await router.push('/workflows')
  wrapper = mount(AiHandoffDialog, {
    attachTo: document.body,
    props: { modelValue: true, scenario, context },
    global: { plugins: [ElementPlus, router] },
  })
  await flushPromises()
  return router
}

function copies() {
  return [...document.querySelectorAll<HTMLButtonElement>('[data-testid="ai-handoff-copy"]')]
}

beforeEach(() => {
  vi.clearAllMocks()
  mocks.copy.mockResolvedValue({ copied: true, method: 'clipboard' })
})

afterEach(() => { wrapper?.unmount(); wrapper = undefined; document.body.innerHTML = '' })

describe('AiHandoffDialog', () => {
  it('links to the connection page and closes without querying or creating credentials', async () => {
    const router = await open({ projectCode: 'yudao-erp' })
    const link = document.querySelector<HTMLAnchorElement>('[data-testid="ai-handoff-connect"]')!
    expect(link.tagName).toBe('A')
    expect(link.getAttribute('href')).toBe('/me/ai-connections')
    expect(document.querySelector('[role="radiogroup"]')).toBeNull()
    expect(mocks.list).not.toHaveBeenCalled()
    expect(mocks.create).not.toHaveBeenCalled()
    link.click()
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/me/ai-connections')
    expect(wrapper!.emitted('update:modelValue')).toEqual([[false]])
  })

  it('keeps detailed instructions collapsed and previews the current project on request', async () => {
    await open({ projectCode: 'yudao-erp' })
    expect(document.querySelector('[data-testid="ai-handoff-prompt"]')).toBeNull()
    const toggle = document.querySelector<HTMLButtonElement>('.ai-handoff__preview-toggle')!
    expect(toggle.getAttribute('aria-expanded')).toBe('false')
    toggle.click()
    await flushPromises()
    const prompt = document.querySelector('[data-testid="ai-handoff-prompt"]')!
    expect(prompt.textContent).toContain('reachai_integration_guide（topic="doctor-project"，projectCode="yudao-erp"）')
    expect(prompt.id).toBe(toggle.getAttribute('aria-controls'))
    expect(toggle.getAttribute('aria-expanded')).toBe('true')
    toggle.click()
    await flushPromises()
    expect(document.querySelector('[data-testid="ai-handoff-prompt"]')).toBeNull()
  })

  it('shows workflow examples as reference text without copy or preview actions', async () => {
    await open({ projectCode: 'orders', workflowKey: 'current-workflow' }, 'workflow-draft')
    const examples = document.querySelector('[aria-label="提示词示例"]')!
    expect(examples.tagName).toBe('UL')
    expect(examples.querySelectorAll('li')).toHaveLength(3)
    expect(examples.textContent).toContain('低于安全库存的商品')
    expect(examples.textContent).toContain('本月各客户的订单数')
    expect(examples.querySelector('h4, button, a')).toBeNull()
    expect(copies()).toHaveLength(0)
    expect(document.querySelector('.ai-handoff__preview-toggle')).toBeNull()
    expect(document.querySelector('[data-testid="ai-handoff-prompt"]')).toBeNull()
    expect(mocks.copy).not.toHaveBeenCalled()
  })

  it('blocks required task context and enables copying only once the page supplies it', async () => {
    await open({}, 'ai-coding-task')
    expect(copies()[0]!.disabled).toBe(true)
    expect(document.querySelector('[data-testid="ai-handoff-prompt"]')).toBeNull()
    copies()[0]!.click()
    await flushPromises()
    expect(mocks.copy).not.toHaveBeenCalled()
    await wrapper!.setProps({ context: { taskId: 'task-1', claimCode: 'fixture-code' } })
    copies()[0]!.click()
    await flushPromises()
    expect(mocks.copy).toHaveBeenCalledWith(expect.stringContaining('taskId="task-1"，claimCode="fixture-code"'))
  })

  it('expands actual task instructions for manual copying when clipboard access fails', async () => {
    mocks.copy.mockResolvedValue({ copied: false, method: 'manual' })
    await open({ taskId: 'task-1', claimCode: 'fixture-code' }, 'ai-coding-task')
    copies()[0]!.click()
    await flushPromises()
    expect(document.querySelector('[data-testid="ai-handoff-prompt"]')?.textContent).toContain('taskId="task-1"，claimCode="fixture-code"')
    expect(document.querySelector('[role="alert"]')?.textContent).toContain('手动复制')
    expect(copies().every(button => !button.disabled)).toBe(true)
  })

  it('ignores a late clipboard result after the page context changes', async () => {
    let finish!: (result: { copied: boolean }) => void
    mocks.copy.mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
    await open({ projectCode: 'old-project' })
    copies()[0]!.click()
    await flushPromises()
    expect(copies()[0]!.disabled).toBe(true)
    await wrapper!.setProps({ context: { projectCode: 'new-project' } })
    finish({ copied: true })
    await flushPromises()
    expect(document.querySelector('[role="status"]')).toBeNull()
    expect(copies()[0]!.disabled).toBe(false)
    copies()[0]!.click()
    await flushPromises()
    expect(mocks.copy.mock.calls[mocks.copy.mock.calls.length - 1]![0]).toContain('projectCode="new-project"')
  })

  it('resets copied feedback and previews when reopened', async () => {
    await open({ projectCode: 'orders' })
    copies()[0]!.click()
    document.querySelector<HTMLButtonElement>('.ai-handoff__preview-toggle')!.click()
    await flushPromises()
    await wrapper!.setProps({ modelValue: false })
    await wrapper!.setProps({ modelValue: true })
    await flushPromises()
    expect(document.querySelector('[role="status"]')).toBeNull()
    expect(document.querySelector('[data-testid="ai-handoff-prompt"]')).toBeNull()
  })

  it('handles an unknown scenario without offering an empty copy action', async () => {
    await open({}, 'unknown-scenario')
    expect(document.body.textContent).toContain('找不到这个功能的提示词')
    expect(copies()).toHaveLength(0)
  })
})
