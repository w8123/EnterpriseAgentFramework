import { controlRequest } from './request'
import type {
  A2aApiKeyIssue,
  A2aCredential,
  A2aOutboundSecretStored,
  A2aHubOverview,
  A2aPageView,
  A2aPayloadView,
  A2aPrincipal,
  A2aPrincipalCreateRequest,
  A2aPublication,
  A2aPublicationCreateRequest,
  A2aPublicationDetail,
  A2aPublicationDraft,
  A2aRemoteAgent,
  A2aRemoteAgentDetail,
  A2aRemoteAgentDiscoverRequest,
  A2aRemoteAgentDiscoveryResult,
  A2aTaskDetail,
  A2aTaskQuery,
  A2aTaskSummary,
  A2aTrustProfile,
  A2aTrustProfileUpsertRequest,
} from '@/types/a2aHub'

const ROOT = '/api/a2a-hub'

export function getA2aHubOverview() {
  return controlRequest.get<A2aHubOverview>(`${ROOT}/overview`)
}

export function listA2aPublications(params?: {
  search?: string
  status?: string
  limit?: number
  offset?: number
}) {
  return controlRequest.get<A2aPageView<A2aPublication>>(`${ROOT}/publications`, { params })
}

export function getA2aPublication(id: number) {
  return controlRequest.get<A2aPublicationDetail>(`${ROOT}/publications/${id}`)
}

export function createA2aPublication(body: A2aPublicationCreateRequest) {
  return controlRequest.post<A2aPublicationDetail>(`${ROOT}/publications`, body)
}

export function createA2aPublicationRevision(publicationId: number, body: A2aPublicationDraft) {
  return controlRequest.post(
    `${ROOT}/publications/${publicationId}/revisions`,
    body,
  )
}

export function preflightA2aPublication(publicationId: number, revisionId: number) {
  return controlRequest.post<A2aPublicationDetail>(
    `${ROOT}/publications/${publicationId}/revisions/${revisionId}:preflight`,
  )
}

export function publishA2aPublication(publicationId: number, revisionId: number) {
  return controlRequest.post<A2aPublicationDetail>(
    `${ROOT}/publications/${publicationId}/revisions/${revisionId}:publish`,
  )
}

export function suspendA2aPublication(publicationId: number) {
  return controlRequest.post<A2aPublicationDetail>(`${ROOT}/publications/${publicationId}:suspend`)
}

export function resumeA2aPublication(publicationId: number) {
  return controlRequest.post<A2aPublicationDetail>(`${ROOT}/publications/${publicationId}:resume`)
}

export function listA2aRemoteAgents(params?: {
  search?: string
  status?: string
  health?: string
  limit?: number
  offset?: number
}) {
  return controlRequest.get<A2aPageView<A2aRemoteAgent>>(`${ROOT}/remote-agents`, { params })
}

export function getA2aRemoteAgent(id: number) {
  return controlRequest.get<A2aRemoteAgentDetail>(`${ROOT}/remote-agents/${id}`)
}

export function discoverA2aRemoteAgent(body: A2aRemoteAgentDiscoverRequest) {
  return controlRequest.post<A2aRemoteAgentDiscoveryResult>(`${ROOT}/remote-agents:discover`, body)
}

export function rediscoverA2aRemoteAgent(id: number) {
  return controlRequest.post<A2aRemoteAgentDiscoveryResult>(`${ROOT}/remote-agents/${id}:rediscover`)
}

export function approveA2aRemoteAgentRevision(
  id: number,
  revisionId: number,
  body: {
    preferredInterfaceKey: string
    preferredSecuritySchemeKey?: string
    trustProfileId: number
    credentialId?: number
  },
) {
  return controlRequest.post<A2aRemoteAgentDetail>(
    `${ROOT}/remote-agents/${id}/revisions/${revisionId}:approve`,
    body,
  )
}

export function rejectA2aRemoteAgentRevision(id: number, revisionId: number, reason?: string) {
  return controlRequest.post<A2aRemoteAgentDetail>(
    `${ROOT}/remote-agents/${id}/revisions/${revisionId}:reject`,
    { reason },
  )
}

export function disableA2aRemoteAgent(id: number) {
  return controlRequest.post<A2aRemoteAgentDetail>(`${ROOT}/remote-agents/${id}:disable`)
}

export function enableA2aRemoteAgent(id: number) {
  return controlRequest.post<A2aRemoteAgentDetail>(`${ROOT}/remote-agents/${id}:enable`)
}

export function listA2aTasks(params?: A2aTaskQuery) {
  return controlRequest.get<A2aPageView<A2aTaskSummary>>(`${ROOT}/tasks`, { params })
}

export function getA2aTask(taskId: string, direction?: string) {
  return controlRequest.get<A2aTaskDetail>(`${ROOT}/tasks/${encodeURIComponent(taskId)}`, {
    params: { direction },
  })
}

export function cancelA2aTask(taskId: string, direction?: string) {
  return controlRequest.post<A2aTaskSummary>(
    `${ROOT}/tasks/${encodeURIComponent(taskId)}:cancel`,
    undefined,
    { params: { direction } },
  )
}

export function getA2aMessagePayload(taskId: string, messageId: string, direction?: string) {
  return controlRequest.get<A2aPayloadView>(
    `${ROOT}/tasks/${encodeURIComponent(taskId)}/messages/${encodeURIComponent(messageId)}/payload`,
    { params: { direction } },
  )
}

export function getA2aArtifactPayload(taskId: string, artifactId: string, direction?: string) {
  return controlRequest.get<A2aPayloadView>(
    `${ROOT}/tasks/${encodeURIComponent(taskId)}/artifacts/${encodeURIComponent(artifactId)}/payload`,
    { params: { direction } },
  )
}

export function listA2aTrustProfiles(params?: {
  search?: string
  status?: string
  limit?: number
  offset?: number
}) {
  return controlRequest.get<A2aPageView<A2aTrustProfile>>(`${ROOT}/trust-profiles`, { params })
}

export function createA2aTrustProfile(body: A2aTrustProfileUpsertRequest) {
  return controlRequest.post<A2aTrustProfile>(`${ROOT}/trust-profiles`, body)
}

export function updateA2aTrustProfile(id: number, body: A2aTrustProfileUpsertRequest) {
  return controlRequest.put<A2aTrustProfile>(`${ROOT}/trust-profiles/${id}`, body)
}

export function changeA2aTrustProfileStatus(id: number, status: string) {
  return controlRequest.post<A2aTrustProfile>(`${ROOT}/trust-profiles/${id}:status`, { status })
}

export function listA2aPrincipals(params?: {
  search?: string
  status?: string
  limit?: number
  offset?: number
}) {
  return controlRequest.get<A2aPageView<A2aPrincipal>>(`${ROOT}/principals`, { params })
}

export function createA2aPrincipal(body: A2aPrincipalCreateRequest) {
  return controlRequest.post<A2aPrincipal>(`${ROOT}/principals`, body)
}

export function changeA2aPrincipalStatus(id: number, status: string) {
  return controlRequest.post<A2aPrincipal>(`${ROOT}/principals/${id}:status`, { status })
}

export function listA2aCredentials(params?: {
  search?: string
  status?: string
  limit?: number
  offset?: number
}) {
  return controlRequest.get<A2aPageView<A2aCredential>>(`${ROOT}/credentials`, { params })
}

export function createA2aApiKey(body: {
  credentialKey: string
  name: string
  expiresAt: string
}) {
  return controlRequest.post<A2aApiKeyIssue>(`${ROOT}/credentials/api-keys`, body)
}

export function rotateA2aCredential(id: number) {
  return controlRequest.post<A2aApiKeyIssue>(`${ROOT}/credentials/${id}:rotate`)
}

export function storeA2aOutboundSecret(body: {
  credentialKey: string
  name: string
  credentialType: 'API_KEY' | 'BEARER'
  secret: string
  expiresAt: string
}) {
  return controlRequest.post<A2aOutboundSecretStored>(`${ROOT}/credentials/outbound-secrets`, body)
}

export function rotateA2aOutboundSecret(id: number, body: { secret: string; expiresAt?: string }) {
  return controlRequest.post<A2aOutboundSecretStored>(`${ROOT}/credentials/${id}:rotate-outbound`, body)
}

export function revokeA2aCredential(id: number) {
  return controlRequest.post<A2aCredential>(`${ROOT}/credentials/${id}:revoke`)
}
