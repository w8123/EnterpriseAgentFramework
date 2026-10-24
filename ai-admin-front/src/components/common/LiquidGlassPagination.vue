<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import LiquidGlassLayer from './LiquidGlassLayer.vue'
import LiquidGlassSelect from './LiquidGlassSelect.vue'
import { useLiquidGlassSource, type LiquidGlassSource } from './liquidGlassContext'

const props = withDefaults(defineProps<{
  total: number
  pageSizes?: number[]
  source?: LiquidGlassSource
  ariaLabel?: string
  disabled?: boolean
}>(), { pageSizes: () => [5, 10, 20, 50], ariaLabel: '分页', disabled: false })
const source = useLiquidGlassSource(() => props.source)
const emit = defineEmits<{
  currentChange: [page: number]
  sizeChange: [size: number]
  change: [page: number, pageSize: number]
}>()
const currentPage = defineModel<number>('currentPage', { default: 1 })
const pageSize = defineModel<number>('pageSize', { default: 10 })
const dock = ref<HTMLElement | null>(null)
const lens = ref<Record<string, string> | null>(null)
let frame = 0
let observer: ResizeObserver | undefined
let changePending = false

function queueChange() {
  if (changePending) return
  changePending = true
  nextTick(() => {
    changePending = false
    emit('change', currentPage.value, pageSize.value)
  })
}

function changePage(page: number) {
  if (page === currentPage.value) return
  currentPage.value = page
  emit('currentChange', page)
  queueChange()
}

function changeSize(size: number) {
  if (size === pageSize.value) return
  pageSize.value = size
  currentPage.value = 1
  emit('sizeChange', size)
  queueChange()
}

function measureSelection() {
  frame = 0
  const host = dock.value
  const active = host?.querySelector<HTMLElement>('.el-pager li.is-active')
  if (!host || !active) {
    lens.value = null
    return
  }
  const hostBounds = host.getBoundingClientRect()
  const bounds = active.getBoundingClientRect()
  lens.value = {
    width: `${bounds.width}px`,
    height: `${bounds.height}px`,
    transform: `translate(${bounds.left - hostBounds.left - host.clientLeft}px, ${bounds.top - hostBounds.top - host.clientTop}px)`,
  }
}

function scheduleSelection() {
  nextTick(() => {
    if (!frame && dock.value) frame = requestAnimationFrame(measureSelection)
  })
}

watch([currentPage, pageSize, () => props.total], scheduleSelection)
onMounted(() => {
  observer = new ResizeObserver(scheduleSelection)
  if (dock.value) observer.observe(dock.value)
  scheduleSelection()
})
onBeforeUnmount(() => {
  observer?.disconnect()
  if (frame) cancelAnimationFrame(frame)
})
</script>

<template>
  <nav class="liquid-pagination" :class="{ 'glass-workspace-adaptive': source === 'wallpaper' }" :aria-label="props.ariaLabel">
    <div ref="dock" class="liquid-pagination__dock glass-surface-lens liquid-glass-host" :class="{ 'samples-wallpaper': source === 'wallpaper' }">
      <LiquidGlassLayer :source="source" :bezel="7" :depth="12" />
      <span v-if="lens" class="liquid-pagination__selection glass-surface-lens glass-surface-selection" :style="lens" aria-hidden="true" />
      <el-pagination
        :current-page="currentPage"
        :page-size="pageSize"
        :disabled="props.disabled"
        :total="props.total"
        :pager-count="5"
        layout="prev, pager, next"
        @update:current-page="changePage"
      />
    </div>
    <LiquidGlassSelect
      :model-value="pageSize"
      class="liquid-pagination__size"
      :source="source"
      :disabled="props.disabled"
      dropdown-title="每页显示"
      aria-label="每页条数"
      @update:model-value="changeSize"
    >
      <el-option v-for="size in props.pageSizes" :key="size" :value="size" :label="`${size}条/页`" />
    </LiquidGlassSelect>
  </nav>
</template>

<style scoped lang="scss">
.liquid-pagination {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  flex-wrap: wrap;
  gap: 10px;
  min-width: 0;
  max-width: 100%;
}

.liquid-pagination__dock {
  --glass-tint: var(--surface-glass-lens);
  --surface-glass-selected: var(--surface-solid-control);
  --pagination-ink: var(--text-primary);
  position: relative;
  display: inline-flex;
  align-items: center;
  min-width: 0;
  padding: 4px;
  border-radius: var(--glass-control-radius);

  &.is-liquid-refracting.samples-wallpaper {
    --pagination-ink: var(--text-on-wallpaper);
  }
}

.liquid-pagination__selection {
  --surface-glass-selected: var(--surface-glass-control);
  position: absolute;
  top: 0;
  left: 0;
  border-radius: var(--glass-control-radius);
  pointer-events: none;
  transition: transform var(--motion-duration-normal) var(--motion-easing-emphasized), width var(--motion-duration-normal) ease;

  &::before {
    background: var(--surface-glass-inset);
  }
}

.liquid-pagination__dock :deep(.el-pagination) {
  --el-pagination-button-width: 30px;
  --el-pagination-button-height: 30px;
  --el-pagination-bg-color: transparent;
  --el-pagination-button-bg-color: transparent;
  position: relative;
  z-index: 1;
  gap: 3px;
}

.liquid-pagination__dock :deep(.el-pager) {
  gap: 3px;
}

.liquid-pagination__dock :deep(.btn-prev),
.liquid-pagination__dock :deep(.btn-next),
.liquid-pagination__dock :deep(.el-pager li) {
  min-width: 30px;
  height: 30px;
  margin: 0;
  padding: 0 6px;
  border: 0;
  border-radius: var(--glass-control-radius);
  color: var(--pagination-ink);
  background: transparent;
  font-size: 13px;
  font-weight: 500;
  font-variant-numeric: tabular-nums;
}

.liquid-pagination__dock :deep(.el-pager li.is-active) {
  color: var(--text-primary);
  font-weight: 650;
}

.liquid-pagination__dock :deep(button:disabled) {
  color: var(--pagination-ink);
  background: transparent;
  opacity: 0.35;
}

.liquid-pagination__dock :deep(.el-pager li:not(.is-active):hover),
.liquid-pagination__dock :deep(button:not(:disabled):hover) {
  background: var(--surface-glass-inset);
}

.liquid-pagination__dock :deep(.el-pager li:focus-visible),
.liquid-pagination__dock :deep(button:focus-visible) {
  outline: 2px solid var(--border-focus);
  outline-offset: 1px;
}

.liquid-pagination__size {
  flex: 0 0 122px;
  width: 122px;
}

@media (max-width: 600px) {
  .liquid-pagination__dock :deep(.el-pager li:not(.is-active)) {
    display: none;
  }
}

@media (prefers-reduced-motion: reduce) {
  .liquid-pagination__selection {
    transition: none;
  }
}

@media (forced-colors: active) {
  .liquid-pagination__dock {
    --pagination-ink: CanvasText;
  }

  .liquid-pagination__dock :deep(.el-pager li.is-active) {
    color: HighlightText;
    background: Highlight;
  }
}
</style>
