import type {
  ProjectPage,
  ProjectPageAction,
  ProjectPageResource,
} from '@/types/pageWorkbench'

const HAN_TEXT_PATTERN = /[\u3400-\u9fff]/
const COMMON_TECHNICAL_TEXT_PATTERN =
  /^(?:(?:AI|MCP|API|SDK|HTTP|HTTPS|REST|SSE|JWT|URL|UI)(?:\s*[/·,+&]\s*)?)+$/i
const HUMAN_TEXT_REPLACEMENTS: ReadonlyArray<readonly [RegExp, string]> = [
  [/\bAI Coding\b/gi, 'AI 编程工具'],
  [/\bEmbed Token\b/gi, '嵌入凭据'],
  [/\bTask Token\b/gi, '任务凭据'],
  [/\bWorkflow\b/g, '工作流'],
  [/\bRuntime\b/g, '运行服务'],
  [/\bAgent\b/g, '智能体'],
  [/\bStudio\b/g, '编排工作台'],
  [/\bPENDING\b/g, '待确认'],
  [/\bPASS\b/g, '通过'],
  [/\bFAIL\b/g, '未通过'],
  [/\bWARN\b/g, '需注意'],
]

function localizeKnownHumanTerms(value: string) {
  const localized = HUMAN_TEXT_REPLACEMENTS.reduce(
    (result, [pattern, replacement]) => result.replace(pattern, replacement),
    value,
  )
  return localized.replace(
    /([\u3400-\u9fff])\s+(?=[\u3400-\u9fff])/g,
    '$1',
  )
}

export function pageWorkbenchHumanText(
  value: string | null | undefined,
  fallback: string,
) {
  const normalized = value?.trim()
  if (!normalized) return fallback
  return HAN_TEXT_PATTERN.test(normalized)
    || COMMON_TECHNICAL_TEXT_PATTERN.test(normalized)
    ? localizeKnownHumanTerms(normalized)
    : fallback
}

export function pageWorkbenchPageName(page: ProjectPage) {
  const technicalReference = page.routePattern || page.pageKey
  return pageWorkbenchHumanText(page.name, `页面 · ${technicalReference}`)
}

export function pageWorkbenchPageDescription(page: ProjectPage) {
  return pageWorkbenchHumanText(page.description, '尚未提供中文页面说明。')
}

export function pageWorkbenchModuleName(
  name: string | null | undefined,
  key: string | null | undefined,
) {
  if (!key || key === '__ungrouped__') return '未分组'
  return pageWorkbenchHumanText(name, `业务模块 · ${key}`)
}

export function pageWorkbenchResourceName(
  resource: ProjectPageResource,
  typeLabel: string,
) {
  return pageWorkbenchHumanText(
    resource.displayName,
    `${typeLabel} · ${resource.resourceKey}`,
  )
}

export function pageWorkbenchActionTitle(action: ProjectPageAction) {
  return pageWorkbenchHumanText(
    action.title,
    `页面操作 · ${action.actionKey}`,
  )
}

export function pageWorkbenchSourceLabel(sourceType?: string) {
  return (
    {
      AI_SCAN: 'AI 扫描',
      MANUAL: '手动添加',
      SDK: 'SDK 同步',
    }[sourceType || ''] || '来源待确认'
  )
}

export function pageWorkbenchRiskLabel(riskLevel?: string) {
  return (
    {
      READ: '只读',
      LOW: '低风险',
      WRITE: '写入',
      MEDIUM: '中风险',
      PAGE_ACTION: '页面操作',
      HIGH: '高风险',
      IRREVERSIBLE: '不可逆',
    }[riskLevel || ''] || '风险待确认'
  )
}

export function pageWorkbenchReadinessStatusLabel(status?: string) {
  return (
    {
      PASS: '通过',
      PENDING: '待确认',
      FAIL: '未通过',
      WARN: '需注意',
      NOT_REQUIRED: '无需验收',
    }[status || ''] || '待确认'
  )
}

export function pageWorkbenchTaskTitle(
  value: string | null | undefined,
  taskKindLabel: string,
) {
  return pageWorkbenchHumanText(value, `${taskKindLabel}任务`)
}

export function pageWorkbenchTaskSummary(
  lastMessage: string | null | undefined,
  objective: string | null | undefined,
  statusLabel: string,
) {
  return pageWorkbenchHumanText(
    lastMessage || objective,
    `当前进度：${statusLabel}`,
  )
}
