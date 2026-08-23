import { afterEach, describe, expect, it, vi } from 'vitest'
import type { DocumentImportJob } from '@/types/import'
import {
  documentJobStep,
  documentProviderLabel,
  formatDocumentJobDuration,
  formatDocumentSourceLocator,
  isAbortError,
  MAX_DOCUMENT_FILE_BYTES,
  pollDocumentImportJob,
  validateDocumentFileSize,
} from './documentImport'

function job(partial: Partial<DocumentImportJob> = {}): DocumentImportJob {
  return {
    jobId: 'dij-test',
    fileId: 'file-test',
    knowledgeBaseCode: 'kb-test',
    fileName: 'contract.pdf',
    fileType: 'pdf',
    providerType: 'DOCLING',
    status: 'PARSING',
    stage: 'PARSING',
    attemptCount: 1,
    maxAttempts: 3,
    autoCommit: false,
    ...partial,
  }
}

describe('document import presentation contract', () => {
  afterEach(() => {
    vi.useRealTimers()
  })

  it('formats providers, structured anchors and job stages', () => {
    expect(documentProviderLabel('DOCLING')).toBe('Docling')
    expect(documentProviderLabel('JAVA_FAST')).toBe('Java Fast')
    expect(formatDocumentSourceLocator(JSON.stringify({ pageStart: 2, pageEnd: 4 })))
      .toBe('第 2–4 页')
    expect(formatDocumentSourceLocator({ sheetName: '报价', cellRange: 'B2:F18' }))
      .toBe('报价 · B2:F18')
    expect(formatDocumentSourceLocator('{broken')).toBe('')
    expect(documentJobStep(job({ status: 'INDEXING', stage: 'INDEXING', autoCommit: true }))).toBe(2)
    expect(documentJobStep(job({ status: 'FAILED', stage: 'PARSING' }))).toBe(1)
  })

  it('enforces the same 50 MiB browser boundary as the service', () => {
    expect(validateDocumentFileSize({ size: MAX_DOCUMENT_FILE_BYTES })).toBeNull()
    expect(validateDocumentFileSize({ size: MAX_DOCUMENT_FILE_BYTES + 1 })).toContain('超过 50 MB')
  })

  it('formats active and completed durations from server timestamps', () => {
    const active = job({ createTime: '2026-08-20T10:00:00' })
    expect(formatDocumentJobDuration(active, new Date('2026-08-20T10:01:05').getTime()))
      .toBe('1 分 5 秒')
    expect(formatDocumentJobDuration(job({
      createTime: '2026-08-20T10:00:00',
      completedAt: '2026-08-20T10:00:42',
    }))).toBe('42 秒')
  })

  it('backs off polling and stops when preview becomes available', async () => {
    vi.useFakeTimers()
    const fetchJob = vi.fn()
      .mockResolvedValueOnce(job())
      .mockResolvedValueOnce(job({
        status: 'PARSED',
        stage: 'PARSED',
        preview: {
          fileName: 'contract.pdf',
          chunkStrategy: 'fixed_length',
          chunkSize: 500,
          chunkOverlap: 50,
          totalChunks: 1,
          chunks: [{ index: 0, content: '正文', length: 2 }],
        },
      }))
    const updates: string[] = []
    const resultPromise = pollDocumentImportJob({
      jobId: 'dij-test',
      includePreview: true,
      fetchJob,
      initialDelayMs: 10,
      maxDelayMs: 20,
      stopWhen: (current) => current.status === 'PARSED' && !!current.preview,
      onUpdate: (current) => updates.push(current.status),
    })

    await vi.advanceTimersByTimeAsync(10)
    await expect(resultPromise).resolves.toMatchObject({ status: 'PARSED' })
    expect(fetchJob).toHaveBeenCalledTimes(2)
    expect(updates).toEqual(['PARSING', 'PARSED'])
  })

  it('stops polling immediately when the page aborts tracking', async () => {
    const controller = new AbortController()
    controller.abort()
    const promise = pollDocumentImportJob({
      jobId: 'dij-test',
      fetchJob: vi.fn(),
      stopWhen: () => false,
      signal: controller.signal,
    })
    await expect(promise).rejects.toMatchObject({ name: 'AbortError' })
    await promise.catch((error) => expect(isAbortError(error)).toBe(true))
  })
})
