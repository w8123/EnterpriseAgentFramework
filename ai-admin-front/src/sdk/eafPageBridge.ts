export type PageActionStatus = 'SUCCESS' | 'FAILED' | 'CANCELLED' | 'ACTION_NOT_FOUND' | 'FORBIDDEN' | 'TIMEOUT'

export interface PageActionRequest {
  protocolVersion?: string
  type: 'page.action.requested'
  requestId: string
  target?: {
    pageInstanceId?: string
  }
  actionKey: string
  title?: string
  nodeId?: string
  confirm?: boolean
  args?: Record<string, unknown>
  metadata?: Record<string, unknown>
}

export interface PageActionResult {
  protocolVersion: string
  type: 'page.action.result'
  requestId: string
  actionKey: string
  status: PageActionStatus
  data?: unknown
  error?: string
}

export type PageActionHandler = (args: Record<string, unknown>, request: PageActionRequest) => unknown | Promise<unknown>
export type PageActionConfirm = (request: PageActionRequest) => boolean | Promise<boolean>

export interface EafPageBridge {
  readonly pageInstanceId: string
  readonly route?: string
  readonly registeredActions: string[]
  registerAction(actionKey: string, handler: PageActionHandler): () => void
  handleEvent(event: unknown): Promise<PageActionResult | null>
  onResult(listener: (result: PageActionResult) => void): () => void
}

export interface EafPageBridgeOptions {
  pageInstanceId?: string
  route?: string
  confirmAction?: PageActionConfirm
  actionTimeoutMs?: number
}

export function createEafPageBridge(options: EafPageBridgeOptions = {}): EafPageBridge {
  const pageInstanceId = options.pageInstanceId || createPageInstanceId()
  const actionTimeoutMs = Math.max(1000, options.actionTimeoutMs || 15000)
  const handlers = new Map<string, PageActionHandler>()
  const listeners = new Set<(result: PageActionResult) => void>()

  function emit(result: PageActionResult) {
    listeners.forEach((listener) => listener(result))
  }

  return {
    pageInstanceId,
    route: options.route,
    get registeredActions() {
      return Array.from(handlers.keys())
    },
    registerAction(actionKey, handler) {
      handlers.set(actionKey, handler)
      return () => handlers.delete(actionKey)
    },
    async handleEvent(event) {
      if (!isPageActionRequest(event)) return null
      const targetPageInstanceId = event.target?.pageInstanceId
      if (targetPageInstanceId && targetPageInstanceId !== pageInstanceId) return null
      const handler = handlers.get(event.actionKey)
      if (!handler) {
        const result = resultOf(event, 'ACTION_NOT_FOUND', undefined, `Action is not registered: ${event.actionKey}`)
        emit(result)
        return result
      }
      if (!await confirmIfNeeded(event, options.confirmAction)) {
        const result = resultOf(event, 'CANCELLED', undefined, `Action was cancelled: ${event.actionKey}`)
        emit(result)
        return result
      }
      try {
        const data = await withTimeout(handler(event.args || {}, event), actionTimeoutMs)
        const result = resultOf(event, 'SUCCESS', data)
        emit(result)
        return result
      } catch (error) {
        const message = error instanceof Error ? error.message : String(error)
        const result = resultOf(event, message === 'Page action timed out' ? 'TIMEOUT' : 'FAILED', undefined, message)
        emit(result)
        return result
      }
    },
    onResult(listener) {
      listeners.add(listener)
      return () => listeners.delete(listener)
    },
  }
}

async function confirmIfNeeded(request: PageActionRequest, confirmAction?: PageActionConfirm): Promise<boolean> {
  if (!request.confirm) return true
  if (confirmAction) {
    return await confirmAction(request)
  }
  if (typeof window !== 'undefined' && typeof window.confirm === 'function') {
    return window.confirm(request.title || `Run page action: ${request.actionKey}`)
  }
  return false
}

function resultOf(
  request: PageActionRequest,
  status: PageActionStatus,
  data?: unknown,
  error?: string,
): PageActionResult {
  return {
    protocolVersion: request.protocolVersion || '1.0',
    type: 'page.action.result',
    requestId: request.requestId,
    actionKey: request.actionKey,
    status,
    data,
    error,
  }
}

async function withTimeout<T>(value: T | Promise<T>, timeoutMs: number): Promise<T> {
  let timer: ReturnType<typeof setTimeout> | undefined
  try {
    return await Promise.race([
      Promise.resolve(value),
      new Promise<T>((_, reject) => {
        timer = setTimeout(() => reject(new Error('Page action timed out')), timeoutMs)
      }),
    ])
  } catch (error) {
    if (error instanceof Error && error.message === 'Page action timed out') {
      throw error
    }
    throw error
  } finally {
    if (timer) clearTimeout(timer)
  }
}

function isPageActionRequest(value: unknown): value is PageActionRequest {
  if (!value || typeof value !== 'object') return false
  const record = value as Record<string, unknown>
  return record.type === 'page.action.requested'
    && typeof record.requestId === 'string'
    && typeof record.actionKey === 'string'
}

function createPageInstanceId(): string {
  if (typeof crypto !== 'undefined' && 'randomUUID' in crypto) {
    return `page-${crypto.randomUUID()}`
  }
  return `page-${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}`
}
