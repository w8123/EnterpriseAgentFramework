<template>
  <WorkbenchPage class="capability-catalog-page" layout="list">
    <PageHeader
      variant="overview"
      domain="platform"
      :eyebrow="catalogCopy.eyebrow"
      :title="catalogCopy.title"
      :description="catalogCopy.description"
    >
      <template #tags>
        <el-tag effect="light">{{ currentScopeLabel }}</el-tag>
        <el-tag v-if="summaryState === 'ready'" type="success" effect="light">{{ summary.available }} 项已启用</el-tag>
        <el-tag v-else-if="summaryState === 'error'" type="danger" effect="light">目录指标不可用</el-tag>
        <el-tag v-else type="info" effect="light">正在读取目录</el-tag>
        <el-tag v-if="summaryState === 'ready' && summary.disabled" type="warning" effect="light">
          {{ summary.disabled }} 项停用
        </el-tag>
      </template>
      <template #actions>
        <el-button :icon="Document" @click="router.push('/capability/review')">
          能力变化
        </el-button>
        <el-tooltip :content="`刷新${catalogCopy.title}与指标`" placement="top">
          <el-button
            circle
            :icon="Refresh"
            :loading="loading || summaryLoading"
            :aria-label="`刷新${catalogCopy.title}`"
            @click="refreshCatalog"
          />
        </el-tooltip>
      </template>
    </PageHeader>

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
        <el-button link type="primary" @click="loadCapabilities">重新加载{{ catalogCopy.title }}</el-button>
      </template>
    </el-alert>

    <ProjectScopeState
      v-if="isBusinessMethods && !businessScopeReady"
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
      v-model:current-page="currentPage"
      v-model:page-size="pageSize"
      class="capability-table-shell project-list-table-shell workbench-list-surface"
      density="compact"
      :loading="loading"
      :empty="listState === 'ready' && capabilities.length === 0"
      :total="listState === 'ready' ? total : 0"
      :page-sizes="[10, 20, 50, 100]"
      @page-change="loadCapabilities"
      @size-change="handleSizeChange"
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
            v-model="filterKeyword"
            class="filter-control is-keyword"
            :prefix-icon="Search"
            :placeholder="`搜索${isBusinessMethods ? '业务方法' : '能力'}名称、稳定标识或说明`"
            clearable
          />
          <el-select
            v-if="!isBusinessMethods"
            v-model="filterProjectId"
            class="filter-control is-project"
            placeholder="来源项目"
            aria-label="来源项目筛选"
            clearable
            filterable
          >
            <el-option
              v-for="project in projectStore.projects"
              :key="project.id"
              :label="projectStore.projectLabel(project)"
              :value="project.id"
            />
          </el-select>
          <el-select
            v-if="!isBusinessMethods"
            v-model="filterSource"
            class="filter-control is-source"
            placeholder="接入方式"
            aria-label="接入方式筛选"
            clearable
          >
            <el-option label="代码内置" value="code" />
            <el-option label="SDK 注册" value="sdk" />
            <el-option label="源码扫描" value="scanner" />
            <el-option label="平台配置" value="manual" />
          </el-select>
          <el-select
            v-model="filterEnabled"
            class="filter-control is-status"
            placeholder="启用状态"
            aria-label="启用状态筛选"
            clearable
          >
            <el-option label="已启用" :value="true" />
            <el-option label="已停用" :value="false" />
          </el-select>

          <template #actions>
            <el-tooltip content="重置筛选" placement="top">
              <el-button
                circle
                native-type="button"
                :icon="RefreshLeft"
                :aria-label="`重置${isBusinessMethods ? '业务方法' : '能力'}筛选`"
                @click="resetFilters"
              />
            </el-tooltip>
            <el-button native-type="submit" type="primary" :icon="Search" :loading="loading">
              搜索
            </el-button>
          </template>
        </FilterBar>
      </template>

      <el-table
        v-if="capabilities.length"
        :data="capabilities"
        row-key="name"
        class="capability-table"
        @row-click="openCapabilityDetail"
      >
        <el-table-column :label="isBusinessMethods ? '业务方法' : '能力'" min-width="280">
          <template #default="{ row }">
            <div class="capability-cell">
              <span class="capability-cell__mark" aria-hidden="true">{{ isBusinessMethods ? 'M' : 'C' }}</span>
              <span class="capability-cell__copy">
                <strong>{{ capabilityDisplayName(row) }}</strong>
                <small>{{ capabilityStableName(row) }}</small>
                <span>{{ capabilityDescription(row) }}</span>
              </span>
            </div>
          </template>
        </el-table-column>
        <el-table-column :label="isBusinessMethods ? '所属项目 / 来源' : '来源'" min-width="180">
          <template #default="{ row }">
            <div class="source-cell">
              <strong>{{ row.sourceProjectName || row.projectCode || '平台级能力' }}</strong>
              <StatusTag
                :label="capabilitySourceLabel(row.source, row.sourceLocation)"
                :tone="capabilitySourceTone(row.source, row.sourceLocation)"
              />
            </div>
          </template>
        </el-table-column>
        <el-table-column v-if="!isBusinessMethods" label="调用入口" min-width="220" show-overflow-tooltip>
          <template #default="{ row }">
            <code class="protocol-cell">{{ capabilityProtocol(row) }}</code>
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
        <el-table-column :label="isBusinessMethods ? '来源状态' : '调用状态'" width="116" align="center">
          <template #default="{ row }">
            <StatusTag
              :label="capabilityReadiness(row).label"
              :tone="capabilityReadiness(row).tone"
              :pulse="row.enabled && row.sourceAvailability === 'READY' && row.catalogLinkStatus !== 'NOT_IN_CATALOG'"
            />
          </template>
        </el-table-column>
        <el-table-column label="操作" width="92" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" :icon="View" @click.stop="openCapabilityDetail(row)">
              查看
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <template #empty>
        <div class="catalog-empty-state">
          <el-empty
            :description="hasActiveFilters ? `没有符合当前条件的${isBusinessMethods ? '业务方法' : '能力'}` : `${catalogCopy.title}还是空的`"
            :image-size="84"
          />
          <p v-if="!hasActiveFilters">{{ isBusinessMethods ? '请先在当前项目声明业务方法并完成 SDK 同步；已接纳的声明会自动出现在这里。' : '先接入业务项目，再由 SDK 同步或扫描纳管能力。' }}</p>
          <div class="catalog-empty-state__actions">
            <el-button v-if="hasActiveFilters" @click="resetFilters">清空筛选</el-button>
            <el-button v-else type="primary" @click="router.push('/registry/projects')">接入业务项目</el-button>
            <el-button @click="router.push('/capability/review')">查看能力变化</el-button>
          </div>
        </div>
      </template>
    </DataTableShell>

    <CapabilityDetailDialog
      v-model="detailVisible"
      :capability="selectedCapability"
      :catalog-kind="catalogKind"
      :context-key="detailContextKey"
      @refreshed="refreshSelectedCapability"
    />
  </WorkbenchPage>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Document, Refresh, RefreshLeft, Search, View } from '@element-plus/icons-vue'
import CapabilityDetailDialog from './components/CapabilityDetailDialog.vue'
import { capabilityReadiness } from './capabilityDetail'
import DataTableShell from '@/components/common/DataTableShell.vue'
import FilterBar from '@/components/common/FilterBar.vue'
import MetricStrip from '@/components/common/MetricStrip.vue'
import PageHeader from '@/components/common/PageHeader.vue'
import ProjectScopeState, { type ProjectScopeStateStatus } from '@/components/common/ProjectScopeState.vue'
import StatusTag from '@/components/common/StatusTag.vue'
import WorkbenchPage from '@/components/common/WorkbenchPage.vue'
import type { MetricStripItem } from '@/components/common/glassWorkbench'
import { getBusinessMethods, getTools } from '@/api/tool'
import { usePageProjectScope } from '@/composables/usePageProjectScope'
import { useProjectStore } from '@/store/project'
import type { ToolInfo, ToolPageResult } from '@/types/tool'
import {
  capabilityDescription,
  capabilityDisplayName,
  capabilityProtocol,
  capabilitySideEffectLabel,
  capabilitySideEffectTone,
  capabilitySourceLabel,
  capabilitySourceTone,
  capabilityStableName,
} from './capabilityGovernance'

const props = withDefaults(defineProps<{ catalogKind?: 'capability' | 'business-method' }>(), {
  catalogKind: 'capability',
})
const router = useRouter()
const projectStore = useProjectStore()
const pageProjectScope = usePageProjectScope()
type LoadState = 'loading' | 'ready' | 'error'
const catalogKind = computed(() => props.catalogKind)
const isBusinessMethods = computed(() => catalogKind.value === 'business-method')
const catalogCopy = computed(() => isBusinessMethods.value
  ? {
      eyebrow: 'Business Methods',
      title: '业务方法',
      description: '查看当前项目范围内已接受的 Java 业务方法及其声明契约、来源状态和使用证据。',
    }
  : {
      eyebrow: 'Capability Catalog',
      title: '能力目录',
      description: '查看已接入平台的能力及其来源、调用方式和可用状态。',
    })
const businessScopeReady = computed(() => (
  !isBusinessMethods.value || Boolean(pageProjectScope?.canLoadData.value)
))
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
const currentScopeLabel = computed(() => isBusinessMethods.value
  ? (pageProjectScope?.currentScopeLabel.value || '范围未确认')
  : '平台范围')
const scopeRequestKey = computed(() => pageProjectScope?.requestKey.value || '')
const scopeProjectId = computed(() => pageProjectScope?.requestParams.value?.projectId ?? null)
const detailContextKey = computed(() => isBusinessMethods.value
  ? `${scopeRequestKey.value}|project:${scopeProjectId.value ?? ''}`
  : '')

const loading = ref(false)
const summaryLoading = ref(false)
const summaryState = ref<LoadState>('loading')
const listState = ref<LoadState>('loading')
const listError = ref('')
const listErrorDiagnostic = ref('')
const capabilities = ref<ToolInfo[]>([])
const total = ref(0)
const currentPage = ref(1)
const pageSize = ref(20)
const filterKeyword = ref('')
const filterProjectId = ref<number>()
const filterSource = ref('')
const filterEnabled = ref<boolean>()
const requestSequence = ref(0)
const summaryRequestSequence = ref(0)

const summary = reactive({ catalog: 0, available: 0, disabled: 0 })

const detailVisible = ref(false)
const selectedCapability = ref<ToolInfo | null>(null)

const metricItems = computed<MetricStripItem[]>(() => [
  { key: 'catalog', label: isBusinessMethods.value ? '已纳管方法' : '目录能力', value: summaryState.value === 'ready' ? summary.catalog : '—', hint: summaryState.value === 'error' ? '指标读取失败' : '当前已纳管定义', tone: 'brand', iconKey: 'box' },
  { key: 'available', label: '已启用', value: summaryState.value === 'ready' ? summary.available : '—', hint: summaryState.value === 'error' ? '状态未知' : '调用前仍核对来源与发布契约', tone: 'success', iconKey: 'check' },
  { key: 'disabled', label: '已停用', value: summaryState.value === 'ready' ? summary.disabled : '—', hint: summaryState.value === 'error' ? '状态未知' : '保留定义但不可新选', tone: 'warning', iconKey: 'pause' },
  { key: 'projects', label: isBusinessMethods.value ? '当前范围' : '平台项目', value: isBusinessMethods.value ? currentScopeLabel.value : projectStore.projects.length, hint: isBusinessMethods.value ? '由左侧当前范围控制' : '接入与来源治理范围', tone: 'info', iconKey: 'link' },
])

const hasActiveFilters = computed(() => Boolean(
  filterKeyword.value.trim()
  || (!isBusinessMethods.value && filterProjectId.value)
  || (!isBusinessMethods.value && filterSource.value)
  || filterEnabled.value !== undefined,
))

onMounted(async () => {
  if (isBusinessMethods.value) {
    if (businessScopeReady.value) await refreshCatalog(false)
    return
  }
  await Promise.all([
    projectStore.projects.length ? Promise.resolve() : projectStore.fetchProjects(),
    loadCatalogSummary(),
    loadCapabilities(),
  ])
})

watch([isBusinessMethods, scopeRequestKey, businessScopeReady, scopeProjectId], ([nextIsBusinessMethods, nextScopeKey, , nextProjectId], [previousIsBusinessMethods, previousScopeKey, , previousProjectId]) => {
  invalidatePendingCatalogRequests()
  if (nextIsBusinessMethods && (!previousIsBusinessMethods
    || nextScopeKey !== previousScopeKey
    || nextProjectId !== previousProjectId)) {
    clearSelectedCapability()
  }
  currentPage.value = 1
  if (isBusinessMethods.value) {
    filterProjectId.value = undefined
    filterSource.value = ''
    if (!businessScopeReady.value) {
      clearListForUnconfirmedScope()
      return
    }
    void refreshCatalog(false)
    return
  }
  void Promise.all([
    projectStore.projects.length ? Promise.resolve() : projectStore.fetchProjects(),
    refreshCatalog(false),
  ])
}, { flush: 'sync' })

async function loadCatalogSummary() {
  if (isBusinessMethods.value && !businessScopeReady.value) return
  const sequence = ++summaryRequestSequence.value
  summaryLoading.value = true
  summaryState.value = 'loading'
  try {
    const projectId = activeProjectId()
    const [catalogResponse, availableResponse, disabledResponse] = isBusinessMethods.value
      ? await Promise.all([
          getBusinessMethods({ current: 1, size: 1, projectId }),
          getBusinessMethods({ current: 1, size: 1, enabled: true, projectId }),
          getBusinessMethods({ current: 1, size: 1, enabled: false, projectId }),
        ])
      : await Promise.all([
          getTools({ current: 1, size: 1 }),
          getTools({ current: 1, size: 1, enabled: true }),
          getTools({ current: 1, size: 1, enabled: false }),
        ])
    if (sequence !== summaryRequestSequence.value) return
    summary.catalog = pageTotal(catalogResponse.data)
    summary.available = pageTotal(availableResponse.data)
    summary.disabled = pageTotal(disabledResponse.data)
    summaryState.value = 'ready'
  } catch {
    if (sequence === summaryRequestSequence.value) summaryState.value = 'error'
  } finally {
    if (sequence === summaryRequestSequence.value) summaryLoading.value = false
  }
}

async function loadCapabilities() {
  if (isBusinessMethods.value && !businessScopeReady.value) return
  const sequence = ++requestSequence.value
  loading.value = true
  listState.value = 'loading'
  listError.value = ''
  listErrorDiagnostic.value = ''
  capabilities.value = []
  total.value = 0
  try {
    const projectId = activeProjectId()
    const { data } = isBusinessMethods.value
      ? await getBusinessMethods({
          current: currentPage.value,
          size: pageSize.value,
          keyword: filterKeyword.value.trim() || undefined,
          projectId,
          enabled: filterEnabled.value,
        })
      : await getTools({
          current: currentPage.value,
          size: pageSize.value,
          keyword: filterKeyword.value.trim() || undefined,
          projectId,
          source: filterSource.value || undefined,
          enabled: filterEnabled.value,
        })
    if (sequence !== requestSequence.value) return
    capabilities.value = Array.isArray(data?.records) ? data.records : []
    total.value = Number(data?.total || 0)
    listState.value = 'ready'
  } catch (error) {
    if (sequence !== requestSequence.value) return
    capabilities.value = []
    total.value = 0
    listState.value = 'error'
    listError.value = catalogLoadError(error)
  } finally {
    if (sequence === requestSequence.value) loading.value = false
  }
}

async function refreshCatalog(showFeedback = true) {
  if (isBusinessMethods.value && !businessScopeReady.value) return
  await Promise.all([loadCatalogSummary(), loadCapabilities()])
  if (!showFeedback) return
  if (summaryState.value === 'ready' && listState.value === 'ready') ElMessage.success(`${catalogCopy.value.title}已刷新`)
  else ElMessage.warning(`${catalogCopy.value.title}刷新不完整，请重试`)
}

async function applyFilters() {
  currentPage.value = 1
  await loadCapabilities()
}

async function resetFilters() {
  filterKeyword.value = ''
  filterProjectId.value = undefined
  filterSource.value = ''
  filterEnabled.value = undefined
  currentPage.value = 1
  await loadCapabilities()
}

async function handleSizeChange() {
  currentPage.value = 1
  await loadCapabilities()
}

function openCapabilityDetail(row: ToolInfo) {
  selectedCapability.value = row
  detailVisible.value = true
}

function refreshSelectedCapability(tool: ToolInfo) {
  if (!detailVisible.value || selectedCapability.value?.name !== tool.name) return
  const index = capabilities.value.findIndex(item => item.name === tool.name)
  if (index >= 0) capabilities.value[index] = tool
  selectedCapability.value = tool
}

function activeProjectId() {
  return isBusinessMethods.value
    ? pageProjectScope?.requestParams.value?.projectId
    : filterProjectId.value
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
  capabilities.value = []
  total.value = 0
  summaryState.value = 'loading'
  summary.catalog = 0
  summary.available = 0
  summary.disabled = 0
  clearSelectedCapability()
}

function clearSelectedCapability() {
  detailVisible.value = false
  selectedCapability.value = null
}

function catalogLoadError(error: unknown) {
  const rawStatus = (error as { response?: { status?: unknown } })?.response?.status
  const status = typeof rawStatus === 'number' ? rawStatus : Number(rawStatus)
  const hasStatus = Number.isFinite(status) && status > 0
  listErrorDiagnostic.value = hasStatus
    ? `技术诊断：HTTP ${status}`
    : '技术诊断：未取得可确认的响应状态'
  if (isBusinessMethods.value) {
    if (status === 403) return '当前账号无权读取业务方法，请切换项目范围或联系项目管理员。'
    if (status === 404) return '当前环境暂未提供业务方法目录，请确认服务已更新后重试。'
    if (hasStatus && status >= 500) return '业务方法目录暂时不可用，请稍后重试。'
    return '业务方法目录加载失败，请检查当前项目范围后重试。'
  }
  if (status === 403) return '当前账号无权读取能力目录，请联系项目管理员。'
  if (status === 404) return '当前环境暂未提供能力目录，请确认服务已更新后重试。'
  if (hasStatus && status >= 500) return '能力目录暂时不可用，请稍后重试。'
  return '能力目录加载失败，请稍后重试。'
}

async function retryBusinessScope() {
  await pageProjectScope?.retryScope()
}

function pageTotal(data?: ToolPageResult | null) {
  return Number(data?.total || 0)
}
</script>

<style scoped lang="scss">
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
