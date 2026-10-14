import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus, { ElButton } from 'element-plus'
import { afterEach, describe, expect, it } from 'vitest'
import SourceImportDialog from './SourceImportDialog.vue'
import { getDefaultScanSettings } from '@/types/scanProject'

const views: ReturnType<typeof mount>[] = []
afterEach(() => { views.splice(0).forEach(view => view.unmount()); document.body.innerHTML = '' })
function setup() {
  const view = mount(SourceImportDialog, { attachTo: document.body, props: { visible: true, project: null,
    syncLoading: false, syncResult: null, syncError: null, rescanLoading: false, scanSettingsForm: getDefaultScanSettings(),
    isOpenApiMode: false, descriptionSourceLabels: {}, paramSourceLabels: {}, allHttpMethods: ['GET'], scanSettingsSaving: false,
  } as never, global: { plugins: [ElementPlus] } })
  views.push(view); return view
}
const button = (view: ReturnType<typeof mount>, label: string) => view.findAllComponents(ElButton).find(item => item.text() === label)!
describe('implemented source import actions', () => {
  it('offers SDK guidance without an unimplemented import tab', async () => {
    const view = setup(); await flushPromises()
    expect(document.body.textContent).not.toContain('AI Coding')
    expect(document.querySelector('[role="tablist"]')).toBeNull()
    await button(view, 'SDK 接入指引').trigger('click')
    expect(view.emitted('sdkGuide')).toEqual([[]])
  })
  it('keeps settings readable but prevents read-only synchronization and saves', async () => {
    const view = setup(); await view.setProps({ canWrite: false }); await flushPromises()
    expect(button(view, '扫描并同步接口').props('disabled')).toBe(true)
    button(view, '扫描并同步接口').vm.$emit('click', new MouseEvent('click')); await flushPromises()
    expect(view.emitted('scanSdk')).toBeUndefined()
    await view.setProps({ settingsVisible: true }); await flushPromises()
    expect(button(view, '保存扫描设置').props('disabled')).toBe(true)
    button(view, '保存扫描设置').vm.$emit('click', new MouseEvent('click')); await flushPromises()
    expect(view.emitted('saveScanSettings')).toBeUndefined()
  })
})
