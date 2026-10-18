<template>
  <WorkbenchPage class="run-conversations-page" layout="list">
    <PageHeader
      variant="overview"
      domain="governance"
      eyebrow="运行中心"
      title="对话"
      description="业务用户在嵌入式对话里问了什么、哪一轮出了问题，点开即可查看对应运行。"
    >
      <template #meta>
        <RunCenterNav />
      </template>
      <template #actions>
        <el-button :icon="Refresh" :loading="loading" @click="loadList">刷新</el-button>
      </template>
    </PageHeader>

    <section class="conversation-filters glass-surface-control" aria-label="对话筛选">
      <el-input
        v-model="filters.keyword"
        class="keyword-filter"
        clearable
        placeholder="业务用户 / 会话 ID / 页面路由"
        :prefix-icon="Search"
        @keyup.enter="applyFilters"
        @clear="applyFilters"
      />
      <el-select v-model="filters.agentId" clearable filterable placeholder="全部 Agent" class="agent-filter" @change="applyFilters">
        <el-option v-for="agent in agents" :key="agent.id" :label="agent.name || agent.keySlug" :value="agent.id" />
      </el-select>
      <el-select v-model="filters.days" class="days-filter" aria-label="时间范围" @change="applyFilters">
        <el-option label="最近 1 天" :value="1" />
        <el-option label="最近 7 天" :value="7" />
        <el-option label="最近 30 天" :value="30" />
      </el-select>
      <el-checkbox v-model="filters.problemsOnly" @change="applyFilters">只看有问题的</el-checkbox>
    </section>

    <el-alert v-if="loadError" type="error" :title="loadError" :closable="false" show-icon />

    <div class="conversation-layout">
      <section class="conversation-list glass-surface-panel" aria-label="对话列表" v-loading="loading">
        <el-empty v-if="!loading && conversations.length === 0" description="这段时间没有嵌入式对话" :image-size="72" />
        <button
          v-for="item in conversations"
          :key="item.sessionId"
          type="button"
          class="conversation-item"
          :class="{ 'is-active': item.sessionId === selectedId }"
          @click="select(item.sessionId)"
        >
          <div class="conversation-item__head">
            <strong>{{ conversationUserLabel(item) }}</strong>
            <el-tag size="small" :type="conversationBadge(item).type" effect="light">{{ conversationBadge(item).label }}</el-tag>
          </div>
          <p class="conversation-item__question">{{ item.lastQuestion || '（没有提问）' }}</p>
          <div class="conversation-item__meta">
            <span>{{ agentName(item.agentId) }}</span>
            <span>{{ item.questionCount }} 轮</span>
            <span>{{ formatTime(item.lastActiveAt) }}</span>
          </div>
        </button>
      </section>

      <section class="conversation-detail glass-surface-panel" aria-label="对话详情" v-loading="detailLoading">
        <el-empty v-if="!detail && !detailLoading" description="选择左侧的一次对话" :image-size="72" />
        <template v-if="detail">
          <header class="detail-head">
            <div>
              <h2>{{ conversationUserLabel(detail.conversation) }} · {{ agentName(detail.conversation.agentId) }}</h2>
              <p>
                {{ detail.conversation.projectCode || '-' }}
                <template v-if="detail.conversation.route"> · {{ detail.conversation.route }}</template>
                · 开始于 {{ formatTime(detail.conversation.startedAt) }}
              </p>
            </div>
            <el-button link :icon="CopyDocument" @click="copySessionId">复制会话 ID</el-button>
          </header>
          <el-alert
            v-if="!detail.runsAvailable"
            type="warning"
            :closable="false"
            show-icon
            title="运行状态暂时取不到，下面只显示对话内容。"
          />
          <ol class="turn-list">
            <li
              v-for="turn in detail.turns"
              :key="turn.turnNo"
              :ref="element => registerTurn(turn.turnNo, element)"
              class="turn"
              :class="`is-${turnState(turn, detail.runsAvailable).tone}`"
            >
              <div class="turn__head">
                <span class="turn__no">第 {{ turn.turnNo }} 轮</span>
                <el-tag size="small" :type="tagType(turnState(turn, detail.runsAvailable).tone)" effect="plain">
                  {{ turnState(turn, detail.runsAvailable).label }}
                </el-tag>
                <span v-if="turn.run?.latencyMs != null" class="turn__latency">{{ formatLatency(turn.run.latencyMs) }}</span>
                <el-button
                  v-if="canEvaluate && turn.run?.traceId && detail.conversation.agentId"
                  link
                  class="turn__open"
                  @click="openAddToEval(turn.run.traceId, turn.question)"
                >
                  加入评测集
                </el-button>
                <el-button
                  v-if="turn.run?.traceId"
                  link
                  type="primary"
                  :class="{ turn__open: !canEvaluate || !detail.conversation.agentId }"
                  @click="openRun(turn.run.traceId)"
                >
                  查看运行 →
                </el-button>
              </div>
              <div v-if="turn.question" class="bubble bubble--user">
                <small>{{ formatTime(turn.askedAt) }}</small>
                <p>{{ turn.question }}</p>
              </div>
              <div v-if="turn.answer" class="bubble bubble--assistant">
                <small>{{ formatTime(turn.answeredAt) }}</small>
                <p>{{ turn.answer }}</p>
              </div>
              <p v-if="runErrorText(turn.run)" class="turn__error">{{ runErrorText(turn.run) }}</p>
            </li>
          </ol>
        </template>
      </section>
    </div>

    <AddToEvalDialog
      v-if="detail?.conversation.agentId"
      v-model="addToEvalVisible"
      :trace-id="addToEvalTraceId"
      :agent-id="detail.conversation.agentId"
      :question="addToEvalQuestion"
    />
  </WorkbenchPage>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref, type ComponentPublicInstance } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { CopyDocument, Refresh, Search } from '@element-plus/icons-vue'
import PageHeader from '@/components/common/PageHeader.vue'
import WorkbenchPage from '@/components/common/WorkbenchPage.vue'
import { getRunCenterConversation, listRunCenterConversations } from '@/api/runops'
import { listAgents } from '@/api/workflow'
import type { ConversationDetail, ConversationSummary } from '@/types/runops'
import type { Agent } from '@/types/workflow'
import RunCenterNav from './components/RunCenterNav.vue'
import AddToEvalDialog from './components/AddToEvalDialog.vue'
import { platformSessionUser } from '@/auth/platformSession'
import { hasPlatformPermission, PLATFORM_PERMISSION_AGENT_EVALUATE } from '@/auth/platformAccess'
import { conversationBadge, conversationUserLabel, firstProblemTurn, runErrorText, turnState, type TurnTone } from './conversationView'

const route = useRoute()
const router = useRouter()

const queryText = (key: string) => (typeof route.query[key] === 'string' ? String(route.query[key]).trim() : '')

const filters = reactive({
  keyword: queryText('keyword'),
  agentId: queryText('agentId'),
  days: Number(route.query.days) > 0 ? Number(route.query.days) : 7,
  problemsOnly: route.query.problemsOnly === 'true',
})
const loading = ref(false)
const loadError = ref('')
const conversations = ref<ConversationSummary[]>([])
const agents = ref<Agent[]>([])
const selectedId = ref(queryText('sessionId'))
const detail = ref<ConversationDetail>()
const detailLoading = ref(false)
const turnElements = new Map<number, Element>()
let detailRequest = 0
const canEvaluate = computed(() => hasPlatformPermission(platformSessionUser.value?.permissions, PLATFORM_PERMISSION_AGENT_EVALUATE))
const addToEvalVisible = ref(false)
const addToEvalTraceId = ref('')
const addToEvalQuestion = ref<string | null>(null)

function openAddToEval(traceId: string, question?: string | null) {
  addToEvalTraceId.value = traceId
  addToEvalQuestion.value = question ?? null
  addToEvalVisible.value = true
}

function registerTurn(turnNo: number, element: Element | ComponentPublicInstance | null) {
  if (element instanceof Element) turnElements.set(turnNo, element)
  else turnElements.delete(turnNo)
}

function agentName(agentId?: string | null) {
  if (!agentId) return '-'
  const agent = agents.value.find(item => item.id === agentId)
  return agent?.name || agent?.keySlug || agentId
}

function formatTime(value?: string | null) {
  return value ? value.replace('T', ' ').slice(0, 19) : '-'
}

function formatLatency(ms: number) {
  return ms >= 1000 ? `${(ms / 1000).toFixed(1)} 秒` : `${ms} 毫秒`
}

function tagType(tone: TurnTone) {
  if (tone === 'failed') return 'danger'
  if (tone === 'waiting' || tone === 'running') return 'warning'
  if (tone === 'ok') return 'success'
  return 'info'
}

function syncQuery() {
  router.replace({
    query: {
      ...(filters.keyword ? { keyword: filters.keyword } : {}),
      ...(filters.agentId ? { agentId: filters.agentId } : {}),
      ...(filters.days !== 7 ? { days: String(filters.days) } : {}),
      ...(filters.problemsOnly ? { problemsOnly: 'true' } : {}),
      ...(selectedId.value ? { sessionId: selectedId.value } : {}),
    },
  })
}

function applyFilters() {
  syncQuery()
  loadList()
}

async function loadList() {
  loading.value = true
  loadError.value = ''
  try {
    const { data } = await listRunCenterConversations({
      keyword: filters.keyword || undefined,
      agentId: filters.agentId || undefined,
      days: filters.days,
      problemsOnly: filters.problemsOnly || undefined,
    })
    conversations.value = Array.isArray(data) ? data : []
    if (!selectedId.value && conversations.value.length) await select(conversations.value[0].sessionId, false)
  } catch (error) {
    loadError.value = error instanceof Error && error.message ? `对话加载失败：${error.message}` : '对话加载失败'
  } finally {
    loading.value = false
  }
}

async function select(sessionId: string, updateQuery = true) {
  selectedId.value = sessionId
  if (updateQuery) syncQuery()
  const request = ++detailRequest
  detailLoading.value = true
  try {
    const { data } = await getRunCenterConversation(sessionId)
    if (request !== detailRequest) return
    detail.value = data
    await nextTick()
    const problem = firstProblemTurn(data.turns, data.runsAvailable)
    if (problem) turnElements.get(problem.turnNo)?.scrollIntoView({ block: 'center', behavior: 'smooth' })
  } catch {
    if (request === detailRequest) detail.value = undefined
  } finally {
    if (request === detailRequest) detailLoading.value = false
  }
}

function openRun(traceId: string) {
  router.push({ name: 'RunOpsDetail', params: { traceId } })
}

async function copySessionId() {
  if (!detail.value) return
  try {
    await navigator.clipboard.writeText(detail.value.conversation.sessionId)
    ElMessage.success('已复制会话 ID')
  } catch {
    ElMessage.warning('复制失败，请手动复制')
  }
}

async function loadAgents() {
  try {
    const { data } = await listAgents(undefined, { errorFeedback: 'local' })
    agents.value = Array.isArray(data) ? data : []
  } catch {
    agents.value = []
  }
}

onMounted(async () => {
  void loadAgents()
  if (selectedId.value) void select(selectedId.value, false)
  await loadList()
})
</script>

<style scoped lang="scss">
.run-conversations-page {
  width: min(100%, 1840px);
  margin-inline: auto;
}

.conversation-filters {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
  padding: 10px 12px;
  border-radius: var(--radius-md);
}

.keyword-filter {
  width: 280px;
}

.agent-filter {
  width: 200px;
}

.days-filter {
  width: 130px;
}

.conversation-layout {
  display: grid;
  grid-template-columns: minmax(280px, 360px) minmax(0, 1fr);
  gap: 12px;
  min-height: 560px;
}

.conversation-list,
.conversation-detail {
  min-width: 0;
  padding: 8px;
  border-radius: var(--radius-md);
  overflow: auto;
}

.conversation-list {
  display: flex;
  flex-direction: column;
  gap: 4px;
  max-height: calc(100vh - 260px);
}

.conversation-item {
  display: flex;
  flex-direction: column;
  gap: 4px;
  width: 100%;
  padding: 10px 12px;
  border: 1px solid transparent;
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--text-primary);
  text-align: left;
  cursor: pointer;
}

.conversation-item:hover,
.conversation-item.is-active {
  background: var(--surface-glass-selected);
}

.conversation-item.is-active {
  border-color: rgb(var(--brand-primary-rgb) / 0.18);
}

.conversation-item__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.conversation-item__question {
  margin: 0;
  overflow: hidden;
  color: var(--text-secondary);
  font-size: 13px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.conversation-item__meta {
  display: flex;
  gap: 10px;
  color: var(--text-secondary);
  font-size: 12px;
}

.conversation-detail {
  padding: 14px 16px;
}

.detail-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 10px;
}

.detail-head h2 {
  margin: 0;
  font-size: 16px;
}

.detail-head p {
  margin: 4px 0 0;
  color: var(--text-secondary);
  font-size: 12px;
}

.turn-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
  margin: 12px 0 0;
  padding: 0;
  list-style: none;
}

.turn {
  padding: 10px 12px;
  border: 1px solid var(--border-subtle);
  border-left-width: 3px;
  border-radius: var(--radius-sm);
}

.turn.is-failed {
  border-left-color: var(--el-color-danger);
}

.turn.is-waiting,
.turn.is-running {
  border-left-color: var(--el-color-warning);
}

.turn.is-ok {
  border-left-color: var(--el-color-success);
}

.turn__head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}

.turn__no {
  font-size: 12px;
  font-weight: 700;
}

.turn__latency {
  color: var(--text-secondary);
  font-size: 12px;
}

.turn__open {
  margin-left: auto;
}

.bubble {
  max-width: 85%;
  margin-top: 6px;
  padding: 8px 10px;
  border-radius: var(--radius-sm);
  background: var(--surface-glass-control);
}

.bubble--user {
  margin-left: auto;
  background: var(--surface-glass-selected);
}

.bubble small {
  color: var(--text-secondary);
  font-size: 11px;
}

.bubble p {
  margin: 2px 0 0;
  white-space: pre-wrap;
  word-break: break-word;
}

.turn__error {
  margin: 8px 0 0;
  color: var(--el-color-danger);
  font-size: 12px;
  word-break: break-word;
}

@media (max-width: 900px) {
  .conversation-layout {
    grid-template-columns: 1fr;
  }

  .conversation-list {
    max-height: 320px;
  }
}
</style>
