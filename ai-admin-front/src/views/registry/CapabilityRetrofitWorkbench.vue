<template>
  <div class="retrofit-page project-workbench-page workbench-page--list">
    <PageHeader
      variant="standard"
      presentation="plain"
      action-presentation="inline"
      domain="project"
      :title="`用 AI 改造业务能力 · ${project?.name || projectCode}`"
      description="写一句目标，交给你自己的 Codex / Claude Code：它扫描代码、列出候选能力给你勾选，只改你选中的部分，再由你评审并发布给员工的 AI 工具。"
    >
      <template #actions>
        <LiquidGlassButton :loading="loading" @click="reload">刷新</LiquidGlassButton>
      </template>
    </PageHeader>

    <LiquidGlassNotice v-if="loadError" type="error" :title="loadError" />

    <div class="retrofit-grid">
      <LiquidGlassCard source="wallpaper" class="retrofit-card">
        <h2>新建改造</h2>
        <p class="muted">说清楚想让 AI 办哪些事，比如“能查应收款、能新建销售退货单”。不确定就写“扫描全部，列出适合开放的”。</p>
        <el-input
          v-model="objective"
          type="textarea"
          :rows="3"
          maxlength="2000"
          show-word-limit
          placeholder="例如：让 AI 能查询应收款、能新建销售退货单"
          aria-label="改造目标"
        />
        <div class="executor-row">
          <span>交给</span>
          <el-radio-group v-model="executorProvider" size="small">
            <el-radio-button value="CODEX">Codex</el-radio-button>
            <el-radio-button value="CLAUDE_CODE">Claude Code</el-radio-button>
            <el-radio-button value="CURSOR">Cursor</el-radio-button>
            <el-radio-button value="TRAE">Trae</el-radio-button>
          </el-radio-group>
        </div>

        <div class="prompt-block">
          <div class="prompt-head">
            <strong>已经用「AI 工具接入」连上平台的，交给你的 AI 就行</strong>
            <AiHandoffButton
              scenario="capability-retrofit"
              size="small"
              type="primary"
              :context="{ projectCode, objective: objective.trim() || '扫描业务代码，列出适合开放给 AI 的业务方法' }"
              missing-hint="当前项目还没有设置项目编码。"
            />
          </div>
          <p class="muted">AI 会自己建改造任务并领取，扫描完把候选清单交回这里等你勾选。</p>
        </div>

        <div class="prompt-block">
          <div class="prompt-head">
            <strong>还没连上平台？生成一次性交接提示词</strong>
            <LiquidGlassButton
              size="small"
              type="primary"
              :loading="creating"
              :disabled="!canWrite || !project?.id || !objective.trim()"
              @click="createHandoff"
            >
              新建任务并生成
            </LiquidGlassButton>
          </div>
          <p v-if="!canWrite" class="muted">你没有这个项目的修改权限。</p>
          <template v-if="latestHandoff">
            <pre class="prompt-text">{{ latestHandoff.prompt }}</pre>
            <div class="prompt-foot">
              <span class="muted">激活码一次有效，{{ formatTime(latestHandoff.activationExpiresAt) }} 前使用。</span>
              <LiquidGlassButton size="small" @click="copy(latestHandoff.prompt)">复制交接提示词</LiquidGlassButton>
            </div>
          </template>
        </div>
      </LiquidGlassCard>

      <LiquidGlassCard source="wallpaper" class="retrofit-card">
        <h2>改造记录</h2>
        <LiquidGlassEmptyState
          v-if="!loading && !tasks.length"
          title="还没有改造任务"
          description="在左边写目标，交给你的 AI 后，任务会出现在这里。"
        />
        <ul v-else class="task-list">
          <li v-for="task in tasks" :key="task.taskId">
            <button
              type="button"
              class="task-row"
              :class="{ active: task.taskId === selectedTaskId }"
              @click="selectTask(task.taskId)"
            >
              <span class="task-title">{{ task.objective || task.title }}</span>
              <span class="task-meta">
                <el-tag size="small" effect="plain" :type="statusTagType(task.executionStatus)">{{ statusLabel(task.executionStatus) }}</el-tag>
                <small>{{ task.createdBy || '—' }} · {{ formatTime(task.createdAt) }}</small>
              </span>
            </button>
          </li>
        </ul>
      </LiquidGlassCard>
    </div>

    <LiquidGlassCard v-if="selectedTaskId" source="wallpaper" class="retrofit-card detail-card">
      <div class="detail-head">
        <h2>{{ selectedTask?.objective || selectedTask?.title || selectedTaskId }}</h2>
        <LiquidGlassButton size="small" :loading="overviewLoading" @click="loadOverview(selectedTaskId)">刷新这条</LiquidGlassButton>
      </div>
      <ol class="stage-strip" aria-label="改造进度">
        <li v-for="stage in stages" :key="stage.key" :class="`stage--${stage.status}`">{{ stage.title }}</li>
      </ol>
      <p v-if="overviewError" class="error-message" role="alert">{{ overviewError }}</p>

      <section v-if="stage === 'INVENTORY'" class="detail-section">
        <p class="muted">等 AI 扫描代码、提交候选清单。如果迟迟没有动静，确认 AI 已经领取任务（连接状态：{{ connectionLabel }}）。</p>
      </section>

      <section v-if="candidates.length" class="detail-section">
        <div class="section-heading">
          <div>
            <h3>候选能力</h3>
            <p class="muted">{{ overview?.selection?.candidates?.summary || 'AI 按目标找到的业务方法。' }}</p>
          </div>
          <LiquidGlassButton
            v-if="stage === 'SELECT'"
            type="primary"
            :loading="answering"
            :disabled="!canWrite || !picked.length"
            @click="submitSelection"
          >
            确认开放选中的 {{ picked.length }} 项
          </LiquidGlassButton>
        </div>
        <el-table :data="candidates" row-key="candidateKey" class="candidate-table">
          <el-table-column width="56" label="开放">
            <template #default="{ row }">
              <el-checkbox
                :model-value="isPicked(row.candidateKey)"
                :disabled="stage !== 'SELECT'"
                :aria-label="`开放 ${row.title}`"
                @change="togglePick(row.candidateKey, $event)"
              />
            </template>
          </el-table-column>
          <el-table-column label="能力" min-width="200">
            <template #default="{ row }">
              <strong>{{ row.title }}</strong>
              <div class="muted small">{{ row.description }}</div>
            </template>
          </el-table-column>
          <el-table-column label="读写" width="110">
            <template #default="{ row }">
              <el-tag size="small" effect="plain" :type="sideEffectTagType(row.sideEffect)">{{ sideEffectLabel(row.sideEffect) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="代码位置" min-width="220">
            <template #default="{ row }">
              <code class="small">{{ row.symbol || row.sourceFile }}</code>
              <div v-if="row.path" class="muted small">{{ row.httpMethod }} {{ row.path }}</div>
            </template>
          </el-table-column>
          <el-table-column label="为什么 / 风险" min-width="240">
            <template #default="{ row }">
              <div class="small">{{ row.reason }}</div>
              <div v-if="row.risk" class="small risk">{{ row.risk }}</div>
            </template>
          </el-table-column>
        </el-table>
        <el-input
          v-if="stage === 'SELECT'"
          v-model="selectionNote"
          class="selection-note"
          placeholder="给 AI 的补充说明（可选），比如“退货先不开放”"
          maxlength="500"
        />
        <p v-else-if="overview?.selection?.answered" class="muted small">已由 {{ overview.selection.answeredBy || '开发者' }} 选定。</p>
      </section>

      <section v-if="stage === 'IMPLEMENT'" class="detail-section">
        <p class="muted">AI 正在给选中的方法加声明、编译测试并做 SDK 同步。完成后会提交改造报告。</p>
      </section>

      <section v-if="overview?.report" class="detail-section">
        <h3>改造报告</h3>
        <p>{{ overview.report.summary }}</p>
        <p class="muted small">
          SDK 同步：{{ overview.report.sdkSync?.status || '—' }}，{{ overview.report.sdkSync?.message }}
          <template v-if="overview.report.changedFiles?.length"> · 改了 {{ overview.report.changedFiles.length }} 个文件</template>
        </p>
        <ul v-if="overview.report.tests?.length" class="test-list">
          <li v-for="test in overview.report.tests" :key="test.name">
            <el-tag size="small" effect="plain" :type="test.status === 'PASS' ? 'success' : test.status === 'FAIL' ? 'danger' : 'info'">{{ test.status }}</el-tag>
            {{ test.name }} <code v-if="test.command" class="small">{{ test.command }}</code>
          </li>
        </ul>
      </section>

      <section v-if="overview?.capabilityStates?.length" class="detail-section">
        <div class="section-heading">
          <div>
            <h3>平台里的状态</h3>
            <p class="muted">新能力要在评审里被接受才算上线。写操作员工调用时会走审批。</p>
          </div>
          <LiquidGlassButton v-if="hasPendingReview" type="primary" @click="goReview">去评审</LiquidGlassButton>
        </div>
        <el-table :data="overview.capabilityStates" row-key="name">
          <el-table-column label="能力名" min-width="200">
            <template #default="{ row }">
              <code>{{ row.name }}</code>
              <div v-if="row.title" class="muted small">{{ row.title }}</div>
            </template>
          </el-table-column>
          <el-table-column label="读写" width="110">
            <template #default="{ row }">
              <el-tag v-if="row.sideEffect" size="small" effect="plain" :type="sideEffectTagType(row.sideEffect)">{{ sideEffectLabel(row.sideEffect) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="150">
            <template #default="{ row }">
              <el-tag size="small" :type="capabilityStateTagType(row.state)">{{ capabilityStateLabel(row.state) }}</el-tag>
            </template>
          </el-table-column>
        </el-table>
      </section>

      <section v-if="acceptedStates.length" class="detail-section publish-section">
        <h3>发布给员工 AI 工具</h3>
        <p class="muted">把已接受的 {{ acceptedStates.length }} 个能力加进员工 MCP 发布，并发布新版本。员工在自己的 Codex / Claude Code 里就能用到。</p>
        <p v-if="!canPublish" class="muted">你没有管理 MCP 发布的权限，请联系平台管理员发布。</p>
        <div v-else class="publish-row">
          <el-select v-model="publicationId" placeholder="选择员工 MCP 发布" :loading="publicationsLoading" style="min-width: 260px">
            <el-option v-for="publication in publications" :key="publication.id" :label="publication.name" :value="publication.id" />
          </el-select>
          <LiquidGlassButton type="primary" :loading="publishing" :disabled="!publicationId" @click="publishToEmployees">
            加入并发布
          </LiquidGlassButton>
        </div>
        <p v-if="publishMessage" class="muted small" role="status">{{ publishMessage }}</p>
      </section>
    </LiquidGlassCard>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import PageHeader from '@/components/common/PageHeader.vue'
import {
  LiquidGlassButton, LiquidGlassCard, LiquidGlassEmptyState, LiquidGlassNotice,
} from '@/components/common/liquid-glass'
import { getCapabilityRetrofitOverview, type CapabilityRetrofitOverview } from '@/api/aiCodingTasks'
import { getScanProjects } from '@/api/scanProject'
import { listAllTools } from '@/api/tool'
import {
  addMcpPublicationItem, getMcpPublication, listMcpPublications, publishMcpPublication,
} from '@/api/mcp'
import { useAiCodingTask } from '@/composables/useAiCodingTask'
import { platformSessionUser } from '@/auth/platformSession'
import {
  PLATFORM_PERMISSION_MCP_PUBLICATION_MANAGE,
  PLATFORM_PERMISSION_WRITE,
  hasPlatformPermission,
} from '@/auth/platformAccess'
import AiHandoffButton from '@/components/ai-access/AiHandoffButton.vue'
import { copyAiCodingText } from '@/utils/aiCodingClipboard'
import type { AiCodingExecutorProvider, AiCodingExecutionStatus } from '@/types/aiCodingTask'
import type { McpPublication } from '@/types/mcp'
import type { ScanProject } from '@/types/scanProject'
import {
  CAPABILITY_RETROFIT_TASK_KIND,
  capabilityStateLabel,
  capabilityStateTagType,
  currentRetrofitStage,
  matchCatalogTool,
  retrofitStages,
  selectionAnswer,
  sideEffectLabel,
  sideEffectTagType,
} from './capabilityRetrofitViewModel'

const route = useRoute()
const router = useRouter()
const projectCode = computed(() => String(route.params.projectCode || ''))
const project = ref<ScanProject | null>(null)
const loadError = ref('')
const objective = ref('')
const executorProvider = ref<AiCodingExecutorProvider>('CODEX')
const creating = ref(false)
const selectedTaskId = ref('')
const overview = ref<CapabilityRetrofitOverview | null>(null)
const overviewLoading = ref(false)
const overviewError = ref('')
const picked = ref<string[]>([])
const selectionNote = ref('')
const answering = ref(false)
const publications = ref<McpPublication[]>([])
const publicationsLoading = ref(false)
const publicationId = ref<number | null>(null)
const publishing = ref(false)
const publishMessage = ref('')

const {
  tasks, selectedTaskDetail, latestHandoff, loading, loadTasks, createTask, refreshTask, answerQuestion,
} = useAiCodingTask()

const permissions = computed(() => platformSessionUser.value?.permissions)
const canWrite = computed(() => hasPlatformPermission(permissions.value, PLATFORM_PERMISSION_WRITE))
const canPublish = computed(() => hasPlatformPermission(permissions.value, PLATFORM_PERMISSION_MCP_PUBLICATION_MANAGE))
const selectedTask = computed(() => tasks.value.find((task) => task.taskId === selectedTaskId.value) || selectedTaskDetail.value?.task)
const stage = computed(() => currentRetrofitStage(overview.value))
const stages = computed(() => retrofitStages(overview.value))
const candidates = computed(() => overview.value?.selection?.candidates?.candidates || [])
const acceptedStates = computed(() => (overview.value?.capabilityStates || []).filter((state) => state.state === 'ACCEPTED'))
const hasPendingReview = computed(() => (overview.value?.capabilityStates || []).some((state) => state.state === 'PENDING_REVIEW'))
const connectionLabel = computed(() => {
  const status = selectedTaskDetail.value?.task.connection?.status
  if (status === 'ACTIVE') return '已连接'
  if (status === 'WAITING_CONNECT') return '等 AI 领取'
  if (status === 'TIMED_OUT') return '连接超时'
  if (status === 'CLOSED') return '已断开'
  return '未知'
})

async function loadProject() {
  const { data } = await getScanProjects()
  project.value = data.find((item) => item.projectCode === projectCode.value) || null
  if (!project.value) loadError.value = `没有找到项目 ${projectCode.value}`
}

async function reload() {
  loadError.value = ''
  try {
    if (!project.value) await loadProject()
    if (!project.value?.id) return
    const rows = await loadTasks({ projectId: project.value.id, taskKind: CAPABILITY_RETROFIT_TASK_KIND, limit: 50 })
    if (!selectedTaskId.value && rows.length) await selectTask(rows[0].taskId)
    else if (selectedTaskId.value) await loadOverview(selectedTaskId.value)
  } catch (error) {
    loadError.value = `加载失败：${(error as Error).message || '请稍后重试'}`
  }
}

async function selectTask(taskId: string) {
  selectedTaskId.value = taskId
  picked.value = []
  selectionNote.value = ''
  publishMessage.value = ''
  await loadOverview(taskId)
}

async function loadOverview(taskId: string) {
  overviewLoading.value = true
  overviewError.value = ''
  try {
    const [{ data }] = await Promise.all([getCapabilityRetrofitOverview(taskId), refreshTask(taskId)])
    if (taskId !== selectedTaskId.value) return
    overview.value = data
    if (data.selection?.answered) picked.value = [...data.selection.selected]
    else if (!picked.value.length) {
      picked.value = (data.selection?.candidates?.candidates || [])
        .filter((candidate) => candidate.recommended !== false && candidate.sideEffect === 'READ')
        .map((candidate) => candidate.candidateKey)
    }
    if (acceptedStates.value.length && canPublish.value && !publications.value.length) await loadPublications()
  } catch (error) {
    overviewError.value = `读取改造进度失败：${(error as Error).message || '请稍后重试'}`
  } finally {
    overviewLoading.value = false
  }
}

function isPicked(key: string) {
  return picked.value.includes(key)
}

function togglePick(key: string, checked: unknown) {
  picked.value = checked ? [...new Set([...picked.value, key])] : picked.value.filter((item) => item !== key)
}

async function submitSelection() {
  const questionId = overview.value?.selection?.questionId
  if (!questionId || !selectedTaskId.value) return
  const risky = candidates.value.filter((candidate) => picked.value.includes(candidate.candidateKey) && candidate.sideEffect !== 'READ')
  if (risky.length) {
    await ElMessageBox.confirm(
      `选中的「${risky.map((item) => item.title).join('、')}」会改数据。上线后员工调用这类能力会先进入审批。确定开放吗？`,
      '确认开放写操作',
      { type: 'warning', confirmButtonText: '确定开放', cancelButtonText: '再看看' },
    )
  }
  answering.value = true
  try {
    await answerQuestion(selectedTaskId.value, questionId, selectionAnswer(picked.value, selectionNote.value))
    ElMessage.success('已告诉 AI 你的选择')
    await loadOverview(selectedTaskId.value)
  } catch (error) {
    ElMessage.error(`提交失败：${(error as Error).message || '请稍后重试'}`)
  } finally {
    answering.value = false
  }
}

async function createHandoff() {
  if (!project.value?.id) return
  creating.value = true
  try {
    const goal = objective.value.trim()
    const { task } = await createTask({
      projectId: project.value.id,
      projectCode: project.value.projectCode || projectCode.value,
      taskKind: CAPABILITY_RETROFIT_TASK_KIND,
      executorProvider: executorProvider.value,
      title: `业务能力改造：${goal.slice(0, 60)}`,
      objective: goal,
      targets: [{
        targetType: 'PROJECT',
        targetKey: project.value.projectCode || projectCode.value,
        targetRole: 'PRIMARY',
        accessMode: 'READ_WRITE',
        snapshot: { projectId: project.value.id, name: project.value.name, projectKind: project.value.projectKind },
      }],
    })
    await selectTask(task.taskId)
    ElMessage.success('任务已建好，复制交接提示词给你的 AI')
  } catch (error) {
    ElMessage.error(`新建失败：${(error as Error).message || '请稍后重试'}`)
  } finally {
    creating.value = false
  }
}

async function loadPublications() {
  publicationsLoading.value = true
  try {
    const { data } = await listMcpPublications({ limit: 100 })
    publications.value = (data.items || []).filter((item) => item.state !== 'ARCHIVED')
    const code = projectCode.value.toLowerCase()
    publicationId.value = publications.value.find((item) => item.name.toLowerCase().includes(code))?.id
      ?? publications.value[0]?.id ?? null
  } finally {
    publicationsLoading.value = false
  }
}

async function publishToEmployees() {
  if (!publicationId.value || !project.value?.id) return
  publishing.value = true
  publishMessage.value = ''
  try {
    const [tools, { data: detail }] = await Promise.all([
      listAllTools({ projectId: project.value.id, enabled: true }),
      getMcpPublication(publicationId.value),
    ])
    const existing = new Set(detail.items.filter((item) => item.sourceKind === 'CAPABILITY').map((item) => item.sourceRef))
    const missing: string[] = []
    let added = 0
    for (const state of acceptedStates.value) {
      const tool = matchCatalogTool(state, tools, projectCode.value)
      if (!tool) {
        missing.push(state.name)
        continue
      }
      if (existing.has(tool.name)) continue
      await addMcpPublicationItem(publicationId.value, { sourceKind: 'CAPABILITY', sourceRef: tool.name })
      existing.add(tool.name)
      added += 1
    }
    await publishMcpPublication(publicationId.value)
    publishMessage.value = `已加入 ${added} 个新能力并发布新版本。${missing.length ? `没在能力目录里找到：${missing.join('、')}，请到 MCP 发布页手动添加。` : ''}`
    ElMessage.success('已发布给员工 AI 工具')
  } catch (error) {
    publishMessage.value = `发布没有完成：${(error as Error).message || '请到 MCP 发布页查看'}。已加入的条目会保留在草稿里。`
  } finally {
    publishing.value = false
  }
}

function goReview() {
  router.push({
    name: 'RegistryProjectDetail',
    params: { projectCode: projectCode.value },
    query: { section: 'source-changes' },
  })
}

async function copy(text: string) {
  const result = await copyAiCodingText(text)
  if (result.copied) ElMessage.success('已复制')
  else ElMessage.warning('浏览器不允许自动复制，请手动选中复制')
}

function statusLabel(status: AiCodingExecutionStatus | string) {
  const labels: Record<string, string> = {
    READY: '等 AI 领取', RUNNING: '进行中', WAITING_USER: '等你操作', RESULT_SUBMITTED: '已交报告',
    RESULT_APPLIED: '等评审', ACCEPTANCE_READY: '可以验收', COMPLETED: '已完成', FAILED: '失败', CANCELLED: '已取消',
  }
  return labels[status] || status
}

function statusTagType(status: string): 'success' | 'warning' | 'danger' | 'info' {
  if (status === 'COMPLETED' || status === 'ACCEPTANCE_READY') return 'success'
  if (status === 'WAITING_USER' || status === 'RESULT_APPLIED') return 'warning'
  if (status === 'FAILED') return 'danger'
  return 'info'
}

function formatTime(value?: string | null) {
  if (!value) return '—'
  return value.replace('T', ' ').slice(0, 16)
}

watch(projectCode, () => {
  project.value = null
  selectedTaskId.value = ''
  overview.value = null
  void reload()
})

onMounted(() => {
  void reload()
})
</script>

<style scoped>
.retrofit-grid {
  display: grid;
  grid-template-columns: minmax(0, 3fr) minmax(0, 2fr);
  gap: 16px;
}
.retrofit-card {
  padding: 20px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.retrofit-card h2 {
  margin: 0;
  font-size: 16px;
}
.retrofit-card h3 {
  margin: 0 0 4px;
  font-size: 14px;
}
.muted {
  color: var(--el-text-color-secondary);
  margin: 0;
}
.small {
  font-size: 12px;
}
.risk {
  color: var(--el-color-warning);
}
.executor-row,
.publish-row,
.prompt-head,
.prompt-foot,
.detail-head,
.section-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}
.executor-row {
  justify-content: flex-start;
}
.prompt-block {
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 12px;
  padding: 12px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.prompt-text {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-all;
  font-size: 12px;
  max-height: 220px;
  overflow: auto;
  background: var(--el-fill-color-lighter);
  border-radius: 8px;
  padding: 10px;
}
.task-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 6px;
  max-height: 520px;
  overflow: auto;
}
.task-row {
  width: 100%;
  text-align: left;
  border: 1px solid transparent;
  border-radius: 10px;
  background: transparent;
  padding: 10px 12px;
  cursor: pointer;
  display: flex;
  flex-direction: column;
  gap: 4px;
  color: inherit;
}
.task-row:hover,
.task-row.active {
  border-color: var(--el-color-primary-light-5);
  background: var(--el-color-primary-light-9);
}
.task-title {
  font-weight: 600;
}
.task-meta {
  display: flex;
  gap: 8px;
  align-items: center;
}
.stage-strip {
  list-style: none;
  display: flex;
  gap: 8px;
  margin: 0;
  padding: 0;
  flex-wrap: wrap;
}
.stage-strip li {
  padding: 4px 12px;
  border-radius: 999px;
  font-size: 12px;
  border: 1px solid var(--el-border-color);
  color: var(--el-text-color-secondary);
}
.stage-strip .stage--done {
  color: var(--el-color-success);
  border-color: var(--el-color-success-light-5);
}
.stage-strip .stage--current {
  color: var(--el-color-primary);
  border-color: var(--el-color-primary);
  font-weight: 600;
}
.detail-section {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.test-list {
  margin: 0;
  padding-left: 18px;
  font-size: 13px;
}
.error-message {
  color: var(--el-color-danger);
  margin: 0;
}
.selection-note {
  max-width: 560px;
}
@media (max-width: 960px) {
  .retrofit-grid {
    grid-template-columns: 1fr;
  }
}
</style>
