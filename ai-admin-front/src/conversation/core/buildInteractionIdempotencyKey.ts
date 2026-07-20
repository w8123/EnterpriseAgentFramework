/**
 * Stable attempt keys for interaction submit.
 * - Embed: generate once per user click; reuse across stream/JSON fallback retries.
 * - Debug: derive from interactionId + canonical action/values so same payload replays.
 */

function stableSerialize(value: unknown): string {
  if (value === null || value === undefined) return ''
  if (typeof value !== 'object') return JSON.stringify(value)
  if (Array.isArray(value)) {
    return `[${value.map((item) => stableSerialize(item)).join(',')}]`
  }
  const entries = Object.entries(value as Record<string, unknown>)
    .filter(([, v]) => v !== undefined)
    .sort(([a], [b]) => a.localeCompare(b))
  return `{${entries.map(([k, v]) => `${JSON.stringify(k)}:${stableSerialize(v)}`).join(',')}}`
}

function shortHash(input: string): string {
  let h = 2166136261
  for (let i = 0; i < input.length; i += 1) {
    h ^= input.charCodeAt(i)
    h = Math.imul(h, 16777619)
  }
  return (h >>> 0).toString(36)
}

/** One-shot attempt key for Embed / Agent resume (reuse on network/stream fallback). */
export function createAttemptIdempotencyKey(prefix: string, interactionId: string): string {
  const id = typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function'
    ? crypto.randomUUID().replace(/-/g, '')
    : `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 10)}`
  return `${prefix}-${interactionId}-${id}`
}

/** Deterministic Debug key from interactionId + canonical payload. */
export function buildDebugInteractionIdempotencyKey(
  interactionId: string,
  action: string,
  values: Record<string, unknown> | undefined,
  nodeId?: string,
): string {
  const canonical = stableSerialize({
    action: action || 'submit',
    values: values || {},
    interactionId: interactionId || '',
    nodeId: nodeId || '',
  })
  return `wf-debug-${interactionId}-${shortHash(canonical)}`
}
