<template>
  <ConversationView
    class="eaf-chat__conversation"
    chrome="inline"
    density="compact"
    surface="embed"
    atmosphere
    :snapshot="snapshot"
    :placeholder="placeholder"
    :resolve-status-hint="resolveEmbedStatusHint"
    @send="onSend"
    @stop="onStop"
    @interaction-submit="onInteractionSubmit"
    @interaction-cancel="onInteractionCancel"
  />
</template>

<script setup lang="ts">
import { onUnmounted, ref } from 'vue'
import {
  ConversationView,
  createConversationController,
  createEmbedTransport,
  createEmptySnapshot,
  textContentOf,
  type ConversationMessage,
  type ConversationSnapshot,
} from '@/conversation/embed'
import type { ConversationEventEnvelope } from '@/conversation/core/conversationEvents'
import type { EafPageBridge } from './eafPageBridge'
import { buildEafChatSessionPayload, type EafPageDescriptor } from './embedSession'
import { extractEmbedPublicMetadata } from '@/conversation/core/adapters/adaptEmbedEvent'

interface EmbedChatHostEvent {
  type: string
  data: unknown
  sessionId?: string
  turnId?: string
}

interface EmbedChatHostMessageResponse {
  answer?: string
  sessionId?: string
  metadata?: Record<string, unknown>
  uiRequest?: unknown
  intentType?: unknown
  toolCalls?: unknown
  turnId?: string
}

const props = withDefaults(defineProps<{
  apiBase: string
  tokenProvider: () => Promise<string> | string
  bridge: EafPageBridge
  page?: EafPageDescriptor
  context?: Record<string, unknown>
  placeholder?: string
  preferStream?: boolean
  onEvent?: (event: EmbedChatHostEvent) => void
  onError?: (error: { message: string; cause?: unknown }) => void
  onUnauthorized?: () => Promise<string | void>
}>(), {
  context: () => ({}),
  placeholder: '输入消息',
  preferStream: true,
})

const snapshot = ref<ConversationSnapshot>(createEmptySnapshot())
let sessionId = ''
let context: Record<string, unknown> = { ...props.context }
let cachedToken = ''
let disposed = false
let pendingSend: {
  resolve: (value: EmbedChatHostMessageResponse) => void
  reject: (error: unknown) => void
  /** 本轮开始后才允许用终态 settle，避免沿用上一轮 completed 过早 resolve */
  armed: boolean
} | null = null

async function resolveToken(): Promise<string> {
  const value = await props.tokenProvider()
  cachedToken = String(value || '')
  return cachedToken
}

void resolveToken().catch(() => undefined)

const transport = createEmbedTransport({
  apiBase: props.apiBase,
  tokenProvider: () => cachedToken,
  getSessionId: () => sessionId || undefined,
  setSessionId: (id) => {
    sessionId = id || ''
  },
  getContext: () => context,
  createSessionPayload: () => ({
    ...buildEafChatSessionPayload(props.bridge, props.page),
  } as Record<string, unknown>),
  preferStream: props.preferStream,
  onUnauthorized: async () => {
    const next = await props.onUnauthorized?.()
    if (typeof next === 'string' && next) {
      cachedToken = next
      return next
    }
    return resolveToken()
  },
})

const transportWithEnsure = transport as typeof transport & {
  ensureSession?: (signal?: AbortSignal) => Promise<string>
}

const controller = createConversationController({
  transport,
  onPublicEvent: forwardPublicEvent,
  onChange(state) {
    snapshot.value = state
    if (state.sessionId) sessionId = state.sessionId
    settlePendingIfTerminal(state)
  },
})

/**
 * 将内部 Conversation 事件映射为 createEafChat.onEvent 公共协议。
 * 不转发 supervisor.step / reasoning.delta。
 */
function forwardPublicEvent(event: ConversationEventEnvelope) {
  const data = event.data
  const extras = {
    sessionId: event.sessionId || sessionId || undefined,
    turnId: event.turnId,
  }
  switch (event.type) {
    case 'message.delta':
      props.onEvent?.({ type: 'message.delta', data, ...extras })
      return
    case 'ui.requested':
      props.onEvent?.({ type: 'ui.requested', data, ...extras })
      return
    case 'page.action.requested':
      props.onEvent?.({ type: 'page.action.requested', data, ...extras })
      return
    case 'turn.completed':
    case 'turn.waiting':
      props.onEvent?.({
        type: 'message.completed',
        data: buildMessageResponseFromEvent(event),
        ...extras,
      })
      return
    case 'turn.failed':
      props.onEvent?.({ type: 'error', data, ...extras })
      return
    case 'interaction.submitted':
      props.onEvent?.({ type: 'interaction.submitted', data, ...extras })
      return
    case 'interaction.cancelled':
      props.onEvent?.({ type: 'interaction.cancelled', data, ...extras })
      return
    default:
      return
  }
}

function buildMessageResponseFromEvent(event: ConversationEventEnvelope): EmbedChatHostMessageResponse {
  const record = (event.data && typeof event.data === 'object')
    ? event.data as Record<string, unknown>
    : {}
  const metadata = extractEmbedPublicMetadata(record)
    || (record.metadata && typeof record.metadata === 'object' && !Array.isArray(record.metadata)
      ? { ...(record.metadata as Record<string, unknown>) }
      : undefined)
  return {
    answer: record.answer != null ? String(record.answer) : undefined,
    sessionId: event.sessionId || (record.sessionId != null ? String(record.sessionId) : sessionId) || undefined,
    uiRequest: record.uiRequest,
    metadata,
    intentType: record.intentType,
    toolCalls: record.toolCalls,
    turnId: event.turnId,
  }
}

function settlePendingIfTerminal(state: ConversationSnapshot) {
  if (!pendingSend) return
  const status = state.turnStatus
  if (status === 'sending' || status === 'streaming') {
    pendingSend.armed = true
    return
  }
  if (!pendingSend.armed) return
  if (
    status !== 'completed'
    && status !== 'waiting'
    && status !== 'failed'
    && status !== 'cancelled'
  ) {
    return
  }
  const waiter = pendingSend
  pendingSend = null
  if (status === 'failed') {
    waiter.reject(new Error(state.error || 'Message failed'))
    return
  }
  if (status === 'cancelled') {
    waiter.reject(new DOMException('The operation was aborted.', 'AbortError'))
    return
  }
  waiter.resolve(buildMessageResponse(state))
}

function buildMessageResponse(state: ConversationSnapshot): EmbedChatHostMessageResponse {
  let answer = ''
  let uiRequest: unknown
  let metadata: Record<string, unknown> | undefined
  let intentType: unknown
  let toolCalls: unknown
  for (let index = state.messages.length - 1; index >= 0; index -= 1) {
    const message = state.messages[index]
    if (message.role !== 'assistant') continue
    answer = textContentOf(message)
    for (const block of message.blocks) {
      if (block.type === 'interaction' && (block.state === 'waiting' || block.state === 'submitting')) {
        uiRequest = block.request
      }
    }
    // message.metadata 必须已是后端内层 metadata
    metadata = message.metadata ? { ...message.metadata } : undefined
    if (metadata && 'metadata' in metadata && typeof metadata.metadata === 'object') {
      // 防御：解掉历史嵌套
      metadata = { ...(metadata.metadata as Record<string, unknown>) }
    }
    intentType = message.completion?.intentType
    toolCalls = message.completion?.toolCalls
    break
  }
  if (metadata) {
    delete metadata.result
    delete metadata.answer
    delete metadata.uiRequest
    delete metadata.sessionId
    delete metadata.intentType
    delete metadata.toolCalls
  }
  return {
    answer,
    sessionId: state.sessionId || sessionId || undefined,
    uiRequest,
    metadata: metadata && Object.keys(metadata).length ? metadata : undefined,
    intentType,
    toolCalls,
    turnId: state.turnId,
  }
}

async function sendMessage(message: string): Promise<EmbedChatHostMessageResponse> {
  if (disposed) {
    throw new DOMException('The operation was aborted.', 'AbortError')
  }
  const text = message.trim()
  if (!text) return {}
  if (pendingSend) {
    throw new Error('Another message is still in flight')
  }
  await resolveToken()
  return new Promise<EmbedChatHostMessageResponse>((resolve, reject) => {
    pendingSend = { resolve, reject, armed: false }
    void controller.send(text).catch((error) => {
      if (!pendingSend) return
      pendingSend = null
      reject(error)
    })
  })
}

function setContext(next: Record<string, unknown>) {
  context = { ...context, ...next }
}

function getSessionId() {
  return sessionId || null
}

/** 页面动作轮询专用：没有会话时返回 null，绝不创建会话 */
function getExistingSessionId() {
  return sessionId || null
}

async function ensureHostSession(): Promise<string> {
  if (disposed) throw new DOMException('The operation was aborted.', 'AbortError')
  await resolveToken()
  if (sessionId) return sessionId
  if (typeof transportWithEnsure.ensureSession === 'function') {
    return transportWithEnsure.ensureSession()
  }
  throw new Error('Embed transport cannot ensure session')
}

/** Embed 仅展示通用思考文案，绝不注入 Supervisor / reasoning */
function resolveEmbedStatusHint(message: ConversationMessage) {
  if (message.role !== 'assistant') return undefined
  if (message.status !== 'pending' && message.status !== 'streaming') return undefined
  const hasText = message.blocks.some((b) => b.type === 'text' && b.text)
  return hasText ? '正在生成回答' : '正在处理'
}

function onSend(text: string) {
  void sendMessage(text).catch(reportError)
}

function onStop() {
  void controller.cancel().then(() => {
    if (pendingSend) {
      const waiter = pendingSend
      pendingSend = null
      waiter.reject(new DOMException('The operation was aborted.', 'AbortError'))
    }
  })
}

function onInteractionSubmit(interactionId: string, action: string, values: Record<string, unknown>) {
  void controller.submitInteraction(interactionId, action, values).catch(reportError)
}

/** 取消交互卡片：只提交 action=cancel，绝不 abort 当前流 / session cancel */
function onInteractionCancel(interactionId: string) {
  void controller.submitInteraction(interactionId, 'cancel', {}).catch(reportError)
}

function reportError(error: unknown) {
  const wrapped = { message: error instanceof Error ? error.message : String(error), cause: error }
  props.onError?.(wrapped)
}

function disposeHost() {
  disposed = true
  if (pendingSend) {
    const waiter = pendingSend
    pendingSend = null
    waiter.reject(new DOMException('The operation was aborted.', 'AbortError'))
  }
  controller.dispose()
}

onUnmounted(() => {
  disposeHost()
})

defineExpose({
  sendMessage,
  setContext,
  getSessionId,
  getExistingSessionId,
  ensureHostSession,
  disposeHost,
})
</script>

<style scoped>
.eaf-chat__conversation {
  flex: 1;
  min-height: 0;
  height: 100%;
  border: none;
  border-radius: 0;
  background: transparent;
}
</style>
