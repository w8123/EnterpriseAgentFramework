import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'
import SourceSemanticDocsPanel from './SourceSemanticDocsPanel.vue'
import type { SemanticDoc, SemanticSubjectType } from '@/types/semanticDoc'

const api = vi.hoisted(() => ({ body: vi.fn() }))
vi.mock('@/api/semanticDoc', () => ({ getSubjectSemanticDoc: api.body }))
const bodies = new Map<string, SemanticDoc>()
function doc(subjectType: SemanticSubjectType, subjectId: number, overrides: Partial<SemanticDoc> = {}): SemanticDoc {
  const value: SemanticDoc = { id: subjectId, projectId: 7, subjectType, subjectId, subjectKey: `${subjectType}:stable:${subjectId}`,
    title: `${subjectType}说明`, contentMd: `# ${subjectType}完整正文`, status: 'generated', modelName: '测试模型',
    basisContractHash: 'contract-0123456789', promptVersion: null, tokenUsage: 0, ...overrides }
  bodies.set(`${value.projectId}:${subjectType}:${subjectId}`, value)
  return value
}
const docs = [doc('PROJECT', 7), doc('GROUP', 2), doc('BUSINESS_METHOD', 2), doc('HTTP_API', 2), doc('SOURCE_OBSERVATION', 2)]
const wrappers: ReturnType<typeof mount>[] = []
afterEach(() => { wrappers.splice(0).forEach(wrapper => wrapper.unmount()); document.body.innerHTML = ''; api.body.mockClear() })
async function start(extra: Record<string, unknown> = {}, query: Record<string, string> = {}, slots: Record<string, string> = {}) {
  api.body.mockImplementation(async (projectId: number, type: string, id: number) => ({ data: bodies.get(`${projectId}:${type}:${id}`) || null }))
  const router = createRouter({ history: createMemoryHistory(), routes: [
    { path: '/business-capabilities/:category/:workspace', component: { template: '<div />' } },
    { path: '/:rest(.*)', component: { template: '<div />' } },
  ] })
  await router.push({ path: '/business-capabilities/java-methods/semantics', query: { projectId: '7', ...query }, hash: '#kept' })
  const readerProps = { projectId: 7, docs, ...extra }
  readerProps.docs = readerProps.docs.map(({ contentMd: _body, ...index }) => index) as SemanticDoc[]
  const view = mount(SourceSemanticDocsPanel, { attachTo: document.body, props: readerProps, slots,
    global: { plugins: [router, ElementPlus] } })
  wrappers.push(view); await flushPromises()
  return { view, router }
}
function reader(view: ReturnType<typeof mount>) { return view.get('[aria-label="当前业务说明"]') }
function action(view: ReturnType<typeof mount>, label: string) {
  const target = view.findAll('button').find(button => button.text().trim() === label || button.attributes('aria-label') === label)
  expect(target, `action ${label}`).toBeDefined()
  return target!
}

describe('semantic document directory and single reader', () => {
  it('always offers the project entry and generation when the document list is empty', async () => {
    const { view } = await start({ docs: [] })
    expect(action(view, '阅读项目业务说明').attributes('aria-current')).toBe('true')
    expect(reader(view).text()).toContain('当前项目尚未补充业务说明')
    await action(view, '生成项目说明').trigger('click')
    expect(view.emitted('generateProject')).toEqual([[]])
    expect(action(view, '编辑说明').attributes('disabled')).toBeDefined()
    await view.setProps({ projectGenerating: true })
    await action(view, '生成项目说明').trigger('click')
    expect(view.emitted('generateProject')).toHaveLength(1)
  })

  it('honors a typed deep link while reading only its document when subject IDs overlap', async () => {
    const { view } = await start({}, { subjectType: 'HTTP_API', subjectId: '2' })
    expect(api.body).toHaveBeenCalledWith(7, 'HTTP_API', 2)
    expect(api.body).toHaveBeenCalledOnce()
    expect(view.props('docs').every(item => !('contentMd' in item))).toBe(true)
    expect(reader(view).text()).toContain('HTTP_API完整正文')
    expect(reader(view).text()).not.toContain('BUSINESS_METHOD完整正文')
    expect(reader(view).text()).not.toContain('PROJECT完整正文')
    expect(action(view, '阅读HTTP_API说明').attributes('aria-current')).toBe('true')
    for (const value of docs) expect(action(view, `阅读${value.title}`).exists()).toBe(true)
    expect(reader(view).text()).toContain('测试模型')
    expect(reader(view).text()).toContain('contract-012')
  })

  it('resolves a source bookmark to its formal owner without duplicating directory entries or losing busy protection', async () => {
    const owner = doc('HTTP_API', 9, { title: '正式API说明', contentMd: '来源关联的正式API正文', sourceObservationIds: [2] })
    const { view, router } = await start({ docs: [doc('PROJECT', 7), doc('BUSINESS_METHOD', 2), owner],
      generatingKeys: ['HTTP_API:9'] }, { subjectType: 'SOURCE_OBSERVATION', subjectId: '2', projectCode: 'kept' })
    expect(reader(view).text()).toContain('来源关联的正式API正文')
    expect(reader(view).text()).not.toContain('BUSINESS_METHOD完整正文')
    expect(action(view, '阅读正式API说明').attributes('aria-current')).toBe('true')
    expect(view.findAll('button[aria-label^="阅读"]')).toHaveLength(3)
    expect(reader(view).find('a').attributes('href')).toContain('/business-capabilities/http-apis/9?projectId=7')
    expect(action(view, '编辑说明').attributes('disabled')).toBeDefined()
    expect(action(view, '重新生成').attributes('disabled')).toBeDefined()
    await action(view, '编辑说明').trigger('click'); await action(view, '重新生成').trigger('click')
    expect(view.emitted('edit')).toBeUndefined(); expect(view.emitted('generate')).toBeUndefined()
    await view.setProps({ generatingKeys: [] })
    await action(view, '编辑说明').trigger('click'); await action(view, '重新生成').trigger('click')
    expect(view.emitted('edit')![0][0]).toMatchObject(view.props('docs')[2])
    expect(view.emitted('generate')![0][0]).toMatchObject(view.props('docs')[2])
    expect(router.currentRoute.value.query.projectCode).toBe('kept')
    expect(router.currentRoute.value.hash).toBe('#kept')
  })

  it('keeps a selected source positioned when refresh moves it to a formal owner and clears a removed alias', async () => {
    const { view } = await start({ docs: [doc('SOURCE_OBSERVATION', 2, { contentMd: '迁移前来源正文' })] },
      { subjectType: 'SOURCE_OBSERVATION', subjectId: '2' })
    expect(reader(view).text()).toContain('迁移前来源正文')
    const owner = doc('HTTP_API', 9, { id: 99, title: '接纳后的API说明', contentMd: '迁移后正式正文', sourceObservationIds: [2] })
    await view.setProps({ docs: [doc('BUSINESS_METHOD', 2), owner] }); await flushPromises()
    expect(reader(view).text()).toContain('迁移后正式正文')
    expect(reader(view).text()).not.toContain('迁移前来源正文')
    expect(action(view, '阅读接纳后的API说明').attributes('aria-current')).toBe('true')
    await action(view, '编辑说明').trigger('click')
    expect(view.emitted('edit')![0][0]).toMatchObject(view.props('docs')[1])
    await view.setProps({ generatingKeys: ['HTTP_API:9'] })
    expect(action(view, '重新生成').attributes('disabled')).toBeDefined()
    await view.setProps({ docs: [doc('BUSINESS_METHOD', 2), { ...owner, sourceObservationIds: [] }], generatingKeys: [] }); await flushPromises()
    expect(reader(view).text()).toContain('当前不可用')
    expect(reader(view).text()).not.toContain('迁移后正式正文')
    expect(reader(view).text()).not.toContain('BUSINESS_METHOD完整正文')
  })

  it.each([{ subjectType: 'HTTP_API', subjectId: 'invalid' }, { subjectType: 'UNKNOWN', subjectId: '2' },
    { subjectType: 'HTTP_API', subjectId: '999' }])('does not substitute project prose for an unavailable bookmark %j', async query => {
    const { view } = await start({}, query)
    expect(reader(view).text()).not.toContain('PROJECT完整正文')
    expect(reader(view).text()).toContain('当前不可用')
    expect(action(view, '编辑说明').attributes('disabled')).toBeDefined()
    expect(action(view, '重新生成').attributes('disabled')).toBeDefined()
  })

  it('writes typed selection without dropping the current route parameters, query or hash', async () => {
    const { view, router } = await start({}, { projectCode: 'orders', methodKeyword: 'kept', reviewState: 'PROCESSED' })
    await action(view, '阅读BUSINESS_METHOD说明').trigger('click'); await flushPromises()
    expect(router.currentRoute.value.params).toEqual({ category: 'java-methods', workspace: 'semantics' })
    expect(router.currentRoute.value.query).toEqual({ projectId: '7', projectCode: 'orders', methodKeyword: 'kept', reviewState: 'PROCESSED',
      subjectType: 'BUSINESS_METHOD', subjectId: '2' })
    expect(router.currentRoute.value.hash).toBe('#kept')
    expect(reader(view).text()).toContain('BUSINESS_METHOD完整正文')
    await router.replace({ query: { ...router.currentRoute.value.query, subjectType: 'GROUP', subjectId: '2' } }); await flushPromises()
    expect(reader(view).text()).toContain('GROUP完整正文')
  })

  it('resolves a refreshed selection from the new document array and clears removed content', async () => {
    const { view } = await start({}, { subjectType: 'HTTP_API', subjectId: '2' })
    const fresh = doc('HTTP_API', 2, { id: 99, contentMd: '更新后的正文', status: 'edited' })
    await view.setProps({ docs: [fresh] }); await flushPromises()
    expect(reader(view).text()).toContain('更新后的正文')
    await action(view, '编辑说明').trigger('click')
    expect(view.emitted('edit')![0][0]).toMatchObject(view.props('docs')[0])
    await action(view, '重新生成').trigger('click')
    expect(view.emitted('generate')![0][0]).toMatchObject(view.props('docs')[0])
    await view.setProps({ generatingKeys: ['HTTP_API:2'] })
    await action(view, '重新生成').trigger('click')
    expect(view.emitted('generate')).toHaveLength(1)
    await view.setProps({ docs: [], generatingKeys: [] }); await flushPromises()
    expect(reader(view).text()).not.toContain('更新后的正文')
    expect(action(view, '编辑说明').attributes('disabled')).toBeDefined()
  })

  it('filters the directory after Chinese composition, keeps the selected reader and clears with focus', async () => {
    const { view } = await start({ docs: [...docs, doc('GROUP', 9, { title: '客户管理说明' })] })
    const search = view.get('input[aria-label="搜索业务说明"]')
    await search.trigger('compositionstart')
    ;(search.element as HTMLInputElement).value = '客户'
    await search.trigger('input')
    expect(action(view, '阅读HTTP_API说明').exists()).toBe(true)
    await search.trigger('compositionend')
    expect(view.findAll('button').some(button => button.attributes('aria-label') === '阅读HTTP_API说明')).toBe(false)
    expect(action(view, '阅读客户管理说明').exists()).toBe(true)
    expect(reader(view).text()).toContain('PROJECT完整正文')
    await search.setValue('没有任何匹配')
    expect(view.text()).toContain('没有匹配的说明')
    await action(view, '清除说明搜索').trigger('click')
    expect(action(view, '阅读HTTP_API说明').exists()).toBe(true)
    expect(document.activeElement).toBe(search.element)
  })

  it('resets selection and search for another project and ignores the previous project deep link', async () => {
    const { view } = await start({}, { subjectType: 'HTTP_API', subjectId: '2' })
    await view.get('input[aria-label="搜索业务说明"]').setValue('HTTP_API')
    await view.setProps({ projectId: 8, docs: [doc('HTTP_API', 2, { projectId: 8, contentMd: '新项目API正文' })] })
    expect((view.get('input[aria-label="搜索业务说明"]').element as HTMLInputElement).value).toBe('')
    expect(reader(view).text()).not.toContain('HTTP_API完整正文')
    expect(reader(view).text()).not.toContain('新项目API正文')
    expect(reader(view).text()).toContain('当前项目尚未补充业务说明')
  })

  it('keeps reading and owner links available while disabling all writes for a read-only account', async () => {
    const { view } = await start({ canWrite: false }, { subjectType: 'HTTP_API', subjectId: '2' })
    expect(reader(view).text()).toContain('仅可阅读说明')
    expect(reader(view).find('a').attributes('href')).toContain('/business-capabilities/http-apis/2?projectId=7')
    expect(action(view, '编辑说明').attributes('disabled')).toBeDefined()
    expect(action(view, '重新生成').attributes('disabled')).toBeDefined()
    await action(view, '编辑说明').trigger('click'); await action(view, '重新生成').trigger('click')
    expect(view.emitted('edit')).toBeUndefined()
    expect(view.emitted('generate')).toBeUndefined()
    await action(view, '阅读PROJECT说明').trigger('click')
    expect(action(view, '重新生成').attributes('disabled')).toBeDefined()
    await action(view, '重新生成').trigger('click')
    expect(view.emitted('generateProject')).toBeUndefined()
  })

  it('keeps project content, safe Markdown and supplied operations without mutating the original document', async () => {
    const source = '# 原始一级标题\n\n不能赋予调用权限。<script>unsafe()</script>\n\n```text\n完整代码\n```'
    const project = doc('PROJECT', 7, { contentMd: source })
    const { view } = await start({ docs: [], projectDoc: project }, {}, { toolbar: '<button>批量补充语义</button>' })
    expect(reader(view).text()).toContain('原始一级标题')
    expect(reader(view).text()).toContain('不能赋予调用权限')
    expect(reader(view).find('script').exists()).toBe(false)
    expect(reader(view).find('pre code').text()).toContain('完整代码')
    expect(view.text()).toContain('批量补充语义')
    expect(project.contentMd).toBe(source)
    await action(view, '编辑说明').trigger('click')
    expect((view.emitted('edit')![0][0] as SemanticDoc).contentMd).toBe(source)
  })
})
