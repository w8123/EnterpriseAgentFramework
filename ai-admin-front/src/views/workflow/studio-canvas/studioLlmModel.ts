import type { ModelInstance } from '@/types/model'
import type { CanvasNode } from '@/types/studio'

export interface StudioLlmModelContext {
  defaultModelInstanceId?: string | null
  modelOptions?: readonly Pick<ModelInstance, 'id' | 'provider'>[]
}

export function resolveStudioLlmProvider(
  data: CanvasNode['data'] | null | undefined,
  context: StudioLlmModelContext,
): string {
  if (data?.kind !== 'llm') return ''
  const modelInstanceId = data.llmConfig?.modelInstanceId || context.defaultModelInstanceId
  if (!modelInstanceId) return ''
  return context.modelOptions?.find((item) => item.id === modelInstanceId)?.provider?.trim() || ''
}
