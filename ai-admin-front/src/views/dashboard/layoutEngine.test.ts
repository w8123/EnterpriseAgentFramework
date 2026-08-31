import { beforeEach, describe, expect, it, vi } from 'vitest'
import type {
  DashboardLayoutDocument,
  DashboardWidgetInstance,
} from '@/types/operationsDashboard'
import {
  clampRect,
  compactLayout,
  computeColumnWidth,
  findFreeRect,
  parseLayoutDocument,
  pixelsToGridUnits,
  rectToPixels,
  resizeRect,
  resizeRectFromEdge,
  resolveCollisions,
} from './layoutEngine'
import { dashboardLayoutLocalRepository } from './dashboardLayoutLocalRepository'
import {
  dashboardWidgetRegistry,
  defaultConsoleLayout,
  sanitizeLayoutItems,
} from './widgetRegistry'

const constraints = {
  minW: 2,
  minH: 2,
  maxW: 6,
  maxH: 4,
  defaultW: 4,
  defaultH: 4,
}

function item(id: string, x: number, y: number, w = 4, h = 4): DashboardWidgetInstance {
  return { instanceId: id, widgetKey: 'ranking.agent-top', rect: { x, y, w, h }, config: {} }
}

describe('layoutEngine 边界限制', () => {
  it('clampRect 限制在 12 列边界内并应用 min/max', () => {
    expect(clampRect({ x: -3, y: -2, w: 99, h: 1 }, constraints, 12)).toEqual({
      x: 0,
      y: 0,
      w: 6,
      h: 2,
    })
    expect(clampRect({ x: 10, y: 5, w: 4, h: 2 }, constraints, 12)).toEqual({
      x: 8,
      y: 5,
      w: 4,
      h: 2,
    })
  })

  it('resizeRect 不改变 x/y，且不超过列右边界', () => {
    expect(resizeRect({ x: 9, y: 0, w: 3, h: 2 }, 5, 5, constraints, 12)).toEqual({
      x: 9,
      y: 0,
      w: 3,
      h: 4,
    })
    expect(resizeRect({ x: 0, y: 0, w: 4, h: 3 }, -9, -9, constraints, 12)).toEqual({
      x: 0,
      y: 0,
      w: 2,
      h: 2,
    })
  })

  it('三侧缩放分别保持对应锚点', () => {
    const origin = { x: 4, y: 2, w: 4, h: 3 }
    expect(resizeRectFromEdge(origin, -2, 0, 'left', constraints, 12)).toEqual({
      x: 2,
      y: 2,
      w: 6,
      h: 3,
    })
    expect(resizeRectFromEdge(origin, 1, 0, 'left', constraints, 12)).toEqual({
      x: 5,
      y: 2,
      w: 3,
      h: 3,
    })
    expect(resizeRectFromEdge(origin, 2, 0, 'right', constraints, 12)).toEqual({
      x: 4,
      y: 2,
      w: 6,
      h: 3,
    })
    expect(resizeRectFromEdge(origin, 0, 1, 'bottom', constraints, 12)).toEqual({
      x: 4,
      y: 2,
      w: 4,
      h: 4,
    })
  })
})

describe('layoutEngine 碰撞下推', () => {
  it('被碰撞项按 y、x 稳定排序后下推到移动项正下方', () => {
    const moved = item('moved', 0, 0, 6, 2)
    const below = item('below', 2, 1, 4, 2)
    const result = resolveCollisions([moved, below], 'moved')
    const pushed = result.find((candidate) => candidate.instanceId === 'below')!
    expect(pushed.rect.y).toBe(2)
    expect(result.find((candidate) => candidate.instanceId === 'moved')!.rect).toEqual({
      x: 0,
      y: 0,
      w: 6,
      h: 2,
    })
  })

  it('连锁碰撞继续向下推，且不会左右换位', () => {
    const a = item('a', 0, 0, 4, 2)
    const b = item('b', 0, 2, 4, 2)
    const c = item('c', 0, 4, 4, 2)
    const result = resolveCollisions(
      [a, b, c].map((entry) => ({ ...entry, rect: { ...entry.rect, y: entry.rect.y + 1 } })),
      'a',
    )
    // a 保持 y=1，b 推到 3，c 连锁推到 5
    expect(result.find((entry) => entry.instanceId === 'a')!.rect.y).toBe(1)
    expect(result.find((entry) => entry.instanceId === 'b')!.rect.y).toBe(3)
    expect(result.find((entry) => entry.instanceId === 'c')!.rect.y).toBe(5)
  })
})

describe('layoutEngine 整理与空位', () => {
  it('compactLayout 保持 x 只收紧 y', () => {
    const items = [
      item('a', 0, 0, 4, 2),
      item('b', 0, 6, 4, 2),
      item('c', 6, 3, 4, 2),
    ]
    const compacted = compactLayout(items, 12, () => constraints)
    expect(compacted.map((entry) => [entry.instanceId, entry.rect.y])).toEqual([
      ['a', 0],
      ['c', 0],
      ['b', 2],
    ])
  })

  it('findFreeRect 找到第一个不冲突的位置', () => {
    const items = [item('a', 0, 0, 12, 2)]
    expect(findFreeRect(items, 12, 4, 2)).toEqual({ x: 0, y: 2, w: 4, h: 2 })
  })
})

describe('layoutEngine 像素换算', () => {
  it('列宽 / 网格单位 / 矩形像素一致', () => {
    const colWidth = computeColumnWidth(1428, 12, 12)
    expect(colWidth).toBeCloseTo(108, 5)
    expect(pixelsToGridUnits(120, 108)).toBe(1)
    expect(pixelsToGridUnits(-50, 108)).toBe(0)
    // -0 归一化为 +0，避免网格坐标出现 "-0"
    expect(Object.is(pixelsToGridUnits(-50, 108), -0)).toBe(false)
    const { left, top, width, height } = rectToPixels({ x: 1, y: 1, w: 2, h: 2 }, 108, 60, 12)
    expect(left).toBe(120)
    expect(top).toBe(72)
    expect(width).toBe(228)
    expect(height).toBe(132)
  })
})

describe('layoutEngine schema 校验与回退', () => {
  it('接受合法 v1 文档', () => {
    const result = parseLayoutDocument(JSON.parse(JSON.stringify(defaultConsoleLayout)))
    expect(result.ok).toBe(true)
  })

  it('拒绝未知 schemaVersion、错误 mode / columns、畸形项与重复 id', () => {
    expect(parseLayoutDocument({ ...defaultConsoleLayout, schemaVersion: 2 }).ok).toBe(false)
    expect(parseLayoutDocument({ ...defaultConsoleLayout, mode: 'SCREEN' }).ok).toBe(false)
    expect(parseLayoutDocument({ ...defaultConsoleLayout, columns: 24 }).ok).toBe(false)
    expect(
      parseLayoutDocument({
        ...defaultConsoleLayout,
        items: [{ instanceId: 'x', widgetKey: 'kpi.agent-inventory' }],
      }).ok,
    ).toBe(false)
    const duplicated = defaultConsoleLayout.items[0]
    expect(
      parseLayoutDocument({
        ...defaultConsoleLayout,
        items: [duplicated, { ...duplicated }],
      }).ok,
    ).toBe(false)
  })

  it('接受刻意清空的空 items 布局', () => {
    const result = parseLayoutDocument({ ...defaultConsoleLayout, items: [] })
    expect(result.ok).toBe(true)
    if (result.ok) expect(result.document.items).toEqual([])
  })
})

describe('widgetRegistry 收口', () => {
  it('默认布局只包含可添加 widget，且矩形满足约束', () => {
    const sanitized = sanitizeLayoutItems(defaultConsoleLayout.items)
    expect(sanitized).toHaveLength(defaultConsoleLayout.items.length)
    for (const entry of sanitized) {
      const definition = dashboardWidgetRegistry[entry.widgetKey]
      expect(definition.availability.state).toBe('AVAILABLE')
      expect(entry.rect.w).toBeGreaterThanOrEqual(definition.grid.minW)
      expect(entry.rect.w).toBeLessThanOrEqual(definition.grid.maxW)
      expect(entry.rect.x + entry.rect.w).toBeLessThanOrEqual(12)
    }
  })

  it('sanitizeLayoutItems 丢弃 DISABLED / 未注册 widget 并裁剪越界配置', () => {
    const sanitized = sanitizeLayoutItems([
      {
        instanceId: 'working',
        widgetKey: 'kpi.active-executions',
        rect: { x: 0, y: 0, w: 2, h: 2 },
        config: {},
      },
      {
        instanceId: 'unknown',
        widgetKey: 'not.registered',
        rect: { x: 0, y: 0, w: 2, h: 2 },
        config: {},
      },
      {
        instanceId: 'top',
        widgetKey: 'ranking.agent-top',
        rect: { x: 99, y: -5, w: 99, h: 1 },
        config: { topN: 42, title: '  ' },
      },
    ])
    expect(sanitized.map((entry) => entry.instanceId)).toEqual(['top'])
    expect(sanitized[0].config.topN).toBe(10)
    expect(sanitized[0].config.title).toBeUndefined()
    expect(sanitized[0].rect).toEqual({ x: 6, y: 0, w: 6, h: 4 })
  })

  it('未接数据模块以 DISABLED 呈现并给出真实原因', () => {
    for (const key of [
      'kpi.active-executions',
      'insight.question-clusters-top',
      'cost.model-usage',
      'telemetry.resource-utilization',
    ]) {
      const definition = dashboardWidgetRegistry[key]
      expect(definition.availability.state).toBe('DISABLED')
      expect(definition.availability.reason).toBeTruthy()
    }
  })
})

describe('dashboardLayoutLocalRepository', () => {
  beforeEach(() => {
    localStorage.clear()
  })

  it('save 自增 revision 并可 load 回来；clear 后回退 null', () => {
    expect(dashboardLayoutLocalRepository.load()).toBeNull()
    const saved = dashboardLayoutLocalRepository.save(defaultConsoleLayout)
    expect(saved.revision).toBe(defaultConsoleLayout.revision + 1)
    const loaded = dashboardLayoutLocalRepository.load()
    expect(loaded).not.toBeNull()
    expect(loaded!.revision).toBe(saved.revision)
    dashboardLayoutLocalRepository.clear()
    expect(dashboardLayoutLocalRepository.load()).toBeNull()
  })

  it('畸形 / 未知版本 localStorage 数据回退 null，不抛异常', () => {
    localStorage.setItem('reachai.dashboard.consoleLayout.local.v1', '{not-json')
    expect(dashboardLayoutLocalRepository.load()).toBeNull()
    const bad: DashboardLayoutDocument = {
      ...defaultConsoleLayout,
      schemaVersion: 99,
    }
    localStorage.setItem('reachai.dashboard.consoleLayout.local.v1', JSON.stringify(bad))
    expect(dashboardLayoutLocalRepository.load()).toBeNull()
  })

  it('刻意清空的空布局可以保存并在 reload 后恢复', () => {
    const empty: DashboardLayoutDocument = { ...defaultConsoleLayout, items: [] }
    const saved = dashboardLayoutLocalRepository.save(empty)
    expect(saved.items).toEqual([])
    const loaded = dashboardLayoutLocalRepository.load()
    expect(loaded).not.toBeNull()
    expect(loaded!.items).toEqual([])
    expect(loaded!.revision).toBe(saved.revision)
  })

  it('items 非空但全部 widgetKey 未知/禁用时回退 null', () => {
    const stale: DashboardLayoutDocument = {
      ...defaultConsoleLayout,
      items: [
        {
          instanceId: 'ghost-1',
          widgetKey: 'not.registered',
          rect: { x: 0, y: 0, w: 2, h: 2 },
          config: {},
        },
        {
          instanceId: 'ghost-2',
          widgetKey: 'kpi.active-executions',
          rect: { x: 2, y: 0, w: 2, h: 2 },
          config: {},
        },
      ],
    }
    localStorage.setItem('reachai.dashboard.consoleLayout.local.v1', JSON.stringify(stale))
    expect(dashboardLayoutLocalRepository.load()).toBeNull()
  })

  it('仅迁移未改动的旧版默认布局，并保留 revision', () => {
    const legacyItems = [
      ['kpi.agent-inventory', 0, 0, 2, 2, {}],
      ['kpi.enabled-agents', 2, 0, 2, 2, {}],
      ['kpi.business-runs', 4, 0, 2, 2, {}],
      ['kpi.active-runtime-users', 6, 0, 2, 2, {}],
      ['kpi.recorded-tokens', 8, 0, 2, 2, {}],
      ['kpi.technical-completion-rate', 10, 0, 2, 2, {}],
      ['ranking.agent-top', 0, 2, 4, 8, { topN: 5 }],
      ['trend.usage', 4, 2, 5, 8, {}],
      ['ranking.project-top', 9, 2, 3, 4, {}],
      ['quality.attention', 9, 6, 3, 4, {}],
      ['projects.operations-cards', 0, 10, 9, 7, { displayCount: 6 }],
      ['distribution.token-by-project', 9, 10, 3, 7, {}],
      ['activity.runtime-stream', 0, 17, 8, 3, {}],
      ['service.data-source-status', 8, 17, 4, 3, {}],
    ].map(([widgetKey, x, y, w, h, config]) => ({
      instanceId: `${widgetKey}#default`,
      widgetKey,
      rect: { x, y, w, h },
      config,
    }))
    localStorage.setItem('reachai.dashboard.consoleLayout.local.v1', JSON.stringify({
      schemaVersion: 1,
      revision: 7,
      mode: 'CONSOLE',
      columns: 12,
      items: legacyItems,
    }))

    const loaded = dashboardLayoutLocalRepository.load()
    expect(loaded?.revision).toBe(7)
    expect(loaded?.items).toHaveLength(defaultConsoleLayout.items.length)
    expect(loaded?.items.some((item) => item.widgetKey === 'service.data-source-status')).toBe(false)
    expect(loaded?.items.find((item) => item.widgetKey === 'activity.runtime-stream')?.rect).toEqual({
      x: 0,
      y: 12,
      w: 12,
      h: 1,
    })
  })

  it('存储写入失败时抛出受控错误而不是静默成功', () => {
    // spyOn 在实例上定义自有 setItem，直接拦截 repository 内的调用，
    // 不受 happy-dom localStorage.clear() 重绑定影响。
    const spy = vi.spyOn(window.localStorage, 'setItem').mockImplementation(() => {
      throw new Error('QuotaExceededError')
    })
    try {
      expect(() => dashboardLayoutLocalRepository.save(defaultConsoleLayout)).toThrowError(
        /保存失败/,
      )
    } finally {
      spy.mockRestore()
    }
  })
})
