import type { CanvasNode } from '@/types/studio'
import type { CanvasNodeKind } from '@/types/studio'
import { studioNodeLabel } from '@/utils/studioNodeRegistry'

const interactionTypeLabels: Record<string, string> = {
  COLLECT_INPUT: 'Collect input',
  PRESENT_OUTPUT: 'Present output',
  USER_CHOICE: 'User choice',
  CONFIRM_ACTION: 'Confirm action',
  REVIEW_EDIT: 'Review and edit',
}

export function useWorkflowStudioNodeMetadata() {
  function nodeKindLabel(kind: CanvasNodeKind) {
    return studioNodeLabel(kind)
  }

  function assignmentCount(assignments?: Record<string, string>) {
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
