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
    expect(wrapper.text()).toContain('读取、保存和删除你的个人记忆')
    expect(wrapper.text()).toContain('记忆操作不经过业务写审批')
    expect(wrapper.findAll('[data-testid="call-record"]')).toHaveLength(1)
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

  it('opens 我的记忆 as its own tab with the portal session, layered and grouped', async () => {
    sessionStorage.setItem('reachai.employee-portal.token', 'sess')
    const fetchMock = vi.fn((url: string, init?: RequestInit) => {
      void init
      if (url === '/api/employee-portal/me') return reply(me)
      if (url === '/api/employee-portal/memories/tree') {
        return reply({
          layers: [
            { layer: 'PERSONAL', scope: 'personal', name: '个人记忆', count: 1, groups: [
              { id: 11, name: '偏好', kind: 'PREFERENCE', count: 1 }, { id: 12, name: '备注', kind: 'NOTE', count: 0 }] },
            { layer: 'PROJECT', scope: 'project:yudao-erp', projectCode: 'yudao-erp', name: '芋道 ERP', count: 0, groups: [
              { id: 21, name: '偏好', kind: 'PREFERENCE', count: 0 }] },
          ],
          addableProjects: [],
          total: 1,
        })
      }
      if (url.startsWith('/api/employee-portal/memories?')) {
        return reply({ items: [{
          id: 3, type: 'PREFERENCE', content: '喜欢先看库存', layer: 'PERSONAL', scope: 'personal',
          layerName: '个人记忆', groupId: 11, groupName: '偏好', sourceType: 'AI_TOOL', updatedAt: '2026-10-29T08:00:00',
        }], total: 1, limit: 100, offset: 0 })
      }
      return reply([])
    })
    vi.stubGlobal('fetch', fetchMock)

    const wrapper = mount(EmployeePortal, { global: { stubs: { LiquidGlassButton: { template: '<button><slot /></button>' } } } })
    await flushPromises()
    expect(wrapper.find('[data-testid="portal-memories"]').exists()).toBe(false)

    await wrapper.get('[data-portal-tab="memories"]').trigger('click')
    await flushPromises()

    expect(wrapper.find('[data-testid="portal-memories"]').exists()).toBe(true)
    const memoryCall = fetchMock.mock.calls.find(([url]) => String(url).startsWith('/api/employee-portal/memories?'))
    expect((memoryCall?.[1]?.headers as Record<string, string>).Authorization).toBe('Bearer sess')
    expect(wrapper.text()).toContain('喜欢先看库存')
    expect(wrapper.text()).toContain('个人记忆 · 偏好')
    expect(wrapper.text()).toContain('芋道 ERP')
    expect(wrapper.text()).toContain('AI 工具记录')
    expect(fetchMock.mock.calls.some(([url]) => String(url).includes('/candidates'))).toBe(false)
  })

  it('opens 我的助手 with a chat token minted from the portal session for this page instance', async () => {
    sessionStorage.setItem('reachai.employee-portal.token', 'sess')
    const fetchMock = vi.fn((url: string, init?: RequestInit) => {
      void init
      if (url === '/api/employee-portal/me') return reply(me)
      if (url === '/api/employee-portal/assistant/token') {
        return reply({ token: 'embed-tok', expiresIn: 600, expiresAt: 'x', agentId: 'agent-erp' })
      }
      if (url.startsWith('/api/embed/chat/sessions')) return reply({ sessionId: 'embed-1', agentId: 'agent-erp', principal: {} })
      return reply([])
    })
    vi.stubGlobal('fetch', fetchMock)

    const wrapper = mount(EmployeePortal, { global: { stubs: { LiquidGlassButton: { template: '<button><slot /></button>' } } } })
    await flushPromises()
    expect(fetchMock.mock.calls.some(([url]) => String(url) === '/api/employee-portal/assistant/token')).toBe(false)

    await wrapper.get('[data-portal-tab="assistant"]').trigger('click')
    await flushPromises()

    expect(wrapper.find('[data-testid="employee-assistant"]').exists()).toBe(true)
    const tokenCall = fetchMock.mock.calls.find(([url]) => String(url) === '/api/employee-portal/assistant/token')
    expect((tokenCall?.[1]?.headers as Record<string, string>).Authorization).toBe('Bearer sess')
    expect(JSON.parse(String(tokenCall?.[1]?.body)).pageInstanceId).toMatch(/^page-/)
  })

  it('turns a new credential into an install prompt and lists what the AI can do', async () => {
    sessionStorage.setItem('reachai.employee-portal.token', 'sess')
    const fetchMock = vi.fn((url: string, init?: RequestInit) => {
      if (url === '/api/employee-portal/me') return reply(me)
      if (url.endsWith('/ai-tool-connections/options')) return reply([{ projectCode: 'yudao-erp', name: '芋道 ERP', toolCount: 2, current: true }])
      if (url === '/api/employee-portal/capabilities') {
        return reply([{ projectCode: 'yudao-erp', name: '芋道 ERP', current: true, tools: [
          { name: 'yudao_erp_stock_page', description: '查询库存', readOnly: true, approvalRequired: false },
          { name: 'yudao_erp_customer_create', description: '新建客户', readOnly: false, approvalRequired: true },
        ] }])
      }
      if (url === '/api/employee-portal/ai-tool-connections' && init?.method === 'POST') {
        return reply({
          connection: { id: 5, toolKind: 'CLAUDE_CODE', keyPrefix: 'rai_ab12', state: 'ACTIVE', systems: [{ projectCode: 'yudao-erp', name: '芋道 ERP' }] },
          setup: { endpointUrl: 'https://r/mcp/employee', kind: 'COMMAND', content: 'claude mcp add --transport http reachai https://r/mcp/employee --header "Authorization: Bearer rai_secret"', hint: '在终端运行这一行，然后重新打开 Claude Code。' },
        })
      }
      return reply([])
    })
    vi.stubGlobal('fetch', fetchMock)

    const wrapper = mount(EmployeePortal, { global: { stubs: { LiquidGlassButton: { template: '<button v-bind="$attrs"><slot /></button>' } } } })
    await flushPromises()
    expect(wrapper.text()).toContain('用你习惯的 AI 工具，在已接入的业务系统中查询信息、协助办理业务')
    expect(wrapper.text()).toContain('帮我在芋道 ERP中使用「查询库存」')
    expect(wrapper.text()).not.toContain('帮我在芋道 ERP中使用「新建客户」')
    expect(wrapper.text()).not.toContain('查看最近的订单')
    await wrapper.get('input[type="checkbox"]').setValue(false)
    expect(wrapper.text()).not.toContain('接入后，试着说')
    await wrapper.get('input[type="checkbox"]').setValue(true)
    await wrapper.get('[data-testid="get-install-prompt"]').trigger('click')
    await flushPromises()

    const create = fetchMock.mock.calls.find(([url, init]) => url === '/api/employee-portal/ai-tool-connections' && init?.method === 'POST')
    expect(JSON.parse(String(create?.[1]?.body))).toEqual({ toolKind: 'CLAUDE_CODE', systems: ['yudao-erp'] })
    const prompt = wrapper.get('[data-testid="install-prompt"]').text()
    expect(prompt).toContain('操作「芋道 ERP」')
    expect(prompt).toContain('Bearer rai_secret')
    expect(JSON.stringify(sessionStorage)).not.toContain('rai_secret')

    await wrapper.get('[data-portal-tab="capabilities"]').trigger('click')
    const capabilities = wrapper.get('[data-testid="portal-capabilities"]').text()
    expect(capabilities).toContain('查询库存')
    expect(capabilities).toContain('需要审核')
  })
})
