<template>
  <LiquidGlassPage class="run-conversations-page">
    <PageHeader
      variant="overview"
      height-preset="compact"
      presentation="plain"
      action-presentation="inline"
      domain="governance"
      title="运行中心"
    >
      <template #actions>
        <RunCenterNav />
      </template>
    </PageHeader>

    <LiquidGlassCard source="wallpaper" class="conversation-workspace workbench-list-surface">
      <div class="conversation-toolbar">
        <LiquidGlassFilterBar class="conversation-filters" aria-label="对话筛选" :loading="loading" :show-reset="false" query-label="查询" @query="applyFilters">
          <LiquidGlassInput
            v-model="filters.keyword"
            source="wallpaper"
            class="keyword-filter"
            clearable
            placeholder="业务用户 / 会话 ID / 页面路由"
            aria-label="搜索对话"
            :prefix-icon="Search"
            @clear="applyFilters"
          />
          <LiquidGlassSelect v-model="filters.agentId" source="wallpaper" clearable filterable placeholder="全部 Agent" dropdown-title="Agent" aria-label="Agent" class="agent-filter" @change="applyFilters">
            <el-option v-for="agent in agents" :key="agent.id" :label="agent.name || agent.keySlug" :value="agent.id" />
          </LiquidGlassSelect>
          <LiquidGlassSelect v-model="filters.days" source="wallpaper" class="days-filter" dropdown-title="时间范围" aria-label="时间范围" @change="applyFilters">
            <el-option label="最近 1 天" :value="1" />
            <el-option label="最近 7 天" :value="7" />
            <el-option label="最近 30 天" :value="30" />
          </LiquidGlassSelect>
          <LiquidGlassButton
            source="wallpaper"
            native-type="button"
            :type="filters.problemsOnly ? 'primary' : undefined"
            :icon="WarningFilled"
            :aria-pressed="filters.problemsOnly"
            @click="toggleProblemsOnly"
          >
            只看有问题的
          </LiquidGlassButton>
        </LiquidGlassFilterBar>
      </div>

      <div class="conversation-layout" :class="{ 'has-conversations': !loadError && (conversations.length > 0 || selectedId) }" v-loading="loading" :aria-busy="loading">
        <LiquidGlassEmptyState v-if="loadError" compact class="conversation-empty" :icon="WarningFilled" title="对话加载失败" :description="loadError">
          <LiquidGlassButton @click="loadList()">重新加载</LiquidGlassButton>
        </LiquidGlassEmptyState>
        <LiquidGlassEmptyState v-else-if="!conversations.length && !selectedId" compact class="conversation-empty" :icon="ChatDotRound" :title="loading ? '正在加载对话…' : emptyTitle">
          <LiquidGlassButton v-if="!loading && hasAppliedFilters" link type="primary" @click="resetFilters">重置筛选</LiquidGlassButton>
        </LiquidGlassEmptyState>
        <template v-else>
          <aside class="conversation-list" aria-label="对话列表">
            <LiquidGlassEmptyState v-if="!conversations.length" compact title="当前范围暂无对话" />
            <button
              v-for="item in conversations"
              :key="item.sessionId"
              type="button"
              class="conversation-item"
              :class="{ 'is-active': item.sessionId === selectedId }"
              :aria-pressed="item.sessionId === selectedId"
              @click="select(item.sessionId)"
            >
              <div class="conversation-item__head">
                <strong :title="conversationUserLabel(item)">{{ conversationUserLabel(item) }}</strong>
                <el-tag size="small" :type="conversationBadge(item).type" effect="plain">{{ conversationBadge(item).label }}</el-tag>
              </div>
              <p class="conversation-item__question">{{ item.lastQuestion || '（没有提问）' }}</p>
              <div class="conversation-item__meta">
                <span class="conversation-item__agent" :title="agentName(item.agentId)">{{ agentName(item.agentId) }}</span>
                <span>{{ item.questionCount }} 轮</span>
              </div>
              <time class="conversation-item__time" :datetime="item.lastActiveAt || undefined">{{ formatTime(item.lastActiveAt) }}</time>
            </button>
          </aside>

          <section class="conversation-detail" aria-label="对话详情" v-loading="detailLoading" :aria-busy="detailLoading">
            <LiquidGlassEmptyState v-if="detailError" compact class="conversation-empty" :icon="WarningFilled" title="对话详情加载失败">
              <LiquidGlassButton @click="select(selectedId)">重新加载</LiquidGlassButton>
            </LiquidGlassEmptyState>
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
                <LiquidGlassButton link :icon="CopyDocument" @click="copySessionId">复制会话 ID</LiquidGlassButton>
              </header>
              <div ref="turnViewport" class="conversation-turns" tabindex="0" aria-label="对话内容">
              <LiquidGlassNotice
                v-if="!detail.runsAvailable"
                type="warning"
                title="运行状态暂时取不到，下面只显示对话内容。"
                source="backdrop"
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
                    <LiquidGlassButton
                      v-if="canEvaluate && turn.run?.traceId && detail.conversation.agentId"
                      link
                      class="turn__open"
                      @click="openAddToEval(turn.run.traceId, turn.question)"
                    >
                      加入评测集
                    </LiquidGlassButton>
                    <LiquidGlassButton
                      v-if="turn.run?.traceId"
                      link
                      type="primary"
                      :class="{ turn__open: !canEvaluate || !detail.conversation.agentId }"
                      @click="openRun(turn.run.traceId)"
                    >
                      查看运行 →
                    </LiquidGlassButton>
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
              </div>
            </template>
          </section>
        </template>
      </div>
    </LiquidGlassCard>

    <AddToEvalDialog
      v-if="detail?.conversation.agentId"
      v-model="addToEvalVisible"
      :trace-id="addToEvalTraceId"
      :agent-id="detail.conversation.agentId"
      :question="addToEvalQuestion"
    />
  </LiquidGlassPage>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, type ComponentPublicInstance } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ChatDotRound, CopyDocument, Search, WarningFilled } from '@element-plus/icons-vue'
import PageHeader from '@/components/common/PageHeader.vue'
import {
  LiquidGlassButton,
  LiquidGlassCard,
  LiquidGlassEmptyState,
  LiquidGlassFilterBar,
  LiquidGlassInput,
  LiquidGlassNotice,
  LiquidGlassPage,
  LiquidGlassSelect,
} from '@/components/common/liquid-glass'
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
const detailError = ref(false)
const appliedFilters = ref({ ...filters })
const hasAppliedFilters = computed(() => Boolean(appliedFilters.value.keyword || appliedFilters.value.agentId || appliedFilters.value.problemsOnly))
const emptyTitle = computed(() => hasAppliedFilters.value ? '没有符合条件的对话' : `近 ${appliedFilters.value.days} 天暂无对话`)
const turnViewport = ref<HTMLElement>()
const turnElements = new Map<number, Element>()
let listRequest = 0
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
  void loadList()
}

function resetFilters() {
  Object.assign(filters, { keyword: '', agentId: '', days: 7, problemsOnly: false })
  applyFilters()
}

function toggleProblemsOnly() {
  filters.problemsOnly = !filters.problemsOnly
  applyFilters()
}

async function loadList(preserveLinkedSession = false) {
  const request = ++listRequest
  const query = { ...filters }
  ++detailRequest
  detailLoading.value = false
  loading.value = true
  loadError.value = ''
  appliedFilters.value = query
  try {
    const { data } = await listRunCenterConversations({
      keyword: query.keyword || undefined,
      agentId: query.agentId || undefined,
      days: query.days,
      problemsOnly: query.problemsOnly || undefined,
    })
    if (request !== listRequest) return
    conversations.value = Array.isArray(data) ? data : []
    const nextId = conversations.value.find(item => item.sessionId === selectedId.value)?.sessionId
      || (preserveLinkedSession ? selectedId.value : '')
      || conversations.value[0]?.sessionId
    if (nextId) await select(nextId, false)
    else clearSelection()
    if (request === listRequest) syncQuery()
  } catch (error) {
    if (request !== listRequest) return
    conversations.value = []
    clearSelection()
    loadError.value = error instanceof Error && error.message ? error.message : '请稍后重新加载'
  } finally {
    if (request === listRequest) loading.value = false
  }
}

function clearSelection() {
  ++detailRequest
  selectedId.value = ''
  detail.value = undefined
  detailError.value = false
  detailLoading.value = false
  turnElements.clear()
}

async function select(sessionId: string, updateQuery = true) {
  selectedId.value = sessionId
  if (updateQuery) syncQuery()
  const request = ++detailRequest
  detail.value = undefined
  detailError.value = false
  turnElements.clear()
  detailLoading.value = true
  try {
    const { data } = await getRunCenterConversation(sessionId)
    if (request !== detailRequest) return
    detail.value = data
    await nextTick()
    if (request !== detailRequest) return
    const problem = firstProblemTurn(data.turns, data.runsAvailable)
    const element = problem && turnElements.get(problem.turnNo)
    const viewport = turnViewport.value
    if (element && viewport) {
      viewport.scrollTop = Math.max(0, viewport.scrollTop + element.getBoundingClientRect().top - viewport.getBoundingClientRect().top - 16)
    } else if (viewport) viewport.scrollTop = 0
  } catch {
    if (request === detailRequest) detailError.value = true
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
  await loadList(true)
})

onBeforeUnmount(() => { ++listRequest; ++detailRequest })
</script>

<style scoped lang="scss">
.run-conversations-page {
  width: min(100%, 1840px);
  margin-inline: auto;
}

.run-conversations-page :deep(.run-center-nav) {
  margin-top: 0;
}

.run-conversations-page > .conversation-workspace {
  display: flex;
  flex: 1 1 auto;
  flex-direction: column;
  min-height: 0;
  padding: 0;
  overflow: hidden;
}

.conversation-toolbar {
  flex: 0 0 auto;
  padding: var(--layout-panel-padding);
  border-bottom: 1px solid var(--border-subtle);
}

.conversation-filters :deep(.filter-bar__fields > .keyword-filter) {
  flex: 3 1 240px;
}

.conversation-filters :deep(.filter-bar__fields > .agent-filter) {
  flex: 1 1 160px;
}

.conversation-filters :deep(.filter-bar__fields > .days-filter) {
  flex: 0 1 140px;
}

.conversation-filters :deep(.filter-bar__fields > .liquid-glass-button) {
  flex: 0 0 auto;
}

.conversation-layout {
  display: flex;
  flex: 1;
  align-items: center;
  justify-content: center;
  min-height: 0;
  overflow: hidden;
}

.conversation-layout.has-conversations {
  display: grid;
  grid-template-columns: clamp(280px, 28%, 340px) minmax(0, 1fr);
  align-items: stretch;
}

.conversation-empty {
  align-self: center;
}

.conversation-list,
.conversation-detail {
  min-width: 0;
  min-height: 0;
}

.conversation-list {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 8px;
  overflow-y: auto;
  overscroll-behavior: contain;
  border-right: 1px solid var(--border-subtle);
}

.conversation-item {
  display: flex;
  flex: 0 0 auto;
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

.conversation-item:hover {
  background: var(--surface-glass-inset);
}

.conversation-item.is-active {
  border-color: var(--border-glass-edge);
  background: var(--surface-glass-inset);
  box-shadow: var(--glass-rim-double);
}

.conversation-item:focus-visible {
  outline: 2px solid var(--border-focus);
  outline-offset: -2px;
}

.conversation-item__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.conversation-item__head strong {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.conversation-item__head .el-tag {
  flex: 0 0 auto;
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

.conversation-item__agent {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.conversation-item__time {
  color: var(--text-muted);
  font-size: 12px;
  font-variant-numeric: tabular-nums;
}

.conversation-detail {
  display: flex;
  flex-direction: column;
  padding: var(--layout-panel-padding);
  overflow: hidden;
}

.detail-head {
  display: flex;
  flex: 0 0 auto;
  flex-wrap: wrap;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 10px;
}

.detail-head > div {
  flex: 1 1 240px;
  min-width: 0;
  overflow-wrap: anywhere;
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

.conversation-turns {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  overscroll-behavior: contain;
  scrollbar-gutter: stable;
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

.turn__head {
  display: flex;
  flex-wrap: wrap;
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
  .run-conversations-page {
    overflow-y: auto;
  }

  .run-conversations-page > .conversation-workspace {
    flex: 1 0 auto;
  }

  .conversation-layout {
    min-height: 240px;
  }

  .conversation-layout.has-conversations {
    grid-template-columns: 1fr;
    overflow: visible;
  }

  .conversation-list {
    max-height: 260px;
    border-right: 0;
    border-bottom: 1px solid var(--border-subtle);
  }

  .conversation-turns {
    overflow: visible;
  }
}

@media (max-width: 720px) {
  .conversation-filters :deep(.filter-bar__fields > .keyword-filter) {
    flex-basis: 100%;
  }

  .conversation-filters :deep(.filter-bar__fields > .agent-filter),
  .conversation-filters :deep(.filter-bar__fields > .days-filter) {
    flex: 1 1 100px;
  }
}
</style>
