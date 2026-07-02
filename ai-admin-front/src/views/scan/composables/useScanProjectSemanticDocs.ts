import { computed, ref, watch, type ComputedRef, type Ref } from 'vue'
import { ElMessage } from 'element-plus'
import { marked } from 'marked'
import type { ModelInstance } from '@/types/model'
import type { ProjectToolInfo, ScanProject, SensitiveScanTask } from '@/types/scanProject'
import type { ScanModule, SemanticDoc, SemanticTask } from '@/types/semanticDoc'
import { getModelInstances } from '@/api/model'
import {
  getSensitiveDataScanStatus,
  startSensitiveDataScan,
} from '@/api/scanProject'
import {
  editSemanticDoc,
  generateModuleDoc,
  generateScanToolDoc,
  getProjectBatchStatus,
  listProjectSemanticDocs,
  listScanModules,
  mergeScanModules,
  renameScanModule,
  startProjectBatchGenerate,
  type SemanticLlmParams,
} from '@/api/semanticDoc'
import { scanSensitiveTypeLabel } from '@/utils/scanProjectToolExport'

type AiGenerationMode = 'missing' | 'force'

export interface UseScanProjectSemanticDocsDeps {
  projectId: ComputedRef<number>
  project: Ref<ScanProject | null>
  tools: Ref<ProjectToolInfo[]>
  aiSettingsDrawerVisible: Ref<boolean>
  modelGenerateDrawerVisible: Ref<boolean>
  opsDrawerVisible: Ref<boolean>
  refreshAll: () => Promise<void>
}

export function useScanProjectSemanticDocs(deps: UseScanProjectSemanticDocsDeps) {
  const modules = ref<ScanModule[]>([])
  const projectDoc = ref<SemanticDoc | null>(null)
  const moduleDocMap = ref<Record<number, SemanticDoc>>({})
  const toolDocMap = ref<Record<number, SemanticDoc>>({})
  const selectedModuleIds = ref<number[]>([])

  const batchStarting = ref(false)
  const sensitiveScanStarting = ref(false)
  const sensitiveTask = ref<SensitiveScanTask | null>(null)
  let sensitivePollTimer: ReturnType<typeof setInterval> | null = null

  const sensitiveTaskPolling = computed(
    () => sensitiveTask.value?.stage === 'RUNNING' || sensitiveTask.value?.stage === 'QUEUED',
  )

  const task = ref<SemanticTask | null>(null)
  let pollTimer: ReturnType<typeof setInterval> | null = null

  const semanticModelInstances = ref<ModelInstance[]>([])
  const semanticModelInstanceId = ref('')
  const aiGenerationMode = ref<AiGenerationMode>('missing')

  const docEditVisible = ref(false)
  const docEditContent = ref('')
  const docEditingId = ref<number | null>(null)
  const docEditSaving = ref(false)

  const mergeDialogVisible = ref(false)
  const mergeSelectedModules = ref<ScanModule[]>([])
  const mergeSourceModules = ref<ScanModule[]>([])
  const mergeTargetId = ref<number | null>(null)
  const mergeDisplayName = ref('')
  const mergeSaving = ref(false)

  const renameDialogVisible = ref(false)
  const renameTarget = ref<ScanModule | null>(null)
  const renameValue = ref('')
  const renameSaving = ref(false)

  const taskPercent = computed(() => {
    if (!task.value || task.value.totalSteps <= 0) return 0
    return Math.min(100, Math.round((task.value.completedSteps / task.value.totalSteps) * 100))
  })
  const taskRunning = computed(() => task.value?.stage === 'QUEUED' || task.value?.stage === 'RUNNING')
  const taskFailed = computed(() => task.value?.stage === 'FAILED')
  const taskLabel = computed(() =>
    task.value ? `${task.value.stage} · ${task.value.completedSteps}/${task.value.totalSteps}` : '',
  )
  const taskTotalTokens = computed(() => task.value?.totalTokens ?? 0)
  const taskStageTagType = computed(() => (task.value ? taskTagType(task.value.stage) : 'info'))
  const taskFailedTitle = computed(() => `批量生成失败：${task.value?.errorMessage || '未知错误'}`)

  function applySemanticDocsFromList(docs: SemanticDoc[]) {
    projectDoc.value = docs.find((doc) => doc.level === 'project') || null
    const moduleMap: Record<number, SemanticDoc> = {}
    docs.filter((doc) => doc.level === 'module' && doc.moduleId != null).forEach((doc) => {
      moduleMap[doc.moduleId as number] = doc
    })
    moduleDocMap.value = moduleMap

    const toolMap: Record<number, SemanticDoc> = {}
    for (const doc of docs.filter((item) => item.level === 'scan_tool')) {
      if (doc.toolId != null) toolMap[doc.toolId] = doc
    }
    toolDocMap.value = toolMap
  }

  async function loadSemanticAssetsForRefresh() {
    const moduleResponse = await listScanModules(deps.projectId.value)
    modules.value = Array.isArray(moduleResponse.data) ? moduleResponse.data : []
    try {
      const { data } = await listProjectSemanticDocs(deps.projectId.value)
      applySemanticDocsFromList(Array.isArray(data) ? data : [])
    } catch {
      applySemanticDocsFromList([])
    }
  }

  function clearSemanticAssets() {
    modules.value = []
    applySemanticDocsFromList([])
  }

  function renderMd(content: string | null | undefined): string {
    if (!content) return ''
    return marked.parse(content, { async: false }) as string
  }

  function sensitiveCellTooltip(row: ProjectToolInfo): string {
    const sensitiveData = row.sensitiveData
    if (!sensitiveData) return ''
    const parts: string[] = []
    if (sensitiveData.types?.length) {
      parts.push(`类型: ${sensitiveData.types.map((type) => scanSensitiveTypeLabel(type)).join('、')}`)
    }
    if (sensitiveData.summary) parts.push(sensitiveData.summary)
    if (sensitiveData.scannedAt) parts.push(`扫描时间: ${sensitiveData.scannedAt}`)
    if (sensitiveData.modelName) parts.push(`模型: ${sensitiveData.modelName}`)
    return parts.join('\n')
  }

  function toolDocSummary(tool: ProjectToolInfo): string {
    const doc = toolDocMap.value[tool.scanToolId]
    if (!doc || !doc.contentMd) return tool.description || '（无 AI 描述）'
    const marker = '## 一句话语义'
    const index = doc.contentMd.indexOf(marker)
    if (index < 0) return doc.contentMd.slice(0, 120)
    const rest = doc.contentMd.slice(index + marker.length).trim()
    const next = rest.indexOf('\n##')
    const section = next > 0 ? rest.slice(0, next) : rest
    return section.trim().slice(0, 140)
  }

  function taskTagType(stage: SemanticTask['stage']) {
    switch (stage) {
      case 'DONE':
        return 'success'
      case 'FAILED':
        return 'danger'
      case 'RUNNING':
        return 'warning'
      default:
        return 'info'
    }
  }

  function semanticLlmParams(): SemanticLlmParams {
    const id = semanticModelInstanceId.value?.trim()
    if (!id) {
      ElMessage.warning('请先选择 LLM 模型实例')
      throw new Error('modelInstanceId is required')
    }
    return {
      modelInstanceId: id,
    }
  }

  async function loadSemanticModelInstances() {
    try {
      const { data } = await getModelInstances({ modelType: 'LLM' })
      const list = (data?.data ?? []) as ModelInstance[]
      semanticModelInstances.value = list
      if (list.length === 0) {
        semanticModelInstanceId.value = ''
        return
      }
      if (semanticModelInstanceId.value && !list.some((item) => item.id === semanticModelInstanceId.value)) {
        semanticModelInstanceId.value = ''
      }
    } catch {
      semanticModelInstances.value = []
    }
  }

  function aiSettingsStorageKey() {
    return `reachai:scan-project:${deps.projectId.value}:ai-settings`
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
    deps.aiSettingsDrawerVisible.value = false
    deps.modelGenerateDrawerVisible.value = false
    deps.opsDrawerVisible.value = false
    ElMessage.success('AI 理解设置已保存')
  }

  async function reloadAiTab() {
    try {
      const [moduleResponse, docResponse] = await Promise.all([
        listScanModules(deps.projectId.value),
        listProjectSemanticDocs(deps.projectId.value),
      ])
      modules.value = Array.isArray(moduleResponse.data) ? moduleResponse.data : []
      applySemanticDocsFromList(Array.isArray(docResponse.data) ? docResponse.data : [])
    } catch (error) {
      ElMessage.error((error as Error).message || '加载 AI 理解数据失败')
    }
  }

  async function reloadSemanticUi() {
    await reloadAiTab()
  }

  async function resumeBatchTaskIfAny() {
    try {
      const { data } = await getProjectBatchStatus(deps.projectId.value)
      task.value = data ?? null
      if (data && (data.stage === 'RUNNING' || data.stage === 'QUEUED')) {
        startPollingTask(data.taskId)
      }
    } catch {
      task.value = null
    }
  }

  async function startBatchGenerate(force: boolean) {
    batchStarting.value = true
    try {
      const { data } = await startProjectBatchGenerate(deps.projectId.value, force, semanticLlmParams())
      ElMessage.success('已提交批量生成任务')
      startPollingTask(data.taskId)
    } catch (error) {
      ElMessage.error((error as Error).message || '启动批量生成失败')
    } finally {
      batchStarting.value = false
    }
  }

  function startPollingTask(taskId: string) {
    stopPollingTask()
    const poll = async () => {
      try {
        const { data } = await getProjectBatchStatus(deps.projectId.value, taskId)
        if (data == null) {
          task.value = null
          stopPollingTask()
          return
        }
        task.value = data
        if (data.stage === 'DONE' || data.stage === 'FAILED') {
          stopPollingTask()
          await reloadAiTab()
          await deps.refreshAll()
        }
      } catch {
        stopPollingTask()
      }
    }
    void poll()
    pollTimer = setInterval(poll, 2500)
  }

  function stopPollingTask() {
    if (pollTimer) {
      clearInterval(pollTimer)
      pollTimer = null
    }
  }

  async function resumeSensitiveTaskIfAny() {
    try {
      const { data } = await getSensitiveDataScanStatus(deps.projectId.value)
      sensitiveTask.value = data ?? null
      if (data && (data.stage === 'RUNNING' || data.stage === 'QUEUED')) {
        startPollingSensitiveTask(data.taskId)
      }
    } catch {
      sensitiveTask.value = null
    }
  }

  async function startSensitiveDataScanFlow() {
    sensitiveScanStarting.value = true
    try {
      const { data } = await startSensitiveDataScan(deps.projectId.value, semanticLlmParams())
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
    const poll = async () => {
      try {
        const { data } = await getSensitiveDataScanStatus(deps.projectId.value, taskId)
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
        stopPollingSensitiveTask()
      }
    }
    void poll()
    sensitivePollTimer = setInterval(poll, 2500)
  }

  function stopPollingSensitiveTask() {
    if (sensitivePollTimer) {
      clearInterval(sensitivePollTimer)
      sensitivePollTimer = null
    }
  }

  async function regenerateModule(row: ScanModule) {
    try {
      const { data } = await generateModuleDoc(row.id, true, semanticLlmParams())
      moduleDocMap.value = { ...moduleDocMap.value, [row.id]: data }
      ElMessage.success(`已更新模块 ${row.displayName}`)
    } catch (error) {
      ElMessage.error((error as Error).message || '生成失败')
    }
  }

  async function regenerateTool(row: ProjectToolInfo) {
    try {
      const { data } = await generateScanToolDoc(deps.projectId.value, row.scanToolId, true, semanticLlmParams())
      toolDocMap.value = { ...toolDocMap.value, [row.scanToolId]: data }
      ElMessage.success(`已更新接口 ${row.name}`)
      await deps.refreshAll()
    } catch (error) {
      ElMessage.error((error as Error).message || '生成失败')
    }
  }

  function openEditDoc(doc: SemanticDoc | null | undefined) {
    if (!doc) return
    docEditingId.value = doc.id
    docEditContent.value = doc.contentMd || ''
    docEditVisible.value = true
  }

  async function submitDocEdit() {
    if (!docEditingId.value) return
    docEditSaving.value = true
    try {
      const { data } = await editSemanticDoc(docEditingId.value, { contentMd: docEditContent.value })
      if (data.level === 'project') {
        projectDoc.value = data
      } else if (data.level === 'module' && data.moduleId != null) {
        moduleDocMap.value = { ...moduleDocMap.value, [data.moduleId]: data }
      } else if (data.level === 'scan_tool' && data.toolId != null) {
        toolDocMap.value = { ...toolDocMap.value, [data.toolId]: data }
      } else if (data.level === 'tool' && data.toolId != null) {
        const entry = Object.entries(toolDocMap.value).find(([, value]) => value.id === data.id)
        if (entry) {
          const key = Number(entry[0])
          if (!Number.isNaN(key)) toolDocMap.value = { ...toolDocMap.value, [key]: data }
        }
      }
      docEditVisible.value = false
      ElMessage.success('已保存')
    } catch (error) {
      ElMessage.error((error as Error).message || '保存失败')
    } finally {
      docEditSaving.value = false
    }
  }

  function onModuleSelectionChange(rows: ScanModule[]) {
    selectedModuleIds.value = rows.map((row) => row.id)
  }

  function openMergeDialog() {
    const selected = modules.value.filter((module) => selectedModuleIds.value.includes(module.id))
    if (selected.length < 2) {
      ElMessage.warning('至少选中 2 个模块才能合并')
      return
    }
    mergeSelectedModules.value = selected
    mergeTargetId.value = selected[0].id
    mergeDisplayName.value = ''
    mergeSourceModules.value = selected
    mergeDialogVisible.value = true
  }

  watch(mergeTargetId, (target) => {
    mergeSourceModules.value = mergeSelectedModules.value.filter((module) => module.id !== target)
  })

  async function submitMerge() {
    if (!mergeTargetId.value) return
    const sourceIds = mergeSourceModules.value.map((module) => module.id)
    mergeSaving.value = true
    try {
      await mergeScanModules({
        targetId: mergeTargetId.value,
        sourceIds,
        displayName: mergeDisplayName.value || null,
      })
      mergeDialogVisible.value = false
      ElMessage.success('合并成功')
      await reloadAiTab()
    } catch (error) {
      ElMessage.error((error as Error).message || '合并失败')
    } finally {
      mergeSaving.value = false
    }
  }

  function openRenameDialog(row: ScanModule) {
    renameTarget.value = row
    renameValue.value = row.displayName
    renameDialogVisible.value = true
  }

  async function submitRename() {
    if (!renameTarget.value) return
    renameSaving.value = true
    try {
      await renameScanModule(renameTarget.value.id, renameValue.value.trim())
      renameDialogVisible.value = false
      ElMessage.success('已重命名')
      await reloadAiTab()
    } catch (error) {
      ElMessage.error((error as Error).message || '重命名失败')
    } finally {
      renameSaving.value = false
    }
  }

  return {
    modules,
    projectDoc,
    moduleDocMap,
    toolDocMap,
    selectedModuleIds,
    batchStarting,
    sensitiveScanStarting,
    sensitiveTask,
    sensitiveTaskPolling,
    task,
    semanticModelInstances,
    semanticModelInstanceId,
    aiGenerationMode,
    docEditVisible,
    docEditContent,
    docEditSaving,
    mergeDialogVisible,
    mergeSelectedModules,
    mergeSourceModules,
    mergeTargetId,
    mergeDisplayName,
    mergeSaving,
    renameDialogVisible,
    renameValue,
    renameSaving,
    taskPercent,
    taskRunning,
    taskFailed,
    taskLabel,
    taskTotalTokens,
    taskStageTagType,
    taskFailedTitle,
    applySemanticDocsFromList,
    clearSemanticAssets,
    loadSemanticAssetsForRefresh,
    renderMd,
    sensitiveCellTooltip,
    toolDocSummary,
    loadSemanticModelInstances,
    restoreAiGenerationSettings,
    saveAiGenerationSettings,
    reloadAiTab,
    reloadSemanticUi,
    resumeBatchTaskIfAny,
    startBatchGenerate,
    stopPollingTask,
    resumeSensitiveTaskIfAny,
    startSensitiveDataScanFlow,
    stopPollingSensitiveTask,
    regenerateModule,
    regenerateTool,
    openEditDoc,
    submitDocEdit,
    onModuleSelectionChange,
    openMergeDialog,
    submitMerge,
    openRenameDialog,
    submitRename,
  }
}
