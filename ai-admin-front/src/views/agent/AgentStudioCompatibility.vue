<template>
  <div class="compat-page">
    <header class="compat-header">
      <div>
        <h1>Agent Studio moved to Workflow Studio</h1>
        <p>Agent is now the project entry. Workflow owns graph editing and publishing.</p>
      </div>
      <el-button :icon="Refresh" :loading="loading" @click="loadBindings">Reload</el-button>
    </header>

    <el-alert
      v-if="autoRedirecting"
      type="success"
      :closable="false"
      show-icon
      title="Opening the bound Workflow Studio"
    />

    <el-alert
      v-else-if="!loading"
      type="warning"
      :closable="false"
      show-icon
      title="Choose a Workflow before editing graph"
    >
      <template #default>
        <div class="alert-body">
          This Agent has {{ enabledBindings.length }} enabled Workflow binding(s). Old Agent graph editing is deprecated.
        </div>
      </template>
    </el-alert>

    <section class="compat-actions">
      <el-button type="primary" :icon="Connection" @click="router.push(`/agents/${agentId}/bindings`)">
        Agent Bindings
      </el-button>
      <el-button :icon="Operation" @click="router.push('/workflows')">Workflow List</el-button>
      <el-button :icon="ArrowLeft" text @click="router.back()">Back</el-button>
    </section>

    <el-table v-if="bindings.length" :data="bindings" v-loading="loading" stripe>
      <el-table-column prop="bindingType" label="Type" width="120" />
      <el-table-column prop="workflowId" label="Workflow" min-width="220">
        <template #default="{ row }">
          <el-button text type="primary" @click="openWorkflow(row.workflowId)">
            {{ row.workflowId }}
          </el-button>
        </template>
      </el-table-column>
      <el-table-column prop="pageKey" label="Page" min-width="180" />
      <el-table-column prop="routePattern" label="Route" min-width="200" />
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

    <el-empty v-else-if="!loading" description="No Workflow bindings" />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowLeft, Connection, Operation, Refresh } from '@element-plus/icons-vue'
import { listAgentWorkflowBindings } from '@/api/workflow'
import type { AgentWorkflowBinding } from '@/types/workflow'

const route = useRoute()
const router = useRouter()

const agentId = computed(() => String(route.params.id || ''))
const loading = ref(false)
const autoRedirecting = ref(false)
const bindings = ref<AgentWorkflowBinding[]>([])

const enabledBindings = computed(() => bindings.value.filter((item) => item.enabled !== false))

onMounted(loadBindings)

async function loadBindings() {
  if (!agentId.value) return
  loading.value = true
  try {
    const { data } = await listAgentWorkflowBindings(agentId.value)
    bindings.value = Array.isArray(data) ? data : []
    if (enabledBindings.value.length === 1) {
      autoRedirecting.value = true
      await router.replace(`/workflows/${enabledBindings.value[0].workflowId}/studio`)
    }
  } finally {
    loading.value = false
  }
}

function openWorkflow(workflowId: string) {
  router.push(`/workflows/${workflowId}/studio`)
}
</script>

<style scoped>
.compat-page {
  min-height: calc(100vh - 56px);
  padding: 20px;
  background: var(--el-bg-color-page);
}

.compat-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 16px;
}

.compat-header h1 {
  margin: 0 0 6px;
  font-size: 20px;
  font-weight: 700;
  letter-spacing: 0;
}

.compat-header p {
  margin: 0;
  color: var(--el-text-color-secondary);
}

.alert-body {
  line-height: 1.5;
}

.compat-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  margin: 16px 0;
}

@media (max-width: 720px) {
  .compat-header,
  .compat-actions {
    align-items: stretch;
    flex-direction: column;
  }
}
</style>
