import { effectScope, nextTick, ref } from 'vue'
import { readFileSync } from 'node:fs'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { WorkflowWorkingCopyState } from '@/types/workflow'

const api = vi.hoisted(() => ({ getHttpApi: vi.fn(), getHttpApiConnection: vi.fn(), getMethod: vi.fn(), run: vi.fn() }))
vi.mock('@/api/httpApi', () => ({ getHttpApi: api.getHttpApi, getHttpApiConnection: api.getHttpApiConnection }))
vi.mock('@/api/businessMethodInvocation', () => ({ getBusinessMethodInvocationContext: api.getMethod }))
vi.mock('@/api/workflow', () => ({ runWorkflowReadOnlyTrial: api.run }))
import { savedReadOnlyTrialTarget, trialFailureHint, useWorkflowStudioReadOnlyTrial } from './useWorkflowStudioReadOnlyTrial'

const REF = 'http-api:orders:dev:api'
const graph = () => ({ nodes: [
  { id: 'api', type: 'TOOL', ref: { kind: 'TOOL', qualifiedName: REF }, config: { httpApiAssetId: 1,
    inputMapping: { 'pathParams.orderId': 'params.orderId', 'queryParams.detailLevel': 'params.detailLevel' } } },
  { id: 'variable', type: 'VARIABLE_ASSIGN', config: { assignments: { order_state: 'nodeOutput.api.state' } } },
], edges: [{ from: 'api', to: 'variable', condition: 'always' }], entryNodeId: 'api', exitNodeIds: ['variable'] })
const owner = () => ({ summary: { id: 1, qualifiedName: REF, projectId: 41, projectCode: 'orders', environment: 'dev',
  httpMethod: 'GET', routeTemplate: '/orders/{orderId}', sourceConfirmed: true, sourceStatus: 'ACCEPTED',
  acceptedContractHash: 'a'.repeat(64), candidateContractHash: 'a'.repeat(64), sourceSetRevision: 'b'.repeat(64),
  activeSourceCount: 1 }, acceptedContract: { identity: { method: 'GET', routeTemplate: '/orders/{orderId}' },
  sideEffect: 'READ_ONLY', parameters: [
    { name: 'orderId', location: 'PATH', required: true, schema: { type: 'string' } },
    { name: 'detailLevel', location: 'QUERY', required: false, schema: { type: 'string' } },
  ], responses: [] }, sources: [] })
const connection = { qualifiedName: REF, projectId: 41, projectCode: 'orders', environment: 'dev',
  status: 'CONFIGURED', revision: 1, blockingReason: null }
const result = { success: true, status: 'SUCCESS', traceId: 'trial-trace', runId: 'trial-run', workflowId: 'wf-api',
  revision: 'saved-r1', apiId: 1, qualifiedName: REF, environment: 'dev', apiOutput: { state: 'PAID' },
  variables: { order_state: 'PAID' }, elapsedMs: 20 }
function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((done) => { resolve = done })
  return { promise, resolve }
}
const methodGraph = () => ({ nodes: [
  { id: 'method', type: 'TOOL', ref: { kind: 'TOOL', qualifiedName: 'orders:normalize' },
    config: { inputMapping: { orderNo: 'params.orderNo' } } },
  { id: 'variable', type: 'VARIABLE_ASSIGN', config: { assignments: { normalized: 'nodeOutput.method.data' } } },
], edges: [{ from: 'method', to: 'variable', condition: 'always' }], entryNodeId: 'method', exitNodeIds: ['variable'] })
const methodOwner = (): any => ({ contractVersion: 1, name: 'orders_normalize', qualifiedName: 'orders:normalize',
  sourceQualifiedName: 'orders:normalize', assetType: 'BUSINESS_METHOD', projectId: 41, projectCode: 'orders',
  currentContractHash: 'a'.repeat(64), acceptedContractHash: 'a'.repeat(64), sourceContractHash: 'a'.repeat(64),
  sourceAvailability: 'READY', enabled: true, sideEffect: 'READ_ONLY', credentialAvailable: true,
  businessIdentityRequired: false, executable: true, responseType: 'java.lang.String', parameters: [{ name: 'orderNo', type: 'String', required: true }] })
const methodResult = { ...result, assetType: 'BUSINESS_METHOD' as const, qualifiedName: 'orders:normalize', apiId: 0,
  environment: null, apiOutput: null, methodName: 'orders_normalize', methodOutput: { data: 'N-A-1024' },
  variables: { normalized: 'N-A-1024' } }
function trial(method = false) {
  const scope = effectScope()
  const studio = ref({ workflowId: 'wf-api', projectId: 41, projectCode: 'orders', status: 'DRAFT', revision: 'saved-r1',
    graphSpecJson: JSON.stringify(method ? methodGraph() : graph()) } as WorkflowWorkingCopyState)
  const open = ref(true), dirty = ref(false), authorized = ref(true), sessionScope = ref('account-a:wf-api')
  let state!: ReturnType<typeof useWorkflowStudioReadOnlyTrial>
  scope.run(() => { state = useWorkflowStudioReadOnlyTrial({ studio, open, dirty, authorized, sessionScope }) })
  return { scope, studio, open, dirty, authorized, sessionScope, state }
}
async function settle() { for (let index = 0; index < 8; index++) await Promise.resolve() }

describe('saved API draft read-only trial', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    api.getHttpApi.mockResolvedValue({ data: owner() }); api.getHttpApiConnection.mockResolvedValue({ data: connection })
    api.run.mockResolvedValue({ data: result })
  })
  it('rejects extra nodes, release pins, conditional edges and unproven variable sources', () => {
    expect(savedReadOnlyTrialTarget(JSON.stringify(graph()))?.supported).toBe(true)
    const extra = graph(); extra.nodes.push({ id: 'other', type: 'HTTP_REQUEST' } as any)
    expect(savedReadOnlyTrialTarget(JSON.stringify(extra))?.supported).toBe(false)
    const pinned = graph(); (pinned.nodes[0].ref as any).definitionId = 9
    expect(savedReadOnlyTrialTarget(JSON.stringify(pinned))?.supported).toBe(false)
    const conditional = graph(); conditional.edges[0].condition = 'params.allow'
    expect(savedReadOnlyTrialTarget(JSON.stringify(conditional))?.supported).toBe(false)
    const invalid = graph(); invalid.nodes[1].config!.assignments!.order_state = 'memory.personal'
    expect(savedReadOnlyTrialTarget(JSON.stringify(invalid))?.supported).toBe(false)
    const fallback = graph(); (fallback.nodes[0] as any).errorPolicy = { strategy: 'FALLBACK', fallbackNodeId: 'api' }
    expect(savedReadOnlyTrialTarget(JSON.stringify(fallback))?.supported).toBe(false)
  })
  it('does not treat an unopened JSON editor buffer as unsaved trial edits', () => {
    const studioSource = readFileSync('src/views/workflow/WorkflowStudio.vue', 'utf8')
    expect(studioSource).toContain(':dirty="saving || visualDirty || workflowMetaDirty() || (jsonDrawerVisible && sourceBufferChanged)"')
  })
  it('dirty draft, missing permission and required input stop without a trial request', async () => {
    const t = trial(); await settle()
    t.dirty.value = true; await t.state.run()
    expect(t.state.error.value).toContain('先保存')
    t.dirty.value = false; t.authorized.value = false; await t.state.run()
    expect(t.state.error.value).toContain('权限')
    t.authorized.value = true; await t.state.run()
    expect(t.state.invalidField.value).toBe('orderId')
    expect(api.run).not.toHaveBeenCalled(); t.scope.stop()
  })
  it('GET with unknown/write declaration, stale source or blocked connection never runs', async () => {
    for (const sideEffect of ['UNKNOWN', 'WRITE', '']) {
      const detail = owner(); detail.acceptedContract.sideEffect = sideEffect
      api.getHttpApi.mockResolvedValueOnce({ data: detail })
      const t = trial(); await settle(); t.state.inputParams.orderId = 'O-321'; await t.state.run()
      expect(t.state.reason.value).toContain('只读'); t.scope.stop()
    }
    const stale = owner(); stale.summary.sourceConfirmed = false
    api.getHttpApi.mockResolvedValueOnce({ data: stale })
    const s = trial(); await settle(); await s.state.run(); expect(s.state.reason.value).toContain('来源'); s.scope.stop()
    api.getHttpApiConnection.mockResolvedValueOnce({ data: { ...connection, status: 'BLOCKED' } })
    const b = trial(); await settle(); await b.state.run(); expect(b.state.reason.value).toContain('可用'); b.scope.stop()
    expect(api.run).not.toHaveBeenCalled()
  })
  it('sends only saved workflow revision and scalar input, displays API output and variable', async () => {
    const t = trial(); await settle(); expect(t.state.reason.value).toBe('')
    t.state.inputParams.orderId = 'O-321'; t.state.inputParams.detailLevel = 'full'
    await t.state.run()
    expect(api.run).toHaveBeenCalledWith({ workflowId: 'wf-api', expectedRevision: 'saved-r1',
      inputParams: { orderId: 'O-321', detailLevel: 'full' } })
    expect(t.state.result.value?.apiOutput).toEqual({ state: 'PAID' })
    expect(t.state.result.value?.variables).toEqual({ order_state: 'PAID' }); t.scope.stop()
  })
  it('double submission and revision change keep one in-flight call; stale result is discarded', async () => {
    const pending = deferred<{ data: typeof result }>(); api.run.mockReturnValue(pending.promise)
    const t = trial(); await settle(); t.state.inputParams.orderId = 'O-321'
    const run = t.state.run(); await t.state.run(); expect(api.run).toHaveBeenCalledTimes(1)
    t.studio.value.revision = 'saved-r2'; await nextTick(); await t.state.run()
    expect(t.state.running.value).toBe(true); expect(api.run).toHaveBeenCalledTimes(1)
    pending.resolve({ data: result }); await run; await settle()
    expect(t.state.result.value).toBeNull(); expect(t.state.running.value).toBe(false)
    expect(t.state.inputParams.orderId).toBe('O-321'); t.scope.stop()
  })
  it('account switch clears inputs and discards the previous account preview and result', async () => {
    const pending = deferred<{ data: typeof result }>(); api.run.mockReturnValue(pending.promise)
    const t = trial(); await settle(); t.state.inputParams.orderId = 'O-321'
    const run = t.state.run(); t.sessionScope.value = 'account-b:wf-api'; await nextTick()
    expect(t.state.inputParams.orderId).toBeUndefined()
    pending.resolve({ data: result }); await run; await settle()
    expect(t.state.result.value).toBeNull(); t.scope.stop()
  })
  it('read/ACL/network failures retain same-scope inputs and never automatically retry', async () => {
    const t = trial(); await settle(); t.state.inputParams.orderId = 'O-321'
    api.run.mockRejectedValueOnce({ response: { status: 403, data: { errorCode: 'HTTP_API_TRIAL_ACL_DENIED' } } })
    await t.state.run(); expect(t.state.error.value).toContain('授权不足')
    expect(t.state.inputParams.orderId).toBe('O-321')
    api.run.mockRejectedValueOnce(new Error('network unavailable'))
    await t.state.run(); expect(t.state.error.value).toContain('核对运行记录')
    expect(api.run).toHaveBeenCalledTimes(2)
    api.getHttpApi.mockRejectedValueOnce(new Error('read unavailable')); await t.state.refresh()
    expect(t.state.error.value).toContain('输入已保留'); expect(t.state.inputParams.orderId).toBe('O-321')
    t.scope.stop()
  })
  it('gives actionable hints without exposing upstream error text', () => {
    expect(trialFailureHint('HTTP_API_TRIAL_DRAFT_STALE')).toContain('保存')
    expect(trialFailureHint('HTTP_API_CREDENTIAL_DISABLED')).toContain('连接配置')
    expect(trialFailureHint('HTTP_API_SOURCE_NOT_READY')).toContain('API 详情')
    expect(trialFailureHint('HTTP_API_WORKFLOW_INPUT_INVALID')).toContain('必填项')
    expect(trialFailureHint('HTTP_API_TRIAL_RESULT_STALE_AFTER_DISPATCH')).toContain('勿直接重试')
    expect(trialFailureHint('HTTP_API_WORKFLOW_RESULT_UNCONFIRMED')).toContain('核对运行记录')
  })
})

describe('saved business method draft read-only trial', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    api.getMethod.mockResolvedValue({ data: methodOwner() })
    api.run.mockResolvedValue({ data: methodResult })
  })
  it('reads owner by saved qualifiedName and submits only revision and scalar input', async () => {
    const t = trial(true); await settle()
    expect(api.getMethod).toHaveBeenCalledWith('orders:normalize')
    expect(api.getHttpApi).not.toHaveBeenCalled(); expect(api.getHttpApiConnection).not.toHaveBeenCalled()
    expect(t.state.reason.value).toBe(''); expect(t.state.isMethod.value).toBe(true)
    await t.state.run(); expect(t.state.invalidField.value).toBe('orderNo'); expect(api.run).not.toHaveBeenCalled()
    t.state.inputParams.orderNo = 'A-1024'; await t.state.run()
    expect(api.run).toHaveBeenCalledExactlyOnceWith({ workflowId: 'wf-api', expectedRevision: 'saved-r1',
      inputParams: { orderNo: 'A-1024' } })
    expect(t.state.result.value?.methodOutput).toEqual({ data: 'N-A-1024' })
    expect(t.state.result.value?.variables).toEqual({ normalized: 'N-A-1024' }); t.scope.stop()
  })
  it('owner type/project/acceptance/source/credential/identity/side-effect drift never submits', async () => {
    for (const change of [ { projectId: 99 }, { qualifiedName: 'other:normalize' }, { assetType: 'HTTP_API' },
      { enabled: false }, { sourceAvailability: 'MISSING' }, { acceptedContractHash: 'b'.repeat(64) },
      { sourceContractHash: 'b'.repeat(64) }, { sideEffect: 'WRITE' }, { sideEffect: 'UNKNOWN' },
      { credentialAvailable: false }, { businessIdentityRequired: true }, { executable: false } ]) {
      api.getMethod.mockResolvedValueOnce({ data: { ...methodOwner(), ...change } })
      const t = trial(true); await settle(); t.state.inputParams.orderNo = 'A-1024'; await t.state.run()
      expect(t.state.reason.value, JSON.stringify(change)).not.toBe(''); t.scope.stop()
    }
    expect(api.run).not.toHaveBeenCalled()
  })
  it('complex/sensitive/undeclared mappings and hidden args do not claim a supported trial', async () => {
    for (const parameter of [ { name: 'orderNo', type: 'OrderRequest', required: true },
      { name: 'orderNo', type: 'String', required: true, children: [{ name: 'secret', type: 'String' }] },
      { name: 'orderNo', type: 'String', required: true, metadata: { sensitive: true } },
      { name: 'anotherRequiredField', type: 'String', required: true } ]) {
      api.getMethod.mockResolvedValueOnce({ data: { ...methodOwner(), parameters: [parameter] } })
      const t = trial(true); await settle(); t.state.inputParams.orderNo = 'A-1024'; await t.state.run()
      expect(t.state.reason.value).not.toBe(''); t.scope.stop()
    }
    const mixed = methodGraph(); mixed.nodes.push({ id: 'api', type: 'TOOL', ref: { kind: 'TOOL', qualifiedName: REF } } as any)
    expect(savedReadOnlyTrialTarget(JSON.stringify(mixed))?.supported).toBe(false)
    const hidden = methodGraph(); (hidden.nodes[0].config as any).args = { tenantId: 'forged' }
    expect(savedReadOnlyTrialTarget(JSON.stringify(hidden))?.supported).toBe(false)
    const identity = methodGraph(); identity.nodes[0].config!.inputMapping!.orderNo = 'identity.userId'
    expect(savedReadOnlyTrialTarget(JSON.stringify(identity))?.supported).toBe(false)
    expect(api.run).not.toHaveBeenCalled()
  })
  it('method in-flight submission stays single and loses stale output after a revision/account switch', async () => {
    const pending = deferred<{ data: typeof methodResult }>(); api.run.mockReturnValue(pending.promise)
    const t = trial(true); await settle(); t.state.inputParams.orderNo = 'A-1024'
    const run = t.state.run(); await t.state.run(); expect(api.run).toHaveBeenCalledTimes(1)
    t.studio.value.revision = 'saved-r2'; t.sessionScope.value = 'account-b:wf-api'; await nextTick()
    expect(t.state.inputParams.orderNo).toBeUndefined(); await t.state.run(); expect(api.run).toHaveBeenCalledTimes(1)
    pending.resolve({ data: methodResult }); await run; await settle()
    expect(t.state.result.value).toBeNull(); expect(t.state.running.value).toBe(false); t.scope.stop()
  })
  it('late method-owner response cannot overwrite another project or resurrect old inputs', async () => {
    const pending = deferred<{ data: ReturnType<typeof methodOwner> }>(); api.getMethod.mockReturnValueOnce(pending.promise)
    const t = trial(true); t.sessionScope.value = 'account-b:wf-api'
    t.studio.value = { ...t.studio.value, projectId: 99, projectCode: 'other', revision: 'saved-r2' }
    await nextTick(); await settle(); pending.resolve({ data: methodOwner() }); await settle()
    expect(t.state.reason.value).toContain('所属项目'); expect(t.state.result.value).toBeNull()
    await t.state.run(); expect(api.run).not.toHaveBeenCalled(); t.scope.stop()
  })
  it('uncertain method response preserves inputs and does not retry automatically', async () => {
    const t = trial(true); await settle(); t.state.inputParams.orderNo = 'A-1024'
    api.run.mockRejectedValueOnce(new Error('upstream token must not be displayed'))
    await t.state.run(); await settle()
    expect(api.run).toHaveBeenCalledTimes(1); expect(t.state.error.value).toContain('勿直接重试')
    expect(t.state.error.value).not.toContain('upstream token'); expect(t.state.inputParams.orderNo).toBe('A-1024')
    expect(trialFailureHint('BUSINESS_METHOD_TRIAL_BUSINESS_IDENTITY_REQUIRED')).toContain('业务用户身份')
    expect(trialFailureHint('BUSINESS_METHOD_TRIAL_CREDENTIAL_UNAVAILABLE')).toContain('项目签名凭据')
    t.scope.stop()
  })
  it('owner ACL rejection explains the required authorization without a trial or retry', async () => {
    const t = trial(true); await settle(); t.state.inputParams.orderNo = 'A-1024'
    api.getMethod.mockRejectedValueOnce({ response: { status: 403, data: { message: 'must not expose raw upstream' } } })
    await t.state.refresh(); await t.state.run(); await settle()
    expect(t.state.reason.value).toContain('ACL'); expect(t.state.error.value).toContain('项目管理员')
    expect(t.state.error.value).not.toContain('raw upstream')
    expect(t.state.inputParams.orderNo).toBe('A-1024'); expect(api.getMethod).toHaveBeenCalledTimes(2)
    expect(api.run).not.toHaveBeenCalled(); t.scope.stop()
  })
})
