import { computed, onScopeDispose, reactive, ref, watch, type Ref } from 'vue'
import { runWorkflowReadOnlyTrial } from '@/api/workflow'
import type {
  WorkflowReadOnlyTrialBlockedNode,
  WorkflowReadOnlyTrialResult,
  WorkflowWorkingCopyState,
} from '@/types/workflow'

/**
 * Studio「只读试运行整个草稿」(D28)。
 *
 * 前端只做提示性预检：列出将被真实调用的业务方法 / API 节点、明显带副作用的节点和 Workflow 输入字段。
 * 是否允许执行完全由 Control / Runtime 按已保存修订重新判定（只读声明、已接纳契约、连接、ACL、图哈希）；
 * 服务端拒绝时返回 blockedNodes / failedNodeId，本组合函数原样展示，不做本地放行。
 */

export interface ReadOnlyTrialTargetNode {
  nodeId: string
  nodeName: string
  assetType: 'HTTP_API' | 'BUSINESS_METHOD'
  qualifiedName: string
}

export interface ReadOnlyTrialInputField {
  name: string
  label: string
  required: boolean
  type: 'string' | 'number' | 'integer' | 'boolean' | 'unsupported'
  description?: string
}

export interface ReadOnlyTrialPreview {
  targets: ReadOnlyTrialTargetNode[]
  blocked: WorkflowReadOnlyTrialBlockedNode[]
  fields: ReadOnlyTrialInputField[]
}

const NODE_TYPE_LABELS: Record<string, string> = {
  PAGE_ACTION: '页面动作', HUMAN_APPROVAL: '人工审批', MCP_CALL: 'MCP 调用', KNOWLEDGE_WRITE: '知识写入',
  INTERACTION: '交互', HTTP_REQUEST: 'HTTP 请求', TOOL: '业务方法/API',
}
const SIDE_EFFECT_REASONS: Record<string, string> = {
  PAGE_ACTION: '会操作用户页面，可能产生副作用',
  HUMAN_APPROVAL: '需要人工审批，试运行不会挂起等待',
  MCP_CALL: '外部 MCP 工具的副作用无法确认',
  KNOWLEDGE_WRITE: '会写入知识库',
}

export function nodeTypeLabel(type: string): string {
  return NODE_TYPE_LABELS[type] || type || '未知'
}

function qualifiedNameOf(node: any): string {
  const ref = node?.ref || {}
  const config = node?.config || {}
  return String(ref.qualifiedName || ref.toolName || config.qualifiedName || config.toolName || '').trim()
}

/** 已保存 GraphSpec 中的业务方法 / API 节点、可见的副作用节点和 Workflow 输入字段。 */
export function readOnlyTrialPreview(graphJson: string): ReadOnlyTrialPreview | null {
  let graph: any
  try { graph = JSON.parse(graphJson) } catch { return null }
  const nodes: any[] = Array.isArray(graph?.nodes) ? graph.nodes : []
  if (!nodes.length) return null
  const targets: ReadOnlyTrialTargetNode[] = []
  const blocked: WorkflowReadOnlyTrialBlockedNode[] = []
  for (const node of nodes) {
    const type = String(node?.type || '').trim().toUpperCase()
    const nodeId = String(node?.id || '')
    const nodeName = String(node?.name || nodeId)
    if (type === 'TOOL') {
      const qualifiedName = qualifiedNameOf(node)
      const api = qualifiedName.startsWith('http-api:') || node?.config?.httpApiAssetId != null
      targets.push({ nodeId, nodeName, assetType: api ? 'HTTP_API' : 'BUSINESS_METHOD', qualifiedName })
    } else if (SIDE_EFFECT_REASONS[type]) {
      blocked.push({ nodeId, nodeName, nodeType: type, reason: SIDE_EFFECT_REASONS[type] })
    } else if (type === 'INTERACTION') {
      const interaction = String(node?.config?.interactionType || '').trim().replace(/-/g, '_').toUpperCase()
      if (interaction !== 'PRESENT_OUTPUT') {
        blocked.push({ nodeId, nodeName, nodeType: type, reason: '需要人工交互，试运行不会挂起等待' })
      }
    }
  }
  return { targets, blocked, fields: trialInputFields(graph, nodes) }
}

/** 草稿含业务方法或 API 节点时，Studio 的「调试」入口改走整稿只读试运行。 */
export function hasReadOnlyTrialTarget(graphJson: string): boolean {
  return Boolean(readOnlyTrialPreview(graphJson)?.targets.length)
}

function trialInputFields(graph: any, nodes: any[]): ReadOnlyTrialInputField[] {
  const schema = graph?.inputSchema
  const properties = schema?.properties && typeof schema.properties === 'object' ? schema.properties : null
  if (properties) {
    const required = new Set<string>(Array.isArray(schema.required) ? schema.required : [])
    return Object.entries(properties).map(([name, property]: [string, any]) => ({
      name, label: name, required: required.has(name), type: scalarType(property?.type),
      description: typeof property?.description === 'string' ? property.description : undefined,
    }))
  }
  const input = nodes.find((node) => String(node?.type || '').toUpperCase() === 'USER_INPUT')
  const fields: any[] = input?.config?.fields || input?.config?.userInputConfig?.fields || []
  return fields.filter((field) => typeof field?.name === 'string' && field.name.trim()).map((field) => ({
    name: field.name.trim(), label: field.name.trim(), required: field.required === true,
    type: scalarType(field.type), description: field.description || undefined,
  }))
}

function scalarType(raw: unknown): ReadOnlyTrialInputField['type'] {
  const type = String(raw || 'string').toLowerCase()
  if (type === 'string' || type === 'file') return 'string'
  if (type === 'number' || type === 'integer' || type === 'boolean') return type
  return 'unsupported'
}

export function trialFailureHint(code: string): string {
  if (/AFTER_DISPATCH|RESULT_UNCONFIRMED|TIMEOUT|HTTP_FAILED/.test(code)) return '结果未确认，请先核对运行记录和目标系统，勿直接重试。'
  if (/GRAPH_UNSUPPORTED/.test(code)) return '草稿包含不能只读试运行的节点，请按提示调整后保存。'
  if (/READ_ONLY_REQUIRED/.test(code)) return '草稿包含会写入业务数据的业务方法或 API；整稿试运行只允许只读节点，请通过正式发布后的入口运行。'
  if (/DISPATCH_LIMIT/.test(code)) return '试运行调用次数超出上限，请检查循环或重复调用。'
  if (/DRAFT|REVISION|TARGET/.test(code)) return '草稿或节点已变化，请保存或重新加载后检查。'
  if (/BUSINESS_METHOD.*BUSINESS_IDENTITY/.test(code)) return '此方法要求业务用户身份，项目测试身份不可调用；请联系方法接入开发者核对。'
  if (/BUSINESS_METHOD.*CREDENTIAL/.test(code)) return '项目签名凭据不可用，请到业务方法所属项目检查接入状态。'
  if (/BUSINESS_METHOD.*(SOURCE|OWNER|FACTS|DISABLED|UNAVAILABLE)/.test(code)) return '业务方法来源或已接纳契约已变化，请到方法详情核对。'
  if (/ACL|PERMISSION|IDENTITY|PROJECT|AUTH_REQUIRED/.test(code)) return '项目或业务方法/API 调用授权不足，请检查当前账号权限。'
  if (/CONNECTION|CREDENTIAL|AUTH_MISMATCH|EGRESS/.test(code)) return '项目连接或凭据不可用，请到 API 详情检查连接配置。'
  if (/SOURCE|OWNER|FACTS/.test(code)) return 'API 来源或已接纳契约已变化，请到 API 详情核对。'
  if (/INPUT|PARAM/.test(code)) return '输入无效，请检查必填项与参数类型。'
  return '结果未确认，请先核对运行记录和目标系统，再决定是否发起新试运行。'
}

export function useWorkflowStudioReadOnlyTrial(deps: {
  studio: Ref<WorkflowWorkingCopyState | null>
  open: Readonly<Ref<boolean>>
  dirty: Readonly<Ref<boolean>>
  authorized: Readonly<Ref<boolean>>
  sessionScope: Readonly<Ref<string>>
}) {
  const running = ref(false)
  const error = ref('')
  const invalidField = ref('')
  const result = ref<WorkflowReadOnlyTrialResult | null>(null)
  const blockedNodes = ref<WorkflowReadOnlyTrialBlockedNode[]>([])
  const failedNodeId = ref('')
  const message = ref('')
  const inputParams = reactive<Record<string, string>>({})
  const preview = computed(() => readOnlyTrialPreview(deps.studio.value?.graphSpecJson || ''))
  const targets = computed(() => preview.value?.targets || [])
  const fields = computed(() => preview.value?.fields || [])
  const visibleBlocked = computed(() => blockedNodes.value.length ? blockedNodes.value : preview.value?.blocked || [])
  const scope = computed(() => [deps.sessionScope.value, deps.studio.value?.workflowId || '',
    deps.studio.value?.revision || '', deps.open.value].join('\u0000'))
  let generation = 0
  let disposed = false
  const current = (token: number, key: string) => !disposed && token === generation && scope.value === key

  const reason = computed(() => {
    if (deps.dirty.value) return '有未保存编辑，请先保存，再试运行该修订。'
    if (!deps.studio.value?.revision) return '需要先保存草稿。'
    if (!preview.value) return '已保存草稿没有可执行节点。'
    if (!deps.authorized.value) return '当前账号缺少本项目的调试或业务方法/API 调用权限。'
    if (preview.value.blocked.length) {
      return `以下节点不能只读试运行：${preview.value.blocked.map((node) => `「${node.nodeName}」（${nodeTypeLabel(node.nodeType)}）${node.reason}`).join('；')}`
    }
    const unsupported = fields.value.find((field) => field.required && field.type === 'unsupported')
    if (unsupported) return `必填输入 ${unsupported.label} 不是标量类型，暂不支持在试运行中填写。`
    return ''
  })

  function resetInputs() {
    Object.keys(inputParams).forEach((key) => delete inputParams[key])
    fields.value.forEach((field) => { inputParams[field.name] ??= '' })
  }

  async function run() {
    if (running.value) return
    error.value = ''; invalidField.value = ''
    if (reason.value) { error.value = reason.value; return }
    const body: Record<string, string | number | boolean> = {}
    for (const field of fields.value) {
      const value = (inputParams[field.name] || '').trim()
      if (!value && field.required) {
        invalidField.value = field.name; error.value = `请填写 ${field.label}。`; return
      }
      if (!value || field.type === 'unsupported') continue
      if (field.type === 'number' || field.type === 'integer') {
        const number = Number(value)
        if (!Number.isFinite(number) || (field.type === 'integer' && !Number.isInteger(number))) {
          invalidField.value = field.name; error.value = `${field.label} 需要${field.type === 'integer' ? '整数' : '数值'}。`; return
        }
        body[field.name] = number
      } else if (field.type === 'boolean') {
        if (!['true', 'false'].includes(value)) {
          invalidField.value = field.name; error.value = `${field.label} 需要 true 或 false。`; return
        }
        body[field.name] = value === 'true'
      } else body[field.name] = value
    }
    const saved = deps.studio.value!
    const token = ++generation; const key = scope.value
    running.value = true; result.value = null; blockedNodes.value = []; failedNodeId.value = ''
    try {
      const text = message.value.trim()
      const response = await runWorkflowReadOnlyTrial({ workflowId: saved.workflowId,
        expectedRevision: saved.revision!, inputParams: body, ...(text ? { message: text } : {}) })
      if (!current(token, key)) return
      result.value = response.data
      failedNodeId.value = response.data.failedNodeId || ''
      if (!response.data.success) {
        const code = response.data.errorCode || 'WORKFLOW_TRIAL_FAILED'
        error.value = response.data.errorMessage || `${code}：${trialFailureHint(code)}`
      }
    } catch (failure: any) {
      if (!current(token, key)) return
      const data = failure?.response?.data || {}
      const code = data.errorCode || (failure?.response?.status === 403
        ? 'WORKFLOW_TRIAL_PERMISSION_DENIED' : failure?.response?.status === 401
          ? 'RUNTIME_INTERNAL_AUTH_REQUIRED' : 'WORKFLOW_TRIAL_RESULT_UNCONFIRMED')
      blockedNodes.value = Array.isArray(data.blockedNodes) ? data.blockedNodes : []
      failedNodeId.value = typeof data.failedNodeId === 'string' ? data.failedNodeId : ''
      const detail = typeof data.errorMessage === 'string' && data.errorMessage ? data.errorMessage : trialFailureHint(code)
      error.value = `${detail}（${code}）`
    } finally {
      running.value = false
    }
  }

  watch(scope, () => {
    generation++; result.value = null; invalidField.value = ''; error.value = ''
    blockedNodes.value = []; failedNodeId.value = ''
    fields.value.forEach((field) => { inputParams[field.name] ??= '' })
  }, { immediate: true })
  watch(deps.sessionScope, () => { resetInputs(); message.value = '' })
  onScopeDispose(() => { disposed = true; generation++ })
  return { preview, targets, fields, reason, running, error, invalidField, result, inputParams, message,
    blockedNodes: visibleBlocked, failedNodeId, run }
}
