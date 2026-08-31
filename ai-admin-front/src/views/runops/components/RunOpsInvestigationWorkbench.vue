<template>
  <section class="investigation-workbench" aria-label="运行调查工作台">
    <header class="investigation-workbench__header">
      <nav class="workbench-groups" aria-label="调查视图">
        <div
          v-for="group in workspaceGroups"
          :key="group.name"
          :class="['workbench-group', { active: activeGroupName === group.name }]"
        >
          <button type="button" class="workbench-group__label" @click="activateGroup(group)">
            {{ group.label }}
          </button>
          <div v-if="activeGroupName === group.name && group.tabs.length > 1" class="workbench-subtabs">
            <button
              v-for="tab in group.tabs"
              :key="tab.name"
              type="button"
              :class="{ active: activeTab === tab.name }"
              @click="activeTab = tab.name"
            >
              {{ tab.label }}
            </button>
          </div>
        </div>
      </nav>
      <div class="workbench-summary" aria-label="执行摘要">
        <span>{{ executionRows.length }} 个阶段</span>
        <span>{{ summary.workflowCallCount ?? 0 }} Workflow · {{ summary.toolCallCount ?? 0 }} Tool</span>
        <strong :class="{ clear: issueCount === 0 }">
          {{ issueCount ? `${issueCount} 个异常` : '全链正常' }}
        </strong>
      </div>
    </header>

    <div class="investigation-workbench__body">
      <main class="workbench-main">
        <section v-show="activeTab === 'execution'" class="execution-view">
          <header class="view-toolbar">
            <div>
              <strong>按实际执行顺序</strong>
              <span>点击阶段，右侧立即查看原因、输入输出与证据</span>
            </div>
            <div class="filter-pills" aria-label="执行链筛选">
              <button
                type="button"
                :class="{ active: executionFilter === 'all' }"
                @click="executionFilter = 'all'"
              >
                全部
              </button>
              <button
                type="button"
                :class="{ active: executionFilter === 'issues' }"
                @click="executionFilter = 'issues'"
              >
                只看异常
              </button>
            </div>
          </header>

          <div v-if="visibleExecutionRows.length" class="execution-list">
            <button
              v-for="row in visibleExecutionRows"
              :key="row.key"
              type="button"
              :class="[
                'execution-row',
                `is-${statusTone(row.item.status)}`,
                { selected: selectedExecutionKey === row.key },
              ]"
              :style="{ '--execution-depth': String(Math.min(row.item.depth || 0, 4)) }"
              @click="selectExecutionRow(row.key)"
            >
              <span class="execution-rail" aria-hidden="true"><i /></span>
              <span class="execution-copy">
                <span class="execution-title">
                  <strong>{{ row.title }}</strong>
                  <em>{{ phaseLabel(row.item.spanType) }}</em>
                </span>
                <small>{{ executionSubtitle(row) }}</small>
              </span>
              <span class="execution-duration">{{ rowDuration(row) }}</span>
              <span :class="['execution-status', `is-${statusTone(row.item.status)}`]">
                {{ executionStatusLabel(row.item.status) }}
              </span>
            </button>
          </div>
          <el-empty v-else :description="executionFilter === 'issues' ? '当前执行链没有异常阶段' : '暂无结构化执行路径'" />
        </section>

        <section v-show="activeTab === 'supervisor'" class="data-view supervisor-view">
          <div class="view-heading">
            <div>
              <h2>调度决策</h2>
              <p>保留规划、有限重规划和 Workflow-as-Tool 选择证据。</p>
            </div>
          </div>
          <div class="metric-strip">
            <div v-for="item in supervisorMetrics" :key="item.label">
              <span>{{ item.label }}</span>
              <strong>{{ item.value }}</strong>
              <small>{{ item.hint }}</small>
            </div>
          </div>
          <el-table
            :data="supervisorEvents"
            row-key="id"
            class="evidence-table"
            @row-click="selectSpan"
          >
            <el-table-column label="阶段" width="118">
              <template #default="{ row }">
                <el-tag size="small" :type="phaseTagType(row.spanType)">{{ phaseLabel(row.spanType) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="计划 / 选择" min-width="210">
              <template #default="{ row }">{{ supervisorEventLabel(row) }}</template>
            </el-table-column>
            <el-table-column label="结果" min-width="250" show-overflow-tooltip>
              <template #default="{ row }">{{ supervisorEventResultLabel(row) }}</template>
            </el-table-column>
            <el-table-column label="状态" width="100">
              <template #default="{ row }">
                <el-tag size="small" :type="statusTagType(row.status)">{{ executionStatusLabel(row.status) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="耗时" width="110">
              <template #default="{ row }">{{ formatDuration(row.latencyMs) }}</template>
            </el-table-column>
          </el-table>
          <el-empty v-if="!supervisorEvents.length" description="该运行尚无结构化调度决策事件" />
        </section>

        <section v-show="activeTab === 'spans'" class="data-view span-view">
          <div class="view-heading">
            <div>
              <h2>链路明细</h2>
              <p>逐段核对运行时、耗时、错误以及原始输入输出摘要。</p>
            </div>
          </div>
          <el-timeline v-if="detail.spans.length" class="span-timeline">
            <el-timeline-item
              v-for="span in detail.spans"
              :key="span.id"
              :timestamp="formatDateTime(span.startedAt)"
              :type="statusTagType(span.status)"
              placement="top"
            >
              <button type="button" class="span-card" @click="selectSpan(span)">
                <span class="span-card__head">
                  <span>
                    <strong>{{ spanDisplayName(span) }}</strong>
                    <small>{{ phaseLabel(span.spanType) }} · {{ formatRuntimeTypeLabel(span.runtimeType) }}</small>
                  </span>
                  <span class="span-card__tags">
                    <el-tag size="small" :type="statusTagType(span.status)">{{ executionStatusLabel(span.status) }}</el-tag>
                    <el-tag size="small" effect="plain">{{ formatDuration(span.latencyMs) }}</el-tag>
                  </span>
                </span>
                <span v-if="span.errorMessage || span.errorCode" class="span-card__error">
                  {{ span.errorCode || 'SPAN_FAILED' }}：{{ executionSummaryLabel(span.errorMessage) }}
                </span>
              </button>
            </el-timeline-item>
          </el-timeline>
          <el-empty v-else description="暂无结构化链路片段" />
        </section>

        <section v-show="activeTab === 'tools'" class="data-view">
          <div class="view-heading">
            <div>
              <h2>工具调用</h2>
              <p>完整保留状态、参数、耗时、Token、错误和发生时间。</p>
            </div>
          </div>
          <el-table v-if="detail.toolCalls.length" :data="detail.toolCalls" stripe class="evidence-table" @row-click="selectToolCall">
            <el-table-column label="状态" width="92">
              <template #default="{ row }">
                <el-tag size="small" :type="row.success ? 'success' : 'danger'">{{ row.success ? '成功' : '失败' }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="toolName" label="工具" min-width="190" show-overflow-tooltip />
            <el-table-column prop="intentType" label="来源 / 意图" min-width="150" show-overflow-tooltip />
            <el-table-column label="耗时" width="100">
              <template #default="{ row }">{{ formatDuration(row.elapsedMs) }}</template>
            </el-table-column>
            <el-table-column prop="tokenCost" label="Token" width="86" />
            <el-table-column prop="errorCode" label="错误" min-width="180" show-overflow-tooltip />
            <el-table-column label="时间" width="168">
              <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
            </el-table-column>
          </el-table>
          <div v-else class="workbench-empty">
            <span>…</span>
            <strong>{{ summary.status === 'RUNNING' || summary.status === 'SUSPENDED' ? '还没有工具调用结果' : '该运行未调用工具' }}</strong>
            <p>{{ summary.status === 'RUNNING' || summary.status === 'SUSPENDED' ? '运行继续后，工具状态、耗时和错误会显示在这里。' : '当前轨迹没有产生 Tool 调用记录。' }}</p>
          </div>
        </section>

        <section v-show="activeTab === 'guards'" class="data-view">
          <div class="view-heading">
            <div>
              <h2>治理决策</h2>
              <p>审批、拒绝和策略放行均保留对象、原因与发生时间。</p>
            </div>
          </div>
          <el-table v-if="detail.guardDecisions.length" :data="detail.guardDecisions" stripe class="evidence-table">
            <el-table-column label="决策" width="100">
              <template #default="{ row }">
                <el-tag size="small" :type="guardTagType(row.decision)">{{ guardDecisionLabel(row.decision) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="类型" width="150">
              <template #default="{ row }">{{ guardDecisionTypeLabel(row.decisionType) }}</template>
            </el-table-column>
            <el-table-column label="对象类型" width="128">
              <template #default="{ row }">{{ guardTargetKindLabel(row.targetKind) }}</template>
            </el-table-column>
            <el-table-column prop="targetName" label="对象" min-width="170" show-overflow-tooltip />
            <el-table-column label="原因" min-width="230" show-overflow-tooltip>
              <template #default="{ row }">{{ executionSummaryLabel(row.reason) }}</template>
            </el-table-column>
            <el-table-column label="时间" width="168">
              <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
            </el-table-column>
          </el-table>
          <div v-else class="workbench-empty is-success">
            <span>✓</span>
            <strong>没有拒绝或审批事件</strong>
            <p>本次运行没有产生治理阻断；节点的 ACL 与运行证据仍可在右侧核对。</p>
          </div>
        </section>

        <section v-show="activeTab === 'metadata'" class="data-view metadata-view">
          <div class="view-heading">
            <div>
              <h2>发布配置与运行元数据</h2>
              <p>四类审计快照完整保留，按需展开，不再占据首屏。</p>
            </div>
          </div>
          <div class="snapshot-accordion">
            <details open>
              <summary>根运行元数据 <small>RunSummary.metadata</small></summary>
              <pre>{{ pretty(summary.metadata) }}</pre>
            </details>
            <details>
              <summary>已发布运行时配置 <small>snapshot.runtimeConfig</small></summary>
              <pre>{{ pretty(detail.snapshot?.runtimeConfig) }}</pre>
            </details>
            <details>
              <summary>已发布 GraphSpec <small>snapshot.graphSpec</small></summary>
              <pre>{{ pretty(detail.snapshot?.graphSpec) }}</pre>
            </details>
            <details>
              <summary>配置版本身份 <small>versionIdentity</small></summary>
              <pre>{{ pretty(versionIdentity) }}</pre>
            </details>
          </div>
        </section>

        <section v-show="activeTab === 'compare'" class="data-view comparison-view">
          <template v-if="comparison">
            <div class="compare-banner">
              <span>原运行 <code>{{ shortIdentifier(comparison.baseline.traceId) }}</code></span>
              <strong>→</strong>
              <span>本次运行 <code>{{ shortIdentifier(comparison.candidate.traceId) }}</code></span>
            </div>
            <div class="diff-metrics">
              <div v-for="item in changedSummaryDiffs" :key="item.field">
                <span>{{ diffFieldLabel(item.field) }}</span>
                <strong>
                  {{ displayDiffValue(item.field, item.baseline) }}
                  <em>→</em>
                  {{ displayDiffValue(item.field, item.candidate) }}
                </strong>
              </div>
              <div v-if="!changedSummaryDiffs.length" class="diff-empty">摘要指标一致</div>
            </div>
            <el-tabs class="compare-tabs">
              <el-tab-pane label="链路差异">
                <DiffTable
                  kind="span"
                  :rows="changedSpanDiffs"
                  :digest="spanDigest"
                  empty-text="执行链路一致"
                />
              </el-tab-pane>
              <el-tab-pane label="工具差异">
                <DiffTable
                  kind="tool"
                  :rows="changedToolDiffs"
                  :digest="toolDigest"
                  empty-text="工具调用一致"
                />
              </el-tab-pane>
              <el-tab-pane label="治理差异">
                <DiffTable
                  kind="guard"
                  :rows="changedGuardDiffs"
                  :digest="guardDigest"
                  empty-text="治理决策一致"
                />
              </el-tab-pane>
            </el-tabs>
          </template>
          <el-empty v-else description="当前运行没有可对比的原始追踪" />
        </section>
      </main>

      <aside class="node-inspector" aria-label="所选节点检查器">
        <template v-if="selectedRow">
          <header class="node-inspector__header">
            <div>
              <span>{{ phaseLabel(selectedRow.item.spanType) }}</span>
              <el-tag size="small" :type="statusTagType(selectedRow.item.status)">
                {{ executionStatusLabel(selectedRow.item.status) }}
              </el-tag>
            </div>
            <h2>{{ selectedRow.title }}</h2>
            <code>{{ selectedNodeCode }}</code>
          </header>

          <dl class="node-metrics">
            <div><dt>运行时</dt><dd>{{ formatRuntimeTypeLabel(selectedRow.item.runtimeType || selectedSpan?.runtimeType) }}</dd></div>
            <div><dt>开始时间</dt><dd>{{ formatTimeOnly(selectedRow.item.startedAt || selectedSpan?.startedAt) }}</dd></div>
            <div><dt>耗时</dt><dd>{{ rowDuration(selectedRow) }}</dd></div>
            <div><dt>尝试</dt><dd>{{ selectedAttemptLabel }}</dd></div>
            <div><dt>Token</dt><dd>{{ selectedSpan?.tokenCost ?? '—' }}</dd></div>
          </dl>

          <nav class="inspector-tabs" aria-label="节点详情">
            <button type="button" :class="{ active: inspectorTab === 'diagnosis' }" @click="inspectorTab = 'diagnosis'">诊断</button>
            <button type="button" :class="{ active: inspectorTab === 'payload' }" @click="inspectorTab = 'payload'">输入 / 输出</button>
            <button type="button" :class="{ active: inspectorTab === 'evidence' }" @click="inspectorTab = 'evidence'">证据</button>
          </nav>

          <div class="node-inspector__content">
            <section v-if="inspectorTab === 'diagnosis'" class="diagnosis-view">
              <div :class="['node-diagnostic', `is-${selectedTone}`]">
                <strong>{{ selectedDiagnostic.title }}</strong>
                <code>{{ selectedDiagnostic.code }}</code>
                <p>{{ selectedDiagnostic.description }}</p>
              </div>
              <h3>{{ selectedTone === 'danger' ? '建议按这个顺序检查' : '继续核对' }}</h3>
              <ol class="next-steps">
                <li v-for="step in selectedSteps" :key="step">{{ step }}</li>
              </ol>
              <div class="inspector-actions">
                <el-button v-if="selectedSpan?.toolName || selectedRow.item.toolName" @click="activeTab = 'tools'">查看工具调用</el-button>
                <el-button @click="inspectorTab = 'evidence'">查看节点证据</el-button>
              </div>
              <div class="inspector-footnote">
                <span>发生于 <strong>{{ formatTimeOnly(selectedSpan?.startedAt || selectedRow.item.startedAt) }}</strong></span>
                <span>span <strong>{{ shortIdentifier(selectedSpan?.spanId || selectedRow.item.spanId) }}</strong></span>
              </div>
            </section>

            <section v-else-if="inspectorTab === 'payload'" class="payload-view">
              <article>
                <h3>节点输入 <small>application/json</small></h3>
                <pre>{{ payloadText(selectedSpan?.inputSummary) }}</pre>
              </article>
              <article>
                <h3>节点输出 <small>{{ selectedTone === 'danger' ? 'error' : 'application/json' }}</small></h3>
                <pre>{{ payloadText(selectedSpan?.outputSummary) }}</pre>
              </article>
            </section>

            <section v-else class="evidence-view">
              <h3>本节点的直接证据</h3>
              <dl>
                <div v-for="item in selectedEvidence" :key="item.label">
                  <dt>{{ item.label }}</dt>
                  <dd><code>{{ item.value }}</code></dd>
                </div>
              </dl>
              <details v-if="selectedSpan?.metadata && Object.keys(selectedSpan.metadata).length">
                <summary>Span metadata</summary>
                <pre>{{ pretty(selectedSpan.metadata) }}</pre>
              </details>
            </section>
          </div>
        </template>
        <el-empty v-else description="选择执行阶段后查看节点证据" />
      </aside>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { formatRuntimeTypeLabel } from '@/utils/registryLabels'
import DiffTable from './RunOpsDiffTable.vue'
import type {
  RunComparison,
  RunDetail,
  RunExecutionPathItem,
  RunGuardDecision,
  RunSpan,
  RunSummary,
  RunToolCall,
} from '@/types/runops'

type WorkbenchTab = 'execution' | 'supervisor' | 'spans' | 'tools' | 'guards' | 'metadata' | 'compare'
type WorkbenchGroupName = 'investigation' | 'operations' | 'evidence' | 'compare'
type InspectorTab = 'diagnosis' | 'payload' | 'evidence'
type StatusTone = 'success' | 'danger' | 'warning' | 'primary' | 'info'

interface WorkspaceGroup {
  name: WorkbenchGroupName
  label: string
  tabs: Array<{ name: WorkbenchTab; label: string }>
}

interface ExecutionRow {
  key: string
  item: RunExecutionPathItem
  span?: RunSpan
  title: string
}

const props = defineProps<{
  detail: RunDetail
  summary: RunSummary
  comparison?: RunComparison | null
}>()

const activeTab = ref<WorkbenchTab>('execution')
const inspectorTab = ref<InspectorTab>('diagnosis')
const executionFilter = ref<'all' | 'issues'>('all')
const selectedExecutionKey = ref('')

const workspaceGroups = computed<WorkspaceGroup[]>(() => [
  {
    name: 'investigation',
    label: '调查',
    tabs: [
      { name: 'execution', label: '执行链' },
      ...(props.summary.runType === 'AGENT'
        ? [{ name: 'supervisor' as const, label: '调度决策' }]
        : []),
      { name: 'spans', label: '链路明细' },
    ],
  },
  {
    name: 'operations',
    label: '调用与治理',
    tabs: [
      { name: 'tools', label: '工具调用' },
      { name: 'guards', label: '治理决策' },
    ],
  },
  {
    name: 'evidence',
    label: '发布证据',
    tabs: [{ name: 'metadata', label: '元数据' }],
  },
  ...(props.comparison
    ? [{ name: 'compare' as const, label: '重放对比', tabs: [{ name: 'compare' as const, label: '重放对比' }] }]
    : []),
])

const activeGroupName = computed<WorkbenchGroupName>(() =>
  workspaceGroups.value.find((group) => group.tabs.some((tab) => tab.name === activeTab.value))?.name
    || 'investigation',
)

const executionItems = computed<RunExecutionPathItem[]>(() => {
  if (props.detail.executionPath?.length) return props.detail.executionPath
  return props.detail.spans.map((span) => ({
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

const executionRows = computed<ExecutionRow[]>(() => executionItems.value.map((item, index) => {
  const span = spanForItem(item)
  return {
    key: item.spanId || span?.spanId || `${item.spanType || 'stage'}:${item.nodeId || item.toolName || item.label || index}:${index}`,
    item,
    span,
    title: executionPathLabel(item),
  }
}))

const visibleExecutionRows = computed(() => executionFilter.value === 'all'
  ? executionRows.value
  : executionRows.value.filter((row) => !isHealthyStatus(row.item.status)))

const selectedRow = computed(() => executionRows.value.find((row) => row.key === selectedExecutionKey.value))
const selectedSpan = computed(() => selectedRow.value?.span)
const selectedTone = computed(() => statusTone(selectedRow.value?.item.status))
const selectedAttemptLabel = computed(() => attemptLabel(selectedSpan.value))
const issueCount = computed(() => executionRows.value.filter((row) => !isHealthyStatus(row.item.status)).length)
const supervisorEvents = computed(() => props.detail.spans.filter((span) =>
  ['PLAN', 'REPLAN', 'WORKFLOW_TOOL'].includes(span.spanType || ''),
))
const supervisorMetrics = computed(() => [
  { label: '配置版本', value: props.summary.agentConfigVersion || '-', hint: props.summary.agentConfigVersionId == null ? '未记录版本 ID' : `版本 ID ${props.summary.agentConfigVersionId}` },
  { label: '计划次数', value: props.summary.planCount ?? 0, hint: '调度器首次规划' },
  { label: '重规划', value: props.summary.replanCount ?? 0, hint: '有限重规划次数' },
  { label: '工作流调用', value: props.summary.workflowCallCount ?? 0, hint: '工作流工具' },
])
const versionIdentity = computed(() => ({
  runType: props.summary.runType,
  agentId: props.summary.agentId,
  agentKeySlug: props.summary.agentKeySlug,
  agentConfigVersionId: props.summary.agentConfigVersionId,
  agentConfigVersion: props.summary.agentConfigVersion,
  workflowId: props.summary.workflowId,
  workflowKeySlug: props.summary.workflowKeySlug,
  workflowVersionId: props.summary.workflowVersionId,
  workflowVersion: props.summary.workflowVersion,
}))
const changedSummaryDiffs = computed(() => props.comparison?.summaryDiffs.filter((item) => item.changed) ?? [])
const changedSpanDiffs = computed(() => props.comparison?.spanDiffs.filter((item) => item.changed) ?? [])
const changedToolDiffs = computed(() => props.comparison?.toolDiffs.filter((item) => item.changed) ?? [])
const changedGuardDiffs = computed(() => props.comparison?.guardDiffs.filter((item) => item.changed) ?? [])

const selectedNodeCode = computed(() => selectedRow.value?.item.nodeId
  || selectedRow.value?.item.toolName
  || selectedSpan.value?.spanId
  || selectedRow.value?.item.spanId
  || '-')

const selectedDiagnostic = computed(() => {
  const row = selectedRow.value
  const span = selectedSpan.value
  const tone = selectedTone.value
  if (!row) return { title: '尚未选择节点', code: '-', description: '请选择左侧执行阶段。' }
  if (tone === 'danger') {
    return {
      title: span?.errorCode ? '节点执行失败' : '阶段未成功完成',
      code: span?.errorCode || executionStatusLabel(row.item.status),
      description: compactMessage(span?.errorMessage || span?.outputSummary, `${row.title} 未成功完成，请核对输入、输出和直接证据。`),
    }
  }
  if (tone === 'warning') {
    return {
      title: '运行在此等待继续',
      code: executionStatusLabel(row.item.status),
      description: compactMessage(span?.outputSummary, '完成审批或用户交互后，运行将从当前阶段继续。'),
    }
  }
  if (tone === 'primary') {
    return {
      title: '节点仍在执行',
      code: executionStatusLabel(row.item.status),
      description: compactMessage(span?.outputSummary, '当前尚未超过运行阈值，可刷新查看增量结果。'),
    }
  }
  return {
    title: '节点已成功完成',
    code: executionStatusLabel(row.item.status),
    description: compactMessage(span?.outputSummary, '本阶段没有记录错误，可继续核对输入输出与发布证据。'),
  }
})

const selectedSteps = computed(() => {
  if (selectedTone.value === 'danger' && props.detail.repairHints.length) return props.detail.repairHints
  if (selectedTone.value === 'warning') return ['完成当前审批或用户交互。', '恢复后刷新运行详情，确认后续执行链。']
  if (selectedTone.value === 'primary') return ['等待当前阶段返回。', '超过阈值后再检查目标服务与连接状态。']
  return ['核对输出是否符合用户意图。', '需要审计时继续查看节点证据与发布快照。']
})

const selectedEvidence = computed(() => {
  const row = selectedRow.value
  const span = selectedSpan.value
  if (!row) return []
  const evidence = [
    { label: '状态', value: executionStatusLabel(row.item.status) },
    { label: '运行时', value: formatRuntimeTypeLabel(row.item.runtimeType || span?.runtimeType) },
    { label: 'Span ID', value: span?.spanId || row.item.spanId || '-' },
    { label: '父 Span', value: span?.parentSpanId || row.item.parentSpanId || '-' },
    { label: '节点', value: row.item.nodeId || span?.nodeId || '-' },
    { label: '工具', value: row.item.toolName || span?.toolName || '-' },
    { label: '错误码', value: span?.errorCode || '-' },
  ]
  const metadata = span?.metadata
  if (metadata?.nodeType) evidence.push({ label: '节点类型', value: String(metadata.nodeType) })
  if (metadata?.qualifiedName) evidence.push({ label: '能力全名', value: String(metadata.qualifiedName) })
  if (metadata?.attempt != null || metadata?.maxAttempts != null) {
    evidence.push({ label: '执行尝试', value: attemptLabel(span) })
  }
  if (metadata?.errorPolicy) evidence.push({ label: '错误策略', value: String(metadata.errorPolicy) })
  if (metadata?.failureCategory) evidence.push({ label: '失败分类', value: String(metadata.failureCategory) })
  if (metadata?.retryableFailure != null) {
    evidence.push({ label: '允许重试', value: metadata.retryableFailure ? '是' : '否' })
  }
  if (metadata?.fallbackNodeId) evidence.push({ label: '回退节点', value: String(metadata.fallbackNodeId) })
  return evidence
})

watch(executionRows, (rows) => {
  if (rows.some((row) => row.key === selectedExecutionKey.value)) return
  selectedExecutionKey.value = primaryIssueRow(rows)?.key || rows[rows.length - 1]?.key || ''
}, { immediate: true })

watch(() => props.comparison, (value) => {
  if (value) activeTab.value = 'compare'
})

function focusFailure() {
  activeTab.value = 'execution'
  executionFilter.value = 'all'
  const failed = primaryIssueRow(executionRows.value)
  if (failed) selectExecutionRow(failed.key)
}

function showCompare() {
  if (props.comparison) activeTab.value = 'compare'
}

defineExpose({ focusFailure, showCompare })

function selectExecutionRow(key: string) {
  selectedExecutionKey.value = key
  inspectorTab.value = 'diagnosis'
}

function activateGroup(group: WorkspaceGroup) {
  if (group.tabs.some((tab) => tab.name === activeTab.value)) return
  const firstTab = group.tabs[0]
  if (firstTab) activeTab.value = firstTab.name
}

function primaryIssueRow(rows: ExecutionRow[]) {
  let selected: ExecutionRow | undefined
  rows.forEach((row) => {
    if (isHealthyStatus(row.item.status)) return
    if (!selected || (row.item.depth || 0) >= (selected.item.depth || 0)) selected = row
  })
  return selected
}

function selectSpan(span: RunSpan) {
  const row = executionRows.value.find((candidate) => candidate.span?.id === span.id || candidate.span?.spanId === span.spanId)
  if (row) selectExecutionRow(row.key)
}

function selectToolCall(tool: RunToolCall) {
  const row = executionRows.value.find((candidate) =>
    candidate.item.toolName === tool.toolName || candidate.span?.toolName === tool.toolName,
  )
  if (!row) return
  activeTab.value = 'execution'
  selectExecutionRow(row.key)
}

function spanForItem(item: RunExecutionPathItem) {
  return props.detail.spans.find((span) =>
    (item.spanId && (span.spanId === item.spanId || String(span.id) === item.spanId))
    || (item.nodeId && span.nodeId === item.nodeId && (!item.startedAt || span.startedAt === item.startedAt))
    || (item.toolName && span.toolName === item.toolName && (!item.startedAt || span.startedAt === item.startedAt)),
  )
}

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

function executionSubtitle(row: ExecutionRow) {
  const span = row.span
  const parts = [selectedRuntime(row)]
  if (span?.toolName && span.toolName !== row.title) parts.push(span.toolName)
  if (span?.metadata?.attempt != null || span?.metadata?.maxAttempts != null) {
    parts.push(`尝试 ${attemptLabel(span)}`)
  }
  if (span?.errorCode) parts.push(span.errorCode)
  return parts.join(' · ')
}

function attemptLabel(span?: RunSpan) {
  const attempt = numericMetadata(span?.metadata?.attempt)
  const maxAttempts = numericMetadata(span?.metadata?.maxAttempts)
  if (attempt == null && maxAttempts == null) return '—'
  return `${attempt ?? 1}/${maxAttempts ?? attempt ?? 1}`
}

function numericMetadata(value: unknown): number | undefined {
  const parsed = Number(value)
  return Number.isFinite(parsed) && parsed >= 0 ? parsed : undefined
}

function selectedRuntime(row: ExecutionRow) {
  return formatRuntimeTypeLabel(row.item.runtimeType || row.span?.runtimeType) || '未记录运行时'
}

function rowDuration(row: ExecutionRow) {
  if (row.span?.latencyMs != null) return formatDuration(row.span.latencyMs)
  const start = timestampValue(row.item.startedAt)
  const end = timestampValue(row.item.endedAt)
  if (start !== Number.MAX_SAFE_INTEGER && end !== Number.MAX_SAFE_INTEGER && end >= start) return formatDuration(end - start)
  return row.item.status === 'RUNNING' || String(row.item.status).startsWith('WAITING') ? '进行中' : '—'
}

function semanticDepth(span: RunSpan) {
  if (span.spanType === 'SUPERVISOR') return 0
  if (span.spanType === 'PLAN' || span.spanType === 'REPLAN') return 1
  if (span.spanType === 'WORKFLOW_TOOL') return 2
  return props.summary.runType === 'AGENT' ? 3 : 0
}

function phaseLabel(spanType?: string) {
  const labels: Record<string, string> = {
    SUPERVISOR: '调度器',
    PLAN: '规划',
    REPLAN: '重规划',
    WORKFLOW_TOOL: '工作流工具',
    APPROVAL: '审批',
    INTERACTION: '交互',
  }
  return spanType ? labels[spanType] || '工作流节点' : '工作流节点'
}

function phaseTagType(spanType?: string) {
  if (spanType === 'SUPERVISOR' || spanType === 'PLAN') return 'primary'
  if (spanType === 'REPLAN') return 'warning'
  if (spanType === 'WORKFLOW_TOOL') return 'success'
  return 'info'
}

function statusTagType(status?: string) {
  if (status === 'COMPLETED' || status === 'SUCCESS' || status === 'RECORDED') return 'success'
  if (status === 'RUNNING') return 'primary'
  if (status === 'SUSPENDED' || status === 'WAITING' || status === 'WAITING_APPROVAL' || status === 'WAITING_USER') return 'warning'
  if (status === 'CANCELLED') return 'info'
  return 'danger'
}

function statusTone(status?: string): StatusTone {
  if (status === 'COMPLETED' || status === 'SUCCESS' || status === 'RECORDED') return 'success'
  if (status === 'RUNNING') return 'primary'
  if (status === 'SUSPENDED' || status === 'WAITING' || status === 'WAITING_APPROVAL' || status === 'WAITING_USER') return 'warning'
  if (status === 'FAILED' || status === 'TIMED_OUT' || status === 'TIMEOUT') return 'danger'
  return 'info'
}

function isHealthyStatus(status?: string) {
  return ['COMPLETED', 'SUCCESS', 'RECORDED'].includes(status || '')
}

function executionStatusLabel(status?: string) {
  const labels: Record<string, string> = {
    RUNNING: '运行中',
    SUSPENDED: '已暂停',
    COMPLETED: '已完成',
    SUCCESS: '成功',
    FAILED: '失败',
    CANCELLED: '已取消',
    TIMED_OUT: '已超时',
    TIMEOUT: '已超时',
    WAITING: '等待中',
    WAITING_APPROVAL: '等待审批',
    WAITING_USER: '等待用户交互',
    RECORDED: '已记录',
    EXPIRED: '已过期',
  }
  return status ? labels[status] || status : '-'
}

function spanDisplayName(span: RunSpan) {
  const nodeName = typeof span.metadata?.nodeName === 'string' ? span.metadata.nodeName : undefined
  const workflowName = typeof span.metadata?.workflowName === 'string' ? span.metadata.workflowName : undefined
  const fallback = span.spanType === 'WORKFLOW_TOOL'
    ? span.toolName || workflowName || span.nodeId || '工作流工具'
    : span.nodeId || span.toolName || phaseLabel(span.spanType) || span.spanId || '-'
  return nodeName ? `${fallback} · ${nodeName}` : fallback
}

function supervisorEventLabel(span: RunSpan) {
  if (span.spanType === 'WORKFLOW_TOOL') {
    const version = span.metadata?.workflowVersion
    return `${span.toolName || span.nodeId || '工作流'}${version ? ` · ${version}` : ''}`
  }
  const planNo = span.metadata?.planNo
  return planNo ? `第 ${planNo} 次规划` : span.nodeId || phaseLabel(span.spanType)
}

function supervisorEventResultLabel(span: RunSpan) {
  const summaryText = executionSummaryLabel(span.outputSummary)
  if (!['PLAN', 'REPLAN'].includes(span.spanType || '') || !span.outputSummary?.startsWith('{')) return summaryText
  try {
    const result = JSON.parse(span.outputSummary) as Record<string, unknown>
    const planNo = Number(result.planNo || span.metadata?.planNo || 0)
    const stepCount = Number(result.stepCount || 0)
    const status = executionStatusLabel(typeof result.status === 'string' ? result.status : undefined)
    return `${status === '-' ? '' : status}${planNo > 0 ? `第 ${planNo} 次规划` : '规划'}${Number.isFinite(stepCount) ? `，共 ${stepCount} 个步骤` : ''}`
  } catch {
    return summaryText
  }
}

function guardDecisionLabel(decision?: string) {
  const labels: Record<string, string> = { ALLOW: '允许', DENY: '拒绝', WAITING_APPROVAL: '等待审批' }
  return decision ? labels[decision] || decision : '-'
}

function guardTagType(decision?: string) {
  if (decision === 'DENY') return 'danger'
  if (decision === 'WAITING_APPROVAL') return 'warning'
  return 'success'
}

function guardDecisionTypeLabel(decisionType?: string) {
  const labels: Record<string, string> = { SUPERVISOR_TOOL_POLICY: '调度工具策略' }
  return decisionType ? labels[decisionType] || decisionType : '-'
}

function guardTargetKindLabel(targetKind?: string) {
  const labels: Record<string, string> = { WORKFLOW_TOOL: '工作流工具' }
  return targetKind ? labels[targetKind] || targetKind : '-'
}

function formatDuration(ms?: number | null) {
  const value = Number(ms ?? 0)
  if (!Number.isFinite(value) || value <= 0) return '0 毫秒'
  if (value < 1000) return `${Math.round(value)} 毫秒`
  if (value < 60_000) return `${value >= 10_000 ? (value / 1000).toFixed(1) : (value / 1000).toFixed(2)} 秒`
  const minutes = Math.floor(value / 60_000)
  const seconds = (value % 60_000) / 1000
  return seconds > 0 ? `${minutes} 分 ${seconds.toFixed(1)} 秒` : `${minutes} 分钟`
}

function formatDateTime(value?: string | null) {
  if (!value) return '-'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return date.toLocaleString('zh-CN', { hour12: false })
}

function formatTimeOnly(value?: string | null) {
  if (!value) return '-'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return date.toLocaleTimeString('zh-CN', { hour12: false })
}

function timestampValue(value?: string | null) {
  if (!value) return Number.MAX_SAFE_INTEGER
  const timestamp = new Date(value).getTime()
  return Number.isNaN(timestamp) ? Number.MAX_SAFE_INTEGER : timestamp
}

function executionSummaryLabel(value?: string) {
  if (!value) return '-'
  if (value === '[omitted]') return '[已省略]'
  if (value === '[redacted]') return '[已脱敏]'
  return value
}

function payloadText(value?: string) {
  if (!value) return '-'
  try {
    return JSON.stringify(JSON.parse(value), null, 2)
  } catch {
    return executionSummaryLabel(value)
  }
}

function compactMessage(value?: string | null, fallback = '未记录详细信息。') {
  const text = value?.replace(/\s+/g, ' ').trim()
  if (!text || text === '[omitted]' || text === '[redacted]') return fallback
  return text.length > 220 ? `${text.slice(0, 217)}…` : text
}

function shortIdentifier(value?: string | null) {
  if (!value) return '-'
  if (value.length <= 24) return value
  return `${value.slice(0, 10)}…${value.slice(-8)}`
}

function pretty(value: unknown) {
  if (value == null) return '-'
  try {
    return JSON.stringify(value, null, 2)
  } catch {
    return String(value)
  }
}

function diffFieldLabel(field: string) {
  const labels: Record<string, string> = {
    runType: '运行类型', entryType: '运行入口', status: '运行状态', suspensionReason: '暂停原因',
    agentId: '智能体 ID', agentConfigVersionId: '智能体配置版本 ID', workflowId: '工作流 ID',
    workflowVersionId: '工作流版本 ID', runtimeType: '运行时类型', latencyMs: '耗时', tokenCost: 'Token 数',
    planCount: '规划次数', replanCount: '重规划次数', workflowCallCount: '工作流调用次数',
    toolCallCount: '工具调用次数', guardDenyCount: '治理拒绝次数', approvalCount: '审批次数', errorCode: '错误码',
  }
  return labels[field] || field
}

function displayDiffValue(field: string, value: unknown) {
  if (value == null || value === '') return '-'
  if (field === 'latencyMs') return formatDuration(Number(value))
  if (field === 'tokenCost') return Number(value).toLocaleString('zh-CN')
  if (field === 'status') return executionStatusLabel(String(value))
  if (field === 'runtimeType') return formatRuntimeTypeLabel(String(value))
  return String(value)
}

function spanDigest(span?: RunSpan) {
  if (!span) return '缺失'
  return `${executionStatusLabel(span.status)} · ${formatDuration(span.latencyMs)} · ${span.errorCode || executionSummaryLabel(span.outputSummary)}`
}

function toolDigest(tool?: RunToolCall) {
  if (!tool) return '缺失'
  return `${tool.success ? '成功' : '失败'} · ${formatDuration(tool.elapsedMs)} · ${tool.errorCode || tool.resultSummary || '-'}`
}

function guardDigest(guard?: RunGuardDecision) {
  if (!guard) return '缺失'
  return `${guardDecisionLabel(guard.decision)} · ${guard.reason || '-'}`
}
</script>

<style scoped lang="scss">
.investigation-workbench {
  display: flex;
  min-width: 0;
  height: clamp(490px, calc(100vh - 330px), 610px);
  height: clamp(490px, calc(100dvh - 330px), 610px);
  flex-direction: column;
  overflow: hidden;
  border: 1px solid color-mix(in srgb, var(--border-readable) 58%, transparent);
  border-radius: 12px;
  background: color-mix(in srgb, var(--surface-solid-overlay) 88%, transparent);
  box-shadow:
    0 18px 42px -34px rgb(15 23 42 / 0.46),
    inset 0 1px 0 color-mix(in srgb, var(--text-primary) 7%, transparent);
  -webkit-backdrop-filter: blur(18px) saturate(1.02);
  backdrop-filter: blur(18px) saturate(1.02);
}

.investigation-workbench__header {
  display: flex;
  min-height: 48px;
  flex: 0 0 auto;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 6px 12px;
  border-bottom: 1px solid var(--border-color);
  background: color-mix(in srgb, var(--surface-solid-panel) 84%, transparent);
}

.workbench-groups,
.workbench-group,
.workbench-subtabs,
.workbench-summary,
.filter-pills,
.run-conclusion-heading,
.inspector-tabs,
.inspector-actions,
.inspector-footnote,
.compare-banner {
  display: flex;
  align-items: center;
}

.workbench-groups {
  min-width: 0;
  gap: 5px;
  overflow-x: auto;
  scrollbar-width: none;
}

.workbench-groups::-webkit-scrollbar { display: none; }

.workbench-group {
  height: 34px;
  flex: 0 0 auto;
  gap: 2px;
  padding: 2px;
  border: 1px solid transparent;
  border-radius: 9px;
}

.workbench-group.active {
  border-color: color-mix(in srgb, var(--border-readable) 62%, transparent);
  background: color-mix(in srgb, var(--surface-solid-control) 92%, transparent);
  box-shadow: inset 0 1px 0 color-mix(in srgb, var(--text-primary) 7%, transparent);
}

.workbench-group__label,
.workbench-subtabs button {
  height: 28px;
  border: 0;
  border-radius: 7px;
  background: transparent;
  color: var(--text-secondary);
  cursor: pointer;
  font-size: 12px;
}

.workbench-group__label {
  padding: 0 10px;
  font-weight: 720;
}

.workbench-group.active > .workbench-group__label { color: var(--text-primary); }

.workbench-subtabs {
  gap: 2px;
  padding-left: 3px;
  border-left: 1px solid var(--border-divider);
}

.workbench-subtabs button {
  padding: 0 9px;
  color: var(--text-muted);
  font-size: 11px;
}

.workbench-subtabs button:hover { color: var(--brand-primary); }
.workbench-subtabs button.active {
  background: color-mix(in srgb, var(--brand-primary) 10%, var(--surface-solid-control));
  color: var(--brand-primary);
  font-weight: 720;
}

.workbench-summary {
  flex: 0 0 auto;
  gap: 8px;
  color: var(--text-muted);
  font-size: 12px;
  white-space: nowrap;
}

.workbench-summary span + span::before {
  content: '·';
  margin-right: 8px;
  color: var(--border-readable);
}

.workbench-summary strong { color: var(--status-danger); }
.workbench-summary strong.clear { color: var(--status-success); }

.investigation-workbench__body {
  display: grid;
  min-width: 0;
  min-height: 0;
  flex: 1 1 auto;
  grid-template-columns: minmax(0, 1fr) clamp(420px, 42%, 660px);
}

.workbench-main,
.node-inspector,
.execution-view,
.data-view {
  min-width: 0;
  min-height: 0;
}

.workbench-main {
  overflow: hidden;
  background: color-mix(in srgb, var(--surface-solid-panel) 68%, transparent);
}

.execution-view,
.data-view {
  height: 100%;
  overflow: auto;
}

.view-toolbar,
.view-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.view-toolbar {
  min-height: 40px;
  padding: 5px 12px;
  border-bottom: 1px solid var(--border-divider);
  background: color-mix(in srgb, var(--surface-solid-control) 58%, transparent);
}

.view-toolbar > div:first-child {
  display: flex;
  min-width: 0;
  align-items: baseline;
  gap: 8px;
}

.view-toolbar strong { font-size: 13px; }
.view-toolbar span,
.view-heading p { color: var(--text-muted); font-size: 12px; }

.filter-pills { gap: 6px; }
.filter-pills button {
  min-height: 26px;
  padding: 0 10px;
  border: 1px solid var(--border-color);
  border-radius: 999px;
  background: var(--surface-solid-control, var(--card-bg));
  color: var(--text-secondary);
  cursor: pointer;
  font-size: 12px;
}
.filter-pills button.active {
  border-color: color-mix(in srgb, var(--brand-primary) 45%, var(--border-color));
  background: color-mix(in srgb, var(--brand-primary) 9%, var(--card-bg));
  color: var(--brand-primary);
}

.execution-list { padding: 7px 9px 12px; }

.execution-row {
  --row-tone: var(--status-success);
  display: grid;
  width: 100%;
  min-width: 0;
  grid-template-columns: 24px minmax(0, 1fr) 82px 82px;
  align-items: center;
  gap: 8px;
  padding: 7px 9px 7px calc(9px + var(--execution-depth) * 13px);
  border: 1px solid transparent;
  border-radius: 8px;
  background: transparent;
  color: inherit;
  text-align: left;
  cursor: pointer;
}

.execution-row:hover { background: color-mix(in srgb, var(--brand-primary) 5%, transparent); }
.execution-row.selected {
  border-color: transparent;
  background: color-mix(in srgb, var(--row-tone) 8%, var(--surface-solid-control));
  box-shadow:
    inset 3px 0 0 var(--row-tone),
    inset 0 0 0 1px color-mix(in srgb, var(--row-tone) 16%, transparent);
}
.execution-row.is-danger { --row-tone: var(--status-danger); }
.execution-row.is-warning { --row-tone: var(--status-warning); }
.execution-row.is-primary { --row-tone: var(--brand-primary); }
.execution-row.is-info { --row-tone: var(--status-info); }
.execution-row.is-danger:not(.selected) {
  background: color-mix(in srgb, var(--status-danger) 3%, transparent);
}
.execution-row.is-success:not(.selected) .execution-title strong {
  color: var(--text-secondary);
  font-weight: 620;
}

.execution-rail {
  position: relative;
  display: flex;
  height: 100%;
  align-items: center;
  justify-content: center;
}
.execution-rail::before,
.execution-rail::after {
  content: '';
  position: absolute;
  left: 50%;
  width: 1px;
  height: calc(50% + 9px);
  background: var(--border-color);
}
.execution-rail::before { bottom: 50%; }
.execution-rail::after { top: 50%; }
.execution-row:first-child .execution-rail::before,
.execution-row:last-child .execution-rail::after { display: none; }
.execution-rail i {
  position: relative;
  z-index: 1;
  width: 10px;
  height: 10px;
  border: 2px solid var(--card-bg);
  border-radius: 50%;
  background: var(--row-tone);
  box-shadow: 0 0 0 1px color-mix(in srgb, var(--row-tone) 45%, transparent);
}

.execution-copy,
.execution-title { min-width: 0; }
.execution-copy { display: block; }
.execution-title { display: flex; align-items: center; gap: 7px; }
.execution-title strong,
.execution-copy small {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.execution-title strong { color: var(--text-primary); font-size: 13px; }
.execution-title em {
  flex: 0 0 auto;
  padding: 2px 6px;
  border-radius: 4px;
  background: color-mix(in srgb, var(--brand-primary) 7%, var(--card-bg));
  color: var(--text-muted);
  font-size: 9px;
  font-style: normal;
  font-weight: 700;
  text-transform: uppercase;
}
.execution-copy small {
  display: block;
  margin-top: 3px;
  color: var(--text-muted);
  font-family: var(--font-mono, Consolas, monospace);
  font-size: 10px;
}
.execution-duration { color: var(--text-secondary); font-size: 11px; text-align: right; }
.execution-status {
  justify-self: end;
  padding: 2px 6px;
  border-radius: 999px;
  background: transparent;
  color: var(--status-success);
  font-size: 10px;
  font-weight: 700;
}
.execution-status.is-danger { background: color-mix(in srgb, var(--status-danger) 10%, var(--surface-solid-control)); color: var(--status-danger); }
.execution-status.is-warning { background: color-mix(in srgb, var(--status-warning) 12%, var(--card-bg)); color: var(--status-warning); }
.execution-status.is-primary { background: color-mix(in srgb, var(--brand-primary) 10%, var(--card-bg)); color: var(--brand-primary); }
.execution-status.is-info { background: color-mix(in srgb, var(--status-info) 10%, var(--card-bg)); color: var(--status-info); }

.node-inspector {
  display: flex;
  flex-direction: column;
  overflow: hidden;
  border-left: 1px solid var(--border-color);
  background: color-mix(in srgb, var(--surface-solid-overlay) 88%, transparent);
}
.node-inspector__header { padding: 12px 14px 9px; border-bottom: 1px solid var(--border-divider); }
.node-inspector__header > div { display: flex; align-items: center; justify-content: space-between; gap: 8px; color: var(--text-muted); font-size: 11px; }
.node-inspector__header h2 { margin: 6px 0 2px; color: var(--text-primary); font-size: 17px; line-height: 1.3; }
.node-inspector__header > code { color: var(--text-muted); font-size: 10px; overflow-wrap: anywhere; }

.node-metrics {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  margin: 0;
  border-bottom: 1px solid var(--border-divider);
}
.node-metrics div { min-width: 0; padding: 8px 9px; border-right: 1px solid var(--border-divider); }
.node-metrics div:last-child { border-right: 0; }
.node-metrics dt,
.node-metrics dd { margin: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.node-metrics dt { color: var(--text-muted); font-size: 9px; }
.node-metrics dd { margin-top: 4px; color: var(--text-primary); font-size: 10px; font-weight: 700; }

.inspector-tabs { min-height: 38px; padding: 0 16px; gap: 18px; border-bottom: 1px solid var(--border-divider); }
.inspector-tabs button {
  position: relative;
  height: 100%;
  padding: 0;
  border: 0;
  background: transparent;
  color: var(--text-secondary);
  cursor: pointer;
  font-size: 12px;
}
.inspector-tabs button::after { content: ''; position: absolute; right: 0; bottom: 0; left: 0; height: 2px; background: transparent; }
.inspector-tabs button.active { color: var(--brand-primary); font-weight: 700; }
.inspector-tabs button.active::after { background: var(--brand-primary); }

.node-inspector__content { min-height: 0; flex: 1 1 auto; overflow: auto; padding: 13px 14px; }
.node-diagnostic { --diagnostic-tone: var(--status-success); padding: 10px 11px; border: 0; border-left: 3px solid var(--diagnostic-tone); border-radius: 0 7px 7px 0; background: color-mix(in srgb, var(--diagnostic-tone) 5%, var(--surface-solid-control)); }
.node-diagnostic.is-danger { --diagnostic-tone: var(--status-danger); }
.node-diagnostic.is-warning { --diagnostic-tone: var(--status-warning); }
.node-diagnostic.is-primary { --diagnostic-tone: var(--brand-primary); }
.node-diagnostic.is-info { --diagnostic-tone: var(--status-info); }
.node-diagnostic strong { display: block; color: var(--diagnostic-tone); font-size: 13px; }
.node-diagnostic code { display: block; margin-top: 5px; color: var(--diagnostic-tone); font-size: 10px; overflow-wrap: anywhere; }
.node-diagnostic p { margin: 8px 0 0; color: var(--text-secondary); font-size: 11px; line-height: 1.55; }
.diagnosis-view > h3,
.evidence-view > h3 { margin: 14px 0 8px; color: var(--text-primary); font-size: 12px; }
.next-steps { display: grid; gap: 0; margin: 0; padding: 0; list-style: none; counter-reset: steps; }
.next-steps li { position: relative; padding: 8px 6px 8px 28px; border-bottom: 1px solid var(--border-divider); color: var(--text-secondary); font-size: 11px; line-height: 1.45; counter-increment: steps; }
.next-steps li::before { content: counter(steps); position: absolute; top: 8px; left: 4px; display: grid; width: 15px; height: 15px; place-items: center; border-radius: 50%; background: color-mix(in srgb, var(--brand-primary) 8%, var(--surface-solid-control)); color: var(--brand-primary); font-size: 9px; font-weight: 700; }
.inspector-actions { gap: 8px; margin-top: 12px; }
.inspector-actions :deep(.el-button) { flex: 0 0 auto; margin-left: 0; }
.inspector-footnote { justify-content: space-between; gap: 10px; margin-top: 12px; padding-top: 10px; border-top: 1px solid var(--border-divider); color: var(--text-muted); font-size: 10px; }

.payload-view { display: grid; gap: 10px; }
.payload-view article { overflow: hidden; border: 1px solid var(--border-color); border-radius: 9px; background: var(--card-bg); }
.payload-view h3 { display: flex; justify-content: space-between; margin: 0; padding: 8px 10px; border-bottom: 1px solid var(--border-divider); color: var(--text-secondary); font-size: 11px; }
.payload-view h3 small { color: var(--text-muted); font-family: var(--font-mono, Consolas, monospace); font-weight: 500; }
.payload-view pre,
.evidence-view pre,
.snapshot-accordion pre { margin: 0; overflow: auto; color: var(--text-secondary); font-family: var(--font-mono, Consolas, monospace); font-size: 10px; line-height: 1.55; white-space: pre-wrap; overflow-wrap: anywhere; }
.payload-view pre { max-height: 170px; padding: 10px; }

.evidence-view dl { display: grid; gap: 7px; margin: 0; }
.evidence-view dl div { display: grid; min-width: 0; grid-template-columns: 86px minmax(0, 1fr); align-items: center; gap: 8px; padding-bottom: 7px; border-bottom: 1px solid var(--border-divider); }
.evidence-view dt,
.evidence-view dd { min-width: 0; margin: 0; }
.evidence-view dt { color: var(--text-muted); font-size: 10px; }
.evidence-view dd { overflow: hidden; color: var(--text-secondary); font-size: 10px; text-overflow: ellipsis; white-space: nowrap; }
.evidence-view details { margin-top: 12px; }
.evidence-view summary { color: var(--brand-primary); cursor: pointer; font-size: 11px; font-weight: 700; }
.evidence-view pre { margin-top: 8px; padding: 9px; border-radius: 8px; background: var(--surface-solid-control, var(--page-bg)); }

.data-view { padding: 14px; }
.view-heading { margin-bottom: 12px; }
.view-heading h2,
.view-heading p { margin: 0; }
.view-heading h2 { color: var(--text-primary); font-size: 15px; }
.view-heading p { margin-top: 4px; }
.metric-strip { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 8px; margin-bottom: 12px; }
.metric-strip > div { min-width: 0; padding: 10px 12px; border: 1px solid var(--border-color); border-radius: 9px; background: color-mix(in srgb, var(--brand-primary) 3%, var(--card-bg)); }
.metric-strip span,
.metric-strip small { display: block; overflow: hidden; color: var(--text-muted); font-size: 10px; text-overflow: ellipsis; white-space: nowrap; }
.metric-strip strong { display: block; margin: 4px 0 2px; color: var(--text-primary); font-size: 16px; }
.evidence-table { cursor: pointer; }

.span-card { display: block; width: 100%; padding: 11px 12px; border: 1px solid var(--border-color); border-radius: 9px; background: var(--card-bg); color: inherit; text-align: left; cursor: pointer; }
.span-card:hover { border-color: color-mix(in srgb, var(--brand-primary) 42%, var(--border-color)); }
.span-card__head { display: flex; align-items: flex-start; justify-content: space-between; gap: 10px; }
.span-card__head > span:first-child { min-width: 0; }
.span-card__head strong,
.span-card__head small { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.span-card__head strong { color: var(--text-primary); font-size: 12px; }
.span-card__head small { margin-top: 4px; color: var(--text-muted); font-size: 10px; }
.span-card__tags { display: flex; flex: 0 0 auto; gap: 6px; }
.span-card__error { display: block; margin-top: 8px; color: var(--status-danger); font-size: 10px; }

.workbench-empty { display: grid; min-height: 300px; place-items: center; align-content: center; text-align: center; }
.workbench-empty > span { display: grid; width: 40px; height: 40px; place-items: center; border-radius: 50%; background: color-mix(in srgb, var(--status-info) 10%, var(--card-bg)); color: var(--status-info); font-size: 18px; }
.workbench-empty > strong { margin-top: 9px; color: var(--text-primary); font-size: 13px; }
.workbench-empty > p { max-width: 340px; margin: 5px 0 0; color: var(--text-muted); font-size: 11px; line-height: 1.55; }
.workbench-empty.is-success > span { background: color-mix(in srgb, var(--status-success) 10%, var(--card-bg)); color: var(--status-success); }

.snapshot-accordion { display: grid; gap: 8px; }
.snapshot-accordion details { overflow: hidden; border: 1px solid var(--border-color); border-radius: 9px; background: var(--card-bg); }
.snapshot-accordion summary { padding: 10px 12px; color: var(--text-primary); cursor: pointer; font-size: 12px; font-weight: 700; }
.snapshot-accordion summary small { margin-left: 8px; color: var(--text-muted); font-family: var(--font-mono, Consolas, monospace); font-size: 9px; font-weight: 500; }
.snapshot-accordion pre { max-height: 260px; padding: 11px 12px; border-top: 1px solid var(--border-divider); background: var(--surface-solid-control, var(--page-bg)); }

.compare-banner { justify-content: center; gap: 12px; padding: 10px; border: 1px solid var(--border-color); border-radius: 9px; background: color-mix(in srgb, var(--brand-primary) 4%, var(--card-bg)); color: var(--text-secondary); font-size: 11px; }
.compare-banner code { color: var(--text-primary); }
.compare-banner strong { color: var(--brand-primary); }
.diff-metrics { display: grid; grid-template-columns: repeat(auto-fit, minmax(150px, 1fr)); gap: 8px; margin: 10px 0; }
.diff-metrics > div { min-width: 0; padding: 9px 10px; border: 1px solid var(--border-color); border-radius: 8px; }
.diff-metrics span { display: block; color: var(--text-muted); font-size: 9px; }
.diff-metrics strong { display: block; margin-top: 4px; overflow: hidden; color: var(--text-primary); font-size: 11px; text-overflow: ellipsis; white-space: nowrap; }
.diff-metrics em { margin: 0 3px; color: var(--brand-primary); font-style: normal; }
.diff-empty { color: var(--text-muted); font-size: 11px; }

@media (max-width: 1180px) {
  .investigation-workbench__body { grid-template-columns: minmax(0, 1fr) 390px; }
  .workbench-summary span { display: none; }
}

@media (max-width: 900px) {
  .investigation-workbench { height: auto; min-height: 760px; }
  .investigation-workbench__body { grid-template-columns: minmax(0, 1fr); }
  .workbench-main { min-height: 430px; }
  .node-inspector { min-height: 420px; border-top: 1px solid var(--border-color); border-left: 0; }
  .investigation-workbench__header { align-items: stretch; flex-direction: column; gap: 0; }
  .workbench-groups { min-height: 42px; }
  .workbench-summary { min-height: 32px; justify-content: flex-end; }
  .workbench-summary span { display: inline; }
}

@media (max-width: 640px) {
  .workbench-summary span { display: none; }
  .execution-row { grid-template-columns: 20px minmax(0, 1fr) 66px; }
  .execution-duration { display: none; }
  .view-toolbar { align-items: flex-start; flex-direction: column; }
  .metric-strip { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .node-metrics { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .node-metrics div:nth-child(2) { border-right: 0; }
  .node-metrics div:nth-child(-n + 2) { border-bottom: 1px solid var(--border-divider); }
}
</style>
