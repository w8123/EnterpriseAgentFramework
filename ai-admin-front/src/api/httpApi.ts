import { controlRequest } from './request'
import type { AssetReferences } from '@/types/assetReferences'
import type { HttpApiConnection, HttpApiDetail, HttpApiInvocationOutcome, HttpApiProjectDefaultConnection,
  HttpApiInvocationUnconfirmed, HttpApiJsonValue, HttpApiPage } from '@/types/httpApi'

export function listHttpApis(params: { projectId: number; environment?: string; keyword?: string;
  method?: string; sourceStatus?: string; groupId?: number; ungrouped?: boolean; current: number; size: number }) {
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

/** Project + environment default connection, resolved from this API's owner (D27). */
export function getHttpApiProjectDefaultConnection(id: number) {
  return controlRequest.get<HttpApiProjectDefaultConnection>(`/api/apis/${id}/connection/project-default`)
}

/** `expectedRevision` is `projectDefault.revision` (null before the first save); returns this API's effective view. */
export function saveHttpApiProjectDefaultConnection(id: number, body: { origin: string; authMode: string;
  credentialRef: string | null; expectedRevision: number | null }) {
  return controlRequest.put<HttpApiConnection>(`/api/apis/${id}/connection/project-default`, body)
}

/** Removes this API's override so it inherits the project default again. */
export function removeHttpApiConnectionOverride(id: number, expectedRevision: number) {
  return controlRequest.delete<HttpApiConnection>(`/api/apis/${id}/connection/override`, { params: { expectedRevision } })
}

export function invokeHttpApi(id: number, body: { invocationId: string; expectedContractHash: string;
  connectionRevision: number; pathParams: Record<string, unknown>; queryParams: Record<string, unknown>;
  body?: HttpApiJsonValue; confirmedSideEffect?: boolean; expectedSourceSetRevision?: string;
  expectedCredentialRevision?: string | null }) {
  return controlRequest.post<HttpApiInvocationOutcome | HttpApiInvocationUnconfirmed>(
    `/api/apis/${id}/invocations`, body, { errorFeedback: 'local' })
}

/** Read-back only: a timeout never sends the HTTP request again. */
export function getHttpApiInvocation(invocationId: string, projectCode: string) {
  return controlRequest.get<HttpApiInvocationOutcome>(
    `/api/api-invocations/${encodeURIComponent(invocationId)}`, { params: { projectCode }, errorFeedback: 'local' })
}
