import { beforeEach, describe, expect, it, vi } from 'vitest'

const mocks = vi.hoisted(() => ({ batch: vi.fn(), get: vi.fn(), accept: vi.fn(), connection: vi.fn(), saveConnection: vi.fn(), credential: vi.fn() }))
vi.mock('@/api/httpApi', () => ({ saveManualHttpApiBatch: mocks.batch, getHttpApi: mocks.get, acceptHttpApi: mocks.accept,
  getHttpApiConnection: mocks.connection, saveHttpApiConnection: mocks.saveConnection }))
vi.mock('@/api/workflowCredential', () => ({ createWorkflowCredential: mocks.credential }))

import { buildBatchItem, configureDifyConnections, originOf, chunk, defaultBatchSettings, defaultSelection, filterCandidates, runBatchImport, validateSettings } from './httpApiBatchImport'
import type { HttpApiImportCandidate } from '@/api/httpApi'

function candidate(key: string, extra: Partial<HttpApiImportCandidate> = {}): HttpApiImportCandidate {
  const [method, path] = key.split(' ')
  return { key, host: 'erp.example.com', deprecated: false, warnings: [], unsupported: null, existing: false,
    request: { httpMethod: method as 'GET', path, sideEffect: 'READ_ONLY', origin: 'form', parameters: [], requestBody: null,
      responseType: 'object', authentication: { mode: 'API_KEY_HEADER', headerName: 'X-Key' }, businessGroups: ['库存'], summary: '库存分页' },
    ...extra }
}

beforeEach(() => { vi.clearAllMocks() })

describe('httpApiBatchImport', () => {
  it('applies unified auth, group and per-row side effects', () => {
    const item = candidate('POST /erp/stock/query')
    const settings = defaultBatchSettings()
    expect(buildBatchItem(item, settings, 'postman')).toMatchObject({ origin: 'postman', authentication: { mode: 'API_KEY_HEADER', headerName: 'X-Key' },
      businessGroups: ['库存'], sideEffect: 'READ_ONLY' })
    Object.assign(settings, { authMode: 'NONE', groupMode: 'NONE', sideEffects: { 'POST /erp/stock/query': 'WRITE' } })
    expect(buildBatchItem(item, settings, 'openapi')).toMatchObject({ authentication: { mode: 'NONE' }, businessGroups: [], sideEffect: 'WRITE' })
    Object.assign(settings, { authMode: 'API_KEY_HEADER', authHeaderName: 'bad header' })
    expect(validateSettings(settings)).toContain('请求头')
    Object.assign(settings, { authHeaderName: 'X-Token', groupMode: 'CUSTOM', groupName: ' ' })
    expect(validateSettings(settings)).toContain('分组')
  })

  it('selects importable, non-deprecated rows and filters', () => {
    const rows = [candidate('GET /a'), candidate('GET /b', { unsupported: 'x' }), candidate('GET /c', { deprecated: true }),
      candidate('POST /d', { existing: true, host: 'other' })]
    expect(defaultSelection(rows)).toEqual(['GET /a', 'POST /d'])
    expect(filterCandidates(rows, { keyword: '', group: '', method: 'POST', host: '', onlyNew: false }).map(r => r.key)).toEqual(['POST /d'])
    expect(filterCandidates(rows, { keyword: '', group: '', method: '', host: '', onlyNew: true })).toHaveLength(3)
    expect(filterCandidates(rows, { keyword: '', group: '', method: '', host: 'other', onlyNew: false })).toHaveLength(1)
    expect(chunk(Array.from({ length: 450 }, (_, i) => i)).map(group => group.length)).toEqual([200, 200, 50])
  })

  it('saves in chunks of 200 and accepts afterwards when asked', async () => {
    const rows = Array.from({ length: 201 }, (_, i) => candidate(`GET /a${i}`))
    mocks.batch.mockImplementation(async (_project: number, apis: unknown[]) => ({ data: { saved: apis.length, failed: 0,
      results: apis.map((_, index) => index === 0 ? { index, ok: false, httpMethod: 'GET', path: '/x', message: '路径无效' }
        : { index, ok: true, apiId: 1000 + index, created: index % 2 === 0, httpMethod: 'GET', path: '/x' }) } }))
    mocks.get.mockImplementation(async (id: number) => ({ data: { summary: { sourceStatus: id === 1001 ? 'ACCEPTED' : 'DISCOVERED',
      sourceConfirmed: true, sourceSetRevision: `r${id}`, sourceReason: null } } }))
    mocks.accept.mockResolvedValue({ data: {} })
    const settings = { ...defaultBatchSettings(), acceptAfterImport: true }
    const progress: string[] = []
    const outcome = await runBatchImport(7, rows, settings, 'openapi', value => progress.push(`${value.stage}:${value.done}/${value.total}`))
    expect(mocks.batch).toHaveBeenCalledTimes(2)
    expect(mocks.batch.mock.calls[0][1]).toHaveLength(200)
    expect(outcome.saved).toBe(199)
    expect(outcome.failures).toEqual([{ key: 'GET /a0', message: '路径无效' }, { key: 'GET /a200', message: '路径无效' }])
    expect(outcome.accepted).toBe(199)
    expect(mocks.accept).toHaveBeenCalledWith(1002, 'r1002')
    expect(mocks.accept).not.toHaveBeenCalledWith(1001, expect.anything())
    expect(progress[0]).toBe('saving:200/201')
    expect(progress[progress.length - 1]).toBe('accepting:199/199')
  })

  it('accepts read-only APIs by default and leaves writes pending', async () => {
    const rows = [candidate('GET /orders'), candidate('POST /orders', { request: { ...candidate('POST /orders').request, sideEffect: 'WRITE' } }),
      candidate('POST /orders/query')]
    mocks.batch.mockImplementation(async (_project: number, apis: unknown[]) => ({ data: { saved: apis.length, failed: 0,
      results: apis.map((_, index) => ({ index, ok: true, apiId: 10 + index, created: true, httpMethod: 'GET', path: '/x' })) } }))
    mocks.get.mockImplementation(async (id: number) => ({ data: { summary: { sourceStatus: 'DISCOVERED', sourceConfirmed: true,
      sourceSetRevision: `r${id}`, sourceReason: null } } }))
    mocks.accept.mockResolvedValue({ data: {} })
    const settings = { ...defaultBatchSettings(), sideEffects: { 'POST /orders/query': 'WRITE' as const } }
    const outcome = await runBatchImport(7, rows, settings, 'openapi')
    expect(outcome.accepted).toBe(1)
    expect(mocks.accept).toHaveBeenCalledTimes(1)
    expect(mocks.accept).toHaveBeenCalledWith(10, 'r10')
  })

  it('maps platform formats to their origins', () => {
    expect(originOf('DIFY')).toBe('dify')
    expect(originOf('N8N')).toBe('n8n')
    expect(originOf('OPENAPI3')).toBe('openapi')
  })

  it('stores the Dify key as a project credential and sets each API override', async () => {
    mocks.credential.mockResolvedValue({ data: { credentialRef: 'cred-1' } })
    mocks.connection.mockResolvedValueOnce({ data: { overrideRevision: null } }).mockResolvedValueOnce({ data: { overrideRevision: 3 } })
    mocks.saveConnection.mockResolvedValue({ data: {} })
    const result = await configureDifyConnections({ projectId: 7, projectCode: 'dify', title: '合同审查', apiKey: 'app-x',
      origin: 'https://dify.example.com', apiIds: [11, 12] })
    expect(result).toEqual({ configured: 2, error: null })
    expect(mocks.credential).toHaveBeenCalledWith(expect.objectContaining({ type: 'BEARER', scope: 'PROJECT', projectCode: 'dify',
      secret: { token: 'app-x' } }))
    expect(mocks.saveConnection).toHaveBeenNthCalledWith(2, 12, { origin: 'https://dify.example.com', authMode: 'BEARER',
      credentialRef: 'cred-1', expectedRevision: 3 })
  })

  it('reports a credential failure without touching connections', async () => {
    mocks.credential.mockRejectedValue({ response: { data: { message: '没有权限' } } })
    const result = await configureDifyConnections({ projectId: 7, projectCode: 'dify', title: '', apiKey: 'k', origin: 'https://d', apiIds: [1] })
    expect(result.error).toContain('没有权限')
    expect(mocks.saveConnection).not.toHaveBeenCalled()
  })
})
