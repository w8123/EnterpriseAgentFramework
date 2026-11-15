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
    <div
      v-if="authState.status === 'ready' && !memoryOpen && !aiToolOpen && (memoryPanel || (aiToolPanel && !aiToolHeaderEntry) || hasSession)"
      class="eaf-chat-tools"
    >
      <button v-if="hasSession" type="button" class="eaf-chat-memory-toggle"
        :disabled="clearBlocked" @click="clearConfirm = true">
        {{ locale === 'en-US' ? 'Clear context' : '清除上下文' }}
      </button>
      <button
        v-if="aiToolPanel && !aiToolHeaderEntry"
        type="button"
        class="eaf-chat-memory-toggle"
        :title="locale === 'en-US' ? 'Use in my AI tool' : '在我的 AI 工具中使用'"
        @click="openAiTools"
      >
        {{ locale === 'en-US' ? 'Use in my AI tool' : '在我的 AI 工具中使用' }}
      </button>
      <button
        v-if="memoryPanel"
        type="button"
        class="eaf-chat-memory-toggle"
        :title="locale === 'en-US' ? 'My memories' : '我的记忆'"
        @click="memoryOpen = true"
      >
        {{ locale === 'en-US' ? 'Memories' : '我的记忆' }}
      </button>
    </div>
    <div v-if="clearConfirm" class="eaf-chat-auth" role="group"
      :aria-label="locale === 'en-US' ? 'Confirm context clear' : '确认清除上下文'">
      <span>{{ locale === 'en-US' ? 'Clear the current conversation context? Saved memories and audit records remain.' : '清除当前对话上下文？已保存的记忆和审计记录会保留。' }}</span>
      <button type="button" :disabled="clearing" @click="clearConfirm = false">{{ locale === 'en-US' ? 'Cancel' : '取消' }}</button>
      <button type="button" :disabled="clearBlocked" @click="onClearContext">{{ clearing ? (locale === 'en-US' ? 'Clearing…' : '清除中…') : (locale === 'en-US' ? 'Clear' : '清除') }}</button>
    </div>
    <div v-if="clearError" class="eaf-chat-auth is-error" role="alert">{{ clearError }}</div>
    <div v-if="navigationError" class="eaf-chat-auth is-error" role="alert">{{ navigationError }}</div>
    <EmbedAiToolPanel
      v-if="aiToolOpen"
      :api-base="apiBase"
      :get-token="() => resolveToken('send')"
      :locale="locale"
      @close="closeAiTools"
    />
    <EmbedMemoryPanel
      v-if="memoryOpen"
      :api-base="apiBase"
      :get-token="() => resolveToken('send')"
      :locale="locale"
      @close="memoryOpen = false"
    />
    <EmbedShortcutBar
      v-if="authState.status === 'ready' && !memoryOpen && !aiToolOpen && shortcutsEnabled && shortcutsActive"
      :api-base="apiBase"
      :get-token="() => resolveToken('send')"
      :route="currentRoute"
      :locale="locale"
      :disabled="clearBlocked"
      @pick="onSend"
    />
    <ConversationView
      class="eaf-chat__conversation"
      :inert="memoryOpen || aiToolOpen"
      chrome="inline"
      density="compact"
      surface="embed"
      atmosphere
      :snapshot="snapshot"
      :placeholder="placeholder"
      :force-composer-disabled="authState.status !== 'ready' || clearing"
      :resolve-status-hint="resolveEmbedStatusHint"
      :fetch-approval-status="controller.approvalStatus"
      @send="onSend"
      @stop="onStop"
      @retry="onConversationRetry"
      @interaction-submit="onInteractionSubmit"
      @interaction-cancel="onInteractionCancel"
    />
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onUnmounted, ref } from 'vue'
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
import EmbedAiToolPanel from './EmbedAiToolPanel.vue'
import EmbedMemoryPanel from './EmbedMemoryPanel.vue'
import EmbedShortcutBar from './EmbedShortcutBar.vue'
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
  terminal?: boolean
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
  /** Show the end user's 我的记忆 entry (their own personal memories). */
  memoryPanel?: boolean
  /** Show 在我的 AI 工具中使用: the end user connects their own Claude Code / Codex as themselves. */
  aiToolPanel?: boolean
  /** The SDK shell owns the entry; standalone hosts keep the text entry. */
  aiToolHeaderEntry?: boolean
  /** Show 快捷操作 a manager recommended for the current page (主动感知). */
  shortcutsEnabled?: boolean
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
  memoryPanel: true,
  aiToolPanel: true,
  shortcutsEnabled: true,
  tokenTimeoutMs: 10_000,
})

const snapshot = ref<ConversationSnapshot>(createEmptySnapshot())
const memoryOpen = ref(false)
const aiToolOpen = ref(false)
let aiToolTrigger: HTMLElement | null = null
const clearConfirm = ref(false)
const clearError = ref('')
const navigationError = ref('')
const clearing = ref(false)
const sending = ref(false)
const hasSession = ref(false)
const pageRebinding = ref(false)
const clearBlocked = computed(() => clearing.value || sending.value || pageRebinding.value || documentContinuationBusy.value || pageTransferBusy.value
  || ['sending', 'streaming', 'waiting'].includes(snapshot.value.turnStatus))
const authState = ref<EafChatAuthState>({
  phase: 'token',
  status: 'loading',
  reason: 'initial',
  attempt: 0,
  message: props.locale === 'en-US' ? 'Connecting to ReachAI…' : '正在连接 ReachAI…',
})
let sessionId = ''
let context: Record<string, unknown> = { ...props.context }
// Props describe the initial page only. During an SPA navigation the facade
// swaps these bindings after the target page has registered its actions.
let activeBridge = props.bridge
let activePage = props.page
const currentRoute = ref(buildEafChatSessionPayload(props.bridge, props.page).route)
// Shortcuts load only once the employee actually opens the chat, so an idle page makes no extra requests.
const shortcutsActive = ref(false)
function activateShortcuts() {
  shortcutsActive.value = true
}
let cachedToken = ''
let cachedTokenExpiresAt = 0
let disposed = false
let tokenAttempt = 0
let tokenRequest: Promise<string> | null = null
let tokenAbortController: AbortController | null = null
let rebindAbortController: AbortController | null = null
let continuationAbortController: AbortController | null = null
const attemptedDocumentContinuations = new Set<string>()
const documentContinuationBusy = ref(false)
const pageTransferBusy = ref(false)
let pageTransferAbortController: AbortController | null = null
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
    ?? (error as { response?: { status?: unknown } }).response?.status
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
  if (error && typeof error === 'object' && (error as EafChatError).code && (error as EafChatError).phase === 'token') {
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
  if (status === 403) {
    return tokenError(
      'TOKEN_PROVIDER_FORBIDDEN',
      props.locale === 'en-US'
        ? 'This page cannot access the assistant. Ask your administrator to check access and embed settings.'
        : '当前页面无法访问该助手，请联系管理员检查权限与接入设置',
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
  if (error instanceof TypeError || (error && typeof error === 'object' && (error as { code?: string }).code === 'ERR_NETWORK')) {
    return tokenError(
      'TOKEN_PROVIDER_NETWORK_ERROR',
      props.locale === 'en-US'
        ? 'Network connection failed. Check your network and retry.'
        : '网络连接失败，请检查网络后重试',
      error,
      status,
    )
  }
  const code = error && typeof error === 'object' ? (error as { code?: unknown }).code : undefined
  if (typeof code === 'string' && !code.startsWith('ERR_') && code !== 'ECONNABORTED') {
    return error as Error & EafChatError
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

function navigationInProgressError(): EafChatError {
  return tokenError('PAGE_NAVIGATION_IN_PROGRESS', props.locale === 'en-US'
    ? 'The page is changing. Retry shortly.' : '页面正在切换，请稍后重试')
}

async function resolveToken(reason: EafChatTokenReason, force = false): Promise<string> {
  if (disposed) throw new DOMException('The operation was aborted.', 'AbortError')
  if (pageRebinding.value && reason !== 'page-navigation') {
    throw navigationInProgressError()
  }
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
  const pageContext = buildEafChatSessionPayload(activeBridge, activePage)
  const providerContext = {
    reason: requestReason,
    signal: controller.signal,
    attempt,
    sessionId: sessionId || undefined,
    pageKey: pageContext.pageKey,
    pageInstanceId: pageContext.pageInstanceId,
    route: pageContext.route,
    origin: activePage?.origin || (typeof location !== 'undefined' ? location.origin : ''),
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
      reject(tokenError(
        'TOKEN_PROVIDER_TIMEOUT',
        props.locale === 'en-US'
          ? 'Connection timed out. Retry.'
          : '连接超时，请重试',
      ))
      controller.abort()
    }, timeoutMs)
  })
  let rejectOnAbort: (() => void) | undefined
  const abortPromise = new Promise<never>((_, reject) => {
    rejectOnAbort = () => reject(new DOMException('The operation was aborted.', 'AbortError'))
    controller.signal.addEventListener('abort', rejectOnAbort, { once: true })
  })

  tokenRequest = Promise.race([providerPromise, timeoutPromise, abortPromise])
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
      if (requestReason !== 'page-navigation') props.onTokenChanged?.(next.token)
      updateAuthState({
        phase: 'token',
        status: 'ready',
        reason: requestReason,
        attempt,
      })
      return next.token
    })
    .catch((error) => {
      if (error instanceof DOMException && error.name === 'AbortError' && (disposed || controller.signal.aborted)) throw error
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
      if (rejectOnAbort) controller.signal.removeEventListener('abort', rejectOnAbort)
      if (tokenAbortController === controller) tokenAbortController = null
      tokenRequest = null
    })

  return tokenRequest
}

async function refreshToken(reason: EafChatTokenReason = 'unauthorized'): Promise<string> {
  if (disposed) throw new DOMException('The operation was aborted.', 'AbortError')
  if (pageRebinding.value && reason !== 'page-navigation') throw navigationInProgressError()
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
  hasSession.value = Boolean(next)
  if (changed && next) props.onSessionChanged?.(next)
}

const transport = createEmbedTransport({
  apiBase: props.apiBase,
  tokenProvider: () => cachedToken,
  getSessionId: () => sessionId || undefined,
  setSessionId: updateSessionId,
  getContext: () => context,
  createSessionPayload: () => ({
    ...buildEafChatSessionPayload(activeBridge, activePage),
  } as Record<string, unknown>),
  preferStream: props.preferStream,
  restoreHistory: true,
  onUnauthorized: () => refreshToken('unauthorized'),
})

const transportWithEnsure = transport as typeof transport & {
  ensureSession?: (signal?: AbortSignal) => Promise<string>
}

const controller = createConversationController({
  transport,
  onPublicEvent: forwardPublicEvent,
  onChange(state) {
    if (state.turnStatus === 'sending' && snapshot.value.turnStatus !== 'sending') {
      props.onEvent?.({ type: 'turn.preparing', data: {}, sessionId: sessionId || undefined })
    }
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
    case 'turn.progress':
      props.onEvent?.({ type: 'turn.progress', data, ...extras })
      return
    case 'turn.started':
      props.onEvent?.({ type: 'turn.started', data, ...extras })
      return
    case 'turn.completed':
    case 'turn.waiting':
      props.onEvent?.({
        type: 'message.completed',
        data: buildMessageResponseFromEvent(event),
        terminal: event.type === 'turn.completed',
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
  if (pendingSend || sending.value || clearing.value || documentContinuationBusy.value || pageTransferBusy.value) {
    throw new Error('Another message is still in flight')
  }
  sending.value = true
  try {
    lastSubmittedMessage = text
    await resolveToken('send')
    if (disposed) throw new DOMException('The operation was aborted.', 'AbortError')
    return await new Promise<EmbedChatHostMessageResponse>((resolve, reject) => {
      pendingSend = { resolve, reject, armed: false }
      void controller.send(text).catch((error) => {
        if (!pendingSend) return
        pendingSend = null
        reject(error)
      })
    })
  } finally {
    sending.value = false
  }
}

async function clearContext() {
  if (disposed) throw new DOMException('The operation was aborted.', 'AbortError')
  if (clearBlocked.value) throw new Error('Conversation is busy')
  clearing.value = true
  clearError.value = ''
  try {
    await resolveToken('send')
    await controller.clearSession()
    lastSubmittedMessage = ''
    clearConfirm.value = false
    props.onEvent?.({ type: 'context.cleared', data: {}, sessionId: sessionId || undefined })
  } finally {
    clearing.value = false
  }
}

function onClearContext() {
  void clearContext().catch((error) => {
    const status = tokenHttpStatus(error)
    clearError.value = status === 409
      ? (props.locale === 'en-US' ? 'The conversation is busy. Try again after it finishes.' : '对话正在处理中，请结束后重试。')
      : status === 423
        ? (props.locale === 'en-US' ? 'This conversation is protected and cannot be cleared.' : '该对话受保护，暂时无法清除。')
        : (props.locale === 'en-US' ? 'Could not clear the context. Try again later.' : '清除上下文失败，请稍后重试。')
    reportError(error)
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

async function restoreHistory() {
  if (disposed) throw new DOMException('The operation was aborted.', 'AbortError')
  if (!sessionId) throw new Error('An authenticated Embed session is required to restore history')
  if (sending.value || pendingSend || clearing.value) throw new Error('Conversation is busy')
  return controller.restore()
}

async function preparePageTransfer() {
  if (disposed) throw new DOMException('The operation was aborted.', 'AbortError')
  if (!sessionId || clearBlocked.value || pendingSend) throw new Error('The conversation is not ready to move')
  const current = sessionId
  const bridge = activeBridge
  pageTransferBusy.value = true
  const abort = new AbortController()
  pageTransferAbortController = abort
  try {
    const token = await resolveToken('send')
    if (disposed || abort.signal.aborted || sessionId !== current || activeBridge !== bridge) {
      throw new DOMException('The page session changed', 'AbortError')
    }
    const response = await fetch(`${props.apiBase}/chat/sessions/${encodeURIComponent(current)}/page-transfers`, {
      method: 'POST', headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
      body: '{}', signal: abort.signal,
    })
    if (!response.ok) throw new Error(`Unable to prepare page transfer: HTTP ${response.status}`)
    const payload = await response.json()
    const data = payload?.data
    if (disposed || abort.signal.aborted || sessionId !== current || activeBridge !== bridge) {
      throw new DOMException('The page session changed', 'AbortError')
    }
    if ((payload?.code && payload.code !== 200 && payload.code !== 0)
        || typeof data?.transferTicket !== 'string' || !/^[A-Za-z0-9_-]{32,128}$/.test(data.transferTicket)
        || typeof data.expiresInSeconds !== 'number' || !Number.isFinite(data.expiresInSeconds)
        || data.expiresInSeconds <= 0 || data.expiresInSeconds > 86400) throw new Error('Invalid page transfer response')
    return { sessionId: current, transferTicket: data.transferTicket, expiresInSeconds: data.expiresInSeconds }
  } finally {
    if (pageTransferAbortController === abort) pageTransferAbortController = null
    pageTransferBusy.value = false
  }
}

async function claimPageTransfer(transfer: { sessionId: string; transferTicket: string }) {
  if (disposed) throw new DOMException('The operation was aborted.', 'AbortError')
  if (sessionId || pageRebinding.value || pageTransferBusy.value || sending.value || pendingSend || clearing.value
      || !activePage?.pageKey || !transfer?.sessionId || !/^[A-Za-z0-9_-]{32,128}$/.test(transfer.transferTicket)) {
    throw new Error('The page transfer cannot be claimed')
  }
  pageRebinding.value = true
  const abort = new AbortController()
  pageTransferAbortController = abort
  try {
    const token = await refreshToken('page-navigation')
    const target = buildEafChatSessionPayload(activeBridge, activePage)
    const url = `${props.apiBase}/chat/sessions/${encodeURIComponent(transfer.sessionId)}/page-transfers/claim`
    const init: RequestInit = { method: 'POST', signal: abort.signal,
      headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({ transferTicket: transfer.transferTicket, bridgeActions: target.bridgeActions, sdkVersion: target.sdkVersion }) }
    let response: Response | undefined
    try { response = await fetch(url, init) } catch (error) {
      if (!(error instanceof TypeError) || abort.signal.aborted) throw error
    }
    if ((!response || [502, 503, 504].includes(response.status)) && !abort.signal.aborted) response = await fetch(url, init)
    if (!response || disposed || abort.signal.aborted) throw new DOMException('The operation was aborted.', 'AbortError')
    const payload = await response.json()
    if (disposed || abort.signal.aborted) throw new DOMException('The operation was aborted.', 'AbortError')
    const bound = payload?.data
    if (!response.ok || (payload?.code && payload.code !== 200 && payload.code !== 0)
        || bound?.sessionId !== transfer.sessionId || bound.pageKey !== target.pageKey
        || bound.pageInstanceId !== target.pageInstanceId || bound.route !== target.route) {
      throw new Error('Page transfer was not confirmed')
    }
    updateSessionId(transfer.sessionId)
    props.onTokenChanged?.(token)
  } finally {
    if (pageTransferAbortController === abort) pageTransferAbortController = null
    pageRebinding.value = false
  }
}

async function continueDocumentNavigation(navigationRequestId: string, cancel = false) {
  if (disposed) throw new DOMException('The operation was aborted.', 'AbortError')
  if (!sessionId || !navigationRequestId || pageRebinding.value || sending.value || clearing.value) {
    throw new Error('The target session is not ready for navigation continuation')
  }
  if (attemptedDocumentContinuations.has(navigationRequestId)) {
    throw new Error('The navigation continuation was already submitted; check its result')
  }
  if (documentContinuationBusy.value) throw new Error('A navigation continuation is already in progress')
  documentContinuationBusy.value = true
  if (cancel) navigationError.value = ''
  const expectedSessionId = sessionId
  const expectedBridge = activeBridge
  const expectedPage = activePage
  const ensureCurrent = () => {
    if (disposed || clearing.value || sending.value || pendingSend || pageRebinding.value || sessionId !== expectedSessionId
        || activeBridge !== expectedBridge || activePage !== expectedPage || abort?.signal.aborted) {
      throw new DOMException('The page session changed before continuation', 'AbortError')
    }
  }
  let abort: AbortController | undefined
  try {
    const token = await resolveToken('send')
    ensureCurrent()
    abort = new AbortController()
    continuationAbortController = abort
    const response = await fetch(`${props.apiBase}/chat/sessions/${encodeURIComponent(sessionId)}`
      + `/page-bridge/navigations/${encodeURIComponent(navigationRequestId)}/${cancel ? 'cancelled-interaction' : 'interaction'}`, {
      headers: { Authorization: `Bearer ${token}` }, signal: abort.signal,
    })
    ensureCurrent()
    if (!response.ok) throw new Error(`Navigation continuation is not ready: HTTP ${response.status}`)
    const payload = await response.json()
    ensureCurrent()
    const data = payload?.data
    if ((payload?.code && payload.code !== 200 && payload.code !== 0)
        || data?.navigationRequestId !== navigationRequestId || typeof data?.interactionId !== 'string'
        || !/^wfi_[a-f0-9]{32}$/.test(data.interactionId)) {
      throw new Error('Invalid navigation continuation response')
    }
    // Mark before the POST. An uncertain write outcome must not be retried automatically.
    attemptedDocumentContinuations.add(navigationRequestId)
    await controller.submitInteraction(data.interactionId, cancel ? 'cancel' : 'confirm', {})
  } catch (error) {
    if (cancel && !disposed) navigationError.value = props.locale === 'en-US'
      ? 'The cancellation could not be confirmed. Check the conversation before continuing.'
      : '取消状态尚未确认，请核对当前对话后再继续。'
    throw error
  } finally {
    if (continuationAbortController === abort) continuationAbortController = null
    documentContinuationBusy.value = false
  }
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

async function rebindPage(
  bridge: EafPageBridge,
  page: EafPageDescriptor,
  navigationRequestId: string,
  resumeSessionId?: string,
) {
  if (disposed) throw new DOMException('The operation was aborted.', 'AbortError')
  const targetSessionId = resumeSessionId || sessionId
  if (!targetSessionId) throw new Error('An active Embed chat session is required before page rebinding')
  if (resumeSessionId && (sessionId || sending.value || pendingSend)) {
    throw new Error('A page navigation can only be resumed in an unused chat host')
  }
  if (!navigationRequestId) throw new Error('A platform navigation request id is required before page rebinding')
  if (pageRebinding.value || clearing.value) throw new Error('Page navigation is already in progress')

  const previousBridge = activeBridge
  const previousPage = activePage
  pageRebinding.value = true
  let switched = false
  try {
    // An in-flight source-page refresh cannot authenticate a target-page rebind.
    if (tokenRequest) await tokenRequest
    if (disposed) throw new DOMException('The operation was aborted.', 'AbortError')
    activeBridge = bridge
    activePage = page
    currentRoute.value = buildEafChatSessionPayload(bridge, page).route
    switched = true
    const token = await refreshToken('page-navigation')
    if (disposed) throw new DOMException('The operation was aborted.', 'AbortError')
    const rebindController = new AbortController()
    rebindAbortController = rebindController
    // v2 guarantees replay cannot restore an obsolete page; older Control
    // versions must fail closed instead of receiving an unsafe retry.
    const rebindUrl = `${props.apiBase}/chat/sessions/${encodeURIComponent(targetSessionId)}/page-bridge/rebind-v2`
    const targetPayload = buildEafChatSessionPayload(activeBridge, activePage)
    const rebindRequest: RequestInit = {
      method: 'POST',
      signal: rebindController.signal,
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${token}`,
      },
      body: JSON.stringify({
        ...targetPayload,
        navigationRequestId,
      }),
    }
    let response: Response | undefined
    try {
      response = await fetch(rebindUrl, rebindRequest)
    } catch (error) {
      // The server may have committed before the connection failed. Replaying
      // the identical navigation is safe only because Control checks that the
      // session still belongs to this exact completed target instance.
      if (!(error instanceof TypeError) || rebindController.signal.aborted) throw error
    }
    if ((!response || [502, 503, 504].includes(response.status)) && !rebindController.signal.aborted) {
      response = await fetch(rebindUrl, rebindRequest)
    }
    if (!response) throw new DOMException('The operation was aborted.', 'AbortError')
    if (disposed) throw new DOMException('The operation was aborted.', 'AbortError')
    const payload = await response.json().catch(() => ({}))
    if (disposed) throw new DOMException('The operation was aborted.', 'AbortError')
    if (!response.ok || (payload.code && payload.code !== 200 && payload.code !== 0)) {
      const error = tokenError(
        'PAGE_NAVIGATION_REBIND_FAILED',
        payload.message || `Page navigation rebind failed: ${response.status}`,
        undefined,
        response.status,
      )
      error.phase = 'page-action'
      throw error
    }
    const bound = payload.data
    if (!bound || bound.sessionId !== targetSessionId || bound.pageKey !== targetPayload.pageKey
        || bound.pageInstanceId !== targetPayload.pageInstanceId || bound.route !== targetPayload.route) {
      const error = tokenError('PAGE_NAVIGATION_REBIND_INVALID_RESPONSE',
        'Page navigation rebind returned an invalid target', undefined, response.status)
      error.phase = 'page-action'
      throw error
    }
    // An MPA host must not adopt the supplied session id until Control has
    // verified the target token, principal and the pending navigation.
    if (resumeSessionId) updateSessionId(targetSessionId)
    props.onTokenChanged?.(token)
  } catch (error) {
    if (switched) {
      activeBridge = previousBridge
      activePage = previousPage
      currentRoute.value = buildEafChatSessionPayload(previousBridge, previousPage).route
      cachedToken = ''
      cachedTokenExpiresAt = 0
      if (!disposed) props.onTokenChanged?.('')
    }
    throw error
  } finally {
    rebindAbortController = null
    pageRebinding.value = false
  }
}

/** Embed 仅展示通用思考文案，绝不注入 Supervisor / reasoning */
function resolveEmbedStatusHint(message: ConversationMessage) {
  if (message.role !== 'assistant') return undefined
  if (message.status !== 'pending' && message.status !== 'streaming') return undefined
  const publicProgress = message.metadata?.publicProgress
  if (publicProgress && typeof publicProgress === 'object' && !Array.isArray(publicProgress)) {
    const progressMessage = (publicProgress as Record<string, unknown>).message
    if (typeof progressMessage === 'string' && progressMessage.trim()) {
      return progressMessage.trim().slice(0, 120)
    }
  }
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
  if (clearing.value) return
  void controller.submitInteraction(interactionId, action, values).catch(reportError)
}

/** 取消交互卡片：只提交 action=cancel，绝不 abort 当前流 / session cancel */
function onInteractionCancel(interactionId: string) {
  if (clearing.value) return
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

function openMemories() {
  aiToolOpen.value = false
  memoryOpen.value = true
}

function openAiTools() {
  aiToolTrigger = document.activeElement instanceof HTMLElement ? document.activeElement : null
  memoryOpen.value = false
  aiToolOpen.value = true
}

function closeAiTools() {
  aiToolOpen.value = false
  void nextTick(() => {
    if (aiToolTrigger?.isConnected) aiToolTrigger.focus({ preventScroll: true })
  })
}

function disposeHost() {
  disposed = true
  cachedToken = ''
  cachedTokenExpiresAt = 0
  tokenAbortController?.abort()
  tokenAbortController = null
  rebindAbortController?.abort()
  rebindAbortController = null
  continuationAbortController?.abort()
  continuationAbortController = null
  pageTransferAbortController?.abort()
  pageTransferAbortController = null
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
  activateShortcuts,
  busy: clearBlocked,
  sendMessage,
  clearContext,
  retryAuthentication,
  refreshToken,
  setContext,
  getSessionId,
  getExistingSessionId,
  restoreHistory,
  preparePageTransfer,
  claimPageTransfer,
  continueDocumentNavigation,
  ensureHostSession,
  rebindPage,
  openMemories,
  openAiTools,
  disposeHost,
})
</script>

<style scoped>
.eaf-chat-host {
  position: relative;
  display: flex;
  flex: 1;
  flex-direction: column;
  width: 100%;
  min-width: 0;
  max-width: 100%;
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
  flex: 0 0 auto;
  white-space: nowrap;
  border: 1px solid currentColor;
  border-radius: 8px;
  padding: 4px 10px;
  color: inherit;
  background: transparent;
  cursor: pointer;
  font: inherit;
}

.eaf-chat-tools {
  /* 放在正常文档流里（右上角一行），避免盖住第一条消息。 */
  display: flex;
  flex: 0 0 auto;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 6px;
  margin: 8px 10px 0;
}

.eaf-chat-memory-toggle {
  flex: 0 0 auto;
  padding: 3px 9px;
  border: 1px solid rgb(var(--reachai-chat-primary-rgb, 99 102 241) / 0.22);
  border-radius: 999px;
  color: var(--reachai-chat-text-muted, #52605f);
  background: var(--reachai-chat-glass-control, rgb(255 255 255 / 0.82));
  font: inherit;
  font-size: 12px;
  cursor: pointer;
}

.eaf-chat-memory-toggle:hover {
  color: var(--reachai-chat-primary, #6366f1);
}

.eaf-chat-memory-toggle:disabled { opacity: 0.55; cursor: not-allowed; }

.eaf-chat__conversation {
  flex: 1;
  align-self: stretch;
  width: 100%;
  min-width: 0;
  max-width: 100%;
  min-height: 0;
  height: 100%;
  border: none;
  border-radius: 0;
  background: transparent;
}
</style>
