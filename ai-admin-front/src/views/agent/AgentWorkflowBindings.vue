<template>
  <div class="binding-page">
    <header class="page-header">
      <div>
        <h1>Agent Workflow 绑定</h1>
        <p>将入口 Agent 按页面、路由、动作或意图路由到 Workflow。</p>
      </div>
      <div class="header-actions">
        <el-button type="primary" :icon="Plus" @click="openCreate">新建绑定</el-button>
        <el-button :icon="Refresh" :loading="loading" @click="loadBindings">刷新</el-button>
      </div>
    </header>

    <section class="preview-band">
      <el-form :model="preview" :inline="true">
        <el-form-item label="页面">
          <el-input v-model="preview.pageKey" clearable placeholder="pageKey" />
        </el-form-item>
        <el-form-item label="路由">
          <el-input v-model="preview.route" clearable placeholder="/orders" />
        </el-form-item>
        <el-form-item label="动作">
          <el-input v-model="preview.actionKey" clearable placeholder="actionKey" />
        </el-form-item>
        <el-form-item label="意图">
          <el-input v-model="preview.intentType" clearable placeholder="intentType" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :icon="Aim" :loading="resolving" @click="resolvePreview">
            解析预览
          </el-button>
        </el-form-item>
      </el-form>
      <el-alert
        v-if="resolved"
        type="success"
        :closable="false"
        show-icon
        :title="`解析到 Workflow：${resolved.workflowId}`"
      />
    </section>

    <el-table :data="bindings" v-loading="loading" stripe>
      <el-table-column prop="bindingType" label="类型" width="120" />
      <el-table-column prop="workflowId" label="Workflow" min-width="220">
        <template #default="{ row }">
          <el-button text type="primary" @click="router.push(`/workflows/${row.workflowId}/studio`)">
            {{ workflowLabel(row.workflowId) }}
          </el-button>
        </template>
      </el-table-column>
      <el-table-column prop="pageKey" label="页面" min-width="140" />
      <el-table-column prop="routePattern" label="路由" min-width="160" />
      <el-table-column prop="actionKey" label="动作" min-width="140" />
      <el-table-column prop="intentType" label="意图" min-width="140" />
      <el-table-column prop="priority" label="优先级" width="90" />
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag size="small" :type="row.enabled === false ? 'info' : 'success'">
            {{ row.enabled === false ? '停用' : '启用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="220" fixed="right">
        <template #default="{ row }">
          <el-button text type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button text @click="toggleEnabled(row)">
            {{ row.enabled === false ? '启用' : '停用' }}
          </el-button>
          <el-button text type="danger" @click="handleDelete(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog
      v-model="dialogVisible"
      :title="editingId ? '编辑绑定' : '新建绑定'"
      width="560px"
      destroy-on-close
    >
      <el-form :model="form" label-width="96px">
        <el-form-item label="Workflow" required>
          <el-select v-model="form.workflowId" filterable placeholder="选择 Workflow" style="width: 100%">
            <el-option
              v-for="wf in workflows"
              :key="wf.id"
              :label="`${wf.name} (${wf.keySlug})`"
              :value="wf.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="绑定类型">
          <el-select v-model="form.bindingType" style="width: 100%">
            <el-option v-for="t in bindingTypes" :key="t" :label="t" :value="t" />
          </el-select>
        </el-form-item>
        <el-form-item label="页面">
          <el-input v-model="form.pageKey" clearable />
        </el-form-item>
        <el-form-item label="路由">
          <el-input v-model="form.routePattern" clearable />
        </el-form-item>
        <el-form-item label="动作">
          <el-input v-model="form.actionKey" clearable />
        </el-form-item>
        <el-form-item label="意图">
          <el-input v-model="form.intentType" clearable />
        </el-form-item>
        <el-form-item label="优先级">
          <el-input-number v-model="form.priority" :min="0" :max="9999" />
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="form.enabled" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="handleSave">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Aim, Plus, Refresh } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  createAgentWorkflowBinding,
  deleteAgentWorkflowBinding,
  listAgentWorkflowBindings,
  listWorkflows,
  resolveAgentWorkflowBinding,
  updateAgentWorkflowBinding,
} from '@/api/workflow'
import type { AgentWorkflowBinding, AgentWorkflowResolveRequest, WorkflowDefinition } from '@/types/workflow'

const route = useRoute()
const router = useRouter()
const agentId = String(route.params.agentId || route.params.id || '')

const bindingTypes = ['DEFAULT', 'PAGE', 'ROUTE', 'ACTION', 'INTENT', 'TASK', 'FALLBACK']

const loading = ref(false)
const saving = ref(false)
const resolving = ref(false)
const dialogVisible = ref(false)
const editingId = ref<number | null>(null)
const bindings = ref<AgentWorkflowBinding[]>([])
const workflows = ref<WorkflowDefinition[]>([])
const workflowMap = ref<Record<string, WorkflowDefinition>>({})
const resolved = ref<AgentWorkflowBinding | null>(null)
const preview = reactive<AgentWorkflowResolveRequest>({
  pageKey: '',
  route: '',
  actionKey: '',
  intentType: '',
})

const form = reactive({
  workflowId: '',
  bindingType: 'DEFAULT',
  pageKey: '',
  routePattern: '',
  actionKey: '',
  intentType: '',
  priority: 0,
  enabled: true,
})

onMounted(async () => {
  await Promise.all([loadBindings(), loadWorkflows()])
})

function workflowLabel(workflowId?: string | null) {
  if (!workflowId) return '-'
  const wf = workflowMap.value[workflowId]
  return wf ? `${wf.name}` : workflowId
}

async function loadWorkflows() {
  const { data } = await listWorkflows()
  workflows.value = Array.isArray(data) ? data : []
  workflowMap.value = Object.fromEntries(workflows.value.map((wf) => [wf.id, wf]))
}

async function loadBindings() {
  if (!agentId) return
  loading.value = true
  try {
    const { data } = await listAgentWorkflowBindings(agentId)
    bindings.value = Array.isArray(data) ? data : []
  } finally {
    loading.value = false
  }
}

function resetForm() {
  form.workflowId = ''
  form.bindingType = 'DEFAULT'
  form.pageKey = ''
  form.routePattern = ''
  form.actionKey = ''
  form.intentType = ''
  form.priority = 0
  form.enabled = true
}

function openCreate() {
  editingId.value = null
  resetForm()
  dialogVisible.value = true
}

function openEdit(row: AgentWorkflowBinding) {
  editingId.value = row.id ?? null
  form.workflowId = row.workflowId || ''
  form.bindingType = row.bindingType || 'DEFAULT'
  form.pageKey = row.pageKey || ''
  form.routePattern = row.routePattern || ''
  form.actionKey = row.actionKey || ''
  form.intentType = row.intentType || ''
  form.priority = row.priority ?? 0
  form.enabled = row.enabled !== false
  dialogVisible.value = true
}

async function handleSave() {
  if (!form.workflowId) {
    ElMessage.warning('请选择 Workflow')
    return
  }
  saving.value = true
  try {
    const payload = {
      workflowId: form.workflowId,
      bindingType: form.bindingType,
      pageKey: form.pageKey || null,
      routePattern: form.routePattern || null,
      actionKey: form.actionKey || null,
      intentType: form.intentType || null,
      priority: form.priority,
      enabled: form.enabled,
    }
    if (editingId.value) {
      await updateAgentWorkflowBinding(agentId, editingId.value, payload)
      ElMessage.success('绑定已更新')
    } else {
      await createAgentWorkflowBinding(agentId, payload)
      ElMessage.success('绑定已创建')
    }
    dialogVisible.value = false
    await loadBindings()
  } finally {
    saving.value = false
  }
}

async function toggleEnabled(row: AgentWorkflowBinding) {
  if (!row.id) return
  await updateAgentWorkflowBinding(agentId, row.id, { enabled: row.enabled === false })
  ElMessage.success(row.enabled === false ? '已启用' : '已停用')
  await loadBindings()
}

async function handleDelete(row: AgentWorkflowBinding) {
  if (!row.id) return
  await ElMessageBox.confirm('确定删除该绑定？', '删除确认', { type: 'warning' })
  await deleteAgentWorkflowBinding(agentId, row.id)
  ElMessage.success('已删除')
  await loadBindings()
}

async function resolvePreview() {
  resolving.value = true
  try {
    const request: AgentWorkflowResolveRequest = {
      agentId,
      pageKey: preview.pageKey || undefined,
      route: preview.route || undefined,
      actionKey: preview.actionKey || undefined,
      intentType: preview.intentType || undefined,
    }
    const { data } = await resolveAgentWorkflowBinding(agentId, request)
    resolved.value = data
  } finally {
    resolving.value = false
  }
}
</script>

<style scoped>
.binding-page {
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

.header-actions {
  display: flex;
  gap: 8px;
}

.page-header h1 {
  margin: 0 0 6px;
  font-size: 20px;
  font-weight: 700;
  letter-spacing: 0;
}

.page-header p {
  margin: 0;
  color: var(--el-text-color-secondary);
}

.preview-band {
  margin-bottom: 14px;
  padding: 14px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  background: var(--el-bg-color);
}
</style>
