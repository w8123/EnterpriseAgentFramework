/**
 * ReachAI workflow file: GraphSpec + canvas layout + a list of what the workflow depends on.
 * Imports always become a new draft in the target project; dependencies are listed so the author can rebind
 * business capabilities and knowledge bases that don't exist there.
 */
export const WORKFLOW_FILE_FORMAT = 'reachai-workflow'
export const WORKFLOW_FILE_VERSION = 1
const MAX_FILE_BYTES = 2 * 1024 * 1024

export interface WorkflowFileDependencies {
  tools: string[]
  knowledgeBases: string[]
  subWorkflows: string[]
  models: string[]
}

export interface WorkflowFile {
  format: typeof WORKFLOW_FILE_FORMAT
  version: number
  exportedAt: string
  workflow: {
    name: string
    keySlug: string
    description?: string | null
    workflowKind?: string | null
  }
  graphSpec: Record<string, unknown>
  canvas?: Record<string, unknown> | null
  dependencies: WorkflowFileDependencies
}

interface GraphNodeLike {
  id?: string
  type?: string
  ref?: { name?: string; qualifiedName?: string } | null
  config?: Record<string, unknown> | null
}

function text(value: unknown) {
  return typeof value === 'string' ? value.trim() : ''
}

function push(target: string[], value: unknown) {
  const item = text(value)
  if (item && !target.includes(item)) target.push(item)
}

export function workflowDependencies(graphSpec: Record<string, unknown>): WorkflowFileDependencies {
  const deps: WorkflowFileDependencies = { tools: [], knowledgeBases: [], subWorkflows: [], models: [] }
  const nodes = Array.isArray(graphSpec.nodes) ? (graphSpec.nodes as GraphNodeLike[]) : []
  for (const node of nodes) {
    const config = node.config || {}
    const type = text(node.type).toUpperCase()
    if (type === 'TOOL') push(deps.tools, node.ref?.qualifiedName || node.ref?.name || config.qualifiedName || config.toolName)
    if (type === 'KNOWLEDGE_RETRIEVAL' && Array.isArray(config.knowledgeBaseCodes)) {
      for (const code of config.knowledgeBaseCodes) push(deps.knowledgeBases, code)
    }
    if (type === 'KNOWLEDGE_WRITE') {
      const write = (config.knowledgeWriteConfig as Record<string, unknown> | undefined) || config
      push(deps.knowledgeBases, write.knowledgeBaseCode)
    }
    if (type === 'SUB_WORKFLOW') {
      const sub = (config.subWorkflowConfig as Record<string, unknown> | undefined) || config
      push(deps.subWorkflows, sub.workflowName || sub.workflowId)
    }
    push(deps.models, config.modelInstanceId)
  }
  return deps
}

export function buildWorkflowFile(input: {
  name: string
  keySlug: string
  description?: string | null
  workflowKind?: string | null
  graphSpecJson: string
  canvasJson?: string | null
}): WorkflowFile {
  const graphSpec = JSON.parse(input.graphSpecJson || '{}') as Record<string, unknown>
  let canvas: Record<string, unknown> | null = null
  if (input.canvasJson?.trim()) {
    try {
      canvas = JSON.parse(input.canvasJson) as Record<string, unknown>
    } catch {
      canvas = null
    }
  }
  return {
    format: WORKFLOW_FILE_FORMAT,
    version: WORKFLOW_FILE_VERSION,
    exportedAt: new Date().toISOString(),
    workflow: {
      name: input.name,
      keySlug: input.keySlug,
      description: input.description || null,
      workflowKind: input.workflowKind || null,
    },
    graphSpec,
    canvas,
    dependencies: workflowDependencies(graphSpec),
  }
}

export function downloadWorkflowFile(file: WorkflowFile) {
  const blob = new Blob([JSON.stringify(file, null, 2)], { type: 'application/json' })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = `${file.workflow.keySlug || 'workflow'}.reachai-workflow.json`
  document.body.appendChild(link)
  link.click()
  link.remove()
  URL.revokeObjectURL(url)
}

/** Parses and checks an uploaded workflow file. Throws an Error with a Chinese message when it's not usable. */
export function parseWorkflowFile(content: string): WorkflowFile {
  if (content.length > MAX_FILE_BYTES) throw new Error('文件太大（超过 2MB），不像是工作流文件')
  let parsed: unknown
  try {
    parsed = JSON.parse(content)
  } catch {
    throw new Error('文件不是有效的 JSON')
  }
  const file = parsed as Partial<WorkflowFile>
  if (!file || typeof file !== 'object' || file.format !== WORKFLOW_FILE_FORMAT) {
    throw new Error('不是 ReachAI 工作流文件（缺少 format: reachai-workflow）')
  }
  if (typeof file.version !== 'number' || file.version > WORKFLOW_FILE_VERSION) {
    throw new Error('文件版本比当前系统新，请升级后再导入')
  }
  const graphSpec = file.graphSpec as Record<string, unknown> | undefined
  if (!graphSpec || typeof graphSpec !== 'object' || graphSpec.schemaVersion !== 2 || !Array.isArray(graphSpec.nodes)) {
    throw new Error('文件里没有可用的工作流图（GraphSpec v2）')
  }
  const workflow = file.workflow || { name: '', keySlug: '' }
  return {
    format: WORKFLOW_FILE_FORMAT,
    version: file.version,
    exportedAt: text(file.exportedAt),
    workflow: {
      name: text(workflow.name),
      keySlug: text(workflow.keySlug),
      description: text(workflow.description) || null,
      workflowKind: text(workflow.workflowKind) || null,
    },
    graphSpec,
    canvas: file.canvas && typeof file.canvas === 'object' ? file.canvas : null,
    // Recomputed from the graph so a hand-edited list can't hide a dependency.
    dependencies: workflowDependencies(graphSpec),
  }
}

/**
 * Model choices point at another server's model instances; drop them so imported model nodes use the
 * target workflow's default model.
 */
export function stripEnvironmentBindings(graphSpec: Record<string, unknown>): Record<string, unknown> {
  const copy = JSON.parse(JSON.stringify(graphSpec)) as Record<string, unknown>
  const nodes = Array.isArray(copy.nodes) ? (copy.nodes as GraphNodeLike[]) : []
  for (const node of nodes) {
    const config = node.config
    if (!config) continue
    delete config.modelInstanceId
    for (const key of ['llmConfig', 'classifierConfig', 'parameterConfig']) {
      const nested = config[key] as Record<string, unknown> | undefined
      if (nested && typeof nested === 'object') delete nested.modelInstanceId
    }
  }
  return copy
}
