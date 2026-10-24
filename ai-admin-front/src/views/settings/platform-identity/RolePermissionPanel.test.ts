import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import type { PlatformPermissionView, PlatformRoleManagementView } from '@/api/platformAuth'
import RolePermissionPanel from './RolePermissionPanel.vue'

const api = vi.hoisted(() => ({ create: vi.fn(), update: vi.fn() }))
vi.mock('@/api/platformAuth', () => ({ createPlatformRole: api.create, updatePlatformRole: api.update }))

const permissions: PlatformPermissionView[] = [
  {
    id: 1,
    permissionCode: 'platform:admin',
    permissionName: '管理平台',
    resourceType: 'PLATFORM',
    reservedForSystemRole: true,
  },
  {
    id: 2,
    permissionCode: 'platform:read',
    permissionName: '查看平台',
    resourceType: 'PLATFORM',
    reservedForSystemRole: false,
  },
  {
    id: 3,
    permissionCode: 'knowledge:read',
    permissionName: '查看知识库',
    resourceType: 'KNOWLEDGE',
    reservedForSystemRole: false,
  },
  {
    id: 4,
    permissionCode: 'knowledge:write',
    permissionName: '维护知识库',
    resourceType: 'KNOWLEDGE',
    reservedForSystemRole: false,
  },
  {
    id: 5,
    permissionCode: 'a2a-hub:conformance:run',
    permissionName: '运行 A2A 诊断与一致性检查',
    resourceType: 'A2A_CONFORMANCE',
    reservedForSystemRole: false,
  },
  {
    id: 6,
    permissionCode: 'vendor:test',
    permissionName: '扩展权限',
    resourceType: 'VENDOR_EXTENSION',
    reservedForSystemRole: false,
  },
]
const roles: PlatformRoleManagementView[] = [
  {
    id: 1,
    roleCode: 'PLATFORM_ADMIN',
    roleName: '平台管理员',
    roleKind: 'SYSTEM',
    status: 'ACTIVE',
    permissionIds: [1, 2, 3, 4, 5],
    permissionCodes: [],
    userCount: 1,
    grantCount: 1,
  },
  {
    id: 2,
    roleCode: 'READER',
    roleName: '知识库读者',
    roleKind: 'CUSTOM',
    status: 'ACTIVE',
    permissionIds: [3],
    permissionCodes: ['knowledge:read'],
    userCount: 0,
    grantCount: 0,
  },
]
let wrapper: ReturnType<typeof mount>

function start() {
  wrapper = mount(RolePermissionPanel, {
    attachTo: document.body,
    props: { roles, permissions, loading: false },
    global: {
      plugins: [ElementPlus],
      stubs: {
        LiquidGlassLayer: true,
      },
    },
  })
  return wrapper
}

function button(text: string) {
  const result = wrapper.findAll('button').find((item) => item.text() === text)
  if (!result) throw new Error(`Missing button: ${text}`)
  return result
}

function row(code: string) {
  const result = wrapper
    .findAll('.permission-row')
    .find((item) => item.find('.permission-code').text() === code)
  if (!result) throw new Error(`Missing permission: ${code}`)
  return result
}

beforeEach(() => {
  vi.clearAllMocks()
  api.create.mockImplementation(async (body) => ({ data: { ...roles[1], ...body, id: 3 } }))
  api.update.mockResolvedValue({ data: roles[1] })
})
afterEach(() => {
  wrapper?.unmount()
  document.body.innerHTML = ''
})

describe('role permission workbench', () => {
  it('presents system permissions as read-only states without editable controls', async () => {
    start()
    expect(wrapper.text()).toContain('系统预置 · 只读')
    expect(wrapper.findAll('input[type="checkbox"]')).toHaveLength(0)
    expect(row('knowledge:read').find('.permission-status').text()).toBe('已包含')
    expect(row('vendor:test').find('.permission-status').text()).toBe('未包含')
    expect(wrapper.text()).not.toContain('暂无权限描述')
    expect(wrapper.findAll('button').some((item) => item.text() === '保存角色变更')).toBe(false)
    await row('knowledge:read').trigger('click')
    expect(wrapper.find('.selected-summary').text()).toBe('已包含 5 / 6 项')
    expect(api.update).not.toHaveBeenCalled()
  })

  it('opens search results after collapsing groups and filters missing permissions', async () => {
    start()
    const firstGroup = wrapper.find('.group-disclosure')
    await firstGroup.trigger('click')
    expect(firstGroup.attributes('aria-expanded')).toBe('false')
    expect(wrapper.find('.permission-rows').isVisible()).toBe(false)
    await button('全部收起').trigger('click')
    expect(wrapper.findAll('.permission-rows').every((group) => !group.isVisible())).toBe(true)
    await wrapper.get('input[aria-label="搜索权限"]').setValue('A2A 诊断')
    expect(wrapper.findAll('.permission-group')).toHaveLength(1)
    expect(wrapper.find('.group-disclosure').attributes('aria-expanded')).toBe('true')
    expect(wrapper.find('.permission-rows').isVisible()).toBe(true)
    await wrapper.get('input[aria-label="搜索权限"]').setValue('')
    await wrapper.findAll('.scope-switch button')[2].trigger('click')
    expect(wrapper.findAll('.permission-row')).toHaveLength(1)
    expect(wrapper.find('.permission-code').text()).toBe('vendor:test')
    await wrapper.get('input[aria-label="搜索权限"]').setValue('没有此权限')
    expect(wrapper.text()).toContain('没有符合当前筛选条件的权限项')
  })

  it('keeps custom-role selection, group actions and reserved permissions correct', async () => {
    start()
    await wrapper.findAll('.role-item')[1].trigger('click')
    expect(row('platform:admin').get('input[type="checkbox"]').attributes('disabled')).toBeDefined()
    const platform = wrapper
      .findAll('.permission-group')
      .find((group) => group.text().includes('平台治理与底座'))!
    await platform.get('.group-toggle-btn').trigger('click')
    expect(row('platform:read').get('input').element.checked).toBe(true)
    expect(row('platform:admin').get('input').element.checked).toBe(false)
    expect(platform.get('.group-toggle-btn').text()).toBe('取消本组')
    await row('knowledge:write').get('input[type="checkbox"]').setValue(true)
    await button('保存角色变更').trigger('click')
    await flushPromises()
    expect(api.update).toHaveBeenCalledWith(2, expect.objectContaining({ permissionIds: [3, 2, 4] }))
    expect(wrapper.emitted('changed')).toHaveLength(1)
  })

  it('searches the whole displayed domain while retaining exact resource-type search', async () => {
    start()
    await wrapper.setProps({
      permissions: [
        ...permissions,
        {
          id: 7,
          permissionCode: 'a2a-hub:credential:manage',
          permissionName: '管理 A2A 凭证',
          resourceType: 'A2A_CREDENTIAL',
          reservedForSystemRole: false,
        },
      ],
    })
    await wrapper.get('input[aria-label="搜索权限"]').setValue('A2A 互联')
    expect(wrapper.findAll('.permission-group')).toHaveLength(1)
    expect(wrapper.findAll('.permission-row')).toHaveLength(2)
    await wrapper.get('input[aria-label="搜索权限"]').setValue('A2A_CREDENTIAL')
    expect(wrapper.findAll('.permission-row')).toHaveLength(1)
    expect(wrapper.get('.permission-code').text()).toBe('a2a-hub:credential:manage')
  })

  it('creates a role with filtered bulk selection and excludes system-only permissions', async () => {
    start()
    await wrapper.get('button[aria-label="新建自定义角色"]').trigger('click')
    await wrapper.get('input[placeholder="例如 DATA_ANALYST"]').setValue('report_viewer')
    await wrapper.get('input[placeholder="例如 数据分析师"]').setValue('报表查看员')
    await wrapper.get('input[aria-label="搜索权限"]').setValue('平台')
    await button('全选筛选结果').trigger('click')
    await button('创建角色').trigger('click')
    await flushPromises()
    expect(api.create).toHaveBeenCalledWith(
      expect.objectContaining({
        roleCode: 'REPORT_VIEWER',
        roleName: '报表查看员',
        permissionIds: [2],
        status: 'ACTIVE',
      }),
    )
    expect(roles[0].permissionIds).toEqual([1, 2, 3, 4, 5])
    expect(wrapper.get('.role-title').text()).toBe('报表查看员')
    const response = await api.create.mock.results[0].value
    await wrapper.setProps({ roles: [...roles, response.data] })
    expect(wrapper.get('.role-title').text()).toBe('报表查看员')
    expect(wrapper.findAll('.role-item')).toHaveLength(3)
  })
})
