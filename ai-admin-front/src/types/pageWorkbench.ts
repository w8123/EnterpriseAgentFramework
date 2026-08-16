export type PageSourceType = 'AI_SCAN' | 'MANUAL' | 'SDK'
export type PageLifecycleStatus = 'ACTIVE' | 'ARCHIVED'
export type PageDetailCapabilityWorkspace =
  | 'resources'
  | 'actions'
  | 'goals'
  | 'implementation'
  | 'diagnostics'
  | 'debug'
export type PageResourceType =
  | 'ROUTE'
  | 'COMPONENT'
  | 'API'
  | 'CONFIG'
  | 'PERMISSION'
  | 'STORE'
  | 'STYLE'
  | 'TEST'
export type ResourceAccessMode = 'READ_ONLY' | 'READ_WRITE'

export interface PageResourceInput {
  resourceType: PageResourceType
  resourceKey: string
  displayName?: string
  location?: string
  httpMethod?: string
  accessMode?: ResourceAccessMode
  metadata?: Record<string, unknown>
}

export interface PageActionInput {
  actionKey: string
  title: string
  description?: string
  actionType?: string
  riskLevel?: 'READ' | 'WRITE' | 'PAGE_ACTION' | 'IRREVERSIBLE'
  confirmRequired?: boolean
  permissionKey?: string
  inputSchema?: Record<string, unknown>
  outputSchema?: Record<string, unknown>
  sampleArgs?: Record<string, unknown>
  allowedAgentIds?: string[]
  implementationRef?: string
  metadata?: Record<string, unknown>
}

export interface ManualProjectPageRequest {
  projectId: number
  pageKey: string
  moduleKey?: string
  moduleName?: string
  name: string
  description?: string
  routePattern?: string
  businessPageUrl?: string
  componentPath?: string
  resources?: PageResourceInput[]
  actions?: PageActionInput[]
}

export interface ProjectPageResource {
  id: number
  resourceType: PageResourceType
  resourceKey: string
  displayName?: string
  location?: string
  httpMethod?: string
  accessMode: ResourceAccessMode
  metadata: Record<string, unknown>
}

export interface ProjectPageAction {
  id: number
  pageId: number
  projectId: number
  projectCode: string
  pageKey: string
  actionKey: string
  title: string
  description?: string
  actionType: string
  riskLevel: string
  confirmRequired: boolean
  permissionKey?: string
  inputSchema: Record<string, unknown>
  outputSchema: Record<string, unknown>
  sampleArgs: Record<string, unknown>
  allowedAgentIds: string[]
  implementationRef?: string
  sourceType: PageSourceType
  status: string
  metadata: Record<string, unknown>
  lastVerifiedAt?: string
}

export interface ProjectPage {
  id: number
  projectId: number
  projectCode: string
  pageKey: string
  moduleKey?: string
  moduleName?: string
  name: string
  description?: string
  routePattern?: string
  businessPageUrl?: string
  componentPath?: string
  sourceType: PageSourceType
  lifecycleStatus: PageLifecycleStatus
  lastDiscoveredAt?: string
  lastVerifiedAt?: string
  resources: ProjectPageResource[]
  actions: ProjectPageAction[]
}

export interface PageMapSummary {
  scanned: boolean
  repositoryBranch?: string
  repositoryRevision?: string
  scannedAt?: string
  taskId?: string
  taskStatus?: AiCodingExecutionStatus
}

export type AnalysisFindingStatus = 'UNREAD' | 'KEPT' | 'IGNORED'

export interface PageAnalysisFinding {
  id: number
  findingKey: string
  pageId: number
  pageKey: string
  sourceTaskId: string
  category?: string
  title: string
  confirmedFact: string
  technicalInference?: string
  openQuestion?: string
  useCase?: string
  businessConfirmStatus: string
  technicalFeasibility: string
  operationRisk: string
  informationCompleteness: string
  readScope?: string
  writeScope?: string
  implementationReference?: string
  acceptanceCriteria?: string
  relatedPages: string[]
  evidence: unknown
  codeReferences: unknown
  status: AnalysisFindingStatus
  reviewedBy?: string
  reviewedAt?: string
  createdAt: string
  updatedAt: string
}

export interface PageImplementationGoalDraft {
  manualGoal: string
  findingIds: number[]
}

export interface PageImplementationGoalItem {
  source: 'MANUAL' | 'AI_FINDING' | 'TASK'
  content: string
  findingId?: number
}

export interface PageImplementationGoalSelection {
  schema: 'reachai.page-implementation-goal-selection.v1'
  manualGoal: string
  findingIds: number[]
  items: PageImplementationGoalItem[]
}

export interface PublishedPageWorkflow {
  pageKey: string
  workflowId: string
  workflowKeySlug: string
  workflowName: string
  workflowDescription?: string
  workflowStatus: string
  workflowVersionId: number
  workflowVersion: string
  publishedBy?: string
  publishedAt?: string
  agentId: string
  agentKeySlug: string
  agentName: string
  agentConfigVersionId: number
  agentConfigVersion: number
  modelInstanceId?: string
  toolName: string
  riskLevel: string
  permissionKey?: string
  recentCallCount: number
  successRate?: number
  latestCallAt?: string
  latestCallStatus?: string
  latestTraceId?: string
}

export type PageIntegrationReadinessStatus = 'PASS' | 'PENDING' | 'FAIL' | 'WARN' | 'NOT_REQUIRED'

export interface PageIntegrationReadinessItem {
  key: string
  label: string
  status: PageIntegrationReadinessStatus
  message: string
  evidence: Record<string, unknown>
}

export interface PageIntegrationReadiness {
  schema: 'reachai.page-workbench.page-integration-readiness.v1'
  projectCode: string
  pageId: number
  pageKey: string
  status: 'PASS' | 'PENDING' | 'FAIL'
  message: string
  items: PageIntegrationReadinessItem[]
  checkedAt: string
}

export interface WorkflowEngineeringDraftResult {
  schema: 'reachai.page-workbench.workflow-engineering-draft.v1'
  taskId: string
  pageKey: string
  summary: string
  selectedActionKeys: string[]
  referencedFiles: string[]
  acceptanceCriteria: string[]
  replaceWorkflowId?: string | null
  remainingQuestions: string[]
  workflow: {
    id: string
    keySlug: string
    name: string
    status: string
    updatedAt?: string
  }
  validation: {
    valid: boolean
    errors: Array<{
      code: string
      level: string
      nodeId?: string
      message: string
    }>
    warnings: Array<{
      code: string
      level: string
      nodeId?: string
      message: string
    }>
  }
}

export interface WorkflowDeliveryRequest {
  pageKey: string
  version?: string
  agentId?: string
  modelInstanceId?: string
  publishedBy?: string
  replaceWorkflowId?: string | null
}

export interface WorkflowDeliveryResult {
  schema: 'reachai.page-workbench.workflow-delivery.v1'
  taskId: string
  pageKey: string
  workflowId: string
  workflowKeySlug: string
  workflowName: string
  workflowVersionId: number
  workflowVersion: string
  publishedAt?: string
  agentId: string
  agentKeySlug: string
  agentConfigVersionId: number
  agentConfigVersion: number
  toolName: string
  configStatus: string
  published: boolean
  replacedWorkflowId?: string | null
}

export type PageAccessJourneyStage =
  | 'GOAL_SELECTION'
  | 'AI_IMPLEMENTATION'
  | 'PUBLISH_CONFIRMATION'
  | 'REAL_ACCEPTANCE'
  | 'COMPLETE'

export type PageAccessJourneyStatus =
  | 'WAITING'
  | 'ACTIVE'
  | 'ACTION_REQUIRED'
  | 'COMPLETE'
  | 'ERROR'
  | 'UNAVAILABLE'

export interface PageAccessNextAction {
  code:
    | 'SELECT_GOAL'
    | 'START_IMPLEMENTATION'
    | 'OPEN_TASK'
    | 'RETRY_TASK'
    | 'CHECK_PAGE_ACTIONS'
    | 'START_WORKFLOW_ENGINEERING'
    | 'PUBLISH_WORKFLOW'
    | 'START_ACCEPTANCE'
    | 'RETRY_ACCEPTANCE'
    | 'VIEW_ONLINE'
    | 'REFRESH_STATUS'
  label: string
  message: string
  enabled: boolean
}

export interface PageAccessJourney {
  pageId: number
  pageKey: string
  stage: PageAccessJourneyStage
  status: PageAccessJourneyStatus
  currentStep: number
  completedSteps: number
  statusLabel: string
  title: string
  message: string
  nextAction: PageAccessNextAction
  activeTaskId?: string
  activeTaskKind?: string
  activeTaskStatus?: AiCodingExecutionStatus
  unreadFindingCount: number
  keptFindingCount: number
  resourceCount: number
  actionCount: number
  workflowId?: string
  workflowVersion?: string
  updatedAt?: string
}

export interface PageAccessActivity {
  taskId: string
  pageKey?: string
  taskKind: string
  executorProvider: AiCodingExecutorProvider
  title: string
  executionStatus: AiCodingExecutionStatus
  lastMessage?: string
  openQuestionCount: number
  connectionStatus?: AiCodingConnectionStatus
  updatedAt: string
}

export interface PageAccessCenterSummary {
  discoveredCount: number
  waitingCount: number
  activeCount: number
  awaitingAcceptanceCount: number
  completedCount: number
  unavailableCount: number
}

export interface PageAccessCenterOverview {
  schema: 'reachai.page-access-center.overview.v1'
  projectCode: string
  summary: PageAccessCenterSummary
  pages: PageAccessJourney[]
  activities: PageAccessActivity[]
  onlineCapabilities: PublishedPageWorkflow[]
  runtimeAvailable: boolean
  runtimeMessage: string
  generatedAt: string
  catalogPages?: ProjectPage[]
  pageMap?: PageMapSummary
  findings?: PageAnalysisFinding[]
  tasks?: AiCodingTask[]
}
import type {
  AiCodingTask,
  AiCodingConnectionStatus,
  AiCodingExecutionStatus,
  AiCodingExecutorProvider,
} from '@/types/aiCodingTask'
