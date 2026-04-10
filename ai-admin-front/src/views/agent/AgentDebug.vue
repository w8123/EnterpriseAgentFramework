<template>
  <div class="debug-container">
    <!-- 顶部 Agent 信息 -->
    <div class="debug-header">
      <el-button @click="router.push('/agent')" :icon="ArrowLeft" text>返回</el-button>
      <h3>{{ agentName || 'Agent 调试台' }}</h3>
      <div class="session-info">
        <el-tag v-if="sessionId" size="small" type="info">Session: {{ sessionId }}</el-tag>
        <el-button
          v-if="sessionId"
          size="small"
          type="warning"
          text
          @click="handleClearSession"
        >清除会话</el-button>
      </div>
    </div>

    <div class="debug-body">
      <!-- 左侧：对话区域 -->
      <div class="chat-panel">
        <div class="chat-messages" ref="messagesRef">
          <div v-if="messages.length === 0" class="chat-empty">
            <el-icon :size="48" color="#c0c4cc"><ChatDotRound /></el-icon>
            <p>输入消息开始调试对话</p>
          </div>
          <div
            v-for="msg in messages"
            :key="msg.id"
            class="message-item"
            :class="msg.role"
          >
            <div class="message-avatar">
              <el-icon v-if="msg.role === 'user'" :size="20"><User /></el-icon>
              <el-icon v-else :size="20"><Cpu /></el-icon>
            </div>
            <div class="message-content">
              <div class="message-text" v-if="msg.loading">
                <span class="typing-indicator">
                  <span></span><span></span><span></span>
                </span>
              </div>
              <div class="message-text" v-else>{{ msg.content }}</div>
              <div v-if="msg.toolCalls?.length" class="message-meta">
                <el-tag
                  v-for="tc in msg.toolCalls"
                  :key="tc"
                  size="small"
                  type="success"
                >{{ tc }}</el-tag>
              </div>
            </div>
          </div>
        </div>

        <div class="chat-input">
          <el-input
            v-model="inputMessage"
            type="textarea"
            :rows="2"
            placeholder="输入消息... (Ctrl+Enter 发送)"
            @keydown="handleKeydown"
            :disabled="streaming"
          />
          <div class="input-actions">
            <el-radio-group v-model="chatMode" size="small">
              <el-radio-button value="chat">轻量对话</el-radio-button>
              <el-radio-button value="stream">流式对话</el-radio-button>
              <el-radio-button value="agent">Agent 执行</el-radio-button>
            </el-radio-group>
            <div>
              <el-button v-if="streaming" type="danger" size="small" @click="stopStream">
                停止
              </el-button>
              <el-button type="primary" @click="handleSend" :loading="sending" :disabled="streaming">
                发送
              </el-button>
            </div>
          </div>
        </div>
      </div>

      <!-- 右侧：执行详情 -->
      <div class="detail-panel">
        <el-tabs v-model="activeTab">
          <el-tab-pane label="执行详情" name="exec">
            <div v-if="!lastResult" class="empty-detail">
              <p>发送消息后查看执行详情</p>
            </div>
            <template v-else>
              <div class="detail-section">
                <h4>意图识别</h4>
                <el-tag>{{ lastResult.intentType || '未知' }}</el-tag>
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
                <el-tag
                  v-for="tc in lastResult.toolCalls"
                  :key="tc"
                  type="success"
                  class="detail-tag"
                >{{ tc }}</el-tag>
              </div>
            </template>
          </el-tab-pane>
          <el-tab-pane label="Agent 详情" name="agent-detail">
            <div v-if="!lastAgentResult" class="empty-detail">
              <p>使用"Agent 执行"模式查看完整链路</p>
            </div>
            <template v-else>
              <div class="detail-section">
                <h4>执行状态</h4>
                <el-tag :type="lastAgentResult.success ? 'success' : 'danger'">
                  {{ lastAgentResult.success ? '成功' : '失败' }}
                </el-tag>
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
        </el-tabs>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted, nextTick } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ArrowLeft, User, Cpu, ChatDotRound } from '@element-plus/icons-vue'
import type { ChatMessage, ChatResponse } from '@/types/chat'
import type { AgentResult } from '@/types/agent'
import { sendChat, clearSession } from '@/api/chat'
import { getAgent, executeAgentDetailed } from '@/api/agent'
import { useSSE } from '@/composables/useSSE'

const route = useRoute()
const router = useRouter()
const agentId = route.params.id as string

const agentName = ref('')
const sessionId = ref('')
const inputMessage = ref('')
const chatMode = ref<'chat' | 'stream' | 'agent'>('stream')
const messages = ref<ChatMessage[]>([])
const sending = ref(false)
const activeTab = ref('exec')
const messagesRef = ref<HTMLElement>()

const lastResult = ref<ChatResponse | null>(null)
const lastAgentResult = ref<AgentResult | null>(null)

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
    })
    placeholder.loading = false
    placeholder.content = data.answer || '(无回答)'
    lastAgentResult.value = data
  } catch {
    placeholder.loading = false
    placeholder.content = '执行失败，请重试'
  } finally {
    sending.value = false
    scrollToBottom()
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

onMounted(loadAgent)
</script>

<style scoped lang="scss">
.debug-container {
  display: flex;
  flex-direction: column;
  height: calc(100vh - 56px);
  padding: 0;
}

.debug-header {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 24px;
  background: #fff;
  border-bottom: 1px solid #e5e6eb;
  flex-shrink: 0;

  h3 {
    margin: 0;
    font-size: 16px;
  }

  .session-info {
    margin-left: auto;
    display: flex;
    align-items: center;
    gap: 8px;
  }
}

.debug-body {
  display: flex;
  flex: 1;
  overflow: hidden;
}

.chat-panel {
  flex: 1;
  display: flex;
  flex-direction: column;
  border-right: 1px solid #e5e6eb;
}

.chat-messages {
  flex: 1;
  overflow-y: auto;
  padding: 20px;
}

.chat-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  height: 100%;
  color: #c0c4cc;

  p {
    margin-top: 12px;
    font-size: 14px;
  }
}

.message-item {
  display: flex;
  gap: 12px;
  margin-bottom: 20px;

  &.user {
    flex-direction: row-reverse;

    .message-content {
      align-items: flex-end;
    }

    .message-text {
      background: #409eff;
      color: #fff;
      border-radius: 12px 2px 12px 12px;
    }
  }

  &.assistant .message-text {
    background: #f4f4f5;
    border-radius: 2px 12px 12px 12px;
  }
}

.message-avatar {
  width: 36px;
  height: 36px;
  border-radius: 50%;
  background: #e5e6eb;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.message-content {
  display: flex;
  flex-direction: column;
  max-width: 70%;
}

.message-text {
  padding: 10px 14px;
  font-size: 14px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
}

.message-meta {
  display: flex;
  gap: 4px;
  margin-top: 6px;
  flex-wrap: wrap;
}

.typing-indicator {
  display: inline-flex;
  gap: 4px;

  span {
    width: 6px;
    height: 6px;
    border-radius: 50%;
    background: #909399;
    animation: typing 1.2s infinite ease-in-out;

    &:nth-child(2) { animation-delay: 0.2s; }
    &:nth-child(3) { animation-delay: 0.4s; }
  }
}

@keyframes typing {
  0%, 80%, 100% { opacity: 0.3; transform: scale(0.8); }
  40% { opacity: 1; transform: scale(1); }
}

.chat-input {
  padding: 16px 20px;
  background: #fff;
  border-top: 1px solid #e5e6eb;
}

.input-actions {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-top: 10px;
}

.detail-panel {
  width: 380px;
  flex-shrink: 0;
  overflow-y: auto;
  padding: 16px;
  background: #fff;
}

.empty-detail {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 200px;
  color: #c0c4cc;
  font-size: 14px;
}

.detail-section {
  margin-bottom: 20px;

  h4 {
    font-size: 13px;
    color: #909399;
    margin-bottom: 8px;
  }
}

.detail-tag {
  margin-right: 6px;
  margin-bottom: 4px;
}

.metadata-json {
  background: #fafafa;
  border: 1px solid #ebeef5;
  border-radius: 4px;
  padding: 12px;
  font-size: 12px;
  overflow-x: auto;
  max-height: 300px;
  overflow-y: auto;
}
</style>
