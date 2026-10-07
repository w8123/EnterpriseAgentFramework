<script setup lang="ts">
import { densityClass, type WorkbenchDensity } from './glassWorkbench'

const props = withDefaults(
  defineProps<{
    density?: WorkbenchDensity
    loading?: boolean
    empty?: boolean
    emptyDescription?: string
    currentPage?: number
    pageSize?: number
    total?: number
    pageSizes?: number[]
    paginationLayout?: string
  }>(),
  {
    density: 'comfortable',
    loading: false,
    empty: false,
    emptyDescription: '暂无数据',
    currentPage: 1,
    pageSize: 20,
    total: 0,
    pageSizes: () => [10, 20, 50, 100],
    paginationLayout: 'total, sizes, prev, pager, next, jumper',
  },
)

const emit = defineEmits<{
  'update:currentPage': [page: number]
  'update:pageSize': [size: number]
  pageChange: [page: number]
  sizeChange: [size: number]
}>()

function handlePageChange(page: number) {
  emit('update:currentPage', page)
  emit('pageChange', page)
}

function handleSizeChange(size: number) {
  emit('update:pageSize', size)
  emit('sizeChange', size)
}
</script>

<template>
  <section
    v-loading="props.loading"
    :class="[
      'data-table-shell',
      'glass-surface-panel',
      densityClass(props.density),
      { 'is-empty': props.empty && !props.loading },
    ]"
  >
    <div v-if="$slots.toolbar" class="data-table-shell__toolbar">
      <slot name="toolbar" />
    </div>

    <div class="data-table-shell__body">
      <slot />
    </div>

    <div v-if="props.empty && !props.loading" class="data-table-shell__empty">
      <slot name="empty">
        <el-empty :description="props.emptyDescription" />
      </slot>
    </div>

    <div
      v-if="$slots.pagination || props.total > 0"
      class="data-table-shell__pagination"
    >
      <slot name="pagination">
        <el-pagination
          :current-page="props.currentPage"
          :page-size="props.pageSize"
          :total="props.total"
          :page-sizes="props.pageSizes"
          :layout="props.paginationLayout"
          @update:current-page="handlePageChange"
          @update:page-size="handleSizeChange"
        />
      </slot>
    </div>
  </section>
</template>

<style scoped lang="scss">
.data-table-shell {
  display: flex;
  flex-direction: column;
  gap: var(--section-gap);
  min-width: 0;
  padding: var(--panel-padding);
  border-radius: var(--radius-lg);
  color: var(--text-primary);
}

.data-table-shell__toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: calc(var(--section-gap) / 2);
}

.data-table-shell__toolbar > :deep(.filter-bar) {
  flex: 1 1 100%;
}

.data-table-shell__body {
  min-width: 0;
  overflow-x: auto;
}

.data-table-shell__empty {
  display: grid;
  flex: 1 0 auto;
  place-items: center;
  border-top: 1px solid var(--border-divider);
  color: var(--text-muted);
}

.data-table-shell__pagination {
  display: flex;
  justify-content: flex-end;
  padding-top: calc(var(--section-gap) / 2);
  border-top: 1px solid var(--border-divider);
}

@media (max-width: 720px) {
  .data-table-shell__pagination {
    justify-content: flex-start;
    overflow-x: auto;
  }
}
</style>
