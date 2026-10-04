/** Query handles only. Shared by Java-method and HTTP Console flows; never persist inputs/results. */
export interface ConsoleInvocationReference { invocationId: string; createdAt: number }
const ID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
export const MAX_RECENT_REFERENCES = 5

export function safeStorageGet(key: string) {
  try { return globalThis.localStorage?.getItem(key) || '' } catch { return '' }
}
export function safeStorageSet(key: string, value: string) {
  try { globalThis.localStorage?.setItem(key, value) } catch { /* Query references are optional convenience. */ }
}
export function removeInvocationReferences(key: string) {
  try { globalThis.localStorage?.removeItem(key) } catch { /* Storage may be unavailable. */ }
}
export function normalizedReferences(value: unknown): ConsoleInvocationReference[] {
  if (!Array.isArray(value)) return []
  const seen = new Set<string>()
  return value.flatMap((entry) => {
    if (!entry || typeof entry !== 'object') return []
    const candidate = entry as Partial<ConsoleInvocationReference>
    if (typeof candidate.invocationId !== 'string' || !ID.test(candidate.invocationId)
      || typeof candidate.createdAt !== 'number' || !Number.isFinite(candidate.createdAt)
      || seen.has(candidate.invocationId)) return []
    seen.add(candidate.invocationId)
    return [{ invocationId: candidate.invocationId, createdAt: candidate.createdAt }]
  }).slice(0, MAX_RECENT_REFERENCES)
}
export function readInvocationReferences(key: string) {
  try { return normalizedReferences(JSON.parse(safeStorageGet(key))) } catch { return [] }
}
export function rememberInvocationReference(key: string, invocationId: string) {
  if (!key || !ID.test(invocationId)) return
  safeStorageSet(key, JSON.stringify(normalizedReferences([
    { invocationId, createdAt: Date.now() },
    ...readInvocationReferences(key).filter(entry => entry.invocationId !== invocationId),
  ])))
}
