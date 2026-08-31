<script setup lang="ts">
import { computed, reactive, watch } from 'vue'
import { CircleClose, CopyDocument, Refresh } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import AiCodingArtifactEvidence from '@/components/ai-coding/AiCodingArtifactEvidence.vue'
import AiCodingManagedExecutionPanel from '@/components/ai-coding/AiCodingManagedExecutionPanel.vue'
import type {
  AiCodingTask,
  AiCodingTaskDetail,
} from '@/types/aiCodingTask'
import {
  aiCodingConnectionCanRestore,
  aiCodingConnectionStatusDescription,
  aiCodingExecutionAcceptsClientAccess,
  aiCodingExecutionIsTerminal,
  aiCodingExecutionStatusTagType,
  aiCodingExecutionStatusLabel,
  aiCodingProviderLabel,
} from '@/utils/aiCodingPresentation'
import { copyAiCodingText } from '@/utils/aiCodingClipboard'

const props = withDefaults(defineProps<{
  detail: AiCodingTaskDetail | null
  loading?: boolean
  busy?: boolean
  variant?: 'drawer' | 'inline'
}>(), {
  variant: 'drawer',
})

const emit = defineEmits<{
  refresh: [taskId: string]
  answer: [taskId: string, questionId: string, answer: string]
  reissue: [taskId: string]
  verify: [taskId: string]
  acceptance: [taskId: string, passed: boolean, message: string]
  cancel: [taskId: string]
}>()

const answers = reactive<Record<string, string>>({})

watch(
  () => props.detail?.questions,
  (questions) => {
    for (const question of questions || []) {
      if (!(question.questionId in answers) || question.answer) {
        answers[question.questionId] = question.answer || ''
      }
    }
  },
  { immediate: true, deep: true },
)

const task = computed(() => props.detail?.task || null)
const readiness = computed(() => props.detail?.readiness || [])
const acceptanceActionsAvailable = computed(() =>
  Boolean(
    task.value?.executionStatus === 'ACCEPTANCE_READY'
      && (
        readiness.value.length === 0
        || readiness.value.every((item) => item.status === 'PASS')
      ),
  ),
)
const needsAcceptanceVerification = computed(() =>
  Boolean(
    task.value
      && ['RESULT_APPLIED', 'ACCEPTANCE_READY'].includes(
        task.value.executionStatus,
      )
      && readiness.value.length
      && readiness.value.some((item) => item.status !== 'PASS'),
  ),
)
const canReissue = computed(() =>
  Boolean(
    task.value
      && task.value.executionMode !== 'MANAGED_SANDBOX'
      && aiCodingExecutionAcceptsClientAccess(task.value.executionStatus)
      && ['WAITING_CONNECT', 'ACTIVE', 'TIMED_OUT', 'CLOSED'].includes(
        task.value.connection.status,
      ),
  ),
)
const canRestore = computed(() =>
  Boolean(
    task.value
      && task.value.executionMode !== 'MANAGED_SANDBOX'
      && aiCodingExecutionAcceptsClientAccess(task.value.executionStatus)
      && aiCodingConnectionCanRestore(task.value.connection),
  ),
)
const reissueActionLabel = computed(() =>
  task.value && ['ACTIVE', 'TIMED_OUT', 'CLOSED'].includes(task.value.connection.status)
    ? '重新生成交接包'
    : '生成交接包',
)
const canCancelInline = computed(() =>
  Boolean(
    props.variant === 'inline'
      && task.value
      && !aiCodingExecutionIsTerminal(task.value.executionStatus),
  ),
)
const canCancelDrawer = computed(() =>
  Boolean(
    props.variant === 'drawer'
      && task.value
      && !aiCodingExecutionIsTerminal(task.value.executionStatus),
  ),
)
const showFooter = computed(() =>
  Boolean(
    needsAcceptanceVerification.value
      || acceptanceActionsAvailable.value
      || canCancelDrawer.value
      || (props.variant === 'drawer' && canReissue.value),
  ),
)
const restoreCommand = computed(() =>
  task.value
    ? `. "$env:LOCALAPPDATA\\ReachAI\\ai-coding-sessions\\${task.value.taskId}.restore.ps1"`
    : '',
)

function submitAnswer(questionId: string) {
  const value = answers[questionId]?.trim()
  if (!value || !task.value) return
  emit('answer', task.value.taskId, questionId, value)
}

async function copyRestoreCommand() {
  if (!restoreCommand.value) return
  const copied = await copyAiCodingText(restoreCommand.value)
  if (copied.copied) {
    ElMessage.success('恢复命令已复制')
    return
  }
  ElMessage.warning('无法自动复制，请手动复制下方恢复命令。')
}

async function requestAcceptance(passed: boolean) {
  if (!task.value) return
  const action = passed ? '通过' : '不通过'
  await ElMessageBox.confirm(
    `确认将当前任务验收为“${action}”？`,
    '确认验收结果',
    {
      confirmButtonText: `确认${action}`,
      cancelButtonText: '返回',
      type: passed ? 'success' : 'warning',
    },
  )
  emit(
    'acceptance',
    task.value.taskId,
    passed,
    passed ? '用户在 ReachAI 确认验收通过' : '用户在 ReachAI 确认验收不通过',
  )
}

async function requestCancel() {
  if (!task.value) return
  await ElMessageBox.confirm(
    task.value.executionMode === 'MANAGED_SANDBOX'
      ? '取消后 Runtime 会终止 Worker 并清理一次性沙箱；已校验的证据仍会保留。'
      : '取消后当前交接凭据会立即失效，且该任务不能继续执行。',
    '取消 AI 编程任务',
    {
      confirmButtonText: '确认取消',
      cancelButtonText: '返回',
      type: 'warning',
    },
  )
  emit('cancel', task.value.taskId)
}

async function requestReissue() {
  if (!task.value) return
  if (task.value.connection.status === 'ACTIVE') {
    await ElMessageBox.confirm(
      '重新生成会立即撤销当前 AI 编程工具的任务凭据。仅在原终端和本机加密缓存都无法恢复时继续。',
      '确认重新生成交接包',
      {
        confirmButtonText: '撤销并重新生成',
        cancelButtonText: '返回',
        type: 'warning',
      },
    )
  }
  emit('reissue', task.value.taskId)
}

function primaryTarget(current: AiCodingTask) {
  return current.targets.find((target) => target.targetRole === 'PRIMARY')
}

function eventLabel(type: string) {
  return {
    CREATED: '任务已创建',
    HANDOFF_ISSUED: '交接包已签发',
    CONNECTION_ACTIVATED: '客户端已对接',
    STARTED: '开始执行',
    PROGRESS: '执行进度',
    QUESTION: 'AI 编程工具提问',
    QUESTION_ANSWERED: '用户已回答',
    RESUMED: '继续执行',
    RESULT_SUBMITTED: 'AI 编程工具已提交',
    ARTIFACT_REJECTED: '结果校验未通过',
    RESULT_APPLIED: '结果已应用',
    ACCEPTANCE_BLOCKED: '平台验证未通过',
    ACCEPTANCE_READY: '平台验证通过，待人工验收',
    ACCEPTANCE_PASSED: '验收通过',
    ACCEPTANCE_FAILED: '验收未通过',
    COMPLETED: '任务完成',
    FAILED: '任务失败',
    CANCELLED: '任务已取消',
    MANAGED_EXECUTION_STARTED: '隔离执行已启动',
    MANAGED_EXECUTION_SNAPSHOT: '隔离执行状态已同步',
    MANAGED_EXECUTION_STATUS_CHANGED: '隔离执行状态已变更',
    MANAGED_EXECUTION_APPROVAL_REQUESTED: '隔离执行请求审批',
    MANAGED_EXECUTION_APPROVAL_DECIDED: '审批决定已写入',
    MANAGED_EXECUTION_APPROVAL_RESOLVED: 'Worker 已执行审批决定',
    MANAGED_EXECUTION_SUCCEEDED: '隔离执行与证据校验成功',
    MANAGED_EXECUTION_FAILED: '隔离执行失败',
    MANAGED_EXECUTION_TIMED_OUT: '隔离执行超时',
  }[type] || type
}

function readinessTagType(status: string) {
  if (status === 'PASS') return 'success'
  if (status === 'FAIL') return 'danger'
  return 'warning'
}

function readinessStatusLabel(status: string) {
  return {
    PASS: '已通过',
    WARN: '需处理',
    FAIL: '未通过',
    PENDING: '待验证',
  }[status] || status
}

function artifactStatusLabel(status: string) {
  return {
    RECEIVED: '已收到',
    VALIDATED: '已校验',
    APPLIED: '已应用',
    REJECTED: '校验未通过',
  }[status] || status
}

function artifactTagType(status: string) {
  if (status === 'REJECTED') return 'danger'
  if (status === 'APPLIED') return 'success'
  return 'primary'
}

function formatDateTime(value?: string) {
  if (!value) return '—'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  }).format(date)
}

</script>

<template>
  <div
    class="ai-task-detail-panel"
    :class="{ 'is-inline': variant === 'inline' }"
    v-loading="loading"
  >
    <article v-if="detail && task" class="ai-task-detail">
      <header v-if="variant !== 'inline'" class="ai-task-hero">
        <div class="ai-task-hero__copy">
          <div class="ai-task-hero__title">
            <div class="ai-task-hero__heading">
              <h2>{{ task.title }}</h2>
              <small class="ai-task-id">{{ task.taskId }}</small>
            </div>
            <div class="ai-task-hero__meta">
              <el-tag
                :type="aiCodingExecutionStatusTagType(task.executionStatus)"
                effect="light"
                round
              >
                {{ aiCodingExecutionStatusLabel(task.executionStatus) }}
              </el-tag>
            </div>
          </div>
          <p>{{ task.objective }}</p>
        </div>
      </header>

      <section class="ai-task-summary-block">
        <header
          v-if="variant === 'inline'"
          class="ai-task-summary-toolbar"
        >
          <el-tag
            :type="aiCodingExecutionStatusTagType(task.executionStatus)"
            effect="light"
            round
          >
            {{ aiCodingExecutionStatusLabel(task.executionStatus) }}
          </el-tag>
          <nav
            class="ai-task-inline-actions"
            aria-label="AI 实施任务操作"
          >
            <el-button
              :icon="Refresh"
              circle
              :loading="busy"
              aria-label="刷新"
              title="刷新"
              @click="emit('refresh', task.taskId)"
            />
            <el-button
              v-if="canCancelInline"
              :icon="CircleClose"
              circle
              :disabled="busy"
              aria-label="取消任务"
              title="取消任务"
              @click="requestCancel"
            />
          </nav>
        </header>
        <dl class="ai-task-summary">
          <div>
            <dt>工具</dt>
            <dd>{{ aiCodingProviderLabel(task.executorProvider) }}</dd>
          </div>
          <div>
            <dt>访问范围</dt>
            <dd>{{ task.accessMode === 'READ_ONLY' ? '只读' : '可写' }}</dd>
          </div>
          <div>
            <dt>目标</dt>
            <dd>
              {{ primaryTarget(task)?.targetKey || task.projectCode }}
            </dd>
          </div>
          <div>
            <dt>{{ task.executionMode === 'MANAGED_SANDBOX' ? '执行方式' : '连接状态' }}</dt>
            <dd>
              {{ task.executionMode === 'MANAGED_SANDBOX'
                ? 'ReachAI 隔离执行'
                : aiCodingConnectionStatusDescription(task.connection) }}
            </dd>
          </div>
        </dl>
      </section>

      <AiCodingManagedExecutionPanel
        v-if="task.executionMode === 'MANAGED_SANDBOX'"
        :task="task"
        @refresh-task="emit('refresh', $event)"
      />

      <aside v-if="canRestore" class="ai-task-recovery">
        <span>
          <strong>终端重置后可继续当前任务</strong>
          <small>
            将恢复命令发回原 AI 编程工具；命令不含凭据。若本机加密缓存不存在，再重新生成交接包。
          </small>
          <code class="ai-task-recovery__command">{{ restoreCommand }}</code>
        </span>
        <el-button
          :icon="CopyDocument"
          :disabled="busy"
          @click="copyRestoreCommand"
        >
          复制恢复命令
        </el-button>
      </aside>

      <section v-if="readiness.length" class="ai-task-panel ai-task-readiness">
        <div class="ai-readiness-heading">
          <div>
            <h3>平台验证</h3>
            <p>以 ReachAI 服务端观测到的事实为准</p>
          </div>
          <el-tag
            v-if="needsAcceptanceVerification"
            type="warning"
            effect="plain"
          >
            尚未达到验收条件
          </el-tag>
        </div>
        <div class="ai-readiness-grid">
          <article
            v-for="item in readiness"
            :key="item.key"
            :class="`is-${item.status.toLowerCase()}`"
          >
            <span>
              <strong>{{ item.label }}</strong>
              <small>{{ item.message }}</small>
            </span>
            <el-tag
              effect="plain"
              size="small"
              :type="readinessTagType(item.status)"
            >
              {{ readinessStatusLabel(item.status) }}
            </el-tag>
          </article>
        </div>
      </section>

      <section v-if="detail.questions.length" class="ai-task-panel ai-task-questions">
        <h3>问题与回答</h3>
        <article
          v-for="question in detail.questions"
          :key="question.questionId"
          :class="{ 'is-answered': question.status === 'ANSWERED' }"
        >
          <strong>{{ question.title }}</strong>
          <p>{{ question.body }}</p>
          <div v-if="question.options?.length" class="ai-question-options">
            <button
              v-for="option in question.options"
              :key="option"
              type="button"
              :disabled="busy"
              @click="answers[question.questionId] = option"
            >
              {{ option }}
            </button>
          </div>
          <template v-if="question.status === 'OPEN'">
            <el-input
              v-model="answers[question.questionId]"
              type="textarea"
              :rows="3"
              placeholder="回答会写回当前任务"
            />
            <el-button
              type="primary"
              size="small"
              :loading="busy"
              :disabled="!answers[question.questionId]?.trim()"
              @click="submitAnswer(question.questionId)"
            >
              写回答案
            </el-button>
          </template>
          <p v-else class="ai-question-answer">
            <strong>回答：</strong>{{ question.answer || '—' }}
          </p>
        </article>
      </section>

      <div class="ai-task-split">
        <section class="ai-task-panel ai-task-events">
          <header class="ai-task-panel__heading">
            <h3>任务动态</h3>
            <small v-if="detail.events.length">{{ detail.events.length }} 条</small>
          </header>
          <el-timeline v-if="detail.events.length">
            <el-timeline-item
              v-for="event in detail.events"
              :key="event.id"
              :timestamp="formatDateTime(event.createdAt)"
              placement="top"
            >
              <strong>{{ eventLabel(event.eventType) }}</strong>
              <p>
                {{
                  event.message
                    || aiCodingExecutionStatusLabel(event.executionStatusAfter)
                }}
              </p>
            </el-timeline-item>
          </el-timeline>
          <p v-else class="ai-task-empty">还没有任务动态</p>
        </section>

        <section class="ai-task-panel ai-task-results">
          <header class="ai-task-panel__heading">
            <h3>结果与验收材料</h3>
            <small v-if="detail.artifacts.length">{{ detail.artifacts.length }} 项</small>
          </header>
          <div v-if="detail.artifacts.length" class="ai-task-artifacts">
            <article
              v-for="artifact in detail.artifacts"
              :key="artifact.artifactId"
            >
              <div class="ai-artifact-heading">
                <span>
                  <strong>{{ artifact.contractKey }}</strong>
                  <small>{{ artifact.artifactKey }}</small>
                  <em v-if="artifact.validationMessage">
                    {{ artifact.validationMessage }}
                  </em>
                </span>
                <el-tag
                  effect="plain"
                  size="small"
                  :type="artifactTagType(artifact.processingStatus)"
                >
                  {{ artifactStatusLabel(artifact.processingStatus) }}
                </el-tag>
              </div>
              <AiCodingArtifactEvidence
                v-if="artifact.applicationResult"
                :application-result="artifact.applicationResult"
              />
            </article>
          </div>
          <p v-else class="ai-task-empty">结果尚未回传</p>
        </section>
      </div>

      <footer v-if="showFooter">
        <el-button
          v-if="canCancelDrawer"
          text
          :disabled="busy"
          @click="requestCancel"
        >
          取消任务
        </el-button>
        <span />
        <el-button
          v-if="needsAcceptanceVerification"
          type="primary"
          :icon="Refresh"
          :loading="busy"
          @click="emit('verify', task.taskId)"
        >
          重新验证
        </el-button>
        <el-button
          v-if="variant === 'drawer' && canReissue"
          type="primary"
          :icon="CopyDocument"
          :loading="busy"
          @click="requestReissue"
        >
          {{ reissueActionLabel }}
        </el-button>
        <template v-if="acceptanceActionsAvailable">
          <el-button :disabled="busy" @click="requestAcceptance(false)">
            验收不通过
          </el-button>
          <el-button
            type="primary"
            :loading="busy"
            @click="requestAcceptance(true)"
          >
            确认验收通过
          </el-button>
        </template>
      </footer>
    </article>
    <el-empty v-else-if="!loading" description="任务详情不可用" />
  </div>
</template>

<style scoped>
.ai-task-detail {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 16px;
}

.ai-task-detail-panel.is-inline {
  display: flex;
  min-height: 0;
  flex: 1;
  flex-direction: column;
}

.ai-task-detail-panel.is-inline .ai-task-detail {
  flex: 1;
  min-height: 0;
}

.ai-task-inline-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.ai-task-summary-block {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 10px;
}

.ai-task-summary-toolbar {
  display: flex;
  min-width: 0;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.ai-task-hero {
  padding: 16px 18px;
  border: 1px solid color-mix(in srgb, var(--brand-primary) 16%, var(--border-subtle));
  border-radius: 14px;
  background:
    linear-gradient(
      135deg,
      color-mix(in srgb, var(--brand-primary) 8%, var(--surface-solid-panel)),
      color-mix(in srgb, var(--surface-solid-panel) 90%, transparent)
    );
  box-shadow: 0 10px 24px rgb(var(--brand-primary-rgb) / 0.05);
}

.ai-task-hero__copy,
.ai-task-hero__copy small,
.ai-task-hero__copy h2,
.ai-task-hero__copy p,
.ai-task-panel h3,
.ai-task-panel p,
.ai-task-panel__heading small {
  display: block;
  min-width: 0;
  margin: 0;
}

.ai-task-hero__heading {
  display: flex;
  min-width: 0;
  flex: 1 1 auto;
  flex-direction: column;
  gap: 2px;
}

.ai-task-hero__title h2 {
  color: var(--text-primary);
  font-size: 1.12rem;
  line-height: 1.35;
}

.ai-task-id {
  color: var(--text-muted);
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 0.68rem;
  font-weight: 560;
  letter-spacing: 0.01em;
  line-height: 1.3;
}

.ai-task-hero__title {
  display: flex;
  min-width: 0;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

.ai-task-hero__meta {
  display: flex;
  flex: 0 0 auto;
  align-items: center;
  justify-content: flex-end;
  flex-wrap: wrap;
  gap: 8px;
}

.ai-task-hero__copy > p {
  margin-top: 10px;
  padding: 10px 12px;
  border-radius: 10px;
  background: color-mix(
    in srgb,
    var(--surface-solid-disabled) 42%,
    var(--surface-solid-panel)
  );
  color: var(--text-secondary);
  font-size: 0.76rem;
  line-height: 1.65;
  white-space: pre-wrap;
  word-break: break-word;
}

.ai-task-summary {
  display: grid;
  margin: 0;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 10px;
}

.ai-task-summary > div {
  position: relative;
  min-width: 0;
  padding: 14px 14px 13px;
  overflow: hidden;
  border: 1px solid var(--border-subtle);
  border-radius: 12px;
  background:
    linear-gradient(
      180deg,
      color-mix(in srgb, var(--brand-primary) 5%, var(--surface-solid-panel)),
      var(--surface-solid-panel)
    );
  box-shadow: 0 8px 18px rgb(var(--brand-primary-rgb) / 0.03);
}

.ai-task-summary > div::before {
  position: absolute;
  top: 0;
  right: 14px;
  left: 14px;
  height: 2px;
  border-radius: 999px;
  background: linear-gradient(90deg, var(--brand-primary), var(--brand-hover));
  content: "";
  opacity: 0.7;
}

.ai-task-summary dt {
  color: var(--text-muted);
  font-size: 0.66rem;
  font-weight: 700;
}

.ai-task-summary dd {
  margin: 7px 0 0;
  overflow: hidden;
  color: var(--text-primary);
  font-size: 0.84rem;
  font-weight: 760;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ai-task-recovery {
  display: flex;
  min-width: 0;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  padding: 13px 14px;
  border: 1px solid color-mix(in srgb, var(--brand-active) 20%, var(--border-subtle));
  border-radius: 12px;
  background: color-mix(in srgb, var(--brand-active) 5%, var(--surface-solid-panel));
}

.ai-task-recovery > span {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 4px;
}

.ai-task-recovery strong {
  color: var(--text-primary);
  font-size: 0.78rem;
}

.ai-task-recovery small {
  color: var(--text-muted);
  font-size: 0.7rem;
  line-height: 1.5;
}

.ai-task-recovery__command {
  overflow-wrap: anywhere;
  color: var(--text-secondary);
  font-size: 0.68rem;
  line-height: 1.5;
  user-select: all;
}

.ai-task-split {
  display: grid;
  min-width: 0;
  grid-template-columns: minmax(0, 1.15fr) minmax(0, 0.85fr);
  gap: 12px;
  align-items: start;
}

.ai-task-detail-panel.is-inline .ai-task-split {
  flex: 1;
  min-height: 0;
  align-items: stretch;
}

.ai-task-panel {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 12px;
  padding: 14px 16px 16px;
  border: 1px solid var(--border-subtle);
  border-radius: 14px;
  background: color-mix(in srgb, var(--surface-solid-overlay) 88%, transparent);
  box-shadow: 0 10px 24px rgb(var(--brand-primary-rgb) / 0.035);
}

.ai-task-detail-panel.is-inline .ai-task-split > .ai-task-panel {
  min-height: 0;
  height: 100%;
  overflow: hidden;
}

.ai-task-detail-panel.is-inline .ai-task-events :deep(.el-timeline),
.ai-task-detail-panel.is-inline .ai-task-artifacts,
.ai-task-detail-panel.is-inline .ai-task-empty {
  min-height: 0;
  flex: 1;
  overflow-y: auto;
}

.ai-task-detail-panel.is-inline .ai-task-empty {
  display: grid;
  place-items: center;
  margin: 0;
}

.ai-task-panel__heading {
  display: flex;
  min-width: 0;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.ai-task-panel__heading small {
  display: inline-flex;
  min-height: 22px;
  align-items: center;
  padding: 0 8px;
  border-radius: 999px;
  background: color-mix(
    in srgb,
    var(--brand-primary) 10%,
    var(--surface-solid-control)
  );
  color: var(--brand-active);
  font-size: 0.66rem;
  font-weight: 740;
}

.ai-task-panel h3 {
  color: var(--text-primary);
  font-size: 0.88rem;
}

.ai-task-panel p {
  color: var(--text-muted);
  font-size: 0.78rem;
  line-height: 1.6;
}

.ai-readiness-heading {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

.ai-readiness-heading p {
  margin-top: 4px !important;
}

.ai-readiness-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px;
}

.ai-readiness-grid > article {
  display: flex;
  min-width: 0;
  align-items: flex-start;
  justify-content: space-between;
  gap: 10px;
  padding: 12px;
  border: 1px solid var(--border-subtle);
  border-radius: 12px;
  background: var(--surface-solid-control);
}

.ai-readiness-grid > article.is-pass {
  border-color: color-mix(in srgb, var(--status-success) 28%, var(--border-subtle));
}

.ai-readiness-grid > article.is-fail {
  border-color: color-mix(in srgb, var(--status-danger) 30%, var(--border-subtle));
}

.ai-readiness-grid > article.is-warn,
.ai-readiness-grid > article.is-pending {
  border-color: color-mix(in srgb, var(--status-warning) 30%, var(--border-subtle));
}

.ai-readiness-grid span {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 5px;
}

.ai-readiness-grid strong {
  color: var(--text-primary);
  font-size: 0.78rem;
}

.ai-readiness-grid small {
  color: var(--text-muted);
  font-size: 0.7rem;
  line-height: 1.5;
}

.ai-task-questions > article {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 9px;
  padding: 14px;
  border: 1px solid color-mix(in srgb, var(--status-warning) 28%, var(--border-subtle));
  border-radius: 12px;
  background: color-mix(in srgb, var(--status-warning) 6%, var(--surface-solid-panel));
}

.ai-task-questions > article.is-answered {
  border-color: var(--border-subtle);
  background: var(--surface-solid-control);
}

.ai-question-options {
  display: flex;
  flex-wrap: wrap;
  gap: 7px;
}

.ai-question-options button {
  padding: 5px 9px;
  border: 1px solid var(--border-subtle);
  border-radius: 999px;
  color: var(--text-secondary);
  background: var(--surface-solid-panel);
  cursor: pointer;
}

.ai-question-answer {
  color: var(--text-secondary) !important;
}

.ai-task-artifacts {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.ai-task-artifacts > article {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 12px;
  padding: 12px;
  border: 1px solid var(--border-subtle);
  border-radius: 12px;
  background: var(--surface-solid-control);
}

.ai-artifact-heading {
  display: flex;
  min-width: 0;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

.ai-artifact-heading > span {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 3px;
}

.ai-task-artifacts small {
  color: var(--text-muted);
}

.ai-task-artifacts em {
  color: var(--status-danger);
  font-size: 0.74rem;
  font-style: normal;
  line-height: 1.5;
}

.ai-task-empty {
  margin: 0;
  padding: 28px 14px;
  border: 1px dashed var(--border-subtle);
  border-radius: 12px;
  background: color-mix(
    in srgb,
    var(--surface-solid-disabled) 34%,
    var(--surface-solid-panel)
  );
  color: var(--text-muted);
  font-size: 0.76rem;
  text-align: center;
}

.ai-task-events :deep(.el-timeline) {
  padding-left: 2px;
}

.ai-task-events :deep(.el-timeline-item__timestamp) {
  color: var(--text-muted);
  font-size: 0.68rem;
}

.ai-task-events :deep(.el-timeline-item__content) strong {
  color: var(--text-primary);
  font-size: 0.8rem;
}

.ai-task-events :deep(.el-timeline-item__node) {
  background: var(--brand-primary);
}

.ai-task-detail > footer {
  display: flex;
  align-items: center;
  gap: 8px;
  padding-top: 4px;
}

.ai-task-detail > footer > span {
  flex: 1;
}

@media (max-width: 1100px) {
  .ai-task-split {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 720px) {
  .ai-task-summary {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .ai-task-hero__title {
    align-items: flex-start;
    flex-direction: column;
  }

  .ai-task-hero__meta {
    width: 100%;
    justify-content: flex-start;
  }

  .ai-task-detail > footer {
    align-items: stretch;
    flex-direction: column;
  }

  .ai-task-recovery {
    align-items: stretch;
    flex-direction: column;
  }

  .ai-task-detail > footer > span {
    display: none;
  }

  .ai-readiness-grid {
    grid-template-columns: 1fr;
  }
}
</style>
