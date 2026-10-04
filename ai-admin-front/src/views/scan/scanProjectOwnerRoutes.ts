import type { ScanProject } from '@/types/scanProject'

/** Source rows are not execution assets. Only carry the verified owning project. */
export function scanProjectOwnerRoutes(project: Pick<ScanProject, 'id' | 'projectCode'> | null) {
  if (!project || !Number.isSafeInteger(project.id) || project.id <= 0) return null
  const projectCode = project.projectCode?.trim()
  const query = { projectId: String(project.id), ...(projectCode ? { projectCode } : {}) }
  return {
    businessMethods: { path: '/business-methods', query },
    apis: { path: '/apis', query },
  }
}
