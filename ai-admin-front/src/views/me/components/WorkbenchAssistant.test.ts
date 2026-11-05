import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import WorkbenchAssistant from './WorkbenchAssistant.vue'
import { useTheme } from '@/composables/useTheme'

const sendMessage = vi.fn()
const employee = defineComponent({
  props: ['getToken'], emits: ['busy', 'expired', 'completed'],
  setup(_, { expose }) { expose({ sendMessage }); return {} },
  template: '<div class="employee-test">正在对话</div>',
})
let wrapper: ReturnType<typeof mount> | undefined
beforeEach(() => { sendMessage.mockReset(); sendMessage.mockResolvedValue({}) })
afterEach(() => { wrapper?.unmount(); wrapper = undefined })
function mountAssistant() {
  wrapper = mount(WorkbenchAssistant, { props: {
    systemName: '芋道 ERP', getToken: () => Promise.resolve('portal-session'),
    suggestions: [{ label: '查询商品库存', prompt: '请帮我查询商品库存' }],
  }, global: { stubs: { EmployeeAssistant: employee } } })
}
describe('workbench conversation entry', () => {
  it('explicitly passes the application theme to the isolated SDK composer', async () => {
    mountAssistant()
    const { setThemeMode } = useTheme()
    try {
      setThemeMode('dark')
      await flushPromises()
      expect(wrapper!.get('.eaf-chat').attributes('data-theme')).toBe('dark')
    } finally { setThemeMode('light') }
  })

  it('fills and focuses the shared composer without executing a suggested task', async () => {
    mountAssistant()
    await wrapper!.findAll('button').find(button => button.text().includes('查询商品库存'))!.trigger('click')
    expect(wrapper!.get('textarea').element.value).toBe('请帮我查询商品库存')
    expect(sendMessage).not.toHaveBeenCalled()
  })

  it('hands the first message to the shared employee assistant and keeps busy while it is waiting', async () => {
    let finish!: () => void
    sendMessage.mockImplementation(() => new Promise<void>(resolve => { finish = resolve }))
    mountAssistant()
    await wrapper!.get('textarea').setValue('查询本月库存')
    await wrapper!.get('form').trigger('submit')
    await flushPromises()
    expect(sendMessage).toHaveBeenCalledWith('查询本月库存')
    const child = wrapper!.findComponent(employee)
    child.vm.$emit('busy', true)
    finish()
    await flushPromises()
    expect(wrapper!.emitted('busy')!.slice(-1)[0]).toEqual([true])
    child.vm.$emit('busy', false)
    await flushPromises()
    expect(wrapper!.emitted('busy')!.slice(-1)[0]).toEqual([false])
  })

  it('restores the exact first draft when authentication fails without replaying it', async () => {
    sendMessage.mockRejectedValue(Object.assign(new Error('业务登录已过期'), { name: 'EafChatTokenError', httpStatus: 401 }))
    mountAssistant()
    await wrapper!.get('textarea').setValue('创建采购单，备注：下周送达')
    await wrapper!.get('form').trigger('submit')
    await flushPromises()
    expect(wrapper!.get('textarea').element.value).toBe('创建采购单，备注：下周送达')
    expect(wrapper!.text()).toContain('业务登录已过期')
    expect(wrapper!.emitted('expired')).toHaveLength(1)
    expect(sendMessage).toHaveBeenCalledTimes(1)
  })
})
