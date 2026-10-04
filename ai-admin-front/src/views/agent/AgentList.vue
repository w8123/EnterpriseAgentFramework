<template>
  <WorkbenchPage class="agent-page" layout="list">
    <CollapsibleHeaderRegion :collapsed="isAgentHeaderCollapsed">
      <PageHeader
        variant="overview"
        domain="agent"
        eyebrow="Agent Catalog"
        title="Agent 管理"
        description="统一管理 Agent 身份、项目归属、Supervisor 配置状态和可调用 Workflow。"
        :collapsed="isAgentHeaderCollapsed"
      >
        <template #tags>
          <el-tag effect="light">Supervisor Agent</el-tag>
          <el-tag :type="scopeCanLoadData ? 'info' : 'warning'" effect="light">
            {{ agentScopeTagLabel }}
          </el-tag>
          <el-tag v-if="hasLoadedAgentList" type="success" effect="light">
            当前 {{ agentStatistics.totalAgents }} 个
          </el-tag>
          <el-tag type="info" effect="light">可调用 Workflow</el-tag>
        </template>
        <template #actions>
          <ViewToggle v-model="viewMode" />
          <el-button v-if="canOpenRunOps" :icon="Connection" @click="router.push('/runops')">运行中心</el-button>
          <el-button v-if="canCreateAgent" type="primary" :icon="Plus" @click="handleCreate">
            新建智能体
          </el-button>
        </template>
      </PageHeader>

      <template v-if="hasLoadedAgentList" #summary>
        <MetricStrip :items="metricItems" density="compact" aria-label="智能体指标概览" />
        <div v-if="statisticsError" class="agent-statistics-warning" role="status" aria-live="polite">
          当前范围的 Agent 列表已加载，统计暂时不可用；可以稍后重试。
        </div>
      </template>
    </CollapsibleHeaderRegion>

      <DataTableShell
        v-if="scopeCanLoadData"
        v-model:current-page="currentPage"
        v-model:page-size="pageSize"
        class="agent-shell workbench-list-surface"
        :loading="loading"
        :empty="!hasLoadedAgentList || filteredAgents.length === 0"
        empty-description="暂无符合条件的智能体"
        :total="filteredAgents.length"
      :page-sizes="[10, 20, 50]"
    >
      <template #toolbar>
        <FilterBar
          class="agent-filter-bar"
          density="compact"
          :loading="loading"
          @query="handleQuery"
          @reset="resetFilters"
        >
          <el-input
            v-model="filterKeyword"
            :prefix-icon="Search"
            placeholder="搜索名称、keySlug 或 ID"
            clearable
            class="filter-control is-keyword"
          />
          <el-select v-model="filterEnabled" placeholder="启用状态" clearable class="filter-control is-narrow">
            <el-option label="已启用" :value="true" />
            <el-option label="已停用" :value="false" />
          </el-select>

          <template #actions>
            <el-tooltip content="重置筛选" placement="top">
              <el-button
                class="filter-reset-button"
                :icon="RefreshLeft"
                circle
                native-type="button"
                aria-label="重置筛选"
                @click="resetFilters"
              />
            </el-tooltip>
            <el-button
              class="filter-search-button"
              type="primary"
              native-type="submit"
              :icon="Search"
              :loading="loading"
            >
              搜索
            </el-button>
          </template>
        </FilterBar>
      </template>

      <div v-if="viewMode === 'card'" class="agent-card-grid">
        <article
          v-for="agent in pagedAgents"
          :key="agent.id"
          class="agent-card glass-surface-card"
          @click="handleEdit(agent.id)"
        >
          <div class="agent-card-top">
            <div class="agent-avatar">
              <el-icon><Cpu /></el-icon>
            </div>
            <div class="agent-title-area">
              <h3>{{ agent.name }}</h3>
              <span>{{ agent.keySlug || agent.id }}</span>
            </div>
            <el-switch
              :model-value="agent.enabled !== false"
              size="small"
              :disabled="!canWriteAgent(agent)"
              @change="(val: boolean) => handleToggle(agent, val)"
              @click.stop
            />
          </div>

          <p class="agent-card-description">{{ agent.description || '尚未填写智能体用途说明。' }}</p>

          <div class="agent-card-meta">
            <el-tag size="small" effect="plain">
              {{ agent.projectCode || projectCodeById(agent.projectId) || '全局' }}
            </el-tag>
            <el-tag size="small" effect="plain">{{ visibilityLabel(agent.visibility) }}</el-tag>
            <el-tag size="small" :type="agent.enabled !== false ? 'success' : 'info'" effect="plain">
              {{ agent.enabled !== false ? '启用' : '停用' }}
            </el-tag>
            <el-tag size="small" :type="configStatusType(agent.configStatus)" effect="plain">
              {{ configStatusLabel(agent) }}
            </el-tag>
            <el-tag size="small" effect="plain">{{ agent.workflowToolCount || 0 }} 个 Workflow</el-tag>
          </div>

          <div class="agent-card-footer" @click.stop>
            <el-button
              v-for="action in visibleAgentActions(agent)"
              :key="action.id"
              link
              :type="action.buttonType"
              size="small"
              @click="handleAgentAction(agent, action.id)"
            >
              {{ action.label }}
            </el-button>
          </div>
        </article>
      </div>

      <div v-else ref="agentTableShellRef" class="agent-table-shell">
        <el-table
          ref="agentTableRef"
          :data="pagedAgents"
          class="agent-table"
          row-key="id"
          :max-height="agentTableMaxHeight"
        >
          <el-table-column prop="name" label="名称" min-width="220" fixed="left">
            <template #default="{ row }">
              <button
                type="button"
                class="agent-name-cell"
                :disabled="!canWriteAgent(row)"
                @click="handleEdit(row.id)"
              >
                <span class="agent-name-avatar">{{ agentInitial(row.name) }}</span>
                <span class="agent-name-copy">
                  <strong>{{ row.name }}</strong>
                  <small>{{ row.description || row.keySlug }}</small>
                </span>
              </button>
            </template>
          </el-table-column>
          <el-table-column prop="keySlug" label="keySlug" min-width="170" show-overflow-tooltip />
          <el-table-column label="项目" width="126" show-overflow-tooltip>
            <template #default="{ row }">
              {{ row.projectCode || projectCodeById(row.projectId) || '全局' }}
            </template>
          </el-table-column>
          <el-table-column label="可见性" width="92">
            <template #default="{ row }">{{ visibilityLabel(row.visibility) }}</template>
          </el-table-column>
          <el-table-column label="Supervisor 配置" min-width="158">
            <template #default="{ row }">
              <el-tag size="small" :type="configStatusType(row.configStatus)" effect="light">
                {{ configStatusLabel(row) }}
              </el-tag>
              <small class="config-tool-count">{{ row.workflowToolCount || 0 }} 个可调用 Workflow</small>
            </template>
          </el-table-column>
          <el-table-column prop="enabled" label="状态" width="86" align="center">
            <template #default="{ row }">
              <el-switch
                :model-value="row.enabled !== false"
                size="small"
                :disabled="!canWriteAgent(row)"
                @change="(val: boolean) => handleToggle(row, val)"
              />
            </template>
          </el-table-column>
          <el-table-column prop="updatedAt" label="更新时间" width="170">
            <template #default="{ row }">{{ formatDateTime(row.updatedAt) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="250" fixed="right">
            <template #default="{ row }">
              <div class="row-actions">
                <el-button
                  v-for="action in visibleAgentActions(row)"
                  :key="action.id"
                  link
                  :type="action.buttonType"
                  size="small"
                  @click.stop="handleAgentAction(row, action.id)"
                >
                  {{ action.label }}
                </el-button>
              </div>
            </template>
          </el-table-column>

          <template #empty><span /></template>
        </el-table>
      </div>

      <template #empty>
        <div v-if="listError" class="agent-list-error" role="alert" aria-live="assertive">
          <h3>智能体列表加载失败</h3>
          <p>当前范围的 Agent 数据未能加载，原范围保持不变。</p>
          <el-button type="primary" :loading="loading" @click="handleQuery">重试</el-button>
        </div>

        <div v-else-if="agentListStatus !== 'success'" class="agent-list-loading-state" role="status" aria-live="polite">
          <h3>正在加载当前范围</h3>
          <p>Agent 列表结果返回后，这里会显示真实数量和数据。</p>
        </div>

        <div v-else-if="agents.length === 0" class="agent-empty-guide">
          <div class="agent-empty-guide__visual">
            <img
              :src="agentSupervisorOnboardingIllustration"
              alt="Supervisor Agent 协调 Workflow、工具与知识检索的引导插画"
              width="1024"
              height="1024"
              decoding="async"
            />
          </div>

          <div class="agent-empty-guide__content">
            <h3>创建第一个 Agent</h3>
            <p>配置 Supervisor，并按需接入 Workflow 工具。</p>
            <el-button v-if="canCreateAgent" type="primary" :icon="Plus" @click="handleCreate">
              新建智能体
            </el-button>
          </div>
        </div>

        <el-empty v-else description="暂无符合条件的智能体">
          <el-button v-if="canCreateAgent" type="primary" :icon="Plus" @click="handleCreate">
            新建智能体
          </el-button>
        </el-empty>
      </template>

      <template v-if="hasLoadedAgentList" #pagination>
        <span class="agent-pagination-total">共 {{ filteredAgents.length }} 条</span>
        <el-pagination
          v-model:current-page="currentPage"
          v-model:page-size="pageSize"
          class="agent-pagination"
          background
          layout="prev, pager, next, sizes"
          :page-sizes="[10, 20, 50]"
          :total="filteredAgents.length"
        />
      </template>
    </DataTableShell>

    <section
      v-else
      class="agent-scope-panel glass-surface-panel"
      :aria-label="agentScopeLabel"
    >
      <ProjectScopeState
        :title="scopeStateTitle"
        :message="scopeFeedbackMessage"
        :status="scopeStatus"
        :show-select-project="scopeNeedsChoice && appStore.sidebarCollapsed"
        :can-retry="scopeCanRetry"
        :retrying="scopeRetrying"
        :retry-label="scopeRecoveryLabel"
        @select-project="openScopeSelector"
        @retry="retryScope"
      />
    </section>
  </WorkbenchPage>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox, type TableInstance } from 'element-plus'
import { Connection, Cpu, Plus, RefreshLeft, Search } from '@element-plus/icons-vue'
import type { Agent, AgentStatistics } from '@/types/workflow'
import {
  deleteAgent,
  getAgentStatistics,
  listAgents,
  updateAgent,
} from '@/api/workflow'
import ViewToggle from '@/components/ViewToggle.vue'
import { useAppStore } from '@/store/app'
import { usePageProjectScope } from '@/composables/usePageProjectScope'
import { agentListActions, type AgentListActionId } from '@/utils/agentActions'
import CollapsibleHeaderRegion from '@/components/common/CollapsibleHeaderRegion.vue'
import DataTableShell from '@/components/common/DataTableShell.vue'
import FilterBar from '@/components/common/FilterBar.vue'
import MetricStrip from '@/components/common/MetricStrip.vue'
import PageHeader from '@/components/common/PageHeader.vue'
import WorkbenchPage from '@/components/common/WorkbenchPage.vue'
import ProjectScopeState from '@/components/common/ProjectScopeState.vue'
import type { MetricStripItem } from '@/components/common/glassWorkbench'
import { useCollapsiblePageHeader } from '@/composables/useCollapsiblePageHeader'
import agentSupervisorOnboardingIllustration from '@/assets/illustrations/agent-supervisor-onboarding.webp'
import {
  hasPlatformGlobalPermissionGrant,
  hasPlatformResourcePermission,
  PLATFORM_PERMISSION_AGENT_DEBUG,
  PLATFORM_PERMISSION_AGENT_EVALUATE,
  PLATFORM_PERMISSION_AGENT_WRITE,
  PLATFORM_PERMISSION_RUNOPS_READ,
} from '@/auth/platformAccess'
import { platformSessionUser } from '@/auth/platformSession'

const router = useRouter()
const appStore = useAppStore()
const pageProjectScope = usePageProjectScope({ required: true })
const scopeCanLoadData = pageProjectScope.canLoadData
const scopeStatus = pageProjectScope.status
const scopeFeedbackMessage = pageProjectScope.feedbackMessage
const scopeRecoveryLabel = pageProjectScope.recoveryLabel
const scopeRetrying = ref(false)
const agentScopeLabel = computed(() => (
  pageProjectScope.canLoadData.value && pageProjectScope.resolution.value?.kind === 'all'
    ? '全部 Agent'
    : pageProjectScope.currentScopeLabel.value
))
const agentScopeTagLabel = computed(() => (
  scopeCanLoadData.value ? `范围：${agentScopeLabel.value}` : '范围未确认'
))
const scopeStateTitle = computed(() => (
  scopeStatus.value === 'pending' ? '正在确认项目范围' : '需要确认项目范围'
))
const scopeNeedsChoice = computed(() => (
  pageProjectScope.status.value === 'blocked'
    && (pageProjectScope.resolution.value?.kind === 'blocked')
    && ['project-required', 'all-forbidden', 'project-not-found', 'project-forbidden', 'ambiguous-project', 'invalid-reference'].includes(pageProjectScope.resolution.value.reason)
))
const scopeCanRetry = computed(() => (
  (pageProjectScope.recoveryAction.value === 'retry-catalog'
    || pageProjectScope.recoveryAction.value === 'retry-normalization')
    && !pageProjectScope.isCatalogLoading.value
))

function canUseAgentPermission(permission: string, projectCode?: string | null) {
  return hasPlatformResourcePermission(
    platformSessionUser.value?.permissionGrants,
    permission,
    'PROJECT',
    null,
    projectCode,
  )
}

function agentProjectCode(agent: Agent) {
  return agent.projectCode || projectCodeById(agent.projectId)
}

function canWriteAgent(agent: Agent) {
  return canUseAgentPermission(PLATFORM_PERMISSION_AGENT_WRITE, agentProjectCode(agent))
}

function visibleAgentActions(agent: Agent) {
  const projectCode = agentProjectCode(agent)
  return agentListActions(agent).filter((action) => {
    if (action.id === 'edit' || action.id === 'delete') {
      return canUseAgentPermission(PLATFORM_PERMISSION_AGENT_WRITE, projectCode)
    }
    if (action.id === 'debug') {
      return canUseAgentPermission(PLATFORM_PERMISSION_AGENT_DEBUG, projectCode)
    }
    if (action.id === 'eval') {
      return canUseAgentPermission(PLATFORM_PERMISSION_AGENT_EVALUATE, projectCode)
    }
    return canUseAgentPermission(PLATFORM_PERMISSION_RUNOPS_READ, projectCode)
  })
}

type AgentDataStatus = 'idle' | 'loading' | 'success' | 'error'

const agents = ref<Agent[]>([])
const statistics = ref<AgentStatistics | null>(null)
const agentListStatus = ref<AgentDataStatus>('idle')
const agentStatisticsStatus = ref<AgentDataStatus>('idle')
const loading = computed(() => agentListStatus.value === 'loading')
const listError = computed(() => agentListStatus.value === 'error')
const statisticsError = computed(() => agentStatisticsStatus.value === 'error')
const filterKeyword = ref('')
const filterEnabled = ref<boolean | ''>('')
const viewMode = ref<'table' | 'card'>('table')
const currentPage = ref(1)
const pageSize = ref(10)
const agentTableRef = ref<TableInstance>()
const agentTableShellRef = ref<HTMLElement | null>(null)
/** 限制表格主体高度，搜索栏/表头固定，仅表体滚动。 */
const agentTableMaxHeight = ref(480)

const canCreateAgent = computed(() => (
  scopeCanLoadData.value
  && (
    pageProjectScope.resolution.value?.kind === 'all'
      ? hasPlatformGlobalPermissionGrant(
        platformSessionUser.value?.permissionGrants,
        PLATFORM_PERMISSION_AGENT_WRITE,
      )
        || pageProjectScope.readableProjects.value.some((project) => (
          canUseAgentPermission(PLATFORM_PERMISSION_AGENT_WRITE, project.projectCode)
        ))
      : pageProjectScope.resolution.value?.kind === 'project'
        && canUseAgentPermission(
          PLATFORM_PERMISSION_AGENT_WRITE,
          pageProjectScope.resolution.value.project.projectCode,
        )
  )
))
const canOpenRunOps = computed(() => (
  hasPlatformGlobalPermissionGrant(
    platformSessionUser.value?.permissionGrants,
    PLATFORM_PERMISSION_RUNOPS_READ,
  )
  || pageProjectScope.readableProjects.value.some((project) => (
    canUseAgentPermission(PLATFORM_PERMISSION_RUNOPS_READ, project.projectCode)
  ))
))

let layoutRaf = 0
let layoutFreezeUntil = 0
let layoutFreezeTimer = 0
let tableShellObserver: ResizeObserver | null = null

function scheduleAgentListLayout() {
  if (typeof window === 'undefined') return
  const remaining = layoutFreezeUntil - performance.now()
  if (remaining > 0) {
    window.clearTimeout(layoutFreezeTimer)
    layoutFreezeTimer = window.setTimeout(() => {
      scheduleAgentListLayout()
    }, remaining + 16)
    return
  }
  if (layoutRaf) return
  layoutRaf = requestAnimationFrame(() => {
    layoutRaf = 0
    updateAgentTableMaxHeight()
    agentTableRef.value?.doLayout()
  })
}

function freezeAgentListLayout(ms = 300) {
  layoutFreezeUntil = performance.now() + ms
}

const {
  collapsed: isAgentHeaderCollapsed,
  refreshScrollTargets: refreshAgentHeaderScrollTargets,
} = useCollapsiblePageHeader({
  rootSelector: '.agent-page',
  scrollSelectors: [
    '.agent-table .el-scrollbar__wrap',
    '.agent-table .el-table__body-wrapper',
  ],
  // Compat callback; composable only invokes these after collapse transitions settle.
  onScroll: scheduleAgentListLayout,
  onLayoutChange: scheduleAgentListLayout,
})

function updateAgentTableMaxHeight() {
  if (typeof window === 'undefined') return
  if (viewMode.value !== 'table') return

  let nextHeight = 0
  const shellEl = agentTableShellRef.value
  if (shellEl && shellEl.clientHeight >= 260) {
    nextHeight = Math.floor(shellEl.clientHeight)
  } else {
    const root = document.querySelector('.agent-page')
    const tableEl = root?.querySelector('.agent-table') as HTMLElement | undefined
    const paginationEl = root?.querySelector('.data-table-shell__pagination') as HTMLElement | undefined
    if (!tableEl) {
      nextHeight = Math.max(280, window.innerHeight - 420)
    } else {
      const tableTop = tableEl.getBoundingClientRect().top
      const viewportPad = 16
      const paginationGap = 12
      const paginationVisible = Boolean(paginationEl && paginationEl.offsetParent !== null)
      const paginationHeight = paginationVisible
        ? paginationEl!.getBoundingClientRect().height
        : 0
      let bottomLimit = window.innerHeight - viewportPad
      if (paginationVisible) {
        const paginationTop = paginationEl!.getBoundingClientRect().top
        if (paginationTop > tableTop && paginationTop < window.innerHeight) {
          bottomLimit = Math.min(bottomLimit, paginationTop - paginationGap)
        } else {
          bottomLimit -= paginationHeight + paginationGap
        }
      }
      nextHeight = Math.floor(bottomLimit - tableTop)
    }
  }

  nextHeight = Math.max(260, nextHeight)
  if (Math.abs(nextHeight - agentTableMaxHeight.value) < 2) return
  agentTableMaxHeight.value = nextHeight
}

function bindAgentTableShellObserver() {
  tableShellObserver?.disconnect()
  tableShellObserver = null
  if (typeof ResizeObserver === 'undefined') return
  const shellEl = agentTableShellRef.value
  if (!shellEl) return
  tableShellObserver = new ResizeObserver(() => {
    scheduleAgentListLayout()
  })
  tableShellObserver.observe(shellEl)
}

const filteredAgents = computed(() => {
  const keyword = filterKeyword.value.trim().toLowerCase()
  return agents.value.filter((agent) => {
    if (filterEnabled.value !== '' && (agent.enabled !== false) !== filterEnabled.value) return false
    if (!keyword) return true
    const haystack = [agent.name, agent.keySlug, agent.id, agent.description]
      .filter(Boolean)
      .join(' ')
      .toLowerCase()
    return haystack.includes(keyword)
  })
})

const pagedAgents = computed(() => {
  const start = (currentPage.value - 1) * pageSize.value
  return filteredAgents.value.slice(start, start + pageSize.value)
})

const fallbackStatistics = computed<AgentStatistics>(() => {
  const enabledAgents = agents.value.filter((agent) => agent.enabled !== false).length
  return {
    totalAgents: agents.value.length,
    enabledAgents,
    workflowToolAgents: 0,
    activeWorkflowTools: 0,
  }
})

const agentStatistics = computed(() => statistics.value ?? fallbackStatistics.value)
const scopeLabel = computed(() => agentScopeLabel.value)
const hasLoadedAgentList = computed(() => (
  scopeCanLoadData.value && agentListStatus.value === 'success'
))

const metricItems = computed<MetricStripItem[]>(() => {
  const stats = agentStatistics.value
  const disabled = Math.max(0, stats.totalAgents - stats.enabledAgents)
  const withoutTools = Math.max(0, stats.totalAgents - stats.workflowToolAgents)
  const hasWorkflowToolStatistics = statistics.value !== null
  return [
    {
      key: 'agent-total',
      label: '智能体总数',
      value: stats.totalAgents,
      hint: scopeLabel.value,
      iconKey: 'agent-total',
      tone: 'brand',
    },
    {
      key: 'agent-enabled',
      label: '已启用',
      value: stats.enabledAgents,
      hint: disabled ? `${disabled} 个已停用` : '全部处于启用状态',
      iconKey: 'agent-enabled',
      tone: 'success',
    },
    {
      key: 'agent-workflow-tools',
      label: '已配置 Workflow 工具',
      value: hasWorkflowToolStatistics ? `${stats.workflowToolAgents} / ${stats.totalAgents}` : '—',
      hint: hasWorkflowToolStatistics
        ? (stats.activeWorkflowTools
            ? `${stats.activeWorkflowTools} 个已发布工具`
            : `${withoutTools} 个 Agent 未配置工具`)
        : '统计服务待加载',
      iconKey: 'agent-workflow-tools',
      tone: hasWorkflowToolStatistics ? (withoutTools ? 'warning' : 'success') : 'neutral',
    },
  ]
})

function visibilityLabel(visibility?: string | null) {
  if (visibility === 'PRIVATE') return '私有'
  if (visibility === 'PUBLIC') return '公开'
  return '项目'
}

function configStatusType(status?: string | null) {
  if (status === 'ACTIVE') return 'success'
  if (status === 'DRAFT') return 'warning'
  return 'info'
}

function configStatusLabel(agent: Agent) {
  if (!agent.configStatus || agent.configStatus === 'NONE') return '未配置'
  const version = agent.displayConfigVersionNo ? ` v${agent.displayConfigVersionNo}` : ''
  const statusLabels: Record<string, string> = {
    ACTIVE: '已激活',
    DRAFT: '草稿',
    ARCHIVED: '已归档',
  }
  return `${statusLabels[agent.configStatus] || agent.configStatus}${version}`
}

function formatDateTime(value?: string | null) {
  if (!value) return '-'
  const parsed = Date.parse(value)
  if (!Number.isFinite(parsed)) return value
  return new Date(parsed).toLocaleString()
}

function agentInitial(name?: string | null) {
  return name?.trim().slice(0, 1).toUpperCase() || 'A'
}

function projectCodeById(projectId?: number | null) {
  if (projectId == null) return null
  return pageProjectScope.readableProjects.value.find((project) => project.id === projectId)?.projectCode || null
}

let dataRequestGeneration = 0

function isCurrentDataRequest(generation: number, requestKey: string) {
  return generation === dataRequestGeneration
    && pageProjectScope.canLoadData.value
    && pageProjectScope.requestKey.value === requestKey
}

function invalidateAgentData() {
  dataRequestGeneration += 1
  agents.value = []
  statistics.value = null
  agentListStatus.value = 'idle'
  agentStatisticsStatus.value = 'idle'
  currentPage.value = 1
}

async function fetchData() {
  if (!pageProjectScope.canLoadData.value) return
  const generation = ++dataRequestGeneration
  const requestKey = pageProjectScope.requestKey.value
  const params = pageProjectScope.requestParams.value
  agents.value = []
  statistics.value = null
  agentListStatus.value = 'loading'
  agentStatisticsStatus.value = 'loading'
  currentPage.value = 1

  const listRequest = Promise.resolve()
    .then(() => listAgents(params))
    .then((result) => {
      if (!isCurrentDataRequest(generation, requestKey)) return
      if (!Array.isArray(result.data)) {
        agents.value = []
        agentListStatus.value = 'error'
        return
      }
      agents.value = result.data
      agentListStatus.value = 'success'
    })
    .catch(() => {
      if (!isCurrentDataRequest(generation, requestKey)) return
      agents.value = []
      agentListStatus.value = 'error'
    })

  const statisticsRequest = Promise.resolve()
    .then(() => getAgentStatistics(params))
    .then((result) => {
      if (!isCurrentDataRequest(generation, requestKey)) return
      statistics.value = result.data
      agentStatisticsStatus.value = 'success'
    })
    .catch(() => {
      if (!isCurrentDataRequest(generation, requestKey)) return
      statistics.value = null
      agentStatisticsStatus.value = 'error'
    })

  await Promise.all([listRequest, statisticsRequest])
  if (!isCurrentDataRequest(generation, requestKey)) return
  await nextTick()
  refreshAgentHeaderScrollTargets()
  scheduleAgentListLayout()
}

watch(
  () => [filteredAgents.value.length, currentPage.value, pageSize.value] as const,
  async () => {
    await nextTick()
    scheduleAgentListLayout()
  },
)

function handleQuery() {
  if (!pageProjectScope.canLoadData.value) return
  currentPage.value = 1
  void fetchData()
}

function resetFilters() {
  filterKeyword.value = ''
  filterEnabled.value = ''
  currentPage.value = 1
  if (pageProjectScope.canLoadData.value) void fetchData()
}

function handleCreate() {
  if (!canCreateAgent.value) return
  const scopeQuery = pageProjectScope.scopeQuery.value
  if (!scopeQuery) return
  router.push({
    path: '/agent/new/edit',
    query: scopeQuery,
  })
}

function handleEdit(id: string) {
  const agent = agents.value.find((item) => item.id === id)
  if (agent && !canWriteAgent(agent)) return
  router.push(`/agent/${id}/edit`)
}

function handleAgentAction(agent: Agent, actionId: AgentListActionId) {
  if (!visibleAgentActions(agent).some((action) => action.id === actionId)) return
  if (actionId === 'edit') return handleEdit(agent.id)
  if (actionId === 'debug') return router.push(`/agent/${agent.id}/debug`)
  if (actionId === 'eval') return router.push(`/agent/${agent.id}/evals`)
  if (actionId === 'runops') return router.push({ path: '/runops', query: { agentId: agent.id } })
  return handleDelete(agent.id)
}

async function handleToggle(agent: Agent, enabled: boolean) {
  if (!pageProjectScope.canLoadData.value || !canWriteAgent(agent)) return
  const requestKey = pageProjectScope.requestKey.value
  try {
    await updateAgent(agent.id, { enabled })
    if (!isCurrentDataRequest(dataRequestGeneration, requestKey)) return
    agent.enabled = enabled
    ElMessage.success(enabled ? '已启用' : '已停用')
    await fetchData()
  } catch {
    if (isCurrentDataRequest(dataRequestGeneration, requestKey)) ElMessage.error('操作失败')
  }
}

async function handleDelete(id: string) {
  const agent = agents.value.find((item) => item.id === id)
  if (!agent || !pageProjectScope.canLoadData.value || !canWriteAgent(agent)) return
  const requestKey = pageProjectScope.requestKey.value
  try {
    await ElMessageBox.confirm('确认删除该 Agent？', '删除 Agent', { type: 'warning' })
    if (!isCurrentDataRequest(dataRequestGeneration, requestKey)) return
    await deleteAgent(id)
    if (!isCurrentDataRequest(dataRequestGeneration, requestKey)) return
    ElMessage.success('删除成功')
    void fetchData()
  } catch (error) {
    if (isCurrentDataRequest(dataRequestGeneration, requestKey) && error !== 'cancel' && error !== 'close') {
      ElMessage.error('删除失败')
    }
  }
}

async function retryScope() {
  if (!scopeCanRetry.value || scopeRetrying.value) return
  scopeRetrying.value = true
  try {
    await pageProjectScope.retryScope()
  } finally {
    scopeRetrying.value = false
  }
}

function openScopeSelector() {
  if (appStore.sidebarCollapsed) appStore.toggleSidebar()
}

onMounted(() => {
  window.addEventListener('resize', scheduleAgentListLayout)
})

onUnmounted(() => {
  dataRequestGeneration += 1
  window.removeEventListener('resize', scheduleAgentListLayout)
  tableShellObserver?.disconnect()
  tableShellObserver = null
  if (layoutRaf) cancelAnimationFrame(layoutRaf)
  window.clearTimeout(layoutFreezeTimer)
})

watch(
  agentTableShellRef,
  () => {
    bindAgentTableShellObserver()
    scheduleAgentListLayout()
  },
  { flush: 'post', immediate: true },
)

watch(
  () => [pageProjectScope.requestKey.value, pageProjectScope.canLoadData.value, pageProjectScope.isActive.value] as const,
  () => {
    invalidateAgentData()
    if (pageProjectScope.canLoadData.value) void fetchData()
  },
  { immediate: true },
)

watch([filterKeyword, filterEnabled, pageSize], () => {
  currentPage.value = 1
})

watch(viewMode, async () => {
  await nextTick()
  bindAgentTableShellObserver()
  refreshAgentHeaderScrollTargets()
  scheduleAgentListLayout()
})

watch(isAgentHeaderCollapsed, () => {
  // Freeze table relayout while header/summary CSS transitions run; mid-animation
  // doLayout was canceling the transition and causing visible flicker.
  freezeAgentListLayout()
})
</script>

<style scoped lang="scss">
.agent-page {
  height: 100%;
  max-height: 100%;
  min-height: 0;
  overflow: hidden;
}

.agent-page :deep(.collapsible-header-region) {
  flex: 0 0 auto;
}

.agent-shell {
  flex: 1 1 auto;
  min-height: 0;
  gap: 0;
  overflow: hidden;
  padding: 0;
  border-radius: 16px;
}

.agent-filter-bar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
  width: 100%;
  padding: 9px 14px;
  border-color: rgb(var(--brand-primary-rgb) / 0.11);
  border-radius: 12px;
  background: var(--brand-soft-bg, var(--surface-glass-control));
  box-shadow: none;
}

.filter-control {
  width: 100%;
  min-width: 0;

  &.is-keyword {
    min-width: 0;
  }
}

.agent-filter-bar :deep(.filter-bar__fields) {
  display: grid;
  flex: 1 1 480px;
  grid-template-columns: minmax(300px, 2.2fr) minmax(138px, 0.72fr);
  align-items: center;
  gap: 12px;
  min-width: min(100%, 480px);
}

.agent-statistics-warning {
  margin-top: 10px;
  padding: 8px 12px;
  border: 1px solid color-mix(in srgb, var(--el-color-warning) 24%, var(--border-divider));
  border-radius: 9px;
  color: var(--el-color-warning-dark-2);
  background: color-mix(in srgb, var(--el-color-warning) 7%, transparent);
  font-size: 12px;
}

.agent-filter-bar :deep(.filter-bar__actions) {
  flex: 0 0 auto;
  display: grid;
  grid-template-columns: 40px 88px;
  align-items: center;
  gap: 10px;
}

.agent-filter-bar :deep(.el-input__wrapper),
.agent-filter-bar :deep(.el-select__wrapper) {
  min-height: 38px;
  padding: 0 14px;
  border-radius: 9px;
  background: var(--surface-solid-control);
  box-shadow: 0 0 0 1px rgb(var(--brand-primary-rgb) / 0.18) inset;
}

.agent-filter-bar :deep(.el-input__inner),
.agent-filter-bar :deep(.el-select__placeholder),
.agent-filter-bar :deep(.el-select__selected-item) {
  color: var(--text-muted);
  font-size: 13px;
}

.agent-filter-bar :deep(.el-input__prefix),
.agent-filter-bar :deep(.el-input__suffix),
.agent-filter-bar :deep(.el-select__suffix),
.agent-filter-bar :deep(.el-select__caret) {
  color: rgb(var(--brand-primary-rgb) / 0.52);
}

.filter-reset-button,
.filter-search-button {
  height: 40px;
  min-height: 40px;
  border-radius: 10px;
  font-weight: 700;
}

.filter-reset-button {
  width: 40px;
  color: var(--text-secondary);
  border-color: rgb(var(--brand-primary-rgb) / 0.18);
  background: var(--surface-solid-control);
}

.filter-reset-button:hover {
  color: var(--brand-active);
  border-color: rgb(var(--brand-primary-rgb) / 0.28);
  background: var(--surface-glass-selected);
}

.filter-search-button {
  width: 88px;
  border-color: transparent;
  background: var(--brand-primary-gradient);
  box-shadow: 0 10px 18px -10px rgb(var(--brand-primary-rgb) / 0.24);
}

.agent-shell :deep(.data-table-shell__toolbar) {
  display: block;
  flex: 0 0 auto;
  margin: 20px 24px 0;
}

.agent-shell :deep(.data-table-shell__body) {
  display: flex;
  min-height: 0;
  flex: 1 1 auto;
  flex-direction: column;
  margin: 16px 24px 0;
}

.agent-shell.is-empty :deep(.data-table-shell__body) {
  min-height: 46px;
  flex: 0 0 46px;
}

.agent-shell :deep(.data-table-shell__pagination) {
  min-height: 64px;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 12px 24px 14px;
  border-top-color: rgb(var(--brand-primary-rgb) / 0.12);
  color: var(--text-muted);
  background: color-mix(in srgb, var(--surface-glass-control) 78%, transparent);
}

.agent-shell :deep(.data-table-shell__empty) {
  padding: 20px 24px 24px;
}

.agent-scope-panel {
  display: grid;
  min-width: 0;
  min-height: 260px;
  flex: 1 1 auto;
  place-items: center;
  padding: clamp(24px, 4vw, 48px);
  border-radius: var(--radius-lg);
}

.agent-list-error,
.agent-list-loading-state {
  display: grid;
  width: min(620px, 100%);
  min-height: 210px;
  place-items: center;
  align-content: center;
  gap: 8px;
  margin: 0 auto;
  padding: 26px;
  border: 1px dashed var(--border-readable);
  border-radius: var(--radius-lg);
  text-align: center;
  background: color-mix(in srgb, var(--surface-solid-panel) 84%, transparent);

  h3,
  p {
    margin: 0;
  }

  h3 {
    color: var(--text-primary);
    font-size: 17px;
  }

  p {
    max-width: 520px;
    color: var(--text-muted);
    font-size: 13px;
    line-height: 1.6;
  }
}

.agent-list-error {
  border-color: color-mix(in srgb, var(--el-color-danger) 28%, var(--border-readable));

  h3,
  p {
    color: var(--el-color-danger);
  }
}

.agent-list-loading-state {
  min-height: 160px;

  h3 {
    color: var(--text-primary);
  }
}

.agent-shell.is-empty :deep(.data-table-shell__empty) {
  min-height: 0;
  flex: 1 1 auto;
  overflow: hidden;
}

.agent-empty-guide {
  display: grid;
  width: min(980px, 100%);
  min-height: clamp(280px, 32vh, 340px);
  grid-template-columns: minmax(200px, clamp(220px, 26vh, 280px)) minmax(0, 1fr);
  align-items: center;
  gap: clamp(28px, 4vw, 52px);
  margin: 0 auto;
  padding: clamp(22px, 3vh, 36px);
  overflow: hidden;
  border: 1px dashed var(--border-readable);
  border-radius: var(--radius-lg);
  background:
    linear-gradient(
      135deg,
      color-mix(in srgb, var(--brand-primary) 6%, var(--surface-solid-panel)),
      color-mix(in srgb, var(--surface-solid-control) 82%, transparent)
    );
}

.agent-empty-guide__visual {
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-lg);
  background: var(--surface-solid-panel);
  box-shadow: var(--shadow-card), var(--inner-highlight);
}

.agent-empty-guide__visual img {
  display: block;
  width: 100%;
  height: auto;
  aspect-ratio: 1;
  border-radius: inherit;
  object-fit: cover;
}

.agent-empty-guide__content {
  display: flex;
  min-width: 0;
  flex-direction: column;
  align-items: flex-start;
}

.agent-empty-guide__content h3,
.agent-empty-guide__content p {
  margin: 0;
}

.agent-empty-guide__content h3 {
  color: var(--text-primary);
  font-size: 1.25rem;
  line-height: 1.35;
}

.agent-empty-guide__content > p {
  margin-top: 10px;
  color: var(--text-muted);
  font-size: 0.85rem;
  line-height: 1.55;
}

.agent-empty-guide__content > .el-button {
  min-width: 118px;
  height: 40px;
  margin-top: 20px;
  border-radius: 10px;
  font-weight: 700;
}

.agent-table-shell {
  display: flex;
  min-height: 0;
  flex: 1;
  flex-direction: column;
  overflow: hidden;
  border: 1px solid rgb(var(--brand-primary-rgb) / 0.12);
  border-radius: 13px;
  background: var(--surface-glass-control);
}

.agent-table {
  --el-table-header-bg-color: color-mix(in srgb, var(--brand-selected-bg) 42%, var(--surface-fallback-panel));
  --el-table-row-hover-bg-color: color-mix(in srgb, var(--brand-primary) 8%, var(--surface-fallback-panel));
  --el-table-border-color: rgb(var(--brand-primary-rgb) / 0.1);
  width: 100%;
  background: transparent;
}

.agent-table :deep(.el-table__inner-wrapper::before) {
  height: 0;
}

.agent-table :deep(.cell) {
  padding: 0 10px;
}

.agent-table :deep(th.el-table__cell) {
  height: 44px;
  color: var(--brand-active);
  background: color-mix(in srgb, var(--brand-selected-bg) 42%, var(--surface-fallback-panel)) !important;
  font-size: 12px;
  font-weight: 700;
}

.agent-table :deep(td.el-table__cell) {
  height: 62px;
  color: var(--text-secondary);
  background: color-mix(in srgb, var(--surface-solid-panel) 82%, transparent) !important;
  border-bottom-color: rgb(var(--brand-primary-rgb) / 0.1);
}

.agent-table :deep(.el-table__row:hover > td.el-table__cell),
.agent-table :deep(.el-table__body tr.hover-row > td.el-table__cell) {
  background: rgb(var(--brand-primary-rgb) / 0.08) !important;
}

.agent-table :deep(.el-table-fixed-column--right),
.agent-table :deep(.el-table-fixed-column--left) {
  background-color: var(--surface-fallback-panel) !important;
}

.agent-table :deep(th.el-table-fixed-column--right),
.agent-table :deep(th.el-table-fixed-column--left) {
  background-color: color-mix(in srgb, var(--brand-selected-bg) 42%, var(--surface-fallback-panel)) !important;
}

.agent-table :deep(td.el-table-fixed-column--right),
.agent-table :deep(td.el-table-fixed-column--left) {
  background-color: var(--surface-fallback-panel) !important;
}

.agent-table :deep(.el-table__row:hover > td.el-table-fixed-column--right),
.agent-table :deep(.el-table__row:hover > td.el-table-fixed-column--left),
.agent-table :deep(.el-table__body tr.hover-row > td.el-table-fixed-column--right),
.agent-table :deep(.el-table__body tr.hover-row > td.el-table-fixed-column--left) {
  background-color: color-mix(in srgb, var(--brand-primary) 8%, var(--surface-fallback-panel)) !important;
}

.agent-pagination-total {
  flex: 0 0 auto;
  font-size: 13px;
}

.agent-shell :deep(.agent-pagination.el-pagination) {
  --el-pagination-button-width: 34px;
  --el-pagination-button-height: 34px;
  --el-pagination-hover-color: var(--brand-active);
  gap: 6px;
}

.agent-shell :deep(.agent-pagination.is-background .btn-prev),
.agent-shell :deep(.agent-pagination.is-background .btn-next),
.agent-shell :deep(.agent-pagination.is-background .el-pager li:not(.is-active)) {
  min-width: 34px;
  height: 34px;
  margin: 0;
  border: 0;
  border-radius: 8px;
  background: var(--surface-solid-control);
  box-shadow: none;
}

.agent-shell :deep(.agent-pagination.is-background .el-pager li.is-active) {
  color: var(--brand-active);
  background: var(--surface-glass-selected);
  font-weight: 700;
}

.agent-shell :deep(.agent-pagination .el-pagination__sizes .el-select) {
  width: 94px;
}

.agent-shell :deep(.agent-pagination .el-pagination__sizes .el-select__wrapper) {
  min-height: 34px;
  height: 34px;
  border-radius: 8px;
}

.agent-name-cell {
  display: flex;
  width: 100%;
  align-items: center;
  gap: 10px;
  padding: 0;
  border: 0;
  color: inherit;
  background: transparent;
  text-align: left;
  cursor: pointer;
}

.agent-name-avatar {
  display: inline-grid;
  width: 32px;
  height: 32px;
  place-items: center;
  flex: 0 0 32px;
  border: 1px solid rgb(var(--brand-primary-rgb) / 0.28);
  border-radius: 9px;
  color: var(--brand-active);
  background: linear-gradient(
    90deg,
    var(--surface-solid-control) 0%,
    rgb(var(--brand-selected-rgb) / 0.82) 100%
  );
  box-shadow: 0 8px 16px -12px rgb(var(--brand-primary-rgb) / 0.22);
  font-weight: 700;
  transition:
    border-color var(--motion-duration-fast) ease,
    box-shadow var(--motion-duration-fast) ease,
    transform var(--motion-duration-fast) ease;
}

.agent-name-cell:hover .agent-name-avatar {
  color: var(--brand-primary);
  border-color: rgb(var(--brand-primary-rgb) / 0.42);
  box-shadow:
    var(--inner-highlight),
    0 10px 18px -14px rgb(var(--brand-primary-rgb) / 0.46);
  transform: translateY(-1px);
}

.agent-name-copy {
  display: grid;
  min-width: 0;
  gap: 2px;

  strong,
  small {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  strong {
    color: var(--text-primary);
    font-weight: 650;
  }

  small {
    color: var(--text-muted);
    font-size: 12px;
  }
}

.row-actions,
.agent-card-footer,
.agent-card-top,
.agent-card-meta {
  display: flex;
  align-items: center;
}

.row-actions {
  gap: 2px;
}

.config-tool-count {
  display: block;
  margin-top: 4px;
  color: var(--text-muted);
  font-size: 11px;
}

.agent-card-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
  gap: var(--section-gap);
}

.agent-card {
  display: flex;
  min-height: 188px;
  flex-direction: column;
  padding: var(--panel-padding);
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-lg);
  cursor: pointer;
  transition:
    border-color var(--motion-duration-fast) ease,
    box-shadow var(--motion-duration-fast) ease,
    transform var(--motion-duration-fast) ease;

  &:hover {
    border-color: var(--brand-primary);
    box-shadow: var(--shadow-floating);
    transform: translateY(-1px);
  }
}

.agent-card-top {
  gap: 12px;
}

.agent-avatar {
  display: grid;
  width: 44px;
  height: 44px;
  place-items: center;
  flex-shrink: 0;
  border: 1px solid var(--border-subtle);
  border-radius: 12px;
  color: var(--brand-active);
  background: var(--surface-glass-selected);
  box-shadow: var(--inner-highlight);
}

.agent-title-area {
  min-width: 0;
  flex: 1;

  h3,
  span {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  h3 {
    margin: 0;
    color: var(--text-primary);
    font-size: 16px;
  }

  span {
    display: block;
    margin-top: 4px;
    color: var(--text-muted);
    font-size: 12px;
  }
}

.agent-card-description {
  display: -webkit-box;
  min-height: 40px;
  margin: 14px 0 0;
  overflow: hidden;
  color: var(--text-secondary);
  font-size: 13px;
  line-height: 1.55;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}

.agent-card-meta {
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 12px;
}

.agent-card-footer {
  justify-content: flex-end;
  gap: 8px;
  margin-top: auto;
  padding-top: 12px;
  border-top: 1px solid var(--border-divider);
}

@media (max-width: 1080px) {
  .agent-empty-guide {
    grid-template-columns: minmax(190px, 220px) minmax(0, 1fr);
  }
}

@media (max-width: 900px) {
  .agent-filter-bar :deep(.filter-bar__fields) {
    flex-basis: 100%;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    min-width: 0;
  }

  .filter-control.is-keyword {
    grid-column: 1 / -1;
  }
}

@media (max-width: 760px) {
  .agent-shell :deep(.data-table-shell__toolbar),
  .agent-shell :deep(.data-table-shell__body) {
    margin-right: 16px;
    margin-left: 16px;
  }

  .agent-shell :deep(.data-table-shell__toolbar) {
    margin-top: 16px;
  }

  .agent-shell :deep(.data-table-shell__body) {
    margin-top: 16px;
  }

  .agent-filter-bar :deep(.filter-bar__fields) {
    flex-basis: 100%;
    grid-template-columns: minmax(0, 1fr);
    min-width: 0;
  }

  .agent-filter-bar :deep(.filter-bar__actions) {
    width: 100%;
    justify-content: flex-start;
  }

  .filter-control.is-keyword {
    grid-column: auto;
  }

  .filter-control,
  .filter-control.is-keyword,
  .filter-control.is-project,
  .filter-control.is-narrow {
    width: 100%;
    min-width: 0;
  }

  .agent-shell :deep(.data-table-shell__pagination) {
    align-items: flex-start;
    flex-direction: column;
    padding-right: 16px;
    padding-left: 16px;
    overflow-x: auto;
  }

  .agent-shell :deep(.data-table-shell__empty) {
    padding: 18px 16px 22px;
  }

  .agent-empty-guide {
    grid-template-columns: 1fr;
    justify-items: center;
    padding: 22px;
    text-align: center;
  }

  .agent-empty-guide__visual {
    width: min(220px, 100%);
  }

  .agent-empty-guide__content {
    align-items: center;
  }

}
</style>
