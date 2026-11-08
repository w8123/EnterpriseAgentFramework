import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { ref, type Ref } from 'vue'
import ElementPlus, { ElMessageBox } from 'element-plus'
import { platformSessionUser } from '@/auth/platformSession'
import AiToolAccess from './AiToolAccess.vue'
import { platformAiSetup } from './platformAiSetup'
import { useSidebarWorkspace } from '@/composables/useSidebarWorkspace'

const mocks = vi.hoisted(() => ({ list: vi.fn(), create: vi.fn(), revoke: vi.fn(), capabilities: vi.fn(),
  calls: vi.fn(), listApprovals: vi.fn() }))
vi.mock('@/api/platformAiConnections', () => ({
  listPlatformAiConnections: mocks.list,
  createPlatformAiConnection: mocks.create,
  revokePlatformAiConnection: mocks.revoke,
  listPlatformAiCapabilities: mocks.capabilities,
  listPlatformAiCalls: mocks.calls,
}))
vi.mock('@/api/platformAiApprovals', () => ({ listPlatformAiApprovals: mocks.listApprovals }))
vi.mock('@/auth/platformSession', () => ({ platformSessionUser: ref({ userId: 7, username: 'operator' }) }))

const owner = platformSessionUser as unknown as Ref<{ userId: number; username: string; roles?: string[]; permissions?: string[] } | null>
const connection = {
  id: 19, label: '我的 Codex', toolKind: 'CODEX', tokenHint: 'rpi_hint', status: 'ACTIVE',
  createdAt: '2026-10-28T10:00:00', expiresAt: '2026-11-04T10:00:00', lastUsedAt: null,
}
let wrapper: ReturnType<typeof mount> | undefined

function button(label: string) {
  const found = [...document.querySelectorAll('button')].reverse()
    .find(item => item.textContent?.trim() === label)
  if (!found) throw new Error(`button not found: ${label}`)
  return found as HTMLButtonElement
}

async function mountPage() {
  wrapper = mount(AiToolAccess, { attachTo: document.body, global: { plugins: [ElementPlus] } })
  await flushPromises()
}

async function openTab(label: string) {
  const tab = [...document.querySelectorAll('.el-tabs__item')].find(item => item.textContent?.trim() === label)
  if (!tab) throw new Error(`tab not found: ${label}`)
  ;(tab as HTMLElement).click()
  await flushPromises()
}

async function startCreate() {
  ;(document.querySelector('[data-tool-kind="CODEX"] input') as HTMLInputElement).click()
  await flushPromises()
  button('获取安装提示词').click()
  await flushPromises()
}

beforeEach(() => {
  vi.clearAllMocks()
  owner.value = { userId: 7, username: 'operator' }
  mocks.list.mockResolvedValue({ data: [] })
  mocks.create.mockResolvedValue({ data: { connection, token: 'rpi_one_time_secret' } })
  mocks.revoke.mockResolvedValue({ data: { ...connection, status: 'REVOKED' } })
  mocks.capabilities.mockResolvedValue({ data: [] })
  mocks.calls.mockResolvedValue({ data: [] })
  mocks.listApprovals.mockResolvedValue({ data: [] })
  localStorage.clear()
  sessionStorage.clear()
})

afterEach(() => {
  wrapper?.unmount()
  wrapper = undefined
  document.body.innerHTML = ''
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

describe('AI 工具接入 (console)', () => {
  it('updates workspace copy and only suggests capabilities returned for the account', async () => {
    owner.value = { userId: 7, username: 'admin', roles: ['PLATFORM_ADMIN'], permissions: ['*'] }
    mocks.capabilities.mockResolvedValue({ data: [
      { name: 'reachai_project_onboarding_start', description: '接入业务系统', readOnly: false },
      { name: 'reachai_doctor', description: '检查接入', readOnly: true },
      { name: 'reachai_mcp_usage', description: '查看使用情况', readOnly: true },
      { name: 'reachai_agents_list', description: '查看智能体', readOnly: true },
    ] })
    const workspace = useSidebarWorkspace()
    workspace.setWorkspace('developer')
    await mountPage()
    expect(wrapper!.text()).toContain('协助接入业务系统、改造项目和开发智能体')
    expect(wrapper!.text()).toContain('帮我接入 ReachAI')
    expect(wrapper!.text()).toContain('查看我的项目接入情况')
    expect(wrapper!.text()).not.toContain('帮我创建一个 Workflow 草稿')

    workspace.setWorkspace('manager')
    await flushPromises()
    expect(wrapper!.text()).toContain('协助了解使用情况、运营智能体和处理管理工作')
    expect(wrapper!.text()).toContain('查看 AI 使用情况')
    expect(wrapper!.text()).not.toContain('查看员工 AI 写操作审批')
    expect(wrapper!.text()).not.toContain('查看我的项目接入情况')

    workspace.setWorkspace('admin')
    await flushPromises()
    expect(wrapper!.text()).toContain('协助检查和管理 ReachAI 平台')
    expect(wrapper!.text()).toContain('帮我检查一个项目的接入配置')
    workspace.setWorkspace('my')
    await flushPromises()
    expect(wrapper!.text()).toContain('让 AI 协助你完成工作')
    expect(wrapper!.text()).toContain('查看我能访问的智能体')
  })

  it('does not suggest actions when capability loading fails', async () => {
    mocks.capabilities.mockRejectedValue(new Error('offline'))
    await mountPage()
    expect(wrapper!.text()).not.toContain('接入后，试着说')
  })

  it.each([['WORKBUDDY', 'WorkBuddy'], ['TRAE', 'TRAE'], ['DOUBAO', '豆包'], ['OPENCLAW', 'OpenClaw'], ['HERMES', 'Hermes Agent'], ['OTHER', '企业自建智能体']])(
    'keeps %s in the setup guide while using the compatible connection kind', async (clientId, clientLabel) => {
    mocks.create.mockResolvedValue({ data: {
      connection: { ...connection, toolKind: 'OTHER', label: `我的 ${clientLabel}` }, token: 'rpi_one_time_secret',
    } })
    await mountPage()
    ;(document.querySelector(`[data-tool-kind="${clientId}"] input`) as HTMLInputElement).click()
    await flushPromises()

    expect((document.querySelector('input[aria-label="凭据名称"]') as HTMLInputElement).value).toBe(`我的 ${clientLabel}`)
    button('生成接入配置').click()
    await flushPromises()

    expect(mocks.create).toHaveBeenCalledWith({ label: `我的 ${clientLabel}`, toolKind: 'OTHER', ttlDays: 7 })
    const prompt = document.querySelector('[data-testid="install-prompt"]')?.textContent ?? ''
    expect(prompt).toContain(clientLabel)
    expect(prompt).toContain('Streamable HTTP')
    expect(prompt).toContain('Authorization')
    expect(prompt).not.toContain('请帮我把 ReachAI 接入到你')
    expect(document.querySelector('[data-tool-kind="TRAE"] input')).toBeNull()
    expect(button('复制配置').disabled).toBe(false)
  })

  it('preserves a custom credential name when switching clients and locks the selection during creation', async () => {
    mocks.create.mockReturnValue(new Promise(() => {}))
    await mountPage()
    wrapper!.get<HTMLDetailsElement>('.ai-setup__scope').element.open = true
    const input = document.querySelector('input[aria-label="凭据名称"]') as HTMLInputElement
    input.value = '办公电脑'
    input.dispatchEvent(new Event('input', { bubbles: true }))
    ;(document.querySelector('[data-tool-kind="TRAE"] input') as HTMLInputElement).click()
    await flushPromises()
    expect(input.value).toBe('办公电脑')
    button('生成接入配置').click()
    await flushPromises()
    expect((document.querySelector('[data-tool-kind="CODEX"] input') as HTMLInputElement).disabled).toBe(true)
    expect(input.disabled).toBe(true)
  })

  it('reports clipboard failures and allows copying the one-time setup again', async () => {
    const writeText = vi.fn().mockRejectedValueOnce(new Error('denied')).mockResolvedValueOnce(undefined)
    vi.stubGlobal('navigator', { ...navigator, clipboard: { writeText } })
    await mountPage()
    await startCreate()
    button('复制提示词').click()
    await flushPromises()
    expect(document.body.textContent).toContain('复制失败，请选中文本手动复制')
    expect(document.querySelector('[data-testid="install-prompt"]')?.closest('details')?.open).toBe(true)
    button('复制提示词').click()
    await flushPromises()
    expect(writeText).toHaveBeenLastCalledWith(expect.stringContaining('rpi_one_time_secret'))
    expect(document.body.textContent).toContain('已复制')
    expect(document.body.textContent).not.toContain('复制失败')
    vi.unstubAllGlobals()
  })

  it('shows the install prompt once, with the secret only as an environment variable value', async () => {
    await mountPage()
    expect(wrapper!.get<HTMLDetailsElement>('.ai-setup__scope').element.open).toBe(false)
    await startCreate()

    expect(mocks.create).toHaveBeenCalledWith({ label: '我的 Codex', toolKind: 'CODEX', ttlDays: 7 })
    const prompt = document.querySelector('[data-testid="install-prompt"]')?.textContent ?? ''
    expect(prompt).toContain('export REACHAI_PLATFORM_AI_TOKEN_19="rpi_one_time_secret"')
    expect(prompt).toContain('bearer_token_env_var = "REACHAI_PLATFORM_AI_TOKEN_19"')
    expect(prompt).toContain('ReachAI 平台')
    expect(prompt).toContain('需要审核的操作先提交审核')
    expect(prompt).not.toContain('写操作会先提交审核')
    expect(JSON.stringify(localStorage)).not.toContain('rpi_one_time_secret')
    expect(JSON.stringify(sessionStorage)).not.toContain('rpi_one_time_secret')

    expect(document.activeElement?.textContent).toBe('在 Codex 中完成接入')
    expect(document.querySelector('[data-testid="install-prompt"]')?.closest('details')?.open).toBe(false)
    button('关闭接入信息').click()
    await flushPromises()
    expect(document.body.textContent).not.toContain('rpi_one_time_secret')
    expect(document.activeElement?.textContent).toBe('选择你正在用的 AI 工具')
  })

  it('does not show a secret created for the previous signed-in user', async () => {
    let resolve!: (result: unknown) => void
    mocks.create.mockReturnValue(new Promise(done => { resolve = done }))
    await mountPage()
    await startCreate()
    owner.value = { userId: 8, username: 'another' }
    await flushPromises()
    resolve({ data: { connection, token: 'rpi_previous_user_secret' } })
    await flushPromises()

    expect(document.body.textContent).not.toContain('rpi_previous_user_secret')
  })

  it('shows a load failure instead of treating it as an empty list', async () => {
    mocks.list.mockRejectedValue(new Error('offline'))
    await mountPage()
    await openTab('访问凭据')
    expect(document.body.textContent).toContain('凭据列表加载失败')
    expect(document.body.textContent).not.toContain('还没有访问凭据')
  })

  it('stops the selected credential after confirmation', async () => {
    mocks.list.mockResolvedValue({ data: [connection] })
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    await mountPage()
    await openTab('访问凭据')
    expect(document.querySelectorAll('[data-testid="credential-row"]')).toHaveLength(1)
    button('停用').click()
    await flushPromises()
    expect(mocks.revoke).toHaveBeenCalledWith(19)
    expect(mocks.list).toHaveBeenCalledTimes(2)
  })

  it('groups what the AI can do and names calls by what they did', async () => {
    mocks.capabilities.mockResolvedValue({ data: [
      { name: 'reachai_projects_list', description: '分页查询项目', readOnly: true, approvalRequired: false },
      { name: 'reachai_http_api_request_write', description: '申请 HTTP API 写入', readOnly: false, approvalRequired: true },
      { name: 'reachai_workflow_trial_read_only', description: '试运行已保存的 Workflow', readOnly: false, approvalRequired: false, executionMode: 'READ_ONLY_TRIAL' },
      { name: 'reachai_agent_create', description: '创建 Agent 身份', readOnly: false, approvalRequired: false, executionMode: 'PLATFORM_WRITE' },
    ] })
    mocks.calls.mockResolvedValue({ data: [
      { id: 1, connectionId: 19, connectionLabel: '我的 Codex', toolName: 'reachai_projects_list', success: true, latencyMs: 12, createdAt: '2026-10-29T08:00:00' },
      { id: 2, connectionId: 19, connectionLabel: '我的 Codex', toolName: 'reachai_http_api_request_write', success: false, errorCode: 'AUTH', createdAt: '2026-10-29T08:01:00' },
    ] })
    await mountPage()
    await openTab('我能办的事')
    expect(document.querySelector('[data-capability-group="platform"]')?.textContent).toContain('分页查询项目')
    expect(document.body.textContent).toContain('需要审核')
    const trialGroup = document.querySelector('.ai-capabilities__mode.is-trial')
    expect(trialGroup?.textContent).toContain('试运行已保存的 Workflow')
    expect(trialGroup?.textContent).toContain('会真实执行调用')
    expect(trialGroup?.textContent).not.toContain('创建 Agent 身份')
    expect(document.querySelector('.ai-capabilities__mode.is-write')?.textContent).toContain('创建 Agent 身份')
    expect(document.body.textContent).not.toContain('只改草稿，不影响线上')

    await openTab('任务记录')
    const rows = document.querySelectorAll('[data-testid="call-record"]')
    expect(rows).toHaveLength(2)
    expect(rows[0].textContent).toContain('分页查询项目')
    ;(document.querySelector('[data-call-filter="failed"]') as HTMLButtonElement).click()
    await flushPromises()
    expect(document.querySelectorAll('[data-testid="call-record"]')).toHaveLength(1)
  })

  it('finds capabilities beyond the preview and combines keyword and execution filters', async () => {
    mocks.capabilities.mockResolvedValue({ data: [
      ...Array.from({ length: 8 }, (_, index) => ({
        name: `reachai_query_${index + 1}`, description: `查询能力 ${index + 1}`, readOnly: true,
      })),
      { name: 'reachai_request_write', description: '提交修改', readOnly: false, approvalRequired: true },
    ] })
    await mountPage()
    await openTab('我能办的事')
    const catalog = wrapper!.get('.ai-capabilities')
    expect(catalog.text()).toContain('9 项能力')
    expect(catalog.findAll('.is-read > ul > li')).toHaveLength(6)
    expect(catalog.get('details summary').text()).toContain('查看其余 2 项')

    const search = catalog.get('input[aria-label="搜索能力说明或工具名"]')
    await search.setValue(' REACHAI_QUERY_8 ')
    expect(catalog.get('.ai-capabilities__entries').text()).toContain('查询能力 8')
    expect(catalog.find('details').exists()).toBe(false)

    await catalog.get('button.is-approval').trigger('click')
    expect(catalog.text()).toContain('没有符合条件的能力')
    await catalog.get('button[aria-label="清除搜索"]').trigger('click')
    expect(document.activeElement).toBe(search.element)
    expect(catalog.get('.ai-capabilities__mode').text()).toContain('提交修改')
    expect(catalog.find('.ai-capabilities__mode.is-read').exists()).toBe(false)
  })

  it('provides portable Codex and Claude Code setup without embedding a secret', () => {
    const codex = platformAiSetup('CODEX', 'https://reachai.example.test', 19)
    const claude = platformAiSetup('CLAUDE_CODE', 'https://reachai.example.test', 20)
    expect(codex.content).toContain('bearer_token_env_var = "REACHAI_PLATFORM_AI_TOKEN_19"')
    expect(codex.content).toContain('https://reachai.example.test/mcp/platform')
    expect(JSON.parse(claude.content).mcpServers.reachai_platform_20.headers.Authorization)
      .toBe('Bearer ${REACHAI_PLATFORM_AI_TOKEN_20}')
  })
})
