import { computed, ref, type Ref } from 'vue'

export interface UseApiGraphHorizontalDockDeps {
  panelExpanded: Readonly<Ref<boolean | undefined>>
}

function nearestScrollableAncestor(el: HTMLElement): HTMLElement {
  let p: HTMLElement | null = el.parentElement
  while (p) {
    const st = getComputedStyle(p)
    if (/(auto|scroll|overlay)/.test(st.overflowY) || /(auto|scroll|overlay)/.test(st.overflowX))
      return p
    p = p.parentElement
  }
  return document.documentElement
}

export function useApiGraphHorizontalDock(deps: UseApiGraphHorizontalDockDeps) {
  const graphScrollRef = ref<HTMLElement | null>(null)
  const hScrollTrackRef = ref<HTMLElement | null>(null)
  const hScrollContentWidth = ref(0)
  const hScrollNeedsDock = ref(false)
  const hScrollDockLeft = ref(0)
  const hScrollDockWidth = ref(0)

  const hScrollDockVisible = computed(
    () => deps.panelExpanded.value !== false && hScrollNeedsDock.value,
  )

  const hScrollDockPositionStyle = computed(() => ({
    left: `${hScrollDockLeft.value}px`,
    width: `${Math.max(0, hScrollDockWidth.value)}px`,
  }))

  let syncingHorizontalScroll = false
  let graphScrollResizeObserver: ResizeObserver | null = null
  let hScrollDockLayoutRaf = 0
  let dockScrollParent: HTMLElement | null = null
  let dockScrollHandler: (() => void) | null = null

  function unbindDockScrollListeners() {
    if (dockScrollParent && dockScrollHandler) {
      dockScrollParent.removeEventListener('scroll', dockScrollHandler)
    }
    dockScrollParent = null
    dockScrollHandler = null
  }

  function bindDockScrollListeners() {
    unbindDockScrollListeners()
    const el = graphScrollRef.value
    if (!el)
      return
    const sp = nearestScrollableAncestor(el)
    dockScrollHandler = () => scheduleHScrollDockLayout()
    sp.addEventListener('scroll', dockScrollHandler, { passive: true })
    dockScrollParent = sp
  }

  function scheduleHScrollDockLayout() {
    if (hScrollDockLayoutRaf)
      return
    hScrollDockLayoutRaf = requestAnimationFrame(() => {
      hScrollDockLayoutRaf = 0
      refreshHScrollDockMetrics()
    })
  }

  function refreshHScrollDockMetrics() {
    const el = graphScrollRef.value
    if (!el) {
      hScrollContentWidth.value = 0
      hScrollNeedsDock.value = false
      hScrollDockLeft.value = 0
      hScrollDockWidth.value = 0
      return
    }
    hScrollContentWidth.value = el.scrollWidth
    hScrollNeedsDock.value = el.scrollWidth > el.clientWidth + 1
    const r = el.getBoundingClientRect()
    hScrollDockLeft.value = r.left
    hScrollDockWidth.value = r.width
  }

  function onGraphScroll() {
    if (syncingHorizontalScroll)
      return
    const main = graphScrollRef.value
    const dock = hScrollTrackRef.value
    if (!main || !dock)
      return
    syncingHorizontalScroll = true
    dock.scrollLeft = main.scrollLeft
    syncingHorizontalScroll = false
    scheduleHScrollDockLayout()
  }

  function onHScrollDockScroll() {
    if (syncingHorizontalScroll)
      return
    const main = graphScrollRef.value
    const dock = hScrollTrackRef.value
    if (!main || !dock)
      return
    syncingHorizontalScroll = true
    main.scrollLeft = dock.scrollLeft
    syncingHorizontalScroll = false
  }

  function onGraphWheel(e: WheelEvent) {
    const el = graphScrollRef.value
    if (!el)
      return
    const maxX = el.scrollWidth - el.clientWidth
    if (maxX <= 0)
      return
    const horiz = e.deltaX + (e.shiftKey ? e.deltaY : 0)
    if (!horiz)
      return
    e.preventDefault()
    el.scrollLeft = Math.max(0, Math.min(maxX, el.scrollLeft + horiz))
    syncingHorizontalScroll = true
    if (hScrollTrackRef.value)
      hScrollTrackRef.value.scrollLeft = el.scrollLeft
    syncingHorizontalScroll = false
  }

  function onWindowResizeForDock() {
    scheduleHScrollDockLayout()
  }

  function mountHScrollDock() {
    const ro = new ResizeObserver(() => {
      scheduleHScrollDockLayout()
    })
    graphScrollResizeObserver = ro
    window.addEventListener('resize', onWindowResizeForDock, { passive: true })
    if (graphScrollRef.value)
      ro.observe(graphScrollRef.value)
    bindDockScrollListeners()
    refreshHScrollDockMetrics()
  }

  function unmountHScrollDock() {
    window.removeEventListener('resize', onWindowResizeForDock)
    unbindDockScrollListeners()
    graphScrollResizeObserver?.disconnect()
    graphScrollResizeObserver = null
  }

  return {
    graphScrollRef,
    hScrollTrackRef,
    hScrollContentWidth,
    hScrollDockVisible,
    hScrollDockPositionStyle,
    bindDockScrollListeners,
    refreshHScrollDockMetrics,
    scheduleHScrollDockLayout,
    onGraphScroll,
    onHScrollDockScroll,
    onGraphWheel,
    mountHScrollDock,
    unmountHScrollDock,
  }
}
