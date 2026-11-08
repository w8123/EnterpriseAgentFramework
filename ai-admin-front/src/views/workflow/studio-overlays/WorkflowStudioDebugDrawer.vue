<template>
  <AppDrawer title="工作流调试台（当前工作副本）"
    v-model="open"
    class="studio-debug-drawer"
    modal-class="studio-debug-drawer-overlay"
    size="min(960px, 58vw)"
    direction="rtl"
    :modal="false"
    :modal-penetrable="true"
    :lock-scroll="false"
  >
    <template #header>
      <div class="debug-drawer-head">
        <strong>工作流调试台（当前工作副本）</strong>
        <el-popover
          placement="bottom-end"
          trigger="click"
          width="560"
          popper-class="debug-advanced-popover app-frosted-overlay"
        >
          <template #reference>
            <el-button class="debug-advanced-trigger" plain :icon="Operation">
              高级调试
            </el-button>
          </template>
          <el-collapse class="debug-advanced-collapse debug-advanced-popover-collapse">
            <el-collapse-item name="variables">
              <template #title>
                <span class="debug-collapse-title">高级调试：变量与状态快照</span>
              </template>
              <div class="result-section">
                <strong>变量映射合同：</strong>
                <pre>{{ JSON.stringify(variablePreview, null, 2) }}</pre>
              </div>
              <div v-if="runResult" class="result-section">
                <strong>最终状态快照：</strong>
                <pre>{{ stringifyDebugPayload(runResult.stateSnapshot) }}</pre>
              </div>
            </el-collapse-item>

            <el-collapse-item name="trace">
              <template #title>
                <span class="debug-collapse-title">高级调试：Trace 回放与生产运行</span>
              </template>
              <div class="trace-replay-panel">
                <div class="trace-replay-row debug-production-row">
                  <div>
                    <strong>发布版本验证</strong>
                    <span>使用已发布 ACTIVE 版本图规范运行，用于和当前工作副本调试结果对照。</span>
                  </div>
                  <el-button :loading="debugLoading" @click="$emit('run-published')">
                    发布版本验证
                  </el-button>
                </div>
                <div class="trace-replay-row">
                  <el-input
                    v-model="replayTraceInput"
                    clearable
                    placeholder="输入 traceId 回放到画布"
                    @keyup.enter="$emit('load-trace-replay')"
                  />
                  <el-button type="primary" plain :loading="traceReplayLoading" @click="$emit('load-trace-replay')">
                    回放
                  </el-button>
                  <el-button :disabled="!currentTraceId" @click="$emit('clear-trace-replay')">
                    清除
                  </el-button>
                </div>
                <div class="trace-replay-row">
                  <el-select
                    v-model="selectedRecentTraceId"
                    filterable
                    clearable
                    :placeholder="recentRunsPlaceholder"
                    style="width: 100%"
                    @change="$emit('recent-trace-change', $event)"
                  >
                    <el-option
                      v-for="run in recentRuns"
                      :key="run.traceId"
                      :label="recentRunLabel(run)"
                      :value="run.traceId"
                    />
                  </el-select>
                  <el-button :loading="recentRunsLoading" @click="$emit('load-recent-runs')">
                    刷新
                  </el-button>
                </div>
              </div>
              <div v-if="currentTraceId" class="result-section trace-detail-section">
                <div class="debug-section-head">
                  <div>
                    <strong>链路详情</strong>
                    <span>{{ currentTraceId }}</span>
                  </div>
                  <el-button
                    type="primary"
                    size="small"
                    @click="$emit('open-runops', currentTraceId)"
                  >查看运行详情</el-button>
                </div>
                <div v-if="replaySummary.length" class="runtime-insights workflow-replay-summary">
                  <div v-for="item in replaySummary" :key="item.label" class="runtime-insight">
                    <span>{{ item.label }}</span>
                    <strong>{{ item.value }}</strong>
                  </div>
                </div>
                <div v-if="nodeTraceList.length" class="node-run-summary">
                  <button
                    v-for="item in nodeTraceList"
                    :key="item.nodeId"
                    class="node-run-item"
                    :class="item.status"
                    type="button"
                    @click="$emit('open-node-trace', item.nodeId)"
                  >
                    <span>{{ item.nodeId }}</span>
                    <em>{{ formatElapsed(item.elapsedMs) }}</em>
                  </button>
                </div>
                <TraceTimeline :nodes="traceNodes" />
              </div>
              <el-empty v-else description="需要排查生产调用时，再输入 traceId 回放" />
            </el-collapse-item>
          </el-collapse>
        </el-popover>
      </div>
    </template>
    <div class="debug-body" :class="debugSessionVisualClass(session?.status)">
      <div class="debug-session-grid">
        <section class="debug-chat-panel" :class="debugSessionVisualClass(session?.status)">
          <ConversationView
            class="debug-chat-conversation"
            chrome="inline"
            density="comfortable"
            surface="admin"
            atmosphere
            :snapshot="conversationSnapshot"
            :resolve-status-hint="resolveWorkflowStatusHint"
            :resolve-thinking-presentation="resolveWorkflowThinkingPresentation"
            hide-composer
            @stop="$emit('cancel-session')"
            @retry="$emit('restore-session')"
            @interaction-submit="handleInteractionSubmit"
            @interaction-cancel="$emit('interaction-cancel', $event)"
          >
            <template #empty>
              <el-empty description="输入问题后开始一次可恢复调试会话" />
            </template>
            <template #composer>
              <section class="debug-chat-composer">
                <template v-if="initialChatField">
                  <el-input
                    v-model="debugInputParams[initialChatField.name]"
                    type="textarea"
                    :rows="3"
                    resize="none"
                    :placeholder="initialChatField.description || '输入测试消息...'"
                    :disabled="conversationBusy"
                  />
                  <div class="debug-actions">
                    <el-tooltip content="运行当前工作副本" placement="top" :trigger-keys="[]">
                      <el-button
                        type="primary"
                        circle
                        :icon="SendIcon"
                        :loading="conversationBusy"
                        aria-label="运行当前工作副本"
                        @click="$emit('run-working-copy')"
                      />
                    </el-tooltip>
                  </div>
                </template>
                <UnifiedInteractionRenderer
                  v-else-if="initialUiRequest"
                  :request="initialUiRequest"
                  @submit="handleInitialInteractionSubmit"
                  @cancel="$emit('interaction-cancel', WORKFLOW_INITIAL_INPUT_ID)"
                />
                <template v-else-if="conversationSnapshot.turnStatus !== 'waiting'">
                  <el-input
                    v-model="debugMessage"
                    type="textarea"
                    :rows="3"
                    resize="none"
                    placeholder="输入测试消息..."
                    :disabled="conversationBusy"
                  />
                  <div class="debug-actions">
                    <el-tooltip content="运行当前工作副本" placement="top" :trigger-keys="[]">
                      <el-button
                        type="primary"
                        circle
                        :icon="SendIcon"
                        :loading="conversationBusy"
                        aria-label="运行当前工作副本"
                        @click="$emit('run-working-copy')"
                      />
                    </el-tooltip>
                  </div>
                </template>
              </section>
            </template>
          </ConversationView>
        </section>

        <section class="debug-steps-panel">
          <div class="debug-section-head">
            <div class="debug-panel-tabs" role="tablist">
              <button
                v-for="item in PANEL_TABS"
                :key="item.value"
                type="button"
                role="tab"
                :aria-selected="panelTab === item.value"
                :class="{ active: panelTab === item.value }"
                @click="panelTab = item.value"
              >{{ item.label }}<em v-if="item.value === 'steps' && steps.length">{{ steps.length }}</em></button>
            </div>
            <el-button v-if="panelTab === 'steps'" size="small" text :disabled="!session && !runResult" @click="$emit('clear-session-view')">
              清空视图
            </el-button>
            <el-button v-else-if="panelTab === 'runs'" size="small" text :loading="recentRunsLoading" @click="$emit('load-recent-runs')">
              刷新
            </el-button>
          </div>
          <div v-if="panelTab === 'steps'" class="workflow-debug-steps">
            <el-empty v-if="!steps.length" :image-size="72" description="运行一次后，这里按顺序列出每个节点的结果" />
            <article
              v-for="(step, index) in steps"
              :key="step.nodeId + ':' + index"
              class="workflow-debug-step-card"
              :class="[debugStepStatusClass(step.status), { selected: selectedStepIndex === index }]"
            >
              <button
                class="workflow-debug-step"
                :class="debugStepStatusClass(step.status)"
                type="button"
                @click="$emit('select-step', index)"
              >
                <span class="step-marker">
                  <span
                    class="step-running-icon"
                    :class="{ active: isStepRunning(step) }"
                    aria-label="运行中"
                  ></span>
                  <span class="step-index">{{ index + 1 }}</span>
                </span>
                <span class="step-main">
                  <strong>{{ step.nodeName || step.nodeId }}<small v-if="stepOccurrence[index] > 1" class="step-repeat">第 {{ stepOccurrence[index] }} 次</small></strong>
                  <em>{{ step.nodeType || step.eventType || '-' }}</em>
                </span>
                <span class="step-route">
                  <template v-if="step.route">路由 {{ step.route }}</template>
                  <template v-else>{{ step.eventType || '节点' }}</template>
                  <small v-if="step.nextNodeId">→ {{ step.nextNodeId }}</small>
                </span>
                <span class="step-time">{{ formatElapsed(step.elapsedMs) }}</span>
              </button>
              <div v-if="selectedStepIndex === index" class="debug-step-inline">
                <LiquidGlassTabs>
                  <el-tab-pane label="输出">
                    <div v-if="step.uiRequest || waitingOutput(step)" class="debug-waiting-card">
                      <strong>{{ step.status === 'WAITING' ? '等待用户补充' : '输出卡片' }}</strong>
                      <span>{{ step.uiRequest?.message || waitingOutput(step)?.message || step.uiRequest?.title || '已生成交互 UI' }}</span>
                    </div>
                    <pre>{{ stringifyDebugPayload(step.output ?? step.statePatch) }}</pre>
                  </el-tab-pane>
                  <el-tab-pane v-if="hasContent(step.input)" label="输入">
                    <pre>{{ stringifyDebugPayload(step.input) }}</pre>
                  </el-tab-pane>
                  <el-tab-pane v-if="hasContent(step.statePatch)" label="状态变化">
                    <pre>{{ stringifyDebugPayload(step.statePatch) }}</pre>
                  </el-tab-pane>
                  <el-tab-pane v-if="step.errorMessage" label="错误">
                    <pre>{{ step.errorCode }} {{ step.errorMessage }}</pre>
                  </el-tab-pane>
                </LiquidGlassTabs>
                <div class="debug-step-actions">
                  <el-button
                    size="small"
                    :disabled="!step.input || !Object.keys(step.input).length || conversationBusy"
                    @click="$emit('rerun-from', step.nodeId)"
                  >从这里重新运行</el-button>
                  <span>沿用这次的数据，从这个节点跑到结束，前面的节点不再执行。</span>
                </div>
              </div>
            </article>
          </div>
          <div v-else-if="panelTab === 'variables'" class="workflow-debug-variables">
            <el-empty v-if="!variableGroups.length" :image-size="72" description="运行结束后，这里能看到所有变量的值" />
            <details v-for="group in variableGroups" :key="group.key" class="debug-variable-group" open>
              <summary>
                <strong>{{ group.label }}</strong>
                <span>{{ group.hint }}</span>
              </summary>
              <div v-for="item in group.items" :key="group.key + '.' + item.name" class="debug-variable-row">
                <code>{{ item.path }}</code>
                <pre>{{ stringifyDebugPayload(item.value) }}</pre>
              </div>
            </details>
          </div>
          <div v-else class="workflow-debug-runs">
            <el-empty v-if="!recentRuns.length" :image-size="72" description="最近 7 天没有运行记录" />
            <button
              v-for="run in recentRuns"
              :key="run.traceId"
              type="button"
              class="debug-run-row"
              :class="{ active: run.traceId === currentTraceId }"
              @click="$emit('recent-trace-change', run.traceId)"
            >
              <span class="debug-run-status" :class="runStatusClass(run.status)">{{ runStatusText(run.status) }}</span>
              <span class="debug-run-main">
                <strong>{{ recentRunLabel(run) }}</strong>
              </span>
            </button>
            <div v-if="currentTraceId && nodeTraceList.length" class="node-run-summary">
              <button
                v-for="item in nodeTraceList"
                :key="item.nodeId"
                class="node-run-item"
                :class="item.status"
                type="button"
                @click="$emit('open-node-trace', item.nodeId)"
              >
                <span>{{ item.nodeId }}</span>
                <em>{{ formatElapsed(item.elapsedMs) }}</em>
              </button>
            </div>
            <el-button v-if="currentTraceId" size="small" type="primary" plain @click="$emit('open-runops', currentTraceId)">
              在运行中心查看完整详情
            </el-button>
          </div>
        </section>
      </div>

      <div v-if="result" class="debug-result">
        <div class="result-section">
          <strong>发布版本回答：</strong>
          <div>{{ result.answer }}</div>
        </div>
        <div v-if="opsItems.length" class="result-section">
          <strong>生产运行信息：</strong>
          <div class="runtime-insights">
            <div v-for="item in opsItems" :key="item.label" class="runtime-insight">
              <span>{{ item.label }}</span>
              <strong>{{ item.value }}</strong>
            </div>
          </div>
        </div>
      </div>
    </div>
  </AppDrawer>
</template>

<script setup lang="ts">
import AppDrawer from '@/components/common/AppDrawer.vue'
import { computed, ref } from 'vue'
import { Operation } from '@element-plus/icons-vue'
import type { ChatResponse } from '@/types/chat'
import type { RunSummary } from '@/types/runops'
import type { StudioFieldSchema } from '@/types/studio'
import type { TraceNode } from '@/types/trace'
import type {
  WorkflowDebugRunResult,
  WorkflowDebugSessionView,
  WorkflowDebugStepResult,
} from '@/types/workflow'
import TraceTimeline from '@/components/TraceTimeline.vue'
import { LiquidGlassTabs } from '@/components/common/liquid-glass'
import SendIcon from '@/components/icons/SendIcon.vue'
import {
  ConversationView,
  UnifiedInteractionRenderer,
  WORKFLOW_INITIAL_INPUT_ID,
  type ConversationMessage,
  type ConversationSnapshot,
  type UiRequestV1,
} from '@/conversation'
import type { WorkflowNodeTraceState } from '@/views/workflow/composables/workflowStudioTrace'
import {
  debugStepStatus,
  formatElapsed,
  stringifyDebugPayload,
} from '@/views/workflow/composables/workflowStudioTrace'

interface DebugMetricItem {
  label: string
  value: string
}

const props = defineProps<{
  variablePreview: unknown
  runResult: WorkflowDebugRunResult | null
  result: ChatResponse | null
  session: WorkflowDebugSessionView | null
  conversationSnapshot: ConversationSnapshot
  initialChatField: StudioFieldSchema | null
  initialUiRequest: UiRequestV1 | null
  debugInputParams: Record<string, unknown>
  steps: WorkflowDebugStepResult[]
  selectedStepIndex: number | null
  currentTraceId: string
  traceNodes: TraceNode[]
  recentRuns: RunSummary[]
  recentRunsPlaceholder: string
  replaySummary: DebugMetricItem[]
  nodeTraceList: WorkflowNodeTraceState[]
  opsItems: DebugMetricItem[]
  debugLoading: boolean
  traceReplayLoading: boolean
  recentRunsLoading: boolean
  conversationBusy: boolean
  recentRunLabel: (run: RunSummary) => string
  isStepRunning: (step: WorkflowDebugStepResult) => boolean
  waitingOutput: (step: WorkflowDebugStepResult) => Record<string, unknown> | null
}>()

const emit = defineEmits<{
  (event: 'run-published'): void
  (event: 'load-trace-replay'): void
  (event: 'clear-trace-replay'): void
  (event: 'recent-trace-change', value: string | number | boolean | undefined): void
  (event: 'load-recent-runs'): void
  (event: 'open-runops', traceId: string): void
  (event: 'open-node-trace', nodeId: string): void
  (event: 'cancel-session'): void
  (event: 'restore-session'): void
  (event: 'interaction-submit', interactionId: string, action: string, values: Record<string, unknown>): void
  (event: 'interaction-cancel', interactionId: string): void
  (event: 'run-working-copy'): void
  (event: 'clear-session-view'): void
  (event: 'select-step', index: number): void
  (event: 'rerun-from', nodeId: string): void
}>()

const open = defineModel<boolean>('open', { required: true })
const replayTraceInput = defineModel<string>('replayTraceInput', { required: true })
const selectedRecentTraceId = defineModel<string>('selectedRecentTraceId', { required: true })
const debugMessage = defineModel<string>('debugMessage', { required: true })

function handleInteractionSubmit(
  interactionId: string,
  action: string,
  values: Record<string, unknown>,
) {
  emit('interaction-submit', interactionId, action, values)
}

function handleInitialInteractionSubmit(action: string, values: Record<string, unknown>) {
  emit('interaction-submit', WORKFLOW_INITIAL_INPUT_ID, action, values)
}

type DebugPanelTab = 'steps' | 'variables' | 'runs'
const PANEL_TABS: { label: string; value: DebugPanelTab }[] = [
  { label: '节点轨迹', value: 'steps' },
  { label: '变量', value: 'variables' },
  { label: '最近运行', value: 'runs' },
]
const panelTab = ref<DebugPanelTab>('steps')

/** Loop bodies run the same node several times; number each repeat. */
const stepOccurrence = computed(() => {
  const seen = new Map<string, number>()
  return props.steps.map((step) => {
    const count = (seen.get(step.nodeId) || 0) + 1
    seen.set(step.nodeId, count)
    return count
  })
})

function hasContent(value: unknown) {
  if (value == null) return false
  if (typeof value === 'object') return Object.keys(value as object).length > 0
  return String(value).trim() !== ''
}

interface VariableGroup {
  key: string
  label: string
  hint: string
  items: { name: string; path: string; value: unknown }[]
}

const VARIABLE_GROUPS: { key: string; label: string; hint: string }[] = [
  { key: 'params', label: '开始输入', hint: 'params.字段名' },
  { key: 'var', label: '业务变量', hint: 'var.名称（节点设置的输出别名）' },
  { key: 'nodeOutput', label: '节点输出', hint: 'nodeOutput.节点ID' },
  { key: 'sys', label: '系统变量', hint: 'sys.名称' },
  { key: 'env', label: '环境变量', hint: 'env.名称' },
]

const variableGroups = computed<VariableGroup[]>(() => {
  const snapshot = props.runResult?.stateSnapshot
  if (!snapshot || typeof snapshot !== 'object') return []
  const groups: VariableGroup[] = []
  for (const group of VARIABLE_GROUPS) {
    const value = (snapshot as Record<string, unknown>)[group.key]
    if (!value || typeof value !== 'object' || Array.isArray(value)) continue
    const items = Object.entries(value as Record<string, unknown>)
      .filter(([name]) => !name.startsWith('__'))
      .map(([name, item]) => ({ name, path: `${group.key}.${name}`, value: item }))
    if (items.length) groups.push({ ...group, items })
  }
  const lastOutput = (snapshot as Record<string, unknown>).lastOutput
  if (lastOutput !== undefined) {
    groups.push({
      key: 'last',
      label: '最近一次输出',
      hint: 'lastOutput',
      items: [{ name: 'lastOutput', path: 'lastOutput', value: lastOutput }],
    })
  }
  return groups
})

function runStatusClass(status?: string) {
  const normalized = (status || '').toUpperCase()
  if (['SUCCESS', 'COMPLETED', 'OK'].includes(normalized)) return 'is-success'
  if (['FAILED', 'ERROR', 'TIMED_OUT', 'CANCELLED'].includes(normalized)) return 'is-error'
  return 'is-waiting'
}

function runStatusText(status?: string) {
  const cls = runStatusClass(status)
  if (cls === 'is-success') return '成功'
  if (cls === 'is-error') return '失败'
  return status || '进行中'
}

function debugStepStatusClass(status?: string) {
  return `is-${debugStepStatus(status)}`
}

function debugSessionVisualClass(status?: string) {
  const normalized = (status || '').trim().toUpperCase()
  if (normalized === 'RUNNING') return 'is-running'
  if (!normalized) return 'is-idle'
  return debugStepStatusClass(status)
}

function resolveWorkflowStatusHint(message: ConversationMessage) {
  if (message.role !== 'assistant') return undefined
  if (message.status !== 'pending' && message.status !== 'streaming') return undefined
  if (props.steps.length) {
    const current = props.steps[props.steps.length - 1]
    const nodeLabel = current.nodeName || current.nodeType || current.nodeId
    if (nodeLabel) return `当前节点：${nodeLabel}`
  }
  return 'Workflow 运行中'
}

function workflowThinkingStepState(status?: string): 'complete' | 'active' | 'pending' | 'error' {
  const normalized = (status || '').trim().toUpperCase()
  if (normalized === 'RUNNING' || normalized === 'EXECUTING' || normalized === 'WAITING') return 'active'
  if (normalized === 'ERROR' || normalized === 'FAILED' || normalized === 'FAILURE' || normalized === 'FAIL') {
    return 'error'
  }
  if (['SUCCESS', 'OK', 'COMPLETED'].includes(normalized)) return 'complete'
  return 'pending'
}

/** 仅展示安全节点结构信息；input/output/statePatch 留在右侧轨迹面板 */
function resolveWorkflowThinkingPresentation(message: ConversationMessage) {
  if (message.role !== 'assistant') return undefined
  if (message.status !== 'pending' && message.status !== 'streaming') return undefined
  if (!props.steps.length) return undefined
  return {
    label: `节点执行 ${props.steps.length} 步`,
    count: props.steps.length,
    steps: props.steps.map((step, index) => {
      const title = step.nodeName || step.nodeType || step.nodeId || `节点 ${index + 1}`
      const parts = [
        step.nodeType ? `类型 ${step.nodeType}` : '',
        step.status ? `状态 ${step.status}` : '',
        step.route ? `路由 ${step.route}` : '',
        step.nextNodeId ? `下一节点 ${step.nextNodeId}` : '',
      ].filter(Boolean)
      return {
        id: `wf-step-${step.index ?? index}-${step.nodeId || 'node'}`,
        title,
        detail: parts.length ? parts.join(' · ') : undefined,
        state: workflowThinkingStepState(step.status),
      }
    }),
  }
}
</script>

<style scoped lang="scss">
:global(.studio-debug-drawer-overlay) {
  pointer-events: none;
}

:global(.studio-debug-drawer-overlay .studio-debug-drawer) {
  pointer-events: auto;
  min-width: min(760px, 100vw);
}

:global(.studio-debug-drawer-overlay .studio-debug-drawer.rtl.open),
:global(.studio-debug-drawer.rtl.open) {
  transform: translateX(0) !important;
}

:global(.studio-debug-drawer-overlay .studio-debug-drawer .el-drawer__header) {
  display: flex;
  align-items: center;
  margin-bottom: 0;
  padding: 22px 28px 18px;
  border-bottom: 1px solid var(--border-glass-edge);
}

:global(.studio-debug-drawer-overlay .studio-debug-drawer .el-drawer__close-btn) {
  color: var(--text-secondary);
}

:global(.studio-debug-drawer-overlay .studio-debug-drawer .el-drawer__title) {
  color: var(--text-primary);
  font-weight: 800;
  letter-spacing: 0;
}

:global(.studio-debug-drawer-overlay .studio-debug-drawer .el-drawer__body) {
  padding: 18px 0 0;
  overflow: hidden;
  background: transparent;
}

.debug-body {
  display: flex;
  flex-direction: column;
  height: calc(100vh - 82px);
  min-height: 0;
  padding: 0 20px 22px;
  position: relative;
  overflow: hidden;
  color: var(--text-primary);
}

.debug-session-grid {
  display: grid;
  grid-template-columns: minmax(360px, 1fr) minmax(300px, 0.92fr);
  align-items: stretch;
  gap: 14px;
  flex: 1;
  min-height: 0;
}

.debug-chat-panel,
.debug-steps-panel {
  min-width: 0;
  min-height: 0;
  height: 100%;
  padding: 14px;
  border: 1px solid var(--border-readable);
  border-radius: 16px;
  background: var(--surface-glass-inset);
  box-shadow: 0 22px 56px rgba(15, 23, 42, 0.1);
  backdrop-filter: blur(18px) saturate(1.16);
}

.debug-steps-panel {
  display: flex;
  flex-direction: column;
  overflow: hidden;
  box-shadow: 0 18px 46px rgba(15, 23, 42, 0.07);
}

.debug-chat-panel {
  display: flex;
  min-height: 0;
  flex-direction: column;
  position: relative;
  isolation: isolate;
  overflow: hidden;
  border-color: transparent;
  background: var(--surface-glass-inset);

  .debug-chat-conversation {
    flex: 1;
    min-height: 0;
    border: none;
    background: transparent;
    border-radius: inherit;
  }

  &::before,
  &::after {
    content: '';
    position: absolute;
    pointer-events: none;
  }

  &::before {
    inset: -2px;
    z-index: 0;
    border-radius: inherit;
    background: conic-gradient(from 140deg, var(--brand-primary), var(--status-info), var(--status-success), var(--brand-primary), var(--brand-primary));
    opacity: 0;
    filter: blur(1px);
  }

  &::after {
    inset: 1px;
    z-index: 1;
    border-radius: 15px;
    background:
      radial-gradient(circle at 14% 0%, rgb(var(--brand-primary-rgb) / 0.12), transparent 34%),
      radial-gradient(circle at 92% 12%, color-mix(in srgb, var(--status-info) 11%, transparent), transparent 28%),
      var(--surface-glass-inset);
    backdrop-filter: blur(18px) saturate(1.16);
  }

  > * {
    position: relative;
    z-index: 2;
  }

  &.is-running::before {
    opacity: 0.78;
    animation: debugAuraSpin 5.8s linear infinite, debugPulseGlow 2.6s ease-in-out infinite;
  }

  &.is-waiting::before {
    background: conic-gradient(from 120deg, var(--status-warning), var(--brand-primary), var(--status-info), var(--status-danger), var(--status-warning));
    opacity: 0.72;
    animation: debugAuraSpin 7.2s linear infinite;
  }

  &.is-success {
    background:
      linear-gradient(var(--surface-glass-control), var(--surface-glass-control)) padding-box,
      linear-gradient(135deg, color-mix(in srgb, var(--status-success) 56%, transparent), color-mix(in srgb, var(--status-info) 24%, transparent)) border-box;
  }

  &.is-error {
    background:
      linear-gradient(var(--surface-glass-control), var(--surface-glass-control)) padding-box,
      linear-gradient(135deg, color-mix(in srgb, var(--status-danger) 62%, transparent), rgb(var(--brand-primary-rgb) / 0.28)) border-box;
  }
}

.debug-chat-composer {
  margin-top: 0;
  padding: var(--reachai-chat-composer-padding, 12px 16px 14px);
  border: 0;
  border-radius: 0;

  :deep(.el-textarea__inner),
  :deep(.el-input__wrapper) {
    border-radius: 0;
    color: var(--reachai-chat-text, var(--text-primary));
    background: transparent;
    box-shadow: none;
  }

  :deep(.el-textarea__inner) {
    min-height: 64px !important;
    padding: 10px 4px;
    line-height: 1.6;
  }

  :deep(.el-textarea__inner::placeholder) {
    color: var(--reachai-chat-text-muted, var(--text-muted));
    opacity: 1;
  }

  .debug-actions .el-button--primary {
    width: 42px;
    height: 42px;
    border: 0;
    background: linear-gradient(
      135deg,
      var(--reachai-chat-primary-soft, var(--brand-primary)),
      var(--reachai-chat-primary, var(--brand-primary))
    );
    box-shadow: 0 12px 26px rgb(var(--reachai-chat-primary-rgb, 99 102 241) / 0.28);
  }
}

.debug-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 10px;
}

.debug-drawer-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  width: 100%;
  padding-right: 34px;

  strong {
    color: var(--text-primary);
    font-size: 16px;
    font-weight: 850;
  }
}

.debug-section-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 10px;

  strong,
  span {
    display: block;
  }

  strong {
    color: var(--text-primary);
    font-weight: 800;
  }

  span {
    margin-top: 3px;
    color: var(--text-muted);
    font-size: 12px;
  }
}

.debug-result {
  display: grid;
  gap: 6px;
  min-width: 0;
  min-height: 180px;
}

.debug-result strong {
  font-size: 12px;
}

.debug-result pre {
  overflow: auto;
  max-height: 180px;
  margin: 0;
  padding: 10px;
  border: 1px solid var(--border-glass-edge);
  border-radius: 6px;
  background: var(--surface-glass-inset);
  color: var(--el-text-color-primary);
  font-family: Consolas, Monaco, 'Courier New', monospace;
  font-size: 11px;
  line-height: 1.5;
}

.debug-advanced-collapse {
  margin-top: 14px;
  border: 1px solid var(--border-readable);
  border-radius: 14px;
  background: var(--surface-glass-control);
  overflow: hidden;
  backdrop-filter: blur(12px);
}

.debug-advanced-popover-collapse {
  max-height: calc(100vh - 150px);
  margin-top: 0;
  border: 0;
  background: transparent;
  overflow: auto;
  backdrop-filter: none;
}

.debug-collapse-title {
  color: var(--el-text-color-secondary);
  font-size: 13px;
  font-weight: 600;
}

.workflow-debug-steps {
  display: grid;
  align-content: start;
  grid-auto-rows: max-content;
  flex: 1;
  gap: 8px;
  min-height: 0;
  padding-right: 2px;
  overflow: auto;
}

.workflow-debug-step-card {
  overflow: hidden;
  border: 1px solid var(--border-readable);
  border-left: 3px solid color-mix(in srgb, var(--status-success) 40%, transparent);
  border-radius: 14px;
  background:
    linear-gradient(135deg, var(--surface-glass-control), var(--surface-glass-control)),
    var(--surface-glass-control);
  box-shadow: 0 10px 26px rgba(15, 23, 42, 0.055);
  backdrop-filter: blur(12px) saturate(1.12);
  transition: border-color 0.16s ease, box-shadow 0.16s ease, transform 0.16s ease;

  &:hover {
    transform: translateY(-1px);
    border-color: rgb(var(--brand-primary-rgb) / 0.34);
    box-shadow: 0 14px 32px rgba(15, 23, 42, 0.08);
  }

  &.selected {
    border-color: rgb(var(--brand-primary-rgb) / 0.36);
    box-shadow: 0 0 0 2px rgb(var(--brand-primary-rgb) / 0.14), 0 16px 36px rgb(var(--brand-primary-rgb) / 0.1);
  }

  &.is-error {
    border-left-color: color-mix(in srgb, var(--status-danger) 40%, transparent);
  }

  &.is-waiting {
    border-left-color: color-mix(in srgb, var(--status-warning) 40%, transparent);
  }
}

.workflow-debug-step {
  display: grid;
  grid-template-columns: 42px minmax(0, 1fr) auto;
  gap: 7px 8px;
  align-items: start;
  width: 100%;
  padding: 10px 12px;
  border: 0;
  background: transparent;
  color: var(--el-text-color-primary);
  cursor: pointer;
  text-align: left;
}

.workflow-debug-step .step-route {
  grid-column: 2 / -1;
}

.workflow-debug-step .step-time {
  grid-column: 3;
  grid-row: 1;
}

.step-marker {
  display: inline-flex;
  position: relative;
  align-items: center;
  justify-content: center;
  width: 32px;
}

.step-running-icon {
  position: absolute;
  left: -4px;
  width: 14px;
  height: 14px;
  border: 2px solid rgb(var(--brand-primary-rgb) / 0.22);
  border-top-color: rgb(var(--brand-primary-rgb) / 0.4);
  border-radius: 999px;
  opacity: 0;

  &.active {
    opacity: 1;
    animation: debugStepSpin 0.86s linear infinite;
  }
}

.step-index {
  display: grid;
  width: 26px;
  height: 26px;
  place-items: center;
  border-radius: 50%;
  background: var(--surface-glass-selected);
  color: var(--text-muted);
  font-size: 12px;
  font-weight: 700;
}

.workflow-debug-step-card.is-success .step-index {
  background: color-mix(in srgb, var(--status-success) 92%, transparent);
  color: var(--status-success);
}

.workflow-debug-step-card.is-running {
  border-left-color: rgb(var(--brand-primary-rgb) / 0.4);
}

.workflow-debug-step-card.is-running .step-index {
  background: rgb(var(--brand-primary-rgb) / 0.95);
  color: var(--brand-on-surface);
}

.workflow-debug-step-card.is-waiting .step-index {
  background: color-mix(in srgb, var(--status-warning) 95%, transparent);
  color: var(--status-warning);
}

.workflow-debug-step-card.is-error .step-index {
  background: color-mix(in srgb, var(--status-danger) 95%, transparent);
  color: var(--status-danger);
}

.step-main,
.step-route {
  min-width: 0;

  strong,
  em,
  small {
    display: block;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  em,
  small {
    color: var(--el-text-color-secondary);
    font-size: 12px;
    font-style: normal;
  }

  strong {
    color: var(--text-primary);
    font-size: 13px;
    line-height: 1.22;
  }
}

.step-time {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.2;
  text-align: right;
}

.debug-step-inline {
  padding: 0 12px 12px 54px;
  border-top: 1px solid var(--border-glass-edge);
}

.debug-step-inline pre {
  margin: 0;
  padding: 10px;
  border-radius: 6px;
  background: var(--surface-glass-selected);
  color: var(--el-text-color-primary);
  font-size: 12px;
  white-space: pre-wrap;
  word-break: break-all;
}

.debug-waiting-card {
  display: grid;
  gap: 4px;
  margin-bottom: 8px;
  padding: 10px;
  border: 1px solid color-mix(in srgb, var(--status-warning) 35%, transparent);
  border-radius: 6px;
  background: color-mix(in srgb, var(--status-warning) 8%, transparent);

  strong {
    color: var(--status-warning);
    font-size: 13px;
  }

  span {
    color: var(--el-text-color-primary);
    font-size: 13px;
  }
}

.trace-replay-panel {
  display: grid;
  gap: 10px;
  margin-top: 12px;
  padding: 12px;
  border: 1px solid var(--border-glass-edge);
  border-radius: 8px;
  background: var(--surface-glass-inset);
}

.debug-advanced-collapse .trace-replay-panel {
  margin-top: 0;
  margin-bottom: 12px;
}

.trace-replay-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto auto;
  gap: 8px;
  align-items: center;
}

.debug-production-row {
  padding: 10px;
  border: 1px solid var(--border-glass-edge);
  border-radius: 8px;
  background: var(--surface-glass-inset);

  strong,
  span {
    display: block;
  }

  span {
    margin-top: 4px;
    color: var(--el-text-color-secondary);
    font-size: 12px;
    line-height: 1.5;
  }
}

.node-run-summary {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 12px;
}

.node-run-item {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  max-width: 220px;
  padding: 6px 9px;
  border: 1px solid color-mix(in srgb, var(--status-success) 40%, transparent);
  border-radius: 999px;
  background: var(--surface-glass-selected);
  color: var(--status-success);
  cursor: pointer;
  font-size: 12px;

  span,
  em {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  span {
    font-weight: 700;
  }

  em {
    color: var(--text-muted);
    font-style: normal;
  }

  &.error {
    border-color: color-mix(in srgb, var(--status-danger) 40%, transparent);
    background: var(--surface-glass-selected);
    color: var(--status-danger);
  }

  &.waiting {
    border-color: color-mix(in srgb, var(--status-warning) 40%, transparent);
    background: var(--status-warning-soft);
    color: var(--status-warning);
  }
}

.workflow-replay-summary {
  margin-bottom: 12px;
}

.trace-detail-section {
  margin-top: 12px;
}

.result-section {
  margin-bottom: 12px;

  pre {
    padding: 8px;
    border-radius: 4px;
    background: var(--surface-glass-selected);
    font-size: 12px;
    white-space: pre-wrap;
    word-break: break-all;
  }
}

.runtime-insights {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
  margin-top: 8px;
}

.runtime-insight {
  padding: 10px;
  border: 1px solid var(--border-glass-edge);
  border-radius: 8px;
  background: var(--surface-glass-inset);

  span {
    display: block;
    margin-bottom: 4px;
    color: var(--el-text-color-secondary);
    font-size: 12px;
  }

  strong {
    color: var(--el-text-color-primary);
    word-break: break-word;
  }
}

.debug-advanced-trigger {
  border-color: rgb(var(--brand-primary-rgb) / 0.22);
  color: var(--brand-on-surface);
  background: var(--surface-glass-control);
  box-shadow: 0 10px 24px rgb(var(--brand-primary-rgb) / 0.08);
  backdrop-filter: blur(12px) saturate(1.12);
}

:global(.debug-advanced-popover) {
  max-width: min(560px, calc(100vw - 40px));
}

@media (prefers-reduced-motion: reduce) {
  .debug-chat-panel.is-running::before,
  .debug-chat-panel.is-waiting::before {
    animation: none;
  }
}

@keyframes debugStepSpin {
  to {
    transform: rotate(360deg);
  }
}

@keyframes debugAuraSpin {
  to {
    transform: rotate(360deg);
  }
}

@keyframes debugPulseGlow {
  0%,
  100% {
    opacity: 0.58;
  }

  50% {
    opacity: 0.9;
  }
}
.debug-panel-tabs {
  display: inline-flex;
  gap: 4px;
  padding: 3px;
  border-radius: 10px;
  background: var(--surface-glass-inset);

  button {
    display: inline-flex;
    align-items: center;
    gap: 4px;
    padding: 4px 10px;
    border: none;
    border-radius: 8px;
    background: transparent;
    color: var(--text-secondary);
    font-size: 13px;
    cursor: pointer;

    &.active {
      background: var(--surface-glass-strong, rgba(255, 255, 255, 0.7));
      color: var(--text-primary);
      font-weight: 700;
    }

    em {
      font-style: normal;
      font-size: 11px;
      color: var(--text-secondary);
    }
  }
}

.step-repeat {
  margin-left: 6px;
  font-size: 11px;
  font-weight: 500;
  color: var(--text-secondary);
}

.debug-step-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-top: 8px;

  span {
    font-size: 12px;
    color: var(--text-secondary);
  }
}

.workflow-debug-variables,
.workflow-debug-runs {
  display: flex;
  flex: 1;
  min-height: 0;
  flex-direction: column;
  gap: 8px;
  overflow: auto;
}

.debug-variable-group {
  border: 1px solid var(--border-readable);
  border-radius: 12px;
  padding: 8px 10px;

  summary {
    display: flex;
    align-items: baseline;
    gap: 8px;
    cursor: pointer;

    span {
      font-size: 12px;
      color: var(--text-secondary);
    }
  }
}

.debug-variable-row {
  margin-top: 8px;

  code {
    font-size: 12px;
    color: var(--brand-primary);
  }

  pre {
    margin: 4px 0 0;
    max-height: 220px;
    overflow: auto;
    font-size: 12px;
    white-space: pre-wrap;
    word-break: break-all;
  }
}

.debug-run-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 10px;
  border: 1px solid var(--border-readable);
  border-radius: 10px;
  background: transparent;
  color: var(--text-primary);
  text-align: left;
  cursor: pointer;

  &.active {
    border-color: var(--brand-primary);
  }

  .debug-run-main {
    min-width: 0;
    flex: 1;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    font-size: 12px;
  }
}

.debug-run-status {
  flex: none;
  padding: 1px 6px;
  border-radius: 6px;
  font-size: 11px;

  &.is-success { background: rgba(34, 197, 94, 0.14); color: var(--status-success); }
  &.is-error { background: rgba(239, 68, 68, 0.14); color: var(--status-danger, #dc2626); }
  &.is-waiting { background: rgba(234, 179, 8, 0.16); color: var(--status-warning, #b45309); }
}
</style>
