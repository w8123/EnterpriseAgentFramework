export type KnowledgeAccessMode = 'ALL_CALLERS' | 'FILE_GRANTS'

/** 知识库实体 */
export interface KnowledgeBase {
  id: number
  name: string
  code: string
  description: string
  embeddingModelInstanceId: string
  rerankModelInstanceId?: string
  llmModelInstanceId: string
  workspaceId: string
  projectCode: string
  scope: string
  /** ALL_CALLERS：调用方都能检索；FILE_GRANTS：仅逐文件授权的用户。 */
  accessMode: KnowledgeAccessMode
  dimension: number
  chunkSize: number
  chunkOverlap: number
  splitType: string
  searchMode: string
  topK: number
  similarityThreshold: number
  rerankEnabled: boolean
  vectorWeight: number
  keywordWeight: number
  status: number
  fileCount: number
  chunkCount: number
  hitCount: number
  createTime: string
  updateTime: string
}

/** 知识库创建/编辑表单 */
export interface KnowledgeBaseForm {
  name: string
  code: string
  description: string
  embeddingModelInstanceId: string
  rerankModelInstanceId?: string
  llmModelInstanceId: string
  workspaceId?: string
  projectCode?: string
  scope?: string
  accessMode?: KnowledgeAccessMode
  searchMode?: string
  topK?: number
  similarityThreshold?: number
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
  parseProvider?: string
  parseProviderVersion?: string
  importJobId?: string
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
  elementType?: string
  sectionPath?: string
  sourceLocatorJson?: string
  createTime: string
}

/** chunk 策略配置 */
export interface KbConfig {
  chunkSize: number
  chunkOverlap: number
  splitType: string
  accessMode?: KnowledgeAccessMode
  searchMode?: string
  topK?: number
  similarityThreshold?: number
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
  reason: string
}

/** 检索测试响应 */
export interface RetrievalTestResponse {
  query: string
  searchMode: string
  totalResults: number
  costMs: number
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
  hitCount: number
}

export interface KnowledgeHitLog {
  id: number
  chunkId?: number
  queryText: string
  searchMode?: string
  score?: number
  /** RUNTIME：真实对话；CONSOLE：管理端检索测试。 */
  source?: 'RUNTIME' | 'CONSOLE'
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

/** 一个用到某知识库的 Workflow，以及当前运行它的 Agent。 */
export interface KnowledgeWorkflowUsage {
  id: string
  name: string
  keySlug: string
  status: string
  projectId?: number
  projectCode?: string
  agents: Array<{ id: string; name: string; projectCode?: string }>
}

export interface KnowledgeUsage {
  knowledgeBaseCode: string
  workflows: KnowledgeWorkflowUsage[]
  /** 调用方没有读取权限的项目里的 Workflow 数量。 */
  hiddenWorkflowCount?: number
}

export interface KnowledgeQaAttachResult {
  knowledgeBaseCode: string
  workflowId: string
  workflowKeySlug: string
  workflowName: string
  workflowCreated: boolean
  agentId: string
  agentName: string
  agentConfigVersion?: number
  toolName?: string | null
  alreadyAttached: boolean
  /** 加入 Agent 时使用的调用说明（含出处转述规则）。 */
  toolDescription?: string
}
