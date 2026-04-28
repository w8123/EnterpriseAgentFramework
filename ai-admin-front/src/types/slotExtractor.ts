export interface SlotExtractorInfo {
  name: string
  displayName: string
  priority: number
  metadata: Record<string, unknown>
}

export interface SlotExtractorTestRequest {
  userText: string
  fieldKey?: string
  fieldLabel?: string
  fieldType?: string
  llmExtractHint?: string
  userId?: string
  userDeptId?: string
}

export interface SlotExtractorTestResultRow {
  extractorName: string
  displayName: string
  accepts: boolean
  hit: boolean
  value?: unknown
  confidence?: number
  evidence?: string
  latencyMs?: number
  error?: string
}

export interface SlotExtractorTestResponse {
  field: { key: string; label: string; type: string }
  userText: string
  results: SlotExtractorTestResultRow[]
}

export interface SlotExtractorMetric {
  extractorName: string
  total: number
  hit: number
  hitRate: number
  avgConfidence: number
  p95LatencyMs: number
}

export interface SlotDeptRow {
  id?: number
  parentId?: number | null
  name: string
  pinyin?: string | null
  aliases?: string | null
  projectScope?: number | null
  enabled?: boolean
  createdAt?: string
  updatedAt?: string
}

export interface SlotUserRow {
  id?: number
  deptId?: number | null
  name: string
  pinyin?: string | null
  employeeNo?: string | null
  aliases?: string | null
  enabled?: boolean
  createdAt?: string
  updatedAt?: string
}

export interface SlotExtractLogRow {
  id?: number
  traceId?: string
  skillName?: string
  fieldKey?: string
  extractorName: string
  hit: boolean
  value?: string
  confidence?: number
  evidence?: string
  userText?: string
  latencyMs?: number
  createTime?: string
}

export interface FieldExtractorBindingRow {
  id?: number
  skillName: string
  fieldKey: string
  extractorNamesJson: string
}
