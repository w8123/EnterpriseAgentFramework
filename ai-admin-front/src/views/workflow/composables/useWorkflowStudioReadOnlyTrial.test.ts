import { effectScope, nextTick, ref } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { WorkflowWorkingCopyState } from '@/types/workflow'

const api = vi.hoisted(() => ({ run: vi.fn() }))
vi.mock('@/api/workflow', () => ({ runWorkflowReadOnlyTrial: api.run }))
import {
  hasReadOnlyTrialTarget,
  readOnlyTrialPreview,
  trialFailureHint,
  useWorkflowStudioReadOnlyTrial,
} from './useWorkflowStudioReadOnlyTrial'

const graph = (extra: any[] = []) => ({
  schemaVersion: 2,
  inputSchema: { type: 'object', properties: { productName: { type: 'string', description: '产品名' },
    quantity: { type: 'integer' } }, required: ['productName'] },
  nodes: [
    { id: 'input', type: 'USER_INPUT', name: '开始', config: {} },
    { id: 'method', type: 'TOOL', name: '查询产品', ref: { kind: 'TOOL', qualifiedName: 'orders:findProduct' }, config: {} },
    { id: 'extract', type: 'PARAMETER_EXTRACT', name: '提取产品 ID', config: {} },
    { id: 'api', type: 'TOOL', name: '查询库存', ref: { kind: 'TOOL', qualifiedName: 'http-api:orders:dev:stock' },
      config: { httpApiAssetId: 7 } },
    { id: 'answer', type: 'ANSWER', name: '回答', config: {} },
    ...extra,
  ], edges: [], entryNodeId: 'input', exitNodeIds: ['answer'],
})
const success = { success: true, status: 'SUCCEEDED', runId: 'run-1', traceId: 'trace-1', workflowId: 'wf-1',
  revision: 'saved-r1', answer: '螺丝库存 42 件', variables: {}, elapsedMs: 30, errorCode: null, failedNodeId: null,
  failedNodeName: null, errorMessage: null,
  targets: [{ nodeId: 'method', nodeName: '查询产品', assetType: 'BUSINESS_METHOD', qualifiedName: 'orders:findProduct', apiId: 0 }],
  steps: [{ index: 0, nodeId: 'method', nodeType: 'TOOL', nodeName: '查询产品', status: 'SUCCESS', elapsedMs: 5,
    output: { productId: 42 }, errorCode: null, errorMessage: null }] }

function trial(graphJson = JSON.stringify(graph())) {
  const scope = effectScope()
  const studio = ref({ workflowId: 'wf-1', projectId: 41, projectCode: 'orders', status: 'PUBLISHED', revision: 'saved-r1',
    graphSpecJson: graphJson } as WorkflowWorkingCopyState)
  const open = ref(true), dirty = ref(false), authorized = ref(true), sessionScope = ref('actor-a:wf-1')
  let state!: ReturnType<typeof useWorkflowStudioReadOnlyTrial>
  scope.run(() => { state = useWorkflowStudioReadOnlyTrial({ studio, open, dirty, authorized, sessionScope }) })
  return { scope, studio, open, dirty, authorized, sessionScope, state }
}
async function settle() { for (let index = 0; index < 8; index++) await Promise.resolve() }

describe('whole-draft read-only trial preview', () => {
  it('lists every business method and API node and the Workflow input fields', () => {
    const preview = readOnlyTrialPreview(JSON.stringify(graph()))!
    expect(preview.targets.map((item) => [item.nodeId, item.assetType])).toEqual([
      ['method', 'BUSINESS_METHOD'], ['api', 'HTTP_API']])
    expect(preview.fields).toEqual([
      { name: 'productName', label: 'productName', required: true, type: 'string', description: '产品名' },
      { name: 'quantity', label: 'quantity', required: false, type: 'integer', description: undefined }])
    expect(preview.blocked).toEqual([])
    expect(hasReadOnlyTrialTarget(JSON.stringify(graph()))).toBe(true)
    expect(hasReadOnlyTrialTarget(JSON.stringify({ nodes: [{ id: 'a', type: 'LLM' }] }))).toBe(false)
    expect(hasReadOnlyTrialTarget('not json')).toBe(false)
  })

  it('falls back to USER_INPUT fields when the saved graph has no inputSchema', () => {
    const fields = readOnlyTrialPreview(JSON.stringify({ nodes: [
      { id: 'input', type: 'USER_INPUT', config: { fields: [{ name: 'orderNo', type: 'string', required: true }] } },
    ] }))!.fields
    expect(fields).toEqual([{ name: 'orderNo', label: 'orderNo', required: true, type: 'string', description: undefined }])
  })

  it('names visible side-effect nodes and refuses before any request', async () => {
    const { state, scope } = trial(JSON.stringify(graph([
      { id: 'open', type: 'PAGE_ACTION', name: '打开单据', config: {} },
      { id: 'ask', type: 'INTERACTION', name: '确认', config: { interactionType: 'CONFIRM' } },
      { id: 'show', type: 'INTERACTION', name: '展示', config: { interactionType: 'PRESENT_OUTPUT' } },
    ])))
    expect(state.reason.value).toContain('「打开单据」（页面动作）')
    expect(state.reason.value).toContain('「确认」（交互）')
    expect(state.reason.value).not.toContain('展示')
    await state.run()
    expect(api.run).not.toHaveBeenCalled()
    scope.stop()
  })
})

describe('whole-draft read-only trial run', () => {
  beforeEach(() => { vi.resetAllMocks(); api.run.mockResolvedValue({ data: success }) })

  it('allows the saved working copy of a published Workflow and sends inputs plus the message', async () => {
    const { state, scope } = trial()
    expect(state.reason.value).toBe('')
    state.inputParams.productName = '螺丝'; state.inputParams.quantity = '3'; state.message.value = ' 查一下库存 '
    await state.run(); await settle()
    expect(api.run).toHaveBeenCalledExactlyOnceWith({ workflowId: 'wf-1', expectedRevision: 'saved-r1',
      inputParams: { productName: '螺丝', quantity: 3 }, message: '查一下库存' })
    expect(state.result.value?.answer).toBe('螺丝库存 42 件')
    expect(state.error.value).toBe('')
    scope.stop()
  })

  it('validates required and typed inputs locally without dispatch', async () => {
    const { state, scope } = trial()
    await state.run()
    expect(state.invalidField.value).toBe('productName'); expect(api.run).not.toHaveBeenCalled()
    state.inputParams.productName = '螺丝'; state.inputParams.quantity = '1.5'
    await state.run()
    expect(state.invalidField.value).toBe('quantity'); expect(state.error.value).toContain('整数')
    expect(api.run).not.toHaveBeenCalled()
    scope.stop()
  })

  it('requires a saved, clean and authorized draft', async () => {
    const { state, dirty, authorized, studio, scope } = trial()
    dirty.value = true; await nextTick()
    expect(state.reason.value).toContain('先保存')
    dirty.value = false; authorized.value = false; await nextTick()
    expect(state.reason.value).toContain('权限')
    authorized.value = true; studio.value = { ...studio.value!, revision: undefined } as any; await nextTick()
    expect(state.reason.value).toContain('保存')
    scope.stop()
  })

  it('shows the failing node message from an in-run failure', async () => {
    api.run.mockResolvedValue({ data: { ...success, success: false, status: 'FAILED', answer: null,
      errorCode: 'BUSINESS_METHOD_TRIAL_INPUT_INVALID', failedNodeId: 'method', failedNodeName: '查询产品',
      errorMessage: '「查询产品」输入字段无效: productName (REQUIRED)' } })
    const { state, scope } = trial()
    state.inputParams.productName = '螺丝'
    await state.run(); await settle()
    expect(state.failedNodeId.value).toBe('method')
    expect(state.error.value).toBe('「查询产品」输入字段无效: productName (REQUIRED)')
    scope.stop()
  })

  it('shows server-side blocked nodes from a refused draft', async () => {
    api.run.mockRejectedValue({ response: { status: 409, data: { success: false, errorCode: 'BUSINESS_METHOD_TRIAL_READ_ONLY_REQUIRED',
      errorMessage: '「查询产品」不是只读业务方法', failedNodeId: 'method',
      blockedNodes: [{ nodeId: 'method', nodeName: '查询产品', nodeType: 'TOOL', reason: '会写入业务数据' }] } } })
    const { state, scope } = trial()
    state.inputParams.productName = '螺丝'
    await state.run(); await settle()
    expect(state.error.value).toBe('「查询产品」不是只读业务方法（BUSINESS_METHOD_TRIAL_READ_ONLY_REQUIRED）')
    expect(state.blockedNodes.value).toHaveLength(1)
    expect(state.failedNodeId.value).toBe('method')
    scope.stop()
  })

  it('drops a late result after the saved revision changes', async () => {
    let resolve!: (value: unknown) => void
    api.run.mockReturnValue(new Promise((done) => { resolve = done }))
    const { state, studio, scope } = trial()
    state.inputParams.productName = '螺丝'
    const pending = state.run()
    studio.value = { ...studio.value!, revision: 'saved-r2' } as any; await nextTick()
    resolve({ data: success }); await pending; await settle()
    expect(state.result.value).toBeNull()
    scope.stop()
  })

  it('maps codes to actionable hints', () => {
    expect(trialFailureHint('WORKFLOW_TRIAL_GRAPH_UNSUPPORTED')).toContain('不能只读试运行')
    expect(trialFailureHint('HTTP_API_TRIAL_READ_ONLY_REQUIRED')).toContain('只读')
    expect(trialFailureHint('BUSINESS_METHOD_TRIAL_DISPATCH_LIMIT_EXCEEDED')).toContain('上限')
    expect(trialFailureHint('WORKFLOW_TRIAL_FACTS_STALE_AFTER_DISPATCH')).toContain('勿直接重试')
  })
})
