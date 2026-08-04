import { ref } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useRegistryProjectDetailData } from './useRegistryProjectDetailData'

const mocks = vi.hoisted(() => ({
  getScanProjects: vi.fn(),
  getScanProjectDetail: vi.fn(),
  listRegistryProjectInstances: vi.fn(),
  listPageRegistry: vi.fn(),
  listPageActionCatalog: vi.fn(),
}))

vi.mock('vue-router', () => ({
  useRoute: () => ({
    params: {},
  }),
}))

vi.mock('@/store/project', () => ({
  useProjectStore: () => ({
    projects: [],
  }),
}))

vi.mock('@/api/scanProject', () => ({
  getScanProjectDetail: mocks.getScanProjectDetail,
  getScanProjects: mocks.getScanProjects,
}))

vi.mock('@/api/registry', () => ({
  listRegistryProjectInstances: mocks.listRegistryProjectInstances,
}))

vi.mock('@/api/embedOps', () => ({
  listPageActionCatalog: mocks.listPageActionCatalog,
  listPageRegistry: mocks.listPageRegistry,
}))

function createData() {
  return useRegistryProjectDetailData({
    projectCode: ref('demo-project'),
    loadAiCodingAccess: vi.fn().mockResolvedValue(undefined),
  })
}

describe('useRegistryProjectDetailData', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mocks.getScanProjects.mockResolvedValue({
      data: [{
        id: 27,
        projectCode: 'demo-project',
        name: 'Demo Project',
      }],
    })
    mocks.getScanProjectDetail.mockResolvedValue({
      data: {
        id: 27,
        projectCode: 'demo-project',
        name: 'Demo Project',
      },
    })
  })

  it('keeps the real project visible and marks local metrics unavailable', async () => {
    mocks.listRegistryProjectInstances.mockRejectedValueOnce(
      new Error('instance endpoint down'),
    )
    mocks.listPageRegistry.mockRejectedValueOnce(
      new Error('page endpoint down'),
    )
    mocks.listPageActionCatalog.mockResolvedValueOnce({ data: [] })
    const data = createData()

    await data.refresh()

    expect(data.loadError.value).toBe('')
    expect(data.project.value?.projectCode).toBe('demo-project')
    expect(data.instancesLoadError.value).toContain('instance endpoint down')
    expect(data.pageCatalogLoadError.value).toContain('page endpoint down')

    mocks.listRegistryProjectInstances.mockResolvedValueOnce({ data: [] })
    mocks.listPageRegistry.mockResolvedValueOnce({ data: [] })
    mocks.listPageActionCatalog.mockResolvedValueOnce({ data: [] })
    await Promise.all([data.loadInstances(), data.loadPageCatalog()])
    expect(data.instancesLoadError.value).toBe('')
    expect(data.pageCatalogLoadError.value).toBe('')
  })

  it('uses a blocking error instead of an empty project when the project catalog fails', async () => {
    mocks.getScanProjects.mockRejectedValueOnce(new Error('catalog down'))
    const data = createData()

    await data.refresh()

    expect(data.projectMissing.value).toBe(false)
    expect(data.project.value).toBeNull()
    expect(data.loadError.value).toContain('catalog down')
  })
})
