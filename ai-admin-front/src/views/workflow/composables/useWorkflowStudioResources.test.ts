import { ref } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useWorkflowStudioResources } from './useWorkflowStudioResources'

const mocks = vi.hoisted(() => ({
  getApiGraphParamHints: vi.fn(),
  getKnowledgeList: vi.fn(),
  getModelInstances: vi.fn(),
  listAllTools: vi.fn(),
  getWorkflowGraphNodeTypes: vi.fn(),
  listWorkflowCredentials: vi.fn(),
}))

vi.mock('@/api/apiGraph', () => ({
  getApiGraphParamHints: mocks.getApiGraphParamHints,
}))
vi.mock('@/api/knowledge', () => ({
  getKnowledgeList: mocks.getKnowledgeList,
}))
vi.mock('@/api/model', () => ({
  getModelInstances: mocks.getModelInstances,
}))
vi.mock('@/api/tool', () => ({
  listAllTools: mocks.listAllTools,
}))
vi.mock('@/api/workflow', () => ({
  getWorkflowGraphNodeTypes: mocks.getWorkflowGraphNodeTypes,
}))
vi.mock('@/api/workflowCredential', () => ({
  listWorkflowCredentials: mocks.listWorkflowCredentials,
}))

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((resolvePromise) => {
    resolve = resolvePromise
  })
  return { promise, resolve }
}

function tool(name: string, projectId: number) {
  return {
    name,
    title: name,
    description: '',
    parameters: [],
    source: 'code' as const,
    projectId,
    enabled: true,
  }
}

describe('useWorkflowStudioResources parameter hints', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mocks.listAllTools.mockResolvedValue([
      tool('tool-a', 1),
      tool('tool-b', 2),
    ])
  })

  it('ignores hints returned for a previously selected tool', async () => {
    const requestA = deferred<{ data: { targetPath: string }[] }>()
    const requestB = deferred<{ data: { targetPath: string }[] }>()
    mocks.getApiGraphParamHints.mockImplementation((projectId: number) => (
      projectId === 1 ? requestA.promise : requestB.promise
    ))
    const selectedToolName = ref('tool-a')
    const resources = useWorkflowStudioResources({
      studio: ref(null),
      aiModelInstanceId: ref(''),
      selectedToolName,
    })
    await resources.loadToolOptions()

    const refreshA = resources.refreshParamSourceHints()
    selectedToolName.value = 'tool-b'
    const refreshB = resources.refreshParamSourceHints()
    requestA.resolve({ data: [{ targetPath: 'from-a' }] })
    await refreshA

    expect(resources.paramSourceHints.value).toEqual([])

    requestB.resolve({ data: [{ targetPath: 'from-b' }] })
    await refreshB
    expect(resources.paramSourceHints.value).toEqual([
      { targetPath: 'from-b' },
    ])
  })

  it('keeps hints empty when tool selection is cleared during a request', async () => {
    const request = deferred<{ data: { targetPath: string }[] }>()
    mocks.getApiGraphParamHints.mockReturnValue(request.promise)
    const selectedToolName = ref('tool-a')
    const resources = useWorkflowStudioResources({
      studio: ref(null),
      aiModelInstanceId: ref(''),
      selectedToolName,
    })
    await resources.loadToolOptions()

    const refresh = resources.refreshParamSourceHints()
    selectedToolName.value = ''
    await resources.refreshParamSourceHints()
    request.resolve({ data: [{ targetPath: 'from-a' }] })
    await refresh

    expect(resources.paramSourceHints.value).toEqual([])
  })
})
