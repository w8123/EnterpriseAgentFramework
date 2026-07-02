import type { ScanProject } from '@/types/scanProject'

export type ScanProjectStatusTagType = 'success' | 'danger' | 'warning' | 'info'

export function scanProjectStatusTagType(status: ScanProject['status']): ScanProjectStatusTagType {
  if (status === 'scanned') return 'success'
  if (status === 'failed') return 'danger'
  if (status === 'scanning') return 'warning'
  return 'info'
}
