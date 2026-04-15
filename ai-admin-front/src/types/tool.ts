/** Tool 参数定义 */
export interface ToolParameter {
  name: string
  type: string
  description: string
  required: boolean
  location?: string | null
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

export interface ToolImportResult {
  importedCount: number
  toolNames: string[]
}
