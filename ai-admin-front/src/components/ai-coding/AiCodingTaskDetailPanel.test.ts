import { flushPromises, mount } from '@vue/test-utils'
import { ElMessageBox } from 'element-plus'
import { describe, expect, it, vi } from 'vitest'
import type { AiCodingTaskDetail } from '@/types/aiCodingTask'
import AiCodingTaskDetailPanel from './AiCodingTaskDetailPanel.vue'

function taskDetail(): AiCodingTaskDetail {
  return {
    task: {
      taskId: 'task-1',
      projectId: 1,
      projectCode: 'project',
      capabilityKey: 'PROJECT_ONBOARDING',
      taskKind: 'PROJECT_ONBOARDING',
      protocolVersion: 'v1',
      executorProvider: 'CODEX',
      executionMode: 'EXTERNAL_CLIENT',
      title: '项目接入',
      objective: '完成接入',
      accessMode: 'READ_WRITE',
      executionStatus: 'RUNNING',
      resultContractKey: 'report',
      resultContractVersion: 'v1',
      createdAt: '2026-01-01T00:00:00',
      updatedAt: '2026-01-01T00:00:00',
      connection: { status: 'ACTIVE', activatedAt: '2026-01-01T00:00:00' },
      targets: [],
      openQuestions: [],
    },
    readiness: [],
    events: [{ id: 1, eventType: 'HANDOFF_ISSUED', message: '交接包已签发，等待对接', actorType: 'USER', createdAt: '2026-01-01T00:00:00' }],
    questions: [],
    artifacts: [],
  }
}

function render(detail: AiCodingTaskDetail, handoffEnabled?: boolean) {
  return mount(AiCodingTaskDetailPanel, {
    props: { detail, handoffEnabled },
    global: {
      directives: { loading: () => {} },
      stubs: {
        'el-button': { template: '<button><slot /></button>' },
        'el-tag': { template: '<span><slot /></span>' },
        'el-input': true,
        'el-empty': true,
        'el-timeline': { template: '<div><slot /></div>' },
        'el-timeline-item': { template: '<div><slot /></div>' },
      },
    },
  })
}

describe('AiCodingTaskDetailPanel handoff controls', () => {
  it('offers MCP claim-code reissue without a Windows recovery command', () => {
    const wrapper = render(taskDetail())
    expect(wrapper.text()).toContain('重新生成领取码')
    expect(wrapper.text()).not.toContain('复制恢复命令')
    expect(wrapper.text()).toContain('任务领取码已生成')
  })

  it('confirms revoking an active connection before requesting a new claim code', async () => {
    const confirm = vi.spyOn(ElMessageBox, 'confirm')
      .mockResolvedValueOnce('confirm' as Awaited<ReturnType<typeof ElMessageBox.confirm>>)
    try {
      const wrapper = render(taskDetail())
      const button = wrapper.findAll('button').find((item) => item.text() === '重新生成领取码')
      expect(button).toBeDefined()
      await button!.trigger('click')
      await flushPromises()
      expect(confirm).toHaveBeenCalledOnce()
      expect(wrapper.emitted('reissue')).toEqual([['task-1']])
    } finally {
      confirm.mockRestore()
    }
  })

  it('hides claim-code controls when the caller disables reissue', () => {
    const wrapper = render(taskDetail(), false)
    expect(wrapper.text()).not.toContain('生成领取码')
    expect(wrapper.text()).not.toContain('复制恢复命令')
    expect(wrapper.text()).not.toContain('交接包已签发')
    expect(wrapper.text()).toContain('任务领取码已生成')
    expect(wrapper.text()).toContain('取消任务')
  })

  it('retains acceptance verification when handoff controls are hidden', () => {
    const detail = taskDetail()
    detail.task.executionStatus = 'RESULT_APPLIED'
    detail.readiness = [{ key: 'RUNTIME_READY', label: 'Runtime 就绪', status: 'WARN', message: '等待服务' }]
    const wrapper = render(detail, false)
    expect(wrapper.text()).toContain('重新验证')
  })

  it('allows a result with passing checks to request acceptance verification', async () => {
    const detail = taskDetail()
    detail.task.executionStatus = 'RESULT_APPLIED'
    detail.readiness = [{ key: 'RUNTIME_READY', label: '业务服务在线', status: 'PASS', message: '已在线' }]
    const wrapper = render(detail, false)
    expect(wrapper.text()).toContain('等待验收验证')
    expect(wrapper.text()).not.toContain('确认验收通过')
    const button = wrapper.findAll('button').find((item) => item.text() === '重新验证')
    expect(button).toBeDefined()
    await button!.trigger('click')
    expect(wrapper.emitted('verify')).toEqual([['task-1']])
  })

  it('keeps acceptance gated when a ready task has failing checks', () => {
    const detail = taskDetail()
    detail.task.executionStatus = 'ACCEPTANCE_READY'
    detail.readiness = [{ key: 'RUNTIME_READY', label: '业务服务在线', status: 'WARN', message: '等待服务' }]
    const wrapper = render(detail, false)
    expect(wrapper.text()).toContain('重新验证')
    expect(wrapper.text()).not.toContain('确认验收通过')
  })
})
