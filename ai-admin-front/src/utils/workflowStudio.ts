import type {
  AgentForm,
  AgentRuntimeType,
  WorkflowCanvasSource,
  WorkflowGraphSpec,
} from '@/types/agent'
import type {
  CanvasNode,
  CanvasNodeKind,
  CanvasSnapshot,
} from '@/types/studio'
import type {
  SaveWorkflowWorkingCopyRequest,
  WorkflowWorkingCopyState,
} from '@/types/workflow'
import {
  canvasToDefinition,
  createDefaultNodeData,
  definitionToCanvas,
} from '@/utils/studio'

const EMPTY_GRAPH_SPEC: WorkflowGraphSpec = {
  schemaVersion: 2,
  nodes: [],
  edges: [],
  entryNodeId: '',
  exitNodeIds: [],
}

const GRAPH_SPEC_FIELDS = new Set([
  'schemaVersion', 'inputSchema', 'stateSchema', 'nodes', 'edges', 'entryNodeId', 'exitNodeIds',
])
const GRAPH_NODE_FIELDS = new Set([
  'id', 'type', 'name', 'description', 'ref', 'inputs', 'outputs',
  'inputSchema', 'outputSchema', 'retry', 'errorPolicy', 'config',
])
const GRAPH_EDGE_FIELDS = new Set([
  'id', 'from', 'to', 'condition', 'sourceHandle', 'targetHandle', 'priority',
])
const GRAPH_NODE_TYPES = new Set([
  'LLM', 'USER_INPUT', 'INTERACTION', 'PAGE_ACTION', 'TOOL',
  'IF_ELSE', 'VARIABLE_ASSIGN', 'TEMPLATE', 'ANSWER', 'CODE', 'INTENT_CLASSIFIER',
  'VARIABLE_AGGREGATOR', 'HUMAN_APPROVAL', 'LOOP', 'KNOWLEDGE_WRITE',
  'DOCUMENT_EXTRACT', 'MCP_CALL', 'PARAMETER_EXTRACT', 'HTTP_REQUEST',
  'KNOWLEDGE_RETRIEVAL',
])

const CANVAS_RUNTIME_BY_EXECUTION_ENGINE: Record<string, AgentRuntimeType> = {
  GRAPH_SPEC: 'LANGGRAPH4J',
}

/** Workflow Studio 画布互操作所需的最小定义壳（非 Agent 管理语义） */
type WorkflowStudioCanvasSource = WorkflowCanvasSource & AgentForm & {
  id: string
  keySlug: string
  createdAt: string
  updatedAt: string
}

export function workflowStudioToCanvas(studio: WorkflowWorkingCopyState): CanvasSnapshot {
  const definition = workflowStudioToCanvasSource(studio)
  return definitionToCanvas(definition)
}

/**
 * 构造 Workflow-native Executable Debug Session 工作副本载荷。
 */
export function buildWorkflowDebugWorkingCopyPayload(
  studio: WorkflowWorkingCopyState,
  snapshot: CanvasSnapshot,
  modelInstanceId?: string,
): Record<string, unknown> {
  const saveRequest = workflowCanvasToSaveRequest(studio, snapshot)
  return {
    workflowId: studio.workflowId,
    workflowKeySlug: studio.keySlug || studio.workflowId,
    workflowName: studio.name,
    workflowKind: studio.workflowKind || 'GENERAL',
    projectCode: studio.projectCode,
    executionEngine: studio.executionEngine || 'GRAPH_SPEC',
    modelInstanceId: modelInstanceId || studio.defaultModelInstanceId || undefined,
    graphSpecJson: saveRequest.graphSpecJson,
    canvasJson: saveRequest.canvasJson,
    agentMode: 'WORKFLOW',
  }
}

export function workflowCanvasToSaveRequest(
  studio: WorkflowWorkingCopyState,
  snapshot: CanvasSnapshot,
): SaveWorkflowWorkingCopyRequest {
  const draft = canvasToDefinition(workflowStudioToCanvasForm(studio), snapshot)
  return {
    graphSpecJson: JSON.stringify(draft.graphSpec || parseWorkflowGraphSpec(studio.graphSpecJson)),
    canvasJson: draft.canvasJson || JSON.stringify(snapshot),
    extraJson: studio.extraJson || null,
    baseRevision: studio.revision || studio.updatedAt || '',
  }
}

export function createWorkflowCanvasNode(
  kind: CanvasNodeKind,
  position: { x: number; y: number },
  studio: WorkflowWorkingCopyState,
): CanvasNode {
  const id = `${kind}_${Date.now()}`
  return {
    id,
    type: kind,
    position,
    data: createDefaultNodeData(kind, kind, workflowStudioToCanvasForm(studio)),
  }
}

function workflowStudioToCanvasSource(studio: WorkflowWorkingCopyState): WorkflowStudioCanvasSource {
  const form = workflowStudioToCanvasForm(studio)
  return {
    ...form,
    id: studio.workflowId,
    keySlug: form.keySlug || studio.workflowId || 'workflow',
    canvasJson: studio.canvasJson || undefined,
    createdAt: '',
    updatedAt: '',
  }
}

function workflowStudioToCanvasForm(studio: WorkflowWorkingCopyState): AgentForm {
  const graphSpec = parseWorkflowGraphSpec(studio.graphSpecJson)
  return {
    keySlug: studio.keySlug || studio.workflowId || 'workflow',
    name: studio.name || 'Workflow',
    description: studio.description || '',
    agentMode: 'WORKFLOW',
    projectId: null,
    projectCode: studio.projectCode || null,
    visibility: 'PROJECT',
    allowedRoles: [],
    intentType: studio.workflowKind || 'GENERAL',
    systemPrompt: '',
    tools: [],
    modelInstanceId: studio.defaultModelInstanceId || '',
    runtimeType: toCanvasAgentRuntimeType(studio.executionEngine),
    runtimePlacement: 'CENTRAL',
    runtimeConfig: {},
    defaultResourceConfig: {},
    graphSpec,
    maxSteps: 20,
    enabled: studio.status !== 'ARCHIVED',
    type: 'single',
    pipelineAgentIds: [],
    knowledgeBaseGroupId: '',
    promptTemplateId: '',
    outputSchemaType: '',
    triggerMode: 'MANUAL',
    useMultiAgentModel: false,
    extra: {},
    canvasJson: studio.canvasJson || undefined,
    allowIrreversible: false,
  }
}

export function parseWorkflowGraphSpec(graphSpecJson: string | null | undefined): WorkflowGraphSpec {
  if (!graphSpecJson?.trim()) {
    return {
      ...EMPTY_GRAPH_SPEC,
      entryNodeId: '',
      exitNodeIds: [],
    }
  }
  const parsed = JSON.parse(graphSpecJson) as Partial<WorkflowGraphSpec> & Record<string, unknown>
  for (const removed of ['entry', 'finish', 'code', 'name', 'mode', 'runtimeHint', 'layout']) {
    if (removed in parsed) throw new Error(`GraphSpec field has been removed: ${removed}`)
  }
  assertOnlyFields(parsed, GRAPH_SPEC_FIELDS, 'GraphSpec')
  if (parsed.schemaVersion !== 2) throw new Error('GraphSpec schemaVersion must be 2')
  if (!Array.isArray(parsed.nodes)) throw new Error('GraphSpec nodes must be an array')
  if (!Array.isArray(parsed.edges)) throw new Error('GraphSpec edges must be an array')
  if (typeof parsed.entryNodeId !== 'string') throw new Error('GraphSpec entryNodeId must be a string')
  if (!Array.isArray(parsed.exitNodeIds) || parsed.exitNodeIds.some((id) => typeof id !== 'string')) {
    throw new Error('GraphSpec exitNodeIds must contain only strings')
  }
  for (const node of parsed.nodes) {
    if (!node || typeof node !== 'object' || Array.isArray(node)) {
      throw new Error('GraphSpec nodes must contain only objects')
    }
    const record = node as unknown as Record<string, unknown>
    assertOnlyFields(record, GRAPH_NODE_FIELDS, 'GraphSpec node')
    if (typeof record.type !== 'string' || !GRAPH_NODE_TYPES.has(record.type)) {
      throw new Error(`GraphSpec node.type must use a canonical value: ${String(record.type)}`)
    }
    const ref = record.ref
    if (record.type === 'TOOL' && ref && typeof ref === 'object' && !Array.isArray(ref)) {
      const refRecord = ref as Record<string, unknown>
      if (refRecord.kind != null && refRecord.kind !== 'TOOL') {
        throw new Error('GraphSpec TOOL node ref.kind must be TOOL')
      }
    }
    const config = record.config
    if (config && typeof config === 'object' && !Array.isArray(config)) {
      for (const removed of ['ui', 'collapsed', 'category']) {
        if (removed in config) throw new Error(`GraphSpec node.config.${removed} has been removed`)
      }
    }
  }
  for (const edge of parsed.edges) {
    if (!edge || typeof edge !== 'object' || Array.isArray(edge)) {
      throw new Error('GraphSpec edges must contain only objects')
    }
    assertOnlyFields(edge as unknown as Record<string, unknown>, GRAPH_EDGE_FIELDS, 'GraphSpec edge')
  }
  return {
    schemaVersion: 2,
    inputSchema: parsed.inputSchema,
    stateSchema: parsed.stateSchema,
    nodes: parsed.nodes,
    edges: parsed.edges,
    entryNodeId: parsed.entryNodeId,
    exitNodeIds: parsed.exitNodeIds,
  }
}

function assertOnlyFields(value: Record<string, unknown>, allowed: Set<string>, label: string): void {
  for (const field of Object.keys(value)) {
    if (!allowed.has(field)) throw new Error(`${label} contains unsupported field: ${field}`)
  }
}

function toCanvasAgentRuntimeType(executionEngine?: string | null): AgentRuntimeType {
  return CANVAS_RUNTIME_BY_EXECUTION_ENGINE[executionEngine || 'GRAPH_SPEC'] || 'LANGGRAPH4J'
}
