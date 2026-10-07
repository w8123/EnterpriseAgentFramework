<template>
  <section class="capability-catalog-page" aria-label="Java 业务方法目录">
    <MetricStrip :items="metricItems" density="compact" :aria-label="`${catalogCopy.title}指标概览`" />

    <el-alert
      v-if="listError"
      type="error"
      show-icon
      :closable="false"
      :title="listError"
      class="catalog-load-alert"
    >
      <template #default>
        <p v-if="listErrorDiagnostic" class="catalog-load-diagnostic">{{ listErrorDiagnostic }}</p>
        <el-button link type="primary" @click="loadMethods">重新加载{{ catalogCopy.title }}</el-button>
      </template>
    </el-alert>

    <ProjectScopeState
      v-if="!businessScopeReady"
      title="业务方法范围尚未确认"
      :message="businessScopeMessage"
      :status="businessScopeStatus"
      :can-retry="businessScopeCanRetry"
      :retrying="businessScopeRetrying"
      :retry-label="businessScopeRetryLabel"
      @retry="retryBusinessScope"
    />

    <DataTableShell
      v-else
      :current-page="currentPage"
      :page-size="pageSize"
      class="capability-table-shell project-list-table-shell workbench-list-surface"
      density="compact"
      :loading="loading"
      :empty="listState === 'ready' && methods.length === 0"
      :total="listState === 'ready' ? total : 0"
      :page-sizes="[10, 20, 50, 100]"
      @page-change="query.changePage"
      @size-change="query.changeSize"
    >
      <template #toolbar>
        <FilterBar
          class="capability-filter-bar project-list-filter-bar"
          density="compact"
          :loading="loading"
          @query="applyFilters"
          @reset="resetFilters"
        >
          <el-input
            v-model="query.draft.keyword"
            class="filter-control is-keyword"
            :prefix-icon="Search"
            placeholder="搜索 Java 业务方法名称、稳定标识或说明"
            aria-label="搜索 Java 业务方法"
            clearable
            @compositionstart="composing = true"
            @compositionend="composing = false"
            @clear="applyFilters"
          />
          <el-select
            v-model="query.draft.enabled"
            class="filter-control is-status"
            placeholder="启用状态"
            aria-label="启用状态筛选"
            clearable
          >
            <el-option label="已启用" value="true" />
            <el-option label="已停用" value="false" />
          </el-select>

          <template #actions>
            <el-tooltip content="刷新 Java 业务方法与指标" placement="top">
              <el-button circle :icon="Refresh" :loading="loading || summaryLoading"
                aria-label="刷新 Java 业务方法" @click="refreshCatalog" />
            </el-tooltip>
            <el-tooltip content="重置筛选" placement="top">
              <el-button
                circle
                native-type="button"
                :icon="RefreshLeft"
                :aria-label="`重置${'业务方法'}筛选`"
                @click="resetFilters"
              />
            </el-tooltip>
            <el-button native-type="submit" type="primary" :icon="Search" :loading="loading" :disabled="composing">
              搜索
            </el-button>
          </template>
        </FilterBar>
      </template>

      <el-table
        v-if="methods.length"
        :data="methods"
        row-key="assetId"
        class="capability-table"
        @row-click="openMethodDetail"
      >
        <el-table-column :label="'业务方法'" min-width="280">
          <template #default="{ row }">
            <div class="capability-cell">
              <span class="capability-cell__mark" aria-hidden="true">{{ 'M' }}</span>
              <span class="capability-cell__copy">
                <strong>{{ capabilityDisplayName(row) }}</strong>
                <small>{{ capabilityStableName(row) }}</small>
                <span>{{ capabilityDescription(row) }}</span>
              </span>
            </div>
          </template>
        </el-table-column>
        <el-table-column :label="'所属项目 / 来源'" min-width="180">
          <template #default="{ row }">
            <div class="source-cell">
              <strong>{{ row.sourceProjectName || row.projectCode || '项目未提供' }}</strong>
              <StatusTag
                :label="capabilitySourceLabel(row.source, row.sourceLocation)"
                :tone="capabilitySourceTone(row.source, row.sourceLocation)"
              />
            </div>
          </template>
        </el-table-column>
        <el-table-column label="操作性质" width="126">
          <template #default="{ row }">
            <StatusTag
              :label="capabilitySideEffectLabel(row.sideEffect)"
              :tone="capabilitySideEffectTone(row.sideEffect)"
            />
          </template>
        </el-table-column>
        <el-table-column :label="'来源状态'" width="116" align="center">
          <template #default="{ row }">
            <StatusTag
              :label="businessMethodReadiness(row).label"
              :tone="businessMethodReadiness(row).tone"
              :pulse="row.enabled && row.sourceAvailability === 'READY'"
            />
          </template>
        </el-table-column>
        <el-table-column label="操作" width="92" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" :icon="View" @click.stop="openMethodDetail(row)">
              查看
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <template #empty>
        <div class="catalog-empty-state">
          <el-empty
            :description="hasActiveFilters ? `没有符合当前条件的${'业务方法'}` : `${catalogCopy.title}还是空的`"
            :image-size="84"
          />
          <p v-if="!hasActiveFilters">请先在当前项目声明业务方法并完成 SDK 同步；已接纳的声明会自动出现在这里。</p>
          <div class="catalog-empty-state__actions">
            <el-button v-if="hasActiveFilters" @click="resetFilters">清空筛选</el-button>
            <el-button v-else type="primary" @click="router.push('/registry/projects')">接入业务项目</el-button>
            <el-button v-if="currentProject" @click="openProject">查看项目接入与同步</el-button>
          </div>
        </div>
      </template>
    </DataTableShell>

    <BusinessMethodDetailDialog
      v-model="detailVisible"
      :method="selectedMethod"
      :context-key="detailContextKey"
      @refreshed="refreshSelectedMethod"
    />
  </section>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Refresh, RefreshLeft, Search, View } from '@element-plus/icons-vue'
import BusinessMethodDetailDialog from './components/BusinessMethodDetailDialog.vue'
import { businessMethodReadiness } from './businessMethodDetail'
import DataTableShell from '@/components/common/DataTableShell.vue'
import FilterBar from '@/components/common/FilterBar.vue'
import MetricStrip from '@/components/common/MetricStrip.vue'
import ProjectScopeState, { type ProjectScopeStateStatus } from '@/components/common/ProjectScopeState.vue'
import StatusTag from '@/components/common/StatusTag.vue'
import type { MetricStripItem } from '@/components/common/glassWorkbench'
import { getBusinessMethods, getBusinessMethodSummary } from '@/api/businessMethod'
import { useCatalogQuery } from '@/composables/useCatalogQuery'
import { usePageProjectScope } from '@/composables/usePageProjectScope'
import { useProjectStore } from '@/store/project'
import { isBusinessMethodInfo, type BusinessMethodInfo } from '@/types/businessMethod'
import {
  capabilityDescription,
  capabilityDisplayName,
  capabilitySideEffectLabel,
  capabilitySideEffectTone,
  capabilitySourceLabel,
  capabilitySourceTone,
  capabilityStableName,
} from './capabilityGovernance'

const router = useRouter()
const projectStore = useProjectStore()
const pageProjectScope = usePageProjectScope()
type LoadState = 'loading' | 'ready' | 'error'
const catalogCopy = { title: 'Java 业务方法' }
const businessScopeReady = computed(() => Boolean(pageProjectScope?.canLoadData.value))
const businessScopeStatus = computed<ProjectScopeStateStatus>(() => (
  pageProjectScope?.status.value ?? 'pending'
))
const businessScopeMessage = computed(() => (
  pageProjectScope?.feedbackMessage.value || '正在确认项目范围，确认后再读取业务方法。'
))
const businessScopeCanRetry = computed(() => {
  const action = pageProjectScope?.recoveryAction.value
  return action === 'retry-catalog' || action === 'retry-normalization'
})
const businessScopeRetrying = computed(() => pageProjectScope?.isCatalogLoading.value ?? false)
const businessScopeRetryLabel = computed(() => pageProjectScope?.recoveryLabel.value || '重试范围确认')
const currentScopeLabel = computed(() => pageProjectScope?.currentScopeLabel.value || '范围未确认')
const scopeRequestKey = computed(() => pageProjectScope?.requestKey.value || '')
const scopeProjectId = computed(() => pageProjectScope?.requestParams.value?.projectId ?? null)
const currentProject = computed(() => projectStore.projects.find(project => project.id === scopeProjectId.value))
const detailContextKey = computed(() => `${scopeRequestKey.value}|project:${scopeProjectId.value ?? ''}`)

const loading = ref(false)
const summaryLoading = ref(false)
const summaryState = ref<LoadState>('loading')
const listState = ref<LoadState>('loading')
const listError = ref('')
const listErrorDiagnostic = ref('')
const methods = ref<BusinessMethodInfo[]>([])
const total = ref(0)
const query = useCatalogQuery('method', ['keyword', 'enabled'])
const currentPage = query.page
const pageSize = query.size
const composing = ref(false)
const requestSequence = ref(0)
const summaryRequestSequence = ref(0)

const summary = reactive({ total: 0, enabled: 0, disabled: 0 })

const detailVisible = ref(false)
const selectedMethod = ref<BusinessMethodInfo | null>(null)

const metricItems = computed<MetricStripItem[]>(() => [
  { key: 'total', label: '已纳管方法', value: summaryState.value === 'ready' ? summary.total : '—', hint: summaryState.value === 'error' ? '指标读取失败' : '当前已纳管定义', tone: 'brand', iconKey: 'box' },
  { key: 'enabled', label: '已启用', value: summaryState.value === 'ready' ? summary.enabled : '—', hint: summaryState.value === 'error' ? '状态未知' : '调用前仍核对来源与发布契约', tone: 'success', iconKey: 'check' },
  { key: 'disabled', label: '已停用', value: summaryState.value === 'ready' ? summary.disabled : '—', hint: summaryState.value === 'error' ? '状态未知' : '保留定义但不可新选', tone: 'warning', iconKey: 'pause' },
  { key: 'projects', label: '当前范围', value: currentScopeLabel.value, hint: '由左侧当前范围控制', tone: 'info', iconKey: 'link' },
])

const hasActiveFilters = query.hasFilters
let confirmedScopeContext = businessScopeReady.value ? detailContextKey.value : null

onMounted(() => { if (businessScopeReady.value) void refreshCatalog(false) })

watch([scopeRequestKey, businessScopeReady, scopeProjectId], () => {
  invalidatePendingCatalogRequests()
  clearSelectedMethod()
  if (!businessScopeReady.value) { clearListForUnconfirmedScope(); return }
  const contextChanged = confirmedScopeContext !== null && confirmedScopeContext !== detailContextKey.value
  confirmedScopeContext = detailContextKey.value
  if (contextChanged && currentPage.value !== 1) void query.changePage(1)
  void refreshCatalog(false)
}, { flush: 'sync' })
watch(query.requestKey, () => { if (businessScopeReady.value) void loadMethods() })
onBeforeUnmount(invalidatePendingCatalogRequests)

async function loadCatalogSummary() {
  if (!businessScopeReady.value) return
  const sequence = ++summaryRequestSequence.value
  summaryLoading.value = true
  summaryState.value = 'loading'
  try {
    const projectId = activeProjectId()
    const { data } = await getBusinessMethodSummary(projectId)
    if (sequence !== summaryRequestSequence.value) return
    if (![data.total, data.enabled, data.disabled].every(value => Number.isSafeInteger(value) && value >= 0)
      || data.enabled + data.disabled !== data.total) throw new Error('Invalid business method summary')
    Object.assign(summary, data)
    summaryState.value = 'ready'
  } catch {
    if (sequence === summaryRequestSequence.value) summaryState.value = 'error'
  } finally {
    if (sequence === summaryRequestSequence.value) summaryLoading.value = false
  }
}

async function loadMethods() {
  if (!businessScopeReady.value) return
  const sequence = ++requestSequence.value
  loading.value = true
  listState.value = 'loading'
  listError.value = ''
  listErrorDiagnostic.value = ''
  methods.value = []
  total.value = 0
  try {
    const projectId = activeProjectId()
    const { data } = await getBusinessMethods({
      current: currentPage.value, size: pageSize.value, keyword: query.filters.value.keyword || undefined,
      projectId, enabled: query.filters.value.enabled === 'true' ? true : query.filters.value.enabled === 'false' ? false : undefined,
    })
    if (sequence !== requestSequence.value) return
    if (!Array.isArray(data?.records) || !data.records.every(method => isBusinessMethodInfo(method)
      && (projectId === undefined || method.projectId === projectId))
      || !Number.isSafeInteger(data.total) || data.total < data.records.length) throw new Error('Invalid business method owner response')
    methods.value = data.records
    total.value = data.total
    listState.value = 'ready'
  } catch (error) {
    if (sequence !== requestSequence.value) return
    methods.value = []
    total.value = 0
    listState.value = 'error'
    listError.value = catalogLoadError(error)
  } finally {
    if (sequence === requestSequence.value) loading.value = false
  }
}

async function refreshCatalog(showFeedback = true) {
  if (!businessScopeReady.value) return
  const context = detailContextKey.value
  await Promise.all([loadCatalogSummary(), loadMethods()])
  if (!showFeedback || context !== detailContextKey.value) return
  if (summaryState.value === 'ready' && listState.value === 'ready') ElMessage.success(`${catalogCopy.title}已刷新`)
  else ElMessage.warning(`${catalogCopy.title}刷新不完整，请重试`)
}

async function applyFilters() {
  if (!composing.value) await query.apply()
}

async function resetFilters() {
  await query.reset()
}

function openMethodDetail(row: BusinessMethodInfo) {
  selectedMethod.value = row
  detailVisible.value = true
}

function refreshSelectedMethod(tool: BusinessMethodInfo) {
  if (!detailVisible.value || selectedMethod.value?.assetId !== tool.assetId || selectedMethod.value?.projectId !== tool.projectId) return
  const index = methods.value.findIndex(item => item.assetId === tool.assetId)
  if (index >= 0) methods.value[index] = tool
  selectedMethod.value = tool
}

function activeProjectId() { return pageProjectScope?.requestParams.value?.projectId }
function openProject() {
  const projectCode = currentProject.value?.projectCode
  void router.push(projectCode ? { name: 'RegistryProjectDetail', params: { projectCode } } : '/registry/projects')
}

function invalidatePendingCatalogRequests() {
  requestSequence.value += 1
  summaryRequestSequence.value += 1
}

function clearListForUnconfirmedScope() {
  loading.value = false
  summaryLoading.value = false
  listState.value = 'ready'
  listError.value = ''
  listErrorDiagnostic.value = ''
  methods.value = []
  total.value = 0
  summaryState.value = 'loading'
  summary.total = 0
  summary.enabled = 0
  summary.disabled = 0
  clearSelectedMethod()
}

function clearSelectedMethod() {
  detailVisible.value = false
  selectedMethod.value = null
}

function catalogLoadError(error: unknown) {
  const rawStatus = (error as { response?: { status?: unknown } })?.response?.status
  const status = typeof rawStatus === 'number' ? rawStatus : Number(rawStatus)
  const hasStatus = Number.isFinite(status) && status > 0
  listErrorDiagnostic.value = hasStatus
    ? `技术诊断：HTTP ${status}`
    : '技术诊断：未取得可确认的响应状态'
  if (status === 403) return '当前账号无权读取业务方法，请切换项目范围或联系项目管理员。'
  if (status === 404) return '当前环境暂未提供业务方法目录，请确认服务已更新后重试。'
  if (hasStatus && status >= 500) return '业务方法目录暂时不可用，请稍后重试。'
  return '业务方法目录加载失败，请检查当前项目范围后重试。'
}

async function retryBusinessScope() {
  await pageProjectScope?.retryScope()
}

</script>

<style scoped lang="scss">
.capability-catalog-page { display: flex; min-width: 0; flex-direction: column; gap: var(--layout-page-gap); }
.capability-table-shell { min-height: 430px; }
.catalog-load-alert { margin: 0; }
.catalog-load-diagnostic { margin: 0 0 6px; color: var(--text-secondary); font-size: 12px; }
.capability-filter-bar { width: 100%; }
.filter-control.is-keyword { width: min(360px, 100%); }
.filter-control.is-project { width: 240px; }
.filter-control.is-source { width: 180px; }
.filter-control.is-status { width: 140px; }

.capability-table { --el-table-row-hover-bg-color: rgb(var(--brand-primary-rgb) / 0.07); }
.capability-table :deep(.el-table__row) { cursor: pointer; }
.capability-cell { display: flex; align-items: center; min-width: 0; gap: 11px; }
.capability-cell__mark { display: grid; flex: 0 0 auto; place-items: center; width: 34px; height: 34px; border: 1px solid var(--border-readable); border-radius: var(--radius-md); color: var(--brand-primary); font-size: 13px; font-weight: 700; background: var(--surface-glass-selected); }
.capability-cell__copy, .source-cell { display: grid; min-width: 0; }
.capability-cell__copy { gap: 3px; }
.capability-cell__copy strong, .capability-cell__copy small, .capability-cell__copy > span, .source-cell strong { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.capability-cell__copy strong, .source-cell strong { color: var(--text-primary); font-size: 13px; }
.capability-cell__copy small { color: var(--text-secondary); font: 11px/1.4 ui-monospace, Consolas, monospace; }
.capability-cell__copy > span { color: var(--text-secondary); font-size: 12px; }
.source-cell { justify-items: start; gap: 6px; }
.protocol-cell { color: var(--text-secondary); font-size: 12px; }
.catalog-empty-state { display: grid; justify-items: center; padding: 28px; color: var(--text-muted); text-align: center; }
.catalog-empty-state > p { margin: -12px 0 16px; font-size: 13px; }
.catalog-empty-state__actions { display: flex; flex-wrap: wrap; gap: 8px; }
@media (max-width: 900px) {
  .filter-control.is-project, .filter-control.is-source, .filter-control.is-status { width: min(100%, 220px); }
}
</style>
