<script setup lang="ts">
import { Search } from '@element-plus/icons-vue'
import DataTableShell from '@/components/common/DataTableShell.vue'
import FilterBar from '@/components/common/FilterBar.vue'

defineProps<{
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
</script>

<template>
  <DataTableShell
    class="business-capability-list project-list-table-shell project-list-table-shell--fit project-list-table-shell--catalog workbench-list-surface"
    density="compact"
    :loading="loading"
    :empty="empty"
    preserve-empty-body
    :current-page="currentPage"
    :page-size="pageSize"
    :total="total"
    :page-sizes="[10, 20, 50, 100]"
    @page-change="emit('pageChange', $event)"
    @size-change="emit('sizeChange', $event)"
  >
    <template #toolbar>
      <FilterBar
        class="business-capability-filter project-list-filter-bar"
        density="compact"
        presentation="panel"
        :loading="loading"
        @query="emit('query')"
      >
        <slot name="filters" />
        <template #actions>
          <el-button native-type="submit" type="primary" :icon="Search" :loading="loading" :disabled="composing">
            搜索
          </el-button>
        </template>
      </FilterBar>
    </template>

    <slot />

    <template v-if="total > 0" #pagination>
      <el-pagination
        background
        :current-page="currentPage"
        :page-size="pageSize"
        :total="total"
        :page-sizes="[10, 20, 50, 100]"
        layout="total, prev, pager, next, sizes"
        @update:current-page="emit('pageChange', $event)"
        @update:page-size="emit('sizeChange', $event)"
      />
    </template>

    <template #empty>
      <div class="catalog-empty-state">
        <el-empty :description="emptyDescription" :image-size="84" />
        <p v-if="!hasFilters">{{ emptyHint }}</p>
        <div v-if="!hasFilters" class="catalog-empty-state__actions">
          <slot name="empty-actions" />
        </div>
      </div>
    </template>
  </DataTableShell>
</template>

<style scoped lang="scss">
:deep(.catalog-row-actions) { display: inline-flex; align-items: center; gap: 6px; }
:deep(.catalog-row-actions .el-button + .el-button) { margin-left: 0; }
.business-capability-list :deep(th.el-table__cell) {
  color: var(--text-secondary);
  background: color-mix(in srgb, var(--brand-primary) 4%, var(--surface-solid-control)) !important;
  font-weight: 600;
}
.business-capability-list :deep(td.el-table__cell) { padding-block: 14px; }
.business-capability-list :deep(.el-table .cell) { padding-inline: 16px; }
.business-capability-list :deep(.el-table__row:hover > td.el-table__cell),
.business-capability-list :deep(.el-table__row:focus-within > td.el-table__cell),
.business-capability-list :deep(.el-table__body tr.hover-row > td.el-table__cell) {
  background: color-mix(in srgb, var(--brand-primary) 5%, var(--surface-solid-panel)) !important;
}
.business-capability-list :deep(th.el-table-fixed-column--right) {
  background: color-mix(in srgb, var(--brand-primary) 4%, var(--surface-fallback-panel)) !important;
}
.business-capability-list :deep(td.el-table-fixed-column--right) { background: var(--surface-fallback-panel) !important; }
.business-capability-list :deep(.el-table__row:hover > td.el-table-fixed-column--right),
.business-capability-list :deep(.el-table__row:focus-within > td.el-table-fixed-column--right),
.business-capability-list :deep(.el-table__body tr.hover-row > td.el-table-fixed-column--right) {
  background: color-mix(in srgb, var(--brand-primary) 5%, var(--surface-fallback-panel)) !important;
}
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
  color: var(--brand-active);
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
  color: var(--brand-active);
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
:deep(.catalog-row-action:hover), :deep(.catalog-row-action.el-button:hover) { border-color: var(--brand-primary); background: var(--brand-soft-bg); color: var(--brand-active); }
:deep(.catalog-row-action:active), :deep(.catalog-row-action.el-button:active) { background: color-mix(in srgb, var(--brand-primary) 14%, var(--surface-solid-control)); }
.business-capability-filter { width: 100%; }
.business-capability-filter :deep(.filter-bar__fields) { flex-wrap: nowrap; }
.business-capability-filter :deep(.filter-control) { min-width: 0; width: min(140px, 100%); }
.business-capability-filter :deep(.filter-control.is-keyword) { flex: 1 1 360px; width: min(360px, 100%); }
.business-capability-filter :deep(.filter-control.is-project) { flex: 0 1 180px; width: min(180px, 100%); }
.business-capability-filter :deep(.filter-control.is-classification) { flex: 0 1 200px; width: min(200px, 100%); }
.catalog-empty-state { display: grid; justify-items: center; padding: 28px; color: var(--text-muted); text-align: center; }
.catalog-empty-state > p { margin: -12px 0 16px; font-size: 13px; line-height: 1.6; }
.catalog-empty-state__actions { display: flex; flex-wrap: wrap; justify-content: center; gap: 8px; }
@media (max-width: 960px) {
  .business-capability-filter :deep(.filter-bar__fields) { flex-wrap: wrap; }
}
@media (max-width: 760px) {
  .business-capability-filter :deep(.filter-control),
  .business-capability-filter :deep(.filter-control.is-project),
  .business-capability-filter :deep(.filter-control.is-classification),
  .business-capability-filter :deep(.filter-control.is-keyword) { flex: 0 0 auto; width: 100%; }
  .catalog-empty-state { padding: 0; }
  .catalog-empty-state__actions { width: 100%; flex-direction: column; }
  .catalog-empty-state__actions :deep(.el-button) { margin: 0; }
}
</style>
