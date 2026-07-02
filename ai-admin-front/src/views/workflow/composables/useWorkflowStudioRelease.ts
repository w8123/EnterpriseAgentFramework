import { ElMessage, ElMessageBox } from 'element-plus'
import { computed, type ComputedRef, type Ref } from 'vue'
import { publishWorkflowVersion, validateWorkflowVersion } from '@/api/workflow'
import type { WorkflowPublishRequest, WorkflowReleaseValidationItem, WorkflowStudioState } from '@/types/workflow'
import type { CanvasNode } from '@/types/studio'

export interface UseWorkflowStudioReleaseDeps {
  workflowId: Readonly<Ref<string>>
  studioReadOnly: Readonly<Ref<boolean>>
  studio: Ref<WorkflowStudioState | null>
  nodes: Ref<CanvasNode[]>
  publishing: Ref<boolean>
  publishDialogOpen: Ref<boolean>
  releaseErrors: Ref<WorkflowReleaseValidationItem[]>
  releaseWarnings: Ref<WorkflowReleaseValidationItem[]>
  publishForm: WorkflowPublishRequest
  saveStudio: () => Promise<void>
  loadStudio: () => Promise<void>
}

export function useWorkflowStudioRelease({
  workflowId,
  studioReadOnly,
  studio,
  nodes,
  publishing,
  publishDialogOpen,
  releaseErrors,
  releaseWarnings,
  publishForm,
  saveStudio,
  loadStudio,
}: UseWorkflowStudioReleaseDeps) {
  const publishWarnings: ComputedRef<string[]> = computed(() => {
    const warnings: string[] = []
    if (!studio.value?.keySlug) {
      warnings.push('未配置 keySlug，业务系统可能无法稳定访问发布后的 Workflow。')
    }
    const callableNodeCount = nodes.value.filter((node) =>
      ['tool', 'skill', 'http', 'pageAction', 'mcp'].includes(node.data.kind),
    ).length
    if (!callableNodeCount) {
      warnings.push('画布中没有工具、能力、接口、页面动作或 MCP 节点，本版本只能进行纯流程/纯对话编排。')
    }
    if ((publishForm.rolloutPercent ?? 100) === 100) {
      warnings.push('本次为全量发布，会替换该 Workflow 的历史 ACTIVE 全量版本。')
    }
    return warnings
  })

  function publishWorkflow() {
    if (studioReadOnly.value) {
      ElMessage.info('代码托管 Workflow 当前为只读草稿，请修改后重启同步。')
      return
    }
    publishDialogOpen.value = true
    void preloadPublishValidation()
  }

  async function preloadPublishValidation() {
    if (!workflowId.value) return
    try {
      const validationResult = await validateWorkflowVersion(workflowId.value)
      releaseErrors.value = validationResult.data.errors || []
      releaseWarnings.value = validationResult.data.warnings || []
    } catch {
      releaseErrors.value = []
      releaseWarnings.value = []
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
    publishing.value = true
    releaseErrors.value = []
    releaseWarnings.value = []
    try {
      await saveStudio()
      const validationResult = await validateWorkflowVersion(workflowId.value)
      releaseErrors.value = validationResult.data.errors || []
      releaseWarnings.value = validationResult.data.warnings || []
      if (!validationResult.data.valid) {
        ElMessage.error('Workflow 发布门禁未通过，请先修复阻断项')
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
      await publishWorkflowVersion(workflowId.value, {
        version: publishForm.version.trim(),
        rolloutPercent: publishForm.rolloutPercent ?? 100,
        note: publishForm.note,
        publishedBy: publishForm.publishedBy,
      })
      ElMessage.success(`已发布 Workflow ${publishForm.version}（灰度 ${publishForm.rolloutPercent ?? 100}%）`)
      publishDialogOpen.value = false
      await loadStudio()
    } catch (err) {
      ElMessage.error('发布 Workflow 失败：' + (err as Error).message)
    } finally {
      publishing.value = false
    }
  }

  return {
    publishWarnings,
    publishWorkflow,
    preloadPublishValidation,
    releaseValidationKey,
    formatReleaseValidationItem,
    handlePublishWorkflow,
  }
}
