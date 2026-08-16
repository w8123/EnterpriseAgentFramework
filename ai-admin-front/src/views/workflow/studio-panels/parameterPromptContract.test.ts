import { describe, expect, it } from 'vitest'
import type { ParameterNodeConfig } from '@/types/studio'
import { parameterPromptInputWarning } from './parameterPromptContract'

function config(overrides: Partial<ParameterNodeConfig> = {}): ParameterNodeConfig {
  return {
    mode: 'llm',
    inputExpression: 'nodeOutput.query_team',
    systemPrompt: '',
    userPrompt: '',
    fields: [{ name: 'id', type: 'string', required: true }],
    ...overrides,
  }
}

describe('parameterPromptInputWarning', () => {
  it('warns when a custom prompt hides an upstream node output', () => {
    expect(parameterPromptInputWarning(config({ userPrompt: '目标名称：{{ params.teamName }}' })))
      .toContain('nodeOutput.query_team')
  })

  it('accepts the upstream reference with mustache whitespace', () => {
    expect(parameterPromptInputWarning(config({
      userPrompt: '列表结果：{{ nodeOutput.query_team }}',
    }))).toBe('')
  })

  it('accepts an empty prompt because runtime falls back to inputExpression', () => {
    expect(parameterPromptInputWarning(config())).toBe('')
  })
})
