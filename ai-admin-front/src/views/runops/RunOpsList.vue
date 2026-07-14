<template>
  <WorkbenchPage class="runops-page">
    <PageHeader
      variant="standard"
      compact
      :artwork="false"
      domain="governance"
      eyebrow="RunOps Center"
      title="RunOps 运行中心"
      description="查看 Supervisor 决策、Workflow 调用事件。"
    >
      <template #actions>
        <el-tooltip content="刷新运行数据" placement="top">
          <el-button
            circle
            :icon="Refresh"
            :loading="loading || diagnosticsLoading"
            aria-label="刷新运行数据"
            @click="refreshAll"
          />
        </el-tooltip>
      </template>
    </PageHeader>

    <section class="runops-kpis" aria-label="运行概览">
      <div v-for="(item, index) in kpis" :key="item.label" class="kpi-card" :class="`kpi-card--${index + 1}`">
        <div class="kpi-card__label"><span class="kpi-card__dot" />{{ item.label }}</div>
        <strong>{{ item.value }}</strong>
        <small>{{ item.hint }}</small>
      </div>
    </section>

    <el-card shadow="never" class="filter-card">
      <div class="section-heading">
        <div>
          <span class="section-kicker">RUN FILTERS</span>
          <h2>筛选运行记录</h2>
        </div>
        <el-tag effect="plain" round>当前匹配 {{ filteredRuns.length }} 条</el-tag>
      </div>
      <div class="filter-primary-row">
        <el-input
          v-model="draftFilters.keyword"
          class="keyword-filter"
          clearable
          :prefix-icon="Search"
          placeholder="搜索运行名称、Trace ID 或错误信息"
          @keyup.enter="applyFilters"
        />
        <el-select v-model="draftFilters.days" class="time-window-filter" aria-label="时间范围">
          <el-option label="最近 1 天" :value="1" />
          <el-option label="最近 7 天" :value="7" />
          <el-option label="最近 14 天" :value="14" />
          <el-option label="最近 30 天" :value="30" />
        </el-select>
        <div class="filter-buttons">
          <el-button type="primary" :icon="Search" :loading="loading || diagnosticsLoading" @click="applyFilters">查询</el-button>
          <el-button @click="resetFilters">重置</el-button>
        </div>
      </div>
      <div class="filter-grid">
        <label class="filter-field">
          <span>项目编码</span>
          <el-input v-model="draftFilters.projectCode" clearable placeholder="全部项目" />
        </label>
        <label class="filter-field">
          <span>运行状态</span>
          <el-select v-model="draftFilters.status" clearable placeholder="全部状态">
          <el-option label="运行中" value="RUNNING" />
          <el-option label="成功" value="SUCCESS" />
          <el-option label="失败" value="FAILED" />
          <el-option label="等待审批" value="WAITING_APPROVAL" />
          <el-option label="已取消" value="CANCELLED" />
          <el-option label="超时" value="TIMEOUT" />
        </el-select>
        </label>
        <label class="filter-field">
          <span>运行类型</span>
          <el-select v-model="draftFilters.runType" clearable placeholder="全部类型">
          <el-option label="Agent" value="AGENT" />
          <el-option label="Workflow" value="WORKFLOW" />
        </el-select>
        </label>
        <label class="filter-field">
          <span>运行入口</span>
          <el-select v-model="draftFilters.entryType" clearable placeholder="全部入口">
          <el-option v-for="entry in entryTypes" :key="entry" :label="entryTypeLabel(entry)" :value="entry" />
        </el-select>
        </label>
        <label class="filter-field">
          <span>Agent ID</span>
          <el-input v-model="draftFilters.agentId" clearable placeholder="全部 Agent" />
        </label>
        <label class="filter-field">
          <span>用户 ID</span>
          <el-input v-model="draftFilters.userId" clearable placeholder="全部用户" />
        </label>
      </div>
    </el-card>

    <section class="runops-diagnostics-grid">
      <el-row :gutter="16" class="diagnostics-row">
        <el-col :xs="24" :lg="12">
          <el-card shadow="never" class="diagnostics-card">
            <template #header>
              <div class="card-header">
                <span>失败聚类</span>
                <el-tag size="small" type="danger" effect="plain">{{ diagnostics.failureClusters.length }} 类</el-tag>
              </div>
            </template>
            <el-table class="runops-table" :data="diagnostics.failureClusters" v-loading="diagnosticsLoading" stripe height="320">
              <el-table-column prop="count" label="次数" width="72" />
              <el-table-column label="运行对象" min-width="190" show-overflow-tooltip>
                <template #default="{ row }">
                  <div class="issue-title">{{ diagnosticObjectLabel(row) }}</div>
                  <div class="issue-meta">{{ diagnosticVersionLabel(row) }}</div>
                </template>
              </el-table-column>
              <el-table-column label="错误" min-width="210" show-overflow-tooltip>
                <template #default="{ row }">
                  <div class="issue-title">{{ row.errorCode || row.errorType || 'RUN_FAILED' }}</div>
                  <div class="issue-meta">{{ row.errorMessage || row.nodeId || row.toolName || '-' }}</div>
                </template>
              </el-table-column>
              <el-table-column prop="avgLatencyMs" label="均耗时" width="96">
                <template #default="{ row }">{{ row.avgLatencyMs ?? 0 }} ms</template>
              </el-table-column>
              <el-table-column label="操作" width="72" fixed="right">
                <template #default="{ row }">
                  <el-button
                    v-if="row.sampleTraceId"
                    link
                    type="primary"
                    size="small"
                    @click="router.push(`/runops/${row.sampleTraceId}`)"
                  >
                    样例
                  </el-button>
                </template>
              </el-table-column>
            </el-table>
            <el-empty
              v-if="!diagnosticsLoading && !diagnostics.failureClusters.length"
              description="当前筛选下暂无失败聚类"
            />
          </el-card>
        </el-col>
        <el-col :xs="24" :lg="12">
          <el-card shadow="never" class="diagnostics-card">
            <template #header>
              <div class="card-header">
                <span>发布版本表现</span>
                <el-tag size="small" effect="plain">{{ diagnostics.versionComparisons.length }} 个版本</el-tag>
              </div>
            </template>
            <el-table class="runops-table" :data="diagnostics.versionComparisons" v-loading="diagnosticsLoading" stripe height="320">
              <el-table-column label="运行对象 / 版本" min-width="210" show-overflow-tooltip>
                <template #default="{ row }">
                  <div class="issue-title">{{ diagnosticObjectLabel(row) }}</div>
                  <div class="issue-meta">{{ diagnosticVersionLabel(row) }}</div>
                </template>
              </el-table-column>
              <el-table-column label="成功率" width="116">
                <template #default="{ row }">
                  <el-progress
                    :percentage="toPercent(row.successRate)"
                    :stroke-width="8"
                    :show-text="false"
                    :status="toPercent(row.successRate) >= 90 ? 'success' : undefined"
                  />
                  <span class="rate-text">{{ toPercent(row.successRate) }}%</span>
                </template>
              </el-table-column>
              <el-table-column prop="runCount" label="运行" width="68" />
              <el-table-column prop="failureCount" label="失败" width="68" />
              <el-table-column prop="replanCount" label="重规划" width="76" />
              <el-table-column prop="p95LatencyMs" label="P95" width="88">
                <template #default="{ row }">{{ row.p95LatencyMs ?? 0 }} ms</template>
              </el-table-column>
              <el-table-column label="操作" width="72" fixed="right">
                <template #default="{ row }">
                  <el-button
                    v-if="row.latestTraceId"
                    link
                    type="primary"
                    size="small"
                    @click="router.push(`/runops/${row.latestTraceId}`)"
                  >
                    最新
                  </el-button>
                </template>
              </el-table-column>
            </el-table>
            <el-empty
              v-if="!diagnosticsLoading && !diagnostics.versionComparisons.length"
              description="当前筛选下暂无版本表现数据"
            />
          </el-card>
        </el-col>
      </el-row>
    </section>

    <el-card shadow="never" class="runs-card">
      <template #header>
        <div class="card-header">
          <span>根运行记录</span>
          <el-tag size="small" effect="plain">{{ filteredRuns.length }} 条</el-tag>
        </div>
      </template>
      <el-table class="runops-table runs-table" :data="pagedRuns" v-loading="loading" stripe>
        <el-table-column prop="status" label="状态" width="118">
          <template #default="{ row }">
            <el-tag :type="statusTagType(row.status)" size="small">{{ statusLabel(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="类型" width="92">
          <template #default="{ row }">
            <el-tag size="small" effect="plain" :type="row.runType === 'AGENT' ? 'success' : 'primary'">
              {{ row.runType }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="Agent / Workflow" min-width="210" show-overflow-tooltip>
          <template #default="{ row }">
            <div class="issue-title">{{ runObjectLabel(row) }}</div>
            <div class="issue-meta">{{ runObjectId(row) }}</div>
          </template>
        </el-table-column>
        <el-table-column label="发布版本" width="130" show-overflow-tooltip>
          <template #default="{ row }">{{ runVersionLabel(row) }}</template>
        </el-table-column>
        <el-table-column prop="projectCode" label="项目" width="130" show-overflow-tooltip>
          <template #default="{ row }">{{ row.projectCode || '-' }}</template>
        </el-table-column>
        <el-table-column label="入口" width="100">
          <template #default="{ row }">{{ entryTypeLabel(row.entryType) }}</template>
        </el-table-column>
        <el-table-column label="决策 / 调用" min-width="210">
          <template #default="{ row }">
            <div class="count-tags">
              <el-tag size="small" effect="plain">规划 {{ row.planCount ?? 0 }}</el-tag>
              <el-tag size="small" effect="plain" type="warning">重规划 {{ row.replanCount ?? 0 }}</el-tag>
              <el-tag size="small" effect="plain" type="success">Workflow {{ row.workflowCallCount ?? 0 }}</el-tag>
              <el-tag v-if="row.approvalCount" size="small" effect="plain" type="warning">审批 {{ row.approvalCount }}</el-tag>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="耗时 / Token" width="130">
          <template #default="{ row }">
            <div>{{ row.latencyMs ?? 0 }} ms</div>
            <div class="issue-meta">{{ row.tokenCost ?? 0 }} token</div>
          </template>
        </el-table-column>
        <el-table-column label="错误" min-width="190" show-overflow-tooltip>
          <template #default="{ row }">
            <span v-if="row.errorCode || row.errorMessage" class="error-text">
              {{ row.errorCode || 'RUN_FAILED' }} · {{ row.errorMessage || '-' }}
            </span>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column prop="startedAt" label="开始时间" width="180" />
        <el-table-column prop="traceId" label="Trace" min-width="220" show-overflow-tooltip />
        <el-table-column label="操作" width="142" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="router.push(`/runops/${row.traceId}`)">详情</el-button>
            <el-button
              v-if="row.runType === 'WORKFLOW' && row.workflowId"
              link
              type="primary"
              size="small"
              @click="router.push(`/workflows/${row.workflowId}/studio`)"
            >
              Studio
            </el-button>
          </template>
        </el-table-column>
      </el-table>
      <div class="table-footer">
        <el-pagination
          v-model:current-page="currentPage"
          v-model:page-size="pageSize"
          :page-sizes="[10, 20, 50]"
          layout="total, sizes, prev, pager, next"
          :total="filteredRuns.length"
        />
      </div>
      <el-empty v-if="!loading && !filteredRuns.length" description="当前筛选下暂无根运行记录" />
    </el-card>
  </WorkbenchPage>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Refresh, Search } from '@element-plus/icons-vue'
import { getRecentRunOps, getRunOpsDiagnostics } from '@/api/runops'
import type {
  FailureCluster,
  RunDiagnostics,
  RunEntryType,
  RunOpsQueryParams,
  RunStatus,
  RunSummary,
  VersionComparison,
} from '@/types/runops'
import PageHeader from '@/components/common/PageHeader.vue'
import WorkbenchPage from '@/components/common/WorkbenchPage.vue'
import { useProjectStore } from '@/store/project'

const router = useRouter()
const route = useRoute()
const projectStore = useProjectStore()
const loading = ref(false)
const diagnosticsLoading = ref(false)
const runs = ref<RunSummary[]>([])
const currentPage = ref(1)
const pageSize = ref(10)
const entryTypes: RunEntryType[] = ['DEBUG', 'EMBED', 'GATEWAY', 'EVAL', 'REPLAY', 'API']
const diagnostics = ref<RunDiagnostics>({ failureClusters: [], versionComparisons: [] })

type FilterState = {
  projectCode: string
  status: '' | RunStatus
  runType: '' | 'AGENT' | 'WORKFLOW'
  entryType: '' | RunEntryType
  agentId: string
  userId: string
  keyword: string
  days: number
}

function initialProjectCode() {
  const routeCode = typeof route.query.projectCode === 'string' ? route.query.projectCode.trim() : ''
  return routeCode || projectStore.currentProjectCode || ''
}

const draftFilters = reactive<FilterState>({
  projectCode: initialProjectCode(),
  status: '',
  runType: '',
  entryType: '',
  agentId: typeof route.query.agentId === 'string' ? route.query.agentId.trim() : '',
  userId: '',
  keyword: '',
  days: 7,
})

const appliedFilters = reactive<FilterState>({ ...draftFilters })

const requestParams = computed<RunOpsQueryParams>(() => ({
  projectCode: appliedFilters.projectCode.trim() || undefined,
  status: appliedFilters.status || undefined,
  runType: appliedFilters.runType || undefined,
  entryType: appliedFilters.entryType || undefined,
  agentId: appliedFilters.agentId.trim() || undefined,
  userId: appliedFilters.userId.trim() || undefined,
  keyword: appliedFilters.keyword.trim() || undefined,
  days: appliedFilters.days,
  limit: 200,
}))

const filteredRuns = computed(() => runs.value)

const pagedRuns = computed(() => {
  const start = (currentPage.value - 1) * pageSize.value
  return filteredRuns.value.slice(start, start + pageSize.value)
})

const kpis = computed(() => {
  const total = filteredRuns.value.length
  const failed = filteredRuns.value.filter((run) => ['FAILED', 'TIMEOUT'].includes(run.status)).length
  const waiting = filteredRuns.value.filter((run) => run.status === 'WAITING_APPROVAL').length
  const avgLatency = total
    ? Math.round(filteredRuns.value.reduce((sum, run) => sum + (run.latencyMs ?? 0), 0) / total)
    : 0
  return [
    { label: '运行数', value: total, hint: `最近 ${appliedFilters.days} 天` },
    { label: '失败数', value: failed, hint: '失败与超时的根运行' },
    { label: '等待审批', value: waiting, hint: '当前阻塞于人工审批' },
    { label: '平均耗时', value: `${avgLatency} ms`, hint: '按根运行聚合' },
  ]
})

async function loadRuns() {
  loading.value = true
  try {
    const { data } = await getRecentRunOps(requestParams.value)
    runs.value = data ?? []
  } catch {
    runs.value = []
    ElMessage.error('加载 RunOps 根运行列表失败')
  } finally {
    loading.value = false
  }
}

async function loadDiagnostics() {
  diagnosticsLoading.value = true
  try {
    const { data } = await getRunOpsDiagnostics(requestParams.value)
    diagnostics.value = data ?? { failureClusters: [], versionComparisons: [] }
  } catch {
    diagnostics.value = { failureClusters: [], versionComparisons: [] }
    ElMessage.error('加载 RunOps 诊断数据失败')
  } finally {
    diagnosticsLoading.value = false
  }
}

async function refreshAll() {
  await Promise.all([loadRuns(), loadDiagnostics()])
}

function applyFilters() {
  Object.assign(appliedFilters, {
    ...draftFilters,
    projectCode: draftFilters.projectCode.trim(),
    agentId: draftFilters.agentId.trim(),
    userId: draftFilters.userId.trim(),
    keyword: draftFilters.keyword.trim(),
  })
  currentPage.value = 1
  void refreshAll()
}

function resetFilters() {
  const projectCode = initialProjectCode()
  Object.assign(draftFilters, {
    projectCode,
    status: '',
    runType: '',
    entryType: '',
    agentId: '',
    userId: '',
    days: 7,
  })
  draftFilters.keyword = ''
  applyFilters()
}

function runObjectLabel(run: RunSummary) {
  return run.runType === 'AGENT'
    ? run.agentName || run.agentKeySlug || run.agentId || '-'
    : run.workflowName || run.workflowKeySlug || run.workflowId || '-'
}

function runObjectId(run: RunSummary) {
  return run.runType === 'AGENT'
    ? run.agentKeySlug || run.agentId || '-'
    : run.workflowKeySlug || run.workflowId || '-'
}

function runVersionLabel(run: RunSummary) {
  const version = run.runType === 'AGENT' ? run.agentConfigVersion : run.workflowVersion
  const versionId = run.runType === 'AGENT' ? run.agentConfigVersionId : run.workflowVersionId
  if (!version && versionId == null) return '-'
  return `${version || '-'}${versionId == null ? '' : ` · #${versionId}`}`
}

function diagnosticObjectLabel(row: FailureCluster | VersionComparison) {
  return row.versionType === 'WORKFLOW'
    ? row.workflowName || row.workflowId || '-'
    : row.agentName || row.agentId || '-'
}

function diagnosticVersionLabel(row: FailureCluster | VersionComparison) {
  const version = row.versionType === 'WORKFLOW' ? row.workflowVersion : row.agentConfigVersion
  const versionId = row.versionType === 'WORKFLOW' ? row.workflowVersionId : row.agentConfigVersionId
  if (!version && versionId == null) return '未记录发布版本'
  return `${version || '-'}${versionId == null ? '' : ` · #${versionId}`}`
}

function entryTypeLabel(entryType?: RunEntryType) {
  const labels: Record<RunEntryType, string> = {
    DEBUG: '调试',
    EMBED: '嵌入',
    GATEWAY: '网关',
    EVAL: '评测',
    REPLAY: '重放',
    API: 'API',
  }
  return entryType ? labels[entryType] : '-'
}

function statusLabel(status: RunStatus) {
  const labels: Record<RunStatus, string> = {
    RUNNING: '运行中',
    SUCCESS: '成功',
    FAILED: '失败',
    WAITING_APPROVAL: '等待审批',
    CANCELLED: '已取消',
    TIMEOUT: '超时',
  }
  return labels[status]
}

function statusTagType(status?: string) {
  if (status === 'SUCCESS') return 'success'
  if (status === 'RUNNING') return 'primary'
  if (status === 'WAITING_APPROVAL') return 'warning'
  if (status === 'CANCELLED') return 'info'
  return 'danger'
}

function toPercent(value?: number) {
  return Math.round((value ?? 0) * 100)
}

onMounted(async () => {
  if (!projectStore.projects.length) {
    await projectStore.fetchProjects()
    if (!draftFilters.projectCode && projectStore.currentProjectCode) {
      draftFilters.projectCode = projectStore.currentProjectCode
      appliedFilters.projectCode = projectStore.currentProjectCode
    }
  }
  await refreshAll()
})

watch(filteredRuns, () => {
  currentPage.value = 1
})
</script>

<style scoped lang="scss">
.runops-page {
  --runops-accent: #635bdb;
  --runops-ink: #17233d;
  --runops-muted: #71809a;
  gap: 16px;
}

.runops-kpis {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
}

.kpi-card {
  position: relative;
  min-height: 126px;
  overflow: hidden;
  padding: 18px 20px;
  border: 1px solid var(--border-divider);
  border-radius: 14px;
  background: var(--surface-solid-panel);
  box-shadow: 0 12px 28px -28px rgb(15 23 42 / 55%);
}

.kpi-card::after {
  position: absolute;
  right: -24px;
  bottom: -34px;
  width: 110px;
  height: 110px;
  border-radius: 50%;
  background: color-mix(in srgb, var(--kpi-tone) 12%, transparent);
  content: '';
}

.kpi-card--1 { --kpi-tone: #635bdb; }
.kpi-card--2 { --kpi-tone: #e05858; }
.kpi-card--3 { --kpi-tone: #d28a28; }
.kpi-card--4 { --kpi-tone: #2f9b7a; }

.kpi-card__label {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--runops-muted);
  font-size: 13px;
  font-weight: 650;
}

.kpi-card__dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--kpi-tone);
  box-shadow: 0 0 0 4px color-mix(in srgb, var(--kpi-tone) 12%, transparent);
}

.kpi-card strong {
  display: block;
  margin: 14px 0 5px;
  color: var(--runops-ink);
  font-size: 28px;
  font-weight: 760;
  letter-spacing: -0.04em;
}

.kpi-card small {
  color: var(--runops-muted);
  font-size: 12px;
}

.filter-grid {
  display: grid;
  grid-template-columns: repeat(6, minmax(0, 1fr));
  gap: 10px;
  margin-top: 16px;
  padding-top: 15px;
  border-top: 1px solid var(--border-divider);
}

.filter-primary-row {
  display: flex;
  align-items: center;
  gap: 10px;
}

.keyword-filter {
  flex: 1 1 420px;
  min-width: 220px;
}

.time-window-filter {
  width: 136px;
}

.filter-field {
  display: grid;
  gap: 6px;
  min-width: 0;
  color: var(--runops-muted);
  font-size: 12px;
  font-weight: 650;
}

.filter-field :deep(.el-select) {
  width: 100%;
}

.filter-card,
.diagnostics-card,
.runs-card {
  border: 1px solid var(--border-divider);
  border-radius: 14px;
  background: color-mix(in srgb, var(--surface-solid-panel) 94%, transparent);
  box-shadow: 0 14px 30px -30px rgb(15 23 42 / 60%);
}

.section-heading {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 16px;
}

.section-heading h2 {
  margin: 3px 0 0;
  color: var(--runops-ink);
  font-size: 16px;
  font-weight: 750;
}

.section-kicker {
  color: var(--runops-accent);
  font-size: 10px;
  font-weight: 800;
  letter-spacing: 0.12em;
}

.filter-buttons,
.card-header,
.count-tags {
  display: flex;
  align-items: center;
  gap: 10px;
}

.card-header {
  justify-content: space-between;
}

.diagnostics-card {
  height: 100%;
}

.diagnostics-card :deep(.el-card__header),
.runs-card :deep(.el-card__header) {
  padding: 16px 20px;
  border-bottom-color: var(--border-divider);
}

.diagnostics-card :deep(.el-card__body),
.runs-card :deep(.el-card__body) {
  padding: 0 20px 18px;
}

.runops-table {
  --el-table-border-color: var(--border-divider);
  --el-table-header-bg-color: color-mix(in srgb, var(--runops-accent) 5%, var(--surface-solid-control));
  --el-table-row-hover-bg-color: color-mix(in srgb, var(--runops-accent) 6%, var(--surface-solid-panel));
  color: var(--text-primary);
}

.runops-table :deep(th.el-table__cell) {
  height: 42px;
  color: var(--runops-muted);
  font-size: 12px;
  font-weight: 700;
}

.runops-table :deep(td.el-table__cell) {
  height: 62px;
  padding: 8px 0;
}

.runops-table :deep(.cell) {
  line-height: 1.35;
}

.runs-table :deep(td.el-table__cell) {
  height: 70px;
}

.runops-table :deep(.el-table-fixed-column--right) {
  background: var(--surface-solid-panel) !important;
  box-shadow: -8px 0 16px -16px rgb(15 23 42 / 45%);
}

.issue-title {
  font-weight: 600;
  color: var(--text-primary);
}

.issue-meta {
  margin-top: 4px;
  color: var(--text-secondary);
  font-size: 12px;
}

.rate-text {
  display: inline-block;
  margin-top: 4px;
  color: var(--text-secondary);
  font-size: 12px;
}

.error-text {
  color: var(--el-color-danger);
}

.count-tags {
  flex-wrap: wrap;
  gap: 6px;
}

.table-footer {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}

@media (max-width: 980px) {
  .runops-kpis,
  .filter-grid {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
}

@media (max-width: 640px) {
  .runops-kpis,
  .filter-grid {
    grid-template-columns: 1fr;
  }

  .filter-primary-row {
    align-items: stretch;
    flex-wrap: wrap;
  }

  .keyword-filter {
    flex-basis: 100%;
  }

  .time-window-filter {
    flex: 1;
  }

  .filter-buttons {
    flex: 1;
    justify-content: flex-end;
  }

  .section-heading {
    align-items: flex-start;
    flex-direction: column;
  }
}
</style>
