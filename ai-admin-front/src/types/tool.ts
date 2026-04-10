/** Tool 参数定义 */
export interface ToolParameter {
  name: string
  type: string
  description: string
  required: boolean
}

/** 已注册 Tool 信息 */
export interface ToolInfo {
  name: string
  description: string
  parameters: ToolParameter[]
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
