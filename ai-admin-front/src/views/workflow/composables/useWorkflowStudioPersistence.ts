import { ElMessage } from 'element-plus'
import type { Ref } from 'vue'
import { getWorkflowStudio, saveWorkflowStudio as saveWorkflowStudioApi, updateWorkflow } from '@/api/workflow'
import type { WorkflowStudioState } from '@/types/workflow'
import { formatJson, normalizeJson } from '@/views/workflow/composables/workflowStudioJson'

export interface WorkflowStudioMetaForm {
  name: string
  keySlug: string
  workflowType: string
  description: string
  defaultModelInstanceId: string
}

export interface UseWorkflowStudioPersistenceDeps {
  workflowId: Readonly<Ref<string>>
  studioReadOnly: Readonly<Ref<boolean>>
  saving: Ref<boolean>
  loading: Ref<boolean>
  studio: Ref<WorkflowStudioState | null>
  graphSpecJson: Ref<string>
  canvasJson: Ref<string>
  nodes: Ref<unknown[]>
  workflowMeta: WorkflowStudioMetaForm
  visualDirty: Ref<boolean>
  lastSavedAt: Ref<string>
  validation: Ref<unknown>
  aiModelInstanceId: Ref<string>
  applyCanvasFromStudio: (state: WorkflowStudioState) => void
  syncJsonFromCanvas: () => void
  resetHistorySnapshot: () => void
  loadCredentialOptions: (state: WorkflowStudioState | null) => Promise<void>
  clearWorkflowDocumentState: () => void
}

export function useWorkflowStudioPersistence({
  workflowId,
  studioReadOnly,
  saving,
  loading,
  studio,
  graphSpecJson,
  canvasJson,
  nodes,
  workflowMeta,
  visualDirty,
  lastSavedAt,
  validation,
  aiModelInstanceId,
  applyCanvasFromStudio,
  syncJsonFromCanvas,
  resetHistorySnapshot,
  loadCredentialOptions,
  clearWorkflowDocumentState,
}: UseWorkflowStudioPersistenceDeps) {
  function syncWorkflowMetaFromStudio(state: WorkflowStudioState | null) {
    if (!state) return
    workflowMeta.name = state.name || ''
    workflowMeta.keySlug = state.keySlug || ''
    workflowMeta.workflowType = state.workflowType || 'WORKFLOW'
    workflowMeta.description = state.description || ''
    workflowMeta.defaultModelInstanceId = state.defaultModelInstanceId || ''
  }

  function workflowMetaDirty() {
    if (!studio.value) return false
    return (
      workflowMeta.name !== (studio.value.name || '')
      || workflowMeta.keySlug !== (studio.value.keySlug || '')
      || workflowMeta.workflowType !== (studio.value.workflowType || 'WORKFLOW')
      || workflowMeta.description !== (studio.value.description || '')
      || workflowMeta.defaultModelInstanceId !== (studio.value.defaultModelInstanceId || '')
    )
  }

  async function loadStudio() {
    if (!workflowId.value) return
    loading.value = true
    try {
      const { data } = await getWorkflowStudio(workflowId.value)
      studio.value = data
      syncWorkflowMetaFromStudio(data)
      if (!aiModelInstanceId.value && data.defaultModelInstanceId) {
        aiModelInstanceId.value = data.defaultModelInstanceId
      }
      graphSpecJson.value = formatJson(data.graphSpecJson || '{"nodes":[],"edges":[]}')
      canvasJson.value = formatJson(data.canvasJson || '{"nodes":[],"edges":[]}')
      applyCanvasFromStudio(data)
      await loadCredentialOptions(data)
      resetHistorySnapshot()
      validation.value = null
    } catch {
      clearWorkflowDocumentState()
    } finally {
      loading.value = false
    }
  }

  async function saveStudio() {
    if (studioReadOnly.value) {
      ElMessage.info('代码托管 Workflow 当前为只读草稿，请修改后重启同步。')
      return
    }
    saving.value = true
    try {
      if (nodes.value.length) {
        syncJsonFromCanvas()
      }
      const graph = normalizeJson(graphSpecJson.value, 'GraphSpec')
      const canvas = normalizeJson(canvasJson.value || '{}', 'Canvas')
      if (studio.value && workflowMetaDirty()) {
        await updateWorkflow(workflowId.value, {
          name: workflowMeta.name.trim() || studio.value.name || 'Workflow',
          keySlug: (workflowMeta.keySlug.trim() || studio.value.keySlug || '') || undefined,
          description: workflowMeta.description?.trim() || undefined,
          workflowType: workflowMeta.workflowType.trim() || studio.value.workflowType || undefined,
          defaultModelInstanceId: workflowMeta.defaultModelInstanceId || undefined,
        })
      }
      const { data } = await saveWorkflowStudioApi(workflowId.value, {
        graphSpecJson: graph,
        canvasJson: canvas,
        extraJson: studio.value?.extraJson || null,
      })
      graphSpecJson.value = formatJson(data.graphSpecJson || graph)
      canvasJson.value = formatJson(data.canvasJson || canvas)
      studio.value = {
        workflowId: data.id,
        projectId: data.projectId,
        projectCode: data.projectCode,
        keySlug: data.keySlug,
        name: data.name,
        description: data.description,
        graphSpecJson: data.graphSpecJson || graph,
        canvasJson: data.canvasJson,
        workflowType: data.workflowType,
        runtimeType: data.runtimeType || 'LANGGRAPH4J',
        defaultModelInstanceId: data.defaultModelInstanceId,
        defaultResourceConfigJson: data.defaultResourceConfigJson,
        status: data.status || 'DRAFT',
        managedBy: data.managedBy || 'MANUAL',
        extraJson: data.extraJson,
      }
      syncWorkflowMetaFromStudio(studio.value)
      visualDirty.value = false
      lastSavedAt.value = new Date().toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
      resetHistorySnapshot()
      ElMessage.success('Workflow 草稿已保存')
    } finally {
      saving.value = false
    }
  }

  return {
    syncWorkflowMetaFromStudio,
    workflowMetaDirty,
    loadStudio,
    saveStudio,
  }
}
