import type { ScanProjectBlockers } from '@/types/scanProject'

export function formatScanProjectBlockersMessage(b: ScanProjectBlockers): string {
  const lines: string[] = [
    '本扫描项目已「添加为 Tool/Skill」并仍被以下 Agent 的 tools / skills 白名单引用。请先在「Agent 管理」中移除对应工具名或 Skill 名，再执行删除或重新扫描。',
    '',
  ]
  if (b.toolNames?.length) {
    lines.push(`· 工具名：${b.toolNames.join('、')}`)
  }
  if (b.skillNames?.length) {
    lines.push(`· Skill 名：${b.skillNames.join('、')}`)
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
