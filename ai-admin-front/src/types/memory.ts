export type PersonalMemoryType = 'FACT' | 'PREFERENCE' | 'RULE' | 'NOTE'
/** CANDIDATE is kept for memories confirmed before candidates were removed. */
export type PersonalMemorySource = 'MANUAL' | 'CONVERSATION' | 'AI_TOOL' | 'CANDIDATE'
export type PersonalMemoryLayer = 'PERSONAL' | 'PROJECT'
/** PREFERENCE groups are always handed to the AI; NOTE groups only when relevant. */
export type PersonalMemoryGroupKind = 'PREFERENCE' | 'NOTE'

export interface PersonalMemory {
  id: number
  type: PersonalMemoryType
  title?: string | null
  content: string
  tags?: string[] | null
  layer: PersonalMemoryLayer
  /** `personal` or `project:<code>` */
  scope: string
  projectCode?: string | null
  layerName?: string | null
  groupId?: number | null
  groupName?: string | null
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
  scope?: string
  groupId?: number
  agentId?: string
  keyword?: string
  limit?: number
  offset?: number
}

export interface PersonalMemoryGroup {
  id: number
  name: string
  kind: PersonalMemoryGroupKind
  count: number
}

export interface PersonalMemoryLayerView {
  layer: PersonalMemoryLayer
  scope: string
  projectCode?: string | null
  name: string
  count: number
  groups: PersonalMemoryGroup[]
}

export interface PersonalMemoryProjectOption {
  projectCode: string
  name: string
}

export interface PersonalMemoryTree {
  layers: PersonalMemoryLayerView[]
  addableProjects: PersonalMemoryProjectOption[]
  total: number
}

export interface PersonalMemoryRememberRequest {
  type?: PersonalMemoryType
  title?: string
  content: string
  groupId?: number
  projectCode?: string
  clientRequestId?: string
}

export interface PersonalMemoryRememberResult {
  memory: PersonalMemory
  created: boolean
  idempotentReplay: boolean
  updated: boolean
}

export interface PersonalMemoryUpdateRequest {
  title?: string
  content?: string
  groupId?: number
}

export interface PersonalMemoryGroupRequest {
  projectCode?: string | null
  name: string
  kind?: PersonalMemoryGroupKind
}

export interface PersonalMemoryEraseAllRequest {
  confirmation: 'ERASE_ALL_PERSONAL_MEMORIES'
}

export interface PersonalMemoryEraseAllResult {
  status: string
  memoriesErased: number
  groupsErased: number
  erasedAt: string
  idempotent: boolean
  retainedDomains: string[]
}

/** The calls a memory page needs; the console and the employee portal each supply their own transport. */
export interface PersonalMemoryApi {
  tree(): Promise<PersonalMemoryTree>
  list(params: PersonalMemoryListParams): Promise<PersonalMemoryPage>
  remember(data: PersonalMemoryRememberRequest): Promise<PersonalMemoryRememberResult>
  update(id: number, data: PersonalMemoryUpdateRequest): Promise<PersonalMemory>
  forget(id: number): Promise<unknown>
  eraseAll(data: PersonalMemoryEraseAllRequest): Promise<PersonalMemoryEraseAllResult>
  createGroup(data: PersonalMemoryGroupRequest): Promise<PersonalMemoryGroup>
  updateGroup(id: number, data: { name?: string; kind?: PersonalMemoryGroupKind }): Promise<PersonalMemoryGroup>
  deleteGroup(id: number): Promise<unknown>
  addProject(projectCode: string): Promise<PersonalMemoryLayerView>
  /** Optional: only the console offers a JSON download. */
  exportJson?(): Promise<Blob>
}
