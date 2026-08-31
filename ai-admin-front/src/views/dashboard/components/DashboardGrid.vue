<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import type { DashboardWidgetInstance, DashboardWidgetRect } from '@/types/operationsDashboard'
import {
  clampRect,
  computeColumnWidth,
  DASHBOARD_GRID_GAP,
  DASHBOARD_GRID_ROW_HEIGHT,
  pixelsToGridUnits,
  rectToPixels,
  resizeRectFromEdge,
  type DashboardResizeEdge,
} from '../layoutEngine'
import { getWidgetDefinition } from '../widgetRegistry'

/**
 * 12 列 Dashboard 网格：不依赖第三方布局库。
 * 拖拽与缩放使用 Pointer Events；坐标换算基于容器宽度、列宽、gap 与 rowHeight。
 * 拖动中只预览被拖项的候选矩形（已 clamp），碰撞下推在 pointerup 提交时统一结算。
 */
const props = withDefaults(
  defineProps<{
    items: DashboardWidgetInstance[]
    editing?: boolean
    narrow?: boolean
    columns?: number
    rowHeight?: number
    gap?: number
    selectedId?: string | null
  }>(),
  {
    editing: false,
    narrow: false,
    columns: 12,
    rowHeight: DASHBOARD_GRID_ROW_HEIGHT,
    gap: DASHBOARD_GRID_GAP,
    selectedId: null,
  },
)

const emit = defineEmits<{
  select: [id: string]
  commitRect: [id: string, rect: DashboardWidgetRect]
}>()

defineSlots<{
  default(props: { instance: DashboardWidgetInstance }): unknown
}>()

const containerRef = ref<HTMLElement | null>(null)
const containerWidth = ref(0)
let resizeObserver: ResizeObserver | null = null
let windowResizeHandler: (() => void) | null = null

const colWidth = computed(() => computeColumnWidth(containerWidth.value, props.columns, props.gap))

interface DragState {
  id: string
  mode: 'move' | 'resize'
  resizeEdge?: DashboardResizeEdge
  origin: DashboardWidgetRect
  startX: number
  startY: number
  candidate: DashboardWidgetRect | null
}

const drag = ref<DragState | null>(null)

function measure() {
  const element = containerRef.value
  if (!element) return
  const width = element.getBoundingClientRect().width
  if (width > 0) containerWidth.value = width
}

onMounted(() => {
  measure()
  if (typeof ResizeObserver !== 'undefined') {
    resizeObserver = new ResizeObserver((entries) => {
      const width = entries[0]?.contentRect?.width ?? 0
      if (width > 0) containerWidth.value = width
    })
    resizeObserver.observe(containerRef.value as Element)
  } else if (typeof window !== 'undefined') {
    windowResizeHandler = measure
    window.addEventListener('resize', measure)
  }
})

onBeforeUnmount(() => {
  resizeObserver?.disconnect()
  if (windowResizeHandler) window.removeEventListener('resize', windowResizeHandler)
  detachWindowListeners()
})

function constraintsFor(widgetKey: string) {
  return getWidgetDefinition(widgetKey)?.grid ?? null
}

function selectClosestItem(target: HTMLElement | null) {
  const item = target?.closest<HTMLElement>('[data-grid-item]')
  if (item?.dataset.gridItem) emit('select', item.dataset.gridItem)
}

function resizeEdgeFor(handle: HTMLElement): DashboardResizeEdge {
  const edge = handle.dataset.gridResizeEdge
  return edge === 'left' || edge === 'bottom' ? edge : 'right'
}

function onPointerDown(event: PointerEvent) {
  if (!props.editing) return
  const target = event.target as HTMLElement | null
  // 标题栏整体可拖动，但其内部按钮仍必须正常点击，不能误启动拖拽。
  if (target?.closest('button, a, input, select, textarea, [contenteditable="true"], [data-grid-no-drag]')) {
    selectClosestItem(target)
    return
  }
  const handle = target?.closest<HTMLElement>('[data-grid-drag],[data-grid-resize]')
  if (!handle) {
    selectClosestItem(target)
    return
  }
  const id = handle.dataset.gridDrag ?? handle.dataset.gridResize ?? ''
  const mode = handle.dataset.gridDrag != null ? 'move' : 'resize'
  const instance = props.items.find((candidate) => candidate.instanceId === id)
  if (!instance) return
  event.preventDefault()
  emit('select', id)
  drag.value = {
    id,
    mode,
    resizeEdge: mode === 'resize' ? resizeEdgeFor(handle) : undefined,
    origin: { ...instance.rect },
    startX: event.clientX,
    startY: event.clientY,
    candidate: null,
  }
  window.addEventListener('pointermove', onPointerMove)
  window.addEventListener('pointerup', onPointerUp)
  window.addEventListener('pointercancel', onPointerCancel)
}

function computeCandidate(event: PointerEvent): DashboardWidgetRect | null {
  const state = drag.value
  if (!state) return null
  const instance = props.items.find((candidate) => candidate.instanceId === state.id)
  const constraints = instance ? constraintsFor(instance.widgetKey) : null
  if (!instance || !constraints) return null
  const dcol = pixelsToGridUnits(event.clientX - state.startX, colWidth.value + props.gap)
  const drow = pixelsToGridUnits(event.clientY - state.startY, props.rowHeight + props.gap)
  if (state.mode === 'move') {
    return clampRect(
      {
        ...state.origin,
        x: state.origin.x + dcol,
        y: state.origin.y + drow,
      },
      constraints,
      props.columns,
    )
  }
  return resizeRectFromEdge(
    state.origin,
    dcol,
    drow,
    state.resizeEdge ?? 'right',
    constraints,
    props.columns,
  )
}

function onPointerMove(event: PointerEvent) {
  if (!drag.value) return
  drag.value.candidate = computeCandidate(event)
}

function onPointerUp() {
  const state = drag.value
  detachWindowListeners()
  drag.value = null
  if (!state) return
  if (state.candidate) emit('commitRect', state.id, state.candidate)
}

/** pointercancel（触控被系统接管等）丢弃候选矩形，不做任何提交。 */
function onPointerCancel() {
  detachWindowListeners()
  drag.value = null
}

function detachWindowListeners() {
  window.removeEventListener('pointermove', onPointerMove)
  window.removeEventListener('pointerup', onPointerUp)
  window.removeEventListener('pointercancel', onPointerCancel)
}

// 手势进行中编辑能力被关闭（退出编辑或进入窄屏）：主动丢弃候选并解绑监听，
// 与 pointercancel 同路径，绝不提交。
watch(
  () => [props.editing, props.narrow] as const,
  ([editing, narrow]) => {
    if (drag.value && (!editing || narrow)) onPointerCancel()
  },
)

const gridHeight = computed(() =>
  props.items.reduce(
    (max, item) => Math.max(max, (item.rect.y + item.rect.h) * (props.rowHeight + props.gap)),
    0,
  ),
)

function itemStyle(instance: DashboardWidgetInstance) {
  if (props.narrow) return undefined
  const rect =
    drag.value?.id === instance.instanceId && drag.value.candidate
      ? drag.value.candidate
      : instance.rect
  const { left, top, width, height } = rectToPixels(rect, colWidth.value, props.rowHeight, props.gap)
  return {
    position: 'absolute' as const,
    left: `${left}px`,
    top: `${top}px`,
    width: `${width}px`,
    height: `${height}px`,
    zIndex: drag.value?.id === instance.instanceId ? 3 : undefined,
  }
}
</script>

<template>
  <div
    ref="containerRef"
    class="dash-grid"
    :class="{ 'is-editing': editing, 'is-narrow': narrow }"
    :style="narrow ? undefined : { height: `${gridHeight}px` }"
    @pointerdown="onPointerDown"
  >
    <div
      v-for="instance in items"
      :key="instance.instanceId"
      class="dash-grid__item"
      :style="itemStyle(instance)"
    >
      <slot :instance="instance" />
    </div>
  </div>
</template>

<style scoped lang="scss">
.dash-grid {
  position: relative;
  min-height: 120px;
}

.dash-grid.is-editing {
  background-image:
    repeating-linear-gradient(90deg, var(--ops-grid-line) 0 1px, transparent 1px calc(100% / 12)),
    repeating-linear-gradient(0deg, var(--ops-grid-line-subtle) 0 1px, transparent 1px 66px);
  background-size: 100% 100%;
  user-select: none;
}

.dash-grid__item {
  min-width: 0;
}

.dash-grid.is-narrow .dash-grid__item {
  position: relative !important;
  inset: auto !important;
  width: 100% !important;
  height: auto !important;
  min-height: 0;
  margin-bottom: 12px;
}
</style>
