<template>
  <main class="agent-debug-page density-comfortable">
    <PageHeader
      class="agent-debug-header"
      variant="entity"
      domain="agent"
      :title="agentName || 'Agent 调试台'"
    >
      <template #leading>
        <div class="app-page-header__entity-mark" aria-hidden="true">
          <el-icon><Cpu /></el-icon>
        </div>
      </template>
      <template #tags>
        <StatusTag :label="agentConfigStatusLabel" :tone="agentConfigStatusTone" />
      </template>
      <template #meta>
        <div class="agent-header-meta">
          <span>{{ agent?.runtimeType || 'Supervisor Agent' }}</span>
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
      <template #actions>
        <img class="agent-header-prism" src="/agent-debug/agent-prism.png" alt="" aria-hidden="true" />
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
            <span class="run-state" :class="{ active: streaming || sending }">
              <i />{{ streaming || sending ? '执行中' : '就绪' }}
            </span>
            <el-tooltip v-if="streaming" content="停止执行" placement="top">
              <button class="toolbar-icon-button stop-button" type="button" aria-label="停止执行" @click="stopExecution">
                <el-icon><CircleClose /></el-icon>
              </button>
            </el-tooltip>
            <el-tooltip content="清除对话" placement="top">
              <button
                class="toolbar-icon-button clear-button"
                type="button"
                aria-label="清除对话"
                :disabled="streaming || sending || (!messages.length && !sessionId)"
                @click="handleClearSession"
              >
                <el-icon><Delete /></el-icon>
              </button>
            </el-tooltip>
          </div>
        </div>

        <div ref="messagesRef" class="chat-messages" aria-live="polite">
          <div v-if="messages.length === 0" class="chat-empty">
            <div class="empty-orbit">
              <img src="/agent-debug/agent-prism.png" alt="" />
            </div>
            <h3>开始一次 Agent 调试</h3>
            <p>输入问题，观察 Supervisor 的理解、规划、Workflow 选择与最终回答。</p>
          </div>

          <template v-for="(msg, index) in messages" :key="msg.id">
            <article v-if="msg.role === 'user'" class="message-item user-message">
              <div class="user-message-content">
                <span class="message-role">你</span>
                <div class="user-message-bubble">{{ msg.content }}</div>
              </div>
              <div class="user-avatar" aria-hidden="true">
                <el-icon><User /></el-icon>
              </div>
            </article>

            <article
              v-else
              class="agent-message"
              :class="{ 'is-thinking': isThinkingMessage(msg, index) }"
            >
              <div class="agent-avatar" aria-hidden="true">
                <img src="/agent-debug/agent-prism.png" alt="" />
              </div>

              <div
                v-if="isThinkingMessage(msg, index)"
                class="agent-card-shell thinking-card-shell"
              >
                <div class="agent-card thinking-card">
                  <div class="agent-card-heading">
                    <div class="agent-card-title thinking-title">
                      <el-icon><MagicStick /></el-icon>
                      <strong>思考中…</strong>
                      <span class="thinking-dots" aria-hidden="true"><i /><i /><i /></span>
                    </div>
                  </div>
                  <div class="thinking-summary-row">
                    <span class="thinking-summary">{{ currentThinkingSummary }}</span>
                    <button
                      class="thinking-toggle"
                      type="button"
                      :disabled="!displaySteps.length"
                      :aria-expanded="thinkingExpanded"
                      :aria-label="thinkingExpanded ? '收起本轮推理' : '展开本轮推理'"
                      @click="thinkingExpanded = !thinkingExpanded"
                    >
                      <span>本轮推理</span>
                      <b>{{ displaySteps.length }} 步</b>
                      <span class="thinking-toggle-icon">
                        <el-icon><ArrowDown /></el-icon>
                      </span>
                    </button>
                  </div>
                  <p v-if="msg.content" class="stream-preview">{{ msg.content }}</p>
                  <ol v-if="thinkingExpanded && displaySteps.length" class="thinking-step-list">
                    <li
                      v-for="(step, stepIndex) in displaySteps"
                      :key="`${step.name}-${stepIndex}`"
                      class="thinking-step"
                      :class="`is-${thinkingStepState(stepIndex)}`"
                    >
                      <span class="thinking-step-marker">
                        <el-icon v-if="thinkingStepState(stepIndex) === 'complete'"><Check /></el-icon>
                        <el-icon v-else-if="thinkingStepState(stepIndex) === 'active'" class="spin-icon"><Loading /></el-icon>
                        <template v-else>{{ stepIndex + 1 }}</template>
                      </span>
                      <span class="thinking-step-copy">
                        <strong>{{ formatStepName(step.name, stepIndex) }}</strong>
                        <small>{{ summarizeStepDetail(step.detail) }}</small>
                      </span>
                      <span class="thinking-step-state">{{ thinkingStepStateLabel(stepIndex) }}</span>
                    </li>
                  </ol>
                </div>
              </div>

              <div
                v-else
                class="agent-card-shell response-card-shell"
                :class="`is-${msg.debugStatus || 'complete'}`"
              >
                <div class="agent-card response-card" :class="`is-${msg.debugStatus || 'complete'}`">
                  <div class="agent-card-heading">
                    <div class="agent-card-title">
                      <el-icon><MagicStick /></el-icon>
                      <strong>{{ msg.role === 'system' ? '系统' : (agentName || 'Agent') }}</strong>
                    </div>
                    <StatusTag
                      :label="responseStatusLabel(msg)"
                      :tone="responseStatusTone(msg)"
                    />
                  </div>
                  <div class="response-content">{{ msg.content }}</div>

                  <DynamicInteraction
                    v-if="msg.uiRequest"
                    class="interaction-card"
                    :payload="msg.uiRequest"
                    @action="(act, vals) => handleUiAction(msg.uiRequest!, act, vals)"
                  />

                  <div v-if="msg.toolCalls?.length || msg.elapsedMs || msg.traceId" class="message-meta">
                    <span v-for="tool in msg.toolCalls" :key="tool" class="tool-chip">
                      <el-icon><Tools /></el-icon>{{ tool }}
                    </span>
                    <span v-if="msg.elapsedMs" class="elapsed-chip">
                      <el-icon><Timer /></el-icon>{{ formatElapsed(msg.elapsedMs) }}
                    </span>
                    <button v-if="msg.traceId" class="trace-link" type="button" @click="openTrace(msg.traceId)">
                      <el-icon><Connection /></el-icon>查看 Trace
                    </button>
                  </div>
                </div>
              </div>
            </article>
          </template>
        </div>

        <form class="chat-input" @submit.prevent="handleSend">
          <el-input
            v-model="inputMessage"
            type="textarea"
            :autosize="{ minRows: 2, maxRows: 5 }"
            placeholder="输入消息"
            resize="none"
            :disabled="streaming || sending"
            aria-label="调试消息"
            @keydown="handleKeydown"
          />
          <div class="input-actions">
            <span class="input-hint">Ctrl + Enter 发送</span>
            <el-button
              class="send-button"
              :icon="streaming || sending ? Loading : Promotion"
              type="primary"
              native-type="submit"
              :loading="streaming || sending"
              :disabled="streaming || sending || !inputMessage.trim()"
            >
              {{ streaming || sending ? '思考中' : '发送' }}
            </el-button>
          </div>
        </form>
      </main>

      <div
        class="workbench-resizer"
        role="separator"
        aria-label="调整对话与执行洞察宽度"
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
            <span class="toolbar-label">Supervisor Runtime</span>
            <h3>执行洞察</h3>
          </div>
          <div class="detail-header-actions">
            <el-tooltip content="查看本轮 Trace" placement="top">
              <button class="detail-icon-button" type="button" :disabled="!latestTraceId" @click="openTrace(latestTraceId)">
                <el-icon><Connection /></el-icon>
              </button>
            </el-tooltip>
            <el-tooltip content="查看运行元数据" placement="top">
              <button class="detail-icon-button" type="button" :disabled="!lastAgentResult?.metadata" @click="metadataDrawerVisible = true">
                <el-icon><Operation /></el-icon>
              </button>
            </el-tooltip>
          </div>
        </div>

        <div class="insight-summary">
          <div><strong>{{ displaySteps.length }}</strong><span>个步骤</span></div>
          <i aria-hidden="true" />
          <div><span>人工审批</span><strong>{{ pendingApprovals.length }}</strong></div>
        </div>

        <el-tabs v-model="activeTab" class="detail-tabs" stretch>
          <el-tab-pane name="supervisor">
            <template #label>执行过程 <b class="tab-count">{{ displaySteps.length }}</b></template>
            <div class="execution-panel">
              <div class="execution-state" :class="`is-${executionPhase}`">
                <i />{{ executionStatusText }}
              </div>

              <div class="metric-grid">
                <div class="metric-card">
                  <span>规划 / 重规划</span>
                  <strong>{{ lastAgentResult?.metadata?.planCount || 0 }} / {{ lastAgentResult?.metadata?.replanCount || 0 }}</strong>
                </div>
                <div class="metric-card">
                  <span>Workflow 调用</span>
                  <strong>{{ lastAgentResult?.metadata?.workflowCallCount || 0 }}</strong>
                </div>
              </div>

              <div v-if="!displaySteps.length" class="empty-detail compact">
                <el-icon><DataLine /></el-icon>
                <p>发送消息后查看 Supervisor 执行链路</p>
              </div>
              <div v-else class="execution-step-list">
                <article
                  v-for="(step, stepIndex) in displaySteps"
                  :key="`${step.name}-${stepIndex}`"
                  class="execution-step"
                  :class="[`is-${insightStepState(stepIndex)}`, { 'is-open': selectedStepIndex === stepIndex }]"
                >
                  <button type="button" @click="selectedStepIndex = selectedStepIndex === stepIndex ? -1 : stepIndex">
                    <span class="execution-step-marker">
                      <el-icon v-if="insightStepState(stepIndex) === 'complete'"><Check /></el-icon>
                      <el-icon v-else-if="insightStepState(stepIndex) === 'active'" class="spin-icon"><Loading /></el-icon>
                      <el-icon v-else-if="insightStepState(stepIndex) === 'error'"><CircleClose /></el-icon>
                      <template v-else>{{ stepIndex + 1 }}</template>
                    </span>
                    <span class="execution-step-copy">
                      <strong>{{ formatStepName(step.name, stepIndex) }}</strong>
                      <small>{{ step.name }}</small>
                    </span>
                    <el-icon class="step-chevron"><ArrowDown /></el-icon>
                  </button>
                  <p v-if="selectedStepIndex === stepIndex">{{ formatStepDetail(step.detail) }}</p>
                </article>
              </div>
            </div>
          </el-tab-pane>

          <el-tab-pane name="approvals">
            <template #label>人工审批 <b class="tab-count">{{ pendingApprovals.length }}</b></template>
            <div class="approval-panel">
              <div class="approval-toolbar">
                <span>待处理审批</span>
                <el-button size="small" :loading="approvalLoading" @click="loadPendingApprovals">刷新</el-button>
              </div>
              <div v-if="!pendingApprovals.length" class="empty-detail compact">
                <el-icon><CircleCheck /></el-icon>
                <p>暂无待处理审批</p>
              </div>
              <div v-else class="approval-list">
                <article v-for="item in pendingApprovals" :key="item.interactionId" class="approval-item">
                  <div class="approval-title">
                    <strong>{{ item.title || item.nodeId || '人工审批' }}</strong>
                    <StatusTag :label="item.status" tone="warning" />
                  </div>
                  <p v-if="item.message" class="approval-message">{{ item.message }}</p>
                  <div class="approval-meta">
                    <span>{{ item.nodeId }}</span>
                    <button v-if="item.traceId" type="button" @click="openTrace(item.traceId)">查看 Trace</button>
                  </div>
                  <DynamicInteraction
                    v-if="item.uiRequest"
                    class="interaction-card"
                    :payload="item.uiRequest"
                    @action="(act, vals) => handlePendingApprovalAction(item, act, vals)"
                  />
                </article>
              </div>
            </div>
          </el-tab-pane>
        </el-tabs>

        <div class="approval-policy" :class="{ 'has-pending': pendingApprovals.length > 0 }">
          <el-icon><Lock /></el-icon>
          <div>
            <strong>{{ pendingApprovals.length ? `存在 ${pendingApprovals.length} 条待处理审批` : '当前无需人工审批' }}</strong>
            <span>{{ pendingApprovals.length ? '切换到人工审批后处理' : '本轮请求未触发审批策略' }}</span>
          </div>
        </div>
      </aside>
    </section>

    <AppDrawer
      v-model="traceDrawerVisible"
      :title="`Trace 回放 · ${activeTraceId}`"
      description="查看本轮 Agent 调试的结构化执行节点与耗时。"
      size="min(720px, 92vw)"
    >
      <TraceTimeline :nodes="traceNodes" />
      <template #footer>
        <el-button @click="traceDrawerVisible = false">关闭</el-button>
        <el-button type="primary" :disabled="!activeTraceId" @click="openRunOps">在 RunOps 中查看</el-button>
      </template>
    </AppDrawer>

    <AppDrawer
      v-model="metadataDrawerVisible"
      title="运行元数据"
      description="Supervisor 本轮规划、Workflow 选择与运行时返回的原始元数据。"
      size="min(620px, 92vw)"
    >
      <pre class="metadata-json">{{ JSON.stringify(lastAgentResult?.metadata || {}, null, 2) }}</pre>
    </AppDrawer>
  </main>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  ArrowDown,
  Check,
  ChatDotRound,
  CircleCheck,
  CircleClose,
  Connection,
  CopyDocument,
  Cpu,
  DArrowLeft,
  DataLine,
  Delete,
  Loading,
  Lock,
  MagicStick,
  Operation,
  Promotion,
  Timer,
  Tools,
  User,
} from '@element-plus/icons-vue'
import AppDrawer from '@/components/common/AppDrawer.vue'
import PageHeader from '@/components/common/PageHeader.vue'
import StatusTag from '@/components/common/StatusTag.vue'
import type { StatusTone } from '@/components/common/glassWorkbench'
import TraceTimeline from '@/components/TraceTimeline.vue'
import DynamicInteraction from '@/components/interaction/DynamicInteraction.vue'
import type { ChatMessage, ChatRequest } from '@/types/chat'
import type { UiRequestPayload } from '@/types/interaction'
import type { AgentResult, PendingHumanApproval, StepRecord } from '@/types/agent'
import type { TraceNode } from '@/types/trace'
import type { Agent } from '@/types/workflow'
import { getAgent } from '@/api/workflow'
import {
  clearAgentSession,
  executeAgentStream,
  listPendingHumanApprovals,
  submitHumanApproval,
} from '@/api/agent'
import { getTraceDetail } from '@/api/trace'

const route = useRoute()
const router = useRouter()
const agentId = route.params.id as string

type ExecutionPhase = 'idle' | 'running' | 'success' | 'error' | 'stopped'
type DebugMessageStatus = 'running' | 'complete' | 'error' | 'stopped'
type StepState = 'complete' | 'active' | 'error' | 'pending'

interface DebugChatMessage extends ChatMessage {
  debugStatus?: DebugMessageStatus
  elapsedMs?: number
}

const INSIGHT_WIDTH_STORAGE_KEY = 'reachai-agent-debug-insight-width'
const DEFAULT_INSIGHT_WIDTH = 312
const MIN_CONVERSATION_WIDTH = 460
const RESIZER_WIDTH = 14
const RESIZE_BREAKPOINT = 1080
const MIN_INSIGHT_WIDTH = 260
const MAX_INSIGHT_WIDTH = 640

const agent = ref<Agent | null>(null)
const agentName = ref('')
const sessionId = ref('')
const inputMessage = ref('')
const messages = ref<DebugChatMessage[]>([])
const sending = ref(false)
const streaming = ref(false)
const activeTab = ref('supervisor')
const messagesRef = ref<HTMLElement>()
const debugBodyRef = ref<HTMLElement>()
const lastAgentResult = ref<AgentResult | null>(null)
const liveSteps = ref<StepRecord[]>([])
const traceDrawerVisible = ref(false)
const metadataDrawerVisible = ref(false)
const activeTraceId = ref('')
const traceNodes = ref<TraceNode[]>([])
const pendingApprovals = ref<PendingHumanApproval[]>([])
const approvalLoading = ref(false)
const executionPhase = ref<ExecutionPhase>('idle')
const thinkingExpanded = ref(false)
const selectedStepIndex = ref(-1)
const resizing = ref(false)
const insightWidth = ref(readStoredInsightWidth())

let activeAbortController: AbortController | null = null
let msgCounter = 0
let resizingActive = false

const displaySteps = computed(() => lastAgentResult.value?.steps?.length
  ? lastAgentResult.value.steps
  : liveSteps.value)
const executionStatusText = computed(() => ({
  idle: '等待开始调试',
  running: 'Supervisor 正在执行',
  success: 'Supervisor 执行完成',
  error: 'Supervisor 执行失败',
  stopped: 'Supervisor 执行已停止',
})[executionPhase.value])
const agentConfigStatusLabel = computed(() => {
  if (agent.value?.configStatus === 'ACTIVE') return '已发布'
  if (agent.value?.configStatus === 'DRAFT') return '草稿'
  if (agent.value?.configStatus === 'ARCHIVED') return '已归档'
  return agent.value?.enabled === false ? '已停用' : '未发布'
})
const agentConfigStatusTone = computed<StatusTone>(() => {
  if (agent.value?.configStatus === 'ACTIVE') return 'success'
  if (agent.value?.configStatus === 'DRAFT') return 'warning'
  if (agent.value?.enabled === false) return 'danger'
  return 'neutral'
})
const latestTraceId = computed(() => {
  for (let index = messages.value.length - 1; index >= 0; index -= 1) {
    if (messages.value[index].traceId) return messages.value[index].traceId || ''
  }
  return String(lastAgentResult.value?.metadata?.traceId || '')
})
const currentThinkingSummary = computed(() => {
  const step = displaySteps.value[displaySteps.value.length - 1]
  if (!step) return 'Supervisor 正在理解意图，并选择本轮需要的能力。'
  const detail = summarizeStepDetail(step.detail)
  return detail || `正在${formatStepName(step.name, displaySteps.value.length - 1)}`
})

function readStoredInsightWidth() {
  try {
    const value = Number(localStorage.getItem(INSIGHT_WIDTH_STORAGE_KEY))
    return Number.isFinite(value) ? Math.min(MAX_INSIGHT_WIDTH, Math.max(MIN_INSIGHT_WIDTH, value)) : DEFAULT_INSIGHT_WIDTH
  } catch {
    return DEFAULT_INSIGHT_WIDTH
  }
}

function createMsg(
  role: ChatMessage['role'],
  content: string,
  extra?: Partial<DebugChatMessage>,
): DebugChatMessage {
  return {
    id: `msg-${++msgCounter}`,
    role,
    content,
    timestamp: Date.now(),
    ...extra,
  }
}

function formatStepDetail(detail: unknown) {
  if (typeof detail === 'string') return detail
  try {
    return JSON.stringify(detail)
  } catch {
    return String(detail ?? '')
  }
}

function summarizeStepDetail(detail: unknown) {
  const text = formatStepDetail(detail).replace(/\s+/g, ' ').trim()
  if (!text) return '等待运行时返回步骤详情'
  return text.length > 96 ? `${text.slice(0, 96)}…` : text
}

function formatStepName(name: string, index: number) {
  const normalized = name.toLowerCase()
  if (normalized.includes('config')) return '读取已发布配置'
  if (normalized.includes('workflow') || normalized.includes('tool')) return '筛选 Workflow-as-Tool'
  if (normalized.includes('plan')) return '规划执行路径'
  if (normalized.includes('answer') || normalized.includes('final') || normalized.includes('respond')) return '生成最终回答'
  if (normalized.includes('agent') || normalized.includes('resolve')) return '识别 Agent 范围'
  return name || `步骤 ${index + 1}`
}

function thinkingStepState(index: number): Exclude<StepState, 'error'> {
  if (!streaming.value || index < displaySteps.value.length - 1) return 'complete'
  return index === displaySteps.value.length - 1 ? 'active' : 'pending'
}

function thinkingStepStateLabel(index: number) {
  const state = thinkingStepState(index)
  return state === 'complete' ? '已完成' : state === 'active' ? '进行中' : '等待中'
}

function insightStepState(index: number): StepState {
  if (executionPhase.value === 'running') {
    return index === displaySteps.value.length - 1 ? 'active' : 'complete'
  }
  if ((executionPhase.value === 'error' || executionPhase.value === 'stopped') && index === displaySteps.value.length - 1) {
    return 'error'
  }
  if (executionPhase.value === 'success' || index < displaySteps.value.length - 1) return 'complete'
  return 'pending'
}

function isThinkingMessage(message: DebugChatMessage, index: number) {
  return message.debugStatus === 'running'
    && index === messages.value.length - 1
    && (streaming.value || sending.value)
}

function responseStatusLabel(message: DebugChatMessage) {
  if (message.debugStatus === 'error') return '执行失败'
  if (message.debugStatus === 'stopped') return '已停止'
  return message.role === 'system' ? '系统消息' : '回答完成'
}

function responseStatusTone(message: DebugChatMessage): StatusTone {
  if (message.debugStatus === 'error') return 'danger'
  if (message.debugStatus === 'stopped') return 'warning'
  return message.role === 'system' ? 'info' : 'success'
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
  traceDrawerVisible.value = true
  try {
    const { data } = await getTraceDetail(traceId)
    traceNodes.value = data.nodes || []
  } catch {
    traceNodes.value = []
    ElMessage.error('加载 Trace 失败')
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

function scrollToBottom() {
  nextTick(() => {
    if (messagesRef.value) messagesRef.value.scrollTop = messagesRef.value.scrollHeight
  })
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
  if (event.ctrlKey && event.key === 'Enter') {
    event.preventDefault()
    handleSend()
  }
}

async function handleSend() {
  const message = inputMessage.value.trim()
  if (!message || streaming.value || sending.value) return
  messages.value.push(createMsg('user', message))
  inputMessage.value = ''
  scrollToBottom()
  await runAgentExecution({
    message,
    sessionId: sessionId.value || undefined,
    agentId,
    entryType: 'DEBUG',
  }, '执行失败，请重试')
}

async function runAgentExecution(request: ChatRequest, failureText: string): Promise<AgentResult | null> {
  const startedAt = performance.now()
  const placeholder = createMsg('assistant', '', { loading: true, debugStatus: 'running' })
  messages.value.push(placeholder)
  activeTab.value = 'supervisor'
  lastAgentResult.value = null
  liveSteps.value = []
  streaming.value = true
  executionPhase.value = 'running'
  thinkingExpanded.value = false
  selectedStepIndex.value = -1
  activeAbortController = new AbortController()
  scrollToBottom()

  try {
    const result = await executeAgentStream(request, {
      onEvent(streamEvent) {
        if (streamEvent.event !== 'supervisor.step' || !streamEvent.data || typeof streamEvent.data !== 'object') return
        const step = streamEvent.data as { name?: unknown; detail?: unknown }
        liveSteps.value.push({
          name: String(step.name || 'supervisor'),
          detail: step.detail,
        })
      },
      onMessageDelta(text) {
        placeholder.loading = false
        placeholder.content += text
        scrollToBottom()
      },
    }, activeAbortController.signal)
    placeholder.loading = false
    placeholder.content = placeholder.content || result.answer || '(无回答)'
    placeholder.traceId = (result.metadata?.traceId as string) || undefined
    placeholder.uiRequest = result.uiRequest
    placeholder.toolCalls = extractToolCalls(result)
    placeholder.elapsedMs = resolveElapsedMs(result, startedAt)
    placeholder.debugStatus = result.success ? 'complete' : 'error'
    sessionId.value = result.sessionId || sessionId.value
    lastAgentResult.value = result
    executionPhase.value = result.success ? 'success' : 'error'
    if (result.uiRequest?.component === 'confirm') await loadPendingApprovals()
    return result
  } catch (error) {
    placeholder.loading = false
    const executionError = error instanceof Error ? error : new Error(String(error))
    const stopped = executionError.name === 'AbortError'
    placeholder.content = stopped
      ? '执行已停止'
      : executionError.message
        ? `${failureText}：${executionError.message}`
        : failureText
    placeholder.debugStatus = stopped ? 'stopped' : 'error'
    placeholder.elapsedMs = resolveElapsedMs(null, startedAt)
    executionPhase.value = stopped ? 'stopped' : 'error'
    return null
  } finally {
    streaming.value = false
    activeAbortController = null
    scrollToBottom()
  }
}

function stopExecution() {
  activeAbortController?.abort()
}

async function handleUiAction(payload: UiRequestPayload, action: string, values: Record<string, unknown>) {
  await runAgentExecution({
    sessionId: sessionId.value || undefined,
    interactionId: payload.interactionId,
    uiSubmit: { action, values },
    entryType: 'DEBUG',
  }, '交互提交失败，请重试')
  await loadPendingApprovals()
}

async function handlePendingApprovalAction(
  approval: PendingHumanApproval,
  action: string,
  values: Record<string, unknown>,
) {
  const routeAction = values?.confirm === false ? 'reject' : action
  if (approval.interactionId.startsWith('spv_')) {
    await runAgentExecution({
      interactionId: approval.interactionId,
      uiSubmit: { action: routeAction, values },
      sessionId: sessionId.value || approval.sessionId || undefined,
      entryType: 'DEBUG',
    }, '审批提交失败，请重试')
    await loadPendingApprovals()
    return
  }

  sending.value = true
  try {
    const { data } = await submitHumanApproval(approval.interactionId, {
      action: routeAction,
      values,
      sessionId: sessionId.value || approval.sessionId || undefined,
    })
    messages.value.push(createMsg('assistant', data.answer || '审批已提交', {
      traceId: (data.metadata?.traceId as string) || undefined,
      uiRequest: data.uiRequest,
      toolCalls: extractToolCalls(data),
      debugStatus: data.success ? 'complete' : 'error',
    }))
    lastAgentResult.value = data
    executionPhase.value = data.success ? 'success' : 'error'
    await loadPendingApprovals()
    scrollToBottom()
  } catch {
    ElMessage.error('审批提交失败')
  } finally {
    sending.value = false
  }
}

async function handleClearSession() {
  if (streaming.value || sending.value) return
  try {
    if (sessionId.value) await clearAgentSession(sessionId.value)
    sessionId.value = ''
    messages.value = []
    liveSteps.value = []
    lastAgentResult.value = null
    executionPhase.value = 'idle'
    thinkingExpanded.value = false
    selectedStepIndex.value = -1
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
  loadPendingApprovals()
  nextTick(handleWindowResize)
  window.addEventListener('resize', handleWindowResize)
})

onBeforeUnmount(() => {
  activeAbortController?.abort()
  window.removeEventListener('resize', handleWindowResize)
})
</script>

<style scoped lang="scss">
.agent-debug-page {
  --agent-spectrum-anchor: var(--brand-primary);
  --agent-spectrum-cool: color-mix(in oklab, var(--brand-primary) 12%, #58dcff);
  --agent-spectrum-violet: color-mix(in oklab, var(--brand-primary) 18%, #9687ff);
  --agent-spectrum-rose: color-mix(in oklab, var(--brand-primary) 12%, #f5a2d8);
  --agent-spectrum-warm: color-mix(in oklab, var(--brand-primary) 10%, #ffc38b);
  --agent-atmosphere-art-opacity: 1;
  --agent-atmosphere-art-saturation: 1;
  --agent-atmosphere-wash-top: rgb(245 248 255 / 0.58);
  --agent-atmosphere-wash-bottom: rgb(238 244 255 / 0.32);
  --agent-atmosphere-glow-primary: rgb(100 220 255 / 0.10);
  --agent-atmosphere-glow-secondary: rgb(143 96 255 / 0.11);
  --agent-atmosphere-bottom-tint: transparent;
  --agent-atmosphere-theme-wash: linear-gradient(115deg, transparent, transparent);
  --agent-card-anchor-glow: color-mix(in srgb, var(--agent-spectrum-anchor) 14%, transparent);
  --agent-card-cool-glow: color-mix(in srgb, var(--agent-spectrum-cool) 15%, transparent);
  --agent-card-rose-glow: color-mix(in srgb, var(--agent-spectrum-rose) 14%, transparent);
  --agent-card-warm-glow: color-mix(in srgb, var(--agent-spectrum-warm) 12%, transparent);
  --agent-card-sweep-start: color-mix(in srgb, var(--agent-spectrum-anchor) 6%, transparent);
  --agent-card-sweep-cool: color-mix(in srgb, var(--agent-spectrum-cool) 8%, transparent);
  --agent-card-sweep-warm: color-mix(in srgb, var(--agent-spectrum-warm) 7%, transparent);
  --agent-card-sweep-rose: color-mix(in srgb, var(--agent-spectrum-rose) 9%, transparent);
  --agent-card-base: rgb(255 255 255 / 0.76);
  display: flex;
  box-sizing: border-box;
  width: 100%;
  height: 100%;
  min-width: 0;
  min-height: 0;
  flex-direction: column;
  gap: var(--layout-page-gap);
  overflow: hidden;
  padding: 0 var(--layout-content-inline) var(--layout-page-end);
}

:global(html[data-brand='metro-green']) .agent-debug-page {
  --agent-spectrum-anchor: color-mix(in oklab, var(--brand-primary) 62%, #67d7b5);
  --agent-spectrum-cool: color-mix(in oklab, var(--brand-primary) 22%, #73d7cb);
  --agent-spectrum-violet: color-mix(in oklab, var(--brand-primary) 24%, #9bcfc5);
  --agent-spectrum-rose: color-mix(in oklab, var(--brand-primary) 14%, #acdcca);
  --agent-spectrum-warm: color-mix(in oklab, var(--brand-primary) 12%, #ead9b1);
  --agent-atmosphere-art-opacity: 0.56;
  --agent-atmosphere-art-saturation: 0.03;
  --agent-atmosphere-wash-top: rgb(246 252 250 / 0.86);
  --agent-atmosphere-wash-bottom: rgb(231 248 241 / 0.68);
  --agent-atmosphere-glow-primary: rgb(74 203 166 / 0.2);
  --agent-atmosphere-glow-secondary: rgb(237 202 129 / 0.12);
  --agent-atmosphere-bottom-tint: rgb(145 226 203 / 0.18);
  --agent-atmosphere-theme-wash: linear-gradient(115deg, rgb(159 226 204 / 0.18), rgb(248 250 239 / 0.08) 50%, rgb(237 213 166 / 0.12));
  --agent-card-anchor-glow: rgb(43 159 122 / 0.2);
  --agent-card-cool-glow: rgb(83 207 190 / 0.22);
  --agent-card-rose-glow: rgb(154 219 196 / 0.14);
  --agent-card-warm-glow: rgb(232 211 169 / 0.14);
  --agent-card-sweep-start: rgb(43 159 122 / 0.1);
  --agent-card-sweep-cool: rgb(83 207 190 / 0.12);
  --agent-card-sweep-warm: rgb(232 211 169 / 0.1);
  --agent-card-sweep-rose: rgb(154 219 196 / 0.1);
  --agent-card-base: rgb(247 253 250 / 0.92);
}

:global(html[data-brand='solar-gold']) .agent-debug-page {
  --agent-spectrum-anchor: color-mix(in oklab, var(--brand-primary) 60%, #f1b75c);
  --agent-spectrum-cool: color-mix(in oklab, var(--brand-primary) 8%, #c8dde2);
  --agent-spectrum-violet: color-mix(in oklab, var(--brand-primary) 10%, #d8c9c2);
  --agent-spectrum-rose: color-mix(in oklab, var(--brand-primary) 10%, #efbda5);
  --agent-spectrum-warm: color-mix(in oklab, var(--brand-primary) 12%, #f5d58c);
  --agent-atmosphere-art-opacity: 0.52;
  --agent-atmosphere-art-saturation: 0.02;
  --agent-atmosphere-wash-top: rgb(255 252 245 / 0.88);
  --agent-atmosphere-wash-bottom: rgb(252 242 220 / 0.7);
  --agent-atmosphere-glow-primary: rgb(240 181 83 / 0.2);
  --agent-atmosphere-glow-secondary: rgb(239 177 153 / 0.14);
  --agent-atmosphere-bottom-tint: rgb(247 211 139 / 0.2);
  --agent-atmosphere-theme-wash: linear-gradient(115deg, rgb(247 211 139 / 0.18), rgb(255 248 230 / 0.1) 48%, rgb(239 177 153 / 0.14));
  --agent-card-anchor-glow: rgb(208 126 31 / 0.18);
  --agent-card-cool-glow: rgb(184 215 222 / 0.08);
  --agent-card-rose-glow: rgb(239 177 153 / 0.16);
  --agent-card-warm-glow: rgb(245 204 111 / 0.22);
  --agent-card-sweep-start: rgb(208 126 31 / 0.09);
  --agent-card-sweep-cool: rgb(184 215 222 / 0.06);
  --agent-card-sweep-warm: rgb(245 204 111 / 0.13);
  --agent-card-sweep-rose: rgb(239 177 153 / 0.1);
  --agent-card-base: rgb(255 251 243 / 0.93);
}

.agent-debug-header {
  --page-header-material: url('/agent-debug/conversation-atmosphere.png');
  --page-header-material-position: center 43%;
  flex: 0 0 auto;
  min-height: 112px;

  :deep(.app-page-header__entity-mark) {
    color: var(--brand-primary);
    background: color-mix(in srgb, var(--surface-solid-control) 76%, transparent);
    box-shadow: 0 12px 28px rgb(var(--brand-primary-rgb) / 0.14), var(--inner-highlight);
  }

  :deep(.app-page-header__action-dock) {
    padding: 0;
    border: 0;
    background: transparent;
    box-shadow: none;
    backdrop-filter: none;
  }
}

.agent-header-meta,
.toolbar-title,
.toolbar-actions,
.run-state,
.agent-card-title,
.thinking-summary-row,
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
.detail-icon-button,
.thinking-toggle,
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

.agent-header-prism {
  display: block;
  width: 94px;
  height: 94px;
  border-radius: 50%;
  object-fit: cover;
  filter: saturate(1.04) drop-shadow(0 12px 25px rgb(var(--brand-primary-rgb) / 0.22));
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

.toolbar-icon-button,
.detail-icon-button {
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

.chat-messages {
  position: relative;
  flex: 1 1 auto;
  min-height: 0;
  overflow-y: auto;
  overscroll-behavior: contain;
  scrollbar-width: none;
  -ms-overflow-style: none;
  padding: 34px 32px 38px;
  scroll-behavior: smooth;
  background-color: color-mix(in srgb, var(--surface-solid-page) 76%, transparent);
}

.chat-messages::-webkit-scrollbar {
  display: none;
  width: 0;
  height: 0;
}

.chat-messages::before {
  content: '';
  position: absolute;
  inset: 0;
  pointer-events: none;
  opacity: var(--agent-atmosphere-art-opacity);
  background-image:
    linear-gradient(180deg, var(--agent-atmosphere-wash-top), var(--agent-atmosphere-wash-bottom)),
    url('/agent-debug/conversation-atmosphere.png');
  background-position: center, center bottom;
  background-size: cover, 100% 100%;
  background-repeat: no-repeat;
  filter: saturate(var(--agent-atmosphere-art-saturation));
}

.chat-messages::after {
  content: '';
  position: absolute;
  inset: 0;
  pointer-events: none;
  background:
    var(--agent-atmosphere-theme-wash),
    radial-gradient(circle at 14% 28%, var(--agent-atmosphere-glow-primary), transparent 22%),
    radial-gradient(circle at 68% 62%, var(--agent-atmosphere-glow-secondary), transparent 30%),
    linear-gradient(180deg, transparent 38%, var(--agent-atmosphere-bottom-tint));
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
  background: rgb(255 255 255 / 0.56);
  box-shadow: 0 18px 40px rgb(var(--brand-primary-rgb) / 0.16), inset 0 1px 0 rgb(255 255 255 / 0.82);
  backdrop-filter: blur(14px);

  img {
    width: 64px;
    height: 64px;
    border-radius: 50%;
    object-fit: cover;
  }
}

.user-message,
.agent-message {
  position: relative;
  z-index: 1;
}

.user-message {
  display: flex;
  justify-content: flex-end;
  gap: 11px;
  margin: 0 0 30px auto;
}

.user-message-content {
  display: flex;
  max-width: min(650px, 70%);
  min-width: 0;
  flex-direction: column;
  align-items: flex-end;
}

.message-role {
  margin: 0 6px 7px;
  color: var(--text-muted);
  font-size: 11px;
  font-weight: 750;
}

.user-message-bubble {
  padding: 13px 18px;
  border-radius: 18px 18px 5px 18px;
  color: var(--text-inverse);
  background: linear-gradient(
    135deg,
    color-mix(in srgb, var(--brand-primary) 82%, var(--brand-hover)),
    var(--brand-primary) 48%,
    var(--brand-active)
  );
  box-shadow: 0 12px 26px rgb(var(--brand-primary-rgb) / 0.24);
  font-size: 14px;
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-word;
}

.user-avatar {
  display: grid;
  width: 38px;
  height: 38px;
  margin-top: 22px;
  flex: 0 0 auto;
  place-items: center;
  border: 1px solid rgb(var(--brand-primary-rgb) / 0.16);
  border-radius: 13px;
  color: var(--brand-primary);
  background: rgb(255 255 255 / 0.66);
  box-shadow: var(--inner-highlight);
  backdrop-filter: blur(14px);
}

.agent-message {
  display: grid;
  grid-template-columns: 60px minmax(0, min(820px, calc(100% - 76px)));
  gap: 15px;
  align-items: start;
  margin: 0 0 32px;
}

.agent-avatar {
  display: grid;
  width: 58px;
  height: 58px;
  place-items: center;
  border: 1px solid rgb(255 255 255 / 0.7);
  border-radius: 50%;
  background: rgb(255 255 255 / 0.48);
  box-shadow: 0 12px 30px rgb(var(--brand-primary-rgb) / 0.18), inset 0 0 0 5px rgb(255 255 255 / 0.30);
  backdrop-filter: blur(14px);

  img {
    width: 49px;
    height: 49px;
    border-radius: 50%;
    object-fit: cover;
  }
}

.agent-card-shell {
  position: relative;
  isolation: isolate;
  padding: 2px;
  overflow: hidden;
  border-radius: 26px;
  box-shadow:
    -18px 18px 48px color-mix(in srgb, var(--agent-spectrum-anchor) 14%, transparent),
    2px 22px 54px color-mix(in srgb, var(--agent-spectrum-cool) 12%, transparent),
    22px 18px 48px color-mix(in srgb, var(--agent-spectrum-rose) 12%, transparent);
}

.response-card-shell {
  background: linear-gradient(
    112deg,
    color-mix(in oklab, var(--agent-spectrum-anchor) 88%, var(--agent-spectrum-violet)) 0%,
    var(--agent-spectrum-cool) 28%,
    var(--agent-spectrum-warm) 62%,
    var(--agent-spectrum-rose) 81%,
    color-mix(in oklab, var(--agent-spectrum-anchor) 76%, var(--agent-spectrum-violet)) 100%
  );

  &.is-error {
    background: linear-gradient(112deg, rgb(255 133 151 / 0.82), rgb(222 89 135 / 0.78), rgb(255 181 116 / 0.72));
  }

  &.is-stopped {
    background: linear-gradient(112deg, rgb(255 195 112 / 0.74), rgb(165 140 255 / 0.75), rgb(103 193 255 / 0.68));
  }
}

.thinking-card-shell {
  padding: 2px;

  &::before {
    content: '';
    position: absolute;
    z-index: -1;
    width: 150%;
    aspect-ratio: 1;
    top: 50%;
    left: 50%;
    background: conic-gradient(
      from 210deg,
      var(--agent-spectrum-anchor),
      var(--agent-spectrum-cool) 22%,
      var(--agent-spectrum-violet) 39%,
      var(--agent-spectrum-rose) 58%,
      var(--agent-spectrum-warm) 75%,
      var(--agent-spectrum-cool) 89%,
      var(--agent-spectrum-anchor)
    );
    transform: translate(-50%, -50%);
    animation: thinking-rim 4.2s linear infinite;
  }

  &::after {
    content: '';
    position: absolute;
    z-index: -2;
    inset: -16px;
    border-radius: 34px;
    background: linear-gradient(
      112deg,
      color-mix(in srgb, var(--agent-spectrum-anchor) 22%, transparent),
      color-mix(in srgb, var(--agent-spectrum-cool) 22%, transparent) 30%,
      color-mix(in srgb, var(--agent-spectrum-violet) 18%, transparent) 48%,
      color-mix(in srgb, var(--agent-spectrum-rose) 20%, transparent) 70%,
      color-mix(in srgb, var(--agent-spectrum-warm) 18%, transparent)
    );
    filter: blur(20px);
    animation: aura-breathe 2.4s ease-in-out infinite alternate;
  }
}

.agent-card {
  position: relative;
  border-radius: 24px;
  color: var(--text-primary);
  background:
    radial-gradient(circle at 4% 4%, var(--agent-card-anchor-glow), transparent 43%),
    radial-gradient(circle at 42% -8%, var(--agent-card-cool-glow), transparent 46%),
    radial-gradient(circle at 102% 4%, var(--agent-card-rose-glow), transparent 44%),
    radial-gradient(circle at 78% 112%, var(--agent-card-warm-glow), transparent 46%),
    linear-gradient(
      116deg,
      var(--agent-card-sweep-start),
      var(--agent-card-sweep-cool) 35%,
      rgb(255 255 255 / 0.12) 52%,
      var(--agent-card-sweep-warm) 72%,
      var(--agent-card-sweep-rose)
    ),
    var(--agent-card-base);
  box-shadow: inset 0 1px 0 rgb(255 255 255 / 0.86);
  backdrop-filter: blur(28px) saturate(1.16);
}

.agent-card-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  padding: 18px 22px 0;
}

.agent-card-title {
  gap: 9px;
  min-width: 0;
  color: var(--text-primary);

  .el-icon {
    color: var(--brand-primary);
    font-size: 18px;
  }

  strong {
    overflow: hidden;
    font-size: 15px;
    font-weight: 820;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
}

.response-content {
  padding: 14px 22px 18px;
  color: var(--text-primary);
  font-size: 14px;
  line-height: 1.8;
  white-space: pre-wrap;
  word-break: break-word;
}

.interaction-card {
  margin: 0 22px 16px;
}

.message-meta {
  gap: 7px;
  padding: 12px 22px 16px;
  border-top: 1px solid rgb(115 133 172 / 0.12);
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
  background: color-mix(in srgb, var(--status-success) 10%, rgb(255 255 255 / 0.48));
}

.elapsed-chip {
  color: var(--text-muted);
  background: rgb(255 255 255 / 0.34);
}

.trace-link {
  margin-left: auto;
  color: var(--brand-primary);
  background: rgb(var(--brand-primary-rgb) / 0.06);

  &:hover {
    background: rgb(var(--brand-primary-rgb) / 0.12);
  }
}

.thinking-card {
  padding-bottom: 16px;
}

.thinking-title strong {
  font-size: 17px;
}

.thinking-dots {
  display: inline-flex;
  gap: 4px;
  align-items: center;

  i {
    width: 6px;
    height: 6px;
    border-radius: 50%;
    background: var(--brand-primary);
    animation: thinking-dot 1.3s ease-in-out infinite;

    &:nth-child(2) { animation-delay: 0.16s; }
    &:nth-child(3) { animation-delay: 0.32s; }
  }
}

.thinking-summary-row {
  justify-content: space-between;
  gap: 16px;
  padding: 14px 22px 8px;
}

.thinking-summary {
  min-width: 0;
  color: var(--text-muted);
  font-size: 12px;
  line-height: 1.6;
}

.thinking-toggle {
  display: inline-flex;
  min-height: 34px;
  flex: 0 0 auto;
  align-items: center;
  gap: 7px;
  padding: 4px 6px 4px 12px;
  border: 1px solid rgb(var(--brand-primary-rgb) / 0.10);
  border-radius: 999px;
  color: var(--brand-primary);
  background: rgb(255 255 255 / 0.48);
  box-shadow: inset 0 1px 0 rgb(255 255 255 / 0.82);
  transition: background 0.16s ease, transform 0.16s ease;

  span,
  b {
    font-size: 11px;
    font-weight: 750;
  }

  b {
    padding: 2px 6px;
    border-radius: 999px;
    background: rgb(var(--brand-primary-rgb) / 0.09);
  }

  &:hover:not(:disabled) {
    transform: translateY(-1px);
    background: rgb(255 255 255 / 0.72);
  }

  &:disabled {
    cursor: default;
    opacity: 0.56;
  }

  &[aria-expanded='true'] .thinking-toggle-icon {
    transform: rotate(180deg);
  }
}

.thinking-toggle-icon {
  display: grid;
  width: 24px;
  height: 24px;
  place-items: center;
  border-radius: 50%;
  background: rgb(255 255 255 / 0.72);
  transition: transform 0.2s ease;
}

.stream-preview {
  margin: 4px 22px 0;
  color: var(--text-secondary);
  font-size: 13px;
  line-height: 1.7;
  white-space: pre-wrap;
}

.thinking-step-list {
  display: grid;
  gap: 0;
  margin: 12px 22px 0;
  padding: 14px 0 0;
  border-top: 1px solid rgb(115 133 172 / 0.14);
  list-style: none;
}

.thinking-step {
  position: relative;
  display: grid;
  grid-template-columns: 32px minmax(0, 1fr) auto;
  gap: 11px;
  align-items: start;
  padding: 7px 0 11px;

  &:not(:last-child)::after {
    content: '';
    position: absolute;
    top: 36px;
    bottom: -3px;
    left: 15px;
    width: 1px;
    background: var(--border-divider);
  }
}

.thinking-step-marker,
.execution-step-marker {
  display: grid;
  place-items: center;
  border-radius: 11px;
  color: var(--text-muted);
  background: color-mix(in srgb, var(--surface-solid-control) 76%, transparent);
  font-size: 11px;
  font-weight: 800;
}

.thinking-step-marker {
  width: 31px;
  height: 31px;
}

.thinking-step.is-complete .thinking-step-marker,
.execution-step.is-complete .execution-step-marker {
  color: var(--status-success);
  background: color-mix(in srgb, var(--status-success) 10%, var(--surface-solid-control));
}

.thinking-step.is-active .thinking-step-marker,
.execution-step.is-active .execution-step-marker {
  color: var(--brand-primary);
  background: rgb(var(--brand-primary-rgb) / 0.10);
}

.thinking-step-copy,
.execution-step-copy {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 4px;

  strong {
    color: var(--text-primary);
    font-size: 12px;
    font-weight: 780;
  }

  small {
    overflow: hidden;
    color: var(--text-muted);
    font-size: 11px;
    line-height: 1.45;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
}

.thinking-step-state {
  padding-top: 7px;
  color: var(--text-muted);
  font-size: 10px;
  font-weight: 700;
}

.thinking-step.is-complete .thinking-step-state { color: var(--status-success); }
.thinking-step.is-active .thinking-step-state { color: var(--brand-primary); }

.spin-icon {
  animation: icon-spin 1s linear infinite;
}

.chat-input {
  flex: 0 0 auto;
  margin: 0;
  padding: 12px 18px 14px;
  border-top: 1px solid var(--border-divider);
  background:
    linear-gradient(135deg, rgb(255 255 255 / 0.58), rgb(237 245 255 / 0.46));
  backdrop-filter: blur(22px) saturate(1.08);

  :deep(.el-textarea__inner) {
    min-height: 56px !important;
    padding: 10px 12px;
    border: 0;
    border-radius: 0;
    color: var(--text-primary);
    background: transparent;
    box-shadow: none;
    font-size: 13px;
    resize: none;

    &:focus {
      box-shadow: none;
    }
  }
}

.input-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  padding-top: 10px;
  border-top: 1px solid var(--border-divider);
}

.input-hint {
  color: var(--text-muted);
  font-size: 11px;
  font-weight: 650;
}

.send-button {
  min-width: 94px;
  border: 0;
  border-radius: 13px;
  background: linear-gradient(135deg, #6b63f3, #4e48e8);
  box-shadow: 0 10px 24px rgb(79 70 229 / 0.22);

  &.is-disabled {
    opacity: 0.58;
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
  display: flex;
  flex-direction: column;
}

.detail-header {
  display: flex;
  flex: 0 0 auto;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  padding: 20px 20px 14px;

  h3 {
    margin: 5px 0 0;
    color: var(--text-primary);
    font-size: 19px;
    font-weight: 830;
  }
}

.toolbar-label {
  display: block;
  color: var(--text-muted);
  font-size: 10px;
  font-weight: 800;
  letter-spacing: 0.10em;
  text-transform: uppercase;
}

.detail-header-actions {
  gap: 7px;
}

.detail-icon-button {
  width: 32px;
  height: 32px;
  border: 0;
  background: transparent;
  box-shadow: none;
}

.insight-summary {
  flex: 0 0 auto;
  gap: 12px;
  padding: 0 20px 14px;
  border-bottom: 1px solid var(--border-divider);

  div {
    display: flex;
    align-items: baseline;
    gap: 5px;
  }

  strong {
    color: var(--text-primary);
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
    background: var(--border-divider);
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
    padding: 4px;
    border: 1px solid var(--border-subtle);
    border-radius: 13px;
    background: color-mix(in srgb, var(--surface-solid-control) 68%, transparent);
  }

  :deep(.el-tabs__nav-wrap::after),
  :deep(.el-tabs__active-bar) {
    display: none;
  }

  :deep(.el-tabs__item) {
    height: 34px;
    border-radius: 10px;
    color: var(--text-muted);
    font-size: 11px;
    font-weight: 780;
    transition: background 0.16s ease, color 0.16s ease, box-shadow 0.16s ease;
  }

  :deep(.el-tabs__item.is-active) {
    color: var(--brand-primary);
    background: var(--surface-solid-control);
    box-shadow: 0 5px 14px rgb(52 76 121 / 0.08), var(--inner-highlight);
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
  background: rgb(var(--brand-primary-rgb) / 0.08);
  font-size: 10px;
}

.execution-panel,
.approval-panel {
  padding: 0 2px 12px;
}

.execution-state {
  gap: 7px;
  margin: 2px 2px 12px;
  color: var(--text-secondary);
  font-size: 11px;
  font-weight: 750;

  i {
    width: 8px;
    height: 8px;
    border-radius: 50%;
    background: var(--status-neutral);
    box-shadow: 0 0 0 5px color-mix(in srgb, var(--status-neutral) 10%, transparent);
  }

  &.is-running { color: var(--brand-primary); }
  &.is-running i { background: var(--brand-primary); animation: status-pulse 1.8s ease-out infinite; }
  &.is-success { color: var(--status-success); }
  &.is-success i { background: var(--status-success); }
  &.is-error { color: var(--status-danger); }
  &.is-error i { background: var(--status-danger); }
  &.is-stopped { color: var(--status-warning); }
  &.is-stopped i { background: var(--status-warning); }
}

.metric-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
  margin-bottom: 12px;
}

.metric-card {
  padding: 11px 12px;
  border: 1px solid var(--border-subtle);
  border-radius: 12px;
  background: color-mix(in srgb, var(--surface-solid-control) 64%, transparent);
  box-shadow: var(--inner-highlight);

  span {
    display: block;
    color: var(--text-muted);
    font-size: 10px;
    font-weight: 700;
  }

  strong {
    display: block;
    margin-top: 5px;
    color: var(--text-primary);
    font-size: 17px;
    font-weight: 850;
  }
}

.execution-step-list {
  display: grid;
  gap: 8px;
}

.execution-step {
  overflow: hidden;
  border: 1px solid var(--border-subtle);
  border-radius: 14px;
  background: color-mix(in srgb, var(--surface-solid-control) 62%, transparent);
  transition: border-color 0.16s ease, background 0.16s ease, box-shadow 0.16s ease;

  &.is-active {
    border-color: rgb(var(--brand-primary-rgb) / 0.30);
    background: color-mix(in srgb, var(--brand-selected-bg) 44%, var(--surface-solid-control));
    box-shadow: 0 8px 20px rgb(var(--brand-primary-rgb) / 0.09);
  }

  &.is-error {
    border-color: color-mix(in srgb, var(--status-danger) 30%, var(--border-subtle));
  }

  > button {
    display: grid;
    width: 100%;
    grid-template-columns: 35px minmax(0, 1fr) auto;
    gap: 10px;
    align-items: center;
    padding: 10px 11px;
    color: inherit;
    text-align: left;
    background: transparent;
  }

  > p {
    margin: 0;
    padding: 0 13px 12px 56px;
    color: var(--text-secondary);
    font-size: 11px;
    line-height: 1.6;
    word-break: break-word;
  }
}

.execution-step-marker {
  width: 34px;
  height: 34px;
}

.execution-step.is-error .execution-step-marker {
  color: var(--status-danger);
  background: color-mix(in srgb, var(--status-danger) 9%, var(--surface-solid-control));
}

.step-chevron {
  color: var(--text-muted);
  transition: transform 0.18s ease;
}

.execution-step.is-open .step-chevron {
  transform: rotate(180deg);
}

.empty-detail {
  display: flex;
  min-height: 210px;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  color: var(--text-muted);
  text-align: center;

  &.compact {
    min-height: 145px;
  }

  .el-icon {
    margin-bottom: 9px;
    font-size: 30px;
  }

  p {
    margin: 0;
    font-size: 12px;
    font-weight: 650;
  }
}

.approval-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 10px;
  color: var(--text-secondary);
  font-size: 12px;
  font-weight: 760;
}

.approval-list {
  display: grid;
  gap: 10px;
}

.approval-item {
  padding: 13px;
  border: 1px solid color-mix(in srgb, var(--status-warning) 24%, var(--border-subtle));
  border-radius: 14px;
  background: color-mix(in srgb, var(--status-warning) 5%, var(--surface-solid-control));
}

.approval-title {
  justify-content: space-between;
  gap: 9px;

  strong {
    color: var(--text-primary);
    font-size: 12px;
  }
}

.approval-message {
  margin: 9px 0 0;
  color: var(--text-secondary);
  font-size: 11px;
  line-height: 1.6;
}

.approval-meta {
  gap: 8px 12px;
  margin-top: 9px;
  flex-wrap: wrap;
  color: var(--text-muted);
  font-size: 10px;

  button {
    padding: 0;
    color: var(--brand-primary);
    background: transparent;
  }
}

.approval-policy {
  flex: 0 0 auto;
  gap: 10px;
  margin: 10px 14px 14px;
  padding: 12px 13px;
  border: 1px solid color-mix(in srgb, var(--status-success) 18%, var(--border-subtle));
  border-radius: 14px;
  color: var(--status-success);
  background: color-mix(in srgb, var(--status-success) 5%, var(--surface-solid-control));

  &.has-pending {
    border-color: color-mix(in srgb, var(--status-warning) 28%, var(--border-subtle));
    color: var(--status-warning);
    background: color-mix(in srgb, var(--status-warning) 6%, var(--surface-solid-control));
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

.metadata-json {
  max-height: calc(100vh - 240px);
  margin: 0;
  overflow: auto;
  padding: 16px;
  border: 1px solid rgb(113 131 156 / 0.22);
  border-radius: var(--radius-md);
  color: #dce8ff;
  background: #101827;
  font-family: 'JetBrains Mono', 'Fira Code', Consolas, monospace;
  font-size: 12px;
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-word;
}

@keyframes thinking-rim {
  to { transform: translate(-50%, -50%) rotate(1turn); }
}

@keyframes aura-breathe {
  from { opacity: 0.56; transform: scale(0.985); }
  to { opacity: 0.96; transform: scale(1.018); }
}

@keyframes thinking-dot {
  0%, 70%, 100% { opacity: 0.34; transform: translateY(0); }
  35% { opacity: 1; transform: translateY(-3px); }
}

@keyframes icon-spin {
  to { transform: rotate(1turn); }
}

@keyframes status-pulse {
  0% { box-shadow: 0 0 0 0 currentColor; }
  70% { box-shadow: 0 0 0 7px transparent; }
  100% { box-shadow: 0 0 0 0 transparent; }
}

:global([data-theme='dark']) {
  .chat-messages {
    background-color: rgb(10 18 34 / 0.68);
    background-image:
      linear-gradient(180deg, rgb(11 20 38 / 0.74), rgb(17 27 52 / 0.62)),
      url('/agent-debug/conversation-atmosphere.png');
    background-blend-mode: multiply, normal;
  }

  .agent-card {
    background:
      radial-gradient(circle at 4% 4%, rgb(var(--brand-primary-rgb) / 0.26), transparent 44%),
      radial-gradient(circle at 42% -8%, color-mix(in srgb, var(--agent-spectrum-cool) 22%, transparent), transparent 47%),
      radial-gradient(circle at 102% 4%, color-mix(in srgb, var(--agent-spectrum-rose) 18%, transparent), transparent 45%),
      radial-gradient(circle at 78% 112%, color-mix(in srgb, var(--agent-spectrum-warm) 15%, transparent), transparent 47%),
      linear-gradient(116deg, rgb(var(--brand-primary-rgb) / 0.08), transparent 48%, color-mix(in srgb, var(--agent-spectrum-rose) 8%, transparent)),
      rgb(15 23 42 / 0.88);
    box-shadow: inset 0 1px 0 rgb(255 255 255 / 0.10);
  }

  .empty-orbit,
  .user-avatar,
  .agent-avatar,
  .thinking-toggle {
    background: rgb(20 31 52 / 0.72);
  }

  .chat-input {
    background: linear-gradient(135deg, rgb(15 25 44 / 0.88), rgb(20 31 57 / 0.76));
  }
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

  .agent-message {
    grid-template-columns: 54px minmax(0, 1fr);
  }
}

@media (max-width: 720px) {
  .agent-debug-header {
    min-height: 104px;

    :deep(.app-page-header__trailing) {
      display: none;
    }
  }

  .agent-header-meta {
    gap: 7px;
    flex-wrap: wrap;
  }

  .chat-toolbar {
    padding: 0 16px;
  }

  .chat-messages {
    padding: 24px 15px 30px;
  }

  .user-message-content {
    max-width: calc(100% - 49px);
  }

  .agent-message {
    grid-template-columns: 42px minmax(0, 1fr);
    gap: 9px;
  }

  .agent-avatar {
    width: 42px;
    height: 42px;

    img {
      width: 36px;
      height: 36px;
    }
  }

  .agent-card-shell {
    border-radius: 20px;
  }

  .agent-card {
    border-radius: 18px;
  }

  .agent-card-heading,
  .thinking-summary-row {
    padding-right: 15px;
    padding-left: 15px;
  }

  .thinking-summary-row {
    align-items: flex-start;
    flex-direction: column;
  }

  .response-content {
    padding-right: 15px;
    padding-left: 15px;
  }

  .message-meta,
  .thinking-step-list {
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
  .thinking-card-shell::before,
  .thinking-card-shell::after,
  .thinking-dots i,
  .spin-icon,
  .run-state.active i,
  .execution-state.is-running i {
    animation: none !important;
  }

  .chat-messages {
    scroll-behavior: auto;
  }
}
</style>
