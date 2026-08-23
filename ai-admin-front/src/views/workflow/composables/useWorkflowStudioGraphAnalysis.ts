import { computed, type Ref } from 'vue'
import type { CanvasEdge, CanvasNode } from '@/types/studio'
import {
  isRouteCondition,
  isSupportedCanvasCondition,
} from '@/views/workflow/composables/useWorkflowStudioCanvasActions'
import {
  autoLayoutWorkflowNodes,
  type WorkflowNodeMeasurement,
} from '@/utils/workflowAutoLayout'

export type GraphLintItem = {
  level: 'error' | 'warning'
  message: string
  nodeId?: string
  edgeId?: string
}

export type WorkflowStudioGraphVariable = {
  name: string
  source: string
  nodeId: string
  label?: string
  group?: string
  description?: string
}

export interface UseWorkflowStudioGraphAnalysisDeps {
  nodes: Ref<CanvasNode[]>
  edges: Ref<CanvasEdge[]>
  decorateWorkflowNode: (node: CanvasNode) => CanvasNode
  markCanvasDirty: () => void
  syncJsonFromCanvas: () => void
  waitForNodeMeasurements: () => Promise<void>
  getNodeMeasurement: (nodeId: string) => WorkflowNodeMeasurement | undefined
}

function nodeInputMapping(data: CanvasNode['data']) {
  if (data.kind === 'tool') return data.toolConfig?.inputMapping || data.inputMapping || {}
  if (data.kind === 'mcp') return data.mcpConfig?.inputMapping || data.inputMapping || {}
  return data.inputMapping || {}
}

function isProbablyVariableReference(source: string) {
  const value = source.trim()
  if (!value || value.startsWith('const:') || value.startsWith('"') || value.startsWith("'")) return false
  if (['true', 'false', 'null'].includes(value)) return false
  return Number.isNaN(Number(value))
}

function isKnownVariableReference(
  source: string,
  graphVariables: WorkflowStudioGraphVariable[],
  nodes: CanvasNode[],
  currentNodeId?: string,
) {
  const value = source.trim().replace(/^\$/, '')
  if (!value) return true
  if (['input', 'answer', 'lastOutput', 'previousOutput', 'lastRoute', 'lastSuccess', 'lastError', 'params', 'sys'].includes(value)) return true
  if (value.startsWith('params.') || value.startsWith('sys.')) return true
  if (value.startsWith('nodeOutput.')) {
    const nodeId = value.slice('nodeOutput.'.length).split('.', 1)[0]
    return nodes.some((node) => node.id === nodeId)
  }
  if (value.startsWith('var.')) {
    const alias = value.slice('var.'.length).split('.', 1)[0]
    return graphVariables.some((item) => item.name === alias)
  }
  const alias = value.split('.', 1)[0]
  return graphVariables.some((item) => item.name === alias || item.nodeId === alias) || alias === currentNodeId
}

export function useWorkflowStudioGraphAnalysis(deps: UseWorkflowStudioGraphAnalysisDeps) {
  const canvasStats = computed(() => ({
    nodes: deps.nodes.value.length,
    edges: deps.edges.value.length,
  }))

  const graphVariables = computed<WorkflowStudioGraphVariable[]>(() => {
    const vars: WorkflowStudioGraphVariable[] = []
    for (const node of deps.nodes.value) {
      if (node.data.kind === 'start' || node.data.kind === 'end') continue
      if (node.data.kind === 'userInput') {
        const alias = node.data.userInputConfig?.outputAlias || node.data.outputAlias || 'params'
        const nodeLabel = node.data.label || node.id
        vars.push({ name: alias, source: nodeLabel, nodeId: node.id, label: `${nodeLabel} · 全部输入`, group: '用户输入', description: alias })
        for (const field of node.data.userInputConfig?.fields || []) {
          const name = field.name?.trim()
          if (name) {
            vars.push({
              name: `${alias}.${name}`,
              source: nodeLabel,
              nodeId: node.id,
              label: `${nodeLabel} · ${field.description || field.name}`,
              group: '用户输入',
              description: `${alias}.${name}`,
            })
          }
        }
        continue
      }
      if (node.data.kind === 'interaction') {
        const alias = node.data.interactionConfig?.outputAlias || node.data.outputAlias || 'interaction_output'
        const nodeLabel = node.data.interactionConfig?.title || node.data.label || node.id
        vars.push({ name: alias, source: nodeLabel, nodeId: node.id, label: `${nodeLabel} · 交互输出`, group: '交互变量', description: alias })
        for (const field of node.data.interactionConfig?.fields || []) {
          const name = (field.key || field.name || '').trim()
          if (name) {
            vars.push({
              name: `${alias}.${name}`,
              source: nodeLabel,
              nodeId: node.id,
              label: `${nodeLabel} · ${field.description || name}`,
              group: '交互变量',
              description: `${alias}.${name}`,
            })
          }
        }
        continue
      }
      if (node.data.outputAlias) {
        const alias = node.data.outputAlias
        const varPath = alias.startsWith('var.') ? alias : `var.${alias}`
        vars.push({
          name: varPath,
          source: node.data.label || node.id,
          nodeId: node.id,
          label: `${node.data.label || node.id} · 输出`,
          group: '业务变量',
          description: `规范路径：${varPath}`,
        })
      }
      vars.push({
        name: `nodeOutput.${node.id}`,
        source: node.data.label || node.id,
        nodeId: node.id,
        label: `${node.data.label || node.id} · 节点输出`,
        group: '节点输出',
        description: node.data.kind,
      })
    }
    return vars
  })

  const graphLintItems = computed<GraphLintItem[]>(() => {
    const items: GraphLintItem[] = []
    const nodeIds = new Set(deps.nodes.value.map((node) => node.id))
    const incoming = new Map<string, number>()
    const outgoing = new Map<string, number>()
    for (const edge of deps.edges.value) {
      if (!nodeIds.has(edge.source)) {
        items.push({ level: 'error', edgeId: edge.id, message: `连线来源不存在：${edge.source}` })
      }
      if (!nodeIds.has(edge.target)) {
        items.push({ level: 'error', edgeId: edge.id, message: `连线目标不存在：${edge.target}` })
      }
      outgoing.set(edge.source, (outgoing.get(edge.source) || 0) + 1)
      incoming.set(edge.target, (incoming.get(edge.target) || 0) + 1)
      const sourceNode = deps.nodes.value.find((node) => node.id === edge.source)
      if (!isSupportedCanvasCondition(edge.condition || edge.label, sourceNode)) {
        items.push({ level: 'warning', edgeId: edge.id, message: `连线条件可能无法命中：${edge.condition || edge.label}` })
      }
    }
    const startCount = deps.nodes.value.filter((node) => node.data.kind === 'start').length
    const endCount = deps.nodes.value.filter((node) => node.data.kind === 'end').length
    if (startCount !== 1) items.push({ level: 'error', message: `画布需要且仅需要 1 个开始节点，当前 ${startCount} 个` })
    if (endCount !== 1) items.push({ level: 'error', message: `画布需要且仅需要 1 个结束节点，当前 ${endCount} 个` })
    if (!(outgoing.get('start') || 0)) items.push({ level: 'error', nodeId: 'start', message: '开始节点没有出边' })
    if (!(incoming.get('end') || 0)) items.push({ level: 'warning', nodeId: 'end', message: '结束节点没有入边，保存时会尝试自动补边' })
    for (const node of deps.nodes.value) {
      if (node.data.kind === 'start' || node.data.kind === 'end') continue
      if (!(incoming.get(node.id) || 0)) {
        items.push({ level: 'warning', nodeId: node.id, message: `${node.data.label || node.id} 没有入边` })
      }
      if (!(outgoing.get(node.id) || 0)) {
        items.push({ level: 'warning', nodeId: node.id, message: `${node.data.label || node.id} 没有出边` })
      }
      if (node.data.kind === 'tool' && !node.data.toolConfig?.ref) {
        items.push({ level: 'error', nodeId: node.id, message: `${node.data.label || node.id} 未选择引用能力` })
      }
      const mapping = nodeInputMapping(node.data)
      for (const input of node.data.inputs || []) {
        const target = input.id || input.name || ''
        if (input.required && target && !mapping[target] && !input.source) {
          items.push({ level: 'error', nodeId: node.id, message: `${node.data.label || node.id} 必填输入未绑定：${target}` })
        }
        const mappedSource = mapping[target]
        const source = typeof mappedSource === 'string' ? mappedSource : input.source || ''
        if (source && isProbablyVariableReference(source) && !isKnownVariableReference(source, graphVariables.value, deps.nodes.value, node.id)) {
          items.push({ level: 'error', nodeId: node.id, message: `${node.data.label || node.id} 引用了不存在的变量：${source}` })
        }
      }
      if (node.data.kind === 'userInput' && !(node.data.userInputConfig?.fields || []).length) {
        items.push({ level: 'error', nodeId: node.id, message: `${node.data.label || node.id} 没有输入字段` })
      }
      if (node.data.kind === 'interaction') {
        const config = node.data.interactionConfig
        const interactionType = config?.interactionType || 'COLLECT_INPUT'
        const needsFields = ['COLLECT_INPUT', 'USER_CHOICE', 'CONFIRM_ACTION', 'REVIEW_EDIT'].includes(interactionType)
        if (needsFields && !(config?.fields || []).length) {
          items.push({ level: 'error', nodeId: node.id, message: `${node.data.label || node.id} 没有交互字段` })
        }
      }
      if (node.data.kind === 'classifier' && !(node.data.classifierConfig?.classes || []).length) {
        items.push({ level: 'error', nodeId: node.id, message: `${node.data.label || node.id} 没有分类分支` })
      }
      if (node.data.kind === 'pageAction' && !node.data.pageActionConfig?.actionKey?.trim()) {
        items.push({ level: 'error', nodeId: node.id, message: `${node.data.label || node.id} 未配置 actionKey` })
      }
      if (node.data.kind === 'knowledge' && !(node.data.knowledgeConfig?.knowledgeBaseCodes || []).length) {
        items.push({ level: 'warning', nodeId: node.id, message: `${node.data.label || node.id} 未配置知识库` })
      }
      if (node.data.kind === 'http' && !node.data.httpConfig?.url) {
        items.push({ level: 'error', nodeId: node.id, message: `${node.data.label || node.id} 未配置 URL` })
      }
      if (node.data.kind === 'variable' && Object.keys(node.data.assignments || {}).length === 0) {
        items.push({ level: 'warning', nodeId: node.id, message: `${node.data.label || node.id} 没有变量赋值` })
      }
      if (node.data.kind === 'parameter' && !(node.data.parameterConfig?.fields || []).length) {
        items.push({ level: 'warning', nodeId: node.id, message: `${node.data.label || node.id} 没有参数字段` })
      }
      if (node.data.kind === 'template' && !node.data.template) {
        items.push({ level: 'warning', nodeId: node.id, message: `${node.data.label || node.id} 没有模板内容` })
      }
      if (node.data.kind === 'answer' && !node.data.answerConfig?.template) {
        items.push({ level: 'error', nodeId: node.id, message: `${node.data.label || node.id} 没有回复模板` })
      }
      if (node.data.kind === 'code' && !Object.keys(node.data.codeConfig?.outputs || {}).length) {
        items.push({ level: 'warning', nodeId: node.id, message: `${node.data.label || node.id} 没有输出字段` })
      }
      if (node.data.kind === 'classifier' && (outgoing.get(node.id) || 0) > 1) {
        const routeEdges = deps.edges.value.filter((edge) => edge.source === node.id && isRouteCondition(edge.condition || edge.label))
        if (!routeEdges.length) {
          items.push({ level: 'warning', nodeId: node.id, message: `${node.data.label || node.id} 有多条出边，但还没有配置 route 分支条件` })
        }
      }
      if (node.data.kind === 'aggregate' && !(node.data.aggregateConfig?.items || []).length) {
        items.push({ level: 'warning', nodeId: node.id, message: `${node.data.label || node.id} 没有聚合项` })
      }
      if (node.data.kind === 'approval' && !node.data.approvalConfig?.prompt) {
        items.push({ level: 'error', nodeId: node.id, message: `${node.data.label || node.id} 没有确认内容` })
      }
      if (node.data.kind === 'loop') {
        const loop = node.data.loopConfig
        const max = loop?.maxIterations
        if (max != null && (!Number.isFinite(max) || max < 1 || max > 1000)) {
          items.push({ level: 'error', nodeId: node.id, message: `${node.data.label || node.id} maxIterations 必须在 1–1000` })
        }
        if (!loop?.collection?.trim()) {
          items.push({ level: 'error', nodeId: node.id, message: `${node.data.label || node.id} 未配置集合表达式` })
        }
        if (!(loop?.bodyNodeIds || []).length || !loop?.bodyEntry || !loop?.bodyExit) {
          items.push({ level: 'error', nodeId: node.id, message: `${node.data.label || node.id} 需配置循环体入口/出口与成员` })
        }
      }
      if (node.data.kind === 'knowledgeWrite' && !node.data.knowledgeWriteConfig?.knowledgeBaseCode) {
        items.push({ level: 'warning', nodeId: node.id, message: `${node.data.label || node.id} 未选择写入知识库` })
      }
      if (node.data.kind === 'documentExtract' && !node.data.documentExtractConfig?.sourceExpression) {
        items.push({ level: 'error', nodeId: node.id, message: `${node.data.label || node.id} 没有文档来源表达式` })
      }
      if (node.data.kind === 'mcp' && !node.data.mcpConfig?.toolName) {
        items.push({ level: 'error', nodeId: node.id, message: `${node.data.label || node.id} 未配置 MCP 工具名称` })
      }
    }
    return items
  })

  const graphLintErrors = computed(() => graphLintItems.value.filter((item) => item.level === 'error'))
  const graphLintWarnings = computed(() => graphLintItems.value.filter((item) => item.level === 'warning'))

  async function autoLayoutWorkflowCanvas() {
    await deps.waitForNodeMeasurements()
    const measurements = Object.fromEntries(
      deps.nodes.value
        .map((node) => [node.id, deps.getNodeMeasurement(node.id)] as const)
        .filter((entry): entry is readonly [string, WorkflowNodeMeasurement] => Boolean(entry[1])),
    )
    const layoutNodes = await autoLayoutWorkflowNodes(deps.nodes.value, deps.edges.value, {
      measurements,
    })
    deps.nodes.value = layoutNodes.map(deps.decorateWorkflowNode)
    deps.syncJsonFromCanvas()
    deps.markCanvasDirty()
  }

  return {
    canvasStats,
    graphVariables,
    graphLintItems,
    graphLintErrors,
    graphLintWarnings,
    autoLayoutWorkflowCanvas,
  }
}
