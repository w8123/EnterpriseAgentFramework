/**
 * Page Action status is a public Embed protocol contract.  The three
 * business-terminal values mean that the browser handled the request, but
 * the requested business effect cannot continue in the current state.  They
 * must not be collapsed into a transport or SDK failure.
 *
 * `CANCELLED` remains accepted for pre-v2 callers. New integrations should
 * return `USER_CANCELLED` when the end user rejects a confirmation.
 */
export type PageActionStatus =
  | 'SUCCESS'
  | 'NO_DATA'
  | 'PRECONDITION_FAILED'
  | 'USER_CANCELLED'
  | 'FAILED'
  | 'CANCELLED'
  | 'ACTION_NOT_FOUND'
  | 'FORBIDDEN'
  | 'TIMEOUT'

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
  /** Short business-facing explanation, safe to use in the assistant answer. */
  message?: string
  error?: string
  /**
   * True only when this SDK displayed a confirmation for this request and the
   * user accepted it before the server lease was claimed. Runtime can use
   * this verified fact when composing the final answer.
   */
  userConfirmed?: boolean
}

/**
 * Optional structured value a registered action can return. Returning a plain
 * value remains equivalent to `{ status: 'SUCCESS', data: value }`.
 */
export interface PageActionBusinessResult<T = unknown> {
  status: PageActionStatus
  data?: T
  message?: string
  error?: string | { message?: string }
}

export type PageActionHandler = (args: Record<string, unknown>, request: PageActionRequest) => unknown | Promise<unknown>
export type PageActionConfirm = (request: PageActionRequest) => boolean | Promise<boolean>
export type PageActionBeforeExecute = (request: PageActionRequest) => boolean | Promise<boolean>

export interface PageActionExecutionOptions {
  /**
   * Called after user confirmation and immediately before any host-side effect.
   * ReachAI Embed uses it to atomically claim a still-active server request.
   */
  beforeExecute?: PageActionBeforeExecute
  /** Rechecked synchronously just before calling a host handler. */
  isActive?: () => boolean
  /** Whether this bridge still belongs to the currently bound page. */
  isCurrentPage?: () => boolean
  /** Cancels pending confirmation when the embedding client is destroyed. */
  signal?: AbortSignal
  /** Optional host fallback, subject to the same target, confirmation and claim checks. */
  fallbackHandler?: PageActionHandler
}

/**
 * ReachAI uses this reserved action internally when a published page Workflow
 * needs to move an SPA from the current page to another registered page. A
 * business application never registers this key itself; it supplies
 * `onNavigate` when creating the bridge instead.
 */
export const EAF_PAGE_NAVIGATE_ACTION = '__reachai.navigate'

/**
 * A platform-approved, cross-route navigation request. `targetPageKey` and
 * `route` are emitted by ReachAI from the published page Workflow, not from
 * chat text. The host application must still validate the target against its
 * own route registry before navigating.
 */
export interface EafPageNavigationRequest {
  requestId: string
  targetPageKey: string
  route?: string
  title?: string
  metadata?: Record<string, unknown>
}

/**
 * Host-side SPA navigation adapter. Once it resolves, the SDK reports that
 * navigation started; the target page then calls `chat.rebindPage(...)` after
 * its Page Bridge and actions are ready.
 */
export type EafPageNavigationHandler = (
  request: EafPageNavigationRequest,
) => void | Promise<void>

export interface PageActionRegisterOptions {
  title?: string
  description?: string
  confirmRequired?: boolean
  inputSchema?: Record<string, unknown>
  outputSchema?: Record<string, unknown>
  sampleArgs?: Record<string, unknown>
  allowedAgentIds?: string[]
  metadata?: Record<string, unknown>
}

export interface EafPageActionDefinition extends PageActionRegisterOptions {
  actionKey: string
}

export interface EafPageBridge {
  readonly pageInstanceId: string
  readonly route?: string
  /** Whether this bridge can handle platform-requested cross-page navigation. */
  readonly supportsNavigation?: boolean
  /** Opt in only when navigation replaces the whole document. */
  readonly documentNavigation?: boolean
  readonly registeredActions: string[]
  readonly actionDefinitions: EafPageActionDefinition[]
  registerAction(actionKey: string, handler: PageActionHandler, options?: PageActionRegisterOptions): () => void
  handleEvent(event: unknown, executionOptions?: PageActionExecutionOptions): Promise<PageActionResult | null>
  onResult(listener: (result: PageActionResult) => void): () => void
  onActionDefinitionsChange(listener: (definitions: EafPageActionDefinition[]) => void): () => void
}

export interface EafPageBridgeOptions {
  pageInstanceId?: string
  route?: string
  confirmAction?: PageActionConfirm
  /** Handles platform-approved cross-route navigation for this SPA page. */
  onNavigate?: EafPageNavigationHandler
  /** Requests a persisted checkpoint before a whole-document navigation. */
  documentNavigation?: boolean
  actionTimeoutMs?: number
}

export function createEafPageBridge(options: EafPageBridgeOptions = {}): EafPageBridge {
  const pageInstanceId = options.pageInstanceId || createPageInstanceId()
  const actionTimeoutMs = Math.max(1000, options.actionTimeoutMs || 15000)
  const handlers = new Map<string, PageActionHandler>()
  const definitions = new Map<string, PageActionRegisterOptions>()
  const registrations = new Map<string, symbol>()
  const listeners = new Set<(result: PageActionResult) => void>()
  const definitionListeners = new Set<(definitions: EafPageActionDefinition[]) => void>()

  function actionDefinitions(): EafPageActionDefinition[] {
    return Array.from(handlers.keys()).map((actionKey) => ({
      actionKey,
      ...(definitions.get(actionKey) || {}),
    }))
  }

  function emit(result: PageActionResult) {
    listeners.forEach((listener) => listener(result))
  }

  function emitDefinitionsChange() {
    const snapshot = actionDefinitions()
    definitionListeners.forEach((listener) => listener(snapshot))
  }

  return {
    pageInstanceId,
    route: options.route,
    supportsNavigation: typeof options.onNavigate === 'function',
    documentNavigation: options.documentNavigation === true && typeof options.onNavigate === 'function',
    get registeredActions() {
      return Array.from(handlers.keys())
    },
    get actionDefinitions() {
      return actionDefinitions()
    },
    registerAction(actionKey, handler, registerOptions = {}) {
      const registration = Symbol(actionKey)
      registrations.set(actionKey, registration)
      handlers.set(actionKey, handler)
      definitions.set(actionKey, registerOptions)
      emitDefinitionsChange()
      return () => {
        if (registrations.get(actionKey) !== registration) return
        registrations.delete(actionKey)
        handlers.delete(actionKey)
        definitions.delete(actionKey)
        emitDefinitionsChange()
      }
    },
    async handleEvent(incomingEvent, executionOptions) {
      if (!isPageActionRequest(incomingEvent)) return null
      let event = incomingEvent
      let confirmedArgsJson: string | undefined
      // The default confirmation must show the same JSON values that reach the
      // action even if the caller mutates its original object while it is open.
      // Custom confirmation callbacks own their own display/approval contract.
      if (event.confirm && !options.confirmAction) {
        try {
          confirmedArgsJson = serializeJsonValue(event.args ?? {})
          const args = deepFreezeJson(JSON.parse(confirmedArgsJson)) as Record<string, unknown>
          const metadata = event.metadata == null ? undefined
            : deepFreezeJson(JSON.parse(serializeJsonValue(event.metadata))) as Record<string, unknown>
          const target = event.target == null ? undefined
            : deepFreezeJson(JSON.parse(serializeJsonValue(event.target))) as PageActionRequest['target']
          event = Object.freeze({ ...event, args, metadata, target })
        } catch {
          const result = resultOf(event, 'FAILED', undefined, undefined, 'Page action arguments cannot be displayed safely')
          emit(result)
          return result
        }
      }
      const targetPageInstanceId = event.target?.pageInstanceId
      if (targetPageInstanceId && targetPageInstanceId !== pageInstanceId) return null
      if (event.actionKey === EAF_PAGE_NAVIGATE_ACTION) {
        const userConfirmed = await confirmIfNeeded(event, options.confirmAction, executionOptions?.signal)
        if (executionOptions?.isActive?.() === false) return null
        if (!userConfirmed.accepted) {
          const result = resultOf(
            event,
            'USER_CANCELLED',
            undefined,
            `Action was cancelled: ${event.actionKey}`,
          )
          emit(result)
          return result
        }
        const allowed = await executionAllowed(event, executionOptions)
        if (executionOptions?.isActive?.() === false) return null
        if (!allowed) {
          const result = expiredBeforeExecution(event)
          emit(result)
          return result
        }
        const result = await handleNavigation(event, options.onNavigate)
        if (executionOptions?.isActive?.() === false) return null
        if (userConfirmed.confirmed) result.userConfirmed = true
        emit(result)
        return result
      }
      const handler = handlers.get(event.actionKey) || executionOptions?.fallbackHandler
      const registration = registrations.get(event.actionKey)
      if (!handler) {
        const result = resultOf(
          event,
          'ACTION_NOT_FOUND',
          undefined,
          undefined,
          `Action is not registered: ${event.actionKey}`,
        )
        emit(result)
        return result
      }
      if (executionOptions?.isCurrentPage?.() === false) {
        const result = resultOf(event, 'ACTION_NOT_FOUND', undefined, undefined, 'Page action no longer belongs to the active page')
        emit(result)
        return result
      }
      const userConfirmed = await confirmIfNeeded(event, options.confirmAction, executionOptions?.signal)
      if (executionOptions?.isActive?.() === false) return null
      if (!userConfirmed.accepted) {
        const result = resultOf(
          event,
          'USER_CANCELLED',
          undefined,
          `Action was cancelled: ${event.actionKey}`,
        )
        emit(result)
        return result
      }
      const registrationChanged = () => executionOptions?.isCurrentPage?.() === false
        || registrations.get(event.actionKey) !== registration
      if (registrationChanged()) {
        const result = resultOf(event, 'ACTION_NOT_FOUND', undefined, undefined, 'Page action registration changed before execution')
        emit(result)
        return result
      }
      const allowed = await executionAllowed(event, executionOptions)
      if (executionOptions?.isActive?.() === false) return null
      if (!allowed) {
        const result = expiredBeforeExecution(event)
        emit(result)
        return result
      }
      if (registrationChanged()) {
        const result = resultOf(event, 'ACTION_NOT_FOUND', undefined, undefined, 'Page action registration changed before execution')
        emit(result)
        return result
      }
      try {
        const actionArgs = confirmedArgsJson === undefined ? event.args || {} : JSON.parse(confirmedArgsJson)
        const handlerEvent = confirmedArgsJson === undefined ? event : { ...event, args: actionArgs }
        const data = await withTimeout(handler(actionArgs, handlerEvent), actionTimeoutMs)
        if (executionOptions?.isActive?.() === false) return null
        const result = resultFromHandler(event, data)
        if (userConfirmed.confirmed) result.userConfirmed = true
        emit(result)
        return result
      } catch (error) {
        if (executionOptions?.isActive?.() === false) return null
        const message = error instanceof Error ? error.message : String(error)
        const result = resultOf(
          event,
          message === 'Page action timed out' ? 'TIMEOUT' : 'FAILED',
          undefined,
          undefined,
          message,
        )
        emit(result)
        return result
      }
    },
    onResult(listener) {
      listeners.add(listener)
      return () => listeners.delete(listener)
    },
    onActionDefinitionsChange(listener) {
      definitionListeners.add(listener)
      return () => definitionListeners.delete(listener)
    },
  }
}

/** Reject values JSON would silently omit or change before asking for consent. */
function serializeJsonValue(value: unknown): string {
  const ancestors = new Set<object>()
  function validate(item: unknown): void {
    if (item === null || typeof item === 'string' || typeof item === 'boolean') return
    if (typeof item === 'number' && Number.isFinite(item)) return
    if (typeof item !== 'object') throw new Error('Non-JSON value')
    const object = item as object
    const prototype = Object.getPrototypeOf(object)
    if (Array.isArray(object) ? prototype !== Array.prototype : prototype !== Object.prototype && prototype !== null) {
      throw new Error('Non-JSON object')
    }
    if (ancestors.has(object)) throw new Error('Circular JSON value')
    ancestors.add(object)
    const descriptors = Object.getOwnPropertyDescriptors(object)
    if (Object.getOwnPropertySymbols(object).length) throw new Error('Non-JSON key')
    if (Array.isArray(object)) {
      for (let index = 0; index < object.length; index += 1) {
        const descriptor = descriptors[String(index)]
        if (!descriptor || !('value' in descriptor) || !descriptor.enumerable) throw new Error('Non-JSON array')
        validate(descriptor.value)
      }
      if (Object.keys(descriptors).length !== object.length + 1) throw new Error('Non-JSON array property')
    } else {
      for (const descriptor of Object.values(descriptors)) {
        if (!('value' in descriptor) || !descriptor.enumerable) throw new Error('Non-JSON property')
        validate(descriptor.value)
      }
    }
    ancestors.delete(object)
  }
  validate(value)
  const serialized = JSON.stringify(value)
  if (typeof serialized !== 'string') throw new Error('Non-JSON value')
  return serialized
}

function deepFreezeJson(value: unknown): unknown {
  if (value && typeof value === 'object') {
    for (const child of Object.values(value)) deepFreezeJson(child)
    Object.freeze(value)
  }
  return value
}

async function executionAllowed(
  request: PageActionRequest,
  options?: PageActionExecutionOptions,
): Promise<boolean> {
  return options?.beforeExecute ? await options.beforeExecute(request) : true
}

function expiredBeforeExecution(request: PageActionRequest): PageActionResult {
  return resultOf(
    request,
    'TIMEOUT',
    undefined,
    undefined,
    'Page action request expired before execution',
  )
}

async function handleNavigation(
  request: PageActionRequest,
  onNavigate?: EafPageNavigationHandler,
): Promise<PageActionResult> {
  const targetPageKey = text(request.metadata?.pageKey)
  const route = text(request.metadata?.route)
  if (!targetPageKey) {
    return resultOf(
      request,
      'FAILED',
      undefined,
      undefined,
      'Cross-route navigation is missing metadata.pageKey',
    )
  }
  if (!onNavigate) {
    return resultOf(
      request,
      'ACTION_NOT_FOUND',
      undefined,
      undefined,
      'Cross-route navigation is not configured. Set createEafPageBridge({ onNavigate }).',
    )
  }
  try {
    await onNavigate({
      requestId: request.requestId,
      targetPageKey,
      route,
      title: text(request.title),
      metadata: request.metadata,
    })
    // Do not include a pageInstanceId here. The target page owns a different
    // instance identity and confirms readiness through chat.rebindPage(...).
    return resultOf(request, 'SUCCESS', {
      pageKey: targetPageKey,
      route,
      ready: false,
    }, 'Navigation started')
  } catch (error) {
    return resultOf(
      request,
      'FAILED',
      undefined,
      undefined,
      error instanceof Error ? error.message : String(error),
    )
  }
}

async function confirmIfNeeded(
  request: PageActionRequest,
  confirmAction?: PageActionConfirm,
  signal?: AbortSignal,
): Promise<{ accepted: boolean; confirmed: boolean }> {
  if (!request.confirm) return { accepted: true, confirmed: false }
  if (signal?.aborted) return { accepted: false, confirmed: true }
  if (confirmAction) {
    const decision = Promise.resolve().then(() => signal?.aborted ? false : confirmAction(request))
    if (!signal) return { accepted: await decision, confirmed: true }
    const accepted = await new Promise<boolean>((resolve, reject) => {
      const abort = () => { signal.removeEventListener('abort', abort); resolve(false) }
      signal.addEventListener('abort', abort, { once: true })
      if (signal.aborted) { abort(); return }
      decision.then(value => { signal.removeEventListener('abort', abort); resolve(value) },
        error => { signal.removeEventListener('abort', abort); reject(error) })
    })
    return { accepted, confirmed: true }
  }
  return { accepted: await showReachAiPageActionConfirmation(request, signal), confirmed: true }
}

/**
 * Native `window.confirm` blocks the browser event loop. That prevents the
 * Embed pending poll from observing the same request while the user decides
 * and makes the confirmation invisible to accessibility/browser tooling.
 * Keep the default confirmation inside the host page instead.
 */
let cancelActiveConfirmation: (() => void) | undefined

function showReachAiPageActionConfirmation(request: PageActionRequest, signal?: AbortSignal): Promise<boolean> {
  if (typeof document === 'undefined' || !document.body) return Promise.resolve(false)
  if (signal?.aborted) return Promise.resolve(false)

  cancelActiveConfirmation?.()

  return new Promise((resolve) => {
    const returnFocus = document.activeElement instanceof HTMLElement ? document.activeElement : undefined
    const backdrop = document.createElement('div')
    backdrop.dataset.reachaiPageActionConfirm = request.requestId
    backdrop.setAttribute('role', 'presentation')
    Object.assign(backdrop.style, {
      position: 'fixed',
      inset: '0',
      zIndex: '2147483647',
      display: 'flex',
      alignItems: 'center',
      justifyContent: 'center',
      padding: '24px',
      background: 'rgba(15, 23, 42, 0.48)',
    })

    const dialog = document.createElement('section')
    dialog.setAttribute('role', 'alertdialog')
    dialog.setAttribute('aria-modal', 'true')
    dialog.setAttribute('aria-labelledby', `reachai-confirm-title-${request.requestId}`)
    dialog.setAttribute('aria-describedby', `reachai-confirm-message-${request.requestId}`)
    Object.assign(dialog.style, {
      width: 'min(440px, calc(100vw - 48px))',
      maxHeight: 'calc(100vh - 48px)',
      boxSizing: 'border-box',
      display: 'flex',
      flexDirection: 'column',
      overflow: 'auto',
      borderRadius: '16px',
      padding: '22px',
      color: '#172033',
      background: '#fff',
      boxShadow: '0 24px 72px rgba(15, 23, 42, 0.28)',
      fontFamily: 'system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif',
    })

    const eyebrow = document.createElement('div')
    eyebrow.textContent = 'ReachAI 页面操作确认'
    Object.assign(eyebrow.style, {
      marginBottom: '8px',
      color: '#0b7a59',
      fontSize: '13px',
      fontWeight: '700',
    })

    const title = document.createElement('h2')
    title.id = `reachai-confirm-title-${request.requestId}`
    title.textContent = request.title || request.actionKey
    Object.assign(title.style, {
      margin: '0 0 10px',
      fontSize: '20px',
      lineHeight: '1.4',
    })

    const message = document.createElement('p')
    message.id = `reachai-confirm-message-${request.requestId}`
    message.textContent = '此操作会修改当前业务页面数据。确认后 ReachAI 才会领取请求并调用页面动作。'
    Object.assign(message.style, {
      margin: '0 0 12px',
      color: '#52605f',
      fontSize: '14px',
      lineHeight: '1.7',
    })

    const actionLabel = document.createElement('p')
    actionLabel.textContent = request.actionKey === EAF_PAGE_NAVIGATE_ACTION
      ? `动作：${request.actionKey}\n目标页面：${text(request.metadata?.pageKey) || '-'}\n目标路径：${text(request.metadata?.route) || '-'}`
      : `动作：${request.actionKey}`
    Object.assign(actionLabel.style, {
      margin: '0 0 8px',
      fontSize: '13px',
      overflowWrap: 'anywhere',
      whiteSpace: 'pre-wrap',
    })

    const argsLabel = document.createElement('p')
    argsLabel.textContent = '操作内容'
    Object.assign(argsLabel.style, { margin: '0 0 6px', fontSize: '13px', fontWeight: '700' })
    const args = document.createElement('pre')
    args.setAttribute('aria-label', '操作内容')
    args.tabIndex = 0
    let argsDisplayable = true
    try {
      const serialized = JSON.stringify(request.args || {}, null, 2)
      if (typeof serialized !== 'string') throw new Error('Unserializable page action arguments')
      args.textContent = serialized
    } catch {
      argsDisplayable = false
      args.textContent = '操作内容无法显示，请取消并重试。'
    }
    Object.assign(args.style, {
      minHeight: '0',
      maxHeight: 'min(40vh, 320px)',
      overflow: 'auto',
      whiteSpace: 'pre-wrap',
      overflowWrap: 'anywhere',
      margin: '0 0 16px',
      padding: '10px',
      borderRadius: '8px',
      background: '#f4f7f6',
      color: '#172033',
      font: '13px/1.55 ui-monospace, monospace',
    })

    const actions = document.createElement('div')
    Object.assign(actions.style, {
      display: 'flex',
      justifyContent: 'flex-end',
      gap: '10px',
    })

    const cancel = confirmationButton('取消', false)
    const confirm = confirmationButton('确认执行', true)
    confirm.disabled = !argsDisplayable
    actions.append(cancel, confirm)
    dialog.append(eyebrow, title, message, actionLabel, argsLabel, args, actions)
    backdrop.append(dialog)
    document.body.append(backdrop)

    let settled = false
    const finish = (accepted: boolean) => {
      if (settled) return
      settled = true
      document.removeEventListener('keydown', onKeydown, true)
      signal?.removeEventListener('abort', onAbort)
      if (cancelActiveConfirmation === onAbort) cancelActiveConfirmation = undefined
      backdrop.remove()
      if (returnFocus?.isConnected) returnFocus.focus({ preventScroll: true })
      resolve(accepted)
    }
    const onAbort = () => finish(false)
    const onKeydown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        event.preventDefault()
        finish(false)
        return
      }
      if (event.key !== 'Tab') return
      const focusable: HTMLElement[] = [args, cancel, confirm].filter(element => !(element instanceof HTMLButtonElement && element.disabled))
      const index = focusable.indexOf(document.activeElement as HTMLElement)
      if (index < 0 || (event.shiftKey && index === 0) || (!event.shiftKey && index === focusable.length - 1)) {
        event.preventDefault()
        ;(event.shiftKey ? focusable[focusable.length - 1] : focusable[0]).focus()
      }
    }
    cancel.addEventListener('click', () => finish(false), { once: true })
    confirm.addEventListener('click', () => finish(true), { once: true })
    document.addEventListener('keydown', onKeydown, true)
    signal?.addEventListener('abort', onAbort, { once: true })
    cancelActiveConfirmation = onAbort
    if (signal?.aborted) { onAbort(); return }
    ;(argsDisplayable ? confirm : cancel).focus()
  })
}

function confirmationButton(label: string, primary: boolean): HTMLButtonElement {
  const button = document.createElement('button')
  button.type = 'button'
  button.textContent = label
  Object.assign(button.style, {
    minWidth: '96px',
    border: primary ? '1px solid #0b7a59' : '1px solid #d7dee7',
    borderRadius: '9px',
    padding: '9px 14px',
    color: primary ? '#fff' : '#344054',
    background: primary ? '#0b7a59' : '#fff',
    cursor: 'pointer',
    fontSize: '14px',
    fontWeight: '600',
  })
  return button
}

function resultOf(
  request: PageActionRequest,
  status: PageActionStatus,
  data?: unknown,
  message?: string,
  error?: string,
): PageActionResult {
  return {
    protocolVersion: request.protocolVersion || '1.0',
    type: 'page.action.result',
    requestId: request.requestId,
    actionKey: request.actionKey,
    status,
    data,
    message,
    error,
  }
}

function resultFromHandler(
  request: PageActionRequest,
  value: unknown,
): PageActionResult {
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    return resultOf(request, 'SUCCESS', value)
  }
  const record = value as Record<string, unknown>
  const status = normalizeHandlerStatus(record.status)
  if (!status) {
    return resultOf(request, 'SUCCESS', value)
  }
  const data = Object.prototype.hasOwnProperty.call(record, 'data')
    ? record.data
    : value
  return resultOf(
    request,
    status,
    data,
    text(record.message),
    errorText(record.error),
  )
}

function normalizeHandlerStatus(value: unknown): PageActionStatus | undefined {
  if (typeof value !== 'string') return undefined
  const status = value.trim().toUpperCase()
  if (status === 'WARN') return 'PRECONDITION_FAILED'
  if (status === 'ERROR') return 'FAILED'
  return isPageActionStatus(status) ? status : undefined
}

function isPageActionStatus(value: string): value is PageActionStatus {
  return value === 'SUCCESS'
    || value === 'NO_DATA'
    || value === 'PRECONDITION_FAILED'
    || value === 'USER_CANCELLED'
    || value === 'FAILED'
    || value === 'CANCELLED'
    || value === 'ACTION_NOT_FOUND'
    || value === 'FORBIDDEN'
    || value === 'TIMEOUT'
}

function text(value: unknown): string | undefined {
  return typeof value === 'string' && value.trim() ? value.trim() : undefined
}

function errorText(value: unknown): string | undefined {
  if (typeof value === 'string') return text(value)
  if (value && typeof value === 'object') {
    return text((value as Record<string, unknown>).message)
  }
  return undefined
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
