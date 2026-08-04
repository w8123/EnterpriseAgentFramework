import type { AiCodingTask } from '@/types/aiCodingTask'
import type {
  PageAnalysisFinding,
  PageImplementationGoalDraft,
  PageImplementationGoalItem,
  PageImplementationGoalSelection,
} from '@/types/pageWorkbench'
import { pageWorkbenchHumanText } from '@/utils/pageWorkbenchPresentation'

export const PAGE_IMPLEMENTATION_GOAL_SELECTION_SCHEMA =
  'reachai.page-implementation-goal-selection.v1' as const

export const PAGE_IMPLEMENTATION_COMPLETION_REQUIREMENT =
  '完成业务页面代码、页面操作登记、相关测试和真实浏览器自检；不要发布工作流。'

function isRecord(value: unknown): value is Record<string, unknown> {
  return Boolean(value && typeof value === 'object' && !Array.isArray(value))
}

function normalizeFindingIds(value: unknown) {
  if (!Array.isArray(value)) return []
  return Array.from(new Set(value
    .filter((item): item is number => (
      typeof item === 'number' && Number.isInteger(item) && item > 0
    ))
    .map(Number)))
}

function findingContent(finding: PageAnalysisFinding) {
  const title = pageWorkbenchHumanText(finding.title, '已选页面改造目标')
  const useCase = pageWorkbenchHumanText(finding.useCase, finding.confirmedFact)
  return `${title}：${useCase}`
}

function normalizeItems(value: unknown): PageImplementationGoalItem[] {
  if (!Array.isArray(value)) return []
  return value.flatMap((item) => {
    if (!isRecord(item) || typeof item.content !== 'string' || !item.content.trim()) return []
    const source = item.source === 'MANUAL' || item.source === 'AI_FINDING'
      ? item.source
      : 'TASK'
    const findingId = typeof item.findingId === 'number'
      && Number.isInteger(item.findingId)
      && item.findingId > 0
      ? item.findingId
      : undefined
    return [{
      source,
      content: item.content.trim(),
      ...(findingId ? { findingId } : {}),
    } satisfies PageImplementationGoalItem]
  })
}

export function buildPageImplementationGoalSelection(
  findings: PageAnalysisFinding[],
  draft: PageImplementationGoalDraft,
): PageImplementationGoalSelection {
  const findingsById = new Map(findings.map((finding) => [finding.id, finding]))
  const findingIds = Array.from(new Set(draft.findingIds))
    .filter((id) => Number.isInteger(id) && findingsById.has(id))
  const manualGoal = draft.manualGoal.trim()
  const items: PageImplementationGoalItem[] = [
    ...(manualGoal
      ? [{ source: 'MANUAL' as const, content: manualGoal }]
      : []),
    ...findingIds.map((findingId) => ({
      source: 'AI_FINDING' as const,
      findingId,
      content: findingContent(findingsById.get(findingId)!),
    })),
  ]
  return {
    schema: PAGE_IMPLEMENTATION_GOAL_SELECTION_SCHEMA,
    manualGoal,
    findingIds,
    items,
  }
}

function structuredSelection(
  task: AiCodingTask,
  findings: PageAnalysisFinding[],
): PageImplementationGoalSelection | null {
  const pageTarget = task.targets.find((target) => (
    target.targetRole === 'PRIMARY' && target.targetType === 'PAGE'
  ))
  if (!isRecord(pageTarget?.snapshot)) return null
  const rawSelection = pageTarget.snapshot.goalSelection
  if (!isRecord(rawSelection)) return null

  const manualGoal = typeof rawSelection.manualGoal === 'string'
    ? rawSelection.manualGoal.trim()
    : ''
  const findingIds = normalizeFindingIds(rawSelection.findingIds)
  const items = normalizeItems(rawSelection.items)
  const fallback = buildPageImplementationGoalSelection(findings, {
    manualGoal,
    findingIds,
  })
  return {
    schema: PAGE_IMPLEMENTATION_GOAL_SELECTION_SCHEMA,
    manualGoal,
    findingIds,
    items: items.length ? items : fallback.items,
  }
}

function legacySelection(
  task: AiCodingTask,
  findings: PageAnalysisFinding[],
): PageImplementationGoalSelection {
  const manualItems: PageImplementationGoalItem[] = []
  const aiContents: string[] = []
  for (const rawLine of task.objective.split('\n')) {
    const line = rawLine.trim()
    if (line.startsWith('人工描述：')) {
      const content = line.slice('人工描述：'.length).trim()
      if (content) manualItems.push({ source: 'MANUAL', content })
    }
    if (line.startsWith('AI 建议：')) {
      const content = line.slice('AI 建议：'.length).trim()
      if (content) aiContents.push(content)
    }
  }

  const matchedFindingIds: number[] = []
  const aiItems = aiContents.map((content) => {
    const finding = findings.find((item) => findingContent(item) === content)
    if (finding) matchedFindingIds.push(finding.id)
    return {
      source: 'AI_FINDING' as const,
      content,
      ...(finding ? { findingId: finding.id } : {}),
    }
  })
  const fallbackFindingIds = aiItems.length
    ? matchedFindingIds
    : findings.filter((item) => item.status === 'KEPT').map((item) => item.id)
  const fallback = buildPageImplementationGoalSelection(findings, {
    manualGoal: manualItems[0]?.content || '',
    findingIds: fallbackFindingIds,
  })
  const items = [...manualItems, ...aiItems]
  if (items.length) {
    return {
      ...fallback,
      items,
    }
  }
  if (task.objective.trim()) {
    return {
      ...fallback,
      items: [{ source: 'TASK', content: task.objective.trim() }],
    }
  }
  return fallback
}

export function readPageImplementationGoalSelection(
  task: AiCodingTask | null | undefined,
  findings: PageAnalysisFinding[],
): PageImplementationGoalSelection {
  if (!task) {
    return buildPageImplementationGoalSelection(findings, {
      manualGoal: '',
      findingIds: findings.filter((item) => item.status === 'KEPT').map((item) => item.id),
    })
  }
  return structuredSelection(task, findings) || legacySelection(task, findings)
}

export function pageImplementationObjective(selection: PageImplementationGoalSelection) {
  return [
    ...selection.items
      .filter((item) => item.source !== 'TASK')
      .map((item) => `${item.source === 'MANUAL' ? '人工描述' : 'AI 建议'}：${item.content}`),
    PAGE_IMPLEMENTATION_COMPLETION_REQUIREMENT,
  ].join('\n')
}
