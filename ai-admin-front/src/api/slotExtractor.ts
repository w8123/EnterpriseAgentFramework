import { agentRequest } from './request'
import type {
  FieldExtractorBindingRow,
  SlotDeptRow,
  SlotExtractLogRow,
  SlotExtractorInfo,
  SlotExtractorMetric,
  SlotExtractorTestRequest,
  SlotExtractorTestResponse,
  SlotUserRow,
} from '@/types/slotExtractor'

export interface PageResult<T> {
  records: T[]
  total: number
  size: number
  current: number
}

// ===== 提取器 =====
export function listSlotExtractors() {
  return agentRequest.get<SlotExtractorInfo[]>('/api/slot-extractors')
}

export function testSlotExtractor(body: SlotExtractorTestRequest) {
  return agentRequest.post<SlotExtractorTestResponse>('/api/slot-extractors/test', body)
}

export function getSlotExtractorMetrics(days = 7) {
  return agentRequest.get<SlotExtractorMetric[]>('/api/slot-extractors/metrics', { params: { days } })
}

// ===== 部门字典 =====
export function pageSlotDept(params: { current?: number; size?: number; name?: string }) {
  return agentRequest.get<PageResult<SlotDeptRow>>('/api/slot-dict/dept', { params })
}

export function listAllDept() {
  return agentRequest.get<SlotDeptRow[]>('/api/slot-dict/dept/all')
}

export function createSlotDept(body: SlotDeptRow) {
  return agentRequest.post<SlotDeptRow>('/api/slot-dict/dept', body)
}

export function updateSlotDept(id: number, body: SlotDeptRow) {
  return agentRequest.put<SlotDeptRow>(`/api/slot-dict/dept/${id}`, body)
}

export function deleteSlotDept(id: number) {
  return agentRequest.delete(`/api/slot-dict/dept/${id}`)
}

export function importSlotDept(file: File) {
  const fd = new FormData()
  fd.append('file', file)
  return agentRequest.post<{ ok: number; skip: number }>('/api/slot-dict/dept/import', fd, {
    headers: { 'Content-Type': 'multipart/form-data' },
  })
}

// ===== 人员字典 =====
export function pageSlotUser(params: {
  current?: number
  size?: number
  name?: string
  deptId?: number
}) {
  return agentRequest.get<PageResult<SlotUserRow>>('/api/slot-dict/user', { params })
}

export function createSlotUser(body: SlotUserRow) {
  return agentRequest.post<SlotUserRow>('/api/slot-dict/user', body)
}

export function updateSlotUser(id: number, body: SlotUserRow) {
  return agentRequest.put<SlotUserRow>(`/api/slot-dict/user/${id}`, body)
}

export function deleteSlotUser(id: number) {
  return agentRequest.delete(`/api/slot-dict/user/${id}`)
}

export function importSlotUser(file: File) {
  const fd = new FormData()
  fd.append('file', file)
  return agentRequest.post<{ ok: number; skip: number }>('/api/slot-dict/user/import', fd, {
    headers: { 'Content-Type': 'multipart/form-data' },
  })
}

// ===== 调用日志 / 绑定 =====
export function pageSlotExtractLogs(params: {
  current?: number
  size?: number
  extractorName?: string
  skillName?: string
  hit?: boolean
  days?: number
}) {
  return agentRequest.get<PageResult<SlotExtractLogRow>>('/api/slot-extract-logs', { params })
}

export function listFieldBindings(skillName?: string) {
  return agentRequest.get<FieldExtractorBindingRow[]>('/api/slot-bindings', {
    params: skillName ? { skillName } : {},
  })
}

export function upsertFieldBinding(body: {
  skillName: string
  fieldKey: string
  extractorNames: string[]
}) {
  return agentRequest.post<{ ok: boolean }>('/api/slot-bindings', body)
}
