import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useBusinessPageWorkbench } from './useBusinessPageWorkbench'

const mocks = vi.hoisted(() => ({
  getScanProjects: vi.fn(),
  listWorkbenchPages: vi.fn(),
  getPageMapSummary: vi.fn(),
  getPageAccessCenterOverview: vi.fn(),
  listPageAnalysisFindings: vi.fn(),
  listPublishedPageWorkflows: vi.fn(),
  listAiCodingTasks: vi.fn(),
}))

const accessCenterBootstrap = {
  schema: 'reachai.page-access-center.overview.v1',
  projectCode: 'demo-project',
  summary: {
    discoveredCount: 1,
    waitingCount: 1,
    activeCount: 0,
    awaitingAcceptanceCount: 0,
    completedCount: 0,
    unavailableCount: 0,
  },
  pages: [],
  activities: [],
  onlineCapabilities: [],
  runtimeAvailable: true,
  runtimeMessage: '',
  generatedAt: '2026-08-01T00:00:00',
  catalogPages: [{
    id: 81,
    projectCode: 'demo-project',
    pageKey: 'team-list',
    name: '班组列表',
  }],
  pageMap: { scanned: true },
  findings: [],
  tasks: [],
}

vi.mock('vue-router', () => ({
  useRoute: () => ({
    params: {
      projectCode: 'demo-project',
    },
  }),
}))

vi.mock('@/api/scanProject', () => ({
  getScanProjects: mocks.getScanProjects,
}))

vi.mock('@/api/pageWorkbench', () => ({
  createWorkbenchPage: vi.fn(),
  deliverWorkflowEngineeringTask: vi.fn(),
  getWorkbenchPageReadiness: vi.fn(),
  getPageAccessCenterOverview: mocks.getPageAccessCenterOverview,
  getPageMapSummary: mocks.getPageMapSummary,
  listPageAnalysisFindings: mocks.listPageAnalysisFindings,
  listPublishedPageWorkflows: mocks.listPublishedPageWorkflows,
  listWorkbenchPages: mocks.listWorkbenchPages,
  updatePageAnalysisFindingStatus: vi.fn(),
}))

vi.mock('@/api/aiCodingTasks', () => ({
  answerAiCodingQuestion: vi.fn(),
  cancelAiCodingTask: vi.fn(),
  createAiCodingTask: vi.fn(),
  finishAiCodingAcceptance: vi.fn(),
  getAiCodingTask: vi.fn(),
  issueAiCodingHandoff: vi.fn(),
  listAiCodingTasks: mocks.listAiCodingTasks,
  verifyAiCodingAcceptanceReadiness: vi.fn(),
}))

vi.mock('element-plus', () => ({
  ElMessage: {
    error: vi.fn(),
    success: vi.fn(),
    warning: vi.fn(),
  },
}))

describe('useBusinessPageWorkbench', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mocks.getScanProjects.mockResolvedValue({
      data: [{
        id: 27,
        projectCode: 'demo-project',
        name: 'Demo Project',
      }],
    })
    mocks.listWorkbenchPages.mockResolvedValue({
      data: [{
        id: 81,
        projectCode: 'demo-project',
        pageKey: 'team-list',
        name: '班组列表',
      }],
    })
    mocks.getPageMapSummary.mockResolvedValue({
      data: {
        scanned: true,
      },
    })
    mocks.listPageAnalysisFindings.mockResolvedValue({ data: [] })
    mocks.getPageAccessCenterOverview.mockResolvedValue({
      data: accessCenterBootstrap,
    })
    mocks.listPublishedPageWorkflows.mockResolvedValue({ data: [] })
    mocks.listAiCodingTasks.mockResolvedValue({ data: [] })
  })

  it('loads the access-center bootstrap without duplicate aggregate requests', async () => {
    const workbench = useBusinessPageWorkbench()

    await workbench.loadAll()

    expect(workbench.loading.value).toBe(false)
    expect(workbench.pages.value.map((page) => page.pageKey)).toEqual(['team-list'])
    expect(workbench.pageMap.value.scanned).toBe(true)
    expect(mocks.listWorkbenchPages).not.toHaveBeenCalled()
    expect(mocks.getPageMapSummary).not.toHaveBeenCalled()
    expect(mocks.listPageAnalysisFindings).not.toHaveBeenCalled()
    expect(mocks.listAiCodingTasks).not.toHaveBeenCalled()
  })

  it('keeps the page map readable but blocks new tasks while task state is unknown', async () => {
    mocks.getPageAccessCenterOverview.mockResolvedValueOnce({
      data: {
        ...accessCenterBootstrap,
        catalogPages: undefined,
        pageMap: undefined,
        findings: undefined,
        tasks: undefined,
      },
    })
    mocks.listAiCodingTasks.mockRejectedValueOnce(new Error('task endpoint down'))
    const workbench = useBusinessPageWorkbench()

    await workbench.loadAll()

    expect(workbench.loadError.value).toBe('')
    expect(workbench.pages.value.map((page) => page.pageKey)).toEqual([
      'team-list',
    ])
    expect(workbench.tasksLoadError.value).toContain('task endpoint down')
    await expect(workbench.createTask({
      taskKind: 'PAGE_MAP_SCAN',
      executorProvider: 'CODEX',
      title: '扫描',
      objective: '建立页面地图',
    })).rejects.toThrow('任务状态尚未加载')

    mocks.listAiCodingTasks.mockResolvedValueOnce({ data: [] })
    await workbench.refreshTasks()
    expect(workbench.tasksLoadError.value).toBe('')
  })
})
