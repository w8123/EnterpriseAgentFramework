import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import McpCallLogList from './McpCallLogList.vue'

const mocks = vi.hoisted(() => ({
  getMcpCallLog: vi.fn(),
  listMcpCallLogs: vi.fn(),
  listMcpClients: vi.fn(),
  listMcpPublications: vi.fn(),
}))

vi.mock('@/api/mcp', () => ({
  getMcpCallLog: (...args: unknown[]) => mocks.getMcpCallLog(...args),
  listMcpCallLogs: (...args: unknown[]) => mocks.listMcpCallLogs(...args),
  listMcpClients: (...args: unknown[]) => mocks.listMcpClients(...args),
  listMcpPublications: (...args: unknown[]) => mocks.listMcpPublications(...args),
}))

const wrappers: VueWrapper[] = []

function mountCallLogs() {
  const wrapper = mount(McpCallLogList, {
    global: {
      directives: {
        loading: {},
      },
      stubs: {
        MetricStrip: {
          props: ['items'],
          template: `
            <section class="metric-strip-stub">
              <article v-for="item in items" :key="item.key">
                <span>{{ item.label }}</span><strong>{{ item.value }}</strong><small>{{ item.hint }}</small>
              </article>
            </section>
          `,
        },
        AppDialog: {
          props: ['modelValue'],
          template: '<section v-if="modelValue" class="app-dialog-stub"><slot /></section>',
        },
        ElCard: {
          template: '<section class="el-card"><slot /></section>',
        },
        ElInput: {
          props: ['modelValue', 'placeholder'],
          template: '<input :value="modelValue" :placeholder="placeholder" />',
        },
        ElSelect: {
          template: '<select><slot /></select>',
        },
        ElOption: {
          template: '<option />',
        },
        ElButton: {
          inheritAttrs: false,
          emits: ['click'],
          template: '<button v-bind="$attrs" @click="$emit(\'click\')"><slot /></button>',
        },
        ElTooltip: {
          template: '<span><slot /></span>',
        },
        ElCollapseTransition: {
          template: '<div><slot /></div>',
        },
        ElAlert: {
          props: ['title'],
          template: '<div class="el-alert">{{ title }}</div>',
        },
        ElTable: {
          props: ['data'],
          template: '<div class="el-table-stub"><slot v-if="data.length" /><slot v-else name="empty" /></div>',
        },
        ElTableColumn: {
          template: '<div class="el-table-column-stub" />',
        },
        ElPagination: {
          template: '<div class="el-pagination-stub" />',
        },
        ElIcon: {
          template: '<i class="el-icon"><slot /></i>',
        },
        ElTag: {
          template: '<span class="el-tag"><slot /></span>',
        },
        ElDescriptions: {
          template: '<div class="el-descriptions"><slot /></div>',
        },
        ElDescriptionsItem: {
          template: '<div class="el-descriptions-item"><slot /></div>',
        },
      },
    },
  })
  wrappers.push(wrapper)
  return wrapper
}

describe('MCP 调用流水', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mocks.listMcpPublications.mockResolvedValue({ data: { items: [], total: 0 } })
    mocks.listMcpClients.mockResolvedValue({ data: [] })
  })

  afterEach(() => {
    wrappers.splice(0).forEach((wrapper) => wrapper.unmount())
  })

  it('空数据时展示项目管理式指标、单行筛选和引导空状态', async () => {
    mocks.listMcpCallLogs.mockResolvedValue({
      data: { items: [], total: 0, limit: 50, offset: 0 },
    })

    const wrapper = mountCallLogs()
    await flushPromises()

    expect(wrapper.findAll('.metric-strip-stub article')).toHaveLength(4)
    expect(wrapper.text()).toContain('匹配流水')
    expect(wrapper.text()).toContain('当前页成功')
    expect(wrapper.text()).toContain('当前页失败')
    expect(wrapper.text()).toContain('当前页 P95')
    expect(wrapper.get('.call-log-empty').text()).toContain('还没有调用流水')
    expect(wrapper.find('.table-footer').exists()).toBe(false)
    expect(wrapper.find('.advanced-filters').exists()).toBe(false)

    await wrapper.get('[aria-label="更多筛选"]').trigger('click')
    expect(wrapper.find('.advanced-filters').exists()).toBe(true)
  })

  it('根据当前页数据计算成功、失败和 P95，并在有结果时显示分页', async () => {
    mocks.listMcpCallLogs.mockResolvedValue({
      data: {
        total: 12,
        limit: 50,
        offset: 0,
        items: [
          { id: 1, success: true, latencyMs: 40 },
          { id: 2, success: false, latencyMs: 120 },
        ],
      },
    })

    const wrapper = mountCallLogs()
    await flushPromises()

    const metrics = wrapper.get('.metric-strip-stub').text()
    expect(metrics).toContain('匹配流水12')
    expect(metrics).toContain('当前页成功150%')
    expect(metrics).toContain('当前页失败1建议排查')
    expect(metrics).toContain('当前页 P95120 ms')
    expect(wrapper.get('.table-footer').text()).toContain('共 12 条')
  })
})
