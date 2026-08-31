<template>
  <div class="page-container playground">
    <PageHeader
      variant="standard"
      domain="platform"
      title="模型调试台"
      compact
    />

    <div class="playground-body">
      <div class="config-panel">
        <el-card shadow="never">
          <template #header>模型配置</template>
          <el-form label-width="80px" size="default">
            <el-form-item label="厂商">
              <el-select
                v-model="config.provider"
                placeholder="请选择厂商"
                filterable
                style="width: 100%"
                :loading="llmInstancesLoading"
                @change="onProviderChange"
                @visible-change="handleModelSelectVisible"
              >
                <el-option
                  v-for="item in llmProviderOptions"
                  :key="item"
                  :label="item"
                  :value="item"
                />
                <template #empty>
                  <ModelSelectEmptyState
                    model-type="LLM"
                    :option-count="llmProviderOptions.length"
                    :loading="llmInstancesLoading"
                    :load-error="llmInstancesLoadError"
                    @retry="fetchInstances"
                  />
                </template>
              </el-select>
            </el-form-item>
            <el-form-item label="实例">
              <el-select
                v-model="config.modelInstanceId"
                placeholder="请选择模型实例"
                style="width: 100%"
                filterable
                :disabled="!config.provider"
                :loading="llmInstancesLoading"
                @change="onInstanceChange"
                @visible-change="handleModelSelectVisible"
              >
                <el-option
                  v-for="item in filteredLlmInstances"
                  :key="item.id"
                  :label="`${item.name} / ${item.modelName}`"
                  :value="item.id"
                />
                <template #empty>
                  <ModelSelectEmptyState
                    model-type="LLM"
                    :option-count="filteredLlmInstances.length"
                    :loading="llmInstancesLoading"
                    :load-error="llmInstancesLoadError"
                    @retry="fetchInstances"
                  />
                </template>
              </el-select>
            </el-form-item>
            <el-form-item label="Model">
              <el-input v-model="config.model" disabled placeholder="选择实例后自动带出" />
            </el-form-item>
            <el-form-item label="流式">
              <el-switch v-model="config.stream" :disabled="streaming" />
            </el-form-item>
          </el-form>
        </el-card>

        <el-card shadow="never" class="system-prompt-card">
          <template #header>System Prompt (可选)</template>
          <el-input
            v-model="config.systemPrompt"
            type="textarea"
            :rows="4"
            placeholder="输入系统提示语..."
            :disabled="streaming"
          />
        </el-card>

        <el-card v-if="displayUsage" shadow="never">
          <template #header>Token 用量</template>
          <el-descriptions :column="1" size="small" border>
            <el-descriptions-item label="Prompt">{{ displayUsage.promptTokens }}</el-descriptions-item>
            <el-descriptions-item label="Completion">{{ displayUsage.completionTokens }}</el-descriptions-item>
            <el-descriptions-item label="Total">{{ displayUsage.totalTokens }}</el-descriptions-item>
          </el-descriptions>
        </el-card>
      </div>

      <div class="chat-area">
        <div class="messages-area" ref="messagesRef">
          <div v-if="messages.length === 0 && !streaming" class="chat-empty">
            <p>选择模型后发送消息开始调试</p>
          </div>

          <div
            v-for="(msg, idx) in messages"
            :key="idx"
            class="msg-block"
            :class="msg.role"
          >
            <div class="msg-role">{{ roleLabel(msg.role) }}</div>
            <details v-if="msg.reasoningContent" class="msg-reasoning">
              <summary>思考过程</summary>
              <pre>{{ msg.reasoningContent }}</pre>
            </details>
            <div v-if="msg.content" class="msg-text">{{ msg.content }}</div>
            <div v-if="msg.toolCalls?.length" class="msg-tools">
              <div v-for="(call, callIdx) in msg.toolCalls" :key="callIdx" class="msg-tool">
                <div class="msg-tool__meta">
                  <strong>{{ callName(call) }}</strong>
                  <span v-if="callId(call)">id: {{ callId(call) }}</span>
                  <span v-if="callType(call)">type: {{ callType(call) }}</span>
                </div>
                <pre>{{ formatArgs(call) }}</pre>
              </div>
            </div>
            <div v-if="msg.incompleteReason" class="msg-flag">
              {{ incompleteLabel(msg) }}
            </div>
          </div>

          <div v-if="streaming" class="msg-block assistant is-streaming">
            <div class="msg-role">AI</div>
            <details v-if="streamState.reasoningContent" class="msg-reasoning" open>
              <summary>思考过程</summary>
              <pre>{{ streamState.reasoningContent }}</pre>
            </details>
            <div v-if="streamState.content" class="msg-text">{{ streamState.content }}</div>
            <div v-else-if="!streamState.reasoningContent && !streamState.toolCalls.length" class="msg-text msg-text--pending">
              生成中…
            </div>
            <div v-if="streamState.toolCalls.length" class="msg-tools">
              <div v-for="call in streamState.toolCalls" :key="call.index" class="msg-tool">
                <div class="msg-tool__meta">
                  <strong>{{ call.name || `tool#${call.index}` }}</strong>
                  <span v-if="call.id">id: {{ call.id }}</span>
                  <span v-if="call.type">type: {{ call.type }}</span>
                </div>
                <pre>{{ formatToolArguments(call.arguments) }}</pre>
              </div>
            </div>
          </div>
        </div>

        <div class="input-area">
          <el-input
            v-model="userInput"
            type="textarea"
            :rows="3"
            placeholder="输入消息... (Ctrl+Enter 发送)"
            :disabled="streaming || sending"
            @keydown="handleKeydown"
          />
          <div class="input-actions">
            <el-button @click="handleClear" :disabled="streaming || sending">清空</el-button>
            <el-button v-if="streaming" type="danger" plain @click="handleStop">停止生成</el-button>
            <el-button
              type="primary"
              @click="handleSend"
              :loading="sending"
              :disabled="streaming || sending || !config.modelInstanceId"
            >发送</el-button>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted, nextTick } from 'vue'
import { ElMessage } from 'element-plus'
import type {
  ModelChatMessage,
  ModelChatResponse,
  ModelInstance,
  ModelStreamState,
  ModelStreamToolCall,
  TokenUsage,
} from '@/types/model'
import { MODEL_STREAM_INTERRUPTED } from '@/types/model'
import { getModelInstances, modelChat } from '@/api/model'
import ModelSelectEmptyState from '@/components/model/ModelSelectEmptyState.vue'
import { normalizeActiveModelInstances } from '@/utils/modelSelection'
import { formatToolArguments } from './modelStream'
import { useModelStream } from './useModelStream'
import PageHeader from '@/components/common/PageHeader.vue'

const llmInstances = ref<ModelInstance[]>([])
const llmInstancesLoading = ref(false)
const llmInstancesLoadError = ref(false)
const selectableLlmInstances = computed(() =>
  llmInstances.value.filter((item) => item.status === 'ACTIVE'),
)
const llmProviderOptions = computed(() =>
  Array.from(new Set(selectableLlmInstances.value.map((item) => item.provider).filter(Boolean))).sort(),
)
const filteredLlmInstances = computed(() =>
  selectableLlmInstances.value.filter((item) => item.provider === config.provider),
)
const config = reactive({
  modelInstanceId: '',
  provider: '',
  model: '',
  stream: true,
  systemPrompt: '',
})

const messages = ref<ModelChatMessage[]>([])
const userInput = ref('')
const sending = ref(false)
const lastUsage = ref<TokenUsage | null>(null)
const messagesRef = ref<HTMLElement>()

const {
  state: streamState,
  isStreaming: streaming,
  start: startStream,
  stop: stopStream,
} = useModelStream()

const displayUsage = computed(() => streamState.value.usage || lastUsage.value)

function roleLabel(role: ModelChatMessage['role']) {
  if (role === 'user') return '你'
  if (role === 'tool') return '工具'
  if (role === 'system') return 'System'
  return 'AI'
}

function callName(call: ModelStreamToolCall | unknown) {
  const item = call as ModelStreamToolCall
  return item?.name || `tool#${item?.index ?? '?'}`
}

function callId(call: ModelStreamToolCall | unknown) {
  return (call as ModelStreamToolCall)?.id
}

function callType(call: ModelStreamToolCall | unknown) {
  return (call as ModelStreamToolCall)?.type
}

function formatArgs(call: ModelStreamToolCall | unknown) {
  return formatToolArguments(String((call as ModelStreamToolCall)?.arguments || ''))
}

function incompleteLabel(msg: ModelChatMessage) {
  if (msg.incompleteReason === 'aborted') return '已停止生成'
  if (msg.incompleteReason === 'interrupted' || msg.errorCode === MODEL_STREAM_INTERRUPTED) {
    return msg.errorMessage || '模型流中断，结果可能不完整'
  }
  if (msg.errorCode || msg.errorMessage) {
    return `${msg.errorCode ? `[${msg.errorCode}] ` : ''}${msg.errorMessage || '生成失败'}`
  }
  return '生成未完整结束'
}

function onProviderChange() {
  config.modelInstanceId = ''
  config.model = ''
}

function onInstanceChange() {
  const selected = selectableLlmInstances.value.find((item) => item.id === config.modelInstanceId)
  if (selected) {
    config.provider = selected.provider
    config.model = selected.modelName
  }
}

function scrollToBottom() {
  nextTick(() => {
    if (messagesRef.value) {
      messagesRef.value.scrollTop = messagesRef.value.scrollHeight
    }
  })
}

function handleKeydown(e: KeyboardEvent) {
  if (e.ctrlKey && e.key === 'Enter') {
    e.preventDefault()
    handleSend()
  }
}

function buildMessages(): ModelChatMessage[] {
  const result: ModelChatMessage[] = []
  if (config.systemPrompt.trim()) {
    result.push({ role: 'system', content: config.systemPrompt.trim() })
  }
  for (const msg of messages.value) {
    result.push({
      role: msg.role,
      content: msg.content,
      reasoningContent: msg.reasoningContent,
      toolCalls: msg.toolCalls,
      toolCallId: msg.toolCallId,
      name: msg.name,
    })
  }
  return result
}

function commitAssistantFromState(state: ModelStreamState) {
  const assistant: ModelChatMessage = {
    role: 'assistant',
    content: state.content || '',
  }
  if (state.reasoningContent) assistant.reasoningContent = state.reasoningContent
  if (state.toolCalls.length) assistant.toolCalls = state.toolCalls.map((item) => ({ ...item }))
  if (state.finishReason) assistant.finishReason = state.finishReason
  if (state.terminal === 'aborted') {
    assistant.incompleteReason = 'aborted'
  } else if (state.terminal === 'interrupted') {
    assistant.incompleteReason = 'interrupted'
    assistant.errorCode = state.errorCode || MODEL_STREAM_INTERRUPTED
    assistant.errorMessage = state.errorMessage || undefined
  } else if (state.terminal === 'error') {
    assistant.incompleteReason = 'error'
    assistant.errorCode = state.errorCode || undefined
    assistant.errorMessage = state.errorMessage || undefined
  }
  messages.value.push(assistant)
  if (state.usage) lastUsage.value = state.usage
}

async function handleSend() {
  const text = userInput.value.trim()
  if (!text || streaming.value || sending.value) return

  messages.value.push({ role: 'user', content: text })
  userInput.value = ''
  scrollToBottom()

  const allMessages = buildMessages()

  if (config.stream) {
    await startStream(
      {
        modelInstanceId: config.modelInstanceId,
        messages: allMessages,
      },
      {
        onEvent: () => scrollToBottom(),
        onTerminal(state) {
          commitAssistantFromState(state)
          if (state.terminal === 'error' && state.errorCode !== MODEL_STREAM_INTERRUPTED) {
            ElMessage.error(state.errorMessage || '模型流失败')
          } else if (state.terminal === 'interrupted') {
            ElMessage.warning(state.errorMessage || '模型流中断')
          }
          scrollToBottom()
        },
      },
    )
    return
  }

  sending.value = true
  try {
    const { data } = await modelChat({
      modelInstanceId: config.modelInstanceId,
      messages: allMessages,
    })
    const resp = (data?.data ?? data) as ModelChatResponse
    const assistantMsg: ModelChatMessage = {
      role: 'assistant',
      content: resp.content || '',
    }
    if (resp.reasoningContent) assistantMsg.reasoningContent = resp.reasoningContent
    if (resp.toolCalls != null) {
      const calls = Array.isArray(resp.toolCalls) ? resp.toolCalls : [resp.toolCalls]
      assistantMsg.toolCalls = calls as ModelStreamToolCall[]
    }
    if (resp.finishReason) assistantMsg.finishReason = resp.finishReason
    messages.value.push(assistantMsg)
    lastUsage.value = resp.usage || null
  } catch (err) {
    ElMessage.error(err instanceof Error ? err.message : '请求失败')
  } finally {
    sending.value = false
    scrollToBottom()
  }
}

function handleStop() {
  stopStream()
}

function handleClear() {
  messages.value = []
  lastUsage.value = null
}

async function fetchInstances() {
  llmInstancesLoading.value = true
  llmInstancesLoadError.value = false
  try {
    const { data } = await getModelInstances({ modelType: 'LLM' })
    llmInstances.value = normalizeActiveModelInstances(data, 'LLM')
    const allowed = new Set(selectableLlmInstances.value.map((i) => i.id))
    if (config.modelInstanceId && !allowed.has(config.modelInstanceId)) {
      config.modelInstanceId = ''
      config.provider = ''
      config.model = ''
    } else if (config.modelInstanceId) {
      onInstanceChange()
    }
  } catch {
    llmInstances.value = []
    llmInstancesLoadError.value = true
    config.modelInstanceId = ''
    config.provider = ''
    config.model = ''
  } finally {
    llmInstancesLoading.value = false
  }
}

function handleModelSelectVisible(visible: boolean) {
  if (visible) void fetchInstances()
}

onMounted(async () => {
  await fetchInstances()
})
</script>

<style scoped lang="scss">
.playground {
  display: flex;
  width: 100%;
  height: 100%;
  min-height: 0;
  flex-direction: column;
  gap: var(--layout-page-gap);
  box-sizing: border-box;
  overflow: hidden;
  padding: var(--layout-page-start) var(--layout-content-inline) var(--layout-page-end);
}

.playground-body {
  display: flex;
  flex: 1;
  min-height: 0;
  gap: 16px;
}

.config-panel {
  width: 320px;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  gap: 12px;
  overflow-y: auto;
}

.system-prompt-card {
  :deep(textarea) {
    font-family: 'Cascadia Code', 'Consolas', monospace;
    font-size: 13px;
  }
}

.chat-area {
  flex: 1;
  display: flex;
  flex-direction: column;
  background: var(--surface-glass-panel, #fff);
  border-radius: 8px;
  border: 1px solid var(--border-glass, #e5e6eb);
  overflow: hidden;
}

.messages-area {
  flex: 1;
  overflow-y: auto;
  padding: 20px;
}

.chat-empty {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100%;
  color: var(--text-secondary, #475569);
  font-size: 14px;
}

.msg-block {
  margin-bottom: 16px;

  .msg-role {
    font-size: 12px;
    color: var(--text-muted, #64748b);
    margin-bottom: 4px;
  }

  .msg-text {
    padding: 10px 14px;
    border-radius: 8px;
    font-size: 14px;
    line-height: 1.6;
    white-space: pre-wrap;
    word-break: break-word;
  }

  .msg-text--pending {
    color: var(--text-muted, #64748b);
    font-style: italic;
  }

  &.user .msg-text {
    background: color-mix(in srgb, var(--el-color-primary) 10%, #fff);
    color: var(--text-primary);
  }

  &.assistant .msg-text {
    background: var(--surface-glass-control, #f4f4f5);
    color: var(--text-primary);
  }
}

.msg-reasoning {
  margin-bottom: 8px;
  border: 1px solid var(--border-glass, #e5e6eb);
  border-radius: 8px;
  background: color-mix(in srgb, var(--surface-glass-control, #f8fafc) 90%, #fff);
  padding: 8px 10px;

  summary {
    cursor: pointer;
    color: var(--text-secondary, #64748b);
    font-size: 12px;
    font-weight: 600;
  }

  pre {
    margin: 8px 0 0;
    white-space: pre-wrap;
    word-break: break-word;
    font-size: 12px;
    color: var(--text-secondary, #64748b);
    line-height: 1.5;
  }
}

.msg-tools {
  display: grid;
  gap: 8px;
  margin-top: 8px;
}

.msg-tool {
  border: 1px dashed var(--border-glass, #d0d5dd);
  border-radius: 8px;
  padding: 8px 10px;
  background: #fff;

  &__meta {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
    font-size: 12px;
    color: var(--text-secondary, #64748b);
  }

  pre {
    margin: 6px 0 0;
    white-space: pre-wrap;
    word-break: break-word;
    font-size: 12px;
    color: var(--text-primary);
  }
}

.msg-flag {
  margin-top: 6px;
  color: var(--el-color-warning);
  font-size: 12px;
}

.input-area {
  padding: 16px;
  border-top: 1px solid var(--border-glass, #e5e6eb);
}

.input-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 8px;
}
</style>
