import {
  createEafPageBridge,
  type EafPageActionDefinition,
  type EafPageBridge,
  type PageActionResult,
} from './eafPageBridge'

const SDK_VERSION = '1.0.0'

export interface EafChatOptions {
  agentId: string
  mount: string | HTMLElement
  tokenProvider: () => Promise<string> | string
  bridge?: EafPageBridge
  pageRegistry?: EafPageRegistryOptions
  page?: EafPageDescriptor
  apiBase?: string
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

export interface EafPageDescriptor {
  pageKey: string
  name?: string
  routePattern?: string
  origin?: string
  metadata?: Record<string, unknown>
}

export interface EafChatTheme {
  primaryColor?: string
  brandName?: string
}

export interface EafChatEvent {
  type: string
  data: unknown
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
}

interface EmbedStreamEvent {
  type: string
  data: unknown
}

interface EmbedSessionResponse {
  sessionId: string
  agentId: string
  principal?: Record<string, string>
}

export interface EafChatSessionPayload {
  pageKey?: string
  pageInstanceId: string
  route: string
  bridgeActions: string[]
  sdkVersion: string
}

export type EafChatPageSessionPayload = EafChatSessionPayload & {
  pageKey: string
}

interface PageActionDispatchRequest {
  type: 'page.action.requested'
  protocolVersion?: string
  requestId: string
  actionKey: string
  title?: string
  args?: Record<string, unknown>
  target?: Record<string, unknown>
  confirm?: boolean
}

export async function createEafChat(options: EafChatOptions): Promise<EafChatClient> {
  if (!options.agentId) throw new Error('agentId is required')
  const mount = typeof options.mount === 'string'
    ? document.querySelector<HTMLElement>(options.mount)
    : options.mount
  if (!mount) throw new Error('mount element not found')

  const bridge = options.bridge || createEafPageBridge({ route: location.pathname })
  const apiBase = trimTrailingSlash(options.apiBase || '')
  let token = await options.tokenProvider()
  let session = await createSession(apiBase, token, bridge, options.page)
  let context: Record<string, unknown> = { ...(options.context || {}) }
  const pendingPageActions = new Set<string>()
  let pendingPollInFlight = false
  let pageCatalogTimer: number | undefined
  let destroyed = false

  const root = document.createElement('div')
  root.className = 'eaf-chat'
  if (options.position && options.position !== 'inline') {
    root.classList.add(`eaf-chat--${options.position}`)
  }
  if (options.initialOpen === false) {
    root.classList.add('eaf-chat--closed')
  }
  if (options.theme?.primaryColor) {
    root.style.setProperty('--reachai-chat-primary', options.theme.primaryColor)
  }
  root.innerHTML = `
    <div class="eaf-chat__header">
      <div class="eaf-chat__brand">${escapeText(options.theme?.brandName || 'ReachAI')}</div>
      <button class="eaf-chat__toggle" type="button" aria-expanded="${options.initialOpen === false ? 'false' : 'true'}">
        ${options.initialOpen === false ? '+' : '-'}
      </button>
    </div>
    <div class="eaf-chat__messages"></div>
    <form class="eaf-chat__input">
      <input name="message" autocomplete="off" />
      <button type="submit">Send</button>
    </form>
  `
  mount.appendChild(root)
  const toggleButton = root.querySelector<HTMLButtonElement>('.eaf-chat__toggle')!
  const messagesEl = root.querySelector<HTMLElement>('.eaf-chat__messages')!
  const inputForm = root.querySelector<HTMLFormElement>('.eaf-chat__input')!
  const input = inputForm.elements.namedItem('message') as HTMLInputElement
  const pendingPoller = window.setInterval(() => {
    void pollPendingPageActions().catch(reportError)
  }, 5000)
  const unregisterPageCatalogChange = bridge.onActionDefinitionsChange(() => schedulePageCatalogRegistration())

  schedulePageCatalogRegistration(0)

  function reportPageCatalogError(error: unknown) {
    const wrapped = { message: error instanceof Error ? error.message : String(error), cause: error }
    options.onError?.(wrapped)
    appendSystemMessage(messagesEl, wrapped.message)
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
    await registerPageCatalogIfConfigured(apiBase, options, bridge)
  }

  function syncOpenState() {
    const open = !root.classList.contains('eaf-chat--closed')
    toggleButton.textContent = open ? '-' : '+'
    toggleButton.setAttribute('aria-expanded', open ? 'true' : 'false')
  }

  async function ensureSession() {
    if (session?.sessionId) return session
    token = await options.tokenProvider()
    session = await createSession(apiBase, token, bridge, options.page)
    return session
  }

  async function refreshSession() {
    token = await options.tokenProvider()
    try {
      session = await createSession(apiBase, token, bridge, options.page)
    } catch (error) {
      reportError(error)
      throw error
    }
    appendSystemMessage(messagesEl, options.locale === 'zh-CN' ? '会话已刷新。' : 'Session refreshed.')
    return session
  }

  async function refreshTokenForCurrentSession() {
    token = await options.tokenProvider()
    appendSystemMessage(messagesEl, 'Token refreshed, continuing the current session.')
  }

  async function send(message: string): Promise<EafChatMessageResponse> {
    const text = message.trim()
    if (!text) return {}
    appendMessage(messagesEl, 'user', text)
    const active = await ensureSession()
    if (options.stream !== false) {
      try {
        return await postSseJson(
          `${apiBase}/api/embed/chat/sessions/${encodeURIComponent(active.sessionId)}/messages/stream`,
          { message: text, context },
          token,
          messagesEl,
          bridge,
          active.sessionId,
          apiBase,
          () => token,
          options.onEvent,
        )
      } catch (error) {
        if (isUnauthorized(error)) {
          await refreshTokenForCurrentSession()
          try {
            return await postSseJson(
              `${apiBase}/api/embed/chat/sessions/${encodeURIComponent(active.sessionId)}/messages/stream`,
              { message: text, context },
              token,
              messagesEl,
              bridge,
              active.sessionId,
              apiBase,
              () => token,
              options.onEvent,
            )
          } catch (retryError) {
            if (!isUnauthorized(retryError)) throw retryError
            const refreshed = await refreshSession()
            return postSseJson(
              `${apiBase}/api/embed/chat/sessions/${encodeURIComponent(refreshed.sessionId)}/messages/stream`,
              { message: text, context },
              token,
              messagesEl,
              bridge,
              refreshed.sessionId,
              apiBase,
              () => token,
              options.onEvent,
            )
          }
        }
        console.warn('[ReachAI Chat] SSE stream failed, falling back to JSON message endpoint', error)
      }
    }
    try {
      return await sendJsonMessage(active.sessionId, text)
    } catch (error) {
      if (isUnauthorized(error)) {
        await refreshTokenForCurrentSession()
        try {
          return await sendJsonMessage(active.sessionId, text)
        } catch (retryError) {
          if (!isUnauthorized(retryError)) throw retryError
          const refreshed = await refreshSession()
          return sendJsonMessage(refreshed.sessionId, text)
        }
      }
      reportError(error)
      throw error
    }
  }

  async function sendJsonMessage(sessionId: string, text: string): Promise<EafChatMessageResponse> {
    const response = await postJson<EafChatMessageResponse>(
      `${apiBase}/api/embed/chat/sessions/${encodeURIComponent(sessionId)}/messages`,
      { message: text, context },
      token,
    )
    appendMessage(messagesEl, 'assistant', response.answer || '')
    await handleUiRequest(response.uiRequest, bridge, messagesEl, sessionId, apiBase, token, options.onEvent)
    return response
  }

  function reportError(error: unknown) {
    const wrapped = { message: error instanceof Error ? error.message : String(error), cause: error }
    options.onError?.(wrapped)
    appendSystemMessage(messagesEl, wrapped.message)
  }

  async function pollPendingPageActions() {
    if (pendingPollInFlight || destroyed) return
    pendingPollInFlight = true
    try {
      const active = await ensureSession()
      const pendingUrl = `${apiBase}/api/embed/chat/sessions/${encodeURIComponent(active.sessionId)}/page-actions/pending?limit=10`
      let requests: PageActionDispatchRequest[]
      try {
        requests = await getJson<PageActionDispatchRequest[]>(pendingUrl, token)
      } catch (error) {
        if (!isUnauthorized(error)) throw error
        await refreshTokenForCurrentSession()
        requests = await getJson<PageActionDispatchRequest[]>(pendingUrl, token)
      }
      for (const request of requests) {
        if (!request?.requestId || pendingPageActions.has(request.requestId)) continue
        pendingPageActions.add(request.requestId)
        try {
          options.onEvent?.({ type: 'page.action.requested', data: request })
          const result = await bridge.handleEvent(request)
          if (result) {
            appendActionResult(messagesEl, result)
            try {
              await postPageActionResult(apiBase, active.sessionId, token, result)
            } catch (error) {
              if (!isUnauthorized(error)) throw error
              await refreshTokenForCurrentSession()
              await postPageActionResult(apiBase, active.sessionId, token, result)
            }
          }
        } finally {
          pendingPageActions.delete(request.requestId)
        }
      }
    } finally {
      pendingPollInFlight = false
    }
  }

  inputForm.addEventListener('submit', (event) => {
    event.preventDefault()
    const text = input.value
    input.value = ''
    void send(text)
  })
  toggleButton.addEventListener('click', () => {
    root.classList.toggle('eaf-chat--closed')
    syncOpenState()
  })
  syncOpenState()

  return {
    bridge,
    get sessionId() {
      return session?.sessionId || null
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
    setContext(nextContext) {
      context = { ...context, ...(nextContext || {}) }
    },
    destroy() {
      destroyed = true
      unregisterPageCatalogChange()
      if (pageCatalogTimer) window.clearTimeout(pageCatalogTimer)
      window.clearInterval(pendingPoller)
      root.remove()
    },
  }
}

export function buildEafChatSessionPayload(bridge: EafPageBridge, page: EafPageDescriptor): EafChatPageSessionPayload
export function buildEafChatSessionPayload(bridge: EafPageBridge, page?: undefined): EafChatSessionPayload
export function buildEafChatSessionPayload(bridge: EafPageBridge, page?: EafPageDescriptor): EafChatSessionPayload
export function buildEafChatSessionPayload(bridge: EafPageBridge, page?: EafPageDescriptor): EafChatSessionPayload {
  return {
    pageKey: page?.pageKey,
    pageInstanceId: bridge.pageInstanceId,
    route: bridge.route || location.pathname,
    bridgeActions: bridge.registeredActions,
    sdkVersion: SDK_VERSION,
  }
}

async function createSession(apiBase: string, token: string, bridge: EafPageBridge, page?: EafPageDescriptor): Promise<EmbedSessionResponse> {
  return postJson<EmbedSessionResponse>(
    `${apiBase}/api/embed/chat/sessions`,
    buildEafChatSessionPayload(bridge, page),
    token,
  )
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

async function postJson<T>(url: string, body: unknown, token: string): Promise<T> {
  const response = await fetch(url, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${token}`,
    },
    body: JSON.stringify(body),
  })
  const payload = await response.json().catch(() => ({}))
  if (!response.ok || (payload.code && payload.code !== 200 && payload.code !== 0)) {
    throw requestError(payload.message || `Request failed: ${response.status}`, response.status)
  }
  return (payload.data ?? payload) as T
}

async function getJson<T>(url: string, token: string): Promise<T> {
  const response = await fetch(url, {
    method: 'GET',
    headers: {
      Authorization: `Bearer ${token}`,
    },
  })
  const payload = await response.json().catch(() => ({}))
  if (!response.ok || (payload.code && payload.code !== 200 && payload.code !== 0)) {
    throw requestError(payload.message || `Request failed: ${response.status}`, response.status)
  }
  return (payload.data ?? payload) as T
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

async function postSseJson(
  url: string,
  body: unknown,
  token: string,
  messagesEl: HTMLElement,
  bridge: EafPageBridge,
  sessionId: string,
  apiBase: string,
  tokenProvider: () => string,
  onEvent?: (event: EafChatEvent) => void,
): Promise<EafChatMessageResponse> {
  const response = await fetch(url, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'text/event-stream',
      Authorization: `Bearer ${token}`,
    },
    body: JSON.stringify(body),
  })
  if (!response.ok || !response.body) {
    throw requestError(`Stream request failed: ${response.status}`, response.status)
  }
  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  let answer = ''
  let assistantEl: HTMLElement | null = null
  const state: { completed?: EafChatMessageResponse } = {}

  async function handle(event: EmbedStreamEvent) {
    if (event.type === 'message.delta') {
      const text = textFromDelta(event.data)
      if (!text) return
      answer += text
      if (!assistantEl) assistantEl = appendMessage(messagesEl, 'assistant', '')
      assistantEl.textContent = answer
      return
    }
    if (event.type === 'page.action.requested') {
      onEvent?.(event)
      const result = await bridge.handleEvent(event.data)
      if (result) {
        appendActionResult(messagesEl, result)
        await postPageActionResult(apiBase, sessionId, tokenProvider(), result)
      }
      return
    }
    if (event.type === 'ui.requested') {
      onEvent?.(event)
      await handleUiRequest(event.data, bridge, messagesEl, sessionId, apiBase, tokenProvider(), onEvent)
      return
    }
    if (event.type === 'message.completed') {
      state.completed = (event.data && typeof event.data === 'object' ? event.data : {}) as EafChatMessageResponse
      if (!answer && state.completed.answer) {
        answer = state.completed.answer
        if (!assistantEl) assistantEl = appendMessage(messagesEl, 'assistant', '')
        assistantEl.textContent = answer
      }
      return
    }
    if (event.type === 'error') {
      const message = event.data && typeof event.data === 'object'
        ? String((event.data as Record<string, unknown>).message || 'Stream failed')
        : 'Stream failed'
      throw new Error(message)
    }
  }

  while (true) {
    const { done, value } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })
    const frames = splitSseFrames(buffer)
    buffer = frames.remainder
    for (const frame of frames.complete) {
      const event = parseSseFrame(frame)
      if (event) await handle(event)
    }
  }
  buffer += decoder.decode()
  if (buffer.trim()) {
    const event = parseSseFrame(buffer)
    if (event) await handle(event)
  }
  return state.completed ? { ...state.completed, answer: state.completed.answer || answer } : { answer }
}

async function handleUiRequest(
  uiRequest: unknown,
  bridge: EafPageBridge,
  messagesEl: HTMLElement,
  sessionId: string,
  apiBase: string,
  token: string,
  onEvent?: (event: EafChatEvent) => void,
) {
  const request = uiRequest as Record<string, unknown> | undefined
  if (request) {
    renderStructuredUi(messagesEl, request)
  }
  const pageAction = request?.extension && typeof request.extension === 'object'
    ? (request.extension as Record<string, unknown>).pageActionRequest
    : undefined
  if (pageAction) onEvent?.({ type: 'page.action.requested', data: pageAction })
  const result = await bridge.handleEvent(pageAction)
  if (result) {
    appendActionResult(messagesEl, result)
    await postPageActionResult(apiBase, sessionId, token, result)
  }
}

function splitSseFrames(buffer: string): { complete: string[]; remainder: string } {
  const normalized = buffer.replace(/\r\n/g, '\n')
  const parts = normalized.split('\n\n')
  return {
    complete: parts.slice(0, -1),
    remainder: parts[parts.length - 1] || '',
  }
}

function parseSseFrame(frame: string): EmbedStreamEvent | null {
  let type = 'message'
  const data: string[] = []
  for (const line of frame.split('\n')) {
    if (line.startsWith('event:')) {
      type = line.slice('event:'.length).trim()
    } else if (line.startsWith('data:')) {
      data.push(line.slice('data:'.length).trimStart())
    }
  }
  const raw = data.join('\n')
  if (!raw) return null
  try {
    return { type, data: JSON.parse(raw) }
  } catch {
    return { type, data: raw }
  }
}

function textFromDelta(data: unknown): string {
  if (typeof data === 'string') return data
  if (data && typeof data === 'object') {
    return String((data as Record<string, unknown>).text || '')
  }
  return ''
}

function appendMessage(target: HTMLElement, role: string, text: string): HTMLElement {
  const item = document.createElement('div')
  item.className = `eaf-chat__message eaf-chat__message--${role}`
  item.textContent = text
  target.appendChild(item)
  return item
}

function appendActionResult(target: HTMLElement, result: PageActionResult) {
  const item = document.createElement('div')
  item.className = 'eaf-chat__action-result'
  item.textContent = `${result.actionKey}: ${result.status}`
  target.appendChild(item)
}

function appendSystemMessage(target: HTMLElement, text: string) {
  if (!text) return
  const item = document.createElement('div')
  item.className = 'eaf-chat__message eaf-chat__message--system'
  item.textContent = text
  target.appendChild(item)
}

function renderStructuredUi(target: HTMLElement, request: Record<string, unknown>) {
  const component = String(request.component || '').toLowerCase()
  if (!component || component === 'page_action') return
  const item = document.createElement('div')
  item.className = `eaf-chat__ui eaf-chat__ui--${component}`
  const title = document.createElement('div')
  title.className = 'eaf-chat__ui-title'
  title.textContent = String(request.title || component)
  item.appendChild(title)
  const data = request.data ?? request.summary
  if (component === 'table' && Array.isArray(data)) {
    item.appendChild(renderTable(data, request.schema))
  } else if (component === 'detail' || component === 'card') {
    item.appendChild(renderKeyValue(data))
  } else if (component === 'report') {
    item.appendChild(renderReport(data))
  } else if (component === 'custom') {
    const rendererKey = request.extension && typeof request.extension === 'object'
      ? (request.extension as Record<string, unknown>).rendererKey
      : undefined
    item.appendChild(textBlock(`Custom renderer: ${String(rendererKey || 'unregistered')}`))
    item.appendChild(renderKeyValue(data))
  } else {
    item.appendChild(renderKeyValue(data))
  }
  target.appendChild(item)
}

function renderTable(data: unknown[], schema: unknown): HTMLElement {
  const table = document.createElement('table')
  const schemaRecord = schema && typeof schema === 'object' ? schema as Record<string, unknown> : {}
  const columns = Array.isArray(schemaRecord.columns) ? schemaRecord.columns as Record<string, unknown>[] : []
  const keys = columns.length > 0
    ? columns.map((column) => String(column.key || '')).filter(Boolean)
    : Object.keys((data[0] && typeof data[0] === 'object' ? data[0] : {}) as Record<string, unknown>)
  const head = document.createElement('thead')
  const headRow = document.createElement('tr')
  keys.forEach((key) => {
    const th = document.createElement('th')
    const column = columns.find((item) => item.key === key)
    th.textContent = String(column?.title || key)
    headRow.appendChild(th)
  })
  head.appendChild(headRow)
  table.appendChild(head)
  const body = document.createElement('tbody')
  data.forEach((row) => {
    const tr = document.createElement('tr')
    const record = row && typeof row === 'object' ? row as Record<string, unknown> : {}
    keys.forEach((key) => {
      const td = document.createElement('td')
      td.textContent = valueText(record[key])
      tr.appendChild(td)
    })
    body.appendChild(tr)
  })
  table.appendChild(body)
  return table
}

function renderReport(data: unknown): HTMLElement {
  const container = document.createElement('div')
  const record = data && typeof data === 'object' ? data as Record<string, unknown> : {}
  if (record.summary) container.appendChild(textBlock(valueText(record.summary)))
  const sections = Array.isArray(record.sections) ? record.sections : []
  sections.forEach((section) => {
    const sectionRecord = section && typeof section === 'object' ? section as Record<string, unknown> : {}
    const title = document.createElement('strong')
    title.textContent = valueText(sectionRecord.title)
    container.appendChild(title)
    container.appendChild(textBlock(valueText(sectionRecord.content)))
  })
  if (!container.childElementCount) container.appendChild(textBlock(valueText(data)))
  return container
}

function renderKeyValue(data: unknown): HTMLElement {
  const container = document.createElement('dl')
  const record = data && typeof data === 'object' && !Array.isArray(data) ? data as Record<string, unknown> : { value: data }
  Object.entries(record).forEach(([key, value]) => {
    const dt = document.createElement('dt')
    dt.textContent = key
    const dd = document.createElement('dd')
    dd.textContent = valueText(value)
    container.appendChild(dt)
    container.appendChild(dd)
  })
  return container
}

function textBlock(text: string): HTMLElement {
  const div = document.createElement('div')
  div.textContent = text
  return div
}

async function postPageActionResult(apiBase: string, sessionId: string, token: string, result: PageActionResult) {
  await postJson(
    `${apiBase}/api/embed/chat/sessions/${encodeURIComponent(sessionId)}/page-actions/${encodeURIComponent(result.requestId)}/result`,
    result,
    token,
  )
}

function valueText(value: unknown): string {
  if (value == null) return ''
  if (typeof value === 'string') return value
  if (typeof value === 'number' || typeof value === 'boolean') return String(value)
  try {
    return JSON.stringify(value)
  } catch {
    return String(value)
  }
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

function trimTrailingSlash(value: string) {
  return value.endsWith('/') ? value.slice(0, -1) : value
}
