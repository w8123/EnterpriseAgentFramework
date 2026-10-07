import { controlRequest } from './request'
import type { AssetReferences } from '@/types/assetReferences'
import type { HttpApiConnection, HttpApiDetail, HttpApiInvocationOutcome,
  HttpApiInvocationUnconfirmed, HttpApiPage } from '@/types/httpApi'

export function listHttpApis(params: { projectId: number; environment?: string; keyword?: string;
  method?: string; sourceStatus?: string; current: number; size: number }) {
  return controlRequest.get<HttpApiPage>('/api/apis', { params, errorFeedback: 'local' })
}

export function getHttpApi(id: number) {
  return controlRequest.get<HttpApiDetail>(`/api/apis/${id}`)
}

export function getHttpApiReferences(id: number) {
  return controlRequest.get<AssetReferences>(`/api/apis/${id}/references`)
}

export function acceptHttpApi(id: number, expectedSourceSetRevision: string) {
  return controlRequest.post<HttpApiDetail>(`/api/apis/${id}/accept`, { expectedSourceSetRevision })
}

export function getHttpApiConnection(id: number) {
  return controlRequest.get<HttpApiConnection>(`/api/apis/${id}/connection`)
}

export function saveHttpApiConnection(id: number, body: { origin: string; authMode: string;
  credentialRef: string | null; expectedRevision: number | null }) {
  return controlRequest.put<HttpApiConnection>(`/api/apis/${id}/connection`, body)
}

export function invokeHttpApi(id: number, body: { invocationId: string; expectedContractHash: string;
  connectionRevision: number; pathParams: Record<string, unknown>; queryParams: Record<string, unknown>;
  body?: Record<string, unknown>; confirmedSideEffect?: boolean; expectedSourceSetRevision?: string;
  expectedCredentialRevision?: string | null }) {
  return controlRequest.post<HttpApiInvocationOutcome | HttpApiInvocationUnconfirmed>(
    `/api/apis/${id}/invocations`, body, { errorFeedback: 'local' })
}

/** Read-back only: a timeout never sends the HTTP request again. */
export function getHttpApiInvocation(invocationId: string, projectCode: string) {
  return controlRequest.get<HttpApiInvocationOutcome>(
    `/api/api-invocations/${encodeURIComponent(invocationId)}`, { params: { projectCode }, errorFeedback: 'local' })
}
