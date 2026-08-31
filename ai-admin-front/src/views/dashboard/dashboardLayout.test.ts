import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import Dashboard from './Dashboard.vue'

const mocks = vi.hoisted(() => ({
  getScanProjects: vi.fn(),
  listAgents: vi.fn(),
  getAgentStatistics: vi.fn(),
  listWorkflows: vi.fn(),
  getRecentRunOps: vi.fn(),
  controlGet: vi.fn(),
  push: vi.fn(),
}))

vi.mock('vue-router', () => ({
  useRouter: () => ({ push: mocks.push }),
}))

vi.mock('@/api/scanProject', () => ({
  getScanProjects: () => mocks.getScanProjects(),
}))

vi.mock('@/api/workflow', () => ({
  listAgents: () => mocks.listAgents(),
  getAgentStatistics: () => mocks.getAgentStatistics(),
  listWorkflows: () => mocks.listWorkflows(),
}))

vi.mock('@/api/runops', () => ({
  getRecentRunOps: (...args: unknown[]) => mocks.getRecentRunOps(...args),
}))

vi.mock('@/api/request', () => ({
  controlRequest: { get: (...args: unknown[]) => mocks.controlGet(...args) },
}))

const wrappers: VueWrapper[] = []

function mockAllReady() {
  const now = new Date().toISOString()
  mocks.getScanProjects.mockResolvedValue({
    data: [
      {
        id: 1,
        name: '订单系统',
        projectCode: 'ORDER',
        baseUrl: '',
        contextPath: '',
        scanPath: '',
        scanType: 'auto',
        toolCount: 12,
        status: 'scanned',
      },
    ],
  })
  mocks.listAgents.mockResolvedValue({
    data: [
      {
        id: 'agent-1',
        keySlug: 'order-agent',
        name: '订单助手',
        projectCode: 'ORDER',
        enabled: true,
        activeConfigVersionId: 8,
      },
    ],
  })
  mocks.listWorkflows.mockResolvedValue({
    data: [{ id: 'w1', keySlug: 'order-sync', name: '订单同步', status: 'ACTIVE' }],
  })
  mocks.getAgentStatistics.mockResolvedValue({
    data: { totalAgents: 2, enabledAgents: 1, workflowToolAgents: 1, activeWorkflowTools: 1 },
  })
  mocks.getRecentRunOps.mockResolvedValue({
    data: [
      {
        traceId: 'trace-1',
        runType: 'AGENT',
        entryType: 'API',
        status: 'COMPLETED',
        projectCode: 'ORDER',
        userId: 'user-1',
        agentId: 'agent-1',
        agentName: '订单助手',
        startedAt: now,
        latencyMs: 1200,
        tokenCost: 128,
      },
      {
        traceId: 'trace-2',
        runType: 'AGENT',
        entryType: 'API',
        status: 'FAILED',
        projectCode: 'ORDER',
        userId: 'user-2',
        agentId: 'agent-1',
        agentName: '订单助手',
        startedAt: now,
        tokenCost: 0,
      },
    ],
  })
  mocks.controlGet.mockResolvedValue({
    data: {
      services: {
        runtime: { status: 'UP' },
        capability: { status: 'UP' },
        model: { status: 'UP' },
        knowledge: { status: 'UP' },
      },
    },
  })
}

const LAYOUT_STORAGE_KEY = 'reachai.dashboard.consoleLayout.local.v1'

function readStoredLayout() {
  const raw = localStorage.getItem(LAYOUT_STORAGE_KEY)
  return raw ? JSON.parse(raw) : null
}

function findToolbarButton(wrapper: VueWrapper, testId: string) {
  return wrapper.find(`[data-testid="${testId}"]`)
}

function widgetFrames(wrapper: VueWrapper) {
  return wrapper.findAll('[data-grid-item]')
}

async function mountDashboard() {
  const wrapper = mount(Dashboard, { attachTo: document.body })
  wrappers.push(wrapper)
  await flushPromises()
  return wrapper
}

async function enterEdit(wrapper: VueWrapper) {
  await findToolbarButton(wrapper, 'edit-layout').trigger('click')
  await flushPromises()
}

describe('Dashboard 可配置布局编辑', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
    mockAllReady()
    // happy-dom 默认视口 1024px；宽屏用例统一抬高，避免受窄屏回退影响。
    vi.stubGlobal('innerWidth', 1440)
  })

  afterEach(() => {
    wrappers.splice(0).forEach((wrapper) => wrapper.unmount())
    localStorage.clear()
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  it('默认渲染 13 个领导首屏 widget，常态由顶栏进入布局编辑', async () => {
    const wrapper = await mountDashboard()
    expect(widgetFrames(wrapper)).toHaveLength(13)
    expect(wrapper.find('[data-testid="edit-layout"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="toggle-fullscreen"]').exists()).toBe(true)
    expect(wrapper.text()).not.toContain('当前浏览器布局 · revision 0')
  })

  it('进入编辑后标题栏可拖动并提供三侧缩放；取消完全恢复已保存布局', async () => {
    const wrapper = await mountDashboard()
    await enterEdit(wrapper)
    expect(wrapper.find('[data-testid="open-catalog"]').exists()).toBe(true)
    expect(wrapper.findAll('[data-grid-drag]').length).toBe(13)
    expect(wrapper.findAll('[data-grid-resize]').length).toBe(39)
    expect(wrapper.findAll('[data-grid-resize-edge="left"]').length).toBe(13)
    expect(wrapper.findAll('[data-grid-resize-edge="right"]').length).toBe(13)
    expect(wrapper.findAll('[data-grid-resize-edge="bottom"]').length).toBe(13)
    expect(wrapper.find('.dash-frame__handle').exists()).toBe(false)
    expect(wrapper.find('button[title="复制"]').exists()).toBe(false)

    // 删除一个 widget 后取消，应恢复到 13 个
    const firstFrame = widgetFrames(wrapper)[0]
    await firstFrame.find('button[title="删除"]').trigger('click')
    await flushPromises()
    expect(widgetFrames(wrapper)).toHaveLength(12)
    await findToolbarButton(wrapper, 'cancel-edit').trigger('click')
    await flushPromises()
    expect(widgetFrames(wrapper)).toHaveLength(13)
    expect(wrapper.find('[data-testid="edit-layout"]').exists()).toBe(true)
  })

  it('目录区分可添加 / 已添加 / 不可用，且可再次添加同一 widget', async () => {
    const wrapper = await mountDashboard()
    await enterEdit(wrapper)
    await findToolbarButton(wrapper, 'open-catalog').trigger('click')
    const catalog = document.querySelector('[data-testid="widget-catalog"]')!
    expect(catalog.textContent).toContain('已添加 1 个实例')
    expect(catalog.textContent).toContain('可再次添加')
    // 不可用组件展示原因且没有添加按钮
    const disabledEntry = catalog.querySelector('[data-catalog-widget="kpi.active-executions"]')!
    expect(disabledEntry.textContent).toContain('活跃执行租约尚未接入')
    expect(disabledEntry.querySelector('button')).toBeNull()
    expect(catalog.querySelector('[data-catalog-widget="cost.model-usage"]')!.textContent).toContain('不可用')

    // 再次添加同一个 KPI widget
    const addable = catalog.querySelector('[data-catalog-widget="kpi.agent-inventory"] button') as HTMLButtonElement
    addable.click()
    await flushPromises()
    expect(widgetFrames(wrapper)).toHaveLength(14)
  })

  it('添加、删除、撤销、重做形成连续历史', async () => {
    const wrapper = await mountDashboard()
    await enterEdit(wrapper)
    expect(findToolbarButton(wrapper, 'undo').attributes('disabled')).toBeDefined()

    // 添加一个 widget
    await findToolbarButton(wrapper, 'open-catalog').trigger('click')
    const catalog = document.querySelector('[data-testid="widget-catalog"]')!
    ;(catalog.querySelector('[data-catalog-widget="kpi.agent-inventory"] button') as HTMLButtonElement).click()
    await flushPromises()
    expect(widgetFrames(wrapper)).toHaveLength(14)

    // 删除一个 widget
    await widgetFrames(wrapper)[0].find('button[title="删除"]').trigger('click')
    await flushPromises()
    expect(widgetFrames(wrapper)).toHaveLength(13)

    await findToolbarButton(wrapper, 'undo').trigger('click')
    await flushPromises()
    expect(widgetFrames(wrapper)).toHaveLength(14)
    await findToolbarButton(wrapper, 'redo').trigger('click')
    await flushPromises()
    expect(widgetFrames(wrapper)).toHaveLength(13)
  })

  it('标题栏本身承担移动入口，不再渲染点阵移动和复制按钮', async () => {
    const wrapper = await mountDashboard()
    await enterEdit(wrapper)
    const firstFrame = widgetFrames(wrapper)[0]
    const titlebar = firstFrame.find('.dash-frame__bar')
    expect(titlebar.attributes('data-grid-drag')).toBeTruthy()
    expect(titlebar.attributes('tabindex')).toBe('0')
    expect(firstFrame.find('.dash-frame__handle').exists()).toBe(false)
    expect(firstFrame.find('button[title="复制"]').exists()).toBe(false)
    expect(firstFrame.find('button[title="设置"]').exists()).toBe(true)
    expect(firstFrame.find('button[title="删除"]').exists()).toBe(true)
  })

  it('保存写入 localStorage 并 revision 自增；reload 后恢复布局', async () => {
    const first = await mountDashboard()
    await enterEdit(first)
    await widgetFrames(first)[0].find('button[title="删除"]').trigger('click')
    await flushPromises()
    await findToolbarButton(first, 'save-layout').trigger('click')
    await flushPromises()

    const stored = readStoredLayout()
    expect(stored.schemaVersion).toBe(1)
    expect(stored.revision).toBe(1)
    expect(stored.items).toHaveLength(12)
    expect(first.find('[data-testid="edit-layout"]').exists()).toBe(true)
    first.unmount()
    wrappers.splice(0)

    // 重新挂载模拟 reload：恢复 12 个 widget
    const reloaded = await mountDashboard()
    expect(widgetFrames(reloaded)).toHaveLength(12)
    expect(reloaded.find('[data-testid="edit-layout"]').exists()).toBe(true)
  })

  it('localStorage 中畸形数据回退默认布局，页面不白屏', async () => {
    localStorage.setItem(LAYOUT_STORAGE_KEY, '{"schemaVersion":99')
    const wrapper = await mountDashboard()
    expect(widgetFrames(wrapper)).toHaveLength(13)
    expect(wrapper.text()).toContain('智能体运营中心')
  })

  it('localStorage 中未知版本布局回退默认布局', async () => {
    localStorage.setItem(
      LAYOUT_STORAGE_KEY,
      JSON.stringify({ schemaVersion: 2, revision: 9, mode: 'CONSOLE', columns: 12, items: [] }),
    )
    const wrapper = await mountDashboard()
    expect(widgetFrames(wrapper)).toHaveLength(13)
    expect(wrapper.find('[data-testid="edit-layout"]').exists()).toBe(true)
  })

  it('恢复默认把草稿重置为默认布局并可撤销', async () => {
    const wrapper = await mountDashboard()
    await enterEdit(wrapper)
    await widgetFrames(wrapper)[0].find('button[title="删除"]').trigger('click')
    await flushPromises()
    expect(widgetFrames(wrapper)).toHaveLength(12)
    await findToolbarButton(wrapper, 'reset-default').trigger('click')
    await flushPromises()
    expect(widgetFrames(wrapper)).toHaveLength(13)
    await findToolbarButton(wrapper, 'undo').trigger('click')
    await flushPromises()
    expect(widgetFrames(wrapper)).toHaveLength(12)
  })

  it('整理布局压缩纵向空隙', async () => {
    const wrapper = await mountDashboard()
    await enterEdit(wrapper)
    // 把最后一个“实时动态”用键盘下移 3 格制造空隙
    const activityFrame = widgetFrames(wrapper).find((frame) => frame.text().includes('实时动态'))!
    const itemEl = activityFrame.element.closest('.dash-grid__item') as HTMLElement
    const topBefore = itemEl.style.top
    const handle = activityFrame.find('[data-grid-drag]')
    for (let i = 0; i < 3; i += 1) await handle.trigger('keydown', { key: 'ArrowDown' })
    await flushPromises()
    const movedFrame = widgetFrames(wrapper).find((frame) => frame.text().includes('实时动态'))!
    const movedEl = movedFrame.element.closest('.dash-grid__item') as HTMLElement
    expect(movedEl.style.top).not.toBe(topBefore)

    // 整理布局把空隙收回，且动作可撤销
    await findToolbarButton(wrapper, 'tidy').trigger('click')
    await flushPromises()
    const tidiedFrame = widgetFrames(wrapper).find((frame) => frame.text().includes('实时动态'))!
    const tidiedEl = tidiedFrame.element.closest('.dash-grid__item') as HTMLElement
    expect(tidiedEl.style.top).toBe(topBefore)
    await findToolbarButton(wrapper, 'undo').trigger('click')
    await flushPromises()
    const undoneFrame = widgetFrames(wrapper).find((frame) => frame.text().includes('实时动态'))!
    const undoneEl = undoneFrame.element.closest('.dash-grid__item') as HTMLElement
    expect(undoneEl.style.top).not.toBe(topBefore)
  })

  it('设置面板修改实例标题并实际影响渲染', async () => {
    const wrapper = await mountDashboard()
    await enterEdit(wrapper)
    await widgetFrames(wrapper)[0].find('button[title="设置"]').trigger('click')
    await flushPromises()
    const dialog = document.querySelector('[data-testid="widget-settings"]')!
    const input = dialog.querySelector('[data-testid="widget-settings-title"]') as HTMLInputElement
    input.value = '自定义 Agent 总数'
    input.dispatchEvent(new Event('input'))
    await flushPromises()
    ;(dialog.querySelector('.dash-settings__footer .is-primary') as HTMLButtonElement).click()
    await flushPromises()
    expect(wrapper.text()).toContain('自定义 Agent 总数')
    // 撤销设置修改
    await findToolbarButton(wrapper, 'undo').trigger('click')
    await flushPromises()
    expect(wrapper.text()).not.toContain('自定义 Agent 总数')
  })

  it('TOP Agent 的 topN 设置实际改变渲染条数', async () => {
    // 提供 3 个不同 Agent 的运行样本
    const now = new Date().toISOString()
    mocks.getRecentRunOps.mockResolvedValue({
      data: [1, 2, 3].map((index) => ({
        traceId: `trace-${index}`,
        runType: 'AGENT',
        entryType: 'API',
        status: 'COMPLETED',
        projectCode: 'ORDER',
        agentId: `agent-${index}`,
        agentName: `订单助手${index}`,
        startedAt: now,
        tokenCost: 10,
      })),
    })
    const wrapper = await mountDashboard()
    const rankingFrame = widgetFrames(wrapper).find((frame) => frame.text().includes('TOP Agent'))!
    expect(rankingFrame.findAll('tbody tr')).toHaveLength(3)

    await enterEdit(wrapper)
    await rankingFrame.find('button[title="设置"]').trigger('click')
    await flushPromises()
    const dialog = document.querySelector('[data-testid="widget-settings"]')!
    const input = dialog.querySelector('[data-testid="widget-settings-topN"]') as HTMLInputElement
    input.value = '2'
    input.dispatchEvent(new Event('input'))
    await flushPromises()
    ;(dialog.querySelector('.dash-settings__footer .is-primary') as HTMLButtonElement).click()
    await flushPromises()
    expect(widgetFrames(wrapper).find((frame) => frame.text().includes('TOP Agent'))!.findAll('tbody tr')).toHaveLength(2)
  })

  it('业务系统运营的显示数量设置实际改变卡片数', async () => {
    mocks.getScanProjects.mockResolvedValue({
      data: [1, 2, 3, 4, 5].map((index) => ({
        id: index,
        name: `系统${index}`,
        projectCode: `SYS${index}`,
        baseUrl: '',
        contextPath: '',
        scanPath: '',
        scanType: 'auto',
        toolCount: 1,
        status: 'scanned',
      })),
    })
    const wrapper = await mountDashboard()
    const cardsFrame = widgetFrames(wrapper).find((frame) => frame.text().includes('业务系统运营'))!
    // 新版领导首屏默认只展示前 4 个，更多项目仍可通过设置扩展。
    expect(cardsFrame.findAll('.project-ops-card')).toHaveLength(4)

    await enterEdit(wrapper)
    await cardsFrame.find('button[title="设置"]').trigger('click')
    await flushPromises()
    const dialog = document.querySelector('[data-testid="widget-settings"]')!
    const input = dialog.querySelector('[data-testid="widget-settings-displayCount"]') as HTMLInputElement
    input.value = '2'
    input.dispatchEvent(new Event('input'))
    await flushPromises()
    ;(dialog.querySelector('.dash-settings__footer .is-primary') as HTMLButtonElement).click()
    await flushPromises()
    expect(
      widgetFrames(wrapper).find((frame) => frame.text().includes('业务系统运营'))!.findAll('.project-ops-card'),
    ).toHaveLength(2)
  })

  it('窄内容区（<1000px）禁用布局编辑并给出明确提示', async () => {
    vi.stubGlobal('innerWidth', 900)
    const wrapper = await mountDashboard()
    const hint = wrapper.find('[data-testid="narrow-edit-disabled"]')
    expect(hint.exists()).toBe(true)
    expect(hint.text()).toContain('小于 1000px')
    expect(wrapper.find('[data-testid="edit-layout"]').exists()).toBe(false)
    // 窄屏下仍可浏览真实数据
    expect(wrapper.text()).toContain('Agent 总数')
    expect(wrapper.text()).toContain('订单助手')
  })

  it('编辑态键盘操作：方向键移动、Shift+方向键调整尺寸', async () => {
    const wrapper = await mountDashboard()
    await enterEdit(wrapper)
    const trendFrame = widgetFrames(wrapper).find((frame) => frame.text().includes('运行态势'))!
    const handle = trendFrame.find('[data-grid-drag]')
    await handle.trigger('keydown', { key: 'ArrowRight' })
    await flushPromises()
    // 移动后撤销可用
    expect(findToolbarButton(wrapper, 'undo').attributes('disabled')).toBeUndefined()
    await findToolbarButton(wrapper, 'undo').trigger('click')
    await flushPromises()

    await handle.trigger('keydown', { key: 'ArrowRight', shiftKey: true })
    await flushPromises()
    expect(findToolbarButton(wrapper, 'undo').attributes('disabled')).toBeUndefined()
    await findToolbarButton(wrapper, 'undo').trigger('click')
    await flushPromises()
  })

  it('TOP Agent 框操作“查看全部”路由到 /agent 而不是 RunOps', async () => {
    mocks.push.mockClear()
    const wrapper = await mountDashboard()
    const rankingFrame = widgetFrames(wrapper).find((frame) => frame.text().includes('TOP Agent'))!
    const link = rankingFrame.find('[data-testid="open-agents-link"]')
    expect(link.exists()).toBe(true)
    await link.trigger('click')
    expect(mocks.push).toHaveBeenCalledWith('/agent')
    expect(mocks.push).not.toHaveBeenCalledWith('/runops')
  })

  it('设置对话框取消：修改不落盘，渲染与撤销历史不变', async () => {
    const wrapper = await mountDashboard()
    await enterEdit(wrapper)
    const firstFrame = widgetFrames(wrapper)[0]
    const defaultTitle = firstFrame.find('.ops-metric__label').text()
    await firstFrame.find('button[title="设置"]').trigger('click')
    await flushPromises()
    const dialog = document.querySelector('[data-testid="widget-settings"]')!
    const input = dialog.querySelector('[data-testid="widget-settings-title"]') as HTMLInputElement
    input.value = '不应生效的标题'
    input.dispatchEvent(new Event('input'))
    await flushPromises()
    // 点击“取消”
    const cancelBtn = dialog.querySelector('.dash-settings__footer button:not(.is-primary)') as HTMLButtonElement
    cancelBtn.click()
    await flushPromises()

    expect(wrapper.text()).not.toContain('不应生效的标题')
    expect(widgetFrames(wrapper)[0].find('.ops-metric__label').text()).toBe(defaultTitle)
    // 没有产生任何历史记录，撤销仍不可用
    expect(findToolbarButton(wrapper, 'undo').attributes('disabled')).toBeDefined()
  })

  it('编辑中变窄：暂停变更并保留草稿；恢复宽屏后继续同一编辑会话', async () => {
    const wrapper = await mountDashboard()
    await enterEdit(wrapper)

    // 编辑中删除一个 widget
    await widgetFrames(wrapper)[0].find('button[title="删除"]').trigger('click')
    await flushPromises()
    expect(widgetFrames(wrapper)).toHaveLength(12)
    expect(findToolbarButton(wrapper, 'save-layout').attributes('disabled')).toBeUndefined()

    // 派发 window resize 到窄屏：布局编辑暂停
    vi.stubGlobal('innerWidth', 900)
    window.dispatchEvent(new Event('resize'))
    await flushPromises()
    expect(wrapper.find('[data-testid="edit-paused"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('布局编辑已暂停')
    // 变更类按钮禁用
    expect(findToolbarButton(wrapper, 'save-layout').attributes('disabled')).toBeDefined()
    expect(findToolbarButton(wrapper, 'open-catalog').attributes('disabled')).toBeDefined()
    expect(findToolbarButton(wrapper, 'reset-default').attributes('disabled')).toBeDefined()
    // 取消仍可用
    expect(findToolbarButton(wrapper, 'cancel-edit').attributes('disabled')).toBeUndefined()
    // 草稿保留：仍是 12 个 widget，编辑会话未退出（工具栏仍处于编辑态控件组）
    expect(widgetFrames(wrapper)).toHaveLength(12)
    expect(wrapper.find('[data-testid="save-layout"]').exists()).toBe(true)

    // 恢复宽屏：同一草稿继续
    vi.stubGlobal('innerWidth', 1440)
    window.dispatchEvent(new Event('resize'))
    await flushPromises()
    expect(wrapper.find('[data-testid="edit-paused"]').exists()).toBe(false)
    expect(widgetFrames(wrapper)).toHaveLength(12)
    expect(findToolbarButton(wrapper, 'save-layout').attributes('disabled')).toBeUndefined()
  })

  it('暂停态冻结全部变更：undo/redo 禁用、设置面板关闭、操作不改草稿与历史', async () => {
    const wrapper = await mountDashboard()
    await enterEdit(wrapper)

    // 制造至少两条历史：连续删除两个组件
    await widgetFrames(wrapper)[0].find('button[title="删除"]').trigger('click')
    await flushPromises()
    await widgetFrames(wrapper)[0].find('button[title="删除"]').trigger('click')
    await flushPromises()
    // 撤销一次：撤销与重做栈同时非空
    await findToolbarButton(wrapper, 'undo').trigger('click')
    await flushPromises()
    expect(findToolbarButton(wrapper, 'undo').attributes('disabled')).toBeUndefined()
    expect(findToolbarButton(wrapper, 'redo').attributes('disabled')).toBeUndefined()
    const widgetCountBeforePause = widgetFrames(wrapper).length

    // 打开设置面板，然后变窄
    await widgetFrames(wrapper)[0].find('button[title="设置"]').trigger('click')
    await flushPromises()
    expect(document.querySelector('[data-testid="widget-settings"]')).not.toBeNull()

    vi.stubGlobal('innerWidth', 900)
    window.dispatchEvent(new Event('resize'))
    await flushPromises()

    // 暂停态：设置 overlay 已被关闭
    expect(wrapper.find('[data-testid="edit-paused"]').exists()).toBe(true)
    expect(document.querySelector('[data-testid="widget-settings"]')).toBeNull()

    // undo / redo 双双禁用
    expect(findToolbarButton(wrapper, 'undo').attributes('disabled')).toBeDefined()
    expect(findToolbarButton(wrapper, 'redo').attributes('disabled')).toBeDefined()

    // 即便程序化触发工具栏操作（绕过 disabled），Container 守卫也不放行变更
    await findToolbarButton(wrapper, 'undo').trigger('click')
    await findToolbarButton(wrapper, 'redo').trigger('click')
    await findToolbarButton(wrapper, 'tidy').trigger('click')
    await findToolbarButton(wrapper, 'reset-default').trigger('click')
    await findToolbarButton(wrapper, 'save-layout').trigger('click')
    await flushPromises()
    expect(widgetFrames(wrapper)).toHaveLength(widgetCountBeforePause)
    // 保存被阻止：localStorage 无布局写入
    expect(localStorage.getItem(LAYOUT_STORAGE_KEY)).toBeNull()

    // 恢复宽屏：同一草稿、同一历史能力
    vi.stubGlobal('innerWidth', 1440)
    window.dispatchEvent(new Event('resize'))
    await flushPromises()
    expect(wrapper.find('[data-testid="edit-paused"]').exists()).toBe(false)
    expect(widgetFrames(wrapper)).toHaveLength(widgetCountBeforePause)
    expect(findToolbarButton(wrapper, 'undo').attributes('disabled')).toBeUndefined()
    expect(findToolbarButton(wrapper, 'redo').attributes('disabled')).toBeUndefined()
    expect(findToolbarButton(wrapper, 'save-layout').attributes('disabled')).toBeUndefined()
  })

  it('刻意清空的空布局保存后 reload 保持空态，且宽屏可从目录添加', async () => {
    const first = await mountDashboard()
    await enterEdit(first)
    for (const frame of widgetFrames(first)) {
      await frame.find('button[title="删除"]').trigger('click')
      await flushPromises()
    }
    expect(first.find('[data-testid="layout-empty"]').exists()).toBe(true)
    await findToolbarButton(first, 'save-layout').trigger('click')
    await flushPromises()
    const stored = JSON.parse(localStorage.getItem(LAYOUT_STORAGE_KEY)!)
    expect(stored.items).toEqual([])
    first.unmount()
    wrappers.splice(0)

    const reloaded = await mountDashboard()
    expect(reloaded.find('[data-testid="layout-empty"]').exists()).toBe(true)
    expect(reloaded.text()).toContain('当前布局没有任何模块')
    // 宽屏空态允许直接打开目录添加
    await reloaded.find('[data-testid="layout-empty-add"]').trigger('click')
    await flushPromises()
    const catalog = document.querySelector('[data-testid="widget-catalog"]')
    expect(catalog).not.toBeNull()
  })

  it('全部 widgetKey 未知的已存布局 reload 后安全回退默认', async () => {
    localStorage.setItem(
      LAYOUT_STORAGE_KEY,
      JSON.stringify({
        schemaVersion: 1,
        revision: 3,
        mode: 'CONSOLE',
        columns: 12,
        items: [
          {
            instanceId: 'ghost',
            widgetKey: 'not.registered',
            rect: { x: 0, y: 0, w: 2, h: 2 },
            config: {},
          },
        ],
      }),
    )
    const wrapper = await mountDashboard()
    expect(widgetFrames(wrapper)).toHaveLength(13)
    expect(wrapper.find('[data-testid="edit-layout"]').exists()).toBe(true)
  })

  it('提供 9 个 Agent 样本时 topN=8 实际渲染 8 条', async () => {
    const now = new Date().toISOString()
    mocks.getRecentRunOps.mockResolvedValue({
      data: Array.from({ length: 9 }, (_, index) => ({
        traceId: `trace-${index}`,
        runType: 'AGENT',
        entryType: 'API',
        status: 'COMPLETED',
        projectCode: 'ORDER',
        agentId: `agent-${index}`,
        agentName: `订单助手${index}`,
        startedAt: now,
        tokenCost: 10,
      })),
    })
    const wrapper = await mountDashboard()
    const rankingFrame = widgetFrames(wrapper).find((frame) => frame.text().includes('TOP Agent'))!
    // 默认 topN=5，但数据已构建到 9 条候选，把 topN 调到 8 后应实际渲染 8 条
    expect(rankingFrame.findAll('tbody tr')).toHaveLength(5)

    await enterEdit(wrapper)
    await rankingFrame.find('button[title="设置"]').trigger('click')
    await flushPromises()
    const dialog = document.querySelector('[data-testid="widget-settings"]')!
    const input = dialog.querySelector('[data-testid="widget-settings-topN"]') as HTMLInputElement
    input.value = '8'
    input.dispatchEvent(new Event('input'))
    await flushPromises()
    ;(dialog.querySelector('.dash-settings__footer .is-primary') as HTMLButtonElement).click()
    await flushPromises()
    expect(
      widgetFrames(wrapper).find((frame) => frame.text().includes('TOP Agent'))!.findAll('tbody tr'),
    ).toHaveLength(8)
  })
})
