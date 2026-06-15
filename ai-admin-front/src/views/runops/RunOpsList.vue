<template>
  <div class="page-container runops-page">
    <div class="page-header">
      <div>
        <h2>RunOps 运行中心</h2>
        <p class="page-subtitle">按 traceId 聚合 Workflow / Agent 运行、Graph 节点、Tool 调用与治理决策。</p>
      </div>
      <div class="header-actions">
        <el-input
          v-model="traceInput"
          class="trace-input"
          clearable
          placeholder="输入 traceId 直接定位"
          @keyup.enter="openTrace"
        />
        <el-button type="primary" @click="openTrace">查看运行</el-button>
        <el-button @click="refreshAll" :loading="loading || diagnosticsLoading">刷新</el-button>
      </div>
    </div>

    <section class="runops-kpis">
      <div v-for="item in kpis" :key="item.label" class="kpi-card">
        <span>{{ item.label }}</span>
        <strong>{{ item.value }}</strong>
        <small>{{ item.hint }}</small>
      </div>
    </section>

    <el-card shadow="never" class="filter-card">
      <div class="filter-grid">
        <el-input v-model="filters.keyword" clearable placeholder="搜索 Workflow / Agent / Trace / Runtime" />
        <el-select v-model="filters.status" clearable placeholder="状态">
          <el-option label="成功" value="SUCCESS" />
          <el-option label="失败" value="ERROR" />
        </el-select>
        <el-select v-model="filters.runKind" clearable placeholder="类型">
          <el-option label="Workflow" value="Workflow" />
          <el-option label="Agent" value="Agent" />
          <el-option label="Tool" value="Tool" />
        </el-select>
        <el-select v-model="filters.identity" clearable filterable placeholder="对象">
          <el-option v-for="item in identityOptions" :key="item" :label="item" :value="item" />
        </el-select>
        <el-select v-model="filters.version" clearable filterable placeholder="版本">
          <el-option v-for="version in versionOptions" :key="version" :label="version" :value="version" />
        </el-select>
        <el-select v-model="filters.runtimePlacement" clearable placeholder="部署">
          <el-option v-for="placement in placementOptions" :key="placement" :label="placement" :value="placement" />
        </el-select>
        <el-select v-model="filters.runtimeType" clearable filterable placeholder="Runtime">
          <el-option v-for="runtime in runtimeOptions" :key="runtime" :label="runtime" :value="runtime" />
        </el-select>
        <el-select v-model="filters.fallback" clearable placeholder="Fallback">
          <el-option label="发生 Fallback" value="true" />
          <el-option label="未发生 Fallback" value="false" />
        </el-select>
        <el-select v-model="days" placeholder="时间窗口" @change="refreshAll">
          <el-option label="最近 1 天" :value="1" />
          <el-option label="最近 7 天" :value="7" />
          <el-option label="最近 14 天" :value="14" />
          <el-option label="最近 30 天" :value="30" />
        </el-select>
      </div>
      <div class="filter-actions">
        <el-button @click="resetFilters">重置筛选</el-button>
        <el-tag effect="plain">当前匹配 {{ filteredRuns.length }} 条</el-tag>
      </div>
    </el-card>

    <el-card shadow="never" class="trend-card">
      <template #header>
        <div class="card-header">
          <span>运行趋势</span>
          <el-tag size="small" effect="plain">按当前筛选</el-tag>
        </div>
      </template>
      <div class="trend-grid">
        <div v-for="point in trendPoints" :key="point.day" class="trend-point">
          <div class="bar-stack">
            <span
              class="bar-success"
              :style="{ height: `${barHeight(point.success + point.failed)}px` }"
            />
            <span
              class="bar-failed"
              :style="{ height: `${barHeight(point.failed)}px` }"
            />
          </div>
          <strong>{{ point.success + point.failed }}</strong>
          <span>{{ point.day.slice(5) }}</span>
        </div>
      </div>
    </el-card>

    <el-row :gutter="16" class="diagnostics-row">
      <el-col :xs="24" :lg="12">
        <el-card shadow="never" class="diagnostics-card">
          <template #header>
            <div class="card-header">
              <span>失败聚类</span>
              <el-tag size="small" type="danger" effect="plain">{{ diagnostics.failureClusters.length }} 类</el-tag>
            </div>
          </template>
          <el-table :data="diagnostics.failureClusters" v-loading="diagnosticsLoading" stripe height="320">
            <el-table-column prop="count" label="次数" width="72" />
            <el-table-column label="问题" min-width="220" show-overflow-tooltip>
              <template #default="{ row }">
                <div class="issue-title">{{ row.errorType || 'RUN_ERROR' }}</div>
                <div class="issue-meta">
                  {{ row.nodeId || row.toolName || '-' }} · {{ clusterPrimaryLabel(row) }}
                </div>
              </template>
            </el-table-column>
            <el-table-column label="版本" width="120">
              <template #default="{ row }">{{ clusterVersionLabel(row) }}</template>
            </el-table-column>
            <el-table-column prop="avgLatencyMs" label="均耗时" width="96">
              <template #default="{ row }">{{ row.avgLatencyMs ?? 0 }} ms</template>
            </el-table-column>
            <el-table-column label="操作" width="90" fixed="right">
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
            description="暂无失败聚类"
          />
        </el-card>
      </el-col>
      <el-col :xs="24" :lg="12">
        <el-card shadow="never" class="diagnostics-card">
          <template #header>
            <div class="card-header">
              <span>版本对比</span>
              <el-tag size="small" effect="plain">{{ diagnostics.versionComparisons.length }} 个版本</el-tag>
            </div>
          </template>
          <el-table :data="diagnostics.versionComparisons" v-loading="diagnosticsLoading" stripe height="320">
            <el-table-column label="版本" min-width="180" show-overflow-tooltip>
              <template #default="{ row }">
                <div class="issue-title">{{ comparisonPrimaryLabel(row) }}</div>
                <div class="issue-meta">{{ comparisonVersionLabel(row) }} · {{ row.runtimePlacement || '-' }}</div>
              </template>
            </el-table-column>
            <el-table-column label="成功率" width="120">
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
            <el-table-column prop="runCount" label="运行" width="72" />
            <el-table-column prop="failureCount" label="失败" width="72" />
            <el-table-column prop="p95LatencyMs" label="P95" width="90">
              <template #default="{ row }">{{ row.p95LatencyMs ?? 0 }} ms</template>
            </el-table-column>
            <el-table-column label="操作" width="90" fixed="right">
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
            description="暂无版本对比数据"
          />
        </el-card>
      </el-col>
    </el-row>

    <el-card shadow="never">
      <template #header>
        <div class="card-header">
          <span>最近运行</span>
          <el-tag size="small" effect="plain">{{ filteredRuns.length }} 条</el-tag>
        </div>
      </template>
      <el-table :data="pagedRuns" v-loading="loading" stripe>
        <el-table-column prop="status" label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="row.status === 'SUCCESS' ? 'success' : 'danger'" size="small">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="类型" width="96">
          <template #default="{ row }">
            <el-tag size="small" effect="plain" :type="runKindTagType(row)">{{ runKindLabel(row) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="名称" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">
            <div class="issue-title">{{ runPrimaryIdentityLabel(row) }}</div>
            <div v-if="runEntryLabel(row)" class="issue-meta">入口 {{ runEntryLabel(row) }}</div>
          </template>
        </el-table-column>
        <el-table-column label="ID" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">
            <span>{{ runPrimaryIdentityId(row) }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="runtimePlacement" label="部署" width="110">
          <template #default="{ row }">
            <el-tag size="small" effect="plain">{{ row.runtimePlacement || '-' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="runtimeType" label="Runtime" width="140" show-overflow-tooltip />
        <el-table-column label="版本" width="120" show-overflow-tooltip>
          <template #default="{ row }">{{ runVersionLabel(row) }}</template>
        </el-table-column>
        <el-table-column prop="latencyMs" label="耗时" width="100">
          <template #default="{ row }">{{ row.latencyMs ?? 0 }} ms</template>
        </el-table-column>
        <el-table-column prop="tokenCost" label="Token" width="100">
          <template #default="{ row }">{{ row.tokenCost ?? 0 }}</template>
        </el-table-column>
        <el-table-column prop="errorCount" label="问题" width="90">
          <template #default="{ row }">
            <el-tag :type="row.errorCount ? 'danger' : 'success'" size="small">{{ row.errorCount ?? 0 }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="startedAt" label="开始时间" width="180" />
        <el-table-column prop="traceId" label="Trace" min-width="240" show-overflow-tooltip />
        <el-table-column label="操作" width="150" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="router.push(`/runops/${row.traceId}`)">详情</el-button>
            <el-button
              v-if="runIsWorkflow(row) && runWorkflowId(row)"
              link
              type="primary"
              size="small"
              @click="openWorkflowStudio(row)"
            >
              Workflow
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
      <el-empty v-if="!loading && !filteredRuns.length" description="暂无运行记录" />
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { getRecentRunOps, getRunOpsDiagnostics } from '@/api/runops'
import type { RunDiagnostics, RunSummary } from '@/types/runops'
import {
  clusterPrimaryLabel,
  clusterVersionLabel,
  comparisonPrimaryLabel,
  comparisonVersionLabel,
  runEntryLabel,
  runIsWorkflow,
  runKindLabel,
  runPrimaryIdentityId,
  runPrimaryIdentityLabel,
  runSearchHaystack,
  runVersionLabel,
  runWorkflowId,
} from '@/utils/workflowRunOps'

const router = useRouter()
const loading = ref(false)
const diagnosticsLoading = ref(false)
const runs = ref<RunSummary[]>([])
const days = ref(7)
const currentPage = ref(1)
const pageSize = ref(10)
const diagnostics = ref<RunDiagnostics>({
  failureClusters: [],
  versionComparisons: [],
})
const traceInput = ref('')
const filters = reactive({
  keyword: '',
  status: '',
  runKind: '',
  identity: '',
  version: '',
  runtimePlacement: '',
  runtimeType: '',
  fallback: '',
})

const identityOptions = computed(() =>
  unique(runs.value.map((run) => runPrimaryIdentityLabel(run)).filter((value) => value !== '-')),
)
const versionOptions = computed(() => unique(runs.value.map((run) => runVersionLabel(run)).filter((value) => value !== '-')))
const placementOptions = computed(() => unique(runs.value.map((run) => run.runtimePlacement)))
const runtimeOptions = computed(() => unique(runs.value.map((run) => run.runtimeType)))

const filteredRuns = computed(() => {
  const keyword = filters.keyword.trim().toLowerCase()
  return runs.value.filter((run) => {
    if (keyword && !runSearchHaystack(run).includes(keyword)) return false
    if (filters.status && run.status !== filters.status) return false
    if (filters.runKind && runKindLabel(run) !== filters.runKind) return false
    if (filters.identity && runPrimaryIdentityLabel(run) !== filters.identity) return false
    if (filters.version && runVersionLabel(run) !== filters.version) return false
    if (filters.runtimePlacement && run.runtimePlacement !== filters.runtimePlacement) return false
    if (filters.runtimeType && run.runtimeType !== filters.runtimeType) return false
    if (filters.fallback && String(Boolean(run.fallback)) !== filters.fallback) return false
    return true
  })
})

const pagedRuns = computed(() => {
  const start = (currentPage.value - 1) * pageSize.value
  return filteredRuns.value.slice(start, start + pageSize.value)
})

const trendPoints = computed(() => {
  const grouped = new Map<string, { day: string; success: number; failed: number }>()
  for (let i = days.value - 1; i >= 0; i--) {
    const day = new Date(Date.now() - i * 24 * 60 * 60 * 1000).toISOString().slice(0, 10)
    grouped.set(day, { day, success: 0, failed: 0 })
  }
  filteredRuns.value.forEach((run) => {
    if (!run.startedAt) return
    const day = run.startedAt.slice(0, 10)
    const point = grouped.get(day)
    if (!point) return
    if (run.status === 'SUCCESS') point.success += 1
    else point.failed += 1
  })
  return Array.from(grouped.values())
})

const trendMax = computed(() => Math.max(1, ...trendPoints.value.map((point) => point.success + point.failed)))

const kpis = computed(() => {
  const total = filteredRuns.value.length
  const failed = filteredRuns.value.filter((run) => run.status !== 'SUCCESS').length
  const fallback = filteredRuns.value.filter((run) => run.fallback).length
  const avgLatency = total
    ? Math.round(filteredRuns.value.reduce((sum, run) => sum + (run.latencyMs ?? 0), 0) / total)
    : 0
  return [
    { label: '运行数', value: total, hint: `最近 ${days.value} 天` },
    { label: '失败数', value: failed, hint: '含节点、工具、治理拒绝' },
    { label: 'Fallback', value: fallback, hint: 'HYBRID 回落次数' },
    { label: '平均耗时', value: `${avgLatency} ms`, hint: '按 trace 聚合' },
  ]
})

async function loadRuns() {
  loading.value = true
  try {
    const { data } = await getRecentRunOps({ days: days.value, limit: 100 })
    runs.value = data ?? []
  } catch {
    ElMessage.error('加载 RunOps 运行列表失败')
  } finally {
    loading.value = false
  }
}

async function loadDiagnostics() {
  diagnosticsLoading.value = true
  try {
    const { data } = await getRunOpsDiagnostics({ days: days.value, limit: 100 })
    diagnostics.value = data ?? { failureClusters: [], versionComparisons: [] }
  } catch {
    ElMessage.error('加载 RunOps 诊断数据失败')
  } finally {
    diagnosticsLoading.value = false
  }
}

function refreshAll() {
  loadRuns()
  loadDiagnostics()
}

function toPercent(value?: number) {
  return Math.round((value ?? 0) * 100)
}

function barHeight(value: number) {
  return Math.max(4, Math.round((value / trendMax.value) * 72))
}

function unique(values: Array<string | undefined>) {
  return Array.from(new Set(values.filter((value): value is string => Boolean(value)))).sort()
}

function runKindTagType(run: RunSummary) {
  const kind = runKindLabel(run)
  if (kind === 'Workflow') return 'primary'
  if (kind === 'Agent') return 'success'
  if (kind === 'Tool') return 'warning'
  return 'info'
}

function openWorkflowStudio(run: RunSummary) {
  const workflowId = runWorkflowId(run)
  if (!workflowId) return
  router.push(`/workflows/${workflowId}/studio`)
}

function resetFilters() {
  filters.keyword = ''
  filters.status = ''
  filters.runKind = ''
  filters.identity = ''
  filters.version = ''
  filters.runtimePlacement = ''
  filters.runtimeType = ''
  filters.fallback = ''
}

function openTrace() {
  const traceId = traceInput.value.trim()
  if (!traceId) {
    ElMessage.warning('请输入 traceId')
    return
  }
  router.push(`/runops/${traceId}`)
}

onMounted(refreshAll)
watch(filteredRuns, () => {
  currentPage.value = 1
})
</script>

<style scoped lang="scss">
.page-subtitle {
  margin: 6px 0 0;
  color: var(--text-secondary);
}

.header-actions {
  display: flex;
  gap: 10px;
  align-items: center;
}

.trace-input {
  width: 280px;
}

.runops-kpis {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 14px;
  margin-bottom: 16px;
}

.filter-card,
.trend-card {
  margin-bottom: 16px;
}

.filter-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
}

.filter-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 12px;
}

.trend-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(58px, 1fr));
  gap: 10px;
  align-items: end;
}

.trend-point {
  display: grid;
  justify-items: center;
  gap: 6px;
  color: var(--text-secondary);
  font-size: 12px;

  strong {
    color: var(--text-primary);
  }
}

.bar-stack {
  position: relative;
  display: flex;
  align-items: end;
  justify-content: center;
  width: 22px;
  height: 78px;
  border-radius: 999px;
  background: var(--fill-color-light);
  overflow: hidden;
}

.bar-success,
.bar-failed {
  position: absolute;
  bottom: 0;
  width: 100%;
  min-height: 4px;
}

.bar-success {
  background: var(--el-color-success-light-5);
}

.bar-failed {
  background: var(--el-color-danger);
}

.diagnostics-row {
  margin-bottom: 16px;
}

.diagnostics-card {
  height: 100%;
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

.kpi-card {
  padding: 16px;
  border: 1px solid var(--border-color);
  border-radius: 8px;
  background: var(--card-bg);

  span,
  small {
    display: block;
    color: var(--text-secondary);
  }

  strong {
    display: block;
    margin: 8px 0 4px;
    font-size: 24px;
    color: var(--text-primary);
  }
}

.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.table-footer {
  display: flex;
  justify-content: flex-end;
  margin-top: 14px;
}

@media (max-width: 980px) {
  .runops-kpis,
  .filter-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .header-actions {
    flex-wrap: wrap;
    justify-content: flex-start;
  }
}

@media (max-width: 640px) {
  .runops-kpis,
  .filter-grid {
    grid-template-columns: 1fr;
  }
}
</style>
