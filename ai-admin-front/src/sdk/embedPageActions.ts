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
  onError?: (error: unknown) => void
  refreshToken?: () => Promise<string>
  fetchImpl?: typeof fetch
}): Promise<void> {
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
      await deliverPageActionResult(options, pendingResult)
      options.pendingPageActionResults?.delete(requestId)
      return
    }
    // 先认领，防止并发第二进入 Bridge
    options.handledPageActions.add(requestId)
    options.onRequested?.(normalized)
    try {
      let executionRejected = false
      let claimAttempted = false
      let claimed = false
      const beforeExecute: PageActionBeforeExecute = async () => {
        if (claimAttempted) return claimed
        claimAttempted = true
        claimed = await claimPageActionWithRefresh(options, normalized)
        executionRejected = !claimed
        return claimed
      }
      const result = await runPageActionBridge(
        normalized,
        options.bridge,
        options.responseMetadata,
        new Set(), // 已认领，内部不再二次 gated
        beforeExecute,
      )
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
        await deliverPageActionResult(options, result)
      } catch (error) {
        if (hasStatus(error, 409)) {
          options.pendingPageActionResults?.delete(requestId)
        }
        throw error
      }
      options.pendingPageActionResults?.delete(requestId)
    } catch (error) {
      options.onError?.(error)
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
  },
  request: PageActionDispatchRequest,
): Promise<boolean> {
  let token = options.token
  try {
    return await claimPageActionExecution(
      options.apiBase,
      options.sessionId,
      token,
      request,
      options.fetchImpl,
    )
  } catch (error) {
    if (hasStatus(error, 409)) return false
    if (!isUnauthorized(error) || !options.refreshToken) throw error
    token = await options.refreshToken()
    try {
      return await claimPageActionExecution(
        options.apiBase,
        options.sessionId,
        token,
        request,
        options.fetchImpl,
      )
    } catch (retryError) {
      if (hasStatus(retryError, 409)) return false
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
  },
  result: PageActionResult,
) {
  let token = options.token
  try {
    await postPageActionResult(
      options.apiBase,
      options.sessionId,
      token,
      result,
      options.fetchImpl,
    )
  } catch (error) {
    if (!isUnauthorized(error) || !options.refreshToken) throw error
    token = await options.refreshToken()
    await postPageActionResult(
      options.apiBase,
      options.sessionId,
      token,
      result,
      options.fetchImpl,
    )
  }
}

async function runPageActionBridge(
  normalized: PageActionDispatchRequest,
  bridge: EafPageBridge,
  responseMetadata: Record<string, unknown> | undefined,
  handledPageActions: Set<string>,
  beforeExecute?: PageActionBeforeExecute,
): Promise<PageActionResult | null> {
  const result = await bridge.handleEvent(normalized, { beforeExecute })
  if (result && result.status !== 'ACTION_NOT_FOUND') {
    handledPageActions.add(normalized.requestId)
    return result
  }
  if (beforeExecute && !await beforeExecute(normalized)) {
    return {
      protocolVersion: normalized.protocolVersion || '1.0',
      type: 'page.action.result',
      requestId: normalized.requestId,
      actionKey: normalized.actionKey,
      status: 'TIMEOUT',
      error: 'Page action request expired before execution',
    }
  }
  const fallback = await executeWindowPageBridgeAction(normalized, responseMetadata)
  if (fallback) {
    if (fallback.status !== 'ACTION_NOT_FOUND') handledPageActions.add(normalized.requestId)
    return fallback
  }
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
  onError?: (error: unknown) => void
  refreshToken?: () => Promise<string>
  fetchImpl?: typeof fetch
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
      onError: options.onError,
      refreshToken: options.refreshToken,
      fetchImpl: options.fetchImpl,
    })
  }
}

async function executeWindowPageBridgeAction(
  request: PageActionDispatchRequest,
  responseMetadata?: Record<string, unknown>,
): Promise<PageActionResult | null> {
  const globalBridge = typeof window !== 'undefined'
    ? (window as Window & { __REACHAI_PAGE_BRIDGE__?: ReachAiWindowPageBridge }).__REACHAI_PAGE_BRIDGE__
    : undefined
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
      confirmed: true,
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
) {
  await postJson(
    `${apiBase}/chat/sessions/${encodeURIComponent(sessionId)}/page-actions/${encodeURIComponent(result.requestId)}/result`,
    result,
    token,
    fetchImpl,
  )
}

export async function claimPageActionExecution(
  apiBase: string,
  sessionId: string,
  token: string,
  request: Pick<PageActionDispatchRequest, 'requestId' | 'actionKey'>,
  fetchImpl: typeof fetch = fetch,
): Promise<boolean> {
  const response = await postJson<{ claimed?: boolean }>(
    `${apiBase}/chat/sessions/${encodeURIComponent(sessionId)}/page-actions/${encodeURIComponent(request.requestId)}/claim`,
    { requestId: request.requestId, actionKey: request.actionKey },
    token,
    fetchImpl,
  )
  return response.claimed === true
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
  onError?: (error: unknown) => void
  refreshToken?: () => Promise<string>
  fetchImpl?: typeof fetch
}) {
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
    requests = await getJson(pendingUrl, activeToken, fetchImpl)
  } catch (error) {
    if (!isUnauthorized(error) || !refreshToken) throw error
    await refreshToken()
    requests = await getJson(pendingUrl, activeToken, fetchImpl)
  }
  const listedRequestIds = new Set<string>()
  for (const request of requests) {
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
      onError: options.onError,
      refreshToken,
      fetchImpl,
    })
  }
  for (const [requestId, result] of [
    ...(options.pendingPageActionResults?.entries() || []),
  ]) {
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
      onError: options.onError,
      refreshToken,
      fetchImpl,
    })
  }
}

async function postJson<T>(url: string, body: unknown, token: string, fetchImpl: typeof fetch): Promise<T> {
  const response = await fetchImpl(url, {
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

async function getJson<T>(url: string, token: string, fetchImpl: typeof fetch): Promise<T> {
  const response = await fetchImpl(url, {
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
