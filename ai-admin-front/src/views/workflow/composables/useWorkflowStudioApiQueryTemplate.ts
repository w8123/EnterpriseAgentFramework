import { ElMessage } from 'element-plus'
import { reactive, ref, type Ref } from 'vue'
import { useRoute } from 'vue-router'
import { listApiAssets } from '@/api/apiAsset'
import type { ApiAssetItem } from '@/types/apiAsset'
import type { ToolParameter } from '@/types/tool'
import type { CanvasEdge, CanvasNode, CanvasNodeKind, StudioFieldSchema } from '@/types/studio'
import type { WorkflowStudioState } from '@/types/workflow'
import { createWorkflowCanvasNode } from '@/utils/workflowStudio'
import { interactionOutputPorts } from '@/utils/studio'

export interface UseWorkflowStudioApiQueryTemplateDeps {
  studio: Ref<WorkflowStudioState | null>
  nodes: Ref<CanvasNode[]>
  edges: Ref<CanvasEdge[]>
  selectedNodeId: Ref<string | null>
  selectedEdgeId: Ref<string | null>
  propertyPanelCollapsed: Ref<boolean>
  decorateWorkflowNode: (node: CanvasNode) => CanvasNode
  decorateWorkflowEdge: (edge: CanvasEdge) => CanvasEdge
  markCanvasDirty: () => void
  syncJsonFromCanvas: () => void
}

function queryString(value: unknown) {
  if (Array.isArray(value)) return value[0] == null ? '' : String(value[0])
  return value == null ? '' : String(value)
}

function normalizeTemplateName(value: string) {
  const normalized = value
    .trim()
    .replace(/([a-z0-9])([A-Z])/g, '$1_$2')
    .replace(/[^a-zA-Z0-9_]+/g, '_')
    .replace(/^_+|_+$/g, '')
    .toLowerCase()
  return normalized || 'api'
}

function isApiInputParameter(parameter: ToolParameter) {
  return (parameter.location || '').toUpperCase() !== 'RESPONSE'
}

function escapeRegex(value: string) {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}

function fieldRuleAliases(name: string, description: string) {
  const aliases = new Set<string>()
  const add = (value?: string | null) => {
    const alias = String(value || '').trim()
    if (alias && alias.length <= 30) aliases.add(alias)
  }
  add(name)
  if (description && description.length <= 80) {
    description.split(/[/、,，;；|\s]+/).forEach(add)
  }
  return Array.from(aliases)
}

function slotRulePatterns(name: string, description: string) {
  const value = '([^，。,.\\s的]+)'
  return fieldRuleAliases(name, description)
    .map(alias => `${escapeRegex(alias)}(?:为|是|叫|包含|包括|有|=|：|:)?\\s*${value}`)
}

function templateSlotFillingForField(name: string, description?: string | null): NonNullable<StudioFieldSchema['slotFilling']> {
  const patterns = slotRulePatterns(name, description || '')
  return {
    enabled: true,
    strategies: patterns.length ? ['RULE', 'LLM'] : ['LLM'],
    confirmPolicy: patterns.length ? 'NEVER' : 'LOW_CONFIDENCE',
    confidenceThreshold: 0.85,
    llmPrompt: '',
    modelInstanceId: '',
    patterns,
    dictionaryValues: [],
  }
}

function apiFieldType(type?: string | null): StudioFieldSchema['type'] {
  const normalized = String(type || '').toLowerCase()
  if (['int', 'integer', 'long', 'double', 'float', 'decimal', 'number'].includes(normalized)) return 'number'
  if (['bool', 'boolean'].includes(normalized)) return 'boolean'
  if (['array', 'list'].includes(normalized)) return 'array'
  if (['object', 'json'].includes(normalized)) return 'object'
  return 'string'
}

function apiFieldComponent(type?: string | null): StudioFieldSchema['component'] {
  const normalized = String(type || '').toLowerCase()
  if (['bool', 'boolean'].includes(normalized)) return 'switch'
  if (['array', 'list', 'object', 'json'].includes(normalized)) return 'textarea'
  return 'input'
}

function apiParameterToFields(parameter: ToolParameter, prefix = ''): StudioFieldSchema[] {
  if (!isApiInputParameter(parameter)) return []
  const rawName = parameter.name || 'param'
  const targetPath = prefix ? `${prefix}.${rawName}` : rawName
  const children = (parameter.children || []).filter(isApiInputParameter)
  if (children.length) {
    return children.flatMap((child) => apiParameterToFields(child, targetPath))
  }
  const name = normalizeTemplateName(targetPath.replace(/\./g, '_'))
  return [{
    name,
    key: name,
    type: apiFieldType(parameter.type),
    required: Boolean(parameter.required),
    description: parameter.description || rawName,
    component: apiFieldComponent(parameter.type),
    source: targetPath,
    targetPath,
    slotFilling: templateSlotFillingForField(rawName, parameter.description || rawName),
  }]
}

function apiAssetToInteractionFields(asset: ApiAssetItem): StudioFieldSchema[] {
  const fields = (asset.parameters || []).flatMap((parameter) => apiParameterToFields(parameter))
  if (fields.length) return fields
  return [{
    name: 'query',
    key: 'query',
    type: 'string',
    required: true,
    description: '查询条件',
    component: 'input',
    source: 'input.message',
    targetPath: 'query',
    slotFilling: templateSlotFillingForField('query', '查询条件'),
  }]
}

function collectPageActionMapping(parameter: ToolParameter, mapping: Record<string, string>, queryAlias: string, prefix = '') {
  if (!isApiInputParameter(parameter)) return
  const rawName = parameter.name || 'param'
  const targetPath = prefix ? `${prefix}.${rawName}` : rawName
  const children = (parameter.children || []).filter(isApiInputParameter)
  if (children.length) {
    for (const child of children) collectPageActionMapping(child, mapping, queryAlias, targetPath)
    return
  }
  mapping[targetPath] = `${queryAlias}.targetArgs.${targetPath}`
}

function apiAssetPageActionMapping(asset: ApiAssetItem, queryAlias: string) {
  const mapping: Record<string, string> = {}
  for (const parameter of asset.parameters || []) {
    collectPageActionMapping(parameter, mapping, queryAlias)
  }
  if (!Object.keys(mapping).length) {
    mapping.query = `${queryAlias}.targetArgs.query`
  }
  return mapping
}

function collectApiInputMapping(parameter: ToolParameter, mapping: Record<string, string>, queryAlias: string, prefix = '') {
  if (!isApiInputParameter(parameter)) return
  const rawName = parameter.name || 'param'
  const targetPath = prefix ? `${prefix}.${rawName}` : rawName
  const children = (parameter.children || []).filter(isApiInputParameter)
  if (children.length) {
    for (const child of children) collectApiInputMapping(child, mapping, queryAlias, targetPath)
    return
  }
  mapping[targetPath] = `${queryAlias}.targetArgs.${targetPath}`
}

function apiAssetInputMapping(asset: ApiAssetItem, queryAlias: string) {
  const mapping: Record<string, string> = {}
  for (const parameter of asset.parameters || []) {
    collectApiInputMapping(parameter, mapping, queryAlias)
  }
  if (!Object.keys(mapping).length) {
    mapping.query = `${queryAlias}.targetArgs.query`
  }
  return mapping
}

function apiAssetTemplateMetadata(asset: ApiAssetItem) {
  return {
    apiId: asset.apiId,
    name: asset.name,
    globalToolName: asset.globalToolName || null,
    qualifiedName: asset.globalToolQualifiedName || null,
    projectCode: asset.projectCode || null,
    httpMethod: asset.httpMethod || null,
    endpointPath: asset.endpointPath || null,
  }
}

function callNodeInputsFromMapping(mapping: Record<string, string>) {
  return Object.keys(mapping).map((key) => ({
    id: key,
    name: key,
    type: 'any' as const,
    required: false,
    source: mapping[key],
  }))
}

export function useWorkflowStudioApiQueryTemplate(deps: UseWorkflowStudioApiQueryTemplateDeps) {
  const route = useRoute()

  const apiQueryTemplateOpen = ref(false)
  const apiQueryTemplateLoading = ref(false)
  const apiQueryTemplateAssets = ref<ApiAssetItem[]>([])
  const apiQueryTemplateTotal = ref(0)
  const apiQueryTemplateActionKey = ref('page.search.applyFilters')
  const apiQueryTemplateRouteAssetId = ref<number | null>(null)
  const apiQueryTemplateFilters = reactive({
    keyword: '',
    toolLinkStatus: '',
    page: 1,
    pageSize: 10,
  })

  function routeApiAssetContext() {
    if (queryString(route.query.intent) !== 'api-query-template') return null
    const id = Number(queryString(route.query.apiAssetId))
    const tool = queryString(route.query.apiAssetTool)
    const name = queryString(route.query.apiAssetName)
    if (!Number.isFinite(id) && !tool && !name) return null
    return {
      id: Number.isFinite(id) && id > 0 ? id : null,
      keyword: tool || name,
    }
  }

  function prioritizeRouteApiAsset(items: ApiAssetItem[]) {
    if (!apiQueryTemplateRouteAssetId.value) return items
    const index = items.findIndex((item) => item.apiId === apiQueryTemplateRouteAssetId.value)
    if (index <= 0) return items
    const next = [...items]
    const [matched] = next.splice(index, 1)
    next.unshift(matched)
    return next
  }

  async function loadApiQueryTemplateAssets() {
    apiQueryTemplateLoading.value = true
    try {
      const { data } = await listApiAssets({
        projectId: deps.studio.value?.projectId || undefined,
        keyword: apiQueryTemplateFilters.keyword || undefined,
        toolLinkStatus: apiQueryTemplateFilters.toolLinkStatus || undefined,
        page: apiQueryTemplateFilters.page,
        pageSize: apiQueryTemplateFilters.pageSize,
      })
      apiQueryTemplateAssets.value = prioritizeRouteApiAsset(data.items || [])
      apiQueryTemplateTotal.value = data.total || 0
    } catch {
      apiQueryTemplateAssets.value = []
      apiQueryTemplateTotal.value = 0
      ElMessage.error('加载 API 资产失败')
    } finally {
      apiQueryTemplateLoading.value = false
    }
  }

  function openApiQueryTemplateDialog() {
    apiQueryTemplateOpen.value = true
    apiQueryTemplateActionKey.value = apiQueryTemplateActionKey.value || 'page.search.applyFilters'
    apiQueryTemplateFilters.page = 1
    void loadApiQueryTemplateAssets()
  }

  function reloadApiQueryTemplateAssets() {
    apiQueryTemplateFilters.page = 1
    void loadApiQueryTemplateAssets()
  }

  function applyApiAssetRouteContext() {
    const context = routeApiAssetContext()
    if (!context) return
    apiQueryTemplateRouteAssetId.value = context.id
    apiQueryTemplateFilters.keyword = context.keyword
    apiQueryTemplateFilters.toolLinkStatus = 'LINKED'
    openApiQueryTemplateDialog()
  }

  function apiQueryTemplateRowClassName({ row }: { row: ApiAssetItem }) {
    return row.apiId === apiQueryTemplateRouteAssetId.value ? 'is-route-api-asset' : ''
  }

  function apiQueryTemplateSelectable(asset: ApiAssetItem) {
    return asset.toolLinkStatus === 'LINKED' && !!asset.globalToolName && asset.enabled && asset.agentVisible && !asset.removedFromSource
  }

  function apiQueryTemplateStatusLabel(asset: ApiAssetItem) {
    if (asset.removedFromSource) return '源接口已移除'
    if (asset.toolLinkStatus !== 'LINKED') return '需先关联 Tool'
    if (!asset.enabled) return '未启用'
    if (!asset.agentVisible) return 'Workflow 不可见'
    return '可生成'
  }

  function addWorkflowStudioNode(kind: CanvasNodeKind, position: { x: number; y: number }, select = true) {
    if (!deps.studio.value) throw new Error('Workflow 未加载')
    const node = deps.decorateWorkflowNode(createWorkflowCanvasNode(kind, position, deps.studio.value))
    deps.nodes.value.push(node)
    if (select) {
      deps.selectedNodeId.value = node.id
      deps.selectedEdgeId.value = null
    }
    return node
  }

  function ensureCanvasEdge(source: string, target: string) {
    if (deps.edges.value.some((edge) => edge.source === source && edge.target === target)) return
    deps.edges.value.push(deps.decorateWorkflowEdge({
      id: `e-${source}-${target}-${Date.now()}`,
      source,
      target,
      condition: 'always',
      label: 'always',
    }))
  }

  function generateApiQueryTemplate(asset: ApiAssetItem) {
    if (!deps.studio.value) return
    if (!apiQueryTemplateSelectable(asset)) {
      ElMessage.warning('该接口还不能生成查询流程，请先完成 Tool 关联并开启 Workflow 可见。')
      return
    }
    const baseName = normalizeTemplateName(asset.name || asset.globalToolName || 'api')
    const queryAlias = `${baseName}_query`
    const actionAlias = `${baseName}_page_action`
    const resultAlias = `${baseName}_result`
    const displayAlias = `${baseName}_display`
    const y = 180 + Math.max(0, deps.nodes.value.length - 2) * 18
    const interactionNode = addWorkflowStudioNode('interaction', { x: 260, y }, false)
    const pageActionNode = addWorkflowStudioNode('pageAction', { x: 600, y }, false)
    const toolNode = addWorkflowStudioNode('tool', { x: 940, y }, false)
    const displayNode = addWorkflowStudioNode('interaction', { x: 1280, y }, false)
    const fields = apiAssetToInteractionFields(asset)
    const inputMapping = apiAssetInputMapping(asset, queryAlias)

    interactionNode.data.label = `${asset.name} 查询条件`
    interactionNode.data.description = asset.aiDescription || asset.description || '从 API 资产生成的查询条件收集节点'
    interactionNode.data.outputAlias = queryAlias
    interactionNode.data.interactionConfig = {
      interactionType: 'COLLECT_INPUT',
      binding: {
        sourceKind: 'API',
        ref: asset.globalToolName || asset.name,
        qualifiedName: asset.globalToolQualifiedName || asset.globalToolName || asset.name,
        projectCode: asset.projectCode || null,
        projectId: asset.projectId,
        apiNodeId: asset.apiId,
        apiMethod: asset.httpMethod || null,
        apiPath: asset.endpointPath || null,
        generatedFrom: `API:${asset.apiId}`,
        autoCreateCallNode: true,
        autoCreateDisplayNode: true,
        callNodeId: toolNode.id,
        displayNodeId: displayNode.id,
      },
      title: `${asset.name} 查询条件`,
      component: 'FORM',
      fields,
      dataExpression: 'lastOutput',
      outputAlias: queryAlias,
      dataSources: { apiAsset: apiAssetTemplateMetadata(asset) },
      behavior: { askMissing: true, maxTurns: 6 },
      renderSchema: {},
    }
    interactionNode.data.outputs = interactionOutputPorts(interactionNode.data.interactionConfig, queryAlias)

    pageActionNode.data.label = '驱动页面查询'
    pageActionNode.data.description = '请求嵌入的业务页面填入查询条件并触发搜索'
    pageActionNode.data.outputAlias = actionAlias
    pageActionNode.data.pageActionConfig = {
      projectCode: asset.projectCode || '',
      actionKey: apiQueryTemplateActionKey.value.trim() || 'page.search.applyFilters',
      title: `页面查询：${asset.name}`,
      confirm: false,
      args: apiAssetPageActionMapping(asset, queryAlias),
      outputAlias: actionAlias,
      metadata: {
        projectCode: asset.projectCode || null,
        apiId: asset.apiId,
        apiName: asset.name,
        endpointPath: asset.endpointPath || null,
      },
    }
    pageActionNode.data.inputs = [{ id: queryAlias, name: queryAlias, type: 'object', required: false, source: queryAlias }]
    pageActionNode.data.outputs = [{ id: actionAlias, name: actionAlias, type: 'object' }]

    toolNode.data.label = `调用 ${asset.globalToolName || asset.name}`
    toolNode.data.description = asset.aiDescription || asset.description || '调用已关联的 API Tool'
    toolNode.data.outputAlias = resultAlias
    toolNode.data.inputs = callNodeInputsFromMapping(inputMapping)
    toolNode.data.outputs = [{ id: resultAlias, name: resultAlias, type: 'any' }]
    toolNode.data.toolConfig = {
      ref: asset.globalToolName || asset.name,
      qualifiedName: asset.globalToolQualifiedName || asset.globalToolName || asset.name,
      projectCode: asset.projectCode || null,
      visibility: 'PROJECT',
      credentialRef: '',
      maxRequestTimeMs: 180000,
      inputMapping,
      mappingNote: `由 API 查询流程向导生成：${asset.httpMethod || ''} ${asset.endpointPath || asset.name}`.trim(),
    }

    displayNode.data.label = `${asset.name} 查询结果`
    displayNode.data.description = '展示查询接口返回结果'
    displayNode.data.outputAlias = displayAlias
    displayNode.data.interactionConfig = {
      interactionType: 'PRESENT_OUTPUT',
      binding: { sourceKind: 'NONE' },
      title: `${asset.name} 查询结果`,
      component: 'TABLE',
      fields: [],
      dataExpression: resultAlias,
      outputAlias: displayAlias,
      dataSources: {
        source: { nodeId: toolNode.id, outputAlias: resultAlias, apiId: asset.apiId },
      },
      behavior: { acknowledge: false },
      renderSchema: {
        apiName: asset.name,
        endpointPath: asset.endpointPath || null,
        responseType: asset.responseType || null,
      },
    }
    displayNode.data.outputs = interactionOutputPorts(displayNode.data.interactionConfig, displayAlias)

    ensureCanvasEdge(interactionNode.id, pageActionNode.id)
    ensureCanvasEdge(pageActionNode.id, toolNode.id)
    ensureCanvasEdge(toolNode.id, displayNode.id)
    deps.selectedNodeId.value = interactionNode.id
    deps.selectedEdgeId.value = null
    deps.propertyPanelCollapsed.value = false
    apiQueryTemplateOpen.value = false
    deps.markCanvasDirty()
    deps.syncJsonFromCanvas()
    ElMessage.success('已生成 API 查询流程')
  }

  return {
    apiQueryTemplateOpen,
    apiQueryTemplateLoading,
    apiQueryTemplateAssets,
    apiQueryTemplateTotal,
    apiQueryTemplateActionKey,
    apiQueryTemplateFilters,
    openApiQueryTemplateDialog,
    reloadApiQueryTemplateAssets,
    loadApiQueryTemplateAssets,
    applyApiAssetRouteContext,
    apiQueryTemplateRowClassName,
    apiQueryTemplateSelectable,
    apiQueryTemplateStatusLabel,
    generateApiQueryTemplate,
  }
}
