import request from './request'
import type {
  BizIndex,
  BizIndexForm,
  BizIndexStats,
  BizSearchRequest,
  BizSearchResponse,
} from '@/types/bizIndex'
import type { ApiResult } from '@/types/import'

// ==================== 索引管理 ====================

export function getBizIndexList() {
  return request.get<ApiResult<BizIndex[]>>('/biz-index/list')
}

export function getBizIndexDetail(indexCode: string) {
  return request.get<ApiResult<BizIndex>>(`/biz-index/${indexCode}`)
}

export function createBizIndex(data: BizIndexForm) {
  return request.post<ApiResult<void>>('/biz-index', data)
}

export function updateBizIndex(indexCode: string, data: BizIndexForm) {
  return request.put<ApiResult<void>>(`/biz-index/${indexCode}`, data)
}

export function deleteBizIndex(indexCode: string) {
  return request.delete<ApiResult<void>>(`/biz-index/${indexCode}`)
}

export function getBizIndexStats(indexCode: string) {
  return request.get<ApiResult<BizIndexStats>>(`/biz-index/${indexCode}/stats`)
}

// ==================== 数据同步 ====================

export function bizIndexUpsert(
  indexCode: string,
  data: Record<string, unknown>,
  attachments?: File[],
) {
  const formData = new FormData()
  formData.append('data', new Blob([JSON.stringify(data)], { type: 'application/json' }))

  if (attachments) {
    attachments.forEach((file) => {
      formData.append('attachments', file)
    })
  }

  return request.post<ApiResult<void>>(`/biz-index/${indexCode}/upsert`, formData, {
    headers: { 'Content-Type': 'multipart/form-data' },
    timeout: 300000,
  })
}

export function bizIndexBatchUpsert(indexCode: string, items: Record<string, unknown>[]) {
  return request.post<ApiResult<void>>(`/biz-index/${indexCode}/batch`, { items }, {
    timeout: 300000,
  })
}

export function bizIndexDeleteRecord(indexCode: string, bizId: string) {
  return request.delete<ApiResult<void>>(`/biz-index/${indexCode}/record/${bizId}`)
}

export function bizIndexRebuild(indexCode: string) {
  return request.post<ApiResult<void>>(`/biz-index/${indexCode}/rebuild`, null, {
    timeout: 600000,
  })
}

// ==================== 语义搜索 ====================

export function bizIndexSearch(indexCode: string, data: BizSearchRequest) {
  return request.post<ApiResult<BizSearchResponse>>(`/biz-index/${indexCode}/search`, data, {
    timeout: 120000,
  })
}
