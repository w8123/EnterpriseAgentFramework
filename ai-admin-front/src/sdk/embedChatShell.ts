type EafChatPosition = 'inline' | 'bottom-right' | 'bottom-left'
export type EafChatLauncherState = 'idle' | 'thinking' | 'unread' | 'error'

interface EmbedChatShellOptions {
  mount: HTMLElement
  position?: EafChatPosition
  initialOpen?: boolean
  brandName: string
  locale: string
  resizable?: boolean
  launcherDraggable?: boolean
  onOpen?: () => void
}

export interface EmbedChatShellController {
  readonly root: HTMLElement
  readonly panel: HTMLElement
  readonly body: HTMLElement
  readonly connectionStatus: HTMLElement
  readonly minimizeButton: HTMLButtonElement
  readonly launcher: HTMLButtonElement
  isOpen(): boolean
  open(): void
  close(): void
  toggle(): void
  setLauncherState(state: EafChatLauncherState): void
  destroy(): void
}

type ResizeAnchor = 'north-west' | 'north-east' | 'south-east'

interface ResizeSession {
  pointerId: number
  startX: number
  startY: number
  width: number
  height: number
  left: number
  top: number
  customPosition: boolean
}

interface DragSession {
  pointerId: number
  startX: number
  startY: number
  left: number
  top: number
  moved: boolean
}

const DEFAULT_FLOATING_WIDTH = 380
const DEFAULT_FLOATING_HEIGHT = 560
const MIN_PANEL_WIDTH = 320
const MIN_PANEL_HEIGHT = 360
const VIEWPORT_GAP = 12
const LAUNCHER_EDGE_GAP = 18

let shellSequence = 0

export function createEmbedChatShell(options: EmbedChatShellOptions): EmbedChatShellController {
  const position = options.position || 'inline'
  const floating = position !== 'inline'
  const resizable = options.resizable !== false
  const launcherDraggable = floating && options.launcherDraggable !== false
  const english = options.locale === 'en-US'
  const panelId = `reachai-chat-panel-${++shellSequence}`
  const root = document.createElement('div')
  root.className = 'eaf-chat'
  root.dataset.launcherState = 'thinking'
  root.dataset.position = position
  if (floating) root.classList.add(`eaf-chat--${position}`)
  if (!resizable) root.classList.add('eaf-chat--resize-disabled')

  const openLabel = english ? `Open ${options.brandName}` : `打开${options.brandName}`
  const minimizeLabel = english ? `Minimize ${options.brandName}` : `最小化${options.brandName}`
  const resizeLabel = english ? 'Resize conversation' : '调整对话框大小'
  root.innerHTML = `
    <section id="${panelId}" class="eaf-chat__panel" aria-label="${escapeHtml(options.brandName)}">
      <div class="eaf-chat__header">
        <div class="eaf-chat__brand">${escapeHtml(options.brandName)}</div>
        <div class="eaf-chat__header-actions">
          <span class="eaf-chat__connection-status" role="status" aria-live="polite">${english ? 'Connecting' : '正在连接'}</span>
          <button
            class="eaf-chat__toggle eaf-chat__minimize"
            type="button"
            aria-label="${escapeHtml(minimizeLabel)}"
            title="${escapeHtml(minimizeLabel)}"
            aria-controls="${panelId}"
            aria-expanded="true"
          ><span aria-hidden="true"></span></button>
        </div>
      </div>
      <div class="eaf-chat__body"></div>
      <button
        class="eaf-chat__resize-handle"
        type="button"
        aria-label="${escapeHtml(resizeLabel)}"
        title="${escapeHtml(resizeLabel)}"
      ><span aria-hidden="true"></span></button>
    </section>
    <div class="eaf-chat__launcher-wrap">
      <span class="eaf-chat__launcher-label">${escapeHtml(options.brandName)}</span>
      <button
        class="eaf-chat__launcher"
        type="button"
        aria-label="${escapeHtml(openLabel)}"
        aria-controls="${panelId}"
        aria-expanded="false"
      >
        <span class="eaf-chat__launcher-orbit eaf-chat__launcher-orbit--back" aria-hidden="true"></span>
        <span class="eaf-chat__launcher-prism" aria-hidden="true"></span>
        <span class="eaf-chat__launcher-glass" aria-hidden="true"></span>
        <span class="eaf-chat__launcher-orbit" aria-hidden="true"></span>
        <span class="eaf-chat__launcher-particle" aria-hidden="true"></span>
        <span class="eaf-chat__launcher-badge" aria-hidden="true">1</span>
        <span class="eaf-chat__launcher-error" aria-hidden="true">!</span>
      </button>
    </div>
  `
  options.mount.appendChild(root)

  const panel = requiredElement<HTMLElement>(root, '.eaf-chat__panel')
  const header = requiredElement<HTMLElement>(root, '.eaf-chat__header')
  const body = requiredElement<HTMLElement>(root, '.eaf-chat__body')
  const connectionStatus = requiredElement<HTMLElement>(root, '.eaf-chat__connection-status')
  const minimizeButton = requiredElement<HTMLButtonElement>(root, '.eaf-chat__minimize')
  const resizeHandle = requiredElement<HTMLButtonElement>(root, '.eaf-chat__resize-handle')
  const launcherWrap = requiredElement<HTMLElement>(root, '.eaf-chat__launcher-wrap')
  const launcher = requiredElement<HTMLButtonElement>(root, '.eaf-chat__launcher')
  const launcherLabel = requiredElement<HTMLElement>(root, '.eaf-chat__launcher-label')
  let resizeAnchor: ResizeAnchor = position === 'bottom-left'
    ? 'north-east'
    : position === 'bottom-right'
      ? 'north-west'
      : 'south-east'
  resizeHandle.dataset.resizeAnchor = resizeAnchor
  resizeHandle.hidden = !resizable
  launcher.classList.toggle('eaf-chat__launcher--draggable', launcherDraggable)
  root.classList.toggle('eaf-chat--panel-draggable', floating)

  let opened = options.initialOpen !== false
  let destroyed = false
  let launcherState: EafChatLauncherState = 'thinking'
  let resizeSession: ResizeSession | null = null
  let dragSession: DragSession | null = null
  let panelDragSession: DragSession | null = null
  let suppressLauncherClick = false
  let previousResizeUserSelect = ''
  let previousPanelDragUserSelect = ''

  function syncVisibility() {
    root.classList.toggle('eaf-chat--closed', !opened)
    root.dataset.open = opened ? 'true' : 'false'
    panel.hidden = !opened
    panel.setAttribute('aria-hidden', opened ? 'false' : 'true')
    launcherWrap.hidden = opened
    launcher.setAttribute('aria-expanded', opened ? 'true' : 'false')
    minimizeButton.setAttribute('aria-expanded', opened ? 'true' : 'false')
  }

  function setOpen(nextOpen: boolean, notify = true) {
    if (destroyed || opened === nextOpen) return
    opened = nextOpen
    syncVisibility()
    if (opened && notify) options.onOpen?.()
  }

  function setLauncherState(state: EafChatLauncherState) {
    launcherState = state
    root.dataset.launcherState = state
    const suffix = state === 'thinking'
      ? (english ? 'connecting' : '正在连接')
      : state === 'unread'
        ? (english ? 'new message' : '有新消息')
        : state === 'error'
          ? (english ? 'connection error' : '连接异常')
          : ''
    const nextLabel = suffix
      ? (english ? `${openLabel} (${suffix})` : `${openLabel}（${suffix}）`)
      : openLabel
    launcher.setAttribute('aria-label', nextLabel)
    launcher.title = nextLabel
    launcherLabel.textContent = state === 'error'
      ? (english ? `${options.brandName} · Connection error` : `${options.brandName} · 连接异常`)
      : state === 'unread'
        ? (english ? `${options.brandName} · New message` : `${options.brandName} · 有新消息`)
        : options.brandName
  }

  function currentPanelSize() {
    const rect = panel.getBoundingClientRect()
    const width = rect.width || readPixelVariable(root, '--reachai-chat-user-width') || DEFAULT_FLOATING_WIDTH
    const height = rect.height || readPixelVariable(root, '--reachai-chat-user-height') || DEFAULT_FLOATING_HEIGHT
    return { width, height }
  }

  function hasCustomPanelPosition() {
    return floating
      && Number.isFinite(Number.parseFloat(panel.style.left))
      && Number.isFinite(Number.parseFloat(panel.style.top))
  }

  function setPanelPosition(left: number, top: number) {
    if (!floating) return
    const rect = panel.getBoundingClientRect()
    const size = currentPanelSize()
    const width = rect.width || size.width
    const height = rect.height || size.height
    const maxLeft = Math.max(VIEWPORT_GAP, window.innerWidth - width - VIEWPORT_GAP)
    const maxTop = Math.max(VIEWPORT_GAP, window.innerHeight - height - VIEWPORT_GAP)
    const nextLeft = clamp(left, VIEWPORT_GAP, maxLeft)
    const nextTop = clamp(top, VIEWPORT_GAP, maxTop)
    panel.style.right = 'auto'
    panel.style.bottom = 'auto'
    panel.style.left = `${Math.round(nextLeft)}px`
    panel.style.top = `${Math.round(nextTop)}px`
    root.classList.add('eaf-chat--user-positioned')
  }

  function sizeBounds() {
    const mountRect = options.mount.getBoundingClientRect()
    const availableWidth = floating
      ? window.innerWidth - VIEWPORT_GAP * 2
      : (mountRect.width || window.innerWidth)
    const availableHeight = floating
      ? window.innerHeight - VIEWPORT_GAP * 2
      : (mountRect.height || window.innerHeight)
    const maxWidth = Math.max(240, availableWidth)
    const maxHeight = Math.max(260, availableHeight)
    return {
      minWidth: Math.min(MIN_PANEL_WIDTH, maxWidth),
      minHeight: Math.min(MIN_PANEL_HEIGHT, maxHeight),
      maxWidth,
      maxHeight,
    }
  }

  function setPanelSize(width: number, height: number) {
    const bounds = sizeBounds()
    const nextWidth = Math.round(clamp(width, bounds.minWidth, bounds.maxWidth))
    const nextHeight = Math.round(clamp(height, bounds.minHeight, bounds.maxHeight))
    root.style.setProperty('--reachai-chat-user-width', `${nextWidth}px`)
    root.style.setProperty('--reachai-chat-user-height', `${nextHeight}px`)
    root.classList.add('eaf-chat--user-sized')
    resizeHandle.setAttribute(
      'aria-label',
      english
        ? `${resizeLabel}, current ${nextWidth} by ${nextHeight}`
        : `${resizeLabel}，当前 ${nextWidth} × ${nextHeight}`,
    )
    return { width: nextWidth, height: nextHeight }
  }

  function resizeFromPointer(event: PointerEvent) {
    if (!resizeSession || event.pointerId !== resizeSession.pointerId) return
    const dx = event.clientX - resizeSession.startX
    const dy = event.clientY - resizeSession.startY
    const width = resizeSession.width + (resizeAnchor.includes('west') ? -dx : dx)
    const height = resizeSession.height + (resizeAnchor.includes('north') ? -dy : dy)
    const nextSize = setPanelSize(width, height)
    if (resizeSession.customPosition) {
      const left = resizeSession.left
        + (resizeAnchor.includes('west') ? resizeSession.width - nextSize.width : 0)
      const top = resizeSession.top
        + (resizeAnchor.includes('north') ? resizeSession.height - nextSize.height : 0)
      setPanelPosition(left, top)
    }
    event.preventDefault()
  }

  function finishResize(event?: PointerEvent) {
    if (!resizeSession || (event && event.pointerId !== resizeSession.pointerId)) return
    const pointerId = resizeSession.pointerId
    resizeSession = null
    root.classList.remove('eaf-chat--resizing')
    document.body.style.userSelect = previousResizeUserSelect
    try {
      if (resizeHandle.hasPointerCapture?.(pointerId)) resizeHandle.releasePointerCapture(pointerId)
    } catch {
      // Pointer capture may already be released by the browser after pointercancel.
    }
  }

  function onResizePointerDown(event: PointerEvent) {
    if (!resizable || event.button !== 0 || panelDragSession) return
    const size = currentPanelSize()
    const rect = panel.getBoundingClientRect()
    resizeSession = {
      pointerId: event.pointerId,
      startX: event.clientX,
      startY: event.clientY,
      width: size.width,
      height: size.height,
      left: rect.left,
      top: rect.top,
      customPosition: hasCustomPanelPosition(),
    }
    previousResizeUserSelect = document.body.style.userSelect
    document.body.style.userSelect = 'none'
    root.classList.add('eaf-chat--resizing')
    resizeHandle.focus({ preventScroll: true })
    resizeHandle.setPointerCapture?.(event.pointerId)
    event.preventDefault()
    event.stopPropagation()
  }

  function onResizeKeyDown(event: KeyboardEvent) {
    if (!resizable || !['ArrowLeft', 'ArrowRight', 'ArrowUp', 'ArrowDown'].includes(event.key)) return
    const step = event.shiftKey ? 40 : 16
    const size = currentPanelSize()
    let width = size.width
    let height = size.height
    const rect = panel.getBoundingClientRect()
    const customPosition = hasCustomPanelPosition()
    if (event.key === 'ArrowLeft') width += resizeAnchor.includes('west') ? step : -step
    if (event.key === 'ArrowRight') width += resizeAnchor.includes('west') ? -step : step
    if (event.key === 'ArrowUp') height += resizeAnchor.includes('north') ? step : -step
    if (event.key === 'ArrowDown') height += resizeAnchor.includes('north') ? -step : step
    const nextSize = setPanelSize(width, height)
    if (customPosition) {
      const left = rect.left + (resizeAnchor.includes('west') ? size.width - nextSize.width : 0)
      const top = rect.top + (resizeAnchor.includes('north') ? size.height - nextSize.height : 0)
      setPanelPosition(left, top)
    }
    event.preventDefault()
  }

  function isInteractiveHeaderTarget(target: EventTarget | null) {
    return target instanceof Element
      && Boolean(target.closest('button, a, input, textarea, select, [role="button"], [contenteditable="true"]'))
  }

  function onPanelPointerDown(event: PointerEvent) {
    if (!floating || !opened || event.button !== 0 || event.isPrimary === false || resizeSession) return
    if (isInteractiveHeaderTarget(event.target)) return
    const rect = panel.getBoundingClientRect()
    panelDragSession = {
      pointerId: event.pointerId,
      startX: event.clientX,
      startY: event.clientY,
      left: rect.left,
      top: rect.top,
      moved: false,
    }
    previousPanelDragUserSelect = document.body.style.userSelect
    document.body.style.userSelect = 'none'
    root.classList.add('eaf-chat--panel-dragging')
    header.setPointerCapture?.(event.pointerId)
    event.preventDefault()
  }

  function onPanelPointerMove(event: PointerEvent) {
    if (!panelDragSession || event.pointerId !== panelDragSession.pointerId) return
    const dx = event.clientX - panelDragSession.startX
    const dy = event.clientY - panelDragSession.startY
    if (!panelDragSession.moved && Math.hypot(dx, dy) > 3) panelDragSession.moved = true
    if (!panelDragSession.moved) return
    setPanelPosition(panelDragSession.left + dx, panelDragSession.top + dy)
    event.preventDefault()
  }

  function finishPanelDrag(event?: PointerEvent) {
    if (!panelDragSession || (event && event.pointerId !== panelDragSession.pointerId)) return
    const pointerId = panelDragSession.pointerId
    panelDragSession = null
    root.classList.remove('eaf-chat--panel-dragging')
    document.body.style.userSelect = previousPanelDragUserSelect
    try {
      if (header.hasPointerCapture?.(pointerId)) header.releasePointerCapture(pointerId)
    } catch {
      // Pointer capture may already be released by the browser after pointercancel.
    }
  }

  function setLauncherPosition(left: number, top: number) {
    const width = launcherWrap.offsetWidth || 64
    const height = launcherWrap.offsetHeight || 64
    const maxLeft = Math.max(VIEWPORT_GAP, window.innerWidth - width - VIEWPORT_GAP)
    const maxTop = Math.max(VIEWPORT_GAP, window.innerHeight - height - VIEWPORT_GAP)
    const nextLeft = clamp(left, VIEWPORT_GAP, maxLeft)
    const nextTop = clamp(top, VIEWPORT_GAP, maxTop)
    launcherWrap.style.right = 'auto'
    launcherWrap.style.bottom = 'auto'
    launcherWrap.style.left = `${Math.round(nextLeft)}px`
    launcherWrap.style.top = `${Math.round(nextTop)}px`
    const dockLeft = nextLeft + width / 2 < window.innerWidth / 2
    root.dataset.launcherDock = dockLeft ? 'left' : 'right'
    if (floating && !hasCustomPanelPosition()) {
      resizeAnchor = dockLeft ? 'north-east' : 'north-west'
      resizeHandle.dataset.resizeAnchor = resizeAnchor
    }
  }

  function snapLauncherToEdge() {
    const rect = launcherWrap.getBoundingClientRect()
    const dockLeft = rect.left + rect.width / 2 < window.innerWidth / 2
    setLauncherPosition(
      dockLeft ? LAUNCHER_EDGE_GAP : window.innerWidth - rect.width - LAUNCHER_EDGE_GAP,
      rect.top,
    )
  }

  function onLauncherPointerDown(event: PointerEvent) {
    if (!launcherDraggable || event.button !== 0) return
    const rect = launcherWrap.getBoundingClientRect()
    dragSession = {
      pointerId: event.pointerId,
      startX: event.clientX,
      startY: event.clientY,
      left: rect.left,
      top: rect.top,
      moved: false,
    }
    launcher.setPointerCapture?.(event.pointerId)
    root.classList.add('eaf-chat--launcher-dragging')
  }

  function onLauncherPointerMove(event: PointerEvent) {
    if (!dragSession || event.pointerId !== dragSession.pointerId) return
    const dx = event.clientX - dragSession.startX
    const dy = event.clientY - dragSession.startY
    if (!dragSession.moved && Math.hypot(dx, dy) > 4) dragSession.moved = true
    if (!dragSession.moved) return
    setLauncherPosition(dragSession.left + dx, dragSession.top + dy)
    event.preventDefault()
  }

  function onLauncherPointerUp(event: PointerEvent) {
    if (!dragSession || event.pointerId !== dragSession.pointerId) return
    const moved = dragSession.moved
    dragSession = null
    root.classList.remove('eaf-chat--launcher-dragging')
    try {
      if (launcher.hasPointerCapture?.(event.pointerId)) launcher.releasePointerCapture(event.pointerId)
    } catch {
      // Pointer capture may already be released by the browser after pointercancel.
    }
    if (moved) {
      snapLauncherToEdge()
      suppressLauncherClick = true
      window.setTimeout(() => {
        suppressLauncherClick = false
      }, 0)
    }
  }

  function onLauncherClick() {
    if (suppressLauncherClick) {
      suppressLauncherClick = false
      return
    }
    setOpen(true)
  }

  function onViewportResize() {
    const customLeft = Number.parseFloat(launcherWrap.style.left)
    const customTop = Number.parseFloat(launcherWrap.style.top)
    if (Number.isFinite(customLeft) && Number.isFinite(customTop)) {
      setLauncherPosition(customLeft, customTop)
    }
    if (root.classList.contains('eaf-chat--user-sized')) {
      const size = currentPanelSize()
      setPanelSize(size.width, size.height)
    }
    const customPanelLeft = Number.parseFloat(panel.style.left)
    const customPanelTop = Number.parseFloat(panel.style.top)
    if (Number.isFinite(customPanelLeft) && Number.isFinite(customPanelTop)) {
      setPanelPosition(customPanelLeft, customPanelTop)
    }
  }

  minimizeButton.addEventListener('click', () => setOpen(false))
  header.addEventListener('pointerdown', onPanelPointerDown)
  launcher.addEventListener('click', onLauncherClick)
  launcher.addEventListener('pointerdown', onLauncherPointerDown)
  launcher.addEventListener('pointermove', onLauncherPointerMove)
  launcher.addEventListener('pointerup', onLauncherPointerUp)
  launcher.addEventListener('pointercancel', onLauncherPointerUp)
  resizeHandle.addEventListener('pointerdown', onResizePointerDown)
  resizeHandle.addEventListener('keydown', onResizeKeyDown)
  window.addEventListener('pointermove', resizeFromPointer)
  window.addEventListener('pointermove', onPanelPointerMove)
  window.addEventListener('pointerup', finishResize)
  window.addEventListener('pointerup', finishPanelDrag)
  window.addEventListener('pointercancel', finishResize)
  window.addEventListener('pointercancel', finishPanelDrag)
  window.addEventListener('resize', onViewportResize)

  syncVisibility()
  setLauncherState(launcherState)

  return {
    root,
    panel,
    body,
    connectionStatus,
    minimizeButton,
    launcher,
    isOpen: () => opened,
    open: () => setOpen(true),
    close: () => setOpen(false),
    toggle: () => setOpen(!opened),
    setLauncherState,
    destroy() {
      if (destroyed) return
      destroyed = true
      finishResize()
      finishPanelDrag()
      window.removeEventListener('pointermove', resizeFromPointer)
      window.removeEventListener('pointermove', onPanelPointerMove)
      window.removeEventListener('pointerup', finishResize)
      window.removeEventListener('pointerup', finishPanelDrag)
      window.removeEventListener('pointercancel', finishResize)
      window.removeEventListener('pointercancel', finishPanelDrag)
      window.removeEventListener('resize', onViewportResize)
      root.remove()
    },
  }
}

function requiredElement<T extends Element>(root: ParentNode, selector: string): T {
  const element = root.querySelector<T>(selector)
  if (!element) throw new Error(`ReachAI embed shell element missing: ${selector}`)
  return element
}

function readPixelVariable(element: HTMLElement, property: string): number {
  const value = Number.parseFloat(element.style.getPropertyValue(property))
  return Number.isFinite(value) ? value : 0
}

function clamp(value: number, min: number, max: number): number {
  return Math.min(Math.max(value, min), max)
}

function escapeHtml(value: string): string {
  return value.replace(/[<>&"']/g, (character) => ({
    '<': '&lt;',
    '>': '&gt;',
    '&': '&amp;',
    '"': '&quot;',
    "'": '&#39;',
  }[character] || character))
}
