import { effectScope, ref, type Ref } from 'vue'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { platformSessionId } from '@/auth/platformSession'
import type { ScanProject, ScanProjectBlockers, ScanProjectUpsertRequest } from '@/types/scanProject'
import { useRegistryProjectDetailActions, type UseRegistryProjectDetailActionsDeps } from './useRegistryProjectDetailActions'

const mocks = vi.hoisted(() => ({ blockers: vi.fn(), delete: vi.fn(), confirm: vi.fn(), alert: vi.fn(),
  success: vi.fn(), error: vi.fn(), push: vi.fn(), clear: vi.fn() }))
vi.mock('element-plus', () => ({ ElMessage: { success: mocks.success, error: mocks.error },
  ElMessageBox: { confirm: mocks.confirm, alert: mocks.alert } }))
vi.mock('vue-router', () => ({ useRouter: () => ({ push: mocks.push }) }))
vi.mock('@/store/project', () => ({ useProjectStore: () => ({ clearCurrentProject: mocks.clear }) }))
vi.mock('@/auth/platformSession', async () => ({ platformSessionId: (await import('vue')).ref('session-a') }))
vi.mock('@/api/scanProject', () => ({ getScanProjectOperationBlockers: mocks.blockers, deleteScanProject: mocks.delete,
  updateScanProject: vi.fn(), updateScanProjectRegistryCredential: vi.fn() }))
vi.mock('@/api/registry', () => ({ purgeRegistryProjectOfflineInstances: vi.fn(), updateRegistryProjectInstanceStatus: vi.fn() }))

const scopes: ReturnType<typeof effectScope>[] = []
const project = (id = 7): ScanProject => ({ id, projectCode: id === 7 ? 'orders' : 'other', name: '订单项目' } as ScanProject)
const empty: ScanProjectBlockers = { blocked: false, tools: [], agents: [], assets: [] }
function deferred<T>() { let resolve!: (value: T) => void; const promise = new Promise<T>(done => { resolve = done }); return { promise, resolve } }
function setup() {
  const deps: UseRegistryProjectDetailActionsDeps = { project: ref(project()), projectCode: ref('orders'),
    offlineInstanceCount: ref(0), refresh: vi.fn(), loadInstances: vi.fn(), editDialogVisible: ref(false),
    editSaving: ref(false), deleteLoading: ref(false), purgingOffline: ref(false), editAccessLockedToSdk: ref(false),
    editForm: {} as ScanProjectUpsertRequest, editCredentialForm: { appKey: '', appSecret: '' }, isEditingSdkProject: ref(true) }
  const scope = effectScope(); scopes.push(scope)
  const actions = scope.run(() => useRegistryProjectDetailActions(deps))!
  return { deps, actions, scope }
}
beforeEach(() => {
  vi.clearAllMocks(); (platformSessionId as Ref<string>).value = 'session-a'
  mocks.blockers.mockResolvedValue({ data: empty }); mocks.confirm.mockResolvedValue(undefined)
  mocks.delete.mockResolvedValue(undefined); mocks.alert.mockResolvedValue(undefined)
})
afterEach(() => { for (const scope of scopes.splice(0)) scope.stop() })

it('shows an owner blocker and stops before asking to delete', async () => {
  mocks.blockers.mockResolvedValue({ data: { ...empty, blocked: true, assets: [
    { assetType: 'BUSINESS_METHOD', assetId: 21, qualifiedName: 'orders:read', title: '查询订单' },
  ] } })
  await setup().actions.handleDeleteProject()
  expect(mocks.blockers).toHaveBeenCalledWith(7, 'DELETE')
  expect(mocks.alert.mock.calls[0][0]).toContain('查询订单')
  expect(mocks.confirm).not.toHaveBeenCalled(); expect(mocks.delete).not.toHaveBeenCalled()
})

it('allows confirmed deletion of an empty project using its captured identity', async () => {
  const { actions, deps } = setup(); await actions.handleDeleteProject()
  expect(mocks.delete).toHaveBeenCalledWith(7); expect(mocks.clear).toHaveBeenCalledWith(7)
  expect(mocks.push).toHaveBeenCalledWith('/registry/projects'); expect(deps.deleteLoading.value).toBe(false)
})

it('discards a late preflight after switching away and back to the same project', async () => {
  const pending = deferred<{ data: ScanProjectBlockers }>(); mocks.blockers.mockReturnValue(pending.promise)
  const { actions, deps } = setup(); const work = actions.handleDeleteProject()
  deps.project.value = project(8); deps.project.value = project(7)
  pending.resolve({ data: empty }); await work
  expect(mocks.confirm).not.toHaveBeenCalled(); expect(mocks.delete).not.toHaveBeenCalled()
})

it('discards confirmation from a replaced session before issuing deletion', async () => {
  const approval = deferred<void>(); mocks.confirm.mockReturnValue(approval.promise)
  const { actions } = setup(); const work = actions.handleDeleteProject()
  await Promise.resolve(); await Promise.resolve()
  expect(mocks.confirm).toHaveBeenCalledOnce()
  ;(platformSessionId as Ref<string>).value = 'session-b'
  approval.resolve(undefined); await work
  expect(mocks.delete).not.toHaveBeenCalled()
})

it('does not apply completion of a prior deletion to the newly selected project', async () => {
  const pending = deferred<void>(); mocks.delete.mockReturnValue(pending.promise)
  const { actions, deps } = setup(); const work = actions.handleDeleteProject()
  await Promise.resolve(); await Promise.resolve(); await Promise.resolve()
  expect(mocks.delete).toHaveBeenCalledWith(7)
  deps.project.value = project(8); pending.resolve(undefined); await work
  expect(mocks.success).not.toHaveBeenCalled(); expect(mocks.clear).not.toHaveBeenCalled(); expect(mocks.push).not.toHaveBeenCalled()
})

it('does not continue a preflight after the project page is disposed', async () => {
  const pending = deferred<{ data: ScanProjectBlockers }>(); mocks.blockers.mockReturnValue(pending.promise)
  const { actions, scope } = setup(); const work = actions.handleDeleteProject(); scope.stop()
  pending.resolve({ data: empty }); await work
  expect(mocks.confirm).not.toHaveBeenCalled(); expect(mocks.delete).not.toHaveBeenCalled()
})

it('refuses deletion when the blocker check fails', async () => {
  mocks.blockers.mockRejectedValue(new Error('unavailable')); await setup().actions.handleDeleteProject()
  expect(mocks.error).toHaveBeenCalledWith('检查引用关系失败'); expect(mocks.delete).not.toHaveBeenCalled()
})
