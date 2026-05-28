import { agentRequest } from './request'

export interface EmbedSessionView {
  id: number
  sessionId: string
  tenantId: string
  appId: string
  projectCode: string
  agentId: string
  externalUserId: string
  globalUserId?: string
  pageInstanceId: string
  route?: string
  origin: string
  status: string
  createdAt?: string
  expiresAt?: string
}

export interface PageActionEventView {
  id: number
  requestId: string
  sessionId: string
  appId: string
  agentId: string
  actionKey?: string
  targetPageInstanceId?: string
  confirmRequired?: boolean
  status: string
  errorMessage?: string
  requestedAt?: string
  completedAt?: string
}

export interface EmbedChatEventView {
  id: number
  sessionId: string
  eventType: string
  role?: string
  content?: string
  payloadJson?: string
  traceId?: string
  createdAt?: string
}

export interface EmbedRendererView {
  id: number
  appId: string
  rendererKey: string
  name: string
  version: string
  inputSchemaJson?: string
  allowedAgentIdsJson?: string
  status: string
  createdAt?: string
  updatedAt?: string
}

export interface EmbedRendererPayload {
  appId: string
  rendererKey: string
  name?: string
  version: string
  inputSchema?: Record<string, unknown>
  allowedAgentIds?: string[]
  status?: string
}

export interface EmbedCredentialPolicyView {
  id: number
  projectId?: number
  projectCode: string
  appKey: string
  allowedOriginsJson?: string
  allowedAgentIdsJson?: string
  tokenTtlSeconds?: number
  status: string
}

export interface EmbedCredentialPolicyPayload {
  allowedOrigins: string[]
  allowedAgentIds: string[]
  tokenTtlSeconds: number
  status?: string
}

export function listEmbedSessions(params: Record<string, unknown> = {}) {
  return agentRequest.get<EmbedSessionView[]>('/api/platform/embed/sessions', { params })
}

export function listPageActionEvents(params: Record<string, unknown> = {}) {
  return agentRequest.get<PageActionEventView[]>('/api/platform/embed/page-actions', { params })
}

export function listEmbedChatEvents(sessionId: string, limit = 200) {
  return agentRequest.get<EmbedChatEventView[]>('/api/platform/embed/chat-events', { params: { sessionId, limit } })
}

export function listEmbedRenderers(params: Record<string, unknown> = {}) {
  return agentRequest.get<EmbedRendererView[]>('/api/platform/embed/renderers', { params })
}

export function listEmbedCredentialPolicies(params: Record<string, unknown> = {}) {
  return agentRequest.get<EmbedCredentialPolicyView[]>('/api/platform/embed/credentials', { params })
}

export function updateEmbedCredentialPolicy(id: number, payload: EmbedCredentialPolicyPayload) {
  return agentRequest.put<EmbedCredentialPolicyView>(`/api/platform/embed/credentials/${id}/policy`, payload)
}

export function createEmbedRenderer(payload: EmbedRendererPayload) {
  return agentRequest.post<EmbedRendererView>('/api/platform/embed/renderers', payload)
}

export function updateEmbedRenderer(id: number, payload: EmbedRendererPayload) {
  return agentRequest.put<EmbedRendererView>(`/api/platform/embed/renderers/${id}`, payload)
}

export function disableEmbedRenderer(id: number) {
  return agentRequest.post<void>(`/api/platform/embed/renderers/${id}/disable`)
}
