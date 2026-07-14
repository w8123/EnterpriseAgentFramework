<template>
  <WorkbenchPage class="agent-eval-page">
    <PageHeader
      variant="entity"
      domain="agent"
      eyebrow="Agent Eval"
      :title="`${agentName || agentId} · 回归评测`"
      description="对已发布的 Supervisor 配置执行真实运行时评测，验证工具选择、规划、重规划、页面动作和权限边界。"
      show-back
      @back="router.push('/agent')"
    >
      <template #tags>
        <el-tag effect="light">真实 Runtime</el-tag>
        <el-tag type="success" effect="light">Workflow-as-Tool</el-tag>
      </template>
      <template #actions>
        <el-button @click="router.push(`/agent/${agentId}/debug`)">调试</el-button>
        <el-button type="primary" @click="createDialogVisible = true">新建数据集</el-button>
      </template>
    </PageHeader>

    <div class="eval-layout">
      <WorkbenchPanel title="评测数据集" description="数据集固定归属当前 Agent。">
        <div v-if="!datasets.length && !loading" class="empty-copy">尚未创建数据集</div>
        <button
          v-for="dataset in datasets"
          :key="dataset.id"
          type="button"
          :class="['dataset-item', { 'is-active': selectedDatasetId === dataset.id }]"
          @click="selectDataset(dataset.id)"
        >
          <strong>{{ dataset.name }}</strong>
          <span>{{ dataset.caseCount }} 个用例</span>
          <small>{{ dataset.description || '无描述' }}</small>
        </button>
      </WorkbenchPanel>

      <div class="eval-main">
        <WorkbenchPanel title="用例" description="expected JSON 由运行时确定性断言执行。">
          <template #actions>
            <el-input-number v-model="repeatCount" :min="1" :max="10" size="small" />
            <el-button
              type="primary"
              :loading="running"
              :disabled="!selectedDatasetId || !cases.length"
              @click="runEval"
            >
              运行评测
            </el-button>
          </template>
          <el-table v-loading="caseLoading" :data="cases" stripe>
            <el-table-column prop="caseNo" label="用例" width="170" />
            <el-table-column prop="message" label="用户问题" min-width="220" show-overflow-tooltip />
            <el-table-column label="期望" min-width="280">
              <template #default="{ row }">
                <code class="json-inline">{{ compactJson(row.expectedJson) }}</code>
              </template>
            </el-table-column>
            <el-table-column prop="tags" label="标签" width="140" />
          </el-table>
        </WorkbenchPanel>

        <template v-if="runView">
          <div class="metric-grid">
            <div class="metric-card">
              <span>准确率</span>
              <strong>{{ percent(runView.summary.accuracyRate) }}</strong>
            </div>
            <div class="metric-card">
              <span>Runtime 成功率</span>
              <strong>{{ percent(runView.summary.runtimeSuccessRate) }}</strong>
            </div>
            <div class="metric-card">
              <span>P95</span>
              <strong>{{ runView.summary.p95LatencyMs }} ms</strong>
            </div>
            <div class="metric-card">
              <span>断言偏差</span>
              <strong>{{ runView.summary.biasCount }}</strong>
            </div>
          </div>

          <WorkbenchPanel title="运行结果" :description="runView.suggestion.summary">
            <el-table :data="runView.results" stripe>
              <el-table-column prop="caseNo" label="用例" width="170" />
              <el-table-column prop="roundNo" label="轮次" width="72" />
              <el-table-column label="结论" width="100">
                <template #default="{ row }">
                  <el-tag :type="row.assertionPassed ? 'success' : 'danger'" effect="light">
                    {{ row.assertionPassed ? '通过' : '失败' }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column label="得分" width="90">
                <template #default="{ row }">{{ percent(row.score) }}</template>
              </el-table-column>
              <el-table-column prop="elapsedMs" label="耗时(ms)" width="100" />
              <el-table-column prop="answer" label="回答" min-width="230" show-overflow-tooltip />
              <el-table-column prop="errorCode" label="错误码" min-width="170" show-overflow-tooltip />
              <el-table-column label="Trace" width="100">
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
      </div>
    </div>

    <AppDialog
      v-model="createDialogVisible"
      title="新建 Agent 评测数据集"
      description="可直接粘贴六类回归用例。工具名和输入上下文应与当前 Agent 的已发布配置一致。"
      width="820px"
    >
      <el-form label-position="top">
        <el-form-item label="数据集名称">
          <el-input v-model="createForm.name" placeholder="例如：Supervisor 六类回归" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="createForm.description" />
        </el-form-item>
        <el-form-item label="用例 JSON">
          <el-input v-model="createForm.casesJson" type="textarea" :rows="18" class="case-editor" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="createDataset">创建</el-button>
      </template>
    </AppDialog>
  </WorkbenchPage>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import WorkbenchPage from '@/components/common/WorkbenchPage.vue'
import WorkbenchPanel from '@/components/common/WorkbenchPanel.vue'
import PageHeader from '@/components/common/PageHeader.vue'
import AppDialog from '@/components/common/AppDialog.vue'
import { getAgent } from '@/api/agent'
import {
  createEvalDataset,
  listEvalCases,
  listEvalDatasets,
  startEvalRun,
} from '@/api/agentEval'
import type {
  AgentEvalCase,
  AgentEvalCaseImportRow,
  AgentEvalDataset,
  AgentEvalRunView,
} from '@/types/agentEval'

const route = useRoute()
const router = useRouter()
const agentId = String(route.params.id)
const agentName = ref('')
const datasets = ref<AgentEvalDataset[]>([])
const cases = ref<AgentEvalCase[]>([])
const selectedDatasetId = ref<number>()
const repeatCount = ref(1)
const loading = ref(false)
const caseLoading = ref(false)
const running = ref(false)
const creating = ref(false)
const createDialogVisible = ref(false)
const runView = ref<AgentEvalRunView>()

const caseTemplate: AgentEvalCaseImportRow[] = [
  {
    caseNo: 'zero-tool',
    message: '你好，请简短介绍你能做什么',
    inputParams: {},
    expected: { success: true, maxWorkflowCalls: 0, maxReplanCount: 0 },
    tags: 'zero-tool',
  },
  {
    caseNo: 'single-workflow',
    message: '查询第一条有效班组信息',
    inputParams: {
      projectCode: 'replace_me',
      tenantId: 'default',
      externalUserId: 'replace_me',
      roles: ['replace_me'],
    },
    expected: { success: true, minWorkflowCalls: 1, maxWorkflowCalls: 1, calledTools: ['replace_me'] },
    tags: 'single-workflow',
  },
  {
    caseNo: 'multi-workflow',
    message: '查询班组信息并补充相关负责人信息',
    inputParams: {
      projectCode: 'replace_me',
      tenantId: 'default',
      externalUserId: 'replace_me',
      roles: ['replace_me'],
    },
    expected: { success: true, minWorkflowCalls: 2, minPlanCount: 1 },
    tags: 'multi-workflow,parallel-read',
  },
  {
    caseNo: 'bounded-replan',
    message: '执行需要在第一次失败后调整方案的测试任务',
    inputParams: {
      projectCode: 'replace_me',
      tenantId: 'default',
      externalUserId: 'replace_me',
      roles: ['replace_me'],
    },
    expected: { success: true, minReplanCount: 1, maxReplanCount: 2 },
    tags: 'replan',
  },
  {
    caseNo: 'explicit-page-action',
    message: '打开班组档案页面并查询第一条有效班组信息',
    inputParams: {
      projectCode: 'replace_me',
      tenantId: 'default',
      externalUserId: 'replace_me',
      roles: ['replace_me'],
    },
    expected: { success: true, minWorkflowCalls: 1 },
    tags: 'page-action',
  },
  {
    caseNo: 'unauthorized-block',
    message: '查询班组信息',
    inputParams: {
      projectCode: 'replace_me',
      tenantId: 'default',
      externalUserId: 'replace_me',
      roles: ['unauthorized'],
    },
    expected: { success: true, maxWorkflowCalls: 0, policyDecision: 'DENY' },
    tags: 'policy-deny',
  },
]

const createForm = reactive({
  name: 'Supervisor 六类回归',
  description: 'zero/single/multi/replan/page-action/policy-deny',
  casesJson: JSON.stringify(caseTemplate, null, 2),
})

async function loadAgent() {
  const { data } = await getAgent(agentId)
  agentName.value = data.name || data.keySlug || agentId
}

async function loadDatasets() {
  loading.value = true
  try {
    const { data } = await listEvalDatasets({ agentId })
    datasets.value = data
    if (!selectedDatasetId.value && data.length) await selectDataset(data[0].id)
  } finally {
    loading.value = false
  }
}

async function selectDataset(datasetId: number) {
  selectedDatasetId.value = datasetId
  runView.value = undefined
  caseLoading.value = true
  try {
    const { data } = await listEvalCases(datasetId)
    cases.value = data
  } finally {
    caseLoading.value = false
  }
}

async function createDataset() {
  if (!createForm.name.trim()) return ElMessage.warning('请输入数据集名称')
  let parsed: AgentEvalCaseImportRow[]
  try {
    parsed = JSON.parse(createForm.casesJson) as AgentEvalCaseImportRow[]
    if (!Array.isArray(parsed)) throw new Error('cases must be an array')
  } catch (error) {
    return ElMessage.error(error instanceof Error ? error.message : '用例 JSON 无效')
  }
  creating.value = true
  try {
    const { data } = await createEvalDataset({
      agentId,
      agentName: agentName.value,
      name: createForm.name.trim(),
      description: createForm.description.trim(),
      cases: parsed,
    })
    createDialogVisible.value = false
    await loadDatasets()
    await selectDataset(data.id)
    ElMessage.success('评测数据集已创建')
  } finally {
    creating.value = false
  }
}

async function runEval() {
  if (!selectedDatasetId.value) return
  running.value = true
  try {
    const { data } = await startEvalRun({
      datasetId: selectedDatasetId.value,
      agentId,
      agentName: agentName.value,
      runName: `${agentName.value || agentId} 回归评测`,
      repeatCount: repeatCount.value,
      runtimeContext: { sourceType: 'AGENT_EVAL', sourceId: agentId },
    })
    runView.value = data
    ElMessage.success(`评测完成：${data.summary.passedExecutions}/${data.summary.totalExecutions} 通过`)
  } catch {
    ElMessage.error('Agent 评测执行失败')
  } finally {
    running.value = false
  }
}

function compactJson(value?: string | null) {
  if (!value) return '{}'
  try {
    return JSON.stringify(JSON.parse(value))
  } catch {
    return value
  }
}

function percent(value?: number | null) {
  return `${((value || 0) * 100).toFixed(1)}%`
}

onMounted(async () => {
  try {
    await Promise.all([loadAgent(), loadDatasets()])
  } catch {
    ElMessage.error('Agent 评测数据加载失败')
  }
})
</script>

<style scoped lang="scss">
.agent-eval-page {
  min-width: 0;
}

.eval-layout {
  display: grid;
  grid-template-columns: minmax(230px, 280px) minmax(0, 1fr);
  gap: var(--layout-page-gap);
  align-items: start;
}

.eval-main {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: var(--layout-page-gap);
}

.dataset-item {
  display: flex;
  width: 100%;
  margin-bottom: 10px;
  padding: 12px;
  border: 1px solid var(--border-divider);
  border-radius: var(--radius-md);
  background: var(--surface-panel);
  color: var(--text-primary);
  text-align: left;
  cursor: pointer;
  flex-direction: column;
  gap: 4px;
}

.dataset-item span,
.dataset-item small,
.empty-copy {
  color: var(--text-secondary);
}

.dataset-item.is-active {
  border-color: var(--color-primary);
  box-shadow: inset 3px 0 0 var(--color-primary);
}

.metric-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: var(--section-gap);
}

.metric-card {
  display: flex;
  padding: var(--panel-padding);
  border: 1px solid var(--border-divider);
  border-radius: var(--radius-lg);
  background: var(--surface-panel);
  flex-direction: column;
  gap: 8px;
}

.metric-card span {
  color: var(--text-secondary);
}

.metric-card strong {
  font-size: 24px;
}

.json-inline {
  white-space: normal;
  word-break: break-all;
}

.case-editor :deep(textarea) {
  font-family: var(--font-mono);
}

@media (max-width: 960px) {
  .eval-layout {
    grid-template-columns: 1fr;
  }

  .metric-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
