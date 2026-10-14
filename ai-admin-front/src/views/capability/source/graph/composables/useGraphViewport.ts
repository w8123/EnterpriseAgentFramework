import { onBeforeUnmount, ref, watch, type Ref } from 'vue'

export interface GraphRect { x: number; y: number; width: number; height: number }
export function graphRectVisible(rect: GraphRect, viewport: GraphRect | null, margin = 180) {
  return !viewport || (rect.x + rect.width >= viewport.x - margin && rect.x <= viewport.x + viewport.width + margin
    && rect.y + rect.height >= viewport.y - margin && rect.y <= viewport.y + viewport.height + margin)
}
export function graphPathBounds(path: string): GraphRect | null {
  const segments = [...path.matchAll(/([MLCQAHVZ])([^a-zA-Z]*)/g)]
  if (!segments.length || /[a-z]|[B-IKN-PR-UW-Y]/.test(path)) return null
  let minX = Infinity, maxX = -Infinity, minY = Infinity, maxY = -Infinity
  let x = 0, y = 0
  function include(px: number, py: number, rx = 0, ry = 0) {
    minX = Math.min(minX, px - rx); maxX = Math.max(maxX, px + rx)
    minY = Math.min(minY, py - ry); maxY = Math.max(maxY, py + ry)
  }
  for (const [, command, argumentsText] of segments) {
    const values = argumentsText.match(/-?(?:\d+\.?\d*|\.\d+)(?:e[+-]?\d+)?/gi)?.map(Number) || []
    if (command === 'A') {
      if (values.length % 7) return null
      for (let i = 0; i < values.length; i += 7) {
        const radius = Math.max(Math.abs(values[i]), Math.abs(values[i + 1]))
        include(x, y, radius, radius)
        x = values[i + 5]; y = values[i + 6]; include(x, y, radius, radius)
      }
    } else if (command === 'H' || command === 'V') {
      for (const value of values) { if (command === 'H') x = value; else y = value; include(x, y) }
    } else {
      if (values.length % 2) return null
      for (let i = 0; i < values.length; i += 2) { x = values[i]; y = values[i + 1]; include(x, y) }
    }
  }
  if (!Number.isFinite(minX) || !Number.isFinite(minY)) return null
  return { x: minX, y: minY, width: maxX - minX, height: maxY - minY }
}
export function useGraphViewport(element: Ref<HTMLElement | undefined | null>) {
  const viewport = ref<GraphRect | null>(null)
  let observer: ResizeObserver | null = null
  function update() {
    const node = element.value
    viewport.value = node?.clientWidth && node.clientHeight
      ? { x: node.scrollLeft, y: node.scrollTop, width: node.clientWidth, height: node.clientHeight } : null
  }
  watch(element, node => {
    observer?.disconnect()
    observer = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(update)
    if (node) observer?.observe(node)
    update()
  }, { immediate: true, flush: 'post' })
  window.addEventListener('resize', update)
  onBeforeUnmount(() => { observer?.disconnect(); window.removeEventListener('resize', update) })
  return { viewport, update }
}
