export const syntheticKnowledgeDetailConsumer = `<template>
  <WorkbenchPage density="comfortable">
    <PageHeader
      :title="kbInfo?.name || '知识库详情'"
      show-back
      @back="router.push('/knowledge')"
    >
      <template #tags>
        <el-tag>{{ kbCode }}</el-tag>
        <StatusTag
          :label="kbInfo?.scope || 'WORKSPACE'"
          :tone="scopeTagType(kbInfo?.scope)"
        />
      </template>
      <template #meta>
        <span v-if="kbInfo?.projectCode">项目 {{ kbInfo.projectCode }}</span>
      </template>
    </PageHeader>

    <MetricStrip :items="knowledgeMetrics" aria-label="知识库运营指标" />

    <el-tabs v-model="activeTab">
      <el-tab-pane label="运营概览" name="overview">
        <WorkbenchPanel title="命中热度" density="compact">
          <DataTableShell density="compact" :empty="(dashboard?.hotChunks || []).length === 0">
            <el-table :data="dashboard?.hotChunks || []" empty-text=" "><el-table-column prop="chunkIndex" /></el-table>
          </DataTableShell>
        </WorkbenchPanel>
        <WorkbenchPanel title="最近命中" density="compact">
          <DataTableShell density="compact" :empty="(dashboard?.recentHits || []).length === 0">
            <el-table :data="dashboard?.recentHits || []" empty-text=" "><el-table-column prop="queryText" /></el-table>
          </DataTableShell>
        </WorkbenchPanel>
        <WorkbenchPanel title="低置信命中" density="compact">
          <DataTableShell density="compact" :empty="(dashboard?.lowConfidenceHits || []).length === 0">
            <el-table :data="dashboard?.lowConfidenceHits || []" empty-text=" "><el-table-column prop="queryText" /></el-table>
          </DataTableShell>
        </WorkbenchPanel>
        <WorkbenchPanel title="零命中段落" density="compact">
          <DataTableShell density="compact" :empty="(dashboard?.zeroHitChunks || []).length === 0">
            <el-table :data="dashboard?.zeroHitChunks || []" empty-text=" "><el-table-column prop="chunkIndex" /></el-table>
          </DataTableShell>
        </WorkbenchPanel>
      </el-tab-pane>

      <el-tab-pane label="文件" name="files">
        <DataTableShell density="compact" :empty="fileList.length === 0" :loading="filesLoading">
          <el-table :data="fileList" empty-text=" ">
            <el-table-column label="状态">
              <template #default="{ row }"><StatusTag :label="statusText(row.status)" :tone="statusTagType(row.status)" /></template>
            </el-table-column>
          </el-table>
        </DataTableShell>
      </el-tab-pane>

      <el-tab-pane label="段落运营" name="chunks">
        <DataTableShell density="compact" :empty="chunks.length === 0" :loading="chunksLoading">
          <el-table :data="chunks" empty-text=" ">
            <el-table-column label="状态">
              <template #default="{ row }">
                <StatusTag :label="row.enabled === 0 ? '停用' : '启用'" :tone="row.enabled === 0 ? 'info' : 'success'" />
              </template>
            </el-table-column>
          </el-table>
        </DataTableShell>
      </el-tab-pane>

      <el-tab-pane label="命中分析" name="hits">
        <DataTableShell density="compact" :empty="hitLogs.length === 0" :loading="hitsLoading">
          <el-table :data="hitLogs" empty-text=" ">
            <el-table-column label="直接返回">
              <template #default="{ row }">
                <StatusTag :label="row.directReturn ? '是' : '否'" :tone="row.directReturn ? 'success' : 'info'" />
              </template>
            </el-table-column>
          </el-table>
        </DataTableShell>
      </el-tab-pane>

      <el-tab-pane label="检索策略" name="policy" />

      <el-tab-pane label="标签" name="tags">
        <div class="tag-panel-stack">
          <WorkbenchPanel title="标签库" density="compact">
            <DataTableShell density="compact" :empty="tagStats.length === 0">
              <el-table :data="tagStats" empty-text=" "><el-table-column prop="tagKey" /></el-table>
            </DataTableShell>
          </WorkbenchPanel>
          <DataTableShell density="compact" :empty="tags.length === 0">
            <el-table :data="tags" empty-text=" "><el-table-column prop="tagValue" /></el-table>
          </DataTableShell>
        </div>
      </el-tab-pane>

      <el-tab-pane label="问题映射" name="questions">
        <DataTableShell density="compact" :empty="questions.length === 0">
          <el-table :data="questions" empty-text=" "><el-table-column prop="question" /></el-table>
        </DataTableShell>
      </el-tab-pane>
    </el-tabs>

    <AppDialog v-model="tagDialogVisible" title="新增标签" width="460px">
      <el-form />
      <template #footer><el-button @click="tagDialogVisible = false">取消</el-button></template>
    </AppDialog>
    <AppDialog v-model="batchTagDialogVisible" title="Batch Tags" width="520px">
      <el-form />
      <template #footer><el-button @click="batchTagDialogVisible = false">Cancel</el-button></template>
    </AppDialog>
    <AppDialog v-model="questionDialogVisible" title="新增问题映射" width="560px">
      <el-form />
      <template #footer><el-button @click="questionDialogVisible = false">取消</el-button></template>
    </AppDialog>
  </WorkbenchPage>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Plus, Refresh, Search, Upload } from '@element-plus/icons-vue'
import AppDialog from '@/components/common/AppDialog.vue'
import DataTableShell from '@/components/common/DataTableShell.vue'
import MetricStrip from '@/components/common/MetricStrip.vue'
import PageHeader from '@/components/common/PageHeader.vue'
import StatusTag from '@/components/common/StatusTag.vue'
import WorkbenchPage from '@/components/common/WorkbenchPage.vue'
import WorkbenchPanel from '@/components/common/WorkbenchPanel.vue'
import type { MetricStripItem, StatusTone } from '@/components/common/glassWorkbench'

const route = useRoute()
const router = useRouter()
const kbCode = route.params.code as string
const activeTab = ref('overview')
const kbInfo = ref<any>(null)
const dashboard = ref<any>(null)
const fileList = ref<any[]>([])
const chunks = ref<any[]>([])
const hitLogs = ref<any[]>([])
const tagStats = ref<any[]>([])
const tags = ref<any[]>([])
const questions = ref<any[]>([])
const filesLoading = ref(false)
const chunksLoading = ref(false)
const hitsLoading = ref(false)
const tagDialogVisible = ref(false)
const batchTagDialogVisible = ref(false)
const questionDialogVisible = ref(false)
const stats = computed(() => ({ fileCount: 0, chunkCount: 0, activeChunkCount: 0, questionCount: 0, hitCount: 0 }))
const knowledgeMetrics = computed<MetricStripItem[]>(() => [
  { key: 'files', label: '文件', value: stats.value.fileCount, iconKey: 'workflow-sources', tone: 'brand' },
  { key: 'chunks', label: '段落', value: stats.value.chunkCount, iconKey: 'api-discovery', tone: 'info' },
  { key: 'active', label: '启用段落', value: stats.value.activeChunkCount, iconKey: 'agent-ready', tone: 'success' },
  { key: 'questions', label: '问题', value: stats.value.questionCount, iconKey: 'ai-semantic', tone: 'warning' },
  { key: 'hits', label: '命中', value: stats.value.hitCount, iconKey: 'scan', tone: 'brand' },
])

function statusText(status: number) {
  return status === 1 ? '完成' : '未知'
}
function statusTagType(status: number): StatusTone {
  return status === 1 ? 'success' : 'info'
}
function scopeTagType(scope?: string): StatusTone {
  return scope === 'SHARED' ? 'success' : 'info'
}
</script>

<style scoped lang="scss">
.tag-panel-stack {
  display: grid;
  gap: var(--section-gap);
}
</style>
`

export const syntheticGlassWorkbench = `export type WorkbenchDensity = 'compact' | 'comfortable' | 'spacious'
export type StatusTone = 'success' | 'warning' | 'danger' | 'info' | 'neutral'
export type MetricTone = StatusTone | 'brand'
export type WizardStepState = 'pending' | 'current' | 'complete' | 'error' | 'skipped'
export interface MetricStripItem {
  key: string
  label: string
  value: string | number
  hint?: string
  tone?: MetricTone
  iconKey?: string
}
export interface WizardStep {
  key: string
  title: string
  description?: string
  state: WizardStepState
  disabled?: boolean
}
export function densityClass(density: WorkbenchDensity): \`density-\${WorkbenchDensity}\` {
  return \`density-\${density}\`
}
`

export const syntheticMetricStrip = `<script setup lang="ts">
import MetricIconBg from './MetricIconBg.vue'
import { densityClass, type MetricStripItem, type WorkbenchDensity } from './glassWorkbench'

const props = withDefaults(defineProps<{
  items: MetricStripItem[]
  density?: WorkbenchDensity
  presentation?: 'panel' | 'inline'
  ariaLabel?: string
}>(), {
  density: 'comfortable',
  presentation: 'panel',
  ariaLabel: '指标概览',
})
</script>

<template>
  <section
    :class="['metric-strip', 'glass-surface-panel', densityClass(props.density)]"
    :data-presentation="props.presentation"
    role="list"
    :aria-label="props.ariaLabel"
  >
    <article
      v-for="item in props.items"
      :key="item.key"
      class="metric-strip__item"
      role="listitem"
    >
      <MetricIconBg :icon-key="item.iconKey" :tone="item.tone ?? 'brand'" />
      <div class="metric-strip__content">
        <span class="metric-strip__label">{{ item.label }}</span>
        <strong class="metric-strip__value">{{ item.value }}</strong>
        <small v-if="item.hint" class="metric-strip__hint">{{ item.hint }}</small>
      </div>
    </article>
  </section>
</template>

<style scoped lang="scss">
.metric-strip {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(12rem, 1fr));
  gap: var(--section-gap);
  padding: var(--panel-padding);
  border-radius: var(--radius-lg);
}

.metric-strip__item + .metric-strip__item {
  border-inline-start: 1px solid var(--border-divider);
}

.metric-strip[data-presentation='inline'] {
  padding: 0;
  border: 0;
  border-radius: 0;
  background: transparent;
  box-shadow: none;
  backdrop-filter: none;
}
</style>
`

export const syntheticStatusTag = `<script setup lang="ts">
import { computed } from 'vue'
import type { StatusTone } from './glassWorkbench'

const props = withDefaults(defineProps<{
  label: string
  tone?: StatusTone
  size?: 'small' | 'default' | 'large'
  pulse?: boolean
}>(), {
  tone: 'neutral',
  size: 'small',
  pulse: false,
})
const tagType = computed(() => props.tone === 'neutral' ? 'info' : props.tone)
</script>

<template>
  <el-tag
    class="status-tag"
    :class="\`status-tag--\${props.tone}\`"
    :type="tagType"
    :size="props.size"
  >
    <span v-if="props.pulse" class="status-tag__pulse" aria-hidden="true" />
    {{ props.label }}
  </el-tag>
</template>

<style scoped lang="scss">
.status-tag--success {
  color: var(--status-success);
  background: var(--status-success-soft);
}
.status-tag--warning {
  color: var(--status-warning);
  background: var(--status-warning-soft);
}
.status-tag--danger {
  color: var(--status-danger);
  background: var(--status-danger-soft);
}
.status-tag--info {
  color: var(--status-info);
  background: var(--status-info-soft);
}
.status-tag--neutral {
  color: var(--status-neutral);
  background: var(--status-neutral-soft);
}
</style>
`

export const syntheticMetricIconBg = `<script setup lang="ts">
import { computed } from 'vue'
import type { MetricTone } from './glassWorkbench'

const props = withDefaults(defineProps<{
  iconKey?: string
  tone?: string
}>(), {
  iconKey: 'scan',
  tone: 'brand',
})
const toneAliases = {
  brand: 'brand',
  success: 'success',
  warning: 'warning',
  danger: 'danger',
  info: 'info',
  neutral: 'neutral',
  green: 'success',
  orange: 'warning',
  muted: 'neutral',
} satisfies Record<string, MetricTone>
const semanticTone = computed<MetricTone>(() => toneAliases[props.tone] ?? 'brand')
</script>

<template>
  <span class="metric-icon-bg" :class="\`metric-icon-bg--\${semanticTone}\`" aria-hidden="true">
    <slot v-if="$slots.default" />
    <svg v-else class="metric-icon-bg__svg" viewBox="0 0 24 24" focusable="false" />
  </span>
</template>

<style scoped lang="scss">
.metric-icon-bg {
  width: var(--metric-icon-bg-size, 48px);
  height: var(--metric-icon-bg-size, 48px);
  border-radius: var(--metric-icon-bg-radius, 14px);
  background: var(--surface-glass-control);
  box-shadow: var(--inner-highlight);
}
.metric-icon-bg__svg {
  width: var(--metric-icon-glyph-size, 24px);
  stroke-width: var(--metric-icon-stroke-width, 1.55);
}
.metric-icon-bg--brand {
  color: var(--brand-active);
  background: var(--surface-glass-selected);
}
.metric-icon-bg--success {
  color: var(--status-success);
  background: var(--status-success-soft);
}
.metric-icon-bg--warning {
  color: var(--status-warning);
  background: var(--status-warning-soft);
}
.metric-icon-bg--danger {
  color: var(--status-danger);
  background: var(--status-danger-soft);
}
.metric-icon-bg--info {
  color: var(--status-info);
  background: var(--status-info-soft);
}
.metric-icon-bg--neutral {
  color: var(--status-neutral);
  background: var(--status-neutral-soft);
}
</style>
`

export const syntheticCommonStatusTag = `<script setup lang="ts">
import { computed } from 'vue'
import StatusTag from './common/StatusTag.vue'
import type { StatusTone } from './common/glassWorkbench'
import { commonStatusTagType, formatCommonStatusLabel } from '@/utils/uiLabels'

const props = defineProps<{
  status?: string | null
}>()
const label = computed(() => formatCommonStatusLabel(props.status))
const tone = computed<StatusTone>(() => commonStatusTagType(props.status))
</script>

<template>
  <StatusTag :label="label" :tone="tone" />
</template>
`

export const syntheticPageHeader = `<script setup lang="ts">
import { densityClass, type WorkbenchDensity } from './glassWorkbench'

const props = withDefaults(defineProps<{
  title: string
  eyebrow?: string
  description?: string
  density?: WorkbenchDensity
  presentation?: 'panel' | 'plain'
  backLabel?: string
  showBack?: boolean
}>(), {
  density: 'comfortable',
  presentation: 'panel',
  backLabel: '返回',
  showBack: false,
})
const emit = defineEmits<{ back: [] }>()
</script>

<template>
  <header :class="['app-page-header', 'glass-surface-panel', densityClass(props.density)]" :data-presentation="props.presentation">
    <button
      v-if="props.showBack"
      class="app-page-header__back"
      :aria-label="props.backLabel"
      @click="emit('back')"
    >
      Back
    </button>
    <slot name="leading" />
    <slot name="tags" />
    <slot name="meta" />
    <slot name="actions" />
  </header>
</template>

<style scoped lang="scss">
.app-page-header {
  padding: var(--layout-page-header-padding-block) var(--layout-page-header-padding-inline);
  gap: var(--section-gap);
}

.app-page-header__back:focus-visible {
  outline: 2px solid var(--border-focus);
}
</style>
`

export const syntheticWorkbenchPanel = `<script setup lang="ts">
import { computed } from 'vue'
import { densityClass, type WorkbenchDensity } from './glassWorkbench'

const props = withDefaults(defineProps<{
  title?: string
  description?: string
  density?: WorkbenchDensity
  level?: 'panel' | 'control'
}>(), {
  density: 'comfortable',
  level: 'panel',
})
const surfaceClass = computed(() => props.level === 'control' ? 'glass-surface-control' : 'glass-surface-panel')
</script>

<template>
  <section :class="['workbench-panel', surfaceClass, densityClass(props.density)]">
    <header class="workbench-panel__header">
      <slot name="header" />
      <div class="workbench-panel__actions"><slot name="actions" /></div>
    </header>
    <div class="workbench-panel__body"><slot /></div>
    <footer class="workbench-panel__footer"><slot name="footer" /></footer>
  </section>
</template>

<style scoped lang="scss">
.workbench-panel {
  padding: var(--panel-padding);
  gap: var(--section-gap);
}
</style>
`

export const syntheticFilterBar = `<script setup lang="ts">
import { densityClass, type WorkbenchDensity } from './glassWorkbench'

const props = withDefaults(defineProps<{
  density?: WorkbenchDensity
  presentation?: 'panel' | 'inline'
  loading?: boolean
  showReset?: boolean
  queryLabel?: string
  resetLabel?: string
}>(), {
  density: 'comfortable',
  presentation: 'panel',
  loading: false,
  showReset: true,
  queryLabel: '查询',
  resetLabel: '重置',
})
const emit = defineEmits<{ query: []; reset: [] }>()
</script>

<template>
  <form
    :class="['filter-bar', 'glass-surface-control', densityClass(props.density)]"
    :data-presentation="props.presentation"
    @submit.prevent="emit('query')"
  >
    <div class="filter-bar__fields"><slot /></div>
    <div class="filter-bar__actions">
      <slot name="actions">
        <el-button
          v-if="props.showReset"
          native-type="button"
          @click="emit('reset')"
        >
          {{ props.resetLabel }}
        </el-button>
        <el-button type="primary" native-type="submit" :loading="props.loading">
          {{ props.queryLabel }}
        </el-button>
      </slot>
    </div>
  </form>
</template>

<style scoped lang="scss">
.filter-bar {
  display: flex;
  gap: var(--section-gap);
  padding: var(--panel-padding);
}

.filter-bar[data-presentation='inline'] {
  padding: 0;
  border: 0;
  border-radius: 0;
  background: transparent;
  box-shadow: none;
}
</style>
`

export const syntheticDataTableShell = `<script setup lang="ts">
import { densityClass, type WorkbenchDensity } from './glassWorkbench'

const props = withDefaults(defineProps<{
  density?: WorkbenchDensity
  loading?: boolean
  empty?: boolean
  preserveEmptyBody?: boolean
  emptyDescription?: string
  currentPage?: number
  pageSize?: number
  total?: number
  pageSizes?: number[]
  paginationLayout?: string
}>(), {
  density: 'comfortable',
  loading: false,
  empty: false,
  preserveEmptyBody: false,
  emptyDescription: '暂无数据',
  currentPage: 1,
  pageSize: 20,
  total: 0,
  pageSizes: () => [10, 20, 50, 100],
  paginationLayout: 'total, sizes, prev, pager, next, jumper',
})
const emit = defineEmits<{
  'update:currentPage': [page: number]
  'update:pageSize': [size: number]
  pageChange: [page: number]
  sizeChange: [size: number]
}>()
function handlePageChange(page: number) {
  emit('update:currentPage', page)
  emit('pageChange', page)
}
function handleSizeChange(size: number) {
  emit('update:pageSize', size)
  emit('sizeChange', size)
}
</script>

<template>
  <section
    v-loading="props.loading"
    :class="['data-table-shell', 'glass-surface-panel', densityClass(props.density), { 'is-empty': props.empty && !props.loading }, { 'data-table-shell--preserve-empty-body': props.preserveEmptyBody }]"
  >
    <div v-if="$slots.toolbar" class="data-table-shell__toolbar">
      <slot name="toolbar" />
    </div>
    <div class="data-table-shell__body"><slot /></div>
    <div v-if="props.empty && !props.loading" class="data-table-shell__empty">
      <slot name="empty"><el-empty :description="props.emptyDescription" /></slot>
    </div>
    <div
      v-if="$slots.pagination || props.total > 0"
      class="data-table-shell__pagination"
    >
      <slot name="pagination">
        <el-pagination
          :current-page="props.currentPage"
          :page-size="props.pageSize"
          :total="props.total"
          :page-sizes="props.pageSizes"
          :layout="props.paginationLayout"
          @update:current-page="handlePageChange"
          @update:page-size="handleSizeChange"
        />
      </slot>
    </div>
  </section>
</template>

<style scoped lang="scss">
.data-table-shell {
  display: flex;
  gap: var(--section-gap);
  padding: var(--panel-padding);
}
</style>
`

export const syntheticAppDialog = `<script setup lang="ts">
defineOptions({ inheritAttrs: false })

const props = defineProps<{
  modelValue: boolean
  title: string
  description?: string
  width?: string | number
}>()
const emit = defineEmits<{
  'update:modelValue': [value: boolean]
}>()
</script>

<template>
  <el-dialog
    v-bind="$attrs"
    class="app-dialog glass-surface-overlay"
    :model-value="props.modelValue"
    :title="props.title"
    :width="props.width"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <template v-if="$slots.header" #header="headerProps">
      <slot name="header" v-bind="headerProps" />
    </template>
    <p v-if="props.description" class="app-dialog__description">
      {{ props.description }}
    </p>
    <hr v-if="props.description" class="app-dialog__divider" />
    <slot />
    <template v-if="$slots.footer" #footer>
      <slot name="footer" />
    </template>
  </el-dialog>
</template>

<style scoped lang="scss">
.app-dialog__description {
  margin: 0;
  color: var(--text-secondary);
  line-height: 1.6;
}

.app-dialog__divider {
  margin: var(--section-gap) 0;
  border: 0;
  border-block-start: 1px solid var(--border-divider);
}
</style>
`

export const syntheticWizardDialog = `<script setup lang="ts">
import { computed } from 'vue'

import AppDialog from './AppDialog.vue'
import {
  densityClass,
  type StatusTone,
  type WizardStep,
  type WizardStepState,
} from './glassWorkbench'

const props = withDefaults(
  defineProps<{
    modelValue: boolean
    title: string
    description?: string
    steps: WizardStep[]
    activeStep: number
    navigable?: boolean
    canAdvance?: boolean
    loading?: boolean
    finishLabel?: string
  }>(),
  {
    navigable: false,
    canAdvance: true,
    loading: false,
    finishLabel: '完成',
  },
)

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  stepChange: [index: number]
  back: []
  next: []
  cancel: []
  finish: []
}>()

const stepToneByState = {
  pending: 'neutral',
  current: 'info',
  complete: 'success',
  error: 'danger',
  skipped: 'neutral',
} satisfies Record<WizardStepState, StatusTone>

const activeErrorStep = computed(() => {
  const step = props.steps[props.activeStep]
  return step && step.state === 'error' ? step : undefined
})

function stepTone(state: WizardStepState): StatusTone {
  return stepToneByState[state]
}
</script>

<template>
  <AppDialog
    :model-value="props.modelValue"
    :title="props.title"
    :description="props.description"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <div :class="['wizard-dialog', densityClass('spacious')]">
      <aside class="wizard-dialog__rail glass-surface-control">
        <div v-if="$slots.summary" class="wizard-dialog__summary">
          <slot name="summary" />
        </div>
        <ol class="wizard-dialog__steps" aria-label="步骤">
          <li
            v-for="(step, index) in props.steps"
            :key="step.key"
            class="wizard-dialog__step"
            :class="[
              \`wizard-dialog__step--state-\${step.state}\`,
              \`wizard-dialog__step--tone-\${stepTone(step.state)}\`,
            ]"
            :aria-current="index === props.activeStep ? 'step' : undefined"
          >
            <button
              class="wizard-dialog__step-button"
              type="button"
              :disabled="!props.navigable || step.disabled"
              @click="emit('stepChange', index)"
            >
              <span class="wizard-dialog__step-marker" aria-hidden="true">{{ index + 1 }}</span>
              <span class="wizard-dialog__step-copy">
                <strong class="wizard-dialog__step-title">{{ step.title }}</strong>
                <span v-if="step.description" class="wizard-dialog__step-description">
                  {{ step.description }}
                </span>
              </span>
            </button>
          </li>
        </ol>
      </aside>

      <main class="wizard-dialog__body">
        <div class="wizard-dialog__live" aria-live="polite" aria-atomic="true">
          <div v-if="activeErrorStep" class="wizard-dialog__validation">
            <strong class="wizard-dialog__validation-title">{{ activeErrorStep.title }}</strong>
            <span
              v-if="activeErrorStep.description"
              class="wizard-dialog__validation-description"
            >
              {{ activeErrorStep.description }}
            </span>
          </div>
        </div>
        <slot />
      </main>
    </div>

    <template #footer>
      <slot name="footer">
        <div class="wizard-dialog__footer">
          <el-button native-type="button" @click="emit('cancel')">取消</el-button>
          <el-button
            native-type="button"
            :disabled="props.activeStep === 0 || props.loading"
            @click="emit('back')"
          >
            上一步
          </el-button>
          <el-button
            v-if="props.activeStep < props.steps.length - 1"
            type="primary"
            native-type="button"
            :disabled="!props.canAdvance || props.loading"
            @click="emit('next')"
          >
            下一步
          </el-button>
          <el-button
            v-else
            type="primary"
            native-type="button"
            :disabled="!props.canAdvance"
            :loading="props.loading"
            @click="emit('finish')"
          >
            {{ props.finishLabel }}
          </el-button>
        </div>
      </slot>
    </template>
  </AppDialog>
</template>

<style scoped lang="scss">
.wizard-dialog {
  display: grid;
  grid-template-columns: minmax(13rem, 18rem) minmax(0, 1fr);
  gap: var(--section-gap);
  min-width: 0;
  color: var(--text-primary);
}

.wizard-dialog__rail {
  display: flex;
  flex-direction: column;
  gap: var(--section-gap);
  min-width: 0;
  padding: var(--panel-padding);
  border-radius: var(--radius-lg);
}

.wizard-dialog__summary {
  color: var(--text-secondary);
}

.wizard-dialog__steps {
  display: flex;
  flex-direction: column;
  gap: calc(var(--section-gap) / 2);
  margin: 0;
  padding: 0;
  list-style: none;
}

.wizard-dialog__step-button {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr);
  gap: calc(var(--section-gap) / 2);
  align-items: start;
  width: 100%;
  min-height: var(--control-height);
  padding: calc(var(--panel-padding) / 2);
  border: 0;
  border-radius: var(--radius-md);
  color: var(--text-secondary);
  background: transparent;
  font: inherit;
  text-align: start;
  cursor: pointer;
}

.wizard-dialog__step-button:disabled {
  color: var(--text-disabled);
  cursor: not-allowed;
}

.wizard-dialog__step-button:focus-visible {
  outline: 2px solid var(--border-focus);
  outline-offset: 2px;
}

.wizard-dialog__step-marker {
  display: inline-grid;
  place-items: center;
  min-width: calc(var(--control-height) / 2);
  min-height: calc(var(--control-height) / 2);
  border: 1px solid currentColor;
  border-radius: var(--radius-xl);
}

.wizard-dialog__step-copy {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: calc(var(--section-gap) / 4);
}

.wizard-dialog__step-title,
.wizard-dialog__step-description {
  display: block;
}

.wizard-dialog__step-description {
  color: var(--text-muted);
  font-size: 0.8125rem;
  line-height: 1.45;
}

.wizard-dialog__step--tone-success .wizard-dialog__step-button {
  color: var(--status-success);
  background: var(--status-success-soft);
}

.wizard-dialog__step--tone-danger .wizard-dialog__step-button {
  color: var(--status-danger);
  background: var(--status-danger-soft);
}

.wizard-dialog__step--tone-info .wizard-dialog__step-button {
  color: var(--status-info);
  background: var(--status-info-soft);
}

.wizard-dialog__step--tone-neutral .wizard-dialog__step-button {
  color: var(--status-neutral);
  background: var(--status-neutral-soft);
}

.wizard-dialog__step--state-current .wizard-dialog__step-button {
  border: 1px solid var(--border-focus);
  color: var(--brand-active);
  background: var(--surface-glass-selected);
}

.wizard-dialog__body {
  min-width: 0;
  max-height: min(70vh, 44rem);
  overflow-y: auto;
  padding-inline-end: calc(var(--panel-padding) / 4);
  color: var(--text-primary);
}

.wizard-dialog__live {
  min-height: 0;
}

.wizard-dialog__validation {
  display: flex;
  flex-direction: column;
  gap: calc(var(--section-gap) / 4);
  margin-bottom: var(--section-gap);
  padding: calc(var(--panel-padding) / 2);
  border: 1px solid var(--status-danger);
  border-radius: var(--radius-md);
  color: var(--status-danger);
  background: var(--status-danger-soft);
}

.wizard-dialog__validation-description {
  color: var(--text-secondary);
}

.wizard-dialog__footer {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: calc(var(--section-gap) / 2);
}

@media (max-width: 760px) {
  .wizard-dialog {
    grid-template-columns: 1fr;
  }
}
</style>
`

export const syntheticAppDrawer = `<script setup lang="ts">
defineOptions({ inheritAttrs: false })

const props = defineProps<{
  modelValue: boolean
  title: string
  description?: string
  size?: string | number
}>()
const emit = defineEmits<{
  'update:modelValue': [value: boolean]
}>()
</script>

<template>
  <el-drawer
    v-bind="$attrs"
    class="app-drawer glass-surface-overlay"
    :model-value="props.modelValue"
    :title="props.title"
    :size="props.size"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <template v-if="$slots.header" #header="headerProps">
      <slot name="header" v-bind="headerProps" />
    </template>
    <p v-if="props.description" class="app-drawer__description">
      {{ props.description }}
    </p>
    <hr v-if="props.description" class="app-drawer__divider" />
    <slot />
    <template v-if="$slots.footer" #footer>
      <slot name="footer" />
    </template>
  </el-drawer>
</template>

<style scoped lang="scss">
.app-drawer__description {
  margin: 0;
  color: var(--text-secondary);
  line-height: 1.6;
}

.app-drawer__divider {
  margin: var(--section-gap) 0;
  border: 0;
  border-block-start: 1px solid var(--border-divider);
}
</style>
`

export const syntheticElementPlusMappings = `.el-table {
  --el-table-header-bg-color: var(--surface-reading-header);
  --el-table-header-text-color: var(--text-muted);
  --el-table-text-color: var(--text-secondary);
  --el-table-border-color: var(--border-divider);
}

.el-pagination {
  --el-fill-color-blank: var(--surface-solid-control);
  --el-pagination-button-color: var(--text-secondary);
  --el-pagination-button-disabled-color: var(--text-disabled);
  --el-pagination-button-disabled-bg-color: var(--surface-solid-disabled);
  --el-color-primary: var(--brand-primary);
  --el-border-color: var(--border-readable);
}

.el-tabs {
  --el-text-color-primary: var(--text-primary);
  --el-text-color-regular: var(--text-secondary);
  --el-border-color-light: var(--border-subtle);
  --el-color-primary: var(--brand-primary);
}

.el-dialog {
  --el-dialog-bg-color: var(--surface-solid-overlay);
  --el-text-color-primary: var(--text-primary);
  --el-border-color: var(--border-readable);
}

.el-drawer {
  --el-drawer-bg-color: var(--surface-solid-overlay);
  --el-text-color-primary: var(--text-primary);
  --el-border-color: var(--border-readable);
}

.el-pagination .btn-prev:focus-visible,
.el-pagination .btn-next:focus-visible,
.el-pagination .el-pager li:focus-visible,
.el-tabs__item:focus-visible {
  outline: 2px solid var(--border-focus);
  outline-offset: 2px;
}
`

export const syntheticComponent = `<template>
  <section class="synthetic-panel glass-surface-panel density-comfortable">
    Contract baseline
  </section>
</template>

<style scoped lang="scss">
.synthetic-panel {
  color: var(--text-primary);
  background: rgb(var(--brand-primary-rgb) / 0.12);
  box-shadow: var(--shadow-panel);
}
</style>
`

export const syntheticConsumer = `<script setup lang="ts">
import WorkbenchPanel from '@/components/common/WorkbenchPanel.vue'
</script>

<template>
  <WorkbenchPanel>Representative content</WorkbenchPanel>
</template>
`

export const syntheticToolListConsumer = `<template>
  <WorkbenchPage density="comfortable">
    <PageHeader
      title="Tool 管理"
      description="管理平台可被 Agent 调用的 Tool、语义信息与运行开关"
    >
      <template #actions>
        <el-button type="primary" @click="openCreateDialog">新建 Tool</el-button>
        <el-button :loading="loading" @click="onRefresh">刷新</el-button>
      </template>
    </PageHeader>

    <FilterBar :loading="loading" @query="handleSearch" @reset="resetFilters">
      <el-form-item label="关键词">
        <el-input
          v-model="filters.keyword"
          clearable
          placeholder="工具名或描述"
          style="width: 200px"
          @keyup.enter="handleSearch"
        />
      </el-form-item>
      <el-form-item label="来源">
        <el-select v-model="filters.source" clearable placeholder="全部" style="width: 130px">
          <el-option label="code" value="code" />
          <el-option label="scanner" value="scanner" />
          <el-option label="manual" value="manual" />
        </el-select>
      </el-form-item>
      <el-form-item label="启用">
        <el-select v-model="filters.enabled" clearable placeholder="全部" style="width: 120px">
          <el-option label="是" :value="true" />
          <el-option label="否" :value="false" />
        </el-select>
      </el-form-item>
    </FilterBar>

    <DataTableShell
      v-model:current-page="pagination.current"
      v-model:page-size="pagination.size"
      density="compact"
      :loading="loading"
      :empty="tools.length === 0"
      empty-description="暂无数据，请调整条件或先在后端注册 Tool"
      :total="total"
      :page-sizes="[10, 20, 50, 100]"
      @page-change="fetchTools"
      @size-change="handlePageSizeChange"
    >
      <el-table :data="tools" empty-text=" " @expand-change="onToolExpandChange">
        <el-table-column label="来源">
          <template #default="{ row }">
            <StatusTag :label="row.source" :tone="sourceTagType(row.source)" />
          </template>
        </el-table-column>
        <el-table-column label="API 目录">
          <template #default="{ row }">
            <StatusTag
              :label="catalogHealthLabel(row.catalogLinkStatus)"
              :tone="catalogHealthTagType(row.catalogLinkStatus)"
            />
          </template>
        </el-table-column>
      </el-table>
    </DataTableShell>

    <WizardDialog
      v-model="formDialogVisible"
      :title="formDialogTitle"
      :steps="toolEditorStepsWithState"
      :active-step="activeToolStep"
      :can-advance="canAdvanceToolStep"
      :loading="saving"
      finish-label="保存"
      width="1180px"
      top="3vh"
      append-to-body
      @back="activeToolStep -= 1"
      @next="advanceToolStep"
      @finish="handleSave"
      @cancel="formDialogVisible = false"
    >
      <section class="tool-editor-main">
        <div class="tool-editor-summary">Summary</div>
        <el-alert title="Alert" />
        <el-form>
          <div v-show="activeToolStep === 0" class="tool-editor-panel">Identity</div>
          <div v-show="activeToolStep === 1" class="tool-editor-panel">Endpoint</div>
          <div v-show="activeToolStep === 2" class="tool-editor-panel"><ParameterTable /></div>
          <div v-show="activeToolStep === 3" class="tool-editor-panel">Release</div>
        </el-form>
      </section>
    </WizardDialog>

    <AppDialog
      v-model="testDialogVisible"
      :title="\`测试工具 — \${testingTool?.title || testingTool?.name}\`"
      width="600px"
      append-to-body
    >
      <el-form v-if="testingTool"><el-form-item><el-input /></el-form-item></el-form>
      <div v-if="testResult" class="test-result-area"><pre>{{ testResult }}</pre></div>
      <template #footer>
        <el-button @click="testDialogVisible = false">关闭</el-button>
        <el-button type="primary" :loading="testRunning" @click="handleTest">执行</el-button>
      </template>
    </AppDialog>
  </WorkbenchPage>
</template>

<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import PageHeader from '@/components/common/PageHeader.vue'
import FilterBar from '@/components/common/FilterBar.vue'
import DataTableShell from '@/components/common/DataTableShell.vue'
import StatusTag from '@/components/common/StatusTag.vue'
import WorkbenchPage from '@/components/common/WorkbenchPage.vue'
import WizardDialog from '@/components/common/WizardDialog.vue'
import AppDialog from '@/components/common/AppDialog.vue'
import type { StatusTone, WizardStep } from '@/components/common/glassWorkbench'

const loading = ref(false)
const saving = ref(false)
const tools = ref([])
const total = ref(0)
const pagination = reactive({ current: 1, size: 20 })
const filters = reactive({ keyword: '', source: undefined, enabled: undefined })
const formDialogVisible = ref(false)
const formDialogTitle = computed(() => '新建 Tool')
const activeToolStep = ref(0)
const form = reactive({ title: '', name: '', description: '' })
const toolEditorSteps = [
  { key: 'identity', title: '身份信息', desc: '名称、描述与项目' },
  { key: 'endpoint', title: '调用配置', desc: 'HTTP 地址与类型' },
  { key: 'parameters', title: '参数定义', desc: 'Agent 入参 Schema' },
  { key: 'release', title: '运行控制', desc: '启用与可见性' },
]
const toolEditorStepsWithState = computed<WizardStep[]>(() =>
  toolEditorSteps.map((step, index) => ({
    key: step.key,
    title: step.title,
    description: step.desc,
    state:
      index < activeToolStep.value
        ? 'complete'
        : index === activeToolStep.value
          ? 'current'
          : 'pending',
  })),
)
const canAdvanceToolStep = computed(
  () =>
    activeToolStep.value !== 0 ||
    Boolean(form.title.trim() && form.name.trim() && form.description.trim()),
)
const testDialogVisible = ref(false)
const testingTool = ref({ title: '演示工具', name: 'demo' })
const testResult = ref(null)
const testRunning = ref(false)

function sourceTagType(source: string): StatusTone {
  if (source === 'code') return 'success'
  if (source === 'scanner') return 'warning'
  return 'info'
}
function catalogHealthLabel(status: string) {
  return status
}
function catalogHealthTagType(status: string): StatusTone {
  if (status === 'IN_SYNC') return 'success'
  return 'info'
}
function advanceToolStep() {
  if (!canAdvanceToolStep.value) {
    ElMessage.warning('请填写工具名称、工具标识和描述')
    return
  }
  activeToolStep.value = Math.min(activeToolStep.value + 1, toolEditorSteps.length - 1)
}
function openCreateDialog() {}
function onRefresh() {}
function handleSearch() {}
function resetFilters() {}
function fetchTools() {}
function handlePageSizeChange() {}
function onToolExpandChange() {}
function handleSave() {}
function handleTest() {}
</script>

<style scoped lang="scss">
.tool-editor-main,
.tool-editor-panel {
  min-width: 0;
}
</style>
`

export const syntheticMcpOnboardingConsumer = `<template>
  <WorkbenchPage density="spacious">
    <PageHeader
      variant="workbench"
      domain="platform"
      eyebrow="MCP Onboarding"
      title="MCP 接入向导"
      description="从能力暴露、Client 凭证到外部工具配置，按步骤完成企业 MCP 接入与连通性自检。"
      density="spacious"
    />
    <el-alert
      type="info"
      show-icon
      :closable="false"
      title="一句话理解：把本仓的 Tool / 粗粒度能力通过 MCP 协议暴露，Cursor / Claude Desktop / Dify 等可一行配置接入"
      description="先在『MCP 暴露白名单』勾选要暴露的 Tool；再在『MCP Client』生成 API Key；最后把 Key 填到客户端配置即可。"
    />
    <div class="mcp-onboarding__columns">
      <div class="mcp-onboarding__column">
        <WorkbenchPanel title="① 服务端基础信息" density="spacious">
          <el-descriptions :column="1" border size="default">
            <el-descriptions-item label="协议">MCP (JSON-RPC 2.0)</el-descriptions-item>
            <el-descriptions-item label="协议版本">2024-11-05</el-descriptions-item>
            <el-descriptions-item label="HTTP 端点">
              <code class="mcp-onboarding__inline-code">{{ jsonrpcUrl }}</code>
              <el-button size="small" :icon="DocumentCopy" link @click="copy(jsonrpcUrl)">复制</el-button>
            </el-descriptions-item>
            <el-descriptions-item label="Manifest">
              <code class="mcp-onboarding__inline-code">{{ manifestUrl }}</code>
              <el-button size="small" :icon="DocumentCopy" link @click="copy(manifestUrl)">复制</el-button>
            </el-descriptions-item>
            <el-descriptions-item label="鉴权">
              <code class="mcp-onboarding__inline-code">Authorization: Bearer &lt;API Key&gt;</code>
            </el-descriptions-item>
          </el-descriptions>
        </WorkbenchPanel>
        <WorkbenchPanel title="② Cursor 接入" density="spacious">
          <p>编辑 <code class="mcp-onboarding__inline-code">~/.cursor/mcp.json</code>：</p>
          <CodeSnippetBlock title="~/.cursor/mcp.json" :code="cursorExample" @copy="copy" />
        </WorkbenchPanel>
      </div>
      <div class="mcp-onboarding__column">
        <WorkbenchPanel title="③ Claude Desktop 接入" density="spacious">
          <p>编辑 <code class="mcp-onboarding__inline-code">claude_desktop_config.json</code>：</p>
          <CodeSnippetBlock title="claude_desktop_config.json" :code="claudeExample" @copy="copy" />
          <el-alert type="warning" :closable="false" show-icon>
            Claude Desktop 目前仅支持 stdio MCP；本仓暂不暴露 stdio，可借助
            <a href="https://github.com/modelcontextprotocol/servers" target="_blank">mcp-proxy</a>
            把 HTTP 端点桥接到 stdio。
          </el-alert>
        </WorkbenchPanel>
        <WorkbenchPanel title="④ Dify / OpenClaw / 通用 HTTP MCP" density="spacious">
          <p>直接配置远端 MCP 服务地址：</p>
          <CodeSnippetBlock title="HTTP MCP" :code="genericExample" @copy="copy" />
        </WorkbenchPanel>
        <WorkbenchPanel title="⑤ curl 自检" density="spacious">
          <CodeSnippetBlock title="curl" :code="curlExample" @copy="copy" />
        </WorkbenchPanel>
      </div>
    </div>
  </WorkbenchPage>
</template>

<script setup lang="ts">
import PageHeader from '@/components/common/PageHeader.vue'
import WorkbenchPage from '@/components/common/WorkbenchPage.vue'
import WorkbenchPanel from '@/components/common/WorkbenchPanel.vue'
import CodeSnippetBlock from '@/components/common/CodeSnippetBlock.vue'
const jsonrpcUrl = ''
const manifestUrl = ''
const cursorExample = ''
const claudeExample = ''
const genericExample = ''
const curlExample = ''
const DocumentCopy = undefined
function copy(_text: string) {}
</script>

<style scoped lang="scss">
.mcp-onboarding__columns {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--section-gap);
  min-width: 0;
}

.mcp-onboarding__column {
  display: flex;
  flex-direction: column;
  gap: var(--section-gap);
  min-width: 0;
}

.mcp-onboarding__inline-code {
  overflow-wrap: anywhere;
  word-break: break-word;
}

@media (max-width: 1080px) {
  .mcp-onboarding__columns {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
`

export const syntheticAgentEditConsumer = `<template>
  <WorkbenchPage density="comfortable" full-height>
    <PageHeader
      variant="standard"
      domain="agent"
      :title="isNew ? '新建智能体' : \`编辑智能体 - \${form.name || agentId}\`"
      eyebrow="Agent / Supervisor"
      density="comfortable"
      show-back
      @back="router.push('/agent')"
    >
      <template #tags>
        <StatusTag :label="agentKindLabel(form.agentKind)" tone="neutral" />
        <StatusTag
          :label="form.enabled !== false ? '启用' : '停用'"
          :tone="form.enabled !== false ? 'success' : 'info'"
        />
      </template>
      <template #actions>
        <el-tooltip content="复制 keySlug 和 ID" placement="bottom">
          <span>
            <el-button :icon="DocumentCopy" :disabled="!canCopyAgentIdentity" @click="copyAgentIdentity">
              复制标识
            </el-button>
          </span>
        </el-tooltip>
        <el-button v-if="!isNew" :icon="Share" @click="openWorkflows">Workflow 列表</el-button>
        <el-button v-if="!isNew" type="success" plain :loading="publishing" @click="handlePublish">发布 Supervisor 配置</el-button>
        <el-button type="primary" :loading="saving" @click="handleSave">保存</el-button>
      </template>
    </PageHeader>

    <el-form ref="formRef" :model="form" :rules="rules" label-width="108px" v-loading="pageLoading">
      <main class="workbench-layout" :class="{ 'workbench-layout--with-aside': !isNew }">
        <WorkbenchPanel
          title="身份与策略"
          description="配置 Agent 入口身份、可见性与默认策略；图编排请在 Workflow Studio 完成。"
          density="comfortable"
        >
          <template #actions>
            <el-switch v-model="form.enabled" active-text="启用" inactive-text="停用" />
          </template>
          <div class="form-grid two">
            <el-form-item label="名称" prop="name"><el-input v-model="form.name" placeholder="如：合同审核助手" /></el-form-item>
            <el-form-item label="keySlug" prop="keySlug"><el-input v-model="form.keySlug" placeholder="如 contract-review" :disabled="!isNew" /></el-form-item>
            <el-form-item label="入口类型" prop="agentKind"><el-select v-model="form.agentKind"><el-option v-for="item in agentKindOptions" :key="item.value" :label="item.label" :value="item.value" /></el-select></el-form-item>
            <el-form-item label="可见性"><el-select v-model="form.visibility"><el-option label="项目" value="PROJECT" /><el-option label="私有" value="PRIVATE" /><el-option label="公开" value="PUBLIC" /></el-select></el-form-item>
            <el-form-item label="所属项目"><el-select v-model="form.projectId" clearable filterable placeholder="平台级 / 全局" @change="handleProjectChange"><el-option v-for="project in scanProjects" :key="project.id" :label="projectOptionLabel(project)" :value="project.id" /></el-select></el-form-item>
            <el-form-item label="默认模型"><el-select v-model="form.modelInstanceId" clearable filterable placeholder="可选"><el-option v-for="item in llmModelInstances" :key="item.id" :label="\`\${item.name} (\${item.modelName})\`" :value="item.id" /></el-select></el-form-item>
            <el-form-item label="运行角色" class="wide"><el-select v-model="form.allowedRoles" multiple filterable allow-create default-first-option collapse-tags collapse-tags-tooltip placeholder="留空表示不限制业务角色" /></el-form-item>
          </div>
          <el-form-item label="描述"><el-input v-model="form.description" type="textarea" :rows="2" placeholder="一句话描述入口用途" /></el-form-item>
          <el-form-item label="System Prompt"><el-input v-model="form.systemPrompt" type="textarea" :rows="6" placeholder="定义入口默认角色与回答风格（Workflow 节点可覆盖）" /></el-form-item>
          <el-form-item label="入口配置"><el-input v-model="entryConfigText" type="textarea" :rows="5" placeholder='JSON，如 {"intentType":"GENERAL_CHAT"}' /></el-form-item>
        </WorkbenchPanel>

        <aside v-if="!isNew" class="agent-workbench__aside">
          <WorkbenchPanel
            title="Workflow 工具目录"
            description="当前选择会保存到 Agent 配置草稿，发布后成为 Supervisor 的动态工具白名单。"
            density="comfortable"
          >
            <el-button class="summary-action" type="primary" plain :icon="Share" @click="openWorkflows">打开 Workflow 列表</el-button>
          </WorkbenchPanel>
        </aside>
      </main>
    </el-form>
  </WorkbenchPage>
</template>

<script setup lang="ts">
import PageHeader from '@/components/common/PageHeader.vue'
import WorkbenchPage from '@/components/common/WorkbenchPage.vue'
import WorkbenchPanel from '@/components/common/WorkbenchPanel.vue'
import StatusTag from '@/components/common/StatusTag.vue'
const isNew = true
const agentId = 'new'
const form = { name: '', agentKind: 'PROJECT_ENTRY', enabled: true }
const router = { push: (_path: string) => undefined }
</script>

<style scoped lang="scss">
.workbench-layout {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  gap: var(--section-gap);
}

.workbench-layout--with-aside {
  grid-template-columns: minmax(0, 1fr) 272px;
}

.agent-workbench__aside,
.form-grid {
  min-width: 0;
}

.form-grid {
  display: grid;
  gap: var(--section-gap);
}

.form-grid.two {
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.wide {
  grid-column: span 2;
}

.summary-action {
  width: 100%;
  margin-top: calc(var(--section-gap) / 2);
}

@media (max-width: 1280px) {
  .workbench-layout--with-aside {
    grid-template-columns: minmax(0, 1fr);
  }
}

@media (max-width: 960px) {
  .form-grid.two {
    grid-template-columns: 1fr;
  }

  .wide {
    grid-column: auto;
  }
}
</style>
`
