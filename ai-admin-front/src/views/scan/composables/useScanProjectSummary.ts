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
  | 'viewOwnerCatalog'
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
        key: 'owner',
        label: '查看所属目录',
        value: hasApi ? '业务方法 / API' : '-',
        desc: '来源发现不等于接纳；请在所属项目目录核对契约与来源状态',
        status: hasApi ? 'active' : 'todo',
      },
      {
        key: 'workflow',
        label: '受控调用与编排',
        value: '显式操作',
        desc: '在资产目录配置连接并受控试调用，再选择到 Workflow 显式发布',
        status: 'todo',
      },
    ]
  })

  const stageAdvice = computed<ApiGovernanceAdvice>(() => {
    const project = deps.project.value
    const isSdkProject = project?.projectKind === 'REGISTERED'
    if (!project) return {
      title: '正在加载来源发现上下文',
      description: '读取项目接入方式与来源记录，不据此推断资产已接纳或可执行。',
      primaryLabel: '刷新', primaryAction: 'refresh',
    }
    if (apiCount.value <= 0) return isSdkProject ? {
      title: '等待业务系统同步方法与 API 来源',
      description: '正常 SDK 同步后，请到所属项目业务方法/API 目录核对契约。',
      primaryLabel: '同步来源', primaryAction: 'importApi',
      secondaryLabel: '刷新同步状态', secondaryAction: 'refresh',
    } : {
      title: '还没有发现来源',
      description: '检查扫描规则并发现 Controller/OpenAPI；接纳与连接配置在 API 目录完成。',
      primaryLabel: '开始发现 API', primaryAction: 'scan',
      secondaryLabel: '配置扫描规则', secondaryAction: 'scanRules',
    }
    return {
      title: project.status === 'failed' ? '来源扫描失败，请核对错误并重新发现' : '来源已发现，请到所属资产目录继续',
      description: removedToolCount.value > 0
        ? `${removedToolCount.value} 个来源已移除；旧图与固定发布不自动迁移，请在目录核对状态后显式重选。`
        : '这里的历史投影关联与 AI 语义不是接纳、连接验证或调用授权；无需人工复制 Tool。',
      primaryLabel: isSdkProject ? '业务方法目录' : 'API 目录', primaryAction: 'viewOwnerCatalog',
      secondaryLabel: '查看来源记录', secondaryAction: 'viewCatalog',
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
      label: '历史投影关联',
      value: `${linkedToolCount.value}/${deps.tools.value.length || 0}`,
      desc: outOfSyncToolCount.value > 0 ? `${outOfSyncToolCount.value} 条历史投影差异，仅供来源核对` : '不代表当前接纳、连接或执行授权',
      tone: outOfSyncToolCount.value > 0 ? 'warning' : 'muted',
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
