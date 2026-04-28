<template>
  <div class="page-container">
    <div class="page-header">
      <h2>A2A 会话监控</h2>
      <div class="header-actions">
        <el-input v-model="filter.agentKey" placeholder="agentKey 过滤" clearable style="width: 200px" @change="reload" />
        <el-select v-model="filter.method" placeholder="方法" clearable style="width: 160px" @change="reload">
          <el-option label="card" value="card" />
          <el-option label="message/send" value="message/send" />
          <el-option label="tasks/get" value="tasks/get" />
          <el-option label="tasks/cancel" value="tasks/cancel" />
        </el-select>
        <el-select v-model="filter.success" placeholder="状态" clearable style="width: 120px" @change="reload">
          <el-option label="成功" :value="true" />
          <el-option label="失败" :value="false" />
        </el-select>
        <el-button :icon="Refresh" @click="reload" :loading="loading">刷新</el-button>
      </div>
    </div>

    <el-card shadow="never">
      <el-table :data="rows" v-loading="loading" stripe size="small" @row-click="openDetail">
        <el-table-column prop="createdAt" label="时间" width="170" />
        <el-table-column prop="agentKey" label="Agent Key" width="180" show-overflow-tooltip />
        <el-table-column prop="method" label="方法" width="140">
          <template #default="{ row }"><code>{{ row.method }}</code></template>
        </el-table-column>
        <el-table-column prop="taskId" label="taskId" width="200" show-overflow-tooltip />
        <el-table-column prop="success" label="状态" width="80">
          <template #default="{ row }">
            <el-tag size="small" :type="row.success ? 'success' : 'danger'">{{ row.success ? 'OK' : 'FAIL' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="latencyMs" label="耗时" width="90">
          <template #default="{ row }">{{ row.latencyMs }} ms</template>
        </el-table-column>
        <el-table-column prop="errorMessage" label="错误" min-width="200" show-overflow-tooltip />
        <el-table-column prop="remoteIp" label="IP" width="130" />
        <el-table-column prop="traceId" label="trace" width="220">
          <template #default="{ row }">
            <el-button v-if="row.traceId" link size="small" @click.stop="goTrace(row.traceId)">
              {{ row.traceId.slice(0, 12) }}…
            </el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination
        v-model:current-page="page.pageNum"
        v-model:page-size="page.pageSize"
        :total="page.total"
        :page-sizes="[50, 100, 200]"
        layout="total, sizes, prev, pager, next"
        @current-change="reload"
        @size-change="reload"
        style="margin-top: 12px; justify-content: flex-end"
      />
    </el-card>

    <el-drawer v-model="drawerVisible" title="调用详情" size="50%">
      <div v-if="active">
        <el-descriptions :column="2" border>
          <el-descriptions-item label="时间">{{ active.createdAt }}</el-descriptions-item>
          <el-descriptions-item label="Agent Key">{{ active.agentKey }}</el-descriptions-item>
          <el-descriptions-item label="方法"><code>{{ active.method }}</code></el-descriptions-item>
          <el-descriptions-item label="taskId">{{ active.taskId }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag :type="active.success ? 'success' : 'danger'">{{ active.success ? 'OK' : 'FAIL' }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="耗时">{{ active.latencyMs }} ms</el-descriptions-item>
          <el-descriptions-item label="trace" :span="2">
            <el-button v-if="active.traceId" link size="small" @click="goTrace(active.traceId!)">
              {{ active.traceId }}
            </el-button>
          </el-descriptions-item>
          <el-descriptions-item label="错误" :span="2" v-if="active.errorMessage">{{ active.errorMessage }}</el-descriptions-item>
        </el-descriptions>
        <el-divider>请求体</el-divider>
        <pre class="payload">{{ pretty(active.requestBody) }}</pre>
        <el-divider>响应体</el-divider>
        <pre class="payload">{{ pretty(active.responseBody) }}</pre>
      </div>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'

import { pageA2aCallLogs } from '@/api/a2a'
import type { A2aCallLog } from '@/types/a2a'

const loading = ref(false)
const rows = ref<A2aCallLog[]>([])
const drawerVisible = ref(false)
const active = ref<A2aCallLog | null>(null)

const filter = reactive<{ agentKey: string; method: string; success: boolean | undefined }>({
  agentKey: '',
  method: '',
  success: undefined,
})

const page = reactive({ pageNum: 1, pageSize: 50, total: 0 })

async function reload() {
  loading.value = true
  try {
    const { data } = await pageA2aCallLogs({
      pageNum: page.pageNum,
      pageSize: page.pageSize,
      agentKey: filter.agentKey || undefined,
      method: filter.method || undefined,
      success: filter.success,
    })
    rows.value = data?.records ?? []
    page.total = data?.total ?? 0
  } finally {
    loading.value = false
  }
}

function openDetail(row: A2aCallLog) {
  active.value = row
  drawerVisible.value = true
}

function goTrace(traceId: string) {
  navigator.clipboard.writeText(traceId)
  ElMessage.success('已复制 traceId，可在 Agent 调试页面粘贴查看完整 TraceTimeline')
}

function pretty(s?: string) {
  if (!s) return ''
  try { return JSON.stringify(JSON.parse(s), null, 2) } catch { return s }
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
.payload {
  background: var(--el-fill-color-lighter); padding: 12px;
  border-radius: 4px; font-size: 12px; white-space: pre-wrap; word-break: break-all;
  max-height: 320px; overflow: auto;
}
</style>
