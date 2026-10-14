import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, describe, expect, it } from 'vitest'
import SourceActions from './SourceActions.vue'
import type { ApiGovernanceAdvice } from '../composables/useSourceSummary'
import type { BusinessCapabilityWorkspace } from '../../businessCapabilityRoutes'

const advice: ApiGovernanceAdvice = { title: '来源已发现', description: '', primaryLabel: '业务方法目录',
  primaryAction: 'viewOwnerCatalog', secondaryLabel: '查看来源记录', secondaryAction: 'viewCatalog' }
const wrappers: ReturnType<typeof mount>[] = []
afterEach(() => { wrappers.splice(0).forEach(wrapper => wrapper.unmount()); document.body.innerHTML = '' })
function setup(workspace: BusinessCapabilityWorkspace = 'sources', stageAdvice = advice) {
  const wrapper = mount(SourceActions, { attachTo: document.body, props: { workspace, stageAdvice, primaryActionLoading: false,
    reconcileLoading: false, loading: false, canWrite: true }, global: { plugins: [ElementPlus] } })
  wrappers.push(wrapper); return wrapper
}

describe('source actions preserve workspace navigation and real operations', () => {
  it('keeps synchronization exclusive to the sources task area', async () => {
    const view = setup()
    expect(view.text()).not.toContain('查看来源记录')
    const discovery = view.findAll('button').find(item => item.text() === '同步与发现')!
    await discovery.trigger('click')
    expect(view.emitted('openImportDialog')).toHaveLength(1)
    expect(view.text()).not.toContain('业务方法目录')
    for (const workspace of ['groups', 'semantics', 'graph'] as const) {
      await view.setProps({ workspace })
      expect(view.findAll('button')).toHaveLength(0)
    }
  })

  it('keeps SDK refresh reachable by its accessible name without duplicating the import action', async () => {
    const view = setup('sources', { title: '等待同步', description: '', primaryLabel: '同步来源', primaryAction: 'importApi',
      secondaryLabel: '刷新同步状态', secondaryAction: 'refresh' })
    expect(view.findAll('button').filter(item => item.text() === '同步与发现')).toHaveLength(1)
    await view.get('button[aria-label="刷新来源"]').trigger('click')
    expect(view.emitted('primaryAction')![0]).toEqual(['refresh'])
  })

  it('preserves the AI and association check commands without the retired index update', async () => {
    const view = setup()
    for (const [label, event] of [['批量补充说明', 'openModelGeneratePanel'], ['核对来源关联', 'reconcile']] as const) {
      await view.get('.el-dropdown button').trigger('click'); await flushPromises()
      const item = Array.from(document.querySelectorAll('.el-dropdown-menu__item')).find(item => item.textContent?.trim() === label)!
      expect(item).toBeDefined()
      item.dispatchEvent(new MouseEvent('click', { bubbles: true })); await flushPromises()
      expect(view.emitted(event)).toHaveLength(1)
    }
    expect(document.body.textContent).not.toContain('更新检索索引')
    expect(view.emitted('openOpsPanel')).toBeUndefined()
  })

  it('keeps source scan and full export reachable without the retired diagnostics action', async () => {
    const view = setup()
    await view.setProps({ sourceCount: 2 })
    for (const [label, event] of [['扫描敏感数据', 'startSensitiveDataScan'], ['导出 EXCEL', 'exportExcel']] as const) {
      await view.get('.el-dropdown button').trigger('click'); await flushPromises()
      const item = Array.from(document.querySelectorAll('.el-dropdown-menu__item')).find(item => item.textContent?.trim() === label)!
      expect(item).toBeDefined()
      item.dispatchEvent(new MouseEvent('click', { bubbles: true })); await flushPromises()
      expect(view.emitted(event)).toHaveLength(1)
    }
    await view.setProps({ sensitiveTaskPolling: true, exportLoading: true })
    await view.get('.el-dropdown button').trigger('click'); await flushPromises()
    const items = Array.from(document.querySelectorAll('.el-dropdown-menu__item'))
    expect(items.find(item => item.textContent?.trim() === '扫描敏感数据')?.getAttribute('aria-disabled')).toBe('true')
    expect(items.find(item => item.textContent?.trim() === '导出 EXCEL')?.getAttribute('aria-disabled')).toBe('true')
    expect(items.some(item => item.textContent?.trim() === '同步诊断')).toBe(false)
  })

  it('does not dispatch another association check while that dropdown action is busy', async () => {
    const view = setup(); await view.setProps({ reconcileLoading: true })
    await view.get('.el-dropdown button').trigger('click'); await flushPromises()
    const item = Array.from(document.querySelectorAll('.el-dropdown-menu__item')).find(item => item.textContent?.trim() === '核对来源关联')!
    expect(item.getAttribute('aria-disabled')).toBe('true')
    item.dispatchEvent(new MouseEvent('click', { bubbles: true })); await flushPromises()
    expect(view.emitted('reconcile')).toBeUndefined()
  })

  it('disables a new sensitive scan while the previous task state cannot be read', async () => {
    const view = setup()
    await view.setProps({ sourceCount: 2, sensitiveStatusError: '扫描状态读取失败' })
    await view.get('.el-dropdown button').trigger('click'); await flushPromises()
    const item = Array.from(document.querySelectorAll('.el-dropdown-menu__item')).find(item => item.textContent?.trim() === '扫描敏感数据')!
    expect(item.getAttribute('aria-disabled')).toBe('true')
    item.dispatchEvent(new MouseEvent('click', { bubbles: true })); await flushPromises()
    expect(view.emitted('startSensitiveDataScan')).toBeUndefined()
  })

})
