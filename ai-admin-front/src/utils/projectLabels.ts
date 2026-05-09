import type { ProjectVisibility } from '@/types/registry'
import type { ScanStatus, ScanType } from '@/types/scanProject'

const PROJECT_KIND_LABELS: Record<string, string> = {
  SCAN: '扫描接入',
  REGISTERED: 'SDK 注册',
  HYBRID: '混合接入',
}

const VISIBILITY_LABELS: Record<string, string> = {
  PRIVATE: '私有',
  PROJECT: '项目内',
  SHARED: '共享',
  PUBLIC: '公开',
}

const SCAN_STATUS_LABELS: Record<string, string> = {
  created: '已创建',
  scanning: '扫描中',
  scanned: '已扫描',
  failed: '失败',
}

const SCAN_TYPE_LABELS: Record<string, string> = {
  openapi: 'OpenAPI',
  controller: 'Controller',
  auto: '自动',
}

/** 项目形态（SCAN / REGISTERED / HYBRID）展示文案；kind 为已是后端枚举值（调用处按需默认 SCAN / REGISTERED 等） */
export function formatProjectKindLabel(kind: string): string {
  return PROJECT_KIND_LABELS[kind] ?? kind
}

/** 可见性枚举展示文案 */
export function formatVisibilityLabel(visibility: string): string {
  return VISIBILITY_LABELS[visibility] ?? visibility
}

/** 扫描状态展示文案 */
export function formatScanStatusLabel(s?: ScanStatus | string | null): string {
  if (s == null || s === '') return '-'
  return SCAN_STATUS_LABELS[String(s)] ?? String(s)
}

/** 扫描方式展示文案 */
export function formatScanTypeLabel(t?: ScanType | string | null): string {
  if (t == null || t === '') return '-'
  return SCAN_TYPE_LABELS[String(t)] ?? String(t)
}

/** 表单：可见性下拉（value 仍为后端枚举） */
export const VISIBILITY_SELECT_OPTIONS: { value: ProjectVisibility; label: string }[] = [
  { value: 'PRIVATE', label: '私有' },
  { value: 'PROJECT', label: '项目内' },
  { value: 'SHARED', label: '共享' },
  { value: 'PUBLIC', label: '公开' },
]

/** 表单：项目形态下拉 */
export const PROJECT_KIND_SELECT_OPTIONS = [
  { value: 'SCAN' as const, label: '扫描接入' },
  { value: 'REGISTERED' as const, label: 'SDK 注册' },
  { value: 'HYBRID' as const, label: '混合接入' },
]
