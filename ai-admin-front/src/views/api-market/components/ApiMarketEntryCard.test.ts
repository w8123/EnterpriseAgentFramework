import { afterEach, describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import ApiMarketEntryCard from './ApiMarketEntryCard.vue'
import type { ApiMarketEntrySummary } from '@/types/apiMarket'

const entry: ApiMarketEntrySummary = {
  id: 1, entryKey: 'weather', title: '天气查询', summary: '实时天气与预报', categoryCode: 'WEATHER', tags: [],
  authType: 'NONE', pricingType: 'FREE', httpsSupported: true, publicationStatus: 'PUBLISHED',
  verificationStatus: 'VERIFIED', specStatus: 'NONE', featured: false, popularityScore: 0,
}
let wrapper: ReturnType<typeof mount> | undefined
afterEach(() => { wrapper?.unmount(); wrapper = undefined })

describe('API market card keyboard actions', () => {
  it('opens the focused card while keeping nested actions independent', async () => {
    wrapper = mount(ApiMarketEntryCard, { props: { entry }, global: { plugins: [ElementPlus], stubs: { LiquidGlassLayer: true } } })
    await wrapper.get('article').trigger('keydown', { key: 'Enter' })
    expect(wrapper.emitted('open')).toEqual([[entry]])
    const [view, integrate] = wrapper.findAll('button')
    await integrate!.trigger('keydown', { key: 'Enter' })
    expect(wrapper.emitted('open')).toHaveLength(1)
    await integrate!.trigger('click')
    expect(wrapper.emitted('integrate')).toEqual([[entry]])
    expect(wrapper.emitted('open')).toHaveLength(1)
    await view!.trigger('click')
    expect(wrapper.emitted('open')).toEqual([[entry], [entry]])
  })
})
