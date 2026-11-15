import type { PageActionReviewPreview } from '@/api/pageActionEffectReview'

const RESERVED = new Set(['params', 'sys', 'env', 'var', 'input', 'message', 'nodeOutput', 'lastOutput',
  'previousOutput', 'projectCode', 'pageKey', 'agentId', 'sessionId', 'supervisorTraceId', '__proto__', 'constructor', 'prototype'])
const ROOT_KEYS = new Set(['type', 'properties', 'required', 'additionalProperties', 'title', 'description', 'minProperties', 'maxProperties'])
const FIELD_KEYS = new Set(['type', 'title', 'description', 'minLength', 'maxLength'])
const literal = (value: unknown): boolean => typeof value !== 'string' || (!!value.trim() && value === value.trim()
  && !['${', '#{', '{{', '}}', '*'].some((part) => value.includes(part)))
function textValuesLiteral(value: unknown): boolean {
  if (typeof value === 'string') return literal(value)
  if (Array.isArray(value)) return value.every(textValuesLiteral)
  return !value || typeof value !== 'object' || Object.values(value).every(textValuesLiteral)
}
function safeRoute(route: string): boolean {
  return route.startsWith('/') && !route.startsWith('//') && !/[\\%\s\x00-\x1f\x7f:{}?#*]/.test(route)
}
function nonnegative(value: unknown): boolean {
  return value === undefined || (typeof value === 'number' && Number.isSafeInteger(value) && value >= 0 && value <= 2147483647)
}

/** Builds an editable draft only; the server must re-check the approval when publishing and executing. */
export function buildTrustedPageDraft(preview: PageActionReviewPreview, projectCode: string, existingEnv: Record<string, unknown>) {
  const approval = preview.approval
  if (!approval?.active || approval.effect !== 'PAGE_LOCAL_DRAFT' || approval.catalogHash !== preview.catalogHash
      || preview.projectCode !== projectCode || !preview.pageKey || !preview.actionKey || !preview.routePattern
      || !safeRoute(preview.routePattern)) {
    throw new Error('页面动作审核已失效，请在页面接入中心重新审核')
  }
  const schema = preview.inputSchema
  if (!schema || typeof schema !== 'object' || Array.isArray(schema)) throw new Error('页面动作缺少有效的输入字段')
  const root = schema as Record<string, unknown>
  const properties = root.properties
  if (!properties || typeof properties !== 'object' || Array.isArray(properties) || !Object.keys(properties).length) {
    throw new Error('页面动作缺少可填写的字段')
  }
  const requiredList = root.required === undefined ? [] : root.required
  if (root.type !== 'object' || Object.keys(root).some((key) => !ROOT_KEYS.has(key))
      || (root.additionalProperties !== undefined && root.additionalProperties !== false)
      || !Array.isArray(requiredList) || requiredList.some((key) => typeof key !== 'string')
      || new Set(requiredList).size !== requiredList.length || !textValuesLiteral(schema)
      || !nonnegative(root.minProperties) || !nonnegative(root.maxProperties)) throw new Error('页面动作输入约束不受支持，请重新审核')
  const required = new Set(requiredList)
  const propertyNames = Object.keys(properties)
  if (requiredList.some((key) => !propertyNames.includes(key as string))
      || new Set(propertyNames.map((key) => key.toLowerCase())).size !== propertyNames.length
      || Number(root.minProperties ?? 0) > propertyNames.length
      || Number(root.maxProperties ?? 2147483647) < Math.max(1, required.size)
      || Number(root.minProperties ?? 0) > Number(root.maxProperties ?? 2147483647)) throw new Error('页面动作输入约束不受支持，请重新审核')
  const fields = Object.entries(properties).map(([name, raw]) => {
    if (!/^[A-Za-z_][A-Za-z0-9_]*$/.test(name) || RESERVED.has(name)
        || !raw || typeof raw !== 'object' || Array.isArray(raw)) {
      throw new Error('页面动作字段格式不受支持，请重新审核')
    }
    const field = raw as Record<string, unknown>
    if (typeof field.type !== 'string' || !['string', 'number', 'integer', 'boolean'].includes(field.type)
        || Object.keys(field).some((key) => !FIELD_KEYS.has(key)) || !nonnegative(field.minLength)
        || !nonnegative(field.maxLength) || ((field.minLength !== undefined || field.maxLength !== undefined) && field.type !== 'string')
        || Number(field.minLength ?? 0) > Number(field.maxLength ?? 2147483647)) throw new Error('页面动作字段格式不受支持，请重新审核')
    return { name, type: field.type, required: required.has(name), source: `input.${name}` }
  })
  const inputMapping = Object.fromEntries(fields.map((field) => [field.name, `params.${field.name}`]))
  return {
    schemaVersion: 2,
    inputSchema: JSON.parse(JSON.stringify(schema)),
    env: { ...existingEnv, reachaiTrustedPageDraft: true },
    nodes: [
      { id: 'user_input', type: 'USER_INPUT', config: { outputAlias: 'params', fields } },
      { id: 'page_action', type: 'PAGE_ACTION', config: { projectCode, pageKey: preview.pageKey,
        actionKey: preview.actionKey, route: preview.routePattern, inputMapping, confirm: true } },
    ],
    edges: [{ id: 'input_to_action', from: 'user_input', to: 'page_action' }],
    entryNodeId: 'user_input', exitNodeIds: ['page_action'],
  }
}
