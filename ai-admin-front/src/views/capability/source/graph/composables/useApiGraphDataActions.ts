import { nextTick, onBeforeUnmount, ref, type Ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import axios from 'axios'
import {
  getApiGraphSnapshot,
  inferApiGraphModelEdges,
  inferApiGraphRequestResponseEdges,
  listApiGraphCandidates,
  parseApiGraphSnapshot,
  rebuildApiGraph,
  regenerateApiGraph,
  type ApiGraphEdge,
  type ApiGraphSnapshot,
} from '@/api/apiGraph'
import type { Point } from '../apiGraphCanvasGeometry'

export interface UseApiGraphDataActionsDeps {
  projectId: () => number
  showCandidates: Ref<boolean>
  snapshot: Ref<ApiGraphSnapshot>
  candidateEdges: Ref<ApiGraphEdge[]>
  manualApiPositions: Ref<Record<number, Point>>
  refreshLayoutMetrics: () => void
  resetAfterRegenerate: () => void
  canWrite?: () => boolean
}

export function useApiGraphDataActions(deps: UseApiGraphDataActionsDeps) {
  let sequence = 0
  let candidateSequence = 0
  let disposed = false
  const loadError = ref('')
  const loading = ref(false)
  const candidateLoading = ref(false)
  const candidateError = ref('')
  const rebuilding = ref(false)
  const regenerating = ref(false)
  const inferring = ref(false)
  const inferRR = ref(false)
  let confirmingRegeneration = false

  function ownedSnapshot(raw: unknown) {
    const result = parseApiGraphSnapshot(raw)
    if (result.nodes.some(node => node.projectId !== deps.projectId())
      || result.edges.some(edge => edge.projectId !== deps.projectId())) throw new Error('图谱项目身份未确认')
    return result
  }

  function syncManualPositionsFromSnapshot(next: ApiGraphSnapshot) {
    const apiIds = new Set(next.nodes.filter((n) => n.kind === 'API').map((n) => n.id))
    const nextPositions: Record<number, Point> = {}
    for (const lo of next.layouts) {
      if (apiIds.has(lo.nodeId)) {
        nextPositions[lo.nodeId] = { x: lo.x, y: lo.y }
      }
    }
    deps.manualApiPositions.value = nextPositions
  }

  async function refreshCandidateEdges(warnOnFailure: boolean) {
    const expected = sequence
    const candidateRequest = ++candidateSequence
    const projectId = deps.projectId()
    candidateError.value = ''
    if (!deps.showCandidates.value || disposed) { deps.candidateEdges.value = []; candidateLoading.value = false; return }
    candidateLoading.value = true
    try {
      const candidates = deps.showCandidates.value
        ? (await listApiGraphCandidates(projectId)).data
        : []
      if (disposed || expected !== sequence || candidateRequest !== candidateSequence || projectId !== deps.projectId()) return
      if (!Array.isArray(candidates) || candidates.some(edge => edge.projectId !== deps.projectId())) throw new Error('Invalid candidate owner')
      deps.candidateEdges.value = candidates
    } catch {
      if (disposed || expected !== sequence || candidateRequest !== candidateSequence || projectId !== deps.projectId()) return
      deps.candidateEdges.value = []
      candidateError.value = '候选关系读取失败；正式关系仍可查看，请重读候选关系。'
      if (warnOnFailure) {
        ElMessage.warning('候选边列表刷新失败，已暂时隐藏候选边')
      }
    } finally {
      if (!disposed && expected === sequence && candidateRequest === candidateSequence && projectId === deps.projectId()) candidateLoading.value = false
    }
  }

  async function loadGraph() {
    if (disposed) return
    const expected = ++sequence
    candidateSequence++; candidateLoading.value = false; candidateError.value = ''; deps.candidateEdges.value = []
    loading.value = true
    loadError.value = ''
    try {
      const { data } = await getApiGraphSnapshot(deps.projectId())
      if (disposed || expected !== sequence) return
      const nextSnapshot = ownedSnapshot(data)
      deps.snapshot.value = nextSnapshot
      syncManualPositionsFromSnapshot(nextSnapshot)
      void refreshCandidateEdges(false)
    } catch (e) {
      if (disposed || expected !== sequence) return
      loadError.value = '关系图谱读取失败；请刷新后继续，当前不表示没有关系。'
      if (!axios.isAxiosError(e)) {
        ElMessage.error(e instanceof Error ? e.message : '加载接口图谱失败')
      }
    } finally {
      if (!disposed && expected === sequence) {
        loading.value = false
        nextTick(() => deps.refreshLayoutMetrics())
      }
    }
  }

  async function refresh() {
    await loadGraph()
  }

  async function rebuild() {
    if (disposed || rebuilding.value || deps.canWrite?.() === false) return
    const expected = ++sequence
    rebuilding.value = true
    loading.value = true
    try {
      const { data } = await rebuildApiGraph(deps.projectId())
      if (disposed || expected !== sequence) return
      const nextSnapshot = ownedSnapshot(data)
      deps.snapshot.value = nextSnapshot
      syncManualPositionsFromSnapshot(nextSnapshot)
      void refreshCandidateEdges(true)
      const apiCount = nextSnapshot.nodes.filter((n) => n.kind === 'API').length
      ElMessage.success(`图谱重建完成（${apiCount} 个业务操作）`)
    } catch (e) {
      if (disposed || expected !== sequence) return
      if (!axios.isAxiosError(e)) {
        ElMessage.error(e instanceof Error ? e.message : '图谱重建失败')
      }
    } finally {
      if (!disposed) rebuilding.value = false
      if (!disposed && expected === sequence) loading.value = false
    }
  }

  async function regenerate() {
    if (disposed || regenerating.value || confirmingRegeneration || deps.canWrite?.() === false) return
    confirmingRegeneration = true
    try {
      await ElMessageBox.confirm(
        '将清空本项目全部业务能力图谱（节点、连线、画布布局），再按当前资产与来源重新生成。手工连线与卡片位置会丢失，节点 ID 也会重新分配。是否继续？',
        '重新生成图谱',
        {
          confirmButtonText: '清空并重新生成',
          cancelButtonText: '取消',
          type: 'warning',
          autofocus: false,
          closeOnClickModal: false,
        },
      )
    } catch {
      return
    } finally {
      confirmingRegeneration = false
    }
    if (disposed || deps.canWrite?.() === false) return
    const expected = ++sequence
    regenerating.value = true
    loading.value = true
    try {
      const { data } = await regenerateApiGraph(deps.projectId())
      if (disposed || expected !== sequence) return
      const nextSnapshot = ownedSnapshot(data)
      deps.snapshot.value = nextSnapshot
      syncManualPositionsFromSnapshot(nextSnapshot)
      deps.resetAfterRegenerate()
      void refreshCandidateEdges(true)
      const apiCount = nextSnapshot.nodes.filter((n) => n.kind === 'API').length
      ElMessage.success(`图谱已重新生成（${apiCount} 个业务操作）`)
    } catch (e) {
      if (disposed || expected !== sequence) return
      if (!axios.isAxiosError(e)) {
        ElMessage.error(e instanceof Error ? e.message : '图谱重新生成失败')
      }
    } finally {
      if (!disposed) regenerating.value = false
      if (!disposed && expected === sequence) loading.value = false
    }
  }

  async function inferModels() {
    if (inferring.value || disposed || deps.canWrite?.() === false) return
    const expected = ++sequence
    inferring.value = true
    try {
      const { data } = await inferApiGraphModelEdges(deps.projectId())
      if (disposed || expected !== sequence) return
      ElMessage.success(`推断完成，生成 ${data.generated} 条模型边`)
      await loadGraph()
    } catch {
      if (!disposed && expected === sequence) ElMessage.error('推断失败')
    } finally {
      if (!disposed) inferring.value = false
    }
  }

  async function inferRequestResponse() {
    if (inferRR.value || disposed || deps.canWrite?.() === false) return
    const expected = ++sequence
    inferRR.value = true
    try {
      const { data } = await inferApiGraphRequestResponseEdges(deps.projectId())
      if (disposed || expected !== sequence) return
      ElMessage.success(`推断完成，生成 ${data.generated} 条请求/响应边`)
      await loadGraph()
    } catch {
      if (!disposed && expected === sequence) ElMessage.error('推断失败')
    } finally {
      if (!disposed) inferRR.value = false
    }
  }

  onBeforeUnmount(() => { disposed = true; sequence += 1; candidateSequence += 1 })
  return {
    loadError,
    loading,
    candidateLoading,
    candidateError,
    rebuilding,
    regenerating,
    inferring,
    inferRR,
    loadGraph,
    refreshCandidates: () => refreshCandidateEdges(true),
    refresh,
    rebuild,
    regenerate,
    inferModels,
    inferRequestResponse,
  }
}
