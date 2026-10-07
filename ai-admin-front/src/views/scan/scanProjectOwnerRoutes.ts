import type { ScanProject } from '@/types/scanProject'
import { BUSINESS_METHOD_CATALOG_PATH, HTTP_API_CATALOG_PATH } from '@/views/capability/businessCapabilityRoutes'

/** Source rows are not execution assets. Only carry the verified owning project. */
export function scanProjectOwnerRoutes(project: Pick<ScanProject, 'id' | 'projectCode'> | null) {
  if (!project || !Number.isSafeInteger(project.id) || project.id <= 0) return null
  const projectCode = project.projectCode?.trim()
  const query = { projectId: String(project.id), ...(projectCode ? { projectCode } : {}) }
  return {
    businessMethods: { path: BUSINESS_METHOD_CATALOG_PATH, query },
    apis: { path: HTTP_API_CATALOG_PATH, query },
  }
}
