<template>
  <div class="page-container playground">
    <PageHeader
      variant="standard"
      domain="platform"
      title="模型调试台"
      compact
    />

    <div class="playground-body">
      <!-- 左侧：配置 -->
      <div class="config-panel">
        <el-card shadow="never">
          <template #header>模型配置</template>
          <el-form label-width="80px" size="default">
            <el-form-item label="厂商">
              <el-select v-model="config.provider" placeholder="请选择厂商" filterable style="width: 100%" @change="onProviderChange">
                <el-option
                  v-for="item in llmProviderOptions"
                  :key="item"
                  :label="item"
                  :value="item"
                />
              </el-select>
            </el-form-item>
            <el-form-item label="实例">
              <el-select
                v-model="config.modelInstanceId"
                placeholder="请选择模型实例"
                style="width: 100%"
                filterable
                :disabled="!config.provider"
                @change="onInstanceChange"
              >
                <el-option
                  v-for="item in filteredLlmInstances"
                  :key="item.id"
                  :label="`${item.name} / ${item.modelName}`"
                  :value="item.id"
                />
              </el-select>
            </el-form-item>
            <el-form-item label="Model">
              <el-input v-model="config.model" disabled placeholder="选择实例后自动带出" />
            </el-form-item>
            <el-form-item label="流式">
              <el-switch v-model="config.stream" />
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
          />
        </el-card>

        <el-card v-if="lastUsage" shadow="never">
          <template #header>Token 用量</template>
          <el-descriptions :column="1" size="small" border>
            <el-descriptions-item label="Prompt">{{ lastUsage.promptTokens }}</el-descriptions-item>
            <el-descriptions-item label="Completion">{{ lastUsage.completionTokens }}</el-descriptions-item>
            <el-descriptions-item label="Total">{{ lastUsage.totalTokens }}</el-descriptions-item>
          </el-descriptions>
        </el-card>
      </div>

      <!-- 右侧：对话 -->
      <div class="chat-area">
        <div class="messages-area" ref="messagesRef">
          <div v-if="messages.length === 0" class="chat-empty">
            <p>选择模型后发送消息开始调试</p>
          </div>
          <div
            v-for="(msg, idx) in messages"
            :key="idx"
            class="msg-block"
            :class="msg.role"
          >
            <div class="msg-role">{{ msg.role === 'user' ? '你' : msg.role === 'tool' ? '工具' : 'AI' }}</div>
            <div class="msg-text">{{ msg.content }}</div>
          </div>
          <div v-if="streamingContent" class="msg-block assistant">
            <div class="msg-role">AI</div>
            <div class="msg-text">{{ streamingContent }}</div>
          </div>
        </div>

        <div class="input-area">
          <el-input
            v-model="userInput"
            type="textarea"
            :rows="3"
            placeholder="输入消息... (Ctrl+Enter 发送)"
            @keydown="handleKeydown"
          />
          <div class="input-actions">
            <el-button @click="handleClear" :disabled="streaming">清空</el-button>
            <el-button
              type="primary"
              @click="handleSend"
              :loading="sending"
              :disabled="streaming || !config.modelInstanceId"
            >发送</el-button>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted, nextTick } from 'vue'
import type { ModelChatMessage, ModelChatResponse, TokenUsage, ModelInstance } from '@/types/model'
import { getModelInstances, modelChat } from '@/api/model'
import { useSSE } from '@/composables/useSSE'
import PageHeader from '@/components/common/PageHeader.vue'

const llmInstances = ref<ModelInstance[]>([])
/** 调试台仅允许选择状态为「可用」的实例，停用/异常不可选 */
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

const { content: sseContent, isStreaming: streaming, start: startSSE, stop: stopSSE } = useSSE()
const streamingContent = computed(() => streaming.value ? sseContent.value : '')

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
  result.push(...messages.value)
  return result
}

async function handleSend() {
  const text = userInput.value.trim()
  if (!text) return

  messages.value.push({ role: 'user', content: text })
  userInput.value = ''
  scrollToBottom()

  const allMessages = buildMessages()

  if (config.stream) {
    await startSSE('/model/chat/stream', {
      modelInstanceId: config.modelInstanceId,
      messages: allMessages,
    }, {
      onChunk: () => scrollToBottom(),
      onDone(full) {
        messages.value.push({ role: 'assistant', content: full })
        scrollToBottom()
      },
    })
  } else {
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
      if (resp.reasoningContent) {
        assistantMsg.reasoningContent = resp.reasoningContent
      }
      if (resp.toolCalls != null) {
        assistantMsg.toolCalls = Array.isArray(resp.toolCalls)
          ? resp.toolCalls
          : [resp.toolCalls]
      }
      messages.value.push(assistantMsg)
      lastUsage.value = resp.usage || null
    } catch {
      messages.value.push({ role: 'assistant', content: '请求失败' })
    } finally {
      sending.value = false
      scrollToBottom()
    }
  }
}

function handleClear() {
  messages.value = []
  lastUsage.value = null
}

async function fetchInstances() {
  try {
    const { data } = await getModelInstances({ modelType: 'LLM' })
    llmInstances.value = data?.data ?? (Array.isArray(data) ? data : [])
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
    config.modelInstanceId = ''
    config.provider = ''
    config.model = ''
  }
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
  height: auto;
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
  background: #fff;
  border-radius: 8px;
  border: 1px solid #e5e6eb;
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
  color: #475569;
  font-size: 14px;
}

.msg-block {
  margin-bottom: 16px;

  .msg-role {
    font-size: 12px;
    color: #64748b;
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

  &.user .msg-text {
    background: #ecf5ff;
    color: var(--text-primary);
  }

  &.assistant .msg-text {
    background: #f4f4f5;
    color: var(--text-primary);
  }

  &.system .msg-text {
    background: #fdf6ec;
    color: #e6a23c;
    font-size: 12px;
  }
}

.input-area {
  padding: 16px;
  border-top: 1px solid #e5e6eb;
}

.input-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 8px;
}

</style>
