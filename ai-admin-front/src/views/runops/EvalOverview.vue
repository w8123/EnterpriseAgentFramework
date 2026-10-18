<template>
  <WorkbenchPage class="eval-overview-page" layout="list">
    <PageHeader
      variant="overview"
      domain="governance"
      eyebrow="运行中心"
      title="评测"
      description="发布新配置前，用同一批用例对比新旧版本，确认没有变差。"
    >
      <template #meta>
        <RunCenterNav />
      </template>
      <template #actions>
        <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
        <el-button type="primary" :icon="Plus" @click="openCreate">新建评测</el-button>
      </template>
    </PageHeader>

    <EvalWorkerNotice />

    <el-alert
      v-if="loadError"
      type="error"
      :title="loadError"
      :closable="false"
      show-icon
    />

    <WorkbenchPanel title="按 Agent" description="每个 Agent 的评测数据集和最近一次对比实验。">
      <el-table v-loading="loading" :data="agentRows" row-key="agentId" empty-text="还没有评测。点「新建评测」为某个 Agent 建立数据集。">
        <el-table-column label="Agent" min-width="200">
          <template #default="{ row }">
            <div class="cell-stack">
              <strong>{{ row.agentName }}</strong>
              <small>{{ row.projectCode || '-' }}</small>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="datasetCount" label="数据集" width="90" align="right" />
        <el-table-column prop="experimentCount" label="实验" width="90" align="right" />
        <el-table-column label="最近实验" min-width="220">
          <template #default="{ row }">
            <template v-if="row.latestExperiment">
              <div class="cell-stack">
                <span>{{ row.latestExperiment.name }}</span>
                <small>{{ formatTime(row.latestExperiment.createdAt) }}</small>
              </div>
            </template>
            <span v-else class="muted">还没有实验</span>
          </template>
        </el-table-column>
        <el-table-column label="结论" width="130">
          <template #default="{ row }">
            <el-tag
              v-if="row.latestExperiment"
              :type="gateStatusView(row.latestExperiment.gateStatus).tone"
              effect="light"
            >
              {{ gateStatusView(row.latestExperiment.gateStatus).label }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="100" align="center">
          <template #default="{ row }">
            <el-button link type="primary" @click="openAgent(row.agentId)">打开</el-button>
          </template>
        </el-table-column>
      </el-table>
    </WorkbenchPanel>

    <WorkbenchPanel title="最近实验" description="全部 Agent 最近的对比实验，点开查看逐项结果和对应运行。">
      <el-table v-loading="loading" :data="recentExperiments" row-key="id" empty-text="还没有实验">
        <el-table-column label="实验" min-width="220">
          <template #default="{ row }">
            <div class="cell-stack">
              <strong>{{ row.name }}</strong>
              <small>{{ agentName(row.targetId) }}</small>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag :type="experimentStatusView(row.status).tone" effect="plain">
              {{ experimentStatusView(row.status).label }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="进度" width="120">
          <template #default="{ row }">
            {{ row.completedTaskCount }}/{{ row.taskCount }}
            <span v-if="row.failedTaskCount" class="failed-count">失败 {{ row.failedTaskCount }}</span>
          </template>
        </el-table-column>
        <el-table-column label="结论" width="130">
          <template #default="{ row }">
            <el-tag :type="gateStatusView(row.gateStatus).tone" effect="light">
              {{ gateStatusView(row.gateStatus).label }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="发起时间" width="170">
          <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="100" align="center">
          <template #default="{ row }">
            <el-button link type="primary" @click="openAgent(row.targetId, row.id)">查看</el-button>
          </template>
        </el-table-column>
      </el-table>
    </WorkbenchPanel>

    <AppDialog v-model="createVisible" title="新建评测" width="480px">
      <p class="dialog-hint">选择要评测的 Agent，进入后建立数据集并发起对比实验。</p>
      <el-select
        v-model="createAgentId"
        filterable
        placeholder="选择 Agent"
        class="agent-select"
        :no-data-text="agentsLoadFailed ? 'Agent 列表加载失败' : '没有可选的 Agent'"
      >
        <el-option
          v-for="agent in agents"
          :key="agent.id"
          :label="`${agent.name || agent.keySlug}${agent.projectCode ? ` · ${agent.projectCode}` : ''}`"
          :value="agent.id"
        />
      </el-select>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :disabled="!createAgentId" @click="openAgent(createAgentId)">进入</el-button>
      </template>
    </AppDialog>
  </WorkbenchPage>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Plus, Refresh } from '@element-plus/icons-vue'
import AppDialog from '@/components/common/AppDialog.vue'
import PageHeader from '@/components/common/PageHeader.vue'
import WorkbenchPage from '@/components/common/WorkbenchPage.vue'
import WorkbenchPanel from '@/components/common/WorkbenchPanel.vue'
import { listEvalOpsDatasets, listEvalOpsExperiments } from '@/api/agentEval'
import { listAgents } from '@/api/workflow'
import type { EvalOpsDatasetSummary, EvalOpsExperimentSummary } from '@/types/agentEval'
import type { Agent } from '@/types/workflow'
import RunCenterNav from './components/RunCenterNav.vue'
import EvalWorkerNotice from './components/EvalWorkerNotice.vue'
import {
  buildEvalAgentRows,
  experimentStatusView,
  gateStatusView,
  sortExperimentsByRecent,
} from './evalOverview'

const RECENT_EXPERIMENT_LIMIT = 20

const router = useRouter()
const loading = ref(false)
const loadError = ref('')
const agents = ref<Agent[]>([])
const agentsLoadFailed = ref(false)
const datasets = ref<EvalOpsDatasetSummary[]>([])
const experiments = ref<EvalOpsExperimentSummary[]>([])
const createVisible = ref(false)
const createAgentId = ref('')

const agentRows = computed(() => buildEvalAgentRows(agents.value, datasets.value, experiments.value))
const recentExperiments = computed(() => sortExperimentsByRecent(experiments.value).slice(0, RECENT_EXPERIMENT_LIMIT))

function agentName(agentId: string) {
  const agent = agents.value.find(item => item.id === agentId)
  return agent?.name || agent?.keySlug || agentId
}

function formatTime(value?: string | null) {
  return value ? value.replace('T', ' ').slice(0, 19) : '-'
}

function openCreate() {
  createAgentId.value = ''
  createVisible.value = true
}

function openAgent(agentId: string, experimentId?: number) {
  createVisible.value = false
  router.push({
    path: `/runops/evals/${encodeURIComponent(agentId)}`,
    query: experimentId ? { experimentId: String(experimentId) } : undefined,
  })
}

async function loadAgents() {
  try {
    const { data } = await listAgents(undefined, { errorFeedback: 'local' })
    agents.value = Array.isArray(data) ? data : []
    agentsLoadFailed.value = false
  } catch {
    agentsLoadFailed.value = true
  }
}

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    const [datasetResponse, experimentResponse] = await Promise.all([
      listEvalOpsDatasets(),
      listEvalOpsExperiments(),
      loadAgents(),
    ])
    datasets.value = Array.isArray(datasetResponse.data) ? datasetResponse.data : []
    experiments.value = Array.isArray(experimentResponse.data) ? experimentResponse.data : []
  } catch (error) {
    loadError.value = error instanceof Error && error.message ? `评测加载失败：${error.message}` : '评测加载失败'
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<style scoped lang="scss">
.eval-overview-page {
  width: min(100%, 1840px);
  margin-inline: auto;
}

.cell-stack {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 2px;
}

.cell-stack small,
.muted {
  color: var(--text-secondary);
  font-size: 12px;
}

.failed-count {
  margin-left: 6px;
  color: var(--el-color-danger);
  font-size: 12px;
}

.dialog-hint {
  margin: 0 0 12px;
  color: var(--text-secondary);
}

.agent-select {
  width: 100%;
}
</style>
