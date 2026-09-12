import type {
  WorkflowWorkingCopy,
  WorkflowCreationChannel,
  WorkflowDefinitionAuthority,
  WorkflowExecutionEngine,
  WorkflowKind,
  WorkflowPublishRequest,
  WorkflowReleaseValidationResult,
  WorkflowRuntimeValidationResult,
  WorkflowStatus,
  SaveWorkflowWorkingCopyRequest,
  WorkflowWorkingCopyState,
  WorkflowValidationItem,
} from './workflow'
import type { CanvasSnapshot } from './studio'
import {
  createWorkflowCanvasNode,
  workflowCanvasToSaveRequest,
  workflowStudioToCanvas,
} from '../utils/workflowStudio'

const studio: WorkflowWorkingCopyState = {
  workflowId: 'wf-1',
  keySlug: 'orders-page',
  name: 'Orders Page',
  description: null,
  graphSpecJson: '{"nodes":[]}',
  canvasJson: '{"nodes":[]}',
  workflowKind: 'PAGE_ASSISTANT',
  executionEngine: 'GRAPH_SPEC',
  definitionAuthority: 'USER',
  creationChannel: 'STUDIO',
  status: 'DRAFT',
  extraJson: null,
}

const saveRequest: SaveWorkflowWorkingCopyRequest = {
  baseRevision: '2026-09-06T10:00:00',
  graphSpecJson: '{"nodes":[]}',
  canvasJson: '{"nodes":[]}',
  extraJson: '{"source":"studio"}',
}

const item: WorkflowValidationItem = {
  code: 'GRAPH_SPEC_MISSING',
  target: null,
  message: 'GraphSpec is required',
}

const validation: WorkflowRuntimeValidationResult = {
  valid: false,
  errors: [item],
}

const releaseValidation: WorkflowReleaseValidationResult = {
  valid: true,
  errors: [],
  warnings: [],
}

const publish: WorkflowPublishRequest = {
  version: 'v1.0.0',
  rolloutPercent: 100,
  note: 'first release',
  baseRevision: '2026-07-14T10:30:00',
}

const workflowKind: WorkflowKind = studio.workflowKind || 'GENERAL'
const executionEngine: WorkflowExecutionEngine = studio.executionEngine || 'GRAPH_SPEC'
const definitionAuthority: WorkflowDefinitionAuthority = studio.definitionAuthority || 'USER'
const creationChannel: WorkflowCreationChannel = studio.creationChannel || 'STUDIO'
const status: WorkflowStatus = studio.status
const workflow: Pick<WorkflowWorkingCopy, 'id' | 'workflowKind' | 'executionEngine' | 'status'> = {
  id: studio.workflowId,
  workflowKind,
  executionEngine,
  status,
}
const snapshot: CanvasSnapshot = workflowStudioToCanvas(studio)
const saveFromCanvas: SaveWorkflowWorkingCopyRequest = workflowCanvasToSaveRequest(studio, snapshot)
const node = createWorkflowCanvasNode('llm', { x: 160, y: 80 }, studio)
const nodeId: string = node.id

void saveRequest
void saveFromCanvas
void validation
void releaseValidation
void publish
void workflow
void nodeId
void workflowKind
void executionEngine
void definitionAuthority
void creationChannel
