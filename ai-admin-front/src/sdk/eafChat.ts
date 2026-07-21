import { createApp, type App, type ComponentPublicInstance } from 'vue'
import {
  createEafPageBridge,
  type EafPageActionDefinition,
  type EafPageBridge,
  type PageActionResult,
} from './eafPageBridge'
import EmbedChatHost from './EmbedChatHost.vue'
import {
  dispatchPageActionExactlyOnce,
  pageActionQueueFromResponse,
  pollPendingPageActions,
  processMessagePageActionQueue,
} from './embedPageActions'
import {
  buildEafChatSessionPayload,
  type EafChatPageSessionPayload,
  type EafChatSessionPayload,
  type EafPageDescriptor,
} from './embedSession'
import { createPendingPageActionPollScheduler } from './pendingPageActionPollScheduler'
import {
  EAF_CHAT_THEME_PRESETS,
  resolveEafChatThemePrimary,
  type EafChatThemePreset,
} from './themePresets'

export type {
  EafChatPageSessionPayload,
  EafChatSessionPayload,
  EafPageDescriptor,
  EafChatThemePreset,
}

export {
  buildEafChatSessionPayload,
  EAF_CHAT_THEME_PRESETS,
  resolveEafChatThemePrimary,
}

export type EafChatTokenReason = 'initial' | 'send' | 'expiring' | 'unauthorized' | 'retry' | 'page-action'

export interface EafChatTokenProviderContext {
  reason: EafChatTokenReason
  signal: AbortSignal
  attempt: number
  sessionId?: string
}

export interface EafChatTokenResult {
  token: string
  /** Unix epoch milliseconds. */
  expiresAt?: number
  /** Relative lifetime in seconds. */
  expiresIn?: number
}

export type EafChatTokenValue = string | EafChatTokenResult
export type EafChatTokenProvider = (
  context?: EafChatTokenProviderContext,
) => Promise<EafChatTokenValue> | EafChatTokenValue

export type EafChatAuthStatus = 'loading' | 'ready' | 'error'

export interface EafChatAuthState {
  phase: 'token'
  status: EafChatAuthStatus
  reason: EafChatTokenReason
  attempt: number
  message?: string
  error?: Omit<EafChatError, 'cause'>
}

export interface EafChatOptions {
  agentId: string
  mount: string | HTMLElement
  tokenProvider: EafChatTokenProvider
  /** 单次 tokenProvider 调用超时，默认 10000ms；仅影响等待，Provider 应响应 signal 主动取消请求。 */
  tokenTimeoutMs?: number
  bridge?: EafPageBridge
  /** @deprecated 浏览器不得保存 appSecret；页面目录注册应由业务后端或接入工具完成。 */
  pageRegistry?: EafPageRegistryOptions
  page?: EafPageDescriptor
  apiBase?: string
  embedPathPrefix?: string
  stream?: boolean
  theme?: EafChatTheme
  locale?: 'zh-CN' | 'en-US' | string
  position?: 'inline' | 'bottom-right' | 'bottom-left'
  initialOpen?: boolean
  context?: Record<string, unknown>
  onEvent?: (event: EafChatEvent) => void
  onError?: (error: EafChatError) => void
  onStateChange?: (state: EafChatAuthState) => void
}

export interface EafChatClient {
  readonly bridge: EafPageBridge
  readonly sessionId: string | null
  open(): void
  close(): void
  toggle(): void
  send(message: string): Promise<EafChatMessageResponse>
  /** 重新获取 Embed Token；不会自动重发上一条用户消息。 */
  retry(): Promise<void>
  registerPageCatalog(): Promise<void>
  setContext(context: Record<string, unknown>): void
  destroy(): void
}

export interface EafPageRegistryOptions {
  projectCode: string
  appKey: string
  appSecret: string
  registerOnStart?: boolean
}

export interface EafChatTheme {
  /**
   * 官方 7 色预设（与管理端 data-brand 对齐）。
   * 优先级：primaryColor 显式覆盖 > preset > 默认 tech-purple。
   */
  preset?: EafChatThemePreset
  /** 显式品牌主色；存在时覆盖 preset */
  primaryColor?: string
  brandName?: string
}

export interface EafChatEvent {
  type:
    | 'message.delta'
    | 'ui.requested'
    | 'page.action.requested'
    | 'message.completed'
    | 'interaction.submitted'
    | 'interaction.cancelled'
    | 'error'
    | string
  data: unknown
  /** 可选：envelope / Host 透传的会话身份，旧业务可忽略 */
  sessionId?: string
  turnId?: string
}

export interface EafChatError {
  message: string
  cause?: unknown
  code?: string
  phase?: 'token' | 'session' | 'message' | 'page-action' | string
  httpStatus?: number
  retryable?: boolean
}

export interface EafChatMessageResponse {
  answer?: string
  sessionId?: string
  metadata?: Record<string, unknown>
  uiRequest?: unknown
  intentType?: unknown
  toolCalls?: unknown
  /** Controller 本轮 turnId，用于跨轮事件身份 */
  turnId?: string
}

type EmbedChatHostInstance = ComponentPublicInstance & {
  sendMessage: (message: string) => Promise<EafChatMessageResponse>
  retryAuthentication: () => Promise<string>
  refreshToken: (reason?: EafChatTokenReason) => Promise<string>
  setContext: (context: Record<string, unknown>) => void
  getSessionId: () => string | null
  getExistingSessionId: () => string | null
  disposeHost: () => void
}

/** 只写入品牌锚点与 RGB；光谱 / 氛围由 conversation-tokens 内 color-mix 派生 */
function applyChatTheme(
  root: HTMLElement,
  theme?: EafChatTheme,
) {
  const resolved = resolveEafChatThemePrimary({
    preset: theme?.preset,
    primaryColor: theme?.primaryColor,
  })
  root.style.setProperty('--reachai-chat-primary', resolved.primary)
  root.style.setProperty('--reachai-chat-spectrum-anchor', resolved.primary)
  root.style.setProperty('--reachai-chat-primary-rgb', resolved.rgb)
  if (theme?.preset && theme.preset in EAF_CHAT_THEME_PRESETS) {
    root.dataset.chatPreset = theme.preset
  } else if (resolved.source === 'default') {
    root.dataset.chatPreset = 'tech-purple'
  } else {
    delete root.dataset.chatPreset
  }
}

export async function createEafChat(options: EafChatOptions): Promise<EafChatClient> {
  if (!options.agentId) throw new Error('agentId is required')
  const mount = typeof options.mount === 'string'
    ? document.querySelector<HTMLElement>(options.mount)
    : options.mount
  if (!mount) throw new Error('mount element not found')

  const bridge = options.bridge || createEafPageBridge({ route: location.pathname })
  const apiBase = resolveEafChatEmbedApiRoot(options.apiBase, options.embedPathPrefix)
  const platformBase = resolveEafChatPlatformBase(options.apiBase)
  const locale = options.locale || 'zh-CN'
  let token = ''
  let context: Record<string, unknown> = { ...(options.context || {}) }
  const pendingPageActions = new Set<string>()
  const handledPageActions = new Set<string>()
  const inFlightPageActions = new Map<string, Promise<void>>()
  const emittedPageActionEventIds = new Set<string>()
  let pageCatalogTimer: number | undefined
  let destroyed = false
  let chatApp: App | null = null
  let hostInstance: EmbedChatHostInstance | null = null
  let sendInFlight = false
  let registeredActionCount = bridge.registeredActions.length

  /**
   * 公开事件发布幂等（按 requestId）：只调用 onEvent，不执行 Bridge。
   * @returns 本次是否首次对外发布
   */
  function publishPageActionRequested(
    request: unknown,
    extras: { sessionId?: string; turnId?: string } = {},
  ): boolean {
    const data = request && typeof request === 'object'
      ? request as Record<string, unknown>
      : {}
    const requestId = String(data.requestId || '')
    if (requestId) {
      if (emittedPageActionEventIds.has(requestId)) return false
      emittedPageActionEventIds.add(requestId)
    }
    options.onEvent?.({
      type: 'page.action.requested',
      data: request,
      sessionId: extras.sessionId,
      turnId: extras.turnId,
    })
    return true
  }

  /**
   * 统一 Page Bridge 执行入口（SSE / completion queue / poll 共用）。
   * onRequested 只 publish，禁止再次 dispatch，避免递归。
   */
  function dispatchOrSchedulePageAction(
    request: unknown,
    sessionId: string | undefined,
    responseMetadata?: Record<string, unknown>,
  ) {
    if (destroyed) return
    if (!sessionId) {
      reportError(new Error('Page action requires an active embed session; no sessionId available'))
      return
    }
    void dispatchPageActionExactlyOnce({
      request,
      bridge,
      sessionId,
      apiBase,
      token,
      handledPageActions,
      inFlightPageActions,
      responseMetadata,
      onRequested: (req) => publishPageActionRequested(req, { sessionId }),
      onError: reportError,
      refreshToken: async () => {
        await refreshTokenForCurrentSession()
        return token
      },
    }).catch(reportError)
  }

  function resolvePageActionSessionId(event: EafChatEvent): string | undefined {
    if (event.sessionId) return event.sessionId
    const data = event.data && typeof event.data === 'object'
      ? event.data as Record<string, unknown>
      : {}
    if (data.sessionId != null && String(data.sessionId)) return String(data.sessionId)
    return hostInstance?.getExistingSessionId()
      || hostInstance?.getSessionId()
      || undefined
  }

  /**
   * Facade 公开事件出口。
   * 契约顺序：message.delta → ui.requested → page.action.requested → message.completed
   * Bridge/result 可晚于 completed；pending poll 属迟到补偿。
   */
  function emitPublicEvent(event: EafChatEvent) {
    if (event.type === 'page.action.requested') {
      // 独立 SSE：先 publish，再统一 dispatch（不依赖 completion metadata）
      const published = publishPageActionRequested(event.data, {
        sessionId: event.sessionId,
        turnId: event.turnId,
      })
      dispatchOrSchedulePageAction(
        event.data,
        resolvePageActionSessionId(event),
        event.data && typeof event.data === 'object'
          ? (event.data as Record<string, unknown>).metadata as Record<string, unknown> | undefined
          : undefined,
      )
      if (published) pendingPollScheduler.notifyActivity()
      return
    }
    if (event.type === 'message.completed') {
      const response = event.data as EafChatMessageResponse
      // 先同步 publish queue 中的 page.action（已由 SSE 发过的 requestId 会跳过）
      for (const request of pageActionQueueFromResponse(response)) {
        publishPageActionRequested(request, { sessionId: response.sessionId, turnId: response.turnId })
      }
      options.onEvent?.(event)
      // Bridge / result POST 可晚于 completed（与 SSE 共用 dispatch 去重）
      void processMessageCompletion(response).catch(reportError)
      pendingPollScheduler.notifyActivity()
      return
    }
    options.onEvent?.(event)
  }

  async function processMessageCompletion(response: EafChatMessageResponse | null | undefined) {
    if (!response || destroyed) return
    const sessionId = response.sessionId
      || hostInstance?.getExistingSessionId()
      || hostInstance?.getSessionId()
    const queue = pageActionQueueFromResponse(response)
    if (!sessionId) {
      if (queue.length > 0) {
        reportError(new Error('Page action requires an active embed session; no sessionId available'))
      }
      return
    }
    await processMessagePageActionQueue({
      response,
      bridge,
      sessionId,
      apiBase,
      token,
      handledPageActions,
      inFlightPageActions,
      onRequested: (request) => publishPageActionRequested(request, { sessionId }),
      onError: reportError,
      refreshToken: async () => {
        await refreshTokenForCurrentSession()
        return token
      },
    })
  }

  function reportError(error: unknown) {
    const wrapped = { message: error instanceof Error ? error.message : String(error), cause: error }
    options.onError?.(wrapped)
  }

  async function pollPendingPageActionsOnce() {
    if (destroyed) return
    const existingSessionId = hostInstance?.getExistingSessionId()
    if (!existingSessionId) return
    await pollPendingPageActions({
      apiBase,
      token,
      sessionId: existingSessionId,
      bridge,
      handledPageActions,
      pendingPageActions,
      inFlightPageActions,
      context,
      // poll 自己已 dispatch；此处只 publish，禁止再走 emitPublicEvent→dispatch 递归
      onEvent: (event) => {
        if (event.type === 'page.action.requested') {
          publishPageActionRequested(event.data, { sessionId: existingSessionId })
        }
      },
      onError: reportError,
      refreshToken: async () => {
        await refreshTokenForCurrentSession()
        return token
      },
    })
  }

  const pendingPollScheduler = createPendingPageActionPollScheduler({
    getSessionId: () => hostInstance?.getExistingSessionId() || null,
    hasRegisteredActions: () => bridge.registeredActions.length > 0,
    isDestroyed: () => destroyed,
    poll: pollPendingPageActionsOnce,
    onError: reportError,
  })

  const root = document.createElement('div')
  root.className = 'eaf-chat'
  if (options.position && options.position !== 'inline') {
    root.classList.add(`eaf-chat--${options.position}`)
  }
  if (options.initialOpen === false) {
    root.classList.add('eaf-chat--closed')
  }
  applyChatTheme(root, options.theme)
  root.innerHTML = `
    <div class="eaf-chat__header">
      <div class="eaf-chat__brand">${escapeText(options.theme?.brandName || 'ReachAI')}</div>
      <div class="eaf-chat__header-actions">
        <span class="eaf-chat__connection-status" role="status" aria-live="polite">${locale === 'en-US' ? 'Connecting' : '正在连接'}</span>
        <button class="eaf-chat__toggle" type="button" aria-expanded="${options.initialOpen === false ? 'false' : 'true'}">
          ${options.initialOpen === false ? '+' : '-'}
        </button>
      </div>
    </div>
    <div class="eaf-chat__body"></div>
  `
  mount.appendChild(root)
  const toggleButton = root.querySelector<HTMLButtonElement>('.eaf-chat__toggle')!
  const connectionStatus = root.querySelector<HTMLElement>('.eaf-chat__connection-status')!
  const bodyEl = root.querySelector<HTMLElement>('.eaf-chat__body')!

  function handleAuthState(state: EafChatAuthState) {
    root.dataset.authState = state.status
    connectionStatus.hidden = state.status === 'ready'
    connectionStatus.classList.toggle('is-error', state.status === 'error')
    connectionStatus.textContent = state.status === 'loading'
      ? (locale === 'en-US' ? 'Connecting' : '正在连接')
      : state.status === 'error'
        ? (locale === 'en-US' ? 'Connection failed' : '连接失败')
        : ''
    try {
      options.onStateChange?.(state)
    } catch (error) {
      try {
        options.onError?.({
          message: 'ReachAI state callback failed',
          cause: error,
          code: 'STATE_CALLBACK_FAILED',
          phase: 'token',
          retryable: false,
        })
      } catch {
        // Consumer callbacks must not break SDK authentication state.
      }
    }
  }

  chatApp = createApp(EmbedChatHost, {
    apiBase,
    tokenProvider: options.tokenProvider,
    tokenTimeoutMs: options.tokenTimeoutMs,
    bridge,
    page: options.page,
    context,
    locale,
    placeholder: locale === 'zh-CN' ? '输入消息' : 'Type a message',
    preferStream: options.stream !== false,
    onEvent: emitPublicEvent,
    onError: (error: EafChatError) => options.onError?.(error),
    onAuthStateChange: handleAuthState,
    onTokenChanged: (nextToken: string) => {
      token = nextToken
    },
    onSessionChanged: () => {
      pendingPollScheduler.notifyActivity()
    },
  })
  hostInstance = chatApp.mount(bodyEl) as EmbedChatHostInstance

  const unregisterPageCatalogChange = bridge.onActionDefinitionsChange(() => {
    schedulePageCatalogRegistration()
    const nextCount = bridge.registeredActions.length
    pendingPollScheduler.notifyActionsChanged(registeredActionCount, nextCount)
    registeredActionCount = nextCount
  })

  schedulePageCatalogRegistration(0)

  function reportPageCatalogError(error: unknown) {
    const wrapped = { message: error instanceof Error ? error.message : String(error), cause: error }
    options.onError?.(wrapped)
  }

  function schedulePageCatalogRegistration(delay = 250) {
    if (!options.pageRegistry || options.pageRegistry.registerOnStart === false || !options.page) return
    if (pageCatalogTimer) window.clearTimeout(pageCatalogTimer)
    pageCatalogTimer = window.setTimeout(() => {
      pageCatalogTimer = undefined
      void registerPageCatalog().catch(reportPageCatalogError)
    }, delay)
  }

  async function registerPageCatalog() {
    if (destroyed) return
    await registerPageCatalogIfConfigured(platformBase, options, bridge)
  }

  function syncOpenState() {
    const open = !root.classList.contains('eaf-chat--closed')
    toggleButton.textContent = open ? '-' : '+'
    toggleButton.setAttribute('aria-expanded', open ? 'true' : 'false')
  }

  async function refreshTokenForCurrentSession() {
    if (!hostInstance) throw new Error('Embed chat host is not ready')
    token = await hostInstance.refreshToken('page-action')
  }

  async function send(message: string): Promise<EafChatMessageResponse> {
    const text = message.trim()
    if (!text || !hostInstance || destroyed) return {}
    if (sendInFlight) throw new Error('Another message is still in flight')
    sendInFlight = true
    pendingPollScheduler.notifyActivity()
    try {
      // 会话由 EmbedChatHost / EmbedTransport 唯一创建；完成事件由 Host onPublicEvent 转发
      const response = await hostInstance.sendMessage(text)
      // 与 UI 路径共用 processMessageCompletion；requestId/in-flight 保证精确一次
      await processMessageCompletion(response)
      pendingPollScheduler.notifyActivity()
      return response
    } catch (error) {
      reportError(error)
      throw error
    } finally {
      sendInFlight = false
    }
  }

  toggleButton.addEventListener('click', () => {
    const wasClosed = root.classList.contains('eaf-chat--closed')
    root.classList.toggle('eaf-chat--closed')
    syncOpenState()
    if (wasClosed && !root.classList.contains('eaf-chat--closed')) {
      pendingPollScheduler.notifyActivity()
    }
  })
  syncOpenState()

  const client = {
    bridge,
    get sessionId() {
      return hostInstance?.getSessionId() || null
    },
    open() {
      root.classList.remove('eaf-chat--closed')
      syncOpenState()
      pendingPollScheduler.notifyActivity()
    },
    close() {
      root.classList.add('eaf-chat--closed')
      syncOpenState()
    },
    toggle() {
      const wasClosed = root.classList.contains('eaf-chat--closed')
      root.classList.toggle('eaf-chat--closed')
      syncOpenState()
      if (wasClosed && !root.classList.contains('eaf-chat--closed')) {
        pendingPollScheduler.notifyActivity()
      }
    },
    send,
    async retry() {
      if (!hostInstance || destroyed) return
      token = await hostInstance.retryAuthentication()
    },
    registerPageCatalog,
    setContext(nextContext: Record<string, unknown>) {
      context = { ...context, ...(nextContext || {}) }
      hostInstance?.setContext(nextContext || {})
    },
    destroy() {
      destroyed = true
      pendingPollScheduler.destroy()
      unregisterPageCatalogChange()
      if (pageCatalogTimer) window.clearTimeout(pageCatalogTimer)
      hostInstance?.disposeHost()
      chatApp?.unmount()
      chatApp = null
      hostInstance = null
      root.remove()
    },
  }

  // Vitest 专用：验证无 sessionId 时的安全失败路径（不进入正式公开 API 文档）
  if (typeof process !== 'undefined' && process.env?.VITEST) {
    Object.assign(client, {
      __emitPublicEventForTests: emitPublicEvent,
    })
  }

  return client
}

async function registerPageCatalogIfConfigured(apiBase: string, options: EafChatOptions, bridge: EafPageBridge) {
  const registry = options.pageRegistry
  const page = options.page
  if (!registry || registry.registerOnStart === false || !page) return
  if (!registry.projectCode || !registry.appKey || !registry.appSecret) return
  await postJsonWithSignature(
    `${apiBase}/api/registry/projects/${encodeURIComponent(registry.projectCode)}/pages/register`,
    {
      pageKey: page.pageKey,
      name: page.name || page.pageKey,
      routePattern: page.routePattern || bridge.route || location.pathname,
      origin: page.origin || location.origin,
      pageInstanceId: bridge.pageInstanceId,
      replaceActions: true,
      actions: bridge.actionDefinitions.map(toCatalogAction),
      metadata: page.metadata || {},
    },
    registry,
  )
}

function toCatalogAction(action: EafPageActionDefinition) {
  return {
    actionKey: action.actionKey,
    title: action.title || action.actionKey,
    description: action.description || '',
    confirmRequired: action.confirmRequired === true,
    inputSchema: action.inputSchema || {},
    outputSchema: action.outputSchema || {},
    sampleArgs: action.sampleArgs || {},
    allowedAgentIds: action.allowedAgentIds || [],
    metadata: action.metadata || {},
  }
}

async function postJsonWithSignature<T>(url: string, body: unknown, registry: EafPageRegistryOptions): Promise<T> {
  const timestamp = String(Date.now())
  const nonce = createNonce()
  const signature = await hmacSha256Hex(registry.appSecret, `${registry.projectCode}\n${timestamp}\n${nonce}`)
  const response = await fetch(url, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'X-ReachAI-App-Key': registry.appKey,
      'X-ReachAI-Timestamp': timestamp,
      'X-ReachAI-Nonce': nonce,
      'X-ReachAI-Signature': signature,
    },
    body: JSON.stringify(body),
  })
  const payload = await response.json().catch(() => ({}))
  if (!response.ok || (payload.code && payload.code !== 200 && payload.code !== 0)) {
    throw requestError(payload.message || `Page catalog registration failed: ${response.status}`, response.status)
  }
  return (payload.data ?? payload) as T
}

async function hmacSha256Hex(secret: string, message: string): Promise<string> {
  if (!crypto?.subtle) {
    throw new Error('Web Crypto API is required for ReachAI page catalog registration')
  }
  const encoder = new TextEncoder()
  const key = await crypto.subtle.importKey(
    'raw',
    encoder.encode(secret),
    { name: 'HMAC', hash: 'SHA-256' },
    false,
    ['sign'],
  )
  const digest = await crypto.subtle.sign('HMAC', key, encoder.encode(message))
  return Array.from(new Uint8Array(digest))
    .map((item) => item.toString(16).padStart(2, '0'))
    .join('')
}

function createNonce(): string {
  if (typeof crypto !== 'undefined' && 'randomUUID' in crypto) {
    return crypto.randomUUID()
  }
  return `${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}`
}

function requestError(message: string, status: number): Error & { status?: number } {
  const error = new Error(message) as Error & { status?: number }
  error.status = status
  return error
}

function escapeText(value: string) {
  return value.replace(/[<>&"']/g, (ch) => ({ '<': '&lt;', '>': '&gt;', '&': '&amp;', '"': '&quot;', "'": '&#39;' }[ch] || ch))
}

export function resolveEafChatEmbedApiRoot(apiBase?: string, embedPathPrefix?: string): string {
  const base = trimTrailingSlash((apiBase || '').trim())
  if (embedPathPrefix !== undefined) {
    const prefix = normalizePathPrefix(embedPathPrefix || '/api/embed')
    return joinBaseAndPrefix(stripPathSuffix(base, prefix), prefix)
  }
  if (isKnownEmbedApiRoot(base)) {
    return base
  }
  return joinBaseAndPrefix(base, '/api/embed')
}

export function resolveEafChatPlatformBase(apiBase?: string): string {
  const base = trimTrailingSlash((apiBase || '').trim())
  if (!base) return ''
  if (isKnownEmbedApiRoot(base)) {
    return base.replace(/\/api\/(?:reachai\/)?embed$/i, '')
  }
  return base
}

function joinBaseAndPrefix(base: string, prefix: string): string {
  const normalizedBase = trimTrailingSlash(base)
  const normalizedPrefix = normalizePathPrefix(prefix)
  if (!normalizedBase) return normalizedPrefix || ''
  if (!normalizedPrefix) return normalizedBase
  return `${normalizedBase}${normalizedPrefix}`
}

function stripPathSuffix(value: string, suffix: string): string {
  const normalizedSuffix = normalizePathPrefix(suffix)
  if (!value || !normalizedSuffix) return value
  return value.toLowerCase().endsWith(normalizedSuffix.toLowerCase())
    ? trimTrailingSlash(value.slice(0, -normalizedSuffix.length))
    : value
}

function normalizePathPrefix(value: string): string {
  const trimmed = trimTrailingSlash((value || '').trim())
  if (!trimmed) return ''
  return trimmed.startsWith('/') ? trimmed : `/${trimmed}`
}

function isKnownEmbedApiRoot(value: string): boolean {
  return /\/api\/(?:reachai\/)?embed$/i.test(value)
}

function trimTrailingSlash(value: string) {
  return value.endsWith('/') ? value.slice(0, -1) : value
}
