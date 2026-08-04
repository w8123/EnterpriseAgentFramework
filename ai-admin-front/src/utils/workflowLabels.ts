type TagType = 'success' | 'info' | 'warning' | 'danger'

/** Workflow 业务形态。 */
const WORKFLOW_KIND_LABELS: Record<string, string> = {
  GENERAL: '通用流程',
  PAGE_ASSISTANT: '页面助手',
}

/** Workflow 生命周期状态 */
const WORKFLOW_STATUS_LABELS: Record<string, string> = {
  DRAFT: '草稿',
  ACTIVE: '已发布',
  ARCHIVED: '已归档',
}

/** Workflow 定义权威。 */
const WORKFLOW_DEFINITION_AUTHORITY_LABELS: Record<string, string> = {
  USER: '用户维护',
  SDK: 'SDK 同步',
  SYSTEM: '系统维护',
}

const WORKFLOW_CREATION_CHANNEL_LABELS: Record<string, string> = {
  STUDIO: 'Studio 创建',
  AI_CODING: 'AI Coding',
  SDK_SYNC: 'SDK 同步',
  AI_QUICK_ACCESS: 'AI 快捷接入',
  SYSTEM_SEED: '系统内置',
}

const WORKFLOW_EXECUTION_ENGINE_LABELS: Record<string, string> = {
  GRAPH_SPEC: 'GraphSpec',
}

export const WORKFLOW_KIND_SELECT_OPTIONS = [
  { value: 'GENERAL', label: '通用流程' },
  { value: 'PAGE_ASSISTANT', label: '页面助手' },
] as const

export const WORKFLOW_STATUS_SELECT_OPTIONS = [
  { value: 'DRAFT', label: '草稿' },
  { value: 'ACTIVE', label: '已发布' },
  { value: 'ARCHIVED', label: '已归档' },
] as const

export function formatWorkflowKindLabel(workflowKind?: string | null): string {
  if (workflowKind == null || workflowKind === '') return '-'
  const key = workflowKind.toUpperCase()
  return WORKFLOW_KIND_LABELS[key] ?? workflowKind
}

export function formatWorkflowStatusLabel(status?: string | null): string {
  if (status == null || status === '') return '草稿'
  const key = status.toUpperCase()
  return WORKFLOW_STATUS_LABELS[key] ?? status
}

export function workflowStatusTagType(status?: string | null): TagType {
  const key = (status || 'DRAFT').toUpperCase()
  if (key === 'ACTIVE') return 'success'
  if (key === 'ARCHIVED') return 'info'
  return 'warning'
}

export function formatWorkflowDefinitionAuthorityLabel(definitionAuthority?: string | null): string {
  if (definitionAuthority == null || definitionAuthority === '') return '-'
  const key = definitionAuthority.toUpperCase()
  return WORKFLOW_DEFINITION_AUTHORITY_LABELS[key] ?? definitionAuthority
}

export function formatWorkflowCreationChannelLabel(creationChannel?: string | null): string {
  if (creationChannel == null || creationChannel === '') return '-'
  const key = creationChannel.toUpperCase()
  return WORKFLOW_CREATION_CHANNEL_LABELS[key] ?? creationChannel
}

export function formatWorkflowExecutionEngineLabel(executionEngine?: string | null): string {
  if (executionEngine == null || executionEngine === '') return '-'
  const key = executionEngine.toUpperCase()
  return WORKFLOW_EXECUTION_ENGINE_LABELS[key] ?? executionEngine
}
