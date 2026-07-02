export function normalizeJson(value: string, label: string) {
  try {
    return JSON.stringify(JSON.parse(value))
  } catch {
    throw new Error(`${label} is not valid JSON`)
  }
}

export function formatJson(value: string) {
  try {
    return JSON.stringify(JSON.parse(value), null, 2)
  } catch {
    return value
  }
}

export function readJsonObject(value: string, fallback: Record<string, unknown>) {
  try {
    const parsed = JSON.parse(value)
    return parsed && typeof parsed === 'object' && !Array.isArray(parsed)
      ? (parsed as Record<string, unknown>)
      : fallback
  } catch {
    return fallback
  }
}
