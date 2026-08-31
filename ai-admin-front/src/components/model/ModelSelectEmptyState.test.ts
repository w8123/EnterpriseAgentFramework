import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import ModelSelectEmptyState from './ModelSelectEmptyState.vue'

const resolve = vi.fn()

vi.mock('vue-router', () => ({
  useRouter: () => ({ resolve }),
}))

describe('ModelSelectEmptyState', () => {
  beforeEach(() => {
    resolve.mockReset()
    resolve.mockReturnValue({ href: '/model/instances?create=1&modelType=EMBEDDING' })
  })

  it('shows a type-specific creation link for a truly empty option list', () => {
    const wrapper = mount(ModelSelectEmptyState, {
      props: {
        modelType: 'EMBEDDING',
        optionCount: 0,
      },
      global: {
        stubs: { 'el-icon': true },
      },
    })

    expect(wrapper.text()).toContain('暂无可用的 Embedding 模型实例')
    expect(wrapper.text()).toContain('前往模型中心接入')
    expect(wrapper.get('a').attributes('href')).toBe('/model/instances?create=1&modelType=EMBEDDING')
    expect(wrapper.get('a').attributes('aria-label')).toBe('前往模型中心接入 Embedding 模型')
    expect(wrapper.get('a').attributes('target')).toBe('_blank')
    expect(resolve).toHaveBeenCalledWith({
      name: 'ModelInstances',
      query: { create: '1', modelType: 'EMBEDDING' },
    })
  })

  it('does not suggest creation when filtering an existing option list has no match', () => {
    const wrapper = mount(ModelSelectEmptyState, {
      props: {
        modelType: 'LLM',
        optionCount: 3,
      },
      global: {
        stubs: { 'el-icon': true },
      },
    })

    expect(wrapper.text()).toContain('没有匹配的模型实例')
    expect(wrapper.find('a').exists()).toBe(false)
  })

  it('shows retry instead of creation when loading failed', async () => {
    const wrapper = mount(ModelSelectEmptyState, {
      props: {
        modelType: 'RERANKER',
        optionCount: 0,
        loadError: true,
      },
      global: {
        stubs: { 'el-icon': true },
      },
    })

    expect(wrapper.text()).toContain('模型列表加载失败')
    expect(wrapper.find('a').exists()).toBe(false)
    await wrapper.get('button').trigger('click')
    expect(wrapper.emitted('retry')).toHaveLength(1)
  })
})
