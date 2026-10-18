<template>
  <aside class="property-panel" :class="{ resizing }">
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
    <div v-else class="property-content">
      <el-alert
        v-if="readOnly"
        class="readonly-alert"
        title="此 Workflow 由其他入口维护，只读展示。请从原创建入口更新。"
        type="warning"
        :closable="false"
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
            <el-icon><component :is="nodeIcon(node.data.kind) || Operation" /></el-icon>
          </div>
          <div class="node-property-title">
            <strong>{{ node.data.label || node.id }}</strong>
            <span>{{ nodeKindLabel(node.data.kind) }} · {{ node.id }}</span>
          </div>
          <el-button
            class="node-property-delete"
            size="small"
            plain
            :icon="Delete"
            type="danger"
            :disabled="readOnly || isBoundary"
            @click="emit('delete-node')"
          >
            删除
          </el-button>
        </div>

        <el-tabs :model-value="tab" class="node-property-tabs" @update:model-value="(value: string | number) => emit('update:tab', value as StudioPropertyTab)">
          <el-tab-pane label="配置" name="config">
            <el-form label-position="top" size="small" class="node-base-form" :disabled="readOnly">
              <el-form-item label="名称">
                <el-input v-model="node.data.label" placeholder="给节点起一个清晰的业务名称" @change="emit('mark-dirty')" />
              </el-form-item>
              <el-form-item label="描述">
                <el-input
                  v-model="node.data.description"
                  type="textarea"
                  :autosize="{ minRows: 2, maxRows: 6 }"
                  placeholder="说明这个节点在流程中的职责"
                  @change="emit('mark-dirty')"
                />
              </el-form-item>
              <el-form-item v-if="!isBoundary" label="输出别名">
                <el-input v-model="node.data.outputAlias" placeholder="如 customer / params / context" @change="emit('mark-dirty')" />
                <div class="field-help">下游节点可用这个名字引用本节点的输出。</div>
              </el-form-item>
            </el-form>
            <div class="node-config-body">
              <slot name="node-config" :node="node" />
            </div>
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
            <el-alert
              v-else
              title="还没有本次 Studio 调试结果"
              type="info"
              :closable="false"
              show-icon
              description="点顶部「调试」跑一遍后，这里会显示该节点的输入和输出。已发布版本的运行请到运行中心查看。"
            />
            <el-button v-if="canReadPublishedRuns" class="published-run-entry" size="small" @click="emit('open-published-runs')">
              查看已发布运行记录
            </el-button>
          </el-tab-pane>
        </el-tabs>
      </div>
    </div>
  </aside>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue'
import type { Component } from 'vue'
import { Aim, ArrowLeft, ArrowRight, Delete, Operation } from '@element-plus/icons-vue'
import ModelSelectEmptyState from '@/components/model/ModelSelectEmptyState.vue'
import { WORKFLOW_KIND_SELECT_OPTIONS } from '@/utils/workflowLabels'
import { formatElapsed, type WorkflowNodeTraceState } from '@/views/workflow/composables/workflowStudioTrace'
import { useWorkflowStudioNodeMetadata } from '@/views/workflow/composables/useWorkflowStudioNodeMetadata'
import type { CanvasEdge, CanvasNode, CanvasNodeKind } from '@/types/studio'
import type { ModelInstance } from '@/types/model'
import type { WorkflowNodeDebugResult } from '@/types/workflow'
import {
  STUDIO_PROPERTY_WIDTH_DEFAULT,
  clampPropertyWidth,
  type StudioPropertyTab,
  type StudioWorkflowMeta,
} from './studioPropertyPanel'

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
const isBoundary = computed(() => props.node?.data.kind === 'start' || props.node?.data.kind === 'end')
const resizing = ref(false)

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
.property-empty-state {
  display: grid;
  gap: 8px;
  padding: 20px 18px;
  border: 1px dashed #c8d5e8;
  border-radius: 12px;
  background:
    radial-gradient(circle at top left, rgba(49, 87, 255, 0.08), transparent 36%),
    linear-gradient(180deg, #ffffff 0%, #f8fbff 100%);
  color: #64748b;
  text-align: center;
}

.property-empty-state .empty-state-icon {
  display: grid;
  width: 40px;
  height: 40px;
  margin: 0 auto 2px;
  place-items: center;
  border-radius: 12px;
  background: #eef4ff;
  color: #3157ff;
  font-size: 18px;
}

.property-empty-state strong {
  color: #0f172a;
  font-size: 14px;
}

.property-empty-state span {
  font-size: 12px;
  line-height: 1.55;
}

.condition-help {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.5;
}

.property-panel {
  position: relative;
  background: linear-gradient(180deg, #ffffff 0%, #f8fbff 100%);
  border-left: 1px solid #e5edf7;
  overflow: hidden;
}

.property-toggle {
  position: absolute;
  top: 14px;
  right: 14px;
  z-index: 2;
  display: grid;
  place-items: center;
  width: 32px;
  height: 32px;
  border: 1px solid #dbe5f2;
  border-radius: 999px;
  background: #fff;
  color: #64748b;
  cursor: pointer;
  box-shadow: 0 8px 20px rgba(15, 23, 42, 0.08);
  transition: border-color 0.16s ease, color 0.16s ease, transform 0.16s ease;
}

.property-toggle:hover {
  transform: translateX(-1px);
  border-color: #bfd0ff;
  color: #3157ff;
}

.property-content {
  height: 100%;
  padding: 18px 16px;
  overflow-y: auto;
}

.property-compact {
  display: flex;
  height: 100%;
  flex-direction: column;
  align-items: center;
  gap: 10px;
  padding: 54px 8px 14px;
  color: #64748b;
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
  color: #0f172a;
  font-size: 13px;
}

.property-compact span {
  font-size: 12px;
}


.node-property-head {
  display: grid;
  grid-template-columns: 42px minmax(0, 1fr) auto;
  gap: 12px;
  align-items: center;
  padding: 2px 44px 12px 2px;
}

.node-property-icon {
  display: grid;
  width: 38px;
  height: 38px;
  place-items: center;
  border-radius: 10px;
  font-size: 18px;
}

.node-property-title {
  min-width: 0;
}


.node-property-title strong {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  color: #0f172a;
  font-size: 16px;
  font-weight: 800;
  white-space: nowrap;
}

.node-property-title span {
  display: block;
  margin-top: 4px;
  overflow: hidden;
  color: #64748b;
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.node-property-delete {
  align-self: start;
  margin-top: 1px;
}

.node-debug-box,
.node-trace-panel {
  display: grid;
  gap: 12px;
}

.node-debug-head,
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
  color: #64748b;
  font-size: 12px;
}

.node-trace-block {
  display: grid;
  gap: 6px;
}

.node-trace-block span {
  color: #64748b;
  font-size: 12px;
  font-weight: 700;
}

.node-trace-block pre {
  margin: 0;
  padding: 12px;
  border-radius: 10px;
  background: #0f172a;
  color: #e2e8f0;
  font-size: 12px;
  line-height: 1.5;
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
  z-index: 3;
  width: 6px;
  cursor: col-resize;
  transition: background 0.16s ease;
}

.property-resizer:hover,
.property-panel.resizing .property-resizer {
  background: rgba(49, 87, 255, 0.28);
}

.property-panel {
  container: studio-config / inline-size;
}

.property-heading {
  display: grid;
  gap: 4px;
  margin: 4px 44px 14px 0;
}

.property-heading strong {
  color: #0f172a;
  font-size: 15px;
  font-weight: 800;
}

.property-heading span {
  overflow-wrap: anywhere;
  color: #64748b;
  font-size: 12px;
}

.workflow-property .property-heading {
  margin-top: 18px;
}

.field-help {
  margin-top: 4px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.5;
}

.readonly-alert {
  margin-bottom: 12px;
}

.node-property-tabs :deep(.el-tabs__header) {
  position: sticky;
  top: -18px;
  z-index: 2;
  margin-bottom: 12px;
  background: #fff;
}

.node-base-form {
  padding-bottom: 4px;
  border-bottom: 1px dashed #e5eaf2;
  margin-bottom: 14px;
}

.tab-label {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.tab-label i {
  width: 7px;
  height: 7px;
  border-radius: 50%;
}

.tab-label i.is-ok {
  background: var(--el-color-success);
}

.tab-label i.is-fail {
  background: var(--el-color-danger);
}

.tab-label i.is-wait {
  background: var(--el-color-warning);
}
</style>
