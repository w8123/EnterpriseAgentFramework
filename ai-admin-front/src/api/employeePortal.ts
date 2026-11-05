/** Employee credentials stay in the existing, independently authenticated portal domain. */
export const EMPLOYEE_PORTAL_TOKEN_KEY = 'reachai.employee-portal.token'

export interface EmployeeProfile {
  userName?: string
  account: string
  projectCode: string
  systemName: string
}

export interface EmployeeCapabilities {
  projectCode: string
  name: string
  tools: { name: string; description?: string | null; readOnly: boolean; approvalRequired: boolean }[]
}

export interface EmployeeCall {
  id: number
  toolName?: string | null
  businessSystem?: string | null
  success: boolean
  errorCategory?: string | null
  createdAt?: string | null
}

export interface EmployeeApproval {
  approvalId: number
  approvalStatus: string
  tool: string
  businessSystem: string
  comment?: string
}

export class EmployeePortalError extends Error {
  constructor(message: string, public readonly status: number) { super(message) }
}

export async function employeePortalRequest<T>(token: string, path: string, signal?: AbortSignal): Promise<T> {
  const response = await fetch(`/api/employee-portal${path}`, {
    headers: { Authorization: `Bearer ${token}` },
    signal,
    credentials: 'omit',
  })
  const payload = await response.json().catch(() => ({}))
  if (!response.ok || (payload.code != null && payload.code !== 200 && payload.code !== 0)) {
    throw new EmployeePortalError(payload.message || '暂时无法读取业务系统，请稍后重试。', response.status)
  }
  return payload.data as T
}
