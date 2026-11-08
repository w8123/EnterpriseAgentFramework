import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { ref, type Ref } from 'vue'
import ElementPlus from 'element-plus'
import { platformSessionUser } from '@/auth/platformSession'
import AiApprovals from './AiApprovals.vue'
import { knowledgeSettingsReview } from './knowledgeSettingsReview'

const mocks = vi.hoisted(() => ({ list: vi.fn(), create: vi.fn(), revoke: vi.fn(),
  listApprovals: vi.fn(), review: vi.fn(), decide: vi.fn() }))
vi.mock('@/api/platformAiConnections', () => ({
  listPlatformAiConnections: mocks.list,
  createPlatformAiConnection: mocks.create,
  revokePlatformAiConnection: mocks.revoke,
}))
vi.mock('@/api/platformAiApprovals', () => ({
  listPlatformAiApprovals: mocks.listApprovals,
  reviewPlatformAiApproval: mocks.review,
  decidePlatformAiApproval: mocks.decide,
}))
vi.mock('@/views/mcp-hub/McpWriteApprovalList.vue', () => ({ default: { name: 'McpWriteApprovalList', template: '<div data-testid="employee-approvals" />' } }))
vi.mock('@/auth/platformSession', () => ({ platformSessionUser: ref({ userId: 7, username: 'operator' }) }))

const owner = platformSessionUser as unknown as Ref<{ userId: number; username: string } | null>
const connection = {
  id: 19, label: '我的 Codex', toolKind: 'CODEX', tokenHint: 'rpi_hint', status: 'ACTIVE',
  createdAt: '2026-10-28T10:00:00', expiresAt: '2026-11-04T10:00:00', lastUsedAt: null,
}
const approval = {
  id: 'approval-1', connectionId: 19, action: 'BUSINESS_METHOD_WRITE', subject: 'orders.create',
  invocationId: '00000000-0000-0000-0000-000000000001', status: 'PENDING', requestDigest: 'review-digest',
  createdAt: '2026-10-29T10:00:00', expiresAt: '2026-10-29T10:30:00',
}
const approvalDetail = { ...approval, arguments: { projectCode: 'orders', projectId: 3,
  declaredSideEffect: 'WRITE', input: { item: 'sensitive-item', amount: 2 } } }
let wrapper: ReturnType<typeof mount> | undefined

function knowledgeArguments() {
  const before: Record<string, unknown> = { name: '协作知识库', description: '审核前的说明', embeddingModelInstanceId: 'embedding', llmModelInstanceId: 'llm',
    rerankModelInstanceId: null, accessMode: 'FILE_GRANTS', chunkSize: 500, chunkOverlap: 50, splitType: 'FIXED', searchMode: 'hybrid', topK: 5,
    similarityThreshold: 0.5, rerankEnabled: false, vectorWeight: 0.7, keywordWeight: 0.3 }
  const changes = { accessMode: 'ALL_CALLERS', topK: 9 }
  return { contractVersion: 1, kbCode: 'kb', knowledgeBaseId: 7, baseRevision: 'a'.repeat(64), dimension: 1536,
    resource: { scope: 'PROJECT', workspaceId: 'team', projectCode: 'orders' }, before, after: { ...before, ...changes }, changes,
    invocationId: approval.invocationId }
}

function button(label: string) {
  const found = [...document.querySelectorAll('button')].reverse()
    .find(item => item.textContent?.trim() === label)
  if (!found) throw new Error(`button not found: ${label}`)
  return found as HTMLButtonElement
}

beforeEach(() => {
  vi.clearAllMocks()
  owner.value = { userId: 7, username: 'operator' }
  mocks.list.mockResolvedValue({ data: [] })
  mocks.create.mockResolvedValue({ data: { connection, token: 'rpi_one_time_secret' } })
  mocks.revoke.mockResolvedValue({ data: { ...connection, status: 'REVOKED' } })
  mocks.listApprovals.mockResolvedValue({ data: [] })
  mocks.review.mockResolvedValue({ data: approvalDetail })
  mocks.decide.mockResolvedValue({ data: { ...approval, status: 'APPROVED' } })
  localStorage.clear()
  sessionStorage.clear()
})

afterEach(() => {
  wrapper?.unmount()
  wrapper = undefined
  document.body.innerHTML = ''
  vi.restoreAllMocks()
})

describe('AI 操作审核', () => {
  it('shows the project and receiving connection for first registration approval', async () => {
    const enrollment = { ...approval, action: 'REGISTRY_ENROLLMENT', subject: 'REGISTRY_ENROLLMENT:orders' }
    mocks.list.mockResolvedValue({ data: [connection] })
    mocks.listApprovals.mockResolvedValue({ data: [enrollment] })
    mocks.review.mockResolvedValue({ data: { ...enrollment, arguments: { contractVersion: 1, projectCode: 'orders',
      ttlHours: 72, invocationId: approval.invocationId } } })
    wrapper = mount(AiApprovals, { attachTo: document.body, global: { plugins: [ElementPlus] } })
    await flushPromises(); button('审核').click(); await flushPromises()
    expect(document.body.textContent).toContain('审核首次注册授权')
    expect(document.body.textContent).toContain('orders')
    expect(document.body.textContent).toContain('我的 Codex')
    expect(document.body.textContent).toContain('有效 72 小时，仅可成功使用一次')
    button('批准一次执行').click(); await flushPromises()
    expect(mocks.decide).toHaveBeenCalledWith('approval-1', 'review-digest', true)
  })

  it('does not approve first registration when the project differs from the subject', async () => {
    const enrollment = { ...approval, action: 'REGISTRY_ENROLLMENT', subject: 'REGISTRY_ENROLLMENT:orders' }
    mocks.listApprovals.mockResolvedValue({ data: [enrollment] })
    mocks.review.mockResolvedValue({ data: { ...enrollment, arguments: { contractVersion: 1, projectCode: 'other',
      ttlHours: 72, invocationId: approval.invocationId } } })
    wrapper = mount(AiApprovals, { attachTo: document.body, global: { plugins: [ElementPlus] } })
    await flushPromises(); button('审核').click(); await flushPromises()
    expect(document.body.textContent).toContain('审核内容不完整')
    expect([...document.querySelectorAll('button')].some(item => item.textContent?.trim() === '批准一次执行')).toBe(false)
  })

  it('shows the complete knowledge settings difference and explicitly warns about expanded retrieval access', async () => {
    const settings = { ...approval, action: 'KNOWLEDGE_SETTINGS_WRITE', subject: 'KNOWLEDGE:kb' }
    mocks.listApprovals.mockResolvedValue({ data: [settings] })
    mocks.review.mockResolvedValue({ data: { ...settings, arguments: knowledgeArguments() } })
    wrapper = mount(AiApprovals, { attachTo: document.body, global: { plugins: [ElementPlus] } })
    await flushPromises(); button('审核').click(); await flushPromises()
    expect(document.body.textContent).toContain('审核知识库设置')
    expect(document.body.textContent).toContain('无需逐文件授权')
    const changes = document.querySelector('[data-testid="knowledge-settings-changes"]')?.textContent ?? ''
    expect(changes).toContain('仅授权文件 → 所有调用方')
    expect(changes).toContain('5 → 9')
    const content = document.querySelector('[data-testid="approval-input"]')?.textContent ?? ''
    expect(content).toContain('"before"'); expect(content).toContain('"after"'); expect(content).toContain('embedding')
    expect(document.body.textContent).not.toContain('调用使用项目测试身份')
    button('批准一次执行').click(); await flushPromises()
    expect(mocks.decide).toHaveBeenCalledWith('approval-1', 'review-digest', true)
    expect(document.body.textContent).not.toContain('审核前的说明')
  })
  it('keeps rejection available but does not approve an incomplete knowledge snapshot', async () => {
    const settings = { ...approval, action: 'KNOWLEDGE_SETTINGS_WRITE', subject: 'KNOWLEDGE:kb' }
    const args = knowledgeArguments(); delete args.before.rerankModelInstanceId
    mocks.listApprovals.mockResolvedValue({ data: [settings] }); mocks.review.mockResolvedValue({ data: { ...settings, arguments: args } })
    wrapper = mount(AiApprovals, { attachTo: document.body, global: { plugins: [ElementPlus] } })
    await flushPromises(); button('审核').click(); await flushPromises()
    expect(document.body.textContent).toContain('审核内容不完整')
    expect([...document.querySelectorAll('button')].some(item => item.textContent?.trim() === '批准一次执行')).toBe(false)
    button('拒绝').click(); await flushPromises()
    expect(mocks.decide).toHaveBeenCalledWith('approval-1', 'review-digest', false)
  })
  it('rejects a knowledge review with a mismatched target, patch, resource or hidden change', () => {
    const valid = knowledgeArguments()
    expect(knowledgeSettingsReview(valid, 'KNOWLEDGE:kb', approval.invocationId)).not.toBeNull()
    expect(knowledgeSettingsReview(valid, 'KNOWLEDGE:other', approval.invocationId)).toBeNull()
    expect(knowledgeSettingsReview({ ...valid, changes: { actorId: '1' } }, 'KNOWLEDGE:kb', approval.invocationId)).toBeNull()
    expect(knowledgeSettingsReview({ ...valid, changes: { topK: 12 } }, 'KNOWLEDGE:kb', approval.invocationId)).toBeNull()
    expect(knowledgeSettingsReview({ ...valid, after: { ...valid.after, llmModelInstanceId: 'hidden-model' } }, 'KNOWLEDGE:kb', approval.invocationId)).toBeNull()
    expect(knowledgeSettingsReview({ ...valid, resource: { ...valid.resource, projectCode: '' } }, 'KNOWLEDGE:kb', approval.invocationId)).toBeNull()
  })

  it('reviews the exact Workflow definition and publication parameters', async () => {
    const publication = { ...approval, action: 'WORKFLOW_PUBLISH', subject: 'WORKFLOW:wf-1' }
    mocks.listApprovals.mockResolvedValue({ data: [publication] })
    mocks.review.mockResolvedValue({ data: { ...publication, arguments: { contractVersion: 1, workflowId: 'wf-1', projectId: 3, projectCode: 'orders',
      baseRevision: '2026-10-29T10:00:00', version: 'v2', note: '新增订单查询', activeVersionId: 7,
      publicationDigest: 'a'.repeat(64), definition: { id: 'wf-1', name: '订单查询', defaultModelInstanceId: 'model-reviewed' }, publishedGraph: '{"nodes":[]}' } } })
    wrapper = mount(AiApprovals, { attachTo: document.body, global: { plugins: [ElementPlus] } })
    await flushPromises(); button('审核').click(); await flushPromises()
    expect(document.body.textContent).toContain('审核 Workflow 发布')
    expect(document.body.textContent).toContain('新增订单查询')
    expect(document.querySelector('[data-testid="approval-input"]')?.textContent).toContain('model-reviewed')
    expect(document.body.textContent).not.toContain('调用使用项目测试身份')
    button('批准一次执行').click(); await flushPromises()
    expect(mocks.decide).toHaveBeenCalledWith('approval-1', 'review-digest', true)
  })
  it('reviews the frozen page copilot target before approving a Page Assistant sync', async () => {
    const sync = { ...approval, action: 'PAGE_ASSISTANT_SYNC', subject: 'WORKFLOW:wf-page' }
    const args = { contractVersion: 1, projectId: 3, projectCode: 'orders', workflowId: 'wf-page', workflowKeySlug: 'orders-page',
      workflowName: '订单页面助手', workflowActiveVersionId: 11, targetExists: true, agentId: 'agent-1', agentKeySlug: 'orders-page-copilot',
      activeConfigVersionNo: 4, activeModelInstanceId: null, attached: false, attachedWorkflowVersionId: null,
      baseRevision: 'c'.repeat(64), createTarget: false, modelInstanceId: null, replaceWorkflowId: null, invocationId: approval.invocationId }
    mocks.listApprovals.mockResolvedValue({ data: [sync] })
    mocks.review.mockResolvedValue({ data: { ...sync, arguments: args } })
    wrapper = mount(AiApprovals, { attachTo: document.body, global: { plugins: [ElementPlus] } })
    await flushPromises()
    expect(document.body.textContent).toContain('同步页面助手 wf-page 到页面副驾驶')
    button('审核').click(); await flushPromises()
    expect(document.body.textContent).toContain('审核同步到页面副驾驶')
    expect(document.body.textContent).toContain('orders-page-copilot（agent-1）')
    expect(document.body.textContent).toContain('尚未接入，本次新增')
    expect(document.body.textContent).not.toContain('声明的副作用')
    button('批准一次执行').click(); await flushPromises()
    expect(mocks.decide).toHaveBeenCalledWith('approval-1', 'review-digest', true)
  })
  it('reviews the frozen Workflow rollback target before approving', async () => {
    const rollback = { ...approval, action: 'WORKFLOW_ROLLBACK', subject: 'WORKFLOW:wf-1' }
    mocks.listApprovals.mockResolvedValue({ data: [rollback] })
    mocks.review.mockResolvedValue({ data: { ...rollback, arguments: { contractVersion: 1, workflowId: 'wf-1', workflowName: '订单查询',
      projectId: 3, projectCode: 'orders', baseRevision: '2026-10-30T10:00:00', versionId: 8, targetVersion: 'v2',
      targetPublishedAt: null, targetPublishedBy: 'platform-user:7', targetGraphSha256: 'a'.repeat(64), activeVersionId: 9,
      invocationId: approval.invocationId } } })
    wrapper = mount(AiApprovals, { attachTo: document.body, global: { plugins: [ElementPlus] } })
    await flushPromises()
    expect(document.body.textContent).toContain('回滚 Workflow wf-1')
    button('审核').click(); await flushPromises()
    expect(document.body.textContent).toContain('审核 Workflow 回滚')
    expect(document.body.textContent).toContain('v2（编号 8）')
    button('批准一次执行').click(); await flushPromises()
    expect(mocks.decide).toHaveBeenCalledWith('approval-1', 'review-digest', true)
  })
  it('reviews the frozen knowledge import preview before approving a commit', async () => {
    const commit = { ...approval, action: 'KNOWLEDGE_IMPORT_COMMIT', subject: 'KNOWLEDGE_IMPORT_JOB:dij_1' }
    mocks.listApprovals.mockResolvedValue({ data: [commit] })
    mocks.review.mockResolvedValue({ data: { ...commit, arguments: { contractVersion: 1, operation: 'COMMIT', jobId: 'dij_1',
      fileId: 'file_1', replaceFileId: null, knowledgeBaseCode: 'kb-orders', fileName: '订单手册.pdf', fileType: 'PDF',
      createTime: '2026-10-30T09:59:00', status: 'PARSED', providerType: 'DOCLING', providerVersion: '2', parsedAt: '2026-10-30T10:00:00',
      preview: { chunkStrategy: 'fixed_length', chunkSize: 500, chunkOverlap: 50, totalChunks: 12, chunksSha256: 'b'.repeat(64) },
      invocationId: approval.invocationId } } })
    wrapper = mount(AiApprovals, { attachTo: document.body, global: { plugins: [ElementPlus] } })
    await flushPromises()
    expect(document.body.textContent).toContain('知识库文件入库 dij_1')
    button('审核').click(); await flushPromises()
    expect(document.body.textContent).toContain('审核知识库文件入库')
    expect(document.body.textContent).toContain('订单手册.pdf（知识库 kb-orders）')
    expect(document.body.textContent).not.toContain('平台级')
    expect(document.body.textContent).not.toContain('声明的副作用')
    button('批准一次执行').click(); await flushPromises()
    expect(mocks.decide).toHaveBeenCalledWith('approval-1', 'review-digest', true)
  })
  it('reviews a frozen Skill version revoke before approving', async () => {
    const change = { ...approval, action: 'SKILL_VERSION_GOVERNANCE', subject: 'SKILL:3:VERSION:8' }
    mocks.listApprovals.mockResolvedValue({ data: [change] })
    mocks.review.mockResolvedValue({ data: { ...change, arguments: { contractVersion: 1, operation: 'REVOKE', skillId: 3, versionId: 8,
      publisher: 'acme', name: 'orders', displayName: '订单技能', visibility: 'PROJECT', projectCode: 'orders', defaultVersionId: 8,
      defaultVersion: '1.1.0', isDefault: true, version: '1.1.0', status: 'PUBLISHED', sourceType: 'UPLOAD', sourceRef: null,
      sourceSha256: 'a'.repeat(64), contentTreeSha256: 'c'.repeat(64), hasScripts: true, declaredLicense: null,
      riskReport: { level: 'MEDIUM' }, decision: null, comment: null, invocationId: approval.invocationId } } })
    wrapper = mount(AiApprovals, { attachTo: document.body, global: { plugins: [ElementPlus] } })
    await flushPromises()
    expect(document.body.textContent).toContain('Skill #3 版本 #8')
    button('审核').click(); await flushPromises()
    expect(document.body.textContent).toContain('审核 Skill 版本撤销')
    expect(document.body.textContent).toContain('订单技能（acme/orders）')
    expect(document.body.textContent).toContain('不能恢复')
    button('批准一次执行').click(); await flushPromises()
    expect(mocks.decide).toHaveBeenCalledWith('approval-1', 'review-digest', true)
  })
  it('reviews a tool permission rule change against the stored rule', async () => {
    const change = { ...approval, action: 'TOOL_ACL_CHANGE', subject: 'TOOL_ACL:5' }
    mocks.listApprovals.mockResolvedValue({ data: [change] })
    mocks.review.mockResolvedValue({ data: { ...change, arguments: { contractVersion: 1, operation: 'UPDATE', aclId: 5,
      before: { roleCode: 'sales', projectId: null, projectCode: null, targetKind: 'TOOL', targetName: 'create_customer', permission: 'ALLOW', note: null, enabled: true, updatedAt: '2026-10-30T10:00:00' },
      after: { roleCode: 'sales', projectId: null, projectCode: 'orders', targetKind: 'TOOL', targetName: 'create_customer', permission: 'DENY', note: null, enabled: true },
      invocationId: approval.invocationId } } })
    wrapper = mount(AiApprovals, { attachTo: document.body, global: { plugins: [ElementPlus] } })
    await flushPromises()
    expect(document.body.textContent).toContain('调用权限规则 #5')
    button('审核').click(); await flushPromises()
    expect(document.body.textContent).toContain('审核调用权限规则修改')
    expect(document.body.textContent).toContain('sales 禁止调用 create_customer（orders）')
    expect(document.body.textContent).not.toContain('平台级（')
    button('批准一次执行').click(); await flushPromises()
    expect(mocks.decide).toHaveBeenCalledWith('approval-1', 'review-digest', true)
  })
  it('reviews a business system credential replacement without any secret on the card', async () => {
    const change = { ...approval, action: 'BUSINESS_SYSTEM_CHANGE', subject: 'BUSINESS_SYSTEM:5' }
    mocks.listApprovals.mockResolvedValue({ data: [change] })
    mocks.review.mockResolvedValue({ data: { ...change, arguments: { contractVersion: 1, operation: 'REGISTRY_CREDENTIAL', projectId: 5,
      before: { name: '订单系统', projectCode: 'orders', registryCredentialConfigured: true },
      after: { appKey: 'orders-app', appSecretSalt: 'a'.repeat(32), appSecretSha256: 'b'.repeat(64) },
      invocationId: approval.invocationId } } })
    wrapper = mount(AiApprovals, { attachTo: document.body, global: { plugins: [ElementPlus] } })
    await flushPromises()
    expect(document.body.textContent).toContain('业务系统 #5')
    button('审核').click(); await flushPromises()
    expect(document.body.textContent).toContain('审核业务系统注册凭据替换')
    expect(document.body.textContent).toContain('订单系统（orders，#5）')
    expect(document.body.textContent).toContain('不在审核单中，指纹 bbbbbbbbbbbb…')
    expect(document.body.textContent).toContain('已配置，将被替换')
    button('批准一次执行').click(); await flushPromises()
    expect(mocks.decide).toHaveBeenCalledWith('approval-1', 'review-digest', true)
  })
  it('does not approve a business system credential approval that carries the secret', async () => {
    const change = { ...approval, action: 'BUSINESS_SYSTEM_CHANGE', subject: 'BUSINESS_SYSTEM:5' }
    mocks.listApprovals.mockResolvedValue({ data: [change] })
    mocks.review.mockResolvedValue({ data: { ...change, arguments: { contractVersion: 1, operation: 'REGISTRY_CREDENTIAL', projectId: 5,
      before: { name: '订单系统', projectCode: 'orders' }, after: { appKey: 'k', appSecret: 'plain', appSecretSha256: 'b'.repeat(64) } } } })
    wrapper = mount(AiApprovals, { attachTo: document.body, global: { plugins: [ElementPlus] } })
    await flushPromises(); button('审核').click(); await flushPromises()
    expect(document.body.textContent).toContain('审核内容不完整')
    expect([...document.querySelectorAll('button')].some(item => item.textContent?.trim() === '批准一次执行')).toBe(false)
  })
  it('reviews a model instance change showing only the new key fingerprint', async () => {
    const change = { ...approval, action: 'MODEL_INSTANCE_CHANGE', subject: 'MODEL_INSTANCE:m1' }
    mocks.listApprovals.mockResolvedValue({ data: [change] })
    mocks.review.mockResolvedValue({ data: { ...change, arguments: { contractVersion: 1, operation: 'UPDATE', modelId: 'm1',
      before: { id: 'm1', name: '对话模型', modelName: 'deepseek-chat', projectCode: null, connection: { baseUrl: 'http://a', apiKey: '******' } },
      after: { name: '对话模型', modelName: 'deepseek-chat', projectCode: null,
        connection: { baseUrl: 'http://b', apiKey: { secretSalt: 'a'.repeat(32), secretSha256: 'd'.repeat(64) } } },
      invocationId: approval.invocationId } } })
    wrapper = mount(AiApprovals, { attachTo: document.body, global: { plugins: [ElementPlus] } })
    await flushPromises()
    expect(document.body.textContent).toContain('模型实例 m1')
    button('审核').click(); await flushPromises()
    expect(document.body.textContent).toContain('审核模型实例修改')
    expect(document.body.textContent).toContain('http://a → http://b')
    expect(document.body.textContent).toContain('新密钥，不在审核单中，指纹 dddddddddddd…')
    button('批准一次执行').click(); await flushPromises()
    expect(mocks.decide).toHaveBeenCalledWith('approval-1', 'review-digest', true)
  })
  it('does not approve a model instance change that carries a plain key', async () => {
    const change = { ...approval, action: 'MODEL_INSTANCE_CHANGE', subject: 'MODEL_INSTANCE:NEW' }
    mocks.listApprovals.mockResolvedValue({ data: [change] })
    mocks.review.mockResolvedValue({ data: { ...change, arguments: { contractVersion: 1, operation: 'CREATE', modelId: null, before: null,
      after: { name: '新模型', connection: { apiKey: 'sk-plain' } } } } })
    wrapper = mount(AiApprovals, { attachTo: document.body, global: { plugins: [ElementPlus] } })
    await flushPromises(); button('审核').click(); await flushPromises()
    expect(document.body.textContent).toContain('审核内容不完整')
    expect([...document.querySelectorAll('button')].some(item => item.textContent?.trim() === '批准一次执行')).toBe(false)
  })
  it('reviews adding models under a provider account', async () => {
    const change = { ...approval, action: 'MODEL_PROVIDER_ACCOUNT_CHANGE', subject: 'MODEL_PROVIDER_ACCOUNT:acc-1' }
    mocks.listApprovals.mockResolvedValue({ data: [change] })
    mocks.review.mockResolvedValue({ data: { ...change, arguments: { contractVersion: 1, operation: 'CREATE_INSTANCES', accountId: 'acc-1',
      before: { id: 'acc-1', name: 'DeepSeek', provider: 'deepseek', instanceCount: 1, connection: { apiKey: '******' } },
      after: { models: [{ modelName: 'deepseek-reasoner', modelType: 'LLM', name: null }] }, invocationId: approval.invocationId } } })
    wrapper = mount(AiApprovals, { attachTo: document.body, global: { plugins: [ElementPlus] } })
    await flushPromises()
    expect(document.body.textContent).toContain('提供商账号 acc-1')
    button('审核').click(); await flushPromises()
    expect(document.body.textContent).toContain('审核提供商账号批量接入模型')
    expect(document.body.textContent).toContain('DeepSeek（deepseek，引用模型 1 个）')
    expect(document.body.textContent).toContain('deepseek-reasoner（LLM）')
    button('批准一次执行').click(); await flushPromises()
    expect(mocks.decide).toHaveBeenCalledWith('approval-1', 'review-digest', true)
  })
  it('reviews an MCP publication publish with its precheck', async () => {
    const change = { ...approval, action: 'MCP_PUBLICATION_CHANGE', subject: 'MCP_PUBLICATION:3' }
    mocks.listApprovals.mockResolvedValue({ data: [change] })
    mocks.review.mockResolvedValue({ data: { ...change, arguments: { contractVersion: 1, operation: 'PUBLISH', publicationId: 3, targetId: null,
      publication: { id: 3, name: '订单工具', state: 'DRAFT' }, items: [{ id: 5, enabled: true }, { id: 6, enabled: false }], revisions: [],
      precheck: { ok: true, toolCount: 1, irreversibleToolCount: 1 }, after: { irreversibleAcknowledged: true }, invocationId: approval.invocationId } } })
    wrapper = mount(AiApprovals, { attachTo: document.body, global: { plugins: [ElementPlus] } })
    await flushPromises()
    expect(document.body.textContent).toContain('MCP 发布 #3')
    button('审核').click(); await flushPromises()
    expect(document.body.textContent).toContain('审核 MCP 发布变更（发布）')
    expect(document.body.textContent).toContain('2 个（启用 1 个）')
    expect(document.body.textContent).toContain('1 个工具，其中不可逆 1 个（已确认不可逆工具）')
    button('批准一次执行').click(); await flushPromises()
    expect(mocks.decide).toHaveBeenCalledWith('approval-1', 'review-digest', true)
  })
  it('reviews a new automation with its target and schedule', async () => {
    const change = { ...approval, action: 'AUTOMATION_CHANGE', subject: 'AUTOMATION:NEW' }
    mocks.listApprovals.mockResolvedValue({ data: [change] })
    mocks.review.mockResolvedValue({ data: { ...change, arguments: { contractVersion: 1, operation: 'CREATE', automationKey: null, before: null,
      after: { name: '库存日报', projectCode: 'orders', activate: true, target: { type: 'AGENT', id: 'erp' }, schedule: { type: 'CRON', cronExpression: '0 9 * * *' } },
      invocationId: approval.invocationId } } })
    wrapper = mount(AiApprovals, { attachTo: document.body, global: { plugins: [ElementPlus] } })
    await flushPromises()
    expect(document.body.textContent).toContain('自动化 新建')
    button('审核').click(); await flushPromises()
    expect(document.body.textContent).toContain('审核自动化新建')
    expect(document.body.textContent).toContain('0 9 * * *')
    expect(document.body.textContent).toContain('无人值守')
    button('批准一次执行').click(); await flushPromises()
    expect(mocks.decide).toHaveBeenCalledWith('approval-1', 'review-digest', true)
  })
  it('reviews a run replay with its new input', async () => {
    const change = { ...approval, action: 'RUN_REPLAY', subject: 'RUN:t1' }
    mocks.listApprovals.mockResolvedValue({ data: [change] })
    mocks.review.mockResolvedValue({ data: { ...change, arguments: { contractVersion: 1, traceId: 't1',
      source: { traceId: 't1', agentId: 'erp', agentName: 'ERP 助手', agentConfigVersionId: 7, projectCode: 'orders', status: 'FAILED' },
      command: { messageOverride: '再查一次库存', sessionId: null }, invocationId: approval.invocationId } } })
    wrapper = mount(AiApprovals, { attachTo: document.body, global: { plugins: [ElementPlus] } })
    await flushPromises()
    expect(document.body.textContent).toContain('重放运行 t1')
    button('审核').click(); await flushPromises()
    expect(document.body.textContent).toContain('ERP 助手（运行 t1，版本 7）')
    expect(document.body.textContent).toContain('再查一次库存')
    button('批准一次执行').click(); await flushPromises()
    expect(mocks.decide).toHaveBeenCalledWith('approval-1', 'review-digest', true)
  })
  it('reviews a batch grant with the rules it would overwrite', async () => {
    const change = { ...approval, action: 'TOOL_ACL_CHANGE', subject: 'TOOL_ACL:BATCH' }
    mocks.listApprovals.mockResolvedValue({ data: [change] })
    mocks.review.mockResolvedValue({ data: { ...change, arguments: { contractVersion: 1, operation: 'BATCH_GRANT', aclId: null,
      overwritten: [{ id: 3, roleCode: 'sales', targetKind: 'TOOL', targetName: 'create_customer', permission: 'DENY', projectCode: null, projectId: null, enabled: true }],
      after: { roleCode: 'sales', projectId: null, projectCode: null, permission: 'ALLOW', note: null,
        targets: [{ kind: 'TOOL', name: 'create_customer' }, { kind: 'TOOL', name: 'query_stock' }] },
      invocationId: approval.invocationId } } })
    wrapper = mount(AiApprovals, { attachTo: document.body, global: { plugins: [ElementPlus] } })
    await flushPromises()
    expect(document.body.textContent).toContain('调用权限规则 批量授权')
    button('审核').click(); await flushPromises()
    expect(document.body.textContent).toContain('sales 允许调用以下 2 项（平台级）')
    expect(document.body.textContent).toContain('create_customer、query_stock')
    expect(document.body.textContent).toContain('sales 禁止调用 create_customer（平台级）')
    button('批准一次执行').click(); await flushPromises()
    expect(mocks.decide).toHaveBeenCalledWith('approval-1', 'review-digest', true)
  })
  it('reviews a business index edit with the changed template', async () => {
    const change = { ...approval, action: 'BUSINESS_INDEX_CHANGE', subject: 'BUSINESS_INDEX:orders_idx' }
    mocks.listApprovals.mockResolvedValue({ data: [change] })
    mocks.review.mockResolvedValue({ data: { ...change, arguments: { contractVersion: 1, operation: 'UPDATE', indexCode: 'orders_idx',
      request: { textTemplate: '{title} {amount}' },
      before: { indexCode: 'orders_idx', indexName: '订单', textTemplate: '{title}', chunkSize: 500 },
      after: { indexCode: 'orders_idx', indexName: '订单', textTemplate: '{title} {amount}', chunkSize: 500 },
      invocationId: approval.invocationId } } })
    wrapper = mount(AiApprovals, { attachTo: document.body, global: { plugins: [ElementPlus] } })
    await flushPromises()
    expect(document.body.textContent).toContain('业务索引 orders_idx')
    button('审核').click(); await flushPromises()
    expect(document.body.textContent).toContain('审核业务索引修改')
    expect(document.body.textContent).toContain('订单（orders_idx）')
    expect(document.body.textContent).toContain('{title} → {title} {amount}')
    expect(document.body.textContent).not.toContain('切分长度')
    button('批准一次执行').click(); await flushPromises()
    expect(mocks.decide).toHaveBeenCalledWith('approval-1', 'review-digest', true)
  })
  it('reviews moving an Agent to the newest Workflow versions', async () => {
    const change = { ...approval, action: 'AGENT_WORKFLOW_PUBLISH', subject: 'AGENT:agent-1' }
    mocks.listApprovals.mockResolvedValue({ data: [change] })
    mocks.review.mockResolvedValue({ data: { ...change, arguments: { contractVersion: 1, operation: 'PUBLISH_LATEST', agentId: 'agent-1',
      activeConfigVersionId: 7, projectId: 3, projectCode: 'orders', agentName: '订单助手',
      workflows: [{ workflowId: 'wf-1', workflowName: '查库存', pinnedWorkflowVersion: 'v1.0.0', latestWorkflowVersion: 'v1.1.0', stale: true },
        { workflowId: 'wf-2', workflowName: '建客户', pinnedWorkflowVersion: 'v2.0.0', latestWorkflowVersion: 'v2.0.0', stale: false }],
      invocationId: approval.invocationId } } })
    wrapper = mount(AiApprovals, { attachTo: document.body, global: { plugins: [ElementPlus] } })
    await flushPromises()
    expect(document.body.textContent).toContain('Agent agent-1 线上配置')
    button('审核').click(); await flushPromises()
    expect(document.body.textContent).toContain('审核 Agent 更新到最新 Workflow 版本')
    expect(document.body.textContent).toContain('查库存：v1.0.0 → v1.1.0')
    expect(document.body.textContent).not.toContain('建客户：')
    button('批准一次执行').click(); await flushPromises()
    expect(mocks.decide).toHaveBeenCalledWith('approval-1', 'review-digest', true)
  })

  it('reviews a page Workflow delivery with its Agent', async () => {
    const change = { ...approval, action: 'PAGE_WORKFLOW_DELIVERY', subject: 'PAGE_WORKFLOW_TASK:task-1' }
    mocks.listApprovals.mockResolvedValue({ data: [change] })
    mocks.review.mockResolvedValue({ data: { ...change, arguments: { contractVersion: 1, projectCode: 'orders', taskId: 'task-1',
      command: { pageKey: null, version: null, agentId: 'agent-1', modelInstanceId: null, replaceWorkflowId: null },
      plan: { workflowId: 'wf-9', artifactId: 5, request: { pageKey: 'order-list', version: 'v1.0.0', agentId: 'agent-1' } },
      invocationId: approval.invocationId } } })
    wrapper = mount(AiApprovals, { attachTo: document.body, global: { plugins: [ElementPlus] } })
    await flushPromises()
    button('审核').click(); await flushPromises()
    expect(document.body.textContent).toContain('审核页面 Workflow 交付')
    expect(document.body.textContent).toContain('wf-9 · v1.0.0 · 页面 order-list')
    expect(document.body.textContent).toContain('agent-1')
  })

  it('reviews a cross-domain memory erasure with its target and reason', async () => {
    const change = { ...approval, action: 'MEMORY_ERASURE_REQUEST', subject: 'MEMORY_ERASURE:req-1' }
    mocks.listApprovals.mockResolvedValue({ data: [change] })
    mocks.review.mockResolvedValue({ data: { ...change, arguments: { contractVersion: 1, tenantId: 'tenant-a', runtimeUserId: 'user-7',
      clientRequestId: 'req-1', reasonCode: 'GDPR_ART17', referenceId: 'ticket-9', invocationId: approval.invocationId } } })
    wrapper = mount(AiApprovals, { attachTo: document.body, global: { plugins: [ElementPlus] } })
    await flushPromises()
    expect(document.body.textContent).toContain('跨域记忆删除 req-1')
    button('审核').click(); await flushPromises()
    expect(document.body.textContent).toContain('审核跨域记忆删除')
    expect(document.body.textContent).toContain('user-7（租户 tenant-a）')
    expect(document.body.textContent).toContain('GDPR_ART17 · ticket-9')
    button('批准一次执行').click(); await flushPromises()
    expect(mocks.decide).toHaveBeenCalledWith('approval-1', 'review-digest', true)
  })
  it('reviews a credential secret rotation without showing the secret', async () => {
    const change = { ...approval, action: 'WORKFLOW_CREDENTIAL_CHANGE', subject: 'WORKFLOW_CREDENTIAL:4' }
    mocks.listApprovals.mockResolvedValue({ data: [change] })
    mocks.review.mockResolvedValue({ data: { ...change, arguments: { contractVersion: 1, operation: 'UPDATE', credentialId: 4,
      request: { secretSalt: 'aa', secretSha256: 'bb' },
      before: { id: 4, credentialRef: 'cred_erp', name: 'ERP 令牌', type: 'BEARER', projectId: 3, projectCode: 'orders', scope: 'PROJECT', status: 'ACTIVE', secretPreview: { token: 'ab****yz' }, updatedAt: '2026-10-30T10:00:00' },
      after: { credentialRef: 'cred_erp', name: 'ERP 令牌', type: 'BEARER', scope: 'PROJECT', projectId: 3, projectCode: 'orders', status: 'ACTIVE' },
      secretChanged: true, invocationId: approval.invocationId } } })
    wrapper = mount(AiApprovals, { attachTo: document.body, global: { plugins: [ElementPlus] } })
    await flushPromises()
    expect(document.body.textContent).toContain('Workflow 凭据 #4')
    button('审核').click(); await flushPromises()
    expect(document.body.textContent).toContain('审核修改 Workflow 凭据')
    expect(document.body.textContent).toContain('更换为新密钥（只保存指纹）')
    button('批准一次执行').click(); await flushPromises()
    expect(mocks.decide).toHaveBeenCalledWith('approval-1', 'review-digest', true)
  })
  it('reviews regenerating an API graph with the curated relations it drops', async () => {
    const change = { ...approval, action: 'API_GRAPH_CHANGE', subject: 'API_GRAPH:7' }
    mocks.listApprovals.mockResolvedValue({ data: [change] })
    mocks.review.mockResolvedValue({ data: { ...change, arguments: { contractVersion: 1, operation: 'REGENERATE', projectId: 7, edgeId: null,
      nodeCount: 2, edgeCount: 2, layoutCount: 0, edgesByStatus: { CANDIDATE: 1, CONFIRMED: 1 },
      curatedEdges: [{ id: 1, sourceNodeId: 10, targetNodeId: 11, kind: 'MODEL_REF', source: 'INFERRED', status: 'CONFIRMED', confirmedBy: 'alice', note: null, enabled: true }],
      edgeDigest: 'abc', invocationId: approval.invocationId } } })
    wrapper = mount(AiApprovals, { attachTo: document.body, global: { plugins: [ElementPlus] } })
    await flushPromises()
    expect(document.body.textContent).toContain('重新生成项目 #7 的 API 关系图')
    button('审核').click(); await flushPromises()
    expect(document.body.textContent).toContain('审核重新生成 API 关系图')
    expect(document.body.textContent).toContain('#1 节点 10 → 11（MODEL_REF，CONFIRMED，alice 确认）')
    button('批准一次执行').click(); await flushPromises()
    expect(mocks.decide).toHaveBeenCalledWith('approval-1', 'review-digest', true)
  })
  it('reviews an embed policy change with the new origins', async () => {
    const change = { ...approval, action: 'EMBED_CHANGE', subject: 'EMBED_POLICY:3' }
    mocks.listApprovals.mockResolvedValue({ data: [change] })
    mocks.review.mockResolvedValue({ data: { ...change, arguments: { contractVersion: 1, operation: 'UPDATE_POLICY', targetId: 3,
      request: { allowedOrigins: ['https://erp.example.com', 'https://new.example.com'] },
      before: { id: 3, projectCode: 'orders', appKey: 'ak', allowedOrigins: ['https://erp.example.com'], allowedAgentIds: ['erp'], tokenTtlSeconds: 600, status: 'ACTIVE' },
      after: { allowedOrigins: ['https://erp.example.com', 'https://new.example.com'], allowedAgentIds: ['erp'], tokenTtlSeconds: 600, status: 'ACTIVE' },
      invocationId: approval.invocationId } } })
    wrapper = mount(AiApprovals, { attachTo: document.body, global: { plugins: [ElementPlus] } })
    await flushPromises()
    expect(document.body.textContent).toContain('嵌入接入策略 #3')
    button('审核').click(); await flushPromises()
    expect(document.body.textContent).toContain('审核修改嵌入接入策略')
    expect(document.body.textContent).toContain('https://erp.example.com → https://erp.example.com、https://new.example.com')
    button('批准一次执行').click(); await flushPromises()
    expect(mocks.decide).toHaveBeenCalledWith('approval-1', 'review-digest', true)
  })
  it('reviews applying a capability change with its impact', async () => {
    const change = { ...approval, action: 'CAPABILITY_CHANGE_REVIEW', subject: 'CAPABILITY_CHANGE:orders:5' }
    mocks.listApprovals.mockResolvedValue({ data: [change] })
    mocks.review.mockResolvedValue({ data: { ...change, arguments: { contractVersion: 1, projectCode: 'orders', diffItemId: 5, action: 'APPLY', note: null,
      change: { id: 5, qualifiedName: 'orders:createCustomer', changeType: 'CHANGED', fieldDiffJson: '{"path":"/v2"}', impactJson: '{"agents":["ERP 助手"]}', reviewStatus: 'PENDING' },
      invocationId: approval.invocationId } } })
    wrapper = mount(AiApprovals, { attachTo: document.body, global: { plugins: [ElementPlus] } })
    await flushPromises()
    expect(document.body.textContent).toContain('能力变更 orders #5')
    button('审核').click(); await flushPromises()
    expect(document.body.textContent).toContain('审核能力变更采纳')
    expect(document.body.textContent).toContain('orders:createCustomer（变更 #5，orders）')
    expect(document.body.textContent).toContain('ERP 助手')
    button('批准一次执行').click(); await flushPromises()
    expect(mocks.decide).toHaveBeenCalledWith('approval-1', 'review-digest', true)
  })
  it('reviews deleting a knowledge base with its files', async () => {
    const change = { ...approval, action: 'KNOWLEDGE_DELETE', subject: 'KNOWLEDGE_BASE:orders-kb' }
    mocks.listApprovals.mockResolvedValue({ data: [change] })
    mocks.review.mockResolvedValue({ data: { ...change, arguments: { contractVersion: 1, operation: 'DELETE_BASE', kbCode: 'orders-kb', fileId: null,
      base: { code: 'orders-kb', name: '订单知识', scope: 'PROJECT', workspaceId: 'default', projectCode: 'orders' },
      files: [{ fileId: 'f1', fileName: '合同.pdf', fileType: 'pdf', fileSize: 100 }], invocationId: approval.invocationId } } })
    wrapper = mount(AiApprovals, { attachTo: document.body, global: { plugins: [ElementPlus] } })
    await flushPromises()
    expect(document.body.textContent).toContain('删除知识库 orders-kb')
    button('审核').click(); await flushPromises()
    expect(document.body.textContent).toContain('审核删除知识库')
    expect(document.body.textContent).toContain('订单知识（orders-kb，orders）')
    expect(document.body.textContent).toContain('1 个文件：合同.pdf')
    button('批准一次执行').click(); await flushPromises()
    expect(mocks.decide).toHaveBeenCalledWith('approval-1', 'review-digest', true)
  })
  it('reviews a new runtime user mapping', async () => {
    const change = { ...approval, action: 'IDENTITY_MAPPING_CHANGE', subject: 'RUNTIME_USER_MAPPING:NEW:7' }
    mocks.listApprovals.mockResolvedValue({ data: [change] })
    mocks.review.mockResolvedValue({ data: { ...change, arguments: { contractVersion: 1, operation: 'MAPPING_CREATE', targetId: null,
      request: { tenantId: 'default', platformUserId: 7, runtimeUserId: 'staff01' }, before: [],
      after: { tenantId: 'default', platformUserId: 7, runtimeUserId: 'staff01' }, invocationId: approval.invocationId } } })
    wrapper = mount(AiApprovals, { attachTo: document.body, global: { plugins: [ElementPlus] } })
    await flushPromises()
    expect(document.body.textContent).toContain('运行时用户映射 新建 · 平台账号 #7')
    button('审核').click(); await flushPromises()
    expect(document.body.textContent).toContain('审核新建运行时用户映射')
    expect(document.body.textContent).toContain('没有生效的映射')
    expect(document.body.textContent).toContain('平台账号 #7 → 运行身份 staff01（租户 default）')
    button('批准一次执行').click(); await flushPromises()
    expect(mocks.decide).toHaveBeenCalledWith('approval-1', 'review-digest', true)
  })
  it('reviews an API connection change with the new address', async () => {
    const change = { ...approval, action: 'HTTP_API_CONNECTION_CHANGE', subject: 'HTTP_API:9' }
    mocks.listApprovals.mockResolvedValue({ data: [change] })
    mocks.review.mockResolvedValue({ data: { ...change, arguments: { contractVersion: 1, operation: 'SAVE_OVERRIDE', apiId: 9,
      request: { origin: 'https://erp-new.example' }, expectedRevision: 4,
      before: { qualifiedName: 'erp.customers', projectCode: 'erp', origin: 'https://erp.example', authMode: 'BEARER', credentialRef: 'cred-1', credentialName: 'ERP 令牌', overrideActive: true, overrideRevision: 4, projectDefault: null },
      after: { origin: 'https://erp-new.example', authMode: 'BEARER', credentialRef: 'cred-1' },
      invocationId: approval.invocationId } } })
    wrapper = mount(AiApprovals, { attachTo: document.body, global: { plugins: [ElementPlus] } })
    await flushPromises()
    expect(document.body.textContent).toContain('API 连接 #9')
    button('审核').click(); await flushPromises()
    expect(document.body.textContent).toContain('审核 API 连接修改')
    expect(document.body.textContent).toContain('https://erp.example · BEARER · 凭据 ERP 令牌')
    expect(document.body.textContent).toContain('https://erp-new.example · BEARER · 凭据 cred-1')
    button('批准一次执行').click(); await flushPromises()
    expect(mocks.decide).toHaveBeenCalledWith('approval-1', 'review-digest', true)
  })
  it('reviews disabling an Agent', async () => {
    const change = { ...approval, action: 'AGENT_LIFECYCLE_CHANGE', subject: 'AGENT:erp' }
    mocks.listApprovals.mockResolvedValue({ data: [change] })
    mocks.review.mockResolvedValue({ data: { ...change, arguments: { contractVersion: 1, operation: 'DISABLE', agentId: 'erp',
      agent: { id: 'erp', name: 'ERP 助手', projectCode: 'orders', enabled: true, activeConfigVersionId: 7 },
      invocationId: approval.invocationId } } })
    wrapper = mount(AiApprovals, { attachTo: document.body, global: { plugins: [ElementPlus] } })
    await flushPromises()
    button('审核').click(); await flushPromises()
    expect(document.body.textContent).toContain('审核停用 Agent')
    expect(document.body.textContent).toContain('ERP 助手（erp，当前已启用）')
    expect(document.body.textContent).toContain('用户将不能再使用这个 Agent')
    button('批准一次执行').click(); await flushPromises()
    expect(mocks.decide).toHaveBeenCalledWith('approval-1', 'review-digest', true)
  })
  it('reviews a role grant replacement with the current and new grants', async () => {
    const change = { ...approval, action: 'ACCOUNT_CHANGE', subject: 'PLATFORM_USER:7' }
    mocks.listApprovals.mockResolvedValue({ data: [change] })
    mocks.review.mockResolvedValue({ data: { ...change, arguments: { contractVersion: 1, operation: 'REPLACE_GRANTS', targetId: 7,
      request: { grants: [{ roleId: 2, scopeType: 'PROJECT', scopeValue: 'orders' }] },
      before: { id: 7, username: 'dev01', displayName: '张三', status: 'ACTIVE', grants: [] },
      after: { grants: [{ roleId: 2, roleCode: 'DEVELOPER', roleName: '开发人员', scopeType: 'PROJECT', scopeValue: 'orders' }] },
      invocationId: approval.invocationId } } })
    wrapper = mount(AiApprovals, { attachTo: document.body, global: { plugins: [ElementPlus] } })
    await flushPromises()
    expect(document.body.textContent).toContain('账号 #7')
    button('审核').click(); await flushPromises()
    expect(document.body.textContent).toContain('审核角色授权替换')
    expect(document.body.textContent).toContain('张三（dev01，#7，当前ACTIVE）')
    expect(document.body.textContent).toContain('开发人员 · 项目 orders')
    expect(document.body.textContent).toContain('不能借此修改你自己的账号')
    button('批准一次执行').click(); await flushPromises()
    expect(mocks.decide).toHaveBeenCalledWith('approval-1', 'review-digest', true)
  })
  it('reviews a knowledge chunk rewrite with both versions of the text', async () => {
    const change = { ...approval, action: 'KNOWLEDGE_CHUNK_CHANGE', subject: 'KNOWLEDGE_CHUNK:7' }
    mocks.listApprovals.mockResolvedValue({ data: [change] })
    mocks.review.mockResolvedValue({ data: { ...change, arguments: { contractVersion: 1, operation: 'UPDATE', fileId: 'file_1', chunkId: 7,
      resource: { scope: 'PROJECT', workspaceId: 'ws', projectCode: 'orders' },
      before: { chunkIndex: 0, title: '第一节', content: '旧的退货规则', contentSha256: 'c'.repeat(64), enabled: true },
      after: { title: '第一节', content: '新的退货规则' }, invocationId: approval.invocationId } } })
    wrapper = mount(AiApprovals, { attachTo: document.body, global: { plugins: [ElementPlus] } })
    await flushPromises()
    expect(document.body.textContent).toContain('知识切片 #7')
    button('审核').click(); await flushPromises()
    expect(document.body.textContent).toContain('审核知识切片改写')
    expect(document.body.textContent).toContain('旧的退货规则')
    expect(document.body.textContent).toContain('新的退货规则')
    button('批准一次执行').click(); await flushPromises()
    expect(mocks.decide).toHaveBeenCalledWith('approval-1', 'review-digest', true)
  })
  it('does not approve a knowledge import commit without its preview digest', async () => {
    const commit = { ...approval, action: 'KNOWLEDGE_IMPORT_COMMIT', subject: 'KNOWLEDGE_IMPORT_JOB:dij_1' }
    mocks.listApprovals.mockResolvedValue({ data: [commit] })
    mocks.review.mockResolvedValue({ data: { ...commit, arguments: { contractVersion: 1, operation: 'COMMIT', jobId: 'dij_1',
      knowledgeBaseCode: 'kb-orders', status: 'PARSED', preview: { totalChunks: 12 } } } })
    wrapper = mount(AiApprovals, { attachTo: document.body, global: { plugins: [ElementPlus] } })
    await flushPromises(); button('审核').click(); await flushPromises()
    expect(document.body.textContent).toContain('审核内容不完整')
    expect([...document.querySelectorAll('button')].some(item => item.textContent?.trim() === '批准一次执行')).toBe(false)
  })
  it('does not approve a Page Assistant sync that mixes initialization with a revision', async () => {
    const sync = { ...approval, action: 'PAGE_ASSISTANT_SYNC', subject: 'WORKFLOW:wf-page' }
    mocks.listApprovals.mockResolvedValue({ data: [sync] })
    mocks.review.mockResolvedValue({ data: { ...sync, arguments: { contractVersion: 1, projectCode: 'orders', workflowId: 'wf-page',
      workflowActiveVersionId: 11, targetExists: false, createTarget: true, baseRevision: 'c'.repeat(64) } } })
    wrapper = mount(AiApprovals, { attachTo: document.body, global: { plugins: [ElementPlus] } })
    await flushPromises(); button('审核').click(); await flushPromises()
    expect(document.body.textContent).toContain('审核内容不完整')
    expect([...document.querySelectorAll('button')].some(item => item.textContent?.trim() === '批准一次执行')).toBe(false)
  })
  it('reviews an Agent configuration and its exact Workflow versions before approving publication', async () => {
    const publication = { ...approval, action: 'AGENT_PUBLISH', subject: 'AGENT:agent-1' }
    mocks.listApprovals.mockResolvedValue({ data: [publication] })
    mocks.review.mockResolvedValue({ data: { ...publication, arguments: {
      agentId: 'agent-1', projectCode: 'orders', projectId: 3, configVersionId: 8, activeConfigVersionId: 4,
      baseRevision: 'a'.repeat(64), workflowVersions: { 'wf-1': 100 },
      identity: { name: '订单助手', visibility: 'PROJECT', allowedRolesJson: '["operator"]', enabled: true },
      snapshot: { id: 8, agentId: 'agent-1', status: 'DRAFT', systemPrompt: '审核后的上线配置' },
    } } })
    wrapper = mount(AiApprovals, { attachTo: document.body, global: { plugins: [ElementPlus] } })
    await flushPromises()
    expect(document.body.textContent).toContain('发布 Agent agent-1')
    button('审核').click()
    await flushPromises()
    expect(document.body.textContent).toContain('审核 Agent 发布')
    expect(document.body.textContent).toContain('会替换 Agent 的线上配置')
    expect(document.body.textContent).toContain('当前线上配置版本')
    expect(document.body.textContent).toContain('项目内')
    expect(document.body.textContent).toContain('operator')
    expect(document.body.textContent).toContain('wf-1 · 发布版本 #100')
    const content = document.querySelector('[data-testid="approval-input"]')?.textContent ?? ''
    expect(content).toContain('审核后的上线配置')
    expect(content).toContain('"wf-1": 100')
    expect(document.body.textContent).not.toContain('调用使用项目测试身份')
    button('批准一次执行').click()
    await flushPromises()
    expect(mocks.decide).toHaveBeenCalledWith('approval-1', 'review-digest', true)
    expect(document.body.textContent).not.toContain('审核后的上线配置')
  })

  it('does not offer approval when an Agent publication snapshot is incomplete', async () => {
    const publication = { ...approval, action: 'AGENT_PUBLISH', subject: 'AGENT:agent-1' }
    mocks.listApprovals.mockResolvedValue({ data: [publication] })
    mocks.review.mockResolvedValue({ data: { ...publication, arguments: { agentId: 'agent-1', configVersionId: 8 } } })
    wrapper = mount(AiApprovals, { attachTo: document.body, global: { plugins: [ElementPlus] } })
    await flushPromises()
    button('审核').click()
    await flushPromises()
    expect(document.body.textContent).toContain('审核内容不完整')
    expect([...document.querySelectorAll('button')].some(item => item.textContent?.trim() === '批准一次执行')).toBe(false)
    expect(mocks.decide).not.toHaveBeenCalled()
    button('拒绝').click()
    await flushPromises()
    expect(mocks.decide).toHaveBeenCalledWith('approval-1', 'review-digest', false)
  })

  it('requires an understandable Agent audience before offering publication approval', async () => {
    const publication = { ...approval, action: 'AGENT_PUBLISH', subject: 'AGENT:agent-1' }
    mocks.listApprovals.mockResolvedValue({ data: [publication] })
    mocks.review.mockResolvedValue({ data: { ...publication, arguments: {
      agentId: 'agent-1', projectCode: 'orders', projectId: 3, configVersionId: 8, activeConfigVersionId: 4,
      baseRevision: 'a'.repeat(64), workflowVersions: {}, snapshot: { id: 8, agentId: 'agent-1', status: 'DRAFT' },
      identity: { name: '订单助手', visibility: 'PROJECT', allowedRolesJson: 'invalid', enabled: true },
    } } })
    wrapper = mount(AiApprovals, { attachTo: document.body, global: { plugins: [ElementPlus] } })
    await flushPromises()
    button('审核').click()
    await flushPromises()
    expect(document.body.textContent).toContain('无法读取')
    expect([...document.querySelectorAll('button')].some(item => item.textContent?.trim() === '批准一次执行')).toBe(false)
  })
  it('lists only pending requests first and all of them on demand', async () => {
    mocks.listApprovals.mockResolvedValue({ data: [approval, { ...approval, id: 'done', subject: 'orders.done', status: 'SUCCEEDED' }] })
    wrapper = mount(AiApprovals, { attachTo: document.body, global: { plugins: [ElementPlus] } })
    await flushPromises()
    expect(document.body.textContent).toContain('orders.create')
    expect(document.body.textContent).not.toContain('orders.done')
    expect(document.querySelector('[data-testid="employee-approvals"]')).toBeNull()
    button('全部').click()
    await flushPromises()
    expect(document.body.textContent).toContain('orders.done')
  })

  it('shows the exact write input before approving the reviewed digest', async () => {
    mocks.list.mockResolvedValue({ data: [connection] })
    mocks.listApprovals.mockResolvedValue({ data: [approval] })
    wrapper = mount(AiApprovals, { attachTo: document.body, global: { plugins: [ElementPlus] } })
    await flushPromises()
    button('审核').click()
    await flushPromises()
    expect(mocks.review).toHaveBeenCalledWith('approval-1')
    expect(document.body.textContent).toContain('orders.create')
    expect(document.body.textContent).toContain('WRITE')
    expect(document.querySelector('[data-testid="approval-input"]')?.textContent).toContain('sensitive-item')
    expect(JSON.stringify(localStorage)).not.toContain('sensitive-item')
    expect(JSON.stringify(sessionStorage)).not.toContain('sensitive-item')
    button('批准一次执行').click()
    await flushPromises()
    expect(mocks.decide).toHaveBeenCalledWith('approval-1', 'review-digest', true)
    expect(document.body.textContent).not.toContain('sensitive-item')
  })
  it('removes and never submits a previous user review after an account change', async () => {
    let resolve!: (result: unknown) => void
    mocks.listApprovals.mockResolvedValue({ data: [approval] })
    mocks.review.mockReturnValue(new Promise(done => { resolve = done }))
    wrapper = mount(AiApprovals, { attachTo: document.body, global: { plugins: [ElementPlus] } })
    await flushPromises()
    button('审核').click()
    await flushPromises()
    owner.value = { userId: 8, username: 'another' }
    await flushPromises()
    resolve({ data: approvalDetail })
    await flushPromises()
    expect(document.body.textContent).not.toContain('sensitive-item')
    expect([...document.querySelectorAll('button')].some(item => item.textContent?.trim() === '批准一次执行')).toBe(false)
    expect(mocks.decide).not.toHaveBeenCalled()
  })
  it('rejects an approval only after loading its complete review', async () => {
    mocks.listApprovals.mockResolvedValue({ data: [approval] })
    wrapper = mount(AiApprovals, { attachTo: document.body, global: { plugins: [ElementPlus] } })
    await flushPromises()
    button('审核').click()
    await flushPromises()
    button('拒绝').click()
    await flushPromises()
    expect(mocks.decide).toHaveBeenCalledWith('approval-1', 'review-digest', false)
  })
  it('shows HTTP API path, query and body with the connection identity before approval', async () => {
    const httpApproval = { ...approval, id: 'http-approval', action: 'HTTP_API_WRITE', subject: 'HTTP_API:9' }
    mocks.listApprovals.mockResolvedValue({ data: [httpApproval] })
    mocks.review.mockResolvedValue({ data: { ...httpApproval, arguments: {
      qualifiedName: 'orders.dev.POST./orders', projectCode: 'orders', projectId: 3, environment: 'dev',
      sideEffect: 'WRITE', targetDescription: 'POST · https://orders.example.test/orders',
      pathParams: { id: '123' }, queryParams: { source: 'fixture' }, body: { item: 'private-item' },
    } } })
    wrapper = mount(AiApprovals, { attachTo: document.body, global: { plugins: [ElementPlus] } })
    await flushPromises()
    button('审核').click()
    await flushPromises()
    expect(document.body.textContent).toContain('orders.dev.POST./orders')
    expect(document.body.textContent).toContain('调用使用 API 配置的连接身份')
    const input = document.querySelector('[data-testid="approval-input"]')?.textContent
    expect(input).toContain('123')
    expect(input).toContain('fixture')
    expect(input).toContain('private-item')
    button('批准一次执行').click()
    await flushPromises()
    expect(mocks.decide).toHaveBeenCalledWith('http-approval', 'review-digest', true)
  })
})
