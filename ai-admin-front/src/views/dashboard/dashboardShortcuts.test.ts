import { afterEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import {
  availableDashboardShortcuts, dashboardShortcuts, defaultShortcutConfig,
  resolveShortcutIds, sanitizeShortcutIds, shortcutPresetConfig, SHORTCUT_WIDGET_KEY,
} from './dashboardShortcuts'
import { defaultConsoleLayoutFor, sanitizeLayoutItems } from './widgetRegistry'
import { createDashboardLayoutLocalRepository } from './dashboardLayoutLocalRepository'
import { useDashboardLayoutEditor } from './useDashboardLayoutEditor'
import WidgetShortcuts from './components/widgets/WidgetShortcuts.vue'
import DashboardShortcutEditor from './components/DashboardShortcutEditor.vue'
import DashboardWidgetCatalogDrawer from './components/DashboardWidgetCatalogDrawer.vue'

afterEach(() => { vi.restoreAllMocks(); localStorage.clear() })

describe('快捷入口授权与预设', () => {
  it('入口复用已注册导航，不接受自定义 URL、重复项或未知 ID', () => {
    expect(dashboardShortcuts.every(item => item.icon && item.to.startsWith('/'))).toBe(true)
    expect(sanitizeShortcutIds(['agents', 'https://example.com', 'agents', 'projects', '__proto__'])).toEqual(['agents', 'projects'])
    const [item] = sanitizeLayoutItems([{ instanceId: 'nav', widgetKey: SHORTCUT_WIDGET_KEY,
      rect: { x: 0, y: 0, w: 12, h: 2 }, config: { shortcutIds: ['projects', 'unknown'], shortcutPreset: 'custom' } }])
    expect(item.config.shortcutIds).toEqual(['projects'])
  })

  it('只读权限也可进入对应列表，角色预设不会赋予访问权限', () => {
    const available = availableDashboardShortcuts(['workflow:read', 'runops:read'])
    expect(available.map(item => item.id)).toEqual(['workflows', 'run-center'])
    expect(shortcutPresetConfig('admin', ['workflow:read']).shortcutIds).toEqual([])
    expect(availableDashboardShortcuts([])).toEqual([])
  })

  it('开发人员即使有运行读取权限也默认开发预设；多角色只初始化一个组件', () => {
    const permissions = ['platform:read', 'workflow:read', 'runops:read']
    expect(defaultShortcutConfig(permissions, ['DEVELOPER'])?.shortcutPreset).toBe('developer')
    const layout = defaultConsoleLayoutFor(['*'], ['PLATFORM_ADMIN', 'DEVELOPER'])
    expect(layout.items.filter(item => item.widgetKey === SHORTCUT_WIDGET_KEY)).toHaveLength(1)
    expect(layout.items[0].config.shortcutPreset).toBe('admin')
  })

  it('保留用户指定顺序、空选择和暂时失权的配置，展示时按实时授权过滤', async () => {
    const config = { shortcutPreset: 'custom' as const, shortcutIds: ['agents', 'projects'] }
    const wrapper = mount(WidgetShortcuts, { props: { config, permissions: ['platform:read'] } })
    expect(wrapper.findAll('a').map(link => link.attributes('href'))).toEqual(['/registry/projects'])
    await wrapper.setProps({ permissions: ['*'] })
    expect(wrapper.findAll('a').map(link => link.attributes('href'))).toEqual(['/agent', '/registry/projects'])
    await wrapper.find('a').trigger('click')
    expect(wrapper.emitted('open')?.[0]).toEqual(['/agent'])
    expect(resolveShortcutIds({ shortcutPreset: 'admin', shortcutIds: [] })).toEqual([])
    wrapper.unmount()
  })
})

describe('个人快捷入口定制', () => {
  it('旧双行入口升级后下方卡片上移，入口顺序、名称和 revision 保留；加载不写入存储', () => {
    const repository = createDashboardLayoutLocalRepository(() => 11)
    const legacy = defaultConsoleLayoutFor(['*'])
    legacy.schemaVersion = 1
    legacy.revision = 8
    legacy.items = legacy.items.map(item => ({ ...item, rect: item.widgetKey === SHORTCUT_WIDGET_KEY
      ? { ...item.rect, h: 2 } : { ...item.rect, y: item.rect.y + 1 } }))
    legacy.items[0].config = { title: '我的入口', shortcutPreset: 'custom', shortcutIds: ['agents', 'projects'] }
    const key = 'reachai.dashboard.consoleLayout.local.v1.user.11'
    localStorage.setItem(key, JSON.stringify(legacy))
    const upgraded = repository.load()!
    expect(upgraded.schemaVersion).toBe(2)
    expect(upgraded.revision).toBe(8)
    expect(upgraded.items[0]).toEqual({ ...legacy.items[0], rect: { x: 0, y: 0, w: 12, h: 1 } })
    expect(upgraded.items.slice(1)).toEqual(legacy.items.slice(1).map(item => ({ ...item, rect: { ...item.rect, y: item.rect.y - 1 } })))
    expect(JSON.parse(localStorage.getItem(key)!)).toEqual(legacy)
    const saved = repository.save(upgraded)
    expect(repository.load()).toEqual(saved)
    // 新版用户主动调高后，刷新不能再次强行压缩。
    saved.items[0].rect.h = 2
    repository.save(saved)
    expect(repository.load()!.items[0].rect.h).toBe(2)
  })

  it('旧版窄面板、主动加高的入口和被其他卡片占用的行保留原占位', () => {
    const repository = createDashboardLayoutLocalRepository(() => 11)
    for (const rect of [
      { x: 0, y: 0, w: 6, h: 2 },
      { x: 0, y: 0, w: 12, h: 3 },
      { x: 0, y: 0, w: 12, h: 2 },
    ]) {
      const legacy = defaultConsoleLayoutFor(['*'])
      legacy.schemaVersion = 1
      legacy.items[0].rect = rect
      localStorage.setItem('reachai.dashboard.consoleLayout.local.v1.user.11', JSON.stringify(legacy))
      const loaded = repository.load()!
      expect(loaded.items).toEqual(legacy.items)
    }
  })

  it('目录分别统计各预设，已有运营组件不会显示成已有自定义组件', () => {
    const layout = defaultConsoleLayoutFor(['*'], ['OPERATOR'])
    const wrapper = mount(DashboardWidgetCatalogDrawer, { props: { open: true, items: layout.items, permissions: ['*'] }, global: { stubs: { teleport: true } } })
    expect(wrapper.find('[data-catalog-preset="operator"]').text()).toContain('已添加 1')
    expect(wrapper.find('[data-catalog-preset="custom"]').text()).toContain('未添加')
    expect(wrapper.find('[data-catalog-preset="developer"]').text()).toContain('未添加')
    wrapper.unmount()
  })
  it('套用预设、排序、移除和添加都只修改对话框草稿', async () => {
    let config = shortcutPresetConfig('developer')
    const wrapper = mount(DashboardShortcutEditor, { props: { modelValue: config, permissions: ['*'],
      'onUpdate:modelValue': (next) => { config = next; void wrapper.setProps({ modelValue: next }) } } })
    await wrapper.find('[data-shortcut-preset="operator"]').trigger('click')
    expect(config.shortcutIds?.[0]).toBe('workflows')
    await wrapper.find('[aria-label="上移Agent"]').trigger('click')
    expect(config.shortcutIds?.slice(0, 2)).toEqual(['agents', 'workflows'])
    expect(config.shortcutPreset).toBe('custom')
    await wrapper.find('[aria-label="移除Agent"]').trigger('click')
    await wrapper.find('select').setValue('projects')
    expect(config.shortcutIds).not.toContain('agents')
    expect(config.shortcutIds?.slice(-1)).toEqual(['projects'])
    wrapper.unmount()
  })

  it('按稳定账号 ID 隔离保存；换账号丢弃旧草稿，空布局不会被默认预设覆盖', () => {
    let owner = 11
    const repository = createDashboardLayoutLocalRepository(() => owner)
    const editor = useDashboardLayoutEditor({ repository, fallback: () => defaultConsoleLayoutFor(['*']) })
    editor.initialize()
    const id = editor.layoutForRender.value.items[0].instanceId
    editor.saveWidgetConfig(id, { title: '我的常用', shortcutPreset: 'custom', shortcutIds: ['agents'] })
    editor.beginEdit()
    editor.updateWidgetConfig(id, { shortcutIds: ['projects'] })
    owner = 22
    editor.initialize()
    expect(editor.editing.value).toBe(false)
    expect(editor.layoutForRender.value.items[0].config.title).toBeUndefined()
    repository.save({ ...editor.layoutForRender.value, items: [] })
    editor.initialize()
    expect(editor.layoutForRender.value.items).toEqual([])
    owner = 11
    editor.initialize()
    expect(editor.layoutForRender.value.items[0].config).toMatchObject({ title: '我的常用', shortcutIds: ['agents'] })
  })

  it('布局编辑期间入口配置可撤销，取消不会写入浏览器', () => {
    const repository = createDashboardLayoutLocalRepository(() => 11)
    const editor = useDashboardLayoutEditor({ repository, fallback: () => defaultConsoleLayoutFor(['*']) })
    const id = editor.layoutForRender.value.items[0].instanceId
    editor.beginEdit()
    editor.saveWidgetConfig(id, { shortcutIds: ['agents'] })
    editor.undo()
    expect(editor.layoutForRender.value.items[0].config.shortcutIds).not.toEqual(['agents'])
    editor.cancelEdit()
    expect(repository.load()).toBeNull()
  })

  it('常态保存失败保留已保存配置，允许原草稿重试，不误报成功', () => {
    const repository = createDashboardLayoutLocalRepository(() => 11)
    const editor = useDashboardLayoutEditor({ repository, fallback: () => defaultConsoleLayoutFor(['*']) })
    const id = editor.layoutForRender.value.items[0].instanceId
    const original = editor.layoutForRender.value.items[0].config
    const failure = vi.spyOn(window.localStorage, 'setItem').mockImplementation(() => { throw new Error('quota') })
    editor.saveWidgetConfig(id, { shortcutPreset: 'custom', shortcutIds: ['agents'] })
    expect(editor.saveError.value).toContain('保存失败')
    expect(editor.layoutForRender.value.items[0].config).toEqual(original)
    failure.mockRestore()
    editor.saveWidgetConfig(id, { shortcutPreset: 'custom', shortcutIds: ['agents'] })
    expect(editor.saveError.value).toBeNull()
    expect(repository.load()?.items[0].config.shortcutIds).toEqual(['agents'])
  })

  it('没有有效账号时不能读取或写入其他账号及旧共享布局', () => {
    const repository = createDashboardLayoutLocalRepository(() => undefined)
    localStorage.setItem('reachai.dashboard.consoleLayout.local.v1', JSON.stringify(defaultConsoleLayoutFor(['*'])))
    expect(repository.load()).toBeNull()
    expect(() => repository.save(defaultConsoleLayoutFor(['*']))).toThrow('登录后才能保存个人布局')
  })

  it('多个预设实例各自配置，修改和删除其中一个不影响其余实例', () => {
    const repository = createDashboardLayoutLocalRepository(() => 11)
    const editor = useDashboardLayoutEditor({ repository, fallback: () => defaultConsoleLayoutFor(['*']) })
    editor.beginEdit()
    editor.addWidgetInstance(SHORTCUT_WIDGET_KEY, shortcutPresetConfig('developer'))
    const shortcuts = editor.layoutForRender.value.items.filter(item => item.widgetKey === SHORTCUT_WIDGET_KEY)
    expect(shortcuts).toHaveLength(2)
    editor.updateWidgetConfig(shortcuts[1].instanceId, { shortcutIds: ['capabilities'], shortcutPreset: 'custom' })
    expect(editor.layoutForRender.value.items.find(item => item.instanceId === shortcuts[0].instanceId)?.config.shortcutPreset).toBe('admin')
    editor.removeWidgetInstance(shortcuts[1].instanceId)
    expect(editor.layoutForRender.value.items.filter(item => item.widgetKey === SHORTCUT_WIDGET_KEY)).toHaveLength(1)
  })
})
