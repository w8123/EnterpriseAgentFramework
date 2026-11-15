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

export interface ManualHttpApiSaveResult { apiId: number; sourceId: number; created: boolean; status: string }

/** 手动填写或粘贴 curl 添加的 API；同一方法加路径再次保存会覆盖。 */
export function saveManualHttpApi(projectId: number, body: import('@/views/api/manualHttpApi').ManualHttpApiPayload) {
  return controlRequest.post<ManualHttpApiSaveResult>(`/api/scan-projects/${projectId}/manual-http-apis`, body, { errorFeedback: 'local' })
}

/** 只删除手工来源；SDK/OpenAPI 等其他来源保持不变。 */
export function removeManualHttpApi(projectId: number, apiId: number) {
  return controlRequest.delete<{ removed: boolean }>(`/api/scan-projects/${projectId}/manual-http-apis/${apiId}`, { errorFeedback: 'local' })
}

export interface HttpApiImportCandidate {
  key: string
  request: import('@/views/api/manualHttpApi').ManualHttpApiPayload & { summary?: string | null; description?: string | null; businessGroups?: string[] }
  host: string | null
  deprecated: boolean
  warnings: string[]
  unsupported: string | null
  existing: boolean
}

export interface HttpApiImportPreview {
  format: 'OPENAPI3' | 'SWAGGER2' | 'POSTMAN' | 'HAR' | 'DIFY' | 'N8N'
  title: string
  version: string | null
  fetchedFrom: string | null
  stripPrefix: string
  skipped: number
  warnings: string[]
  candidates: HttpApiImportCandidate[]
}

/** 解析整份接口文档（OpenAPI / Swagger / Postman / HAR），不保存。url 由后端下载，Authorization 只用这一次。 */
export function previewHttpApiDocument(projectId: number, body: { content?: string; fileName?: string; url?: string;
  authorization?: string; stripPrefix?: string }) {
  return controlRequest.post<HttpApiImportPreview>(`/api/scan-projects/${projectId}/manual-http-apis/import/preview`, body,
    { errorFeedback: 'local', timeout: 60000 })
}

/** 读取 Dify 应用（用应用 API 密钥）或 n8n 的 Webhook 工作流，列成可导入的接口；密钥只用这一次，不保存。 */
export function previewWorkflowPlatform(projectId: number, body: { platform: 'DIFY' | 'N8N'; baseUrl?: string; apiKey?: string;
  webhookUrls?: string }) {
  return controlRequest.post<HttpApiImportPreview>(`/api/scan-projects/${projectId}/manual-http-apis/import/platform-preview`, body,
    { errorFeedback: 'local', timeout: 60000 })
}

export interface ManualHttpApiBatchResult {
  saved: number
  failed: number
  results: Array<{ index: number; httpMethod: string; path: string; ok: boolean; apiId?: number; created?: boolean;
    status?: string; message?: string }>
}

/** 一次最多 200 个；每个单独保存，一个失败不影响其他。 */
export function saveManualHttpApiBatch(projectId: number, apis: unknown[]) {
  return controlRequest.post<ManualHttpApiBatchResult>(`/api/scan-projects/${projectId}/manual-http-apis/batch`, { apis },
    { errorFeedback: 'local', timeout: 120000 })
}

/** 把已接纳的 HTTP API 包装成「调用一次接口」的流程，之后可以加入员工发布，员工和助手才能用。 */
export interface HttpApiFlowResult {
  apiId: number
  workflowId: string | null
  workflowKeySlug: string | null
  workflowName: string | null
  workflowStatus: string | null
  created: boolean
  error: string | null
}

export function generateHttpApiFlows(body: { projectId: number; projectCode: string;
  apis: { apiId: number; title?: string }[]; publish: boolean; publishedBy?: string }) {
  return controlRequest.post<HttpApiFlowResult[]>('/api/workflows/http-api-flows', body,
    { errorFeedback: 'local', timeout: 600000 })
}

/** 用这个系统助手的模型给 API 起中文名（只给建议），返回 { apiId: 名称 }。 */
export function suggestHttpApiFlowNames(body: { projectId: number; projectCode: string; apiIds: number[] }) {
  return controlRequest.post<Record<string, string>>('/api/workflows/http-api-flows/names', body,
    { errorFeedback: 'local', timeout: 180000 })
}
