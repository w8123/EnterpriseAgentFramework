import { effectScope, ref, type EffectScope } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useWorkflowStudioResources as createWorkflowStudioResources, type UseWorkflowStudioResourcesDeps } from './useWorkflowStudioResources'
import { useWorkflowStudioPersistence } from './useWorkflowStudioPersistence'
import type { WorkflowWorkingCopyState } from '@/types/workflow'

const mocks = vi.hoisted(() => ({
  getApiGraphParamHints: vi.fn(),
  getKnowledgeList: vi.fn(),
  getModelInstances: vi.fn(),
  listAllTools: vi.fn(),
  getWorkflowGraphNodeTypes: vi.fn(),
  listWorkflowCredentials: vi.fn(),
  getWorkflowWorkingCopy: vi.fn(),
  saveWorkflowWorkingCopy: vi.fn(),
}))

vi.mock('@/api/apiGraph', () => ({
  getApiGraphParamHints: mocks.getApiGraphParamHints,
}))
vi.mock('@/api/knowledge', () => ({
  getKnowledgeList: mocks.getKnowledgeList,
}))
vi.mock('@/api/model', () => ({
  getModelInstances: mocks.getModelInstances,
}))
vi.mock('@/api/tool', () => ({
  listAllTools: mocks.listAllTools,
}))
vi.mock('@/api/workflow', () => ({
  getWorkflowGraphNodeTypes: mocks.getWorkflowGraphNodeTypes,
  getWorkflowWorkingCopy: mocks.getWorkflowWorkingCopy,
  saveWorkflowWorkingCopy: mocks.saveWorkflowWorkingCopy,
}))
vi.mock('@/api/workflowCredential', () => ({
  listWorkflowCredentials: mocks.listWorkflowCredentials,
}))
vi.mock('element-plus', () => ({
  ElMessage: { success: vi.fn(), warning: vi.fn(), error: vi.fn(), info: vi.fn() },
  ElMessageBox: { confirm: vi.fn() },
}))

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason: unknown) => void
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise
    reject = rejectPromise
  })
  return { promise, resolve, reject }
}

const scopes: EffectScope[] = []
afterEach(() => scopes.splice(0).forEach(scope => scope.stop()))
function useWorkflowStudioResources(deps: UseWorkflowStudioResourcesDeps) {
  const scope = effectScope()
  scopes.push(scope)
  return scope.run(() => createWorkflowStudioResources(deps))!
}

function tool(name: string, projectId: number) {
  return {
    name,
    title: name,
    description: '',
    parameters: [],
    source: 'code' as const,
    projectId,
    enabled: true,
  }
}

function workingCopy(overrides: Partial<WorkflowWorkingCopyState> = {}): WorkflowWorkingCopyState {
  return {
    workflowId: 'orders', projectId: 1, projectCode: 'orders', name: 'Orders',
    status: 'DRAFT', revision: 'rev-1',
    graphSpecJson: '{"nodes":[{"id":"llm_1","type":"LLM"},{"id":"knowledge_1","type":"KNOWLEDGE"}],"edges":[]}',
    canvasJson: '{}',
    ...overrides,
  }
}

function savingResourceFixture() {
  const studio = ref<WorkflowWorkingCopyState | null>(workingCopy())
  const aiModelInstanceId = ref('')
  const editGeneration = ref(0)
  const resources = useWorkflowStudioResources({ studio, aiModelInstanceId, selectedToolName: ref('') })
  const persistence = useWorkflowStudioPersistence({
    workflowId: ref('orders'), studioReadOnly: ref(false), saving: ref(false), loading: ref(false), studio,
    graphSpecJson: ref(workingCopy().graphSpecJson), canvasJson: ref('{}'),
    nodes: ref([{ id: 'llm_1', data: { kind: 'llm' } }, { id: 'knowledge_1', data: { kind: 'knowledge' } }]),
    workflowMeta: { name: 'Orders', keySlug: '', workflowKind: 'GENERAL', description: '', defaultModelInstanceId: 'model-orders' },
    visualDirty: ref(true), editGeneration, lastSavedAt: ref(''), validation: ref(null), aiModelInstanceId,
    applyCanvasFromStudio: vi.fn(), syncJsonFromCanvas: vi.fn(), resetHistorySnapshot: vi.fn(),
    loadCredentialOptions: resources.loadCredentialOptions, clearWorkflowDocumentState: vi.fn(),
  })
  return { studio, aiModelInstanceId, editGeneration, resources, ...persistence }
}

describe('useWorkflowStudioResources parameter hints', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    mocks.getModelInstances.mockResolvedValue({ data: [] })
    mocks.getKnowledgeList.mockResolvedValue({ data: [] })
    mocks.listAllTools.mockResolvedValue([
      tool('tool-a', 1),
      tool('tool-b', 2),
    ])
  })

  it('ignores hints returned for a previously selected tool', async () => {
    const requestA = deferred<{ data: { targetPath: string }[] }>()
    const requestB = deferred<{ data: { targetPath: string }[] }>()
    mocks.getApiGraphParamHints.mockImplementation((projectId: number) => (
      projectId === 1 ? requestA.promise : requestB.promise
    ))
    const selectedToolName = ref('tool-a')
    const resources = useWorkflowStudioResources({
      studio: ref(null),
      aiModelInstanceId: ref(''),
      selectedToolName,
    })
    await resources.loadToolOptions()

    const refreshA = resources.refreshParamSourceHints()
    selectedToolName.value = 'tool-b'
    const refreshB = resources.refreshParamSourceHints()
    requestA.resolve({ data: [{ targetPath: 'from-a' }] })
    await refreshA

    expect(resources.paramSourceHints.value).toEqual([])

    requestB.resolve({ data: [{ targetPath: 'from-b' }] })
    await refreshB
    expect(resources.paramSourceHints.value).toEqual([
      { targetPath: 'from-b' },
    ])
  })

  it('keeps hints empty when tool selection is cleared during a request', async () => {
    const request = deferred<{ data: { targetPath: string }[] }>()
    mocks.getApiGraphParamHints.mockReturnValue(request.promise)
    const selectedToolName = ref('tool-a')
    const resources = useWorkflowStudioResources({
      studio: ref(null),
      aiModelInstanceId: ref(''),
      selectedToolName,
    })
    await resources.loadToolOptions()

    const refresh = resources.refreshParamSourceHints()
    selectedToolName.value = ''
    await resources.refreshParamSourceHints()
    request.resolve({ data: [{ targetPath: 'from-a' }] })
    await refresh

    expect(resources.paramSourceHints.value).toEqual([])
  })

  it('does not ask the API graph for hints when the selected catalog item is a business method', async () => {
    mocks.listAllTools.mockResolvedValue([
      { ...tool('orders_read', 1), assetType: 'BUSINESS_METHOD', qualifiedName: 'orders:read' },
    ])
    const resources = useWorkflowStudioResources({
      studio: ref(null),
      aiModelInstanceId: ref(''),
      selectedToolName: ref('orders_read'),
    })
    await resources.loadToolOptions()

    await resources.refreshParamSourceHints()

    expect(mocks.getApiGraphParamHints).not.toHaveBeenCalled()
    expect(resources.paramSourceHints.value).toEqual([])
  })

  it('scopes the generic catalog to the active workflow project and ignores stale project results', async () => {
    const orders = deferred<ReturnType<typeof tool>[]>()
    const billing = deferred<ReturnType<typeof tool>[]>()
    mocks.listAllTools.mockImplementation(({ projectId }: { projectId?: number }) => (
      projectId === 1 ? orders.promise : billing.promise
    ))
    const studio = ref({ projectId: 1, projectCode: 'orders' } as WorkflowWorkingCopyState)
    const resources = useWorkflowStudioResources({
      studio,
      aiModelInstanceId: ref(''),
      selectedToolName: ref(''),
    })

    const ordersLoad = resources.loadToolOptions()
    expect(mocks.listAllTools).toHaveBeenLastCalledWith({ enabled: true, projectId: 1 })

    studio.value = { projectId: 2, projectCode: 'billing' } as WorkflowWorkingCopyState
    const billingLoad = resources.loadToolOptions()
    expect(mocks.listAllTools).toHaveBeenLastCalledWith({ enabled: true, projectId: 2 })

    orders.resolve([tool('orders.read', 1)])
    await ordersLoad
    expect(resources.availableTools.value).toEqual([])

    billing.resolve([tool('billing.read', 2)])
    await billingLoad
    expect(resources.availableTools.value.map((item) => item.name)).toEqual(['billing.read'])
  })

  it('does not load optional model or knowledge catalogs for a method/API graph', async () => {
    const resources = useWorkflowStudioResources({
      studio: ref({ workflowId: 'orders', projectId: 1, projectCode: 'orders' } as WorkflowWorkingCopyState),
      aiModelInstanceId: ref(''), selectedToolName: ref(''),
    })
    await resources.loadGraphResourceOptions(['start', 'userInput', 'tool', 'variable', 'end'])
    expect(mocks.getModelInstances).not.toHaveBeenCalled()
    expect(mocks.getKnowledgeList).not.toHaveBeenCalled()
  })

  it('loads the model catalog only when the graph uses a model-backed node', async () => {
    const resources = useWorkflowStudioResources({
      studio: ref({ workflowId: 'orders', projectId: 1, projectCode: 'orders' } as WorkflowWorkingCopyState),
      aiModelInstanceId: ref(''), selectedToolName: ref(''),
    })
    await resources.loadGraphResourceOptions(['parameter', 'classifier', 'llm'])
    await resources.loadGraphResourceOptions(['llm'])
    expect(mocks.getModelInstances).toHaveBeenCalledTimes(1)
    expect(mocks.getKnowledgeList).not.toHaveBeenCalled()
  })

  it('loads knowledge for knowledge nodes and retries model loading explicitly after failure', async () => {
    mocks.getModelInstances.mockRejectedValue(new Error('catalog unavailable'))
    const resources = useWorkflowStudioResources({
      studio: ref({ workflowId: 'orders', projectId: 1, projectCode: 'orders' } as WorkflowWorkingCopyState),
      aiModelInstanceId: ref(''), selectedToolName: ref(''),
    })
    await resources.loadGraphResourceOptions(['knowledge', 'knowledgeWrite', 'llm'])
    expect(mocks.getKnowledgeList).toHaveBeenCalledTimes(1)
    expect(resources.modelOptionsLoadError.value).toBe(true)
    await resources.loadModelOptions()
    expect(mocks.getModelInstances).toHaveBeenCalledTimes(2)
    expect(resources.modelOptionsLoadError.value).toBe(true)
  })

  it('does not prefetch before the working copy is loaded and reloads resources for another project', async () => {
    const studio = ref<WorkflowWorkingCopyState | null>(null)
    const resources = useWorkflowStudioResources({ studio, aiModelInstanceId: ref(''), selectedToolName: ref('') })
    await resources.loadGraphResourceOptions(['llm', 'knowledge'])
    expect(mocks.getModelInstances).not.toHaveBeenCalled()
    expect(mocks.getKnowledgeList).not.toHaveBeenCalled()
    studio.value = { workflowId: 'orders', projectId: 1, projectCode: 'orders' } as WorkflowWorkingCopyState
    await resources.loadGraphResourceOptions(['llm', 'knowledge'])
    studio.value = { workflowId: 'billing', projectId: 2, projectCode: 'billing' } as WorkflowWorkingCopyState
    await resources.loadGraphResourceOptions(['llm', 'knowledge'])
    expect(mocks.getModelInstances).toHaveBeenCalledTimes(2)
    expect(mocks.getKnowledgeList).toHaveBeenCalledTimes(2)
  })

  it('uses the unwrapped knowledge catalog and discards both catalogs from an old document', async () => {
    const models = deferred<{ data: unknown[] }>()
    const knowledge = deferred<{ data: { code: string }[] }>()
    mocks.getModelInstances.mockReturnValueOnce(models.promise)
    mocks.getKnowledgeList.mockReturnValueOnce(knowledge.promise)
    const studio = ref({ workflowId: 'orders', projectId: 1, projectCode: 'orders' } as WorkflowWorkingCopyState)
    const resources = useWorkflowStudioResources({ studio, aiModelInstanceId: ref(''), selectedToolName: ref('') })
    const oldLoad = resources.loadGraphResourceOptions(['llm', 'knowledge'])
    studio.value = { workflowId: 'billing', projectId: 2, projectCode: 'billing' } as WorkflowWorkingCopyState
    await resources.loadGraphResourceOptions(['tool'])
    models.resolve({ data: [{ id: 'old', modelType: 'LLM', status: 'ACTIVE' }] })
    knowledge.resolve({ data: [{ code: 'old' }] })
    await oldLoad
    expect(resources.modelOptions.value).toEqual([])
    expect(resources.knowledgeOptions.value).toEqual([])
    mocks.getKnowledgeList.mockResolvedValueOnce({ data: [{ code: 'billing' }] })
    await resources.loadGraphResourceOptions(['knowledge'])
    expect(resources.knowledgeOptions.value.map((item) => item.code)).toEqual(['billing'])
  })

  it.each([false, true])('keeps both pending catalogs after normal same-scope save (edited during save: %s)', async (editedDuringSave) => {
    const models = deferred<{ data: unknown[] }>()
    const knowledge = deferred<{ data: { code: string }[] }>()
    const saved = deferred<{ data: WorkflowWorkingCopyState }>()
    mocks.getModelInstances.mockReturnValueOnce(models.promise)
    mocks.getKnowledgeList.mockReturnValueOnce(knowledge.promise)
    mocks.saveWorkflowWorkingCopy.mockReturnValueOnce(saved.promise)
    const fixture = savingResourceFixture()
    const original = fixture.studio.value
    const load = fixture.resources.loadGraphResourceOptions(['llm', 'knowledge'])
    expect(fixture.resources.modelOptionsLoading.value).toBe(true)

    const save = fixture.saveStudio()
    if (editedDuringSave) fixture.editGeneration.value++
    saved.resolve({ data: workingCopy({ revision: 'rev-2', defaultModelInstanceId: 'model-orders' }) })
    const result = await save
    expect(result === null).toBe(editedDuringSave)
    expect(fixture.studio.value).not.toBe(original)
    expect(fixture.studio.value).toMatchObject({ workflowId: 'orders', projectId: 1, projectCode: 'orders', revision: 'rev-2' })
    expect(mocks.saveWorkflowWorkingCopy).toHaveBeenCalledWith('orders', expect.objectContaining({ baseRevision: 'rev-1' }))
    await fixture.resources.loadGraphResourceOptions(['llm', 'knowledge'])
    expect(mocks.getModelInstances).toHaveBeenCalledTimes(1)
    expect(mocks.getKnowledgeList).toHaveBeenCalledTimes(1)

    models.resolve({ data: [{ id: 'model-orders', modelType: 'LLM', status: 'ACTIVE' }] })
    knowledge.resolve({ data: [{ code: 'orders-kb' }] })
    await load
    expect.soft(fixture.resources.modelOptions.value.map(item => item.id)).toEqual(['model-orders'])
    expect.soft(fixture.resources.knowledgeOptions.value.map(item => item.code)).toEqual(['orders-kb'])
    expect(fixture.resources.modelOptionsLoading.value).toBe(false)
    expect(fixture.resources.modelOptionsLoadError.value).toBe(false)
    expect(fixture.aiModelInstanceId.value).toBe('model-orders')
    await fixture.resources.loadGraphResourceOptions(['llm', 'knowledge'])
    expect(mocks.getModelInstances).toHaveBeenCalledTimes(1)
    expect(mocks.getKnowledgeList).toHaveBeenCalledTimes(1)
  })

  it.each([
    { workflowId: 'billing' }, { projectId: 2 }, { projectCode: 'billing' },
  ])('isolates a real scope change without relying on draft object replacement: %j', async (changedScope) => {
    const models = deferred<{ data: unknown[] }>()
    const knowledge = deferred<{ data: { code: string }[] }>()
    mocks.getModelInstances.mockReturnValueOnce(models.promise)
      .mockResolvedValueOnce({ data: [{ id: 'current', modelType: 'LLM', status: 'ACTIVE' }] })
    mocks.getKnowledgeList.mockReturnValueOnce(knowledge.promise)
      .mockResolvedValueOnce({ data: [{ code: 'current' }] })
    const fixture = savingResourceFixture()
    const oldLoad = fixture.resources.loadGraphResourceOptions(['llm', 'knowledge'])
    Object.assign(fixture.studio.value!, changedScope)
    models.resolve({ data: [{ id: 'old', modelType: 'LLM', status: 'ACTIVE' }] })
    knowledge.resolve({ data: [{ code: 'old' }] })
    await oldLoad
    expect(fixture.resources.modelOptions.value).toEqual([])
    expect(fixture.resources.knowledgeOptions.value).toEqual([])
    expect(fixture.resources.modelOptionsLoading.value).toBe(false)
    await fixture.resources.loadGraphResourceOptions(['llm', 'knowledge'])
    expect(fixture.resources.modelOptions.value.map(item => item.id)).toEqual(['current'])
    expect(fixture.resources.knowledgeOptions.value.map(item => item.code)).toEqual(['current'])
    expect(mocks.getModelInstances).toHaveBeenCalledTimes(2)
    expect(mocks.getKnowledgeList).toHaveBeenCalledTimes(2)
  })

  it.each([false, true])('invalidates an away/back switch within one tick and rearms loading (old request fails: %s)', async (oldFails) => {
    const models = deferred<{ data: unknown[] }>()
    const knowledge = deferred<{ data: { code: string }[] }>()
    mocks.getModelInstances.mockReturnValueOnce(models.promise)
      .mockResolvedValueOnce({ data: [{ id: 'returned', modelType: 'LLM', status: 'ACTIVE' }] })
    mocks.getKnowledgeList.mockReturnValueOnce(knowledge.promise)
      .mockResolvedValueOnce({ data: [{ code: 'returned' }] })
    const fixture = savingResourceFixture()
    const oldLoad = fixture.resources.loadGraphResourceOptions(['llm', 'knowledge'])
    fixture.studio.value = workingCopy({ workflowId: 'billing', projectId: 2, projectCode: 'billing' })
    fixture.studio.value = workingCopy()
    if (oldFails) models.reject(new Error('old catalog unavailable'))
    else models.resolve({ data: [{ id: 'old', modelType: 'LLM', status: 'ACTIVE' }] })
    knowledge.resolve({ data: [{ code: 'old' }] })
    await oldLoad
    expect(fixture.resources.modelOptions.value).toEqual([])
    expect(fixture.resources.knowledgeOptions.value).toEqual([])
    expect(fixture.resources.modelOptionsLoadError.value).toBe(false)
    await fixture.resources.loadGraphResourceOptions(['llm', 'knowledge'])
    expect(fixture.resources.modelOptions.value.map(item => item.id)).toEqual(['returned'])
    expect(fixture.resources.knowledgeOptions.value.map(item => item.code)).toEqual(['returned'])
    expect(mocks.getModelInstances).toHaveBeenCalledTimes(2)
    expect(mocks.getKnowledgeList).toHaveBeenCalledTimes(2)
  })

  it.each([
    { newestFirst: false, oldFails: false }, { newestFirst: true, oldFails: false },
    { newestFirst: false, oldFails: true }, { newestFirst: true, oldFails: true },
  ])('keeps the newest same-scope request in either completion order: %j', async ({ newestFirst, oldFails }) => {
    const firstModels = deferred<{ data: unknown[] }>()
    const secondModels = deferred<{ data: unknown[] }>()
    const firstKnowledge = deferred<{ data: { code: string }[] }>()
    const secondKnowledge = deferred<{ data: { code: string }[] }>()
    mocks.getModelInstances.mockReturnValueOnce(firstModels.promise).mockReturnValueOnce(secondModels.promise)
    mocks.getKnowledgeList.mockReturnValueOnce(firstKnowledge.promise).mockReturnValueOnce(secondKnowledge.promise)
    const fixture = savingResourceFixture()
    const oldLoad = Promise.all([fixture.resources.loadModelOptions(), fixture.resources.loadKnowledgeOptions()])
    const newLoad = Promise.all([fixture.resources.loadModelOptions(), fixture.resources.loadKnowledgeOptions()])
    const finishOld = () => {
      if (oldFails) {
        firstModels.reject(new Error('old models failed'))
        firstKnowledge.reject(new Error('old knowledge failed'))
      } else {
        firstModels.resolve({ data: [{ id: 'old', modelType: 'LLM', status: 'ACTIVE' }] })
        firstKnowledge.resolve({ data: [{ code: 'old' }] })
      }
    }
    if (!newestFirst) {
      finishOld()
      await oldLoad
      expect(fixture.resources.modelOptionsLoading.value).toBe(true)
      expect(fixture.resources.modelOptionsLoadError.value).toBe(false)
      expect(fixture.resources.modelOptions.value).toEqual([])
      expect(fixture.resources.knowledgeOptions.value).toEqual([])
    }
    secondModels.resolve({ data: [{ id: 'new', modelType: 'LLM', status: 'ACTIVE' }] })
    secondKnowledge.resolve({ data: [{ code: 'new' }] })
    await newLoad
    if (newestFirst) { finishOld(); await oldLoad }
    expect(fixture.resources.modelOptions.value.map(item => item.id)).toEqual(['new'])
    expect(fixture.resources.knowledgeOptions.value.map(item => item.code)).toEqual(['new'])
    expect(fixture.resources.modelOptionsLoading.value).toBe(false)
    expect(fixture.resources.modelOptionsLoadError.value).toBe(false)
    expect(mocks.getModelInstances).toHaveBeenCalledTimes(2)
    expect(mocks.getKnowledgeList).toHaveBeenCalledTimes(2)
  })

  it('preserves a real model failure after same-scope save and recovers only by explicit retry', async () => {
    const models = deferred<{ data: unknown[] }>()
    const knowledge = deferred<{ data: { code: string }[] }>()
    mocks.getModelInstances.mockReturnValueOnce(models.promise)
      .mockResolvedValueOnce({ data: [{ id: 'model-orders', modelType: 'LLM', status: 'ACTIVE' }] })
    mocks.getKnowledgeList.mockReturnValueOnce(knowledge.promise)
    mocks.saveWorkflowWorkingCopy.mockResolvedValueOnce({ data: workingCopy({ revision: 'rev-2' }) })
    const fixture = savingResourceFixture()
    const load = fixture.resources.loadGraphResourceOptions(['llm', 'knowledge'])
    expect(await fixture.saveStudio()).not.toBeNull()
    models.reject(new Error('catalog unavailable'))
    knowledge.resolve({ data: [{ code: 'orders-kb' }] })
    await load
    expect(fixture.resources.modelOptionsLoadError.value).toBe(true)
    expect(fixture.resources.modelOptionsLoading.value).toBe(false)
    expect(fixture.resources.modelOptions.value).toEqual([])
    expect(fixture.resources.knowledgeOptions.value.map(item => item.code)).toEqual(['orders-kb'])
    await fixture.resources.loadGraphResourceOptions(['llm', 'knowledge'])
    expect(mocks.getModelInstances).toHaveBeenCalledTimes(1)
    await fixture.resources.loadModelOptions()
    expect(fixture.resources.modelOptions.value.map(item => item.id)).toEqual(['model-orders'])
    expect(fixture.resources.modelOptionsLoadError.value).toBe(false)
    expect(fixture.resources.modelOptionsLoading.value).toBe(false)
    expect(mocks.getModelInstances).toHaveBeenCalledTimes(2)
    expect(mocks.getKnowledgeList).toHaveBeenCalledTimes(1)
  })

  it('discards pending catalog responses after disposing the component scope', async () => {
    const models = deferred<{ data: unknown[] }>()
    const knowledge = deferred<{ data: { code: string }[] }>()
    mocks.getModelInstances.mockReturnValueOnce(models.promise)
    mocks.getKnowledgeList.mockReturnValueOnce(knowledge.promise)
    const fixture = savingResourceFixture()
    const load = fixture.resources.loadGraphResourceOptions(['llm', 'knowledge'])
    scopes[scopes.length - 1]!.stop()
    models.resolve({ data: [{ id: 'old', modelType: 'LLM', status: 'ACTIVE' }] })
    knowledge.resolve({ data: [{ code: 'old' }] })
    await load
    expect(fixture.resources.modelOptions.value).toEqual([])
    expect(fixture.resources.knowledgeOptions.value).toEqual([])
    expect(fixture.resources.modelOptionsLoadError.value).toBe(false)
    expect(fixture.resources.modelOptionsLoading.value).toBe(false)
  })
})
