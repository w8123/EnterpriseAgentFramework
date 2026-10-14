export type SemanticSubjectType = 'PROJECT' | 'GROUP' | 'BUSINESS_METHOD' | 'HTTP_API' | 'SOURCE_OBSERVATION'
export type SemanticStatus = 'draft' | 'generated' | 'edited'
export type SemanticTaskStage = 'QUEUED' | 'RUNNING' | 'DONE' | 'FAILED'

export interface SemanticDoc {
  id: number
  projectId: number
  subjectType: SemanticSubjectType
  subjectId: number
  subjectKey: string
  title: string
  contractHash?: string | null
  basisContractHash?: string | null
  sourceObservationIds?: number[]
  updatedAt?: string | null
  contentMd: string | null
  promptVersion: string | null
  modelName: string | null
  tokenUsage: number
  status: SemanticStatus
}
/** Directory metadata; document bodies are read only for the selected subject. */
export type SemanticDocIndex = Omit<SemanticDoc, 'contentMd'>

export interface SemanticTask {
  taskId: string
  projectId: number
  stage: SemanticTaskStage
  totalSteps: number
  completedSteps: number
  currentStep: string | null
  errorMessage: string | null
  totalTokens: number
  startedAt: string | null
  finishedAt: string | null
}

export interface ScanModule {
  id: number
  projectId: number
  name: string
  displayName: string
  sourceClasses: string[]
}

export type CatalogClassificationSubjectType = 'BUSINESS_METHOD' | 'HTTP_API'
export interface CatalogClassificationGroup extends ScanModule { memberCount: number }
/** Complete, typed owner memberships for one authorized project; never a page projection. */
export interface CatalogClassificationIndex {
  projectId: number
  subjectType: CatalogClassificationSubjectType
  groups: CatalogClassificationGroup[]
  memberships: Array<{ subjectId: number; groupIds: number[] }>
  ungroupedCount: number
}

export interface SemanticEditRequest {
  contentMd: string
  expectedUpdatedAt?: string | null
}

export interface CapabilityEnrichment {
  paramHints?: import('@/api/apiGraph').ApiGraphParamSourceHint[]
  projectId: number
  subjectType: SemanticSubjectType
  subjectId: number
  subjectKey: string
  title: string
  contractHash: string | null
  accepted: boolean
  semanticDoc: SemanticDoc | null
  semanticStale: boolean
  groups: ScanModule[]
  sourceObservations: Array<{ id: number; title: string; sourceLocation: string | null; removedFromSource: boolean;
    sensitiveData?: { types?: string[]; summary?: string; scannedAt?: string; modelName?: string } | null }>
  graphAvailable: boolean
  graphState: 'UNACCEPTED' | 'NOT_BUILT' | 'STALE' | 'CURRENT'
}

export interface BatchStartResponse {
  taskId: string
}
