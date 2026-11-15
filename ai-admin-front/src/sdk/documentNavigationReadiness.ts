import type { PageActionResultDelivered } from './embedPageActions'

/** Only a claimed, confirmed navigation and its own persisted waiting turn may trigger an MPA handoff. */
export function createDocumentNavigationReadiness(
  ready: (value: { sessionId: string; requestId: string; traceId: string; turnId: string }) => void,
  cancelled?: (value: { sessionId: string; requestId: string; traceId: string; turnId: string }) => void,
) {
  const claims = new Map<string, { sessionId: string; traceId: string; delivered: boolean }>()
  const cancellations = new Map<string, string>()
  const waiting = new Map<string, { sessionId: string; traceId: string; turnId: string }>()
  const active = new Map<string, string>()
  const emitted = new Set<string>()
  const trim = (map: Map<string, unknown> | Set<string>) => {
    while (map.size > 256) map.delete(map.keys().next().value as string)
  }
  const attempt = (requestId: string) => {
    const claim = claims.get(requestId)
    const turn = waiting.get(requestId)
    if (turn && cancellations.get(requestId) === turn.sessionId && !emitted.has(requestId)
        && active.get(turn.sessionId) === turn.turnId) {
      emitted.add(requestId)
      trim(emitted)
      claims.delete(requestId)
      cancellations.delete(requestId)
      waiting.delete(requestId)
      cancelled?.({ requestId, ...turn })
      return
    }
    if (!claim?.delivered || !turn || emitted.has(requestId)
        || claim.sessionId !== turn.sessionId || claim.traceId !== turn.traceId
        || active.get(turn.sessionId) !== turn.turnId) return
    emitted.add(requestId)
    trim(emitted)
    claims.delete(requestId)
    waiting.delete(requestId)
    ready({ requestId, ...turn })
  }
  return {
    preparing(sessionId?: string) { if (sessionId) active.delete(sessionId) },
    started(sessionId?: string, turnId?: string) {
      if (sessionId && turnId) { active.set(sessionId, turnId); trim(active) }
    },
    isCurrent(sessionId: string, turnId: string) { return active.get(sessionId) === turnId },
    claimed(sessionId: string | undefined, requestId: string, actionKey: string, traceId: string) {
      if (!sessionId || !requestId || actionKey !== '__reachai.navigate' || !/^[a-f0-9]{32}$/i.test(traceId)
          || emitted.has(requestId)) return
      const old = claims.get(requestId)
      if (old && (old.sessionId !== sessionId || old.traceId !== traceId)) return
      if (!old) claims.set(requestId, { sessionId, traceId, delivered: false })
      trim(claims)
      attempt(requestId)
    },
    delivered(sessionId: string | undefined, result: PageActionResultDelivered) {
      if (sessionId && result.actionKey === '__reachai.navigate' && result.status === 'USER_CANCELLED'
          && result.userConfirmed !== true && !emitted.has(result.requestId)) {
        cancellations.set(result.requestId, sessionId)
        trim(cancellations)
        attempt(result.requestId)
        return
      }
      const claim = claims.get(result.requestId)
      if (!claim || claim.sessionId !== sessionId || result.actionKey !== '__reachai.navigate'
          || result.status !== 'SUCCESS' || result.userConfirmed !== true) return
      claim.delivered = true
      attempt(result.requestId)
    },
    waiting(sessionId: string | undefined, turnId: string | undefined, response: {
      metadata?: Record<string, unknown>; uiRequest?: unknown
    }) {
      const metadata = response.metadata
      const ui = response.uiRequest && typeof response.uiRequest === 'object'
        ? response.uiRequest as Record<string, unknown> : undefined
      const extension = ui?.extension && typeof ui.extension === 'object'
        ? ui.extension as Record<string, unknown> : undefined
      const navigation = extension?.documentNavigation && typeof extension.documentNavigation === 'object'
        ? extension.documentNavigation as Record<string, unknown> : undefined
      const requestId = navigation?.navigationRequestId
      const traceId = metadata?.traceId
      if (!sessionId || !turnId || active.get(sessionId) !== turnId
          || metadata?.code !== 'RUNTIME_GRAPH_INTERACTION_WAITING'
          || navigation?.version !== 1
          || typeof requestId !== 'string' || !requestId || typeof traceId !== 'string'
          || !/^[a-f0-9]{32}$/i.test(traceId) || typeof ui?.interactionId !== 'string'
          || !/^wfi_[a-f0-9]{32}$/.test(ui.interactionId) || emitted.has(requestId)) return
      waiting.set(requestId, { sessionId, traceId, turnId })
      trim(waiting)
      attempt(requestId)
    },
    clear() { claims.clear(); cancellations.clear(); waiting.clear(); active.clear(); emitted.clear() },
  }
}
