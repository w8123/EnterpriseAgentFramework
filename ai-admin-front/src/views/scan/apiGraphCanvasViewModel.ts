import type { ApiGraphEdge, ApiGraphEdgeKind, ApiGraphNode } from '@/api/apiGraph'
import { buildOrthogonalRoundedPath } from './apiGraphCanvasGeometry'

export type DetailItem = { label: string, value: string | number | boolean }
export type FieldVM = { node: ApiGraphNode, name: string, type: string, path: string, description: string }
export type CascaderPath = Array<string | number>
export type ApiCardVM = {
  node: ApiGraphNode
  x: number
  y: number
  width: number
  height: number
  method: string
  path: string
  inFields: FieldVM[]
  outFields: FieldVM[]
}
export type DtoFieldVM = { key: string, name: string, type: string }
export type ParamCascaderOption = {
  value: string | number
  label: string
  disabled?: boolean
  children?: ParamCascaderOption[]
}
export type ParamPathTreeNode = {
  segment: string
  path: string
  node?: ApiGraphNode
  children: Map<string, ParamPathTreeNode>
}
export type ApiRelationItem = {
  kind: 'API' | 'DTO'
  node: ApiGraphNode
  edgeKinds: string[]
}
export type MiniGraphCard = {
  kind: 'API' | 'DTO'
  node: ApiGraphNode
  x: number
  y: number
  width: number
  current: boolean
}
export type MiniGraphEdge = {
  id: string
  kind: EdgeVisualKind
  path: string
  label: string
  labelX: number
  labelY: number
}
export type MiniGraphLayout = {
  width: number
  height: number
  cards: MiniGraphCard[]
  edges: MiniGraphEdge[]
}
export type DtoCardVM = {
  node: ApiGraphNode
  x: number
  y: number
  width: number
  height: number
  fields: DtoFieldVM[]
}
export type EdgeVisualKind = 'REQUEST_REF' | 'RESPONSE_REF' | 'MODEL_REF'
export type EdgeVM = {
  id: string
  raw: ApiGraphEdge
  visualKind: EdgeVisualKind
  path: string
  label: string
  labelX: number
  labelY: number
}

export const edgeKinds: EdgeVisualKind[] = ['REQUEST_REF', 'RESPONSE_REF', 'MODEL_REF']

export const paramCascaderProps = {
  checkStrictly: true,
  emitPath: true,
  expandTrigger: 'hover' as const,
}

/** 抽屉内级联：避免 flip/overflow 重定位抖动；列宽见全局样式 `.api-graph-param-cascader-popper` */
export const paramCascaderPopperClass = 'api-graph-param-cascader-popper'
export const paramCascaderPopperOptions = {
  strategy: 'fixed' as const,
  modifiers: [
    { name: 'flip', enabled: false },
    { name: 'preventOverflow', enabled: false },
  ],
}

export function groupBy<T>(items: T[], keyFn: (item: T) => number) {
  const map = new Map<number, T[]>()
  items.forEach((item) => {
    const key = keyFn(item)
    if (!map.has(key)) map.set(key, [])
    map.get(key)!.push(item)
  })
  return map
}

export function parseProps(node: ApiGraphNode): Record<string, any> {
  if (!node.propsJson) return {}
  try {
    const parsed = JSON.parse(node.propsJson)
    return parsed && typeof parsed === 'object' ? parsed : {}
  } catch {
    return {}
  }
}

export function toFieldVM(node: ApiGraphNode): FieldVM {
  const props = parseProps(node)
  const descRaw = props.description ?? props.aiDescription
  const description = descRaw != null && String(descRaw).trim() ? String(descRaw).trim() : ''
  return {
    node,
    name: String(props.paramPath || node.label),
    type: shortType(node.typeName || '-'),
    path: String(props.paramPath || node.label),
    description,
  }
}

export function buildApiMiniGraph(apiNode: ApiGraphNode, relations: ApiRelationItem[]): MiniGraphLayout {
  const width = 420
  const cardWidth = 172
  const cardHeight = 74
  const gapX = 24
  const gapY = 28
  const currentCard: MiniGraphCard = {
    kind: 'API',
    node: apiNode,
    x: (width - cardWidth) / 2,
    y: 12,
    width: cardWidth,
    current: true,
  }
  const cards: MiniGraphCard[] = [currentCard]
  const edges: MiniGraphEdge[] = []
  const relationCards = relations.map((relation, index) => {
    const col = index % 2
    const row = Math.floor(index / 2)
    return {
      kind: relation.kind,
      node: relation.node,
      x: 24 + col * (cardWidth + gapX),
      y: currentCard.y + cardHeight + gapY + row * (cardHeight + gapY),
      width: cardWidth,
      current: false,
    } satisfies MiniGraphCard
  })
  cards.push(...relationCards)

  relationCards.forEach((card, index) => {
    const relation = relations[index]
    const start = { x: currentCard.x + currentCard.width / 2, y: currentCard.y + cardHeight }
    const end = { x: card.x + card.width / 2, y: card.y }
    const edgeKind = relation.edgeKinds.includes(edgeKindLabel('MODEL_REF'))
      ? 'MODEL_REF'
      : relation.edgeKinds.includes(edgeKindLabel('RESPONSE_REF'))
        ? 'RESPONSE_REF'
        : 'REQUEST_REF'
    const { path, labelX, labelY } = buildOrthogonalRoundedPath(start.x, start.y, end.x, end.y, {
      radius: 10,
      bendT: 0.5,
    })
    edges.push({
      id: `${apiNode.id}-${card.kind}-${card.node.id}`,
      kind: edgeKind,
      path,
      label: relation.edgeKinds.join('、'),
      labelX,
      labelY: labelY + 4,
    })
  })

  const lastCard = relationCards[relationCards.length - 1]
  const height = lastCard ? lastCard.y + cardHeight + 18 : 0
  return { width, height, cards, edges }
}

export function selectedCascaderNodeId(path: CascaderPath) {
  const leaf = path[path.length - 1]
  if (typeof leaf === 'number' && Number.isFinite(leaf)) return leaf
  if (typeof leaf === 'string' && /^\d+$/.test(leaf)) return Number(leaf)
  return null
}

export function shortType(type: string) {
  return type
    .replace(/^java\.lang\./, '')
    .replace(/^java\.util\./, '')
    .replace(/com\.[\w.]+\./g, '')
}

export function simpleType(type?: string | null) {
  if (!type) return ''
  const cleaned = shortType(type)
  const genericMatch = cleaned.match(/<\s*([^>]+)\s*>/)
  return (genericMatch?.[1] || cleaned).split('.').pop() || cleaned
}

export function buildDtoFieldBuckets(
  dtoNodes: ApiGraphNode[],
  fieldNodes: ApiGraphNode[],
  fieldsByParent: Map<number, ApiGraphNode[]>,
) {
  const dtoLabels = new Set(dtoNodes.map((node) => node.label))
  const buckets = new Map<string, DtoFieldVM[]>()
  fieldNodes.forEach((field) => {
    const dtoLabel = simpleType(field.typeName)
    if (!dtoLabels.has(dtoLabel)) return
    const children = fieldsByParent.get(field.id) ?? []
    if (!buckets.has(dtoLabel)) buckets.set(dtoLabel, [])
    const bucket = buckets.get(dtoLabel)!
    children.forEach((child) => {
      const vm = toFieldVM(child)
      const name = vm.path.split('.').pop() || vm.name
      const key = `${name}:${vm.type}`
      if (!bucket.some((item) => item.key === key)) {
        bucket.push({ key, name, type: vm.type })
      }
    })
  })
  return buckets
}

export function edgeLabel(edge: ApiGraphEdge, target: ApiGraphNode) {
  if (edge.kind === 'REQUEST_REF') return `请求引用 ${target.label}`
  if (edge.kind === 'RESPONSE_REF') return `响应引用 ${target.label}`
  return ''
}

export function nodeStyle(card: ApiCardVM) {
  return {
    left: `${card.x}px`,
    top: `${card.y}px`,
    width: `${card.width}px`,
    minHeight: `${card.height}px`,
  }
}

export function inferDefaultEdgeKind(source: ApiGraphNode, target: ApiGraphNode): ApiGraphEdgeKind {
  if (target.kind === 'FIELD_IN') return 'REQUEST_REF'
  if (source.kind === 'FIELD_OUT') return 'RESPONSE_REF'
  if (source.kind === 'DTO' || target.kind === 'DTO') return 'MODEL_REF'
  return 'REQUEST_REF'
}

export function nodeKindLabel(kind: string) {
  const labels: Record<string, string> = {
    API: '接口',
    FIELD_IN: '入参',
    FIELD_OUT: '出参',
    DTO: '数据模型',
    MODULE: '模块',
  }
  return labels[kind] || kind
}

export function edgeKindLabel(kind: string) {
  const labels: Record<string, string> = {
    REQUEST_REF: '请求引用',
    RESPONSE_REF: '响应引用',
    MODEL_REF: '数据模型引用',
    BELONGS_TO: '从属关系',
  }
  return labels[kind] || kind
}

export function nodeTagType(kind: string) {
  if (kind === 'API') return 'primary'
  if (kind === 'DTO') return 'warning'
  if (kind === 'FIELD_OUT') return 'success'
  return 'info'
}

export function edgeTagType(kind: string) {
  if (kind === 'RESPONSE_REF') return 'success'
  if (kind === 'MODEL_REF') return 'warning'
  return 'primary'
}
