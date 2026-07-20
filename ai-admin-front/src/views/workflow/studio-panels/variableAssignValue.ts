export type VariableAssignValueKind = 'expression' | 'number' | 'boolean' | 'json' | 'null'

export type VariableAssignRow = {
  target: string
  valueKind: VariableAssignValueKind
  textValue: string
  numberValue: number | undefined
  boolValue: boolean
  jsonText: string
}

export type VariableAssignResolveResult =
  | { ok: true; value: unknown }
  | { ok: false; error: string }

export function resolveVariableAssignValue(row: VariableAssignRow): VariableAssignResolveResult {
  switch (row.valueKind) {
    case 'null':
      return { ok: true, value: null }
    case 'number':
      return { ok: true, value: row.numberValue ?? 0 }
    case 'boolean':
      return { ok: true, value: !!row.boolValue }
    case 'json': {
      try {
        return { ok: true, value: JSON.parse(row.jsonText || 'null') }
      } catch {
        return { ok: false, error: 'JSON 格式无效，请修正后再同步/保存' }
      }
    }
    default:
      return { ok: true, value: row.textValue ?? '' }
  }
}

export function buildAssignmentsFromRows(
  rows: VariableAssignRow[],
): { ok: true; assignments: Record<string, unknown> } | { ok: false; error: string; index: number } {
  const assignments: Record<string, unknown> = {}
  for (let i = 0; i < rows.length; i += 1) {
    const row = rows[i]
    const target = row.target?.trim()
    if (!target) continue
    const resolved = resolveVariableAssignValue(row)
    if (!resolved.ok) {
      return { ok: false, error: resolved.error, index: i }
    }
    assignments[target] = resolved.value
  }
  return { ok: true, assignments }
}
