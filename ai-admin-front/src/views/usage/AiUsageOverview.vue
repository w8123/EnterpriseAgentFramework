<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import {
  ArrowRight,
  ChatDotRound,
  Check,
  Connection,
  Cpu,
  Monitor,
  Refresh,
  Search,
  User,
} from '@element-plus/icons-vue'
import AppDrawer from '@/components/common/AppDrawer.vue'
import MetricStrip from '@/components/common/MetricStrip.vue'
import PageHeader from '@/components/common/PageHeader.vue'
import WorkbenchPanel from '@/components/common/WorkbenchPanel.vue'
import type { MetricStripItem } from '@/components/common/glassWorkbench'
import {
  LiquidGlassButton,
  LiquidGlassEmptyState,
  LiquidGlassFilterBar,
  LiquidGlassInput,
  LiquidGlassNotice,
  LiquidGlassPage,
  LiquidGlassSelect,
  LiquidGlassTable,
  LiquidGlassTabs,
} from '@/components/common/liquid-glass'
import { getAiUsageReport } from '@/api/mcp'
import type { AiUsagePerson, AiUsageReport } from '@/types/mcp'
import { useCatalogQuery } from '@/composables/useCatalogQuery'
import { usePlatformCan } from '@/auth/usePlatformCan'
import { PLATFORM_PERMISSION_RUNOPS_READ, PLATFORM_PERMISSION_SKILL_READ } from '@/auth/platformAccess'
import {
  approvalSummary,
  channelHints,
  channelLabel,
  filterPeople,
  formatCount,
  formatDateTime,
  formatRate,
  peopleSortOptions,
  periodOptions,
  skillStatusLabels,
  sortPeople,
  usesUnit,
} from './aiUsageView'

const router = useRouter()
const { can } = usePlatformCan()
const catalog = useCatalogQuery('usage', ['days', 'system', 'keyword', 'channel', 'sort', 'tab'])
const loading = ref(true)
const loadError = ref('')
const report = ref<AiUsageReport | null>(null)
const systems = ref<AiUsageReport['systems']>([])
const updatedAt = ref('')
const selected = ref<AiUsagePerson | null>(null)
const drawerOpen = ref(false)
const searchInput = ref<InstanceType<typeof LiquidGlassInput>>()
let requestSequence = 0
let requestController: AbortController | undefined

const days = computed(
  () => periodOptions.find((option) => String(option.value) === catalog.filters.value.days)?.value ?? 30,
)
const projectCode = computed(() => catalog.filters.value.system)
const channel = computed(() => catalog.filters.value.channel)
const sort = computed(
  () => peopleSortOptions.find((option) => option.value === catalog.filters.value.sort)?.value ?? 'activity',
)
const activeTab = computed({
  get: () =>
    ['operations', 'skills'].includes(catalog.filters.value.tab) ? catalog.filters.value.tab : 'people',
  set: (value: string) => {
    void catalog.applyField('tab', value === 'people' ? '' : value)
  },
})
const summary = computed(() => report.value?.summary)
const periodText = computed(() => periodOptions.find((option) => option.value === days.value)!.label)
const systemText = computed(
  () =>
    systems.value.find((system) => system.projectCode === projectCode.value)?.name ||
    projectCode.value ||
    '全部业务系统',
)
const hasActivity = computed(() =>
  Boolean(
    summary.value?.activePeople ||
    summary.value?.embedRuns ||
    summary.value?.toolCalls ||
    summary.value?.approvals.submitted,
  ),
)
const hasPeopleFilters = computed(() => Boolean(catalog.draft.keyword.trim() || channel.value))
const visiblePeople = computed(() =>
  sortPeople(filterPeople(report.value?.people ?? [], catalog.draft.keyword, channel.value), sort.value),
)
const activeRows = computed(() =>
  activeTab.value === 'people'
    ? visiblePeople.value
    : activeTab.value === 'operations'
      ? (report.value?.operations ?? [])
      : (report.value?.skills ?? []),
)
const pageSize = computed(() => catalog.size.value)
const currentPage = computed(() =>
  Math.min(catalog.page.value, Math.max(1, Math.ceil(activeRows.value.length / pageSize.value))),
)

// The shared pager emits page and size together; persist them in one navigation.
function changePage(page: number, size: number) {
  if (size !== pageSize.value) void catalog.changeSize(size)
  else void catalog.changePage(page)
}
const pagedPeople = computed(() =>
  visiblePeople.value.slice((currentPage.value - 1) * pageSize.value, currentPage.value * pageSize.value),
)
const pagedOperations = computed(() =>
  (report.value?.operations ?? []).slice(
    (currentPage.value - 1) * pageSize.value,
    currentPage.value * pageSize.value,
  ),
)
const pagedSkills = computed(() =>
  (report.value?.skills ?? []).slice(
    (currentPage.value - 1) * pageSize.value,
    currentPage.value * pageSize.value,
  ),
)
const channelIcons = { EMBED: ChatDotRound, CLAUDE_CODE: Monitor, CODEX: Cpu, OTHER: Connection }
const peopleLimitReached = computed(
  () => (summary.value?.activePeople ?? 0) > (report.value?.people.length ?? 0),
)
const noActivityTitle = computed(() =>
  report.value?.embedIncluded ? '当前范围暂无使用记录' : '当前范围暂无可查看的使用记录',
)
const tabDescription = computed(() => {
  if (activeTab.value === 'operations')
    return '员工通过自己的 AI 工具办理的业务，按调用与提交审批次数合计排序，最多展示前 20 项。'
  if (activeTab.value === 'skills')
    return `从运行记录生成的可复用 Skill，展示最近 ${report.value?.skills.length ?? 0} 项，最多 10 项。`
  return peopleLimitReached.value
    ? `共 ${formatCount(summary.value?.activePeople)} 位员工，当前仅载入使用次数最多的 ${formatCount(report.value?.people.length)} 位；搜索、排序与分页作用于已载入员工。`
    : '查看谁在使用 AI，打开员工详情追踪业务操作与审批结果。'
})

const metrics = computed<MetricStripItem[]>(() => {
  const value = summary.value
  return [
    {
      key: 'people',
      label: '使用人数',
      value: value ? formatCount(value.activePeople) : '—',
      hint: value ? `${formatCount(value.activeConnections)} 个使用中的 AI 工具连接` : '按员工去重',
      iconKey: 'agent-total',
      tone: 'brand',
    },
    {
      key: 'embed',
      label: '业务系统内对话',
      value: value && report.value?.embedIncluded ? formatCount(value.embedRuns) : '—',
      hint: !value
        ? '按对话轮次统计'
        : report.value?.embedIncluded
          ? `${formatCount(value.embedSessions)} 个会话`
          : '未纳入统计',
      iconKey: 'agent-page',
      tone: 'brand',
    },
    {
      key: 'tool-calls',
      label: 'AI 工具调用',
      value: value ? formatCount(value.toolCalls) : '—',
      hint: value?.toolCalls
        ? `成功率 ${formatRate(value.toolCalls, value.toolFailedCalls)} · 失败 ${formatCount(value.toolFailedCalls)}`
        : 'Claude Code、Codex 等',
      iconKey: 'api',
      tone: value?.toolFailedCalls ? 'warning' : 'brand',
    },
    {
      key: 'approvals',
      label: '写操作审批',
      value: value ? formatCount(value.approvals.submitted) : '—',
      hint: value?.approvals.submitted ? approvalSummary(value.approvals) : '暂无审批记录',
      iconKey: 'scan',
      tone: value?.approvals.pending ? 'warning' : 'brand',
    },
    {
      key: 'skills',
      label: '沉淀的 Skill',
      value: value ? formatCount(value.capturedSkills) : '—',
      hint: '从运行记录生成',
      iconKey: 'workflow-published',
      tone: 'brand',
    },
  ]
})

function extractErrorMessage(error: unknown): string {
  const failure = error as { response?: { data?: { message?: string; detail?: string } }; message?: string }
  return (
    failure?.response?.data?.message ||
    failure?.response?.data?.detail ||
    failure?.message ||
    '请检查网络后重试'
  )
}

async function reload() {
  const sequence = ++requestSequence
  requestController?.abort()
  const controller = new AbortController()
  requestController = controller
  loading.value = true
  loadError.value = ''
  drawerOpen.value = false
  selected.value = null
  try {
    const { data } = await getAiUsageReport(
      { days: days.value, projectCode: projectCode.value || undefined },
      controller.signal,
    )
    if (sequence !== requestSequence) return
    report.value = data
    systems.value = data.systems
    updatedAt.value = new Date().toLocaleTimeString('zh-CN', { hour12: false })
  } catch (error) {
    if (sequence !== requestSequence) return
    report.value = null
    loadError.value = extractErrorMessage(error)
  } finally {
    if (sequence === requestSequence) loading.value = false
  }
}

function selectChannel(value: string) {
  catalog.draft.channel = value === channel.value ? '' : value
  catalog.draft.tab = ''
  void catalog.applyFields(['channel', 'tab'])
}

function commitKeyword(event?: KeyboardEvent | string) {
  if (typeof event !== 'string' && event?.isComposing) return
  void catalog.applyField('keyword')
}

async function clearPeopleFilters() {
  await catalog.clear(['keyword', 'channel'])
  await nextTick()
  searchInput.value?.focus()
}

async function clearKeyword() {
  await catalog.applyField('keyword', '')
  await nextTick()
  searchInput.value?.focus()
}

function openPerson(person: AiUsagePerson) {
  if (loading.value) return
  selected.value = person
  drawerOpen.value = true
}

function openCalls(connectionId: number) {
  void router.push({ path: '/mcp-hub/call-logs', query: { connectionId: String(connectionId) } })
}

function openApprovals(person: AiUsagePerson) {
  void router.push({
    path: '/mcp-hub/write-approvals',
    query: { keyword: person.accounts[0]?.split(' · ').pop() ?? person.name, state: 'ALL' },
  })
}

function openConversations(person: AiUsagePerson) {
  void router.push({
    path: '/runops/conversations',
    query: { keyword: person.name, days: String(Math.min(days.value, 30)) },
  })
}

watch(
  [days, projectCode],
  () => {
    report.value = null
    updatedAt.value = ''
    void reload()
  },
  { immediate: true },
)
onBeforeUnmount(() => {
  ++requestSequence
  requestController?.abort()
})
</script>

<template>
  <LiquidGlassPage scrollable class="ai-usage-page">
    <PageHeader
      variant="overview"
      presentation="plain"
      action-presentation="inline"
      domain="governance"
      title="AI 使用情况"
      description="查看员工的 AI 使用、业务操作与审批结果。"
    >
      <template #actions
        ><LiquidGlassButton
          source="wallpaper"
          :icon="Refresh"
          :loading="loading"
          :disabled="loading"
          @click="reload"
          >刷新</LiquidGlassButton
        ></template
      >
    </PageHeader>

    <div class="usage-scope">
      <div class="scope-fields">
        <span class="scope-label">统计范围</span>
        <LiquidGlassSelect
          :model-value="days"
          source="wallpaper"
          class="period-select"
          dropdown-title="统计周期"
          aria-label="统计周期"
          @update:model-value="(value) => catalog.applyField('days', String(value))"
        >
          <el-option
            v-for="option in periodOptions"
            :key="option.value"
            :label="option.label"
            :value="option.value"
          />
        </LiquidGlassSelect>
        <LiquidGlassSelect
          :model-value="projectCode"
          source="wallpaper"
          class="system-select"
          clearable
          filterable
          placeholder="全部业务系统"
          dropdown-title="业务系统"
          aria-label="业务系统"
          @update:model-value="(value) => catalog.applyField('system', value || '')"
        >
          <el-option
            v-if="projectCode && !systems.some((system) => system.projectCode === projectCode)"
            :label="projectCode"
            :value="projectCode"
          />
          <el-option
            v-for="system in systems"
            :key="system.projectCode"
            :label="system.name"
            :value="system.projectCode"
          />
        </LiquidGlassSelect>
        <LiquidGlassButton
          v-if="days !== 30 || projectCode"
          source="wallpaper"
          link
          @click="catalog.clear(['days', 'system'])"
          >重置范围</LiquidGlassButton
        >
      </div>
      <span class="update-status" role="status" aria-live="polite">{{
        loading ? '正在更新…' : loadError ? '更新失败' : updatedAt ? `更新于 ${updatedAt}` : ''
      }}</span>
    </div>

    <MetricStrip :items="metrics" density="compact" aria-label="AI 使用概览" />
    <LiquidGlassNotice
      v-if="report?.embedNotice"
      source="wallpaper"
      type="info"
      title="业务系统内对话未纳入统计"
      :description="report.embedNotice"
    />
    <LiquidGlassNotice
      v-if="loadError"
      source="wallpaper"
      type="error"
      title="使用情况加载失败"
      :description="loadError"
      ><template #actions
        ><LiquidGlassButton :disabled="loading" @click="reload">重试</LiquidGlassButton></template
      ></LiquidGlassNotice
    >

    <div v-else class="usage-workspace" :class="{ 'usage-workspace--with-channels': hasActivity }">
      <WorkbenchPanel
        v-if="hasActivity"
        class="channel-panel"
        title="AI 工具分布"
        description="选择工具，筛选员工使用明细。"
      >
        <div class="channel-list" role="group" aria-label="按 AI 工具筛选员工">
          <button
            type="button"
            class="channel-choice channel-choice--all"
            :aria-pressed="!channel"
            :disabled="loading"
            @click="selectChannel('')"
          >
            <span>全部 AI 工具</span><strong>{{ formatCount(summary?.activePeople) }} 人</strong>
          </button>
          <button
            v-for="item in report?.channels ?? []"
            :key="item.channel"
            type="button"
            class="channel-choice"
            :aria-pressed="channel === item.channel"
            :disabled="loading || (item.channel === 'EMBED' && !report?.embedIncluded)"
            :title="channelHints[item.channel]"
            @click="selectChannel(item.channel)"
          >
            <span class="channel-heading"
              ><el-icon aria-hidden="true"><component :is="channelIcons[item.channel]" /></el-icon
              ><strong>{{ channelLabel(item.channel) }}</strong
              ><el-icon v-if="channel === item.channel" class="channel-check" aria-hidden="true"
                ><Check /></el-icon
              ><span v-else class="channel-count">{{
                item.channel === 'EMBED' && !report?.embedIncluded ? '—' : `${formatCount(item.people)} 人`
              }}</span></span
            >
            <span v-if="item.channel === 'EMBED' && !report?.embedIncluded" class="channel-detail"
              >未纳入统计</span
            >
            <template v-else>
              <span class="channel-detail"
                >{{ formatCount(item.uses) }} {{ usesUnit(item.channel)
                }}<span v-if="item.uses"> · 成功率 {{ formatRate(item.uses, item.failed) }}</span></span
              >
              <span class="channel-meter" aria-hidden="true"
                ><span
                  :style="{
                    width: `${Math.min(100, (item.people / Math.max(1, summary?.activePeople ?? 0)) * 100)}%`,
                  }"
              /></span>
              <span v-if="item.approvals.submitted" class="channel-approvals">{{
                approvalSummary(item.approvals)
              }}</span>
            </template>
          </button>
        </div>
        <template #footer><p class="panel-note">同一员工可使用多种 AI 工具，分项人数不相加。</p></template>
      </WorkbenchPanel>

      <WorkbenchPanel class="usage-detail-panel" aria-label="使用明细">
        <LiquidGlassTabs
          v-model="activeTab"
          presentation="underline"
          class="usage-tabs"
          aria-label="使用明细视图"
        >
          <el-tab-pane name="people"
            ><template #label
              >员工使用
              <span class="tab-count">{{ report ? formatCount(summary?.activePeople) : '—' }}</span></template
            ></el-tab-pane
          >
          <el-tab-pane name="operations" label="业务操作" />
          <el-tab-pane name="skills"
            ><template #label
              >沉淀 Skill
              <span class="tab-count">{{
                report ? formatCount(summary?.capturedSkills) : '—'
              }}</span></template
            ></el-tab-pane
          >
        </LiquidGlassTabs>
        <p v-if="hasActivity || loading || activeTab === 'skills'" class="view-description">
          {{ tabDescription }}
        </p>

        <div
          v-if="loading && !report"
          v-loading="true"
          class="initial-loading"
          element-loading-text="正在加载使用情况"
          role="status"
          aria-label="正在加载使用情况"
        />
        <div v-else-if="report && !hasActivity && activeTab !== 'skills'" class="usage-onboarding">
          <LiquidGlassEmptyState
            compact
            :icon="Connection"
            :title="noActivityTitle"
            :description="`${periodText} · ${systemText}。员工在业务系统中对话，或通过 Claude Code、Codex 等办理业务后，可在这里查看记录。`"
          >
            <LiquidGlassButton
              type="primary"
              :icon="Connection"
              @click="router.push('/mcp-hub/employee-connections')"
              >查看员工 AI 连接</LiquidGlassButton
            >
            <LiquidGlassButton v-if="days < 90" @click="catalog.applyField('days', '90')"
              >查看近 90 天</LiquidGlassButton
            >
            <LiquidGlassButton v-if="projectCode" @click="catalog.clear(['system'])"
              >查看全部业务系统</LiquidGlassButton
            >
          </LiquidGlassEmptyState>
          <div class="usage-entry-hints">
            <div>
              <el-icon aria-hidden="true"><ChatDotRound /></el-icon>
              <p><strong>业务系统内对话</strong><span>员工在业务页面使用 AI 助手，按对话轮次统计。</span></p>
            </div>
            <div>
              <el-icon aria-hidden="true"><Monitor /></el-icon>
              <p>
                <strong>员工自己的 AI 工具</strong
                ><span>员工连接业务系统后调用能力，按业务调用次数统计。</span>
              </p>
            </div>
          </div>
        </div>

        <template v-else>
          <div v-if="activeTab === 'people'" class="people-view">
            <LiquidGlassFilterBar class="people-filters" @query="commitKeyword()">
              <LiquidGlassInput
                ref="searchInput"
                v-model="catalog.draft.keyword"
                :prefix-icon="Search"
                clearable
                placeholder="搜索姓名、账号或业务系统"
                aria-label="搜索员工"
                @change="commitKeyword"
                @keydown.enter="commitKeyword"
                @clear="clearKeyword"
              />
              <LiquidGlassSelect
                :model-value="channel"
                class="mobile-channel-select"
                clearable
                placeholder="全部 AI 工具"
                dropdown-title="AI 工具"
                aria-label="AI 工具筛选"
                @update:model-value="(value) => selectChannel(value || '')"
              >
                <el-option
                  v-for="item in report?.channels ?? []"
                  :key="item.channel"
                  :label="channelLabel(item.channel)"
                  :value="item.channel"
                  :disabled="item.channel === 'EMBED' && !report?.embedIncluded"
                />
              </LiquidGlassSelect>
              <LiquidGlassSelect
                :model-value="sort"
                class="sort-select"
                dropdown-title="员工排序"
                aria-label="员工排序"
                @update:model-value="(value) => catalog.applyField('sort', value)"
                ><el-option
                  v-for="option in peopleSortOptions"
                  :key="option.value"
                  :label="option.label"
                  :value="option.value"
              /></LiquidGlassSelect>
              <template #actions
                ><LiquidGlassButton link :disabled="!hasPeopleFilters" @click="clearPeopleFilters"
                  >清除筛选</LiquidGlassButton
                ></template
              >
            </LiquidGlassFilterBar>
            <div class="people-result-summary" role="status" aria-live="polite">
              <span>{{
                hasPeopleFilters
                  ? `匹配 ${formatCount(visiblePeople.length)} 人`
                  : `已载入 ${formatCount(report?.people.length)} 人`
              }}</span
              ><el-tag
                v-if="channel"
                closable
                effect="plain"
                size="small"
                @close="catalog.clear(['channel'])"
                >{{ channelLabel(channel) }}</el-tag
              >
            </div>
            <LiquidGlassTable
              :current-page="currentPage"
              :page-size="pageSize"
              @change="changePage"
              :total="visiblePeople.length"
              :page-sizes="[10, 20, 50]"
              :loading="loading"
              :empty="!visiblePeople.length"
              pagination-label="员工使用分页"
              source="backdrop"
              presentation="collection"
              class="usage-data-table"
            >
              <template #empty
                ><LiquidGlassEmptyState
                  compact
                  :icon="Search"
                  title="没有匹配的员工"
                  description="试试其他姓名、账号，或清除 AI 工具筛选。"
                  ><LiquidGlassButton @click="clearPeopleFilters"
                    >清除筛选</LiquidGlassButton
                  ></LiquidGlassEmptyState
                ></template
              >
              <el-table :data="pagedPeople" empty-text=" " row-key="key">
                <el-table-column label="员工 / 账号" min-width="180"
                  ><template #default="{ row }"
                    ><div class="cell-stack">
                      <strong>{{ row.name }}</strong
                      ><small>{{ row.accounts.join('、') || '—' }}</small>
                    </div></template
                  ></el-table-column
                >
                <el-table-column label="AI 工具" min-width="170"
                  ><template #default="{ row }"
                    ><div class="tag-row">
                      <el-tag
                        v-for="item in row.channels"
                        :key="item"
                        size="small"
                        :type="item === 'EMBED' ? 'info' : 'primary'"
                        effect="light"
                        >{{ channelLabel(item) }}</el-tag
                      >
                    </div></template
                  ></el-table-column
                >
                <el-table-column label="对话 / 调用" min-width="135"
                  ><template #default="{ row }"
                    ><div class="cell-stack">
                      <span>{{
                        report?.embedIncluded ? `${formatCount(row.embedRuns)} 轮对话` : '对话未纳入'
                      }}</span
                      ><small
                        >{{ formatCount(row.toolCalls) }} 次调用<template v-if="row.toolFailed">
                          · <span class="danger">失败 {{ formatCount(row.toolFailed) }}</span></template
                        ></small
                      ><small v-if="row.embedFailed" class="danger"
                        >对话失败 {{ formatCount(row.embedFailed) }}</small
                      >
                    </div></template
                  ></el-table-column
                >
                <el-table-column label="写操作审批" min-width="150"
                  ><template #default="{ row }"
                    ><span :class="{ muted: !row.approvals.submitted, warning: row.approvals.pending }">{{
                      approvalSummary(row.approvals)
                    }}</span></template
                  ></el-table-column
                >
                <el-table-column label="最近使用" width="164"
                  ><template #default="{ row }">{{
                    formatDateTime(row.lastActiveAt)
                  }}</template></el-table-column
                >
                <el-table-column label="操作" width="96" fixed="right"
                  ><template #default="{ row }"
                    ><el-button
                      link
                      type="primary"
                      :disabled="loading"
                      :aria-label="`查看${row.name}的使用详情`"
                      @click="openPerson(row)"
                      >查看详情</el-button
                    ></template
                  ></el-table-column
                >
              </el-table>
            </LiquidGlassTable>
          </div>

          <LiquidGlassTable
            v-else-if="activeTab === 'operations'"
            :current-page="currentPage"
            :page-size="pageSize"
            @change="changePage"
            :total="report?.operations.length ?? 0"
            :page-sizes="[10, 20]"
            :loading="loading"
            :empty="!report?.operations.length"
            pagination-label="业务操作分页"
            source="backdrop"
            presentation="collection"
            class="usage-data-table"
          >
            <template #empty
              ><LiquidGlassEmptyState
                compact
                :icon="Connection"
                title="暂无 AI 工具业务操作"
                description="业务系统内对话单独统计；员工使用自己的 AI 工具调用业务能力后，这里会显示操作分布。"
            /></template>
            <el-table :data="pagedOperations" empty-text=" ">
              <el-table-column label="业务操作" min-width="220"
                ><template #default="{ row }"
                  ><div class="cell-stack">
                    <strong :title="row.toolName">{{ row.label }}</strong
                    ><small>{{ row.systemName }}</small>
                  </div></template
                ></el-table-column
              >
              <el-table-column label="调用次数" width="100" align="right"
                ><template #default="{ row }">{{ formatCount(row.calls) }}</template></el-table-column
              >
              <el-table-column label="使用人数" width="100" align="right"
                ><template #default="{ row }">{{ formatCount(row.people) }}</template></el-table-column
              >
              <el-table-column label="失败次数" width="100" align="right"
                ><template #default="{ row }"
                  ><span :class="{ danger: row.failed }">{{ formatCount(row.failed) }}</span></template
                ></el-table-column
              >
              <el-table-column label="提交审批" width="100" align="right"
                ><template #default="{ row }">{{ formatCount(row.writes) }}</template></el-table-column
              >
            </el-table>
          </LiquidGlassTable>

          <div v-else class="skills-view">
            <div v-if="can(PLATFORM_PERMISSION_SKILL_READ)" class="skill-actions">
              <LiquidGlassButton link type="primary" :icon="ArrowRight" @click="router.push('/skills')"
                >查看 Skill 管理</LiquidGlassButton
              >
            </div>
            <LiquidGlassTable
              :current-page="currentPage"
              :page-size="pageSize"
              @change="changePage"
              :total="report?.skills.length ?? 0"
              :page-sizes="[10, 20]"
              :loading="loading"
              :empty="!report?.skills.length"
              body-layout="cards"
              pagination-label="沉淀 Skill 分页"
              source="backdrop"
              presentation="collection"
              class="usage-data-table"
            >
              <template #empty
                ><LiquidGlassEmptyState
                  compact
                  :icon="Connection"
                  title="暂无沉淀的 Skill"
                  description="在成功的 AI 工具调用记录或运行详情中，将可复用的操作生成 Skill。"
                  ><LiquidGlassButton @click="router.push('/mcp-hub/call-logs')"
                    >查看调用记录</LiquidGlassButton
                  ></LiquidGlassEmptyState
                ></template
              >
              <div class="skill-list">
                <article
                  v-for="skill in pagedSkills"
                  :key="`${skill.skillId}-${skill.version}`"
                  class="skill-item"
                >
                  <div class="cell-stack">
                    <strong>{{ skill.displayName || skill.name }}</strong
                    ><small>{{ skill.systemName }} · v{{ skill.version }}</small
                    ><small>{{ skill.createdBy || '—' }} · {{ formatDateTime(skill.createdAt) }}</small>
                  </div>
                  <el-tag
                    size="small"
                    :type="skill.status === 'PUBLISHED' ? 'success' : 'info'"
                    effect="light"
                    >{{ skillStatusLabels[skill.status] ?? skill.status }}</el-tag
                  >
                </article>
              </div>
            </LiquidGlassTable>
          </div>
        </template>
      </WorkbenchPanel>
    </div>

    <AppDrawer
      v-model="drawerOpen"
      :title="selected ? `${selected.name}的使用详情` : '员工使用详情'"
      :description="`${periodText} · ${systemText}`"
      size="min(680px, 96vw)"
      destroy-on-close
    >
      <div v-if="selected" class="person-detail">
        <div class="person-identity">
          <el-icon aria-hidden="true"><User /></el-icon>
          <div class="cell-stack">
            <strong>{{ selected.name }}</strong
            ><small>{{ selected.accounts.join('、') || '暂无关联账号' }}</small>
          </div>
        </div>
        <section class="detail-figures" aria-label="使用概览">
          <div>
            <span>业务系统内对话</span
            ><strong>{{ report?.embedIncluded ? formatCount(selected.embedRuns) : '—' }}</strong
            ><small
              >{{ report?.embedIncluded ? `${formatCount(selected.embedSessions)} 个会话` : '未纳入统计'
              }}<template v-if="selected.embedFailed">
                · 失败 {{ formatCount(selected.embedFailed) }}</template
              ></small
            >
          </div>
          <div>
            <span>AI 工具调用</span><strong>{{ formatCount(selected.toolCalls) }}</strong
            ><small>成功率 {{ formatRate(selected.toolCalls, selected.toolFailed) }}</small>
          </div>
          <div>
            <span>写操作审批</span><strong>{{ formatCount(selected.approvals.submitted) }}</strong
            ><small>{{ approvalSummary(selected.approvals) }}</small>
          </div>
        </section>
        <section>
          <h3>用过的 AI 工具</h3>
          <div class="tag-row">
            <el-tag
              v-for="item in selected.channels"
              :key="item"
              :type="item === 'EMBED' ? 'info' : 'primary'"
              effect="light"
              >{{ channelLabel(item) }}</el-tag
            >
          </div>
          <p class="muted">
            业务系统：{{ selected.systems.join('、') || '—' }}<br />最近使用：{{
              formatDateTime(selected.lastActiveAt)
            }}
          </p>
        </section>
        <section>
          <h3>常办的事</h3>
          <div v-if="selected.topOperations.length" class="detail-operations">
            <el-table :data="selected.topOperations" size="small"
              ><el-table-column label="业务操作" min-width="200"
                ><template #default="{ row }"
                  ><div class="cell-stack">
                    <strong :title="row.toolName">{{ row.label }}</strong
                    ><small>{{ row.systemName }}</small>
                  </div></template
                ></el-table-column
              ><el-table-column label="调用" width="64" align="right"
                ><template #default="{ row }">{{ formatCount(row.calls) }}</template></el-table-column
              ><el-table-column label="失败" width="64" align="right"
                ><template #default="{ row }"
                  ><span :class="{ danger: row.failed }">{{ formatCount(row.failed) }}</span></template
                ></el-table-column
              ><el-table-column label="提交审批" width="80" align="right"
                ><template #default="{ row }">{{ formatCount(row.writes) }}</template></el-table-column
              ></el-table
            >
          </div>
          <p v-else class="muted">暂无自己的 AI 工具业务操作记录。</p>
        </section>
        <section>
          <h3>查看明细</h3>
          <div class="detail-links">
            <LiquidGlassButton
              v-for="connectionId in selected.connectionIds"
              :key="connectionId"
              @click="openCalls(connectionId)"
              >AI 工具调用记录<template v-if="selected.connectionIds.length > 1"
                >（连接 {{ connectionId }}）</template
              ></LiquidGlassButton
            ><LiquidGlassButton v-if="selected.approvals.submitted" @click="openApprovals(selected)"
              >写操作审批记录</LiquidGlassButton
            ><LiquidGlassButton
              v-if="selected.embedRuns && can(PLATFORM_PERMISSION_RUNOPS_READ)"
              @click="openConversations(selected)"
              >业务系统内对话</LiquidGlassButton
            >
          </div>
        </section>
      </div>
    </AppDrawer>
  </LiquidGlassPage>
</template>

<style scoped lang="scss">
.ai-usage-page {
  width: min(100%, 1840px);
  margin-inline: auto;
}
.ai-usage-page > * {
  flex-shrink: 0;
}
.usage-scope,
.scope-fields {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--layout-control-gap);
  min-width: 0;
}
.usage-scope {
  justify-content: space-between;
}
.scope-label,
.update-status {
  color: var(--text-on-wallpaper-secondary);
  font-size: 12px;
}
.scope-label {
  margin-inline-end: 4px;
}
.update-status {
  font-variant-numeric: tabular-nums;
}
.period-select {
  width: 136px;
}
.system-select {
  width: 220px;
}
.usage-workspace {
  display: grid;
  gap: var(--layout-page-gap);
  align-items: start;
}
.usage-workspace--with-channels {
  grid-template-columns: 268px minmax(0, 1fr);
}
.channel-list {
  display: grid;
  gap: 8px;
}
.channel-choice {
  display: grid;
  gap: 8px;
  width: 100%;
  padding: 12px;
  border: 1px solid transparent;
  border-radius: var(--radius-md);
  background: transparent;
  color: var(--text-primary);
  text-align: start;
  font: inherit;
  cursor: pointer;
}
.channel-choice:not(:disabled):hover {
  background: var(--surface-reading-hover);
}
.channel-choice:not(:disabled):active {
  background: var(--surface-reading-current);
}
.channel-choice[aria-pressed='true'] {
  border-color: var(--border-focus);
  background: var(--surface-reading-current);
}
.channel-choice:focus-visible {
  outline: 2px solid var(--border-focus);
  outline-offset: 2px;
}
.channel-choice:disabled {
  cursor: not-allowed;
  color: var(--text-muted);
}
.channel-choice--all {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 13px;
}
.channel-heading {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
}
.channel-heading > .el-icon {
  flex-shrink: 0;
  color: var(--brand-on-surface);
  font-size: 16px;
}
.channel-heading strong {
  font-weight: 600;
}
.channel-count,
.channel-check {
  margin-inline-start: auto;
  white-space: nowrap;
  font-size: 12px;
}
.channel-detail,
.channel-approvals {
  color: var(--text-secondary);
  font-size: 12px;
  line-height: 1.5;
}
.channel-meter {
  height: 4px;
  overflow: hidden;
  border-radius: var(--radius-pill);
  background: var(--border-divider);
}
.channel-meter > span {
  display: block;
  height: 100%;
  border-radius: inherit;
  background: var(--brand-on-surface);
}
.panel-note {
  margin: 0;
  font-size: 12px;
  line-height: 1.65;
}
.usage-detail-panel {
  min-width: 0;
}
.usage-detail-panel :deep(.workbench-panel__body) {
  display: grid;
  gap: var(--layout-section-gap);
}
.tab-count {
  margin-inline-start: 5px;
  color: var(--text-muted);
  font-variant-numeric: tabular-nums;
  font-size: 12px;
}
.view-description {
  margin: 0;
  color: var(--text-secondary);
  font-size: 13px;
  line-height: 1.6;
}
.initial-loading {
  min-height: 320px;
}
.usage-onboarding {
  display: grid;
  align-content: center;
  gap: 24px;
  min-height: 340px;
  padding-block: 16px;
}
.usage-entry-hints {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 24px;
  width: min(740px, 100%);
  margin-inline: auto;
  padding-block-start: 20px;
  border-top: 1px solid var(--border-divider);
}
.usage-entry-hints > div {
  display: flex;
  align-items: flex-start;
  gap: 12px;
}
.usage-entry-hints .el-icon {
  margin-top: 2px;
  color: var(--brand-on-surface);
  font-size: 20px;
}
.usage-entry-hints p {
  display: grid;
  gap: 5px;
  margin: 0;
  font-size: 13px;
  line-height: 1.65;
}
.usage-entry-hints strong {
  color: var(--text-primary);
  font-weight: 600;
}
.usage-entry-hints span {
  color: var(--text-secondary);
}
.people-view,
.skills-view {
  display: grid;
  min-width: 0;
  gap: var(--layout-section-gap);
}
.people-filters .sort-select {
  flex: 0 1 170px;
}
.mobile-channel-select {
  display: none;
}
.people-result-summary {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  min-height: 24px;
  color: var(--text-secondary);
  font-size: 12px;
}
.usage-data-table {
  min-height: 280px;
}
.usage-detail-panel :deep(.usage-data-table.data-table-shell) {
  border: 0;
  border-radius: 0;
}
.cell-stack {
  min-width: 0;
}
.cell-stack strong,
.cell-stack small {
  display: block;
  overflow-wrap: anywhere;
}
.cell-stack strong {
  color: var(--text-primary);
  font-weight: 600;
}
.cell-stack small {
  margin-top: 4px;
  color: var(--text-muted);
  font-size: 12px;
  line-height: 1.5;
}
.tag-row {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.muted {
  color: var(--text-muted);
}
.danger,
.cell-stack .danger {
  color: var(--status-danger);
}
.warning {
  color: var(--status-warning);
}
.skill-actions {
  display: flex;
  justify-content: flex-end;
}
.skill-list {
  display: grid;
  gap: 12px;
}
.skill-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding-block: 14px;
  border-bottom: 1px solid var(--border-divider);
}
.skill-item > .el-tag {
  flex-shrink: 0;
}
.person-detail {
  display: grid;
  gap: 24px;
}
.person-identity {
  display: flex;
  align-items: center;
  gap: 12px;
}
.person-identity > .el-icon {
  color: var(--brand-on-surface);
  font-size: 28px;
}
.person-detail h3 {
  margin: 0 0 12px;
  color: var(--text-primary);
  font-size: 14px;
}
.person-detail p {
  margin: 12px 0 0;
  font-size: 13px;
  line-height: 1.7;
}
.detail-figures {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
}
.detail-figures > div {
  display: grid;
  align-content: start;
  gap: 6px;
  padding-inline-end: 12px;
}
.detail-figures > div + div {
  padding-inline-start: 12px;
  border-inline-start: 1px solid var(--border-divider);
}
.detail-figures span,
.detail-figures small {
  color: var(--text-secondary);
  font-size: 12px;
  line-height: 1.6;
}
.detail-figures strong {
  color: var(--text-primary);
  font-size: 24px;
  font-variant-numeric: tabular-nums;
}
.detail-operations {
  min-width: 0;
  overflow-x: auto;
}
.detail-links {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
@media (max-width: 1200px) {
  .usage-workspace--with-channels {
    grid-template-columns: minmax(0, 1fr);
  }
  .channel-list {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .channel-choice--all {
    grid-column: 1 / -1;
  }
}
@media (max-width: 760px) {
  .channel-panel {
    display: none;
  }
  .usage-entry-hints {
    grid-template-columns: minmax(0, 1fr);
    gap: 16px;
  }
  .scope-fields {
    flex: 1 1 100%;
  }
  .scope-label {
    flex-basis: 100%;
  }
  .period-select {
    flex: 1 1 120px;
    width: auto;
  }
  .system-select {
    flex: 2 1 160px;
    width: auto;
  }
  .usage-tabs :deep(.el-tabs__nav) {
    gap: 14px;
  }
  .people-filters :deep(.filter-bar__fields > .sort-select),
  .people-filters :deep(.filter-bar__fields > .mobile-channel-select) {
    flex: 1 1 110px;
  }
  .mobile-channel-select {
    display: block;
  }
  .usage-onboarding {
    min-height: 0;
    gap: 16px;
    padding-block: 0;
  }
  .usage-onboarding :deep(.liquid-empty-state--compact) {
    padding: 16px 0;
  }
  .detail-figures {
    grid-template-columns: minmax(0, 1fr);
  }
  .detail-figures > div + div {
    padding-inline-start: 0;
    padding-block-start: 12px;
    border-inline-start: 0;
    border-top: 1px solid var(--border-divider);
  }
}
@media (forced-colors: active) {
  .channel-choice[aria-pressed='true'] {
    border-color: Highlight;
  }
  .channel-meter > span {
    background: Highlight;
  }
}
</style>
