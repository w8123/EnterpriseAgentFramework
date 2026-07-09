import { ElMessage } from 'element-plus'
import { reactive, ref, type Ref } from 'vue'
import { useRoute } from 'vue-router'
import { getScanProjectTools } from '@/api/scanProject'
import type { ProjectToolInfo } from '@/types/scanProject'
import type { ToolParameter } from '@/types/tool'
import type { CanvasEdge, CanvasNode, CanvasNodeKind, StudioFieldSchema } from '@/types/studio'
import type { WorkflowStudioState } from '@/types/workflow'
import { createWorkflowCanvasNode } from '@/utils/workflowStudio'
import { interactionOutputPorts } from '@/utils/studio'
import {
  filterProjectApiTools,
  isProjectApiToolSelectable,
  pageProjectApiTools,
  projectApiToolQualifiedName,
  projectApiToolRef,
  projectApiToolStatusLabel,
} from '@/utils/projectApiTools'

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

function projectApiToInteractionFields(tool: ProjectToolInfo): StudioFieldSchema[] {
  const fields = (tool.parameters || []).flatMap((parameter) => apiParameterToFields(parameter))
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

function projectApiPageActionMapping(tool: ProjectToolInfo, queryAlias: string) {
  const mapping: Record<string, string> = {}
  for (const parameter of tool.parameters || []) {
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

function projectApiInputMapping(tool: ProjectToolInfo, queryAlias: string) {
  const mapping: Record<string, string> = {}
  for (const parameter of tool.parameters || []) {
    collectApiInputMapping(parameter, mapping, queryAlias)
  }
  if (!Object.keys(mapping).length) {
    mapping.query = `${queryAlias}.targetArgs.query`
  }
  return mapping
}

function projectApiTemplateMetadata(tool: ProjectToolInfo, projectCode?: string | null) {
  return {
    scanToolId: tool.scanToolId,
    name: tool.name,
    toolRef: projectApiToolRef(tool),
    qualifiedName: projectApiToolQualifiedName(tool, projectCode),
    projectCode: tool.projectCode || projectCode || null,
    httpMethod: tool.httpMethod || null,
    endpointPath: tool.endpointPath || null,
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
  const apiQueryTemplateTools = ref<ProjectToolInfo[]>([])
  const apiQueryTemplateTotal = ref(0)
  const apiQueryTemplateActionKey = ref('page.search.applyFilters')
  const apiQueryTemplateRouteToolId = ref<number | null>(null)
  const apiQueryTemplateFilters = reactive({
    keyword: '',
    toolLinkStatus: '',
    page: 1,
    pageSize: 10,
  })

  function routeProjectApiContext() {
    if (queryString(route.query.intent) !== 'api-query-template') return null
    const id = Number(queryString(route.query.scanToolId))
    const tool = queryString(route.query.projectApiTool)
    const name = queryString(route.query.projectApiName)
    if (!Number.isFinite(id) && !tool && !name) return null
    return {
      id: Number.isFinite(id) && id > 0 ? id : null,
      keyword: tool || name,
    }
  }

  function prioritizeRouteProjectApiTool(items: ProjectToolInfo[]) {
    if (!apiQueryTemplateRouteToolId.value) return items
    const index = items.findIndex((item) => item.scanToolId === apiQueryTemplateRouteToolId.value)
    if (index <= 0) return items
    const next = [...items]
    const [matched] = next.splice(index, 1)
    next.unshift(matched)
    return next
  }

  async function loadApiQueryTemplateTools() {
    const projectId = deps.studio.value?.projectId
    if (!projectId) {
      apiQueryTemplateTools.value = []
      apiQueryTemplateTotal.value = 0
      ElMessage.warning('请先选择项目后再从项目接口生成查询流程')
      return
    }
    apiQueryTemplateLoading.value = true
    try {
      const { data } = await getScanProjectTools(projectId, 'full')
      const filtered = filterProjectApiTools(data || [], apiQueryTemplateFilters)
      apiQueryTemplateTools.value = prioritizeRouteProjectApiTool(
        pageProjectApiTools(filtered, apiQueryTemplateFilters.page, apiQueryTemplateFilters.pageSize),
      )
      apiQueryTemplateTotal.value = filtered.length
    } catch {
      apiQueryTemplateTools.value = []
      apiQueryTemplateTotal.value = 0
      ElMessage.error('加载项目接口失败')
    } finally {
      apiQueryTemplateLoading.value = false
    }
  }

  function openApiQueryTemplateDialog() {
    apiQueryTemplateOpen.value = true
    apiQueryTemplateActionKey.value = apiQueryTemplateActionKey.value || 'page.search.applyFilters'
    apiQueryTemplateFilters.page = 1
    void loadApiQueryTemplateTools()
  }

  function reloadApiQueryTemplateTools() {
    apiQueryTemplateFilters.page = 1
    void loadApiQueryTemplateTools()
  }

  function applyProjectApiRouteContext() {
    const context = routeProjectApiContext()
    if (!context) return
    apiQueryTemplateRouteToolId.value = context.id
    apiQueryTemplateFilters.keyword = context.keyword
    apiQueryTemplateFilters.toolLinkStatus = 'LINKED'
    openApiQueryTemplateDialog()
  }

  function apiQueryTemplateRowClassName({ row }: { row: ProjectToolInfo }) {
    return row.scanToolId === apiQueryTemplateRouteToolId.value ? 'is-route-project-api' : ''
  }

  function apiQueryTemplateSelectable(tool: ProjectToolInfo) {
    return isProjectApiToolSelectable(tool)
  }

  function apiQueryTemplateStatusLabel(tool: ProjectToolInfo) {
    return projectApiToolStatusLabel(tool)
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

  function generateApiQueryTemplate(tool: ProjectToolInfo) {
    if (!deps.studio.value) return
    if (!apiQueryTemplateSelectable(tool)) {
      ElMessage.warning('该接口还不能生成查询流程，请先完成 Tool 关联并开启 Workflow 可见。')
      return
    }
    const toolRef = projectApiToolRef(tool)
    const qualifiedName = projectApiToolQualifiedName(tool, deps.studio.value.projectCode)
    const projectCode = tool.projectCode || deps.studio.value.projectCode || null
    const baseName = normalizeTemplateName(tool.name || toolRef || 'api')
    const queryAlias = `${baseName}_query`
    const actionAlias = `${baseName}_page_action`
    const resultAlias = `${baseName}_result`
    const displayAlias = `${baseName}_display`
    const y = 180 + Math.max(0, deps.nodes.value.length - 2) * 18
    const interactionNode = addWorkflowStudioNode('interaction', { x: 260, y }, false)
    const pageActionNode = addWorkflowStudioNode('pageAction', { x: 600, y }, false)
    const toolNode = addWorkflowStudioNode('tool', { x: 940, y }, false)
    const displayNode = addWorkflowStudioNode('interaction', { x: 1280, y }, false)
    const fields = projectApiToInteractionFields(tool)
    const inputMapping = projectApiInputMapping(tool, queryAlias)

    interactionNode.data.label = `${tool.name} 查询条件`
    interactionNode.data.description = tool.aiDescription || tool.description || '从项目接口生成的查询条件收集节点'
    interactionNode.data.outputAlias = queryAlias
    interactionNode.data.interactionConfig = {
      interactionType: 'COLLECT_INPUT',
      binding: {
        sourceKind: 'API',
        ref: toolRef,
        qualifiedName,
        projectCode,
        projectId: tool.projectId || deps.studio.value.projectId || null,
        apiNodeId: tool.scanToolId,
        apiMethod: tool.httpMethod || null,
        apiPath: tool.endpointPath || null,
        generatedFrom: `API:${tool.scanToolId}`,
        autoCreateCallNode: true,
        autoCreateDisplayNode: true,
        callNodeId: toolNode.id,
        displayNodeId: displayNode.id,
      },
      title: `${tool.name} 查询条件`,
      component: 'FORM',
      fields,
      dataExpression: 'lastOutput',
      outputAlias: queryAlias,
      dataSources: { projectApi: projectApiTemplateMetadata(tool, deps.studio.value.projectCode) },
      behavior: { askMissing: true, maxTurns: 6 },
      renderSchema: {},
    }
    interactionNode.data.outputs = interactionOutputPorts(interactionNode.data.interactionConfig, queryAlias)

    pageActionNode.data.label = '驱动页面查询'
    pageActionNode.data.description = '请求嵌入的业务页面填入查询条件并触发搜索'
    pageActionNode.data.outputAlias = actionAlias
    pageActionNode.data.pageActionConfig = {
      projectCode: projectCode || '',
      actionKey: apiQueryTemplateActionKey.value.trim() || 'page.search.applyFilters',
      title: `页面查询：${tool.name}`,
      confirm: false,
      args: projectApiPageActionMapping(tool, queryAlias),
      outputAlias: actionAlias,
      metadata: {
        projectCode,
        scanToolId: tool.scanToolId,
        apiName: tool.name,
        endpointPath: tool.endpointPath || null,
      },
    }
    pageActionNode.data.inputs = [{ id: queryAlias, name: queryAlias, type: 'object', required: false, source: queryAlias }]
    pageActionNode.data.outputs = [{ id: actionAlias, name: actionAlias, type: 'object' }]

    toolNode.data.label = `调用 ${toolRef}`
    toolNode.data.description = tool.aiDescription || tool.description || '调用已关联的项目接口 Tool'
    toolNode.data.outputAlias = resultAlias
    toolNode.data.inputs = callNodeInputsFromMapping(inputMapping)
    toolNode.data.outputs = [{ id: resultAlias, name: resultAlias, type: 'any' }]
    toolNode.data.toolConfig = {
      ref: toolRef,
      qualifiedName,
      projectCode,
      visibility: 'PROJECT',
      credentialRef: '',
      maxRequestTimeMs: 180000,
      inputMapping,
      mappingNote: `由项目 API 查询流程向导生成：${tool.httpMethod || ''} ${tool.endpointPath || tool.name}`.trim(),
    }

    displayNode.data.label = `${tool.name} 查询结果`
    displayNode.data.description = '展示查询接口返回结果'
    displayNode.data.outputAlias = displayAlias
    displayNode.data.interactionConfig = {
      interactionType: 'PRESENT_OUTPUT',
      binding: { sourceKind: 'NONE' },
      title: `${tool.name} 查询结果`,
      component: 'TABLE',
      fields: [],
      dataExpression: resultAlias,
      outputAlias: displayAlias,
      dataSources: {
        source: { nodeId: toolNode.id, outputAlias: resultAlias, scanToolId: tool.scanToolId },
      },
      behavior: { acknowledge: false },
      renderSchema: {
        apiName: tool.name,
        endpointPath: tool.endpointPath || null,
        responseType: tool.responseType || null,
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
    apiQueryTemplateTools,
    apiQueryTemplateTotal,
    apiQueryTemplateActionKey,
    apiQueryTemplateFilters,
    openApiQueryTemplateDialog,
    reloadApiQueryTemplateTools,
    loadApiQueryTemplateTools,
    applyProjectApiRouteContext,
    apiQueryTemplateRowClassName,
    apiQueryTemplateSelectable,
    apiQueryTemplateStatusLabel,
    generateApiQueryTemplate,
  }
}
