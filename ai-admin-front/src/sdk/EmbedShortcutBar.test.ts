import { afterEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import EmbedShortcutBar from './EmbedShortcutBar.vue'

function ok(data: unknown) {
  return Promise.resolve(new Response(JSON.stringify({ code: 200, data }), { status: 200 }))
}

describe('EmbedShortcutBar', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('shows the shortcuts and tips for the current page and sends the prompt on click', async () => {
    const fetchMock = vi.fn((url: string, _init?: RequestInit) => {
      if (url.includes('/used')) return ok({ counted: true })
      return ok({
        page: '/erp/customer',
        shortcuts: [{ id: 5, kind: 'FLOW', title: '新增客户并开单', prompt: '帮我新增一个客户（填写客户名称）', forThisPage: true }],
        tips: [{ id: 6, title: '新增客户容易失败', advice: '先填写客户名称' }],
      })
    })
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(EmbedShortcutBar, {
      props: { apiBase: 'https://ai.example.com/api/embed', getToken: async () => 'tok', route: '/erp/customer' },
    })
    await flushPromises()
    expect(fetchMock.mock.calls[0][0]).toBe('https://ai.example.com/api/embed/activity/shortcuts?route=%2Ferp%2Fcustomer')
    expect((fetchMock.mock.calls[0][1] as RequestInit).headers).toEqual({ Authorization: 'Bearer tok' })
    expect(wrapper.text()).toContain('先填写客户名称')
    await wrapper.get('.eaf-shortcuts__chip').trigger('click')
    await flushPromises()
    expect(wrapper.emitted('pick')?.[0]).toEqual(['帮我新增一个客户（填写客户名称）'])
    expect(fetchMock.mock.calls[1][0]).toBe('https://ai.example.com/api/embed/activity/shortcuts/5/used')
  })

  it('stays hidden when nothing is recommended or the request fails', async () => {
    vi.stubGlobal('fetch', vi.fn(() => Promise.reject(new TypeError('offline'))))
    const wrapper = mount(EmbedShortcutBar, {
      props: { apiBase: '/api/embed', getToken: async () => 'tok', route: '/x' },
    })
    await flushPromises()
    expect(wrapper.find('.eaf-shortcuts').exists()).toBe(false)
  })

  it('reloads when the employee moves to another page', async () => {
    const fetchMock = vi.fn((_url: string) => ok({ shortcuts: [], tips: [] }))
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(EmbedShortcutBar, {
      props: { apiBase: '/api/embed', getToken: async () => 'tok', route: '/a' },
    })
    await flushPromises()
    await wrapper.setProps({ route: '/b' })
    await flushPromises()
    expect(fetchMock).toHaveBeenCalledTimes(2)
    expect(String(fetchMock.mock.calls[1][0])).toContain('route=%2Fb')
  })
})
