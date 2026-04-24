import type { AgentDefinition, AgentForm } from '@/types/agent'
import type { CanvasNode, CanvasEdge, CanvasSnapshot, CanvasNodeKind } from '@/types/studio'

/**
 * 画布 → Agent 定义：
 * 1. skill / tool 节点的 ref 展平成 tools[] 白名单（去重、保序）；
 * 2. knowledge 节点取首个 groupId 写入 knowledgeBaseGroupId；
 * 3. 画布整体序列化成 canvasJson 存储。
 */
export function canvasToDefinition(
  base: AgentForm,
  snapshot: CanvasSnapshot,
): AgentForm {
  const tools: string[] = []
  const seen = new Set<string>()
  let knowledgeGroupId: string | undefined

  for (const node of snapshot.nodes) {
    if ((node.data.kind === 'skill' || node.data.kind === 'tool') && node.data.ref) {
      if (!seen.has(node.data.ref)) {
        seen.add(node.data.ref)
        tools.push(node.data.ref)
      }
    }
    if (node.data.kind === 'knowledge' && node.data.groupId && !knowledgeGroupId) {
      knowledgeGroupId = node.data.groupId
    }
  }

  return {
    ...base,
    tools,
    knowledgeBaseGroupId: knowledgeGroupId ?? base.knowledgeBaseGroupId ?? '',
    canvasJson: JSON.stringify(snapshot),
  }
}

/**
 * Agent 定义 → 画布：
 * - 若 `canvasJson` 存在且可解析，优先使用；
 * - 否则按 `tools[]` 自动布局生成最简画布（start → 每个 tool → end）。
 */
export function definitionToCanvas(def: AgentDefinition): CanvasSnapshot {
  if (def.canvasJson) {
    try {
      const parsed = JSON.parse(def.canvasJson) as CanvasSnapshot
      if (Array.isArray(parsed.nodes) && Array.isArray(parsed.edges)) {
        return parsed
      }
    } catch {
      // 回落到自动生成
    }
  }

  const nodes: CanvasNode[] = []
  const edges: CanvasEdge[] = []

  nodes.push({
    id: 'start',
    type: 'start',
    position: { x: 60, y: 220 },
    data: { label: '开始', kind: 'start' },
  })

  const toolNames = def.tools ?? []
  toolNames.forEach((name, idx) => {
    nodes.push({
      id: `node-${idx}`,
      type: 'tool',
      position: { x: 260 + idx * 220, y: 220 },
      data: { label: name, kind: 'tool', ref: name },
    })
    edges.push({
      id: `e-start-${idx}`,
      source: idx === 0 ? 'start' : `node-${idx - 1}`,
      target: `node-${idx}`,
    })
  })

  if (def.knowledgeBaseGroupId) {
    nodes.push({
      id: 'kb',
      type: 'knowledge',
      position: { x: 260, y: 60 },
      data: { label: def.knowledgeBaseGroupId, kind: 'knowledge', groupId: def.knowledgeBaseGroupId },
    })
  }

  const lastIdx = toolNames.length - 1
  nodes.push({
    id: 'end',
    type: 'end',
    position: { x: 260 + (toolNames.length || 1) * 220, y: 220 },
    data: { label: '结束', kind: 'end' },
  })
  edges.push({
    id: 'e-to-end',
    source: lastIdx >= 0 ? `node-${lastIdx}` : 'start',
    target: 'end',
  })

  return { nodes, edges }
}

const KIND_COLOR: Record<CanvasNodeKind, { bg: string; border: string }> = {
  start: { bg: '#ecf5ff', border: '#409eff' },
  end: { bg: '#f0f9eb', border: '#67c23a' },
  skill: { bg: '#fdf6ec', border: '#e6a23c' },
  tool: { bg: '#f4f4f5', border: '#909399' },
  knowledge: { bg: '#fef0f0', border: '#f56c6c' },
}

export function kindColor(kind: CanvasNodeKind) {
  return KIND_COLOR[kind]
}
