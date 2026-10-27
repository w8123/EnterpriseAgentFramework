import type { WorkflowCanvasSource, AgentForm, WorkflowGraphNode, WorkflowGraphSpec } from '@/types/agent'
import { studioNodeColor } from '@/utils/studioNodeRegistry'
import type {
  CanvasEdge,
  CanvasNode,
  CanvasNodeData,
  CanvasNodeKind,
  CanvasSnapshot,
  StudioFieldSchema,
} from '@/types/studio'
import { canvasKindValue, defaultUserInputConfig } from './studio/defaults'
import { arrayValue, fieldTypeValue, isSdkDefinition, objectRecordValue, stringValue } from './studio/values'
import { portValue, syncDynamicPorts, userInputOutputPorts } from './studio/ports'
import { createDefaultNodeData } from './studio/defaultNode'
import { canvasNodeToGraphNode } from './studio/toGraphNode'
import { graphConfigToNodeData } from './studio/fromGraphNode'
import { canvasLayoutFromSnapshot, overlayCanvasLayout, parseCanvasLayout } from './studio/canvasLayout'
export { knowledgeRetrievalOverrides } from './studio/defaults'
export { classifierOutputPorts, interactionOutputPorts, userInputOutputPorts } from './studio/ports'
export { createDefaultNodeData } from './studio/defaultNode'

export function canvasToDefinition(base: AgentForm, snapshot: CanvasSnapshot): AgentForm {
  const tools: string[] = []
  const knowledgeCodes: string[] = []

  for (const node of snapshot.nodes) {
    if (canvasKindValue(node.data.kind) === 'tool' && node.data.toolConfig?.ref && !tools.includes(node.data.toolConfig.ref)) {
      tools.push(node.data.toolConfig.ref)
    }
    if (node.data.kind === 'knowledge') {
      for (const code of node.data.knowledgeConfig?.knowledgeBaseCodes || []) {
        if (code && !knowledgeCodes.includes(code)) knowledgeCodes.push(code)
      }
    }
  }

  const normalized: CanvasSnapshot = normalizeCanvasSnapshot({
    version: 2,
    nodes: snapshot.nodes.map((node) => normalizeCanvasNodeData(node, base)),
    edges: snapshot.edges,
  })

  return {
    ...base,
    tools,
    knowledgeBaseGroupId: knowledgeCodes[0] || '',
    canvasJson: JSON.stringify(canvasLayoutFromSnapshot(normalized)),
    graphSpec: canvasToGraphSpec(base, normalized),
  }
}

function canvasToGraphSpec(base: AgentForm, snapshot: CanvasSnapshot): WorkflowGraphSpec {
  const graphNodes: WorkflowGraphNode[] = snapshot.nodes
    .filter((node) => node.data.kind !== 'start' && node.data.kind !== 'end')
    .map((node) => canvasNodeToGraphNode(node, base))

  const nodesById = new Map(snapshot.nodes.map((node) => [node.id, node]))
  const boundaryAwareEdges: WorkflowGraphSpec['edges'] = snapshot.edges
    .map((edge) => {
      const sourceKind = nodesById.get(edge.source)?.data?.kind
      const condition = sourceKind === 'loop'
        ? 'always'
        : (edge.condition || edge.label || 'always')
      return {
        id: edge.id,
        from: graphEndpoint(edge.source),
        to: graphEndpoint(edge.target),
        condition,
        sourceHandle: sourceKind === 'loop' ? undefined : edge.sourceHandle,
        targetHandle: edge.targetHandle,
        priority: edge.priority,
      }
    })
    .filter((edge) => edge.from !== 'END' && edge.to !== 'START')

  const firstNode = graphNodes[0]?.id || ''
  const entryNodeId = boundaryAwareEdges.find((edge) => edge.from === 'START' && edge.to !== 'END')?.to || firstNode
  const exitNodeIds = Array.from(new Set(
    boundaryAwareEdges
      .filter((edge) => edge.to === 'END' && edge.from !== 'START')
      .map((edge) => edge.from),
  ))
  const graphEdges = boundaryAwareEdges.filter((edge) => (
    edge.from !== 'START'
    && edge.from !== 'END'
    && edge.to !== 'START'
    && edge.to !== 'END'
  ))
  const userInputSchema = userInputSchemaFromCanvas(snapshot.nodes)

  return {
    schemaVersion: 2,
    // USER_INPUT is the authoring source of truth. Persisting a fresh schema
    // here prevents Studio from accepting a different contract than Runtime
    // release validation or the public Workflow AI Coding Skill.
    inputSchema: userInputSchema || base.graphSpec?.inputSchema,
    stateSchema: base.graphSpec?.stateSchema,
    nodes: graphNodes,
    edges: graphEdges,
    entryNodeId,
    exitNodeIds: exitNodeIds.length
      ? exitNodeIds
      : firstNode ? [graphNodes[graphNodes.length - 1]?.id || firstNode] : [],
  }
}

function userInputSchemaFromCanvas(nodes: CanvasNode[]): WorkflowGraphSpec['inputSchema'] | undefined {
  const inputs = nodes.filter((node) => node.data.kind === 'userInput')
  if (inputs.length !== 1) return undefined
  const config = inputs[0].data.userInputConfig || defaultUserInputConfig()
  const fields = (config.fields || []).filter((field) => !!field.name?.trim())
  if (!fields.length) return undefined
  const properties: Record<string, Record<string, unknown>> = {}
  const required: string[] = []
  for (const field of fields) {
    const name = field.name!.trim()
    const property: Record<string, unknown> = {
      type: jsonSchemaFieldType(field.type),
    }
    if (field.description?.trim()) property.description = field.description.trim()
    if (field.defaultValue != null && String(field.defaultValue).trim()) {
      property.default = field.defaultValue
    }
    properties[name] = property
    if (field.required === true) required.push(name)
  }
  return {
    type: 'object',
    properties,
    ...(required.length ? { required } : {}),
    additionalProperties: false,
  }
}

function jsonSchemaFieldType(type: StudioFieldSchema['type'] | undefined): string {
  return type === 'file' ? 'string' : (type || 'string')
}

export function definitionToCanvas(def: WorkflowCanvasSource): CanvasSnapshot {
  if (!def.graphSpec?.nodes?.length) {
    return emptyCanvas()
  }
  const semantic = normalizeCanvasSnapshot(graphSpecToCanvas(def.graphSpec, def))
  return def.canvasJson
    ? overlayCanvasLayout(semantic, parseCanvasLayout(def.canvasJson))
    : semantic
}

function graphSpecToCanvas(graphSpec: WorkflowGraphSpec, def: WorkflowCanvasSource): CanvasSnapshot {
  const nodes: CanvasNode[] = [
    { id: 'start', type: 'start', position: { x: 60, y: 220 }, data: { label: '开始', kind: 'start', configVersion: 2 } },
  ]
  ;(graphSpec.nodes || []).forEach((node, idx) => {
    const kind = graphNodeKindToCanvas(node.type)
    const config = node.config || {}
    const position = { x: 260 + idx * 240, y: 220 }
    const data: CanvasNodeData = graphConfigToNodeData(kind, node.name || node.id, config, def, node.ref)
    if (kind === 'userInput' && !data.userInputConfig?.fields.length) {
      const fields = jsonSchemaInputFields(graphSpec.inputSchema)
      if (fields.length) {
        const outputAlias = data.userInputConfig?.outputAlias || data.outputAlias || 'params'
        data.userInputConfig = { fields, outputAlias }
        data.outputAlias = outputAlias
        data.outputs = userInputOutputPorts(fields, outputAlias)
      }
    }
    data.description = node.description || data.description
    data.inputs = portValue(node.inputs) || data.inputs
    data.outputs = portValue(node.outputs) || data.outputs
    data.inputSchema = node.inputSchema || data.inputSchema
    data.outputSchema = node.outputSchema || data.outputSchema
    if (node.retry) data.retry = node.retry as NonNullable<CanvasNode['data']['retry']>
    if (node.errorPolicy) data.errorPolicy = node.errorPolicy as NonNullable<CanvasNode['data']['errorPolicy']>
    data.source = isSdkDefinition(def) ? 'SDK' : 'CANVAS'
    nodes.push({ id: node.id, type: kind, position, data })
  })
  nodes.push({
    id: 'end',
    type: 'end',
    position: { x: 260 + Math.max(graphSpec.nodes?.length || 1, 1) * 240, y: 220 },
    data: { label: '结束', kind: 'end', configVersion: 2 },
  })
  return {
    version: 2,
    nodes,
    edges: graphEdgesWithSemanticBoundaries(graphSpec).map((edge, idx) => {
      const condition = edge.condition || 'always'
      return decorateSerializableEdge({
        id: edge.id || `graph-e-${idx}`,
        source: canvasEndpoint(edge.from),
        target: canvasEndpoint(edge.to),
        condition,
        label: condition,
        sourceHandle: edge.sourceHandle,
        targetHandle: edge.targetHandle,
        priority: edge.priority,
      })
    }),
  }
}

function jsonSchemaInputFields(schema: WorkflowGraphSpec['inputSchema']): StudioFieldSchema[] {
  if (!schema || typeof schema !== 'object' || Array.isArray(schema)) return []
  const properties = objectRecordValue(schema.properties)
  const required = new Set(arrayValue(schema.required))
  return Object.entries(properties).map(([name, raw]) => {
    const property = objectRecordValue(raw)
    return {
      name,
      key: name,
      type: fieldTypeValue(property.type),
      required: required.has(name),
      description: stringValue(property.description || property.title),
      defaultValue: property.default == null ? '' : String(property.default),
      source: `input.${name}`,
    }
  })
}

function normalizeCanvasNodeData(node: CanvasNode, base: AgentForm): CanvasNode {
  const kind = canvasKindValue(node.data?.kind ?? node.type)
  const label = node.data?.label ?? kind
  const defaults = createDefaultNodeData(kind, label, base)
  const data = node.data ?? {}
  const mergedData = {
    ...defaults,
    ...data,
    configVersion: 2 as const,
    kind,
    label: data.label ?? defaults.label,
    inputs: data.inputs?.length ? data.inputs : defaults.inputs,
    outputs: data.outputs?.length ? data.outputs : defaults.outputs,
    retry: data.retry || defaults.retry,
    errorPolicy: data.errorPolicy || defaults.errorPolicy,
    source: data.source || defaults.source,
    category: data.category || defaults.category,
  }
  return {
    ...node,
    data: syncDynamicPorts(mergedData),
  }
}

function graphEndpoint(endpoint: string) {
  if (endpoint === 'start') return 'START'
  if (endpoint === 'end') return 'END'
  return endpoint
}

function canvasEndpoint(endpoint: string) {
  if (endpoint === 'START') return 'start'
  if (endpoint === 'END') return 'end'
  return endpoint
}

function graphNodeKindToCanvas(type: string): CanvasNodeKind {
  if (type === 'USER_INPUT') return 'userInput'
  if (type === 'INTERACTION') return 'interaction'
  if (type === 'PAGE_ACTION') return 'pageAction'
  if (type === 'LLM') return 'llm'
  if (type === 'TOOL') return 'tool'
  if (type === 'IF_ELSE') return 'condition'
  if (type === 'VARIABLE_ASSIGN') return 'variable'
  if (type === 'TEMPLATE') return 'template'
  if (type === 'ANSWER') return 'answer'
  if (type === 'CODE') return 'code'
  if (type === 'INTENT_CLASSIFIER') return 'classifier'
  if (type === 'VARIABLE_AGGREGATOR') return 'aggregate'
  if (type === 'HUMAN_APPROVAL') return 'approval'
  if (type === 'LOOP') return 'loop'
  if (type === 'KNOWLEDGE_WRITE') return 'knowledgeWrite'
  if (type === 'DOCUMENT_EXTRACT') return 'documentExtract'
  if (type === 'MCP_CALL') return 'mcp'
  if (type === 'PARAMETER_EXTRACT') return 'parameter'
  if (type === 'HTTP_REQUEST') return 'http'
  if (type === 'KNOWLEDGE_RETRIEVAL') return 'knowledge'
  throw new Error(`GraphSpec node.type must use a canonical value: ${type}`)
}

function emptyCanvas(): CanvasSnapshot {
  return {
    version: 2,
    nodes: [
      { id: 'start', type: 'start', position: { x: 60, y: 220 }, data: { label: '开始', kind: 'start', configVersion: 2 } },
      { id: 'end', type: 'end', position: { x: 500, y: 220 }, data: { label: '结束', kind: 'end', configVersion: 2 } },
    ],
    edges: [decorateSerializableEdge({ id: 'e-start-end', source: 'start', target: 'end', condition: 'always', label: 'always' })],
  }
}

const CANVAS_BRANCH_SOURCE_NODE_KINDS = new Set<CanvasNodeKind>(['classifier', 'condition', 'approval', 'knowledge'])

export function normalizeCanvasEdgeHandles(
  edge: CanvasEdge,
  nodesById: Map<string, CanvasNode>,
): CanvasEdge {
  const source = nodesById.get(edge.source)
  const sourceKind = source?.data?.kind
  let sourceHandle = edge.sourceHandle || resolveCanvasSourceHandle(edge, source)
  let targetHandle = edge.targetHandle

  if (!sourceKind || !CANVAS_BRANCH_SOURCE_NODE_KINDS.has(sourceKind)) {
    sourceHandle = undefined
  } else if (sourceHandle && source && !isValidBranchSourceHandle(source, sourceHandle)) {
    sourceHandle = undefined
  }

  targetHandle = undefined

  return {
    ...edge,
    sourceHandle,
    targetHandle,
  }
}

export function normalizeCanvasSnapshot(snapshot: CanvasSnapshot): CanvasSnapshot {
  const nodes = snapshot.nodes || []
  const nodesById = new Map(nodes.map((node) => [node.id, node]))
  return {
    version: 2,
    nodes,
    edges: (snapshot.edges || []).map((edge) =>
      decorateSerializableEdge(normalizeCanvasEdgeHandles(edge, nodesById)),
    ),
  }
}

function isValidBranchSourceHandle(node: CanvasNode, handle: string): boolean {
  if (node.data.kind === 'classifier') {
    return isValidClassifierSourceHandle(node, handle)
  }
  if (node.data.kind === 'condition') {
    return conditionSourceHandleIds(node).has(handle)
  }
  if (node.data.kind === 'approval') {
    return ['approved', 'rejected', 'timeout'].includes(handle)
  }
  if (node.data.kind === 'knowledge') {
    return node.data.knowledgeConfig?.evidencePolicy === 'REQUIRED'
      && ['evidence', 'no_evidence'].includes(handle)
  }
  if (node.data.kind === 'loop') {
    // FOREACH v1 is linear: one success outgoing edge (always), no continue/done routes.
    return false
  }
  return false
}

function conditionSourceHandleIds(node: CanvasNode): Set<string> {
  const ids = new Set<string>()
  for (const group of node.data.conditionConfig?.groups || []) {
    const id = group.id?.trim()
    if (id) ids.add(id)
  }
  const defaultRoute = (node.data.conditionConfig?.defaultRoute || 'else').trim()
  if (defaultRoute) ids.add(defaultRoute)
  if (!ids.size) {
    for (const port of node.data.outputs || []) {
      const extended = port as { id?: string; name?: string; key?: string }
      const id = String(extended.id || extended.name || extended.key || '').trim()
      if (id) ids.add(id)
    }
  }
  if (!ids.size) ids.add('else')
  return ids
}

function resolveCanvasSourceHandle(edge: CanvasEdge, source?: CanvasNode): string | undefined {
  if (!source) return undefined
  const kind = source.data.kind
  if (!CANVAS_BRANCH_SOURCE_NODE_KINDS.has(kind)) return undefined
  const condition = (edge.condition || edge.label || '').trim()
  if (!condition || condition === 'always') return undefined
  if (condition === 'else' || condition === 'default') return 'else'
  if (condition.startsWith('route:')) {
    const route = condition.slice('route:'.length).trim()
    return route || undefined
  }
  if (isValidBranchSourceHandle(source, condition)) return condition
  return undefined
}

function isValidClassifierSourceHandle(node: CanvasNode, handle: string): boolean {
  const config = node.data.classifierConfig
  if (!config) return false
  const ids = new Set(
    (config.classes || [])
      .map((item) => item.id?.trim())
      .filter((item): item is string => !!item),
  )
  const defaultRoute = (config.defaultRoute || 'else').trim()
  if (defaultRoute) ids.add(defaultRoute)
  return ids.has(handle)
}

function decorateSerializableEdge(edge: CanvasEdge): CanvasEdge {
  const condition = edge.condition || edge.label || 'always'
  return {
    ...edge,
    condition,
    label: condition,
    type: edge.type || 'smoothstep',
    markerEnd: edge.markerEnd || 'arrowclosed',
    interactionWidth: edge.interactionWidth || 18,
    animated: !['always', 'default'].includes(condition),
  }
}

function graphEdgesWithSemanticBoundaries(graphSpec: WorkflowGraphSpec): WorkflowGraphSpec['edges'] {
  let edges = [...(graphSpec.edges || [])]
  const nodeIds = new Set((graphSpec.nodes || []).map((node) => node.id))
  const entry = graphSpec.entryNodeId?.trim()
  if (entry && nodeIds.has(entry)) {
    const matching = edges.find((edge) => isGraphStart(edge.from) && edge.to === entry)
    edges = edges.filter((edge) => !isGraphStart(edge.from))
    edges.unshift(matching || {
      id: `graph-entry-${entry}`,
      from: 'START',
      to: entry,
      condition: 'always',
    })
  }

  const finish = Array.from(new Set(
    (graphSpec.exitNodeIds || [])
      .map((nodeId) => nodeId?.trim())
      .filter((nodeId): nodeId is string => !!nodeId && nodeIds.has(nodeId)),
  ))
  if (finish.length) {
    const existing = edges.filter((edge) => isGraphEnd(edge.to))
    edges = edges.filter((edge) => !isGraphEnd(edge.to))
    for (const nodeId of finish) {
      edges.push(existing.find((edge) => edge.from === nodeId) || {
        id: `graph-finish-${nodeId}`,
        from: nodeId,
        to: 'END',
        condition: 'always',
      })
    }
  }
  return edges
}

function isGraphStart(endpoint: string) {
  return endpoint.toUpperCase() === 'START'
}

function isGraphEnd(endpoint: string) {
  return endpoint.toUpperCase() === 'END'
}

export function kindColor(kind: CanvasNodeKind) {
  return studioNodeColor(kind)
}
