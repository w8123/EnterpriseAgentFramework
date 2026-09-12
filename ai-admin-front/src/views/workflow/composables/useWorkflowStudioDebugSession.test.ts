import { beforeEach, describe, expect, it, vi } from 'vitest'
import { effectScope, ref } from 'vue'
import { useWorkflowStudioDebugSession, type UseWorkflowStudioDebugSessionDeps } from './useWorkflowStudioDebugSession'

function fixture(error?: unknown) {
  const ownerScope = ref('platform:42')
  const getDebugSessionById = error ? vi.fn().mockRejectedValue(error) : vi.fn().mockResolvedValue({
    sessionId: 'admitted-1', status: 'COMPLETED', messages: [], steps: [], traceId: 'trace-1',
  })
  const deps = {
    getDebugSessionByCreationKey: vi.fn().mockResolvedValue({
      sessionId: 'admitted-1', status: 'COMPLETED', messages: [], steps: [],
    }),
    ownerScope,
    workflowId: ref('workflow-1'), workflowKeySlug: ref(''), debugSession: ref(null), debugRunResult: ref(null),
    selectedDebugStepIndex: ref(null), currentDebugNodeId: ref(''), currentTraceId: ref(''),
    replayTraceInput: ref(''), selectedRecentTraceId: ref(''), refreshWorkflowNodeClasses: vi.fn(), getDebugSessionById,
  } as unknown as UseWorkflowStudioDebugSessionDeps
  return { ...useWorkflowStudioDebugSession(deps), deps, getDebugSessionById, ownerScope }
}

const legacyKey = 'workflow-studio-debug-session:workflow-1'
const key = 'workflow-studio-debug-session:v2:platform%3A42:workflow-1'
describe('Workflow Studio saved debug-session recovery', () => {
  beforeEach(() => localStorage.clear())

  it('restores a pending creation after reopening without persisting the request', async () => {
    const f = fixture()
    f.applyDebugSession({ sessionId: 'previous', status: 'COMPLETED' } as never)
    f.setDebugCreationKey('attempt-1')
    expect(localStorage.getItem(key)).toBeNull()
    expect(localStorage.getItem(`${key}:creation`)).toBe('attempt-1')
    const reopened = fixture()
    await reopened.loadStoredDebugSession()
    expect(reopened.deps.getDebugSessionByCreationKey).toHaveBeenCalledWith('attempt-1')
    expect(reopened.getDebugSessionById).not.toHaveBeenCalled()
    expect(localStorage.getItem(key)).toBe('admitted-1')
    expect(localStorage.getItem(`${key}:creation`)).toBeNull()
  })

  it('keeps a pending creation on 404 because admission may still commit', async () => {
    const f = fixture()
    f.setDebugCreationKey('pending')
    vi.mocked(f.deps.getDebugSessionByCreationKey!).mockRejectedValue({ status: 404 })
    await expect(f.loadStoredDebugSession()).rejects.toEqual({ status: 404 })
    expect(localStorage.getItem(`${key}:creation`)).toBe('pending')
  })

  it('discards a creation lookup after switching away and back without deleting either reference', async () => {
    const f = fixture()
    f.setDebugCreationKey('alice-attempt')
    let resolve!: (value: unknown) => void
    vi.mocked(f.deps.getDebugSessionByCreationKey!).mockReturnValue(new Promise(done => { resolve = done }) as never)
    const pending = f.loadStoredDebugSession()
    f.ownerScope.value = 'platform:43'
    expect(f.debugCreationKey.value).toBeUndefined()
    f.setDebugCreationKey('bob-attempt')
    f.ownerScope.value = 'platform:42'
    expect(f.debugCreationKey.value).toBe('alice-attempt')
    resolve({ sessionId: 'late', status: 'COMPLETED', messages: [], steps: [] })
    await pending
    expect(f.deps.debugSession.value).toBeNull()
    expect(localStorage.getItem(`${key}:creation`)).toBe('alice-attempt')
    expect(localStorage.getItem('workflow-studio-debug-session:v2:platform%3A43:workflow-1:creation')).toBe('bob-attempt')
  })

  it.each([new TypeError('network unavailable'), { response: { status: 503 } }])('keeps the saved id when restoration temporarily fails', async (error) => {
    localStorage.setItem(key, 'admitted-1')
    const f = fixture(error)
    await expect(f.loadStoredDebugSession()).rejects.toBe(error)
    expect(localStorage.getItem(key)).toBe('admitted-1')
    const resumed = fixture()
    await resumed.loadStoredDebugSession()
    expect(resumed.getDebugSessionById).toHaveBeenCalledWith('admitted-1')
    expect(resumed.deps.debugSession.value?.status).toBe('COMPLETED')
  })

  it.each([404, 410])('forgets a definitively missing session (%s)', async (status) => {
    localStorage.setItem(key, 'admitted-1')
    await fixture({ response: { status } }).loadStoredDebugSession()
    expect(localStorage.getItem(key)).toBeNull()
  })
})

describe('Workflow Studio debug-session account isolation', () => {
  const accountKey = (owner: string) => `workflow-studio-debug-session:v2:${encodeURIComponent(owner)}:workflow-1`
  beforeEach(() => localStorage.clear())

  it('stores only a reference under the current account scope', () => {
    const f = fixture()
    f.applyDebugSession({ sessionId: 'alice-session', status: 'SUSPENDED' } as never)
    expect(localStorage.getItem(accountKey('platform:42'))).toBe('alice-session')
    expect(localStorage.getItem(legacyKey)).toBeNull()
  })

  it('never adopts an old reference that has no account owner', async () => {
    localStorage.setItem(legacyKey, 'legacy-private-session')
    const f = fixture()
    await f.loadStoredDebugSession()
    expect(f.getDebugSessionById).not.toHaveBeenCalled()
    expect(f.deps.debugSession.value).toBeNull()
  })

  it('discards a late restoration from the previous account', async () => {
    localStorage.setItem(legacyKey, 'alice-session')
    localStorage.setItem(accountKey('platform:42'), 'alice-session')
    const f = fixture()
    let resolve!: (value: unknown) => void
    f.getDebugSessionById.mockReturnValueOnce(new Promise(done => { resolve = done }))
    const restoring = f.loadStoredDebugSession()
    expect(f.getDebugSessionById).toHaveBeenCalledWith('alice-session')
    f.ownerScope.value = 'platform:43'
    resolve({ sessionId: 'alice-session', status: 'COMPLETED', traceId: 'alice-trace', messages: [], steps: [] })
    await restoring
    expect(f.deps.debugSession.value).toBeNull()
    expect(f.deps.currentTraceId.value).toBe('')
    expect(localStorage.getItem(accountKey('platform:43'))).toBeNull()
  })

  it('clears private in-memory state when the account changes', () => {
    const f = fixture()
    f.applyDebugSession({ sessionId: 'alice-session', status: 'SUSPENDED' } as never)
    f.deps.currentTraceId.value = 'alice-trace'
    f.ownerScope.value = 'platform:43'
    expect(f.deps.debugSession.value).toBeNull()
    expect(f.deps.debugRunResult.value).toBeNull()
    expect(f.deps.currentTraceId.value).toBe('')
  })

  it('does not delete another account reference on a late 404', async () => {
    localStorage.setItem(accountKey('platform:42'), 'alice-session')
    localStorage.setItem(accountKey('platform:43'), 'bob-session')
    const f = fixture()
    let reject!: (error: unknown) => void
    f.getDebugSessionById.mockReturnValueOnce(new Promise((_, fail) => { reject = fail }))
    const restoring = f.loadStoredDebugSession()
    f.ownerScope.value = 'platform:43'
    reject({ response: { status: 404 } })
    await restoring
    expect(localStorage.getItem(accountKey('platform:42'))).toBe('alice-session')
    expect(localStorage.getItem(accountKey('platform:43'))).toBe('bob-session')
  })

  it('discards a stale response even when the account changes away and back', async () => {
    localStorage.setItem(accountKey('platform:42'), 'alice-session')
    const f = fixture()
    let resolve!: (value: unknown) => void
    f.getDebugSessionById.mockReturnValueOnce(new Promise(done => { resolve = done }))
    const restoring = f.loadStoredDebugSession()
    f.ownerScope.value = 'platform:43'
    f.ownerScope.value = 'platform:42'
    resolve({ sessionId: 'old-alice-session', status: 'COMPLETED', messages: [], steps: [] })
    await restoring
    expect(f.deps.debugSession.value).toBeNull()
    expect(localStorage.getItem(accountKey('platform:42'))).toBe('alice-session')
  })

  it('does not persist or restore a session without an authenticated account', async () => {
    const f = fixture()
    f.ownerScope.value = ''
    f.applyDebugSession({ sessionId: 'anonymous-session', status: 'COMPLETED' } as never)
    await f.loadStoredDebugSession()
    expect(f.deps.debugSession.value).toBeNull()
    expect(f.getDebugSessionById).not.toHaveBeenCalled()
    expect(localStorage.length).toBe(0)
  })

  it('does not restore an old reference after a new session has been admitted', async () => {
    localStorage.setItem(accountKey('platform:42'), 'old-session')
    const f = fixture()
    let resolve!: (value: unknown) => void
    f.getDebugSessionById.mockReturnValueOnce(new Promise(done => { resolve = done }))
    const restoring = f.loadStoredDebugSession()
    f.forgetDebugSession()
    f.applyDebugSession({ sessionId: 'new-session', status: 'RUNNING' } as never)
    resolve({ sessionId: 'old-session', status: 'COMPLETED', messages: [], steps: [] })
    await restoring
    expect(f.deps.debugSession.value?.sessionId).toBe('new-session')
    expect(localStorage.getItem(accountKey('platform:42'))).toBe('new-session')
  })

  it('does not rewrite storage after the owning component scope is disposed', async () => {
    localStorage.setItem(accountKey('platform:42'), 'old-session')
    const scope = effectScope()
    const f = scope.run(() => fixture())!
    let resolve!: (value: unknown) => void
    f.getDebugSessionById.mockReturnValueOnce(new Promise(done => { resolve = done }))
    const restoring = f.loadStoredDebugSession()
    scope.stop()
    localStorage.setItem(accountKey('platform:42'), 'new-page-session')
    resolve({ sessionId: 'old-session', status: 'COMPLETED', messages: [], steps: [] })
    await restoring
    expect(f.deps.debugSession.value).toBeNull()
    expect(localStorage.getItem(accountKey('platform:42'))).toBe('new-page-session')
  })
})
