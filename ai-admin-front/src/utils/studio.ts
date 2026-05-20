import type { AgentDefinition, AgentForm, AgentGraphNode, AgentGraphSpec } from '@/types/agent'
import { studioNodeCategory, studioNodeColor, studioNodeRetryable } from '@/utils/studioNodeRegistry'
import type {
  CanvasEdge,
  CanvasNode,
  CanvasNodeKind,
  CanvasSnapshot,
  ConditionNodeConfig,
  DocumentExtractNodeConfig,
  HumanApprovalNodeConfig,
  HttpNodeConfig,
  IntentClassifierNodeConfig,
  KnowledgeWriteNodeConfig,
  KnowledgeNodeConfig,
  LlmNodeConfig,
  LlmPromptMessage,
  LoopNodeConfig,
  McpNodeConfig,
  ParameterNodeConfig,
  StudioErrorPolicy,
  StudioPort,
  StudioRetryPolicy,
  StudioFieldSchema,
  ToolNodeConfig,
  VariableAggregateNodeConfig,
} from '@/types/studio'

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

  const normalized: CanvasSnapshot = {
    version: 2,
    nodes: snapshot.nodes.map((node) => ensureNodeV2(node, base)),
    edges: snapshot.edges.map(decorateSerializableEdge),
  }

  return {
    ...base,
    tools,
    skills,
    knowledgeBaseGroupId: knowledgeCodes[0] || '',
    canvasJson: JSON.stringify(normalized),
    graphSpec: canvasToGraphSpec(base, normalized),
  }
}

function canvasToGraphSpec(base: AgentForm, snapshot: CanvasSnapshot): AgentGraphSpec {
  const graphNodes: AgentGraphNode[] = snapshot.nodes
    .filter((node) => node.data.kind !== 'start' && node.data.kind !== 'end')
    .map((node) => canvasNodeToGraphNode(node, base))

  const graphEdges: AgentGraphSpec['edges'] = snapshot.edges
    .map((edge) => ({
      id: edge.id,
      from: graphEndpoint(edge.source),
      to: graphEndpoint(edge.target),
      condition: edge.condition || edge.label || 'always',
      sourceHandle: edge.sourceHandle,
      targetHandle: edge.targetHandle,
      layout: {
        label: edge.label || edge.condition || 'always',
        style: edge.type || 'smoothstep',
      },
    }))
    .filter((edge) => edge.from !== 'END' && edge.to !== 'START')

  const firstNode = graphNodes[0]?.id || ''
  if (!graphEdges.some((edge) => edge.from === 'START') && firstNode) {
    graphEdges.unshift({ from: 'START', to: firstNode, condition: 'always' })
  }
  if (!graphEdges.some((edge) => edge.to === 'END') && firstNode) {
    graphEdges.push({ from: graphNodes[graphNodes.length - 1]?.id || firstNode, to: 'END', condition: 'always' })
  }

  const entry = graphEdges.find((edge) => edge.from === 'START' && edge.to !== 'END')?.to || firstNode
  const finish = graphNodes
    .filter((node) => graphEdges.some((edge) => edge.from === node.id && edge.to === 'END'))
    .map((node) => node.id)

  return {
    code: base.keySlug || base.name || 'agent_graph',
    name: base.name || 'Agent Graph',
    mode: 'WORKFLOW',
    runtimeHint: base.runtimeType,
    layout: {
      engine: 'vue-flow',
      direction: 'LR',
    },
    nodes: graphNodes,
    edges: graphEdges,
    entry,
    finish: finish.length ? finish : firstNode ? [firstNode] : [],
  }
}

function canvasNodeToGraphNode(node: CanvasNode, base: AgentForm): AgentGraphNode {
  const common = commonNodeConfig(node)
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
        writeToAnswer: node.data.writeToAnswer ?? true,
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
        classes: classifier.classes || [],
        defaultRoute: classifier.defaultRoute || 'else',
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
    const loop = node.data.loopConfig || defaultLoopConfig()
    return {
      id: node.id,
      type: 'LOOP',
      name: node.data.label,
      ...graphNodeChrome(node),
      config: {
        ...common,
        loopKey: loop.loopKey,
        maxIterations: loop.maxIterations,
        itemExpression: loop.itemExpression,
        breakCondition: loop.breakCondition,
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
      directReturnEnabled: knowledge.directReturnEnabled ?? false,
      directReturnThreshold: knowledge.directReturnThreshold,
      knowledgeConfig: knowledge,
    },
  }
}

function commonNodeConfig(node: CanvasNode): Record<string, unknown> {
  return {
    configVersion: 2,
    outputAlias: node.data.outputAlias,
    description: node.data.description,
    source: node.data.source || 'CANVAS',
    category: node.data.category,
    collapsed: node.data.collapsed === true,
    ui: { position: node.position, collapsed: node.data.collapsed === true },
  }
}

function graphNodeChrome(node: CanvasNode) {
  const dynamicOutputs = dynamicOutputPorts(node.data)
  return {
    description: node.data.description,
    inputs: node.data.inputs || defaultPorts(node.data.kind, 'input'),
    outputs: dynamicOutputs || node.data.outputs || defaultPorts(node.data.kind, 'output', node.data.outputAlias),
    retry: node.data.retry,
    errorPolicy: node.data.errorPolicy,
    layout: {
      x: node.position.x,
      y: node.position.y,
      collapsed: node.data.collapsed === true,
    },
  }
}

export function definitionToCanvas(def: AgentDefinition): CanvasSnapshot {
  if (def.canvasJson) {
    const parsed = JSON.parse(def.canvasJson) as CanvasSnapshot
    return {
      version: 2,
      nodes: (parsed.nodes || []).map((node) => ensureNodeV2(node, def as unknown as AgentForm)),
      edges: (parsed.edges || []).map(decorateSerializableEdge),
    }
  }
  if (!def.graphSpec?.nodes?.length) {
    return emptyCanvas()
  }
  return graphSpecToCanvas(def.graphSpec, def)
}

function graphSpecToCanvas(graphSpec: AgentGraphSpec, def: AgentDefinition): CanvasSnapshot {
  const nodes: CanvasNode[] = [
    { id: 'start', type: 'start', position: { x: 60, y: 220 }, data: { label: '开始', kind: 'start', configVersion: 2 } },
  ]
  ;(graphSpec.nodes || []).forEach((node, idx) => {
    const kind = graphNodeKindToCanvas(node.type)
    const config = node.config || {}
    const position = graphNodePosition(node) || configPosition(config) || { x: 260 + idx * 240, y: 220 }
    const data = graphConfigToNodeData(kind, node.name || node.id, config, def, node.ref)
    data.description = node.description || data.description
    data.inputs = portValue(node.inputs) || data.inputs
    data.outputs = portValue(node.outputs) || data.outputs
    if (node.retry) data.retry = node.retry as NonNullable<CanvasNode['data']['retry']>
    if (node.errorPolicy) data.errorPolicy = node.errorPolicy as NonNullable<CanvasNode['data']['errorPolicy']>
    data.collapsed = node.layout?.collapsed === true || data.collapsed
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
    edges: (graphSpec.edges || []).map((edge, idx) => {
      const condition = edge.condition || 'always'
      return decorateSerializableEdge({
        id: edge.id || `graph-e-${idx}`,
        source: canvasEndpoint(edge.from),
        target: canvasEndpoint(edge.to),
        condition,
        label: condition,
        sourceHandle: edge.sourceHandle,
        targetHandle: edge.targetHandle,
      })
    }),
  }
}

function graphConfigToNodeData(
  kind: CanvasNodeKind,
  label: string,
  config: Record<string, unknown>,
  def: AgentDefinition,
  ref?: AgentGraphNode['ref'],
) {
  const common = {
    label,
    kind,
    configVersion: 2 as const,
    description: stringValue(config.description),
    outputAlias: stringValue(config.outputAlias),
    source: isSdkDefinition(def) ? 'SDK' as const : 'CANVAS' as const,
    category: nodeCategory(kind),
    collapsed: config.ui && typeof config.ui === 'object'
      ? (config.ui as Record<string, unknown>).collapsed === true
      : false,
    inputs: defaultPorts(kind, 'input'),
    outputs: defaultPorts(kind, 'output', stringValue(config.outputAlias)),
    retry: defaultRetryPolicy(kind),
    errorPolicy: defaultErrorPolicy(),
  }
  if (kind === 'llm') {
    return {
      ...common,
      llmConfig: {
        modelInstanceId: stringValue(config.modelInstanceId) || def.modelInstanceId,
        systemPrompt: stringValue(config.systemPrompt) || def.systemPrompt || '',
        userPrompt: stringValue(config.userPrompt) || '{{ input }}',
        messages: llmMessagesValue(config.messages, stringValue(config.systemPrompt) || def.systemPrompt || '', stringValue(config.userPrompt) || '{{ input }}'),
        contextVariables: arrayValue(config.contextVariables),
        modelParams: recordValue(config.modelParams),
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
    return {
      ...common,
      toolConfig: {
        ref: ref?.name || ref?.qualifiedName || '',
        qualifiedName: ref?.qualifiedName || null,
        projectCode: ref?.projectCode || null,
        credentialRef: stringValue(config.credentialRef),
        inputMapping: stringRecord(config.inputMapping),
        mappingNote: stringValue(config.mappingNote),
      } satisfies ToolNodeConfig,
    }
  }
  if (kind === 'condition') {
    return {
      ...common,
      conditionConfig: {
        groups: Array.isArray(config.conditionGroups) ? config.conditionGroups as ConditionNodeConfig['groups'] : [],
        defaultRoute: stringValue(config.defaultRoute) || 'else',
      } satisfies ConditionNodeConfig,
    }
  }
  if (kind === 'parameter') {
    return {
      ...common,
      parameterConfig: {
        mode: stringValue(config.extractMode) === 'llm' ? 'llm' : 'expression',
        modelInstanceId: stringValue(config.modelInstanceId),
        fields: schemaValue(config.fields),
      } satisfies ParameterNodeConfig,
    }
  }
  if (kind === 'answer') {
    return {
      ...common,
      answerConfig: {
        template: stringValue(config.template) || '{{ lastOutput }}',
      },
      template: stringValue(config.template),
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
    const classifierConfig = {
      inputExpression: stringValue(config.inputExpression) || 'input',
      classes: classifierClassesValue(config.classes),
      defaultRoute: stringValue(config.defaultRoute) || 'else',
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
    return {
      ...common,
      loopConfig: {
        loopKey: stringValue(config.loopKey) || 'loop',
        maxIterations: numberValue(config.maxIterations, 3),
        itemExpression: stringValue(config.itemExpression),
        breakCondition: stringValue(config.breakCondition),
      },
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
        directReturnEnabled: config.directReturnEnabled === true,
        directReturnThreshold: numberValue(config.directReturnThreshold, 0),
      } satisfies KnowledgeNodeConfig,
    }
  }
  return {
    ...common,
    assignments: stringRecord(config.assignments),
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
    retry: defaultRetryPolicy(kind),
    errorPolicy: defaultErrorPolicy(),
    collapsed: false,
    outputAlias: defaultOutputAlias(kind),
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
    directReturnEnabled: false,
    directReturnThreshold: 0.85,
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
    fields: [{ name: 'value', type: 'string', required: false, source: 'lastOutput' }],
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
    classes: [
      { id: 'matched', label: 'Matched', keywords: [] },
    ],
    defaultRoute: 'else',
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
    loopKey: 'loop',
    maxIterations: 3,
    itemExpression: '',
    breakCondition: '',
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
    visibility: null,
    credentialRef: '',
    inputMapping: {},
    mappingNote: '',
  }
}

function defaultOutputAlias(kind: CanvasNodeKind) {
  if (kind !== 'start' && kind !== 'end' && kind !== 'llm' && kind !== 'condition' && kind !== 'answer') return `${kind}_output`
  return ''
}

function ensureNodeV2(node: CanvasNode, base: AgentForm): CanvasNode {
  const defaults = createDefaultNodeData(node.data.kind, node.data.label, base)
  const data = node.data.configVersion === 2 ? node.data : defaults
  const mergedData = {
    ...defaults,
    ...data,
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

function graphNodeKindToCanvas(type: AgentGraphNode['type']): CanvasNodeKind {
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

function configPosition(config: Record<string, unknown>) {
  const ui = config.ui
  if (!ui || typeof ui !== 'object') return null
  const position = (ui as Record<string, unknown>).position
  if (!position || typeof position !== 'object') return null
  const pos = position as Record<string, unknown>
  const x = typeof pos.x === 'number' ? pos.x : Number(pos.x)
  const y = typeof pos.y === 'number' ? pos.y : Number(pos.y)
  if (!Number.isFinite(x) || !Number.isFinite(y)) return null
  return { x, y }
}

function graphNodePosition(node: AgentGraphNode) {
  const layout = node.layout
  if (!layout) return null
  const x = typeof layout.x === 'number' ? layout.x : Number(layout.x)
  const y = typeof layout.y === 'number' ? layout.y : Number(layout.y)
  if (!Number.isFinite(x) || !Number.isFinite(y)) return null
  return { x, y }
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
  if (kind === 'approval') {
    return [
      { id: 'approved', name: 'approved', type: 'boolean' },
      { id: 'rejected', name: 'rejected', type: 'boolean' },
      { id: 'timeout', name: 'timeout', type: 'boolean' },
    ]
  }
  if (kind === 'loop') {
    return [
      { id: 'continue', name: 'continue', type: 'boolean' },
      { id: 'done', name: 'done', type: 'boolean' },
    ]
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
  if (data.kind === 'classifier') {
    return classifierOutputPorts(data.classifierConfig || defaultClassifierConfig())
  }
  return null
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

function isSdkDefinition(def: AgentDefinition) {
  const sdkGraph = def.extra?.sdkGraph
  return !!sdkGraph && typeof sdkGraph === 'object'
    && (((sdkGraph as Record<string, unknown>).managedBy === 'SDK') || ((sdkGraph as Record<string, unknown>).source === 'SDK'))
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

function recordValue(value: unknown): Record<string, string | number | boolean> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return {}
  return value as Record<string, string | number | boolean>
}

function stringRecord(value: unknown): Record<string, string> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return {}
  const out: Record<string, string> = {}
  for (const [key, raw] of Object.entries(value)) out[key] = String(raw)
  return out
}

function schemaValue(value: unknown): StudioFieldSchema[] {
  return Array.isArray(value) ? value as StudioFieldSchema[] : []
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

function normalizeParams(value?: Record<string, string | number | boolean>) {
  return value || {}
}

export function kindColor(kind: CanvasNodeKind) {
  return studioNodeColor(kind)
}
