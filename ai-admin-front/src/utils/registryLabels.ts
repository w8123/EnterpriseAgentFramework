/** 接入实例状态（value 仍为后端枚举） */
const INSTANCE_STATUS_LABELS: Record<string, string> = {
  ONLINE: '在线',
  OFFLINE: '离线',
  DISABLED: '已禁用',
  STALE: '心跳超时',
}

/** Runtime 类型 / Adapter 标识 */
const RUNTIME_TYPE_LABELS: Record<string, string> = {
  SPRING_BOOT_EMBEDDED: 'Spring Boot 嵌入',
  SPRING_BOOT2_CAPABILITY_HOST: 'Spring Boot 2 接入侧',
  EMBEDDED_RUNTIME: '嵌入运行时',
  LANGGRAPH4J: 'LangGraph4j 工作流',
  AGENTSCOPE: 'AgentScope',
  WORKFLOW: '工作流',
}

export const INSTANCE_STATUS_SELECT_OPTIONS = [
  { value: 'ONLINE', label: '在线' },
  { value: 'OFFLINE', label: '离线' },
  { value: 'DISABLED', label: '已禁用' },
  { value: 'STALE', label: '心跳超时' },
] as const

export function formatInstanceStatusLabel(status?: string | null): string {
  if (status == null || status === '') return '-'
  const key = status.toUpperCase()
  return INSTANCE_STATUS_LABELS[key] ?? status
}

export function formatRuntimeTypeLabel(runtimeType?: string | null): string {
  if (runtimeType == null || runtimeType === '') return '-'
  const key = runtimeType.toUpperCase()
  return RUNTIME_TYPE_LABELS[key] ?? runtimeType
}
