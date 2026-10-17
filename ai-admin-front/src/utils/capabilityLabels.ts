const SIDE_EFFECT_LABELS: Record<string, string> = {
  READ: '只读',
  READ_ONLY: '只读',
  IDEMPOTENT_WRITE: '幂等写入',
  WRITE: '写入',
  IRREVERSIBLE: '不可逆操作',
  NONE: '无副作用',
}

export function formatSideEffectLabel(sideEffect?: string | null): string {
  if (sideEffect == null || sideEffect === '') return '-'
  return SIDE_EFFECT_LABELS[sideEffect.toUpperCase()] ?? sideEffect
}
