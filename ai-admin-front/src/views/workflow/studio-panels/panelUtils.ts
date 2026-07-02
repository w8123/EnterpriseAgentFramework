import type { CanvasNodeData, StudioFieldSchema } from '@/types/studio'

export function formatMap(mapping?: Record<string, string>) {
  if (!mapping || Object.keys(mapping).length === 0) return ''
  return Object.entries(mapping).map(([key, value]) => `${key} = ${value}`).join('\n')
}

export function parseMap(text: string): Record<string, string> {
  const out: Record<string, string> = {}
  for (const rawLine of (text || '').split('\n')) {
    const line = rawLine.trim()
    if (!line || line.startsWith('#')) continue
    const idx = line.indexOf('=')
    if (idx <= 0) continue
    const key = line.slice(0, idx).trim()
    const value = line.slice(idx + 1).trim()
    if (key && value) out[key] = value
  }
  return out
}

export function ensureFieldList(data: CanvasNodeData) {
  if (!data.parameterConfig) return []
  data.parameterConfig.fields ||= []
  return data.parameterConfig.fields
}

export function addField(fields: StudioFieldSchema[]) {
  fields.push({
    name: `field_${fields.length + 1}`,
    type: 'string',
    required: false,
    source: 'lastOutput',
  })
}
