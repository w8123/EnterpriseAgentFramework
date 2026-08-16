import type { ParameterNodeConfig } from '@/types/studio'

function compactWhitespace(value?: string) {
  return (value || '').replace(/\s+/g, '')
}

/**
 * Runtime uses a non-empty userPrompt instead of the inputExpression fallback.
 * Surface that contract while the user can still repair the node in Studio.
 */
export function parameterPromptInputWarning(config: ParameterNodeConfig) {
  if (config.mode !== 'llm') return ''
  const inputExpression = (config.inputExpression || '').trim()
  const userPrompt = (config.userPrompt || '').trim()
  if (!inputExpression.startsWith('nodeOutput.') || !userPrompt) return ''
  if (compactWhitespace(userPrompt).includes(compactWhitespace(inputExpression))) return ''
  return `用户提示词未引用上游输入 ${inputExpression}，运行时将看不到该节点结果。请加入 {{ ${inputExpression} }} 或清空用户提示词。`
}
