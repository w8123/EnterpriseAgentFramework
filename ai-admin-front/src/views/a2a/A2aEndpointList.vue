<template>
  <div class="page-container">
    <div class="page-header">
      <h2>A2A 暴露 Agent</h2>
      <div class="header-actions">
        <el-input v-model="filter.agentKey" placeholder="按 agentKey 过滤" clearable style="width: 220px" :prefix-icon="Search" @change="reload" />
        <el-select v-model="filter.enabled" clearable placeholder="启用状态" style="width: 140px" @change="reload">
          <el-option :value="true" label="启用" />
          <el-option :value="false" label="禁用" />
        </el-select>
        <el-button :icon="Refresh" @click="reload" :loading="loading">刷新</el-button>
        <el-button type="primary" :icon="Plus" @click="openCreateDialog">暴露新 Agent</el-button>
      </div>
    </div>

    <el-alert
      type="info"
      :closable="false"
      show-icon
      title="把已发布 Agent 当作 A2A 远程节点暴露给 Dify / LangGraph 等多 Agent 系统"
      description="对外可访问 URL：GET /a2a/{agentKey}/.well-known/agent.json （AgentCard） + POST /a2a/{agentKey}/jsonrpc （message/send / tasks/get / tasks/cancel）"
      style="margin-bottom: 12px"
    />

    <el-card shadow="never">
      <el-table :data="rows" v-loading="loading" stripe size="default">
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column prop="agentKey" label="Agent Key" min-width="180">
          <template #default="{ row }"><code>{{ row.agentKey }}</code></template>
        </el-table-column>
        <el-table-column prop="agentId" label="agent_id" width="160" />
        <el-table-column label="启用" width="90">
          <template #default="{ row }">
            <el-switch :model-value="row.enabled" @change="(v: boolean) => handleToggle(row, v)" />
          </template>
        </el-table-column>
        <el-table-column prop="updatedAt" label="更新时间" width="180" />
        <el-table-column label="操作" width="220" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openEditDialog(row)">编辑 AgentCard</el-button>
            <el-button link type="danger" @click="handleDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination
        v-model:current-page="page.pageNum"
        v-model:page-size="page.pageSize"
        :total="page.total"
        layout="total, prev, pager, next"
        @current-change="reload"
        @size-change="reload"
        style="margin-top: 12px"
      />
    </el-card>

    <el-dialog v-model="dialogVisible" :title="editing ? '编辑 AgentCard' : '暴露新 Agent'" width="780px">
      <el-form :model="form" label-width="120px">
        <el-form-item label="Agent">
          <el-select
            v-model="form.agentId"
            filterable
            :disabled="editing"
            placeholder="选择要暴露的 Agent"
            style="width: 100%"
          >
            <el-option
              v-for="agent in agentOptions"
              :key="agent.id"
              :label="`${agent.name} (${agent.keySlug})`"
              :value="agent.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="form.enabled" />
        </el-form-item>
        <el-form-item label="AgentCard JSON">
          <el-input
            type="textarea"
            v-model="form.cardJsonText"
            :rows="14"
            placeholder='{"name":"...","description":"...","version":"1.0.0",...}'
          />
          <div class="hint">
            留空 = 使用基于 Agent 元数据自动生成的默认 Card；编辑后会与默认值合并（同 key 覆盖）。
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="handleSubmit" :loading="submitting">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Refresh, Search } from '@element-plus/icons-vue'

import {
  deleteA2aEndpoint,
  getA2aEndpoint,
  pageA2aEndpoints,
  setA2aEndpointEnabled,
  upsertA2aEndpoint,
} from '@/api/a2a'
import { getAgentList } from '@/api/agent'
import type { A2aEndpoint } from '@/types/a2a'
import type { AgentDefinition } from '@/types/agent'

const loading = ref(false)
const submitting = ref(false)
const rows = ref<A2aEndpoint[]>([])
const agentOptions = ref<AgentDefinition[]>([])

const filter = reactive<{ agentKey: string; enabled: boolean | undefined }>({
  agentKey: '',
  enabled: undefined,
})

const page = reactive({ pageNum: 1, pageSize: 20, total: 0 })

const dialogVisible = ref(false)
const editing = ref(false)
const form = reactive<{ agentId: string; enabled: boolean; cardJsonText: string }>({
  agentId: '',
  enabled: true,
  cardJsonText: '',
})

async function reload() {
  loading.value = true
  try {
    const { data } = await pageA2aEndpoints({
      pageNum: page.pageNum,
      pageSize: page.pageSize,
      agentKey: filter.agentKey || undefined,
      enabled: filter.enabled,
    })
    rows.value = data?.records ?? []
    page.total = data?.total ?? 0
  } finally {
    loading.value = false
  }
}

async function reloadAgents() {
  try {
    const { data } = await getAgentList()
    agentOptions.value = data ?? []
  } catch (e) {
    console.error('load agents failed', e)
  }
}

function openCreateDialog() {
  editing.value = false
  form.agentId = ''
  form.enabled = true
  form.cardJsonText = ''
  dialogVisible.value = true
}

async function openEditDialog(row: A2aEndpoint) {
  editing.value = true
  form.agentId = row.agentId
  form.enabled = !!row.enabled
  try {
    const { data } = await getA2aEndpoint(row.id!)
    form.cardJsonText = data?.card ? JSON.stringify(data.card, null, 2) : ''
  } catch {
    form.cardJsonText = row.cardJson ?? ''
  }
  dialogVisible.value = true
}

async function handleSubmit() {
  if (!form.agentId) {
    ElMessage.warning('请选择 Agent')
    return
  }
  let card: Record<string, unknown> | undefined
  if (form.cardJsonText.trim()) {
    try {
      card = JSON.parse(form.cardJsonText)
    } catch {
      ElMessage.error('AgentCard JSON 解析失败，请检查格式')
      return
    }
  }
  submitting.value = true
  try {
    await upsertA2aEndpoint({ agentId: form.agentId, card, enabled: form.enabled })
    ElMessage.success(editing.value ? '已更新' : '已暴露')
    dialogVisible.value = false
    await reload()
  } finally {
    submitting.value = false
  }
}

async function handleToggle(row: A2aEndpoint, enabled: boolean) {
  await setA2aEndpointEnabled(row.id!, enabled)
  ElMessage.success(enabled ? '已启用' : '已禁用')
  await reload()
}

async function handleDelete(row: A2aEndpoint) {
  await ElMessageBox.confirm(`确认删除 A2A 暴露 ${row.agentKey} ？外部系统将立即不可访问`, '删除确认', { type: 'warning' })
  await deleteA2aEndpoint(row.id!)
  ElMessage.success('已删除')
  await reload()
}

onMounted(async () => {
  await Promise.all([reload(), reloadAgents()])
})
</script>

<style scoped lang="scss">
.page-container { padding: 16px; }
.page-header {
  display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px;
  h2 { margin: 0; font-size: 18px; }
}
.header-actions { display: flex; gap: 8px; align-items: center; }
.hint { color: var(--el-text-color-secondary); font-size: 12px; margin-top: 4px; }
</style>
