import { defineComponent, ref } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { useApiGraphDataActions } from './useApiGraphDataActions'
import type { ApiGraphSnapshot } from '@/api/apiGraph'

const api = vi.hoisted(() => ({ read: vi.fn(), candidates: vi.fn(), regenerate: vi.fn(), confirm: vi.fn() }))
vi.mock('@/api/apiGraph', async () => ({
  ...await vi.importActual<typeof import('@/api/apiGraph')>('@/api/apiGraph'),
  getApiGraphSnapshot: api.read, listApiGraphCandidates: api.candidates, regenerateApiGraph: api.regenerate,
}))
vi.mock('element-plus', () => ({ ElMessage: { success: vi.fn(), error: vi.fn(), warning: vi.fn() },
  ElMessageBox: { confirm: api.confirm } }))
const wrappers: ReturnType<typeof mount>[] = []
afterEach(() => { wrappers.splice(0).forEach(wrapper => wrapper.unmount()); vi.clearAllMocks() })
const graph = (projectId = 7, label = '订单查询'): ApiGraphSnapshot => ({
  nodes: [{ id: 1, projectId, kind: 'API', label, refId: 10, parentId: null, typeName: null, propsJson: '{}' }],
  edges: [], layouts: [],
})
function setup(canWrite?: () => boolean) {
  const projectId = ref(7)
  const snapshot = ref<ApiGraphSnapshot>({ nodes: [], edges: [], layouts: [] })
  const showCandidates = ref(false), candidateEdges = ref<any[]>([])
  let actions!: ReturnType<typeof useApiGraphDataActions>
  const wrapper = mount(defineComponent({ setup() {
    actions = useApiGraphDataActions({ projectId: () => projectId.value, snapshot, showCandidates, candidateEdges,
      manualApiPositions: ref({}), refreshLayoutMetrics: vi.fn(), resetAfterRegenerate: vi.fn(), canWrite })
    return () => null
  } }))
  wrappers.push(wrapper)
  return { actions, snapshot, wrapper, showCandidates, candidateEdges, projectId }
}

describe('project graph response isolation', () => {
  it('releases the main graph while optional candidates are slow and reports a candidate failure independently', async () => {
    let rejectCandidates!: (cause: unknown) => void
    api.read.mockResolvedValue({ data: graph() })
    api.candidates.mockReturnValueOnce(new Promise((_resolve, reject) => { rejectCandidates = reject }))
    const { actions, showCandidates, snapshot, candidateEdges } = setup()
    showCandidates.value = true
    await actions.loadGraph()
    expect(snapshot.value.nodes).toHaveLength(1); expect(actions.loading.value).toBe(false)
    expect(actions.candidateLoading.value).toBe(true)
    rejectCandidates(new Error('unavailable')); await flushPromises()
    expect(actions.loadError.value).toBe(''); expect(actions.candidateError.value).toContain('正式关系仍可查看')
    expect(snapshot.value.nodes).toHaveLength(1); expect(candidateEdges.value).toEqual([])
    expect(actions.candidateLoading.value).toBe(false)
    api.candidates.mockResolvedValueOnce({ data: [{ id: 100, projectId: 7 }] })
    await actions.refreshCandidates()
    expect(actions.candidateError.value).toBe(''); expect(candidateEdges.value[0].id).toBe(100)
  })
  it('discards a late candidate failure when the candidate switch is turned off', async () => {
    let rejectCandidates!: (cause: unknown) => void
    api.read.mockResolvedValue({ data: graph() })
    api.candidates.mockReturnValueOnce(new Promise((_resolve, reject) => { rejectCandidates = reject }))
    const { actions, showCandidates } = setup()
    showCandidates.value = true; await actions.loadGraph()
    showCandidates.value = false; await actions.refreshCandidates()
    expect(actions.candidateLoading.value).toBe(false)
    rejectCandidates(new Error('old candidate failure')); await flushPromises()
    expect(actions.candidateError.value).toBe(''); expect(actions.loadError.value).toBe('')
  })
  it('discards a prior candidate response after a new formal snapshot has replaced it', async () => {
    let resolveCandidates!: (value: unknown) => void
    api.read.mockResolvedValue({ data: graph() })
    api.candidates.mockReturnValueOnce(new Promise(resolve => { resolveCandidates = resolve }))
    const { actions, showCandidates, candidateEdges } = setup()
    showCandidates.value = true; await actions.loadGraph()
    api.candidates.mockResolvedValueOnce({ data: [{ id: 100, projectId: 7 }] })
    await actions.loadGraph(); await flushPromises()
    resolveCandidates({ data: [{ id: 99, projectId: 7 }] }); await flushPromises()
    expect(candidateEdges.value.map(edge => edge.id)).toEqual([100])
    expect(actions.candidateLoading.value).toBe(false); expect(actions.candidateError.value).toBe('')
  })
  it('discards a late candidate response after switching the graph project', async () => {
    let resolveCandidates!: (value: unknown) => void
    api.read.mockResolvedValueOnce({ data: graph(7) }).mockResolvedValueOnce({ data: graph(8) })
    api.candidates.mockReturnValueOnce(new Promise(resolve => { resolveCandidates = resolve }))
      .mockResolvedValueOnce({ data: [{ id: 100, projectId: 8 }] })
    const { actions, showCandidates, candidateEdges, projectId, snapshot } = setup()
    showCandidates.value = true; await actions.loadGraph()
    projectId.value = 8; await actions.loadGraph(); await flushPromises()
    resolveCandidates({ data: [{ id: 99, projectId: 7 }] }); await flushPromises()
    expect(snapshot.value.nodes[0].projectId).toBe(8)
    expect(candidateEdges.value.map(edge => edge.projectId)).toEqual([8])
    expect(actions.candidateError.value).toBe('')
  })
  it('reads candidates independently and prevents a late visible response from restoring hidden candidates', async () => {
    let resolve!: (value: unknown) => void
    api.read.mockResolvedValue({ data: graph() })
    api.candidates.mockReturnValueOnce(new Promise(done => { resolve = done }))
    const { actions, showCandidates, candidateEdges } = setup()
    await actions.loadGraph(); expect(api.read).toHaveBeenCalledOnce()
    showCandidates.value = true
    const pending = actions.refreshCandidates(); expect(api.candidates).toHaveBeenCalledWith(7)
    showCandidates.value = false; await actions.refreshCandidates()
    resolve({ data: [{ id: 99, projectId: 7 }] }); await pending
    expect(candidateEdges.value).toEqual([]); expect(api.read).toHaveBeenCalledOnce()
    showCandidates.value = true; api.candidates.mockResolvedValueOnce({ data: [{ id: 100, projectId: 7 }] })
    await actions.refreshCandidates()
    expect(candidateEdges.value[0].id).toBe(100); expect(api.read).toHaveBeenCalledOnce()
  })
  it('checks write permission again after a destructive confirmation and prevents duplicate confirmations', async () => {
    const canWrite = ref(true)
    let confirm!: () => void
    api.confirm.mockReturnValue(new Promise<void>(resolve => { confirm = resolve }))
    const { actions } = setup(() => canWrite.value)
    const mutation = actions.regenerate()
    const duplicate = actions.regenerate()
    expect(api.confirm).toHaveBeenCalledTimes(1)
    canWrite.value = false
    confirm(); await mutation; await duplicate
    expect(api.regenerate).not.toHaveBeenCalled()
  })
  it('keeps the latest snapshot and discards a late load after refresh', async () => {
    let resolve!: (value: unknown) => void
    api.read.mockReturnValueOnce(new Promise(done => { resolve = done })).mockResolvedValueOnce({ data: graph(7, '当前关系') })
    const { actions, snapshot } = setup()
    const first = actions.loadGraph()
    await actions.refresh()
    resolve({ data: graph(7, '旧关系') }); await first
    expect(snapshot.value.nodes[0].label).toBe('当前关系')
  })
  it('rejects a foreign project and reports failure instead of a known empty graph', async () => {
    api.read.mockResolvedValue({ data: graph(8) })
    const { actions, snapshot } = setup()
    await actions.loadGraph()
    expect(actions.loadError.value).toContain('读取失败')
    expect(snapshot.value.nodes).toEqual([])
  })
  it('does not regenerate after a confirmation resolves on an unmounted project view', async () => {
    let confirm!: () => void
    api.confirm.mockReturnValue(new Promise<void>(resolve => { confirm = resolve }))
    const { actions, wrapper } = setup()
    const mutation = actions.regenerate()
    wrapper.unmount(); confirm(); await mutation; await flushPromises()
    expect(api.regenerate).not.toHaveBeenCalled()
    await actions.refresh()
    expect(api.read).not.toHaveBeenCalled()
  })
})
