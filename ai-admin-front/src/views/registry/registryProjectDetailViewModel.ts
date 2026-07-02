import type { ProjectInstance } from '@/types/registry'
import type { ScanProjectUpsertRequest } from '@/types/scanProject'

export interface ProjectInstanceRuntimeMeta {
  runtimePlacement?: string
  runtimeTypes?: string | string[]
  supportsTools?: boolean
  supportsGraph?: boolean
  supportsAutonomous?: boolean
  supportsWorkflow?: boolean
  supportsEmbeddedExecution?: boolean
  supportsHybridExecution?: boolean
}

export function isSdkBackedProjectKind(kind: string): boolean {
  return kind === 'REGISTERED' || kind === 'HYBRID'
}

export function formatHeartbeatDisplay(value?: string | null): string {
  if (!value) return '-'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${date.getFullYear()}/${pad(date.getMonth() + 1)}/${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
}

export function emptyRegistryProjectEditForm(): ScanProjectUpsertRequest {
  return {
    name: '',
    projectCode: '',
    projectKind: 'REGISTERED',
    environment: 'dev',
    owner: '',
    visibility: 'PRIVATE',
    baseUrl: '',
    contextPath: '',
    scanPath: '',
    scanType: 'openapi',
    specFile: '',
  }
}

export function parseProjectInstanceRuntimeMeta(metadataJson?: string | null): ProjectInstanceRuntimeMeta {
  if (!metadataJson) return {}
  try {
    const parsed: unknown = JSON.parse(metadataJson)
    if (typeof parsed === 'object' && parsed !== null && !Array.isArray(parsed)) {
      return parsed as ProjectInstanceRuntimeMeta
    }
    return {}
  } catch {
    return {}
  }
}

export function projectInstanceRuntimeMeta(instance: ProjectInstance): ProjectInstanceRuntimeMeta {
  return parseProjectInstanceRuntimeMeta(instance.metadataJson)
}

export function runtimePlacement(instance: ProjectInstance): string {
  return projectInstanceRuntimeMeta(instance).runtimePlacement || 'EMBEDDED'
}

export function runtimeTypes(instance: ProjectInstance): string[] {
  const raw = projectInstanceRuntimeMeta(instance).runtimeTypes
  if (Array.isArray(raw)) return raw.filter(Boolean).map(String)
  return raw ? [String(raw)] : ['SPRING_BOOT_EMBEDDED']
}

export function countOfflineInstances(instances: ProjectInstance[]): number {
  return instances.filter((item) => item.status === 'OFFLINE' || item.status === 'STALE').length
}
