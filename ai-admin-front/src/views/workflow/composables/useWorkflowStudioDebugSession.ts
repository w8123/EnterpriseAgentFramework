import { computed, getCurrentScope, onScopeDispose, ref, watch, type Ref } from 'vue'
import type { WorkflowDebugRunResult, WorkflowDebugSessionView } from '@/types/workflow'

export interface UseWorkflowStudioDebugSessionDeps {
  ownerScope: Readonly<Ref<string>>
  workflowId: Readonly<Ref<string>>
  workflowKeySlug: Readonly<Ref<string>>
  debugSession: Ref<WorkflowDebugSessionView | null>
  debugRunResult: Ref<WorkflowDebugRunResult | WorkflowDebugSessionView | null>
  selectedDebugStepIndex: Ref<number | null>
  currentDebugNodeId: Ref<string>
  currentTraceId: Ref<string>
  replayTraceInput: Ref<string>
  selectedRecentTraceId: Ref<string>
  refreshWorkflowNodeClasses: () => void
  getDebugSessionById: (sessionId: string) => Promise<WorkflowDebugSessionView>
  getDebugSessionByCreationKey?: (key: string) => Promise<WorkflowDebugSessionView>
}

export function useWorkflowStudioDebugSession({
  ownerScope,
  workflowId,
  workflowKeySlug,
  debugSession,
  debugRunResult,
  selectedDebugStepIndex,
  currentDebugNodeId,
  currentTraceId,
  replayTraceInput,
  selectedRecentTraceId,
  refreshWorkflowNodeClasses,
  getDebugSessionById,
  getDebugSessionByCreationKey,
}: UseWorkflowStudioDebugSessionDeps) {
  const debugSessionScope = computed(() => ownerScope.value
    ? `workflow-studio-debug-session:v2:${encodeURIComponent(ownerScope.value)}:${encodeURIComponent(workflowId.value || workflowKeySlug.value || 'working-copy')}`
    : '')
  let generation = 0
  const debugCreationKey = ref<string>()
  if (getCurrentScope()) onScopeDispose(() => { generation += 1 })

  function discardLegacyReference() {
    if (typeof window === 'undefined') return
    try {
      window.localStorage.removeItem(`workflow-studio-debug-session:${workflowId.value || workflowKeySlug.value || 'working-copy'}`)
    } catch { /* A reference without an account owner is never adopted. */ }
  }

  function rememberDebugSession(sessionId?: string) {
    if (!sessionId || !debugSessionScope.value || typeof window === 'undefined') return
    discardLegacyReference()
    try { window.localStorage.setItem(debugSessionScope.value, sessionId) }
    catch { /* Debugging can continue without persistent browser storage. */ }
  }

  function setDebugCreationKey(key?: string) {
    generation += 1
    const scope = debugSessionScope.value
    debugCreationKey.value = scope ? key : undefined
    if (!scope || typeof window === 'undefined') return
    try {
      if (key) {
        window.localStorage.setItem(`${scope}:creation`, key)
        window.localStorage.removeItem(scope)
      } else window.localStorage.removeItem(`${scope}:creation`)
    } catch { /* Keep the in-memory reference if storage is unavailable. */ }
  }

  function readCreationKey() {
    debugCreationKey.value = undefined
    if (!debugSessionScope.value || typeof window === 'undefined') return
    try { debugCreationKey.value = window.localStorage.getItem(`${debugSessionScope.value}:creation`) || undefined }
    catch { /* Never retain the previous account's reference. */ }
  }
  readCreationKey()

  function forgetDebugSession() {
    setDebugCreationKey(undefined)
    generation += 1
    if (typeof window === 'undefined') return
    if (!debugSessionScope.value) return
    try { window.localStorage.removeItem(debugSessionScope.value) }
    catch { /* No secret or result data is stored in this reference. */ }
  }

  function applyDebugSession(data: WorkflowDebugSessionView, scope = debugSessionScope.value) {
    if (!scope || scope !== debugSessionScope.value) return
    generation += 1
    debugSession.value = data
    debugRunResult.value = data
    rememberDebugSession(data.sessionId)
    setDebugCreationKey(undefined)
  }

  function clearDebugSessionView() {
    debugSession.value = null
    debugRunResult.value = null
    selectedDebugStepIndex.value = null
    currentDebugNodeId.value = ''
    forgetDebugSession()
    refreshWorkflowNodeClasses()
  }

  watch(debugSessionScope, () => {
    generation += 1
    readCreationKey()
    debugSession.value = null
    debugRunResult.value = null
    selectedDebugStepIndex.value = null
    currentDebugNodeId.value = ''
    currentTraceId.value = ''
    replayTraceInput.value = ''
    selectedRecentTraceId.value = ''
    refreshWorkflowNodeClasses()
  }, { flush: 'sync' })

  async function loadStoredDebugSession() {
    if (typeof window === 'undefined' || debugSession.value) return
    const scope = debugSessionScope.value
    const currentGeneration = generation
    if (!scope) return
    const isCurrent = () => scope === debugSessionScope.value && generation === currentGeneration
    discardLegacyReference()
    let sessionId: string | null = null
    try { sessionId = window.localStorage.getItem(scope) }
    catch { /* The in-memory creation reference still supports read-only recovery. */ }
    const creationKey = debugCreationKey.value
    if (!sessionId && !(creationKey && getDebugSessionByCreationKey)) return
    try {
      const data = sessionId
        ? await getDebugSessionById(sessionId)
        : await getDebugSessionByCreationKey!(creationKey!)
      if (!isCurrent()) return
      applyDebugSession(data, scope)
      currentTraceId.value = data.traceId || ''
      replayTraceInput.value = data.traceId || ''
      selectedRecentTraceId.value = data.traceId || ''
      refreshWorkflowNodeClasses()
      if (data.steps?.length) {
        selectedDebugStepIndex.value = data.steps.length - 1
      }
    } catch (error) {
      if (!isCurrent()) return
      const failure = error as { status?: number; response?: { status?: number } }
      if (sessionId && (failure?.response?.status === 404 || failure?.response?.status === 410
        || failure?.status === 404 || failure?.status === 410)) {
        forgetDebugSession()
        return
      }
      throw error
    }
  }

  return {
    debugCreationKey,
    setDebugCreationKey,
    debugSessionScope,
    forgetDebugSession,
    applyDebugSession,
    clearDebugSessionView,
    loadStoredDebugSession,
  }
}
