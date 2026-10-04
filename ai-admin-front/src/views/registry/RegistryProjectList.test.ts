import { flushPromises, shallowMount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import RegistryProjectList from './RegistryProjectList.vue'
import type { ScanProject } from '@/types/scanProject'

const mocks = vi.hoisted(() => ({
  getScanProjects: vi.fn(),
  createScanProject: vi.fn(),
  issueRegistryEnrollment: vi.fn(),
  push: vi.fn(),
  projectStore: undefined as any,
}))

vi.mock('vue-router', () => ({
  useRouter: () => ({ push: mocks.push }),
}))

vi.mock('@/api/scanProject', () => ({
  getScanProjects: mocks.getScanProjects,
  createScanProject: mocks.createScanProject,
}))

vi.mock('@/api/registry', () => ({
  issueRegistryEnrollment: mocks.issueRegistryEnrollment,
}))

vi.mock('element-plus', () => ({
  ElMessage: {
    error: vi.fn(),
    success: vi.fn(),
    warning: vi.fn(),
  },
}))

vi.mock('@/store/project', () => ({
  useProjectStore: () => mocks.projectStore,
}))

vi.mock('@/composables/useTheme', () => ({
  useTheme: () => ({ theme: 'light' }),
}))

vi.mock('@/composables/useCollapsiblePageHeader', () => ({
  useCollapsiblePageHeader: () => ({
    collapsed: false,
    refreshScrollTargets: vi.fn(),
  }),
}))

function createProject(id: number, projectCode: string): ScanProject {
  return {
    id,
    name: `项目 ${id}`,
    projectCode,
    baseUrl: '',
    contextPath: '',
    scanPath: '',
    scanType: 'auto',
    toolCount: 0,
    status: 'created',
  }
}

function mountProjectList() {
  return shallowMount(RegistryProjectList, {
    global: {
      directives: {
        loading: () => {},
      },
      stubs: {
        AppDialog: true,
        CollapsibleHeaderRegion: true,
        MetricIconBg: true,
        PageHeader: true,
        ElAlert: true,
        ElAvatar: true,
        ElButton: true,
        ElCard: true,
        ElCol: true,
        ElForm: true,
        ElFormItem: true,
        ElInput: true,
        ElOption: true,
        ElPagination: true,
        ElRadio: true,
        ElRadioGroup: true,
        ElRow: true,
        ElSelect: true,
        ElTable: true,
        ElTableColumn: true,
        ElTabs: true,
        ElTabPane: true,
        ElTag: true,
      },
    },
  })
}

describe('RegistryProjectList project catalog boundary', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mocks.projectStore = {
      projects: [createProject(1, 'project-a'), createProject(2, 'project-b')],
      currentProjectId: 1,
    }
    mocks.getScanProjects.mockResolvedValue({ data: [createProject(1, 'project-a')] })
  })

  it('keeps server-filtered page results local and does not overwrite the global catalog', async () => {
    const wrapper = mountProjectList()
    await flushPromises()

    const loadProjects = (wrapper.vm as unknown as {
      loadProjects: (query: { keyword?: string }) => Promise<void>
    }).loadProjects
    await loadProjects({ keyword: 'project-a' })

    expect(mocks.getScanProjects).toHaveBeenLastCalledWith({ keyword: 'project-a' })
    expect(mocks.projectStore.projects).toHaveLength(2)
    expect(mocks.projectStore.projects.map((project: ScanProject) => project.projectCode)).toEqual([
      'project-a',
      'project-b',
    ])
    wrapper.unmount()
  })

  it('keeps the global catalog and current selection when a search returns no rows', async () => {
    const wrapper = mountProjectList()
    await flushPromises()
    const catalogBeforeSearch = mocks.projectStore.projects
    const currentProjectIdBeforeSearch = mocks.projectStore.currentProjectId
    mocks.getScanProjects.mockResolvedValueOnce({ data: [] })

    const loadProjects = (wrapper.vm as unknown as {
      loadProjects: (query: { keyword?: string }) => Promise<void>
    }).loadProjects
    await loadProjects({ keyword: 'missing' })

    expect(mocks.projectStore.projects).toBe(catalogBeforeSearch)
    expect(mocks.projectStore.currentProjectId).toBe(currentProjectIdBeforeSearch)
    wrapper.unmount()
  })

  it('keeps the global catalog and current selection when the page request fails', async () => {
    const wrapper = mountProjectList()
    await flushPromises()
    const catalogBeforeFailure = mocks.projectStore.projects
    const currentProjectIdBeforeFailure = mocks.projectStore.currentProjectId
    mocks.getScanProjects.mockRejectedValueOnce(new Error('project page unavailable'))

    const loadProjects = (wrapper.vm as unknown as {
      loadProjects: (query: { keyword?: string }) => Promise<void>
    }).loadProjects
    await loadProjects({ keyword: 'project-a' })

    expect(mocks.projectStore.projects).toBe(catalogBeforeFailure)
    expect(mocks.projectStore.currentProjectId).toBe(currentProjectIdBeforeFailure)
    wrapper.unmount()
  })
})
