import { computed, defineComponent, ref } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { useSourceOperations } from './useSourceOperations'
import type { ProjectToolInfo, ScanProject } from '@/types/scanProject'
import { ElMessage } from 'element-plus'

const api = vi.hoisted(() => ({ blockers: vi.fn(), rescan: vi.fn(), sourceRescan: vi.fn(), tools: vi.fn(), export: vi.fn() }))
vi.mock('@/api/scanProject', () => ({ getScanProjectTools: api.tools, getScanProjectOperationBlockers: api.blockers,
  reconcileScanProjectTools: vi.fn(), rescanScanToolFromSource: api.sourceRescan, triggerRescan: api.rescan }))
vi.mock('@/utils/scanProjectToolExport', () => ({ exportScanProjectToolsExcel: api.export }))
vi.mock('element-plus', () => ({ ElMessage: { success: vi.fn(), error: vi.fn(), warning: vi.fn() },
  ElMessageBox: { alert: vi.fn() } }))
const wrappers: ReturnType<typeof mount>[] = []
afterEach(() => { wrappers.splice(0).forEach(wrapper => wrapper.unmount()); vi.clearAllMocks() })
function setup(records: ProjectToolInfo[] = [{ scanToolId: 1 }] as ProjectToolInfo[], sourceCount = records.length) {
  let state!: ReturnType<typeof useSourceOperations>
  const refresh = vi.fn().mockResolvedValue(undefined)
  const wrapper = mount(defineComponent({ setup() {
    state = useSourceOperations({ projectId: computed(() => 7),
      project: ref({ id: 7, name: '订单', projectKind: 'SCAN' } as ScanProject),
      tools: ref(records), sourceCount: ref(sourceCount), refreshAll: refresh })
    return () => null
  } }))
  wrappers.push(wrapper); return { state, wrapper, refresh }
}
describe('source operations remain bound to the mounted project', () => {
  it('does not read full sources until export and exports the full project even when the filtered page is empty', async () => {
    const { state } = setup([], 105)
    expect(api.tools).not.toHaveBeenCalled(); expect(api.export).not.toHaveBeenCalled()
    const records = Array.from({ length: 105 }, (_, index) => ({ scanToolId: index + 1, projectId: 7 }))
    api.tools.mockResolvedValueOnce({ data: records })
    await state.handleExportScanToolsExcel()
    expect(api.tools).toHaveBeenCalledOnce(); expect(api.tools).toHaveBeenCalledWith(7, 'full')
    expect(api.export).toHaveBeenCalledWith(records, '订单')
  })
  it('reports a refreshed source only when the HTTP API identity is confirmed, without claiming contract acceptance', async () => {
    api.sourceRescan.mockResolvedValue({ data: { sourceObservation: { scanToolId: 1 },
      httpApiRefresh: { confirmed: true, observed: 1, reason: null, fullSyncRequired: false } } })
    const { state, refresh } = setup()
    await state.handleRescanToolFromSource({ scanToolId: 1 } as ProjectToolInfo)
    expect(api.sourceRescan).toHaveBeenCalledWith(7, 1)
    expect(ElMessage.success).toHaveBeenCalledWith('来源已刷新，所属 HTTP API 来源已确认；请在 API 目录核对契约变化')
    expect(ElMessage.warning).not.toHaveBeenCalled()
    expect(refresh).toHaveBeenCalledOnce()
  })
  it.each([
    { confirmed: false, observed: 0, reason: '新清单存在多个匹配项', fullSyncRequired: true },
    undefined,
  ])('keeps ambiguous or unknown confirmation distinct from a successful HTTP API refresh: %j', async httpApiRefresh => {
    api.sourceRescan.mockResolvedValue({ data: { sourceObservation: { scanToolId: 1 }, httpApiRefresh } })
    const { state, refresh } = setup()
    await state.handleRescanToolFromSource({ scanToolId: 1 } as ProjectToolInfo)
    expect(ElMessage.success).not.toHaveBeenCalled()
    expect(ElMessage.warning).toHaveBeenCalledWith(expect.stringContaining('单条刷新未能确认所属 HTTP API，需要完整项目同步'))
    expect(ElMessage.warning).toHaveBeenCalledWith(expect.stringContaining(httpApiRefresh?.reason || '当前确认结果未知'))
    expect(refresh).toHaveBeenCalledOnce()
  })
  it('does not dispatch a rescan after its blocker check resolves on a disposed project', async () => {
    let resolve!: (value: unknown) => void
    api.blockers.mockReturnValue(new Promise(done => { resolve = done }))
    const { state, wrapper, refresh } = setup()
    const mutation = state.handleRescan()
    wrapper.unmount(); resolve({ data: { blocked: false } }); await mutation
    expect(api.rescan).not.toHaveBeenCalled()
    expect(refresh).not.toHaveBeenCalled()
  })
  it('exports only the full response and never substitutes cached rows when the full read fails', async () => {
    const { state } = setup()
    api.tools.mockResolvedValue({ data: null }); await state.handleExportScanToolsExcel()
    expect(api.export).not.toHaveBeenCalled()
    const records = [{ scanToolId: 1 }, { scanToolId: 2 }]
    api.tools.mockResolvedValue({ data: records }); await state.handleExportScanToolsExcel()
    expect(api.tools).toHaveBeenCalledWith(7, 'full')
    expect(api.export).toHaveBeenCalledWith(records, '订单')
  })
  it('does not download old-project data after its view is unmounted', async () => {
    let resolve!: (value: unknown) => void
    api.tools.mockReturnValue(new Promise(done => { resolve = done }))
    const { state, wrapper } = setup()
    const read = state.handleExportScanToolsExcel()
    wrapper.unmount(); resolve({ data: [{ scanToolId: 99 }] }); await read; await flushPromises()
    expect(api.export).not.toHaveBeenCalled()
  })
})
