import type { ToolInfo, ToolParameter } from './tool'

/** 交互式表单能力（Interactive Form Capability）的 spec_json 结构（与后端 InteractiveFormSpec 对齐） */
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

/** 编辑能力 / 交互表单时「数据来源」单选项的展示文案（提交值仍为后端枚举） */
export const FIELD_SOURCE_KIND_OPTIONS: { value: FieldSourceKind; label: string }[] = [
  { value: 'NONE', label: '无（自由输入）' },
  { value: 'STATIC', label: '静态选项' },
  { value: 'DICT', label: '数据字典' },
  { value: 'TOOL_CALL', label: '字典接口' },
]

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
  const key = String(x.key ?? '')
  const type = String(x.type ?? 'text')
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
  if (Array.isArray(chRaw)) {
    const normalizedChildren = (chRaw as unknown[]).map((c) => normalizeFieldSpec(c))
    const typeKey = type.trim().toLowerCase()
    const keepsEmptyChildren =
      key.trim() === 'body_json' || typeKey === 'object' || typeKey === 'json' || typeKey === 'map'
    children = normalizedChildren.length > 0 ? normalizedChildren : keepsEmptyChildren ? [] : undefined
  }
  return {
    key,
    label: String(x.label ?? ''),
    type,
    required: Boolean(x.required),
    source,
    validateRegex: x.validateRegex != null ? String(x.validateRegex) : undefined,
    llmExtractHint: x.llmExtractHint != null ? String(x.llmExtractHint) : undefined,
    defaultValue: x.defaultValue,
    children,
  }
}

function validateFieldTree(
  fields: FieldSpec[] | undefined,
  ctx: string,
  keyFirstSeen: Map<string, string>,
): string | null {
  if (!fields?.length) return `${ctx}：至少需要一个字段`
  for (let i = 0; i < fields.length; i++) {
    const f = fields[i]
    const loc = `${ctx} 第 ${i + 1} 项`
    if (!f.key?.trim()) return `${loc}：key 不能为空`
    const k = f.key.trim()
    const prev = keyFirstSeen.get(k)
    if (prev != null) {
      return `字段 key 在全树中重复：${k}（首次：${prev}；再次：${loc}）。说明：分组父节点与任意子节点、或不同分支上的叶子也不能共用同一 key。`
    }
    keyFirstSeen.set(k, loc)
    if (!f.label?.trim()) return `「${k}」：label 不能为空`
    const isEmptyObjectGroup = Array.isArray(f.children) && f.children.length === 0
    if (isEmptyObjectGroup) {
      continue
    }
    const hasCh = f.children && f.children.length > 0
    if (hasCh) {
      const sub = validateFieldTree(f.children!, `「${k}」子字段`, keyFirstSeen)
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

/**
 * 扫描器会把 HTTP 响应体展开为 location=RESPONSE 的参数子树（根名多为「返回值」），供图谱/文档；
 * 交互式表单只收集调用 Tool 的入参，映射字段时应排除。
 */
export function isToolInputParameter(p: ToolParameter): boolean {
  const loc = (p.location ?? '').trim().toUpperCase()
  return loc !== 'RESPONSE'
}

/** 将 Tool 参数定义递归映射为 FieldSpec 树（与 mapToolToFields 一致逻辑，供测试或复用） */
export function mapToolParameterToField(p: ToolParameter): FieldSpec {
  const inputChildren = (p.children || []).filter(isToolInputParameter)
  const kids = inputChildren.length > 0 ? inputChildren.map(mapToolParameterToField) : undefined
  const key = toolParameterFieldKey(p)
  const label = (p.description && p.description.trim()) || key
  if (kids && kids.length > 0) {
    return {
      key,
      label,
      type: 'text',
      required: Boolean(p.required),
      source: emptyFieldSource('NONE'),
      children: kids,
    }
  }
  const t = (p.type || '').toLowerCase()
  const emptyBodyLike =
    key === 'body_json' || isBodyParameter(p) || t === 'object' || t === 'json' || t === 'map'
  if (emptyBodyLike) {
    return {
      key,
      label,
      type: 'text',
      required: Boolean(p.required),
      source: emptyFieldSource('NONE'),
      children: [],
    }
  }
  return mapToolParameterLeaf(p)
}

function isBodyParameter(p: ToolParameter): boolean {
  return (p.location ?? '').trim().toUpperCase() === 'BODY'
}

function toolParameterFieldKey(p: ToolParameter): string {
  const rawName = String((p as ToolParameter & { name?: string | null }).name ?? '').trim()
  if (rawName) return rawName
  if (isBodyParameter(p)) return 'body_json'
  const loc = (p.location ?? '').trim().toLowerCase()
  return loc ? `${loc}_param` : 'param'
}

function mapToolParameterLeaf(p: ToolParameter): FieldSpec {
  const t = (p.type || 'string').toLowerCase()
  const key = toolParameterFieldKey(p)
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
    key,
    label: (p.description && p.description.trim()) || key,
    type,
    required: Boolean(p.required),
    source,
  }
}

/** 根据已选 Tool 的 parameters 生成表单字段树（用于 targetTool 联动） */
export function mapToolToFields(tool: ToolInfo): FieldSpec[] {
  return (tool.parameters || []).filter(isToolInputParameter).map(mapToolParameterToField)
}

/** 返回错误文案；null 表示通过 */
export function validateInteractiveFormSpec(spec: InteractiveFormSpec | null): string | null {
  if (!spec) return 'Spec 不能为空'
  if (!spec.targetTool?.trim()) return '请选择或填写 targetTool（最终调用的 Tool）'
  if (!spec.fields?.length) return '至少需要一个表单字段'
  const keyFirstSeen = new Map<string, string>()
  const treeErr = validateFieldTree(spec.fields, '顶层', keyFirstSeen)
  if (treeErr) return treeErr
  const bs = spec.batchSize
  if (bs != null && (bs < 1 || bs > 10)) return 'batchSize 应在 1～10 之间'
  return null
}

/** SubAgent 形态能力的专属 spec（产品语义：子智能体能力） */
export interface SubAgentSpec {
  systemPrompt: string
  toolWhitelist: string[]
  llmProvider?: string | null
  llmModel?: string | null
  maxSteps?: number
  useMultiAgentModel?: boolean
}

/** 后端 JSON 仍使用 skillKind 字段名（legacy API naming），产品语义为能力形态 */
export interface CapabilityInfo {
  name: string
  description: string
  aiDescription?: string | null
  parameters: ToolParameter[]
  skillKind: string
  sideEffect?: string | null
  projectId?: number | null
  projectCode?: string | null
  visibility?: 'PRIVATE' | 'PROJECT' | 'SHARED' | 'PUBLIC'
  qualifiedName?: string | null
  enabled: boolean
  agentVisible: boolean
  source?: string | null
  draft?: boolean
  spec?: SubAgentSpec | Record<string, unknown> | null
}

export interface CapabilityUpsertRequest {
  name: string
  description: string
  parameters: ToolParameter[]
  skillKind: string
  sideEffect?: string | null
  projectId?: number | null
  projectCode?: string | null
  visibility?: 'PRIVATE' | 'PROJECT' | 'SHARED' | 'PUBLIC'
  qualifiedName?: string | null
  enabled: boolean
  agentVisible: boolean
  spec: SubAgentSpec | Record<string, unknown>
  draft?: boolean
}

export interface CapabilityListQuery {
  current?: number
  size?: number
  keyword?: string
  enabled?: boolean
  draft?: boolean
  projectId?: number
}

export interface CapabilityPageResult {
  records: CapabilityInfo[]
  total: number
  size: number
  current: number
  pages: number
}

/** 与后端 CapabilityController / SkillController 的测试结果 DTO 对齐 */
export interface CapabilityTestResult {
  success: boolean
  result: string
  errorMessage?: string
  durationMs: number
  interactionPending?: boolean
  interactionId?: string | null
  uiRequest?: Record<string, unknown> | null
}

/** 后端 JSON 仍使用 skillName（legacy）；管理端测试会话 userId=skill-admin-test */
export interface CapabilityAdminTestPendingItem {
  interactionId: string
  skillName: string
  status: string
  createdAt?: string | null
  updatedAt?: string | null
  expiresAt?: string | null
  uiTitle?: string | null
}

export interface CapabilityMetricPoint {
  day: string
  callCount: number
  successRate: number
  p95LatencyMs: number
  p95TokenCost: number
}

export interface CapabilityMetrics {
  p50LatencyMs: number
  p95LatencyMs: number
  p50TokenCost: number
  p95TokenCost: number
  callCount: number
  successRate: number
  trends: CapabilityMetricPoint[]
}

/** 副作用枚举：页面展示用（提交值仍为后端枚举） */
export const SIDE_EFFECT_LABELS: Record<string, string> = {
  NONE: '无副作用',
  READ_ONLY: '只读',
  IDEMPOTENT_WRITE: '幂等写',
  WRITE: '普通写',
  IRREVERSIBLE: '不可逆',
}

export function formatSideEffectLabel(effect?: string | null): string {
  const k = (effect ?? '').trim()
  if (!k) return SIDE_EFFECT_LABELS.WRITE
  return SIDE_EFFECT_LABELS[k] ?? effect ?? '-'
}

/** 能力形态：页面展示用 */
export const SKILL_KIND_LABELS: Record<string, string> = {
  SUB_AGENT: '子智能体能力',
  INTERACTIVE_FORM: '交互式表单能力',
}

export function formatSkillKindLabel(kind?: string | null): string {
  const k = (kind ?? '').trim()
  if (!k) return SKILL_KIND_LABELS.SUB_AGENT
  return SKILL_KIND_LABELS[k] ?? kind ?? '-'
}

export const SIDE_EFFECT_OPTIONS = [
  { value: 'NONE', label: '无副作用' },
  { value: 'READ_ONLY', label: '只读' },
  { value: 'IDEMPOTENT_WRITE', label: '幂等写' },
  { value: 'WRITE', label: '普通写' },
  { value: 'IRREVERSIBLE', label: '不可逆' },
] as const

export const CAPABILITY_KIND_OPTIONS = [
  { value: 'SUB_AGENT', label: '子智能体能力' },
  { value: 'INTERACTIVE_FORM', label: '交互式表单能力' },
] as const
