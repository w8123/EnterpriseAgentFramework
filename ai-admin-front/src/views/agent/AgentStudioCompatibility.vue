<template>
  <div class="compat-page">
    <header class="compat-header">
      <div>
        <h1>Workflow Studio 是当前画布入口</h1>
        <p>Agent 负责入口和绑定，Workflow 负责 GraphSpec 编辑、调试和发布。</p>
      </div>
      <el-button :icon="Refresh" :loading="loading" @click="loadBindings">Reload</el-button>
    </header>

    <el-alert
      v-if="autoRedirecting"
      type="success"
      :closable="false"
      show-icon
      title="正在打开已绑定的 Workflow Studio"
    />

    <el-alert
      v-else-if="!loading"
      type="warning"
      :closable="false"
      show-icon
      title="编辑画布前请选择 Workflow"
    >
      <template #default>
        <div class="alert-body">
          当前 Agent 有 {{ enabledBindings.length }} 个启用的 Workflow binding；旧 Agent 画布编辑入口仅保留兼容跳转。
        </div>
      </template>
    </el-alert>

    <section class="compat-actions">
      <el-button type="primary" :icon="Connection" @click="router.push(`/agents/${agentId}/bindings`)">
        Agent Binding
      </el-button>
      <el-button :icon="Operation" @click="router.push('/workflows')">Workflow 列表</el-button>
      <el-button :icon="ArrowLeft" text @click="router.back()">返回</el-button>
    </section>

    <el-table v-if="bindings.length" :data="bindings" v-loading="loading" stripe>
      <el-table-column prop="bindingType" label="类型" width="120" />
      <el-table-column prop="workflowId" label="Workflow" min-width="220">
        <template #default="{ row }">
          <el-button text type="primary" @click="openWorkflow(row.workflowId)">
            {{ row.workflowId }}
          </el-button>
        </template>
      </el-table-column>
      <el-table-column prop="pageKey" label="页面" min-width="180" />
      <el-table-column prop="routePattern" label="路由" min-width="200" />
      <el-table-column prop="actionKey" label="动作" min-width="160" />
      <el-table-column prop="intentType" label="意图" min-width="160" />
      <el-table-column prop="priority" label="优先级" width="100" />
      <el-table-column label="启用" width="100">
        <template #default="{ row }">
          <el-tag size="small" :type="row.enabled === false ? 'info' : 'success'">
            {{ row.enabled === false ? '否' : '是' }}
          </el-tag>
        </template>
      </el-table-column>
    </el-table>

    <el-empty v-else-if="!loading" description="暂无 Workflow binding" />
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
