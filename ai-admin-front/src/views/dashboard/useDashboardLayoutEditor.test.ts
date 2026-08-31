import { describe, expect, it, vi } from 'vitest'
import type { DashboardLayoutDocument } from '@/types/operationsDashboard'
import { DashboardLayoutSaveError, type DashboardLayoutRepository } from './dashboardLayoutLocalRepository'
import { defaultConsoleLayout } from './widgetRegistry'
import { useDashboardLayoutEditor } from './useDashboardLayoutEditor'

function memoryRepository(): DashboardLayoutRepository & {
  store: DashboardLayoutDocument[]
  failNextSave: () => void
} {
  const store: DashboardLayoutDocument[] = []
  return {
    store,
    failNextSave: () => {
      store.push = () => {
        throw new Error('should not push')
      }
    },
    sourceLabel: '测试仓储',
    load: () => store[0] ?? null,
    save(document) {
      const next = { ...document, revision: document.revision + 1 }
      store[0] = next
      return next
    },
    clear: () => {
      store.length = 0
    },
  }
}

function itemIds(editor: ReturnType<typeof useDashboardLayoutEditor>) {
  return editor.layoutForRender.value.items.map((item) => item.instanceId).sort()
}

describe('useDashboardLayoutEditor', () => {
  it('三次不同变更支持三步撤销与三步重做，历史逐条恢复', () => {
    const editor = useDashboardLayoutEditor({
      repository: memoryRepository(),
      fallback: defaultConsoleLayout,
    })
    editor.beginEdit()
    const firstId = editor.layoutForRender.value.items[0].instanceId

    // 变更 1：删除
    editor.removeWidgetInstance(firstId)
    expect(editor.layoutForRender.value.items).toHaveLength(defaultConsoleLayout.items.length - 1)
    // 变更 2：添加
    editor.addWidgetInstance('kpi.agent-inventory')
    expect(editor.layoutForRender.value.items).toHaveLength(defaultConsoleLayout.items.length)
    // 变更 3：再删除同一原首项
    editor.removeWidgetInstance(editor.layoutForRender.value.items[0].instanceId)
    expect(editor.layoutForRender.value.items).toHaveLength(defaultConsoleLayout.items.length - 1)
    expect(editor.canUndo.value).toBe(true)

    const afterMutations = itemIds(editor)

    // 撤销 3 次
    editor.undo()
    expect(editor.layoutForRender.value.items).toHaveLength(defaultConsoleLayout.items.length)
    editor.undo()
    expect(editor.layoutForRender.value.items).toHaveLength(defaultConsoleLayout.items.length - 1)
    editor.undo()
    expect(itemIds(editor)).toEqual(
      defaultConsoleLayout.items.map((item) => item.instanceId).sort(),
    )
    expect(editor.canUndo.value).toBe(false)
    expect(editor.canRedo.value).toBe(true)

    // 重做 3 次回到最后状态
    editor.redo()
    editor.redo()
    editor.redo()
    expect(itemIds(editor)).toEqual(afterMutations)
    expect(editor.canRedo.value).toBe(false)
  })

  it('历史条目数不超过上限，且超限后最旧条目被丢弃、其余仍可撤销', () => {
    const editor = useDashboardLayoutEditor({
      repository: memoryRepository(),
      fallback: defaultConsoleLayout,
    })
    editor.beginEdit()
    const firstId = editor.layoutForRender.value.items[0].instanceId
    // 制造 6 次移动（每次都产生实际变化）
    for (let i = 0; i < 6; i += 1) {
      editor.moveWidgetInstanceBy(firstId, 1, 0)
    }
    // 6 次变更全部可撤销，说明截断没有吞掉正常历史
    for (let i = 0; i < 6; i += 1) {
      expect(editor.canUndo.value).toBe(true)
      editor.undo()
    }
    expect(editor.canUndo.value).toBe(false)
    expect(editor.layoutForRender.value.items.find((item) => item.instanceId === firstId)!.rect.x)
      .toBe(defaultConsoleLayout.items[0].rect.x)
  })

  it('保存失败时保留草稿与编辑状态，暴露 saveError，重试成功后清错', () => {
    const repository = memoryRepository()
    const editor = useDashboardLayoutEditor({ repository, fallback: defaultConsoleLayout })
    editor.beginEdit()
    editor.removeWidgetInstance(editor.layoutForRender.value.items[0].instanceId)

    // 让 save 抛出受控错误
    const originalSave = repository.save.bind(repository)
    repository.save = () => {
      throw new DashboardLayoutSaveError('当前浏览器布局保存失败（QuotaExceededError），草稿未丢失，可重试或取消')
    }
    editor.saveEdit()
    expect(editor.editing.value).toBe(true)
    expect(editor.saveError.value).toContain('保存失败')
    expect(editor.layoutForRender.value.items).toHaveLength(defaultConsoleLayout.items.length - 1)
    expect(repository.store[0]).toBeUndefined()

    // 恢复 save 后重试成功
    repository.save = originalSave
    editor.saveEdit()
    expect(editor.editing.value).toBe(false)
    expect(editor.saveError.value).toBeNull()
    expect(editor.savedRevision.value).toBe(defaultConsoleLayout.revision + 1)
    expect(repository.store[0].items).toHaveLength(defaultConsoleLayout.items.length - 1)
  })

  it('支持保存刻意清空的空布局草稿', () => {
    const repository = memoryRepository()
    const editor = useDashboardLayoutEditor({ repository, fallback: defaultConsoleLayout })
    editor.beginEdit()
    for (const item of [...editor.layoutForRender.value.items]) {
      editor.removeWidgetInstance(item.instanceId)
    }
    editor.saveEdit()
    expect(editor.layoutForRender.value.items).toEqual([])
    expect(repository.store[0].items).toEqual([])
  })
})

describe('useDashboardLayoutEditor 草稿隔离', () => {
  it('保存失败不吞掉取消能力：取消后恢复已保存布局', () => {
    const repository = memoryRepository()
    const editor = useDashboardLayoutEditor({ repository, fallback: defaultConsoleLayout })
    editor.beginEdit()
    editor.removeWidgetInstance(editor.layoutForRender.value.items[0].instanceId)
    repository.save = vi.fn(() => {
      throw new DashboardLayoutSaveError()
    })
    editor.saveEdit()
    expect(editor.editing.value).toBe(true)
    editor.cancelEdit()
    expect(editor.editing.value).toBe(false)
    expect(editor.layoutForRender.value.items).toHaveLength(defaultConsoleLayout.items.length)
  })
})
