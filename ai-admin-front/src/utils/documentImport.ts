import type { DocumentImportJob, DocumentImportJobStatus } from '@/types/import'

export const MAX_DOCUMENT_FILE_BYTES = 50 * 1024 * 1024
export const DOCUMENT_JOB_STORAGE_KEY = 'reachai:knowledge:document-import-job'

export interface DocumentSourceLocator {
  pageStart?: number | null
  pageEnd?: number | null
  slideNumber?: number | null
  sheetName?: string | null
  cellRange?: string | null
  lineStart?: number | null
  lineEnd?: number | null
  boundingBoxJson?: string | null
  providerReference?: string | null
}

export type DocumentStatusTone = '' | 'success' | 'warning' | 'danger' | 'info'

const statusLabels: Record<DocumentImportJobStatus, string> = {
  QUEUED: '等待处理',
  PARSING: '正在解析',
  PARSED: '解析完成',
  INDEXING: '正在入库',
  COMPLETED: '入库完成',
  RETRY_WAIT: '等待重试',
  FAILED: '处理失败',
  CANCELLED: '已取消',
}

const elementLabels: Record<string, string> = {
  HEADING: '标题',
  PARAGRAPH: '段落',
  TABLE: '表格',
  PICTURE: '图片',
  TEXT: '文本',
  LIST_ITEM: '列表项',
  CODE: '代码',
}

export function documentProviderLabel(provider?: string | null): string {
  if (provider === 'DOCLING') return 'Docling'
  if (provider === 'JAVA_FAST') return 'Java Fast'
  return provider || '待识别'
}

export function documentStatusLabel(status: DocumentImportJobStatus): string {
  return statusLabels[status] || status
}

export function documentStatusTone(status: DocumentImportJobStatus): DocumentStatusTone {
  if (status === 'COMPLETED' || status === 'PARSED') return 'success'
  if (status === 'FAILED') return 'danger'
  if (status === 'CANCELLED') return 'info'
  if (status === 'RETRY_WAIT') return 'warning'
  return ''
}

export function documentJobStep(job: DocumentImportJob): number {
  if (job.status === 'COMPLETED') return 4
  if (job.status === 'INDEXING') return 2
  if (job.status === 'PARSED') return 2
  if (job.status === 'PARSING' || job.status === 'RETRY_WAIT') return 1
  if (job.status === 'FAILED') return job.stage === 'INDEXING' ? 2 : 1
  return 0
}

export function isDocumentJobTerminal(status: DocumentImportJobStatus): boolean {
  return status === 'COMPLETED' || status === 'FAILED' || status === 'CANCELLED'
}

export function canRetryDocumentJob(status: DocumentImportJobStatus): boolean {
  return status === 'FAILED' || status === 'RETRY_WAIT'
}

export function canCancelDocumentJob(status: DocumentImportJobStatus): boolean {
  return status === 'QUEUED' || status === 'PARSING' || status === 'PARSED' || status === 'RETRY_WAIT'
}

export function documentElementLabel(elementType?: string | null): string {
  if (!elementType) return ''
  return elementLabels[elementType] || elementType
}

export function parseDocumentSourceLocator(
  value?: string | DocumentSourceLocator | null,
): DocumentSourceLocator | null {
  if (!value) return null
  if (typeof value === 'object') return value
  try {
    const parsed = JSON.parse(value)
    return parsed && typeof parsed === 'object' ? parsed as DocumentSourceLocator : null
  } catch {
    return null
  }
}

export function formatDocumentSourceLocator(
  value?: string | DocumentSourceLocator | null,
): string {
  const locator = parseDocumentSourceLocator(value)
  if (!locator) return ''
  const parts: string[] = []
  if (locator.pageStart) {
    parts.push(locator.pageEnd && locator.pageEnd !== locator.pageStart
      ? `第 ${locator.pageStart}–${locator.pageEnd} 页`
      : `第 ${locator.pageStart} 页`)
  }
  if (locator.slideNumber) parts.push(`第 ${locator.slideNumber} 张幻灯片`)
  if (locator.sheetName || locator.cellRange) {
    parts.push([locator.sheetName, locator.cellRange].filter(Boolean).join(' · '))
  }
  if (locator.lineStart) {
    parts.push(locator.lineEnd && locator.lineEnd !== locator.lineStart
      ? `第 ${locator.lineStart}–${locator.lineEnd} 行`
      : `第 ${locator.lineStart} 行`)
  }
  if (locator.boundingBoxJson) parts.push('含版面坐标')
  if (parts.length === 0 && locator.providerReference) parts.push(locator.providerReference)
  return parts.join(' / ')
}

export function validateDocumentFileSize(file: Pick<File, 'size'>): string | null {
  if (file.size <= MAX_DOCUMENT_FILE_BYTES) return null
  return `文件超过 50 MB，当前大小为 ${formatFileSize(file.size)}`
}

export function formatFileSize(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes <= 0) return '0 B'
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}

export function formatDocumentJobDuration(job: DocumentImportJob, now = Date.now()): string {
  if (!job.createTime) return '—'
  const startedAt = Date.parse(job.createTime)
  const finishedAt = job.completedAt ? Date.parse(job.completedAt) : now
  if (!Number.isFinite(startedAt) || !Number.isFinite(finishedAt) || finishedAt < startedAt) return '—'
  const seconds = Math.max(0, Math.round((finishedAt - startedAt) / 1000))
  if (seconds < 60) return `${seconds} 秒`
  const minutes = Math.floor(seconds / 60)
  const rest = seconds % 60
  return rest ? `${minutes} 分 ${rest} 秒` : `${minutes} 分钟`
}

export interface PollDocumentJobOptions {
  jobId: string
  includePreview?: boolean
  fetchJob: (jobId: string, includePreview: boolean) => Promise<DocumentImportJob>
  stopWhen: (job: DocumentImportJob) => boolean
  onUpdate?: (job: DocumentImportJob) => void
  signal?: AbortSignal
  timeoutMs?: number
  initialDelayMs?: number
  maxDelayMs?: number
}

export async function pollDocumentImportJob(options: PollDocumentJobOptions): Promise<DocumentImportJob> {
  const startedAt = Date.now()
  const timeoutMs = options.timeoutMs ?? 20 * 60 * 1000
  const maxDelayMs = options.maxDelayMs ?? 3000
  let delayMs = options.initialDelayMs ?? 1000

  for (;;) {
    throwIfAborted(options.signal)
    const job = await options.fetchJob(options.jobId, options.includePreview ?? false)
    options.onUpdate?.(job)
    if (options.stopWhen(job) || isDocumentJobTerminal(job.status)) return job
    if (Date.now() - startedAt >= timeoutMs) {
      throw new Error('任务仍在后台处理，页面已暂停自动刷新；稍后可重新打开页面继续查看')
    }
    await abortableDelay(delayMs, options.signal)
    delayMs = Math.min(maxDelayMs, Math.round(delayMs * 1.5))
  }
}

export function isAbortError(error: unknown): boolean {
  return error instanceof Error && error.name === 'AbortError'
}

function throwIfAborted(signal?: AbortSignal) {
  if (signal?.aborted) throw abortError()
}

function abortableDelay(milliseconds: number, signal?: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    throwIfAborted(signal)
    const timer = window.setTimeout(() => {
      signal?.removeEventListener('abort', onAbort)
      resolve()
    }, milliseconds)
    const onAbort = () => {
      window.clearTimeout(timer)
      signal?.removeEventListener('abort', onAbort)
      reject(abortError())
    }
    signal?.addEventListener('abort', onAbort, { once: true })
  })
}

function abortError(): Error {
  const error = new Error('任务轮询已取消')
  error.name = 'AbortError'
  return error
}
