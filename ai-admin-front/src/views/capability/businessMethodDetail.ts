import type { StatusTone } from '@/components/common/glassWorkbench'
import { isAssetInputParameter, type AssetParameter } from '@/types/assetParameter'
import type { BusinessMethodInfo } from '@/types/businessMethod'
import { capabilityStableName } from './capabilityGovernance'

export interface BusinessMethodReadiness { label: string; title: string; description: string; tone: StatusTone }

export function businessMethodReadiness(tool: BusinessMethodInfo): BusinessMethodReadiness {
  if (tool.status === 'REMOVED' || tool.sourceAvailability === 'SOURCE_MISSING') return { label: '来源已移除', title: '业务系统已移除这项方法', description: '既有资产与使用位置保留，执行会被拦截。请核对来源项目和 Workflow 引用。', tone: 'danger' }
  if (!tool.enabled) return { label: '已停用', title: '这项业务方法已停用', description: '当前保留方法资产，不能作为新调用的可用方法。', tone: 'neutral' }
  switch (tool.sourceAvailability) {
    case 'READY': return { label: '可用', title: '来源契约已核验', description: '来源与目录定义一致。实际调用仍需通过权限、凭据和业务校验。', tone: 'success' }
    case 'CONTRACT_DRIFT': return { label: '契约已变化', title: '调用契约需要更新', description: '来源与目录中的参数或调用定义不一致。处理当前方法变化后，再校验引用它的 Workflow。', tone: 'warning' }
    case 'SOURCE_MISSING': return { label: '来源已移除', title: '来源已不再上报这项业务方法', description: '当前调用会被拦截。请检查业务系统，并核对已有流程的引用。', tone: 'danger' }
    case 'UNACCEPTED': return { label: '待纳管', title: '来源定义尚未纳管', description: '请在项目来源变化中确认这项定义，再用于调用。', tone: 'warning' }
    case 'SOURCE_UNKNOWN': return { label: '来源未核验', title: '来源一致性尚未确认', description: '当前调用会被拦截。请检查来源项目的同步情况和当前方法变化。', tone: 'warning' }
    default: return { label: '状态未确认', title: '尚未取得可用状态', description: '重新读取详情以确认当前来源状态，不能据此判断能力可用。', tone: 'neutral' }
  }
}

export function splitAssetParameters(parameters: AssetParameter[] = []) {
  return { inputs: parameters.filter(isAssetInputParameter), outputs: parameters.filter(p => !isAssetInputParameter(p)) }
}

// Export the declared contract verbatim, not a runnable request or an inferred
// JSON Schema: flat dotted paths and nested children are both valid source forms.
export function businessMethodInputContract(tool: BusinessMethodInfo): string {
  function declared(parameter: AssetParameter): Record<string, unknown> {
    return { name: parameter.name, type: parameter.type, location: parameter.location || 'body', required: parameter.required,
      description: parameter.description || '', ...(parameter.children?.length ? { children: parameter.children.map(declared) } : {}) }
  }
  return JSON.stringify({
    businessMethod: capabilityStableName(tool), assetId: tool.assetId, acceptedRevisionId: tool.acceptedRevisionId,
    inputs: splitAssetParameters(tool.parameters).inputs.map(declared),
  }, null, 2)
}

export function capabilityExecutionFacts(metadata: Record<string, unknown>) {
  return [
    { label: '声明超时', value: typeof metadata.timeoutMs === 'number' && metadata.timeoutMs > 0 ? `${metadata.timeoutMs / 1000} 秒` : '未单独声明' },
    { label: '声明重试', value: typeof metadata.retryLimit === 'number' && metadata.retryLimit >= 0 ? `${metadata.retryLimit} 次` : '未单独声明' },
    ...(Array.isArray(metadata.requiredRoles) && metadata.requiredRoles.length ? [{ label: '声明角色', value: metadata.requiredRoles.join('、') }] : []),
  ]
}
