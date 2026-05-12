/** 知识库实体 */
export interface KnowledgeBase {
  id: number
  name: string
  code: string
  description: string
  embeddingModel: string
  workspaceId: string
  projectCode: string
  scope: string
  dimension: number
  chunkSize: number
  chunkOverlap: number
  splitType: string
  searchMode: string
  topK: number
  similarityThreshold: number
  directReturnEnabled: boolean
  directReturnThreshold: number
  rerankEnabled: boolean
  vectorWeight: number
  keywordWeight: number
  status: number
  fileCount: number
  chunkCount: number
  questionCount: number
  tagCount: number
  hitCount: number
  createTime: string
  updateTime: string
}

/** 知识库创建/编辑表单 */
export interface KnowledgeBaseForm {
  name: string
  code: string
  description: string
  embeddingModel: string
  workspaceId?: string
  projectCode?: string
  scope?: string
  searchMode?: string
  topK?: number
  similarityThreshold?: number
  directReturnEnabled?: boolean
  directReturnThreshold?: number
  rerankEnabled?: boolean
  vectorWeight?: number
  keywordWeight?: number
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
  title?: string
  content: string
  chunkIndex: number
  length: number
  vectorId: string
  hitCount: number
  enabled: number
  createTime: string
}

/** chunk 策略配置 */
export interface KbConfig {
  chunkSize: number
  chunkOverlap: number
  splitType: string
  searchMode?: string
  topK?: number
  similarityThreshold?: number
  directReturnEnabled?: boolean
  directReturnThreshold?: number
  rerankEnabled?: boolean
  vectorWeight?: number
  keywordWeight?: number
}

/** 检索测试请求 */
export interface RetrievalTestRequest {
  query: string
  knowledgeBaseCodes?: string[]
  topK?: number
  scoreThreshold?: number
  searchMode?: string
  rerankEnabled?: boolean
  directReturnEnabled?: boolean
  directReturnThreshold?: number
  vectorWeight?: number
  keywordWeight?: number
  recordHit?: boolean
}

/** 检索结果条目 */
export interface RetrievalItem {
  chunkId: string
  chunkDbId: number
  content: string
  score: number
  vectorScore: number
  keywordScore: number
  rerankScore: number
  fileName: string
  fileId: string
  knowledgeBaseCode: string
  chunkIndex: number
  hitCount: number
  directReturn: boolean
  reason: string
}

/** 检索测试响应 */
export interface RetrievalTestResponse {
  query: string
  searchMode: string
  totalResults: number
  costMs: number
  directReturn: boolean
  directReturnContent?: string
  items: RetrievalItem[]
}

export interface ChunkUpdateForm {
  title?: string
  content?: string
  enabled?: number
}

export interface KnowledgeStats {
  knowledgeBaseCode: string
  fileCount: number
  chunkCount: number
  activeChunkCount: number
  questionCount: number
  tagCount: number
  hitCount: number
}

export interface KnowledgeTag {
  id: number
  targetType: string
  targetId: string
  tagKey: string
  tagValue: string
  tagGroup: string
  color: string
  description?: string
  parentId?: number
  sortOrder?: number
  createTime: string
}

export interface KnowledgeTagForm {
  targetType?: string
  targetId?: string
  tagKey: string
  tagValue: string
  tagGroup?: string
  color?: string
  description?: string
  parentId?: number
  sortOrder?: number
}

export interface KnowledgeTagBatchForm {
  targetType: string
  targetIds: string[]
  tagKey: string
  tagValue: string
  tagGroup?: string
  color?: string
  description?: string
  parentId?: number
  sortOrder?: number
}

export interface KnowledgeTagStats {
  tagKey: string
  tagValue: string
  tagGroup: string
  color: string
  description?: string
  parentId?: number
  sortOrder?: number
  totalCount: number
  knowledgeCount: number
  fileCount: number
  chunkCount: number
}

export interface KnowledgeQuestion {
  id: number
  chunkId?: number
  question: string
  hitCount: number
  source: string
  createTime: string
  updateTime: string
}

export interface KnowledgeQuestionForm {
  chunkId?: number
  question: string
  source?: string
}

export interface KnowledgeHitLog {
  id: number
  chunkId?: number
  queryText: string
  searchMode?: string
  score?: number
  directReturn: boolean
  fileId?: string
  fileName?: string
  chunkIndex?: number
  userId?: string
  traceId?: string
  createTime: string
}

export interface PipelineStepStatus {
  name: string
  label: string
  status: 'done' | 'running' | 'failed'
  durationMs?: number
}

export interface PipelineFileStatus {
  fileId: string
  fileName: string
  status: number
  chunkCount: number
  steps: PipelineStepStatus[]
}

export interface KnowledgeOpsDashboard {
  stats: KnowledgeStats
  recentFiles: PipelineFileStatus[]
  hotChunks: ChunkDetail[]
  zeroHitChunks: ChunkDetail[]
  recentHits: KnowledgeHitLog[]
  lowConfidenceHits: KnowledgeHitLog[]
}
