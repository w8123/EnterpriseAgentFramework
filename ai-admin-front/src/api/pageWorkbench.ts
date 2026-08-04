import { controlRequest } from './request'
import type {
  AnalysisFindingStatus,
  ManualProjectPageRequest,
  PageAnalysisFinding,
  PageIntegrationReadiness,
  PageAccessCenterOverview,
  PageMapSummary,
  ProjectPage,
  PublishedPageWorkflow,
  WorkflowDeliveryRequest,
  WorkflowDeliveryResult,
} from '@/types/pageWorkbench'

function root(projectCode: string) {
  return `/api/registry/projects/${encodeURIComponent(projectCode)}/page-workbench`
}

export function listWorkbenchPages(projectCode: string, includeArchived = false) {
  return controlRequest.get<ProjectPage[]>(`${root(projectCode)}/pages`, {
    params: { includeArchived },
  })
}

export function getPageAccessCenterOverview(projectCode: string) {
  return controlRequest.get<PageAccessCenterOverview>(`${root(projectCode)}/access-center`)
}

export function createWorkbenchPage(projectCode: string, data: ManualProjectPageRequest) {
  return controlRequest.post<ProjectPage>(`${root(projectCode)}/pages`, data)
}

export function getWorkbenchPageReadiness(projectCode: string, pageId: number) {
  return controlRequest.get<PageIntegrationReadiness>(
    `${root(projectCode)}/pages/${pageId}/readiness`,
  )
}

export function getPageMapSummary(projectCode: string) {
  return controlRequest.get<PageMapSummary>(`${root(projectCode)}/page-map`)
}

export function listPageAnalysisFindings(projectCode: string, pageId?: number) {
  return controlRequest.get<PageAnalysisFinding[]>(`${root(projectCode)}/findings`, {
    params: { pageId },
  })
}

export function updatePageAnalysisFindingStatus(
  projectCode: string,
  findingId: number,
  status: AnalysisFindingStatus,
) {
  return controlRequest.patch<PageAnalysisFinding>(
    `${root(projectCode)}/findings/${findingId}/status`,
    { status },
  )
}

export function listPublishedPageWorkflows(projectCode: string, pageKey?: string) {
  return controlRequest.get<PublishedPageWorkflow[]>(`${root(projectCode)}/published`, {
    params: { pageKey },
  })
}

export function deliverWorkflowEngineeringTask(
  projectCode: string,
  taskId: string,
  data: WorkflowDeliveryRequest,
) {
  return controlRequest.post<WorkflowDeliveryResult>(
    `${root(projectCode)}/workflow-engineering/tasks/${encodeURIComponent(taskId)}/deliver`,
    data,
  )
}
