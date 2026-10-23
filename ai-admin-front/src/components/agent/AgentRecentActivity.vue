<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { ArrowRight } from '@element-plus/icons-vue'
import WorkbenchPanel from '@/components/common/WorkbenchPanel.vue'
import StatusTag from '@/components/common/StatusTag.vue'
import type { StatusTone } from '@/components/common/glassWorkbench'
import { getRecentRunOps } from '@/api/runops'
import { listEvalOpsExperiments } from '@/api/agentEval'
import type { RunEntryType, RunStatus, RunSummary } from '@/types/runops'
import type { EvalOpsExperimentSummary } from '@/types/agentEval'
import { gateStatusView, sortExperimentsByRecent } from '@/views/runops/evalOverview'

const props = defineProps<{
  agentId: string
  canReadRuns: boolean
  canEvaluate: boolean
}>()

const emit = defineEmits<{
  'open-run': [traceId: string]
  'open-runs': []
  'open-eval': []
}>()

const runs = ref<RunSummary[]>([])
const runsLoading = ref(false)
const runsFailed = ref(false)
const latestExperiment = ref<EvalOpsExperimentSummary | null>(null)
const evalLoading = ref(false)

const ENTRY_LABELS: Partial<Record<RunEntryType, string>> = {
  DEBUG: '调试',
  EMBED: '嵌入',
  GATEWAY: '网关',
  EVAL: '评测',
  REPLAY: '重放',
  AUTOMATION: '自动化',
  API: 'API',
  MCP: 'MCP',
  CONSOLE: '控制台',
}
const STATUS_VIEW: Record<RunStatus, { label: string; tone: StatusTone }> = {
  RUNNING: { label: '运行中', tone: 'info' },
  SUSPENDED: { label: '已暂停', tone: 'warning' },
  COMPLETED: { label: '已完成', tone: 'success' },
  FAILED: { label: '失败', tone: 'danger' },
  CANCELLED: { label: '已取消', tone: 'neutral' },
  TIMED_OUT: { label: '已超时', tone: 'danger' },
}

const experimentProgress = computed(() => {
  const experiment = latestExperiment.value
  if (!experiment) return ''
  const total = experiment.taskCount || experiment.summary?.totalTasks || 0
  const failed = experiment.failedTaskCount || 0
  const completed = experiment.completedTaskCount || 0
  return total ? `完成 ${completed}/${total}${failed ? `，失败 ${failed}` : ''}` : ''
})

function gateTone(status: string): StatusTone {
  const tone = gateStatusView(status).tone
  return tone === 'primary' ? 'info' : tone
}

function statusView(status: RunStatus) {
  return STATUS_VIEW[status] || { label: status, tone: 'neutral' as StatusTone }
}

/** 运行记录不保存用户原话（inputSummary 只是输入元数据），标题用入口和时间。 */
function runTitle(run: RunSummary) {
  const entry = ENTRY_LABELS[run.entryType] || run.entryType || '运行'
  return run.startedAt ? `${entry} · ${formatTime(run.startedAt)}` : entry
}

function runMeta(run: RunSummary) {
  const parts: string[] = []
  if (run.agentConfigVersion) parts.push(`配置 v${run.agentConfigVersion}`)
  if (typeof run.latencyMs === 'number') parts.push(formatLatency(run.latencyMs))
  if (run.workflowCallCount) parts.push(`调用 ${run.workflowCallCount} 次 Workflow`)
  if (run.errorMessage || run.errorCode) parts.push(run.errorMessage || run.errorCode || '')
  return parts.join(' · ')
}

function formatLatency(ms: number) {
  return ms < 1000 ? `${Math.round(ms)}ms` : `${(ms / 1000).toFixed(ms < 10_000 ? 1 : 0)}s`
}

function formatTime(value: string) {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`
}

async function loadRuns() {
  if (!props.canReadRuns || !props.agentId) return
  runsLoading.value = true
  runsFailed.value = false
  try {
    const { data } = await getRecentRunOps({ agentId: props.agentId, days: 30, limit: 5 }, { errorFeedback: 'local' })
    runs.value = Array.isArray(data) ? data.slice(0, 5) : []
  } catch {
    runs.value = []
    runsFailed.value = true
  } finally {
    runsLoading.value = false
  }
}

async function loadLatestExperiment() {
  if (!props.canEvaluate || !props.agentId) return
  evalLoading.value = true
  try {
    const { data } = await listEvalOpsExperiments({ targetId: props.agentId })
    latestExperiment.value = sortExperimentsByRecent(Array.isArray(data) ? data : [])[0] || null
  } catch {
    latestExperiment.value = null
  } finally {
    evalLoading.value = false
  }
}

onMounted(() => {
  void loadRuns()
  void loadLatestExperiment()
})
watch(() => props.agentId, () => {
  void loadRuns()
  void loadLatestExperiment()
})
</script>

<template>
  <WorkbenchPanel v-if="canReadRuns || canEvaluate" class="overview-side-card recent-activity-card" density="compact">
    <template v-if="canReadRuns">
      <div class="recent-activity-heading">
        <h2>最近运行</h2>
        <el-button link type="primary" @click="emit('open-runs')">全部 <el-icon class="el-icon--right"><ArrowRight /></el-icon></el-button>
      </div>
      <div v-loading="runsLoading" class="recent-run-list">
        <p v-if="!runsLoading && !runs.length" class="recent-activity-empty">
          {{ runsFailed ? '运行记录暂时无法加载' : '最近 30 天没有运行记录' }}
        </p>
        <button
          v-for="run in runs"
          :key="run.traceId"
          class="recent-run-item"
          type="button"
          @click="emit('open-run', run.traceId)"
        >
          <span class="recent-run-item__copy">
            <strong :title="runTitle(run)">{{ runTitle(run) }}</strong>
            <small>{{ runMeta(run) }}</small>
          </span>
          <StatusTag :label="statusView(run.status).label" :tone="statusView(run.status).tone" />
        </button>
      </div>
    </template>

    <template v-if="canEvaluate">
      <div class="recent-activity-heading" :class="{ 'has-divider': canReadRuns }">
        <h2>最近一次评测</h2>
        <el-button link type="primary" @click="emit('open-eval')">评测 <el-icon class="el-icon--right"><ArrowRight /></el-icon></el-button>
      </div>
      <div v-loading="evalLoading" class="recent-eval">
        <p v-if="!evalLoading && !latestExperiment" class="recent-activity-empty">还没有评测，可在评测页新建</p>
        <button v-else-if="latestExperiment" class="recent-run-item" type="button" @click="emit('open-eval')">
          <span class="recent-run-item__copy">
            <strong :title="latestExperiment.name">{{ latestExperiment.name }}</strong>
            <small>{{ [experimentProgress, latestExperiment.createdAt ? formatTime(latestExperiment.createdAt) : ''].filter(Boolean).join(' · ') }}</small>
          </span>
          <StatusTag
            :label="gateStatusView(latestExperiment.gateStatus).label"
            :tone="gateTone(latestExperiment.gateStatus)"
          />
        </button>
      </div>
    </template>
  </WorkbenchPanel>
</template>

<style scoped lang="scss">
.recent-activity-card {
  border: 1px solid var(--border-divider);
  background: var(--surface-glass-panel);
  box-shadow: var(--shadow-panel);
}

.recent-activity-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.recent-activity-heading.has-divider {
  margin-top: 14px;
  padding-top: 14px;
  border-top: 1px solid var(--border-divider);
}

.recent-activity-heading h2 {
  margin: 0;
  color: var(--text-primary);
  font-size: 15px;
  line-height: 22px;
}

.recent-run-list,
.recent-eval {
  display: grid;
  min-height: 40px;
  gap: 6px;
  margin-top: 8px;
}

.recent-activity-empty {
  margin: 0;
  color: var(--text-secondary);
  font-size: 12px;
  line-height: 19px;
}

.recent-run-item {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 8px;
  padding: 8px 10px;
  border: 1px solid var(--border-divider);
  border-radius: var(--radius-md);
  background: var(--surface-solid-control);
  cursor: pointer;
  text-align: left;
}

.recent-run-item:hover,
.recent-run-item:focus-visible {
  border-color: rgb(var(--brand-primary-rgb) / 0.28);
}

.recent-run-item__copy {
  display: grid;
  min-width: 0;
  flex: 1;
}

.recent-run-item__copy strong {
  overflow: hidden;
  color: var(--text-primary);
  font-size: 13px;
  font-weight: 500;
  line-height: 20px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.recent-run-item__copy small {
  color: var(--text-muted);
  font-size: 12px;
  line-height: 18px;
}
</style>
