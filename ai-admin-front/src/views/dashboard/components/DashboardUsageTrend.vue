<script setup lang="ts">
import { computed, getCurrentInstance, ref } from 'vue'
import type { DashboardDomainStatus, DashboardTrendBucket } from '@/types/operationsDashboard'
import { formatCompactNumber } from '../dashboardModel'
import DashboardPanelState from './DashboardPanelState.vue'

let gradientSequence = 0

const props = defineProps<{
  status: DashboardDomainStatus
  buckets: DashboardTrendBucket[]
  rangeLabel: string
}>()

defineEmits<{ retry: [] }>()

type TrendMetric = 'runs' | 'tokens' | 'users'
const activeMetric = ref<TrendMetric>('runs')
const gradientId = `ops-trend-area-${getCurrentInstance()?.uid ?? ++gradientSequence}`
const gradientUrl = `url(#${gradientId})`

const metricOptions: { key: TrendMetric; label: string; color: string }[] = [
  { key: 'runs', label: '调用样本', color: 'var(--ops-blue)' },
  { key: 'tokens', label: 'Token', color: 'var(--ops-violet)' },
  { key: 'users', label: '可识别用户', color: 'var(--ops-cyan)' },
]

const activeOption = computed(() => metricOptions.find((option) => option.key === activeMetric.value)!)
const values = computed(() => props.buckets.map((bucket) => bucket[activeMetric.value]))
const maximum = computed(() => Math.max(1, ...values.value))
const tokenMaximum = computed(() => Math.max(1, ...props.buckets.map((bucket) => bucket.tokens)))
const metricTicks = computed(() => [1, .75, .5, .25, 0].map((ratio, index) => ({
  key: ratio,
  y: [57, 93, 129, 165, 202][index],
  label: formatCompactNumber(maximum.value * ratio),
})))
const completionFloor = computed(() => {
  const values = props.buckets
    .map((bucket) => bucket.technicalCompletionRate)
    .filter((value): value is number => value != null)
  return values.length && Math.min(...values) >= 75 ? 75 : 0
})
const completionTicks = computed(() => [1, .5, 0].map((ratio, index) => ({
  key: ratio,
  y: [57, 129.5, 202][index],
  label: `${Math.round(completionFloor.value + (100 - completionFloor.value) * ratio)}%`,
})))

function bucketX(index: number) {
  return props.buckets.length === 1 ? 365 : 48 + (index / (props.buckets.length - 1)) * 634
}

function metricY(value: number) {
  return 202 - (value / maximum.value) * 145
}

function completionY(value: number) {
  const range = Math.max(1, 100 - completionFloor.value)
  const ratio = Math.min(1, Math.max(0, (value - completionFloor.value) / range))
  return 202 - ratio * 145
}

function showBucketLabel(index: number) {
  return props.buckets.length <= 8 || index % 3 === 0 || index === props.buckets.length - 1
}

const activeLinePoints = computed(() => props.buckets
  .map((bucket, index) => `${bucketX(index).toFixed(1)},${metricY(bucket[activeMetric.value]).toFixed(1)}`)
  .join(' '))

const activeAreaPoints = computed(() => (
  activeLinePoints.value ? `48,202 ${activeLinePoints.value} 682,202` : ''
))

const completionSegments = computed(() => {
  const segments: string[][] = []
  let current: string[] = []
  props.buckets.forEach((bucket, index) => {
    if (bucket.technicalCompletionRate == null) {
      if (current.length) segments.push(current)
      current = []
      return
    }
    current.push(`${bucketX(index).toFixed(1)},${completionY(bucket.technicalCompletionRate).toFixed(1)}`)
  })
  if (current.length) segments.push(current)
  return segments
})

const totals = computed(() => {
  const completed = props.buckets.reduce((sum, bucket) => sum + bucket.completed, 0)
  const terminal = props.buckets.reduce((sum, bucket) => sum + bucket.terminal, 0)
  return {
    runs: props.buckets.reduce((sum, bucket) => sum + bucket.runs, 0),
    tokens: props.buckets.reduce((sum, bucket) => sum + bucket.tokens, 0),
    users: props.buckets.reduce((sum, bucket) => sum + bucket.users, 0),
    completionRate: terminal > 0 ? (completed / terminal) * 100 : null,
  }
})
</script>

<template>
  <div class="usage-trend__body widget-body">
    <div class="usage-trend__header">
      <p class="usage-trend__meta">{{ rangeLabel }} · 仅基于最近返回样本，不外推平台总量</p>
      <div class="usage-trend__tabs" aria-label="趋势指标">
        <button
          v-for="option in metricOptions"
          :key="option.key"
          type="button"
          :class="{ 'is-active': activeMetric === option.key }"
          @click="activeMetric = option.key"
        >{{ option.label }}</button>
      </div>
    </div>

    <div v-if="status === 'loading'" class="usage-trend__loading" aria-busy="true" aria-label="运行态势加载中">
      <span v-for="i in 8" :key="i" :style="{ height: `${20 + ((i * 17) % 70)}%` }" />
    </div>
    <DashboardPanelState
      v-else-if="status === 'error'"
      title="运行态势暂不可用"
      detail="其他资产数据仍会保留；可单独重试运行数据。"
      tone="error"
      @retry="$emit('retry')"
    />
    <DashboardPanelState
      v-else-if="!buckets.some((bucket) => bucket.runs > 0)"
      title="当前时间范围暂无运行样本"
      detail="这里不会生成示例曲线；产生真实 Agent 或 Workflow 运行后才会显示趋势。"
    />
    <div v-else class="usage-trend__chart">
      <div class="usage-trend__legend" aria-hidden="true">
        <span class="is-active-line"><i />{{ activeOption.label }}</span>
        <span class="is-token"><i />Token 记录</span>
        <span class="is-completion"><i />技术完成率</span>
      </div>
      <svg viewBox="0 0 730 246" role="img" :aria-label="`${activeOption.label}、Token 与技术完成率综合态势`">
        <defs>
          <linearGradient :id="gradientId" x1="0" y1="0" x2="0" y2="1">
            <stop offset="0" :stop-color="activeOption.color" stop-opacity=".17" />
            <stop offset="1" :stop-color="activeOption.color" stop-opacity="0" />
          </linearGradient>
        </defs>
        <g class="usage-trend__grid" aria-hidden="true">
          <line v-for="y in [57, 93, 129, 165, 202]" :key="y" x1="48" :y1="y" x2="682" :y2="y" />
        </g>
        <g class="usage-trend__axis" aria-hidden="true">
          <text class="usage-trend__axis-title" x="48" y="45">{{ activeOption.label }}</text>
          <text class="usage-trend__axis-title" x="682" y="45" text-anchor="end">技术完成率</text>
          <text
            v-for="tick in metricTicks"
            :key="`metric-tick-${tick.key}`"
            x="42"
            :y="tick.y + 3"
            text-anchor="end"
          >{{ tick.label }}</text>
          <text
            v-for="tick in completionTicks"
            :key="`completion-tick-${tick.key}`"
            x="688"
            :y="tick.y + 3"
            text-anchor="start"
          >{{ tick.label }}</text>
        </g>
        <g class="usage-trend__bars">
          <rect
            v-for="(bucket, index) in buckets"
            :key="`bar-${bucket.key}`"
            :x="bucketX(index) - Math.min(16, 180 / buckets.length)"
            :y="202 - (bucket.tokens / tokenMaximum) * 125"
            :width="Math.min(24, 360 / buckets.length)"
            :height="(bucket.tokens / tokenMaximum) * 125"
            rx="2"
          />
        </g>
        <polygon :points="activeAreaPoints" :fill="gradientUrl" />
        <polyline class="usage-trend__active-line" :points="activeLinePoints" fill="none" :stroke="activeOption.color" stroke-width="2.5" stroke-linejoin="round" stroke-linecap="round" />
        <polyline
          v-for="(segment, index) in completionSegments"
          :key="`completion-${index}`"
          class="usage-trend__completion-line"
          :points="segment.join(' ')"
          fill="none"
          stroke="var(--ops-green)"
          stroke-width="2"
          stroke-linejoin="round"
          stroke-linecap="round"
        />
        <g v-for="(bucket, index) in buckets" :key="bucket.key">
          <circle
            :cx="bucketX(index)"
            :cy="metricY(bucket[activeMetric])"
            :r="buckets.length > 12 ? 2.2 : 3.2"
            :fill="activeOption.color"
            stroke="var(--ops-chart-point-stroke)"
            :stroke-width="buckets.length > 12 ? 1.2 : 1.6"
          />
          <circle
            v-if="bucket.technicalCompletionRate != null"
            :cx="bucketX(index)"
            :cy="completionY(bucket.technicalCompletionRate)"
            r="2.7"
            fill="var(--ops-green)"
            stroke="var(--ops-chart-point-stroke)"
            stroke-width="1.3"
          />
          <text v-if="showBucketLabel(index)" :x="bucketX(index)" y="231" text-anchor="middle">{{ bucket.label }}</text>
          <title>{{ bucket.label }}：样本 {{ bucket.runs }}，Token {{ bucket.tokens }}，可识别用户 {{ bucket.users }}，技术完成率 {{ bucket.technicalCompletionRate == null ? '无终态样本' : `${bucket.technicalCompletionRate.toFixed(1)}%` }}</title>
        </g>
      </svg>
    </div>

    <footer class="usage-trend__totals">
      <span>运行样本 <strong>{{ formatCompactNumber(totals.runs) }}</strong></span>
      <span>已记录 Token <strong>{{ totals.tokens ? formatCompactNumber(totals.tokens) : '—' }}</strong></span>
      <span>分桶可识别用户 <strong>{{ formatCompactNumber(totals.users) }}</strong></span>
      <span>技术完成率 <strong>{{ totals.completionRate == null ? '—' : `${totals.completionRate.toFixed(1)}%` }}</strong></span>
    </footer>
  </div>
</template>

<style scoped lang="scss">
.widget-body {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

.usage-trend__header {
  min-height: 40px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 6px 10px;
  background: var(--ops-panel-header-background);
  border-bottom: 1px solid var(--ops-divider);
}

.usage-trend__meta {
  margin: 0;
  color: var(--ops-text-muted);
  font-size: 9px;
  line-height: 1.35;
}

.usage-trend__tabs {
  display: flex;
  padding: 2px;
  background: color-mix(in srgb, var(--ops-glass-surface) 76%, var(--ops-surface-control));
  border: 1px solid var(--ops-glass-border);
  border-radius: 8px;
  box-shadow: inset 0 0 0 1px color-mix(in srgb, var(--ops-border) 54%, transparent), inset 0 1px 0 rgb(255 255 255 / .72);
  -webkit-backdrop-filter: blur(10px) saturate(135%);
  backdrop-filter: blur(10px) saturate(135%);
}

.usage-trend__tabs button {
  padding: 5px 11px;
  color: var(--ops-text-muted);
  background: transparent;
  border: 0;
  border-radius: 5px;
  font-size: 10px;
  cursor: pointer;
}

.usage-trend__tabs button.is-active {
  color: var(--ops-primary-action-text);
  background: var(--ops-primary-action-background);
  box-shadow: 0 4px 10px color-mix(in srgb, var(--ops-blue) 18%, transparent);
}

.usage-trend__chart {
  position: relative;
  flex: 1;
  min-height: 0;
  padding: 5px 10px 0;
  background: radial-gradient(420px 180px at 50% 62%, color-mix(in srgb, var(--ops-blue) 4%, transparent), transparent 74%);
}

.usage-trend__legend {
  position: absolute;
  z-index: 1;
  top: 9px;
  left: 18px;
  display: flex;
  gap: 14px;
  color: var(--ops-text-muted);
  font-size: 9px;
}

.usage-trend__legend span {
  display: inline-flex;
  align-items: center;
  gap: 5px;
}

.usage-trend__legend i {
  width: 12px;
  height: 2px;
  background: var(--ops-blue);
}

.usage-trend__legend .is-token i {
  height: 6px;
  background: var(--ops-violet);
  opacity: .62;
}

.usage-trend__legend .is-completion i { background: var(--ops-green); }

.usage-trend__chart svg {
  width: 100%;
  height: 100%;
  min-height: 188px;
  display: block;
  overflow: visible;
}

.usage-trend__chart text {
  fill: var(--ops-text-muted);
  font-size: 8.5px;
  font-variant-numeric: tabular-nums;
}

.usage-trend__axis-title {
  fill: var(--ops-text-secondary) !important;
  font-size: 8px !important;
  font-weight: 650;
  letter-spacing: .02em;
}

.usage-trend__grid line {
  stroke: var(--ops-chart-grid);
  stroke-width: 1;
  stroke-dasharray: 3 5;
}

.usage-trend__active-line {
  filter: drop-shadow(0 3px 4px color-mix(in srgb, var(--ops-blue) 18%, transparent));
}

.usage-trend__completion-line {
  filter: drop-shadow(0 2px 3px color-mix(in srgb, var(--ops-green) 16%, transparent));
}

.usage-trend__bars rect {
  fill: var(--ops-violet);
  opacity: .3;
}

.usage-trend__totals {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  margin: 0 10px 10px;
  padding: 8px 10px;
  background:
    linear-gradient(90deg, transparent 4%, rgb(255 255 255 / .72) 26%, transparent 68%) top / 100% 1px no-repeat,
    color-mix(in srgb, var(--ops-glass-surface) 74%, var(--ops-surface-soft));
  border: 1px solid var(--ops-glass-border);
  border-radius: 10px;
  box-shadow: 0 6px 18px rgb(43 82 130 / .055), inset 0 0 0 1px color-mix(in srgb, var(--ops-border) 46%, transparent);
  -webkit-backdrop-filter: blur(12px) saturate(135%);
  backdrop-filter: blur(12px) saturate(135%);
  color: var(--ops-text-muted);
  font-size: 9px;
}

.usage-trend__totals span {
  min-width: 0;
  padding: 0 8px;
  text-align: center;
}

.usage-trend__totals span:not(:last-child) { border-right: 1px solid var(--ops-divider); }

.usage-trend__totals strong {
  display: block;
  margin-top: 2px;
  color: var(--ops-blue);
  font: 700 13px/1 ui-monospace, SFMono-Regular, Consolas, monospace;
}

.usage-trend__loading {
  flex: 1;
  min-height: 190px;
  display: flex;
  align-items: flex-end;
  gap: 5%;
  padding: 34px 26px;
}

.usage-trend__loading span {
  flex: 1;
  border-radius: 3px 3px 0 0;
  background: var(--ops-skeleton-background);
  animation: ops-trend-pulse 1.6s ease-in-out infinite alternate;
}

@keyframes ops-trend-pulse { to { opacity: .35; } }

@media (max-width: 720px) {
  .usage-trend__header { align-items: stretch; flex-direction: column; }
  .usage-trend__tabs button { flex: 1; }
  .usage-trend__totals { grid-template-columns: repeat(2, 1fr); row-gap: 8px; }
}

@media (prefers-reduced-motion: reduce) {
  .usage-trend__loading span { animation: none; }
}
</style>
