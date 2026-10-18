import { describe, expect, it, vi } from 'vitest'
import { ref } from 'vue'
import { listWorkflowAgentReferences } from '@/api/agentWorkflowFreshness'
import { useWorkflowStudioEvalLink } from './useWorkflowStudioEvalLink'

vi.mock('@/api/agentWorkflowFreshness', () => ({ listWorkflowAgentReferences: vi.fn() }))

const agent = (agentId: string) => ({ agentId, agentName: agentId, activeConfigVersionId: 1, stale: false })

function setup() {
  const openPath = vi.fn()
  const link = useWorkflowStudioEvalLink({ workflowId: ref('wf-1'), openPath })
  return { link, openPath }
}

describe('useWorkflowStudioEvalLink', () => {
  it('opens the only referencing Agent eval page directly', async () => {
    vi.mocked(listWorkflowAgentReferences).mockResolvedValueOnce({ data: {
      workflowId: 'wf-1', staleCount: 0, agents: [agent('erp-assistant')] } } as never)
    const { link, openPath } = setup()
    await link.openWorkflowEval()
    expect(openPath).toHaveBeenCalledWith('/runops/evals/erp-assistant')
    expect(link.evalTargetOpen.value).toBe(false)
  })

  it('asks which Agent when several or none use the Workflow', async () => {
    vi.mocked(listWorkflowAgentReferences).mockResolvedValueOnce({ data: {
      workflowId: 'wf-1', staleCount: 0, agents: [agent('a'), agent('b')] } } as never)
    const { link, openPath } = setup()
    await link.openWorkflowEval()
    expect(openPath).not.toHaveBeenCalled()
    expect(link.evalTargetOpen.value).toBe(true)
    link.openAgentEval('b')
    expect(openPath).toHaveBeenCalledWith('/runops/evals/b')

    vi.mocked(listWorkflowAgentReferences).mockRejectedValueOnce(new Error('offline'))
    await link.openWorkflowEval()
    expect(link.evalTargetsLoadError.value).toBe(true)
    expect(link.evalTargetOpen.value).toBe(true)
  })
})
