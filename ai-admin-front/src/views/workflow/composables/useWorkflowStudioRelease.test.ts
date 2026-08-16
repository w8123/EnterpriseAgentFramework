import { describe, expect, it, vi } from 'vitest'
import { computed, ref } from 'vue'
import { publishWorkflowVersion, validateWorkflowVersion } from '@/api/workflow'
import { useWorkflowStudioPanelValidation } from './useWorkflowStudioPanelValidation'
import { useWorkflowStudioRelease } from './useWorkflowStudioRelease'

vi.mock('@/api/workflow', () => ({
  publishWorkflowVersion: vi.fn(),
  validateWorkflowVersion: vi.fn(),
}))

vi.mock('element-plus', () => ({
  ElMessage: { success: vi.fn(), error: vi.fn(), warning: vi.fn(), info: vi.fn() },
  ElMessageBox: { confirm: vi.fn() },
}))

describe('useWorkflowStudioRelease', () => {
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
      publishForm: { version: 'v1', rolloutPercent: 100, note: '', publishedBy: '' },
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
      publishForm: { version: 'v1.1.0', rolloutPercent: 100, note: '', publishedBy: 'tester' },
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
