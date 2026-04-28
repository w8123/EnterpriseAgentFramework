export interface DomainDef {
  id?: number
  code: string
  name: string
  description?: string
  keywordsJson?: string
  parentCode?: string | null
  agentVisible?: boolean
  enabled?: boolean
  createdAt?: string
  updatedAt?: string
}

export interface DomainAssignment {
  id?: number
  targetKind: 'TOOL' | 'SKILL' | 'PROJECT' | 'AGENT'
  targetName: string
  domainCode: string
  weight?: number
  source?: 'MANUAL' | 'AUTO_FROM_PROJECT'
  createdAt?: string
  updatedAt?: string
}

export interface DomainCoverageRow {
  domainCode: string
  name: string
  toolCount: number
  skillCount: number
  agentCount: number
  projectCount: number
}

export interface DomainClassifyHit {
  domainCode: string
  score: number
  name?: string
  toolCount?: number
}

export interface DomainClassifyResponse {
  text: string
  results: DomainClassifyHit[]
}

export interface TargetRefBody {
  kind: 'TOOL' | 'SKILL' | 'PROJECT' | 'AGENT'
  name: string
  weight?: number
}
