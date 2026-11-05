import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { platformSessionId, platformSessionUser } from '@/auth/platformSession'
import { getScanProjects } from '@/api/scanProject'
import {
  employeePortalRequest, EmployeePortalError,
  type EmployeeProfile, type EmployeeCapabilities, type EmployeeCall, type EmployeeApproval,
} from '@/api/employeePortal'
import {
  businessSystemUrl, readPortalToken, readWorkbenchConnections, writeWorkbenchConnections,
  readWorkbenchPreferences, writeWorkbenchPreferences, MAX_WORKBENCH_SYSTEMS,
  type WorkbenchConnection,
} from './myWorkbench'

export interface WorkbenchSystem {
  code: string
  name: string
  url?: string
  connection?: WorkbenchConnection
}

const catalogReaders = new Set(['*', 'platform:read', 'agent:read', 'workflow:read', 'runops:read',
  'automation:read', 'knowledge:read', 'model:read', 'tool-acl:read', 'embed:read', 'skill:read', 'mcp-hub:read'])

export function useMyWorkbench() {
  const route = useRoute()
  const router = useRouter()
  const catalog = ref<WorkbenchSystem[]>([])
  const connections = ref<WorkbenchConnection[]>([])
  const selectedCode = ref('')
  const pinned = ref<string[]>([])
  const loading = ref(false)
  const loadError = ref('')
  const connectionNotice = ref('')
  const busy = ref(false)
  const capabilities = ref<EmployeeCapabilities[]>([])
  const calls = ref<EmployeeCall[]>([])
  const approvals = ref<EmployeeApproval[]>([])
  const activityLoading = ref(false)
  const activityError = ref('')
  const capabilitiesError = ref('')
  let controller: AbortController | undefined
  let activityController: AbortController | undefined
  let generation = 0
  let activityGeneration = 0
  let pendingImport = ''

  const owner = computed(() => platformSessionId.value)
  const userId = computed(() => platformSessionUser.value?.userId ?? 0)
  const systems = computed(() => {
    const merged = new Map(catalog.value.map(system => [system.code, { ...system }]))
    for (const connection of connections.value) {
      const code = connection.profile.projectCode
      merged.set(code, { ...merged.get(code), code, name: connection.profile.systemName, connection })
    }
    return [...merged.values()].sort((a, b) => Number(pinned.value.includes(b.code)) - Number(pinned.value.includes(a.code)))
  })
  const selected = computed(() => systems.value.find(system => system.code === selectedCode.value))
  const visibleSystems = computed(() => {
    const visible = systems.value.slice(0, 6)
    if (selected.value && !visible.some(system => system.code === selectedCode.value)) visible.splice(5, 1, selected.value)
    return visible
  })
  const suggestions = computed(() => {
    const current = capabilities.value.find(system => system.projectCode === selectedCode.value)
    // Queries explain real published capabilities; clicking only fills the input, never runs a write.
    return (current?.tools ?? []).filter(tool => tool.readOnly && tool.description?.trim())
      .slice(0, 3).map(tool => ({ label: tool.description!.trim(), prompt: `请帮我${tool.description!.trim()}` }))
  })
  const toolLabels = computed(() => new Map(capabilities.value.flatMap(system =>
    system.tools.map(tool => [tool.name, tool.description || tool.name] as const))))
  const pendingApprovals = computed(() => approvals.value.filter(approval => approval.approvalStatus === 'PENDING'))

  function persist() { writeWorkbenchConnections(owner.value, connections.value) }
  function savePreferences() {
    if (userId.value) writeWorkbenchPreferences(userId.value, { selected: selectedCode.value, pinned: pinned.value })
  }

  async function load() {
    controller?.abort()
    activityController?.abort()
    ++activityGeneration
    activityLoading.value = false
    calls.value = []
    approvals.value = []
    capabilities.value = []
    controller = new AbortController()
    const signal = controller.signal
    const current = ++generation
    loading.value = true
    loadError.value = ''
    connectionNotice.value = ''
    catalog.value = []
    connections.value = []
    const saved = readWorkbenchPreferences(userId.value)
    pinned.value = saved.pinned
    const stored = readWorkbenchConnections(owner.value)
    const importing = route.query.connect === 'business'
    if (importing) pendingImport = readPortalToken()
    const incoming = pendingImport
    const tokens = [...new Set([...stored.map(item => item.token), ...(incoming ? [incoming] : [])])].slice(-MAX_WORKBENCH_SYSTEMS)
    if (route.query.connect === 'business') {
      const query = { ...route.query }
      delete query.connect
      void router.replace({ path: route.path, query })
    }
    if (incoming === '' && importing) connectionNotice.value = '请先从业务系统的 AI 面板打开个人页，再选择「在工作台中使用」。'
    const canReadCatalog = platformSessionUser.value?.permissions?.some(permission => catalogReaders.has(permission))
    const results = await Promise.allSettled([
      canReadCatalog ? getScanProjects({}, { signal, errorFeedback: 'local' }) : Promise.resolve({ data: [] }),
      ...tokens.map(async token => ({ token, profile: await employeePortalRequest<EmployeeProfile>(token, '/me', signal) })),
    ] as const)
    if (signal.aborted || current !== generation) return
    const projects = results[0]
    if (projects.status === 'fulfilled') {
      catalog.value = projects.value.data.filter(project => project.projectCode).map(project => ({
        code: project.projectCode!, name: project.name, url: businessSystemUrl(project.businessPageUrl),
      }))
    } else loadError.value = '系统入口暂时无法加载，请重试。'
    const verified = new Map<string, WorkbenchConnection>()
    let transientFailure = false
    const retryableTokens = new Set<string>()
    for (const [index, result] of results.slice(1).entries()) {
      if (result.status === 'fulfilled') {
        const connection = result.value as WorkbenchConnection
        if (connection.profile?.projectCode) verified.set(connection.profile.projectCode, connection)
      } else if (!(result.reason instanceof EmployeePortalError && result.reason.status === 401)) {
        transientFailure = true
        retryableTokens.add(tokens[index])
      } else {
        if (tokens[index] === pendingImport) pendingImport = ''
        connectionNotice.value = '部分业务登录已过期，请从对应业务系统重新连接。'
      }
    }
    connections.value = [...verified.values()]
    // Preserve unverified credentials after a transient outage so an explicit retry can recover them.
    writeWorkbenchConnections(owner.value, [
      ...stored.filter(connection => retryableTokens.has(connection.token)), ...connections.value,
    ])
    if (transientFailure) connectionNotice.value = '业务连接暂时无法验证，请重试；验证通过后即可对话。'
    if (incoming && connections.value.some(connection => connection.token === incoming)) {
      selectedCode.value = connections.value.find(connection => connection.token === incoming)!.profile.projectCode
      pendingImport = ''
    } else selectedCode.value = systems.value.find(system => system.code === saved.selected)?.code
      ?? systems.value.find(system => system.connection)?.code ?? systems.value[0]?.code ?? ''
    loading.value = false
    savePreferences()
    void loadActivity()
  }

  async function loadActivity() {
    activityController?.abort()
    activityController = new AbortController()
    const signal = activityController.signal
    const current = ++activityGeneration
    capabilities.value = []
    calls.value = []
    approvals.value = []
    activityError.value = ''
    capabilitiesError.value = ''
    const token = selected.value?.connection?.expired ? undefined : selected.value?.connection?.token
    activityLoading.value = Boolean(token)
    if (!token) return
    const results = await Promise.allSettled([
      employeePortalRequest<EmployeeCapabilities[]>(token, '/capabilities', signal),
      employeePortalRequest<EmployeeCall[]>(token, '/calls', signal),
      employeePortalRequest<EmployeeApproval[]>(token, '/ai-tool-connections/approvals', signal),
    ])
    if (signal.aborted || current !== activityGeneration) return
    if (results.some(result => result.status === 'rejected' && result.reason instanceof EmployeePortalError && result.reason.status === 401)) {
      expireConnection(token)
    } else {
      const [capabilityResult, callResult, approvalResult] = results
      if (capabilityResult.status === 'fulfilled') capabilities.value = capabilityResult.value
      else capabilitiesError.value = '推荐办事内容暂时无法加载。'
      if (callResult.status === 'fulfilled') calls.value = callResult.value
      if (approvalResult.status === 'fulfilled') approvals.value = approvalResult.value
      if (callResult.status === 'rejected' || approvalResult.status === 'rejected') activityError.value = '部分办事记录暂时无法加载，请重试。'
    }
    activityLoading.value = false
  }

  function expireConnection(token: string) {
    const connection = connections.value.find(connection => connection.token === token)
    // Keep the mounted conversation/draft readable, but never restore an expired credential.
    if (connection) connection.expired = true
    persist()
    calls.value = []
    approvals.value = []
    capabilities.value = []
    busy.value = false
    connectionNotice.value = '业务登录已过期，请从业务系统重新连接。'
  }

  function selectSystem(code: string) {
    if (busy.value || loading.value || code === selectedCode.value || !systems.value.some(system => system.code === code)) return
    selectedCode.value = code
    savePreferences()
    void loadActivity()
  }

  function togglePinned(code: string) {
    pinned.value = pinned.value.includes(code) ? pinned.value.filter(item => item !== code) : [...pinned.value, code].slice(-MAX_WORKBENCH_SYSTEMS)
    savePreferences()
  }

  watch([owner, userId], () => {
    pendingImport = ''
    busy.value = false
    selectedCode.value = ''
    activityController?.abort()
    ++activityGeneration
    calls.value = []; approvals.value = []; capabilities.value = []
    if (owner.value && userId.value) void load()
    else {
      controller?.abort(); ++generation
      loading.value = false
      activityLoading.value = false
      connections.value = []; catalog.value = []
      writeWorkbenchConnections('', [])
    }
  }, { immediate: true })
  onBeforeUnmount(() => { controller?.abort(); activityController?.abort() })

  return { systems, selected, selectedCode, visibleSystems, pinned, loading, loadError, connectionNotice,
    busy, capabilities, suggestions, calls, approvals, pendingApprovals, toolLabels, activityLoading,
    activityError, capabilitiesError, connections, load, loadActivity, selectSystem, togglePinned, expireConnection }
}
