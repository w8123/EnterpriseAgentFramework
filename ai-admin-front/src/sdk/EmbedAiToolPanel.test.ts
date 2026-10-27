import { afterEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import EmbedAiToolPanel from './EmbedAiToolPanel.vue'

function ok(data: unknown) {
  return Promise.resolve(new Response(JSON.stringify({ code: 200, data }), { status: 200 }))
}

const options = [
  { projectCode: 'yudao-erp', name: '芋道 ERP', toolCount: 12, current: true },
  { projectCode: 'crm', name: '客户管理', toolCount: 3, current: false },
]

describe('EmbedAiToolPanel', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('preselects the current system and shows the one-line setup after creating', async () => {
    const fetchMock = vi.fn((url: string, init?: RequestInit) => {
      if (url.endsWith('/options')) return ok(options)
      if (init?.method === 'POST') {
        return ok({
          connection: { id: 5, toolKind: 'CLAUDE_CODE', state: 'ACTIVE', systems: [] },
          setup: {
            endpointUrl: 'https://reachai.example.com/mcp/employee',
            kind: 'COMMAND',
            content: 'claude mcp add --transport http reachai https://reachai.example.com/mcp/employee',
            hint: '在终端里运行这一行',
          },
        })
      }
      return ok([])
    })
    vi.stubGlobal('fetch', fetchMock)

    const wrapper = mount(EmbedAiToolPanel, {
      props: { apiBase: '/api/embed', getToken: () => Promise.resolve('tok') },
    })
    await flushPromises()

    const [firstUrl, firstInit] = fetchMock.mock.calls[0]
    expect(firstUrl).toBe('/api/embed/ai-tool-connections/options')
    expect((firstInit?.headers as Record<string, string>).Authorization).toBe('Bearer tok')
    const boxes = wrapper.findAll<HTMLInputElement>('input[type="checkbox"]')
    expect(boxes.map((box) => box.element.checked)).toEqual([true, false])

    await wrapper.find('.eaf-aitool__primary').trigger('click')
    await flushPromises()

    const post = fetchMock.mock.calls.find(([, init]) => init?.method === 'POST')
    expect(JSON.parse(String(post?.[1]?.body))).toEqual({ toolKind: 'CLAUDE_CODE', systems: ['yudao-erp'] })
    expect(wrapper.find('[data-testid="setup-content"]').text()).toContain('claude mcp add')
    expect(wrapper.text()).toContain('只显示一次')
  })

  it('disconnects an active connection', async () => {
    let mine = [{
      id: 5, toolKind: 'CODEX', keyPrefix: 'rai_abcdef12', state: 'ACTIVE',
      systems: [{ projectCode: 'yudao-erp', name: '芋道 ERP' }],
      createdAt: '2026-10-24T10:00:00', lastUsedAt: null, expiresAt: '2027-01-22T10:00:00',
    }]
    const fetchMock = vi.fn((url: string, init?: RequestInit) => {
      if (url.endsWith('/options')) return ok(options)
      if (url.endsWith('/approvals')) return ok([])
      if (init?.method === 'DELETE') {
        mine = mine.map((item) => ({ ...item, state: 'REVOKED' }))
        return ok(null)
      }
      return ok(mine)
    })
    vi.stubGlobal('fetch', fetchMock)

    const wrapper = mount(EmbedAiToolPanel, {
      props: { apiBase: '/api/embed', getToken: () => Promise.resolve('tok') },
    })
    await flushPromises()
    expect(wrapper.text()).toContain('Codex')

    await wrapper.find('.eaf-aitool__item .danger').trigger('click')
    await flushPromises()

    expect(fetchMock).toHaveBeenCalledWith('/api/embed/ai-tool-connections/5', expect.objectContaining({ method: 'DELETE' }))
    expect(wrapper.find('.eaf-aitool__item .danger').exists()).toBe(false)
    expect(wrapper.text()).toContain('已断开')
  })

  it('shows the server message when loading fails', async () => {
    vi.stubGlobal('fetch', vi.fn(() => Promise.resolve(
      new Response(JSON.stringify({ code: 403, message: '账号已停用' }), { status: 403 }))))

    const wrapper = mount(EmbedAiToolPanel, {
      props: { apiBase: '/api/embed', getToken: () => Promise.resolve('tok') },
    })
    await flushPromises()

    expect(wrapper.find('[role="alert"]').text()).toBe('账号已停用')
  })

  it('lists the employee\'s write approvals with their outcome', async () => {
    const fetchMock = vi.fn((url: string) => {
      if (url.endsWith('/options')) return ok(options)
      if (url.endsWith('/approvals')) {
        return ok([
          { approvalId: 3, approvalStatus: 'PENDING', tool: 'erpCustomerCreate', businessSystem: '芋道 ERP' },
          { approvalId: 2, approvalStatus: 'REJECTED', tool: 'erpCustomerCreate', businessSystem: '芋道 ERP', comment: '客户名不规范' },
        ])
      }
      return ok([])
    })
    vi.stubGlobal('fetch', fetchMock)

    const wrapper = mount(EmbedAiToolPanel, {
      props: { apiBase: '/api/embed', getToken: () => Promise.resolve('tok') },
    })
    await flushPromises()

    const items = wrapper.findAll('[data-testid="approval-item"]')
    expect(items).toHaveLength(2)
    expect(items[0].text()).toContain('等待审批')
    expect(items[1].text()).toContain('已驳回')
    expect(items[1].text()).toContain('客户名不规范')
  })

  it('still works against a ReachAI without write approval', async () => {
    vi.stubGlobal('fetch', vi.fn((url: string) => {
      if (url.endsWith('/options')) return ok(options)
      if (url.endsWith('/approvals')) return Promise.resolve(new Response('{}', { status: 404 }))
      return ok([])
    }))

    const wrapper = mount(EmbedAiToolPanel, {
      props: { apiBase: '/api/embed', getToken: () => Promise.resolve('tok') },
    })
    await flushPromises()

    expect(wrapper.find('.eaf-aitool__error').exists()).toBe(false)
    expect(wrapper.findAll('input[type="checkbox"]')).toHaveLength(2)
  })

  it('lists ready-made skills and downloads one with the embed token', async () => {
    const fetchMock = vi.fn((url: string) => {
      if (url.endsWith('/options')) return ok(options)
      if (url.endsWith('/skills')) {
        return ok([{ skillId: 4, name: 'yudao-erp-create-customer', displayName: '在芋道 ERP 中新建客户',
          description: '新建客户的做法', projectCode: 'yudao-erp', systemName: '芋道 ERP', version: '1.0.0' }])
      }
      if (url.endsWith('/skills/4/package')) {
        return Promise.resolve(new Response(new Blob(['zip']), { status: 200 }))
      }
      return ok([])
    })
    vi.stubGlobal('fetch', fetchMock)
    const createObjectURL = vi.fn(() => 'blob:skill')
    vi.stubGlobal('URL', Object.assign(URL, { createObjectURL, revokeObjectURL: vi.fn() }))

    const wrapper = mount(EmbedAiToolPanel, {
      props: { apiBase: '/api/embed', getToken: () => Promise.resolve('tok') },
    })
    await flushPromises()

    const item = wrapper.find('[data-testid="skill-item"]')
    expect(item.text()).toContain('在芋道 ERP 中新建客户')
    expect(wrapper.text()).toContain('~/.claude/skills/')
    await item.find('button').trigger('click')
    await flushPromises()

    const download = fetchMock.mock.calls.find(([url]) => String(url).endsWith('/skills/4/package')) as unknown as [string, RequestInit]
    expect((download[1].headers as Record<string, string>).Authorization).toBe('Bearer tok')
    expect(createObjectURL).toHaveBeenCalled()
  })

  it('opens the personal page in a new tab with a one-time link', async () => {
    const tab = { opener: {}, location: { href: '' }, close: vi.fn() }
    vi.stubGlobal('open', vi.fn(() => tab))
    const fetchMock = vi.fn((url: string, init?: RequestInit) => {
      if (url.endsWith('/portal-ticket') && init?.method === 'POST') {
        return ok({ url: 'https://reachai.example.com/my/ai-tools#ticket=abc', expiresAt: 'x' })
      }
      if (url.endsWith('/options')) return ok(options)
      return ok([])
    })
    vi.stubGlobal('fetch', fetchMock)

    const wrapper = mount(EmbedAiToolPanel, {
      props: { apiBase: '/api/embed', getToken: () => Promise.resolve('tok') },
    })
    await flushPromises()
    await wrapper.find('[data-testid="open-portal"]').trigger('click')
    await flushPromises()

    expect(tab.location.href).toBe('https://reachai.example.com/my/ai-tools#ticket=abc')
    expect(tab.opener).toBeNull()
  })

  it('hides the chat-only controls on the personal page', async () => {
    vi.stubGlobal('fetch', vi.fn((url: string) => (url.endsWith('/options') ? ok(options) : ok([]))))

    const wrapper = mount(EmbedAiToolPanel, {
      props: { apiBase: '/api/employee-portal', getToken: () => Promise.resolve('sess'), page: true },
    })
    await flushPromises()

    expect(wrapper.find('.eaf-aitool__close').exists()).toBe(false)
    expect(wrapper.find('[data-testid="open-portal"]').exists()).toBe(false)
  })
})
