import { nextTick, onMounted, onUnmounted, readonly, ref } from 'vue'

export interface CollapsiblePageHeaderOptions {
  rootSelector: string
  scrollSelectors?: string[]
  collapseScrollTop?: number
  expandScrollTop?: number
  wheelDelta?: number
  onScroll?: () => void
  onLayoutChange?: (collapsed: boolean) => void
}

const DEFAULT_COLLAPSE_SCROLL_TOP = 80
const DEFAULT_EXPAND_SCROLL_TOP = 24
const DEFAULT_WHEEL_DELTA = 8
/** Match page-header / summary motion, with buffer against trackpad bounce. */
const LAYOUT_SETTLE_DELAY = 280
/** Require sustained upward intent before re-expanding (avoids flicker). */
const EXPAND_WHEEL_ACCUMULATION = 28

export function useCollapsiblePageHeader(options: CollapsiblePageHeaderOptions) {
  const collapsed = ref(false)
  const scrollTargets = new Set<HTMLElement>()
  let mainContentScrollEl: HTMLElement | null = null
  let previousScrollTop = 0
  let settleTimer = 0
  let reverseLockUntil = 0
  let pendingExpandDelta = 0
  let bootGraceUntil = 0

  const collapseScrollTop = options.collapseScrollTop ?? DEFAULT_COLLAPSE_SCROLL_TOP
  const expandScrollTop = options.expandScrollTop ?? DEFAULT_EXPAND_SCROLL_TOP
  const wheelDelta = options.wheelDelta ?? DEFAULT_WHEEL_DELTA

  function getMainScrollTop() {
    return mainContentScrollEl?.scrollTop || 0
  }

  function getScrollTop() {
    let current = getMainScrollTop()
    for (const target of scrollTargets) current = Math.max(current, target.scrollTop || 0)
    return current
  }

  function notifyLayoutChange() {
    // Only notify after the CSS height/summary transition finishes. Relayout during
    // the animation fights sticky/table geometry and reads as flicker/stutter.
    window.clearTimeout(settleTimer)
    settleTimer = window.setTimeout(() => {
      options.onLayoutChange?.(collapsed.value)
      options.onScroll?.()
    }, LAYOUT_SETTLE_DELAY)
  }

  function setCollapsed(value: boolean) {
    if (collapsed.value === value) return
    collapsed.value = value
    pendingExpandDelta = 0
    reverseLockUntil = performance.now() + LAYOUT_SETTLE_DELAY
    nextTick(notifyLayoutChange)
  }

  function isReverseLocked() {
    return performance.now() < reverseLockUntil
  }

  function inBootGrace() {
    return performance.now() < bootGraceUntil
  }

  function nestedScrollerCanAbsorbWheel(event: WheelEvent) {
    const target = event.target
    if (!(target instanceof Element) || !mainContentScrollEl) return false
    for (const scroller of scrollTargets) {
      if (scroller === mainContentScrollEl) continue
      if (scroller !== target && !scroller.contains(target)) continue
      const maxScroll = scroller.scrollHeight - scroller.clientHeight
      if (maxScroll <= 1) continue
      if (event.deltaY > 0 && scroller.scrollTop < maxScroll - 1) return true
      if (event.deltaY < 0 && scroller.scrollTop > 1) return true
    }
    return false
  }

  function updateCollapsedByScroll() {
    if (inBootGrace()) return
    const scrollTop = getScrollTop()
    // Scroll only collapses. Expanding from scrollTop clamps / nested resets is what
    // created the expand↔collapse flicker; expand is handled by upward wheel at top.
    if (scrollTop > collapseScrollTop) {
      setCollapsed(true)
    }
    previousScrollTop = scrollTop
  }

  function handleWheel(event: WheelEvent) {
    if (inBootGrace()) return
    if (Math.abs(event.deltaY) < wheelDelta) return

    // Prefer list/table body scrolling. Header chrome only reacts when the nested
    // scroller cannot absorb this gesture (at top/bottom or no overflow).
    if (nestedScrollerCanAbsorbWheel(event)) {
      pendingExpandDelta = 0
      return
    }

    if (event.deltaY > 0) {
      pendingExpandDelta = 0
      setCollapsed(true)
      return
    }

    if (!collapsed.value || isReverseLocked() || getScrollTop() > expandScrollTop) {
      pendingExpandDelta = 0
      return
    }

    // Sustained upward intent at top — ignore trackpad bounce opposite ticks.
    pendingExpandDelta += Math.abs(event.deltaY)
    if (pendingExpandDelta >= EXPAND_WHEEL_ACCUMULATION) {
      setCollapsed(false)
    }
  }

  function unbindScrollTargets() {
    for (const target of scrollTargets) target.removeEventListener('scroll', updateCollapsedByScroll)
    scrollTargets.clear()
  }

  function refreshScrollTargets() {
    unbindScrollTargets()
    if (mainContentScrollEl) scrollTargets.add(mainContentScrollEl)
    const root = document.querySelector(options.rootSelector)
    for (const selector of options.scrollSelectors ?? []) {
      root?.querySelectorAll<HTMLElement>(selector).forEach((target) => scrollTargets.add(target))
    }
    for (const target of scrollTargets) {
      target.addEventListener('scroll', updateCollapsedByScroll, { passive: true })
    }
    previousScrollTop = getScrollTop()
  }

  onMounted(() => {
    mainContentScrollEl = document.querySelector('.main-layout .main-content') as HTMLElement | null
    // Ignore restored scroll / first layout thrash so the page doesn't boot collapsed.
    bootGraceUntil = performance.now() + LAYOUT_SETTLE_DELAY
    if (mainContentScrollEl) mainContentScrollEl.scrollTop = 0
    mainContentScrollEl?.addEventListener('wheel', handleWheel, { passive: true })
    nextTick(() => {
      refreshScrollTargets()
    })
  })

  onUnmounted(() => {
    mainContentScrollEl?.removeEventListener('wheel', handleWheel)
    unbindScrollTargets()
    window.clearTimeout(settleTimer)
  })

  return {
    collapsed: readonly(collapsed),
    setCollapsed,
    refreshScrollTargets,
  }
}
