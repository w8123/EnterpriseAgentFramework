<script setup lang="ts">
import { onMounted, reactive, ref, computed } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search } from '@element-plus/icons-vue'
import { LiquidGlassButton, LiquidGlassEmptyState, LiquidGlassFilterBar, LiquidGlassInput, LiquidGlassSelect, LiquidGlassTable } from '@/components/common/liquid-glass'
import { listMcpEmployeeConnections, revokeMcpEmployeeConnection } from '@/api/mcp'
import type { McpEmployeeConnection } from '@/types/mcp'
import { usePlatformCan } from '@/auth/usePlatformCan'
import { PLATFORM_PERMISSION_MCP_CREDENTIAL_MANAGE, PLATFORM_PERMISSION_MCP_EMPLOYEE_CONNECTION_MANAGE } from '@/auth/platformAccess'
const { canAny } = usePlatformCan()
const canRevoke = computed(() => canAny(PLATFORM_PERMISSION_MCP_EMPLOYEE_CONNECTION_MANAGE, PLATFORM_PERMISSION_MCP_CREDENTIAL_MANAGE))

const router = useRouter()
const loading = ref(false)
const loadError = ref('')
const rows = ref<McpEmployeeConnection[]>([])
const total = ref(0)
const query = reactive({ keyword: '', projectCode: '', state: 'ACTIVE', limit: 20, offset: 0 })
const hasFilters = computed(() => Boolean(query.keyword.trim() || query.projectCode.trim() || query.state !== 'ACTIVE'))

const stateOptions = [
  { value: 'ACTIVE', label: '使用中' },
  { value: 'REVOKED', label: '已断开' },
  { value: 'EXPIRED', label: '已过期' },
]
const stateLabels: Record<string, string> = { ACTIVE: '使用中', REVOKED: '已断开', EXPIRED: '已过期' }
const stateTones: Record<string, 'success' | 'info' | 'warning'> = { ACTIVE: 'success', REVOKED: 'info', EXPIRED: 'warning' }
const toolLabels: Record<string, string> = { CLAUDE_CODE: 'Claude Code', CODEX: 'Codex', OTHER: '其他 AI 工具' }

function systemNames(row: McpEmployeeConnection) {
  return row.systems.map((system) => system.name).join('、') || '-'
}

function formatDateTime(value?: string | null): string {
  if (!value) return '-'
  const parsed = new Date(value)
  return Number.isNaN(parsed.getTime()) ? value : parsed.toLocaleString('zh-CN', { hour12: false })
}

function extractErrorMessage(error: unknown): string {
  const anyError = error as { response?: { data?: { message?: string; detail?: string } }; message?: string }
  return anyError?.response?.data?.message
    || anyError?.response?.data?.detail
    || anyError?.message
    || '请求失败'
}

async function reload() {
  loading.value = true
  loadError.value = ''
  try {
    const { data } = await listMcpEmployeeConnections({
      keyword: query.keyword.trim() || undefined,
      projectCode: query.projectCode.trim() || undefined,
      state: query.state || undefined,
      limit: query.limit,
      offset: query.offset,
    })
    rows.value = data?.items ?? []
    total.value = data?.total ?? 0
  } catch (error) {
    rows.value = []
    total.value = 0
    loadError.value = extractErrorMessage(error)
  } finally {
    loading.value = false
  }
}

function applyFilters() {
  query.offset = 0
  reload()
}

function resetFilters() {
  query.keyword = ''
  query.projectCode = ''
  query.state = 'ACTIVE'
  applyFilters()
}

function changePage(page: number, size: number) {
  query.limit = size
  query.offset = (page - 1) * size
  reload()
}

async function revoke(row: McpEmployeeConnection) {
  try {
    await ElMessageBox.confirm(
      `确认断开「${row.userName || row.externalUserId}」的 ${toolLabels[row.toolKind] ?? row.toolKind} 连接？断开后对方的 AI 工具立即无法再调用业务系统，需要的话可以在对话面板里重新生成。`,
      '断开员工连接',
      { type: 'warning', confirmButtonText: '断开' },
    )
  } catch {
    return
  }
  try {
    await revokeMcpEmployeeConnection(row.id)
    ElMessage.success('已断开')
    await reload()
  } catch (error) {
    ElMessage.error(`断开失败：${extractErrorMessage(error)}`)
  }
}

function openCalls(row: McpEmployeeConnection) {
  router.push({ path: '/mcp-hub/call-logs', query: { connectionId: String(row.id) } })
}

onMounted(reload)
</script>

<template>
  <section class="employee-connection-page mcp-list-page">
    <LiquidGlassTable
      class="workbench-list-surface"
      :current-page="Math.floor(query.offset / query.limit) + 1"
      :page-size="query.limit"
      :total="total"
      :page-sizes="[20, 50, 100]"
      pagination-label="员工连接分页"
      :loading="loading"
      :empty="!loading && rows.length === 0"
      @change="changePage"
    >
      <template #empty>
        <LiquidGlassEmptyState v-if="loadError" compact title="连接加载失败" :description="loadError">
          <LiquidGlassButton @click="reload">重试</LiquidGlassButton>
        </LiquidGlassEmptyState>
        <LiquidGlassEmptyState v-else-if="hasFilters" compact title="没有匹配的连接">
          <LiquidGlassButton link type="primary" @click="resetFilters">重置筛选</LiquidGlassButton>
        </LiquidGlassEmptyState>
        <LiquidGlassEmptyState v-else compact title="暂无使用中的连接" />
      </template>
      <template #toolbar>
        <LiquidGlassFilterBar :loading="loading" @query="applyFilters" @reset="resetFilters">
          <LiquidGlassInput
            v-model="query.keyword"
            :prefix-icon="Search"
            clearable
            placeholder="员工姓名或业务系统账号"
            aria-label="搜索员工"
            @clear="applyFilters"
          />
          <LiquidGlassInput
            v-model="query.projectCode"
            class="system-filter"
            clearable
            placeholder="业务系统编码"
            aria-label="业务系统编码"
            @clear="applyFilters"
          />
          <LiquidGlassSelect v-model="query.state" class="state-filter" clearable placeholder="全部状态" dropdown-title="连接状态" aria-label="连接状态" @change="applyFilters">
            <el-option v-for="option in stateOptions" :key="option.value" :label="option.label" :value="option.value" />
          </LiquidGlassSelect>
        </LiquidGlassFilterBar>
      </template>

      <el-table :data="rows" stripe empty-text=" ">
        <el-table-column label="员工" min-width="150">
          <template #default="{ row }">
            <div class="cell-stack">
              <strong>{{ row.userName || row.externalUserId }}</strong>
              <small>{{ row.appId }} 账号 {{ row.externalUserId }}</small>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="AI 工具" width="120">
          <template #default="{ row }">{{ toolLabels[row.toolKind] ?? row.toolKind }}</template>
        </el-table-column>
        <el-table-column label="接入的系统" min-width="160" show-overflow-tooltip>
          <template #default="{ row }">{{ systemNames(row) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="stateTones[row.state] ?? 'info'" size="small">{{ stateLabels[row.state] ?? row.state }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="最近使用" width="170">
          <template #default="{ row }">{{ row.lastUsedAt ? formatDateTime(row.lastUsedAt) : '还未使用' }}</template>
        </el-table-column>
        <el-table-column label="到期时间" width="170">
          <template #default="{ row }">{{ formatDateTime(row.expiresAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="130">
          <template #default="{ row }">
            <el-button link type="primary" @click="openCalls(row)">调用记录</el-button>
            <el-button v-if="canRevoke && row.state === 'ACTIVE'" link type="danger" @click="revoke(row)">断开</el-button>
          </template>
        </el-table-column>
      </el-table>
    </LiquidGlassTable>
  </section>
</template>

<style scoped lang="scss">
.employee-connection-page {
  display: flex;
  flex-direction: column;
  gap: var(--layout-page-gap);
}

.workbench-list-surface :deep(.filter-bar__fields > .liquid-glass-control.state-filter),
.workbench-list-surface :deep(.filter-bar__fields > .liquid-glass-control.system-filter) {
  flex: 0 1 160px;
  max-width: 180px;
}

.cell-stack strong,
.cell-stack small {
  display: block;
}

.cell-stack strong {
  color: var(--text-primary);
}

.cell-stack small {
  margin-top: 3px;
  overflow: hidden;
  color: var(--text-muted);
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

@media (max-width: 760px) {
  .workbench-list-surface :deep(.filter-bar__fields > .liquid-glass-control.state-filter),
  .workbench-list-surface :deep(.filter-bar__fields > .liquid-glass-control.system-filter) {
    max-width: none;
  }
}
</style>
