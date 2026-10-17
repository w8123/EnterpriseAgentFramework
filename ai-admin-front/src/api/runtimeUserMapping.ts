import { controlRequest } from './request'
import type {
  RuntimeUserMapping,
  RuntimeUserMappingCreateRequest,
  RuntimeUserMappingListParams,
} from '@/types/runtimeUserMapping'

const ROOT = '/api/runtime-user-mappings'

export function listRuntimeUserMappings(params: RuntimeUserMappingListParams) {
  return controlRequest.get<RuntimeUserMapping[]>(ROOT, { params })
}

export function createRuntimeUserMapping(data: RuntimeUserMappingCreateRequest) {
  return controlRequest.post<RuntimeUserMapping>(ROOT, data)
}

export function deleteRuntimeUserMapping(id: number) {
  return controlRequest.delete<RuntimeUserMapping>(`${ROOT}/${id}`)
}
