import { reactive, ref, type ComputedRef, type Ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { ProjectToolInfo, ScanProject } from '@/types/scanProject'
import {
  getScanProjectTools,
  getScanProjectOperationBlockers,
  promoteScanModuleToolsToGlobal,
  promoteScanProjectToolToGlobal,
  pushScanProjectToolToGlobalTool,
  reconcileScanProjectTools,
  rescanScanToolFromSource,
  triggerRescan,
  unpromoteScanProjectToolFromGlobal,
} from '@/api/scanProject'
import { startToolRetrievalRebuild } from '@/api/toolRetrieval'
import {
  formatScanProjectBlockersMessage,
  parseScanProjectBlockersFromError,
} from '@/utils/scanProjectBlockers'
import { exportScanProjectToolsExcel } from '@/utils/scanProjectToolExport'
import type { ToolModuleGroup } from './useScanProjectSummary'

export interface UseScanProjectToolOperationsDeps {
  projectId: ComputedRef<number>
  project: Ref<ScanProject | null>
  tools: Ref<ProjectToolInfo[]>
  refreshAll: () => Promise<void>
  reloadAiTab: () => Promise<void>
}

export function useScanProjectToolOperations(deps: UseScanProjectToolOperationsDeps) {
  const rescanLoading = ref(false)
  const rebuildEmbeddingLoading = ref(false)
  const reconcileLoading = ref(false)
  const promoteLoading = reactive<Record<number, boolean>>({})
  const pushToGlobalLoading = reactive<Record<number, boolean>>({})
  const unpromoteLoading = reactive<Record<number, boolean>>({})
  const batchModulePromoteLoading = reactive<Record<string, boolean>>({})
  const rescanSourceLoading = reactive<Record<number, boolean>>({})
  const exportScanToolsExcelLoading = ref(false)

  async function handleReconcile() {
    reconcileLoading.value = true
    try {
      const { data } = await reconcileScanProjectTools(deps.projectId.value)
      const message = [
        `镜像补齐 ${data.sdkMirrorsEnsured}`,
        `未添加 ${data.notLinked}`,
        `已同步 ${data.inSync}`,
        `待更新 ${data.pendingUpdate}`,
        `源已移除 ${data.apiRemovedStale}`,
        `关联断开 ${data.globalMissing}`,
        `SDK 待评审行 ${data.sdkReviewPendingRows}`,
      ].join('，')
      ElMessage.success(`对账完成：${message}`)
      await deps.refreshAll()
    } catch {
      ElMessage.error('对账失败')
    } finally {
      reconcileLoading.value = false
    }
  }

  async function handleRebuildEmbeddings() {
    rebuildEmbeddingLoading.value = true
    try {
      const { data } = await startToolRetrievalRebuild()
      ElMessage.success(`已提交向量索引重建任务 (${data.taskId.slice(0, 8)})，可在「Tool 检索测试」页查看进度`)
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

  async function handlePromoteToGlobal(tool: ProjectToolInfo) {
    promoteLoading[tool.scanToolId] = true
    try {
      const { data } = await promoteScanProjectToolToGlobal(deps.projectId.value, tool.scanToolId)
      ElMessage.success(`已添加到 Tool 管理，全局名称：${data.globalToolName}`)
      await deps.refreshAll()
      await deps.reloadAiTab()
    } catch (error) {
      ElMessage.error((error as Error).message || '添加失败')
    } finally {
      promoteLoading[tool.scanToolId] = false
    }
  }

  async function handlePushToGlobalTool(tool: ProjectToolInfo) {
    pushToGlobalLoading[tool.scanToolId] = true
    try {
      await pushScanProjectToolToGlobalTool(deps.projectId.value, tool.scanToolId)
      ElMessage.success('已更新到 Tool 管理中的对应工具')
      await deps.refreshAll()
    } catch (error) {
      ElMessage.error((error as Error).message || '更新失败')
    } finally {
      pushToGlobalLoading[tool.scanToolId] = false
    }
  }

  async function handleUnpromoteFromGlobal(tool: ProjectToolInfo) {
    try {
      await ElMessageBox.confirm(
        '将删除 Tool 管理中的该工具，并解除与本扫描接口的关联。若需对外暴露，可再次点「添加为 Tool」。',
        '从Tool中下架',
        { type: 'warning', confirmButtonText: '确定下架', cancelButtonText: '取消' },
      )
    } catch {
      return
    }
    unpromoteLoading[tool.scanToolId] = true
    try {
      await unpromoteScanProjectToolFromGlobal(deps.projectId.value, tool.scanToolId)
      ElMessage.success('已从 Tool 中下架')
      await deps.refreshAll()
      await deps.reloadAiTab()
    } catch (error) {
      ElMessage.error((error as Error).message || '下架失败')
    } finally {
      unpromoteLoading[tool.scanToolId] = false
    }
  }

  async function handlePromoteModuleToGlobal(group: ToolModuleGroup) {
    if (group.tools.length === 0) return
    batchModulePromoteLoading[group.key] = true
    try {
      const { data } = await promoteScanModuleToolsToGlobal(deps.projectId.value, group.moduleId)
      if (data.promotedCount === 0) {
        ElMessage.info('本模块下没有可添加的接口')
        return
      }
      ElMessage.success(`已添加 ${data.promotedCount} 个接口到 Tool 管理`)
      await deps.refreshAll()
      await deps.reloadAiTab()
    } catch (error) {
      ElMessage.error((error as Error).message || '批量添加失败')
    } finally {
      batchModulePromoteLoading[group.key] = false
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
    promoteLoading,
    pushToGlobalLoading,
    unpromoteLoading,
    batchModulePromoteLoading,
    rescanSourceLoading,
    exportScanToolsExcelLoading,
    handleReconcile,
    handleRebuildEmbeddings,
    handleRescan,
    handleRescanToolFromSource,
    handlePromoteToGlobal,
    handlePushToGlobalTool,
    handleUnpromoteFromGlobal,
    handlePromoteModuleToGlobal,
    handleExportScanToolsExcel,
  }
}
