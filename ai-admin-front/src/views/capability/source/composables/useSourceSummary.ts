import { computed, type Ref } from 'vue'
import type { ProjectToolInfo, ScanProject, SourceStatistics } from '@/types/scanProject'

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

export interface ApiGovernanceAdvice {
  title: string
  description: string
  primaryLabel: string
  primaryAction: ApiGovernanceAction
  secondaryLabel?: string
  secondaryAction?: ApiGovernanceAction
}

export interface UseSourceSummaryDeps {
  project: Ref<ScanProject | null>
  tools: Ref<ProjectToolInfo[]>
  statistics?: Ref<SourceStatistics>
}

export function useSourceSummary(deps: UseSourceSummaryDeps) {
  const removedToolCount = computed(() => deps.statistics?.value.removed ?? deps.tools.value.filter((item) => item.removedFromSource).length)

  const stageAdvice = computed<ApiGovernanceAdvice>(() => {
    const project = deps.project.value
    const isSdkProject = project?.projectKind === 'REGISTERED'
    if (!project) return {
      title: '正在加载来源发现上下文',
      description: '读取项目接入方式与来源记录，不据此推断资产已接纳或可执行。',
      primaryLabel: '刷新', primaryAction: 'refresh',
    }
    if ((deps.statistics?.value.total ?? deps.tools.value.length) === 0) return isSdkProject ? {
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

  const toolModuleGroups = computed<ToolModuleGroup[]>(() => {
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
      const fromTool = list[0]?.moduleDisplayName?.trim()
      const label =
        moduleId == null
          ? '未关联模块'
          : (fromTool || `模块 #${moduleId}`)
      return { key, moduleId, label, tools: list }
    })

    return groups.sort((a, b) => {
      if (a.moduleId == null && b.moduleId != null) return 1
      if (a.moduleId != null && b.moduleId == null) return -1
      return a.label.localeCompare(b.label, 'zh-CN')
    })
  })

  function toolParameterCount(row: ProjectToolInfo): number {
    return row.parameterCount ?? (row.parameters || []).length
  }

  return {
    stageAdvice,
    toolModuleGroups,
    toolParameterCount,
  }
}
