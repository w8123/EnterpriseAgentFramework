import { reactive, ref, type ComputedRef, type Ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { ProjectToolInfo, ScanProject } from '@/types/scanProject'
import {
  getScanProjectTools,
  getScanProjectOperationBlockers,
  reconcileScanProjectTools,
  rescanScanToolFromSource,
  triggerRescan,
} from '@/api/scanProject'
import { startToolRetrievalRebuild } from '@/api/toolRetrieval'
import {
  formatScanProjectBlockersMessage,
  parseScanProjectBlockersFromError,
} from '@/utils/scanProjectBlockers'
import { exportScanProjectToolsExcel } from '@/utils/scanProjectToolExport'

export interface UseScanProjectToolOperationsDeps {
  projectId: ComputedRef<number>
  project: Ref<ScanProject | null>
  tools: Ref<ProjectToolInfo[]>
  refreshAll: () => Promise<void>
}

export function useScanProjectToolOperations(deps: UseScanProjectToolOperationsDeps) {
  const rescanLoading = ref(false)
  const rebuildEmbeddingLoading = ref(false)
  const reconcileLoading = ref(false)
  const rescanSourceLoading = reactive<Record<number, boolean>>({})
  const exportScanToolsExcelLoading = ref(false)

  async function handleReconcile() {
    reconcileLoading.value = true
    try {
      const { data } = await reconcileScanProjectTools(deps.projectId.value)
      const message = [
        `无历史关联 ${data.notLinked}`,
        `历史投影一致 ${data.inSync}`,
        `历史投影差异 ${data.pendingUpdate}`,
        `源已移除 ${data.apiRemovedStale}`,
        `历史投影缺失 ${data.globalMissing}`,
      ].join('，')
      ElMessage.success(`只读核对完成（不修改投影或接纳状态）：${message}`)
      await deps.refreshAll()
    } catch {
      ElMessage.error('只读核对来源关联失败')
    } finally {
      reconcileLoading.value = false
    }
  }

  async function handleRebuildEmbeddings() {
    rebuildEmbeddingLoading.value = true
    try {
      const { data } = await startToolRetrievalRebuild()
      ElMessage.success(`已提交向量索引重建任务 (${data.taskId.slice(0, 8)})，可在「调用候选检索」页查看进度`)
    } catch (error) {
      ElMessage.error((error as Error).message || '重建向量索引失败')
    } finally {
      rebuildEmbeddingLoading.value = false
    }
  }

  async function ensureScanOperationAllowed(): Promise<boolean> {
    try {
      const { data } = await getScanProjectOperationBlockers(deps.projectId.value)
      if (!data.blocked) {
        return true
      }
      await ElMessageBox.alert(formatScanProjectBlockersMessage(data), '操作被阻止', {
        type: 'warning',
        confirmButtonText: '知道了',
      })
      return false
    } catch {
      ElMessage.error('检查引用关系失败')
      return false
    }
  }

  async function handleRescan() {
    if (deps.project.value?.projectKind === 'REGISTERED') {
      ElMessage.warning('SDK 接入项目由业务系统同步能力，不需要扫描')
      return
    }
    rescanLoading.value = true
    try {
      if (!(await ensureScanOperationAllowed())) {
        return
      }
      const { data } = await triggerRescan(deps.projectId.value)
      ElMessage.success(`重新扫描完成，发现 ${data.toolCount} 个接口`)
      await deps.refreshAll()
    } catch (error) {
      const blockers = parseScanProjectBlockersFromError(error)
      if (blockers?.blocked) {
        await ElMessageBox.alert(formatScanProjectBlockersMessage(blockers), '操作被阻止', {
          type: 'warning',
          confirmButtonText: '知道了',
        })
        return
      }
      ElMessage.error((error as Error).message || '重新扫描失败')
      await deps.refreshAll()
    } finally {
      rescanLoading.value = false
    }
  }

  async function handleRescanToolFromSource(tool: ProjectToolInfo) {
    rescanSourceLoading[tool.scanToolId] = true
    try {
      await rescanScanToolFromSource(deps.projectId.value, tool.scanToolId)
      ElMessage.success('已从源码更新该接口')
      await deps.refreshAll()
    } catch {
      // 错误文案由 axios 拦截器展示
    } finally {
      rescanSourceLoading[tool.scanToolId] = false
    }
  }





  async function handleExportScanToolsExcel() {
    if (!deps.tools.value.length) {
      ElMessage.warning('暂无接口可导出')
      return
    }
    exportScanToolsExcelLoading.value = true
    try {
      const name = deps.project.value?.name?.trim() || `项目${deps.projectId.value}`
      const { data } = await getScanProjectTools(deps.projectId.value, 'full')
      exportScanProjectToolsExcel(Array.isArray(data) ? data : deps.tools.value, name)
      ElMessage.success('已导出 Excel')
    } finally {
      exportScanToolsExcelLoading.value = false
    }
  }

  return {
    rescanLoading,
    rebuildEmbeddingLoading,
    reconcileLoading,
    rescanSourceLoading,
    exportScanToolsExcelLoading,
    handleReconcile,
    handleRebuildEmbeddings,
    handleRescan,
    handleRescanToolFromSource,
    handleExportScanToolsExcel,
  }
}
