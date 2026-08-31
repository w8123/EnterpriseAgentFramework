import { mount } from '@vue/test-utils'
import { afterEach, describe, expect, it } from 'vitest'
import type { DashboardWidgetInstance } from '@/types/operationsDashboard'
import DashboardGrid from './components/DashboardGrid.vue'
import DashboardUsageTrend from './components/DashboardUsageTrend.vue'

function makeItem(rect = { x: 0, y: 0, w: 2, h: 2 }): DashboardWidgetInstance {
  return {
    instanceId: 'kpi.agent-inventory#default',
    widgetKey: 'kpi.agent-inventory',
    rect,
    config: {},
  }
}

function mountGrid(items: DashboardWidgetInstance[]) {
  return mount(DashboardGrid, {
    props: { items, editing: true },
    attachTo: document.body,
    slots: {
      default: `
        <template #default="{ instance }">
          <div class="fake-frame">
            <header :data-grid-drag="instance.instanceId">
              <span>title</span>
              <button type="button" class="frame-action">settings</button>
            </header>
            <span :data-grid-resize="instance.instanceId" data-grid-resize-edge="left" />
            <span :data-grid-resize="instance.instanceId" data-grid-resize-edge="right" />
            <span :data-grid-resize="instance.instanceId" data-grid-resize-edge="bottom" />
          </div>
        </template>
      `,
    },
  })
}

function pointerEvent(type: string, x: number, y: number) {
  return new PointerEvent(type, { clientX: x, clientY: y, bubbles: true })
}

describe('DashboardGrid pointer 交互', () => {
  const wrappers: ReturnType<typeof mountGrid>[] = []

  afterEach(() => {
    wrappers.splice(0).forEach((wrapper) => wrapper.unmount())
  })

  it('pointerup 提交拖拽候选矩形', () => {
    const wrapper = mountGrid([makeItem()])
    wrappers.push(wrapper)
    const handle = wrapper.find('[data-grid-drag]')
    handle.element.dispatchEvent(pointerEvent('pointerdown', 100, 100))
    window.dispatchEvent(pointerEvent('pointermove', 340, 100))
    window.dispatchEvent(pointerEvent('pointerup', 340, 100))
    const emitted = wrapper.emitted('commitRect') as unknown as [[string, { x: number; y: number }]]
    expect(emitted).toBeTruthy()
    // happy-dom 中容器宽度为 0，像素换算被 clamp 到列边界；只需验证提交的矩形发生了位移
    expect(emitted[0][1].x).toBeGreaterThan(0)
    expect(emitted[0][1].y).toBe(0)
  })

  it('pointercancel 丢弃候选矩形，不做任何提交', () => {
    const wrapper = mountGrid([makeItem()])
    wrappers.push(wrapper)
    const handle = wrapper.find('[data-grid-drag]')
    handle.element.dispatchEvent(pointerEvent('pointerdown', 100, 100))
    window.dispatchEvent(pointerEvent('pointermove', 340, 100))
    window.dispatchEvent(pointerEvent('pointercancel', 340, 100))
    expect(wrapper.emitted('commitRect')).toBeUndefined()
  })

  it('缩放把手 pointerup 提交、pointercancel 丢弃', () => {
    const wrapper = mountGrid([makeItem()])
    wrappers.push(wrapper)
    const handle = wrapper.find('[data-grid-resize-edge="right"]')
    handle.element.dispatchEvent(pointerEvent('pointerdown', 100, 100))
    window.dispatchEvent(pointerEvent('pointermove', 240, 200))
    window.dispatchEvent(pointerEvent('pointercancel', 240, 200))
    expect(wrapper.emitted('commitRect')).toBeUndefined()

    handle.element.dispatchEvent(pointerEvent('pointerdown', 100, 100))
    window.dispatchEvent(pointerEvent('pointermove', 240, 200))
    window.dispatchEvent(pointerEvent('pointerup', 240, 200))
    const emitted = wrapper.emitted('commitRect') as unknown as [[string, { w: number }]]
    expect(emitted).toBeTruthy()
    expect(emitted[0][1].w).toBeGreaterThan(2)
  })

  it('左侧保持右边界缩放，底侧只改变高度', () => {
    const wrapper = mountGrid([makeItem({ x: 4, y: 0, w: 2, h: 2 })])
    wrappers.push(wrapper)

    const left = wrapper.find('[data-grid-resize-edge="left"]')
    left.element.dispatchEvent(pointerEvent('pointerdown', 100, 100))
    window.dispatchEvent(pointerEvent('pointermove', 76, 100))
    window.dispatchEvent(pointerEvent('pointerup', 76, 100))
    const leftCommit = wrapper.emitted('commitRect')?.[0] as [string, { x: number; w: number }]
    expect(leftCommit[1].x).toBeLessThan(4)
    expect(leftCommit[1].x + leftCommit[1].w).toBe(6)

    const bottom = wrapper.find('[data-grid-resize-edge="bottom"]')
    bottom.element.dispatchEvent(pointerEvent('pointerdown', 100, 100))
    window.dispatchEvent(pointerEvent('pointermove', 100, 240))
    window.dispatchEvent(pointerEvent('pointerup', 100, 240))
    const bottomCommit = wrapper.emitted('commitRect')?.[1] as [string, { w: number; h: number }]
    expect(bottomCommit[1].w).toBe(2)
    expect(bottomCommit[1].h).toBeGreaterThan(2)
  })

  it('标题栏内操作按钮不会误启动拖拽', () => {
    const wrapper = mountGrid([makeItem()])
    wrappers.push(wrapper)
    const action = wrapper.find('.frame-action')
    action.element.dispatchEvent(pointerEvent('pointerdown', 100, 100))
    window.dispatchEvent(pointerEvent('pointermove', 340, 100))
    window.dispatchEvent(pointerEvent('pointerup', 340, 100))
    expect(wrapper.emitted('commitRect')).toBeUndefined()
  })

  it('拖拽中编辑能力被禁用时主动丢弃会话，后续 pointerup 不提交', async () => {
    const wrapper = mountGrid([makeItem()])
    wrappers.push(wrapper)
    const handle = wrapper.find('[data-grid-drag]')
    handle.element.dispatchEvent(pointerEvent('pointerdown', 100, 100))
    window.dispatchEvent(pointerEvent('pointermove', 340, 100))

    // 手势进行中切换 editing=false / narrow=true
    await wrapper.setProps({ editing: false, narrow: true })
    // 丢弃路径应已解绑监听；随后的 pointerup 不得产生任何提交
    window.dispatchEvent(pointerEvent('pointerup', 340, 100))
    expect(wrapper.emitted('commitRect')).toBeUndefined()

    // 恢复编辑能力后正常手势仍可提交（会话未残留）
    await wrapper.setProps({ editing: true, narrow: false })
    handle.element.dispatchEvent(pointerEvent('pointerdown', 100, 100))
    window.dispatchEvent(pointerEvent('pointermove', 340, 100))
    window.dispatchEvent(pointerEvent('pointerup', 340, 100))
    const emitted = wrapper.emitted('commitRect') as unknown as [[string, { x: number }]]
    expect(emitted).toBeTruthy()
    expect(emitted[0][1].x).toBeGreaterThan(0)
  })
})

describe('DashboardUsageTrend 渐变 id', () => {
  it('多个实例生成唯一渐变 id 且引用匹配', () => {
    const buckets = [
      { key: 'a', label: '08:00', runs: 1, tokens: 10, users: 1, completed: 1, terminal: 1, technicalCompletionRate: 100 },
      { key: 'b', label: '09:00', runs: 2, tokens: 20, users: 2, completed: 1, terminal: 2, technicalCompletionRate: 50 },
    ]
    const first = mount(DashboardUsageTrend, {
      props: { status: 'ready', buckets, rangeLabel: '近 24 小时' },
    })
    const second = mount(DashboardUsageTrend, {
      props: { status: 'ready', buckets, rangeLabel: '近 24 小时' },
    })
    try {
      const firstDefs = first.findAll('linearGradient')
      const secondDefs = second.findAll('linearGradient')
      expect(firstDefs).toHaveLength(1)
      expect(secondDefs).toHaveLength(1)
      const firstId = firstDefs[0].attributes('id')
      const secondId = secondDefs[0].attributes('id')
      expect(firstId).not.toBe(secondId)
      expect(first.find('polygon').attributes('fill')).toBe(`url(#${firstId})`)
      expect(second.find('polygon').attributes('fill')).toBe(`url(#${secondId})`)
    } finally {
      first.unmount()
      second.unmount()
    }
  })
})
