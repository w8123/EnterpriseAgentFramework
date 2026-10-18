import type { CanvasNode } from '@/types/studio'
import type { CanvasNodeKind } from '@/types/studio'
import { studioNodeLabel } from '@/utils/studioNodeRegistry'

const interactionTypeLabels: Record<string, string> = {
  COLLECT_INPUT: '采集输入',
  PRESENT_OUTPUT: '展示输出',
  USER_CHOICE: '用户选择',
  CONFIRM_ACTION: '确认动作',
  REVIEW_EDIT: '审阅编辑',
}

export function useWorkflowStudioNodeMetadata() {
  function nodeKindLabel(kind: CanvasNodeKind) {
    return studioNodeLabel(kind)
  }

  function assignmentCount(assignments?: Record<string, unknown>) {
    return assignments ? Object.keys(assignments).length : 0
  }

  function userInputFieldCount(data: CanvasNode['data']) {
    return data.userInputConfig?.fields?.filter((field) => !!field.name?.trim()).length || 0
  }

  function interactionFieldCount(data: CanvasNode['data']) {
    return data.interactionConfig?.fields?.filter((field) => !!(field.key || field.name)?.trim()).length || 0
  }

  function interactionTypeLabel(type?: string) {
    return interactionTypeLabels[type || ''] || 'Other'
  }

  return {
    nodeKindLabel,
    assignmentCount,
    userInputFieldCount,
    interactionFieldCount,
    interactionTypeLabel,
  }
}
