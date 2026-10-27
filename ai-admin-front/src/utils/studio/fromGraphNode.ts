import type { WorkflowCanvasSource, WorkflowGraphNode } from '@/types/agent'
import type {
  CanvasNodeKind,
  ConditionNodeConfig,
  HttpNodeConfig,
  InteractionNodeConfig,
  IntentClassifierNodeConfig,
  KnowledgeNodeConfig,
  LlmNodeConfig,
  PageActionNodeConfig,
  ParameterNodeConfig,
  ToolNodeConfig,
  UserInputNodeConfig,
} from '@/types/studio'
import {
  defaultErrorPolicy,
  defaultRetryPolicy,
  knowledgeRetrievalOverrides,
  llmMessagesValue,
  nodeCategory,
  normalizeLoopConfig,
} from './defaults'
import {
  aggregateItemsValue,
  aggregateModeValue,
  arrayValue,
  classifierClassesValue,
  classifierStrategyValue,
  configuredReferenceValue,
  documentFormatValue,
  firstNonEmptyText,
  firstRecordValue,
  interactionBindingValue,
  interactionComponentValue,
  interactionTypeValue,
  isSdkDefinition,
  jsonValueRecord,
  knowledgeWriteModeValue,
  numberValue,
  objectRecordValue,
  schemaValue,
  stringRecord,
  stringValue,
} from './values'
import { classifierOutputPorts, defaultPorts, interactionOutputPorts, userInputOutputPorts } from './ports'
import { toolNestedConfigExtras, toolRuntimeConfigExtras } from './toolConfig'

/** Restores Studio node configuration from GraphSpec, including legacy fields. */
export function graphConfigToNodeData(
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
    marketRef: config.marketRef && typeof config.marketRef === 'object' && !Array.isArray(config.marketRef)
      ? objectRecordValue(config.marketRef)
      : undefined,
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
  if (kind === 'tool') {
    const nested = objectRecordValue(config.toolConfig)
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
    const topLevelInputMapping = objectRecordValue(config.inputMapping)
    const topLevelArgs = objectRecordValue(config.args)
    // Legacy Tool definitions used non-empty args as a generic mapping alias.
    // Only a deliberately empty args object carries the zero-parameter Runtime
    // contract and must survive as args={} rather than the legacy fallback.
    const usesArgs = Object.prototype.hasOwnProperty.call(config, 'args')
      && Object.keys(topLevelArgs).length === 0
      && (!Object.prototype.hasOwnProperty.call(config, 'inputMapping') || Object.keys(topLevelInputMapping).length === 0)
    return {
      ...common,
      toolConfig: {
        assetReference: ref ? { ...ref } : undefined,
        ref: firstNonEmptyText(ref?.name, ref?.qualifiedName, configuredRef),
        qualifiedName: resolvedQualifiedName || null,
        projectCode: firstNonEmptyText(ref?.projectCode, config.projectCode, nested.projectCode) || null,
        ...(typeof config.httpApiAssetId === 'number' && Number.isInteger(config.httpApiAssetId)
          && config.httpApiAssetId > 0 ? { httpApiAssetId: config.httpApiAssetId } : {}),
        credentialRef: firstNonEmptyText(config.credentialRef, nested.credentialRef),
        maxRequestTimeMs: numberValue(config.maxRequestTimeMs, 180000),
        inputMapping: usesArgs
          ? topLevelArgs
          : firstRecordValue(config.inputMapping, config.args, nested.inputMapping, nested.args),
        mappingNote: stringValue(config.mappingNote),
        argumentSource: usesArgs ? 'args' : 'inputMapping',
        runtimeConfigExtras: toolRuntimeConfigExtras(config),
        nestedConfigExtras: toolNestedConfigExtras(nested),
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
    const nested = objectRecordValue(config.knowledgeConfig)
    const merged = { ...nested, ...config }
    delete merged.knowledgeConfig
    const rawEvidencePolicy = stringValue(merged.evidencePolicy).toUpperCase()
    return {
      ...common,
      knowledgeConfig: {
        knowledgeBaseCodes: arrayValue(merged.knowledgeBaseCodes),
        query: stringValue(merged.query) || 'input',
        ...knowledgeRetrievalOverrides({
          topK: merged.topK == null || merged.topK === '' ? undefined : numberValue(merged.topK, 5),
          similarityThreshold: merged.similarityThreshold == null ? undefined : numberValue(merged.similarityThreshold, 0.5),
          searchMode: stringValue(merged.searchMode) || undefined,
          rerankEnabled: typeof merged.rerankEnabled === 'boolean' ? merged.rerankEnabled : undefined,
        }),
        // Missing means a legacy graph. Keep its historical linear fallback semantics.
        evidencePolicy: rawEvidencePolicy === 'REQUIRED' ? 'REQUIRED' : 'OPTIONAL',
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
