import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import McpHubOverview from './McpHubOverview.vue'

const mocks = vi.hoisted(() => ({
  getMcpHubOverview: vi.fn(),
  push: vi.fn(),
}))

vi.mock('vue-router', () => ({
  useRouter: () => ({ push: mocks.push }),
}))

vi.mock('@/api/mcp', () => ({
  getMcpHubOverview: (...args: unknown[]) => mocks.getMcpHubOverview(...args),
}))

const wrappers: VueWrapper[] = []

function mountOverview() {
  const wrapper = mount(McpHubOverview, {
    global: {
      directives: {
        loading: {},
      },
      stubs: {
        ElAlert: {
          props: ['title', 'description'],
          template: '<section class="el-alert"><strong>{{ title }}</strong><span>{{ description }}</span><slot /></section>',
        },
        ElButton: {
          emits: ['click'],
          template: '<button class="el-button" @click="$emit(\'click\')"><slot /></button>',
        },
        ElIcon: {
          template: '<i class="el-icon"><slot /></i>',
        },
        ElRadioGroup: {
          template: '<div class="el-radio-group"><slot /></div>',
        },
        ElRadioButton: {
          template: '<button class="el-radio-button"><slot /></button>',
        },
        ElSkeleton: {
          template: '<div class="el-skeleton" />',
        },
      },
    },
  })
  wrappers.push(wrapper)
  return wrapper
}

describe('MCP Hub 总览', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  afterEach(() => {
    wrappers.splice(0).forEach((wrapper) => wrapper.unmount())
  })

  it('没有发布单元时展示插画引导，不渲染空指标矩阵', async () => {
    mocks.getMcpHubOverview.mockResolvedValue({
      data: {
        publications: 0,
        publishedPublications: 0,
        activeClients: 0,
        expiringCredentials: 0,
        outboundCalls: 0,
        outboundSuccessRate: 1,
        outboundP95Ms: null,
        inboundCalls: 0,
      },
    })

    const wrapper = mountOverview()
    await flushPromises()

    expect(wrapper.find('.onboarding-card').exists()).toBe(true)
    expect(wrapper.get('.onboarding-visual img').attributes('alt')).toContain('安全 MCP 网关')
    expect(wrapper.get('.onboarding-visual img').attributes('src')).toContain('mcp-interconnection-onboarding')
    expect(wrapper.findAll('.metric-card')).toHaveLength(0)
    expect(wrapper.text()).toContain('发布第一个 MCP 服务')
    expect(wrapper.text()).toContain('选择能力或已发布 Workflow')
    expect(wrapper.text()).toContain('Client 凭证')
    expect(wrapper.text()).not.toContain('入向调用量')

    await wrapper.get('.onboarding-actions .el-button').trigger('click')
    expect(mocks.push).toHaveBeenCalledWith('/mcp-hub/publications')
  })

  it('已有草稿但没有调用时仅展示四项核心指标且不伪造成功率', async () => {
    mocks.getMcpHubOverview.mockResolvedValue({
      data: {
        publications: 1,
        publishedPublications: 0,
        activeClients: 0,
        expiringCredentials: 0,
        outboundCalls: 0,
        outboundSuccessRate: 1,
        outboundP95Ms: null,
        inboundCalls: 0,
      },
    })

    const wrapper = mountOverview()
    await flushPromises()

    expect(wrapper.find('.onboarding-card').exists()).toBe(false)
    expect(wrapper.findAll('.metric-card')).toHaveLength(4)
    expect(wrapper.text()).toContain('调用成功率')
    expect(wrapper.text()).toContain('—')
    expect(wrapper.text()).toContain('P95 暂无样本')
    expect(wrapper.text()).toContain('继续完成第一个发布')
    expect(wrapper.text()).not.toContain('100.0%')
  })

  it('有真实调用时格式化核心指标并突出临期凭证', async () => {
    mocks.getMcpHubOverview.mockResolvedValue({
      data: {
        publications: 3,
        publishedPublications: 2,
        activeClients: 4,
        expiringCredentials: 1,
        outboundCalls: 1234,
        outboundSuccessRate: 0.985,
        outboundP95Ms: 180,
        inboundCalls: 0,
      },
    })

    const wrapper = mountOverview()
    await flushPromises()

    expect(wrapper.findAll('.metric-card')).toHaveLength(4)
    expect(wrapper.text()).toContain('1,234')
    expect(wrapper.text()).toContain('98.5%')
    expect(wrapper.text()).toContain('P95 180 ms')
    expect(wrapper.text()).toContain('1 个即将过期')
    expect(wrapper.find('.next-step-panel').exists()).toBe(false)
  })
})
