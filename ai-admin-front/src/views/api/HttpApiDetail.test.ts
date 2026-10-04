import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import HttpApiDetail from './HttpApiDetail.vue'

const mocks = vi.hoisted(() => ({ detail: vi.fn(), connection: vi.fn(), references: vi.fn(), credentials: vi.fn(),
  sessionId: null as { value: string } | null }))
vi.mock('@/api/httpApi', () => ({ getHttpApi: mocks.detail, getHttpApiConnection: mocks.connection,
  getHttpApiReferences: mocks.references, getHttpApiInvocation: vi.fn(), acceptHttpApi: vi.fn(),
  invokeHttpApi: vi.fn(), saveHttpApiConnection: vi.fn() }))
vi.mock('@/api/workflowCredential', () => ({ listWorkflowCredentials: mocks.credentials }))
vi.mock('@/composables/usePageProjectScope', async () => {
  const { ref } = await import('vue')
  return { usePageProjectScope: () => ({ canLoadData: ref(true), requestParams: ref({ projectId: 41 }),
    requestKey: ref('orders:41'), status: ref('ready'), feedbackMessage: ref(''), recoveryAction: ref('') }) }
})
vi.mock('@/auth/platformSession', async () => {
  const { ref } = await import('vue')
  const sessionId = ref('test-session')
  mocks.sessionId = sessionId
  return { platformSessionId: sessionId, platformSessionUser: ref({ userId: 7, permissionGrants: [] }) }
})
vi.mock('vue-router', () => ({ useRoute: () => ({ params: { id: '1' }, query: {} }),
  useRouter: () => ({ push: vi.fn() }) }))

const owner = { summary: { id: 1, projectId: 41, projectCode: 'orders', environment: 'dev',
  httpMethod: 'GET', routeTemplate: '/orders/{orderId}', sourceStatus: 'ACCEPTED',
  sourceConfirmed: true, candidateContractHash: 'a', acceptedContractHash: 'a', sourceSetRevision: 'b' },
  contract: { sideEffect: 'READ_ONLY', authentication: { state: 'NONE', schemes: [], requiredHeaderNames: [] },
    parameters: [], responses: [] },
  acceptedContract: { authentication: { state: 'NONE', schemes: [], requiredHeaderNames: [] }, parameters: [] },
  sources: [] }
let wrapper: ReturnType<typeof mount>
function start() { wrapper = mount(HttpApiDetail, { attachTo: document.body,
  global: { plugins: [ElementPlus], stubs: { RouterLink: { props: ['to'], template: '<a :data-route="JSON.stringify(to)"><slot /></a>' } } } }) }

beforeEach(() => {
  vi.clearAllMocks()
  if (mocks.sessionId) mocks.sessionId.value = 'test-session'
  mocks.detail.mockResolvedValue({ data: owner })
  mocks.connection.mockResolvedValue({ data: { status: 'CONFIGURED', revision: 1, origin: 'http://127.0.0.1:1', authMode: 'NONE' } })
  mocks.credentials.mockResolvedValue({ data: [] })
})
afterEach(() => { wrapper?.unmount(); document.body.innerHTML = '' })

describe('HTTP API detail usage', () => {
  it('separates supported published POST execution from the unchanged Studio read-only boundary', async () => {
    const accepted = { ...owner.acceptedContract, sideEffect: 'WRITE', requestBody: {
      required: true, contentTypes: ['application/json'], schema: { type: 'object',
        properties: { note: { type: 'string' } }, required: ['note'] },
    } }
    mocks.detail.mockResolvedValue({ data: { ...owner, summary: { ...owner.summary, httpMethod: 'POST' },
      contract: accepted, acceptedContract: accepted } })
    mocks.references.mockResolvedValue({ data: { runtimeEvidence: 'COMPLETE', publicationEvidence: 'COMPLETE', references: [] } })
    start(); await flushPromises()
    expect(wrapper.text()).toContain('内部 POST + WRITE 平铺 JSON 可通过已发布 Workflow 执行')
    expect(wrapper.text()).toContain('Studio 两种调试入口仍不执行写 API')
    expect(wrapper.text()).toContain('Agent 使用既有写操作确认')
    expect(wrapper.text()).not.toContain('不开放 Workflow')
  })

  it('separates market selection/connection from current Console proof and retains actual Run/Trace link', async () => {
    mocks.detail.mockResolvedValue({ data: { ...owner, summary: { ...owner.summary, sourceKinds: ['API_MARKET_OPERATION'], qualifiedName: 'http-api:orders:dev:market:' + 'a'.repeat(64) } } })
    const connection = { status: 'CONFIGURED', revision: 1, credentialRevision: null, origin: 'http://127.0.0.1:1', authMode: 'NONE',
      verification: { status: 'UNVERIFIED', reason: '已配置不等于已验证', apiId: 1, acceptedContractHash: 'a', sourceSetRevision: 'b',
        connectionRevision: 1, credentialRevision: null, runId: 7, traceId: 'actual-console-trace' } }
    mocks.connection.mockResolvedValue({ data: connection }); mocks.references.mockResolvedValue({ data: { runtimeEvidence: 'COMPLETE', publicationEvidence: 'COMPLETE', references: [] } })
    start(); await flushPromises()
    expect(wrapper.text()).toContain('连接已配置'); expect(wrapper.text()).toContain('当前市场验证未确认')
    expect(wrapper.text()).toContain('已配置不等于已验证')
    mocks.connection.mockResolvedValue({ data: { ...connection, verification: { ...connection.verification, status: 'VERIFIED' } } })
    Array.from(document.querySelectorAll('button')).find(button => button.textContent?.includes('刷新当前状态'))?.click()
    await flushPromises(); expect(wrapper.text()).toContain('当前市场验证已确认')
    expect(document.querySelector('a[data-route*="actual-console-trace"]')).not.toBeNull()
    mocks.connection.mockResolvedValue({ data: { ...connection, verification: { ...connection.verification, status: 'VERIFIED', sourceSetRevision: 'old-source' } } })
    Array.from(document.querySelectorAll('button')).find(button => button.textContent?.includes('刷新当前状态'))?.click()
    await flushPromises(); expect(wrapper.text()).toContain('当前市场验证未确认')
  })
  it('shows accepted-to-candidate field changes and checked impact before catalog acceptance', async () => {
    const accepted = { identity: { method: 'GET', routeTemplate: '/orders/{orderId}' },
      authentication: { state: 'NONE', schemes: [], requiredHeaderNames: [] }, sideEffect: 'READ_ONLY',
      parameters: [{ name: 'orderId', location: 'PATH', required: true, schema: { type: 'string' }, contentTypes: [] }],
      requestBody: null, responses: [{ status: '200', contentTypes: ['application/json'],
        schema: { type: 'object', properties: { state: { type: 'string' } } } }] }
    const candidate = structuredClone(accepted)
    candidate.parameters[0].schema = { type: 'string', minLength: 4 } as any
    mocks.detail.mockResolvedValue({ data: { ...owner, contract: candidate, acceptedContract: accepted,
      summary: { ...owner.summary, sourceStatus: 'CONTRACT_DRIFT', candidateContractHash: 'new-hash' } } })
    mocks.references.mockResolvedValue({ data: { runtimeEvidence: 'PARTIAL', publicationEvidence: 'COMPLETE',
      references: [{ kind: 'WORKFLOW', id: 'draft-wf', name: '订单处理', stage: 'DRAFT', nodeId: 'api-node' }] } })
    start(); await flushPromises()
    const changes = wrapper.find('[aria-labelledby="api-change-heading"]')
    expect(changes.exists(), 'API_CONTRACT_CHANGE_FIELD_VIEW_MISSING').toBe(true)
    expect(changes.text()).toContain('请求参数 PATH orderId / 最小长度')
    expect(changes.text()).toContain('已接纳')
    expect(changes.text()).toContain('候选')
    expect(changes.text()).toContain('未声明')
    expect(changes.text()).toContain('4')
    expect(wrapper.find('.api-accept-impact').text()).toContain('已查到 1 处使用位置')
    expect(wrapper.find('.api-accept-impact').text()).toContain('尚未完整确认')
    expect(wrapper.find('.api-accept-impact').text()).toContain('不会更新旧版本')
  })

  it('explains equal execution hashes independently of an unconfirmed source revision', async () => {
    mocks.detail.mockResolvedValue({ data: { ...owner,
      summary: { ...owner.summary, sourceStatus: 'SOURCE_UNCONFIRMED', sourceConfirmed: false,
        sourceReason: '最近一次扫描失败，请重新扫描项目' } } })
    mocks.references.mockResolvedValue({ data: { runtimeEvidence: 'COMPLETE', publicationEvidence: 'COMPLETE', references: [] } })
    start(); await flushPromises()
    expect(document.body.textContent, 'API_HASH_AND_SOURCE_CONFIRMATION_EXPLANATION_MISSING').toContain('执行契约没有变化')
    expect(document.body.textContent).toContain('不等于来源已确认')
    expect(document.body.textContent).toContain('清单修订')
    expect(document.body.textContent).toContain('重新校验并显式发布')
  })

  it('explains a response field conflict with both sources and preserves the accepted contract view', async () => {
    const response = (type: string) => ({ identity: { method: 'GET', routeTemplate: '/orders/{orderId}' },
      authentication: { state: 'UNKNOWN', schemes: [], requiredHeaderNames: [] }, sideEffect: 'READ_ONLY',
      parameters: [], requestBody: null,
      responses: [{ status: 'DEFAULT', contentTypes: ['application/json'], schema: {
        type: 'object', properties: { state: { type } } } }] })
    mocks.detail.mockResolvedValue({ data: { ...owner,
      summary: { ...owner.summary, sourceStatus: 'CONFLICT', sourceConfirmed: false,
        sourceReason: '活动来源契约不一致，请先处理来源冲突', candidateContractHash: null, activeSourceCount: 2 },
      contract: null, acceptedContract: response('string'), sources: [
        { id: 11, sourceKind: 'CONTROLLER_SCAN', sourceKey: 'controller:orders', sourceLocation: 'OrdersController.java#find',
          sourceContractHash: 'a', status: 'CONFLICT', confirmedInLatestInventory: true, inventoryComplete: true,
          contract: response('string') },
        { id: 12, sourceKind: 'OPENAPI_SCAN', sourceKey: 'openapi:orders.yaml:get', sourceLocation: 'orders.yaml#/paths/orders/get',
          sourceContractHash: 'c', status: 'CONFLICT', confirmedInLatestInventory: true, inventoryComplete: true,
          contract: response('integer') },
      ] } })
    mocks.references.mockResolvedValue({ data: { runtimeEvidence: 'COMPLETE', publicationEvidence: 'COMPLETE', references: [] } })
    start(); await flushPromises()
    expect(document.body.textContent).toContain('响应 DEFAULT / state / 类型')
    expect(document.body.textContent).toContain('string')
    expect(document.body.textContent).toContain('integer')
    expect(document.body.textContent).toContain('以下展示已接纳契约；冲突来源不会覆盖它')
    expect(document.body.textContent).toContain('先在来源修正差异并重新扫描；新试调用和新发布暂不可用')
    const call = Array.from(document.querySelectorAll('button')).find(button => button.textContent?.trim() === '试调用')
    expect(call?.disabled).toBe(true)
    expect(document.body.textContent).not.toContain('来源未声明响应结构')
  })

  it('shows owner-backed draft and published Workflow positions with their routes', async () => {
    mocks.references.mockResolvedValue({ data: { runtimeEvidence: 'COMPLETE', publicationEvidence: 'COMPLETE',
      references: [{ kind: 'WORKFLOW', id: 'draft-wf', name: '订单处理', stage: 'DRAFT', nodeId: 'api-node' },
        { kind: 'WORKFLOW', id: 'published-wf', name: '订单处理', stage: 'PUBLISHED', versionId: 9 }] } })
    start(); await flushPromises()
    expect(mocks.references).toHaveBeenCalledWith(1)
    expect(document.body.textContent).toContain('使用位置')
    expect(document.body.textContent).toContain('订单处理')
    expect(document.querySelector('a[data-route*="WorkflowStudio"]')).not.toBeNull()
    expect(document.querySelector('a[data-route*="WorkflowVersions"]')).not.toBeNull()
    const published = JSON.parse(document.querySelector('a[data-route*="WorkflowVersions"]')!.getAttribute('data-route')!)
    expect(published.query, 'API_USAGE_VERSION_LOCATION_MISSING').toMatchObject({ versionId: '9' })
  })

  it('never labels partial or failed reference evidence as unused', async () => {
    mocks.references.mockResolvedValueOnce({ data: { runtimeEvidence: 'PARTIAL', publicationEvidence: 'COMPLETE', references: [] } })
    start(); await flushPromises()
    expect(document.body.textContent).toContain('部分使用位置尚未确认')
    expect(document.body.textContent).not.toContain('还没有被引用')
    mocks.references.mockRejectedValueOnce(new Error('offline'))
    Array.from(document.querySelectorAll('button')).find(button => button.textContent?.includes('刷新使用位置'))?.click()
    await flushPromises()
    expect(document.body.textContent).toContain('使用位置查询失败')
    expect(document.body.textContent).not.toContain('还没有被引用')
  })

  it('discards a late usage response after the platform session changes', async () => {
    let resolveOld: (value: unknown) => void = () => {}
    mocks.references.mockImplementationOnce(() => new Promise(resolve => { resolveOld = resolve }))
    mocks.references.mockResolvedValueOnce({ data: { runtimeEvidence: 'COMPLETE',
      publicationEvidence: 'COMPLETE', references: [{ kind: 'WORKFLOW', id: 'new-wf', name: '新会话使用方', stage: 'DRAFT' }] } })
    start(); await flushPromises()
    mocks.sessionId!.value = 'next-session'
    await flushPromises()
    resolveOld({ data: { runtimeEvidence: 'COMPLETE', publicationEvidence: 'COMPLETE',
      references: [{ kind: 'WORKFLOW', id: 'old-wf', name: '旧会话使用方', stage: 'DRAFT' }] } })
    await flushPromises()
    expect(document.body.textContent).toContain('新会话使用方')
    expect(document.body.textContent).not.toContain('旧会话使用方')
  })
})
