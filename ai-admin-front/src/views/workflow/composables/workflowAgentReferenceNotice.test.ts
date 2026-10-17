import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ElNotification } from 'element-plus'
import { listWorkflowAgentReferences } from '@/api/agentWorkflowFreshness'
import { notifyStaleAgentReferences } from './workflowAgentReferenceNotice'

vi.mock('@/api/agentWorkflowFreshness', async (original) => ({
  ...await original<typeof import('@/api/agentWorkflowFreshness')>(),
  listWorkflowAgentReferences: vi.fn(),
}))
vi.mock('element-plus', () => ({ ElNotification: vi.fn() }))

const agent = (agentId: string, stale: boolean) => ({
  agentId, agentName: `助手 ${agentId}`, activeConfigVersionId: 1, pinnedWorkflowVersion: '1.0.1', stale,
})

describe('notifyStaleAgentReferences', () => {
  beforeEach(() => vi.resetAllMocks())

  it('names the Agents still on an old version and opens the versions page on click', async () => {
    vi.mocked(listWorkflowAgentReferences).mockResolvedValue({ data: {
      workflowId: 'wf-1', latestWorkflowVersion: '1.0.2', staleCount: 1, agents: [agent('a', true), agent('b', false)],
    } } as any)
    const openVersions = vi.fn()

    await notifyStaleAgentReferences('wf-1', openVersions)

    expect(listWorkflowAgentReferences).toHaveBeenCalledWith('wf-1', { errorFeedback: 'local' })
    const options = vi.mocked(ElNotification).mock.calls[0][0] as unknown as Record<string, any>
    expect(options.title).toBe('1 个 Agent 仍在使用旧版本')
    expect(options.message).toContain('助手 a 发布 Agent 后才会用到 v1.0.2')
    expect(options.message).not.toContain('助手 b')
    options.onClick()
    expect(openVersions).toHaveBeenCalledTimes(1)
  })

  it('stays silent when every Agent is current or the lookup fails', async () => {
    vi.mocked(listWorkflowAgentReferences).mockResolvedValueOnce({ data: {
      workflowId: 'wf-1', staleCount: 0, agents: [agent('b', false)],
    } } as any)
    await notifyStaleAgentReferences('wf-1', vi.fn())
    vi.mocked(listWorkflowAgentReferences).mockRejectedValueOnce(new Error('offline'))
    await notifyStaleAgentReferences('wf-1', vi.fn())
    expect(ElNotification).not.toHaveBeenCalled()
  })
})
