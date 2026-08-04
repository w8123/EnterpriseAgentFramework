import { computed, reactive, ref } from 'vue'
import { describe, expect, it } from 'vitest'
import type { ProjectInstance } from '@/types/registry'
import type { ScanProject, SdkAccessCheckResponse } from '@/types/scanProject'
import { useSdkAccessWizardProgress } from './useSdkAccessWizardProgress'

function createProgress() {
  const activeStep = ref<'starter' | 'gateway' | 'backend-check' | 'frontend' | 'self-check'>('starter')
  const project = ref<ScanProject | null>({
    id: 27,
    name: 'Demo',
    projectCode: 'demo',
    projectKind: 'REGISTERED',
    registryCredentialConfigured: true,
  } as ScanProject)
  const instances = ref<ProjectInstance[]>([])
  const checkResult = ref<SdkAccessCheckResponse | null>(null)
  const gatewayBaseUrl = ref('http://localhost:8080')
  const embedTokenPath = ref('/api/reachai/embed-token')
  const manualChecks = reactive({
    starter: false,
    gateway: false,
    frontend: false,
  })
  const progress = useSdkAccessWizardProgress({
    activeStep,
    project,
    instances,
    onboardingTask: ref(null),
    checkResult,
    isSdkBackedProject: computed(() => true),
    onlineInstanceCount: computed(
      () => instances.value.filter((item) => item.status === 'ONLINE').length,
    ),
    gatewayBaseUrl,
    embedTokenPath,
    manualChecks,
  })
  return {
    ...progress,
    instances,
    checkResult,
    manualChecks,
  }
}

describe('useSdkAccessWizardProgress', () => {
  it('does not treat a stored credential alone as proof that Starter is integrated', () => {
    const progress = createProgress()

    expect(progress.steps.value[0]).toMatchObject({
      status: '待处理',
      done: false,
    })
  })

  it('distinguishes platform verification from a user confirmation', () => {
    const progress = createProgress()
    progress.manualChecks.gateway = true
    progress.instances.value = [{
      id: 1,
      projectId: 27,
      projectCode: 'demo',
      instanceId: 'demo-local',
      status: 'ONLINE',
    }]
    progress.checkResult.value = {
      projectId: 27,
      projectCode: 'demo',
      overallStatus: 'PASS',
      readiness: [{
        key: 'CODE_READY',
        label: '代码接入',
        status: 'PASS',
        message: 'Starter registration observed',
      }],
      checks: [],
    }

    expect(progress.steps.value[0]?.status).toBe('已验证')
    expect(progress.steps.value[1]?.status).toBe('已确认')
    expect(progress.steps.value[2]?.status).toBe('已验证')
    expect(progress.steps.value[4]?.status).toBe('已验证')
    expect(progress.completedStepCount.value).toBe(4)
  })
})
