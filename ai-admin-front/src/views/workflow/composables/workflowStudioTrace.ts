import type { WorkflowDebugStepResult } from '@/types/workflow'

export interface WorkflowNodeTraceState {
  nodeId: string
  status: 'success' | 'error' | 'waiting' | 'running'
  elapsedMs?: number
  spanType?: string
  toolName?: string
  input?: string
  output?: string
  errorCode?: string
  route?: string
  interactionId?: string
  createdAt?: string
}

export function debugStepStatus(status?: string): WorkflowNodeTraceState['status'] {
  const normalized = (status || '').trim().toUpperCase()
  if (normalized === 'RUNNING' || normalized === 'EXECUTING') return 'running'
  if (normalized === 'SUSPENDED' || normalized === 'WAITING' || normalized === 'WAITING_USER') return 'waiting'
  if (normalized === 'ERROR' || normalized === 'FAILED' || normalized === 'FAILURE' || normalized === 'FAIL') return 'error'
  if (['SUCCESS', 'OK', 'COMPLETED'].includes(normalized)) return 'success'
  return 'success'
}

export function stringifyDebugPayload(value: unknown) {
  if (value === null || value === undefined || value === '') return '-'
  if (typeof value === 'string') return value
  try {
    return JSON.stringify(value, null, 2)
  } catch {
    return String(value)
  }
}

export function formatElapsed(value?: number) {
  if (value === null || value === undefined) return '-'
  if (value < 1000) return `${value}ms`
  return `${(value / 1000).toFixed(2)}s`
}

export function debugWaitingOutput(step: WorkflowDebugStepResult) {
  const raw = objectPayload(step.rawOutput)
  if (raw.status === 'WAITING') return raw
  const output = objectPayload(step.output)
  const lastOutput = objectPayload(output.lastOutput)
  return lastOutput.status === 'WAITING' ? lastOutput : null
}

function objectPayload(value: unknown): Record<string, unknown> {
  return value && typeof value === 'object' && !Array.isArray(value)
    ? value as Record<string, unknown>
    : {}
}
