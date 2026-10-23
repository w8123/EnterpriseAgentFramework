const pad = (value: number) => String(value).padStart(2, '0')

/** 统一管理端时间展示：本地时区 `YYYY-MM-DD HH:mm:ss`，无法解析时原样返回。 */
export function formatDateTime(value?: string | number | Date | null): string {
  if (value == null || value === '') return '-'
  const date = value instanceof Date ? value : new Date(value)
  if (Number.isNaN(date.getTime())) return String(value)
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} `
    + `${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
}

/** el-table-column 的 formatter 签名。 */
export function formatTableDateTime(_row: unknown, _column: unknown, cellValue: unknown): string {
  return formatDateTime(cellValue as string | number | Date | null | undefined)
}
