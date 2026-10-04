import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { AxiosError, type AxiosResponse, type InternalAxiosRequestConfig } from 'axios'
import { ElMessage } from 'element-plus'
import { controlRequest } from '@/api/request'
import { getScanProjects } from '@/api/scanProject'
import { getAgentStatistics, listAgents, listWorkflows } from '@/api/workflow'
import { getRecentRunOps } from '@/api/runops'
import {
  isPlatformAuthenticated, markPlatformSessionAnonymous, markPlatformSessionAuthenticated, resetPlatformSessionForTest,
} from '@/auth/platformSession'

vi.mock('element-plus', () => ({ ElMessage: { error: vi.fn(), warning: vi.fn(), info: vi.fn(), success: vi.fn() } }))
const originalAdapter = controlRequest.defaults.adapter
const local = { errorFeedback: 'local' as const }
const requests = [
  () => getScanProjects({}, local),
  () => listAgents(undefined, local),
  () => listWorkflows(undefined, local),
  () => getAgentStatistics(undefined, local),
  () => getRecentRunOps({ days: 1, limit: 100 }, local),
]
function rejectStatus(status: number, headers: Record<string, string> = {}) {
  const adapter = vi.fn(async (config: InternalAxiosRequestConfig) => {
    const response: AxiosResponse = { config, status, headers, statusText: String(status), data: { message: 'scoped rejection' } }
    throw new AxiosError('scoped rejection', AxiosError.ERR_BAD_RESPONSE, config, undefined, response)
  })
  controlRequest.defaults.adapter = adapter
  return adapter
}
beforeEach(() => {
  vi.clearAllMocks()
  sessionStorage.clear()
  localStorage.clear()
  resetPlatformSessionForTest()
  markPlatformSessionAuthenticated({ sessionId: 'dashboard-request-session', expiresAt: '2030-01-01T00:00:00.000Z',
    principal: { userId: 5, username: 'project-reader', permissions: ['platform:read'] } })
  vi.spyOn(window.location, 'assign').mockImplementation(() => undefined)
})
afterEach(() => {
  controlRequest.defaults.adapter = originalAdapter
  vi.restoreAllMocks()
  markPlatformSessionAnonymous(false)
  sessionStorage.clear()
  localStorage.clear()
})
describe('Dashboard 本地反馈保留真实请求与共享会话处理', () => {
  it('每个数据包装器传递本地反馈但不绕过401，403保留登录且不弹重复技术错误', async () => {
    const adapter = rejectStatus(403)
    const outcomes = await Promise.allSettled(requests.map(request => request()))
    expect(outcomes.every(outcome => outcome.status === 'rejected')).toBe(true)
    expect(adapter.mock.calls).toHaveLength(5)
    for (const [config] of adapter.mock.calls) {
      expect(config.errorFeedback).toBe('local')
      expect(config.platformAuthFailure).toBeUndefined()
    }
    expect(isPlatformAuthenticated.value).toBe(true)
    expect(ElMessage.error).not.toHaveBeenCalled()
    expect(adapter.mock.calls[4][0].params).toEqual({ days: 1, limit: 100 })
  })

  it('本地反馈不屏蔽带平台失效标记的401与登录重定向', async () => {
    rejectStatus(401, { 'x-reachai-auth-failure': 'PLATFORM_SESSION_INVALID' })
    await expect(getRecentRunOps({ days: 1, limit: 100 }, local)).rejects.toBeInstanceOf(AxiosError)
    expect(isPlatformAuthenticated.value).toBe(false)
    expect(window.location.assign).toHaveBeenCalled()
  })

  it.each([401, 503])('没有平台失效标记的%s不会被本地反馈误清会话', async status => {
    rejectStatus(status)
    await expect(listAgents(undefined, local)).rejects.toBeInstanceOf(AxiosError)
    expect(isPlatformAuthenticated.value).toBe(true)
    expect(window.location.assign).not.toHaveBeenCalled()
  })
})
