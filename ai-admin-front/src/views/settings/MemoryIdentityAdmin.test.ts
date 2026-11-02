import { afterEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { ElMessageBox } from 'element-plus'
import MemoryIdentityAdmin from './MemoryIdentityAdmin.vue'
import * as api from '@/api/platformIdentity'
import type { BusinessUserView, ExternalIdentityView } from '@/api/platformIdentity'

vi.mock('@/api/platformIdentity', () => ({
  listBusinessUsers: vi.fn(), listBusinessUserIdentities: vi.fn(), listPersonalMemoryIdentityLinks: vi.fn(),
  createPersonalMemoryIdentityLink: vi.fn(), revokePersonalMemoryIdentityLink: vi.fn(),
}))
const user = { id: 1, tenantId: 'tenant', globalUserId: 'alice', displayName: 'Alice', status: 'ACTIVE' } as BusinessUserView
const identity = (id: number, appId: string, externalUserId: string) => ({ id, appId, externalUserId, status: 'ACTIVE' } as ExternalIdentityView)
const stubs = {
  LiquidGlassButton: { template: '<button><slot /></button>' },
  LiquidGlassInput: { props: ['modelValue'], emits: ['update:modelValue'], template: '<input :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)">' },
}
const button = (wrapper: ReturnType<typeof mount>, label: string) => wrapper.findAll('button').find(item => item.text() === label)!

describe('MemoryIdentityAdmin', () => {
  afterEach(() => { vi.resetAllMocks(); vi.restoreAllMocks() })
  it('selects both verified accounts, requires evidence, and confirms before creating or revoking', async () => {
    vi.mocked(api.listPersonalMemoryIdentityLinks).mockResolvedValue({ data: { items: [{ id: 91, peerBindingId: 22, peerAppId: 'crm', peerExternalUserId: 'bob', verificationMethod: 'SELF', createdAt: '' }], hasMore: false } } as never)
    vi.mocked(api.listBusinessUsers).mockResolvedValue({ data: { records: [{ ...user, id: 2, globalUserId: 'bob' }], total: 1 } } as never)
    vi.mocked(api.listBusinessUserIdentities).mockResolvedValue({ data: [identity(22, 'crm', 'bob')] } as never)
    vi.mocked(api.createPersonalMemoryIdentityLink).mockResolvedValue({ data: { id: 92 } } as never)
    vi.mocked(api.revokePersonalMemoryIdentityLink).mockResolvedValue({} as never)
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    const wrapper = mount(MemoryIdentityAdmin, { props: { user, identities: [identity(11, 'erp', 'alice')] }, global: { stubs } })
    await wrapper.find('#memory-link-source').setValue('11'); await flushPromises()
    await button(wrapper, '解除').trigger('click'); expect(wrapper.text()).toContain('请先填写至少 4 字')
    await wrapper.find('input').setValue('bob'); await wrapper.find('form').trigger('submit'); await flushPromises()
    await wrapper.find('#memory-link-user').setValue('2'); await flushPromises()
    await wrapper.find('#memory-link-target').setValue('22')
    await wrapper.find('textarea').setValue('人工核验员工资料')
    await button(wrapper, '关联账号').trigger('click'); await flushPromises()
    expect(api.listBusinessUsers).toHaveBeenCalledWith(expect.objectContaining({ tenantId: 'tenant', keyword: 'bob' }))
    expect(api.createPersonalMemoryIdentityLink).toHaveBeenCalledWith(11, 22, '人工核验员工资料')
    await wrapper.find('textarea').setValue('员工请求解除')
    await button(wrapper, '解除').trigger('click'); await flushPromises()
    expect(api.revokePersonalMemoryIdentityLink).toHaveBeenCalledWith(91, '员工请求解除')
    expect(ElMessageBox.confirm).toHaveBeenCalledTimes(2)
    wrapper.unmount()
  })

  it('ignores a stale link response after selecting another source account', async () => {
    let resolveFirst!: (value: unknown) => void
    vi.mocked(api.listPersonalMemoryIdentityLinks).mockImplementation((id: number) => id === 11
      ? new Promise(resolve => { resolveFirst = resolve as (value: unknown) => void })
      : Promise.resolve({ data: { items: [], hasMore: false } } as never))
    const wrapper = mount(MemoryIdentityAdmin, { props: { user, identities: [identity(11, 'erp', 'alice'), identity(12, 'oa', 'alice')] }, global: { stubs } })
    await wrapper.find('#memory-link-source').setValue('11')
    await wrapper.find('#memory-link-source').setValue('12'); await flushPromises()
    resolveFirst({ data: { items: [{ id: 1, peerAppId: 'old', peerExternalUserId: 'old' }], hasMore: false } }); await flushPromises()
    expect(wrapper.text()).not.toContain('old')
    expect(wrapper.text()).toContain('暂无关联')
    wrapper.unmount()
  })

  it('cancels a pending revoke if the selected source changes during confirmation', async () => {
    vi.mocked(api.listPersonalMemoryIdentityLinks).mockResolvedValue({ data: { items: [{ id: 91, peerBindingId: 22, peerAppId: 'crm', peerExternalUserId: 'bob', verificationMethod: 'SELF', createdAt: '' }], hasMore: false } } as never)
    let confirm!: (value: never) => void
    vi.spyOn(ElMessageBox, 'confirm').mockImplementation(() => new Promise(resolve => { confirm = resolve as (value: never) => void }))
    const wrapper = mount(MemoryIdentityAdmin, { props: { user, identities: [identity(11, 'erp', 'alice'), identity(12, 'oa', 'alice')] }, global: { stubs } })
    await wrapper.find('#memory-link-source').setValue('11'); await flushPromises()
    await wrapper.find('textarea').setValue('员工请求解除')
    await button(wrapper, '解除').trigger('click')
    await wrapper.find('#memory-link-source').setValue('12'); await flushPromises()
    await wrapper.find('#memory-link-source').setValue('11'); await flushPromises()
    confirm('confirm' as never); await flushPromises()
    expect(api.revokePersonalMemoryIdentityLink).not.toHaveBeenCalled()
    wrapper.unmount()
  })
})
