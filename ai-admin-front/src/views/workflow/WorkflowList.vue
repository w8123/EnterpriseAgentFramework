<template>
  <div class="workflow-list-page" :class="{ 'is-dark': theme === 'dark' }">
    <div class="page-hero">
      <div class="hero-copy">
        <span class="hero-accent" aria-hidden="true" />
        <div class="hero-text">
          <h1>{{ pageTitle }}</h1>
          <p>{{ pageDescription }}</p>
          <div class="hero-tags" aria-label="Workflow 编排状态">
            <span class="tag-brand">{{ projectScoped ? '项目视图' : '全量视图' }}</span>
            <span class="tag-success">当前 {{ workflows.length }} 个 Workflow</span>
            <span class="tag-info">GraphSpec 可编排</span>
          </div>
        </div>
      </div>
      <div class="hero-actions">
        <el-button v-if="projectScoped" @click="backToProject">返回项目</el-button>
        <el-button v-if="projectScoped" @click="openGlobalWorkflows">全部 Workflow</el-button>
        <el-button type="primary" size="large" class="primary-action" :icon="Plus" @click="openCreateDialog">
          新建 Workflow
        </el-button>
      </div>
    </div>

    <div class="metric-strip">
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

    <el-card class="workflow-card" :class="{ 'has-context-filter': projectScoped }" shadow="never">
      <div v-if="projectScoped" class="context-filter">
        当前仅展示项目 {{ filters.projectCode }} 下的 Workflow。
        <el-button link type="primary" @click="openGlobalWorkflows">查看全部 Workflow</el-button>
      </div>

      <div class="toolbar">
        <el-input
          v-model="keyword"
          class="search-input"
          clearable
          :prefix-icon="Search"
          placeholder="搜索 Workflow 名称、Key、描述、项目"
        />
        <el-input
          v-model="filters.projectCode"
          clearable
          :disabled="projectScoped"
          placeholder="项目编码"
          @change="searchWorkflows"
          @clear="searchWorkflows"
          @keyup.enter="searchWorkflows"
        />
        <el-select v-model="filters.workflowType" clearable placeholder="Workflow 类型" @change="searchWorkflows">
          <el-option
            v-for="item in WORKFLOW_TYPE_SELECT_OPTIONS"
            :key="item.value"
            :label="item.label"
            :value="item.value"
          />
        </el-select>
        <el-select v-model="filters.status" clearable placeholder="发布状态" @change="searchWorkflows">
          <el-option
            v-for="item in WORKFLOW_STATUS_SELECT_OPTIONS"
            :key="item.value"
            :label="item.label"
            :value="item.value"
          />
        </el-select>
        <el-button class="toolbar-reset" @click="resetFilters">重置</el-button>
        <el-button class="toolbar-refresh" :icon="Refresh" :loading="loading" @click="loadWorkflows" />
      </div>

      <div class="workflow-table-shell">
        <el-table
          v-loading="loading"
          :data="pagedWorkflows"
          row-key="id"
          class="workflow-table"
          :max-height="workflowTableMaxHeight"
        >
          <el-table-column label="Workflow 名称" min-width="260">
            <template #default="{ row }">
              <button type="button" class="workflow-name-cell workflow-name-cell-btn" @click="openStudio(row.id)">
                <div class="workflow-avatar" :class="workflowAvatarClass(row.workflowType)">
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
          <el-table-column label="类型" width="130">
            <template #default="{ row }">
              <el-tag :type="workflowTypeTagType(row.workflowType)" effect="light">
                {{ formatWorkflowTypeLabel(row.workflowType) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="运行时" width="130">
            <template #default="{ row }">
              <span class="muted">{{ formatRuntimeTypeLabel(row.runtimeType) }}</span>
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
          <el-table-column label="管理来源" width="130">
            <template #default="{ row }">
              <span class="muted">{{ formatWorkflowManagedByLabel(row.managedBy) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="204" fixed="right">
            <template #default="{ row }">
              <el-button size="small" type="primary" @click="openStudio(row.id)">编排</el-button>
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
            <div v-if="!loading" class="workflow-table-empty-state">
              <div class="empty-main">
                <div class="empty-illustration" aria-hidden="true">
                  <img class="empty-illustration-img" src="/智能化.svg" alt="" />
                </div>
                <div class="empty-body">
                  <div class="empty-copy">
                    <h3>还没有匹配的 Workflow</h3>
                    <p>可以调整项目、类型或状态筛选，也可以新建一个可执行图资产。</p>
                  </div>
                  <div class="empty-actions">
                    <el-button type="primary" :icon="Plus" @click="openCreateDialog">新建 Workflow</el-button>
                    <el-button @click="resetFilters">重置筛选</el-button>
                  </div>
                </div>
              </div>
            </div>
          </template>
        </el-table>
      </div>

      <div v-if="filteredWorkflows.length > 0" class="table-footer">
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

    <el-dialog
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
          <el-input
            v-model="createForm.projectCode"
            clearable
            :disabled="projectScoped"
            placeholder="projectCode"
          />
        </el-form-item>
        <div class="create-form-grid">
          <el-form-item label="类型">
            <el-select v-model="createForm.workflowType">
              <el-option
                v-for="item in WORKFLOW_TYPE_SELECT_OPTIONS"
                :key="item.value"
                :label="item.label"
                :value="item.value"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="运行时">
            <el-select v-model="createForm.runtimeType">
              <el-option label="LangGraph4j 工作流" value="LANGGRAPH4J" />
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
        <el-button @click="createDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="submitCreateWorkflow">
          创建并进入编排
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  Plus,
  Refresh,
  Search,
} from '@element-plus/icons-vue'
import MetricIconBg from '@/components/common/MetricIconBg.vue'
import { createWorkflow, deleteWorkflow, listWorkflows } from '@/api/workflow'
import type { WorkflowDefinition } from '@/types/workflow'
import { useTheme } from '@/composables/useTheme'
import { formatRuntimeTypeLabel } from '@/utils/registryLabels'
import {
  WORKFLOW_STATUS_SELECT_OPTIONS,
  WORKFLOW_TYPE_SELECT_OPTIONS,
  formatWorkflowManagedByLabel,
  formatWorkflowStatusLabel,
  formatWorkflowTypeLabel,
} from '@/utils/workflowLabels'

const route = useRoute()
const router = useRouter()
const { theme } = useTheme()

const loading = ref(false)
const creating = ref(false)
const deletingId = ref('')
const createDialogVisible = ref(false)
const workflows = ref<WorkflowDefinition[]>([])
const keyword = ref('')
const currentPage = ref(1)
const pageSize = ref(10)
const workflowTableMaxHeight = ref(480)
const filters = reactive({
  projectCode: String(route.query.projectCode || ''),
  workflowType: '',
  status: '',
})
const createForm = reactive({
  name: '',
  keySlug: '',
  projectCode: '',
  workflowType: 'CHAT',
  runtimeType: 'LANGGRAPH4J',
  description: '',
})

let mainContentScrollEl: HTMLElement | null = null
let tableHeightRaf = 0

const routeProjectCode = computed(() => String(route.query.projectCode || '').trim())
const projectScoped = computed(() => !!routeProjectCode.value)
const pageTitle = computed(() => (projectScoped.value ? '项目 Workflow' : 'Workflow 编排'))
const pageDescription = computed(() =>
  projectScoped.value
    ? '当前项目下的可执行 Workflow 资产。'
    : '面向页面动作、SDK 图和对话流的可执行图资产。',
)

const filteredWorkflows = computed(() => {
  const text = keyword.value.trim().toLowerCase()
  if (!text) return workflows.value
  return workflows.value.filter((workflow) => {
    return [
      workflow.name,
      workflow.keySlug,
      workflow.description,
      workflow.projectCode,
      formatWorkflowTypeLabel(workflow.workflowType),
      formatWorkflowStatusLabel(workflow.status),
      formatWorkflowManagedByLabel(workflow.managedBy),
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
  const pageActionCount = workflows.value.filter((workflow) => workflowTypeKey(workflow.workflowType) === 'PAGE_ACTION').length
  const sdkGraphCount = workflows.value.filter((workflow) => workflowTypeKey(workflow.workflowType) === 'SDK_GRAPH').length
  const projectCount = new Set(workflows.value.map((workflow) => workflow.projectCode).filter(Boolean)).size
  const manualCount = workflows.value.filter((workflow) => workflowManagedByKey(workflow.managedBy) === 'MANUAL').length
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
      label: '页面动作 / SDK 图',
      value: `${pageActionCount} / ${sdkGraphCount}`,
      caption: '业务页面与能力图',
      delta: pageActionCount || sdkGraphCount ? 'GraphSpec 管理' : '',
      deltaTone: 'brand',
      iconKey: 'workflow-sources',
      tone: 'info',
    },
    {
      label: '手动编排',
      value: manualCount,
      caption: '人工创建与维护',
      delta: total ? `${Math.max(total - manualCount, 0)} 个托管` : '',
      deltaTone: 'warning',
      iconKey: 'workflow-manual',
      tone: 'orange',
    },
  ]
})

onMounted(() => {
  loadWorkflows().finally(() => {
    nextTick(scheduleUpdateWorkflowTableMaxHeight)
  })
  mainContentScrollEl = document.querySelector('.main-layout .main-content') as HTMLElement | null
  mainContentScrollEl?.addEventListener('scroll', scheduleUpdateWorkflowTableMaxHeight, { passive: true })
  window.addEventListener('resize', scheduleUpdateWorkflowTableMaxHeight)
})

onUnmounted(() => {
  mainContentScrollEl?.removeEventListener('scroll', scheduleUpdateWorkflowTableMaxHeight)
  window.removeEventListener('resize', scheduleUpdateWorkflowTableMaxHeight)
})

watch(
  () => route.query.projectCode,
  (value) => {
    filters.projectCode = String(value || '')
    currentPage.value = 1
    void loadWorkflows()
  },
)

watch([keyword, pageSize], () => {
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

function scheduleUpdateWorkflowTableMaxHeight() {
  if (typeof window === 'undefined') return
  if (tableHeightRaf) return
  tableHeightRaf = requestAnimationFrame(() => {
    tableHeightRaf = 0
    updateWorkflowTableMaxHeight()
  })
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
  const h = bottomLimit - tableTop
  workflowTableMaxHeight.value = Math.max(260, Math.floor(h))
}

async function loadWorkflows() {
  loading.value = true
  try {
    const { data } = await listWorkflows({
      projectCode: filters.projectCode || undefined,
      workflowType: filters.workflowType || undefined,
      status: filters.status || undefined,
    })
    workflows.value = Array.isArray(data) ? data : []
  } finally {
    loading.value = false
    nextTick(scheduleUpdateWorkflowTableMaxHeight)
  }
}

function searchWorkflows() {
  currentPage.value = 1
  void loadWorkflows()
}

function resetFilters() {
  keyword.value = ''
  filters.projectCode = routeProjectCode.value || ''
  filters.workflowType = ''
  filters.status = ''
  currentPage.value = 1
  void loadWorkflows()
}

function openStudio(id: string) {
  router.push(`/workflows/${id}/studio`)
}

function openVersions(id: string) {
  router.push(`/workflows/${id}/versions`)
}

function canDeleteWorkflow(row: WorkflowDefinition) {
  return row.deletable === true
}

async function confirmDeleteWorkflow(row: WorkflowDefinition) {
  try {
    await ElMessageBox.confirm(
      `确认删除草稿 Workflow「${row.name}」吗？删除后不可恢复。`,
      '删除确认',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  deletingId.value = row.id
  try {
    await deleteWorkflow(row.id)
    ElMessage.success('Workflow 已删除')
    await loadWorkflows()
  } catch {
    // 错误提示由 request 拦截器处理
  } finally {
    deletingId.value = ''
  }
}

function backToProject() {
  if (!routeProjectCode.value) return
  router.push({ name: 'RegistryProjectDetail', params: { projectCode: routeProjectCode.value } })
}

function openGlobalWorkflows() {
  filters.projectCode = ''
  router.push({ name: 'WorkflowList' })
}

function openCreateDialog() {
  createForm.name = ''
  createForm.keySlug = ''
  createForm.projectCode = routeProjectCode.value || filters.projectCode || ''
  createForm.workflowType = 'CHAT'
  createForm.runtimeType = 'LANGGRAPH4J'
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

async function submitCreateWorkflow() {
  if (!validateCreateForm()) return
  creating.value = true
  try {
    const { data } = await createWorkflow({
      name: createForm.name.trim(),
      keySlug: createForm.keySlug.trim(),
      projectCode: createForm.projectCode.trim() || null,
      workflowType: createForm.workflowType,
      runtimeType: createForm.runtimeType,
      description: createForm.description.trim() || null,
      status: 'DRAFT',
      managedBy: 'MANUAL',
      graphSpecJson: '{"nodes":[],"edges":[]}',
      canvasJson: '{"version":2,"nodes":[],"edges":[]}',
    })
    createDialogVisible.value = false
    ElMessage.success('Workflow 已创建')
    router.push(`/workflows/${data.id}/studio`)
  } catch (err) {
    ElMessage.error((err as Error).message || '创建 Workflow 失败')
  } finally {
    creating.value = false
  }
}

function workflowInitial(value?: string | null) {
  const trimmed = (value || 'W').trim()
  return trimmed.slice(0, 1).toUpperCase()
}

function workflowTypeKey(value?: string | null) {
  return String(value || 'CHAT').toUpperCase()
}

function workflowStatusKey(value?: string | null) {
  return String(value || 'DRAFT').toUpperCase()
}

function workflowManagedByKey(value?: string | null) {
  return String(value || 'MANUAL').toUpperCase()
}

function workflowAvatarClass(type?: string | null) {
  const key = workflowTypeKey(type)
  if (key === 'PAGE_ACTION') return 'page-action'
  if (key === 'SDK_GRAPH') return 'sdk-graph'
  return 'chat'
}

function workflowTypeTagType(type?: string | null) {
  const key = workflowTypeKey(type)
  if (key === 'PAGE_ACTION') return 'success'
  if (key === 'SDK_GRAPH') return 'warning'
  return 'info'
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
  gap: 10px;
  min-height: 0;
  height: 100%;
  padding: 24px 28px 14px;
  width: 100%;
  min-width: 0;
  box-sizing: border-box;
  background: transparent;
}

.page-hero {
  display: flex;
  justify-content: space-between;
  gap: 24px;
  align-items: center;
  width: 100%;
  min-width: 0;
  margin: 0;
  flex-shrink: 0;
  min-height: 120px;
  padding: 26px 28px;
  position: relative;
  overflow: hidden;
  border: 1px solid rgb(var(--brand-primary-rgb) / 0.18);
  border-radius: 16px;
  background:
    linear-gradient(90deg, rgba(255, 255, 255, 0.92) 0%, rgba(255, 255, 255, 0.72) 52%, rgb(var(--brand-selected-rgb) / 0.16) 100%),
    var(--brand-soft-bg, rgba(255, 255, 255, 0.58));
  box-shadow: 0 18px 42px rgb(var(--brand-primary-rgb) / 0.052);

  &::before {
    content: '';
    position: absolute;
    inset: 0;
    pointer-events: none;
    background:
      linear-gradient(90deg, rgb(var(--brand-primary-rgb) / 0.18) 0 3px, transparent 3px),
      radial-gradient(circle at 78% 18%, rgb(var(--brand-primary-rgb) / 0.12), transparent 28%);
    opacity: 0.72;
  }

  h1 {
    margin: 0 0 8px;
    color: #111827;
    font-size: 26px;
    font-weight: 800;
    line-height: 1.2;
  }

  p {
    margin: 0;
    color: #667085;
    font-size: 14px;
  }
}

.hero-copy {
  position: relative;
  z-index: 1;
  display: flex;
  align-items: flex-start;
  gap: 18px;
  min-width: 0;
}

.hero-accent {
  flex: 0 0 4px;
  width: 4px;
  height: 28px;
  margin-top: 4px;
  border-radius: 999px;
  background: var(--brand-primary);
  box-shadow: 0 0 16px rgb(var(--brand-primary-rgb) / 0.25);
}

.hero-text {
  min-width: 0;
}

.hero-tags {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  margin-top: 10px;

  span {
    display: inline-flex;
    align-items: center;
    height: 24px;
    padding: 0 12px;
    border-radius: 999px;
    font-size: 12px;
    font-weight: 700;
  }
}

.tag-brand {
  color: var(--brand-active);
  background: var(--brand-selected-bg);
}

.tag-success {
  color: #16a34a;
  background: #dcfce7;
}

.tag-info {
  color: var(--brand-primary);
  background: rgb(var(--brand-primary-rgb) / 0.1);
}

.hero-actions,
.empty-actions {
  position: relative;
  z-index: 1;
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.primary-action {
  --el-button-bg-color: var(--brand-primary);
  --el-button-border-color: var(--brand-primary);
  --el-button-hover-bg-color: var(--brand-hover);
  --el-button-hover-border-color: var(--brand-hover);
  min-width: 142px;
  border-radius: 8px;
  box-shadow: 0 10px 20px rgb(var(--brand-primary-rgb) / 0.22);
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

.workflow-card.has-context-filter {
  .toolbar {
    margin-top: 12px;
  }
}

.toolbar {
  display: grid;
  grid-template-columns: minmax(340px, 2.4fr) minmax(170px, 0.95fr) repeat(2, minmax(170px, 1fr)) 74px 48px;
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

.toolbar-reset,
.toolbar-refresh {
  height: 40px;
  min-height: 40px;
  border: 1px solid rgb(var(--brand-primary-rgb) / 0.18);
  border-radius: 10px;
  font-weight: 700;
  box-shadow: 0 6px 16px -8px rgba(100, 116, 139, 0.08);
}

.toolbar-reset {
  color: #334155;
  background: rgba(255, 255, 255, 0.86);
}

.toolbar-refresh {
  color: var(--brand-active);
  background: rgba(255, 255, 255, 0.86);
}

.toolbar-reset:hover,
.toolbar-refresh:hover {
  color: var(--brand-active);
  border-color: rgb(var(--brand-primary-rgb) / 0.24);
  background: #f8fbff;
}

.context-filter {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 28px 28px 0;
  padding: 9px 14px;
  border: 1px solid rgb(var(--brand-primary-rgb) / 0.12);
  border-radius: 10px;
  color: var(--brand-primary);
  background: rgb(var(--brand-selected-rgb) / 0.46);
  font-size: 13px;
  flex-shrink: 0;
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
  --el-table-header-bg-color: color-mix(in srgb, var(--brand-selected-bg) 42%, #f1f5fb);
  --el-table-row-hover-bg-color: rgba(255, 255, 255, 0.72);
  --el-table-border-color: rgb(var(--brand-primary-rgb) / 0.1);
  width: 100%;
  flex: 1;
  min-height: 0;
  background: transparent;

  :deep(.el-table__inner-wrapper::before) {
    height: 0;
  }

  :deep(.cell) {
    padding: 0 8px;
  }

  :deep(th.el-table__cell) {
    height: 44px;
    color: var(--brand-active);
    background: color-mix(in srgb, var(--brand-selected-bg) 42%, #f1f5fb) !important;
    font-size: 12px;
    font-weight: 700;
    line-height: 16px;
  }

  :deep(td.el-table__cell) {
    height: 58px;
    color: #334155;
    background: rgba(255, 255, 255, 0.72) !important;
    border-bottom-color: rgb(var(--brand-primary-rgb) / 0.1);
  }

  :deep(.el-table__row:hover > td.el-table__cell),
  :deep(.el-table__body tr.hover-row > td.el-table__cell) {
    background: rgba(255, 255, 255, 0.72) !important;
  }

  :deep(.el-table-fixed-column--right),
  :deep(.el-table-fixed-column--left) {
    background-color: rgba(255, 255, 255, 0.88) !important;
  }

  :deep(th.el-table-fixed-column--right),
  :deep(th.el-table-fixed-column--left) {
    background-color: rgb(var(--brand-selected-rgb) / 0.34) !important;
  }

  :deep(td.el-table-fixed-column--right),
  :deep(td.el-table-fixed-column--left) {
    background-color: rgba(255, 255, 255, 0.88) !important;
  }

  :deep(.el-table__row:hover > td.el-table-fixed-column--right),
  :deep(.el-table__row:hover > td.el-table-fixed-column--left),
  :deep(.el-table__body tr.hover-row > td.el-table-fixed-column--right),
  :deep(.el-table__body tr.hover-row > td.el-table-fixed-column--left),
  :deep(.el-table__body tr.current-row > td.el-table-fixed-column--right),
  :deep(.el-table__body tr.current-row > td.el-table-fixed-column--left) {
    background-color: rgba(255, 255, 255, 0.88) !important;
  }

  :deep(.el-table__fixed-right-patch) {
    background-color: rgba(255, 255, 255, 0.88) !important;
  }

  :deep(.el-table__empty-block) {
    width: 100% !important;
    min-height: 320px;
    background: rgba(255, 255, 255, 0.46);
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

.workflow-list-page.is-dark {
  background: transparent;

  .page-hero {
    h1 {
      color: #e2e8f0;
    }

    p {
      color: #94a3b8;
    }
  }

  .primary-action {
    box-shadow: 0 10px 22px rgb(var(--brand-primary-rgb) / 0.28);
  }

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

  .context-filter {
    color: var(--brand-selected-bg);
    background: rgb(var(--brand-primary-rgb) / 0.12);
    border-color: rgb(var(--brand-primary-rgb) / 0.22);
  }

  .workflow-table-shell {
    border-color: rgba(255, 255, 255, 0.06);
    background: rgba(255, 255, 255, 0.025);
  }

  .workflow-table {
    --el-table-bg-color: transparent;
    --el-table-tr-bg-color: transparent;
    --el-table-header-bg-color: rgba(255, 255, 255, 0.045);
    --el-table-row-hover-bg-color: rgb(var(--brand-primary-rgb) / 0.08);
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
      background: rgba(255, 255, 255, 0.045) !important;
    }

    :deep(td.el-table__cell) {
      color: #cbd5e1;
      background: rgba(255, 255, 255, 0.015) !important;
      border-bottom-color: rgba(255, 255, 255, 0.06);
    }

    :deep(.el-table__row:hover > td.el-table__cell),
    :deep(.el-table__body tr.hover-row > td.el-table__cell) {
      background: rgb(var(--brand-primary-rgb) / 0.08) !important;
    }

    :deep(.el-table__inner-wrapper),
    :deep(.el-table__header-wrapper),
    :deep(.el-table__body-wrapper) {
      background: transparent;
    }

    :deep(.el-table-fixed-column--right),
    :deep(.el-table-fixed-column--left) {
      background-color: var(--bg-secondary) !important;
    }

    :deep(th.el-table-fixed-column--right),
    :deep(th.el-table-fixed-column--left) {
      background-color: #1c1c2a !important;
    }

    :deep(td.el-table-fixed-column--right),
    :deep(td.el-table-fixed-column--left) {
      background-color: var(--bg-secondary) !important;
    }

    :deep(.el-table__row:hover > td.el-table-fixed-column--right),
    :deep(.el-table__row:hover > td.el-table-fixed-column--left),
    :deep(.el-table__body tr.hover-row > td.el-table-fixed-column--right),
    :deep(.el-table__body tr.hover-row > td.el-table-fixed-column--left),
    :deep(.el-table__body tr.current-row > td.el-table-fixed-column--right),
    :deep(.el-table__body tr.current-row > td.el-table-fixed-column--left) {
      background-color: #252538 !important;
    }

    :deep(.el-table__fixed-right-patch) {
      background-color: var(--bg-secondary) !important;
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

:global(.main-layout.registry-shell .main-content:has(.workflow-list-page)) {
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

@media (max-width: 1200px) {
  .metric-strip {
    flex-direction: column;
    max-height: none;
  }

  .metric-divider {
    width: auto;
    height: 1px;
    align-self: stretch;
    margin: 0 22px;
  }

  .metric-segment {
    min-height: 92px;
  }

  .page-hero {
    align-items: flex-start;
    flex-direction: column;
  }

  .toolbar {
    grid-template-columns: minmax(240px, 1fr) repeat(2, minmax(150px, 1fr));
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

@media (max-width: 760px) {
  .workflow-list-page {
    padding: 18px 14px 24px;
  }

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
</style>
