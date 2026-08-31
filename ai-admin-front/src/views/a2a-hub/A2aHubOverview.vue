<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ArrowRight, CircleCheck, Refresh, Warning } from '@element-plus/icons-vue'
import MetricIconBg from '@/components/common/MetricIconBg.vue'
import WorkbenchPanel from '@/components/common/WorkbenchPanel.vue'
import A2aTaskStateBadge from '@/components/a2a-hub/A2aTaskStateBadge.vue'
import { getA2aHubOverview, listA2aTasks } from '@/api/a2aHub'
import type { A2aHubOverview, A2aTaskSummary } from '@/types/a2aHub'
import { a2aFormatDate } from '@/utils/a2aHub'

const router = useRouter()
const loading = ref(false)
const overview = ref<A2aHubOverview | null>(null)
const recentTasks = ref<A2aTaskSummary[]>([])

const kpis = computed(() => {
  const value = overview.value
  const taskTotal = value?.tasks.total ?? 0
  const activeTasks = (value?.tasks.working ?? 0)
    + (value?.tasks.inputRequired ?? 0)
    + (value?.tasks.authRequired ?? 0)
  const waitingTasks = (value?.tasks.inputRequired ?? 0) + (value?.tasks.authRequired ?? 0)
  const transportRequests = value?.transport.requests ?? 0
  const governanceItems = (value?.governance.expiringCredentials ?? 0)
    + (value?.governance.failedConformanceRuns ?? 0)
  const remoteRisks = (value?.remoteAgents.quarantined ?? 0) + (value?.remoteAgents.unhealthy ?? 0)

  return [
    {
      label: '24h 任务成功率',
      value: taskTotal > 0 && value ? `${(value.tasks.completionRate * 100).toFixed(1)}%` : '—',
      hint: taskTotal > 0
        ? `${value?.tasks.completed ?? 0} 个完成 · ${value?.tasks.failed ?? 0} 个失败`
        : '近 24 小时暂无任务样本',
      route: '/a2a-hub/tasks',
      iconKey: 'agent-workflow-tools',
      tone: taskTotal === 0 ? 'neutral' : (value?.tasks.failed ? 'warning' : 'success'),
    },
    {
      label: '进行与等待',
      value: activeTasks,
      hint: `${value?.tasks.working ?? 0} 个执行中 · ${waitingTasks} 个等待处理`,
      route: '/a2a-hub/tasks',
      iconKey: 'workflow-total',
      tone: waitingTasks > 0 ? 'warning' : (activeTasks > 0 ? 'info' : 'neutral'),
    },
    {
      label: 'HTTP 成功率 / P95',
      value: transportRequests > 0 && value ? `${(value.transport.successRate * 100).toFixed(1)}%` : '—',
      hint: transportRequests === 0
        ? '暂无传输样本 · P95 暂不可用'
        : value?.transport.p95LatencyMs == null
          ? `${transportRequests} 个请求 · P95 暂不可用`
          : `${transportRequests} 个请求 · P95 ${value.transport.p95LatencyMs} ms`,
      route: '/a2a-hub/developer',
      iconKey: 'api',
      tone: transportRequests === 0
        ? 'neutral'
        : ((value?.transport.successRate ?? 0) >= 0.98 ? 'success' : 'warning'),
    },
    {
      label: '已发布本地 Agent',
      value: value?.publications.published ?? 0,
      hint: `${value?.publications.draft ?? 0} 个草稿 · ${value?.publications.suspended ?? 0} 个暂停`,
      route: '/a2a-hub/publications',
      iconKey: 'tool-publish',
      tone: (value?.publications.published ?? 0) > 0 ? 'brand' : 'neutral',
    },
    {
      label: '可信远程 Agent',
      value: value?.remoteAgents.trusted ?? 0,
      hint: `${value?.remoteAgents.quarantined ?? 0} 个隔离 · ${value?.remoteAgents.unhealthy ?? 0} 个异常`,
      route: '/a2a-hub/remote-agents',
      iconKey: 'agent-total',
      tone: remoteRisks > 0 ? 'warning' : ((value?.remoteAgents.trusted ?? 0) > 0 ? 'brand' : 'neutral'),
    },
    {
      label: '治理待办',
      value: governanceItems,
      hint: `${value?.governance.expiringCredentials ?? 0} 凭据临期 · ${value?.governance.failedConformanceRuns ?? 0} 合规失败`,
      route: '/a2a-hub/trust',
      iconKey: 'agent-ready',
      tone: governanceItems > 0 ? 'danger' : 'success',
    },
  ]
})

const generatedAtLabel = computed(() => {
  if (!overview.value?.generatedAt) return '等待首次加载'
  const parsed = new Date(overview.value.generatedAt)
  if (Number.isNaN(parsed.getTime())) return '已更新'
  return `更新于 ${parsed.toLocaleTimeString('zh-CN', { hour12: false, hour: '2-digit', minute: '2-digit' })}`
})

function compactTaskId(taskId: string): string {
  if (taskId.length <= 26) return taskId
  return `${taskId.slice(0, 14)}…${taskId.slice(-8)}`
}

async function reload() {
  loading.value = true
  try {
    const [overviewResult, taskResult] = await Promise.all([
      getA2aHubOverview(),
      listA2aTasks({ limit: 8, offset: 0 }),
    ])
    overview.value = overviewResult.data
    recentTasks.value = taskResult.data?.items ?? []
  } finally {
    loading.value = false
  }
}

function openTask(task: A2aTaskSummary) {
  router.push({ path: '/a2a-hub/tasks', query: { taskId: task.taskId, direction: task.direction } })
}

onMounted(reload)
</script>

<template>
  <section class="hub-overview" v-loading="loading">
    <div class="overview-toolbar">
      <div class="overview-heading">
        <div class="overview-heading__title">
          <h2>运行态势</h2>
          <span>近 24 小时</span>
        </div>
        <p>只展示安全摘要，不读取消息正文。</p>
      </div>
      <div class="overview-actions">
        <small>{{ generatedAtLabel }}</small>
        <el-button :icon="Refresh" :loading="loading" @click="reload">刷新</el-button>
      </div>
    </div>

    <section class="metric-grid" aria-label="A2A 核心指标">
      <button
        v-for="item in kpis"
        :key="item.label"
        class="metric-card"
        :class="`is-${item.tone}`"
        type="button"
        @click="router.push(item.route)"
      >
        <span class="metric-card__head">
          <MetricIconBg :icon-key="item.iconKey" :tone="item.tone" />
          <span class="metric-card__label">{{ item.label }}</span>
          <el-icon class="metric-card__arrow" aria-hidden="true"><ArrowRight /></el-icon>
        </span>
        <strong class="metric-card__value">{{ item.value }}</strong>
        <small class="metric-card__hint">{{ item.hint }}</small>
      </button>
    </section>

    <div class="overview-grid">
      <WorkbenchPanel class="attention-panel" density="compact" title="待处理队列" description="只列出需要人处理的风险或退化项。">
        <div v-if="overview?.attention.length" class="attention-list">
          <button
            v-for="item in overview.attention"
            :key="item.code"
            type="button"
            class="attention-row"
            @click="router.push(item.actionRoute || '/a2a-hub/overview')"
          >
            <el-icon><Warning /></el-icon>
            <span><strong>{{ item.code }}</strong><small>{{ item.severity }} · {{ item.count }} 项</small></span>
            <em>处理 <el-icon><ArrowRight /></el-icon></em>
          </button>
        </div>
        <div v-else class="all-clear" role="status">
          <span class="all-clear__icon"><el-icon><CircleCheck /></el-icon></span>
          <span>
            <strong>当前运行平稳</strong>
            <small>没有需要人工处理的 A2A 风险项</small>
          </span>
        </div>
      </WorkbenchPanel>

      <WorkbenchPanel class="recent-task-panel" density="compact" title="最近跨 Agent 任务" description="点击任务可进入 Runtime Run 与 Trace 调查链路。">
        <template #actions>
          <el-button text :icon="ArrowRight" @click="router.push('/a2a-hub/tasks')">查看全部</el-button>
        </template>
        <el-table :data="recentTasks" size="small" max-height="350" @row-click="openTask">
          <el-table-column prop="submittedAt" label="受理时间" width="154">
            <template #default="{ row }">{{ a2aFormatDate(row.submittedAt) }}</template>
          </el-table-column>
          <el-table-column prop="direction" label="方向" width="82">
            <template #default="{ row }">
              <span class="direction-badge" :class="`is-${row.direction.toLowerCase()}`">
                {{ row.direction === 'INBOUND' ? '入站' : '出站' }}
              </span>
            </template>
          </el-table-column>
          <el-table-column prop="taskId" label="Task" min-width="220">
            <template #default="{ row }">
              <code class="task-id" :title="row.taskId">{{ compactTaskId(row.taskId) }}</code>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="100">
            <template #default="{ row }"><A2aTaskStateBadge :state="row.state" /></template>
          </el-table-column>
          <el-table-column prop="principalKey" label="Principal" min-width="150" show-overflow-tooltip />
          <el-table-column width="38" align="right">
            <template #default><el-icon class="row-arrow"><ArrowRight /></el-icon></template>
          </el-table-column>
        </el-table>
      </WorkbenchPanel>
    </div>
  </section>
</template>

<style scoped lang="scss">
.hub-overview {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: var(--layout-page-gap);
}

.overview-toolbar {
  display: flex;
  min-height: 44px;
  align-items: center;
  justify-content: space-between;
  gap: var(--section-gap);
}

.overview-heading h2,
.overview-heading p {
  margin: 0;
}

.overview-heading h2 {
  color: var(--text-primary);
  font-size: 18px;
}

.overview-heading__title,
.overview-actions {
  display: flex;
  align-items: center;
}

.overview-heading__title {
  gap: 9px;
}

.overview-heading__title > span {
  padding: 3px 8px;
  border: 1px solid var(--border-divider);
  border-radius: 999px;
  color: var(--text-muted);
  background: var(--surface-solid-control);
  font-size: 11px;
  font-weight: 650;
}

.overview-heading p {
  margin-top: 4px;
  color: var(--text-muted);
  font-size: 13px;
}

.overview-actions {
  flex: 0 0 auto;
  gap: 10px;
}

.overview-actions > small {
  color: var(--text-muted);
  font-size: 11px;
}

.metric-grid {
  display: grid;
  grid-template-columns: repeat(6, minmax(0, 1fr));
  gap: 10px;
}

.metric-card {
  --metric-tone: var(--status-neutral);
  position: relative;
  min-width: 0;
  min-height: 126px;
  padding: 14px 15px;
  overflow: hidden;
  border: 1px solid color-mix(in srgb, var(--metric-tone) 20%, var(--border-divider));
  border-radius: var(--radius-lg);
  color: var(--text-primary);
  text-align: left;
  background:
    linear-gradient(145deg, color-mix(in srgb, var(--metric-tone) 5%, var(--surface-solid-panel)), var(--surface-solid-panel));
  box-shadow: var(--inner-highlight), 0 14px 30px -28px color-mix(in srgb, var(--metric-tone) 62%, transparent);
  cursor: pointer;
  transition:
    border-color var(--motion-duration-fast) ease,
    box-shadow var(--motion-duration-fast) ease,
    transform var(--motion-duration-fast) ease;
}

.metric-card.is-brand { --metric-tone: var(--brand-primary); }
.metric-card.is-success { --metric-tone: var(--status-success); }
.metric-card.is-warning { --metric-tone: var(--status-warning); }
.metric-card.is-danger { --metric-tone: var(--status-danger); }
.metric-card.is-info { --metric-tone: var(--status-info); }

.metric-card::after {
  content: '';
  position: absolute;
  inset: 0 auto 0 0;
  width: 3px;
  background: var(--metric-tone);
  opacity: 0.72;
}

.metric-card:hover {
  border-color: color-mix(in srgb, var(--metric-tone) 46%, var(--border-divider));
  box-shadow: var(--inner-highlight), 0 18px 36px -28px color-mix(in srgb, var(--metric-tone) 72%, transparent);
  transform: translateY(-1px);
}

.metric-card__head {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  align-items: center;
  gap: 9px;
}

.metric-card__head :deep(.metric-icon-bg) {
  --metric-icon-bg-size: 34px;
  --metric-icon-bg-radius: 10px;
  --metric-icon-glyph-size: 18px;
}

.metric-card__label,
.metric-card__hint {
  overflow: hidden;
  color: var(--text-muted);
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.metric-card__label {
  color: var(--text-secondary);
  font-weight: 650;
}

.metric-card__arrow {
  color: var(--text-muted);
  font-size: 13px;
  opacity: 0;
  transform: translateX(-3px);
  transition: opacity var(--motion-duration-fast) ease, transform var(--motion-duration-fast) ease;
}

.metric-card:hover .metric-card__arrow,
.metric-card:focus-visible .metric-card__arrow {
  opacity: 1;
  transform: translateX(0);
}

.metric-card__value {
  display: block;
  margin: 10px 0 6px;
  font-size: 27px;
  font-variant-numeric: tabular-nums;
  line-height: 1;
}

.metric-card__hint {
  display: block;
  font-size: 11px;
}

.overview-grid {
  display: grid;
  grid-template-columns: minmax(300px, 0.58fr) minmax(620px, 1.72fr);
  align-items: start;
  gap: var(--layout-page-gap);
}

.attention-panel,
.recent-task-panel {
  background: color-mix(in srgb, var(--surface-solid-panel) 88%, transparent);
}

.all-clear {
  display: flex;
  min-height: 78px;
  align-items: center;
  gap: 12px;
  padding: 14px;
  border: 1px solid color-mix(in srgb, var(--status-success) 24%, var(--border-divider));
  border-radius: var(--radius-md);
  background: color-mix(in srgb, var(--status-success-soft) 58%, var(--surface-solid-control));
}

.all-clear__icon {
  display: grid;
  width: 38px;
  height: 38px;
  flex: 0 0 38px;
  place-items: center;
  border: 1px solid color-mix(in srgb, var(--status-success) 28%, transparent);
  border-radius: 50%;
  color: var(--status-success);
  background: var(--surface-solid-panel);
}

.all-clear__icon .el-icon {
  font-size: 20px;
}

.all-clear strong,
.all-clear small {
  display: block;
}

.all-clear strong {
  color: var(--text-primary);
  font-size: 13px;
}

.all-clear small {
  margin-top: 3px;
  color: var(--text-muted);
  font-size: 11px;
  line-height: 1.4;
}

.attention-list {
  display: grid;
  gap: 8px;
}

.attention-row {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  align-items: center;
  gap: 10px;
  width: 100%;
  padding: 10px;
  border: 1px solid var(--border-divider);
  border-radius: var(--radius-md);
  color: var(--status-warning);
  text-align: left;
  background: var(--surface-glass-control);
  cursor: pointer;
}

.attention-row strong,
.attention-row small {
  display: block;
}

.attention-row strong {
  color: var(--text-primary);
  font-size: 13px;
}

.attention-row small {
  margin-top: 2px;
  color: var(--text-muted);
  font-size: 12px;
}

.attention-row em {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  color: var(--brand-active);
  font-size: 12px;
  font-style: normal;
}

.direction-badge {
  display: inline-flex;
  min-width: 44px;
  align-items: center;
  justify-content: center;
  padding: 2px 7px;
  border: 1px solid var(--border-divider);
  border-radius: 999px;
  color: var(--text-secondary);
  background: var(--surface-solid-control);
  font-size: 11px;
  font-weight: 650;
}

.direction-badge.is-inbound {
  border-color: color-mix(in srgb, var(--status-info) 24%, var(--border-divider));
  color: var(--status-info);
  background: color-mix(in srgb, var(--status-info-soft) 56%, var(--surface-solid-control));
}

.direction-badge.is-outbound {
  border-color: color-mix(in srgb, var(--brand-primary) 24%, var(--border-divider));
  color: var(--brand-active);
  background: color-mix(in srgb, var(--surface-glass-selected) 56%, var(--surface-solid-control));
}

.task-id {
  color: var(--text-secondary);
  font-family: ui-monospace, SFMono-Regular, Consolas, monospace;
  font-size: 11px;
}

.row-arrow {
  color: var(--text-muted);
  opacity: 0;
  transition: opacity var(--motion-duration-fast) ease, transform var(--motion-duration-fast) ease;
  transform: translateX(-3px);
}

.recent-task-panel :deep(.el-table) {
  --el-table-header-bg-color: color-mix(in srgb, var(--brand-primary) 7%, var(--surface-solid-control));
  --el-table-row-hover-bg-color: color-mix(in srgb, var(--surface-glass-selected) 48%, var(--surface-solid-panel));
  --el-table-tr-bg-color: transparent;
  background: transparent;
}

.recent-task-panel :deep(.el-table__body tr) {
  cursor: pointer;
}

.recent-task-panel :deep(.el-table__body tr:hover .row-arrow) {
  opacity: 1;
  transform: translateX(0);
}

.recent-task-panel :deep(.el-table th.el-table__cell) {
  color: var(--text-muted);
  font-size: 11px;
  font-weight: 700;
}

.recent-task-panel :deep(.el-table td.el-table__cell) {
  color: var(--text-secondary);
}

@media (max-width: 1380px) {
  .metric-grid { grid-template-columns: repeat(3, minmax(0, 1fr)); }
}

@media (max-width: 1180px) {
  .overview-grid { grid-template-columns: 1fr; }
}

@media (max-width: 900px) {
  .metric-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}

@media (max-width: 640px) {
  .metric-grid { grid-template-columns: minmax(0, 1fr); }

  .overview-toolbar,
  .overview-actions {
    align-items: flex-start;
    flex-direction: column;
  }

  .overview-actions {
    width: 100%;
  }

  .overview-actions > .el-button {
    width: 100%;
  }
}

@media (prefers-reduced-motion: reduce) {
  .metric-card,
  .metric-card__arrow,
  .row-arrow {
    transition: none;
  }
}
</style>
