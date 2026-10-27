import { defineComponent, ref } from 'vue'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useRegistryProjectDetailData } from './useRegistryProjectDetailData'
import { useProjectStore } from '@/store/project'

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

const wrappers: VueWrapper[] = []

function createData() {
  let data!: ReturnType<typeof useRegistryProjectDetailData>
  wrappers.push(mount(defineComponent({
    setup() {
      data = useRegistryProjectDetailData({
        projectCode: ref('demo-project'),
        loadAiCodingAccess: vi.fn().mockResolvedValue(undefined),
      })
      return () => null
    },
  })))
  return data
}

describe('useRegistryProjectDetailData', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    mocks.listRegistryProjectInstances.mockResolvedValue({ data: [] })
    mocks.listPageRegistry.mockResolvedValue({ data: [] })
    mocks.listPageActionCatalog.mockResolvedValue({ data: [] })
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

  afterEach(() => {
    wrappers.splice(0).forEach((wrapper) => wrapper.unmount())
    vi.restoreAllMocks()
    vi.useRealTimers()
  })

  it('refreshes instance liveness while open and stops polling after leaving', async () => {
    vi.useFakeTimers()
    const instance = { id: 1, instanceId: 'worker', status: 'ONLINE' }
    mocks.listRegistryProjectInstances.mockResolvedValueOnce({ data: [instance] })
    const data = createData()
    await data.refresh()
    expect(data.instances.value[0]?.status).toBe('ONLINE')

    mocks.listRegistryProjectInstances.mockResolvedValue({
      data: [{ ...instance, status: 'STALE' }],
    })
    await vi.advanceTimersByTimeAsync(30_000)

    expect(data.instances.value[0]?.status).toBe('STALE')
    expect(data.offlineInstanceCount.value).toBe(1)
    expect(mocks.listRegistryProjectInstances).toHaveBeenCalledTimes(2)
    wrappers.splice(0).forEach((wrapper) => wrapper.unmount())
    await vi.advanceTimersByTimeAsync(60_000)
    expect(mocks.listRegistryProjectInstances).toHaveBeenCalledTimes(2)
  })

  it('pauses hidden pages, refreshes on return, and avoids overlapping background requests', async () => {
    vi.useFakeTimers()
    const visibility = vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('hidden')
    const data = createData()
    await data.refresh()
    await vi.advanceTimersByTimeAsync(60_000)
    expect(mocks.listRegistryProjectInstances).toHaveBeenCalledTimes(1)

    let resolveInstances!: (value: { data: unknown[] }) => void
    mocks.listRegistryProjectInstances.mockImplementationOnce(() => new Promise((resolve) => {
      resolveInstances = resolve
    }))
    visibility.mockReturnValue('visible')
    document.dispatchEvent(new Event('visibilitychange'))
    expect(data.loadingInstances.value).toBe(false)
    await vi.advanceTimersByTimeAsync(60_000)
    expect(mocks.listRegistryProjectInstances).toHaveBeenCalledTimes(2)

    resolveInstances({ data: [] })
    await flushPromises()
    await vi.advanceTimersByTimeAsync(30_000)
    expect(mocks.listRegistryProjectInstances).toHaveBeenCalledTimes(3)
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
    expect(data.loadError.value).toContain('项目列表加载失败')
  })

  it('shares the sidebar catalog request with project detail loading', async () => {
    const project = {
      id: 27,
      projectCode: 'demo-project',
      name: 'Demo Project',
    }
    let resolveCatalog!: (value: { data: typeof project[] }) => void
    mocks.getScanProjects.mockImplementationOnce(() => new Promise((resolve) => {
      resolveCatalog = resolve
    }))
    mocks.listRegistryProjectInstances.mockResolvedValue({ data: [] })
    mocks.listPageRegistry.mockResolvedValue({ data: [] })
    mocks.listPageActionCatalog.mockResolvedValue({ data: [] })

    const projectStore = useProjectStore()
    const sidebarRequest = projectStore.fetchProjects()
    const data = createData()
    const detailRequest = data.refresh()

    expect(mocks.getScanProjects).toHaveBeenCalledTimes(1)

    resolveCatalog({ data: [project] })
    await Promise.all([sidebarRequest, detailRequest])

    expect(projectStore.status).toBe('ready')
    expect(projectStore.projects).toEqual([project])
    expect(data.project.value?.projectCode).toBe('demo-project')
  })
})
