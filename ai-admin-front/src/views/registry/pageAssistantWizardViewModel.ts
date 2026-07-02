import type { Component } from 'vue'
import { Connection, Operation, Search } from '@element-plus/icons-vue'
import type { PageActionRegistryView, PageRegistryView } from '@/api/embedOps'
import type { ApiAssetItem } from '@/types/apiAsset'
import type { PageAssistantSessionSummary } from '@/types/scanProject'
import type { ModelInstance } from '@/types/model'
import type { WorkflowDraftGenerationResult } from '@/types/workflow'
import { modelOptionLabel } from '@/views/registry/pageAssistantWizardUtils'

export type WizardStepKey = 'connect' | 'page' | 'action' | 'draft' | 'confirm' | 'bind' | 'studio'
export type AssistantGoal = 'query' | 'operate' | 'queryThenAction'
export type DraftSource = 'NONE' | 'PLATFORM_GENERATED' | 'AI_CODING_RETURNED'

export interface WorkflowAiCodingDraftEvidence {
  workflowId?: string
  keySlug?: string
  studioUrl?: string
  workflowName?: string
}

export const WIZARD_STEP_KEYS: WizardStepKey[] = ['connect', 'page', 'action', 'draft', 'confirm', 'bind', 'studio']

export interface AssistantGoalOption {
  value: AssistantGoal
  icon: Component
  tone: 'query' | 'operate' | 'link'
  title: string
  desc: string
}

export const ASSISTANT_GOAL_OPTIONS: AssistantGoalOption[] = [
  { value: 'query', icon: Search, tone: 'query', title: '查询/筛选助手', desc: '提取条件并触发页面查询' },
  { value: 'operate', icon: Operation, tone: 'operate', title: '页面操作助手', desc: '围绕页面动作执行操作' },
  { value: 'queryThenAction', icon: Connection, tone: 'link', title: '查询后联动', desc: '先查询再触发后续动作' },
]

export function pageIdentity(page: PageRegistryView): string {
  return String(page.id || page.pageKey)
}

export function actionRowKey(action: PageActionRegistryView): string {
  return String(action.id || action.actionKey)
}

export function actionCountForPage(pageKey: string, pageActions: PageActionRegistryView[]): number {
  return pageActions.filter((action) => action.pageKey === pageKey).length
}

export function formatEvidence(evidence: Record<string, unknown>): string {
  return JSON.stringify(evidence || {}, null, 2)
}

export function pageAccessTitle(
  session: PageAssistantSessionSummary,
  pageRegistry: PageRegistryView[],
): string {
  const page = pageRegistry.find((item) => item.pageKey === session.targetPageKey)
  return page?.name || session.targetPageKey || '待确认业务页面'
}

export interface WizardStatsInput {
  pageCount: number
  actionCount: number
  activeActionCount: number
  apiAssetCount: number
}

export function buildWizardStats(input: WizardStatsInput) {
  return [
    { key: 'page', icon: '页', label: '页面', value: String(input.pageCount) },
    { key: 'action', icon: '动', label: '动作', value: String(input.actionCount) },
    { key: 'active', icon: 'A', label: 'ACTIVE', value: String(input.activeActionCount) },
    { key: 'api', icon: 'API', label: 'API 资产', value: String(input.apiAssetCount) },
  ]
}

export interface WizardStepsInput {
  pageRegistryLength: number
  pageActionsLength: number
  selectedPageKey: string
  selectedPageName?: string | null
  selectedActionsLength: number
  isDraftStepComplete: boolean
  isAiCodingWorkflowSelected: boolean
  createdWorkflowId: string
  bindingResultPresent: boolean
  draftPreview: WorkflowDraftGenerationResult | null
}

export function buildWizardSteps(input: WizardStepsInput) {
  return [
    {
      index: 1,
      key: 'connect' as const,
      title: '接入准备',
      desc: 'AI 或手动接入页面动作',
      done: input.pageRegistryLength > 0 || input.pageActionsLength > 0,
    },
    {
      index: 2,
      key: 'page' as const,
      title: '选择页面',
      desc: input.selectedPageName || input.selectedPageKey || '定位业务页面',
      done: Boolean(input.selectedPageKey),
    },
    {
      index: 3,
      key: 'action' as const,
      title: '选择动作',
      desc: '声明可执行能力',
      done: input.selectedActionsLength > 0,
    },
    {
      index: 4,
      key: 'draft' as const,
      title: '生成 / 选择草稿',
      desc: input.isAiCodingWorkflowSelected ? 'AI Coding Workflow' : '构建 GraphSpec',
      done: input.isDraftStepComplete,
    },
    {
      index: 5,
      key: 'confirm' as const,
      title: '确认草稿',
      desc: '创建 Workflow',
      done: input.isAiCodingWorkflowSelected ? input.isAiCodingWorkflowSelected : Boolean(input.createdWorkflowId),
    },
    {
      index: 6,
      key: 'bind' as const,
      title: '挂载智能体',
      desc: '绑定 PAGE_COPILOT',
      done: input.bindingResultPresent,
    },
    {
      index: 7,
      key: 'studio' as const,
      title: '进入 Studio',
      desc: '预览保存发布',
      done: false,
    },
  ]
}

export function resolveActiveWizardStep(input: {
  pageRegistryLength: number
  pageActionsLength: number
  selectedPageKey: string
  selectedActionsLength: number
  isDraftStepComplete: boolean
  isAiCodingWorkflowSelected: boolean
  createdWorkflowId: string
  bindingResultPresent: boolean
}): WizardStepKey {
  if (!input.pageRegistryLength && !input.pageActionsLength) return 'connect'
  if (!input.selectedPageKey) return 'page'
  if (!input.selectedActionsLength) return 'action'
  if (!input.isDraftStepComplete) return 'draft'
  if (input.isAiCodingWorkflowSelected) {
    if (!input.bindingResultPresent) return 'bind'
    return 'studio'
  }
  if (!input.createdWorkflowId) return 'confirm'
  if (!input.bindingResultPresent) return 'bind'
  return 'studio'
}

export function isRequiredWizardStepComplete(
  key: WizardStepKey,
  input: {
    selectedPageKey: string
    selectedActionsLength: number
    draftSource: DraftSource
    createdWorkflowId: string
    draftPreview: WorkflowDraftGenerationResult | null
    bindingResultPresent: boolean
  },
): boolean {
  if (key === 'page') return Boolean(input.selectedPageKey)
  if (key === 'action') return input.selectedActionsLength > 0
  if (key === 'draft') {
    if (input.draftSource === 'AI_CODING_RETURNED') {
      return Boolean(input.createdWorkflowId)
    }
    return Boolean(input.draftPreview)
  }
  if (key === 'confirm') {
    if (input.draftSource === 'AI_CODING_RETURNED') {
      return true
    }
    return Boolean(input.createdWorkflowId)
  }
  if (key === 'bind') return input.bindingResultPresent
  return true
}

export function buildDraftIssues(draftPreview: WorkflowDraftGenerationResult | null): string[] {
  return [
    ...(draftPreview?.validationErrors || []),
    ...(draftPreview?.placeholderNodes || []).map(
      (item) => `${item.label || item.nodeId}: ${item.reason || '节点仍需配置'}`,
    ),
  ]
}

export function buildSelectedModelLabel(
  modelOptions: ModelInstance[],
  modelInstanceId: string,
): string {
  const model = modelOptions.find((item) => item.id === modelInstanceId)
  return model ? modelOptionLabel(model) : modelInstanceId || '未选择'
}
