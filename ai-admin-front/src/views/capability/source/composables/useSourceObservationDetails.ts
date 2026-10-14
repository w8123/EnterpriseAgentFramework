import { onBeforeUnmount, reactive, ref, type ComputedRef, type Ref } from 'vue'
import type { ProjectToolInfo } from '@/types/scanProject'
import { getScanProjectTool } from '@/api/scanProject'

export interface UseSourceObservationDetailsDeps {
  projectId: ComputedRef<number>
  tools: Ref<ProjectToolInfo[]>
}

export function useSourceObservationDetails(deps: UseSourceObservationDetailsDeps) {
  let disposed = false
  let revision = 0
  onBeforeUnmount(() => { disposed = true; revision += 1; toolDetailPromises.clear() })
  const diffDialogVisible = ref(false)
  const diffDialogRow = ref<ProjectToolInfo | null>(null)
  const toolDetailLoading = reactive<Record<number, boolean>>({})
  const toolDetailError = reactive<Record<number, string>>({})
  const toolDetailLoaded = reactive<Record<number, boolean>>({})
  const toolDetailPromises = new Map<number, Promise<ProjectToolInfo>>()

  function resetToolDetailCache() {
    revision += 1
    toolDetailPromises.clear()
    Object.keys(toolDetailLoaded).forEach((key) => delete toolDetailLoaded[Number(key)])
    Object.keys(toolDetailLoading).forEach((key) => delete toolDetailLoading[Number(key)])
    Object.keys(toolDetailError).forEach((key) => delete toolDetailError[Number(key)])
  }

  function scanToolRowClassName({ row }: { row: ProjectToolInfo }) {
    return row.removedFromSource ? 'row-api-tombstone' : ''
  }

  function mergeScanToolDetail(detail: ProjectToolInfo): ProjectToolInfo {
    const index = deps.tools.value.findIndex((item) => item.scanToolId === detail.scanToolId)
    if (index >= 0) {
      Object.assign(deps.tools.value[index], detail)
      toolDetailLoaded[detail.scanToolId] = true
      return deps.tools.value[index]
    }

    deps.tools.value.push(detail)
    toolDetailLoaded[detail.scanToolId] = true
    return detail
  }

  async function ensureToolDetail(tool: ProjectToolInfo): Promise<ProjectToolInfo> {
    if (disposed) return tool
    if (toolDetailLoaded[tool.scanToolId]) {
      return tool
    }
    const pending = toolDetailPromises.get(tool.scanToolId)
    if (pending) {
      return pending
    }
    toolDetailLoading[tool.scanToolId] = true
    delete toolDetailError[tool.scanToolId]
    const expected = revision
    const request = (async () => {
      const { data } = await getScanProjectTool(deps.projectId.value, tool.scanToolId)
      if (disposed || expected !== revision) return tool
      if (data.scanToolId !== tool.scanToolId) throw new Error('Invalid source owner')
      return mergeScanToolDetail(data)
    })()
    toolDetailPromises.set(tool.scanToolId, request)
    try {
      return await request
    } catch (error) {
      if (!disposed && expected === revision) toolDetailError[tool.scanToolId] = '读取来源详情失败，请重新加载。'
      return tool
    } finally {
      if (!disposed && expected === revision) {
        toolDetailLoading[tool.scanToolId] = false
        toolDetailPromises.delete(tool.scanToolId)
      }
    }
  }

  async function handleToolExpandChange(row: ProjectToolInfo, expandedRows: ProjectToolInfo[]) {
    if (!expandedRows.some((item) => item.scanToolId === row.scanToolId)) {
      return
    }
    await ensureToolDetail(row)
  }

  function toolLinkLabel(row: ProjectToolInfo) {
    const labels: Record<string, string> = {
      NOT_LINKED: '无历史关联',
      IN_SYNC: '历史投影一致',
      PENDING_UPDATE: '历史投影差异',
      API_REMOVED_STALE: '源已移除',
      GLOBAL_MISSING: '历史投影缺失',
    }
    const status = row.toolLinkStatus || 'NOT_LINKED'
    return labels[status] || status
  }

  function toolLinkTagType(row: ProjectToolInfo) {
    switch (row.toolLinkStatus) {
      case 'IN_SYNC':
        return 'success'
      case 'PENDING_UPDATE':
        return 'warning'
      case 'API_REMOVED_STALE':
      case 'GLOBAL_MISSING':
        return 'danger'
      default:
        return 'info'
    }
  }

  function openDiffDialog(row: ProjectToolInfo) {
    diffDialogRow.value = row
    diffDialogVisible.value = true
  }

  return {
    diffDialogVisible,
    diffDialogRow,
    toolDetailLoading,
    toolDetailError,
    resetToolDetailCache,
    scanToolRowClassName,
    ensureToolDetail,
    handleToolExpandChange,
    toolLinkLabel,
    toolLinkTagType,
    openDiffDialog,
  }
}
