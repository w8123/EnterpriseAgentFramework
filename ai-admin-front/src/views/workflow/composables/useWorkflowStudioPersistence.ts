import { ElMessage, ElMessageBox } from 'element-plus'
import type { Ref } from 'vue'
import { getWorkflowWorkingCopy, saveWorkflowWorkingCopy } from '@/api/workflow'
import type { WorkflowWorkingCopyState } from '@/types/workflow'
import { formatJson, normalizeJson } from '@/views/workflow/composables/workflowStudioJson'

export interface WorkflowStudioMetaForm {
  name: string
  keySlug: string
  workflowKind: string
  description: string
  defaultModelInstanceId: string
}

export interface UseWorkflowStudioPersistenceDeps {
  workflowId: Readonly<Ref<string>>
  studioReadOnly: Readonly<Ref<boolean>>
  saving: Ref<boolean>
  loading: Ref<boolean>
  studio: Ref<WorkflowWorkingCopyState | null>
  graphSpecJson: Ref<string>
  canvasJson: Ref<string>
  nodes: Ref<unknown[]>
  workflowMeta: WorkflowStudioMetaForm
  visualDirty: Ref<boolean>
  editGeneration: Readonly<Ref<number>>
  lastSavedAt: Ref<string>
  validation: Ref<unknown>
  aiModelInstanceId: Ref<string>
  applyCanvasFromStudio: (state: WorkflowWorkingCopyState) => void
  syncJsonFromCanvas: () => void
  resetHistorySnapshot: () => void
  loadCredentialOptions: (
    state: WorkflowWorkingCopyState | null,
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

  function stateWorkflowId(state: WorkflowWorkingCopyState | null) {
    return state?.workflowId || state?.id || ''
  }

  function syncWorkflowMetaFromStudio(state: WorkflowWorkingCopyState | null) {
    if (!state) return
    workflowMeta.name = state.name || ''
    workflowMeta.keySlug = state.keySlug || ''
    workflowMeta.workflowKind = state.workflowKind || 'GENERAL'
    workflowMeta.description = state.description || ''
    workflowMeta.defaultModelInstanceId = state.defaultModelInstanceId || ''
  }

  function workflowMetaDirty() {
    if (!studio.value) return false
    return (
      workflowMeta.name !== (studio.value.name || '')
      || workflowMeta.keySlug !== (studio.value.keySlug || '')
      || workflowMeta.workflowKind !== (studio.value.workflowKind || 'GENERAL')
      || workflowMeta.description !== (studio.value.description || '')
      || workflowMeta.defaultModelInstanceId !== (studio.value.defaultModelInstanceId || '')
    )
  }

  async function loadStudio(): Promise<WorkflowWorkingCopyState | null> {
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
      const { data } = await getWorkflowWorkingCopy(requestedWorkflowId)
      if (!isCurrentLoad()) return null
      const loadedState = canonicalizeWorkingCopyState(data, requestedWorkflowId)
      studio.value = loadedState
      syncWorkflowMetaFromStudio(loadedState)
      if (!aiModelInstanceId.value && loadedState.defaultModelInstanceId) {
        aiModelInstanceId.value = loadedState.defaultModelInstanceId
      }
      graphSpecJson.value = formatJson(loadedState.graphSpecJson || '{"schemaVersion":2,"nodes":[],"edges":[],"entryNodeId":"","exitNodeIds":[]}')
      canvasJson.value = formatJson(loadedState.canvasJson || '{"schemaVersion":1,"layoutVersion":1,"nodes":[],"edges":[]}')
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

  async function saveStudio(): Promise<WorkflowWorkingCopyState | null> {
    if (studioReadOnly.value) {
      ElMessage.info('代码托管 Workflow 的工作副本当前只读，请修改后重启同步。')
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
      const baseRevision = baseStudio.revision || baseStudio.updatedAt
      if (!baseRevision?.trim()) {
        ElMessage.error('未能读取草稿版本，请保留编辑内容并重新加载')
        return null
      }
      const savedEditGeneration = editGeneration.value
      const { data } = await saveWorkflowWorkingCopy(requestedWorkflowId, {
        graphSpecJson: graph,
        canvasJson: canvas,
        extraJson: baseStudio.extraJson || null,
        baseRevision,
        keySlug: workflowMeta.keySlug.trim() || baseStudio.keySlug || null,
        name: workflowMeta.name.trim() || baseStudio.name || 'Workflow',
        description: workflowMeta.description.trim(),
        workflowKind: workflowMeta.workflowKind.trim()
          || baseStudio.workflowKind
          || 'GENERAL',
        executionEngine: baseStudio.executionEngine || 'GRAPH_SPEC',
        definitionAuthority: baseStudio.definitionAuthority || 'USER',
        creationChannel: baseStudio.creationChannel || 'STUDIO',
        inputSchemaJson: baseStudio.inputSchemaJson || null,
        outputSchemaJson: baseStudio.outputSchemaJson || null,
        defaultModelInstanceId: workflowMeta.defaultModelInstanceId,
        defaultResourceConfigJson: baseStudio.defaultResourceConfigJson || null,
      })
      if (!isCurrentSave()) return null
      const savedState = canonicalizeWorkingCopyState({
        ...baseStudio,
        ...data,
        workflowId: data.workflowId || data.id || requestedWorkflowId,
        graphSpecJson: data.graphSpecJson || graph,
        canvasJson: data.canvasJson ?? canvas,
        workflowKind: data.workflowKind || baseStudio.workflowKind || 'GENERAL',
        executionEngine: data.executionEngine || baseStudio.executionEngine || 'GRAPH_SPEC',
        definitionAuthority: data.definitionAuthority || baseStudio.definitionAuthority || 'USER',
        creationChannel: data.creationChannel || baseStudio.creationChannel || 'STUDIO',
        status: data.status || baseStudio.status || 'DRAFT',
        revision: data.revision || data.updatedAt || baseStudio.revision || null,
        hasUnpublishedChanges: data.hasUnpublishedChanges ?? true,
      }, requestedWorkflowId)
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
      ElMessage.success('Workflow 工作副本已保存')
      return savedState
    } catch (err) {
      if (!isCurrentSave()) return null
      const error = err as { response?: { status?: number; data?: { message?: string } }; message?: string }
      if (error.response?.status === 409) {
        void ElMessageBox.confirm(
          '服务器上的工作副本已被其他编辑更新，本地修改仍然保留。加载最新版本会覆盖当前本地修改。',
          '检测到版本冲突',
          {
            type: 'warning',
            autofocus: false,
            confirmButtonText: '加载最新版本（覆盖本地）',
            cancelButtonText: '保留当前修改',
          },
        ).then(() => {
          if (isCurrentSave()) void loadStudio()
        }).catch(() => undefined)
      } else {
        ElMessage.error(`Workflow 工作副本保存失败：${error.response?.data?.message || error.message || '服务请求失败'}`)
      }
      return null
    } finally {
      if (saveToken === saveSequence) {
        activeSaveWorkflowId = null
        saving.value = false
      }
    }
  }

  function canonicalizeWorkingCopyState(
    state: WorkflowWorkingCopyState,
    fallbackWorkflowId: string,
  ): WorkflowWorkingCopyState {
    const canonical: WorkflowWorkingCopyState = {
      ...state,
      workflowId: state.workflowId || state.id || fallbackWorkflowId,
      workflowKind: state.workflowKind || 'GENERAL',
      executionEngine: state.executionEngine || 'GRAPH_SPEC',
      definitionAuthority: state.definitionAuthority || 'USER',
      creationChannel: state.creationChannel || 'STUDIO',
    }
    return canonical
  }

  return {
    workflowMetaDirty,
    loadStudio,
    saveStudio,
  }
}
