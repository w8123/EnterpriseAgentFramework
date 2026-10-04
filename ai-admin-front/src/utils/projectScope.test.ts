import { describe, expect, it, vi } from 'vitest'

import {
  parseProjectScopeQuery,
  resolveProjectScope,
  withProjectScopeQuery,
  type ProjectScopeCatalogProject,
  type ProjectScopeQueryReference,
  type ResolveProjectScopeInput,
} from '@/utils/projectScope'

const PROJECT_A: ProjectScopeCatalogProject = {
  id: 1,
  name: '项目 A',
  projectCode: 'project-a',
}

const PROJECT_B: ProjectScopeCatalogProject = {
  id: 2,
  name: '项目 B',
  projectCode: 'project-b',
}

function resolveWith(
  overrides: Partial<ResolveProjectScopeInput> = {},
): ResolvedProjectScopeForTest {
  return resolveProjectScope({
    reference: { kind: 'unspecified' },
    selectedProjectId: null,
    catalog: [PROJECT_A, PROJECT_B],
    catalogStatus: 'ready',
    hasLoadedSuccessfully: true,
    canReadAll: false,
    canReadProject: () => true,
    ...overrides,
  })
}

type ResolvedProjectScopeForTest = ReturnType<typeof resolveProjectScope>

describe('project scope query parsing', () => {
  it.each([
    ['no range fields', {}, { kind: 'unspecified' }],
    ['explicit all', { scope: 'all' }, { kind: 'all' }],
    ['unrelated fields only', { keyword: 'orders', page: '2' }, { kind: 'unspecified' }],
    ['project id', { projectId: '2' }, { kind: 'project', projectId: 2 }],
    ['project code with surrounding whitespace', { projectCode: '  project-b  ' }, { kind: 'project', projectCode: 'project-b' }],
    ['both project identifiers', { projectId: '2', projectCode: ' project-b ' }, { kind: 'project', projectId: 2, projectCode: 'project-b' }],
  ])('parses %s', (_label, query, expected) => {
    expect(parseProjectScopeQuery(query)).toEqual(expected)
  })

  it.each([
    ['uppercase all', { scope: 'ALL' }],
    ['other scope', { scope: 'project' }],
    ['null scope', { scope: null }],
    ['array scope', { scope: ['all'] }],
    ['undefined scope', { scope: undefined }],
  ])('rejects %s', (_label, query) => {
    expect(parseProjectScopeQuery(query)).toEqual({ kind: 'invalid', reason: 'invalid-scope' })
  })

  it('rejects scope combined with any project field, including an empty field', () => {
    expect(parseProjectScopeQuery({ scope: 'all', projectId: undefined })).toEqual({
      kind: 'invalid',
      reason: 'scope-conflicts-with-project',
    })
    expect(parseProjectScopeQuery({ scope: 'all', projectCode: '' })).toEqual({
      kind: 'invalid',
      reason: 'scope-conflicts-with-project',
    })
  })

  it.each([
    ['empty', ''],
    ['whitespace', '  '],
    ['zero', '0'],
    ['negative', '-1'],
    ['decimal', '1.5'],
    ['scientific notation', '1e2'],
    ['number instead of string', 2],
    ['null', null],
    ['array', ['2']],
    ['above safe integer', String(Number.MAX_SAFE_INTEGER + 1)],
  ])('rejects invalid projectId: %s', (_label, projectId) => {
    expect(parseProjectScopeQuery({ projectId })).toEqual({
      kind: 'invalid',
      reason: 'invalid-project-id',
    })
  })

  it('accepts a safe positive decimal string and normalizes leading zeroes', () => {
    expect(parseProjectScopeQuery({ projectId: '0002' })).toEqual({
      kind: 'project',
      projectId: 2,
    })
    expect(parseProjectScopeQuery({ projectId: String(Number.MAX_SAFE_INTEGER) })).toEqual({
      kind: 'project',
      projectId: Number.MAX_SAFE_INTEGER,
    })
  })

  it.each([
    ['empty', ''],
    ['whitespace', ' \t '],
    ['null', null],
    ['array', ['project-b']],
    ['number', 2],
    ['undefined', undefined],
  ])('rejects invalid projectCode: %s', (_label, projectCode) => {
    expect(parseProjectScopeQuery({ projectCode })).toEqual({
      kind: 'invalid',
      reason: 'invalid-project-code',
    })
  })

  it('ignores unrelated query parameters and does not mutate the query', () => {
    const query = { projectId: '2', keyword: 'orders', page: 3 }
    const before = { ...query }

    expect(parseProjectScopeQuery(query)).toEqual({ kind: 'project', projectId: 2 })
    expect(query).toEqual(before)
  })
})

describe('project scope resolution', () => {
  it('uses an explicit project instead of the selected default', () => {
    expect(resolveWith({
      reference: parseProjectScopeQuery({ projectId: '2' }),
      selectedProjectId: 1,
    })).toEqual({
      kind: 'project',
      project: { id: 2, name: '项目 B', projectCode: 'project-b' },
    })
  })

  it('blocks an ID/code conflict without falling back to the selected project or all', () => {
    expect(resolveWith({
      reference: parseProjectScopeQuery({ projectId: '2', projectCode: 'project-a' }),
      selectedProjectId: 1,
      canReadAll: true,
    })).toEqual({
      kind: 'blocked',
      reason: 'invalid-reference',
      request: { projectId: 2, projectCode: 'project-a' },
    })
  })

  it.each([
    ['forbidden project', parseProjectScopeQuery({ projectId: '2' }), [PROJECT_A, PROJECT_B], 'project-forbidden'],
    ['unknown project', parseProjectScopeQuery({ projectId: '99' }), [PROJECT_A, PROJECT_B], 'project-not-found'],
    ['duplicate project code', parseProjectScopeQuery({ projectCode: 'duplicate' }), [PROJECT_A, { ...PROJECT_B, projectCode: ' duplicate ' }, { id: 3, name: '项目 C', projectCode: 'duplicate' }], 'ambiguous-project'],
  ])('returns the stable reason for %s', (_label, reference, catalog, reason) => {
    const result = resolveWith({
      reference,
      selectedProjectId: 1,
      canReadAll: true,
      catalog,
      canReadProject: (project) => project.id !== 2,
    })

    expect(result).toEqual({
      kind: 'blocked',
      reason,
      request: reference.kind === 'project'
        ? {
          ...(reference.projectId === undefined ? {} : { projectId: reference.projectId }),
          ...(reference.projectCode === undefined ? {} : { projectCode: reference.projectCode }),
        }
        : undefined,
    })
  })

  it('does not require the catalog for explicit all and enforces the all permission', () => {
    const canReadProject = vi.fn(() => true)

    expect(resolveWith({
      reference: { kind: 'all' },
      catalogStatus: 'error',
      hasLoadedSuccessfully: false,
      canReadAll: false,
      canReadProject,
    })).toEqual({ kind: 'blocked', reason: 'all-forbidden' })
    expect(canReadProject).not.toHaveBeenCalled()

    expect(resolveWith({
      reference: { kind: 'all' },
      catalogStatus: 'idle',
      hasLoadedSuccessfully: false,
      canReadAll: true,
    })).toEqual({ kind: 'all' })
  })

  it('uses all for an unspecified request only when the user can read all', () => {
    expect(resolveWith({ canReadAll: true })).toEqual({ kind: 'all' })
  })

  it('requires an explicit project choice instead of auto-selecting the first project', () => {
    expect(resolveWith()).toEqual({ kind: 'blocked', reason: 'project-required' })
    expect(resolveWith({ canReadProject: () => false })).toEqual({ kind: 'blocked', reason: 'no-access' })
  })

  it.each([
    ['selected project disappeared', 99, () => true, 'project-not-found'],
    ['selected project is forbidden', 1, (project: ProjectScopeCatalogProject) => project.id !== 1, 'project-forbidden'],
  ])('does not expand %s to all', (_label, selectedProjectId, canReadProject, reason) => {
    expect(resolveWith({ selectedProjectId, canReadAll: true, canReadProject })).toMatchObject({
      kind: 'blocked',
      reason,
    })
  })

  it.each([
    ['idle', 'idle', false, { kind: 'pending', reason: 'catalog-loading' }],
    ['first loading', 'loading', false, { kind: 'pending', reason: 'catalog-loading' }],
    ['error', 'error', true, { kind: 'blocked', reason: 'catalog-error', request: { projectId: 1 } }],
    ['ready', 'ready', true, { kind: 'project', project: { id: 1, name: '项目 A', projectCode: 'project-a' } }],
  ] as const)('handles catalog status %s without using an unverified cache', (_label, catalogStatus, hasLoadedSuccessfully, expected) => {
    const reference: ProjectScopeQueryReference = { kind: 'project', projectId: 1 }
    expect(resolveWith({ reference, catalogStatus, hasLoadedSuccessfully })).toEqual(expected)
  })

  it('can use the successfully loaded cache while a refresh is loading', () => {
    expect(resolveWith({
      reference: { kind: 'project', projectId: 2 },
      catalogStatus: 'loading',
      hasLoadedSuccessfully: true,
    })).toEqual({
      kind: 'project',
      project: { id: 2, name: '项目 B', projectCode: 'project-b' },
    })
  })

  it('returns catalog-error for an unscoped request when the catalog is unavailable', () => {
    expect(resolveWith({
      catalogStatus: 'error',
      hasLoadedSuccessfully: false,
    })).toEqual({ kind: 'blocked', reason: 'catalog-error' })
  })

  it('checks project permission even when all-project access is available', () => {
    const canReadProject = vi.fn(() => false)

    expect(resolveWith({
      reference: { kind: 'project', projectId: 1 },
      canReadAll: true,
      canReadProject,
    })).toEqual({ kind: 'blocked', reason: 'project-forbidden', request: { projectId: 1 } })
    expect(canReadProject).toHaveBeenCalledWith(PROJECT_A)
  })

  it('requires projectCode only when the caller opts into that requirement', () => {
    const withoutCode = { id: 3, name: '无编码项目', projectCode: null }
    const reference: ProjectScopeQueryReference = { kind: 'project', projectId: 3 }

    expect(resolveWith({
      reference,
      catalog: [withoutCode],
      requiresProjectCode: true,
    })).toEqual({ kind: 'blocked', reason: 'project-code-missing', request: { projectId: 3 } })
    expect(resolveWith({
      reference,
      catalog: [withoutCode],
      requiresProjectCode: false,
    })).toEqual({
      kind: 'project',
      project: { id: 3, name: '无编码项目', projectCode: null },
    })
  })

  it('returns only project identity fields and leaves the catalog untouched', () => {
    const project = {
      ...PROJECT_A,
      baseUrl: 'https://secret.example.test',
      authApiKeyValue: 'secret-value',
    }
    const catalog = [project]
    const before = { ...project }

    expect(resolveWith({
      reference: { kind: 'project', projectId: 1 },
      catalog,
    })).toEqual({
      kind: 'project',
      project: { id: 1, name: '项目 A', projectCode: 'project-a' },
    })
    expect(catalog).toEqual([before])
  })
})

describe('project scope query serialization', () => {
  it('retains unrelated query fields, removes old range fields, and writes project identity', () => {
    const filters = ['active']
    const existingQuery = {
      keyword: 'orders',
      page: 2,
      filters,
      scope: 'all',
      projectId: '1',
      projectCode: 'project-a',
    }
    const before = { ...existingQuery }

    const nextQuery = withProjectScopeQuery(existingQuery, {
      kind: 'project',
      project: { id: 2, name: '项目 B', projectCode: ' project-b ' },
    })

    expect(nextQuery).toEqual({
      keyword: 'orders',
      page: 2,
      filters,
      projectId: '2',
      projectCode: 'project-b',
    })
    expect(existingQuery).toEqual(before)
    expect(nextQuery).not.toBe(existingQuery)
  })

  it('serializes all by clearing project constraints', () => {
    expect(withProjectScopeQuery({
      scope: 'all',
      projectId: '2',
      projectCode: 'project-b',
      keyword: 'orders',
    }, { kind: 'all' })).toEqual({
      keyword: 'orders',
      scope: 'all',
    })
  })

  it('omits an empty projectCode instead of serializing a false constraint', () => {
    expect(withProjectScopeQuery({ projectCode: 'old', page: 1 }, {
      kind: 'project',
      project: { id: 3, name: '无编码项目', projectCode: null },
    })).toEqual({ projectId: '3', page: 1 })
  })

  it('round-trips serialized project and all scopes through the parser and resolver', () => {
    const projectScope = { kind: 'project' as const, project: { id: 2, name: '项目 B', projectCode: 'project-b' } }
    const projectQuery = withProjectScopeQuery({ keyword: 'orders' }, projectScope)
    expect(resolveWith({
      reference: parseProjectScopeQuery(projectQuery),
    })).toEqual(projectScope)

    const allQuery = withProjectScopeQuery({ projectId: '2' }, { kind: 'all' })
    expect(resolveWith({
      reference: parseProjectScopeQuery(allQuery),
      canReadAll: true,
    })).toEqual({ kind: 'all' })
  })

  it('does not accept pending or blocked resolutions for serialization', () => {
    expect(() => withProjectScopeQuery({}, { kind: 'pending', reason: 'catalog-loading' } as never)).toThrow(
      'Cannot serialize an unresolved project scope',
    )
    expect(() => withProjectScopeQuery({}, { kind: 'blocked', reason: 'project-required' } as never)).toThrow(
      'Cannot serialize an unresolved project scope',
    )
  })
})
