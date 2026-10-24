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
})
