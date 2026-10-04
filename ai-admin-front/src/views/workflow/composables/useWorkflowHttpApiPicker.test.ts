import { effectScope, nextTick, ref } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const api = vi.hoisted(() => ({ getScanProjectDetail: vi.fn(), listHttpApis: vi.fn(),
  getHttpApi: vi.fn(), getHttpApiConnection: vi.fn() }))
vi.mock('@/api/scanProject', () => ({ getScanProjectDetail: api.getScanProjectDetail }))
vi.mock('@/api/httpApi', () => ({ listHttpApis: api.listHttpApis, getHttpApi: api.getHttpApi,
  getHttpApiConnection: api.getHttpApiConnection }))

import { useWorkflowHttpApiPicker } from './useWorkflowHttpApiPicker'

const HASH = 'a'.repeat(64)
const REF = `http-api:orders:dev:${'b'.repeat(64)}`
const summary = { id: 41, qualifiedName: REF, projectId: 7, projectCode: 'orders', environment: 'dev',
  httpMethod: 'GET', routeTemplate: '/orders/{id}', sourceStatus: 'ACCEPTED', sourceConfirmed: true,
  sourceReason: null, candidateContractHash: HASH, acceptedContractHash: HASH,
  sourceSetRevision: 'c'.repeat(64), activeSourceCount: 1, sourceKinds: ['OPENAPI_SCAN'] }
const detail = { summary, acceptedContract: { identity: { method: 'GET', routeTemplate: '/orders/{id}' },
  parameters: [{ name: 'id', location: 'PATH', required: true, schema: { type: 'string' } }],
  requestBody: null, responses: [], sideEffect: 'READ_ONLY' }, sources: [] }
const connection = { qualifiedName: REF, projectId: 7, projectCode: 'orders', environment: 'dev',
  status: 'CONFIGURED', revision: 1, blockingReason: null }

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((done) => { resolve = done })
  return { promise, resolve }
}

function picker() {
  const owner = effectScope()
  const projectId = ref<number | null>(7), projectCode = ref('orders')
  const nodeId = ref('tool-a'), requestScopeKey = ref('account-a'), selectedAssetId = ref<number | null>(null)
  let state!: ReturnType<typeof useWorkflowHttpApiPicker>
  owner.run(() => { state = useWorkflowHttpApiPicker({ projectId, projectCode, nodeId,
    requestScopeKey, selectedAssetId }) })
  return { owner, projectId, projectCode, requestScopeKey, selectedAssetId, state }
}

async function settle() { for (let index = 0; index < 8; index++) await Promise.resolve() }

describe('useWorkflowHttpApiPicker', () => {
  beforeEach(() => vi.resetAllMocks())
  it('uses project environment, explicit IME-safe search and server pagination', async () => {
    api.getScanProjectDetail.mockResolvedValue({ data: { id: 7, projectCode: 'orders', environment: 'dev' } })
    api.listHttpApis.mockResolvedValue({ data: { records: [summary], total: 12 } })
    api.getHttpApi.mockResolvedValue({ data: detail })
    api.getHttpApiConnection.mockResolvedValue({ data: connection })
    const p = picker()
    p.state.dialogOpen.value = true
    await nextTick(); await settle()
    expect(api.listHttpApis).toHaveBeenLastCalledWith({ projectId: 7, environment: 'dev',
      sourceStatus: 'ACCEPTED', current: 1, size: 10, keyword: undefined })
    expect(p.state.rows.value[0].reason).toBe('')
    p.state.keyword.value = '订单'
    p.state.submitSearch({ isComposing: true })
    expect(api.listHttpApis).toHaveBeenCalledTimes(1)
    p.state.submitSearch({ isComposing: false })
    await settle()
    expect(api.listHttpApis).toHaveBeenLastCalledWith(expect.objectContaining({ keyword: '订单', current: 1 }))
    p.state.changePage(2)
    await settle()
    expect(api.listHttpApis).toHaveBeenLastCalledWith(expect.objectContaining({ keyword: '订单', current: 2 }))
    p.owner.stop()
  })

  it('rejects a connection that becomes blocked at click time without changing the saved ref', async () => {
    api.getScanProjectDetail.mockResolvedValue({ data: { id: 7, projectCode: 'orders', environment: 'dev' } })
    api.listHttpApis.mockResolvedValue({ data: { records: [summary], total: 1 } })
    api.getHttpApi.mockResolvedValue({ data: detail })
    api.getHttpApiConnection.mockResolvedValueOnce({ data: connection }).mockResolvedValueOnce({ data: {
      ...connection, status: 'BLOCKED', blockingReason: '凭据已停用',
    } })
    const p = picker()
    p.state.dialogOpen.value = true
    await nextTick(); await settle()
    const choice = await p.state.selectCandidate(p.state.rows.value[0])
    expect(choice).toBeNull()
    expect(p.state.rows.value[0].reason).toBe('凭据已停用')
    expect(p.selectedAssetId.value).toBeNull()
    p.owner.stop()
  })

  it('ignores late account/project responses and keeps saved config untouched on read failure', async () => {
    const first = deferred<{ data: { id: number; projectCode: string; environment: string } }>()
    api.getScanProjectDetail.mockReturnValueOnce(first.promise)
      .mockResolvedValue({ data: { id: 7, projectCode: 'orders', environment: 'dev' } })
    api.listHttpApis.mockResolvedValue({ data: { records: [], total: 0 } })
    const p = picker()
    p.state.dialogOpen.value = true
    await nextTick()
    p.requestScopeKey.value = 'account-b'
    await nextTick(); await settle()
    first.resolve({ data: { id: 7, projectCode: 'orders', environment: 'prod' } })
    await settle()
    expect(p.state.environment.value).toBe('dev')
    expect(api.listHttpApis).toHaveBeenCalledTimes(1)
    p.state.closeDialog()
    p.selectedAssetId.value = 41
    api.getHttpApi.mockRejectedValueOnce(new Error('unavailable'))
    await nextTick(); await settle()
    expect(p.state.selectedDetail.value).toBeNull()
    expect(p.state.selectedReason.value).toContain('引用和映射未被修改')
    expect(p.selectedAssetId.value).toBe(41)
    p.owner.stop()
  })
})
