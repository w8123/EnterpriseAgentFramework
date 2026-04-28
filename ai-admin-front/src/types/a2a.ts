export interface A2aEndpoint {
  id?: number
  agentId: string
  agentKey: string
  cardJson: string
  enabled?: boolean
  createdAt?: string
  updatedAt?: string
}

export interface A2aEndpointDetail {
  entity: A2aEndpoint
  card: Record<string, unknown>
}

export interface A2aCallLog {
  id?: number
  endpointId?: number
  agentKey?: string
  taskId?: string
  method: string
  success: boolean
  latencyMs?: number
  requestBody?: string
  responseBody?: string
  errorMessage?: string
  traceId?: string
  remoteIp?: string
  createdAt?: string
}
