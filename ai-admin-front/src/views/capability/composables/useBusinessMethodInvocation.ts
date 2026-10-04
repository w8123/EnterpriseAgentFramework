import { computed, onBeforeUnmount, ref, toValue, watch, type MaybeRefOrGetter } from 'vue'
import { safeStorageGet, safeStorageSet, normalizedReferences, MAX_RECENT_REFERENCES } from '@/composables/consoleInvocationReferences'
import {
  getBusinessMethodInvocation,
  getBusinessMethodInvocationContext,
  invokeBusinessMethod,
} from '@/api/businessMethodInvocation'
import type {
  BusinessMethodInvocationContext,
  BusinessMethodInvocationOutcome,
  StoredBusinessMethodInvocationReference,
} from '@/types/businessMethodInvocation'
import {
  CONTRACT_HASH_PATTERN,
  INVOCATION_ID_PATTERN,
  isOutcomeUnconfirmed,
  normalizeInvocationOutcome,
} from '../businessMethodInvocation'

const STORAGE_PREFIX = 'reachai.businessMethodInvocation.refs.v1'

export interface UseBusinessMethodInvocationOptions {
  methodName: MaybeRefOrGetter<string>
  contextKey: MaybeRefOrGetter<string>
  /** Stable project owner for persisted query handles; request generations must not survive reload. */
  referenceContextKey?: MaybeRefOrGetter<string>
  actorId: MaybeRefOrGetter<number | null | undefined>
  /** The invocation tab is visible. Hiding a tab is not the same as losing authorization. */
  active: MaybeRefOrGetter<boolean>
  /** Changes immediately invalidate sensitive state and every in-flight response. */
  authorized: MaybeRefOrGetter<boolean>
}

export type InvocationClientState = 'IDLE' | 'SUBMITTING' | 'OUTCOME_UNCONFIRMED' | 'QUERY_NOT_FOUND'

function randomInvocationId() {
  return globalThis.crypto?.randomUUID?.() || ''
}

function responseStatus(error: unknown) {
  const candidate = error as { response?: { status?: unknown } }
  return typeof candidate.response?.status === 'number' ? candidate.response.status : null
}

type ErrorOperation = 'context' | 'submit' | 'query'

function safeErrorText(error: unknown, fallback: string, operation: ErrorOperation) {
  switch (responseStatus(error)) {
    case 401: return '登录状态已失效，已清除当前调用条件、输入与结果。请重新登录后再试。'
    case 403:
      if (operation === 'query') return '当前账号无权读取这条调用记录；无法据此确认前次调用是否已经执行。'
      if (operation === 'submit') return '提交请求未获平台授权；无法仅凭当前响应确认业务系统是否收到调用。'
      return '当前账号无权读取该业务方法的调用条件，页面不会发起调用。'
    case 404:
      if (operation === 'query') return '未找到该调用记录；无法据此确认前次调用是否已经执行，请稍后使用同一调用 ID 查询。'
      if (operation === 'submit') return '业务方法或调用条件已不存在；无法仅凭当前响应确认业务系统是否收到调用。'
      return '未找到该业务方法的调用条件，页面不会发起调用。'
    case 409: return operation === 'query'
      ? '调用记录当前无法读取；请稍后使用同一调用 ID 查询。'
      : '当前业务方法状态或契约已变化，请刷新调用条件后人工确认。'
    case 400: return operation === 'query'
      ? '调用记录查询未通过平台校验；请保留调用 ID 后稍后再查。'
      : '请求未通过平台校验。请检查当前输入与已接受契约。'
    default: return fallback
  }
}

function validContext(value: unknown, methodName: string): value is BusinessMethodInvocationContext {
  const context = value as Partial<BusinessMethodInvocationContext> | null
  return Boolean(
    context
    && context.contractVersion === 1
    && context.assetType === 'BUSINESS_METHOD'
    && context.name === methodName
    && typeof context.projectId === 'number' && context.projectId > 0
    && typeof context.projectCode === 'string' && context.projectCode.trim()
    && typeof context.qualifiedName === 'string' && context.qualifiedName.trim()
    && Array.isArray(context.parameters)
    && typeof context.enabled === 'boolean'
    && typeof context.credentialAvailable === 'boolean'
    && typeof context.businessIdentityRequired === 'boolean'
    && typeof context.executable === 'boolean',
  )
}

function cloneInput(value: Record<string, unknown>) {
  try {
    return JSON.parse(JSON.stringify(value)) as Record<string, unknown>
  } catch {
    return { ...value }
  }
}

/**
 * Keeps only owner-scoped invocation handles. Context reads, attempts and
 * read-only result queries deliberately use separate sequence ownership so one
 * cannot cancel or overwrite another.
 */
export function useBusinessMethodInvocation(options: UseBusinessMethodInvocationOptions) {
  const context = ref<BusinessMethodInvocationContext | null>(null)
  const contextLoading = ref(false)
  const contextError = ref('')
  const actionError = ref('')
  const input = ref<Record<string, unknown>>({})
  const outcome = ref<BusinessMethodInvocationOutcome | null>(null)
  const currentInvocationId = ref('')
  const clientState = ref<InvocationClientState>('IDLE')
  const recentReferences = ref<StoredBusinessMethodInvocationReference[]>([])
  const queryingId = ref('')

  let scopeEpoch = 0
  let contextSequence = 0
  let attemptSequence = 0
  let querySequence = 0
  let recoveredScope = ''
  let activePost: Promise<boolean> | null = null
  let activePostAttempt = 0

  const scopeKey = computed(() => {
    const actorId = toValue(options.actorId)
    const contextKey = String(toValue(options.contextKey) || '').trim()
    const methodName = String(toValue(options.methodName) || '').trim()
    return actorId && contextKey && methodName
      ? `actor:${actorId}|${contextKey}|method:${methodName}`
      : ''
  })
  const referenceScopeKey = computed(() => {
    const actorId = toValue(options.actorId)
    const contextKey = String(toValue(options.referenceContextKey ?? options.contextKey) || '').trim()
    const methodName = String(toValue(options.methodName) || '').trim()
    return actorId && contextKey && methodName
      ? `actor:${actorId}|${contextKey}|method:${methodName}`
      : ''
  })
  const storageKey = computed(() => (
    referenceScopeKey.value ? `${STORAGE_PREFIX}.${encodeURIComponent(referenceScopeKey.value)}` : ''
  ))
  const isVisible = computed(() => Boolean(toValue(options.active)))
  const isAuthorized = computed(() => Boolean(toValue(options.authorized)))
  const canUseNetwork = computed(() => Boolean(scopeKey.value && isVisible.value && isAuthorized.value))
  const isSubmitting = computed(() => clientState.value === 'SUBMITTING')
  const isQuerying = computed(() => Boolean(queryingId.value))
  const queryHandleId = computed(() => outcome.value?.invocationId || currentInvocationId.value)
  const requiresExplicitNewAttempt = computed(() => Boolean(currentInvocationId.value))

  function readReferences() {
    const key = storageKey.value
    if (!key || !isAuthorized.value) return []
    try {
      return normalizedReferences(JSON.parse(safeStorageGet(key)))
    } catch {
      return []
    }
  }

  function writeReferences(next: StoredBusinessMethodInvocationReference[]) {
    recentReferences.value = next.slice(0, MAX_RECENT_REFERENCES)
    const key = storageKey.value
    if (key && isAuthorized.value) safeStorageSet(key, JSON.stringify(recentReferences.value))
  }

  function rememberInvocation(invocationId: string) {
    if (!storageKey.value || !isAuthorized.value || !INVOCATION_ID_PATTERN.test(invocationId)) return
    writeReferences([
      { invocationId, createdAt: Date.now() },
      ...recentReferences.value.filter((entry) => entry.invocationId !== invocationId),
    ])
  }

  function clearSensitiveState() {
    context.value = null
    contextLoading.value = false
    contextError.value = ''
    actionError.value = ''
    input.value = {}
    outcome.value = null
    currentInvocationId.value = ''
    clientState.value = 'IDLE'
    queryingId.value = ''
  }

  function invalidateRequests() {
    scopeEpoch += 1
    contextSequence += 1
    attemptSequence += 1
    querySequence += 1
    activePost = null
    activePostAttempt = 0
  }

  function resetForScope() {
    invalidateRequests()
    recoveredScope = ''
    clearSensitiveState()
    recentReferences.value = readReferences()
  }

  function clearForAccessLoss() {
    invalidateRequests()
    recoveredScope = ''
    clearSensitiveState()
    recentReferences.value = []
  }

  function stillInScope(expectedScope: string, expectedScopeEpoch: number) {
    return scopeEpoch === expectedScopeEpoch && scopeKey.value === expectedScope && isAuthorized.value
  }

  function ownsAttempt(expectedScope: string, expectedScopeEpoch: number, expectedAttempt: number, invocationId: string) {
    return stillInScope(expectedScope, expectedScopeEpoch)
      && attemptSequence === expectedAttempt
      && currentInvocationId.value === invocationId
  }

  /** A reopened tab may make one owner-scoped, read-only recovery query. */
  function recoverLatestReference() {
    const expectedScope = scopeKey.value
    if (!canUseNetwork.value || !expectedScope || recoveredScope === expectedScope) return
    recoveredScope = expectedScope
    const latest = recentReferences.value[0]
    if (latest) void query(latest.invocationId)
  }

  async function loadContext() {
    const methodName = String(toValue(options.methodName) || '').trim()
    const expectedScope = scopeKey.value
    if (!canUseNetwork.value || !methodName || !expectedScope) return false
    const expectedScopeEpoch = scopeEpoch
    const request = ++contextSequence
    contextLoading.value = true
    contextError.value = ''
    try {
      const { data } = await getBusinessMethodInvocationContext(methodName)
      if (!stillInScope(expectedScope, expectedScopeEpoch) || contextSequence !== request) return false
      if (!validContext(data, methodName)) {
        context.value = null
        contextError.value = '业务方法调用条件无效，当前不能试调用。'
        return false
      }
      context.value = data
      return true
    } catch (error) {
      if (!stillInScope(expectedScope, expectedScopeEpoch) || contextSequence !== request) return false
      context.value = null
      contextError.value = safeErrorText(error, '业务方法调用条件暂不可用，请稍后重试。', 'context')
      if (responseStatus(error) === 401) clearForAccessLoss()
      return false
    } finally {
      if (stillInScope(expectedScope, expectedScopeEpoch) && contextSequence === request) contextLoading.value = false
    }
  }

  /** Clears a viewed attempt only after the user explicitly asks to create another one. */
  function beginNewAttempt() {
    if (!currentInvocationId.value || isSubmitting.value) return false
    attemptSequence += 1
    querySequence += 1
    queryingId.value = ''
    outcome.value = null
    currentInvocationId.value = ''
    actionError.value = ''
    clientState.value = 'IDLE'
    return true
  }

  async function submit(confirmedSideEffect: boolean, inputSnapshot: Record<string, unknown>) {
    if (activePost) return activePost
    if (requiresExplicitNewAttempt.value) {
      actionError.value = '当前已有调用记录。请先查询该记录，或明确新建一次试调用。'
      return false
    }
    const currentContext = context.value
    const methodName = String(toValue(options.methodName) || '').trim()
    const expectedScope = scopeKey.value
    const expectedContractHash = String(currentContext?.currentContractHash || '')
    if (!canUseNetwork.value || !currentContext || !methodName || !expectedScope || !CONTRACT_HASH_PATTERN.test(expectedContractHash)) {
      actionError.value = '调用条件尚未准备好，请刷新后再试。'
      return false
    }
    const invocationId = randomInvocationId()
    if (!invocationId) {
      actionError.value = '当前浏览器无法安全生成调用 ID，未提交请求。'
      return false
    }
    const expectedScopeEpoch = scopeEpoch
    const expectedAttempt = ++attemptSequence
    const snapshot = cloneInput(inputSnapshot)
    currentInvocationId.value = invocationId
    outcome.value = null
    actionError.value = ''
    clientState.value = 'SUBMITTING'
    rememberInvocation(invocationId)
    const post = (async () => {
      try {
        const { data } = await invokeBusinessMethod(methodName, {
          invocationId,
          expectedContractHash,
          input: snapshot,
          confirmedSideEffect,
        })
        if (!ownsAttempt(expectedScope, expectedScopeEpoch, expectedAttempt, invocationId)) return false
        const normalized = normalizeInvocationOutcome(data, invocationId)
        if (normalized) {
          outcome.value = normalized
          clientState.value = 'IDLE'
          return true
        }
        if (isOutcomeUnconfirmed(data, invocationId)) {
          clientState.value = 'OUTCOME_UNCONFIRMED'
          actionError.value = '本次调用结果尚未确认，业务系统可能已经执行。请使用同一调用 ID 查询。'
          return false
        }
        clientState.value = 'OUTCOME_UNCONFIRMED'
        actionError.value = '调用响应无效，无法确认是否已经执行。请使用同一调用 ID 查询。'
        return false
      } catch (error) {
        if (!ownsAttempt(expectedScope, expectedScopeEpoch, expectedAttempt, invocationId)) return false
        clientState.value = 'OUTCOME_UNCONFIRMED'
        actionError.value = safeErrorText(error, '提交结果未确认，业务系统可能已经执行。请使用同一调用 ID 查询。', 'submit')
        if (responseStatus(error) === 401) clearForAccessLoss()
        return false
      } finally {
        if (ownsAttempt(expectedScope, expectedScopeEpoch, expectedAttempt, invocationId) && clientState.value === 'SUBMITTING') {
          clientState.value = 'OUTCOME_UNCONFIRMED'
          actionError.value = '提交响应未完成，业务系统可能已经执行。请使用同一调用 ID 查询。'
        }
        if (activePostAttempt === expectedAttempt) activePost = null
      }
    })()
    activePost = post
    activePostAttempt = expectedAttempt
    return post
  }

  async function query(invocationId = queryHandleId.value) {
    const expectedScope = scopeKey.value
    if (!canUseNetwork.value || isSubmitting.value || !invocationId || !INVOCATION_ID_PATTERN.test(invocationId) || queryingId.value) return false
    if (currentInvocationId.value && currentInvocationId.value !== invocationId) {
      actionError.value = '当前正在查看另一条调用记录。请先明确新建一次试调用，或继续查询当前调用 ID。'
      return false
    }
    const expectedScopeEpoch = scopeEpoch
    let expectedAttempt = attemptSequence
    if (!currentInvocationId.value) {
      expectedAttempt = ++attemptSequence
      currentInvocationId.value = invocationId
      outcome.value = null
      clientState.value = 'IDLE'
    }
    const request = ++querySequence
    queryingId.value = invocationId
    actionError.value = ''
    try {
      const { data } = await getBusinessMethodInvocation(invocationId)
      if (!ownsAttempt(expectedScope, expectedScopeEpoch, expectedAttempt, invocationId) || querySequence !== request) return false
      const normalized = normalizeInvocationOutcome(data, invocationId)
      if (!normalized) {
        clientState.value = 'OUTCOME_UNCONFIRMED'
        actionError.value = '调用记录响应无效，无法确认结果。请稍后使用同一调用 ID 再次查询。'
        return false
      }
      outcome.value = normalized
      clientState.value = 'IDLE'
      rememberInvocation(invocationId)
      return true
    } catch (error) {
      if (!ownsAttempt(expectedScope, expectedScopeEpoch, expectedAttempt, invocationId) || querySequence !== request) return false
      clientState.value = responseStatus(error) === 404 ? 'QUERY_NOT_FOUND' : 'OUTCOME_UNCONFIRMED'
      actionError.value = safeErrorText(error, '调用记录暂不可查询。请使用同一调用 ID 稍后再查。', 'query')
      if (responseStatus(error) === 401) clearForAccessLoss()
      return false
    } finally {
      if (stillInScope(expectedScope, expectedScopeEpoch) && querySequence === request && queryingId.value === invocationId) queryingId.value = ''
    }
  }

  /** Replaces a completed/read-only view with a different historic handle, never during a POST. */
  async function queryReference(invocationId: string) {
    if (!invocationId || !INVOCATION_ID_PATTERN.test(invocationId) || isSubmitting.value || queryingId.value) return false
    if (currentInvocationId.value && currentInvocationId.value !== invocationId) {
      attemptSequence += 1
      querySequence += 1
      currentInvocationId.value = ''
      outcome.value = null
      actionError.value = ''
      clientState.value = 'IDLE'
    }
    return query(invocationId)
  }

  watch([scopeKey, referenceScopeKey], () => {
    resetForScope()
    if (canUseNetwork.value) {
      void loadContext()
      recoverLatestReference()
    }
  }, { immediate: true, flush: 'sync' })

  watch(isAuthorized, (authorized) => {
    if (!authorized) {
      clearForAccessLoss()
      return
    }
    resetForScope()
    if (isVisible.value) {
      void loadContext()
      recoverLatestReference()
    }
  }, { flush: 'sync' })

  watch(isVisible, (visible) => {
    if (!visible || !isAuthorized.value) return
    if (!context.value && !contextLoading.value) void loadContext()
    recoverLatestReference()
  })

  onBeforeUnmount(() => {
    invalidateRequests()
    clearSensitiveState()
  })

  return {
    context,
    contextLoading,
    contextError,
    actionError,
    input,
    outcome,
    currentInvocationId,
    clientState,
    recentReferences,
    queryHandleId,
    requiresExplicitNewAttempt,
    isSubmitting,
    isQuerying,
    loadContext,
    beginNewAttempt,
    submit,
    query,
    queryReference,
  }
}
