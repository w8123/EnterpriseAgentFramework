import type { ScanProject } from '@/types/scanProject'
import type { LocationQueryRaw } from 'vue-router'
import { BUSINESS_METHOD_CATALOG_PATH, HTTP_API_CATALOG_PATH, type BusinessCapabilityCategory } from '@/views/capability/businessCapabilityRoutes'

/** Source rows are not execution assets. Only carry the verified owning project. */
export function sourceOwnerRoutes(project: Pick<ScanProject, 'id' | 'projectCode'> | null) {
  if (!project || !Number.isSafeInteger(project.id) || project.id <= 0) return null
  const projectCode = project.projectCode?.trim()
  const query = { projectId: String(project.id), ...(projectCode ? { projectCode } : {}) }
  return {
    businessMethods: { path: BUSINESS_METHOD_CATALOG_PATH, query },
    apis: { path: HTTP_API_CATALOG_PATH, query },
  }
}

export function sourceGroupMembersLocation(projectId: number, category: BusinessCapabilityCategory,
  group: { id: number; projectId: number }, query: LocationQueryRaw) {
  if (!Number.isSafeInteger(projectId) || projectId <= 0 || group.projectId !== projectId || !Number.isSafeInteger(group.id) || group.id <= 0) return null
  return { path: category === 'http-apis' ? HTTP_API_CATALOG_PATH : BUSINESS_METHOD_CATALOG_PATH,
    query: { ...query, projectId: String(projectId),
      [category === 'http-apis' ? 'apiGroupId' : 'methodGroupId']: String(group.id),
      [category === 'http-apis' ? 'apiPage' : 'methodPage']: '1',
      subjectType: undefined, subjectId: undefined, subjectKey: undefined } }
}
