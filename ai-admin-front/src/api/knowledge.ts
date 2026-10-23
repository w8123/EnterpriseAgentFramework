import request, { controlRequest } from './request'
import type {
  KnowledgeBase,
  KnowledgeBaseForm,
  FileInfo,
  ChunkDetail,
  KbConfig,
  RetrievalTestRequest,
  RetrievalTestResponse,
  KnowledgeStats,
  ChunkUpdateForm,
  KnowledgeHitLog,
  KnowledgeOpsDashboard,
  KnowledgeQaAttachResult,
  KnowledgeUsage,
} from '@/types/knowledge'
import type { ApiResult } from '@/types/import'
import type { DocumentImportJob } from '@/types/import'

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

export function getKnowledgeStats(kbCode: string) {
  return request.get<ApiResult<KnowledgeStats>>(`/knowledge/kb/${kbCode}/stats`)
}

export function getKnowledgeOpsDashboard(kbCode: string) {
  return request.get<ApiResult<KnowledgeOpsDashboard>>(`/knowledge/kb/${kbCode}/ops-dashboard`)
}

export function getKbChunks(kbCode: string, params?: { keyword?: string; enabled?: number; limit?: number }) {
  return request.get<ApiResult<ChunkDetail[]>>(`/knowledge/kb/${kbCode}/chunks`, { params })
}

export function getKnowledgeHitLogs(kbCode: string, params?: { limit?: number; lowConfidenceOnly?: boolean; source?: 'RUNTIME' | 'CONSOLE' }) {
  return request.get<ApiResult<KnowledgeHitLog[]>>(`/knowledge/kb/${kbCode}/hit-logs`, { params })
}

// ==================== 文件管理 ====================

export function getFileChunks(fileId: string) {
  return request.get<ApiResult<ChunkDetail[]>>(`/file/${fileId}/chunks`)
}

export function updateChunk(chunkId: number, data: ChunkUpdateForm) {
  return request.put<ApiResult<ChunkDetail>>(`/knowledge/chunks/${chunkId}`, data)
}

export function toggleChunk(chunkId: number, enabled: number) {
  return request.post<ApiResult<ChunkDetail>>(`/knowledge/chunks/${chunkId}/toggle`, null, {
    params: { enabled },
  })
}

export function reembedChunk(chunkId: number) {
  return request.post<ApiResult<void>>(`/knowledge/chunks/${chunkId}/reembed`, null, {
    timeout: 120000,
  })
}

export function deleteFile(fileId: string) {
  return request.delete<ApiResult<void>>(`/file/${fileId}`)
}

export function reparseFile(fileId: string) {
  return controlRequest.post<ApiResult<DocumentImportJob>>(`/api/knowledge/import-files/${fileId}/reparse`)
}

// ==================== 检索测试 ====================

export function retrievalTest(data: RetrievalTestRequest) {
  return request.post<ApiResult<RetrievalTestResponse>>('/retrieval/test', data, {
    timeout: 120000,
  })
}

// ==================== 接入 Agent ====================

/** 用到这些知识库的 Workflow 及运行它们的 Agent（Runtime 只读视图）。 */
export function getKnowledgeUsage(knowledgeBaseCodes: string[]) {
  // 没有 Workflow 读取权限时静默处理：页面只是不显示使用情况。
  return controlRequest.get<KnowledgeUsage[]>('/api/workflows/knowledge-usage', {
    params: { knowledgeBaseCodes: knowledgeBaseCodes.join(',') },
    errorFeedback: 'local',
  })
}

/** 生成并发布本知识库的知识问答 Workflow，再加入 Agent 的可调用 Workflow 并发布。 */
export function attachKnowledgeToAgent(agentId: string, data: {
  knowledgeBaseCode: string
  knowledgeBaseName?: string
  modelInstanceId?: string
  publishedBy?: string
  /** false：只准备好知识问答 Workflow，不改 Agent 配置（配置页把它加入草稿）。 */
  attachToAgent?: boolean
}) {
  return controlRequest.post<KnowledgeQaAttachResult>(
    `/api/agents/${encodeURIComponent(agentId)}/knowledge-qa`, data, { timeout: 60000 })
}
