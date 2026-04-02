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
  enableOcr: boolean
  tags: string[]
  deptId: string
  overwrite: boolean
}

/** 单个 Chunk 条目 */
export interface ChunkItem {
  index: number
  content: string
  length: number
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

/** Pipeline 执行结果 */
export interface PipelineResult {
  fileId: string
  knowledgeBaseCode: string
  chunkCount: number
  vectorCount: number
  stepDurations: Record<string, number>
  status: 'SUCCESS' | 'FAILED' | 'ABORTED'
  errorMessage: string | null
}

/** 统一响应结构 */
export interface ApiResult<T = unknown> {
  code: number
  message: string
  data: T
}
