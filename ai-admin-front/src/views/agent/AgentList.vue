<template>
  <div class="page-container">
    <div class="page-header">
      <h2>Agent 管理</h2>
      <el-button type="primary" @click="handleCreate">
        <el-icon><Plus /></el-icon>新建 Agent
      </el-button>
    </div>

    <el-card shadow="never">
      <el-tabs v-model="activeView" class="top-tabs">
        <el-tab-pane label="Agent 列表" name="agents" />
        <el-tab-pane label="最近 Trace" name="traces" />
      </el-tabs>
      <template v-if="activeView === 'agents'">
      <div class="filter-bar">
        <el-select v-model="filterIntent" placeholder="按意图类型筛选" clearable style="width: 180px">
          <el-option
            v-for="t in allIntentTypes"
            :key="t.value"
            :label="t.label"
            :value="t.value"
          />
        </el-select>
        <el-select v-model="filterTrigger" placeholder="按触发方式筛选" clearable style="width: 160px">
          <el-option
            v-for="m in TRIGGER_MODES"
            :key="m.value"
            :label="m.label"
            :value="m.value"
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
        <el-table-column prop="triggerMode" label="触发方式" width="100" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="triggerTagType(row.triggerMode)">
              {{ triggerLabel(row.triggerMode) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="modelName" label="模型" width="120" />
        <el-table-column label="Tools" min-width="160">
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
        <el-table-column label="知识库组" width="120">
          <template #default="{ row }">
            <span v-if="row.knowledgeBaseGroupId" class="meta-text">{{ row.knowledgeBaseGroupId }}</span>
            <span v-else class="meta-empty">—</span>
          </template>
        </el-table-column>
        <el-table-column prop="maxSteps" label="步数" width="70" align="center" />
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
        <el-table-column label="操作" width="300" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="handleEdit(row.id)">编辑</el-button>
            <el-button link type="warning" size="small" @click="handleStudio(row.id)">画布编排</el-button>
            <el-button link type="info" size="small" @click="handleVersions(row.id)">版本</el-button>
            <el-button link type="success" size="small" @click="handleDebug(row.id)">调试</el-button>
            <el-popconfirm title="确认删除该 Agent？" @confirm="handleDelete(row.id)">
              <template #reference>
                <el-button link type="danger" size="small">删除</el-button>
              </template>
            </el-popconfirm>
          </template>
        </el-table-column>
      </el-table>
      </template>
      <template v-else>
        <div class="filter-bar">
          <el-input v-model="traceFilter.userId" placeholder="按 userId 过滤" clearable style="width: 200px" />
          <el-select v-model="traceFilter.days" style="width: 130px">
            <el-option :value="1" label="近1天" />
            <el-option :value="7" label="近7天" />
            <el-option :value="14" label="近14天" />
          </el-select>
          <el-button type="primary" @click="fetchRecentTraces">查询</el-button>
        </div>
        <el-table :data="recentTraces" v-loading="traceLoading" stripe>
          <el-table-column prop="traceId" label="traceId" min-width="240" show-overflow-tooltip />
          <el-table-column prop="agentName" label="agent" width="140" />
          <el-table-column prop="intentType" label="intent" width="120" />
          <el-table-column prop="callCount" label="调用数" width="80" />
          <el-table-column label="成功率" width="100">
            <template #default="{ row }">
              {{ row.callCount ? ((row.successCount / row.callCount) * 100).toFixed(1) : '0.0' }}%
            </template>
          </el-table-column>
          <el-table-column prop="startedAt" label="开始时间" width="180" />
          <el-table-column label="操作" width="120">
            <template #default="{ row }">
              <el-button link type="primary" @click="copyTraceId(row.traceId)">复制ID</el-button>
            </template>
          </el-table-column>
        </el-table>
      </template>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import type { AgentDefinition } from '@/types/agent'
import { INTENT_TYPES, TRIGGER_MODES } from '@/types/agent'
import { getAgentList, deleteAgent, updateAgent } from '@/api/agent'
import { getRecentTraces } from '@/api/trace'
import type { TraceSummary } from '@/types/trace'

const router = useRouter()
const agents = ref<AgentDefinition[]>([])
const loading = ref(false)
const filterIntent = ref<string>('')
const filterTrigger = ref<string>('')
const filterEnabled = ref<boolean | ''>('')
const activeView = ref<'agents' | 'traces'>('agents')
const recentTraces = ref<TraceSummary[]>([])
const traceLoading = ref(false)
const traceFilter = ref({ userId: '', days: 7 })

/**
 * 合并预置意图类型与数据中出现的自定义意图，
 * 确保筛选下拉框能覆盖所有实际值。
 */
const allIntentTypes = computed(() => {
  const presetValues = new Set<string>(INTENT_TYPES.map((t) => t.value))
  const custom = agents.value
    .map((a) => a.intentType)
    .filter((v) => v && !presetValues.has(v))
    .filter((v, i, arr) => arr.indexOf(v) === i)
    .map((v) => ({ value: v, label: v }))
  return [...INTENT_TYPES, ...custom]
})

const filteredAgents = computed(() => {
  return agents.value.filter((a) => {
    if (filterIntent.value && a.intentType !== filterIntent.value) return false
    if (filterTrigger.value && a.triggerMode !== filterTrigger.value) return false
    if (filterEnabled.value !== '' && a.enabled !== filterEnabled.value) return false
    return true
  })
})

function intentLabel(type: string) {
  return INTENT_TYPES.find((t) => t.value === type)?.label || type
}

function triggerLabel(mode: string) {
  return TRIGGER_MODES.find((m) => m.value === mode)?.label || mode || '全部'
}

function triggerTagType(mode: string): '' | 'success' | 'warning' | 'info' | 'danger' {
  const map: Record<string, '' | 'success' | 'warning' | 'info' | 'danger'> = {
    all: '',
    chat: 'success',
    api: 'warning',
    event: 'info',
  }
  return map[mode] ?? ''
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

async function fetchRecentTraces() {
  traceLoading.value = true
  try {
    const { data } = await getRecentTraces({
      userId: traceFilter.value.userId || undefined,
      days: traceFilter.value.days,
      limit: 50,
    })
    recentTraces.value = Array.isArray(data) ? data : []
  } catch {
    recentTraces.value = []
    ElMessage.error('加载 Trace 失败')
  } finally {
    traceLoading.value = false
  }
}

function copyTraceId(traceId: string) {
  if (!traceId) return
  if (typeof window !== 'undefined' && window.navigator?.clipboard) {
    window.navigator.clipboard.writeText(traceId)
    ElMessage.success('traceId 已复制')
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

function handleStudio(id: string) {
  router.push(`/agent/${id}/studio`)
}

function handleVersions(id: string) {
  router.push(`/agent/${id}/versions`)
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

onMounted(() => {
  fetchData()
})

// 切到"最近 Trace"时再拉；避免用户从不点该 tab 也做了无用请求
watch(activeView, (view) => {
  if (view === 'traces' && recentTraces.value.length === 0) {
    fetchRecentTraces()
  }
})
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

.meta-text {
  font-size: 12px;
  color: #606266;
}

.meta-empty {
  color: #c0c4cc;
}
</style>
