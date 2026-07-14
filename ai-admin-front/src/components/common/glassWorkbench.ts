export type WorkbenchDensity = 'compact' | 'comfortable' | 'spacious'
export type StatusTone = 'success' | 'warning' | 'danger' | 'info' | 'neutral'
export type MetricTone = StatusTone | 'brand'
export type WizardStepState = 'pending' | 'current' | 'complete' | 'error' | 'skipped'

export interface MetricStripItem {
  key: string
  label: string
  value: string | number
  hint?: string
  tone?: MetricTone
  iconKey?: string
}

export interface WizardStep {
  key: string
  title: string
  description?: string
  state: WizardStepState
  disabled?: boolean
}

export function densityClass(density: WorkbenchDensity): `density-${WorkbenchDensity}` {
  return `density-${density}`
}
