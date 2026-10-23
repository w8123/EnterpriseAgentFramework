<template>
  <main class="agent-debug-page density-comfortable">
    <PageHeader
      class="agent-debug-header"
      variant="entity"
      domain="agent"
      presentation="plain"
      action-presentation="inline"
      :title="agentName || 'Agent 调试台'"
    >
      <template #leading>
        <div class="app-page-header__entity-mark" aria-hidden="true">
          <el-icon><LlmModelIcon /></el-icon>
        </div>
      </template>
      <template #tags>
        <el-radio-group
          v-if="activeDebugConfig && draftDebugConfig"
          v-model="debugTarget"
          class="debug-target-switch"
          size="small"
          aria-label="选择调试的配置版本"
          :disabled="isConversationBusy"
          @change="handleDebugTargetChange"
        >
          <el-radio-button value="draft">草稿 v{{ draftDebugConfig.versionNo }}</el-radio-button>
          <el-radio-button value="active">线上 v{{ activeDebugConfig.versionNo }}</el-radio-button>
        </el-radio-group>
        <StatusTag v-else :label="debugTargetLabel" :tone="debugTargetTone" />
      </template>
      <template #meta>
        <div class="agent-header-meta">
          <span>{{ debugTargetHint }}</span>
          <i aria-hidden="true" />
          <span>会话 ID <strong>{{ sessionId || '尚未建立' }}</strong></span>
          <el-tooltip content="复制会话 ID" placement="top">
            <button
              class="header-copy-button"
              type="button"
              aria-label="复制会话 ID"
              :disabled="!sessionId"
              @click="copySessionId"
            >
              <el-icon><CopyDocument /></el-icon>
            </button>
          </el-tooltip>
        </div>
      </template>
    </PageHeader>

    <section
      ref="debugBodyRef"
      class="debug-body"
      :class="{ 'is-resizing': resizing }"
      :style="{ '--insight-width': `${insightWidth}px` }"
    >
      <main class="chat-panel glass-surface-panel">
        <div class="chat-toolbar">
          <div class="toolbar-title">
            <el-icon><ChatDotRound /></el-icon>
            <strong>对话</strong>
          </div>
          <div class="toolbar-actions">
            <span class="run-state" :class="{ active: isConversationBusy }">
              <i />{{ isConversationBusy ? '执行中' : '就绪' }}
            </span>
            <el-tooltip v-if="isConversationBusy" content="停止执行" placement="top">
              <button class="toolbar-icon-button stop-button" type="button" aria-label="停止执行" @click="stopExecution">
                <el-icon><CircleClose /></el-icon>
              </button>
            </el-tooltip>
            <el-tooltip content="清除对话" placement="top">
              <button
                class="toolbar-icon-button clear-button"
                type="button"
                aria-label="清除对话"
                :disabled="isConversationBusy || (!conversationSnapshot.messages.length && !sessionId)"
                @click="handleClearSession"
              >
                <el-icon><Delete /></el-icon>
              </button>
            </el-tooltip>
          </div>
        </div>

        <ConversationView
          class="chat-conversation"
          chrome="inline"
          density="comfortable"
          surface="admin"
          atmosphere
          :snapshot="conversationSnapshot"
          :resolve-status-hint="resolveAssistantStatusHint"
          :resolve-thinking-presentation="resolveAssistantThinkingPresentation"
          hide-composer
          @stop="stopExecution"
          @interaction-submit="handleInteractionSubmit"
          @interaction-cancel="handleInteractionCancel"
        >
          <template #empty>
            <div class="chat-empty">
              <div class="empty-orbit">
                <img :src="prismAvatarUrl" alt="" />
              </div>
              <h3>开始一次 Agent 调试</h3>
              <p>输入问题，观察 Agent 的理解、规划、Workflow 选择与最终回答。</p>
            </div>
          </template>
          <template #message-meta="{ message }">
            <div
              v-if="messageMetaVisible(message)"
              class="message-meta"
            >
              <span
                v-for="tool in messageToolCalls(message)"
                :key="tool"
                class="tool-chip"
              >
                <el-icon><Tools /></el-icon>{{ tool }}
              </span>
              <span v-if="messageElapsedMs(message)" class="elapsed-chip">
                <el-icon><Timer /></el-icon>{{ formatElapsed(messageElapsedMs(message)!) }}
              </span>
              <button
                v-if="messageTraceId(message)"
                class="trace-link"
                type="button"
                @click="openTrace(messageTraceId(message))"
              >
                <el-icon><Connection /></el-icon>查看运行详情
              </button>
            </div>
          </template>
          <template #composer>
            <form class="chat-input" novalidate @submit.prevent="handleSend">
              <div class="input-composer">
                <el-input
                  v-model="inputMessage"
                  type="textarea"
                  :autosize="{ minRows: 2, maxRows: 5 }"
                  placeholder="输入消息"
                  resize="none"
                  :disabled="isConversationBusy"
                  aria-label="调试消息"
                  @keydown="handleKeydown"
                />
                <div class="input-actions">
                  <span class="input-hint"><kbd>Ctrl</kbd><b>+</b><kbd>Enter</kbd><em>发送</em></span>
                  <el-button
                    class="send-button"
                    :icon="isConversationBusy ? Loading : Promotion"
                    type="primary"
                    native-type="submit"
                    :loading="isConversationBusy"
                    :disabled="isConversationBusy || !inputMessage.trim()"
                  >
                    {{ isConversationBusy ? '思考中' : '发送' }}
                  </el-button>
                </div>
              </div>
            </form>
          </template>
        </ConversationView>
      </main>

      <div
        class="workbench-resizer"
        role="separator"
        aria-label="调整对话与本轮执行面板宽度"
        aria-orientation="vertical"
        :aria-valuemin="MIN_INSIGHT_WIDTH"
        :aria-valuemax="MAX_INSIGHT_WIDTH"
        :aria-valuenow="Math.round(insightWidth)"
        tabindex="0"
        title="拖动调整宽度 · 双击复位"
        @pointerdown="startResize"
        @pointermove="moveResize"
        @pointerup="finishResize"
        @pointercancel="finishResize"
        @lostpointercapture="cancelResize"
        @dblclick="resetInsightWidth"
        @keydown="handleResizeKeydown"
      >
        <span class="resizer-grip" aria-hidden="true">
          <el-icon><DArrowLeft /></el-icon>
        </span>
      </div>

      <aside class="detail-panel glass-surface-panel">
        <div class="detail-header">
          <div>
            <span class="toolbar-label">本轮执行</span>
            <h3>本轮执行</h3>
          </div>
          <div class="detail-header-actions">
            <el-tooltip content="查看本轮执行链路与原始运行数据" placement="top">
              <button
                class="detail-action-button"
                type="button"
                :disabled="!latestTraceId"
                aria-label="查看运行详情"
                @click="openTrace(latestTraceId)"
              >
                <el-icon><Connection /></el-icon>
                <span>查看运行详情</span>
              </button>
            </el-tooltip>
          </div>
        </div>

        <div class="insight-summary">
          <div><strong>{{ displaySteps.length }}</strong><span>个阶段</span></div>
          <i aria-hidden="true" />
          <div><span>人工审批</span><strong>{{ pendingApprovals.length }}</strong></div>
        </div>

        <LiquidGlassTabs v-model="activeTab" class="detail-tabs" stretch>
          <el-tab-pane name="supervisor">
            <template #label>执行阶段 <b class="tab-count">{{ displaySteps.length }}</b></template>
            <AgentDebugExecutionPanel
              v-model:selected-index="selectedStepIndex"
              :steps="displaySteps"
              :phase="executionPhase"
              :status-text="executionStatusText"
              :note="turnExecutionNote"
              :plan-count="planCount"
              :replan-count="replanCount"
              :workflow-call-count="workflowCallCount"
              :step-state="insightStepState"
            />
          </el-tab-pane>

          <el-tab-pane name="approvals">
            <template #label>人工审批 <b class="tab-count">{{ pendingApprovals.length }}</b></template>
            <AgentDebugApprovalPanel
              :approvals="pendingApprovals"
              :loading="approvalLoading"
              @refresh="loadPendingApprovals"
              @open-trace="openTrace"
              @action="handlePendingApprovalAction"
            />
          </el-tab-pane>
        </LiquidGlassTabs>

        <div class="approval-policy" :class="{ 'has-pending': pendingApprovals.length > 0 }">
          <el-icon><Lock /></el-icon>
          <div>
            <strong>{{ pendingApprovals.length ? `存在 ${pendingApprovals.length} 条待处理审批` : '当前无需人工审批' }}</strong>
            <span>{{ pendingApprovals.length ? '切换到人工审批后处理' : '本轮请求未触发审批策略' }}</span>
          </div>
        </div>
      </aside>
    </section>

    <AgentDebugTraceDrawer
      v-model="traceDrawerVisible"
      :trace-id="activeTraceId"
      :nodes="traceNodes"
      :loading="traceLoading"
      :raw-mode="rawResponseMode"
      :raw-json="rawResponseJson"
      @open-runops="openRunOps"
    />
  </main>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  ChatDotRound,
  CircleClose,
  Connection,
  CopyDocument,
  DArrowLeft,
  Delete,
  Loading,
  Lock,
  Promotion,
  Timer,
  Tools,
} from '@element-plus/icons-vue'
import PageHeader from '@/components/common/PageHeader.vue'
import StatusTag from '@/components/common/StatusTag.vue'
import type { StatusTone } from '@/components/common/glassWorkbench'
import LlmModelIcon from '@/components/icons/LlmModelIcon.vue'
import type { UiRequestPayload } from '@/types/interaction'
import type { AgentConfigVersion, AgentResult, PendingHumanApproval, StepRecord } from '@/types/agent'
import type { TraceNode } from '@/types/trace'
import type { Agent } from '@/types/workflow'
import { getAgent, listAgentConfigVersions } from '@/api/workflow'
import { listPendingHumanApprovals } from '@/api/agent'
import { getTraceDetail } from '@/api/trace'
import {
  ConversationView,
  createAgentDebugTransport,
  createConversationController,
  createEmptySnapshot,
  textContentOf,
  type ConversationMessage,
  type ConversationSnapshot,
} from '@/conversation'
import prismAvatarUrl from '@/conversation/assets/agent-prism.webp'
import { LiquidGlassTabs } from '@/components/common/liquid-glass'
import AgentDebugApprovalPanel from './debug/AgentDebugApprovalPanel.vue'
import AgentDebugExecutionPanel from './debug/AgentDebugExecutionPanel.vue'
import AgentDebugTraceDrawer from './debug/AgentDebugTraceDrawer.vue'
import { formatStepName, type ExecutionPhase, type RawResponseMode, type StepState } from './debug/agentDebugFormat'

const route = useRoute()
const router = useRouter()
const agentId = route.params.id as string


const INSIGHT_WIDTH_STORAGE_KEY = 'reachai-agent-debug-insight-width'
const DEFAULT_INSIGHT_WIDTH = 312
const MIN_CONVERSATION_WIDTH = 340
const RESIZER_WIDTH = 14
const RESIZE_BREAKPOINT = 1080
const MIN_INSIGHT_WIDTH = 260
/** 需足够大，才能在常见桌面 debug-body 宽度下把左侧对话区拖到约 380px */
const MAX_INSIGHT_WIDTH = 900

const agent = ref<Agent | null>(null)
const agentName = ref('')
const sessionId = ref('')
const inputMessage = ref('')
const conversationSnapshot = ref<ConversationSnapshot>(createEmptySnapshot())
const activeTab = ref('supervisor')
const debugBodyRef = ref<HTMLElement>()
const lastAgentResult = ref<AgentResult | null>(null)
const liveSteps = ref<StepRecord[]>([])
const traceDrawerVisible = ref(false)
const activeTraceId = ref('')
const traceNodes = ref<TraceNode[]>([])
const traceLoading = ref(false)
const pendingApprovals = ref<PendingHumanApproval[]>([])
const approvalLoading = ref(false)
const executionPhase = ref<ExecutionPhase>('idle')
const selectedStepIndex = ref(-1)
const resizing = ref(false)
const insightWidth = ref(readStoredInsightWidth())
/** 本地占位真实状态：在收到 turn.started 前不得声称 Supervisor 已开始推理 */
const runtimeConnected = ref(false)
const hasStreamedAnswer = ref(false)

let turnStartedAt = 0
let resizingActive = false

const configVersions = ref<AgentConfigVersion[]>([])
const activeDebugConfig = computed(() => configVersions.value.find((item) => item.status === 'ACTIVE') || null)
const draftDebugConfig = computed(() => configVersions.value.find((item) => item.status === 'DRAFT') || null)
/** 有草稿时默认调试草稿；?config=active 可直接打开线上版本。 */
const debugTarget = ref<'draft' | 'active'>(route.query.config === 'active' ? 'active' : 'draft')
const debugConfig = computed(() => {
  if (debugTarget.value === 'draft' && draftDebugConfig.value) return draftDebugConfig.value
  return activeDebugConfig.value || draftDebugConfig.value
})
const debugTargetLabel = computed(() => {
  const config = debugConfig.value
  if (!config) return agent.value?.enabled === false ? '已停用' : '尚无配置'
  return config.status === 'DRAFT' ? `草稿 v${config.versionNo}` : `线上 v${config.versionNo}`
})
const debugTargetTone = computed<StatusTone>(() => {
  if (!debugConfig.value) return agent.value?.enabled === false ? 'danger' : 'neutral'
  return debugConfig.value.status === 'DRAFT' ? 'warning' : 'success'
})
const debugTargetHint = computed(() => {
  const config = debugConfig.value
  if (!config) return '保存配置后即可调试'
  return config.status === 'DRAFT'
    ? '正在调试未发布的草稿，业务用户仍在使用线上版本'
    : '正在调试线上版本'
})

const transport = createAgentDebugTransport({
  agentId,
  getDebugConfigVersionId: () => debugConfig.value?.id,
  getSessionId: () => sessionId.value || undefined,
  setSessionId: (id) => {
    sessionId.value = id || ''
  },
  entryType: 'DEBUG',
})

const controller = createConversationController({
  transport,
  initialSessionId: sessionId.value || undefined,
  onPublicEvent(event) {
    if (event.type === 'turn.started') {
      runtimeConnected.value = true
    }
    if (event.type === 'message.delta') {
      hasStreamedAnswer.value = true
    }
  },
  onDebugEvent(event) {
    if (event.type !== 'debug.supervisor.step') return
    upsertLiveStep(event.data as StepRecord & Record<string, unknown>)
  },
  onChange(state) {
    const enriched = enrichSnapshot(state)
    conversationSnapshot.value = enriched
    syncShellFromSnapshot(enriched)
  },
})

const isConversationBusy = computed(() => {
  const status = conversationSnapshot.value.turnStatus
  return status === 'sending' || status === 'streaming'
})

const displaySteps = computed(() => lastAgentResult.value?.steps?.length
  ? lastAgentResult.value.steps
  : liveSteps.value)
const executionStatusText = computed(() => ({
  idle: '等待开始调试',
  running: 'Agent 正在执行',
  success: '执行完成',
  error: '执行失败',
  stopped: '执行已停止',
})[executionPhase.value])

const UNSAFE_THINKING_DETAIL = /reason(ing)?|chain\s*of\s*thought|prompt|internal|system|内部|思维链/i

/** 安全阶段摘要：仅 Agent Debug Shell 注入，不进入共享层写死文案 */
const currentThinkingSummary = computed(() => {
  if (hasStreamedAnswer.value) return '正在生成回答…'
  const step = displaySteps.value[displaySteps.value.length - 1]
  if (step) {
    const title = typeof step.title === 'string' ? step.title.trim() : ''
    if (title) return title
    const detail = typeof step.detail === 'string' ? step.detail.trim() : ''
    if (detail && detail.length <= 120 && !UNSAFE_THINKING_DETAIL.test(detail)) {
      return detail
    }
    return `正在执行：${formatStepName(step.name, displaySteps.value.length - 1)}`
  }
  if (runtimeConnected.value) {
    return 'Agent 已开始处理请求'
  }
  return '正在连接运行时…'
})

function resolveAssistantStatusHint(message: ConversationMessage) {
  if (message.role !== 'assistant') return undefined
  if (message.status !== 'pending' && message.status !== 'streaming') return undefined
  return currentThinkingSummary.value
}

function safeThinkingDetail(detail: unknown): string | undefined {
  if (typeof detail === 'string') {
    const text = detail.trim()
    if (!text || text.length > 120) return undefined
    if (UNSAFE_THINKING_DETAIL.test(text)) return undefined
    return text
  }
  return undefined
}

function upsertLiveStep(raw: StepRecord & Record<string, unknown>) {
  const stepId = raw.stepId ? String(raw.stepId) : undefined
  const next: StepRecord = {
    name: String(raw.name || 'supervisor'),
    detail: raw.detail,
    stepId,
    state: raw.state ? String(raw.state) : undefined,
    sequence: typeof raw.sequence === 'number' ? raw.sequence : undefined,
    source: raw.source ? String(raw.source) : undefined,
    title: raw.title ? String(raw.title) : undefined,
    timestamp: raw.timestamp ? String(raw.timestamp) : undefined,
  }
  if (stepId) {
    const index = liveSteps.value.findIndex((s) => s.stepId === stepId)
    if (index >= 0) {
      const prev = liveSteps.value[index]
      liveSteps.value.splice(index, 1, {
        ...prev,
        ...next,
        sequence: next.sequence ?? prev.sequence,
      })
      return
    }
  }
  // 无 stepId 的旧事件：保持 append 兼容
  liveSteps.value.push(next)
}

/** 思考卡片安全步骤：仅映射高层阶段，绝不展示 reasoning / prompt */
function resolveAssistantThinkingPresentation(message: ConversationMessage) {
  if (message.role !== 'assistant') return undefined
  if (message.status !== 'pending' && message.status !== 'streaming') return undefined
  const steps = displaySteps.value
  if (!steps.length) return undefined
  return {
    label: `本轮执行 ${steps.length} 步`,
    count: steps.length,
    steps: steps.map((step, index) => ({
      id: step.stepId || `agent-step-${index}-${step.name || 'step'}`,
      title: step.title || formatStepName(step.name, index),
      detail: safeThinkingDetail(step.detail),
      state: mapThinkingStepState(step, index),
    })),
  }
}
const latestTraceId = computed(() => {
  for (let index = conversationSnapshot.value.messages.length - 1; index >= 0; index -= 1) {
    const traceId = conversationSnapshot.value.messages[index].metadata?.traceId
    if (traceId) return String(traceId)
  }
  return String(lastAgentResult.value?.metadata?.traceId || '')
})

const planCount = computed(() => readOptionalNumber(lastAgentResult.value?.metadata, 'planCount'))
const replanCount = computed(() => readOptionalNumber(lastAgentResult.value?.metadata, 'replanCount'))
const workflowCallCount = computed(() => readOptionalNumber(lastAgentResult.value?.metadata, 'workflowCallCount'))

/** 仅在有可靠信号时解释「直接回答」；字段缺失不伪装为 0，也不断言「未发生」。 */
const turnExecutionNote = computed(() => {
  if (executionPhase.value === 'idle' || executionPhase.value === 'running') return ''
  const steps = displaySteps.value
  if (!steps.length) return ''

  const onlyDirectAnswer = steps.every((step) => isDirectAnswerStepName(step.name))
  if (!onlyDirectAnswer) return ''

  const hasApprovals = pendingApprovals.value.length > 0
  const hasTools = hasObservedToolActivity(lastAgentResult.value)
  if (hasApprovals || hasTools) return ''

  const plan = planCount.value
  const replan = replanCount.value
  const workflowCalls = workflowCallCount.value
  const countsReliable = plan !== null && replan !== null && workflowCalls !== null

  if (countsReliable && plan === 0 && replan === 0 && workflowCalls === 0) {
    return '本轮直接回答，未触发规划、Workflow 或人工审批。'
  }

  if (!countsReliable) {
    return '本轮仅观察到直接回答阶段。'
  }

  return ''
})


/**
 * 防止审批项等其它 traceId 打开详情时，误展示当前轮次 lastAgentResult.metadata。
 * - match / latest-fallback → show
 * - 明确不一致 → mismatch
 * - 无 metadata → empty
 */
const rawResponseMode = computed((): RawResponseMode => {
  const metadata = lastAgentResult.value?.metadata
  if (metadata == null) return 'empty'

  const metaTraceId = readOptionalTraceId(metadata)
  const active = activeTraceId.value

  if (metaTraceId) {
    return metaTraceId === active ? 'show' : 'mismatch'
  }

  // metadata 无 traceId：仅当打开的是明确的本轮 latestTraceId 时展示
  if (active && latestTraceId.value && active === latestTraceId.value) {
    return 'show'
  }

  if (active && latestTraceId.value && active !== latestTraceId.value) {
    return 'mismatch'
  }

  // 有 metadata、无冲突 id 时按本轮数据展示
  return 'show'
})

const rawResponseJson = computed(() => {
  if (rawResponseMode.value !== 'show') return ''
  return JSON.stringify(lastAgentResult.value?.metadata ?? {}, null, 2)
})

function enrichAssistantMetadata(meta: Record<string, unknown>): Record<string, unknown> {
  const result = metadataAsAgentResult(meta)
  return {
    ...meta,
    traceId: meta.traceId || result.metadata?.traceId,
    toolCalls: extractToolCalls(result),
    elapsedMs: resolveElapsedMs(result, turnStartedAt),
  }
}

function enrichSnapshot(state: ConversationSnapshot): ConversationSnapshot {
  return {
    ...state,
    messages: state.messages.map((message) => {
      if (message.role !== 'assistant' || message.status === 'streaming' || message.status === 'pending') {
        return message
      }
      if (!message.metadata) return message
      return {
        ...message,
        metadata: enrichAssistantMetadata(message.metadata),
      }
    }),
  }
}

function metadataAsAgentResult(meta: Record<string, unknown>): AgentResult {
  const nested = (meta.metadata && typeof meta.metadata === 'object' && !Array.isArray(meta.metadata))
    ? meta.metadata as Record<string, unknown>
    : undefined
  // success 显式 false 才失败；缺失时不默认当成功（由 turnStatus 决定右侧态）
  const explicitSuccess = meta.success === true || nested?.success === true
  const explicitFailure = meta.success === false
    || nested?.success === false
    || String(meta.success).toLowerCase() === 'false'
  return {
    success: explicitFailure ? false : explicitSuccess ? true : meta.success !== false,
    answer: String(meta.answer || ''),
    sessionId: meta.sessionId as string | undefined,
    steps: Array.isArray(meta.steps) ? meta.steps as StepRecord[] : undefined,
    toolResults: meta.toolResults as Record<string, unknown> | undefined,
    metadata: nested || (meta.traceId || meta.code
      ? {
          ...(meta.traceId ? { traceId: meta.traceId } : {}),
          ...(meta.code ? { code: meta.code } : {}),
          ...(meta.finishReason ? { finishReason: meta.finishReason } : {}),
        }
      : undefined),
    uiRequest: meta.uiRequest as UiRequestPayload | undefined,
  }
}

function findLastAssistantMessage(state: ConversationSnapshot): ConversationMessage | undefined {
  for (let index = state.messages.length - 1; index >= 0; index -= 1) {
    if (state.messages[index].role === 'assistant') return state.messages[index]
  }
  return undefined
}

function syncShellFromSnapshot(state: ConversationSnapshot) {
  const lastAssistant = findLastAssistantMessage(state)
  if (lastAssistant?.metadata) {
    const result = metadataAsAgentResult(lastAssistant.metadata)
    lastAgentResult.value = {
      ...result,
      answer: result.answer || textContentOf(lastAssistant),
      steps: result.steps?.length ? result.steps : liveSteps.value,
    }
  }

  if (state.turnStatus === 'sending' || state.turnStatus === 'streaming') {
    executionPhase.value = 'running'
  } else if (state.turnStatus === 'cancelled') {
    executionPhase.value = 'stopped'
  } else if (state.turnStatus === 'failed') {
    executionPhase.value = 'error'
  } else if (state.turnStatus === 'completed' || state.turnStatus === 'waiting') {
    executionPhase.value = lastAgentResult.value?.success === false ? 'error' : 'success'
    const uiRequest = lastAgentResult.value?.uiRequest
    if (uiRequest?.component === 'confirm') {
      void loadPendingApprovals()
    }
  } else if (state.turnStatus === 'idle') {
    executionPhase.value = state.messages.length ? executionPhase.value : 'idle'
  }
}

function messageMetaVisible(message: ConversationMessage) {
  const meta = message.metadata || {}
  const toolCalls = meta.toolCalls
  return Boolean(
    (Array.isArray(toolCalls) && toolCalls.length)
    || meta.elapsedMs
    || meta.traceId,
  )
}

function messageToolCalls(message: ConversationMessage): string[] {
  const toolCalls = message.metadata?.toolCalls
  return Array.isArray(toolCalls) ? toolCalls.map(String) : []
}

function messageElapsedMs(message: ConversationMessage): number | undefined {
  const value = Number(message.metadata?.elapsedMs)
  return Number.isFinite(value) && value >= 0 ? value : undefined
}

function messageTraceId(message: ConversationMessage): string | undefined {
  const traceId = message.metadata?.traceId
  return traceId ? String(traceId) : undefined
}

function beginTurnShellReset() {
  turnStartedAt = performance.now()
  liveSteps.value = []
  lastAgentResult.value = null
  activeTab.value = 'supervisor'
  selectedStepIndex.value = -1
  runtimeConnected.value = false
  hasStreamedAnswer.value = false
}

function readStoredInsightWidth() {
  try {
    const value = Number(localStorage.getItem(INSIGHT_WIDTH_STORAGE_KEY))
    return Number.isFinite(value) ? Math.min(MAX_INSIGHT_WIDTH, Math.max(MIN_INSIGHT_WIDTH, value)) : DEFAULT_INSIGHT_WIDTH
  } catch {
    return DEFAULT_INSIGHT_WIDTH
  }
}



/** 类型安全读取可选数值：字段缺失返回 null，不伪装为 0。 */
function readOptionalNumber(
  source: Record<string, unknown> | null | undefined,
  key: string,
): number | null {
  if (!source || !Object.prototype.hasOwnProperty.call(source, key)) return null
  const value = source[key]
  if (typeof value === 'number' && Number.isFinite(value)) return value
  if (typeof value === 'string' && value.trim() !== '') {
    const parsed = Number(value)
    if (Number.isFinite(parsed)) return parsed
  }
  return null
}


function readOptionalTraceId(source: Record<string, unknown>): string {
  const value = source.traceId
  if (typeof value === 'string' && value.trim()) return value.trim()
  if (typeof value === 'number' && Number.isFinite(value)) return String(value)
  return ''
}

function isDirectAnswerStepName(name: string): boolean {
  const normalized = String(name || '').toLowerCase()
  return normalized.includes('answer')
    || normalized.includes('final')
    || normalized.includes('respond')
    || normalized === 'final_answer'
}

function hasObservedToolActivity(result: AgentResult | null): boolean {
  if (!result) return false
  const toolResults = result.toolResults
  if (toolResults && Object.keys(toolResults).length > 0) return true
  const toolCalls = result.metadata?.toolCalls
  if (Array.isArray(toolCalls) && toolCalls.length > 0) return true
  return false
}

function mapThinkingStepState(
  step: StepRecord,
  index: number,
): 'complete' | 'active' | 'pending' | 'error' {
  const state = String(step.state || '').toLowerCase()
  if (state === 'failed' || state === 'cancelled') return 'error'
  if (state === 'completed') return 'complete'
  if (state === 'started' || state === 'waiting' || state === 'active') return 'active'
  return insightStepState(index) === 'error'
    ? 'error'
    : insightStepState(index) === 'complete'
      ? 'complete'
      : insightStepState(index) === 'active'
        ? 'active'
        : 'pending'
}

function insightStepState(index: number): StepState {
  const step = displaySteps.value[index]
  const state = String(step?.state || '').toLowerCase()
  if (state === 'failed' || state === 'cancelled') return 'error'
  if (state === 'completed') return 'complete'
  if (state === 'started' || state === 'waiting' || state === 'active') {
    return executionPhase.value === 'running' ? 'active' : 'complete'
  }
  if (executionPhase.value === 'running') {
    return index === displaySteps.value.length - 1 ? 'active' : 'complete'
  }
  if ((executionPhase.value === 'error' || executionPhase.value === 'stopped') && index === displaySteps.value.length - 1) {
    return 'error'
  }
  if (executionPhase.value === 'success' || index < displaySteps.value.length - 1) return 'complete'
  return 'pending'
}

function formatElapsed(elapsedMs: number) {
  if (elapsedMs < 1000) return `${Math.max(1, Math.round(elapsedMs))}ms`
  return `${(elapsedMs / 1000).toFixed(elapsedMs < 10_000 ? 1 : 0)}s`
}

function resolveElapsedMs(result: AgentResult | null, startedAt: number) {
  const metadata = result?.metadata || {}
  const candidates = [metadata.durationMs, metadata.elapsedMs, metadata.latencyMs]
  const runtimeValue = candidates.map(Number).find(value => Number.isFinite(value) && value >= 0)
  return runtimeValue ?? Math.max(1, performance.now() - startedAt)
}

function extractToolCalls(result: AgentResult) {
  const metadataCalls = Array.isArray(result.metadata?.toolCalls)
    ? result.metadata.toolCalls.map(String)
    : []
  return [...new Set([...Object.keys(result.toolResults || {}), ...metadataCalls])]
}

async function openTrace(traceId?: string) {
  if (!traceId) return
  activeTraceId.value = traceId
  traceNodes.value = []
  traceLoading.value = true
  traceDrawerVisible.value = true
  try {
    const { data } = await getTraceDetail(traceId)
    traceNodes.value = data.nodes || []
  } catch {
    traceNodes.value = []
    ElMessage.error('加载 Trace 失败')
  } finally {
    traceLoading.value = false
  }
}

function openRunOps() {
  if (!activeTraceId.value) return
  traceDrawerVisible.value = false
  router.push(`/runops/${encodeURIComponent(activeTraceId.value)}`)
}



async function copySessionId() {
  if (!sessionId.value) return
  try {
    await navigator.clipboard.writeText(sessionId.value)
    ElMessage.success('会话 ID 已复制')
  } catch {
    ElMessage.error('复制失败，请手动复制')
  }
}

async function loadAgent() {
  try {
    const { data } = await getAgent(agentId)
    agent.value = data
    agentName.value = data.name || agentId
  } catch {
    agentName.value = agentId
  }
}

async function loadConfigVersions() {
  try {
    const { data } = await listAgentConfigVersions(agentId)
    configVersions.value = Array.isArray(data) ? data : []
  } catch {
    configVersions.value = []
  }
}

/** 换配置版本后开新会话，避免一段对话里混用两套配置。 */
async function handleDebugTargetChange() {
  if (!sessionId.value && !conversationSnapshot.value.messages.length) return
  await handleClearSession()
}

async function loadPendingApprovals() {
  approvalLoading.value = true
  try {
    const { data } = await listPendingHumanApprovals({ agentId, limit: 50 })
    pendingApprovals.value = data || []
  } catch {
    ElMessage.error('加载审批列表失败')
  } finally {
    approvalLoading.value = false
  }
}

function handleKeydown(event: KeyboardEvent) {
  if (!event.isComposing && (event.ctrlKey || event.metaKey) && event.key === 'Enter') {
    event.preventDefault()
    handleSend()
  }
}

async function handleSend() {
  const message = inputMessage.value.trim()
  if (!message || isConversationBusy.value) return
  inputMessage.value = ''
  beginTurnShellReset()
  await controller.send(message)
}

async function handleInteractionSubmit(
  interactionId: string,
  action: string,
  values: Record<string, unknown>,
) {
  beginTurnShellReset()
  await controller.submitInteraction(interactionId, action, values)
  await loadPendingApprovals()
}

/** 取消交互卡片：提交 action=cancel，不调用 controller.cancel()（那是停止生成） */
async function handleInteractionCancel(interactionId: string) {
  beginTurnShellReset()
  await controller.submitInteraction(interactionId, 'cancel', {})
  await loadPendingApprovals()
}

function stopExecution() {
  void controller.cancel()
}

async function handlePendingApprovalAction(
  approval: PendingHumanApproval,
  action: string,
  values: Record<string, unknown>,
) {
  const routeAction = values?.confirm === false ? 'reject' : action
  beginTurnShellReset()
  await controller.submitInteraction(approval.interactionId, routeAction, values)
  await loadPendingApprovals()
}

async function handleClearSession() {
  if (isConversationBusy.value) return
  try {
    await controller.clearSession()
    liveSteps.value = []
    lastAgentResult.value = null
    executionPhase.value = 'idle'
    selectedStepIndex.value = -1
    runtimeConnected.value = false
    hasStreamedAnswer.value = false
    ElMessage.success('对话已清除')
  } catch {
    ElMessage.error('清除失败')
  }
}

function constrainInsightWidth(width: number) {
  const availableWidth = debugBodyRef.value?.getBoundingClientRect().width || 0
  const responsiveMax = availableWidth
    ? Math.max(MIN_INSIGHT_WIDTH, availableWidth - MIN_CONVERSATION_WIDTH - RESIZER_WIDTH)
    : MAX_INSIGHT_WIDTH
  return Math.min(Math.min(MAX_INSIGHT_WIDTH, responsiveMax), Math.max(MIN_INSIGHT_WIDTH, width))
}

function persistInsightWidth() {
  try {
    localStorage.setItem(INSIGHT_WIDTH_STORAGE_KEY, String(Math.round(insightWidth.value)))
  } catch {
    // localStorage may be unavailable in privacy-restricted embedded contexts.
  }
}

function updateInsightWidth(clientX: number) {
  const rect = debugBodyRef.value?.getBoundingClientRect()
  if (!rect) return
  insightWidth.value = constrainInsightWidth(rect.right - clientX - RESIZER_WIDTH / 2)
}

function startResize(event: PointerEvent) {
  if (window.innerWidth <= RESIZE_BREAKPOINT) return
  resizingActive = true
  resizing.value = true
  const target = event.currentTarget as HTMLElement
  target.focus()
  target.setPointerCapture(event.pointerId)
  updateInsightWidth(event.clientX)
  event.preventDefault()
}

function moveResize(event: PointerEvent) {
  if (!resizingActive) return
  updateInsightWidth(event.clientX)
}

function finishResize(event: PointerEvent) {
  if (!resizingActive) return
  const target = event.currentTarget as HTMLElement
  if (target.hasPointerCapture(event.pointerId)) target.releasePointerCapture(event.pointerId)
  resizingActive = false
  resizing.value = false
  persistInsightWidth()
}

function cancelResize() {
  resizingActive = false
  resizing.value = false
  persistInsightWidth()
}

function resetInsightWidth() {
  insightWidth.value = constrainInsightWidth(DEFAULT_INSIGHT_WIDTH)
  persistInsightWidth()
}

function handleResizeKeydown(event: KeyboardEvent) {
  const step = event.shiftKey ? 48 : 16
  if (event.key === 'ArrowLeft') insightWidth.value = constrainInsightWidth(insightWidth.value + step)
  else if (event.key === 'ArrowRight') insightWidth.value = constrainInsightWidth(insightWidth.value - step)
  else if (event.key === 'Home') insightWidth.value = constrainInsightWidth(MIN_INSIGHT_WIDTH)
  else if (event.key === 'End') insightWidth.value = constrainInsightWidth(MAX_INSIGHT_WIDTH)
  else return
  event.preventDefault()
  persistInsightWidth()
}

function handleWindowResize() {
  insightWidth.value = constrainInsightWidth(insightWidth.value)
}

onMounted(() => {
  loadAgent()
  loadConfigVersions()
  loadPendingApprovals()
  nextTick(handleWindowResize)
  window.addEventListener('resize', handleWindowResize)
})

onBeforeUnmount(() => {
  controller.dispose()
  window.removeEventListener('resize', handleWindowResize)
})
</script>

<style scoped lang="scss">
.agent-debug-page {
  /* Shell 只桥接品牌主色；光谱 / 氛围由共享 conversation-tokens 从 primary 派生 */
  --agent-spectrum-anchor: var(--reachai-chat-spectrum-anchor);
  --agent-spectrum-cool: var(--reachai-chat-spectrum-cool);
  --agent-spectrum-violet: var(--reachai-chat-spectrum-violet);
  --agent-spectrum-rose: var(--reachai-chat-spectrum-rose);
  --agent-spectrum-warm: var(--reachai-chat-spectrum-warm);
  --agent-atmosphere-art-opacity: var(--reachai-chat-atmosphere-art-opacity);
  --agent-atmosphere-art-saturation: var(--reachai-chat-atmosphere-art-saturation);
  --agent-atmosphere-wash-top: var(--reachai-chat-atmosphere-wash-top);
  --agent-atmosphere-wash-bottom: var(--reachai-chat-atmosphere-wash-bottom);
  --agent-atmosphere-glow-primary: var(--reachai-chat-atmosphere-glow-primary);
  --agent-atmosphere-glow-secondary: var(--reachai-chat-atmosphere-glow-secondary);
  --agent-atmosphere-bottom-tint: var(--reachai-chat-atmosphere-bottom-tint);
  --agent-atmosphere-theme-wash: var(--reachai-chat-atmosphere-theme-wash);
  /* Insight 面板边缘辉光（与共享卡片阅读面解耦，强度对齐 conversation-tokens） */
  --agent-card-anchor-glow: color-mix(in srgb, var(--agent-spectrum-anchor) 7%, transparent);
  --agent-card-cool-glow: color-mix(in srgb, var(--agent-spectrum-cool) 8%, transparent);
  --agent-card-rose-glow: color-mix(in srgb, var(--agent-spectrum-rose) 7%, transparent);
  --agent-card-warm-glow: color-mix(in srgb, var(--agent-spectrum-warm) 6%, transparent);
  display: flex;
  box-sizing: border-box;
  width: 100%;
  height: 100%;
  min-width: 0;
  min-height: 0;
  flex-direction: column;
  gap: var(--layout-page-gap);
  overflow: hidden;
  padding: var(--layout-page-start) var(--layout-content-inline) var(--layout-page-end);
}

.agent-debug-header {
  flex: 0 0 auto;
}

.agent-header-meta,
.toolbar-title,
.toolbar-actions,
.run-state,
.message-meta,
.tool-chip,
.elapsed-chip,
.trace-link,
.detail-header-actions,
.insight-summary,
.execution-state,
.approval-title,
.approval-meta,
.approval-policy {
  display: flex;
  align-items: center;
}

.agent-header-meta {
  gap: 10px;
  min-width: 0;
  color: var(--text-muted);
  font-size: 12px;
  font-weight: 650;

  i {
    width: 1px;
    height: 14px;
    background: var(--border-divider);
  }

  strong {
    color: var(--text-secondary);
    font-weight: 750;
  }
}

.header-copy-button,
.toolbar-icon-button,
.detail-action-button,
.trace-link,
.execution-step > button,
.approval-meta button {
  appearance: none;
  border: 0;
  font: inherit;
  cursor: pointer;
}

.header-copy-button {
  display: inline-grid;
  place-items: center;
  width: 24px;
  height: 24px;
  padding: 0;
  border-radius: var(--radius-sm);
  color: var(--text-muted);
  background: transparent;

  &:hover:not(:disabled) {
    color: var(--brand-primary);
    background: rgb(var(--brand-primary-rgb) / 0.08);
  }

  &:disabled {
    cursor: not-allowed;
    opacity: 0.45;
  }
}

.debug-body {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 14px var(--insight-width, 312px);
  gap: 0;
  min-height: 0;
  flex: 1 1 auto;
}

.glass-surface-panel {
  border: 1px solid var(--border-subtle);
  background: var(--surface-glass-panel);
  box-shadow: var(--shadow-panel);
  backdrop-filter: blur(20px) saturate(1.08);
}

.chat-panel,
.detail-panel {
  min-width: 0;
  min-height: 0;
  overflow: hidden;
  border-radius: var(--radius-xl);
}

.chat-panel {
  display: flex;
  flex-direction: column;
  background: transparent;
}

.chat-conversation {
  flex: 1 1 auto;
  min-height: 0;
  border: none;
  border-radius: 0;
  background: transparent;
  color: var(--text-primary);

  :deep(.reachai-message-list) {
    padding: 34px 32px 38px;
    scroll-behavior: smooth;
    scrollbar-width: none;
    -ms-overflow-style: none;
  }

  :deep(.reachai-message-list::-webkit-scrollbar) {
    display: none;
    width: 0;
    height: 0;
  }

  :deep(.reachai-message-list__empty) {
    padding: 0;
    color: inherit;
  }
}

.chat-toolbar {
  display: flex;
  flex: 0 0 64px;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 0 22px;
  border-bottom: 1px solid var(--border-divider);
  background: color-mix(in srgb, var(--surface-solid-panel) 68%, transparent);
  backdrop-filter: blur(18px);
}

.toolbar-title {
  gap: 10px;
  color: var(--text-primary);
  font-size: 16px;

  .el-icon {
    font-size: 20px;
  }
}

.toolbar-actions {
  gap: 10px;
}

.run-state {
  gap: 7px;
  color: var(--status-success);
  font-size: 12px;
  font-weight: 750;

  i {
    width: 8px;
    height: 8px;
    border-radius: 50%;
    background: currentColor;
    box-shadow: 0 0 0 5px color-mix(in srgb, currentColor 12%, transparent);
  }

  &.active {
    color: var(--brand-primary);

    i {
      animation: status-pulse 1.8s ease-out infinite;
    }
  }
}

.toolbar-icon-button {
  display: inline-grid;
  place-items: center;
  width: 36px;
  height: 36px;
  padding: 0;
  border: 1px solid var(--border-subtle);
  border-radius: 12px;
  color: var(--text-secondary);
  background: color-mix(in srgb, var(--surface-solid-control) 72%, transparent);
  box-shadow: var(--inner-highlight);
  transition: transform 0.16s ease, border-color 0.16s ease, color 0.16s ease, background 0.16s ease;

  &:hover:not(:disabled) {
    transform: translateY(-1px);
    border-color: var(--border-focus);
    color: var(--brand-primary);
    background: var(--surface-solid-control);
  }

  &:disabled {
    cursor: not-allowed;
    opacity: 0.38;
  }

  &:focus-visible {
    outline: 2px solid var(--border-focus);
    outline-offset: 2px;
  }
}

.stop-button,
.clear-button {
  color: var(--status-danger);

  &:hover:not(:disabled) {
    border-color: color-mix(in srgb, var(--status-danger) 38%, var(--border-subtle));
    color: var(--status-danger);
    background: color-mix(in srgb, var(--status-danger) 7%, var(--surface-solid-control));
  }
}

.chat-empty {
  position: relative;
  z-index: 1;
  display: flex;
  height: 100%;
  min-height: 320px;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  color: var(--text-muted);
  text-align: center;

  h3 {
    margin: 18px 0 7px;
    color: var(--text-primary);
    font-size: 19px;
    font-weight: 800;
  }

  p {
    max-width: 420px;
    margin: 0;
    font-size: 13px;
    line-height: 1.7;
  }
}

.empty-orbit {
  display: grid;
  width: 80px;
  height: 80px;
  place-items: center;
  border: 1px solid rgb(var(--brand-primary-rgb) / 0.14);
  border-radius: 50%;
  background: var(--surface-glass-control);
  box-shadow: 0 18px 40px rgb(var(--brand-primary-rgb) / 0.16), inset 0 1px 0 rgb(255 255 255 / 0.82);
  backdrop-filter: blur(14px);

  img {
    width: 64px;
    height: 64px;
    border-radius: 50%;
    object-fit: cover;
  }
}

.message-meta {
  gap: 7px;
  padding: 0;
  border-top: 0;
  flex-wrap: wrap;
}

.tool-chip,
.elapsed-chip,
.trace-link {
  gap: 6px;
  min-height: 25px;
  padding: 3px 9px;
  border-radius: 999px;
  font-size: 11px;
  font-weight: 700;
}

.tool-chip {
  color: color-mix(in srgb, var(--status-success) 82%, var(--text-primary));
  background: color-mix(in srgb, var(--status-success) 10%, var(--surface-glass-control));
}

.elapsed-chip {
  color: var(--text-muted);
  background: var(--surface-hover);
}

.trace-link {
  margin-left: auto;
  color: var(--brand-primary);
  background: rgb(var(--brand-primary-rgb) / 0.06);

  &:hover {
    background: rgb(var(--brand-primary-rgb) / 0.12);
  }
}

.chat-input {
  /* 玻璃材质由 ConversationView :deep(.chat-input) 提供；此处只保留布局 */
  flex: 0 0 auto;
  margin: 0;
  padding: var(--reachai-chat-composer-padding, 12px 16px 14px);
}

.input-composer {
  padding: 2px 4px 0;
  border: 0;
  border-radius: 0;
  background: transparent;
  box-shadow: none;

  :deep(.el-textarea__inner) {
    min-height: 56px !important;
    padding: 10px 4px;
    border: 0;
    border-radius: 0;
    color: var(--reachai-chat-text, var(--text-primary, var(--text-primary)));
    background: transparent;
    box-shadow: none;
    font-size: 13px;
    font-weight: 500;
    line-height: 1.6;
    resize: none;

    &:focus {
      box-shadow: none;
    }

    &::placeholder {
      color: var(--reachai-chat-text-muted, var(--text-muted));
      opacity: 1;
    }
  }
}

.input-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  padding: 9px 2px 0;
  border-top: 1px solid var(--border-divider);
}

.input-hint {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  color: var(--text-muted);
  font-size: 11px;
  font-weight: 650;

  kbd {
    min-width: 22px;
    padding: 2px 5px;
    border: 1px solid color-mix(in srgb, var(--border-readable) 55%, transparent);
    border-bottom-color: color-mix(in srgb, var(--text-muted) 34%, transparent);
    border-radius: 5px;
    color: var(--text-secondary);
    background: var(--surface-glass-control);
    box-shadow: 0 1px 0 rgb(15 23 42 / 0.05);
    font-family: inherit;
    font-size: 10px;
    font-weight: 760;
    line-height: 1.25;
    text-align: center;
  }

  b {
    color: var(--text-muted);
    font-size: 11px;
  }

  em {
    margin-left: 2px;
    font-style: normal;
  }
}

.send-button {
  min-width: 102px;
  min-height: 38px;
  border: 0;
  border-radius: 12px;
  background: linear-gradient(135deg, var(--brand-hover), var(--brand-primary));
  box-shadow:
    0 10px 22px rgb(var(--brand-primary-rgb) / 0.22),
    inset 0 1px 0 rgb(255 255 255 / 0.24);
  font-weight: 730;
  transition: transform 0.16s ease, box-shadow 0.16s ease, filter 0.16s ease;

  &:hover:not(.is-disabled) {
    filter: brightness(1.04);
    box-shadow:
      0 13px 26px rgb(var(--brand-primary-rgb) / 0.30),
      inset 0 1px 0 rgb(255 255 255 / 0.28);
    transform: translateY(-1px);
  }

  &.is-disabled {
    background: linear-gradient(
      135deg,
      color-mix(in srgb, var(--brand-hover) 58%, transparent),
      color-mix(in srgb, var(--brand-primary) 58%, transparent)
    );
    box-shadow: none;
    opacity: 0.72;
  }
}

.workbench-resizer {
  position: relative;
  z-index: 4;
  display: grid;
  min-width: 14px;
  place-items: center;
  cursor: col-resize;
  touch-action: none;
  user-select: none;

  &::before {
    content: '';
    position: absolute;
    top: 16px;
    bottom: 16px;
    left: 50%;
    width: 1px;
    background: color-mix(in srgb, var(--border-readable) 52%, transparent);
    opacity: 0.46;
    transition: width 0.16s ease, opacity 0.16s ease, background 0.16s ease;
  }

  &:hover::before,
  &:focus-visible::before,
  .is-resizing &::before {
    width: 3px;
    background: color-mix(in srgb, var(--brand-primary) 72%, transparent);
    opacity: 0.85;
  }

  &:focus-visible {
    outline: 2px solid var(--border-focus);
    outline-offset: -2px;
  }
}

.resizer-grip {
  position: relative;
  z-index: 1;
  display: grid;
  width: 22px;
  height: 48px;
  place-items: center;
  border: 1px solid var(--border-subtle);
  border-radius: 999px;
  color: var(--text-muted);
  background: color-mix(in srgb, var(--surface-solid-control) 74%, transparent);
  box-shadow: var(--inner-highlight);
  opacity: 0.72;

  .el-icon {
    transform: scaleX(0.72);
  }
}

.detail-panel {
  position: relative;
  display: flex;
  flex-direction: column;
  isolation: isolate;
  background:
    radial-gradient(circle at 10% 6%, var(--agent-card-anchor-glow), transparent 42%),
    radial-gradient(circle at 94% 14%, var(--agent-card-rose-glow), transparent 40%),
    radial-gradient(circle at 72% 100%, var(--agent-card-cool-glow), transparent 46%),
    var(--agent-atmosphere-theme-wash),
    linear-gradient(155deg, var(--surface-glass-control), rgb(var(--brand-selected-rgb) / 0.34) 58%, var(--surface-glass-control));
}

.detail-panel::before {
  content: '';
  position: absolute;
  inset: 0;
  z-index: 0;
  pointer-events: none;
  border-radius: inherit;
  background:
    linear-gradient(180deg, var(--agent-atmosphere-wash-top), var(--agent-atmosphere-wash-bottom)),
    linear-gradient(180deg, transparent 42%, var(--agent-atmosphere-bottom-tint));
  opacity: 0.72;
}

.detail-panel > * {
  position: relative;
  z-index: 1;
}

.detail-header {
  display: flex;
  flex: 0 0 auto;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  padding: 20px 20px 14px;

  > div:first-child {
    min-width: 0;
  }

  h3 {
    margin: 5px 0 0;
    color: var(--text-primary);
    font-size: 19px;
    font-weight: 830;
  }
}

.toolbar-label {
  display: block;
  color: color-mix(in srgb, var(--brand-primary) 42%, var(--text-muted));
  font-size: 10px;
  font-weight: 800;
  letter-spacing: 0.10em;
  text-transform: uppercase;
}

.detail-header-actions {
  flex: 0 1 auto;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 7px;
  min-width: 0;
}

.detail-action-button {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  max-width: 100%;
  min-width: 0;
  height: 32px;
  padding: 0 10px;
  border: 1px solid rgb(var(--brand-primary-rgb) / 0.12);
  border-radius: 10px;
  color: var(--text-secondary);
  background: color-mix(in srgb, rgb(var(--brand-selected-rgb) / 0.55) 48%, var(--surface-solid-control));
  box-shadow: var(--inner-highlight);
  font-size: 12px;
  font-weight: 750;
  white-space: nowrap;
  transition:
    transform var(--motion-duration-fast, 0.16s) var(--motion-easing-standard, ease),
    border-color var(--motion-duration-fast, 0.16s) var(--motion-easing-standard, ease),
    color var(--motion-duration-fast, 0.16s) var(--motion-easing-standard, ease),
    background var(--motion-duration-fast, 0.16s) var(--motion-easing-standard, ease);

  span {
    overflow: hidden;
    text-overflow: ellipsis;
  }

  &:hover:not(:disabled) {
    transform: translateY(-1px);
    border-color: var(--border-focus);
    color: var(--brand-primary);
    background: var(--surface-solid-control);
  }

  &:disabled {
    cursor: not-allowed;
    opacity: 0.38;
  }

  &:focus-visible {
    outline: 2px solid var(--border-focus);
    outline-offset: 2px;
  }
}

.insight-summary {
  flex: 0 0 auto;
  gap: 12px;
  padding: 0 20px 14px;
  border-bottom: 1px solid color-mix(in srgb, var(--brand-primary) 12%, var(--border-divider));

  div {
    display: flex;
    align-items: baseline;
    gap: 5px;
  }

  strong {
    color: var(--brand-primary);
    font-size: 22px;
    font-weight: 860;
  }

  span {
    color: var(--text-muted);
    font-size: 11px;
    font-weight: 700;
  }

  i {
    width: 1px;
    height: 20px;
    background: color-mix(in srgb, var(--brand-primary) 18%, var(--border-divider));
  }
}

.detail-tabs {
  display: flex;
  min-height: 0;
  flex: 1 1 auto;
  flex-direction: column;
  margin: 12px 14px 0;

  :deep(.el-tabs__header) {
    margin: 0 0 12px;
  }

  :deep(.el-tabs__content),
  :deep(.el-tab-pane) {
    min-height: 0;
    height: 100%;
  }

  :deep(.el-tabs__content) {
    overflow-y: auto;
    overscroll-behavior: contain;
    scrollbar-width: none;
    -ms-overflow-style: none;
  }

  :deep(.el-tabs__content::-webkit-scrollbar) {
    display: none;
    width: 0;
    height: 0;
  }
}

.tab-count {
  display: inline-grid;
  min-width: 20px;
  height: 20px;
  margin-left: 5px;
  place-items: center;
  border-radius: 999px;
  color: var(--brand-primary);
  background: rgb(var(--brand-primary-rgb) / 0.10);
  font-size: 10px;
}

.approval-policy {
  flex: 0 0 auto;
  gap: 10px;
  margin: 10px 14px 14px;
  padding: 12px 13px;
  border: 1px solid color-mix(in srgb, var(--status-success) 18%, rgb(var(--brand-primary-rgb) / 0.12));
  border-radius: 14px;
  color: var(--status-success);
  background:
    linear-gradient(145deg, var(--surface-glass-control), rgb(var(--brand-selected-rgb) / 0.26)),
    color-mix(in srgb, var(--status-success) 6%, transparent);

  &.has-pending {
    border-color: color-mix(in srgb, var(--status-warning) 28%, rgb(var(--brand-primary-rgb) / 0.12));
    color: var(--status-warning);
    background:
      linear-gradient(145deg, var(--surface-glass-control), rgb(var(--brand-selected-rgb) / 0.26)),
      color-mix(in srgb, var(--status-warning) 7%, transparent);
  }

  .el-icon {
    flex: 0 0 auto;
    font-size: 18px;
  }

  div {
    display: flex;
    min-width: 0;
    flex-direction: column;
    gap: 2px;
  }

  strong {
    color: var(--text-secondary);
    font-size: 11px;
    font-weight: 760;
  }

  span {
    color: var(--text-muted);
    font-size: 9px;
  }
}

@keyframes icon-spin {
  to { transform: rotate(1turn); }
}

@keyframes status-pulse {
  0% { box-shadow: 0 0 0 0 currentColor; }

  70% { box-shadow: 0 0 0 7px transparent; }

  100% { box-shadow: 0 0 0 0 transparent; }
}

@media (max-width: 1080px) {
  .agent-debug-page {
      height: auto;
      min-height: 100%;
      overflow: visible;
    }

  .debug-body {
      grid-template-columns: minmax(0, 1fr);
      gap: var(--layout-page-gap);
    }

  .workbench-resizer {
      display: none;
    }

  .chat-panel {
      min-height: 700px;
    }

  .detail-panel {
      min-height: 560px;
    }
}

@media (max-width: 720px) {
  .agent-header-meta {
      gap: 7px;
      flex-wrap: wrap;
    }

  .chat-toolbar {
      padding: 0 16px;
    }

  .chat-conversation :deep(.reachai-message-list) {
      padding: 24px 15px 30px;
    }

  .message-meta {
      margin-right: 0;
      margin-left: 0;
      padding-right: 15px;
      padding-left: 15px;
    }

  .trace-link {
      margin-left: 0;
    }

  .chat-input {
      padding-right: 12px;
      padding-left: 12px;
    }
}

@media (prefers-reduced-motion: reduce) {
  .spin-icon,
    .run-state.active i,
    .execution-state.is-running i {
      animation: none !important;
    }

  .toolbar-icon-button,
    .detail-action-button {
      transition: none !important;
    }

  .toolbar-icon-button:hover:not(:disabled),
    .detail-action-button:hover:not(:disabled) {
      transform: none;
    }

  .chat-conversation :deep(.reachai-message-list) {
      scroll-behavior: auto;
    }
}
</style>
