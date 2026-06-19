import assert from 'node:assert/strict'
import type { AgentForm, AgentGraphSpec } from '@/types/agent'
import { definitionToCanvas } from './studio'

const base = {
  name: 'Test Workflow',
  keySlug: 'test-workflow',
  modelInstanceId: 'llm-1',
  systemPrompt: '',
} as AgentForm

function canvasSnapshot(nodes: unknown[]) {
  return definitionToCanvas({
    ...base,
    canvasJson: JSON.stringify({ version: 2, nodes, edges: [] }),
  })
}

function canvasSnapshotWithGraphSpec(nodes: unknown[], graphSpec: AgentGraphSpec) {
  return definitionToCanvas({
    ...base,
    graphSpec,
    canvasJson: JSON.stringify({ version: 2, nodes, edges: [] }),
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
assert.ok(classifierNode, 'classifier node should exist')
assert.equal(classifierNode.data.configVersion, 2)
assert.equal(classifierNode.data.classifierConfig?.strategy, 'HYBRID')
assert.equal(classifierNode.data.classifierConfig?.classes?.[0]?.id, 'search_intent')

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
assert.ok(pageActionNode, 'page action node should exist')
assert.equal(pageActionNode.data.configVersion, 2)
assert.equal(pageActionNode.data.pageActionConfig?.actionKey, 'orders.search')
assert.equal(pageActionNode.data.pageActionConfig?.projectCode, 'orders')

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
  code: 'workflow',
  name: 'Test Workflow',
  mode: 'WORKFLOW',
  entry: 'input',
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
assert.ok(hydratedClassifier, 'hydrated classifier should exist')
assert.equal(hydratedClassifier.position.x, 10)
assert.equal(hydratedClassifier.position.y, 20)
assert.equal(hydratedClassifier.data.label, 'Intent Router')
assert.equal(hydratedClassifier.data.classifierConfig?.strategy, 'HYBRID')
assert.deepEqual(
  hydratedClassifier.data.classifierConfig?.classes?.map((item) => item.id),
  ['query_intent', 'reset_intent'],
)

const hydratedPageAction = graphHydratedSnapshot.nodes.find((node) => node.id === 'search_action')
assert.ok(hydratedPageAction, 'hydrated page action should exist')
assert.equal(hydratedPageAction.position.x, 30)
assert.equal(hydratedPageAction.position.y, 40)
assert.equal(hydratedPageAction.data.label, '执行查询')
assert.equal(hydratedPageAction.data.pageActionConfig?.actionKey, 'search')
assert.equal(hydratedPageAction.data.pageActionConfig?.projectCode, 'orders')

console.log('studio canvas node data checks passed')
