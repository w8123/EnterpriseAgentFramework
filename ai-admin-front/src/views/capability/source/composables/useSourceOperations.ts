import { onBeforeUnmount, reactive, ref, type ComputedRef, type Ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { ProjectToolInfo, ScanProject } from '@/types/scanProject'
import {
  getScanProjectTools,
  getScanProjectOperationBlockers,
  reconcileScanProjectTools,
  rescanScanToolFromSource,
  triggerRescan,
} from '@/api/scanProject'
import {
  formatScanProjectBlockersMessage,
  parseScanProjectBlockersFromError,
} from '@/utils/scanProjectBlockers'

export interface UseSourceOperationsDeps {
  projectId: ComputedRef<number>
  project: Ref<ScanProject | null>
  tools: Ref<ProjectToolInfo[]>
  sourceCount?: Ref<number>
  refreshAll: () => Promise<void>
}

export function useSourceOperations(deps: UseSourceOperationsDeps) {
  let disposed = false
  onBeforeUnmount(() => { disposed = true })
  const rescanLoading = ref(false)
  const reconcileLoading = ref(false)
  const rescanSourceLoading = reactive<Record<number, boolean>>({})
  const exportScanToolsExcelLoading = ref(false)

  async function handleReconcile() {
    if (disposed || reconcileLoading.value) return
    reconcileLoading.value = true
    try {
      const { data } = await reconcileScanProjectTools(deps.projectId.value)
      if (disposed) return
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
      if (!disposed) ElMessage.error('只读核对来源关联失败')
    } finally {
      reconcileLoading.value = false
    }
  }

  async function ensureScanOperationAllowed(): Promise<boolean> {
    try {
      const { data } = await getScanProjectOperationBlockers(deps.projectId.value, 'RESCAN')
      if (disposed) return false
      if (!data.blocked) {
        return true
      }
      await ElMessageBox.alert(formatScanProjectBlockersMessage(data), '操作被阻止', {
        type: 'warning',
        confirmButtonText: '知道了',
      })
      return false
    } catch {
      if (!disposed) ElMessage.error('检查引用关系失败')
      return false
    }
  }

  async function handleRescan() {
    if (disposed || rescanLoading.value) return
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
      if (disposed) return
      ElMessage.success(`重新扫描完成，发现 ${data.toolCount} 个接口`)
      await deps.refreshAll()
    } catch (error) {
      if (disposed) return
      const blockers = parseScanProjectBlockersFromError(error)
      if (blockers?.blocked) {
        await ElMessageBox.alert(formatScanProjectBlockersMessage(blockers), '操作被阻止', {
          type: 'warning',
          confirmButtonText: '知道了',
        })
        return
      }
      ElMessage.error('重新扫描失败，请核对来源与扫描设置')
      await deps.refreshAll()
    } finally {
      rescanLoading.value = false
    }
  }

  async function handleRescanToolFromSource(tool: ProjectToolInfo) {
    if (disposed || rescanSourceLoading[tool.scanToolId]) return
    rescanSourceLoading[tool.scanToolId] = true
    try {
      const { data } = await rescanScanToolFromSource(deps.projectId.value, tool.scanToolId)
      if (disposed) return
      const confirmation = data?.httpApiRefresh
      if (data?.sourceObservation?.scanToolId === tool.scanToolId && confirmation?.confirmed === true
        && confirmation.observed === 1 && confirmation.fullSyncRequired === false) {
        ElMessage.success('来源已刷新，所属 HTTP API 来源已确认；请在 API 目录核对契约变化')
      } else {
        const reason = typeof confirmation?.reason === 'string' ? confirmation.reason.trim() : ''
        ElMessage.warning(`单条刷新未能确认所属 HTTP API，需要完整项目同步。${reason ? `原因：${reason}` : '当前确认结果未知。'}`)
      }
      await deps.refreshAll()
    } catch {
      // 错误文案由 axios 拦截器展示
    } finally {
      rescanSourceLoading[tool.scanToolId] = false
    }
  }





  async function handleExportScanToolsExcel() {
    if (disposed || exportScanToolsExcelLoading.value) return
    if (!(deps.sourceCount?.value ?? deps.tools.value.length)) {
      ElMessage.warning('暂无接口可导出')
      return
    }
    exportScanToolsExcelLoading.value = true
    const owner = deps.projectId.value
    try {
      const name = deps.project.value?.name?.trim() || `项目${deps.projectId.value}`
      const { data } = await getScanProjectTools(owner, 'full')
      if (disposed || owner !== deps.projectId.value) return
      if (!Array.isArray(data) || data.some(row => !Number.isSafeInteger(row.scanToolId) || row.scanToolId <= 0
        || row.projectId != null && row.projectId !== owner)) throw new Error('Invalid source export')
      const { exportScanProjectToolsExcel } = await import('@/utils/scanProjectToolExport')
      if (disposed || owner !== deps.projectId.value) return
      exportScanProjectToolsExcel(data, name)
      ElMessage.success('已导出 Excel')
    } catch {
      if (!disposed) ElMessage.error('读取完整来源失败，未导出；请重试')
    } finally {
      exportScanToolsExcelLoading.value = false
    }
  }

  return {
    rescanLoading,
    reconcileLoading,
    rescanSourceLoading,
    exportScanToolsExcelLoading,
    handleReconcile,
    handleRescan,
    handleRescanToolFromSource,
    handleExportScanToolsExcel,
  }
}
