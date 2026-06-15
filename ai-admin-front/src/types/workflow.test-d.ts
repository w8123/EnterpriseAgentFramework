import type {
  WorkflowDefinition,
  WorkflowManagedBy,
  WorkflowPublishRequest,
  WorkflowReleaseValidationResult,
  WorkflowRuntimeType,
  WorkflowRuntimeValidationResult,
  WorkflowStatus,
  WorkflowStudioSaveRequest,
  WorkflowStudioState,
  WorkflowValidationItem,
} from './workflow'
import type { CanvasSnapshot } from './studio'
import {
  createWorkflowCanvasNode,
  workflowCanvasToSaveRequest,
  workflowStudioToCanvas,
} from '../utils/workflowStudio'

const studio: WorkflowStudioState = {
  workflowId: 'wf-1',
  keySlug: 'orders-page',
  name: 'Orders Page',
  description: null,
  graphSpecJson: '{"nodes":[]}',
  canvasJson: '{"nodes":[]}',
  workflowType: 'PAGE_ACTION',
  runtimeType: 'LANGGRAPH4J',
  status: 'DRAFT',
  managedBy: 'MANUAL',
  extraJson: null,
}

const saveRequest: WorkflowStudioSaveRequest = {
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
  publishedBy: 'alice',
}

const runtimeType: WorkflowRuntimeType = studio.runtimeType
const status: WorkflowStatus = studio.status
const managedBy: WorkflowManagedBy = studio.managedBy
const workflow: Pick<WorkflowDefinition, 'id' | 'runtimeType' | 'status' | 'managedBy'> = {
  id: studio.workflowId,
  runtimeType,
  status,
  managedBy,
}
const snapshot: CanvasSnapshot = workflowStudioToCanvas(studio)
const saveFromCanvas: WorkflowStudioSaveRequest = workflowCanvasToSaveRequest(studio, snapshot)
const node = createWorkflowCanvasNode('llm', { x: 160, y: 80 }, studio)
const nodeId: string = node.id

void saveRequest
void saveFromCanvas
void validation
void releaseValidation
void publish
void workflow
void nodeId
