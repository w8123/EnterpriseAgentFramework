import type { ScanProject } from '@/types/scanProject'

export type ProjectScopeCatalogStatus = 'idle' | 'loading' | 'ready' | 'error'

export type ProjectScopeCatalogProject = Pick<ScanProject, 'id' | 'name' | 'projectCode'>

export interface ProjectScopeProjectIdentity {
  id: number
  name: string
  projectCode: string | null
}

export interface ProjectScopeRequestIdentity {
  projectId?: number
  projectCode?: string
}

export type ProjectScopeQueryInvalidReason =
  | 'invalid-scope'
  | 'scope-conflicts-with-project'
  | 'invalid-project-id'
  | 'invalid-project-code'

export type ProjectScopeQueryReference =
  | { kind: 'unspecified' }
  | { kind: 'all' }
  | { kind: 'project'; projectId?: number; projectCode?: string }
  | { kind: 'invalid'; reason: ProjectScopeQueryInvalidReason }

export type ProjectScopeBlockedReason =
  | 'invalid-reference'
  | 'all-forbidden'
  | 'project-not-found'
  | 'ambiguous-project'
  | 'project-forbidden'
  | 'project-code-missing'
  | 'catalog-error'
  | 'project-required'
  | 'no-access'

export type ResolvedProjectScope =
  | { kind: 'all' }
  | { kind: 'project'; project: ProjectScopeProjectIdentity }
  | { kind: 'pending'; reason: 'catalog-loading' }
  | {
    kind: 'blocked'
    reason: ProjectScopeBlockedReason
    request?: ProjectScopeRequestIdentity
  }

export interface ResolveProjectScopeInput {
  reference: ProjectScopeQueryReference
  selectedProjectId: number | null
  catalog: readonly ProjectScopeCatalogProject[]
  catalogStatus: ProjectScopeCatalogStatus
  hasLoadedSuccessfully: boolean
  canReadAll: boolean
  canReadProject: (project: ProjectScopeCatalogProject) => boolean
  requiresProjectCode?: boolean
}

const QUERY_SCOPE = 'scope'
const QUERY_PROJECT_ID = 'projectId'
const QUERY_PROJECT_CODE = 'projectCode'

function hasOwn(query: Record<string, unknown>, key: string) {
  return Object.prototype.hasOwnProperty.call(query, key)
}

function parseProjectId(value: unknown): number | null {
  if (typeof value !== 'string' || !/^\d+$/.test(value)) return null
  const projectId = Number(value)
  if (!Number.isSafeInteger(projectId) || projectId <= 0) return null
  return projectId
}

function parseProjectCode(value: unknown): string | null {
  if (typeof value !== 'string') return null
  const projectCode = value.trim()
  return projectCode ? projectCode : null
}

export function parseProjectScopeQuery(query: Record<string, unknown>): ProjectScopeQueryReference {
  const hasScope = hasOwn(query, QUERY_SCOPE)
  const hasProjectId = hasOwn(query, QUERY_PROJECT_ID)
  const hasProjectCode = hasOwn(query, QUERY_PROJECT_CODE)
  const hasProjectFields = hasProjectId || hasProjectCode

  if (hasScope) {
    if (query.scope !== 'all') {
      return { kind: 'invalid', reason: 'invalid-scope' }
    }
    if (hasProjectFields) {
      return { kind: 'invalid', reason: 'scope-conflicts-with-project' }
    }
    return { kind: 'all' }
  }

  if (!hasProjectFields) return { kind: 'unspecified' }

  const reference: Extract<ProjectScopeQueryReference, { kind: 'project' }> = { kind: 'project' }
  if (hasProjectId) {
    const projectId = parseProjectId(query.projectId)
    if (projectId === null) return { kind: 'invalid', reason: 'invalid-project-id' }
    reference.projectId = projectId
  }
  if (hasProjectCode) {
    const projectCode = parseProjectCode(query.projectCode)
    if (projectCode === null) return { kind: 'invalid', reason: 'invalid-project-code' }
    reference.projectCode = projectCode
  }
  return reference
}

function normalizeProjectCode(projectCode: unknown): string {
  return typeof projectCode === 'string' ? projectCode.trim() : ''
}

function toProjectIdentity(project: ProjectScopeCatalogProject): ProjectScopeProjectIdentity {
  const projectCode = normalizeProjectCode(project.projectCode)
  return {
    id: project.id,
    name: project.name,
    projectCode: projectCode || null,
  }
}

function projectRequestFromReference(
  reference: Extract<ProjectScopeQueryReference, { kind: 'project' }>,
): ProjectScopeRequestIdentity {
  const request: ProjectScopeRequestIdentity = {}
  if (reference.projectId !== undefined) request.projectId = reference.projectId
  if (reference.projectCode !== undefined) request.projectCode = reference.projectCode
  return request
}

function blocked(
  reason: ProjectScopeBlockedReason,
  request?: ProjectScopeRequestIdentity,
): Extract<ResolvedProjectScope, { kind: 'blocked' }> {
  return request ? { kind: 'blocked', reason, request: { ...request } } : { kind: 'blocked', reason }
}

function catalogResolutionState(input: ResolveProjectScopeInput): 'ready' | 'pending' | 'error' {
  if (input.catalogStatus === 'error') return 'error'
  if (input.catalogStatus === 'idle') return 'pending'
  if (input.catalogStatus === 'loading' && !input.hasLoadedSuccessfully) return 'pending'
  if (!input.hasLoadedSuccessfully) return 'pending'
  return 'ready'
}

function findProjectByRequest(
  catalog: readonly ProjectScopeCatalogProject[],
  request: ProjectScopeRequestIdentity,
): { kind: 'matched'; project: ProjectScopeCatalogProject }
  | { kind: 'not-found' }
  | { kind: 'ambiguous' }
  | { kind: 'invalid-reference' } {
  if (request.projectId !== undefined) {
    const project = catalog.find((item) => item.id === request.projectId)
    if (!project) return { kind: 'not-found' }
    if (
      request.projectCode !== undefined
      && normalizeProjectCode(project.projectCode) !== request.projectCode
    ) {
      return { kind: 'invalid-reference' }
    }
    return { kind: 'matched', project }
  }

  const matches = catalog.filter(
    (item) => normalizeProjectCode(item.projectCode) === request.projectCode,
  )
  if (matches.length === 0) return { kind: 'not-found' }
  if (matches.length > 1) return { kind: 'ambiguous' }
  return { kind: 'matched', project: matches[0] }
}

function resolveProjectRequest(
  input: ResolveProjectScopeInput,
  request: ProjectScopeRequestIdentity,
): ResolvedProjectScope {
  const match = findProjectByRequest(input.catalog, request)
  if (match.kind === 'not-found') return blocked('project-not-found', request)
  if (match.kind === 'ambiguous') return blocked('ambiguous-project', request)
  if (match.kind === 'invalid-reference') return blocked('invalid-reference', request)
  if (!input.canReadProject(match.project)) return blocked('project-forbidden', request)

  const project = toProjectIdentity(match.project)
  if (input.requiresProjectCode && !project.projectCode) {
    return blocked('project-code-missing', request)
  }
  return { kind: 'project', project }
}

export function resolveProjectScope(input: ResolveProjectScopeInput): ResolvedProjectScope {
  if (input.reference.kind === 'invalid') return blocked('invalid-reference')

  if (input.reference.kind === 'all') {
    return input.canReadAll ? { kind: 'all' } : blocked('all-forbidden')
  }

  let request: ProjectScopeRequestIdentity | undefined
  if (input.reference.kind === 'project') {
    request = projectRequestFromReference(input.reference)
  } else if (input.selectedProjectId !== null) {
    request = { projectId: input.selectedProjectId }
  } else if (input.canReadAll) {
    return { kind: 'all' }
  }

  const catalogState = catalogResolutionState(input)
  if (catalogState === 'pending') return { kind: 'pending', reason: 'catalog-loading' }
  if (catalogState === 'error') return blocked('catalog-error', request)
  if (request) return resolveProjectRequest(input, request)

  const hasReadableProject = input.catalog.some((project) => input.canReadProject(project))
  return hasReadableProject ? blocked('project-required') : blocked('no-access')
}

export type ResolvedProjectScopeSuccess = Extract<ResolvedProjectScope, { kind: 'all' | 'project' }>

export function withProjectScopeQuery(
  existingQuery: Record<string, unknown>,
  resolvedScope: ResolvedProjectScopeSuccess,
): Record<string, unknown> {
  if (!resolvedScope || (resolvedScope.kind !== 'all' && resolvedScope.kind !== 'project')) {
    throw new Error('Cannot serialize an unresolved project scope')
  }

  const nextQuery = { ...existingQuery }
  delete nextQuery[QUERY_SCOPE]
  delete nextQuery[QUERY_PROJECT_ID]
  delete nextQuery[QUERY_PROJECT_CODE]

  if (resolvedScope.kind === 'all') {
    nextQuery[QUERY_SCOPE] = 'all'
    return nextQuery
  }

  nextQuery[QUERY_PROJECT_ID] = String(resolvedScope.project.id)
  const projectCode = normalizeProjectCode(resolvedScope.project.projectCode)
  if (projectCode) nextQuery[QUERY_PROJECT_CODE] = projectCode
  return nextQuery
}
