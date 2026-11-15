import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import PageActionEffectReviewDialog from './PageActionEffectReviewDialog.vue'

const api = vi.hoisted(() => ({ previewPageActionEffect: vi.fn(), approvePageActionEffect: vi.fn(), revokePageActionEffect: vi.fn() }))
vi.mock('@/api/pageActionEffectReview', () => api)

const contract = {
  projectCode: 'mall', pageKey: 'profile', actionKey: 'fill', actionTitle: '填写资料',
  actionDescription: '填写个人资料', routePattern: '/profile', implementationVersion: 'v1',
  inputSchema: { type: 'object', properties: { nickName: { type: 'string' }, introduceSign: { type: 'string' } } },
  riskLevel: 'WRITE', confirmRequired: true, catalogHash: 'hash', currentRevision: 3, approval: null,
}
let wrapper: VueWrapper
function render() {
  wrapper = mount(PageActionEffectReviewDialog, {
    props: { modelValue: true, projectCode: 'mall', pageKey: 'profile', actionKey: 'fill' },
    global: { plugins: [ElementPlus], stubs: { AppDialog: { props: ['modelValue'], template: '<div v-if="modelValue"><slot /></div>' } } },
  })
  return wrapper
}
function button(text: string) { return wrapper.findAll('button').find((item) => item.text().includes(text))! }
async function fillDeclaration() {
  await wrapper.find('#effect-version').setValue('profile-v1')
  await wrapper.find('#effect-field-0').setValue('昵称')
  await wrapper.find('#effect-field-1').setValue('个性签名')
  await wrapper.find('#effect-target').setValue('填写个人资料')
  await wrapper.find('input[type="checkbox"]').setValue(true)
}
beforeEach(() => { vi.resetAllMocks(); api.previewPageActionEffect.mockResolvedValue({ data: { ...contract } }) })
afterEach(() => wrapper?.unmount())

describe('页面动作效果审核', () => {
  it('默认未知；确认目标、版本及责任后才提交并固定审核修订', async () => {
    render(); await flushPromises()
    expect(wrapper.text()).toContain('未审核或已失效 · UNKNOWN')
    expect(wrapper.text()).toContain('不是对浏览器代码的证明')
    expect(button('确认审核').attributes('disabled')).toBeDefined()
    await wrapper.find('input[value="PAGE_LOCAL_DRAFT"]').setValue()
    await fillDeclaration()
    api.approvePageActionEffect.mockResolvedValue({})
    await button('确认审核').trigger('click'); await flushPromises()
    expect(api.approvePageActionEffect).toHaveBeenCalledWith('mall', 'profile', 'fill', {
      effect: 'PAGE_LOCAL_DRAFT', expectedCatalogHash: 'hash', expectedRevision: 3,
      implementationVersion: 'profile-v1', fieldLabels: { nickName: '昵称', introduceSign: '个性签名' },
      targetAlias: '填写个人资料', responsibilityAccepted: true,
    })
  })
  it('权限不足和并发冲突给出明确提示，冲突后不允许重发旧审核', async () => {
    api.previewPageActionEffect.mockRejectedValueOnce({ response: { status: 403 } })
    render(); await flushPromises()
    expect(wrapper.text()).toContain('没有此项目的页面动作审核权限')
    await button('重新加载审核信息').trigger('click'); await flushPromises()
    await wrapper.find('input[value="PERSISTENT"]').setValue()
    await fillDeclaration()
    api.approvePageActionEffect.mockRejectedValue({ response: { status: 409 } })
    await button('确认审核').trigger('click'); await flushPromises()
    expect(wrapper.text()).toContain('页面动作或审核状态已变化')
    expect(wrapper.text()).not.toContain('确认审核')
    expect(api.approvePageActionEffect).toHaveBeenCalledTimes(1)
  })
  it('撤销须显式确认并固定当前审核', async () => {
    const approval = { approvalId: 9, revision: 3, effect: 'PERSISTENT', targetAlias: '更新资料', implementationVersion: 'v1', fieldLabels: { nickName: '昵称' }, catalogHash: 'hash', active: true }
    api.previewPageActionEffect.mockResolvedValue({ data: { ...contract, approval } })
    render(); await flushPromises()
    expect(button('撤销审核').attributes('disabled')).toBeDefined()
    await wrapper.findAll('input[type="checkbox"]')[1]!.setValue(true)
    api.revokePageActionEffect.mockResolvedValue({})
    await button('撤销审核').trigger('click'); await flushPromises()
    expect(api.revokePageActionEffect).toHaveBeenCalledWith('mall', 'profile', 'fill', approval)
  })
  it('切换动作后忽略旧预览的迟到响应', async () => {
    let resolveOld!: (value: unknown) => void
    api.previewPageActionEffect.mockImplementationOnce(() => new Promise((resolve) => { resolveOld = resolve }))
    api.previewPageActionEffect.mockResolvedValueOnce({ data: { ...contract, actionKey: 'search', actionTitle: '搜索商品' } })
    render()
    await wrapper.setProps({ actionKey: 'search' }); await flushPromises()
    resolveOld({ data: contract }); await flushPromises()
    expect(wrapper.text()).toContain('搜索商品')
    expect(wrapper.text()).not.toContain('填写资料')
  })
  it('目录版本为空时仍要求管理员填版本，并拒绝规范化后重复的字段名', async () => {
    api.previewPageActionEffect.mockResolvedValue({ data: { ...contract, implementationVersion: null } })
    render(); await flushPromises()
    await wrapper.find('input[value="PAGE_LOCAL_DRAFT"]').setValue()
    await fillDeclaration()
    await wrapper.find('#effect-version').setValue('')
    expect(button('确认审核').attributes('disabled')).toBeDefined()
    await wrapper.find('#effect-version').setValue('v2')
    await wrapper.find('#effect-field-0').setValue('Ｎａｍｅ')
    await wrapper.find('#effect-field-1').setValue('name')
    expect(button('确认审核').attributes('disabled')).toBeDefined()
    await wrapper.find('#effect-field-0').setValue('地址')
    await wrapper.find('#effect-field-1').setValue('收货地址')
    expect(button('确认审核').attributes('disabled')).toBeDefined()
    await wrapper.find('#effect-field-0').setValue('昵称')
    await wrapper.find('#effect-field-1').setValue('个性签名')
    expect(button('确认审核').attributes('disabled')).toBeUndefined()
  })
  it('不允许将空字段或嵌套契约审核为页面草稿', async () => {
    api.previewPageActionEffect.mockResolvedValueOnce({ data: { ...contract, inputSchema: { type: 'object', properties: {} } } })
    render(); await flushPromises()
    expect(wrapper.text()).toContain('不能审核为页面草稿')
    expect(wrapper.find('input[value="PAGE_LOCAL_DRAFT"]').attributes('disabled')).toBeDefined()
    api.previewPageActionEffect.mockResolvedValueOnce({ data: { ...contract, inputSchema: { type: 'object', properties: { address: { type: 'object' } } } } })
    await wrapper.setProps({ actionKey: 'other' }); await flushPromises()
    expect(wrapper.text()).toContain('输入契约不受支持')
    expect(button('确认审核').attributes('disabled')).toBeDefined()
  })
})
