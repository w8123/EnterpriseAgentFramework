import { nextTick, ref, type Ref } from 'vue'
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
}

export function useApiGraphDataActions(deps: UseApiGraphDataActionsDeps) {
  const loading = ref(false)
  const rebuilding = ref(false)
  const regenerating = ref(false)
  const inferring = ref(false)
  const inferRR = ref(false)

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
    try {
      deps.candidateEdges.value = deps.showCandidates.value
        ? (await listApiGraphCandidates(deps.projectId())).data
        : []
    } catch {
      deps.candidateEdges.value = []
      if (warnOnFailure) {
        ElMessage.warning('候选边列表刷新失败，已暂时隐藏候选边')
      }
    }
  }

  async function loadGraph() {
    loading.value = true
    try {
      const { data } = await getApiGraphSnapshot(deps.projectId())
      const nextSnapshot = parseApiGraphSnapshot(data)
      deps.snapshot.value = nextSnapshot
      syncManualPositionsFromSnapshot(nextSnapshot)
      await refreshCandidateEdges(false)
    } catch (e) {
      console.error('[ApiGraph] loadGraph failed', e)
      if (!axios.isAxiosError(e)) {
        ElMessage.error(e instanceof Error ? e.message : '加载接口图谱失败')
      }
    } finally {
      loading.value = false
      nextTick(() => deps.refreshLayoutMetrics())
    }
  }

  async function refresh() {
    await loadGraph()
  }

  async function rebuild() {
    rebuilding.value = true
    loading.value = true
    try {
      const { data } = await rebuildApiGraph(deps.projectId())
      const nextSnapshot = parseApiGraphSnapshot(data)
      deps.snapshot.value = nextSnapshot
      syncManualPositionsFromSnapshot(nextSnapshot)
      await refreshCandidateEdges(true)
      const apiCount = nextSnapshot.nodes.filter((n) => n.kind === 'API').length
      ElMessage.success(`图谱重建完成（${apiCount} 个接口）`)
    } catch (e) {
      console.error('[ApiGraph] rebuild failed', e)
      if (!axios.isAxiosError(e)) {
        ElMessage.error(e instanceof Error ? e.message : '图谱重建失败')
      }
    } finally {
      rebuilding.value = false
      loading.value = false
    }
  }

  async function regenerate() {
    try {
      await ElMessageBox.confirm(
        '将清空本项目全部接口图谱（节点、连线、画布布局），再按当前扫描结果重新生成。手工连线与卡片位置会丢失，节点 ID 也会重新分配。是否继续？',
        '重新生成图谱',
        {
          confirmButtonText: '清空并重新生成',
          cancelButtonText: '取消',
          type: 'warning',
        },
      )
    } catch {
      return
    }
    regenerating.value = true
    loading.value = true
    try {
      const { data } = await regenerateApiGraph(deps.projectId())
      const nextSnapshot = parseApiGraphSnapshot(data)
      deps.snapshot.value = nextSnapshot
      syncManualPositionsFromSnapshot(nextSnapshot)
      deps.resetAfterRegenerate()
      await refreshCandidateEdges(true)
      const apiCount = nextSnapshot.nodes.filter((n) => n.kind === 'API').length
      ElMessage.success(`图谱已重新生成（${apiCount} 个接口）`)
    } catch (e) {
      console.error('[ApiGraph] regenerate failed', e)
      if (!axios.isAxiosError(e)) {
        ElMessage.error(e instanceof Error ? e.message : '图谱重新生成失败')
      }
    } finally {
      regenerating.value = false
      loading.value = false
    }
  }

  async function inferModels() {
    inferring.value = true
    try {
      const { data } = await inferApiGraphModelEdges(deps.projectId())
      ElMessage.success(`推断完成，生成 ${data.generated} 条模型边`)
      await loadGraph()
    } catch {
      ElMessage.error('推断失败')
    } finally {
      inferring.value = false
    }
  }

  async function inferRequestResponse() {
    inferRR.value = true
    try {
      const { data } = await inferApiGraphRequestResponseEdges(deps.projectId())
      ElMessage.success(`推断完成，生成 ${data.generated} 条请求/响应边`)
      await loadGraph()
    } catch {
      ElMessage.error('推断失败')
    } finally {
      inferRR.value = false
    }
  }

  return {
    loading,
    rebuilding,
    regenerating,
    inferring,
    inferRR,
    loadGraph,
    refresh,
    rebuild,
    regenerate,
    inferModels,
    inferRequestResponse,
  }
}
