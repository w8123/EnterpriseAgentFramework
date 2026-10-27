<template>
  <LiquidGlassPage class="workflow-list-page project-workbench-page">
    <CollapsibleHeaderRegion :collapsed="isWorkflowHeaderCollapsed">
      <PageHeader
        variant="overview"
        presentation="plain"
        action-presentation="inline"
        domain="workflow"
        :title="pageTitle"
        :description="pageDescription"
        :collapsed="isWorkflowHeaderCollapsed"
      >
        <template #tags>
          <el-tag effect="light">{{ workflowScopeLabel }}</el-tag>
          <el-tag v-if="workflowListStatus === 'success'" type="success" effect="light">
            当前 {{ workflows.length }} 个 Workflow
          </el-tag>
        </template>
        <template #actions>
          <LiquidGlassButton source="wallpaper" v-if="projectScoped" @click="backToProject">返回项目</LiquidGlassButton>
          <LiquidGlassButton source="wallpaper"
            v-if="scopeCanLoadData && canCreateWorkflowInCurrentScope"
            type="primary"
            :icon="Plus"
            @click="openCreateDialog"
          >
            新建 Workflow
          </LiquidGlassButton>
        </template>
      </PageHeader>

      <template #summary>
        <MetricStrip v-if="workflowListStatus === 'success'" class="glass-workspace-adaptive" :items="metricItems" density="compact" aria-label="Workflow 指标概览" />
      </template>
    </CollapsibleHeaderRegion>

    <section
      v-if="!scopeCanLoadData"
      class="workflow-scope-panel glass-surface-panel glass-workspace-adaptive"
      :aria-label="workflowScopeLabel"
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

    <LiquidGlassTable
      v-else
      v-model:current-page="currentPage"
      v-model:page-size="pageSize"
      class="workflow-card workbench-list-surface"
      :loading="loading"
      :total="filteredWorkflows.length"
      :page-sizes="[5, 10, 20, 50]"
      :show-pagination="workflowListStatus === 'success'"
      pagination-label="Workflow 分页"
    >
      <template #toolbar>
        <LiquidGlassFilterBar :loading="loading" :show-reset="false" @query="searchWorkflows">
          <LiquidGlassInput
            v-model="keyword"
            class="search-input"
            clearable
            placeholder="搜索 Workflow 名称、Key、描述、项目"
          />
          <LiquidGlassSelect v-model="filters.workflowKind" clearable placeholder="Workflow 形态">
            <el-option
              v-for="item in WORKFLOW_KIND_SELECT_OPTIONS"
              :key="item.value"
              :label="item.label"
              :value="item.value"
            />
          </LiquidGlassSelect>
          <LiquidGlassSelect v-model="filters.status" clearable placeholder="发布状态">
            <el-option
              v-for="item in WORKFLOW_STATUS_SELECT_OPTIONS"
              :key="item.value"
              :label="item.label"
              :value="item.value"
            />
          </LiquidGlassSelect>
        </LiquidGlassFilterBar>
      </template>

      <div v-if="workflowListStatus === 'error'" class="workflow-list-error" role="alert" aria-live="polite">
        <h3>Workflow 列表加载失败</h3>
        <p>{{ workflowErrorMessage }}</p>
        <el-button type="primary" :loading="loading" @click="loadWorkflows">重试</el-button>
      </div>

      <div v-else ref="workflowTableShellRef" class="workflow-table-shell">
        <el-table
          :data="pagedWorkflows"
          row-key="id"
          class="workflow-table"
          :max-height="workflowTableMaxHeight"
          @row-click="openWorkflow"
        >
          <el-table-column label="Workflow 名称" min-width="260">
            <template #default="{ row }">
              <button type="button" class="workflow-name-cell workflow-name-cell-btn" @click="openWorkflow(row)">
                <div class="workflow-avatar" :class="workflowAvatarClass(effectiveWorkflowKind(row))">
                  {{ workflowInitial(row.name) }}
                </div>
                <div>
                  <strong>{{ row.name }}</strong>
                  <span>{{ row.keySlug }}</span>
                </div>
              </button>
            </template>
          </el-table-column>
          <el-table-column label="描述" min-width="220" show-overflow-tooltip>
            <template #default="{ row }">
              <span class="muted">{{ row.description || '可执行图资产' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="项目" min-width="120">
            <template #default="{ row }">
              <span class="muted">{{ row.projectCode || '-' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="业务形态" width="130">
            <template #default="{ row }">
              <el-tag :type="workflowKindTagType(effectiveWorkflowKind(row))" effect="light">
                {{ formatWorkflowKindLabel(effectiveWorkflowKind(row)) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="执行引擎" width="130">
            <template #default="{ row }">
              <span class="muted">{{ formatWorkflowExecutionEngineLabel(row.executionEngine) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="112">
            <template #default="{ row }">
              <span class="status-pill" :class="workflowStatusClass(row.status)">
                <i />
                {{ formatWorkflowStatusLabel(row.status) }}
              </span>
            </template>
          </el-table-column>
          <el-table-column label="定义 / 来源" width="180">
            <template #default="{ row }">
              <span class="muted">
                {{ formatWorkflowDefinitionAuthorityLabel(row.definitionAuthority) }}
                · {{ formatWorkflowCreationChannelLabel(row.creationChannel) }}
              </span>
            </template>
          </el-table-column>

          <template #empty>
            <div v-if="workflowListStatus === 'success'" class="workflow-table-empty-state">
              <div class="empty-main">
                <div class="empty-illustration" aria-hidden="true">
                  <img class="empty-illustration-img" src="/智能化.svg" alt="" />
                </div>
                <div class="empty-body">
                  <div class="empty-copy">
                    <h3>还没有匹配的 Workflow</h3>
                    <p>可以调整名称、类型或状态筛选，也可以新建一个可执行图资产。</p>
                  </div>
                  <div class="empty-actions">
                    <el-button
                      v-if="canCreateWorkflowInCurrentScope"
                      type="primary"
                      :icon="Plus"
                      @click="openCreateDialog"
                    >
                      新建 Workflow
                    </el-button>
                    <el-button @click="resetFilters">重置筛选</el-button>
                  </div>
                </div>
              </div>
            </div>
          </template>
        </el-table>
      </div>

    </LiquidGlassTable>

    <AppDialog
      v-model="createDialogVisible"
      title="新建 Workflow"
      width="560px"
      destroy-on-close
    >
      <el-form label-position="top" class="create-form">
        <el-form-item label="名称" required>
          <el-input
            v-model="createForm.name"
            maxlength="80"
            show-word-limit
            placeholder="订单页助手"
            @blur="fillKeySlugFromName"
          />
        </el-form-item>
        <el-form-item label="Key" required>
          <el-input
            v-model="createForm.keySlug"
            maxlength="128"
            placeholder="orders-page-assistant"
          />
        </el-form-item>
        <el-form-item label="项目">
          <el-select
            v-model="createForm.projectId"
            class="create-project-select"
            :clearable="canCreatePlatformWorkflow"
            :disabled="createProjectSelectDisabled"
            :placeholder="createProjectPlaceholder"
            :value-on-clear="null"
            @update:model-value="handleCreateProjectSelection"
            @clear="clearCreateProjectSelection"
          >
            <el-option
              v-for="project in createProjectOptions"
              :key="project.id"
              :label="`${project.name} · ${project.projectCode}`"
              :value="project.id"
            >
              <div class="create-project-option">
                <span>{{ project.name }}</span>
                <small>{{ project.projectCode }}</small>
              </div>
            </el-option>
          </el-select>
          <div v-if="createProjectHint || createCatalogRetryAvailable" class="create-project-feedback" aria-live="polite">
            <p v-if="createProjectHint" class="create-project-hint">{{ createProjectHint }}</p>
            <el-button
              v-if="createCatalogRetryAvailable"
              class="create-project-retry"
              text
              type="primary"
              :loading="createCatalogRetrying"
              @click="retryCreateCatalog"
            >
              重试加载项目目录
            </el-button>
          </div>
        </el-form-item>
        <div class="create-form-grid">
          <el-form-item label="类型">
            <el-select v-model="createForm.workflowKind">
              <el-option
                v-for="item in WORKFLOW_KIND_SELECT_OPTIONS"
                :key="item.value"
                :label="item.label"
                :value="item.value"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="执行引擎">
            <el-select v-model="createForm.executionEngine">
              <el-option label="GraphSpec" value="GRAPH_SPEC" />
            </el-select>
          </el-form-item>
        </div>
        <el-form-item label="描述">
          <el-input
            v-model="createForm.description"
            type="textarea"
            :rows="3"
            maxlength="240"
            show-word-limit
            placeholder="可选"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <LiquidGlassButton :disabled="creating" @click="createDialogVisible = false">取消</LiquidGlassButton>
        <LiquidGlassButton
          type="primary"
          :disabled="createSubmitDisabled"
          :loading="creating"
          @click="submitCreateWorkflow"
        >
          创建并进入编排
        </LiquidGlassButton>
      </template>
    </AppDialog>
  </LiquidGlassPage>
</template>

<script setup lang="ts">
import AppDialog from '@/components/common/AppDialog.vue'
import { computed, nextTick, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import CollapsibleHeaderRegion from '@/components/common/CollapsibleHeaderRegion.vue'
import MetricStrip from '@/components/common/MetricStrip.vue'
import type { MetricStripItem } from '@/components/common/glassWorkbench'
import { LiquidGlassButton, LiquidGlassFilterBar, LiquidGlassInput, LiquidGlassSelect, LiquidGlassTable, LiquidGlassPage } from '@/components/common/liquid-glass'
import PageHeader from '@/components/common/PageHeader.vue'
import ProjectScopeState from '@/components/common/ProjectScopeState.vue'
import { createWorkflow, listWorkflows } from '@/api/workflow'
import type { WorkflowWorkingCopy, WorkflowWorkingCopyInput } from '@/types/workflow'
import type { ScanProject } from '@/types/scanProject'
import { useAppStore } from '@/store/app'
import { useCollapsiblePageHeader } from '@/composables/useCollapsiblePageHeader'
import { usePageProjectScope } from '@/composables/usePageProjectScope'
import {
  WORKFLOW_STATUS_SELECT_OPTIONS,
  WORKFLOW_KIND_SELECT_OPTIONS,
  formatWorkflowCreationChannelLabel,
  formatWorkflowExecutionEngineLabel,
  formatWorkflowKindLabel,
  formatWorkflowDefinitionAuthorityLabel,
  formatWorkflowStatusLabel,
} from '@/utils/workflowLabels'
import {
  hasPlatformGlobalPermissionGrant,
  hasPlatformResourcePermission,
  PLATFORM_PERMISSION_WORKFLOW_WRITE,
} from '@/auth/platformAccess'
import { platformSessionId, platformSessionUser } from '@/auth/platformSession'
import type {
  ProjectScopeQueryReference,
  ResolvedProjectScope,
  ResolvedProjectScopeSuccess,
} from '@/utils/projectScope'

type WorkflowListStatus = 'idle' | 'loading' | 'success' | 'error'
type WorkflowListParams = {
  projectId?: number
  projectCode?: string
  workflowKind?: string
  status?: string
}
type WorkflowCreateScopeSnapshot = {
  requestKey: string
  sessionId: string
  reference: ProjectScopeQueryReference
  resolution: ResolvedProjectScopeSuccess
}
type WorkflowCreateProjectIdentity = {
  id: number
  projectCode: string
}

const router = useRouter()
const appStore = useAppStore()
const pageProjectScope = usePageProjectScope({ required: true })
const scopeCanLoadData = pageProjectScope.canLoadData
const scopeStatus = pageProjectScope.status
const scopeFeedbackMessage = pageProjectScope.feedbackMessage
const scopeRecoveryLabel = pageProjectScope.recoveryLabel
const scopeRetrying = ref(false)
const createCatalogRetrying = ref(false)

const scopeStateTitle = computed(() => (
  scopeStatus.value === 'pending' ? '正在确认项目范围' : '需要确认项目范围'
))
const scopeNeedsChoice = computed(() => (
  pageProjectScope.status.value === 'blocked'
    && pageProjectScope.recoveryAction.value === 'select-project'
))
const scopeCanRetry = computed(() => (
  (pageProjectScope.recoveryAction.value === 'retry-catalog'
    || pageProjectScope.recoveryAction.value === 'retry-normalization')
    && !pageProjectScope.isCatalogLoading.value
))

function canUseWorkflowPermission(permission: string, projectCode?: string | null) {
  return hasPlatformResourcePermission(
    platformSessionUser.value?.permissionGrants,
    permission,
    'PROJECT',
    null,
    projectCode,
  )
}

const canCreatePlatformWorkflow = computed(() => hasPlatformGlobalPermissionGrant(
  platformSessionUser.value?.permissionGrants,
  PLATFORM_PERMISSION_WORKFLOW_WRITE,
))

const canCreateWorkflowInCurrentScope = computed(() => {
  if (!scopeCanLoadData.value) return false
  const resolution = pageProjectScope.resolution.value
  if (resolution?.kind === 'all') {
    return canCreatePlatformWorkflow.value
      || pageProjectScope.readableProjects.value.some((project) => (
        Boolean(project.projectCode?.trim())
        && canUseWorkflowPermission(PLATFORM_PERMISSION_WORKFLOW_WRITE, project.projectCode)
      ))
  }
  return resolution?.kind === 'project'
    && Boolean(resolution.project.projectCode)
    && canUseWorkflowPermission(PLATFORM_PERMISSION_WORKFLOW_WRITE, resolution.project.projectCode)
})

function canWriteWorkflow(row: WorkflowWorkingCopy) {
  return canUseWorkflowPermission(PLATFORM_PERMISSION_WORKFLOW_WRITE, row.projectCode)
}

const workflowListStatus = ref<WorkflowListStatus>('idle')
const loading = computed(() => workflowListStatus.value === 'loading')
const workflowErrorMessage = ref('')
const creating = ref(false)
const createDialogVisible = ref(false)
const workflows = ref<WorkflowWorkingCopy[]>([])
const keyword = ref('')
const appliedKeyword = ref('')
const currentPage = ref(1)
const pageSize = ref(10)
const workflowTableMaxHeight = ref(480)
const workflowTableShellRef = ref<HTMLElement | null>(null)
const filters = reactive({
  workflowKind: '',
  status: '',
})
const appliedFilters = reactive({
  workflowKind: '',
  status: '',
})
const createForm = reactive<{
  name: string
  keySlug: string
  projectId: number | null
  workflowKind: string
  executionEngine: string
  description: string
}>({
  name: '',
  keySlug: '',
  projectId: null,
  workflowKind: 'GENERAL',
  executionEngine: 'GRAPH_SPEC',
  description: '',
})
const createScopeSnapshot = ref<WorkflowCreateScopeSnapshot | null>(null)
const createTargetIdentity = ref<WorkflowCreateProjectIdentity | null>(null)

const projectScoped = computed(() => (
  scopeCanLoadData.value && pageProjectScope.resolution.value?.kind === 'project'
))
const workflowScopeLabel = computed(() => {
  if (!scopeCanLoadData.value) return '范围未确认'
  const resolution = pageProjectScope.resolution.value
  if (resolution?.kind === 'all') return '全部 Workflow'
  if (resolution?.kind === 'project') return resolution.project.name
  return '范围未确认'
})
const pageTitle = computed(() => (
  projectScoped.value ? '项目 Workflow' : 'Workflow 编排'
))
const pageDescription = computed(() => (
  projectScoped.value
    ? '当前项目下的可执行 Workflow 资产。'
    : '面向通用流程、页面助手和 SDK 同步图的可执行 Workflow 资产。'
))

const filteredWorkflows = computed(() => {
  const text = appliedKeyword.value.trim().toLowerCase()
  if (!text) return workflows.value
  return workflows.value.filter((workflow) => {
    return [
      workflow.name,
      workflow.keySlug,
      workflow.description,
      workflow.projectCode,
      formatWorkflowKindLabel(effectiveWorkflowKind(workflow)),
      formatWorkflowStatusLabel(workflow.status),
      formatWorkflowDefinitionAuthorityLabel(workflow.definitionAuthority),
      formatWorkflowCreationChannelLabel(workflow.creationChannel),
    ]
      .filter(Boolean)
      .some((value) => String(value).toLowerCase().includes(text))
  })
})

const pagedWorkflows = computed(() => {
  const start = (currentPage.value - 1) * pageSize.value
  return filteredWorkflows.value.slice(start, start + pageSize.value)
})

const metrics = computed(() => {
  const total = workflows.value.length
  const activeCount = workflows.value.filter((workflow) => workflowStatusKey(workflow.status) === 'ACTIVE').length
  const draftCount = workflows.value.filter((workflow) => workflowStatusKey(workflow.status) === 'DRAFT').length
  const pageAssistantCount = workflows.value.filter((workflow) => workflowKindKey(effectiveWorkflowKind(workflow)) === 'PAGE_ASSISTANT').length
  const sdkManagedCount = workflows.value.filter((workflow) => definitionAuthorityKey(workflow.definitionAuthority) === 'SDK').length
  const projectCount = new Set(workflows.value.map((workflow) => workflow.projectCode).filter(Boolean)).size
  const userManagedCount = workflows.value.filter((workflow) => definitionAuthorityKey(workflow.definitionAuthority) === 'USER').length
  return [
    {
      label: 'Workflow 总数',
      value: total,
      caption: '可执行图资产',
      delta: projectCount ? `${projectCount} 个项目` : '',
      deltaTone: 'brand',
      iconKey: 'workflow-total',
      tone: 'brand',
    },
    {
      label: '已发布数量',
      value: activeCount,
      caption: '可进入运行时',
      delta: draftCount ? `${draftCount} 个草稿` : '',
      deltaTone: 'success',
      iconKey: 'workflow-published',
      tone: 'green',
    },
    {
      label: '页面助手 / SDK 管理',
      value: `${pageAssistantCount} / ${sdkManagedCount}`,
      caption: '业务形态与定义权威',
      delta: pageAssistantCount || sdkManagedCount ? '语义已分维度' : '',
      deltaTone: 'brand',
      iconKey: 'workflow-sources',
      tone: 'info',
    },
    {
      label: '用户维护',
      value: userManagedCount,
      caption: 'Studio 与 AI Coding',
      delta: total ? `${Math.max(total - userManagedCount, 0)} 个托管` : '',
      deltaTone: 'warning',
      iconKey: 'workflow-manual',
      tone: 'orange',
    },
  ]
})

const metricItems = computed<MetricStripItem[]>(() => metrics.value.map(metric => ({
  key: metric.iconKey,
  label: metric.label,
  value: metric.value,
  hint: [metric.caption, metric.delta].filter(Boolean).join(' · '),
  iconKey: metric.iconKey,
  tone: metric.tone === 'green' ? 'success' : metric.tone === 'orange' ? 'warning' : metric.tone as 'brand' | 'info',
})))

let tableHeightRaf = 0
let tableShellObserver: ResizeObserver | null = null
let workflowDataGeneration = 0
let isUnmounted = false

const {
  collapsed: isWorkflowHeaderCollapsed,
  refreshScrollTargets: refreshWorkflowHeaderScrollTargets,
} = useCollapsiblePageHeader({
  rootSelector: '.workflow-list-page',
  scrollSelectors: [
    '.workflow-table .el-scrollbar__wrap',
    '.workflow-table .el-table__body-wrapper',
  ],
  onScroll: scheduleUpdateWorkflowTableMaxHeight,
  onLayoutChange: scheduleUpdateWorkflowTableMaxHeight,
})

onMounted(() => {
  window.addEventListener('resize', scheduleUpdateWorkflowTableMaxHeight)
  nextTick(() => {
    refreshWorkflowHeaderScrollTargets()
    bindWorkflowTableShellObserver()
    scheduleUpdateWorkflowTableMaxHeight()
  })
})

onUnmounted(() => {
  isUnmounted = true
  workflowDataGeneration += 1
  window.removeEventListener('resize', scheduleUpdateWorkflowTableMaxHeight)
  tableShellObserver?.disconnect()
  tableShellObserver = null
  if (tableHeightRaf) cancelAnimationFrame(tableHeightRaf)
})

watch(
  () => [
    pageProjectScope.requestKey.value,
    pageProjectScope.canLoadData.value,
    pageProjectScope.isActive.value,
  ] as const,
  () => {
    invalidateWorkflowData()
    if (pageProjectScope.canLoadData.value) void loadWorkflows()
  },
  { immediate: true },
)

watch(pageSize, () => {
  currentPage.value = 1
})

watch(
  () => filteredWorkflows.value.length,
  () => {
    const maxPage = Math.max(1, Math.ceil(filteredWorkflows.value.length / pageSize.value))
    if (currentPage.value > maxPage) currentPage.value = maxPage
    nextTick(scheduleUpdateWorkflowTableMaxHeight)
  },
)

watch(
  workflowTableShellRef,
  () => {
    bindWorkflowTableShellObserver()
    nextTick(scheduleUpdateWorkflowTableMaxHeight)
  },
  { flush: 'post', immediate: true },
)

watch(createDialogVisible, (visible) => {
  if (!visible) {
    createScopeSnapshot.value = null
    createTargetIdentity.value = null
  }
})

function scheduleUpdateWorkflowTableMaxHeight() {
  if (typeof window === 'undefined') return
  if (tableHeightRaf) return
  tableHeightRaf = requestAnimationFrame(() => {
    tableHeightRaf = 0
    updateWorkflowTableMaxHeight()
  })
}

function bindWorkflowTableShellObserver() {
  tableShellObserver?.disconnect()
  tableShellObserver = null
  if (typeof ResizeObserver === 'undefined' || !workflowTableShellRef.value) return
  tableShellObserver = new ResizeObserver(() => scheduleUpdateWorkflowTableMaxHeight())
  tableShellObserver.observe(workflowTableShellRef.value)
}

function updateWorkflowTableMaxHeight() {
  if (typeof window === 'undefined') return
  const root = document.querySelector('.workflow-list-page')
  const cardEl = root?.querySelector('.workflow-card') as HTMLElement | undefined
  const tableEl = root?.querySelector('.workflow-table') as HTMLElement | undefined
  const footerEl = root?.querySelector('.data-table-shell__pagination') as HTMLElement | undefined
  if (!tableEl) {
    workflowTableMaxHeight.value = Math.max(280, window.innerHeight - 420)
    return
  }
  const tableTop = tableEl.getBoundingClientRect().top
  const viewportPad = 16
  let bottomLimit = window.innerHeight - viewportPad
  if (cardEl) {
    const cardBottom = cardEl.getBoundingClientRect().bottom
    if (cardBottom > tableTop && cardBottom <= window.innerHeight) {
      bottomLimit = Math.min(bottomLimit, cardBottom - 12)
    }
  }
  if (footerEl && footerEl.offsetParent !== null) {
    const footerTop = footerEl.getBoundingClientRect().top
    if (footerTop > tableTop && footerTop < bottomLimit) {
      bottomLimit = footerTop - 12
    }
  }
  workflowTableMaxHeight.value = Math.max(260, Math.floor(bottomLimit - tableTop))
}

function invalidateWorkflowData() {
  workflowDataGeneration += 1
  workflows.value = []
  workflowListStatus.value = 'idle'
  workflowErrorMessage.value = ''
  currentPage.value = 1
}

function isCurrentWorkflowRequest(generation: number, scopeKey: string) {
  return !isUnmounted
    && generation === workflowDataGeneration
    && pageProjectScope.canLoadData.value
    && pageProjectScope.requestKey.value === scopeKey
}

function currentWorkflowListParams(): WorkflowListParams {
  const params: WorkflowListParams = {
    ...(pageProjectScope.requestParams.value || {}),
  }
  if (appliedFilters.workflowKind) params.workflowKind = appliedFilters.workflowKind
  if (appliedFilters.status) params.status = appliedFilters.status
  return params
}

async function loadWorkflows() {
  const scopeKey = pageProjectScope.requestKey.value
  const generation = ++workflowDataGeneration
  if (!pageProjectScope.canLoadData.value) {
    workflows.value = []
    workflowListStatus.value = 'idle'
    workflowErrorMessage.value = ''
    return
  }

  const params = currentWorkflowListParams()
  workflowListStatus.value = 'loading'
  workflowErrorMessage.value = ''
  workflows.value = []
  try {
    const response = await listWorkflows(params)
    if (!isCurrentWorkflowRequest(generation, scopeKey)) return
    if (!Array.isArray(response.data)) {
      workflowListStatus.value = 'error'
      workflowErrorMessage.value = 'Workflow 列表返回数据格式异常，请重试'
      return
    }
    workflows.value = response.data as WorkflowWorkingCopy[]
    workflowListStatus.value = 'success'
  } catch (error) {
    if (!isCurrentWorkflowRequest(generation, scopeKey)) return
    workflows.value = []
    workflowListStatus.value = 'error'
    workflowErrorMessage.value = error instanceof Error && error.message
      ? error.message
      : 'Workflow 列表加载失败，请重试'
  } finally {
    if (isCurrentWorkflowRequest(generation, scopeKey)) {
      nextTick(() => {
        refreshWorkflowHeaderScrollTargets()
        bindWorkflowTableShellObserver()
        scheduleUpdateWorkflowTableMaxHeight()
      })
    }
  }
}

function searchWorkflows() {
  appliedKeyword.value = keyword.value
  appliedFilters.workflowKind = filters.workflowKind
  appliedFilters.status = filters.status
  currentPage.value = 1
  void loadWorkflows()
}

function resetFilters() {
  keyword.value = ''
  appliedKeyword.value = ''
  filters.workflowKind = ''
  filters.status = ''
  appliedFilters.workflowKind = ''
  appliedFilters.status = ''
  currentPage.value = 1
  void loadWorkflows()
}

function openScopeSelector() {
  if (appStore.sidebarCollapsed) appStore.toggleSidebar()
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

function openStudio(id: string) {
  if (!scopeCanLoadData.value) return
  const workflow = workflows.value.find((item) => item.id === id)
  if (workflow && !canWriteWorkflow(workflow)) return
  void router.push(`/workflows/${id}/studio`)
}

function openWorkflow(row: WorkflowWorkingCopy) {
  openStudio(row.id)
}

function backToProject() {
  const resolution = pageProjectScope.resolution.value
  if (!scopeCanLoadData.value || resolution?.kind !== 'project' || !resolution.project.projectCode) return
  void router.push({
    name: 'RegistryProjectDetail',
    params: { projectCode: resolution.project.projectCode },
  })
}

function snapshotResolution(resolution: ResolvedProjectScopeSuccess): ResolvedProjectScopeSuccess {
  if (resolution.kind === 'all') return { kind: 'all' }
  return {
    kind: 'project',
    project: { ...resolution.project },
  }
}

function sameResolvedScope(
  current: ResolvedProjectScope | null,
  snapshot: ResolvedProjectScopeSuccess,
) {
  if (
    !current
    || (current.kind !== 'all' && current.kind !== 'project')
    || current.kind !== snapshot.kind
  ) return false
  if (current.kind === 'all' && snapshot.kind === 'all') return true
  return current.kind === 'project'
    && snapshot.kind === 'project'
    && current.project.id === snapshot.project.id
    && current.project.projectCode === snapshot.project.projectCode
}

function snapshotScopeReference(reference: ProjectScopeQueryReference): ProjectScopeQueryReference {
  if (reference.kind === 'project') {
    return {
      kind: 'project',
      ...(reference.projectId !== undefined ? { projectId: reference.projectId } : {}),
      ...(reference.projectCode !== undefined ? { projectCode: reference.projectCode } : {}),
    }
  }
  if (reference.kind === 'invalid') return { kind: 'invalid', reason: reference.reason }
  return { kind: reference.kind }
}

function sameScopeReference(
  current: ProjectScopeQueryReference | null,
  snapshot: ProjectScopeQueryReference,
) {
  if (!current || current.kind !== snapshot.kind) return false
  if (current.kind === 'project' && snapshot.kind === 'project') {
    return current.projectId === snapshot.projectId && current.projectCode === snapshot.projectCode
  }
  if (current.kind === 'invalid' && snapshot.kind === 'invalid') return current.reason === snapshot.reason
  return true
}

const createProjectOptions = computed(() => {
  const snapshot = createScopeSnapshot.value
  if (!snapshot) return [] as ScanProject[]
  const readableProjects = pageProjectScope.readableProjects.value
    .filter((project) => Boolean(project.projectCode?.trim()))
    .filter((project) => canUseWorkflowPermission(PLATFORM_PERMISSION_WORKFLOW_WRITE, project.projectCode))
  const snapshotResolution = snapshot.resolution
  if (snapshotResolution.kind === 'project') {
    return readableProjects.filter((project) => (
      project.id === snapshotResolution.project.id
      && project.projectCode?.trim() === snapshotResolution.project.projectCode
    ))
  }
  return readableProjects
})

const createTargetProject = computed(() => (
  createForm.projectId === null
    ? null
    : pageProjectScope.readableProjects.value.find((project) => project.id === createForm.projectId) || null
))
const createScopeAddressIsCurrent = computed(() => {
  const snapshot = createScopeSnapshot.value
  return Boolean(
    createDialogVisible.value
      && snapshot
      && !isUnmounted
      && platformSessionId.value === snapshot.sessionId
      && pageProjectScope.isActive.value
      && sameScopeReference(pageProjectScope.reference.value, snapshot.reference),
  )
})
const createScopeIsCurrent = computed(() => {
  if (!createScopeAddressIsCurrent.value) return false
  if (pageProjectScope.isCatalogLoading.value || pageProjectScope.hasCatalogError.value) return true
  return pageProjectScope.requestKey.value === createScopeSnapshot.value!.requestKey
    && sameResolvedScope(pageProjectScope.resolution.value, createScopeSnapshot.value!.resolution)
})
const createTargetIdentityMatches = computed(() => {
  const identity = createTargetIdentity.value
  const project = createTargetProject.value
  return Boolean(
    identity
      && project
      && project.id === identity.id
      && project.projectCode?.trim() === identity.projectCode,
  )
})
const createProjectSelectDisabled = computed(() => (
  creating.value || createScopeSnapshot.value?.resolution.kind === 'project'
))
const createProjectPlaceholder = computed(() => {
  if (createScopeSnapshot.value?.resolution.kind === 'project') {
    return createTargetProject.value?.name || '当前项目'
  }
  return createProjectOptions.value.length ? '可选项目' : '暂无可写项目'
})
const createProjectHint = computed(() => {
  const snapshot = createScopeSnapshot.value
  if (!snapshot) return ''
  if (!createScopeAddressIsCurrent.value) {
    return '项目地址或登录会话已变化，请关闭此窗口后重新打开。'
  }
  if (pageProjectScope.isCatalogLoading.value) {
    return '正在刷新项目目录，已保留输入；完成后可继续创建。'
  }
  if (pageProjectScope.hasCatalogError.value) {
    return '项目目录暂时不可用，已保留输入；可在此重试目录后继续创建。'
  }
  if (!createScopeIsCurrent.value) {
    const currentResolution = pageProjectScope.resolution.value
    if (snapshot.resolution.kind === 'project' && currentResolution?.kind === 'blocked') {
      if (currentResolution.reason === 'project-not-found') {
        return '当前项目已不在项目目录中，请关闭窗口后重新打开。'
      }
      if (currentResolution.reason === 'invalid-reference' || currentResolution.reason === 'project-code-missing') {
        return '当前项目编码已变化或缺失，请关闭窗口后重新打开。'
      }
      if (currentResolution.reason === 'project-forbidden') {
        return '当前项目的 Workflow 范围权限已变化，请关闭窗口后重新打开。'
      }
    }
    return '项目范围已变化，请关闭此窗口后重新打开。'
  }
  if (snapshot.resolution.kind === 'project') {
    const project = createTargetProject.value
    if (!project) return '当前项目已不在项目目录中，请关闭窗口后重新打开。'
    if (project.projectCode?.trim() !== snapshot.resolution.project.projectCode) {
      return '当前项目编码已变化，请关闭窗口后重新打开。'
    }
    if (!canUseWorkflowPermission(PLATFORM_PERMISSION_WORKFLOW_WRITE, project.projectCode)) {
      return '当前项目的 Workflow 写权限已变化，请关闭窗口后重新打开。'
    }
    return `仅可创建到当前项目：${snapshot.resolution.project.name}`
  }
  if (createForm.projectId !== null) {
    if (!createTargetIdentity.value) return '请重新选择一个有 Workflow 写权限的项目。'
    if (!createTargetProject.value) return '所选项目已不在项目目录中，请重新选择项目。'
    if (!createTargetIdentityMatches.value) return '所选项目编码已变化，请重新选择项目。'
    if (!canUseWorkflowPermission(PLATFORM_PERMISSION_WORKFLOW_WRITE, createTargetProject.value.projectCode)) {
      return '所选项目的 Workflow 写权限已变化，请重新选择项目。'
    }
  }
  if (canCreatePlatformWorkflow.value) return '留空创建平台级 Workflow，不归属项目。'
  return '请选择一个有 Workflow 写权限的项目。'
})
const createCatalogRetryAvailable = computed(() => (
  createScopeAddressIsCurrent.value
    && (pageProjectScope.hasCatalogError.value || createCatalogRetrying.value)
))
const createTargetPermissionValid = computed(() => {
  const snapshot = createScopeSnapshot.value
  if (!snapshot || !createScopeIsCurrent.value) return false
  if (snapshot.resolution.kind === 'project') {
    const project = createTargetProject.value
    return createForm.projectId === snapshot.resolution.project.id
      && project?.projectCode?.trim() === snapshot.resolution.project.projectCode
      && canUseWorkflowPermission(PLATFORM_PERMISSION_WORKFLOW_WRITE, project?.projectCode)
  }
  if (createForm.projectId === null) return canCreatePlatformWorkflow.value
  const project = createTargetProject.value
  return Boolean(
    createTargetIdentityMatches.value
      && project?.projectCode?.trim()
      && canUseWorkflowPermission(PLATFORM_PERMISSION_WORKFLOW_WRITE, project.projectCode),
  )
})
const createSubmitDisabled = computed(() => (
  creating.value
    || !createTargetPermissionValid.value
    || pageProjectScope.isCatalogLoading.value
    || pageProjectScope.hasCatalogError.value
))

function normalizeCreateProjectId(value: unknown): number | null {
  if (value === null || value === undefined || value === '') return null
  if (typeof value === 'number') {
    return Number.isSafeInteger(value) && value > 0 ? value : null
  }
  if (typeof value === 'string' && /^\d+$/.test(value)) {
    const projectId = Number(value)
    return Number.isSafeInteger(projectId) && projectId > 0 ? projectId : null
  }
  return null
}

function handleCreateProjectSelection(value: unknown) {
  if (createScopeSnapshot.value?.resolution.kind === 'project') return
  const projectId = normalizeCreateProjectId(value)
  createForm.projectId = projectId
  if (projectId === null) {
    createTargetIdentity.value = null
    return
  }

  const project = pageProjectScope.readableProjects.value.find((item) => item.id === projectId)
  const projectCode = project?.projectCode?.trim()
  if (!project || !projectCode || !canUseWorkflowPermission(PLATFORM_PERMISSION_WORKFLOW_WRITE, projectCode)) {
    createTargetIdentity.value = null
    return
  }
  createTargetIdentity.value = { id: project.id, projectCode }
}

function clearCreateProjectSelection() {
  if (createScopeSnapshot.value?.resolution.kind === 'project') return
  createForm.projectId = null
  createTargetIdentity.value = null
}

async function retryCreateCatalog() {
  if (!createCatalogRetryAvailable.value || createCatalogRetrying.value) return
  createCatalogRetrying.value = true
  try {
    await pageProjectScope.refreshCatalog()
  } finally {
    createCatalogRetrying.value = false
  }
}

function openCreateDialog() {
  if (!canCreateWorkflowInCurrentScope.value) return
  const resolution = pageProjectScope.resolution.value
  const reference = pageProjectScope.reference.value
  if (!resolution || (resolution.kind !== 'all' && resolution.kind !== 'project') || !reference) return
  createScopeSnapshot.value = {
    requestKey: pageProjectScope.requestKey.value,
    sessionId: platformSessionId.value,
    reference: snapshotScopeReference(reference),
    resolution: snapshotResolution(resolution),
  }
  createForm.name = ''
  createForm.keySlug = ''
  createForm.projectId = resolution.kind === 'project' ? resolution.project.id : null
  createTargetIdentity.value = resolution.kind === 'project' && resolution.project.projectCode
    ? { id: resolution.project.id, projectCode: resolution.project.projectCode }
    : null
  createForm.workflowKind = 'GENERAL'
  createForm.executionEngine = 'GRAPH_SPEC'
  createForm.description = ''
  createDialogVisible.value = true
}

function fillKeySlugFromName() {
  if (createForm.keySlug.trim() || !createForm.name.trim()) return
  createForm.keySlug = slugFromName(createForm.name)
}

function slugFromName(value: string) {
  return value
    .trim()
    .toLowerCase()
    .replace(/[^a-z0-9_-]+/g, '-')
    .replace(/^-+|-+$/g, '')
    .slice(0, 128)
}

function validateCreateForm() {
  if (!createForm.name.trim()) {
    ElMessage.warning('请输入 Workflow 名称')
    return false
  }
  fillKeySlugFromName()
  if (!/^[A-Za-z0-9][A-Za-z0-9_-]{1,127}$/.test(createForm.keySlug.trim())) {
    ElMessage.warning('Key 需为 2-128 位，仅支持字母、数字、下划线或连字符')
    return false
  }
  return true
}

function resolveCreateTarget() {
  const snapshot = createScopeSnapshot.value
  const resolution = pageProjectScope.resolution.value
  if (!snapshot || !createScopeAddressIsCurrent.value) {
    ElMessage.warning('项目地址或登录会话已变化，请关闭窗口后重新打开')
    return null
  }
  if (pageProjectScope.isCatalogLoading.value || pageProjectScope.hasCatalogError.value) {
    ElMessage.warning('项目目录暂时不可用，请恢复后再试')
    return null
  }
  if (!resolution || !createScopeIsCurrent.value || !sameResolvedScope(resolution, snapshot.resolution)) {
    ElMessage.warning(createProjectHint.value || '项目范围已变化，请关闭窗口后重新打开')
    return null
  }
  if (snapshot.resolution.kind === 'project') {
    const project = createTargetProject.value
    if (!project) {
      ElMessage.warning('当前项目已不在项目目录中，请关闭窗口后重新打开')
      return null
    }
    if (
      createForm.projectId !== snapshot.resolution.project.id
      || project.projectCode?.trim() !== snapshot.resolution.project.projectCode
    ) {
      ElMessage.warning('当前项目编码已变化，请关闭窗口后重新打开')
      return null
    }
    if (!canUseWorkflowPermission(PLATFORM_PERMISSION_WORKFLOW_WRITE, project.projectCode)) {
      ElMessage.warning('当前项目的 Workflow 写权限已变化，请关闭窗口后重新打开')
      return null
    }
    return { projectId: project.id, projectCode: project.projectCode.trim() }
  }

  if (createForm.projectId === null) {
    if (!canCreatePlatformWorkflow.value) {
      ElMessage.warning('请选择一个有 Workflow 写权限的项目')
      return null
    }
    return { projectId: null, projectCode: null }
  }

  const project = createTargetProject.value
  if (!createTargetIdentity.value) {
    ElMessage.warning('请重新选择一个有 Workflow 写权限的项目')
    return null
  }
  if (!project) {
    ElMessage.warning('所选项目已不在项目目录中，请重新选择项目')
    return null
  }
  if (project.projectCode?.trim() !== createTargetIdentity.value.projectCode) {
    ElMessage.warning('所选项目编码已变化，请重新选择项目')
    return null
  }
  if (!canUseWorkflowPermission(PLATFORM_PERMISSION_WORKFLOW_WRITE, project.projectCode)) {
    ElMessage.warning('所选项目的 Workflow 写权限已变化，请重新选择项目')
    return null
  }
  return { projectId: project.id, projectCode: project.projectCode.trim() }
}

function isCreateOperationCurrent(
  snapshot: WorkflowCreateScopeSnapshot,
  target: { projectId: number | null; projectCode: string | null },
) {
  if (
    !createScopeIsCurrent.value
      || createScopeSnapshot.value !== snapshot
      || pageProjectScope.requestKey.value !== snapshot.requestKey
      || pageProjectScope.isCatalogLoading.value
      || pageProjectScope.hasCatalogError.value
  ) return false
  const resolution = pageProjectScope.resolution.value
  if (!resolution || !sameResolvedScope(resolution, snapshot.resolution)) return false
  if (target.projectId === null) return canCreatePlatformWorkflow.value
  const project = pageProjectScope.readableProjects.value.find((item) => item.id === target.projectId)
  return Boolean(
    project
      && createTargetIdentity.value?.id === target.projectId
      && createTargetIdentity.value.projectCode === target.projectCode
      && project.projectCode?.trim() === target.projectCode
      && canUseWorkflowPermission(PLATFORM_PERMISSION_WORKFLOW_WRITE, target.projectCode),
  )
}

async function submitCreateWorkflow() {
  if (creating.value || !validateCreateForm()) return
  const target = resolveCreateTarget()
  if (!target) return
  const snapshot = createScopeSnapshot.value
  if (!snapshot) return

  const payload: WorkflowWorkingCopyInput = {
    name: createForm.name.trim(),
    keySlug: createForm.keySlug.trim(),
    projectId: target.projectId,
    projectCode: target.projectCode,
    workflowKind: createForm.workflowKind,
    executionEngine: 'GRAPH_SPEC',
    definitionAuthority: 'USER',
    creationChannel: 'STUDIO',
    description: createForm.description.trim() || null,
    status: 'DRAFT',
    graphSpecJson: '{"schemaVersion":2,"nodes":[],"edges":[],"entryNodeId":"","exitNodeIds":[]}',
    canvasJson: '{"schemaVersion":1,"layoutVersion":1,"nodes":[],"edges":[]}',
  }
  creating.value = true
  try {
    const { data } = await createWorkflow(payload)
    if (!data?.id || !isCreateOperationCurrent(snapshot, target)) return
    createDialogVisible.value = false
    ElMessage.success('Workflow 已创建')
    void router.push(`/workflows/${data.id}/studio`)
  } catch (error) {
    if (isCreateOperationCurrent(snapshot, target)) {
      ElMessage.error(error instanceof Error && error.message ? error.message : '创建 Workflow 失败')
    }
  } finally {
    if (!isUnmounted) creating.value = false
  }
}

function workflowInitial(value?: string | null) {
  const trimmed = (value || 'W').trim()
  return trimmed.slice(0, 1).toUpperCase()
}

function workflowKindKey(value?: string | null) {
  return String(value || 'GENERAL').toUpperCase()
}

function workflowStatusKey(value?: string | null) {
  return String(value || 'DRAFT').toUpperCase()
}

function definitionAuthorityKey(value?: string | null) {
  return String(value || 'USER').toUpperCase()
}

function workflowAvatarClass(type?: string | null) {
  const key = workflowKindKey(type)
  if (key === 'PAGE_ASSISTANT') return 'page-action'
  return 'chat'
}

function workflowKindTagType(type?: string | null) {
  const key = workflowKindKey(type)
  if (key === 'PAGE_ASSISTANT') return 'success'
  return 'info'
}

function effectiveWorkflowKind(workflow: WorkflowWorkingCopy) {
  return workflow.workflowKind || 'GENERAL'
}

function workflowStatusClass(status?: string | null) {
  const key = workflowStatusKey(status)
  if (key === 'ACTIVE') return 'status-active'
  if (key === 'ARCHIVED') return 'status-archived'
  return 'status-draft'
}
</script>

<style scoped lang="scss">
.empty-actions {
  position: relative;
  z-index: 1;
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.workflow-scope-panel {
  display: grid;
  min-width: 0;
  min-height: 260px;
  flex: 1 1 auto;
  place-items: center;
  padding: clamp(24px, 4vw, 48px);
  border-radius: var(--radius-lg);
}

.workflow-list-error {
  display: grid;
  width: min(620px, 100%);
  min-height: 210px;
  place-items: center;
  align-content: center;
  gap: 8px;
  margin: var(--layout-section-gap) var(--layout-panel-padding) 0;
  padding: var(--layout-panel-padding);
  border: 1px dashed color-mix(in srgb, var(--el-color-danger) 28%, var(--border-readable));
  border-radius: var(--radius-lg);
  text-align: center;
  background: color-mix(in srgb, var(--surface-solid-panel) 84%, transparent);

  h3,
  p {
    margin: 0;
  }

  h3,
  p {
    color: var(--el-color-danger);
  }

  p {
    max-width: 520px;
    font-size: 13px;
    line-height: 1.6;
  }
}

.workflow-table-shell {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-height: 0;
  overflow: hidden;
}

.workflow-table {
  width: 100%;
  flex: 1;
  min-height: 0;

  :deep(.el-table__row) { cursor: pointer; }
  :deep(.el-table__empty-text) { width: 100%; line-height: inherit; }
}

.workflow-name-cell {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;

  strong,
  span {
    display: block;
  }

  strong {
    color: var(--text-primary);
    font-weight: 700;
  }

  span {
    margin-top: 4px;
    color: var(--text-muted);
    font-size: 12px;
  }
}

.workflow-name-cell-btn {
  border: none;
  background: transparent;
  padding: 0;
  margin: 0;
  font: inherit;
  text-align: left;
  cursor: pointer;
  width: 100%;
  min-width: 0;

  &:hover strong {
    color: var(--brand-primary);
  }

  &:focus-visible {
    outline: 2px solid rgb(var(--brand-primary-rgb) / 0.35);
    outline-offset: 2px;
    border-radius: 10px;
  }
}

.workflow-avatar {
  display: grid;
  place-items: center;
  flex: 0 0 34px;
  width: 34px;
  height: 34px;
  border-radius: 10px;
  color: var(--text-on-brand);
  font-weight: 800;

  &.chat {
    background: linear-gradient(135deg, var(--brand-hover), var(--brand-active));
  }

  &.sdk-graph {
    background: linear-gradient(135deg, var(--status-success), var(--status-info));
  }

  &.page-action {
    background: linear-gradient(135deg, var(--status-warning), var(--status-warning));
  }
}

.muted {
  color: var(--text-muted);
}

.status-pill {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  font-weight: 600;

  i {
    width: 7px;
    height: 7px;
    border-radius: 999px;
  }
}

.status-active {
  color: var(--status-success);

  i {
    background: var(--status-success);
  }
}

.status-draft {
  color: var(--status-warning);

  i {
    background: var(--status-warning);
  }
}

.status-archived {
  color: var(--text-muted);

  i {
    background: var(--surface-pressed);
  }
}

.workflow-table-empty-state {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 100%;
  min-height: 320px;
  padding: 34px 24px 42px;
  box-sizing: border-box;

  h3 {
    margin: 0 0 8px;
    color: var(--text-primary);
    font-size: 18px;
    font-weight: 700;
  }

  p {
    margin: 0;
    color: var(--text-muted);
    line-height: 1.55;
  }
}

.empty-main {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 28px;
  width: min(760px, 100%);
  min-width: 0;
  margin: 0 auto;
  text-align: left;
}

.empty-body {
  display: flex;
  flex: 1;
  flex-direction: column;
  justify-content: center;
  gap: 18px;
  min-width: 0;
}

.empty-copy {
  flex: 0 0 auto;
  min-width: 0;
}

.empty-illustration {
  display: flex;
  align-items: center;
  justify-content: center;
  flex: 0 0 220px;
  width: 220px;
  height: 160px;
  border-radius: 22px;
  overflow: hidden;
  background: linear-gradient(135deg, rgb(var(--brand-primary-rgb) / 0.08), var(--surface-solid-panel));
}

.empty-illustration-img {
  display: block;
  width: auto;
  height: auto;
  max-width: 100%;
  max-height: 100%;
  object-fit: contain;
}

.create-form {
  display: grid;
  gap: 2px;
}

.create-form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}

.create-project-select {
  width: 100%;
}

.create-project-option {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;

  span {
    overflow: hidden;
    text-overflow: ellipsis;
  }

  small {
    flex: 0 0 auto;
    color: var(--text-muted);
  }
}

.create-project-hint {
  margin: 6px 0 0;
  color: var(--text-muted);
  font-size: 12px;
  line-height: 1.5;
}

.create-project-feedback {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 10px;
}

.create-project-retry {
  flex: 0 0 auto;
  min-height: 24px;
  padding: 0;
  font-size: 12px;
}

@media (max-width: 1200px) {

  .empty-main {
    flex-direction: column;
    align-items: center;
    text-align: center;
  }

  .empty-body {
    align-items: center;
    width: 100%;
  }

  .empty-actions {
    justify-content: center;
  }

  .empty-illustration {
    align-self: center;
    flex: 0 0 auto;
    width: min(100%, 240px);
    height: 200px;
    min-height: 0;
  }
}

@media (max-width: 760px) {
  .create-form-grid {
    grid-template-columns: 1fr;
  }

  .empty-illustration {
    width: min(100%, 240px);
    height: 176px;
  }

}

@media (prefers-reduced-motion: reduce) {
  .workflow-table,
  .workflow-table :deep(.el-scrollbar__wrap) {
    transition: none;
  }
}
</style>
