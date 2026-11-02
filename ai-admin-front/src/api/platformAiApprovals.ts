import { controlRequest } from './request'

export type PlatformAiApprovalStatus = 'PENDING' | 'APPROVED' | 'REJECTED' | 'EXPIRED' | 'EXECUTING' | 'SUCCEEDED' | 'FAILED' | 'UNKNOWN'

export interface PlatformAiApproval {
  id: string
  connectionId: number
  action: string
  subject: string
  invocationId: string
  status: PlatformAiApprovalStatus
  requestDigest: string
  arguments?: Record<string, unknown> | null
  resultCode?: string | null
  createdAt: string
  expiresAt: string
  decidedAt?: string | null
  completedAt?: string | null
}

export function listPlatformAiApprovals() {
  return controlRequest.get<PlatformAiApproval[]>('/api/me/ai-approvals', { errorFeedback: 'local' })
}

export function reviewPlatformAiApproval(id: string) {
  return controlRequest.get<PlatformAiApproval>(`/api/me/ai-approvals/${encodeURIComponent(id)}`, { errorFeedback: 'local' })
}

export function decidePlatformAiApproval(id: string, expectedDigest: string, approve: boolean) {
  const decision = approve ? 'approve' : 'reject'
  return controlRequest.post<PlatformAiApproval>(`/api/me/ai-approvals/${encodeURIComponent(id)}/${decision}`,
    { expectedDigest }, { errorFeedback: 'local' })
}
