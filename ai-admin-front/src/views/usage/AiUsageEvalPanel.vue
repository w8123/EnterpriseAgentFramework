<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { DataAnalysis } from '@element-plus/icons-vue'
import {
  LiquidGlassButton,
  LiquidGlassEmptyState,
  LiquidGlassNotice,
  LiquidGlassTable,
} from '@/components/common/liquid-glass'
import { listAgents } from '@/api/agent'
import {
  draftLearnedFlow,
  enableLearnedFlow,
  listPendingLearnedFlows,
  type PendingLearnedFlow,
} from '@/api/agentLearnedFlow'
import { platformSessionUser } from '@/auth/platformSession'
import {
  generateProjectEvalQuestions,
  applyPlatformEvalAccount,
  getProjectEvalOverview,
  runProjectEval,
  saveProjectEvalSetting,
  type ProjectEvalOverview,
  type ProjectEvalRun,
  type ProjectEvalSummary,
} from '@/api/projectEval'
import type { Agent } from '@/types/workflow'
import { formatDateTime } from './aiUsageView'

const props = defineProps<{ projectCode?: string; systems: { projectCode: string; name?: string | null }[] }>()

const router = useRouter()
const loading = ref(false)
const loadError = ref('')
const overview = ref<ProjectEvalOverview | null>(null)
const generationNote = ref('')
const agents = ref<Agent[]>([])
const busy = ref<'' | 'save' | 'questions' | 'run' | 'account'>('')
const form = ref({
  agentId: '',
  evalUserId: '',
  tenantId: '',
  enabled: false,
  runHour: 3,
  questionCount: 12,
  autoEnableReadonlyFlows: false,
})
let sequence = 0
let refreshTimer: number | undefined
const pending = ref<PendingLearnedFlow[]>([])
const enabling = ref('')

async function loadPending() {
  try {
    const { data } = await listPendingLearnedFlows({ projectCode: props.projectCode || undefined, days: 30 })
    pending.value = Array.isArray(data) ? data : []
  } catch {
    // 没有查看助手的权限时不显示这一块
    pending.value = []
  }
}

/** 管理员一键确认：生成流程草稿、发布并加入那个助手（会发布助手新版本）。 */
async function enablePending(row: PendingLearnedFlow) {
  const key = `${row.agentId}:${row.candidate.sourceRef}`
  try {
    await ElMessageBox.confirm(
      `「${row.candidate.title}」最近被问了 ${row.candidate.runCount} 次，都是助手推理后查询完成的。启用后会发布一条直达流程并加入「${row.agentName}」，助手会发布一个新版本，以后同类问题更快、更省 Token。只涉及查询，不会写数据。`,
      '启用直达流程',
      { confirmButtonText: '启用', cancelButtonText: '取消', type: 'info' },
    )
  } catch {
    return
  }
  enabling.value = key
  try {
    await draftLearnedFlow(row.agentId, row.candidate.sourceRef)
    await enableLearnedFlow(row.agentId, row.candidate.sourceRef,
      platformSessionUser.value?.displayName || platformSessionUser.value?.username || undefined)
    ElMessage.success('已启用')
    await loadPending()
  } catch (error) {
    ElMessage.error(`启用失败：${errorMessage(error)}`)
  } finally {
    enabling.value = ''
  }
}

const current = computed<ProjectEvalSummary | undefined>(() =>
  overview.value?.projects.find((project) => project.setting.projectCode === props.projectCode),
)
const latest = computed(() => current.value?.runs[0])
const running = computed(() =>
  Boolean(current.value?.runs.some((run) => ['QUEUED', 'RUNNING'].includes(run.status))),
)
const configured = computed(() => Boolean(current.value?.setting.agentId && current.value?.setting.evalUserId))

function systemName(code: string) {
  return props.systems.find((system) => system.projectCode === code)?.name || code
}

function percent(value?: number | null) {
  return value == null ? '—' : `${Math.round(value * 100)}%`
}

function seconds(value?: number | null) {
  return value == null ? '—' : `${Math.max(1, Math.round(value / 1000))} 秒`
}

const statusLabels: Record<string, string> = {
  QUEUED: '排队中',
  RUNNING: '评测中',
  COMPLETED: '已完成',
  PARTIAL: '部分没跑完',
  CANCELLED: '已取消',
  CANCEL_REQUESTED: '取消中',
}

function runStatus(run?: ProjectEvalRun) {
  if (!run) return '—'
  return statusLabels[run.status] || run.status
}

function errorMessage(error: unknown) {
  const failure = error as { response?: { data?: { message?: string } }; message?: string }
  return failure?.response?.data?.message || failure?.message || '请稍后再试'
}

async function load() {
  const mine = ++sequence
  loading.value = true
  loadError.value = ''
  try {
    const { data } = await getProjectEvalOverview(props.projectCode || undefined)
    if (mine !== sequence) return
    overview.value = data
    const setting = data.projects.find((project) => project.setting.projectCode === props.projectCode)?.setting
    if (setting) {
      form.value = {
        agentId: setting.agentId || form.value.agentId,
        evalUserId: setting.evalUserId || '',
        tenantId: setting.tenantId || '',
        enabled: setting.enabled,
        runHour: setting.runHour,
        questionCount: setting.questionCount,
        autoEnableReadonlyFlows: setting.autoEnableReadonlyFlows,
      }
    }
    scheduleRefresh()
  } catch (error) {
    if (mine !== sequence) return
    loadError.value = errorMessage(error)
  } finally {
    if (mine === sequence) loading.value = false
  }
}

async function loadAgents() {
  agents.value = []
  if (!props.projectCode) return
  try {
    const { data } = await listAgents({ projectCode: props.projectCode })
    agents.value = Array.isArray(data) ? data : []
    // 还没选时默认用这个系统的页面助手（一键接入时创建的那个），只有一个助手时就用它
    if (!form.value.agentId && agents.value.length) {
      const copilot = agents.value.find((agent) => agent.keySlug?.endsWith('-page-copilot'))
      if (copilot || agents.value.length === 1) form.value.agentId = (copilot || agents.value[0]).id
    }
  } catch {
    agents.value = []
  }
}

/** 评测在后台跑，开着页面时每 20 秒刷新一次进度。 */
function scheduleRefresh() {
  window.clearTimeout(refreshTimer)
  if (running.value) refreshTimer = window.setTimeout(load, 20000)
}

async function save() {
  if (!props.projectCode) return
  busy.value = 'save'
  try {
    await saveProjectEvalSetting(props.projectCode, { ...form.value })
    ElMessage.success('已保存')
    await load()
  } catch (error) {
    ElMessage.error(`保存失败：${errorMessage(error)}`)
  } finally {
    busy.value = ''
  }
}

async function platformAccount() {
  if (!props.projectCode) return
  busy.value = 'account'
  try {
    if (form.value.agentId) await saveProjectEvalSetting(props.projectCode, { agentId: form.value.agentId })
    await applyPlatformEvalAccount(props.projectCode)
    ElMessage.success('已配上平台评测账号，它只能调用只读能力')
    await load()
  } catch (error) {
    ElMessage.error(`没能配上平台评测账号：${errorMessage(error)}`)
  } finally {
    busy.value = ''
  }
}

async function generate() {
  if (!props.projectCode) return
  busy.value = 'questions'
  try {
    const { data } = await generateProjectEvalQuestions(props.projectCode)
    generationNote.value = data?.generationNote || ''
    ElMessage.success(generationNote.value || '已按这个系统的只读能力重新出题')
    await load()
  } catch (error) {
    ElMessage.error(`出题失败：${errorMessage(error)}`)
  } finally {
    busy.value = ''
  }
}

async function run() {
  if (!props.projectCode) return
  busy.value = 'run'
  try {
    await runProjectEval(props.projectCode)
    ElMessage.success('评测已开始，几分钟后刷新查看结果')
    await load()
  } catch (error) {
    ElMessage.error(`没能开始评测：${errorMessage(error)}`)
  } finally {
    busy.value = ''
  }
}

watch(
  () => props.projectCode,
  () => {
    form.value = { agentId: '', evalUserId: '', tenantId: '', enabled: false, runHour: 3, questionCount: 12,
      autoEnableReadonlyFlows: false }
    void load()
    void loadAgents()
    void loadPending()
  },
  { immediate: true },
)
onBeforeUnmount(() => window.clearTimeout(refreshTimer))
</script>

<template>
  <div class="eval-panel">
    <p class="view-description">
      让每个接入的业务系统自动变好用。每个系统都可以每天自动考一次助手：平台按系统开放的只读查询能力自动出题，助手用一个只读评测账号回答，
      只会查询，不会新建、修改或提交审批。通过率下降或变慢时，在这里第一时间看到。
    </p>

    <LiquidGlassNotice v-if="loadError" type="error" title="自动评测加载失败" :description="loadError"
      ><template #actions><LiquidGlassButton :disabled="loading" @click="load">重试</LiquidGlassButton></template></LiquidGlassNotice
    >
    <LiquidGlassNotice
      v-else-if="overview && !overview.workerEnabled"
      type="warning"
      title="评测执行器没有打开"
      description="Runtime 的评测执行器关闭时，评测只会排队不会运行。请运维在 Runtime 配置里打开 reachai.runtime.eval.worker-enabled。"
    />

    <section v-if="pending.length" class="eval-pending" aria-label="待确认的直达流程">
      <header>
        <strong>待确认的直达流程 · {{ pending.length }}</strong>
        <small>这些查询请求在近 30 天里重复出现，每次都靠推理完成。启用后同类问题直接走流程：更快、更稳、更省 Token。也可以在下方设置里打开「自动变成直达流程」。</small>
      </header>
      <LiquidGlassTable :loading="false" :empty="false" source="backdrop" presentation="collection">
        <el-table :data="pending" empty-text=" " :row-key="(row: PendingLearnedFlow) => `${row.agentId}:${row.candidate.sourceRef}`">
          <el-table-column label="请求" min-width="200"
            ><template #default="{ row }"><strong>{{ row.candidate.title }}</strong></template></el-table-column
          >
          <el-table-column label="助手" min-width="140"
            ><template #default="{ row }">{{ row.agentName }}<template v-if="!projectCode"> · {{ systemName(row.projectCode) }}</template></template></el-table-column
          >
          <el-table-column label="次数" min-width="70" align="right"
            ><template #default="{ row }">{{ row.candidate.runCount }}</template></el-table-column
          >
          <el-table-column label="平均耗时" min-width="90" align="right"
            ><template #default="{ row }">{{ seconds(row.candidate.avgLatencyMs) }}</template></el-table-column
          >
          <el-table-column label="" min-width="90"
            ><template #default="{ row }"
              ><LiquidGlassButton text :loading="enabling === `${row.agentId}:${row.candidate.sourceRef}`"
                :disabled="Boolean(enabling)" @click="enablePending(row)">启用</LiquidGlassButton></template
            ></el-table-column
          >
        </el-table>
      </LiquidGlassTable>
    </section>

    <template v-if="!projectCode">
      <div v-if="loading && !overview" v-loading="true" class="eval-loading" element-loading-text="正在加载" />
      <LiquidGlassEmptyState
        v-else-if="overview && !overview.projects.length"
        compact
        :icon="DataAnalysis"
        title="还没有业务系统开启自动评测"
        description="在上方选择一个业务系统，然后选择要评测的助手、填写只读评测账号即可开启。"
      />
      <LiquidGlassTable v-else-if="overview" :loading="loading" :empty="false" source="backdrop" presentation="collection">
        <el-table :data="overview.projects" empty-text=" " row-key="setting.projectCode">
          <el-table-column label="业务系统" min-width="160"
            ><template #default="{ row }"><strong>{{ systemName(row.setting.projectCode) }}</strong></template></el-table-column
          >
          <el-table-column label="最近通过率" min-width="110" align="right"
            ><template #default="{ row }">{{ percent(row.runs[0]?.passRate) }}</template></el-table-column
          >
          <el-table-column label="一般耗时" min-width="100" align="right"
            ><template #default="{ row }">{{ seconds(row.runs[0]?.p50LatencyMs) }}</template></el-table-column
          >
          <el-table-column label="最近评测" min-width="170"
            ><template #default="{ row }"
              >{{ formatDateTime(row.runs[0]?.createdAt) }} · {{ runStatus(row.runs[0]) }}</template
            ></el-table-column
          >
          <el-table-column label="每日评测" min-width="110"
            ><template #default="{ row }">{{ row.setting.enabled ? `每天 ${row.setting.runHour} 点` : '未开启' }}</template></el-table-column
          >
        </el-table>
      </LiquidGlassTable>
    </template>

    <template v-else>
      <section class="eval-summary" aria-label="最近一次评测">
        <div class="eval-summary__item">
          <span>最近通过率</span>
          <strong>{{ percent(latest?.passRate) }}</strong>
          <small>{{ latest ? `${latest.completedTaskCount ?? 0}/${latest.taskCount ?? 0} 题 · ${runStatus(latest)}` : '还没有评测' }}</small>
        </div>
        <div class="eval-summary__item">
          <span>一般耗时</span>
          <strong>{{ seconds(latest?.p50LatencyMs) }}</strong>
          <small>最慢的一成 {{ seconds(latest?.p95LatencyMs) }}</small>
        </div>
        <div class="eval-summary__item">
          <span>最近评测</span>
          <strong class="eval-summary__time">{{ formatDateTime(latest?.createdAt) }}</strong>
          <small>{{ current?.setting.enabled ? `每天 ${current.setting.runHour} 点自动评测` : '每日评测未开启' }}</small>
        </div>
      </section>

      <section class="eval-settings" aria-label="自动评测设置">
        <header>
          <strong>评测设置 · {{ systemName(projectCode) }}</strong>
          <small>评测账号建议用业务系统里专门建的只读账号；就算这个账号有写权限，自动评测也只调用只读能力。</small>
        </header>
        <el-form label-position="top" class="eval-form" @submit.prevent>
          <el-form-item label="评测哪个助手">
            <el-select v-model="form.agentId" :placeholder="agents.length ? '选择这个系统的助手' : '这个系统还没有助手'" filterable clearable>
              <el-option v-for="agent in agents" :key="agent.id" :label="agent.name" :value="agent.id" />
            </el-select>
          </el-form-item>
          <el-form-item label="只读评测账号（业务系统里的员工编号）">
            <el-input v-model="form.evalUserId" maxlength="128" placeholder="例如 2" />
            <div class="eval-account-hint">
              <el-button size="small" :loading="busy === 'account'" :disabled="Boolean(busy)" @click="platformAccount">
                使用平台评测账号
              </el-button>
              <small>业务系统里没有合适的账号，或员工还没登录过助手时用它：平台自带，只能调用只读能力。</small>
            </div>
          </el-form-item>
          <el-form-item label="租户（多租户系统才需要）">
            <el-input v-model="form.tenantId" maxlength="96" placeholder="不填即可" />
          </el-form-item>
          <el-form-item label="每天几点评测">
            <el-input-number v-model="form.runHour" :min="0" :max="23" />
          </el-form-item>
          <el-form-item label="题目数量">
            <el-input-number v-model="form.questionCount" :min="3" :max="30" />
          </el-form-item>
          <el-form-item label="每日自动评测">
            <el-switch v-model="form.enabled" />
          </el-form-item>
          <el-form-item label="重复的只读请求自动变成直达流程">
            <el-switch v-model="form.autoEnableReadonlyFlows" />
          </el-form-item>
        </el-form>
        <div class="eval-actions">
          <LiquidGlassButton type="primary" :loading="busy === 'save'" :disabled="Boolean(busy)" @click="save">保存设置</LiquidGlassButton>
          <LiquidGlassButton :loading="busy === 'questions'" :disabled="Boolean(busy) || !configured" @click="generate"
            >重新出题</LiquidGlassButton
          >
          <LiquidGlassButton :loading="busy === 'run'" :disabled="Boolean(busy) || !configured || running" @click="run"
            >立即评测</LiquidGlassButton
          >
          <small v-if="current?.setting.lastGeneratedAt">题目生成于 {{ formatDateTime(current.setting.lastGeneratedAt) }}</small>
          <small v-else-if="configured">还没有题目，第一次评测前会自动出题</small>
        </div>
        <p v-if="generationNote" class="eval-generation-note" role="status">{{ generationNote }}</p>
      </section>

      <section v-if="current?.failures.length" class="eval-failures" aria-label="没通过的问题">
        <header><strong>最近一次没通过的问题</strong></header>
        <LiquidGlassTable :loading="false" :empty="false" source="backdrop" presentation="collection">
          <el-table :data="current.failures" empty-text=" ">
            <el-table-column label="问题" min-width="220"
              ><template #default="{ row }">{{ row.question || '—' }}</template></el-table-column
            >
            <el-table-column label="原因" min-width="200"
              ><template #default="{ row }">{{ row.reason || '—' }}</template></el-table-column
            >
            <el-table-column label="耗时" min-width="80" align="right"
              ><template #default="{ row }">{{ seconds(row.elapsedMs) }}</template></el-table-column
            >
            <el-table-column label="" min-width="90"
              ><template #default="{ row }"
                ><LiquidGlassButton v-if="row.traceId" text @click="router.push(`/runops/${encodeURIComponent(row.traceId)}`)"
                  >看运行记录</LiquidGlassButton
                ></template
              ></el-table-column
            >
          </el-table>
        </LiquidGlassTable>
      </section>

      <section v-if="current?.runs.length" class="eval-history" aria-label="评测记录">
        <header><strong>评测记录</strong></header>
        <LiquidGlassTable :loading="loading" :empty="false" source="backdrop" presentation="collection">
          <el-table :data="current.runs" empty-text=" " row-key="experimentId">
            <el-table-column label="时间" min-width="170"
              ><template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template></el-table-column
            >
            <el-table-column label="状态" min-width="100"
              ><template #default="{ row }">{{ runStatus(row) }}</template></el-table-column
            >
            <el-table-column label="通过率" min-width="90" align="right"
              ><template #default="{ row }">{{ percent(row.passRate) }}</template></el-table-column
            >
            <el-table-column label="一般耗时" min-width="100" align="right"
              ><template #default="{ row }">{{ seconds(row.p50LatencyMs) }}</template></el-table-column
            >
            <el-table-column label="题目" min-width="90" align="right"
              ><template #default="{ row }">{{ row.completedTaskCount ?? 0 }}/{{ row.taskCount ?? 0 }}</template></el-table-column
            >
            <el-table-column label="发起" min-width="120"
              ><template #default="{ row }">{{ String(row.createdBy || '').startsWith('system') ? '每日自动' : '手动' }}</template></el-table-column
            >
          </el-table>
        </LiquidGlassTable>
      </section>
    </template>
  </div>
</template>

<style scoped lang="scss">
.eval-panel {
  display: grid;
  gap: 16px;
  min-width: 0;
}
.view-description {
  margin: 0;
  color: var(--text-secondary);
  font-size: 13px;
  line-height: 1.6;
}
.eval-loading {
  min-height: 160px;
}
.eval-summary {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: 12px;
}
.eval-summary__item {
  display: grid;
  gap: 4px;
  padding: 12px 14px;
  border: 1px solid var(--border-subtle, rgba(127, 127, 127, 0.18));
  border-radius: 12px;
  min-width: 0;

  span {
    color: var(--text-secondary);
    font-size: 12px;
  }
  strong {
    color: var(--text-primary);
    font-size: 20px;
    font-weight: 650;
    font-variant-numeric: tabular-nums;
  }
  small {
    color: var(--text-muted);
    font-size: 12px;
  }
}
.eval-summary__time {
  font-size: 15px !important;
}
.eval-settings,
.eval-pending,
.eval-failures,
.eval-history {
  display: grid;
  gap: 10px;
  padding: 12px 14px;
  border: 1px solid var(--border-divider);
  border-radius: 12px;
  min-width: 0;

  header {
    display: flex;
    flex-direction: column;
    gap: 2px;

    strong {
      color: var(--text-primary);
      font-size: 14px;
    }
    small {
      color: var(--text-muted);
      font-size: 12px;
    }
  }
}
.eval-form {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: 0 16px;
}
.eval-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;

  small {
    color: var(--text-muted);
    font-size: 12px;
  }
}
.eval-account-hint {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-top: 6px;

  small {
    color: var(--el-text-color-secondary);
  }
}
</style>
