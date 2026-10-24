import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { describe, expect, it } from 'vitest'
import StudioHeader from './StudioHeader.vue'

const baseProps = {
  displayName: '产品库存查询', visibilityLabel: '已发布 v1.0.0', validationLabel: '通过',
  validationTagType: 'success' as const, keySlug: 'product-stock-query', projectCode: 'yudao-erp',
  saveStateClass: {}, saveBadgeText: '已发布 v1.0.0', readOnly: true, debugLoading: false,
  readOnlyTrial: false, validating: false, saving: false, releaseChecking: false,
  showPageAssistantSync: false, pageAssistantSyncing: false,
}

function render(extra: Record<string, unknown> = {}) {
  return mount(StudioHeader, { props: { ...baseProps, ...extra }, global: { plugins: [ElementPlus] } })
}

describe('StudioHeader 只读角色', () => {
  it('只读查看时不显示保存、发布和调试，只留校验并标出「只读查看」', () => {
    const wrapper = render({ viewOnly: true, hideRun: true })
    expect(wrapper.text()).toContain('只读查看')
    expect(wrapper.find('.studio-validate-button').exists()).toBe(true)
    expect(wrapper.find('.studio-save-button').exists()).toBe(false)
    expect(wrapper.find('.studio-publish-button').exists()).toBe(false)
    expect(wrapper.find('.studio-run-button').exists()).toBe(false)
  })

  it('SDK 定义等只读草稿仍显示禁用的保存与发布', () => {
    const wrapper = render()
    expect(wrapper.text()).not.toContain('只读查看')
    expect(wrapper.find('.studio-save-button').attributes('disabled')).toBeDefined()
  })
})
