import type { HttpApiContract, HttpApiSource } from '@/types/httpApi'

export interface HttpApiSourceDifference {
  path: string
  label: string
  values: Array<{ sourceId: number; text: string }>
}

/** Display-only comparison of owner-provided canonical facts; never decides eligibility or hashes. */
export function compareHttpApiSources(sources: HttpApiSource[]) {
  const active = sources.filter(source => source.status !== 'REMOVED')
  const comparison = compareContracts(active.map(source => source.contract))
  return { ...comparison, active, differences: comparison.differences.map(difference => ({
    ...difference, values: difference.values.map((text, index) => ({ sourceId: active[index].id, text })),
  })) }
}

/** Accepted/candidate explanation only. Hash equality and source trust remain owner decisions. */
export function compareHttpApiContracts(accepted: HttpApiContract | null, candidate: HttpApiContract | null) {
  const comparison = compareContracts([accepted, candidate])
  return { ...comparison, differences: comparison.differences.map(difference => ({
    path: difference.path, label: difference.label, before: difference.values[0], after: difference.values[1],
  })) }
}

function compareContracts(contracts: Array<HttpApiContract | null | undefined>) {
  const differences: Array<{ path: string; label: string; values: string[] }> = []
  const available = contracts.length > 0 && contracts.length <= 16 && contracts.every(contract => contract != null)
  let truncated = false
  if (!available) return { differences, available, truncated }
  const walk = (path: string[], nodes: unknown[]) => {
    if (nodes.every(node => JSON.stringify(node) === JSON.stringify(nodes[0]))) return
    if (differences.length >= 64) { truncated = true; return }
    const objects = nodes.every(node => node != null && typeof node === 'object' && !Array.isArray(node))
    const arrays = nodes.every(node => Array.isArray(node))
    if (objects) {
      const keys = [...new Set(nodes.flatMap(node => Object.keys(node as object)))].sort()
      keys.forEach(key => walk([...path, key], nodes.map(node => (node as Record<string, unknown>)[key])))
    } else if (arrays) {
      const size = Math.max(...nodes.map(node => (node as unknown[]).length))
      for (let index = 0; index < size; index++) walk([...path, String(index)], nodes.map(node => (node as unknown[])[index]))
    } else {
      differences.push({ path: '/' + path.join('/'), label: fieldLabel(path, contracts), values: nodes.map(displayValue) })
    }
  }
  walk([], contracts)
  return { differences, available, truncated }
}

function displayValue(value: unknown): string {
  if (value === undefined) return '未声明'
  if (value === null) return 'null'
  const text = typeof value === 'string' ? value : JSON.stringify(value)
  return text.length > 256 ? text.slice(0, 256) + '…（展开来源契约查看完整值）' : text
}

function fieldLabel(path: string[], contracts: Array<HttpApiContract | null | undefined>): string {
  let parts = [...path]
  if (parts[0] === 'responses') {
    const index = Number(parts[1])
    const response = contracts.map(contract => contract?.responses?.[index]).find(Boolean)
    parts = [`响应 ${response?.status || index}`, ...parts.slice(2)]
  } else if (parts[0] === 'parameters') {
    const index = Number(parts[1])
    const parameter = contracts.map(contract => contract?.parameters?.[index]).find(Boolean)
    parts = [`请求参数 ${parameter?.location || ''} ${parameter?.name || index}`, ...parts.slice(2)]
  }
  const names: Record<string, string> = { authentication: '认证', state: '声明状态', schemes: '方式',
    requiredHeaderNames: '必需请求头', type: '类型', required: '必填', contentTypes: '媒体类型',
    requestBody: '请求体', sideEffect: '副作用', identity: '操作身份', routeTemplate: '完整路由',
    minLength: '最小长度', maxLength: '最大长度', minimum: '最小值', maximum: '最大值',
    pattern: '格式约束', enum: '允许值', nullable: '可空', format: '格式', writeOnly: '仅写入' }
  // A property called state is business data, not the authentication.state keyword.
  return parts.filter(part => part !== 'schema' && part !== 'properties').map((part, _index, filtered) =>
    part === 'state' && filtered[0] !== 'authentication' ? part : names[part] || part).join(' / ')
}
