import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus, { ElButton, ElDrawer, ElDropdown, ElSwitch } from 'element-plus'
import { afterEach, describe, expect, it } from 'vitest'
import { defineComponent, ref } from 'vue'
import AppDrawer from '@/components/common/AppDrawer.vue'
import CapabilityGraphToolbar from './CapabilityGraphToolbar.vue'

const wrappers: ReturnType<typeof mount>[] = []
afterEach(() => {
  wrappers.splice(0).forEach(view => view.unmount())
  document.body.innerHTML = ''
})
function setup(props: Record<string, unknown> = {}) {
  const view = mount(CapabilityGraphToolbar, {
    attachTo: document.body,
    props,
    slots: { navigation: '<button type="button">返回能力目录</button>' },
    global: { plugins: [ElementPlus] },
  })
  wrappers.push(view)
  return view
}
function button(view: ReturnType<typeof mount>, label: string) {
  const target = view.findAllComponents(ElButton).find(item => item.text().trim() === label)
  expect(target, `action ${label}`).toBeDefined()
  return target!
}
function switchControl(view: ReturnType<typeof mount>, label: string) {
  const target = view.findAllComponents(ElSwitch).find(item =>
    item.props('activeText') === label || item.props('ariaLabel') === label
      || item.attributes('aria-label') === label || item.text().trim() === label)
  expect(target, `view option ${label}`).toBeDefined()
  return target!
}
async function dropdownItem(view: ReturnType<typeof mount>, label: string) {
  await button(view, '分析与更新').trigger('click')
  await flushPromises()
  const item = Array.from(document.querySelectorAll<HTMLElement>('.el-dropdown-menu__item'))
    .find(item => item.textContent?.trim() === label)
  expect(item, `analysis command ${label}`).toBeDefined()
  return item!
}

describe('association analysis toolbar preserves controls without writing graph data', () => {
  it('keeps navigation and the dependency boundary visible for read-only users', () => {
    const view = setup()
    expect(view.text()).toContain('关联分析')
    expect(view.text()).toContain('契约关联，非实际调用依赖')
    expect(view.text()).toContain('返回能力目录')
    expect(view.findComponent(ElDropdown).exists()).toBe(false)
    expect(view.findAllComponents(ElSwitch)).toHaveLength(2)
    expect(switchControl(view, '候选边').props('modelValue')).toBe(false)
    expect(switchControl(view, '数据模型').props('modelValue')).toBe(false)
    expect(view.emitted()).toEqual({})
  })

  it('dispatches refresh and retains the current capability focus action', async () => {
    const view = setup({ focusedLabel: '查询字典子项' })
    expect(view.text()).toContain('查询字典子项')
    await button(view, '刷新').trigger('click')
    expect(view.emitted('refresh')).toEqual([[]])
    await button(view, '当前能力关联').trigger('click')
    expect(view.emitted('focus-subject')).toEqual([[]])
    await view.setProps({ focusedLabel: undefined })
    expect(view.text()).not.toContain('查询字典子项')
    expect(view.findAllComponents(ElButton).some(item => item.text().trim() === '当前能力关联')).toBe(false)
  })

  it('retains all four existing analysis commands in the real dropdown', async () => {
    const view = setup({ canWrite: true })
    for (const [label, command] of [
      ['推断共享数据结构', 'inferModels'],
      ['推断请求与响应关联', 'inferRequestResponse'],
      ['按当前来源更新关系', 'rebuild'],
      ['清空并重新生成', 'regenerate'],
    ]) {
      const item = await dropdownItem(view, label)
      item.dispatchEvent(new MouseEvent('click', { bubbles: true }))
      await flushPromises()
      const commands = view.emitted('analysis-command') || []
      expect(commands[commands.length - 1]).toEqual([command])
    }
    expect(view.emitted('analysis-command')).toHaveLength(4)
  })

  it('prevents duplicate refresh and analysis actions while requests are pending', async () => {
    const view = setup({ canWrite: true, loading: true })
    expect(button(view, '刷新').props('loading')).toBe(true)
    expect(button(view, '分析与更新').props('disabled')).toBe(true)
    await button(view, '刷新').trigger('click')
    await button(view, '分析与更新').trigger('click')
    expect(view.emitted('refresh')).toBeUndefined()
    expect(view.emitted('analysis-command')).toBeUndefined()
    await view.setProps({ loading: false })
    const item = await dropdownItem(view, '推断共享数据结构')
    await view.setProps({ analysisBusy: true })
    expect(button(view, '分析与更新').props('loading')).toBe(true)
    expect(item.getAttribute('aria-disabled')).toBe('true')
    item.dispatchEvent(new MouseEvent('click', { bubbles: true }))
    await flushPromises()
    expect(view.emitted('analysis-command')).toBeUndefined()
  })

  it('emits each display toggle without dispatching an analysis command', async () => {
    const view = setup({ canWrite: true })
    for (const [label, event] of [
      ['连线模式', 'update:linkMode'],
      ['候选边', 'update:showCandidates'],
      ['数据模型', 'update:showDtoPanel'],
    ]) {
      await switchControl(view, label).trigger('click')
      expect(view.emitted(event)).toEqual([[true]])
    }
    expect(view.emitted('analysis-command')).toBeUndefined()
    await view.setProps({ canWrite: false })
    expect(view.findComponent(ElDropdown).exists()).toBe(false)
    expect(view.findAllComponents(ElSwitch)).toHaveLength(2)
    expect(view.text()).not.toContain('连线模式')
  })

  it('keeps native checkbox and accessible switch state in sync when clicking the option text', async () => {
    const host = mount(defineComponent({
      components: { CapabilityGraphToolbar },
      setup() {
        return { candidates: ref(false), candidateUpdates: ref<boolean[]>([]) }
      },
      template: `<CapabilityGraphToolbar v-model:show-candidates="candidates"
        @update:show-candidates="candidateUpdates.push($event)" />`,
    }), { attachTo: document.body, global: { plugins: [ElementPlus] } })
    wrappers.push(host)
    const toolbar = host.findComponent(CapabilityGraphToolbar)
    const optionText = toolbar.findAll('span').find(item => item.text().trim() === '候选边'
      && item.element.children.length === 0)!
    expect(optionText).toBeDefined()
    ;(optionText.element as HTMLElement).click()
    await flushPromises()
    const control = switchControl(toolbar, '候选边')
    expect(host.vm.candidates).toBe(true)
    expect(host.vm.candidateUpdates).toEqual([true])
    const accessibleSwitch = control.get('input[type="checkbox"][role="switch"]')
    expect(accessibleSwitch.attributes('aria-checked')).toBe('true')
    expect((accessibleSwitch.element as HTMLInputElement).checked).toBe(true)
  })

  it('keeps both live connection instructions visible outside the help drawer', async () => {
    const view = setup({ canWrite: true, linkMode: true })
    expect(view.text()).toContain('请点击一个接口或参数作为源节点')
    expect(view.findComponent(AppDrawer).props('modelValue')).toBe(false)
    await view.setProps({ linkSourceLabel: '查询字典子项' })
    expect(view.text()).toContain('已选源节点：查询字典子项')
    expect(view.text()).toContain('点击目标节点完成连线')
    expect(view.emitted('analysis-command')).toBeUndefined()
  })

  it('opens and closes shared help locally without emitting business write events', async () => {
    const view = setup({ canWrite: true })
    const drawer = view.findComponent(AppDrawer)
    expect(drawer.props('title')).toBe('图谱操作帮助')
    expect(drawer.props('modelValue')).toBe(false)
    await button(view, '操作帮助').trigger('click')
    expect(drawer.props('modelValue')).toBe(true)
    drawer.vm.$emit('update:modelValue', false)
    await flushPromises()
    expect(drawer.props('modelValue')).toBe(false)
    for (const event of ['refresh', 'focus-subject', 'analysis-command', 'update:linkMode',
      'update:showCandidates', 'update:showDtoPanel']) expect(view.emitted(event)).toBeUndefined()
  })

  it('teleports help outside the glass workbench to avoid clipping its fixed overlay', async () => {
    const view = setup()
    await button(view, '操作帮助').trigger('click')
    expect(view.findComponent(AppDrawer).props('modelValue')).toBe(true)
    expect(view.findComponent(AppDrawer).findComponent(ElDrawer).props('appendToBody')).toBe(true)
  })
})
