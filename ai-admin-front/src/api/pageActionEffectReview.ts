import { controlRequest } from './request'

export type PageActionEffect = 'PAGE_LOCAL_DRAFT' | 'PERSISTENT'
export interface PageActionApproval {
  approvalId: number
  revision: number
  effect: PageActionEffect
  targetAlias: string
  implementationVersion: string
  fieldLabels: Record<string, string>
  catalogHash: string
  active: boolean
}
export interface PageActionReviewPreview {
  projectCode: string
  pageKey: string
  actionKey: string
  routePattern: string | null
  actionTitle: string
  actionDescription: string | null
  implementationVersion: string | null
  inputSchema: unknown
  riskLevel: string
  confirmRequired: boolean
  catalogHash: string
  currentRevision: number
  approval: PageActionApproval | null
}
export interface PageActionReviewCommand {
  effect: PageActionEffect
  expectedCatalogHash: string
  expectedRevision: number
  implementationVersion: string
  fieldLabels: Record<string, string>
  targetAlias: string
  responsibilityAccepted: true
}
function url(projectCode: string, pageKey: string, actionKey: string) {
  return `/api/registry/projects/${encodeURIComponent(projectCode)}/page-workbench/pages/${encodeURIComponent(pageKey)}/actions/${encodeURIComponent(actionKey)}/effect-review`
}
export function previewPageActionEffect(projectCode: string, pageKey: string, actionKey: string) {
  return controlRequest.get<PageActionReviewPreview>(url(projectCode, pageKey, actionKey), { errorFeedback: 'local' })
}
export function approvePageActionEffect(projectCode: string, pageKey: string, actionKey: string, command: PageActionReviewCommand) {
  return controlRequest.post<PageActionApproval>(url(projectCode, pageKey, actionKey), command, { errorFeedback: 'local' })
}
export function revokePageActionEffect(projectCode: string, pageKey: string, actionKey: string, approval: PageActionApproval) {
  return controlRequest.post<void>(`${url(projectCode, pageKey, actionKey)}/revoke`, {
    expectedApprovalId: approval.approvalId,
    expectedRevision: approval.revision,
    expectedCatalogHash: approval.catalogHash,
  }, { errorFeedback: 'local' })
}

