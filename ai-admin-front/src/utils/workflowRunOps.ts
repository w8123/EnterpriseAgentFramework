import type { RunSummary } from '@/types/runops'
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

export function runDisplayName(run: RunSummary): string {
  return runIsWorkflow(run)
    ? firstText(run.workflowName, run.workflowKeySlug, run.workflowId, 'Workflow')
    : firstText(run.agentName, run.agentKeySlug, run.agentId, 'Agent')
}

export function runVersionLabel(run: RunSummary): string {
  if (runIsWorkflow(run)) {
    return firstText(run.workflowVersion, run.workflowVersionId, '-')
  }
  return firstText(run.agentConfigVersion, run.agentConfigVersionId, '-')
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
