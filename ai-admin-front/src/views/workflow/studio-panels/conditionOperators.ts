import type { ConditionOperator } from '@/types/studio'

export type ConditionOperatorOption = { label: string; value: ConditionOperator; unary?: boolean; hint?: string }

/** Comparison operators the runtime understands; unary ones ignore the right-hand value. */
export const CONDITION_OPERATORS: ConditionOperatorOption[] = [
  { label: '存在', value: 'exists', unary: true },
  { label: '为空', value: 'empty', unary: true },
  { label: '非空', value: 'not_empty', unary: true },
  { label: '等于', value: 'equals' },
  { label: '不等于', value: 'not_equals' },
  { label: '包含', value: 'contains' },
  { label: '不包含', value: 'not_contains' },
  { label: '开头是', value: 'starts_with' },
  { label: '结尾是', value: 'ends_with' },
  { label: '属于列表', value: 'in', hint: '右值写 a,b,c 或 JSON 数组' },
  { label: '不属于列表', value: 'not_in', hint: '右值写 a,b,c 或 JSON 数组' },
  { label: '匹配正则', value: 'regex', hint: '右值写正则，例如 ^SO-\\d+$' },
  { label: '大于', value: 'gt' },
  { label: '大于等于', value: 'gte' },
  { label: '小于', value: 'lt' },
  { label: '小于等于', value: 'lte' },
  { label: '是数字', value: 'is_number', unary: true },
  { label: '是文本', value: 'is_text', unary: true },
  { label: '是列表', value: 'is_list', unary: true },
  { label: '是对象', value: 'is_object', unary: true },
  { label: '是真假值', value: 'is_boolean', unary: true },
]

const UNARY = new Set(CONDITION_OPERATORS.filter((item) => item.unary).map((item) => item.value))

export function isUnaryOperator(operator?: string) {
  return UNARY.has((operator || '') as ConditionOperator)
}

export function operatorPlaceholder(operator?: string) {
  return CONDITION_OPERATORS.find((item) => item.value === operator)?.hint || '右值'
}
