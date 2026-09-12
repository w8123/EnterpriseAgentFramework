/** 内置能力模块编码 → 展示名 */
const CAPABILITY_MODULE_CODE_LABELS: Record<string, string> = {
  system: '系统内置能力',
}

/** 种子数据常见英文名称 → 中文（已有库未升级时兜底） */
const CAPABILITY_DISPLAY_NAME_LABELS: Record<string, string> = {
  'System Built-in Capability': '系统内置能力',
  'Echo Tool': '回声动作',
  'Echo Composition': '回声组合',
  'Echo Input Interaction': '回声输入交互',
  'Echo Result Interaction': '回声结果展示',
  'Interactive Echo Composition': '交互式回声组合',
}

const MODULE_SOURCE_TYPE_LABELS: Record<string, string> = {
  BUILTIN: '内置',
  SDK: 'SDK 接入',
  PLUGIN: '插件',
}

const EXECUTOR_TYPE_LABELS: Record<string, string> = {
  BEAN: 'Spring Bean',
  ECHO: '回声测试',
  HTTP: 'HTTP 调用',
}

const INTERACTION_TYPE_LABELS: Record<string, string> = {
  COLLECT_INPUT: '采集输入',
  PRESENT_OUTPUT: '展示输出',
  USER_CHOICE: '用户选择',
  CONFIRM_ACTION: '确认操作',
}

const SIDE_EFFECT_LABELS: Record<string, string> = {
  READ: '只读',
  READ_ONLY: '只读',
  IDEMPOTENT_WRITE: '幂等写入',
  WRITE: '写入',
  IRREVERSIBLE: '不可逆操作',
  NONE: '无副作用',
}

export const MODULE_SOURCE_SELECT_OPTIONS = [
  { value: 'BUILTIN', label: '内置' },
  { value: 'SDK', label: 'SDK 接入' },
  { value: 'PLUGIN', label: '插件' },
] as const

export const EXECUTOR_TYPE_SELECT_OPTIONS = [
  { value: 'BEAN', label: 'Spring Bean' },
  { value: 'ECHO', label: '回声测试' },
  { value: 'HTTP', label: 'HTTP 调用' },
] as const

export const INTERACTION_TYPE_SELECT_OPTIONS = [
  { value: 'COLLECT_INPUT', label: '采集输入' },
  { value: 'PRESENT_OUTPUT', label: '展示输出' },
  { value: 'USER_CHOICE', label: '用户选择' },
  { value: 'CONFIRM_ACTION', label: '确认操作' },
] as const

export function formatCapabilityDisplayName(name?: string | null, code?: string | null): string {
  if (code && CAPABILITY_MODULE_CODE_LABELS[code]) {
    return CAPABILITY_MODULE_CODE_LABELS[code]
  }
  if (name && CAPABILITY_DISPLAY_NAME_LABELS[name]) {
    return CAPABILITY_DISPLAY_NAME_LABELS[name]
  }
  return name?.trim() || code || '-'
}

export function formatModuleSourceTypeLabel(sourceType?: string | null): string {
  if (sourceType == null || sourceType === '') return '-'
  return MODULE_SOURCE_TYPE_LABELS[sourceType.toUpperCase()] ?? sourceType
}

export function formatExecutorTypeLabel(executorType?: string | null): string {
  if (executorType == null || executorType === '') return '-'
  return EXECUTOR_TYPE_LABELS[executorType.toUpperCase()] ?? executorType
}

export function formatInteractionTypeLabel(interactionType?: string | null): string {
  if (interactionType == null || interactionType === '') return '-'
  return INTERACTION_TYPE_LABELS[interactionType.toUpperCase()] ?? interactionType
}

export function formatSideEffectLabel(sideEffect?: string | null): string {
  if (sideEffect == null || sideEffect === '') return '-'
  return SIDE_EFFECT_LABELS[sideEffect.toUpperCase()] ?? sideEffect
}
