<script setup lang="ts">
import { onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search } from '@element-plus/icons-vue'
import AppDialog from '@/components/common/AppDialog.vue'
import AppDrawer from '@/components/common/AppDrawer.vue'
import WorkbenchPanel from '@/components/common/WorkbenchPanel.vue'
import { LiquidGlassInput, LiquidGlassNotice, LiquidGlassSelect, LiquidGlassTable, LiquidGlassTabs } from '@/components/common/liquid-glass'
import A2aListToolbar from '../A2aListToolbar.vue'
import A2aTaskStateBadge from '@/components/a2a-hub/A2aTaskStateBadge.vue'
import TaskTimeline from '@/components/a2a-hub/TaskTimeline.vue'
import {
  cancelA2aTask,
  getA2aArtifactPayload,
  getA2aMessagePayload,
  getA2aTask,
  listA2aTasks,
} from '@/api/a2aHub'
import type {
  A2aArtifactSummary,
  A2aMessageSummary,
  A2aPayloadView,
  A2aTaskDetail,
  A2aTaskSummary,
} from '@/types/a2aHub'
import {
  a2aFormatBytes,
  a2aCompactId,
  a2aFormatDate,
  a2aLabel,
  a2aTaskCenterDeepLink,
  a2aTaskTerminal,
} from '@/utils/a2aHub'

const route = useRoute()
const router = useRouter()
const loading = ref(false)
const rows = ref<A2aTaskSummary[]>([])
const total = ref(0)
const filters = reactive({ search: '', direction: '', state: '', tenantScope: '', limit: 20, offset: 0 })
const taskStates = ['TASK_STATE_SUBMITTED', 'TASK_STATE_WORKING', 'TASK_STATE_INPUT_REQUIRED', 'TASK_STATE_AUTH_REQUIRED', 'TASK_STATE_COMPLETED', 'TASK_STATE_FAILED', 'TASK_STATE_CANCELED', 'TASK_STATE_REJECTED']

function routeDirection(value: unknown): string {
  return value === 'INBOUND' || value === 'OUTBOUND' ? value : ''
}

function routeState(value: unknown): string {
  return typeof value === 'string' && taskStates.includes(value) ? value : ''
}

const detailVisible = ref(false)
const detailLoading = ref(false)
const detail = ref<A2aTaskDetail | null>(null)
const activeTab = ref('timeline')

const payloadVisible = ref(false)
const payloadLoading = ref(false)
const payload = ref<A2aPayloadView | null>(null)
const payloadTitle = ref('受控正文')

function pollStatusLabel(value?: string | null) {
  return ({
    PENDING: '待启动',
    ACTIVE: '自动查询中',
    PAUSED: '等待输入 / 授权',
    TERMINAL: '已收敛',
  } as Record<string, string>)[value || 'PENDING'] ?? value ?? '-'
}

async function reload() {
  loading.value = true
  try {
    const { data } = await listA2aTasks({
      search: filters.search || undefined,
      direction: filters.direction || undefined,
      state: filters.state || undefined,
      tenantScope: filters.tenantScope || undefined,
      limit: filters.limit,
      offset: filters.offset,
    })
    rows.value = data?.items ?? []
    total.value = data?.total ?? 0
  } finally {
    loading.value = false
  }
}

async function applyFilters() {
  filters.offset = 0
  const next = { ...route.query }
  if (filters.direction) next.direction = filters.direction
  else delete next.direction
  if (filters.state) next.state = filters.state
  else delete next.state
  await router.replace({ query: next })
  await reload()
}

function changePage(page: number, size: number) {
  filters.limit = size
  filters.offset = (page - 1) * size
  reload()
}

async function openDetail(row: A2aTaskSummary | { taskId: string; direction: string }) {
  detailVisible.value = true
  detailLoading.value = true
  activeTab.value = 'timeline'
  try {
    const { data } = await getA2aTask(row.taskId, row.direction)
    detail.value = data
    await router.replace({ query: { ...route.query, taskId: row.taskId, direction: row.direction } })
  } finally {
    detailLoading.value = false
  }
}

async function requestCancel() {
  const task = detail.value?.task
  if (!task) return
  const outbound = task.direction === 'OUTBOUND'
  await ElMessageBox.confirm(
    outbound
      ? '系统只向固定远端 Task 发送一次取消请求。若结果不确定，不会重复取消；后续仅通过安全查询收敛真实终态。'
      : '取消是协作命令。页面只会显示“已请求”，直到 Runtime 返回真实终态证据。',
    '请求取消 A2A Task',
    { type: 'warning', confirmButtonText: '发送取消请求' },
  )
  detailLoading.value = true
  try {
    const { data: canceled } = await cancelA2aTask(task.taskId, task.direction)
    if (outbound && canceled.cancelPhase === 'UNKNOWN') {
      ElMessage.warning('远端取消结果不确定，系统不会重复取消，将继续通过 Task 查询收敛状态')
    } else if (outbound) {
      ElMessage.success('远端取消结果已写入 Task，系统将继续收敛真实终态')
    } else {
      ElMessage.success('取消请求已持久化并进入 Runtime 可靠投递')
    }
    await Promise.all([openDetail(task), reload()])
  } finally {
    detailLoading.value = false
  }
}

async function revealMessage(item: A2aMessageSummary) {
  const task = detail.value?.task
  if (!task) return
  payloadTitle.value = `Message ${item.messageId}`
  payloadVisible.value = true
  payloadLoading.value = true
  payload.value = null
  try {
    const { data } = await getA2aMessagePayload(task.taskId, item.messageId, task.direction)
    payload.value = data
  } finally {
    payloadLoading.value = false
  }
}

async function revealArtifact(item: A2aArtifactSummary) {
  const task = detail.value?.task
  if (!task) return
  payloadTitle.value = `Artifact ${item.artifactId}`
  payloadVisible.value = true
  payloadLoading.value = true
  payload.value = null
  try {
    const { data } = await getA2aArtifactPayload(task.taskId, item.artifactId, task.direction)
    payload.value = data
  } finally {
    payloadLoading.value = false
  }
}

function goTrace() {
  const traceId = detail.value?.runtime.traceId
  if (traceId) router.push(`/runops/${encodeURIComponent(traceId)}`)
}

watch(detailVisible, (visible) => {
  if (!visible && route.query.taskId) {
    const next = { ...route.query }
    delete next.taskId
    if (filters.direction) next.direction = filters.direction
    else delete next.direction
    router.replace({ query: next })
  }
})

watch(() => [route.query.direction, route.query.state], ([direction, state]) => {
  if (route.query.taskId) return
  const nextDirection = routeDirection(direction)
  const nextState = routeState(state)
  if (filters.direction === nextDirection && filters.state === nextState) return
  filters.direction = nextDirection
  filters.state = nextState
  filters.offset = 0
  reload()
})

onMounted(async () => {
  const deepLink = a2aTaskCenterDeepLink(route.query)
  filters.direction = deepLink?.direction ?? routeDirection(route.query.direction)
  filters.state = routeState(route.query.state)
  if (deepLink) {
    filters.search = deepLink.taskId
  }
  await reload()
  if (deepLink) await openDetail(deepLink)
})
</script>

<template>
<section class="task-center" aria-label="协作任务">
    <LiquidGlassTable
      class="workbench-list-surface"
      :current-page="Math.floor(filters.offset / filters.limit) + 1"
      :page-size="filters.limit"
      :total="total"
      :show-pagination="total > filters.limit"
      :page-sizes="[20, 50, 100]"
      pagination-label="协作任务分页"
      :loading="loading"
      :empty="!loading && rows.length === 0"
      empty-description="暂无符合条件的任务"
      @change="changePage"
    >
      <template #toolbar>
        <A2aListToolbar :loading="loading" @query="applyFilters" @refresh="reload">
          <LiquidGlassInput v-model="filters.search" clearable :prefix-icon="Search" placeholder="搜索任务、协作对象或调用身份" aria-label="搜索任务" @clear="applyFilters" />
          <LiquidGlassSelect v-model="filters.direction" class="short-filter" clearable placeholder="全部方向" dropdown-title="协作方向" aria-label="协作方向" @change="applyFilters"><el-option label="对外服务" value="INBOUND" /><el-option label="外部协作" value="OUTBOUND" /></LiquidGlassSelect>
          <LiquidGlassSelect v-model="filters.state" class="short-filter" clearable placeholder="全部状态" dropdown-title="任务状态" aria-label="任务状态" @change="applyFilters">
            <el-option v-for="state in taskStates" :key="state" :value="state" :label="a2aLabel(state)" />
          </LiquidGlassSelect>
          <LiquidGlassInput v-model="filters.tenantScope" class="short-filter" clearable placeholder="租户范围" aria-label="租户范围" @clear="applyFilters" />
        </A2aListToolbar>
      </template>
      <el-table :data="rows" class="task-list-table" stripe size="small" empty-text=" " @row-click="openDetail">
        <el-table-column prop="submittedAt" label="受理时间" width="160"><template #default="{ row }">{{ a2aFormatDate(row.submittedAt) }}</template></el-table-column>
        <el-table-column prop="direction" label="方向" width="106"><template #default="{ row }">{{ row.direction === 'INBOUND' ? '对外服务' : '外部协作' }}</template></el-table-column>
        <el-table-column label="协作对象" min-width="200" show-overflow-tooltip><template #default="{ row }">{{ row.publicationKey || row.remoteAgentKey || '—' }}</template></el-table-column>
        <el-table-column label="状态" width="104"><template #default="{ row }"><A2aTaskStateBadge :state="row.state" /></template></el-table-column>
        <el-table-column prop="taskId" label="任务 ID" min-width="220"><template #default="{ row }"><span class="task-identifier" :title="row.taskId">{{ a2aCompactId(row.taskId) }}</span></template></el-table-column>
        <el-table-column label="操作" width="76" fixed="right"><template #default="{ row }"><el-button link type="primary" @click.stop="openDetail(row)">查看</el-button></template></el-table-column>
      </el-table>
    </LiquidGlassTable>

    <AppDrawer v-model="detailVisible" title="协作任务详情" size="min(1040px, 95vw)">
      <div v-if="detail" v-loading="detailLoading" class="task-detail glass-loading-surface">
        <LiquidGlassNotice v-if="detail.eventGapDetected" type="error" title="检测到事件序号缺口：当前投影可能不完整，请先检查 outbox 与数据库一致性。" />
        <div class="task-heading">
          <div><small>{{ a2aLabel(detail.task.direction) }} · {{ detail.task.publicationKey || detail.task.remoteAgentKey }}</small><h3>{{ detail.task.taskId }}</h3><p>{{ detail.task.statusSummary || '暂无安全摘要' }}</p></div>
          <div class="task-actions"><A2aTaskStateBadge :state="detail.task.state" effect="dark" /><el-button v-if="detail.runtime.traceId" @click="goTrace">打开 Trace</el-button><el-button v-if="!a2aTaskTerminal(detail.task.state)" type="warning" @click="requestCancel">请求取消</el-button></div>
        </div>
        <WorkbenchPanel title="身份与运行证据" density="compact">
          <el-descriptions :column="3" border size="small">
            <el-descriptions-item label="Principal">{{ detail.identity.principalDisplayName }} ({{ detail.identity.principalKey }})</el-descriptions-item>
            <el-descriptions-item label="租户">{{ detail.identity.tenantScope || '全局' }}</el-descriptions-item>
            <el-descriptions-item label="Trust Profile">{{ detail.identity.trustProfileKey || `#${detail.identity.trustProfileId}` }}</el-descriptions-item>
            <el-descriptions-item label="Execution">{{ detail.runtime.executionId }}</el-descriptions-item>
            <el-descriptions-item label="Workflow Run">{{ detail.runtime.runId || '-' }}</el-descriptions-item>
            <el-descriptions-item label="Trace">{{ detail.runtime.traceId || '-' }}</el-descriptions-item>
            <el-descriptions-item label="Context">{{ detail.task.contextId || '-' }}</el-descriptions-item>
            <el-descriptions-item v-if="detail.task.errorCode" label="错误码"><span class="error-text">{{ detail.task.errorCode }}</span></el-descriptions-item>
            <el-descriptions-item label="受理">{{ a2aFormatDate(detail.task.submittedAt) }}</el-descriptions-item>
            <el-descriptions-item label="截止">{{ a2aFormatDate(detail.task.deadlineAt) }}</el-descriptions-item>
            <el-descriptions-item label="取消阶段">{{ detail.task.cancelPhase || '-' }}</el-descriptions-item>
            <el-descriptions-item v-if="detail.task.direction === 'OUTBOUND'" label="远端同步">{{ pollStatusLabel(detail.task.outboundPollStatus) }}</el-descriptions-item>
            <el-descriptions-item v-if="detail.task.direction === 'OUTBOUND'" label="查询次数">{{ detail.task.outboundPollAttemptCount }}</el-descriptions-item>
            <el-descriptions-item v-if="detail.task.direction === 'OUTBOUND'" label="下次查询">{{ a2aFormatDate(detail.task.outboundNextPollAt) }}</el-descriptions-item>
            <el-descriptions-item v-if="detail.task.direction === 'OUTBOUND'" label="最近查询">{{ a2aFormatDate(detail.task.outboundLastPolledAt) }}</el-descriptions-item>
            <el-descriptions-item v-if="detail.task.direction === 'OUTBOUND'" label="查询错误">{{ detail.task.outboundLastPollErrorCode || '-' }}</el-descriptions-item>
            <el-descriptions-item v-if="detail.task.direction === 'OUTBOUND'" label="错误摘要">{{ detail.task.outboundLastPollErrorSummary || '-' }}</el-descriptions-item>
          </el-descriptions>
        </WorkbenchPanel>

        <LiquidGlassTabs v-model="activeTab" class="task-tabs">
          <el-tab-pane :label="`时间线 ${detail.events.length}`" name="timeline"><TaskTimeline :events="detail.events" /></el-tab-pane>
          <el-tab-pane :label="`消息 ${detail.messages.length}`" name="messages">
            <el-table :data="detail.messages" size="small">
              <el-table-column prop="role" label="角色" width="80" />
              <el-table-column prop="messageId" label="Message ID" min-width="190" show-overflow-tooltip />
              <el-table-column prop="safeSummary" label="安全摘要" min-width="180" show-overflow-tooltip />
              <el-table-column label="大小 / 保留期" width="180"><template #default="{ row }"><div class="cell-stack"><strong>{{ a2aFormatBytes(row.payloadBytes) }}</strong><small>{{ a2aFormatDate(row.retentionExpiresAt) }}</small></div></template></el-table-column>
              <el-table-column label="正文" width="90"><template #default="{ row }"><el-button link type="primary" :disabled="!row.contentAvailable" @click="revealMessage(row)">{{ row.contentAvailable ? '审计查看' : '不可用' }}</el-button></template></el-table-column>
            </el-table>
          </el-tab-pane>
          <el-tab-pane :label="`产物 ${detail.artifacts.length}`" name="artifacts">
            <el-table :data="detail.artifacts" size="small">
              <el-table-column prop="name" label="名称" min-width="140" />
              <el-table-column prop="artifactId" label="Artifact ID" min-width="180" show-overflow-tooltip />
              <el-table-column prop="mediaTypes" label="媒体类型" min-width="150"><template #default="{ row }">{{ row.mediaTypes.join(', ') }}</template></el-table-column>
              <el-table-column label="大小" width="90"><template #default="{ row }">{{ a2aFormatBytes(row.payloadBytes) }}</template></el-table-column>
              <el-table-column label="正文" width="90"><template #default="{ row }"><el-button link type="primary" :disabled="!row.contentAvailable" @click="revealArtifact(row)">{{ row.contentAvailable ? '审计查看' : '不可用' }}</el-button></template></el-table-column>
            </el-table>
          </el-tab-pane>
        </LiquidGlassTabs>
      </div>
    </AppDrawer>

    <AppDialog v-model="payloadVisible" :title="payloadTitle" description="此操作需要 a2a-hub:payload:read 权限并已写入平台审计；窗口关闭后页面不缓存正文。" width="min(780px, 92vw)" @closed="payload = null">
      <div v-loading="payloadLoading" class="glass-loading-surface">
        <LiquidGlassNotice type="warning" title="正文仅用于必要调查，请勿复制凭据、令牌或个人敏感数据到工单与聊天。" />
        <div v-if="payload" class="payload-meta">{{ payload.mediaType }} · {{ a2aFormatBytes(payload.bytes) }} · SHA-256 {{ payload.sha256 }}</div>
        <pre v-if="payload" class="payload-viewer">{{ JSON.stringify(payload.content, null, 2) }}</pre>
      </div>
    </AppDialog>
  </section>
</template>

<style scoped lang="scss">
.task-center, .task-detail { display: flex; flex-direction: column; gap: var(--layout-page-gap); }
.task-heading, .task-actions { display: flex; align-items: center; justify-content: space-between; gap: 10px; }
.task-heading h3, .task-heading p { margin: 0; }
.task-identifier { display: block; overflow: hidden; color: var(--text-muted); font: 12px/1.5 var(--font-family-mono, ui-monospace, monospace); text-overflow: ellipsis; white-space: nowrap; }
.task-list-table :deep(.cell) { white-space: nowrap; }
.task-heading p { margin-top: 4px; color: var(--text-muted); font-size: 13px; }
/* 方向、状态、租户是短值，筛选条里收窄，把宽度留给搜索框。 */
.workbench-list-surface :deep(.filter-bar__fields > .liquid-glass-control.short-filter) { flex: 0 1 150px; max-width: 170px; }
.cell-stack strong, .cell-stack small { display: block; }
.cell-stack strong { color: var(--text-primary); }
.cell-stack small { margin-top: 3px; color: var(--text-muted); font-size: 11px; }
.error-text { color: var(--status-danger); }
.task-heading { align-items: flex-start; }
.task-heading small { color: var(--text-muted); }
.task-heading h3 { margin-top: 4px; font: 650 17px/1.4 ui-monospace, SFMono-Regular, Consolas, monospace; word-break: break-all; }
.task-actions { flex-wrap: wrap; justify-content: flex-end; }
.payload-meta { margin: 12px 0 8px; color: var(--text-muted); font-size: 12px; }
.payload-viewer { max-height: 480px; margin: 0; overflow: auto; padding: 14px; border: 1px solid var(--border-divider); border-radius: var(--radius-md); color: var(--code-text); background: var(--code-surface); font: 12px/1.65 ui-monospace, SFMono-Regular, Consolas, monospace; white-space: pre-wrap; word-break: break-word; }

@media (max-width: 900px) {
  .workbench-list-surface :deep(.filter-bar__fields > .liquid-glass-control.short-filter) { max-width: none; }
  .task-heading { flex-direction: column; }
  .task-actions { justify-content: flex-start; }
}
</style>
