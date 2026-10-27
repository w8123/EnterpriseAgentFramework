<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Search } from '@element-plus/icons-vue'
import AppDialog from '@/components/common/AppDialog.vue'
import McpListToolbar from './McpListToolbar.vue'
import { LiquidGlassButton, LiquidGlassEmptyState, LiquidGlassFilterBar, LiquidGlassInput, LiquidGlassNotice, LiquidGlassSelect, LiquidGlassTable } from '@/components/common/liquid-glass'
import { approveMcpWriteApproval, getMcpWriteApproval, listMcpWriteApprovals, rejectMcpWriteApproval } from '@/api/mcp'
import type { McpWriteApproval } from '@/types/mcp'
import { usePlatformCan } from '@/auth/usePlatformCan'
import { PLATFORM_PERMISSION_MCP_WRITE_APPROVAL_MANAGE } from '@/auth/platformAccess'

const { can } = usePlatformCan()
const canDecide = computed(() => can(PLATFORM_PERMISSION_MCP_WRITE_APPROVAL_MANAGE))
const emit = defineEmits<{ pendingCount: [count: number | null] }>()

const route = useRoute()
const router = useRouter()
const loading = ref(false)
const loadError = ref('')
const rows = ref<McpWriteApproval[]>([])
const total = ref(0)
const pendingCount = ref(0)
const query = reactive({ keyword: '', projectCode: '', state: 'PENDING', limit: 20, offset: 0 })
const hasFilters = computed(() => Boolean(query.keyword.trim() || query.projectCode.trim() || query.state !== 'PENDING'))

const detailOpen = ref(false)
const detailLoading = ref(false)
const detail = ref<McpWriteApproval | null>(null)
const comment = ref('')
const deciding = ref<'' | 'approve' | 'reject'>('')

const stateOptions = [
  { value: 'PENDING', label: '待审批' },
  { value: 'SUCCEEDED', label: '已通过并执行' },
  { value: 'FAILED', label: '已通过但执行失败' },
  { value: 'REJECTED', label: '已驳回' },
  { value: 'EXPIRED', label: '已过期' },
]
const stateLabels: Record<string, string> = {
  PENDING: '待审批',
  EXECUTING: '执行中',
  SUCCEEDED: '已执行',
  FAILED: '执行失败',
  REJECTED: '已驳回',
  EXPIRED: '已过期',
}
const stateTones: Record<string, 'success' | 'info' | 'warning' | 'danger' | 'primary'> = {
  PENDING: 'warning',
  EXECUTING: 'primary',
  SUCCEEDED: 'success',
  FAILED: 'danger',
  REJECTED: 'info',
  EXPIRED: 'info',
}
const toolLabels: Record<string, string> = { CLAUDE_CODE: 'Claude Code', CODEX: 'Codex', OTHER: '其他 AI 工具' }
const errorLabels: Record<string, string> = {
  MCP_APPROVAL_CONNECTION_INACTIVE: '员工的 AI 工具连接已断开或过期',
  MCP_APPROVAL_SYSTEM_UNAVAILABLE: '员工在该系统已不可用',
  MCP_APPROVAL_TOOL_CHANGED: '能力已下线或合同已变化',
  MCP_APPROVAL_ACL_DENIED: '员工现在没有这个能力的调用权限',
  MCP_CAPABILITY_BUSINESS_FAILED: '业务系统返回业务失败',
}

function systemName(row: McpWriteApproval) {
  return row.systemName || row.projectCode
}

function formatDateTime(value?: string | null): string {
  if (!value) return '-'
  const parsed = new Date(value)
  return Number.isNaN(parsed.getTime()) ? value : parsed.toLocaleString('zh-CN', { hour12: false })
}

function formatJson(value: unknown): string {
  if (value == null) return '-'
  return typeof value === 'string' ? value : JSON.stringify(value, null, 2)
}

/** One-line preview of the frozen arguments for the list. */
function argumentPreview(value: unknown): string {
  if (value == null) return '-'
  if (typeof value !== 'object') return String(value)
  const entries = Object.entries(value as Record<string, unknown>)
  if (entries.length === 0) return '（无参数）'
  return entries.map(([key, item]) => `${key}=${typeof item === 'object' ? JSON.stringify(item) : String(item)}`).join('，')
}

function errorLabel(code?: string | null): string {
  if (!code) return '-'
  return errorLabels[code] ? `${errorLabels[code]}（${code}）` : code
}

function extractErrorMessage(error: unknown): string {
  const anyError = error as { response?: { data?: { message?: string; detail?: string } }; message?: string }
  return anyError?.response?.data?.detail
    || anyError?.response?.data?.message
    || anyError?.message
    || '请求失败'
}

async function reload() {
  loading.value = true
  loadError.value = ''
  try {
    const { data } = await listMcpWriteApprovals({
      keyword: query.keyword.trim() || undefined,
      projectCode: query.projectCode.trim() || undefined,
      state: query.state || undefined,
      limit: query.limit,
      offset: query.offset,
    })
    rows.value = data?.items ?? []
    total.value = data?.total ?? 0
    pendingCount.value = data?.pendingCount ?? 0
    emit('pendingCount', pendingCount.value)
  } catch (error) {
    rows.value = []
    total.value = 0
    loadError.value = extractErrorMessage(error)
    emit('pendingCount', null)
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
  query.state = 'PENDING'
  applyFilters()
}

function changePage(page: number, size: number) {
  query.limit = size
  query.offset = (page - 1) * size
  reload()
}

async function openDetail(row: McpWriteApproval) {
  detail.value = row
  comment.value = ''
  detailOpen.value = true
  detailLoading.value = true
  try {
    const { data } = await getMcpWriteApproval(row.id)
    detail.value = data
  } catch (error) {
    ElMessage.error(`详情加载失败：${extractErrorMessage(error)}`)
  } finally {
    detailLoading.value = false
  }
}

async function decide(kind: 'approve' | 'reject') {
  if (!detail.value) return
  deciding.value = kind
  try {
    const action = kind === 'approve' ? approveMcpWriteApproval : rejectMcpWriteApproval
    const { data } = await action(detail.value.id, comment.value.trim() || undefined)
    detail.value = data
    if (kind === 'reject') ElMessage.success('已驳回，员工的 AI 工具查询时会看到原因')
    else if (data.state === 'SUCCEEDED') ElMessage.success('已通过，并以员工身份执行成功')
    else ElMessage.warning(`已通过，但执行失败：${errorLabel(data.errorCode)}`)
    await reload()
  } catch (error) {
    ElMessage.error(`处理失败：${extractErrorMessage(error)}`)
    await reload()
  } finally {
    deciding.value = ''
  }
}

function openRun(runId: string) {
  router.push({ path: '/run-center', query: { runId } })
}

onMounted(async () => {
  await reload()
  const id = Number(route.query.id)
  if (Number.isFinite(id) && id > 0) {
    await openDetail({ id } as McpWriteApproval)
  }
})
</script>

<template>
  <section class="write-approval-page mcp-list-page">
    <McpListToolbar label="审批记录" :count="total">
      <template #meta><el-tag v-if="pendingCount > 0" type="warning" size="small" round>{{ pendingCount }} 项待审批</el-tag></template>
    </McpListToolbar>

    <LiquidGlassTable
      class="workbench-list-surface"
      :current-page="Math.floor(query.offset / query.limit) + 1"
      :page-size="query.limit"
      :total="total"
      :page-sizes="[20, 50, 100]"
      pagination-label="写操作审批分页"
      :loading="loading"
      :empty="!loading && rows.length === 0"
      @change="changePage"
    >
      <template #empty>
        <LiquidGlassEmptyState v-if="loadError" compact title="审批记录加载失败" :description="loadError">
          <LiquidGlassButton @click="reload">重试</LiquidGlassButton>
        </LiquidGlassEmptyState>
        <LiquidGlassEmptyState v-else-if="hasFilters" compact title="没有匹配的审批记录">
          <LiquidGlassButton link type="primary" @click="resetFilters">重置筛选</LiquidGlassButton>
        </LiquidGlassEmptyState>
        <LiquidGlassEmptyState v-else compact title="暂无待审批操作" />
      </template>
      <template #toolbar>
        <LiquidGlassFilterBar :loading="loading" @query="applyFilters" @reset="resetFilters">
          <LiquidGlassInput
            v-model="query.keyword"
            :prefix-icon="Search"
            clearable
            placeholder="员工、账号或工具名"
            aria-label="搜索审批单"
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
          <LiquidGlassSelect v-model="query.state" class="state-filter" clearable placeholder="全部状态" dropdown-title="审批状态" aria-label="审批状态" @change="applyFilters">
            <el-option v-for="option in stateOptions" :key="option.value" :label="option.label" :value="option.value" />
          </LiquidGlassSelect>
        </LiquidGlassFilterBar>
      </template>

      <el-table :data="rows" stripe empty-text=" " @row-click="openDetail">
        <el-table-column label="提交时间" width="170">
          <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="员工" min-width="140">
          <template #default="{ row }">
            <div class="cell-stack">
              <strong>{{ row.userName || row.externalUserId }}</strong>
              <small>{{ toolLabels[row.toolKind] ?? row.toolKind ?? '-' }}</small>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="要做的操作" min-width="220">
          <template #default="{ row }">
            <div class="cell-stack">
              <strong>{{ row.toolName }}</strong>
              <small>{{ systemName(row) }}<template v-if="row.arguments != null"> · {{ argumentPreview(row.arguments) }}</template></small>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="stateTones[row.state] ?? 'info'" size="small">{{ stateLabels[row.state] ?? row.state }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="审批人" width="120">
          <template #default="{ row }">{{ row.decidedBy || '-' }}</template>
        </el-table-column>
        <el-table-column label="操作" width="90">
          <template #default="{ row }">
            <el-button link type="primary" @click.stop="openDetail(row)">{{ canDecide && row.state === 'PENDING' ? '审批' : '查看' }}</el-button>
          </template>
        </el-table-column>
      </el-table>
    </LiquidGlassTable>

    <AppDialog v-model="detailOpen" title="写操作审批" width="min(760px, 94vw)">
      <div v-loading="detailLoading" class="detail-body">
        <template v-if="detail && detail.toolName">
          <el-descriptions :column="2" border size="small">
            <el-descriptions-item label="员工">{{ detail.userName || detail.externalUserId }}（账号 {{ detail.externalUserId }}）</el-descriptions-item>
            <el-descriptions-item label="AI 工具">{{ toolLabels[detail.toolKind ?? ''] ?? detail.toolKind ?? '-' }}</el-descriptions-item>
            <el-descriptions-item label="业务系统">{{ systemName(detail) }}</el-descriptions-item>
            <el-descriptions-item label="状态">
              <el-tag :type="stateTones[detail.state] ?? 'info'" size="small">{{ stateLabels[detail.state] ?? detail.state }}</el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="能力" :span="2">
              <strong>{{ detail.toolName }}</strong>
              <span v-if="detail.toolDescription" class="muted"> · {{ detail.toolDescription }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="提交时间">{{ formatDateTime(detail.createdAt) }}</el-descriptions-item>
            <el-descriptions-item label="过期时间">{{ formatDateTime(detail.expiresAt) }}</el-descriptions-item>
            <template v-if="detail.decidedAt">
              <el-descriptions-item label="审批人">{{ detail.decidedBy || '-' }}</el-descriptions-item>
              <el-descriptions-item label="审批时间">{{ formatDateTime(detail.decidedAt) }}</el-descriptions-item>
            </template>
            <el-descriptions-item v-if="detail.decisionComment" label="审批备注" :span="2">{{ detail.decisionComment }}</el-descriptions-item>
            <el-descriptions-item v-if="detail.errorCode" label="失败原因" :span="2">{{ errorLabel(detail.errorCode) }}</el-descriptions-item>
            <el-descriptions-item v-if="detail.runId" label="运行记录" :span="2">
              <el-button link type="primary" @click="openRun(detail.runId)">{{ detail.runId }}</el-button>
            </el-descriptions-item>
          </el-descriptions>

          <div class="payload-block">
            <h4>参数（提交时冻结，通过后按这些参数执行）</h4>
            <pre v-if="detail.arguments != null" class="payload-json">{{ formatJson(detail.arguments) }}</pre>
            <LiquidGlassNotice v-else type="info" source="backdrop" title="查看参数需要审批权限或 MCP 载荷查看权限" />
          </div>
          <div v-if="detail.result != null" class="payload-block">
            <h4>执行结果</h4>
            <pre class="payload-json">{{ formatJson(detail.result) }}</pre>
          </div>

          <template v-if="canDecide && detail.state === 'PENDING'">
            <el-input
              v-model="comment"
              type="textarea"
              :rows="2"
              maxlength="500"
              show-word-limit
              placeholder="备注（可选），驳回时建议写明原因，员工的 AI 工具会看到"
              aria-label="审批备注"
            />
          </template>
        </template>
      </div>
      <template v-if="canDecide && detail?.state === 'PENDING'" #footer>
        <div class="decision-actions">
          <LiquidGlassButton :loading="deciding === 'reject'" :disabled="deciding === 'approve'" @click="decide('reject')">驳回</LiquidGlassButton>
          <LiquidGlassButton type="primary" :loading="deciding === 'approve'" :disabled="deciding === 'reject'" @click="decide('approve')">通过并执行</LiquidGlassButton>
        </div>
      </template>
    </AppDialog>
  </section>
</template>

<style scoped lang="scss">
.write-approval-page {
  display: flex;
  flex-direction: column;
  gap: var(--layout-page-gap);
}

.workbench-list-surface :deep(.filter-bar__fields > .liquid-glass-control.state-filter),
.workbench-list-surface :deep(.filter-bar__fields > .liquid-glass-control.system-filter) {
  flex: 0 1 160px;
  max-width: 180px;
}

.workbench-list-surface :deep(.el-table__row) {
  cursor: pointer;
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

.detail-body {
  display: flex;
  min-height: 120px;
  flex-direction: column;
  gap: 12px;
}

.muted {
  color: var(--text-muted);
}

.payload-block h4 {
  margin: 0 0 6px;
  color: var(--text-primary);
  font-size: 13px;
}

.payload-json {
  max-height: 260px;
  overflow: auto;
  margin: 0;
  padding: 10px;
  border-radius: var(--radius-md);
  color: var(--text-primary);
  background: var(--surface-glass-control);
  font-family: Consolas, 'JetBrains Mono', monospace;
  font-size: 12px;
  white-space: pre-wrap;
  word-break: break-all;
}

.decision-actions {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
}

@media (max-width: 760px) {
  .section-intro {
    align-items: stretch;
    flex-direction: column;
  }

  .workbench-list-surface :deep(.filter-bar__fields > .liquid-glass-control.state-filter),
  .workbench-list-surface :deep(.filter-bar__fields > .liquid-glass-control.system-filter) {
    max-width: none;
  }
}
</style>
