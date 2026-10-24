<template>
  <LiquidGlassPage class="runops-page" scrollable>
    <PageHeader
      variant="overview"
      presentation="plain"
      action-presentation="inline"
      domain="governance"
      title="运行中心"
      description="观测智能体决策、工作流调用与运行异常"
    >
      <template #meta>
        <div class="runops-header-meta">
          <RunCenterNav />
          <MetricStrip presentation="inline" source="wallpaper" :items="kpis" aria-label="运行概览" />
        </div>
      </template>
      <template #actions>
        <LiquidGlassButton
          source="wallpaper"
          :icon="Refresh"
          :loading="loading || diagnosticsLoading"
          aria-label="刷新运行数据"
          @click="refreshAll"
        >
          刷新
        </LiquidGlassButton>
      </template>
    </PageHeader>

    <LiquidGlassCard source="wallpaper" class="filter-card" aria-label="筛选运行记录">
      <LiquidGlassFilterBar
        :loading="loading || diagnosticsLoading"
        query-label="查询"
        @query="applyFilters"
        @reset="resetFilters"
      >
        <LiquidGlassInput
          v-model="draftFilters.keyword"
          class="keyword-filter"
          clearable
          :prefix-icon="Search"
          placeholder="搜索运行名称、追踪 ID 或错误信息"
          aria-label="搜索运行记录"
        />
        <LiquidGlassSelect v-model="draftFilters.days" class="compact-filter" dropdown-title="时间范围" aria-label="时间范围">
          <el-option label="最近 1 天" :value="1" />
          <el-option label="最近 7 天" :value="7" />
          <el-option label="最近 14 天" :value="14" />
          <el-option label="最近 30 天" :value="30" />
        </LiquidGlassSelect>
        <LiquidGlassSelect
          v-model="draftFilters.status"
          class="compact-filter"
          clearable
          placeholder="运行状态"
          dropdown-title="运行状态"
          aria-label="运行状态"
        >
          <el-option label="运行中" value="RUNNING" />
          <el-option label="已暂停" value="SUSPENDED" />
          <el-option label="已完成" value="COMPLETED" />
          <el-option label="失败" value="FAILED" />
          <el-option label="已取消" value="CANCELLED" />
          <el-option label="已超时" value="TIMED_OUT" />
        </LiquidGlassSelect>
        <LiquidGlassSelect
          v-model="draftFilters.runType"
          class="compact-filter"
          clearable
          placeholder="运行类型"
          dropdown-title="运行类型"
          aria-label="运行类型"
        >
          <el-option label="智能体" value="AGENT" />
          <el-option label="工作流" value="WORKFLOW" />
          <el-option label="MCP 工具调用" value="MCP" />
          <el-option label="业务方法试调用" value="CONSOLE_CAPABILITY" />
          <el-option label="API 试调用" value="CONSOLE_HTTP_API" />
        </LiquidGlassSelect>
        <LiquidGlassSelect
          v-model="draftFilters.entryType"
          class="compact-filter"
          clearable
          placeholder="运行入口"
          dropdown-title="运行入口"
          aria-label="运行入口"
        >
          <el-option v-for="entry in entryTypes" :key="entry" :label="entryTypeLabel(entry)" :value="entry" />
        </LiquidGlassSelect>
        <template #actions>
          <LiquidGlassButton
            link
            native-type="button"
            :aria-expanded="showMoreFilters"
            aria-controls="runops-advanced-filters"
            @click="showMoreFilters = !showMoreFilters"
          >
            {{ showMoreFilters ? '收起筛选' : '更多筛选' }}
            <span v-if="advancedFilterCount > 0" class="advanced-count">{{ advancedFilterCount }}</span>
          </LiquidGlassButton>
          <LiquidGlassButton link native-type="button" @click="resetFilters">重置</LiquidGlassButton>
          <LiquidGlassButton type="primary" native-type="submit" :icon="Search" :loading="loading || diagnosticsLoading">
            查询
          </LiquidGlassButton>
        </template>
      </LiquidGlassFilterBar>

      <div id="runops-advanced-filters" v-show="showMoreFilters" class="filter-advanced">
        <label class="filter-field">
          <span>项目编码</span>
          <LiquidGlassInput v-model="draftFilters.projectCode" clearable placeholder="全部项目" @keyup.enter="applyFilters" />
        </label>
        <label class="filter-field">
          <span>智能体 ID</span>
          <LiquidGlassInput v-model="draftFilters.agentId" clearable placeholder="全部智能体" @keyup.enter="applyFilters" />
        </label>
        <label class="filter-field">
          <span>用户 ID</span>
          <LiquidGlassInput v-model="draftFilters.userId" clearable placeholder="全部用户" @keyup.enter="applyFilters" />
        </label>
      </div>
    </LiquidGlassCard>

      <LiquidGlassTable class="diagnostics-card" :loading="diagnosticsLoading" :show-pagination="false" aria-label="运行诊断">
        <template #toolbar>
          <div class="card-header diagnostics-header">
            <div class="diagnostics-heading">
              <h2>运行诊断</h2>
              <LiquidGlassNav source="backdrop" class="diagnostics-nav" aria-label="诊断视图">
                <button type="button" :aria-pressed="diagnosticView === 'failures'" aria-controls="runops-diagnostics-content" @click="openDiagnostics('failures')">
                  失败聚类
                  <span :class="{ 'diagnostic-count--danger': diagnostics.failureClusters.length > 0 }">{{ diagnostics.failureClusters.length }}</span>
                </button>
                <button type="button" :aria-pressed="diagnosticView === 'versions'" aria-controls="runops-diagnostics-content" @click="openDiagnostics('versions')">
                  发布版本表现 <span>{{ diagnostics.versionComparisons.length }}</span>
                </button>
              </LiquidGlassNav>
            </div>
            <LiquidGlassButton
              link
              native-type="button"
              :icon="showDiagnostics ? ArrowUp : ArrowDown"
              :aria-expanded="showDiagnostics"
              aria-controls="runops-diagnostics-content"
              @click="showDiagnostics = !showDiagnostics"
            >
              {{ showDiagnostics ? '收起诊断' : '展开诊断' }}
            </LiquidGlassButton>
          </div>
        </template>
        <div id="runops-diagnostics-content" v-show="showDiagnostics" class="table-panel diagnostics-panel">
          <el-table
            v-if="diagnosticView === 'failures' && diagnostics.failureClusters.length"
            class="runops-table"
            :data="diagnostics.failureClusters"
            max-height="176"
          >
            <el-table-column label="次数" width="76" align="right">
              <template #default="{ row }"><span class="failure-count">{{ row.count ?? 0 }}</span></template>
            </el-table-column>
            <el-table-column label="运行对象" min-width="220">
              <template #default="{ row }">
                <el-tooltip :content="diagnosticObjectTooltip(row)" placement="top" :show-after="400">
                  <div class="cell-stack">
                    <div class="issue-title">{{ diagnosticObjectLabel(row) }}</div>
                    <div class="issue-meta">{{ diagnosticVersionLabel(row) }}</div>
                  </div>
                </el-tooltip>
              </template>
            </el-table-column>
            <el-table-column label="错误摘要" min-width="300">
              <template #default="{ row }">
                <el-tooltip :content="errorTooltip(row)" placement="top" :show-after="400">
                  <div class="cell-stack">
                    <div class="issue-title error-code">{{ errorSummary(row) }}</div>
                    <div class="issue-meta error-detail">{{ errorDetail(row) }}</div>
                  </div>
                </el-tooltip>
              </template>
            </el-table-column>
            <el-table-column label="平均耗时" width="104" align="right">
              <template #default="{ row }">
                <el-tooltip :content="`${row.avgLatencyMs ?? 0} ms`" placement="top" :show-after="400">
                  <span>{{ formatLatencyMs(row.avgLatencyMs) }}</span>
                </el-tooltip>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="108" align="center" fixed="right">
              <template #default="{ row }">
                <LiquidGlassButton
                  v-if="row.sampleTraceId"
                  link
                  type="primary"
                  size="small"
                  @click="router.push(`/runops/${row.sampleTraceId}`)"
                >
                  查看样例
                </LiquidGlassButton>
                <span v-else class="muted">-</span>
              </template>
            </el-table-column>
          </el-table>
          <el-empty
            v-else-if="diagnosticView === 'failures' && !diagnosticsLoading"
            :image-size="48"
            description="当前筛选下暂无失败聚类"
          />
          <el-table
            v-if="diagnosticView === 'versions' && diagnostics.versionComparisons.length"
            class="runops-table"
            :data="diagnostics.versionComparisons"
            max-height="176"
          >
            <el-table-column label="运行对象 / 版本" min-width="240">
              <template #default="{ row }">
                <el-tooltip :content="diagnosticObjectTooltip(row)" placement="top" :show-after="400">
                  <div class="cell-stack">
                    <div class="issue-title">{{ diagnosticObjectLabel(row) }}</div>
                    <div class="issue-meta">{{ diagnosticVersionLabel(row) }}</div>
                  </div>
                </el-tooltip>
              </template>
            </el-table-column>
            <el-table-column label="成功率" width="112" align="right">
              <template #default="{ row }">
                <span class="rate-text" :class="{ 'rate-text--warning': toPercent(row.successRate) < 90 && toPercent(row.successRate) >= 70, 'rate-text--danger': toPercent(row.successRate) < 70 }">{{ toPercent(row.successRate) }}%</span>
              </template>
            </el-table-column>
            <el-table-column prop="runCount" label="运行" width="84" align="right" />
            <el-table-column label="失败" width="84" align="right">
              <template #default="{ row }"><span :class="{ 'failure-count': row.failureCount > 0 }">{{ row.failureCount ?? 0 }}</span></template>
            </el-table-column>
            <el-table-column prop="replanCount" label="重规划" width="92" align="right" />
            <el-table-column label="P95 耗时" width="112" align="right">
              <template #default="{ row }">
                <el-tooltip :content="`${row.p95LatencyMs ?? 0} ms`" placement="top" :show-after="400">
                  <span>{{ formatLatencyMs(row.p95LatencyMs) }}</span>
                </el-tooltip>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="108" align="center" fixed="right">
              <template #default="{ row }">
                <LiquidGlassButton
                  v-if="row.latestTraceId"
                  link
                  type="primary"
                  size="small"
                  @click="router.push(`/runops/${row.latestTraceId}`)"
                >
                  最新运行
                </LiquidGlassButton>
                <span v-else class="muted">-</span>
              </template>
            </el-table-column>
          </el-table>
          <el-empty
            v-else-if="diagnosticView === 'versions' && !diagnosticsLoading"
            :image-size="48"
            description="当前筛选下暂无版本表现数据"
          />
        </div>
      </LiquidGlassTable>

    <LiquidGlassTable
      v-model:current-page="currentPage"
      v-model:page-size="pageSize"
      class="runs-card"
      :loading="loading"
      :total="filteredRuns.length"
      :page-sizes="[10, 20, 50]"
      pagination-label="根运行分页"
    >
      <template #toolbar>
        <div class="card-header">
          <h2>根运行记录</h2>
          <span class="card-header__meta">当前匹配 {{ filteredRuns.length }} 条</span>
        </div>
      </template>
      <div class="table-panel runs-panel">
          <el-table
            v-if="filteredRuns.length"
            class="runops-table runs-table"
            :data="pagedRuns"
            height="100%"
          >
            <el-table-column label="状态" width="108">
              <template #default="{ row }">
                <el-tag :type="isHttpApiWriteResultUnconfirmed(row) ? 'warning' : statusTagType(row.status)" size="small" effect="light">
                  {{ runStatusLabel(row) }}
                </el-tag>
              </template>
            </el-table-column>

            <el-table-column label="运行对象" min-width="200">
              <template #default="{ row }">
                <div class="run-object">
                  <div class="run-object__title">
                    <el-tag size="small" effect="plain" :type="row.runType === 'AGENT' ? 'success' : 'info'">
                      {{ runTypeLabel(row.runType) }}
                    </el-tag>
                    <el-tooltip :content="runObjectLabel(row)" placement="top" :show-after="400">
                      <span class="issue-title run-object__name">{{ runObjectLabel(row) }}</span>
                    </el-tooltip>
                  </div>
                  <div class="run-object__meta">
                    <el-tooltip :content="runObjectId(row)" placement="top" :show-after="400">
                      <span class="issue-meta">{{ runObjectId(row) }}</span>
                    </el-tooltip>
                    <span class="meta-sep" aria-hidden="true">·</span>
                    <el-tooltip :content="row.traceId" placement="top" :show-after="200">
                      <button
                        type="button"
                        class="trace-chip"
                        :aria-label="`复制追踪 ID ${row.traceId}`"
                        @click="copyTraceId(row.traceId)"
                      >
                        {{ shortTraceId(row.traceId) }}
                      </button>
                    </el-tooltip>
                  </div>
                </div>
              </template>
            </el-table-column>

            <el-table-column label="版本 / 入口" width="120">
              <template #default="{ row }">
                <el-tooltip :content="`${runVersionLabel(row)} · ${entryTypeLabel(row.entryType)}`" placement="top" :show-after="400">
                  <div class="cell-stack">
                    <div class="issue-title">{{ runVersionLabel(row) }}</div>
                    <div class="issue-meta">{{ entryTypeLabel(row.entryType) }}</div>
                  </div>
                </el-tooltip>
              </template>
            </el-table-column>

            <el-table-column label="项目" width="104" show-overflow-tooltip>
              <template #default="{ row }">{{ row.projectCode || '-' }}</template>
            </el-table-column>

            <el-table-column label="决策与调用" min-width="132">
              <template #default="{ row }">
                <div class="decision-metrics">
                  <span>规划 {{ row.planCount ?? 0 }}</span>
                  <span :class="{ 'is-warn': (row.replanCount ?? 0) > 0 }">重规划 {{ row.replanCount ?? 0 }}</span>
                  <span>工作流 {{ row.workflowCallCount ?? 0 }}</span>
                  <span v-if="row.approvalCount" class="is-warn">审批 {{ row.approvalCount }}</span>
                </div>
              </template>
            </el-table-column>

            <el-table-column label="运行结果" min-width="140">
              <template #default="{ row }">
                <template v-if="isFailedStatus(row.status) && (row.errorCode || row.errorMessage)">
                  <el-tooltip :content="errorTooltip(row)" placement="top" :show-after="300">
                    <div class="cell-stack">
                      <div class="issue-title error-code">{{ errorSummary(row) }}</div>
                      <div class="issue-meta error-detail">{{ errorDetail(row) }}</div>
                    </div>
                  </el-tooltip>
                </template>
                <span v-else-if="row.status === 'SUSPENDED'" class="result-waiting">{{ runStatusLabel(row) }}</span>
                <span v-else class="muted">{{ statusLabel(row.status) }}</span>
              </template>
            </el-table-column>

            <el-table-column label="耗时 / Token" width="112" align="right">
              <template #default="{ row }">
                <el-tooltip :content="`${row.latencyMs ?? 0} ms · ${row.tokenCost ?? 0} Token`" placement="top" :show-after="400">
                  <div class="cell-stack cell-stack--end">
                    <div class="issue-title">{{ formatLatencyMs(row.latencyMs) }}</div>
                    <div class="issue-meta">{{ row.tokenCost ?? 0 }} Token</div>
                  </div>
                </el-tooltip>
              </template>
            </el-table-column>

            <el-table-column label="开始时间" width="120">
              <template #default="{ row }">
                <el-tooltip :content="formatDateTime(row.startedAt)" placement="top" :show-after="400">
                  <div class="cell-stack time-cell">
                    <div>{{ formatDateTime(row.startedAt).split(' ')[0] }}</div>
                    <div class="issue-meta">{{ formatDateTime(row.startedAt).split(' ').slice(1).join(' ') }}</div>
                  </div>
                </el-tooltip>
              </template>
            </el-table-column>

            <el-table-column label="操作" width="104" align="center" fixed="right">
              <template #default="{ row }">
                <div class="row-actions">
                  <LiquidGlassButton link type="primary" size="small" @click="router.push(`/runops/${row.traceId}`)">
                    详情
                  </LiquidGlassButton>
                  <LiquidGlassButton
                    v-if="row.runType === 'WORKFLOW' && row.workflowId && canOpenWorkflowStudio(row)"
                    link
                    type="primary"
                    size="small"
                    @click="router.push(`/workflows/${row.workflowId}/studio`)"
                  >
                    编排
                  </LiquidGlassButton>
                </div>
              </template>
            </el-table-column>
          </el-table>

        <el-empty
          v-else-if="!loading"
          :image-size="56"
          description="当前筛选下暂无根运行记录"
        />

      </div>
    </LiquidGlassTable>
  </LiquidGlassPage>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ArrowDown, ArrowUp, Refresh, Search } from '@element-plus/icons-vue'
import { getRecentRunOps, getRunOpsDiagnostics } from '@/api/runops'
import RunCenterNav from './components/RunCenterNav.vue'
import { isHttpApiWriteResultUnconfirmed } from './httpApiWriteOutcome'
import type {
  FailureCluster,
  RunDiagnostics,
  RunEntryType,
  RunOpsQueryParams,
  RunStatus,
  RunSummary,
  RunType,
  VersionComparison,
} from '@/types/runops'
import PageHeader from '@/components/common/PageHeader.vue'
import { formatDateTime } from '@/utils/dateTime'
import MetricStrip from '@/components/common/MetricStrip.vue'
import type { MetricStripItem } from '@/components/common/glassWorkbench'
import {
  LiquidGlassButton,
  LiquidGlassCard,
  LiquidGlassFilterBar,
  LiquidGlassInput,
  LiquidGlassNav,
  LiquidGlassPage,
  LiquidGlassSelect,
  LiquidGlassTable,
} from '@/components/common/liquid-glass'
import { useProjectStore } from '@/store/project'
import {
  hasPlatformGlobalPermissionGrant,
  hasPlatformResourcePermission,
  platformProjectPermissionScopes,
  PLATFORM_PERMISSION_RUNOPS_READ,
  PLATFORM_PERMISSION_WORKFLOW_WRITE,
} from '@/auth/platformAccess'
import { platformSessionUser } from '@/auth/platformSession'

const router = useRouter()
const route = useRoute()
const projectStore = useProjectStore()

function canUseRunOpsRead(projectCode?: string | null) {
  return hasPlatformResourcePermission(
    platformSessionUser.value?.permissionGrants,
    PLATFORM_PERMISSION_RUNOPS_READ,
    'PROJECT',
    null,
    projectCode,
  )
}

const canReadAllRunOps = computed(() => hasPlatformGlobalPermissionGrant(
  platformSessionUser.value?.permissionGrants,
  PLATFORM_PERMISSION_RUNOPS_READ,
))
const readableRunOpsProjects = computed(() => platformProjectPermissionScopes(
  platformSessionUser.value?.permissionGrants,
  PLATFORM_PERMISSION_RUNOPS_READ,
))
const loading = ref(false)
const diagnosticsLoading = ref(false)
const showMoreFilters = ref(false)
const showDiagnostics = ref(true)
const diagnosticView = ref<'failures' | 'versions'>('failures')
const runs = ref<RunSummary[]>([])
const currentPage = ref(1)
const pageSize = ref(10)
const entryTypes: RunEntryType[] = ['DEBUG', 'STUDIO_READ_ONLY_TRIAL', 'EMBED', 'GATEWAY', 'EVAL', 'REPLAY', 'API', 'AUTOMATION', 'MCP', 'CONSOLE']
const diagnostics = ref<RunDiagnostics>({ failureClusters: [], versionComparisons: [] })

type FilterState = {
  projectCode: string
  status: '' | RunStatus
  runType: '' | RunType
  entryType: '' | RunEntryType
  agentId: string
  userId: string
  keyword: string
  days: number
}

function initialProjectCode() {
  const routeCode = typeof route.query.projectCode === 'string' ? route.query.projectCode.trim() : ''
  if (routeCode && canUseRunOpsRead(routeCode)) return routeCode
  const currentProjectCode = projectStore.currentProjectCode || ''
  if (currentProjectCode && canUseRunOpsRead(currentProjectCode)) return currentProjectCode
  return canReadAllRunOps.value ? '' : readableRunOpsProjects.value[0] || ''
}

const draftFilters = reactive<FilterState>({
  projectCode: initialProjectCode(),
  status: '',
  runType: '',
  entryType: '',
  agentId: typeof route.query.agentId === 'string' ? route.query.agentId.trim() : '',
  userId: '',
  keyword: typeof route.query.keyword === 'string' ? route.query.keyword.trim() : '',
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

const advancedFilterCount = computed(() => {
  let count = 0
  if (appliedFilters.projectCode.trim()) count += 1
  if (appliedFilters.agentId.trim()) count += 1
  if (appliedFilters.userId.trim()) count += 1
  return count
})

const kpis = computed<MetricStripItem[]>(() => {
  const total = filteredRuns.value.length
  const failed = filteredRuns.value.filter((run) => ['FAILED', 'TIMED_OUT'].includes(run.status)).length
  const waiting = filteredRuns.value.filter((run) => run.status === 'SUSPENDED').length
  const avgLatency = total
    ? Math.round(filteredRuns.value.reduce((sum, run) => sum + (run.latencyMs ?? 0), 0) / total)
    : 0
  return [
    { key: 'runs', label: '运行数', value: String(total), hint: `最近 ${appliedFilters.days} 天` },
    { key: 'failed', label: '失败数', value: String(failed), hint: '失败与超时的根运行', tone: failed > 0 ? 'danger' : 'neutral' },
    { key: 'waiting', label: '等待审批', value: String(waiting), hint: '当前阻塞于人工审批', tone: waiting > 0 ? 'warning' : 'neutral' },
    { key: 'latency', label: '平均耗时', value: formatLatencyMs(avgLatency), hint: '按根运行聚合' },
  ]
})

async function loadRuns() {
  if (!canUseRunOpsRead(requestParams.value.projectCode || null)) {
    runs.value = []
    return
  }
  loading.value = true
  try {
    const { data } = await getRecentRunOps(requestParams.value)
    runs.value = data ?? []
  } catch {
    ElMessage.error('加载根运行列表失败')
  } finally {
    loading.value = false
  }
}

async function loadDiagnostics() {
  if (!canUseRunOpsRead(requestParams.value.projectCode || null)) {
    diagnostics.value = { failureClusters: [], versionComparisons: [] }
    return
  }
  diagnosticsLoading.value = true
  try {
    const { data } = await getRunOpsDiagnostics(requestParams.value)
    diagnostics.value = data ?? { failureClusters: [], versionComparisons: [] }
  } catch {
    ElMessage.error('加载运行诊断数据失败')
  } finally {
    diagnosticsLoading.value = false
  }
}

async function refreshAll() {
  await Promise.all([loadRuns(), loadDiagnostics()])
}

function applyFilters() {
  const requestedProjectCode = draftFilters.projectCode.trim()
  if (!canUseRunOpsRead(requestedProjectCode || null)) {
    ElMessage.warning('当前账号没有该项目的 RunOps 查看权限')
    return
  }
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
  showMoreFilters.value = false
  applyFilters()
}

function runObjectLabel(run: RunSummary) {
  if (run.runType === 'AGENT') return run.agentName || run.agentKeySlug || run.agentId || '-'
  if (run.runType === 'WORKFLOW') return run.workflowName || run.workflowKeySlug || run.workflowId || '-'
  if (run.runType === 'CONSOLE_CAPABILITY') return '业务方法试调用'
  if (run.runType === 'CONSOLE_HTTP_API') return 'API 试调用'
  return run.workflowName || run.workflowKeySlug || run.workflowId
    || (run.runtimeType === 'CAPABILITY' ? 'MCP Capability 调用' : 'MCP 工具调用')
}

function canOpenWorkflowStudio(run: RunSummary) {
  return hasPlatformResourcePermission(
    platformSessionUser.value?.permissionGrants,
    PLATFORM_PERMISSION_WORKFLOW_WRITE,
    'PROJECT',
    null,
    run.projectCode,
  )
}

function runObjectId(run: RunSummary) {
  if (run.runType === 'AGENT') return run.agentKeySlug || run.agentId || '-'
  if (run.runType === 'WORKFLOW') return run.workflowKeySlug || run.workflowId || '-'
  return run.workflowKeySlug || run.workflowId || run.runtimeType || '-'
}

function runVersionLabel(run: RunSummary) {
  if (run.runType === 'MCP' && !run.workflowVersion && run.workflowVersionId == null) return '-'
  const version = run.runType === 'AGENT' ? run.agentConfigVersion : run.workflowVersion
  const versionId = run.runType === 'AGENT' ? run.agentConfigVersionId : run.workflowVersionId
  if (!version && versionId == null) return '-'
  return `${version || '-'}${versionId == null ? '' : ` · #${versionId}`}`
}

function runTypeLabel(runType?: string) {
  const labels: Record<string, string> = {
    AGENT: '智能体',
    WORKFLOW: '工作流',
    MCP: 'MCP 工具调用',
    CONSOLE_CAPABILITY: '业务方法试调用',
    CONSOLE_HTTP_API: 'API 试调用',
  }
  return runType ? labels[runType] || runType : '-'
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

function diagnosticObjectTooltip(row: FailureCluster | VersionComparison) {
  return `${diagnosticObjectLabel(row)} · ${diagnosticVersionLabel(row)}`
}

function openDiagnostics(view: 'failures' | 'versions') {
  diagnosticView.value = view
  showDiagnostics.value = true
}

type RunError = { errorCode?: string; errorType?: string; errorMessage?: string; nodeId?: string; toolName?: string }

function hasErrorMessage(row: RunError) {
  return Boolean(row.errorMessage?.trim() && row.errorMessage.trim() !== '[omitted]')
}

function errorSummary(row: RunError) {
  return hasErrorMessage(row) ? row.errorMessage : row.errorCode || row.errorType || '运行失败'
}

function errorDetail(row: RunError) {
  if (hasErrorMessage(row)) return row.errorCode || row.errorType || 'RUN_FAILED'
  return row.nodeId || row.toolName || (row.errorMessage === '[omitted]' ? '错误详情已省略' : '未记录错误详情')
}

function errorTooltip(row: { errorCode?: string; errorType?: string; errorMessage?: string; nodeId?: string; toolName?: string }) {
  const code = row.errorCode || row.errorType || 'RUN_FAILED'
  const detail = row.errorMessage || row.nodeId || row.toolName || '-'
  return `${code} · ${detail}`
}

function entryTypeLabel(entryType?: RunEntryType) {
  const labels: Record<RunEntryType, string> = {
    DEBUG: '调试',
    STUDIO_READ_ONLY_TRIAL: 'Studio 只读真实试运行',
    EMBED: '嵌入',
    GATEWAY: '网关',
    EVAL: '评测',
    REPLAY: '重放',
    AUTOMATION: '自动化',
    API: 'API',
    MCP: 'MCP',
    CONSOLE: '控制台',
  }
  return entryType ? labels[entryType] : '-'
}

function statusLabel(status: RunStatus) {
  const labels: Record<RunStatus, string> = {
    RUNNING: '运行中',
    SUSPENDED: '已暂停',
    COMPLETED: '已完成',
    FAILED: '失败',
    CANCELLED: '已取消',
    TIMED_OUT: '已超时',
  }
  return labels[status]
}

function runStatusLabel(run: RunSummary) {
  if (isHttpApiWriteResultUnconfirmed(run)) return '结果未确认'
  if (run.status !== 'SUSPENDED') return statusLabel(run.status)
  if (run.suspensionReason === 'APPROVAL') return '等待审批'
  if (run.suspensionReason === 'USER_INPUT') return '等待用户交互'
  return statusLabel(run.status)
}

function statusTagType(status?: string) {
  if (status === 'COMPLETED' || status === 'SUCCESS') return 'success'
  if (status === 'RUNNING') return 'primary'
  if (status === 'SUSPENDED' || status === 'WAITING_APPROVAL' || status === 'WAITING_USER') return 'warning'
  if (status === 'CANCELLED') return 'info'
  return 'danger'
}

function isFailedStatus(status?: string) {
  return status === 'FAILED' || status === 'TIMED_OUT' || status === 'TIMEOUT'
}

function toPercent(value?: number) {
  return Math.round((value ?? 0) * 100)
}

/** 展示用耗时格式化，不改变底层 latencyMs 数值。 */
function formatLatencyMs(ms?: number | null) {
  const value = Number(ms ?? 0)
  if (!Number.isFinite(value) || value <= 0) return '0ms'
  if (value >= 1000) {
    const seconds = value / 1000
    if (seconds >= 100) return `${Math.round(seconds)}s`
    return `${seconds.toFixed(2)}s`
  }
  return `${Math.round(value)}ms`
}

function shortTraceId(traceId?: string) {
  if (!traceId) return '-'
  if (traceId.length <= 14) return traceId
  return `${traceId.slice(0, 8)}…${traceId.slice(-4)}`
}

async function copyTraceId(traceId?: string) {
  if (!traceId) return
  try {
    await navigator.clipboard.writeText(traceId)
    ElMessage.success('追踪 ID 已复制')
  } catch {
    ElMessage.error('复制追踪 ID 失败')
  }
}

onMounted(async () => {
  if (!projectStore.projects.length) {
    await projectStore.fetchProjects()
    if (!draftFilters.projectCode
      && projectStore.currentProjectCode
      && canUseRunOpsRead(projectStore.currentProjectCode)) {
      draftFilters.projectCode = projectStore.currentProjectCode
      appliedFilters.projectCode = projectStore.currentProjectCode
    }
  }
  if (draftFilters.projectCode || draftFilters.agentId) {
    showMoreFilters.value = true
  }
  await refreshAll()
})

watch([filteredRuns, pageSize], () => {
  const maxPage = Math.max(1, Math.ceil(filteredRuns.value.length / pageSize.value) || 1)
  if (currentPage.value > maxPage) currentPage.value = maxPage
})
</script>

<style scoped lang="scss">
.runops-page {
  gap: var(--layout-section-gap);
}

.runops-header-meta {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px 16px;
}

.filter-card {
  flex: 0 0 auto;
  padding: 10px 12px;
}

.filter-card :deep(.filter-bar__fields > .liquid-glass-control.compact-filter) {
  flex: 1 1 120px;
  max-width: 150px;
}

.card-header__meta {
  color: var(--text-muted);
  font-size: 12px;
  font-weight: 500;
  white-space: nowrap;
}

.advanced-count {
  display: inline-flex;
  min-width: 18px;
  height: 18px;
  align-items: center;
  justify-content: center;
  margin-left: 4px;
  padding: 0 5px;
  border-radius: 999px;
  background: color-mix(in srgb, currentColor 16%, transparent);
  font-size: 11px;
  font-weight: 700;
  line-height: 1;
}

.filter-advanced {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px;
  margin-top: 10px;
  padding-top: 10px;
  border-top: 1px solid var(--border-divider);
}

.filter-field {
  display: grid;
  gap: 6px;
  min-width: 0;
  color: var(--text-muted);
  font-size: 12px;
  font-weight: 600;
}

.filter-field > .liquid-glass-control {
  width: 100%;
}

.runops-page > .diagnostics-card {
  flex: 0 0 auto;
  min-width: 0;
  min-height: 0;
}

.runops-page > .runs-card {
  flex: 1 1 280px;
  min-width: 0;
  min-height: 280px;
}

.diagnostics-heading {
  display: flex;
  min-width: 0;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px 16px;
}

.diagnostics-nav {
  --control-height: 28px;
}

.diagnostics-nav :deep(button > span) {
  font-variant-numeric: tabular-nums;
}

.diagnostic-count--danger,
.failure-count,
.rate-text--danger {
  color: var(--status-danger);
  font-weight: 650;
}

.rate-text--warning {
  color: var(--status-warning);
  font-weight: 650;
}

.card-header {
  display: flex;
  width: 100%;
  align-items: center;
  justify-content: space-between;
  gap: 12px;

  h2 {
    margin: 0;
    color: var(--text-primary);
    font-size: 14px;
    font-weight: 650;
  }
}

.table-panel {
  min-width: 0;
  padding: 0 0 8px;
}

.runs-panel {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-height: 0;
  padding: 0;
}

.runs-panel > .el-table {
  flex: 1;
  min-height: 0;
}

.runops-table {
  width: 100%;
}

.runops-table :deep(th.el-table__cell) {
  height: 40px;
  padding: 0;
  color: var(--text-muted);
  font-size: 12px;
  font-weight: 650;
}

.runops-table :deep(th.el-table__cell > .cell) {
  white-space: nowrap;
}

.runops-table :deep(td.el-table__cell) {
  height: 48px;
  padding: 6px 0;
  font-variant-numeric: tabular-nums;
}

.runops-table :deep(.cell) {
  overflow: hidden;
  line-height: 1.35;
}

.runs-table :deep(td.el-table__cell) {
  height: 56px;
}

.cell-stack {
  min-width: 0;
}

.cell-stack--end {
  text-align: right;
}

.issue-title {
  overflow: hidden;
  color: var(--text-primary);
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.issue-meta {
  margin-top: 2px;
  overflow: hidden;
  color: var(--text-secondary);
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.error-code {
  color: var(--status-danger);
}

.error-detail,
.time-cell {
  font-variant-numeric: tabular-nums;
}

.rate-text {
  font-variant-numeric: tabular-nums;
}

.run-object {
  min-width: 0;
}

.run-object__title {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.run-object__name {
  min-width: 0;
}

.run-object__title > .el-tag {
  flex-shrink: 0;
}

.run-object__meta {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 2px;
  min-width: 0;
}

.meta-sep {
  color: var(--text-disabled);
}

.trace-chip {
  min-width: 0;
  max-width: 100%;
  overflow: hidden;
  padding: 0;
  border: 0;
  background: transparent;
  color: var(--text-muted);
  font: inherit;
  font-size: 12px;
  font-family: var(--font-family-mono);
  text-overflow: ellipsis;
  white-space: nowrap;
  cursor: pointer;
}

.trace-chip:hover,
.trace-chip:focus-visible {
  color: var(--text-link);
}

.trace-chip:focus-visible {
  outline: 2px solid var(--border-focus);
  outline-offset: 2px;
}

.decision-metrics {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 10px;
  color: var(--text-secondary);
  font-size: 12px;
  font-variant-numeric: tabular-nums;
}

.decision-metrics .is-warn,
.result-waiting {
  color: var(--status-warning);
  font-weight: 600;
}

.decision-metrics > span {
  white-space: nowrap;
}

.row-actions {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
}

.row-actions :deep(.el-button) {
  margin: 0;
}

.muted {
  color: var(--text-muted);
}

.table-panel :deep(.el-empty) {
  padding: 20px 8px;
}

@media (max-width: 900px) {
  .filter-advanced {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 760px) {
  .diagnostics-header {
    align-items: flex-start;
  }

  .diagnostics-heading {
    flex-direction: column;
    align-items: flex-start;
    gap: 8px;
  }

  .diagnostics-nav {
    --control-height: 26px;
  }
}
</style>
