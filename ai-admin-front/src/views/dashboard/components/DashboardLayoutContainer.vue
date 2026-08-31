<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import type {
  DashboardLayoutDocument,
  DashboardWidgetConfig,
  DashboardWidgetContext,
  DashboardWidgetRect,
} from '@/types/operationsDashboard'
import DashboardGrid from './DashboardGrid.vue'
import DashboardLayoutToolbar from './DashboardLayoutToolbar.vue'
import DashboardWidgetCatalogDrawer from './DashboardWidgetCatalogDrawer.vue'
import DashboardWidgetHost from './DashboardWidgetHost.vue'
import DashboardWidgetSettingsDialog from './DashboardWidgetSettingsDialog.vue'

/**
 * 看板布局容器：把网格、工具栏、组件目录与设置面板装配在一起。
 * 编辑状态与历史由 useDashboardLayoutEditor 提供；本组件只做视图装配、窄屏检测与编辑暂停。
 */
const props = withDefaults(
  defineProps<{
    context: DashboardWidgetContext
    layout: DashboardLayoutDocument
    editing: boolean
    canUndo: boolean
    canRedo: boolean
    saveError?: string | null
    layoutSourceLabel: string
    savedRevision: number
  }>(),
  {
    saveError: null,
  },
)

const emit = defineEmits<{
  narrowChange: [narrow: boolean]
  beginEdit: []
  cancelEdit: []
  saveEdit: []
  undo: []
  redo: []
  tidy: []
  resetDefault: []
  addWidget: [widgetKey: string]
  removeWidget: [id: string]
  moveWidget: [id: string, dx: number, dy: number]
  resizeWidget: [id: string, dw: number, dh: number]
  commitRect: [id: string, rect: DashboardWidgetRect]
  updateConfig: [id: string, patch: Partial<DashboardWidgetConfig>]
  openAgent: [agent: DashboardWidgetContext['agentRanking'][number]]
  openProject: [project: DashboardWidgetContext['projectSummaries'][number]]
  openProjects: []
  openAgents: []
  openRunops: []
  openAttention: [target: DashboardWidgetContext['attentionItems'][number]['to']]
  openRunTrace: [traceId: string]
  retryRuns: []
  retryAll: []
}>()

const NARROW_BREAKPOINT = 1000
const catalogOpen = ref(false)
const selectedId = ref<string | null>(null)
const settingsId = ref<string | null>(null)
const rootRef = ref<HTMLElement | null>(null)
// 初始值用窗口宽度兜底；真实宽度由 ResizeObserver / 测量回调覆盖（忽略 0 测量）。
const containerWidth = ref(typeof window === 'undefined' ? NARROW_BREAKPOINT : window.innerWidth)
let resizeObserver: ResizeObserver | null = null

const narrow = computed(() => containerWidth.value < NARROW_BREAKPOINT)
/** 编辑会话因窄屏暂停：草稿保留，但所有变更动作被阻止。 */
const editPaused = computed(() => props.editing && narrow.value)

// 暂停时关闭残留的目录与设置面板，防止暂停态 overlay 仍能提交变更；草稿与历史保留。
watch(editPaused, (paused) => {
  if (paused) {
    catalogOpen.value = false
    settingsId.value = null
  }
})

watch(narrow, (value) => emit('narrowChange', value), { immediate: true })

function measureWidth() {
  const element = rootRef.value
  const measured = element ? element.getBoundingClientRect().width : 0
  if (measured > 0) containerWidth.value = measured
  else if (typeof window !== 'undefined') containerWidth.value = window.innerWidth
}

function handleWindowResize() {
  measureWidth()
}

onMounted(() => {
  measureWidth()
  if (typeof ResizeObserver !== 'undefined') {
    resizeObserver = new ResizeObserver((entries) => {
      const width = entries[0]?.contentRect?.width ?? 0
      if (width > 0) containerWidth.value = width
    })
    resizeObserver.observe(rootRef.value as Element)
  }
  window.addEventListener('resize', handleWindowResize)
})

onBeforeUnmount(() => {
  resizeObserver?.disconnect()
  window.removeEventListener('resize', handleWindowResize)
})

const settingsInstance = computed(
  () => props.layout.items.find((item) => item.instanceId === settingsId.value) ?? null,
)

function onSelect(id: string) {
  selectedId.value = id
}

function beginEdit() {
  selectedId.value = null
  emit('beginEdit')
}

function cancelEdit() {
  selectedId.value = null
  settingsId.value = null
  catalogOpen.value = false
  emit('cancelEdit')
}

function saveEdit() {
  if (editPaused.value) return
  selectedId.value = null
  settingsId.value = null
  emit('saveEdit')
}

/** 撤销/重做同样修改草稿；暂停时必须禁止。 */
function undo() {
  if (editPaused.value) return
  emit('undo')
}

function redo() {
  if (editPaused.value) return
  emit('redo')
}

/** 整理布局是草稿变更；暂停时必须禁止。 */
function tidy() {
  if (editPaused.value) return
  emit('tidy')
}

function removeWidget(id: string) {
  if (editPaused.value) return
  emit('removeWidget', id)
}

function openSettings(id: string) {
  if (editPaused.value) return
  settingsId.value = id
}

function updateConfig(id: string, patch: Partial<DashboardWidgetConfig>) {
  if (editPaused.value) return
  emit('updateConfig', id, patch)
}

function openCatalog() {
  if (editPaused.value) return
  catalogOpen.value = true
}

function addWidget(widgetKey: string) {
  if (editPaused.value) return
  emit('addWidget', widgetKey)
}

function resetDefault() {
  if (editPaused.value) return
  emit('resetDefault')
}

function moveWidget(id: string, dx: number, dy: number) {
  if (editPaused.value) return
  emit('moveWidget', id, dx, dy)
}

function resizeWidget(id: string, dw: number, dh: number) {
  if (editPaused.value) return
  emit('resizeWidget', id, dw, dh)
}

function commitRect(id: string, rect: DashboardWidgetRect) {
  if (editPaused.value) return
  emit('commitRect', id, rect)
}

/** 空布局占位：宽屏下允许直接从组件目录添加模块（必要时先进入编辑）。 */
function addFromEmptyState() {
  if (narrow.value) return
  if (!props.editing) beginEdit()
  catalogOpen.value = true
}
</script>

<template>
  <div ref="rootRef" class="dash-layout">
    <DashboardLayoutToolbar
      v-if="editing"
      :editing="editing"
      :can-undo="canUndo"
      :can-redo="canRedo"
      :save-error="saveError"
      :layout-source-label="layoutSourceLabel"
      :saved-revision="savedRevision"
      :narrow="narrow"
      @begin-edit="beginEdit"
      @cancel="cancelEdit"
      @save="saveEdit"
      @open-catalog="openCatalog"
      @undo="undo"
      @redo="redo"
      @tidy="tidy"
      @reset-default="resetDefault"
    />
    <div
      v-if="layout.items.length === 0"
      class="dash-layout__empty"
      data-testid="layout-empty"
    >
      <strong>当前布局没有任何模块</strong>
      <p v-if="narrow">当前内容区小于 1000px，布局编辑不可用；放大窗口后可从组件目录添加模块。</p>
      <button v-else type="button" data-testid="layout-empty-add" @click="addFromEmptyState">从组件目录添加模块</button>
    </div>
    <DashboardGrid
      v-else
      class="dash-layout__grid"
      :items="layout.items"
      :editing="editing && !narrow"
      :narrow="narrow"
      :selected-id="selectedId"
      @select="onSelect"
      @commit-rect="commitRect"
    >
      <template #default="{ instance }">
        <DashboardWidgetHost
          :instance="instance"
          :context="context"
          :editing="editing && !narrow"
          :selected="selectedId === instance.instanceId"
          @select="onSelect(instance.instanceId)"
          @move="(dx, dy) => moveWidget(instance.instanceId, dx, dy)"
          @resize="(dw, dh) => resizeWidget(instance.instanceId, dw, dh)"
          @remove="removeWidget(instance.instanceId)"
          @settings="openSettings(instance.instanceId)"
          @open-agent="(agent) => emit('openAgent', agent)"
          @open-project="(project) => emit('openProject', project)"
          @open-projects="emit('openProjects')"
          @open-agents="emit('openAgents')"
          @open-runops="emit('openRunops')"
          @open-attention="(target) => emit('openAttention', target)"
          @open-run-trace="(traceId) => emit('openRunTrace', traceId)"
          @retry-runs="emit('retryRuns')"
          @retry-all="emit('retryAll')"
        />
      </template>
    </DashboardGrid>
    <DashboardWidgetCatalogDrawer
      :open="catalogOpen && editing && !editPaused"
      :items="layout.items"
      @close="catalogOpen = false"
      @add="addWidget"
    />
    <DashboardWidgetSettingsDialog
      :instance="settingsInstance"
      @close="settingsId = null"
      @apply="(patch) => { if (settingsId) updateConfig(settingsId, patch) }"
    />
  </div>
</template>

<style scoped lang="scss">
.dash-layout {
  display: grid;
  gap: 10px;
}

.dash-layout__grid {
  min-height: 240px;
}

.dash-layout__empty {
  min-height: 200px;
  display: grid;
  place-content: center;
  gap: 8px;
  padding: 28px;
  text-align: center;
  color: var(--ops-text-muted);
  background: var(--ops-surface-soft);
  border: 1px dashed var(--ops-border);
  border-radius: 8px;

  strong {
    color: var(--ops-text-secondary);
    font-size: 13px;
  }

  p {
    margin: 0;
    font-size: 11px;
  }

  button {
    justify-self: center;
    padding: 6px 16px;
    color: var(--ops-cyan);
    background: var(--ops-active-background);
    border: 1px solid color-mix(in srgb, var(--ops-blue) 30%, transparent);
    border-radius: 5px;
    cursor: pointer;
  }
}
</style>
