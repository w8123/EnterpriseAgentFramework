import type { ScanProjectBlockers } from '@/types/scanProject'

export function formatScanProjectBlockersMessage(b: ScanProjectBlockers): string {
  const lines: string[] = []
  if (b.assets?.length) {
    lines.push('项目仍有业务方法或 API 资产，无法删除。项目承载来源、接纳修订和发布引用；请保留项目，并在资产详情处理停用或来源变化。', '')
    for (const asset of b.assets) {
      const label = asset.assetType === 'BUSINESS_METHOD' ? '业务方法' : 'API'
      lines.push(`· ${label}：${asset.title || asset.qualifiedName}（${asset.qualifiedName}）`)
    }
  }
  if (b.tools?.length || b.agents?.length) {
    lines.push('项目的调用定义仍被已发布的 Agent 配置引用。请核对使用位置，显式更新并发布相关引用后再操作。')
  }
  if (b.tools?.length) {
    lines.push(`· 调用引用：${b.tools.join('、')}`)
  }
  if (b.agents?.length) {
    lines.push(`· 涉及 Agent：${b.agents.map((a) => a.agentName).join('、')}`)
  }
  return lines.length ? lines.join('\n') : '当前项目操作被阻止，请重新读取项目状态后再试。'
}

/** 直接调删除/重扫返回 409 时，从 axios 错误中解析 body */
export function parseScanProjectBlockersFromError(err: unknown): ScanProjectBlockers | null {
  if (typeof err !== 'object' || err === null || !('response' in err)) {
    return null
  }
  const r = (err as { response?: { status?: number; data?: unknown } }).response
  if (r?.status !== 409 || r.data == null || typeof r.data !== 'object') {
    return null
  }
  const d = r.data as Record<string, unknown>
  const record = (value: unknown): value is Record<string, unknown> =>
    value !== null && typeof value === 'object' && !Array.isArray(value)
  if (typeof d.blocked !== 'boolean'
      || !Array.isArray(d.tools) || !d.tools.every(value => typeof value === 'string')
      || !Array.isArray(d.agents) || !d.agents.every(value => record(value)
        && typeof value.agentId === 'string' && typeof value.agentName === 'string')
      || !Array.isArray(d.assets) || !d.assets.every(value => record(value)
        && (value.assetType === 'BUSINESS_METHOD' || value.assetType === 'HTTP_API')
        && typeof value.assetId === 'number' && Number.isSafeInteger(value.assetId) && value.assetId > 0
        && typeof value.qualifiedName === 'string' && Boolean(value.qualifiedName.trim())
        && (value.title === null || typeof value.title === 'string'))) {
    return null
  }
  return d as unknown as ScanProjectBlockers
}
