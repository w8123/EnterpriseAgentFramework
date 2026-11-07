import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import HttpApiAddDialog from './HttpApiAddDialog.vue'

const mocks = vi.hoisted(() => ({ save: vi.fn(), detail: vi.fn(), update: vi.fn(), rescan: vi.fn() }))
vi.mock('@/api/httpApi', () => ({ saveManualHttpApi: mocks.save }))
vi.mock('@/api/scanProject', () => ({ getScanProjectDetail: mocks.detail, updateScanProject: mocks.update, triggerRescan: mocks.rescan }))

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

  it('imports a Swagger 2 document by saving it on the project and rescanning', async () => {
    const { wrapper } = await setup()
    mocks.detail.mockResolvedValue({ data: { id: 7, name: '芋道 ERP', projectCode: 'yudao-erp', projectKind: 'SCAN', environment: 'dev',
      visibility: 'PROJECT', baseUrl: 'http://erp:48080', contextPath: '/admin-api', owner: null } })
    mocks.update.mockResolvedValue({ data: {} })
    mocks.rescan.mockResolvedValue({ data: { toolCount: 1 } })
    await openTab('导入 OpenAPI')
    const area = body().querySelector('textarea[aria-label="OpenAPI 文档内容"]') as HTMLTextAreaElement
    area.value = JSON.stringify({ swagger: '2.0', info: { title: 't', version: '1' }, paths: { '/a': { get: { responses: { 200: { description: 'ok' } } } } } })
    area.dispatchEvent(new Event('input'))
    await flushPromises()
    buttonByText('上传并导入')!.click()
    await flushPromises()
    const request = mocks.update.mock.calls[0][1]
    expect(JSON.parse(request.specContent).openapi).toBe('3.0.3')
    expect(request).toMatchObject({ name: '芋道 ERP', projectKind: 'SCAN', contextPath: '/admin-api' })
    expect(mocks.rescan).toHaveBeenCalledWith(7)
    expect(wrapper.emitted('saved')?.[0]).toEqual([{ imported: true }])
  })

  it('tells SDK projects to use the other ways instead of uploading a document', async () => {
    await setup({ projectKind: 'REGISTERED' })
    await openTab('导入 OpenAPI')
    expect(body().textContent).toContain('这个项目通过 SDK 自动同步接口')
    expect(buttonByText('上传并导入')).toBeUndefined()
  })
})
