import { computed, onBeforeUnmount, ref, type ComputedRef, type Ref } from 'vue'
import { ElMessage } from 'element-plus'
import { renderSafeMarkdown } from '@/utils/safeMarkdown'
import { platformSessionUser } from '@/auth/platformSession'
import type { ModelInstance } from '@/types/model'
import type { ProjectToolInfo, ScanProject, SensitiveScanTask } from '@/types/scanProject'
import type { SemanticDoc, SemanticDocIndex, SemanticSubjectType, SemanticTask } from '@/types/semanticDoc'
import { getModelInstances } from '@/api/model'
import { normalizeActiveModelInstances } from '@/utils/modelSelection'
import {
  getSensitiveDataScanStatus,
  startSensitiveDataScan,
} from '@/api/scanProject'
import {
  editSubjectSemanticDoc,
  generateSubjectSemanticDoc,
  getProjectBatchStatus,
  listProjectSemanticDocs,
  startProjectBatchGenerate,
  type SemanticLlmParams,
} from '@/api/semanticDoc'

type AiGenerationMode = 'missing' | 'force'

export interface UseSourceSemanticDocsDeps {
  projectId: ComputedRef<number>
  project: Ref<ScanProject | null>
  tools: Ref<ProjectToolInfo[]>
  modelGenerateDrawerVisible: Ref<boolean>
  refreshAll: () => Promise<void>
  canWrite?: () => boolean
}

export function useSourceSemanticDocs(deps: UseSourceSemanticDocsDeps) {
  let disposed = false
  let semanticSequence = 0
  let taskSequence = 0
  let sensitiveSequence = 0
  const semanticDocs = ref<SemanticDocIndex[]>([])
  const projectDocGenerating = ref(false)
  const semanticGeneratingKeys = ref<string[]>([])
  const projectDoc = ref<SemanticDocIndex | null>(null)
  const toolDocMap = ref<Record<number, SemanticDocIndex>>({})

  const batchStarting = ref(false)
  const taskStatusError = ref('')
  const sensitiveStatusError = ref('')
  const sensitiveScanStarting = ref(false)
  const sensitiveTask = ref<SensitiveScanTask | null>(null)
  let sensitivePollTimer: ReturnType<typeof setInterval> | null = null

  const sensitiveTaskPolling = computed(
    () => sensitiveTask.value?.stage === 'RUNNING' || sensitiveTask.value?.stage === 'QUEUED',
  )

  const task = ref<SemanticTask | null>(null)
  let pollTimer: ReturnType<typeof setInterval> | null = null

  const semanticModelInstances = ref<ModelInstance[]>([])
  const semanticModelInstancesLoading = ref(false)
  const semanticModelInstancesLoadError = ref(false)
  const semanticModelInstanceId = ref('')
  const aiGenerationMode = ref<AiGenerationMode>('missing')
  let semanticModelsLoaded = false
  let semanticModelLoadPromise: Promise<void> | null = null

  const docEditVisible = ref(false)
  const docEditContent = ref('')
  const editingDoc = ref<SemanticDoc | null>(null)
  const docEditSaving = ref(false)

  const taskPercent = computed(() => {
    if (!task.value || task.value.totalSteps <= 0) return 0
    return Math.min(100, Math.round((task.value.completedSteps / task.value.totalSteps) * 100))
  })
  const taskRunning = computed(() => task.value?.stage === 'QUEUED' || task.value?.stage === 'RUNNING')
  const taskFailed = computed(() => task.value?.stage === 'FAILED')
  const taskFailedTitle = computed(() => `批量生成失败：${task.value?.errorMessage || '未知错误'}`)

  function applySemanticDocsFromList(docs: SemanticDocIndex[]) {
    if (disposed) return
    if (!Array.isArray(docs) || docs.some(doc => doc.projectId !== deps.projectId.value
      || !['PROJECT', 'GROUP', 'BUSINESS_METHOD', 'HTTP_API', 'SOURCE_OBSERVATION'].includes(doc.subjectType)
      || !Number.isSafeInteger(doc.subjectId) || doc.subjectId <= 0 || !doc.subjectKey
      || doc.subjectType === 'PROJECT' && doc.subjectId !== deps.projectId.value
      || doc.sourceObservationIds != null && (!Array.isArray(doc.sourceObservationIds)
        || doc.sourceObservationIds.some(id => !Number.isSafeInteger(id) || id <= 0)))) {
      throw new Error('Invalid semantic owner')
    }
    if (new Set(docs.map(doc => `${doc.subjectType}:${doc.subjectId}`)).size !== docs.length) throw new Error('Duplicate semantic owner')
    const indices = docs.map(doc => {
      const { contentMd: _contentMd, ...index } = doc as SemanticDoc
      return index
    })
    semanticDocs.value = indices
    projectDoc.value = indices.find((doc) => doc.subjectType === 'PROJECT') || null
    const toolMap: Record<number, SemanticDocIndex> = {}
    const ambiguous = new Set<number>()
    for (const doc of indices) {
      for (const id of [...(doc.subjectType === 'SOURCE_OBSERVATION' ? [doc.subjectId] : []), ...(doc.sourceObservationIds || [])]) {
        const current = toolMap[id]
        if (current && (current.subjectType !== doc.subjectType || current.subjectId !== doc.subjectId)) ambiguous.add(id)
        else toolMap[id] = doc
      }
    }
    for (const id of ambiguous) delete toolMap[id]
    toolDocMap.value = toolMap
  }

  async function loadSemanticAssetsForRefresh() {
    const expected = ++semanticSequence
    const owner = deps.projectId.value
    const docResponse = await listProjectSemanticDocs(owner)
    if (disposed || expected !== semanticSequence || owner !== deps.projectId.value) return
    applySemanticDocsFromList(docResponse.data)
  }

  function clearSemanticAssets() {
    semanticSequence += 1
    applySemanticDocsFromList([])
  }

  function renderMd(content: string | null | undefined): string {
    return renderSafeMarkdown(content)
  }

  async function semanticLlmParams(): Promise<SemanticLlmParams | null> {
    if (disposed || deps.canWrite?.() === false) return null
    const id = semanticModelInstanceId.value?.trim()
    if (!id) {
      ElMessage.warning('请先选择 LLM 模型实例')
      return null
    }
    if (!semanticModelsLoaded || semanticModelLoadPromise) await loadSemanticModelInstances()
    if (disposed || deps.canWrite?.() === false) return null
    if (semanticModelInstancesLoadError.value) {
      ElMessage.warning('LLM 模型读取失败，请重新加载模型后再生成')
      return null
    }
    if (semanticModelInstanceId.value?.trim() !== id || !semanticModelInstances.value.some(model => model.id === id)) {
      ElMessage.warning('所选 LLM 模型已不可用或选择已变化，请重新选择后再操作')
      return null
    }
    return { modelInstanceId: id }
  }

  function loadSemanticModelInstances(): Promise<void> {
    if (disposed) return Promise.resolve()
    if (semanticModelLoadPromise) return semanticModelLoadPromise
    semanticModelsLoaded = false
    semanticModelInstancesLoading.value = true
    semanticModelInstancesLoadError.value = false
    semanticModelLoadPromise = (async () => {
      try {
        const { data } = await getModelInstances({ modelType: 'LLM' })
        if (disposed) return
        const list = normalizeActiveModelInstances(data, 'LLM')
        semanticModelInstances.value = list
        semanticModelsLoaded = true
        if (semanticModelInstanceId.value && !list.some(item => item.id === semanticModelInstanceId.value.trim())) {
          semanticModelInstanceId.value = ''
        }
      } catch {
        if (disposed) return
        semanticModelInstances.value = []
        semanticModelInstancesLoadError.value = true
      }
    })().finally(() => {
      semanticModelLoadPromise = null
      if (!disposed) semanticModelInstancesLoading.value = false
    })
    return semanticModelLoadPromise
  }

  function aiSettingsStorageKey() {
    return `reachai:business-capabilities:${String(platformSessionUser.value?.userId || '')}:${deps.projectId.value}:ai-settings`
  }

  function restoreAiGenerationSettings() {
    try {
      const raw = window.localStorage.getItem(aiSettingsStorageKey())
      if (!raw) return
      const saved = JSON.parse(raw) as { modelInstanceId?: string; generationMode?: AiGenerationMode }
      if (saved.modelInstanceId) {
        semanticModelInstanceId.value = saved.modelInstanceId
      }
      if (saved.generationMode === 'missing' || saved.generationMode === 'force') {
        aiGenerationMode.value = saved.generationMode
      }
    } catch {
      // Ignore local preference parse failures; the user can save again.
    }
  }

  function saveAiGenerationSettings() {
    window.localStorage.setItem(
      aiSettingsStorageKey(),
      JSON.stringify({
        modelInstanceId: semanticModelInstanceId.value,
        generationMode: aiGenerationMode.value,
      }),
    )
    deps.modelGenerateDrawerVisible.value = false
    ElMessage.success('AI 理解设置已保存')
  }

  async function resumeBatchTaskIfAny() {
    const expected = taskSequence
    taskStatusError.value = ''
    try {
      const { data } = await getProjectBatchStatus(deps.projectId.value)
      if (disposed || expected !== taskSequence) return
      if (data && data.projectId !== deps.projectId.value) throw new Error('Invalid semantic task owner')
      task.value = data ?? null
      if (data && (data.stage === 'RUNNING' || data.stage === 'QUEUED')) {
        startPollingTask(data.taskId)
      }
    } catch {
      if (!disposed && expected === taskSequence) taskStatusError.value = '批量任务状态读取失败；请重试状态查询，当前无法确认任务是否已结束。'
    }
  }

  async function startBatchGenerate(force: boolean) {
    if (batchStarting.value || disposed || deps.canWrite?.() === false) return
    if (taskStatusError.value) { ElMessage.warning('请先重试批量任务状态查询，再提交新任务'); return }
    if (taskRunning.value) { ElMessage.info('当前批量生成任务仍在进行'); return }
    batchStarting.value = true
    try {
      const llm = await semanticLlmParams()
      if (!llm || disposed || deps.canWrite?.() === false || taskRunning.value || taskStatusError.value) return
      const { data } = await startProjectBatchGenerate(deps.projectId.value, force, llm)
      if (disposed) return
      ElMessage.success('已提交批量生成任务')
      startPollingTask(data.taskId)
    } catch (error) {
      if (!disposed) ElMessage.error((error as Error).message || '启动批量生成失败')
    } finally {
      batchStarting.value = false
    }
  }

  function startPollingTask(taskId: string) {
    stopPollingTask()
    const expected = ++taskSequence
    let polling = false
    const poll = async () => {
      if (disposed || expected !== taskSequence || polling) return
      polling = true
      try {
        const { data } = await getProjectBatchStatus(deps.projectId.value, taskId)
        if (disposed || expected !== taskSequence) return
        if (data && (data.projectId !== deps.projectId.value || data.taskId !== taskId)) throw new Error('Invalid semantic task owner')
        if (data == null) {
          task.value = null
          stopPollingTask()
          return
        }
        task.value = data
        if (data.stage === 'DONE' || data.stage === 'FAILED') {
          stopPollingTask()
          await deps.refreshAll()
        }
      } catch {
        if (!disposed && expected === taskSequence) {
          taskStatusError.value = '批量任务状态读取失败；轮询已暂停，请重试状态查询。'
          stopPollingTask()
        }
      } finally {
        polling = false
      }
    }
    void poll()
    pollTimer = setInterval(poll, 2500)
  }

  function stopPollingTask() {
    taskSequence += 1
    if (pollTimer) {
      clearInterval(pollTimer)
      pollTimer = null
    }
  }

  async function resumeSensitiveTaskIfAny() {
    const expected = sensitiveSequence
    sensitiveStatusError.value = ''
    try {
      const { data } = await getSensitiveDataScanStatus(deps.projectId.value)
      if (disposed || expected !== sensitiveSequence) return
      if (data && data.projectId !== deps.projectId.value) throw new Error('Invalid scan task owner')
      sensitiveTask.value = data ?? null
      if (data && (data.stage === 'RUNNING' || data.stage === 'QUEUED')) {
        startPollingSensitiveTask(data.taskId)
      }
    } catch {
      if (!disposed && expected === sensitiveSequence) sensitiveStatusError.value = '敏感扫描状态读取失败；请重试状态查询，当前无法确认扫描是否已结束。'
    }
  }

  async function startSensitiveDataScanFlow() {
    if (sensitiveScanStarting.value || disposed || deps.canWrite?.() === false) return
    if (sensitiveStatusError.value) { ElMessage.warning('请先重试敏感扫描状态查询，再提交新任务'); return }
    if (sensitiveTaskPolling.value) { ElMessage.info('当前敏感扫描仍在进行'); return }
    sensitiveScanStarting.value = true
    try {
      const llm = await semanticLlmParams()
      if (!llm || disposed || deps.canWrite?.() === false || sensitiveTaskPolling.value || sensitiveStatusError.value) return
      const { data } = await startSensitiveDataScan(deps.projectId.value, llm)
      if (disposed) return
      ElMessage.success('已提交敏感数据扫描任务')
      startPollingSensitiveTask(data.taskId)
    } catch {
      // 全局拦截器已提示
    } finally {
      sensitiveScanStarting.value = false
    }
  }

  function startPollingSensitiveTask(taskId: string) {
    stopPollingSensitiveTask()
    const expected = ++sensitiveSequence
    let polling = false
    const poll = async () => {
      if (disposed || expected !== sensitiveSequence || polling) return
      polling = true
      try {
        const { data } = await getSensitiveDataScanStatus(deps.projectId.value, taskId)
        if (disposed || expected !== sensitiveSequence) return
        if (data && (data.projectId !== deps.projectId.value || data.taskId !== taskId)) throw new Error('Invalid scan task owner')
        if (data == null) {
          sensitiveTask.value = null
          stopPollingSensitiveTask()
          return
        }
        sensitiveTask.value = data
        if (data.stage === 'DONE' || data.stage === 'FAILED') {
          stopPollingSensitiveTask()
          if (data.stage === 'DONE') {
            if (data.errorMessage) {
              ElMessage.warning(`敏感扫描完成：${data.errorMessage}`)
            } else {
              ElMessage.success('敏感扫描完成')
            }
          } else {
            ElMessage.error(data.errorMessage || '敏感扫描失败')
          }
          await deps.refreshAll()
        }
      } catch {
        if (!disposed && expected === sensitiveSequence) {
          sensitiveStatusError.value = '敏感扫描状态读取失败；轮询已暂停，请重试状态查询。'
          stopPollingSensitiveTask()
        }
      } finally {
        polling = false
      }
    }
    void poll()
    sensitivePollTimer = setInterval(poll, 2500)
  }

  function stopPollingSensitiveTask() {
    sensitiveSequence += 1
    if (sensitivePollTimer) {
      clearInterval(sensitivePollTimer)
      sensitivePollTimer = null
    }
  }

  function beginSemanticGeneration(type: SemanticSubjectType, id: number) {
    if (disposed || deps.canWrite?.() === false) return null
    const owner = type === 'SOURCE_OBSERVATION' ? toolDocMap.value[id] : undefined
    const key = `${owner?.subjectType || type}:${owner?.subjectId || id}`
    if (semanticGeneratingKeys.value.includes(key)) return null
    semanticGeneratingKeys.value = [...semanticGeneratingKeys.value, key]
    projectDocGenerating.value = semanticGeneratingKeys.value.includes(`PROJECT:${deps.projectId.value}`)
    return key
  }

  function includeResolvedSemanticOwner(doc: SemanticDoc) {
    const key = `${doc.subjectType}:${doc.subjectId}`
    if (semanticGeneratingKeys.value.includes(key)) return null
    semanticGeneratingKeys.value = [...semanticGeneratingKeys.value, key]
    return key
  }

  function finishSemanticGeneration(key: string, resolvedKey?: string | null) {
    semanticGeneratingKeys.value = semanticGeneratingKeys.value.filter(item => item !== key && item !== resolvedKey)
    projectDocGenerating.value = semanticGeneratingKeys.value.includes(`PROJECT:${deps.projectId.value}`)
  }

  async function regenerateTool(row: ProjectToolInfo) {
    const key = beginSemanticGeneration('SOURCE_OBSERVATION', row.scanToolId)
    if (!key) return
    let resolvedKey: string | null = null
    try {
      const llm = await semanticLlmParams()
      if (!llm || disposed || deps.canWrite?.() === false) return
      const { data } = await generateSubjectSemanticDoc(deps.projectId.value, 'SOURCE_OBSERVATION', row.scanToolId, true, llm)
      if (disposed) return
      if (!(data.subjectType === 'SOURCE_OBSERVATION' && data.subjectId === row.scanToolId)
        && !data.sourceObservationIds?.includes(row.scanToolId)) throw new Error('Invalid source semantic owner')
      applySemanticDocsFromList([...semanticDocs.value.filter(doc => doc.subjectType !== data.subjectType || doc.subjectId !== data.subjectId), data])
      resolvedKey = includeResolvedSemanticOwner(data)
      await deps.refreshAll()
      if (disposed) return
      ElMessage.success(`已更新接口 ${row.name}`)
    } catch (error) {
      if (!disposed) ElMessage.error('生成来源说明失败，请重试')
    } finally {
      finishSemanticGeneration(key, resolvedKey)
    }
  }

  function openEditDoc(doc: SemanticDoc | null | undefined) {
    if (!doc || disposed || docEditSaving.value || deps.canWrite?.() === false) return
    editingDoc.value = doc
    docEditContent.value = doc.contentMd || ''
    docEditVisible.value = true
  }

  async function submitDocEdit() {
    if (!editingDoc.value || disposed || docEditSaving.value || deps.canWrite?.() === false) return
    docEditSaving.value = true
    try {
      const doc = editingDoc.value
      const { data } = await editSubjectSemanticDoc(deps.projectId.value, doc.subjectType, doc.subjectId,
        { contentMd: docEditContent.value, expectedUpdatedAt: doc.updatedAt })
      if (disposed) return
      if (data.subjectType !== doc.subjectType || data.subjectId !== doc.subjectId) throw new Error('Invalid semantic owner')
      applySemanticDocsFromList([...semanticDocs.value.filter(item => item.subjectType !== data.subjectType || item.subjectId !== data.subjectId), data])
      docEditVisible.value = false
      ElMessage.success('已保存')
    } catch (error) {
      if (!disposed) ElMessage.error('保存说明失败，请刷新当前修订后重试')
    } finally {
      docEditSaving.value = false
    }
  }

  async function regenerateProject() {
    const key = beginSemanticGeneration('PROJECT', deps.projectId.value)
    if (!key) return
    try {
      const llm = await semanticLlmParams()
      if (!llm || disposed || deps.canWrite?.() === false) return
      const { data } = await generateSubjectSemanticDoc(deps.projectId.value, 'PROJECT', deps.projectId.value, true, llm)
      if (disposed) return
      if (data.subjectType !== 'PROJECT' || data.subjectId !== deps.projectId.value) throw new Error('Invalid project semantic owner')
      applySemanticDocsFromList([...semanticDocs.value.filter(doc => doc.subjectType !== 'PROJECT'), data])
      ElMessage.success('已更新项目说明')
    } catch (error) { if (!disposed) ElMessage.error((error as Error).message || '生成项目说明失败') }
    finally { finishSemanticGeneration(key) }
  }

  async function regenerateSemanticDoc(doc: SemanticDoc) {
    const key = beginSemanticGeneration(doc.subjectType, doc.subjectId)
    if (!key) return
    let resolvedKey: string | null = null
    try {
      const llm = await semanticLlmParams()
      if (!llm || disposed || deps.canWrite?.() === false) return
      const { data } = await generateSubjectSemanticDoc(deps.projectId.value, doc.subjectType, doc.subjectId, true, llm)
      if (disposed) return
      if (data.projectId !== deps.projectId.value || (!(data.subjectType === doc.subjectType && data.subjectId === doc.subjectId)
        && !(doc.subjectType === 'SOURCE_OBSERVATION' && data.sourceObservationIds?.includes(doc.subjectId)))) {
        throw new Error('Invalid semantic owner')
      }
      resolvedKey = includeResolvedSemanticOwner(data)
      await loadSemanticAssetsForRefresh()
      if (disposed) return
      ElMessage.success('已更新业务说明')
    } catch (error) { if (!disposed) ElMessage.error((error as Error).message || '生成业务说明失败') }
    finally { finishSemanticGeneration(key, resolvedKey) }
  }

  onBeforeUnmount(() => { disposed = true; semanticSequence += 1; stopPollingTask(); stopPollingSensitiveTask() })
  return {
    semanticDocs,
    projectDocGenerating,
    semanticGeneratingKeys,
    regenerateProject,
    regenerateSemanticDoc,
    projectDoc,
    toolDocMap,
    batchStarting,
    taskStatusError,
    sensitiveStatusError,
    sensitiveScanStarting,
    sensitiveTask,
    sensitiveTaskPolling,
    task,
    semanticModelInstances,
    semanticModelInstancesLoading,
    semanticModelInstancesLoadError,
    semanticModelInstanceId,
    aiGenerationMode,
    docEditVisible,
    docEditContent,
    docEditSaving,
    taskPercent,
    taskRunning,
    taskFailed,
    taskFailedTitle,
    applySemanticDocsFromList,
    clearSemanticAssets,
    loadSemanticAssetsForRefresh,
    renderMd,
    loadSemanticModelInstances,
    restoreAiGenerationSettings,
    saveAiGenerationSettings,
    resumeBatchTaskIfAny,
    startBatchGenerate,
    stopPollingTask,
    resumeSensitiveTaskIfAny,
    startSensitiveDataScanFlow,
    stopPollingSensitiveTask,
    regenerateTool,
    openEditDoc,
    submitDocEdit,
  }
}
