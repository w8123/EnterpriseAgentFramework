import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, h, inject, provide, toRef } from 'vue'
import AiUsageActivityPanel from './AiUsageActivityPanel.vue'
import { failureRate, insightEvidence, pickDefaultModel } from './activityView'
import type { ActivityInsight, ActivityReport } from '@/types/mcp'
import type { ModelInstance } from '@/types/model'

const mocks = vi.hoisted(() => ({
  getActivityReport: vi.fn(),
  getActivityInsights: vi.fn(),
  generateActivityInsights: vi.fn(),
  updateActivityInsight: vi.fn(),
  updateActivitySetting: vi.fn(),
  getModelInstances: vi.fn(),
}))

vi.mock('@/auth/usePlatformCan', () => ({ usePlatformCan: () => ({ can: () => true, canAny: () => true }) }))
vi.mock('@/api/mcp', () => ({
  getActivityReport: (...a: unknown[]) => mocks.getActivityReport(...a),
  getActivityInsights: (...a: unknown[]) => mocks.getActivityInsights(...a),
  generateActivityInsights: (...a: unknown[]) => mocks.generateActivityInsights(...a),
  updateActivityInsight: (...a: unknown[]) => mocks.updateActivityInsight(...a),
  updateActivitySetting: (...a: unknown[]) => mocks.updateActivitySetting(...a),
}))
vi.mock('@/api/model', () => ({ getModelInstances: (...a: unknown[]) => mocks.getModelInstances(...a) }))

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
    return () => h('div', { 'data-label': props.label },
      (rows?.value ?? []).map((row) => h('div', { class: 'cell' }, slots.default?.({ row }))))
  },
})
const TabsStub = defineComponent({
  props: ['modelValue'],
  emits: ['update:modelValue'],
  setup(_props, { emit, slots }) {
    provide('changeTab', (name: string) => emit('update:modelValue', name))
    return () => h('nav', slots.default?.())
  },
})
const TabPaneStub = defineComponent({
  props: ['name', 'label'],
  setup(props) {
    const change = inject<(name: string) => void>('changeTab')!
    return () => h('button', { type: 'button', role: 'tab', 'data-name': props.name, onClick: () => change(props.name) }, props.label)
  },
})

function report(overrides: Partial<ActivityReport> = {}): ActivityReport {
  return {
    days: 30,
    projectCode: 'yudao-erp',
    summary: { total: 12, people: 3, operations: 4, failures: 2, searches: 5, lastAt: '2026-11-09T10:00:00' },
    operations: [{ projectCode: 'yudao-erp', operationKey: 'POST /erp/customer/create', operationName: '新增客户', total: 8, people: 3, failures: 2, avgDurationMs: 120, lastAt: null }],
    keywords: [],
    problems: [{ projectCode: 'yudao-erp', operationKey: 'POST /erp/customer/create', operationName: '新增客户', errorMessage: '客户名称不能为空', total: 2, people: 2, pagePath: '/erp/customer', lastAt: null }],
    people: [],
    byDay: [],
    setting: { projectCode: 'yudao-erp', enabled: true, captureSearch: true, hideUserNames: false, retentionDays: 90, updatedBy: null, updatedAt: null, saved: false },
    ...overrides,
  }
}

const insight: ActivityInsight = {
  id: 7, kind: 'PITFALL', title: '「新增客户」容易失败', summary: '做了 8 次，失败 2 次', advice: '先填写客户名称',
  shortcutPrompt: '帮我新增客户', steps: [{ operationKey: 'POST /erp/customer/create', name: '新增客户' }],
  pagePaths: ['/erp/customer'], evidence: { total: 8, failed: 2, failRate: 25, people: 2, retriedThenSucceeded: 1, gaveUp: 1 },
  status: 'SUGGESTED', usedCount: 0, generatedBy: 'RULE', updatedBy: null, updatedAt: null,
}

function mountPanel(projectCode?: string) {
  return mount(AiUsageActivityPanel, {
    props: { days: 30, projectCode, systems: [{ projectCode: 'yudao-erp', name: '芋道 ERP' }] },
    global: {
      stubs: {
        LiquidGlassNotice: { props: ['title', 'description'], template: '<div class="notice">{{ title }} {{ description }}</div>' },
        LiquidGlassEmptyState: { props: ['title', 'description'], template: '<div class="empty">{{ title }} {{ description }}<slot /></div>' },
        LiquidGlassInput: { props: ['modelValue'], emits: ['update:modelValue'], template: '<input class="prompt" :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />' },
        LiquidGlassSelect: { props: ['modelValue'], template: '<select><slot /></select>' },
        LiquidGlassTable: { template: '<div class="glass-table"><slot /></div>' },
        LiquidGlassTabs: TabsStub,
        ElTabPane: TabPaneStub,
        LiquidGlassButton: { emits: ['click'], template: '<button class="glass-button" @click="$emit(\'click\')"><slot /></button>' },
        AppDrawer: { props: ['modelValue'], template: '<aside v-if="modelValue" class="drawer"><slot /></aside>' },
        ElOption: { props: ['label', 'value'], template: '<option :value="value">{{ label }}</option>' },
        ElTable: TableStub,
        ElTableColumn: ColumnStub,
        ElSwitch: { props: ['modelValue'], template: '<input type="checkbox" :checked="modelValue" />' },
      },
      directives: { loading: () => undefined },
    },
  })
}

beforeEach(() => {
  Object.values(mocks).forEach((mock) => mock.mockReset())
  mocks.getActivityInsights.mockResolvedValue({ data: [insight] })
  mocks.getModelInstances.mockResolvedValue({ data: { data: [
    { id: 'm1', name: 'GPT', modelName: 'gpt', modelType: 'LLM', status: 'ACTIVE' },
    { id: 'm2', name: 'DeepSeek Flash', modelName: 'deepseek-v4-flash', modelType: 'LLM', status: 'ACTIVE' },
  ] } })
})

describe('AiUsageActivityPanel', () => {
  it('shows frequent operations and common problems with failure rates', async () => {
    mocks.getActivityReport.mockResolvedValue({ data: report() })
    const wrapper = mountPanel('yudao-erp')
    await flushPromises()
    expect(mocks.getActivityReport).toHaveBeenCalledWith({ days: 30, projectCode: 'yudao-erp' }, expect.any(AbortSignal))
    expect(wrapper.text()).toContain('新增客户')
    expect(wrapper.text()).toContain('25%')
    await wrapper.get('[data-name="problems"]').trigger('click')
    expect(wrapper.text()).toContain('客户名称不能为空')
  })

  it('guides the developer to switch collection on when nothing arrived yet', async () => {
    mocks.getActivityReport.mockResolvedValue({ data: report({ summary: { total: 0 } }) })
    const wrapper = mountPanel()
    await flushPromises()
    expect(wrapper.text()).toContain('reachai.activity.enabled: true')
    expect(wrapper.text()).toContain('REACHAI_ACTIVITY_ENABLED=true')
    expect(mocks.getActivityInsights).not.toHaveBeenCalled()
  })

  it('generates insights with the cheap default model and recommends one to employees', async () => {
    mocks.getActivityReport.mockResolvedValue({ data: report() })
    mocks.generateActivityInsights.mockResolvedValue({ data: {
      days: 30, events: 12, truncated: false, sessions: 4, flows: 0, pitfalls: 1, ai: true, notice: null, insights: [insight],
    } })
    mocks.updateActivityInsight.mockResolvedValue({ data: { ...insight, status: 'RECOMMENDED', shortcutPrompt: '帮我新增客户（填写名称）' } })
    const wrapper = mountPanel('yudao-erp')
    await flushPromises()
    await wrapper.get('[data-name="insights"]').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('「新增客户」容易失败')
    expect(wrapper.text()).toContain('1 次重试后成功')
    await wrapper.findAll('.glass-button').find((b) => b.text() === '重新归纳')!.trigger('click')
    await flushPromises()
    expect(mocks.generateActivityInsights).toHaveBeenCalledWith('yudao-erp', { days: 30, modelInstanceId: 'm2' })
    expect(wrapper.text()).toContain('找到 0 个常用流程、1 个易错步骤')
    await wrapper.get('input.prompt').setValue('帮我新增客户（填写名称）')
    await wrapper.findAll('.glass-button').find((b) => b.text() === '推荐给员工')!.trigger('click')
    await flushPromises()
    expect(mocks.updateActivityInsight).toHaveBeenCalledWith('yudao-erp', 7, { status: 'RECOMMENDED', shortcutPrompt: '帮我新增客户（填写名称）' })
    expect(wrapper.text()).toContain('已推荐给员工')
  })

  it('drops a slow insight answer for a system the manager already left', async () => {
    mocks.getActivityReport.mockResolvedValue({ data: report() })
    let finishFirst: (value: unknown) => void = () => undefined
    mocks.getActivityInsights
      .mockReturnValueOnce(new Promise((resolve) => { finishFirst = resolve }))
      .mockResolvedValueOnce({ data: [{ ...insight, id: 9, title: '另一个系统的结论' }] })
    const wrapper = mountPanel('yudao-erp')
    await flushPromises()
    await wrapper.setProps({ projectCode: 'crm' })
    await flushPromises()
    finishFirst({ data: [insight] })
    await flushPromises()
    await wrapper.get('[data-name="insights"]').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('另一个系统的结论')
    expect(wrapper.text()).not.toContain('「新增客户」容易失败')
  })

  it('saves collection settings for the selected system', async () => {
    mocks.getActivityReport.mockResolvedValue({ data: report() })
    mocks.updateActivitySetting.mockResolvedValue({ data: { ...report().setting!, hideUserNames: true } })
    const wrapper = mountPanel('yudao-erp')
    await flushPromises()
    await wrapper.findAll('.glass-button').find((b) => b.text() === '采集设置')!.trigger('click')
    await wrapper.findAll('.drawer .glass-button').find((b) => b.text() === '保存')!.trigger('click')
    await flushPromises()
    expect(mocks.updateActivitySetting).toHaveBeenCalledWith('yudao-erp', {
      enabled: true, captureSearch: true, hideUserNames: false, retentionDays: 90,
    })
  })
})

describe('activityView', () => {
  it('formats failure rates and evidence', () => {
    expect(failureRate(0, 0)).toBe('—')
    expect(failureRate(8, 2)).toBe('25%')
    expect(failureRate(3, 1)).toBe('33%')
    expect(failureRate(40, 1)).toBe('2.5%')
    expect(insightEvidence({ ...insight, kind: 'FLOW', evidence: { people: 2, sessions: 5 } })).toBe('2 人做过 5 次')
  })

  it('prefers a flash model for summaries', () => {
    const models = [
      { id: 'a', name: 'Qwen', modelName: 'qwen-max' },
      { id: 'b', name: 'DeepSeek', modelName: 'deepseek-v4-flash' },
    ] as ModelInstance[]
    expect(pickDefaultModel(models)?.id).toBe('b')
    expect(pickDefaultModel([])).toBeUndefined()
  })
})
