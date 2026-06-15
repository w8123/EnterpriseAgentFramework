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
  title?: string
  argsJson?: string
  targetPageInstanceId?: string
  confirmRequired?: boolean
  status: string
  resultJson?: string
  errorMessage?: string
  requestedAt?: string
  completedAt?: string
}

export interface PageRegistryView {
  id: number
  projectCode: string
  appId: string
  pageKey: string
  name: string
  routePattern?: string
  origin?: string
  currentPageInstanceId?: string
  status: string
  lastSeenAt?: string
  metadataJson?: string
}

export interface PageActionRegistryView {
  id: number
  projectCode: string
  appId: string
  pageKey: string
  actionKey: string
  title: string
  description?: string
  confirmRequired?: boolean
  inputSchemaJson?: string
  outputSchemaJson?: string
  sampleArgsJson?: string
  allowedAgentIdsJson?: string
  metadataJson?: string
  status: string
  lastSeenAt?: string
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

export interface PageActionDebugRequest {
  sessionId?: string
  args?: Record<string, unknown>
}

export interface PageActionDebugResponse {
  requestId?: string
  sessionId?: string
  projectCode: string
  pageKey: string
  actionKey: string
  targetPageInstanceId?: string
  status: string
  message: string
}

export interface PageActionReferenceView {
  referenceKey?: string
  agentId?: string
  agentName?: string
  agentKeySlug?: string
  agentProjectCode?: string
  agentEnabled?: boolean
  workflowId?: string
  workflowKeySlug?: string
  workflowName?: string
  workflowProjectCode?: string
  workflowStatus?: string
  workflowVersionId?: number
  workflowVersion?: string
  graphSource?: string
  bindingId?: number
  bindingType?: string
  bindingEnabled?: boolean
  nodeId: string
  nodeName?: string
  projectCode: string
  pageKey: string
  actionKey: string
}

export interface PageActionManualDeclarePayload {
  projectCode: string
  appId?: string
  pageKey: string
  pageName?: string
  routePattern?: string
  actionKey: string
  title?: string
  description?: string
  confirmRequired?: boolean
  inputSchema?: Record<string, unknown>
  outputSchema?: Record<string, unknown>
  sampleArgs?: Record<string, unknown>
  allowedAgentIds?: string[]
  status?: string
}

export interface PageActionManualDeclareResponse {
  source: string
  page: PageRegistryView
  action: PageActionRegistryView
}

export function listEmbedSessions(params: Record<string, unknown> = {}) {
  return agentRequest.get<EmbedSessionView[]>('/api/platform/embed/sessions', { params })
}

export function listPageActionEvents(params: Record<string, unknown> = {}) {
  return agentRequest.get<PageActionEventView[]>('/api/platform/embed/page-actions', { params })
}

export function listPageRegistry(params: Record<string, unknown> = {}) {
  return agentRequest.get<PageRegistryView[]>('/api/platform/embed/pages', { params })
}

export function listPageActionCatalog(params: Record<string, unknown> = {}) {
  return agentRequest.get<PageActionRegistryView[]>('/api/platform/embed/page-actions/catalog', { params })
}

export function debugPageActionCatalog(id: number, payload: PageActionDebugRequest) {
  return agentRequest.post<PageActionDebugResponse>(`/api/platform/embed/page-actions/catalog/${id}/debug`, payload)
}

export function getPageActionDebugResult(requestId: string) {
  return agentRequest.get<PageActionEventView>(`/api/platform/embed/page-actions/debug/${requestId}`)
}

export function listPageActionReferences(id: number) {
  return agentRequest.get<PageActionReferenceView[]>(`/api/platform/embed/page-actions/catalog/${id}/references`)
}

export function declarePageActionCatalog(payload: PageActionManualDeclarePayload) {
  return agentRequest.post<PageActionManualDeclareResponse>('/api/platform/embed/page-actions/catalog/manual', payload)
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
