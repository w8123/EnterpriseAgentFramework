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
const LAYOUT_SETTLE_DELAY = 240

export function useCollapsiblePageHeader(options: CollapsiblePageHeaderOptions) {
  const collapsed = ref(false)
  const scrollTargets = new Set<HTMLElement>()
  let mainContentScrollEl: HTMLElement | null = null
  let previousScrollTop = 0
  let settleTimer = 0

  const collapseScrollTop = options.collapseScrollTop ?? DEFAULT_COLLAPSE_SCROLL_TOP
  const expandScrollTop = options.expandScrollTop ?? DEFAULT_EXPAND_SCROLL_TOP
  const wheelDelta = options.wheelDelta ?? DEFAULT_WHEEL_DELTA

  function getScrollTop() {
    let current = mainContentScrollEl?.scrollTop || 0
    for (const target of scrollTargets) current = Math.max(current, target.scrollTop || 0)
    return current
  }

  function notifyLayoutChange() {
    options.onLayoutChange?.(collapsed.value)
    window.clearTimeout(settleTimer)
    settleTimer = window.setTimeout(() => {
      options.onLayoutChange?.(collapsed.value)
    }, LAYOUT_SETTLE_DELAY)
  }

  function setCollapsed(value: boolean) {
    if (collapsed.value === value) return
    collapsed.value = value
    nextTick(notifyLayoutChange)
  }

  function updateCollapsedByScroll() {
    const scrollTop = getScrollTop()
    if (scrollTop > collapseScrollTop) {
      setCollapsed(true)
    } else if (scrollTop <= expandScrollTop && scrollTop < previousScrollTop) {
      setCollapsed(false)
    }
    previousScrollTop = scrollTop
    options.onScroll?.()
  }

  function handleWheel(event: WheelEvent) {
    if (Math.abs(event.deltaY) < wheelDelta) return
    if (event.deltaY > 0) {
      setCollapsed(true)
    } else if (getScrollTop() <= expandScrollTop) {
      setCollapsed(false)
    }
    options.onScroll?.()
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
    mainContentScrollEl?.addEventListener('wheel', handleWheel, { passive: true })
    nextTick(() => {
      refreshScrollTargets()
      options.onScroll?.()
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
