<template>
  <LiquidGlassCard class="workflow-approvals" source="wallpaper">
    <div class="section-heading">
      <div>
        <h2>工作流审批</h2>
        <p>工作流里的「人工确认」指定由你（或你的角色）来批。批准或拒绝后，流程会在发起人那边按发起人自己的权限继续执行。</p>
      </div>
      <div class="section-actions">
        <div class="workflow-approvals__filters" role="group" aria-label="按状态筛选">
          <button type="button" :class="{ active: scope === 'pending' }" @click="scope = 'pending'">待我审批</button>
          <button type="button" :class="{ active: scope === 'decided' }" @click="scope = 'decided'">我已处理</button>
          <button v-if="isAdmin" type="button" :class="{ active: scope === 'all' }" @click="scope = 'all'">全部待审批</button>
        </div>
        <LiquidGlassButton :loading="loading" @click="load">刷新</LiquidGlassButton>
      </div>
    </div>

    <p v-if="loadError" class="error-message" role="alert">{{ loadError }}</p>
    <p v-if="loading && !items.length" class="muted" role="status">正在加载…</p>
    <LiquidGlassEmptyState
      v-else-if="!loading && !loadError && !items.length"
      :title="scope === 'decided' ? '还没有处理过工作流审批' : '暂无待审批的工作流'"
      description="工作流运行到指定你审批的「人工确认」时，会出现在这里。"
    />
    <ul v-else-if="items.length" class="approval-list">
      <li v-for="item in items" :key="item.interactionId" class="approval-row" :data-testid="`workflow-approval-${item.interactionId}`">
        <div class="approval-main">
          <div class="approval-title">
            <strong>{{ item.title }}</strong>
            <el-tag size="small" :type="statusTone(item.status)" effect="plain">{{ statusLabel(item.status) }}</el-tag>
          </div>
          <p v-if="item.message" class="approval-message">{{ item.message }}</p>
          <small>
            {{ item.workflowName || '工作流' }}<template v-if="item.agentName"> · {{ item.agentName }}</template>
            · 发起人 {{ item.runnerName || '未知' }} · 发起于 {{ formatTime(item.createdAt) }}
            <template v-if="item.status === 'PENDING'"> · 截止 {{ formatTime(item.expiresAt) }}</template>
          </small>
          <small v-if="item.assignees" class="muted">审批人：{{ item.assignees }}</small>
          <small v-if="item.decision" class="approval-decision">
            {{ item.decision.approverName }}{{ item.decision.onBehalf ? '（代为处理）' : '' }}
            {{ item.decision.decision === 'approved' ? '批准' : '拒绝' }}于 {{ formatTime(item.decision.decidedAt) }}
            <template v-if="item.decision.comment">：{{ item.decision.comment }}</template>
          </small>
        </div>
        <div v-if="item.status === 'PENDING'" class="approval-actions">
          <span v-if="item.selfRun" class="muted">你发起的运行，不能自己审批</span>
          <template v-else>
            <LiquidGlassButton type="primary" @click="openDecision(item, 'approve')">批准</LiquidGlassButton>
            <LiquidGlassButton @click="openDecision(item, 'reject')">拒绝</LiquidGlassButton>
          </template>
        </div>
      </li>
    </ul>

    <AppDialog
      v-model="dialogOpen"
      :title="pendingDecision === 'approve' ? '批准这次确认' : '拒绝这次确认'"
      width="560px"
      :show-close="!deciding"
      :close-on-click-modal="!deciding"
      :close-on-press-escape="!deciding"
    >
      <div v-if="target" class="decision-body">
        <p><strong>{{ target.title }}</strong></p>
        <p v-if="target.message" class="approval-message">{{ target.message }}</p>
        <p class="muted">
          {{ target.workflowName || '工作流' }} · 发起人 {{ target.runnerName || '未知' }}
          <template v-if="!target.assignedToMe"> · 你是平台管理员，将代为处理</template>
        </p>
        <el-input
          v-model="comment"
          type="textarea"
          :rows="3"
          maxlength="500"
          show-word-limit
          :placeholder="pendingDecision === 'approve' ? '审批意见（可选）' : '拒绝原因（建议填写，发起人能看到）'"
        />
        <p v-if="decideError" class="error-message" role="alert">{{ decideError }}</p>
      </div>
      <template #footer>
        <LiquidGlassButton :disabled="deciding" @click="dialogOpen = false">取消</LiquidGlassButton>
        <LiquidGlassButton type="primary" :loading="deciding" @click="submitDecision">
          {{ pendingDecision === 'approve' ? '确认批准' : '确认拒绝' }}
        </LiquidGlassButton>
      </template>
    </AppDialog>
  </LiquidGlassCard>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import AppDialog from '@/components/common/AppDialog.vue'
import { LiquidGlassButton, LiquidGlassCard, LiquidGlassEmptyState } from '@/components/common/liquid-glass'
import {
  WORKFLOW_APPROVAL_STATUS_LABEL,
  decideWorkflowApproval,
  listWorkflowApprovals,
  type WorkflowApprovalItem,
  type WorkflowApprovalScope,
  type WorkflowApprovalStatus,
} from '@/api/workflowApprovals'
import { platformSessionUser } from '@/auth/platformSession'
import { refreshWorkflowApprovalCount } from '@/composables/useWorkflowApprovalCount'

const emit = defineEmits<{ pendingCount: [count: number] }>()

const isAdmin = computed(() => (platformSessionUser.value?.roles ?? []).includes('PLATFORM_ADMIN'))
const scope = ref<WorkflowApprovalScope>('pending')
const items = ref<WorkflowApprovalItem[]>([])
const loading = ref(false)
const loadError = ref('')
const dialogOpen = ref(false)
const target = ref<WorkflowApprovalItem | null>(null)
const pendingDecision = ref<'approve' | 'reject'>('approve')
const comment = ref('')
const deciding = ref(false)
const decideError = ref('')
let generation = 0

async function load() {
  const current = ++generation
  loading.value = true
  loadError.value = ''
  try {
    const { data } = await listWorkflowApprovals(scope.value)
    if (current !== generation) return
    items.value = data?.items ?? []
    if (scope.value === 'pending') emit('pendingCount', items.value.length)
  } catch {
    if (current === generation) loadError.value = '工作流审批加载失败，请刷新重试。'
  } finally {
    if (current === generation) loading.value = false
  }
}

function openDecision(item: WorkflowApprovalItem, decision: 'approve' | 'reject') {
  target.value = item
  pendingDecision.value = decision
  comment.value = ''
  decideError.value = ''
  dialogOpen.value = true
}

async function submitDecision() {
  if (!target.value) return
  deciding.value = true
  decideError.value = ''
  try {
    await decideWorkflowApproval(target.value.interactionId, pendingDecision.value, comment.value)
    dialogOpen.value = false
    ElMessage.success(pendingDecision.value === 'approve'
      ? '已批准，流程会在发起人那边继续执行。'
      : '已拒绝，流程会走「拒绝」分支。')
    await load()
    void refreshWorkflowApprovalCount()
  } catch (error) {
    const response = (error as { response?: { data?: { message?: string } } })?.response
    decideError.value = response?.data?.message || '处理失败，请刷新后重试。'
    await load()
  } finally {
    deciding.value = false
  }
}

function statusLabel(status: WorkflowApprovalStatus) {
  return WORKFLOW_APPROVAL_STATUS_LABEL[status] ?? status
}

function statusTone(status: WorkflowApprovalStatus): 'success' | 'info' | 'warning' | 'danger' | 'primary' {
  if (status === 'PENDING') return 'warning'
  if (status === 'APPROVED' || status === 'CONTINUED_APPROVED') return 'success'
  if (status === 'FAILED') return 'danger'
  return 'info'
}

function formatTime(value?: string | null) {
  if (!value) return '-'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return date.toLocaleString('zh-CN', { hour12: false, month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' })
}

watch(scope, () => { void load() })
onMounted(() => { void load() })
</script>

<style scoped lang="scss">
.section-heading {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  align-items: flex-start;
  justify-content: space-between;
  margin-bottom: 16px;

  h2 {
    margin: 0 0 4px;
    font-size: 16px;
  }

  p {
    max-width: 640px;
    margin: 0;
    color: var(--text-secondary);
    font-size: 13px;
  }
}

.section-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
}

.workflow-approvals__filters {
  display: inline-flex;
  gap: 4px;

  button {
    padding: 4px 10px;
    border: 1px solid var(--border-subtle, rgba(127, 127, 127, 0.25));
    border-radius: 999px;
    background: transparent;
    color: var(--text-secondary);
    cursor: pointer;
    font-size: 13px;

    &.active {
      border-color: var(--color-primary, #409eff);
      color: var(--color-primary, #409eff);
    }
  }
}

.approval-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.approval-row {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  align-items: center;
  justify-content: space-between;
  padding: 12px 14px;
  border: 1px solid var(--border-subtle, rgba(127, 127, 127, 0.18));
  border-radius: 12px;
}

.approval-main {
  display: flex;
  flex: 1 1 320px;
  flex-direction: column;
  gap: 4px;
  min-width: 0;

  small {
    color: var(--text-muted);
    font-size: 12px;
  }
}

.approval-title {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
}

.approval-message {
  margin: 0;
  color: var(--text-primary);
  font-size: 13px;
  white-space: pre-wrap;
  word-break: break-word;
}

.approval-decision {
  color: var(--text-secondary) !important;
}

.approval-actions {
  display: flex;
  gap: 8px;
  align-items: center;
}

.decision-body {
  display: flex;
  flex-direction: column;
  gap: 10px;

  p {
    margin: 0;
  }
}

.muted {
  color: var(--text-muted);
  font-size: 12px;
}

.error-message {
  color: var(--color-danger, #f56c6c);
  font-size: 13px;
}
</style>
