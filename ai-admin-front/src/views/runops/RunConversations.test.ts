import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import RunConversations from './RunConversations.vue'
import type { ConversationDetail, ConversationSummary } from '@/types/runops'

const api = vi.hoisted(() => ({ listRunCenterConversations: vi.fn(), getRunCenterConversation: vi.fn() }))
const navigation = vi.hoisted(() => ({ push: vi.fn(), replace: vi.fn(), query: {} as Record<string, string> }))
vi.mock('@/api/runops', () => api)
vi.mock('@/api/workflow', () => ({ listAgents: vi.fn(async () => ({ data: [{ id: 'agent-1', name: '业务助手' }] })) }))
vi.mock('vue-router', () => ({ useRoute: () => ({ query: navigation.query }), useRouter: () => navigation }))
vi.mock('@/auth/platformSession', async () => {
  const { ref } = await import('vue')
  return { platformSessionUser: ref({ permissions: ['runops:read'] }) }
})

const first: ConversationSummary = { sessionId: 'session-1', externalUserName: '用户甲', agentId: 'agent-1', questionCount: 2, lastQuestion: '查询订单', failedRunCount: 1 }
const second: ConversationSummary = { sessionId: 'session-2', externalUserName: '用户乙', agentId: 'agent-1', questionCount: 1, lastQuestion: '查询库存' }
function detail(row: ConversationSummary): ConversationDetail {
  return { conversation: row, runsAvailable: true, turns: [{ turnNo: 1, question: row.lastQuestion, answer: '本次回答', run: { traceId: `trace-${row.sessionId}`, status: 'FAILED' } }] }
}
function mountPage() {
  return mount(RunConversations, { global: { plugins: [ElementPlus], stubs: { RunCenterNav: true, AddToEvalDialog: true } } })
}

describe('Conversation workbench selection and display states', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    navigation.query = {}
    api.listRunCenterConversations.mockResolvedValue({ data: [] })
    api.getRunCenterConversation.mockImplementation(async id => ({ data: detail(id === first.sessionId ? first : second) }))
  })

  it('shows a single empty state and one query action', async () => {
    const wrapper = mountPage()
    try {
      await flushPromises()
      expect(wrapper.get('h1').text()).toBe('运行中心')
      expect(wrapper.findAll('.liquid-empty-state')).toHaveLength(1)
      expect(wrapper.text()).toContain('近 7 天暂无对话')
      expect(wrapper.text()).not.toContain('选择左侧的一次对话')
      expect(wrapper.text()).not.toContain('刷新')
      await wrapper.get('form').trigger('submit')
      await flushPromises()
      expect(api.listRunCenterConversations).toHaveBeenCalledTimes(2)
      expect(api.getRunCenterConversation).not.toHaveBeenCalled()
    } finally { wrapper.unmount() }
  })

  it('opens the first conversation, keeps run navigation, and respects evaluation permissions', async () => {
    api.listRunCenterConversations.mockResolvedValue({ data: [first, second] })
    const wrapper = mountPage()
    try {
      await flushPromises()
      expect(api.getRunCenterConversation).toHaveBeenCalledTimes(1)
      expect(api.getRunCenterConversation).toHaveBeenCalledWith(first.sessionId)
      expect(wrapper.get('.conversation-item').attributes('aria-pressed')).toBe('true')
      expect(wrapper.get('.conversation-detail').text()).toContain('本次回答')
      expect(wrapper.text()).not.toContain('加入评测集')
      const open = wrapper.findAll('button').find(button => button.text().includes('查看运行'))!
      await open.trigger('click')
      expect(navigation.push).toHaveBeenCalledWith({ name: 'RunOpsDetail', params: { traceId: 'trace-session-1' } })
    } finally { wrapper.unmount() }
  })

  it('clears the old detail after a query has no matches and can reset filters', async () => {
    api.listRunCenterConversations.mockResolvedValueOnce({ data: [first] }).mockResolvedValueOnce({ data: [] }).mockResolvedValue({ data: [second] })
    const wrapper = mountPage()
    try {
      await flushPromises()
      await wrapper.get('input[aria-label="搜索对话"]').setValue('missing')
      await wrapper.get('form').trigger('submit')
      await flushPromises()
      expect(api.listRunCenterConversations).toHaveBeenLastCalledWith(expect.objectContaining({ keyword: 'missing' }))
      expect(wrapper.find('.conversation-detail').exists()).toBe(false)
      expect(wrapper.findAll('.liquid-empty-state')).toHaveLength(1)
      expect(wrapper.text()).toContain('没有符合条件的对话')
      expect(navigation.replace).toHaveBeenLastCalledWith({ query: { keyword: 'missing' } })
      await wrapper.findAll('button').find(button => button.text() === '重置筛选')!.trigger('click')
      await flushPromises()
      expect(api.listRunCenterConversations).toHaveBeenLastCalledWith({ keyword: undefined, agentId: undefined, days: 7, problemsOnly: undefined })
      expect(wrapper.get('.conversation-detail').text()).toContain('用户乙')
    } finally { wrapper.unmount() }
  })

  it('selects an available result when the old selection is excluded', async () => {
    api.listRunCenterConversations.mockResolvedValueOnce({ data: [first] }).mockResolvedValue({ data: [second] })
    const wrapper = mountPage()
    try {
      await flushPromises()
      await wrapper.get('form').trigger('submit')
      await flushPromises()
      expect(api.getRunCenterConversation).toHaveBeenLastCalledWith(second.sessionId)
      expect(wrapper.get('.conversation-detail').text()).not.toContain('用户甲')
      expect(wrapper.get('.conversation-item').attributes('aria-pressed')).toBe('true')
    } finally { wrapper.unmount() }
  })

  it('preserves a linked session even outside the default list range', async () => {
    navigation.query = { sessionId: first.sessionId }
    const wrapper = mountPage()
    try {
      await flushPromises()
      expect(api.getRunCenterConversation).toHaveBeenCalledTimes(1)
      expect(api.getRunCenterConversation).toHaveBeenCalledWith(first.sessionId)
      expect(wrapper.get('.conversation-detail').text()).toContain('用户甲')
    } finally { wrapper.unmount() }
  })

  it('distinguishes a failed detail from no selection and retries the same session', async () => {
    api.listRunCenterConversations.mockResolvedValue({ data: [first] })
    api.getRunCenterConversation.mockRejectedValueOnce(new Error('offline'))
    const wrapper = mountPage()
    try {
      await flushPromises()
      expect(wrapper.get('.conversation-detail').text()).toContain('对话详情加载失败')
      await wrapper.get('.conversation-detail button').trigger('click')
      await flushPromises()
      expect(api.getRunCenterConversation).toHaveBeenCalledTimes(2)
      expect(api.getRunCenterConversation).toHaveBeenLastCalledWith(first.sessionId)
      expect(wrapper.get('.conversation-detail').text()).toContain('本次回答')
    } finally { wrapper.unmount() }
  })

  it('keeps a list failure separate from an empty result and supports retry', async () => {
    api.listRunCenterConversations.mockRejectedValueOnce(new Error('服务暂不可用'))
    const wrapper = mountPage()
    try {
      await flushPromises()
      expect(wrapper.text()).toContain('对话加载失败')
      expect(wrapper.text()).not.toContain('暂无对话')
      await wrapper.get('.conversation-empty button').trigger('click')
      await flushPromises()
      expect(wrapper.text()).toContain('近 7 天暂无对话')
    } finally { wrapper.unmount() }
  })

  it('ignores an older list response after a newer query completes', async () => {
    let resolveOld!: (value: { data: ConversationSummary[] }) => void
    api.listRunCenterConversations.mockImplementationOnce(() => new Promise(resolve => { resolveOld = resolve }))
      .mockResolvedValue({ data: [second] })
    const wrapper = mountPage()
    try {
      await wrapper.get('form').trigger('submit')
      await flushPromises()
      resolveOld({ data: [first] })
      await flushPromises()
      expect(wrapper.findAll('.conversation-item')).toHaveLength(1)
      expect(wrapper.get('.conversation-item').text()).toContain('用户乙')
      expect(api.getRunCenterConversation).toHaveBeenCalledTimes(1)
    } finally { wrapper.unmount() }
  })
})
