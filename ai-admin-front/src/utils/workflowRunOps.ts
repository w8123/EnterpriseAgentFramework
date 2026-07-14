import type { FailureCluster, RunSummary, VersionComparison } from '@/types/runops'

function textValue(value: unknown): string {
  if (value == null) return ''
  return String(value).trim()
}

function firstText(...values: unknown[]): string {
  for (const value of values) {
    const text = textValue(value)
    if (text) return text
  }
  return ''
}

export function runIsWorkflow(run: RunSummary): boolean {
  return run.runType === 'WORKFLOW'
}

export function runWorkflowId(run: RunSummary): string {
  return textValue(run.workflowId)
}

export function runWorkflowKeySlug(run: RunSummary): string {
  return textValue(run.workflowKeySlug)
}

export function runWorkflowVersion(run: RunSummary): string {
  return textValue(run.workflowVersion)
}

export function runWorkflowVersionId(run: RunSummary): string {
  return textValue(run.workflowVersionId)
}

export function runDisplayName(run: RunSummary): string {
  return runIsWorkflow(run)
    ? firstText(run.workflowName, run.workflowKeySlug, run.workflowId, 'Workflow')
    : firstText(run.agentName, run.agentKeySlug, run.agentId, 'Agent')
}

export function runPrimaryIdentityLabel(run: RunSummary): string {
  return runDisplayName(run)
}

export function runSecondaryIdentityLabel(run: RunSummary): string {
  return runIsWorkflow(run)
    ? firstText(run.workflowKeySlug, run.workflowId, '-')
    : firstText(run.agentKeySlug, run.agentId, '-')
}

export function runPrimaryIdentityId(run: RunSummary): string {
  return runIsWorkflow(run) ? firstText(run.workflowId, '-') : firstText(run.agentId, '-')
}

export function runVersionLabel(run: RunSummary): string {
  if (runIsWorkflow(run)) {
    return firstText(run.workflowVersion, run.workflowVersionId, '-')
  }
  return firstText(run.agentConfigVersion, run.agentConfigVersionId, '-')
}

export function runKindLabel(run: RunSummary): 'Workflow' | 'Agent' {
  return runIsWorkflow(run) ? 'Workflow' : 'Agent'
}

export function runEntryLabel(run: RunSummary): string {
  return textValue(run.entryType)
}

export function runSearchHaystack(run: RunSummary): string {
  return [
    run.traceId,
    run.runType,
    run.entryType,
    run.projectCode,
    run.userId,
    run.agentId,
    run.agentKeySlug,
    run.agentName,
    run.agentConfigVersion,
    run.workflowId,
    run.workflowKeySlug,
    run.workflowName,
    run.workflowVersion,
    run.runtimeType,
    run.errorCode,
    run.errorMessage,
  ]
    .filter(Boolean)
    .join(' ')
    .toLowerCase()
}

export function clusterPrimaryLabel(cluster: FailureCluster): string {
  return cluster.versionType === 'WORKFLOW'
    ? firstText(cluster.workflowName, cluster.workflowId, '-')
    : firstText(cluster.agentName, cluster.agentId, '-')
}

export function clusterVersionLabel(cluster: FailureCluster): string {
  return cluster.versionType === 'WORKFLOW'
    ? firstText(cluster.workflowVersion, cluster.workflowVersionId, '-')
    : firstText(cluster.agentConfigVersion, cluster.agentConfigVersionId, '-')
}

export function comparisonPrimaryLabel(row: VersionComparison): string {
  return row.versionType === 'WORKFLOW'
    ? firstText(row.workflowName, row.workflowId, '-')
    : firstText(row.agentName, row.agentId, '-')
}

export function comparisonVersionLabel(row: VersionComparison): string {
  return row.versionType === 'WORKFLOW'
    ? firstText(row.workflowVersion, row.workflowVersionId, '-')
    : firstText(row.agentConfigVersion, row.agentConfigVersionId, '-')
}

export function runMatchesCurrentWorkflow(
  run: RunSummary,
  workflowId?: string,
  workflowName?: string,
  workflowKeySlug?: string,
): boolean {
  if (!runIsWorkflow(run)) return false
  const currentId = textValue(workflowId)
  const currentName = textValue(workflowName)
  const currentSlug = textValue(workflowKeySlug)
  if (currentId && textValue(run.workflowId) === currentId) return true
  if (currentSlug && textValue(run.workflowKeySlug) === currentSlug) return true
  if (currentName && textValue(run.workflowName) === currentName) return true
  return !currentId && !currentName && !currentSlug
}
