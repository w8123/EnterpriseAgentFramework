<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  ChatDotRound,
  Connection,
  Cpu,
  Grid,
  Plus,
  Refresh,
  Search,
} from '@element-plus/icons-vue'
import CollapsibleHeaderRegion from '@/components/common/CollapsibleHeaderRegion.vue'
import FilterBar from '@/components/common/FilterBar.vue'
import MetricStrip from '@/components/common/MetricStrip.vue'
import PageHeader from '@/components/common/PageHeader.vue'
import WorkbenchPage from '@/components/common/WorkbenchPage.vue'
import type { MetricStripItem } from '@/components/common/glassWorkbench'
import { useCollapsiblePageHeader } from '@/composables/useCollapsiblePageHeader'
import {
  getModelInstances,
  testModelInstance,
  updateModelInstance,
} from '@/api/model'
import type { ModelInstance, ModelType } from '@/types/model'
import ModelInstanceCard from './components/ModelInstanceCard.vue'
import ModelOnboardingDialog from './components/ModelOnboardingDialog.vue'
import {
  buildStatusUpdateRequest,
  computeModelCenterMetrics,
  filterModelInstances,
  modelTestFailureHint,
  normalizeListPayload,
  providerDisplayName,
  type RuntimeStatusFilter,
  type TestStatusFilter,
  uniqueProviders,
  readModelApiPayload,
} from './modelCenterUi'

const router = useRouter()
const instances = ref<ModelInstance[]>([])
const loading = ref(false)
const testingId = ref('')
const togglingId = ref('')
const onboardingVisible = ref(false)

const filterDraft = reactive({
  keyword: '',
  provider: '',
  runtimeStatus: 'current' as RuntimeStatusFilter,
  testStatus: '' as TestStatusFilter,
})

const filters = reactive({
  keyword: '',
  provider: '',
  modelType: '' as ModelType | '',
  runtimeStatus: 'current' as RuntimeStatusFilter,
  testStatus: '' as TestStatusFilter,
})

const {
  collapsed: isModelHeaderCollapsed,
  refreshScrollTargets: refreshModelHeaderScrollTargets,
} = useCollapsiblePageHeader({
  rootSelector: '.model-center',
})

const metrics = computed(() => computeModelCenterMetrics(instances.value))

const metricItems = computed<MetricStripItem[]>(() => [
  { key: 'connected', label: '已接入', value: metrics.value.connected, tone: 'brand', iconKey: 'box' },
  { key: 'active', label: '启用中', value: metrics.value.active, tone: 'success', iconKey: 'check' },
  { key: 'disabled', label: '已停用', value: metrics.value.disabled, tone: 'warning', iconKey: 'pause' },
  { key: 'failed', label: '测试异常', value: metrics.value.testFailed, tone: 'danger', iconKey: 'alert' },
])

const providerOptions = computed(() => uniqueProviders(instances.value))

const typeFilters = computed(() => {
  const base = [
    { value: '' as ModelType | '', label: '全部', icon: Grid },
    { value: 'LLM' as ModelType, label: '大语言模型', icon: ChatDotRound },
    { value: 'EMBEDDING' as ModelType, label: '向量模型', icon: Connection },
    { value: 'RERANKER' as ModelType, label: '重排模型', icon: Cpu },
  ]
  return base.map((item) => ({
    ...item,
    count: filterModelInstances(instances.value, {
      modelType: item.value,
      runtimeStatus: filters.runtimeStatus,
      provider: filters.provider,
      keyword: filters.keyword,
      testStatus: filters.testStatus,
    }).length,
  }))
})

const filteredInstances = computed(() =>
  filterModelInstances(instances.value, {
    keyword: filters.keyword,
    provider: filters.provider,
    modelType: filters.modelType,
    runtimeStatus: filters.runtimeStatus,
    testStatus: filters.testStatus,
  }),
)

const showEmptySystem = computed(
  () => !loading.value && metrics.value.connected === 0 && filters.runtimeStatus === 'current',
)
const showEmptyFilter = computed(
  () => !loading.value && !showEmptySystem.value && filteredInstances.value.length === 0,
)

async function loadInstances() {
  loading.value = true
  try {
    const { data } = await getModelInstances({ includeArchived: true })
    instances.value = normalizeListPayload<ModelInstance>(readModelApiPayload(data))
  } catch (err) {
    instances.value = []
    ElMessage.error(err instanceof Error ? err.message : '加载模型实例失败')
  } finally {
    loading.value = false
    await nextTick()
    refreshModelHeaderScrollTargets()
  }
}

function applyFilters() {
  filters.keyword = filterDraft.keyword
  filters.provider = filterDraft.provider
  filters.runtimeStatus = filterDraft.runtimeStatus
  filters.testStatus = filterDraft.testStatus
}

function resetFilters() {
  filterDraft.keyword = ''
  filterDraft.provider = ''
  filterDraft.runtimeStatus = 'current'
  filterDraft.testStatus = ''
  filters.keyword = ''
  filters.provider = ''
  filters.modelType = ''
  filters.runtimeStatus = 'current'
  filters.testStatus = ''
}

function selectModelType(type: ModelType | '') {
  filters.modelType = type
}

function openDetail(instance: ModelInstance) {
  router.push({ name: 'ModelInstanceDetail', params: { id: instance.id } })
}

async function handleTest(instance: ModelInstance) {
  testingId.value = instance.id
  try {
    const { data } = await testModelInstance(instance.id)
    const result = readModelApiPayload<{ success?: boolean; message?: string }>(data)
    if (result.success) {
      ElMessage.success('测试通过')
    } else {
      ElMessage.warning(modelTestFailureHint(result.message))
    }
    await loadInstances()
  } catch (err) {
    ElMessage.error(err instanceof Error ? err.message : '测试失败')
  } finally {
    testingId.value = ''
  }
}

async function handleToggle(instance: ModelInstance) {
  if (instance.status === 'ARCHIVED') return
  const nextStatus = instance.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE'
  if (nextStatus === 'ACTIVE' && instance.lastTestStatus !== 'SUCCESS') {
    try {
      await ElMessageBox.confirm(
        `当前测试状态为「${instance.lastTestStatus === 'FAILED' ? '测试失败' : '未测试'}」，仍要启用吗？启用只表示运行开关，不代表测试通过。`,
        '启用确认',
        { type: 'warning', confirmButtonText: '仍然启用', cancelButtonText: '取消' },
      )
    } catch {
      return
    }
  }

  togglingId.value = instance.id
  try {
    await updateModelInstance(instance.id, buildStatusUpdateRequest(instance, nextStatus))
    ElMessage.success(nextStatus === 'ACTIVE' ? '已启用' : '已停用')
    await loadInstances()
  } catch (err) {
    ElMessage.error(err instanceof Error ? err.message : '更新状态失败')
  } finally {
    togglingId.value = ''
  }
}

onMounted(loadInstances)
</script>

<template>
  <WorkbenchPage class="model-center" layout="list">
    <CollapsibleHeaderRegion :collapsed="isModelHeaderCollapsed">
      <PageHeader
        variant="overview"
        domain="platform"
        eyebrow="Model Center"
        title="模型中心"
        description="管理已经接入、可供 Agent、Workflow、知识库和业务索引使用的模型。"
        :collapsed="isModelHeaderCollapsed"
      >
        <template #actions>
          <el-button :icon="Refresh" :loading="loading" @click="loadInstances">刷新</el-button>
          <el-button type="primary" :icon="Plus" @click="onboardingVisible = true">接入模型</el-button>
        </template>
      </PageHeader>
      <template #summary>
        <MetricStrip class="model-metric-strip" :items="metricItems" aria-label="模型中心运营指标" />
      </template>
    </CollapsibleHeaderRegion>

    <section class="model-center__shell workbench-list-surface">
      <div class="model-center__toolbar">
        <div class="model-center-filter">
          <FilterBar
            class="model-center-filter-bar"
            density="compact"
            :loading="loading"
            @query="applyFilters"
            @reset="resetFilters"
          >
            <el-input
              v-model="filterDraft.keyword"
              class="model-center-filter-bar__search"
              clearable
              placeholder="搜索名称、供应商、模型或备注"
              :prefix-icon="Search"
            />
            <el-select
              v-model="filterDraft.provider"
              class="model-center-filter-bar__select"
              clearable
              placeholder="供应商"
            >
              <el-option
                v-for="provider in providerOptions"
                :key="provider"
                :label="providerDisplayName(provider)"
                :value="provider"
              />
            </el-select>
            <el-select
              v-model="filterDraft.runtimeStatus"
              class="model-center-filter-bar__select"
              placeholder="运行状态"
            >
              <el-option label="当前模型" value="current" />
              <el-option label="启用" value="ACTIVE" />
              <el-option label="停用" value="DISABLED" />
              <el-option label="已归档" value="ARCHIVED" />
            </el-select>
            <el-select
              v-model="filterDraft.testStatus"
              class="model-center-filter-bar__select"
              clearable
              placeholder="测试状态"
            >
              <el-option label="全部" value="" />
              <el-option label="测试通过" value="SUCCESS" />
              <el-option label="测试失败" value="FAILED" />
              <el-option label="未测试" value="UNKNOWN" />
            </el-select>
          </FilterBar>
        </div>

        <div class="model-center__type-tabs" role="tablist" aria-label="模型类型">
          <button
            v-for="item in typeFilters"
            :key="item.label"
            type="button"
            class="type-tab"
            :class="{ 'is-active': filters.modelType === item.value }"
            @click="selectModelType(item.value)"
          >
            <el-icon><component :is="item.icon" /></el-icon>
            <span>{{ item.label }}</span>
            <em>{{ item.count }}</em>
          </button>
        </div>
      </div>

      <div v-loading="loading" class="model-center__content">
        <div v-if="loading && !instances.length" class="model-center__skeleton">
          <el-skeleton v-for="index in 6" :key="index" animated>
            <template #template>
              <el-skeleton-item variant="rect" style="height: 240px; border-radius: 16px" />
            </template>
          </el-skeleton>
        </div>

        <div v-else-if="showEmptySystem" class="model-center__empty">
          <el-empty description="尚未接入模型">
            <el-button type="primary" :icon="Plus" @click="onboardingVisible = true">接入第一个模型</el-button>
          </el-empty>
        </div>

        <div v-else-if="showEmptyFilter" class="model-center__empty">
          <el-empty description="没有匹配的模型">
            <el-button @click="resetFilters">清除筛选</el-button>
          </el-empty>
        </div>

        <div v-else class="model-center__grid">
          <ModelInstanceCard
            v-for="item in filteredInstances"
            :key="item.id"
            :instance="item"
            :testing="testingId === item.id"
            :toggling="togglingId === item.id"
            @manage="openDetail"
            @test="handleTest"
            @toggle="handleToggle"
          />
        </div>
      </div>
    </section>

    <ModelOnboardingDialog v-model="onboardingVisible" @created="loadInstances" />
  </WorkbenchPage>
</template>

<style scoped lang="scss">
.model-center__shell {
  display: grid;
  grid-template-rows: auto minmax(0, 1fr);
  align-content: start;
  gap: 12px;
  min-height: 0;
}

.model-center__toolbar {
  display: grid;
  grid-template-rows: auto auto;
  align-content: start;
  align-self: start;
  gap: 10px;
  min-width: 0;
}

.model-center-filter {
  min-width: 0;
}

.model-center-filter :deep(.model-center-filter-bar) {
  align-items: center;
  gap: 12px;
  width: 100%;
  min-width: 0;
  padding: 9px 14px;
  border-color: color-mix(in srgb, var(--brand-primary) 16%, var(--border-glass));
  border-radius: 12px;
  box-shadow: var(--inner-highlight);
}

.model-center-filter :deep(.filter-bar__fields) {
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
}

.model-center-filter :deep(.filter-bar__actions) {
  align-items: center;
  gap: 10px;
}

.model-center-filter :deep(.model-center-filter-bar__search) {
  flex: 1 1 360px;
  min-width: min(100%, 280px);
  max-width: 100%;
}

.model-center-filter :deep(.model-center-filter-bar__select) {
  flex: 0 1 168px;
  width: 168px;
  min-width: 148px;
}

.model-center-filter :deep(.el-input__wrapper),
.model-center-filter :deep(.el-select__wrapper) {
  min-height: 40px;
  padding: 0 14px;
  border-radius: 9px;
  background: color-mix(in srgb, var(--surface-solid-control) 92%, transparent);
  box-shadow: 0 0 0 1px color-mix(in srgb, var(--brand-primary) 18%, transparent) inset;
  transition:
    background var(--motion-duration-fast, 0.16s) ease,
    box-shadow var(--motion-duration-fast, 0.16s) ease;
}

.model-center-filter :deep(.el-input__wrapper:hover),
.model-center-filter :deep(.el-select__wrapper:hover) {
  box-shadow: 0 0 0 1px color-mix(in srgb, var(--brand-primary) 30%, transparent) inset;
}

.model-center-filter :deep(.el-input__wrapper.is-focus),
.model-center-filter :deep(.el-select__wrapper.is-focused) {
  background: var(--surface-solid-control);
  box-shadow: 0 0 0 1px var(--border-focus, var(--el-color-primary)) inset;
}

.model-center-filter :deep(.el-input__inner),
.model-center-filter :deep(.el-select__placeholder),
.model-center-filter :deep(.el-select__selected-item) {
  color: var(--text-secondary);
  font-size: 13px;
}

.model-center-filter :deep(.el-input__prefix),
.model-center-filter :deep(.el-input__suffix),
.model-center-filter :deep(.el-select__suffix) {
  color: var(--text-muted);
}

.model-center-filter :deep(.el-button) {
  min-width: 74px;
  min-height: 40px;
  margin: 0;
  border-radius: 10px;
  font-weight: 700;
}

.model-center-filter :deep(.el-button--default) {
  color: var(--text-secondary);
  border-color: var(--border-readable, var(--border-glass));
  background: color-mix(in srgb, var(--surface-solid-control) 94%, transparent);
}

.model-center-filter :deep(.el-button--primary) {
  border-color: transparent;
  background: var(--brand-primary-gradient, var(--el-color-primary));
  box-shadow: var(--shadow-active, none);
}

.model-center__type-tabs {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  align-content: flex-start;
  gap: 8px;
  min-width: 0;
}

.type-tab {
  display: inline-flex;
  flex: 0 0 auto;
  align-items: center;
  align-self: center;
  gap: 6px;
  height: 36px;
  padding: 0 14px;
  border: 1px solid var(--border-glass);
  border-radius: 999px;
  background: var(--surface-glass-control);
  color: var(--text-secondary);
  font-size: 13px;
  line-height: 1;
  white-space: nowrap;
  cursor: pointer;
  transition: border-color 0.18s ease, color 0.18s ease, background 0.18s ease;
}

.type-tab .el-icon {
  font-size: 14px;
}

.type-tab em {
  font-style: normal;
  color: var(--text-muted);
}

.type-tab.is-active {
  border-color: color-mix(in srgb, var(--el-color-primary) 45%, var(--border-glass));
  background: color-mix(in srgb, var(--el-color-primary) 12%, var(--surface-glass-control));
  color: var(--text-primary);
}

.type-tab.is-active em {
  color: color-mix(in srgb, var(--el-color-primary) 70%, var(--text-muted));
}

.model-center__content {
  min-width: 0;
  min-height: 0;
  align-self: stretch;
}

.model-center__grid,
.model-center__skeleton {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  align-content: start;
  gap: 16px;
}

.model-center__empty {
  display: grid;
  place-items: center;
  min-height: 280px;
  border: 1px solid var(--border-glass);
  border-radius: var(--radius-lg);
  background: var(--surface-glass-panel);
}

@media (max-width: 1280px) {
  .model-center__grid,
  .model-center__skeleton {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .model-center-filter :deep(.model-center-filter-bar__select) {
    flex: 0 1 156px;
    width: 156px;
  }
}

@media (max-width: 860px) {
  .model-center__grid,
  .model-center__skeleton {
    grid-template-columns: 1fr;
  }

  .model-center-filter :deep(.model-center-filter-bar__search) {
    flex: 1 1 100%;
  }

  .model-center-filter :deep(.model-center-filter-bar__select) {
    flex: 1 1 calc(50% - 10px);
    width: auto;
    min-width: 140px;
  }
}

@media (prefers-reduced-motion: reduce) {
  .type-tab,
  .model-center-filter :deep(.el-input__wrapper),
  .model-center-filter :deep(.el-select__wrapper) {
    transition: none;
  }
}
</style>
