/**
 * 生成唯一文件ID
 */
export function generateFileId(): string {
  return `file_${Date.now()}_${Math.random().toString(36).slice(2, 8)}`
}

/**
 * 获取文件扩展名（不含点号）
 */
export function getFileExtension(fileName: string): string {
  const idx = fileName.lastIndexOf('.')
  return idx >= 0 ? fileName.substring(idx + 1).toLowerCase() : ''
}

/**
 * 判断文件类型是否支持
 */
export function isSupportedFile(fileName: string): boolean {
  const supported = ['doc', 'docx', 'pdf', 'txt']
  return supported.includes(getFileExtension(fileName))
}

/**
 * 格式化字节大小
 */
export function formatFileSize(bytes: number): string {
  if (bytes === 0) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB']
  const i = Math.floor(Math.log(bytes) / Math.log(1024))
  return (bytes / Math.pow(1024, i)).toFixed(1) + ' ' + units[i]
}

/**
 * 截断文本
 */
export function truncateText(text: string, maxLength: number): string {
  if (text.length <= maxLength) return text
  return text.slice(0, maxLength) + '...'
}

/** 切分策略中文映射 */
export const CHUNK_STRATEGY_OPTIONS = [
  { label: '固定长度', value: 'fixed_length', description: '按固定字符数滑动窗口切分' },
  { label: '段落切分', value: 'paragraph', description: '按自然段落分割' },
  { label: '语义切分', value: 'semantic', description: '基于语义相似度的智能切分' },
] as const
