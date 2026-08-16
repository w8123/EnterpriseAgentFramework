import { computed, createApp, defineComponent, ref } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'
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
