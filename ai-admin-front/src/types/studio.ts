/**
 * Agent Studio 画布数据结构
 *
 * Canvas 上的节点分成几类：
 * - start / end: 起止锚点（仅一个），描述 Agent 的输入/输出
 * - skill: 对应 tool_definition.kind = SKILL（引用 Skill 名）
 * - tool:  对应 tool_definition.kind = TOOL（引用 Tool 名）
 * - knowledge: 关联知识库组（主要用于 RAG 语义，落库到 knowledgeBaseGroupId 字段）
 *
 * Phase 3.0 MVP 的转换策略：
 * - 画布保存到 `canvas_json` 列（只存布局）；
 * - 同时把 skill/tool 节点的 "ref" 展平成 `AgentDefinition.tools`（执行器真正使用的白名单）；
 * - 知识库节点映射到 `AgentDefinition.knowledgeBaseGroupId`；
 * - 连线当前只做可视化，不参与执行（执行仍由 ReAct + Tool Retrieval 驱动）。
 */

export type CanvasNodeKind = 'start' | 'end' | 'skill' | 'tool' | 'knowledge'

export interface CanvasNodeData {
  /** 对 skill / tool 节点：引用的 tool_definition.name */
  ref?: string
  /** 稳定引用名，后续后端可优先按 qualifiedName 解析 */
  qualifiedName?: string | null
  projectCode?: string | null
  visibility?: string | null
  /** 对 knowledge 节点：知识库组 ID */
  groupId?: string
  /** 展示用描述 */
  description?: string
  /** 当前节点输出在流程变量中的别名，如 customer / contract */
  outputAlias?: string
  /** 入参映射：参数路径 -> 上游输出 / 上下文 / 常量表达式 */
  inputMapping?: Record<string, string>
  /** 变量映射备注，便于运营记录业务含义 */
  mappingNote?: string
}

export interface CanvasNode {
  id: string
  type: CanvasNodeKind
  position: { x: number; y: number }
  data: {
    label: string
    kind: CanvasNodeKind
    ref?: string
    qualifiedName?: string | null
    projectCode?: string | null
    visibility?: string | null
    groupId?: string
    description?: string
    outputAlias?: string
    inputMapping?: Record<string, string>
    mappingNote?: string
  }
}

export interface CanvasEdge {
  id: string
  source: string
  target: string
  label?: string
}

export interface CanvasSnapshot {
  nodes: CanvasNode[]
  edges: CanvasEdge[]
}
