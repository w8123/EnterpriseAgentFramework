import { controlRequest } from './request'
import type { ApiResult, DocumentImportJob } from '@/types/import'

/**
 * Upload once into the durable import lifecycle.  Preview and commit read the
 * same stored parse result; neither endpoint asks the browser for the file a
 * second time.
 */
export function submitDocumentImportJob(params: {
  file: File
  knowledgeBaseCode: string
  chunkStrategy: string
  chunkSize: number
  chunkOverlap: number
  extraParams?: Record<string, unknown>
}) {
  const formData = new FormData()
  formData.append('file', params.file)
  formData.append('knowledgeBaseCode', params.knowledgeBaseCode)
  formData.append('chunkStrategy', params.chunkStrategy)
  formData.append('chunkSize', String(params.chunkSize))
  formData.append('chunkOverlap', String(params.chunkOverlap))
  formData.append('autoCommit', 'false')
  if (params.extraParams) {
    formData.append('extraParams', JSON.stringify(params.extraParams))
  }
  return controlRequest.post<ApiResult<DocumentImportJob>>('/api/knowledge/import-jobs', formData, {
    headers: { 'Content-Type': 'multipart/form-data' },
    timeout: 120000,
  })
}

export function getDocumentImportJob(jobId: string, includePreview = false) {
  return controlRequest.get<ApiResult<DocumentImportJob>>(`/api/knowledge/import-jobs/${jobId}`, {
    params: { includePreview },
    timeout: 30000,
  })
}

export function commitDocumentImportJob(jobId: string) {
  return controlRequest.post<ApiResult<DocumentImportJob>>(`/api/knowledge/import-jobs/${jobId}/commit`, null, {
    timeout: 30000,
  })
}

export function retryDocumentImportJob(jobId: string) {
  return controlRequest.post<ApiResult<DocumentImportJob>>(`/api/knowledge/import-jobs/${jobId}/retry`, null, {
    timeout: 30000,
  })
}

export function cancelDocumentImportJob(jobId: string) {
  return controlRequest.post<ApiResult<void>>(`/api/knowledge/import-jobs/${jobId}/cancel`, null, {
    timeout: 30000,
  })
}
