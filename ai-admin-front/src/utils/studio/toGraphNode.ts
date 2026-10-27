import type { AgentForm, WorkflowGraphNode } from '@/types/agent'
import type { CanvasNode } from '@/types/studio'
import {
  canvasKindValue,
  defaultAggregateConfig,
  defaultApprovalConfig,
  defaultClassifierConfig,
  defaultCodeConfig,
  defaultConditionConfig,
  defaultDocumentExtractConfig,
  defaultHttpConfig,
  defaultInteractionConfig,
  defaultKnowledgeConfig,
  defaultKnowledgeWriteConfig,
  defaultLlmConfig,
  defaultLlmMessages,
  defaultLoopConfig,
  defaultMcpConfig,
  defaultPageActionConfig,
  defaultParameterConfig,
  defaultToolConfig,
  defaultUserInputConfig,
  knowledgeRetrievalOverrides,
  normalizeLoopConfig,
} from './defaults'
import { normalizeParams } from './values'
import {
  defaultPorts,
  dynamicOutputPorts,
  interactionFieldsForGraph,
  interactionOutputPorts,
  userInputOutputPorts,
} from './ports'
import { graphToolConfig } from './toolConfig'

/** Converts an authored Studio node into its GraphSpec execution contract. */
export function canvasNodeToGraphNode(node: CanvasNode, base: AgentForm): WorkflowGraphNode {
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
  if (canvasKindValue(node.data.kind) === 'tool') {
    const tool = node.data.toolConfig || defaultToolConfig()
    const { inputMapping: _canvasInputMapping, ...toolCommon } = common
    const runtimeInput = tool.inputMapping || {}
    return {
      id: node.id,
      type: 'TOOL',
      name: node.data.label,
      ...graphNodeChrome(node),
      ref: {
        ...tool.assetReference,
        kind: 'TOOL',
        name: tool.ref,
        qualifiedName: tool.qualifiedName || tool.ref,
        projectCode: tool.projectCode,
      },
      config: {
        ...tool.runtimeConfigExtras,
        ...toolCommon,
        ...(tool.httpApiAssetId ? { httpApiAssetId: tool.httpApiAssetId } : {}),
        ...(tool.argumentSource === 'args'
          ? { args: runtimeInput }
          : { inputMapping: runtimeInput }),
        mappingNote: tool.mappingNote,
        maxRequestTimeMs: tool.maxRequestTimeMs || 180000,
        credentialRef: tool.credentialRef,
        toolConfig: graphToolConfig(tool),
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
  const evidencePolicy = knowledge.evidencePolicy === 'REQUIRED' ? 'REQUIRED' : 'OPTIONAL'
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
      ...knowledgeRetrievalOverrides(knowledge),
      evidencePolicy,
      knowledgeConfig: { ...knowledge, evidencePolicy },
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
    marketRef: node.data.marketRef,
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
