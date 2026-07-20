import { describe, expect, it, vi } from 'vitest'
import { ref } from 'vue'
import { useWorkflowStudioPanelValidation } from './useWorkflowStudioPanelValidation'
import { useWorkflowStudioPersistence } from './useWorkflowStudioPersistence'

const saveWorkflowStudio = vi.hoisted(() => vi.fn())

vi.mock('@/api/workflow', () => ({
  getWorkflowStudio: vi.fn(),
  saveWorkflowStudio,
}))

vi.mock('element-plus', () => ({
  ElMessage: { success: vi.fn(), error: vi.fn(), info: vi.fn() },
  ElMessageBox: { confirm: vi.fn() },
}))

describe('useWorkflowStudioPersistence', () => {
  it('blocks saveStudio through the real panel validation gate until JSON is fixed', async () => {
    const validation = useWorkflowStudioPanelValidation()
    validation.setPanelValidation('assign_1', { valid: false, message: 'JSON 无效' })
    const studio = ref({
      workflowId: 'wf-1',
      name: 'Demo',
      graphSpecJson: '{"nodes":[],"edges":[]}',
      canvasJson: '{"nodes":[],"edges":[]}',
    } as any)
    const actions = useWorkflowStudioPersistence({
      workflowId: ref('wf-1'),
      studioReadOnly: ref(false),
      saving: ref(false),
      loading: ref(false),
      studio,
      graphSpecJson: ref('{"nodes":[],"edges":[]}'),
      canvasJson: ref('{"nodes":[],"edges":[]}'),
      nodes: ref([]),
      workflowMeta: {
        name: 'Demo',
        keySlug: '',
        workflowType: 'WORKFLOW',
        description: '',
        defaultModelInstanceId: '',
      },
      visualDirty: ref(false),
      editGeneration: ref(0),
      lastSavedAt: ref(''),
      validation: ref(null),
      aiModelInstanceId: ref(''),
      applyCanvasFromStudio: vi.fn(),
      syncJsonFromCanvas: vi.fn(),
      resetHistorySnapshot: vi.fn(),
      loadCredentialOptions: vi.fn(async () => undefined),
      clearWorkflowDocumentState: vi.fn(),
      ensurePanelValidationClear: validation.ensurePanelValidationClear,
    })

    expect(await actions.saveStudio()).toBeNull()
    expect(saveWorkflowStudio).not.toHaveBeenCalled()

    validation.setPanelValidation('assign_1', { valid: true })
    saveWorkflowStudio.mockResolvedValue({ data: { workflowId: 'wf-1' } })
    expect(await actions.saveStudio()).not.toBeNull()
    expect(saveWorkflowStudio).toHaveBeenCalledTimes(1)
  })
})
