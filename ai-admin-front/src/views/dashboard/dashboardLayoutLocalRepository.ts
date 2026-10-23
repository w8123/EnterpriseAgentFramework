import type { DashboardLayoutDocument } from '@/types/operationsDashboard'
import {
  cloneLayoutDocument,
  DASHBOARD_LAYOUT_SCHEMA_VERSION,
  parseLayoutDocument,
} from './layoutEngine'
import { migrateLegacyDefaultLayout, migrateShortcutStripLayout, sanitizeLayoutItems } from './widgetRegistry'

/**
 * Dashboard 布局仓储接口。当前唯一实现是浏览器 localStorage 的临时仓储；
 * 下一轮接入 Control 个人布局 API 时只需替换实现，编辑器不感知存储介质。
 */
export interface DashboardLayoutRepository {
  /** 展示给用户的布局来源说明；必须诚实，不得冒充服务端个人布局。 */
  readonly sourceLabel: string
  load(): DashboardLayoutDocument | null
  /**
   * 保存布局并返回新文档（revision 自增）。
   * 存储失败时抛出 DashboardLayoutSaveError；调用方负责保留草稿并提示用户。
   */
  save(document: DashboardLayoutDocument): DashboardLayoutDocument
  clear(): void
}

/** 存储写入失败（例如 localStorage 配额 / 隐私模式）时的受控错误。 */
export class DashboardLayoutSaveError extends Error {
  constructor(message = '当前浏览器布局保存失败，草稿未丢失，可重试或取消') {
    super(message)
    this.name = 'DashboardLayoutSaveError'
  }
}

const STORAGE_KEY = 'reachai.dashboard.consoleLayout.local.v1'

/**
 * 前端临时布局仓储：只保存“当前浏览器布局”。
 * 畸形数据、未知 schemaVersion 回退 null，由调用方回退默认布局。
 * 区分两种“空 items”：
 * - 文档本身就是空 items：用户刻意清空布局，照常返回；
 * - 文档 items 非空但全部 widgetKey 未知或 DISABLED（收口后为空）：返回 null 回退默认。
 */
export function createDashboardLayoutLocalRepository(ownerId?: () => number | undefined): DashboardLayoutRepository {
  function storageKey() {
    if (!ownerId) return STORAGE_KEY
    const id = ownerId()
    return typeof id === 'number' && Number.isSafeInteger(id) && id > 0 ? `${STORAGE_KEY}.user.${id}` : null
  }
  return {
    sourceLabel: ownerId ? '当前账号 · 此浏览器' : '当前浏览器布局',
    load(): DashboardLayoutDocument | null {
      if (typeof localStorage === 'undefined') return null
      try {
        const key = storageKey()
        const raw = key ? localStorage.getItem(key) : null
        if (!raw) return null
        const parsed = parseLayoutDocument(JSON.parse(raw))
        if (!parsed.ok) return null
        if (parsed.document.items.length === 0) return migrateShortcutStripLayout(parsed.document)
        const items = sanitizeLayoutItems(parsed.document.items)
        if (!items.length) return null
        return migrateShortcutStripLayout(migrateLegacyDefaultLayout({ ...parsed.document, items }))
      } catch {
        return null
      }
    },
    save(document: DashboardLayoutDocument): DashboardLayoutDocument {
      const key = storageKey()
      if (!key) throw new DashboardLayoutSaveError('登录后才能保存个人布局')
      const next: DashboardLayoutDocument = {
        ...cloneLayoutDocument(document),
        schemaVersion: DASHBOARD_LAYOUT_SCHEMA_VERSION,
        revision: document.revision + 1,
      }
      if (typeof localStorage !== 'undefined') {
        try {
          localStorage.setItem(key, JSON.stringify(next))
        } catch (error) {
          throw new DashboardLayoutSaveError(
            error instanceof Error && error.message
              ? `当前浏览器布局保存失败（${error.message}），草稿未丢失，可重试或取消`
              : undefined,
          )
        }
      }
      return next
    },
    clear() {
      const key = storageKey()
      if (typeof localStorage !== 'undefined' && key) localStorage.removeItem(key)
    },
  }
}

/** 旧的未分账号仓储保留给布局迁移校验；管理端使用显式账号仓储。 */
export const dashboardLayoutLocalRepository = createDashboardLayoutLocalRepository()
