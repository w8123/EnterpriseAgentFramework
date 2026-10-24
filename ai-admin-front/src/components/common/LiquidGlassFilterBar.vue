<script setup lang="ts">
import { RefreshLeft, Search } from '@element-plus/icons-vue'
import FilterBar from './FilterBar.vue'
import LiquidGlassButton from './LiquidGlassButton.vue'

withDefaults(defineProps<{ loading?: boolean; showReset?: boolean; queryLabel?: string; density?: 'comfortable' | 'compact' | 'spacious' }>(), {
  loading: false, showReset: true, queryLabel: '搜索',
})
const emit = defineEmits<{ query: []; reset: [] }>()
</script>

<template>
  <FilterBar
    class="liquid-glass-filter-bar"
    presentation="inline"
    :loading="loading"
    @query="emit('query')"
    @reset="emit('reset')"
  >
    <slot />
    <template #actions>
      <slot name="actions">
        <el-tooltip v-if="showReset" content="重置筛选" placement="top">
          <LiquidGlassButton circle :icon="RefreshLeft" native-type="button" aria-label="重置筛选" @click="emit('reset')" />
        </el-tooltip>
        <LiquidGlassButton type="primary" native-type="submit" :icon="Search" :loading="loading">{{ queryLabel }}</LiquidGlassButton>
      </slot>
    </template>
  </FilterBar>
</template>

<style scoped lang="scss">
/* 筛选栏只负责排列和高度；圆角、背景、边框与状态交给 LiquidGlassControl。 */
.liquid-glass-filter-bar {
  --control-height: var(--project-list-control-height, 40px);
  width: 100%;
  align-items: center;
  gap: var(--layout-control-gap);
}

.liquid-glass-filter-bar :deep(.filter-bar__fields) {
  align-items: center;
  gap: var(--layout-control-gap);
}

.liquid-glass-filter-bar :deep(.filter-bar__fields > .liquid-glass-control) {
  flex: 1 1 160px;
  min-width: 0;
  min-height: var(--control-height);
}

.liquid-glass-filter-bar :deep(.filter-bar__fields > .liquid-glass-input) {
  flex: 3 1 280px;
}

.liquid-glass-filter-bar :deep(.el-input),
.liquid-glass-filter-bar :deep(.el-select) {
  width: 100%;
  min-width: 0;
  height: var(--control-height);
}

.liquid-glass-filter-bar :deep(.filter-bar__actions) {
  display: flex;
  align-items: center;
  gap: var(--layout-control-gap);
}

@media (max-width: 720px) {
  .liquid-glass-filter-bar {
    align-items: stretch;
  }

  .liquid-glass-filter-bar :deep(.filter-bar__fields) {
    flex: 0 0 auto;
  }

  .liquid-glass-filter-bar :deep(.filter-bar__fields > .liquid-glass-input) {
    flex-basis: 100%;
  }

  .liquid-glass-filter-bar :deep(.filter-bar__actions) {
    justify-content: flex-end;
  }
}
</style>
