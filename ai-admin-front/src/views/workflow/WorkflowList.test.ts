import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { defineComponent, h, nextTick } from 'vue'
import {
  createMemoryHistory,
  createRouter,
  RouterView,
  type RouteRecordRaw,
} from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ElMessage, ElMessageBox } from 'element-plus'
import WorkflowList from './WorkflowList.vue'
import AppSidebar from '@/components/common/AppSidebar.vue'
import {
  acknowledgePlatformExplorationNotice,
  markPlatformSessionAnonymous,
  markPlatformSessionAuthenticated,
  type PlatformSessionView,
} from '@/auth/platformSession'
import {
  PLATFORM_PERMISSION_AGENT_READ,
  PLATFORM_PERMISSION_WORKFLOW_READ,
  PLATFORM_PERMISSION_WORKFLOW_WRITE,
} from '@/auth/platformAccess'
import { providePageProjectScope } from '@/composables/usePageProjectScope'
import { useAppStore } from '@/store/app'
import { useProjectStore } from '@/store/project'
import type { ScanProject } from '@/types/scanProject'
import type { WorkflowWorkingCopy } from '@/types/workflow'

const mocks = vi.hoisted(() => ({
  getScanProjects: vi.fn(),
  listWorkflows: vi.fn(),
  createWorkflow: vi.fn(),
  deleteWorkflow: vi.fn(),
}))

vi.mock('@/api/scanProject', () => ({
  getScanProjects: (...args: unknown[]) => mocks.getScanProjects(...args),
}))

vi.mock('@/api/workflow', () => ({
  listWorkflows: (...args: unknown[]) => mocks.listWorkflows(...args),
  createWorkflow: (...args: unknown[]) => mocks.createWorkflow(...args),
  deleteWorkflow: (...args: unknown[]) => mocks.deleteWorkflow(...args),
}))

vi.mock('element-plus', () => ({
  ElMessage: {
    error: vi.fn(),
    success: vi.fn(),
    warning: vi.fn(),
  },
  ElMessageBox: {
    confirm: vi.fn(),
  },
}))

function createProject(id: number, projectCode?: string | null): ScanProject {
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

function createWorkflow(id: string, projectId: number | null, projectCode: string | null): WorkflowWorkingCopy {
  return {
    id,
    projectId,
    projectCode,
    keySlug: `key-${id}`,
    name: `Workflow ${id}`,
    description: `${id} description`,
    workflowKind: 'GENERAL',
    executionEngine: 'GRAPH_SPEC',
    definitionAuthority: 'USER',
    creationChannel: 'STUDIO',
    status: 'DRAFT',
    deletable: true,
  }
}

function createSession(
  sessionId: string,
  grants: PlatformSessionView['principal']['permissionGrants'],
): PlatformSessionView {
  return {
    sessionId,
    expiresAt: '2030-01-01T00:00:00.000Z',
    principal: {
      userId: 7,
      username: 'workflow-list-test',
      permissionGrants: grants,
    },
  }
}

function projectGrant(permissionCode: string, projectCode: string) {
  return { permissionCode, scopeType: 'PROJECT', scopeValue: projectCode }
}

function globalGrant(permissionCode: string) {
  return { permissionCode, scopeType: 'GLOBAL', scopeValue: '*' }
}

const namedSlotsStub = (name: string) => defineComponent({
  name,
  setup: (_props, { slots }) => () => h(
    'div',
    { class: `${name}-stub` },
    Object.values(slots).flatMap((slot) => slot?.() || []),
  ),
})

const ElButtonStub = defineComponent({
  name: 'ElButton',
  props: { loading: Boolean, disabled: Boolean },
  inheritAttrs: false,
  emits: ['click'],
  setup: (_props, { attrs, slots, emit }) => () => h(
    'button',
    { ...attrs, onClick: () => emit('click') },
    slots.default?.(),
  ),
})

const ElSelectStub = defineComponent({
  name: 'ElSelect',
  props: {
    modelValue: { type: [Number, String], default: null },
    valueOnClear: { type: [Number, String, Boolean], default: undefined },
    loading: Boolean,
    disabled: Boolean,
    placeholder: { type: String, default: '' },
  },
  emits: ['update:modelValue', 'change', 'clear'],
  setup: (props, { attrs, slots, expose, emit }) => {
    expose({
      clearSelection: () => {
        emit('update:modelValue', props.valueOnClear)
        emit('change', props.valueOnClear)
        emit('clear')
      },
    })
    return () => h(
    'div',
    { ...attrs, class: ['el-select-stub', attrs.class], 'data-value': String(props.modelValue ?? '') },
    [slots.prefix?.(), slots.default?.(), h('span', { class: 'select-placeholder' }, props.placeholder)],
    )
  },
})

const ElOptionStub = defineComponent({
  name: 'ElOption',
  props: {
    label: { type: String, default: '' },
    value: { type: [Number, String], default: null },
    disabled: Boolean,
  },
  setup: (props, { slots }) => () => h(
    'div',
    {
      class: 'el-option-stub',
      'data-label': props.label,
      'data-value': String(props.value ?? ''),
      'data-disabled': String(props.disabled),
    },
    slots.default?.() || props.label,
  ),
})

const ElInputStub = defineComponent({
  name: 'ElInput',
  props: { modelValue: { type: [String, Number], default: '' }, placeholder: { type: String, default: '' } },
  setup: (props) => () => h('input', { value: props.modelValue, placeholder: props.placeholder }),
})

const ElTableStub = defineComponent({
  name: 'ElTable',
  props: { data: { type: Array, default: () => [] } },
  setup: (props, { slots, expose }) => {
    expose({ doLayout: () => {} })
    return () => h('div', { class: 'el-table-stub' }, props.data.length
      ? slots.default?.()
      : slots.empty?.())
  },
})

const ElDialogStub = defineComponent({
  name: 'ElDialog',
  props: { modelValue: Boolean, title: { type: String, default: '' } },
  setup: (props, { slots }) => () => props.modelValue
    ? h('div', { class: 'el-dialog-stub', 'aria-label': props.title }, [slots.default?.(), slots.footer?.()])
    : null,
})

const passthroughStub = (name: string, className = name) => defineComponent({
  name,
  setup: (_props, { slots }) => () => h('div', { class: className }, slots.default?.()),
})

const wrappers: VueWrapper[] = []
let sessionSequence = 0

function createWorkflowRouter() {
  const Probe = defineComponent({ setup: () => () => h('div', 'probe') })
  const routes: RouteRecordRaw[] = [
    {
      path: '/workflows',
      name: 'WorkflowList',
      component: WorkflowList,
      meta: {
        projectScope: {
          permission: PLATFORM_PERMISSION_WORKFLOW_READ,
          resourceLabel: 'Workflow',
          requiresProjectCode: true,
        },
      },
    },
    {
      path: '/agent',
      name: 'AgentList',
      component: Probe,
      meta: {
        projectScope: {
          permission: PLATFORM_PERMISSION_AGENT_READ,
          resourceLabel: 'Agent',
          requiresProjectCode: false,
        },
      },
    },
    { path: '/registry/projects/:projectCode', name: 'RegistryProjectDetail', component: Probe },
    { path: '/workflows/:workflowId/studio', name: 'WorkflowStudio', component: Probe },
    { path: '/workflows/:workflowId/versions', name: 'WorkflowVersions', component: Probe },
  ]
  return createRouter({ history: createMemoryHistory(), routes })
}

function authenticate(
  projectCodes: string[],
  writableProjectCodes: string[] = [],
  globalRead = false,
  globalWrite = false,
  agentGlobal = false,
) {
  sessionSequence += 1
  const grants = projectCodes.map((projectCode) => projectGrant(
    PLATFORM_PERMISSION_WORKFLOW_READ,
    projectCode,
  ))
  writableProjectCodes.forEach((projectCode) => {
    grants.push(projectGrant(PLATFORM_PERMISSION_WORKFLOW_WRITE, projectCode))
  })
  if (globalRead) grants.push(globalGrant(PLATFORM_PERMISSION_WORKFLOW_READ))
  if (globalWrite) grants.push(globalGrant(PLATFORM_PERMISSION_WORKFLOW_WRITE))
  if (agentGlobal) grants.push(globalGrant(PLATFORM_PERMISSION_AGENT_READ))
  markPlatformSessionAuthenticated(createSession(`workflow-list-${sessionSequence}`, grants))
  expect(acknowledgePlatformExplorationNotice()).toBe(true)
}

async function mountWorkflowList(path: string, includeSidebar = false, sidebarCollapsed = false) {
  const pinia = createPinia()
  setActivePinia(pinia)
  useAppStore().sidebarCollapsed = sidebarCollapsed
  const router = createWorkflowRouter()
  await router.push(path)
  await router.isReady()
  const Host = defineComponent({
    name: 'WorkflowListTestHost',
    setup: () => {
      providePageProjectScope()
      return () => h('main', [
        includeSidebar ? h(AppSidebar) : null,
        h(RouterView),
      ])
    },
  })
  const wrapper = mount(Host, {
    global: {
      plugins: [pinia, router],
      directives: { loading: () => {} },
      stubs: {
        CollapsibleHeaderRegion: namedSlotsStub('CollapsibleHeaderRegion'),
        PageHeader: namedSlotsStub('PageHeader'),
        MetricIconBg: passthroughStub('MetricIconBg'),
        ElButton: ElButtonStub,
        ElCard: passthroughStub('ElCard'),
        ElDialog: ElDialogStub,
        ElForm: passthroughStub('ElForm'),
        ElFormItem: passthroughStub('ElFormItem'),
        ElInput: ElInputStub,
        ElSelect: ElSelectStub,
        ElOption: ElOptionStub,
        ElTable: ElTableStub,
        ElTableColumn: defineComponent({
          name: 'ElTableColumn',
          setup: () => () => null,
        }),
        ElTag: passthroughStub('ElTag'),
        ElPagination: passthroughStub('ElPagination'),
        ElIcon: passthroughStub('ElIcon'),
        ElScrollbar: passthroughStub('ElScrollbar'),
        ElMenu: passthroughStub('ElMenu'),
        ElMenuItem: passthroughStub('ElMenuItem'),
        ElSubMenu: passthroughStub('ElSubMenu'),
      },
    },
  })
  wrappers.push(wrapper)
  await flushPromises()
  await nextTick()
  await flushPromises()
  return { wrapper, router, workflowWrapper: wrapper.findComponent(WorkflowList) }
}

async function settle() {
  await flushPromises()
  await nextTick()
  await flushPromises()
}

describe('WorkflowList project scope integration', () => {
  beforeEach(() => {
    markPlatformSessionAnonymous(false)
    localStorage.clear()
    sessionStorage.clear()
    vi.clearAllMocks()
    mocks.getScanProjects.mockResolvedValue({ data: [createProject(1, 'project-a'), createProject(2, 'project-b')] })
    mocks.listWorkflows.mockResolvedValue({ data: [] })
    mocks.createWorkflow.mockResolvedValue({ data: createWorkflow('created', null, null) })
    mocks.deleteWorkflow.mockResolvedValue({ data: null })
  })

  afterEach(() => {
    wrappers.splice(0).forEach((wrapper) => wrapper.unmount())
    markPlatformSessionAnonymous(false)
    localStorage.clear()
    sessionStorage.clear()
  })

  it('uses only the explicit B identity and never issues an unbounded request', async () => {
    authenticate(['project-a', 'project-b'])
    mocks.listWorkflows.mockResolvedValue({ data: [createWorkflow('b', 2, 'project-b')] })

    const mounted = await mountWorkflowList('/workflows?projectId=2&projectCode=project-b')

    expect(mocks.listWorkflows).toHaveBeenCalledTimes(1)
    expect(mocks.listWorkflows).toHaveBeenCalledWith({ projectId: 2, projectCode: 'project-b' })
    expect(mounted.workflowWrapper.vm).toHaveProperty('workflows', [createWorkflow('b', 2, 'project-b')])
    expect(mounted.workflowWrapper.text()).toContain('项目 2')
  })

  it('blocks missing-code, invalid, unknown, forbidden, and restricted scopes without listing', async () => {
    authenticate(['project-a'], [], true)
    mocks.getScanProjects.mockResolvedValue({ data: [createProject(1, null)] })
    let mounted = await mountWorkflowList('/workflows?projectId=1')
    expect(mocks.listWorkflows).not.toHaveBeenCalled()
    expect(mounted.workflowWrapper.text()).toContain('缺少项目编码')
    expect(mounted.workflowWrapper.text()).toContain('Workflow')

    const firstRootIndex = wrappers.indexOf(mounted.wrapper)
    if (firstRootIndex >= 0) wrappers.splice(firstRootIndex, 1)
    mounted.wrapper.unmount()
    markPlatformSessionAnonymous(false)
    authenticate(['project-a'])
    mocks.getScanProjects.mockResolvedValue({ data: [createProject(1, 'project-a')] })
    mocks.listWorkflows.mockClear()
    mounted = await mountWorkflowList('/workflows?projectId=nope')
    expect(mocks.listWorkflows).not.toHaveBeenCalled()

    mounted = await mountWorkflowList('/workflows?projectId=999')
    expect(mocks.listWorkflows).not.toHaveBeenCalled()

    mounted = await mountWorkflowList('/workflows?scope=all')
    expect(mocks.listWorkflows).not.toHaveBeenCalled()

    mounted = await mountWorkflowList('/workflows')
    expect(mocks.listWorkflows).not.toHaveBeenCalled()
  })

  it('keeps Agent and Workflow resource labels on the shared host and sidebar', async () => {
    authenticate([], [], false, false, true)
    const mounted = await mountWorkflowList('/workflows?scope=all', true)
    expect(mounted.wrapper.text()).toContain('没有全部 Workflow')
    expect(mounted.wrapper.text()).not.toContain('没有全部 Agent')

    await mounted.router.push('/agent?scope=all')
    await settle()
    expect(mounted.wrapper.text()).toContain('全部 Agent')
  })

  it('retains confirmed scope across navigation and reset, and returns with the confirmed code', async () => {
    authenticate(['project-a', 'project-b'])
    mocks.listWorkflows.mockImplementation((params: { projectId?: number }) => Promise.resolve({
      data: [createWorkflow(params.projectId === 2 ? 'b' : 'a', params.projectId === 2 ? 2 : 1, params.projectId === 2 ? 'project-b' : 'project-a')],
    }))
    const mounted = await mountWorkflowList('/workflows?projectId=1&projectCode=project-a')
    const workflowVm = mounted.workflowWrapper.vm as unknown as {
      keyword: string
      filters: { workflowKind: string; status: string }
      searchWorkflows: () => void
      resetFilters: () => void
      backToProject: () => void
    }
    workflowVm.keyword = 'needle'
    workflowVm.filters.workflowKind = 'GENERAL'
    workflowVm.filters.status = 'DRAFT'
    workflowVm.searchWorkflows()
    await settle()
    expect(mounted.router.currentRoute.value.query.projectId).toBe('1')
    expect(mocks.listWorkflows).toHaveBeenLastCalledWith({
      projectId: 1,
      projectCode: 'project-a',
      workflowKind: 'GENERAL',
      status: 'DRAFT',
    })

    workflowVm.resetFilters()
    await settle()
    expect(workflowVm.keyword).toBe('')
    expect(mounted.router.currentRoute.value.query.projectId).toBe('1')
    expect(mocks.listWorkflows).toHaveBeenLastCalledWith({ projectId: 1, projectCode: 'project-a' })

    workflowVm.backToProject()
    await settle()
    expect(mounted.router.currentRoute.value.name).toBe('RegistryProjectDetail')
    expect(mounted.router.currentRoute.value.params.projectCode).toBe('project-a')
  })

  it('ignores stale A to B to A responses and keeps the final request loading until it finishes', async () => {
    authenticate(['project-a', 'project-b'])
    type PendingResponse = {
      params: { projectId?: number; projectCode?: string }
      resolve: (value: { data: WorkflowWorkingCopy[] }) => void
      reject: (reason?: unknown) => void
    }
    const pendingWorkflowResponses: PendingResponse[] = []
    mocks.listWorkflows.mockImplementation((params) => new Promise((resolve, reject) => {
      pendingWorkflowResponses.push({ params, resolve, reject })
    }))

    const mounted = await mountWorkflowList('/workflows?projectId=1&projectCode=project-a')
    await mounted.router.push('/workflows?projectId=2&projectCode=project-b')
    await settle()
    await mounted.router.push('/workflows?projectId=1&projectCode=project-a')
    await settle()
    expect(pendingWorkflowResponses).toHaveLength(3)

    pendingWorkflowResponses[0].resolve({ data: [createWorkflow('a-stale', 1, 'project-a')] })
    pendingWorkflowResponses[1].reject(new Error('stale B failure'))
    await settle()
    expect((mounted.workflowWrapper.vm as unknown as { loading: boolean }).loading).toBe(true)
    expect((mounted.workflowWrapper.vm as unknown as { workflows: WorkflowWorkingCopy[] }).workflows).toEqual([])

    const finalWorkflow = createWorkflow('a-final', 1, 'project-a')
    pendingWorkflowResponses[2].resolve({ data: [finalWorkflow] })
    await settle()
    expect((mounted.workflowWrapper.vm as unknown as { loading: boolean }).loading).toBe(false)
    expect((mounted.workflowWrapper.vm as unknown as { workflows: WorkflowWorkingCopy[] }).workflows).toEqual([finalWorkflow])
  })

  it('distinguishes non-array failure, network failure, and a real empty result', async () => {
    authenticate(['project-a'])
    mocks.listWorkflows.mockResolvedValueOnce({ data: { records: [] } })
    let mounted = await mountWorkflowList('/workflows?projectId=1&projectCode=project-a')
    expect((mounted.workflowWrapper.vm as unknown as { workflowListStatus: string }).workflowListStatus).toBe('error')
    expect(mounted.workflowWrapper.text()).toContain('数据格式异常')

    mocks.listWorkflows.mockRejectedValueOnce(new Error('network down'))
    await (mounted.workflowWrapper.vm as unknown as { loadWorkflows: () => Promise<void> }).loadWorkflows()
    await settle()
    expect(mounted.workflowWrapper.text()).toContain('network down')

    mocks.listWorkflows.mockResolvedValueOnce({ data: [] })
    await (mounted.workflowWrapper.vm as unknown as { loadWorkflows: () => Promise<void> }).loadWorkflows()
    await settle()
    expect((mounted.workflowWrapper.vm as unknown as { workflowListStatus: string }).workflowListStatus).toBe('success')
    expect(mounted.workflowWrapper.text()).toContain('还没有匹配的 Workflow')
  })

  it('uses the selected project id and code from the confirmed catalog without a second project query', async () => {
    authenticate(['project-a', 'project-b'], ['project-b'])
    const mounted = await mountWorkflowList('/workflows?projectId=2&projectCode=project-b')
    const workflowVm = mounted.workflowWrapper.vm as unknown as {
      createForm: { name: string; keySlug: string; projectId: number | null }
      openCreateDialog: () => void
      submitCreateWorkflow: () => Promise<void>
    }
    workflowVm.openCreateDialog()
    workflowVm.createForm.name = 'B workflow'
    workflowVm.createForm.keySlug = 'b-workflow'
    await workflowVm.submitCreateWorkflow()
    await settle()

    expect(mocks.getScanProjects).toHaveBeenCalledTimes(1)
    expect(mocks.createWorkflow).toHaveBeenCalledWith(expect.objectContaining({
      projectId: 2,
      projectCode: 'project-b',
      executionEngine: 'GRAPH_SPEC',
      definitionAuthority: 'USER',
      creationChannel: 'STUDIO',
      status: 'DRAFT',
    }))
  })

  it('keeps all-scope create blank instead of preselecting the first writable project', async () => {
    authenticate(['project-a', 'project-b'], ['project-a', 'project-b'], true, true)
    const mounted = await mountWorkflowList('/workflows?scope=all')
    const workflowVm = mounted.workflowWrapper.vm as unknown as {
      createForm: { name: string; keySlug: string; projectId: number | null }
      openCreateDialog: () => void
      submitCreateWorkflow: () => Promise<void>
    }
    workflowVm.openCreateDialog()
    expect(workflowVm.createForm.projectId).toBeNull()
    await settle()
    const projectSelect = mounted.workflowWrapper.findAllComponents({ name: 'ElSelect' }).find((select) => (
      select.classes().includes('create-project-select')
    ))
    expect(projectSelect).toBeDefined()
    projectSelect?.vm.$emit('update:modelValue', 2)
    projectSelect?.vm.$emit('change', 2)
    await settle()
    expect(workflowVm.createForm.projectId).toBe(2)
    projectSelect?.vm.$emit('update:modelValue', projectSelect?.props('valueOnClear'))
    projectSelect?.vm.$emit('clear')
    await settle()
    expect(workflowVm.createForm.projectId).toBeNull()
    workflowVm.createForm.name = 'Platform workflow'
    workflowVm.createForm.keySlug = 'platform-workflow'
    await workflowVm.submitCreateWorkflow()
    expect(mocks.createWorkflow).toHaveBeenCalledWith(expect.objectContaining({
      projectId: null,
      projectCode: null,
    }))
  })

  it('does not fall back to a platform workflow after a project-only user clears selection', async () => {
    authenticate(['project-a', 'project-b'], ['project-b'], true, false)
    const mounted = await mountWorkflowList('/workflows?scope=all')
    const workflowVm = mounted.workflowWrapper.vm as unknown as {
      createForm: { name: string; keySlug: string; projectId: number | null }
      openCreateDialog: () => void
      submitCreateWorkflow: () => Promise<void>
    }
    workflowVm.openCreateDialog()
    workflowVm.createForm.name = 'Project workflow'
    workflowVm.createForm.keySlug = 'project-workflow'
    await settle()
    const projectSelect = mounted.workflowWrapper.findAllComponents({ name: 'ElSelect' }).find((select) => (
      select.classes().includes('create-project-select')
    ))
    expect(projectSelect).toBeDefined()
    projectSelect?.vm.$emit('update:modelValue', 2)
    projectSelect?.vm.$emit('change', 2)
    await settle()
    expect(workflowVm.createForm.projectId).toBe(2)
    projectSelect?.vm.$emit('update:modelValue', projectSelect?.props('valueOnClear'))
    projectSelect?.vm.$emit('clear')
    await settle()
    expect(workflowVm.createForm.projectId).toBeNull()
    await workflowVm.submitCreateWorkflow()
    expect(mocks.createWorkflow).not.toHaveBeenCalled()
  })

  it('preserves the form and blocks submit while the confirmed project catalog refreshes', async () => {
    authenticate(['project-a'], ['project-a'])
    const mounted = await mountWorkflowList('/workflows?projectId=1&projectCode=project-a')
    const workflowVm = mounted.workflowWrapper.vm as unknown as {
      pageProjectScope: { refreshCatalog: () => Promise<ScanProject[] | null> }
      createForm: { name: string; keySlug: string }
      openCreateDialog: () => void
      submitCreateWorkflow: () => Promise<void>
    }
    workflowVm.openCreateDialog()
    workflowVm.createForm.name = 'Refreshing workflow'
    workflowVm.createForm.keySlug = 'refreshing-workflow'
    let releaseCatalog!: (response: { data: ScanProject[] }) => void
    mocks.getScanProjects.mockReturnValueOnce(new Promise((resolve) => {
      releaseCatalog = resolve
    }))
    void workflowVm.pageProjectScope.refreshCatalog()
    await nextTick()
    await workflowVm.submitCreateWorkflow()
    expect(mocks.createWorkflow).not.toHaveBeenCalled()
    expect(workflowVm.createForm.name).toBe('Refreshing workflow')
    releaseCatalog({ data: [createProject(1, 'project-a')] })
    await settle()
  })

  it('does not submit or navigate after the dialog scope changes', async () => {
    authenticate(['project-a', 'project-b'], ['project-a'])
    const mounted = await mountWorkflowList('/workflows?projectId=1&projectCode=project-a')
    const workflowVm = mounted.workflowWrapper.vm as unknown as {
      createForm: { name: string; keySlug: string }
      openCreateDialog: () => void
      submitCreateWorkflow: () => Promise<void>
    }
    workflowVm.openCreateDialog()
    workflowVm.createForm.name = 'A workflow'
    workflowVm.createForm.keySlug = 'a-workflow'
    await mounted.router.push('/workflows?projectId=2&projectCode=project-b')
    await settle()
    await workflowVm.submitCreateWorkflow()
    expect(mocks.createWorkflow).not.toHaveBeenCalled()
    expect(mounted.router.currentRoute.value.path).toBe('/workflows')
  })

  it('uses the shared recovery control to expand the sidebar from a blocked scope', async () => {
    authenticate(['project-a'])
    const mounted = await mountWorkflowList('/workflows', false, true)
    const recoveryButton = mounted.wrapper.findAll('button').find((button) => (
      button.text().includes('展开侧栏选择项目')
    ))
    expect(recoveryButton).toBeDefined()
    await recoveryButton?.trigger('click')
    expect(useAppStore().sidebarCollapsed).toBe(false)
    expect(mounted.wrapper.find('.workflow-scope-panel').exists()).toBe(true)
    expect(mounted.wrapper.find('.toolbar').exists()).toBe(false)
  })

  it('offers the shared selector recovery for a missing project code with the sidebar collapsed', async () => {
    authenticate([], [], true, true)
    mocks.getScanProjects.mockResolvedValue({ data: [createProject(1, null)] })
    const mounted = await mountWorkflowList('/workflows?projectId=1', false, true)
    expect(mounted.workflowWrapper.text()).toContain('缺少项目编码')
    const recoveryButton = mounted.workflowWrapper.findAll('button').find((button) => (
      button.text().includes('展开侧栏选择项目')
    ))
    expect(recoveryButton).toBeDefined()
    await recoveryButton?.trigger('click')
    expect(useAppStore().sidebarCollapsed).toBe(false)
    expect(mocks.listWorkflows).not.toHaveBeenCalled()
  })

  it('does not silently change a selected create target when its catalog code changes', async () => {
    authenticate([], [], true, true)
    const mounted = await mountWorkflowList('/workflows?scope=all')
    const workflowVm = mounted.workflowWrapper.vm as unknown as {
      createForm: { name: string; keySlug: string; projectId: number | null }
      openCreateDialog: () => void
      submitCreateWorkflow: () => Promise<void>
    }
    workflowVm.openCreateDialog()
    await settle()
    workflowVm.createForm.name = 'Selected target'
    workflowVm.createForm.keySlug = 'selected-target'
    const projectSelect = mounted.workflowWrapper.findAllComponents({ name: 'ElSelect' }).find((select) => (
      select.classes().includes('create-project-select')
    ))
    expect(projectSelect).toBeDefined()
    projectSelect?.vm.$emit('update:modelValue', 2)
    projectSelect?.vm.$emit('change', 2)
    await settle()
    expect(workflowVm.createForm.projectId).toBe(2)

    mocks.getScanProjects.mockResolvedValueOnce({ data: [createProject(1, 'project-a'), createProject(2, 'renamed-b')] })
    await useProjectStore().fetchProjects()
    await settle()
    await workflowVm.submitCreateWorkflow()
    expect(mocks.createWorkflow).not.toHaveBeenCalled()
    expect(mounted.router.currentRoute.value.path).toBe('/workflows')
  })

  it('allows a changed catalog identity only after the user explicitly selects it again', async () => {
    authenticate([], [], true, true)
    const mounted = await mountWorkflowList('/workflows?scope=all')
    const workflowVm = mounted.workflowWrapper.vm as unknown as {
      createForm: { name: string; keySlug: string; projectId: number | null }
      openCreateDialog: () => void
      submitCreateWorkflow: () => Promise<void>
    }
    workflowVm.openCreateDialog()
    workflowVm.createForm.name = 'Reselected target'
    workflowVm.createForm.keySlug = 'reselected-target'
    await settle()
    const projectSelect = mounted.workflowWrapper.findAllComponents({ name: 'ElSelect' }).find((select) => (
      select.classes().includes('create-project-select')
    ))
    expect(projectSelect).toBeDefined()
    projectSelect?.vm.$emit('update:modelValue', 2)
    projectSelect?.vm.$emit('change', 2)
    await settle()

    mocks.getScanProjects.mockResolvedValueOnce({ data: [createProject(1, 'project-a'), createProject(2, 'renamed-b')] })
    await useProjectStore().fetchProjects()
    await settle()
    expect(workflowVm.createForm.projectId).toBe(2)
    projectSelect?.vm.$emit('update:modelValue', 2)
    projectSelect?.vm.$emit('change', 2)
    await settle()
    await workflowVm.submitCreateWorkflow()

    expect(mocks.createWorkflow).toHaveBeenCalledWith(expect.objectContaining({
      projectId: 2,
      projectCode: 'renamed-b',
    }))
  })

  it('keeps a disappeared selected project blocked instead of creating a platform workflow', async () => {
    authenticate([], [], true, true)
    const mounted = await mountWorkflowList('/workflows?scope=all')
    const workflowVm = mounted.workflowWrapper.vm as unknown as {
      createForm: { name: string; keySlug: string; projectId: number | null }
      openCreateDialog: () => void
      submitCreateWorkflow: () => Promise<void>
    }
    workflowVm.openCreateDialog()
    workflowVm.createForm.name = 'Disappeared target'
    workflowVm.createForm.keySlug = 'disappeared-target'
    workflowVm.createForm.projectId = 2
    await settle()
    mocks.getScanProjects.mockResolvedValueOnce({ data: [createProject(1, 'project-a')] })
    await useProjectStore().fetchProjects()
    await settle()
    await workflowVm.submitCreateWorkflow()
    expect(mocks.createWorkflow).not.toHaveBeenCalled()
    expect(workflowVm.createForm.projectId).toBe(2)
  })

  it('keeps a same-scope catalog failure recoverable without telling the user to discard the dialog', async () => {
    authenticate(['project-a'], ['project-a'])
    const mounted = await mountWorkflowList('/workflows?projectId=1&projectCode=project-a')
    const workflowVm = mounted.workflowWrapper.vm as unknown as {
      createForm: { name: string; keySlug: string }
      createProjectHint: string
      createSubmitDisabled: boolean
      openCreateDialog: () => void
    }
    workflowVm.openCreateDialog()
    workflowVm.createForm.name = 'Preserved input'
    workflowVm.createForm.keySlug = 'preserved-input'
    mocks.getScanProjects.mockRejectedValueOnce(new Error('catalog offline'))
    await useProjectStore().fetchProjects()
    await settle()
    expect(workflowVm.createSubmitDisabled).toBe(true)
    expect(workflowVm.createForm.name).toBe('Preserved input')
    expect(workflowVm.createProjectHint).toContain('目录')
    expect(workflowVm.createProjectHint).not.toContain('重新打开')
    const retryButton = mounted.workflowWrapper.findAll('button').find((button) => (
      button.text().includes('重试加载项目目录')
    ))
    expect(retryButton).toBeDefined()
    mocks.getScanProjects.mockResolvedValueOnce({ data: [createProject(1, 'project-a')] })
    await retryButton?.trigger('click')
    await settle()
    expect(workflowVm.createSubmitDisabled).toBe(false)
  })

  it('ignores a late create success after moving to another scope', async () => {
    authenticate(['project-a', 'project-b'], ['project-a', 'project-b'])
    let releaseCreate!: (response: { data: WorkflowWorkingCopy }) => void
    mocks.createWorkflow.mockReturnValueOnce(new Promise((resolve) => { releaseCreate = resolve }))
    const mounted = await mountWorkflowList('/workflows?projectId=1&projectCode=project-a')
    const workflowVm = mounted.workflowWrapper.vm as unknown as {
      createForm: { name: string; keySlug: string }
      openCreateDialog: () => void
      submitCreateWorkflow: () => Promise<void>
    }
    workflowVm.openCreateDialog()
    workflowVm.createForm.name = 'Pending create'
    workflowVm.createForm.keySlug = 'pending-create'
    const creating = workflowVm.submitCreateWorkflow()
    expect(mocks.createWorkflow).toHaveBeenCalledTimes(1)
    await mounted.router.push('/workflows?projectId=2&projectCode=project-b')
    await settle()
    releaseCreate({ data: createWorkflow('created-a', 1, 'project-a') })
    await creating
    await settle()
    expect(mounted.router.currentRoute.value.query.projectId).toBe('2')
    expect(mounted.router.currentRoute.value.name).toBe('WorkflowList')
    expect(ElMessage.success).not.toHaveBeenCalled()
  })

  it('does not revive an old create dialog after the scope changes A to B to A', async () => {
    authenticate(['project-a', 'project-b'], ['project-a', 'project-b'])
    const mounted = await mountWorkflowList('/workflows?projectId=1&projectCode=project-a')
    const workflowVm = mounted.workflowWrapper.vm as unknown as {
      createForm: { name: string; keySlug: string }
      openCreateDialog: () => void
      submitCreateWorkflow: () => Promise<void>
    }
    workflowVm.openCreateDialog()
    workflowVm.createForm.name = 'Old dialog'
    workflowVm.createForm.keySlug = 'old-dialog'
    await mounted.router.push('/workflows?projectId=2&projectCode=project-b')
    await settle()
    await mounted.router.push('/workflows?projectId=1&projectCode=project-a')
    await settle()
    await workflowVm.submitCreateWorkflow()
    expect(mocks.createWorkflow).not.toHaveBeenCalled()
    expect(mounted.router.currentRoute.value.name).toBe('WorkflowList')
  })

  it('rejects a pending create when the authenticated session loses write permission', async () => {
    authenticate(['project-a'], ['project-a'])
    const mounted = await mountWorkflowList('/workflows?projectId=1&projectCode=project-a')
    const workflowVm = mounted.workflowWrapper.vm as unknown as {
      createForm: { name: string; keySlug: string }
      openCreateDialog: () => void
      submitCreateWorkflow: () => Promise<void>
    }
    workflowVm.openCreateDialog()
    workflowVm.createForm.name = 'Permission changed'
    workflowVm.createForm.keySlug = 'permission-changed'
    authenticate(['project-a'])
    await settle()
    await workflowVm.submitCreateWorkflow()
    expect(mocks.createWorkflow).not.toHaveBeenCalled()
  })

  it('checks deletable, write permission, and scope again before deleting', async () => {
    authenticate(['project-a', 'project-b'], ['project-b'])
    const rowB = createWorkflow('b', 2, 'project-b')
    const rowA = createWorkflow('a', 1, 'project-a')
    mocks.listWorkflows.mockResolvedValue({ data: [rowB, rowA] })
    const mounted = await mountWorkflowList('/workflows?projectId=2&projectCode=project-b')
    const workflowVm = mounted.workflowWrapper.vm as unknown as {
      confirmDeleteWorkflow: (row: WorkflowWorkingCopy) => Promise<void>
    }
    vi.mocked(ElMessageBox.confirm).mockResolvedValueOnce('confirm' as never)
    await workflowVm.confirmDeleteWorkflow(rowB)
    expect(mocks.deleteWorkflow).toHaveBeenCalledWith('b')

    mocks.deleteWorkflow.mockClear()
    let confirmDelete!: (value: unknown) => void
    vi.mocked(ElMessageBox.confirm).mockReturnValueOnce(new Promise((resolve) => {
      confirmDelete = resolve
    }) as never)
    const deleting = workflowVm.confirmDeleteWorkflow(rowB)
    await mounted.router.push('/workflows?projectId=1&projectCode=project-a')
    await settle()
    confirmDelete('confirm')
    await deleting
    expect(mocks.deleteWorkflow).not.toHaveBeenCalled()
    expect(rowA.projectCode).toBe('project-a')
  })
})
