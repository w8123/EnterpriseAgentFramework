import { ref } from 'vue'
import { describe, expect, it } from 'vitest'
import type { ChatResponse } from '@/types/chat'
import type { RunDetail, RunExecutionPathItem, RunSpan } from '@/types/runops'
import type { TraceNode } from '@/types/trace'
import type { WorkflowDebugRunResult, WorkflowNodeDebugResult } from '@/types/workflow'
import { useWorkflowStudioTraceProjection } from './useWorkflowStudioTraceProjection'

function sources() {
  return {
    nodes: ref([{ id: 'ask' }, { id: 'lookup' }, { id: 'finish' }]),
    debugRunResult: ref<WorkflowDebugRunResult | null>(null),
    nodeDebugResult: ref<WorkflowNodeDebugResult | null>(null),
    debugResult: ref<ChatResponse | null>(null),
    runOpsDetail: ref<RunDetail | null>(null),
    traceNodes: ref<TraceNode[]>([]),
  }
}

function replay(spans: RunSpan[], executionPath: RunExecutionPathItem[] = []): RunDetail {
  return {
    summary: { traceId: 'replay-1', runType: 'WORKFLOW', entryType: 'DEBUG', status: 'COMPLETED' },
    spans,
    executionPath,
    toolCalls: [],
    guardDecisions: [],
    repairHints: [],
  }
}

describe('Workflow Studio trace projection', () => {
  it('uses live debug steps for node state while keeping canvas order and structured output', () => {
    const input = sources()
    input.runOpsDetail.value = replay([{ id: 1, nodeId: 'ask', status: 'FAILED' }])
    input.debugRunResult.value = {
      runId: 'debug-1',
      status: 'SUSPENDED',
      success: true,
      steps: [
        { index: 0, nodeId: 'lookup', status: 'SUCCESS', nextNodeId: 'ask', output: { count: 2 } },
        { index: 1, nodeId: 'ask', status: 'WAITING', route: 'needs_input', statePatch: { lastRoute: 'needs_input' } },
      ],
    }
    input.debugResult.value = { answer: '', metadata: { runtimeType: 'LANGGRAPH4J', traceId: 'debug-1' } }

    const view = useWorkflowStudioTraceProjection(input)

    expect(view.nodeTraceStates.value.ask.status).toBe('waiting')
    expect(view.nodeTraceStates.value.lookup.output).toBe('{\n  "count": 2\n}')
    expect(view.lastRouteForNode('ask')).toBe('needs_input')
    expect(view.nodeTraceList.value.map(node => node.nodeId)).toEqual(['ask', 'lookup'])
    expect(view.workflowExecutionNodeIds.value).toEqual(new Set(['lookup', 'ask']))
    expect(view.workflowHitEdgeKeys.value.has('lookup->ask')).toBe(true)
    expect(view.debugOpsItems.value).toEqual([
      { label: '运行时', value: 'LANGGRAPH4J' },
      { label: '追踪 ID', value: 'debug-1' },
    ])
    expect(input.debugRunResult.value.steps.map(step => step.nodeId)).toEqual(['lookup', 'ask'])
  })

  it('shows suspended RunOps spans and execution paths as waiting, with failures kept visible', () => {
    const input = sources()
    input.runOpsDetail.value = replay([
      { id: 1, spanType: 'SUPERVISOR', status: 'RUNNING' },
      { id: 2, nodeId: 'ask', spanType: 'WORKFLOW_NODE', status: 'SUSPENDED', metadata: { interactionId: 'question-1' } },
      { id: 3, nodeId: 'lookup', spanType: 'WORKFLOW_NODE', status: 'FAIL', errorCode: 'UPSTREAM_FAILURE' },
    ], [
      { depth: 0, spanType: 'SUPERVISOR', status: 'RUNNING' },
      { depth: 1, spanType: 'WORKFLOW_NODE', nodeId: 'ask', status: 'WAITING_USER' },
      { depth: 1, spanType: 'WORKFLOW_NODE', nodeId: 'lookup', status: 'FAIL' },
    ])

    const view = useWorkflowStudioTraceProjection(input)

    expect(view.workflowExecutionPath.value).toHaveLength(2)
    expect(view.nodeTraceStates.value.ask).toMatchObject({ status: 'waiting', interactionId: 'question-1' })
    expect(view.nodeTraceStates.value.lookup).toMatchObject({ status: 'error', errorCode: 'UPSTREAM_FAILURE' })
    expect(view.workflowReplaySummary.value).toEqual([
      { label: 'RunOps path', value: '2' },
      { label: 'Waiting', value: '1' },
      { label: 'Errors', value: '1' },
    ])
  })

  it('uses the latest route when a replay revisits a node, and reacts to an updated replay', () => {
    const input = sources()
    input.runOpsDetail.value = replay([], [
      { depth: 1, spanType: 'WORKFLOW_NODE', fromNodeId: 'lookup', toNodeId: 'ask', route: 'retry' },
      { depth: 1, spanType: 'WORKFLOW_NODE', fromNodeId: 'lookup', toNodeId: 'finish', route: 'done' },
    ])
    const view = useWorkflowStudioTraceProjection(input)

    expect(view.lastRouteForNode('lookup')).toBe('done')
    expect(view.workflowHitEdgeKeys.value.has('lookup->ask')).toBe(true)
    expect(view.workflowHitEdgeKeys.value.has('lookup->finish')).toBe(true)

    input.runOpsDetail.value.executionPath!.push({
      depth: 1, spanType: 'WORKFLOW_NODE', fromNodeId: 'lookup', toNodeId: 'ask', route: 'recheck',
    })
    expect(view.lastRouteForNode('lookup')).toBe('recheck')
    expect(input.runOpsDetail.value.executionPath![0].route).toBe('retry')
  })

  it('falls back to legacy trace evidence and clears its projection when the caller clears the session', () => {
    const input = sources()
    input.traceNodes.value = [
      { id: 2, traceId: 'legacy-1', nodeId: 'lookup', toolName: 'find', success: true, resultSummary: 'lastRoute=done', retrievalCandidates: [], createdAt: '2026-09-05T10:01:00Z' },
      { id: 1, traceId: 'legacy-1', nodeId: 'ask', toolName: 'ask', success: false, errorCode: 'INVALID_INPUT', retrievalCandidates: [], createdAt: '2026-09-05T10:00:00Z' },
      { id: 3, traceId: 'legacy-1', toolName: 'supervisor', success: true, retrievalCandidates: [] },
    ]
    const view = useWorkflowStudioTraceProjection(input)

    expect(view.nodeTraceStates.value.ask).toMatchObject({ status: 'error', errorCode: 'INVALID_INPUT' })
    expect(view.lastRouteForNode('lookup')).toBe('done')
    expect(view.nodeTraceList.value.map(node => node.nodeId)).toEqual(['ask', 'lookup'])
    expect(input.traceNodes.value.map(node => node.id)).toEqual([2, 1, 3])

    input.traceNodes.value = []
    input.nodes.value = []
    expect(view.nodeTraceStates.value).toEqual({})
    expect(view.nodeTraceList.value).toEqual([])
    expect(view.lastRouteForNode('lookup')).toBe('')
  })
})
