/** 知识库实体 */
export interface KnowledgeBase {
  id: number
  name: string
  code: string
  description: string
  embeddingModel: string
  dimension: number
  chunkSize: number
  chunkOverlap: number
  splitType: string
  status: number
  fileCount: number
  createTime: string
  updateTime: string
}

/** 知识库创建/编辑表单 */
export interface KnowledgeBaseForm {
  name: string
  code: string
  description: string
  embeddingModel: string
}

/** 文件信息 */
export interface FileInfo {
  id: number
  fileId: string
  fileName: string
  fileType: string
  fileSize: number
  chunkCount: number
  status: number
  createTime: string
  updateTime: string
}

/** Chunk 详细信息 */
export interface ChunkDetail {
  id: number
  fileId: string
  content: string
  chunkIndex: number
  length: number
  vectorId: string
  createTime: string
}

/** chunk 策略配置 */
export interface KbConfig {
  chunkSize: number
  chunkOverlap: number
  splitType: string
}

/** 检索测试请求 */
export interface RetrievalTestRequest {
  query: string
  knowledgeBaseCodes?: string[]
  topK?: number
  scoreThreshold?: number
}

/** 检索结果条目 */
export interface RetrievalItem {
  chunkId: string
  content: string
  score: number
  fileName: string
  fileId: string
  knowledgeBaseCode: string
  chunkIndex: number
}

/** 检索测试响应 */
export interface RetrievalTestResponse {
  query: string
  totalResults: number
  costMs: number
  items: RetrievalItem[]
}
