<script setup lang="ts">
import MetricIconBg from './MetricIconBg.vue'
import { densityClass, type MetricStripItem, type WorkbenchDensity } from './glassWorkbench'

const props = withDefaults(
  defineProps<{
    items: MetricStripItem[]
    density?: WorkbenchDensity
    ariaLabel?: string
  }>(),
  {
    density: 'comfortable',
    ariaLabel: '指标概览',
  },
)
</script>

<template>
  <section
    :class="['metric-strip', 'glass-surface-panel', densityClass(props.density)]"
    role="list"
    :aria-label="props.ariaLabel"
  >
    <article
      v-for="item in props.items"
      :key="item.key"
      class="metric-strip__item"
      role="listitem"
    >
      <MetricIconBg :icon-key="item.iconKey" :tone="item.tone ?? 'brand'" />
      <div class="metric-strip__content">
        <span class="metric-strip__label">{{ item.label }}</span>
        <strong class="metric-strip__value">{{ item.value }}</strong>
        <small v-if="item.hint" class="metric-strip__hint">{{ item.hint }}</small>
      </div>
    </article>
  </section>
</template>

<style scoped lang="scss">
.metric-strip {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(12rem, 1fr));
  gap: var(--section-gap);
  padding: var(--panel-padding);
  border-radius: var(--radius-lg);
}

.metric-strip__item {
  display: flex;
  align-items: center;
  min-width: 0;
  gap: calc(var(--section-gap) / 2);
}

.metric-strip__item + .metric-strip__item {
  border-inline-start: 1px solid var(--border-divider);
  padding-inline-start: var(--section-gap);
}

.metric-strip__content {
  display: grid;
  min-width: 0;
  gap: 0.2rem;
}

.metric-strip__label,
.metric-strip__hint {
  overflow: hidden;
  color: var(--text-muted);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.metric-strip__label {
  font-size: 0.8rem;
}

.metric-strip__value {
  overflow: hidden;
  color: var(--text-primary);
  font-size: 1.45rem;
  line-height: 1.15;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.metric-strip__hint {
  font-size: 0.75rem;
}

@media (max-width: 720px) {
  .metric-strip {
    grid-template-columns: minmax(0, 1fr);
  }

  .metric-strip__item + .metric-strip__item {
    border-block-start: 1px solid var(--border-divider);
    border-inline-start: 0;
    padding-block-start: var(--section-gap);
    padding-inline-start: 0;
  }
}
</style>
