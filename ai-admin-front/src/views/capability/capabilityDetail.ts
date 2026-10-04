import type { StatusTone } from '@/components/common/glassWorkbench'
import { isToolInputParameter, type ToolInfo, type ToolParameter } from '@/types/tool'
import { capabilityStableName } from './capabilityGovernance'

export interface CapabilityReadiness { label: string; title: string; description: string; tone: StatusTone }

export function capabilityReadiness(tool: ToolInfo): CapabilityReadiness {
  if (!tool.enabled) return { label: '已停用', title: '这项能力已停用', description: '当前保留调用定义，不能作为新调用的可用能力。', tone: 'neutral' }
  if (tool.catalogLinkStatus === 'NOT_IN_CATALOG') return { label: '关联异常', title: '目录记录尚未关联', description: '请在来源项目重新同步能力，检查接口是否已纳入目录。', tone: 'danger' }
  switch (tool.sourceAvailability) {
    case 'READY': return { label: '可用', title: '来源契约已核验', description: '来源与目录定义一致。实际调用仍需通过权限、凭据和业务校验。', tone: 'success' }
    case 'CONTRACT_DRIFT': return { label: '契约已变化', title: '调用契约需要更新', description: '来源与目录中的参数或调用定义不一致。处理当前能力变化后，再校验引用它的 Workflow。', tone: 'warning' }
    case 'SOURCE_MISSING': return { label: '来源已移除', title: '来源已不再上报这项能力', description: '当前调用会被拦截。请检查业务系统，并核对已有流程的引用。', tone: 'danger' }
    case 'UNACCEPTED': return { label: '待纳管', title: '来源定义尚未纳管', description: '请在能力变化中确认这项定义，再用于调用。', tone: 'warning' }
    case 'SOURCE_UNKNOWN': return { label: '来源未核验', title: '来源一致性尚未确认', description: '当前调用会被拦截。请检查来源项目的同步情况和当前能力变化。', tone: 'warning' }
    default: return { label: '状态未确认', title: '尚未取得可用状态', description: '重新读取详情以确认当前来源状态，不能据此判断能力可用。', tone: 'neutral' }
  }
}

export function splitCapabilityParameters(parameters: ToolParameter[] = []) {
  return { inputs: parameters.filter(isToolInputParameter), outputs: parameters.filter(p => !isToolInputParameter(p)) }
}

export function parameterLocation(location?: string | null): string {
  const key = (location || 'body').toLowerCase()
  return ({ body: '请求体', body_json: '请求体', query: '查询参数', path: '路径参数', header: '请求头', output: '返回值', return: '返回值', response: '返回值' } as Record<string, string>)[key] || location || '请求体'
}

// Export the declared contract verbatim, not a runnable request or an inferred
// JSON Schema: flat dotted paths and nested children are both valid source forms.
export function capabilityInputContract(tool: ToolInfo): string {
  function declared(parameter: ToolParameter): Record<string, unknown> {
    return { name: parameter.name, type: parameter.type, location: parameter.location || 'body', required: parameter.required,
      description: parameter.description || '', ...(parameter.children?.length ? { children: parameter.children.map(declared) } : {}) }
  }
  return JSON.stringify({
    capability: capabilityStableName(tool),
    inputs: splitCapabilityParameters(tool.parameters).inputs.map(declared),
  }, null, 2)
}

export function capabilityExecutionFacts(metadata: Record<string, unknown>) {
  return [
    { label: '声明超时', value: typeof metadata.timeoutMs === 'number' && metadata.timeoutMs > 0 ? `${metadata.timeoutMs / 1000} 秒` : '未单独声明' },
    { label: '声明重试', value: typeof metadata.retryLimit === 'number' && metadata.retryLimit >= 0 ? `${metadata.retryLimit} 次` : '未单独声明' },
    ...(Array.isArray(metadata.requiredRoles) && metadata.requiredRoles.length ? [{ label: '声明角色', value: metadata.requiredRoles.join('、') }] : []),
  ]
}

export function referenceStage(stage?: string): string {
  return ({ DRAFT: '草稿', PUBLISHED: '已发布', HISTORICAL: '历史版本', EXTERNAL_PIN: '开放固定版本' } as Record<string, string>)[stage || ''] || stage || '状态未提供'
}
