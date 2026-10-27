<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ArrowRight, Plus, Refresh } from '@element-plus/icons-vue'
import WorkbenchPanel from '@/components/common/WorkbenchPanel.vue'
import { LiquidGlassButton, LiquidGlassCard, LiquidGlassEmptyState, LiquidGlassNotice } from '@/components/common/liquid-glass'
import A2aTaskStateBadge from '@/components/a2a-hub/A2aTaskStateBadge.vue'
import { getA2aHubOverview, listA2aTasks } from '@/api/a2aHub'
import type { A2aHubOverview, A2aTaskSummary } from '@/types/a2aHub'
import { a2aCompactId, a2aFormatDate } from '@/utils/a2aHub'
import { usePlatformCan } from '@/auth/usePlatformCan'

const router = useRouter()
const { can } = usePlatformCan()
const loading = ref(false)
const overview = ref<A2aHubOverview | null>(null)
const recentTasks = ref<A2aTaskSummary[]>([])
const overviewError = ref(false)
const tasksError = ref(false)
const attentionLabels: Record<string, string> = {
  REMOTE_AGENT_QUARANTINED: '外部 Agent 已隔离',
  CREDENTIAL_EXPIRING: '凭据即将过期',
  CONFORMANCE_FAILED: '兼容性检查失败',
  TASK_AUTH_REQUIRED: '协作任务等待授权',
  TASK_INPUT_REQUIRED: '协作任务等待输入',
}

async function reload() {
  loading.value = true
  try {
    const [overviewResult, taskResult] = await Promise.allSettled([
      getA2aHubOverview(), listA2aTasks({ limit: 5, offset: 0 }),
    ])
    overviewError.value = overviewResult.status === 'rejected'
    tasksError.value = taskResult.status === 'rejected'
    overview.value = overviewResult.status === 'fulfilled' ? overviewResult.value.data : null
    recentTasks.value = taskResult.status === 'fulfilled' ? taskResult.value.data?.items ?? [] : []
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
    <div class="connection-grid">
      <LiquidGlassCard as="section" source="wallpaper" class="connection-panel">
        <div class="connection-heading">
          <router-link to="/a2a-hub/publications" class="connection-title" aria-label="查看对外发布">对外发布 <el-icon aria-hidden="true"><ArrowRight /></el-icon></router-link>
          <p class="connection-summary">
            <span>已发布 <strong class="connection-count">{{ overview?.publications.published ?? '—' }}</strong></span>
            <span v-if="overview?.publications.draft">草稿 {{ overview.publications.draft }}</span>
            <span v-if="overview?.publications.suspended">已暂停 {{ overview.publications.suspended }}</span>
          </p>
        </div>
        <LiquidGlassButton v-if="can('a2a-hub:publication:manage')" size="small" type="primary" :icon="Plus" @click="router.push({ path: '/a2a-hub/publications', query: { action: 'create' } })">发布 Agent</LiquidGlassButton>
      </LiquidGlassCard>

      <LiquidGlassCard as="section" source="wallpaper" class="connection-panel">
        <div class="connection-heading">
          <router-link to="/a2a-hub/remote-agents" class="connection-title" aria-label="查看外部 Agent">外部 Agent <el-icon aria-hidden="true"><ArrowRight /></el-icon></router-link>
          <p class="connection-summary">
            <span>可信 <strong class="connection-count">{{ overview?.remoteAgents.trusted ?? '—' }}</strong></span>
            <span v-if="overview?.remoteAgents.quarantined" class="risk-count">已隔离 {{ overview.remoteAgents.quarantined }}</span>
            <span v-if="overview?.remoteAgents.unhealthy" class="risk-count">健康异常 {{ overview.remoteAgents.unhealthy }}</span>
          </p>
        </div>
        <LiquidGlassButton v-if="can('a2a-hub:remote-agent:manage')" size="small" type="primary" :icon="Plus" @click="router.push({ path: '/a2a-hub/remote-agents', query: { action: 'create' } })">接入外部 Agent</LiquidGlassButton>
      </LiquidGlassCard>
    </div>

    <LiquidGlassNotice v-if="overviewError" source="wallpaper" type="error" title="互联状态加载失败">
      <template #actions><LiquidGlassButton size="small" :loading="loading" @click="reload">重试</LiquidGlassButton></template>
    </LiquidGlassNotice>
    <LiquidGlassNotice
      v-for="item in overview?.attention ?? []" :key="item.code" source="wallpaper"
      :type="item.severity === 'CRITICAL' ? 'error' : item.severity === 'WARNING' ? 'warning' : 'info'"
      :title="`${attentionLabels[item.code] ?? item.code} · ${item.count} 项`"
    >
      <template #actions><LiquidGlassButton size="small" @click="router.push(item.actionRoute || '/a2a-hub/overview')">处理</LiquidGlassButton></template>
    </LiquidGlassNotice>

    <WorkbenchPanel class="recent-task-panel glass-workspace-adaptive" density="compact" title="最近协作任务">
      <template #actions>
        <LiquidGlassButton size="small" circle :icon="Refresh" :loading="loading" aria-label="刷新" title="刷新互联状态与任务" @click="reload" />
        <LiquidGlassButton size="small" @click="router.push('/a2a-hub/tasks')">查看全部</LiquidGlassButton>
      </template>
      <LiquidGlassEmptyState v-if="tasksError" title="协作任务加载失败" description="请重试。">
        <LiquidGlassButton :loading="loading" @click="reload">重试</LiquidGlassButton>
      </LiquidGlassEmptyState>
      <el-table v-else :data="recentTasks" size="small" empty-text="暂无协作任务" @row-click="openTask">
        <el-table-column prop="submittedAt" label="受理时间" width="160"><template #default="{ row }">{{ a2aFormatDate(row.submittedAt) }}</template></el-table-column>
        <el-table-column prop="direction" label="方向" width="106"><template #default="{ row }">{{ row.direction === 'INBOUND' ? '对外服务' : '外部协作' }}</template></el-table-column>
        <el-table-column label="协作对象" min-width="180" show-overflow-tooltip><template #default="{ row }">{{ row.publicationKey || row.remoteAgentKey || '—' }}</template></el-table-column>
        <el-table-column label="状态" width="90"><template #default="{ row }"><A2aTaskStateBadge :state="row.state" /></template></el-table-column>
        <el-table-column prop="taskId" label="任务 ID" min-width="200"><template #default="{ row }"><span class="task-id" :title="row.taskId">{{ a2aCompactId(row.taskId) }}</span></template></el-table-column>
      </el-table>
    </WorkbenchPanel>
  </section>
</template>

<style scoped lang="scss">
.hub-overview { display: flex; min-width: 0; flex-direction: column; gap: var(--layout-page-gap); }
.connection-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: var(--layout-page-gap); }
.connection-panel { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 12px; min-width: 0; padding: var(--panel-padding); }
.connection-heading { min-width: 0; }
.connection-title { display: inline-flex; align-items: center; gap: 6px; color: var(--text-primary); font-size: 16px; font-weight: 650; line-height: 1.5; text-decoration: none; }
.connection-title > .el-icon { color: var(--text-muted); font-size: 13px; }
.connection-title:hover { color: var(--brand-on-surface); }
.connection-title:focus-visible { outline: 2px solid var(--border-focus); outline-offset: 3px; border-radius: var(--radius-sm); }
.connection-summary { display: flex; flex-wrap: wrap; gap: 12px; margin: 6px 0 0; color: var(--text-muted); font-size: 12px; line-height: 1.5; }
.connection-count { color: var(--text-primary); font-variant-numeric: tabular-nums; }
.risk-count { color: var(--status-warning); }
.task-id { display: block; overflow: hidden; color: var(--text-muted); font-family: var(--font-family-mono, ui-monospace, monospace); font-size: 12px; text-overflow: ellipsis; white-space: nowrap; }
.recent-task-panel :deep(.el-table) { --el-table-header-bg-color: transparent; --el-table-row-hover-bg-color: var(--surface-hover); --el-table-tr-bg-color: transparent; --el-table-bg-color: transparent; background: transparent; }
.recent-task-panel :deep(.el-table__body tr) { cursor: pointer; }
@media (max-width: 900px) { .connection-grid { grid-template-columns: 1fr; } }
</style>
