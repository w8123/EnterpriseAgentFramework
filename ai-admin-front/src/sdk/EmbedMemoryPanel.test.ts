import { afterEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import EmbedMemoryPanel from './EmbedMemoryPanel.vue'

function ok(data: unknown) {
  return Promise.resolve(new Response(JSON.stringify({ code: 200, data }), { status: 200 }))
}

describe('EmbedMemoryPanel', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('lists the end user own memories with the embed token and deletes one', async () => {
    let items = [{ id: 3, type: 'PREFERENCE', content: '默认用中文回答', sourceAgentId: 'erp-assistant' }]
    const fetchMock = vi.fn((url: string, init?: RequestInit) => {
      if (init?.method === 'DELETE') {
        items = []
        return ok({ id: 3 })
      }
      if (url.includes('/candidates')) return ok({ items: [], total: 0 })
      return ok({ items, total: items.length })
    })
    vi.stubGlobal('fetch', fetchMock)

    const wrapper = mount(EmbedMemoryPanel, {
      props: { apiBase: '/api/embed', getToken: () => Promise.resolve('tok') },
    })
    await flushPromises()

    expect(wrapper.text()).toContain('默认用中文回答')
    const [firstUrl, firstInit] = fetchMock.mock.calls[0]
    expect(firstUrl).toMatch(/^\/api\/embed\/memories/)
    expect((firstInit?.headers as Record<string, string>).Authorization).toBe('Bearer tok')

    await wrapper.find('.eaf-memory__item .danger').trigger('click')
    await flushPromises()

    expect(fetchMock).toHaveBeenCalledWith('/api/embed/memories/3', expect.objectContaining({ method: 'DELETE' }))
    expect(wrapper.text()).not.toContain('默认用中文回答')
  })

  it('shows the server message when an action fails', async () => {
    vi.stubGlobal('fetch', vi.fn(() => Promise.resolve(
      new Response(JSON.stringify({ code: 401, message: 'embed token expired' }), { status: 401 }))))

    const wrapper = mount(EmbedMemoryPanel, {
      props: { apiBase: '/api/embed', getToken: () => Promise.resolve('tok') },
    })
    await flushPromises()

    expect(wrapper.find('[role="alert"]').text()).toBe('embed token expired')
  })
})
