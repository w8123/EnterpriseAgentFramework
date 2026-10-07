export interface AssetUsage {
  kind: string
  id: string | number
  name?: string
  stage?: string
  version?: string
  versionId?: number
  nodeId?: string
  workflowId?: string
  agentConfigVersionId?: number
}

export interface AssetReferences {
  runtimeEvidence: 'COMPLETE' | 'PARTIAL' | 'UNKNOWN'
  publicationEvidence: 'COMPLETE' | 'PARTIAL' | 'UNKNOWN'
  references: AssetUsage[]
  checkedAt?: string
}
