<template>
  <div class="page-container playground">
    <div class="page-header">
      <h2>模型调试台</h2>
    </div>

    <div class="playground-body">
      <!-- 左侧：配置 -->
      <div class="config-panel">
        <el-card shadow="never">
          <template #header>模型配置</template>
          <el-form label-width="80px" size="default">
            <el-form-item label="Provider">
              <el-select v-model="config.provider" placeholder="选择 Provider" style="width: 100%" @change="onProviderChange">
                <el-option
                  v-for="p in providers"
                  :key="p.name"
                  :label="p.name"
                  :value="p.name"
                />
              </el-select>
            </el-form-item>
            <el-form-item label="Model">
              <el-select v-model="config.model" placeholder="选择模型" style="width: 100%">
                <el-option v-for="m in currentModels" :key="m" :label="m" :value="m" />
              </el-select>
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
            <div class="msg-role">{{ msg.role === 'user' ? '你' : 'AI' }}</div>
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
              :disabled="streaming || !config.provider"
            >发送</el-button>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted, nextTick } from 'vue'
import type { ProviderInfo, ModelChatMessage, TokenUsage } from '@/types/model'
import { getProviders, modelChat, modelChatStream } from '@/api/model'
import { useSSE } from '@/composables/useSSE'

const providers = ref<ProviderInfo[]>([])
const config = reactive({
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

const currentModels = computed(() => {
  const p = providers.value.find((p) => p.name === config.provider)
  return p?.models || []
})

function onProviderChange() {
  config.model = currentModels.value[0] || ''
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
      provider: config.provider,
      model: config.model,
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
        provider: config.provider,
        model: config.model,
        messages: allMessages,
      })
      const resp = data?.data ?? data
      messages.value.push({ role: 'assistant', content: (resp as any).content || '' })
      lastUsage.value = (resp as any).usage || null
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

async function fetchProviders() {
  try {
    const { data } = await getProviders()
    providers.value = data?.data ?? (Array.isArray(data) ? data : [])
  } catch {
    providers.value = []
  }
}

onMounted(fetchProviders)
</script>

<style scoped lang="scss">
.playground-body {
  display: flex;
  gap: 16px;
  height: calc(100vh - 56px - 80px);
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
  color: #c0c4cc;
  font-size: 14px;
}

.msg-block {
  margin-bottom: 16px;

  .msg-role {
    font-size: 12px;
    color: #909399;
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
    color: #303133;
  }

  &.assistant .msg-text {
    background: #f4f4f5;
    color: #303133;
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
