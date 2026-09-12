import { mount, flushPromises } from '@vue/test-utils'
import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest'
import ElementPlus from 'element-plus'
import { reactive } from 'vue'
import CapabilityReviewPanel from './CapabilityReviewPanel.vue'

const mocks = vi.hoisted(() => ({
  list: vi.fn(), review: vi.fn(), rollback: vi.fn(), replace: vi.fn(),
  user: { value: { permissionGrants: [{ permissionCode: 'platform:read', scopeType: 'PROJECT', scopeValue: 'orders' }] } },
  route: { query: {} },
}))
vi.mock('@/api/registry', () => ({ listCapabilityChanges: mocks.list, reviewCapabilityDiffItem: mocks.review, rollbackCapabilityDiffItem: mocks.rollback }))
vi.mock('@/auth/platformSession', () => ({ platformSessionUser: mocks.user }))
vi.mock('vue-router', () => ({ useRoute: () => mocks.route, useRouter: () => ({ replace: mocks.replace }) }))

const pending = { id: 1, snapshotId: 1, syncId: 's1', projectCode: 'orders', name: 'read', qualifiedName: 'orders:read', storageName: 'orders_read', changeType: 'CHANGED', reviewStatus: 'PENDING', rollbackAvailable: false, impactJson: JSON.stringify({ reason: '返回结构变化', currentCandidate: true, sourceAvailability: 'CONTRACT_DRIFT', runtimeEvidence: 'PARTIAL', references: [], candidate: { title: '查询订单' } }), fieldDiffJson: '[]' }
const response = (records = [pending]) => ({ data: { records, total: records.length, pending: records.length, current: 1, size: 20, automated: 0 } })
let wrapper: ReturnType<typeof mount> | undefined
function start(projectCode = 'orders') { wrapper = mount(CapabilityReviewPanel, { props: { projectCode }, attachTo: document.body, global: { plugins: [ElementPlus] } }); return wrapper }
function button(text: string) { return Array.from(document.querySelectorAll('button')).reverse().find(button => button.textContent?.trim() === text) as HTMLButtonElement }
beforeEach(() => { vi.clearAllMocks(); mocks.route = reactive({ query: {} }); mocks.replace.mockResolvedValue(undefined); mocks.list.mockResolvedValue(response()); mocks.user.value.permissionGrants = [{ permissionCode: 'platform:read', scopeType: 'PROJECT', scopeValue: 'orders' }] })
afterEach(() => { wrapper?.unmount(); document.body.innerHTML = '' })

describe('capability exception handling', () => {
  it('does not create work or load records without a project', async () => {
    start(''); await flushPromises(); expect(mocks.list).not.toHaveBeenCalled(); expect(document.body.textContent).toContain('选择项目，查看需要确认的变化')
  })

  it('shows the business name and incomplete evidence without giving read-only users decision actions', async () => {
    start(); await flushPromises(); button('查看详情').click(); await flushPromises()
    expect(document.body.textContent).toContain('查询订单'); expect(document.body.textContent).toContain('不能据此判断没有影响')
    expect(button('接受变化')).toBeUndefined(); expect(button('恢复上次目录定义')).toBeUndefined()
  })

  it('discards a late response from the previous project', async () => {
    let resolveOld!: (value: ReturnType<typeof response>) => void
    mocks.list.mockImplementationOnce(() => new Promise(resolve => { resolveOld = resolve }))
    start(); await flushPromises(); mocks.list.mockResolvedValueOnce(response([])); await wrapper!.setProps({ projectCode: 'other' }); await flushPromises()
    resolveOld(response()); await flushPromises()
    expect(document.body.textContent).not.toContain('查询订单'); expect(document.body.textContent).toContain('目前没有需要你处理的变化')
  })

  it('does not use a write grant from another project', async () => {
    mocks.user.value.permissionGrants = [{ permissionCode: 'platform:write', scopeType: 'PROJECT', scopeValue: 'other' }]
    start(); await flushPromises(); button('查看详情').click(); await flushPromises(); expect(button('接受变化')).toBeUndefined()
  })

  it('keeps the decision open after failure and prevents duplicate submissions', async () => {
    mocks.user.value.permissionGrants = [{ permissionCode: 'platform:write', scopeType: 'PROJECT', scopeValue: 'orders' }]
    let reject!: (error: Error) => void; mocks.review.mockImplementation(() => new Promise((_resolve, failure) => { reject = failure }))
    start(); await flushPromises(); button('查看详情').click(); await flushPromises(); button('接受变化').click(); await flushPromises()
    button('接受变化').click(); button('接受变化').click(); await flushPromises(); expect(mocks.review).toHaveBeenCalledTimes(1)
    reject(new Error('该变化已被新的来源观察替代')); await flushPromises(); expect(document.body.textContent).toContain('该变化已被新的来源观察替代')
    expect(document.querySelectorAll('[role="dialog"]').length).toBeGreaterThan(0)
  })

  it('restores URL filters without the search debounce resetting the restored page', async () => {
    mocks.list.mockResolvedValue({ data: { ...response().data, total: 100 } }); start(); await flushPromises()
    mocks.route.query = { changes: 'history', changeSearch: '订单', changePage: '3' }; await flushPromises()
    await new Promise(resolve => setTimeout(resolve, 350)); await flushPromises()
    expect(mocks.list.mock.lastCall?.[1]).toMatchObject({ state: 'PROCESSED', keyword: '订单', current: 3 })
  })
})
