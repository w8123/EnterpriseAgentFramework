import { type Ref } from 'vue'
import type { KnowledgeBase } from '@/types/knowledge'
import type { ModelInstance } from '@/types/model'
import type { BusinessMethodInfo } from '@/types/businessMethod'
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

  function toolToProposalResource(tool: BusinessMethodInfo): WorkflowProposalResource {
    return {
      kind: 'TOOL',
      name: tool.name,
      qualifiedName: tool.qualifiedName,
      projectCode: tool.projectCode,
      description: tool.description,
      metadata: { assetType: tool.assetType, assetId: tool.assetId, methodCode: tool.methodCode },
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
    knowledgeToProposalResource,
  }
}
