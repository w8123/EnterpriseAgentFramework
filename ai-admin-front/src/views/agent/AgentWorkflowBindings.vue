<template>
  <div class="binding-page">
    <header class="page-header">
      <div>
        <h1>Agent Workflow Bindings</h1>
        <p>Route an entry Agent to Workflows by page, route, action, or intent.</p>
      </div>
      <el-button :icon="Refresh" :loading="loading" @click="loadBindings">Reload</el-button>
    </header>

    <section class="preview-band">
      <el-form :model="preview" :inline="true">
        <el-form-item label="Page">
          <el-input v-model="preview.pageKey" clearable placeholder="pageKey" />
        </el-form-item>
        <el-form-item label="Route">
          <el-input v-model="preview.route" clearable placeholder="/orders" />
        </el-form-item>
        <el-form-item label="Action">
          <el-input v-model="preview.actionKey" clearable placeholder="actionKey" />
        </el-form-item>
        <el-form-item label="Intent">
          <el-input v-model="preview.intentType" clearable placeholder="intentType" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :icon="Aim" :loading="resolving" @click="resolvePreview">
            Resolve
          </el-button>
        </el-form-item>
      </el-form>
      <el-alert
        v-if="resolved"
        type="success"
        :closable="false"
        show-icon
        :title="`Resolved Workflow: ${resolved.workflowId}`"
      />
    </section>

    <el-table :data="bindings" v-loading="loading" stripe>
      <el-table-column prop="bindingType" label="Type" width="120" />
      <el-table-column prop="workflowId" label="Workflow" min-width="220">
        <template #default="{ row }">
          <el-button text type="primary" @click="router.push(`/workflows/${row.workflowId}/studio`)">
            {{ row.workflowId }}
          </el-button>
        </template>
      </el-table-column>
      <el-table-column prop="pageKey" label="Page" min-width="160" />
      <el-table-column prop="routePattern" label="Route" min-width="180" />
      <el-table-column prop="actionKey" label="Action" min-width="160" />
      <el-table-column prop="intentType" label="Intent" min-width="160" />
      <el-table-column prop="priority" label="Priority" width="100" />
      <el-table-column label="Enabled" width="100">
        <template #default="{ row }">
          <el-tag size="small" :type="row.enabled === false ? 'info' : 'success'">
            {{ row.enabled === false ? 'No' : 'Yes' }}
          </el-tag>
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Aim, Refresh } from '@element-plus/icons-vue'
import { listAgentWorkflowBindings, resolveAgentWorkflowBinding } from '@/api/workflow'
import type { AgentWorkflowBinding, AgentWorkflowResolveRequest } from '@/types/workflow'

const route = useRoute()
const router = useRouter()
const agentId = String(route.params.agentId || route.params.id || '')

const loading = ref(false)
const resolving = ref(false)
const bindings = ref<AgentWorkflowBinding[]>([])
const resolved = ref<AgentWorkflowBinding | null>(null)
const preview = reactive<AgentWorkflowResolveRequest>({
  pageKey: '',
  route: '',
  actionKey: '',
  intentType: '',
})

onMounted(loadBindings)

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
