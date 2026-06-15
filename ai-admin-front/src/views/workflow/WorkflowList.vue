<template>
  <div class="workflow-list">
    <header class="page-header">
      <div>
        <h1>{{ pageTitle }}</h1>
        <p>{{ pageDescription }}</p>
      </div>
      <div class="page-actions">
        <el-button
          v-if="projectScoped"
          @click="backToProject"
        >
          Back To Project
        </el-button>
        <el-button
          v-if="projectScoped"
          @click="openGlobalWorkflows"
        >
          All Workflows
        </el-button>
        <el-button type="primary" @click="openCreateDialog">New Workflow</el-button>
        <el-button :icon="Refresh" :loading="loading" @click="loadWorkflows">Reload</el-button>
      </div>
    </header>

    <el-alert
      v-if="projectScoped"
      class="context-alert"
      type="info"
      :closable="false"
      show-icon
    >
      <template #title>
        Project scope: {{ filters.projectCode }}
      </template>
    </el-alert>

    <el-form class="filters" :inline="true">
      <el-form-item label="Project">
        <el-input v-model="filters.projectCode" clearable placeholder="projectCode" />
      </el-form-item>
      <el-form-item label="Type">
        <el-select v-model="filters.workflowType" clearable placeholder="Any" style="width: 150px">
          <el-option label="Chat" value="CHAT" />
          <el-option label="SDK Graph" value="SDK_GRAPH" />
          <el-option label="Page Action" value="PAGE_ACTION" />
        </el-select>
      </el-form-item>
      <el-form-item label="Status">
        <el-select v-model="filters.status" clearable placeholder="Any" style="width: 140px">
          <el-option label="Draft" value="DRAFT" />
          <el-option label="Active" value="ACTIVE" />
          <el-option label="Archived" value="ARCHIVED" />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" :icon="Search" @click="loadWorkflows">Search</el-button>
      </el-form-item>
    </el-form>

    <el-table :data="workflows" v-loading="loading" stripe>
      <el-table-column prop="name" label="Name" min-width="220">
        <template #default="{ row }">
          <el-button text type="primary" @click="openStudio(row.id)">{{ row.name }}</el-button>
          <div class="muted">{{ row.keySlug }}</div>
        </template>
      </el-table-column>
      <el-table-column prop="projectCode" label="Project" min-width="140" />
      <el-table-column prop="workflowType" label="Type" min-width="130" />
      <el-table-column prop="runtimeType" label="Runtime" min-width="140" />
      <el-table-column prop="status" label="Status" width="120">
        <template #default="{ row }">
          <el-tag size="small" :type="row.status === 'ACTIVE' ? 'success' : 'warning'">
            {{ row.status || 'DRAFT' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="managedBy" label="Managed By" min-width="140" />
      <el-table-column label="Actions" width="190" fixed="right">
        <template #default="{ row }">
          <el-button size="small" type="primary" @click="openStudio(row.id)">Studio</el-button>
          <el-button size="small" @click="openVersions(row.id)">Versions</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog
      v-model="createDialogVisible"
      title="New Workflow"
      width="560px"
      destroy-on-close
    >
      <el-form label-position="top" class="create-form">
        <el-form-item label="Name" required>
          <el-input
            v-model="createForm.name"
            maxlength="80"
            show-word-limit
            placeholder="Orders page assistant"
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
        <el-form-item label="Project">
          <el-input
            v-model="createForm.projectCode"
            clearable
            :disabled="projectScoped"
            placeholder="projectCode"
          />
        </el-form-item>
        <div class="create-form-grid">
          <el-form-item label="Type">
            <el-select v-model="createForm.workflowType">
              <el-option label="Chat" value="CHAT" />
              <el-option label="SDK Graph" value="SDK_GRAPH" />
              <el-option label="Page Action" value="PAGE_ACTION" />
            </el-select>
          </el-form-item>
          <el-form-item label="Runtime">
            <el-select v-model="createForm.runtimeType">
              <el-option label="LangGraph4j" value="LANGGRAPH4J" />
            </el-select>
          </el-form-item>
        </div>
        <el-form-item label="Description">
          <el-input
            v-model="createForm.description"
            type="textarea"
            :rows="3"
            maxlength="240"
            show-word-limit
            placeholder="Optional"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createDialogVisible = false">Cancel</el-button>
        <el-button type="primary" :loading="creating" @click="submitCreateWorkflow">
          Create And Open Studio
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Refresh, Search } from '@element-plus/icons-vue'
import { createWorkflow, listWorkflows } from '@/api/workflow'
import type { WorkflowDefinition } from '@/types/workflow'

const route = useRoute()
const router = useRouter()

const loading = ref(false)
const creating = ref(false)
const createDialogVisible = ref(false)
const workflows = ref<WorkflowDefinition[]>([])
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

const routeProjectCode = computed(() => String(route.query.projectCode || '').trim())
const projectScoped = computed(() => !!routeProjectCode.value)
const pageTitle = computed(() => (projectScoped.value ? 'Project Workflows' : 'Workflows'))
const pageDescription = computed(() =>
  projectScoped.value
    ? 'Executable Workflow assets for the current project.'
    : 'Executable graph assets for page actions, SDK graphs, and chat flows.',
)

onMounted(loadWorkflows)

watch(
  () => route.query.projectCode,
  (value) => {
    filters.projectCode = String(value || '')
    void loadWorkflows()
  },
)

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
  }
}

function openStudio(id: string) {
  router.push(`/workflows/${id}/studio`)
}

function openVersions(id: string) {
  router.push(`/workflows/${id}/versions`)
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
    ElMessage.warning('Please enter a workflow name')
    return false
  }
  fillKeySlugFromName()
  if (!/^[A-Za-z0-9][A-Za-z0-9_-]{1,127}$/.test(createForm.keySlug.trim())) {
    ElMessage.warning('Key must be 2-128 characters, using letters, numbers, _ or -')
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
    ElMessage.success('Workflow created')
    router.push(`/workflows/${data.id}/studio`)
  } catch (err) {
    ElMessage.error((err as Error).message || 'Create workflow failed')
  } finally {
    creating.value = false
  }
}
</script>

<style scoped>
.workflow-list {
  min-height: calc(100vh - 56px);
  padding: 20px;
  background: var(--el-bg-color-page);
}

.page-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 16px;
}

.page-actions {
  display: flex;
  align-items: center;
  gap: 8px;
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

.page-header h1 {
  margin: 0 0 6px;
  font-size: 20px;
  font-weight: 700;
  letter-spacing: 0;
}

.page-header p,
.muted {
  margin: 0;
  color: var(--el-text-color-secondary);
}

.filters {
  margin-bottom: 12px;
}

.context-alert {
  margin-bottom: 12px;
}
</style>
