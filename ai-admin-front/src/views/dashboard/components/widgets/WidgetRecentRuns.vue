<script setup lang="ts">
import type { DashboardDomainStatus } from '@/types/operationsDashboard'
import type { RunSummary } from '@/types/runops'
import {
  runStatusLabel,
  runStatusTone,
  runTargetLabel,
} from '../../dashboardModel'

defineProps<{
  status: DashboardDomainStatus
  runs: RunSummary[]
}>()

defineEmits<{
  open: [traceId: string]
  viewAll: []
}>()

function formatTime(value?: string) {
  if (!value) return '—'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return '—'
  return new Intl.DateTimeFormat('zh-CN', {
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  }).format(date)
}
</script>

<template>
  <div class="widget-body ops-activity">
    <span class="ops-activity__title"><i aria-hidden="true" />实时动态</span>
    <div class="ops-activity__stream">
      <span v-if="status === 'loading'" class="ops-activity__state">加载中…</span>
      <span v-else-if="status === 'error'" class="ops-activity__state is-error">运行动态暂不可用</span>
      <span v-else-if="!runs.length" class="ops-activity__state">当前时间范围暂无运行记录</span>
      <button
        v-for="run in runs.slice(0, 5)"
        v-else
        :key="run.traceId"
        type="button"
        class="ops-activity__item"
        :class="`is-${runStatusTone(run)}`"
        @click="$emit('open', run.traceId)"
      >
        <time>{{ formatTime(run.startedAt) }}</time>
        <span>{{ runStatusLabel(run) }} · {{ runTargetLabel(run) }}<template v-if="run.projectCode"> · {{ run.projectCode }}</template></span>
        <b>查看详情 ›</b>
      </button>
    </div>
    <button type="button" class="ops-activity__more" @click="$emit('viewAll')">更多动态 ›</button>
  </div>
</template>

<style scoped lang="scss">
.widget-body {
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
</style>
