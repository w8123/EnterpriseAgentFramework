import type {
  DashboardLayoutDocument,
  DashboardWidgetGridConstraints,
  DashboardWidgetInstance,
  DashboardWidgetRect,
} from '@/types/operationsDashboard'

export const DASHBOARD_LAYOUT_SCHEMA_VERSION = 1
export const DASHBOARD_GRID_COLUMNS = 12
export const DASHBOARD_GRID_ROW_HEIGHT = 57
export const DASHBOARD_GRID_GAP = 12

export type DashboardResizeEdge = 'left' | 'right' | 'bottom'

export type LayoutParseResult =
  | { ok: true; document: DashboardLayoutDocument }
  | { ok: false; reason: string }

function clamp(value: number, min: number, max: number) {
  if (max < min) return min
  return Math.min(Math.max(value, min), max)
}

function toInt(value: unknown): number | null {
  const num = typeof value === 'number' ? value : Number(value)
  if (!Number.isFinite(num)) return null
  return Math.round(num)
}

/** 限制矩形在列边界内，并应用 Widget min/max 尺寸。 */
export function clampRect(
  rect: DashboardWidgetRect,
  constraints: DashboardWidgetGridConstraints,
  columns: number,
): DashboardWidgetRect {
  const w = clamp(rect.w, constraints.minW, Math.min(constraints.maxW, columns))
  const h = clamp(rect.h, constraints.minH, constraints.maxH)
  const x = clamp(rect.x, 0, columns - w)
  const y = Math.max(0, rect.y)
  return { x, y, w, h }
}

/** 缩放时保持 x/y 不变，只调整宽高并受列边界与 min/max 限制。 */
export function resizeRect(
  rect: DashboardWidgetRect,
  dw: number,
  dh: number,
  constraints: DashboardWidgetGridConstraints,
  columns: number,
): DashboardWidgetRect {
  const w = clamp(rect.w + dw, constraints.minW, Math.min(constraints.maxW, columns - rect.x))
  const h = clamp(rect.h + dh, constraints.minH, constraints.maxH)
  return { x: rect.x, y: rect.y, w, h }
}

/**
 * 从指定边缘缩放矩形：
 * - left 保持原右边界不动，同时调整 x / w；
 * - right 保持 x 不动，只调整 w；
 * - bottom 保持 y 不动，只调整 h。
 */
export function resizeRectFromEdge(
  rect: DashboardWidgetRect,
  dcol: number,
  drow: number,
  edge: DashboardResizeEdge,
  constraints: DashboardWidgetGridConstraints,
  columns: number,
): DashboardWidgetRect {
  if (edge === 'left') {
    const right = Math.min(columns, rect.x + rect.w)
    const maxWidth = Math.min(constraints.maxW, right)
    const w = clamp(rect.w - dcol, constraints.minW, maxWidth)
    return { x: right - w, y: rect.y, w, h: rect.h }
  }
  if (edge === 'right') return resizeRect(rect, dcol, 0, constraints, columns)
  return resizeRect(rect, 0, drow, constraints, columns)
}

export function rectsOverlap(a: DashboardWidgetRect, b: DashboardWidgetRect) {
  return a.x < b.x + b.w && b.x < a.x + a.w && a.y < b.y + b.h && b.y < a.y + a.h
}

function byPosition(a: DashboardWidgetInstance, b: DashboardWidgetInstance) {
  return (
    a.rect.y - b.rect.y ||
    a.rect.x - b.rect.x ||
    (a.instanceId < b.instanceId ? -1 : a.instanceId > b.instanceId ? 1 : 0)
  )
}

/**
 * 确定性碰撞策略：moved 项保持候选位置不动，与它相交的项按 y、x、id 稳定排序后
 * 依次向下推到 moved 正下方，被推项引发的连锁碰撞继续向下推。
 * 不做左右交换，也不在 drop 后自动紧凑。
 */
export function resolveCollisions(
  items: DashboardWidgetInstance[],
  movedId: string,
): DashboardWidgetInstance[] {
  const working = items.map((item) => ({ ...item, rect: { ...item.rect } }))
  const byId = new Map(working.map((item) => [item.instanceId, item]))
  const anchor = byId.get(movedId)
  if (!anchor) return items
  // y 只增不减，循环必然收敛；budget 只是防御异常输入。
  let budget = 1000
  const pushBelow = (id: string) => {
    const current = byId.get(id)
    if (!current || budget <= 0) return
    const colliding = working
      .filter((item) => item.instanceId !== id && rectsOverlap(item.rect, current.rect))
      .sort(byPosition)
    for (const item of colliding) {
      budget -= 1
      if (budget <= 0) return
      const newY = current.rect.y + current.rect.h
      if (item.rect.y >= newY) continue
      item.rect = { ...item.rect, y: newY }
      pushBelow(item.instanceId)
    }
  }
  pushBelow(movedId)
  return working
}

/** 纵向紧凑：保持每项的 x，把各项下移到的最低可行 y；由“整理布局”显式触发。 */
export function compactLayout(
  items: DashboardWidgetInstance[],
  columns: number,
  constraintsFor: (widgetKey: string) => DashboardWidgetGridConstraints | null,
): DashboardWidgetInstance[] {
  const placed: DashboardWidgetInstance[] = []
  for (const item of [...items].sort(byPosition)) {
    const constraints = constraintsFor(item.widgetKey)
    const base = constraints ? clampRect(item.rect, constraints, columns) : { ...item.rect }
    let y = 0
    while (placed.some((settled) => rectsOverlap(settled.rect, { ...base, y }))) y += 1
    placed.push({ ...item, rect: { ...base, y } })
  }
  return placed
}

/** 从上到下、从左到右寻找第一个不与现有项相交的矩形。 */
export function findFreeRect(
  items: DashboardWidgetInstance[],
  columns: number,
  w: number,
  h: number,
): DashboardWidgetRect {
  const maxY = items.reduce((max, item) => Math.max(max, item.rect.y + item.rect.h), 0)
  for (let y = 0; y <= maxY; y += 1) {
    for (let x = 0; x + w <= columns; x += 1) {
      if (!items.some((item) => rectsOverlap(item.rect, { x, y, w, h }))) {
        return { x, y, w, h }
      }
    }
  }
  return { x: 0, y: maxY, w, h }
}

function parseConfig(value: unknown): Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return {}
  const result: Record<string, unknown> = {}
  for (const [key, entry] of Object.entries(value as Record<string, unknown>)) {
    if (key === 'title' && typeof entry === 'string') result.title = entry
    if ((key === 'topN' || key === 'displayCount') && typeof entry === 'number' && Number.isFinite(entry)) {
      result[key] = entry
    }
  }
  return result
}

function parseInstance(value: unknown): DashboardWidgetInstance | null {
  if (!value || typeof value !== 'object') return null
  const candidate = value as Record<string, unknown>
  if (typeof candidate.instanceId !== 'string' || !candidate.instanceId.trim()) return null
  if (typeof candidate.widgetKey !== 'string' || !candidate.widgetKey.trim()) return null
  const rawRect = candidate.rect
  if (!rawRect || typeof rawRect !== 'object') return null
  const rectSource = rawRect as Record<string, unknown>
  const x = toInt(rectSource.x)
  const y = toInt(rectSource.y)
  const w = toInt(rectSource.w)
  const h = toInt(rectSource.h)
  if (x == null || y == null || w == null || h == null) return null
  if (x < 0 || y < 0 || w < 1 || h < 1) return null
  return {
    instanceId: candidate.instanceId,
    widgetKey: candidate.widgetKey,
    rect: { x, y, w, h },
    config: parseConfig(candidate.config),
  }
}

/** 严格 schema 校验；未知版本或结构畸形时返回失败原因，由调用方回退默认布局。 */
export function parseLayoutDocument(value: unknown): LayoutParseResult {
  if (!value || typeof value !== 'object') return { ok: false, reason: '布局根节点不是对象' }
  const candidate = value as Record<string, unknown>
  if (candidate.schemaVersion !== DASHBOARD_LAYOUT_SCHEMA_VERSION) {
    return { ok: false, reason: `未知布局 schemaVersion: ${String(candidate.schemaVersion)}` }
  }
  if (candidate.mode !== 'CONSOLE') return { ok: false, reason: '布局 mode 不受支持' }
  if (candidate.columns !== DASHBOARD_GRID_COLUMNS) {
    return { ok: false, reason: `布局列数必须是 ${DASHBOARD_GRID_COLUMNS}` }
  }
  const revision = toInt(candidate.revision)
  if (revision == null || revision < 0) return { ok: false, reason: '布局 revision 无效' }
  if (!Array.isArray(candidate.items)) return { ok: false, reason: '布局 items 不是数组' }
  const items: DashboardWidgetInstance[] = []
  const seen = new Set<string>()
  for (const raw of candidate.items) {
    const instance = parseInstance(raw)
    if (!instance) return { ok: false, reason: '存在结构无效的布局项' }
    if (seen.has(instance.instanceId)) return { ok: false, reason: '布局项 instanceId 重复' }
    seen.add(instance.instanceId)
    items.push(instance)
  }
  // 空 items 是合法文档：用户可以刻意清空全部模块。
  return {
    ok: true,
    document: {
      schemaVersion: DASHBOARD_LAYOUT_SCHEMA_VERSION,
      revision,
      mode: 'CONSOLE',
      columns: DASHBOARD_GRID_COLUMNS,
      items,
    },
  }
}

export function cloneLayoutDocument(document: DashboardLayoutDocument): DashboardLayoutDocument {
  return JSON.parse(JSON.stringify(document)) as DashboardLayoutDocument
}

export function sameItems(a: DashboardWidgetInstance[], b: DashboardWidgetInstance[]) {
  return JSON.stringify(a) === JSON.stringify(b)
}

/** 网格像素换算：列宽基于容器宽度、列数与 gap。 */
export function computeColumnWidth(containerWidth: number, columns: number, gap: number) {
  return Math.max(0, (containerWidth - gap * (columns - 1)) / columns)
}

/** 像素位移换算为网格单位（列或行），四舍五入到最近单位；-0 归一化为 +0。 */
export function pixelsToGridUnits(deltaPx: number, unitPx: number) {
  if (unitPx <= 0) return 0
  const value = Math.round(deltaPx / unitPx)
  return value === 0 ? 0 : value
}

export function rectToPixels(
  rect: DashboardWidgetRect,
  colWidth: number,
  rowHeight: number,
  gap: number,
) {
  const left = rect.x * (colWidth + gap)
  const top = rect.y * (rowHeight + gap)
  const width = rect.w * colWidth + (rect.w - 1) * gap
  const height = rect.h * rowHeight + (rect.h - 1) * gap
  return { left, top, width, height }
}
