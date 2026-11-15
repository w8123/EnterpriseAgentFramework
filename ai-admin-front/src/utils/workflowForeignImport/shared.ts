import type { AgentForm } from '@/types/agent'
import type {
  CanvasEdge,
  CanvasNode,
  CanvasNodeData,
  CanvasNodeKind,
  CanvasSnapshot,
  ConditionOperator,
} from '@/types/studio'
import { canvasToDefinition, createDefaultNodeData } from '@/utils/studio'

/** Where an imported workflow came from. */
export type ForeignWorkflowSource = 'dify' | 'n8n'

/** ok = converted as is; review = converted, worth a look; todo = placeholder the author must finish. */
export type ImportNoteLevel = 'ok' | 'review' | 'todo'

export interface ImportNote {
  node: string
  from: string
  result: string
  level: ImportNoteLevel
}

export interface ForeignImportReport {
  source: ForeignWorkflowSource
  sourceLabel: string
  converted: number
  placeholders: number
  notes: ImportNote[]
  /** Whole-workflow remarks (dropped nodes, serialized parallel branches, ...). */
  remarks: string[]
}

export interface ForeignImportResult {
  name: string
  keySlug: string
  description: string
  graphSpec: Record<string, unknown>
  canvas: Record<string, unknown> | null
  report: ForeignImportReport
}

interface DraftNode {
  id: string
  kind: CanvasNodeKind
  label: string
  x: number
  y: number
  data: Partial<CanvasNodeData>
}

export interface DraftEdge {
  from: string
  to: string
  /** always | else | error | route:<id> */
  condition: string
}

const BASE: AgentForm = {
  keySlug: 'import',
  name: 'import',
  description: '',
  intentType: '',
  systemPrompt: '',
  tools: [],
  modelInstanceId: '',
  maxSteps: 10,
  type: 'workflow',
  enabled: true,
  extra: {},
} as unknown as AgentForm

/**
 * Collects converted nodes and edges, then lays them out and runs the same canvas → GraphSpec conversion the
 * Studio uses on save, so an import is always an ordinary draft built from existing node types.
 */
export class DraftGraph {
  readonly nodes: DraftNode[] = []
  readonly edges: DraftEdge[] = []
  readonly notes: ImportNote[] = []
  readonly remarks: string[] = []
  private readonly ids = new Set<string>(['start', 'end'])

  /** A stable, unique node id derived from the source id. */
  uniqueId(raw: string, fallback = 'node') {
    let base = String(raw || '').replace(/[^A-Za-z0-9_]+/g, '_').replace(/^_+|_+$/g, '').slice(0, 48)
    if (!base || /^[0-9]/.test(base)) base = `${fallback}_${base}`.replace(/_+$/g, '')
    let id = base
    let index = 2
    while (this.ids.has(id)) id = `${base}_${index++}`
    this.ids.add(id)
    return id
  }

  add(node: DraftNode) {
    this.nodes.push(node)
    return node
  }

  connect(from: string, to: string, condition = 'always') {
    if (!from || !to || from === to) return
    if (this.edges.some((edge) => edge.from === from && edge.to === to && edge.condition === condition)) return
    this.edges.push({ from, to, condition })
  }

  note(node: string, from: string, result: string, level: ImportNoteLevel = 'ok') {
    this.notes.push({ node, from, result, level })
  }

  /**
   * The engine walks one path at a time (branching happens on condition routes). Plain fan-out from one node to
   * several next nodes is rewritten so the branches run one after another; when that is not possible only the
   * first branch is kept and the author is told.
   */
  serializeFanOut() {
    for (let guard = 0; guard < 50; guard++) {
      const source = this.nodes.find((node) => this.plainOut(node.id).length > 1)
      if (!source) return
      const branches = this.plainOut(source.id).map((edge) => edge.to)
      const chains = branches.map((entry) => this.chainFrom(entry, branches))
      const join = this.commonJoin(chains)
      const ok = chains.every((chain) => chain !== null) && (join !== undefined)
      if (!ok) {
        const kept = branches[0]
        this.edges.splice(0, this.edges.length, ...this.edges.filter((edge) => !(edge.from === source.id
          && edge.condition === 'always' && edge.to !== kept)))
        this.remarks.push(`「${source.label}」后面有多条同时执行的分支，结构太复杂，只保留了第一条，其余分支节点还在画布上，请手动接上。`)
        continue
      }
      const list = chains as string[][]
      // Drop the fan-out edges and the joins of every branch but the last, then link branch tails to the next head.
      this.edges.splice(0, this.edges.length, ...this.edges.filter((edge) => {
        if (edge.from === source.id && edge.condition === 'always' && branches.includes(edge.to) && edge.to !== branches[0]) return false
        return true
      }))
      for (let index = 0; index < list.length - 1; index++) {
        const tail = list[index][list[index].length - 1]
        if (join) {
          const at = this.edges.findIndex((edge) => edge.from === tail && edge.to === join && edge.condition === 'always')
          if (at >= 0) this.edges.splice(at, 1)
        }
        this.connect(tail, list[index + 1][0])
      }
      const names = branches.map((id) => this.nodes.find((node) => node.id === id)?.label || id)
      this.remarks.push(`「${source.label}」后的 ${branches.length} 条并行分支（${names.join('、')}）已改成依次执行，结果不变，只是不再同时跑。`)
    }
  }

  /** Drops edges that loop back to an earlier node (n8n "loop over items" wiring); loops use the LOOP node here. */
  breakCycles() {
    const state = new Map<string, number>()
    const dropped: DraftEdge[] = []
    const visit = (id: string) => {
      state.set(id, 1)
      for (const edge of this.edges.filter((item) => item.from === id)) {
        const seen = state.get(edge.to)
        if (seen === 1) dropped.push(edge)
        else if (!seen) visit(edge.to)
      }
      state.set(id, 2)
    }
    const targets = new Set(this.edges.map((edge) => edge.to))
    for (const node of this.nodes) if (!targets.has(node.id) && !state.get(node.id)) visit(node.id)
    for (const node of this.nodes) if (!state.get(node.id)) visit(node.id)
    if (!dropped.length) return
    this.edges.splice(0, this.edges.length, ...this.edges.filter((edge) => !dropped.includes(edge)))
    const label = (id: string) => this.nodes.find((node) => node.id === id)?.label || id
    this.remarks.push(`去掉了 ${dropped.length} 条往回连的线（${dropped.map((edge) => `${label(edge.from)} → ${label(edge.to)}`).join('、')}）；需要重复执行的步骤请改用循环节点。`)
  }

  private plainOut(id: string) {
    return this.edges.filter((edge) => edge.from === id && edge.condition === 'always')
  }

  /** A branch that is a simple chain: each node has at most one outgoing edge, all plain. */
  private chainFrom(entry: string, siblings: string[]): string[] | null {
    const chain: string[] = []
    let current: string | undefined = entry
    while (current && chain.length < 40) {
      if (chain.includes(current)) return null
      chain.push(current)
      const out = this.edges.filter((edge) => edge.from === current)
      if (out.length === 0) return chain
      if (out.length > 1 || out[0].condition !== 'always') return null
      const next = out[0].to
      const incoming = this.edges.filter((edge) => edge.to === next).length
      if (incoming > 1 || siblings.includes(next)) {
        chain.push(`→${next}`)
        return chain
      }
      current = next
    }
    return null
  }

  /** The join node all branches lead into (`→id` marker), null when every branch just ends, undefined when mixed. */
  private commonJoin(chains: Array<string[] | null>): string | null | undefined {
    let join: string | null | undefined
    for (const chain of chains) {
      if (!chain) return undefined
      const last = chain[chain.length - 1]
      const target = last.startsWith('→') ? last.slice(1) : null
      if (last.startsWith('→')) chain.pop()
      if (join === undefined) join = target
      else if (join !== target) return undefined
    }
    return join
  }

  build(input: { name: string; keySlug: string; description: string; source: ForeignWorkflowSource; sourceLabel: string }): ForeignImportResult {
    const entryTargets = new Set(this.edges.map((edge) => edge.to))
    const minX = Math.min(...this.nodes.map((node) => node.x), 0)
    const minY = Math.min(...this.nodes.map((node) => node.y), 0)
    const offsetX = 260 - minX
    const offsetY = 120 - minY
    const loopBody = new Set<string>()
    for (const node of this.nodes) {
      for (const id of node.data.loopConfig?.bodyNodeIds || []) loopBody.add(id)
    }
    const roots = this.nodes.filter((node) => !entryTargets.has(node.id) && !loopBody.has(node.id))
    const exits = this.nodes.filter((node) => !loopBody.has(node.id) && !this.edges.some((edge) => edge.from === node.id))
    const edges: DraftEdge[] = [...this.edges]
    if (roots[0]) edges.unshift({ from: 'start', to: roots[0].id, condition: 'always' })
    if (roots.length > 1) {
      this.remarks.push(`有 ${roots.length - 1} 个节点前面没有连线（${roots.slice(1).map((node) => node.label).join('、')}），请确认它们该接在哪里。`)
    }
    for (const exit of exits) edges.push({ from: exit.id, to: 'end', condition: 'always' })

    const lastX = Math.max(...this.nodes.map((node) => node.x + offsetX), 260)
    const firstY = roots[0] ? roots[0].y + offsetY : 220
    const canvasNodes: CanvasNode[] = [
      { id: 'start', type: 'start', position: { x: 20, y: firstY }, data: { label: '开始', kind: 'start', configVersion: 2 } },
      ...this.nodes.map((node) => ({
        id: node.id,
        type: node.kind,
        position: { x: Math.round(node.x + offsetX), y: Math.round(node.y + offsetY) },
        data: { ...createDefaultNodeData(node.kind, node.label, BASE), ...node.data, label: node.label } as CanvasNodeData,
      })),
      { id: 'end', type: 'end', position: { x: lastX + 300, y: firstY }, data: { label: '结束', kind: 'end', configVersion: 2 } },
    ]
    const canvasEdges: CanvasEdge[] = edges.map((edge, index) => {
      const route = edge.condition.startsWith('route:') ? edge.condition.slice('route:'.length) : edge.condition === 'else' ? 'else' : ''
      return {
        id: `imp-e-${index}-${edge.from}-${edge.to}`,
        source: edge.from,
        target: edge.to,
        condition: edge.condition,
        label: edge.condition,
        ...(route ? { sourceHandle: route } : {}),
      } as CanvasEdge
    })
    const snapshot: CanvasSnapshot = { version: 2, nodes: canvasNodes, edges: canvasEdges }
    const definition = canvasToDefinition(BASE, snapshot)
    let canvas: Record<string, unknown> | null = null
    try {
      canvas = definition.canvasJson ? JSON.parse(definition.canvasJson) as Record<string, unknown> : null
    } catch {
      canvas = null
    }
    const placeholders = this.notes.filter((note) => note.level === 'todo').length
    return {
      name: input.name,
      keySlug: input.keySlug,
      description: input.description,
      graphSpec: definition.graphSpec as unknown as Record<string, unknown>,
      canvas,
      report: {
        source: input.source,
        sourceLabel: input.sourceLabel,
        converted: this.notes.length - placeholders,
        placeholders,
        notes: this.notes,
        remarks: this.remarks,
      },
    }
  }
}

/** A tool node left unbound, carrying what the original step was so the author can pick a capability. */
export function placeholderTool(note: string, inputs: Record<string, string> = {}): Partial<CanvasNodeData> {
  return {
    description: `导入占位：${note}`,
    needsConfiguration: true,
    placeholderReason: `请绑定业务能力：${note}`,
    toolConfig: { inputMapping: inputs, mappingNote: note },
  } as Partial<CanvasNodeData>
}

export function llmData(systemPrompt: string, userPrompt: string, extra: { json?: boolean; temperature?: number } = {}): Partial<CanvasNodeData> {
  const modelParams: Record<string, unknown> = {}
  if (typeof extra.temperature === 'number' && Number.isFinite(extra.temperature)) modelParams.temperature = extra.temperature
  return {
    llmConfig: {
      systemPrompt,
      userPrompt,
      messages: [
        ...(systemPrompt ? [{ id: 'system', role: 'system' as const, content: systemPrompt, templateEngine: 'mustache' as const, enabled: true }] : []),
        { id: 'user', role: 'user' as const, content: userPrompt || '{{ input }}', templateEngine: 'mustache' as const, enabled: true },
      ],
      outputFormat: extra.json ? 'json' : 'text',
      modelParams,
      promptTemplateMode: 'messages',
    },
  } as Partial<CanvasNodeData>
}

export function answerData(template: string): Partial<CanvasNodeData> {
  return { answerConfig: { template }, template, writeToAnswer: true } as Partial<CanvasNodeData>
}

/** Route ids must be plain identifiers. */
export function routeId(raw: string, fallback: string) {
  const id = String(raw || '').replace(/[^A-Za-z0-9_-]+/g, '_').replace(/^_+|_+$/g, '').slice(0, 48)
  return id || fallback
}

export function slugFrom(value: string, fallback: string) {
  const slug = String(value || '').trim().toLowerCase().replace(/[^a-z0-9_-]+/g, '-').replace(/^-+|-+$/g, '').slice(0, 100)
  return slug.length >= 2 ? slug : fallback
}

export function asRecord(value: unknown): Record<string, unknown> {
  return value && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : {}
}

export function asList(value: unknown): unknown[] {
  return Array.isArray(value) ? value : []
}

export function str(value: unknown): string {
  if (typeof value === 'string') return value
  if (typeof value === 'number' || typeof value === 'boolean') return String(value)
  return ''
}

/** Maps comparison names used by Dify and n8n onto IF_ELSE operators. */
export function conditionOperator(raw: string): ConditionOperator | null {
  const key = String(raw || '').trim().toLowerCase().replace(/[\s-]+/g, '_')
  const table: Record<string, ConditionOperator> = {
    contains: 'contains',
    not_contains: 'not_contains',
    notcontains: 'not_contains',
    start_with: 'starts_with',
    starts_with: 'starts_with',
    startswith: 'starts_with',
    end_with: 'ends_with',
    ends_with: 'ends_with',
    endswith: 'ends_with',
    is: 'equals',
    equals: 'equals',
    equal: 'equals',
    '=': 'equals',
    is_not: 'not_equals',
    not_equals: 'not_equals',
    notequals: 'not_equals',
    '≠': 'not_equals',
    '!=': 'not_equals',
    empty: 'empty',
    is_empty: 'empty',
    isempty: 'empty',
    null: 'empty',
    not_exists: 'empty',
    notexists: 'empty',
    not_empty: 'not_empty',
    is_not_empty: 'not_empty',
    isnotempty: 'not_empty',
    not_null: 'not_empty',
    exists: 'exists',
    '>': 'gt',
    gt: 'gt',
    '<': 'lt',
    lt: 'lt',
    '≥': 'gte',
    '>=': 'gte',
    gte: 'gte',
    '≤': 'lte',
    '<=': 'lte',
    lte: 'lte',
    in: 'in',
    not_in: 'not_in',
    regex: 'regex',
    matches: 'regex',
    true: 'equals',
    false: 'equals',
  }
  return table[key] || null
}
