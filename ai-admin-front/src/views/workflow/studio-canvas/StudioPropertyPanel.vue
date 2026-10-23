<template>
  <aside class="property-panel" :class="{ resizing, 'has-node': !!node && !edge }">
    <div
      v-if="!collapsed"
      class="property-resizer"
      role="separator"
      aria-orientation="vertical"
      aria-label="拖动调整面板宽度"
      @pointerdown="startResize"
      @dblclick="emit('update:width', defaultWidth)"
    />
    <button
      class="property-toggle"
      type="button"
      :aria-label="collapsed ? '展开属性面板' : '收起属性面板'"
      @click="emit('update:collapsed', !collapsed)"
    >
      <el-icon><ArrowRight v-if="!collapsed" /><ArrowLeft v-else /></el-icon>
    </button>
    <div v-if="collapsed" class="property-compact">
      <strong>{{ node?.data.label || (edge ? edgeLabel : '') || '画布摘要' }}</strong>
      <span>{{ node ? nodeKindLabel(node.data.kind) : edge ? '连线' : `${nodeCount} 节点` }}</span>
    </div>
    <div v-else ref="propertyContent" class="property-content">
      <LiquidGlassNotice
        v-if="readOnly"
        class="readonly-alert"
        title="此 Workflow 由其他入口维护，只读展示。请从原创建入口更新。"
        type="warning"
        source="backdrop"
      />

      <div v-if="edge" class="edge-property">
        <div class="property-heading">
          <strong>连线</strong>
          <span>{{ nodeName(edge.source) }} → {{ nodeName(edge.target) }}</span>
        </div>
        <el-form label-position="top" size="small">
          <el-form-item label="什么时候走这条线">
            <el-select
              v-model="edge.condition"
              :disabled="readOnly"
              filterable
              :allow-create="edgeAllowsCustomRoute"
              default-first-option
              placeholder="选择条件"
              style="width: 100%"
              @change="emit('edge-condition-change')"
            >
              <el-option
                v-for="item in edgeConditionOptions"
                :key="item.value"
                :label="item.label"
                :value="item.value"
              />
            </el-select>
          </el-form-item>
        </el-form>
        <div class="condition-help">
          「失败时」：上一个节点出错时走这条线，可在回答里用 <code v-pre>{{ lastError.message }}</code> 说明原因。「结果为空」「有结果」：按上一个节点的输出是否有数据分流，参数提取没拿到必填值也算为空。
        </div>
      </div>

      <div v-else-if="!node" class="workflow-property">
        <div class="property-empty-state">
          <div class="empty-state-icon">
            <el-icon><Aim /></el-icon>
          </div>
          <strong>选择画布中的节点</strong>
          <span>选中节点后在这里直接修改配置、测试节点或查看调试记录。</span>
        </div>
        <div class="property-heading">
          <strong>Workflow 信息</strong>
        </div>
        <el-form label-position="top" size="small" class="property-form" :disabled="readOnly">
          <el-form-item label="名称">
            <el-input v-model="meta.name" @change="emit('mark-dirty')" />
          </el-form-item>
          <el-form-item label="标识">
            <el-input v-model="meta.keySlug" placeholder="如 product-stock-query" @change="emit('mark-dirty')" />
            <div class="field-help">英文标识，用于接口调用和运行记录里识别这个 Workflow；只能用字母、数字、- 和 _。</div>
          </el-form-item>
          <el-form-item label="类型">
            <el-select v-model="meta.workflowKind" style="width: 100%" @change="emit('mark-dirty')">
              <el-option
                v-for="item in WORKFLOW_KIND_SELECT_OPTIONS"
                :key="item.value"
                :label="item.label"
                :value="item.value"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="说明">
            <el-input v-model="meta.description" type="textarea" :rows="3" @change="emit('mark-dirty')" />
          </el-form-item>
          <el-form-item label="默认模型">
            <el-select
              v-model="meta.defaultModelInstanceId"
              filterable
              clearable
              placeholder="选择默认大模型"
              style="width: 100%"
              :loading="modelOptionsLoading"
              @visible-change="(visible: boolean) => visible && emit('load-model-options')"
              @change="emit('mark-dirty')"
            >
              <el-option
                v-for="item in modelOptions"
                :key="item.id"
                :label="modelOptionLabel(item)"
                :value="item.id"
              />
              <template #empty>
                <ModelSelectEmptyState
                  model-type="LLM"
                  :option-count="modelOptions.length"
                  :loading="modelOptionsLoading"
                  :load-error="modelOptionsLoadError"
                  @retry="emit('load-model-options')"
                />
              </template>
            </el-select>
          </el-form-item>
        </el-form>
      </div>

      <div v-else class="node-property">
        <div class="node-property-head">
          <div class="node-property-icon" :style="{ background: kindColor(node.data.kind).bg, color: kindColor(node.data.kind).border }">
            <ModelProviderIcon v-if="nodeModelProvider" class="node-model-provider" :provider="nodeModelProvider" :size="26" />
            <el-icon v-else><component :is="nodeIcon(node.data.kind) || Operation" /></el-icon>
          </div>
          <div class="node-property-title">
            <el-input
              v-model="node.data.label"
              class="node-name-input"
              aria-label="节点名称"
              placeholder="节点名称"
              :disabled="readOnly"
              @change="emit('mark-dirty')"
            />
            <span>{{ nodeKindLabel(node.data.kind) }} <code>{{ node.id }}</code></span>
          </div>
          <el-button
            class="node-property-delete"
            size="small"
            text
            aria-label="删除节点"
            title="删除节点"
            :icon="Delete"
            type="danger"
            :disabled="readOnly || isBoundary"
            @click="emit('delete-node')"
          />
        </div>

        <LiquidGlassTabs :model-value="tab" source="backdrop" class="node-property-tabs" @update:model-value="(value: string | number) => emit('update:tab', value as StudioPropertyTab)">
          <el-tab-pane label="配置" name="config">
            <details :key="node.id" class="node-description">
              <summary><span>节点说明</span><span>{{ node.data.description || '添加说明' }}</span><el-icon><ArrowRight /></el-icon></summary>
              <el-input
                v-model="node.data.description"
                type="textarea"
                :rows="2"
                resize="vertical"
                aria-label="节点说明"
                placeholder="说明这个节点在流程中的职责（可选）"
                :disabled="readOnly"
                @change="emit('mark-dirty')"
              />
            </details>
            <el-form label-position="top" size="small" class="node-config-body" :disabled="readOnly" @submit.prevent>
              <slot name="node-config" :node="node" />
            </el-form>
          </el-tab-pane>

          <el-tab-pane v-if="!isBoundary" name="test">
            <template #label>
              <span class="tab-label">
                测试
                <i v-if="debugResult?.nodeId === node.id" :class="debugResult.success ? 'is-ok' : 'is-fail'" />
              </span>
            </template>
            <el-form label-position="top" size="small" class="node-debug-box">
              <el-form-item label="测试消息">
                <el-input :model-value="debugMessage" @update:model-value="(value: string) => emit('update:debugMessage', value)" />
              </el-form-item>
              <el-form-item label="模拟状态（JSON）">
                <el-input
                  :model-value="debugStateText"
                  type="textarea"
                  :rows="5"
                  @update:model-value="(value: string) => emit('update:debugStateText', value)"
                />
              </el-form-item>
              <el-button type="primary" size="small" :loading="debugLoading" @click="emit('run-node-debug')">
                测试此节点
              </el-button>
              <div v-if="debugResult?.nodeId === node.id" class="node-trace-block">
                <span>
                  输出
                  <el-tag size="small" :type="debugResult.success ? 'success' : 'danger'">
                    {{ debugResult.success ? '通过' : '失败' }}
                  </el-tag>
                </span>
                <pre>{{ formatJson(debugResult.outputState || debugResult.nodeOutput || {}) }}</pre>
              </div>
            </el-form>
          </el-tab-pane>

          <el-tab-pane name="trace">
            <template #label>
              <span class="tab-label">
                调试记录
                <i v-if="trace" :class="trace.status === 'success' ? 'is-ok' : trace.status === 'error' ? 'is-fail' : 'is-wait'" />
              </span>
            </template>
            <div v-if="trace" class="node-trace-panel">
              <div class="node-trace-head">
                <strong>本次 Studio 调试</strong>
                <el-tag size="small" :type="traceTagType(trace.status)">{{ traceStatusText(trace.status) }}</el-tag>
              </div>
              <div class="node-trace-meta">
                <span>{{ formatElapsed(trace.elapsedMs) }}</span>
                <span v-if="trace.errorCode">{{ trace.errorCode }}</span>
                <span v-if="trace.route">分支：{{ trace.route }}</span>
              </div>
              <div v-if="trace.input" class="node-trace-block">
                <span>输入</span>
                <pre>{{ trace.input }}</pre>
              </div>
              <div v-if="trace.output" class="node-trace-block">
                <span>输出</span>
                <pre>{{ trace.output }}</pre>
              </div>
            </div>
            <LiquidGlassNotice
              v-else
              title="还没有本次 Studio 调试结果"
              type="info"
              description="点顶部「调试」跑一遍后，这里会显示该节点的输入和输出。已发布版本的运行请到运行中心查看。"
              source="backdrop"
            />
            <el-button v-if="canReadPublishedRuns" class="published-run-entry" size="small" @click="emit('open-published-runs')">
              查看已发布运行记录
            </el-button>
          </el-tab-pane>
        </LiquidGlassTabs>
      </div>
    </div>
  </aside>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import type { Component } from 'vue'
import { Aim, ArrowLeft, ArrowRight, Delete, Operation } from '@element-plus/icons-vue'
import ModelSelectEmptyState from '@/components/model/ModelSelectEmptyState.vue'
import ModelProviderIcon from '@/views/model/components/ModelProviderIcon.vue'
import { WORKFLOW_KIND_SELECT_OPTIONS } from '@/utils/workflowLabels'
import { formatElapsed, type WorkflowNodeTraceState } from '@/views/workflow/composables/workflowStudioTrace'
import { useWorkflowStudioNodeMetadata } from '@/views/workflow/composables/useWorkflowStudioNodeMetadata'
import type { CanvasEdge, CanvasNode, CanvasNodeKind } from '@/types/studio'
import type { ModelInstance } from '@/types/model'
import type { WorkflowNodeDebugResult } from '@/types/workflow'
import { resolveStudioLlmProvider } from './studioLlmModel'
import {
  STUDIO_PROPERTY_WIDTH_DEFAULT,
  clampPropertyWidth,
  type StudioPropertyTab,
  type StudioWorkflowMeta,
} from './studioPropertyPanel'
import { LiquidGlassNotice, LiquidGlassTabs } from '@/components/common/liquid-glass'

const props = defineProps<{
  collapsed: boolean
  width: number
  tab: StudioPropertyTab
  readOnly: boolean
  node: CanvasNode | null
  edge: CanvasEdge | null
  edgeLabel: string
  edgeConditionOptions: Array<{ value: string; label: string }>
  edgeAllowsCustomRoute: boolean
  canvasNodes: CanvasNode[]
  meta: StudioWorkflowMeta
  modelOptions: ModelInstance[]
  modelOptionsLoading: boolean
  modelOptionsLoadError: boolean
  kindColor: (kind: CanvasNodeKind) => { bg: string; border: string }
  nodeIcon: (kind: CanvasNodeKind) => Component | undefined
  debugMessage: string
  debugStateText: string
  debugLoading: boolean
  debugResult: WorkflowNodeDebugResult | null
  trace: WorkflowNodeTraceState | null
  canReadPublishedRuns: boolean
}>()

const emit = defineEmits<{
  'update:collapsed': [value: boolean]
  'update:width': [value: number]
  'update:tab': [value: StudioPropertyTab]
  'update:debugMessage': [value: string]
  'update:debugStateText': [value: string]
  'mark-dirty': []
  'edge-condition-change': []
  'delete-node': []
  'load-model-options': []
  'run-node-debug': []
  'open-published-runs': []
}>()

const { nodeKindLabel } = useWorkflowStudioNodeMetadata()
const defaultWidth = STUDIO_PROPERTY_WIDTH_DEFAULT
const nodeCount = computed(() => props.canvasNodes.length)
const nodeModelProvider = computed(() => resolveStudioLlmProvider(props.node?.data, {
  defaultModelInstanceId: props.meta.defaultModelInstanceId,
  modelOptions: props.modelOptions,
}))
const isBoundary = computed(() => props.node?.data.kind === 'start' || props.node?.data.kind === 'end')
const resizing = ref(false)
const propertyContent = ref<HTMLElement | null>(null)

watch([() => props.node?.id, () => props.tab], async () => {
  await nextTick()
  const scrollArea = propertyContent.value?.querySelector<HTMLElement>('.node-property-tabs > .el-tabs__content')
  if (scrollArea) scrollArea.scrollTop = 0
})

function nodeName(id: string) {
  const found = props.canvasNodes.find((item) => item.id === id)
  return found?.data.label || id
}

function modelOptionLabel(item: ModelInstance) {
  return `${item.name || item.id} / ${item.provider || '-'} / ${item.modelName || '-'}`
}

function formatJson(value: unknown) {
  return JSON.stringify(value, null, 2)
}

function traceStatusText(status: WorkflowNodeTraceState['status']) {
  if (status === 'running') return '运行中'
  if (status === 'waiting') return '等待'
  return status === 'success' ? '成功' : '失败'
}

function traceTagType(status: WorkflowNodeTraceState['status']) {
  if (status === 'running') return 'primary'
  if (status === 'waiting') return 'warning'
  return status === 'success' ? 'success' : 'danger'
}

let stopResize: (() => void) | null = null

function startResize(event: PointerEvent) {
  if (event.button !== 0) return
  event.preventDefault()
  const startX = event.clientX
  const startWidth = props.width
  resizing.value = true
  const onMove = (move: PointerEvent) => {
    emit('update:width', clampPropertyWidth(startWidth + startX - move.clientX))
  }
  const onUp = () => {
    resizing.value = false
    window.removeEventListener('pointermove', onMove)
    window.removeEventListener('pointerup', onUp)
    stopResize = null
  }
  window.addEventListener('pointermove', onMove)
  window.addEventListener('pointerup', onUp)
  stopResize = onUp
}

onBeforeUnmount(() => stopResize?.())
</script>

<style scoped lang="scss">
.property-panel {
  position: relative;
  container: studio-config / inline-size;
  border-left: 1px solid var(--border-glass-edge);
  background: var(--surface-glass-panel);
  -webkit-backdrop-filter: blur(var(--glass-blur-panel)) saturate(var(--glass-saturation));
  backdrop-filter: blur(var(--glass-blur-panel)) saturate(var(--glass-saturation));
  overflow: hidden;
}
.property-content {
  height: 100%;
  padding: 20px;
  overflow-y: auto;
}
.property-panel.has-node .property-content {
  display: flex;
  flex-direction: column;
  padding: 0;
  overflow: hidden;
}
.readonly-alert {
  flex-shrink: 0;
  margin-bottom: 12px;
}
.node-property {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-height: 0;
}
.node-property-head {
  display: grid;
  grid-template-columns: 36px minmax(0, 1fr) 28px;
  align-items: center;
  flex-shrink: 0;
  gap: 10px;
  min-height: 88px;
  padding: 18px 54px 14px 20px;
}
.node-property-icon {
  display: grid;
  width: 36px;
  height: 36px;
  place-items: center;
  border-radius: 10px;
  font-size: 18px;
}
.node-property-icon .node-model-provider {
  border: 0;
  border-radius: 0;
  background: transparent;
  box-shadow: none;
}
.node-model-provider :deep(.model-provider-icon__img) {
  width: 100%;
  height: 100%;
}
.node-property-title {
  min-width: 0;
}
.node-name-input :deep(.el-input__wrapper) {
  padding: 2px 5px;
  margin-left: -5px;
  background: transparent;
  box-shadow: none;
  border-radius: 4px;
}
.node-name-input :deep(.el-input__wrapper:hover) {
  box-shadow: 0 0 0 1px var(--border-readable) inset;
}
.node-name-input :deep(.el-input__wrapper.is-focus) {
  box-shadow: 0 0 0 1px var(--brand-primary) inset;
}
.node-name-input :deep(.el-input__inner) {
  height: 26px;
  color: var(--text-primary);
  font-size: 15px;
  font-weight: 650;
}
.node-property-title > span {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 3px;
  color: var(--text-muted);
  font-size: 11px;
  white-space: nowrap;
  overflow: hidden;
}
.node-property-title code {
  overflow: hidden;
  color: var(--text-disabled);
  font:
    10px/1.5 Consolas,
    monospace;
  text-overflow: ellipsis;
}
.node-property-delete {
  padding: 6px;
  margin: 0;
  color: var(--text-muted);
}
.node-property-delete:hover {
  color: var(--status-danger);
}
.property-toggle {
  position: absolute;
  top: 27px;
  right: 14px;
  z-index: 3;
  display: grid;
  place-items: center;
  width: 28px;
  height: 28px;
  padding: 0;
  border: 1px solid var(--border-subtle);
  border-radius: 6px;
  background: transparent;
  color: var(--text-muted);
  cursor: pointer;
}
.property-toggle:hover {
  color: var(--brand-primary);
  border-color: var(--border-focus);
}
.property-toggle:focus-visible {
  outline: 2px solid var(--border-focus);
  outline-offset: 3px;
}
.property-compact {
  display: flex;
  height: 100%;
  flex-direction: column;
  align-items: center;
  gap: 10px;
  padding: 70px 8px 14px;
  color: var(--text-muted);
  writing-mode: vertical-rl;
  text-orientation: mixed;
}
.property-compact strong,
.property-compact span {
  overflow: hidden;
  max-height: 220px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.property-compact strong {
  color: var(--text-primary);
  font-size: 13px;
}
.property-compact span {
  font-size: 12px;
}
.node-property-tabs {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-height: 0;
}
.node-property-tabs :deep(> .el-tabs__header) {
  flex-shrink: 0;
  padding: 10px 20px 4px;
  margin: 0;
  /* 内容区独立滚动，页签栏无需不透明底；直接落在属性面板磨砂层上，避免面板顶部出现实色横条。 */
  background: transparent;
}
.node-property-tabs :deep(> .el-tabs__header .el-tabs__item) {
  font-size: 12px;
}
.node-property-tabs :deep(> .el-tabs__content) {
  flex: 1;
  min-height: 0;
  padding: 0 20px 28px;
  overflow-y: auto;
  scrollbar-gutter: stable;
}
.node-property-tabs :deep(> .el-tabs__content > .el-tab-pane) {
  padding-top: 18px;
}
.node-description {
  margin-bottom: 20px;
  padding-bottom: 16px;
  border-bottom: 1px solid var(--border-divider);
}
.node-description summary {
  display: flex;
  align-items: center;
  gap: 10px;
  list-style: none;
  color: var(--text-muted);
  font-size: 12px;
  cursor: pointer;
}
.node-description summary::-webkit-details-marker {
  display: none;
}
.node-description summary span:first-child {
  flex-shrink: 0;
  color: var(--text-secondary);
}
.node-description summary span:nth-child(2) {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.node-description[open] summary {
  margin-bottom: 10px;
}
.node-description[open] summary .el-icon {
  transform: rotate(90deg);
}
.node-description summary:focus-visible {
  outline: 2px solid var(--border-focus);
  outline-offset: 4px;
}
.node-config-body {
  min-width: 0;
}
.node-debug-box,
.node-trace-panel {
  display: grid;
  gap: 16px;
}
.node-trace-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}
.node-trace-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  color: var(--text-muted);
  font-size: 12px;
}
.node-trace-block {
  display: grid;
  gap: 6px;
}
.node-trace-block > span {
  color: var(--text-secondary);
  font-size: 12px;
  font-weight: 600;
}
.node-trace-block pre {
  margin: 0;
  padding: 12px;
  border: 1px solid var(--border-subtle);
  border-radius: 6px;
  background: var(--surface-solid-disabled);
  color: var(--text-primary);
  font:
    12px/1.6 Consolas,
    monospace;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  overflow: auto;
}
.published-run-entry {
  margin-top: 12px;
}
.property-resizer {
  position: absolute;
  top: 0;
  bottom: 0;
  left: 0;
  z-index: 4;
  width: 5px;
  cursor: col-resize;
}
.property-resizer:hover,
.property-panel.resizing .property-resizer {
  background: var(--border-focus);
}
.property-heading {
  display: grid;
  gap: 4px;
  margin: 4px 34px 14px 0;
}
.property-heading strong {
  color: var(--text-primary);
  font-size: 14px;
  font-weight: 650;
}
.property-heading span {
  overflow-wrap: anywhere;
  color: var(--text-muted);
  font-size: 12px;
}
.field-help,
.condition-help {
  margin-top: 5px;
  color: var(--text-muted);
  font-size: 12px;
  line-height: 1.6;
}
.workflow-property .property-heading {
  margin-top: 24px;
}
.property-empty-state {
  display: grid;
  gap: 8px;
  padding: 24px 16px;
  border: 1px dashed var(--border-readable);
  border-radius: 8px;
  color: var(--text-muted);
  text-align: center;
}
.empty-state-icon {
  margin: 0 auto 4px;
  color: var(--brand-primary);
  font-size: 24px;
}
.property-empty-state strong {
  color: var(--text-primary);
  font-size: 14px;
}
.property-empty-state span {
  font-size: 12px;
  line-height: 1.6;
}
.tab-label {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.tab-label i {
  width: 6px;
  height: 6px;
  border-radius: 50%;
}
.tab-label i.is-ok {
  background: var(--status-success);
}
.tab-label i.is-fail {
  background: var(--status-danger);
}
.tab-label i.is-wait {
  background: var(--status-warning);
}
</style>
