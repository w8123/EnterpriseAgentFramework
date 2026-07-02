import { reactive, ref, type ComputedRef, type Ref } from 'vue'
import { ElMessage } from 'element-plus'
import type { ProjectToolInfo } from '@/types/scanProject'
import { getScanProjectTool } from '@/api/scanProject'

export interface UseScanProjectToolDetailsDeps {
  projectId: ComputedRef<number>
  tools: Ref<ProjectToolInfo[]>
}

export function useScanProjectToolDetails(deps: UseScanProjectToolDetailsDeps) {
  const diffDialogVisible = ref(false)
  const diffDialogRow = ref<ProjectToolInfo | null>(null)
  const toolDetailLoading = reactive<Record<number, boolean>>({})
  const toolDetailLoaded = reactive<Record<number, boolean>>({})
  const toolDetailPromises = new Map<number, Promise<ProjectToolInfo>>()

  function resetToolDetailCache() {
    Object.keys(toolDetailLoaded).forEach((key) => delete toolDetailLoaded[Number(key)])
    Object.keys(toolDetailLoading).forEach((key) => delete toolDetailLoading[Number(key)])
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
    if (toolDetailLoaded[tool.scanToolId]) {
      return tool
    }
    const pending = toolDetailPromises.get(tool.scanToolId)
    if (pending) {
      return pending
    }
    toolDetailLoading[tool.scanToolId] = true
    const request = (async () => {
      const { data } = await getScanProjectTool(deps.projectId.value, tool.scanToolId)
      return mergeScanToolDetail(data)
    })()
    toolDetailPromises.set(tool.scanToolId, request)
    try {
      return await request
    } catch (error) {
      ElMessage.error((error as Error).message || '加载接口详情失败')
      return tool
    } finally {
      toolDetailLoading[tool.scanToolId] = false
      toolDetailPromises.delete(tool.scanToolId)
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
      NOT_LINKED: '未添加',
      IN_SYNC: '已同步',
      PENDING_UPDATE: '待更新',
      API_REMOVED_STALE: '源已移除',
      GLOBAL_MISSING: '关联断开',
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
    resetToolDetailCache,
    scanToolRowClassName,
    ensureToolDetail,
    handleToolExpandChange,
    toolLinkLabel,
    toolLinkTagType,
    openDiffDialog,
  }
}
