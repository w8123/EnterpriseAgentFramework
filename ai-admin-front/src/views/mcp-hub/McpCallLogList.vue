<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { Connection, Filter, Search } from '@element-plus/icons-vue'
import AppDialog from '@/components/common/AppDialog.vue'
import MetricStrip from '@/components/common/MetricStrip.vue'
import type { MetricStripItem } from '@/components/common/glassWorkbench'
import { getMcpCallLog, listMcpCallLogs, listMcpClients, listMcpPublications } from '@/api/mcp'
import type { McpCallLogEntry, McpClientView, McpPublication } from '@/types/mcp'

const numberFormatter = new Intl.NumberFormat('zh-CN')
const loading = ref(false)
const loadError = ref('')
const publicationLoadFailed = ref(false)
const advancedFiltersOpen = ref(false)
const rows = ref<McpCallLogEntry[]>([])
const total = ref(0)
const publications = ref<McpPublication[]>([])
const clients = ref<McpClientView[]>([])
const query = reactive({
  direction: '',
  publicationId: undefined as number | undefined,
  clientId: undefined as number | undefined,
  method: '',
  toolName: '',
  success: '' as '' | 'true' | 'false',
  errorCategory: '',
  days: 7,
  limit: 50,
  offset: 0,
})

const methodOptions = ['initialize', 'tools/list', 'tools/call']

const pageSuccessCount = computed(() => rows.value.filter((row) => row.success === true).length)
const pageFailureCount = computed(() => rows.value.filter((row) => row.success === false).length)
const pageLatencySamples = computed(() => rows.value
  .map((row) => row.latencyMs)
  .filter((value): value is number => value != null && Number.isFinite(value))
  .sort((left, right) => left - right))
const pageP95Latency = computed(() => {
  const samples = pageLatencySamples.value
  if (!samples.length) return null
  return samples[Math.max(0, Math.ceil(samples.length * 0.95) - 1)]
})
const metricItems = computed<MetricStripItem[]>(() => [
  {
    key: 'matched',
    label: '匹配流水',
    value: numberFormatter.format(total.value),
    hint: `近 ${query.days} 天`,
    iconKey: 'api-discovery',
    tone: 'brand',
  },
  {
    key: 'success',
    label: '当前页成功',
    value: numberFormatter.format(pageSuccessCount.value),
    hint: rows.value.length ? `${((pageSuccessCount.value / rows.value.length) * 100).toFixed(0)}%` : '暂无样本',
    iconKey: 'agent-enabled',
    tone: 'success',
  },
  {
    key: 'failed',
    label: '当前页失败',
    value: numberFormatter.format(pageFailureCount.value),
    hint: pageFailureCount.value ? '建议排查' : '运行稳定',
    iconKey: 'scan',
    tone: pageFailureCount.value ? 'danger' : 'neutral',
  },
  {
    key: 'p95',
    label: '当前页 P95',
    value: pageP95Latency.value == null ? '—' : `${pageP95Latency.value} ms`,
    hint: pageLatencySamples.value.length ? `${pageLatencySamples.value.length} 个延迟样本` : '暂无样本',
    iconKey: 'model-ready',
    tone: 'brand',
  },
])

const hasAdvancedFilters = computed(() => Boolean(
  query.direction || query.clientId || query.method || query.errorCategory,
))

function formatDateTime(value?: string | null): string {
  if (!value) return '-'
  const parsed = new Date(value)
  return Number.isNaN(parsed.getTime()) ? value : parsed.toLocaleString('zh-CN', { hour12: false })
}

function formatJson(value?: string | null): string {
  if (!value) return '-'
  try {
    return JSON.stringify(JSON.parse(value), null, 2)
  } catch {
    return value
  }
}

function directionLabel(direction?: string | null) {
  return direction === 'INBOUND' ? '入向' : '出向'
}

function resultLabel(success?: boolean | null) {
  if (success === true) return '成功'
  if (success === false) return '失败'
  return '未知'
}

function resultClass(success?: boolean | null) {
  if (success === true) return 'is-success'
  if (success === false) return 'is-failed'
  return 'is-unknown'
}

async function loadPublications() {
  publicationLoadFailed.value = false
  try {
    const { data } = await listMcpPublications({ limit: 200, offset: 0 })
    publications.value = data?.items ?? []
  } catch {
    publications.value = []
    publicationLoadFailed.value = true
  }
}

watch(() => query.publicationId, async (publicationId) => {
  query.clientId = undefined
  clients.value = []
  if (publicationId) {
    try {
      const { data } = await listMcpClients(publicationId)
      clients.value = data ?? []
    } catch {
      clients.value = []
    }
  }
})

async function reload() {
  loading.value = true
  loadError.value = ''
  try {
    const { data } = await listMcpCallLogs({
      direction: query.direction || undefined,
      publicationId: query.publicationId,
      clientId: query.clientId,
      method: query.method || undefined,
      toolName: query.toolName || undefined,
      success: query.success === '' ? undefined : query.success === 'true',
      errorCategory: query.errorCategory || undefined,
      days: query.days,
      limit: query.limit,
      offset: query.offset,
    })
    rows.value = data?.items ?? []
    total.value = data?.total ?? 0
  } catch (error) {
    rows.value = []
    total.value = 0
    loadError.value = error instanceof Error ? error.message : '调用流水加载失败'
  } finally {
    loading.value = false
  }
}

function applyFilters() {
  query.offset = 0
  reload()
}

function resetFilters() {
  query.direction = ''
  query.publicationId = undefined
  query.clientId = undefined
  query.method = ''
  query.toolName = ''
  query.success = ''
  query.errorCategory = ''
  query.days = 7
  query.offset = 0
  advancedFiltersOpen.value = false
  reload()
}

// 流水详情包含正文，需要 mcp-hub:payload:read 权限。
const detailOpen = ref(false)
const detailLoading = ref(false)
const detailEntry = ref<McpCallLogEntry | null>(null)
const detailError = ref('')
const payloadDenied = ref(false)

async function openDetail(row: McpCallLogEntry) {
  if (row.id == null) return
  detailOpen.value = true
  detailLoading.value = true
  detailError.value = ''
  payloadDenied.value = false
  detailEntry.value = null
  try {
    const { data } = await getMcpCallLog(row.id)
    detailEntry.value = data
  } catch (error) {
    const status = (error as { response?: { status?: number } })?.response?.status
    const message = (error as { response?: { data?: { message?: string } }; message?: string })?.response?.data?.message
      ?? (error instanceof Error ? error.message : '请求失败')
    detailError.value = message
    if (status === 403) {
      payloadDenied.value = true
    }
  } finally {
    detailLoading.value = false
  }
}

onMounted(() => {
  void loadPublications()
  void reload()
})
</script>

<template>
  <section class="call-log-page">
    <h2 class="visually-hidden">调用流水</h2>

    <MetricStrip class="call-log-metrics" :items="metricItems" aria-label="调用流水指标概览" />

    <el-card class="call-log-card workbench-list-surface" shadow="never">
      <div class="call-log-toolbar">
        <el-input
          v-model="query.toolName"
          class="toolbar-keyword"
          clearable
          :prefix-icon="Search"
          placeholder="搜索工具名称"
          @keyup.enter="applyFilters"
        />
        <el-select
          v-model="query.publicationId"
          clearable
          filterable
          :placeholder="publicationLoadFailed ? '发布单元暂不可用' : '发布单元'"
        >
          <el-option
            v-for="publication in publications"
            :key="publication.id"
            :label="publication.name"
            :value="publication.id"
          />
        </el-select>
        <el-select v-model="query.success" clearable placeholder="调用结果">
          <el-option label="成功" value="true" />
          <el-option label="失败" value="false" />
        </el-select>
        <el-select v-model="query.days" placeholder="时间窗口">
          <el-option label="近 7 天" :value="7" />
          <el-option label="近 14 天" :value="14" />
          <el-option label="近 30 天" :value="30" />
        </el-select>
        <div class="toolbar-actions">
          <el-tooltip :content="advancedFiltersOpen ? '收起高级筛选' : '更多筛选'" placement="top">
            <el-button
              class="toolbar-more"
              :class="{ 'is-active': advancedFiltersOpen || hasAdvancedFilters }"
              :icon="Filter"
              aria-label="更多筛选"
              @click="advancedFiltersOpen = !advancedFiltersOpen"
            />
          </el-tooltip>
          <el-button class="toolbar-reset" @click="resetFilters">重置</el-button>
          <el-button class="toolbar-search" type="primary" :icon="Search" :loading="loading" @click="applyFilters">
            搜索
          </el-button>
        </div>
      </div>

      <el-collapse-transition>
        <div v-if="advancedFiltersOpen" class="advanced-filters">
          <el-select v-model="query.direction" clearable placeholder="调用方向">
            <el-option label="出向（外部 Client 调用平台）" value="OUTBOUND" />
            <el-option label="入向（平台调用外部，M2）" value="INBOUND" />
          </el-select>
          <el-select
            v-model="query.clientId"
            clearable
            filterable
            placeholder="Client 凭证"
            :disabled="!query.publicationId"
          >
            <el-option
              v-for="client in clients"
              :key="client.id"
              :label="client.name"
              :value="client.id"
            />
          </el-select>
          <el-select v-model="query.method" clearable filterable allow-create placeholder="JSON-RPC 方法">
            <el-option v-for="method in methodOptions" :key="method" :label="method" :value="method" />
          </el-select>
          <el-input v-model="query.errorCategory" clearable placeholder="错误分层，如 AUTH / RUNTIME" @keyup.enter="applyFilters" />
          <span class="advanced-filter-note">列表已脱敏，正文按权限与保留期开放</span>
        </div>
      </el-collapse-transition>

      <el-alert
        v-if="loadError"
        class="call-log-error"
        type="error"
        :closable="false"
        show-icon
        :title="`调用流水加载失败：${loadError}`"
      />

      <div class="call-log-table-shell">
        <el-table v-loading="loading" :data="rows" class="call-log-table" max-height="480">
          <el-table-column prop="createdAt" label="时间" width="150">
            <template #default="{ row }"><span class="muted">{{ formatDateTime(row.createdAt) }}</span></template>
          </el-table-column>
          <el-table-column label="方向" width="76">
            <template #default="{ row }">
              <span class="direction-pill" :class="row.direction === 'INBOUND' ? 'is-inbound' : 'is-outbound'">
                <i />{{ directionLabel(row.direction) }}
              </span>
            </template>
          </el-table-column>
          <el-table-column label="Client 凭证" min-width="120">
            <template #default="{ row }">
              <div class="stacked-cell">
                <strong>{{ row.clientName || (row.clientId != null ? `Client #${row.clientId}` : '未绑定凭证') }}</strong>
                <span v-if="row.projectCode">{{ row.projectCode }}</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="调用内容" min-width="180">
            <template #default="{ row }">
              <div class="stacked-cell call-target-cell">
                <strong>{{ row.method || '未知方法' }}</strong>
                <span>{{ row.toolName || '未指定工具' }}</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="结果 / 耗时" width="116">
            <template #default="{ row }">
              <div class="result-cell">
                <span class="result-pill" :class="resultClass(row.success)"><i />{{ resultLabel(row.success) }}</span>
                <small>{{ row.latencyMs == null ? '耗时 —' : `${row.latencyMs} ms` }}</small>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="错误分层" width="104">
            <template #default="{ row }">
              <span :class="row.errorCategory ? 'error-category' : 'muted'">{{ row.errorCategory || '-' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="链路证据" min-width="180">
            <template #default="{ row }">
              <div class="stacked-cell evidence-cell">
                <strong>{{ row.traceId || '无 TraceId' }}</strong>
                <span>{{ row.remoteIp ? `来源 ${row.remoteIp}` : '来源 IP 未记录' }}</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="68" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" :disabled="row.id == null" @click="openDetail(row)">详情</el-button>
            </template>
          </el-table-column>

          <template #empty>
            <div class="call-log-empty">
              <span class="call-log-empty__icon"><el-icon><Connection /></el-icon></span>
              <div>
                <h3>还没有调用流水</h3>
                <p>发布 MCP 服务并完成首次调用后，这里会记录结果、耗时与链路证据。</p>
              </div>
            </div>
          </template>
        </el-table>
      </div>

      <div v-if="total > 0" class="table-footer">
        <span>共 {{ total }} 条</span>
        <el-pagination
          :current-page="Math.floor(query.offset / query.limit) + 1"
          :page-size="query.limit"
          :total="total"
          class="call-log-pagination"
          background
          layout="prev, pager, next, sizes"
          :page-sizes="[20, 50, 100, 200]"
          @current-change="(page: number) => { query.offset = (page - 1) * query.limit; reload() }"
          @size-change="(size: number) => { query.limit = size; query.offset = 0; reload() }"
        />
      </div>
    </el-card>

    <AppDialog v-model="detailOpen" title="调用流水详情" width="min(860px, 94vw)">
      <div v-loading="detailLoading" class="detail-body">
        <el-alert
          v-if="payloadDenied"
          type="warning"
          :closable="false"
          show-icon
          title="权限不足：查看请求/响应正文需要 mcp-hub:payload:read 权限"
          :description="detailError"
        />
        <el-alert v-else-if="detailError" type="error" :closable="false" show-icon :title="`详情加载失败：${detailError}`" />
        <template v-if="detailEntry">
          <el-descriptions :column="2" border size="small">
            <el-descriptions-item label="时间">{{ formatDateTime(detailEntry.createdAt) }}</el-descriptions-item>
            <el-descriptions-item label="方向">{{ directionLabel(detailEntry.direction) }}</el-descriptions-item>
            <el-descriptions-item label="发布单元">#{{ detailEntry.publicationId ?? '-' }}</el-descriptions-item>
            <el-descriptions-item label="凭证">{{ detailEntry.clientName || `#${detailEntry.clientId ?? '-'}` }}</el-descriptions-item>
            <el-descriptions-item label="方法">{{ detailEntry.method || '-' }}</el-descriptions-item>
            <el-descriptions-item label="工具">{{ detailEntry.toolName || '-' }}</el-descriptions-item>
            <el-descriptions-item label="结果">
              <el-tag :type="detailEntry.success ? 'success' : 'danger'" size="small">{{ resultLabel(detailEntry.success) }}</el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="延迟">{{ detailEntry.latencyMs == null ? '-' : `${detailEntry.latencyMs} ms` }}</el-descriptions-item>
            <el-descriptions-item label="错误分层">{{ detailEntry.errorCategory || '-' }}</el-descriptions-item>
            <el-descriptions-item label="来源 IP">{{ detailEntry.remoteIp || '-' }}</el-descriptions-item>
            <el-descriptions-item label="TraceId">{{ detailEntry.traceId || '-' }}</el-descriptions-item>
            <el-descriptions-item label="RunId">{{ detailEntry.runId || '-' }}</el-descriptions-item>
          </el-descriptions>

          <el-alert
            v-if="detailEntry.errorMessage"
            type="error"
            :closable="false"
            show-icon
            title="错误信息"
            :description="detailEntry.errorMessage"
          />

          <div class="payload-block">
            <h4>请求正文</h4>
            <pre class="payload-json">{{ formatJson(detailEntry.requestBody) }}</pre>
          </div>
          <div class="payload-block">
            <h4>响应正文</h4>
            <pre class="payload-json">{{ formatJson(detailEntry.responseBody) }}</pre>
          </div>
        </template>
      </div>
    </AppDialog>
  </section>
</template>

<style scoped lang="scss">
.call-log-page {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: var(--layout-page-gap);
}

.visually-hidden {
  position: absolute;
  width: 1px;
  height: 1px;
  padding: 0;
  overflow: hidden;
  clip: rect(0, 0, 0, 0);
  white-space: nowrap;
  border: 0;
}

.call-log-metrics {
  min-height: 96px;
  padding: 0;
  overflow: hidden;
  border: 1px solid rgb(var(--brand-primary-rgb) / 0.16);
  border-radius: 8px;
  background: color-mix(in srgb, var(--surface-solid-panel) 72%, transparent);
  box-shadow: 0 18px 42px rgb(var(--brand-primary-rgb) / 0.052);
}

.call-log-metrics :deep(.metric-strip__item) {
  min-height: 94px;
  padding: 16px 22px;
  gap: 16px;
}

.call-log-metrics :deep(.metric-strip__item + .metric-strip__item) {
  padding-inline-start: 22px;
}

.call-log-card {
  overflow: hidden;
  border: 1px solid rgb(var(--brand-primary-rgb) / 0.14);
  border-radius: 16px;
  background: color-mix(in srgb, var(--surface-solid-panel) 76%, transparent);
  box-shadow: 0 18px 42px -16px rgb(var(--brand-primary-rgb) / 0.08);
  backdrop-filter: blur(18px);
}

.call-log-card :deep(.el-card__body) {
  padding: 0;
}

.call-log-toolbar {
  display: grid;
  grid-template-columns: minmax(240px, 2.2fr) minmax(150px, 1.15fr) minmax(112px, 0.78fr) minmax(112px, 0.78fr) auto;
  align-items: center;
  gap: 12px;
  margin: 28px 28px 0;
  padding: 9px 14px;
  border: 1px solid rgb(var(--brand-primary-rgb) / 0.11);
  border-radius: 12px;
  background: var(--brand-soft-bg, var(--surface-glass-control));
}

.call-log-toolbar :deep(.el-input__wrapper),
.call-log-toolbar :deep(.el-select__wrapper),
.advanced-filters :deep(.el-input__wrapper),
.advanced-filters :deep(.el-select__wrapper) {
  min-height: 38px;
  padding: 0 14px;
  border-radius: 9px;
  background: var(--surface-solid-control);
  box-shadow: 0 0 0 1px rgb(var(--brand-primary-rgb) / 0.18) inset;
}

.call-log-toolbar :deep(.el-input__inner),
.call-log-toolbar :deep(.el-select__placeholder),
.call-log-toolbar :deep(.el-select__selected-item),
.advanced-filters :deep(.el-input__inner),
.advanced-filters :deep(.el-select__placeholder),
.advanced-filters :deep(.el-select__selected-item) {
  color: var(--text-muted);
  font-size: 13px;
}

.toolbar-actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 10px;
}

.toolbar-more,
.toolbar-reset,
.toolbar-search {
  height: 40px;
  min-height: 40px;
  margin: 0;
  border-radius: 10px;
  font-weight: 700;
}

.toolbar-more {
  width: 40px;
  padding: 0;
  color: var(--text-secondary);
  border-color: rgb(var(--brand-primary-rgb) / 0.18);
  background: var(--surface-solid-control);
}

.toolbar-more.is-active {
  color: var(--brand-active);
  border-color: rgb(var(--brand-primary-rgb) / 0.32);
  background: var(--surface-glass-selected);
}

.toolbar-reset {
  width: 72px;
  color: var(--text-primary);
  border-color: rgb(var(--brand-primary-rgb) / 0.18);
  background: var(--surface-solid-control);
}

.toolbar-search {
  width: 88px;
  border-color: transparent;
  background: var(--brand-primary-gradient);
  box-shadow: 0 10px 18px -10px rgb(var(--brand-primary-rgb) / 0.24);
}

.advanced-filters {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  align-items: center;
  gap: 12px;
  margin: 12px 28px 0;
  padding: 12px 14px;
  border: 1px solid rgb(var(--brand-primary-rgb) / 0.1);
  border-radius: 12px;
  background: color-mix(in srgb, var(--surface-glass-control) 76%, transparent);
}

.advanced-filter-note {
  grid-column: 1 / -1;
  color: var(--text-muted);
  font-size: 12px;
}

.call-log-error {
  margin: 12px 28px 0;
}

.call-log-table-shell {
  display: flex;
  min-width: 0;
  flex-direction: column;
  margin: 22px 28px 0;
  overflow: hidden;
  border: 1px solid rgb(var(--brand-primary-rgb) / 0.12);
  border-radius: 13px;
  background: color-mix(in srgb, var(--surface-solid-panel) 74%, transparent);
}

.call-log-table {
  --el-table-header-bg-color: color-mix(in srgb, var(--brand-selected-bg) 42%, var(--surface-glass-control));
  --el-table-row-hover-bg-color: color-mix(in srgb, var(--surface-solid-panel) 84%, transparent);
  --el-table-border-color: rgb(var(--brand-primary-rgb) / 0.1);
  background: transparent;
}

.call-log-table :deep(.el-table__inner-wrapper::before) { height: 0; }
.call-log-table :deep(.cell) { padding: 0 8px; }

.call-log-table :deep(th.el-table__cell) {
  height: 44px;
  color: var(--brand-active);
  background: color-mix(in srgb, var(--brand-selected-bg) 42%, var(--surface-glass-control)) !important;
  font-size: 12px;
  font-weight: 700;
}

.call-log-table :deep(td.el-table__cell) {
  height: 62px;
  color: var(--text-secondary);
  background: color-mix(in srgb, var(--surface-solid-panel) 84%, transparent) !important;
  border-bottom-color: rgb(var(--brand-primary-rgb) / 0.1);
}

.call-log-table :deep(.el-table-fixed-column--right) {
  background-color: color-mix(in srgb, var(--surface-solid-panel) 92%, transparent) !important;
}

.muted,
.stacked-cell span,
.result-cell small { color: var(--text-muted); }
.muted { font-size: 12px; }

.stacked-cell,
.result-cell {
  display: flex;
  min-width: 0;
  flex-direction: column;
  align-items: flex-start;
  gap: 4px;
}

.stacked-cell strong,
.stacked-cell span {
  display: block;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.stacked-cell strong {
  color: var(--text-primary);
  font-size: 13px;
  font-weight: 700;
}

.stacked-cell span,
.result-cell small { font-size: 11px; }
.call-target-cell strong,
.evidence-cell strong { font-family: Consolas, 'JetBrains Mono', monospace; }
.evidence-cell strong { color: var(--text-secondary); font-size: 11px; }

.direction-pill,
.result-pill {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  font-weight: 700;
  white-space: nowrap;
}

.direction-pill i,
.result-pill i {
  width: 7px;
  height: 7px;
  flex: 0 0 7px;
  border-radius: 50%;
  background: currentColor;
}

.direction-pill.is-outbound { color: var(--brand-active); }
.direction-pill.is-inbound { color: var(--status-info); }
.result-pill.is-success { color: var(--status-success); }
.result-pill.is-failed,
.error-category { color: var(--status-danger); }
.result-pill.is-unknown { color: var(--status-neutral); }
.error-category { font-size: 12px; font-weight: 700; }

.call-log-table :deep(.el-table__empty-block) {
  min-height: 236px;
  background: color-mix(in srgb, var(--surface-solid-panel) 72%, transparent);
}

.call-log-empty {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 18px;
  padding: 34px 20px;
  text-align: left;
}

.call-log-empty__icon {
  display: inline-flex;
  width: 58px;
  height: 58px;
  flex: 0 0 58px;
  align-items: center;
  justify-content: center;
  border: 1px solid rgb(var(--brand-primary-rgb) / 0.24);
  border-radius: 16px;
  color: var(--brand-active);
  background: var(--surface-glass-selected);
  box-shadow: var(--inner-highlight);
  font-size: 26px;
}

.call-log-empty h3,
.call-log-empty p { margin: 0; }
.call-log-empty h3 { color: var(--text-primary); font-size: 16px; }

.call-log-empty p {
  max-width: 520px;
  margin-top: 6px;
  color: var(--text-muted);
  font-size: 12px;
  line-height: 1.55;
}

.table-footer {
  display: flex;
  min-height: 64px;
  flex-shrink: 0;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 14px 28px 16px;
  border-top: 1px solid rgb(var(--brand-primary-rgb) / 0.12);
  color: var(--text-muted);
  background: color-mix(in srgb, var(--surface-glass-control) 72%, transparent);
  font-size: 13px;
}

.table-footer :deep(.call-log-pagination.el-pagination) {
  --el-pagination-button-width: 34px;
  --el-pagination-button-height: 34px;
  --el-pagination-hover-color: var(--brand-active);
  gap: 6px;
}

.table-footer :deep(.call-log-pagination.is-background .btn-prev),
.table-footer :deep(.call-log-pagination.is-background .btn-next),
.table-footer :deep(.call-log-pagination.is-background .el-pager li:not(.is-active)) {
  min-width: 34px;
  height: 34px;
  margin: 0;
  border: 0;
  border-radius: 8px;
  background: var(--surface-glass-control);
  box-shadow: none;
}

.table-footer :deep(.call-log-pagination.is-background .el-pager li.is-active) {
  color: var(--brand-active);
  border-radius: 8px;
  background: var(--surface-glass-selected);
  font-weight: 700;
}

.detail-body {
  display: flex;
  min-height: 120px;
  flex-direction: column;
  gap: 12px;
}

.payload-block h4 { margin: 0 0 6px; color: var(--text-primary); font-size: 13px; }

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
}

@media (max-width: 1200px) {
  .call-log-toolbar { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .toolbar-keyword,
  .toolbar-actions { grid-column: 1 / -1; }
  .toolbar-actions { justify-self: end; }
  .advanced-filters { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .advanced-filter-note { grid-column: 1 / -1; }
}

@media (max-width: 760px) {
  .call-log-toolbar,
  .advanced-filters {
    grid-template-columns: minmax(0, 1fr);
    margin-right: 16px;
    margin-left: 16px;
  }

  .call-log-toolbar { margin-top: 16px; }
  .toolbar-keyword,
  .toolbar-actions,
  .advanced-filter-note { grid-column: auto; }
  .toolbar-actions { width: 100%; justify-self: stretch; }
  .toolbar-reset,
  .toolbar-search { flex: 1; width: auto; }
  .call-log-table-shell,
  .call-log-error { margin-right: 16px; margin-left: 16px; }
  .call-log-table-shell { margin-top: 16px; }
  .call-log-empty { align-items: center; flex-direction: column; text-align: center; }

  .table-footer {
    align-items: flex-start;
    flex-direction: column;
    overflow-x: auto;
    padding-right: 16px;
    padding-left: 16px;
  }
}
</style>
