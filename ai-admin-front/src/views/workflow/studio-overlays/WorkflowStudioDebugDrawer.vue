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
          popper-class="debug-advanced-popover"
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
            <div>
              <strong>节点轨迹</strong>
              <span>{{ steps.length }} 个节点事件</span>
            </div>
            <el-button size="small" text :disabled="!session" @click="$emit('clear-session-view')">
              清空视图
            </el-button>
          </div>
          <div class="workflow-debug-steps">
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
                  <strong>{{ step.nodeName || step.nodeId }}</strong>
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
                <el-tabs>
                  <el-tab-pane label="输入">
                    <pre>{{ stringifyDebugPayload(step.input) }}</pre>
                  </el-tab-pane>
                  <el-tab-pane label="输出">
                    <div v-if="step.uiRequest || waitingOutput(step)" class="debug-waiting-card">
                      <strong>{{ step.status === 'WAITING' ? '等待用户补充' : '输出卡片' }}</strong>
                      <span>{{ step.uiRequest?.message || waitingOutput(step)?.message || step.uiRequest?.title || '已生成交互 UI' }}</span>
                    </div>
                    <pre>{{ stringifyDebugPayload(step.output ?? step.statePatch) }}</pre>
                  </el-tab-pane>
                  <el-tab-pane label="状态变化">
                    <pre>{{ stringifyDebugPayload(step.statePatch) }}</pre>
                  </el-tab-pane>
                  <el-tab-pane v-if="step.errorMessage" label="错误">
                    <pre>{{ step.errorCode }} {{ step.errorMessage }}</pre>
                  </el-tab-pane>
                </el-tabs>
              </div>
            </article>
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
  border-left: 1px solid rgba(191, 219, 254, 0.72);
  background:
    radial-gradient(circle at 18% 8%, rgba(129, 140, 248, 0.14), transparent 32%),
    radial-gradient(circle at 76% 18%, rgba(34, 211, 238, 0.13), transparent 28%),
    linear-gradient(135deg, rgba(248, 250, 252, 0.78), rgba(239, 246, 255, 0.62));
  box-shadow: -26px 0 72px rgba(15, 23, 42, 0.16);
  backdrop-filter: blur(20px) saturate(1.18);
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
  border-bottom: 1px solid rgba(226, 232, 240, 0.72);
  background: rgba(255, 255, 255, 0.42);
  backdrop-filter: blur(16px) saturate(1.18);
}

:global(.studio-debug-drawer-overlay .studio-debug-drawer .el-drawer__close-btn) {
  color: #334155;
}

:global(.studio-debug-drawer-overlay .studio-debug-drawer .el-drawer__title) {
  color: #0f172a;
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
  color: #0f172a;
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
  border: 1px solid rgba(203, 213, 225, 0.64);
  border-radius: 16px;
  background: rgba(255, 255, 255, 0.74);
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
  background:
    linear-gradient(rgba(255, 255, 255, 0.72), rgba(248, 250, 252, 0.58)) padding-box,
    linear-gradient(135deg, rgba(255, 255, 255, 0.9), rgba(148, 163, 184, 0.34)) border-box;

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
    background: conic-gradient(from 140deg, #7c3aed, #06b6d4, #22c55e, #f472b6, #7c3aed);
    opacity: 0;
    filter: blur(1px);
  }

  &::after {
    inset: 1px;
    z-index: 1;
    border-radius: 15px;
    background:
      radial-gradient(circle at 14% 0%, rgba(129, 140, 248, 0.12), transparent 34%),
      radial-gradient(circle at 92% 12%, rgba(34, 211, 238, 0.11), transparent 28%),
      rgba(255, 255, 255, 0.84);
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
    background: conic-gradient(from 120deg, #f59e0b, #a855f7, #38bdf8, #fb7185, #f59e0b);
    opacity: 0.72;
    animation: debugAuraSpin 7.2s linear infinite;
  }

  &.is-success {
    background:
      linear-gradient(rgba(255, 255, 255, 0.76), rgba(248, 250, 252, 0.62)) padding-box,
      linear-gradient(135deg, rgba(34, 197, 94, 0.56), rgba(14, 165, 233, 0.24)) border-box;
  }

  &.is-error {
    background:
      linear-gradient(rgba(255, 255, 255, 0.76), rgba(254, 242, 242, 0.6)) padding-box,
      linear-gradient(135deg, rgba(239, 68, 68, 0.62), rgba(244, 114, 182, 0.28)) border-box;
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
    color: var(--reachai-chat-text, #1e293b);
    background: transparent;
    box-shadow: none;
  }

  :deep(.el-textarea__inner) {
    min-height: 64px !important;
    padding: 10px 4px;
    line-height: 1.6;
  }

  :deep(.el-textarea__inner::placeholder) {
    color: var(--reachai-chat-text-muted, #64748b);
    opacity: 1;
  }

  .debug-actions .el-button--primary {
    width: 42px;
    height: 42px;
    border: 0;
    background: linear-gradient(
      135deg,
      var(--reachai-chat-primary-soft, #818cf8),
      var(--reachai-chat-primary, #6366f1)
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
    color: #0f172a;
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
    color: #0f172a;
    font-weight: 800;
  }

  span {
    margin-top: 3px;
    color: #64748b;
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
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  background: var(--el-fill-color-light);
  color: var(--el-text-color-primary);
  font-family: Consolas, Monaco, 'Courier New', monospace;
  font-size: 11px;
  line-height: 1.5;
}

.debug-advanced-collapse {
  margin-top: 14px;
  border: 1px solid rgba(203, 213, 225, 0.58);
  border-radius: 14px;
  background: rgba(255, 255, 255, 0.46);
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
  border: 1px solid rgba(203, 213, 225, 0.62);
  border-left: 3px solid #22c55e;
  border-radius: 14px;
  background:
    linear-gradient(135deg, rgba(255, 255, 255, 0.88), rgba(248, 250, 252, 0.66)),
    rgba(255, 255, 255, 0.76);
  box-shadow: 0 10px 26px rgba(15, 23, 42, 0.055);
  backdrop-filter: blur(12px) saturate(1.12);
  transition: border-color 0.16s ease, box-shadow 0.16s ease, transform 0.16s ease;

  &:hover {
    transform: translateY(-1px);
    border-color: rgba(129, 140, 248, 0.34);
    box-shadow: 0 14px 32px rgba(15, 23, 42, 0.08);
  }

  &.selected {
    border-color: rgba(99, 102, 241, 0.36);
    box-shadow: 0 0 0 2px rgba(99, 102, 241, 0.14), 0 16px 36px rgba(99, 102, 241, 0.1);
  }

  &.is-error {
    border-left-color: #ef4444;
  }

  &.is-waiting {
    border-left-color: #f59e0b;
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
  border: 2px solid rgba(99, 102, 241, 0.22);
  border-top-color: #6366f1;
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
  background: rgba(241, 245, 249, 0.92);
  color: #64748b;
  font-size: 12px;
  font-weight: 700;
}

.workflow-debug-step-card.is-success .step-index {
  background: rgba(220, 252, 231, 0.92);
  color: #15803d;
}

.workflow-debug-step-card.is-running {
  border-left-color: #6366f1;
}

.workflow-debug-step-card.is-running .step-index {
  background: rgba(224, 231, 255, 0.95);
  color: #4338ca;
}

.workflow-debug-step-card.is-waiting .step-index {
  background: rgba(254, 243, 199, 0.95);
  color: #b45309;
}

.workflow-debug-step-card.is-error .step-index {
  background: rgba(254, 226, 226, 0.95);
  color: #b91c1c;
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
    color: #0f172a;
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
  border-top: 1px solid rgba(226, 232, 240, 0.74);
}

.debug-step-inline pre {
  margin: 0;
  padding: 10px;
  border-radius: 6px;
  background: #f4f4f5;
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
  border: 1px solid rgba(245, 158, 11, 0.35);
  border-radius: 6px;
  background: rgba(245, 158, 11, 0.08);

  strong {
    color: #92400e;
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
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
  background: var(--el-fill-color-lighter);
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
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
  background: #fff;

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
  border: 1px solid #bbf7d0;
  border-radius: 999px;
  background: #f0fdf4;
  color: #166534;
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
    color: #64748b;
    font-style: normal;
  }

  &.error {
    border-color: #fecaca;
    background: #fef2f2;
    color: #991b1b;
  }

  &.waiting {
    border-color: #fde68a;
    background: #fffbeb;
    color: #92400e;
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
    background: #f4f4f5;
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
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
  background: var(--el-fill-color-lighter);

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
  border-color: rgba(129, 140, 248, 0.22);
  color: #4338ca;
  background: rgba(255, 255, 255, 0.58);
  box-shadow: 0 10px 24px rgba(79, 70, 229, 0.08);
  backdrop-filter: blur(12px) saturate(1.12);
}

:global(.debug-advanced-popover) {
  max-width: min(560px, calc(100vw - 40px));
  border: 1px solid rgba(203, 213, 225, 0.64) !important;
  border-radius: 16px !important;
  background:
    radial-gradient(circle at 10% 0%, rgba(129, 140, 248, 0.12), transparent 34%),
    linear-gradient(135deg, rgba(255, 255, 255, 0.9), rgba(248, 250, 252, 0.8)) !important;
  box-shadow: 0 24px 62px rgba(15, 23, 42, 0.16) !important;
  backdrop-filter: blur(18px) saturate(1.16);
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
</style>
