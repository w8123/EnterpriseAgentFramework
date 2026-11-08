/**
 * 「交给我的 AI」提示词登记表。
 *
 * 每个功能一个文件，放在 ./prompts/<key>.ts，默认导出一个 AiPromptScenario；
 * 这里用 import.meta.glob 自动收集，新增场景不用改本文件，也不会和别的 PR 冲突。
 * 所有场景共用同一个 AI 工具连接（「AI 工具接入」页创建的平台连接，或员工连接），
 * 只是给 AI 的提示词不同。
 */

export type AiPromptCategory =
  | 'onboarding'
  | 'page'
  | 'embed'
  | 'api'
  | 'workflow'
  | 'knowledge'
  | 'agent'
  | 'retrofit'
  | 'doctor'
  | 'employee'

export type AiPromptAudience = 'developer' | 'manager' | 'admin' | 'employee'

export interface AiPromptVariable {
  name: string
  label: string
  required?: boolean
}

export interface AiPromptScenario {
  /** kebab-case，全局唯一。 */
  key: string
  title: string
  category: AiPromptCategory
  audience: AiPromptAudience[]
  /** platform = ReachAI 平台连接（/mcp/platform）；employee = 员工连接（/mcp/employee）。 */
  connection: 'platform' | 'employee'
  /** 一句话说明，提示词库卡片上显示。 */
  summary: string
  variables: AiPromptVariable[]
  /** 提示词正文，变量写成 {{name}}。 */
  template: string
  /** 接好后可以试的一句话。 */
  tryAfter?: string
  order?: number
}

export const AI_PROMPT_CATEGORY_LABELS: Record<AiPromptCategory, string> = {
  onboarding: '项目接入',
  page: '业务页面',
  embed: '前端嵌入对话',
  api: '接口与能力',
  workflow: 'Workflow',
  knowledge: '知识库',
  agent: '智能体',
  retrofit: '业务系统改造',
  doctor: '体检与排错',
  employee: '办业务',
}

const CATEGORY_ORDER = Object.keys(AI_PROMPT_CATEGORY_LABELS) as AiPromptCategory[]

const modules = import.meta.glob<{ default: AiPromptScenario }>('./prompts/*.ts', { eager: true })

function collect(): AiPromptScenario[] {
  const seen = new Set<string>()
  const list: AiPromptScenario[] = []
  for (const [path, module] of Object.entries(modules)) {
    if (path.endsWith('.test.ts')) continue
    const scenario = module.default
    if (!scenario?.key || seen.has(scenario.key)) {
      if (import.meta.env.DEV) console.warn(`[ai-prompts] 跳过重复或无效的场景：${path}`)
      continue
    }
    seen.add(scenario.key)
    list.push(scenario)
  }
  return list.sort((a, b) =>
    CATEGORY_ORDER.indexOf(a.category) - CATEGORY_ORDER.indexOf(b.category)
    || (a.order ?? 100) - (b.order ?? 100)
    || a.title.localeCompare(b.title, 'zh-CN'))
}

export const AI_PROMPT_SCENARIOS: readonly AiPromptScenario[] = collect()

export function findAiPromptScenario(key: string) {
  return AI_PROMPT_SCENARIOS.find(scenario => scenario.key === key)
}

export type AiPromptContext = Record<string, string | number | null | undefined>

/** ReachAI 的访问地址总是可用，作为内置变量。 */
export function withBuiltinVariables(context: AiPromptContext, origin = typeof window === 'undefined' ? '' : window.location.origin) {
  return { reachaiUrl: origin, ...context } as AiPromptContext
}

export function missingAiPromptVariables(scenario: AiPromptScenario, context: AiPromptContext) {
  const values = withBuiltinVariables(context)
  return scenario.variables.filter(variable => variable.required && !String(values[variable.name] ?? '').trim())
}

/** 填入变量；未提供的可选变量写成「（未指定）」，让 AI 自己去问或去查。 */
export function renderAiPrompt(scenario: AiPromptScenario, context: AiPromptContext) {
  const values = withBuiltinVariables(context)
  return scenario.template
    .replace(/\{\{\s*([a-zA-Z0-9_]+)\s*\}\}/g, (_match, name: string) => {
      const value = values[name]
      return value === null || value === undefined || String(value).trim() === '' ? '（未指定）' : String(value)
    })
    .trim()
}

export function groupAiPromptScenarios(scenarios: readonly AiPromptScenario[]) {
  const groups = new Map<AiPromptCategory, AiPromptScenario[]>()
  for (const scenario of scenarios) {
    const list = groups.get(scenario.category) ?? []
    list.push(scenario)
    groups.set(scenario.category, list)
  }
  return CATEGORY_ORDER.filter(category => groups.has(category))
    .map(category => ({ category, label: AI_PROMPT_CATEGORY_LABELS[category], scenarios: groups.get(category)! }))
}
