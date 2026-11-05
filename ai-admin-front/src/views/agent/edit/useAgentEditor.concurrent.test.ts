import { defineComponent, h, ref } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ElMessage, ElMessageBox } from 'element-plus'

const mocks = vi.hoisted(() => ({
  routeId: 'agent-1', create: vi.fn(), update: vi.fn(), getAgent: vi.fn(), list: vi.fn(),
  context: vi.fn(), save: vi.fn(), copy: vi.fn(), publish: vi.fn(), freshness: vi.fn(), fetchProjects: vi.fn(), push: vi.fn(),
  workflowVersions: vi.fn(),
}))
vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { id: mocks.routeId }, query: {} }),
  useRouter: () => ({ push: mocks.push }),
}))
vi.mock('@/api/workflow', () => ({
  createAgent: mocks.create, updateAgent: mocks.update, getAgent: mocks.getAgent,
  listAgentConfigVersions: mocks.list, getAgentDraftEditContext: mocks.context,
  saveAgentDraftIfUnchanged: mocks.save, copyAgentConfigToDraftIfUnchanged: mocks.copy,
  publishAgentConfigIfUnchanged: mocks.publish, searchWorkflows: vi.fn(), listWorkflowVersions: mocks.workflowVersions,
}))
vi.mock('@/api/agentWorkflowFreshness', () => ({
  getAgentWorkflowFreshness: mocks.freshness, publishLatestAgentWorkflowVersions: vi.fn(),
  agentWorkflowFreshnessFailure: () => '更新失败', formatWorkflowVersionLabel: (v: string) => v,
}))
vi.mock('@/store/project', () => ({
  useProjectStore: () => ({ fetchProjects: mocks.fetchProjects, currentProjectId: 12, currentProjectCode: 'orders' }),
}))
vi.mock('@/auth/platformSession', () => ({ platformSessionUser: ref({ userId: 7, username: 'operator',
  permissions: ['agent:read', 'agent:write', 'agent:publish'], permissionGrants: [
    { permissionCode: 'agent:read', scopeType: 'PROJECT', scopeValue: 'orders' },
    { permissionCode: 'agent:write', scopeType: 'PROJECT', scopeValue: 'orders' },
    { permissionCode: 'agent:publish', scopeType: 'PROJECT', scopeValue: 'orders' },
  ] }) }))

import { useAgentEditor } from './useAgentEditor'

const draft = {
  id: 4, agentId: 'agent-1', versionNo: 1, status: 'DRAFT', runtimeType: 'AGENTSCOPE',
  systemPrompt: '原始草稿', modelInstanceId: 'model-1', maxPlanSteps: 6, maxWorkflowCalls: 4,
  maxReplans: 2, totalTimeoutMs: 300000, workflowTimeoutMs: 180000, pageBridgeTimeoutMs: 30000,
  parallelReadOnly: true, policyProfile: 'STANDARD', toolCatalogMode: 'ALLOW_LIST', configJson: '{}',
  tools: [], skills: [], remoteAgents: [],
}
let editor: ReturnType<typeof useAgentEditor>
let wrapper: ReturnType<typeof mount> | undefined

async function mountEditor() {
  wrapper = mount(defineComponent({
    setup() {
      editor = useAgentEditor()
      editor.formRef.value = { validate: vi.fn().mockResolvedValue(true) } as never
      return () => h('div')
    },
  }))
  await flushPromises()
}

beforeEach(() => {
  vi.clearAllMocks()
  mocks.routeId = 'agent-1'
  mocks.fetchProjects.mockResolvedValue([{ id: 12, projectCode: 'orders' }])
  mocks.getAgent.mockResolvedValue({ data: { id: 'agent-1', name: '订单助手', keySlug: 'orders-agent',
    projectId: 12, projectCode: 'orders', visibility: 'PROJECT', enabled: true } })
  mocks.list.mockResolvedValue({ data: [draft] })
  mocks.context.mockResolvedValue({ data: { agentId: 'agent-1', projectId: 12, projectCode: 'orders',
    revision: 'a'.repeat(64), base: draft } })
  mocks.freshness.mockResolvedValue({ data: { tools: [] } })
  mocks.update.mockResolvedValue({ data: {} })
  mocks.create.mockResolvedValue({ data: { id: 'created-agent' } })
  mocks.push.mockResolvedValue(undefined)
})

afterEach(() => { wrapper?.unmount(); wrapper = undefined; vi.restoreAllMocks() })

describe('human and AI Agent draft collaboration', () => {
  it('uses the published write floor when adding a Workflow', async () => {
    mocks.workflowVersions.mockResolvedValue({ data: [{ status: 'ACTIVE', snapshotJson: JSON.stringify({ businessMethodRiskFloor: 'WRITE' }) }] })
    await mountEditor()
    editor.publishedWorkflows.value = [{ id: 'shop', keySlug: 'shop', name: '取消订单' }]
    editor.workflowPickerVisible.value = true
    editor.workflowPickerSelection.value = ['shop']
    await editor.applyWorkflowPickerSelection()
    expect(editor.workflowTools.value[0]).toMatchObject({ riskLevel: 'WRITE', readOnly: false })
  })

  it('keeps the picker open on version lookup failure and ignores results after cancellation', async () => {
    const error = vi.spyOn(ElMessage, 'error').mockImplementation(() => undefined as never)
    mocks.workflowVersions.mockRejectedValueOnce(new Error('offline'))
    await mountEditor()
    editor.publishedWorkflows.value = [{ id: 'shop', keySlug: 'shop', name: '商城' }]
    editor.workflowPickerVisible.value = true
    editor.workflowPickerSelection.value = ['shop']
    await editor.applyWorkflowPickerSelection()
    expect(editor.workflowTools.value).toEqual([])
    expect(editor.workflowPickerVisible.value).toBe(true)
    expect(error).toHaveBeenCalled()
    let finish!: (value: unknown) => void
    mocks.workflowVersions.mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
    const pending = editor.applyWorkflowPickerSelection()
    editor.workflowPickerVisible.value = false
    finish({ data: [{ status: 'ACTIVE', snapshotJson: JSON.stringify({ businessMethodRiskFloor: 'WRITE' }) }] })
    await pending
    expect(editor.workflowTools.value).toEqual([])
  })

  it('requires an explicit operation type when a published version has no reliable risk', async () => {
    vi.spyOn(ElMessage, 'error').mockImplementation(() => undefined as never)
    mocks.workflowVersions.mockResolvedValue({ data: [{ status: 'ACTIVE', snapshotJson: '{}' }] })
    await mountEditor()
    editor.publishedWorkflows.value = [{ id: 'shop', keySlug: 'shop', name: '商城' }]
    editor.workflowPickerVisible.value = true
    editor.workflowPickerSelection.value = ['shop']
    await editor.applyWorkflowPickerSelection()
    expect(editor.workflowTools.value[0].riskLevel).toBeUndefined()
    expect(await editor.saveDraft()).toBe(false)
    expect(mocks.save).not.toHaveBeenCalled()
    expect(mocks.update).not.toHaveBeenCalled()
  })

  it('does not apply a version response after the selection changes', async () => {
    let finish!: (value: unknown) => void
    mocks.workflowVersions.mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
    await mountEditor()
    editor.publishedWorkflows.value = [{ id: 'shop', keySlug: 'shop', name: '商城' }]
    editor.workflowPickerVisible.value = true
    editor.workflowPickerSelection.value = ['shop']
    const pending = editor.applyWorkflowPickerSelection()
    editor.workflowPickerSelection.value = []
    finish({ data: [{ status: 'ACTIVE', snapshotJson: JSON.stringify({ businessMethodRiskFloor: 'WRITE' }) }] })
    await pending
    expect(editor.workflowTools.value).toEqual([])
    expect(editor.workflowPickerVisible.value).toBe(true)
  })

  it('keeps unsaved human input and reports a conflict when the shared draft changed', async () => {
    const error = vi.spyOn(ElMessage, 'error').mockImplementation(() => undefined as never)
    mocks.save.mockRejectedValue({ response: { status: 409, data: { code: 'AGENT_DRAFT_CHANGED' } } })
    await mountEditor()
    editor.supervisor.systemPrompt = '人工未保存的修改'
    expect(await editor.saveDraft()).toBe(false)
    expect(mocks.save).toHaveBeenCalledWith('agent-1', 'a'.repeat(64), expect.objectContaining({ systemPrompt: '人工未保存的修改' }))
    expect(editor.supervisor.systemPrompt).toBe('人工未保存的修改')
    expect(error).toHaveBeenCalledWith(expect.objectContaining({
      message: expect.stringContaining('请先复制需要保留的内容'), duration: 0, showClose: true,
    }))
  })

  it('uses the returned revision on the next save', async () => {
    mocks.save.mockResolvedValue({ data: { revision: 'b'.repeat(64), base: { ...draft, systemPrompt: '人工修改' } } })
    await mountEditor()
    editor.supervisor.systemPrompt = '人工修改'
    expect(await editor.saveDraft()).toBe(true)
    expect(await editor.saveDraft()).toBe(true)
    expect(mocks.save.mock.calls.map(call => call[1])).toEqual(['a'.repeat(64), 'b'.repeat(64)])
  })

  it('does not create a second Agent if its initial draft save has an uncertain outcome', async () => {
    mocks.routeId = 'new'
    mocks.context.mockResolvedValue({ data: { agentId: 'created-agent', projectId: 12, projectCode: 'orders',
      revision: 'c'.repeat(64), base: null } })
    mocks.save.mockRejectedValue(new Error('连接中断'))
    vi.spyOn(ElMessage, 'error').mockImplementation(() => undefined as never)
    await mountEditor()
    editor.form.name = '新助手'
    editor.form.keySlug = 'new-agent'
    expect(await editor.saveDraft()).toBe(false)
    expect(await editor.saveDraft()).toBe(false)
    expect(mocks.create).toHaveBeenCalledTimes(1)
    expect(mocks.save.mock.calls.map(call => call[1])).toEqual(['c'.repeat(64), 'c'.repeat(64)])
  })

  it('does not replace the visible human edits when a version copy conflicts', async () => {
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    const error = vi.spyOn(ElMessage, 'error').mockImplementation(() => undefined as never)
    mocks.copy.mockRejectedValue({ response: { status: 409, data: { code: 'AGENT_DRAFT_CHANGED' } } })
    await mountEditor()
    editor.supervisor.systemPrompt = '尚未保存的内容'
    await editor.handleCopyVersion(draft as never)
    expect(mocks.copy).toHaveBeenCalledWith('agent-1', 4, 'a'.repeat(64))
    expect(editor.supervisor.systemPrompt).toBe('尚未保存的内容')
    expect(error).toHaveBeenCalledWith(expect.objectContaining({
      message: expect.stringContaining('草稿已被其他人或 AI 修改'), duration: 0,
    }))
  })

  it('publishes only the revision saved after the human confirmation', async () => {
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    const error = vi.spyOn(ElMessage, 'error').mockImplementation(() => undefined as never)
    mocks.save.mockResolvedValue({ data: { revision: 'b'.repeat(64), base: draft } })
    mocks.publish.mockRejectedValue({ response: { status: 409, data: { code: 'AGENT_DRAFT_CHANGED' } } })
    await mountEditor()
    await editor.handlePublish()
    expect(mocks.publish).toHaveBeenCalledWith('agent-1', 4, 'b'.repeat(64))
    expect(error).toHaveBeenCalledWith(expect.objectContaining({
      message: expect.stringContaining('发布已停止'), duration: 0,
    }))
  })
})
