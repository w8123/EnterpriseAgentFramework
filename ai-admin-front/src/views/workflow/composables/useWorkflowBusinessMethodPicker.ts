import { computed, onScopeDispose, ref, watch, type Ref } from 'vue'
import { getBusinessMethod, getBusinessMethods } from '@/api/tool'
import type { ToolInfo } from '@/types/tool'
import { isSelectableBusinessMethod } from '@/views/workflow/businessMethodWorkflow'

export type BusinessMethodListStatus = 'idle' | 'loading' | 'ready' | 'empty' | 'error'
export type BusinessMethodDetailStatus = 'idle' | 'loading' | 'ready' | 'missing' | 'error' | 'inconsistent'

export interface UseWorkflowBusinessMethodPickerDeps {
  projectId: Ref<number | null | undefined>
  projectCode: Ref<string | null | undefined>
  nodeId: Ref<string | null | undefined>
  /** Includes the authenticated session so A→B→A remains a fresh request generation. */
  requestScopeKey: Ref<string | null | undefined>
}

function errorStatus(error: unknown) {
  const candidate = error as { response?: { status?: unknown }; status?: unknown }
  const responseStatus = candidate?.response?.status
  return typeof responseStatus === 'number'
    ? responseStatus
    : typeof candidate?.status === 'number' ? candidate.status : null
}

function listErrorMessage(error: unknown) {
  const status = errorStatus(error)
  if (status === 403) return '当前账号无权读取此项目的业务方法，请确认项目范围后重试。'
  if (status === 404) return '当前环境尚未提供业务方法目录，请确认服务已更新后重试。'
  return '业务方法目录暂时无法加载，请保留当前编辑并重试。'
}

function detailErrorMessage(error: unknown) {
  const status = errorStatus(error)
  if (status === 404) return '该已保存引用不再是当前项目中可用的业务方法；已有引用和映射未被修改。'
  if (status === 403) return '当前账号无权读取该业务方法的摘要。'
  return '业务方法摘要暂时无法加载；已有引用和映射未被修改。'
}

export function useWorkflowBusinessMethodPicker(deps: UseWorkflowBusinessMethodPickerDeps) {
  const dialogOpen = ref(false)
  const keyword = ref('')
  const submittedKeyword = ref('')
  const currentPage = ref(1)
  const pageSize = ref(10)
  const rows = ref<ToolInfo[]>([])
  const total = ref(0)
  const listStatus = ref<BusinessMethodListStatus>('idle')
  const listError = ref('')
  const selectedSummary = ref<ToolInfo | null>(null)
  const detailStatus = ref<BusinessMethodDetailStatus>('idle')
  const detailError = ref('')
  const selectedReference = ref('')
  let listGeneration = 0
  let detailGeneration = 0
  let disposed = false

  const scopeKey = computed(() => [
    deps.requestScopeKey.value || '',
    deps.projectId.value ?? '',
    deps.projectCode.value || '',
    deps.nodeId.value || '',
  ].join('\u0000'))

  const invalidRows = computed(() => rows.value.filter((row) => !isSelectableBusinessMethod(row, deps.projectId.value)))
  const hasProjectScope = computed(() => typeof deps.projectId.value === 'number' && deps.projectId.value > 0)
  const emptyDescription = computed(() => submittedKeyword.value
    ? '当前项目中没有匹配的业务方法。'
    : '当前项目暂时没有可供选择的业务方法。')

  function listIsCurrent(generation: number, requestScope: string) {
    return !disposed && dialogOpen.value && generation === listGeneration && requestScope === scopeKey.value
  }

  function detailIsCurrent(generation: number, requestScope: string, reference: string) {
    return !disposed
      && generation === detailGeneration
      && requestScope === scopeKey.value
      && reference === selectedReference.value
  }

  function invalidateListState() {
    listGeneration += 1
    listStatus.value = 'idle'
    listError.value = ''
  }

  function invalidateDetailState(clearSummary = false) {
    detailGeneration += 1
    detailStatus.value = 'idle'
    detailError.value = ''
    if (clearSummary) {
      selectedSummary.value = null
      selectedReference.value = ''
    }
  }

  async function loadList() {
    if (!dialogOpen.value) return false
    if (!hasProjectScope.value) {
      rows.value = []
      total.value = 0
      listStatus.value = 'empty'
      listError.value = '当前 Workflow 未绑定项目，不能跨项目选择业务方法。'
      return false
    }
    const generation = ++listGeneration
    const requestScope = scopeKey.value
    listStatus.value = 'loading'
    listError.value = ''
    try {
      const { data } = await getBusinessMethods({
        projectId: deps.projectId.value!,
        enabled: true,
        current: currentPage.value,
        size: pageSize.value,
        keyword: submittedKeyword.value || undefined,
      })
      if (!listIsCurrent(generation, requestScope)) return false
      rows.value = Array.isArray(data?.records) ? data.records : []
      total.value = Number.isFinite(Number(data?.total)) ? Number(data?.total) : 0
      listStatus.value = rows.value.length ? 'ready' : 'empty'
      return true
    } catch (error) {
      if (!listIsCurrent(generation, requestScope)) return false
      rows.value = []
      total.value = 0
      listStatus.value = 'error'
      listError.value = listErrorMessage(error)
      return false
    }
  }

  function openDialog() {
    if (dialogOpen.value) {
      void loadList()
      return
    }
    dialogOpen.value = true
  }

  function closeDialog() {
    dialogOpen.value = false
  }

  function submitSearch(event?: Pick<KeyboardEvent, 'isComposing'>) {
    if (event?.isComposing) return false
    submittedKeyword.value = keyword.value.trim()
    currentPage.value = 1
    void loadList()
    return true
  }

  function clearSearch() {
    keyword.value = ''
    submittedKeyword.value = ''
    currentPage.value = 1
    void loadList()
  }

  function changePage(page: number) {
    currentPage.value = Math.max(1, Number(page) || 1)
    void loadList()
  }

  function retryList() {
    void loadList()
  }

  function adoptCandidate(candidate: ToolInfo) {
    if (!isSelectableBusinessMethod(candidate, deps.projectId.value)) return false
    detailGeneration += 1
    selectedReference.value = candidate.name
    selectedSummary.value = candidate
    detailStatus.value = 'ready'
    detailError.value = ''
    return true
  }

  async function loadSelectedDetail(reference: string | null | undefined) {
    const name = String(reference || '').trim()
    const preserveCurrentSummary = selectedReference.value === name && selectedSummary.value?.name === name
    detailGeneration += 1
    selectedReference.value = name
    if (!name || !hasProjectScope.value) {
      selectedSummary.value = null
      detailStatus.value = 'idle'
      detailError.value = ''
      return null
    }
    const generation = detailGeneration
    const requestScope = scopeKey.value
    if (!preserveCurrentSummary) selectedSummary.value = null
    detailStatus.value = 'loading'
    detailError.value = ''
    try {
      const { data } = await getBusinessMethod(name)
      if (!detailIsCurrent(generation, requestScope, name)) return null
      if (!isSelectableBusinessMethod(data, deps.projectId.value)) {
        if (!preserveCurrentSummary) selectedSummary.value = null
        detailStatus.value = 'inconsistent'
        detailError.value = '返回的业务方法不满足当前项目、已接纳、启用或来源可用条件，不能作为选择结果。'
        return null
      }
      selectedSummary.value = data
      detailStatus.value = 'ready'
      return data
    } catch (error) {
      if (!detailIsCurrent(generation, requestScope, name)) return null
      const status = errorStatus(error)
      // A generic Tool ref is routinely absent from the business-method
      // endpoint. Keep that normal 404 silent unless a same-reference business
      // candidate is already visible in this editing session.
      if (!preserveCurrentSummary && status === 404) {
        detailStatus.value = 'idle'
        detailError.value = ''
        return null
      }
      if (!preserveCurrentSummary) selectedSummary.value = null
      detailStatus.value = status === 404 ? 'missing' : 'error'
      detailError.value = detailErrorMessage(error)
      return null
    }
  }

  function retrySelectedDetail() {
    void loadSelectedDetail(selectedReference.value)
  }

  watch(dialogOpen, (open) => {
    listGeneration += 1
    if (open) {
      void loadList()
      return
    }
    // The selected-detail request belongs to the Tool node, not to the list
    // dialog. Closing the picker immediately after a selection must not make
    // that detail stale before it can populate the dedicated mapping panel.
    listStatus.value = 'idle'
    listError.value = ''
  })

  watch(scopeKey, () => {
    listGeneration += 1
    detailGeneration += 1
    rows.value = []
    total.value = 0
    listStatus.value = 'idle'
    listError.value = ''
    selectedSummary.value = null
    selectedReference.value = ''
    detailStatus.value = 'idle'
    detailError.value = ''
    if (dialogOpen.value) void loadList()
  })

  onScopeDispose(() => {
    disposed = true
    listGeneration += 1
    detailGeneration += 1
  })

  return {
    dialogOpen,
    keyword,
    submittedKeyword,
    currentPage,
    pageSize,
    rows,
    total,
    listStatus,
    listError,
    invalidRows,
    hasProjectScope,
    emptyDescription,
    selectedSummary,
    detailStatus,
    detailError,
    selectedReference,
    openDialog,
    closeDialog,
    loadList,
    submitSearch,
    clearSearch,
    changePage,
    retryList,
    adoptCandidate,
    loadSelectedDetail,
    retrySelectedDetail,
    invalidateListState,
    invalidateDetailState,
  }
}
