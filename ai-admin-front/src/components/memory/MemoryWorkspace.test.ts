import { afterEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus, { ElMessageBox, ElSelect } from 'element-plus'
import MemoryWorkspace from './MemoryWorkspace.vue'
import type { PersonalMemoryApi, PersonalMemoryTree } from '@/types/memory'

const tree: PersonalMemoryTree = {
  total: 0, addableProjects: [], layers: [
    { layer: 'PERSONAL', scope: 'personal', name: '个人记忆', count: 0, groups: [
      { id: 11, name: '偏好', kind: 'PREFERENCE', count: 0 },
      { id: 12, name: '备注', kind: 'NOTE', count: 0 },
    ] },
    { layer: 'PROJECT', scope: 'project:orders', projectCode: 'orders', name: '订单系统', count: 0, groups: [
      { id: 21, name: '发布备注', kind: 'NOTE', count: 0 },
    ] },
  ],
}

function memoryApi() {
  return {
    tree: vi.fn().mockResolvedValue(structuredClone(tree)),
    list: vi.fn().mockResolvedValue({ items: [], total: 0, limit: 100, offset: 0 }),
    remember: vi.fn().mockResolvedValue({ created: true }), update: vi.fn(), forget: vi.fn(),
    eraseAll: vi.fn(), createGroup: vi.fn(), updateGroup: vi.fn(), deleteGroup: vi.fn(), addProject: vi.fn(),
  } satisfies PersonalMemoryApi
}

let wrapper: ReturnType<typeof mount> | undefined
function render(api: PersonalMemoryApi, systemScoped = false) {
  wrapper = mount(MemoryWorkspace, {
    props: { api, systemScoped }, attachTo: document.body, global: {
      plugins: [ElementPlus],
      stubs: { AppDialog: {
        props: ['modelValue', 'title'],
        template: '<section v-if="modelValue" role="dialog"><h2>{{ title }}</h2><slot /><slot name="footer" /></section>',
      } },
    },
  })
  return wrapper
}

afterEach(() => { wrapper?.unmount(); document.body.innerHTML = ''; vi.restoreAllMocks() })

it('makes the current system erase scope explicit in the employee portal', async () => {
  const api = memoryApi()
  api.tree.mockResolvedValueOnce({ ...structuredClone(tree), total: 1 })
  const prompt = vi.spyOn(ElMessageBox, 'prompt').mockRejectedValueOnce('cancel')
  const view = render(api, true)
  await flushPromises()
  expect(view.text()).toContain('本系统记忆')
  await view.findAll('button').find(button => button.text() === '清空本系统记忆')!.trigger('click')
  expect(prompt.mock.calls[0][0]).toContain('不会删除其他关联系统的记忆')
  expect(api.eraseAll).not.toHaveBeenCalled()
})

describe('MemoryWorkspace', () => {
  it('creates from the empty state in the selected project group', async () => {
    const api = memoryApi()
    const view = render(api)
    await flushPromises()
    await view.findAll('button').find(button => button.text() === '发布备注0')!.trigger('click')
    await flushPromises()
    expect(api.list).toHaveBeenLastCalledWith(expect.objectContaining({ scope: 'project:orders', groupId: 21 }))
    expect(view.get('button[aria-current="true"]').text()).toBe('发布备注0')
    expect(view.text()).toContain('在「发布备注」记下第一条')

    await view.findAll('button').find(button => button.text() === '新增第一条记忆')!.trigger('click')
    expect(view.findComponent(ElSelect).props('modelValue')).toBe(21)
    await view.get('textarea').setValue('发布前核对回滚步骤')
    await view.get('[role="dialog"] input[maxlength="256"]').setValue('发布准备')
    await view.get('[role="dialog"]').findAll('button').find(button => button.text() === '保存')!.trigger('click')
    await flushPromises()
    expect(api.remember).toHaveBeenCalledWith(expect.objectContaining({ groupId: 21, title: '发布准备', content: '发布前核对回滚步骤' }))
  })

  it('distinguishes search results from a new group and clears the submitted search', async () => {
    const api = memoryApi()
    const view = render(api)
    await flushPromises()
    await view.get('input[aria-label="搜索记忆"]').setValue('周报')
    expect(view.text()).toContain('从一条记忆开始')
    await view.get('form[role="search"]').trigger('submit')
    await flushPromises()
    expect(api.list).toHaveBeenLastCalledWith(expect.objectContaining({ keyword: '周报' }))
    expect(view.text()).toContain('没有找到相关记忆')
    await view.findAll('button').find(button => button.text() === '清除搜索')!.trigger('click')
    await flushPromises()
    expect(api.list).toHaveBeenLastCalledWith(expect.objectContaining({ keyword: undefined }))
    expect(view.text()).toContain('从一条记忆开始')
  })

  it('shows a failed tree load with retry instead of claiming there are no memories', async () => {
    const api = memoryApi()
    api.tree.mockRejectedValueOnce(new Error('连接暂时不可用'))
    const view = render(api)
    await flushPromises()
    expect(view.text()).toContain('记忆暂时无法加载')
    expect(view.text()).toContain('连接暂时不可用')
    expect(view.text()).not.toContain('从一条记忆开始')
    expect(api.list).not.toHaveBeenCalled()
    await view.findAll('button').find(button => button.text() === '重新加载')!.trigger('click')
    await flushPromises()
    expect(view.text()).toContain('从一条记忆开始')
    expect(api.tree).toHaveBeenCalledTimes(2)
  })

  it('keeps the first load busy while the group catalog is pending', async () => {
    const api = memoryApi()
    let resolveTree!: (value: PersonalMemoryTree) => void
    api.tree.mockReturnValueOnce(new Promise(resolve => { resolveTree = resolve }))
    const view = render(api)
    expect(view.find('[aria-busy="true"]').exists()).toBe(true)
    expect(view.text()).not.toContain('从一条记忆开始')
    resolveTree(structuredClone(tree))
    await flushPromises()
    expect(view.find('[aria-busy="true"]').exists()).toBe(false)
    expect(view.text()).toContain('从一条记忆开始')
  })

  it('keeps a failed save in the editor without replacing the empty state with a load failure', async () => {
    const api = memoryApi()
    api.remember.mockRejectedValueOnce(new Error('保存失败'))
    const view = render(api)
    await flushPromises()
    await view.findAll('button').find(button => button.text() === '新增第一条记忆')!.trigger('click')
    await view.get('textarea').setValue('请保留这段未保存的内容')
    await view.get('[role="dialog"]').findAll('button').find(button => button.text() === '保存')!.trigger('click')
    await flushPromises()
    expect(view.get('textarea').element.value).toBe('请保留这段未保存的内容')
    expect(view.text()).toContain('从一条记忆开始')
    expect(view.text()).not.toContain('记忆暂时无法加载')
  })

  it('preserves the selected project when deleting a different empty group from its menu', async () => {
    const api = memoryApi()
    api.deleteGroup.mockResolvedValueOnce({})
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    const view = render(api)
    await flushPromises()
    await view.findAll('button').find(button => button.text() === '发布备注0')!.trigger('click')
    await view.get('button[aria-label="管理分组：偏好"]').trigger('click')
    await flushPromises()
    const remove = [...document.querySelectorAll<HTMLElement>('[role="menuitem"]')].find(item => item.textContent?.trim() === '删除分组')!
    remove.click()
    await flushPromises()
    expect(api.deleteGroup).toHaveBeenCalledWith(11)
    expect(api.list).toHaveBeenLastCalledWith(expect.objectContaining({ scope: 'project:orders', groupId: 21 }))
  })
})
