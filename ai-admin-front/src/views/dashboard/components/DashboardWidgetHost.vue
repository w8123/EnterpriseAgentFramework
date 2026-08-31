<script setup lang="ts">
import { computed } from 'vue'
import type {
  DashboardWidgetContext,
  DashboardWidgetInstance,
} from '@/types/operationsDashboard'
import DashboardWidgetFrame from './DashboardWidgetFrame.vue'
import WidgetKpiMetric from './widgets/WidgetKpiMetric.vue'
import WidgetAgentRanking from './widgets/WidgetAgentRanking.vue'
import WidgetProjectRanking from './widgets/WidgetProjectRanking.vue'
import WidgetAttention from './widgets/WidgetAttention.vue'
import WidgetProjectCards from './widgets/WidgetProjectCards.vue'
import WidgetRecentRuns from './widgets/WidgetRecentRuns.vue'
import WidgetHealthStatus from './widgets/WidgetHealthStatus.vue'
import DashboardUsageTrend from './DashboardUsageTrend.vue'
import DashboardTokenDistribution from './DashboardTokenDistribution.vue'
import {
  getWidgetDefinition,
  resolveDisplayCount,
  resolveTopN,
  resolveWidgetTitle,
} from '../widgetRegistry'

/**
 * 单个 Widget 实例宿主：根据注册表 widgetKey 分发渲染体。
 * KPI 用 bare 模式（卡片自身标签即标题）；面板型复用统一 Frame。
 */
const props = defineProps<{
  instance: DashboardWidgetInstance
  context: DashboardWidgetContext
  editing?: boolean
  selected?: boolean
}>()

const emit = defineEmits<{
  select: []
  move: [dx: number, dy: number]
  resize: [dw: number, dh: number]
  remove: []
  settings: []
  openAgent: [agent: DashboardWidgetContext['agentRanking'][number]]
  openProject: [project: DashboardWidgetContext['projectSummaries'][number]]
  openProjects: []
  openAgents: []
  openRunops: []
  openAttention: [target: DashboardWidgetContext['attentionItems'][number]['to']]
  openRunTrace: [traceId: string]
  retryRuns: []
  retryAll: []
}>()

const definition = computed(() => getWidgetDefinition(props.instance.widgetKey))

const metric = computed(() => props.context.metrics[props.instance.widgetKey] ?? null)

const isBareWidget = computed(() => metric.value != null || props.instance.widgetKey === 'activity.runtime-stream')

const widgetTitle = computed(() => {
  if (props.instance.config.title?.trim()) return resolveWidgetTitle(props.instance)
  if (props.instance.widgetKey === 'ranking.agent-top') {
    return props.context.rangeLabel === '近 24 小时' ? '今日 TOP Agent' : `${props.context.rangeLabel} TOP Agent`
  }
  if (props.instance.widgetKey === 'trend.usage') {
    return props.context.rangeLabel === '近 24 小时' ? '今日运行态势' : `${props.context.rangeLabel}运行态势`
  }
  return resolveWidgetTitle(props.instance)
})

const agentRanking = computed(() =>
  props.context.agentRanking.slice(0, resolveTopN(props.instance)),
)

const rankedProjects = computed(() => props.context.rankedProjects)

const projectSummaries = computed(() =>
  props.context.projectSummaries.slice(0, resolveDisplayCount(props.instance)),
)
</script>

<template>
  <DashboardWidgetFrame
    :instance-id="instance.instanceId"
    :title="widgetTitle"
    :subtitle="definition?.category === 'KPI' ? undefined : ''"
    :editing="editing"
    :selected="selected"
    :bare="isBareWidget"
    @select="emit('select')"
    @move="(dx, dy) => emit('move', dx, dy)"
    @resize="(dw, dh) => emit('resize', dw, dh)"
    @remove="emit('remove')"
    @settings="emit('settings')"
  >
    <template #actions>
      <button
        v-if="instance.widgetKey === 'ranking.agent-top'"
        type="button"
        class="ops-link-btn"
        data-testid="open-agents-link"
        @click="emit('openAgents')"
      >查看全部 →</button>
      <button
        v-else-if="instance.widgetKey === 'ranking.project-top' || instance.widgetKey === 'projects.operations-cards'"
        type="button"
        class="ops-link-btn"
        @click="emit('openProjects')"
      >查看全部 →</button>
      <button
        v-else-if="instance.widgetKey === 'quality.attention'"
        type="button"
        class="ops-link-btn"
        @click="emit('openRunops')"
      >运行中心 →</button>
    </template>

    <WidgetKpiMetric v-if="metric" :metric="metric" />
    <WidgetAgentRanking
      v-else-if="instance.widgetKey === 'ranking.agent-top'"
      :status="context.runsStatus"
      :items="agentRanking"
      @open="(agent) => emit('openAgent', agent)"
      @view-all="emit('openAgents')"
      @retry="emit('retryRuns')"
    />
    <DashboardUsageTrend
      v-else-if="instance.widgetKey === 'trend.usage'"
      :status="context.runsStatus"
      :buckets="context.trendBuckets"
      :range-label="context.rangeLabel"
      @retry="emit('retryRuns')"
    />
    <WidgetProjectRanking
      v-else-if="instance.widgetKey === 'ranking.project-top'"
      :status="context.projectStatus"
      :projects="rankedProjects"
      :max-runs="context.maxProjectRuns"
      @open="(project) => emit('openProject', project)"
      @view-all="emit('openProjects')"
      @retry="emit('retryAll')"
    />
    <WidgetAttention
      v-else-if="instance.widgetKey === 'quality.attention'"
      :status="context.runsStatus"
      :items="context.attentionItems"
      :has-signals="context.attentionHasSignals"
      :sample-count="context.runsData.length"
      @open="(target) => emit('openAttention', target)"
      @view-all="emit('openRunops')"
      @retry="emit('retryRuns')"
    />
    <WidgetProjectCards
      v-else-if="instance.widgetKey === 'projects.operations-cards'"
      :status="context.projectStatus"
      :projects="projectSummaries"
      @open="(project) => emit('openProject', project)"
      @view-all="emit('openProjects')"
      @retry="emit('retryAll')"
    />
    <DashboardTokenDistribution
      v-else-if="instance.widgetKey === 'distribution.token-by-project'"
      :status="context.tokenDistributionStatus"
      :items="context.tokenDistribution"
      @retry="emit('retryAll')"
    />
    <WidgetRecentRuns
      v-else-if="instance.widgetKey === 'activity.runtime-stream'"
      :status="context.runsStatus"
      :runs="context.runsData"
      @open="(traceId) => emit('openRunTrace', traceId)"
      @view-all="emit('openRunops')"
    />
    <WidgetHealthStatus
      v-else-if="instance.widgetKey === 'service.data-source-status'"
      :health="context.health"
    />
    <div v-else class="dash-host__unknown">未注册的看板组件：{{ instance.widgetKey }}</div>
  </DashboardWidgetFrame>
</template>

<style scoped lang="scss">
.dash-host__unknown {
  flex: 1;
  display: grid;
  place-items: center;
  padding: 16px;
  color: var(--ops-warning);
  font-size: 11px;
  text-align: center;
}
</style>
