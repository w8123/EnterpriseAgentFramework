import { type Ref } from 'vue'
import type { CompositionInfo } from '@/types/composition'
import type { KnowledgeBase } from '@/types/knowledge'
import type { ModelInstance } from '@/types/model'
import type { ToolInfo } from '@/types/tool'
import type { WorkflowProposalResource, WorkflowWorkingCopyState } from '@/types/workflow'

export interface WorkflowStudioProposalContextDependencies {
  aiModelInstanceId: Readonly<Ref<string>>
  studio: Readonly<Ref<WorkflowWorkingCopyState | null>>
  authoringModelOptions: Readonly<Ref<ModelInstance[]>>
}

export function useWorkflowStudioProposalContext({
  aiModelInstanceId,
  studio,
  authoringModelOptions,
}: WorkflowStudioProposalContextDependencies) {
  function resolveAiModelInstanceId() {
    return aiModelInstanceId.value.trim()
      || studio.value?.defaultModelInstanceId
      || authoringModelOptions.value[0]?.id
      || ''
  }

  function toolToProposalResource(tool: ToolInfo): WorkflowProposalResource {
    return {
      kind: 'TOOL',
      name: tool.name,
      qualifiedName: tool.qualifiedName,
      projectCode: tool.projectCode,
      description: tool.aiDescription || tool.description,
    }
  }

  function compositionToProposalResource(composition: CompositionInfo): WorkflowProposalResource {
    return {
      kind: 'SKILL',
      name: composition.name,
      qualifiedName: composition.qualifiedName,
      projectCode: composition.projectCode,
      description: composition.aiDescription || composition.description,
    }
  }

  function knowledgeToProposalResource(knowledge: KnowledgeBase): WorkflowProposalResource {
    return {
      kind: 'KNOWLEDGE',
      name: knowledge.code,
      projectCode: knowledge.projectCode,
      description: knowledge.description || knowledge.name,
    }
  }

  return {
    resolveAiModelInstanceId,
    toolToProposalResource,
    compositionToProposalResource,
    knowledgeToProposalResource,
  }
}
