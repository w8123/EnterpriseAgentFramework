import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { describe, expect, it } from 'vitest'
import WorkflowStudioEnvDialog from './WorkflowStudioEnvDialog.vue'

describe('Workflow environment editor', () => {
  it('does not let ordinary environment inputs impersonate the reviewed draft marker', async () => {
    const wrapper = mount(WorkflowStudioEnvDialog, {
      props: { modelValue: true, env: {} },
      global: { plugins: [ElementPlus], stubs: { AppDialog: { template: '<div><slot/><slot name="footer"/></div>' } } },
    })
    await wrapper.findAll('button').find((button) => button.text() === '添加')!.trigger('click')
    const inputs = wrapper.findAll('input')
    await inputs[0].setValue('reachaiTrustedPageDraft')
    await inputs[1].setValue('true')
    await wrapper.findAll('button').find((button) => button.text() === '确定')!.trigger('click')
    expect(wrapper.text()).toContain('请勿在环境变量中手动填写')
    expect(wrapper.emitted('apply')).toBeUndefined()
  })
})
