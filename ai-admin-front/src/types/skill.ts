import type { ToolInfo, ToolParameter } from './tool'

/** InteractiveFormSkill 的 spec_json 结构（与后端 InteractiveFormSpec 对齐） */
export type FieldSourceKind = 'NONE' | 'STATIC' | 'DICT' | 'TOOL_CALL'

export interface FieldOptionSpec {
  value: string
  label: string
}

export interface FieldSourceSpec {
  kind: FieldSourceKind
  options?: FieldOptionSpec[]
  dictCode?: string
  toolName?: string
  toolArgs?: Record<string, unknown>
  valueField?: string
  labelField?: string
}

export interface FieldSpec {
  key: string
  label: string
  type: string
  required: boolean
  source: FieldSourceSpec
  validateRegex?: string
  llmExtractHint?: string
  defaultValue?: unknown
  /** 非空时为分组节点，提交 targetTool 时按树组装嵌套参数 */
  children?: FieldSpec[]
}

export interface InteractiveFormSpec {
  targetTool: string
  fields: FieldSpec[]
  batchSize?: number
  confirmTitle?: string
  successTemplate?: string
}

export function emptyFieldSource(kind: FieldSourceKind = 'NONE'): FieldSourceSpec {
  switch (kind) {
    case 'STATIC':
      return { kind: 'STATIC', options: [] }
    case 'DICT':
      return { kind: 'DICT', dictCode: '' }
    case 'TOOL_CALL':
      return {
        kind: 'TOOL_CALL',
        toolName: '',
        toolArgs: {},
        valueField: 'id',
        labelField: 'name',
      }
    default:
      return { kind: 'NONE' }
  }
}

export function defaultInteractiveFormSpec(): InteractiveFormSpec {
  return {
    targetTool: '',
    batchSize: 2,
    confirmTitle: '',
    successTemplate: '',
    fields: [],
  }
}

/** normalize 遇到损坏子节点时的占位叶子（不绑定任何业务 Tool） */
const NORMALIZE_FIELD_FALLBACK: FieldSpec = {
  key: 'field',
  label: '字段',
  type: 'text',
  required: false,
  source: emptyFieldSource('NONE'),
}

/** 规范化从后端读入的 loose 对象 */
export function normalizeInteractiveFormSpec(raw: unknown): InteractiveFormSpec {
  const d = defaultInteractiveFormSpec()
  if (!raw || typeof raw !== 'object') return d
  const o = raw as Record<string, unknown>
  const fieldsRaw = o.fields
  const fields: FieldSpec[] = Array.isArray(fieldsRaw)
    ? fieldsRaw.map((fr) => normalizeFieldSpec(fr))
    : d.fields
  return {
    targetTool: typeof o.targetTool === 'string' ? o.targetTool : d.targetTool,
    fields,
    batchSize: typeof o.batchSize === 'number' ? o.batchSize : d.batchSize,
    confirmTitle: typeof o.confirmTitle === 'string' ? o.confirmTitle : d.confirmTitle,
    successTemplate: typeof o.successTemplate === 'string' ? o.successTemplate : d.successTemplate,
  }
}

function normalizeFieldSpec(fr: unknown): FieldSpec {
  if (!fr || typeof fr !== 'object') return { ...NORMALIZE_FIELD_FALLBACK }
  const x = fr as Record<string, unknown>
  const src = x.source
  let source: FieldSourceSpec = emptyFieldSource()
  if (src && typeof src === 'object') {
    const s = src as Record<string, unknown>
    const kind = (s.kind as string)?.toUpperCase()
    if (kind === 'STATIC') {
      source = {
        kind: 'STATIC',
        options: Array.isArray(s.options)
          ? (s.options as FieldOptionSpec[]).map((o) => ({
              value: String((o as FieldOptionSpec).value ?? ''),
              label: String((o as FieldOptionSpec).label ?? ''),
            }))
          : [],
      }
    } else if (kind === 'DICT') {
      source = { kind: 'DICT', dictCode: String(s.dictCode ?? '') }
    } else if (kind === 'TOOL_CALL') {
      source = {
        kind: 'TOOL_CALL',
        toolName: String(s.toolName ?? ''),
        toolArgs: (s.toolArgs as Record<string, unknown>) ?? {},
        valueField: String(s.valueField ?? 'id'),
        labelField: String(s.labelField ?? 'name'),
      }
    } else {
      source = emptyFieldSource('NONE')
    }
  }
  const chRaw = x.children
  let children: FieldSpec[] | undefined
  if (Array.isArray(chRaw) && chRaw.length > 0) {
    children = (chRaw as unknown[]).map((c) => normalizeFieldSpec(c))
  }
  return {
    key: String(x.key ?? ''),
    label: String(x.label ?? ''),
    type: String(x.type ?? 'text'),
    required: Boolean(x.required),
    source,
    validateRegex: x.validateRegex != null ? String(x.validateRegex) : undefined,
    llmExtractHint: x.llmExtractHint != null ? String(x.llmExtractHint) : undefined,
    defaultValue: x.defaultValue,
    children,
  }
}

function validateFieldTree(fields: FieldSpec[] | undefined, ctx: string, allKeys: Set<string>): string | null {
  if (!fields?.length) return `${ctx}：至少需要一个字段`
  for (let i = 0; i < fields.length; i++) {
    const f = fields[i]
    const loc = `${ctx} 第 ${i + 1} 项`
    if (!f.key?.trim()) return `${loc}：key 不能为空`
    const k = f.key.trim()
    if (allKeys.has(k)) return `字段 key 在全树中重复：${k}`
    allKeys.add(k)
    if (!f.label?.trim()) return `「${k}」：label 不能为空`
    const hasCh = f.children && f.children.length > 0
    if (hasCh) {
      const sub = validateFieldTree(f.children, `「${k}」子字段`, allKeys)
      if (sub) return sub
      continue
    }
    if (!f.type?.trim()) return `「${k}」：type 不能为空`
    const sk = f.source?.kind
    if (sk === 'DICT' && !f.source.dictCode?.trim()) return `字段「${k}」：字典来源需填写 dictCode`
    if (sk === 'TOOL_CALL') {
      if (!f.source.toolName?.trim()) return `字段「${k}」：TOOL_CALL 需选择 toolName`
      if (!f.source.valueField?.trim()) return `字段「${k}」：请填写 valueField`
      if (!f.source.labelField?.trim()) return `字段「${k}」：请填写 labelField`
    }
  }
  return null
}

/** 将 Tool 参数定义递归映射为 FieldSpec 树（与 mapToolToFields 一致逻辑，供测试或复用） */
export function mapToolParameterToField(p: ToolParameter): FieldSpec {
  const kids = p.children && p.children.length > 0 ? p.children.map(mapToolParameterToField) : undefined
  if (kids && kids.length > 0) {
    return {
      key: p.name,
      label: (p.description && p.description.trim()) || p.name,
      type: 'text',
      required: Boolean(p.required),
      source: emptyFieldSource('NONE'),
      children: kids,
    }
  }
  return mapToolParameterLeaf(p)
}

function mapToolParameterLeaf(p: ToolParameter): FieldSpec {
  const t = (p.type || 'string').toLowerCase()
  let type = 'text'
  let source: FieldSourceSpec = emptyFieldSource('NONE')
  if (t === 'integer' || t === 'number' || t === 'long' || t === 'double' || t === 'float') {
    type = 'number'
  } else if (t === 'boolean' || t === 'bool') {
    type = 'radio'
    source = {
      kind: 'STATIC',
      options: [
        { value: 'true', label: '是' },
        { value: 'false', label: '否' },
      ],
    }
  } else if (t === 'array') {
    type = 'multi_select'
  }
  return {
    key: p.name,
    label: (p.description && p.description.trim()) || p.name,
    type,
    required: Boolean(p.required),
    source,
  }
}

/** 根据已选 Tool 的 parameters 生成表单字段树（用于 targetTool 联动） */
export function mapToolToFields(tool: ToolInfo): FieldSpec[] {
  return (tool.parameters || []).map(mapToolParameterToField)
}

/** 返回错误文案；null 表示通过 */
export function validateInteractiveFormSpec(spec: InteractiveFormSpec | null): string | null {
  if (!spec) return 'Spec 不能为空'
  if (!spec.targetTool?.trim()) return '请选择或填写 targetTool（最终调用的 Tool）'
  if (!spec.fields?.length) return '至少需要一个表单字段'
  const allKeys = new Set<string>()
  const treeErr = validateFieldTree(spec.fields, '顶层', allKeys)
  if (treeErr) return treeErr
  const bs = spec.batchSize
  if (bs != null && (bs < 1 || bs > 10)) return 'batchSize 应在 1～10 之间'
  return null
}

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
  /** 草稿未完成发布前不可执行、不可启用 */
  draft?: boolean
  /** SUB_AGENT 为 SubAgentSpec；INTERACTIVE_FORM 为 InteractiveFormSpec 对象 */
  spec?: SubAgentSpec | Record<string, unknown> | null
}

export interface SkillUpsertRequest {
  name: string
  description: string
  parameters: ToolParameter[]
  skillKind: string
  sideEffect?: string | null
  enabled: boolean
  agentVisible: boolean
  spec: SubAgentSpec | Record<string, unknown>
  /** true 为暂存（后端放宽校验、强制禁用、不可执行） */
  draft?: boolean
}

export interface SkillListQuery {
  current?: number
  size?: number
  keyword?: string
  enabled?: boolean
  /** 仅草稿 / 非草稿；不传查全部 */
  draft?: boolean
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
  { value: 'INTERACTIVE_FORM', label: 'INTERACTIVE_FORM（交互式表单）' },
] as const
