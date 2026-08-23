import { ElMessage, ElMessageBox } from 'element-plus'
import { computed, type ComputedRef, type Ref } from 'vue'
import { publishWorkflowVersion, validateWorkflowVersion } from '@/api/workflow'
import type {
  WorkflowPublishRequest,
  WorkflowReleaseValidationItem,
  WorkflowRuntimeValidationResult,
  WorkflowWorkingCopyState,
  WorkflowValidationItem,
} from '@/types/workflow'
import type { CanvasNode } from '@/types/studio'
import type { GraphLintItem } from '@/views/workflow/composables/useWorkflowStudioGraphAnalysis'

export interface UseWorkflowStudioReleaseDeps {
  workflowId: Readonly<Ref<string>>
  studioReadOnly: Readonly<Ref<boolean>>
  studio: Ref<WorkflowWorkingCopyState | null>
  nodes: Ref<CanvasNode[]>
  graphLintErrors: Readonly<Ref<GraphLintItem[]>>
  graphLintWarnings: Readonly<Ref<GraphLintItem[]>>
  editGeneration: Readonly<Ref<number>>
  publishing: Ref<boolean>
  releaseChecking: Ref<boolean>
  releaseValidationReady: Ref<boolean>
  publishDialogOpen: Ref<boolean>
  releaseErrors: Ref<WorkflowReleaseValidationItem[]>
  releaseWarnings: Ref<WorkflowReleaseValidationItem[]>
  publishForm: WorkflowPublishRequest
  validateWorkingCopy: () => Promise<WorkflowRuntimeValidationResult | null>
  saveStudio: () => Promise<WorkflowWorkingCopyState | null>
  loadStudio: () => Promise<WorkflowWorkingCopyState | null>
  ensurePanelValidationClear?: () => boolean
  syncPublishedPageAssistant?: () => Promise<boolean>
}

export function useWorkflowStudioRelease({
  workflowId,
  studioReadOnly,
  studio,
  nodes,
  graphLintErrors,
  graphLintWarnings,
  editGeneration,
  publishing,
  releaseChecking,
  releaseValidationReady,
  publishDialogOpen,
  releaseErrors,
  releaseWarnings,
  publishForm,
  validateWorkingCopy,
  saveStudio,
  loadStudio,
  ensurePanelValidationClear,
  syncPublishedPageAssistant,
}: UseWorkflowStudioReleaseDeps) {
  const publishWarnings: ComputedRef<string[]> = computed(() => {
    const warnings: string[] = []
    if (!studio.value?.keySlug) {
      warnings.push('未配置 keySlug，业务系统可能无法稳定访问发布后的 Workflow。')
    }
    const callableNodeCount = nodes.value.filter((node) =>
      ['tool', 'http', 'pageAction', 'mcp'].includes(node.data.kind),
    ).length
    if (!callableNodeCount) {
      warnings.push('画布中没有工具、能力、接口、页面动作或 MCP 节点，本版本只能进行纯流程/纯对话编排。')
    }
    if ((publishForm.rolloutPercent ?? 100) === 100) {
      warnings.push('本次为全量发布，会替换该 Workflow 的历史 ACTIVE 全量版本。')
    }
    return warnings
  })

  function localValidationItem(item: GraphLintItem, level: 'ERROR' | 'WARN'): WorkflowReleaseValidationItem {
    return {
      code: 'GRAPH_LOCAL_LINT',
      level,
      nodeId: item.nodeId || null,
      message: item.edgeId ? `${item.message}（连线 ${item.edgeId}）` : item.message,
    }
  }

  function localReleaseErrors() {
    return graphLintErrors.value.map((item) => localValidationItem(item, 'ERROR'))
  }

  function localReleaseWarnings() {
    return graphLintWarnings.value.map((item) => localValidationItem(item, 'WARN'))
  }

  async function publishWorkflow() {
    if (studioReadOnly.value) {
      ElMessage.info('代码托管 Workflow 当前为只读草稿，请修改后重启同步。')
      return
    }
    if (ensurePanelValidationClear && !ensurePanelValidationClear()) {
      return
    }
    publishDialogOpen.value = true
    releaseValidationReady.value = false
    releaseErrors.value = localReleaseErrors()
    releaseWarnings.value = localReleaseWarnings()
    releaseChecking.value = true
    try {
      const result = await validateWorkingCopy()
      if (!result) {
        releaseErrors.value = [validationUnavailableItem()]
        return
      }
      releaseErrors.value = [
        ...localReleaseErrors(),
        ...(result.errors || []).map((item) => runtimeValidationItem(item, 'ERROR')),
      ]
      releaseWarnings.value = [
        ...localReleaseWarnings(),
        ...(result.warnings || []).map((item) => runtimeValidationItem(item, 'WARN')),
      ]
    } finally {
      releaseChecking.value = false
      releaseValidationReady.value = true
    }
  }

  function runtimeValidationItem(
    item: WorkflowValidationItem,
    level: 'ERROR' | 'WARN',
  ): WorkflowReleaseValidationItem {
    return {
      code: item.code,
      level,
      nodeId: item.target || null,
      message: item.message,
    }
  }

  function validationUnavailableItem(): WorkflowReleaseValidationItem {
    return {
      code: 'VALIDATION_UNAVAILABLE',
      level: 'ERROR',
      message: '当前工作副本未能完成发布校验，请确认 Runtime 服务可用后重试。',
    }
  }

  function releaseValidationKey(item: WorkflowReleaseValidationItem) {
    return `${item.level || ''}-${item.code}-${item.nodeId || ''}-${item.message}`
  }

  function formatReleaseValidationItem(item: WorkflowReleaseValidationItem) {
    return item.nodeId
      ? `[${item.code}] ${item.nodeId}: ${item.message}`
      : `[${item.code}] ${item.message}`
  }

  async function handlePublishWorkflow() {
    if (studioReadOnly.value) {
      ElMessage.info('代码托管 Workflow 当前为只读草稿，请修改后重启同步。')
      return
    }
    if (!publishForm.version?.trim()) {
      ElMessage.warning('请先填写版本号')
      return
    }
    if (!releaseValidationReady.value || releaseChecking.value) {
      ElMessage.warning('请等待当前工作副本校验完成')
      return
    }
    if (releaseErrors.value.length) {
      ElMessage.error('Workflow 发布门禁未通过，请先修复阻断项')
      return
    }
    const currentLocalErrors = localReleaseErrors()
    if (currentLocalErrors.length) {
      releaseErrors.value = currentLocalErrors
      releaseWarnings.value = localReleaseWarnings()
      ElMessage.error('当前画布本地检查未通过，请先修复阻断项')
      return
    }
    const publishingWorkflowId = workflowId.value
    const publishingEditGeneration = editGeneration.value
    const isCurrentPublishingWorkingCopy = () => (
      workflowId.value === publishingWorkflowId
      && editGeneration.value === publishingEditGeneration
    )
    publishing.value = true
    try {
      const saved = await saveStudio()
      if (!saved) return
      if (!isCurrentPublishingWorkingCopy()) {
        ElMessage.warning('发布已取消：保存后检测到新的本地修改，请重新校验')
        return
      }
      let validationResult
      try {
        validationResult = await validateWorkflowVersion(publishingWorkflowId)
      } catch (err) {
        releaseErrors.value = [validationUnavailableItem()]
        ElMessage.error('发布校验失败：' + (err as Error).message)
        return
      }
      releaseErrors.value = [
        ...localReleaseErrors(),
        ...(validationResult.data.errors || []),
      ]
      releaseWarnings.value = [
        ...localReleaseWarnings(),
        ...(validationResult.data.warnings || []),
      ]
      if (!validationResult.data.valid) {
        ElMessage.error('Workflow 发布门禁未通过，请先修复阻断项')
        return
      }
      if (!isCurrentPublishingWorkingCopy()) {
        ElMessage.warning('发布已取消：校验期间草稿发生变化，请重新校验')
        return
      }
      const warnings = [
        ...publishWarnings.value,
        ...releaseWarnings.value.map(formatReleaseValidationItem),
      ]
      if (warnings.length) {
        try {
          await ElMessageBox.confirm(
            warnings.join('\n'),
            '确认继续发布？',
            { type: 'warning', confirmButtonText: '继续发布', cancelButtonText: '返回检查' },
          )
        } catch {
          return
        }
      }
      if (!isCurrentPublishingWorkingCopy()) {
        ElMessage.warning('发布已取消：确认期间草稿发生变化，请重新校验')
        return
      }
      await publishWorkflowVersion(publishingWorkflowId, {
        version: publishForm.version.trim(),
        rolloutPercent: publishForm.rolloutPercent ?? 100,
        note: publishForm.note,
        publishedBy: publishForm.publishedBy,
        baseRevision: saved.revision || saved.updatedAt || null,
      })
      let pageAssistantSynced = false
      if (syncPublishedPageAssistant) {
        try {
          pageAssistantSynced = await syncPublishedPageAssistant()
        } catch {
          ElMessage.warning('Workflow 已发布，但页面副驾驶同步失败；可在工作流顶部重试同步。')
        }
      }
      ElMessage.success(
        `已发布 Workflow ${publishForm.version}（灰度 ${publishForm.rolloutPercent ?? 100}%）`
        + (pageAssistantSynced ? '，已同步页面副驾驶' : ''),
      )
      publishDialogOpen.value = false
      if (isCurrentPublishingWorkingCopy()) {
        await loadStudio()
      } else {
        ElMessage.warning('版本已发布；检测到新的本地修改，已保留当前页面且未自动刷新')
      }
    } catch (err) {
      const error = err as { response?: { status?: number; data?: { message?: string } }; message?: string }
      if (error.response?.status === 409) {
        releaseValidationReady.value = false
        void ElMessageBox.confirm(
          '保存后服务器草稿又被其他编辑更新，本次未发布任何版本。加载最新草稿后请重新校验并发布。',
          '发布已取消：草稿版本冲突',
          {
            type: 'warning',
            confirmButtonText: '加载最新草稿',
            cancelButtonText: '保留当前页面',
          },
        ).then(() => {
          void loadStudio()
        }).catch(() => undefined)
      } else {
        ElMessage.error('发布 Workflow 失败：' + (error.response?.data?.message || error.message || '服务请求失败'))
      }
    } finally {
      publishing.value = false
    }
  }

  return {
    publishWarnings,
    publishWorkflow,
    releaseValidationKey,
    handlePublishWorkflow,
  }
}
