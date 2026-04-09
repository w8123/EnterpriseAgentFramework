/** 字段定义中的单个字段 */
export interface FieldDef {
  name: string
  label: string
  type: string
  required: boolean
  indexed: boolean
}

/** 业务索引实体 */
export interface BizIndex {
  id: number
  indexCode: string
  indexName: string
  sourceSystem: string
  textTemplate: string
  fieldSchema: string
  embeddingModel: string
  dimension: number
  chunkSize: number
  chunkOverlap: number
  splitType: string
  status: string
  remark: string
  recordCount: number
  attachmentChunkCount: number
  createTime: string
  updateTime: string
}

/** 业务索引创建/编辑表单 */
export interface BizIndexForm {
  indexCode: string
  indexName: string
  sourceSystem: string
  textTemplate: string
  fieldSchema: string
  embeddingModel?: string
  dimension?: number
  chunkSize?: number
  chunkOverlap?: number
  splitType?: string
  remark?: string
}

/** 索引统计 */
export interface BizIndexStats {
  indexCode: string
  indexName: string
  recordCount: number
  attachmentRecordCount: number
  attachmentChunkCount: number
  totalVectorCount: number
}

/** 搜索请求 */
export interface BizSearchRequest {
  query: string
  topK?: number
  scoreThreshold?: number
  filters?: Record<string, string[]>
  includeAttachmentMatch?: boolean
}

/** 搜索结果条目 */
export interface BizSearchItem {
  bizId: string
  bizType: string | null
  score: number
  matchSource: string
  matchFileName: string | null
  matchContent: string
  metadata: Record<string, unknown> | null
}

/** 搜索响应 */
export interface BizSearchResponse {
  results: BizSearchItem[]
  total: number
  costMs: number
}
