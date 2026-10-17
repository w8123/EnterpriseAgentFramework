export type PersonalMemoryType = 'FACT' | 'PREFERENCE' | 'RULE' | 'NOTE'
export type PersonalMemorySource = 'MANUAL' | 'CONVERSATION' | 'CANDIDATE'

export interface PersonalMemory {
  id: number
  type: PersonalMemoryType
  title?: string | null
  content: string
  tags?: string[] | null
  sourceType: PersonalMemorySource
  sourceAgentId?: string | null
  expiresAt?: string | null
  createdAt?: string | null
  updatedAt?: string | null
}

export interface PersonalMemoryPage {
  items: PersonalMemory[]
  total: number
  limit: number
  offset: number
}

export interface PersonalMemoryListParams {
  type?: PersonalMemoryType
  agentId?: string
  keyword?: string
  limit?: number
  offset?: number
}

export interface PersonalMemoryRememberRequest {
  type: PersonalMemoryType
  semanticKey?: string
  title?: string
  content: string
  tags?: string[]
  expiresAt?: string
  clientRequestId?: string
}

export interface PersonalMemoryRememberResult {
  memory: PersonalMemory
  created: boolean
  idempotentReplay: boolean
  updated: boolean
}

export interface PersonalMemoryUpdateRequest {
  type?: PersonalMemoryType
  title?: string
  content?: string
  tags?: string[]
  expiresAt?: string
}

export interface PersonalMemoryEraseAllRequest {
  confirmation: 'ERASE_ALL_PERSONAL_MEMORIES'
}

export interface PersonalMemoryEraseAllResult {
  status: string
  memoriesErased: number
  candidatesErased: number
  erasedAt: string
  idempotent: boolean
  retainedDomains: string[]
}

export interface PersonalMemoryCandidate {
  id: number
  type: PersonalMemoryType
  title?: string | null
  content: string
  reason?: string | null
  confidence?: number | null
  status: 'PENDING' | 'APPROVED' | 'REJECTED'
  conflictMemoryId?: number | null
  occurrenceCount?: number | null
  sourceAgentId?: string | null
  expiresAt?: string | null
  createdAt?: string | null
  lastSeenAt?: string | null
  reviewedAt?: string | null
  approvedMemoryId?: number | null
}

export interface PersonalMemoryCandidatePage {
  items: PersonalMemoryCandidate[]
  total: number
  limit: number
  offset: number
}

export interface PersonalMemoryCandidateReviewRequest {
  type?: PersonalMemoryType
  title?: string
  content?: string
}

export interface PersonalMemoryAudit {
  id: number
  eventType: string
  memoryId?: number | null
  decision?: string | null
  reason?: string | null
  agentId?: string | null
  createdAt: string
}
