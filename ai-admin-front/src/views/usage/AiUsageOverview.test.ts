import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, h, inject, provide, reactive, toRef } from 'vue'
import AiUsageOverview from './AiUsageOverview.vue'
import { approvalSummary, filterPeople, formatCost, formatRate, formatTokens, sortPeople } from './aiUsageView'
import type { AiUsagePerson, AiUsageReport } from '@/types/mcp'

const mocks = vi.hoisted(() => ({
  getAiUsageReport: vi.fn(),
  getAiCostReport: vi.fn(),
  downloadAiAuditExport: vi.fn(),
  push: vi.fn(),
  replace: vi.fn(),
  can: vi.fn(() => true),
}))
const routeState = reactive({ path: '/usage', hash: '', query: {} as Record<string, string | undefined> })

vi.mock('@/auth/usePlatformCan', () => ({ usePlatformCan: () => ({ can: mocks.can, canAny: () => true }) }))
vi.mock('@/api/mcp', () => ({
  getAiUsageReport: (...args: unknown[]) => mocks.getAiUsageReport(...args),
  getAiCostReport: (...args: unknown[]) => mocks.getAiCostReport(...args),
  downloadAiAuditExport: (...args: unknown[]) => mocks.downloadAiAuditExport(...args),
}))
vi.mock('vue-router', async (importOriginal) => ({
  ...(await importOriginal<typeof import('vue-router')>()),
  useRoute: () => routeState,
  useRouter: () => ({ push: mocks.push, replace: mocks.replace, afterEach: () => () => undefined }),
}))

const TableStub = defineComponent({
  props: { data: { type: Array, default: () => [] } },
  setup(props, { slots }) {
    provide('rows', toRef(props, 'data'))
    return () => h('div', { class: 'table-stub' }, slots.default?.())
  },
})
const ColumnStub = defineComponent({
  props: { label: String },
  setup(props, { slots }) {
    const rows = inject<{ value: unknown[] }>('rows')
    return () =>
      h(
        'div',
        { class: 'column-stub', 'data-label': props.label },
        (rows?.value ?? []).map((row) => h('div', { class: 'cell' }, slots.default?.({ row }))),
      )
  },
})

const TabsStub = defineComponent({
  props: ['modelValue'],
  emits: ['update:modelValue'],
  setup(_props, { emit, slots }) {
    provide('changeTab', (name: string) => emit('update:modelValue', name))
    return () => h('nav', { class: 'tabs-stub' }, slots.default?.())
  },
})
const TabPaneStub = defineComponent({
  props: ['name', 'label'],
  setup(props, { slots }) {
    const change = inject<(name: string) => void>('changeTab')!
    return () =>
      h(
        'button',
        { type: 'button', role: 'tab', onClick: () => change(props.name) },
        slots.label?.() ?? props.label,
      )
  },
})

const none = { submitted: 0, succeeded: 0, failed: 0, rejected: 0, pending: 0, expired: 0 }

function person(overrides: Partial<AiUsagePerson>): AiUsagePerson {
  return {
    key: 'u:1',
    name: '张三',
    businessUserId: 1,
    accounts: ['yudao-erp · 101'],
    channels: ['CLAUDE_CODE'],
    systems: ['芋道 ERP'],
    connectionIds: [7],
    embedRuns: 0,
    embedSessions: 0,
    embedFailed: 0,
    toolCalls: 5,
    toolFailed: 1,
    approvals: { ...none, submitted: 2, succeeded: 1, rejected: 1 },
    lastActiveAt: '2026-10-27T10:00:00',
    topOperations: [],
    ...overrides,
  }
}

function report(): AiUsageReport {
  return {
    days: 30,
    since: '2026-09-27T00:00:00',
    projectCode: null,
    summary: {
      activePeople: 2,
      embedRuns: 12,
      embedSessions: 4,
      toolCalls: 5,
      toolFailedCalls: 1,
      activeConnections: 1,
      approvals: { ...none, submitted: 2, succeeded: 1, rejected: 1 },
      capturedSkills: 1,
    },
    channels: [
      { channel: 'EMBED', people: 1, uses: 12, failed: 0, approvals: none },
      {
        channel: 'CLAUDE_CODE',
        people: 1,
        uses: 5,
        failed: 1,
        approvals: { ...none, submitted: 2, succeeded: 1, rejected: 1 },
      },
      { channel: 'CODEX', people: 0, uses: 0, failed: 0, approvals: none },
      { channel: 'OTHER', people: 0, uses: 0, failed: 0, approvals: none },
    ],
    people: [
      person({
        topOperations: [
          {
            projectCode: 'yudao-erp',
            systemName: '芋道 ERP',
            toolName: 'yudao_erp_create_customer',
            label: '新建客户',
            calls: 3,
            failed: 0,
            writes: 2,
            people: 1,
          },
        ],
      }),
      person({
        key: 'u:2',
        name: '李四',
        businessUserId: 2,
        accounts: ['yudao-erp · 102'],
        channels: ['EMBED'],
        connectionIds: [],
        embedRuns: 12,
        embedSessions: 4,
        toolCalls: 0,
        toolFailed: 0,
        approvals: none,
      }),
    ],
    operations: [
      {
        projectCode: 'yudao-erp',
        systemName: '芋道 ERP',
        toolName: 'yudao_erp_create_customer',
        label: '新建客户',
        calls: 3,
        failed: 0,
        writes: 2,
        people: 1,
      },
    ],
    skills: [
      {
        skillId: 4,
        name: 'yudao-erp-erp-customer-create',
        displayName: '在芋道 ERP 中新建客户',
        version: '1.0.0',
        status: 'PUBLISHED',
        projectCode: 'yudao-erp',
        systemName: '芋道 ERP',
        createdBy: 'admin',
        createdAt: '2026-10-26T08:00:00',
      },
    ],
    systems: [{ projectCode: 'yudao-erp', name: '芋道 ERP' }],
    embedIncluded: true,
    embedNotice: null,
  }
}

const wrappers: VueWrapper[] = []

function mountPage() {
  const wrapper = mount(AiUsageOverview, {
    global: {
      stubs: {
        LiquidGlassPage: { template: '<main><slot /></main>' },
        PageHeader: { template: '<header><slot name="actions" /></header>' },
        MetricStrip: {
          props: ['items'],
          template:
            '<ul class="metrics"><li v-for="item in items" :key="item.key">{{ item.label }} {{ item.value }} {{ item.hint }}</li></ul>',
        },
        WorkbenchPanel: {
          props: ['title'],
          template:
            '<section class="panel" :data-title="title"><slot name="actions" /><slot /><slot name="footer" /></section>',
        },
        LiquidGlassNotice: {
          props: ['title', 'description'],
          template: '<div class="notice">{{ title }} {{ description }}<slot name="actions" /></div>',
        },
        LiquidGlassEmptyState: {
          props: ['title', 'description'],
          template: '<div class="empty">{{ title }} {{ description }}<slot /></div>',
        },
        LiquidGlassInput: {
          props: ['modelValue'],
          emits: ['update:modelValue'],
          methods: { focus() {} },
          template:
            '<input :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />',
        },
        LiquidGlassSelect: {
          props: ['modelValue'],
          emits: ['update:modelValue'],
          template:
            '<select :value="modelValue" @change="$emit(\'update:modelValue\', $event.target.value)"><slot /></select>',
        },
        LiquidGlassFilterBar: {
          emits: ['query'],
          template: '<form @submit.prevent="$emit(\'query\')"><slot /><slot name="actions" /></form>',
        },
        LiquidGlassTable: {
          props: ['total', 'currentPage', 'pageSize', 'empty', 'loading'],
          emits: ['change'],
          template:
            '<div class="glass-table" :data-total="total" :data-page="currentPage"><slot v-if="!empty" /><slot v-else name="empty" /><button v-if="total > currentPage * pageSize" class="next-page" @click="$emit(\'change\', currentPage + 1, pageSize)">下一页</button><button class="change-size" @click="$emit(\'change\', 1, 20)">每页20条</button></div>',
        },
        LiquidGlassTabs: TabsStub,
        ElTabPane: TabPaneStub,
        LiquidGlassButton: {
          emits: ['click'],
          template: '<button class="glass-button" @click="$emit(\'click\')"><slot /></button>',
        },
        AppDrawer: {
          props: ['modelValue', 'title'],
          template: '<aside v-if="modelValue" class="drawer" :data-title="title"><slot /></aside>',
        },
        ElOption: { props: ['label', 'value'], template: '<option :value="value">{{ label }}</option>' },
        ElTable: TableStub,
        ElTableColumn: ColumnStub,
        ElTag: { template: '<span class="el-tag"><slot /></span>' },
        ElButton: {
          emits: ['click'],
          template: '<button class="row-action" @click="$emit(\'click\', $event)"><slot /></button>',
        },
        ElIcon: { template: '<i><slot /></i>' },
      },
      directives: { loading: () => undefined },
    },
  })
  wrappers.push(wrapper)
  return wrapper
}

beforeEach(() => {
  mocks.getAiUsageReport.mockReset()
  mocks.push.mockReset()
  mocks.replace
    .mockReset()
    .mockImplementation(async (location: { query: Record<string, string | number | undefined> }) => {
      routeState.query = Object.fromEntries(
        Object.entries(location.query).map(([key, value]) => [
          key,
          value == null ? undefined : String(value),
        ]),
      )
    })
  mocks.can.mockReset().mockReturnValue(true)
  routeState.query = {}
})

afterEach(() => {
  wrappers.splice(0).forEach((wrapper) => wrapper.unmount())
})

describe('AI 使用情况', () => {
  it('shows cost by person and asks for missing model prices', async () => {
    mocks.getAiUsageReport.mockResolvedValue({ data: report() })
    const line = (key: string, name: string, cost: number | null, partial = false) => ({
      key, name, calls: 3, runs: 2, inputTokens: 120_000, outputTokens: 30_000, cost, partialCost: partial,
    })
    mocks.getAiCostReport.mockResolvedValue({
      data: {
        days: 30, projectCode: null, currency: 'CNY',
        total: line('total', '合计', 1.5, true),
        byDay: [line('2026-11-08', '2026-11-08', 1.5, true)],
        people: [line('u:1', '张三', 1.2), line('x:AUTOMATION', '自动化任务', 0.3, true)],
        systems: [], channels: [], models: [],
        unpricedModels: [{ modelInstanceId: 'pro', name: 'DeepSeek Pro' }],
        notice: null, available: true,
      },
    })
    const wrapper = mountPage()
    await flushPromises()
    await wrapper.findAll('[role="tab"]').find((tab) => tab.text() === '费用')!.trigger('click')
    await flushPromises()

    expect(mocks.getAiCostReport).toHaveBeenCalledWith({ days: 30, projectCode: undefined }, expect.any(AbortSignal))
    const panel = wrapper.find('.usage-detail-panel').text()
    expect(panel).toContain('¥1.50 起')
    expect(panel).toContain('张三')
    expect(panel).toContain('自动化任务')
    expect(panel).toContain('DeepSeek Pro 只统计了 Token')
  })

  it('formats tokens and cost for people who read Chinese', () => {
    expect(formatTokens(12_345)).toBe('12,345')
    expect(formatTokens(1_234_567)).toBe('123.5 万')
    expect(formatCost(null, 'CNY')).toBe('未填价格')
    expect(formatCost(0.004, 'CNY')).toBe('< ¥0.01')
    expect(formatCost(12.3, 'USD')).toBe('$12.30')
  })

  it('loads the last 30 days for all systems and shows the headline figures', async () => {
    mocks.getAiUsageReport.mockResolvedValue({ data: report() })
    const wrapper = mountPage()
    await flushPromises()

    expect(mocks.getAiUsageReport).toHaveBeenCalledWith(
      { days: 30, projectCode: undefined },
      expect.any(AbortSignal),
    )
    const metrics = wrapper.find('.metrics').text()
    expect(metrics).toContain('使用人数 2')
    expect(metrics).toContain('业务系统内对话 12')
    expect(metrics).toContain('成功率 80%')
    expect(metrics).toContain('通过 1 · 拒绝 1')
    expect(wrapper.find('.channel-list').text()).toContain('Claude Code')
    await wrapper
      .findAll('[role="tab"]')
      .find((tab) => tab.text().includes('业务操作'))!
      .trigger('click')
    await flushPromises()
    expect(wrapper.find('.usage-detail-panel').text()).toContain('新建客户')
    await wrapper
      .findAll('[role="tab"]')
      .find((tab) => tab.text().includes('沉淀 Skill'))!
      .trigger('click')
    await flushPromises()
    expect(wrapper.find('.usage-detail-panel').text()).toContain('在芋道 ERP 中新建客户')
  })

  it('opens a person and links to their call records and approvals', async () => {
    mocks.getAiUsageReport.mockResolvedValue({ data: report() })
    const wrapper = mountPage()
    await flushPromises()

    await wrapper.find('[aria-label="查看张三的使用详情"]').trigger('click')
    const drawer = wrapper.find('.drawer')
    expect(drawer.attributes('data-title')).toBe('张三的使用详情')
    const links = drawer.findAll('.glass-button')
    await links.find((button) => button.text().includes('AI 工具调用记录'))!.trigger('click')
    expect(mocks.push).toHaveBeenCalledWith({ path: '/mcp-hub/call-logs', query: { connectionId: '7' } })
    await links.find((button) => button.text().includes('写操作审批记录'))!.trigger('click')
    expect(mocks.push).toHaveBeenCalledWith({
      path: '/mcp-hub/write-approvals',
      query: { keyword: '101', state: 'ALL' },
    })
  })

  it('explains when the embedded chat is not included', async () => {
    mocks.getAiUsageReport.mockResolvedValue({
      data: {
        ...report(),
        embedIncluded: false,
        embedNotice: '你没有查看运行记录的权限，这里不含业务系统里的 AI 对话。',
      },
    })
    const wrapper = mountPage()
    await flushPromises()

    expect(wrapper.find('.notice').text()).toContain('不含业务系统里的 AI 对话')
    expect(wrapper.find('.metrics').text()).toContain('业务系统内对话 — 未纳入统计')
    expect(
      wrapper
        .findAll('.channel-choice')
        .find((button) => button.text().includes('业务系统内对话'))!
        .attributes('disabled'),
    ).toBeDefined()
  })

  it('shows the load error with a retry', async () => {
    mocks.getAiUsageReport.mockRejectedValueOnce({
      response: { data: { message: 'platform permission is required' } },
    })
    const wrapper = mountPage()
    await flushPromises()

    expect(wrapper.find('.notice').text()).toContain('使用情况加载失败')
    mocks.getAiUsageReport.mockResolvedValue({ data: report() })
    await wrapper.find('.notice .glass-button').trigger('click')
    await flushPromises()
    expect(wrapper.find('.metrics').exists()).toBe(true)
  })

  it('filters people by tool, toggles the filter off and preserves it in the URL without fetching again', async () => {
    mocks.getAiUsageReport.mockResolvedValue({ data: report() })
    const wrapper = mountPage()
    await flushPromises()
    const claude = wrapper.findAll('.channel-choice').find((button) => button.text().includes('Claude Code'))!
    await claude.trigger('click')
    await flushPromises()
    expect(routeState.query.usageChannel).toBe('CLAUDE_CODE')
    expect(wrapper.find('.people-view').text()).toContain('张三')
    expect(wrapper.find('.people-view').text()).not.toContain('李四')
    expect(claude.attributes('aria-pressed')).toBe('true')
    await claude.trigger('click')
    await flushPromises()
    expect(wrapper.find('.people-view').text()).toContain('李四')
    expect(mocks.getAiUsageReport).toHaveBeenCalledTimes(1)
  })

  it('distinguishes an unmatched search from no activity and clears the search', async () => {
    mocks.getAiUsageReport.mockResolvedValue({ data: report() })
    const wrapper = mountPage()
    await flushPromises()
    await wrapper.find('[aria-label="搜索员工"]').setValue('不存在的员工')
    await flushPromises()
    expect(wrapper.find('.people-view').text()).toContain('没有匹配的员工')
    expect(wrapper.find('.usage-onboarding').exists()).toBe(false)
    expect(routeState.query.usageKeyword).toBe('不存在的员工')
    await wrapper.find('.people-view .glass-button').trigger('click')
    await flushPromises()
    expect(wrapper.find('.people-view').text()).toContain('张三')
    expect(routeState.query.usageKeyword).toBeUndefined()
    expect(mocks.getAiUsageReport).toHaveBeenCalledTimes(1)
  })

  it('shows one useful empty state and can expand the reporting period', async () => {
    const empty = report()
    empty.summary = {
      activePeople: 0,
      activeConnections: 0,
      embedRuns: 0,
      embedSessions: 0,
      toolCalls: 0,
      toolFailedCalls: 0,
      approvals: none,
      capturedSkills: 0,
    }
    empty.people = []
    empty.operations = []
    empty.skills = []
    mocks.getAiUsageReport.mockResolvedValue({ data: empty })
    const wrapper = mountPage()
    await flushPromises()
    expect(wrapper.findAll('.empty')).toHaveLength(1)
    expect(wrapper.find('.channel-panel').exists()).toBe(false)
    expect(wrapper.find('.empty').text()).toContain('当前范围暂无使用记录')
    await wrapper
      .findAll('.empty .glass-button')
      .find((button) => button.text() === '查看近 90 天')!
      .trigger('click')
    await flushPromises()
    expect(mocks.getAiUsageReport).toHaveBeenLastCalledWith(
      { days: 90, projectCode: undefined },
      expect.any(AbortSignal),
    )
  })

  it('restores URL filters, sorts within the loaded subset and paginates people', async () => {
    const sample = report()
    sample.people = Array.from({ length: 25 }, (_, index) =>
      person({
        key: `u:${index}`,
        name: `员工${index}`,
        toolCalls: index,
        lastActiveAt: `2026-10-${String(index + 1).padStart(2, '0')}T10:00:00`,
      }),
    )
    sample.summary.activePeople = 250
    routeState.query = { usageDays: '7', usageSystem: 'yudao-erp', usageSort: 'recent', usageSize: '10' }
    mocks.getAiUsageReport.mockResolvedValue({ data: sample })
    const wrapper = mountPage()
    await flushPromises()
    expect(mocks.getAiUsageReport).toHaveBeenCalledWith(
      { days: 7, projectCode: 'yudao-erp' },
      expect.any(AbortSignal),
    )
    expect(wrapper.find('.view-description').text()).toContain('搜索、排序与分页作用于已载入员工')
    expect(wrapper.findAll('.row-action')).toHaveLength(10)
    expect(wrapper.findAll('.row-action')[0].attributes('aria-label')).toBe('查看员工24的使用详情')
    await wrapper.find('.next-page').trigger('click')
    await flushPromises()
    expect(routeState.query.usagePage).toBe('2')
    expect(wrapper.findAll('.row-action')[0].attributes('aria-label')).toBe('查看员工14的使用详情')
    await wrapper.find('[aria-label="搜索员工"]').setValue('员工0')
    await flushPromises()
    expect(wrapper.findAll('.row-action')).toHaveLength(1)
    expect(wrapper.find('.glass-table').attributes('data-page')).toBe('1')
  })

  it('ignores an older successful request after a newer scope has loaded', async () => {
    let resolveFirst!: (value: { data: AiUsageReport }) => void
    mocks.getAiUsageReport.mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          resolveFirst = resolve
        }),
    )
    const wrapper = mountPage()
    await flushPromises()
    const firstSignal = mocks.getAiUsageReport.mock.calls[0][1] as AbortSignal
    const latest = report()
    latest.summary.activePeople = 9
    mocks.getAiUsageReport.mockResolvedValueOnce({ data: latest })
    await wrapper.find('[aria-label="统计周期"]').setValue('7')
    await flushPromises()
    expect(firstSignal.aborted).toBe(true)
    expect(wrapper.find('.metrics').text()).toContain('使用人数 9')
    const older = report()
    older.summary.activePeople = 99
    resolveFirst({ data: older })
    await flushPromises()
    expect(wrapper.find('.metrics').text()).toContain('使用人数 9')
    expect(wrapper.find('.metrics').text()).not.toContain('使用人数 99')
  })

  it('changes the page size and resets the page in one URL update', async () => {
    const sample = report()
    sample.people = Array.from({ length: 25 }, (_, index) =>
      person({ key: `u:${index}`, name: `员工${index}` }),
    )
    sample.summary.activePeople = 25
    routeState.query = { usagePage: '2', usageSize: '10' }
    mocks.getAiUsageReport.mockResolvedValue({ data: sample })
    const wrapper = mountPage()
    await flushPromises()
    mocks.replace.mockClear()
    await wrapper.find('.change-size').trigger('click')
    await flushPromises()
    expect(mocks.replace).toHaveBeenCalledTimes(1)
    expect(routeState.query).toMatchObject({ usagePage: '1', usageSize: '20' })
    expect(wrapper.findAll('.row-action')).toHaveLength(20)
    expect(mocks.getAiUsageReport).toHaveBeenCalledTimes(1)
  })

  it('does not commit a search while Enter belongs to IME composition', async () => {
    mocks.getAiUsageReport.mockResolvedValue({ data: report() })
    const wrapper = mountPage()
    await flushPromises()
    const input = wrapper.find<HTMLInputElement>('[aria-label="搜索员工"]')
    input.element.value = '张'
    await input.trigger('input')
    await input.trigger('keydown', { key: 'Enter', isComposing: true })
    await flushPromises()
    expect(routeState.query.usageKeyword).toBeUndefined()
    await input.trigger('keydown', { key: 'Enter', isComposing: false })
    await flushPromises()
    expect(routeState.query.usageKeyword).toBe('张')
    expect(mocks.getAiUsageReport).toHaveBeenCalledTimes(1)
  })

  it('ignores an older failure and closes the person drawer when the scope changes', async () => {
    mocks.getAiUsageReport.mockResolvedValueOnce({ data: report() })
    const wrapper = mountPage()
    await flushPromises()
    await wrapper.find('[aria-label="查看张三的使用详情"]').trigger('click')
    expect(wrapper.find('.drawer').exists()).toBe(true)
    let rejectOld!: (reason: Error) => void
    mocks.getAiUsageReport.mockImplementationOnce(
      () =>
        new Promise((_resolve, reject) => {
          rejectOld = reject
        }),
    )
    await wrapper.find('[aria-label="统计周期"]').setValue('7')
    await flushPromises()
    expect(wrapper.find('.drawer').exists()).toBe(false)
    mocks.getAiUsageReport.mockResolvedValueOnce({ data: report() })
    await wrapper.find('[aria-label="统计周期"]').setValue('90')
    await flushPromises()
    rejectOld(new Error('旧请求失败'))
    await flushPromises()
    expect(wrapper.find('.notice').exists()).toBe(false)
    expect(wrapper.find('.people-view').exists()).toBe(true)
  })

  it('does not misreport unavailable embedded data as zero use or show links without permission', async () => {
    const sample = report()
    sample.embedIncluded = false
    sample.embedNotice = '暂时读不到业务系统里的 AI 对话数据'
    mocks.getAiUsageReport.mockResolvedValue({ data: sample })
    mocks.can.mockReturnValue(false)
    const wrapper = mountPage()
    await flushPromises()
    expect(wrapper.find('.metrics').text()).toContain('业务系统内对话 — 未纳入统计')
    await wrapper.find('[aria-label="查看李四的使用详情"]').trigger('click')
    expect(wrapper.find('.drawer .detail-links').text()).not.toContain('业务系统内对话')
    await wrapper
      .findAll('[role="tab"]')
      .find((tab) => tab.text().includes('沉淀 Skill'))!
      .trigger('click')
    await flushPromises()
    expect(wrapper.find('.skill-actions').exists()).toBe(false)
  })
})

describe('aiUsageView helpers', () => {
  it('summarises approvals in words and hides zero states', () => {
    expect(approvalSummary({ ...none })).toBe('没有写操作')
    expect(approvalSummary({ ...none, submitted: 4, succeeded: 2, pending: 1, expired: 1 })).toBe(
      '通过 2 · 待审批 1 · 已过期 1',
    )
  })

  it('computes success rate only with samples', () => {
    expect(formatRate(0, 0)).toBe('—')
    expect(formatRate(4, 1)).toBe('75%')
  })

  it('filters people by name, account, system and AI tool', () => {
    const people = report().people
    expect(filterPeople(people, '李', '').map((p) => p.name)).toEqual(['李四'])
    expect(filterPeople(people, '101', '').map((p) => p.name)).toEqual(['张三'])
    expect(filterPeople(people, '', 'EMBED').map((p) => p.name)).toEqual(['李四'])
    expect(filterPeople(people, '芋道', 'CLAUDE_CODE').map((p) => p.name)).toEqual(['张三'])
  })

  it('orders failure triage and pending approvals without mutating the report', () => {
    const people = [
      person({ key: 'a', toolFailed: 0, embedFailed: 3, approvals: none }),
      person({ key: 'b', toolFailed: 2, approvals: { ...none, pending: 4 } }),
    ]
    expect(sortPeople(people, 'failed').map((item) => item.key)).toEqual(['a', 'b'])
    expect(sortPeople(people, 'pending').map((item) => item.key)).toEqual(['b', 'a'])
    expect(people.map((item) => item.key)).toEqual(['a', 'b'])
  })
})
