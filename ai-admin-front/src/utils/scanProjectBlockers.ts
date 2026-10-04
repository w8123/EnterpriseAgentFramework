import type { ScanProjectBlockers } from '@/types/scanProject'

export function formatScanProjectBlockersMessage(b: ScanProjectBlockers): string {
  const lines: string[] = [
    '本项目的历史调用投影仍被以下 Agent 的已发布配置引用。请在所属业务方法/API 目录核对状态，显式更新并发布相关引用后，再执行受保护的删除或重新扫描；这里不修改调用契约。',
    '',
  ]
  if (b.toolNames?.length) {
    lines.push(`· 历史引用：${b.toolNames.join('、')}`)
  }
  if (b.agents?.length) {
    lines.push(`· 涉及 Agent：${b.agents.map((a) => a.name).join('、')}`)
  }
  return lines.join('\n')
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
  if (typeof d.blocked !== 'boolean') {
    return null
  }
  return d as unknown as ScanProjectBlockers
}
