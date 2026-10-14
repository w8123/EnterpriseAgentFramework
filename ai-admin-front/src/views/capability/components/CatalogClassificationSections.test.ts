import { flushPromises, mount } from '@vue/test-utils'
import { defineComponent } from 'vue'
import ElementPlus from 'element-plus'
import { describe, expect, it } from 'vitest'
import CatalogClassificationSections from './CatalogClassificationSections.vue'
import type { CatalogClassificationIndex } from '@/types/semanticDoc'
const index = (count = 2): CatalogClassificationIndex => ({ projectId: 7, subjectType: 'HTTP_API',
  groups: Array.from({ length: count }, (_, i) => ({ id: i + 1, projectId: 7, name: `module-${i}`, displayName: `业务 ${i + 1}`, sourceClasses: [], memberCount: 12 })),
  memberships: [{ subjectId: 10, groupIds: [1, 2] },
    ...Array.from({ length: count }, (_, i) => Array.from({ length: i < 2 ? 11 : 12 }, (_, n) => ({ subjectId: 1000 + i * 100 + n, groupIds: [i + 1] }))).flat(),
    ...Array.from({ length: 3 }, (_, i) => ({ subjectId: 3000 + i, groupIds: [] }))], ungroupedCount: 3 })
const setup = (props = {}) => mount(CatalogClassificationSections, { props: { rows: [{ id: 10 }], identity: (row: { id?: number; assetId?: number }) => row.id || row.assetId || 0,
  index: index(), selected: '', loading: false, error: '', ...props }, global: { plugins: [ElementPlus] },
  slots: { default: '<div data-test="rows">{{ params.rows.length }} 条</div><span data-test="selected-ids">{{ params.selectedIds.join(",") }}</span><button data-test="slot-toggle" @click="params.toggleRow(params.rows[0], true)">选中</button>' } })
describe('classification sections and compact navigation', () => {
  it('renders the flat variant once while retaining category navigation and page selection', async () => {
    const wrapper = setup({ layout: 'flat', rows: [{ id: 10 }, { id: 3000 }], canWrite: true, adjusting: true })
    expect(wrapper.findAll('[data-test="rows"]')).toHaveLength(1)
    expect(wrapper.get('[data-test="rows"]').text()).toBe('2 条')
    expect(wrapper.find('.classification-section__toggle').exists()).toBe(false)
    expect(wrapper.find('button[data-classification="1"]').exists()).toBe(true)
    wrapper.findComponent({ name: 'ElCheckbox' }).vm.$emit('change', true)
    await wrapper.vm.$nextTick()
    expect(wrapper.get('[data-test="selected-ids"]').text()).toBe('10,3000')
    await wrapper.setProps({ showNavigation: false })
    expect(wrapper.find('button[data-classification="1"]').exists()).toBe(false)
    expect(wrapper.get('[data-test="rows"]').text()).toBe('2 条')
    expect(wrapper.get('[data-test="selected-ids"]').text()).toBe('10,3000')
    wrapper.unmount()
  })
  it('offers independent create on an empty adjustment page and shows zero-member project groups', async () => {
    const value = index(); value.groups.forEach(group => { group.memberCount = 0 }); value.memberships = []; value.ungroupedCount = 0
    const wrapper = setup({ index: value, rows: [], canWrite: true, adjusting: true })
    expect(wrapper.find('button[data-classification="2"]').exists()).toBe(true)
    const create = wrapper.findAll('button').find(item => item.text() === '新增分类')!
    await create.trigger('click'); expect(wrapper.emitted('create')).toEqual([[]])
    expect(wrapper.findComponent({ name: 'ElCheckbox' }).exists()).toBe(false)
    await wrapper.setProps({ adjusting: false })
    expect(wrapper.findAll('button').some(item => item.text() === '新增分类')).toBe(false)
    expect(wrapper.find('button[data-classification="2"]').exists()).toBe(false)
    wrapper.unmount()
  })
  it('disables classification creation while the index is unavailable', async () => {
    const wrapper = setup({ canWrite: true, adjusting: true, index: null, loading: true })
    const create = wrapper.findAll('button').find(item => item.text() === '新增分类')!
    expect(create.attributes('disabled')).toBeDefined()
    await create.trigger('click'); expect(wrapper.emitted('create')).toBeUndefined()
    wrapper.unmount()
  })
  it('updates real nested table row checkboxes when page selection changes', async () => {
    const table = defineComponent({ components: { CatalogClassificationSections }, setup() {
      return { rows: [{ id: 10 }, { id: 3000 }], value: index(), identity: (row: { id: number }) => row.id }
    }, template: `<CatalogClassificationSections :rows="rows" :identity="identity" :index="value" selected="" :loading="false" error="" can-write adjusting>
      <template #default="{ rows: sectionRows, isSelected, toggleRow }"><el-table :data="sectionRows" row-key="id">
        <el-table-column><template #default="{ row }"><el-checkbox :model-value="isSelected(row)" :aria-label="'row-' + row.id" @change="toggleRow(row, !!$event)" /></template></el-table-column>
      </el-table></template></CatalogClassificationSections>` })
    const wrapper = mount(table, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    const rowInputs = () => wrapper.findAll('.el-table input[type="checkbox"]')
    expect(rowInputs()).toHaveLength(2)
    wrapper.findComponent({ name: 'ElCheckbox' }).vm.$emit('change', true)
    await flushPromises()
    expect(wrapper.text()).toContain('已选 2 项')
    expect(rowInputs().every(input => (input.element as HTMLInputElement).checked)).toBe(true)
    await rowInputs()[0].setValue(false); await flushPromises()
    expect(wrapper.text()).toContain('已选 1 项')
    expect((rowInputs()[0].element as HTMLInputElement).checked).toBe(false)
    expect((rowInputs()[1].element as HTMLInputElement).checked).toBe(true)
    wrapper.unmount()
  })
  it('hides unused categories while retaining a selected empty bookmark', async () => {
    const value = index(); value.groups[1].memberCount = 0
    const wrapper = setup({ index: value })
    expect(wrapper.find('button[data-classification="2"]').exists()).toBe(false)
    await wrapper.setProps({ selected: '2', rows: [] })
    expect(wrapper.get('button[data-classification="2"]').text()).toContain('业务 2')
    wrapper.unmount()
  })
  it('keeps selection controls hidden by default and ignores slot selection outside adjustment mode', async () => {
    const wrapper = setup({ canWrite: true })
    expect(wrapper.find('.classification-selection').exists()).toBe(false)
    await wrapper.get('[data-test="slot-toggle"]').trigger('click')
    expect(wrapper.get('[data-test="selected-ids"]').text()).toBe('')
    wrapper.unmount()
  })
  it('selects the current page across sections and clears selection on page changes', async () => {
    const wrapper = setup({ rows: [{ id: 10 }, { id: 3000 }], canWrite: true, adjusting: true })
    const checkbox = wrapper.findComponent({ name: 'ElCheckbox' })
    checkbox.vm.$emit('change', true); await wrapper.vm.$nextTick()
    expect(wrapper.text()).toContain('已选 2 项')
    await wrapper.findAll('button').find(item => item.text() === '批量归类')!.trigger('click')
    expect(wrapper.emitted('assign')?.[0]).toEqual([[{ id: 10 }, { id: 3000 }]])
    await wrapper.setProps({ rows: [{ id: 11 }] })
    expect(wrapper.text()).toContain('已选 0 项'); wrapper.unmount()
  })

  it('clears selection when adjustment mode closes and does not restore it on reentry', async () => {
    const wrapper = setup({ canWrite: true, adjusting: true })
    wrapper.findComponent({ name: 'ElCheckbox' }).vm.$emit('change', true)
    await wrapper.vm.$nextTick()
    expect(wrapper.get('[data-test="selected-ids"]').text()).toBe('10')
    await wrapper.setProps({ adjusting: false })
    expect(wrapper.find('.classification-selection').exists()).toBe(false)
    expect(wrapper.get('[data-test="selected-ids"]').text()).toBe('')
    await wrapper.get('[data-test="slot-toggle"]').trigger('click')
    expect(wrapper.get('[data-test="selected-ids"]').text()).toBe('')
    await wrapper.setProps({ adjusting: true })
    expect(wrapper.text()).toContain('已选 0 项')
    wrapper.unmount()
  })

  it('does not select rows without write permission even while adjusting', async () => {
    const wrapper = setup({ adjusting: true, canWrite: false })
    await wrapper.get('[data-test="slot-toggle"]').trigger('click')
    expect(wrapper.get('[data-test="selected-ids"]').text()).toBe('')
    expect(wrapper.find('.classification-selection').exists()).toBe(false)
    wrapper.unmount()
  })

  it('shows page and category counts separately and expands or collapses a category in place', async () => {
    const wrapper = setup()
    expect(wrapper.text()).toContain('本页 1 项')
    expect(wrapper.text()).toContain('分类共 12 项')
    expect(wrapper.findAll('[data-test="rows"]')).toHaveLength(1)
    await wrapper.get('button[aria-label="收起业务 1分类"]').trigger('click')
    expect(wrapper.find('[data-test="rows"]').exists()).toBe(false)
    await wrapper.get('button[aria-label="展开业务 1分类"]').trigger('click')
    expect(wrapper.find('[data-test="rows"]').exists()).toBe(true)
    wrapper.unmount()
  })
  it('reopens a collapsed matching category when the committed search changes', async () => {
    const wrapper = setup({ searchKey: '["", "", ""]' })
    await wrapper.get('button[aria-label="收起业务 1分类"]').trigger('click')
    expect(wrapper.find('[data-test="rows"]').exists()).toBe(false)

    await wrapper.setProps({ searchKey: '["查询", "", ""]' })
    await wrapper.setProps({ rows: [{ id: 10 }] })

    expect(wrapper.get('button[aria-label="收起业务 1分类"]').attributes('aria-expanded')).toBe('true')
    expect(wrapper.get('[data-test="rows"]').text()).toBe('1 条')
    wrapper.unmount()
  })
  it('reopens current results when a committed dropdown condition changes', async () => {
    const wrapper = setup({ searchKey: '["查询", "", ""]' })
    await wrapper.get('button[aria-label="收起业务 1分类"]').trigger('click')
    await wrapper.setProps({ searchKey: '["查询", "", "ACTIVE"]' })
    expect(wrapper.get('[data-test="rows"]').text()).toBe('1 条')
    wrapper.unmount()
  })
  it('reopens the selected category when a committed category filter changes', async () => {
    const wrapper = setup({ rows: [{ id: 10 }, { id: 1100 }], searchKey: '["查询", "", ""]' })
    await wrapper.get('button[aria-label="收起业务 2分类"]').trigger('click')
    await wrapper.setProps({ rows: [{ id: 10 }], selected: '2', searchKey: '["查询", "2", ""]' })
    expect(wrapper.get('[data-classification-section="2"] [data-test="rows"]').text()).toBe('1 条')
    wrapper.unmount()
  })
  it('keeps the current category collapsed while paging or refreshing the same committed conditions', async () => {
    const wrapper = setup({ searchKey: '["查询", "", ""]' })
    await wrapper.get('button[aria-label="收起业务 1分类"]').trigger('click')
    await wrapper.setProps({ index: null, loading: true })
    await wrapper.setProps({ index: index(), loading: false })
    await wrapper.setProps({ rows: [{ id: 1000 }], index: index() })
    expect(wrapper.get('button[aria-label="展开业务 1分类"]').attributes('aria-expanded')).toBe('false')
    expect(wrapper.find('[data-test="rows"]').exists()).toBe(false)
    wrapper.unmount()
  })
  it('starts with visible results and clears the old collapsed state on a project change', async () => {
    const wrapper = setup({ searchKey: '["查询", "", ""]' })
    expect(wrapper.get('[data-test="rows"]').text()).toBe('1 条')
    await wrapper.get('button[aria-label="收起业务 1分类"]').trigger('click')
    const nextIndex = index()
    nextIndex.projectId = 8
    nextIndex.groups.forEach(group => { group.projectId = 8 })
    await wrapper.setProps({ index: nextIndex })
    expect(wrapper.get('[data-test="rows"]').text()).toBe('1 条')
    wrapper.unmount()
  })
  it('clears collapsed categories when leaving and returning from all-project scope', async () => {
    const wrapper = setup()
    await wrapper.get('button[aria-label="收起业务 1分类"]').trigger('click')
    await wrapper.setProps({ allProjects: true, index: null })
    expect(wrapper.find('[data-classification-section="1"]').exists()).toBe(false)
    expect(wrapper.get('[data-test="rows"]').text()).toBe('1 条')
    await wrapper.setProps({ allProjects: false, index: index() })
    expect(wrapper.get('[data-test="rows"]').text()).toBe('1 条')
    wrapper.unmount()
  })
  it('omits a duplicate category count when this page contains every member', () => {
    const value = index()
    value.groups[0]!.memberCount = 1
    value.memberships = value.memberships.filter(member => member.subjectId === 10 || !member.groupIds.includes(1))
    const wrapper = setup({ index: value })
    expect(wrapper.get('.classification-section__count').text()).toBe('本页 1 项')
    wrapper.unmount()
  })
  it('announces selected category filters and keeps the zero-count unclassified entrance', async () => {
    const value = index()
    value.ungroupedCount = 0
    value.memberships = value.memberships.filter(member => member.groupIds.length)
    const wrapper = setup({ index: value })
    expect(wrapper.get('button[data-classification=""]').attributes('aria-pressed')).toBe('true')
    expect(wrapper.get('button[data-classification="1"]').attributes('aria-pressed')).toBe('false')
    expect(wrapper.get('button[data-classification="unclassified"]').text()).toContain('未分类 0')
    await wrapper.setProps({ selected: '1' })
    expect(wrapper.get('button[data-classification=""]').attributes('aria-pressed')).toBe('false')
    expect(wrapper.get('button[data-classification="1"]').attributes('aria-pressed')).toBe('true')
    await wrapper.setProps({ selected: 'unclassified' })
    expect(wrapper.get('button[data-classification="unclassified"]').attributes('aria-pressed')).toBe('true')
    wrapper.unmount()
  })
  it('keeps project-wide counts accessible without visible navigation hints', () => {
    const wrapper = setup({ selected: '1' })
    expect(wrapper.get('.classification-overview').attributes('aria-label')).toBe('业务分类，数量为项目全量')
    expect(wrapper.get('.classification-navigation__label').text()).toBe('业务分类')
    expect(wrapper.find('.classification-search-context').exists()).toBe(false)
    wrapper.unmount()
  })
  it('retains the selected empty category title and gates rename while keeping description readable', async () => {
    const wrapper = setup({ rows: [], selected: '2', canWrite: true })
    expect(wrapper.get('[data-classification-section="2"]').text()).toContain('业务 2')
    expect(wrapper.find('button[aria-label="仅查看业务 2分类"]').exists()).toBe(false)
    expect(wrapper.findComponent({ name: 'ElDropdown' }).exists()).toBe(false)
    const tooltip = wrapper.getComponent({ name: 'ElTooltip' })
    expect(tooltip.props()).toMatchObject({ content: '查看分类说明', trigger: ['hover', 'focus'] })
    await wrapper.get('button[aria-label="查看业务 2分类说明"]').trigger('click')
    expect(wrapper.emitted('edit')).toEqual([[index().groups[1], 'description']])
    await wrapper.setProps({ adjusting: true })
    expect(wrapper.find('button[aria-label="查看业务 2分类说明"]').exists()).toBe(false)
    const menu = wrapper.getComponent({ name: 'ElDropdown' })
    expect(wrapper.findAllComponents({ name: 'ElDropdownItem' }).map(item => item.props('command'))).toEqual(['description', 'rename'])
    menu.vm.$emit('command', 'rename')
    expect(wrapper.emitted('edit')).toEqual([[index().groups[1], 'description'], [index().groups[1], 'rename']])
    wrapper.unmount()
  })
  it('uses one-level navigation for many categories and never loses empty full-project categories', async () => {
    const wrapper = setup({ index: index(8) })
    expect(wrapper.find('.classification-navigation').exists()).toBe(true)
    const nav = wrapper.get('.classification-navigation')
    expect(nav.text()).toContain('业务 8')
    await nav.get('button[data-classification="8"]').trigger('click')
    expect(wrapper.emitted('select')?.[0]).toEqual(['8'])
    expect(wrapper.findAll('[data-test="rows"]')).toHaveLength(1)
    wrapper.unmount()
  })
  it('keeps unread classifications pending with an explicit retry, then recovers', async () => {
    const wrapper = setup({ index: null, error: '业务分类读取失败' })
    expect(wrapper.text()).toContain('分类待更新')
    expect(wrapper.text()).not.toContain('未分类')
    await wrapper.get('button[aria-label="重新读取业务分类"]').trigger('click')
    expect(wrapper.emitted('retry')).toHaveLength(1)
    await wrapper.setProps({ index: index(), error: '' })
    expect(wrapper.text()).toContain('业务 1')
    wrapper.unmount()
  })
})
