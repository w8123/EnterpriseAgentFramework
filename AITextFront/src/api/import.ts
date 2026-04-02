import request from './request'
import type { ApiResult, ChunkPreviewResponse, PipelineResult } from '@/types/import'

/**
 * 预览切分结果 — 上传文件 + 切分参数，返回 chunk 列表（不入库）
 */
export function previewChunks(params: {
  file: File
  chunkStrategy: string
  chunkSize: number
  chunkOverlap: number
}) {
  const formData = new FormData()
  formData.append('file', params.file)
  formData.append('chunkStrategy', params.chunkStrategy)
  formData.append('chunkSize', String(params.chunkSize))
  formData.append('chunkOverlap', String(params.chunkOverlap))

  return request.post<ApiResult<ChunkPreviewResponse>>('/knowledge/preview', formData, {
    headers: { 'Content-Type': 'multipart/form-data' },
    timeout: 120000,
  })
}

/**
 * 文件入库 — 上传文件并触发 Pipeline 完整流程
 */
export function importFile(params: {
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
  if (params.extraParams) {
    formData.append('extraParams', JSON.stringify(params.extraParams))
  }

  return request.post<ApiResult<PipelineResult>>('/knowledge/import/file', formData, {
    headers: { 'Content-Type': 'multipart/form-data' },
    timeout: 300000,
  })
}
