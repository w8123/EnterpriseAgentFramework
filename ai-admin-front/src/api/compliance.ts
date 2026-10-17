import { controlRequest } from './request'
import type {
  MemoryErasureCreateRequest,
  MemoryErasureEvidenceRequest,
  MemoryErasureRequest,
} from '@/types/compliance'

const ROOT = '/api/compliance/erasure-requests'

export function createMemoryErasureRequest(data: MemoryErasureCreateRequest) {
  return controlRequest.post<MemoryErasureRequest>(ROOT, data)
}

export function getMemoryErasureRequest(requestId: string) {
  return controlRequest.get<MemoryErasureRequest>(`${ROOT}/${encodeURIComponent(requestId)}`)
}

export function retryMemoryErasureRequest(requestId: string) {
  return controlRequest.post<MemoryErasureRequest>(`${ROOT}/${encodeURIComponent(requestId)}/retry`)
}

export function attestMemoryErasureDomain(
  requestId: string,
  domainCode: string,
  data: MemoryErasureEvidenceRequest,
) {
  return controlRequest.post<MemoryErasureRequest>(
    `${ROOT}/${encodeURIComponent(requestId)}/domains/${encodeURIComponent(domainCode)}/evidence`,
    data,
  )
}
