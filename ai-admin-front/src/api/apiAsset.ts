import { controlRequest } from './request'
import type { ApiAssetPageResponse, ApiAssetQuery } from '@/types/apiAsset'

export function listApiAssets(params: ApiAssetQuery) {
  return controlRequest.get<ApiAssetPageResponse>('/api/api-assets', { params })
}
