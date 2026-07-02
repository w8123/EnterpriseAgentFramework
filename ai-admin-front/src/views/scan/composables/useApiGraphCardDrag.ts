import { ref, type Ref } from 'vue'
import { saveApiGraphLayout } from '@/api/apiGraph'
import type { Point } from '../apiGraphCanvasGeometry'
import type { ApiCardVM } from '../apiGraphCanvasViewModel'

export interface UseApiGraphCardDragDeps {
  projectId: () => number
  manualApiPositions: Ref<Record<number, Point>>
  minX: number
  minY: number
}

type ApiCardDragState = {
  nodeId: number
  startClientX: number
  startClientY: number
  startX: number
  startY: number
  moved: boolean
}

export function useApiGraphCardDrag(deps: UseApiGraphCardDragDeps) {
  const draggingApiId = ref<number | null>(null)
  const suppressNextCardClick = ref(false)
  let dragState: ApiCardDragState | null = null

  function consumeSuppressedCardClick() {
    if (!suppressNextCardClick.value)
      return false
    suppressNextCardClick.value = false
    return true
  }

  function startApiDrag(event: PointerEvent, card: ApiCardVM) {
    if (event.button !== 0)
      return
    const target = event.target as HTMLElement | null
    if (target?.closest('.field-row'))
      return

    dragState = {
      nodeId: card.node.id,
      startClientX: event.clientX,
      startClientY: event.clientY,
      startX: card.x,
      startY: card.y,
      moved: false,
    }
    draggingApiId.value = card.node.id
    window.addEventListener('pointermove', handleApiDragMove)
    window.addEventListener('pointerup', stopApiDrag, { once: true })
  }

  function handleApiDragMove(event: PointerEvent) {
    if (!dragState)
      return
    const deltaX = event.clientX - dragState.startClientX
    const deltaY = event.clientY - dragState.startClientY
    if (!dragState.moved && Math.hypot(deltaX, deltaY) < 4)
      return

    dragState.moved = true
    suppressNextCardClick.value = true
    deps.manualApiPositions.value = {
      ...deps.manualApiPositions.value,
      [dragState.nodeId]: {
        x: Math.max(deps.minX, dragState.startX + deltaX),
        y: Math.max(deps.minY, dragState.startY + deltaY),
      },
    }
  }

  async function persistApiCardLayout(nodeId: number, pos: Point) {
    try {
      await saveApiGraphLayout(deps.projectId(), {
        positions: [{ nodeId, x: pos.x, y: pos.y }],
      })
    } catch {
      /* controlRequest 拦截器已提示 */
    }
  }

  function stopApiDrag() {
    const state = dragState
    if (state?.moved) {
      suppressNextCardClick.value = true
      window.setTimeout(() => {
        suppressNextCardClick.value = false
      }, 0)
      const pos = deps.manualApiPositions.value[state.nodeId]
      if (pos) {
        void persistApiCardLayout(state.nodeId, pos)
      }
    }
    dragState = null
    draggingApiId.value = null
    window.removeEventListener('pointermove', handleApiDragMove)
    window.removeEventListener('pointerup', stopApiDrag)
  }

  function unmountApiCardDrag() {
    dragState = null
    draggingApiId.value = null
    window.removeEventListener('pointermove', handleApiDragMove)
    window.removeEventListener('pointerup', stopApiDrag)
  }

  return {
    draggingApiId,
    consumeSuppressedCardClick,
    startApiDrag,
    unmountApiCardDrag,
  }
}
