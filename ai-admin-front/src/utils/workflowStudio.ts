import type {
  AgentDefinition,
  AgentForm,
  AgentGraphSpec,
  AgentRuntimeType,
} from '@/types/agent'
import type {
  CanvasNode,
  CanvasNodeKind,
  CanvasSnapshot,
} from '@/types/studio'
import type {
  WorkflowStudioSaveRequest,
  WorkflowStudioState,
} from '@/types/workflow'
import {
  canvasToDefinition,
  createDefaultNodeData,
  definitionToCanvas,
} from '@/utils/studio'

const EMPTY_GRAPH_SPEC: AgentGraphSpec = {
  code: 'workflow',
  name: 'Workflow',
  mode: 'WORKFLOW',
  nodes: [],
  edges: [],
}

export function workflowStudioToCanvas(studio: WorkflowStudioState): CanvasSnapshot {
  const definition = workflowStudioToCompatAgentDefinition(studio)
  try {
    return definitionToCanvas(definition)
  } catch {
    return definitionToCanvas({
      ...definition,
      canvasJson: undefined,
    })
  }
}

/**
 * 构造 Workflow-native Executable Debug Session 草稿载荷。
 * 避免在 Workflow Studio 扩散 AgentDefinition 语义；后端 adapter 负责转成 Runtime 兼容 shell。
 */
export function buildWorkflowDebugDraftPayload(
  studio: WorkflowStudioState,
  snapshot: CanvasSnapshot,
  modelInstanceId?: string,
): Record<string, unknown> {
  const saveRequest = workflowCanvasToSaveRequest(studio, snapshot)
  return {
    workflowId: studio.workflowId,
    workflowKeySlug: studio.keySlug || studio.workflowId,
    workflowName: studio.name,
    workflowType: studio.workflowType || 'WORKFLOW',
    projectCode: studio.projectCode,
    runtimeType: studio.runtimeType || 'LANGGRAPH4J',
    modelInstanceId: modelInstanceId || studio.defaultModelInstanceId || undefined,
    graphSpecJson: saveRequest.graphSpecJson,
    canvasJson: saveRequest.canvasJson,
    agentMode: 'WORKFLOW',
  }
}

export function workflowCanvasToSaveRequest(
  studio: WorkflowStudioState,
  snapshot: CanvasSnapshot,
): WorkflowStudioSaveRequest {
  const draft = canvasToDefinition(workflowStudioToAgentForm(studio), snapshot)
  return {
    graphSpecJson: JSON.stringify(draft.graphSpec || parseGraphSpec(studio.graphSpecJson, studio)),
    canvasJson: draft.canvasJson || JSON.stringify(snapshot),
    extraJson: studio.extraJson || null,
  }
}

export function createWorkflowCanvasNode(
  kind: CanvasNodeKind,
  position: { x: number; y: number },
  studio: WorkflowStudioState,
): CanvasNode {
  const id = `${kind}_${Date.now()}`
  return {
    id,
    type: kind,
    position,
    data: createDefaultNodeData(kind, kind, workflowStudioToAgentForm(studio)),
  }
}

/**
 * 将 WorkflowStudioState 转为旧 AgentDefinition 兼容壳，仅供 canvasToDefinition / definitionToCanvas 复用。
 * Workflow 主对象是 WorkflowDefinition + GraphSpec，不是 AgentDefinition。
 */
function workflowStudioToCompatAgentDefinition(studio: WorkflowStudioState): AgentDefinition {
  const form = workflowStudioToAgentForm(studio)
  return {
    ...form,
    id: studio.workflowId,
    keySlug: form.keySlug || studio.workflowId || 'workflow',
    canvasJson: studio.canvasJson || undefined,
    createdAt: '',
    updatedAt: '',
  }
}

function workflowStudioToAgentForm(studio: WorkflowStudioState): AgentForm {
  const graphSpec = parseGraphSpec(studio.graphSpecJson, studio)
  return {
    keySlug: studio.keySlug || studio.workflowId || 'workflow',
    name: studio.name || graphSpec.name || 'Workflow',
    description: studio.description || '',
    agentMode: 'WORKFLOW',
    projectId: null,
    projectCode: null,
    visibility: 'PROJECT',
    allowedRoles: [],
    intentType: studio.workflowType || 'WORKFLOW',
    systemPrompt: '',
    tools: [],
    skills: [],
    modelInstanceId: '',
    runtimeType: normalizeRuntimeType(studio.runtimeType),
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

function parseGraphSpec(graphSpecJson: string | null | undefined, studio: WorkflowStudioState): AgentGraphSpec {
  if (!graphSpecJson?.trim()) {
    return {
      ...EMPTY_GRAPH_SPEC,
      code: studio.keySlug || studio.workflowId || EMPTY_GRAPH_SPEC.code,
      name: studio.name || EMPTY_GRAPH_SPEC.name,
      runtimeHint: normalizeRuntimeType(studio.runtimeType),
    }
  }
  try {
    const parsed = JSON.parse(graphSpecJson) as Partial<AgentGraphSpec>
    return {
      ...EMPTY_GRAPH_SPEC,
      ...parsed,
      code: parsed.code || studio.keySlug || studio.workflowId || EMPTY_GRAPH_SPEC.code,
      name: parsed.name || studio.name || EMPTY_GRAPH_SPEC.name,
      mode: parsed.mode || 'WORKFLOW',
      runtimeHint: normalizeRuntimeType(studio.runtimeType),
      nodes: Array.isArray(parsed.nodes) ? parsed.nodes : [],
      edges: Array.isArray(parsed.edges) ? parsed.edges : [],
    }
  } catch {
    return {
      ...EMPTY_GRAPH_SPEC,
      code: studio.keySlug || studio.workflowId || EMPTY_GRAPH_SPEC.code,
      name: studio.name || EMPTY_GRAPH_SPEC.name,
      runtimeHint: normalizeRuntimeType(studio.runtimeType),
    }
  }
}

function normalizeRuntimeType(runtimeType?: string | null): AgentRuntimeType {
  return runtimeType === 'AGENTSCOPE'
    || runtimeType === 'OPENAI_AGENTS'
    || runtimeType === 'CURSOR_CODE_AGENT'
    ? runtimeType
    : 'LANGGRAPH4J'
}
