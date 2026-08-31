import { describe, expect, it } from 'vitest'
import type { ModelInstance } from '@/types/model'
import {
  isSupportedModelType,
  modelTypeDisplayLabel,
  normalizeActiveModelInstances,
} from './modelSelection'

function sampleInstance(partial: Partial<ModelInstance> = {}): ModelInstance {
  return {
    id: 'model-1',
    name: 'Model 1',
    provider: 'openai',
    modelType: 'LLM',
    modelName: 'gpt-test',
    protocol: 'OPENAI_COMPATIBLE',
    connection: {},
    defaultOptions: {},
    paramsSchema: [],
    status: 'ACTIVE',
    lastTestStatus: 'SUCCESS',
    ...partial,
  }
}

describe('model selection helpers', () => {
  it('keeps only active instances of the requested type', () => {
    const payload = {
      data: [
        sampleInstance(),
        sampleInstance({ id: 'disabled', status: 'DISABLED' }),
        sampleInstance({ id: 'embedding', modelType: 'EMBEDDING' }),
      ],
    }

    expect(normalizeActiveModelInstances(payload, 'LLM').map((item) => item.id)).toEqual(['model-1'])
    expect(normalizeActiveModelInstances(payload).map((item) => item.id)).toEqual(['model-1', 'embedding'])
  })

  it('returns an empty list for malformed payloads', () => {
    expect(normalizeActiveModelInstances(null)).toEqual([])
    expect(normalizeActiveModelInstances({ data: null })).toEqual([])
  })

  it('provides stable model type labels and guards', () => {
    expect(modelTypeDisplayLabel('EMBEDDING')).toBe('Embedding')
    expect(modelTypeDisplayLabel('RERANKER')).toBe('Reranker')
    expect(isSupportedModelType('LLM')).toBe(true)
    expect(isSupportedModelType('IMAGE')).toBe(false)
  })
})
