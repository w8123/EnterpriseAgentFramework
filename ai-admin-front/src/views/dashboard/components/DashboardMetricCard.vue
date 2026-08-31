<script setup lang="ts">
import { computed } from 'vue'
import type { DashboardMetricCardModel } from '@/types/operationsDashboard'

const props = defineProps<{
  metric: DashboardMetricCardModel
}>()

const safeSeries = computed(() => (props.metric.series ?? []).map((value) => (
  Number.isFinite(value) && value >= 0 ? value : 0
)))

const sparklinePoints = computed(() => {
  const values = safeSeries.value
  if (values.length < 2) return ''
  const min = Math.min(...values)
  const max = Math.max(...values)
  const span = Math.max(1, max - min)
  return values.map((value, index) => {
    const x = 2 + (index / (values.length - 1)) * 108
    const y = 29 - ((value - min) / span) * 23
    return `${x.toFixed(1)},${y.toFixed(1)}`
  }).join(' ')
})

const sparklineArea = computed(() => (
  sparklinePoints.value ? `2,32 ${sparklinePoints.value} 110,32` : ''
))

const safeProgress = computed(() => {
  const value = props.metric.progress
  if (value == null || !Number.isFinite(value)) return null
  return Math.min(100, Math.max(0, value))
})
</script>

<template>
  <article class="ops-metric" :class="[`is-${metric.tone}`, `is-${metric.status}`]">
    <span class="ops-metric__glyph" aria-hidden="true">
      <svg viewBox="0 0 28 28" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round">
        <template v-if="metric.icon === 'agent'">
          <path d="M14 4v3M11.5 4h5" />
          <rect x="5" y="8" width="18" height="14" rx="5" />
          <path d="M5 14H2.8M25.2 14H23M10 16h.1M18 16h.1M10 20h8" />
        </template>
        <template v-else-if="metric.icon === 'enabled'">
          <path d="M3 15h5l2.3-7 4.1 14 3.2-11 2 6H25" />
        </template>
        <template v-else-if="metric.icon === 'runs'">
          <rect x="6" y="3.5" width="16" height="21" rx="3.5" />
          <path d="M10 20h8M12.3 7.5h3.4" />
        </template>
        <template v-else-if="metric.icon === 'users'">
          <circle cx="10" cy="10" r="3.2" />
          <circle cx="19" cy="9" r="2.7" />
          <path d="M3.5 22c.5-5 3.1-7.3 6.5-7.3s6 2.3 6.5 7.3M15.8 15c4.8-.8 7.5 1.6 8.2 6" />
        </template>
        <template v-else-if="metric.icon === 'tokens'">
          <ellipse cx="13" cy="7" rx="8" ry="3.2" />
          <path d="M5 7v6c0 1.8 3.6 3.3 8 3.3s8-1.5 8-3.3V7M5 13v5.5c0 1.8 3.6 3.3 8 3.3 1.5 0 2.9-.2 4-.5M22 18v6M19 21h6" />
        </template>
        <template v-else>
          <path d="M14 3.5 23 7v6.4c0 5.2-3.5 9.2-9 11.1-5.5-1.9-9-5.9-9-11.1V7l9-3.5Z" />
          <path d="m10 14 2.7 2.7L18.5 11" />
        </template>
      </svg>
    </span>

    <div class="ops-metric__content">
      <span class="ops-metric__label">{{ metric.label }}</span>
      <span v-if="metric.status === 'loading'" class="ops-metric__skeleton" role="status" :aria-label="`${metric.label}加载中`" />
      <span v-else-if="metric.status === 'error'" class="ops-metric__unavailable">暂不可用</span>
      <strong v-else>{{ metric.value }}</strong>
      <span class="ops-metric__detail">{{ metric.status === 'ready' ? metric.detail : metric.note || '等待数据源响应' }}</span>
    </div>

    <div v-if="metric.status === 'ready'" class="ops-metric__visual" aria-hidden="true">
      <svg v-if="sparklinePoints" class="ops-metric__sparkline" viewBox="0 0 112 34" preserveAspectRatio="none">
        <polygon :points="sparklineArea" fill="currentColor" opacity=".08" />
        <polyline :points="sparklinePoints" fill="none" stroke="currentColor" stroke-width="1.7" vector-effect="non-scaling-stroke" />
      </svg>
      <span v-else-if="safeProgress != null" class="ops-metric__progress">
        <i :style="{ width: `${safeProgress}%` }" />
      </span>
    </div>
  </article>
</template>

<style scoped lang="scss">
.ops-metric {
  --metric: var(--ops-blue);
  position: relative;
  isolation: isolate;
  min-width: 0;
  min-height: 100%;
  display: grid;
  grid-template-columns: 54px minmax(0, 1fr);
  grid-template-rows: 1fr auto;
  column-gap: 13px;
  align-items: center;
  padding: 15px 14px 11px;
  overflow: hidden;
  color: var(--metric);
  background:
    linear-gradient(112deg, rgb(255 255 255 / .32), transparent 42%),
    radial-gradient(circle at 8% 12%, color-mix(in srgb, var(--metric) 14%, transparent), transparent 38%),
    radial-gradient(circle at 92% 112%, color-mix(in srgb, var(--metric) 9%, transparent), transparent 42%),
    var(--ops-card-background);
  border: 1px solid color-mix(in srgb, var(--metric) 20%, var(--ops-glass-border));
  border-radius: 14px;
  box-shadow: var(--ops-glass-shadow), 0 0 0 1px rgb(255 255 255 / .18) inset;
  -webkit-backdrop-filter: var(--ops-glass-filter);
  backdrop-filter: var(--ops-glass-filter);
}

.ops-metric::before {
  content: '';
  position: absolute;
  z-index: -1;
  inset: 0;
  pointer-events: none;
  background:
    linear-gradient(90deg, transparent 4%, rgb(255 255 255 / .82) 22%, transparent 58%) top / 100% 1px no-repeat,
    linear-gradient(135deg, rgb(255 255 255 / .34), transparent 28%);
}

.ops-metric > * {
  position: relative;
  z-index: 1;
}

.ops-metric.is-cyan { --metric: var(--ops-cyan); }
.ops-metric.is-violet { --metric: var(--ops-violet); }
.ops-metric.is-green { --metric: var(--ops-green); }
.ops-metric.is-orange { --metric: var(--ops-orange); }

.ops-metric__glyph {
  width: 50px;
  height: 50px;
  display: grid;
  place-items: center;
  align-self: start;
  color: var(--metric);
  background:
    radial-gradient(circle at 35% 24%, rgb(255 255 255 / .72), transparent 32%),
    color-mix(in srgb, var(--metric) 10%, var(--ops-glass-surface-strong));
  border: 1px solid color-mix(in srgb, var(--metric) 20%, rgb(255 255 255 / .72));
  border-radius: 50%;
  box-shadow:
    0 8px 18px color-mix(in srgb, var(--metric) 12%, transparent),
    inset 0 1px 0 rgb(255 255 255 / .88);
}

.ops-metric__glyph svg {
  width: 27px;
  height: 27px;
}

.ops-metric__content {
  min-width: 0;
  align-self: start;
  display: flex;
  flex-direction: column;
}

.ops-metric__label {
  overflow: hidden;
  color: var(--ops-text-secondary);
  font-size: 11.5px;
  font-weight: 650;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ops-metric strong,
.ops-metric__unavailable {
  min-height: 38px;
  display: flex;
  align-items: center;
  color: var(--ops-text-strong);
  font: 780 clamp(27px, 2vw, 36px)/1 ui-monospace, SFMono-Regular, Consolas, monospace;
  letter-spacing: -0.055em;
  font-variant-numeric: tabular-nums;
}

.ops-metric__unavailable {
  color: var(--ops-warning);
  font-size: 14px;
  letter-spacing: 0;
}

.ops-metric__detail {
  overflow: hidden;
  color: var(--ops-text-muted);
  font-size: 9.5px;
  line-height: 1.35;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ops-metric__visual {
  grid-column: 1 / -1;
  height: 30px;
  margin-top: 5px;
  color: var(--metric);
}

.ops-metric__sparkline {
  width: 100%;
  height: 30px;
  overflow: visible;
}

.ops-metric__progress {
  height: 5px;
  display: block;
  margin-top: 12px;
  overflow: hidden;
  background: var(--ops-track);
  border-radius: 99px;
}

.ops-metric__progress i {
  display: block;
  height: 100%;
  background: currentColor;
  border-radius: inherit;
}

.ops-metric__skeleton {
  width: 78%;
  height: 27px;
  margin: 4px 0 3px;
  border-radius: 5px;
  background: var(--ops-skeleton-background);
  background-size: 220% 100%;
  animation: ops-shimmer 1.5s linear infinite;
}

.ops-metric.is-error .ops-metric__glyph {
  filter: grayscale(.75);
  opacity: .58;
}

@keyframes ops-shimmer {
  to { background-position: -220% 0; }
}

@media (prefers-reduced-motion: reduce) {
  .ops-metric__skeleton { animation: none; }
}
</style>
