<template>
  <div class="page-container">
    <div class="page-header">
      <h2>Agent 管理</h2>
      <el-button type="primary" @click="handleCreate">
        <el-icon><Plus /></el-icon>新建 Agent
      </el-button>
    </div>

    <el-card shadow="never">
      <div class="filter-bar">
        <el-select v-model="filterIntent" placeholder="按意图类型筛选" clearable style="width: 180px">
          <el-option
            v-for="t in INTENT_TYPES"
            :key="t.value"
            :label="t.label"
            :value="t.value"
          />
        </el-select>
        <el-select v-model="filterEnabled" placeholder="按状态筛选" clearable style="width: 140px">
          <el-option label="已启用" :value="true" />
          <el-option label="已停用" :value="false" />
        </el-select>
      </div>

      <el-table :data="filteredAgents" v-loading="loading" stripe>
        <el-table-column prop="name" label="名称" min-width="140">
          <template #default="{ row }">
            <el-link type="primary" @click="handleEdit(row.id)">{{ row.name }}</el-link>
          </template>
        </el-table-column>
        <el-table-column prop="intentType" label="意图类型" width="130">
          <template #default="{ row }">
            <el-tag size="small">{{ intentLabel(row.intentType) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="type" label="类型" width="100">
          <template #default="{ row }">
            <el-tag :type="row.type === 'pipeline' ? 'warning' : 'info'" size="small">
              {{ row.type }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="modelName" label="模型" width="140" />
        <el-table-column label="Tools" min-width="180">
          <template #default="{ row }">
            <el-tag
              v-for="tool in (row.tools || []).slice(0, 3)"
              :key="tool"
              size="small"
              class="tool-tag"
            >{{ tool }}</el-tag>
            <span v-if="(row.tools || []).length > 3" class="more-tools">
              +{{ row.tools.length - 3 }}
            </span>
          </template>
        </el-table-column>
        <el-table-column prop="maxSteps" label="最大步数" width="90" align="center" />
        <el-table-column prop="enabled" label="状态" width="80" align="center">
          <template #default="{ row }">
            <el-switch
              :model-value="row.enabled"
              @change="(val: boolean) => handleToggle(row, val)"
              size="small"
            />
          </template>
        </el-table-column>
        <el-table-column prop="updatedAt" label="更新时间" width="170" />
        <el-table-column label="操作" width="200" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="handleEdit(row.id)">编辑</el-button>
            <el-button link type="success" size="small" @click="handleDebug(row.id)">调试</el-button>
            <el-popconfirm title="确认删除该 Agent？" @confirm="handleDelete(row.id)">
              <template #reference>
                <el-button link type="danger" size="small">删除</el-button>
              </template>
            </el-popconfirm>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import type { AgentDefinition } from '@/types/agent'
import { INTENT_TYPES } from '@/types/agent'
import { getAgentList, deleteAgent, updateAgent } from '@/api/agent'

const router = useRouter()
const agents = ref<AgentDefinition[]>([])
const loading = ref(false)
const filterIntent = ref<string>('')
const filterEnabled = ref<boolean | ''>('')

const filteredAgents = computed(() => {
  return agents.value.filter((a) => {
    if (filterIntent.value && a.intentType !== filterIntent.value) return false
    if (filterEnabled.value !== '' && a.enabled !== filterEnabled.value) return false
    return true
  })
})

function intentLabel(type: string) {
  return INTENT_TYPES.find((t) => t.value === type)?.label || type
}

async function fetchData() {
  loading.value = true
  try {
    const { data } = await getAgentList()
    agents.value = Array.isArray(data) ? data : []
  } catch {
    agents.value = []
  } finally {
    loading.value = false
  }
}

function handleCreate() {
  router.push('/agent/new/edit')
}

function handleEdit(id: string) {
  router.push(`/agent/${id}/edit`)
}

function handleDebug(id: string) {
  router.push(`/agent/${id}/debug`)
}

async function handleToggle(agent: AgentDefinition, enabled: boolean) {
  try {
    await updateAgent(agent.id, { enabled })
    agent.enabled = enabled
    ElMessage.success(enabled ? '已启用' : '已停用')
  } catch {
    ElMessage.error('操作失败')
  }
}

async function handleDelete(id: string) {
  try {
    await deleteAgent(id)
    ElMessage.success('删除成功')
    fetchData()
  } catch {
    ElMessage.error('删除失败')
  }
}

onMounted(fetchData)
</script>

<style scoped lang="scss">
.filter-bar {
  display: flex;
  gap: 12px;
  margin-bottom: 16px;
}

.tool-tag {
  margin-right: 4px;
  margin-bottom: 2px;
}

.more-tools {
  font-size: 12px;
  color: #909399;
}
</style>
