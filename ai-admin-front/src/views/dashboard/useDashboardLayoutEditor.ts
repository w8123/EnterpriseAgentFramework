import { computed, ref } from 'vue'
import type {
  DashboardLayoutDocument,
  DashboardWidgetConfig,
  DashboardWidgetInstance,
  DashboardWidgetRect,
} from '@/types/operationsDashboard'
import type { DashboardLayoutRepository } from './dashboardLayoutLocalRepository'
import {
  clampRect,
  cloneLayoutDocument,
  compactLayout,
  DASHBOARD_GRID_COLUMNS,
  findFreeRect,
  resizeRect,
  resolveCollisions,
  sameItems,
} from './layoutEngine'
import { getWidgetDefinition } from './widgetRegistry'

const HISTORY_LIMIT = 50

let instanceSequence = 0
function nextInstanceId(widgetKey: string) {
  instanceSequence += 1
  const random =
    typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function'
      ? crypto.randomUUID().slice(0, 8)
      : Math.random().toString(36).slice(2, 10)
  return `${widgetKey}~${random}${instanceSequence.toString(36)}`
}

/**
 * CONSOLE 布局编辑器：
 * - 编辑只修改深拷贝 Draft，取消可完全恢复已保存布局；
 * - 撤销/重做覆盖添加、移动、缩放、复制、删除、设置、整理与恢复默认；
 * - 保存写入注入的 repository（当前为浏览器 localStorage），revision 自增。
 */
export function useDashboardLayoutEditor(options: {
  repository: DashboardLayoutRepository
  fallback: DashboardLayoutDocument
}) {
  const savedLayout = ref<DashboardLayoutDocument>(cloneLayoutDocument(options.fallback))
  const draftLayout = ref<DashboardLayoutDocument | null>(null)
  const undoStack = ref<DashboardWidgetInstance[][]>([])
  const redoStack = ref<DashboardWidgetInstance[][]>([])

  const editing = computed(() => draftLayout.value != null)
  const canUndo = computed(() => undoStack.value.length > 0)
  const canRedo = computed(() => redoStack.value.length > 0)
  const savedRevision = computed(() => savedLayout.value.revision)
  const layoutForRender = computed(() => draftLayout.value ?? savedLayout.value)
  /** 保存失败信息；非空时编辑会话与草稿保留，用户可重试或取消。 */
  const saveError = ref<string | null>(null)

  function initialize() {
    const loaded = options.repository.load()
    if (loaded) savedLayout.value = loaded
  }

  function beginEdit() {
    if (editing.value) return
    draftLayout.value = cloneLayoutDocument(savedLayout.value)
    undoStack.value = []
    redoStack.value = []
    saveError.value = null
  }

  function cancelEdit() {
    draftLayout.value = null
    undoStack.value = []
    redoStack.value = []
    saveError.value = null
  }

  function saveEdit() {
    if (draftLayout.value == null) return
    let saved: DashboardLayoutDocument
    try {
      saved = options.repository.save(draftLayout.value)
    } catch (error) {
      saveError.value = error instanceof Error && error.message ? error.message : '布局保存失败，草稿未丢失'
      return
    }
    savedLayout.value = saved
    draftLayout.value = null
    undoStack.value = []
    redoStack.value = []
    saveError.value = null
  }

  /** 所有编辑动作都经过这里：写入 Draft 并记录一条历史（无变化则跳过）。 */
  function applyChange(next: DashboardWidgetInstance[]) {
    if (draftLayout.value == null) return
    const current = draftLayout.value.items
    if (sameItems(current, next)) return
    // 保留最近 HISTORY_LIMIT 条历史：超限时从头部丢弃最旧条目。
    const stack = undoStack.value
    const kept = stack.length >= HISTORY_LIMIT ? stack.slice(stack.length - HISTORY_LIMIT + 1) : stack
    undoStack.value = [...kept, current]
    redoStack.value = []
    draftLayout.value = { ...draftLayout.value, items: next }
  }

  function undo() {
    if (draftLayout.value == null || !undoStack.value.length) return
    const previous = undoStack.value[undoStack.value.length - 1]
    undoStack.value = undoStack.value.slice(0, -1)
    redoStack.value = [...redoStack.value, draftLayout.value.items]
    draftLayout.value = { ...draftLayout.value, items: previous }
  }

  function redo() {
    if (draftLayout.value == null || !redoStack.value.length) return
    const next = redoStack.value[redoStack.value.length - 1]
    redoStack.value = redoStack.value.slice(0, -1)
    undoStack.value = [...undoStack.value, draftLayout.value.items]
    draftLayout.value = { ...draftLayout.value, items: next }
  }

  function replaceRect(items: DashboardWidgetInstance[], id: string, rect: DashboardWidgetRect) {
    return items.map((item) => (item.instanceId === id ? { ...item, rect } : item))
  }

  function addWidgetInstance(widgetKey: string) {
    if (draftLayout.value == null) return
    const definition = getWidgetDefinition(widgetKey)
    if (!definition || definition.availability.state !== 'AVAILABLE') return
    const rect = findFreeRect(
      draftLayout.value.items,
      DASHBOARD_GRID_COLUMNS,
      definition.grid.defaultW,
      definition.grid.defaultH,
    )
    const created: DashboardWidgetInstance = {
      instanceId: nextInstanceId(widgetKey),
      widgetKey,
      rect,
      config: { ...definition.defaultConfig },
    }
    applyChange(resolveCollisions([...draftLayout.value.items, created], created.instanceId))
  }

  function removeWidgetInstance(id: string) {
    if (draftLayout.value == null) return
    applyChange(draftLayout.value.items.filter((item) => item.instanceId !== id))
  }

  function duplicateWidgetInstance(id: string) {
    if (draftLayout.value == null) return
    const source = draftLayout.value.items.find((item) => item.instanceId === id)
    if (!source) return
    const definition = getWidgetDefinition(source.widgetKey)
    if (!definition || definition.availability.state !== 'AVAILABLE') return
    const rect = clampRect(
      { ...source.rect, x: source.rect.x + 1, y: source.rect.y + 1 },
      definition.grid,
      DASHBOARD_GRID_COLUMNS,
    )
    const copy: DashboardWidgetInstance = {
      instanceId: nextInstanceId(source.widgetKey),
      widgetKey: source.widgetKey,
      rect,
      config: { ...source.config },
    }
    applyChange(resolveCollisions([...draftLayout.value.items, copy], copy.instanceId))
  }

  function moveWidgetInstanceBy(id: string, dx: number, dy: number) {
    if (draftLayout.value == null) return
    const target = draftLayout.value.items.find((item) => item.instanceId === id)
    if (!target) return
    const definition = getWidgetDefinition(target.widgetKey)
    if (!definition) return
    const rect = clampRect(
      { ...target.rect, x: target.rect.x + dx, y: target.rect.y + dy },
      definition.grid,
      DASHBOARD_GRID_COLUMNS,
    )
    applyChange(resolveCollisions(replaceRect(draftLayout.value.items, id, rect), id))
  }

  function resizeWidgetInstanceBy(id: string, dw: number, dh: number) {
    if (draftLayout.value == null) return
    const target = draftLayout.value.items.find((item) => item.instanceId === id)
    if (!target) return
    const definition = getWidgetDefinition(target.widgetKey)
    if (!definition) return
    const rect = resizeRect(target.rect, dw, dh, definition.grid, DASHBOARD_GRID_COLUMNS)
    applyChange(resolveCollisions(replaceRect(draftLayout.value.items, id, rect), id))
  }

  /** Pointer 拖拽/缩放结束时提交候选矩形（已经过 clamp 预览）。 */
  function commitWidgetRect(id: string, rect: DashboardWidgetRect) {
    if (draftLayout.value == null) return
    const target = draftLayout.value.items.find((item) => item.instanceId === id)
    if (!target) return
    const definition = getWidgetDefinition(target.widgetKey)
    if (!definition) return
    const clamped = clampRect(rect, definition.grid, DASHBOARD_GRID_COLUMNS)
    applyChange(resolveCollisions(replaceRect(draftLayout.value.items, id, clamped), id))
  }

  function updateWidgetConfig(id: string, patch: Partial<DashboardWidgetConfig>) {
    if (draftLayout.value == null) return
    const target = draftLayout.value.items.find((item) => item.instanceId === id)
    if (!target) return
    const definition = getWidgetDefinition(target.widgetKey)
    if (!definition) return
    const config: DashboardWidgetConfig = { ...target.config }
    if ('title' in patch) {
      const title = typeof patch.title === 'string' ? patch.title.trim() : ''
      if (title) config.title = title
      else delete config.title
    }
    for (const field of definition.configFields) {
      if (!(field.key in patch)) continue
      const raw = patch[field.key]
      if (typeof raw !== 'number' || !Number.isFinite(raw)) continue
      config[field.key] = Math.min(field.max, Math.max(field.min, Math.round(raw)))
    }
    applyChange(
      draftLayout.value.items.map((item) =>
        item.instanceId === id ? { ...item, config } : item,
      ),
    )
  }

  function tidyLayout() {
    if (draftLayout.value == null) return
    applyChange(
      compactLayout(
        draftLayout.value.items,
        DASHBOARD_GRID_COLUMNS,
        (widgetKey) => getWidgetDefinition(widgetKey)?.grid ?? null,
      ),
    )
  }

  function resetToDefault() {
    if (draftLayout.value == null) return
    applyChange(cloneLayoutDocument(options.fallback).items)
  }

  return {
    savedRevision,
    editing,
    canUndo,
    canRedo,
    saveError,
    layoutForRender,
    initialize,
    beginEdit,
    cancelEdit,
    saveEdit,
    undo,
    redo,
    addWidgetInstance,
    removeWidgetInstance,
    duplicateWidgetInstance,
    moveWidgetInstanceBy,
    resizeWidgetInstanceBy,
    commitWidgetRect,
    updateWidgetConfig,
    tidyLayout,
    resetToDefault,
  }
}
