/**
 * Page Action pending 补偿轮询调度器。
 *
 * 产品语义：SSE / completion queue 是主路径；pending GET 只做迟到补偿与会话恢复。
 * 默认安全策略（不可恢复为永久 500ms）：
 * - Dormant：无 session / 无注册 action / 页面 hidden / destroyed → 不发请求
 * - Active：活动窗口内最多每 1s 一次，窗口建议 5s
 * - Idle：可见空闲最多每 10s 一次
 * - Error：5 → 10 → 20 → 40 → 60s 退避，成功清零
 * - 禁止并发 pending GET；进行中只合并一次 rerun
 */

export const PENDING_POLL_DEFAULTS = {
  activeWindowMs: 5_000,
  activeIntervalMs: 1_000,
  idleIntervalMs: 10_000,
  /** 第 1..n 次连续失败后的等待间隔（ms），封顶取最后一项 */
  backoffMs: [5_000, 10_000, 20_000, 40_000, 60_000] as readonly number[],
} as const

export type PendingPollTimings = {
  activeWindowMs: number
  activeIntervalMs: number
  idleIntervalMs: number
  backoffMs: readonly number[]
}

export interface PendingPageActionPollSchedulerDeps {
  getSessionId: () => string | null | undefined
  hasRegisteredActions: () => boolean
  isDestroyed: () => boolean
  /** 默认读 document.visibilityState */
  isDocumentHidden?: () => boolean
  poll: () => Promise<void>
  onError?: (error: unknown) => void
  now?: () => number
  setTimeoutFn?: (handler: () => void, delayMs: number) => number
  clearTimeoutFn?: (id: number) => void
  /** 返回取消函数；默认挂 document.visibilitychange */
  addVisibilityListener?: (listener: () => void) => () => void
  timings?: Partial<PendingPollTimings>
}

export interface PendingPageActionPollScheduler {
  /** 进入/刷新活跃补偿窗口，并合并一次立即查询意图 */
  notifyActivity(): void
  /** 注册 action 数量变化：0→N 有 session 时立即补偿；N→0 停止 */
  notifyActionsChanged(previousCount: number, nextCount: number): void
  destroy(): void
  /** 测试辅助：当前是否有 in-flight poll */
  isInFlight(): boolean
}

export function createPendingPageActionPollScheduler(
  deps: PendingPageActionPollSchedulerDeps,
): PendingPageActionPollScheduler {
  const timings: PendingPollTimings = {
    activeWindowMs: deps.timings?.activeWindowMs ?? PENDING_POLL_DEFAULTS.activeWindowMs,
    activeIntervalMs: deps.timings?.activeIntervalMs ?? PENDING_POLL_DEFAULTS.activeIntervalMs,
    idleIntervalMs: deps.timings?.idleIntervalMs ?? PENDING_POLL_DEFAULTS.idleIntervalMs,
    backoffMs: deps.timings?.backoffMs ?? PENDING_POLL_DEFAULTS.backoffMs,
  }

  const now = deps.now || (() => Date.now())
  const setTimeoutFn = deps.setTimeoutFn
    || ((handler: () => void, delayMs: number) => window.setTimeout(handler, delayMs) as unknown as number)
  const clearTimeoutFn = deps.clearTimeoutFn
    || ((id: number) => window.clearTimeout(id))
  const isDocumentHidden = deps.isDocumentHidden || (() => {
    if (typeof document === 'undefined') return false
    return document.visibilityState === 'hidden'
  })

  let destroyed = false
  let timerId: number | undefined
  let inFlight = false
  let rerunNeeded = false
  let immediateWanted = false
  let activeUntilMs = 0
  let lastPollStartedAt = 0
  let consecutiveErrors = 0
  let backoffUntilMs = 0
  let removeVisibilityListener: (() => void) | undefined

  function canPoll(): boolean {
    if (destroyed || deps.isDestroyed()) return false
    if (!deps.getSessionId()) return false
    if (!deps.hasRegisteredActions()) return false
    if (isDocumentHidden()) return false
    return true
  }

  function clearTimer() {
    if (timerId !== undefined) {
      clearTimeoutFn(timerId)
      timerId = undefined
    }
  }

  function backoffDelayMs(): number {
    if (consecutiveErrors <= 0) return 0
    const idx = Math.min(consecutiveErrors - 1, timings.backoffMs.length - 1)
    return timings.backoffMs[idx]
  }

  function computeDelayMs(at: number): number {
    if (backoffUntilMs > at) {
      return backoffUntilMs - at
    }
    if (immediateWanted) {
      if (lastPollStartedAt > 0) {
        const since = at - lastPollStartedAt
        if (since < timings.activeIntervalMs) {
          return timings.activeIntervalMs - since
        }
      }
      return 0
    }
    if (at < activeUntilMs) {
      if (lastPollStartedAt > 0) {
        const since = at - lastPollStartedAt
        if (since < timings.activeIntervalMs) {
          return timings.activeIntervalMs - since
        }
      }
      return 0
    }
    if (lastPollStartedAt > 0) {
      const since = at - lastPollStartedAt
      if (since < timings.idleIntervalMs) {
        return timings.idleIntervalMs - since
      }
    }
    return timings.idleIntervalMs
  }

  function schedule() {
    clearTimer()
    if (destroyed || deps.isDestroyed()) return
    if (!canPoll()) return
    if (inFlight) {
      rerunNeeded = true
      return
    }

    const at = now()
    const delay = computeDelayMs(at)
    timerId = setTimeoutFn(() => {
      timerId = undefined
      void runPoll()
    }, Math.max(0, delay))
  }

  async function runPoll() {
    if (destroyed || deps.isDestroyed()) return
    if (!canPoll()) return
    if (inFlight) {
      rerunNeeded = true
      return
    }

    inFlight = true
    immediateWanted = false
    lastPollStartedAt = now()
    try {
      await deps.poll()
      consecutiveErrors = 0
      backoffUntilMs = 0
    } catch (error) {
      consecutiveErrors += 1
      const wait = backoffDelayMs()
      backoffUntilMs = now() + wait
      deps.onError?.(error)
    } finally {
      inFlight = false
      if (rerunNeeded) {
        rerunNeeded = false
        immediateWanted = true
      }
      schedule()
    }
  }

  function notifyActivity() {
    if (destroyed || deps.isDestroyed()) return
    const at = now()
    activeUntilMs = Math.max(activeUntilMs, at + timings.activeWindowMs)
    immediateWanted = true
    if (inFlight) {
      rerunNeeded = true
      return
    }
    schedule()
  }

  function notifyActionsChanged(previousCount: number, nextCount: number) {
    if (destroyed || deps.isDestroyed()) return
    if (previousCount <= 0 && nextCount > 0) {
      if (deps.getSessionId()) {
        notifyActivity()
      }
      return
    }
    if (previousCount > 0 && nextCount <= 0) {
      clearTimer()
      immediateWanted = false
      rerunNeeded = false
    }
  }

  function onVisibilityChange() {
    if (destroyed || deps.isDestroyed()) return
    if (isDocumentHidden()) {
      clearTimer()
      return
    }
    notifyActivity()
  }

  const addVisibilityListener = deps.addVisibilityListener || ((listener: () => void) => {
    if (typeof document === 'undefined') return () => {}
    document.addEventListener('visibilitychange', listener)
    return () => document.removeEventListener('visibilitychange', listener)
  })
  removeVisibilityListener = addVisibilityListener(onVisibilityChange)

  return {
    notifyActivity,
    notifyActionsChanged,
    destroy() {
      destroyed = true
      clearTimer()
      immediateWanted = false
      rerunNeeded = false
      removeVisibilityListener?.()
      removeVisibilityListener = undefined
    },
    isInFlight() {
      return inFlight
    },
  }
}
