import { controlRequest } from './request'
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

// ===== 鎻愬彇鍣?=====
export function listSlotExtractors() {
  return controlRequest.get<SlotExtractorInfo[]>('/api/slot-extractors')
}

export function testSlotExtractor(body: SlotExtractorTestRequest) {
  return controlRequest.post<SlotExtractorTestResponse>('/api/slot-extractors/test', body)
}

export function getSlotExtractorMetrics(days = 7) {
  return controlRequest.get<SlotExtractorMetric[]>('/api/slot-extractors/metrics', { params: { days } })
}

// ===== 閮ㄩ棬瀛楀吀 =====
export function pageSlotDept(params: { current?: number; size?: number; name?: string }) {
  return controlRequest.get<PageResult<SlotDeptRow>>('/api/slot-dict/dept', { params })
}

export function listAllDept() {
  return controlRequest.get<SlotDeptRow[]>('/api/slot-dict/dept/all')
}

export function createSlotDept(body: SlotDeptRow) {
  return controlRequest.post<SlotDeptRow>('/api/slot-dict/dept', body)
}

export function updateSlotDept(id: number, body: SlotDeptRow) {
  return controlRequest.put<SlotDeptRow>(`/api/slot-dict/dept/${id}`, body)
}

export function deleteSlotDept(id: number) {
  return controlRequest.delete(`/api/slot-dict/dept/${id}`)
}

export function importSlotDept(file: File) {
  const fd = new FormData()
  fd.append('file', file)
  return controlRequest.post<{ ok: number; skip: number }>('/api/slot-dict/dept/import', fd, {
    headers: { 'Content-Type': 'multipart/form-data' },
  })
}

// ===== 浜哄憳瀛楀吀 =====
export function pageSlotUser(params: {
  current?: number
  size?: number
  name?: string
  deptId?: number
}) {
  return controlRequest.get<PageResult<SlotUserRow>>('/api/slot-dict/user', { params })
}

export function createSlotUser(body: SlotUserRow) {
  return controlRequest.post<SlotUserRow>('/api/slot-dict/user', body)
}

export function updateSlotUser(id: number, body: SlotUserRow) {
  return controlRequest.put<SlotUserRow>(`/api/slot-dict/user/${id}`, body)
}

export function deleteSlotUser(id: number) {
  return controlRequest.delete(`/api/slot-dict/user/${id}`)
}

export function importSlotUser(file: File) {
  const fd = new FormData()
  fd.append('file', file)
  return controlRequest.post<{ ok: number; skip: number }>('/api/slot-dict/user/import', fd, {
    headers: { 'Content-Type': 'multipart/form-data' },
  })
}

// ===== 璋冪敤鏃ュ織 / 缁戝畾 =====
export function pageSlotExtractLogs(params: {
  current?: number
  size?: number
  extractorName?: string
  skillName?: string
  hit?: boolean
  days?: number
}) {
  return controlRequest.get<PageResult<SlotExtractLogRow>>('/api/slot-extract-logs', { params })
}

export function listFieldBindings(skillName?: string) {
  return controlRequest.get<FieldExtractorBindingRow[]>('/api/slot-bindings', {
    params: skillName ? { skillName } : {},
  })
}

export function upsertFieldBinding(body: {
  skillName: string
  fieldKey: string
  extractorNames: string[]
}) {
  return controlRequest.post<{ ok: boolean }>('/api/slot-bindings', body)
}
