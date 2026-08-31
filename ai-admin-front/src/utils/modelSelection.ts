import type { ModelInstance, ModelType } from '@/types/model'

const MODEL_TYPE_LABELS: Record<ModelType, string> = {
  LLM: 'LLM',
  EMBEDDING: 'Embedding',
  RERANKER: 'Reranker',
}

export function modelTypeDisplayLabel(modelType: ModelType): string {
  return MODEL_TYPE_LABELS[modelType]
}

export function normalizeActiveModelInstances(
  payload: unknown,
  modelType?: ModelType,
): ModelInstance[] {
  let candidate = payload
  if (!Array.isArray(candidate) && candidate !== null && typeof candidate === 'object') {
    candidate = (candidate as { data?: unknown }).data
  }
  if (!Array.isArray(candidate)) return []

  return (candidate as ModelInstance[]).filter((item) => (
    item?.status === 'ACTIVE'
    && (!modelType || item.modelType === modelType)
  ))
}

export function isSupportedModelType(value: unknown): value is ModelType {
  return value === 'LLM' || value === 'EMBEDDING' || value === 'RERANKER'
}
