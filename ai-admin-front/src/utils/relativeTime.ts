/**
 * 将时间显示为中文相对时间，例如“12 秒前”“8 分钟前”“3 小时前”。
 * 空值显示为“-”；无法解析的字符串保留原样，便于接口异常时排查。
 */
export function formatRelativeTime(value?: string | number | Date | null, now = Date.now()): string {
  if (value === null || value === undefined || value === '') return '-'

  const date = value instanceof Date ? value : new Date(value)
  if (Number.isNaN(date.getTime())) return typeof value === 'string' ? value : '-'

  const seconds = Math.max(0, Math.floor((now - date.getTime()) / 1000))
  if (seconds < 60) return `${seconds} 秒前`

  const minutes = Math.floor(seconds / 60)
  if (minutes < 60) return `${minutes} 分钟前`

  const hours = Math.floor(minutes / 60)
  if (hours < 24) return `${hours} 小时前`

  return `${Math.floor(hours / 24)} 天前`
}
