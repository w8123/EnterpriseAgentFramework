import type { ToolParameter } from './tool'

/** SubAgent 形态的 Skill 专属 spec */
export interface SubAgentSpec {
  systemPrompt: string
  toolWhitelist: string[]
  llmProvider?: string | null
  llmModel?: string | null
  maxSteps?: number
  useMultiAgentModel?: boolean
}

export interface SkillInfo {
  name: string
  description: string
  aiDescription?: string | null
  parameters: ToolParameter[]
  skillKind: string
  sideEffect?: string | null
  enabled: boolean
  agentVisible: boolean
  source?: string | null
  spec?: SubAgentSpec | null
}

export interface SkillUpsertRequest {
  name: string
  description: string
  parameters: ToolParameter[]
  skillKind: string
  sideEffect?: string | null
  enabled: boolean
  agentVisible: boolean
  spec: SubAgentSpec
}

export interface SkillListQuery {
  current?: number
  size?: number
  keyword?: string
  enabled?: boolean
}

export interface SkillPageResult {
  records: SkillInfo[]
  total: number
  size: number
  current: number
  pages: number
}

export interface SkillTestResult {
  success: boolean
  result: string
  errorMessage?: string
  durationMs: number
}

export interface SkillMetricPoint {
  day: string
  callCount: number
  successRate: number
  p95LatencyMs: number
  p95TokenCost: number
}

export interface SkillMetrics {
  p50LatencyMs: number
  p95LatencyMs: number
  p50TokenCost: number
  p95TokenCost: number
  callCount: number
  successRate: number
  trends: SkillMetricPoint[]
}

export const SIDE_EFFECT_OPTIONS = [
  { value: 'NONE', label: 'NONE（无副作用）' },
  { value: 'READ_ONLY', label: 'READ_ONLY（只读）' },
  { value: 'IDEMPOTENT_WRITE', label: 'IDEMPOTENT_WRITE（幂等写）' },
  { value: 'WRITE', label: 'WRITE（普通写）' },
  { value: 'IRREVERSIBLE', label: 'IRREVERSIBLE（不可逆）' },
] as const

export const SKILL_KIND_OPTIONS = [
  { value: 'SUB_AGENT', label: 'SUB_AGENT（子 Agent 封装）' },
] as const
