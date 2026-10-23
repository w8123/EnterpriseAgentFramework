<script setup lang="ts">
import { computed } from 'vue'
import { Grid, Setting } from '@element-plus/icons-vue'
import type {
  DashboardWidgetContext,
  DashboardWidgetInstance,
} from '@/types/operationsDashboard'
import DashboardWidgetFrame from './DashboardWidgetFrame.vue'
import DashboardPanelState from './DashboardPanelState.vue'
import WidgetKpiMetric from './widgets/WidgetKpiMetric.vue'
import WidgetAgentRanking from './widgets/WidgetAgentRanking.vue'
import WidgetProjectRanking from './widgets/WidgetProjectRanking.vue'
import WidgetAttention from './widgets/WidgetAttention.vue'
import WidgetProjectCards from './widgets/WidgetProjectCards.vue'
import WidgetRecentRuns from './widgets/WidgetRecentRuns.vue'
import WidgetHealthStatus from './widgets/WidgetHealthStatus.vue'
import WidgetShortcuts from './widgets/WidgetShortcuts.vue'
import { SHORTCUT_WIDGET_KEY, shortcutPresets } from '../dashboardShortcuts'
import DashboardUsageTrend from './DashboardUsageTrend.vue'
import DashboardTokenDistribution from './DashboardTokenDistribution.vue'
import {
  dashboardDomainLabels,
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
  openShortcut: [path: string]
}>()

const definition = computed(() => getWidgetDefinition(props.instance.widgetKey))
const isShortcuts = computed(() => props.instance.widgetKey === SHORTCUT_WIDGET_KEY)
const shortcutLabel = computed(() => shortcutPresets.find(preset => preset.key === props.instance.config.shortcutPreset)?.label ?? '自选')
const restrictedDomains = computed(() => (definition.value?.dataDomains ?? [])
  .filter(domain => props.context.deniedDomains?.[domain]))
const restrictionDetail = computed(() =>
  `${restrictedDomains.value.map(domain => dashboardDomainLabels[domain]).join('、')}；显式刷新可重新核验。`,
)

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
    :class="{ 'dash-host--shortcuts': isShortcuts }"
    :instance-id="instance.instanceId"
    :title="widgetTitle"
    :subtitle="definition?.category === 'KPI' ? undefined : ''"
    :editing="editing"
    :selected="selected"
    :bare="isBareWidget && !restrictedDomains.length"
    :inline="isShortcuts && !restrictedDomains.length"
    @select="emit('select')"
    @move="(dx, dy) => emit('move', dx, dy)"
    @resize="(dw, dh) => emit('resize', dw, dh)"
    @remove="emit('remove')"
    @settings="emit('settings')"
  >
    <template v-if="isShortcuts" #heading>
      <span class="dash-host__shortcut-mark"><Grid aria-hidden="true" /></span>
      <div class="dash-host__shortcut-heading">
        <h3 class="dash-host__shortcut-title" :title="widgetTitle">{{ widgetTitle }}</h3>
        <span v-if="shortcutLabel !== widgetTitle" class="dash-host__shortcut-label">{{ shortcutLabel }}</span>
      </div>
    </template>
    <template v-if="isShortcuts" #inline-content>
      <WidgetShortcuts
        :config="instance.config"
        :permissions="context.permissions ?? []"
        @open="path => emit('openShortcut', path)"
      />
    </template>
    <template v-if="!restrictedDomains.length" #actions>
      <button
        v-if="isShortcuts"
        type="button"
        class="dash-host__customize"
        data-testid="customize-shortcuts"
        @click="emit('settings')"
      ><Setting aria-hidden="true" />自定义</button>
      <button
        v-else-if="instance.widgetKey === 'ranking.agent-top'"
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

    <DashboardPanelState
      v-if="restrictedDomains.length"
      class="dash-host__restricted"
      tone="disabled"
      :title="definition?.category === 'KPI' ? '读取受限' : `${widgetTitle}读取受限`"
      :detail="restrictionDetail"
      role="status"
    />
    <WidgetKpiMetric v-else-if="metric" :metric="metric" />
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
.dash-host--shortcuts {
  container-type: inline-size;

  &.dash-frame.ops-panel {
    border-radius: 14px;
    background: var(--surface-glass-frost);
    -webkit-backdrop-filter: blur(var(--glass-blur-frost)) saturate(var(--glass-saturation));
    backdrop-filter: blur(var(--glass-blur-frost)) saturate(var(--glass-saturation));
  }

  &.dash-frame.ops-panel:not(.is-editing) {
    border-color: var(--border-glass-edge);
    box-shadow: var(--shadow-glass-frost);
  }

  :deep(.dash-frame__heading) { align-items: center; gap: 8px; max-width: 200px; }
}
.dash-host__shortcut-mark { display: grid; place-items: center; color: var(--ops-blue); }
.dash-host__shortcut-mark svg { width: 15px; height: 15px; }
.dash-host__shortcut-heading { display: grid; gap: 1px; min-width: 0; }
.dash-host__shortcut-title {
  margin: 0;
  overflow: hidden;
  color: var(--ops-text-strong);
  font-size: 13px;
  font-weight: 600;
  line-height: 1.4;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.dash-host__shortcut-label { color: var(--ops-text-secondary); font-size: 11px; line-height: 1.4; white-space: nowrap; }
.dash-host__customize {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  min-height: 32px;
  padding: 5px 7px;
  border: 0;
  border-radius: 6px;
  color: var(--ops-text-secondary);
  background: transparent;
  font-size: 12px;
  cursor: pointer;

  svg { width: 13px; height: 13px; }
  &:hover { color: var(--ops-blue); background: var(--ops-surface-hover); }
  &:focus-visible { outline: 2px solid var(--border-focus); outline-offset: 2px; }
}

@media (pointer: coarse) {
  .dash-host__customize { min-height: 44px; }
}
.dash-host__restricted {
  min-height: 0;
  flex: 1;
  padding: 8px;
  gap: 3px;

  :deep(.dashboard-panel-state__signal) { display: none; }
  :deep(strong) { font-size: 12px; }
  :deep(p) { font-size: 11px; }
}

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
