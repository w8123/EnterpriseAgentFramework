<template>
  <div class="compat-page">
    <header class="compat-header">
      <div>
        <h1>Agent 版本已迁移</h1>
        <p>版本与发布已归属 Workflow；请通过 Agent 绑定的 Workflow 管理版本。</p>
      </div>
      <el-button :icon="Refresh" :loading="loading" @click="loadBindings">刷新</el-button>
    </header>

    <el-alert
      v-if="autoRedirecting"
      type="success"
      :closable="false"
      show-icon
      title="正在跳转到 Workflow 版本页"
    />

    <el-alert
      v-else-if="!loading"
      type="info"
      :closable="false"
      show-icon
      title="Agent 版本功能已弃用"
    >
      <template #default>
        <div class="alert-body">
          请先为 Agent 配置 Workflow 绑定，再在 Workflow 版本页发布与回滚。
        </div>
      </template>
    </el-alert>

    <section class="compat-actions">
      <el-button type="primary" :icon="Connection" @click="router.push(`/agents/${agentId}/bindings`)">
        Workflow 绑定
      </el-button>
      <el-button
        v-if="primaryWorkflowId"
        :icon="Operation"
        @click="router.push(`/workflows/${primaryWorkflowId}/versions`)"
      >
        Workflow 版本
      </el-button>
      <el-button :icon="ArrowLeft" text @click="router.back()">返回</el-button>
    </section>

    <el-table v-if="bindings.length" :data="bindings" v-loading="loading" stripe>
      <el-table-column prop="bindingType" label="类型" width="120" />
      <el-table-column prop="workflowId" label="Workflow" min-width="220">
        <template #default="{ row }">
          <el-button text type="primary" @click="router.push(`/workflows/${row.workflowId}/versions`)">
            {{ row.workflowId }}
          </el-button>
        </template>
      </el-table-column>
      <el-table-column prop="priority" label="优先级" width="100" />
      <el-table-column label="启用" width="100">
        <template #default="{ row }">
          <el-tag size="small" :type="row.enabled === false ? 'info' : 'success'">
            {{ row.enabled === false ? '否' : '是' }}
          </el-tag>
        </template>
      </el-table-column>
    </el-table>

    <el-empty v-else-if="!loading" description="暂无 Workflow 绑定" />
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
const primaryWorkflowId = computed(() => enabledBindings.value[0]?.workflowId || '')

onMounted(loadBindings)

async function loadBindings() {
  if (!agentId.value) return
  loading.value = true
  try {
    const { data } = await listAgentWorkflowBindings(agentId.value)
    bindings.value = Array.isArray(data) ? data : []
    if (enabledBindings.value.length === 1) {
      autoRedirecting.value = true
      await router.replace(`/workflows/${enabledBindings.value[0].workflowId}/versions`)
    }
  } finally {
    loading.value = false
  }
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
</style>
