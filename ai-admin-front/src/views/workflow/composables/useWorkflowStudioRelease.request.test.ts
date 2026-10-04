import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { computed, ref } from 'vue'
import { AxiosError, type AxiosResponse, type InternalAxiosRequestConfig } from 'axios'
import { ElMessage } from 'element-plus'
import { controlRequest } from '@/api/request'
import { publishWorkflowVersion } from '@/api/workflow'
import {
  isPlatformAuthenticated,
  markPlatformSessionAnonymous,
  markPlatformSessionAuthenticated,
  platformSessionState,
  resetPlatformSessionForTest,
} from '@/auth/platformSession'
import { getPlatformSessionId, PLATFORM_CSRF_HEADER, PLATFORM_SESSION_EVENT_KEY } from '@/utils/platformAuth'
import type { WorkflowWorkingCopyState } from '@/types/workflow'
import { useWorkflowStudioRelease } from './useWorkflowStudioRelease'

const confirmRelease = vi.hoisted(() => vi.fn())
vi.mock('element-plus', () => ({
  ElMessage: { success: vi.fn(), error: vi.fn(), warning: vi.fn(), info: vi.fn() },
  ElMessageBox: { confirm: confirmRelease },
}))

// Only the transport boundary and notification sink are replaced. Axios,
// request interceptors, workflow API, session transitions and Studio are real.
const originalAdapter = controlRequest.defaults.adapter
const sessionView = {
  sessionId: 'pls_r1b_test',
  expiresAt: '2030-01-01T00:00:00.000Z',
  principal: { userId: 7, username: 'r1b-test', roles: ['PLATFORM_ADMIN'] },
}
const guidance = '发布已取消，本次未产生新版本。引用的 API 来源冲突或未确认，请到 API 详情核对来源，修正并重新扫描后再发布。'
let assignLogin: ReturnType<typeof vi.spyOn>

function response(config: InternalAxiosRequestConfig, status: number, data: unknown,
  headers: Record<string, string> = {}): AxiosResponse {
  return { config, status, data, headers, statusText: String(status) }
}

function rejectPublication(status: number, message: string, headers: Record<string, string> = {}) {
  const adapter = vi.fn(async (config: InternalAxiosRequestConfig) => {
    if (config.url?.endsWith('/versions/validate')) {
      return response(config, 200, { code: 200, data: { valid: true, errors: [], warnings: [] } })
    }
    throw new AxiosError(message, AxiosError.ERR_BAD_RESPONSE, config, undefined,
      response(config, status, { message }, headers))
  })
  controlRequest.defaults.adapter = adapter
  return adapter
}

function studioRelease() {
  const studio = ref<WorkflowWorkingCopyState | null>({
    workflowId: 'wf-orders', keySlug: 'orders', status: 'DRAFT', graphSpecJson: '{}', revision: 'revision-1',
  })
  const publishing = ref(false)
  const publishDialogOpen = ref(true)
  const publishForm = { version: 'v1.1.0', rolloutPercent: 100, note: '保留发布说明' }
  const loadStudio = vi.fn()
  const syncPublishedPageAssistant = vi.fn()
  const actions = useWorkflowStudioRelease({
    workflowId: ref('wf-orders'), studio, studioReadOnly: ref(false), editGeneration: ref(0),
    nodes: ref([]), graphLintErrors: computed(() => []), graphLintWarnings: computed(() => []),
    publishing, releaseChecking: ref(false), releaseValidationReady: ref(true), publishDialogOpen,
    releaseErrors: ref([]), releaseWarnings: ref([]), publishForm,
    validateWorkingCopy: vi.fn(), saveStudio: vi.fn(async () => studio.value!),
    loadStudio, syncPublishedPageAssistant,
  })
  return { ...actions, publishing, publishDialogOpen, publishForm, loadStudio, syncPublishedPageAssistant }
}

beforeEach(() => {
  vi.resetAllMocks()
  localStorage.clear()
  sessionStorage.clear()
  window.history.replaceState({}, '', '/workflows/wf-orders/studio')
  resetPlatformSessionForTest()
  markPlatformSessionAuthenticated(sessionView)
  confirmRelease.mockResolvedValue('confirm')
  assignLogin = vi.spyOn(window.location, 'assign').mockImplementation(() => undefined)
})

afterEach(() => {
  controlRequest.defaults.adapter = originalAdapter
  vi.restoreAllMocks()
  markPlatformSessionAnonymous(false)
  localStorage.clear()
  sessionStorage.clear()
})

describe('Studio publication through real request interceptors', () => {
  it('emits only actionable guidance for the target 400, with no bare code or duplicate toast', async () => {
    const adapter = rejectPublication(400, 'HTTP_API_SOURCE_NOT_READY')
    const actions = studioRelease()

    await actions.handlePublishWorkflow()

    expect(vi.mocked(ElMessage.error).mock.calls).toEqual([[guidance]])
    expect(adapter.mock.calls.map(([config]) => config.url)).toEqual([
      '/api/workflows/wf-orders/versions/validate', '/api/workflows/wf-orders/versions/publish',
    ])
    const publishConfig = adapter.mock.calls[1][0]
    expect(publishConfig.errorFeedback).toBe('local')
    expect(publishConfig.platformAuthFailure).toBeUndefined()
    expect(publishConfig.headers.get(PLATFORM_CSRF_HEADER)).toBe(sessionView.sessionId)
    expect(actions.publishDialogOpen.value).toBe(true)
    expect(actions.publishing.value).toBe(false)
    expect(actions.publishForm).toEqual({ version: 'v1.1.0', rolloutPercent: 100, note: '保留发布说明' })
    expect(actions.loadStudio).not.toHaveBeenCalled()
    expect(actions.syncPublishedPageAssistant).not.toHaveBeenCalled()
    expect(ElMessage.success).not.toHaveBeenCalled()
    expect(isPlatformAuthenticated.value).toBe(true)
  })

  it.each([
    [400, 'OTHER_RELEASE_ERROR'],
    [403, 'HTTP_API_SOURCE_NOT_READY'],
    [500, 'RELEASE_SERVICE_UNAVAILABLE'],
  ])('keeps the existing Studio failure text exactly once for non-target %s', async (status, message) => {
    const adapter = rejectPublication(status, message)
    const actions = studioRelease()

    await actions.handlePublishWorkflow()

    expect(vi.mocked(ElMessage.error).mock.calls).toEqual([['发布 Workflow 失败：' + message]])
    expect(adapter).toHaveBeenCalledTimes(2)
    expect(actions.publishDialogOpen.value).toBe(true)
    expect(isPlatformAuthenticated.value).toBe(true)
    expect(ElMessage.success).not.toHaveBeenCalled()
  })

  it('does not swallow a network failure or duplicate its Studio feedback', async () => {
    const adapter = rejectPublication(400, 'unused')
    adapter.mockImplementationOnce(async config =>
      response(config, 200, { code: 200, data: { valid: true, errors: [], warnings: [] } }))
      .mockImplementationOnce(async config => { throw new AxiosError('网络连接断开', AxiosError.ERR_NETWORK, config) })
    const actions = studioRelease()

    await actions.handlePublishWorkflow()

    expect(vi.mocked(ElMessage.error).mock.calls).toEqual([['发布 Workflow 失败：网络连接断开']])
    expect(adapter).toHaveBeenCalledTimes(2)
    expect(actions.publishDialogOpen.value).toBe(true)
    expect(ElMessage.success).not.toHaveBeenCalled()
  })

  it('still rejects an unsuccessful ApiResult and delegates its feedback to Studio', async () => {
    const adapter = rejectPublication(400, 'unused')
    adapter.mockImplementationOnce(async config =>
      response(config, 200, { code: 200, data: { valid: true, errors: [], warnings: [] } }))
      .mockImplementationOnce(async config => response(config, 200, { code: 500, message: 'RELEASE_RESULT_ERROR' }))
    const actions = studioRelease()

    await actions.handlePublishWorkflow()

    expect(vi.mocked(ElMessage.error).mock.calls).toEqual([['发布 Workflow 失败：RELEASE_RESULT_ERROR']])
    expect(actions.publishDialogOpen.value).toBe(true)
    expect(ElMessage.success).not.toHaveBeenCalled()
  })

  it('preserves 409 draft recovery without adding a request-layer toast', async () => {
    rejectPublication(409, 'WORKFLOW_DRAFT_CONFLICT')
    confirmRelease.mockResolvedValueOnce('confirm').mockRejectedValueOnce('cancel')
    const actions = studioRelease()

    await actions.handlePublishWorkflow()
    await Promise.resolve()
    await Promise.resolve()

    expect(ElMessage.error).not.toHaveBeenCalled()
    expect(confirmRelease).toHaveBeenCalledTimes(2)
    expect(confirmRelease).toHaveBeenLastCalledWith(
      expect.stringContaining('本次未发布任何版本'), '发布已取消：草稿版本冲突', expect.any(Object),
    )
    expect(actions.loadStudio).not.toHaveBeenCalled()
    expect(actions.publishDialogOpen.value).toBe(true)
  })

  it('clears an explicitly invalid platform session and navigates to login despite local feedback', async () => {
    rejectPublication(401, 'PLATFORM_SESSION_INVALID', { 'x-reachai-auth-failure': 'PLATFORM_SESSION_INVALID' })
    const actions = studioRelease()

    await actions.handlePublishWorkflow()

    expect(platformSessionState.value).toBe('ANONYMOUS')
    expect(getPlatformSessionId()).toBe('')
    expect(JSON.parse(localStorage.getItem(PLATFORM_SESSION_EVENT_KEY)!).type).toBe('LOGOUT')
    expect(assignLogin).toHaveBeenCalledExactlyOnceWith('/login?redirect=%2Fworkflows%2Fwf-orders%2Fstudio')
    expect(vi.mocked(ElMessage.error).mock.calls).toEqual([['发布 Workflow 失败：PLATFORM_SESSION_INVALID']])
    expect(actions.publishDialogOpen.value).toBe(true)
    expect(ElMessage.success).not.toHaveBeenCalled()
  })

  it('still reports an invalid-session 401 when there is no authenticated session to redirect', async () => {
    markPlatformSessionAnonymous(false)
    rejectPublication(401, 'PLATFORM_SESSION_INVALID', { 'x-reachai-auth-failure': 'PLATFORM_SESSION_INVALID' })
    const actions = studioRelease()

    await actions.handlePublishWorkflow()

    expect(platformSessionState.value).toBe('ANONYMOUS')
    expect(assignLogin).not.toHaveBeenCalled()
    expect(vi.mocked(ElMessage.error).mock.calls).toEqual([['发布 Workflow 失败：PLATFORM_SESSION_INVALID']])
  })

  it('does not turn an unclassified downstream 401 into a platform logout', async () => {
    rejectPublication(401, 'DOWNSTREAM_UNAUTHORIZED')
    const actions = studioRelease()

    await actions.handlePublishWorkflow()

    expect(isPlatformAuthenticated.value).toBe(true)
    expect(getPlatformSessionId()).toBe(sessionView.sessionId)
    expect(assignLogin).not.toHaveBeenCalled()
    expect(vi.mocked(ElMessage.error).mock.calls).toEqual([['发布 Workflow 失败：DOWNSTREAM_UNAUTHORIZED']])
  })

  it('retains global feedback for the versions-page two-argument publication API', async () => {
    const adapter = rejectPublication(400, 'HTTP_API_SOURCE_NOT_READY')

    await expect(publishWorkflowVersion('wf-orders', {
      version: 'v2', rolloutPercent: 100, note: '', baseRevision: 'revision-1',
    })).rejects.toMatchObject({ response: { status: 400 } })

    expect(vi.mocked(ElMessage.error).mock.calls).toEqual([['HTTP_API_SOURCE_NOT_READY']])
    expect(adapter.mock.calls[0][0].errorFeedback).toBeUndefined()
    expect(adapter).toHaveBeenCalledTimes(1)
  })

  it('retains default invalid-session feedback when no login redirect can be started', async () => {
    markPlatformSessionAnonymous(false)
    rejectPublication(401, 'PLATFORM_SESSION_INVALID', { 'x-reachai-auth-failure': 'PLATFORM_SESSION_INVALID' })

    await expect(publishWorkflowVersion('wf-orders', {
      version: 'v2', rolloutPercent: 100, note: '', baseRevision: 'revision-1',
    })).rejects.toMatchObject({ response: { status: 401 } })

    expect(platformSessionState.value).toBe('ANONYMOUS')
    expect(assignLogin).not.toHaveBeenCalled()
    expect(vi.mocked(ElMessage.error).mock.calls).toEqual([['PLATFORM_SESSION_INVALID']])
  })

  it('still completes a successful Studio publication through ApiResult unwrapping', async () => {
    const adapter = rejectPublication(400, 'unused')
    adapter.mockImplementationOnce(async config =>
      response(config, 200, { code: 200, data: { valid: true, errors: [], warnings: [] } }))
      .mockImplementationOnce(async config => response(config, 200, { code: 0, data: { version: 'v1.1.0' } }))
    const actions = studioRelease()

    await actions.handlePublishWorkflow()

    expect(ElMessage.error).not.toHaveBeenCalled()
    expect(ElMessage.success).toHaveBeenCalledExactlyOnceWith('已发布 Workflow v1.1.0')
    expect(actions.publishDialogOpen.value).toBe(false)
    expect(actions.publishing.value).toBe(false)
    expect(actions.loadStudio).toHaveBeenCalledOnce()
    expect(actions.syncPublishedPageAssistant).toHaveBeenCalledOnce()
    expect(adapter).toHaveBeenCalledTimes(2)
  })
})
