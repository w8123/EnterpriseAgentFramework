import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus, { ElButton } from 'element-plus'
import { afterEach, describe, expect, it } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'
import SourceSemanticTasksDrawer from './SourceSemanticTasksDrawer.vue'

const wrappers: ReturnType<typeof mount>[] = []
afterEach(() => { wrappers.splice(0).forEach(view => view.unmount()); document.body.innerHTML = '' })
function setup() {
  const router = createRouter({ history: createMemoryHistory(), routes: [
    { name: 'ModelInstances', path: '/models/instances', component: { template: '<div />' } },
    { path: '/', component: { template: '<div />' } },
  ] })
  const view = mount(SourceSemanticTasksDrawer, { attachTo: document.body, props: {
    visible: true, semanticModelInstanceId: 'llm', aiGenerationMode: 'missing',
    semanticModelInstances: [], semanticModelInstancesLoading: false, semanticModelInstancesLoadError: false,
    batchStarting: false, taskRunning: false, taskPercent: 0, taskFailed: false, taskFailedTitle: '', taskStatusError: '',
  }, global: { plugins: [router, ElementPlus] } })
  wrappers.push(view); return view
}
function button(view: ReturnType<typeof mount>, label: string) {
  const target = view.findAllComponents(ElButton).find(item => item.text().trim() === label)
  expect(target, `action ${label}`).toBeDefined(); return target!
}

describe('semantic generation drawer permission', () => {
  it('retains task recovery and local settings while suppressing read-only generation', async () => {
    const view = setup(); await flushPromises()
    expect(button(view, '一键生成').props('disabled')).toBe(false)
    await view.setProps({ canWrite: false, taskStatusError: '状态读取失败' }); await flushPromises()
    expect(document.body.textContent).toContain('当前账号仅可阅读业务说明')
    await button(view, '重试任务状态').trigger('click')
    expect(view.emitted('retryTaskStatus')).toEqual([[]])
    await view.setProps({ taskStatusError: '', taskRunning: true, taskPercent: 42 }); await flushPromises()
    expect(document.body.textContent).toContain('42%')
    await view.setProps({ taskRunning: false }); await flushPromises()
    expect(button(view, '一键生成').props('disabled')).toBe(true)
    button(view, '一键生成').vm.$emit('click', new MouseEvent('click')); await flushPromises()
    expect(view.emitted('startBatchGenerate')).toBeUndefined()
    await button(view, '保存设置').trigger('click')
    expect(view.emitted('saveAiGenerationSettings')).toEqual([[]])
    await view.setProps({ canWrite: true }); await flushPromises()
    expect(button(view, '一键生成').props('disabled')).toBe(false)
    await button(view, '一键生成').trigger('click')
    expect(view.emitted('startBatchGenerate')).toEqual([[false]])
  })
})
