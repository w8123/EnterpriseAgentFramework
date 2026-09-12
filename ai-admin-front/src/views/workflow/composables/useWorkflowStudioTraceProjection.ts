import { computed, type Ref } from 'vue'
import type { ChatResponse } from '@/types/chat'
import type { RunDetail, RunExecutionPathItem, RunSpan } from '@/types/runops'
import type { TraceNode } from '@/types/trace'
import type { WorkflowDebugRunResult, WorkflowNodeDebugResult } from '@/types/workflow'
import { debugStepStatus, stringifyDebugPayload, type WorkflowNodeTraceState } from './workflowStudioTrace'

export interface WorkflowStudioTraceProjectionSources {
  nodes: Readonly<Ref<readonly { id: string }[]>>
  debugRunResult: Readonly<Ref<WorkflowDebugRunResult | null>>
  nodeDebugResult: Readonly<Ref<WorkflowNodeDebugResult | null>>
  debugResult: Readonly<Ref<ChatResponse | null>>
  runOpsDetail: Readonly<Ref<RunDetail | null>>
  traceNodes: Readonly<Ref<TraceNode[]>>
}

/** 将调试结果与运行回放投影为画布状态；数据加载和会话生命周期由调用方负责。 */
export function useWorkflowStudioTraceProjection(sources: WorkflowStudioTraceProjectionSources) {
  const { nodes, debugRunResult, nodeDebugResult, debugResult, runOpsDetail, traceNodes } = sources

  const workflowExecutionPath = computed<RunExecutionPathItem[]>(() =>
    (runOpsDetail.value?.executionPath ?? []).filter((item) => {
      const spanType = (item.spanType || '').trim().toUpperCase()
      if (spanType === 'WORKFLOW' || spanType === 'WORKFLOW_NODE') return true
      if (item.fromNodeId || item.toNodeId) return true
      return Boolean(item.nodeId && !['SUPERVISOR', 'PLAN', 'REPLAN', 'WORKFLOW_TOOL'].includes(spanType))
    }),
  )

  const workflowExecutionNodeIds = computed(() => {
    const ids = new Set<string>()
    for (const step of debugRunResult.value?.steps || []) {
      ids.add(step.nodeId)
    }
    for (const item of workflowExecutionPath.value) {
      const nodeId = item.fromNodeId || item.nodeId
      if (nodeId) ids.add(nodeId)
      if (item.toNodeId) ids.add(item.toNodeId)
    }
    return ids
  })

  const workflowHitEdgeKeys = computed(() => {
    const keys = new Set<string>()
    for (const step of debugRunResult.value?.steps || []) {
      if (step.nextNodeId) {
        keys.add(edgeKey(step.nodeId, step.nextNodeId))
      }
    }
    for (const item of workflowExecutionPath.value) {
      if (item.fromNodeId && item.toNodeId) {
        keys.add(edgeKey(item.fromNodeId, item.toNodeId))
      }
    }
    const nodeSequence = workflowExecutionPath.value
      .map((item) => item.fromNodeId || item.nodeId || item.toNodeId || '')
      .filter((nodeId, index, values) => !!nodeId && (index === 0 || nodeId !== values[index - 1]))
    for (let index = 0; index < nodeSequence.length - 1; index += 1) {
      keys.add(edgeKey(nodeSequence[index], nodeSequence[index + 1]))
    }
    return keys
  })

  const workflowReplaySummary = computed(() => {
    if (!workflowExecutionPath.value.length) return []
    const waiting = workflowExecutionPath.value.filter((item) => workflowExecutionItemStatus(item) === 'waiting').length
    const errors = workflowExecutionPath.value.filter((item) => workflowExecutionItemStatus(item) === 'error').length
    return [
      { label: 'RunOps path', value: String(workflowExecutionPath.value.length) },
      { label: 'Waiting', value: String(waiting) },
      { label: 'Errors', value: String(errors) },
    ]
  })

  const nodeTraceList = computed(() =>
    Object.values(nodeTraceStates.value).sort((a, b) => {
      const ai = nodes.value.findIndex((node) => node.id === a.nodeId)
      const bi = nodes.value.findIndex((node) => node.id === b.nodeId)
      return (ai < 0 ? 9999 : ai) - (bi < 0 ? 9999 : bi)
    }),
  )

  const debugOpsItems = computed(() => {
    const metadata = debugResult.value?.metadata || {}
    return [
      { label: '发布版本', value: textValue(metadata.agentConfigVersion || metadata.workflowVersion) },
      { label: '运行时', value: textValue(metadata.runtimeType) },
      { label: '业务项目', value: textValue(metadata.projectCode) },
      { label: 'Workflow', value: textValue(metadata.workflowKeySlug || metadata.workflowId) },
      { label: '实例', value: textValue(metadata.instanceId) },
      { label: '追踪 ID', value: textValue(metadata.traceId) },
    ].filter((item) => item.value !== '-')
  })

  const nodeTraceStates = computed<Record<string, WorkflowNodeTraceState>>(() => {
    const states: Record<string, WorkflowNodeTraceState> = {}
    const debugSteps = debugRunResult.value?.steps || []
    if (debugSteps.length) {
      for (const step of debugSteps) {
        states[step.nodeId] = {
          nodeId: step.nodeId,
          status: debugStepStatus(step.status),
          elapsedMs: step.elapsedMs,
          input: stringifyDebugPayload(step.input),
          output: stringifyDebugPayload(step.output ?? step.statePatch),
          errorCode: step.errorCode,
          route: step.route,
          createdAt: step.startedAt,
        }
      }
      return states
    }
    const runSpans = runOpsDetail.value?.spans ?? []
    if (runSpans.length) {
      const orderedSpans = [...runSpans].sort((a, b) => dateMs(a.startedAt) - dateMs(b.startedAt))
      for (const item of orderedSpans) {
        const next = spanToNodeTraceState(item)
        if (!next) continue
        const previous = states[next.nodeId]
        states[next.nodeId] = preferNodeTraceState(previous, next)
      }
      for (const item of workflowExecutionPath.value) {
        const nodeId = (item.fromNodeId || item.nodeId || '').trim()
        if (!nodeId) continue
        const previous = states[nodeId]
        const next: WorkflowNodeTraceState = {
          nodeId,
          status: workflowExecutionItemStatus(item),
          route: item.route || item.condition,
          createdAt: item.startedAt,
        }
        states[nodeId] = preferNodeTraceState(previous, next)
      }
      return states
    }
    const ordered = [...traceNodes.value].sort((a, b) => dateMs(a.createdAt) - dateMs(b.createdAt))
    for (const item of ordered) {
      const nodeId = (item.nodeId || '').trim()
      if (!nodeId) continue
      const next: WorkflowNodeTraceState = {
        nodeId,
        status: item.success ? 'success' : 'error',
        elapsedMs: item.elapsedMs,
        input: item.argsJson,
        output: item.resultSummary,
        errorCode: item.errorCode,
        createdAt: item.createdAt,
      }
      const previous = states[nodeId]
      states[nodeId] = preferNodeTraceState(previous, next)
    }
    return states
  })

  function lastRouteForNode(nodeId: string) {
    if (nodeDebugResult.value?.nodeId === nodeId) {
      const route = nodeDebugResult.value.lastRoute || String(nodeDebugResult.value.outputState?.lastRoute || '')
      if (route) return route
    }
    const fromWorkflow = [...workflowExecutionPath.value].reverse().find(
      (item) => (item.fromNodeId || item.nodeId) === nodeId && item.route,
    )?.route
    if (fromWorkflow) return fromWorkflow
    const trace = nodeTraceStates.value[nodeId]
    if (trace?.route) return trace.route
    if (!trace?.output) return ''
    try {
      const parsed = JSON.parse(trace.output)
      return String(parsed.lastRoute || parsed.outputState?.lastRoute || '')
    } catch {
      const match = trace.output.match(/lastRoute[=:]\s*([A-Za-z0-9_-]+)/)
      return match?.[1] || ''
    }
  }

  return {
    workflowExecutionPath,
    workflowExecutionNodeIds,
    workflowHitEdgeKeys,
    workflowReplaySummary,
    nodeTraceStates,
    nodeTraceList,
    debugOpsItems,
    lastRouteForNode,
  }
}

function edgeKey(source?: string, target?: string) {
  return `${source || ''}->${target || ''}`
}

function textValue(value: unknown) {
  if (value === null || value === undefined || value === '') return '-'
  return String(value)
}

function dateMs(value?: string) {
  if (!value) return 0
  const ms = Date.parse(value)
  return Number.isFinite(ms) ? ms : 0
}

function stringMeta(metadata: Record<string, unknown>, key: string) {
  const value = metadata[key]
  return value === null || value === undefined ? '' : String(value)
}

function workflowExecutionItemStatus(item: RunExecutionPathItem): WorkflowNodeTraceState['status'] {
  return debugStepStatus(item.status || item.workflowStatus)
}

function preferNodeTraceState(previous: WorkflowNodeTraceState | undefined, next: WorkflowNodeTraceState) {
  if (!previous) return next
  if (previous.status === 'error' && next.status !== 'error') return previous
  if (next.status === 'running') return { ...previous, ...next }
  if (next.status === 'waiting') return { ...previous, ...next }
  if (previous.status === 'waiting' && next.status === 'success') return previous
  return next.createdAt && previous.createdAt && dateMs(previous.createdAt) > dateMs(next.createdAt)
    ? previous
    : { ...previous, ...next }
}

function spanToNodeTraceState(span: RunSpan): WorkflowNodeTraceState | null {
  const nodeId = (span.nodeId || '').trim()
  if (!nodeId) return null
  const metadata = span.metadata || {}
  const status = debugStepStatus(span.status)
  return {
    nodeId,
    status,
    elapsedMs: span.latencyMs,
    spanType: span.spanType,
    toolName: span.toolName,
    input: span.inputSummary,
    output: span.outputSummary,
    errorCode: span.errorCode,
    route: stringMeta(metadata, 'lastRoute') || stringMeta(metadata, 'route'),
    interactionId: stringMeta(metadata, 'interactionId'),
    createdAt: span.startedAt,
  }
}
