import type { FailureCluster, RunSummary, VersionComparison } from '@/types/runops'

function textValue(value: unknown): string {
  if (value == null) return ''
  const text = String(value).trim()
  return text
}

function firstText(...values: unknown[]): string {
  for (const value of values) {
    const text = textValue(value)
    if (text) return text
  }
  return ''
}

function runMetadata(run: RunSummary): Record<string, unknown> {
  return run.metadata ?? {}
}

export function isWorkflowSourceType(sourceType?: string | null): boolean {
  const normalized = textValue(sourceType).toUpperCase()
  return normalized.startsWith('WORKFLOW')
}

export function runIsWorkflow(run: RunSummary): boolean {
  if (isWorkflowSourceType(runSourceType(run))) return true
  return Boolean(textValue(run.workflowId) || textValue(runMetadata(run).workflowId))
}

/** 从 RunOps 记录解析 Workflow 归属 ID；agentId 仅作旧数据兜底。 */
export function runWorkflowId(run: RunSummary): string {
  const metadata = runMetadata(run)
  const sourceType = firstText(run.sourceType, metadata.sourceType)
  return firstText(
    run.workflowId,
    metadata.workflowId,
    metadata.resolvedWorkflowId,
    isWorkflowSourceType(sourceType) ? run.sourceId : '',
    isWorkflowSourceType(sourceType) ? metadata.sourceId : '',
    run.agentId,
  )
}

export function runWorkflowKeySlug(run: RunSummary): string {
  const metadata = runMetadata(run)
  return firstText(run.workflowKeySlug, metadata.workflowKeySlug)
}

export function runWorkflowVersion(run: RunSummary): string {
  const metadata = runMetadata(run)
  return firstText(run.workflowVersion, metadata.workflowVersion, run.version, metadata.version)
}

export function runWorkflowVersionId(run: RunSummary): string {
  const metadata = runMetadata(run)
  return firstText(
    run.workflowVersionId,
    metadata.workflowVersionId,
    run.versionId,
    metadata.versionId,
  )
}

export function runEntryAgentId(run: RunSummary): string {
  const metadata = runMetadata(run)
  return firstText(run.entryAgentId, metadata.entryAgentId)
}

export function runEntryAgentKeySlug(run: RunSummary): string {
  const metadata = runMetadata(run)
  return firstText(run.entryAgentKeySlug, metadata.entryAgentKeySlug)
}

export function runSourceType(run: RunSummary): string {
  const metadata = runMetadata(run)
  return firstText(run.sourceType, metadata.sourceType)
}

export function runSourceId(run: RunSummary): string {
  const metadata = runMetadata(run)
  const sourceType = runSourceType(run)
  return firstText(
    run.sourceId,
    metadata.sourceId,
    isWorkflowSourceType(sourceType) ? runWorkflowId(run) : '',
  )
}

/** 展示名：优先 Workflow 语义，再 fallback 旧 agentName。 */
export function runDisplayName(run: RunSummary): string {
  const metadata = runMetadata(run)
  return firstText(
    metadata.workflowName,
    run.workflowKeySlug,
    metadata.workflowKeySlug,
    isWorkflowSourceType(runSourceType(run)) ? run.agentName : '',
    run.agentName,
    runWorkflowId(run),
    run.agentId,
    'Workflow',
  )
}

export function runPrimaryIdentityLabel(run: RunSummary): string {
  return runIsWorkflow(run) ? runDisplayName(run) : firstText(run.agentName, run.agentId, '-')
}

export function runSecondaryIdentityLabel(run: RunSummary): string {
  if (runIsWorkflow(run)) {
    return firstText(runWorkflowKeySlug(run), runWorkflowId(run), runSourceId(run), '-')
  }
  return firstText(run.agentId, '-')
}

export function runPrimaryIdentityId(run: RunSummary): string {
  return runIsWorkflow(run) ? firstText(runWorkflowId(run), runSourceId(run)) : firstText(run.agentId, '-')
}

export function runVersionLabel(run: RunSummary): string {
  if (runIsWorkflow(run)) {
    return firstText(runWorkflowVersion(run), runWorkflowVersionId(run), run.version, '-')
  }
  return firstText(run.version, run.versionId ? String(run.versionId) : '', '-')
}

export function runKindLabel(run: RunSummary): 'Workflow' | 'Agent' | 'Tool' | 'Unknown' {
  const sourceType = runSourceType(run).toUpperCase()
  if (isWorkflowSourceType(sourceType) || runIsWorkflow(run)) return 'Workflow'
  if (sourceType.includes('TOOL') || (run.toolCallCount ?? 0) > 0 && !run.agentName && !run.workflowId) {
    return 'Tool'
  }
  if (run.agentId || run.agentName) return 'Agent'
  return 'Unknown'
}

export function runEntryLabel(run: RunSummary): string {
  return firstText(runEntryAgentKeySlug(run), runEntryAgentId(run))
}

export function runSearchHaystack(run: RunSummary): string {
  return [
    run.traceId,
    runPrimaryIdentityLabel(run),
    runPrimaryIdentityId(run),
    runSecondaryIdentityLabel(run),
    runDisplayName(run),
    run.agentName,
    run.agentId,
    runWorkflowId(run),
    runWorkflowKeySlug(run),
    runSourceType(run),
    runSourceId(run),
    runEntryAgentId(run),
    runEntryAgentKeySlug(run),
    run.version,
    runWorkflowVersion(run),
    run.runtimeType,
    run.runtimePlacement,
  ]
    .filter(Boolean)
    .join(' ')
    .toLowerCase()
}

export function clusterPrimaryLabel(cluster: FailureCluster): string {
  if (cluster.sourceType && isWorkflowSourceType(cluster.sourceType)) {
    return firstText(cluster.workflowKeySlug, cluster.workflowId, cluster.agentName, '-')
  }
  return firstText(cluster.agentName, cluster.agentId, '-')
}

export function clusterVersionLabel(cluster: FailureCluster): string {
  if (cluster.sourceType && isWorkflowSourceType(cluster.sourceType)) {
    return firstText(cluster.workflowVersion, cluster.workflowVersionId ? String(cluster.workflowVersionId) : '', cluster.version, '-')
  }
  return firstText(cluster.version, cluster.versionId ? String(cluster.versionId) : '', '-')
}

export function comparisonPrimaryLabel(row: VersionComparison): string {
  if (row.sourceType && isWorkflowSourceType(row.sourceType)) {
    return firstText(row.workflowKeySlug, row.workflowId, row.agentName, '-')
  }
  return firstText(row.agentName, row.agentId, '-')
}

export function comparisonVersionLabel(row: VersionComparison): string {
  if (row.sourceType && isWorkflowSourceType(row.sourceType)) {
    return firstText(row.workflowVersion, row.workflowVersionId ? String(row.workflowVersionId) : '', row.version, '-')
  }
  return firstText(row.version, row.versionId ? String(row.versionId) : '', '-')
}

export function runMatchesCurrentWorkflow(
  run: RunSummary,
  workflowId?: string,
  workflowName?: string,
  workflowKeySlug?: string,
): boolean {
  const currentId = textValue(workflowId)
  const currentName = textValue(workflowName)
  const currentSlug = textValue(workflowKeySlug)
  const resolvedWorkflowId = runWorkflowId(run)
  if (currentId && resolvedWorkflowId && resolvedWorkflowId === currentId) {
    return true
  }
  if (currentSlug) {
    const runSlug = runWorkflowKeySlug(run)
    if (runSlug && runSlug === currentSlug) return true
  }
  if (currentName && textValue(runDisplayName(run)) === currentName) {
    return true
  }
  if (currentId && textValue(run.agentId) === currentId) {
    return true
  }
  return !currentId && !currentName && !currentSlug
}
