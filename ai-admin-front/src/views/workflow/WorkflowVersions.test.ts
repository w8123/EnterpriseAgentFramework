import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import ElementPlus, { ElMessageBox } from 'element-plus'
import WorkflowVersions from './WorkflowVersions.vue'
import { getWorkflow, listWorkflowVersions, publishWorkflowVersion, rollbackWorkflowVersion } from '@/api/workflow'

vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { workflowId: 'wf-orders' }, query: {} }),
  useRouter: () => ({ push: vi.fn() }),
}))
vi.mock('@/api/workflow', () => ({
  getWorkflow: vi.fn(), listWorkflowVersions: vi.fn(), publishWorkflowVersion: vi.fn(),
  rollbackWorkflowVersion: vi.fn(), validateWorkflowVersion: vi.fn(),
}))
vi.mock('@/auth/platformAccess', () => ({
  hasPlatformResourcePermission: () => true,
  PLATFORM_PERMISSION_WORKFLOW_PUBLISH: 'workflow:publish', PLATFORM_PERMISSION_WORKFLOW_WRITE: 'workflow:write',
}))
vi.mock('@/auth/platformSession', () => ({ platformSessionUser: { value: { permissionGrants: [] } } }))
vi.mock('element-plus', async (original) => ({
  ...await original<typeof import('element-plus')>(),
  ElMessage: { success: vi.fn(), warning: vi.fn(), error: vi.fn() },
  ElMessageBox: { confirm: vi.fn() },
}))

describe('Workflow version release commands', () => {
  let wrapper: VueWrapper
  beforeEach(async () => {
    vi.clearAllMocks()
    vi.mocked(getWorkflow).mockResolvedValue({ data: {
      id: 'wf-orders', name: '订单查询', keySlug: 'orders', updatedAt: '2026-09-05T10:00:00',
    } } as any)
    vi.mocked(listWorkflowVersions).mockResolvedValue({ data: [
      { id: 9, workflowId: 'wf-orders', version: 'v1', status: 'RETIRED' },
    ] } as any)
    wrapper = mount(WorkflowVersions, { attachTo: document.body, global: {
      plugins: [ElementPlus], stubs: { AppDialog: {
        props: ['modelValue', 'title'],
        template: '<section v-if="modelValue" role="dialog" :aria-label="title"><slot /><slot name="footer" /></section>',
      } },
    } })
    await flushPromises()
  })
  afterEach(() => { wrapper.unmount(); document.body.innerHTML = '' })

  const button = (text: string) => wrapper.findAll('button').find(value => value.text() === text)!

  async function openPublish() {
    await button('发布').trigger('click')
    await flushPromises()
    const dialog = wrapper.find('[role="dialog"]')
    await dialog.find('input[placeholder="v1.0.0"]').setValue('v2')
    await dialog.find('textarea').setValue('发布说明')
    return dialog
  }

  it('sends the seen revision and preserves entered values after a conflict', async () => {
    vi.mocked(publishWorkflowVersion).mockRejectedValue({ response: { status: 409 } })
    const dialog = await openPublish()
    expect(dialog.text()).not.toContain('发布人')
    await dialog.findAll('button').find(value => value.text() === '发布')!.trigger('click')
    await flushPromises()
    expect(publishWorkflowVersion).toHaveBeenCalledWith('wf-orders', {
      version: 'v2', rolloutPercent: 100, note: '发布说明', baseRevision: '2026-09-05T10:00:00',
    })
    expect(dialog.text()).toContain('草稿已被更新')
    expect((dialog.find('input[placeholder="v1.0.0"]').element as HTMLInputElement).value).toBe('v2')
    expect((dialog.find('textarea').element as HTMLTextAreaElement).value).toBe('发布说明')
  })

  it('prevents a second publication while the first is pending', async () => {
    let finish!: (value: any) => void
    vi.mocked(publishWorkflowVersion).mockImplementation(() => new Promise(resolve => { finish = resolve }))
    const dialog = await openPublish()
    const publish = dialog.findAll('button').find(value => value.text() === '发布')!
    await publish.trigger('click')
    await publish.trigger('click')
    expect(publishWorkflowVersion).toHaveBeenCalledTimes(1)
    finish({ data: {} })
    await flushPromises()
  })

  it('confirms draft preservation and passes the seen revision to rollback', async () => {
    vi.mocked(ElMessageBox.confirm).mockReturnValue(Promise.resolve('confirm') as ReturnType<typeof ElMessageBox.confirm>)
    vi.mocked(rollbackWorkflowVersion).mockResolvedValue({ data: {} } as any)
    await button('回滚').trigger('click')
    await flushPromises()
    expect(ElMessageBox.confirm).toHaveBeenCalledWith(expect.stringContaining('当前编辑草稿会保留'),
      '回滚 Workflow', expect.objectContaining({ confirmButtonText: '回滚版本' }))
    expect(rollbackWorkflowVersion).toHaveBeenCalledWith('wf-orders', 9, '2026-09-05T10:00:00')
  })
})
