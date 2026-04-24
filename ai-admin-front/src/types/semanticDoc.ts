export type SemanticLevel = 'project' | 'module' | 'tool' | 'scan_tool'
export type SemanticStatus = 'draft' | 'generated' | 'edited'
export type SemanticTaskStage = 'QUEUED' | 'RUNNING' | 'DONE' | 'FAILED'

export interface SemanticDoc {
  id: number
  level: SemanticLevel
  projectId: number | null
  moduleId: number | null
  toolId: number | null
  toolName?: string | null
  contentMd: string | null
  promptVersion: string | null
  modelName: string | null
  tokenUsage: number
  status: SemanticStatus
}

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

export interface ModuleMergeRequest {
  targetId: number
  sourceIds: number[]
  displayName?: string | null
}

export interface SemanticEditRequest {
  contentMd: string
}

export interface BatchStartResponse {
  taskId: string
}
