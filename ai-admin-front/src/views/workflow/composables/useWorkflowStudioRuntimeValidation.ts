import { ElMessage } from 'element-plus'
import { getCurrentScope, onScopeDispose, watch, type Ref } from 'vue'
import { validateWorkflowRuntime as validateWorkflowRuntimeApi } from '@/api/workflow'
import type { WorkflowRuntimeValidationResult, WorkflowWorkingCopyState } from '@/types/workflow'

export interface UseWorkflowStudioRuntimeValidationDeps {
  workflowId: Readonly<Ref<string>>
  studio: Ref<WorkflowWorkingCopyState | null>
  graphSpecJson: Ref<string>
  defaultModelInstanceId: Readonly<Ref<string>>
  editGeneration: Readonly<Ref<number>>
  nodes: Readonly<Ref<unknown[]>>
  validating: Ref<boolean>
  validation: Ref<WorkflowRuntimeValidationResult | null>
  validationRequestError: Ref<string>
  syncJsonFromCanvas: () => void
}

export function useWorkflowStudioRuntimeValidation({
  workflowId, studio, graphSpecJson, defaultModelInstanceId, editGeneration, nodes,
  validating, validation, validationRequestError, syncJsonFromCanvas,
}: UseWorkflowStudioRuntimeValidationDeps) {
  let validationSequence = 0
  let disposed = false

  function invalidateValidation() {
    validationSequence += 1
    validating.value = false
    validation.value = null
    validationRequestError.value = ''
  }

  const stopDocumentWatch = watch([workflowId, studio], invalidateValidation, { flush: 'sync' })
  if (getCurrentScope()) {
    onScopeDispose(() => {
      disposed = true
      stopDocumentWatch()
      invalidateValidation()
    })
  }

  function workflowRequestErrorMessage(err: unknown) {
    const error = err as { response?: { data?: { message?: string } }; message?: string }
    return error.response?.data?.message || error.message || '服务请求失败'
  }

  async function validateRuntime(options: { silent?: boolean; syncCanvas?: boolean } = {}) {
    const validatedStudio = studio.value
    if (disposed || !validatedStudio || !workflowId.value
      || (validatedStudio.workflowId || validatedStudio.id) !== workflowId.value) return null
    const validationToken = ++validationSequence
    let validatedWorkflowId = workflowId.value
    let validatedGraphSpecJson = graphSpecJson.value
    let validatedModelInstanceId = defaultModelInstanceId.value
    let validatedEditGeneration = editGeneration.value
    const isCurrentValidation = () => (
      !disposed
      && validationToken === validationSequence
      && studio.value === validatedStudio
      && workflowId.value === validatedWorkflowId
      && graphSpecJson.value === validatedGraphSpecJson
      && defaultModelInstanceId.value === validatedModelInstanceId
      && editGeneration.value === validatedEditGeneration
    )
    validating.value = true
    try {
      if (options.syncCanvas !== false && nodes.value.length) {
        syncJsonFromCanvas()
      }
      if (disposed || studio.value !== validatedStudio || workflowId.value !== validatedWorkflowId) return null
      validatedWorkflowId = workflowId.value
      validatedGraphSpecJson = graphSpecJson.value
      validatedModelInstanceId = defaultModelInstanceId.value
      validatedEditGeneration = editGeneration.value
      const { data } = await validateWorkflowRuntimeApi({
        workflowId: validatedWorkflowId,
        graphSpecJson: validatedGraphSpecJson,
        executionEngine: studio.value?.executionEngine || 'GRAPH_SPEC',
        defaultModelInstanceId: validatedModelInstanceId,
      })
      if (!isCurrentValidation()) return null
      validation.value = data
      validationRequestError.value = ''
      if (data.valid && !options.silent) {
        ElMessage.success('Workflow GraphSpec 校验通过')
      }
      if (!data.valid && !options.silent) {
        ElMessage.warning(`Workflow 仍有 ${data.errors.length} 个发布阻断项`)
      }
      return data
    } catch (err) {
      if (!isCurrentValidation()) return null
      validation.value = null
      validationRequestError.value = workflowRequestErrorMessage(err)
      if (!options.silent) {
        ElMessage.error(`Workflow 校验失败：${validationRequestError.value}`)
      }
      return null
    } finally {
      if (validationToken === validationSequence) validating.value = false
    }
  }

  return { validateRuntime }
}
