export function normalizeBusinessPageUrl(value?: string | null) {
  const candidate = value?.trim()
  if (!candidate) return ''
  try {
    const url = new URL(candidate)
    if (url.protocol !== 'http:' && url.protocol !== 'https:') return ''
    return url.hostname ? url.toString() : ''
  } catch {
    return ''
  }
}

export function isBusinessPageUrl(value?: string | null) {
  return Boolean(normalizeBusinessPageUrl(value))
}
