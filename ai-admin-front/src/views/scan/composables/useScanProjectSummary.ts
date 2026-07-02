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

  const sensitiveRiskCount = computed(() =>
    deps.tools.value.filter((item) => item.sensitiveData?.types?.length || item.sensitiveData?.summary).length,
  )

  const removedToolCount = computed(() => deps.tools.value.filter((item) => item.removedFromSource).length)

  const workbenchSummaryCards = computed(() => [
    {
      label: '扫描状态',
      value: deps.project.value ? formatScanStatusLabel(deps.project.value.status) : '-',
      desc: `接口 ${deps.tools.value.length || deps.project.value?.toolCount || 0} 个，模块 ${deps.modules.value.length} 个`,
      tone: deps.project.value?.status === 'failed'
        ? 'danger'
        : deps.project.value?.status === 'scanning'
          ? 'warning'
          : 'success',
    },
    {
      label: 'AI 理解质量',
      value: `${semanticCompletionPercent.value}%`,
      desc: `${Math.max(0, deps.tools.value.length - Object.keys(deps.toolDocMap.value).length)} 个接口待补语义`,
      tone: semanticCompletionPercent.value >= 80 ? 'success' : semanticCompletionPercent.value > 0 ? 'warning' : 'muted',
    },
    {
      label: 'Tool 同步',
      value: `${linkedToolCount.value}/${deps.tools.value.length || 0}`,
      desc: outOfSyncToolCount.value > 0 ? `${outOfSyncToolCount.value} 个接口存在差异` : 'API 与 Tool 关联状态可治理',
      tone: outOfSyncToolCount.value > 0 ? 'warning' : 'success',
    },
    {
      label: '风险提示',
      value: sensitiveRiskCount.value > 0 ? `${sensitiveRiskCount.value} 条` : '无',
      desc: removedToolCount.value > 0 ? `${removedToolCount.value} 个源接口已移除` : '敏感数据与下线状态集中复核',
      tone: sensitiveRiskCount.value > 0 || removedToolCount.value > 0 ? 'danger' : 'muted',
    },
  ])

  const assetSummaryItems = computed(() => {
    const project = deps.project.value
    return [
      deps.projectAccessLabel.value,
      project?.environment || 'default',
      `${deps.tools.value.length || project?.toolCount || 0} API`,
      `${deps.modules.value.length} 模块`,
      `已同步 ${linkedToolCount.value}`,
    ]
  })

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
    workbenchSummaryCards,
    assetSummaryItems,
    toolModuleGroups,
    visibleToolModuleGroups,
    hiddenModuleGroupCount,
    showMoreToolModuleGroups,
    resetVisibleToolModuleGroups,
    toolParameterCount,
  }
}
