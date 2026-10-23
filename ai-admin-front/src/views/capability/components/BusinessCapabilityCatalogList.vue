<script setup lang="ts">
import { Search } from '@element-plus/icons-vue'
import { LiquidGlassTable, LiquidGlassFilterBar, LiquidGlassButton, LiquidGlassEmptyState } from '@/components/common/liquid-glass'

const props = defineProps<{
  loading: boolean
  composing: boolean
  currentPage: number
  pageSize: number
  total: number
  empty: boolean
  hasFilters: boolean
  emptyDescription: string
  emptyHint: string
}>()

const emit = defineEmits<{
  query: []
  pageChange: [page: number]
  sizeChange: [size: number]
}>()
function changePagination(page: number, size: number) {
  if (size !== props.pageSize) emit('sizeChange', size)
  else if (page !== props.currentPage) emit('pageChange', page)
}
</script>

<template>
  <LiquidGlassTable
    class="business-capability-list workbench-list-surface"
    density="compact"
    :loading="loading"
    :empty="empty"
    preserve-empty-body
    :current-page="currentPage"
    :page-size="pageSize"
    :total="total"
    :page-sizes="[10, 20, 50, 100]"
    @change="changePagination"
  >
    <template #toolbar>
      <LiquidGlassFilterBar
        class="business-capability-filter project-list-filter-bar"
        density="compact"
        :show-reset="false"
        :loading="loading"
        @query="emit('query')"
      >
        <slot name="filters" />
        <template #actions>
          <LiquidGlassButton native-type="submit" type="primary" :icon="Search" :loading="loading" :disabled="composing">
            搜索
          </LiquidGlassButton>
        </template>
      </LiquidGlassFilterBar>
    </template>

    <slot />

    <template #empty>
      <LiquidGlassEmptyState class="catalog-empty-state" :title="emptyDescription" :description="!hasFilters ? emptyHint : undefined">
        <slot v-if="!hasFilters" name="empty-actions" />
      </LiquidGlassEmptyState>
    </template>
  </LiquidGlassTable>
</template>

<style scoped lang="scss">
:deep(.catalog-row-actions) { display: inline-flex; align-items: center; gap: 6px; }
:deep(.catalog-row-actions .el-button + .el-button) { margin-left: 0; }
.business-capability-list :deep(td.el-table__cell) { padding-block: 14px; }
:deep(.catalog-asset-cell) { display: flex; align-items: flex-start; gap: 12px; min-width: 0; }
:deep(.catalog-asset-icon) {
  display: grid;
  place-items: center;
  flex: 0 0 36px;
  height: 36px;
  margin-top: 2px;
  border: 1px solid color-mix(in srgb, var(--brand-primary) 16%, var(--border-divider));
  border-radius: 10px;
  background: var(--brand-soft-bg);
  color: var(--brand-on-surface);
  font: 600 21px/1 var(--font-family-mono, ui-monospace, Consolas, monospace);
}
:deep(.catalog-asset-heading) { display: flex; flex-wrap: wrap; align-items: center; gap: 6px 10px; min-width: 0; }
:deep(.catalog-asset-description) { color: var(--text-secondary); font-size: 13px; line-height: 1.6; overflow-wrap: anywhere; }
:deep(.catalog-category-cell) { display: flex; flex-wrap: wrap; align-items: center; gap: 6px; min-width: 0; }
:deep(.catalog-category-link.el-button) {
  min-width: 0;
  max-width: 100%;
  height: auto;
  margin: 0;
  padding: 5px 9px;
  border: 1px solid color-mix(in srgb, var(--brand-primary) 14%, var(--border-divider));
  border-radius: var(--radius-sm);
  background: color-mix(in srgb, var(--brand-primary) 5%, var(--surface-solid-control));
  color: var(--brand-on-surface);
  font-size: 12px;
  font-weight: 500;
  line-height: 1.5;
}
:deep(.catalog-category-link span) { white-space: normal; overflow-wrap: anywhere; text-align: left; }
:deep(.catalog-category-link.el-button:not(:disabled):hover) { background: var(--brand-soft-bg); border-color: var(--brand-primary); }
:deep(.catalog-category-link.el-button:not(:disabled):active) { background: color-mix(in srgb, var(--brand-primary) 14%, var(--surface-solid-control)); }
:deep(.catalog-category-link:focus-visible), :deep(.catalog-row-action:focus-visible) { outline: 2px solid var(--border-focus); outline-offset: 2px; }
:deep(.catalog-category-link:disabled) { opacity: 0.55; cursor: not-allowed; }
:deep(.catalog-category-empty) { color: var(--text-muted); font-size: 12px; }
:deep(.catalog-side-effect.status-tag--success) {
  border: 1px solid var(--border-divider);
  background: var(--surface-solid-control);
  color: var(--text-secondary);
}
:deep(.catalog-method-status .status-tag--success) {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  height: auto;
  padding: 0;
  border: 0;
  background: transparent;
  color: color-mix(in srgb, var(--status-success) 72%, var(--text-primary));
  font-size: 12px;
  line-height: 22px;
}
:deep(.catalog-method-status .status-tag--success::before) { content: ''; width: 6px; height: 6px; flex: 0 0 auto; border-radius: 50%; background: var(--status-success); }
:deep(.catalog-row-action), :deep(.catalog-row-action.el-button) {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  min-height: 32px;
  margin: 0;
  padding: 5px 9px;
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-sm);
  background: var(--surface-solid-control);
  color: var(--text-secondary);
  font-size: 12px;
  font-weight: 500;
  line-height: 20px;
  text-decoration: none;
  cursor: pointer;
}
:deep(.catalog-row-action .el-icon) { margin-left: 5px; font-size: 12px; }
:deep(.catalog-row-action:hover), :deep(.catalog-row-action.el-button:hover) { border-color: var(--brand-primary); background: var(--brand-soft-bg); color: var(--brand-on-surface); }
:deep(.catalog-row-action:active), :deep(.catalog-row-action.el-button:active) { background: color-mix(in srgb, var(--brand-primary) 14%, var(--surface-solid-control)); }
.business-capability-filter { width: 100%; }
.business-capability-filter :deep(.filter-bar__fields) { flex-wrap: nowrap; }
.business-capability-filter :deep(.filter-control) { min-width: 0; width: min(140px, 100%); }
.business-capability-filter :deep(.filter-control.is-keyword) { flex: 1 1 360px; width: min(360px, 100%); }
.business-capability-filter :deep(.filter-control.is-project) { flex: 0 1 180px; width: min(180px, 100%); }
.business-capability-filter :deep(.filter-control.is-classification) { flex: 0 1 200px; width: min(200px, 100%); }
@media (max-width: 960px) {
  .business-capability-filter :deep(.filter-bar__fields) { flex-wrap: wrap; }
}
@media (max-width: 760px) {
  .business-capability-filter :deep(.filter-control),
  .business-capability-filter :deep(.filter-control.is-project),
  .business-capability-filter :deep(.filter-control.is-classification),
  .business-capability-filter :deep(.filter-control.is-keyword) { flex: 0 0 auto; width: 100%; }

}
</style>
