<template>
  <WorkbenchPage class="agent-page" layout="list">
    <CollapsibleHeaderRegion :collapsed="isAgentHeaderCollapsed">
      <PageHeader
        variant="overview"
        domain="agent"
        eyebrow="Agent Catalog"
        title="Agent 管理"
        description="统一管理 Agent 身份、项目归属、Supervisor 配置状态和 Workflow-as-Tool 目录。"
        :collapsed="isAgentHeaderCollapsed"
      >
        <template #tags>
          <el-tag effect="light">Supervisor Agent</el-tag>
          <el-tag type="success" effect="light">当前 {{ agentStatistics.totalAgents }} 个</el-tag>
          <el-tag type="info" effect="light">Workflow-as-Tool</el-tag>
        </template>
        <template #actions>
          <ViewToggle v-model="viewMode" />
          <el-button :icon="Connection" @click="router.push('/runops')">运行中心</el-button>
          <el-button type="primary" :icon="Plus" @click="handleCreate">新建智能体</el-button>
        </template>
      </PageHeader>

      <template #summary>
        <MetricStrip :items="metricItems" density="compact" aria-label="智能体指标概览" />
      </template>
    </CollapsibleHeaderRegion>

    <DataTableShell
      v-model:current-page="currentPage"
      v-model:page-size="pageSize"
      class="agent-shell workbench-list-surface"
      :loading="loading"
      :empty="filteredAgents.length === 0"
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
          <el-select
            v-model="filterProjectId"
            placeholder="所属项目"
            clearable
            filterable
            class="filter-control is-project"
          >
            <el-option
              v-for="project in scanProjects"
              :key="project.id"
              :label="projectOptionLabel(project)"
              :value="project.id"
            />
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
            <el-tag size="small" effect="plain">{{ agent.workflowToolCount || 0 }} tools</el-tag>
          </div>

          <div class="agent-card-footer" @click.stop>
            <el-button
              v-for="action in agentListActions(agent)"
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
              <button type="button" class="agent-name-cell" @click="handleEdit(row.id)">
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
              <small class="config-tool-count">{{ row.workflowToolCount || 0 }} 个 Workflow 工具</small>
            </template>
          </el-table-column>
          <el-table-column prop="enabled" label="状态" width="86" align="center">
            <template #default="{ row }">
              <el-switch
                :model-value="row.enabled !== false"
                size="small"
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
                  v-for="action in agentListActions(row)"
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
        <div v-if="agents.length === 0" class="agent-empty-guide">
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
            <small class="agent-empty-guide__eyebrow">SUPERVISOR AGENT</small>
            <h3>创建第一个 Agent，开始编排业务能力</h3>
            <p>
              为 Agent 定义身份与项目归属，发布 Supervisor 配置，并将可复用的 Workflow
              加入 Workflow-as-Tool 目录。
            </p>

            <button class="agent-empty-guide__action" type="button" @click="handleCreate">
              <span class="agent-empty-guide__action-icon">
                <el-icon><Plus /></el-icon>
              </span>
              <span>
                <strong>新建智能体</strong>
                <small>定义身份、归属与 Supervisor 运行配置</small>
              </span>
              <el-icon class="agent-empty-guide__action-arrow"><ArrowRight /></el-icon>
            </button>

            <small class="agent-empty-guide__note">
              创建后可继续配置 Workflow-as-Tool，并从运行中心查看执行记录。
            </small>
          </div>
        </div>

        <el-empty v-else description="暂无符合条件的智能体">
          <el-button type="primary" :icon="Plus" @click="handleCreate">新建智能体</el-button>
        </el-empty>
      </template>

      <template #pagination>
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
  </WorkbenchPage>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox, type TableInstance } from 'element-plus'
import { ArrowRight, Connection, Cpu, Plus, RefreshLeft, Search } from '@element-plus/icons-vue'
import type { Agent, AgentStatistics } from '@/types/workflow'
import {
  deleteAgent,
  getAgentStatistics,
  listAgents,
  updateAgent,
} from '@/api/workflow'
import { getScanProjects } from '@/api/scanProject'
import type { ScanProject } from '@/types/scanProject'
import ViewToggle from '@/components/ViewToggle.vue'
import { useProjectStore } from '@/store/project'
import { agentListActions, type AgentListActionId } from '@/utils/agentActions'
import CollapsibleHeaderRegion from '@/components/common/CollapsibleHeaderRegion.vue'
import DataTableShell from '@/components/common/DataTableShell.vue'
import FilterBar from '@/components/common/FilterBar.vue'
import MetricStrip from '@/components/common/MetricStrip.vue'
import PageHeader from '@/components/common/PageHeader.vue'
import WorkbenchPage from '@/components/common/WorkbenchPage.vue'
import type { MetricStripItem } from '@/components/common/glassWorkbench'
import { useCollapsiblePageHeader } from '@/composables/useCollapsiblePageHeader'
import agentSupervisorOnboardingIllustration from '@/assets/illustrations/agent-supervisor-onboarding.webp'

const route = useRoute()
const router = useRouter()
const projectStore = useProjectStore()

const agents = ref<Agent[]>([])
const statistics = ref<AgentStatistics | null>(null)
const scanProjects = ref<ScanProject[]>([])
const loading = ref(false)
const filterKeyword = ref('')
const filterEnabled = ref<boolean | ''>('')
const filterProjectId = ref<number | undefined>(undefined)
const viewMode = ref<'table' | 'card'>('table')
const currentPage = ref(1)
const pageSize = ref(10)
const agentTableRef = ref<TableInstance>()
const agentTableShellRef = ref<HTMLElement | null>(null)
/** 限制表格主体高度，搜索栏/表头固定，仅表体滚动。 */
const agentTableMaxHeight = ref(480)

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
const scopeLabel = computed(() => filterProjectId.value == null ? '全量 Agent 目录' : '当前项目范围')

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

function projectOptionLabel(project?: ScanProject | null) {
  if (!project) return ''
  const code = project.projectCode ? ` / ${project.projectCode}` : ''
  const env = project.environment ? ` · ${project.environment}` : ''
  return `${project.name}${code}${env}`
}

function projectCodeById(projectId?: number | null) {
  if (projectId == null) return null
  return scanProjects.value.find((project) => project.id === projectId)?.projectCode || null
}

function syncProjectFilter(allowRouteFallback = false) {
  if (projectStore.currentProjectId !== null) {
    filterProjectId.value = projectStore.currentProjectId
    return
  }
  const queryProjectId = Number(route.query.projectId)
  if (allowRouteFallback && Number.isFinite(queryProjectId) && queryProjectId > 0) {
    filterProjectId.value = queryProjectId
    return
  }
  filterProjectId.value = undefined
}

async function loadScanProjects() {
  try {
    const { data } = await getScanProjects()
    scanProjects.value = Array.isArray(data) ? data : []
    projectStore.projects = scanProjects.value
  } catch {
    scanProjects.value = []
  }
}

function currentScopeParams() {
  return filterProjectId.value !== undefined ? { projectId: filterProjectId.value } : undefined
}

async function fetchData() {
  loading.value = true
  try {
    const [listResult, statisticsResult] = await Promise.allSettled([
      listAgents(currentScopeParams()),
      getAgentStatistics(currentScopeParams()),
    ])

    if (listResult.status === 'fulfilled') {
      agents.value = Array.isArray(listResult.value.data) ? listResult.value.data : []
    } else {
      agents.value = []
      ElMessage.error('智能体列表加载失败')
    }

    statistics.value = statisticsResult.status === 'fulfilled'
      ? statisticsResult.value.data
      : null
    currentPage.value = 1
  } finally {
    loading.value = false
    await nextTick()
    refreshAgentHeaderScrollTargets()
    scheduleAgentListLayout()
  }
}

watch(
  () => [filteredAgents.value.length, currentPage.value, pageSize.value] as const,
  async () => {
    await nextTick()
    scheduleAgentListLayout()
  },
)

function handleQuery() {
  currentPage.value = 1
  fetchData()
}

function resetFilters() {
  filterKeyword.value = ''
  filterEnabled.value = ''
  filterProjectId.value = projectStore.currentProjectId ?? undefined
  currentPage.value = 1
  fetchData()
}

function handleCreate() {
  router.push({
    path: '/agent/new/edit',
    query: filterProjectId.value !== undefined ? { projectId: filterProjectId.value } : {},
  })
}

function handleEdit(id: string) {
  router.push(`/agent/${id}/edit`)
}

function handleAgentAction(agent: Agent, actionId: AgentListActionId) {
  if (actionId === 'edit') return handleEdit(agent.id)
  if (actionId === 'debug') return router.push(`/agent/${agent.id}/debug`)
  if (actionId === 'eval') return router.push(`/agent/${agent.id}/evals`)
  if (actionId === 'runops') return router.push({ path: '/runops', query: { agentId: agent.id } })
  return handleDelete(agent.id)
}

async function handleToggle(agent: Agent, enabled: boolean) {
  try {
    await updateAgent(agent.id, { enabled })
    agent.enabled = enabled
    ElMessage.success(enabled ? '已启用' : '已停用')
    await fetchData()
  } catch {
    ElMessage.error('操作失败')
  }
}

async function handleDelete(id: string) {
  try {
    await ElMessageBox.confirm('确认删除该 Agent？', '删除 Agent', { type: 'warning' })
    await deleteAgent(id)
    ElMessage.success('删除成功')
    fetchData()
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') {
      ElMessage.error('删除失败')
    }
  }
}

onMounted(async () => {
  window.addEventListener('resize', scheduleAgentListLayout)
  await loadScanProjects()
  syncProjectFilter(true)
  await fetchData()
  await nextTick()
  bindAgentTableShellObserver()
  refreshAgentHeaderScrollTargets()
  scheduleAgentListLayout()
})

onUnmounted(() => {
  window.removeEventListener('resize', scheduleAgentListLayout)
  tableShellObserver?.disconnect()
  tableShellObserver = null
  if (layoutRaf) cancelAnimationFrame(layoutRaf)
  window.clearTimeout(layoutFreezeTimer)
})

watch(
  () => projectStore.currentProjectId,
  () => {
    syncProjectFilter()
    fetchData()
  },
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
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
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
  grid-template-columns: minmax(300px, 2.2fr) minmax(138px, 0.72fr) minmax(128px, 0.64fr) minmax(210px, 1fr);
  align-items: center;
  gap: 12px;
}

.agent-filter-bar :deep(.filter-bar__actions) {
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
  margin: 28px 28px 0;
}

.agent-shell :deep(.data-table-shell__body) {
  display: flex;
  min-height: 0;
  flex: 1 1 auto;
  flex-direction: column;
  margin: 22px 28px 0;
}

.agent-shell :deep(.data-table-shell__pagination) {
  min-height: 64px;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 14px 28px 16px;
  border-top-color: rgb(var(--brand-primary-rgb) / 0.12);
  color: var(--text-muted);
  background: color-mix(in srgb, var(--surface-glass-control) 78%, transparent);
}

.agent-shell :deep(.data-table-shell__empty) {
  padding: 22px 28px 28px;
}

.agent-empty-guide {
  display: grid;
  width: min(980px, 100%);
  min-height: 340px;
  grid-template-columns: minmax(230px, 280px) minmax(0, 1fr);
  align-items: center;
  gap: clamp(28px, 4vw, 52px);
  margin: 0 auto;
  padding: clamp(24px, 3vw, 36px);
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

.agent-empty-guide__eyebrow,
.agent-empty-guide__content h3,
.agent-empty-guide__content p {
  margin: 0;
}

.agent-empty-guide__eyebrow {
  color: var(--brand-active);
  font-size: 0.7rem;
  font-weight: 800;
  letter-spacing: 0.08em;
}

.agent-empty-guide__content h3 {
  margin-top: 7px;
  color: var(--text-primary);
  font-size: 1.15rem;
  line-height: 1.35;
}

.agent-empty-guide__content > p {
  max-width: 560px;
  margin-top: 8px;
  color: var(--text-muted);
  font-size: 0.85rem;
  line-height: 1.65;
}

.agent-empty-guide__action {
  display: grid;
  width: 100%;
  min-width: 0;
  grid-template-columns: 40px minmax(0, 1fr) auto;
  align-items: center;
  gap: 12px;
  margin-top: 20px;
  padding: 12px 14px;
  border: 1px solid color-mix(in srgb, var(--brand-primary) 30%, var(--border-subtle));
  border-radius: var(--radius-md);
  color: var(--text-secondary);
  background: color-mix(in srgb, var(--brand-primary) 8%, var(--surface-solid-panel));
  font: inherit;
  text-align: left;
  cursor: pointer;
  transition:
    border-color var(--motion-duration-fast) ease,
    background var(--motion-duration-fast) ease,
    transform var(--motion-duration-fast) ease;
}

.agent-empty-guide__action:hover {
  border-color: color-mix(in srgb, var(--brand-primary) 48%, var(--border-subtle));
  background: color-mix(in srgb, var(--brand-primary) 12%, var(--surface-solid-panel));
  transform: translateY(-1px);
}

.agent-empty-guide__action:focus-visible {
  outline: 2px solid var(--border-focus);
  outline-offset: 2px;
}

.agent-empty-guide__action > span:nth-child(2) {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 3px;
}

.agent-empty-guide__action strong {
  color: var(--text-primary);
  font-size: 0.86rem;
}

.agent-empty-guide__action small {
  overflow: hidden;
  color: var(--text-muted);
  font-size: 0.74rem;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.agent-empty-guide__action-icon {
  display: inline-flex;
  width: 40px;
  height: 40px;
  align-items: center;
  justify-content: center;
  border-radius: 12px;
  color: var(--brand-primary);
  background: var(--surface-solid-panel);
  box-shadow: var(--inner-highlight);
}

.agent-empty-guide__action-arrow {
  color: var(--brand-active);
}

.agent-empty-guide__note {
  margin-top: 11px;
  color: var(--text-muted);
  font-size: 0.72rem;
  line-height: 1.5;
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
  --el-table-header-bg-color: color-mix(in srgb, var(--brand-selected-bg) 42%, var(--surface-solid-control));
  --el-table-row-hover-bg-color: rgb(var(--brand-primary-rgb) / 0.08);
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
  background: color-mix(in srgb, var(--brand-selected-bg) 42%, var(--surface-solid-control)) !important;
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
  background-color: var(--surface-solid-panel) !important;
}

.agent-table :deep(th.el-table-fixed-column--right),
.agent-table :deep(th.el-table-fixed-column--left) {
  background-color: color-mix(in srgb, var(--brand-selected-bg) 42%, var(--surface-solid-control)) !important;
}

.agent-table :deep(td.el-table-fixed-column--right),
.agent-table :deep(td.el-table-fixed-column--left) {
  background-color: var(--surface-solid-panel) !important;
}

.agent-table :deep(.el-table__row:hover > td.el-table-fixed-column--right),
.agent-table :deep(.el-table__row:hover > td.el-table-fixed-column--left),
.agent-table :deep(.el-table__body tr.hover-row > td.el-table-fixed-column--right),
.agent-table :deep(.el-table__body tr.hover-row > td.el-table-fixed-column--left) {
  background-color: color-mix(in srgb, var(--brand-primary) 8%, var(--surface-solid-panel)) !important;
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

@media (max-width: 1280px) {
  .agent-filter-bar {
    grid-template-columns: 1fr;
  }

  .agent-filter-bar :deep(.filter-bar__actions) {
    justify-self: end;
  }

  .agent-empty-guide {
    grid-template-columns: minmax(190px, 220px) minmax(0, 1fr);
  }
}

@media (max-width: 900px) {
  .agent-filter-bar :deep(.filter-bar__fields) {
    grid-template-columns: repeat(2, minmax(0, 1fr));
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
    grid-template-columns: minmax(0, 1fr);
  }

  .agent-filter-bar :deep(.filter-bar__actions) {
    justify-self: stretch;
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

  .agent-empty-guide__action {
    text-align: left;
  }
}
</style>
