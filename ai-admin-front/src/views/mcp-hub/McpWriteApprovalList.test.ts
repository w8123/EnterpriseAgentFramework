import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import McpWriteApprovalList from './McpWriteApprovalList.vue'

const mocks = vi.hoisted(() => ({
  list: vi.fn(),
  get: vi.fn(),
  approve: vi.fn(),
  reject: vi.fn(),
  canDecide: true,
  query: {} as Record<string, string>,
}))

vi.mock('@/auth/usePlatformCan', () => ({ usePlatformCan: () => ({ can: () => mocks.canDecide }) }))
vi.mock('@/api/mcp', () => ({
  listMcpWriteApprovals: (...args: unknown[]) => mocks.list(...args),
  getMcpWriteApproval: (...args: unknown[]) => mocks.get(...args),
  approveMcpWriteApproval: (...args: unknown[]) => mocks.approve(...args),
  rejectMcpWriteApproval: (...args: unknown[]) => mocks.reject(...args),
}))
vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn() }), useRoute: () => ({ query: mocks.query }) }))
vi.mock('element-plus', () => ({ ElMessage: { success: vi.fn(), error: vi.fn(), warning: vi.fn() } }))

const pending = {
  id: 7, state: 'PENDING', tenantId: 'default', connectionId: 1, userName: '张三', externalUserId: '1',
  toolKind: 'CLAUDE_CODE', projectCode: 'yudao-erp', systemName: '芋道 ERP', publicationId: 1, revisionNo: 2,
  toolName: 'yudao_erp_erpCustomerCreate', toolDescription: '新建客户', sourceKind: 'CAPABILITY',
  sourceRef: 'yudao-erp:erpCustomerCreate', riskLevel: 'WRITE', arguments: { name: '华东客户' },
  decidedBy: null, decidedAt: null, decisionComment: null, executedAt: null, result: null, errorCode: null,
  runId: null, traceId: null, expiresAt: '2026-10-26T10:00:00', createdAt: '2026-10-25T10:00:00',
}

function mountPage() {
  return mount(McpWriteApprovalList, {
    global: {
      stubs: {
        LiquidGlassTable: { template: '<section><slot name="toolbar" /><slot /></section>' },
        LiquidGlassFilterBar: { template: '<div><slot /></div>' },
        LiquidGlassInput: { template: '<input />' },
        LiquidGlassSelect: { template: '<select><slot /></select>' },
        LiquidGlassNotice: { props: ['title'], template: '<p class="notice">{{ title }}</p>' },
        LiquidGlassButton: { emits: ['click'], template: '<button class="lg-button" @click="$emit(\'click\')"><slot /></button>' },
        AppDialog: { props: ['modelValue'], template: '<div v-if="modelValue" class="dialog"><slot /><slot name="footer" /></div>' },
        ElOption: true,
        ElTable: { template: '<div />' },
        ElTableColumn: true,
        ElTag: { template: '<span><slot /></span>' },
        ElDescriptions: { template: '<dl><slot /></dl>' },
        ElDescriptionsItem: { props: ['label'], template: '<div><dt>{{ label }}</dt><dd><slot /></dd></div>' },
        ElInput: { props: ['modelValue'], emits: ['update:modelValue'], template: '<textarea class="comment" :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />' },
        ElButton: { template: '<button><slot /></button>' },
      },
      directives: { loading: {} },
    },
  })
}

describe('McpWriteApprovalList', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mocks.canDecide = true
    mocks.query = { id: '7' }
    mocks.list.mockResolvedValue({ data: { items: [pending], total: 1, pendingCount: 1, limit: 20, offset: 0 } })
    mocks.get.mockResolvedValue({ data: pending })
  })

  it('opens pending requests by default and approves with the comment', async () => {
    mocks.approve.mockResolvedValue({ data: { ...pending, state: 'SUCCEEDED', decidedBy: 'manager01' } })
    const wrapper = mountPage()
    await flushPromises()

    expect(mocks.list).toHaveBeenCalledWith(expect.objectContaining({ state: 'PENDING' }))
    expect(wrapper.text()).toContain('1 项待审批')
    expect(wrapper.emitted('pendingCount')?.[0]).toEqual([1])
    expect(wrapper.find('.dialog').text()).toContain('华东客户')

    await wrapper.find('.comment').setValue('同意')
    const approveButton = wrapper.findAll('.lg-button').find((button) => button.text() === '通过并执行')
    await approveButton!.trigger('click')
    await flushPromises()

    expect(mocks.approve).toHaveBeenCalledWith(7, '同意')
    expect(wrapper.find('.dialog').text()).not.toContain('通过并执行')
  })

  it('read-only viewers cannot decide', async () => {
    mocks.canDecide = false
    const wrapper = mountPage()
    await flushPromises()

    const labels = wrapper.findAll('.lg-button').map((button) => button.text())
    expect(labels).not.toContain('通过并执行')
    expect(labels).not.toContain('驳回')
    expect(wrapper.find('.comment').exists()).toBe(false)
  })
})
