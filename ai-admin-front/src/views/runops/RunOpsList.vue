<template>
  <WorkbenchPage class="runops-page" layout="list">
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
            native-type="button"
            :aria-expanded="showMoreFilters"
            @click="showMoreFilters = !showMoreFilters"
          >
            {{ showMoreFilters ? '收起筛选' : '更多筛选' }}
            <span v-if="advancedFilterCount > 0" class="advanced-count">{{ advancedFilterCount }}</span>
          </LiquidGlassButton>
          <LiquidGlassButton native-type="button" @click="resetFilters">重置</LiquidGlassButton>
          <LiquidGlassButton type="primary" native-type="submit" :icon="Search" :loading="loading || diagnosticsLoading">
            查询
          </LiquidGlassButton>
        </template>
      </LiquidGlassFilterBar>

      <div v-show="showMoreFilters" class="filter-advanced">
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

    <section class="runops-diagnostics" aria-label="运行诊断">
      <LiquidGlassTable class="diagnostics-card" :loading="diagnosticsLoading" :show-pagination="false">
        <template #toolbar>
          <div class="card-header">
            <h2>失败聚类</h2>
            <span class="card-header__meta">{{ diagnostics.failureClusters.length }} 类</span>
          </div>
        </template>
        <div class="table-panel">
          <el-table
            v-if="diagnostics.failureClusters.length"
            class="runops-table"
            :data="diagnostics.failureClusters"
            stripe
            max-height="300"
          >
            <el-table-column prop="count" label="次数" width="52" align="right" />
            <el-table-column label="运行对象" min-width="110">
              <template #default="{ row }">
                <el-tooltip :content="diagnosticObjectTooltip(row)" placement="top" :show-after="400">
                  <div class="cell-stack">
                    <div class="issue-title">{{ diagnosticObjectLabel(row) }}</div>
                    <div class="issue-meta">{{ diagnosticVersionLabel(row) }}</div>
                  </div>
                </el-tooltip>
              </template>
            </el-table-column>
            <el-table-column label="错误" min-width="148">
              <template #default="{ row }">
                <el-tooltip :content="errorTooltip(row)" placement="top" :show-after="400">
                  <div class="cell-stack">
                    <div class="issue-title error-code">{{ row.errorCode || row.errorType || 'RUN_FAILED' }}</div>
                    <div class="issue-meta">{{ row.errorMessage || row.nodeId || row.toolName || '-' }}</div>
                  </div>
                </el-tooltip>
              </template>
            </el-table-column>
            <el-table-column label="均耗时" width="72" align="right">
              <template #default="{ row }">
                <el-tooltip :content="`${row.avgLatencyMs ?? 0} ms`" placement="top" :show-after="400">
                  <span>{{ formatLatencyMs(row.avgLatencyMs) }}</span>
                </el-tooltip>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="52" align="center">
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
                <span v-else class="muted">-</span>
              </template>
            </el-table-column>
          </el-table>
          <el-empty
            v-else-if="!diagnosticsLoading"
            :image-size="48"
            description="当前筛选下暂无失败聚类"
          />
        </div>
      </LiquidGlassTable>

      <LiquidGlassTable class="diagnostics-card" :loading="diagnosticsLoading" :show-pagination="false">
        <template #toolbar>
          <div class="card-header">
            <h2>发布版本表现</h2>
            <span class="card-header__meta">{{ diagnostics.versionComparisons.length }} 个版本</span>
          </div>
        </template>
        <div class="table-panel">
          <el-table
            v-if="diagnostics.versionComparisons.length"
            class="runops-table"
            :data="diagnostics.versionComparisons"
            stripe
            max-height="300"
          >
            <el-table-column label="运行对象 / 版本" min-width="150">
              <template #default="{ row }">
                <el-tooltip :content="diagnosticObjectTooltip(row)" placement="top" :show-after="400">
                  <div class="cell-stack">
                    <div class="issue-title">{{ diagnosticObjectLabel(row) }}</div>
                    <div class="issue-meta">{{ diagnosticVersionLabel(row) }}</div>
                  </div>
                </el-tooltip>
              </template>
            </el-table-column>
            <el-table-column label="成功率" width="108">
              <template #default="{ row }">
                <div class="rate-cell">
                  <el-progress
                    :percentage="toPercent(row.successRate)"
                    :stroke-width="6"
                    :show-text="false"
                    :color="successRateColor(row.successRate)"
                  />
                  <span class="rate-text">{{ toPercent(row.successRate) }}%</span>
                </div>
              </template>
            </el-table-column>
            <el-table-column prop="runCount" label="运行" width="56" align="right" />
            <el-table-column prop="failureCount" label="失败" width="56" align="right" />
            <el-table-column prop="replanCount" label="重规划" width="64" align="right" />
            <el-table-column label="P95" width="72" align="right">
              <template #default="{ row }">
                <el-tooltip :content="`${row.p95LatencyMs ?? 0} ms`" placement="top" :show-after="400">
                  <span>{{ formatLatencyMs(row.p95LatencyMs) }}</span>
                </el-tooltip>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="56" align="center">
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
                <span v-else class="muted">-</span>
              </template>
            </el-table-column>
          </el-table>
          <el-empty
            v-else-if="!diagnosticsLoading"
            :image-size="48"
            description="当前筛选下暂无版本表现数据"
          />
        </div>
      </LiquidGlassTable>
    </section>

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
            stripe
          >
            <el-table-column label="状态" width="108">
              <template #default="{ row }">
                <el-tag :type="isHttpApiWriteResultUnconfirmed(row) ? 'warning' : statusTagType(row.status)" size="small" effect="light">
                  {{ runStatusLabel(row) }}
                </el-tag>
              </template>
            </el-table-column>

            <el-table-column label="运行对象" min-width="176">
              <template #default="{ row }">
                <div class="run-object">
                  <div class="run-object__title">
                    <el-tag size="small" effect="plain" :type="row.runType === 'AGENT' ? 'success' : ''">
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

            <el-table-column label="版本 / 入口" width="108">
              <template #default="{ row }">
                <el-tooltip :content="`${runVersionLabel(row)} · ${entryTypeLabel(row.entryType)}`" placement="top" :show-after="400">
                  <div class="cell-stack">
                    <div class="issue-title">{{ runVersionLabel(row) }}</div>
                    <div class="issue-meta">{{ entryTypeLabel(row.entryType) }}</div>
                  </div>
                </el-tooltip>
              </template>
            </el-table-column>

            <el-table-column label="项目" width="100" show-overflow-tooltip>
              <template #default="{ row }">{{ row.projectCode || '-' }}</template>
            </el-table-column>

            <el-table-column label="决策与调用" min-width="120">
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
                      <div class="issue-title error-code">{{ row.errorCode || 'RUN_FAILED' }}</div>
                      <div class="issue-meta">{{ row.errorMessage || '-' }}</div>
                    </div>
                  </el-tooltip>
                </template>
                <span v-else-if="row.status === 'SUSPENDED'" class="result-waiting">{{ runStatusLabel(row) }}</span>
                <span v-else class="muted">{{ statusLabel(row.status) }}</span>
              </template>
            </el-table-column>

            <el-table-column label="耗时 / Token 数" width="92" align="right">
              <template #default="{ row }">
                <el-tooltip :content="`${row.latencyMs ?? 0} ms · ${row.tokenCost ?? 0} Token`" placement="top" :show-after="400">
                  <div class="cell-stack cell-stack--end">
                    <div class="issue-title">{{ formatLatencyMs(row.latencyMs) }}</div>
                    <div class="issue-meta">{{ row.tokenCost ?? 0 }} Token</div>
                  </div>
                </el-tooltip>
              </template>
            </el-table-column>

            <el-table-column prop="startedAt" label="开始时间" width="148" show-overflow-tooltip />

            <el-table-column label="操作" width="92" align="center">
              <template #default="{ row }">
                <div class="row-actions">
                  <el-button link type="primary" size="small" @click="router.push(`/runops/${row.traceId}`)">
                    详情
                  </el-button>
                  <el-button
                    v-if="row.runType === 'WORKFLOW' && row.workflowId && canOpenWorkflowStudio(row)"
                    link
                    type="primary"
                    size="small"
                    @click="router.push(`/workflows/${row.workflowId}/studio`)"
                  >
                    编排
                  </el-button>
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
  </WorkbenchPage>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Refresh, Search } from '@element-plus/icons-vue'
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
import WorkbenchPage from '@/components/common/WorkbenchPage.vue'
import MetricStrip from '@/components/common/MetricStrip.vue'
import type { MetricStripItem } from '@/components/common/glassWorkbench'
import {
  LiquidGlassButton,
  LiquidGlassCard,
  LiquidGlassFilterBar,
  LiquidGlassInput,
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
    { key: 'failed', label: '失败数', value: String(failed), hint: '失败与超时的根运行' },
    { key: 'waiting', label: '等待审批', value: String(waiting), hint: '当前阻塞于人工审批' },
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

function successRateColor(value?: number) {
  const percent = toPercent(value)
  if (percent >= 90) return 'var(--status-success)'
  if (percent >= 70) return 'var(--status-warning)'
  return 'var(--status-danger)'
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
  gap: 12px;
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

.runops-diagnostics {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1.2fr);
  gap: 12px;
  align-items: stretch;
}

.diagnostics-card,
.runs-card {
  flex: 0 0 auto;
  min-width: 0;
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
  min-height: 96px;
  padding: 0 12px 12px;
}

.runs-panel {
  overflow-x: auto;
  padding-bottom: 8px;
}

.runs-panel :deep(.el-table) {
  --el-table-current-row-bg-color: transparent;
  min-width: 1060px;
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

.runops-table :deep(td.el-table__cell) {
  height: 52px;
  padding: 6px 0;
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

.rate-cell {
  display: grid;
  gap: 4px;
}

.rate-text {
  color: var(--text-secondary);
  font-size: 12px;
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
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
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

.row-actions {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 2px;
}

.muted {
  color: var(--text-muted);
}

.table-panel :deep(.el-empty) {
  padding: 20px 8px;
}

@media (max-width: 1360px) {
  .runops-diagnostics {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 900px) {
  .runops-page :deep(.app-page-header) {
    height: auto;
    min-height: 64px;
  }

  .filter-advanced {
    grid-template-columns: 1fr;
  }
}
</style>
