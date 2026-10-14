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
function start(projectCode = 'orders', density: 'comfortable' | 'compact' = 'comfortable', presentation: 'panel' | 'status' = 'panel') { wrapper = mount(CapabilityReviewPanel, { props: { projectCode, density, presentation }, attachTo: document.body, global: { plugins: [ElementPlus] } }); return wrapper }
function button(text: string) { return Array.from(document.querySelectorAll('button')).reverse().find(button => button.textContent?.trim() === text) as HTMLButtonElement }
beforeEach(() => { vi.clearAllMocks(); mocks.route = reactive({ query: {} }); mocks.replace.mockResolvedValue(undefined); mocks.list.mockResolvedValue(response()); mocks.user.value.permissionGrants = [{ permissionCode: 'platform:read', scopeType: 'PROJECT', scopeValue: 'orders' }] })
afterEach(() => { wrapper?.unmount(); document.body.innerHTML = '' })

describe('capability exception handling', () => {
  it('shows only a status row when no changes need attention and opens history on demand', async () => {
    mocks.list.mockResolvedValue(response([]))
    const view = start('orders', 'compact', 'status'); await flushPromises()
    expect(view.get('.review-status').text()).toContain('暂无待处理变化')
    expect(view.find('.review-toolbar').exists()).toBe(false)
    button('处理记录').click(); await flushPromises()
    expect(mocks.list.mock.lastCall?.[1]).toMatchObject({ state: 'PROCESSED' })
    expect(view.find('.review-toolbar').exists()).toBe(true)
    button('收起').click(); await flushPromises()
    expect(view.find('.review-toolbar').exists()).toBe(false)
  })

  it('keeps failed status reads distinct from zero and retries in place', async () => {
    mocks.list.mockRejectedValueOnce(new Error('读取超时'))
    const view = start('orders', 'compact', 'status'); await flushPromises()
    expect(view.get('.review-status [role="alert"]').text()).toContain('读取超时')
    expect(view.text()).not.toContain('暂无待处理变化')
    mocks.list.mockResolvedValue(response([]))
    button('重新加载').click(); await flushPromises()
    expect(view.get('.review-status').text()).toContain('暂无待处理变化')
  })

  it('opens pending changes and restores a bookmarked history view in status presentation', async () => {
    const view = start('orders', 'compact', 'status'); await flushPromises()
    expect(view.get('.review-status').text()).toContain('1 项变化待确认')
    button('查看变化').click(); await flushPromises()
    expect(view.find('.review-table').exists()).toBe(true)
    mocks.route.query = { changes: 'history', changeSearch: '订单', changePage: '2' }
    mocks.list.mockResolvedValue({ data: { ...response().data, total: 100 } })
    await flushPromises()
    expect(mocks.list.mock.lastCall?.[1]).toMatchObject({ state: 'PROCESSED', keyword: '订单', current: 2 })
  })
  it('does not create work or load records without a project', async () => {
    start(''); await flushPromises(); expect(mocks.list).not.toHaveBeenCalled(); expect(document.body.textContent).toContain('选择项目，查看需要确认的变化')
  })

  it('shows the business name and incomplete evidence without giving read-only users decision actions', async () => {
    start('orders', 'compact'); await flushPromises(); button('查看详情').click(); await flushPromises()
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

  it('keeps the compact success state inline and lets users inspect empty processing history', async () => {
    mocks.list.mockResolvedValue(response([]))
    const view = start('orders', 'compact'); await flushPromises()
    expect(view.get('.review-state[role="status"]').text()).toContain('目前没有需要你处理的变化')
    expect(view.find('.el-empty').exists()).toBe(false)
    expect(view.find('.review-summary').exists()).toBe(false)
    expect(view.find('input[placeholder="搜索能力名称或标识"]').exists()).toBe(true)
    const history = view.findAll('input[type="radio"]').find(input => input.attributes('value') === 'PROCESSED')!
    await history.setValue(true); await flushPromises()
    expect(mocks.list.mock.lastCall?.[1]).toMatchObject({ state: 'PROCESSED' })
    expect(view.get('.review-state').text()).toContain('暂无处理记录')
    button('刷新').click(); await flushPromises()
    expect(mocks.list.mock.lastCall?.[1]).toMatchObject({ state: 'PROCESSED' })
  })

  it('lets users clear a compact no-results state and reload the unfiltered changes', async () => {
    mocks.route.query = { changeSearch: '不存在' }
    mocks.list.mockResolvedValue(response([]))
    const view = start('orders', 'compact'); await flushPromises()
    expect(view.get('.review-state').text()).toContain('没有匹配的变化')
    mocks.list.mockResolvedValue(response())
    button('清除搜索').click()
    await new Promise(resolve => setTimeout(resolve, 350)); await flushPromises()
    expect(mocks.list.mock.lastCall?.[1]).toMatchObject({ keyword: '', current: 1 })
    expect(view.text()).toContain('查询订单')
    expect(view.find('.review-state').exists()).toBe(false)
  })

  it('keeps a compact read error distinct from a successful empty result and offers retry', async () => {
    mocks.list.mockRejectedValueOnce(new Error('读取超时'))
    const view = start('orders', 'compact'); await flushPromises()
    expect(view.get('.review-state[role="alert"]').text()).toContain('读取超时')
    expect(view.text()).not.toContain('目前没有需要你处理的变化')
    mocks.list.mockResolvedValue(response([]))
    button('重新加载').click(); await flushPromises()
    expect(view.find('[role="alert"]').exists()).toBe(false)
    expect(view.get('.review-state[role="status"]').text()).toContain('目前没有需要你处理的变化')
  })

  it('preserves the comfortable empty-state presentation for other callers', async () => {
    mocks.list.mockResolvedValue(response([]))
    const view = start(); await flushPromises()
    expect(view.get('.el-empty').text()).toContain('目前没有需要你处理的变化')
    expect(view.find('.review-state').exists()).toBe(false)
    expect(view.get('.review-summary').text()).toContain('系统自动处理可确定的变化')
  })
})
