import { computed, defineComponent, ref } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useSourceSemanticDocs } from './useSourceSemanticDocs'
import type { SemanticDoc, SemanticSubjectType } from '@/types/semanticDoc'
import type { ProjectToolInfo } from '@/types/scanProject'

const api = vi.hoisted(() => ({ status: vi.fn(), sensitive: vi.fn(), sensitiveStart: vi.fn(), models: vi.fn(),
  docs: vi.fn(), groups: vi.fn(), generate: vi.fn(), edit: vi.fn(), batch: vi.fn(), rename: vi.fn() }))
const messages = vi.hoisted(() => ({ success: vi.fn(), error: vi.fn(), warning: vi.fn(), info: vi.fn() }))
vi.mock('@/api/semanticDoc', () => ({ getProjectBatchStatus: api.status, listProjectSemanticDocs: api.docs,
  listScanModules: api.groups, editSubjectSemanticDoc: api.edit, generateSubjectSemanticDoc: api.generate,
  renameScanModule: api.rename, startProjectBatchGenerate: api.batch }))
vi.mock('@/api/scanProject', () => ({ getSensitiveDataScanStatus: api.sensitive, startSensitiveDataScan: api.sensitiveStart }))
vi.mock('@/api/model', () => ({ getModelInstances: api.models }))
vi.mock('element-plus', () => ({ ElMessage: messages }))
const wrappers: ReturnType<typeof mount>[] = []
beforeEach(() => {
  vi.resetAllMocks()
  api.groups.mockResolvedValue({ data: [] }); api.docs.mockResolvedValue({ data: [] })
  api.models.mockResolvedValue({ data: [{ id: 'llm', modelType: 'LLM', status: 'ACTIVE' }] })
  api.status.mockResolvedValue({ data: null }); api.sensitive.mockResolvedValue({ data: null })
})
afterEach(() => { wrappers.splice(0).forEach(wrapper => wrapper.unmount()); vi.useRealTimers(); vi.clearAllMocks() })
function setup(canWrite?: () => boolean) {
  const refresh = vi.fn().mockResolvedValue(undefined)
  let state!: ReturnType<typeof useSourceSemanticDocs>
  const wrapper = mount(defineComponent({ setup() {
    state = useSourceSemanticDocs({ projectId: computed(() => 7), project: ref(null), tools: ref([]),
      modelGenerateDrawerVisible: ref(false), refreshAll: refresh, canWrite })
    return () => null
  } }))
  wrappers.push(wrapper)
  return { state, wrapper, refresh }
}
const task = (projectId = 7) => ({ taskId: 'batch-1', projectId, stage: 'RUNNING', totalSteps: 2, completedSteps: 1 })
function doc(subjectType: SemanticSubjectType = 'HTTP_API', subjectId = 11): SemanticDoc {
  return { id: subjectId, projectId: 7, subjectType, subjectId, subjectKey: `${subjectType}:${subjectId}`,
    title: '业务说明', contentMd: '原说明', promptVersion: null, modelName: 'llm', tokenUsage: 0,
    status: 'generated', updatedAt: '2026-10-10T08:00:00', sourceObservationIds: [] }
}
function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>(done => { resolve = done })
  return { promise, resolve }
}

describe('lazy model validation before LLM actions', () => {
  it('validates a saved model only on the first generation and reuses a successful read', async () => {
    api.generate.mockImplementation((_project, type, id) => Promise.resolve({ data: doc(type, id) }))
    const { state } = setup()
    state.semanticModelInstanceId.value = 'llm'
    state.saveAiGenerationSettings()
    state.semanticModelInstanceId.value = ''
    state.restoreAiGenerationSettings()
    expect(api.models).not.toHaveBeenCalled()
    await state.regenerateSemanticDoc(doc())
    expect(api.models).toHaveBeenCalledOnce()
    expect(api.generate).toHaveBeenCalledWith(7, 'HTTP_API', 11, true, { modelInstanceId: 'llm' })
    await state.regenerateProject()
    expect(api.models).toHaveBeenCalledOnce()
    expect(api.generate).toHaveBeenLastCalledWith(7, 'PROJECT', 7, true, { modelInstanceId: 'llm' })
    window.localStorage.clear()
  })

  it('reuses model options successfully read by the drawer', async () => {
    api.generate.mockResolvedValue({ data: doc('PROJECT', 7) })
    const { state } = setup()
    state.semanticModelInstanceId.value = 'llm'
    await state.loadSemanticModelInstances()
    await state.regenerateProject()
    expect(api.models).toHaveBeenCalledOnce()
    expect(api.generate).toHaveBeenCalledOnce()
  })

  it('shares an in-flight model read and locks each owner before awaiting validation', async () => {
    const models = deferred<any>()
    api.models.mockReturnValueOnce(models.promise)
    api.generate.mockImplementation((_project, type, id) => Promise.resolve({ data: doc(type, id) }))
    const { state } = setup()
    state.semanticModelInstanceId.value = 'llm'
    const drawerRead = state.loadSemanticModelInstances()
    const project = state.regenerateProject()
    const duplicate = state.regenerateProject()
    const asset = state.regenerateSemanticDoc(doc())
    expect(api.models).toHaveBeenCalledOnce()
    expect(api.generate).not.toHaveBeenCalled()
    expect(state.semanticGeneratingKeys.value).toEqual(['PROJECT:7', 'HTTP_API:11'])
    expect(state.projectDocGenerating.value).toBe(true)
    models.resolve({ data: [{ id: 'llm', modelType: 'LLM', status: 'ACTIVE' }] })
    await Promise.all([drawerRead, project, duplicate, asset])
    expect(api.generate).toHaveBeenCalledTimes(2)
    expect(state.semanticGeneratingKeys.value).toEqual([])
  })

  it.each([
    { label: 'disabled', models: [{ id: 'llm', modelType: 'LLM', status: 'DISABLED' }] },
    { label: 'another model type', models: [{ id: 'llm', modelType: 'EMBEDDING', status: 'ACTIVE' }] },
    { label: 'removed', models: [] },
  ])('does not generate with a saved $label model', async ({ models }) => {
    api.models.mockResolvedValue({ data: models })
    const { state } = setup()
    state.semanticModelInstanceId.value = 'llm'
    await state.regenerateSemanticDoc(doc())
    expect(api.models).toHaveBeenCalledOnce()
    expect(api.generate).not.toHaveBeenCalled()
    expect(state.semanticModelInstanceId.value).toBe('')
    expect(state.semanticGeneratingKeys.value).toEqual([])
    expect(messages.warning).toHaveBeenCalledWith(expect.stringContaining('模型已不可用'))
  })

  it('blocks generation after a failed model read and permits an explicit retry', async () => {
    api.models.mockRejectedValueOnce(new Error('Model catalog unavailable'))
    api.generate.mockResolvedValue({ data: doc() })
    const { state } = setup()
    state.semanticModelInstanceId.value = 'llm'
    await state.regenerateSemanticDoc(doc())
    expect(api.generate).not.toHaveBeenCalled()
    expect(state.semanticModelInstancesLoadError.value).toBe(true)
    expect(state.semanticModelInstanceId.value).toBe('llm')
    expect(state.semanticGeneratingKeys.value).toEqual([])
    await state.regenerateSemanticDoc(doc())
    expect(api.models).toHaveBeenCalledTimes(2)
    expect(api.generate).toHaveBeenCalledOnce()
    expect(state.semanticModelInstancesLoadError.value).toBe(false)
  })

  it('does not dispatch the old selection after a model changes during validation', async () => {
    const models = deferred<any>()
    api.models.mockReturnValueOnce(models.promise)
    api.generate.mockResolvedValue({ data: doc() })
    const { state } = setup()
    state.semanticModelInstanceId.value = 'llm'
    const generation = state.regenerateSemanticDoc(doc())
    state.semanticModelInstanceId.value = 'llm-2'
    models.resolve({ data: [
      { id: 'llm', modelType: 'LLM', status: 'ACTIVE' },
      { id: 'llm-2', modelType: 'LLM', status: 'ACTIVE' },
    ] })
    await generation
    expect(api.generate).not.toHaveBeenCalled()
    await state.regenerateSemanticDoc(doc())
    expect(api.models).toHaveBeenCalledOnce()
    expect(api.generate).toHaveBeenCalledWith(7, 'HTTP_API', 11, true, { modelInstanceId: 'llm-2' })
  })

  it('asks for a selection without reading models or dispatching generation when none was saved', async () => {
    const { state } = setup()
    await state.regenerateProject()
    expect(api.models).not.toHaveBeenCalled()
    expect(api.generate).not.toHaveBeenCalled()
    expect(state.semanticGeneratingKeys.value).toEqual([])
    expect(messages.warning).toHaveBeenCalledWith('请先选择 LLM 模型实例')
  })

  it.each(['project', 'asset', 'source', 'batch', 'sensitive'].flatMap(action =>
    ['unmount', 'permission-revoked'].map(interruption => ({ action, interruption }))))(
    'does not dispatch $action after $interruption while validating the model', async ({ action, interruption }) => {
      const models = deferred<any>(), permission = ref(true)
      api.models.mockReturnValueOnce(models.promise)
      const { state, wrapper } = setup(() => permission.value)
      state.semanticModelInstanceId.value = 'llm'
      const operation = action === 'project' ? state.regenerateProject()
        : action === 'asset' ? state.regenerateSemanticDoc(doc())
        : action === 'source' ? state.regenerateTool({ scanToolId: 31, name: 'orders:read' } as ProjectToolInfo)
        : action === 'batch' ? state.startBatchGenerate(false) : state.startSensitiveDataScanFlow()
      expect(api.models).toHaveBeenCalledOnce()
      if (interruption === 'unmount') wrapper.unmount()
      else permission.value = false
      models.resolve({ data: [{ id: 'llm', modelType: 'LLM', status: 'ACTIVE' }] })
      await operation
      expect(api.generate).not.toHaveBeenCalled()
      expect(api.batch).not.toHaveBeenCalled()
      expect(api.sensitiveStart).not.toHaveBeenCalled()
      expect(state.semanticGeneratingKeys.value).toEqual([])
      expect(state.batchStarting.value).toBe(false)
      expect(state.sensitiveScanStarting.value).toBe(false)
    })
})

describe('typed semantics and task lifecycle', () => {
  it('keeps read-only reads, task recovery and local preferences without sending semantic writes', async () => {
    const asset = doc()
    api.docs.mockResolvedValue({ data: [asset] })
    api.generate.mockImplementation((_project, type, id) => Promise.resolve({ data: doc(type, id) }))
    api.batch.mockResolvedValue({ data: { ...task(), stage: 'DONE' } })
    api.status.mockResolvedValue({ data: null })
    const { state } = setup(() => false)
    state.semanticModelInstanceId.value = 'llm'
    await state.loadSemanticAssetsForRefresh()
    await state.resumeBatchTaskIfAny()
    const { contentMd: _body, ...metadata } = asset
    expect(state.semanticDocs.value).toEqual([metadata])
    expect(state.semanticDocs.value[0]).not.toHaveProperty('contentMd')
    expect(api.docs).toHaveBeenCalledOnce()
    expect(api.status).toHaveBeenCalledOnce()
    state.saveAiGenerationSettings()
    state.semanticModelInstanceId.value = ''
    state.restoreAiGenerationSettings()
    expect(state.semanticModelInstanceId.value).toBe('llm')
    await state.startBatchGenerate(false)
    await state.regenerateProject()
    await state.regenerateSemanticDoc(doc('GROUP', 2))
    await state.regenerateTool({ scanToolId: 31, name: 'orders:read' } as ProjectToolInfo)
    await state.regenerateSemanticDoc(asset)
    state.openEditDoc(asset)
    await state.submitDocEdit()
    expect(api.batch).not.toHaveBeenCalled()
    expect(api.generate).not.toHaveBeenCalled()
    expect(api.edit).not.toHaveBeenCalled()
    expect(state.semanticGeneratingKeys.value).toEqual([])
    expect(state.projectDocGenerating.value).toBe(false)
    expect(state.docEditVisible.value).toBe(false)
    window.localStorage.clear()
  })
  it('checks the current write permission again after an editor opens and permits saving after access returns', async () => {
    const permission = ref(true), first = doc(), second = doc('BUSINESS_METHOD', 12)
    api.edit.mockResolvedValue({ data: first })
    const { state } = setup(() => permission.value)
    state.openEditDoc(first)
    state.docEditContent.value = '撤销权限后仍保留的草稿'
    permission.value = false
    state.openEditDoc(second)
    await state.submitDocEdit()
    expect(api.edit).not.toHaveBeenCalled()
    expect(state.docEditContent.value).toBe('撤销权限后仍保留的草稿')
    expect(state.docEditVisible.value).toBe(true)
    expect(state.docEditSaving.value).toBe(false)
    permission.value = true
    await state.submitDocEdit()
    expect(api.edit).toHaveBeenCalledWith(7, 'HTTP_API', first.subjectId,
      { contentMd: '撤销权限后仍保留的草稿', expectedUpdatedAt: first.updatedAt })
    expect(state.docEditVisible.value).toBe(false)
  })
  it('shares the formal-owner generation lock with its source observations', async () => {
    const asset = { ...doc(), sourceObservationIds: [31] }
    const generation = deferred<{ data: SemanticDoc }>()
    api.generate.mockReturnValue(generation.promise)
    api.docs.mockResolvedValue({ data: [asset] })
    const { state } = setup()
    state.semanticModelInstanceId.value = 'llm'
    state.applySemanticDocsFromList([asset])
    const first = state.regenerateSemanticDoc(asset)
    const duplicate = state.regenerateSemanticDoc(asset)
    const source = state.regenerateTool({ scanToolId: 31, name: 'orders:read' } as ProjectToolInfo)
    await flushPromises()
    expect(api.generate).toHaveBeenCalledTimes(1)
    expect(state.semanticGeneratingKeys.value).toEqual(['HTTP_API:11'])
    generation.resolve({ data: asset })
    await Promise.all([first, duplicate, source])
    expect(state.semanticGeneratingKeys.value).toEqual([])
    expect(messages.success).toHaveBeenCalledOnce()
  })
  it('keeps different typed owners with the same ID independently busy', async () => {
    const asset = doc('HTTP_API', 11), group = doc('GROUP', 11)
    const assetGeneration = deferred<{ data: SemanticDoc }>(), groupGeneration = deferred<{ data: SemanticDoc }>()
    api.generate.mockImplementation((_project, type) => type === 'GROUP' ? groupGeneration.promise : assetGeneration.promise)
    api.docs.mockResolvedValue({ data: [asset, group] })
    const { state } = setup()
    state.semanticModelInstanceId.value = 'llm'
    const first = state.regenerateSemanticDoc(asset)
    const second = state.regenerateSemanticDoc(group)
    await flushPromises()
    expect(api.generate).toHaveBeenCalledTimes(2)
    expect(state.semanticGeneratingKeys.value).toEqual(['HTTP_API:11', 'GROUP:11'])
    groupGeneration.resolve({ data: group }); await second
    expect(state.semanticGeneratingKeys.value).toEqual(['HTTP_API:11'])
    assetGeneration.resolve({ data: asset }); await first
    expect(state.semanticGeneratingKeys.value).toEqual([])
  })
  it('locks a newly resolved formal owner while its source generation refresh is pending', async () => {
    const asset = { ...doc(), sourceObservationIds: [31] }
    api.generate.mockResolvedValue({ data: asset })
    const { state, refresh } = setup()
    const followUp = deferred<void>()
    refresh.mockReturnValue(followUp.promise)
    state.semanticModelInstanceId.value = 'llm'
    const source = state.regenerateTool({ scanToolId: 31, name: 'orders:read' } as ProjectToolInfo)
    await flushPromises()
    expect(state.toolDocMap.value[31].subjectType).toBe('HTTP_API')
    const duplicate = state.regenerateSemanticDoc(asset)
    expect(api.generate).toHaveBeenCalledTimes(1)
    expect(state.semanticGeneratingKeys.value).toContain('HTTP_API:11')
    followUp.resolve(); await Promise.all([source, duplicate])
    expect(state.semanticGeneratingKeys.value).toEqual([])
  })
  it('shares the project generation lock with an existing project document', async () => {
    const projectDoc = doc('PROJECT', 7)
    const generation = deferred<{ data: SemanticDoc }>()
    api.generate.mockReturnValue(generation.promise)
    const { state } = setup()
    state.semanticModelInstanceId.value = 'llm'
    const first = state.regenerateProject()
    const duplicate = state.regenerateSemanticDoc(projectDoc)
    await flushPromises()
    expect(api.generate).toHaveBeenCalledTimes(1)
    expect(state.projectDocGenerating.value).toBe(true)
    expect(state.semanticGeneratingKeys.value).toEqual(['PROJECT:7'])
    generation.resolve({ data: projectDoc }); await Promise.all([first, duplicate])
    expect(state.projectDocGenerating.value).toBe(false)
    expect(state.semanticGeneratingKeys.value).toEqual([])
  })
  it('does not announce a generation after its follow-up read outlives the project context', async () => {
    const asset = doc()
    const read = deferred<{ data: SemanticDoc[] }>()
    api.generate.mockResolvedValue({ data: asset })
    api.docs.mockReturnValue(read.promise)
    const { state, wrapper } = setup()
    state.semanticModelInstanceId.value = 'llm'
    const generating = state.regenerateSemanticDoc(asset)
    await flushPromises()
    expect(api.docs).toHaveBeenCalledOnce()
    wrapper.unmount()
    read.resolve({ data: [asset] }); await generating
    expect(state.semanticDocs.value).toEqual([])
    expect(messages.success).not.toHaveBeenCalled()
  })
  it('keeps the current editor locked while saving and does not open another document', async () => {
    const first = doc(), second = doc('BUSINESS_METHOD', 12)
    const save = deferred<{ data: SemanticDoc }>()
    api.edit.mockReturnValue(save.promise)
    const { state } = setup()
    state.applySemanticDocsFromList([first, second])
    state.openEditDoc(first)
    state.docEditContent.value = '保留当前用户输入'
    const saving = state.submitDocEdit()
    void state.submitDocEdit()
    state.openEditDoc(second)
    expect(api.edit).toHaveBeenCalledOnce()
    expect(api.edit).toHaveBeenCalledWith(7, 'HTTP_API', 11,
      { contentMd: '保留当前用户输入', expectedUpdatedAt: first.updatedAt })
    expect(state.docEditContent.value).toBe('保留当前用户输入')
    expect(state.docEditVisible.value).toBe(true)
    save.resolve({ data: { ...first, contentMd: '保留当前用户输入', status: 'edited' } }); await saving
    expect(state.docEditVisible.value).toBe(false)
    expect(state.docEditSaving.value).toBe(false)
    state.openEditDoc(second)
    expect(state.docEditContent.value).toBe(second.contentMd)
  })
  it('keeps the editor and unsaved input after an expected-revision conflict', async () => {
    api.edit.mockRejectedValue({ response: { status: 409, data: { message: '文档已变化，请刷新后编辑' } } })
    const { state } = setup()
    state.openEditDoc(doc())
    state.docEditContent.value = '不能丢失的修改'
    await state.submitDocEdit()
    expect(state.docEditVisible.value).toBe(true)
    expect(state.docEditContent.value).toBe('不能丢失的修改')
    expect(state.docEditSaving.value).toBe(false)
    expect(messages.error).toHaveBeenCalled()
    expect(messages.success).not.toHaveBeenCalled()
  })
  it('uses one formal owner document for its linked observations and rejects a foreign owner list', () => {
    const { state } = setup()
    const doc = { id: 1, projectId: 7, subjectType: 'BUSINESS_METHOD', subjectId: 11, subjectKey: 'orders:read',
      sourceObservationIds: [31, 32], contentMd: '用途', status: 'generated' } as SemanticDoc
    state.applySemanticDocsFromList([doc])
    expect(state.semanticDocs.value).toHaveLength(1)
    expect(state.toolDocMap.value[31].subjectType).toBe('BUSINESS_METHOD')
    expect(state.toolDocMap.value[32].subjectId).toBe(11)
    expect(() => state.applySemanticDocsFromList([{ ...doc, projectId: 8 }])).toThrow('Invalid semantic owner')
    expect(state.semanticDocs.value[0].projectId).toBe(7)
  })
  it('stops semantic and sensitive polling on scope unmount and ignores late results', async () => {
    vi.useFakeTimers()
    let completePoll!: (value: unknown) => void
    api.status.mockResolvedValueOnce({ data: task() }).mockReturnValueOnce(new Promise(resolve => { completePoll = resolve }))
    api.sensitive.mockResolvedValue({ data: { ...task(), taskId: 'scan-1' } })
    const { state, wrapper, refresh } = setup()
    await state.resumeBatchTaskIfAny(); await state.resumeSensitiveTaskIfAny(); await flushPromises()
    const beforeSemantic = api.status.mock.calls.length, beforeSensitive = api.sensitive.mock.calls.length
    wrapper.unmount()
    completePoll({ data: { ...task(), stage: 'DONE' } }); await flushPromises()
    await vi.advanceTimersByTimeAsync(6000)
    expect(api.status).toHaveBeenCalledTimes(beforeSemantic)
    expect(api.sensitive).toHaveBeenCalledTimes(beforeSensitive)
    expect(refresh).not.toHaveBeenCalled()
    expect(api.docs).not.toHaveBeenCalled()
  })
  it('does not resume a semantic task from another project', async () => {
    vi.useFakeTimers(); api.status.mockResolvedValue({ data: task(8) })
    const { state } = setup()
    await state.resumeBatchTaskIfAny(); await vi.advanceTimersByTimeAsync(5000)
    expect(state.task.value).toBeNull()
    expect(api.status).toHaveBeenCalledTimes(1)
  })
})
