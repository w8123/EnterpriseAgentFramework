import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter, type LocationQueryRaw } from 'vue-router'
import { parseProjectScopeQuery } from '@/utils/projectScope'
import ElementPlus from 'element-plus'
import CapabilityEnrichmentPanel from './CapabilityEnrichmentPanel.vue'

const api = vi.hoisted(() => ({ read: vi.fn(), edit: vi.fn(), generate: vi.fn(), models: vi.fn(), session: null as any, user: null as any }))
vi.mock('@/api/semanticDoc', () => ({ getCapabilityEnrichment: api.read, editSubjectSemanticDoc: api.edit, generateSubjectSemanticDoc: api.generate }))
vi.mock('@/api/model', () => ({ getModelInstances: api.models }))
vi.mock('@/auth/platformSession', async () => {
  const { ref } = await import('vue'); api.session = ref('session-a'); api.user = ref(null)
  return { platformSessionId: api.session, platformSessionUser: api.user }
})
const wrappers: ReturnType<typeof mount>[] = []
afterEach(() => { wrappers.splice(0).forEach(wrapper => wrapper.unmount()); vi.clearAllMocks() })
const value = (id = 10) => ({ projectId: 7, subjectType: 'HTTP_API', subjectId: id, subjectKey: `orders:${id}`,
  title: '订单查询', contractHash: 'new', accepted: true, semanticDoc: { projectId: 7, subjectKey: `orders:${id}`, updatedAt: 'revision-1', subjectType: 'HTTP_API', subjectId: id,
    contentMd: '## 查询用途', status: 'generated', modelName: '测试模型' }, semanticStale: true,
  groups: [{ id: 1, name: 'Orders', displayName: '订单', sourceClasses: [] }], sourceObservations: [], graphAvailable: true, graphState: 'CURRENT', paramHints: [] })
async function render(navigate?: (location: any) => void, query: LocationQueryRaw = {}) {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/:rest(.*)', component: { template: '<div />' } }] })
  await router.push({ path: '/', query })
  const wrapper = mount(CapabilityEnrichmentPanel, { props: { projectId: 7, projectCode: 'orders', subjectType: 'HTTP_API', subjectId: 10, subjectKey: 'orders:10', navigate }, global: { plugins: [router, ElementPlus] } })
  wrappers.push(wrapper); return wrapper
}
describe('typed asset enrichment', () => {
  it('gets graph hints with enrichment and retries an incomplete hint response through one owner read', async () => {
    api.read.mockResolvedValueOnce({ data: { ...value(), paramHints: undefined } }).mockResolvedValueOnce({ data: value() })
    const wrapper = await render(); await flushPromises()
    expect(api.read).toHaveBeenCalledOnce()
    expect(wrapper.text()).toContain('参数来源读取失败')
    await wrapper.findAll('button').find(button => button.text() === '重新加载参数来源')!.trigger('click'); await flushPromises()
    expect(api.read).toHaveBeenCalledTimes(2)
    expect(wrapper.text()).toContain('尚无可用的已确认参数来源')
  })
  beforeEach(() => {
    api.session.value = 'session-a'
    api.user.value = { userId: 1, permissionGrants: [{ permissionCode: 'platform:write', scopeType: 'PROJECT', scopeValue: 'orders' }] }
    api.models.mockResolvedValue({ data: [{ id: 'llm-1', name: '模型一', modelType: 'LLM', status: 'ACTIVE', enabled: true }] })
  })
  it('shows provenance, stale-contract warning and group without replacing the source contract', async () => {
    api.read.mockResolvedValue({ data: value() }); const navigate = vi.fn()
    const wrapper = await render(navigate); await flushPromises()
    expect(wrapper.text()).toContain('AI 补充说明')
    expect(wrapper.text()).toContain('补充说明基于此前契约')
    expect(wrapper.text()).toContain('订单')
    await wrapper.findAll('button').find(button => button.text() === '关联分析')!.trigger('click')
    expect(navigate).toHaveBeenCalledWith(expect.objectContaining({ path: '/business-capabilities/http-apis/graph', query: expect.objectContaining({ projectId: '7', subjectType: 'HTTP_API', subjectId: '10' }) }))
  })
  it.each(['查看来源', '关联分析'])('preserves catalog filters through %s and pins the actual owner instead of the all-project scope', async label => {
    api.read.mockResolvedValue({ data: value() }); const navigate = vi.fn()
    const wrapper = await render(navigate, { scope: 'all', projectId: '99', projectCode: 'stale-project', subjectId: '99',
      subjectType: 'BUSINESS_METHOD', subjectKey: 'stale-key', apiKeyword: '订单', apiPage: '3', apiGroupId: '8', methodKeyword: '查询', methodPage: '2' })
    await flushPromises()
    await wrapper.findAll('button').find(button => button.text() === label)!.trigger('click')
    const target = navigate.mock.calls[0][0]
    expect(target.query).toEqual({ projectId: '7', projectCode: 'orders', subjectType: 'HTTP_API', subjectId: '10', subjectKey: 'orders:10',
      apiKeyword: '订单', apiPage: '3', apiGroupId: '8', methodKeyword: '查询', methodPage: '2' })
    expect(parseProjectScopeQuery(target.query)).toEqual({ kind: 'project', projectId: 7, projectCode: 'orders' })
  })
  it('pins classification navigation to the owner project and clears unrelated deep-link actions', async () => {
    api.read.mockResolvedValue({ data: value() }); const navigate = vi.fn()
    const wrapper = await render(navigate, { scope: 'all', projectId: '99', projectCode: 'stale-project',
      subjectType: 'BUSINESS_METHOD', subjectId: '99', methodDetail: '99', methodAssetId: '99',
      classificationAction: 'merge', classificationId: '99', apiPage: '3', methodGroupId: '8', apiKeyword: '订单' })
    await flushPromises()
    await wrapper.findAll('button').find(button => button.text() === '订单')!.trigger('click')
    const target = navigate.mock.calls[0][0]
    expect(target.path).toBe('/business-capabilities/http-apis')
    expect(parseProjectScopeQuery(target.query)).toEqual({ kind: 'project', projectId: 7, projectCode: 'orders' })
    expect(target.query).toMatchObject({ apiPage: '1', apiGroupId: '1', methodGroupId: '8', apiKeyword: '订单' })
    expect(target.query.classificationAction).toBeUndefined(); expect(target.query.classificationId).toBeUndefined()
  })
  it('edits in place with optimistic revision, locks pending controls and keeps input on conflict', async () => {
    api.read.mockResolvedValue({ data: value() })
    let reject!: (error: unknown) => void
    api.edit.mockReturnValue(new Promise((_resolve, fail) => { reject = fail }))
    const wrapper = await render(); await flushPromises()
    const button = (label: string) => wrapper.findAll('button').find(item => item.text() === label)!
    await button('编辑说明').trigger('click')
    await wrapper.find('textarea').setValue('人工补充输入')
    await wrapper.find('form.enrichment-editor').trigger('submit')
    expect(api.edit).toHaveBeenCalledWith(7, 'HTTP_API', 10, { contentMd: '人工补充输入', expectedUpdatedAt: 'revision-1' })
    expect(wrapper.find('textarea').attributes('disabled')).toBeDefined()
    expect(button('取消编辑').attributes('disabled')).toBeDefined()
    reject({ response: { status: 409 } }); await flushPromises()
    expect(wrapper.find('textarea').element.value).toBe('人工补充输入')
    expect(wrapper.text()).toContain('说明已被更新')
    expect(button('保存说明').attributes('disabled')).toBeDefined()
    expect(wrapper.text()).not.toContain('资产整理视图')
  })
  it('rereads after a successful save and ignores a previous subject save after an account switch', async () => {
    api.read.mockResolvedValue({ data: value() })
    let resolve!: (response: any) => void
    api.edit.mockReturnValueOnce(new Promise(done => { resolve = done }))
    const wrapper = await render(); await flushPromises()
    await wrapper.findAll('button').find(item => item.text() === '编辑说明')!.trigger('click')
    await wrapper.find('textarea').setValue('旧账号输入')
    await wrapper.find('form.enrichment-editor').trigger('submit')
    expect(api.edit).toHaveBeenCalledTimes(1)
    api.user.value = { userId: 2, permissionGrants: [] }; api.session.value = 'session-b'; await flushPromises()
    resolve({ data: { ...value().semanticDoc, contentMd: '旧保存结果' } }); await flushPromises()
    expect(wrapper.text()).not.toContain('旧保存结果')
    expect(wrapper.find('textarea').exists()).toBe(false)
    expect(wrapper.findAll('button').some(item => item.text() === '编辑说明')).toBe(false)
  })
  it('rereads the saved owner and closes the editor only after confirmation', async () => {
    api.read.mockResolvedValueOnce({ data: value() }).mockResolvedValueOnce({ data: {
      ...value(), semanticDoc: { ...value().semanticDoc, status: 'edited', contentMd: '已保存的最新说明', updatedAt: 'revision-2' },
    } })
    api.edit.mockResolvedValue({ data: { ...value().semanticDoc, contentMd: '已保存的最新说明', updatedAt: 'revision-2' } })
    const wrapper = await render(); await flushPromises()
    await wrapper.findAll('button').find(item => item.text() === '编辑说明')!.trigger('click')
    await wrapper.find('textarea').setValue('已保存的最新说明')
    await wrapper.find('form.enrichment-editor').trigger('submit'); await flushPromises()
    expect(api.read).toHaveBeenCalledTimes(2)
    expect(wrapper.find('textarea').exists()).toBe(false)
    expect(wrapper.text()).toContain('业务说明已保存并重读')
    expect(wrapper.text()).toContain('人工编辑的补充说明')
  })
  it('keeps the draft while explicitly rereading a conflict and saves against the newly reviewed revision', async () => {
    api.read.mockResolvedValue({ data: value() })
    api.edit.mockRejectedValueOnce({ response: { status: 409 } })
    const wrapper = await render(); await flushPromises()
    const button = (label: string) => wrapper.findAll('button').find(item => item.text() === label)!
    await button('编辑说明').trigger('click'); await wrapper.find('textarea').setValue('我的说明')
    await wrapper.find('form.enrichment-editor').trigger('submit'); await flushPromises()
    api.read.mockResolvedValue({ data: { ...value(), semanticDoc: { ...value().semanticDoc, contentMd: '同事新说明', updatedAt: 'revision-2' } } })
    await button('重读最新说明').trigger('click'); await flushPromises()
    expect(wrapper.find('textarea').element.value).toBe('我的说明')
    expect(wrapper.text()).toContain('同事新说明')
    api.edit.mockResolvedValue({ data: { ...value().semanticDoc, contentMd: '我的说明', updatedAt: 'revision-3' } })
    await wrapper.find('form.enrichment-editor').trigger('submit'); await flushPromises()
    expect(api.edit).toHaveBeenLastCalledWith(7, 'HTTP_API', 10, { contentMd: '我的说明', expectedUpdatedAt: 'revision-2' })
  })
  it('generates with the chosen active model then rereads, without permitting parallel editing', async () => {
    api.read.mockResolvedValue({ data: value() })
    let resolve!: (data: any) => void
    api.generate.mockReturnValue(new Promise(done => { resolve = done }))
    const wrapper = await render(); await flushPromises()
    await wrapper.findAll('button').find(item => item.text() === '重新生成说明')!.trigger('click'); await flushPromises()
    wrapper.findComponent({ name: 'ElSelect' }).vm.$emit('update:modelValue', 'llm-1'); await flushPromises()
    await wrapper.find('form.enrichment-generation').trigger('submit')
    expect(api.generate).toHaveBeenCalledWith(7, 'HTTP_API', 10, true, { modelInstanceId: 'llm-1' })
    expect(wrapper.findAll('button').find(item => item.text() === '编辑说明')!.attributes('disabled')).toBeDefined()
    await wrapper.find('form.enrichment-generation').trigger('submit'); expect(api.generate).toHaveBeenCalledTimes(1)
    resolve({ data: value().semanticDoc }); await flushPromises()
    expect(api.read).toHaveBeenCalledTimes(2)
    expect(wrapper.text()).toContain('业务说明已生成并重读')
  })
  it('does not expose writes for another project grant and rejects a mismatched document owner', async () => {
    api.user.value.permissionGrants[0].scopeValue = 'another-project'
    api.read.mockResolvedValue({ data: value() })
    const wrapper = await render(); await flushPromises()
    expect(wrapper.findAll('button').some(item => item.text() === '编辑说明')).toBe(false)
    expect(wrapper.findAll('button').some(item => item.text() === '重新生成说明')).toBe(false)
    api.read.mockResolvedValue({ data: { ...value(), semanticDoc: { ...value().semanticDoc, subjectId: 99 } } })
    await wrapper.findAll('button').find(item => item.text() === '重读最新说明')!.trigger('click'); await flushPromises()
    expect(wrapper.text()).toContain('附属说明与关系读取失败')
    expect(wrapper.text()).not.toContain('查询用途')
  })
  it('rejects identity mismatch and ignores an earlier asset response after selection changes', async () => {
    let resolve!: (value: any) => void
    api.read.mockReturnValueOnce(new Promise(done => { resolve = done })).mockResolvedValueOnce({ data: value(11) })
    const wrapper = await render(); await wrapper.setProps({ subjectId: 11, subjectKey: 'orders:11' }); await flushPromises()
    resolve({ data: { ...value(), title: '旧资产' } }); await flushPromises()
    expect(wrapper.text()).not.toContain('旧资产')
    api.read.mockResolvedValueOnce({ data: { ...value(12), projectId: 8 } })
    await wrapper.setProps({ subjectId: 12, subjectKey: 'orders:12' }); await flushPromises()
    expect(wrapper.text()).toContain('附属说明与关系读取失败')
    expect(wrapper.text()).not.toContain('查询用途')
  })
})
