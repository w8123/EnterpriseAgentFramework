import { controlRequest } from './request'
import { isBusinessMethodInfo, type BusinessMethodInfo, type BusinessMethodListQuery, type BusinessMethodPageResult, type BusinessMethodCatalogSummary } from '@/types/businessMethod'
import type { AssetReferences } from '@/types/assetReferences'

export function getBusinessMethods(params?: BusinessMethodListQuery) {
  return controlRequest.get<BusinessMethodPageResult>('/api/business-methods', { params, errorFeedback: 'local' })
}

export function getBusinessMethodSummary(projectId?: number) {
  return controlRequest.get<BusinessMethodCatalogSummary>('/api/business-methods/summary', { params: { projectId }, errorFeedback: 'local' })
}

export function getBusinessMethod(name: string) {
  return controlRequest.get<BusinessMethodInfo>(`/api/business-methods/${encodeURIComponent(name)}`)
}

export function getBusinessMethodReferences(projectCode: string, name: string) {
  return controlRequest.get<AssetReferences>(`/api/capability-review/projects/${encodeURIComponent(projectCode)}/capabilities/${encodeURIComponent(name)}/references`)
}


/** Authoring requires the complete accepted catalog for one explicit project. */
export async function listAllBusinessMethods(projectId: number): Promise<BusinessMethodInfo[]> {
  if (!Number.isSafeInteger(projectId) || projectId <= 0) throw new Error('请先关联 Workflow 所属项目。')
  const records: BusinessMethodInfo[] = []
  const identities = new Set<number>()
  let expectedTotal: number | null = null
  for (let current = 1; current <= 100; current++) {
    const { data } = await getBusinessMethods({ projectId, enabled: true, current, size: 100 })
    const pages = Number(data?.pages)
    const total = Number(data?.total)
    if (!Array.isArray(data?.records) || !Number.isSafeInteger(pages) || pages < 0
      || !Number.isSafeInteger(total) || total < 0 || data.current !== current
      || (expectedTotal !== null && total !== expectedTotal)) {
      throw new Error('业务方法目录响应不完整，请重新加载。')
    }
    expectedTotal = total
    for (const method of data.records) {
      if (!isBusinessMethodInfo(method) || method.projectId !== projectId || identities.has(method.assetId)) {
        throw new Error('业务方法目录包含无效或重复的资产身份，请重新加载。')
      }
      identities.add(method.assetId)
      records.push(method)
    }
    if (current >= pages) {
      if (records.length !== expectedTotal) throw new Error('业务方法目录未完整加载，请重新加载。')
      return records
    }
  }
  throw new Error('业务方法目录未完整加载，请缩小项目范围后重试。')
}
