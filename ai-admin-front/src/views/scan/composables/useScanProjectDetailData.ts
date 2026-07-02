import { computed, ref, type ComputedRef } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { getScanProjectDetail, getScanProjectTools } from '@/api/scanProject'
import type { ProjectToolInfo, ScanProject } from '@/types/scanProject'

export interface ScanProjectRefreshSideEffects {
  loadSemanticAssets: () => Promise<void>
  applyProjectLoaded: (project: ScanProject) => void
  onToolsLoaded: () => void
  onRefreshFailed: () => void
}

export interface UseScanProjectDetailDataDeps {
  sideEffects: () => ScanProjectRefreshSideEffects
  projectId?: ComputedRef<number>
}

export function useScanProjectDetailData(deps: UseScanProjectDetailDataDeps) {
  const route = useRoute()
  const projectId = deps.projectId ?? computed(() => Number(route.params.id))
  const project = ref<ScanProject | null>(null)
  const tools = ref<ProjectToolInfo[]>([])
  const loading = ref(false)

  async function refreshAll() {
    loading.value = true
    const fx = deps.sideEffects()
    try {
      const [projectResponse, toolResponse] = await Promise.all([
        getScanProjectDetail(projectId.value),
        getScanProjectTools(projectId.value, 'summary'),
        fx.loadSemanticAssets(),
      ])
      project.value = projectResponse.data
      fx.applyProjectLoaded(projectResponse.data)
      tools.value = Array.isArray(toolResponse.data) ? toolResponse.data : []
      fx.onToolsLoaded()
    } catch {
      project.value = null
      tools.value = []
      fx.onRefreshFailed()
      ElMessage.error('加载扫描详情失败')
    } finally {
      loading.value = false
    }
  }

  return {
    projectId,
    project,
    tools,
    loading,
    refreshAll,
  }
}
