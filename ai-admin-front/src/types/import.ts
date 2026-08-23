/** 切分策略枚举 */
export type ChunkStrategyType = 'fixed_length' | 'paragraph' | 'semantic'

/** 切分策略配置 */
export interface ChunkConfig {
  chunkStrategy: ChunkStrategyType
  chunkSize: number
  chunkOverlap: number
}

/** 高级参数 */
export interface ExtraParams {
  tags: string[]
  deptId: string
  overwrite: boolean
}

/** 单个 Chunk 条目 */
export interface ChunkItem {
  index: number
  content: string
  length: number
  elementType?: string | null
  sectionPath?: string | null
  sourceLocatorJson?: string | null
}

/** Chunk 预览响应 */
export interface ChunkPreviewResponse {
  fileName: string
  chunkStrategy: string
  chunkSize: number
  chunkOverlap: number
  totalChunks: number
  chunks: ChunkItem[]
}

export type DocumentImportJobStatus =
  | 'QUEUED'
  | 'PARSING'
  | 'PARSED'
  | 'INDEXING'
  | 'COMPLETED'
  | 'RETRY_WAIT'
  | 'FAILED'
  | 'CANCELLED'

/** One upload is retained while parsing, preview and formal indexing share it. */
export interface DocumentImportJob {
  jobId: string
  fileId: string
  replaceFileId?: string | null
  knowledgeBaseCode: string
  fileName: string
  fileType: string
  providerType: 'JAVA_FAST' | 'DOCLING'
  providerVersion?: string | null
  status: DocumentImportJobStatus
  stage: string
  attemptCount: number
  maxAttempts: number
  autoCommit: boolean
  errorCode?: string | null
  errorMessage?: string | null
  parsedAt?: string | null
  completedAt?: string | null
  createTime?: string | null
  updateTime?: string | null
  preview?: ChunkPreviewResponse | null
}

/** 统一响应结构 */
export interface ApiResult<T = unknown> {
  code: number
  message: string
  data: T
}
