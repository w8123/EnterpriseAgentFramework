import { flushPromises, shallowMount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import RegistryProjectList from './RegistryProjectList.vue'
import type { ScanProject } from '@/types/scanProject'
import type { RegistryEnrollmentToken, RegistryProjectRegisterRequest } from '@/types/registry'

const mocks = vi.hoisted(() => ({
  getScanProjects: vi.fn(),
  createScanProject: vi.fn(),
  issueRegistryEnrollment: vi.fn(),
  push: vi.fn(),
  copy: vi.fn(),
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
        AppDialog: {
          name: 'AppDialog',
          props: ['modelValue'],
          emits: ['close', 'update:modelValue'],
          template: '<section v-if="modelValue"><slot /><slot name="footer" /></section>',
        },
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

interface EnrollmentState {
  sdkForm: RegistryProjectRegisterRequest
  saving: boolean
  accessDialogVisible: boolean
  enrollmentDialogVisible: boolean
  issuedEnrollment: RegistryEnrollmentToken | null
  issuedProject: RegistryProjectRegisterRequest | null
  enrollmentTokenVisible: boolean
  enrollmentSnippet: string
  visibleEnrollmentSnippet: string
  enrollmentError: string
  saveSdkProject: () => Promise<void>
  copyEnrollmentSnippet: () => Promise<void>
}

describe('RegistryProjectList SDK enrollment handoff', () => {
  const enrollment: RegistryEnrollmentToken = {
    enrollmentToken: 'test-only-enrollment-token',
    projectCode: 'bzjs21',
    expiresAt: '2026-10-12T08:00:00Z',
    ttlHours: 72,
  }

  beforeEach(() => {
    vi.clearAllMocks()
    mocks.getScanProjects.mockResolvedValue({ data: [] })
    mocks.issueRegistryEnrollment.mockResolvedValue({ data: enrollment })
    mocks.copy.mockResolvedValue(undefined)
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: { writeText: mocks.copy },
    })
  })

  function prepareForm() {
    const wrapper = mountProjectList()
    const state = wrapper.vm as unknown as EnrollmentState
    Object.assign(state.sdkForm, {
      projectCode: ' bzjs21 ',
      name: ' 班组建设21 ',
      environment: ' test ',
      owner: ' 项目负责人 ',
      visibility: 'PROJECT',
      baseUrl: ' http://127.0.0.1:8080 ',
      contextPath: ' /teams ',
    })
    state.accessDialogVisible = true
    return { wrapper, state }
  }

  it('hands every project field to the Starter without creating a project before registration', async () => {
    const { wrapper, state } = prepareForm()
    await state.saveSdkProject()

    expect(mocks.issueRegistryEnrollment).toHaveBeenCalledWith('bzjs21')
    expect(mocks.createScanProject).not.toHaveBeenCalled()
    expect(state.enrollmentSnippet).toContain('url: ${REACHAI_REGISTRY_URL:http://localhost:18603}')
    for (const line of [
      'code: "bzjs21"',
      'name: "班组建设21"',
      'environment: "test"',
      'owner: "项目负责人"',
      'visibility: "PROJECT"',
      'base-url: "http://127.0.0.1:8080"',
      'context-path: "/teams"',
      'scan-mode: ANNOTATED_ONLY',
    ]) expect(state.enrollmentSnippet).toContain(line)
    expect(state.accessDialogVisible).toBe(false)
    expect(state.enrollmentDialogVisible).toBe(true)
    wrapper.unmount()
  })

  it('keeps the submitted identity if the form changes while token issuance is pending', async () => {
    let finish: (result: { data: RegistryEnrollmentToken }) => void = () => {}
    mocks.issueRegistryEnrollment.mockReturnValueOnce(new Promise((resolve) => { finish = resolve }))
    const { wrapper, state } = prepareForm()
    const pending = state.saveSdkProject()
    state.sdkForm.name = '另一个项目'
    state.sdkForm.baseUrl = 'http://localhost:9999'
    finish({ data: enrollment })
    await pending

    expect(state.enrollmentSnippet).toContain('name: "班组建设21"')
    expect(state.enrollmentSnippet).toContain('base-url: "http://127.0.0.1:8080"')
    expect(state.enrollmentSnippet).not.toContain('另一个项目')
    wrapper.unmount()
  })

  it('quotes colons, quotes and line breaks in user-entered metadata as YAML strings', async () => {
    const { wrapper, state } = prepareForm()
    state.sdkForm.name = '班组: "二十一"\n测试'
    await state.saveSdkProject()

    expect(state.enrollmentSnippet).toContain('name: "班组: \\"二十一\\"\\n测试"')
    wrapper.unmount()
  })

  it('masks the token until requested and copies the complete unmasked handoff only on action', async () => {
    const { wrapper, state } = prepareForm()
    await state.saveSdkProject()

    expect(mocks.copy).not.toHaveBeenCalled()
    expect(state.visibleEnrollmentSnippet).toContain('enrollment-token: "******"')
    expect(state.visibleEnrollmentSnippet).not.toContain(enrollment.enrollmentToken)
    state.enrollmentTokenVisible = true
    expect(state.visibleEnrollmentSnippet).toContain(enrollment.enrollmentToken)
    state.enrollmentTokenVisible = false
    await state.copyEnrollmentSnippet()
    expect(mocks.copy).toHaveBeenCalledWith(state.enrollmentSnippet)
    expect(state.visibleEnrollmentSnippet).not.toContain(enrollment.enrollmentToken)
    wrapper.unmount()
  })

  it('clears the token and submitted identity as soon as its dialog closes', async () => {
    const { wrapper, state } = prepareForm()
    await state.saveSdkProject()
    state.enrollmentTokenVisible = true
    wrapper.findAllComponents({ name: 'AppDialog' })[1].vm.$emit('close')

    expect(state.issuedEnrollment).toBeNull()
    expect(state.issuedProject).toBeNull()
    expect(state.enrollmentSnippet).toBe('')
    expect(state.enrollmentTokenVisible).toBe(false)
    wrapper.unmount()
  })

  it('blocks duplicate issuance while pending and preserves the form for a failed request', async () => {
    let fail: (reason: Error) => void = () => {}
    mocks.issueRegistryEnrollment.mockReturnValueOnce(new Promise((_, reject) => { fail = reject }))
    const { wrapper, state } = prepareForm()
    const pending = state.saveSdkProject()
    await state.saveSdkProject()
    expect(mocks.issueRegistryEnrollment).toHaveBeenCalledTimes(1)
    expect(state.saving).toBe(true)
    fail(new Error('request failed'))
    await pending

    expect(state.saving).toBe(false)
    expect(state.accessDialogVisible).toBe(true)
    expect(state.enrollmentDialogVisible).toBe(false)
    expect(state.enrollmentError).toContain('填写的信息已保留')
    expect(state.sdkForm.name).toBe(' 班组建设21 ')
    expect(state.issuedEnrollment).toBeNull()
    wrapper.unmount()
  })
})
