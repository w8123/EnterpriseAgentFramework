import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import EmployeePortal from './EmployeePortal.vue'

function reply(data: unknown, status = 200) {
  return Promise.resolve(new Response(JSON.stringify(status === 200 ? { code: 200, data } : { code: status, message: data }), { status }))
}

const me = { userName: '张三', account: '1', projectCode: 'yudao-erp', systemName: '芋道 ERP' }

describe('EmployeePortal', () => {
  beforeEach(() => {
    sessionStorage.clear()
    window.history.replaceState(null, '', '/my/ai-tools')
  })
  afterEach(() => vi.unstubAllGlobals())

  it('signs in with the one-time ticket and removes it from the address bar', async () => {
    window.history.replaceState(null, '', '/my/ai-tools#ticket=abc123')
    const fetchMock = vi.fn((url: string, init?: RequestInit) => {
      if (url === '/api/employee-portal/session') return reply({ token: 'sess', expiresAt: 'x', me })
      if (url === '/api/employee-portal/calls') {
        return reply([{ id: 1, toolKind: 'CODEX', toolName: 'yudao_erp_stock_page', businessSystem: '芋道 ERP', success: true, latencyMs: 80, createdAt: '2026-10-26T08:00:00' }])
      }
      if (url.endsWith('/options')) return reply([])
      void init
      return reply([])
    })
    vi.stubGlobal('fetch', fetchMock)

    const wrapper = mount(EmployeePortal, { global: { stubs: { LiquidGlassButton: { template: '<button><slot /></button>' } } } })
    await flushPromises()

    const [, init] = fetchMock.mock.calls[0]
    expect(JSON.parse(String(init?.body))).toEqual({ ticket: 'abc123' })
    expect(window.location.hash).toBe('')
    expect(sessionStorage.getItem('reachai.employee-portal.token')).toBe('sess')
    expect(wrapper.text()).toContain('张三 · 芋道 ERP')
    expect(wrapper.findAll('[data-testid="portal-call"]')).toHaveLength(1)
    const panelCall = fetchMock.mock.calls.find(([url]) => String(url).endsWith('/ai-tool-connections/options'))
    expect((panelCall?.[1]?.headers as Record<string, string>).Authorization).toBe('Bearer sess')
  })

  it('asks the employee to open it from the business system when there is no session', async () => {
    const fetchMock = vi.fn(() => reply([]))
    vi.stubGlobal('fetch', fetchMock)

    const wrapper = mount(EmployeePortal)
    await flushPromises()

    expect(fetchMock).not.toHaveBeenCalled()
    expect(wrapper.find('[data-testid="portal-signed-out"]').exists()).toBe(true)
  })

  it('shows the reason when a used ticket is refused', async () => {
    window.history.replaceState(null, '', '/my/ai-tools#ticket=de0f')
    vi.stubGlobal('fetch', vi.fn(() => reply('链接已失效，请回到业务系统的 AI 对话面板重新打开', 401)))

    const wrapper = mount(EmployeePortal)
    await flushPromises()

    expect(wrapper.text()).toContain('链接已失效')
  })
})
