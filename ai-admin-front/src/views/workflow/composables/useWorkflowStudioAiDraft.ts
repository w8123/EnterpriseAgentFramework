import { type Ref } from 'vue'
import type { CompositionInfo } from '@/types/composition'
import type { KnowledgeBase } from '@/types/knowledge'
import type { ModelInstance } from '@/types/model'
import type { ToolInfo } from '@/types/tool'
import type { WorkflowDraftResource, WorkflowStudioState } from '@/types/workflow'

export interface WorkflowStudioAiDraftDependencies {
  aiModelInstanceId: Readonly<Ref<string>>
  studio: Readonly<Ref<WorkflowStudioState | null>>
  aiDraftModelOptions: Readonly<Ref<ModelInstance[]>>
}

export function useWorkflowStudioAiDraft({
  aiModelInstanceId,
  studio,
  aiDraftModelOptions,
}: WorkflowStudioAiDraftDependencies) {
  function resolveAiModelInstanceId() {
    return aiModelInstanceId.value.trim()
      || studio.value?.defaultModelInstanceId
      || aiDraftModelOptions.value[0]?.id
      || ''
  }

  function toolToDraftResource(tool: ToolInfo): WorkflowDraftResource {
    return {
      kind: 'TOOL',
      name: tool.name,
      qualifiedName: tool.qualifiedName,
      projectCode: tool.projectCode,
      description: tool.aiDescription || tool.description,
    }
  }

  function compositionToDraftResource(composition: CompositionInfo): WorkflowDraftResource {
    return {
      kind: 'SKILL',
      name: composition.name,
      qualifiedName: composition.qualifiedName,
      projectCode: composition.projectCode,
      description: composition.aiDescription || composition.description,
    }
  }

  function knowledgeToDraftResource(knowledge: KnowledgeBase): WorkflowDraftResource {
    return {
      kind: 'KNOWLEDGE',
      name: knowledge.code,
      projectCode: knowledge.projectCode,
      description: knowledge.description || knowledge.name,
    }
  }

  return {
    resolveAiModelInstanceId,
    toolToDraftResource,
    compositionToDraftResource,
    knowledgeToDraftResource,
  }
}
