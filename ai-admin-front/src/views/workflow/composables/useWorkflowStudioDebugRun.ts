import { computed, onUnmounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import type { ComputedRef, Ref } from 'vue'
import {
  debugWorkflowNode,
  debugWorkflowRun,
  listWorkflowVersions,
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
  WorkflowWorkingCopyState,
} from '@/types/workflow'
import type { StudioFieldSchema } from '@/types/studio'
import { buildWorkflowDebugWorkingCopyPayload } from '@/utils/workflowStudio'
import { runDisplayName } from '@/utils/workflowRunOps'
import { normalizeJson } from '@/views/workflow/composables/workflowStudioJson'
import { debugStepStatus, stringifyDebugPayload, formatElapsed, type WorkflowNodeTraceState } from './workflowStudioTrace'
import { isWorkflowSessionInFlight } from '@/conversation/core/adapters/adaptWorkflowSessionView'
import type { CanvasSnapshot } from '@/types/studio'
import {
  buildWorkflowInitialFormRequest,
  createConversationController,
  createEmptySnapshot,
  createWorkflowWorkingCopyTransport,
  WORKFLOW_INITIAL_INPUT_ID,
  type ConversationEventEnvelope,
  type ConversationSnapshot,
} from '@/conversation'

const DEBUG_DRAWER_WIDTH_RATIO = 0.58
const DEBUG_DRAWER_MAX_WIDTH = 960
const WORKFLOW_CONVERSATION_FIELD_NAMES = new Set(['message', 'input', 'question', 'query', 'prompt'])

export function resolveWorkflowInitialChatField(fields: StudioFieldSchema[]): StudioFieldSchema | null {
  if (fields.length !== 1) return null
  const field = fields[0]
  if (!field || field.type !== 'string') return null
  return WORKFLOW_CONVERSATION_FIELD_NAMES.has(field.name.trim().toLowerCase()) ? field : null
}

export interface WorkflowStudioMetaForm {
  name: string
  keySlug: string
  workflowKind: string
  description: string
  defaultModelInstanceId: string
}

export interface UseWorkflowStudioDebugRunDeps {
  debugSessionScope: Readonly<Ref<string>>
  workflowId: Readonly<Ref<string>>
  studio: Ref<WorkflowWorkingCopyState | null>
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
  resolveAiModelInstanceId: () => string
  syncJsonFromCanvas: () => void
  canvasSnapshot: () => CanvasSnapshot
  refreshWorkflowNodeClasses: () => void
  applyDebugSession: (data: WorkflowDebugSessionView, scope: string) => void
  forgetDebugSession: () => void
  debugCreationKey?: Readonly<Ref<string | undefined>>
  setDebugCreationKey?: (key: string | undefined) => void
  loadStoredDebugSession: () => Promise<void>
  getViewport: () => { zoom?: number }
  setCenter: (x: number, y: number, options?: { zoom?: number; duration?: number }) => void
  nextTick: (fn?: () => void) => Promise<void>
  findNodePosition: (nodeId: string) => { x: number; y: number } | null
  parseOptionalObject: (value: string, label: string) => Record<string, unknown> | undefined
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

function sleep(ms: number) {
  return new Promise((resolve) => window.setTimeout(resolve, ms))
}

export function useWorkflowStudioDebugRun(deps: UseWorkflowStudioDebugRunDeps) {
  const debugConversationSnapshot = ref<ConversationSnapshot>(createEmptySnapshot())
  let sessionViewSyncToken = 0
  let traceReplayRequestSequence = 0
  let debugController: ReturnType<typeof createConversationController> | null = null
  let controllerGeneration = 0

  function currentDebugScope() {
    const scope = deps.debugSessionScope.value
    const generation = controllerGeneration
    return () => Boolean(scope) && deps.debugSessionScope.value === scope && generation === controllerGeneration
  }

  async function syncShellFromWorkflowSessionView(view: WorkflowDebugSessionView, scope: string) {
    const token = sessionViewSyncToken + 1
    sessionViewSyncToken = token
    deps.applyDebugSession(view, scope)
    deps.currentTraceId.value = view.traceId || ''
    deps.replayTraceInput.value = view.traceId || ''
    deps.selectedRecentTraceId.value = view.traceId || ''
    deps.refreshWorkflowNodeClasses()
    await replayDebugSteps(view.steps || [])
    if (sessionViewSyncToken !== token) return
    deps.selectedDebugStepIndex.value = view.steps?.length ? view.steps.length - 1 : null
  }

  function handleWorkflowDebugEvent(event: ConversationEventEnvelope) {
    if (event.type === 'debug.trace.available') {
      const traceId = String((event.data as { traceId?: string }).traceId || event.traceId || '')
      if (traceId) {
        deps.currentTraceId.value = traceId
        deps.replayTraceInput.value = traceId
        deps.selectedRecentTraceId.value = traceId
      }
      return
    }
    if (!event.type.startsWith('debug.workflow.node.')) return
    const data = event.data as { nodeId?: string; index?: number }
    if (data.nodeId) {
      deps.currentDebugNodeId.value = data.nodeId
      deps.selectedNodeId.value = data.nodeId
      deps.selectedEdgeId.value = null
      focusDebugNode(data.nodeId, 280)
    }
    if (typeof data.index === 'number') {
      deps.selectedDebugStepIndex.value = data.index
    }
    deps.refreshWorkflowNodeClasses()
  }

  function createDebugController() {
    const scope = deps.debugSessionScope.value
    const generation = controllerGeneration
    const isCurrent = () => Boolean(scope) && deps.debugSessionScope.value === scope && generation === controllerGeneration
    const transport = createWorkflowWorkingCopyTransport({
      tryStream: true,
      getCreationKey: () => isCurrent() ? deps.debugCreationKey?.value : undefined,
      setCreationKey: (key) => { if (isCurrent()) deps.setDebugCreationKey?.(key) },
      getSessionId: () => isCurrent() ? deps.debugSession.value?.sessionId : undefined,
      setSessionId: (sessionId) => {
        if (!isCurrent()) return
        if (!sessionId) {
          deps.debugSession.value = null
          return
        }
        if (deps.debugSession.value?.sessionId === sessionId) return
        deps.debugSession.value = {
          ...(deps.debugSession.value || {}),
          sessionId,
        } as WorkflowDebugSessionView
      },
      getCreateRequest: () => ({
        targetType: 'WORKFLOW_WORKING_COPY',
        workingCopyDefinition: buildWorkflowDebugWorkingCopyDefinition(),
        debugOptions: {},
      }),
      onSessionView: (view) => {
        if (!isCurrent()) return
        void syncShellFromWorkflowSessionView(view, scope).then(() => {
          if (!isCurrent()) return
          if (!deps.debugLoading.value) return
          if (isWorkflowSessionInFlight(view.status)) return
          if (debugStepStatus(view.status) === 'waiting') {
            ElMessage.warning('当前 Workflow 工作副本等待用户补充信息')
          } else {
            ElMessage[view.success ? 'success' : 'error'](
              view.success ? '当前工作副本调试完成' : '当前工作副本调试失败',
            )
          }
          deps.debugLoading.value = false
        }).catch((err) => {
          if (!isCurrent()) return
          if (!deps.debugLoading.value) return
          ElMessage.error('同步调试会话失败：' + (err as Error).message)
          deps.debugLoading.value = false
        })
      },
    })

    return createConversationController({
      transport,
      initialSessionId: deps.debugSession.value?.sessionId,
      onDebugEvent: (event) => { if (isCurrent()) handleWorkflowDebugEvent(event) },
      onChange(state) {
        if (isCurrent()) debugConversationSnapshot.value = state
      },
    })
  }

  function ensureDebugController() {
    if (!deps.debugSessionScope.value) throw new Error('请先登录后再调试 Workflow')
    if (!debugController) {
      debugController = createDebugController()
    }
    return debugController
  }

  function disposeDebugConversation() {
    controllerGeneration += 1
    debugController?.dispose()
    debugController = null
  }

  async function restoreDebugConversation() {
    const controller = ensureDebugController()
    const snapshot = await controller.restore()
    if (controller !== debugController) return
    if (snapshot?.sessionId && deps.debugSession.value) {
      debugConversationSnapshot.value = snapshot
    }
  }

  const isDebugConversationBusy = computed(() => {
    const status = debugConversationSnapshot.value.turnStatus
    return status === 'sending' || status === 'streaming' || deps.debugLoading.value
  })

  const workflowInitialUiRequest = computed(() => {
    if (debugConversationSnapshot.value.turnStatus === 'waiting') return null
    if (debugConversationSnapshot.value.sessionId) return null
    if (!deps.debugInputFields.value.length) return null
    return buildWorkflowInitialFormRequest(
      deps.debugInputFields.value.map((field) => ({
        key: field.name,
        label: field.name,
        type: field.type,
        required: field.required,
        placeholder: field.description,
        options: field.options,
      })),
      deps.debugInputParams,
    )
  })

  const workflowInitialChatField = computed(() => {
    if (!workflowInitialUiRequest.value) return null
    return resolveWorkflowInitialChatField(deps.debugInputFields.value)
  })

  onUnmounted(() => {
    disposeDebugConversation()
  })

  watch(deps.debugSessionScope, () => {
    sessionViewSyncToken += 1
    traceReplayRequestSequence += 1
    deps.debugPlaybackToken.value += 1
    disposeDebugConversation()
    debugConversationSnapshot.value = createEmptySnapshot()
    deps.debugLoading.value = false
    deps.traceReplayLoading.value = false
    deps.traceNodes.value = []
    deps.runOpsDetail.value = null
    deps.recentRuns.value = []
    deps.nodeDebugResult.value = null
    deps.debugResult.value = null
    deps.nodeDebugLoading.value = false
    deps.recentRunsLoading.value = false
    deps.debugMessage.value = ''
    deps.nodeDebugMessage.value = ''
    deps.nodeDebugStateJson.value = '{}'
    Object.keys(deps.debugInputParams).forEach(key => { delete deps.debugInputParams[key] })
  }, { flush: 'sync' })

  function currentStudioStateForDebug(): WorkflowWorkingCopyState {
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
      workflowKind: deps.workflowMeta.workflowKind || deps.studio.value.workflowKind,
      description: deps.workflowMeta.description || deps.studio.value.description,
      defaultModelInstanceId: deps.workflowMeta.defaultModelInstanceId || deps.studio.value.defaultModelInstanceId,
      graphSpecJson: deps.graphSpecJson.value,
      canvasJson: deps.canvasJson.value,
    }
  }

  function buildWorkflowDebugWorkingCopyDefinition() {
    return buildWorkflowDebugWorkingCopyPayload(
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

  function buildDebugBaseRequest() {
    if (deps.nodes.value.length) {
      deps.syncJsonFromCanvas()
    }
    return {
      workflowId: deps.workflowId.value,
      workflowKeySlug: deps.studio.value?.keySlug || undefined,
      workflowName: deps.studio.value?.name || undefined,
      workflowKind: deps.studio.value?.workflowKind || undefined,
      projectCode: deps.studio.value?.projectCode || undefined,
      executionEngine: deps.studio.value?.executionEngine || 'GRAPH_SPEC',
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
    const isCurrent = currentDebugScope()
    deps.debugOpen.value = true
    deps.propertyPanelCollapsed.value = false
    for (const field of deps.debugInputFields.value) {
      if (!(field.name in deps.debugInputParams)) {
        deps.debugInputParams[field.name] = field.defaultValue ?? (field.name === 'question' ? deps.debugMessage.value : '')
      }
    }
    if (!deps.recentRuns.value.length) {
      await loadRecentStudioRuns()
      if (!isCurrent()) return
    }
    try {
      await deps.loadStoredDebugSession()
      if (!isCurrent()) return
      await restoreDebugConversation()
    } catch {
      if (!isCurrent()) return
      ElMessage.error('暂时无法查询调试结果，已保留原会话。请稍后重新打开调试面板。')
    }
  }

  async function beginWorkingCopyDebugTurn(input: {
    message?: string
    values?: Record<string, unknown>
    interactionId?: string
  }) {
    const isCurrent = currentDebugScope()
    if (!isCurrent()) return
    const controller = ensureDebugController()
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
    // Clear transport-owned session id so a new free-text turn never submits a stale session.
    try {
      await controller.clearSession()
    } catch {
      if (!isCurrent()) return
      // ignore clear errors; create path will still run
    }
    try {
      if (!isCurrent()) return
      await controller.send(input)
      if (!isCurrent()) return
      const status = debugConversationSnapshot.value.turnStatus
      if (status === 'failed') {
        ElMessage.error(debugConversationSnapshot.value.error || '工作副本调试失败')
        deps.debugLoading.value = false
      } else if (status !== 'waiting' && status !== 'completed' && status !== 'sending' && status !== 'streaming') {
        deps.debugLoading.value = false
      }
    } catch (err) {
      if (!isCurrent()) return
      ElMessage.error('工作副本调试失败：' + (err as Error).message)
      deps.debugLoading.value = false
    }
  }

  async function handleRunWorkingCopyDebug() {
    const inputParams = buildDebugInputParams()
    const message = debugMessageFromParams(inputParams)
    if (!message.trim()) {
      ElMessage.warning('请输入测试消息或用户输入字段')
      return
    }
    deps.debugMessage.value = ''
    if (deps.debugInputFields.value.length) {
      await beginWorkingCopyDebugTurn({
        interactionId: WORKFLOW_INITIAL_INPUT_ID,
        values: inputParams,
        message,
      })
      return
    }
    await beginWorkingCopyDebugTurn({ message })
  }

  async function handleDebugConversationSend(text: string) {
    const message = text.trim()
    if (!message) return
    deps.debugMessage.value = ''
    await beginWorkingCopyDebugTurn({ message })
  }

  async function handleDebugInteractionSubmit(
    interactionId: string,
    action: string,
    values: Record<string, unknown>,
  ) {
    const isCurrent = currentDebugScope()
    if (interactionId === WORKFLOW_INITIAL_INPUT_ID) {
      const message = debugMessageFromParams(values)
      if (!message.trim() && !Object.keys(values).length) {
        ElMessage.warning('请输入测试消息或用户输入字段')
        return
      }
      await beginWorkingCopyDebugTurn({
        interactionId: WORKFLOW_INITIAL_INPUT_ID,
        values,
        message,
      })
      return
    }
    if (!deps.debugSession.value?.sessionId) {
      ElMessage.warning('当前没有可继续的调试会话')
      return
    }
    deps.debugLoading.value = true
    try {
      await ensureDebugController().submitInteraction(interactionId, action, values)
      if (!isCurrent()) return
      const status = debugConversationSnapshot.value.turnStatus
      if (status === 'failed') {
        ElMessage.error(debugConversationSnapshot.value.error || '调试会话执行失败')
      } else if (status === 'waiting') {
        ElMessage.warning('调试会话等待继续输入')
      } else if (status === 'completed') {
        ElMessage.success('调试会话已继续执行')
      }
    } catch (err) {
      if (!isCurrent()) return
      ElMessage.error('提交交互失败：' + (err as Error).message)
    } finally {
      if (isCurrent()) deps.debugLoading.value = false
    }
  }

  async function handleDebugUiSubmit(values: Record<string, unknown>) {
    const waitingBlock = debugConversationSnapshot.value.messages
      .flatMap((message) => message.blocks)
      .find((block) => block.type === 'interaction' && block.state === 'waiting')
    const interactionId = deps.debugSession.value?.uiRequest?.interactionId
      || (waitingBlock && waitingBlock.type === 'interaction' ? waitingBlock.request.interactionId : undefined)
    if (!interactionId) {
      ElMessage.warning('当前没有可提交的交互')
      return
    }
    await handleDebugInteractionSubmit(interactionId, 'submit', values)
  }

  /** 取消交互卡片：仅 submit action=cancel，不调用 session cancel / controller.cancel() */
  async function handleDebugInteractionCancel(interactionId: string) {
    if (interactionId === WORKFLOW_INITIAL_INPUT_ID) {
      // 本地初始表单：只关闭本地 waiting，不创建会话
      debugConversationSnapshot.value = {
        ...debugConversationSnapshot.value,
        turnStatus: 'idle',
      }
      return
    }
    await handleDebugInteractionSubmit(interactionId, 'cancel', {})
  }

  async function handleCancelDebugSession() {
    const isCurrent = currentDebugScope()
    if (!deps.debugSession.value?.sessionId) return
    deps.debugLoading.value = true
    try {
      await ensureDebugController().cancel()
      if (!isCurrent()) return
      deps.currentDebugNodeId.value = ''
      deps.refreshWorkflowNodeClasses()
      ElMessage.success('调试会话已取消')
    } catch (err) {
      if (!isCurrent()) return
      ElMessage.error('取消调试会话失败：' + (err as Error).message)
    } finally {
      if (isCurrent()) deps.debugLoading.value = false
    }
  }

  async function loadTraceArtifacts(
    traceId: string,
    shouldApply: () => boolean = () => true,
  ) {
    const [trace, runOps] = await Promise.allSettled([
      getTraceDetail(traceId),
      getRunOpsDetail(traceId),
    ])
    if (!shouldApply()) return false
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
    return true
  }

  async function loadRecentStudioRuns() {
    const isCurrent = currentDebugScope()
    deps.recentRunsLoading.value = true
    try {
      const { data } = await getRecentRunOps({ days: 7, limit: 100 })
      if (!isCurrent()) return
      deps.recentRuns.value = data ?? []
    } catch {
      if (!isCurrent()) return
      ElMessage.error('加载最近运行失败')
    } finally {
      if (isCurrent()) deps.recentRunsLoading.value = false
    }
  }

  async function handleLoadTraceReplay(traceId = deps.replayTraceInput.value) {
    const value = String(traceId || '').trim()
    if (!value) {
      ElMessage.warning('请输入 traceId')
      return
    }
    const requestSequence = ++traceReplayRequestSequence
    const isCurrentRequest = () => (
      requestSequence === traceReplayRequestSequence
      && deps.currentTraceId.value === value
    )
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
      const applied = await loadTraceArtifacts(value, isCurrentRequest)
      if (!applied || !isCurrentRequest()) return
      if (!deps.runOpsDetail.value && !deps.traceNodes.value.length) {
        ElMessage.warning('未读取到这次运行的链路数据')
      } else {
        ElMessage.success('已回放到画布')
      }
    } catch (err) {
      if (!isCurrentRequest()) return
      ElMessage.error('回放失败：' + (err as Error).message)
    } finally {
      if (requestSequence === traceReplayRequestSequence) {
        deps.traceReplayLoading.value = false
      }
    }
  }

  async function handleRestoreDebugSession() {
    const isCurrent = currentDebugScope()
    if (!isCurrent()) return
    if (deps.debugLoading.value) return
    deps.debugLoading.value = true
    try {
      await deps.loadStoredDebugSession()
      if (!isCurrent()) return
      if (!deps.debugSession.value?.sessionId) {
        ElMessage.warning('尚未取得会话标识，无法查询原调试结果。')
        return
      }
      await restoreDebugConversation()
    } catch {
      if (!isCurrent()) return
      ElMessage.error('暂时无法查询调试结果，已保留原会话。请稍后重试查询。')
    } finally {
      if (isCurrent()) deps.debugLoading.value = false
    }
  }

  function handleRecentTraceChange(value: string | number | boolean | undefined) {
    const traceId = String(value || '').trim()
    if (traceId) {
      void handleLoadTraceReplay(traceId)
    }
  }

  function clearTraceReplay() {
    traceReplayRequestSequence += 1
    deps.traceReplayLoading.value = false
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
    const isCurrent = currentDebugScope()
    if (!isCurrent()) return
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
      if (!isCurrent()) return
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
      if (!isCurrent()) return
      deps.debugResult.value = {
        answer: data.answer || data.errorMessage || '',
        metadata: {
          traceId: data.traceId,
          version: active.version,
          workflowKeySlug: deps.studio.value?.keySlug,
          workflowId: deps.workflowId.value,
          executionEngine: deps.studio.value?.executionEngine,
          projectCode: deps.studio.value?.projectCode,
        },
      } as ChatResponse
      if (data.traceId) {
        deps.currentTraceId.value = data.traceId
        deps.replayTraceInput.value = data.traceId
        deps.selectedRecentTraceId.value = data.traceId
        try {
          await loadTraceArtifacts(data.traceId, isCurrent)
        } catch {
          if (!isCurrent()) return
          // trace 可能尚未写入
        }
      }
      if (!isCurrent()) return
      ElMessage[data.success ? 'success' : 'error'](data.success ? '发布版本验证完成' : '发布版本验证失败')
    } catch (err) {
      if (!isCurrent()) return
      ElMessage.error('发布版本验证失败：' + (err as Error).message)
    } finally {
      if (isCurrent()) deps.debugLoading.value = false
    }
  }

  async function runNodeDebug() {
    const isCurrent = currentDebugScope()
    if (!isCurrent()) return
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
      if (!isCurrent()) return
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
      if (!isCurrent()) return
      ElMessage.error((err as Error).message)
    } finally {
      if (isCurrent()) deps.nodeDebugLoading.value = false
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
    nodeDebugState,
    nodeRunClass,
    nodeRunLabel,
    focusDebugNode,
    selectDebugStep,
    openNodeTrace,
    handleDebug,
    handleRunWorkingCopyDebug,
    handleDebugUiSubmit,
    handleDebugInteractionSubmit,
    handleDebugInteractionCancel,
    handleDebugConversationSend,
    handleCancelDebugSession,
    loadRecentStudioRuns,
    handleLoadTraceReplay,
    handleRecentTraceChange,
    clearTraceReplay,
    recentRunLabel,
    handleRunPublishedDebug,
    handleRunNodeDebug,
    isDebugStepRunning,
    debugConversationSnapshot,
    workflowInitialUiRequest,
    workflowInitialChatField,
    isDebugConversationBusy,
    restoreDebugConversation,
    handleRestoreDebugSession,
    disposeDebugConversation,
  }
}
