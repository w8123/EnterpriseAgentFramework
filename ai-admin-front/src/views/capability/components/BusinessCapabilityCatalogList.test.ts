import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import { describe, expect, it } from 'vitest'
import BusinessCapabilityCatalogList from './BusinessCapabilityCatalogList.vue'

const defaults = { loading: false, composing: false, currentPage: 1,
  pageSize: 20, total: 2, empty: false, hasFilters: false, emptyDescription: '没有结果', emptyHint: '先接入项目' }
const render = (props = {}) => mount(BusinessCapabilityCatalogList, { props: { ...defaults, ...props },
  slots: { filters: '<input aria-label="搜索方法" />', default: '<table><tbody><tr><td>查询字典子项</td></tr></tbody></table>' },
  global: { plugins: [[ElementPlus, { locale: zhCn }]] } })

describe('business capability catalog controls', () => {
  it('submits search without exposing reset or refresh actions, including filtered empty results', async () => {
    const wrapper = render()
    expect(wrapper.findAll('.filter-bar__actions button').map(button => button.text())).toEqual(['搜索'])
    await wrapper.get('form').trigger('submit')
    expect(wrapper.emitted('query')).toHaveLength(1)
    await wrapper.setProps({ hasFilters: true, empty: true })
    expect(wrapper.findAll('.filter-bar__actions button').map(button => button.text())).toEqual(['搜索'])
    expect(wrapper.text()).not.toContain('清空筛选')
    expect(wrapper.emitted('reset')).toBeUndefined()
    expect(wrapper.emitted('refresh')).toBeUndefined()
    wrapper.unmount()
  })

  it('keeps project-list pagination visible for one page and enables navigation when another page is reachable', async () => {
    const wrapper = render()
    expect(wrapper.text()).toContain('共 2 条')
    expect(wrapper.find('.el-pagination__jump').exists()).toBe(false)
    expect(wrapper.get('.el-pagination').classes()).toContain('is-background')
    expect(wrapper.get('.btn-next').attributes('disabled')).toBeDefined()
    expect(wrapper.find('.el-pagination__sizes').exists()).toBe(true)
    await wrapper.setProps({ total: 40 })
    expect(wrapper.find('.el-pagination__jump').exists()).toBe(false)
    expect(wrapper.get('.btn-next').attributes('disabled')).toBeUndefined()
    await wrapper.get('.btn-next').trigger('click')
    expect(wrapper.emitted('pageChange')).toEqual([[2]])
    await wrapper.setProps({ total: 2, currentPage: 2 })
    expect(wrapper.find('.btn-prev').exists()).toBe(true)
    wrapper.unmount()
  })
})
