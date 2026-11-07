import type { PageActionResultDelivered } from './embedPageActions'

/** Correlates only server-attributed requests with a completed run from the same session. */
export function createPageActionTurnCompletion(
  onSettled: (result: PageActionResultDelivered & { traceId: string; sessionId: string; turnId: string }) => void,
) {
  const requests = new Map<string, { sessionId: string; traceId: string; actionKey: string; result?: PageActionResultDelivered }>()
  const completed = new Map<string, string>()
  const activeTurns = new Map<string, string>()
  const emitted = new Set<string>()
  const limit = 256
  const key = (sessionId: string, traceId: string) => `${sessionId}\u0000${traceId}`
  const trim = <T>(values: Set<T> | Map<T, unknown>) => {
    while (values.size > limit) values.delete(values.keys().next().value as T)
  }
  const attempt = (requestId: string) => {
    const entry = requests.get(requestId)
    const turnId = entry && completed.get(key(entry.sessionId, entry.traceId))
    if (!entry?.result || !turnId || activeTurns.get(entry.sessionId) !== turnId || emitted.has(requestId)) return
    emitted.add(requestId)
    trim(emitted)
    requests.delete(requestId)
    onSettled({ ...entry.result, traceId: entry.traceId, sessionId: entry.sessionId, turnId })
  }
  return {
    preparing(sessionId: string | undefined) {
      if (!sessionId) return
      activeTurns.delete(sessionId)
      for (const stored of completed.keys()) {
        if (stored.startsWith(`${sessionId}\u0000`)) completed.delete(stored)
      }
    },
    started(sessionId: string | undefined, turnId: string | undefined) {
      if (!sessionId || !turnId || activeTurns.get(sessionId) === turnId) return
      activeTurns.set(sessionId, turnId)
      for (const stored of completed.keys()) {
        if (stored.startsWith(`${sessionId}\u0000`)) completed.delete(stored)
      }
      trim(activeTurns)
    },
    isCurrent(sessionId: string, turnId: string) { return activeTurns.get(sessionId) === turnId },
    claimed(sessionId: string | undefined, requestId: string, actionKey: string, traceId: string | undefined) {
      if (!sessionId || !requestId || !actionKey || !traceId
          || !/^[a-f0-9]{32}$/i.test(traceId) || emitted.has(requestId)) return
      const old = requests.get(requestId)
      if (old && (old.sessionId !== sessionId || old.traceId !== traceId || old.actionKey !== actionKey)) return
      if (!old) requests.set(requestId, { sessionId, traceId, actionKey })
      trim(requests)
      attempt(requestId)
    },
    delivered(sessionId: string | undefined, result: PageActionResultDelivered) {
      const entry = requests.get(result.requestId)
      if (!entry || entry.sessionId !== sessionId || entry.actionKey !== result.actionKey || emitted.has(result.requestId)) return
      entry.result = result
      attempt(result.requestId)
    },
    completed(sessionId: string | undefined, traceId: unknown, turnId: string | undefined) {
      if (!sessionId || !turnId || activeTurns.get(sessionId) !== turnId
          || typeof traceId !== 'string' || !/^[a-f0-9]{32}$/i.test(traceId)) return
      completed.set(key(sessionId, traceId), turnId)
      trim(completed)
      for (const requestId of requests.keys()) attempt(requestId)
    },
    clear() { requests.clear(); completed.clear(); activeTurns.clear(); emitted.clear() },
  }
}
