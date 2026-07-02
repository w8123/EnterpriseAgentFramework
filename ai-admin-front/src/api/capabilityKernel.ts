import { controlRequest } from './request'

export interface CapabilityModule {
  id?: number
  code: string
  name: string
  version?: string
  sourceType?: string
  status?: string
  enabled?: boolean
  manifestJson?: string | null
  configSchemaJson?: string | null
  configJson?: string | null
  createTime?: string
  updateTime?: string
}

export interface ToolAsset {
  id?: number
  capabilityModuleId?: number
  capabilityCode?: string
  toolCode: string
  name: string
  qualifiedName?: string
  description?: string
  inputSchemaJson?: string | null
  outputSchemaJson?: string | null
  executorType?: string
  executorRef?: string
  sideEffect?: string
  enabled?: boolean
  agentVisible?: boolean
  createTime?: string
  updateTime?: string
}

export interface CompositionDefinition {
  id?: number
  capabilityModuleId?: number
  capabilityCode?: string
  compositionCode: string
  name: string
  qualifiedName?: string
  description?: string
  graphSpecJson?: string | null
  inputSchemaJson?: string | null
  outputSchemaJson?: string | null
  sideEffect?: string
  enabled?: boolean
  agentVisible?: boolean
  createTime?: string
  updateTime?: string
}

export interface InteractionDefinition {
  id?: number
  capabilityModuleId?: number
  capabilityCode?: string
  interactionCode: string
  name: string
  qualifiedName?: string
  description?: string
  interactionType?: 'COLLECT_INPUT' | 'PRESENT_OUTPUT' | 'USER_CHOICE' | 'CONFIRM_ACTION' | 'REVIEW_EDIT' | string
  specJson?: string | null
  inputSchemaJson?: string | null
  outputSchemaJson?: string | null
  enabled?: boolean
  agentVisible?: boolean
  createTime?: string
  updateTime?: string
}

export interface RuntimeExecuteResult {
  success: boolean
  status?: 'SUCCESS' | 'FAILED' | 'WAITING_USER' | string
  qualifiedName: string
  output?: unknown
  errorMessage?: string
  metadata?: Record<string, unknown>
}

export function listCapabilityModules() {
  return controlRequest.get<CapabilityModule[]>('/api/capabilities')
}

export function saveCapabilityModule(data: CapabilityModule) {
  return controlRequest.post<CapabilityModule>('/api/capabilities', data)
}

export function listModuleTools(code: string) {
  return controlRequest.get<ToolAsset[]>(`/api/capabilities/${encodeURIComponent(code)}/tools`)
}

export function saveModuleTool(code: string, data: ToolAsset) {
  return controlRequest.post<ToolAsset>(`/api/capabilities/${encodeURIComponent(code)}/tools`, data)
}

export function listModuleCompositions(code: string) {
  return controlRequest.get<CompositionDefinition[]>(`/api/capabilities/${encodeURIComponent(code)}/compositions`)
}

export function saveModuleComposition(code: string, data: CompositionDefinition) {
  return controlRequest.post<CompositionDefinition>(`/api/capabilities/${encodeURIComponent(code)}/compositions`, data)
}

export function listModuleInteractions(code: string) {
  return controlRequest.get<InteractionDefinition[]>(`/api/capabilities/${encodeURIComponent(code)}/interactions`)
}

export function saveModuleInteraction(code: string, data: InteractionDefinition) {
  return controlRequest.post<InteractionDefinition>(`/api/capabilities/${encodeURIComponent(code)}/interactions`, data)
}

export function executeToolAsset(qualifiedName: string, params: Record<string, unknown>) {
  return controlRequest.post<RuntimeExecuteResult>(
    `/api/runtime/tools/${encodeURIComponent(qualifiedName)}/execute`,
    { params },
  )
}

export function resumeInteraction(sessionId: string, params: Record<string, unknown>) {
  return controlRequest.post<RuntimeExecuteResult>(
    `/api/runtime/interactions/${encodeURIComponent(sessionId)}/resume`,
    { params },
  )
}

export function executeComposition(qualifiedName: string, params: Record<string, unknown>) {
  return controlRequest.post<RuntimeExecuteResult>(
    `/api/runtime/compositions/${encodeURIComponent(qualifiedName)}/execute`,
    { params },
  )
}
