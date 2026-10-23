<script setup lang="ts">
import { LiquidGlassNotice } from '@/components/common/liquid-glass'
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { ArrowRight, Close, Search } from '@element-plus/icons-vue'
import type { ProjectToolInfo, SourceModuleSummary } from '@/types/scanProject'
import type { SemanticDoc, SemanticDocIndex } from '@/types/semanticDoc'
import type { ToolParameter } from '@/types/tool'
import type { ApiGovernanceAdvice, ToolModuleGroup } from '../composables/useSourceSummary'
import SourceObservationDetailDrawer from './SourceObservationDetailDrawer.vue'
import SourceOwnerLink from './SourceOwnerLink.vue'

interface ParameterRow extends ToolParameter { _key: string; children?: ParameterRow[] }
type ToolLinkTagType = 'success' | 'warning' | 'danger' | 'info'
const props = withDefaults(defineProps<{
  loading: boolean
  projectId: number
  focusObservationId?: number
  tools: ProjectToolInfo[]
  canWrite?: boolean
  canReadOwner?: boolean
  generatingKeys?: string[]
  stageAdvice: ApiGovernanceAdvice
  visibleToolModuleGroups: ToolModuleGroup[]
  total: number
  projectTotal: number
  modules: SourceModuleSummary[]
  current: number
  pageSize: number
  keyword?: string
  moduleFilter?: string
  toolDocMap: Record<number, SemanticDocIndex>
  semanticLoading?: boolean
  semanticError?: string
  sensitiveScanStarting: boolean
  sensitiveTaskPolling: boolean
  sensitiveStatusError: string
  exportScanToolsExcelLoading: boolean
  toolDetailLoading: Record<number, boolean>
  toolDetailError: Record<number, string>
  rescanSourceLoading: Record<number, boolean>
  parameterRows: (parameters: ToolParameter[] | null | undefined, prefix?: string) => ParameterRow[]
  renderMd: (content: string | null | undefined) => string
  scanToolRowClassName: (data: { row: ProjectToolInfo }) => string
  toolParameterCount: (row: ProjectToolInfo) => number
  toolLinkLabel: (row: ProjectToolInfo) => string
  toolLinkTagType: (row: ProjectToolInfo) => ToolLinkTagType
}>(), { canWrite: true, canReadOwner: false, generatingKeys: () => [], semanticLoading: false, semanticError: '', keyword: '', moduleFilter: '' })
const interfaceCollapseActive = defineModel<string[]>('interfaceCollapseActive', { required: true })
const emit = defineEmits<{
  startSensitiveDataScan: []
  retrySensitiveStatus: []
  exportExcel: []
  toolExpandChange: [row: ProjectToolInfo, expanded: boolean]
  openDiff: [row: ProjectToolInfo]
  rescanFromSource: [row: ProjectToolInfo]
  regenerateTool: [row: ProjectToolInfo]
  openEditDoc: [doc: SemanticDoc]
  search: [keyword: string]
  filterModule: [module: string]
  changePage: [page: number]
  emptyPrimaryAction: []
  emptySecondaryAction: []
}>()

const selectedId = ref<number | null>(null)
const detailVisible = ref(false)
const selected = computed(() => props.tools.find(row => row.scanToolId === selectedId.value) || null)
const selectedSemanticBusy = computed(() => {
  if (!selected.value) return false
  const doc = props.toolDocMap[selected.value.scanToolId]
  const key = `${doc?.subjectType || 'SOURCE_OBSERVATION'}:${doc?.subjectId || selected.value.scanToolId}`
  return props.generatingKeys.includes(key)
})
const searchInput = ref<HTMLInputElement>()
const searchDraft = ref('')
const composing = ref(false)
const searchCollapsedGroups = ref<string[]>([])
const recordGroups = computed(() => props.visibleToolModuleGroups.filter(group => group.tools.length > 0))
let searchTimer: ReturnType<typeof setTimeout> | undefined
onBeforeUnmount(() => { clearTimeout(searchTimer) })
watch(() => props.keyword, keyword => { searchDraft.value = keyword; searchCollapsedGroups.value = [] }, { immediate: true })
function commitSearch(value: string) {
  clearTimeout(searchTimer)
  if (value.trim() !== props.keyword) emit('search', value.trim())
}
function updateSearch(event: Event) {
  searchDraft.value = (event.target as HTMLInputElement).value
  clearTimeout(searchTimer)
  if (!composing.value && !(event as InputEvent).isComposing) searchTimer = setTimeout(() => commitSearch(searchDraft.value), 300)
}
function finishSearch(event: CompositionEvent) {
  composing.value = false
  updateSearch(event)
}
function startComposition() { composing.value = true; clearTimeout(searchTimer) }
function clearSearch() {
  searchDraft.value = ''
  composing.value = false
  commitSearch('')
  searchInput.value?.focus()
}
function isExpanded(group: ToolModuleGroup) {
  return props.keyword ? !searchCollapsedGroups.value.includes(group.key) : interfaceCollapseActive.value.includes(group.key)
}
function toggleGroup(group: ToolModuleGroup) {
  if (props.keyword) {
    searchCollapsedGroups.value = isExpanded(group)
      ? [...searchCollapsedGroups.value, group.key]
      : searchCollapsedGroups.value.filter(key => key !== group.key)
    return
  }
  interfaceCollapseActive.value = isExpanded(group)
    ? interfaceCollapseActive.value.filter(key => key !== group.key)
    : [...interfaceCollapseActive.value, group.key]
}
function openDetail(row: ProjectToolInfo) {
  selectedId.value = row.scanToolId
  detailVisible.value = true
  emit('toolExpandChange', row, true)
}
function retryDetail() {
  if (selected.value) emit('toolExpandChange', selected.value, true)
}
watch(selected, row => { if (!row) detailVisible.value = false })
watch(() => [props.tools, props.focusObservationId], () => {
  selectedId.value = null; detailVisible.value = false
  const focused = props.tools.find(row => row.scanToolId === props.focusObservationId)
  if (focused) openDetail(focused)
}, { immediate: true })
function semanticLabel(row: ProjectToolInfo) {
  if (props.semanticLoading) return '说明读取中'
  if (props.semanticError) return '说明读取失败'
  const doc = props.toolDocMap[row.scanToolId]
  return !doc ? '未补充' : doc.status === 'draft' ? '语义草稿' : doc.status === 'edited' ? '人工编辑' : 'AI 已生成'
}
</script>

<template>
  <section class="source-observations glass-surface-panel" aria-label="来源发现记录">
    <header class="source-observations__toolbar">
      <div class="source-observations__heading">
        <h2>来源记录</h2>
        <span v-if="!loading" class="source-observations__count" role="status" aria-live="polite">{{ keyword || moduleFilter ? `${total} / ${projectTotal}` : projectTotal }} 条</span>
      </div>
      <div class="source-observations__search glass-surface-control">
        <el-icon aria-hidden="true"><Search /></el-icon>
        <input ref="searchInput" type="search" :value="searchDraft" aria-label="搜索来源记录" placeholder="搜索名称、说明、标识或端点"
          @input="updateSearch" @compositionstart="startComposition" @compositionend="finishSearch" @keydown.enter="!composing && commitSearch(searchDraft)" />
        <button v-if="searchDraft" type="button" class="source-observations__search-clear" aria-label="清除来源搜索" @click="clearSearch"><el-icon aria-hidden="true"><Close /></el-icon></button>
      </div>
      <el-select :model-value="moduleFilter" clearable placeholder="全部模块" aria-label="按模块筛选来源" @update:model-value="emit('filterModule', $event || '')">
        <el-option v-for="module in modules" :key="module.moduleId ?? 'unclassified'" :value="module.moduleId == null ? 'unclassified' : String(module.moduleId)"
          :label="`${module.moduleDisplayName || '未关联模块'} (${module.count})`" />
      </el-select>
      <div class="source-observations__actions">
        <slot name="toolbar">
          <div v-if="projectTotal > 0" class="tools-actions">
            <el-button size="small" :loading="sensitiveScanStarting || sensitiveTaskPolling"
              :disabled="!!sensitiveStatusError" @click="emit('startSensitiveDataScan')">扫描敏感数据</el-button>
            <el-button size="small" :loading="exportScanToolsExcelLoading" @click="emit('exportExcel')">导出 EXCEL</el-button>
          </div>
        </slot>
      </div>
    </header>
    <div v-if="$slots.status" class="source-observations__status"><slot name="status" /></div>
    <LiquidGlassNotice
      v-if="sensitiveStatusError"
      :title="sensitiveStatusError"
      type="warning"
      class="source-observations__alert"
      source="backdrop"
    >
      <template #actions><el-button @click="emit('retrySensitiveStatus')">重试扫描状态</el-button></template>
    </LiquidGlassNotice>
    <div v-loading="loading" element-loading-text="正在读取来源记录…" class="tools-table-wrap" :aria-busy="loading">
      <div v-if="!loading && projectTotal === 0" class="empty-api-onboarding">
        <div class="empty-api-onboarding__copy">
          <h3>{{ stageAdvice.title }}</h3>
          <p v-if="stageAdvice.description">{{ stageAdvice.description }}</p>
        </div>
        <div class="empty-api-onboarding__actions">
          <el-button type="primary" @click="emit('emptyPrimaryAction')">{{ stageAdvice.primaryLabel }}</el-button>
          <el-button v-if="stageAdvice.secondaryLabel && stageAdvice.secondaryAction"
            @click="emit('emptySecondaryAction')">{{ stageAdvice.secondaryLabel }}</el-button>
        </div>
      </div>
      <div v-else class="source-observations__scroll" tabindex="0" role="region" aria-label="来源记录表，可横向滚动">
        <table class="source-observations__table" aria-label="来源记录">
          <colgroup><col class="source-column-name" /><col class="source-column-endpoint" /><col class="source-column-semantic" /><col class="source-column-sensitive" /><col class="source-column-state" /><col class="source-column-action" /></colgroup>
          <thead><tr><th scope="col">名称与说明</th><th scope="col">端点</th><th scope="col">补充语义</th><th scope="col">敏感数据</th><th scope="col">来源状态</th><th scope="col" class="source-record-action">操作</th></tr></thead>
          <tbody v-for="group in recordGroups" :key="group.key" :aria-label="group.label + '来源记录'">
            <tr class="source-observations__group"><th colspan="6" scope="rowgroup">
              <button type="button" class="source-group-toggle" :aria-expanded="isExpanded(group)" :aria-label="`${isExpanded(group) ? '折叠' : '展开'}${group.label}来源记录`" @click="toggleGroup(group)">
                <el-icon class="source-group-arrow" :class="{ 'is-expanded': isExpanded(group) }" aria-hidden="true"><ArrowRight /></el-icon>
                <span class="collapse-module-title">{{ group.label }}</span><span class="source-observations__count">{{ group.tools.length }} 条</span>
              </button>
            </th></tr>
            <template v-if="isExpanded(group)">
              <tr v-for="row in group.tools" :key="row.scanToolId" :class="scanToolRowClassName({ row })">
                <td><el-button link class="source-record-name" @click="openDetail(row)">{{ row.title || row.name }}</el-button><span class="source-record-description">{{ row.description || '尚未提供来源说明' }}</span></td>
                <td><div class="source-record-endpoint"><el-tag size="small" type="info" effect="plain">{{ row.httpMethod || '-' }}</el-tag><code>{{ row.contextPath || '' }}{{ row.endpointPath || '未声明端点' }}</code></div></td>
                <td><span :class="{ 'source-record-muted': !toolDocMap[row.scanToolId] }">{{ semanticLabel(row) }}</span></td>
                <td><el-tag v-if="row.sensitiveData?.types?.length" type="warning" size="small">{{ row.sensitiveData.types.length }} 类</el-tag><span v-else class="source-record-muted">{{ row.sensitiveData?.summary ? '查看结果' : row.sensitiveData?.scannedAt ? '暂无标记' : '未扫描' }}</span></td>
                <td><el-tag size="small" :type="row.removedFromSource ? 'danger' : 'info'">{{ row.removedFromSource ? '来源已移除' : '已发现' }}</el-tag><span v-if="row.sdkCapabilityReviewPending" class="source-record-attention">待确认变化</span></td>
                <td class="source-record-action"><el-button link @click="openDetail(row)">核对来源</el-button><SourceOwnerLink :project-id="projectId" :observation-id="row.scanToolId" :can-read="canReadOwner" /></td>
              </tr>
            </template>
          </tbody>
          <tbody v-if="!recordGroups.length && !loading"><tr><td colspan="6"><div class="source-observations__no-results" role="status"><span>没有匹配的来源记录，试试其他关键词。</span><el-button @click="clearSearch">清除来源搜索</el-button></div></td></tr></tbody>
        </table>
      </div>
      <el-pagination v-if="total > pageSize" class="source-observations__pagination" :current-page="current" :page-size="pageSize" :total="total"
        layout="total, prev, pager, next" @current-change="emit('changePage', $event)" />
    </div>
    <SourceObservationDetailDrawer v-model="detailVisible" :row="selected"
      :project-id="projectId"
      :can-write="canWrite" :can-read-owner="canReadOwner" :generating="selectedSemanticBusy"
      :doc="detailVisible && selected ? toolDocMap[selected.scanToolId] : undefined"
      :semantic-loading="semanticLoading" :semantic-error="semanticError"
      :loading="selected ? !!toolDetailLoading[selected.scanToolId] : false"
      :error="selected ? toolDetailError[selected.scanToolId] || '' : ''"
      :rescan-loading="selected ? !!rescanSourceLoading[selected.scanToolId] : false"
      :parameter-rows="parameterRows" :render-md="renderMd" :tool-parameter-count="toolParameterCount"
      :tool-link-label="toolLinkLabel" :tool-link-tag-type="toolLinkTagType"
      @retry="retryDetail" @open-diff="emit('openDiff', $event)" @rescan-from-source="emit('rescanFromSource', $event)"
      @regenerate-tool="emit('regenerateTool', $event)" @open-edit-doc="emit('openEditDoc', $event)" />
  </section>
</template>

<style scoped lang="scss">
.source-observations {
  min-width: 0;
  overflow: hidden;
  border-radius: var(--radius-lg);
}

.source-observations__toolbar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px;
  padding: var(--density-compact-panel-padding);
}

.source-observations__heading {
  display: flex;
  align-items: baseline;
  min-width: 0;
  gap: 8px;

  h2 {
    margin: 0;
    color: var(--text-primary);
    font-size: 15px;
    font-weight: 600;
  }
}

.source-observations__search {
  display: flex;
  align-items: center;
  flex: 1 1 240px;
  gap: 8px;
  min-width: 180px;
  max-width: 360px;
  height: var(--project-list-control-height, var(--density-compact-control-height));
  box-sizing: border-box;
  margin-left: auto;
  padding-inline: 10px;
  border-radius: var(--radius-md);
  color: var(--text-muted);

  &:focus-within {
    border-color: var(--border-focus);
    outline: 2px solid var(--border-focus);
    outline-offset: 2px;
  }

  input {
    width: 100%;
    min-width: 0;
    padding: 0;
    border: 0;
    outline: 0;
    background: transparent;
    color: var(--text-primary);
    font: inherit;
    font-size: 13px;

    &::placeholder { color: var(--text-muted); }
    &::-webkit-search-cancel-button { appearance: none; }
  }
}

.source-observations__search-clear {
  display: grid;
  flex-shrink: 0;
  width: 24px;
  height: 24px;
  place-items: center;
  padding: 0;
  border: 0;
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--text-secondary);
  cursor: pointer;

  &:hover { background: var(--surface-glass-control); }
}

.source-observations__actions {
  min-width: 0;
}

.source-observations__status {
  border-top: 1px solid var(--border-divider);
  padding-inline: var(--density-compact-panel-padding);
}

.source-observations__alert {
  width: auto;
  margin: 0 var(--density-compact-panel-padding) var(--density-compact-section-gap);
}

.source-observations__count {
  flex-shrink: 0;
  color: var(--text-muted);
  font-size: 12px;
  font-weight: 400;
}

.tools-actions {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 8px;

  :deep(.el-button) {
    height: var(--density-compact-control-height);
    margin: 0;
    border-radius: var(--radius-sm);
    font-weight: 600;
  }
}

.tools-table-wrap {
  min-height: 120px;
}

.empty-api-onboarding {
  display: grid;
  justify-items: center;
  gap: 12px;
  padding: 32px var(--density-compact-panel-padding);
  text-align: center;
}

.empty-api-onboarding__copy {
  max-width: 620px;

  h3 {
    margin: 0 0 8px;
    color: var(--text-primary);
    font-size: 16px;
    line-height: 1.5;
  }

  p {
    margin: 0;
    color: var(--text-secondary);
    font-size: 13px;
    line-height: 1.6;
  }
}

.empty-api-onboarding__actions {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: 8px;

  :deep(.el-button + .el-button) {
    margin-left: 0;
  }
}

.source-observations__scroll {
  overflow-x: auto;
  border-top: 1px solid var(--border-divider);
}

.collapse-module-title {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.source-observations__table {
  width: 100%;
  min-width: 980px;
  table-layout: fixed;
  border-collapse: collapse;
  background: transparent;
  color: var(--text-primary);
  font-size: 13px;

  th, td {
    padding: 12px var(--density-compact-panel-padding);
    border-bottom: 1px solid var(--border-divider);
    text-align: left;
    vertical-align: middle;
    background: transparent;
  }

  thead th {
    height: var(--density-compact-row-height);
    box-sizing: border-box;
    padding-block: 8px;
    background: rgb(var(--brand-primary-rgb) / 0.06);
    color: var(--text-secondary);
    font-size: 12px;
    font-weight: 600;
  }

  td {
    height: var(--row-height);
    box-sizing: border-box;
  }

  tbody tr:hover > td {
    background: var(--surface-glass-selected);
  }

  .row-api-tombstone > td {
    background: var(--surface-solid-disabled);
  }

  .source-record-action { text-align: right; }

  .source-observations__group th {
    padding-block: 0;
    background: rgb(var(--brand-primary-rgb) / 0.035);
  }
}

.source-column-name { width: 30%; }
.source-column-endpoint { width: 28%; }
.source-column-semantic { width: 116px; }
.source-column-sensitive { width: 110px; }
.source-column-state { width: 124px; }
.source-column-action { width: 144px; }
.source-record-action > .el-button { margin-bottom: 4px; }
.source-record-action :deep(.source-owner-link) { align-items: flex-end; }

.source-group-toggle {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  min-height: var(--density-compact-row-height);
  padding: 0;
  border: 0;
  background: transparent;
  color: var(--text-primary);
  font: inherit;
  font-weight: 600;
  text-align: left;
  cursor: pointer;
}

.source-group-arrow {
  flex-shrink: 0;
  color: var(--text-muted);

  &.is-expanded { transform: rotate(90deg); }
}

.source-group-toggle:focus-visible,
.source-observations__search-clear:focus-visible,
.source-observations__scroll:focus-visible {
  outline: 2px solid var(--border-focus);
  outline-offset: -2px;
}

.source-record-name {
  display: block;
  max-width: 100%;
  height: auto;
  padding: 0;
  color: var(--text-primary);
  font-weight: 600;

  &:hover {
    color: var(--brand-active);
  }

  &:focus-visible {
    outline: 2px solid var(--border-focus);
    outline-offset: 2px;
    border-radius: var(--radius-sm);
  }
}

.source-record-name :deep(span),
.source-record-description {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.source-record-description {
  margin-top: 4px;
  color: var(--text-secondary);
  font-size: 12px;
}

.source-record-endpoint {
  display: flex;
  align-items: center;
  min-width: 0;
  gap: 8px;

  code {
    padding: 0;
    border: 0;
    background: transparent;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    color: var(--text-secondary);
    font-size: 12px;
  }

  .el-tag {
    flex-shrink: 0;
  }
}

.source-record-muted {
  color: var(--text-muted);
  font-size: 12px;
}

.source-record-attention {
  display: block;
  margin-top: 4px;
  color: var(--status-warning);
  font-size: 12px;
}

.source-observations__pagination {
  :deep(.el-pagination) {
    justify-content: flex-end;
  }
}

.source-observations__no-results {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px;
  min-height: 64px;
  color: var(--text-secondary);
}

@media (max-width: 640px) {
  .source-observations__toolbar { gap: 10px; }

  .source-observations__search {
    flex-basis: 100%;
    max-width: none;
    order: 2;
  }

  .source-observations__actions {
    width: 100%;
    order: 3;
  }

  .tools-actions {
    justify-content: flex-start;
  }

  .source-observations__pagination :deep(.el-pagination) {
    justify-content: flex-start;
  }
}
</style>
