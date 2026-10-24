import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { reactive } from 'vue'
import ElementPlus from 'element-plus'
import ToolAclList from './ToolAclList.vue'
import ToolAclProjectScopeField from './components/ToolAclProjectScopeField.vue'
import type { ToolAclProjectScope, ToolAclRule } from '@/types/toolAcl'

const mocks = vi.hoisted(() => ({
  list: vi.fn(), roles: vi.fn(), create: vi.fn(), update: vi.fn(), batch: vi.fn(), explain: vi.fn(),
  remove: vi.fn(), toggle: vi.fn(), tools: vi.fn(), fetchProjects: vi.fn(), store: {} as any, scope: null as any,
  canManage: true,
}))
vi.mock('@/api/toolAcl', () => ({
  listToolAcl: mocks.list, listToolAclRoles: mocks.roles, createToolAcl: mocks.create,
  updateToolAcl: mocks.update, grantToolAclBatch: mocks.batch, explainToolAcl: mocks.explain,
  deleteToolAcl: mocks.remove, toggleToolAcl: mocks.toggle,
}))
vi.mock('@/api/tool', () => ({ listAllTools: mocks.tools }))
vi.mock('@/store/project', () => ({ useProjectStore: () => mocks.store }))
vi.mock('@/composables/usePageProjectScope', () => ({ usePageProjectScope: () => mocks.scope }))
vi.mock('@/auth/usePlatformCan', () => ({ usePlatformCan: () => ({ can: () => mocks.canManage, canAny: () => mocks.canManage }) }))

let wrapper: ReturnType<typeof mount> | undefined
const baseRule: ToolAclRule = { id: 1, roleCode: 'ops', targetKind: 'TOOL', targetName: 'orders.search', permission: 'ALLOW', enabled: true }
function start(records: ToolAclRule[] = []) {
  mocks.list.mockResolvedValue({ data: { records, total: records.length, size: 20, current: 1 } })
  wrapper = mount(ToolAclList, { attachTo: document.body, global: { plugins: [ElementPlus] } })
  return wrapper
}
function button(text: string) {
  return Array.from(document.querySelectorAll('button')).reverse().find(button => button.textContent?.trim() === text) as HTMLButtonElement
}
async function fill(placeholder: string, value: string) {
  const input = document.querySelector(`[placeholder="${placeholder}"]`) as HTMLInputElement
  input.value = value
  input.dispatchEvent(new Event('input', { bubbles: true }))
  await flushPromises()
}
function scopeField() { return wrapper!.findComponent(ToolAclProjectScopeField) }
async function chooseScope(selection: number | string) {
  scopeField().findComponent({ name: 'ElSelect' }).vm.$emit('change', selection)
  await flushPromises()
}
async function setDiagnosisRoles(roles: string[]) {
  const roleSelect = wrapper!.findAllComponents({ name: 'ElSelect' }).find(select => select.props('multiple'))!
  roleSelect.vm.$emit('update:modelValue', roles)
  await flushPromises()
}
async function openDiagnosis() {
  ;(document.querySelector('[aria-label="权限检查"]') as HTMLButtonElement).click()
  await flushPromises()
  await chooseScope(7)
  await setDiagnosisRoles(['ops'])
  await fill('每行一个能力标识，例如 orders.search', 'TOOL:orders:lookup')
}
function diagnosisRows() {
  return wrapper!.findAllComponents({ name: 'ElTable' })
    .flatMap(table => table.props('data') as { decision?: string }[])
    .filter(row => row.decision)
}
function diagnosisLoading() {
  return wrapper!.findAllComponents({ name: 'ElButton' })
    .find(button => button.text() === '开始检查')!.props('loading')
}
function pendingDiagnosis() {
  let resolve!: (value: { data: Record<string, string> }) => void
  let reject!: (error: Error) => void
  const promise = new Promise<{ data: Record<string, string> }>((success, failure) => {
    resolve = success
    reject = failure
  })
  return { promise, resolve, reject }
}
function closeDiagnosis() {
  wrapper!.findAllComponents({ name: 'AppDialog' })
    .find(dialog => dialog.props('title') === '权限检查')!.vm.$emit('update:modelValue', false)
}

beforeEach(() => {
  vi.clearAllMocks()
  mocks.scope = null
  mocks.canManage = true
  const projects = [{ id: 7, name: 'Orders', projectCode: 'orders' }, { id: 8, name: 'Billing', projectCode: 'billing' }]
  mocks.store = reactive({ projects, currentProject: null, status: 'ready', loading: false, errorMessage: null,
    fetchProjects: mocks.fetchProjects, projectLabel: (project: any) => `${project.name} / ${project.projectCode}` })
  mocks.roles.mockResolvedValue({ data: ['ops'] })
  mocks.tools.mockResolvedValue([])
  mocks.create.mockResolvedValue({ data: baseRule })
  mocks.update.mockResolvedValue({ data: baseRule })
  mocks.batch.mockResolvedValue({ data: { count: 2 } })
  mocks.explain.mockResolvedValue({ data: { 'orders:lookup': 'ALLOW' } })
})
afterEach(() => { wrapper?.unmount(); wrapper = undefined; document.body.innerHTML = '' })

describe('Tool ACL project scope', () => {
  it('requires an explicit scope and creates an exact project rule without target wildcards', async () => {
    start(); await flushPromises(); button('新建规则').click(); await flushPromises()
    await fill('业务系统传入的角色编码，如 sales_manager', 'ops')
    await fill('能力的唯一标识，可在业务能力或 API 详情页复制', 'orders.search')
    button('保存').click(); await flushPromises()
    expect(mocks.create).not.toHaveBeenCalled()
    await chooseScope(7)
    button('保存').click(); await flushPromises()
    expect(mocks.create).toHaveBeenCalledWith(expect.objectContaining({ projectId: 7, projectCode: 'orders',
      roleCode: 'ops', targetKind: 'TOOL', targetName: 'orders.search', permission: 'ALLOW' }))
  })

  it('uses the shared selected project as the initial explicit project scope', async () => {
    mocks.store.currentProject = mocks.store.projects[0]
    start(); await flushPromises(); button('新建规则').click(); await flushPromises()
    expect(scopeField().props('modelValue')).toMatchObject({ projectId: 7, projectCode: 'orders' })
    expect(scopeField().findComponent({ name: 'ElSelect' }).props('modelValue')).toBe(7)
  })

  it('does not fall back to a previous sidebar project when an active page scope is unresolved', async () => {
    mocks.store.currentProject = mocks.store.projects[0]
    mocks.scope = { isActive: { value: true }, requestParams: { value: undefined } }
    start(); await flushPromises(); button('新建规则').click(); await flushPromises()
    expect(scopeField().findComponent({ name: 'ElSelect' }).props('modelValue')).toBeUndefined()
  })

  it.each([
    { projectId: 8, projectCode: 'billing' },
    { projectId: 21, projectCode: 'unlisted' },
    { projectId: null, projectCode: 'code-only' },
    { projectId: 21, projectCode: null },
    { projectId: null, projectCode: null },
  ] satisfies ToolAclProjectScope[])('preserves existing scope while editing: %j', async scope => {
    mocks.store.currentProject = mocks.store.projects[0]
    start([{ ...baseRule, ...scope }]); await flushPromises(); button('编辑').click(); await flushPromises()
    button('保存').click(); await flushPromises()
    expect(mocks.update).toHaveBeenCalledWith(1, expect.objectContaining(scope))
    expect(mocks.create).not.toHaveBeenCalled()
  })

  it('creates a platform rule only after explicit platform scope selection', async () => {
    start(); await flushPromises(); button('新建规则').click(); await flushPromises()
    await chooseScope('platform')
    await fill('业务系统传入的角色编码，如 sales_manager', 'ops')
    await fill('能力的唯一标识，可在业务能力或 API 详情页复制', 'orders.search')
    button('保存').click(); await flushPromises()
    expect(mocks.create).toHaveBeenCalledWith(expect.objectContaining({ projectId: null, projectCode: null,
      targetKind: 'TOOL', targetName: 'orders.search' }))
  })

  it('sends the selected project with every batch target', async () => {
    start(); await flushPromises()
    ;(document.querySelector('[aria-label="批量授权"]') as HTMLButtonElement).click(); await flushPromises()
    await chooseScope(8)
    await fill('一次授权给一个角色', 'ops')
    await fill('每行一个能力标识', 'billing.lookup\nbilling.summary')
    button('提交').click(); await flushPromises()
    expect(mocks.batch).toHaveBeenCalledWith(expect.objectContaining({ projectId: 8, projectCode: 'billing',
      targets: [{ kind: 'TOOL', name: 'billing.lookup' }, { kind: 'TOOL', name: 'billing.summary' }] }))
  })

  it('diagnoses within the selected project and preserves the full target name', async () => {
    start(); await flushPromises()
    ;(document.querySelector('[aria-label="权限检查"]') as HTMLButtonElement).click(); await flushPromises()
    await chooseScope(7)
    const roleSelect = wrapper!.findAllComponents({ name: 'ElSelect' }).find(select => select.props('multiple'))!
    roleSelect.vm.$emit('update:modelValue', ['ops']); await flushPromises()
    await fill('每行一个能力标识，例如 orders.search', 'TOOL:orders:lookup')
    button('开始检查').click(); await flushPromises()
    expect(mocks.explain).toHaveBeenCalledWith({ projectId: 7, projectCode: 'orders', roles: ['ops'],
      targets: [{ kind: 'TOOL', name: 'orders:lookup' }] })
    expect(document.body.textContent).not.toContain('不拦截，仅 warn')
  })

  it('keeps the selected project on save failure and prevents duplicate permission changes', async () => {
    let reject!: (error: Error) => void
    mocks.create.mockImplementation(() => new Promise((_resolve, failure) => { reject = failure }))
    start(); await flushPromises(); button('新建规则').click(); await flushPromises()
    await chooseScope(7)
    await fill('业务系统传入的角色编码，如 sales_manager', 'ops')
    await fill('能力的唯一标识，可在业务能力或 API 详情页复制', 'orders.search')
    button('保存').click(); button('保存').click(); await flushPromises()
    expect(mocks.create).toHaveBeenCalledTimes(1)
    reject(new Error('test failure')); await flushPromises()
    expect(scopeField().props('modelValue')).toMatchObject({ projectId: 7, projectCode: 'orders' })
    expect(button('保存')).toBeDefined()
  })

  it('retains an existing unavailable project and exposes catalog retry', async () => {
    mocks.store.status = 'error'; mocks.store.errorMessage = '项目列表加载失败，请稍后重试'
    start([{ ...baseRule, projectId: 21, projectCode: 'unlisted' }]); await flushPromises()
    button('编辑').click(); await flushPromises()
    expect(scopeField().findComponent({ name: 'ElSelect' }).props('modelValue')).toBe(21)
    expect(document.body.textContent).toContain('现有项目范围 / unlisted')
    button('重试项目列表').click(); await flushPromises()
    expect(mocks.fetchProjects).toHaveBeenCalledTimes(1)
    button('保存').click(); await flushPromises()
    expect(mocks.update).toHaveBeenCalledWith(1, expect.objectContaining({ projectId: 21, projectCode: 'unlisted' }))
  })

  it.each(['projectId', 'projectCode', 'roles', 'targets'] as const)(
    'clears a completed ALLOW immediately when diagnosis %s changes', async criterion => {
      start(); await flushPromises(); await openDiagnosis()
      button('开始检查').click(); await flushPromises()
      expect(diagnosisRows()).toEqual([{ kind: 'TOOL', name: 'orders:lookup', decision: 'ALLOW' }])
      if (criterion === 'projectId') await chooseScope(8)
      if (criterion === 'projectCode') {
        scopeField().vm.$emit('update:modelValue', { projectId: 7, projectCode: 'orders-changed' })
        await flushPromises()
      }
      if (criterion === 'roles') await setDiagnosisRoles(['auditor'])
      if (criterion === 'targets') await fill('每行一个能力标识，例如 orders.search', '')
      expect(diagnosisRows()).toEqual([])
      expect(mocks.explain).toHaveBeenCalledTimes(1)
    },
  )

  it.each([true, false])('ignores the old project response when it returns before the current one: %s', async oldFirst => {
    const oldRequest = pendingDiagnosis()
    const currentRequest = pendingDiagnosis()
    mocks.explain.mockReturnValueOnce(oldRequest.promise).mockReturnValueOnce(currentRequest.promise)
    start(); await flushPromises(); await openDiagnosis()
    button('开始检查').click(); await flushPromises()
    await chooseScope(8)
    expect(diagnosisRows()).toEqual([])
    expect(diagnosisLoading()).toBe(false)
    button('开始检查').click(); await flushPromises()
    expect(mocks.explain).toHaveBeenNthCalledWith(2, expect.objectContaining({ projectId: 8, projectCode: 'billing' }))
    expect(diagnosisLoading()).toBe(true)
    if (oldFirst) {
      oldRequest.resolve({ data: { 'orders:lookup': 'ALLOW' } }); await flushPromises()
      expect(diagnosisRows()).toEqual([])
      expect(diagnosisLoading()).toBe(true)
    }
    currentRequest.resolve({ data: { 'orders:lookup': 'DENY_NO_MATCH' } }); await flushPromises()
    if (!oldFirst) {
      oldRequest.resolve({ data: { 'orders:lookup': 'ALLOW' } }); await flushPromises()
    }
    expect(diagnosisRows()).toEqual([{ kind: 'TOOL', name: 'orders:lookup', decision: 'DENY_NO_MATCH' }])
    expect(diagnosisLoading()).toBe(false)
  })

  it('invalidates a pending response when the diagnosis is closed and reopened with the same criteria', async () => {
    const oldRequest = pendingDiagnosis()
    mocks.explain.mockReturnValueOnce(oldRequest.promise)
    start(); await flushPromises(); await openDiagnosis()
    button('开始检查').click(); await flushPromises()
    closeDiagnosis(); await flushPromises()
    await openDiagnosis()
    oldRequest.resolve({ data: { 'orders:lookup': 'ALLOW' } }); await flushPromises()
    expect(diagnosisRows()).toEqual([])
    expect(diagnosisLoading()).toBe(false)
  })

  it('ignores a stale failure after roles change without ending the current query', async () => {
    const oldRequest = pendingDiagnosis()
    const currentRequest = pendingDiagnosis()
    mocks.explain.mockReturnValueOnce(oldRequest.promise).mockReturnValueOnce(currentRequest.promise)
    start(); await flushPromises(); await openDiagnosis()
    button('开始检查').click(); await flushPromises()
    await setDiagnosisRoles(['auditor'])
    button('开始检查').click(); await flushPromises()
    expect(mocks.explain.mock.calls[0][0].roles).toEqual(['ops'])
    oldRequest.reject(new Error('stale diagnosis failure')); await flushPromises()
    expect(document.body.textContent).not.toContain('stale diagnosis failure')
    expect(diagnosisLoading()).toBe(true)
    currentRequest.resolve({ data: { 'orders:lookup': 'DENY_NO_MATCH' } }); await flushPromises()
    expect(diagnosisRows()[0].decision).toBe('DENY_NO_MATCH')
  })

  it('ignores a late diagnosis failure after the page unmounts', async () => {
    const oldRequest = pendingDiagnosis()
    mocks.explain.mockReturnValueOnce(oldRequest.promise)
    start(); await flushPromises(); await openDiagnosis()
    button('开始检查').click(); await flushPromises()
    wrapper!.unmount(); wrapper = undefined
    oldRequest.reject(new Error('unmounted diagnosis failure')); await flushPromises()
    expect(document.body.textContent).not.toContain('unmounted diagnosis failure')
  })
})

describe('Tool ACL Chinese display', () => {
  const apiName = 'http-api:bzjs21:dev:874a2a04c15dd584facb5e3d41dbc004a259e96ae451ff65d24b5156f5fde388'

  it('shows readable role and capability names while keeping the raw codes', async () => {
    mocks.roles.mockResolvedValue({ data: ['PLATFORM_ADMIN', 'c6e63675-b4f9-470c-9061-89bea1b24c66:班组成员'] })
    mocks.tools.mockResolvedValue([{ name: apiName, qualifiedName: apiName, title: '查询班组成员' }])
    start([
      { ...baseRule, roleCode: 'PLATFORM_ADMIN', targetKind: 'ALL', targetName: '*' },
      { ...baseRule, id: 2, roleCode: 'c6e63675-b4f9-470c-9061-89bea1b24c66:班组成员', targetName: apiName, permission: 'DENY' },
    ])
    await flushPromises()
    const text = document.body.textContent ?? ''
    expect(text).toContain('平台管理员')
    expect(text).toContain('班组成员')
    expect(text).toContain('全部能力')
    expect(text).toContain('查询班组成员')
    expect(text).toContain('接口 · dev · 874a2a04')
    expect(text).toContain('允许')
    expect(text).toContain('拒绝')
    expect(text).not.toContain('ALLOW')
    expect(mocks.list).toHaveBeenCalledWith({ current: 1, size: 1, roleCode: 'PLATFORM_ADMIN' })
  })

  it('checks a capability whose identifier contains colons as one target', async () => {
    start(); await flushPromises(); await openDiagnosis()
    await fill('每行一个能力标识，例如 orders.search', apiName)
    button('开始检查').click(); await flushPromises()
    expect(mocks.explain).toHaveBeenCalledWith(expect.objectContaining({ targets: [{ kind: 'TOOL', name: apiName }] }))
  })

  it('hides rule changes from read-only roles such as managers', async () => {
    mocks.canManage = false
    start([baseRule]); await flushPromises()
    expect(button('新建规则')).toBeUndefined()
    expect(button('批量授权')).toBeUndefined()
    expect(button('编辑')).toBeUndefined()
    expect(document.querySelector('[aria-label="权限检查"]')).not.toBeNull()
  })
})
