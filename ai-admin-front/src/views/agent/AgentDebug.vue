<template>
  <div class="debug-container">
    <section class="debug-header">
      <div class="header-main">
        <el-button class="back-button" :icon="ArrowLeft" text @click="router.push('/agent')">
          返回
        </el-button>
        <div class="agent-mark">
          <el-icon><Cpu /></el-icon>
        </div>
        <div class="title-block">
          <div class="eyebrow">Agent Debug Console</div>
          <h2>{{ agentName || 'Agent 调试台' }}</h2>
        </div>
      </div>

      <div class="header-actions">
        <el-tag v-if="sessionId" class="session-tag" size="large" effect="plain">
          Session {{ sessionId }}
        </el-tag>
        <el-button
          v-if="sessionId"
          :icon="Delete"
          plain
          type="warning"
          @click="handleClearSession"
        >
          清除会话
        </el-button>
      </div>
    </section>

    <section class="debug-body">
      <main class="chat-panel">
        <div class="chat-toolbar">
          <div>
            <span class="toolbar-label">调试模式</span>
            <el-radio-group v-model="chatMode" size="small" class="mode-toggle">
              <el-radio-button value="chat">轻量对话</el-radio-button>
              <el-radio-button value="stream">流式对话</el-radio-button>
              <el-radio-button value="agent">Agent 执行</el-radio-button>
            </el-radio-group>
          </div>
          <div class="run-state" :class="{ active: streaming || sending }">
            <span />
            {{ streaming || sending ? '运行中' : '就绪' }}
          </div>
        </div>

        <div ref="messagesRef" class="chat-messages">
          <div v-if="messages.length === 0" class="chat-empty">
            <div class="empty-orbit">
              <el-icon><ChatDotRound /></el-icon>
            </div>
            <h3>开始一次调试</h3>
            <p>选择执行模式后输入问题，右侧会同步展示执行细节。</p>
          </div>

          <div
            v-for="msg in messages"
            :key="msg.id"
            class="message-item"
            :class="msg.role"
          >
            <div class="message-avatar">
              <el-icon v-if="msg.role === 'user'"><User /></el-icon>
              <el-icon v-else><Cpu /></el-icon>
            </div>
            <div class="message-content">
              <div class="message-role">{{ msg.role === 'user' ? 'You' : 'Agent' }}</div>
              <div class="message-text" v-if="msg.loading">
                <span class="typing-indicator">
                  <span></span><span></span><span></span>
                </span>
              </div>
              <div class="message-text" v-else>{{ msg.content }}</div>

              <DynamicInteraction
                v-if="msg.role === 'assistant' && msg.uiRequest"
                class="interaction-card"
                :payload="msg.uiRequest"
                @action="(act, vals) => handleUiAction(msg.uiRequest!, act, vals)"
              />

              <div v-if="msg.toolCalls?.length" class="message-meta">
                <el-tag
                  v-for="tc in msg.toolCalls"
                  :key="tc"
                  size="small"
                  type="success"
                  effect="light"
                >
                  {{ tc }}
                </el-tag>
              </div>
              <div v-if="msg.traceId" class="message-meta">
                <el-button :icon="Connection" link type="primary" size="small" @click="openTrace(msg.traceId)">
                  查看 Trace
                </el-button>
              </div>
            </div>
          </div>
        </div>

        <div class="chat-input">
          <el-input
            v-model="inputMessage"
            type="textarea"
            :autosize="{ minRows: 2, maxRows: 5 }"
            placeholder="输入消息...（Ctrl+Enter 发送）"
            resize="none"
            :disabled="streaming"
            @keydown="handleKeydown"
          />
          <div class="input-actions">
            <span class="input-hint">Enter 换行，Ctrl+Enter 发送</span>
            <div class="send-actions">
              <el-button v-if="streaming" :icon="CircleClose" type="danger" plain @click="stopStream">
                停止
              </el-button>
              <el-button
                :icon="Promotion"
                type="primary"
                :loading="sending"
                :disabled="streaming"
                @click="handleSend"
              >
                发送
              </el-button>
            </div>
          </div>
        </div>
      </main>

      <aside class="detail-panel">
        <div class="detail-header">
          <div>
            <span class="toolbar-label">执行洞察</span>
            <h3>{{ activeTab === 'agent-detail' ? 'Agent 详情' : '执行详情' }}</h3>
          </div>
          <el-tag :type="lastAgentResult?.success === false ? 'danger' : 'info'" effect="light">
            {{ lastAgentResult ? (lastAgentResult.success ? '成功' : '失败') : '待执行' }}
          </el-tag>
        </div>

        <el-tabs v-model="activeTab" class="detail-tabs">
          <el-tab-pane label="执行详情" name="exec">
            <div v-if="!lastResult" class="empty-detail">
              <el-icon><Document /></el-icon>
              <p>发送消息后查看执行详情</p>
            </div>
            <template v-else>
              <div class="detail-section">
                <h4>意图识别</h4>
                <el-tag effect="light">{{ lastResult.intentType || '未知' }}</el-tag>
              </div>
              <div v-if="lastResult.reasoningSteps?.length" class="detail-section">
                <h4>推理步骤</h4>
                <el-timeline>
                  <el-timeline-item
                    v-for="(step, idx) in lastResult.reasoningSteps"
                    :key="idx"
                    :timestamp="`Step ${idx + 1}`"
                    placement="top"
                  >
                    {{ step }}
                  </el-timeline-item>
                </el-timeline>
              </div>
              <div v-if="lastResult.toolCalls?.length" class="detail-section">
                <h4>Tool 调用</h4>
                <div class="tag-row">
                  <el-tag
                    v-for="tc in lastResult.toolCalls"
                    :key="tc"
                    type="success"
                    effect="light"
                  >
                    {{ tc }}
                  </el-tag>
                </div>
              </div>
            </template>
          </el-tab-pane>

          <el-tab-pane label="Agent 详情" name="agent-detail">
            <div v-if="!lastAgentResult" class="empty-detail">
              <el-icon><DataLine /></el-icon>
              <p>使用 Agent 执行模式查看完整链路</p>
            </div>
            <template v-else>
              <div class="metric-grid">
                <div class="metric-card">
                  <span>状态</span>
                  <strong :class="lastAgentResult.success ? 'success' : 'danger'">
                    {{ lastAgentResult.success ? '成功' : '失败' }}
                  </strong>
                </div>
                <div class="metric-card">
                  <span>步骤数</span>
                  <strong>{{ lastAgentResult.steps?.length || 0 }}</strong>
                </div>
              </div>

              <div v-if="lastAgentResult.steps?.length" class="detail-section">
                <h4>执行步骤</h4>
                <el-timeline>
                  <el-timeline-item
                    v-for="(step, idx) in lastAgentResult.steps"
                    :key="idx"
                    :timestamp="step.name"
                    placement="top"
                  >
                    {{ step.detail }}
                  </el-timeline-item>
                </el-timeline>
              </div>

              <div v-if="lastAgentResult.metadata" class="detail-section">
                <h4>元数据</h4>
                <pre class="metadata-json">{{ JSON.stringify(lastAgentResult.metadata, null, 2) }}</pre>
              </div>
            </template>
          </el-tab-pane>
          <el-tab-pane :label="`Approvals (${pendingApprovals.length})`" name="approvals">
            <div class="approval-toolbar">
              <span class="toolbar-label">Human approvals</span>
              <el-button size="small" :loading="approvalLoading" @click="loadPendingApprovals">Refresh</el-button>
            </div>
            <div v-if="!pendingApprovals.length" class="empty-detail compact">
              <el-icon><DataLine /></el-icon>
              <p>No pending human approvals</p>
            </div>
            <div v-else class="approval-list">
              <div v-for="item in pendingApprovals" :key="item.interactionId" class="approval-item">
                <div class="approval-title">
                  <strong>{{ item.title || item.nodeId || 'Human approval' }}</strong>
                  <el-tag size="small" effect="light">{{ item.status }}</el-tag>
                </div>
                <p v-if="item.message" class="approval-message">{{ item.message }}</p>
                <div class="approval-meta">
                  <span>{{ item.nodeId }}</span>
                  <span v-if="item.traceId">Trace {{ item.traceId }}</span>
                </div>
                <DynamicInteraction
                  v-if="item.uiRequest"
                  class="interaction-card"
                  :payload="item.uiRequest"
                  @action="(act, vals) => handlePendingApprovalAction(item.interactionId, act, vals)"
                />
              </div>
            </div>
          </el-tab-pane>
        </el-tabs>
      </aside>
    </section>

    <el-drawer v-model="traceDrawerVisible" :title="`Trace 回放 - ${activeTraceId}`" size="45%">
      <TraceTimeline :nodes="traceNodes" />
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { nextTick, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  ArrowLeft,
  ChatDotRound,
  CircleClose,
  Connection,
  Cpu,
  DataLine,
  Delete,
  Document,
  Promotion,
  User,
} from '@element-plus/icons-vue'
import TraceTimeline from '@/components/TraceTimeline.vue'
import DynamicInteraction from '@/components/interaction/DynamicInteraction.vue'
import type { ChatMessage, ChatResponse } from '@/types/chat'
import type { UiRequestPayload } from '@/types/interaction'
import type { AgentResult, PendingHumanApproval } from '@/types/agent'
import type { TraceNode } from '@/types/trace'
import { sendChat, clearSession } from '@/api/chat'
import { getAgent, executeAgentDetailed, listPendingHumanApprovals, submitHumanApproval } from '@/api/agent'
import { getTraceDetail } from '@/api/trace'
import { useSSE } from '@/composables/useSSE'

const route = useRoute()
const router = useRouter()
const agentId = route.params.id as string

const agentName = ref('')
const sessionId = ref('')
const inputMessage = ref('')
const chatMode = ref<'chat' | 'stream' | 'agent'>('agent')
const messages = ref<ChatMessage[]>([])
const sending = ref(false)
const activeTab = ref('exec')
const messagesRef = ref<HTMLElement>()

const lastResult = ref<ChatResponse | null>(null)
const lastAgentResult = ref<AgentResult | null>(null)
const traceDrawerVisible = ref(false)
const activeTraceId = ref('')
const traceNodes = ref<TraceNode[]>([])
const pendingApprovals = ref<PendingHumanApproval[]>([])
const approvalLoading = ref(false)

const { content: streamContent, isStreaming: streaming, start: startSSE, stop: stopStream } = useSSE()

let msgCounter = 0
function createMsg(role: 'user' | 'assistant', content: string, extra?: Partial<ChatMessage>): ChatMessage {
  return {
    id: `msg-${++msgCounter}`,
    role,
    content,
    timestamp: Date.now(),
    ...extra,
  }
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

function scrollToBottom() {
  nextTick(() => {
    if (messagesRef.value) {
      messagesRef.value.scrollTop = messagesRef.value.scrollHeight
    }
  })
}

async function loadAgent() {
  try {
    const { data } = await getAgent(agentId)
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

function handleKeydown(e: KeyboardEvent) {
  if (e.ctrlKey && e.key === 'Enter') {
    e.preventDefault()
    handleSend()
  }
}

async function handleSend() {
  const msg = inputMessage.value.trim()
  if (!msg) return

  messages.value.push(createMsg('user', msg))
  inputMessage.value = ''
  scrollToBottom()

  if (chatMode.value === 'stream') {
    await handleStreamChat(msg)
  } else if (chatMode.value === 'agent') {
    await handleAgentExec(msg)
  } else {
    await handleNormalChat(msg)
  }
}

async function handleNormalChat(msg: string) {
  const placeholder = createMsg('assistant', '', { loading: true })
  messages.value.push(placeholder)
  sending.value = true
  scrollToBottom()

  try {
    const { data } = await sendChat({ message: msg, sessionId: sessionId.value || undefined })
    placeholder.loading = false
    placeholder.content = data.answer
    placeholder.toolCalls = data.toolCalls
    placeholder.reasoningSteps = data.reasoningSteps
    sessionId.value = data.sessionId || sessionId.value
    lastResult.value = data
  } catch {
    placeholder.loading = false
    placeholder.content = '请求失败，请重试'
  } finally {
    sending.value = false
    scrollToBottom()
  }
}

async function handleStreamChat(msg: string) {
  const placeholder = createMsg('assistant', '')
  messages.value.push(placeholder)
  scrollToBottom()

  await startSSE('/api/chat/stream', {
    message: msg,
    sessionId: sessionId.value || undefined,
  }, {
    onChunk() {
      placeholder.content = streamContent.value
      scrollToBottom()
    },
    onDone(fullText) {
      placeholder.content = fullText
      scrollToBottom()
    },
    onError() {
      placeholder.content = placeholder.content || '流式请求失败'
    },
  })
}

async function handleAgentExec(msg: string) {
  const placeholder = createMsg('assistant', '', { loading: true })
  messages.value.push(placeholder)
  sending.value = true
  activeTab.value = 'agent-detail'
  scrollToBottom()

  try {
    const { data } = await executeAgentDetailed({
      message: msg,
      sessionId: sessionId.value || undefined,
      agentDefinitionId: agentId,
    })
    placeholder.loading = false
    placeholder.content = data.answer || '(无回答)'
    placeholder.traceId = (data.metadata?.traceId as string) || undefined
    placeholder.uiRequest = data.uiRequest
    lastAgentResult.value = data
    if (data.uiRequest?.component === 'confirm') {
      await loadPendingApprovals()
    }
  } catch {
    placeholder.loading = false
    placeholder.content = '执行失败，请重试'
  } finally {
    sending.value = false
    scrollToBottom()
  }
}

async function handleUiAction(payload: UiRequestPayload, action: string, values: Record<string, unknown>) {
  const placeholder = createMsg('assistant', '', { loading: true })
  messages.value.push(placeholder)
  sending.value = true
  activeTab.value = 'agent-detail'
  scrollToBottom()
  try {
    const { data } = await executeAgentDetailed({
      sessionId: sessionId.value || undefined,
      interactionId: payload.interactionId,
      uiSubmit: { action, values },
    })
    placeholder.loading = false
    placeholder.content = data.answer || '(无回答)'
    placeholder.traceId = (data.metadata?.traceId as string) || undefined
    placeholder.uiRequest = data.uiRequest
    lastAgentResult.value = data
    await loadPendingApprovals()
  } catch {
    placeholder.loading = false
    placeholder.content = '交互提交失败，请重试'
  } finally {
    sending.value = false
    scrollToBottom()
  }
}

async function handlePendingApprovalAction(interactionId: string, action: string, values: Record<string, unknown>) {
  const routeAction = values?.confirm === false ? 'reject' : action
  try {
    const { data } = await submitHumanApproval(interactionId, {
      action: routeAction,
      values,
      sessionId: sessionId.value || undefined,
    })
    messages.value.push(createMsg('assistant', data.answer || 'Approval submitted', {
      traceId: (data.metadata?.traceId as string) || undefined,
    }))
    lastAgentResult.value = data
    await loadPendingApprovals()
    scrollToBottom()
  } catch {
    ElMessage.error('审批提交失败')
  }
}

async function handleClearSession() {
  if (!sessionId.value) return
  try {
    await clearSession(sessionId.value)
    sessionId.value = ''
    messages.value = []
    lastResult.value = null
    lastAgentResult.value = null
    ElMessage.success('会话已清除')
  } catch {
    ElMessage.error('清除失败')
  }
}

onMounted(() => {
  loadAgent()
  loadPendingApprovals()
})
</script>

<style scoped lang="scss">
.debug-container {
  display: flex;
  flex-direction: column;
  height: calc(100vh - 56px);
  min-height: 0;
  padding: 22px 26px 24px;
  background:
    radial-gradient(circle at 10% 0%, rgba(79, 70, 229, 0.12), transparent 28%),
    radial-gradient(circle at 88% 4%, rgba(14, 165, 233, 0.09), transparent 24%),
    linear-gradient(180deg, #f8fafc 0%, #eef2f7 100%);
}

.debug-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
  min-height: 88px;
  padding: 18px 20px;
  margin-bottom: 16px;
  border: 1px solid #e3e8f2;
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.94);
  box-shadow: 0 16px 40px rgba(15, 23, 42, 0.07);
}

.header-main,
.header-actions {
  display: flex;
  align-items: center;
  min-width: 0;
}

.header-main {
  gap: 14px;
}

.header-actions {
  gap: 10px;
  flex-wrap: wrap;
  justify-content: flex-end;
}

.back-button {
  color: #475569;
  font-weight: 700;
}

.agent-mark {
  display: grid;
  place-items: center;
  width: 46px;
  height: 46px;
  flex: 0 0 auto;
  border-radius: 8px;
  color: #fff;
  background: linear-gradient(135deg, #4f46e5, #2563eb 58%, #0891b2);
  box-shadow: 0 14px 28px rgba(37, 99, 235, 0.24);

  .el-icon {
    font-size: 22px;
  }
}

.title-block {
  min-width: 0;

  h2 {
    margin: 4px 0 0;
    color: #101828;
    font-size: 22px;
    line-height: 1.2;
    font-weight: 800;
    letter-spacing: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
}

.eyebrow,
.toolbar-label {
  display: block;
  color: #64748b;
  font-size: 12px;
  line-height: 1.2;
  font-weight: 800;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.session-tag {
  max-width: 320px;
  overflow: hidden;
  text-overflow: ellipsis;
}

.debug-body {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 392px;
  gap: 16px;
  min-height: 0;
  flex: 1;
}

.chat-panel,
.detail-panel {
  min-height: 0;
  border: 1px solid #e3e8f2;
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.94);
  box-shadow: 0 18px 42px rgba(15, 23, 42, 0.07);
  overflow: hidden;
}

.chat-panel {
  display: flex;
  flex-direction: column;
}

.chat-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  min-height: 68px;
  padding: 14px 18px;
  border-bottom: 1px solid #edf1f7;
  background: linear-gradient(180deg, #ffffff, #fbfcff);
}

.mode-toggle {
  margin-top: 8px;
}

.run-state {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  flex: 0 0 auto;
  color: #64748b;
  font-size: 13px;
  font-weight: 700;

  span {
    width: 8px;
    height: 8px;
    border-radius: 999px;
    background: #94a3b8;
  }

  &.active {
    color: #2563eb;

    span {
      background: #2563eb;
      box-shadow: 0 0 0 5px rgba(37, 99, 235, 0.12);
    }
  }
}

.chat-messages {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 26px 28px;
  background:
    linear-gradient(90deg, rgba(148, 163, 184, 0.07) 1px, transparent 1px),
    linear-gradient(180deg, rgba(148, 163, 184, 0.05) 1px, transparent 1px),
    #f8fafc;
  background-size: 28px 28px;
}

.chat-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  height: 100%;
  min-height: 320px;
  color: #64748b;
  text-align: center;

  h3 {
    margin: 16px 0 6px;
    color: #101828;
    font-size: 20px;
    font-weight: 800;
  }

  p {
    max-width: 360px;
    font-size: 14px;
    line-height: 1.7;
  }
}

.empty-orbit {
  display: grid;
  place-items: center;
  width: 72px;
  height: 72px;
  border: 1px solid #dbeafe;
  border-radius: 8px;
  color: #2563eb;
  background:
    linear-gradient(135deg, rgba(79, 70, 229, 0.10), rgba(14, 165, 233, 0.08)),
    #ffffff;
  box-shadow: 0 18px 36px rgba(37, 99, 235, 0.12);

  .el-icon {
    font-size: 34px;
  }
}

.message-item {
  display: flex;
  gap: 12px;
  margin-bottom: 22px;

  &.user {
    flex-direction: row-reverse;

    .message-content {
      align-items: flex-end;
    }

    .message-role {
      text-align: right;
    }

    .message-avatar {
      color: #fff;
      background: linear-gradient(135deg, #6366f1, #8b5cf6);
    }

    .message-text {
      color: #fff;
      border-color: transparent;
      background: linear-gradient(135deg, #4f46e5, #2563eb);
      box-shadow: 0 12px 24px rgba(37, 99, 235, 0.18);
    }
  }

  &.assistant {
    .message-avatar {
      color: #1e293b;
      background: #e9edf5;
    }
  }
}

.message-avatar {
  display: grid;
  place-items: center;
  width: 38px;
  height: 38px;
  flex: 0 0 auto;
  border-radius: 8px;
  box-shadow: inset 0 0 0 1px rgba(255, 255, 255, 0.38);

  .el-icon {
    font-size: 19px;
  }
}

.message-content {
  display: flex;
  flex-direction: column;
  width: fit-content;
  max-width: min(760px, 74%);
  min-width: 0;
}

.message-role {
  margin: 0 0 6px;
  color: #94a3b8;
  font-size: 12px;
  font-weight: 800;
}

.message-text {
  padding: 13px 16px;
  border: 1px solid #e6eaf2;
  border-radius: 8px;
  color: #1e293b;
  background: rgba(255, 255, 255, 0.94);
  box-shadow: 0 10px 24px rgba(15, 23, 42, 0.05);
  font-size: 14px;
  line-height: 1.75;
  white-space: pre-wrap;
  word-break: break-word;
}

.interaction-card {
  margin-top: 10px;
}

.message-meta {
  display: flex;
  gap: 6px;
  margin-top: 8px;
  flex-wrap: wrap;
}

.typing-indicator {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  min-width: 38px;
  min-height: 18px;

  span {
    width: 6px;
    height: 6px;
    border-radius: 50%;
    background: #64748b;
    animation: typing 1.2s infinite ease-in-out;

    &:nth-child(2) {
      animation-delay: 0.2s;
    }

    &:nth-child(3) {
      animation-delay: 0.4s;
    }
  }
}

@keyframes typing {
  0%,
  80%,
  100% {
    opacity: 0.32;
    transform: translateY(0);
  }
  40% {
    opacity: 1;
    transform: translateY(-3px);
  }
}

.chat-input {
  padding: 16px 18px;
  border-top: 1px solid #edf1f7;
  background: #ffffff;

  :deep(.el-textarea__inner) {
    min-height: 64px !important;
    border-radius: 8px;
    box-shadow: 0 0 0 1px #dfe5ef inset;
    font-size: 14px;

    &:focus {
      box-shadow: 0 0 0 1px #4f46e5 inset, 0 0 0 4px rgba(79, 70, 229, 0.10);
    }
  }
}

.input-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-top: 12px;
}

.input-hint {
  color: #94a3b8;
  font-size: 12px;
  font-weight: 700;
}

.send-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.detail-panel {
  display: flex;
  flex-direction: column;
  padding: 18px;
  overflow-y: auto;
}

.detail-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  padding-bottom: 14px;
  border-bottom: 1px solid #edf1f7;

  h3 {
    margin: 5px 0 0;
    color: #101828;
    font-size: 18px;
    line-height: 1.25;
    font-weight: 800;
  }
}

.detail-tabs {
  margin-top: 8px;

  :deep(.el-tabs__item) {
    font-weight: 800;
  }
}

.empty-detail {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  min-height: 220px;
  color: #94a3b8;
  text-align: center;

  .el-icon {
    margin-bottom: 10px;
    font-size: 34px;
  }

  p {
    font-size: 14px;
    font-weight: 700;
  }
}

.detail-section {
  margin-bottom: 22px;

  h4 {
    margin: 0 0 10px;
    color: #475569;
    font-size: 13px;
    font-weight: 800;
  }

  :deep(.el-timeline) {
    padding-left: 4px;
  }

  :deep(.el-timeline-item__content) {
    color: #334155;
    line-height: 1.65;
  }
}

.tag-row {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.metric-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
  margin: 8px 0 22px;
}

.metric-card {
  padding: 14px;
  border: 1px solid #e6eaf2;
  border-radius: 8px;
  background: linear-gradient(180deg, #ffffff, #f8fafc);

  span {
    display: block;
    color: #64748b;
    font-size: 12px;
    font-weight: 800;
  }

  strong {
    display: block;
    margin-top: 8px;
    color: #101828;
    font-size: 20px;
    line-height: 1;
    font-weight: 900;

    &.success {
      color: #059669;
    }

    &.danger {
      color: #e11d48;
    }
  }
}

.approval-toolbar,
.approval-title,
.approval-meta {
  display: flex;
  align-items: center;
}

.approval-toolbar {
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
}

.empty-detail.compact {
  min-height: 150px;
}

.approval-list {
  display: grid;
  gap: 12px;
}

.approval-item {
  padding: 14px;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  background: #ffffff;
}

.approval-title {
  justify-content: space-between;
  gap: 10px;

  strong {
    color: #0f172a;
  }
}

.approval-message {
  margin: 8px 0 0;
  color: #475569;
  line-height: 1.55;
}

.approval-meta {
  flex-wrap: wrap;
  gap: 8px 12px;
  margin-top: 8px;
  color: #94a3b8;
  font-size: 12px;
}

.metadata-json {
  max-height: 320px;
  overflow: auto;
  padding: 14px;
  border: 1px solid #dce3ee;
  border-radius: 8px;
  background: #0f172a;
  color: #dbeafe;
  font-family: 'JetBrains Mono', 'Fira Code', Consolas, monospace;
  font-size: 12px;
  line-height: 1.65;
}

:deep(.el-button) {
  border-radius: 8px;
  font-weight: 700;
}

:deep(.el-button--primary:not(.is-link):not(.is-text)) {
  color: #fff;
  border-color: transparent;
  background: linear-gradient(135deg, #4f46e5, #2563eb);
  box-shadow: 0 10px 22px rgba(37, 99, 235, 0.20);
}

:deep(.el-radio-button__inner) {
  font-weight: 700;
}

:deep(.el-tag) {
  border-radius: 7px;
  font-weight: 700;
}

:global([data-theme="dark"]) {
  .debug-container {
    background:
      radial-gradient(circle at 10% 0%, rgba(79, 70, 229, 0.20), transparent 30%),
      radial-gradient(circle at 88% 4%, rgba(14, 165, 233, 0.12), transparent 24%),
      linear-gradient(180deg, #0d1020, #090d18);
  }

  .debug-header,
  .chat-panel,
  .detail-panel {
    border-color: rgba(148, 163, 184, 0.14);
    background: rgba(15, 23, 42, 0.82);
    box-shadow: 0 18px 42px rgba(0, 0, 0, 0.24);
  }

  .title-block h2,
  .detail-header h3,
  .chat-empty h3 {
    color: #f8fafc;
  }

  .chat-toolbar,
  .chat-input {
    border-color: rgba(148, 163, 184, 0.12);
    background: rgba(15, 23, 42, 0.76);
  }

  .chat-messages {
    background:
      linear-gradient(90deg, rgba(148, 163, 184, 0.055) 1px, transparent 1px),
      linear-gradient(180deg, rgba(148, 163, 184, 0.045) 1px, transparent 1px),
      rgba(2, 6, 23, 0.38);
  }

  .message-item.assistant .message-avatar {
    color: #dbeafe;
    background: rgba(148, 163, 184, 0.16);
  }

  .message-text {
    color: #e2e8f0;
    border-color: rgba(148, 163, 184, 0.14);
    background: rgba(15, 23, 42, 0.86);
  }

  .detail-header {
    border-color: rgba(148, 163, 184, 0.12);
  }

  .detail-section {
    h4 {
      color: #cbd5e1;
    }

    :deep(.el-timeline-item__content) {
      color: #cbd5e1;
    }
  }

  .metric-card {
    border-color: rgba(148, 163, 184, 0.14);
    background: rgba(15, 23, 42, 0.70);

    strong {
      color: #f8fafc;
    }
  }
}

@media (max-width: 1180px) {
  .debug-body {
    grid-template-columns: 1fr;
  }

  .detail-panel {
    min-height: 360px;
  }
}

@media (max-width: 760px) {
  .debug-container {
    height: auto;
    min-height: calc(100vh - 56px);
    padding: 16px;
  }

  .debug-header,
  .chat-toolbar,
  .input-actions {
    align-items: flex-start;
    flex-direction: column;
  }

  .header-actions,
  .send-actions {
    width: 100%;
    justify-content: flex-start;
  }

  .message-content {
    max-width: calc(100vw - 118px);
  }

  .chat-messages {
    padding: 20px 16px;
    min-height: 420px;
  }
}
</style>
