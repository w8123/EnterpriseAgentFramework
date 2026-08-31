<script setup lang="ts">
import { onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh, Search } from '@element-plus/icons-vue'
import AppDialog from '@/components/common/AppDialog.vue'
import AppDrawer from '@/components/common/AppDrawer.vue'
import WorkbenchPanel from '@/components/common/WorkbenchPanel.vue'
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
const filters = reactive({ search: '', direction: '', state: '', tenantScope: '', limit: 50, offset: 0 })

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

function applyFilters() {
  filters.offset = 0
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
    delete next.direction
    router.replace({ query: next })
  }
})

onMounted(async () => {
  const deepLink = a2aTaskCenterDeepLink(route.query)
  if (deepLink) {
    filters.search = deepLink.taskId
    filters.direction = deepLink.direction
  }
  await reload()
  if (deepLink) await openDetail(deepLink)
})
</script>

<template>
  <section class="task-center">
    <div class="section-intro">
      <div><h2>任务中心</h2><p>按协议 Task 还原身份、策略、Runtime、消息与 Artifact 的完整证据链。</p></div>
      <el-button :icon="Refresh" :loading="loading" @click="reload">刷新</el-button>
    </div>

    <WorkbenchPanel level="control" density="compact">
      <div class="filter-row">
        <el-input v-model="filters.search" clearable :prefix-icon="Search" placeholder="taskId、contextId、traceId、Principal 或错误码" @keyup.enter="applyFilters" />
        <el-select v-model="filters.direction" clearable placeholder="全部方向" @change="applyFilters"><el-option label="入站" value="INBOUND" /><el-option label="出站" value="OUTBOUND" /></el-select>
        <el-select v-model="filters.state" clearable placeholder="全部状态" @change="applyFilters">
          <el-option v-for="state in ['TASK_STATE_SUBMITTED','TASK_STATE_WORKING','TASK_STATE_INPUT_REQUIRED','TASK_STATE_AUTH_REQUIRED','TASK_STATE_COMPLETED','TASK_STATE_FAILED','TASK_STATE_CANCELED','TASK_STATE_REJECTED']" :key="state" :value="state" :label="a2aLabel(state)" />
        </el-select>
        <el-input v-model="filters.tenantScope" clearable placeholder="租户范围" @keyup.enter="applyFilters" />
        <el-button type="primary" @click="applyFilters">查询</el-button>
      </div>
    </WorkbenchPanel>

    <el-card class="workbench-list-surface" shadow="never">
      <el-table :data="rows" v-loading="loading" stripe size="small" @row-click="openDetail">
        <el-table-column prop="submittedAt" label="受理时间" width="165"><template #default="{ row }">{{ a2aFormatDate(row.submittedAt) }}</template></el-table-column>
        <el-table-column prop="direction" label="方向" width="66"><template #default="{ row }">{{ a2aLabel(row.direction) }}</template></el-table-column>
        <el-table-column prop="taskId" label="Task / Context" min-width="220"><template #default="{ row }"><div class="cell-stack"><strong>{{ row.taskId }}</strong><small>{{ row.contextId }}</small></div></template></el-table-column>
        <el-table-column label="状态" width="104"><template #default="{ row }"><A2aTaskStateBadge :state="row.state" /></template></el-table-column>
        <el-table-column label="出站同步" width="132"><template #default="{ row }"><span v-if="row.direction === 'OUTBOUND'">{{ pollStatusLabel(row.outboundPollStatus) }}</span><span v-else>-</span></template></el-table-column>
        <el-table-column label="协作对象" min-width="150"><template #default="{ row }"><div class="cell-stack"><strong>{{ row.publicationKey || row.remoteAgentKey || '-' }}</strong><small>{{ row.principalKey }}</small></div></template></el-table-column>
        <el-table-column prop="statusSummary" label="安全摘要" min-width="170" show-overflow-tooltip />
        <el-table-column label="Runtime" min-width="150"><template #default="{ row }"><div class="cell-stack"><strong>{{ row.runtimeRunId || '无 Workflow Run' }}</strong><small>{{ row.traceId || '-' }}</small></div></template></el-table-column>
        <el-table-column label="错误" min-width="130"><template #default="{ row }"><span class="error-text">{{ row.errorCode || '-' }}</span></template></el-table-column>
        <el-table-column label="操作" width="64" fixed="right"><template #default="{ row }"><el-button link type="primary" @click.stop="openDetail(row)">调查</el-button></template></el-table-column>
      </el-table>
      <el-pagination
        :current-page="Math.floor(filters.offset / filters.limit) + 1"
        :page-size="filters.limit"
        :total="total"
        :page-sizes="[20, 50, 100, 200]"
        layout="total, sizes, prev, pager, next"
        @current-change="(page: number) => { filters.offset = (page - 1) * filters.limit; reload() }"
        @size-change="(size: number) => { filters.limit = size; filters.offset = 0; reload() }"
      />
    </el-card>

    <AppDrawer v-model="detailVisible" title="A2A Task 调查" size="min(1040px, 95vw)">
      <div v-if="detail" v-loading="detailLoading" class="task-detail">
        <el-alert v-if="detail.eventGapDetected" type="error" :closable="false" show-icon title="检测到事件序号缺口：当前投影可能不完整，请先检查 outbox 与数据库一致性。" />
        <div class="task-heading">
          <div><small>{{ a2aLabel(detail.task.direction) }} · {{ detail.task.publicationKey || detail.task.remoteAgentKey }}</small><h3>{{ detail.task.taskId }}</h3><p>{{ detail.task.statusSummary || '暂无安全摘要' }}</p></div>
          <div class="task-actions"><A2aTaskStateBadge :state="detail.task.state" effect="dark" /><el-button v-if="detail.runtime.traceId" @click="goTrace">打开 Trace</el-button><el-button v-if="!a2aTaskTerminal(detail.task.state)" type="warning" @click="requestCancel">请求取消</el-button></div>
        </div>
        <el-descriptions :column="3" border size="small">
          <el-descriptions-item label="Principal">{{ detail.identity.principalDisplayName }} ({{ detail.identity.principalKey }})</el-descriptions-item>
          <el-descriptions-item label="租户">{{ detail.identity.tenantScope || '全局' }}</el-descriptions-item>
          <el-descriptions-item label="Trust Profile">{{ detail.identity.trustProfileKey || `#${detail.identity.trustProfileId}` }}</el-descriptions-item>
          <el-descriptions-item label="Execution">{{ detail.runtime.executionId }}</el-descriptions-item>
          <el-descriptions-item label="Workflow Run">{{ detail.runtime.runId || '-' }}</el-descriptions-item>
          <el-descriptions-item label="Trace">{{ detail.runtime.traceId || '-' }}</el-descriptions-item>
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

        <el-tabs v-model="activeTab">
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
        </el-tabs>
      </div>
    </AppDrawer>

    <AppDialog v-model="payloadVisible" :title="payloadTitle" description="此操作需要 a2a-hub:payload:read 权限并已写入平台审计；窗口关闭后页面不缓存正文。" width="min(780px, 92vw)" @closed="payload = null">
      <div v-loading="payloadLoading">
        <el-alert type="warning" :closable="false" show-icon title="正文仅用于必要调查，请勿复制凭据、令牌或个人敏感数据到工单与聊天。" />
        <div v-if="payload" class="payload-meta">{{ payload.mediaType }} · {{ a2aFormatBytes(payload.bytes) }} · SHA-256 {{ payload.sha256 }}</div>
        <pre v-if="payload" class="payload-viewer">{{ JSON.stringify(payload.content, null, 2) }}</pre>
      </div>
    </AppDialog>
  </section>
</template>

<style scoped lang="scss">
.task-center, .task-detail { display: flex; flex-direction: column; gap: var(--layout-page-gap); }
.section-intro, .filter-row, .task-heading, .task-actions { display: flex; align-items: center; justify-content: space-between; gap: 10px; }
.section-intro { align-items: flex-start; }
.section-intro h2, .section-intro p, .task-heading h3, .task-heading p { margin: 0; }
.section-intro h2 { font-size: 18px; }
.section-intro p, .task-heading p { margin-top: 4px; color: var(--text-muted); font-size: 13px; }
.filter-row > .el-input:first-child { flex: 1; min-width: 280px; }
.filter-row > .el-select { width: 150px; }
.filter-row > .el-input:not(:first-child) { width: 150px; }
.cell-stack strong, .cell-stack small { display: block; }
.cell-stack small { margin-top: 3px; color: var(--text-muted); font-size: 11px; }
.error-text { color: var(--status-danger); }
.workbench-list-surface :deep(.el-pagination) { justify-content: flex-end; margin-top: 12px; }
.task-heading { align-items: flex-start; }
.task-heading small { color: var(--text-muted); }
.task-heading h3 { margin-top: 4px; font: 650 17px/1.4 ui-monospace, SFMono-Regular, Consolas, monospace; word-break: break-all; }
.task-actions { flex-wrap: wrap; justify-content: flex-end; }
.payload-meta { margin: 12px 0 8px; color: var(--text-muted); font-size: 12px; }
.payload-viewer { max-height: 480px; margin: 0; overflow: auto; padding: 14px; border: 1px solid var(--border-divider); border-radius: var(--radius-md); color: var(--text-secondary); background: var(--surface-glass-control); font: 12px/1.65 ui-monospace, SFMono-Regular, Consolas, monospace; white-space: pre-wrap; word-break: break-word; }

@media (max-width: 900px) {
  .filter-row { align-items: stretch; flex-direction: column; }
  .filter-row > .el-input:first-child, .filter-row > .el-input:not(:first-child), .filter-row > .el-select { width: 100%; min-width: 0; }
  .task-heading { flex-direction: column; }
  .task-actions { justify-content: flex-start; }
}
</style>
