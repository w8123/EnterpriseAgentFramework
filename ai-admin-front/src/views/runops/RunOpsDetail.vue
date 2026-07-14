<template>
  <WorkbenchPage class="runops-detail">
    <PageHeader
      variant="entity"
      domain="governance"
      :title="detailTitle"
      show-back
      @back="router.push('/runops')"
    >
      <template #tags>
        <el-tag v-if="summary" size="small" effect="plain" :type="summary.runType === 'AGENT' ? 'success' : 'primary'">
          {{ summary.runType }}
        </el-tag>
        <el-tag v-if="summary" :type="statusTagType(summary.status)">
          {{ statusLabel(summary.status) }}
        </el-tag>
      </template>
      <template #meta>
        <HeaderMetaList :items="headerMetaItems" />
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
          v-if="summary?.runType === 'WORKFLOW' && summary.workflowId"
          @click="router.push(`/workflows/${summary.workflowId}/studio`)"
        >
          打开 Workflow Studio
        </el-button>
        <el-button
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
      <section class="summary-grid">
        <div v-for="item in summaryItems" :key="item.label" class="summary-card">
          <span>{{ item.label }}</span>
          <strong>{{ item.value }}</strong>
          <small>{{ item.hint }}</small>
        </div>
      </section>

      <el-alert
        v-if="summary.errorCode || summary.errorMessage"
        class="run-alert"
        type="error"
        :closable="false"
        show-icon
        :title="summary.errorCode || '运行失败'"
        :description="summary.errorMessage || '未记录错误详情'"
      />

      <el-alert
        v-if="detail.repairHints?.length"
        class="run-alert"
        type="warning"
        :closable="false"
        show-icon
      >
        <template #title>
          <div class="hint-list">
            <span v-for="hint in detail.repairHints" :key="hint">{{ hint }}</span>
          </div>
        </template>
      </el-alert>

      <el-card v-if="comparison" class="compare-card" shadow="never">
        <template #header>
          <div class="card-header">
            <span>原运行与重放对比</span>
            <el-tag size="small" effect="plain">
              {{ comparison.baseline.traceId }} → {{ comparison.candidate.traceId }}
            </el-tag>
          </div>
        </template>
        <section class="compare-summary">
          <div v-for="item in changedSummaryDiffs" :key="item.field" class="diff-chip">
            <span>{{ item.field }}</span>
            <strong>{{ displayValue(item.baseline) }} → {{ displayValue(item.candidate) }}</strong>
          </div>
          <el-empty v-if="!changedSummaryDiffs.length" description="摘要指标一致" />
        </section>
        <el-tabs>
          <el-tab-pane label="Span 差异">
            <el-table :data="changedSpanDiffs" stripe>
              <el-table-column type="expand">
                <template #default="{ row }">
                  <div class="diff-detail-grid">
                    <div>
                      <div class="panel-title">原运行</div>
                      <pre>{{ pretty(row.baseline) }}</pre>
                    </div>
                    <div>
                      <div class="panel-title">重放</div>
                      <pre>{{ pretty(row.candidate) }}</pre>
                    </div>
                  </div>
                </template>
              </el-table-column>
              <el-table-column prop="key" label="Span" min-width="180" show-overflow-tooltip />
              <el-table-column label="原运行" min-width="240" show-overflow-tooltip>
                <template #default="{ row }">{{ spanDigest(row.baseline) }}</template>
              </el-table-column>
              <el-table-column label="重放" min-width="240" show-overflow-tooltip>
                <template #default="{ row }">{{ spanDigest(row.candidate) }}</template>
              </el-table-column>
            </el-table>
            <el-empty v-if="!changedSpanDiffs.length" description="Span 链路一致" />
          </el-tab-pane>
          <el-tab-pane label="Tool 差异">
            <el-table :data="changedToolDiffs" stripe>
              <el-table-column type="expand">
                <template #default="{ row }">
                  <div class="diff-detail-grid">
                    <div>
                      <div class="panel-title">原运行</div>
                      <pre>{{ pretty(row.baseline) }}</pre>
                    </div>
                    <div>
                      <div class="panel-title">重放</div>
                      <pre>{{ pretty(row.candidate) }}</pre>
                    </div>
                  </div>
                </template>
              </el-table-column>
              <el-table-column prop="key" label="Tool" min-width="180" show-overflow-tooltip />
              <el-table-column label="原运行" min-width="240" show-overflow-tooltip>
                <template #default="{ row }">{{ toolDigest(row.baseline) }}</template>
              </el-table-column>
              <el-table-column label="重放" min-width="240" show-overflow-tooltip>
                <template #default="{ row }">{{ toolDigest(row.candidate) }}</template>
              </el-table-column>
            </el-table>
            <el-empty v-if="!changedToolDiffs.length" description="Tool 调用一致" />
          </el-tab-pane>
          <el-tab-pane label="治理差异">
            <el-table :data="changedGuardDiffs" stripe>
              <el-table-column type="expand">
                <template #default="{ row }">
                  <div class="diff-detail-grid">
                    <div>
                      <div class="panel-title">原运行</div>
                      <pre>{{ pretty(row.baseline) }}</pre>
                    </div>
                    <div>
                      <div class="panel-title">重放</div>
                      <pre>{{ pretty(row.candidate) }}</pre>
                    </div>
                  </div>
                </template>
              </el-table-column>
              <el-table-column prop="key" label="策略对象" min-width="220" show-overflow-tooltip />
              <el-table-column label="原运行" min-width="220" show-overflow-tooltip>
                <template #default="{ row }">{{ guardDigest(row.baseline) }}</template>
              </el-table-column>
              <el-table-column label="重放" min-width="220" show-overflow-tooltip>
                <template #default="{ row }">{{ guardDigest(row.candidate) }}</template>
              </el-table-column>
            </el-table>
            <el-empty v-if="!changedGuardDiffs.length" description="治理决策一致" />
          </el-tab-pane>
        </el-tabs>
      </el-card>

      <el-tabs>
        <el-tab-pane v-if="summary.runType === 'AGENT'" label="Supervisor 决策">
          <section class="supervisor-metrics">
            <div v-for="item in supervisorMetrics" :key="item.label" class="supervisor-metric">
              <span>{{ item.label }}</span>
              <strong>{{ item.value }}</strong>
              <small>{{ item.hint }}</small>
            </div>
          </section>
          <el-table :data="supervisorEvents" row-key="id" class="supervisor-event-table">
            <el-table-column label="阶段" width="136">
              <template #default="{ row }">
                <el-tag :type="phaseTagType(row.spanType)">{{ phaseLabel(row.spanType) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="计划 / 选择" min-width="230">
              <template #default="{ row }">{{ supervisorEventLabel(row) }}</template>
            </el-table-column>
            <el-table-column label="结果" min-width="260" show-overflow-tooltip>
              <template #default="{ row }">{{ row.outputSummary || '-' }}</template>
            </el-table-column>
            <el-table-column label="状态" width="112">
              <template #default="{ row }"><el-tag :type="statusTagType(row.status)">{{ row.status || '-' }}</el-tag></template>
            </el-table-column>
            <el-table-column label="耗时" width="100">
              <template #default="{ row }">{{ row.latencyMs ?? 0 }} ms</template>
            </el-table-column>
          </el-table>
          <el-empty v-if="!supervisorEvents.length" description="该运行尚无结构化 Supervisor 决策事件" />
        </el-tab-pane>

        <el-tab-pane label="执行路径">
          <div class="execution-model" :class="{ 'workflow-only': summary.runType === 'WORKFLOW' }">
            <template v-if="summary.runType === 'AGENT'">
              <div><strong>Supervisor</strong><small>理解与调度</small></div>
              <span>→</span>
              <div><strong>Plan / Replan</strong><small>规划与有限重规划</small></div>
              <span>→</span>
              <div><strong>Workflow Tool</strong><small>选择并调用 Workflow</small></div>
              <span>→</span>
            </template>
            <div><strong>Workflow Node</strong><small>执行 GraphSpec 节点</small></div>
          </div>

          <el-table :data="executionPath" row-key="spanId" stripe>
            <el-table-column label="层级 / 阶段" min-width="260">
              <template #default="{ row }">
                <div class="execution-stage" :style="{ paddingLeft: `${Math.min(row.depth || 0, 4) * 24}px` }">
                  <span v-if="row.depth" class="path-prefix">↳</span>
                  <el-tag size="small" effect="plain" :type="phaseTagType(row.spanType)">
                    {{ phaseLabel(row.spanType) }}
                  </el-tag>
                  <strong>{{ row.label || row.nodeId || row.toolName || row.spanId || '-' }}</strong>
                </div>
              </template>
            </el-table-column>
            <el-table-column prop="runtimeType" label="Runtime" width="150" show-overflow-tooltip />
            <el-table-column label="状态" width="112">
              <template #default="{ row }"><el-tag size="small" :type="statusTagType(row.status)">{{ row.status || '-' }}</el-tag></template>
            </el-table-column>
            <el-table-column prop="startedAt" label="开始时间" width="180" />
            <el-table-column prop="endedAt" label="结束时间" width="180" />
          </el-table>
          <el-empty v-if="!executionPath.length" description="暂无结构化执行路径" />
        </el-tab-pane>

        <el-tab-pane label="Span 明细">
          <el-timeline class="span-timeline">
            <el-timeline-item
              v-for="span in detail.spans"
              :key="span.id"
              :timestamp="span.startedAt"
              :type="statusTagType(span.status)"
              placement="top"
            >
              <div class="span-item">
                <div class="span-head">
                  <div>
                    <strong>{{ spanDisplayName(span) }}</strong>
                    <span>{{ phaseLabel(span.spanType) }} · {{ span.runtimeType || '-' }}</span>
                  </div>
                  <div class="span-tags">
                    <el-tag size="small" :type="statusTagType(span.status)">{{ span.status || '-' }}</el-tag>
                    <el-tag size="small" effect="plain">{{ span.latencyMs ?? 0 }} ms</el-tag>
                  </div>
                </div>
                <div v-if="span.errorMessage" class="error-text">{{ span.errorCode || 'SPAN_FAILED' }}：{{ span.errorMessage }}</div>
                <div class="io-grid">
                  <pre>{{ span.inputSummary || '-' }}</pre>
                  <pre>{{ span.outputSummary || '-' }}</pre>
                </div>
              </div>
            </el-timeline-item>
          </el-timeline>
          <el-empty v-if="!detail.spans.length" description="暂无结构化 Span" />
        </el-tab-pane>

        <el-tab-pane label="Tool 调用">
          <el-table :data="detail.toolCalls" stripe>
            <el-table-column prop="success" label="状态" width="100">
              <template #default="{ row }">
                <el-tag size="small" :type="row.success ? 'success' : 'danger'">{{ row.success ? 'SUCCESS' : 'FAILED' }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="toolName" label="Tool" min-width="220" show-overflow-tooltip />
            <el-table-column prop="elapsedMs" label="耗时" width="100">
              <template #default="{ row }">{{ row.elapsedMs ?? 0 }} ms</template>
            </el-table-column>
            <el-table-column prop="tokenCost" label="Token" width="100" />
            <el-table-column prop="errorCode" label="错误" width="180" show-overflow-tooltip />
            <el-table-column prop="createdAt" label="时间" width="180" />
          </el-table>
          <el-empty v-if="!detail.toolCalls.length" description="该运行未调用 Tool" />
        </el-tab-pane>

        <el-tab-pane label="治理决策">
          <el-table :data="detail.guardDecisions" stripe>
            <el-table-column prop="decision" label="决策" width="100">
              <template #default="{ row }">
                <el-tag size="small" :type="row.decision === 'DENY' ? 'danger' : 'success'">{{ row.decision }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="decisionType" label="类型" width="140" />
            <el-table-column prop="targetKind" label="对象类型" width="120" />
            <el-table-column prop="targetName" label="对象" min-width="180" show-overflow-tooltip />
            <el-table-column prop="reason" label="原因" min-width="240" show-overflow-tooltip />
            <el-table-column prop="createdAt" label="时间" width="180" />
          </el-table>
          <el-empty v-if="!detail.guardDecisions.length" description="暂无治理决策记录" />
        </el-tab-pane>

        <el-tab-pane label="发布配置与 Metadata">
          <section class="snapshot-grid">
            <div class="snapshot-panel">
              <div class="panel-title">根运行 Metadata</div>
              <pre>{{ pretty(summary.metadata) }}</pre>
            </div>
            <div class="snapshot-panel">
              <div class="panel-title">已发布 Runtime 配置</div>
              <pre>{{ pretty(detail.snapshot?.runtimeConfig) }}</pre>
            </div>
            <div class="snapshot-panel">
              <div class="panel-title">已发布 GraphSpec</div>
              <pre>{{ pretty(detail.snapshot?.graphSpec) }}</pre>
            </div>
            <div class="snapshot-panel">
              <div class="panel-title">配置版本身份</div>
              <pre>{{ pretty(versionIdentity) }}</pre>
            </div>
          </section>
        </el-tab-pane>
      </el-tabs>
    </template>

    <AppDialog v-model="replayDialogVisible" title="重放运行" width="560px">
      <el-alert
        class="replay-alert"
        type="info"
        :closable="false"
        show-icon
        title="重放使用原运行的已发布配置版本"
        description="重放不会切换到当前活动配置；新 Trace 将自动与原运行建立对比关系。"
      />
      <el-form label-width="110px">
        <el-form-item label="覆盖输入">
          <el-input
            v-model="replayForm.messageOverride"
            type="textarea"
            :rows="4"
            placeholder="留空则复用原 Trace 输入"
          />
        </el-form-item>
        <el-form-item label="User ID">
          <el-input v-model="replayForm.userId" clearable placeholder="留空则复用原运行用户" />
        </el-form-item>
        <el-form-item label="Session ID">
          <el-input v-model="replayForm.sessionId" clearable placeholder="留空则自动生成 replay session" />
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
      <template #footer>
        <el-button @click="replayDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="replaying" @click="replayTrace">开始重放</el-button>
      </template>
    </AppDialog>
  </WorkbenchPage>
</template>

<script setup lang="ts">
import AppDialog from '@/components/common/AppDialog.vue'
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Refresh, VideoPlay } from '@element-plus/icons-vue'
import HeaderMetaList from '@/components/common/HeaderMetaList.vue'
import PageHeader from '@/components/common/PageHeader.vue'
import WorkbenchPage from '@/components/common/WorkbenchPage.vue'
import { compareRunOpsTrace, getRunOpsDetail, replayRunOpsTrace } from '@/api/runops'
import type {
  ReplayRequest,
  RunComparison,
  RunDetail,
  RunExecutionPathItem,
  RunGuardDecision,
  RunSpan,
  RunStatus,
  RunSummary,
  RunToolCall,
} from '@/types/runops'

const route = useRoute()
const router = useRouter()
const traceId = computed(() => route.params.traceId as string)
const loading = ref(false)
const replaying = ref(false)
const replayDialogVisible = ref(false)
const detail = ref<RunDetail | null>(null)
const comparison = ref<RunComparison | null>(null)
const replayForm = ref<ReplayRequest>({})
const replayRoles = ref<string[]>([])
const summary = computed(() => detail.value?.summary)
const compareSource = computed(() => route.query.compareWith as string | undefined)
const supervisorEvents = computed(() => (detail.value?.spans || []).filter((span) =>
  ['PLAN', 'REPLAN', 'WORKFLOW_TOOL'].includes(span.spanType || ''),
))

const supervisorMetrics = computed(() => {
  const run = summary.value
  if (!run) return []
  return [
    { label: '配置版本', value: run.agentConfigVersion || '-', hint: run.agentConfigVersionId == null ? '未记录版本 ID' : `versionId ${run.agentConfigVersionId}` },
    { label: '计划次数', value: run.planCount ?? 0, hint: 'Supervisor 首次规划' },
    { label: '重规划', value: run.replanCount ?? 0, hint: '有限重规划次数' },
    { label: 'Workflow 调用', value: run.workflowCallCount ?? 0, hint: 'Workflow-as-Tool' },
  ]
})

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

const changedSummaryDiffs = computed(() => comparison.value?.summaryDiffs.filter((item) => item.changed) ?? [])
const changedSpanDiffs = computed(() => comparison.value?.spanDiffs.filter((item) => item.changed) ?? [])
const changedToolDiffs = computed(() => comparison.value?.toolDiffs.filter((item) => item.changed) ?? [])
const changedGuardDiffs = computed(() => comparison.value?.guardDiffs.filter((item) => item.changed) ?? [])

const summaryItems = computed(() => {
  const run = summary.value
  if (!run) return []
  return [
    { label: run.runType === 'AGENT' ? 'Agent' : 'Workflow', value: runObjectLabel(run), hint: runObjectId(run) },
    { label: '发布版本', value: runVersionLabel(run), hint: run.runtimeType || '未记录 Runtime' },
    { label: '入口', value: entryTypeLabel(run.entryType), hint: `${run.projectCode || '-'} · ${run.userId || '匿名用户'}` },
    { label: '规划 / 重规划', value: `${run.planCount ?? 0} / ${run.replanCount ?? 0}`, hint: 'Supervisor 决策' },
    { label: 'Workflow / Tool', value: `${run.workflowCallCount ?? 0} / ${run.toolCallCount ?? 0}`, hint: '运行内调用' },
    { label: '治理 / 审批', value: `${run.guardDenyCount ?? 0} / ${run.approvalCount ?? 0}`, hint: '拒绝与审批事件' },
    { label: '耗时', value: `${run.latencyMs ?? 0} ms`, hint: `${run.tokenCost ?? 0} token` },
    { label: '会话', value: run.sessionId || '-', hint: run.replayOfTraceId ? `重放自 ${run.replayOfTraceId}` : '原始运行' },
  ]
})

const detailTitle = computed(() => {
  const run = summary.value
  if (!run) return '运行详情'
  return `${run.runType === 'AGENT' ? 'Agent' : 'Workflow'} 运行 · ${runObjectLabel(run)}`
})

const headerMetaItems = computed(() => [
  { key: 'trace', label: 'Trace', value: traceId.value },
  { key: 'project', label: '项目', value: summary.value?.projectCode || '-' },
  { key: 'entry', label: '入口', value: summary.value ? entryTypeLabel(summary.value.entryType) : '-' },
])

const versionIdentity = computed(() => {
  const run = summary.value
  if (!run) return {}
  return {
    runType: run.runType,
    agentId: run.agentId,
    agentKeySlug: run.agentKeySlug,
    agentConfigVersionId: run.agentConfigVersionId,
    agentConfigVersion: run.agentConfigVersion,
    workflowId: run.workflowId,
    workflowKeySlug: run.workflowKeySlug,
    workflowVersionId: run.workflowVersionId,
    workflowVersion: run.workflowVersion,
  }
})

async function loadDetail() {
  loading.value = true
  try {
    const { data } = await getRunOpsDetail(traceId.value)
    detail.value = data
    await loadComparison()
  } catch {
    detail.value = null
    comparison.value = null
    ElMessage.error('加载运行详情失败')
  } finally {
    loading.value = false
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
  replayForm.value = { userId: summary.value?.userId }
  replayRoles.value = []
  replayDialogVisible.value = true
}

async function replayTrace() {
  if (!detail.value) return
  replaying.value = true
  try {
    const request: ReplayRequest = { ...replayForm.value, roles: replayRoles.value }
    const { data } = await replayRunOpsTrace(traceId.value, request)
    if (!data?.replayTraceId) {
      ElMessage.warning('重放完成，但未返回新 traceId')
      return
    }
    replayDialogVisible.value = false
    ElMessage.success('已按原发布配置完成重放，正在打开新 Trace')
    router.push({ path: `/runops/${data.replayTraceId}`, query: { compareWith: traceId.value } })
  } catch {
    ElMessage.error('重放运行失败')
  } finally {
    replaying.value = false
  }
}

function runObjectLabel(run: RunSummary) {
  return run.runType === 'AGENT'
    ? run.agentName || run.agentKeySlug || run.agentId || '-'
    : run.workflowName || run.workflowKeySlug || run.workflowId || '-'
}

function runObjectId(run: RunSummary) {
  return run.runType === 'AGENT'
    ? run.agentKeySlug || run.agentId || '-'
    : run.workflowKeySlug || run.workflowId || '-'
}

function runVersionLabel(run: RunSummary) {
  const version = run.runType === 'AGENT' ? run.agentConfigVersion : run.workflowVersion
  const versionId = run.runType === 'AGENT' ? run.agentConfigVersionId : run.workflowVersionId
  if (!version && versionId == null) return '-'
  return `${version || '-'}${versionId == null ? '' : ` · #${versionId}`}`
}

function entryTypeLabel(entryType?: string) {
  const labels: Record<string, string> = {
    DEBUG: '调试',
    EMBED: '嵌入',
    GATEWAY: '网关',
    EVAL: '评测',
    REPLAY: '重放',
    API: 'API',
  }
  return entryType ? labels[entryType] || entryType : '-'
}

function statusLabel(status: RunStatus) {
  const labels: Record<RunStatus, string> = {
    RUNNING: '运行中',
    SUCCESS: '成功',
    FAILED: '失败',
    WAITING_APPROVAL: '等待审批',
    CANCELLED: '已取消',
    TIMEOUT: '超时',
  }
  return labels[status]
}

function statusTagType(status?: string) {
  if (status === 'SUCCESS') return 'success'
  if (status === 'RUNNING') return 'primary'
  if (status === 'WAITING' || status === 'WAITING_APPROVAL') return 'warning'
  if (status === 'CANCELLED') return 'info'
  return 'danger'
}

function phaseLabel(spanType?: string) {
  const labels: Record<string, string> = {
    SUPERVISOR: 'Supervisor',
    PLAN: 'Plan',
    REPLAN: 'Replan',
    WORKFLOW_TOOL: 'Workflow Tool',
  }
  return spanType ? labels[spanType] || 'Workflow Node' : 'Workflow Node'
}

function phaseTagType(spanType?: string) {
  if (spanType === 'SUPERVISOR') return 'primary'
  if (spanType === 'PLAN') return 'primary'
  if (spanType === 'REPLAN') return 'warning'
  if (spanType === 'WORKFLOW_TOOL') return 'success'
  return 'info'
}

function semanticDepth(span: RunSpan) {
  if (span.spanType === 'SUPERVISOR') return 0
  if (span.spanType === 'PLAN' || span.spanType === 'REPLAN') return 1
  if (span.spanType === 'WORKFLOW_TOOL') return 2
  return summary.value?.runType === 'AGENT' ? 3 : 1
}

function spanDisplayName(span: RunSpan) {
  const nodeName = span.metadata?.nodeName
  const base = span.nodeId || span.toolName || span.spanType || span.spanId || '-'
  return nodeName ? `${base} · ${nodeName}` : base
}

function supervisorEventLabel(span: RunSpan) {
  if (span.spanType === 'WORKFLOW_TOOL') {
    const version = span.metadata?.workflowVersion
    return `${span.toolName || span.nodeId || 'Workflow'}${version ? ` · ${version}` : ''}`
  }
  const planNo = span.metadata?.planNo
  return planNo ? `第 ${planNo} 次规划` : span.nodeId || phaseLabel(span.spanType)
}

function displayValue(value: unknown) {
  if (value == null || value === '') return '-'
  if (typeof value === 'boolean') return value ? 'true' : 'false'
  return String(value)
}

function spanDigest(span?: RunSpan) {
  if (!span) return '缺失'
  return `${span.status || '-'} · ${span.latencyMs ?? 0} ms · ${span.errorCode || span.outputSummary || '-'}`
}

function toolDigest(tool?: RunToolCall) {
  if (!tool) return '缺失'
  return `${tool.success ? 'SUCCESS' : 'FAILED'} · ${tool.elapsedMs ?? 0} ms · ${tool.errorCode || tool.resultSummary || '-'}`
}

function guardDigest(guard?: RunGuardDecision) {
  if (!guard) return '缺失'
  return `${guard.decision || '-'} · ${guard.reason || '-'}`
}

async function copyIssueSummary() {
  const run = summary.value
  if (!run) return
  const text = [
    `Trace: ${run.traceId}`,
    `RunType: ${run.runType}`,
    `Object: ${runObjectLabel(run)} (${runObjectId(run)})`,
    `Version: ${runVersionLabel(run)}`,
    `Entry: ${run.entryType}`,
    `Status: ${run.status}`,
    `Error: ${run.errorCode || '-'} ${run.errorMessage || ''}`.trim(),
    `Plan/Replan: ${run.planCount ?? 0}/${run.replanCount ?? 0}`,
    `Workflow/Tool: ${run.workflowCallCount ?? 0}/${run.toolCallCount ?? 0}`,
    `Hints: ${(detail.value?.repairHints || []).join(' | ') || '-'}`,
  ].join('\n')
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success('运行摘要已复制')
  } catch {
    ElMessage.error('复制失败')
  }
}

function pretty(value: unknown) {
  if (value == null) return '-'
  try {
    return JSON.stringify(value, null, 2)
  } catch {
    return String(value)
  }
}

watch(traceId, loadDetail)
onMounted(loadDetail)
</script>

<style scoped lang="scss">
.replay-alert,
.run-alert {
  margin-bottom: 16px;
}

.summary-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 14px;
}

.summary-card,
.snapshot-panel,
.span-item {
  border: 1px solid var(--border-color);
  border-radius: 8px;
  background: var(--card-bg);
}

.summary-card {
  padding: 16px;

  span,
  small {
    display: block;
    color: var(--text-secondary);
  }

  strong {
    display: block;
    margin: 8px 0 4px;
    font-size: 20px;
    color: var(--text-primary);
  }
}

.supervisor-metrics {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
  margin-bottom: 14px;
}

.supervisor-metric {
  padding: 14px;
  border: 1px solid var(--border-color);
  border-radius: 10px;
  background: var(--card-bg);

  span,
  small {
    display: block;
    color: var(--text-secondary);
  }

  strong {
    display: block;
    margin: 7px 0 3px;
    color: var(--text-primary);
    font-size: 20px;
  }
}

.supervisor-event-table {
  margin-bottom: 16px;
}

.card-header,
.span-head,
.span-tags,
.execution-stage {
  display: flex;
  align-items: center;
  gap: 10px;
}

.card-header,
.span-head {
  justify-content: space-between;
}

.compare-summary {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 10px;
  margin-bottom: 12px;
}

.diff-chip {
  padding: 12px;
  border: 1px solid var(--border-color);
  border-radius: 8px;
  background: var(--fill-color-light);

  span,
  strong {
    display: block;
  }

  span {
    color: var(--text-secondary);
    font-size: 12px;
  }

  strong {
    margin-top: 6px;
    color: var(--text-primary);
    word-break: break-word;
  }
}

.diff-detail-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
  padding: 10px 0;

  > div {
    border: 1px solid var(--border-color);
    border-radius: 8px;
    overflow: hidden;
    background: var(--card-bg);
  }

  .panel-title {
    padding: 10px 12px;
    border-bottom: 1px solid var(--border-color);
    font-weight: 600;
  }

  pre {
    max-height: 300px;
    overflow: auto;
    margin: 0;
    padding: 12px;
    white-space: pre-wrap;
    word-break: break-word;
  }
}

.hint-list {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.execution-model {
  display: flex;
  align-items: stretch;
  gap: 10px;
  margin: 6px 0 18px;

  > div {
    display: grid;
    flex: 1;
    gap: 4px;
    padding: 14px;
    border: 1px solid var(--border-color);
    border-radius: 8px;
    background: var(--fill-color-light);
  }

  > span {
    align-self: center;
    color: var(--text-secondary);
    font-size: 20px;
  }

  small {
    color: var(--text-secondary);
  }

  &.workflow-only > div {
    max-width: 320px;
  }
}

.path-prefix {
  color: var(--text-secondary);
  font-size: 18px;
}

.span-timeline {
  padding: 12px 8px;
}

.span-item {
  padding: 14px;
}

.span-head strong,
.span-head span {
  display: block;
}

.span-head span {
  margin-top: 4px;
  color: var(--text-secondary);
}

.error-text {
  margin-top: 10px;
  color: var(--el-color-danger);
}

.io-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
  margin-top: 12px;

  pre {
    max-height: 220px;
    overflow: auto;
    padding: 10px;
    border-radius: 6px;
    background: var(--fill-color-light);
    white-space: pre-wrap;
    word-break: break-word;
  }
}

.snapshot-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px;
}

.snapshot-panel {
  min-height: 280px;
  overflow: hidden;

  .panel-title {
    padding: 12px 14px;
    border-bottom: 1px solid var(--border-color);
    font-weight: 600;
  }

  pre {
    max-height: 480px;
    overflow: auto;
    margin: 0;
    padding: 14px;
    white-space: pre-wrap;
    word-break: break-word;
  }
}

@media (max-width: 1100px) {
  .summary-grid,
  .supervisor-metrics,
  .compare-summary,
  .diff-detail-grid,
  .snapshot-grid,
  .io-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .execution-model {
    flex-direction: column;

    > span {
      transform: rotate(90deg);
    }
  }
}

@media (max-width: 640px) {
  .summary-grid,
  .supervisor-metrics,
  .compare-summary,
  .diff-detail-grid,
  .snapshot-grid,
  .io-grid {
    grid-template-columns: 1fr;
  }
}
</style>
