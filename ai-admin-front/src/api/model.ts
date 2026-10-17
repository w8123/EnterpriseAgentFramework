import { modelRequest } from './request'
import type {
  ModelChatRequest,
  ModelChatResponse,
  ModelDiscoveryResult,
  ModelProviderAccount,
  ModelProviderAccountRequest,
  ProviderAccountInstancesRequest,
  ProviderAccountInstancesResult,
  ProviderModelsResult,
  ModelCatalogStatus,
  ModelCatalogSettingsRequest,
  ModelCatalogManualSyncResponse,
  ModelInstance,
  ModelInstanceCreateRequest,
  ModelInstanceDraftTestRequest,
  ModelInstanceFromTemplateRequest,
  ModelInstanceListParams,
  ModelInstanceTestResult,
  ModelInstanceUpdateRequest,
  ModelTemplate,
  ModelTemplateListParams,
} from '@/types/model'
import type { ApiResult } from '@/types/import'

export function modelChat(data: ModelChatRequest) {
  return modelRequest.post<ApiResult<ModelChatResponse>>('/chat', data)
}

export function getModelTemplates(params?: ModelTemplateListParams) {
  return modelRequest.get<ApiResult<ModelTemplate[]>>('/templates', { params })
}

export function getModelCatalogStatus() {
  return modelRequest.get<ApiResult<ModelCatalogStatus>>('/catalog/status')
}

export function updateModelCatalogSettings(data: ModelCatalogSettingsRequest) {
  return modelRequest.put<ApiResult<ModelCatalogStatus>>('/catalog/settings', data)
}

export function triggerModelCatalogSync() {
  return modelRequest.post<ApiResult<ModelCatalogManualSyncResponse>>('/catalog/sync')
}

export function getModelInstances(params?: ModelInstanceListParams) {
  return modelRequest.get<ApiResult<ModelInstance[]>>('/instances', { params })
}

export function getModelInstance(id: string) {
  return modelRequest.get<ApiResult<ModelInstance>>(`/instances/${id}`)
}

export function createModelInstance(data: ModelInstanceCreateRequest) {
  return modelRequest.post<ApiResult<ModelInstance>>('/instances', data)
}

export function createModelInstanceFromTemplate(templateId: string, data: ModelInstanceFromTemplateRequest) {
  return modelRequest.post<ApiResult<ModelInstance>>(`/instances/from-template/${templateId}`, data)
}

export function updateModelInstance(id: string, data: ModelInstanceUpdateRequest) {
  return modelRequest.put<ApiResult<ModelInstance>>(`/instances/${id}`, data)
}

export function testModelInstanceDraft(data: ModelInstanceDraftTestRequest) {
  return modelRequest.post<ApiResult<ModelInstanceTestResult>>('/instances/test-draft', data)
}

/** 用草稿或已保存实例的连接配置，实时列出供应商当前可用的模型 */
export function discoverUpstreamModels(data: ModelInstanceDraftTestRequest) {
  return modelRequest.post<ApiResult<ModelDiscoveryResult>>('/instances/discover-models', data)
}

export function testModelInstance(id: string) {
  return modelRequest.post<ApiResult<ModelInstanceTestResult>>(`/instances/${id}/test`)
}

export function archiveModelInstance(id: string) {
  return modelRequest.post<ApiResult<ModelInstance>>(`/instances/${id}/archive`)
}

const PROVIDER_ACCOUNTS = '/instances/provider-accounts'

export function getProviderAccounts(provider?: string) {
  return modelRequest.get<ApiResult<ModelProviderAccount[]>>(PROVIDER_ACCOUNTS, { params: { provider } })
}

export function createProviderAccount(data: ModelProviderAccountRequest) {
  return modelRequest.post<ApiResult<ModelProviderAccount>>(PROVIDER_ACCOUNTS, data)
}

export function updateProviderAccount(id: string, data: ModelProviderAccountRequest) {
  return modelRequest.put<ApiResult<ModelProviderAccount>>(`${PROVIDER_ACCOUNTS}/${id}`, data)
}

export function deleteProviderAccount(id: string) {
  return modelRequest.delete<ApiResult<void>>(`${PROVIDER_ACCOUNTS}/${id}`)
}

/** 不保存，用填写的地址和 Key 试取厂商模型列表 */
export function discoverProviderModelsDraft(data: ModelProviderAccountRequest) {
  return modelRequest.post<ApiResult<ProviderModelsResult>>(`${PROVIDER_ACCOUNTS}/discover`, data)
}

export function getProviderAccountModels(id: string) {
  return modelRequest.post<ApiResult<ProviderModelsResult>>(`${PROVIDER_ACCOUNTS}/${id}/models`)
}

export function createProviderAccountInstances(id: string, data: ProviderAccountInstancesRequest) {
  return modelRequest.post<ApiResult<ProviderAccountInstancesResult>>(`${PROVIDER_ACCOUNTS}/${id}/instances`, data)
}
