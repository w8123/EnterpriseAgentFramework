<template>
  <div class="approval-panel">
    <div class="approval-toolbar">
      <span>待处理审批</span>
      <el-button size="small" :loading="loading" @click="emit('refresh')">刷新</el-button>
    </div>
    <div v-if="!approvals.length" class="empty-detail compact">
      <el-icon><CircleCheck /></el-icon>
      <p>暂无待处理审批</p>
    </div>
    <div v-else class="approval-list">
      <article v-for="item in approvals" :key="item.interactionId" class="approval-item">
        <div class="approval-title">
          <strong>{{ item.title || item.nodeId || '人工审批' }}</strong>
          <StatusTag :label="item.status" tone="warning" />
        </div>
        <p v-if="item.message" class="approval-message">{{ item.message }}</p>
        <div class="approval-meta">
          <span>{{ item.nodeId }}</span>
          <button v-if="item.traceId" type="button" @click="emit('open-trace', item.traceId)">查看运行详情</button>
        </div>
        <DynamicInteraction
          v-if="item.uiRequest"
          class="interaction-card"
          :payload="item.uiRequest"
          @action="(act, vals) => emit('action', item, act, vals)"
        />
      </article>
    </div>
  </div>
</template>

<script setup lang="ts">
import { CircleCheck } from '@element-plus/icons-vue'
import StatusTag from '@/components/common/StatusTag.vue'
import DynamicInteraction from '@/components/interaction/DynamicInteraction.vue'
import type { PendingHumanApproval } from '@/types/agent'

defineProps<{
  approvals: PendingHumanApproval[]
  loading: boolean
}>()

const emit = defineEmits<{
  refresh: []
  'open-trace': [traceId: string]
  action: [approval: PendingHumanApproval, action: string, values: Record<string, unknown>]
}>()
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

.execution-panel,
.approval-panel {
  padding: 0 2px 12px;
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

.approval-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 10px;
  color: var(--text-secondary);
  font-size: 12px;
  font-weight: 760;
}

.approval-list {
  display: grid;
  gap: 10px;
}

.approval-item {
  padding: 13px;
  border: 1px solid color-mix(in srgb, var(--status-warning) 24%, rgb(var(--brand-primary-rgb) / 0.12));
  border-radius: 14px;
  background:
    linear-gradient(145deg, var(--surface-glass-control), rgb(var(--brand-selected-rgb) / 0.22)),
    color-mix(in srgb, var(--status-warning) 6%, transparent);
}

.approval-title {
  justify-content: space-between;
  gap: 9px;

  strong {
    color: var(--text-primary);
    font-size: 12px;
  }
}

.approval-message {
  margin: 9px 0 0;
  color: var(--text-secondary);
  font-size: 11px;
  line-height: 1.6;
}

.approval-meta {
  gap: 8px 12px;
  margin-top: 9px;
  flex-wrap: wrap;
  color: var(--text-muted);
  font-size: 10px;

  button {
    padding: 0;
    color: var(--brand-primary);
    background: transparent;
  }
}
</style>
