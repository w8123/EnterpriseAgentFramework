<script setup lang="ts">
import { densityClass, type WorkbenchDensity } from './glassWorkbench'

const props = withDefaults(
  defineProps<{
    density?: WorkbenchDensity
    loading?: boolean
    showReset?: boolean
    queryLabel?: string
    resetLabel?: string
  }>(),
  {
    density: 'comfortable',
    loading: false,
    showReset: true,
    queryLabel: '查询',
    resetLabel: '重置',
  },
)

const emit = defineEmits<{
  query: []
  reset: []
}>()
</script>

<template>
  <form
    :class="['filter-bar', 'glass-surface-control', densityClass(props.density)]"
    novalidate
    @submit.prevent="emit('query')"
  >
    <div class="filter-bar__fields">
      <slot />
    </div>

    <div class="filter-bar__actions">
      <slot name="actions">
        <el-button
          v-if="props.showReset"
          native-type="button"
          @click="emit('reset')"
        >
          {{ props.resetLabel }}
        </el-button>
        <el-button type="primary" native-type="submit" :loading="props.loading">
          {{ props.queryLabel }}
        </el-button>
      </slot>
    </div>
  </form>
</template>

<style scoped lang="scss">
.filter-bar {
  display: flex;
  align-items: flex-end;
  gap: var(--section-gap);
  min-width: 0;
  padding: var(--panel-padding);
  border-radius: var(--radius-lg);
  color: var(--text-primary);
}

.filter-bar__fields {
  display: flex;
  flex: 1;
  flex-wrap: wrap;
  align-items: flex-end;
  gap: calc(var(--section-gap) / 2);
  min-width: 0;
}

.filter-bar__actions {
  display: flex;
  flex: 0 0 auto;
  flex-wrap: wrap;
  align-items: center;
  justify-content: flex-end;
  gap: calc(var(--section-gap) / 2);
}

@media (max-width: 720px) {
  .filter-bar {
    align-items: stretch;
    flex-direction: column;
  }

  .filter-bar__actions {
    justify-content: flex-start;
  }
}
</style>
