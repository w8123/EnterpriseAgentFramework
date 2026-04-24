export interface ToolCandidate {
  toolId: number
  toolName: string
  projectId: number | null
  moduleId: number | null
  score: number
  text: string | null
}

export interface ToolRetrievalSearchRequest {
  query: string
  topK?: number
  projectIds?: number[]
  moduleIds?: number[]
  toolWhitelist?: number[]
  enabledOnly?: boolean
  agentVisibleOnly?: boolean
}

export interface ToolRetrievalSearchResponse {
  candidates: ToolCandidate[]
  message?: string | null
}

export type ToolRebuildStage = 'QUEUED' | 'RUNNING' | 'DONE' | 'FAILED'

export interface ToolRebuildTask {
  taskId: string
  stage: ToolRebuildStage
  totalSteps: number
  completedSteps: number
  successCount: number
  skippedCount: number
  failedCount: number
  currentStep?: string | null
  errorMessage?: string | null
  startedAt?: string
  finishedAt?: string | null
}

export interface ToolRebuildStartResponse {
  taskId: string
}
