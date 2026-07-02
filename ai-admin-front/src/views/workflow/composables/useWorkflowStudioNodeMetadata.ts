import type { CanvasNode } from '@/types/studio'
import type { WorkflowGraphNodeTypeDescriptor as NodeTypeDescriptor } from '@/types/workflow'
import type { CanvasNodeKind } from '@/types/studio'
import { studioNodeLabel } from '@/utils/studioNodeRegistry'

export interface UseWorkflowStudioNodeMetadataDeps {
  nodeTypeLabelMap?: Record<string, string>
}

const nodeTypeClassMap: Partial<Record<CanvasNodeKind, string>> = {
  userInput: 'user-input-node',
  pageAction: 'page-action-node',
  knowledgeWrite: 'knowledge-write-node',
  documentExtract: 'document-extract-node',
}

const interactionTypeLabels: Record<string, string> = {
  COLLECT_INPUT: 'Collect input',
  PRESENT_OUTPUT: 'Present output',
  USER_CHOICE: 'User choice',
  CONFIRM_ACTION: 'Confirm action',
  REVIEW_EDIT: 'Review and edit',
}

export function useWorkflowStudioNodeMetadata() {
  function nodeTypeClass(kind: CanvasNodeKind) {
    return nodeTypeClassMap[kind] || `${kind}-node`
  }

  function nodeKindLabel(kind: CanvasNodeKind) {
    return studioNodeLabel(kind)
  }

  function nodeHint(kind: CanvasNodeKind, descriptor?: NodeTypeDescriptor) {
    if (descriptor?.aliases?.length) return descriptor.aliases.join(' / ')
    const hintMap: Record<CanvasNodeKind, string> = {
      start: 'Workflow start marker.',
      end: 'Workflow end marker.',
      userInput: 'Collect user input and write into params.',
      interaction: 'Collect or confirm user-facing interaction fields.',
      pageAction: 'Execute configured page action.',
      llm: 'Run LLM-based reasoning workflow node.',
      skill: 'Call a registered capability.',
      tool: 'Call a platform tool.',
      knowledge: 'Query knowledge service.',
      condition: 'Branch to matched condition routes.',
      variable: 'Assign/prepare reusable variables.',
      template: 'Generate response by template.',
      parameter: 'Extract and transform parameters.',
      http: 'Call external HTTP endpoint.',
      answer: 'Compose answer text.',
      code: 'Run script or expression node.',
      classifier: 'Branch by classified result.',
      aggregate: 'Aggregate values from multiple nodes.',
      approval: 'Wait for human approval.',
      loop: 'Control loop execution strategy.',
      knowledgeWrite: 'Write workflow output into knowledge base.',
      documentExtract: 'Extract structured content from text.',
      mcp: 'Call MCP tool.',
    }
    return hintMap[kind] || 'Workflow node'
  }

  function nodeStateLabel(data: CanvasNode['data']) {
    if (data.needsConfiguration) return 'Needs config'
    if (data.collapsed) return 'Collapsed'
    return 'Normal'
  }

  function nodeTitle(id: string, data: CanvasNode['data']) {
    return data.label || nodeKindLabel(data.kind) || id
  }

  function nodeDescription(data: CanvasNode['data']) {
    if (data.description) return data.description
    if (data.pageActionConfig?.actionKey) {
      return `${data.pageActionConfig.pageKey || 'Current page'} / ${data.pageActionConfig.actionKey}`
    }
    if (data.toolConfig?.qualifiedName || data.toolConfig?.ref) {
      return data.toolConfig.qualifiedName || data.toolConfig.ref || ''
    }
    if (data.httpConfig?.url) return data.httpConfig.url
    if (data.knowledgeConfig?.knowledgeBaseCodes?.length) {
      return data.knowledgeConfig.knowledgeBaseCodes.join(', ')
    }
    return nodeHint(data.kind)
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
    nodeTypeClass,
    nodeKindLabel,
    nodeHint,
    nodeStateLabel,
    nodeTitle,
    nodeDescription,
    assignmentCount,
    userInputFieldCount,
    interactionFieldCount,
    interactionTypeLabel,
  }
}
