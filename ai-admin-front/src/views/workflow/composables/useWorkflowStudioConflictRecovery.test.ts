import { beforeEach, describe, expect, it, vi } from 'vitest'
import { computed, ref } from 'vue'
import { useWorkflowStudioRelease } from './useWorkflowStudioRelease'
import type { WorkflowWorkingCopyState, WorkflowReleaseValidationItem } from '@/types/workflow'

const api = vi.hoisted(() => ({ publish: vi.fn(), validate: vi.fn(), confirm: vi.fn() }))
vi.mock('@/api/workflow', () => ({ publishWorkflowVersion: api.publish, validateWorkflowVersion: api.validate }))
vi.mock('element-plus', () => ({ ElMessageBox: { confirm: api.confirm },
  ElMessage: { success: vi.fn(), warning: vi.fn(), error: vi.fn(), info: vi.fn() } }))
beforeEach(() => vi.resetAllMocks())

function fixture() {
  const original: WorkflowWorkingCopyState = { workflowId: 'wf-a', keySlug: 'wf-a', status: 'DRAFT', graphSpecJson: '{}', revision: 'rev-1' }
  const workflowId = ref('wf-a'), studio = ref<WorkflowWorkingCopyState | null>(original), editGeneration = ref(0)
  let resolve!: () => void
  const decision = new Promise<void>(done => { resolve = done })
  api.confirm.mockResolvedValueOnce('confirm').mockReturnValue(decision)
  api.validate.mockResolvedValue({ data: { valid: true, errors: [], warnings: [] } })
  api.publish.mockRejectedValue({ response: { status: 409 } })
  const loadStudio = vi.fn(async () => original)
  const actions = useWorkflowStudioRelease({ workflowId, studio, editGeneration, studioReadOnly: ref(false),
    nodes: ref([]), graphLintErrors: computed(() => []), graphLintWarnings: computed(() => []),
    publishing: ref(false), releaseChecking: ref(false), releaseValidationReady: ref(true), publishDialogOpen: ref(true),
    releaseErrors: ref<WorkflowReleaseValidationItem[]>([]), releaseWarnings: ref<WorkflowReleaseValidationItem[]>([]),
    publishForm: { version: 'v1', rolloutPercent: 100, note: '' },
    validateWorkingCopy: vi.fn(async () => ({ valid: true, errors: [] })),
    saveStudio: vi.fn(async () => original), loadStudio,
  })
  return { ...actions, workflowId, studio, editGeneration, loadStudio, resolve, decision }
}

describe('Workflow release conflict recovery', () => {
  it.each(['route', 'edit', 'reload'] as const)('ignores confirmation after %s changes the current working copy', async kind => {
    const state = fixture()
    await state.handlePublishWorkflow()
    expect(api.confirm).toHaveBeenCalledTimes(2)
    if (kind === 'route') state.workflowId.value = 'wf-b'
    if (kind === 'edit') state.editGeneration.value++
    if (kind === 'reload') state.studio.value = { ...state.studio.value!, revision: 'rev-2' }
    state.resolve()
    await state.decision
    await Promise.resolve()
    expect(state.loadStudio).not.toHaveBeenCalled()
  })

  it('reloads after confirming a conflict for the unchanged working copy', async () => {
    const state = fixture()
    await state.handlePublishWorkflow()
    state.resolve()
    await state.decision
    await Promise.resolve()
    expect(state.loadStudio).toHaveBeenCalledOnce()
  })
})
