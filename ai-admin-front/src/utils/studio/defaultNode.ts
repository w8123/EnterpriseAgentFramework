import type { AgentForm } from '@/types/agent'
import type { CanvasNode, CanvasNodeKind } from '@/types/studio'
import {
  canvasKindValue,
  defaultAggregateConfig,
  defaultAnswerConfig,
  defaultApprovalConfig,
  defaultClassifierConfig,
  defaultCodeConfig,
  defaultConditionConfig,
  defaultDocumentExtractConfig,
  defaultErrorPolicy,
  defaultHttpConfig,
  defaultInteractionConfig,
  defaultKnowledgeConfig,
  defaultKnowledgeWriteConfig,
  defaultLlmConfig,
  defaultLoopConfig,
  defaultMcpConfig,
  defaultOutputAlias,
  defaultPageActionConfig,
  defaultParameterConfig,
  defaultRetryPolicy,
  defaultToolConfig,
  defaultUserInputConfig,
  nodeCategory,
} from './defaults'
import { classifierOutputPorts, defaultPorts, interactionOutputPorts, userInputOutputPorts } from './ports'

export function createDefaultNodeData(kind: CanvasNodeKind, label: string, base?: AgentForm): CanvasNode['data'] {
  kind = canvasKindValue(kind)
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
  if (kind === 'tool') return { ...common, toolConfig: defaultToolConfig() }
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
