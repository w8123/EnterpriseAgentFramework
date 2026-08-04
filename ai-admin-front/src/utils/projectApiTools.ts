import type { ProjectToolInfo } from '@/types/scanProject'

export interface ProjectApiToolFilters {
  keyword?: string
  toolLinkStatus?: string
  page?: number
  pageSize?: number
}

export function projectApiToolId(tool: ProjectToolInfo): number {
  return tool.scanToolId || tool.catalogScanToolId || 0
}

export function projectApiToolRef(tool: ProjectToolInfo): string {
  return tool.globalToolName || tool.name
}

export function projectApiToolQualifiedName(tool: ProjectToolInfo, projectCode?: string | null): string {
  if (tool.qualifiedName) return tool.qualifiedName
  const code = tool.projectCode || projectCode
  return code ? `${code}:${projectApiToolRef(tool)}` : projectApiToolRef(tool)
}

export function isProjectApiToolLinked(tool: ProjectToolInfo): boolean {
  return Boolean(tool.globalToolDefinitionId)
    && tool.toolLinkStatus !== 'NOT_LINKED'
    && tool.toolLinkStatus !== 'GLOBAL_MISSING'
}

export function isProjectApiToolSelectable(tool: ProjectToolInfo): boolean {
  return isProjectApiToolLinked(tool)
    && tool.enabled
    && !tool.removedFromSource
}

export function projectApiToolStatusLabel(tool: ProjectToolInfo): string {
  if (tool.removedFromSource || tool.toolLinkStatus === 'API_REMOVED_STALE') return '源接口已移除'
  if (!tool.globalToolDefinitionId || tool.toolLinkStatus === 'NOT_LINKED') return '需先添加为 Tool'
  if (tool.toolLinkStatus === 'GLOBAL_MISSING') return 'Tool 缺失'
  if (!tool.enabled) return '未启用'
  if (tool.toolLinkStatus === 'PENDING_UPDATE') return '可选，待更新'
  return '可选择'
}

export function projectApiToolParameterCount(tool: ProjectToolInfo): number {
  return tool.parameterCount ?? tool.parameters?.length ?? 0
}

export function filterProjectApiTools(
  tools: ProjectToolInfo[],
  filters: ProjectApiToolFilters = {},
): ProjectToolInfo[] {
  const keyword = (filters.keyword || '').trim().toLowerCase()
  const status = filters.toolLinkStatus || ''
  return tools.filter((tool) => {
    if (keyword && !projectApiToolMatchesKeyword(tool, keyword)) return false
    if (!status) return true
    if (status === 'LINKED') return isProjectApiToolLinked(tool)
    return tool.toolLinkStatus === status
  })
}

export function pageProjectApiTools(
  tools: ProjectToolInfo[],
  page = 1,
  pageSize = 10,
): ProjectToolInfo[] {
  const safePage = Math.max(1, page || 1)
  const safeSize = Math.max(1, pageSize || 10)
  const from = Math.min((safePage - 1) * safeSize, tools.length)
  return tools.slice(from, from + safeSize)
}

function projectApiToolMatchesKeyword(tool: ProjectToolInfo, keyword: string): boolean {
  return [
    tool.title,
    tool.name,
    tool.description,
    tool.aiDescription,
    tool.httpMethod,
    tool.endpointPath,
    tool.sourceLocation,
    tool.moduleDisplayName,
    tool.projectCode,
  ].some((value) => String(value || '').toLowerCase().includes(keyword))
}
