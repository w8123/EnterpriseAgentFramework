<script setup lang="ts">
import { computed } from 'vue'
import type { DashboardProjectSummaryItem } from '@/types/operationsDashboard'
import { formatCompactNumber } from '../dashboardModel'

const props = defineProps<{
  project: DashboardProjectSummaryItem
  index: number
}>()

defineEmits<{
  open: []
}>()

const tones = ['blue', 'violet', 'cyan', 'green', 'orange', 'amber']
const tone = computed(() => tones[props.index % tones.length])
const rateLabel = computed(() =>
  props.project.technicalCompletionRate == null
    ? '—'
    : `${props.project.technicalCompletionRate.toFixed(1)}%`,
)
const sparklinePoints = computed(() => {
  const values = props.project.runSeries ?? []
  if (values.length < 2) return ''
  const min = Math.min(...values)
  const max = Math.max(...values)
  const span = Math.max(1, max - min)
  return values.map((value, index) => {
    const x = 2 + (index / (values.length - 1)) * 146
    const y = 25 - ((value - min) / span) * 18
    return `${x.toFixed(1)},${y.toFixed(1)}`
  }).join(' ')
})
</script>

<template>
  <button type="button" class="project-ops-card" :class="`is-${tone}`" @click="$emit('open')">
    <header>
      <span class="project-ops-card__icon" aria-hidden="true">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
          <template v-if="index % 4 === 0"><path d="M3 7.5h7l2 2H21v9.5H3zM3 7.5V5h7l2 2" /></template>
          <template v-else-if="index % 4 === 1"><circle cx="9" cy="9" r="3" /><circle cx="16.5" cy="8" r="2.4" /><path d="M3 19c.6-4.2 2.8-6 6-6s5.4 1.8 6 6M14 13c3.8-.4 6 1.6 6.5 5" /></template>
          <template v-else-if="index % 4 === 2"><path d="m12 3 8 4.5v9L12 21l-8-4.5v-9zM4 7.5l8 4.5 8-4.5M12 12v9" /></template>
          <template v-else><path d="M4 13v-2a8 8 0 0 1 16 0v2M4 13H2.5v5H6v-5zM20 13h1.5v5H18v-5zM18 19c-1 1.3-2.5 2-4.5 2" /></template>
        </svg>
      </span>
      <span class="project-ops-card__title">
        <strong>{{ project.name }}</strong>
        <small>{{ project.projectCode || '未配置项目编码' }}</small>
      </span>
      <span class="project-ops-card__link">查看详情 ›</span>
    </header>
    <dl>
      <div><dt>Agent</dt><dd>{{ project.agentCount }}</dd></div>
      <div><dt>运行样本</dt><dd>{{ formatCompactNumber(project.runs) }}</dd></div>
      <div><dt>已记录 Token</dt><dd>{{ project.tokens ? formatCompactNumber(project.tokens) : '—' }}</dd></div>
      <div><dt>可识别用户</dt><dd>{{ project.users || '—' }}</dd></div>
      <div><dt>技术完成率</dt><dd>{{ rateLabel }}</dd></div>
    </dl>
    <svg v-if="sparklinePoints" class="project-ops-card__sparkline" viewBox="0 0 150 30" preserveAspectRatio="none" aria-hidden="true">
      <polyline :points="sparklinePoints" fill="none" stroke="currentColor" stroke-width="1.4" vector-effect="non-scaling-stroke" />
    </svg>
    <footer>
      <span>身份覆盖</span>
      <span class="project-ops-card__bar"><i :style="{ width: `${project.identityCoverage ?? 0}%` }" /></span>
      <strong>{{ project.identityCoverage == null ? '—' : `${project.identityCoverage.toFixed(1)}%` }}</strong>
    </footer>
  </button>
</template>

<style scoped lang="scss">
.project-ops-card {
  --project-tone: var(--ops-blue);
  min-width: 0;
  padding: 10px 11px 9px;
  color: var(--ops-text-secondary);
  text-align: left;
  background:
    linear-gradient(145deg, color-mix(in srgb, var(--project-tone) 8%, var(--ops-panel)), color-mix(in srgb, var(--ops-panel) 92%, var(--ops-bg)));
  border: 1px solid color-mix(in srgb, var(--project-tone) 52%, transparent);
  border-radius: 7px;
  box-shadow: var(--ops-card-inner-highlight);
  cursor: pointer;
  transition: transform 150ms ease, border-color 150ms ease, box-shadow 150ms ease;
}

.project-ops-card:hover,
.project-ops-card:focus-visible {
  transform: translateY(-2px);
  border-color: color-mix(in srgb, var(--project-tone) 78%, var(--ops-text-strong) 6%);
  box-shadow: 0 8px 24px color-mix(in srgb, var(--project-tone) 10%, transparent);
  outline: none;
}

.project-ops-card.is-violet { --project-tone: var(--ops-violet); }
.project-ops-card.is-cyan { --project-tone: var(--ops-cyan); }
.project-ops-card.is-green { --project-tone: var(--ops-green); }
.project-ops-card.is-orange { --project-tone: var(--ops-orange); }
.project-ops-card.is-amber { --project-tone: var(--ops-amber); }

.project-ops-card header {
  display: grid;
  grid-template-columns: 30px minmax(0, 1fr) auto;
  gap: 8px;
  align-items: center;
  padding-bottom: 8px;
  border-bottom: 1px solid color-mix(in srgb, var(--project-tone) 20%, transparent);
}

.project-ops-card__icon {
  width: 28px;
  height: 28px;
  display: grid;
  place-items: center;
  color: color-mix(in srgb, var(--project-tone) 70%, var(--ops-text-strong));
  background: color-mix(in srgb, var(--project-tone) 14%, transparent);
  border: 1px solid color-mix(in srgb, var(--project-tone) 38%, transparent);
  border-radius: 6px;
  font-weight: 700;
}

.project-ops-card__icon svg {
  width: 18px;
  height: 18px;
}

.project-ops-card__title {
  min-width: 0;
  display: flex;
  flex-direction: column;
}

.project-ops-card__title strong {
  overflow: hidden;
  color: var(--ops-text-strong);
  font-size: 13px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.project-ops-card__title small {
  overflow: hidden;
  color: var(--ops-text-muted);
  font-size: 9px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.project-ops-card__link {
  color: color-mix(in srgb, var(--project-tone) 82%, var(--ops-text-strong));
  font-size: 9px;
}

.project-ops-card dl {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 4px 12px;
  margin: 8px 0 5px;
}

.project-ops-card dl div {
  display: flex;
  justify-content: space-between;
  gap: 8px;
}

.project-ops-card dl div:last-child {
  grid-column: 1 / -1;
}

.project-ops-card dt {
  color: var(--ops-text-muted);
  font-size: 10px;
}

.project-ops-card dd {
  margin: 0;
  color: var(--ops-text-strong);
  font: 600 11px/1.4 ui-monospace, SFMono-Regular, Consolas, monospace;
  font-variant-numeric: tabular-nums;
}

.project-ops-card footer {
  display: grid;
  grid-template-columns: auto minmax(28px, 1fr) auto;
  gap: 7px;
  align-items: center;
  padding-top: 7px;
  border-top: 1px solid color-mix(in srgb, var(--project-tone) 16%, transparent);
  color: var(--ops-text-muted);
  font-size: 9px;
}

.project-ops-card__sparkline {
  width: 100%;
  height: 24px;
  display: block;
  color: var(--project-tone);
  opacity: .9;
}

.project-ops-card__bar {
  height: 4px;
  overflow: hidden;
  background: var(--ops-track);
  border-radius: 99px;
}

.project-ops-card__bar i {
  display: block;
  height: 100%;
  background: var(--project-tone);
  box-shadow: 0 0 8px color-mix(in srgb, var(--project-tone) 56%, transparent);
}

.project-ops-card footer strong {
  color: var(--ops-text-secondary);
  font-size: 9px;
}

@media (prefers-reduced-motion: reduce) {
  .project-ops-card { transition: none; }
}
</style>
