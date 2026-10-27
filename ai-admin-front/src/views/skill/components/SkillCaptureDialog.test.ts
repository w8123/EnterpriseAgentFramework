import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import SkillCaptureDialog from './SkillCaptureDialog.vue'

const push = vi.fn()
vi.mock('vue-router', () => ({ useRouter: () => ({ push }) }))

const previewAgentSkillCapture = vi.fn()
const createAgentSkillCapture = vi.fn()
vi.mock('@/api/skill', () => ({
  previewAgentSkillCapture: (...args: unknown[]) => previewAgentSkillCapture(...args),
  createAgentSkillCapture: (...args: unknown[]) => createAgentSkillCapture(...args),
}))
const createWorkflow = vi.fn()
vi.mock('@/api/workflow', () => ({ createWorkflow: (...args: unknown[]) => createWorkflow(...args) }))

const draft = {
  projectCode: 'yudao-erp',
  projectId: 1,
  systemName: '芋道 ERP',
  name: 'yudao-erp-create-customer',
  displayName: '在芋道 ERP 中新建客户',
  description: 'd',
  skillMd: '---\nname: yudao-erp-create-customer\ndescription: "d"\n---\n# 新建客户\n',
  steps: [{ order: 1, toolName: 'yudao_erp_createCustomer', description: '新建客户', riskLevel: 'WRITE',
    requiresApproval: true, fields: [], available: true }],
  warnings: ['已跳过 1 条失败的调用。'],
  workflowInstruction: '按员工的真实操作生成 Workflow：1. 调用业务能力「新建客户」',
  sourceRef: 'mcp-calls:12',
  version: '1.0.0',
}

let wrapper: ReturnType<typeof mount> | undefined

beforeEach(() => {
  push.mockReset()
  previewAgentSkillCapture.mockReset().mockResolvedValue({ data: draft })
  createAgentSkillCapture.mockReset().mockResolvedValue({
    data: { skill: { id: 4, name: 'yudao-erp-create-customer' }, version: { id: 9, version: '1.0.0' }, created: true },
  })
  createWorkflow.mockReset().mockResolvedValue({ data: { id: 'wf-1' } })
})
afterEach(() => { wrapper?.unmount(); wrapper = undefined })

function mountDialog(props: { source: 'MCP_CALLS' | 'RUN'; callLogIds?: number[]; traceId?: string; canCreateWorkflow?: boolean }) {
  wrapper = mount(SkillCaptureDialog, {
    props: { modelValue: true, ...props },
    global: { plugins: [ElementPlus] },
    attachTo: document.body,
  })
  return wrapper
}

describe('SkillCaptureDialog', () => {
  it('previews the selected calls and saves the edited SKILL.md into review', async () => {
    mountDialog({ source: 'MCP_CALLS', callLogIds: [12, 11] })
    await flushPromises()

    expect(previewAgentSkillCapture).toHaveBeenCalledWith({ source: 'MCP_CALLS', callLogIds: [12, 11], traceId: undefined })
    expect(document.body.textContent).toContain('已跳过 1 条失败的调用')
    expect(document.body.textContent).toContain('新建客户（需审批）')

    const save = [...document.body.querySelectorAll('button')].find((button) => button.textContent?.includes('保存并提交审核'))
    save?.click()
    await flushPromises()

    expect(createAgentSkillCapture).toHaveBeenCalledWith(expect.objectContaining({
      projectCode: 'yudao-erp', skillMd: draft.skillMd, sourceRef: 'mcp-calls:12',
    }))
    expect(push).toHaveBeenCalledWith({ path: '/skills', query: { skillId: '4', versionId: '9' } })
  })

  it('creates an empty workflow and hands the run steps to the Studio AI bar', async () => {
    mountDialog({ source: 'RUN', traceId: 't-1', canCreateWorkflow: true })
    await flushPromises()

    expect(previewAgentSkillCapture).toHaveBeenCalledWith({ source: 'RUN', callLogIds: undefined, traceId: 't-1' })
    const button = [...document.body.querySelectorAll('button')].find((item) => item.textContent?.includes('生成 Workflow 草稿'))
    button?.click()
    await flushPromises()

    expect(createWorkflow).toHaveBeenCalledWith(expect.objectContaining({ projectId: 1, projectCode: 'yudao-erp', status: 'DRAFT' }))
    expect(push).toHaveBeenCalledWith({ path: '/workflows/wf-1/studio', query: { aiInstruction: draft.workflowInstruction } })
  })
})
