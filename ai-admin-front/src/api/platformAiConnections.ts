import { controlRequest } from './request'

export type PlatformAiToolKind = 'CODEX' | 'CLAUDE_CODE' | 'OTHER'

export interface PlatformAiConnection {
  id: number
  label: string
  toolKind: PlatformAiToolKind
  tokenHint: string
  status: 'ACTIVE' | 'REVOKED' | 'EXPIRED'
  createdAt: string
  expiresAt: string
  lastUsedAt?: string | null
  revokedAt?: string | null
}

export interface CreatedPlatformAiConnection {
  connection: PlatformAiConnection
  token: string
}

export function listPlatformAiConnections() {
  return controlRequest.get<PlatformAiConnection[]>('/api/me/ai-connections', { errorFeedback: 'local' })
}

export function createPlatformAiConnection(body: { label: string; toolKind: PlatformAiToolKind; ttlDays: number }) {
  return controlRequest.post<CreatedPlatformAiConnection>('/api/me/ai-connections', body, { errorFeedback: 'local' })
}

export function revokePlatformAiConnection(id: number) {
  return controlRequest.post<PlatformAiConnection>(`/api/me/ai-connections/${id}/revoke`, null, { errorFeedback: 'local' })
}

/** 「我能办的事」: what a connection created now could do with this account's permissions. */
export interface PlatformAiCapability {
  name: string
  description: string
  readOnly: boolean
  approvalRequired: boolean
  executionMode: 'READ' | 'READ_ONLY_TRIAL' | 'PLATFORM_WRITE' | 'APPROVAL'
}

export interface PlatformAiCall {
  id: number
  connectionId?: number | null
  connectionLabel?: string | null
  toolKind?: string | null
  toolName?: string | null
  success: boolean
  latencyMs?: number | null
  errorCode?: string | null
  createdAt?: string | null
}

export function listPlatformAiCapabilities() {
  return controlRequest.get<PlatformAiCapability[]>('/api/me/ai-access/capabilities', { errorFeedback: 'local' })
}

export function listPlatformAiCalls() {
  return controlRequest.get<PlatformAiCall[]>('/api/me/ai-access/calls', { errorFeedback: 'local' })
}
