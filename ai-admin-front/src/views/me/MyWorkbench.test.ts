import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { defineComponent, ref, type Ref } from 'vue'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import ElementPlus from 'element-plus'
import { platformSessionId, platformSessionUser } from '@/auth/platformSession'
import MyWorkbench from '../NoRoleLanding.vue'
import { businessSystemUrl, readWorkbenchConnections, writeWorkbenchConnections } from './myWorkbench'

const mocks = vi.hoisted(() => ({ projects: vi.fn() }))
vi.mock('@/api/scanProject', () => ({ getScanProjects: mocks.projects }))
vi.mock('@/auth/platformSession', () => ({
  platformSessionId: ref('console-a'),
  platformSessionUser: ref({ userId: 7, username: 'operator', permissions: ['platform:read'] }),
}))

const session = platformSessionId as unknown as Ref<string>
const user = platformSessionUser as unknown as Ref<{ userId: number; username: string; permissions: string[] }>
const profile = { projectCode: 'erp', systemName: '芋道 ERP', account: '1', userName: '张三' }
const crmProfile = { projectCode: 'crm', systemName: '客户管理', account: '2', userName: '李四' }
const projects = [
  { id: 1, projectCode: 'erp', name: '芋道 ERP', businessPageUrl: 'https://erp.example.com', baseUrl: 'https://api.example.com' },
  { id: 2, projectCode: 'crm', name: '客户管理', baseUrl: 'https://api.crm.example.com' },
]
const capabilities = [{ projectCode: 'erp', name: '芋道 ERP', tools: [
  { name: 'stock', description: '查询商品库存', readOnly: true },
  { name: 'create', description: '创建采购订单', readOnly: false },
] }]
const fetchMock = vi.fn()
let wrapper: ReturnType<typeof mount> | undefined
let router: Router
const assistantStub = defineComponent({
  props: ['systemName', 'expired'], emits: ['busy', 'expired'],
  template: '<div class="assistant-test"><span>{{ systemName }}</span><input aria-label="测试草稿"><button @click="$emit(\'busy\', true)">开始处理</button><button @click="$emit(\'busy\', false)">结束处理</button></div>',
})

function reply(data: unknown, status = 200) { return new Response(JSON.stringify({ code: status, data }), { status }) }
function defaultFetch(url: string, init?: RequestInit) {
  const token = new Headers(init?.headers).get('Authorization')
  if (url.endsWith('/me')) return Promise.resolve(reply(token === 'Bearer crm-session' ? crmProfile : profile))
  if (url.endsWith('/capabilities')) return Promise.resolve(reply(capabilities))
  if (url.endsWith('/calls')) return Promise.resolve(reply([{ id: 1, toolName: 'stock', businessSystem: '芋道 ERP', success: true, createdAt: '2026-11-04T10:00:00' }]))
  if (url.endsWith('/approvals')) return Promise.resolve(reply([{ approvalId: 1, tool: 'create', businessSystem: '芋道 ERP', approvalStatus: 'PENDING' }]))
  return Promise.reject(new Error('unexpected request'))
}
async function mountPage(path = '/me') {
  router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/me', component: MyWorkbench }, { path: '/me/:section', component: { template: '<div />' } }] })
  await router.push(path)
  wrapper = mount(MyWorkbench, { attachTo: document.body, global: { plugins: [router, ElementPlus], stubs: { WorkbenchAssistant: assistantStub } } })
  await flushPromises()
}
function button(text: string) {
  const element = [...document.querySelectorAll('button')].find(item => item.textContent?.trim() === text)
  if (!element) throw new Error(`Missing button: ${text}`)
  return element
}
beforeEach(() => {
  vi.clearAllMocks()
  localStorage.clear(); sessionStorage.clear()
  session.value = 'console-a'
  user.value = { userId: 7, username: 'operator', permissions: ['platform:read'] }
  mocks.projects.mockResolvedValue({ data: projects })
  fetchMock.mockImplementation(defaultFetch)
  vi.stubGlobal('fetch', fetchMock)
})
afterEach(() => { wrapper?.unmount(); wrapper = undefined; document.body.innerHTML = ''; vi.unstubAllGlobals() })

describe('personal AI workbench', () => {
  it('offers connection guidance without requesting an administrative catalog for a no-role user', async () => {
    user.value = { userId: 7, username: 'employee', permissions: [] }
    await mountPage()
    expect(mocks.projects).not.toHaveBeenCalled()
    expect(fetchMock).not.toHaveBeenCalled()
    expect(wrapper!.text()).toContain('还没有连接业务系统')
    button('连接业务系统').click()
    await flushPromises()
    expect(document.body.textContent).toContain('在个人页点击「在工作台中使用」')
  })

  it('opens configured frontend URLs and never treats a backend address as a system entry', async () => {
    await mountPage()
    const cards = wrapper!.findAll('.workbench-system')
    expect(cards[0].get('a').attributes('href')).toBe('https://erp.example.com/')
    expect(cards[0].get('a').attributes('rel')).toContain('noopener')
    expect(cards[1].find('a').exists()).toBe(false)
    expect(cards[1].text()).toContain('入口未配置')
    expect(wrapper!.findComponent(assistantStub).exists()).toBe(false)
  })

  it('verifies an explicit portal handoff and uses its business identity for activity', async () => {
    sessionStorage.setItem('reachai.employee-portal.token', 'erp-session')
    await mountPage('/me?connect=business')
    expect(router.currentRoute.value.query.connect).toBeUndefined()
    expect(readWorkbenchConnections('console-a')[0].profile.account).toBe('1')
    expect(wrapper!.text()).toContain('查询商品库存')
    expect(wrapper!.text()).toContain('1 项操作等待审批')
    expect(wrapper!.findComponent(assistantStub).props('systemName')).toBe('芋道 ERP')
    expect(fetchMock.mock.calls.every(([, init]) => new Headers(init.headers).get('Authorization') === 'Bearer erp-session')).toBe(true)
    expect(localStorage.getItem('reachai.my-workbench.preferences.v1.7')).not.toContain('erp-session')
  })

  it('does not silently import an unrelated portal session without an explicit handoff', async () => {
    sessionStorage.setItem('reachai.employee-portal.token', 'erp-session')
    await mountPage()
    expect(fetchMock).not.toHaveBeenCalled()
    expect(wrapper!.findComponent(assistantStub).exists()).toBe(false)
  })

  it('keeps drafts separate when switching systems and blocks switching during a task', async () => {
    writeWorkbenchConnections('console-a', [{ token: 'erp-session', profile }, { token: 'crm-session', profile: crmProfile }])
    await mountPage()
    const first = wrapper!.findComponent(assistantStub)
    await first.get('input').setValue('库存草稿')
    await first.get('button').trigger('click')
    expect(wrapper!.get('button.workbench-system__ask').attributes('disabled')).toBeDefined()
    await first.findAll('button')[1].trigger('click')
    await wrapper!.findAll('.workbench-system__ask')[1].trigger('click')
    await flushPromises()
    const assistants = wrapper!.findAllComponents(assistantStub)
    expect(assistants).toHaveLength(2)
    expect(assistants[0].get('input').element.value).toBe('库存草稿')
    expect(assistants[1].get('input').element.value).toBe('')
    expect(assistants[0].isVisible()).toBe(false)
    expect(assistants[1].isVisible()).toBe(true)
  })

  it('removes expired business credentials while leaving the console signed in', async () => {
    writeWorkbenchConnections('console-a', [{ token: 'expired', profile }])
    fetchMock.mockResolvedValue(reply(null, 401))
    await mountPage()
    expect(wrapper!.text()).toContain('业务登录已过期')
    expect(wrapper!.findComponent(assistantStub).exists()).toBe(false)
    expect(readWorkbenchConnections('console-a')).toEqual([])
    expect(session.value).toBe('console-a')
  })

  it('retains the mounted draft when a live conversation reports that its login expired', async () => {
    writeWorkbenchConnections('console-a', [{ token: 'erp-session', profile }])
    await mountPage()
    const assistant = wrapper!.findComponent(assistantStub)
    await assistant.get('input').setValue('需要保留的采购草稿')
    assistant.vm.$emit('expired')
    await flushPromises()
    expect(wrapper!.findComponent(assistantStub).get('input').element.value).toBe('需要保留的采购草稿')
    expect(wrapper!.findComponent(assistantStub).props('expired')).toBe(true)
    expect(readWorkbenchConnections('console-a')).toEqual([])
  })

  it('ignores an old account request completing after the console account changes', async () => {
    let resolveOld!: (value: unknown) => void
    mocks.projects.mockImplementationOnce(() => new Promise(resolve => { resolveOld = resolve }))
    await mountPage()
    session.value = 'console-b'
    user.value = { userId: 8, username: 'new-user', permissions: [] }
    await flushPromises()
    resolveOld({ data: projects })
    await flushPromises()
    expect(wrapper!.findAll('.workbench-system')).toHaveLength(0)
    expect(wrapper!.text()).toContain('new-user')
  })

  it('retains credentials through a temporary verification failure so retry can recover', async () => {
    writeWorkbenchConnections('console-a', [{ token: 'erp-session', profile }])
    fetchMock.mockRejectedValueOnce(new TypeError('network unavailable'))
    await mountPage()
    expect(wrapper!.text()).toContain('连接暂时无法验证')
    expect(readWorkbenchConnections('console-a')).toHaveLength(1)
    button('重新检查').click()
    await flushPromises()
    expect(wrapper!.findComponent(assistantStub).exists()).toBe(true)
  })

  it('can retry a new portal handoff even after removing its navigation flag', async () => {
    sessionStorage.setItem('reachai.employee-portal.token', 'erp-session')
    fetchMock.mockRejectedValueOnce(new TypeError('temporary outage'))
    await mountPage('/me?connect=business')
    expect(router.currentRoute.value.query.connect).toBeUndefined()
    button('重新检查').click()
    await flushPromises()
    expect(wrapper!.findComponent(assistantStub).props('systemName')).toBe('芋道 ERP')
  })

  it('ignores activity from the previous system when its response arrives late', async () => {
    writeWorkbenchConnections('console-a', [{ token: 'erp-session', profile }, { token: 'crm-session', profile: crmProfile }])
    let resolveOld!: (response: Response) => void
    fetchMock.mockImplementation((url, init) => {
      const token = new Headers(init.headers).get('Authorization')
      if (url.endsWith('/calls') && token === 'Bearer erp-session') return new Promise(resolve => { resolveOld = resolve })
      if (url.endsWith('/calls')) return Promise.resolve(reply([{ id: 2, toolName: 'CRM 最新记录', success: true }]))
      return defaultFetch(url, init)
    })
    await mountPage()
    await wrapper!.findAll('.workbench-system__ask')[1].trigger('click')
    await flushPromises()
    resolveOld(reply([{ id: 99, toolName: '过期的 ERP 记录', success: true }]))
    await flushPromises()
    expect(wrapper!.text()).toContain('CRM 最新记录')
    expect(wrapper!.text()).not.toContain('过期的 ERP 记录')
  })

  it('provides bounded system browsing, local search and pin preferences', async () => {
    mocks.projects.mockResolvedValue({ data: [...projects, ...Array.from({ length: 6 }, (_, i) => ({ id: i + 3, projectCode: `system-${i}`, name: `业务系统${i}` }))] })
    await mountPage()
    expect(wrapper!.findAll('.workbench-system')).toHaveLength(6)
    button('全部系统').click()
    await flushPromises()
    const input = document.querySelector('input[aria-label="搜索业务系统"]') as HTMLInputElement
    input.value = '业务系统5'; input.dispatchEvent(new Event('input', { bubbles: true }))
    await flushPromises()
    expect(document.querySelectorAll('.my-system-grid--all .workbench-system')).toHaveLength(1)
    const pin = document.querySelector('button[aria-label="置顶业务系统5"]') as HTMLButtonElement
    pin.click()
    await flushPromises()
    expect(localStorage.getItem('reachai.my-workbench.preferences.v1.7')).toContain('system-5')
  })

  it('rejects unsafe and credential-bearing system addresses', () => {
    for (const value of ['javascript:alert(1)', 'data:text/html,abc', '//example.com', 'https://user:secret@example.com']) expect(businessSystemUrl(value)).toBeUndefined()
  })
})
