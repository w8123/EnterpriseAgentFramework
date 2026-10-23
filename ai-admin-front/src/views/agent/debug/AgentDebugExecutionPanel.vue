<template>
  <div class="execution-panel">
    <div class="execution-state" :class="`is-${phase}`">
      <i />{{ statusText }}
    </div>

    <p v-if="note" class="execution-note">{{ note }}</p>

    <div class="metric-grid">
      <div class="metric-card">
        <span>规划 / 重规划</span>
        <strong>{{ formatOptionalCount(planCount) }} / {{ formatOptionalCount(replanCount) }}</strong>
      </div>
      <div class="metric-card">
        <span>Workflow 调用</span>
        <strong>{{ formatOptionalCount(workflowCallCount) }}</strong>
      </div>
    </div>

    <div v-if="!steps.length" class="empty-detail compact">
      <el-icon><DataLine /></el-icon>
      <p>发送消息后查看执行阶段</p>
    </div>
    <div v-else class="execution-step-list">
      <article
        v-for="(step, stepIndex) in steps"
        :key="step.stepId || `${step.name}-${stepIndex}`"
        class="execution-step"
        :class="[`is-${stepState(stepIndex)}`, { 'is-open': selectedIndex === stepIndex }]"
      >
        <button type="button" @click="toggleStep(stepIndex)">
          <span class="execution-step-marker">
            <el-icon v-if="stepState(stepIndex) === 'complete'"><Check /></el-icon>
            <el-icon v-else-if="stepState(stepIndex) === 'active'" class="spin-icon"><Loading /></el-icon>
            <el-icon v-else-if="stepState(stepIndex) === 'error'"><CircleClose /></el-icon>
            <template v-else>{{ stepIndex + 1 }}</template>
          </span>
          <span class="execution-step-copy">
            <strong>{{ step.title || formatStepName(step.name, stepIndex) }}</strong>
            <small>{{ step.name }}</small>
          </span>
          <el-icon class="step-chevron"><ArrowDown /></el-icon>
        </button>
        <p v-if="selectedIndex === stepIndex">{{ formatStepDetail(step.detail) }}</p>
      </article>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ArrowDown, Check, CircleClose, DataLine, Loading } from '@element-plus/icons-vue'
import type { StepRecord } from '@/types/agent'
import { formatOptionalCount, formatStepDetail, formatStepName, type ExecutionPhase, type StepState } from './agentDebugFormat'

defineProps<{
  steps: StepRecord[]
  phase: ExecutionPhase
  statusText: string
  note?: string
  planCount: number | null
  replanCount: number | null
  workflowCallCount: number | null
  stepState: (index: number) => StepState
}>()

const selectedIndex = defineModel<number>('selectedIndex', { default: -1 })

function toggleStep(index: number) {
  selectedIndex.value = selectedIndex.value === index ? -1 : index
}
</script>

<style scoped lang="scss">
.agent-header-meta,
.toolbar-title,
.toolbar-actions,
.run-state,
.message-meta,
.tool-chip,
.elapsed-chip,
.trace-link,
.detail-header-actions,
.insight-summary,
.execution-state,
.approval-title,
.approval-meta,
.approval-policy {
  display: flex;
  align-items: center;
}

.header-copy-button,
.toolbar-icon-button,
.detail-action-button,
.trace-link,
.execution-step > button,
.approval-meta button {
  appearance: none;
  border: 0;
  font: inherit;
  cursor: pointer;
}

.execution-step-marker {
  display: grid;
  place-items: center;
  border-radius: 11px;
  color: var(--text-muted);
  background: color-mix(in srgb, var(--surface-solid-control) 76%, transparent);
  font-size: 11px;
  font-weight: 800;
}

.execution-step.is-complete .execution-step-marker {
  color: var(--status-success);
  background: color-mix(in srgb, var(--status-success) 10%, var(--surface-solid-control));
}

.execution-step.is-active .execution-step-marker {
  color: var(--brand-primary);
  background: rgb(var(--brand-primary-rgb) / 0.10);
}

.execution-step-copy {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 4px;

  strong {
    color: var(--text-primary);
    font-size: 12px;
    font-weight: 780;
  }

  small {
    overflow: hidden;
    color: var(--text-muted);
    font-size: 11px;
    line-height: 1.45;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
}

.spin-icon {
  animation: icon-spin 1s linear infinite;
}

.execution-panel,
.approval-panel {
  padding: 0 2px 12px;
}

.execution-note {
  margin: 0 2px 12px;
  padding: 8px 10px;
  border: 1px solid color-mix(in srgb, var(--brand-primary) 14%, var(--border-subtle));
  border-radius: var(--radius-sm);
  color: var(--text-secondary);
  background: color-mix(in srgb, rgb(var(--brand-selected-rgb) / 0.55) 42%, transparent);
  font-size: 12px;
  font-weight: 650;
  line-height: 1.5;
}

.execution-state {
  gap: 7px;
  margin: 2px 2px 12px;
  color: var(--text-secondary);
  font-size: 11px;
  font-weight: 750;

  i {
    width: 8px;
    height: 8px;
    border-radius: 50%;
    background: var(--status-neutral);
    box-shadow: 0 0 0 5px color-mix(in srgb, var(--status-neutral) 10%, transparent);
  }

  &.is-running { color: var(--brand-primary); }
  &.is-running i { background: var(--brand-primary); animation: status-pulse 1.8s ease-out infinite; }
  &.is-success { color: var(--status-success); }
  &.is-success i { background: var(--status-success); }
  &.is-error { color: var(--status-danger); }
  &.is-error i { background: var(--status-danger); }
  &.is-stopped { color: var(--status-warning); }
  &.is-stopped i { background: var(--status-warning); }
}

.metric-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
  margin-bottom: 12px;
}

.metric-card {
  padding: 11px 12px;
  border: 1px solid rgb(var(--brand-primary-rgb) / 0.12);
  border-radius: 12px;
  background:
    radial-gradient(circle at 8% 0%, var(--agent-card-anchor-glow), transparent 58%),
    linear-gradient(145deg, var(--surface-glass-control), rgb(var(--brand-selected-rgb) / 0.36));
  box-shadow: 0 8px 18px rgb(var(--brand-primary-rgb) / 0.06), var(--inner-highlight);

  span {
    display: block;
    color: var(--text-muted);
    font-size: 10px;
    font-weight: 700;
  }

  strong {
    display: block;
    margin-top: 5px;
    color: var(--brand-primary);
    font-size: 17px;
    font-weight: 850;
  }
}

.execution-step-list {
  display: grid;
  gap: 8px;
}

.execution-step {
  overflow: hidden;
  border: 1px solid rgb(var(--brand-primary-rgb) / 0.10);
  border-radius: 14px;
  background:
    linear-gradient(145deg, var(--surface-glass-control), rgb(var(--brand-selected-rgb) / 0.28));
  box-shadow: inset 0 1px 0 rgb(255 255 255 / 0.72);
  transition: border-color 0.16s ease, background 0.16s ease, box-shadow 0.16s ease;

  &.is-active {
    border-color: rgb(var(--brand-primary-rgb) / 0.30);
    background:
      radial-gradient(circle at 6% 0%, var(--agent-card-cool-glow), transparent 55%),
      linear-gradient(145deg, var(--surface-glass-control), rgb(var(--brand-selected-rgb) / 0.52));
    box-shadow: 0 8px 20px rgb(var(--brand-primary-rgb) / 0.10);
  }

  &.is-complete {
    border-color: color-mix(in srgb, var(--status-success) 22%, rgb(var(--brand-primary-rgb) / 0.10));
  }

  &.is-error {
    border-color: color-mix(in srgb, var(--status-danger) 30%, var(--border-subtle));
  }

  > button {
    display: grid;
    width: 100%;
    grid-template-columns: 35px minmax(0, 1fr) auto;
    gap: 10px;
    align-items: center;
    padding: 10px 11px;
    color: inherit;
    text-align: left;
    background: transparent;
  }

  > p {
    margin: 0;
    padding: 0 13px 12px 56px;
    color: var(--text-secondary);
    font-size: 11px;
    line-height: 1.6;
    word-break: break-word;
  }
}

.execution-step-marker {
  width: 34px;
  height: 34px;
}

.execution-step.is-error .execution-step-marker {
  color: var(--status-danger);
  background: color-mix(in srgb, var(--status-danger) 9%, var(--surface-solid-control));
}

.step-chevron {
  color: var(--text-muted);
  transition: transform 0.18s ease;
}

.execution-step.is-open .step-chevron {
  transform: rotate(180deg);
}

.empty-detail {
  display: flex;
  min-height: 210px;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  color: var(--text-muted);
  text-align: center;

  &.compact {
    min-height: 145px;
  }

  .el-icon {
    margin-bottom: 9px;
    font-size: 30px;
  }

  p {
    margin: 0;
    font-size: 12px;
    font-weight: 650;
  }
}

@media (prefers-reduced-motion: reduce) {
  .spin-icon,
    .run-state.active i,
    .execution-state.is-running i {
      animation: none !important;
    }
}
</style>
