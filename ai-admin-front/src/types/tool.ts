/** Tool 参数定义 */
export interface ToolParameter {
  name: string
  type: string
  description: string
  required: boolean
  location?: string | null
  /** body_json 解析出的 DTO 子字段（可递归），仅展示用，运行时 body 以整体 JSON 传入 */
  children?: ToolParameter[]
}

/** 已注册 Tool 信息 */
export interface ToolInfo {
  name: string
  description: string
  parameters: ToolParameter[]
  source: 'code' | 'scanner' | 'manual'
  sourceLocation?: string | null
  httpMethod?: string | null
  baseUrl?: string | null
  contextPath?: string | null
  endpointPath?: string | null
  requestBodyType?: string | null
  responseType?: string | null
  projectId?: number | null
  /** 扫描项目显示名，由后端根据 `projectId` 解析；无项目时多为 null */
  sourceProjectName?: string | null
  /** 从扫描项目语义/接口文档同步的「AI 理解」摘要，用于 Agent 与列表展示 */
  aiDescription?: string | null
  enabled: boolean
  agentVisible: boolean
  lightweightEnabled: boolean
}

export interface ToolUpsertRequest {
  name: string
  description: string
  parameters: ToolParameter[]
  source: 'code' | 'scanner' | 'manual'
  sourceLocation?: string | null
  httpMethod?: string | null
  baseUrl?: string | null
  contextPath?: string | null
  endpointPath?: string | null
  requestBodyType?: string | null
  responseType?: string | null
  projectId?: number | null
  enabled: boolean
  agentVisible: boolean
  lightweightEnabled: boolean
}

/** Tool 测试请求 */
export interface ToolTestRequest {
  args: Record<string, unknown>
}

/** Tool 测试结果 */
export interface ToolTestResult {
  success: boolean
  result: string
  errorMessage?: string
  durationMs: number
}

/** Tool 列表查询（与 GET /api/tools 查询参数一致） */
export interface ToolListQuery {
  current?: number
  size?: number
  /** 匹配工具名或描述（模糊） */
  keyword?: string
  source?: 'code' | 'scanner' | 'manual' | string
  enabled?: boolean
  projectId?: number
}

/** Tool 列表分页结果 */
export interface ToolPageResult {
  records: ToolInfo[]
  total: number
  size: number
  current: number
  pages: number
}
