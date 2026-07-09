import { computed, ref, type Ref } from 'vue'
import type { ProjectToolInfo, ScanProject } from '@/types/scanProject'
import type { ScanModule, SemanticDoc } from '@/types/semanticDoc'
import { formatScanStatusLabel } from '@/utils/projectLabels'

export interface ToolModuleGroup {
  key: string
  moduleId: number | null
  label: string
  tools: ProjectToolInfo[]
}

export type ApiGovernanceAction =
  | 'importApi'
  | 'scan'
  | 'generateAi'
  | 'reconcile'
  | 'scanSensitive'
  | 'viewCatalog'
  | 'refresh'
  | 'scanRules'
  | 'modelSettings'
  | 'ops'

export type ApiGovernanceStageStatus = 'done' | 'active' | 'todo' | 'warning' | 'danger'

export interface ApiGovernanceStage {
  key: string
  label: string
  value: string
  desc: string
  status: ApiGovernanceStageStatus
}

export interface ApiGovernanceAdvice {
  title: string
  description: string
  primaryLabel: string
  primaryAction: ApiGovernanceAction
  secondaryLabel?: string
  secondaryAction?: ApiGovernanceAction
}

export interface UseScanProjectSummaryDeps {
  project: Ref<ScanProject | null>
  tools: Ref<ProjectToolInfo[]>
  modules: Ref<ScanModule[]>
  projectDoc: Ref<SemanticDoc | null>
  moduleDocMap: Ref<Record<number, SemanticDoc>>
  toolDocMap: Ref<Record<number, SemanticDoc>>
  projectAccessLabel: Readonly<Ref<string>>
}

export function useScanProjectSummary(deps: UseScanProjectSummaryDeps) {
  const toolModuleGroupPageSize = 60
  const visibleModuleGroupLimit = ref(toolModuleGroupPageSize)

  const semanticCompletionPercent = computed(() => {
    const total = deps.tools.value.length + deps.modules.value.length + (deps.project.value ? 1 : 0)
    if (total <= 0) return 0
    const completed =
      Object.keys(deps.toolDocMap.value).length +
      Object.keys(deps.moduleDocMap.value).length +
      (deps.projectDoc.value ? 1 : 0)
    return Math.min(100, Math.round((completed / total) * 100))
  })

  const linkedToolCount = computed(() => deps.tools.value.filter((item) => !!item.globalToolDefinitionId).length)

  const outOfSyncToolCount = computed(() => deps.tools.value.filter((item) => item.globalToolOutOfSync).length)

  const apiCount = computed(() => deps.tools.value.length || deps.project.value?.apiCount || deps.project.value?.toolCount || 0)

  const generatedToolDocCount = computed(() => Object.keys(deps.toolDocMap.value).length)

  const semanticMissingToolCount = computed(() => Math.max(0, apiCount.value - generatedToolDocCount.value))

  const addableToolCount = computed(() =>
    deps.tools.value.filter((item) => !item.globalToolDefinitionId && !item.removedFromSource).length,
  )

  const agentVisibleToolCount = computed(() =>
    deps.tools.value.filter((item) => item.agentVisible && item.globalToolDefinitionId && !item.removedFromSource).length,
  )

  const sensitiveRiskCount = computed(() =>
    deps.tools.value.filter((item) => item.sensitiveData?.types?.length || item.sensitiveData?.summary).length,
  )

  const removedToolCount = computed(() => deps.tools.value.filter((item) => item.removedFromSource).length)

  const governanceStages = computed<ApiGovernanceStage[]>(() => {
    const hasApi = apiCount.value > 0
    return [
      {
        key: 'discover',
        label: '发现 API',
        value: `${apiCount.value}`,
        desc: hasApi ? `已归档 ${apiCount.value} 个接口` : '先完成扫描或 SDK 同步',
        status: hasApi ? 'done' : 'active',
      },
      {
        key: 'semantic',
        label: '补全 AI 语义',
        value: hasApi ? `${generatedToolDocCount.value}/${apiCount.value}` : '-',
        desc: !hasApi
          ? '等待接口目录生成'
          : semanticMissingToolCount.value > 0
            ? `${semanticMissingToolCount.value} 个接口待补语义`
            : 'AI 语义已覆盖',
        status: !hasApi ? 'todo' : semanticMissingToolCount.value > 0 ? 'active' : 'done',
      },
      {
        key: 'tool',
        label: '上架 Tool',
        value: hasApi ? `${linkedToolCount.value}/${apiCount.value}` : '-',
        desc: !hasApi
          ? '等待项目接口'
          : addableToolCount.value > 0
            ? `${addableToolCount.value} 个 API 可添加为 Tool`
            : 'Tool 关联已治理',
        status: !hasApi ? 'todo' : addableToolCount.value > 0 ? 'active' : 'done',
      },
      {
        key: 'agent',
        label: '用于 Agent',
        value: hasApi ? `${agentVisibleToolCount.value}` : '-',
        desc: agentVisibleToolCount.value > 0 ? '已可进入智能体编排' : '上架后开放给 Agent',
        status: agentVisibleToolCount.value > 0 ? 'done' : hasApi && linkedToolCount.value > 0 ? 'active' : 'todo',
      },
    ]
  })

  const stageAdvice = computed<ApiGovernanceAdvice>(() => {
    const project = deps.project.value
    const isSdkProject = project?.projectKind === 'REGISTERED'
    const hasApi = apiCount.value > 0
    if (!project) {
      return {
        title: '正在加载 API 治理上下文',
        description: '读取项目接入方式、接口目录和 Tool 关联状态。',
        primaryLabel: '刷新',
        primaryAction: 'refresh',
      }
    }
    if (!hasApi) {
      if (isSdkProject) {
        return {
          title: '等待业务系统同步 API 能力',
          description: '',
          primaryLabel: '添加接口',
          primaryAction: 'importApi',
          secondaryLabel: '刷新同步状态',
          secondaryAction: 'refresh',
        }
      }
      return {
        title: '还没有发现 API',
        description: '当前还没有扫描结果；可先检查扫描规则，再启动一次 API 扫描。',
        primaryLabel: '开始扫描 API',
        primaryAction: 'scan',
        secondaryLabel: '配置扫描规则',
        secondaryAction: 'scanRules',
      }
    }
    if (semanticMissingToolCount.value > 0) {
      return {
        title: '先补全 AI 语义，再上架给 Agent',
        description: `${semanticMissingToolCount.value} 个接口还缺少 AI 理解，补齐后更适合进入 Tool 和 Workflow。`,
        primaryLabel: '一键生成 AI 语义',
        primaryAction: 'generateAi',
        secondaryLabel: '模型设置',
        secondaryAction: 'modelSettings',
      }
    }
    if (sensitiveRiskCount.value > 0 || removedToolCount.value > 0) {
      return {
        title: '复核风险后再开放给 Agent',
        description: '敏感数据、源接口下线状态需要集中检查，避免把不安全或失效接口暴露给智能体。',
        primaryLabel: '扫描敏感数据',
        primaryAction: 'scanSensitive',
        secondaryLabel: '查看接口目录',
        secondaryAction: 'viewCatalog',
      }
    }
    if (addableToolCount.value > 0) {
      return {
        title: '这些 API 已准备好上架为 Tool',
        description: `${addableToolCount.value} 个接口还没有 Tool 关联，可在接口目录中按模块或按接口添加。`,
        primaryLabel: '查看可上架 API',
        primaryAction: 'viewCatalog',
        secondaryLabel: '检查 Tool 关联',
        secondaryAction: 'reconcile',
      }
    }
    if (outOfSyncToolCount.value > 0) {
      return {
        title: 'Tool 关联存在差异',
        description: `${outOfSyncToolCount.value} 个接口与已上架 Tool 不一致，请对账后同步更新。`,
        primaryLabel: '检查 Tool 关联',
        primaryAction: 'reconcile',
        secondaryLabel: '查看接口目录',
        secondaryAction: 'viewCatalog',
      }
    }
    return {
      title: 'API 已进入 Agent 可用链路',
      description: '接口目录、AI 语义和 Tool 关联已具备基础治理信息，可继续用于 Agent 与 Workflow 编排。',
      primaryLabel: '刷新状态',
      primaryAction: 'refresh',
      secondaryLabel: '维护动作',
      secondaryAction: 'ops',
    }
  })

  const workbenchSummaryCards = computed(() => [
    {
      label: '扫描状态',
      value: deps.project.value ? formatScanStatusLabel(deps.project.value.status) : '-',
      desc: `接口 ${apiCount.value} 个，模块 ${deps.modules.value.length} 个`,
      tone: deps.project.value?.status === 'failed'
        ? 'danger'
        : deps.project.value?.status === 'scanning'
          ? 'warning'
          : 'success',
    },
    {
      label: 'AI 理解质量',
      value: `${semanticCompletionPercent.value}%`,
      desc: apiCount.value > 0 ? `${semanticMissingToolCount.value} 个接口待补语义` : '等待接口目录生成',
      tone: semanticCompletionPercent.value >= 80 ? 'success' : semanticCompletionPercent.value > 0 ? 'warning' : 'muted',
    },
    {
      label: 'Tool 同步',
      value: `${linkedToolCount.value}/${deps.tools.value.length || 0}`,
      desc: apiCount.value <= 0
        ? '等待项目接口'
        : outOfSyncToolCount.value > 0
          ? `${outOfSyncToolCount.value} 个接口存在差异`
          : 'API 与 Tool 关联状态可治理',
      tone: outOfSyncToolCount.value > 0 ? 'warning' : 'success',
    },
    {
      label: '风险提示',
      value: sensitiveRiskCount.value > 0 ? `${sensitiveRiskCount.value} 条` : '无',
      desc: apiCount.value <= 0
        ? '等待项目接口'
        : removedToolCount.value > 0
          ? `${removedToolCount.value} 个源接口已移除`
          : '敏感数据与下线状态集中复核',
      tone: sensitiveRiskCount.value > 0 || removedToolCount.value > 0 ? 'danger' : 'muted',
    },
  ])

  const toolModuleGroups = computed<ToolModuleGroup[]>(() => {
    const moduleById = new Map<number, ScanModule>()
    for (const module of deps.modules.value) {
      moduleById.set(module.id, module)
    }
    const buckets = new Map<string, ProjectToolInfo[]>()
    const order: string[] = []

    for (const tool of deps.tools.value) {
      const moduleId = tool.moduleId ?? null
      const key = moduleId != null ? `m-${moduleId}` : 'm-none'
      if (!buckets.has(key)) {
        buckets.set(key, [])
        order.push(key)
      }
      buckets.get(key)!.push(tool)
    }

    const groups: ToolModuleGroup[] = order.map((key) => {
      const list = buckets.get(key)!
      const moduleId = key === 'm-none' ? null : Number(key.slice(2))
      const module = moduleId != null ? moduleById.get(moduleId) : undefined
      const fromTool = list[0]?.moduleDisplayName?.trim()
      const label =
        moduleId == null
          ? '未关联模块'
          : (module?.displayName?.trim() || module?.name || fromTool || `模块 #${moduleId}`)
      return { key, moduleId, label, tools: list }
    })

    return groups.sort((a, b) => {
      if (a.moduleId == null && b.moduleId != null) return 1
      if (a.moduleId != null && b.moduleId == null) return -1
      return a.label.localeCompare(b.label, 'zh-CN')
    })
  })

  const visibleToolModuleGroups = computed(() => toolModuleGroups.value.slice(0, visibleModuleGroupLimit.value))

  const hiddenModuleGroupCount = computed(() =>
    Math.max(0, toolModuleGroups.value.length - visibleToolModuleGroups.value.length),
  )

  function showMoreToolModuleGroups() {
    visibleModuleGroupLimit.value += toolModuleGroupPageSize
  }

  function resetVisibleToolModuleGroups() {
    visibleModuleGroupLimit.value = toolModuleGroupPageSize
  }

  function toolParameterCount(row: ProjectToolInfo): number {
    return row.parameterCount ?? (row.parameters || []).length
  }

  return {
    semanticCompletionPercent,
    linkedToolCount,
    governanceStages,
    stageAdvice,
    workbenchSummaryCards,
    toolModuleGroups,
    visibleToolModuleGroups,
    hiddenModuleGroupCount,
    showMoreToolModuleGroups,
    resetVisibleToolModuleGroups,
    toolParameterCount,
  }
}
