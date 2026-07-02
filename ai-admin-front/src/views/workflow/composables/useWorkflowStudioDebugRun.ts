import { ElMessage } from 'element-plus'
import type { ComputedRef, Ref } from 'vue'
import {
  cancelWorkflowDebugSession,
  createWorkflowDebugSession,
  debugWorkflowNode,
  debugWorkflowRun,
  listWorkflowVersions,
  submitWorkflowDebugSession,
} from '@/api/workflow'
import { getTraceDetail } from '@/api/trace'
import type { TraceNode } from '@/types/trace'
import { getRecentRunOps, getRunOpsDetail } from '@/api/runops'
import type { RunDetail, RunSummary } from '@/types/runops'
import type { ChatResponse } from '@/types/chat'
import type {
  WorkflowDebugRunResult,
  WorkflowDebugSessionView,
  WorkflowDebugStepResult,
  WorkflowNodeDebugResult,
  WorkflowStudioState,
} from '@/types/workflow'
import type { StudioFieldSchema } from '@/types/studio'
import type { UiFieldPayload } from '@/types/interaction'
import { buildWorkflowDebugDraftPayload } from '@/utils/workflowStudio'
import { runDisplayName } from '@/utils/workflowRunOps'
import { normalizeJson } from '@/views/workflow/composables/workflowStudioJson'
import type { WorkflowNodeTraceState } from '@/views/workflow/composables/useWorkflowStudioCanvasActions'
import type { CanvasSnapshot } from '@/types/studio'

const DEBUG_DRAWER_WIDTH_RATIO = 0.58
const DEBUG_DRAWER_MAX_WIDTH = 960

export interface WorkflowStudioMetaForm {
  name: string
  keySlug: string
  workflowType: string
  description: string
  defaultModelInstanceId: string
}

export interface UseWorkflowStudioDebugRunDeps {
  workflowId: Readonly<Ref<string>>
  studio: Ref<WorkflowStudioState | null>
  workflowMeta: WorkflowStudioMetaForm
  graphSpecJson: Ref<string>
  canvasJson: Ref<string>
  nodes: Ref<unknown[]>
  debugOpen: Ref<boolean>
  propertyPanelCollapsed: Ref<boolean>
  debugLoading: Ref<boolean>
  nodeDebugLoading: Ref<boolean>
  traceReplayLoading: Ref<boolean>
  recentRunsLoading: Ref<boolean>
  debugNodeId: Ref<string>
  debugMessage: Ref<string>
  nodeDebugMessage: Ref<string>
  nodeDebugStateJson: Ref<string>
  debugInputParams: Record<string, unknown>
  debugInteractionParams: Record<string, unknown>
  currentTraceId: Ref<string>
  traceNodes: Ref<TraceNode[]>
  runOpsDetail: Ref<RunDetail | null>
  replayTraceInput: Ref<string>
  selectedRecentTraceId: Ref<string>
  recentRuns: Ref<RunSummary[]>
  selectedDebugStepIndex: Ref<number | null>
  currentDebugNodeId: Ref<string>
  debugPlaybackToken: Ref<number>
  nodeDebugResult: Ref<WorkflowNodeDebugResult | null>
  debugRunResult: Ref<WorkflowDebugRunResult | WorkflowDebugSessionView | null>
  debugSession: Ref<WorkflowDebugSessionView | null>
  debugResult: Ref<ChatResponse | null>
  selectedNodeId: Ref<string | null>
  selectedEdgeId: Ref<string | null>
  selectedNode: ComputedRef<{ id: string } | null>
  nodeDebugStateText: Ref<string>
  debugInputFields: ComputedRef<StudioFieldSchema[]>
  debugWaitingFields: ComputedRef<UiFieldPayload[]>
  resolveAiModelInstanceId: () => string
  syncJsonFromCanvas: () => void
  canvasSnapshot: () => CanvasSnapshot
  refreshWorkflowNodeClasses: () => void
  applyDebugSession: (data: WorkflowDebugSessionView) => void
  forgetDebugSession: () => void
  clearDebugSessionView: () => void
  loadStoredDebugSession: () => Promise<void>
  getViewport: () => { zoom?: number }
  setCenter: (x: number, y: number, options?: { zoom?: number; duration?: number }) => void
  nextTick: (fn?: () => void) => Promise<void>
  findNodePosition: (nodeId: string) => { x: number; y: number } | null
  parseOptionalObject: (value: string, label: string) => Record<string, unknown> | undefined
}

export function debugStepStatus(status?: string): WorkflowNodeTraceState['status'] {
  const normalized = (status || '').trim().toUpperCase()
  if (normalized === 'RUNNING' || normalized === 'EXECUTING') return 'running'
  if (normalized === 'WAITING') return 'waiting'
  if (normalized === 'ERROR' || normalized === 'FAILED' || normalized === 'FAILURE' || normalized === 'FAIL') return 'error'
  if (['SUCCESS', 'OK', 'COMPLETED'].includes(normalized)) return 'success'
  return 'success'
}

export function stringifyDebugPayload(value: unknown) {
  if (value === null || value === undefined || value === '') return '-'
  if (typeof value === 'string') return value
  try {
    return JSON.stringify(value, null, 2)
  } catch {
    return String(value)
  }
}

export function formatElapsed(value?: number) {
  if (value === null || value === undefined) return '-'
  if (value < 1000) return `${value}ms`
  return `${(value / 1000).toFixed(2)}s`
}

function parseDebugJsonLike(value: unknown) {
  if (typeof value !== 'string') return value
  const text = value.trim()
  if (!text) return ''
  try {
    return JSON.parse(text)
  } catch {
    return text
  }
}

function debugUiFieldKey(field: UiFieldPayload) {
  return field.key || field.name || field.targetPath || ''
}

function coerceDebugUiFieldValue(value: unknown, type?: string) {
  if (type === 'number' || type === 'integer') {
    return value === '' || value === undefined || value === null ? undefined : Number(value)
  }
  if (type === 'boolean') {
    return Boolean(value)
  }
  if (type === 'object' || type === 'array') {
    return parseDebugJsonLike(value)
  }
  return value === undefined || value === null ? '' : String(value)
}

function sleep(ms: number) {
  return new Promise((resolve) => window.setTimeout(resolve, ms))
}

export function useWorkflowStudioDebugRun(deps: UseWorkflowStudioDebugRunDeps) {
  function currentStudioStateForDebug(): WorkflowStudioState {
    if (!deps.studio.value) {
      throw new Error('Workflow 未加载')
    }
    if (deps.nodes.value.length) {
      deps.syncJsonFromCanvas()
    }
    return {
      ...deps.studio.value,
      name: deps.workflowMeta.name || deps.studio.value.name,
      keySlug: deps.workflowMeta.keySlug || deps.studio.value.keySlug,
      workflowType: deps.workflowMeta.workflowType || deps.studio.value.workflowType,
      description: deps.workflowMeta.description || deps.studio.value.description,
      defaultModelInstanceId: deps.workflowMeta.defaultModelInstanceId || deps.studio.value.defaultModelInstanceId,
      graphSpecJson: deps.graphSpecJson.value,
      canvasJson: deps.canvasJson.value,
    }
  }

  function buildWorkflowDebugDraftDefinition() {
    return buildWorkflowDebugDraftPayload(
      currentStudioStateForDebug(),
      deps.canvasSnapshot(),
      deps.resolveAiModelInstanceId() || undefined,
    )
  }

  function buildDebugInputParams() {
    const params: Record<string, unknown> = {}
    for (const field of deps.debugInputFields.value) {
      const raw = deps.debugInputParams[field.name]
      if (field.type === 'number' || field.type === 'integer') {
        params[field.name] = raw === '' || raw === undefined || raw === null ? undefined : Number(raw)
      } else if (field.type === 'boolean') {
        params[field.name] = Boolean(raw)
      } else if (field.type === 'object' || field.type === 'array') {
        params[field.name] = parseDebugJsonLike(raw)
      } else {
        params[field.name] = raw === undefined || raw === null ? '' : String(raw)
      }
    }
    if (!deps.debugInputFields.value.length) {
      params.input = deps.debugMessage.value
      params.question = deps.debugMessage.value
    }
    return params
  }

  function debugMessageFromParams(params: Record<string, unknown>) {
    const preferred = params.question ?? params.input ?? params.message
    if (preferred !== undefined && preferred !== null && String(preferred).trim()) {
      return String(preferred)
    }
    const firstValue = Object.values(params).find((value) => value !== undefined && value !== null && String(value).trim())
    return firstValue === undefined ? deps.debugMessage.value : String(firstValue)
  }

  function buildInteractionDebugParams() {
    const params: Record<string, unknown> = {}
    for (const field of deps.debugWaitingFields.value) {
      const key = debugUiFieldKey(field)
      if (!key) continue
      const value = coerceDebugUiFieldValue(deps.debugInteractionParams[key], field.type)
      params[key] = value
      if (field.targetPath) {
        params[field.targetPath] = value
        params[field.targetPath.replace(/\./g, '_')] = value
      }
    }
    return params
  }

  function buildDebugBaseRequest() {
    if (deps.nodes.value.length) {
      deps.syncJsonFromCanvas()
    }
    return {
      workflowId: deps.workflowId.value,
      workflowKeySlug: deps.studio.value?.keySlug || undefined,
      workflowName: deps.studio.value?.name || undefined,
      workflowType: deps.studio.value?.workflowType || undefined,
      projectCode: deps.studio.value?.projectCode || undefined,
      runtimeType: deps.studio.value?.runtimeType || 'LANGGRAPH4J',
      modelInstanceId: deps.resolveAiModelInstanceId() || undefined,
      graphSpecJson: normalizeJson(deps.graphSpecJson.value, 'GraphSpec'),
      canvasJson: deps.canvasJson.value.trim() ? normalizeJson(deps.canvasJson.value, 'Canvas') : undefined,
    }
  }

  function nodeDebugState(
    nodeId: string,
    nodeTraceStates: Record<string, WorkflowNodeTraceState>,
  ): WorkflowNodeTraceState | null {
    const runState = nodeTraceStates[nodeId]
    if (runState) return runState
    if (deps.nodeDebugResult.value?.nodeId === nodeId) {
      return {
        nodeId,
        status: deps.nodeDebugResult.value.success ? 'success' : 'error',
        elapsedMs: deps.nodeDebugResult.value.elapsedMs,
        output: stringifyDebugPayload(deps.nodeDebugResult.value.outputState || deps.nodeDebugResult.value.nodeOutput),
        errorCode: deps.nodeDebugResult.value.errorCode,
      }
    }
    return null
  }

  function nodeRunClass(nodeId: string, nodeTraceStates: Record<string, WorkflowNodeTraceState>) {
    const classes: string[] = []
    const state = nodeDebugState(nodeId, nodeTraceStates)
    if (deps.currentDebugNodeId.value === nodeId) classes.push('run-current')
    if (state) classes.push(`run-${state.status}`)
    return classes
  }

  function nodeRunLabel(nodeId: string, nodeTraceStates: Record<string, WorkflowNodeTraceState>) {
    const state = nodeDebugState(nodeId, nodeTraceStates)
    if (!state) return '未运行'
    const statusMap: Record<WorkflowNodeTraceState['status'], string> = {
      success: '成功',
      error: '异常',
      waiting: '等待',
      running: '运行中',
    }
    const elapsed = state.elapsedMs ? ` · ${formatElapsed(state.elapsedMs)}` : ''
    return `${statusMap[state.status] || state.status}${elapsed}`
  }

  function focusDebugNode(nodeId: string, duration = 360) {
    const nodePosition = deps.findNodePosition(nodeId)
    if (!nodePosition) return
    const viewport = deps.getViewport()
    const zoom = viewport.zoom || 1
    const drawerOffset = deps.debugOpen.value && typeof window !== 'undefined'
      ? (Math.min(DEBUG_DRAWER_MAX_WIDTH, window.innerWidth * DEBUG_DRAWER_WIDTH_RATIO) / 2) / zoom
      : 0
    deps.setCenter(nodePosition.x + 125 + drawerOffset, nodePosition.y + 70, {
      zoom: Math.max(zoom, 0.85),
      duration,
    })
  }

  async function replayDebugSteps(steps: WorkflowDebugStepResult[] = []) {
    const token = deps.debugPlaybackToken.value + 1
    deps.debugPlaybackToken.value = token
    await deps.nextTick()
    for (let index = 0; index < steps.length; index += 1) {
      if (deps.debugPlaybackToken.value !== token) return
      const step = steps[index]
      deps.selectedDebugStepIndex.value = index
      deps.currentDebugNodeId.value = step.nodeId
      deps.selectedNodeId.value = step.nodeId
      deps.selectedEdgeId.value = null
      focusDebugNode(step.nodeId, 360)
      await sleep(420)
    }
    if (deps.debugPlaybackToken.value === token) {
      deps.currentDebugNodeId.value = ''
    }
  }

  function selectDebugStep(index: number) {
    if (deps.selectedDebugStepIndex.value === index) {
      deps.selectedDebugStepIndex.value = null
      return
    }
    deps.selectedDebugStepIndex.value = index
    const step = deps.debugRunResult.value?.steps?.[index]
    if (step?.nodeId) {
      deps.selectedNodeId.value = step.nodeId
      deps.selectedEdgeId.value = null
      deps.currentDebugNodeId.value = step.nodeId
      focusDebugNode(step.nodeId)
    }
    deps.refreshWorkflowNodeClasses()
  }

  function openNodeTrace(nodeId: string) {
    const index = deps.debugRunResult.value?.steps?.findIndex((step) => step.nodeId === nodeId) ?? -1
    if (index >= 0) {
      selectDebugStep(index)
    } else {
      deps.selectedNodeId.value = nodeId
      deps.selectedEdgeId.value = null
      deps.debugNodeId.value = nodeId
      deps.currentDebugNodeId.value = nodeId
      deps.refreshWorkflowNodeClasses()
      void focusDebugNode(nodeId)
    }
    deps.propertyPanelCollapsed.value = false
  }

  async function handleDebug() {
    deps.debugOpen.value = true
    deps.propertyPanelCollapsed.value = false
    for (const field of deps.debugInputFields.value) {
      if (!(field.name in deps.debugInputParams)) {
        deps.debugInputParams[field.name] = field.defaultValue ?? (field.name === 'question' ? deps.debugMessage.value : '')
      }
    }
    if (!deps.recentRuns.value.length) {
      await loadRecentStudioRuns()
    }
    await deps.loadStoredDebugSession()
  }

  async function executeDraftDebug(inputParams: Record<string, unknown>, message: string) {
    deps.debugLoading.value = true
    deps.currentTraceId.value = ''
    deps.traceNodes.value = []
    deps.runOpsDetail.value = null
    deps.debugResult.value = null
    deps.debugRunResult.value = null
    deps.debugSession.value = null
    deps.forgetDebugSession()
    deps.selectedDebugStepIndex.value = null
    deps.currentDebugNodeId.value = ''
    deps.debugPlaybackToken.value += 1
    try {
      const payload = buildWorkflowDebugDraftDefinition()
      const { data } = await createWorkflowDebugSession({
        targetType: 'WORKFLOW_DRAFT',
        draftDefinition: payload,
        message,
        inputParams,
        debugOptions: {},
      })
      deps.applyDebugSession(data)
      deps.currentTraceId.value = data.traceId || ''
      deps.replayTraceInput.value = data.traceId || ''
      deps.selectedRecentTraceId.value = data.traceId || ''
      deps.refreshWorkflowNodeClasses()
      await replayDebugSteps(data.steps || [])
      deps.selectedDebugStepIndex.value = data.steps?.length ? data.steps.length - 1 : null
      if (debugStepStatus(data.status) === 'waiting') {
        ElMessage.warning('当前 Workflow 草稿等待用户补充信息')
      } else {
        ElMessage[data.success ? 'success' : 'error'](data.success ? '当前草稿调试完成' : '当前草稿调试失败')
      }
    } catch (err) {
      ElMessage.error('草稿调试失败：' + (err as Error).message)
    } finally {
      deps.debugLoading.value = false
    }
  }

  async function handleRunDraftDebug() {
    const inputParams = buildDebugInputParams()
    const message = debugMessageFromParams(inputParams)
    if (!message.trim()) {
      ElMessage.warning('请输入测试消息或用户输入字段')
      return
    }
    await executeDraftDebug(inputParams, message)
  }

  async function handleDebugUiSubmit(values: Record<string, unknown>) {
    if (!deps.debugSession.value?.sessionId) {
      ElMessage.warning('当前没有可继续的调试会话')
      return
    }
    deps.debugLoading.value = true
    try {
      const { data } = await submitWorkflowDebugSession(deps.debugSession.value.sessionId, {
        action: 'submit',
        values,
      })
      deps.applyDebugSession(data)
      deps.currentTraceId.value = data.traceId || ''
      deps.replayTraceInput.value = data.traceId || ''
      deps.selectedRecentTraceId.value = data.traceId || ''
      deps.refreshWorkflowNodeClasses()
      await replayDebugSteps(data.steps || [])
      deps.selectedDebugStepIndex.value = data.steps?.length ? data.steps.length - 1 : null
      ElMessage[debugStepStatus(data.status) === 'waiting' ? 'warning' : data.success ? 'success' : 'error'](
        debugStepStatus(data.status) === 'waiting'
          ? '调试会话等待继续输入'
          : data.success ? '调试会话已继续执行' : '调试会话执行失败',
      )
    } catch (err) {
      ElMessage.error('提交交互失败：' + (err as Error).message)
    } finally {
      deps.debugLoading.value = false
    }
  }

  async function handleCancelDebugSession() {
    if (!deps.debugSession.value?.sessionId) return
    deps.debugLoading.value = true
    try {
      const { data } = await cancelWorkflowDebugSession(deps.debugSession.value.sessionId)
      deps.applyDebugSession(data)
      deps.currentDebugNodeId.value = ''
      deps.refreshWorkflowNodeClasses()
      ElMessage.success('调试会话已取消')
    } catch (err) {
      ElMessage.error('取消调试会话失败：' + (err as Error).message)
    } finally {
      deps.debugLoading.value = false
    }
  }

  async function loadTraceArtifacts(traceId: string) {
    const [trace, runOps] = await Promise.allSettled([
      getTraceDetail(traceId),
      getRunOpsDetail(traceId),
    ])
    if (trace.status === 'fulfilled') {
      deps.traceNodes.value = trace.value.data?.nodes ?? []
    } else {
      deps.traceNodes.value = []
    }
    if (runOps.status === 'fulfilled') {
      deps.runOpsDetail.value = runOps.value.data ?? null
    } else {
      deps.runOpsDetail.value = null
    }
    deps.refreshWorkflowNodeClasses()
  }

  async function loadRecentStudioRuns() {
    deps.recentRunsLoading.value = true
    try {
      const { data } = await getRecentRunOps({ days: 7, limit: 100 })
      deps.recentRuns.value = data ?? []
    } catch {
      ElMessage.error('加载最近运行失败')
    } finally {
      deps.recentRunsLoading.value = false
    }
  }

  async function handleLoadTraceReplay(traceId = deps.replayTraceInput.value) {
    const value = String(traceId || '').trim()
    if (!value) {
      ElMessage.warning('请输入 traceId')
      return
    }
    deps.traceReplayLoading.value = true
    deps.currentTraceId.value = value
    deps.replayTraceInput.value = value
    deps.selectedRecentTraceId.value = value
    deps.debugResult.value = null
    deps.debugRunResult.value = null
    deps.selectedDebugStepIndex.value = null
    deps.currentDebugNodeId.value = ''
    deps.debugPlaybackToken.value += 1
    try {
      await loadTraceArtifacts(value)
      if (!deps.runOpsDetail.value && !deps.traceNodes.value.length) {
        ElMessage.warning('未读取到这次运行的链路数据')
      } else {
        ElMessage.success('已回放到画布')
      }
    } catch (err) {
      ElMessage.error('回放失败：' + (err as Error).message)
    } finally {
      deps.traceReplayLoading.value = false
    }
  }

  function handleRecentTraceChange(value: string | number | boolean | undefined) {
    const traceId = String(value || '').trim()
    if (traceId) {
      void handleLoadTraceReplay(traceId)
    }
  }

  function clearTraceReplay() {
    deps.currentTraceId.value = ''
    deps.replayTraceInput.value = ''
    deps.selectedRecentTraceId.value = ''
    deps.traceNodes.value = []
    deps.runOpsDetail.value = null
    deps.debugResult.value = null
    deps.selectedDebugStepIndex.value = null
    deps.currentDebugNodeId.value = ''
    deps.debugPlaybackToken.value += 1
    deps.refreshWorkflowNodeClasses()
  }

  function recentRunLabel(run: RunSummary) {
    const status = run.status || '-'
    const name = runDisplayName(run)
    const time = run.startedAt ? run.startedAt.replace('T', ' ').slice(0, 19) : '-'
    return `${status} · ${name} · ${time} · ${run.traceId}`
  }

  async function handleRunPublishedDebug() {
    const inputParams = buildDebugInputParams()
    const message = debugMessageFromParams(inputParams)
    if (!message.trim()) {
      ElMessage.warning('请输入测试消息')
      return
    }
    deps.debugLoading.value = true
    deps.currentTraceId.value = ''
    deps.traceNodes.value = []
    deps.runOpsDetail.value = null
    deps.debugRunResult.value = null
    deps.debugSession.value = null
    deps.selectedDebugStepIndex.value = null
    deps.currentDebugNodeId.value = ''
    deps.debugPlaybackToken.value += 1
    try {
      const { data: versions } = await listWorkflowVersions(deps.workflowId.value)
      const active = versions.find((item) => (item.status || '').toUpperCase() === 'ACTIVE')
      if (!active?.graphSpecSnapshotJson) {
        ElMessage.warning('没有可验证的 ACTIVE 发布版本')
        return
      }
      const { data } = await debugWorkflowRun({
        ...buildDebugBaseRequest(),
        graphSpecJson: normalizeJson(active.graphSpecSnapshotJson, 'GraphSpec'),
        canvasJson: active.canvasSnapshotJson ? normalizeJson(active.canvasSnapshotJson, 'Canvas') : undefined,
        message,
        inputParams,
        debugOptions: {
          publishedVersion: active.version,
          publishedVersionId: active.id,
        },
      })
      deps.debugResult.value = {
        answer: data.answer || data.errorMessage || '',
        metadata: {
          traceId: data.traceId,
          version: active.version,
          workflowKeySlug: deps.studio.value?.keySlug,
          workflowId: deps.workflowId.value,
          runtimeType: deps.studio.value?.runtimeType,
          projectCode: deps.studio.value?.projectCode,
        },
      } as ChatResponse
      if (data.traceId) {
        deps.currentTraceId.value = data.traceId
        deps.replayTraceInput.value = data.traceId
        deps.selectedRecentTraceId.value = data.traceId
        try {
          await loadTraceArtifacts(data.traceId)
        } catch {
          // trace 可能尚未写入
        }
      }
      ElMessage[data.success ? 'success' : 'error'](data.success ? '发布版本验证完成' : '发布版本验证失败')
    } catch (err) {
      ElMessage.error('发布版本验证失败：' + (err as Error).message)
    } finally {
      deps.debugLoading.value = false
    }
  }

  async function runNodeDebug() {
    if (!deps.debugNodeId.value.trim()) {
      ElMessage.warning('请输入节点 ID')
      return
    }
    deps.nodeDebugLoading.value = true
    try {
      const { data } = await debugWorkflowNode({
        ...buildDebugBaseRequest(),
        nodeId: deps.debugNodeId.value.trim(),
        message: deps.debugMessage.value || undefined,
        state: deps.parseOptionalObject(deps.nodeDebugStateJson.value, 'Node state'),
      })
      deps.nodeDebugResult.value = data
      deps.debugRunResult.value = null
      deps.debugSession.value = null
      deps.selectedDebugStepIndex.value = null
      deps.currentDebugNodeId.value = data.nodeId || deps.debugNodeId.value.trim()
      deps.selectedNodeId.value = deps.currentDebugNodeId.value
      deps.selectedEdgeId.value = null
      deps.refreshWorkflowNodeClasses()
      ElMessage.success(data.success ? 'Node debug completed' : 'Node debug failed')
    } catch (err) {
      ElMessage.error((err as Error).message)
    } finally {
      deps.nodeDebugLoading.value = false
    }
  }

  async function handleRunNodeDebug() {
    if (!deps.selectedNode.value) return
    deps.debugNodeId.value = deps.selectedNode.value.id
    if (deps.nodeDebugMessage.value.trim()) {
      deps.debugMessage.value = deps.nodeDebugMessage.value
    }
    deps.nodeDebugStateJson.value = deps.nodeDebugStateText.value
    await runNodeDebug()
  }

  function clearWorkflowDebugView() {
    deps.clearDebugSessionView()
  }

  function isDebugStepRunning(step: WorkflowDebugStepResult) {
    if (debugStepStatus(step.status) === 'running') return true
    const sessionCurrentNodeId = deps.debugSession.value?.currentNodeId || deps.debugRunResult.value?.currentNodeId || ''
    return debugStepStatus(deps.debugSession.value?.status || deps.debugRunResult.value?.status) === 'running'
      && !!sessionCurrentNodeId
      && sessionCurrentNodeId === step.nodeId
  }

  return {
    debugStepStatus,
    stringifyDebugPayload,
    formatElapsed,
    buildDebugBaseRequest,
    buildDebugInputParams,
    buildInteractionDebugParams,
    currentStudioStateForDebug,
    buildWorkflowDebugDraftDefinition,
    nodeDebugState,
    nodeRunClass,
    nodeRunLabel,
    focusDebugNode,
    replayDebugSteps,
    selectDebugStep,
    openNodeTrace,
    handleDebug,
    handleRunDraftDebug,
    executeDraftDebug,
    handleDebugUiSubmit,
    handleCancelDebugSession,
    loadTraceArtifacts,
    loadRecentStudioRuns,
    handleLoadTraceReplay,
    handleRecentTraceChange,
    clearTraceReplay,
    recentRunLabel,
    handleRunPublishedDebug,
    runNodeDebug,
    handleRunNodeDebug,
    clearWorkflowDebugView,
    isDebugStepRunning,
  }
}
