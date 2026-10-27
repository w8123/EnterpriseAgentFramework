import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import McpPublicationList from './McpPublicationList.vue'

const mocks = vi.hoisted(() => ({
  list: vi.fn(), create: vi.fn(), suspend: vi.fn(), resume: vi.fn(), archive: vi.fn(),
  push: vi.fn(), confirm: vi.fn(), error: vi.fn(), canManage: true,
}))
vi.mock('@/api/mcp', () => ({
  listMcpPublications: (...args: unknown[]) => mocks.list(...args),
  createMcpPublication: (...args: unknown[]) => mocks.create(...args),
  suspendMcpPublication: (...args: unknown[]) => mocks.suspend(...args),
  resumeMcpPublication: (...args: unknown[]) => mocks.resume(...args),
  archiveMcpPublication: (...args: unknown[]) => mocks.archive(...args),
}))
vi.mock('@/auth/usePlatformCan', () => ({ usePlatformCan: () => ({ can: () => mocks.canManage }) }))
vi.mock('vue-router', () => ({ useRouter: () => ({ push: mocks.push }) }))
vi.mock('element-plus', () => ({
  ElMessage: { success: vi.fn(), warning: vi.fn(), error: (...args: unknown[]) => mocks.error(...args) },
  ElMessageBox: { confirm: (...args: unknown[]) => mocks.confirm(...args) },
}))

const wrappers: VueWrapper[] = []
function mountList() {
  const wrapper = mount(McpPublicationList, {
    global: {
      stubs: {
        LiquidGlassButton: { emits: ['click'], template: '<button @click="$emit(\'click\')"><slot /></button>' },
        LiquidGlassTable: { props: ['empty'], template: '<section><slot name="toolbar" /><slot v-if="!empty" /><slot v-else name="empty" /></section>' },
        LiquidGlassFilterBar: { template: '<div><slot /></div>' },
        LiquidGlassInput: true,
        LiquidGlassSelect: true,
        LiquidGlassEmptyState: { props: ['title', 'description'], template: '<div class="empty"><h3>{{ title }}</h3><p>{{ description }}</p><slot /></div>' },
        AppDialog: { props: ['modelValue'], template: '<section v-if="modelValue" class="dialog"><slot /><slot name="footer" /></section>' },
        ElForm: { template: '<form><slot /></form>' },
        ElFormItem: { template: '<label><slot name="label" /><slot /></label>' },
        ElInput: { props: ['modelValue'], emits: ['update:modelValue'], template: '<input :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />' },
        ElOption: true,
        ElTable: { name: 'ElTable', template: '<div class="table" />' },
        ElTableColumn: true,
        ElTag: true,
        ElDropdownItem: true,
        ElDropdownMenu: true,
      },
    },
  })
  wrappers.push(wrapper)
  return wrapper
}

async function fillCreateForm(wrapper: VueWrapper) {
  await wrapper.findAll('button').find(button => button.text() === '新建 MCP 服务')!.trigger('click')
  const inputs = wrapper.findAll('.dialog input')
  await inputs[0].setValue(' customer-tools ')
  await inputs[1].setValue(' 客户查询 ')
}

describe('MCP 服务创建与配置', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mocks.canManage = true
    mocks.list.mockResolvedValue({ data: { items: [], total: 0 } })
    mocks.push.mockResolvedValue(undefined)
  })
  afterEach(() => wrappers.splice(0).forEach(wrapper => wrapper.unmount()))

  it('创建后直接进入新服务配置，提交清理后的名称与说明', async () => {
    mocks.create.mockResolvedValue({ data: { id: 42 } })
    const wrapper = mountList()
    await flushPromises()
    await fillCreateForm(wrapper)
    await wrapper.findAll('.dialog button').find(button => button.text() === '创建并配置')!.trigger('click')
    await flushPromises()

    expect(mocks.create).toHaveBeenCalledWith({ name: 'customer-tools', description: '客户查询' })
    expect(mocks.push).toHaveBeenCalledWith('/mcp-hub/publications/42')
    expect(mocks.list).toHaveBeenCalledTimes(1)
    expect(wrapper.find('.dialog').exists()).toBe(false)
  })

  it('创建失败时保留输入并反馈原因，不跳转', async () => {
    mocks.create.mockRejectedValue(new Error('名称已存在'))
    const wrapper = mountList()
    await flushPromises()
    await fillCreateForm(wrapper)
    await wrapper.findAll('.dialog button').find(button => button.text() === '创建并配置')!.trigger('click')
    await flushPromises()

    expect(mocks.push).not.toHaveBeenCalled()
    expect(wrapper.find<HTMLInputElement>('.dialog input').element.value).toBe(' customer-tools ')
    expect(mocks.error).toHaveBeenCalledWith('创建失败：名称已存在')
  })

  it('点击服务行进入管理，不把表格传入的列对象当成接入参数', async () => {
    const row = { id: 7, state: 'PUBLISHED' }
    mocks.list.mockResolvedValue({ data: { items: [row], total: 1 } })
    const wrapper = mountList()
    await flushPromises()
    wrapper.findComponent({ name: 'ElTable' }).vm.$emit('row-click', row, { label: '名称' })
    expect(mocks.push).toHaveBeenCalledWith('/mcp-hub/publications/7')
  })

  it('只读用户在工具栏和首次空状态均看不到创建入口', async () => {
    mocks.canManage = false
    const wrapper = mountList()
    await flushPromises()
    expect(wrapper.text()).toContain('管理员发布的服务会显示在这里')
    expect(wrapper.findAll('button').some(button => button.text() === '新建 MCP 服务')).toBe(false)
  })
})
