import { afterEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { ElMessage, ElMessageBox } from 'element-plus'
import SharedMemoryPanel from './SharedMemoryPanel.vue'
import type { PersonalMemory, SharedPersonalMemoryApi } from '@/types/memory'

const item = (id: number, content: string): PersonalMemory => ({
  id, type: 'NOTE', content, layer: 'PERSONAL', scope: 'personal', sourceType: 'MANUAL',
})
const page = (items: PersonalMemory[]) => ({ items, total: items.length, limit: 20, offset: 0 })
const stubs = {
  LiquidGlassButton: { template: '<button><slot /></button>' },
  LiquidGlassInput: { props: ['modelValue'], emits: ['update:modelValue'], template: '<input :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)">' },
  AppDialog: { props: ['modelValue'], template: '<div v-if="modelValue"><slot /><slot name="footer" /></div>' },
  'el-form': { template: '<div><slot /></div>' },
  'el-form-item': { template: '<div><slot /></div>' },
  'el-input': { props: ['modelValue'], emits: ['update:modelValue'], template: '<textarea :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)"></textarea>' },
}
const findButton = (wrapper: ReturnType<typeof mount>, text: string) => wrapper.findAll('button').find(button => button.text() === text)!

describe('SharedMemoryPanel', () => {
  afterEach(() => vi.restoreAllMocks())

  it('revokes the selected account after confirmation and refreshes the list', async () => {
    vi.spyOn(ElMessage, 'success').mockImplementation(() => undefined as never)
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    const api: SharedPersonalMemoryApi = {
      systems: vi.fn().mockResolvedValueOnce([{ linkId: 7, projectCode: 'crm', accountHint: '•••bob' }]).mockResolvedValueOnce([]),
      list: vi.fn().mockResolvedValue(page([item(55, '内容')])), update: vi.fn(), forget: vi.fn(),
    }
    const identityApi = { revoke: vi.fn().mockResolvedValue(true) }
    const wrapper = mount(SharedMemoryPanel, { props: { api, identityApi }, global: { stubs } })
    await flushPromises()
    await findButton(wrapper, '解除当前账号关联').trigger('click'); await flushPromises()
    expect(identityApi.revoke).toHaveBeenCalledWith(7)
    expect(wrapper.text()).toContain('当前没有可查看的关联账号')
    expect(wrapper.text()).not.toContain('内容')
  })

  it('ignores an earlier account response after the employee switches accounts', async () => {
    let finishFirst!: (value: ReturnType<typeof page>) => void
    const first = new Promise<ReturnType<typeof page>>(resolve => { finishFirst = resolve })
    const api: SharedPersonalMemoryApi = {
      systems: vi.fn().mockResolvedValue([{ linkId: 1, projectCode: 'erp', accountHint: '•••ice' }, { linkId: 2, projectCode: 'crm', accountHint: '•••bob' }]),
      list: vi.fn((id: number) => id === 1 ? first : Promise.resolve(page([item(2, '第二个账号')]))),
      update: vi.fn(), forget: vi.fn(),
    }
    const wrapper = mount(SharedMemoryPanel, { props: { api }, global: { stubs } })
    await flushPromises()
    await findButton(wrapper, 'crm · •••bob').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('第二个账号')
    finishFirst(page([item(1, '旧账号结果')]))
    await flushPromises()
    expect(wrapper.text()).not.toContain('旧账号结果')
    expect(wrapper.text()).toContain('第二个账号')
  })

  it('edits without moving the source group and deletes only after confirmation', async () => {
    vi.spyOn(ElMessage, 'success').mockImplementation(() => undefined as never)
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    const api: SharedPersonalMemoryApi = {
      systems: vi.fn().mockResolvedValue([{ linkId: 7, projectCode: 'crm', accountHint: '•••bob' }]),
      list: vi.fn().mockResolvedValue(page([item(55, '原内容')])),
      update: vi.fn().mockResolvedValue(item(55, '新内容')),
      forget: vi.fn().mockResolvedValue(undefined),
    }
    const wrapper = mount(SharedMemoryPanel, { props: { api }, global: { stubs } })
    await flushPromises()
    await findButton(wrapper, '编辑').trigger('click')
    await wrapper.findAll('textarea')[1].setValue('新内容')
    await findButton(wrapper, '保存').trigger('click')
    await flushPromises()
    expect(api.update).toHaveBeenCalledWith(7, 55, { title: '', content: '新内容' })
    await findButton(wrapper, '删除').trigger('click')
    await flushPromises()
    expect(ElMessageBox.confirm).toHaveBeenCalledOnce()
    expect(api.forget).toHaveBeenCalledWith(7, 55)
  })

  it('keeps the editor open and shows a rejection when the link is revoked', async () => {
    const api: SharedPersonalMemoryApi = {
      systems: vi.fn().mockResolvedValue([{ linkId: 7, projectCode: 'crm', accountHint: '•••bob' }]),
      list: vi.fn().mockResolvedValue(page([item(55, '原内容')])),
      update: vi.fn().mockRejectedValue(new Error('关联已失效')),
      forget: vi.fn(),
    }
    const wrapper = mount(SharedMemoryPanel, { props: { api }, global: { stubs } })
    await flushPromises()
    await findButton(wrapper, '编辑').trigger('click')
    await findButton(wrapper, '保存').trigger('click')
    await flushPromises()
    expect(wrapper.find('[role="alert"]').text()).toContain('关联已失效')
    expect(wrapper.findAll('textarea')[1].element.value).toBe('原内容')
  })

  it('closes the editor if a refresh shows that the link no longer exists', async () => {
    const api: SharedPersonalMemoryApi = {
      systems: vi.fn().mockResolvedValueOnce([{ linkId: 7, projectCode: 'crm', accountHint: '•••bob' }]).mockResolvedValueOnce([]),
      list: vi.fn().mockResolvedValue(page([item(55, '原内容')])),
      update: vi.fn(), forget: vi.fn(),
    }
    const wrapper = mount(SharedMemoryPanel, { props: { api }, global: { stubs } })
    await flushPromises()
    await findButton(wrapper, '编辑').trigger('click')
    expect(wrapper.findAll('textarea')).toHaveLength(2)
    await findButton(wrapper, '刷新').trigger('click')
    await flushPromises()
    expect(wrapper.findAll('textarea')).toHaveLength(0)
    expect(wrapper.text()).toContain('当前没有可查看的关联账号')
    expect(api.update).not.toHaveBeenCalled()
  })
})
