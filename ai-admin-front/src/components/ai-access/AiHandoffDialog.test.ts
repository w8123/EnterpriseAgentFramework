import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import AiHandoffDialog from './AiHandoffDialog.vue'
import { resetAiConnectionStatusForTest } from './useAiConnectionStatus'

const mocks = vi.hoisted(() => ({ list: vi.fn(), create: vi.fn() }))
vi.mock('@/api/platformAiConnections', () => ({
  listPlatformAiConnections: mocks.list,
  createPlatformAiConnection: mocks.create,
}))
vi.mock('vue-router', () => ({ RouterLink: { template: '<a><slot /></a>' } }))

let wrapper: ReturnType<typeof mount> | undefined

async function open(context: Record<string, string>) {
  wrapper = mount(AiHandoffDialog, {
    attachTo: document.body,
    props: { modelValue: true, scenario: 'doctor-project', context },
    global: { plugins: [ElementPlus] },
  })
  await flushPromises()
}

beforeEach(() => {
  vi.clearAllMocks()
  resetAiConnectionStatusForTest()
})
afterEach(() => { wrapper?.unmount(); wrapper = undefined; document.body.innerHTML = '' })

describe('AiHandoffDialog', () => {
  it('shows the existing connection and only the feature prompt', async () => {
    mocks.list.mockResolvedValue({ data: [{ id: 3, label: '我的 Codex', toolKind: 'CODEX', tokenHint: 'x', status: 'ACTIVE', createdAt: '2026-11-07T10:00:00', expiresAt: '2026-12-07T10:00:00' }] })
    await open({ projectCode: 'yudao-erp' })
    expect(document.querySelector('[data-testid="ai-handoff-connected"]')?.textContent).toContain('Codex')
    const prompt = document.querySelector('[data-testid="ai-handoff-prompt"]')!.textContent!
    expect(prompt).toContain('reachai_doctor（projectCode="yudao-erp"）')
    expect(prompt).not.toContain('export REACHAI_PLATFORM_AI_TOKEN')
    expect(mocks.create).not.toHaveBeenCalled()
  })

  it('creates a connection when none exists and combines install and task prompts', async () => {
    mocks.list.mockResolvedValue({ data: [] })
    mocks.create.mockResolvedValue({ data: {
      connection: { id: 9, label: '我的 Claude Code', toolKind: 'CLAUDE_CODE', tokenHint: 'r', status: 'ACTIVE', createdAt: '2026-11-07T10:00:00', expiresAt: '2026-12-07T10:00:00' },
      token: 'rpi_secret',
    } })
    await open({ projectCode: 'yudao-erp' })
    ;(document.querySelector('[data-testid="ai-handoff-connect"]') as HTMLButtonElement).click()
    await flushPromises()
    expect(mocks.create).toHaveBeenCalledWith({ label: '我的 Claude Code', toolKind: 'CLAUDE_CODE', ttlDays: 30 })
    const prompt = document.querySelector('[data-testid="ai-handoff-prompt"]')!.textContent!
    expect(prompt).toContain('REACHAI_PLATFORM_AI_TOKEN_9="rpi_secret"')
    expect(prompt).toContain('接好之后，接着做下面这件事')
    expect(prompt).toContain('reachai_doctor')
  })

  it('blocks copying until the page provides required values', async () => {
    mocks.list.mockResolvedValue({ data: [] })
    await open({})
    expect(document.querySelector('[data-testid="ai-handoff-prompt"]')).toBeNull()
    expect((document.querySelector('[data-testid="ai-handoff-copy"]') as HTMLButtonElement).disabled).toBe(true)
  })
})
