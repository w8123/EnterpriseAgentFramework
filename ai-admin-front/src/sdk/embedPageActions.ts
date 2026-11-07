import type {
  EafPageBridge,
  PageActionBeforeExecute,
  PageActionResult,
} from './eafPageBridge'
import type { EafChatEvent, EafChatMessageResponse } from './eafChat'

export interface PageActionDispatchRequest {
  type: 'page.action.requested'
  protocolVersion?: string
  requestId: string
  actionKey: string
  title?: string
  args?: Record<string, unknown>
  target?: Record<string, unknown>
  confirm?: boolean
  metadata?: Record<string, unknown>
}

/** The server has acknowledged this exact page-action result. Contains no action arguments or tokens. */
export interface PageActionResultDelivered {
  requestId: string
  actionKey: string
  status: PageActionResult['status']
  userConfirmed: boolean
}

interface ReachAiWindowPageBridge {
  execute?: (pageKey: string, actionKey: string, args?: Record<string, unknown>, options?: Record<string, unknown>) => Promise<unknown> | unknown
  list?: (pageKey?: string) => unknown[]
}

export function pageActionQueueFromResponse(response: EafChatMessageResponse): PageActionDispatchRequest[] {
  const raw = response.metadata?.pageActionQueue
  if (!Array.isArray(raw)) return []
  const pageKey = pageKeyFromMetadata(response.metadata)
  return raw
    .map((item) => normalizePageActionRequest(item, pageKey))
    .filter((item): item is PageActionDispatchRequest => !!item)
}

export function normalizePageActionRequest(value: unknown, fallbackPageKey?: string): PageActionDispatchRequest | null {
  if (!value || typeof value !== 'object') return null
  const record = value as Record<string, unknown>
  if (record.type !== 'page.action.requested') return null
  if (typeof record.requestId !== 'string' || typeof record.actionKey !== 'string') return null
  const metadata = record.metadata && typeof record.metadata === 'object'
    ? { ...record.metadata as Record<string, unknown> }
    : {}
  const pageKey = typeof record.pageKey === 'string' ? record.pageKey : fallbackPageKey
  if (pageKey && !metadata.pageKey) metadata.pageKey = pageKey
  return {
    type: 'page.action.requested',
    protocolVersion: typeof record.protocolVersion === 'string' ? record.protocolVersion : undefined,
    requestId: record.requestId,
    actionKey: record.actionKey,
    title: typeof record.title === 'string' ? record.title : undefined,
    args: record.args && typeof record.args === 'object' ? record.args as Record<string, unknown> : undefined,
    target: record.target && typeof record.target === 'object' ? record.target as Record<string, unknown> : undefined,
    confirm: record.confirm === true,
    metadata,
  }
}

export async function executePageActionRequest(
  request: unknown,
  bridge: EafPageBridge,
  handledPageActions: Set<string>,
  responseMetadata?: Record<string, unknown>,
): Promise<PageActionResult | null> {
  const normalized = normalizePageActionRequest(request, pageKeyFromMetadata(responseMetadata))
  if (!normalized?.requestId) return null
  if (handledPageActions.has(normalized.requestId)) return null
  return runPageActionBridge(normalized, bridge, responseMetadata, handledPageActions)
}

/**
 * completion 与 pending poll 共用：按 requestId 保证 onEvent / Bridge / result POST 精确一次。
 * 支持 in-flight Promise 去重，避免并发双执行。
 */
export async function dispatchPageActionExactlyOnce(options: {
  request: unknown
  bridge: EafPageBridge
  sessionId: string
  apiBase: string
  token: string
  handledPageActions: Set<string>
  inFlightPageActions: Map<string, Promise<void>>
  pendingPageActionResults?: Map<string, PageActionResult>
  responseMetadata?: Record<string, unknown>
  onRequested?: (request: PageActionDispatchRequest) => void
  onClaimed?: (request: PageActionDispatchRequest, traceId: string) => void
  onDelivered?: (result: PageActionResultDelivered) => void
  onError?: (error: unknown) => void
  refreshToken?: () => Promise<string>
  fetchImpl?: typeof fetch
  isActive?: () => boolean
  isCurrentPage?: () => boolean
  signal?: AbortSignal
}): Promise<void> {
  if (options.isActive?.() === false) return
  const normalized = normalizePageActionRequest(options.request, pageKeyFromMetadata(options.responseMetadata))
  if (!normalized?.requestId) return
  const requestId = normalized.requestId

  if (
    options.handledPageActions.has(requestId)
    && !options.pendingPageActionResults?.has(requestId)
  ) return
  const existing = options.inFlightPageActions.get(requestId)
  if (existing) {
    await existing
    return
  }

  const work = (async () => {
    const pendingResult =
      options.pendingPageActionResults?.get(requestId)
    if (options.handledPageActions.has(requestId)) {
      if (!pendingResult) return
      const acknowledged = await deliverPageActionResult(options, pendingResult)
      options.pendingPageActionResults?.delete(requestId)
      notifyDelivered(options, pendingResult, acknowledged)
      return
    }
    // 先认领，防止并发第二进入 Bridge
    options.handledPageActions.add(requestId)
    options.onRequested?.(normalized)
    let claimAttempted = false
    let claimed = false
    try {
      let executionRejected = false
      const beforeExecute: PageActionBeforeExecute = async () => {
        if (options.isActive?.() === false) { executionRejected = true; return false }
        if (claimAttempted) return claimed
        claimAttempted = true
        const claim = await claimPageActionWithRefresh(options, normalized)
        claimed = claim.claimed
        if (claimed && claim.traceId && normalized.metadata?.traceId === claim.traceId) {
          options.onClaimed?.(normalized, claim.traceId)
        }
        executionRejected = !claimed || options.isActive?.() === false
        return !executionRejected
      }
      const result = await runPageActionBridge(
        normalized,
        options.bridge,
        options.responseMetadata,
        new Set(), // 已认领，内部不再二次 gated
        beforeExecute,
        options.isActive,
        options.isCurrentPage,
        options.signal,
      )
      if (options.isActive?.() === false) return
      if (!result) return
      if (executionRejected) {
        options.onError?.(new Error(`Page action request expired before execution: ${normalized.actionKey}`))
        return
      }
      if (isTechnicalPageActionFailure(result.status)) {
        options.onError?.(new Error(result.error || `Page action ${result.status}: ${normalized.actionKey}`))
      }
      options.pendingPageActionResults?.set(requestId, result)
      try {
        const acknowledged = await deliverPageActionResult(options, result)
        options.pendingPageActionResults?.delete(requestId)
        notifyDelivered(options, result, acknowledged)
      } catch (error) {
        if (hasStatus(error, 409)) {
          options.pendingPageActionResults?.delete(requestId)
        }
        throw error
      }
    } catch (error) {
      // A failed claim may never have reached Control. Let a later delivery
      // retry; if Control did receive it, its atomic claim returns 409 and
      // the handler still cannot run a second time.
      if (claimAttempted && !claimed && options.isActive?.() !== false) {
        options.handledPageActions.delete(requestId)
      }
      if (options.isActive?.() !== false) options.onError?.(error)
    }
  })()

  options.inFlightPageActions.set(requestId, work)
  try {
    await work
  } finally {
    options.inFlightPageActions.delete(requestId)
  }
}

async function claimPageActionWithRefresh(
  options: {
    apiBase: string
    sessionId: string
    token: string
    refreshToken?: () => Promise<string>
    fetchImpl?: typeof fetch
    signal?: AbortSignal
  },
  request: PageActionDispatchRequest,
): Promise<{ claimed: boolean; traceId?: string }> {
  let token = options.token
  try {
    return await claimPageActionExecutionDetails(
      options.apiBase,
      options.sessionId,
      token,
      request,
      options.fetchImpl,
      options.signal,
    )
  } catch (error) {
    if (hasStatus(error, 409)) return { claimed: false }
    if (!isUnauthorized(error) || !options.refreshToken) throw error
    token = await options.refreshToken()
    try {
      return await claimPageActionExecutionDetails(
        options.apiBase,
        options.sessionId,
        token,
        request,
        options.fetchImpl,
        options.signal,
      )
    } catch (retryError) {
      if (hasStatus(retryError, 409)) return { claimed: false }
      throw retryError
    }
  }
}

async function deliverPageActionResult(
  options: {
    apiBase: string
    sessionId: string
    token: string
    refreshToken?: () => Promise<string>
    fetchImpl?: typeof fetch
    signal?: AbortSignal
  },
  result: PageActionResult,
) {
  let token = options.token
  try {
    return await postPageActionResult(
      options.apiBase,
      options.sessionId,
      token,
      result,
      options.fetchImpl,
      options.signal,
    )
  } catch (error) {
    if (!isUnauthorized(error) || !options.refreshToken) throw error
    token = await options.refreshToken()
    return await postPageActionResult(
      options.apiBase,
      options.sessionId,
      token,
      result,
      options.fetchImpl,
      options.signal,
    )
  }
}

function notifyDelivered(
  options: { onDelivered?: (result: PageActionResultDelivered) => void; onError?: (error: unknown) => void; isActive?: () => boolean },
  result: PageActionResult,
  acknowledged: boolean,
) {
  if (!acknowledged || options.isActive?.() === false) return
  try {
    options.onDelivered?.({ requestId: result.requestId, actionKey: result.actionKey,
      status: result.status, userConfirmed: result.userConfirmed === true })
  } catch (error) {
    // The result was already acknowledged: never replay the action or its result
    // because a host event handler failed.
    options.onError?.(error)
  }
}

async function runPageActionBridge(
  normalized: PageActionDispatchRequest,
  bridge: EafPageBridge,
  responseMetadata: Record<string, unknown> | undefined,
  handledPageActions: Set<string>,
  beforeExecute?: PageActionBeforeExecute,
  isActive?: () => boolean,
  isCurrentPage?: () => boolean,
  signal?: AbortSignal,
): Promise<PageActionResult | null> {
  const globalBridge = typeof window !== 'undefined'
    ? (window as Window & { __REACHAI_PAGE_BRIDGE__?: ReachAiWindowPageBridge }).__REACHAI_PAGE_BRIDGE__
    : undefined
  const result = await bridge.handleEvent(normalized, {
    beforeExecute, isActive, isCurrentPage, signal,
    fallbackHandler: typeof globalBridge?.execute === 'function'
      ? async () => executeWindowPageBridgeAction(normalized, responseMetadata, globalBridge)
      : undefined,
  })
  if (isActive?.() === false) return null
  if (result) {
    handledPageActions.add(normalized.requestId)
  }
  return result
}

export async function processMessagePageActionQueue(options: {
  response: EafChatMessageResponse
  bridge: EafPageBridge
  sessionId: string
  apiBase: string
  token: string
  handledPageActions: Set<string>
  inFlightPageActions: Map<string, Promise<void>>
  pendingPageActionResults?: Map<string, PageActionResult>
  onRequested?: (request: PageActionDispatchRequest) => void
  onClaimed?: (request: PageActionDispatchRequest, traceId: string) => void
  onDelivered?: (result: PageActionResultDelivered) => void
  onError?: (error: unknown) => void
  refreshToken?: () => Promise<string>
  fetchImpl?: typeof fetch
  isActive?: () => boolean
  isCurrentPage?: () => boolean
  signal?: AbortSignal
}): Promise<void> {
  const queue = pageActionQueueFromResponse(options.response)
  for (const request of queue) {
    await dispatchPageActionExactlyOnce({
      request,
      bridge: options.bridge,
      sessionId: options.sessionId,
      apiBase: options.apiBase,
      token: options.token,
      handledPageActions: options.handledPageActions,
      inFlightPageActions: options.inFlightPageActions,
      pendingPageActionResults: options.pendingPageActionResults,
      responseMetadata: options.response.metadata,
      onRequested: options.onRequested,
      onClaimed: options.onClaimed,
      onDelivered: options.onDelivered,
      onError: options.onError,
      refreshToken: options.refreshToken,
      fetchImpl: options.fetchImpl,
      isActive: options.isActive,
      isCurrentPage: options.isCurrentPage,
      signal: options.signal,
    })
  }
}

async function executeWindowPageBridgeAction(
  request: PageActionDispatchRequest,
  responseMetadata?: Record<string, unknown>,
  globalBridge?: ReachAiWindowPageBridge,
): Promise<PageActionResult | null> {
  if (!globalBridge || typeof globalBridge.execute !== 'function') return null
  const pageKey = pageKeyFromRequest(request) || pageKeyFromMetadata(responseMetadata)
  if (!pageKey) {
    return {
      protocolVersion: request.protocolVersion || '1.0',
      type: 'page.action.result',
      requestId: request.requestId,
      actionKey: request.actionKey,
      status: 'ACTION_NOT_FOUND',
      error: 'Page action pageKey is missing for window.__REACHAI_PAGE_BRIDGE__ fallback',
    }
  }
  try {
    const raw = await globalBridge.execute(pageKey, request.actionKey, request.args || {}, {
      confirmed: request.confirm === true,
      requestId: request.requestId,
    })
    const record = raw && typeof raw === 'object' ? raw as Record<string, unknown> : { data: raw }
    const rawStatus = String(record.status || 'SUCCESS').trim().toUpperCase()
    const status = normalizeWindowBridgeStatus(rawStatus)
    const error = record.error && typeof record.error === 'object'
      ? String((record.error as Record<string, unknown>).message || record.message || '')
      : String(record.message || '')
    return {
      protocolVersion: request.protocolVersion || '1.0',
      type: 'page.action.result',
      requestId: request.requestId,
      actionKey: request.actionKey,
      status,
      data: record.data ?? raw,
      message: typeof record.message === 'string' && record.message.trim()
        ? record.message.trim()
        : undefined,
      error: isTechnicalPageActionFailure(status)
        ? error || `Page action returned ${rawStatus}`
        : undefined,
    }
  } catch (error) {
    return {
      protocolVersion: request.protocolVersion || '1.0',
      type: 'page.action.result',
      requestId: request.requestId,
      actionKey: request.actionKey,
      status: 'FAILED',
      error: error instanceof Error ? error.message : String(error),
    }
  }
}

function isTechnicalPageActionFailure(status: PageActionResult['status']): boolean {
  return status === 'FAILED'
    || status === 'TIMEOUT'
    || status === 'ACTION_NOT_FOUND'
    || status === 'FORBIDDEN'
}

function normalizeWindowBridgeStatus(rawStatus: string): PageActionResult['status'] {
  switch (rawStatus) {
    case 'SUCCESS':
    case 'NO_DATA':
    case 'PRECONDITION_FAILED':
    case 'USER_CANCELLED':
    case 'FAILED':
    case 'CANCELLED':
    case 'ACTION_NOT_FOUND':
    case 'FORBIDDEN':
    case 'TIMEOUT':
      return rawStatus
    // Legacy page bridge templates expose WARN / ERROR. Preserve their business
    // meaning on the public Embed boundary instead of treating a warning as a
    // transport failure.
    case 'WARN':
      return 'PRECONDITION_FAILED'
    case 'ERROR':
      return 'FAILED'
    default:
      return 'FAILED'
  }
}

function pageKeyFromRequest(request: PageActionDispatchRequest): string | undefined {
  const metadataPageKey = request.metadata?.pageKey
  return typeof metadataPageKey === 'string' && metadataPageKey.trim() ? metadataPageKey.trim() : undefined
}

export function pageKeyFromMetadata(metadata?: Record<string, unknown>): string | undefined {
  const pageKey = metadata?.pageKey
  return typeof pageKey === 'string' && pageKey.trim() ? pageKey.trim() : undefined
}

export async function postPageActionResult(
  apiBase: string,
  sessionId: string,
  token: string,
  result: PageActionResult,
  fetchImpl: typeof fetch = fetch,
  signal?: AbortSignal,
) {
  const accepted = await postJson<{ requestId?: string; actionKey?: string; status?: string }>(
    `${apiBase}/chat/sessions/${encodeURIComponent(sessionId)}/page-actions/${encodeURIComponent(result.requestId)}/result`,
    result,
    token,
    fetchImpl,
    signal,
  )
  return accepted?.requestId === result.requestId
    && accepted?.actionKey === result.actionKey
    && accepted?.status === result.status
}

export async function claimPageActionExecution(
  apiBase: string,
  sessionId: string,
  token: string,
  request: Pick<PageActionDispatchRequest, 'requestId' | 'actionKey'>,
  fetchImpl: typeof fetch = fetch,
  signal?: AbortSignal,
): Promise<boolean> {
  return (await claimPageActionExecutionDetails(apiBase, sessionId, token, request, fetchImpl, signal)).claimed
}

async function claimPageActionExecutionDetails(
  apiBase: string,
  sessionId: string,
  token: string,
  request: Pick<PageActionDispatchRequest, 'requestId' | 'actionKey'>,
  fetchImpl: typeof fetch = fetch,
  signal?: AbortSignal,
): Promise<{ claimed: boolean; traceId?: string }> {
  const response = await postJson<{ claimed?: boolean; requestId?: string; actionKey?: string; traceId?: unknown }>(
    `${apiBase}/chat/sessions/${encodeURIComponent(sessionId)}/page-actions/${encodeURIComponent(request.requestId)}/claim`,
    { requestId: request.requestId, actionKey: request.actionKey },
    token,
    fetchImpl,
    signal,
  )
  return { claimed: response.claimed === true,
    traceId: response.requestId === request.requestId && response.actionKey === request.actionKey
      && typeof response.traceId === 'string' && /^[a-f0-9]{32}$/i.test(response.traceId)
      ? response.traceId : undefined }
}

export async function pollPendingPageActions(options: {
  apiBase: string
  token: string
  sessionId: string
  bridge: EafPageBridge
  handledPageActions: Set<string>
  pendingPageActions: Set<string>
  inFlightPageActions?: Map<string, Promise<void>>
  pendingPageActionResults?: Map<string, PageActionResult>
  context?: Record<string, unknown>
  onEvent?: (event: EafChatEvent) => void
  onClaimed?: (request: PageActionDispatchRequest, traceId: string) => void
  onError?: (error: unknown) => void
  refreshToken?: () => Promise<string>
  fetchImpl?: typeof fetch
  isActive?: () => boolean
  isCurrentPage?: () => boolean
  signal?: AbortSignal
}) {
  if (options.isActive?.() === false) return
  const fetchImpl = options.fetchImpl || fetch
  const inFlight = options.inFlightPageActions || new Map<string, Promise<void>>()
  const pendingUrl = `${options.apiBase}/chat/sessions/${encodeURIComponent(options.sessionId)}/page-actions/pending?limit=10`
  let activeToken = options.token
  const refreshToken = options.refreshToken
    ? async () => {
        activeToken = await options.refreshToken!()
        return activeToken
      }
    : undefined
  let requests: PageActionDispatchRequest[]
  try {
    requests = await getJson(pendingUrl, activeToken, fetchImpl, options.signal)
  } catch (error) {
    if (!isUnauthorized(error) || !refreshToken) throw error
    await refreshToken()
    requests = await getJson(pendingUrl, activeToken, fetchImpl, options.signal)
  }
  const listedRequestIds = new Set<string>()
  for (const request of requests) {
    if (options.isActive?.() === false) return
    if (!request?.requestId) continue
    listedRequestIds.add(request.requestId)
    await dispatchPageActionExactlyOnce({
      request,
      bridge: options.bridge,
      sessionId: options.sessionId,
      apiBase: options.apiBase,
      token: activeToken,
      handledPageActions: options.handledPageActions,
      inFlightPageActions: inFlight,
      pendingPageActionResults: options.pendingPageActionResults,
      responseMetadata: options.context,
      onRequested: (req) => options.onEvent?.({ type: 'page.action.requested', data: req }),
      onClaimed: options.onClaimed,
      onDelivered: (result) => options.onEvent?.({ type: 'page.action.result.delivered', data: result }),
      onError: options.onError,
      refreshToken,
      fetchImpl,
      isActive: options.isActive,
      isCurrentPage: options.isCurrentPage,
      signal: options.signal,
    })
  }
  for (const [requestId, result] of [
    ...(options.pendingPageActionResults?.entries() || []),
  ]) {
    if (options.isActive?.() === false) return
    if (listedRequestIds.has(requestId)) continue
    options.handledPageActions.add(requestId)
    await dispatchPageActionExactlyOnce({
      request: {
        type: 'page.action.requested',
        requestId,
        actionKey: result.actionKey,
      },
      bridge: options.bridge,
      sessionId: options.sessionId,
      apiBase: options.apiBase,
      token: activeToken,
      handledPageActions: options.handledPageActions,
      inFlightPageActions: inFlight,
      pendingPageActionResults: options.pendingPageActionResults,
      responseMetadata: options.context,
      onDelivered: (delivered) => options.onEvent?.({ type: 'page.action.result.delivered', data: delivered }),
      onError: options.onError,
      refreshToken,
      fetchImpl,
      isActive: options.isActive,
      isCurrentPage: options.isCurrentPage,
      signal: options.signal,
    })
  }
}

async function postJson<T>(url: string, body: unknown, token: string, fetchImpl: typeof fetch, signal?: AbortSignal): Promise<T> {
  const response = await fetchImpl(url, {
    method: 'POST',
    signal,
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

async function getJson<T>(url: string, token: string, fetchImpl: typeof fetch, signal?: AbortSignal): Promise<T> {
  const response = await fetchImpl(url, {
    method: 'GET',
    signal,
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

function requestError(message: string, status: number): Error & { status?: number } {
  const error = new Error(message) as Error & { status?: number }
  error.status = status
  return error
}

function isUnauthorized(error: unknown): boolean {
  return Boolean(error && typeof error === 'object' && (error as { status?: number }).status === 401)
}

function hasStatus(error: unknown, status: number): boolean {
  return Boolean(error && typeof error === 'object' && (error as { status?: number }).status === status)
}
