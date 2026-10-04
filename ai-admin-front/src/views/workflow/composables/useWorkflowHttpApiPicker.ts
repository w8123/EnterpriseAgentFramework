import { computed, onScopeDispose, ref, watch, type Ref } from 'vue'
import { getScanProjectDetail } from '@/api/scanProject'
import { getHttpApi, getHttpApiConnection, listHttpApis } from '@/api/httpApi'
import type { HttpApiConnection, HttpApiDetail, HttpApiSummary } from '@/types/httpApi'
import { httpApiReadinessReason } from '@/views/workflow/httpApiWorkflow'

export interface HttpApiPickerDeps {
  projectId: Ref<number | null | undefined>
  projectCode: Ref<string | null | undefined>
  nodeId: Ref<string | null | undefined>
  requestScopeKey: Ref<string | null | undefined>
  selectedAssetId: Ref<number | null | undefined>
}

export interface HttpApiCandidate {
  summary: HttpApiSummary
  detail: HttpApiDetail | null
  connection: HttpApiConnection | null
  reason: string
}

export function useWorkflowHttpApiPicker(deps: HttpApiPickerDeps) {
  const dialogOpen = ref(false)
  const keyword = ref('')
  const submittedKeyword = ref('')
  const currentPage = ref(1)
  const pageSize = ref(10)
  const rows = ref<HttpApiCandidate[]>([])
  const total = ref(0)
  const environment = ref('')
  const status = ref<'idle' | 'loading' | 'ready' | 'empty' | 'error'>('idle')
  const error = ref('')
  const selectedDetail = ref<HttpApiDetail | null>(null)
  const selectedReason = ref('')
  const selectedLoading = ref(false)
  let generation = 0
  let selectedGeneration = 0
  let disposed = false

  const scopeKey = computed(() => [deps.requestScopeKey.value || '', deps.projectId.value ?? '',
    deps.projectCode.value || '', deps.nodeId.value || ''].join('\u0000'))
  const scope = () => ({ projectId: deps.projectId.value, projectCode: deps.projectCode.value,
    environment: environment.value })
  const current = (token: number, key: string) => !disposed && token === generation && key === scopeKey.value
  const selectedCurrent = (token: number, key: string, id: number) => !disposed && token === selectedGeneration
    && key === scopeKey.value && id === deps.selectedAssetId.value

  async function readCandidate(summary: HttpApiSummary): Promise<HttpApiCandidate> {
    try {
      const [{ data: detail }, { data: connection }] = await Promise.all([
        getHttpApi(summary.id), getHttpApiConnection(summary.id),
      ])
      return { summary, detail, connection, reason: httpApiReadinessReason(summary, detail, connection, scope()) }
    } catch {
      return { summary, detail: null, connection: null, reason: 'API 详情或连接状态暂不可读，请重试' }
    }
  }

  async function loadList() {
    if (!dialogOpen.value) return
    const token = ++generation
    const key = scopeKey.value
    rows.value = []; total.value = 0; environment.value = ''; error.value = ''; status.value = 'loading'
    if (!deps.projectId.value || !deps.projectCode.value) {
      status.value = 'error'; error.value = '当前 Workflow 未绑定项目，无法选择 API。'; return
    }
    try {
      const { data: project } = await getScanProjectDetail(deps.projectId.value)
      if (!current(token, key)) return
      if (project.id !== deps.projectId.value || project.projectCode !== deps.projectCode.value
          || !project.environment) {
        status.value = 'error'; error.value = 'Workflow 项目或环境与项目目录不一致，请重新打开。'; return
      }
      environment.value = project.environment
      const { data } = await listHttpApis({ projectId: deps.projectId.value,
        environment: project.environment, sourceStatus: 'ACCEPTED',
        current: currentPage.value, size: pageSize.value, keyword: submittedKeyword.value || undefined })
      if (!current(token, key)) return
      const summaries = Array.isArray(data.records) ? data.records : []
      const candidates = await Promise.all(summaries.map(readCandidate))
      if (!current(token, key)) return
      rows.value = candidates
      total.value = Number(data.total) || 0
      status.value = candidates.length ? 'ready' : 'empty'
    } catch {
      if (!current(token, key)) return
      rows.value = []; total.value = 0; status.value = 'error'
      error.value = 'API 候选暂时无法加载，请保留当前编辑并重试。'
    }
  }

  async function selectCandidate(candidate: HttpApiCandidate) {
    if (candidate.reason) return null
    const token = ++generation
    const key = scopeKey.value
    try {
      const fresh = await readCandidate(candidate.summary)
      if (!current(token, key) || !dialogOpen.value) return null
      if (fresh.reason || !fresh.detail) {
        rows.value = rows.value.map((row) => row.summary.id === candidate.summary.id ? fresh : row)
        return null
      }
      return fresh.detail
    } catch { return null }
  }

  async function loadSelectedDetail() {
    const id = deps.selectedAssetId.value
    const token = ++selectedGeneration
    const key = scopeKey.value
    selectedDetail.value = null; selectedReason.value = ''
    if (!id) return
    if (!deps.projectId.value || !deps.projectCode.value) {
      selectedReason.value = '当前 Workflow 未绑定项目；已有 API 引用未被修改。'
      return
    }
    selectedLoading.value = true
    try {
      const { data: project } = await getScanProjectDetail(deps.projectId.value)
      if (!selectedCurrent(token, key, id)) return
      if (project.id !== deps.projectId.value || project.projectCode !== deps.projectCode.value
          || !project.environment) {
        selectedReason.value = 'Workflow 项目或环境已变化；已有 API 引用未被修改。'; return
      }
      environment.value = project.environment
      const { data: detail } = await getHttpApi(id)
      if (!selectedCurrent(token, key, id)) return
      const { data: connection } = await getHttpApiConnection(id)
      if (!selectedCurrent(token, key, id)) return
      const reason = httpApiReadinessReason(detail.summary, detail, connection, scope())
      if (reason) selectedReason.value = `${reason}；已有 API 引用未被修改。`
      selectedDetail.value = detail
    } catch {
      if (selectedCurrent(token, key, id)) selectedReason.value = '已保存 API 摘要暂时无法加载；已有引用和映射未被修改。'
    } finally {
      if (selectedCurrent(token, key, id)) selectedLoading.value = false
    }
  }

  function submitSearch(event?: Pick<KeyboardEvent, 'isComposing'>) {
    if (event?.isComposing) return
    submittedKeyword.value = keyword.value.trim(); currentPage.value = 1; void loadList()
  }
  function clearSearch() { keyword.value = ''; submittedKeyword.value = ''; currentPage.value = 1; void loadList() }
  function changePage(page: number) { currentPage.value = Math.max(1, page); void loadList() }
  function closeDialog() { dialogOpen.value = false; generation += 1 }

  watch(dialogOpen, (open) => { if (open) void loadList(); else generation += 1 })
  watch(scopeKey, () => {
    generation += 1; selectedGeneration += 1; rows.value = []; total.value = 0
    environment.value = ''; status.value = 'idle'; selectedDetail.value = null; selectedReason.value = ''
    if (dialogOpen.value) void loadList()
    if (deps.selectedAssetId.value) void loadSelectedDetail()
  })
  watch(deps.selectedAssetId, () => { void loadSelectedDetail() }, { immediate: true })
  onScopeDispose(() => { disposed = true; generation += 1; selectedGeneration += 1 })

  return { dialogOpen, keyword, submittedKeyword, currentPage, pageSize, rows, total,
    environment, status, error, selectedDetail, selectedReason, selectedLoading,
    loadList, selectCandidate, loadSelectedDetail, submitSearch, clearSearch, changePage, closeDialog }
}
