/** Declared input/output field shared by source assets and invocation protocols. */
export interface AssetParameter {
  name: string
  type: string
  description: string
  required: boolean
  location?: string | null
  children?: AssetParameter[]
  metadata?: Record<string, unknown> | null
}

export function isAssetInputParameter(parameter: AssetParameter) {
  return !['output', 'return', 'response'].includes(String(parameter.location || '').trim().toLowerCase())
}

export function parameterLocation(location?: string | null): string {
  const key = (location || 'body').toLowerCase()
  return ({ body: '请求体', body_json: '请求体', query: '查询参数', path: '路径参数', header: '请求头', output: '返回值', return: '返回值', response: '返回值' } as Record<string, string>)[key] || location || '请求体'
}
