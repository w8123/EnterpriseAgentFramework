import type { CanvasNodeKind } from '@/types/studio'
import type { AgentGraphNodeTypeDescriptor } from '@/types/agent'

export type StudioNodeCategory = 'system' | 'action' | 'flow' | 'integration'

export type StudioNodeRegistryItem = {
  kind: CanvasNodeKind
  label: string
  defaultLabel: string
  meta: string
  hint: string
  group: string
  category: StudioNodeCategory
  color: { bg: string; border: string }
  retryable?: boolean
}

export const STUDIO_NODE_GROUPS = [
  { title: '推理与能力', icon: 'Cpu' },
  { title: '流程控制与变量', icon: 'Operation' },
  { title: '集成与检索', icon: 'Connection' },
  { title: '知识与上下文', icon: 'Collection' },
] as const

export const STUDIO_NODE_REGISTRY: Record<CanvasNodeKind, StudioNodeRegistryItem> = {
  start: {
    kind: 'start',
    label: '开始',
    defaultLabel: '开始',
    meta: '入口',
    hint: '用户输入、上下文和启动参数进入流程。',
    group: '流程控制与变量',
    category: 'system',
    color: { bg: '#ecf5ff', border: '#409eff' },
  },
  end: {
    kind: 'end',
    label: '结束',
    defaultLabel: '结束',
    meta: '出口',
    hint: '流程最终输出。',
    group: '流程控制与变量',
    category: 'system',
    color: { bg: '#f0f9eb', border: '#67c23a' },
  },
  userInput: {
    kind: 'userInput',
    label: '用户输入',
    defaultLabel: '用户输入',
    meta: '表单入口',
    hint: '定义工作流入口字段，运行时写入 params，后续节点可通过 params.xxx 引用。',
    group: '娴佺▼鎺у埗涓庡彉閲?',
    category: 'flow',
    color: { bg: '#ecfdf5', border: '#10b981' },
  },
  interaction: {
    kind: 'interaction',
    label: '交互节点',
    defaultLabel: '智能交互',
    meta: '输入/输出',
    hint: '采集缺失参数、渲染业务结果或让用户选择确认，运行时写入结构化输出。',
    group: STUDIO_NODE_GROUPS[1].title,
    category: 'flow',
    color: { bg: '#f0fdfa', border: '#14b8a6' },
  },
  pageAction: {
    kind: 'pageAction',
    label: '页面动作',
    defaultLabel: '页面动作',
    meta: '前端联动',
    hint: '向宿主业务页面发起受控动作请求，例如打开详情、定位列表或刷新模块。',
    group: STUDIO_NODE_GROUPS[2].title,
    category: 'integration',
    color: { bg: '#f5f3ff', border: '#8b5cf6' },
  },
  llm: {
    kind: 'llm',
    label: '大模型节点',
    defaultLabel: '大模型',
    meta: '推理',
    hint: '模型推理、规划、总结，进入图规范的大模型节点。',
    group: '推理与能力',
    category: 'action',
    color: { bg: '#eef2ff', border: '#6366f1' },
  },
  skill: {
    kind: 'skill',
    label: '能力节点',
    defaultLabel: '新能力',
    meta: '能力',
    hint: '引用已注册的粗粒度能力，适合复用业务流程。',
    group: '推理与能力',
    category: 'action',
    color: { bg: '#fdf6ec', border: '#e6a23c' },
    retryable: true,
  },
  tool: {
    kind: 'tool',
    label: '工具节点',
    defaultLabel: '新工具',
    meta: '原子工具',
    hint: '引用原子工具，支持入参映射与输出别名。',
    group: '推理与能力',
    category: 'action',
    color: { bg: '#f4f4f5', border: '#909399' },
    retryable: true,
  },
  condition: {
    kind: 'condition',
    label: '条件分支',
    defaultLabel: '条件分支',
    meta: '条件',
    hint: '根据上游输出或错误状态选择不同出边。',
    group: '流程控制与变量',
    category: 'flow',
    color: { bg: '#fff7ed', border: '#f97316' },
  },
  classifier: {
    kind: 'classifier',
    label: '意图分类',
    defaultLabel: '意图分类',
    meta: '路由',
    hint: '按用户输入或上游输出命中业务意图分支。',
    group: '流程控制与变量',
    category: 'flow',
    color: { bg: '#fff7ed', border: '#ea580c' },
  },
  approval: {
    kind: 'approval',
    label: '人工确认',
    defaultLabel: '人工确认',
    meta: '审批',
    hint: '在关键动作前创建人工确认点，支持批准、拒绝和超时分支。',
    group: '流程控制与变量',
    category: 'flow',
    color: { bg: '#fefce8', border: '#ca8a04' },
  },
  loop: {
    kind: 'loop',
    label: '循环控制',
    defaultLabel: '循环控制',
    meta: '循环',
    hint: '控制列表迭代或重复尝试，输出继续/结束分支。',
    group: '流程控制与变量',
    category: 'flow',
    color: { bg: '#f0f9ff', border: '#0284c7' },
  },
  variable: {
    kind: 'variable',
    label: '变量赋值',
    defaultLabel: '变量赋值',
    meta: '状态',
    hint: '把输入、上游输出或常量写入流程变量。',
    group: '流程控制与变量',
    category: 'flow',
    color: { bg: '#ecfeff', border: '#0891b2' },
  },
  aggregate: {
    kind: 'aggregate',
    label: '变量聚合',
    defaultLabel: '变量聚合',
    meta: '聚合',
    hint: '把多个变量聚合成对象、数组或文本。',
    group: '流程控制与变量',
    category: 'flow',
    color: { bg: '#ecfeff', border: '#0891b2' },
  },
  code: {
    kind: 'code',
    label: '代码转换',
    defaultLabel: '代码转换',
    meta: '表达式',
    hint: '安全表达式模式，生成结构化输出字段。',
    group: '流程控制与变量',
    category: 'flow',
    color: { bg: '#f8fafc', border: '#475569' },
  },
  documentExtract: {
    kind: 'documentExtract',
    label: '文档抽取',
    defaultLabel: '文档抽取',
    meta: '抽取',
    hint: '从文档文本或接口响应中抽取结构化字段。',
    group: '流程控制与变量',
    category: 'flow',
    color: { bg: '#f8fafc', border: '#64748b' },
  },
  parameter: {
    kind: 'parameter',
    label: '参数提取',
    defaultLabel: '参数提取',
    meta: '提取',
    hint: '从输入或上游输出抽取结构化参数。',
    group: '流程控制与变量',
    category: 'flow',
    color: { bg: '#f0fdf4', border: '#16a34a' },
  },
  template: {
    kind: 'template',
    label: '模板转换',
    defaultLabel: '模板转换',
    meta: '模板',
    hint: '用 {{ variable }} 渲染文本，可作为最终回答。',
    group: '流程控制与变量',
    category: 'flow',
    color: { bg: '#f5f3ff', border: '#7c3aed' },
  },
  answer: {
    kind: 'answer',
    label: '回复节点',
    defaultLabel: '回复节点',
    meta: '输出',
    hint: '组装最终回复并写入 answer。',
    group: '流程控制与变量',
    category: 'flow',
    color: { bg: '#f0fdf4', border: '#16a34a' },
  },
  http: {
    kind: 'http',
    label: '接口请求',
    defaultLabel: '接口请求',
    meta: '接口',
    hint: '调用外部 HTTP API，并把响应写回流程变量。',
    group: STUDIO_NODE_GROUPS[2].title,
    category: 'integration',
    color: { bg: '#eff6ff', border: '#2563eb' },
    retryable: true,
  },
  mcp: {
    kind: 'mcp',
    label: 'MCP 调用',
    defaultLabel: 'MCP 调用',
    meta: '协议',
    hint: '通过 MCP 工具桥接外部上下文和动作。',
    group: '集成与检索',
    category: 'integration',
    color: { bg: '#eef2ff', border: '#4f46e5' },
    retryable: true,
  },
  knowledge: {
    kind: 'knowledge',
    label: '知识节点',
    defaultLabel: '知识库',
    meta: '检索',
    hint: '关联知识库组，作为检索上下文进入流程。',
    group: '知识与上下文',
    category: 'integration',
    color: { bg: '#fef0f0', border: '#f56c6c' },
    retryable: true,
  },
  knowledgeWrite: {
    kind: 'knowledgeWrite',
    label: '知识写入',
    defaultLabel: '知识写入',
    meta: '沉淀',
    hint: '把流程结果沉淀为知识库草稿或发布内容。',
    group: '知识与上下文',
    category: 'integration',
    color: { bg: '#fff1f2', border: '#e11d48' },
    retryable: true,
  },
}

export function studioNodeMeta(kind: CanvasNodeKind) {
  return STUDIO_NODE_REGISTRY[kind]
}

export function studioNodeLabel(kind?: string | null) {
  const meta = STUDIO_NODE_REGISTRY[String(kind || '') as CanvasNodeKind]
  return meta?.label || kind || '节点'
}

export function studioNodeDefaultLabel(kind: CanvasNodeKind) {
  return STUDIO_NODE_REGISTRY[kind].defaultLabel
}

export function studioNodeColor(kind: CanvasNodeKind) {
  return STUDIO_NODE_REGISTRY[kind].color
}

export function studioNodeCategory(kind: CanvasNodeKind) {
  return STUDIO_NODE_REGISTRY[kind].category
}

export function studioNodeRetryable(kind: CanvasNodeKind) {
  return STUDIO_NODE_REGISTRY[kind].retryable === true
}

export function enabledStudioNodeKinds(
  descriptors: AgentGraphNodeTypeDescriptor[],
  capabilityLoaded: boolean,
) {
  const builtIn = new Set<CanvasNodeKind>(['start', 'end', 'pageAction'])
  if (!capabilityLoaded || descriptors.length === 0) {
    return new Set(Object.keys(STUDIO_NODE_REGISTRY) as CanvasNodeKind[])
  }
  const enabled = new Set<CanvasNodeKind>(builtIn)
  const knownKinds = new Set(Object.keys(STUDIO_NODE_REGISTRY))
  for (const item of descriptors) {
    const kind = item.canvasKind
    if (knownKinds.has(kind)) {
      enabled.add(kind as CanvasNodeKind)
    }
  }
  return enabled
}

export function studioNodeCapabilityMap(descriptors: AgentGraphNodeTypeDescriptor[]) {
  const out: Partial<Record<CanvasNodeKind, AgentGraphNodeTypeDescriptor>> = {}
  const knownKinds = new Set(Object.keys(STUDIO_NODE_REGISTRY))
  for (const item of descriptors) {
    if (knownKinds.has(item.canvasKind)) {
      out[item.canvasKind as CanvasNodeKind] = item
    }
  }
  return out
}
