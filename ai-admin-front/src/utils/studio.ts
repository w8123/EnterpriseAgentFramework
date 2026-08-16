import type { WorkflowCanvasSource, AgentForm, WorkflowGraphNode, WorkflowGraphSpec } from '@/types/agent'
import { studioNodeCategory, studioNodeColor, studioNodeRetryable } from '@/utils/studioNodeRegistry'
import type {
  CanvasEdge,
  CanvasNode,
  CanvasNodeData,
  CanvasNodeKind,
  CanvasSnapshot,
  ConditionNodeConfig,
  DocumentExtractNodeConfig,
  HumanApprovalNodeConfig,
  HttpNodeConfig,
  InteractionNodeConfig,
  IntentClassifierNodeConfig,
  KnowledgeWriteNodeConfig,
  KnowledgeNodeConfig,
  LlmNodeConfig,
  LlmPromptMessage,
  LoopNodeConfig,
  McpNodeConfig,
  PageActionNodeConfig,
  ParameterNodeConfig,
  StudioErrorPolicy,
  StudioPort,
  StudioRetryPolicy,
  StudioFieldSchema,
  ToolNodeConfig,
  UserInputNodeConfig,
  VariableAggregateNodeConfig,
} from '@/types/studio'

interface CanvasLayoutDocument {
  schemaVersion: 1
  layoutVersion: 1
  nodes: Array<{
    id: string
    position: { x: number; y: number }
    collapsed?: boolean
  }>
  edges: Array<{
    id: string
    label?: string
    style?: string
  }>
}

export function canvasToDefinition(base: AgentForm, snapshot: CanvasSnapshot): AgentForm {
  const tools: string[] = []
  const skills: string[] = []
  const knowledgeCodes: string[] = []

  for (const node of snapshot.nodes) {
    if (node.data.kind === 'tool' && node.data.toolConfig?.ref && !tools.includes(node.data.toolConfig.ref)) {
      tools.push(node.data.toolConfig.ref)
    }
    if (node.data.kind === 'skill' && node.data.toolConfig?.ref && !skills.includes(node.data.toolConfig.ref)) {
      skills.push(node.data.toolConfig.ref)
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
    skills,
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

function canvasNodeToGraphNode(node: CanvasNode, base: AgentForm): WorkflowGraphNode {
  const common = commonNodeConfig(node)
  if (node.data.kind === 'userInput') {
    const userInput = node.data.userInputConfig || defaultUserInputConfig()
    const outputAlias = userInput.outputAlias || node.data.outputAlias || 'params'
    return {
      id: node.id,
      type: 'USER_INPUT',
      name: node.data.label,
      ...graphNodeChrome(node),
      outputs: userInputOutputPorts(userInput.fields || [], outputAlias),
      config: {
        ...common,
        fields: userInput.fields || [],
        outputAlias,
        userInputConfig: { fields: userInput.fields || [], outputAlias },
      },
    }
  }
  if (node.data.kind === 'interaction') {
    const interaction = node.data.interactionConfig || defaultInteractionConfig()
    const outputAlias = interaction.outputAlias || node.data.outputAlias || 'interaction_output'
    const fields = interactionFieldsForGraph(interaction.fields || [])
    return {
      id: node.id,
      type: 'INTERACTION',
      name: node.data.label,
      ...graphNodeChrome(node),
      outputs: interactionOutputPorts(interaction, outputAlias),
      ref: interaction.qualifiedName ? {
        kind: 'INTERACTION',
        name: interaction.qualifiedName,
        qualifiedName: interaction.qualifiedName,
      } : undefined,
      config: {
        ...common,
        interactionType: interaction.interactionType || 'COLLECT_INPUT',
        qualifiedName: interaction.qualifiedName,
        binding: interaction.binding || { sourceKind: 'NONE' },
        title: interaction.title || node.data.label,
        component: interaction.component || 'FORM',
        fields,
        dataExpression: interaction.dataExpression,
        outputAlias,
        dataSources: interaction.dataSources || {},
        behavior: interaction.behavior || {},
        presentation: interaction.presentation || {},
        renderSchema: interaction.renderSchema || {},
        interactionConfig: {
          ...interaction,
          fields,
          outputAlias,
        },
      },
    }
  }
  if (node.data.kind === 'pageAction') {
    const pageAction = node.data.pageActionConfig || defaultPageActionConfig()
    const outputAlias = pageAction.outputAlias || node.data.outputAlias || 'page_action_result'
    return {
      id: node.id,
      type: 'PAGE_ACTION',
      name: node.data.label,
      ...graphNodeChrome(node),
      outputs: defaultPorts('pageAction', 'output', outputAlias),
      config: {
        ...common,
        actionKey: pageAction.actionKey,
        projectCode: pageAction.projectCode,
        pageKey: pageAction.pageKey,
        routePattern: pageAction.routePattern,
        title: pageAction.title || node.data.label,
        confirm: pageAction.confirm === true,
        args: pageAction.args || {},
        outputAlias,
        metadata: pageAction.metadata || {},
        pageActionConfig: {
          ...pageAction,
          outputAlias,
        },
      },
    }
  }
  if (node.data.kind === 'llm') {
    const llm = node.data.llmConfig || defaultLlmConfig(base)
    return {
      id: node.id,
      type: 'LLM',
      name: node.data.label,
      ...graphNodeChrome(node),
      config: {
        ...common,
        modelInstanceId: llm.modelInstanceId || base.modelInstanceId,
        systemPrompt: llm.systemPrompt || base.systemPrompt || '',
        userPrompt: llm.userPrompt || '{{ input }}',
        messages: llm.messages?.length ? llm.messages : defaultLlmMessages(llm.systemPrompt || base.systemPrompt || '', llm.userPrompt || '{{ input }}'),
        contextVariables: llm.contextVariables || [],
        modelParams: normalizeParams(llm.modelParams),
        outputFormat: llm.outputFormat || 'text',
        structuredOutput: llm.structuredOutput === true || llm.outputFormat === 'json',
        strictJsonSchema: llm.strictJsonSchema !== false,
        outputSchema: llm.outputSchema || [],
        visionEnabled: llm.visionEnabled === true,
        visionInputs: llm.visionInputs || [],
        promptTemplateMode: llm.promptTemplateMode || 'messages',
        llmConfig: llm,
      },
    }
  }
  if (node.data.kind === 'tool' || node.data.kind === 'skill') {
    const tool = node.data.toolConfig || defaultToolConfig()
    const kind = node.data.kind === 'tool' ? 'TOOL' : 'CAPABILITY'
    return {
      id: node.id,
      type: kind,
      name: node.data.label,
      ...graphNodeChrome(node),
      ref: {
        kind,
        name: tool.ref,
        qualifiedName: tool.qualifiedName || tool.ref,
        projectCode: tool.projectCode,
      },
      config: {
        ...common,
        inputMapping: tool.inputMapping || {},
        mappingNote: tool.mappingNote,
        maxRequestTimeMs: tool.maxRequestTimeMs || 180000,
        credentialRef: tool.credentialRef,
        toolConfig: tool,
      },
    }
  }
  if (node.data.kind === 'condition') {
    const condition = node.data.conditionConfig || defaultConditionConfig()
    return {
      id: node.id,
      type: 'IF_ELSE',
      name: node.data.label,
      ...graphNodeChrome(node),
      config: {
        ...common,
        conditionGroups: condition.groups || [],
        defaultRoute: condition.defaultRoute || 'else',
        conditionConfig: condition,
      },
    }
  }
  if (node.data.kind === 'variable') {
    return {
      id: node.id,
      type: 'VARIABLE_ASSIGN',
      name: node.data.label,
      ...graphNodeChrome(node),
      config: {
        ...common,
        assignments: node.data.assignments || {},
      },
    }
  }
  if (node.data.kind === 'template') {
    return {
      id: node.id,
      type: 'TEMPLATE',
      name: node.data.label,
      ...graphNodeChrome(node),
      config: {
        ...common,
        template: node.data.template || '',
        // TEMPLATE is an intermediate transform node; ANSWER owns final user response.
        writeToAnswer: false,
      },
    }
  }
  if (node.data.kind === 'answer') {
    return {
      id: node.id,
      type: 'ANSWER',
      name: node.data.label,
      ...graphNodeChrome(node),
      config: {
        ...common,
        template: node.data.answerConfig?.template || node.data.template || '{{ lastOutput }}',
        writeToAnswer: true,
        answerConfig: node.data.answerConfig,
      },
    }
  }
  if (node.data.kind === 'code') {
    const code = node.data.codeConfig || defaultCodeConfig()
    return {
      id: node.id,
      type: 'CODE',
      name: node.data.label,
      ...graphNodeChrome(node),
      config: {
        ...common,
        language: code.language || 'expression',
        code: code.code || '',
        outputs: code.outputs || {},
        codeConfig: code,
      },
    }
  }
  if (node.data.kind === 'classifier') {
    const classifier = node.data.classifierConfig || defaultClassifierConfig()
    return {
      id: node.id,
      type: 'INTENT_CLASSIFIER',
      name: node.data.label,
      ...graphNodeChrome(node),
      config: {
        ...common,
        inputExpression: classifier.inputExpression || 'input',
        strategy: classifier.strategy || 'KEYWORD',
        classes: classifier.classes || [],
        defaultRoute: classifier.defaultRoute || 'else',
        modelInstanceId: classifier.modelInstanceId || '',
        confidenceThreshold: classifier.confidenceThreshold ?? 0.7,
        llmPrompt: classifier.llmPrompt || '',
        modelParams: normalizeParams(classifier.modelParams),
        classifierConfig: classifier,
      },
    }
  }
  if (node.data.kind === 'aggregate') {
    const aggregate = node.data.aggregateConfig || defaultAggregateConfig()
    return {
      id: node.id,
      type: 'VARIABLE_AGGREGATOR',
      name: node.data.label,
      ...graphNodeChrome(node),
      config: {
        ...common,
        aggregateMode: aggregate.mode || 'object',
        items: aggregate.items || [],
        template: aggregate.template || '',
        aggregateConfig: aggregate,
      },
    }
  }
  if (node.data.kind === 'approval') {
    const approval = node.data.approvalConfig || defaultApprovalConfig()
    return {
      id: node.id,
      type: 'HUMAN_APPROVAL',
      name: node.data.label,
      ...graphNodeChrome(node),
      config: {
        ...common,
        title: approval.title,
        prompt: approval.prompt,
        approvers: approval.approvers,
        timeoutSeconds: approval.timeoutSeconds,
        defaultRoute: approval.defaultRoute || 'approved',
        approvalConfig: approval,
      },
    }
  }
  if (node.data.kind === 'loop') {
    const loop = normalizeLoopConfig(node.data.loopConfig || defaultLoopConfig())
    return {
      id: node.id,
      type: 'LOOP',
      name: node.data.label,
      ...graphNodeChrome(node),
      config: {
        ...common,
        mode: 'FOREACH',
        collection: loop.collection,
        itemAlias: loop.itemAlias,
        indexAlias: loop.indexAlias,
        outputAlias: loop.outputAlias,
        bodyOutput: loop.bodyOutput,
        maxIterations: loop.maxIterations,
        bodyEntry: loop.bodyEntry,
        bodyExit: loop.bodyExit,
        bodyNodeIds: loop.bodyNodeIds,
        loopConfig: loop,
      },
    }
  }
  if (node.data.kind === 'knowledgeWrite') {
    const knowledgeWrite = node.data.knowledgeWriteConfig || defaultKnowledgeWriteConfig()
    return {
      id: node.id,
      type: 'KNOWLEDGE_WRITE',
      name: node.data.label,
      ...graphNodeChrome(node),
      config: {
        ...common,
        knowledgeBaseCode: knowledgeWrite.knowledgeBaseCode,
        titleExpression: knowledgeWrite.titleExpression,
        contentExpression: knowledgeWrite.contentExpression,
        tags: knowledgeWrite.tags,
        writeMode: knowledgeWrite.mode,
        knowledgeWriteConfig: knowledgeWrite,
      },
    }
  }
  if (node.data.kind === 'documentExtract') {
    const documentExtract = node.data.documentExtractConfig || defaultDocumentExtractConfig()
    return {
      id: node.id,
      type: 'DOCUMENT_EXTRACT',
      name: node.data.label,
      ...graphNodeChrome(node),
      config: {
        ...common,
        sourceExpression: documentExtract.sourceExpression,
        format: documentExtract.format,
        fields: documentExtract.fields,
        documentExtractConfig: documentExtract,
      },
    }
  }
  if (node.data.kind === 'mcp') {
    const mcp = node.data.mcpConfig || defaultMcpConfig()
    return {
      id: node.id,
      type: 'MCP_CALL',
      name: node.data.label,
      ...graphNodeChrome(node),
      config: {
        ...common,
        serverRef: mcp.serverRef,
        toolName: mcp.toolName,
        inputMapping: mcp.inputMapping,
        mcpConfig: mcp,
      },
    }
  }
  if (node.data.kind === 'parameter') {
    const parameter = node.data.parameterConfig || defaultParameterConfig()
    return {
      id: node.id,
      type: 'PARAMETER_EXTRACT',
      name: node.data.label,
      ...graphNodeChrome(node),
      config: {
        ...common,
        extractMode: parameter.mode || 'expression',
        modelInstanceId: parameter.modelInstanceId,
        inputExpression: parameter.inputExpression,
        systemPrompt: parameter.systemPrompt,
        userPrompt: parameter.userPrompt,
        modelParams: normalizeParams(parameter.modelParams),
        fields: parameter.fields || [],
        parameterConfig: parameter,
      },
    }
  }
  if (node.data.kind === 'http') {
    const http = node.data.httpConfig || defaultHttpConfig()
    return {
      id: node.id,
      type: 'HTTP_REQUEST',
      name: node.data.label,
      ...graphNodeChrome(node),
      config: {
        ...common,
        method: http.method || 'GET',
        url: http.url || '',
        queryParams: http.queryParams || {},
        headers: http.headers || {},
        bodyType: http.bodyType || 'none',
        body: http.body || '',
        timeoutMs: http.timeoutMs || 30000,
        credentialRef: http.credentialRef,
        httpConfig: http,
      },
    }
  }
  const knowledge = node.data.knowledgeConfig || defaultKnowledgeConfig()
  return {
    id: node.id,
    type: 'KNOWLEDGE_RETRIEVAL',
    name: node.data.label,
    ...graphNodeChrome(node),
    config: {
      ...common,
      knowledgeBaseCodes: knowledge.knowledgeBaseCodes || [],
      knowledgeBaseGroupId: (knowledge.knowledgeBaseCodes || [])[0] || '',
      query: knowledge.query || 'input',
      topK: knowledge.topK || 5,
      similarityThreshold: knowledge.similarityThreshold,
      searchMode: knowledge.searchMode || 'hybrid',
      rerankEnabled: knowledge.rerankEnabled ?? true,
      knowledgeConfig: knowledge,
    },
  }
}

function commonNodeConfig(node: CanvasNode): Record<string, unknown> {
  return {
    configVersion: 2,
    inputMapping: node.data.inputMapping || {},
    outputAlias: node.data.outputAlias,
    needsConfiguration: node.data.needsConfiguration === true,
    placeholderReason: node.data.placeholderReason,
    description: node.data.description,
  }
}

function graphNodeChrome(node: CanvasNode) {
  const dynamicOutputs = dynamicOutputPorts(node.data)
  return {
    description: node.data.description,
    inputs: node.data.inputs || defaultPorts(node.data.kind, 'input'),
    outputs: dynamicOutputs || node.data.outputs || defaultPorts(node.data.kind, 'output', node.data.outputAlias),
    inputSchema: node.data.inputSchema,
    outputSchema: node.data.outputSchema,
    retry: node.data.retry,
    errorPolicy: node.data.errorPolicy,
  }
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

function overlayCanvasLayout(semantic: CanvasSnapshot, layout: CanvasLayoutDocument): CanvasSnapshot {
  const layoutNodes = new Map(layout.nodes.map((node) => [node.id, node]))
  const layoutEdges = new Map(layout.edges.map((edge) => [edge.id, edge]))
  return {
    version: 2,
    nodes: semantic.nodes.map((node) => {
      const layoutNode = layoutNodes.get(node.id)
      if (!layoutNode) return node
      return {
        ...node,
        position: layoutNode.position || node.position,
        data: {
          ...node.data,
          collapsed: typeof layoutNode.collapsed === 'boolean'
            ? layoutNode.collapsed
            : node.data.collapsed,
        },
      }
    }),
    edges: semantic.edges.map((edge) => {
      const layoutEdge = layoutEdges.get(edge.id)
      if (!layoutEdge) return edge
      return {
        ...edge,
        label: layoutEdge.label ?? edge.label,
        type: layoutEdge.style ?? edge.type,
      }
    }),
  }
}

function canvasLayoutFromSnapshot(snapshot: CanvasSnapshot): CanvasLayoutDocument {
  return {
    schemaVersion: 1,
    layoutVersion: 1,
    nodes: snapshot.nodes.map((node) => ({
      id: node.id,
      position: { x: node.position.x, y: node.position.y },
      collapsed: node.data.collapsed === true ? true : undefined,
    })),
    edges: snapshot.edges.map((edge) => ({
      id: edge.id,
      label: typeof edge.label === 'string' ? edge.label : undefined,
      style: edge.type,
    })),
  }
}

function parseCanvasLayout(canvasJson: string): CanvasLayoutDocument {
  const parsed = JSON.parse(canvasJson) as {
    schemaVersion?: unknown
    layoutVersion?: unknown
    nodes?: Array<Record<string, unknown>>
    edges?: Array<Record<string, unknown>>
  }
  if (parsed.schemaVersion !== 1 || parsed.layoutVersion !== 1) {
    throw new Error('Canvas layout schemaVersion/layoutVersion must both be 1')
  }
  assertLayoutFields(parsed as Record<string, unknown>, new Set([
    'schemaVersion', 'layoutVersion', 'viewport', 'layout', 'nodes', 'edges',
  ]), 'Canvas layout')
  return {
    schemaVersion: 1,
    layoutVersion: 1,
    nodes: (Array.isArray(parsed.nodes) ? parsed.nodes : []).flatMap((raw) => {
      assertLayoutFields(raw, new Set(['id', 'position', 'width', 'height', 'collapsed']), 'Canvas layout node')
      const id = typeof raw.id === 'string' ? raw.id : ''
      const position = raw.position as { x?: unknown; y?: unknown } | undefined
      const x = Number(position?.x)
      const y = Number(position?.y)
      if (!id || !Number.isFinite(x) || !Number.isFinite(y)) return []
      const collapsed = typeof raw.collapsed === 'boolean'
        ? raw.collapsed
        : undefined
      return [{ id, position: { x, y }, collapsed }]
    }),
    edges: (Array.isArray(parsed.edges) ? parsed.edges : []).flatMap((raw) => {
      assertLayoutFields(raw, new Set(['id', 'label', 'style']), 'Canvas layout edge')
      const id = typeof raw.id === 'string' ? raw.id : ''
      if (!id) return []
      return [{
        id,
        label: typeof raw.label === 'string' ? raw.label : undefined,
        style: typeof raw.style === 'string' ? raw.style : undefined,
      }]
    }),
  }
}

function assertLayoutFields(value: Record<string, unknown>, allowed: Set<string>, label: string): void {
  for (const field of Object.keys(value)) {
    if (!allowed.has(field)) throw new Error(`${label} contains unsupported field: ${field}`)
  }
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

function graphConfigToNodeData(
  kind: CanvasNodeKind,
  label: string,
  config: Record<string, unknown>,
  def: WorkflowCanvasSource,
  ref?: WorkflowGraphNode['ref'],
) {
  const common = {
    label,
    kind,
    configVersion: 2 as const,
    description: stringValue(config.description),
    outputAlias: stringValue(config.outputAlias),
    needsConfiguration: config.needsConfiguration === true,
    placeholderReason: stringValue(config.placeholderReason),
    source: isSdkDefinition(def) ? 'SDK' as const : 'CANVAS' as const,
    category: nodeCategory(kind),
    collapsed: config.ui && typeof config.ui === 'object'
      ? (config.ui as Record<string, unknown>).collapsed === true
      : false,
    inputs: defaultPorts(kind, 'input'),
    outputs: defaultPorts(kind, 'output', stringValue(config.outputAlias)),
    inputSchema: objectRecordValue(config.inputSchema),
    outputSchema: objectRecordValue(config.outputSchema),
    inputMapping: stringRecord(config.inputMapping),
    retry: defaultRetryPolicy(kind),
    errorPolicy: defaultErrorPolicy(),
  }
  if (kind === 'userInput') {
    const fields = schemaValue(config.fields)
    const outputAlias = stringValue(config.outputAlias) || 'params'
    return {
      ...common,
      outputAlias,
      outputs: userInputOutputPorts(fields, outputAlias),
      userInputConfig: {
        fields,
        outputAlias,
      } satisfies UserInputNodeConfig,
    }
  }
  if (kind === 'interaction') {
    const fields = schemaValue(config.fields)
    const outputAlias = stringValue(config.outputAlias) || 'interaction_output'
    const interactionConfig = {
      interactionType: interactionTypeValue(config.interactionType),
      qualifiedName: stringValue(config.qualifiedName),
      binding: interactionBindingValue(config.binding),
      title: stringValue(config.title) || label,
      component: interactionComponentValue(config.component),
      fields,
      dataExpression: stringValue(config.dataExpression),
      outputAlias,
      dataSources: objectRecordValue(config.dataSources),
      behavior: objectRecordValue(config.behavior),
      presentation: objectRecordValue(config.presentation),
      renderSchema: objectRecordValue(config.renderSchema),
    } satisfies InteractionNodeConfig
    return {
      ...common,
      outputAlias,
      outputs: interactionOutputPorts(interactionConfig, outputAlias),
      interactionConfig,
    }
  }
  if (kind === 'pageAction') {
    const outputAlias = stringValue(config.outputAlias) || 'page_action_result'
    const pageActionConfig = {
      projectCode: stringValue(config.projectCode),
      pageKey: stringValue(config.pageKey),
      routePattern: stringValue(config.routePattern),
      actionKey: stringValue(config.actionKey),
      title: stringValue(config.title) || label,
      confirm: config.confirm === true,
      args: stringRecord(config.args),
      outputAlias,
      metadata: objectRecordValue(config.metadata),
    } satisfies PageActionNodeConfig
    return {
      ...common,
      outputAlias,
      outputs: defaultPorts('pageAction', 'output', outputAlias),
      pageActionConfig,
    }
  }
  if (kind === 'llm') {
    const systemPrompt = firstNonEmptyText(config.systemPrompt, config.prompt, def.systemPrompt)
    const userPrompt = firstNonEmptyText(config.userPrompt, '{{ input }}')
    return {
      ...common,
      llmConfig: {
        modelInstanceId: stringValue(config.modelInstanceId) || def.modelInstanceId,
        systemPrompt,
        userPrompt,
        messages: llmMessagesValue(config.messages, systemPrompt, userPrompt),
        contextVariables: arrayValue(config.contextVariables),
        modelParams: firstRecordValue(config.modelParams, config.options),
        outputFormat: stringValue(config.outputFormat) === 'json' ? 'json' : 'text',
        structuredOutput: config.structuredOutput === true || stringValue(config.outputFormat) === 'json',
        strictJsonSchema: config.strictJsonSchema !== false,
        outputSchema: schemaValue(config.outputSchema),
        visionEnabled: config.visionEnabled === true,
        visionInputs: arrayValue(config.visionInputs),
        promptTemplateMode: stringValue(config.promptTemplateMode) === 'simple' ? 'simple' : 'messages',
      } satisfies LlmNodeConfig,
    }
  }
  if (kind === 'tool' || kind === 'skill') {
    const nested = objectRecordValue(config.toolConfig || config.capabilityConfig)
    const configuredRef = firstNonEmptyText(
      configuredReferenceValue(config.ref),
      config.toolName,
      config.qualifiedName,
      configuredReferenceValue(nested.ref),
      nested.toolName,
      nested.qualifiedName,
    )
    const resolvedQualifiedName = firstNonEmptyText(
      ref?.qualifiedName,
      ref?.name,
      config.qualifiedName,
      configuredReferenceValue(config.ref),
      config.toolName,
      nested.qualifiedName,
      configuredReferenceValue(nested.ref),
      nested.toolName,
    )
    return {
      ...common,
      toolConfig: {
        ref: firstNonEmptyText(ref?.name, ref?.qualifiedName, configuredRef),
        qualifiedName: resolvedQualifiedName || null,
        projectCode: ref?.projectCode || null,
        credentialRef: stringValue(config.credentialRef),
        maxRequestTimeMs: numberValue(config.maxRequestTimeMs, 180000),
        inputMapping: firstRecordValue(config.inputMapping, config.args),
        mappingNote: stringValue(config.mappingNote),
      } satisfies ToolNodeConfig,
    }
  }
  if (kind === 'condition') {
    const nested = objectRecordValue(config.conditionConfig)
    const merged = { ...nested, ...config }
    delete merged.conditionConfig
    const rawGroups = merged.conditionGroups ?? merged.groups
    return {
      ...common,
      conditionConfig: {
        groups: Array.isArray(rawGroups) ? rawGroups as ConditionNodeConfig['groups'] : [],
        defaultRoute: stringValue(merged.defaultRoute) || 'else',
      } satisfies ConditionNodeConfig,
    }
  }
  if (kind === 'parameter') {
    const nested = objectRecordValue(config.parameterConfig)
    const mode = firstNonEmptyText(config.extractMode, config.mode, nested.extractMode, nested.mode)
    return {
      ...common,
      parameterConfig: {
        mode: mode.toLowerCase() === 'llm' ? 'llm' : 'expression',
        modelInstanceId: firstNonEmptyText(config.modelInstanceId, nested.modelInstanceId),
        inputExpression: firstNonEmptyText(config.inputExpression, nested.inputExpression),
        systemPrompt: firstNonEmptyText(config.systemPrompt, nested.systemPrompt),
        userPrompt: firstNonEmptyText(config.userPrompt, nested.userPrompt),
        modelParams: firstRecordValue(
          config.modelParams,
          config.options,
          nested.modelParams,
          nested.options,
        ),
        fields: schemaValue(config.fields || nested.fields),
      } satisfies ParameterNodeConfig,
    }
  }
  if (kind === 'answer') {
    const template = firstNonEmptyText(config.template, config.answer, config.content, config.message)
      || '{{ lastOutput }}'
    return {
      ...common,
      answerConfig: {
        template,
      },
      template,
      writeToAnswer: true,
    }
  }
  if (kind === 'code') {
    return {
      ...common,
      codeConfig: {
        language: 'expression' as const,
        code: stringValue(config.code),
        outputs: stringRecord(config.outputs),
      },
    }
  }
  if (kind === 'classifier') {
    const nested = objectRecordValue(config.classifierConfig)
    const merged = { ...nested, ...config }
    delete merged.classifierConfig
    const classifierConfig = {
      inputExpression: stringValue(merged.inputExpression) || 'input',
      strategy: classifierStrategyValue(merged.strategy),
      classes: classifierClassesValue(merged.classes),
      defaultRoute: stringValue(merged.defaultRoute) || 'else',
      modelInstanceId: stringValue(merged.modelInstanceId),
      confidenceThreshold: numberValue(merged.confidenceThreshold, 0.7),
      llmPrompt: stringValue(merged.llmPrompt),
      modelParams: firstRecordValue(
        config.modelParams,
        config.options,
        nested.modelParams,
        nested.options,
      ),
    } satisfies IntentClassifierNodeConfig
    return {
      ...common,
      outputs: classifierOutputPorts(classifierConfig),
      classifierConfig,
    }
  }
  if (kind === 'aggregate') {
    return {
      ...common,
      aggregateConfig: {
        mode: aggregateModeValue(config.aggregateMode),
        items: aggregateItemsValue(config.items),
        template: stringValue(config.template),
      },
    }
  }
  if (kind === 'approval') {
    return {
      ...common,
      approvalConfig: {
        title: stringValue(config.title) || '人工确认',
        prompt: stringValue(config.prompt) || '{{ lastOutput }}',
        approvers: arrayValue(config.approvers),
        timeoutSeconds: numberValue(config.timeoutSeconds, 3600),
        defaultRoute: stringValue(config.defaultRoute) || 'approved',
      },
    }
  }
  if (kind === 'loop') {
    const nested = (config.loopConfig && typeof config.loopConfig === 'object'
      ? config.loopConfig
      : {}) as Record<string, unknown>
    return {
      ...common,
      loopConfig: normalizeLoopConfig({
        mode: 'FOREACH',
        collection: stringValue(config.collection || nested.collection || config.itemExpression || nested.itemExpression),
        itemAlias: stringValue(config.itemAlias || nested.itemAlias) || 'item',
        indexAlias: stringValue(config.indexAlias || nested.indexAlias) || 'index',
        outputAlias: stringValue(config.outputAlias || nested.outputAlias || config.loopKey || nested.loopKey) || 'loop_results',
        bodyOutput: stringValue(config.bodyOutput || nested.bodyOutput) || 'lastOutput',
        maxIterations: numberValue(config.maxIterations ?? nested.maxIterations, 100),
        bodyEntry: stringValue(config.bodyEntry || nested.bodyEntry),
        bodyExit: stringValue(config.bodyExit || nested.bodyExit),
        bodyNodeIds: arrayValue(config.bodyNodeIds || nested.bodyNodeIds),
      }),
    }
  }
  if (kind === 'knowledgeWrite') {
    return {
      ...common,
      knowledgeWriteConfig: {
        knowledgeBaseCode: stringValue(config.knowledgeBaseCode || config.knowledgeBaseGroupId),
        titleExpression: stringValue(config.titleExpression) || 'const:工作流写入',
        contentExpression: stringValue(config.contentExpression) || 'lastOutput',
        tags: arrayValue(config.tags),
        mode: knowledgeWriteModeValue(config.writeMode),
      },
    }
  }
  if (kind === 'documentExtract') {
    return {
      ...common,
      documentExtractConfig: {
        sourceExpression: stringValue(config.sourceExpression) || 'lastOutput',
        format: documentFormatValue(config.format),
        fields: schemaValue(config.fields),
      },
    }
  }
  if (kind === 'mcp') {
    return {
      ...common,
      mcpConfig: {
        serverRef: stringValue(config.serverRef),
        toolName: stringValue(config.toolName),
        inputMapping: stringRecord(config.inputMapping),
      },
    }
  }
  if (kind === 'http') {
    return {
      ...common,
      httpConfig: {
        method: stringValue(config.method) || 'GET',
        url: stringValue(config.url),
        queryParams: stringRecord(config.queryParams),
        headers: stringRecord(config.headers),
        bodyType: ['json', 'text'].includes(stringValue(config.bodyType)) ? stringValue(config.bodyType) as 'json' | 'text' : 'none',
        body: stringValue(config.body),
        timeoutMs: numberValue(config.timeoutMs, 30000),
        credentialRef: stringValue(config.credentialRef),
      } satisfies HttpNodeConfig,
    }
  }
  if (kind === 'knowledge') {
    return {
      ...common,
      knowledgeConfig: {
        knowledgeBaseCodes: arrayValue(config.knowledgeBaseCodes),
        query: stringValue(config.query) || 'input',
        topK: numberValue(config.topK, 5),
        similarityThreshold: numberValue(config.similarityThreshold, 0),
        searchMode: stringValue(config.searchMode) || 'hybrid',
        rerankEnabled: config.rerankEnabled !== false,
      } satisfies KnowledgeNodeConfig,
    }
  }
  if (kind === 'variable') {
    return {
      ...common,
      assignments: jsonValueRecord(config.assignments),
    }
  }
  return {
    ...common,
    assignments: jsonValueRecord(config.assignments),
    template: stringValue(config.template),
    writeToAnswer: config.writeToAnswer !== false,
  }
}

export function createDefaultNodeData(kind: CanvasNodeKind, label: string, base?: AgentForm): CanvasNode['data'] {
  const common = {
    label,
    kind,
    configVersion: 2 as const,
    description: '',
    source: 'CANVAS' as const,
    category: nodeCategory(kind),
    inputs: defaultPorts(kind, 'input'),
    outputs: defaultPorts(kind, 'output', defaultOutputAlias(kind)),
    inputSchema: {},
    outputSchema: {},
    inputMapping: {},
    retry: defaultRetryPolicy(kind),
    errorPolicy: defaultErrorPolicy(),
    collapsed: false,
    outputAlias: defaultOutputAlias(kind),
  }
  if (kind === 'userInput') {
    const userInputConfig = defaultUserInputConfig()
    return {
      ...common,
      outputAlias: userInputConfig.outputAlias,
      outputs: userInputOutputPorts(userInputConfig.fields, userInputConfig.outputAlias),
      userInputConfig,
    }
  }
  if (kind === 'interaction') {
    const interactionConfig = defaultInteractionConfig()
    return {
      ...common,
      outputAlias: interactionConfig.outputAlias,
      outputs: interactionOutputPorts(interactionConfig, interactionConfig.outputAlias),
      interactionConfig,
    }
  }
  if (kind === 'pageAction') {
    const pageActionConfig = defaultPageActionConfig()
    return {
      ...common,
      outputAlias: pageActionConfig.outputAlias,
      outputs: defaultPorts('pageAction', 'output', pageActionConfig.outputAlias),
      pageActionConfig,
    }
  }
  if (kind === 'llm') return { ...common, llmConfig: defaultLlmConfig(base) }
  if (kind === 'tool' || kind === 'skill') return { ...common, toolConfig: defaultToolConfig() }
  if (kind === 'knowledge') return { ...common, knowledgeConfig: defaultKnowledgeConfig() }
  if (kind === 'http') return { ...common, httpConfig: defaultHttpConfig() }
  if (kind === 'parameter') return { ...common, parameterConfig: defaultParameterConfig() }
  if (kind === 'condition') return { ...common, conditionConfig: defaultConditionConfig() }
  if (kind === 'answer') return { ...common, answerConfig: defaultAnswerConfig(), template: '{{ lastOutput }}', writeToAnswer: true }
  if (kind === 'code') return { ...common, codeConfig: defaultCodeConfig() }
  if (kind === 'classifier') {
    const classifierConfig = defaultClassifierConfig()
    return { ...common, outputs: classifierOutputPorts(classifierConfig), classifierConfig }
  }
  if (kind === 'aggregate') return { ...common, aggregateConfig: defaultAggregateConfig() }
  if (kind === 'approval') return { ...common, approvalConfig: defaultApprovalConfig() }
  if (kind === 'loop') return { ...common, loopConfig: defaultLoopConfig() }
  if (kind === 'knowledgeWrite') return { ...common, knowledgeWriteConfig: defaultKnowledgeWriteConfig() }
  if (kind === 'documentExtract') return { ...common, documentExtractConfig: defaultDocumentExtractConfig() }
  if (kind === 'mcp') return { ...common, mcpConfig: defaultMcpConfig() }
  if (kind === 'variable') return { ...common, assignments: { value: 'lastOutput' } }
  if (kind === 'template') return { ...common, template: '{{ lastOutput }}', writeToAnswer: true }
  return { ...common }
}

function defaultLlmConfig(base?: AgentForm): LlmNodeConfig {
  return {
    modelInstanceId: base?.modelInstanceId || '',
    systemPrompt: base?.systemPrompt || '',
    userPrompt: '{{ input }}',
    contextVariables: ['input', 'lastOutput'],
    modelParams: {},
    outputFormat: 'text',
    structuredOutput: false,
    strictJsonSchema: true,
    outputSchema: [],
    messages: defaultLlmMessages(base?.systemPrompt || '', '{{ input }}'),
    visionEnabled: false,
    visionInputs: [],
    promptTemplateMode: 'messages',
  }
}

function defaultLlmMessages(systemPrompt: string, userPrompt: string): NonNullable<LlmNodeConfig['messages']> {
  return [
    { id: 'system', role: 'system', content: systemPrompt || '', templateEngine: 'mustache', enabled: true },
    { id: 'user', role: 'user', content: userPrompt || '{{ input }}', templateEngine: 'mustache', enabled: true },
  ]
}

function defaultKnowledgeConfig(): KnowledgeNodeConfig {
  return {
    knowledgeBaseCodes: [],
    query: 'input',
    topK: 5,
    similarityThreshold: 0.5,
    searchMode: 'hybrid',
    rerankEnabled: true,
  }
}

function defaultHttpConfig(): HttpNodeConfig {
  return {
    method: 'GET',
    url: '',
    queryParams: {},
    headers: {},
    bodyType: 'none',
    body: '',
    timeoutMs: 30000,
    credentialRef: '',
  }
}

function defaultParameterConfig(): ParameterNodeConfig {
  return {
    mode: 'expression',
    inputExpression: 'input',
    systemPrompt: '',
    userPrompt: '',
    modelParams: {},
    fields: [{ name: 'value', type: 'string', required: false, source: 'lastOutput' }],
  }
}

function defaultUserInputConfig(): UserInputNodeConfig {
  return {
    outputAlias: 'params',
    fields: [
      { name: 'question', type: 'string', required: true, description: '用户问题', source: 'input.message' },
    ],
  }
}

function defaultInteractionConfig(): InteractionNodeConfig {
  return {
    interactionType: 'PRESENT_OUTPUT',
    binding: { sourceKind: 'NONE' },
    title: '展示结构化结果',
    component: 'DETAIL',
    outputAlias: 'interaction_output',
    fields: [],
    dataExpression: 'lastOutput',
    dataSources: {},
    behavior: {
      blocking: false,
      readonly: true,
    },
    presentation: { mode: 'card_only' },
    renderSchema: {},
  }
}

function defaultPageActionConfig(): PageActionNodeConfig {
  return {
    actionKey: '',
    title: '页面动作',
    confirm: true,
    args: {},
    outputAlias: 'page_action_result',
    metadata: {},
  }
}

function defaultAnswerConfig() {
  return {
    template: '{{ lastOutput }}',
  }
}

function defaultCodeConfig() {
  return {
    language: 'expression' as const,
    code: '// Safe expression mode. Configure outputs below.',
    outputs: { result: 'lastOutput' },
  }
}

function defaultClassifierConfig(): IntentClassifierNodeConfig {
  return {
    inputExpression: 'input',
    strategy: 'KEYWORD',
    classes: [
      { id: 'matched', label: 'Matched', keywords: [] },
    ],
    defaultRoute: 'else',
    modelInstanceId: '',
    confidenceThreshold: 0.7,
    llmPrompt: '',
    modelParams: {},
  }
}

function defaultAggregateConfig(): VariableAggregateNodeConfig {
  return {
    mode: 'object' as const,
    items: [{ name: 'value', source: 'lastOutput' }],
    template: '',
  }
}

function defaultApprovalConfig(): HumanApprovalNodeConfig {
  return {
    title: '人工确认',
    prompt: '{{ lastOutput }}',
    approvers: [],
    timeoutSeconds: 3600,
    defaultRoute: 'approved',
  }
}

function defaultLoopConfig(): LoopNodeConfig {
  return {
    mode: 'FOREACH',
    collection: '',
    itemAlias: 'item',
    indexAlias: 'index',
    outputAlias: 'loop_results',
    bodyOutput: 'lastOutput',
    maxIterations: 100,
    bodyEntry: '',
    bodyExit: '',
    bodyNodeIds: [],
  }
}

function normalizeLoopConfig(raw: Partial<LoopNodeConfig> | undefined): LoopNodeConfig {
  const collection = stringValue(raw?.collection || raw?.itemExpression) || ''
  const bodyEntry = stringValue(raw?.bodyEntry)
  const bodyExit = stringValue(raw?.bodyExit) || bodyEntry
  const bodyNodeIds = Array.from(new Set([
    ...arrayValue(raw?.bodyNodeIds),
    ...(bodyEntry ? [bodyEntry] : []),
    ...(bodyExit ? [bodyExit] : []),
  ].filter(Boolean)))
  let maxIterations = numberValue(raw?.maxIterations, 100)
  if (maxIterations < 1) maxIterations = 100
  if (maxIterations > 1000) maxIterations = 1000
  return {
    mode: 'FOREACH',
    collection,
    itemAlias: stringValue(raw?.itemAlias) || 'item',
    indexAlias: stringValue(raw?.indexAlias) || 'index',
    outputAlias: stringValue(raw?.outputAlias || raw?.loopKey) || 'loop_results',
    bodyOutput: stringValue(raw?.bodyOutput) || 'lastOutput',
    maxIterations,
    bodyEntry,
    bodyExit,
    bodyNodeIds,
  }
}

function defaultKnowledgeWriteConfig(): KnowledgeWriteNodeConfig {
  return {
    knowledgeBaseCode: '',
    titleExpression: 'const:工作流写入',
    contentExpression: 'lastOutput',
    tags: [],
    mode: 'draft',
  }
}

function defaultDocumentExtractConfig(): DocumentExtractNodeConfig {
  return {
    sourceExpression: 'lastOutput',
    format: 'text',
    fields: [],
  }
}

function defaultMcpConfig(): McpNodeConfig {
  return {
    serverRef: '',
    toolName: '',
    inputMapping: {},
  }
}

function defaultConditionConfig(): ConditionNodeConfig {
  return {
    groups: [{
      id: 'matched',
      label: 'Matched',
      logic: 'AND',
      conditions: [{ left: 'lastOutput', operator: 'not_empty' }],
    }],
    defaultRoute: 'else',
  }
}

function defaultToolConfig(): ToolNodeConfig {
  return {
    ref: '',
    qualifiedName: null,
    projectCode: null,
    credentialRef: '',
    maxRequestTimeMs: 180000,
    inputMapping: {},
    mappingNote: '',
  }
}

function defaultOutputAlias(kind: CanvasNodeKind) {
  if (kind === 'userInput') return 'params'
  if (kind === 'interaction') return 'interaction_output'
  if (kind === 'pageAction') return 'page_action_result'
  if (kind !== 'start' && kind !== 'end' && kind !== 'llm' && kind !== 'condition' && kind !== 'answer') return `${kind}_output`
  return ''
}

function normalizeCanvasNodeData(node: CanvasNode, base: AgentForm): CanvasNode {
  const kind = (node.data?.kind ?? node.type) as CanvasNodeKind
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

function graphNodeKindToCanvas(type: WorkflowGraphNode['type']): CanvasNodeKind {
  if (type === 'USER_INPUT') return 'userInput'
  if (type === 'INTERACTION') return 'interaction'
  if (type === 'PAGE_ACTION') return 'pageAction'
  if (type === 'LLM') return 'llm'
  if (type === 'CAPABILITY') return 'skill'
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
  return 'tool'
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

const CANVAS_BRANCH_SOURCE_NODE_KINDS = new Set<CanvasNodeKind>(['classifier', 'condition', 'approval'])

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

function portValue(value: unknown): StudioPort[] | null {
  if (!Array.isArray(value)) return null
  return value
    .filter((item): item is Record<string, unknown> => !!item && typeof item === 'object')
    .map((item) => ({
      id: stringValue(item.id || item.name),
      name: stringValue(item.name || item.id),
      type: stringValue(item.type) as StudioPort['type'],
      required: item.required === true,
      schema: stringValue(item.schema),
      source: stringValue(item.source),
    }))
    .filter((item) => !!item.id)
}

function defaultPorts(kind: CanvasNodeKind, direction: 'input' | 'output', alias?: string): StudioPort[] {
  if (kind === 'start') {
    return direction === 'output'
      ? [{ id: 'input', name: 'input', type: 'message', required: true }]
      : []
  }
  if (kind === 'end') {
    return direction === 'input'
      ? [{ id: 'answer', name: 'answer', type: 'message', required: false }]
      : []
  }
  if (kind === 'answer') {
    return direction === 'input'
      ? [{ id: 'input', name: 'input', type: 'message', required: false, source: '$lastOutput' }]
      : [{ id: 'answer', name: 'answer', type: 'message' }]
  }
  if (direction === 'input') {
    return [{ id: 'input', name: 'input', type: 'message', required: false, source: '$input' }]
  }
  const output = alias || defaultOutputAlias(kind) || 'output'
  if (kind === 'userInput' || kind === 'interaction' || kind === 'pageAction') {
    return [{ id: output, name: output, type: 'object' }]
  }
  if (kind === 'approval') {
    return [
      { id: 'approved', name: 'approved', type: 'boolean' },
      { id: 'rejected', name: 'rejected', type: 'boolean' },
      { id: 'timeout', name: 'timeout', type: 'boolean' },
    ]
  }
  if (kind === 'loop') {
    return [{ id: 'output', name: 'output', type: 'array' }]
  }
  if (kind === 'condition' || kind === 'classifier') {
    if (kind === 'classifier') {
      return classifierOutputPorts(defaultClassifierConfig())
    }
    return [
      { id: 'matched', name: 'matched', type: 'boolean' },
      { id: 'else', name: 'else', type: 'boolean' },
    ]
  }
  return [{ id: output, name: output, type: kind === 'parameter' ? 'object' : 'any' }]
}

function syncDynamicPorts(data: CanvasNode['data']): CanvasNode['data'] {
  const outputs = dynamicOutputPorts(data)
  return outputs ? { ...data, outputs } : data
}

function dynamicOutputPorts(data: CanvasNode['data']): StudioPort[] | null {
  if (data.kind === 'userInput') {
    const config = data.userInputConfig || defaultUserInputConfig()
    return userInputOutputPorts(config.fields || [], config.outputAlias || data.outputAlias || 'params')
  }
  if (data.kind === 'interaction') {
    const config = data.interactionConfig || defaultInteractionConfig()
    return interactionOutputPorts(config, config.outputAlias || data.outputAlias || 'interaction_output')
  }
  if (data.kind === 'classifier') {
    return classifierOutputPorts(data.classifierConfig || defaultClassifierConfig())
  }
  return null
}

export function userInputOutputPorts(fields: StudioFieldSchema[], outputAlias = 'params'): StudioPort[] {
  const alias = outputAlias || 'params'
  const ports: StudioPort[] = [{ id: alias, name: alias, type: 'object', required: false }]
  const seen = new Set<string>([alias])
  for (const field of fields || []) {
    const name = field.name?.trim()
    if (!name) continue
    const id = `${alias}.${name}`
    if (seen.has(id)) continue
    seen.add(id)
    ports.push({
      id,
      name: id,
      type: field.type || 'string',
      required: field.required === true,
      source: alias,
    })
  }
  return ports
}

export function interactionOutputPorts(config: InteractionNodeConfig, outputAlias = 'interaction_output'): StudioPort[] {
  const alias = outputAlias || config.outputAlias || 'interaction_output'
  const ports: StudioPort[] = [{ id: alias, name: alias, type: 'object', required: false }]
  const seen = new Set<string>([alias])
  if (config.interactionType === 'COLLECT_INPUT' || config.interactionType === 'USER_CHOICE'
      || config.interactionType === 'CONFIRM_ACTION' || config.interactionType === 'REVIEW_EDIT') {
    for (const field of config.fields || []) {
      const key = fieldKey(field)
      if (!key) continue
      const id = `${alias}.${key}`
      if (seen.has(id)) continue
      seen.add(id)
      ports.push({
        id,
        name: id,
        type: field.type || 'string',
        required: field.required === true,
        source: alias,
      })
    }
  }
  if (config.interactionType === 'PRESENT_OUTPUT' && !seen.has(`${alias}.acknowledged`)) {
    ports.push({ id: `${alias}.acknowledged`, name: `${alias}.acknowledged`, type: 'boolean', source: alias })
  }
  return ports
}

function interactionFieldsForGraph(fields: StudioFieldSchema[]): StudioFieldSchema[] {
  return (fields || []).map((field) => {
    const key = fieldKey(field)
    return {
      ...field,
      key,
      name: key,
    }
  })
}

function fieldKey(field: StudioFieldSchema) {
  return (field.key || field.name || '').trim()
}

export function classifierOutputPorts(config: IntentClassifierNodeConfig): StudioPort[] {
  const ports: StudioPort[] = []
  const seen = new Set<string>()
  for (const item of config.classes || []) {
    const id = item.id?.trim()
    if (!id || seen.has(id)) continue
    seen.add(id)
    ports.push({
      id,
      name: item.label?.trim() || id,
      type: 'boolean',
      required: false,
    })
  }
  const defaultRoute = (config.defaultRoute || 'else').trim()
  if (defaultRoute && !seen.has(defaultRoute)) {
    ports.push({
      id: defaultRoute,
      name: defaultRoute === 'else' ? 'else' : defaultRoute,
      type: 'boolean',
      required: false,
    })
  }
  return ports.length ? ports : [{ id: 'else', name: 'else', type: 'boolean', required: false }]
}

function defaultRetryPolicy(kind: CanvasNodeKind): StudioRetryPolicy {
  const enabled = studioNodeRetryable(kind)
  return {
    enabled,
    maxAttempts: enabled ? 2 : 1,
    backoffMs: 800,
  }
}

function defaultErrorPolicy(): StudioErrorPolicy {
  return {
    strategy: 'TERMINATE' as const,
  }
}

function nodeCategory(kind: CanvasNodeKind) {
  return studioNodeCategory(kind)
}

function isSdkDefinition(def: WorkflowCanvasSource) {
  const sdkGraph = def.extra?.sdkGraph
  return !!sdkGraph && typeof sdkGraph === 'object'
    && (sdkGraph as Record<string, unknown>).source === 'SDK'
}

function stringValue(value: unknown) {
  return value == null ? '' : String(value)
}

function numberValue(value: unknown, fallback: number) {
  const n = Number(value)
  return Number.isFinite(n) ? n : fallback
}

function arrayValue(value: unknown) {
  return Array.isArray(value) ? value.map((item) => String(item)).filter(Boolean) : []
}

function objectRecordValue(value: unknown): Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return {}
  return value as Record<string, unknown>
}

function firstRecordValue(...values: unknown[]): Record<string, unknown> {
  for (const value of values) {
    if (value !== null && value !== undefined) return objectRecordValue(value)
  }
  return {}
}

function firstNonEmptyText(...values: unknown[]) {
  for (const value of values) {
    const text = stringValue(value).trim()
    if (text) return text
  }
  return ''
}

function configuredReferenceValue(value: unknown): string {
  const reference = objectRecordValue(value)
  if (Object.keys(reference).length) {
    return firstNonEmptyText(
      reference.qualifiedName,
      reference.name,
      configuredReferenceValue(reference.ref),
      reference.toolName,
    )
  }
  return stringValue(value).trim()
}

function stringRecord(value: unknown): Record<string, string> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return {}
  const out: Record<string, string> = {}
  for (const [key, raw] of Object.entries(value)) out[key] = String(raw)
  return out
}

/** Preserve native JSON types for VARIABLE_ASSIGN save/reopen. */
function jsonValueRecord(value: unknown): Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return {}
  const out: Record<string, unknown> = {}
  for (const [key, raw] of Object.entries(value)) {
    if (!key) continue
    out[key] = raw
  }
  return out
}

function schemaValue(value: unknown): StudioFieldSchema[] {
  if (!Array.isArray(value)) return []
  return value
    .filter((item): item is Record<string, unknown> => !!item && typeof item === 'object')
    .map((item) => {
      const name = stringValue(item.name || item.key)
      return {
        ...item,
        name,
        key: stringValue(item.key || item.name) || name,
        type: fieldTypeValue(item.type),
        required: item.required === true,
        description: stringValue(item.description || item.label),
        defaultValue: stringValue(item.defaultValue),
        source: stringValue(item.source),
        targetPath: stringValue(item.targetPath),
        component: stringValue(item.component),
        datasource: stringValue(item.datasource),
        slotFilling: slotFillingValue(item.slotFilling),
      } as StudioFieldSchema
    })
}

function slotFillingValue(value: unknown): StudioFieldSchema['slotFilling'] {
  const raw = objectRecordValue(value)
  if (!Object.keys(raw).length) return undefined
  const strategies = arrayValue(raw.strategies)
    .map((item) => item.toUpperCase())
    .filter((item) => ['USER_INPUT', 'RULE', 'LLM', 'DICTIONARY'].includes(item)) as NonNullable<StudioFieldSchema['slotFilling']>['strategies']
  const policy = stringValue(raw.confirmPolicy).toUpperCase()
  return {
    enabled: raw.enabled === true,
    strategies: normalizeSlotStrategies(strategies, raw.enabled === true),
    confirmPolicy: ['NEVER', 'ALWAYS'].includes(policy) ? policy as 'NEVER' | 'ALWAYS' : 'LOW_CONFIDENCE',
    confidenceThreshold: numberValue(raw.confidenceThreshold, 0.85),
    llmPrompt: stringValue(raw.llmPrompt),
    modelInstanceId: stringValue(raw.modelInstanceId),
    patterns: arrayValue(raw.patterns),
    dictionaryValues: arrayValue(raw.dictionaryValues),
  }
}

function normalizeSlotStrategies(
  strategies: NonNullable<StudioFieldSchema['slotFilling']>['strategies'],
  enabled: boolean,
): NonNullable<StudioFieldSchema['slotFilling']>['strategies'] {
  const extractionStrategies = strategies.filter((item) => item !== 'USER_INPUT')
  if (enabled && extractionStrategies.length === 0) {
    return ['LLM']
  }
  return extractionStrategies.length ? extractionStrategies : ['LLM']
}

function fieldTypeValue(value: unknown): StudioFieldSchema['type'] {
  const raw = stringValue(value).toLowerCase()
  return ['number', 'integer', 'boolean', 'object', 'array', 'file'].includes(raw)
    ? raw as StudioFieldSchema['type']
    : 'string'
}

function interactionTypeValue(value: unknown): InteractionNodeConfig['interactionType'] {
  const raw = stringValue(value).toUpperCase()
  if (['PRESENT_OUTPUT', 'USER_CHOICE', 'CONFIRM_ACTION', 'REVIEW_EDIT'].includes(raw)) {
    return raw as InteractionNodeConfig['interactionType']
  }
  return 'COLLECT_INPUT'
}

function interactionComponentValue(value: unknown): InteractionNodeConfig['component'] {
  const raw = stringValue(value).toUpperCase()
  if (['DETAIL', 'TABLE', 'CARD', 'REPORT', 'CUSTOM'].includes(raw)) {
    return raw as InteractionNodeConfig['component']
  }
  return 'FORM'
}

function interactionBindingValue(value: unknown): InteractionNodeConfig['binding'] {
  const raw = objectRecordValue(value)
  const sourceKind = stringValue(raw.sourceKind).toUpperCase()
  const normalizedSourceKind = ['TOOL', 'COMPOSITION', 'API'].includes(sourceKind)
    ? sourceKind as NonNullable<InteractionNodeConfig['binding']>['sourceKind']
    : 'NONE'
  return {
    sourceKind: normalizedSourceKind,
    ref: stringValue(raw.ref),
    qualifiedName: stringValue(raw.qualifiedName) || null,
    projectCode: stringValue(raw.projectCode) || null,
    projectId: raw.projectId == null ? null : numberValue(raw.projectId, 0),
    apiNodeId: raw.apiNodeId == null ? null : numberValue(raw.apiNodeId, 0),
    apiMethod: stringValue(raw.apiMethod) || null,
    apiPath: stringValue(raw.apiPath) || null,
    generatedFrom: stringValue(raw.generatedFrom),
    autoCreateCallNode: raw.autoCreateCallNode === true,
    autoCreateDisplayNode: raw.autoCreateDisplayNode === true || (raw.autoCreateDisplayNode == null && raw.autoCreateCallNode === true),
    callNodeId: stringValue(raw.callNodeId),
    displayNodeId: stringValue(raw.displayNodeId),
  }
}

function llmMessagesValue(value: unknown, systemPrompt: string, userPrompt: string): NonNullable<LlmNodeConfig['messages']> {
  if (!Array.isArray(value)) {
    return defaultLlmMessages(systemPrompt, userPrompt)
  }
  const messages = value
    .filter((item): item is Record<string, unknown> => !!item && typeof item === 'object')
    .map((item, index) => {
      const rawRole = stringValue(item.role)
      const role: LlmPromptMessage['role'] = rawRole === 'assistant' || rawRole === 'system' ? rawRole : 'user'
      return {
        id: stringValue(item.id) || `message-${index + 1}`,
        role,
        content: stringValue(item.content),
        templateEngine: 'mustache' as const,
        enabled: item.enabled !== false,
      }
    })
    .filter((item) => !!item.content || item.role === 'system')
  return messages.length ? messages : defaultLlmMessages(systemPrompt, userPrompt)
}

function classifierStrategyValue(value: unknown): IntentClassifierNodeConfig['strategy'] {
  const strategy = stringValue(value).toUpperCase()
  if (strategy === 'LLM' || strategy === 'HYBRID') return strategy
  return 'KEYWORD'
}

function classifierClassesValue(value: unknown): IntentClassifierNodeConfig['classes'] {
  if (!Array.isArray(value)) return []
  return value
    .filter((item): item is Record<string, unknown> => !!item && typeof item === 'object')
    .map((item) => ({
      id: stringValue(item.id),
      label: stringValue(item.label),
      description: stringValue(item.description),
      keywords: Array.isArray(item.keywords) ? item.keywords.map((keyword) => stringValue(keyword)).filter(Boolean) : [],
    }))
    .filter((item) => !!item.id)
}

function aggregateItemsValue(value: unknown): VariableAggregateNodeConfig['items'] {
  if (!Array.isArray(value)) return []
  return value
    .filter((item): item is Record<string, unknown> => !!item && typeof item === 'object')
    .map((item) => ({ name: stringValue(item.name), source: stringValue(item.source) }))
    .filter((item) => !!item.name && !!item.source)
}

function aggregateModeValue(value: unknown): VariableAggregateNodeConfig['mode'] {
  const mode = stringValue(value)
  if (mode === 'array' || mode === 'text') return mode
  return 'object'
}

function documentFormatValue(value: unknown): DocumentExtractNodeConfig['format'] {
  const format = stringValue(value)
  if (format === 'markdown' || format === 'json') return format
  return 'text'
}

function knowledgeWriteModeValue(value: unknown): KnowledgeWriteNodeConfig['mode'] {
  return stringValue(value) === 'publish' ? 'publish' : 'draft'
}

function normalizeParams(value?: Record<string, unknown>) {
  return value || {}
}

export function kindColor(kind: CanvasNodeKind) {
  return studioNodeColor(kind)
}
