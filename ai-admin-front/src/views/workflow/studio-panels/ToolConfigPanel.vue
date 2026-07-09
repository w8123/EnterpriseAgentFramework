<template>
  <div class="node-specific-panel">
    <el-divider>{{ data.kind === 'skill' ? '能力调用' : '工具调用' }}</el-divider>
    <el-form-item :label="data.kind === 'skill' ? '引用能力' : '引用工具'">
      <div class="reference-row">
        <el-select v-model="config.ref" filterable placeholder="选择引用" style="width: 100%" @change="handleRefChange">
          <el-option v-for="item in options" :key="item.name" :label="assetLabel(item)" :value="item.name" />
        </el-select>
        <el-button v-if="data.kind === 'tool'" plain @click="openProjectApiPicker">从项目接口选择</el-button>
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

    <el-dialog v-model="projectApiDialogOpen" title="选择项目接口" width="860px" append-to-body>
      <div class="project-api-picker-toolbar">
        <el-input
          v-model="projectApiFilters.keyword"
          clearable
          :prefix-icon="Search"
          placeholder="搜索接口名称、路径、描述"
          @keyup.enter="reloadProjectApis"
        />
        <el-select v-model="projectApiFilters.toolLinkStatus" clearable placeholder="Tool 关联状态">
          <el-option label="已关联 Tool" value="LINKED" />
          <el-option label="未关联 Tool" value="NOT_LINKED" />
          <el-option label="全局 Tool 缺失" value="GLOBAL_MISSING" />
        </el-select>
        <el-button :icon="Search" type="primary" @click="reloadProjectApis">查询</el-button>
        <el-button :icon="Refresh" @click="resetProjectApiFilters">重置</el-button>
      </div>
      <el-table
        v-loading="projectApiLoading"
        :data="projectApiRows"
        row-key="scanToolId"
        height="420"
        stripe
        empty-text="暂无项目接口"
      >
        <el-table-column label="接口" min-width="260" show-overflow-tooltip>
          <template #default="{ row }">
            <div class="project-api-name">
              <strong>{{ row.name }}</strong>
              <span>{{ row.httpMethod || '-' }} {{ row.endpointPath || row.sourceLocation || '-' }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="项目 / 模块" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">
            <div class="project-api-name">
              <strong>{{ row.projectCode || projectCode || '-' }}</strong>
              <span>{{ row.moduleDisplayName || '-' }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="参数" width="80" align="center">
          <template #default="{ row }">{{ projectApiToolParameterCount(row) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="150">
          <template #default="{ row }">
            <el-tag size="small" :type="isProjectApiToolSelectable(row) ? 'success' : 'info'" effect="plain">
              {{ projectApiToolStatusLabel(row) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="120" fixed="right">
          <template #default="{ row }">
            <el-button
              size="small"
              type="primary"
              text
              :disabled="!isProjectApiToolSelectable(row)"
              @click="selectProjectApi(row)"
            >
              选择
            </el-button>
          </template>
        </el-table-column>
      </el-table>
      <div class="project-api-picker-footer">
        <span>仅已启用、Agent 可见且已关联 Tool 的接口可直接写入工具节点。</span>
        <el-pagination
          v-model:current-page="projectApiFilters.page"
          v-model:page-size="projectApiFilters.pageSize"
          layout="total, prev, pager, next"
          :total="projectApiTotal"
          @current-change="loadProjectApis"
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
import { getScanProjectTools } from '@/api/scanProject'
import type { ProjectToolInfo } from '@/types/scanProject'
import {
  filterProjectApiTools,
  isProjectApiToolSelectable,
  pageProjectApiTools,
  projectApiToolParameterCount,
  projectApiToolQualifiedName,
  projectApiToolRef,
  projectApiToolStatusLabel,
} from '@/utils/projectApiTools'
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
const projectApiDialogOpen = ref(false)
const projectApiLoading = ref(false)
const projectApiRows = ref<ProjectToolInfo[]>([])
const projectApiTotal = ref(0)
const projectApiFilters = reactive({
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

function openProjectApiPicker() {
  if (!props.projectId) {
    ElMessage.warning('请先在项目上下文中打开 Workflow Studio')
    return
  }
  projectApiDialogOpen.value = true
  projectApiFilters.page = 1
  loadProjectApis()
}

function reloadProjectApis() {
  projectApiFilters.page = 1
  loadProjectApis()
}

function resetProjectApiFilters() {
  projectApiFilters.keyword = ''
  projectApiFilters.toolLinkStatus = 'LINKED'
  reloadProjectApis()
}

async function loadProjectApis() {
  if (!props.projectId) return
  projectApiLoading.value = true
  try {
    const { data } = await getScanProjectTools(props.projectId, 'full')
    const filtered = filterProjectApiTools(data || [], projectApiFilters)
    projectApiRows.value = pageProjectApiTools(filtered, projectApiFilters.page, projectApiFilters.pageSize)
    projectApiTotal.value = filtered.length
  } catch {
    projectApiRows.value = []
    projectApiTotal.value = 0
    ElMessage.error('加载项目接口失败')
  } finally {
    projectApiLoading.value = false
  }
}

function selectProjectApi(row: ProjectToolInfo) {
  if (!isProjectApiToolSelectable(row)) {
    ElMessage.warning('该接口还不能直接用于工具节点，请先完成 Tool 关联并开启 Agent 可见。')
    return
  }
  config.value.ref = projectApiToolRef(row)
  config.value.qualifiedName = projectApiToolQualifiedName(row, props.projectCode)
  config.value.projectCode = row.projectCode || props.projectCode || null
  config.value.visibility = 'PROJECT'
  config.value.maxRequestTimeMs ||= 180000
  config.value.inputMapping = buildDefaultInputMapping(row.parameters || [])
  config.value.mappingNote = `由项目 API 管理选择：${row.httpMethod || ''} ${row.endpointPath || row.name}`.trim()
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
  projectApiDialogOpen.value = false
  ElMessage.success('已写入工具节点配置')
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

.project-api-picker-toolbar {
  display: grid;
  grid-template-columns: minmax(240px, 1fr) 160px auto auto;
  gap: 8px;
  margin-bottom: 12px;
}

.project-api-name {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.project-api-name span {
  color: var(--text-secondary);
  font-size: 12px;
}

.project-api-picker-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-top: 12px;
  color: var(--text-secondary);
  font-size: 12px;
}
</style>
