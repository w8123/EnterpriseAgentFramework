import { computed, onBeforeUnmount, ref, type ComputedRef } from 'vue'
import { getScanProjectDetail, getScanProjectSources } from '@/api/scanProject'
import type { ProjectToolInfo, ScanProject, SourcePageQuery, SourceModuleSummary, SourceStatistics } from '@/types/scanProject'
import type { BusinessCapabilityWorkspace } from '../../businessCapabilityRoutes'

export interface ScanProjectRefreshSideEffects {
  loadSemanticAssets: () => Promise<void>
  applyProjectLoaded: (project: ScanProject) => void
  onToolsLoaded: () => void
  clearSemanticAssets: () => void
}

export interface UseSourceWorkbenchDataDeps {
  sideEffects: () => ScanProjectRefreshSideEffects
  projectId: ComputedRef<number>
  workspace?: ComputedRef<BusinessCapabilityWorkspace>
  sourceQuery?: ComputedRef<SourcePageQuery>
}

export function useSourceWorkbenchData(deps: UseSourceWorkbenchDataDeps) {
  const projectId = deps.projectId
  let sequence = 0
  let disposed = false
  const projectError = ref(''), sourceError = ref('')
  const error = computed(() => projectError.value || sourceError.value)
  const partialError = ref('')
  const project = ref<ScanProject | null>(null)
  const tools = ref<ProjectToolInfo[]>([])
  const projectLoading = ref(false), sourceLoading = ref(false)
  const loading = computed(() => projectLoading.value || sourceLoading.value)
  const semanticLoading = ref(false)
  const sourceStats = ref<SourceStatistics>({ total: 0, removed: 0 })
  const sourceModules = ref<SourceModuleSummary[]>([])
  const sourceTotal = ref(0), sourceCurrent = ref(1), sourceSize = ref(20)
  let sourceSequence = 0
  async function refreshSources() {
    const request = ++sourceSequence
    sourceError.value = ''; tools.value = []; sourceTotal.value = 0
    const query = deps.sourceQuery?.value || { current: 1, size: 20 }
    if (disposed || deps.workspace?.value && deps.workspace.value !== 'sources' || query.defer) { sourceLoading.value = false; return }
    sourceLoading.value = true
    const owner = projectId.value
    try {
      const { data } = await getScanProjectSources(owner, query)
      if (disposed || request !== sourceSequence || owner !== projectId.value) return
      const count = (value: number) => Number.isSafeInteger(value) && value >= 0
      if (!data || !Array.isArray(data.records) || data.records.length > query.size || data.records.some(tool => !Number.isSafeInteger(tool.scanToolId) || tool.scanToolId <= 0
        || tool.projectId != null && tool.projectId !== owner)
        || !count(data.total) || data.total < data.records.length || !Number.isSafeInteger(data.current) || data.current < 1
        || data.size !== query.size || !data.stats || !count(data.stats.total) || !count(data.stats.removed)
        || data.stats.removed > data.stats.total || data.total > data.stats.total || !Array.isArray(data.modules)
        || data.modules.some(module => module.moduleId != null && (!Number.isSafeInteger(module.moduleId) || module.moduleId <= 0)
          || typeof module.moduleDisplayName !== 'string' || !count(module.count))) throw new Error('Invalid source page')
      tools.value = data.records; sourceTotal.value = data.total; sourceCurrent.value = data.current; sourceSize.value = data.size
      sourceStats.value = data.stats; sourceModules.value = data.modules
      deps.sideEffects().onToolsLoaded()
    } catch {
      if (!disposed && request === sourceSequence && owner === projectId.value) sourceError.value = '项目来源读取失败；请重新加载，当前不能据此判断没有来源。'
    } finally { if (!disposed && request === sourceSequence && owner === projectId.value) sourceLoading.value = false }
  }

  async function refreshAll() {
    const current = ++sequence
    if (disposed) return
    projectLoading.value = true
    projectError.value = ''; sourceError.value = ''
    partialError.value = ''
    const fx = deps.sideEffects()
    const owner = projectId.value
    const isCurrent = () => !disposed && current === sequence && owner === projectId.value
    const workspace = deps.workspace?.value || 'sources'
    const needsSources = workspace === 'sources'
    const needsSemantics = workspace === 'sources' || workspace === 'semantics'
    fx.clearSemanticAssets()
    semanticLoading.value = needsSemantics
    if (!needsSources) { sourceSequence++; tools.value = []; sourceLoading.value = false }

    const projectRequest = (async () => {
      try {
        const { data } = await getScanProjectDetail(owner)
        if (!isCurrent()) return
        if (data.id !== owner) throw new Error('Invalid project identity')
        project.value = data
        fx.applyProjectLoaded(data)
      } catch {
        if (!isCurrent()) return
        project.value = null
        projectError.value = '项目接入信息读取失败，请重新加载。'
      } finally { if (isCurrent()) projectLoading.value = false }
    })()
    const sourceRequest = needsSources ? refreshSources() : Promise.resolve()
    const coreRequest = Promise.all([projectRequest, sourceRequest])
    const semanticRequest = needsSemantics ? (async () => {
      try { await fx.loadSemanticAssets() }
      catch {
        if (!isCurrent()) return
        fx.clearSemanticAssets()
        partialError.value = '业务说明读取失败，请重新加载，不能据此判断没有整理结果。'
      } finally { if (isCurrent()) semanticLoading.value = false }
    })() : Promise.resolve()
    await Promise.all([coreRequest, semanticRequest])
  }

  onBeforeUnmount(() => { disposed = true; sequence += 1; sourceSequence += 1 })
  return {
    error,
    partialError,
    projectId,
    project,
    tools,
    loading,
    semanticLoading,
    refreshAll,
    refreshSources, sourceStats, sourceModules, sourceTotal, sourceCurrent, sourceSize,
  }
}
