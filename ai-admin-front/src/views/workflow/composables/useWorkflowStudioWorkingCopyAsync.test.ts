import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { effectScope, ref, type EffectScope } from 'vue'
import { ElMessage } from 'element-plus'
import type { WorkflowRuntimeValidationResult, WorkflowWorkingCopyState } from '@/types/workflow'
import { useWorkflowStudioRuntimeValidation } from './useWorkflowStudioRuntimeValidation'
import { useWorkflowStudioPersistence } from './useWorkflowStudioPersistence'

const api = vi.hoisted(() => ({ validate: vi.fn(), get: vi.fn(), save: vi.fn(), confirm: vi.fn() }))
vi.mock('@/api/workflow', () => ({
  validateWorkflowRuntime: api.validate, getWorkflowWorkingCopy: api.get, saveWorkflowWorkingCopy: api.save,
}))
vi.mock('element-plus', () => ({
  ElMessage: { success: vi.fn(), warning: vi.fn(), error: vi.fn(), info: vi.fn() },
  ElMessageBox: { confirm: api.confirm },
}))

const scopes: EffectScope[] = []
const valid: WorkflowRuntimeValidationResult = { valid: true, errors: [] }
beforeEach(() => vi.resetAllMocks())
afterEach(() => { scopes.splice(0).forEach(scope => scope.stop()) })

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason: unknown) => void
  const promise = new Promise<T>((done, fail) => { resolve = done; reject = fail })
  return { promise, resolve, reject }
}

function document(id = 'wf-a'): WorkflowWorkingCopyState {
  return { workflowId: id, name: id, status: 'DRAFT', revision: 'rev-1', graphSpecJson: '{"nodes":[],"edges":[]}', canvasJson: '{}' }
}

function validationFixture() {
  const deps = {
    workflowId: ref('wf-a'), studio: ref<WorkflowWorkingCopyState | null>(document()),
    graphSpecJson: ref(document().graphSpecJson), defaultModelInstanceId: ref('model-a'), editGeneration: ref(0),
    nodes: ref<unknown[]>([]), validating: ref(false), validation: ref<WorkflowRuntimeValidationResult | null>(null),
    validationRequestError: ref(''), syncJsonFromCanvas: vi.fn(),
  }
  const scope = effectScope()
  scopes.push(scope)
  const actions = scope.run(() => useWorkflowStudioRuntimeValidation(deps))!
  return { ...deps, ...actions, scope }
}

describe('Workflow runtime validation document ownership', () => {
  it('does not submit the previous document with the next route identity', async () => {
    const state = validationFixture()
    state.workflowId.value = 'wf-b'
    api.validate.mockResolvedValue({ data: valid })
    expect(await state.validateRuntime()).toBeNull()
    expect(api.validate).not.toHaveBeenCalled()
    expect(state.validating.value).toBe(false)
  })

  it('ignores a response after replacing the same workflow working copy', async () => {
    const state = validationFixture()
    const pending = deferred<{ data: WorkflowRuntimeValidationResult }>()
    api.validate.mockReturnValue(pending.promise)
    const operation = state.validateRuntime()
    state.studio.value = { ...document(), revision: 'rev-2' }
    pending.resolve({ data: valid })
    expect(await operation).toBeNull()
    expect(state.validation.value).toBeNull()
    expect(ElMessage.success).not.toHaveBeenCalled()
  })

  it('ignores an old response after switching away and back before loading finishes', async () => {
    const state = validationFixture()
    const pending = deferred<{ data: WorkflowRuntimeValidationResult }>()
    api.validate.mockReturnValue(pending.promise)
    const operation = state.validateRuntime()
    state.workflowId.value = 'wf-b'
    state.workflowId.value = 'wf-a'
    pending.resolve({ data: valid })
    expect(await operation).toBeNull()
    expect(state.validation.value).toBeNull()
    expect(state.validating.value).toBe(false)
  })

  it('does not publish validation feedback after leaving the component scope', async () => {
    const state = validationFixture()
    const pending = deferred<{ data: WorkflowRuntimeValidationResult }>()
    api.validate.mockReturnValue(pending.promise)
    const operation = state.validateRuntime()
    state.scope.stop()
    pending.resolve({ data: valid })
    expect(await operation).toBeNull()
    expect(state.validation.value).toBeNull()
    expect(ElMessage.success).not.toHaveBeenCalled()
    expect(state.validating.value).toBe(false)
  })

  it('submits the synchronized graph and preserves the selected model', async () => {
    const state = validationFixture()
    state.nodes.value = [{ id: 'start' }]
    state.syncJsonFromCanvas.mockImplementation(() => { state.graphSpecJson.value = '{"nodes":[{"id":"start"}]}' })
    api.validate.mockResolvedValue({ data: valid })
    expect(await state.validateRuntime()).toEqual(valid)
    expect(api.validate).toHaveBeenCalledWith({ workflowId: 'wf-a', graphSpecJson: state.graphSpecJson.value,
      executionEngine: 'GRAPH_SPEC', defaultModelInstanceId: 'model-a' })
    expect(state.validation.value).toEqual(valid)
    expect(ElMessage.success).toHaveBeenCalledOnce()
  })

  it('keeps a newer pending validation busy when an older request finishes', async () => {
    const state = validationFixture()
    const first = deferred<{ data: WorkflowRuntimeValidationResult }>()
    const second = deferred<{ data: WorkflowRuntimeValidationResult }>()
    api.validate.mockReturnValueOnce(first.promise).mockReturnValueOnce(second.promise)
    const old = state.validateRuntime()
    state.editGeneration.value++
    const current = state.validateRuntime({ silent: true })
    first.resolve({ data: valid })
    expect(await old).toBeNull()
    expect(state.validating.value).toBe(true)
    second.resolve({ data: { valid: false, errors: [{ code: 'EMPTY', message: '请添加节点' }] } })
    expect((await current)?.valid).toBe(false)
    expect(state.validating.value).toBe(false)
    expect(ElMessage.warning).not.toHaveBeenCalled()
  })

  it('preserves request failure feedback and allows a successful retry', async () => {
    const state = validationFixture()
    api.validate.mockRejectedValueOnce({ response: { data: { message: '校验服务暂不可用' } } })
      .mockResolvedValueOnce({ data: valid })
    expect(await state.validateRuntime()).toBeNull()
    expect(state.validationRequestError.value).toBe('校验服务暂不可用')
    expect(state.graphSpecJson.value).toBe(document().graphSpecJson)
    expect(await state.validateRuntime({ syncCanvas: false })).toEqual(valid)
    expect(state.validationRequestError.value).toBe('')
  })
})

function persistenceFixture() {
  const deps = {
    workflowId: ref('wf-a'), studioReadOnly: ref(false), saving: ref(false), loading: ref(false),
    studio: ref<WorkflowWorkingCopyState | null>(document()), graphSpecJson: ref(document().graphSpecJson),
    canvasJson: ref('{}'), nodes: ref<unknown[]>([]),
    workflowMeta: { name: 'wf-a', keySlug: '', workflowKind: 'GENERAL', description: '', defaultModelInstanceId: '' },
    visualDirty: ref(true), editGeneration: ref(0), lastSavedAt: ref(''), validation: ref(null), aiModelInstanceId: ref(''),
    applyCanvasFromStudio: vi.fn(), syncJsonFromCanvas: vi.fn(), resetHistorySnapshot: vi.fn(),
    loadCredentialOptions: vi.fn(async () => undefined), clearWorkflowDocumentState: vi.fn(),
  }
  return { ...deps, ...useWorkflowStudioPersistence(deps) }
}

describe('Workflow save-conflict confirmation ownership', () => {
  it('does not let an old confirmation reload another workflow and discard its edits', async () => {
    const state = persistenceFixture()
    const decision = deferred<void>()
    api.confirm.mockReturnValue(decision.promise)
    api.save.mockRejectedValue({ response: { status: 409 } })
    api.get.mockResolvedValue({ data: document('wf-b') })
    await state.saveStudio()
    state.workflowId.value = 'wf-b'
    state.studio.value = document('wf-b')
    state.graphSpecJson.value = '{"newLocalEdit":true}'
    decision.resolve()
    await decision.promise
    await Promise.resolve()
    expect(api.get).not.toHaveBeenCalled()
    expect(state.graphSpecJson.value).toBe('{"newLocalEdit":true}')
  })

  it('does not reload again when the same workflow was already refreshed', async () => {
    const state = persistenceFixture()
    const decision = deferred<void>()
    api.confirm.mockReturnValue(decision.promise)
    api.save.mockRejectedValue({ response: { status: 409 } })
    api.get.mockResolvedValue({ data: { ...document(), revision: 'rev-2' } })
    await state.saveStudio()
    await state.loadStudio()
    state.graphSpecJson.value = '{"editAfterRefresh":true}'
    decision.resolve()
    await decision.promise
    await Promise.resolve()
    expect(api.get).toHaveBeenCalledTimes(1)
    expect(state.graphSpecJson.value).toBe('{"editAfterRefresh":true}')
  })

  it('still reloads after confirming a conflict for the current document', async () => {
    const state = persistenceFixture()
    const decision = deferred<void>()
    api.confirm.mockReturnValue(decision.promise)
    api.save.mockRejectedValue({ response: { status: 409 } })
    api.get.mockResolvedValue({ data: { ...document(), revision: 'rev-2' } })
    await state.saveStudio()
    decision.resolve()
    await decision.promise
    await Promise.resolve()
    expect(api.get).toHaveBeenCalledWith('wf-a')
  })
})
