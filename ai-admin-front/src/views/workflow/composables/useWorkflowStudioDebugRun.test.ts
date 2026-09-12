import { computed, createApp, defineComponent, ref, type Ref } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  resolveWorkflowInitialChatField,
  useWorkflowStudioDebugRun,
  type UseWorkflowStudioDebugRunDeps,
} from './useWorkflowStudioDebugRun'

const mocks = vi.hoisted(() => ({
  getTraceDetail: vi.fn(),
  getRunOpsDetail: vi.fn(),
  getRecentRunOps: vi.fn(),
  debugWorkflowNode: vi.fn(),
  debugWorkflowRun: vi.fn(),
  listWorkflowVersions: vi.fn(),
  getWorkflowDebugSession: vi.fn(),
  createWorkflowDebugSession: vi.fn(),
  submitWorkflowDebugSession: vi.fn(),
  cancelWorkflowDebugSession: vi.fn(),
  success: vi.fn(),
  warning: vi.fn(),
  error: vi.fn(),
}))

vi.mock('@/api/trace', () => ({
  getTraceDetail: mocks.getTraceDetail,
}))

vi.mock('@/api/runops', () => ({
  getRecentRunOps: mocks.getRecentRunOps,
  getRunOpsDetail: mocks.getRunOpsDetail,
}))

vi.mock('@/api/workflow', () => ({
  debugWorkflowNode: mocks.debugWorkflowNode,
  debugWorkflowRun: mocks.debugWorkflowRun,
  listWorkflowVersions: mocks.listWorkflowVersions,
  getWorkflowDebugSession: mocks.getWorkflowDebugSession,
  createWorkflowDebugSession: mocks.createWorkflowDebugSession,
  submitWorkflowDebugSession: mocks.submitWorkflowDebugSession,
  cancelWorkflowDebugSession: mocks.cancelWorkflowDebugSession,
}))

vi.mock('element-plus', () => ({
  ElMessage: {
    success: mocks.success,
    warning: mocks.warning,
    error: mocks.error,
  },
}))

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((resolvePromise) => {
    resolve = resolvePromise
  })
  return { promise, resolve }
}

function createDeps() {
  return {
    debugSessionScope: ref('owner-42:workflow-1'),
    workflowId: ref('workflow-1'),
    studio: ref(null),
    workflowMeta: {
      name: '',
      keySlug: '',
      workflowKind: 'GENERAL',
      description: '',
      defaultModelInstanceId: '',
    },
    graphSpecJson: ref('{}'),
    canvasJson: ref('{}'),
    nodes: ref([]),
    debugOpen: ref(false),
    propertyPanelCollapsed: ref(false),
    debugLoading: ref(false),
    nodeDebugLoading: ref(false),
    traceReplayLoading: ref(false),
    recentRunsLoading: ref(false),
    debugNodeId: ref(''),
    debugMessage: ref(''),
    nodeDebugMessage: ref(''),
    nodeDebugStateJson: ref('{}'),
    debugInputParams: {},
    currentTraceId: ref(''),
    traceNodes: ref([]),
    runOpsDetail: ref(null),
    replayTraceInput: ref(''),
    selectedRecentTraceId: ref(''),
    recentRuns: ref([]),
    selectedDebugStepIndex: ref(null),
    currentDebugNodeId: ref(''),
    debugPlaybackToken: ref(0),
    nodeDebugResult: ref(null),
    debugRunResult: ref(null),
    debugSession: ref(null),
    debugResult: ref(null),
    selectedNodeId: ref(null),
    selectedEdgeId: ref(null),
    selectedNode: computed(() => null),
    nodeDebugStateText: ref('{}'),
    debugInputFields: computed(() => []),
    resolveAiModelInstanceId: () => '',
    syncJsonFromCanvas: vi.fn(),
    canvasSnapshot: () => ({ nodes: [], edges: [] }),
    refreshWorkflowNodeClasses: vi.fn(),
    applyDebugSession: vi.fn(),
    forgetDebugSession: vi.fn(),
    loadStoredDebugSession: vi.fn(async () => undefined),
    getViewport: () => ({ zoom: 1 }),
    setCenter: vi.fn(),
    nextTick: vi.fn(async (fn?: () => void) => {
      fn?.()
    }),
    findNodePosition: () => null,
    parseOptionalObject: () => undefined,
  } as unknown as UseWorkflowStudioDebugRunDeps
}

function mountDebugRun(deps: UseWorkflowStudioDebugRunDeps) {
  let debugRun!: ReturnType<typeof useWorkflowStudioDebugRun>
  const app = createApp(defineComponent({
    setup() {
      debugRun = useWorkflowStudioDebugRun(deps)
      return () => null
    },
  }))
  app.mount(document.createElement('div'))
  return { debugRun, unmount: () => app.unmount() }
}

function changeAccount(deps: UseWorkflowStudioDebugRunDeps, scope = 'owner-43:workflow-1') {
  (deps.debugSessionScope as Ref<string>).value = scope
}

describe('Workflow Studio initial input presentation', () => {
  it('uses the chat composer for one conversational text field', () => {
    const field = { name: 'message', type: 'string' as const, required: true }
    expect(resolveWorkflowInitialChatField([field])).toBe(field)
  })

  it('keeps structured input fields in the form renderer', () => {
    expect(resolveWorkflowInitialChatField([
      { name: 'teamId', type: 'string', required: true },
    ])).toBeNull()
    expect(resolveWorkflowInitialChatField([
      { name: 'message', type: 'string', required: true },
      { name: 'page', type: 'integer' },
    ])).toBeNull()
  })
})

describe('useWorkflowStudioDebugRun trace replay', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('ignores an older trace response after a newer replay starts', async () => {
    const traceA = deferred<{ data: { nodes: { id: string }[] } }>()
    const runA = deferred<{ data: { traceId: string } }>()
    const traceB = deferred<{ data: { nodes: { id: string }[] } }>()
    const runB = deferred<{ data: { traceId: string } }>()
    mocks.getTraceDetail.mockImplementation((traceId: string) => (
      traceId === 'trace-a' ? traceA.promise : traceB.promise
    ))
    mocks.getRunOpsDetail.mockImplementation((traceId: string) => (
      traceId === 'trace-a' ? runA.promise : runB.promise
    ))
    const deps = createDeps()
    const mounted = mountDebugRun(deps)

    const replayA = mounted.debugRun.handleLoadTraceReplay('trace-a')
    const replayB = mounted.debugRun.handleLoadTraceReplay('trace-b')
    traceA.resolve({ data: { nodes: [{ id: 'node-a' }] } })
    runA.resolve({ data: { traceId: 'trace-a' } })
    await replayA

    expect(deps.currentTraceId.value).toBe('trace-b')
    expect(deps.traceNodes.value).toEqual([])
    expect(deps.runOpsDetail.value).toBeNull()
    expect(deps.traceReplayLoading.value).toBe(true)
    expect(mocks.success).not.toHaveBeenCalled()
    expect(mocks.warning).not.toHaveBeenCalled()

    traceB.resolve({ data: { nodes: [{ id: 'node-b' }] } })
    runB.resolve({ data: { traceId: 'trace-b' } })
    await replayB

    expect(deps.traceNodes.value).toEqual([{ id: 'node-b' }])
    expect(deps.runOpsDetail.value).toEqual({ traceId: 'trace-b' })
    expect(deps.traceReplayLoading.value).toBe(false)
    expect(mocks.success).toHaveBeenCalledTimes(1)
    mounted.unmount()
  })

  it('does not restore trace data after replay is cleared', async () => {
    const trace = deferred<{ data: { nodes: { id: string }[] } }>()
    const run = deferred<{ data: { traceId: string } }>()
    mocks.getTraceDetail.mockReturnValue(trace.promise)
    mocks.getRunOpsDetail.mockReturnValue(run.promise)
    const deps = createDeps()
    const mounted = mountDebugRun(deps)

    const replay = mounted.debugRun.handleLoadTraceReplay('trace-a')
    mounted.debugRun.clearTraceReplay()
    trace.resolve({ data: { nodes: [{ id: 'node-a' }] } })
    run.resolve({ data: { traceId: 'trace-a' } })
    await replay

    expect(deps.currentTraceId.value).toBe('')
    expect(deps.traceNodes.value).toEqual([])
    expect(deps.runOpsDetail.value).toBeNull()
    expect(deps.traceReplayLoading.value).toBe(false)
    expect(mocks.success).not.toHaveBeenCalled()
    expect(mocks.warning).not.toHaveBeenCalled()
    mounted.unmount()
  })
})

describe('useWorkflowStudioDebugRun account changes during async work', () => {
  beforeEach(() => vi.clearAllMocks())
  afterEach(() => vi.unstubAllGlobals())

  it('does not send old input under the new account after awaiting local session clearing', async () => {
    const fetch = vi.fn()
    vi.stubGlobal('fetch', fetch)
    const deps = createDeps()
    deps.debugMessage.value = 'alice-private-input'
    const mounted = mountDebugRun(deps)
    const sending = mounted.debugRun.handleRunWorkingCopyDebug()
    changeAccount(deps)
    await sending
    expect(fetch).not.toHaveBeenCalled()
    expect(mocks.createWorkflowDebugSession).not.toHaveBeenCalled()
    expect(deps.debugLoading.value).toBe(false)
    expect(mounted.debugRun.debugConversationSnapshot.value.messages).toEqual([])
    mounted.unmount()
  })

  it('disposes the old controller and ignores its late restore after a new account restores', async () => {
    const alice = deferred<unknown>()
    mocks.getWorkflowDebugSession.mockReturnValueOnce(alice.promise).mockResolvedValueOnce({ data: {
      sessionId: 'bob-session', status: 'COMPLETED', success: true, traceId: 'bob-trace', messages: [], steps: [],
    } })
    const deps = createDeps()
    deps.debugSession.value = { sessionId: 'alice-session' } as never
    const mounted = mountDebugRun(deps)
    const oldRestore = mounted.debugRun.restoreDebugConversation()
    changeAccount(deps)
    deps.debugSession.value = { sessionId: 'bob-session' } as never
    await mounted.debugRun.restoreDebugConversation()
    alice.resolve({ data: { sessionId: 'alice-session', status: 'SUSPENDED', traceId: 'alice-private-trace', messages: [], steps: [] } })
    await oldRestore
    expect(mocks.getWorkflowDebugSession.mock.calls.map(call => call[0])).toEqual(['alice-session', 'bob-session'])
    expect(deps.applyDebugSession).toHaveBeenCalledTimes(1)
    expect(deps.applyDebugSession).toHaveBeenCalledWith(expect.objectContaining({ sessionId: 'bob-session' }), 'owner-43:workflow-1')
    expect(deps.currentTraceId.value).toBe('bob-trace')
    expect(mounted.debugRun.debugConversationSnapshot.value.sessionId).toBe('bob-session')
    mounted.unmount()
  })

  it('does not start a restore or clear a new loading state after an old storage query finishes', async () => {
    const stored = deferred<void>()
    const deps = createDeps()
    vi.mocked(deps.loadStoredDebugSession).mockReturnValue(stored.promise)
    const mounted = mountDebugRun(deps)
    const restoring = mounted.debugRun.handleRestoreDebugSession()
    changeAccount(deps)
    deps.debugLoading.value = true
    deps.debugSession.value = { sessionId: 'bob-session' } as never
    stored.resolve()
    await restoring
    expect(mocks.getWorkflowDebugSession).not.toHaveBeenCalled()
    expect(deps.debugLoading.value).toBe(true)
    expect(mocks.error).not.toHaveBeenCalled()
    expect(mocks.warning).not.toHaveBeenCalled()
    mounted.unmount()
  })

  it('does not execute a published graph after the account changes while versions load', async () => {
    const versions = deferred<unknown>()
    mocks.listWorkflowVersions.mockReturnValue(versions.promise)
    const deps = createDeps()
    deps.debugMessage.value = 'alice-private-input'
    const mounted = mountDebugRun(deps)
    const running = mounted.debugRun.handleRunPublishedDebug()
    changeAccount(deps)
    versions.resolve({ data: [{ id: 7, version: 'v1', status: 'ACTIVE', graphSpecSnapshotJson: '{}' }] })
    await running
    expect(mocks.debugWorkflowRun).not.toHaveBeenCalled()
    expect(deps.debugResult.value).toBeNull()
    mounted.unmount()
  })

  it('ignores a late node result without clearing a new account node request', async () => {
    const node = deferred<unknown>()
    mocks.debugWorkflowNode.mockReturnValue(node.promise)
    const deps = createDeps()
    deps.selectedNode = computed(() => ({ id: 'alice-node' }) as never)
    const mounted = mountDebugRun(deps)
    const running = mounted.debugRun.handleRunNodeDebug()
    expect(mocks.debugWorkflowNode).toHaveBeenCalledTimes(1)
    changeAccount(deps)
    deps.nodeDebugLoading.value = true
    node.resolve({ data: { nodeId: 'alice-node', success: true, nodeOutput: 'private' } })
    await running
    expect(deps.nodeDebugResult.value).toBeNull()
    expect(deps.nodeDebugLoading.value).toBe(true)
    expect(mocks.success).not.toHaveBeenCalled()
    mounted.unmount()
  })

  it('ignores an old recent-run list even if the same account logs back in', async () => {
    const recent = deferred<unknown>()
    mocks.getRecentRunOps.mockReturnValue(recent.promise)
    const deps = createDeps()
    const mounted = mountDebugRun(deps)
    const loading = mounted.debugRun.loadRecentStudioRuns()
    changeAccount(deps)
    changeAccount(deps, 'owner-42:workflow-1')
    deps.recentRunsLoading.value = true
    recent.resolve({ data: [{ traceId: 'old-alice-private-trace' }] })
    await loading
    expect(deps.recentRuns.value).toEqual([])
    expect(deps.recentRunsLoading.value).toBe(true)
    mounted.unmount()
  })

  it('clears sensitive debug inputs and prevents an old trace response from repopulating the panel', async () => {
    const trace = deferred<unknown>()
    const run = deferred<unknown>()
    mocks.getTraceDetail.mockReturnValue(trace.promise)
    mocks.getRunOpsDetail.mockReturnValue(run.promise)
    const deps = createDeps()
    deps.debugMessage.value = 'alice-private-input'
    deps.nodeDebugMessage.value = 'alice-private-node-input'
    deps.nodeDebugStateJson.value = '{"secret":"alice-context"}'
    deps.debugInputParams.private = 'alice-context'
    const mounted = mountDebugRun(deps)
    const replay = mounted.debugRun.handleLoadTraceReplay('alice-trace')
    changeAccount(deps, '')
    trace.resolve({ data: { nodes: [{ id: 'alice-node' }] } })
    run.resolve({ data: { traceId: 'alice-trace' } })
    await replay
    expect(deps.traceNodes.value).toEqual([])
    expect(deps.runOpsDetail.value).toBeNull()
    expect(deps.debugMessage.value).toBe('')
    expect(deps.nodeDebugMessage.value).toBe('')
    expect(deps.nodeDebugStateJson.value).toBe('{}')
    expect(deps.debugInputParams).toEqual({})
    expect(mocks.success).not.toHaveBeenCalled()
    mounted.unmount()
  })
})
