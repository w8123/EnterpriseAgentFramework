<template>
  <div class="eaf-chat-host">
    <div
      v-if="authState.status !== 'ready'"
      class="eaf-chat-auth"
      :class="`is-${authState.status}`"
      :role="authState.status === 'error' ? 'alert' : 'status'"
      aria-live="polite"
    >
      <span>{{ authState.message }}</span>
      <button
        v-if="authState.status === 'error'"
        type="button"
        @click="onAuthenticationRetry"
      >
        {{ locale === 'en-US' ? 'Retry' : '重试' }}
      </button>
    </div>
    <ConversationView
      class="eaf-chat__conversation"
      chrome="inline"
      density="compact"
      surface="embed"
      atmosphere
      :snapshot="snapshot"
      :placeholder="placeholder"
      :force-composer-disabled="authState.status !== 'ready'"
      :resolve-status-hint="resolveEmbedStatusHint"
      @send="onSend"
      @stop="onStop"
      @retry="onConversationRetry"
      @interaction-submit="onInteractionSubmit"
      @interaction-cancel="onInteractionCancel"
    />
  </div>
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
import type {
  EafChatAuthState,
  EafChatError,
  EafChatTokenProvider,
  EafChatTokenReason,
  EafChatTokenValue,
} from './eafChat'

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
  tokenProvider: EafChatTokenProvider
  tokenTimeoutMs?: number
  bridge: EafPageBridge
  page?: EafPageDescriptor
  context?: Record<string, unknown>
  placeholder?: string
  locale?: string
  preferStream?: boolean
  onEvent?: (event: EmbedChatHostEvent) => void
  onError?: (error: EafChatError) => void
  onAuthStateChange?: (state: EafChatAuthState) => void
  onTokenChanged?: (token: string) => void
  onSessionChanged?: (sessionId: string) => void
  onUnauthorized?: () => Promise<string | void>
}>(), {
  context: () => ({}),
  placeholder: '输入消息',
  locale: 'zh-CN',
  preferStream: true,
  tokenTimeoutMs: 10_000,
})

const snapshot = ref<ConversationSnapshot>(createEmptySnapshot())
const authState = ref<EafChatAuthState>({
  phase: 'token',
  status: 'loading',
  reason: 'initial',
  attempt: 0,
  message: props.locale === 'en-US' ? 'Connecting to ReachAI…' : '正在连接 ReachAI…',
})
let sessionId = ''
let context: Record<string, unknown> = { ...props.context }
let cachedToken = ''
let cachedTokenExpiresAt = 0
let disposed = false
let tokenAttempt = 0
let tokenRequest: Promise<string> | null = null
let tokenAbortController: AbortController | null = null
let lastSubmittedMessage = ''
let pendingSend: {
  resolve: (value: EmbedChatHostMessageResponse) => void
  reject: (error: unknown) => void
  /** 本轮开始后才允许用终态 settle，避免沿用上一轮 completed 过早 resolve */
  armed: boolean
} | null = null

function updateAuthState(state: EafChatAuthState) {
  authState.value = state
  props.onAuthStateChange?.(state)
}

function tokenFromProviderValue(value: EafChatTokenValue | void): { token: string; expiresAt: number } {
  const raw = typeof value === 'string' ? value : value?.token
  const explicitExpiresAt = typeof value === 'object' && value
    ? Number(value.expiresAt || 0)
    : 0
  const expiresIn = typeof value === 'object' && value
    ? Number(value.expiresIn || 0)
    : 0
  return {
    token: String(raw || '').trim(),
    expiresAt: explicitExpiresAt > 0
      ? explicitExpiresAt
      : expiresIn > 0
        ? Date.now() + expiresIn * 1000
        : 0,
  }
}

function tokenHttpStatus(error: unknown): number | undefined {
  if (!error || typeof error !== 'object') return undefined
  const value = (error as { httpStatus?: unknown; status?: unknown }).httpStatus
    ?? (error as { status?: unknown }).status
  const status = Number(value)
  return Number.isFinite(status) && status > 0 ? status : undefined
}

function tokenError(
  code: string,
  message: string,
  cause?: unknown,
  httpStatus?: number,
): Error & EafChatError {
  const error = new Error(message) as Error & EafChatError
  error.name = 'EafChatTokenError'
  error.code = code
  error.phase = 'token'
  error.retryable = httpStatus !== 403
  error.httpStatus = httpStatus
  error.cause = cause
  return error
}

function normalizeTokenError(error: unknown): Error & EafChatError {
  if (error && typeof error === 'object' && (error as EafChatError).code) {
    return error as Error & EafChatError
  }
  const status = tokenHttpStatus(error)
  if (status === 401) {
    return tokenError(
      'TOKEN_PROVIDER_UNAUTHORIZED',
      props.locale === 'en-US'
        ? 'Authentication expired. Sign in again and retry.'
        : '身份认证已失效，请重新登录后重试',
      error,
      status,
    )
  }
  if (status != null && status >= 500) {
    return tokenError(
      'TOKEN_PROVIDER_UNAVAILABLE',
      props.locale === 'en-US'
        ? 'ReachAI is temporarily unavailable. Retry later.'
        : 'ReachAI 暂时不可用，请稍后重试',
      error,
      status,
    )
  }
  if (error instanceof TypeError) {
    return tokenError(
      'TOKEN_PROVIDER_NETWORK_ERROR',
      props.locale === 'en-US'
        ? 'Network connection failed. Check your network and retry.'
        : '网络连接失败，请检查网络后重试',
      error,
      status,
    )
  }
  return tokenError(
    'TOKEN_PROVIDER_FAILED',
    props.locale === 'en-US'
      ? 'Unable to connect to ReachAI. Retry later.'
      : '暂时无法连接 ReachAI，请稍后重试',
    error,
    status,
  )
}

function safeAuthError(error: EafChatError): Omit<EafChatError, 'cause'> {
  return {
    message: error.message,
    code: error.code,
    phase: error.phase,
    httpStatus: error.httpStatus,
    retryable: error.retryable,
  }
}

async function resolveToken(reason: EafChatTokenReason, force = false): Promise<string> {
  if (disposed) throw new DOMException('The operation was aborted.', 'AbortError')
  const expiring = cachedTokenExpiresAt > 0 && Date.now() + 30_000 >= cachedTokenExpiresAt
  if (cachedToken && !force && !expiring) return cachedToken
  if (tokenRequest) return tokenRequest

  const requestReason: EafChatTokenReason = expiring && reason === 'send' ? 'expiring' : reason

  const attempt = ++tokenAttempt
  const controller = new AbortController()
  tokenAbortController = controller
  updateAuthState({
    phase: 'token',
    status: 'loading',
    reason: requestReason,
    attempt,
    message: props.locale === 'en-US' ? 'Connecting to ReachAI…' : '正在连接 ReachAI…',
  })

  let timeoutHandle: ReturnType<typeof setTimeout> | undefined
  const timeoutMs = Number.isFinite(props.tokenTimeoutMs) && props.tokenTimeoutMs > 0
    ? props.tokenTimeoutMs
    : 10_000
  const pageContext = buildEafChatSessionPayload(props.bridge, props.page)
  const providerContext = {
    reason: requestReason,
    signal: controller.signal,
    attempt,
    sessionId: sessionId || undefined,
    pageKey: pageContext.pageKey,
    pageInstanceId: pageContext.pageInstanceId,
    route: pageContext.route,
    origin: props.page?.origin || (typeof location !== 'undefined' ? location.origin : ''),
  }
  const providerPromise = Promise.resolve().then(async () => {
    if (reason === 'unauthorized' && props.onUnauthorized) {
      const refreshed = await props.onUnauthorized()
      if (refreshed) return refreshed
    }
    return props.tokenProvider(providerContext)
  })
  const timeoutPromise = new Promise<never>((_, reject) => {
    timeoutHandle = setTimeout(() => {
      controller.abort()
      reject(tokenError(
        'TOKEN_PROVIDER_TIMEOUT',
        props.locale === 'en-US'
          ? 'Connection timed out. Retry.'
          : '连接超时，请重试',
      ))
    }, timeoutMs)
  })

  tokenRequest = Promise.race([providerPromise, timeoutPromise])
    .then((value) => {
      if (disposed) throw new DOMException('The operation was aborted.', 'AbortError')
      const next = tokenFromProviderValue(value)
      if (!next.token) {
        throw tokenError(
          'TOKEN_PROVIDER_EMPTY',
          props.locale === 'en-US'
            ? 'Authentication token is unavailable. Retry.'
            : '登录凭证不可用，请重试',
        )
      }
      cachedToken = next.token
      cachedTokenExpiresAt = next.expiresAt
      props.onTokenChanged?.(next.token)
      updateAuthState({
        phase: 'token',
        status: 'ready',
        reason: requestReason,
        attempt,
      })
      return next.token
    })
    .catch((error) => {
      if (error instanceof DOMException && error.name === 'AbortError' && disposed) throw error
      const normalized = normalizeTokenError(error)
      cachedToken = ''
      cachedTokenExpiresAt = 0
      props.onTokenChanged?.('')
      updateAuthState({
        phase: 'token',
        status: 'error',
        reason: requestReason,
        attempt,
        message: normalized.message,
        error: safeAuthError(normalized),
      })
      throw normalized
    })
    .finally(() => {
      if (timeoutHandle !== undefined) clearTimeout(timeoutHandle)
      if (tokenAbortController === controller) tokenAbortController = null
      tokenRequest = null
    })

  return tokenRequest
}

async function refreshToken(reason: EafChatTokenReason = 'unauthorized'): Promise<string> {
  cachedToken = ''
  cachedTokenExpiresAt = 0
  props.onTokenChanged?.('')
  return resolveToken(reason, true)
}

function retryAuthentication(): Promise<string> {
  return refreshToken('retry')
}

function updateSessionId(nextSessionId: string | null | undefined) {
  const next = String(nextSessionId || '')
  const changed = next !== sessionId
  sessionId = next
  if (changed && next) props.onSessionChanged?.(next)
}

const transport = createEmbedTransport({
  apiBase: props.apiBase,
  tokenProvider: () => cachedToken,
  getSessionId: () => sessionId || undefined,
  setSessionId: updateSessionId,
  getContext: () => context,
  createSessionPayload: () => ({
    ...buildEafChatSessionPayload(props.bridge, props.page),
  } as Record<string, unknown>),
  preferStream: props.preferStream,
  onUnauthorized: () => refreshToken('unauthorized'),
})

const transportWithEnsure = transport as typeof transport & {
  ensureSession?: (signal?: AbortSignal) => Promise<string>
}

const controller = createConversationController({
  transport,
  onPublicEvent: forwardPublicEvent,
  onChange(state) {
    snapshot.value = state
    if (state.sessionId) updateSessionId(state.sessionId)
    settlePendingIfTerminal(state)
  },
})

// UI 已挂载后再异步获取 Token；失败保留可见入口并进入可重试状态。
void resolveToken('initial').catch((error) => {
  if (!disposed) reportError(error)
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
  lastSubmittedMessage = text
  await resolveToken('send')
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
  await resolveToken('send')
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

function onAuthenticationRetry() {
  void retryAuthentication().catch(reportError)
}

function onConversationRetry() {
  if (authState.value.status === 'error') {
    onAuthenticationRetry()
    return
  }
  if (!lastSubmittedMessage || pendingSend) return
  void sendMessage(lastSubmittedMessage).catch(reportError)
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
  const candidate = error && typeof error === 'object' ? error as EafChatError : undefined
  const wrapped: EafChatError = {
    message: candidate?.message || (error instanceof Error ? error.message : String(error)),
    cause: candidate?.cause ?? error,
    code: candidate?.code,
    phase: candidate?.phase,
    httpStatus: candidate?.httpStatus ?? tokenHttpStatus(error),
    retryable: candidate?.retryable,
  }
  try {
    props.onError?.(wrapped)
  } catch {
    // Consumer callbacks must not create an unhandled SDK rejection.
  }
}

function disposeHost() {
  disposed = true
  tokenAbortController?.abort()
  tokenAbortController = null
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
  retryAuthentication,
  refreshToken,
  setContext,
  getSessionId,
  getExistingSessionId,
  ensureHostSession,
  disposeHost,
})
</script>

<style scoped>
.eaf-chat-host {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-height: 0;
  height: 100%;
}

.eaf-chat-auth {
  position: relative;
  z-index: 2;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin: 10px 12px 0;
  padding: 8px 10px;
  border: 1px solid rgb(var(--reachai-chat-primary-rgb, 99 102 241) / 0.22);
  border-radius: 10px;
  color: var(--reachai-chat-text-muted, #52605f);
  background: var(--reachai-chat-glass-control, rgb(255 255 255 / 0.82));
  font-size: 13px;
}

.eaf-chat-auth.is-error {
  border-color: color-mix(in srgb, var(--reachai-chat-danger, #b42318) 28%, transparent);
  color: var(--reachai-chat-danger, #b42318);
}

.eaf-chat-auth button {
  border: 1px solid currentColor;
  border-radius: 8px;
  padding: 4px 10px;
  color: inherit;
  background: transparent;
  cursor: pointer;
  font: inherit;
}

.eaf-chat__conversation {
  flex: 1;
  min-height: 0;
  height: 100%;
  border: none;
  border-radius: 0;
  background: transparent;
}
</style>
