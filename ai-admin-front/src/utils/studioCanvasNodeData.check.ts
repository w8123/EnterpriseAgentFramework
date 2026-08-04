import type { AgentForm, WorkflowGraphSpec } from '@/types/agent'
import type { CanvasSnapshot } from '@/types/studio'
import { canvasToDefinition, definitionToCanvas } from './studio'

function assertPresent<T>(value: T, message: string): asserts value is NonNullable<T> {
  if (value === null || value === undefined) {
    throw new Error(message)
  }
}

function assertEqual(actual: unknown, expected: unknown, message?: string) {
  if (actual !== expected) {
    throw new Error(message ?? `Expected ${String(expected)}, got ${String(actual)}`)
  }
}

function assertDeepEqual(actual: unknown, expected: unknown, message?: string) {
  const actualJson = JSON.stringify(actual)
  const expectedJson = JSON.stringify(expected)
  if (actualJson !== expectedJson) {
    throw new Error(message ?? `Expected ${expectedJson}, got ${actualJson}`)
  }
}

const base = {
  name: 'Test Workflow',
  keySlug: 'test-workflow',
  modelInstanceId: 'llm-1',
  systemPrompt: '',
} as AgentForm

function canvasSnapshot(nodes: unknown[]) {
  const definition = canvasToDefinition(base, {
    version: 2,
    nodes: nodes as CanvasSnapshot['nodes'],
    edges: [],
  })
  return definitionToCanvas(definition)
}

function canvasSnapshotWithGraphSpec(nodes: unknown[], graphSpec: WorkflowGraphSpec) {
  return definitionToCanvas({
    ...base,
    graphSpec,
    canvasJson: JSON.stringify({
      schemaVersion: 1,
      layoutVersion: 1,
      nodes: (nodes as CanvasSnapshot['nodes']).map((node) => ({
        id: node.id,
        position: node.position,
        collapsed: node.data.collapsed === true ? true : undefined,
      })),
      edges: [],
    }),
  })
}

const classifierSnapshot = canvasSnapshot([{
  id: 'classifier-1',
  type: 'classifier',
  position: { x: 0, y: 0 },
  data: {
    label: 'Intent Router',
    kind: 'classifier',
    classifierConfig: {
      inputExpression: 'input',
      strategy: 'HYBRID',
      classes: [{ id: 'search_intent', label: 'Search', keywords: ['查询'] }],
      defaultRoute: 'else',
      modelInstanceId: 'llm-1',
      confidenceThreshold: 0.8,
      llmPrompt: 'route intent',
    },
  },
}])

const classifierNode = classifierSnapshot.nodes.find((node) => node.id === 'classifier-1')
assertPresent(classifierNode, 'classifier node should exist')
assertEqual(classifierNode.data.configVersion, 2)
assertEqual(classifierNode.data.classifierConfig?.strategy, 'HYBRID')
assertEqual(classifierNode.data.classifierConfig?.classes?.[0]?.id, 'search_intent')

const pageActionSnapshot = canvasSnapshot([{
  id: 'page-action-1',
  type: 'pageAction',
  position: { x: 0, y: 0 },
  data: {
    label: 'Search Action',
    kind: 'pageAction',
    pageActionConfig: {
      actionKey: 'orders.search',
      projectCode: 'orders',
      pageKey: 'orders.list',
      routePattern: '/orders',
      title: 'Search',
      confirm: false,
      args: { keyword: '{{ input }}' },
      outputAlias: 'search_result',
      metadata: { source: 'ai-coding' },
    },
  },
}])

const pageActionNode = pageActionSnapshot.nodes.find((node) => node.id === 'page-action-1')
assertPresent(pageActionNode, 'page action node should exist')
assertEqual(pageActionNode.data.configVersion, 2)
assertEqual(pageActionNode.data.pageActionConfig?.actionKey, 'orders.search')
assertEqual(pageActionNode.data.pageActionConfig?.projectCode, 'orders')

const graphHydratedSnapshot = canvasSnapshotWithGraphSpec([
  {
    id: 'router',
    type: 'classifier',
    position: { x: 10, y: 20 },
    data: {
      label: 'Router From Canvas',
      kind: 'classifier',
    },
  },
  {
    id: 'search_action',
    type: 'pageAction',
    position: { x: 30, y: 40 },
    data: {
      label: 'Search From Canvas',
      kind: 'pageAction',
    },
  },
], {
  schemaVersion: 2,
  entryNodeId: 'router',
  exitNodeIds: ['search_action'],
  nodes: [
    {
      id: 'router',
      type: 'INTENT_CLASSIFIER',
      name: 'Intent Router',
      config: {
        inputExpression: 'input',
        strategy: 'HYBRID',
        classes: [
          { id: 'query_intent', label: '查询', keywords: ['查询', '搜索'] },
          { id: 'reset_intent', label: '重置', keywords: ['重置'] },
        ],
        defaultRoute: 'else',
      },
    },
    {
      id: 'search_action',
      type: 'PAGE_ACTION',
      name: '执行查询',
      config: {
        projectCode: 'orders',
        pageKey: 'orders.list',
        routePattern: '/orders',
        actionKey: 'search',
        title: '执行查询',
        outputAlias: 'search_result',
      },
    },
  ],
  edges: [],
})

const hydratedClassifier = graphHydratedSnapshot.nodes.find((node) => node.id === 'router')
assertPresent(hydratedClassifier, 'hydrated classifier should exist')
assertEqual(hydratedClassifier.position.x, 10)
assertEqual(hydratedClassifier.position.y, 20)
assertEqual(hydratedClassifier.data.label, 'Intent Router')
assertEqual(hydratedClassifier.data.classifierConfig?.strategy, 'HYBRID')
assertDeepEqual(
  hydratedClassifier.data.classifierConfig?.classes?.map((item) => item.id),
  ['query_intent', 'reset_intent'],
)

const hydratedPageAction = graphHydratedSnapshot.nodes.find((node) => node.id === 'search_action')
assertPresent(hydratedPageAction, 'hydrated page action should exist')
assertEqual(hydratedPageAction.position.x, 30)
assertEqual(hydratedPageAction.position.y, 40)
assertEqual(hydratedPageAction.data.label, '执行查询')
assertEqual(hydratedPageAction.data.pageActionConfig?.actionKey, 'search')
assertEqual(hydratedPageAction.data.pageActionConfig?.projectCode, 'orders')

const roundTripGraphSpec: WorkflowGraphSpec = {
  schemaVersion: 2,
  entryNodeId: 'answer',
  exitNodeIds: ['answer'],
  inputSchema: { type: 'object', required: ['question'] },
  stateSchema: { type: 'object', properties: { order: { type: 'object' } } },
  nodes: [{ id: 'answer', type: 'ANSWER', name: 'Answer', config: { answer: 'ok' } }],
  edges: [],
}

function assertTrue(value: unknown, message: string) {
  if (value !== true) throw new Error(message)
}
const roundTripBase = { ...base, graphSpec: roundTripGraphSpec }
const roundTripCanvas = definitionToCanvas(roundTripBase)
const roundTripDefinition = canvasToDefinition(roundTripBase, roundTripCanvas)
assertDeepEqual(roundTripDefinition.graphSpec?.inputSchema, roundTripGraphSpec.inputSchema)
assertDeepEqual(roundTripDefinition.graphSpec?.stateSchema, roundTripGraphSpec.stateSchema)
assertEqual(roundTripDefinition.graphSpec?.schemaVersion, 2)
assertEqual(roundTripDefinition.graphSpec?.entryNodeId, 'answer')
assertDeepEqual(roundTripDefinition.graphSpec?.exitNodeIds, ['answer'])
assertDeepEqual(
  roundTripDefinition.graphSpec?.edges.map((edge) => edge.priority),
  [],
  'START/END are virtual boundaries and must not be persisted as semantic edges',
)
const roundTripLayout = JSON.parse(roundTripDefinition.canvasJson || '{}') as Record<string, unknown>
assertEqual(roundTripLayout.schemaVersion, 1)
assertEqual(roundTripLayout.layoutVersion, 1)
assertTrue(
  (roundTripLayout.nodes as Array<Record<string, unknown>>).every((node) => !('data' in node) && !('type' in node)),
  'Canvas JSON must not duplicate semantic node configuration',
)
assertTrue(
  (roundTripLayout.edges as Array<Record<string, unknown>>).every((edge) => (
    !('source' in edge) && !('target' in edge) && !('condition' in edge)
  )),
  'Canvas JSON must not duplicate semantic edge topology',
)

const aliasGraphSpec: WorkflowGraphSpec = {
  schemaVersion: 2,
  entryNodeId: 'tool_alias',
  exitNodeIds: ['finish_answer'],
  nodes: [
    { id: 'first_node', type: 'ANSWER', config: { template: 'not the entry' } },
    {
      id: 'tool_alias',
      type: 'TOOL',
      config: {
        toolName: 'ordersLookup',
        qualifiedName: 'orders.lookup',
        ref: 'ordersLookupRef',
        args: {
          keyword: 'params.keyword',
          limit: 10,
          filters: { active: true },
        },
      },
    },
    {
      id: 'parameter_alias',
      type: 'PARAMETER_EXTRACT',
      config: {
        mode: 'LLM',
        modelInstanceId: 'llm-parameter',
        inputExpression: 'params.question',
        systemPrompt: 'Extract only declared fields.',
        userPrompt: 'Question: {{ params.question }}',
        options: { temperature: 0.1, responseFormat: { type: 'json_object' } },
        fields: [{ name: 'orderId', type: 'string', required: true }],
      },
    },
    {
      id: 'llm_alias',
      type: 'LLM',
      config: {
        modelInstanceId: 'llm-main',
        prompt: 'You are the order assistant.',
        options: { temperature: 0.2, responseFormat: { type: 'json_object' } },
      },
    },
    {
      id: 'condition_nested',
      type: 'IF_ELSE',
      config: {
        conditionConfig: {
          groups: [{
            id: 'vip',
            logic: 'AND',
            conditions: [{ left: 'lastOutput.level', operator: 'equals', right: 'vip' }],
          }],
          defaultRoute: 'fallback',
        },
      },
    },
    {
      id: 'classifier_override',
      type: 'INTENT_CLASSIFIER',
      config: {
        inputExpression: 'params.current',
        strategy: 'KEYWORD',
        classes: [{ id: 'current', label: 'Current', keywords: ['current'] }],
        defaultRoute: 'current-default',
        options: { temperature: 0.25, responseFormat: { type: 'json_object' } },
        classifierConfig: {
          inputExpression: 'params.stale',
          strategy: 'LLM',
          classes: [{ id: 'stale', label: 'Stale', keywords: ['stale'] }],
          defaultRoute: 'stale-default',
          modelParams: { temperature: 0.9 },
        },
      },
    },
    { id: 'finish_answer', type: 'ANSWER', config: { content: 'Completed: {{ lastOutput }}' } },
  ],
  edges: [
    { from: 'tool_alias', to: 'parameter_alias', condition: 'always' },
    { from: 'parameter_alias', to: 'llm_alias', condition: 'always' },
    { from: 'llm_alias', to: 'finish_answer', condition: 'always' },
  ],
}
const aliasBase = { ...base, graphSpec: aliasGraphSpec }
const aliasCanvas = definitionToCanvas(aliasBase)
assertDeepEqual(
  aliasCanvas.edges.filter((edge) => edge.source === 'start').map((edge) => edge.target),
  ['tool_alias'],
  'GraphSpec entryNodeId must be the visual START target',
)
assertDeepEqual(
  aliasCanvas.edges.filter((edge) => edge.target === 'end').map((edge) => edge.source),
  ['finish_answer'],
  'GraphSpec exitNodeIds must define the visual END sources',
)

const hydratedToolAlias = aliasCanvas.nodes.find((node) => node.id === 'tool_alias')
assertPresent(hydratedToolAlias, 'tool alias node should hydrate')
assertEqual(hydratedToolAlias.data.toolConfig?.ref, 'ordersLookupRef')
assertEqual(hydratedToolAlias.data.toolConfig?.qualifiedName, 'orders.lookup')
assertDeepEqual(hydratedToolAlias.data.toolConfig?.inputMapping, {
  keyword: 'params.keyword',
  limit: 10,
  filters: { active: true },
})

const hydratedParameterAlias = aliasCanvas.nodes.find((node) => node.id === 'parameter_alias')
assertPresent(hydratedParameterAlias, 'parameter alias node should hydrate')
assertEqual(hydratedParameterAlias.data.parameterConfig?.mode, 'llm')
assertEqual(hydratedParameterAlias.data.parameterConfig?.inputExpression, 'params.question')
assertEqual(hydratedParameterAlias.data.parameterConfig?.systemPrompt, 'Extract only declared fields.')
assertEqual(hydratedParameterAlias.data.parameterConfig?.userPrompt, 'Question: {{ params.question }}')
assertDeepEqual(hydratedParameterAlias.data.parameterConfig?.modelParams, {
  temperature: 0.1,
  responseFormat: { type: 'json_object' },
})

const hydratedLlmAlias = aliasCanvas.nodes.find((node) => node.id === 'llm_alias')
assertPresent(hydratedLlmAlias, 'LLM alias node should hydrate')
assertEqual(hydratedLlmAlias.data.llmConfig?.systemPrompt, 'You are the order assistant.')
assertDeepEqual(hydratedLlmAlias.data.llmConfig?.modelParams, {
  temperature: 0.2,
  responseFormat: { type: 'json_object' },
})

const hydratedAnswerAlias = aliasCanvas.nodes.find((node) => node.id === 'finish_answer')
assertPresent(hydratedAnswerAlias, 'answer alias node should hydrate')
assertEqual(hydratedAnswerAlias.data.answerConfig?.template, 'Completed: {{ lastOutput }}')

const hydratedNestedCondition = aliasCanvas.nodes.find((node) => node.id === 'condition_nested')
assertPresent(hydratedNestedCondition, 'nested condition node should hydrate')
assertEqual(hydratedNestedCondition.data.conditionConfig?.groups[0]?.id, 'vip')
assertEqual(hydratedNestedCondition.data.conditionConfig?.defaultRoute, 'fallback')

const hydratedClassifierOverride = aliasCanvas.nodes.find((node) => node.id === 'classifier_override')
assertPresent(hydratedClassifierOverride, 'classifier override node should hydrate')
assertEqual(hydratedClassifierOverride.data.classifierConfig?.inputExpression, 'params.current')
assertEqual(hydratedClassifierOverride.data.classifierConfig?.strategy, 'KEYWORD')
assertEqual(hydratedClassifierOverride.data.classifierConfig?.classes[0]?.id, 'current')
assertEqual(hydratedClassifierOverride.data.classifierConfig?.defaultRoute, 'current-default')
assertDeepEqual(hydratedClassifierOverride.data.classifierConfig?.modelParams, {
  temperature: 0.25,
  responseFormat: { type: 'json_object' },
})

const aliasDefinition = canvasToDefinition(aliasBase, aliasCanvas)
const aliasRoundTrip = aliasDefinition.graphSpec
assertPresent(aliasRoundTrip, 'alias GraphSpec should survive canvas serialization')
assertEqual(aliasRoundTrip.entryNodeId, 'tool_alias')
assertDeepEqual(aliasRoundTrip.exitNodeIds, ['finish_answer'])

const roundTripToolAlias = aliasRoundTrip.nodes.find((node) => node.id === 'tool_alias')
assertPresent(roundTripToolAlias, 'tool alias node should survive round trip')
assertEqual(roundTripToolAlias.ref?.qualifiedName, 'orders.lookup')
assertDeepEqual(roundTripToolAlias.config?.inputMapping, {
  keyword: 'params.keyword',
  limit: 10,
  filters: { active: true },
})

const roundTripParameterAlias = aliasRoundTrip.nodes.find((node) => node.id === 'parameter_alias')
assertPresent(roundTripParameterAlias, 'parameter alias node should survive round trip')
assertEqual(roundTripParameterAlias.config?.extractMode, 'llm')
assertEqual(roundTripParameterAlias.config?.inputExpression, 'params.question')
assertEqual(roundTripParameterAlias.config?.systemPrompt, 'Extract only declared fields.')
assertEqual(roundTripParameterAlias.config?.userPrompt, 'Question: {{ params.question }}')
assertDeepEqual(roundTripParameterAlias.config?.modelParams, {
  temperature: 0.1,
  responseFormat: { type: 'json_object' },
})

const roundTripLlmAlias = aliasRoundTrip.nodes.find((node) => node.id === 'llm_alias')
assertPresent(roundTripLlmAlias, 'LLM alias node should survive round trip')
assertEqual(roundTripLlmAlias.config?.systemPrompt, 'You are the order assistant.')
assertDeepEqual(roundTripLlmAlias.config?.modelParams, {
  temperature: 0.2,
  responseFormat: { type: 'json_object' },
})

const roundTripNestedCondition = aliasRoundTrip.nodes.find((node) => node.id === 'condition_nested')
assertPresent(roundTripNestedCondition, 'nested condition should survive round trip')
assertEqual((roundTripNestedCondition.config?.conditionGroups as Array<{ id?: string }>)[0]?.id, 'vip')
assertEqual(roundTripNestedCondition.config?.defaultRoute, 'fallback')

const roundTripClassifierOverride = aliasRoundTrip.nodes.find((node) => node.id === 'classifier_override')
assertPresent(roundTripClassifierOverride, 'classifier top-level override should survive round trip')
assertEqual(roundTripClassifierOverride.config?.inputExpression, 'params.current')
assertEqual(roundTripClassifierOverride.config?.strategy, 'KEYWORD')
assertEqual((roundTripClassifierOverride.config?.classes as Array<{ id?: string }>)[0]?.id, 'current')
assertEqual(roundTripClassifierOverride.config?.defaultRoute, 'current-default')
assertDeepEqual(roundTripClassifierOverride.config?.modelParams, {
  temperature: 0.25,
  responseFormat: { type: 'json_object' },
})

const roundTripAnswerAlias = aliasRoundTrip.nodes.find((node) => node.id === 'finish_answer')
assertPresent(roundTripAnswerAlias, 'answer alias node should survive round trip')
assertEqual(roundTripAnswerAlias.config?.template, 'Completed: {{ lastOutput }}')

console.log('studio canvas node data checks passed')
