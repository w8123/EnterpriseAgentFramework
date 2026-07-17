<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { ArrowLeft, ArrowRight, ChatDotRound, Connection, Cpu, Plus, Refresh, Search } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { getModelTemplates } from '@/api/model'
import type { ModelTemplate, ModelType } from '@/types/model'
import {
  buildProviderGroups,
  filterProviderGroups,
  filterProviderModels,
  findProviderGroup,
  modelTypeLabel,
  normalizeListPayload,
  type ModelProviderGroup,
  readModelApiPayload,
} from '../modelCenterUi'
import ModelProviderIcon from './ModelProviderIcon.vue'

const props = withDefaults(
  defineProps<{
    modelType?: ModelType | ''
    lockModelType?: boolean
    showCustomEntry?: boolean
  }>(),
  {
    modelType: '',
    lockModelType: false,
    showCustomEntry: true,
  },
)

const emit = defineEmits<{
  selectTemplate: [template: ModelTemplate]
  selectCustom: []
}>()

type PickerStep = 'provider' | 'model'

const loading = ref(false)
const error = ref('')
const templates = ref<ModelTemplate[]>([])
const pickerStep = ref<PickerStep>('provider')
const selectedProvider = ref<string | null>(null)
const providerKeyword = ref('')
const modelKeyword = ref('')
const modelTypeFilter = ref<ModelType | ''>('')

const lockedModelType = computed(() =>
  props.lockModelType ? (props.modelType || '') : '',
)

const effectiveModelType = computed<ModelType | ''>(() => {
  if (lockedModelType.value) return lockedModelType.value
  return modelTypeFilter.value
})

const providerGroups = computed(() =>
  buildProviderGroups(templates.value, {
    modelType: lockedModelType.value || '',
    onlyEnabled: true,
  }),
)

const visibleProviderGroups = computed(() =>
  filterProviderGroups(providerGroups.value, providerKeyword.value),
)

const selectedGroup = computed(() =>
  findProviderGroup(providerGroups.value, selectedProvider.value),
)

const visibleModels = computed(() => {
  if (!selectedGroup.value) return []
  return filterProviderModels(selectedGroup.value.templates, {
    keyword: modelKeyword.value,
    modelType: effectiveModelType.value,
  })
})

const contextSummary = computed(() => {
  const group = selectedGroup.value
  if (!group) return ''
  if (lockedModelType.value) {
    return `${group.displayName} · ${group.modelCount} 个${modelTypeLabel(lockedModelType.value)}`
  }
  return `${group.displayName} · ${group.modelCount} 个模型`
})

watch(
  () => [props.lockModelType, props.modelType] as const,
  () => {
    if (props.lockModelType) {
      modelTypeFilter.value = ''
    }
    ensureSelectedProviderValid('类型筛选已变化，请重新选择厂商')
  },
)

function resetPickerState() {
  pickerStep.value = 'provider'
  selectedProvider.value = null
  providerKeyword.value = ''
  modelKeyword.value = ''
  if (!props.lockModelType) {
    modelTypeFilter.value = ''
  }
}

async function loadTemplates() {
  loading.value = true
  error.value = ''
  try {
    const { data } = await getModelTemplates({ enabled: true })
    templates.value = normalizeListPayload<ModelTemplate>(readModelApiPayload(data))
    ensureSelectedProviderValid('当前厂商暂无可用模型，已返回厂商列表')
  } catch (err) {
    error.value = err instanceof Error ? err.message : '加载模型目录失败'
    templates.value = []
    pickerStep.value = 'provider'
    selectedProvider.value = null
  } finally {
    loading.value = false
  }
}

function ensureSelectedProviderValid(message?: string) {
  if (!selectedProvider.value) return
  const group = findProviderGroup(
    buildProviderGroups(templates.value, {
      modelType: lockedModelType.value || '',
      onlyEnabled: true,
    }),
    selectedProvider.value,
  )
  if (!group || group.modelCount === 0) {
    selectedProvider.value = null
    pickerStep.value = 'provider'
    modelKeyword.value = ''
    if (message) ElMessage.warning(message)
  }
}

function selectProvider(group: ModelProviderGroup) {
  selectedProvider.value = group.provider
  modelKeyword.value = ''
  pickerStep.value = 'model'
}

function backToProviders() {
  pickerStep.value = 'provider'
  modelKeyword.value = ''
}

function selectModel(template: ModelTemplate) {
  if (lockedModelType.value && template.modelType !== lockedModelType.value) {
    ElMessage.warning(`只能选择${modelTypeLabel(lockedModelType.value)}`)
    return
  }
  emit('selectTemplate', template)
}

function selectCustom() {
  emit('selectCustom')
}

onMounted(loadTemplates)

defineExpose({
  reload: loadTemplates,
  reset: resetPickerState,
})
</script>

<template>
  <div class="model-catalog-picker">
    <div class="catalog-steps" aria-label="选择进度">
      <span
        class="catalog-steps__item"
        :class="{ 'is-current': pickerStep === 'provider', 'is-done': pickerStep === 'model' }"
        :aria-current="pickerStep === 'provider' ? 'step' : undefined"
      >
        <em>1</em>
        选择厂商
      </span>
      <span class="catalog-steps__divider" aria-hidden="true" />
      <span
        class="catalog-steps__item"
        :class="{ 'is-current': pickerStep === 'model' }"
        :aria-current="pickerStep === 'model' ? 'step' : undefined"
      >
        <em>2</em>
        选择模型
      </span>
      <button
        type="button"
        class="catalog-reload"
        :disabled="loading"
        aria-label="刷新模型目录"
        @click="loadTemplates"
      >
        <el-icon :class="{ 'is-loading': loading }"><Refresh /></el-icon>
      </button>
    </div>

    <div v-if="loading && !templates.length" class="catalog-skeleton">
      <el-skeleton v-for="index in 8" :key="index" animated>
        <template #template>
          <el-skeleton-item variant="rect" style="height: 84px; border-radius: 12px" />
        </template>
      </el-skeleton>
    </div>

    <el-alert
      v-else-if="error"
      type="error"
      :closable="false"
      show-icon
      :title="error"
    >
      <el-button size="small" @click="loadTemplates">重试</el-button>
    </el-alert>

    <template v-else-if="pickerStep === 'provider'">
      <div class="catalog-header">
        <div>
          <h3>选择模型厂商</h3>
          <p>选择厂商后，再选择该厂商提供的模型。</p>
        </div>
        <el-input
          v-model="providerKeyword"
          clearable
          class="catalog-search"
          placeholder="搜索厂商或模型名称"
          :prefix-icon="Search"
        />
      </div>

      <div v-if="lockModelType && modelType" class="catalog-lock-hint">
        当前仅展示支持「{{ modelTypeLabel(modelType) }}」的厂商
      </div>

      <div v-if="visibleProviderGroups.length" class="provider-grid" role="list">
        <button
          v-for="group in visibleProviderGroups"
          :key="group.provider"
          type="button"
          class="provider-card"
          role="listitem"
          @click="selectProvider(group)"
        >
          <ModelProviderIcon :provider="group.provider" :icon-key="group.iconKey" :size="36" />
          <div class="provider-card__body">
            <strong>{{ group.displayName }}</strong>
            <span>{{ group.modelCount }} 个模型</span>
          </div>
          <el-icon class="provider-card__arrow"><ArrowRight /></el-icon>
        </button>
      </div>

      <div v-else class="catalog-empty">
        <el-empty :description="providerKeyword ? '没有匹配的厂商' : '暂无可用厂商'" />
      </div>

      <section v-if="showCustomEntry" class="catalog-other">
        <h4>其他接入方式</h4>
        <button type="button" class="custom-card" aria-label="自定义 OpenAI 兼容模型" @click="selectCustom">
          <div class="custom-card__icon" aria-hidden="true">
            <el-icon><Plus /></el-icon>
          </div>
          <div class="custom-card__body">
            <strong>自定义 OpenAI 兼容模型</strong>
            <p>自行配置 Base URL、模型名称、路径和凭证。</p>
          </div>
          <el-icon class="provider-card__arrow" aria-hidden="true"><ArrowRight /></el-icon>
        </button>
      </section>
    </template>

    <template v-else>
      <div class="model-context">
        <button type="button" class="model-context__back" aria-label="返回选择厂商" @click="backToProviders">
          <el-icon aria-hidden="true"><ArrowLeft /></el-icon>
          返回选择厂商
        </button>
        <div v-if="selectedGroup" class="model-context__brand">
          <ModelProviderIcon
            :provider="selectedGroup.provider"
            :icon-key="selectedGroup.iconKey"
            :size="32"
          />
          <div>
            <strong>{{ contextSummary }}</strong>
            <p v-if="lockModelType && modelType">模型类型不可更改</p>
          </div>
        </div>
      </div>

      <div class="catalog-header catalog-header--model">
        <el-input
          v-model="modelKeyword"
          clearable
          class="catalog-search"
          placeholder="搜索当前厂商下的模型"
          :prefix-icon="Search"
        />
        <div v-if="!lockModelType" class="model-type-pills" role="tablist" aria-label="模型类型">
          <button
            type="button"
            class="model-type-pill"
            :class="{ 'is-active': modelTypeFilter === '' }"
            @click="modelTypeFilter = ''"
          >
            全部
          </button>
          <button
            type="button"
            class="model-type-pill"
            :class="{ 'is-active': modelTypeFilter === 'LLM' }"
            @click="modelTypeFilter = 'LLM'"
          >
            <el-icon><ChatDotRound /></el-icon>
            大语言模型
          </button>
          <button
            type="button"
            class="model-type-pill"
            :class="{ 'is-active': modelTypeFilter === 'EMBEDDING' }"
            @click="modelTypeFilter = 'EMBEDDING'"
          >
            <el-icon><Connection /></el-icon>
            向量模型
          </button>
          <button
            type="button"
            class="model-type-pill"
            :class="{ 'is-active': modelTypeFilter === 'RERANKER' }"
            @click="modelTypeFilter = 'RERANKER'"
          >
            <el-icon><Cpu /></el-icon>
            重排模型
          </button>
        </div>
      </div>

      <div v-if="visibleModels.length" class="model-grid" role="list">
        <button
          v-for="item in visibleModels"
          :key="item.id"
          type="button"
          class="model-card"
          role="listitem"
          @click="selectModel(item)"
        >
          <div class="model-card__body">
            <div class="model-card__title-row">
              <strong>{{ item.name }}</strong>
              <span v-if="!lockModelType" class="model-card__type">{{ modelTypeLabel(item.modelType) }}</span>
            </div>
            <code>{{ item.modelName }}</code>
            <p v-if="item.remark">{{ item.remark }}</p>
          </div>
          <el-icon class="provider-card__arrow"><ArrowRight /></el-icon>
        </button>
      </div>

      <div v-else class="catalog-empty">
        <el-empty :description="modelKeyword || modelTypeFilter ? '没有匹配的模型' : '该厂商暂无可用模型'" />
      </div>
    </template>
  </div>
</template>

<style scoped lang="scss">
.model-catalog-picker {
  display: grid;
  gap: 14px;
  min-height: 0;
}

.catalog-steps {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}

.catalog-steps__item {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: var(--text-muted);
  font-size: 13px;
  font-weight: 600;
}

.catalog-steps__item em {
  display: inline-flex;
  width: 22px;
  height: 22px;
  align-items: center;
  justify-content: center;
  border-radius: 999px;
  border: 1px solid var(--border-glass);
  background: var(--surface-glass-control);
  color: var(--text-secondary);
  font-style: normal;
  font-size: 12px;
}

.catalog-steps__item.is-current,
.catalog-steps__item.is-done {
  color: var(--text-primary);
}

.catalog-steps__item.is-current em,
.catalog-steps__item.is-done em {
  border-color: color-mix(in srgb, var(--el-color-primary) 45%, var(--border-glass));
  background: color-mix(in srgb, var(--el-color-primary) 14%, var(--surface-glass-control));
  color: var(--el-color-primary);
}

.catalog-steps__divider {
  width: 28px;
  height: 1px;
  background: var(--border-glass);
}

.catalog-reload {
  margin-left: auto;
  display: inline-flex;
  width: 32px;
  height: 32px;
  align-items: center;
  justify-content: center;
  border: 1px solid var(--border-glass);
  border-radius: 9px;
  background: var(--surface-glass-control);
  color: var(--text-secondary);
  cursor: pointer;
}

.catalog-reload:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.catalog-reload .is-loading {
  animation: catalog-spin 0.9s linear infinite;
}

.catalog-header {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-end;
  justify-content: space-between;
  gap: 12px;
}

.catalog-header h3,
.catalog-header p,
.catalog-other h4,
.model-context__brand p,
.model-card p {
  margin: 0;
}

.catalog-header h3 {
  color: var(--text-primary);
  font-size: 16px;
  line-height: 1.3;
}

.catalog-header p {
  margin-top: 4px;
  color: var(--text-secondary);
  font-size: 13px;
}

.catalog-search {
  width: min(100%, 280px);
}

.catalog-lock-hint {
  color: var(--text-secondary);
  font-size: 12px;
}

.catalog-skeleton,
.provider-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px;
  max-height: min(36vh, 360px);
  overflow: auto;
  padding-right: 2px;
}

.provider-card,
.custom-card,
.model-card {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
  padding: 12px 14px;
  border: 1px solid var(--border-glass);
  border-radius: 12px;
  background: var(--surface-glass-panel);
  box-shadow: var(--shadow-panel);
  color: inherit;
  text-align: left;
  cursor: pointer;
  transition: border-color 0.16s ease, background 0.16s ease, transform 0.16s ease;
}

.provider-card {
  min-height: 84px;
}

.provider-card:hover,
.custom-card:hover,
.model-card:hover,
.provider-card:focus-visible,
.custom-card:focus-visible,
.model-card:focus-visible,
.catalog-reload:focus-visible,
.model-context__back:focus-visible,
.model-type-pill:focus-visible {
  outline: none;
  border-color: color-mix(in srgb, var(--el-color-primary) 45%, var(--border-glass));
  background: var(--surface-glass-control);
}

.provider-card:hover,
.custom-card:hover,
.model-card:hover {
  transform: translateY(-1px);
}

.provider-card__body,
.custom-card__body,
.model-card__body {
  min-width: 0;
  flex: 1;
  display: grid;
  gap: 4px;
}

.provider-card__body strong,
.custom-card__body strong,
.model-card__body strong {
  color: var(--text-primary);
  font-size: 14px;
  line-height: 1.3;
}

.provider-card__body span {
  color: var(--text-muted);
  font-size: 12px;
}

.provider-card__arrow {
  flex: 0 0 auto;
  color: var(--text-muted);
}

.catalog-other {
  display: grid;
  gap: 8px;
}

.catalog-other h4 {
  color: var(--text-secondary);
  font-size: 13px;
  font-weight: 600;
}

.custom-card__icon {
  display: inline-flex;
  width: 36px;
  height: 36px;
  flex: 0 0 auto;
  align-items: center;
  justify-content: center;
  border: 1px dashed var(--border-glass);
  border-radius: 10px;
  background: var(--surface-glass-control);
  color: var(--el-color-primary);
}

.custom-card__body p {
  color: var(--text-muted);
  font-size: 12px;
  line-height: 1.45;
}

.model-context {
  display: grid;
  gap: 10px;
}

.model-context__back {
  display: inline-flex;
  width: fit-content;
  align-items: center;
  gap: 4px;
  padding: 0;
  border: 0;
  background: transparent;
  color: var(--el-color-primary);
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
}

.model-context__brand {
  display: flex;
  align-items: center;
  gap: 10px;
}

.model-context__brand strong {
  color: var(--text-primary);
  font-size: 14px;
}

.model-context__brand p {
  margin-top: 2px;
  color: var(--text-muted);
  font-size: 12px;
}

.catalog-header--model {
  align-items: center;
}

.model-type-pills {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.model-type-pill {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  height: 30px;
  padding: 0 10px;
  border: 1px solid var(--border-glass);
  border-radius: 999px;
  background: var(--surface-glass-control);
  color: var(--text-secondary);
  font-size: 12px;
  cursor: pointer;
}

.model-type-pill.is-active {
  border-color: color-mix(in srgb, var(--el-color-primary) 45%, var(--border-glass));
  background: color-mix(in srgb, var(--el-color-primary) 12%, var(--surface-glass-control));
  color: var(--text-primary);
}

.model-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
  max-height: min(40vh, 380px);
  overflow: auto;
  padding-right: 2px;
}

.model-card {
  min-height: 88px;
  align-items: flex-start;
}

.model-card__title-row {
  display: flex;
  gap: 8px;
  align-items: flex-start;
  justify-content: space-between;
}

.model-card__type {
  color: var(--text-muted);
  font-size: 12px;
  white-space: nowrap;
}

.model-card code {
  color: var(--text-secondary);
  font-size: 12px;
  word-break: break-all;
}

.model-card p {
  color: var(--text-muted);
  font-size: 12px;
  line-height: 1.45;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.catalog-empty {
  display: grid;
  place-items: center;
  min-height: 180px;
  border: 1px solid var(--border-glass);
  border-radius: 12px;
  background: var(--surface-glass-panel);
}

@keyframes catalog-spin {
  to {
    transform: rotate(360deg);
  }
}

@media (max-width: 860px) {
  .catalog-skeleton,
  .provider-grid,
  .model-grid {
    grid-template-columns: 1fr;
  }

  .catalog-search {
    width: 100%;
  }
}

@media (prefers-reduced-motion: reduce) {
  .provider-card,
  .custom-card,
  .model-card,
  .catalog-reload .is-loading {
    transition: none;
    animation: none;
  }

  .provider-card:hover,
  .custom-card:hover,
  .model-card:hover {
    transform: none;
  }
}
</style>
