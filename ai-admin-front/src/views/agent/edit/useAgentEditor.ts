import { computed, inject, onMounted, onUnmounted, provide, reactive, ref, watch } from 'vue'
import type { InjectionKey } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import type {
  AgentConfigDraft,
  AgentConfigVersion,
  AgentMemoryPolicy,
  Agent,
  AgentA2aRemoteBindingConfig,
  AgentIdentityForm,
  AgentWorkflowFreshness,
  AgentWorkflowPinFreshness,
  AgentWorkflowToolConfig,
} from '@/types/agent'
import type { WorkflowWorkingCopy } from '@/types/workflow'
import type {
  AgentSkillBindingConfig,
  AgentSkillSummary,
  AgentSkillVersion,
} from '@/types/skill'
import {
  createAgent,
  copyAgentConfigToDraft,
  getAgent,
  listAgentConfigVersions,
  publishAgentConfig,
  saveAgentConfigDraft,
  searchWorkflows,
  updateAgent,
} from '@/api/workflow'
import {
  agentWorkflowFreshnessFailure,
  formatWorkflowVersionLabel,
  getAgentWorkflowFreshness,
  publishLatestAgentWorkflowVersions,
} from '@/api/agentWorkflowFreshness'
import { listBindableAgentSkillVersions } from '@/api/skill'
import { attachKnowledgeToAgent, getKnowledgeList } from '@/api/knowledge'
import type { KnowledgeBase } from '@/types/knowledge'
import { getA2aRemoteAgent, listA2aPrincipals, listA2aRemoteAgents } from '@/api/a2aHub'
import type {
  A2aPrincipal,
  A2aRemoteAgent,
  A2aRemoteAgentDetail,
} from '@/types/a2aHub'
import { getModelInstances } from '@/api/model'
import { canLoadModelCatalog } from '@/auth/modelCatalogAccess'
import type { ScanProject } from '@/types/scanProject'
import type { ModelInstance } from '@/types/model'
import { useProjectStore } from '@/store/project'
import { formatWorkflowKindLabel } from '@/utils/workflowLabels'
import { normalizeActiveModelInstances } from '@/utils/modelSelection'
import { platformSessionUser } from '@/auth/platformSession'
import {
  hasPlatformPermission,
  hasPlatformResourcePermission,
  PLATFORM_PERMISSION_AGENT_DEBUG,
  PLATFORM_PERMISSION_AGENT_EVALUATE,
  PLATFORM_PERMISSION_AGENT_PUBLISH,
  PLATFORM_PERMISSION_RUNOPS_READ,
  PLATFORM_PERMISSION_AGENT_WRITE,
  PLATFORM_PERMISSION_EMBED_MANAGE,
  PLATFORM_PERMISSION_EMBED_READ,
} from '@/auth/platformAccess'


/* Agent 编辑页的全部状态和操作。AgentEdit.vue 创建并 provide，各配置分区组件 inject 使用。 */

export type AgentPageSection = 'overview' | 'config' | 'versions' | 'embed'
export type AgentConfigSection = 'basic' | 'decision' | 'workflows' | 'knowledge' | 'remote' | 'skills' | 'memory'
export interface SkillPickerRow {
  rowKey: string
  skill: AgentSkillSummary
  version: AgentSkillVersion
}

export const KNOWLEDGE_QA_SLUG_PREFIX = 'kb-qa-'

/** 与 Runtime RuntimeKnowledgeQaWorkflowService.keySlug 保持一致。 */
export function knowledgeQaKeySlug(projectCode: string, knowledgeBaseCode: string) {
  const slug = `${KNOWLEDGE_QA_SLUG_PREFIX}${projectCode}-${knowledgeBaseCode}`
    .toLowerCase()
    .replace(/[^a-z0-9_-]+/g, '-')
    .replace(/-+/g, '-')
    .replace(/[-_]+$/, '')
  return slug.length > 128 ? slug.slice(0, 128).replace(/[-_]+$/, '') : slug
}

export function useAgentEditor() {
  const route = useRoute()
  const router = useRouter()
  const advancedSettingsVisible = ref(false)
  const systemPromptDialogVisible = ref(false)
  const systemPromptDraft = ref('')
  const workflowPickerVisible = ref(false)
  const workflowPickerKeyword = ref('')
  const workflowPickerSelection = ref<string[]>([])
  const workflowPickerPage = ref(1)
  const workflowPickerPageSize = ref(10)
  const workflowPickerRows = ref<WorkflowWorkingCopy[]>([])
  const workflowPickerTotal = ref(0)
  const workflowPickerLoading = ref(false)
  const projectStore = useProjectStore()
  const agentId = route.params.id as string
  const isNew = agentId === 'new'
  const initialSection: AgentPageSection = route.query.tab === 'config'
    ? 'config'
    : route.query.tab === 'versions'
      ? 'versions'
      : route.query.tab === 'embed'
        ? 'embed'
        : 'overview'
  const activeSection = ref<AgentPageSection>(isNew ? 'config' : initialSection)
  const activeConfigSection = ref<AgentConfigSection>('basic')

  const formRef = ref<FormInstance>()
  const pageLoading = ref(false)
  const saving = ref(false)
  const publishing = ref(false)
  const publishingLatestWorkflows = ref(false)
  const workflowFreshness = ref<AgentWorkflowFreshness | null>(null)
  const scanProjects = ref<ScanProject[]>([])
  const llmModelInstances = ref<ModelInstance[]>([])
  const llmModelInstancesLoading = ref(false)
  const llmModelInstancesLoadError = ref(false)
  const publishedWorkflows = ref<WorkflowWorkingCopy[]>([])
  const workflowTools = ref<AgentWorkflowToolConfig[]>([])
  const remoteAgentBindings = ref<AgentA2aRemoteBindingConfig[]>([])
  const a2aBindingDialogVisible = ref(false)
  const a2aBindingLoading = ref(false)
  const trustedRemoteAgents = ref<A2aRemoteAgent[]>([])
  const localAgentPrincipals = ref<A2aPrincipal[]>([])
  const selectedRemoteAgentDetail = ref<A2aRemoteAgentDetail | null>(null)
  const DEFAULT_A2A_TIMEOUT_MS = 120_000
  const a2aBindingForm = reactive<AgentA2aRemoteBindingConfig>({
    principalId: 0,
    remoteAgentId: 0,
    remoteAgentRevisionId: 0,
    toolName: '',
    allowedSkillIds: [],
    riskLevel: 'READ',
    permissionKey: '',
    timeoutMs: DEFAULT_A2A_TIMEOUT_MS,
    enabled: true,
  })
  const a2aPrincipalSelection = computed<number | undefined>({
    get: () => a2aBindingForm.principalId || undefined,
    set: (value) => { a2aBindingForm.principalId = value || 0 },
  })
  const a2aRemoteAgentSelection = computed<number | undefined>({
    get: () => a2aBindingForm.remoteAgentId || undefined,
    set: (value) => { a2aBindingForm.remoteAgentId = value || 0 },
  })
  const a2aTimeoutSeconds = computed({
    get: () => Math.round((a2aBindingForm.timeoutMs ?? DEFAULT_A2A_TIMEOUT_MS) / 1000),
    set: (value: number) => {
      a2aBindingForm.timeoutMs = Number.isFinite(value) ? value * 1000 : DEFAULT_A2A_TIMEOUT_MS
    },
  })
  const selectedA2aRemoteAgent = computed(() => trustedRemoteAgents.value.find((item) =>
    item.id === a2aBindingForm.remoteAgentId,
  ))
  const selectedA2aRemoteRevision = computed(() => selectedRemoteAgentDetail.value?.revisions.find((item) =>
    item.id === a2aBindingForm.remoteAgentRevisionId,
  ))
  const a2aBindingReady = computed(() => Boolean(
    a2aBindingForm.principalId
    && a2aBindingForm.remoteAgentId
    && a2aBindingForm.remoteAgentRevisionId
    && a2aBindingForm.allowedSkillIds.length
    && a2aBindingForm.toolName?.trim()
    && a2aBindingForm.permissionKey?.trim(),
  ))
  const skillBindings = ref<AgentSkillBindingConfig[]>([])
  const skillPickerVisible = ref(false)
  const skillPickerKeyword = ref('')
  const skillPickerLoading = ref(false)
  const skillPickerRows = ref<SkillPickerRow[]>([])
  const skillReplaceTargetIndex = ref<number | null>(null)
  const configVersions = ref<AgentConfigVersion[]>([])
  const currentConfig = ref<AgentConfigVersion | null>(null)
  const entryConfigText = ref('{}')
  const DEFAULT_TOTAL_TIMEOUT_MS = 300_000
  const DEFAULT_WORKFLOW_TIMEOUT_MS = 180_000
  const DEFAULT_PAGE_BRIDGE_TIMEOUT_MS = 30_000


  const supervisor = reactive({
    systemPrompt: '',
    modelInstanceId: '',
    maxPlanSteps: 6,
    maxWorkflowCalls: 4,
    maxReplans: 2,
    totalTimeoutMs: DEFAULT_TOTAL_TIMEOUT_MS,
    workflowTimeoutMs: DEFAULT_WORKFLOW_TIMEOUT_MS,
    pageBridgeTimeoutMs: DEFAULT_PAGE_BRIDGE_TIMEOUT_MS,
    parallelReadOnly: true,
    policyProfile: 'STANDARD',
  })

  const DEFAULT_MEMORY_POLICY: AgentMemoryPolicy = {
    sessionMemoryEnabled: true,
    sessionMemoryWindow: 40,
    personalMemoryRead: false,
    personalMemoryWrite: 'OFF',
  }
  const memoryPolicy = reactive<AgentMemoryPolicy>({ ...DEFAULT_MEMORY_POLICY })
  const memorySummaryLabel = computed(() => {
    const personal = memoryPolicy.personalMemoryRead || memoryPolicy.personalMemoryWrite !== 'OFF'
    if (personal) return '个人'
    return memoryPolicy.sessionMemoryEnabled ? '会话' : '关'
  })

  const totalTimeoutSeconds = computed({
    get: () => Math.round(supervisor.totalTimeoutMs / 1000),
    set: (value: number) => { supervisor.totalTimeoutMs = value * 1000 },
  })
  const workflowTimeoutSeconds = computed({
    get: () => Math.round(supervisor.workflowTimeoutMs / 1000),
    set: (value: number) => { supervisor.workflowTimeoutMs = value * 1000 },
  })
  const pageBridgeTimeoutSeconds = computed({
    get: () => Math.round(supervisor.pageBridgeTimeoutMs / 1000),
    set: (value: number) => { supervisor.pageBridgeTimeoutMs = value * 1000 },
  })
  const writableScanProjects = computed(() => scanProjects.value.filter((project) => (
    hasPlatformResourcePermission(
      platformSessionUser.value?.permissionGrants,
      PLATFORM_PERMISSION_AGENT_WRITE,
      'PROJECT',
      null,
      project.projectCode,
    )
  )))
  const canWriteAgent = computed(() => hasPlatformResourcePermission(
    platformSessionUser.value?.permissionGrants,
    PLATFORM_PERMISSION_AGENT_WRITE,
    'PROJECT',
    null,
    form.projectCode,
  ))
  const canDebugAgent = computed(() => hasPlatformResourcePermission(
    platformSessionUser.value?.permissionGrants,
    PLATFORM_PERMISSION_AGENT_DEBUG,
    'PROJECT',
    null,
    form.projectCode,
  ))
  const canPublishAgent = computed(() => hasPlatformResourcePermission(
    platformSessionUser.value?.permissionGrants,
    PLATFORM_PERMISSION_AGENT_PUBLISH,
    'PROJECT',
    null,
    form.projectCode,
  ))
  const canReadEmbed = computed(() => hasPlatformPermission(
    platformSessionUser.value?.permissions,
    PLATFORM_PERMISSION_EMBED_READ,
  ))
  const canManageEmbed = computed(() => hasPlatformPermission(
    platformSessionUser.value?.permissions,
    PLATFORM_PERMISSION_EMBED_MANAGE,
  ))
  const canBindSkills = computed(() => {
    const permissions = platformSessionUser.value?.permissions || []
    return permissions.includes('*') || permissions.includes('skill:bind')
  })
  const systemPromptPreviewHasContent = computed(() => Boolean((supervisor.systemPrompt || '').trim()))
  const systemPromptPreview = computed(() => {
    const text = (supervisor.systemPrompt || '').replace(/\s+/g, ' ').trim()
    return text
      ? `已填写工作要求（${text.length} 字），点击查看或修改`
      : '点击填写工作要求，说明职责、判断规则和回答边界'
  })

  function openSystemPromptEditor() {
    systemPromptDraft.value = supervisor.systemPrompt || ''
    systemPromptDialogVisible.value = true
  }

  function applySystemPromptDraft() {
    supervisor.systemPrompt = systemPromptDraft.value
    systemPromptDialogVisible.value = false
  }
  const isDevelopmentPolicy = computed(() => supervisor.policyProfile === 'DEV_ALLOW_ALL')
  function openPolicySettings() {
    activeSection.value = 'config'
    advancedSettingsVisible.value = true
  }
  const policyProfileHint = computed(() => supervisor.policyProfile === 'DEV_ALLOW_ALL'
    ? '研发模式会放宽租户与角色映射检查，但页面操作仍需用户明确要求，修改操作仍需确认，不可撤销操作仍默认禁止。'
    : '运行前会检查项目、租户、可使用角色、所需权限和操作类型，并记录审核信息。')
  const selectedWorkflowIds = computed<string[]>({
    get: () => workflowTools.value.map((tool) => tool.workflowId),
    set: (workflowIds) => syncWorkflowSelection(workflowIds),
  })

  let workflowPickerSearchTimer: ReturnType<typeof setTimeout> | null = null
  let workflowPickerRequestSequence = 0

  const form = reactive<AgentIdentityForm>({
    keySlug: '',
    name: '',
    description: '',
    projectId: null,
    projectCode: null,
    visibility: 'PROJECT',
    allowedRoles: [],
    enabled: true,
  })

  const rules: FormRules = {
    name: [{ required: true, message: '请输入智能体名称', trigger: 'blur' }],
    keySlug: [{ required: true, message: '请输入唯一标识', trigger: 'blur' }],
  }

  const activeConfig = computed(() => configVersions.value.find((item) => item.status === 'ACTIVE') || null)
  const draftConfig = computed(() => configVersions.value.find((item) => item.status === 'DRAFT') || null)
  const staleWorkflowTools = computed(() => (workflowFreshness.value?.tools || []).filter((tool) => tool.stale))

  /** Latest published version of this card's Workflow when the card is pinned to a different one. */
  function newerWorkflowVersion(row: AgentWorkflowToolConfig): AgentWorkflowPinFreshness | null {
    const latest = (workflowFreshness.value?.tools || []).find((tool) => tool.workflowId === row.workflowId)
    if (!latest?.latestWorkflowVersionId || !row.workflowVersionId) return null
    return latest.latestWorkflowVersionId !== row.workflowVersionId ? latest : null
  }

  async function refreshWorkflowFreshness() {
    if (isNew) return
    try {
      const { data } = await getAgentWorkflowFreshness(agentId, { errorFeedback: 'local' })
      workflowFreshness.value = data && Array.isArray(data.tools) ? data : null
    } catch {
      workflowFreshness.value = null
    }
  }

  async function handlePublishLatestWorkflows() {
    const expected = workflowFreshness.value?.activeConfigVersionId
    if (!canPublishAgent.value || !expected || publishingLatestWorkflows.value) return
    try {
      await ElMessageBox.confirm(
        `将基于线上配置 v${workflowFreshness.value?.activeConfigVersionNo ?? '-'} 生成新版本，把 ${staleWorkflowTools.value.length} 个 Workflow 更新到当前发布版本后立即发布。`
          + '其他线上配置不变；未保存或未发布的草稿修改不会包含在内。',
        '更新 Workflow 版本并发布',
        { type: 'warning', confirmButtonText: '更新并发布', cancelButtonText: '取消' },
      )
    } catch {
      return
    }
    publishingLatestWorkflows.value = true
    try {
      const { data } = await publishLatestAgentWorkflowVersions(agentId, expected, 'admin-ui', { errorFeedback: 'local' })
      const hadDraft = Boolean(draftConfig.value)
      await refreshConfigVersions()
      if (!hadDraft && data.published) loadConfigToForm(data.config)
      ElMessage.success(data.published
        ? `已发布配置 v${data.config.versionNo}，开始使用最新 Workflow 版本`
        : '线上配置已在使用最新 Workflow 版本')
    } catch (error) {
      ElMessage.error(agentWorkflowFreshnessFailure(error))
    } finally {
      publishingLatestWorkflows.value = false
      await refreshWorkflowFreshness()
    }
  }
  const selectedModelLabel = computed(() => {
    const modelId = supervisor.modelInstanceId
    if (!modelId) return '尚未配置'
    const model = llmModelInstances.value.find((item) => String(item.id) === String(modelId))
    return model?.name || model?.modelName || '已配置模型'
  })
  const projectSummaryLabel = computed(() => {
    if (form.projectId == null && !form.projectCode) return '平台级'
    const project = scanProjects.value.find((item) => item.id === form.projectId)
    return project?.name || form.projectCode || '平台级'
  })
  function showConfigSection(section: AgentConfigSection) {
    activeSection.value = 'config'
    activeConfigSection.value = section
  }

  async function showVersionSection() {
    activeSection.value = 'versions'
    await refreshConfigVersions()
  }

  function viewVersionInConfig(config: AgentConfigVersion) {
    loadConfigToForm(config)
    activeSection.value = 'config'
    activeConfigSection.value = 'decision'
  }

  function riskLabel(value?: string | null) {
    return ({
      READ: '只查询',
      WRITE: '会修改数据',
      PAGE_ACTION: '会操作页面',
      IRREVERSIBLE: '可能无法撤销',
    } as Record<string, string>)[value || ''] || value || '未标注'
  }

  function riskToneClass(value?: string | null) {
    if (value === 'READ') return 'is-read'
    if (value === 'PAGE_ACTION') return 'is-info'
    if (value === 'IRREVERSIBLE') return 'is-danger'
    return 'is-warning'
  }

  function workflowSummary(row: AgentWorkflowToolConfig) {
    return row.descriptionOverride
      || row.description
      || workflowById(row.workflowId)?.description
      || '按需执行已发布流程'
  }

  function projectOptionLabel(project?: ScanProject | null) {
    if (!project) return ''
    const code = project.projectCode ? ` / ${project.projectCode}` : ''
    const env = project.environment ? ` · ${project.environment}` : ''
    return `${project.name}${code}${env}`
  }

  function projectCodeById(projectId?: number | null) {
    if (projectId == null) return null
    return scanProjects.value.find((project) => project.id === projectId)?.projectCode || null
  }

  function parseAllowedRoles(json?: string | null): string[] {
    if (!json?.trim()) return []
    try {
      const parsed = JSON.parse(json)
      return Array.isArray(parsed) ? parsed.map(String) : []
    } catch {
      return []
    }
  }

  function entryToForm(entry: Agent): void {
    Object.assign(form, {
      keySlug: entry.keySlug || '',
      name: entry.name || '',
      description: entry.description || '',
      projectId: entry.projectId ?? null,
      projectCode: entry.projectCode ?? null,
      visibility: entry.visibility || 'PROJECT',
      allowedRoles: parseAllowedRoles(entry.allowedRolesJson),
      enabled: entry.enabled !== false,
    })
  }

  function buildPayload(): Partial<Agent> {
    return {
      keySlug: form.keySlug?.trim(),
      name: form.name?.trim(),
      description: form.description || null,
      projectId: form.projectId ?? null,
      projectCode: form.projectCode ?? projectCodeById(form.projectId) ?? null,
      visibility: form.visibility || 'PROJECT',
      allowedRolesJson: form.allowedRoles?.length ? JSON.stringify(form.allowedRoles) : null,
      enabled: form.enabled !== false,
    }
  }

  function workflowToolName(workflow: WorkflowWorkingCopy): string {
    const raw = (workflow.keySlug || workflow.id || 'workflow').replace(/[^A-Za-z0-9_]/g, '_')
    const prefixed = /^[A-Za-z]/.test(raw) ? raw : `wf_${raw}`
    return prefixed.length >= 2 ? prefixed.slice(0, 128) : `wf_${prefixed}`
  }

  function workflowById(workflowId: string) {
    return publishedWorkflows.value.find((item) => item.id === workflowId)
  }

  function openWorkflowPicker() {
    if (workflowPickerSearchTimer) {
      clearTimeout(workflowPickerSearchTimer)
      workflowPickerSearchTimer = null
    }
    workflowPickerKeyword.value = ''
    workflowPickerSelection.value = [...selectedWorkflowIds.value]
    workflowPickerPage.value = 1
    workflowPickerRows.value = []
    workflowPickerTotal.value = 0
    workflowPickerVisible.value = true
    void loadWorkflowPickerPage()
  }

  function scheduleWorkflowPickerSearch() {
    workflowPickerPage.value = 1
    if (workflowPickerSearchTimer) clearTimeout(workflowPickerSearchTimer)
    workflowPickerSearchTimer = setTimeout(() => {
      workflowPickerSearchTimer = null
      void loadWorkflowPickerPage()
    }, 300)
  }

  function toggleWorkflowPickerRow(workflowId: string, selected?: boolean) {
    const next = new Set(workflowPickerSelection.value)
    const shouldSelect = selected ?? !next.has(workflowId)
    if (shouldSelect) next.add(workflowId)
    else next.delete(workflowId)
    workflowPickerSelection.value = [...next]
  }

  function applyWorkflowPickerSelection() {
    selectedWorkflowIds.value = [...workflowPickerSelection.value]
    workflowPickerVisible.value = false
  }

  function workflowInitial(value?: string | null) {
    return (value || 'W').trim().slice(0, 1).toUpperCase()
  }

  function workflowKindKey(value?: string | null) {
    return String(value || 'GENERAL').toUpperCase()
  }

  function workflowAvatarClass(kind?: string | null, authority?: string | null) {
    if (workflowKindKey(kind) === 'PAGE_ASSISTANT') return 'page-action'
    if (String(authority || '').toUpperCase() === 'SDK') return 'sdk-graph'
    return 'chat'
  }

  function workflowKindTagType(kind?: string | null) {
    if (workflowKindKey(kind) === 'PAGE_ASSISTANT') return 'success'
    return 'info'
  }

  function cachePublishedWorkflows(workflows: WorkflowWorkingCopy[]) {
    const cached = new Map(publishedWorkflows.value.map((workflow) => [workflow.id, workflow]))
    for (const workflow of workflows) cached.set(workflow.id, workflow)
    publishedWorkflows.value = [...cached.values()]
  }

  async function loadWorkflowPickerPage() {
    const requestSequence = ++workflowPickerRequestSequence
    workflowPickerLoading.value = true
    try {
      const { data } = await searchWorkflows({
        status: 'ACTIVE',
        keyword: workflowPickerKeyword.value.trim() || undefined,
        current: workflowPickerPage.value,
        size: workflowPickerPageSize.value,
      })
      if (requestSequence !== workflowPickerRequestSequence) return
      const rows = Array.isArray(data?.records) ? data.records : []
      workflowPickerRows.value = rows
      workflowPickerTotal.value = Math.max(Number(data?.total) || 0, 0)
      cachePublishedWorkflows(rows)

      const maxPage = Math.max(1, Math.ceil(workflowPickerTotal.value / workflowPickerPageSize.value))
      if (workflowPickerPage.value > maxPage) {
        workflowPickerPage.value = maxPage
        void loadWorkflowPickerPage()
      }
    } catch {
      if (requestSequence !== workflowPickerRequestSequence) return
      workflowPickerRows.value = []
      workflowPickerTotal.value = 0
      ElMessage.error('加载可选 Workflow 失败')
    } finally {
      if (requestSequence === workflowPickerRequestSequence) {
        workflowPickerLoading.value = false
      }
    }
  }

  function defaultWorkflowTool(workflow: WorkflowWorkingCopy): AgentWorkflowToolConfig {
    const pageAction = workflow.workflowKind === 'PAGE_ASSISTANT'
    return {
      workflowId: workflow.id,
      workflowKeySlug: workflow.keySlug,
      workflowName: workflow.name,
      toolName: workflowToolName(workflow),
      riskLevel: pageAction ? 'PAGE_ACTION' : 'READ',
      permissionKey: `workflow:${workflow.keySlug}`,
      readOnly: !pageAction,
      enabled: true,
      priority: workflowTools.value.length,
    }
  }

  function syncWorkflowSelection(workflowIds: string[]) {
    const current = new Map(workflowTools.value.map((tool) => [tool.workflowId, tool]))
    workflowTools.value = workflowIds.flatMap((workflowId, priority) => {
      const existing = current.get(workflowId)
      const workflow = workflowById(workflowId)
      if (!existing && !workflow) return []
      return [{ ...(existing || defaultWorkflowTool(workflow!)), priority }]
    })
  }

  function syncReadOnly(tool: AgentWorkflowToolConfig) {
    tool.readOnly = tool.riskLevel === 'READ'
  }

  function moveWorkflowTool(index: number, delta: number) {
    const target = index + delta
    if (target < 0 || target >= workflowTools.value.length) return
    const next = [...workflowTools.value]
    ;[next[index], next[target]] = [next[target], next[index]]
    workflowTools.value = next.map((tool, priority) => ({ ...tool, priority }))
  }

  function removeWorkflowTool(index: number) {
    workflowTools.value = workflowTools.value
      .filter((_, itemIndex) => itemIndex !== index)
      .map((tool, priority) => ({ ...tool, priority }))
  }

  // 知识库以「知识问答」Workflow（kb-qa-*）接入，单独在「知识库」分区展示和维护。
  const knowledgePickerVisible = ref(false)
  const knowledgeOptions = ref<KnowledgeBase[]>([])
  const knowledgeOptionsLoading = ref(false)
  const knowledgeAddingCode = ref('')

  function isKnowledgeQaTool(row: AgentWorkflowToolConfig) {
    const slug = row.workflowKeySlug || workflowById(row.workflowId)?.keySlug || ''
    return slug.startsWith(KNOWLEDGE_QA_SLUG_PREFIX)
  }

  const knowledgeTools = computed(() => workflowTools.value.filter(isKnowledgeQaTool))
  const generalWorkflowEntries = computed(() => workflowTools.value
    .map((row, index) => ({ row, index }))
    .filter(({ row }) => !isKnowledgeQaTool(row)))

  const generalWorkflowTools = computed(() => generalWorkflowEntries.value.map(({ row }) => row))

  function knowledgeToolLabel(row: AgentWorkflowToolConfig) {
    const name = row.workflowName || workflowById(row.workflowId)?.name || row.workflowKeySlug || row.workflowId
    return name.replace(/^知识问答[:：]\s*/, '')
  }

  function knowledgeOptionAttached(kb: KnowledgeBase) {
    const slug = knowledgeQaKeySlug(form.projectCode || projectCodeById(form.projectId) || '', kb.code)
    return workflowTools.value.some((row) => row.workflowKeySlug === slug)
  }

  async function openKnowledgePicker() {
    knowledgePickerVisible.value = true
    knowledgeOptionsLoading.value = true
    try {
      const { data } = await getKnowledgeList()
      knowledgeOptions.value = Array.isArray(data) ? data : []
    } catch {
      knowledgeOptions.value = []
    } finally {
      knowledgeOptionsLoading.value = false
    }
  }

  /** 准备好知识问答 Workflow 后只加入草稿，保存草稿、调试后再随 Agent 一起发布。 */
  async function addKnowledgeBase(kb: KnowledgeBase) {
    if (isNew || knowledgeAddingCode.value) return
    knowledgeAddingCode.value = kb.code
    try {
      const { data } = await attachKnowledgeToAgent(agentId, {
        knowledgeBaseCode: kb.code,
        knowledgeBaseName: kb.name,
        modelInstanceId: supervisor.modelInstanceId || undefined,
        publishedBy: platformSessionUser.value?.displayName || platformSessionUser.value?.username || undefined,
        attachToAgent: false,
      })
      if (workflowTools.value.some((row) => row.workflowId === data.workflowId)) {
        ElMessage.info(`「${kb.name}」已在知识库列表中`)
        return
      }
      workflowTools.value = [...workflowTools.value, {
        workflowId: data.workflowId,
        workflowKeySlug: data.workflowKeySlug,
        workflowName: data.workflowName,
        toolName: workflowToolName({ id: data.workflowId, keySlug: data.workflowKeySlug } as WorkflowWorkingCopy),
        descriptionOverride: data.toolDescription || null,
        riskLevel: 'READ',
        permissionKey: `workflow:${data.workflowKeySlug}`,
        readOnly: true,
        enabled: true,
        priority: workflowTools.value.length,
      }]
      ElMessage.success(`已加入「${kb.name}」，保存草稿后可调试`)
    } catch {
      // 请求层已提示失败原因
    } finally {
      knowledgeAddingCode.value = ''
    }
  }

  function removeWorkflowToolById(workflowId: string) {
    const index = workflowTools.value.findIndex((row) => row.workflowId === workflowId)
    if (index >= 0) removeWorkflowTool(index)
  }

  /** 在「可调用 Workflow」可见列表里上下移动（跳过知识库条目）。 */
  function moveGeneralWorkflowTool(position: number, delta: number) {
    const entries = generalWorkflowEntries.value
    const from = entries[position]
    const to = entries[position + delta]
    if (!from || !to) return
    const next = [...workflowTools.value]
    ;[next[from.index], next[to.index]] = [next[to.index], next[from.index]]
    workflowTools.value = next.map((tool, priority) => ({ ...tool, priority }))
  }

  // 已保存的协作 Agent 只存任务 ID，按外部 Agent 详情补上任务名称。
  const a2aSkillNames = ref<Record<string, string>>({})
  const requestedA2aRemoteAgents = new Set<number>()
  watch(remoteAgentBindings, (bindings) => {
    for (const binding of bindings) {
      if (!binding.remoteAgentId || requestedA2aRemoteAgents.has(binding.remoteAgentId)) continue
      requestedA2aRemoteAgents.add(binding.remoteAgentId)
      getA2aRemoteAgent(binding.remoteAgentId, { quiet: true }).then(({ data }) => {
        const names = { ...a2aSkillNames.value }
        for (const revision of data?.revisions || []) {
          for (const skill of revision.protocolSkills || []) {
            if (skill.name) names[`${revision.id}:${skill.id}`] = skill.name
          }
        }
        a2aSkillNames.value = names
      }).catch(() => undefined)
    }
  }, { immediate: true })

  function a2aSkillLabel(binding: AgentA2aRemoteBindingConfig, skillId: string) {
    return a2aSkillNames.value[`${binding.remoteAgentRevisionId}:${skillId}`] || skillId
  }

  function safeA2aToolName(remoteAgentKey: string) {
    const normalized = `delegate_${remoteAgentKey}`.replace(/[^A-Za-z0-9_]/g, '_')
    return /^[A-Za-z]/.test(normalized) ? normalized.slice(0, 128) : `a2a_${normalized}`.slice(0, 128)
  }

  function openA2aTrustWorkspace() {
    a2aBindingDialogVisible.value = false
    router.push('/a2a-hub/trust')
  }

  function openA2aRemoteAgentCatalog() {
    a2aBindingDialogVisible.value = false
    router.push('/a2a-hub/remote-agents')
  }

  async function openA2aBindingDialog() {
    if (isNew) {
      ElMessage.info('请先保存当前 Agent，再配置 Agent 协作')
      return
    }
    a2aBindingLoading.value = true
    try {
      const [remoteResult, principalResult] = await Promise.all([
        listA2aRemoteAgents({ status: 'TRUSTED', limit: 100 }),
        listA2aPrincipals({ status: 'ACTIVE', limit: 100 }),
      ])
      trustedRemoteAgents.value = remoteResult.data?.items ?? []
      localAgentPrincipals.value = (principalResult.data?.items ?? []).filter((principal) =>
        principal.principalType === 'LOCAL_AGENT' && principal.attributes?.runtimeAgentId === agentId,
      )
      selectedRemoteAgentDetail.value = null
      Object.assign(a2aBindingForm, {
        principalId: localAgentPrincipals.value[0]?.id ?? 0,
        remoteAgentId: 0,
        remoteAgentRevisionId: 0,
        toolName: '',
        allowedSkillIds: [],
        riskLevel: 'READ',
        permissionKey: '',
        timeoutMs: DEFAULT_A2A_TIMEOUT_MS,
        enabled: true,
      })
      a2aBindingDialogVisible.value = true
    } catch {
      ElMessage.error('加载可协作的外部 Agent 失败')
    } finally {
      a2aBindingLoading.value = false
    }
  }

  async function handleA2aRemoteAgentChanged(remoteAgentId: number) {
    selectedRemoteAgentDetail.value = null
    a2aBindingForm.remoteAgentRevisionId = 0
    a2aBindingForm.allowedSkillIds = []
    const remote = trustedRemoteAgents.value.find((item) => item.id === remoteAgentId)
    if (!remote?.currentRevisionId) return
    a2aBindingLoading.value = true
    try {
      const { data } = await getA2aRemoteAgent(remoteAgentId)
      selectedRemoteAgentDetail.value = data
      const revision = data.revisions.find((item) => item.id === remote.currentRevisionId
        && item.reviewStatus === 'APPROVED')
      if (!revision) throw new Error('该外部 Agent 当前没有已审核可用的版本')
      a2aBindingForm.remoteAgentRevisionId = revision.id
      a2aBindingForm.allowedSkillIds = revision.protocolSkills.map((skill) => skill.id)
      a2aBindingForm.toolName = safeA2aToolName(remote.remoteAgentKey)
      a2aBindingForm.permissionKey = `a2a:remote-agent:${remote.remoteAgentKey}:invoke`
    } catch {
      ElMessage.error('加载外部 Agent 可用版本失败')
    } finally {
      a2aBindingLoading.value = false
    }
  }

  function addA2aBinding() {
    if (!a2aBindingForm.principalId || !a2aBindingForm.remoteAgentId
      || !a2aBindingForm.remoteAgentRevisionId || !a2aBindingForm.allowedSkillIds.length) {
      ElMessage.warning('请选择当前 Agent 的协作身份、外部 Agent，以及至少一项可委派任务')
      return
    }
    if (!/^[A-Za-z][A-Za-z0-9_]{1,127}$/.test(a2aBindingForm.toolName || '')) {
      ElMessage.warning('调用标识格式不正确：需以英文字母开头，只能包含字母、数字和下划线')
      return
    }
    if (remoteAgentBindings.value.some((item) => item.remoteAgentRevisionId
      === a2aBindingForm.remoteAgentRevisionId)) {
      ElMessage.warning('这个外部 Agent 版本已添加')
      return
    }
    remoteAgentBindings.value.push({
      ...a2aBindingForm,
      allowedSkillIds: [...a2aBindingForm.allowedSkillIds],
      priority: remoteAgentBindings.value.length,
    })
    a2aBindingDialogVisible.value = false
  }

  function removeA2aBinding(index: number) {
    remoteAgentBindings.value = remoteAgentBindings.value
      .filter((_, itemIndex) => itemIndex !== index)
      .map((binding, priority) => ({ ...binding, priority }))
  }

  function moveA2aBinding(index: number, delta: number) {
    const target = index + delta
    if (target < 0 || target >= remoteAgentBindings.value.length) return
    const next = [...remoteAgentBindings.value]
    ;[next[index], next[target]] = [next[target], next[index]]
    remoteAgentBindings.value = next.map((binding, priority) => ({ ...binding, priority }))
  }

  function openSkillPicker(replaceIndex?: number) {
    if (!canBindSkills.value) {
      ElMessage.warning('当前账号只能查看，不能修改 Skill')
      return
    }
    const normalizedIndex = Number.isInteger(replaceIndex) ? Number(replaceIndex) : null
    skillReplaceTargetIndex.value = normalizedIndex
    const target = normalizedIndex == null ? null : skillBindings.value[normalizedIndex]
    skillPickerKeyword.value = target?.name || ''
    skillPickerRows.value = []
    skillPickerVisible.value = true
    void loadSkillPicker()
  }

  async function loadSkillPicker() {
    skillPickerLoading.value = true
    try {
      const { data } = await listBindableAgentSkillVersions({
        search: skillPickerKeyword.value.trim() || undefined,
        agentProjectCode: form.projectCode || projectCodeById(form.projectId) || undefined,
      })
      skillPickerRows.value = (Array.isArray(data) ? data : []).map(({ skill, version }) => ({
        rowKey: `${skill.id}:${version.id}`,
        skill,
        version,
      }))
    } catch {
      skillPickerRows.value = []
      ElMessage.error('加载可添加的 Skill 失败')
    } finally {
      skillPickerLoading.value = false
    }
  }

  function canChooseSkillRow(row: SkillPickerRow) {
    const replaceIndex = skillReplaceTargetIndex.value
    if (replaceIndex != null) {
      const target = skillBindings.value[replaceIndex]
      return Boolean(target)
        && target.skillId === row.skill.id
        && target.skillVersionId !== row.version.id
    }
    return !skillBindings.value.some((binding) =>
      binding.publisher?.toLowerCase() === row.skill.publisher.toLowerCase()
        && binding.name === row.skill.name,
    )
  }

  function chooseSkillRow(row: SkillPickerRow) {
    if (!canChooseSkillRow(row)) return
    const replaceIndex = skillReplaceTargetIndex.value
    const previous = replaceIndex == null ? null : skillBindings.value[replaceIndex]
    const next: AgentSkillBindingConfig = {
      skillId: row.skill.id,
      skillVersionId: row.version.id,
      publisher: row.skill.publisher,
      name: row.skill.name,
      displayName: row.skill.displayName,
      version: row.version.version,
      sourceSha256: row.version.sourceSha256,
      hasScripts: row.version.hasScripts,
      activationMode: previous?.activationMode || 'MODEL_SELECTED',
      scriptPolicy: 'DENY',
      required: previous?.required || false,
      enabled: previous?.enabled ?? true,
      priority: replaceIndex == null ? skillBindings.value.length : replaceIndex,
    }
    if (replaceIndex == null) {
      skillBindings.value = [...skillBindings.value, next]
    } else {
      const bindings = [...skillBindings.value]
      bindings[replaceIndex] = next
      skillBindings.value = bindings
    }
    skillPickerVisible.value = false
    ElMessage.success(replaceIndex == null ? 'Skill 已添加到当前草稿' : 'Skill 版本已更换')
  }

  function handleSkillRequiredChange(binding: AgentSkillBindingConfig) {
    if (binding.required) binding.enabled = true
  }

  function moveSkillBinding(index: number, delta: number) {
    const target = index + delta
    if (target < 0 || target >= skillBindings.value.length) return
    const next = [...skillBindings.value]
    ;[next[index], next[target]] = [next[target], next[index]]
    skillBindings.value = next.map((binding, priority) => ({ ...binding, priority }))
  }

  function removeSkillBinding(index: number) {
    skillBindings.value = skillBindings.value
      .filter((_, itemIndex) => itemIndex !== index)
      .map((binding, priority) => ({ ...binding, priority }))
  }

  function skillSourceLabel(value?: string | null) {
    return ({ UPLOAD: '上传', GIT: 'Git', MARKET: '市场', BUILTIN: '内置' } as Record<string, string>)[value || '']
      || value
      || '未知'
  }

  function validateJson(value: string, label: string) {
    if (!value.trim()) return
    try {
      JSON.parse(value)
    } catch {
      throw new Error(`${label} JSON 格式无效`)
    }
  }

  function buildConfigDraft(): AgentConfigDraft {
    validateJson(entryConfigText.value, '扩展配置')
    const toolNames = new Set<string>()
    for (const tool of workflowTools.value) {
      if (!/^[A-Za-z][A-Za-z0-9_]{1,127}$/.test(tool.toolName || '')) {
        throw new Error(`Workflow“${tool.workflowName || tool.workflowId}”的调用标识格式不正确`)
      }
      if (toolNames.has(tool.toolName)) throw new Error(`调用标识重复：${tool.toolName}`)
      toolNames.add(tool.toolName)
      validateJson(tool.inputSchemaOverrideJson || '', `${tool.toolName} 自定义输入格式`)
      validateJson(tool.outputSchemaOverrideJson || '', `${tool.toolName} 自定义输出格式`)
    }
    for (const binding of remoteAgentBindings.value) {
      if (!/^[A-Za-z][A-Za-z0-9_]{1,127}$/.test(binding.toolName || '')) {
        throw new Error(`外部 Agent“${binding.remoteAgentKey || binding.remoteAgentId}”的调用标识格式不正确`)
      }
      if (toolNames.has(binding.toolName)) throw new Error(`调用标识重复：${binding.toolName}`)
      toolNames.add(binding.toolName)
      if (!binding.allowedSkillIds.length) throw new Error(`${binding.toolName} 至少需要选择一项可委派任务`)
    }
    return {
      runtimeType: 'AGENTSCOPE',
      systemPrompt: supervisor.systemPrompt || null,
      modelInstanceId: supervisor.modelInstanceId || null,
      maxPlanSteps: supervisor.maxPlanSteps,
      maxWorkflowCalls: supervisor.maxWorkflowCalls,
      maxReplans: supervisor.maxReplans,
      totalTimeoutMs: supervisor.totalTimeoutMs,
      workflowTimeoutMs: supervisor.workflowTimeoutMs,
      pageBridgeTimeoutMs: supervisor.pageBridgeTimeoutMs,
      parallelReadOnly: supervisor.parallelReadOnly,
      policyProfile: supervisor.policyProfile,
      toolCatalogMode: 'ALLOW_LIST',
      memory: { ...memoryPolicy },
      configJson: entryConfigText.value.trim() || null,
      tools: workflowTools.value.map((tool, priority) => ({ ...tool, priority })),
      remoteAgents: remoteAgentBindings.value.map((binding, priority) => ({
        principalId: binding.principalId,
        remoteAgentId: binding.remoteAgentId,
        remoteAgentRevisionId: binding.remoteAgentRevisionId,
        remoteAgentKey: binding.remoteAgentKey,
        toolName: binding.toolName,
        allowedSkillIds: [...binding.allowedSkillIds],
        riskLevel: binding.riskLevel,
        permissionKey: binding.permissionKey,
        timeoutMs: binding.timeoutMs,
        enabled: binding.enabled !== false,
        priority,
      })),
      skills: skillBindings.value.map((binding, priority) => ({
        skillId: binding.skillId,
        skillVersionId: binding.skillVersionId,
        activationMode: binding.activationMode || 'MODEL_SELECTED',
        scriptPolicy: 'DENY',
        required: Boolean(binding.required),
        enabled: binding.enabled !== false,
        priority,
      })),
    }
  }

  async function handleProjectChange(projectId: number | null | undefined) {
    form.projectCode = projectCodeById(projectId)
  }

  function handleSave() {
    return saveDraft()
  }

  /** 调试跑的是草稿，所以配置页的「调试」先保存草稿再打开调试页。 */
  async function handleSaveAndDebug() {
    if (await saveDraft({ keepSection: true })) openDebug()
  }

  async function saveDraft(options: { keepSection?: boolean } = {}): Promise<boolean> {
    if (!canWriteAgent.value) {
      ElMessage.warning('当前账号没有该项目的 Agent 写权限')
      return false
    }
    const valid = await formRef.value?.validate().catch(() => false)
    if (!valid) return false
    saving.value = true
    try {
      const payload = buildPayload()
      if (isNew) {
        const { data } = await createAgent(payload)
        const { data: draft } = await saveAgentConfigDraft(data.id, buildConfigDraft())
        currentConfig.value = draft
        ElMessage.success('Agent 已创建，配置已保存为草稿')
        await router.push('/agent')
      } else {
        await updateAgent(agentId, payload)
        const { data } = await saveAgentConfigDraft(agentId, buildConfigDraft())
        currentConfig.value = data
        await refreshConfigVersions()
        if (!options.keepSection) activeSection.value = 'overview'
        ElMessage.success(`配置草稿 v${data.versionNo} 已保存，线上版本未改变`)
      }
      return true
    } catch (error) {
      const message = error instanceof Error ? error.message : ''
      const response = (error as { response?: { data?: { message?: string; error?: string } } })?.response
      ElMessage.error(message || response?.data?.message || response?.data?.error || '保存失败')
      return false
    } finally {
      saving.value = false
    }
  }

  async function handlePublish() {
    if (!canPublishAgent.value) return
    const valid = await formRef.value?.validate().catch(() => false)
    if (!valid || isNew) return
    publishing.value = true
    try {
      await ElMessageBox.confirm(
        isDevelopmentPolicy.value
          ? '这个配置使用研发模式，运行时不检查租户和权限角色。发布后，当前草稿会成为该 Agent 的生效配置，上一版本会自动归档。确认发布吗？'
          : '发布后，当前草稿会成为该 Agent 的生效配置，上一版本会自动归档。确认发布吗？',
        '发布 Agent 配置',
        { type: 'warning', confirmButtonText: '确认发布' },
      )
      await updateAgent(agentId, buildPayload())
      const { data: draft } = await saveAgentConfigDraft(agentId, buildConfigDraft())
      const { data: active } = await publishAgentConfig(agentId, draft.id, 'admin-ui')
      currentConfig.value = active
      await refreshConfigVersions()
      void refreshWorkflowFreshness()
      activeSection.value = 'overview'
      ElMessage.success(`Agent 配置 v${active.versionNo} 已发布`)
    } catch (error) {
      if (error !== 'cancel' && error !== 'close') {
        const message = error instanceof Error ? error.message : '发布失败'
        ElMessage.error(message)
      }
    } finally {
      publishing.value = false
    }
  }

  function openDebug() {
    if (!isNew && canDebugAgent.value) router.push(`/agent/${agentId}/debug`)
  }

  const canReadRuns = computed(() => hasPlatformResourcePermission(
    platformSessionUser.value?.permissionGrants,
    PLATFORM_PERMISSION_RUNOPS_READ,
    'PROJECT',
    null,
    form.projectCode,
  ))
  const canEvaluateAgent = computed(() => hasPlatformResourcePermission(
    platformSessionUser.value?.permissionGrants,
    PLATFORM_PERMISSION_AGENT_EVALUATE,
    'PROJECT',
    null,
    form.projectCode,
  ))

  function openRunRecords(traceId?: string) {
    if (isNew) return
    if (traceId) router.push(`/runops/${encodeURIComponent(traceId)}`)
    else router.push({ path: '/runops', query: { agentId } })
  }

  function openAgentEval() {
    if (!isNew) router.push(`/runops/evals/${agentId}`)
  }

  function openKnowledgeFromOverview() {
    activeSection.value = 'config'
    showConfigSection('knowledge')
    void openKnowledgePicker()
  }

  async function refreshConfigVersions() {
    if (isNew) return
    const { data } = await listAgentConfigVersions(agentId)
    configVersions.value = Array.isArray(data) ? data : []
  }

  function loadConfigToForm(config: AgentConfigVersion) {
    currentConfig.value = config
    Object.assign(supervisor, {
      systemPrompt: config.systemPrompt || '',
      modelInstanceId: config.modelInstanceId || '',
      maxPlanSteps: config.maxPlanSteps ?? 6,
      maxWorkflowCalls: config.maxWorkflowCalls ?? 4,
      maxReplans: config.maxReplans ?? 2,
      totalTimeoutMs: config.totalTimeoutMs ?? DEFAULT_TOTAL_TIMEOUT_MS,
      workflowTimeoutMs: config.workflowTimeoutMs ?? DEFAULT_WORKFLOW_TIMEOUT_MS,
      pageBridgeTimeoutMs: config.pageBridgeTimeoutMs ?? DEFAULT_PAGE_BRIDGE_TIMEOUT_MS,
      parallelReadOnly: config.parallelReadOnly !== false,
      policyProfile: config.policyProfile || 'STANDARD',
    })
    Object.assign(memoryPolicy, { ...DEFAULT_MEMORY_POLICY, ...(config.memory || {}) })
    entryConfigText.value = config.configJson || '{}'
    workflowTools.value = (config.tools || []).map((tool, priority) => ({ ...tool, priority }))
    remoteAgentBindings.value = (config.remoteAgents || []).map((binding, priority) => ({
      ...binding,
      allowedSkillIds: [...(binding.allowedSkillIds || [])],
      enabled: binding.enabled !== false,
      priority,
    }))
    skillBindings.value = (config.skills || []).map((binding, priority) => ({
      ...binding,
      activationMode: binding.activationMode || 'MODEL_SELECTED',
      scriptPolicy: 'DENY',
      required: Boolean(binding.required),
      enabled: binding.enabled !== false,
      priority,
    }))
  }

  async function handleCopyVersion(config: AgentConfigVersion) {
    try {
      await ElMessageBox.confirm(
        `将 v${config.versionNo} 复制为新的可编辑草稿；现有草稿会被替换。确认继续？`,
        '复制配置版本',
        { type: 'warning' },
      )
      const { data } = await copyAgentConfigToDraft(agentId, config.id)
      loadConfigToForm(data)
      await refreshConfigVersions()
      activeSection.value = 'config'
      activeConfigSection.value = 'decision'
      ElMessage.success(`已从 v${config.versionNo} 创建草稿 v${data.versionNo}`)
    } catch (error) {
      if (error !== 'cancel' && error !== 'close') ElMessage.error('复制配置版本失败')
    }
  }

  function versionStatusType(status?: string) {
    if (status === 'ACTIVE') return 'success'
    if (status === 'DRAFT') return 'warning'
    return 'info'
  }

  function configVersionStatusLabel(status?: string) {
    if (status === 'ACTIVE') return '生效中'
    if (status === 'DRAFT') return '草稿'
    if (status === 'ARCHIVED') return '已归档'
    return status || '未配置'
  }

  function formatDateTime(value?: string | null) {
    if (!value) return '-'
    const parsed = Date.parse(value)
    return Number.isFinite(parsed) ? new Date(parsed).toLocaleString() : value
  }

  async function loadAgent() {
    if (isNew) return
    pageLoading.value = true
    try {
      const { data } = await getAgent(agentId)
      entryToForm(data)
      await loadAgentConfig()
    } catch {
      ElMessage.error('加载 Agent 失败')
    } finally {
      pageLoading.value = false
    }
  }

  async function loadAgentConfig() {
    if (isNew) return
    await refreshConfigVersions()
    void refreshWorkflowFreshness()
    const config = configVersions.value.find((item) => item.status === 'DRAFT')
      || configVersions.value.find((item) => item.status === 'ACTIVE')
      || configVersions.value[0]
    if (!config) return
    loadConfigToForm(config)
  }

  async function loadScanProjects() {
    const loadedProjects = await projectStore.fetchProjects()
    if (loadedProjects === null) {
      scanProjects.value = []
      return
    }
    scanProjects.value = loadedProjects
    if (isNew) {
      if (route.query.scope === 'all') {
        form.projectId = null
        form.projectCode = null
        return
      }
      const queryProjectId = Number(route.query.projectId)
      form.projectId = Number.isFinite(queryProjectId) && queryProjectId > 0
        ? queryProjectId
        : projectStore.currentProjectId ?? null
      form.projectCode = projectCodeById(form.projectId)
    }
  }

  async function loadModelInstances() {
    if (!canLoadModelCatalog()) return
    llmModelInstancesLoading.value = true
    llmModelInstancesLoadError.value = false
    try {
      const { data } = await getModelInstances({ modelType: 'LLM' })
      llmModelInstances.value = normalizeActiveModelInstances(data, 'LLM')
    } catch {
      llmModelInstances.value = []
      llmModelInstancesLoadError.value = true
    } finally {
      llmModelInstancesLoading.value = false
    }
  }

  function handleModelSelectVisible(visible: boolean) {
    if (visible) void loadModelInstances()
  }

  onMounted(async () => {
    await Promise.all([loadScanProjects(), loadModelInstances()])
    await loadAgent()
  })

  onUnmounted(() => {
    if (workflowPickerSearchTimer) clearTimeout(workflowPickerSearchTimer)
  })

  return {
    route,
    router,
    a2aSkillLabel,
    canReadRuns,
    canEvaluateAgent,
    openRunRecords,
    openAgentEval,
    openKnowledgeFromOverview,
    generalWorkflowTools,
    knowledgePickerVisible,
    knowledgeOptions,
    knowledgeOptionsLoading,
    knowledgeAddingCode,
    knowledgeTools,
    generalWorkflowEntries,
    knowledgeToolLabel,
    knowledgeOptionAttached,
    openKnowledgePicker,
    addKnowledgeBase,
    removeWorkflowToolById,
    moveGeneralWorkflowTool,
    advancedSettingsVisible,
    systemPromptDialogVisible,
    systemPromptDraft,
    workflowPickerVisible,
    workflowPickerKeyword,
    workflowPickerSelection,
    workflowPickerPage,
    workflowPickerPageSize,
    workflowPickerRows,
    workflowPickerTotal,
    workflowPickerLoading,
    projectStore,
    agentId,
    isNew,
    initialSection,
    activeSection,
    activeConfigSection,
    formRef,
    pageLoading,
    saving,
    publishing,
    publishingLatestWorkflows,
    workflowFreshness,
    scanProjects,
    llmModelInstances,
    llmModelInstancesLoading,
    llmModelInstancesLoadError,
    publishedWorkflows,
    workflowTools,
    remoteAgentBindings,
    a2aBindingDialogVisible,
    a2aBindingLoading,
    trustedRemoteAgents,
    localAgentPrincipals,
    selectedRemoteAgentDetail,
    a2aBindingForm,
    a2aPrincipalSelection,
    a2aRemoteAgentSelection,
    a2aTimeoutSeconds,
    selectedA2aRemoteAgent,
    selectedA2aRemoteRevision,
    a2aBindingReady,
    skillBindings,
    skillPickerVisible,
    skillPickerKeyword,
    skillPickerLoading,
    skillPickerRows,
    skillReplaceTargetIndex,
    configVersions,
    currentConfig,
    entryConfigText,
    supervisor,
    memoryPolicy,
    memorySummaryLabel,
    totalTimeoutSeconds,
    workflowTimeoutSeconds,
    pageBridgeTimeoutSeconds,
    writableScanProjects,
    canWriteAgent,
    canDebugAgent,
    canPublishAgent,
    canReadEmbed,
    canManageEmbed,
    canBindSkills,
    systemPromptPreviewHasContent,
    systemPromptPreview,
    openSystemPromptEditor,
    applySystemPromptDraft,
    isDevelopmentPolicy,
    openPolicySettings,
    policyProfileHint,
    selectedWorkflowIds,
    form,
    rules,
    activeConfig,
    draftConfig,
    staleWorkflowTools,
    newerWorkflowVersion,
    refreshWorkflowFreshness,
    handlePublishLatestWorkflows,
    selectedModelLabel,
    projectSummaryLabel,
    showConfigSection,
    showVersionSection,
    viewVersionInConfig,
    riskLabel,
    riskToneClass,
    workflowSummary,
    projectOptionLabel,
    projectCodeById,
    parseAllowedRoles,
    entryToForm,
    buildPayload,
    workflowToolName,
    workflowById,
    openWorkflowPicker,
    scheduleWorkflowPickerSearch,
    toggleWorkflowPickerRow,
    applyWorkflowPickerSelection,
    workflowInitial,
    workflowKindKey,
    workflowAvatarClass,
    workflowKindTagType,
    cachePublishedWorkflows,
    loadWorkflowPickerPage,
    defaultWorkflowTool,
    syncWorkflowSelection,
    syncReadOnly,
    moveWorkflowTool,
    removeWorkflowTool,
    safeA2aToolName,
    openA2aTrustWorkspace,
    openA2aRemoteAgentCatalog,
    openA2aBindingDialog,
    handleA2aRemoteAgentChanged,
    addA2aBinding,
    removeA2aBinding,
    moveA2aBinding,
    openSkillPicker,
    loadSkillPicker,
    canChooseSkillRow,
    chooseSkillRow,
    handleSkillRequiredChange,
    moveSkillBinding,
    removeSkillBinding,
    skillSourceLabel,
    validateJson,
    buildConfigDraft,
    handleProjectChange,
    handleSave,
    handleSaveAndDebug,
    saveDraft,
    handlePublish,
    openDebug,
    refreshConfigVersions,
    loadConfigToForm,
    handleCopyVersion,
    versionStatusType,
    configVersionStatusLabel,
    formatDateTime,
    loadAgent,
    loadAgentConfig,
    loadScanProjects,
    loadModelInstances,
    handleModelSelectVisible,
    DEFAULT_A2A_TIMEOUT_MS,
    formatWorkflowVersionLabel,
    formatWorkflowKindLabel,
  }
}

export type AgentEditor = ReturnType<typeof useAgentEditor>

const AGENT_EDITOR_KEY: InjectionKey<AgentEditor> = Symbol('agentEditor')

export function provideAgentEditor(editor: AgentEditor) {
  provide(AGENT_EDITOR_KEY, editor)
}

export function useAgentEditorContext(): AgentEditor {
  const editor = inject(AGENT_EDITOR_KEY)
  if (!editor) throw new Error('useAgentEditorContext() must be used inside AgentEdit')
  return editor
}
