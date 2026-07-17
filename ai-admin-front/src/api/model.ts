import { modelRequest } from './request'
import type {
  ModelChatRequest,
  ModelChatResponse,
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

export function getModelTemplate(id: string) {
  return modelRequest.get<ApiResult<ModelTemplate>>(`/templates/${id}`)
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

export function testModelInstance(id: string) {
  return modelRequest.post<ApiResult<ModelInstanceTestResult>>(`/instances/${id}/test`)
}

export function archiveModelInstance(id: string) {
  return modelRequest.post<ApiResult<ModelInstance>>(`/instances/${id}/archive`)
}
