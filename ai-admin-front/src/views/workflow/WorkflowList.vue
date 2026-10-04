<template>
  <div class="workflow-list-page project-workbench-page workbench-page--list" :class="{ 'is-dark': theme === 'dark' }">
    <CollapsibleHeaderRegion :collapsed="isWorkflowHeaderCollapsed">
      <PageHeader
        variant="overview"
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
          <el-tag type="info" effect="light">GraphSpec 可编排</el-tag>
        </template>
        <template #actions>
          <el-button v-if="projectScoped" @click="backToProject">返回项目</el-button>
          <el-button
            v-if="scopeCanLoadData && canCreateWorkflowInCurrentScope"
            type="primary"
            :icon="Plus"
            @click="openCreateDialog"
          >
            新建 Workflow
          </el-button>
        </template>
      </PageHeader>

      <template #summary>
        <div v-if="workflowListStatus === 'success'" class="metric-strip">
          <template v-for="(metric, index) in metrics" :key="metric.label">
            <div v-if="index > 0" class="metric-divider" aria-hidden="true" />
            <div class="metric-segment">
              <MetricIconBg class="metric-segment-icon" :icon-key="metric.iconKey" :tone="metric.tone" />
              <div class="metric-content">
                <div class="metric-line">
                  <span class="metric-label">{{ metric.label }}</span>
                  <em v-if="metric.delta" class="metric-delta" :class="metric.deltaTone">{{ metric.delta }}</em>
                </div>
                <strong>{{ metric.value }}</strong>
                <small>{{ metric.caption }}</small>
              </div>
            </div>
          </template>
        </div>
      </template>
    </CollapsibleHeaderRegion>

    <section
      v-if="!scopeCanLoadData"
      class="workflow-scope-panel glass-surface-panel"
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

    <el-card v-else class="workflow-card workbench-list-surface" shadow="never">
      <div class="toolbar">
        <el-input
          v-model="keyword"
          class="search-input"
          clearable
          placeholder="搜索 Workflow 名称、Key、描述、项目"
        />
        <el-select v-model="filters.workflowKind" clearable placeholder="Workflow 形态">
          <el-option
            v-for="item in WORKFLOW_KIND_SELECT_OPTIONS"
            :key="item.value"
            :label="item.label"
            :value="item.value"
          />
        </el-select>
        <el-select v-model="filters.status" clearable placeholder="发布状态">
          <el-option
            v-for="item in WORKFLOW_STATUS_SELECT_OPTIONS"
            :key="item.value"
            :label="item.label"
            :value="item.value"
          />
        </el-select>
        <el-button
          class="toolbar-search"
          type="primary"
          :icon="Search"
          :loading="loading"
          @click="searchWorkflows"
        >
          搜索
        </el-button>
      </div>

      <div v-if="workflowListStatus === 'error'" class="workflow-list-error" role="alert" aria-live="polite">
        <h3>Workflow 列表加载失败</h3>
        <p>{{ workflowErrorMessage }}</p>
        <el-button type="primary" :loading="loading" @click="loadWorkflows">重试</el-button>
      </div>

      <div v-else ref="workflowTableShellRef" class="workflow-table-shell">
        <el-table
          v-loading="loading"
          :data="pagedWorkflows"
          row-key="id"
          class="workflow-table"
          :max-height="workflowTableMaxHeight"
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
          <el-table-column label="操作" width="204" fixed="right">
            <template #default="{ row }">
              <el-button
                v-if="canWriteWorkflow(row)"
                size="small"
                type="primary"
                @click="openStudio(row.id)"
              >
                编排
              </el-button>
              <el-button size="small" @click="openVersions(row.id)">版本</el-button>
              <el-button
                v-if="canDeleteWorkflow(row)"
                size="small"
                type="danger"
                plain
                :loading="deletingId === row.id"
                @click="confirmDeleteWorkflow(row)"
              >
                删除
              </el-button>
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

      <div v-if="workflowListStatus === 'success' && filteredWorkflows.length > 0" class="table-footer">
        <span>共 {{ filteredWorkflows.length }} 条</span>
        <el-pagination
          v-model:current-page="currentPage"
          v-model:page-size="pageSize"
          background
          layout="prev, pager, next, sizes"
          :page-sizes="[5, 10, 20, 50]"
          :total="filteredWorkflows.length"
        />
      </div>
    </el-card>

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
        <el-button :disabled="creating" @click="createDialogVisible = false">取消</el-button>
        <el-button
          type="primary"
          :disabled="createSubmitDisabled"
          :loading="creating"
          @click="submitCreateWorkflow"
        >
          创建并进入编排
        </el-button>
      </template>
    </AppDialog>
  </div>
</template>

<script setup lang="ts">
import AppDialog from '@/components/common/AppDialog.vue'
import { computed, nextTick, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Search } from '@element-plus/icons-vue'
import CollapsibleHeaderRegion from '@/components/common/CollapsibleHeaderRegion.vue'
import MetricIconBg from '@/components/common/MetricIconBg.vue'
import PageHeader from '@/components/common/PageHeader.vue'
import ProjectScopeState from '@/components/common/ProjectScopeState.vue'
import { createWorkflow, deleteWorkflow, listWorkflows } from '@/api/workflow'
import type { WorkflowWorkingCopy, WorkflowWorkingCopyInput } from '@/types/workflow'
import type { ScanProject } from '@/types/scanProject'
import { useTheme } from '@/composables/useTheme'
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
const { theme } = useTheme()
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
const deletingId = ref('')
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
  const footerEl = root?.querySelector('.table-footer') as HTMLElement | undefined
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
  deletingId.value = ''
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
  deletingId.value = ''
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
  if (canWriteWorkflow(row)) {
    openStudio(row.id)
    return
  }
  openVersions(row.id)
}

function openVersions(id: string) {
  if (!scopeCanLoadData.value) return
  void router.push(`/workflows/${id}/versions`)
}

function canDeleteWorkflow(row: WorkflowWorkingCopy) {
  return row.deletable === true && canWriteWorkflow(row)
}

async function confirmDeleteWorkflow(row: WorkflowWorkingCopy) {
  if (!scopeCanLoadData.value || !canDeleteWorkflow(row)) return
  const operationScopeKey = pageProjectScope.requestKey.value
  const operationGeneration = workflowDataGeneration
  try {
    await ElMessageBox.confirm(
      `确认删除草稿 Workflow「${row.name}」吗？删除后不可恢复。`,
      '删除确认',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  if (
    !isCurrentWorkflowRequest(operationGeneration, operationScopeKey)
    || !canDeleteWorkflow(row)
  ) return

  deletingId.value = row.id
  try {
    await deleteWorkflow(row.id)
    if (!isCurrentWorkflowRequest(operationGeneration, operationScopeKey)) return
    ElMessage.success('Workflow 已删除')
    void loadWorkflows()
  } catch (error) {
    if (isCurrentWorkflowRequest(operationGeneration, operationScopeKey)) {
      ElMessage.error(error instanceof Error && error.message ? error.message : '删除 Workflow 失败')
    }
  } finally {
    if (
      isCurrentWorkflowRequest(operationGeneration, operationScopeKey)
      && deletingId.value === row.id
    ) deletingId.value = ''
  }
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
.workflow-list-page {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-height: 0;
  min-height: 100%;
  width: 100%;
  min-width: 0;
  box-sizing: border-box;
  background: transparent;
}

.empty-actions {
  position: relative;
  z-index: 1;
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.metric-strip {
  display: flex;
  align-items: stretch;
  width: 100%;
  min-width: 0;
  margin: 0;
  flex-shrink: 0;
  min-height: 104px;
  max-height: 104px;
  padding: 0;
  gap: 0;
  overflow: hidden;
  border: 1px solid rgb(var(--brand-primary-rgb) / 0.16);
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.5);
  box-shadow: 0 18px 42px rgb(var(--brand-primary-rgb) / 0.052);
  backdrop-filter: blur(9px);
}

.metric-divider {
  flex: 0 0 1px;
  align-self: center;
  width: 1px;
  height: 68px;
  background: rgb(var(--brand-primary-rgb) / 0.18);
  border-radius: 1px;
}

.metric-segment {
  position: relative;
  display: flex;
  flex: 1 1 0;
  gap: 18px;
  align-items: center;
  min-width: 0;
  min-height: 104px;
  padding: 18px 22px;

  .metric-label,
  small {
    display: block;
    color: #64748b;
  }

  .metric-label {
    overflow: hidden;
    font-size: 13px;
    font-weight: 500;
    line-height: 17px;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  strong {
    display: block;
    margin: 4px 0 2px;
    color: #0f172a;
    font-size: 25px;
    font-weight: 700;
    line-height: 30px;
  }

  small {
    font-size: 12px;
    line-height: 16px;
  }
}

.metric-segment-icon {
  flex-shrink: 0;
  --metric-icon-bg-size: 54px;
  --metric-icon-bg-radius: 16px;
  --metric-icon-glyph-size: 30px;
  --metric-icon-stroke-width: 1.75;
}

.metric-content {
  flex: 1;
  min-width: 0;
}

.metric-line {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  min-width: 0;
}

.metric-delta {
  display: inline-flex;
  flex: 0 0 auto;
  align-items: center;
  justify-content: center;
  min-width: 72px;
  height: 24px;
  padding: 0 12px;
  border-radius: 12px;
  font-size: 12px;
  font-style: normal;
  font-weight: 500;
  line-height: 18px;
}

.metric-delta.brand {
  color: var(--brand-active);
  background: var(--brand-selected-bg);
}

.metric-delta.success {
  color: #16a34a;
  background: #dcfce7;
}

.metric-delta.warning {
  color: #f97316;
  background: #fff7ed;
}

.workflow-card {
  display: flex;
  flex: 1;
  flex-direction: column;
  width: 100%;
  min-width: 0;
  min-height: 0;
  margin: 0;
  border: 1px solid #eaecf5;
  border-radius: 12px;
  box-shadow: 0 14px 34px rgba(17, 24, 39, 0.045);

  :deep(.el-card__body) {
    display: flex;
    flex: 1;
    flex-direction: column;
    min-height: 0;
    padding: 0;
  }
}

.toolbar {
  display: grid;
  grid-template-columns: minmax(340px, 2.4fr) repeat(2, minmax(170px, 1fr)) minmax(96px, auto);
  align-items: center;
  gap: 12px;
  margin: 28px 28px 0;
  padding: 9px 14px;
  border: 1px solid rgb(var(--brand-primary-rgb) / 0.11);
  border-radius: 12px;
  background: var(--brand-soft-bg, rgba(238, 242, 255, 0.76));
  flex-shrink: 0;

  .search-input {
    width: 100%;
    min-width: 0;
  }

  .el-select,
  .el-input {
    width: 100%;
    min-width: 0;
  }

  :deep(.el-input__wrapper),
  :deep(.el-select__wrapper) {
    min-height: 38px;
    padding: 0 14px;
    border-radius: 9px;
    background: rgba(255, 255, 255, 0.86);
    box-shadow: 0 0 0 1px rgb(var(--brand-primary-rgb) / 0.18) inset;
  }

  :deep(.el-input__inner),
  :deep(.el-select__placeholder),
  :deep(.el-select__selected-item) {
    color: #8290a9;
    font-size: 13px;
    line-height: 17px;
  }

  :deep(.el-input__prefix),
  :deep(.el-input__suffix),
  :deep(.el-select__suffix),
  :deep(.el-select__caret) {
    color: rgb(var(--brand-primary-rgb) / 0.42);
  }
}

.toolbar-search {
  height: 40px;
  min-height: 40px;
  border-radius: 10px;
  color: #fff;
  border-color: transparent;
  background: var(--brand-primary-gradient);
  font-weight: 700;
  box-shadow: 0 10px 18px -10px rgb(var(--brand-primary-rgb) / 0.24);
}

.toolbar-search:hover {
  color: #fff;
  border-color: rgb(var(--brand-primary-rgb) / 0.24);
  background: linear-gradient(135deg, var(--brand-hover), var(--brand-primary));
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
  margin: 22px 28px 0;
  padding: 26px;
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
  margin: 22px 28px 0;
  overflow: hidden;
  border: 1px solid rgb(var(--brand-primary-rgb) / 0.12);
  border-radius: 13px;
  background: rgba(255, 255, 255, 0.68);
}

.workflow-table {
  --el-table-header-bg-color: color-mix(in srgb, var(--brand-selected-bg) 42%, var(--surface-fallback-panel));
  --el-table-row-hover-bg-color: color-mix(in srgb, var(--brand-primary) 8%, var(--surface-fallback-panel));
  --el-table-border-color: rgb(var(--brand-primary-rgb) / 0.1);
  width: 100%;
  flex: 1;
  min-height: 0;
  background: transparent;
  transition: max-height var(--motion-duration-fast) var(--motion-easing-standard);

  :deep(.el-scrollbar__wrap) {
    transition: max-height var(--motion-duration-fast) var(--motion-easing-standard);
  }

  :deep(.el-table__inner-wrapper::before) {
    height: 0;
  }

  :deep(.cell) {
    padding: 0 8px;
  }

  :deep(th.el-table__cell) {
    height: 44px;
    color: var(--brand-active);
    background: color-mix(in srgb, var(--brand-selected-bg) 42%, var(--surface-fallback-panel)) !important;
    font-size: 12px;
    font-weight: 700;
    line-height: 16px;
  }

  :deep(td.el-table__cell) {
    height: 58px;
    color: #334155;
    background: var(--surface-fallback-panel) !important;
    border-bottom-color: rgb(var(--brand-primary-rgb) / 0.1);
  }

  :deep(.el-table__row:hover > td.el-table__cell),
  :deep(.el-table__body tr.hover-row > td.el-table__cell) {
    background: color-mix(in srgb, var(--brand-primary) 8%, var(--surface-fallback-panel)) !important;
  }

  :deep(.el-table-fixed-column--right),
  :deep(.el-table-fixed-column--left) {
    background-color: var(--surface-fallback-panel) !important;
  }

  :deep(th.el-table-fixed-column--right),
  :deep(th.el-table-fixed-column--left) {
    background-color: color-mix(in srgb, var(--brand-selected-bg) 42%, var(--surface-fallback-panel)) !important;
  }

  :deep(td.el-table-fixed-column--right),
  :deep(td.el-table-fixed-column--left) {
    background-color: var(--surface-fallback-panel) !important;
  }

  :deep(.el-table__row:hover > td.el-table-fixed-column--right),
  :deep(.el-table__row:hover > td.el-table-fixed-column--left),
  :deep(.el-table__body tr.hover-row > td.el-table-fixed-column--right),
  :deep(.el-table__body tr.hover-row > td.el-table-fixed-column--left),
  :deep(.el-table__body tr.current-row > td.el-table-fixed-column--right),
  :deep(.el-table__body tr.current-row > td.el-table-fixed-column--left) {
    background-color: color-mix(in srgb, var(--brand-primary) 8%, var(--surface-fallback-panel)) !important;
  }

  :deep(.el-table__fixed-right-patch) {
    background-color: var(--surface-fallback-panel) !important;
  }

  :deep(.el-table__empty-block) {
    width: 100% !important;
    min-height: 320px;
    background: var(--surface-fallback-panel);
  }

  :deep(.el-table__empty-text) {
    width: 100%;
    color: inherit;
    line-height: inherit;
  }
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
    color: #101828;
    font-weight: 700;
  }

  span {
    margin-top: 4px;
    color: #667085;
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
  color: #fff;
  font-weight: 800;

  &.chat {
    background: linear-gradient(135deg, var(--brand-hover), var(--brand-active));
  }

  &.sdk-graph {
    background: linear-gradient(135deg, #22c55e, #14b8a6);
  }

  &.page-action {
    background: linear-gradient(135deg, #f97316, #f59e0b);
  }
}

.muted {
  color: #667085;
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
  color: #16a34a;

  i {
    background: #22c55e;
  }
}

.status-draft {
  color: #d97706;

  i {
    background: #f59e0b;
  }
}

.status-archived {
  color: #64748b;

  i {
    background: #94a3b8;
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
    color: #101828;
    font-size: 18px;
    font-weight: 700;
  }

  p {
    margin: 0;
    color: #667085;
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
  background: linear-gradient(135deg, rgb(var(--brand-primary-rgb) / 0.08), rgba(255, 255, 255, 0.9));
}

.empty-illustration-img {
  display: block;
  width: auto;
  height: auto;
  max-width: 100%;
  max-height: 100%;
  object-fit: contain;
}

.table-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 14px 22px 16px;
  border-top: 1px solid #eef1f7;
  color: #667085;
  font-size: 13px;
  flex-shrink: 0;
  margin-top: auto;

  :deep(.el-pagination.is-background .el-pager li.is-active) {
    background-color: var(--brand-primary);
  }
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

.workflow-list-page.is-dark {
  background: transparent;

  .metric-strip,
  .workflow-card {
    border-color: rgba(255, 255, 255, 0.07);
    background: rgba(255, 255, 255, 0.035);
    box-shadow: 0 14px 34px rgba(0, 0, 0, 0.22);
    backdrop-filter: blur(12px);
  }

  .metric-divider {
    background: rgba(255, 255, 255, 0.08);
  }

  .metric-segment {
    .metric-label,
    small {
      color: #94a3b8;
    }

    strong {
      color: #e2e8f0;
    }

    .metric-delta {
      color: #bae6fd;
      background: rgba(14, 165, 233, 0.14);
    }
  }

  .workflow-card {
    --el-card-bg-color: transparent;

    :deep(.el-card__body) {
      background: transparent;
    }
  }

  .toolbar {
    background: rgba(255, 255, 255, 0.02);
    border-color: rgba(255, 255, 255, 0.06);

    :deep(.el-input__wrapper),
    :deep(.el-select__wrapper) {
      background: rgba(255, 255, 255, 0.035);
      box-shadow: 0 0 0 1px rgba(255, 255, 255, 0.08) inset;
    }

    :deep(.el-input__inner),
    :deep(.el-select__placeholder),
    :deep(.el-select__selected-item) {
      color: #cbd5e1;
    }

    :deep(.el-input__inner::placeholder) {
      color: #64748b;
    }

    :deep(.el-input__prefix),
    :deep(.el-input__suffix),
    :deep(.el-select__suffix),
    :deep(.el-select__caret) {
      color: #94a3b8;
    }

    :deep(.el-button--default) {
      color: #cbd5e1;
      background: rgba(255, 255, 255, 0.04);
      border-color: rgba(255, 255, 255, 0.1);

      &:hover {
        color: #e2e8f0;
        background: rgb(var(--brand-primary-rgb) / 0.12);
        border-color: rgb(var(--brand-primary-rgb) / 0.35);
      }
    }
  }

  .workflow-table-shell {
    border-color: rgba(255, 255, 255, 0.06);
    background: rgba(255, 255, 255, 0.025);
  }

  .workflow-table {
    --el-table-bg-color: transparent;
    --el-table-tr-bg-color: transparent;
    --el-table-header-bg-color: color-mix(in srgb, var(--brand-selected-bg) 42%, var(--surface-fallback-panel));
    --el-table-row-hover-bg-color: color-mix(in srgb, var(--brand-primary) 12%, var(--surface-fallback-panel));
    --el-table-border-color: rgba(255, 255, 255, 0.06);
    --el-table-text-color: #cbd5e1;
    --el-table-header-text-color: #94a3b8;
    background: transparent;

    :deep(.el-table__inner-wrapper::before),
    :deep(.el-table__border-left-patch),
    :deep(.el-table__border-bottom-patch) {
      background-color: rgba(255, 255, 255, 0.06);
    }

    :deep(th.el-table__cell) {
      color: #94a3b8;
      background: color-mix(in srgb, var(--brand-selected-bg) 42%, var(--surface-fallback-panel)) !important;
    }

    :deep(td.el-table__cell) {
      color: #cbd5e1;
      background: var(--surface-fallback-panel) !important;
      border-bottom-color: rgba(255, 255, 255, 0.06);
    }

    :deep(.el-table__row:hover > td.el-table__cell),
    :deep(.el-table__body tr.hover-row > td.el-table__cell) {
      background: color-mix(in srgb, var(--brand-primary) 12%, var(--surface-fallback-panel)) !important;
    }

    :deep(.el-table__inner-wrapper),
    :deep(.el-table__header-wrapper),
    :deep(.el-table__body-wrapper) {
      background: transparent;
    }

    :deep(.el-table-fixed-column--right),
    :deep(.el-table-fixed-column--left) {
      background-color: var(--surface-fallback-panel) !important;
    }

    :deep(th.el-table-fixed-column--right),
    :deep(th.el-table-fixed-column--left) {
      background-color: color-mix(in srgb, var(--brand-selected-bg) 42%, var(--surface-fallback-panel)) !important;
    }

    :deep(td.el-table-fixed-column--right),
    :deep(td.el-table-fixed-column--left) {
      background-color: var(--surface-fallback-panel) !important;
    }

    :deep(.el-table__row:hover > td.el-table-fixed-column--right),
    :deep(.el-table__row:hover > td.el-table-fixed-column--left),
    :deep(.el-table__body tr.hover-row > td.el-table-fixed-column--right),
    :deep(.el-table__body tr.hover-row > td.el-table-fixed-column--left),
    :deep(.el-table__body tr.current-row > td.el-table-fixed-column--right),
    :deep(.el-table__body tr.current-row > td.el-table-fixed-column--left) {
      background-color: color-mix(in srgb, var(--brand-primary) 12%, var(--surface-fallback-panel)) !important;
    }

    :deep(.el-table__fixed-right-patch) {
      background-color: var(--surface-fallback-panel) !important;
    }

    :deep(.el-tag.el-tag--light) {
      border-width: 1px;
      border-style: solid;
    }
  }

  .workflow-name-cell {
    strong {
      color: #e2e8f0;
    }

    span {
      color: #64748b;
    }
  }

  .workflow-name-cell-btn:hover strong {
    color: var(--brand-disabled);
  }

  .muted {
    color: #94a3b8;
  }

  .empty-illustration {
    background: linear-gradient(135deg, rgb(var(--brand-primary-rgb) / 0.22), rgb(var(--brand-hover-rgb) / 0.08));
  }

  .workflow-table-empty-state {
    h3 {
      color: #e2e8f0;
    }

    p {
      color: #94a3b8;
    }
  }

  .workflow-list-error {
    background: color-mix(in srgb, var(--surface-fallback-panel) 84%, transparent);
  }

  .table-footer {
    color: #94a3b8;
    border-top-color: rgba(255, 255, 255, 0.06);

    :deep(.el-pagination.is-background .btn-prev),
    :deep(.el-pagination.is-background .btn-next),
    :deep(.el-pagination.is-background .el-pager li) {
      color: #94a3b8;
      background: rgba(255, 255, 255, 0.035);
    }

    :deep(.el-pagination.is-background .btn-prev:hover),
    :deep(.el-pagination.is-background .btn-next:hover),
    :deep(.el-pagination.is-background .el-pager li:hover) {
      color: var(--brand-selected-bg);
      background: rgb(var(--brand-primary-rgb) / 0.12);
    }

    :deep(.el-pagination.is-background .el-pager li.is-active) {
      color: #fff;
      background: linear-gradient(135deg, var(--brand-primary), var(--brand-hover));
    }

    :deep(.el-pagination.is-background .btn-prev.is-disabled),
    :deep(.el-pagination.is-background .btn-next.is-disabled),
    :deep(.el-pagination.is-background .el-pager li.is-disabled) {
      color: rgba(148, 163, 184, 0.35);
      background: rgba(255, 255, 255, 0.02);
    }
  }
}

@media (max-width: 1200px) {
  .metric-strip {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    max-height: none;
  }

  .metric-divider {
    display: none;
  }

  .metric-segment {
    min-height: 92px;
    padding: 14px 16px;
    gap: 12px;
  }

  .toolbar {
    grid-template-columns: minmax(0, 2fr) repeat(2, minmax(0, 1fr));
  }

  .toolbar-search {
    grid-column: 1 / -1;
    justify-self: end;
    min-width: 96px;
  }

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

@media (max-width: 1400px) {
  .metric-line {
    justify-content: flex-start;
    gap: 0;
  }

  .metric-label {
    overflow: visible;
    text-overflow: clip;
    white-space: normal;
  }

  .metric-delta {
    display: none;
  }
}

@media (max-width: 760px) {
  .metric-segment {
    min-height: 84px;
  }

  .toolbar,
  .create-form-grid {
    grid-template-columns: 1fr;
  }

  .empty-illustration {
    width: min(100%, 240px);
    height: 176px;
  }

  .table-footer {
    align-items: flex-start;
    flex-direction: column;
  }
}

@media (prefers-reduced-motion: reduce) {
  .workflow-table,
  .workflow-table :deep(.el-scrollbar__wrap) {
    transition: none;
  }
}
</style>
