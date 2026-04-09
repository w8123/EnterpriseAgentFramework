import request from './request'
import type {
  KnowledgeBase,
  KnowledgeBaseForm,
  FileInfo,
  ChunkDetail,
  KbConfig,
  RetrievalTestRequest,
  RetrievalTestResponse,
} from '@/types/knowledge'
import type { ApiResult } from '@/types/import'

// ==================== 知识库 CRUD ====================

export function getKnowledgeList() {
  return request.get<ApiResult<KnowledgeBase[]>>('/knowledge/base/list')
}

export function createKnowledge(data: KnowledgeBaseForm) {
  return request.post<ApiResult<void>>('/knowledge/base', data)
}

export function updateKnowledge(data: KnowledgeBaseForm) {
  return request.put<ApiResult<void>>('/knowledge/base', data)
}

export function deleteKnowledge(code: string) {
  return request.delete<ApiResult<void>>(`/knowledge/base/${code}`)
}

// ==================== 知识库详情 & 配置 ====================

export function getKbFiles(kbCode: string) {
  return request.get<ApiResult<FileInfo[]>>(`/knowledge/kb/${kbCode}/files`)
}

export function updateKbConfig(kbCode: string, data: KbConfig) {
  return request.put<ApiResult<void>>(`/knowledge/kb/${kbCode}/config`, data)
}

// ==================== 文件管理 ====================

export function getFileChunks(fileId: string) {
  return request.get<ApiResult<ChunkDetail[]>>(`/file/${fileId}/chunks`)
}

export function deleteFile(fileId: string) {
  return request.delete<ApiResult<void>>(`/file/${fileId}`)
}

export function reparseFile(fileId: string) {
  return request.post<ApiResult<void>>(`/file/${fileId}/reparse`)
}

// ==================== 检索测试 ====================

export function retrievalTest(data: RetrievalTestRequest) {
  return request.post<ApiResult<RetrievalTestResponse>>('/retrieval/test', data, {
    timeout: 120000,
  })
}
