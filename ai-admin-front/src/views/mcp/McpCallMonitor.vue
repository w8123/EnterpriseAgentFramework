<template>
  <div class="page-container">
    <div class="page-header">
      <h2>MCP 调用流水</h2>
      <div class="header-actions">
        <el-input v-model="filterMethod" placeholder="method 过滤" clearable style="width: 180px" />
        <el-select v-model="filterClient" placeholder="Client" clearable style="width: 180px">
          <el-option v-for="c in clients" :key="c.id" :label="c.name" :value="c.id" />
        </el-select>
        <el-select v-model="filterSuccess" placeholder="状态" clearable style="width: 120px">
          <el-option label="成功" :value="true" />
          <el-option label="失败" :value="false" />
        </el-select>
        <el-input-number v-model="days" :min="1" :max="90" :step="1" controls-position="right" />
        <el-button :icon="Refresh" @click="reload" :loading="loading">刷新</el-button>
      </div>
    </div>

    <el-card shadow="never">
      <el-table :data="rows" v-loading="loading" stripe size="small">
        <el-table-column prop="createdAt" label="时间" width="170" />
        <el-table-column prop="clientName" label="Client" width="160" show-overflow-tooltip />
        <el-table-column prop="method" label="方法" width="120">
          <template #default="{ row }"><code>{{ row.method }}</code></template>
        </el-table-column>
        <el-table-column prop="toolName" label="Tool" width="160" show-overflow-tooltip />
        <el-table-column prop="success" label="状态" width="80">
          <template #default="{ row }">
            <el-tag size="small" :type="row.success ? 'success' : 'danger'">{{ row.success ? 'OK' : 'FAIL' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="latencyMs" label="耗时" width="80">
          <template #default="{ row }">{{ row.latencyMs }} ms</template>
        </el-table-column>
        <el-table-column prop="errorMessage" label="错误" min-width="220" show-overflow-tooltip />
        <el-table-column prop="remoteIp" label="IP" width="120" />
        <el-table-column prop="traceId" label="trace" width="160">
          <template #default="{ row }">
            <el-button v-if="row.traceId" size="small" link @click="copy(row.traceId)">{{ row.traceId.slice(0, 12) }}…</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination
        v-model:current-page="pagination.current"
        v-model:page-size="pagination.size"
        :total="pagination.total"
        :page-sizes="[50, 100, 200]"
        layout="total, sizes, prev, pager, next"
        @current-change="reload"
        @size-change="reload"
        style="margin-top: 12px; justify-content: flex-end"
      />
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'

import { listMcpClients, pageMcpCallLogs } from '@/api/mcp'
import type { McpCallLog, McpClient } from '@/types/mcp'

const loading = ref(false)
const rows = ref<McpCallLog[]>([])
const clients = ref<McpClient[]>([])

const filterMethod = ref<string>('')
const filterClient = ref<number | undefined>()
const filterSuccess = ref<boolean | undefined>()
const days = ref(7)

const pagination = reactive({ current: 1, size: 50, total: 0 })

async function reload() {
  loading.value = true
  try {
    const [logs, cs] = await Promise.all([
      pageMcpCallLogs({
        current: pagination.current,
        size: pagination.size,
        method: filterMethod.value || undefined,
        clientId: filterClient.value,
        success: filterSuccess.value,
        days: days.value,
      }),
      listMcpClients(),
    ])
    rows.value = logs.data?.records ?? []
    pagination.total = logs.data?.total ?? 0
    clients.value = cs.data ?? []
  } finally {
    loading.value = false
  }
}

function copy(t: string) {
  navigator.clipboard.writeText(t)
  ElMessage.success('已复制 traceId')
}

onMounted(reload)
</script>

<style scoped lang="scss">
.page-container { padding: 16px; }
.page-header {
  display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px;
  h2 { margin: 0; font-size: 18px; }
}
.header-actions { display: flex; gap: 8px; align-items: center; }
</style>
