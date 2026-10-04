import { beforeEach, describe, expect, it, vi } from 'vitest'
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { publishWorkflowVersion, validateWorkflowVersion } from '@/api/workflow'
import { useWorkflowStudioPanelValidation } from './useWorkflowStudioPanelValidation'
import { useWorkflowStudioRelease } from './useWorkflowStudioRelease'

const confirmRelease = vi.hoisted(() => vi.fn())

vi.mock('@/api/workflow', () => ({
  publishWorkflowVersion: vi.fn(),
  validateWorkflowVersion: vi.fn(),
}))

vi.mock('element-plus', () => ({
  ElMessage: { success: vi.fn(), error: vi.fn(), warning: vi.fn(), info: vi.fn() },
  ElMessageBox: { confirm: confirmRelease },
}))

beforeEach(() => vi.resetAllMocks())

function failedPublish(error: unknown) {
  vi.mocked(validateWorkflowVersion).mockResolvedValue({
    data: { valid: true, errors: [], warnings: [] },
  } as any)
  vi.mocked(publishWorkflowVersion).mockRejectedValue(error)
  confirmRelease.mockResolvedValue('confirm')
  const publishing = ref(false)
  const publishDialogOpen = ref(true)
  const publishForm = { version: 'v1.1.0', rolloutPercent: 100, note: '保留发布说明' }
  const loadStudio = vi.fn()
  const syncPublishedPageAssistant = vi.fn()
  const actions = useWorkflowStudioRelease({
    workflowId: ref('wf-orders'),
    studioReadOnly: ref(false),
    studio: ref({ workflowId: 'wf-orders', keySlug: 'orders' } as any),
    nodes: ref([]),
    graphLintErrors: computed(() => []),
    graphLintWarnings: computed(() => []),
    editGeneration: ref(0),
    publishing,
    releaseChecking: ref(false),
    releaseValidationReady: ref(true),
    publishDialogOpen,
    releaseErrors: ref([]),
    releaseWarnings: ref([]),
    publishForm,
    validateWorkingCopy: vi.fn(),
    saveStudio: vi.fn(async () => ({ workflowId: 'wf-orders', revision: 'revision-1' } as any)),
    loadStudio,
    syncPublishedPageAssistant,
  })
  return { ...actions, publishing, publishDialogOpen, publishForm, loadStudio, syncPublishedPageAssistant }
}

describe('useWorkflowStudioRelease', () => {
  it('explains an API-source publication rejection and preserves the release form without retrying', async () => {
    const actions = failedPublish({ response: { status: 400, data: { message: 'HTTP_API_SOURCE_NOT_READY' } } })

    await actions.handlePublishWorkflow()

    expect(ElMessage.error).toHaveBeenLastCalledWith(
      '发布已取消，本次未产生新版本。引用的 API 来源冲突或未确认，请到 API 详情核对来源，修正并重新扫描后再发布。',
    )
    const messages = vi.mocked(ElMessage.error).mock.calls
    expect(String(messages[messages.length - 1]?.[0])).not.toContain('HTTP_API_SOURCE_NOT_READY')
    expect(publishWorkflowVersion).toHaveBeenCalledTimes(1)
    expect(publishWorkflowVersion).toHaveBeenCalledWith('wf-orders', {
      version: 'v1.1.0', rolloutPercent: 100, note: '保留发布说明', baseRevision: 'revision-1',
    }, { errorFeedback: 'local' })
    expect(actions.publishDialogOpen.value).toBe(true)
    expect(actions.publishing.value).toBe(false)
    expect(actions.publishForm).toEqual({ version: 'v1.1.0', rolloutPercent: 100, note: '保留发布说明' })
    expect(actions.loadStudio).not.toHaveBeenCalled()
    expect(actions.syncPublishedPageAssistant).not.toHaveBeenCalled()
    expect(ElMessage.success).not.toHaveBeenCalled()
  })

  it.each([
    [{ response: { status: 400, data: { message: 'OTHER_RELEASE_ERROR' } } }, 'OTHER_RELEASE_ERROR'],
    [{ response: { status: 403, data: { message: 'HTTP_API_SOURCE_NOT_READY' } } }, 'HTTP_API_SOURCE_NOT_READY'],
    [new Error('网络连接断开'), '网络连接断开'],
  ])('preserves the existing generic publication error semantics for %j', async (error, message) => {
    const actions = failedPublish(error)

    await actions.handlePublishWorkflow()

    expect(ElMessage.error).toHaveBeenLastCalledWith('发布 Workflow 失败：' + message)
    expect(publishWorkflowVersion).toHaveBeenCalledTimes(1)
    expect(actions.publishDialogOpen.value).toBe(true)
    expect(ElMessage.success).not.toHaveBeenCalled()
  })

  it('blocks publish validation through the real panel validation gate until JSON is fixed', async () => {
    const validation = useWorkflowStudioPanelValidation()
    validation.setPanelValidation('assign_1', { valid: false, message: 'JSON 无效' })
    const validateWorkingCopy = vi.fn(async () => ({ valid: true, errors: [], warnings: [] }))
    const publishDialogOpen = ref(false)
    const actions = useWorkflowStudioRelease({
      workflowId: ref('wf-1'),
      studioReadOnly: ref(false),
      studio: ref({ workflowId: 'wf-1', keySlug: 'demo' } as any),
      nodes: ref([]),
      graphLintErrors: computed(() => []),
      graphLintWarnings: computed(() => []),
      editGeneration: ref(0),
      publishing: ref(false),
      releaseChecking: ref(false),
      releaseValidationReady: ref(false),
      publishDialogOpen,
      releaseErrors: ref([]),
      releaseWarnings: ref([]),
      publishForm: { version: 'v1', rolloutPercent: 100, note: '' },
      validateWorkingCopy,
      saveStudio: vi.fn(),
      loadStudio: vi.fn(),
      ensurePanelValidationClear: validation.ensurePanelValidationClear,
    })

    await actions.publishWorkflow()
    expect(publishDialogOpen.value).toBe(false)
    expect(validateWorkingCopy).not.toHaveBeenCalled()

    validation.setPanelValidation('assign_1', { valid: true })
    await actions.publishWorkflow()
    expect(publishDialogOpen.value).toBe(true)
    expect(validateWorkingCopy).toHaveBeenCalledTimes(1)
  })

  it('synchronizes a published page assistant before reporting release success', async () => {
    vi.mocked(validateWorkflowVersion).mockResolvedValue({
      data: { valid: true, errors: [], warnings: [] },
    } as any)
    vi.mocked(publishWorkflowVersion).mockResolvedValue({ data: {} } as any)
    const syncPublishedPageAssistant = vi.fn(async () => true)
    const actions = useWorkflowStudioRelease({
      workflowId: ref('wf-1'),
      studioReadOnly: ref(false),
      studio: ref({ workflowId: 'wf-1', keySlug: 'demo', workflowKind: 'PAGE_ASSISTANT' } as any),
      nodes: ref([]),
      graphLintErrors: computed(() => []),
      graphLintWarnings: computed(() => []),
      editGeneration: ref(0),
      publishing: ref(false),
      releaseChecking: ref(false),
      releaseValidationReady: ref(false),
      publishDialogOpen: ref(false),
      releaseErrors: ref([]),
      releaseWarnings: ref([]),
      publishForm: { version: 'v1.1.0', rolloutPercent: 100, note: '' },
      validateWorkingCopy: vi.fn(async () => ({ valid: true, errors: [], warnings: [] })),
      saveStudio: vi.fn(async () => ({ workflowId: 'wf-1', revision: 'revision-1' } as any)),
      loadStudio: vi.fn(async () => ({ workflowId: 'wf-1' } as any)),
      syncPublishedPageAssistant,
    })

    await actions.publishWorkflow()
    await actions.handlePublishWorkflow()

    expect(publishWorkflowVersion).toHaveBeenCalledTimes(1)
    expect(syncPublishedPageAssistant).toHaveBeenCalledTimes(1)
  })
})
