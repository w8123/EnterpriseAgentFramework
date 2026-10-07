<template>
  <section class="http-api-catalog" aria-label="HTTP API 目录">
    <ProjectScopeState v-if="!projectId" title="请选择一个项目查看 API"
      :message="scope?.feedbackMessage.value || '请从左侧当前范围选择具体项目。'"
      :status="scope?.status.value === 'blocked' ? 'blocked' : 'pending'"
      :can-retry="scope?.recoveryAction.value === 'retry-catalog' || scope?.recoveryAction.value === 'retry-normalization'"
      :retrying="scope?.isCatalogLoading.value" @retry="scope?.retryScope()" />

    <el-alert v-else-if="error" type="error" :closable="false" show-icon :title="error">
      <template #default><el-button link type="primary" @click="load">重新加载</el-button></template>
    </el-alert>

    <DataTableShell v-if="projectId" class="workbench-list-surface" :current-page="page" :page-size="size" :total="total"
      :loading="loading" :empty="!loading && !error && rows.length === 0"
      density="compact" :page-sizes="[10, 20, 50, 100]"
      @page-change="changePage" @size-change="changeSize">
      <template #toolbar>
        <FilterBar density="compact" :loading="loading" @query="applyFilters" @reset="resetFilters">
          <el-input v-model="draft.keyword" class="filter-control filter-keyword" clearable
            placeholder="搜索 method、路径或稳定标识" aria-label="搜索 API"
            @compositionstart="composing = true" @compositionend="composing = false" @clear="applyFilters" />
          <el-select v-model="draft.environment" clearable placeholder="环境" aria-label="环境筛选"
            class="filter-control">
            <el-option v-for="item in environments" :key="item" :label="item" :value="item" />
          </el-select>
          <el-select v-model="draft.method" clearable placeholder="HTTP method" aria-label="HTTP method 筛选"
            class="filter-control">
            <el-option v-for="item in methods" :key="item" :label="item" :value="item" />
          </el-select>
          <el-select v-model="draft.sourceStatus" clearable placeholder="来源状态" aria-label="来源状态筛选"
            class="filter-control">
            <el-option v-for="item in statuses" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
          <template #actions>
            <el-tooltip content="刷新 HTTP API" placement="top">
              <el-button circle :icon="Refresh" :loading="loading" aria-label="刷新 HTTP API" @click="load" />
            </el-tooltip>
            <el-button native-type="button" @click="resetFilters">重置</el-button>
            <el-button native-type="submit" type="primary" :loading="loading" :disabled="composing">搜索</el-button>
          </template>
        </FilterBar>
      </template>

      <el-table v-if="rows.length" :data="rows" row-key="id" class="api-table">
        <el-table-column label="API / 完整路径" min-width="350">
          <template #default="{ row }">
            <router-link class="api-identity" :to="detailLocation(row.id)">
              <span class="api-method">{{ row.httpMethod }}</span>
              <span class="api-route">{{ row.routeTemplate }}</span>
            </router-link>
            <small class="api-subline">{{ row.qualifiedName }}</small>
            <dl class="api-compact-summary" aria-label="API 关键状态">
              <div><dt>来源</dt><dd>{{ row.sourceKinds.map(sourceKindLabel).join('、') || '暂无来源' }} · {{ statusLabel(row.sourceStatus) }}</dd></div>
              <div><dt>连接</dt><dd>{{ connectionLabel(row.connectionStatus) }}</dd></div>
              <div><dt>最近试调用</dt><dd>{{ latestTrialLabel(row) }}</dd></div>
              <div><dt>下一步</dt><dd>{{ nextAction(row) }}</dd></div>
            </dl>
          </template>
        </el-table-column>
        <el-table-column label="环境" width="126" prop="environment" />
        <el-table-column label="来源" min-width="170">
          <template #default="{ row }">
            <span>{{ row.sourceKinds.map(sourceKindLabel).join('、') || '暂无来源' }}</span>
            <small class="api-subline">{{ row.activeSourceCount }} 条活动来源</small>
          </template>
        </el-table-column>
        <el-table-column label="来源与接纳" width="155">
          <template #default="{ row }"><el-tag :type="statusTone(row.sourceStatus)" effect="light">
            {{ statusLabel(row.sourceStatus) }}</el-tag></template>
        </el-table-column>
        <el-table-column label="连接" width="120">
          <template #default="{ row }">{{ connectionLabel(row.connectionStatus) }}</template>
        </el-table-column>
        <el-table-column label="最近试调用" min-width="180">
          <template #default="{ row }">{{ latestTrialLabel(row) }}
            <small class="api-subline api-status-subline">{{ row.latestInvocationAt ? '历史记录' : '仅显示当前账号' }}</small>
          </template>
        </el-table-column>
        <el-table-column label="下一步" min-width="190">
          <template #default="{ row }">{{ nextAction(row) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="92" fixed="right">
          <template #default="{ row }">
            <router-link class="api-view-link" :to="detailLocation(row.id)">查看</router-link>
          </template>
        </el-table-column>
      </el-table>
      <template #empty>
        <div class="api-empty"><el-empty :description="hasFilters ? '没有符合当前条件的 API' : '当前项目还没有 API'" />
          <el-button v-if="hasFilters" @click="resetFilters">清空筛选</el-button>
          <router-link v-else to="/registry/projects">前往项目管理检查扫描或同步</router-link>
        </div>
      </template>
    </DataTableShell>
  </section>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { Refresh } from '@element-plus/icons-vue'
import ProjectScopeState from '@/components/common/ProjectScopeState.vue'
import DataTableShell from '@/components/common/DataTableShell.vue'
import FilterBar from '@/components/common/FilterBar.vue'
import { usePageProjectScope } from '@/composables/usePageProjectScope'
import { useCatalogQuery } from '@/composables/useCatalogQuery'
import { httpApiDetailLocation } from '@/views/capability/businessCapabilityRoutes'
import { platformSessionId } from '@/auth/platformSession'
import { listHttpApis } from '@/api/httpApi'
import type { HttpApiSourceStatus, HttpApiSummary } from '@/types/httpApi'

const route = useRoute()
const query = useCatalogQuery('api', ['keyword', 'environment', 'method', 'sourceStatus'])
const scope = usePageProjectScope()
const projectId = computed(() => scope?.canLoadData.value ? scope.requestParams.value?.projectId ?? null : null)
const rows = ref<HttpApiSummary[]>([])
const total = ref(0)
const loading = ref(false)
const error = ref('')
const composing = ref(false)
let sequence = 0
const methods = ['GET', 'POST', 'PUT', 'PATCH', 'DELETE']
const environments = computed(() => Array.from(new Set((scope?.readableProjects.value || [])
  .filter((project) => project.id === projectId.value)
  .map((project) => project.environment).filter((value): value is string => !!value))))
const statuses: Array<{ value: HttpApiSourceStatus; label: string }> = [
  { value: 'DISCOVERED', label: '契约待接纳' }, { value: 'ACCEPTED', label: '契约已接纳' },
  { value: 'CONTRACT_DRIFT', label: '契约已变化' }, { value: 'CONFLICT', label: '来源冲突' },
  { value: 'SOURCE_UNCONFIRMED', label: '来源待确认' }, { value: 'SOURCE_MISSING', label: '来源已缺失' },
]
const page = query.page
const size = query.size
const hasFilters = query.hasFilters
const draft = query.draft
async function load() {
  const id = projectId.value
  const current = ++sequence
  rows.value = []; total.value = 0; error.value = ''
  if (!id) { loading.value = false; return }
  loading.value = true
  try {
    const { data } = await listHttpApis({ projectId: id, environment: query.filters.value.environment || undefined,
      keyword: query.filters.value.keyword || undefined, method: query.filters.value.method || undefined,
      sourceStatus: query.filters.value.sourceStatus || undefined, current: page.value, size: size.value })
    if (current !== sequence) return
    if (!Array.isArray(data.records) || data.records.some(row => row.projectId !== id || !Number.isSafeInteger(row.id) || row.id <= 0)
      || !Number.isSafeInteger(data.total) || data.total < data.records.length) {
      throw new Error('Invalid HTTP API owner page')
    }
    rows.value = data.records
    total.value = data.total
  } catch {
    if (current === sequence) error.value = 'API 目录加载失败；请检查项目访问权限或重试'
  } finally { if (current === sequence) loading.value = false }
}
function applyFilters() {
  if (composing.value) return
  void query.apply()
}
function resetFilters() {
  void query.reset()
}
function changePage(next: number) { void query.changePage(next) }
function changeSize(next: number) { void query.changeSize(next) }
function detailLocation(id: number) { return httpApiDetailLocation(id, route.query) }
function statusLabel(status: HttpApiSourceStatus) { return statuses.find((item) => item.value === status)?.label || status }
function statusTone(status: HttpApiSourceStatus) {
  return status === 'ACCEPTED' ? 'success' : status === 'DISCOVERED' ? 'warning' : 'danger'
}
function sourceKindLabel(kind: string) {
  return ({ OPENAPI_SCAN: 'OpenAPI', CONTROLLER_SCAN: 'Controller', STARTER_MVC: 'Starter MVC' } as Record<string, string>)[kind] || kind
}
function nextAction(row: HttpApiSummary) {
  if (!row.sourceConfirmed) return row.sourceReason || '重新扫描或同步来源'
  if (row.sourceStatus === 'DISCOVERED' || row.sourceStatus === 'CONTRACT_DRIFT') return '查看差异并接纳契约'
  if (row.sourceStatus === 'ACCEPTED') return row.connectionStatus === 'UNCONFIGURED'
    ? '配置接入' : '查看连接与试调用条件'
  return '检查来源状态'
}
function connectionLabel(status: HttpApiSummary['connectionStatus']) {
  const labels: Record<string, string> = { SAVED: '连接已保存', UNCONFIGURED: '连接未配置',
    UNAVAILABLE: '暂不可查询' }
  return labels[status || 'UNAVAILABLE']
}
function latestTrialLabel(row: HttpApiSummary) {
  if (row.connectionStatus === 'UNAVAILABLE') return '暂不可查询'
  if (!row.latestInvocationStatus) return '暂无本账号记录'
  if (row.latestInvocationStatus === 'SUCCEEDED') return `HTTP ${row.latestHttpStatus || '2xx'} 成功`
  if (row.latestInvocationStatus === 'HTTP_FAILED') return `HTTP ${row.latestHttpStatus || '非 2xx'} 失败`
  return ({ UNKNOWN: '结果未知', NOT_DISPATCHED: '未派发', ACCEPTED: '已建立',
    DISPATCHING: '正在调用' } as Record<string, string>)[row.latestInvocationStatus] || '调用已结束'
}
watch([query.requestKey, () => scope?.requestKey.value, projectId, platformSessionId], () => {
  sequence += 1; rows.value = []; total.value = 0; error.value = ''; loading.value = false
  void load()
}, { flush: 'sync' })
onMounted(() => { void load() })
onBeforeUnmount(() => { sequence += 1 })
</script>

<style scoped lang="scss">
.http-api-catalog { display: flex; min-width: 0; flex-direction: column; gap: var(--layout-page-gap); }
.filter-control { width: min(190px, 100%); }
.filter-keyword { width: min(290px, 100%); }
.api-identity { display: flex; align-items: center; gap: 10px; min-width: 0; color: var(--text-primary); text-decoration: none; }
.api-identity:hover .api-route, .api-view-link:hover { color: var(--brand-active); text-decoration: underline; }
.api-identity:focus-visible, .api-view-link:focus-visible { outline: 2px solid var(--brand-active); outline-offset: 3px; }
.api-method { flex: 0 0 auto; border: 1px solid var(--border-readable); border-radius: var(--radius-sm);
  padding: 3px 7px; color: var(--brand-active); font: 700 11px/1.3 var(--font-mono, monospace); }
.api-route { overflow-wrap: anywhere; font: 600 13px/1.5 var(--font-mono, monospace); }
.api-subline { display: block; margin: 5px 0 0 50px; color: var(--text-muted); font-size: 12px; overflow-wrap: anywhere; }
.api-status-subline { margin-left: 0; }
.api-view-link { color: var(--brand-active); text-decoration: none; font-weight: 600; }
.api-empty { display: grid; justify-items: center; padding: 20px; }
.api-compact-summary { display: none; }
@media (max-width: 960px) {
  .api-compact-summary { display: grid; gap: 4px; margin: 8px 0 0 50px; font-size: 12px; line-height: 1.45; }
  .api-compact-summary div { display: flex; gap: 7px; min-width: 0; }
  .api-compact-summary dt { flex: 0 0 72px; color: var(--text-muted); }
  .api-compact-summary dd { min-width: 0; margin: 0; color: var(--text-primary); overflow-wrap: anywhere; }
  .api-table .api-subline { display: -webkit-box; -webkit-box-orient: vertical; -webkit-line-clamp: 2; overflow: hidden; }
}
@media (max-width: 720px) { .filter-control, .filter-keyword { width: 100%; } }
</style>
