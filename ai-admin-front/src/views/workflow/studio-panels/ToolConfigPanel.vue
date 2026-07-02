<template>
  <div class="node-specific-panel">
    <el-divider>{{ data.kind === 'skill' ? '能力调用' : '工具调用' }}</el-divider>
    <el-form-item :label="data.kind === 'skill' ? '引用能力' : '引用工具'">
      <div class="reference-row">
        <el-select v-model="config.ref" filterable placeholder="选择引用" style="width: 100%" @change="handleRefChange">
          <el-option v-for="item in options" :key="item.name" :label="assetLabel(item)" :value="item.name" />
        </el-select>
        <el-button v-if="data.kind === 'tool'" plain @click="openApiAssetPicker">从 API 资产选择</el-button>
      </div>
    </el-form-item>
    <el-form-item label="凭据引用">
      <CredentialSelect
        v-model="config.credentialRef"
        :credentials="credentialOptions"
        :project-id="projectId"
        :project-code="projectCode"
        @created="$emit('credentialCreated', $event)"
      />
    </el-form-item>
    <el-form-item v-if="isRequestTool" label="最大请求时间">
      <el-input-number
        v-model="maxRequestSeconds"
        :min="1"
        :max="1800"
        :step="10"
        controls-position="right"
      />
      <span class="timeout-unit">秒</span>
    </el-form-item>
    <el-form-item label="参数映射">
      <el-input :model-value="formatMap(config.inputMapping)" type="textarea" :rows="6" placeholder="customerId = params.customerId" @update:model-value="config.inputMapping = parseMap($event)" />
    </el-form-item>
    <el-form-item label="映射备注">
      <el-input v-model="config.mappingNote" type="textarea" :rows="2" />
    </el-form-item>
    <div v-if="paramSourceHints.length" class="param-hints">
      <div class="field-table-head">
        <strong>参数来源提示</strong>
      </div>
      <div v-for="hint in paramSourceHints" :key="`${hint.targetPath}-${hint.sourceApi}-${hint.sourcePath}`" class="param-hint-row">
        <div>
          <strong>{{ hint.targetPath }}</strong>
          <span>{{ hint.sourceApi }}.{{ hint.sourcePath }}</span>
        </div>
        <el-tag v-if="hint.confidence !== null" size="small">{{ Math.round((hint.confidence || 0) * 100) }}%</el-tag>
        <el-button size="small" text type="primary" @click="applyHint(hint)">应用</el-button>
      </div>
    </div>
    <div v-if="selectedTool?.parameters?.length" class="tool-params">
      <div class="field-table-head">
        <strong>工具参数</strong>
        <el-button size="small" text type="primary" @click="fillMissingMappings">补齐映射</el-button>
      </div>
      <div v-for="param in selectedTool.parameters" :key="param.name" class="tool-param-row">
        <strong>{{ param.name }}</strong>
        <span>{{ param.type }}</span>
        <em>{{ param.required ? '必填' : '可选' }}</em>
      </div>
    </div>

    <el-dialog v-model="apiAssetDialogOpen" title="选择 API 资产" width="860px" append-to-body>
      <div class="api-asset-picker-toolbar">
        <el-input
          v-model="apiAssetFilters.keyword"
          clearable
          :prefix-icon="Search"
          placeholder="搜索接口名称、路径、描述"
          @keyup.enter="reloadApiAssets"
        />
        <el-select v-model="apiAssetFilters.toolLinkStatus" clearable placeholder="Tool 关联状态">
          <el-option label="已关联 Tool" value="LINKED" />
          <el-option label="未关联 Tool" value="NOT_LINKED" />
          <el-option label="全局 Tool 缺失" value="GLOBAL_MISSING" />
        </el-select>
        <el-button :icon="Search" type="primary" @click="reloadApiAssets">查询</el-button>
        <el-button :icon="Refresh" @click="resetApiAssetFilters">重置</el-button>
      </div>
      <el-table
        v-loading="apiAssetLoading"
        :data="apiAssetRows"
        row-key="apiId"
        height="420"
        stripe
        empty-text="暂无 API 资产"
      >
        <el-table-column label="接口" min-width="260" show-overflow-tooltip>
          <template #default="{ row }">
            <div class="api-asset-name">
              <strong>{{ row.name }}</strong>
              <span>{{ row.httpMethod || '-' }} {{ row.endpointPath || row.sourceLocation || '-' }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="项目 / 模块" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">
            <div class="api-asset-name">
              <strong>{{ row.projectName || row.projectCode || '-' }}</strong>
              <span>{{ row.moduleName || '-' }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="参数" width="80" align="center">
          <template #default="{ row }">{{ row.parameterCount || row.parameters?.length || 0 }}</template>
        </el-table-column>
        <el-table-column label="状态" width="150">
          <template #default="{ row }">
            <el-tag size="small" :type="apiAssetSelectable(row) ? 'success' : 'info'" effect="plain">
              {{ apiAssetStatusLabel(row) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="120" fixed="right">
          <template #default="{ row }">
            <el-button
              size="small"
              type="primary"
              text
              :disabled="!apiAssetSelectable(row)"
              @click="selectApiAsset(row)"
            >
              选择
            </el-button>
          </template>
        </el-table-column>
      </el-table>
      <div class="api-asset-picker-footer">
        <span>仅已启用、Agent 可见且已关联 Tool 的接口可直接写入工具节点。</span>
        <el-pagination
          v-model:current-page="apiAssetFilters.page"
          v-model:page-size="apiAssetFilters.pageSize"
          layout="total, prev, pager, next"
          :total="apiAssetTotal"
          @current-change="loadApiAssets"
        />
      </div>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Refresh, Search } from '@element-plus/icons-vue'
import type { CanvasNodeData, ToolNodeConfig } from '@/types/studio'
import type { ToolInfo, ToolParameter } from '@/types/tool'
import type { CompositionInfo } from '@/types/composition'
import type { WorkflowCredential } from '@/types/workflowCredential'
import type { ApiGraphParamSourceHint } from '@/api/apiGraph'
import { listApiAssets } from '@/api/apiAsset'
import type { ApiAssetItem } from '@/types/apiAsset'
import { formatMap, parseMap } from './panelUtils'
import CredentialSelect from './CredentialSelect.vue'

const props = defineProps<{
  data: CanvasNodeData
  options: (ToolInfo | CompositionInfo)[]
  credentialOptions: WorkflowCredential[]
  paramSourceHints: ApiGraphParamSourceHint[]
  projectId?: number | null
  projectCode?: string | null
}>()
defineEmits<{
  credentialCreated: [credential: WorkflowCredential]
}>()

const config = computed<ToolNodeConfig>(() => {
  props.data.toolConfig ||= {
    ref: '',
    qualifiedName: null,
    projectCode: null,
    visibility: null,
    credentialRef: '',
    maxRequestTimeMs: 180000,
    inputMapping: {},
  }
  return props.data.toolConfig
})
const selectedTool = computed(() => props.options.find((item) => item.name === config.value.ref) as ToolInfo | undefined)
const apiAssetDialogOpen = ref(false)
const apiAssetLoading = ref(false)
const apiAssetRows = ref<ApiAssetItem[]>([])
const apiAssetTotal = ref(0)
const apiAssetFilters = reactive({
  keyword: '',
  toolLinkStatus: 'LINKED',
  page: 1,
  pageSize: 10,
})
const isRequestTool = computed(() => {
  if (props.data.kind !== 'tool') return false
  const tool = selectedTool.value
  return !tool || tool.source !== 'code' || !!tool.httpMethod || !!tool.endpointPath
})
const maxRequestSeconds = computed({
  get: () => Math.round((config.value.maxRequestTimeMs || 180000) / 1000),
  set: (value: number) => {
    config.value.maxRequestTimeMs = Math.max(1000, Math.min(1800000, Math.round(value || 180) * 1000))
  },
})

function handleRefChange() {
  const selected = selectedTool.value
  config.value.qualifiedName = selected?.qualifiedName || null
  config.value.projectCode = selected?.projectCode || null
  config.value.visibility = selected?.visibility || null
  config.value.maxRequestTimeMs ||= 180000
  props.data.description = selected?.description || props.data.description || ''
}

function openApiAssetPicker() {
  apiAssetDialogOpen.value = true
  apiAssetFilters.page = 1
  loadApiAssets()
}

function reloadApiAssets() {
  apiAssetFilters.page = 1
  loadApiAssets()
}

function resetApiAssetFilters() {
  apiAssetFilters.keyword = ''
  apiAssetFilters.toolLinkStatus = 'LINKED'
  reloadApiAssets()
}

async function loadApiAssets() {
  apiAssetLoading.value = true
  try {
    const { data } = await listApiAssets({
      projectId: props.projectId ?? undefined,
      keyword: apiAssetFilters.keyword || undefined,
      toolLinkStatus: apiAssetFilters.toolLinkStatus || undefined,
      page: apiAssetFilters.page,
      pageSize: apiAssetFilters.pageSize,
    })
    apiAssetRows.value = data.items || []
    apiAssetTotal.value = data.total || 0
  } catch {
    apiAssetRows.value = []
    apiAssetTotal.value = 0
    ElMessage.error('加载 API 资产失败')
  } finally {
    apiAssetLoading.value = false
  }
}

function selectApiAsset(row: ApiAssetItem) {
  if (!apiAssetSelectable(row)) {
    ElMessage.warning('该接口还不能直接用于工具节点，请先完成 Tool 关联并开启 Agent 可见。')
    return
  }
  config.value.ref = row.globalToolName || row.name
  config.value.qualifiedName = row.globalToolQualifiedName || row.globalToolName || row.name
  config.value.projectCode = row.projectCode || null
  config.value.visibility = 'PROJECT'
  config.value.maxRequestTimeMs ||= 180000
  config.value.inputMapping = buildDefaultInputMapping(row.parameters || [])
  config.value.mappingNote = `由 API 资产目录选择：${row.httpMethod || ''} ${row.endpointPath || row.name}`.trim()
  props.data.label = row.name
  props.data.description = row.aiDescription || row.description || props.data.description || ''
  props.data.inputs = Object.entries(config.value.inputMapping).map(([target, source]) => ({
    id: target,
    name: target,
    type: 'any',
    required: false,
    source,
  }))
  props.data.outputs = [{ id: props.data.outputAlias || 'tool_output', name: props.data.outputAlias || 'tool_output', type: 'any' }]
  apiAssetDialogOpen.value = false
  ElMessage.success('已写入工具节点配置')
}

function apiAssetSelectable(row: ApiAssetItem) {
  return row.toolLinkStatus === 'LINKED' && !!row.globalToolName && row.enabled && row.agentVisible && !row.removedFromSource
}

function apiAssetStatusLabel(row: ApiAssetItem) {
  if (row.removedFromSource) return '源接口已移除'
  if (row.toolLinkStatus !== 'LINKED') return '需先关联 Tool'
  if (!row.enabled) return '未启用'
  if (!row.agentVisible) return 'Agent 不可见'
  return '可选择'
}

function buildDefaultInputMapping(parameters: ToolParameter[]) {
  const mapping: Record<string, string> = {}
  for (const parameter of parameters) {
    collectInputParameterMapping(parameter, mapping)
  }
  return mapping
}

function collectInputParameterMapping(parameter: ToolParameter, mapping: Record<string, string>, prefix = '') {
  const location = (parameter.location || '').toUpperCase()
  if (location === 'RESPONSE') return
  const key = prefix ? `${prefix}.${parameter.name}` : parameter.name
  const children = (parameter.children || []).filter((item) => (item.location || '').toUpperCase() !== 'RESPONSE')
  if (children.length) {
    for (const child of children) collectInputParameterMapping(child, mapping, key)
    return
  }
  mapping[key] = `params.${key}`
}

function fillMissingMappings() {
  const mapping = { ...(config.value.inputMapping || {}) }
  for (const param of selectedTool.value?.parameters || []) {
    if (!mapping[param.name]) mapping[param.name] = param.name
  }
  config.value.inputMapping = mapping
}

function applyHint(hint: ApiGraphParamSourceHint) {
  const mapping = { ...(config.value.inputMapping || {}) }
  mapping[hint.targetPath] = `${hint.sourceApi}.${hint.sourcePath}`
  config.value.inputMapping = mapping
  if (!config.value.mappingNote) {
    config.value.mappingNote = '参数来源由接口图谱关系生成。'
  }
}

function assetLabel(item: ToolInfo | CompositionInfo) {
  const project = item.projectCode ? ` / ${item.projectCode}` : ''
  const visibility = item.visibility ? ` / ${item.visibility}` : ''
  return `${item.name}${project}${visibility}`
}
</script>

<style scoped>
.timeout-unit {
  margin-left: 8px;
  color: var(--text-secondary);
}

.reference-row {
  display: flex;
  width: 100%;
  gap: 8px;
}

.reference-row .el-button {
  flex: 0 0 auto;
}

.api-asset-picker-toolbar {
  display: grid;
  grid-template-columns: minmax(240px, 1fr) 160px auto auto;
  gap: 8px;
  margin-bottom: 12px;
}

.api-asset-name {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.api-asset-name span {
  color: var(--text-secondary);
  font-size: 12px;
}

.api-asset-picker-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-top: 12px;
  color: var(--text-secondary);
  font-size: 12px;
}
</style>
