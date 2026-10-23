<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  ChatDotRound,
  Connection,
  Cpu,
  Grid,
  Plus,
  Refresh,
  Search,
  Setting,
} from '@element-plus/icons-vue'
import CollapsibleHeaderRegion from '@/components/common/CollapsibleHeaderRegion.vue'
import MetricStrip from '@/components/common/MetricStrip.vue'
import PageHeader from '@/components/common/PageHeader.vue'
import {
  LiquidGlassButton,
  LiquidGlassEmptyState,
  LiquidGlassFilterBar,
  LiquidGlassInput,
  LiquidGlassNav,
  LiquidGlassPage,
  LiquidGlassSelect,
  LiquidGlassTable,
} from '@/components/common/liquid-glass'
import type { MetricStripItem } from '@/components/common/glassWorkbench'
import {
  getModelInstances,
  testModelInstance,
  updateModelInstance,
} from '@/api/model'
import type { ModelInstance, ModelType } from '@/types/model'
import { useCollapsiblePageHeader } from '@/composables/useCollapsiblePageHeader'
import { isSupportedModelType } from '@/utils/modelSelection'
import ModelInstanceCard from './components/ModelInstanceCard.vue'
import ModelCatalogSettingsDialog from './components/ModelCatalogSettingsDialog.vue'
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

const route = useRoute()
const router = useRouter()
const instances = ref<ModelInstance[]>([])
const loading = ref(false)
const testingId = ref('')
const togglingId = ref('')
const onboardingVisible = ref(false)
const catalogSettingsVisible = ref(false)
const onboardingModelType = ref<ModelType | ''>('')

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
  { key: 'connected', label: '已接入', value: metrics.value.connected, tone: 'brand' },
  { key: 'active', label: '启用中', value: metrics.value.active, tone: 'success' },
  { key: 'disabled', label: '已停用', value: metrics.value.disabled, tone: 'warning' },
  { key: 'failed', label: '测试异常', value: metrics.value.testFailed, tone: 'danger' },
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

function openOnboarding(modelType: ModelType | '' = '') {
  onboardingModelType.value = modelType
  onboardingVisible.value = true
}

function consumeModelCreationRoute() {
  if (route.query.create !== '1') return
  const rawModelType = Array.isArray(route.query.modelType)
    ? route.query.modelType[0]
    : route.query.modelType
  const modelType = isSupportedModelType(rawModelType) ? rawModelType : ''
  if (modelType) filters.modelType = modelType
  openOnboarding(modelType)

  const query = { ...route.query }
  delete query.create
  delete query.modelType
  void router.replace({ query })
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

watch(
  () => [route.query.create, route.query.modelType],
  consumeModelCreationRoute,
  { immediate: true },
)

onMounted(loadInstances)
</script>

<template>
  <LiquidGlassPage class="model-center">
    <CollapsibleHeaderRegion :collapsed="isModelHeaderCollapsed">
      <PageHeader
        variant="overview"
        presentation="plain"
        action-presentation="inline"
        domain="platform"
        title="模型中心"
        description="接入和验证模型，配置对话、向量检索与重排服务。"
        :collapsed="isModelHeaderCollapsed"
      >
        <template #meta>
          <MetricStrip class="model-metric-strip" presentation="inline" source="wallpaper" :items="metricItems" aria-label="模型中心运营指标" />
        </template>
        <template #actions>
          <LiquidGlassButton source="wallpaper" :icon="Refresh" :loading="loading" @click="loadInstances">刷新</LiquidGlassButton>
          <LiquidGlassButton source="wallpaper" :icon="Setting" @click="catalogSettingsVisible = true">目录设置</LiquidGlassButton>
          <LiquidGlassButton source="wallpaper" type="primary" :icon="Plus" @click="openOnboarding()">接入模型</LiquidGlassButton>
        </template>
      </PageHeader>
    </CollapsibleHeaderRegion>

    <LiquidGlassTable
      body-layout="cards"
      presentation="collection"
      class="model-center__shell workbench-list-surface"
      :loading="loading"
      :empty="showEmptySystem || showEmptyFilter"
      :show-pagination="false"
    >
      <template #toolbar>
        <div class="model-center__toolbar">
          <LiquidGlassFilterBar class="model-center-filter-bar" density="compact" :loading="loading" @query="applyFilters" @reset="resetFilters">
            <LiquidGlassInput
              v-model="filterDraft.keyword"
              clearable
              placeholder="搜索名称、供应商、模型或备注"
              aria-label="搜索模型"
              :prefix-icon="Search"
            />
            <LiquidGlassSelect v-model="filterDraft.provider" class="model-filter" clearable placeholder="供应商" dropdown-title="供应商" aria-label="供应商">
              <el-option
                v-for="provider in providerOptions"
                :key="provider"
                :label="providerDisplayName(provider)"
                :value="provider"
              />
            </LiquidGlassSelect>
            <LiquidGlassSelect v-model="filterDraft.runtimeStatus" class="model-filter" placeholder="运行状态" dropdown-title="运行状态" aria-label="运行状态">
              <el-option label="当前模型" value="current" />
              <el-option label="启用" value="ACTIVE" />
              <el-option label="停用" value="DISABLED" />
              <el-option label="已归档" value="ARCHIVED" />
            </LiquidGlassSelect>
            <LiquidGlassSelect v-model="filterDraft.testStatus" class="model-filter" clearable placeholder="测试状态" dropdown-title="测试状态" aria-label="测试状态">
              <el-option label="全部" value="" />
              <el-option label="测试通过" value="SUCCESS" />
              <el-option label="测试失败" value="FAILED" />
              <el-option label="未测试" value="UNKNOWN" />
            </LiquidGlassSelect>
          </LiquidGlassFilterBar>

          <LiquidGlassNav class="model-center__type-tabs" aria-label="模型类型">
            <button
              v-for="item in typeFilters"
              :key="item.label"
              type="button"
              :aria-pressed="filters.modelType === item.value"
              @click="selectModelType(item.value)"
            >
              <el-icon aria-hidden="true"><component :is="item.icon" /></el-icon>
              <span>{{ item.label }}</span>
              <em>{{ item.count }}</em>
            </button>
          </LiquidGlassNav>
        </div>
      </template>

      <div v-if="!showEmptySystem && !showEmptyFilter" class="model-center__grid">
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

      <template #empty>
        <LiquidGlassEmptyState
          v-if="showEmptySystem"
          title="尚未接入模型"
          description="接入第一个模型后，Agent、Workflow、知识库和业务索引就能选用它。"
        >
          <LiquidGlassButton type="primary" :icon="Plus" @click="openOnboarding()">接入第一个模型</LiquidGlassButton>
        </LiquidGlassEmptyState>
        <LiquidGlassEmptyState v-else title="没有匹配的模型" description="换个关键词，或清除筛选查看全部模型。">
          <LiquidGlassButton @click="resetFilters">清除筛选</LiquidGlassButton>
        </LiquidGlassEmptyState>
      </template>
    </LiquidGlassTable>

    <ModelOnboardingDialog
      v-model="onboardingVisible"
      :initial-model-type="onboardingModelType"
      @created="loadInstances"
    />
    <ModelCatalogSettingsDialog v-model="catalogSettingsVisible" />
  </LiquidGlassPage>
</template>

<style scoped lang="scss">
.model-center__toolbar {
  display: grid;
  gap: 10px;
  min-width: 0;
}

/* 供应商与状态是短值，收窄后把宽度留给搜索框。 */
.model-center__toolbar :deep(.filter-bar__fields > .liquid-glass-control.model-filter) {
  flex: 1 1 140px;
  max-width: 180px;
}

.model-center__type-tabs {
  justify-self: start;
}

.model-center__type-tabs em {
  color: var(--text-muted);
  font-style: normal;
}

.model-center__grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  align-content: start;
  gap: 16px;
}

@media (max-width: 1280px) {
  .model-center__grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 860px) {
  .model-center__grid {
    grid-template-columns: 1fr;
  }

  .model-center__toolbar :deep(.filter-bar__fields > .liquid-glass-control.model-filter) {
    max-width: none;
  }
}
</style>
