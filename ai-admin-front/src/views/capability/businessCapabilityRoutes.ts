import type { LocationQuery, LocationQueryRaw } from 'vue-router'

export const BUSINESS_CAPABILITY_ROOT = '/business-capabilities'
export const BUSINESS_METHOD_CATALOG_PATH = `${BUSINESS_CAPABILITY_ROOT}/java-methods`
export const HTTP_API_CATALOG_PATH = `${BUSINESS_CAPABILITY_ROOT}/http-apis`
// These identities belong to one project; keyword and other reusable filters do not.
export const BUSINESS_CAPABILITY_SCOPE_QUERY_KEYS = ['methodProjectId', 'methodGroupId', 'apiGroupId', 'methodPage', 'apiPage',
  'subjectType', 'subjectId', 'subjectKey', 'methodDetail', 'methodAssetId', 'classificationId', 'classificationAction']

export type BusinessCapabilityCategory = 'java-methods' | 'http-apis'
export type BusinessCapabilityWorkspace = 'sources' | 'groups' | 'semantics' | 'graph'

/** A detail link can supply fresh filters while changing to its verified owning project. */
export function catalogQueryMatchesProject(query: LocationQuery, projectId: number | null, projectCode?: string) {
  return !!projectId && query.scope !== 'all' && query.projectId === String(projectId)
    && (query.projectCode === undefined || !!projectCode && query.projectCode === projectCode)
}

export function businessCapabilityWorkspaceLocation(category: BusinessCapabilityCategory, workspace: BusinessCapabilityWorkspace,
  query: LocationQueryRaw = {}) {
  if (workspace === 'groups') {
    const next = { ...query }
    const subjectId = Number(next.subjectId)
    if (next.subjectType === 'GROUP' && Number.isSafeInteger(subjectId) && subjectId > 0) next.classificationId = String(subjectId)
    delete next.subjectType
    delete next.subjectId
    delete next.subjectKey
    return { path: `${BUSINESS_CAPABILITY_ROOT}/${category}`, query: next }
  }
  return { path: `${BUSINESS_CAPABILITY_ROOT}/${category}/${workspace}`, query }
}

export function projectCapabilityWorkspaceLocation(project: { id: number; projectCode?: string | null },
  workspace: BusinessCapabilityWorkspace = 'sources', category: BusinessCapabilityCategory = 'java-methods') {
  return businessCapabilityWorkspaceLocation(category, workspace, {
    projectId: String(project.id), ...(project.projectCode?.trim() ? { projectCode: project.projectCode.trim() } : {}),
  })
}

export function httpApiDetailLocation(id: number, query: LocationQueryRaw = {}) {
  return { path: `${HTTP_API_CATALOG_PATH}/${id}`, query }
}

/** Preserve bookmarked filters while handing their ownership to the selected tab. */
export function legacyCatalogQuery(query: LocationQuery, namespace: 'method' | 'api') {
  const next: LocationQueryRaw = { ...query }
  const fields = namespace === 'method' ? ['keyword', 'enabled', 'page', 'size']
    : ['keyword', 'environment', 'method', 'sourceStatus', 'page', 'size']
  for (const field of fields) {
    if (query[field] !== undefined) next[`${namespace}${field.charAt(0).toUpperCase()}${field.slice(1)}`] = query[field]
    delete next[field]
  }
  return next
}
