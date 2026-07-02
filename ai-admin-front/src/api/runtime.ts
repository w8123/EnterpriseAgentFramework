import { controlRequest } from './request'
import type { RuntimeRegistryEntry } from '@/types/agent'

export function listRuntimeRegistry() {
  return controlRequest.get<RuntimeRegistryEntry[]>('/api/runtimes')
}
