import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, h, inject, provide, toRef } from 'vue'
import McpEmployeeConnectionList from './McpEmployeeConnectionList.vue'

const mocks = vi.hoisted(() => ({
  listMcpEmployeeConnections: vi.fn(),
  revokeMcpEmployeeConnection: vi.fn(),
  push: vi.fn(),
  confirm: vi.fn(),
}))

// 这些用例覆盖有写权限的角色；只读角色另有用例。
vi.mock('@/auth/usePlatformCan', () => ({ usePlatformCan: () => ({ can: () => true, canAny: () => true }) }))
vi.mock('@/api/mcp', () => ({
  listMcpEmployeeConnections: (...args: unknown[]) => mocks.listMcpEmployeeConnections(...args),
  revokeMcpEmployeeConnection: (...args: unknown[]) => mocks.revokeMcpEmployeeConnection(...args),
}))
vi.mock('vue-router', () => ({ useRouter: () => ({ push: mocks.push }) }))
vi.mock('element-plus', () => ({
  ElMessage: { success: vi.fn(), error: vi.fn() },
  ElMessageBox: { confirm: (...args: unknown[]) => mocks.confirm(...args) },
}))

// Renders every column's cell slot for every row so the cells can be asserted.
const TableStub = defineComponent({
  props: { data: { type: Array, default: () => [] } },
  setup(props, { slots }) {
    provide('rows', toRef(props, 'data'))
    return () => h('div', { class: 'table-stub' }, slots.default?.())
  },
})
const ColumnStub = defineComponent({
  props: { label: String },
  setup(props, { slots }) {
    const rows = inject<{ value: unknown[] }>('rows')
    return () => h('div', { class: 'column-stub', 'data-label': props.label },
      (rows?.value ?? []).map((row) => h('div', { class: 'cell' }, slots.default?.({ row }))))
  },
})

const wrappers: VueWrapper[] = []

function mountList() {
  const wrapper = mount(McpEmployeeConnectionList, {
    global: {
      stubs: {
        LiquidGlassTable: { template: '<section><slot name="toolbar" /><slot /></section>' },
        LiquidGlassFilterBar: { template: '<div><slot /></div>' },
        LiquidGlassInput: { template: '<input />' },
        LiquidGlassSelect: { template: '<select><slot /></select>' },
        LiquidGlassButton: { template: '<button><slot /></button>' },
        ElOption: { template: '<option />' },
        ElTable: TableStub,
        ElTableColumn: ColumnStub,
        ElTag: { template: '<span class="el-tag"><slot /></span>' },
        ElButton: {
          inheritAttrs: false,
          emits: ['click'],
          template: '<button class="row-action" @click="$emit(\'click\')"><slot /></button>',
        },
      },
    },
  })
  wrappers.push(wrapper)
  return wrapper
}

const connection = {
  id: 5,
  tenantId: 'default',
  userName: '张三',
  appId: 'yudao-erp',
  externalUserId: '1',
  toolKind: 'CLAUDE_CODE',
  keyPrefix: 'rai_12345678',
  state: 'ACTIVE',
  systems: [{ projectCode: 'yudao-erp', name: '芋道 ERP' }],
  createdAt: '2026-10-24T10:00:00',
  lastUsedAt: null,
  expiresAt: '2027-01-22T10:00:00',
}

describe('MCP 员工连接', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mocks.listMcpEmployeeConnections.mockResolvedValue({ data: { items: [connection], total: 1, limit: 20, offset: 0 } })
  })

  afterEach(() => {
    wrappers.splice(0).forEach((wrapper) => wrapper.unmount())
  })

  it('默认只看使用中的连接，显示员工、AI 工具和接入的系统', async () => {
    const wrapper = mountList()
    await flushPromises()

    expect(mocks.listMcpEmployeeConnections).toHaveBeenCalledWith(expect.objectContaining({ state: 'ACTIVE', limit: 20, offset: 0 }))
    const text = wrapper.text()
    expect(text).toContain('张三')
    expect(text).toContain('yudao-erp 账号 1')
    expect(text).toContain('Claude Code')
    expect(text).toContain('芋道 ERP')
    expect(text).toContain('使用中')
    expect(text).toContain('还未使用')
  })

  it('确认后断开连接并刷新，调用记录跳到按连接筛选的流水', async () => {
    mocks.confirm.mockResolvedValue('confirm')
    mocks.revokeMcpEmployeeConnection.mockResolvedValue({ data: { ...connection, state: 'REVOKED' } })
    const wrapper = mountList()
    await flushPromises()

    const actions = wrapper.findAll('.row-action')
    await actions.find((button) => button.text() === '断开')!.trigger('click')
    await flushPromises()
    expect(mocks.revokeMcpEmployeeConnection).toHaveBeenCalledWith(5)
    expect(mocks.listMcpEmployeeConnections).toHaveBeenCalledTimes(2)

    await actions.find((button) => button.text() === '调用记录')!.trigger('click')
    expect(mocks.push).toHaveBeenCalledWith({ path: '/mcp-hub/call-logs', query: { connectionId: '5' } })
  })

  it('取消确认时不断开', async () => {
    mocks.confirm.mockRejectedValue('cancel')
    const wrapper = mountList()
    await flushPromises()

    await wrapper.findAll('.row-action').find((button) => button.text() === '断开')!.trigger('click')
    await flushPromises()
    expect(mocks.revokeMcpEmployeeConnection).not.toHaveBeenCalled()
  })
})
