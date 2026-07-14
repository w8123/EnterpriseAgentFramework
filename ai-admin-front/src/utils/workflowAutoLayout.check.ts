import assert from 'node:assert/strict'
import type { CanvasEdge, CanvasNode, CanvasNodeKind } from '@/types/studio'
import {
  autoLayoutWorkflowNodes,
  WORKFLOW_LAYOUT_COLUMN_GAP,
  type WorkflowNodeMeasurement,
} from './workflowAutoLayout'

function canvasNode(id: string, kind: CanvasNodeKind = 'tool'): CanvasNode {
  return {
    id,
    type: kind,
    position: { x: 0, y: 0 },
    data: { label: id, kind, configVersion: 2 },
  }
}

function canvasEdge(
  id: string,
  source: string,
  target: string,
  sourceHandle?: string,
): CanvasEdge {
  return { id, source, target, sourceHandle, type: 'smoothstep' }
}

function positionsById(nodes: CanvasNode[]) {
  return Object.fromEntries(nodes.map((node) => [node.id, node.position]))
}

function horizontalGap(
  left: CanvasNode,
  right: CanvasNode,
  measurements: Record<string, WorkflowNodeMeasurement>,
) {
  return right.position.x - left.position.x - measurements[left.id].width
}

function rectanglesOverlap(
  left: CanvasNode,
  right: CanvasNode,
  measurements: Record<string, WorkflowNodeMeasurement>,
) {
  const leftSize = measurements[left.id]
  const rightSize = measurements[right.id]
  return left.position.x < right.position.x + rightSize.width
    && left.position.x + leftSize.width > right.position.x
    && left.position.y < right.position.y + rightSize.height
    && left.position.y + leftSize.height > right.position.y
}

const linearNodes = [
  canvasNode('start', 'start'),
  canvasNode('input', 'userInput'),
  canvasNode('answer', 'answer'),
  canvasNode('end', 'end'),
]
const linearEdges = [
  canvasEdge('start-input', 'start', 'input'),
  canvasEdge('input-answer', 'input', 'answer'),
  canvasEdge('answer-end', 'answer', 'end'),
]
const linearMeasurements: Record<string, WorkflowNodeMeasurement> = {
  start: { width: 240, height: 142, sourceHandleCenters: { __default__: 71 } },
  input: {
    width: 240,
    height: 196,
    sourceHandleCenters: { __default__: 98 },
    targetHandleCenters: { __default__: 98 },
  },
  answer: {
    width: 240,
    height: 224,
    sourceHandleCenters: { __default__: 112 },
    targetHandleCenters: { __default__: 112 },
  },
  end: { width: 240, height: 142, targetHandleCenters: { __default__: 71 } },
}
const linearLayout = await autoLayoutWorkflowNodes(linearNodes, linearEdges, {
  measurements: linearMeasurements,
})
const linearById = new Map(linearLayout.map((node) => [node.id, node]))
const linearHandleCenters = linearLayout.map(
  (node) => node.position.y + linearMeasurements[node.id].height / 2,
)
assert.ok(
  Math.max(...linearHandleCenters) - Math.min(...linearHandleCenters) <= 1,
  `linear handle centers must align: ${linearHandleCenters.join(', ')}`,
)
for (let index = 1; index < linearLayout.length; index += 1) {
  assert.equal(
    horizontalGap(linearLayout[index - 1], linearLayout[index], linearMeasurements),
    WORKFLOW_LAYOUT_COLUMN_GAP,
    'linear cards must use equal boundary gaps',
  )
}

const branchNodes = [
  canvasNode('start', 'start'),
  canvasNode('router', 'classifier'),
  canvasNode('yes-node'),
  canvasNode('no-node'),
  canvasNode('merge', 'aggregate'),
  canvasNode('end', 'end'),
]
const branchEdges = [
  canvasEdge('start-router', 'start', 'router'),
  canvasEdge('router-yes', 'router', 'yes-node', 'yes'),
  canvasEdge('router-no', 'router', 'no-node', 'no'),
  canvasEdge('yes-merge', 'yes-node', 'merge'),
  canvasEdge('no-merge', 'no-node', 'merge'),
  canvasEdge('merge-end', 'merge', 'end'),
]
const branchMeasurements: Record<string, WorkflowNodeMeasurement> = {
  start: { width: 240, height: 150 },
  router: {
    width: 300,
    height: 180,
    sourceHandleCenters: { yes: 52, no: 128 },
    targetHandleCenters: { __default__: 90 },
  },
  'yes-node': { width: 240, height: 150 },
  'no-node': { width: 240, height: 190 },
  merge: { width: 240, height: 160 },
  end: { width: 240, height: 150 },
}
const branchLayout = await autoLayoutWorkflowNodes(branchNodes, branchEdges, {
  measurements: branchMeasurements,
})
const branchById = new Map(branchLayout.map((node) => [node.id, node]))
assert.ok(branchById.get('yes-node')!.position.y < branchById.get('no-node')!.position.y)
for (let leftIndex = 0; leftIndex < branchLayout.length; leftIndex += 1) {
  for (let rightIndex = leftIndex + 1; rightIndex < branchLayout.length; rightIndex += 1) {
    assert.equal(
      rectanglesOverlap(branchLayout[leftIndex], branchLayout[rightIndex], branchMeasurements),
      false,
      `${branchLayout[leftIndex].id} and ${branchLayout[rightIndex].id} must not overlap`,
    )
  }
}
const branchCenters = ['yes-node', 'no-node'].map((id) => {
  const node = branchById.get(id)!
  return node.position.y + branchMeasurements[id].height / 2
})
const branchMidpoint = (Math.min(...branchCenters) + Math.max(...branchCenters)) / 2
for (const id of ['router', 'merge']) {
  const node = branchById.get(id)!
  const center = node.position.y + branchMeasurements[id].height / 2
  assert.ok(
    Math.abs(center - branchMidpoint) <= 12,
    `${id} must be centered between branches: center=${center}, midpoint=${branchMidpoint}`,
  )
}

const cycleNodes = [
  canvasNode('start', 'start'),
  canvasNode('a'),
  canvasNode('b'),
  canvasNode('end', 'end'),
]
const cycleEdges = [
  canvasEdge('start-a', 'start', 'a'),
  canvasEdge('a-b', 'a', 'b'),
  canvasEdge('b-a', 'b', 'a', 'retry'),
  canvasEdge('b-end', 'b', 'end'),
]
const cycleMeasurements = Object.fromEntries(
  cycleNodes.map((node) => [node.id, { width: 240, height: 150 }]),
)
const cycleLayout = await Promise.race([
  autoLayoutWorkflowNodes(cycleNodes, cycleEdges, { measurements: cycleMeasurements }),
  new Promise<never>((_, reject) => setTimeout(() => reject(new Error('cycle layout timed out')), 2000)),
])
const cycleById = new Map(cycleLayout.map((node) => [node.id, node]))
assert.ok(cycleById.get('start')!.position.x < cycleById.get('a')!.position.x)
assert.ok(cycleById.get('a')!.position.x < cycleById.get('b')!.position.x)
assert.ok(cycleById.get('b')!.position.x < cycleById.get('end')!.position.x)

const disconnectedNodes = [...linearNodes, canvasNode('orphan')]
const disconnectedMeasurements: Record<string, WorkflowNodeMeasurement> = {
  ...linearMeasurements,
  orphan: { width: 240, height: 156 },
}
const disconnectedLayout = await autoLayoutWorkflowNodes(disconnectedNodes, linearEdges, {
  measurements: disconnectedMeasurements,
})
const orphan = disconnectedLayout.find((node) => node.id === 'orphan')!
const mainBottom = Math.max(
  ...disconnectedLayout
    .filter((node) => node.id !== 'orphan')
    .map((node) => node.position.y + disconnectedMeasurements[node.id].height),
)
assert.ok(orphan.position.y > mainBottom, 'disconnected nodes must be packed below the main graph')

const secondLayout = await autoLayoutWorkflowNodes(linearLayout, linearEdges, {
  measurements: linearMeasurements,
})
assert.deepEqual(positionsById(secondLayout), positionsById(linearLayout), 'layout must be idempotent')

const shuffledLayout = await autoLayoutWorkflowNodes(
  [linearNodes[2], linearNodes[0], linearNodes[3], linearNodes[1]],
  [linearEdges[2], linearEdges[0], linearEdges[1]],
  { measurements: linearMeasurements },
)
assert.deepEqual(positionsById(shuffledLayout), positionsById(linearLayout), 'input order must not affect positions')

assert.ok(linearById.get('start') && linearById.get('end'))
console.log('workflow auto layout assertions passed')
