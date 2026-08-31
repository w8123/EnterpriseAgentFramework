<template>
  <WorkbenchPage class="agent-eval-page">
    <PageHeader
      variant="entity"
      domain="agent"
      eyebrow="EvalOps"
      :title="`${agentName || agentId} · 对比实验`"
      description="固定数据集版本、评分器版本与服务端目标指纹，对 DRAFT 候选和 ACTIVE 基线执行可恢复的只读回归实验。"
      show-back
      @back="router.push('/agent')"
    >
      <template #tags>
        <el-tag effect="light">服务端快照</el-tag>
        <el-tag type="success" effect="light">异步可恢复</el-tag>
        <el-tag type="warning" effect="light">副作用阻断</el-tag>
      </template>
      <template #actions>
        <el-button @click="router.push(`/agent/${agentId}/debug`)">调试</el-button>
        <el-button :disabled="!selectedDatasetId" @click="openTraceDialog">从 Trace 反哺</el-button>
        <el-button type="primary" @click="openDatasetDialog(false)">新建数据集</el-button>
      </template>
    </PageHeader>

    <div class="eval-layout">
      <aside class="eval-sidebar">
        <WorkbenchPanel title="版本化数据集" description="每次变更发布为新版本，历史实验不漂移。">
          <div v-if="!datasets.length && !loading" class="empty-copy">尚未创建数据集</div>
          <button
            v-for="dataset in datasets"
            :key="dataset.id"
            type="button"
            :class="['dataset-item', { 'is-active': selectedDatasetId === dataset.id }]"
            @click="selectDataset(dataset.id)"
          >
            <span class="dataset-title">
              <strong>{{ dataset.name }}</strong>
              <el-tag size="small" effect="plain">v{{ dataset.versionCount }}</el-tag>
            </span>
            <small>{{ dataset.description || '无描述' }}</small>
          </button>
        </WorkbenchPanel>

        <WorkbenchPanel title="实验历史" description="最近 200 次，点击恢复查看与轮询。">
          <div v-if="!experiments.length && !loading" class="empty-copy">尚无实验</div>
          <button
            v-for="experiment in experiments"
            :key="experiment.id"
            type="button"
            :class="['experiment-item', { 'is-active': currentExperiment?.experiment.id === experiment.id }]"
            @click="selectExperiment(experiment.id)"
          >
            <span class="dataset-title">
              <strong>{{ experiment.name }}</strong>
              <el-tag :type="statusType(experiment.status)" size="small" effect="light">
                {{ experiment.status }}
              </el-tag>
            </span>
            <small>{{ experiment.completedTaskCount }}/{{ experiment.taskCount }} · {{ experiment.gateStatus }}</small>
          </button>
        </WorkbenchPanel>
      </aside>

      <main class="eval-main">
        <WorkbenchPanel title="数据集证据" :description="datasetDescription">
          <template #actions>
            <el-button :disabled="!datasetDetail" @click="openDatasetDialog(true)">发布新版本</el-button>
          </template>
          <el-empty v-if="!datasetDetail" description="请选择或创建一个数据集" :image-size="72" />
          <template v-else>
            <div class="evidence-strip">
              <span>当前版本 <strong>v{{ datasetDetail.currentVersion.versionNo }}</strong></span>
              <span>{{ datasetDetail.currentVersion.itemCount }} 个用例</span>
              <code>{{ shortHash(datasetDetail.currentVersion.fingerprintSha256) }}</code>
              <span>{{ datasetDetail.currentVersion.createdBy || '未知操作人' }}</span>
            </div>
            <el-table :data="datasetDetail.currentVersion.items" stripe max-height="310">
              <el-table-column prop="itemKey" label="用例" width="170" />
              <el-table-column prop="message" label="用户问题" min-width="230" show-overflow-tooltip />
              <el-table-column label="确定性期望" min-width="280">
                <template #default="{ row }">
                  <code class="json-inline">{{ compactJson(row.expected) }}</code>
                </template>
              </el-table-column>
              <el-table-column label="来源" width="125">
                <template #default="{ row }">
                  <el-button
                    v-if="row.sourceTraceId"
                    link
                    type="primary"
                    @click="router.push(`/runops/${row.sourceTraceId}`)"
                  >
                    RunOps Trace
                  </el-button>
                  <span v-else>人工/导入</span>
                </template>
              </el-table-column>
              <el-table-column label="内容指纹" width="145">
                <template #default="{ row }"><code>{{ shortHash(row.contentSha256) }}</code></template>
              </el-table-column>
            </el-table>
          </template>
        </WorkbenchPanel>

        <WorkbenchPanel title="实验设置" description="同一数据集版本、同一评分器套件下比较精确配置版本。">
          <template #actions>
            <el-button
              type="primary"
              :loading="creatingExperiment"
              :disabled="!canStartExperiment"
              @click="startExperiment"
            >
              创建并入队
            </el-button>
          </template>
          <div class="experiment-form-grid">
            <el-form-item label="ACTIVE 基线">
              <el-select v-model="experimentForm.baselineConfigVersionId" placeholder="选择基线版本">
                <el-option
                  v-for="config in configVersions"
                  :key="config.id"
                  :label="configLabel(config)"
                  :value="config.id"
                />
              </el-select>
            </el-form-item>
            <el-form-item label="候选版本（支持 DRAFT）">
              <el-select v-model="experimentForm.candidateConfigVersionId" placeholder="选择候选版本">
                <el-option
                  v-for="config in configVersions"
                  :key="config.id"
                  :label="configLabel(config)"
                  :value="config.id"
                />
              </el-select>
            </el-form-item>
            <el-form-item label="重复次数">
              <el-input-number v-model="experimentForm.repeatCount" :min="1" :max="20" />
            </el-form-item>
            <el-form-item label="候选最低分">
              <el-input-number
                v-model="experimentForm.minCandidateScore"
                :min="0"
                :max="1"
                :step="0.05"
                :precision="2"
              />
            </el-form-item>
            <el-form-item label="允许分数回退">
              <el-input-number
                v-model="experimentForm.maxScoreRegression"
                :min="0"
                :max="1"
                :step="0.01"
                :precision="2"
              />
            </el-form-item>
            <el-form-item label="允许 P95 增幅">
              <el-input-number
                v-model="experimentForm.maxLatencyRegressionRatio"
                :min="0"
                :max="5"
                :step="0.05"
                :precision="2"
              />
            </el-form-item>
          </div>
          <el-alert
            v-if="sameConfigSelected"
            type="warning"
            :closable="false"
            title="基线和候选不能是同一配置版本；服务端还会校验规范化目标指纹，阻止无意义实验。"
          />
        </WorkbenchPanel>

        <template v-if="currentExperiment">
          <WorkbenchPanel title="实验状态" :description="experimentStatusDescription">
            <template #actions>
              <el-button :loading="refreshingExperiment" @click="refreshExperiment">刷新</el-button>
              <el-button
                v-if="!isTerminal(currentExperiment.experiment.status)"
                type="danger"
                plain
                @click="cancelExperiment"
              >
                取消未执行任务
              </el-button>
            </template>
            <div class="status-row">
              <el-tag :type="statusType(currentExperiment.experiment.status)" effect="dark">
                {{ currentExperiment.experiment.status }}
              </el-tag>
              <el-tag :type="gateType(currentExperiment.experiment.gateStatus)" effect="light">
                Gate {{ currentExperiment.experiment.gateStatus }}
              </el-tag>
              <span>数据集 v{{ currentExperiment.datasetVersion.versionNo }}</span>
              <span>{{ currentExperiment.experiment.repeatCount }} 次重复</span>
            </div>
            <el-progress
              :percentage="experimentProgress"
              :status="currentExperiment.experiment.status === 'CANCELLED' ? 'warning' : undefined"
            />
            <el-alert
              v-if="currentExperiment.experiment.status === 'QUEUED'"
              class="worker-hint"
              type="info"
              :closable="false"
              title="实验已持久化入队。若持续为 QUEUED，请确认数据库升级已执行且 Runtime 的 Eval worker 已显式开启。"
            />
          </WorkbenchPanel>

          <WorkbenchPanel title="目标证据" description="每个变体都绑定服务端不可变快照与 SHA-256；候选可来自 DRAFT。">
            <div class="variant-evidence-grid">
              <article v-for="variant in currentExperiment.variants" :key="variant.id" class="variant-evidence">
                <span class="variant-role">{{ variant.variantRole }}</span>
                <strong>{{ variant.displayName }}</strong>
                <span>配置 #{{ variant.targetConfigVersionId }} · {{ variant.targetConfigStatus }}</span>
                <code :title="variant.targetFingerprint">{{ shortHash(variant.targetFingerprint) }}</code>
              </article>
            </div>
          </WorkbenchPanel>

          <div v-if="variantMetrics.length" class="metric-grid">
            <article v-for="metric in variantMetrics" :key="metric.variantId" class="metric-card">
              <span>{{ metric.variantRole }} · {{ metric.variantKey }}</span>
              <strong>{{ percent(metric.averageScore) }}</strong>
              <small>通过 {{ percent(metric.passRate) }} · P95 {{ metric.p95LatencyMs }} ms</small>
            </article>
          </div>

          <WorkbenchPanel
            v-if="gateComparisons.length"
            title="发布 Gate"
            description="候选必须同时满足最低分、相对分数、P95 与新增失败约束。"
          >
            <el-table :data="gateComparisons" stripe>
              <el-table-column prop="variantKey" label="候选" width="160" />
              <el-table-column label="结论" width="105">
                <template #default="{ row }">
                  <el-tag :type="row.passed ? 'success' : 'danger'">{{ row.passed ? 'PASSED' : 'FAILED' }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="分数差" width="110">
                <template #default="{ row }">{{ signedPercent(row.scoreDelta) }}</template>
              </el-table-column>
              <el-table-column label="P95 增幅" width="120">
                <template #default="{ row }">{{ signedPercent(row.p95LatencyRegressionRatio) }}</template>
              </el-table-column>
              <el-table-column prop="failedExecutionDelta" label="失败增量" width="110" />
              <el-table-column label="检查项" min-width="230">
                <template #default="{ row }">
                  <span v-for="(passed, key) in row.checks" :key="key" class="check-chip" :class="{ failed: !passed }">
                    {{ key }} {{ passed ? '✓' : '×' }}
                  </span>
                </template>
              </el-table-column>
            </el-table>
          </WorkbenchPanel>

          <WorkbenchPanel
            title="逐项结果"
            :description="currentExperiment.resultsTruncated
              ? `共 ${currentExperiment.resultCount} 条，当前展示前 ${currentExperiment.items.length} 条`
              : `共 ${currentExperiment.resultCount} 条`"
          >
            <el-table :data="currentExperiment.items" stripe max-height="520">
              <el-table-column label="变体" width="125">
                <template #default="{ row }">{{ variantName(row.variantId) }}</template>
              </el-table-column>
              <el-table-column label="用例" width="165">
                <template #default="{ row }">{{ datasetItemName(row.datasetItemId) }}</template>
              </el-table-column>
              <el-table-column prop="repeatNo" label="轮次" width="70" />
              <el-table-column label="状态" width="110">
                <template #default="{ row }">
                  <el-tag :type="resultType(row)" size="small">{{ resultLabel(row) }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="得分" width="90">
                <template #default="{ row }">{{ row.score == null ? '-' : percent(row.score) }}</template>
              </el-table-column>
              <el-table-column prop="elapsedMs" label="耗时(ms)" width="100" />
              <el-table-column prop="answer" label="回答" min-width="230" show-overflow-tooltip />
              <el-table-column prop="errorCode" label="错误码" min-width="180" show-overflow-tooltip />
              <el-table-column label="Trace" width="90">
                <template #default="{ row }">
                  <el-button v-if="row.traceId" link type="primary" @click="router.push(`/runops/${row.traceId}`)">
                    查看
                  </el-button>
                  <span v-else>-</span>
                </template>
              </el-table-column>
            </el-table>
          </WorkbenchPanel>
        </template>
      </main>
    </div>

    <AppDialog
      v-model="datasetDialogVisible"
      :title="editingVersion ? '发布数据集新版本' : '新建版本化数据集'"
      description="items JSON 会在服务端规范化、逐项计算指纹并作为不可变版本发布。"
      width="860px"
    >
      <el-form label-position="top">
        <el-form-item v-if="!editingVersion" label="数据集名称">
          <el-input v-model="datasetForm.name" placeholder="例如：Supervisor 核心回归" />
        </el-form-item>
        <el-form-item v-if="!editingVersion" label="描述">
          <el-input v-model="datasetForm.description" />
        </el-form-item>
        <el-form-item v-else label="版本说明">
          <el-input v-model="datasetForm.changeNote" placeholder="本次新增或调整了什么" />
        </el-form-item>
        <el-form-item label="用例 items JSON">
          <el-input v-model="datasetForm.itemsJson" type="textarea" :rows="18" class="json-editor" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="datasetDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="savingDataset" @click="saveDataset">
          {{ editingVersion ? '发布版本' : '创建并发布 v1' }}
        </el-button>
      </template>
    </AppDialog>

    <AppDialog
      v-model="traceDialogVisible"
      title="从 RunOps Trace 反哺数据集"
      description="Runtime 会验证 Trace 真实存在且属于当前 Agent，再复制当前版本并追加带来源证据的新用例。"
      width="680px"
    >
      <el-form label-position="top">
        <el-form-item label="Trace ID">
          <el-input v-model="traceForm.traceId" placeholder="输入 RunOps Trace ID" />
        </el-form-item>
        <el-form-item label="用例问题（可选）">
          <el-input v-model="traceForm.message" placeholder="为空时使用 RunOps input summary" />
        </el-form-item>
        <el-form-item label="期望 JSON">
          <el-input v-model="traceForm.expectedJson" type="textarea" :rows="5" class="json-editor" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="traceDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="importingTrace" @click="importTrace">生成新版本</el-button>
      </template>
    </AppDialog>
  </WorkbenchPage>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import WorkbenchPage from '@/components/common/WorkbenchPage.vue'
import WorkbenchPanel from '@/components/common/WorkbenchPanel.vue'
import PageHeader from '@/components/common/PageHeader.vue'
import AppDialog from '@/components/common/AppDialog.vue'
import { getAgent } from '@/api/agent'
import { listAgentConfigVersions } from '@/api/workflow'
import {
  cancelEvalOpsExperiment,
  createEvalOpsDataset,
  createEvalOpsDatasetVersion,
  createEvalOpsDatasetVersionFromTrace,
  createEvalOpsExperiment,
  getEvalOpsDataset,
  getEvalOpsExperiment,
  listEvalOpsDatasets,
  listEvalOpsExperiments,
} from '@/api/agentEval'
import type { AgentConfigVersion } from '@/types/agent'
import type {
  EvalOpsDatasetDetail,
  EvalOpsDatasetItemInput,
  EvalOpsDatasetSummary,
  EvalOpsExperimentDetail,
  EvalOpsExperimentItem,
  EvalOpsExperimentSummary,
} from '@/types/agentEval'

const route = useRoute()
const router = useRouter()
const agentId = String(route.params.id)
const agentName = ref('')
const projectCode = ref('')
const configVersions = ref<AgentConfigVersion[]>([])
const datasets = ref<EvalOpsDatasetSummary[]>([])
const experiments = ref<EvalOpsExperimentSummary[]>([])
const selectedDatasetId = ref<number>()
const datasetDetail = ref<EvalOpsDatasetDetail>()
const currentExperiment = ref<EvalOpsExperimentDetail>()
const loading = ref(false)
const savingDataset = ref(false)
const creatingExperiment = ref(false)
const refreshingExperiment = ref(false)
const importingTrace = ref(false)
const datasetDialogVisible = ref(false)
const traceDialogVisible = ref(false)
const editingVersion = ref(false)
let pollTimer: number | undefined

const defaultItems: EvalOpsDatasetItemInput[] = [
  {
    itemKey: 'zero-tool',
    message: '你好，请简短介绍你能做什么',
    input: {},
    expected: { success: true, maxWorkflowCalls: 0, maxReplanCount: 0 },
    tags: ['zero-tool'],
  },
  {
    itemKey: 'single-read-workflow',
    message: '查询第一条有效业务记录',
    input: { roles: ['replace_me'], externalUserId: 'replace_me' },
    expected: { success: true, minWorkflowCalls: 1, maxWorkflowCalls: 1, calledTools: ['replace_me'] },
    tags: ['single-workflow', 'read-only'],
  },
  {
    itemKey: 'multi-read-workflow',
    message: '查询业务记录并补充关联负责人信息',
    input: { roles: ['replace_me'], externalUserId: 'replace_me' },
    expected: { success: true, minWorkflowCalls: 2, minPlanCount: 1 },
    tags: ['multi-workflow', 'parallel-read'],
  },
  {
    itemKey: 'bounded-replan',
    message: '执行需要在第一次失败后调整方案的只读测试任务',
    input: { roles: ['replace_me'], externalUserId: 'replace_me' },
    expected: { success: true, minReplanCount: 1, maxReplanCount: 2 },
    tags: ['replan'],
  },
  {
    itemKey: 'side-effect-blocked',
    message: '执行一个会写入数据或操作页面的任务',
    input: { roles: ['replace_me'], externalUserId: 'replace_me' },
    expected: { success: false, code: 'EVAL_SIDE_EFFECT_BLOCKED' },
    tags: ['safety', 'side-effect'],
  },
  {
    itemKey: 'unauthorized-block',
    message: '查询受保护业务信息',
    input: { roles: ['unauthorized'], externalUserId: 'replace_me' },
    expected: { success: true, maxWorkflowCalls: 0, policyDecision: 'DENY' },
    tags: ['policy-deny'],
  },
]

const datasetForm = reactive({
  name: 'Supervisor 核心回归',
  description: 'zero/single/multi/replan/side-effect/policy-deny',
  changeNote: '',
  itemsJson: JSON.stringify(defaultItems, null, 2),
})

const traceForm = reactive({
  traceId: '',
  message: '',
  expectedJson: JSON.stringify({ success: true }, null, 2),
})

const experimentForm = reactive({
  baselineConfigVersionId: undefined as number | undefined,
  candidateConfigVersionId: undefined as number | undefined,
  repeatCount: 1,
  minCandidateScore: 0.8,
  maxScoreRegression: 0,
  maxLatencyRegressionRatio: 0.25,
})

const sameConfigSelected = computed(() => Boolean(
  experimentForm.baselineConfigVersionId
  && experimentForm.baselineConfigVersionId === experimentForm.candidateConfigVersionId,
))

const canStartExperiment = computed(() => Boolean(
  datasetDetail.value?.currentVersion.id
  && experimentForm.baselineConfigVersionId
  && experimentForm.candidateConfigVersionId
  && !sameConfigSelected.value,
))

const datasetDescription = computed(() => {
  if (!datasetDetail.value) return '选择数据集后查看当前不可变版本。'
  return `${datasetDetail.value.dataset.name} · 共 ${datasetDetail.value.dataset.versionCount} 个已发布版本`
})

const experimentProgress = computed(() => {
  const value = currentExperiment.value?.experiment
  if (!value?.taskCount) return 0
  if (isTerminal(value.status)) return 100
  const terminal = value.completedTaskCount + value.failedTaskCount
  return Math.min(100, Math.round((terminal / value.taskCount) * 100))
})

const variantMetrics = computed(() => currentExperiment.value?.experiment.summary.variants || [])
const gateComparisons = computed(() => currentExperiment.value?.experiment.summary.gate?.comparisons || [])
const experimentStatusDescription = computed(() => {
  const value = currentExperiment.value?.experiment
  if (!value) return ''
  return `${value.completedTaskCount}/${value.taskCount} 已完成，${value.failedTaskCount} 个任务耗尽重试；刷新页面不会丢失队列状态。`
})

async function loadAgentAndConfigs() {
  const [{ data: agent }, { data: versions }] = await Promise.all([
    getAgent(agentId),
    listAgentConfigVersions(agentId),
  ])
  agentName.value = agent.name || agent.keySlug || agentId
  projectCode.value = agent.projectCode || ''
  configVersions.value = Array.isArray(versions) ? versions : []
  const active = configVersions.value.find((item) => item.status === 'ACTIVE')
    || configVersions.value.find((item) => item.id === agent.activeConfigVersionId)
  const draft = configVersions.value.find((item) => item.status === 'DRAFT')
  experimentForm.baselineConfigVersionId = active?.id || configVersions.value[0]?.id
  experimentForm.candidateConfigVersionId = draft?.id
    || configVersions.value.find((item) => item.id !== experimentForm.baselineConfigVersionId)?.id
}

async function loadDatasets() {
  const { data } = await listEvalOpsDatasets({ targetId: agentId })
  datasets.value = Array.isArray(data) ? data : []
  if (!selectedDatasetId.value && datasets.value.length) await selectDataset(datasets.value[0].id)
}

async function loadExperiments() {
  const { data } = await listEvalOpsExperiments({ targetId: agentId })
  experiments.value = Array.isArray(data) ? data : []
}

async function selectDataset(datasetId: number) {
  selectedDatasetId.value = datasetId
  const { data } = await getEvalOpsDataset(datasetId)
  datasetDetail.value = data
}

async function selectExperiment(experimentId: number) {
  stopPolling()
  const { data } = await getEvalOpsExperiment(experimentId)
  currentExperiment.value = data
  if (!isTerminal(data.experiment.status)) schedulePoll()
}

function openDatasetDialog(version: boolean) {
  editingVersion.value = version
  if (version && datasetDetail.value) {
    datasetForm.changeNote = ''
    datasetForm.itemsJson = JSON.stringify(datasetDetail.value.currentVersion.items.map((item) => ({
      itemKey: item.itemKey,
      message: item.message,
      input: item.input,
      expected: item.expected,
      metadata: item.metadata,
      tags: item.tags,
      sourceTraceId: item.sourceTraceId,
      enabled: item.enabled,
    })), null, 2)
  } else {
    datasetForm.itemsJson = JSON.stringify(defaultItems, null, 2)
  }
  datasetDialogVisible.value = true
}

async function saveDataset() {
  if (!editingVersion.value && !datasetForm.name.trim()) return ElMessage.warning('请输入数据集名称')
  let items: EvalOpsDatasetItemInput[]
  try {
    items = JSON.parse(datasetForm.itemsJson) as EvalOpsDatasetItemInput[]
    if (!Array.isArray(items) || !items.length) throw new Error('items 必须是非空数组')
  } catch (error) {
    return ElMessage.error(error instanceof Error ? error.message : 'items JSON 无效')
  }
  savingDataset.value = true
  try {
    if (editingVersion.value && selectedDatasetId.value) {
      await createEvalOpsDatasetVersion(selectedDatasetId.value, {
        changeNote: datasetForm.changeNote.trim(),
        items,
      })
      await selectDataset(selectedDatasetId.value)
      ElMessage.success('数据集新版本已发布')
    } else {
      const { data } = await createEvalOpsDataset({
        targetType: 'AGENT',
        targetId: agentId,
        projectCode: projectCode.value || undefined,
        name: datasetForm.name.trim(),
        description: datasetForm.description.trim(),
        source: 'MANUAL',
        items,
      })
      selectedDatasetId.value = data.dataset.id
      datasetDetail.value = data
      await loadDatasets()
      ElMessage.success('版本化数据集已创建')
    }
    datasetDialogVisible.value = false
  } catch (error) {
    ElMessage.error(errorMessage(error, '保存数据集失败'))
  } finally {
    savingDataset.value = false
  }
}

function openTraceDialog() {
  if (!selectedDatasetId.value) return
  traceForm.traceId = ''
  traceForm.message = ''
  traceForm.expectedJson = JSON.stringify({ success: true }, null, 2)
  traceDialogVisible.value = true
}

async function importTrace() {
  if (!selectedDatasetId.value || !traceForm.traceId.trim()) return ElMessage.warning('请输入 Trace ID')
  let expected: Record<string, unknown>
  try {
    expected = JSON.parse(traceForm.expectedJson) as Record<string, unknown>
  } catch {
    return ElMessage.error('期望 JSON 无效')
  }
  importingTrace.value = true
  try {
    await createEvalOpsDatasetVersionFromTrace(selectedDatasetId.value, {
      traceId: traceForm.traceId.trim(),
      message: traceForm.message.trim() || undefined,
      expected,
      changeNote: `RunOps 反哺 ${traceForm.traceId.trim()}`,
    })
    await selectDataset(selectedDatasetId.value)
    await loadDatasets()
    traceDialogVisible.value = false
    ElMessage.success('Trace 已作为来源证据写入数据集新版本')
  } catch (error) {
    ElMessage.error(errorMessage(error, 'Trace 反哺失败'))
  } finally {
    importingTrace.value = false
  }
}

async function startExperiment() {
  if (!datasetDetail.value || !canStartExperiment.value) return
  creatingExperiment.value = true
  try {
    const idempotencyKey = typeof crypto !== 'undefined' && 'randomUUID' in crypto
      ? crypto.randomUUID()
      : `eval-${Date.now()}-${Math.random().toString(16).slice(2)}`
    const { data } = await createEvalOpsExperiment({
      targetType: 'AGENT',
      targetId: agentId,
      projectCode: projectCode.value || undefined,
      name: `${agentName.value || agentId} · v${datasetDetail.value.currentVersion.versionNo} 对比`,
      datasetVersionId: datasetDetail.value.currentVersion.id,
      repeatCount: experimentForm.repeatCount,
      baselineConfigVersionId: experimentForm.baselineConfigVersionId!,
      candidateConfigVersionId: experimentForm.candidateConfigVersionId!,
      idempotencyKey,
      gateConfig: {
        minCandidateScore: experimentForm.minCandidateScore,
        maxScoreRegression: experimentForm.maxScoreRegression,
        maxLatencyRegressionRatio: experimentForm.maxLatencyRegressionRatio,
        requireNoNewFailures: true,
      },
    })
    currentExperiment.value = data
    await loadExperiments()
    schedulePoll()
    ElMessage.success(`实验 #${data.experiment.id} 已持久化入队`)
  } catch (error) {
    ElMessage.error(errorMessage(error, '创建实验失败'))
  } finally {
    creatingExperiment.value = false
  }
}

async function refreshExperiment() {
  const id = currentExperiment.value?.experiment.id
  if (!id) return
  refreshingExperiment.value = true
  try {
    const { data } = await getEvalOpsExperiment(id)
    currentExperiment.value = data
    if (isTerminal(data.experiment.status)) {
      stopPolling()
      await loadExperiments()
    }
  } finally {
    refreshingExperiment.value = false
  }
}

async function cancelExperiment() {
  const id = currentExperiment.value?.experiment.id
  if (!id) return
  try {
    await ElMessageBox.confirm('未执行任务将取消；已租约执行的只读任务会安全收尾。是否继续？', '取消实验', {
      type: 'warning',
    })
    const { data } = await cancelEvalOpsExperiment(id)
    currentExperiment.value = data
    await loadExperiments()
    if (isTerminal(data.experiment.status)) stopPolling()
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') ElMessage.error(errorMessage(error, '取消实验失败'))
  }
}

function schedulePoll() {
  stopPolling()
  if (!currentExperiment.value || isTerminal(currentExperiment.value.experiment.status)) return
  pollTimer = window.setTimeout(async () => {
    try {
      await refreshExperiment()
    } finally {
      if (currentExperiment.value && !isTerminal(currentExperiment.value.experiment.status)) schedulePoll()
    }
  }, 2000)
}

function stopPolling() {
  if (pollTimer !== undefined) window.clearTimeout(pollTimer)
  pollTimer = undefined
}

function configLabel(config: AgentConfigVersion) {
  return `v${config.versionNo} · ${config.status} · #${config.id}`
}

function datasetItemName(itemId: number) {
  return currentExperiment.value?.datasetVersion.items.find((item) => item.id === itemId)?.itemKey || `#${itemId}`
}

function variantName(variantId: number) {
  return currentExperiment.value?.variants.find((item) => item.id === variantId)?.variantKey || `#${variantId}`
}

function resultLabel(item: EvalOpsExperimentItem) {
  if (item.status !== 'COMPLETED') return item.status
  return item.assertionPassed ? '通过' : '失败'
}

function resultType(item: EvalOpsExperimentItem) {
  if (item.status === 'PENDING' || item.status === 'RUNNING') return 'info'
  if (item.status === 'CANCELLED') return 'warning'
  return item.assertionPassed ? 'success' : 'danger'
}

function statusType(status?: string) {
  if (status === 'COMPLETED') return 'success'
  if (status === 'PARTIAL' || status === 'FAILED') return 'danger'
  if (status === 'CANCELLED' || status === 'CANCEL_REQUESTED') return 'warning'
  return 'info'
}

function gateType(status?: string) {
  if (status === 'PASSED') return 'success'
  if (status === 'FAILED') return 'danger'
  if (status === 'NOT_EVALUATED') return 'warning'
  return 'info'
}

function isTerminal(status?: string) {
  return ['COMPLETED', 'PARTIAL', 'FAILED', 'CANCELLED'].includes(status || '')
}

function shortHash(value?: string | null) {
  if (!value) return '-'
  return `${value.slice(0, 10)}…${value.slice(-8)}`
}

function compactJson(value?: unknown) {
  return JSON.stringify(value || {})
}

function percent(value?: number | null) {
  return `${((value || 0) * 100).toFixed(1)}%`
}

function signedPercent(value?: number | null) {
  const number = value || 0
  return `${number > 0 ? '+' : ''}${(number * 100).toFixed(1)}%`
}

function errorMessage(error: unknown, fallback: string) {
  const response = (error as { response?: { data?: { message?: unknown; detail?: unknown } } })?.response
  const value = response?.data?.message || response?.data?.detail
  return value ? String(value) : error instanceof Error ? error.message : fallback
}

onMounted(async () => {
  loading.value = true
  try {
    await loadAgentAndConfigs()
    await Promise.all([loadDatasets(), loadExperiments()])
    if (experiments.value.length) await selectExperiment(experiments.value[0].id)
  } catch (error) {
    ElMessage.error(errorMessage(error, 'EvalOps 数据加载失败'))
  } finally {
    loading.value = false
  }
})

onBeforeUnmount(stopPolling)
</script>

<style scoped lang="scss">
.agent-eval-page { min-width: 0; }

.eval-layout {
  display: grid;
  grid-template-columns: minmax(260px, 310px) minmax(0, 1fr);
  gap: var(--layout-page-gap);
  align-items: start;
}

.eval-sidebar,
.eval-main {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: var(--layout-page-gap);
}

.dataset-item,
.experiment-item {
  display: flex;
  width: 100%;
  margin-bottom: 9px;
  padding: 12px;
  border: 1px solid var(--border-divider);
  border-radius: var(--radius-md);
  background: var(--surface-panel);
  color: var(--text-primary);
  text-align: left;
  cursor: pointer;
  flex-direction: column;
  gap: 6px;
}

.dataset-item.is-active,
.experiment-item.is-active {
  border-color: var(--color-primary);
  box-shadow: inset 3px 0 0 var(--color-primary);
}

.dataset-title,
.status-row,
.evidence-strip {
  display: flex;
  align-items: center;
  gap: 10px;
}

.dataset-title { justify-content: space-between; }
.dataset-item small,
.experiment-item small,
.empty-copy,
.evidence-strip,
.variant-evidence span,
.metric-card small { color: var(--text-secondary); }

.evidence-strip {
  margin-bottom: 14px;
  flex-wrap: wrap;
}

.evidence-strip code,
.variant-evidence code { color: var(--color-primary); }

.experiment-form-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 0 18px;
}

.experiment-form-grid :deep(.el-select),
.experiment-form-grid :deep(.el-input-number) { width: 100%; }

.status-row { margin-bottom: 16px; flex-wrap: wrap; }
.worker-hint { margin-top: 14px; }

.variant-evidence-grid,
.metric-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--section-gap);
}

.variant-evidence,
.metric-card {
  display: flex;
  min-width: 0;
  padding: var(--panel-padding);
  border: 1px solid var(--border-divider);
  border-radius: var(--radius-lg);
  background: var(--surface-panel);
  flex-direction: column;
  gap: 8px;
}

.variant-role {
  color: var(--color-primary) !important;
  font-size: 12px;
  font-weight: 700;
  letter-spacing: .08em;
}

.metric-card strong { font-size: 26px; }

.check-chip {
  display: inline-block;
  margin: 2px 8px 2px 0;
  color: var(--color-success);
}

.check-chip.failed { color: var(--color-danger); }
.json-inline { white-space: normal; word-break: break-all; }
.json-editor :deep(textarea), code { font-family: var(--font-mono); }

@media (max-width: 1100px) {
  .eval-layout { grid-template-columns: 1fr; }
  .experiment-form-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}

@media (max-width: 720px) {
  .experiment-form-grid,
  .variant-evidence-grid,
  .metric-grid { grid-template-columns: 1fr; }
}
</style>
