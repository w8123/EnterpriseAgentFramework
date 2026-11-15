import { controlRequest } from './request'

/** PENDING 待审批；APPROVED/REJECTED 已决定、等发起人那边继续；CONTINUED_* 已继续执行；WITHDRAWN 发起人撤回。 */
export type WorkflowApprovalStatus =
  | 'PENDING'
  | 'APPROVED'
  | 'REJECTED'
  | 'CONTINUED_APPROVED'
  | 'CONTINUED_REJECTED'
  | 'WITHDRAWN'
  | 'EXPIRED'
  | 'COMPLETED'
  | 'FAILED'
  | 'UNKNOWN'

export interface WorkflowApprovalDecision {
  decision: 'approved' | 'rejected'
  approverName?: string
  comment?: string
  decidedAt?: string
  onBehalf?: boolean
}

export interface WorkflowApprovalItem {
  interactionId: string
  title: string
  message?: string
  workflowId?: string
  workflowName?: string
  agentName?: string
  projectCode?: string
  runId?: string
  traceId?: string
  runnerName?: string
  runnerUserId?: string
  assignees?: string
  status: WorkflowApprovalStatus
  sessionStatus?: string
  createdAt?: string
  expiresAt?: string
  assignedToMe?: boolean
  selfRun?: boolean
  decision?: WorkflowApprovalDecision
}

export type WorkflowApprovalScope = 'pending' | 'decided' | 'all'

export interface ApproverOptions {
  users: Array<{ username: string; displayName: string }>
  roles: Array<{ code: string; label: string }>
}

export function listWorkflowApprovals(scope: WorkflowApprovalScope = 'pending') {
  return controlRequest.get<{ items: WorkflowApprovalItem[] }>('/api/me/workflow-approvals',
    { params: { scope }, errorFeedback: 'local' })
}

export function countWorkflowApprovals() {
  return controlRequest.get<{ count: number }>('/api/me/workflow-approvals/count', { errorFeedback: 'local' })
}

export function decideWorkflowApproval(interactionId: string, decision: 'approve' | 'reject', comment?: string) {
  return controlRequest.post<WorkflowApprovalItem>(
    `/api/me/workflow-approvals/${encodeURIComponent(interactionId)}/decision`,
    { decision, comment: comment?.trim() || undefined },
    { errorFeedback: 'local' },
  )
}

export function getApproverOptions() {
  return controlRequest.get<ApproverOptions>('/api/me/workflow-approvals/approver-options', { errorFeedback: 'local' })
}

export const WORKFLOW_APPROVAL_STATUS_LABEL: Record<string, string> = {
  PENDING: '待审批',
  APPROVED: '已批准，等发起人继续',
  REJECTED: '已拒绝，等发起人继续',
  CONTINUED_APPROVED: '已批准',
  CONTINUED_REJECTED: '已拒绝',
  WITHDRAWN: '发起人已撤回',
  EXPIRED: '已超时',
  COMPLETED: '已结束',
  FAILED: '执行失败',
  UNKNOWN: '未知',
}
