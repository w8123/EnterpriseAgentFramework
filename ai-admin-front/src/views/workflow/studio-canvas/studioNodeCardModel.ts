import type { CanvasNode, CanvasNodeKind } from '@/types/studio'
import { useWorkflowStudioNodeMetadata } from '../composables/useWorkflowStudioNodeMetadata'

type NodeData = CanvasNode['data']

export interface StudioNodeRouteRow {
  id: string
  handleId: string
  label: string
  meta: string
  isDefault?: boolean
}

/** What one canvas node card shows; every node kind renders through the same template. */
export interface StudioNodeCardModel {
  /** CSS modifier, e.g. `tool-node`; drives the accent colour. */
  kindClass: string
  icon: string
  /** Start/end use a single kicker instead of the kind/state pair. */
  kicker?: string
  kind?: string
  state?: string
  label: string
  description: string
  ports: string
  showAlias: boolean
  hasTarget: boolean
  hasSource: boolean
  routes: StudioNodeRouteRow[]
  needsConfig: boolean
}

/** Studio-level values some cards display (default model, project). */
export interface StudioNodeCardContext {
  defaultModelInstanceId?: string | null
  projectCode?: string | null
}

const { assignmentCount, userInputFieldCount, interactionFieldCount, interactionTypeLabel } =
  useWorkflowStudioNodeMetadata()

export function portSummary(ports: NodeData['inputs'] | NodeData['outputs'], fallback: string) {
  if (!ports?.length) return fallback
  const labels = ports
    .map((port) => port.name || port.id)
    .filter(Boolean)
    .slice(0, 2)
  return labels.length ? labels.join(', ') : fallback
}

function inOut(data: NodeData) {
  return `${portSummary(data.inputs, '入')} · ${portSummary(data.outputs, '出')}`
}

export function conditionRouteRows(data: NodeData): StudioNodeRouteRow[] {
  const config = data.conditionConfig
  const rows: StudioNodeRouteRow[] = (config?.groups || [])
    .filter((group) => group.id?.trim())
    .map((group) => ({
      id: group.id.trim(),
      handleId: group.id.trim(),
      label: group.label || group.id,
      meta: `${group.logic || 'AND'} · ${group.conditions?.length || 0} 条条件`,
      isDefault: false,
    }))
  const defaultRoute = (config?.defaultRoute || 'else').trim()
  if (defaultRoute && !rows.some((row) => row.handleId === defaultRoute)) {
    rows.push({
      id: `default-${defaultRoute}`,
      handleId: defaultRoute,
      label: defaultRoute === 'else' ? '默认分支' : defaultRoute,
      meta: '未命中条件时进入',
      isDefault: true,
    })
  }
  if (!rows.length) {
    for (const port of data.outputs || []) {
      const extended = port as { id?: string; name?: string; key?: string; label?: string }
      const id = String(extended.id || extended.name || extended.key || '').trim()
      if (!id) continue
      rows.push({
        id,
        handleId: id,
        label: String(extended.label || extended.name || id),
        meta: '条件分支',
        isDefault: id === 'else',
      })
    }
  }
  return rows.length ? rows : [{
    id: 'default-else',
    handleId: 'else',
    label: '默认分支',
    meta: '未命中条件时进入',
    isDefault: true,
  }]
}

export function approvalRouteRows(): StudioNodeRouteRow[] {
  return [
    { id: 'approved', handleId: 'approved', label: '批准', meta: '审批通过' },
    { id: 'rejected', handleId: 'rejected', label: '拒绝', meta: '审批驳回' },
    { id: 'timeout', handleId: 'timeout', label: '超时', meta: '等待超时' },
  ]
}

export function knowledgeEvidenceRouteRows(): StudioNodeRouteRow[] {
  return [
    { id: 'evidence', handleId: 'evidence', label: '有证据', meta: '检索命中，可基于证据回答', isDefault: false },
    { id: 'no_evidence', handleId: 'no_evidence', label: '无证据', meta: '固定回复，不进入 LLM 生成', isDefault: true },
  ]
}

export function loopNodeSummary(loopConfig?: {
  collection?: string
  outputAlias?: string
  maxIterations?: number
  bodyNodeIds?: string[]
} | null) {
  const collection = loopConfig?.collection?.trim() || '未配置集合'
  const outputAlias = loopConfig?.outputAlias?.trim() || 'loop_results'
  const maxIterations = loopConfig?.maxIterations && loopConfig.maxIterations > 0
    ? loopConfig.maxIterations
    : 100
  const bodyCount = Array.isArray(loopConfig?.bodyNodeIds) ? loopConfig.bodyNodeIds.length : 0
  return `${collection} → var.${outputAlias} · 最多 ${maxIterations} 次 · 循环体 ${bodyCount} 个节点`
}

export function classifierRouteRows(data: NodeData): StudioNodeRouteRow[] {
  const config = data.classifierConfig
  const rows: StudioNodeRouteRow[] = (config?.classes || [])
    .filter((item) => item.id?.trim())
    .map((item) => {
      const keywords = (item.keywords || []).filter(Boolean)
      return {
        id: item.id.trim(),
        handleId: item.id.trim(),
        label: item.label || item.id,
        meta: keywords.length ? keywords.slice(0, 3).join(' / ') : item.id,
        isDefault: false,
      }
    })
  const defaultRoute = (config?.defaultRoute || 'else').trim()
  if (defaultRoute && !rows.some((row) => row.handleId === defaultRoute)) {
    rows.push({
      id: `default-${defaultRoute}`,
      handleId: defaultRoute,
      label: defaultRoute === 'else' ? '默认分支' : defaultRoute,
      meta: '未命中分类时进入',
      isDefault: true,
    })
  }
  return rows.length ? rows : [{
    id: 'default-else',
    handleId: 'else',
    label: '默认分支',
    meta: '未命中分类时进入',
    isDefault: true,
  }]
}

const base = (kindClass: string, icon: string, data: NodeData, id: string) => ({
  kindClass,
  icon,
  label: data.label || id,
  description: '',
  ports: inOut(data),
  showAlias: true,
  hasTarget: true,
  hasSource: true,
  routes: [] as StudioNodeRouteRow[],
  needsConfig: false,
})

export function studioNodeCardModel(
  kind: CanvasNodeKind,
  id: string,
  data: NodeData,
  context: StudioNodeCardContext = {},
): StudioNodeCardModel {
  switch (kind) {
    case 'start':
      return {
        ...base('start-node', 'VideoPlay', data, id),
        label: data.label,
        kicker: '入口',
        description: '用户输入 / 上下文入口',
        ports: portSummary(data.outputs, '输出'),
        showAlias: false,
        hasTarget: false,
      }
    case 'end':
      return {
        ...base('end-node', 'Finished', data, id),
        label: data.label,
        kicker: '结束',
        description: '响应输出 / 流程结束',
        ports: portSummary(data.inputs, '输入'),
        showAlias: false,
        hasSource: false,
      }
    case 'classifier':
      return {
        ...base('classifier-node', 'Switch', data, id),
        kind: '意图',
        state: '分类',
        ports: portSummary(data.outputs, '分支'),
        showAlias: false,
        hasSource: false,
        routes: classifierRouteRows(data),
      }
    case 'userInput':
      return {
        ...base('user-input-node', 'SetUp', data, id),
        kind: '用户输入',
        state: data.userInputConfig?.outputAlias || data.outputAlias || 'params',
        description: `${userInputFieldCount(data)} 个输入字段，写入 params`,
        ports: portSummary(data.outputs, '变量'),
      }
    case 'interaction':
      return {
        ...base('interaction-node', 'SetUp', data, id),
        kind: '交互',
        state: interactionTypeLabel(data.interactionConfig?.interactionType),
        label: data.interactionConfig?.title || data.label || id,
        description: `${interactionFieldCount(data)} 个字段，写入 ${data.interactionConfig?.outputAlias || data.outputAlias || 'interaction_output'}`,
        ports: portSummary(data.outputs, '变量'),
      }
    case 'pageAction':
      return {
        ...base('page-action-node', 'Link', data, id),
        kind: '页面动作',
        state: data.pageActionConfig?.confirm ? '需确认' : '直接触发',
        label: data.pageActionConfig?.title || data.label || id,
        description: data.pageActionConfig?.actionKey || '未配置页面动作',
        ports: `${portSummary(data.inputs, '输入')} · ${portSummary(data.outputs, '输出')}`,
      }
    case 'llm':
      return {
        ...base('llm-node', 'LlmModel', data, id),
        kind: '大模型',
        state: '推理',
        description: `模型实例 · ${data.llmConfig?.modelInstanceId || context.defaultModelInstanceId || '未选择'}`,
        showAlias: false,
      }
    case 'tool': {
      const isApi = !!data.toolConfig?.httpApiAssetId
      return {
        ...base('tool-node', 'Briefcase', data, id),
        kind: isApi ? 'API' : '业务方法',
        state: data.toolConfig?.projectCode || context.projectCode || '全局',
        // The author's node name is the title; the called method stays visible underneath.
        label: data.label || data.toolConfig?.ref || id,
        description: isApi ? 'API 调用' : data.toolConfig?.ref || '未选择业务方法',
        needsConfig: !!data.needsConfiguration,
      }
    }
    case 'knowledge': {
      const required = data.knowledgeConfig?.evidencePolicy === 'REQUIRED'
      return {
        ...base('knowledge-node', 'Coin', data, id),
        kind: '知识',
        state: required ? '必须证据' : '可回退',
        label: data.label || id,
        description: `${data.knowledgeConfig?.knowledgeBaseCodes?.join(', ') || '未选择知识库'} · ${data.knowledgeConfig?.topK ? `返回 ${data.knowledgeConfig.topK} 条` : '按知识库设置'}`,
        hasSource: !required,
        routes: required ? knowledgeEvidenceRouteRows() : [],
      }
    }
    case 'condition':
      return {
        ...base('condition-node', 'Switch', data, id),
        kind: '条件',
        state: '路由',
        description: data.description || '按连线条件分流',
        ports: portSummary(data.outputs, '分支'),
        showAlias: false,
        hasSource: false,
        routes: conditionRouteRows(data),
      }
    case 'variable':
      return {
        ...base('variable-node', 'SetUp', data, id),
        kind: '变量',
        state: '状态',
        description: `${assignmentCount(data.assignments)} 个变量赋值`,
      }
    case 'template':
      return {
        ...base('template-node', 'Document', data, id),
        kind: '模板',
        state: '渲染',
        description: data.description || '模板转换与响应组装',
      }
    case 'parameter':
      return {
        ...base('parameter-node', 'MagicStick', data, id),
        kind: '参数',
        state: '提取',
        description: `${data.parameterConfig?.fields?.length || 0} 个参数字段`,
      }
    case 'http':
      return {
        ...base('http-node', 'Link', data, id),
        kind: '接口',
        state: data.httpConfig?.method || 'GET',
        description: data.httpConfig?.url || '未配置 URL',
      }
    case 'answer':
      return {
        ...base('answer-node', 'Finished', data, id),
        kind: '回复',
        state: '输出',
        description: data.answerConfig?.template || data.template || '未配置回复模板',
        ports: `${portSummary(data.inputs, '入')} · 写入 answer`,
        showAlias: false,
      }
    case 'code':
      return {
        ...base('code-node', 'Document', data, id),
        kind: '代码',
        state: '表达式',
        description: `${Object.keys(data.codeConfig?.outputs || {}).length} 个输出字段`,
      }
    case 'aggregate':
      return {
        ...base('aggregate-node', 'SetUp', data, id),
        kind: '聚合',
        state: data.aggregateConfig?.mode || 'object',
        description: `${data.aggregateConfig?.items?.length || 0} 个变量聚合项`,
      }
    case 'approval':
      return {
        ...base('approval-node', 'Finished', data, id),
        kind: '人工',
        state: '确认',
        description: data.approvalConfig?.title || '人工确认',
        ports: portSummary(data.outputs, '分支'),
        showAlias: false,
        hasSource: false,
        routes: approvalRouteRows(),
      }
    case 'loop':
      return {
        ...base('loop-node', 'RefreshRight', data, id),
        kind: '循环',
        state: `${data.loopConfig?.maxIterations || 100} 次`,
        description: loopNodeSummary(data.loopConfig),
        ports: portSummary(data.outputs, '输出'),
        showAlias: false,
      }
    case 'knowledgeWrite':
      return {
        ...base('knowledge-write-node', 'Collection', data, id),
        kind: '知识',
        state: '写入',
        description: data.knowledgeWriteConfig?.knowledgeBaseCode || '未选择知识库',
        showAlias: false,
      }
    case 'documentExtract':
      return {
        ...base('document-extract-node', 'Files', data, id),
        kind: '文档',
        state: data.documentExtractConfig?.format || 'text',
        description: `${data.documentExtractConfig?.fields?.length || 0} 个抽取字段`,
        showAlias: false,
      }
    case 'mcp':
      return {
        ...base('mcp-node', 'Connection', data, id),
        kind: 'MCP',
        state: '调用',
        description: data.mcpConfig?.toolName || '未配置工具',
        showAlias: false,
      }
  }
}
