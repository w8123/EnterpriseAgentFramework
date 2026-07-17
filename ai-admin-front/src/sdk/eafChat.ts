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

export type {
  EafChatPageSessionPayload,
  EafChatSessionPayload,
  EafPageDescriptor,
}

export { buildEafChatSessionPayload }

export interface EafChatOptions {
  agentId: string
  mount: string | HTMLElement
  tokenProvider: () => Promise<string> | string
  bridge?: EafPageBridge
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
}

export interface EafChatClient {
  readonly bridge: EafPageBridge
  readonly sessionId: string | null
  open(): void
  close(): void
  toggle(): void
  send(message: string): Promise<EafChatMessageResponse>
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
  setContext: (context: Record<string, unknown>) => void
  getSessionId: () => string | null
  getExistingSessionId: () => string | null
  disposeHost: () => void
}

/** theme.primaryColor 只覆盖品牌锚点，光谱由 Token 内 color-mix 派生 */
function applyPrimaryTheme(root: HTMLElement, primaryColor: string) {
  const color = String(primaryColor || '').trim()
  if (!color) return
  root.style.setProperty('--reachai-chat-primary', color)
  root.style.setProperty('--reachai-chat-spectrum-anchor', color)
  const rgb = parseCssColorToRgb(color)
  if (rgb) {
    root.style.setProperty('--reachai-chat-primary-rgb', `${rgb[0]} ${rgb[1]} ${rgb[2]}`)
  }
}

function parseCssColorToRgb(input: string): [number, number, number] | null {
  const hex = input.match(/^#([0-9a-f]{3}|[0-9a-f]{6})$/i)
  if (hex) {
    const raw = hex[1]
    if (raw.length === 3) {
      return [
        parseInt(raw[0] + raw[0], 16),
        parseInt(raw[1] + raw[1], 16),
        parseInt(raw[2] + raw[2], 16),
      ]
    }
    return [
      parseInt(raw.slice(0, 2), 16),
      parseInt(raw.slice(2, 4), 16),
      parseInt(raw.slice(4, 6), 16),
    ]
  }
  const rgb = input.match(/^rgba?\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)/i)
  if (rgb) {
    return [Number(rgb[1]), Number(rgb[2]), Number(rgb[3])]
  }
  return null
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
  let token = await options.tokenProvider()
  let context: Record<string, unknown> = { ...(options.context || {}) }
  const pendingPageActions = new Set<string>()
  const handledPageActions = new Set<string>()
  const inFlightPageActions = new Map<string, Promise<void>>()
  const emittedPageActionEventIds = new Set<string>()
  let pendingPollInFlight = false
  let pageCatalogTimer: number | undefined
  let destroyed = false
  let chatApp: App | null = null
  let hostInstance: EmbedChatHostInstance | null = null
  let sendInFlight = false

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
      publishPageActionRequested(event.data, {
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

  const root = document.createElement('div')
  root.className = 'eaf-chat'
  if (options.position && options.position !== 'inline') {
    root.classList.add(`eaf-chat--${options.position}`)
  }
  if (options.initialOpen === false) {
    root.classList.add('eaf-chat--closed')
  }
  if (options.theme?.primaryColor) {
    applyPrimaryTheme(root, options.theme.primaryColor)
  }
  root.innerHTML = `
    <div class="eaf-chat__header">
      <div class="eaf-chat__brand">${escapeText(options.theme?.brandName || 'ReachAI')}</div>
      <button class="eaf-chat__toggle" type="button" aria-expanded="${options.initialOpen === false ? 'false' : 'true'}">
        ${options.initialOpen === false ? '+' : '-'}
      </button>
    </div>
    <div class="eaf-chat__body"></div>
  `
  mount.appendChild(root)
  const toggleButton = root.querySelector<HTMLButtonElement>('.eaf-chat__toggle')!
  const bodyEl = root.querySelector<HTMLElement>('.eaf-chat__body')!

  chatApp = createApp(EmbedChatHost, {
    apiBase,
    tokenProvider: () => token,
    bridge,
    page: options.page,
    context,
    placeholder: options.locale === 'zh-CN' ? '输入消息' : 'Type a message',
    preferStream: options.stream !== false,
    onEvent: emitPublicEvent,
    onError: (error: EafChatError) => reportError(error.cause ?? new Error(error.message)),
    onUnauthorized: async () => {
      token = await options.tokenProvider()
      return token
    },
  })
  hostInstance = chatApp.mount(bodyEl) as EmbedChatHostInstance

  const pendingPoller = window.setInterval(() => {
    void pollPendingPageActionsLoop().catch(reportError)
  }, 500)
  const unregisterPageCatalogChange = bridge.onActionDefinitionsChange(() => schedulePageCatalogRegistration())

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
    token = await options.tokenProvider()
  }

  async function send(message: string): Promise<EafChatMessageResponse> {
    const text = message.trim()
    if (!text || !hostInstance || destroyed) return {}
    if (sendInFlight) throw new Error('Another message is still in flight')
    sendInFlight = true
    try {
      // 会话由 EmbedChatHost / EmbedTransport 唯一创建；完成事件由 Host onPublicEvent 转发
      const response = await hostInstance.sendMessage(text)
      // 与 UI 路径共用 processMessageCompletion；requestId/in-flight 保证精确一次
      await processMessageCompletion(response)
      return response
    } catch (error) {
      if (isUnauthorized(error)) {
        await refreshTokenForCurrentSession()
      }
      reportError(error)
      throw error
    } finally {
      sendInFlight = false
    }
  }

  function reportError(error: unknown) {
    const wrapped = { message: error instanceof Error ? error.message : String(error), cause: error }
    options.onError?.(wrapped)
  }

  async function pollPendingPageActionsLoop() {
    if (pendingPollInFlight || destroyed) return
    const existingSessionId = hostInstance?.getExistingSessionId()
    if (!existingSessionId) return
    pendingPollInFlight = true
    try {
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
    } finally {
      pendingPollInFlight = false
    }
  }

  toggleButton.addEventListener('click', () => {
    root.classList.toggle('eaf-chat--closed')
    syncOpenState()
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
    },
    close() {
      root.classList.add('eaf-chat--closed')
      syncOpenState()
    },
    toggle() {
      root.classList.toggle('eaf-chat--closed')
      syncOpenState()
    },
    send,
    registerPageCatalog,
    setContext(nextContext: Record<string, unknown>) {
      context = { ...context, ...(nextContext || {}) }
      hostInstance?.setContext(nextContext || {})
    },
    destroy() {
      destroyed = true
      unregisterPageCatalogChange()
      if (pageCatalogTimer) window.clearTimeout(pageCatalogTimer)
      window.clearInterval(pendingPoller)
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

function isUnauthorized(error: unknown): boolean {
  return Boolean(error && typeof error === 'object' && (error as { status?: number }).status === 401)
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
