<template>
  <div class="node-specific-panel interaction-panel">
    <section class="interaction-section interaction-binding">
      <div class="interaction-section-head">
        <div>
          <strong>绑定来源</strong>
          <span>{{ bindingSummary }}</span>
        </div>
        <el-button size="small" type="primary" plain :disabled="!selectedBindingAsset" @click="generateFieldsFromBinding">
          从来源生成字段
        </el-button>
      </div>

      <div class="interaction-form-grid binding-grid">
        <el-form-item label="来源类型">
          <div class="interaction-segmented" role="group" aria-label="来源类型">
            <button
              v-for="option in bindingSourceOptions"
              :key="option.value"
              type="button"
              class="interaction-segmented-item"
              :class="{ active: binding.sourceKind === option.value }"
              @click="selectBindingKind(option.value)"
            >
              {{ option.label }}
            </button>
          </div>
        </el-form-item>
        <el-form-item v-if="binding.sourceKind === 'TOOL'" label="选择工具">
          <el-select v-model="binding.ref" filterable clearable :teleported="false" placeholder="选择已注册 Tool" @change="handleBindingRefChange">
            <el-option v-for="item in toolOptions" :key="item.name" :label="assetLabel(item)" :value="item.name" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="binding.sourceKind === 'COMPOSITION'" label="选择组合">
          <el-select v-model="binding.ref" filterable clearable :teleported="false" placeholder="选择 Composition" @change="handleBindingRefChange">
            <el-option v-for="item in compositionOptions" :key="item.name" :label="assetLabel(item)" :value="item.name" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="binding.sourceKind === 'API'" label="选择接口">
          <div class="api-binding-row">
            <el-select v-model="binding.ref" filterable clearable :teleported="false" placeholder="选择项目接口 / API 型 Tool" @change="handleBindingRefChange">
              <el-option
                v-for="item in apiToolOptions"
                :key="item.name"
                :label="apiLabel(item)"
                :value="item.name"
              />
            </el-select>
            <el-button plain @click="openApiAssetPicker">从资产目录选</el-button>
          </div>
        </el-form-item>
      </div>

      <div v-if="binding.sourceKind !== 'NONE'" class="interaction-inline-option">
        <div>
          <strong>调用节点</strong>
          <span>{{ binding.autoCreateCallNode ? '已开启自动创建/同步' : '未自动创建调用节点' }}</span>
        </div>
        <el-switch
          v-model="binding.autoCreateCallNode"
          active-text="自动创建/同步"
          @change="handleAutoCreateCallNodeChange"
        />
      </div>
      <div v-if="binding.sourceKind !== 'NONE'" class="interaction-inline-option">
        <div>
          <strong>展示节点</strong>
          <span>{{ binding.autoCreateDisplayNode ? '已开启自动创建工具结果展示' : '仅创建调用节点，展示可手动调整' }}</span>
        </div>
        <el-switch
          v-model="binding.autoCreateDisplayNode"
          :disabled="!binding.autoCreateCallNode"
          active-text="自动创建展示节点"
          @change="handleAutoCreateDisplayNodeChange"
        />
      </div>
    </section>

    <el-dialog v-model="apiAssetDialogOpen" title="选择项目 API 资产" width="880px" append-to-body>
      <div class="api-asset-picker-toolbar">
        <el-input
          v-model="apiAssetFilters.keyword"
          clearable
          placeholder="搜索接口名称、路径、描述"
          @keyup.enter="reloadApiAssets"
        />
        <el-select v-model="apiAssetFilters.toolLinkStatus" clearable placeholder="Tool 关联状态">
          <el-option label="已关联 Tool" value="LINKED" />
          <el-option label="未关联 Tool" value="NOT_LINKED" />
          <el-option label="全局 Tool 缺失" value="GLOBAL_MISSING" />
        </el-select>
        <el-button type="primary" @click="reloadApiAssets">查询</el-button>
        <el-button @click="resetApiAssetFilters">重置</el-button>
      </div>
      <el-table
        v-loading="apiAssetLoading"
        :data="apiAssetRows"
        row-key="apiId"
        height="420"
        stripe
        empty-text="暂无 API 资产"
      >
        <el-table-column label="接口" min-width="280" show-overflow-tooltip>
          <template #default="{ row }">
            <div class="api-asset-cell">
              <strong>{{ row.name }}</strong>
              <span>{{ row.httpMethod || '-' }} {{ row.endpointPath || row.sourceLocation || '-' }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="项目 / 模块" min-width="190" show-overflow-tooltip>
          <template #default="{ row }">
            <div class="api-asset-cell">
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
        <span>选择后会生成交互字段，并可继续自动创建调用节点。</span>
        <el-pagination
          v-model:current-page="apiAssetFilters.page"
          v-model:page-size="apiAssetFilters.pageSize"
          layout="total, prev, pager, next"
          :total="apiAssetTotal"
          @current-change="loadApiAssets"
        />
      </div>
    </el-dialog>

    <section class="interaction-section">
      <div class="interaction-section-head">
        <div>
          <strong>交互表现</strong>
          <span>{{ config.title || '未命名交互' }}</span>
        </div>
      </div>
      <div class="interaction-form-grid">
        <el-form-item label="交互类型">
          <div class="interaction-segmented interaction-type-segmented" role="group" aria-label="交互类型">
            <button
              v-for="option in interactionTypeOptions"
              :key="option.value"
              type="button"
              class="interaction-segmented-item"
              :class="{ active: config.interactionType === option.value }"
              @click="selectInteractionType(option.value)"
            >
              {{ option.label }}
            </button>
          </div>
        </el-form-item>
        <el-form-item label="渲染组件">
          <el-select v-model="config.component" :teleported="false">
            <el-option label="表单" value="FORM" />
            <el-option label="详情" value="DETAIL" />
            <el-option label="列表" value="TABLE" />
            <el-option label="卡片" value="CARD" />
            <el-option label="报告" value="REPORT" />
            <el-option label="自定义" value="CUSTOM" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="config.component === 'CUSTOM'" label="Renderer Key">
          <el-input v-model="customRendererKey" placeholder="enterprise.team.detail" />
        </el-form-item>
        <el-form-item label="标题">
          <el-input v-model="config.title" placeholder="智能输入采集" />
        </el-form-item>
        <el-form-item label="输出别名">
          <el-input v-model="config.outputAlias" placeholder="interaction_output" />
        </el-form-item>
        <el-form-item label="数据表达式">
          <el-select
            v-model="config.dataExpression"
            filterable
            allow-create
            default-first-option
            :teleported="false"
            placeholder="lastOutput"
          >
            <el-option
              v-for="item in normalizedVariableOptions"
              :key="item.value"
              :label="item.label"
              :value="item.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="交互资产">
          <el-input v-model="config.qualifiedName" placeholder="可选，如 system.profile_collect" />
        </el-form-item>
      </div>
    </section>

    <section v-if="needsFields" class="interaction-section fields-section">
      <div class="interaction-section-head">
        <div>
          <strong>字段与采集</strong>
          <span>{{ config.fields.length }} 个字段，{{ slotEnabledCount }} 个启用智能采集</span>
        </div>
        <el-button size="small" type="primary" plain @click="addField">添加字段</el-button>
      </div>
      <el-empty v-if="!config.fields.length" description="暂无字段" :image-size="72" />
      <div v-else class="interaction-field-list">
        <article v-for="(field, index) in config.fields" :key="index" class="interaction-field-card">
          <header class="interaction-field-card-head">
            <div class="field-title">
              <span>{{ index + 1 }}</span>
              <strong>{{ field.description || field.name || '未命名字段' }}</strong>
              <em>{{ field.targetPath || field.name }}</em>
            </div>
            <div class="field-actions">
              <el-tag :type="field.required ? 'danger' : 'info'" effect="light" round>
                {{ field.required ? '必填' : '可选' }}
              </el-tag>
              <el-tag :type="field.slotFilling?.enabled ? 'success' : 'info'" effect="light" round>
                {{ field.slotFilling?.enabled ? fieldSlotStrategiesLabel(field) : '手动输入' }}
              </el-tag>
              <el-button text type="danger" @click="removeField(index)">删除</el-button>
            </div>
          </header>

          <div class="field-basic-grid">
            <el-form-item label="字段 key">
              <el-input v-model="field.name" placeholder="teamName" @change="syncFieldKey(field)" />
            </el-form-item>
            <el-form-item label="展示名">
              <el-input v-model="field.description" placeholder="班组名称" />
            </el-form-item>
            <el-form-item label="类型">
              <el-select v-model="field.type" :teleported="false">
                <el-option label="string" value="string" />
                <el-option label="number" value="number" />
                <el-option label="integer" value="integer" />
                <el-option label="boolean" value="boolean" />
                <el-option label="object" value="object" />
                <el-option label="array" value="array" />
                <el-option label="file" value="file" />
              </el-select>
            </el-form-item>
            <el-form-item label="组件">
              <el-select v-model="field.component" :teleported="false">
                <el-option label="输入框" value="input" />
                <el-option label="下拉框" value="select" />
                <el-option label="单选" value="radio" />
                <el-option label="复选" value="checkbox" />
                <el-option label="日期" value="date" />
                <el-option label="上传" value="upload" />
              </el-select>
            </el-form-item>
            <el-form-item label="目标入参">
              <el-input v-model="field.targetPath" placeholder="body_json.teamName" />
            </el-form-item>
            <el-form-item label="数据源">
              <el-input v-model="field.datasource" placeholder="数据源 key / URL" />
            </el-form-item>
          </div>

          <div class="field-collect-row" :class="{ 'is-slot-enabled': field.slotFilling?.enabled }">
            <el-switch v-model="field.required" inline-prompt active-text="必填" inactive-text="可选" />
            <template v-if="field.slotFilling">
              <el-switch
                v-model="field.slotFilling.enabled"
                active-text="智能采集"
                @change="(value: string | number | boolean) => handleSlotFillingToggle(field, value)"
              />
              <el-select
                v-if="field.slotFilling.enabled"
                v-model="field.slotFilling.strategies"
                multiple
                collapse-tags
                collapse-tags-tooltip
                :teleported="false"
                placeholder="采集策略"
              >
                <el-option label="规则抽取" value="RULE" />
                <el-option label="LLM 抽取" value="LLM" />
                <el-option label="字典匹配" value="DICTIONARY" />
              </el-select>
              <el-select
                v-if="field.slotFilling.enabled"
                v-model="field.slotFilling.confirmPolicy"
                :teleported="false"
                placeholder="确认策略"
              >
                <el-option label="低置信度确认" value="LOW_CONFIDENCE" />
                <el-option label="总是确认" value="ALWAYS" />
                <el-option label="不确认" value="NEVER" />
              </el-select>
              <el-input-number
                v-if="field.slotFilling.enabled"
                v-model="field.slotFilling.confidenceThreshold"
                :min="0"
                :max="1"
                :step="0.05"
                controls-position="right"
              />
            </template>
          </div>

          <div v-if="field.slotFilling?.enabled" class="field-slot-grid">
            <el-form-item label="LLM 提示">
              <el-input v-model="field.slotFilling.llmPrompt" placeholder="可选" />
            </el-form-item>
            <el-form-item label="规则正则">
              <el-select
                v-model="field.slotFilling.patterns"
                multiple
                filterable
                allow-create
                default-first-option
                :teleported="false"
                placeholder="第一捕获组为值"
              />
            </el-form-item>
            <el-form-item label="字典值">
              <el-select
                v-model="field.slotFilling.dictionaryValues"
                multiple
                filterable
                allow-create
                default-first-option
                :teleported="false"
                placeholder="命中即采集"
              />
            </el-form-item>
          </div>
        </article>
      </div>
    </section>

    <el-collapse class="interaction-advanced">
      <el-collapse-item title="数据源协议" name="dataSources">
        <el-input v-model="dataSourcesText" type="textarea" :rows="4" @change="syncJson('dataSources')" />
      </el-collapse-item>
      <el-collapse-item title="多轮追问策略" name="behavior">
        <el-input v-model="behaviorText" type="textarea" :rows="4" @change="syncJson('behavior')" />
      </el-collapse-item>
      <el-collapse-item title="渲染协议" name="renderSchema">
        <el-input v-model="renderSchemaText" type="textarea" :rows="4" @change="syncJson('renderSchema')" />
      </el-collapse-item>
    </el-collapse>
  </div>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type {
  CanvasNodeData,
  InteractionCallNodeRequest,
  InteractionBindingConfig,
  InteractionBindingSourceKind,
  InteractionNodeConfig,
  InteractionNodeType,
  StudioFieldSchema,
  StudioPort,
  StudioVariableOption,
} from '@/types/studio'
import type { ToolInfo, ToolParameter } from '@/types/tool'
import type { CompositionInfo } from '@/types/composition'
import { isToolInputParameter } from '@/types/composition'
import { interactionOutputPorts } from '@/utils/studio'
import { listApiAssets } from '@/api/apiAsset'
import type { ApiAssetItem } from '@/types/apiAsset'

const props = defineProps<{
  data: CanvasNodeData
  variableOptions?: Array<string | StudioVariableOption>
  toolOptions?: ToolInfo[]
  compositionOptions?: CompositionInfo[]
  projectId?: number | null
  projectCode?: string | null
}>()

const emit = defineEmits<{
  createCallNode: [request: InteractionCallNodeRequest]
}>()

const bindingSourceOptions: Array<{ label: string; value: InteractionBindingSourceKind }> = [
  { label: '不绑定', value: 'NONE' },
  { label: '工具', value: 'TOOL' },
  { label: '组合', value: 'COMPOSITION' },
  { label: '项目接口', value: 'API' },
]

const interactionTypeOptions: Array<{ label: string; value: InteractionNodeType }> = [
  { label: '采集输入', value: 'COLLECT_INPUT' },
  { label: '展示输出', value: 'PRESENT_OUTPUT' },
  { label: '用户选择', value: 'USER_CHOICE' },
  { label: '确认动作', value: 'CONFIRM_ACTION' },
  { label: '审阅编辑', value: 'REVIEW_EDIT' },
]

const toolOptions = computed(() => props.toolOptions || [])
const compositionOptions = computed(() => props.compositionOptions || [])
const apiToolOptions = computed(() => toolOptions.value.filter((item) => !!item.httpMethod || !!item.endpointPath || !!item.catalogScanToolId))
const selectedApiAssetTool = ref<ToolInfo | null>(null)
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

const config = computed<InteractionNodeConfig>(() => {
  props.data.interactionConfig ||= {
    interactionType: 'COLLECT_INPUT',
    binding: { sourceKind: 'NONE' },
    title: props.data.label || '智能交互',
    component: 'FORM',
    outputAlias: props.data.outputAlias || 'interaction_output',
    fields: [{ name: 'query', key: 'query', type: 'string', required: true, description: '查询条件', source: 'input.message', targetPath: 'query', component: 'input', slotFilling: defaultSlotFilling() }],
    dataExpression: 'lastOutput',
    dataSources: {},
    behavior: { askMissing: true, maxTurns: 6 },
    renderSchema: {},
  }
  props.data.interactionConfig.binding ||= { sourceKind: 'NONE' }
  props.data.interactionConfig.outputAlias ||= 'interaction_output'
  props.data.interactionConfig.title ||= props.data.label || '智能交互'
  props.data.interactionConfig.fields ||= []
  return props.data.interactionConfig
})

const binding = computed<InteractionBindingConfig>(() => {
  config.value.binding ||= { sourceKind: 'NONE' }
  config.value.binding.sourceKind ||= 'NONE'
  return config.value.binding
})

const selectedBindingAsset = computed<ToolInfo | CompositionInfo | undefined>(() => {
  if (binding.value.sourceKind === 'COMPOSITION') {
    return compositionOptions.value.find((item) => item.name === binding.value.ref)
  }
  if (binding.value.sourceKind === 'API') {
    if (selectedApiAssetTool.value?.name === binding.value.ref) {
      return selectedApiAssetTool.value || undefined
    }
    return apiToolOptions.value.find((item) => item.name === binding.value.ref)
  }
  if (binding.value.sourceKind === 'TOOL') {
    return toolOptions.value.find((item) => item.name === binding.value.ref)
  }
  return undefined
})

const dataSourcesText = ref('')
const behaviorText = ref('')
const renderSchemaText = ref('')

const needsFields = computed(() => ['COLLECT_INPUT', 'USER_CHOICE', 'CONFIRM_ACTION', 'REVIEW_EDIT'].includes(config.value.interactionType))

const slotEnabledCount = computed(() => (config.value.fields || []).filter((field) => field.slotFilling?.enabled).length)

const customRendererKey = computed({
  get: () => String(config.value.renderSchema?.rendererKey || ''),
  set: (value: string) => {
    const schema = { ...(config.value.renderSchema || {}) }
    const normalized = value.trim()
    if (normalized) {
      schema.rendererKey = normalized
    } else {
      delete schema.rendererKey
    }
    config.value.renderSchema = schema
  },
})

const bindingSummary = computed(() => {
  if (binding.value.sourceKind === 'NONE') {
    return '未绑定来源'
  }
  if (!selectedBindingAsset.value) {
    return '等待选择资产'
  }
  return assetLabel(selectedBindingAsset.value)
})

const normalizedVariableOptions = computed<StudioVariableOption[]>(() => {
  return (props.variableOptions || []).map((item) => {
    if (typeof item === 'string') {
      return { value: item, label: item, group: '变量' }
    }
    return item
  })
})

function selectBindingKind(value: InteractionBindingSourceKind) {
  if (binding.value.sourceKind === value) return
  binding.value.sourceKind = value
  handleBindingKindChange()
}

function selectInteractionType(value: InteractionNodeType) {
  config.value.interactionType = value
  if (value === 'PRESENT_OUTPUT' && !config.value.component) {
    config.value.component = 'DETAIL'
  }
  if (value !== 'PRESENT_OUTPUT' && !config.value.component) {
    config.value.component = 'FORM'
  }
}

watch(
  config,
  (value) => {
    normalizeSlotFillingFields(value.fields || [])
    props.data.outputAlias = value.outputAlias || 'interaction_output'
    const outputs = interactionOutputPorts(value, props.data.outputAlias)
    if (!samePorts(props.data.outputs, outputs)) {
      props.data.outputs = outputs
    }
    dataSourcesText.value = JSON.stringify(value.dataSources || {}, null, 2)
    behaviorText.value = JSON.stringify(value.behavior || {}, null, 2)
    renderSchemaText.value = JSON.stringify(value.renderSchema || {}, null, 2)
  },
  { deep: true, immediate: true },
)

function handleBindingKindChange() {
  binding.value.ref = ''
  binding.value.qualifiedName = null
  binding.value.projectCode = null
  binding.value.projectId = null
  binding.value.apiMethod = null
  binding.value.apiPath = null
  binding.value.generatedFrom = ''
  binding.value.callNodeId = ''
  binding.value.displayNodeId = ''
  if (binding.value.sourceKind === 'NONE') {
    binding.value.autoCreateDisplayNode = false
  }
  if (binding.value.sourceKind === 'API') {
    config.value.interactionType = 'COLLECT_INPUT'
    config.value.component = 'FORM'
  }
}

function openApiAssetPicker() {
  binding.value.sourceKind = 'API'
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

async function selectApiAsset(row: ApiAssetItem) {
  if (!apiAssetSelectable(row)) {
    ElMessage.warning('该接口还不能直接用于交互节点，请先完成 Tool 关联并开启 Agent 可见。')
    return
  }
  selectedApiAssetTool.value = apiAssetToTool(row)
  binding.value.sourceKind = 'API'
  binding.value.ref = selectedApiAssetTool.value.name
  apiAssetDialogOpen.value = false
  await handleBindingRefChange()
}

function apiAssetToTool(row: ApiAssetItem): ToolInfo {
  return {
    name: row.globalToolName || row.name,
    description: row.aiDescription || row.description || '',
    parameters: row.parameters || [],
    source: 'scanner',
    sourceLocation: row.sourceLocation || null,
    httpMethod: row.httpMethod || null,
    baseUrl: row.baseUrl || null,
    contextPath: row.contextPath || null,
    endpointPath: row.endpointPath || null,
    requestBodyType: row.requestBodyType || null,
    responseType: row.responseType || null,
    projectId: row.projectId,
    projectCode: row.projectCode || null,
    visibility: 'PROJECT',
    qualifiedName: row.globalToolQualifiedName || row.globalToolName || row.name,
    sourceProjectName: row.projectName || null,
    aiDescription: row.aiDescription || null,
    capabilityMetadataJson: null,
    enabled: row.enabled,
    agentVisible: row.agentVisible,
    lightweightEnabled: row.lightweightEnabled,
    catalogScanToolId: row.apiId,
    catalogLinkStatus: row.toolLinkStatus || null,
    catalogLinkMessage: null,
  }
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

async function handleBindingRefChange() {
  const asset = selectedBindingAsset.value
  binding.value.qualifiedName = asset?.qualifiedName || null
  binding.value.projectCode = asset?.projectCode || null
  binding.value.projectId = asset?.projectId || null
  if (isTool(asset)) {
    binding.value.apiMethod = asset.httpMethod || null
    binding.value.apiPath = asset.endpointPath || null
  }
  if (asset?.qualifiedName) {
    config.value.qualifiedName = asset.qualifiedName
  }
  if (asset?.description && !config.value.title) {
    config.value.title = asset.description
  }
  if (asset) {
    const generated = await maybeGenerateFieldsFromBinding(asset)
    if (generated && binding.value.autoCreateCallNode) {
      emitCallNodeRequest(asset)
    }
  }
}

async function generateFieldsFromBinding() {
  const asset = selectedBindingAsset.value
  if (!asset) return
  await applyFieldsFromBinding(asset, true)
  if (binding.value.autoCreateCallNode) {
    emitCallNodeRequest(asset)
  }
}

async function maybeGenerateFieldsFromBinding(asset: ToolInfo | CompositionInfo): Promise<boolean> {
  const generatedKey = bindingGeneratedKey(asset)
  const fields = config.value.fields || []
  const canOverwrite =
    fields.length === 0
    || binding.value.generatedFrom === generatedKey
    || isDefaultInteractionFields(fields)

  if (canOverwrite) {
    await applyFieldsFromBinding(asset, false)
    return true
  }

  try {
    await ElMessageBox.confirm(
      '当前交互节点已有手动字段，是否用所选工具的入参重新生成？',
      '更新交互字段',
      {
        confirmButtonText: '重新生成',
        cancelButtonText: '保留现有',
        type: 'warning',
      },
    )
    await applyFieldsFromBinding(asset, true)
    return true
  } catch {
    ElMessage.info('已保留现有交互字段，可稍后点击“从来源生成字段”手动更新。')
    return false
  }
}

async function applyFieldsFromBinding(asset: ToolInfo | CompositionInfo, notify: boolean) {
  const parameters = (asset.parameters || []).filter(isToolInputParameter)
  const fields = parameters.flatMap((parameter) => mapParameterToFields(parameter))
  config.value.fields = fields.length ? fields : [{
    name: 'input',
    key: 'input',
    type: 'string',
    required: true,
    description: '输入',
    component: 'input',
    source: 'input.message',
    targetPath: 'input',
    slotFilling: defaultSlotFilling(),
  }]
  binding.value.generatedFrom = bindingGeneratedKey(asset)
  config.value.title = config.value.title || asset.description || asset.name
  config.value.dataSources = {
    ...(config.value.dataSources || {}),
    binding: {
      sourceKind: binding.value.sourceKind,
      ref: asset.name,
      qualifiedName: asset.qualifiedName || null,
      projectCode: asset.projectCode || null,
      apiMethod: isTool(asset) ? asset.httpMethod || null : null,
      apiPath: isTool(asset) ? asset.endpointPath || null : null,
    },
  }
  if (notify) {
    ElMessage.success(`已生成 ${config.value.fields.length} 个交互字段`)
  }
}

async function handleAutoCreateCallNodeChange(value: string | number | boolean) {
  if (value !== true) {
    binding.value.autoCreateDisplayNode = false
    return
  }
  if (binding.value.autoCreateDisplayNode !== false) {
    binding.value.autoCreateDisplayNode = true
  }
  const asset = selectedBindingAsset.value
  if (!asset) {
    binding.value.autoCreateCallNode = false
    binding.value.autoCreateDisplayNode = false
    ElMessage.warning('请先选择一个工具、组合或项目接口。')
    return
  }
  const generated = await maybeGenerateFieldsFromBinding(asset)
  if (!generated) {
    binding.value.autoCreateCallNode = false
    binding.value.autoCreateDisplayNode = false
    ElMessage.info('已取消创建调用节点。')
    return
  }
  emitCallNodeRequest(asset)
}

async function handleAutoCreateDisplayNodeChange(value: string | number | boolean) {
  if (value !== true) {
    return
  }
  const asset = selectedBindingAsset.value
  if (!asset) {
    binding.value.autoCreateDisplayNode = false
    ElMessage.warning('请先选择一个工具、组合或项目接口。')
    return
  }
  if (!binding.value.autoCreateCallNode) {
    binding.value.autoCreateCallNode = true
  }
  const generated = await maybeGenerateFieldsFromBinding(asset)
  if (!generated) {
    binding.value.autoCreateCallNode = false
    binding.value.autoCreateDisplayNode = false
    ElMessage.info('已取消创建展示节点。')
    return
  }
  emitCallNodeRequest(asset)
}

function emitCallNodeRequest(asset: ToolInfo | CompositionInfo) {
  if (!asset?.name) return
  emit('createCallNode', {
    sourceKind: binding.value.sourceKind,
    ref: asset.name,
    qualifiedName: asset.qualifiedName || null,
    projectCode: asset.projectCode || null,
    projectId: asset.projectId || null,
    visibility: asset.visibility || null,
    apiMethod: isTool(asset) ? asset.httpMethod || null : null,
    apiPath: isTool(asset) ? asset.endpointPath || null : null,
    responseType: isTool(asset) ? asset.responseType || null : null,
    autoCreateDisplayNode: binding.value.autoCreateDisplayNode === true,
    label: `调用 ${asset.name}`,
    description: asset.description || '',
    outputAlias: `${normalizeFieldName(asset.name)}_result`,
    inputMapping: buildInputMapping(asset),
  })
}

function buildInputMapping(asset: ToolInfo | CompositionInfo) {
  const alias = config.value.outputAlias || 'interaction_output'
  const mapping: Record<string, string> = {}
  for (const parameter of asset.parameters || []) {
    collectInputMapping(parameter, alias, mapping)
  }
  return mapping
}

function collectInputMapping(parameter: ToolParameter, alias: string, mapping: Record<string, string>, targetPrefix = '', fieldPrefix = '') {
  if (!isToolInputParameter(parameter)) return
  const rawName = parameter.name || parameter.location?.toLowerCase() || 'param'
  const targetName = targetPrefix ? `${targetPrefix}.${rawName}` : rawName
  const fieldName = normalizeFieldName(fieldPrefix ? `${fieldPrefix}_${rawName}` : rawName)
  const inputChildren = (parameter.children || []).filter(isToolInputParameter)
  if (inputChildren.length) {
    for (const child of inputChildren) {
      collectInputMapping(child, alias, mapping, targetName, fieldName)
    }
    return
  }
  mapping[targetName] = `${alias}.targetArgs.${targetName}`
}

function bindingGeneratedKey(asset: ToolInfo | CompositionInfo) {
  return `${binding.value.sourceKind}:${asset.name}`
}

function isDefaultInteractionFields(fields: StudioFieldSchema[]) {
  if (fields.length !== 1) return false
  const field = fields[0]
  const name = field.name || field.key
  return (name === 'query' || name === 'input')
    && field.type === 'string'
    && field.required === true
    && (!field.source || field.source === 'input.message')
}

function addField() {
  const index = config.value.fields.length + 1
  config.value.fields.push({
    name: `field_${index}`,
    key: `field_${index}`,
    type: 'string',
    required: false,
    description: '',
    component: 'input',
    targetPath: `field_${index}`,
    slotFilling: defaultSlotFilling(),
  })
}

function removeField(index: number) {
  config.value.fields.splice(index, 1)
}

function syncFieldKey(field: StudioFieldSchema) {
  field.key = field.name
  if (!field.targetPath) {
    field.targetPath = field.name
  }
}

function syncJson(field: 'dataSources' | 'behavior' | 'renderSchema') {
  const text = field === 'dataSources' ? dataSourcesText.value : field === 'behavior' ? behaviorText.value : renderSchemaText.value
  try {
    config.value[field] = JSON.parse(text || '{}')
  } catch {
    ElMessage.error('JSON 格式不正确')
  }
}

function mapParameterToFields(parameter: ToolParameter, prefix = '', targetPrefix = ''): StudioFieldSchema[] {
  const rawName = parameter.name || parameter.location?.toLowerCase() || 'param'
  const targetName = targetPrefix ? `${targetPrefix}.${rawName}` : rawName
  const baseName = normalizeFieldName(prefix ? `${prefix}_${rawName}` : rawName)
  const inputChildren = (parameter.children || []).filter(isToolInputParameter)
  if (inputChildren.length) {
    return inputChildren.flatMap((child) => mapParameterToFields(child, baseName, targetName))
  }
  return [{
    name: baseName,
    key: baseName,
    type: fieldType(parameter.type),
    required: parameter.required === true,
    description: parameter.description || rawName,
    source: targetName,
    targetPath: targetName,
    component: fieldComponent(parameter.type),
    slotFilling: defaultSlotFilling(),
  }]
}

function defaultSlotFilling(): NonNullable<StudioFieldSchema['slotFilling']> {
  return {
    enabled: false,
    strategies: ['LLM'],
    confirmPolicy: 'LOW_CONFIDENCE',
    confidenceThreshold: 0.85,
    llmPrompt: '',
    modelInstanceId: '',
    patterns: [],
    dictionaryValues: [],
  }
}

function normalizeSlotFillingFields(fields: StudioFieldSchema[]) {
  for (const field of fields) {
    if (!field.slotFilling) {
      field.slotFilling = defaultSlotFilling()
    }
    const strategies = normalizeSlotStrategies(field)
    if (!sameStringList(field.slotFilling.strategies, strategies)) {
      field.slotFilling.strategies = strategies
    }
    if (!field.slotFilling.confirmPolicy) {
      field.slotFilling.confirmPolicy = 'LOW_CONFIDENCE'
    }
    const confidenceThreshold = Number.isFinite(Number(field.slotFilling.confidenceThreshold))
      ? Number(field.slotFilling.confidenceThreshold)
      : 0.85
    if (field.slotFilling.confidenceThreshold !== confidenceThreshold) {
      field.slotFilling.confidenceThreshold = confidenceThreshold
    }
  }
}

function sameStringList(left: string[] | undefined, right: string[]) {
  if (!left || left.length !== right.length) return false
  return left.every((item, index) => item === right[index])
}

function samePorts(left: StudioPort[] | undefined, right: StudioPort[]) {
  if (!left || left.length !== right.length) return false
  return left.every((item, index) => {
    const next = right[index]
    return item.id === next.id
      && item.name === next.name
      && item.type === next.type
      && item.required === next.required
      && item.source === next.source
  })
}

function normalizeSlotStrategies(field: StudioFieldSchema): NonNullable<StudioFieldSchema['slotFilling']>['strategies'] {
  const strategies = field.slotFilling?.strategies?.filter((strategy) => strategy !== 'USER_INPUT') || []
  if (field.slotFilling?.enabled && strategies.length === 0) {
    return ['LLM']
  }
  return strategies.length ? strategies : ['LLM']
}

function handleSlotFillingToggle(field: StudioFieldSchema, value: string | number | boolean) {
  if (!field.slotFilling) {
    field.slotFilling = defaultSlotFilling()
  }
  if (value === true) {
    field.slotFilling.strategies = normalizeSlotStrategies(field)
  }
}

function normalizeFieldName(value: string) {
  const normalized = value.replace(/[^A-Za-z0-9_]/g, '_').replace(/^([^A-Za-z_])/, '_$1')
  return normalized || 'field'
}

function fieldType(value: string): StudioFieldSchema['type'] {
  const raw = (value || '').toLowerCase()
  if (['number', 'double', 'float', 'decimal'].includes(raw)) return 'number'
  if (['integer', 'int', 'long', 'short'].includes(raw)) return 'integer'
  if (['boolean', 'bool'].includes(raw)) return 'boolean'
  if (['array', 'list', 'set'].includes(raw)) return 'array'
  if (['object', 'json', 'map'].includes(raw)) return 'object'
  if (['file', 'multipartfile'].includes(raw)) return 'file'
  return 'string'
}

function fieldComponent(value: string) {
  const type = fieldType(value)
  if (type === 'boolean') return 'radio'
  if (type === 'file') return 'upload'
  return 'input'
}

function fieldSlotStrategiesLabel(field: StudioFieldSchema) {
  const labels: Record<string, string> = {
    USER_INPUT: '显式输入',
    RULE: '规则抽取',
    LLM: 'LLM 抽取',
    DICTIONARY: '字典匹配',
  }
  return (field.slotFilling?.strategies || ['USER_INPUT'])
    .map((strategy) => labels[strategy] || strategy)
    .join(' / ')
}

function assetLabel(item: ToolInfo | CompositionInfo) {
  const project = item.projectCode ? ` / ${item.projectCode}` : ''
  const visibility = item.visibility ? ` / ${item.visibility}` : ''
  return `${item.name}${project}${visibility}`
}

function apiLabel(item: ToolInfo) {
  const method = item.httpMethod ? `${item.httpMethod.toUpperCase()} ` : ''
  const path = item.endpointPath || item.sourceLocation || item.name
  const project = item.projectCode ? ` / ${item.projectCode}` : ''
  return `${method}${path}${project}`
}

function isTool(item: ToolInfo | CompositionInfo | undefined): item is ToolInfo {
  return !!item && ('httpMethod' in item || 'endpointPath' in item || 'source' in item)
}
</script>

<style scoped lang="scss">
.interaction-panel {
  gap: 16px;
}

.interaction-section {
  display: grid;
  gap: 14px;
  padding: 16px;
  border: 1px solid #e2e8f0;
  border-radius: 14px;
  background:
    linear-gradient(180deg, rgba(248, 250, 252, 0.92), rgba(255, 255, 255, 0.98)),
    #ffffff;
  box-shadow: 0 14px 34px rgba(15, 23, 42, 0.04);
}

.interaction-section-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;

  > div {
    display: grid;
    gap: 4px;
    min-width: 0;
  }

  strong {
    color: #0f172a;
    font-size: 15px;
    font-weight: 850;
  }

  span {
    overflow: hidden;
    color: #64748b;
    font-size: 12px;
    font-weight: 650;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
}

.interaction-form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;

  :deep(.el-form-item) {
    display: grid;
    gap: 8px;
    margin: 0;
  }

  :deep(.el-form-item__label) {
    justify-content: flex-start;
    height: auto;
    line-height: 1;
  }

  :deep(.el-form-item__content) {
    min-width: 0;
  }
}

.binding-grid {
  grid-template-columns: minmax(220px, 0.72fr) minmax(0, 1.28fr);
}

.api-binding-row {
  display: flex;
  width: 100%;
  gap: 8px;
}

.api-binding-row .el-button {
  flex: 0 0 auto;
}

.api-asset-picker-toolbar {
  display: grid;
  grid-template-columns: minmax(240px, 1fr) 160px auto auto;
  gap: 8px;
  margin-bottom: 12px;
}

.api-asset-cell {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.api-asset-cell span {
  overflow: hidden;
  color: #64748b;
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.api-asset-picker-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-top: 12px;
  color: #64748b;
  font-size: 12px;
}

.interaction-segmented {
  display: inline-flex;
  width: fit-content;
  max-width: 100%;
  overflow: hidden;
  border-radius: 10px;
  background: #eef2ff;
  padding: 3px;
}

.interaction-type-segmented {
  width: 100%;
}

.interaction-segmented-item {
  position: relative;
  z-index: 1;
  min-height: 34px;
  padding: 0 15px;
  border: 0;
  border-radius: 8px;
  background: transparent;
  color: #334155;
  cursor: pointer;
  font: inherit;
  font-size: 13px;
  font-weight: 800;
  white-space: nowrap;
  transition:
    background 0.16s ease,
    color 0.16s ease,
    box-shadow 0.16s ease;

  &:hover {
    color: #4f46e5;
  }

  &.active {
    background: linear-gradient(135deg, #635bff, #4f46e5);
    color: #ffffff;
    box-shadow: 0 8px 18px rgba(79, 70, 229, 0.22);
  }
}

.interaction-inline-option {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 12px 14px;
  border: 1px solid #dbeafe;
  border-radius: 12px;
  background: #f8fbff;

  > div {
    display: grid;
    gap: 4px;
  }

  strong {
    color: #1e293b;
    font-size: 13px;
    font-weight: 800;
  }

  span {
    color: #64748b;
    font-size: 12px;
    font-weight: 650;
  }
}

.fields-section {
  padding-bottom: 12px;
}

.interaction-field-list {
  display: grid;
  gap: 12px;
}

.interaction-field-card {
  display: grid;
  gap: 14px;
  padding: 14px;
  border: 1px solid #e2e8f0;
  border-radius: 13px;
  background: #ffffff;
  box-shadow: 0 12px 28px rgba(15, 23, 42, 0.035);
}

.interaction-field-card-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

.field-title {
  display: grid;
  grid-template-columns: 26px minmax(0, auto);
  gap: 2px 10px;
  min-width: 0;

  span {
    display: inline-flex;
    grid-row: span 2;
    align-items: center;
    justify-content: center;
    width: 26px;
    height: 26px;
    border-radius: 8px;
    background: #eef2ff;
    color: #4f46e5;
    font-size: 12px;
    font-weight: 850;
  }

  strong,
  em {
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  strong {
    color: #0f172a;
    font-size: 14px;
    font-weight: 850;
  }

  em {
    color: #64748b;
    font-size: 12px;
    font-style: normal;
    font-weight: 650;
  }
}

.field-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: flex-end;
  gap: 8px;
}

.field-basic-grid,
.field-slot-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;

  :deep(.el-form-item) {
    display: grid;
    gap: 8px;
    margin: 0;
  }

  :deep(.el-form-item__label) {
    justify-content: flex-start;
    height: auto;
    color: #64748b;
    font-size: 12px;
    line-height: 1;
  }
}

.field-collect-row {
  display: grid;
  grid-template-columns: auto auto;
  gap: 10px;
  align-items: center;
  justify-content: flex-start;
  padding: 12px;
  border-radius: 12px;
  background: #f8fafc;

  &.is-slot-enabled {
    grid-template-columns: auto auto minmax(190px, 1fr) minmax(150px, 0.7fr) 132px;
  }

  :deep(.el-select) {
    width: 100%;
  }

  :deep(.el-input-number) {
    width: 132px;
  }
}

.field-slot-grid {
  padding-top: 2px;
}

.interaction-advanced {
  border: 1px solid #e2e8f0;
  border-radius: 14px;
  overflow: hidden;
  background: #ffffff;

  :deep(.el-collapse-item__header) {
    padding: 0 16px;
    color: #334155;
    font-weight: 800;
  }

  :deep(.el-collapse-item__content) {
    padding: 0 16px 16px;
  }
}

@media (max-width: 900px) {
  .interaction-form-grid,
  .binding-grid,
  .field-basic-grid,
  .field-slot-grid,
  .field-collect-row {
    grid-template-columns: 1fr;
  }

  .interaction-section-head,
  .interaction-field-card-head,
  .interaction-inline-option {
    align-items: stretch;
    flex-direction: column;
  }

  .field-actions {
    justify-content: flex-start;
  }
}
</style>
