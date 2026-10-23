<script setup lang="ts">
import MetricIconBg from './MetricIconBg.vue'
import { densityClass, type MetricStripItem, type WorkbenchDensity } from './glassWorkbench'

const props = withDefaults(
  defineProps<{
    items: MetricStripItem[]
    density?: WorkbenchDensity
    presentation?: 'panel' | 'inline'
    source?: 'backdrop' | 'wallpaper'
    ariaLabel?: string
  }>(),
  {
    density: 'comfortable',
    presentation: 'panel',
    source: 'backdrop',
    ariaLabel: '指标概览',
  },
)
</script>

<template>
  <section
    :class="['metric-strip', 'glass-surface-frost', densityClass(props.density), { 'samples-wallpaper': props.source === 'wallpaper' }]"
    :data-presentation="props.presentation"
    role="list"
    :aria-label="props.ariaLabel"
  >
    <article
      v-for="item in props.items"
      :key="item.key"
      class="metric-strip__item"
      role="listitem"
      :title="props.presentation === 'inline' ? item.hint : undefined"
    >
      <MetricIconBg v-if="props.presentation !== 'inline'" :icon-key="item.iconKey" :tone="item.tone ?? 'brand'" />
      <div class="metric-strip__content">
        <span class="metric-strip__label">{{ item.label }}</span>
        <strong class="metric-strip__value">{{ item.value }}</strong>
        <small v-if="item.hint" class="metric-strip__hint"
          :class="{ 'metric-strip__hint--assistive': props.presentation === 'inline' && item.value !== '—' }">{{ item.hint }}</small>
      </div>
    </article>
  </section>
</template>

<style scoped lang="scss">
.metric-strip {
  --metric-icon-bg-size: var(--layout-metric-icon-size);
  --metric-icon-bg-radius: 12px;
  --metric-icon-glyph-size: 20px;
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(12rem, 1fr));
  gap: var(--section-gap);
  padding: var(--layout-metric-padding-block) var(--panel-padding);
  border-radius: var(--radius-lg);
}

.metric-strip.density-compact {
  --metric-icon-bg-size: 36px;
  --metric-icon-bg-radius: 10px;
  --metric-icon-glyph-size: 18px;
  --metric-icon-stroke-width: 1.45;
  padding-block: var(--layout-metric-padding-block);
}

.metric-strip__item {
  display: flex;
  align-items: center;
  min-width: 0;
  gap: var(--layout-metric-gap);
}

.metric-strip__item + .metric-strip__item {
  border-inline-start: 1px solid var(--border-divider);
  padding-inline-start: var(--layout-metric-gap);
}

.metric-strip__content {
  display: grid;
  min-width: 0;
  gap: 0.2rem;
}

.metric-strip.density-compact .metric-strip__content {
  gap: 0.05rem;
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

.metric-strip.density-compact .metric-strip__label {
  font-size: 0.75rem;
}

.metric-strip__value {
  overflow: hidden;
  color: var(--text-primary);
  font-size: 1.45rem;
  line-height: 1.15;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.metric-strip.density-compact .metric-strip__value {
  font-size: 1.2rem;
  line-height: 1.1;
}

.metric-strip__hint {
  font-size: 0.75rem;
}

.metric-strip.density-compact .metric-strip__hint {
  font-size: 0.7rem;
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

.metric-strip[data-presentation='inline'] {
  display: flex;
  flex-wrap: wrap;
  gap: 8px var(--section-gap);
  padding: 4px 0;
  border: 0;
  border-radius: 0;
  background: transparent;
  box-shadow: none;
  backdrop-filter: none;
}

.metric-strip[data-presentation='inline'] .metric-strip__item {
  flex: 0 1 auto;
  min-height: 24px;
}

.metric-strip[data-presentation='inline'] .metric-strip__item + .metric-strip__item {
  border-block-start: 0;
  border-inline-start: 1px solid var(--border-divider);
  padding-block-start: 0;
  padding-inline-start: var(--section-gap);
}

.metric-strip[data-presentation='inline'] .metric-strip__content {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  column-gap: 6px;
  row-gap: 2px;
}

.metric-strip[data-presentation='inline'] .metric-strip__label {
  color: var(--text-secondary);
  font-size: 12px;
}

.metric-strip[data-presentation='inline'] .metric-strip__value {
  font-size: 13px;
  line-height: 1.5;
}

.metric-strip[data-presentation='inline'] .metric-strip__hint {
  font-size: 12px;
}

.metric-strip[data-presentation='inline'] .metric-strip__hint--assistive {
  position: absolute;
  width: 1px;
  height: 1px;
  padding: 0;
  overflow: hidden;
  clip-path: inset(50%);
  white-space: nowrap;
}

.metric-strip[data-presentation='inline'].samples-wallpaper :is(.metric-strip__label, .metric-strip__hint) { color: var(--text-on-wallpaper-secondary); }
.metric-strip[data-presentation='inline'].samples-wallpaper .metric-strip__value { color: var(--text-on-wallpaper); }
.metric-strip[data-presentation='inline'].samples-wallpaper .metric-strip__item + .metric-strip__item { border-color: color-mix(in srgb, var(--text-on-wallpaper) 16%, transparent); }
@media (forced-colors: active) {
  .metric-strip[data-presentation='inline'].samples-wallpaper :is(.metric-strip__label, .metric-strip__value, .metric-strip__hint) { color: CanvasText; }
}
</style>
