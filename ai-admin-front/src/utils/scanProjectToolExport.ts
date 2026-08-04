import type { ProjectToolInfo } from '@/types/scanProject'
import type { ToolParameter } from '@/types/tool'
import * as XLSX from 'xlsx'

/** 与敏感扫描类型代码对应的中文（表格 / 导出共用） */
export const SCAN_TOOL_SENSITIVE_TYPE_LABELS: Record<string, string> = {
  PHONE: '电话号码/手机号',
  EMAIL: '邮箱',
  ID_CARD: '身份证',
  BANK_CARD: '银行卡',
  REAL_NAME: '姓名',
  USER_CODE: '工号',
  USER_ID: '用户ID',
  PASSWORD_SECRET: '密码/密钥',
  ADDRESS: '地址',
  IP_ADDRESS: 'IP',
  DEVICE_ID: '设备标识',
  SSO_TOKEN: 'SSO/会话令牌',
  API_KEY: 'API Key',
  COOKIE_SESSION: 'Cookie/会话',
  MEDICAL: '医疗信息',
  BIOMETRIC: '生物特征',
  LOCATION: '位置',
  EDUCATIONAL_BACKGROUND: '学历',
  CREDIT: '征信',
  OTHER_PII: '其他个人信息',
}

export function scanSensitiveTypeLabel(code: string): string {
  return SCAN_TOOL_SENSITIVE_TYPE_LABELS[code] ?? code
}

function buildApiAddress(tool: ProjectToolInfo): string {
  const pathPart = `${tool.contextPath ?? ''}${tool.endpointPath ?? ''}`.trim()
  const base = (tool.baseUrl ?? '').trim().replace(/\/+$/, '')
  if (base) {
    if (!pathPart) return base
    const p = pathPart.startsWith('/') ? pathPart : `/${pathPart}`
    return `${base}${p}`
  }
  return pathPart || '-'
}

function queryStylePlaceholder(p: ToolParameter): string {
  const typ = (p.type || '').toLowerCase()
  if (typ.includes('int') || typ.includes('long') || typ.includes('short') || typ.includes('number')) return '0'
  if (typ.includes('bool')) return 'true'
  return 'xxx'
}

function jsonLeafValue(p: ToolParameter): string | number | boolean | unknown[] | Record<string, unknown> {
  const typ = (p.type || '').toLowerCase()
  if (p.children && p.children.length > 0) {
    return buildJsonObjectFromParams(p.children)
  }
  if (typ.includes('int') || typ.includes('long') || typ.includes('short') || typ.includes('number')) return 0
  if (typ.includes('double') || typ.includes('float') || typ.includes('bigdecimal')) return 0
  if (typ.includes('bool')) return true
  if (typ.includes('array') || typ.includes('list') || typ.includes('[]')) return ['xxx']
  return 'xxx'
}

function buildJsonObjectFromParams(params: ToolParameter[]): Record<string, unknown> {
  const o: Record<string, unknown> = {}
  for (const p of params) {
    if (!p.name) continue
    o[p.name] = jsonLeafValue(p)
  }
  return o
}

function isBodyWrapper(p: ToolParameter): boolean {
  const n = (p.name || '').toLowerCase()
  const loc = (p.location || '').toLowerCase()
  if (!p.children?.length) return false
  if (['body_json', 'body', 'request_body', 'json'].includes(n)) return true
  if (['body_json', 'body', 'request_body'].includes(loc)) return true
  return false
}

function isResponseParameter(p: ToolParameter): boolean {
  const loc = (p.location || '').trim().toUpperCase()
  return loc === 'RESPONSE'
}

/** 仅请求体（不含 RESPONSE），生成 JSON 字符串 */
function buildRequestBodyJson(bodyRoots: ToolParameter[]): string {
  if (bodyRoots.length === 0) return ''
  if (bodyRoots.length === 1 && isBodyWrapper(bodyRoots[0])) {
    const inner = bodyRoots[0].children!
    return JSON.stringify(buildJsonObjectFromParams(inner))
  }
  const merged: Record<string, unknown> = {}
  for (const p of bodyRoots) {
    if (isBodyWrapper(p) && p.children?.length) {
      Object.assign(merged, buildJsonObjectFromParams(p.children))
    } else if (p.children?.length) {
      merged[p.name] = jsonLeafValue(p)
    } else {
      merged[p.name] = jsonLeafValue(p)
    }
  }
  return Object.keys(merged).length > 0 ? JSON.stringify(merged) : ''
}

/**
 * 返回值 JSON：Excel 行前已有「返回值：」标签，故不再用参数名（多为「返回值」）包一层根 key，避免重复。
 * 单子树根且带 children：直接序列化子字段对象；仅类型叶子：序列化为 JSON 字符串字面量。
 */
function buildResponseExampleJson(responseRoots: ToolParameter[]): string {
  if (responseRoots.length === 0) return ''
  if (responseRoots.length === 1) {
    const p = responseRoots[0]
    if (p.children?.length) {
      return JSON.stringify(buildJsonObjectFromParams(p.children))
    }
    const typ = (p.type || '').trim()
    return JSON.stringify(typ || 'xxx')
  }
  const merged: Record<string, unknown> = {}
  for (const p of responseRoots) {
    const key = p.name || 'item'
    if (p.children?.length) merged[key] = buildJsonObjectFromParams(p.children)
    else merged[key] = (p.type || '').trim() || 'xxx'
  }
  return JSON.stringify(merged)
}

/**
 * Excel「请求参数」列：
 * 请求参数：query/path/header 为 a=b&…，若有请求体 JSON 则同一行空格拼接；
 * 换行后 返回值：仅 RESPONSE 子树 JSON（行前标签已标明含义，不再叠「返回值」根 key）。
 */
function buildRequestParamsExample(tool: ProjectToolInfo): string {
  const params = tool.parameters || []
  if (params.length === 0) {
    return '-'
  }

  const kvPairs: string[] = []
  const bodyRoots: ToolParameter[] = []
  const responseRoots: ToolParameter[] = []

  for (const p of params) {
    if (isResponseParameter(p)) {
      responseRoots.push(p)
      continue
    }
    const loc = (p.location || '').toLowerCase()
    if (loc === 'query' || loc === 'path' || loc === 'header') {
      kvPairs.push(`${p.name}=${queryStylePlaceholder(p)}`)
    } else {
      bodyRoots.push(p)
    }
  }

  const queryPart = kvPairs.length > 0 ? kvPairs.join('&') : ''
  const requestBodyJson = buildRequestBodyJson(bodyRoots)
  let requestLine = ''
  if (queryPart && requestBodyJson) requestLine = `${queryPart} ${requestBodyJson}`
  else if (queryPart) requestLine = queryPart
  else if (requestBodyJson) requestLine = requestBodyJson

  const responseJson = buildResponseExampleJson(responseRoots)

  const segments: string[] = []
  if (requestLine) segments.push(`请求参数：${requestLine}`)
  if (responseJson) segments.push(`返回值：${responseJson}`)

  if (segments.length === 0) return '-'
  return segments.join('\n')
}

function formatSensitiveInvolved(tool: ProjectToolInfo): string {
  if (!tool.sensitiveData) return '未扫描'
  const types = tool.sensitiveData.types || []
  return types.length > 0 ? '是' : '否'
}

function formatSensitiveTypes(tool: ProjectToolInfo): string {
  const types = tool.sensitiveData?.types
  if (!types?.length) return '-'
  return types.map((t) => scanSensitiveTypeLabel(t)).join('、')
}

/** 导出当前列表中的全部扫描接口（扁平行，与详情页 tools 数据源一致） */
export function exportScanProjectToolsExcel(tools: ProjectToolInfo[], filenameBase: string): void {
  const rows = tools.map((t) => ({
    工具名称: (t.title || t.name).trim(),
    工具标识: t.name,
    接口地址: buildApiAddress(t),
    接口用途: (t.description || '').trim() || '-',
    访问方式: (t.httpMethod || '-').toUpperCase(),
    请求参数: buildRequestParamsExample(t),
    是否涉及敏感数据: formatSensitiveInvolved(t),
    敏感数据类型: formatSensitiveTypes(t),
  }))

  const ws = XLSX.utils.json_to_sheet(rows)
  ws['!cols'] = [
    { wch: 24 },
    { wch: 32 },
    { wch: 48 },
    { wch: 36 },
    { wch: 10 },
    { wch: 72 },
    { wch: 18 },
    { wch: 28 },
  ]

  const wb = XLSX.utils.book_new()
  XLSX.utils.book_append_sheet(wb, ws, '扫描接口')
  const safe = filenameBase.replace(/[/\\?*[\]:]/g, '_').slice(0, 80)
  XLSX.writeFile(wb, `${safe || 'scan-export'}-扫描接口.xlsx`)
}
