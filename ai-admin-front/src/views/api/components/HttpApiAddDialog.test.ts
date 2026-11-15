import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import HttpApiAddDialog from './HttpApiAddDialog.vue'

const mocks = vi.hoisted(() => ({ save: vi.fn(), preview: vi.fn(), batch: vi.fn(), get: vi.fn(), accept: vi.fn() }))
vi.mock('@/api/httpApi', () => ({ saveManualHttpApi: mocks.save, previewHttpApiDocument: mocks.preview,
  saveManualHttpApiBatch: mocks.batch, getHttpApi: mocks.get, acceptHttpApi: mocks.accept }))

const project = { id: 7, name: '芋道 ERP', projectCode: 'yudao-erp', projectKind: 'SCAN' as 'SCAN' | 'REGISTERED' | 'HYBRID', contextPath: '/admin-api', baseUrl: 'http://erp:48080' }
const wrappers: ReturnType<typeof mount>[] = []

async function setup(overrides: Partial<typeof project> = {}) {
  const router = createRouter({ history: createMemoryHistory(), routes: [
    { path: '/', component: { template: '<div />' } }, { path: '/me/ai-connections', component: { template: '<div />' } }] })
  await router.push('/')
  const wrapper = mount(HttpApiAddDialog, { attachTo: document.body,
    props: { modelValue: false, project: { ...project, ...overrides }, canWrite: true },
    global: { plugins: [router, ElementPlus] } })
  await wrapper.setProps({ modelValue: true })
  await flushPromises()
  wrappers.push(wrapper)
  return { wrapper, router }
}
const body = () => document.body
const buttonByText = (text: string) => Array.from(body().querySelectorAll('button')).find(item => item.textContent?.trim() === text) as HTMLButtonElement | undefined
async function pickRadio(text: string) {
  const option = Array.from(body().querySelectorAll('.el-radio-button')).find(item => item.textContent?.trim() === text)
  ;(option?.querySelector('input') as HTMLInputElement).click()
  await flushPromises()
}
async function openTab(label: string) {
  const tab = Array.from(body().querySelectorAll('.el-tabs__item')).find(item => item.textContent?.includes(label)) as HTMLElement
  tab.click()
  await flushPromises()
}

beforeEach(() => { vi.clearAllMocks() })
afterEach(() => { wrappers.splice(0).forEach(wrapper => wrapper.unmount()); document.body.innerHTML = '' })

describe('HttpApiAddDialog', () => {
  it('opens on the agent prompt and links to the AI tool access page', async () => {
    const { router } = await setup()
    const prompt = body().querySelector('textarea[aria-label="发给智能体的提示词"]') as HTMLTextAreaElement
    expect(prompt.value).toContain('reachai_http_apis_upload')
    expect(prompt.value).toContain('项目编号 7')
    buttonByText('如何连接我的智能体工具')!.click()
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/me/ai-connections')
  })

  it('parses a pasted curl and saves only the structure', async () => {
    const { wrapper } = await setup()
    mocks.save.mockResolvedValue({ data: { apiId: 31, sourceId: 2, created: true, status: 'DISCOVERED' } })
    await openTab('粘贴 curl')
    const area = body().querySelector('textarea[aria-label="curl 命令"]') as HTMLTextAreaElement
    area.value = "curl 'http://erp:48080/admin-api/erp/stock/get?id=5' -H 'Authorization: Bearer top-secret'"
    area.dispatchEvent(new Event('input'))
    await flushPromises()
    buttonByText('解析')!.click()
    await flushPromises()
    expect(body().textContent).toContain('已解析：GET /erp/stock/get')
    buttonByText('保存 API')!.click()
    await flushPromises()
    expect(mocks.save).toHaveBeenCalledTimes(1)
    const [projectId, payload] = mocks.save.mock.calls[0]
    expect(projectId).toBe(7)
    expect(payload).toMatchObject({ httpMethod: 'GET', path: '/erp/stock/get', origin: 'curl', authentication: { mode: 'BEARER' },
      parameters: [{ name: 'id', location: 'QUERY', type: 'integer', required: false }] })
    expect(JSON.stringify(payload)).not.toContain('top-secret')
    expect(wrapper.emitted('saved')?.[0]).toEqual([{ apiId: 31 }])
  })

  it('batch-imports a document: preview, tick, unified auth and group, one batch request', async () => {
    const { wrapper } = await setup()
    const candidate = (key: string, extra: Record<string, unknown> = {}) => {
      const [method, path] = key.split(' ')
      return { key, host: null, deprecated: false, warnings: [], unsupported: null, existing: false,
        request: { httpMethod: method, path, sideEffect: method === 'GET' ? 'READ_ONLY' : 'WRITE', origin: null, parameters: [],
          requestBody: null, responseType: 'object', authentication: { mode: 'UNKNOWN' }, businessGroups: ['库存'], summary: '库存分页', description: null },
        ...extra }
    }
    mocks.preview.mockResolvedValue({ data: { format: 'OPENAPI3', title: '芋道 ERP', version: '1', fetchedFrom: null, stripPrefix: '/admin-api',
      skipped: 0, warnings: [], candidates: [candidate('GET /erp/stock/page'), candidate('POST /erp/file/upload', { unsupported: '文件上传接口暂不支持' }),
        candidate('DELETE /erp/stock/old', { deprecated: true }), candidate('POST /erp/stock/save', { existing: true })] } })
    mocks.batch.mockResolvedValue({ data: { saved: 2, failed: 0, results: [
      { index: 0, httpMethod: 'GET', path: '/erp/stock/page', ok: true, apiId: 11, created: true },
      { index: 1, httpMethod: 'POST', path: '/erp/stock/save', ok: true, apiId: 12, created: false }] } })
    await openTab('批量导入文档')
    await pickRadio('粘贴内容')
    const area = body().querySelector('textarea[aria-label="接口文档内容"]') as HTMLTextAreaElement
    area.value = '{"openapi":"3.0.1"}'
    area.dispatchEvent(new Event('input'))
    await flushPromises()
    buttonByText('解析文档')!.click()
    await flushPromises()
    expect(mocks.preview).toHaveBeenCalledWith(7, { content: '{"openapi":"3.0.1"}', fileName: undefined, stripPrefix: '/admin-api' })
    expect(body().textContent).toContain('共 4 个接口，3 个可导入，1 个已存在')
    expect(body().textContent).toContain('不能导入：文件上传接口暂不支持')
    expect(buttonByText('导入选中的 2 个接口')).toBeTruthy()
    const vm = wrapper.findComponent({ name: 'HttpApiBatchImport' }).vm as unknown as { $: { setupState: { settings: Record<string, unknown> } } }
    Object.assign(vm.$.setupState.settings, { authMode: 'BEARER', groupMode: 'CUSTOM', groupName: 'ERP' })
    await flushPromises()
    buttonByText('导入选中的 2 个接口')!.click()
    await flushPromises()
    const [projectId, apis] = mocks.batch.mock.calls[0]
    expect(projectId).toBe(7)
    expect(apis).toHaveLength(2)
    expect(apis[0]).toMatchObject({ path: '/erp/stock/page', origin: 'openapi', authentication: { mode: 'BEARER' }, businessGroups: ['ERP'], summary: '库存分页' })
    expect(body().textContent).toContain('已导入 2 个接口到「芋道 ERP」')
    expect(body().textContent).toContain('新增 1 个，更新 1 个')
    buttonByText('完成')!.click()
    await flushPromises()
    expect(wrapper.emitted('saved')?.[0]).toEqual([{ imported: true, projectId: 7 }])
  })

  it('shows the parse error from the server', async () => {
    await setup()
    mocks.preview.mockRejectedValue({ response: { data: { message: '这是网页，不是接口文档' } } })
    await openTab('批量导入文档')
    await pickRadio('文档地址')
    const input = body().querySelector('input[aria-label="文档地址"]') as HTMLInputElement
    input.value = 'https://erp.example.com/swagger-ui'
    input.dispatchEvent(new Event('input'))
    await flushPromises()
    buttonByText('解析文档')!.click()
    await flushPromises()
    expect(mocks.preview.mock.calls[0][1]).toMatchObject({ url: 'https://erp.example.com/swagger-ui' })
    expect(body().textContent).toContain('这是网页，不是接口文档')
  })
})
