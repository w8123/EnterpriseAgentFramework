import { controlRequest } from './request'
import type {
  AutomationDetail,
  AutomationOccurrence,
  AutomationReadiness,
  AutomationStatus,
  AutomationSummary,
  AutomationUpsertCommand,
} from '@/types/automation'

export function listAutomations(params?: {
  tenantId?: string
  projectCode?: string
  status?: AutomationStatus | ''
  keyword?: string
  limit?: number
}) {
  return controlRequest.get<AutomationSummary[]>('/api/automations', { params })
}

export function getAutomationReadiness() {
  return controlRequest.get<AutomationReadiness>('/api/automations/readiness')
}

export function getAutomation(automationKey: string) {
  return controlRequest.get<AutomationDetail>(`/api/automations/${encodeURIComponent(automationKey)}`)
}

export function createAutomation(data: AutomationUpsertCommand) {
  return controlRequest.post<AutomationDetail>('/api/automations', data)
}

export function updateAutomation(automationKey: string, data: AutomationUpsertCommand) {
  return controlRequest.put<AutomationDetail>(
    `/api/automations/${encodeURIComponent(automationKey)}`,
    data,
  )
}

export function pauseAutomation(automationKey: string, expectedRevision: number) {
  return controlRequest.post<AutomationDetail>(
    `/api/automations/${encodeURIComponent(automationKey)}:pause`,
    { expectedRevision },
  )
}

export function resumeAutomation(automationKey: string, expectedRevision: number) {
  return controlRequest.post<AutomationDetail>(
    `/api/automations/${encodeURIComponent(automationKey)}:resume`,
    { expectedRevision },
  )
}

export function archiveAutomation(automationKey: string, expectedRevision: number) {
  return controlRequest.delete<AutomationDetail>(
    `/api/automations/${encodeURIComponent(automationKey)}`,
    { params: { expectedRevision } },
  )
}

export function runAutomationNow(automationKey: string) {
  return controlRequest.post<AutomationOccurrence>(
    `/api/automations/${encodeURIComponent(automationKey)}:run`,
  )
}

export function listAutomationOccurrences(
  automationKey: string,
  params?: { status?: string; limit?: number },
) {
  return controlRequest.get<AutomationOccurrence[]>(
    `/api/automations/${encodeURIComponent(automationKey)}/occurrences`,
    { params },
  )
}

export function retryAutomationOccurrence(automationKey: string, occurrenceId: number) {
  return controlRequest.post<AutomationOccurrence>(
    `/api/automations/${encodeURIComponent(automationKey)}/occurrences/${occurrenceId}:retry`,
  )
}

export function cancelAutomationOccurrence(
  automationKey: string,
  occurrenceId: number,
  reason?: string,
) {
  return controlRequest.post<AutomationOccurrence>(
    `/api/automations/${encodeURIComponent(automationKey)}/occurrences/${occurrenceId}:cancel`,
    { reason },
  )
}
