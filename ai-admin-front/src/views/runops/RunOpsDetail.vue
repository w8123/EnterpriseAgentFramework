<template>
  <div class="page-container runops-detail">
    <div class="page-header">
      <div class="header-left">
        <el-button text :icon="ArrowLeft" @click="router.push('/runops')">返回</el-button>
        <div>
          <h2>{{ detailTitle }}</h2>
          <p class="trace-id">{{ traceId }}</p>
          <div v-if="summary" class="detail-subtitle">
            <el-tag size="small" effect="plain" :type="runKindTagType(summary)">{{ runKindLabel(summary) }}</el-tag>
            <span v-if="runSourceType(summary)" class="source-type">{{ runSourceType(summary) }}</span>
          </div>
        </div>
        <el-tag v-if="summary" :type="spanTimelineType(summary.status)">
          {{ summary.status }}
        </el-tag>
      </div>
      <div class="header-actions">
        <el-button
          v-if="summary && runIsWorkflow(summary) && runWorkflowId(summary)"
          @click="openWorkflowStudio"
        >
          打开 Workflow Studio
        </el-button>
        <el-button @click="loadDetail" :loading="loading">刷新</el-button>
        <el-button type="primary" @click="openReplayDialog" :loading="replaying" :disabled="!detail">重放运行</el-button>
      </div>
    </div>

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
        v-if="detail.repairHints?.length"
        class="hint-alert"
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

      <el-card shadow="never" class="action-card">
        <template #header>
          <div class="card-header">
            <span>处置状态</span>
            <el-tag :type="actionState.type" size="small">{{ actionState.label }}</el-tag>
          </div>
        </template>
        <div class="action-row">
          <el-button @click="markTrace('handled')">标记已处理</el-button>
          <el-button @click="markTrace('ignored')">忽略此类问题</el-button>
          <el-button @click="copyIssueSummary">复制问题摘要</el-button>
          <el-button v-if="actionState.value" text @click="markTrace('')">清除状态</el-button>
        </div>
      </el-card>

      <el-card v-if="comparison" class="compare-card" shadow="never">
        <template #header>
          <div class="card-header">
            <span>重放差异对比</span>
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
          <el-tab-pane label="节点差异">
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
              <el-table-column prop="key" label="节点" min-width="180" show-overflow-tooltip />
              <el-table-column label="原运行" min-width="240" show-overflow-tooltip>
                <template #default="{ row }">{{ nodeDigest(row.baseline) }}</template>
              </el-table-column>
              <el-table-column label="重放" min-width="240" show-overflow-tooltip>
                <template #default="{ row }">{{ nodeDigest(row.candidate) }}</template>
              </el-table-column>
            </el-table>
            <el-empty v-if="!changedSpanDiffs.length" description="节点链路一致" />
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
        <el-tab-pane label="执行链路">
          <section v-if="detail.workflowPath?.length" class="workflow-path">
            <div v-for="item in detail.workflowPath" :key="item.spanId || item.fromNodeId" class="path-item">
              <div class="path-node">
                <strong>{{ item.fromNodeId || '-' }}</strong>
                <el-tag size="small" :type="pathStatusType(item)">{{ pathStatusLabel(item) }}</el-tag>
              </div>
              <div class="path-edge">
                <span>{{ item.toNodeId ? '-> ' + item.toNodeId : 'END' }}</span>
                <el-tag v-if="item.condition" size="small" effect="plain">{{ item.condition }}</el-tag>
                <el-tag v-if="item.route" size="small" type="success" effect="plain">route: {{ item.route }}</el-tag>
              </div>
              <div class="path-meta">
                <span v-if="item.interactionId">interaction {{ item.interactionId }}</span>
                <span v-if="item.startedAt">{{ item.startedAt }}</span>
              </div>
            </div>
          </section>
          <el-empty v-else description="暂无工作流路径" />
          <el-timeline class="span-timeline">
            <el-timeline-item
              v-for="span in detail.spans"
              :key="span.id"
              :timestamp="span.startedAt"
              :type="spanTimelineType(span.status)"
              placement="top"
            >
              <div class="span-item">
                <div class="span-head">
                  <div>
                    <strong>{{ spanDisplayName(span) }}</strong>
                    <span>{{ span.spanType }} · {{ span.runtimeType || '-' }}</span>
                  </div>
                  <div class="span-tags">
                    <el-tag size="small" :type="spanTimelineType(span.status)">{{ span.status }}</el-tag>
                    <el-tag v-if="span.metadata?.lastRoute" size="small" type="success" effect="plain">
                      route: {{ span.metadata.lastRoute }}
                    </el-tag>
                    <el-tag v-if="span.metadata?.interactionId" size="small" type="warning" effect="plain">
                      {{ span.metadata.interactionId }}
                    </el-tag>
                    <el-tag size="small" effect="plain">{{ span.latencyMs ?? 0 }} ms</el-tag>
                  </div>
                </div>
                <div v-if="span.metadata?.workflowStatus === 'WAITING'" class="waiting-text">
                  等待人工处理：{{ span.metadata?.interactionId || '-' }}
                </div>
                <div v-if="span.errorMessage" class="error-text">{{ span.errorCode }}：{{ span.errorMessage }}</div>
                <div class="io-grid">
                  <pre>{{ span.inputSummary || '-' }}</pre>
                  <pre>{{ span.outputSummary || '-' }}</pre>
                </div>
              </div>
            </el-timeline-item>
          </el-timeline>
          <el-empty v-if="!detail.spans.length" description="暂无结构化 span" />
        </el-tab-pane>

        <el-tab-pane label="Tool 调用">
          <el-table :data="detail.toolCalls" stripe>
            <el-table-column prop="success" label="状态" width="90">
              <template #default="{ row }">
                <el-tag size="small" :type="row.success ? 'success' : 'danger'">{{ row.success ? 'SUCCESS' : 'ERROR' }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="toolName" label="Tool" min-width="220" show-overflow-tooltip />
            <el-table-column prop="elapsedMs" label="耗时" width="100">
              <template #default="{ row }">{{ row.elapsedMs ?? 0 }} ms</template>
            </el-table-column>
            <el-table-column prop="tokenCost" label="Token" width="100" />
            <el-table-column prop="errorCode" label="错误" width="160" show-overflow-tooltip />
            <el-table-column prop="createdAt" label="时间" width="180" />
          </el-table>
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

        <el-tab-pane label="Trace Metadata">
          <section class="snapshot-grid metadata-grid">
            <div class="snapshot-panel">
              <div class="panel-title">Summary Metadata</div>
              <pre>{{ pretty(summary?.metadata) }}</pre>
            </div>
            <div class="snapshot-panel">
              <div class="panel-title">Identity Fields</div>
              <pre>{{ pretty(identityMetadata) }}</pre>
            </div>
          </section>
        </el-tab-pane>

        <el-tab-pane label="生产快照">
          <section class="snapshot-grid">
            <div class="snapshot-panel">
              <div class="panel-title">RuntimeConfig</div>
              <pre>{{ pretty(detail.snapshot?.runtimeConfig) }}</pre>
            </div>
            <div class="snapshot-panel">
              <div class="panel-title">GraphSpec</div>
              <pre>{{ pretty(detail.snapshot?.graphSpec) }}</pre>
            </div>
          </section>
        </el-tab-pane>
      </el-tabs>
    </template>

    <el-dialog v-model="replayDialogVisible" title="重放配置" width="560px">
      <el-alert
        v-if="summary && runIsWorkflow(summary)"
        class="replay-compat-alert"
        :type="replayAlertType"
        :closable="false"
        show-icon
        :title="replayAlertTitle"
        :description="replayAlertDescription"
      />
      <el-form label-width="110px">
        <el-form-item label="使用快照">
          <el-switch v-model="replayForm.useSnapshot" />
        </el-form-item>
        <el-form-item label="覆盖输入">
          <el-input
            v-model="replayForm.messageOverride"
            type="textarea"
            :rows="4"
            placeholder="留空则自动复用原 trace 输入"
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
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ArrowLeft } from '@element-plus/icons-vue'
import { compareRunOpsTrace, getRunOpsDetail, replayRunOpsTrace } from '@/api/runops'
import type { ReplayRequest, ReplayResult, RunComparison, RunDetail, RunGuardDecision, RunSpan, RunSummary, RunToolCall, WorkflowPathItem } from '@/types/runops'
import {
  runDisplayName,
  runEntryAgentId,
  runEntryAgentKeySlug,
  runEntryLabel,
  runIsWorkflow,
  runKindLabel,
  runPrimaryIdentityId,
  runPrimaryIdentityLabel,
  runSourceId,
  runSourceType,
  runVersionLabel,
  runWorkflowId,
  runWorkflowKeySlug,
  runWorkflowVersion,
  runWorkflowVersionId,
} from '@/utils/workflowRunOps'

const route = useRoute()
const router = useRouter()
const traceId = computed(() => route.params.traceId as string)
const loading = ref(false)
const replaying = ref(false)
const replayDialogVisible = ref(false)
const detail = ref<RunDetail | null>(null)
const comparison = ref<RunComparison | null>(null)
const replayForm = ref<ReplayRequest>({ useSnapshot: true })
const replayRoles = ref<string[]>([])
const actionStatus = ref('')
const summary = computed(() => detail.value?.summary)
const compareSource = computed(() => route.query.compareWith as string | undefined)
const changedSummaryDiffs = computed(() => comparison.value?.summaryDiffs.filter((item) => item.changed) ?? [])
const changedSpanDiffs = computed(() => comparison.value?.spanDiffs.filter((item) => item.changed) ?? [])
const changedToolDiffs = computed(() => comparison.value?.toolDiffs.filter((item) => item.changed) ?? [])
const changedGuardDiffs = computed(() => comparison.value?.guardDiffs.filter((item) => item.changed) ?? [])
const actionState = computed(() => {
  if (actionStatus.value === 'handled') return { value: 'handled', label: '已处理', type: 'success' as const }
  if (actionStatus.value === 'ignored') return { value: 'ignored', label: '已忽略', type: 'warning' as const }
  return { value: '', label: '待处置', type: 'danger' as const }
})

const replayAlertType = computed(() => 'info' as const)

const replayAlertTitle = computed(() =>
  'Workflow 重放将按 GraphSpec + RuntimeContext 执行，结果用于观测对比。')

const replayAlertDescription = computed(() => {
  const s = summary.value
  if (!s) return undefined
  const hints: string[] = []
  if (!runEntryAgentId(s)) {
    hints.push('当前 trace 缺少 entryAgentId，后端可能无法解析 AgentEntry 入口。')
  }
  if (!runWorkflowId(s)) {
    hints.push('当前 trace 缺少 workflowId，可能无法解析 Workflow 图。')
  }
  return hints.length ? hints.join(' ') : undefined
})

const summaryItems = computed(() => {
  const s = summary.value
  if (!s) return []
  if (runIsWorkflow(s)) {
    return [
      { label: 'Workflow', value: runPrimaryIdentityLabel(s), hint: runPrimaryIdentityId(s) },
      { label: '版本', value: runVersionLabel(s), hint: runWorkflowVersionId(s) ? `versionId ${runWorkflowVersionId(s)}` : '未绑定版本' },
      { label: '来源', value: runSourceType(s) || '-', hint: runSourceId(s) || '-' },
      { label: '入口绑定', value: runEntryLabel(s) || '-', hint: runEntryAgentId(s) || '无 Agent 入口' },
      { label: 'Runtime', value: s.runtimeType || '-', hint: s.runtimePlacement || '-' },
      { label: 'Graph', value: s.graphCode || '-', hint: `${s.nodeCount ?? 0} spans / ${s.toolCallCount ?? 0} tools` },
      { label: '耗时', value: `${s.latencyMs ?? 0} ms`, hint: `${s.tokenCost ?? 0} token` },
      { label: '问题', value: s.errorCount ?? 0, hint: s.fallback ? `已回落：${s.fallbackReason || '-'}` : '无 fallback' },
    ]
  }
  return [
    { label: 'Agent', value: s.agentName || '-', hint: s.agentId || '-' },
    { label: '版本', value: s.version || '-', hint: s.versionId ? `versionId ${s.versionId}` : '未绑定版本' },
    { label: 'Runtime', value: s.runtimeType || '-', hint: s.runtimePlacement || '-' },
    { label: 'Graph', value: s.graphCode || '-', hint: `${s.nodeCount ?? 0} spans / ${s.toolCallCount ?? 0} tools` },
    { label: '耗时', value: `${s.latencyMs ?? 0} ms`, hint: `${s.tokenCost ?? 0} token` },
    { label: '问题', value: s.errorCount ?? 0, hint: s.fallback ? `已回落：${s.fallbackReason || '-'}` : '无 fallback' },
  ]
})

const detailTitle = computed(() => {
  const s = summary.value
  if (!s) return '运行详情'
  return runIsWorkflow(s) ? `Workflow 运行 · ${runDisplayName(s)}` : `Agent 运行 · ${runPrimaryIdentityLabel(s)}`
})

const identityMetadata = computed(() => {
  const s = summary.value
  if (!s) return {}
  return {
    traceId: s.traceId,
    sourceType: runSourceType(s),
    workflowId: runWorkflowId(s),
    workflowKeySlug: runWorkflowKeySlug(s),
    workflowVersion: runWorkflowVersion(s),
    workflowVersionId: runWorkflowVersionId(s),
    entryAgentId: runEntryAgentId(s),
    entryAgentKeySlug: runEntryAgentKeySlug(s),
    sourceId: runSourceId(s),
    agentId: s.agentId,
    agentName: s.agentName,
    version: s.version,
    versionId: s.versionId,
    runtimeType: s.runtimeType,
    runtimePlacement: s.runtimePlacement,
  }
})

function runKindTagType(run: RunSummary) {
  const kind = runKindLabel(run)
  if (kind === 'Workflow') return 'primary'
  if (kind === 'Agent') return 'success'
  if (kind === 'Tool') return 'warning'
  return 'info'
}

function openWorkflowStudio() {
  const workflowId = summary.value ? runWorkflowId(summary.value) : ''
  if (!workflowId) return
  router.push(`/workflows/${workflowId}/studio`)
}

function spanDisplayName(span: RunSpan) {
  const nodeName = span.metadata?.nodeName
  const nodeType = span.metadata?.nodeType
  const base = span.nodeId || span.toolName || span.spanType || span.spanId || '-'
  if (nodeName) return `${base} · ${nodeName}`
  if (nodeType) return `${base} (${nodeType})`
  return base
}

async function loadDetail() {
  loading.value = true
  try {
    const { data } = await getRunOpsDetail(traceId.value)
    detail.value = data
    loadActionStatus()
    await loadComparison()
  } catch {
    detail.value = null
    ElMessage.error('加载运行详情失败')
  } finally {
    loading.value = false
  }
}

function openReplayDialog() {
  replayForm.value = {
    useSnapshot: true,
    userId: summary.value?.userId,
  }
  replayRoles.value = []
  replayDialogVisible.value = true
}

async function replayTrace() {
  if (!detail.value) return
  replaying.value = true
  try {
    const request: ReplayRequest = {
      ...replayForm.value,
      roles: replayRoles.value,
    }
    const { data } = await replayRunOpsTrace(traceId.value, request)
    if (data?.replayTraceId) {
      replayDialogVisible.value = false
      if (data.executionPath === 'AGENT_DEFINITION_FALLBACK' && data.fallbackReason) {
        ElMessage.warning(`重放已回退到旧兼容路径：${data.fallbackReason}`)
      } else if (data.executionPath === 'AGENT_DEFINITION') {
        ElMessage.warning('重放走旧兼容路径（trace 未携带 Workflow GraphSpec）')
      } else if (data.executionPath === 'GRAPH_SPEC') {
        ElMessage.success('已发起 GraphSpec 重放，正在打开新 trace')
      } else {
        ElMessage.success('重放已完成，正在打开新 trace')
      }
      router.push({ path: `/runops/${data.replayTraceId}`, query: { compareWith: traceId.value } })
    } else {
      ElMessage.warning('重放完成，但未返回新 traceId')
    }
  } catch {
    ElMessage.error('重放运行失败')
  } finally {
    replaying.value = false
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

function displayValue(value: unknown) {
  if (value == null || value === '') return '-'
  if (typeof value === 'boolean') return value ? 'true' : 'false'
  return String(value)
}

function nodeDigest(span?: RunSpan) {
  if (!span) return '缺失'
  return `${span.status || '-'} · ${span.latencyMs ?? 0} ms · ${span.errorCode || span.outputSummary || '-'}`
}

function toolDigest(tool?: RunToolCall) {
  if (!tool) return '缺失'
  return `${tool.success ? 'SUCCESS' : 'ERROR'} · ${tool.elapsedMs ?? 0} ms · ${tool.errorCode || tool.resultSummary || '-'}`
}

function guardDigest(guard?: RunGuardDecision) {
  if (!guard) return '缺失'
  return `${guard.decision || '-'} · ${guard.reason || '-'}`
}

function spanTimelineType(status?: string) {
  if (status === 'SUCCESS') return 'success'
  if (status === 'WAITING') return 'warning'
  return 'danger'
}

function pathStatusType(item: WorkflowPathItem) {
  return spanTimelineType(item.workflowStatus || item.status)
}

function pathStatusLabel(item: WorkflowPathItem) {
  if (item.workflowStatus === 'WAITING' || item.status === 'WAITING') return 'WAITING'
  return item.status || '-'
}

function actionKey() {
  return `runops-action:${traceId.value}`
}

function loadActionStatus() {
  actionStatus.value = window.localStorage.getItem(actionKey()) || ''
}

function markTrace(status: string) {
  actionStatus.value = status
  if (status) window.localStorage.setItem(actionKey(), status)
  else window.localStorage.removeItem(actionKey())
  ElMessage.success(status ? '处置状态已更新' : '处置状态已清除')
}

async function copyIssueSummary() {
  const s = summary.value
  if (!s) return
  const text = [
    `Trace: ${s.traceId}`,
    runIsWorkflow(s) ? `Workflow: ${runPrimaryIdentityLabel(s)} (${runPrimaryIdentityId(s)})` : `Agent: ${s.agentName || '-'}`,
    runIsWorkflow(s) ? `Version: ${runVersionLabel(s)}` : `Version: ${s.version || '-'}`,
    `SourceType: ${runSourceType(s) || '-'}`,
    `Status: ${s.status}`,
    `Errors: ${s.errorCount ?? 0}`,
    `Fallback: ${s.fallback ? s.fallbackReason || 'true' : 'false'}`,
    `Hints: ${(detail.value?.repairHints || []).join(' | ') || '-'}`,
  ].join('\n')
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success('问题摘要已复制')
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
.header-left {
  display: flex;
  align-items: center;
  gap: 12px;
}

.header-actions {
  display: flex;
  align-items: center;
  gap: 10px;
}

.trace-id {
  margin: 4px 0 0;
  color: var(--text-secondary);
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
}

.detail-subtitle {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 6px;
}

.source-type {
  color: var(--text-secondary);
  font-size: 12px;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
}

.replay-compat-alert {
  margin-bottom: 16px;
}

.metadata-grid {
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.summary-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 14px;
  margin-bottom: 16px;
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

.hint-alert {
  margin-bottom: 16px;
}

.action-card {
  margin-bottom: 16px;
}

.action-row {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}

.compare-card {
  margin-bottom: 16px;
}

.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
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

.span-timeline {
  padding: 12px 8px;
}

.workflow-path {
  display: grid;
  gap: 10px;
  padding: 12px 8px 18px;
}

.path-item {
  display: grid;
  grid-template-columns: minmax(180px, 0.9fr) minmax(220px, 1.2fr) minmax(180px, 1fr);
  gap: 12px;
  align-items: center;
  padding: 12px 14px;
  border: 1px solid var(--border-color);
  border-radius: 8px;
  background: var(--card-bg);
}

.path-node,
.path-edge,
.path-meta {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
}

.path-node strong {
  color: var(--text-primary);
}

.path-edge,
.path-meta {
  color: var(--text-secondary);
}

.span-item {
  padding: 14px;
}

.span-head,
.span-tags {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
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

.waiting-text {
  margin-top: 10px;
  color: var(--el-color-warning-dark-2);
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
  min-height: 320px;
  overflow: hidden;

  .panel-title {
    padding: 12px 14px;
    border-bottom: 1px solid var(--border-color);
    font-weight: 600;
  }

  pre {
    max-height: 560px;
    overflow: auto;
    margin: 0;
    padding: 14px;
    white-space: pre-wrap;
    word-break: break-word;
  }
}

@media (max-width: 1100px) {
  .summary-grid,
  .compare-summary,
  .diff-detail-grid,
  .snapshot-grid,
  .path-item,
  .io-grid {
    grid-template-columns: 1fr;
  }
}
</style>
