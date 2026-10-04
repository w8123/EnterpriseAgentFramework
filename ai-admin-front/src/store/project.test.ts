import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import {
  PROJECT_CATALOG_ERROR_MESSAGE,
  useProjectStore,
} from './project'
import {
  markPlatformSessionAnonymous,
  markPlatformSessionAuthenticated,
  type PlatformSessionView,
} from '@/auth/platformSession'
import type { ScanProject } from '@/types/scanProject'

const mocks = vi.hoisted(() => ({
  getScanProjects: vi.fn(),
}))

vi.mock('@/api/scanProject', () => ({
  getScanProjects: mocks.getScanProjects,
}))

function createProject(id: number, projectCode: string): ScanProject {
  return {
    id,
    name: `项目 ${id}`,
    projectCode,
    baseUrl: '',
    contextPath: '',
    scanPath: '',
    scanType: 'auto',
    toolCount: 0,
    status: 'created',
  }
}

function createSession(sessionId: string): PlatformSessionView {
  return {
    sessionId,
    expiresAt: '2030-01-01T00:00:00.000Z',
    principal: {
      userId: 7,
      username: 'project-catalog-test',
      permissions: ['*'],
    },
  }
}

describe('project store project catalog state', () => {
  beforeEach(() => {
    markPlatformSessionAnonymous(false)
    setActivePinia(createPinia())
    mocks.getScanProjects.mockReset()
  })

  it('keeps the last confirmed project and selection when a refresh fails', async () => {
    const project = useProjectStore()
    const projectA = createProject(1, 'project-a')
    mocks.getScanProjects
      .mockResolvedValueOnce({ data: [projectA] })
      .mockRejectedValueOnce(new Error('internal connection details'))

    expect(await project.fetchProjects()).toEqual([projectA])
    project.selectCurrentProject(projectA.id)
    expect(await project.fetchProjects()).toBeNull()

    expect(project.status).toBe('error')
    expect(project.projects).toEqual([projectA])
    expect(project.currentProject?.id).toBe(projectA.id)
    expect(project.currentProjectCode).toBe('project-a')
    expect(project.errorMessage).toBe(PROJECT_CATALOG_ERROR_MESSAGE)
    expect(project.errorMessage).not.toContain('internal connection details')
  })

  it('marks a first-load failure as error instead of a real empty catalog', async () => {
    const project = useProjectStore()
    mocks.getScanProjects.mockRejectedValueOnce(new Error('catalog unavailable'))

    expect(await project.fetchProjects()).toBeNull()

    expect(project.status).toBe('error')
    expect(project.hasLoadedSuccessfully).toBe(false)
    expect(project.projects).toEqual([])
    expect(project.loading).toBe(false)
    expect(project.errorMessage).toBe(PROJECT_CATALOG_ERROR_MESSAGE)
  })

  it('marks a malformed response as error without replacing the catalog with []', async () => {
    const project = useProjectStore()
    const projectA = createProject(1, 'project-a')
    mocks.getScanProjects
      .mockResolvedValueOnce({ data: [projectA] })
      .mockResolvedValueOnce({ data: { records: [] } })

    expect(await project.fetchProjects()).toEqual([projectA])
    expect(await project.fetchProjects()).toBeNull()

    expect(project.status).toBe('error')
    expect(project.projects).toEqual([projectA])
    expect(project.hasLoadedSuccessfully).toBe(true)
  })

  it('treats a successful empty array as a real empty catalog', async () => {
    const project = useProjectStore()
    mocks.getScanProjects.mockResolvedValueOnce({ data: [] })

    expect(await project.fetchProjects()).toEqual([])

    expect(project.status).toBe('ready')
    expect(project.hasLoadedSuccessfully).toBe(true)
    expect(project.projects).toEqual([])
    expect(project.errorMessage).toBeNull()
  })

  it('shares one in-flight request across concurrent fetches', async () => {
    const project = useProjectStore()
    const projectA = createProject(1, 'project-a')
    let resolveResponse!: (value: { data: ScanProject[] }) => void
    const response = new Promise<{ data: ScanProject[] }>((resolve) => {
      resolveResponse = resolve
    })
    mocks.getScanProjects.mockReturnValueOnce(response)

    const firstRequest = project.fetchProjects()
    const secondRequest = project.fetchProjects()

    expect(mocks.getScanProjects).toHaveBeenCalledTimes(1)

    resolveResponse({ data: [projectA] })
    expect(await Promise.all([firstRequest, secondRequest])).toEqual([[projectA], [projectA]])

    expect(project.status).toBe('ready')
    expect(project.projects).toEqual([projectA])
  })

  it('recovers from error after an explicit retry succeeds', async () => {
    const project = useProjectStore()
    const projectA = createProject(1, 'project-a')
    mocks.getScanProjects
      .mockRejectedValueOnce(new Error('temporary outage'))
      .mockResolvedValueOnce({ data: [projectA] })

    expect(await project.fetchProjects()).toBeNull()
    expect(project.status).toBe('error')

    expect(await project.fetchProjects()).toEqual([projectA])

    expect(project.status).toBe('ready')
    expect(project.hasLoadedSuccessfully).toBe(true)
    expect(project.projects).toEqual([projectA])
    expect(project.errorMessage).toBeNull()
  })

  it('rejects malformed catalog rows before replacing the last confirmed selection', async () => {
    const project = useProjectStore()
    const projectA = createProject(1, 'project-a')
    mocks.getScanProjects
      .mockResolvedValueOnce({ data: [projectA] })
      .mockResolvedValueOnce({ data: [null] })

    expect(await project.fetchProjects()).toEqual([projectA])
    project.selectCurrentProject(projectA.id)
    expect(await project.fetchProjects()).toBeNull()

    expect(project.status).toBe('error')
    expect(project.projects).toEqual([projectA])
    expect(project.currentProjectCode).toBe('project-a')
  })

  it('rejects duplicate ids before replacing the last confirmed selection', async () => {
    const project = useProjectStore()
    const projectA = createProject(1, 'project-a')
    const duplicate = createProject(1, 'project-a-copy')
    mocks.getScanProjects
      .mockResolvedValueOnce({ data: [projectA] })
      .mockResolvedValueOnce({ data: [projectA, duplicate] })

    expect(await project.fetchProjects()).toEqual([projectA])
    project.selectCurrentProject(projectA.id)
    expect(await project.fetchProjects()).toBeNull()

    expect(project.status).toBe('error')
    expect(project.projects).toEqual([projectA])
    expect(project.currentProjectId).toBe(projectA.id)
    expect(project.currentProjectCode).toBe('project-a')
  })

  it('does not mark a first malformed row response as loaded', async () => {
    const project = useProjectStore()
    mocks.getScanProjects.mockResolvedValueOnce({ data: [null] })

    expect(await project.fetchProjects()).toBeNull()

    expect(project.status).toBe('error')
    expect(project.hasLoadedSuccessfully).toBe(false)
    expect(project.projects).toEqual([])
    expect(project.currentProjectId).toBeNull()
  })

  it('does not let a previous session overwrite the new session catalog', async () => {
    const project = useProjectStore()
    const projectA = createProject(1, 'project-a')
    const projectB = createProject(2, 'project-b')
    let resolveOldRequest!: (value: { data: ScanProject[] }) => void
    let resolveNewRequest!: (value: { data: ScanProject[] }) => void

    mocks.getScanProjects
      .mockReturnValueOnce(new Promise((resolve) => {
        resolveOldRequest = resolve
      }))
      .mockReturnValueOnce(new Promise((resolve) => {
        resolveNewRequest = resolve
      }))

    markPlatformSessionAuthenticated(createSession('session-a'))
    const oldRequest = project.fetchProjects()
    expect(project.status).toBe('loading')

    markPlatformSessionAuthenticated(createSession('session-b'))
    expect(project.status).toBe('idle')
    expect(project.projects).toEqual([])
    expect(project.currentProjectId).toBeNull()

    const newRequest = project.fetchProjects()
    expect(mocks.getScanProjects).toHaveBeenCalledTimes(2)

    resolveOldRequest({ data: [projectA] })
    await expect(oldRequest).resolves.toBeNull()
    expect(project.projects).toEqual([])
    expect(project.status).toBe('loading')

    resolveNewRequest({ data: [projectB] })
    await expect(newRequest).resolves.toEqual([projectB])
    expect(project.status).toBe('ready')
    expect(project.projects).toEqual([projectB])

    markPlatformSessionAnonymous(false)
  })
})
