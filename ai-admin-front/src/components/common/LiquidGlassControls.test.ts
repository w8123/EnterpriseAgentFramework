import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { defineComponent, nextTick, ref } from 'vue'
import ElementPlus, { ElInput } from 'element-plus'
import { LiquidGlassButton, LiquidGlassInput, LiquidGlassSelect, LiquidGlassPagination, LiquidGlassTable, LiquidGlassFilterBar, LiquidGlassViewToggle, LiquidGlassDropdown, LiquidGlassTabs, LiquidGlassEmptyState } from './liquid-glass'
import LiquidGlassDropdownSurface from './LiquidGlassDropdownSurface.vue'
import AppDialog from './AppDialog.vue'
import AppDrawer from './AppDrawer.vue'

let wrapper: VueWrapper | undefined
const global = { plugins: [ElementPlus], stubs: { LiquidGlassLayer: true, LiquidGlassDropdownSurface: true } }

beforeEach(() => {
  vi.stubGlobal('ResizeObserver', class { observe() {} unobserve() {} disconnect() {} })
  vi.stubGlobal('requestAnimationFrame', (callback: FrameRequestCallback) => setTimeout(() => callback(0), 0))
  vi.stubGlobal('cancelAnimationFrame', clearTimeout)
})
afterEach(() => {
  wrapper?.unmount()
  wrapper = undefined
  document.body.innerHTML = ''
  vi.unstubAllGlobals()
})

describe('public overlay mounting', () => {
  it.each([
    ['dialog', AppDialog, '.app-dialog'],
    ['drawer', AppDrawer, '.app-drawer'],
  ] as const)('mounts a modal %s outside the isolated workspace so the sidebar cannot cover it', async (_name, component, selector) => {
    const workspace = document.createElement('main')
    workspace.style.isolation = 'isolate'
    workspace.style.zIndex = '1'
    document.body.append(workspace)
    wrapper = mount(component, { attachTo: workspace, global, props: { modelValue: true, title: '详情' } })
    await flushPromises()
    const overlay = document.querySelector(selector)?.closest('.el-overlay')
    expect(overlay).not.toBeNull()
    expect(document.body.contains(overlay!)).toBe(true)
    expect(workspace.contains(overlay!)).toBe(false)
  })

  it.each([
    ['dialog', AppDialog, '.app-dialog'],
    ['drawer', AppDrawer, '.app-drawer'],
  ] as const)('preserves an explicit local mount for a %s', async (_name, component, selector) => {
    const workspace = document.createElement('main')
    document.body.append(workspace)
    wrapper = mount(component, { attachTo: workspace, global, props: { modelValue: true, title: '详情' }, attrs: { 'append-to-body': false } })
    await flushPromises()
    expect(workspace.contains(document.querySelector(selector))).toBe(true)
  })

  it('keeps a nonmodal Studio drawer inside its workspace', async () => {
    const workspace = document.createElement('main')
    document.body.append(workspace)
    wrapper = mount(AppDrawer, { attachTo: workspace, global, props: { modelValue: true, title: '调试台' }, attrs: { modal: false, 'lock-scroll': false } })
    await flushPromises()
    expect(workspace.contains(document.querySelector('.app-drawer'))).toBe(true)
  })
})

describe('public liquid glass controls', () => {
  it('anchors below the expansion icon and changes to a right-facing droplet when the menu moves left', async () => {
    const trigger = document.createElement('div')
    const icon = document.createElement('span')
    icon.className = 'expansion-icon'
    trigger.append(icon)
    document.body.append(trigger)
    let triggerBounds = new DOMRect(1400, 300, 420, 40)
    let iconBounds = new DOMRect(1782, 311, 20, 18)
    let menuBounds = new DOMRect(1400, 350, 420, 160)
    trigger.getBoundingClientRect = () => triggerBounds
    icon.getBoundingClientRect = () => iconBounds
    const popper = document.createElement('div')
    popper.className = 'el-popper liquid-select-popper'
    popper.setAttribute('data-popper-placement', 'bottom-start')
    popper.getBoundingClientRect = () => menuBounds
    Object.defineProperties(popper, {
      offsetWidth: { get: () => menuBounds.width },
      offsetHeight: { get: () => menuBounds.height },
    })
    document.body.append(popper)
    wrapper = mount(LiquidGlassDropdownSurface, {
      attachTo: popper,
      props: { reference: trigger, anchorSelector: '.expansion-icon' },
      global: { stubs: { LiquidGlassLayer: true } },
    })
    await flushPromises()
    expect(popper.dataset.liquidDropCorner).toBe('top-right')
    expect(popper.dataset.liquidDropSide).toBe('top')
    expect(wrapper.getComponent({ name: 'LiquidGlassLayer' }).props('dropOffset')).toBe(392)

    triggerBounds = new DOMRect(1830, 960, 172, 48)
    iconBounds = new DOMRect(1960, 975, 20, 18)
    menuBounds = new DOMRect(1580, 804, 240, 216)
    popper.setAttribute('data-popper-placement', 'left')
    await vi.waitFor(() => expect(popper.dataset.liquidDropSide).toBe('right'))
    expect(popper.dataset.liquidDropCorner).toBe('bottom-right')
    expect(wrapper.getComponent({ name: 'LiquidGlassLayer' }).props('dropOffset')).toBe(180)
  })

  it('keeps collection paging and source inheritance when the outer reading panel is removed', async () => {
    wrapper = mount(LiquidGlassTable, { global, attachTo: document.body, props: { presentation: 'collection', bodyLayout: 'cards', total: 22, pageSize: 9 },
      slots: { toolbar: '<span>筛选</span>', default: '<div>独立阅读卡片</div>' } })
    expect(wrapper.classes()).not.toContain('glass-surface-panel')
    expect(wrapper.classes()).not.toContain('glass-surface-frost')
    expect(wrapper.get('.liquid-glass-table__total').text()).toBe('共 22 条')
    await wrapper.get('.btn-next').trigger('click')
    await flushPromises()
    expect(wrapper.emitted('change')).toEqual([[2, 9]])
    await wrapper.setProps({ presentation: 'panel' })
    expect(wrapper.classes()).toContain('glass-surface-panel')
    expect(wrapper.classes()).toContain('glass-surface-frost')
  })

  it('preserves the pending reading body, announces loading and clears the mask after completion', async () => {
    wrapper = mount(LiquidGlassTable, { global, attachTo: document.body,
      props: { loading: true, total: 0 }, slots: { default: '<div class="retained-row">已有列表内容</div>' } })
    await flushPromises()
    expect(wrapper.attributes('aria-busy')).toBe('true')
    expect(wrapper.get('.retained-row').text()).toBe('已有列表内容')
    expect(wrapper.get('.el-loading-mask').text()).toContain('正在加载')
    await wrapper.setProps({ loading: false })
    await vi.waitFor(() => expect(wrapper!.find('.el-loading-mask').exists()).toBe(false))
    expect(wrapper.attributes('aria-busy')).toBe('false')
  })

  it('keeps the current native tab panel when navigation is rejected', async () => {
    const active = ref('java')
    const allowed = ref(false)
    wrapper = mount(defineComponent({ components: { LiquidGlassTabs }, setup: () => ({ active, guard: () => allowed.value }),
      template: '<LiquidGlassTabs v-model="active" :before-leave="guard"><el-tab-pane name="java" label="Java">方法列表</el-tab-pane><el-tab-pane name="http" label="HTTP">API 列表</el-tab-pane></LiquidGlassTabs>',
    }), { global })
    await wrapper.get('#tab-http').trigger('click')
    await flushPromises()
    expect(active.value).toBe('java')
    expect(wrapper.get('#tab-java').attributes('aria-selected')).toBe('true')
    allowed.value = true
    await wrapper.get('#tab-http').trigger('click')
    await flushPromises()
    expect(active.value).toBe('http')
    expect(wrapper.get('#tab-http').attributes('aria-selected')).toBe('true')
  })

  it('forwards a menu command once and keeps disabled commands inactive', async () => {
    const command = vi.fn()
    wrapper = mount(LiquidGlassDropdown, { global, props: { onCommand: command }, attachTo: document.body,
      slots: { default: '<button type="button">整理</button>', dropdown: '<el-dropdown-menu><el-dropdown-item command="blocked" disabled>不可用</el-dropdown-item><el-dropdown-item command="graph">关联分析</el-dropdown-item></el-dropdown-menu>' } })
    await wrapper.getComponent({ name: 'ElDropdown' }).vm.handleOpen()
    await flushPromises()
    const items = [...document.body.querySelectorAll<HTMLElement>('[role="menuitem"]')]
    expect(items).toHaveLength(2)
    expect(items[0]!.getAttribute('aria-disabled')).toBe('true')
    items[0]!.click()
    await flushPromises()
    expect(command).not.toHaveBeenCalled()
    items[1]!.click()
    await flushPromises()
    expect(command).toHaveBeenCalledOnce()
    expect(command.mock.calls[0]![0]).toBe('graph')
  })

  it('preserves empty-state actions without making them submit a surrounding form', async () => {
    const retry = vi.fn()
    wrapper = mount(defineComponent({ components: { LiquidGlassEmptyState, LiquidGlassButton }, setup: () => ({ retry }),
      template: '<form><LiquidGlassEmptyState title="列表加载失败" description="重新读取列表。"><LiquidGlassButton @click="retry">重试</LiquidGlassButton></LiquidGlassEmptyState></form>',
    }), { global })
    const button = wrapper.get('button')
    expect(button.attributes('type')).toBe('button')
    await button.trigger('click')
    expect(retry).toHaveBeenCalledOnce()
  })

  it('submits a shared filter form once and keeps reset separate from submit', async () => {
    const query = vi.fn()
    const reset = vi.fn()
    wrapper = mount(LiquidGlassFilterBar, { global, props: { onQuery: query, onReset: reset }, attachTo: document.body })
    await wrapper.get('form').trigger('submit')
    expect(query).toHaveBeenCalledOnce()
    await wrapper.get('button[aria-label="重置筛选"]').trigger('click')
    expect(reset).toHaveBeenCalledOnce()
    expect(query).toHaveBeenCalledOnce()
    expect(wrapper.get('button[type="submit"]').text()).toContain('搜索')
  })

  it('aligns input and select controls in the unified liquid glass filter bar', async () => {
    const keyword = ref('')
    const status = ref('')
    wrapper = mount(defineComponent({
      components: { LiquidGlassFilterBar, LiquidGlassInput, LiquidGlassSelect },
      setup: () => ({ keyword, status }),
      template: '<LiquidGlassFilterBar><LiquidGlassInput v-model="keyword" placeholder="搜索" /><LiquidGlassSelect v-model="status" placeholder="状态"><el-option label="启用" :value="true" /></LiquidGlassSelect></LiquidGlassFilterBar>',
    }), { global, attachTo: document.body })
    const inputWrapper = wrapper.find('.liquid-glass-input')
    const selectWrapper = wrapper.find('.liquid-glass-select')
    expect(inputWrapper.exists()).toBe(true)
    expect(selectWrapper.exists()).toBe(true)
    expect(inputWrapper.classes()).toContain('liquid-glass-control--field')
    expect(selectWrapper.classes()).toContain('liquid-glass-control--field')
  })

  it('switches a list view through accessible native buttons', async () => {
    const mode = ref<'table' | 'card'>('table')
    wrapper = mount(defineComponent({
      components: { LiquidGlassViewToggle }, setup: () => ({ mode }),
      template: '<LiquidGlassViewToggle v-model="mode" />',
    }), { global, attachTo: document.body })
    expect(wrapper.get('[aria-label="表格视图"]').attributes('aria-pressed')).toBe('true')
    await wrapper.get('[aria-label="卡片视图"]').trigger('click')
    expect(mode.value).toBe('card')
    expect(wrapper.get('[aria-label="卡片视图"]').attributes('aria-pressed')).toBe('true')
    expect(wrapper.get('[aria-label="表格视图"]').attributes('aria-pressed')).toBe('false')
  })

  it('can show a successful zero count while hiding the footer before data has loaded', async () => {
    wrapper = mount(LiquidGlassTable, { global, props: { total: 0, showEmptyPagination: true, showPagination: false } })
    expect(wrapper.find('.data-table-shell__pagination').exists()).toBe(false)
    await wrapper.setProps({ showPagination: true })
    expect(wrapper.get('.liquid-glass-table__total').text()).toBe('共 0 条')
    expect((wrapper.get('.btn-next').element as HTMLButtonElement).disabled).toBe(true)
  })

  it('preserves input v-model, focus, clear and named slots through the public entry', async () => {
    const value = ref('')
    const inputControl = ref<InstanceType<typeof LiquidGlassInput>>()
    const cleared = vi.fn()
    wrapper = mount(defineComponent({
      components: { LiquidGlassInput },
      setup: () => ({ value, cleared, inputControl }),
      template: '<LiquidGlassInput ref="inputControl" v-model="value" aria-label="搜索" clearable @clear="cleared"><template #prefix><span class="custom-prefix">找</span></template></LiquidGlassInput>',
    }), { global, attachTo: document.body })
    const input = wrapper.get('input')
    await input.setValue('ReachAI')
    expect(value.value).toBe('ReachAI')
    expect(input.attributes('aria-label')).toBe('搜索')
    expect(wrapper.find('.custom-prefix').text()).toBe('找')
    inputControl.value!.focus()
    await nextTick()
    expect(document.activeElement).toBe(input.element)
    inputControl.value!.clear()
    await flushPromises()
    expect(value.value).toBe('')
    expect(cleared).toHaveBeenCalledOnce()
    expect(wrapper.findComponent(ElInput).exists()).toBe(true)
  })

  it('keeps native button semantics and suppresses clicks while disabled', async () => {
    const disabled = ref(false)
    const clicked = vi.fn()
    wrapper = mount(defineComponent({
      components: { LiquidGlassButton },
      setup: () => ({ disabled, clicked }),
      template: '<LiquidGlassButton circle native-type="submit" aria-label="提交" :disabled="disabled" @click="clicked"><template #icon><span class="custom-icon">+</span></template></LiquidGlassButton>',
    }), { global, attachTo: document.body })
    const button = wrapper.get('button')
    expect(button.attributes('type')).toBe('submit')
    expect(wrapper.find('.custom-icon').exists()).toBe(true)
    expect(wrapper.find('.liquid-glass-control--circle').exists()).toBe(true)
    await button.trigger('click')
    expect(clicked).toHaveBeenCalledOnce()
    disabled.value = true
    await nextTick()
    expect((button.element as HTMLButtonElement).disabled).toBe(true)
    await button.trigger('click')
    expect(clicked).toHaveBeenCalledOnce()
  })

  it('preserves a real option selection and the selected-label slot', async () => {
    const value = ref('')
    const changed = vi.fn()
    wrapper = mount(defineComponent({
      components: { LiquidGlassSelect },
      setup: () => ({ value, changed }),
      template: '<LiquidGlassSelect v-model="value" placeholder="状态" @change="changed"><template #label="{ label }"><span class="custom-label">{{ label }} / 已选</span></template><el-option label="已发布" value="published" /></LiquidGlassSelect>',
    }), { global, attachTo: document.body })
    await wrapper.get('.el-select__wrapper').trigger('click')
    await flushPromises()
    const option = document.body.querySelector<HTMLElement>('[role="option"]')!
    expect(option).not.toBeNull()
    option.click()
    await flushPromises()
    expect(value.value).toBe('published')
    expect(changed).toHaveBeenLastCalledWith('published')
    expect(wrapper.find('.custom-label').text()).toBe('已发布 / 已选')
  })

  it('emits one settled server-page request when the user changes page size', async () => {
    wrapper = mount(LiquidGlassPagination, {
      global,
      attachTo: document.body,
      props: { total: 95, currentPage: 3, pageSize: 10, pageSizes: [10, 20, 50] },
    })
    await wrapper.get('.btn-next').trigger('click')
    await flushPromises()
    expect(wrapper.emitted('change')).toEqual([[4, 10]])
    await wrapper.get('.el-select__wrapper').trigger('click')
    await flushPromises()
    const option = [...document.body.querySelectorAll<HTMLElement>('[role="option"]')].find(option => option.textContent?.includes('20条/页'))!
    option.click()
    await flushPromises()
    expect(wrapper.emitted('change')).toEqual([[4, 10], [1, 20]])
    expect(wrapper.emitted('sizeChange')).toEqual([[20]])
    await wrapper.setProps({ currentPage: 2, pageSize: 20 })
    await flushPromises()
    expect(wrapper.emitted('change')).toHaveLength(2)
  })

  it('inherits the table source without leaking it to sibling or explicitly overridden fields', async () => {
    wrapper = mount(defineComponent({
      components: { LiquidGlassTable, LiquidGlassInput },
      template: '<div><LiquidGlassTable :total="0"><template #toolbar><LiquidGlassInput class="inherited" /><LiquidGlassInput class="overridden" source="backdrop" /></template><div>业务数据</div></LiquidGlassTable><LiquidGlassInput class="outside" /></div>',
    }), { global, attachTo: document.body })
    expect(wrapper.get('.inherited').classes()).toContain('samples-wallpaper')
    expect(wrapper.get('.overridden').classes()).not.toContain('samples-wallpaper')
    expect(wrapper.get('.outside').classes()).not.toContain('samples-wallpaper')
    expect(wrapper.find('.data-table-shell__pagination').exists()).toBe(false)
    expect(wrapper.text()).toContain('业务数据')
  })
})
