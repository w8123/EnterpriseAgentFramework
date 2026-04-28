export interface McpClient {
  id?: number
  name: string
  apiKeyPrefix?: string
  rolesJson?: string
  toolWhitelistJson?: string
  enabled?: boolean
  expiresAt?: string | null
  lastUsedAt?: string | null
  createdAt?: string
  updatedAt?: string
}

export interface McpClientCreateResult {
  id: number
  plaintextApiKey: string
  client: McpClient
  note: string
}

export interface McpVisibility {
  id?: number
  targetKind: 'TOOL' | 'SKILL'
  targetName: string
  exposed: boolean
  note?: string | null
  createdAt?: string
  updatedAt?: string
}

export interface McpCallLog {
  id?: number
  clientId?: number
  clientName?: string
  method: string
  toolName?: string
  success: boolean
  latencyMs?: number
  requestBody?: string
  responseBody?: string
  errorMessage?: string
  traceId?: string
  remoteIp?: string
  createdAt?: string
}
