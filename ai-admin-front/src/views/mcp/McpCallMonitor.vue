<template>
  <WorkbenchPage class="mcp-call-monitor-page" layout="list">
    <PageHeader
      variant="standard"
      domain="governance"
      eyebrow="MCP Observability"
      title="MCP 调用流水"
      description="按 Client、方法和执行结果追踪 MCP 调用，定位延迟、错误与关联 Trace。"
    >
      <template #actions>
        <el-tooltip content="刷新调用流水" placement="top">
          <el-button circle :icon="Refresh" :loading="loading" aria-label="刷新调用流水" @click="reload" />
        </el-tooltip>
      </template>
    </PageHeader>

    <DataTableShell
      class="project-list-table-shell workbench-list-surface"
      v-model:current-page="pagination.current"
      v-model:page-size="pagination.size"
      density="compact"
      :loading="loading"
      :empty="rows.length === 0"
      :total="pagination.total"
      :page-sizes="[50, 100, 200]"
      pagination-layout="total, prev, pager, next, sizes"
      @page-change="reload"
      @size-change="handlePageSizeChange"
    >
      <template #toolbar>
        <FilterBar
          class="project-list-filter-bar mcp-call-filter-bar"
          density="compact"
          :loading="loading"
          :show-reset="false"
          query-label="搜索"
          @query="handleSearch"
          @reset="resetFilters"
        >
          <el-form-item label="方法名称" class="mcp-filter-method">
            <el-input
              v-model="filterMethod"
              clearable
              :prefix-icon="Search"
              placeholder="搜索方法"
              @keyup.enter="handleSearch"
            />
          </el-form-item>
          <el-form-item label="Client" class="mcp-filter-client">
            <el-select
              v-model="filterClient"
              clearable
              filterable
              placeholder="全部 Client"
            >
              <el-option v-for="c in clients" :key="c.id" :label="c.name" :value="c.id" />
            </el-select>
          </el-form-item>
          <el-form-item label="调用状态" class="mcp-filter-status">
            <el-select v-model="filterSuccess" clearable placeholder="全部状态">
              <el-option label="成功" :value="true" />
              <el-option label="失败" :value="false" />
            </el-select>
          </el-form-item>
          <el-form-item label="最近天数" class="mcp-filter-days">
            <div class="mcp-days-control">
              <span class="mcp-days-visible-label">最近天数</span>
              <el-input-number
                v-model="days"
                :min="1"
                :max="90"
                :step="1"
                controls-position="right"
                aria-label="最近天数"
              />
            </div>
          </el-form-item>

          <template #actions>
            <el-tooltip content="重置筛选" placement="top">
              <el-button
                class="mcp-filter-reset"
                :icon="RefreshLeft"
                circle
                native-type="button"
                aria-label="重置筛选"
                @click="resetFilters"
              />
            </el-tooltip>
            <el-button type="primary" native-type="submit" :loading="loading">
              搜索
            </el-button>
          </template>
        </FilterBar>
      </template>

      <el-table :data="rows" :row-key="rowKey" empty-text=" " style="width: 100%">
        <el-table-column prop="createdAt" label="时间" width="170">
          <template #default="{ row }">{{ displayText(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column prop="clientName" label="Client" width="160" show-overflow-tooltip>
          <template #default="{ row }">{{ displayText(row.clientName) }}</template>
        </el-table-column>
        <el-table-column prop="method" label="方法" width="140">
          <template #default="{ row }">
            <code class="mcp-code-chip">{{ row.method }}</code>
          </template>
        </el-table-column>
        <el-table-column prop="toolName" label="Tool" width="160" show-overflow-tooltip>
          <template #default="{ row }">
            <code v-if="row.toolName" class="mcp-code-chip">{{ row.toolName }}</code>
            <span v-else>—</span>
          </template>
        </el-table-column>
        <el-table-column prop="success" label="状态" width="96" align="center">
          <template #default="{ row }">
            <StatusTag
              :label="row.success ? '成功' : '失败'"
              :tone="row.success ? 'success' : 'danger'"
            />
          </template>
        </el-table-column>
        <el-table-column prop="latencyMs" label="耗时" width="96">
          <template #default="{ row }">
            {{ row.latencyMs == null ? '—' : `${row.latencyMs} ms` }}
          </template>
        </el-table-column>
        <el-table-column prop="errorMessage" label="错误" min-width="220" show-overflow-tooltip>
          <template #default="{ row }">{{ displayText(row.errorMessage) }}</template>
        </el-table-column>
        <el-table-column prop="remoteIp" label="IP" width="120">
          <template #default="{ row }">{{ displayText(row.remoteIp) }}</template>
        </el-table-column>
        <el-table-column prop="traceId" label="Trace" width="160">
          <template #default="{ row }">
            <el-button
              v-if="row.traceId"
              size="small"
              link
              type="primary"
              :title="`复制 Trace ${row.traceId}`"
              :aria-label="`复制 Trace ${row.traceId}`"
              @click="copyTrace(row.traceId)"
            >
              {{ row.traceId.slice(0, 12) }}…
            </el-button>
            <span v-else>—</span>
          </template>
        </el-table-column>
      </el-table>

      <template #empty>
        <div class="project-list-empty-state">
          <img src="/智能化.svg" alt="" aria-hidden="true" />
          <div class="project-list-empty-copy">
            <h3>{{ emptyTitle }}</h3>
            <p>{{ emptyDescription }}</p>
          </div>
          <el-button v-if="hasActiveFilters" @click="resetFilters">重置筛选</el-button>
        </div>
      </template>
    </DataTableShell>
  </WorkbenchPage>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Refresh, RefreshLeft, Search } from '@element-plus/icons-vue'
import WorkbenchPage from '@/components/common/WorkbenchPage.vue'
import PageHeader from '@/components/common/PageHeader.vue'
import DataTableShell from '@/components/common/DataTableShell.vue'
import FilterBar from '@/components/common/FilterBar.vue'
import StatusTag from '@/components/common/StatusTag.vue'

import { listMcpClients, pageMcpCallLogs } from '@/api/mcp'
import type { McpCallLog, McpClient } from '@/types/mcp'

const loading = ref(false)
const rows = ref<McpCallLog[]>([])
const clients = ref<McpClient[]>([])

const filterMethod = ref('')
const filterClient = ref<number | undefined>()
const filterSuccess = ref<boolean | undefined>()
const days = ref(7)

const pagination = reactive({ current: 1, size: 50, total: 0 })

const hasActiveFilters = computed(() =>
  Boolean(
    filterMethod.value.trim()
    || filterClient.value != null
    || filterSuccess.value != null
    || days.value !== 7,
  ),
)

const emptyTitle = computed(() =>
  hasActiveFilters.value ? '没有符合条件的调用记录' : '最近 7 天暂无 MCP 调用',
)

const emptyDescription = computed(() =>
  hasActiveFilters.value
    ? '调整方法、Client、状态或时间范围后再试一次。'
    : 'MCP Client 产生调用后，将按时间显示在这里。',
)

function displayText(value: string | null | undefined) {
  return value ? value : '—'
}

function rowKey(row: McpCallLog) {
  if (row.id != null) return String(row.id)
  return [
    row.createdAt ?? '',
    row.clientId ?? '',
    row.method,
    row.toolName ?? '',
    row.traceId ?? '',
    row.remoteIp ?? '',
    row.success ? '1' : '0',
  ].join('|')
}

async function loadClients() {
  try {
    const { data } = await listMcpClients()
    clients.value = data ?? []
  } catch {
    clients.value = []
  }
}

async function reload() {
  loading.value = true
  try {
    const logs = await pageMcpCallLogs({
      current: pagination.current,
      size: pagination.size,
      method: filterMethod.value.trim() || undefined,
      clientId: filterClient.value,
      success: filterSuccess.value,
      days: days.value,
    })
    rows.value = logs.data?.records ?? []
    pagination.total = logs.data?.total ?? 0
  } finally {
    loading.value = false
  }
}

function handleSearch() {
  pagination.current = 1
  return reload()
}

function handlePageSizeChange() {
  pagination.current = 1
  return reload()
}

function resetFilters() {
  filterMethod.value = ''
  filterClient.value = undefined
  filterSuccess.value = undefined
  days.value = 7
  pagination.current = 1
  return reload()
}

function copyTrace(traceId: string) {
  navigator.clipboard.writeText(traceId)
  ElMessage.success('已复制 traceId')
}

onMounted(async () => {
  await loadClients()
  await reload()
})
</script>

<style scoped lang="scss">
.mcp-code-chip {
  display: inline-block;
  max-width: 100%;
  padding: 2px 7px;
  overflow: hidden;
  border-radius: 6px;
  color: var(--text-link);
  background: color-mix(in srgb, var(--surface-glass-selected) 62%, transparent);
  font-family: var(--font-family-mono, ui-monospace, SFMono-Regular, Consolas, monospace);
  font-size: 11px;
  line-height: 17px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.mcp-days-control {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}

.mcp-days-visible-label {
  flex: 0 0 auto;
  color: var(--text-muted);
  font-size: 12px;
  font-weight: 600;
  line-height: 18px;
  white-space: nowrap;
}

.mcp-call-monitor-page {
  :deep(.mcp-call-filter-bar.filter-bar) {
    flex-wrap: nowrap;
    gap: 10px;
  }

  :deep(.mcp-call-filter-bar .filter-bar__fields) {
    flex: 0 1 auto;
    flex-wrap: nowrap;
    gap: 10px;
  }

  :deep(.mcp-call-filter-bar .filter-bar__actions) {
    flex: 0 0 auto;
    margin-left: auto;
  }

  :deep(.mcp-call-filter-bar .el-form-item),
  :deep(.mcp-call-filter-bar .el-form-item:first-child),
  :deep(.mcp-call-filter-bar .el-form-item:not(:first-child)) {
    flex: 0 0 auto !important;
    min-width: 0 !important;
    margin: 0 !important;
  }

  :deep(.mcp-call-filter-bar .el-form-item.mcp-filter-method) {
    width: 168px !important;
  }

  :deep(.mcp-call-filter-bar .el-form-item.mcp-filter-client) {
    width: 148px !important;
  }

  :deep(.mcp-call-filter-bar .el-form-item.mcp-filter-status) {
    width: 120px !important;
  }

  :deep(.mcp-call-filter-bar .el-form-item.mcp-filter-days) {
    width: auto !important;
  }

  :deep(.mcp-call-filter-bar .mcp-filter-method .el-input),
  :deep(.mcp-call-filter-bar .mcp-filter-client .el-select),
  :deep(.mcp-call-filter-bar .mcp-filter-status .el-select),
  :deep(.mcp-call-filter-bar .mcp-filter-client .el-select__wrapper),
  :deep(.mcp-call-filter-bar .mcp-filter-status .el-select__wrapper) {
    width: 100% !important;
  }

  :deep(.mcp-call-filter-bar .mcp-filter-days .el-input-number) {
    width: 108px !important;
  }

  :deep(.mcp-call-filter-bar .mcp-filter-days .el-input-number .el-input__wrapper) {
    min-height: 40px;
    padding: 0 8px 0 12px;
    border-radius: 9px;
    background: color-mix(in srgb, var(--surface-solid-control) 92%, transparent);
    box-shadow: 0 0 0 1px color-mix(in srgb, var(--brand-primary) 18%, transparent) inset;
  }

  :deep(.mcp-call-filter-bar .mcp-filter-reset) {
    width: 40px !important;
    min-width: 40px !important;
    height: 40px;
    min-height: 40px;
    margin: 0;
    color: var(--text-secondary);
    border-color: var(--border-readable);
    background: color-mix(in srgb, var(--surface-solid-control) 94%, transparent);
  }

  :deep(.mcp-call-filter-bar .mcp-filter-reset:hover) {
    color: var(--brand-active);
    border-color: color-mix(in srgb, var(--brand-primary) 30%, transparent);
    background: var(--surface-glass-selected);
  }

  :deep(.mcp-call-filter-bar .filter-bar__actions .el-button--primary) {
    min-width: 74px;
  }
}

@media (max-width: 760px) {
  .mcp-call-monitor-page {
    :deep(.mcp-call-filter-bar.filter-bar) {
      flex-wrap: wrap;
    }

    :deep(.mcp-call-filter-bar .filter-bar__fields) {
      flex-wrap: wrap;
    }

    :deep(.mcp-call-filter-bar .mcp-filter-method),
    :deep(.mcp-call-filter-bar .mcp-filter-client),
    :deep(.mcp-call-filter-bar .mcp-filter-status),
    :deep(.mcp-call-filter-bar .mcp-filter-days),
    :deep(.mcp-call-filter-bar .mcp-filter-days .el-input-number) {
      width: 100%;
    }

    .mcp-days-control {
      width: 100%;
    }
  }
}
</style>
