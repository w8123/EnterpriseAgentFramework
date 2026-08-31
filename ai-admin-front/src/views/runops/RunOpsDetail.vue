<template>
  <WorkbenchPage class="runops-detail">
    <PageHeader
      variant="entity"
      domain="governance"
      height-preset="compact"
      eyebrow="运行诊断"
      :title="detailTitle"
      show-back
      @back="router.push('/runops')"
    >
      <template #tags>
        <el-tag v-if="summary" size="small" effect="plain" :type="summary.runType === 'AGENT' ? 'success' : 'primary'">
          {{ runTypeLabel(summary.runType) }}
        </el-tag>
        <el-tag v-if="summary" size="small" effect="plain" :type="statusTagType(summary.status)">
          {{ lifecycleStatusLabel(summary) }}
        </el-tag>
      </template>
      <template #actions>
        <el-tooltip content="刷新运行详情" placement="top">
          <el-button
            circle
            :icon="Refresh"
            :loading="loading"
            aria-label="刷新运行详情"
            @click="loadDetail"
          />
        </el-tooltip>
        <el-button :disabled="!summary" @click="copyIssueSummary">复制运行摘要</el-button>
        <el-button
          v-if="summary?.runType === 'WORKFLOW' && summary.workflowId && canWriteSourceWorkflow"
          @click="router.push(`/workflows/${summary.workflowId}/studio`)"
        >
          打开工作流编排
        </el-button>
        <el-dropdown trigger="click" @command="handleMoreCommand">
          <el-button :icon="MoreFilled" aria-label="更多运行操作" />
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="context">运行上下文</el-dropdown-item>
              <el-dropdown-item command="candidate">
                Workflow 候选资格
                <span class="menu-state">
                  {{ candidateEligibility?.eligible ? '可创建' : `${candidateEligibility?.blockers.length || 0} 项未满足` }}
                </span>
              </el-dropdown-item>
              <el-dropdown-item v-if="comparison" command="compare">查看重放对比</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
        <el-button
          v-if="canOperateRun && summary?.runType !== 'MCP'"
          type="primary"
          :icon="VideoPlay"
          :loading="replaying"
          :disabled="!detail"
          @click="openReplayDialog"
        >
          重放运行
        </el-button>
      </template>
    </PageHeader>

    <el-empty v-if="!loading && !detail" description="未找到运行记录" />

    <template v-if="detail && summary">
      <section :class="['run-overview-panel', `is-${diagnosticState.tone}`]" aria-label="运行结论与上下文">
        <div class="run-conclusion-row">
          <div class="run-conclusion-icon" aria-hidden="true">{{ diagnosticIcon }}</div>
          <div class="run-conclusion-copy">
            <div class="run-conclusion-heading">
              <el-tag size="small" :type="diagnosticState.tagType" effect="plain" round>
                {{ diagnosticState.label }}
              </el-tag>
              <strong>{{ diagnosticState.title }}</strong>
              <el-button v-if="failureFocus" link type="danger" @click="focusFailure">定位失败点 →</el-button>
            </div>
            <p>{{ diagnosticState.description }}</p>
          </div>
          <dl class="run-vitals" aria-label="运行关键指标">
            <div>
              <dt>耗时</dt>
              <dd>{{ formatDuration(summary.latencyMs) }}</dd>
            </div>
            <div>
              <dt>Token</dt>
              <dd>{{ formatTokenCount(summary.tokenCost) }}</dd>
            </div>
            <div>
              <dt>规划</dt>
              <dd>{{ summary.planCount ?? 0 }} / 重规划 {{ summary.replanCount ?? 0 }}</dd>
            </div>
          </dl>
        </div>

        <div class="run-context-strip">
          <span>项目 <strong>{{ summary.projectCode || '-' }}</strong></span>
          <span>发布版本 <strong>{{ runVersionLabel(summary) }}</strong></span>
          <span>入口 <strong>{{ entryTypeLabel(summary.entryType) }}</strong></span>
          <span class="trace-context">
            Trace
            <el-tooltip :content="traceId" placement="top" :show-after="400">
              <code>{{ shortIdentifier(traceId) }}</code>
            </el-tooltip>
            <el-button
              link
              :icon="CopyDocument"
              aria-label="复制追踪 ID"
              @click="copyIdentifier('追踪 ID', traceId)"
            />
          </span>
          <el-button link class="context-entry" @click="contextDrawerVisible = true">运行上下文 ›</el-button>
        </div>
      </section>

      <RunOpsInvestigationWorkbench
        ref="investigationWorkbenchRef"
        :detail="detail"
        :summary="summary"
        :comparison="comparison"
      />

    </template>

    <AppDrawer
      v-if="summary"
      v-model="contextDrawerVisible"
      title="运行上下文"
      description="用于复现、审计与跨团队协作的完整运行证据。"
      size="560px"
    >
      <section class="context-drawer-section">
        <h3>标识与入口</h3>
        <el-descriptions :column="1" border>
          <el-descriptions-item label="追踪 ID">
            <div class="copyable-value">
              <code>{{ traceId }}</code>
              <el-button link :icon="CopyDocument" @click="copyIdentifier('追踪 ID', traceId)" />
            </div>
          </el-descriptions-item>
          <el-descriptions-item label="会话 ID">
            <div class="copyable-value">
              <code>{{ summary.sessionId || '-' }}</code>
              <el-button
                link
                :icon="CopyDocument"
                :disabled="!summary.sessionId"
                @click="copyIdentifier('会话 ID', summary.sessionId)"
              />
            </div>
          </el-descriptions-item>
          <el-descriptions-item label="用户 ID"><code>{{ summary.userId || '-' }}</code></el-descriptions-item>
          <el-descriptions-item label="租户"><code>{{ summary.tenantId || '-' }}</code></el-descriptions-item>
          <el-descriptions-item label="所属项目">{{ summary.projectCode || '-' }}</el-descriptions-item>
          <el-descriptions-item label="运行入口">{{ entryTypeLabel(summary.entryType) }}</el-descriptions-item>
        </el-descriptions>
      </section>

      <section class="context-drawer-section">
        <h3>发布快照</h3>
        <el-descriptions :column="1" border>
          <el-descriptions-item :label="runTypeLabel(summary.runType)">{{ runObjectId(summary) }}</el-descriptions-item>
          <el-descriptions-item label="发布版本">{{ runVersionLabel(summary) }}</el-descriptions-item>
          <el-descriptions-item label="运行时">{{ formatRuntimeTypeLabel(summary.runtimeType) }}</el-descriptions-item>
          <el-descriptions-item label="开始时间">{{ formatDateTime(summary.startedAt) }}</el-descriptions-item>
          <el-descriptions-item label="结束时间">{{ summary.endedAt ? formatDateTime(summary.endedAt) : '—（运行未结束）' }}</el-descriptions-item>
          <el-descriptions-item label="调用统计">
            {{ summary.planCount ?? 0 }} 规划 · {{ summary.replanCount ?? 0 }} 重规划 ·
            {{ summary.workflowCallCount ?? 0 }} Workflow · {{ summary.toolCallCount ?? 0 }} Tool
          </el-descriptions-item>
        </el-descriptions>
      </section>

      <section v-if="summary.replayOfTraceId || compareSource" class="context-drawer-section">
        <h3>重放关系</h3>
        <el-descriptions :column="1" border>
          <el-descriptions-item label="原始运行"><code>{{ summary.replayOfTraceId || compareSource }}</code></el-descriptions-item>
          <el-descriptions-item label="当前运行"><code>{{ traceId }}</code></el-descriptions-item>
        </el-descriptions>
      </section>

      <section class="context-drawer-section">
        <h3>重放边界</h3>
        <el-alert
          type="info"
          :closable="false"
          description="重放固定使用本次运行的历史发布版本与配置快照，不会静默切换到当前最新配置；新运行会保留对原始运行的审计引用。"
        />
      </section>
    </AppDrawer>

    <AppDialog v-model="replayDialogVisible" title="重放运行" width="560px">
      <el-alert
        class="replay-alert"
        type="info"
        :closable="false"
        show-icon
        title="重放使用原运行的已发布配置版本"
        description="重放不会切换到当前活动配置；新的追踪记录将自动与原运行建立对比关系。"
      />
      <div v-if="summary" class="replay-version-box">
        固定版本：<strong>{{ runTypeLabel(summary.runType) }} · {{ runVersionLabel(summary) }} · {{ formatRuntimeTypeLabel(summary.runtimeType) }}</strong>
      </div>
      <details class="replay-advanced">
        <summary>高级覆盖选项 <small>默认复用原运行输入与身份</small></summary>
        <el-form label-width="110px">
        <el-form-item label="覆盖输入">
          <el-input
            v-model="replayForm.messageOverride"
            type="textarea"
            :rows="4"
            placeholder="留空则复用原追踪输入"
          />
        </el-form-item>
        <el-form-item label="用户 ID">
          <el-input v-model="replayForm.userId" clearable placeholder="留空则复用原运行用户" />
        </el-form-item>
        <el-form-item label="会话 ID">
          <el-input v-model="replayForm.sessionId" clearable placeholder="留空则自动生成重放会话" />
        </el-form-item>
        <el-form-item label="角色">
          <el-select
            v-model="replayRoles"
            multiple
            filterable
            allow-create
            default-first-option
            placeholder="可选，输入后回车创建"
          />
        </el-form-item>
        </el-form>
      </details>
      <template #footer>
        <el-button @click="replayDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="replaying" @click="replayTrace">开始重放</el-button>
      </template>
    </AppDialog>

    <AppDialog
      v-model="candidateDialogVisible"
      :title="candidateEligibility?.eligible ? '生成 Workflow 候选' : 'Workflow 候选资格'"
      width="640px"
    >
      <template v-if="candidateLoading">
        <el-skeleton :rows="6" animated />
      </template>
      <template v-else-if="candidateEligibility?.eligible">
        <el-alert
          type="success"
          :closable="false"
          show-icon
          title="资格检查通过，可以创建候选任务"
          description="只创建并校验 DRAFT Workflow；不会自动发布、绑定 Agent 或改动线上路由。"
        />
        <el-descriptions :column="1" border class="candidate-summary">
          <el-descriptions-item label="源追踪">{{ candidateEligibility.traceId }}</el-descriptions-item>
          <el-descriptions-item label="源 Workflow">
            {{ candidateEligibility.sourceWorkflowId }} · {{ candidateEligibility.sourceWorkflowVersion }}
          </el-descriptions-item>
          <el-descriptions-item label="资格证据">
            <div class="hint-list">
              <span v-for="item in candidateEligibility.evidence" :key="item">{{ item }}</span>
            </div>
          </el-descriptions-item>
        </el-descriptions>
        <el-form label-width="112px">
          <el-form-item label="AI 编程工具">
            <el-select v-model="candidateProvider" style="width: 100%">
              <el-option label="Codex" value="CODEX" />
              <el-option label="Cursor" value="CURSOR" />
              <el-option label="Trae" value="TRAE" />
              <el-option label="Claude Code" value="CLAUDE_CODE" />
            </el-select>
          </el-form-item>
        </el-form>
      </template>
      <template v-else-if="candidateEligibility">
        <el-alert
          type="info"
          :closable="false"
          show-icon
          title="当前运行不满足 Workflow 候选规则"
          description="这些限制会阻止把失败或缺少治理证据的偶然轨迹固化成 Workflow。"
        />
        <div class="candidate-blocker-panel">
          <strong>{{ candidateEligibility.blockers.length }} 项规则未满足</strong>
          <ol>
            <li v-for="blocker in candidateEligibility.blockers" :key="blocker">{{ blocker }}</li>
          </ol>
        </div>
      </template>
      <el-empty v-else description="暂时无法读取候选资格，请刷新运行详情后重试" />
      <template #footer>
        <el-button @click="candidateDialogVisible = false">关闭</el-button>
        <el-button
          v-if="candidateEligibility?.eligible"
          type="primary"
          :loading="candidateTaskBusy"
          @click="createCandidateTask"
        >
          创建 AI Coding 任务
        </el-button>
      </template>
    </AppDialog>

    <AppDialog
      v-model="candidateHandoffVisible"
      title="Workflow 候选 AI Coding 交接包"
      width="860px"
    >
      <el-alert
        type="info"
        :closable="false"
        show-icon
        title="将下方交接包复制到所选 AI 编程工具"
        description="重复点击同一轨迹会复用未终态任务；同一任务的 Runtime 草稿也会幂等复用。"
      />
      <el-input
        class="candidate-prompt"
        :model-value="candidateHandoffPrompt"
        type="textarea"
        :rows="20"
        readonly
      />
      <template #footer>
        <el-button @click="candidateHandoffVisible = false">关闭</el-button>
        <el-button
          :disabled="!candidateTaskId"
          @click="openCandidateTaskDetail"
        >
          查看任务闭环
        </el-button>
        <el-button type="primary" @click="copyCandidateHandoff">复制交接包</el-button>
      </template>
    </AppDialog>

    <AppDrawer
      v-model="candidateTaskDrawerVisible"
      title="Workflow 候选任务"
      size="760px"
    >
      <AiCodingTaskDetailPanel
        :detail="candidateTaskDetail"
        :loading="candidateTaskActionBusy"
        :busy="candidateTaskActionBusy"
        @refresh="refreshCandidateTask"
        @answer="answerCandidateTaskQuestion"
        @reissue="reissueCandidateTaskHandoff"
        @verify="verifyCandidateTask"
        @acceptance="finishCandidateTaskAcceptance"
        @cancel="cancelCandidateTask"
      />
    </AppDrawer>
  </WorkbenchPage>
</template>

<script setup lang="ts">
import AppDialog from '@/components/common/AppDialog.vue'
import AppDrawer from '@/components/common/AppDrawer.vue'
import AiCodingTaskDetailPanel from '@/components/ai-coding/AiCodingTaskDetailPanel.vue'
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { CopyDocument, MoreFilled, Refresh, VideoPlay } from '@element-plus/icons-vue'
import PageHeader from '@/components/common/PageHeader.vue'
import WorkbenchPage from '@/components/common/WorkbenchPage.vue'
import RunOpsInvestigationWorkbench from './components/RunOpsInvestigationWorkbench.vue'
import {
  compareRunOpsTrace,
  createTraceWorkflowCandidateTask,
  getRunOpsDetail,
  getTraceWorkflowCandidateEligibility,
  replayRunOpsTrace,
} from '@/api/runops'
import { issueAiCodingHandoff } from '@/api/aiCodingTasks'
import { useAiCodingTask } from '@/composables/useAiCodingTask'
import { formatRuntimeTypeLabel } from '@/utils/registryLabels'
import type { AiCodingExecutorProvider } from '@/types/aiCodingTask'
import type {
  ReplayRequest,
  RunComparison,
  RunDetail,
  RunExecutionPathItem,
  RunSpan,
  RunStatus,
  RunSummary,
  TraceWorkflowCandidateEligibility,
} from '@/types/runops'
import {
  hasPlatformResourcePermission,
  PLATFORM_PERMISSION_RUNOPS_OPERATE,
  PLATFORM_PERMISSION_WORKFLOW_WRITE,
} from '@/auth/platformAccess'
import { platformSessionUser } from '@/auth/platformSession'

type DiagnosticTone = 'success' | 'danger' | 'warning' | 'primary' | 'info'
type RunOpsMoreCommand = 'context' | 'candidate' | 'compare'

interface FailureFocus {
  kindLabel: string
  title: string
  status?: string
  code?: string
  message: string
}

interface DiagnosticState {
  tone: DiagnosticTone
  tagType: DiagnosticTone
  label: string
  title: string
  description: string
}

const route = useRoute()
const router = useRouter()
const traceId = computed(() => route.params.traceId as string)
const loading = ref(false)
const replaying = ref(false)
const replayDialogVisible = ref(false)
const contextDrawerVisible = ref(false)
const candidateDialogVisible = ref(false)
const candidateHandoffVisible = ref(false)
const candidateLoading = ref(false)
const candidateTaskBusy = ref(false)
const candidateTaskActionBusy = ref(false)
const candidateTaskDrawerVisible = ref(false)
const candidateTaskId = ref('')
const candidateEligibility = ref<TraceWorkflowCandidateEligibility | null>(null)
const candidateProvider = ref<AiCodingExecutorProvider>('CODEX')
const candidateHandoffPrompt = ref('')
const candidateTaskKernel = useAiCodingTask()
const candidateTaskDetail = candidateTaskKernel.selectedTaskDetail
const detail = ref<RunDetail | null>(null)
const comparison = ref<RunComparison | null>(null)
const replayForm = ref<ReplayRequest>({})
const replayRoles = ref<string[]>([])
const investigationWorkbenchRef = ref<InstanceType<typeof RunOpsInvestigationWorkbench> | null>(null)
const summary = computed(() => detail.value?.summary)
const canOperateRun = computed(() => hasPlatformResourcePermission(
  platformSessionUser.value?.permissionGrants,
  PLATFORM_PERMISSION_RUNOPS_OPERATE,
  'PROJECT',
  null,
  summary.value?.projectCode,
))
const canWriteSourceWorkflow = computed(() => hasPlatformResourcePermission(
  platformSessionUser.value?.permissionGrants,
  PLATFORM_PERMISSION_WORKFLOW_WRITE,
  'PROJECT',
  null,
  summary.value?.projectCode,
))
const compareSource = computed(() =>
  (route.query.compareWith as string | undefined) || summary.value?.replayOfTraceId,
)
const executionPath = computed<RunExecutionPathItem[]>(() => {
  if (detail.value?.executionPath?.length) return detail.value.executionPath
  return (detail.value?.spans || []).map((span) => ({
    spanId: span.spanId || String(span.id),
    parentSpanId: span.parentSpanId,
    depth: semanticDepth(span),
    spanType: span.spanType,
    label: spanDisplayName(span),
    status: span.status,
    nodeId: span.nodeId,
    toolName: span.toolName,
    runtimeType: span.runtimeType,
    startedAt: span.startedAt,
    endedAt: span.endedAt,
  }))
})

function executionPathLabel(item: RunExecutionPathItem) {
  const labels: Record<string, string> = {
    Supervisor: '智能体调度',
    Plan: '首次规划',
    Replan: '有限重规划',
    'Workflow Tool': '工作流工具',
    'Workflow Node': '工作流节点',
  }
  const label = item.label || item.nodeId || item.toolName || item.spanId
  return label ? labels[label] || label : '-'
}

const failureFocus = computed<FailureFocus | null>(() => {
  const run = summary.value
  if (!run) return null

  const failedSpan = detail.value?.spans
    .filter((span) => isFailureStatus(span.status) || Boolean(span.errorCode || span.errorMessage))
    .sort((left, right) => {
      const depthDelta = semanticDepth(right) - semanticDepth(left)
      if (depthDelta !== 0) return depthDelta
      return timestampValue(left.startedAt) - timestampValue(right.startedAt)
    })[0]
  if (failedSpan) {
    const title = failureSpanTitle(failedSpan)
    const codeSuffix = failedSpan.errorCode ? `，错误码 ${failedSpan.errorCode}` : ''
    return {
      kindLabel: phaseLabel(failedSpan.spanType),
      title,
      status: failedSpan.status || 'FAILED',
      code: failedSpan.errorCode,
      message: compactMessage(
        failedSpan.errorMessage || failedSpan.outputSummary,
        `${phaseLabel(failedSpan.spanType)}“${title}”未成功完成${codeSuffix}。`,
      ),
    }
  }

  const failedTool = detail.value?.toolCalls.find((tool) => !tool.success)
  if (failedTool) {
    return {
      kindLabel: '工具调用',
      title: failedTool.toolName || '未命名工具',
      status: 'FAILED',
      code: failedTool.errorCode,
      message: compactMessage(
        failedTool.resultSummary,
        '工具调用失败，但没有记录更具体的错误信息。',
      ),
    }
  }

  const failedPath = executionPath.value.find((item) => isFailureStatus(item.status))
  if (failedPath) {
    return {
      kindLabel: phaseLabel(failedPath.spanType),
      title: executionPathLabel(failedPath),
      status: failedPath.status,
      message: '执行路径中记录了非成功阶段，请展开对应链路查看输入、输出和错误码。',
    }
  }

  if (isFailureStatus(run.status) || run.errorCode || run.errorMessage) {
    return {
      kindLabel: '根运行',
      title: runObjectLabel(run),
      status: run.status,
      code: run.errorCode,
      message: compactMessage(run.errorMessage, '根运行失败，但没有记录更具体的错误信息。'),
    }
  }

  return null
})

const diagnosticState = computed<DiagnosticState>(() => {
  const run = summary.value
  if (!run) {
    return {
      tone: 'info',
      tagType: 'info',
      label: '正在加载',
      title: '正在读取运行结果',
      description: '运行数据加载完成后将在此给出明确结论。',
    }
  }
  if (run.status === 'RUNNING') {
    return {
      tone: 'primary',
      tagType: 'primary',
      label: '运行中',
      title: '本次运行仍在执行',
      description: '页面会保留当前已产生的执行路径，可刷新查看最新进展。',
    }
  }
  if (run.status === 'SUSPENDED') {
    return {
      tone: 'warning',
      tagType: 'warning',
      label: runStatusLabel(run),
      title: run.suspensionReason === 'APPROVAL' ? '运行正在等待审批' : '运行正在等待用户输入',
      description: '完成当前交互后，运行会从暂停位置继续执行。',
    }
  }
  if (failureFocus.value) {
    return {
      tone: 'danger',
      tagType: 'danger',
      label: run.status === 'COMPLETED' ? '异常完成' : runStatusLabel(run),
      title: run.status === 'COMPLETED'
        ? '流程已结束，但执行链中存在失败'
        : `本次运行${runStatusLabel(run)}`,
      description: failureFocus.value.message,
    }
  }
  if (run.status === 'CANCELLED') {
    return {
      tone: 'info',
      tagType: 'info',
      label: '已取消',
      title: '本次运行已取消',
      description: '运行没有继续执行，已产生的链路仍可用于审计。',
    }
  }
  if (detail.value?.repairHints?.length) {
    return {
      tone: 'warning',
      tagType: 'warning',
      label: '需要检查',
      title: '流程已结束，但仍有诊断提示',
      description: detail.value.repairHints[0],
    }
  }
  return {
    tone: 'success',
    tagType: 'success',
    label: '运行成功',
    title: '本次运行已成功完成',
    description: '未发现失败阶段、失败工具调用或治理拒绝。',
  }
})

const diagnosticIcon = computed(() => {
  if (diagnosticState.value.tone === 'success') return '✓'
  if (diagnosticState.value.tone === 'primary') return '↻'
  if (diagnosticState.value.tone === 'warning') return '…'
  if (diagnosticState.value.tone === 'danger') return '!'
  return 'i'
})

const detailTitle = computed(() => {
  const run = summary.value
  if (!run) return '运行详情'
  return runObjectLabel(run)
})

async function loadDetail() {
  loading.value = true
  candidateTaskKernel.reset()
  candidateTaskId.value = ''
  candidateHandoffPrompt.value = ''
  candidateTaskDrawerVisible.value = false
  candidateHandoffVisible.value = false
  try {
    const { data } = await getRunOpsDetail(traceId.value)
    detail.value = data
    await Promise.all([loadComparison(), loadCandidateEligibility()])
  } catch {
    detail.value = null
    comparison.value = null
    ElMessage.error('加载运行详情失败')
  } finally {
    loading.value = false
  }
}

async function loadCandidateEligibility() {
  candidateLoading.value = true
  candidateEligibility.value = null
  try {
    const { data } = await getTraceWorkflowCandidateEligibility(traceId.value)
    candidateEligibility.value = data
  } catch {
    candidateEligibility.value = null
  } finally {
    candidateLoading.value = false
  }
}

function openCandidateDialog() {
  candidateDialogVisible.value = true
}

function handleMoreCommand(command: RunOpsMoreCommand) {
  if (command === 'context') {
    contextDrawerVisible.value = true
    return
  }
  if (command === 'candidate') {
    openCandidateDialog()
    return
  }
  investigationWorkbenchRef.value?.showCompare()
}

async function createCandidateTask() {
  if (!candidateEligibility.value?.eligible) return
  candidateTaskBusy.value = true
  try {
    const { data } = await createTraceWorkflowCandidateTask(traceId.value, {
      executorProvider: candidateProvider.value,
    })
    const handoff = (await issueAiCodingHandoff(data.task.taskId)).data
    candidateTaskId.value = data.task.taskId
    candidateHandoffPrompt.value = handoff.prompt
    candidateDialogVisible.value = false
    candidateHandoffVisible.value = true
    ElMessage.success(data.created ? '候选任务已创建' : '已复用该轨迹的未完成候选任务')
  } catch {
    ElMessage.error('创建 Workflow 候选任务失败')
  } finally {
    candidateTaskBusy.value = false
  }
}

async function runCandidateTaskAction<T>(
  action: () => Promise<T>,
  errorMessage: string,
): Promise<T | null> {
  candidateTaskActionBusy.value = true
  try {
    return await action()
  } catch (error) {
    ElMessage.error((error as Error).message || errorMessage)
    return null
  } finally {
    candidateTaskActionBusy.value = false
  }
}

async function openCandidateTaskDetail() {
  if (!candidateTaskId.value) return
  candidateHandoffVisible.value = false
  candidateTaskDrawerVisible.value = true
  await refreshCandidateTask(candidateTaskId.value)
}

async function refreshCandidateTask(taskId: string) {
  await runCandidateTaskAction(
    () => candidateTaskKernel.refreshTask(taskId),
    '刷新 Workflow 候选任务失败',
  )
}

async function answerCandidateTaskQuestion(
  taskId: string,
  questionId: string,
  answer: string,
) {
  const result = await runCandidateTaskAction(
    () => candidateTaskKernel.answerQuestion(taskId, questionId, answer),
    '回答写回失败',
  )
  if (result) ElMessage.success('回答已写回候选任务')
}

async function reissueCandidateTaskHandoff(taskId: string) {
  const handoff = await runCandidateTaskAction(
    () => candidateTaskKernel.reissueHandoff(taskId),
    '重新生成交接包失败',
  )
  if (!handoff) return
  candidateHandoffPrompt.value = handoff.prompt
  candidateTaskDrawerVisible.value = false
  candidateHandoffVisible.value = true
}

async function verifyCandidateTask(taskId: string) {
  const result = await runCandidateTaskAction(
    () => candidateTaskKernel.verifyAcceptanceReadiness(taskId),
    '平台验证失败',
  )
  if (!result) return
  ElMessage[result.acceptanceReady ? 'success' : 'warning'](
    result.acceptanceReady
      ? '平台已确认候选草稿、发布校验和成功重放证据'
      : `仍有 ${result.blockers.length} 项未通过平台验证`,
  )
}

async function finishCandidateTaskAcceptance(
  taskId: string,
  passed: boolean,
  message: string,
) {
  const result = await runCandidateTaskAction(
    () => candidateTaskKernel.finishAcceptance(taskId, passed, message),
    '写入验收结果失败',
  )
  if (result) {
    ElMessage[passed ? 'success' : 'warning'](
      passed ? '候选任务验收已通过' : '候选任务已标记为验收不通过',
    )
  }
}

async function cancelCandidateTask(taskId: string) {
  const result = await runCandidateTaskAction(
    () => candidateTaskKernel.cancelTask(taskId),
    '取消候选任务失败',
  )
  if (result) ElMessage.success('候选任务已取消')
}

async function copyCandidateHandoff() {
  if (!candidateHandoffPrompt.value) return
  try {
    await navigator.clipboard.writeText(candidateHandoffPrompt.value)
    ElMessage.success('交接包已复制')
  } catch {
    ElMessage.error('复制失败，请手动复制')
  }
}

async function loadComparison() {
  const sourceTraceId = compareSource.value
  comparison.value = null
  if (!sourceTraceId || sourceTraceId === traceId.value) return
  try {
    const { data } = await compareRunOpsTrace(sourceTraceId, traceId.value)
    comparison.value = data
  } catch {
    ElMessage.error('加载重放差异对比失败')
  }
}

function openReplayDialog() {
  if (!canOperateRun.value) return
  replayForm.value = { userId: summary.value?.userId }
  replayRoles.value = []
  replayDialogVisible.value = true
}

async function replayTrace() {
  if (!detail.value || !canOperateRun.value) return
  replaying.value = true
  try {
    const request: ReplayRequest = { ...replayForm.value, roles: replayRoles.value }
    const { data } = await replayRunOpsTrace(traceId.value, request)
    if (!data?.replayTraceId) {
      ElMessage.warning('重放完成，但未返回新的追踪 ID')
      return
    }
    replayDialogVisible.value = false
    ElMessage.success('已按原发布配置完成重放，正在打开新的运行详情')
    router.push({ path: `/runops/${data.replayTraceId}`, query: { compareWith: traceId.value } })
  } catch {
    ElMessage.error('重放运行失败')
  } finally {
    replaying.value = false
  }
}

function runObjectLabel(run: RunSummary) {
  if (run.runType === 'AGENT') return run.agentName || run.agentKeySlug || run.agentId || '-'
  if (run.runType === 'WORKFLOW') return run.workflowName || run.workflowKeySlug || run.workflowId || '-'
  return snapshotText('toolName') || run.workflowName || run.workflowKeySlug || run.workflowId || 'MCP 工具调用'
}

function runObjectId(run: RunSummary) {
  if (run.runType === 'AGENT') return run.agentKeySlug || run.agentId || '-'
  if (run.runType === 'WORKFLOW') return run.workflowKeySlug || run.workflowId || '-'
  return snapshotText('sourceRef') || run.workflowKeySlug || run.workflowId || '-'
}

function runVersionLabel(run: RunSummary) {
  if (run.runType === 'MCP') {
    const revisionNo = snapshotText('revisionNo')
    if (revisionNo) return `MCP 发布修订 r${revisionNo}`
    if (!run.workflowVersion && run.workflowVersionId == null) return '-'
  }
  const version = run.runType === 'AGENT' ? run.agentConfigVersion : run.workflowVersion
  const versionId = run.runType === 'AGENT' ? run.agentConfigVersionId : run.workflowVersionId
  if (!version && versionId == null) return '-'
  return `${version || '-'}${versionId == null ? '' : ` · #${versionId}`}`
}

function runTypeLabel(runType?: string) {
  const labels: Record<string, string> = {
    AGENT: '智能体',
    WORKFLOW: '工作流',
    MCP: 'MCP 工具调用',
  }
  return runType ? labels[runType] || runType : '-'
}

function entryTypeLabel(entryType?: string) {
  const labels: Record<string, string> = {
    DEBUG: '调试',
    EMBED: '嵌入',
    GATEWAY: '网关',
    EVAL: '评测',
    REPLAY: '重放',
    AUTOMATION: '自动化',
    API: 'API',
    MCP: 'MCP',
  }
  return entryType ? labels[entryType] || entryType : '-'
}

function snapshotText(key: string) {
  const value = detail.value?.snapshot?.snapshot?.[key]
  if (value == null) return ''
  return String(value).trim()
}

function formatDuration(ms?: number | null) {
  const value = Number(ms ?? 0)
  if (!Number.isFinite(value) || value <= 0) return '0 毫秒'
  if (value < 1000) return `${Math.round(value)} 毫秒`
  if (value < 60_000) {
    const seconds = value / 1000
    return `${seconds >= 10 ? seconds.toFixed(1) : seconds.toFixed(2)} 秒`
  }
  const minutes = Math.floor(value / 60_000)
  const seconds = (value % 60_000) / 1000
  return seconds > 0 ? `${minutes} 分 ${seconds.toFixed(1)} 秒` : `${minutes} 分钟`
}

function formatTokenCount(value?: number | null) {
  const count = Number(value ?? 0)
  if (!Number.isFinite(count) || count <= 0) return '0 个'
  return `${Math.round(count).toLocaleString('zh-CN')} 个`
}

function formatDateTime(value?: string | null) {
  if (!value) return '-'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return date.toLocaleString('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hour12: false,
  })
}

function formatCompactDateTime(value?: string | null) {
  if (!value) return '-'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  const parts = new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hour12: false,
  }).formatToParts(date)
  const values = Object.fromEntries(parts.map((part) => [part.type, part.value]))
  return `${values.month}-${values.day} ${values.hour}:${values.minute}:${values.second}`
}

function shortIdentifier(value?: string | null) {
  if (!value) return '-'
  if (value.length <= 24) return value
  return `${value.slice(0, 10)}…${value.slice(-8)}`
}

function compactMessage(value?: string | null, fallback = '未记录详细信息。') {
  const text = value?.replace(/\s+/g, ' ').trim()
  if (!text || text === '[omitted]' || text === '[redacted]') return fallback
  return text.length > 240 ? `${text.slice(0, 237)}…` : text
}

function timestampValue(value?: string | null) {
  if (!value) return Number.MAX_SAFE_INTEGER
  const timestamp = new Date(value).getTime()
  return Number.isNaN(timestamp) ? Number.MAX_SAFE_INTEGER : timestamp
}

function failureSpanTitle(span: RunSpan) {
  const nodeName = typeof span.metadata?.nodeName === 'string' ? span.metadata.nodeName : undefined
  if (span.spanType === 'WORKFLOW_TOOL') {
    const workflowName = typeof span.metadata?.workflowName === 'string' ? span.metadata.workflowName : undefined
    return span.toolName || workflowName || nodeName || span.nodeId || '未命名工作流工具'
  }
  return nodeName || span.nodeId || span.toolName || spanDisplayName(span)
}

function statusLabel(status: RunStatus) {
  const labels: Record<RunStatus, string> = {
    RUNNING: '运行中',
    SUSPENDED: '已暂停',
    COMPLETED: '已完成',
    FAILED: '失败',
    CANCELLED: '已取消',
    TIMED_OUT: '已超时',
  }
  return labels[status]
}

function runStatusLabel(run: RunSummary) {
  if (run.status !== 'SUSPENDED') return statusLabel(run.status)
  if (run.suspensionReason === 'APPROVAL') return '等待审批'
  if (run.suspensionReason === 'USER_INPUT') return '等待用户交互'
  return statusLabel(run.status)
}

function lifecycleStatusLabel(run: RunSummary) {
  return run.status === 'COMPLETED' ? '流程已结束' : runStatusLabel(run)
}

function isFailureStatus(status?: string) {
  return status === 'FAILED' || status === 'TIMED_OUT' || status === 'TIMEOUT'
}

function statusTagType(status?: string) {
  if (status === 'COMPLETED' || status === 'SUCCESS') return 'success'
  if (status === 'RUNNING') return 'primary'
  if (status === 'SUSPENDED' || status === 'WAITING' || status === 'WAITING_APPROVAL' || status === 'WAITING_USER') return 'warning'
  if (status === 'CANCELLED') return 'info'
  return 'danger'
}

function phaseLabel(spanType?: string) {
  const labels: Record<string, string> = {
    SUPERVISOR: '调度器',
    PLAN: '规划',
    REPLAN: '重规划',
    WORKFLOW_TOOL: '工作流工具',
  }
  return spanType ? labels[spanType] || '工作流节点' : '工作流节点'
}

function semanticDepth(span: RunSpan) {
  if (span.spanType === 'SUPERVISOR') return 0
  if (span.spanType === 'PLAN' || span.spanType === 'REPLAN') return 1
  if (span.spanType === 'WORKFLOW_TOOL') return 2
  return summary.value?.runType === 'AGENT' ? 3 : 1
}

function spanDisplayName(span: RunSpan) {
  const nodeName = span.metadata?.nodeName
  const workflowName = span.metadata?.workflowName
  const spanTypeLabels: Record<string, string> = {
    SUPERVISOR: '智能体调度',
    PLAN: '规划',
    REPLAN: '重规划',
    WORKFLOW_TOOL: '工作流工具',
  }
  const spanTypeLabel = span.spanType ? spanTypeLabels[span.spanType] || span.spanType : undefined
  const base = span.spanType === 'WORKFLOW_TOOL'
    ? span.toolName || (typeof workflowName === 'string' ? workflowName : undefined) || span.nodeId || spanTypeLabel || span.spanId || '-'
    : span.nodeId || span.toolName || spanTypeLabel || span.spanId || '-'
  return nodeName ? `${base} · ${nodeName}` : base
}

async function copyIssueSummary() {
  const run = summary.value
  if (!run) return
  const text = [
    `追踪 ID: ${run.traceId}`,
    `运行类型: ${runTypeLabel(run.runType)} (${run.runType})`,
    `运行对象: ${runObjectLabel(run)} (${runObjectId(run)})`,
    `发布版本: ${runVersionLabel(run)}`,
    `运行入口: ${entryTypeLabel(run.entryType)} (${run.entryType})`,
    `运行状态: ${runStatusLabel(run)} (${run.status})`,
    `诊断结论: ${diagnosticState.value.label}`,
    `错误: ${run.errorCode || '-'} ${run.errorMessage || ''}`.trim(),
    `耗时: ${formatDuration(run.latencyMs)}`,
    `规划/重规划: ${run.planCount ?? 0}/${run.replanCount ?? 0}`,
    `工作流/工具调用: ${run.workflowCallCount ?? 0}/${run.toolCallCount ?? 0}`,
    `修复建议: ${(detail.value?.repairHints || []).join(' | ') || '-'}`,
  ].join('\n')
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success('运行摘要已复制')
  } catch {
    ElMessage.error('复制失败')
  }
}

async function copyIdentifier(label: string, value?: string | null) {
  if (!value) return
  try {
    await navigator.clipboard.writeText(value)
    ElMessage.success(`${label}已复制`)
  } catch {
    ElMessage.error(`${label}复制失败`)
  }
}

function focusFailure() {
  if (!failureFocus.value) return
  nextTick(() => {
    investigationWorkbenchRef.value?.focusFailure()
  })
}

watch(traceId, () => {
  loadDetail()
})
onMounted(loadDetail)
</script>

<style scoped lang="scss">
.runops-detail {
  --layout-page-header-art-opacity: 0.18;
  min-width: 0;
  overflow-x: hidden;
}

.runops-detail :deep(.app-page-header) {
  border: 1px solid var(--border-color);
  box-shadow: none;
}

.replay-alert {
  margin-bottom: 16px;
}

.candidate-summary,
.candidate-prompt {
  margin-top: 16px;
}

.hint-list {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

/* RunOps investigation workbench V2 */
.runops-detail {
  align-items: center;
  gap: 8px;
}

.runops-detail :deep(.app-page-header),
.runops-detail > .run-overview-panel,
.runops-detail :deep(.investigation-workbench) {
  width: min(100%, 1840px);
  margin-inline: auto;
}

.runops-detail :deep(.app-page-header__actions) {
  flex-wrap: nowrap;
}

.runops-detail :deep(.app-page-header) {
  border-color: color-mix(in srgb, var(--border-readable) 48%, transparent);
  border-radius: 12px;
  box-shadow:
    0 16px 36px -34px rgb(15 23 42 / 0.46),
    inset 0 1px 0 color-mix(in srgb, var(--text-primary) 7%, transparent);
}

.runops-detail :deep(.app-page-header__title) {
  font-size: clamp(19px, 1.55vw, 23px);
  letter-spacing: -0.015em;
}

.runops-detail :deep(.app-page-header__action-dock) {
  gap: 4px;
  padding: 3px;
  border-radius: 11px;
  box-shadow:
    0 12px 24px -22px rgb(15 23 42 / 0.44),
    inset 0 1px 0 rgb(255 255 255 / 0.9);
}

.runops-detail :deep(.app-page-header__actions .el-button) {
  min-height: 34px;
  border-radius: 8px;
  font-size: 12px;
}

.runops-detail :deep(.app-page-header__actions .el-button.is-circle) {
  width: 34px;
}

.run-overview-panel {
  --run-tone: var(--status-info);
  min-width: 0;
  overflow: hidden;
  border: 1px solid color-mix(in srgb, var(--border-readable) 48%, transparent);
  border-left: 2px solid var(--run-tone);
  border-radius: 9px;
  background: color-mix(in srgb, var(--surface-solid-overlay) 78%, transparent);
  box-shadow:
    0 14px 28px -30px color-mix(in srgb, var(--run-tone) 42%, transparent),
    inset 0 1px 0 color-mix(in srgb, var(--text-primary) 6%, transparent);
  -webkit-backdrop-filter: blur(16px) saturate(1.02);
  backdrop-filter: blur(16px) saturate(1.02);

  &.is-success { --run-tone: var(--status-success); }
  &.is-danger { --run-tone: var(--status-danger); }
  &.is-warning { --run-tone: var(--status-warning); }
  &.is-primary { --run-tone: var(--brand-primary); }
}

.run-conclusion-row {
  display: grid;
  min-width: 0;
  min-height: 54px;
  grid-template-columns: 28px minmax(0, 1fr) auto;
  align-items: center;
  gap: 9px;
  padding: 6px 12px;
  background: color-mix(in srgb, var(--run-tone) 3%, transparent);
}

.run-conclusion-icon {
  display: grid;
  width: 24px;
  height: 24px;
  place-items: center;
  border-radius: 50%;
  background: var(--run-tone);
  color: #fff;
  font-size: 12px;
  font-weight: 800;
}

.run-conclusion-copy {
  min-width: 0;

  p {
    margin: 3px 0 0;
    overflow: hidden;
    color: var(--text-secondary);
    font-size: 11px;
    line-height: 1.4;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
}

.run-conclusion-heading {
  min-width: 0;
  gap: 7px;

  strong {
    overflow: hidden;
    color: var(--text-primary);
    font-size: 13px;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  :deep(.el-tag) {
    flex: 0 0 auto;
  }

  :deep(.el-button) {
    flex: 0 0 auto;
    padding: 0;
  }
}

.run-overview-panel .run-vitals {
  display: grid;
  min-width: 306px;
  grid-template-columns: repeat(3, minmax(90px, 1fr));
  gap: 0;
  margin: 0;
  padding: 4px;
  border: 1px solid color-mix(in srgb, var(--border-readable) 42%, transparent);
  border-radius: 8px;
  background: color-mix(in srgb, var(--surface-solid-control) 66%, transparent);

  > div {
    min-width: 0;
    padding: 0 10px;
    border-left: 1px solid var(--border-divider);
  }

  dt,
  dd {
    margin: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  dt {
    color: var(--text-muted);
    font-size: 9px;
  }

  dd {
    margin-top: 2px;
    color: var(--text-primary);
    font-size: 12px;
    font-weight: 750;
  }
}

.run-context-strip {
  display: flex;
  min-width: 0;
  min-height: 29px;
  align-items: center;
  gap: 12px;
  padding: 3px 10px;
  border-top: 1px solid var(--border-divider);
  color: var(--text-muted);
  font-size: 10px;

  > span {
    min-width: 0;
    white-space: nowrap;
  }

  strong,
  code {
    margin-left: 3px;
    color: var(--text-primary);
    font-size: 10px;
    font-weight: 700;
  }

  .context-entry {
    margin-left: auto;
    padding: 2px 7px;
    border-radius: 6px;
    background: color-mix(in srgb, var(--surface-solid-control) 58%, transparent);
    color: var(--text-secondary);
  }
}

.trace-context {
  display: flex;
  min-width: 0;
  align-items: center;

  code {
    display: block;
    max-width: 240px;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  :deep(.el-button) {
    min-height: auto;
    padding: 0 4px;
  }
}

:global(.menu-state) {
  margin-left: 16px;
  color: var(--text-muted);
  font-size: 11px;
}

.context-drawer-section {
  & + & {
    margin-top: 20px;
  }

  h3 {
    margin: 0 0 10px;
    color: var(--text-primary);
    font-size: 14px;
  }

  code {
    color: var(--text-secondary);
    font-size: 11px;
    overflow-wrap: anywhere;
  }
}

.copyable-value {
  display: flex;
  min-width: 0;
  align-items: center;
  justify-content: space-between;
  gap: 8px;

  code {
    min-width: 0;
  }
}

.replay-version-box {
  margin: 12px 0;
  padding: 10px 12px;
  border: 1px solid var(--border-color);
  border-radius: 8px;
  background: var(--fill-color-light);
  color: var(--text-secondary);
  font-size: 12px;

  strong {
    color: var(--text-primary);
  }
}

.replay-advanced {
  overflow: hidden;
  border: 1px solid var(--border-color);
  border-radius: 8px;

  summary {
    padding: 11px 12px;
    color: var(--text-primary);
    cursor: pointer;
    font-size: 13px;
    font-weight: 700;

    small {
      margin-left: 8px;
      color: var(--text-muted);
      font-size: 11px;
      font-weight: 500;
    }
  }

  :deep(.el-form) {
    padding: 14px 14px 2px;
    border-top: 1px solid var(--border-divider);
  }
}

.candidate-blocker-panel {
  margin-top: 14px;
  padding: 14px;
  border: 1px solid var(--border-color);
  border-radius: 9px;
  background: var(--fill-color-light);

  > strong {
    color: var(--text-primary);
    font-size: 13px;
  }

  ol {
    display: grid;
    gap: 7px;
    margin: 10px 0 0;
    padding-left: 20px;
    color: var(--text-secondary);
    font-size: 12px;
    line-height: 1.5;
  }
}

@media (max-width: 1180px) {
  .run-overview-panel .run-vitals {
    min-width: 280px;
  }

  .run-context-strip > span:nth-child(2) {
    display: none;
  }
}

@media (max-width: 900px) {
  .runops-detail :deep(.app-page-header__actions) {
    flex-wrap: wrap;
  }

  .run-conclusion-row {
    grid-template-columns: 30px minmax(0, 1fr);
  }

  .run-overview-panel .run-vitals {
    min-width: 0;
    grid-column: 1 / -1;
    padding-left: 40px;
  }

  .run-context-strip {
    flex-wrap: wrap;
  }

  .run-context-strip .context-entry {
    margin-left: 0;
  }
}

@media (max-width: 640px) {
  .run-conclusion-copy p {
    white-space: normal;
  }

  .run-conclusion-heading {
    align-items: flex-start;
    flex-wrap: wrap;
  }

  .run-overview-panel .run-vitals {
    grid-template-columns: repeat(3, minmax(0, 1fr));
    padding-left: 0;

    > div {
      padding: 0 7px;
    }
  }

  .run-context-strip > span:nth-child(1),
  .run-context-strip > span:nth-child(3) {
    display: none;
  }

  .trace-context {
    flex: 1 1 auto;
  }
}
</style>
