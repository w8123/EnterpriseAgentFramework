<template>
  <WorkbenchPage class="capability-catalog-page" layout="list">
    <PageHeader
      variant="overview"
      domain="platform"
      eyebrow="Capability Catalog"
      title="能力目录"
      description="查看已接入平台的能力及其来源、调用方式和可用状态。"
    >
      <template #tags>
        <el-tag effect="light">平台范围</el-tag>
        <el-tag v-if="summaryState === 'ready'" type="success" effect="light">{{ summary.available }} 项已启用</el-tag>
        <el-tag v-else-if="summaryState === 'error'" type="danger" effect="light">目录指标不可用</el-tag>
        <el-tag v-else type="info" effect="light">正在读取目录</el-tag>
        <el-tag v-if="summaryState === 'ready' && summary.disabled" type="warning" effect="light">
          {{ summary.disabled }} 项停用
        </el-tag>
      </template>
      <template #actions>
        <el-button :icon="Document" @click="router.push('/capability/review')">
          能力变化
        </el-button>
        <el-tooltip content="刷新目录与指标" placement="top">
          <el-button
            circle
            :icon="Refresh"
            :loading="loading || summaryLoading"
            aria-label="刷新能力目录"
            @click="refreshCatalog"
          />
        </el-tooltip>
      </template>
    </PageHeader>

    <MetricStrip :items="metricItems" density="compact" aria-label="能力目录指标概览" />

    <el-alert
      v-if="listError"
      type="error"
      show-icon
      :closable="false"
      :title="listError"
      class="catalog-load-alert"
    >
      <template #default>
        <el-button link type="primary" @click="loadCapabilities">重新加载能力目录</el-button>
      </template>
    </el-alert>

    <DataTableShell
      v-model:current-page="currentPage"
      v-model:page-size="pageSize"
      class="capability-table-shell project-list-table-shell workbench-list-surface"
      density="compact"
      :loading="loading"
      :empty="listState === 'ready' && capabilities.length === 0"
      :total="listState === 'ready' ? total : 0"
      :page-sizes="[10, 20, 50, 100]"
      @page-change="loadCapabilities"
      @size-change="handleSizeChange"
    >
      <template #toolbar>
        <FilterBar
          class="capability-filter-bar project-list-filter-bar"
          density="compact"
          :loading="loading"
          @query="applyFilters"
          @reset="resetFilters"
        >
          <el-input
            v-model="filterKeyword"
            class="filter-control is-keyword"
            :prefix-icon="Search"
            placeholder="搜索能力名称、稳定标识或说明"
            clearable
          />
          <el-select
            v-model="filterProjectId"
            class="filter-control is-project"
            placeholder="来源项目"
            aria-label="来源项目筛选"
            clearable
            filterable
          >
            <el-option
              v-for="project in projectStore.projects"
              :key="project.id"
              :label="projectStore.projectLabel(project)"
              :value="project.id"
            />
          </el-select>
          <el-select
            v-model="filterSource"
            class="filter-control is-source"
            placeholder="接入方式"
            aria-label="接入方式筛选"
            clearable
          >
            <el-option label="代码内置" value="code" />
            <el-option label="SDK 注册" value="sdk" />
            <el-option label="源码扫描" value="scanner" />
            <el-option label="平台配置" value="manual" />
          </el-select>
          <el-select
            v-model="filterEnabled"
            class="filter-control is-status"
            placeholder="可用状态"
            aria-label="可用状态筛选"
            clearable
          >
            <el-option label="可用" :value="true" />
            <el-option label="已停用" :value="false" />
          </el-select>

          <template #actions>
            <el-tooltip content="重置筛选" placement="top">
              <el-button
                circle
                native-type="button"
                :icon="RefreshLeft"
                aria-label="重置能力筛选"
                @click="resetFilters"
              />
            </el-tooltip>
            <el-button native-type="submit" type="primary" :icon="Search" :loading="loading">
              搜索
            </el-button>
          </template>
        </FilterBar>
      </template>

      <el-table
        v-if="capabilities.length"
        :data="capabilities"
        row-key="name"
        class="capability-table"
        @row-click="openCapabilityDetail"
      >
        <el-table-column label="能力" min-width="280">
          <template #default="{ row }">
            <div class="capability-cell">
              <span class="capability-cell__mark" aria-hidden="true">C</span>
              <span class="capability-cell__copy">
                <strong>{{ capabilityDisplayName(row) }}</strong>
                <small>{{ capabilityStableName(row) }}</small>
                <span>{{ capabilityDescription(row) }}</span>
              </span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="来源" min-width="180">
          <template #default="{ row }">
            <div class="source-cell">
              <strong>{{ row.sourceProjectName || row.projectCode || '平台级能力' }}</strong>
              <StatusTag
                :label="capabilitySourceLabel(row.source, row.sourceLocation)"
                :tone="capabilitySourceTone(row.source, row.sourceLocation)"
              />
            </div>
          </template>
        </el-table-column>
        <el-table-column label="调用入口" min-width="220" show-overflow-tooltip>
          <template #default="{ row }">
            <code class="protocol-cell">{{ capabilityProtocol(row) }}</code>
          </template>
        </el-table-column>
        <el-table-column label="副作用" width="126">
          <template #default="{ row }">
            <StatusTag
              :label="capabilitySideEffectLabel(row.sideEffect)"
              :tone="capabilitySideEffectTone(row.sideEffect)"
            />
          </template>
        </el-table-column>
        <el-table-column label="状态" width="104" align="center">
          <template #default="{ row }">
            <StatusTag
              :label="row.catalogLinkStatus === 'NOT_IN_CATALOG' ? '关联异常' : availabilityLabel(row)"
              :tone="row.catalogLinkStatus === 'NOT_IN_CATALOG' ? 'danger' : row.sourceAvailability && row.sourceAvailability !== 'READY' ? 'warning' : row.enabled ? 'success' : 'neutral'"
              :pulse="row.enabled && row.sourceAvailability === 'READY' && row.catalogLinkStatus !== 'NOT_IN_CATALOG'"
            />
          </template>
        </el-table-column>
        <el-table-column label="操作" width="92" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" :icon="View" @click.stop="openCapabilityDetail(row)">
              查看
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <template #empty>
        <div class="catalog-empty-state">
          <el-empty
            :description="hasActiveFilters ? '没有符合当前条件的能力' : '能力目录还是空的'"
            :image-size="84"
          />
          <p v-if="!hasActiveFilters">先接入业务项目，再由 SDK 同步或扫描纳管能力。</p>
          <div class="catalog-empty-state__actions">
            <el-button v-if="hasActiveFilters" @click="resetFilters">清空筛选</el-button>
            <el-button v-else type="primary" @click="router.push('/registry/projects')">接入业务项目</el-button>
            <el-button @click="router.push('/capability/review')">查看能力变化</el-button>
          </div>
        </div>
      </template>
    </DataTableShell>

    <AppDrawer
      v-model="detailVisible"
      title="能力详情"
      description="目录展示当前已纳管定义；字段修改请回到来源项目，SDK 变化请通过评审应用。"
      size="min(760px, 92vw)"
      destroy-on-close
    >
      <template v-if="selectedCapability">
        <section class="capability-detail-identity">
          <div class="capability-detail-identity__mark" aria-hidden="true">C</div>
          <div class="capability-detail-identity__copy">
            <p>{{ selectedCapability.sourceProjectName || selectedCapability.projectCode || '平台级能力' }}</p>
            <h2>{{ capabilityDisplayName(selectedCapability) }}</h2>
            <code>{{ capabilityStableName(selectedCapability) }}</code>
            <div class="capability-detail-identity__tags">
              <StatusTag
                :label="capabilitySourceLabel(selectedCapability.source, selectedCapability.sourceLocation)"
                :tone="capabilitySourceTone(selectedCapability.source, selectedCapability.sourceLocation)"
              />
              <StatusTag
                :label="capabilitySideEffectLabel(selectedCapability.sideEffect)"
                :tone="capabilitySideEffectTone(selectedCapability.sideEffect)"
              />
              <StatusTag
                :label="selectedCapability.catalogLinkStatus === 'NOT_IN_CATALOG' ? '目录关联异常' : availabilityLabel(selectedCapability)"
                :tone="selectedCapability.catalogLinkStatus === 'NOT_IN_CATALOG' ? 'danger' : selectedCapability.sourceAvailability && selectedCapability.sourceAvailability !== 'READY' ? 'warning' : selectedCapability.enabled ? 'success' : 'neutral'"
              />
            </div>
          </div>
        </section>

        <el-alert
          v-if="selectedCapability.catalogLinkStatus === 'NOT_IN_CATALOG'"
          title="该执行定义没有对应的项目能力目录记录"
          :description="selectedCapability.catalogLinkMessage || '请回到来源项目重新同步并检查能力变化；在关联修复前，不应把它视为正常纳管能力。'"
          type="error"
          :closable="false"
          show-icon
          class="catalog-link-alert"
        />

        <el-alert
          v-if="capabilityDescriptionHasEncodingIssue(selectedCapability)"
          title="来源说明存在编码异常"
          description="平台已保留能力身份和调用契约，但不会将损坏文本伪装成正常说明；请在来源项目修复编码后重新同步。"
          type="warning"
          :closable="false"
          show-icon
          class="catalog-link-alert"
        />

        <el-tabs v-model="detailTab" class="capability-detail-tabs">
          <el-tab-pane label="概览" name="overview">
            <section class="capability-detail-section">
              <h3>它能做什么</h3>
              <p>{{ capabilityDescription(selectedCapability) }}</p>
              <small v-if="selectedCapability.aiDescription && selectedCapability.description">
                来源说明：{{ capabilityDescription({ description: selectedCapability.description }) }}
              </small>
            </section>

            <dl class="capability-fact-grid">
              <div>
                <dt>稳定标识</dt>
                <dd>{{ capabilityStableName(selectedCapability) }}</dd>
              </div>
              <div>
                <dt>来源项目</dt>
                <dd>{{ selectedCapability.sourceProjectName || selectedCapability.projectCode || '平台级能力' }}</dd>
              </div>
              <div>
                <dt>接入方式</dt>
                <dd>{{ capabilitySourceLabel(selectedCapability.source, selectedCapability.sourceLocation) }}</dd>
              </div>
              <div>
                <dt>输入参数</dt>
                <dd>{{ capabilityParameterCount(selectedCapability.parameters) }} 项</dd>
              </div>
              <div class="is-wide">
                <dt>调用地址</dt>
                <dd>{{ capabilityEndpoint(selectedCapability) }}</dd>
              </div>
              <div>
                <dt>请求类型</dt>
                <dd>{{ selectedCapability.requestBodyType || '未声明' }}</dd>
              </div>
              <div>
                <dt>响应类型</dt>
                <dd>{{ selectedCapability.responseType || '未声明' }}</dd>
              </div>
            </dl>
          </el-tab-pane>

          <el-tab-pane :label="`调用契约 · ${capabilityParameterCount(selectedCapability.parameters)}`" name="contract">
            <section class="capability-detail-section">
              <h3>调用协议</h3>
              <div class="protocol-banner">
                <el-tag v-if="selectedCapability.httpMethod" :type="httpMethodTone(selectedCapability.httpMethod)" effect="dark">
                  {{ selectedCapability.httpMethod.toUpperCase() }}
                </el-tag>
                <code>{{ capabilityEndpoint(selectedCapability) }}</code>
              </div>
            </section>

            <el-table
              v-if="selectedCapability.parameters?.length"
              :data="selectedCapability.parameters"
              row-key="name"
              default-expand-all
              :tree-props="{ children: 'children' }"
              class="parameter-table"
            >
              <el-table-column prop="name" label="参数" min-width="180" />
              <el-table-column prop="type" label="类型" width="120" />
              <el-table-column prop="location" label="位置" width="100">
                <template #default="{ row }">{{ row.location || 'body' }}</template>
              </el-table-column>
              <el-table-column label="必填" width="76" align="center">
                <template #default="{ row }">{{ row.required ? '是' : '否' }}</template>
              </el-table-column>
              <el-table-column prop="description" label="说明" min-width="180">
                <template #default="{ row }">{{ row.description || '—' }}</template>
              </el-table-column>
            </el-table>
            <el-empty v-else description="该能力没有声明输入参数" :image-size="72" />
          </el-tab-pane>

          <el-tab-pane label="治理边界" name="governance">
            <el-alert
              title="跨服务引用尚未汇总到目录"
              description="这里不会把缺失数据展示成 0。停用、移除或改变调用契约前，仍需核对 Workflow、Agent 与 MCP 发布修订。"
              type="warning"
              :closable="false"
              show-icon
            />

            <section class="governance-path">
              <article>
                <span>Workflow</span>
                <strong>按稳定标识引用</strong>
                <p>Studio 从可用目录中选择，发布时由 Runtime 校验引用。</p>
              </article>
              <article>
                <span>Agent</span>
                <strong>通过 Workflow 使用</strong>
                <p>Agent 只装配已发布的 Workflow-as-Tool，不直接拥有目录能力。</p>
              </article>
              <article>
                <span>MCP / A2A</span>
                <strong>由控制面解析</strong>
                <p>开放时按发布修订冻结，不以当前目录列表替代发布证据。</p>
              </article>
            </section>

            <section v-if="metadataEntries.length" class="capability-detail-section metadata-section">
              <h3>声明元数据</h3>
              <dl>
                <div v-for="([key, value]) in metadataEntries" :key="key">
                  <dt>{{ key }}</dt>
                  <dd>{{ prettyCapabilityValue(value) }}</dd>
                </div>
              </dl>
            </section>
          </el-tab-pane>
        </el-tabs>
      </template>

      <template #footer>
        <div class="drawer-actions">
          <el-button
            v-if="selectedCapability?.projectCode"
            @click="openSourceProject(selectedCapability.projectCode)"
          >
            查看来源项目
          </el-button>
          <el-button type="primary" @click="router.push('/workflows')">前往 Workflow 编排</el-button>
        </div>
      </template>
    </AppDrawer>
  </WorkbenchPage>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Document, Refresh, RefreshLeft, Search, View } from '@element-plus/icons-vue'
import AppDrawer from '@/components/common/AppDrawer.vue'
import DataTableShell from '@/components/common/DataTableShell.vue'
import FilterBar from '@/components/common/FilterBar.vue'
import MetricStrip from '@/components/common/MetricStrip.vue'
import PageHeader from '@/components/common/PageHeader.vue'
import StatusTag from '@/components/common/StatusTag.vue'
import WorkbenchPage from '@/components/common/WorkbenchPage.vue'
import type { MetricStripItem } from '@/components/common/glassWorkbench'
import { getTools } from '@/api/tool'
import { useProjectStore } from '@/store/project'
import type { ToolInfo, ToolPageResult } from '@/types/tool'
import {
  capabilityDescription,
  capabilityDescriptionHasEncodingIssue,
  capabilityDisplayName,
  capabilityEndpoint,
  capabilityParameterCount,
  capabilityProtocol,
  capabilitySideEffectLabel,
  capabilitySideEffectTone,
  capabilitySourceLabel,
  capabilitySourceTone,
  capabilityStableName,
  parseCapabilityMetadata,
  prettyCapabilityValue,
} from './capabilityGovernance'

const router = useRouter()
const projectStore = useProjectStore()
type LoadState = 'loading' | 'ready' | 'error'

const loading = ref(false)
const summaryLoading = ref(false)
const summaryState = ref<LoadState>('loading')
const listState = ref<LoadState>('loading')
const listError = ref('')
const capabilities = ref<ToolInfo[]>([])
const total = ref(0)
const currentPage = ref(1)
const pageSize = ref(20)
const filterKeyword = ref('')
const filterProjectId = ref<number>()
const filterSource = ref('')
const filterEnabled = ref<boolean>()
const requestSequence = ref(0)

const summary = reactive({ catalog: 0, available: 0, disabled: 0 })

const detailVisible = ref(false)
const detailTab = ref('overview')
const selectedCapability = ref<ToolInfo | null>(null)

const metricItems = computed<MetricStripItem[]>(() => [
  { key: 'catalog', label: '目录能力', value: summaryState.value === 'ready' ? summary.catalog : '—', hint: summaryState.value === 'error' ? '指标读取失败' : '当前已纳管定义', tone: 'brand', iconKey: 'box' },
  { key: 'available', label: '已启用', value: summaryState.value === 'ready' ? summary.available : '—', hint: summaryState.value === 'error' ? '状态未知' : '调用前仍核对来源与发布契约', tone: 'success', iconKey: 'check' },
  { key: 'disabled', label: '已停用', value: summaryState.value === 'ready' ? summary.disabled : '—', hint: summaryState.value === 'error' ? '状态未知' : '保留定义但不可新选', tone: 'warning', iconKey: 'pause' },
  { key: 'projects', label: '平台项目', value: projectStore.projects.length, hint: '接入与来源治理范围', tone: 'info', iconKey: 'link' },
])

const hasActiveFilters = computed(() => Boolean(
  filterKeyword.value.trim()
  || filterProjectId.value
  || filterSource.value
  || filterEnabled.value !== undefined,
))

const capabilityMetadata = computed(() => parseCapabilityMetadata(selectedCapability.value?.capabilityMetadataJson))
const metadataEntries = computed(() => Object.entries(capabilityMetadata.value || {}))

onMounted(async () => {
  await Promise.all([
    projectStore.projects.length ? Promise.resolve() : projectStore.fetchProjects(),
    loadCatalogSummary(),
    loadCapabilities(),
  ])
})

async function loadCatalogSummary() {
  summaryLoading.value = true
  summaryState.value = 'loading'
  try {
    const [catalogResponse, availableResponse, disabledResponse] = await Promise.all([
      getTools({ current: 1, size: 1 }),
      getTools({ current: 1, size: 1, enabled: true }),
      getTools({ current: 1, size: 1, enabled: false }),
    ])
    summary.catalog = pageTotal(catalogResponse.data)
    summary.available = pageTotal(availableResponse.data)
    summary.disabled = pageTotal(disabledResponse.data)
    summaryState.value = 'ready'
  } catch {
    summaryState.value = 'error'
  } finally {
    summaryLoading.value = false
  }
}

async function loadCapabilities() {
  const sequence = ++requestSequence.value
  loading.value = true
  listState.value = 'loading'
  listError.value = ''
  capabilities.value = []
  total.value = 0
  try {
    const { data } = await getTools({
      current: currentPage.value,
      size: pageSize.value,
      keyword: filterKeyword.value.trim() || undefined,
      projectId: filterProjectId.value,
      source: filterSource.value || undefined,
      enabled: filterEnabled.value,
    })
    if (sequence !== requestSequence.value) return
    capabilities.value = Array.isArray(data?.records) ? data.records : []
    total.value = Number(data?.total || 0)
    listState.value = 'ready'
  } catch (error) {
    if (sequence !== requestSequence.value) return
    capabilities.value = []
    total.value = 0
    listState.value = 'error'
    listError.value = error instanceof Error ? error.message : '能力目录加载失败'
  } finally {
    if (sequence === requestSequence.value) loading.value = false
  }
}

async function refreshCatalog() {
  await Promise.all([loadCatalogSummary(), loadCapabilities()])
  if (summaryState.value === 'ready' && listState.value === 'ready') ElMessage.success('能力目录已刷新')
  else ElMessage.warning('能力目录刷新不完整，请重试')
}

async function applyFilters() {
  currentPage.value = 1
  await loadCapabilities()
}

async function resetFilters() {
  filterKeyword.value = ''
  filterProjectId.value = undefined
  filterSource.value = ''
  filterEnabled.value = undefined
  currentPage.value = 1
  await loadCapabilities()
}

async function handleSizeChange() {
  currentPage.value = 1
  await loadCapabilities()
}

function openCapabilityDetail(row: ToolInfo) {
  selectedCapability.value = row
  detailTab.value = 'overview'
  detailVisible.value = true
}

function openSourceProject(projectCode: string) {
  detailVisible.value = false
  router.push({ name: 'RegistryProjectDetail', params: { projectCode } })
}

function httpMethodTone(method?: string | null) {
  const normalized = String(method || '').toUpperCase()
  if (normalized === 'GET') return 'success'
  if (normalized === 'DELETE') return 'danger'
  if (normalized === 'POST') return 'warning'
  return 'info'
}

function pageTotal(data?: ToolPageResult | null) {
  return Number(data?.total || 0)
}
function availabilityLabel(tool: { enabled: boolean; sourceAvailability?: string }) {
  if (!tool.enabled) return '已停用'
  if (tool.sourceAvailability && tool.sourceAvailability !== 'READY') return '来源待确认'
  return tool.sourceAvailability === 'READY' ? '可用' : '待核验'
}
</script>

<style scoped lang="scss">
.capability-table-shell { min-height: 430px; }
.catalog-load-alert { margin: 0; }
.capability-filter-bar { width: 100%; }
.filter-control.is-keyword { width: min(360px, 100%); }
.filter-control.is-project { width: 240px; }
.filter-control.is-source { width: 180px; }
.filter-control.is-status { width: 140px; }

.capability-table { --el-table-row-hover-bg-color: rgb(var(--brand-primary-rgb) / 0.07); }
.capability-table :deep(.el-table__row) { cursor: pointer; }
.capability-cell,
.source-cell,
.capability-detail-identity,
.capability-detail-identity__tags,
.protocol-banner,
.drawer-actions { display: flex; align-items: center; }

.capability-cell { min-width: 0; gap: 11px; }

.capability-cell__mark,
.capability-detail-identity__mark {
  display: grid;
  flex: 0 0 auto;
  place-items: center;
  border: 1px solid rgb(var(--brand-primary-rgb) / 0.24);
  color: var(--brand-primary);
  font-weight: 800;
  background: var(--surface-glass-selected);
}

.capability-cell__mark { width: 34px; height: 34px; border-radius: 11px; font-size: 13px; }

.capability-cell__copy,
.source-cell,
.capability-detail-identity__copy { display: grid; min-width: 0; }

.capability-cell__copy { gap: 2px; }

.capability-cell__copy strong,
.capability-cell__copy small,
.capability-cell__copy > span,
.source-cell strong { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

.capability-cell__copy strong,
.source-cell strong { color: var(--text-primary); font-size: 13px; }

.capability-cell__copy small {
  color: var(--text-secondary);
  font: 11px/1.4 ui-monospace, SFMono-Regular, Consolas, monospace;
}

.capability-cell__copy > span { color: var(--text-muted); font-size: 12px; }
.source-cell { justify-items: start; gap: 6px; }
.protocol-cell { color: var(--text-secondary); font-size: 12px; }

.catalog-empty-state {
  display: grid;
  justify-items: center;
  padding: 28px;
  color: var(--text-muted);
  text-align: center;
}

.catalog-empty-state > p { margin: -12px 0 16px; font-size: 13px; }
.catalog-empty-state__actions,
.drawer-actions { display: flex; flex-wrap: wrap; gap: 8px; }

.capability-detail-identity {
  position: relative;
  isolation: isolate;
  gap: 15px;
  overflow: hidden;
  padding: 18px;
  border: 1px solid var(--border-readable);
  border-radius: var(--radius-lg);
  background:
    radial-gradient(circle at 92% 0, rgb(var(--brand-primary-rgb) / 0.18), transparent 42%),
    var(--surface-glass-selected);
}

.capability-detail-identity__mark { width: 52px; height: 52px; border-radius: 16px; font-size: 20px; }
.capability-detail-identity__copy { gap: 3px; }

.capability-detail-identity__copy p,
.capability-detail-identity__copy h2,
.capability-detail-identity__copy code { margin: 0; }

.capability-detail-identity__copy p { color: var(--text-muted); font-size: 12px; }
.capability-detail-identity__copy h2 { color: var(--text-primary); font-size: 20px; }
.capability-detail-identity__copy code { color: var(--text-secondary); font-size: 12px; }

.capability-detail-identity__tags { flex-wrap: wrap; gap: 6px; margin-top: 7px; }
.capability-detail-tabs { margin-top: var(--section-gap); }
.catalog-link-alert { margin-top: var(--section-gap); }
.capability-detail-section { padding: 14px 0; }

.capability-detail-section h3,
.capability-detail-section p { margin: 0; }

.capability-detail-section h3 { color: var(--text-primary); font-size: 14px; }

.capability-detail-section p,
.capability-detail-section > small {
  display: block;
  margin-top: 7px;
  color: var(--text-secondary);
  font-size: 13px;
  line-height: 1.65;
}

.capability-detail-section > small { color: var(--text-muted); font-size: 12px; }

.capability-fact-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
  margin: 0;
}

.capability-fact-grid > div {
  min-width: 0;
  padding: 11px 12px;
  border: 1px solid var(--border-divider);
  border-radius: var(--radius-md);
  background: var(--surface-glass-control);
}

.capability-fact-grid > .is-wide { grid-column: 1 / -1; }
.capability-fact-grid dt,
.capability-fact-grid dd { margin: 0; }
.capability-fact-grid dt { color: var(--text-muted); font-size: 11px; }

.capability-fact-grid dd {
  overflow-wrap: anywhere;
  margin-top: 5px;
  color: var(--text-primary);
  font-size: 13px;
}

.protocol-banner {
  gap: 10px;
  margin-top: 8px;
  padding: 12px;
  border: 1px solid var(--border-divider);
  border-radius: var(--radius-md);
  background: var(--surface-glass-control);
}

.protocol-banner code { overflow-wrap: anywhere; color: var(--text-secondary); font-size: 12px; }
.parameter-table { margin-top: 4px; }

.governance-path {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px;
  margin-top: var(--section-gap);
}

.governance-path article {
  padding: 12px;
  border: 1px solid var(--border-divider);
  border-radius: var(--radius-md);
  background: var(--surface-glass-control);
}

.governance-path span,
.governance-path strong { display: block; }
.governance-path span { color: var(--brand-primary); font-size: 11px; font-weight: 700; letter-spacing: 0.05em; }
.governance-path strong { margin-top: 4px; color: var(--text-primary); font-size: 13px; }
.governance-path p { margin: 6px 0 0; color: var(--text-muted); font-size: 12px; line-height: 1.55; }

.metadata-section dl { display: grid; gap: 8px; margin: 10px 0 0; }

.metadata-section dl > div {
  display: grid;
  grid-template-columns: minmax(120px, 0.4fr) minmax(0, 1.6fr);
  gap: 12px;
  padding: 9px 0;
  border-bottom: 1px solid var(--border-divider);
}

.metadata-section dt,
.metadata-section dd { margin: 0; }
.metadata-section dt { color: var(--text-muted); font-size: 12px; }

.metadata-section dd {
  overflow-wrap: anywhere;
  color: var(--text-secondary);
  font: 12px/1.55 ui-monospace, SFMono-Regular, Consolas, monospace;
  white-space: pre-wrap;
}

.drawer-actions { justify-content: flex-end; }

@media (max-width: 900px) {
  .filter-control.is-project,
  .filter-control.is-source,
  .filter-control.is-status { width: min(100%, 220px); }
  .governance-path { grid-template-columns: 1fr; }
}

@media (max-width: 560px) {
  .capability-fact-grid { grid-template-columns: 1fr; }
  .capability-fact-grid > .is-wide { grid-column: auto; }
  .metadata-section dl > div { grid-template-columns: 1fr; gap: 4px; }
}
</style>
