<script setup lang="ts">
import { useAttrs } from 'vue'
import DataTableShell from './DataTableShell.vue'
import LiquidGlassPagination from './LiquidGlassPagination.vue'
import { nativeControlAttrs, provideLiquidGlassSource, useLiquidGlassSource, type LiquidGlassSource } from './liquidGlassContext'

defineOptions({ inheritAttrs: false })
const props = withDefaults(defineProps<{
  total?: number
  pageSizes?: number[]
  source?: LiquidGlassSource
  paginationLabel?: string
  showPagination?: boolean
  showEmptyPagination?: boolean
  bodyLayout?: 'table' | 'cards'
  presentation?: 'panel' | 'collection'
}>(), { total: 0, pageSizes: () => [10, 20, 50, 100], source: 'wallpaper', paginationLabel: '分页', showPagination: true, showEmptyPagination: false, bodyLayout: 'table', presentation: 'panel' })
const currentPage = defineModel<number>('currentPage', { default: 1 })
const pageSize = defineModel<number>('pageSize', { default: 20 })
const emit = defineEmits<{ change: [page: number, pageSize: number] }>()
const source = useLiquidGlassSource(() => props.source)
provideLiquidGlassSource(source)
const attrs = useAttrs()
</script>

<template>
  <DataTableShell
    v-bind="nativeControlAttrs(attrs)"
    :surface="props.presentation === 'collection' ? 'none' : 'panel'"
    :class="['liquid-glass-table', { 'glass-surface-frost': props.presentation === 'panel', 'glass-workspace-adaptive': source === 'wallpaper', 'liquid-glass-table--cards': props.bodyLayout === 'cards', 'liquid-glass-table--collection': props.presentation === 'collection' }, $attrs.class]"
    :style="$attrs.style"
    :total="0"
  >
    <template v-if="$slots.toolbar" #toolbar><slot name="toolbar" /></template>
    <slot />
    <template v-if="$slots.empty" #empty><slot name="empty" /></template>
    <template v-if="props.showPagination && (props.total > 0 || props.showEmptyPagination || $slots.pagination)" #pagination>
      <slot name="pagination">
        <span class="liquid-glass-table__total">共 {{ props.total }} 条</span>
        <LiquidGlassPagination
          v-model:current-page="currentPage"
          v-model:page-size="pageSize"
          :total="props.total"
          :page-sizes="props.pageSizes"
          :source="source"
          :aria-label="props.paginationLabel"
          :disabled="Boolean($attrs.loading)"
          @change="(page, size) => emit('change', page, size)"
        />
      </slot>
    </template>
  </DataTableShell>
</template>

<style scoped lang="scss">
.liquid-glass-table.data-table-shell {
  --liquid-table-sticky: var(--surface-reading-sticky);
  --el-text-color-primary: var(--text-primary);
  --el-text-color-regular: var(--text-secondary);
  --el-text-color-secondary: var(--text-muted);
  --el-color-primary: var(--brand-on-surface);
  --el-color-success: var(--status-success);
  --el-color-warning: var(--status-warning);
  --el-color-danger: var(--status-danger);
  --el-color-info: var(--status-info);
  flex: 1;
  gap: 0;
  min-height: 0;
  padding: 0;
  overflow: hidden;
  border-radius: var(--radius-xl);
}

@supports (color: light-dark(Canvas, CanvasText)) {
  .liquid-glass-table.glass-workspace-adaptive {
    // Floating cells cover scrolling text; the local color scheme also covers dark wallpapers.
    --liquid-table-sticky: light-dark(color-mix(in srgb, var(--wallpaper-fallback) 72%, var(--surface-fallback-panel)), var(--surface-fallback-panel));
  }
}

.liquid-glass-table :deep(.data-table-shell__toolbar) {
  display: block;
  padding: var(--layout-panel-padding);
}

.liquid-glass-table :deep(.data-table-shell__body) {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-height: 0;
  overflow: hidden;
}

.liquid-glass-table--cards :deep(.data-table-shell__body) {
  display: block;
  overflow: auto;
  padding: 0 var(--layout-panel-padding) var(--layout-panel-padding);
}

.liquid-glass-table.is-empty:not(.data-table-shell--preserve-empty-body) :deep(.data-table-shell__body) {
  display: none;
}

.liquid-glass-table :deep(.data-table-shell__empty) {
  min-height: 0;
  overflow: auto;
  padding: var(--layout-panel-padding);
  border: 0;
}

.liquid-glass-table :deep(.data-table-shell__pagination) {
  flex-shrink: 0;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: var(--layout-control-gap);
  margin-top: auto;
  padding: var(--layout-pagination-padding-block) var(--layout-panel-padding);
  border: 0;
  color: var(--text-secondary);
  font-size: 12px;
}

.liquid-glass-table :deep(.el-table) {
  --el-table-bg-color: transparent;
  --el-table-tr-bg-color: transparent;
  --el-table-header-bg-color: var(--surface-reading-header);
  --el-table-row-hover-bg-color: var(--surface-reading-hover);
  --el-table-current-row-bg-color: var(--surface-reading-current);
  --el-table-border-color: var(--border-divider);
  border-radius: 0;
  background: transparent;
  box-shadow: none;
  -webkit-backdrop-filter: none;
  backdrop-filter: none;
}

.liquid-glass-table :deep(.data-table-shell__body > .el-table) {
  flex: 1;
  height: 100%;
  min-height: 0;
}

.liquid-glass-table :deep(.el-table__inner-wrapper::before) {
  display: none;
}

.liquid-glass-table :deep(th.el-table__cell) {
  height: 40px;
  color: var(--text-secondary);
  background: var(--surface-reading-header);
  font-size: 12px;
  font-weight: 600;
}

.liquid-glass-table :deep(td.el-table__cell) {
  height: var(--row-height);
  color: var(--text-secondary);
  border-bottom-color: var(--border-divider);
}

.liquid-glass-table :deep(td.el-table-fixed-column--left),
.liquid-glass-table :deep(td.el-table-fixed-column--right),
.liquid-glass-table :deep(th.el-table-fixed-column--left),
.liquid-glass-table :deep(th.el-table-fixed-column--right) {
  background: var(--liquid-table-sticky);
}

.liquid-glass-table :deep(.el-table:is(.is-scrolling-none, .is-scrolling-left) td.el-table-fixed-column--left),
.liquid-glass-table :deep(.el-table:is(.is-scrolling-none, .is-scrolling-right) td.el-table-fixed-column--right) {
  background: transparent;
}

.liquid-glass-table :deep(.el-table:is(.is-scrolling-none, .is-scrolling-left) th.el-table-fixed-column--left),
.liquid-glass-table :deep(.el-table:is(.is-scrolling-none, .is-scrolling-right) th.el-table-fixed-column--right) {
  background: var(--surface-reading-header);
}

.liquid-glass-table :deep(.el-table__body tr:hover > td.el-table__cell),
.liquid-glass-table :deep(.el-table__body tr.hover-row > td.el-table__cell) {
  background: var(--surface-reading-hover);
}

.liquid-glass-table :deep(.el-table__body tr:hover > td.el-table-fixed-column--left),
.liquid-glass-table :deep(.el-table__body tr:hover > td.el-table-fixed-column--right),
.liquid-glass-table :deep(.el-table__body tr.hover-row > td.el-table-fixed-column--left),
.liquid-glass-table :deep(.el-table__body tr.hover-row > td.el-table-fixed-column--right) {
  background: color-mix(in srgb, var(--brand-primary) 10%, var(--liquid-table-sticky));
}

.liquid-glass-table :deep(.el-table .cell) {
  padding-inline: var(--layout-panel-padding);
}

.liquid-glass-table__total {
  white-space: nowrap;
}

// Collections already have individual reading cards; keep the surrounding workspace open.
.liquid-glass-table--collection.data-table-shell { border-radius: 0; }
.liquid-glass-table--collection :deep(.data-table-shell__toolbar) { padding: 2px 2px 16px; }
.liquid-glass-table--collection :deep(.data-table-shell__body),
.liquid-glass-table--collection :deep(.data-table-shell__empty) { padding: 2px 2px 12px; }
.liquid-glass-table--collection :deep(.data-table-shell__pagination) { padding: 12px 2px 2px; }
.liquid-glass-table--collection.glass-workspace-adaptive :deep(.data-table-shell__pagination) { color: var(--text-on-wallpaper-secondary); }

@media (max-width: 760px) {
  // Fixed identity and action columns can together exceed the entire viewport.
  // Keep the native horizontal scroll, with all columns in normal table order.
  .liquid-glass-table :deep(.el-table-fixed-column--left),
  .liquid-glass-table :deep(.el-table-fixed-column--right) {
    position: static !important;
    left: auto !important;
    right: auto !important;
  }

  .liquid-glass-table :deep(td.el-table-fixed-column--left),
  .liquid-glass-table :deep(td.el-table-fixed-column--right) {
    background: transparent !important;
  }

  .liquid-glass-table :deep(th.el-table-fixed-column--left),
  .liquid-glass-table :deep(th.el-table-fixed-column--right) {
    background: var(--surface-reading-header) !important;
  }

  .liquid-glass-table :deep(.el-table-fixed-column--left::before),
  .liquid-glass-table :deep(.el-table-fixed-column--right::before) {
    display: none;
  }

  .liquid-glass-table :deep(.data-table-shell__pagination) {
    flex-direction: column;
    align-items: flex-end;
    overflow: visible;
  }

  .liquid-glass-table__total {
    align-self: flex-start;
  }
}
</style>
