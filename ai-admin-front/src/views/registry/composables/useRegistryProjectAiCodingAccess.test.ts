import { ref } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { ScanProject } from '@/types/scanProject'
import { useRegistryProjectAiCodingAccess } from './useRegistryProjectAiCodingAccess'

const mocks = vi.hoisted(() => ({
  getAiOnboardingManifest: vi.fn(),
  updateAiCodingAccess: vi.fn(),
  getAiCodingCredentialPolicy: vi.fn(),
  updateAiCodingCredentialPolicy: vi.fn(),
  warning: vi.fn(),
  success: vi.fn(),
  error: vi.fn(),
}))

vi.mock('@/api/scanProject', () => ({
  getAiOnboardingManifest: mocks.getAiOnboardingManifest,
  updateAiCodingAccess: mocks.updateAiCodingAccess,
}))

vi.mock('@/api/aiCodingTasks', () => ({
  getAiCodingCredentialPolicy: mocks.getAiCodingCredentialPolicy,
  updateAiCodingCredentialPolicy: mocks.updateAiCodingCredentialPolicy,
}))

vi.mock('element-plus', () => ({
  ElMessage: {
    warning: mocks.warning,
    success: mocks.success,
    error: mocks.error,
  },
}))

function createAccess(
  project = ref({
    id: 27,
    projectCode: 'qmssmp-local-e2e',
    name: 'QMSSMP Local E2E',
  } as ScanProject),
  projectCode = ref('qmssmp-local-e2e'),
) {
  return useRegistryProjectAiCodingAccess({
    project,
    projectCode,
  })
}

function stubSuccessfulLoads() {
  mocks.getAiOnboardingManifest.mockResolvedValue({
    data: {
      aiCodingAccess: {
        enabled: true,
        accessKey: 'local-test-key',
      },
    },
  })
  mocks.getAiCodingCredentialPolicy.mockResolvedValue({
    data: {
      projectId: 27,
      handoffActivationTtlHours: 96,
      taskTokenTtlHours: 120,
      customized: true,
    },
  })
}

describe('useRegistryProjectAiCodingAccess', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    stubSuccessfulLoads()
  })

  it('loads both settings and clears truthful error state on retry', async () => {
    mocks.getAiCodingCredentialPolicy.mockRejectedValueOnce(
      new Error('network down'),
    )
    const access = createAccess()

    await access.loadAiCodingAccess(27)
    expect(access.credentialPolicyLoading.value).toBe(false)
    expect(access.credentialPolicyLoadError.value).not.toBe('')
    expect(access.handoffActivationTtlHours.value).toBe(72)
    expect(access.taskTokenTtlHours.value).toBe(72)

    await access.loadAiCodingAccess(27)
    expect(access.credentialPolicyLoadError.value).toBe('')
    expect(access.aiCodingAccessLoadError.value).toBe('')
    expect(access.handoffActivationTtlHours.value).toBe(96)
    expect(access.taskTokenTtlHours.value).toBe(120)
    expect(access.credentialPolicyCustomized.value).toBe(true)
  })

  it('does not overwrite credential policy when its current value is unknown', async () => {
    mocks.getAiCodingCredentialPolicy.mockRejectedValue(
      new Error('network down'),
    )
    const access = createAccess()

    await access.loadAiCodingAccess(27)
    await access.saveCredentialPolicy()

    expect(mocks.updateAiCodingCredentialPolicy).not.toHaveBeenCalled()
    expect(mocks.warning).toHaveBeenCalledWith(
      '请先重新加载当前凭据有效期',
    )
  })

  it('does not overwrite legacy access settings when their current value is unknown', async () => {
    mocks.getAiOnboardingManifest.mockRejectedValue(
      new Error('network down'),
    )
    const access = createAccess()

    await access.loadAiCodingAccess(27)
    access.aiCodingAccessEnabled.value = false
    access.aiCodingAccessKey.value = ''
    await access.saveAiCodingAccess()
    await access.clearAiCodingAccess()
    await access.copyAiCodingBundle()

    expect(mocks.updateAiCodingAccess).not.toHaveBeenCalled()
    expect(mocks.warning).toHaveBeenCalledTimes(3)
    expect(mocks.warning).toHaveBeenNthCalledWith(
      1,
      '请先重新加载当前 AI Coding 接入设置',
    )
  })

  it('clears sensitive state before loading settings for another project', async () => {
    const access = createAccess()
    await access.loadAiCodingAccess(27)
    expect(access.aiCodingAccessKey.value).toBe('local-test-key')

    mocks.getAiOnboardingManifest.mockRejectedValueOnce(new Error('network down'))
    mocks.getAiCodingCredentialPolicy.mockRejectedValueOnce(new Error('network down'))
    await access.loadAiCodingAccess(28)

    expect(access.aiCodingAccessEnabled.value).toBe(false)
    expect(access.aiCodingAccessKey.value).toBe('')
    expect(access.handoffActivationTtlHours.value).toBe(72)
    expect(access.taskTokenTtlHours.value).toBe(72)
    expect(access.credentialPolicyCustomized.value).toBe(false)
    expect(access.aiCodingAccessLoadError.value).not.toBe('')
    expect(access.credentialPolicyLoadError.value).not.toBe('')
  })

  it('only lets the latest concurrent load update settings', async () => {
    let resolveFirstAccess:
      | ((value: { data: { aiCodingAccess: { enabled: boolean; accessKey: string } } }) => void)
      | undefined
    let resolveFirstPolicy:
      | ((value: {
          data: {
            projectId: number
            handoffActivationTtlHours: number
            taskTokenTtlHours: number
            customized: boolean
          }
        }) => void)
      | undefined
    mocks.getAiOnboardingManifest
      .mockImplementationOnce(() => new Promise((resolve) => {
        resolveFirstAccess = resolve
      }))
      .mockResolvedValueOnce({
        data: {
          aiCodingAccess: {
            enabled: true,
            accessKey: 'newer-key',
          },
        },
      })
    mocks.getAiCodingCredentialPolicy
      .mockImplementationOnce(() => new Promise((resolve) => {
        resolveFirstPolicy = resolve
      }))
      .mockResolvedValueOnce({
        data: {
          projectId: 27,
          handoffActivationTtlHours: 120,
          taskTokenTtlHours: 240,
          customized: true,
        },
      })
    const access = createAccess()

    const firstLoad = access.loadAiCodingAccess(27)
    const latestLoad = access.loadAiCodingAccess(27)
    await latestLoad
    resolveFirstAccess?.({
      data: {
        aiCodingAccess: {
          enabled: false,
          accessKey: 'stale-key',
        },
      },
    })
    resolveFirstPolicy?.({
      data: {
        projectId: 27,
        handoffActivationTtlHours: 1,
        taskTokenTtlHours: 1,
        customized: false,
      },
    })
    await firstLoad

    expect(access.aiCodingAccessEnabled.value).toBe(true)
    expect(access.aiCodingAccessKey.value).toBe('newer-key')
    expect(access.handoffActivationTtlHours.value).toBe(120)
    expect(access.taskTokenTtlHours.value).toBe(240)
    expect(access.credentialPolicyCustomized.value).toBe(true)
    expect(access.credentialPolicyLoading.value).toBe(false)
  })

  it('does not publish an access save response after changing projects', async () => {
    let resolveSave:
      | ((value: { data: { enabled: boolean; accessKey: string } }) => void)
      | undefined
    mocks.updateAiCodingAccess.mockImplementationOnce(() => new Promise((resolve) => {
      resolveSave = resolve
    }))
    const project = ref({
      id: 27,
      projectCode: 'project-a',
      name: 'Project A',
    } as ScanProject)
    const projectCode = ref('project-a')
    const access = createAccess(project, projectCode)
    await access.loadAiCodingAccess(27)

    const pendingSave = access.saveAiCodingAccess()
    project.value = {
      id: 28,
      projectCode: 'project-b',
      name: 'Project B',
    } as ScanProject
    projectCode.value = 'project-b'
    mocks.getAiOnboardingManifest.mockResolvedValueOnce({
      data: {
        aiCodingAccess: {
          enabled: true,
          accessKey: 'project-b-key',
        },
      },
    })
    mocks.getAiCodingCredentialPolicy.mockResolvedValueOnce({
      data: {
        projectId: 28,
        handoffActivationTtlHours: 24,
        taskTokenTtlHours: 48,
        customized: true,
      },
    })
    await access.loadAiCodingAccess(28)
    resolveSave?.({
      data: {
        enabled: false,
        accessKey: 'project-a-saved-key',
      },
    })
    await pendingSave

    expect(access.aiCodingAccessEnabled.value).toBe(true)
    expect(access.aiCodingAccessKey.value).toBe('project-b-key')
    expect(mocks.success).not.toHaveBeenCalled()
  })

  it('does not publish a credential-policy save response after changing projects', async () => {
    let resolveSave:
      | ((value: {
          data: {
            handoffActivationTtlHours: number
            taskTokenTtlHours: number
            customized: boolean
          }
        }) => void)
      | undefined
    mocks.updateAiCodingCredentialPolicy.mockImplementationOnce(
      () => new Promise((resolve) => {
        resolveSave = resolve
      }),
    )
    const project = ref({
      id: 27,
      projectCode: 'project-a',
      name: 'Project A',
    } as ScanProject)
    const projectCode = ref('project-a')
    const access = createAccess(project, projectCode)
    await access.loadAiCodingAccess(27)

    const pendingSave = access.saveCredentialPolicy()
    project.value = {
      id: 28,
      projectCode: 'project-b',
      name: 'Project B',
    } as ScanProject
    projectCode.value = 'project-b'
    mocks.getAiOnboardingManifest.mockResolvedValueOnce({
      data: {
        aiCodingAccess: {
          enabled: true,
          accessKey: 'project-b-key',
        },
      },
    })
    mocks.getAiCodingCredentialPolicy.mockResolvedValueOnce({
      data: {
        projectId: 28,
        handoffActivationTtlHours: 24,
        taskTokenTtlHours: 48,
        customized: true,
      },
    })
    await access.loadAiCodingAccess(28)
    resolveSave?.({
      data: {
        handoffActivationTtlHours: 96,
        taskTokenTtlHours: 120,
        customized: true,
      },
    })
    await pendingSave

    expect(access.handoffActivationTtlHours.value).toBe(24)
    expect(access.taskTokenTtlHours.value).toBe(48)
    expect(mocks.success).not.toHaveBeenCalled()
  })
})
