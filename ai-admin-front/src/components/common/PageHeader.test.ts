import { afterEach, describe, expect, it, vi } from 'vitest'
import { mount, type VueWrapper } from '@vue/test-utils'
import { defineComponent, ref } from 'vue'
import ElementPlus from 'element-plus'
import PageHeader from './PageHeader.vue'
import PageHeaderTags from './PageHeaderTags.vue'

let wrapper: VueWrapper | undefined
const global = { plugins: [ElementPlus], stubs: { LiquidGlassLayer: true } }
afterEach(() => { wrapper?.unmount(); wrapper = undefined })

describe('shared PageHeader metadata', () => {
  it('places reactive tags after the description without including them in the page heading', async () => {
    const count = ref(19)
    const action = vi.fn()
    wrapper = mount(defineComponent({
      components: { PageHeader },
      setup: () => ({ count, action }),
      template: `<PageHeader title="Agent 管理" description="管理智能体" presentation="plain" action-presentation="inline">
        <template #tags><el-tag>范围：全部 Agent</el-tag><el-tag type="success">当前 {{ count }} 个</el-tag></template>
        <template #actions><el-button @click="action">新建智能体</el-button></template>
      </PageHeader>`,
    }), { global })
    expect(wrapper.get('h1').text()).toBe('Agent 管理')
    expect(wrapper.get('.app-page-header__title-row').findComponent(PageHeaderTags).exists()).toBe(false)
    expect(wrapper.get('.app-page-header__description').element.nextElementSibling).toBe(wrapper.get('[role="group"]').element)
    expect(wrapper.get('[role="group"]').attributes('aria-label')).toBe('页面信息')
    count.value = 20
    await wrapper.vm.$nextTick()
    expect(wrapper.get('[role="group"]').text()).toContain('当前 20 个')
    await wrapper.get('button').trigger('click')
    expect(action).toHaveBeenCalledOnce()
  })

  it('makes tag controls inert while the header is collapsed and restores them when expanded', async () => {
    wrapper = mount(PageHeader, {
      global, props: { title: '项目详情', collapsed: true },
      slots: { tags: '<button type="button">项目范围</button>' },
    })
    const group = wrapper.get('[role="group"]')
    expect(group.attributes('aria-hidden')).toBe('true')
    expect(group.attributes('inert')).toBeDefined()
    await wrapper.setProps({ collapsed: false })
    expect(group.attributes('aria-hidden')).toBe('false')
    expect(group.attributes('inert')).toBeUndefined()
    expect(group.get('button').text()).toBe('项目范围')
  })

  it('preserves native tag close events in a standalone group', async () => {
    const close = vi.fn()
    wrapper = mount(defineComponent({
      components: { PageHeaderTags }, setup: () => ({ close }),
      template: '<PageHeaderTags><el-tag closable @close="close">当前项目</el-tag></PageHeaderTags>',
    }), { global })
    await wrapper.get('.el-tag__close').trigger('click')
    expect(close).toHaveBeenCalledOnce()
  })
})
