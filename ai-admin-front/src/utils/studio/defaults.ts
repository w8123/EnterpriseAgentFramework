import type { AgentForm } from '@/types/agent'
import { studioNodeCategory, studioNodeRetryable } from '@/utils/studioNodeRegistry'
import type {
  CanvasNodeKind,
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
  StudioRetryPolicy,
  ToolNodeConfig,
  UserInputNodeConfig,
  VariableAggregateNodeConfig,
} from '@/types/studio'
import { arrayValue, numberValue, stringValue } from './values'

export function defaultLlmConfig(base?: AgentForm): LlmNodeConfig {
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

export function defaultLlmMessages(systemPrompt: string, userPrompt: string): NonNullable<LlmNodeConfig['messages']> {
  return [
    { id: 'system', role: 'system', content: systemPrompt || '', templateEngine: 'mustache', enabled: true },
    { id: 'user', role: 'user', content: userPrompt || '{{ input }}', templateEngine: 'mustache', enabled: true },
  ]
}

export function defaultKnowledgeConfig(): KnowledgeNodeConfig {
  // Retrieval knobs stay unset so the node follows the knowledge base settings.
  return {
    knowledgeBaseCodes: [],
    query: 'input',
    evidencePolicy: 'REQUIRED',
  }
}

/** Only the knobs a node explicitly overrides; everything else comes from the knowledge base. */
export function knowledgeRetrievalOverrides(config: Partial<KnowledgeNodeConfig>): Partial<KnowledgeNodeConfig> {
  const out: Partial<KnowledgeNodeConfig> = {}
  if (typeof config.topK === 'number' && Number.isFinite(config.topK)) out.topK = Math.max(1, Math.min(20, config.topK))
  if (typeof config.similarityThreshold === 'number' && Number.isFinite(config.similarityThreshold)) {
    out.similarityThreshold = config.similarityThreshold
  }
  if (config.searchMode) out.searchMode = config.searchMode
  if (typeof config.rerankEnabled === 'boolean') out.rerankEnabled = config.rerankEnabled
  return out
}

export function defaultHttpConfig(): HttpNodeConfig {
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

export function defaultParameterConfig(): ParameterNodeConfig {
  return {
    mode: 'expression',
    inputExpression: 'input',
    systemPrompt: '',
    userPrompt: '',
    modelParams: {},
    fields: [{ name: 'value', type: 'string', required: false, source: 'lastOutput' }],
  }
}

export function defaultUserInputConfig(): UserInputNodeConfig {
  return {
    outputAlias: 'params',
    fields: [
      { name: 'question', type: 'string', required: true, description: '用户问题', source: 'input.message' },
    ],
  }
}

export function defaultInteractionConfig(): InteractionNodeConfig {
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

export function defaultPageActionConfig(): PageActionNodeConfig {
  return {
    actionKey: '',
    title: '页面动作',
    confirm: true,
    args: {},
    outputAlias: 'page_action_result',
    metadata: {},
  }
}

export function defaultAnswerConfig() {
  return {
    template: '{{ lastOutput }}',
  }
}

export function defaultCodeConfig() {
  return {
    language: 'expression' as const,
    code: '// Safe expression mode. Configure outputs below.',
    outputs: { result: 'lastOutput' },
  }
}

export function defaultClassifierConfig(): IntentClassifierNodeConfig {
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

export function defaultAggregateConfig(): VariableAggregateNodeConfig {
  return {
    mode: 'object' as const,
    items: [{ name: 'value', source: 'lastOutput' }],
    template: '',
  }
}

export function defaultApprovalConfig(): HumanApprovalNodeConfig {
  return {
    title: '人工确认',
    prompt: '{{ lastOutput }}',
    approvers: [],
    timeoutSeconds: 3600,
    defaultRoute: 'approved',
  }
}

export function defaultLoopConfig(): LoopNodeConfig {
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

export function normalizeLoopConfig(raw: Partial<LoopNodeConfig> | undefined): LoopNodeConfig {
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

export function defaultKnowledgeWriteConfig(): KnowledgeWriteNodeConfig {
  return {
    knowledgeBaseCode: '',
    titleExpression: 'const:工作流写入',
    contentExpression: 'lastOutput',
    tags: [],
    mode: 'draft',
  }
}

export function defaultDocumentExtractConfig(): DocumentExtractNodeConfig {
  return {
    sourceExpression: 'lastOutput',
    format: 'text',
    fields: [],
  }
}

export function defaultMcpConfig(): McpNodeConfig {
  return {
    serverRef: '',
    toolName: '',
    inputMapping: {},
  }
}

export function defaultConditionConfig(): ConditionNodeConfig {
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

export function defaultToolConfig(): ToolNodeConfig {
  return {
    ref: '',
    qualifiedName: null,
    projectCode: null,
    credentialRef: '',
    maxRequestTimeMs: 180000,
    inputMapping: {},
    mappingNote: '',
    argumentSource: 'inputMapping',
  }
}

export function defaultOutputAlias(kind: CanvasNodeKind) {
  if (kind === 'userInput') return 'params'
  if (kind === 'interaction') return 'interaction_output'
  if (kind === 'pageAction') return 'page_action_result'
  if (kind !== 'start' && kind !== 'end' && kind !== 'llm' && kind !== 'condition' && kind !== 'answer') return `${kind}_output`
  return ''
}

const CANVAS_NODE_KINDS = new Set<CanvasNodeKind>([
  'start', 'end', 'userInput', 'interaction', 'pageAction', 'llm', 'tool', 'knowledge',
  'condition', 'variable', 'template', 'parameter', 'http', 'answer', 'code', 'classifier',
  'aggregate', 'approval', 'loop', 'knowledgeWrite', 'documentExtract', 'mcp',
])

export function canvasKindValue(kind: string | undefined | null): CanvasNodeKind {
  const raw = String(kind || '').trim()
  if (!CANVAS_NODE_KINDS.has(raw as CanvasNodeKind)) {
    throw new Error(`Canvas node kind must use a canonical value: ${raw}`)
  }
  return raw as CanvasNodeKind
}

export function defaultRetryPolicy(kind: CanvasNodeKind): StudioRetryPolicy {
  const enabled = studioNodeRetryable(kind)
  return {
    enabled,
    maxAttempts: enabled ? 2 : 1,
    backoffMs: 800,
  }
}

export function defaultErrorPolicy(): StudioErrorPolicy {
  return {
    strategy: 'TERMINATE' as const,
  }
}

export function nodeCategory(kind: CanvasNodeKind) {
  return studioNodeCategory(kind)
}

export function llmMessagesValue(value: unknown, systemPrompt: string, userPrompt: string): NonNullable<LlmNodeConfig['messages']> {
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
