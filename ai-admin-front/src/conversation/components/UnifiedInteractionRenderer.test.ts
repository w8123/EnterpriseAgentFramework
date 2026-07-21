import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { defineComponent, h } from 'vue'
import UnifiedInteractionRenderer from '../components/UnifiedInteractionRenderer.vue'
import {
  registerCustomInteractionRenderer,
  unregisterCustomInteractionRenderer,
} from '../renderers/interactionRegistry'

describe('UnifiedInteractionRenderer DOM', () => {
  afterEach(() => {
    unregisterCustomInteractionRenderer('host.test-card')
  })

  it('isolates radio name by interactionId', () => {
    const wrapper = mount(UnifiedInteractionRenderer, {
      props: {
        request: {
          schemaVersion: '1.0',
          interactionId: 'ix-radio-1',
          component: 'choice',
          fields: [{
            key: 'opt',
            label: 'Opt',
            type: 'select',
            options: [
              { value: 'a', label: 'A' },
              { value: 'b', label: 'B' },
            ],
          }],
        },
      },
    })
    const radios = wrapper.findAll('input[type="radio"]')
    expect(radios.length).toBe(2)
    expect(radios[0].attributes('name')).toBe('reachai-choice-ix-radio-1')
  })

  it('prefills multi_select and validates required', async () => {
    const wrapper = mount(UnifiedInteractionRenderer, {
      props: {
        request: {
          schemaVersion: '1.0',
          interactionId: 'ix-ms',
          component: 'multi_select',
          fields: [{
            key: 'tags',
            label: 'Tags',
            type: 'multi_select',
            required: true,
            options: [
              { value: 'x', label: 'X' },
              { value: 'y', label: 'Y' },
            ],
          }],
          prefilled: { tags: ['x'] },
        },
      },
    })
    const checks = wrapper.findAll('input[type="checkbox"]')
    expect((checks[0].element as HTMLInputElement).checked).toBe(true)

    const formWrapper = mount(UnifiedInteractionRenderer, {
      props: {
        request: {
          schemaVersion: '1.0',
          interactionId: 'ix-req',
          component: 'form',
          fields: [{ key: 'name', label: '姓名', type: 'string', required: true }],
        },
      },
    })
    await formWrapper.find('form').trigger('submit.prevent')
    expect(formWrapper.text()).toContain('请填写必填项')
  })

  it('renders declared table columns', () => {
    const wrapper = mount(UnifiedInteractionRenderer, {
      props: {
        request: {
          schemaVersion: '1.0',
          interactionId: 'ix-table',
          component: 'table',
          schema: {
            columns: [
              { key: 'name', label: '名称' },
              { key: 'age', label: '年龄' },
            ],
          },
          data: [{ name: 'Alice', age: 18, extra: 'hidden-prefer-declared' }],
        },
      },
    })
    expect(wrapper.text()).toContain('名称')
    expect(wrapper.text()).toContain('年龄')
    expect(wrapper.text()).toContain('Alice')
  })

  it('renders schema-driven list cards from a paged response and expands them', async () => {
    const wrapper = mount(UnifiedInteractionRenderer, {
      props: {
        request: {
          schemaVersion: '1.0',
          interactionId: 'ix-list-card',
          component: 'list_card',
          title: '查询结果',
          data: {
            total: 10,
            records: [
              { id: 't1', teamName: '一组', managerName: '张三', enabled: true },
              { id: 't2', teamName: '二组', managerName: '李四', enabled: false },
            ],
          },
          schema: {
            itemKey: 'id',
            titleField: 'teamName',
            initialVisibleCount: 1,
            status: {
              field: 'enabled',
              labels: { true: '已启用', false: '已停用' },
              tones: { true: 'success', false: 'neutral' },
            },
            fields: [{ key: 'managerName', label: '负责人' }],
          },
        },
      },
    })

    expect(wrapper.text()).toContain('共 10 条')
    expect(wrapper.text()).toContain('一组')
    expect(wrapper.text()).toContain('负责人')
    expect(wrapper.text()).toContain('张三')
    expect(wrapper.text()).toContain('已启用')
    expect(wrapper.text()).not.toContain('二组')
    expect(wrapper.find('.reachai-interaction__status-chip').classes()).toContain('is-success')

    await wrapper.find('.reachai-interaction__list-toggle').trigger('click')
    expect(wrapper.text()).toContain('二组')
    expect(wrapper.text()).toContain('李四')
    expect(wrapper.text()).toContain('收起')
  })

  it('mounts registered custom renderer into DOM', () => {
    registerCustomInteractionRenderer('host.test-card', () => defineComponent({
      name: 'HostTestCard',
      setup() {
        return () => h('div', { class: 'host-test-card' }, 'CUSTOM_OK')
      },
    }))
    const wrapper = mount(UnifiedInteractionRenderer, {
      props: {
        request: {
          schemaVersion: '1.0',
          interactionId: 'ix-custom',
          component: 'custom',
          extension: { rendererKey: 'host.test-card' },
        },
      },
    })
    expect(wrapper.find('.host-test-card').exists()).toBe(true)
    expect(wrapper.text()).toContain('CUSTOM_OK')
  })

  it('hides actions for readonly display cards', () => {
    const wrapper = mount(UnifiedInteractionRenderer, {
      props: {
        request: {
          schemaVersion: '1.0',
          interactionId: 'ix-ro',
          component: 'table',
          data: [{ a: 1 }],
        },
      },
    })
    expect(wrapper.findAll('button').length).toBe(0)
  })
})
