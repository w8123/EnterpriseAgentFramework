import { ElMessage, ElMessageBox } from 'element-plus'
import type { Ref } from 'vue'
import { getWorkflowStudio, saveWorkflowStudio as saveWorkflowStudioApi } from '@/api/workflow'
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
  editGeneration: Readonly<Ref<number>>
  lastSavedAt: Ref<string>
  validation: Ref<unknown>
  aiModelInstanceId: Ref<string>
  applyCanvasFromStudio: (state: WorkflowStudioState) => void
  syncJsonFromCanvas: () => void
  resetHistorySnapshot: () => void
  loadCredentialOptions: (
    state: WorkflowStudioState | null,
    shouldApply?: () => boolean,
  ) => Promise<void>
  clearWorkflowDocumentState: () => void
  ensurePanelValidationClear?: () => boolean
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
  editGeneration,
  lastSavedAt,
  validation,
  aiModelInstanceId,
  applyCanvasFromStudio,
  syncJsonFromCanvas,
  resetHistorySnapshot,
  loadCredentialOptions,
  clearWorkflowDocumentState,
  ensurePanelValidationClear,
}: UseWorkflowStudioPersistenceDeps) {
  let loadSequence = 0
  let saveSequence = 0
  let documentOperationSequence = 0
  let activeSaveWorkflowId: string | null = null

  function stateWorkflowId(state: WorkflowStudioState | null) {
    return state?.workflowId || state?.id || ''
  }

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

  async function loadStudio(): Promise<WorkflowStudioState | null> {
    const requestedWorkflowId = workflowId.value
    const loadToken = ++loadSequence
    const documentToken = ++documentOperationSequence
    if (!requestedWorkflowId) {
      loading.value = false
      return null
    }
    if (activeSaveWorkflowId && activeSaveWorkflowId !== requestedWorkflowId) {
      saveSequence += 1
      activeSaveWorkflowId = null
      saving.value = false
    }
    const isCurrentLoad = () => (
      loadToken === loadSequence
      && documentToken === documentOperationSequence
      && workflowId.value === requestedWorkflowId
    )
    loading.value = true
    try {
      const { data } = await getWorkflowStudio(requestedWorkflowId)
      if (!isCurrentLoad()) return null
      const loadedState: WorkflowStudioState = {
        ...data,
        workflowId: data.workflowId || data.id || requestedWorkflowId,
      }
      studio.value = loadedState
      syncWorkflowMetaFromStudio(loadedState)
      if (!aiModelInstanceId.value && loadedState.defaultModelInstanceId) {
        aiModelInstanceId.value = loadedState.defaultModelInstanceId
      }
      graphSpecJson.value = formatJson(loadedState.graphSpecJson || '{"nodes":[],"edges":[]}')
      canvasJson.value = formatJson(loadedState.canvasJson || '{"nodes":[],"edges":[]}')
      applyCanvasFromStudio(loadedState)
      await loadCredentialOptions(loadedState, isCurrentLoad)
      if (!isCurrentLoad()) return null
      resetHistorySnapshot()
      validation.value = null
      return loadedState
    } catch {
      if (!isCurrentLoad()) return null
      if (!studio.value || stateWorkflowId(studio.value) !== requestedWorkflowId) {
        clearWorkflowDocumentState()
      }
      return null
    } finally {
      if (loadToken === loadSequence) loading.value = false
    }
  }

  async function saveStudio(): Promise<WorkflowStudioState | null> {
    if (studioReadOnly.value) {
      ElMessage.info('代码托管 Workflow 当前为只读草稿，请修改后重启同步。')
      return null
    }
    if (ensurePanelValidationClear && !ensurePanelValidationClear()) {
      return null
    }
    const requestedWorkflowId = workflowId.value
    const baseStudio = studio.value
    if (
      !requestedWorkflowId
      || !baseStudio
      || stateWorkflowId(baseStudio) !== requestedWorkflowId
      || saving.value
    ) return null
    const saveToken = ++saveSequence
    const documentToken = ++documentOperationSequence
    activeSaveWorkflowId = requestedWorkflowId
    const isCurrentSave = () => (
      saveToken === saveSequence
      && documentToken === documentOperationSequence
      && workflowId.value === requestedWorkflowId
      && stateWorkflowId(studio.value) === requestedWorkflowId
    )
    saving.value = true
    try {
      if (nodes.value.length) {
        syncJsonFromCanvas()
      }
      if (!isCurrentSave()) return null
      const graph = normalizeJson(graphSpecJson.value, 'GraphSpec')
      const canvas = normalizeJson(canvasJson.value || '{}', 'Canvas')
      const savedEditGeneration = editGeneration.value
      const { data } = await saveWorkflowStudioApi(requestedWorkflowId, {
        graphSpecJson: graph,
        canvasJson: canvas,
        extraJson: baseStudio.extraJson || null,
        baseRevision: baseStudio.revision || baseStudio.updatedAt || null,
        keySlug: workflowMeta.keySlug.trim() || baseStudio.keySlug || null,
        name: workflowMeta.name.trim() || baseStudio.name || 'Workflow',
        description: workflowMeta.description.trim(),
        workflowType: workflowMeta.workflowType.trim() || baseStudio.workflowType || null,
        runtimeType: baseStudio.runtimeType || 'LANGGRAPH4J',
        inputSchemaJson: baseStudio.inputSchemaJson || null,
        outputSchemaJson: baseStudio.outputSchemaJson || null,
        defaultModelInstanceId: workflowMeta.defaultModelInstanceId,
        defaultResourceConfigJson: baseStudio.defaultResourceConfigJson || null,
      })
      if (!isCurrentSave()) return null
      const savedState: WorkflowStudioState = {
        ...baseStudio,
        ...data,
        workflowId: data.workflowId || data.id || requestedWorkflowId,
        graphSpecJson: data.graphSpecJson || graph,
        canvasJson: data.canvasJson ?? canvas,
        runtimeType: data.runtimeType || baseStudio.runtimeType || 'LANGGRAPH4J',
        status: data.status || baseStudio.status || 'DRAFT',
        managedBy: data.managedBy || baseStudio.managedBy || 'MANUAL',
        revision: data.revision || data.updatedAt || baseStudio.revision || null,
        hasUnpublishedChanges: data.hasUnpublishedChanges ?? true,
      }
      if (editGeneration.value !== savedEditGeneration) {
        studio.value = savedState
        const savedAt = savedState.updatedAt ? new Date(savedState.updatedAt) : new Date()
        lastSavedAt.value = savedAt.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
        validation.value = null
        ElMessage.warning('保存期间检测到新的本地修改；已保留这些修改，请再次保存')
        return null
      }
      graphSpecJson.value = formatJson(savedState.graphSpecJson)
      canvasJson.value = formatJson(savedState.canvasJson || canvas)
      studio.value = savedState
      syncWorkflowMetaFromStudio(studio.value)
      visualDirty.value = false
      const savedAt = savedState.updatedAt ? new Date(savedState.updatedAt) : new Date()
      lastSavedAt.value = savedAt.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
      validation.value = null
      resetHistorySnapshot()
      ElMessage.success('Workflow 草稿已保存')
      return savedState
    } catch (err) {
      if (!isCurrentSave()) return null
      const error = err as { response?: { status?: number; data?: { message?: string } }; message?: string }
      if (error.response?.status === 409) {
        void ElMessageBox.confirm(
          '服务器上的草稿已被其他编辑更新，本地修改仍然保留。加载最新版本会覆盖当前本地修改。',
          '检测到版本冲突',
          {
            type: 'warning',
            confirmButtonText: '加载最新版本（覆盖本地）',
            cancelButtonText: '保留当前修改',
          },
        ).then(() => {
          void loadStudio()
        }).catch(() => undefined)
      } else {
        ElMessage.error(`Workflow 草稿保存失败：${error.response?.data?.message || error.message || '服务请求失败'}`)
      }
      return null
    } finally {
      if (saveToken === saveSequence) {
        activeSaveWorkflowId = null
        saving.value = false
      }
    }
  }

  return {
    workflowMetaDirty,
    loadStudio,
    saveStudio,
  }
}
