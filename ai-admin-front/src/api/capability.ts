import { agentRequest } from './request'
import type {
  CapabilityAdminTestPendingItem,
  CapabilityInfo,
  CapabilityListQuery,
  CapabilityMetrics,
  CapabilityPageResult,
  CapabilityTestResult,
  CapabilityUpsertRequest,
} from '@/types/capability'

const BASE = '/api/capabilities'

export function listCapabilities(params?: CapabilityListQuery) {
  return agentRequest.get<CapabilityPageResult>(BASE, { params })
}

export function getCapabilityDetail(name: string) {
  return agentRequest.get<CapabilityInfo>(`${BASE}/${encodeURIComponent(name)}`)
}

export function createCapability(data: CapabilityUpsertRequest) {
  return agentRequest.post<CapabilityInfo>(BASE, data)
}

export function updateCapability(name: string, data: CapabilityUpsertRequest) {
  return agentRequest.put<CapabilityInfo>(`${BASE}/${encodeURIComponent(name)}`, data)
}

export function deleteCapability(name: string) {
  return agentRequest.delete(`${BASE}/${encodeURIComponent(name)}`)
}

export function toggleCapability(name: string, enabled: boolean) {
  return agentRequest.put<CapabilityInfo>(`${BASE}/${encodeURIComponent(name)}/toggle`, { enabled })
}

export function testCapability(name: string, args: Record<string, unknown>) {
  return agentRequest.post<CapabilityTestResult>(`${BASE}/${encodeURIComponent(name)}/test`, { args })
}

/** 交互式表单能力挂起后继续（确认卡 / 表单批交等） */
export function testCapabilityResume(
  name: string,
  body: { interactionId: string; action?: string; values?: Record<string, unknown> },
) {
  return agentRequest.post<CapabilityTestResult>(`${BASE}/${encodeURIComponent(name)}/test/resume`, body)
}

export function getCapabilityMetrics(name: string, days = 7) {
  return agentRequest.get<CapabilityMetrics>(`${BASE}/${encodeURIComponent(name)}/metrics`, {
    params: { days },
  })
}

export function getAdminTestPendingInteractions() {
  return agentRequest.get<CapabilityAdminTestPendingItem[]>(`${BASE}/pending-interactions/admin-test`)
}

export function cancelAdminTestPendingInteraction(interactionId: string) {
  return agentRequest.delete(`${BASE}/pending-interactions/admin-test/${encodeURIComponent(interactionId)}`)
}

export function cancelAllAdminTestPendingInteractions() {
  return agentRequest.post<{ cancelled: number }>(`${BASE}/pending-interactions/admin-test/cancel-all`)
}
