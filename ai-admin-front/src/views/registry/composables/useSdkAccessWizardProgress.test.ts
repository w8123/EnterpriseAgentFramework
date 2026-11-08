import { computed, ref } from 'vue'
import { describe, expect, it } from 'vitest'
import type { ProjectInstance } from '@/types/registry'
import type { ScanProject, SdkAccessCheckResponse } from '@/types/scanProject'
import { instanceMatchesSdkLanguage, useSdkAccessWizardProgress } from './useSdkAccessWizardProgress'
import { useSdkAccessWizardUiState, type SdkAccessLanguage } from './useSdkAccessWizardUiState'

function createProgress(language: SdkAccessLanguage = 'java') {
  const { activeStep, embedTokenPath, manualChecks } = useSdkAccessWizardUiState()
  const project = ref<ScanProject | null>({
    id: 27,
    name: 'Demo',
    projectCode: 'demo',
    projectKind: 'REGISTERED',
    registryCredentialConfigured: true,
  } as ScanProject)
  const instances = ref<ProjectInstance[]>([])
  const selectedInstances = computed(() => instances.value.filter((item) => instanceMatchesSdkLanguage(item, language)))
  const checkResult = ref<SdkAccessCheckResponse | null>(null)
  const progress = useSdkAccessWizardProgress({
    activeStep,
    project,
    instances: selectedInstances,
    onboardingTask: ref(null),
    checkResult,
    isSdkBackedProject: computed(() => true),
    onlineInstanceCount: computed(
      () => selectedInstances.value.filter((item) => item.status === 'ONLINE').length,
    ),
    embedTokenPath,
    manualChecks,
  })
  return { ...progress, instances, checkResult, manualChecks }
}

const javaInstance = { id: 1, projectId: 27, projectCode: 'demo', instanceId: 'x', status: 'ONLINE' } as ProjectInstance

function projectCheck(callbackStatus: 'PASS' | 'WARN' = 'PASS'): SdkAccessCheckResponse {
  return {
    projectId: 27,
    projectCode: 'demo',
    overallStatus: 'WARN',
    readiness: [],
    checks: [
      { key: 'REGISTRY_CREDENTIAL', label: '注册凭据', status: 'PASS', message: 'credential present' },
      { key: 'INSTANCE_HEARTBEAT', label: '实例心跳', status: 'PASS', message: 'instance online' },
      { key: 'SDK_SYNC_CALLBACK', label: 'SDK 同步回调', status: callbackStatus, message: 'callback evidence' },
      { key: 'AI_CODING_ACCESS', label: 'AI Coding 接入', status: 'WARN', message: 'disabled' },
    ],
  }
}

describe('useSdkAccessWizardProgress', () => {
  it('keeps legacy Java instances separate from explicitly reported SDK languages', () => {
    expect(instanceMatchesSdkLanguage(javaInstance, 'java')).toBe(true)
    expect(instanceMatchesSdkLanguage({ ...javaInstance, metadataJson: '{invalid' }, 'java')).toBe(false)
    for (const language of ['csharp', 'python', 'node', 'go'] as const) {
      const instance = { ...javaInstance, metadataJson: JSON.stringify({ sdkLanguage: language }) }
      expect(instanceMatchesSdkLanguage(javaInstance, language)).toBe(false)
      expect(instanceMatchesSdkLanguage(instance, language)).toBe(true)
      expect(instanceMatchesSdkLanguage(instance, 'java')).toBe(false)
    }
  })

  it.each(['csharp', 'python', 'node', 'go'] as const)(
    'requires a %s instance and an explicit review of the synced capabilities',
    (language) => {
      const progress = createProgress(language)
      progress.checkResult.value = projectCheck()
      progress.instances.value = [javaInstance]
      expect(progress.steps.value.find((step) => step.key === 'starter')?.done).toBe(false)
      expect(progress.canConfirmSync.value).toBe(false)

      progress.instances.value.push({ ...javaInstance, metadataJson: JSON.stringify({ sdkLanguage: language }) })
      expect(progress.canConfirmSync.value).toBe(true)
      expect(progress.steps.value.find((step) => step.key === 'backend-check')?.done).toBe(false)
      progress.manualChecks.sync = true
      expect(progress.steps.value.find((step) => step.key === 'backend-check')?.status).toBe('已确认')
      expect(progress.steps.value.find((step) => step.key === 'acceptance')?.done).toBe(false)
    },
  )

  it('keeps configured callbacks pending and omits the optional AI Coding setting from the SDK check', () => {
    const progress = createProgress()
    progress.instances.value = [javaInstance]
    progress.checkResult.value = projectCheck('WARN')
    expect(progress.manualSdkCheck.value?.status).toBe('WARN')
    expect(progress.canConfirmSync.value).toBe(false)

    progress.checkResult.value = projectCheck()
    expect(progress.manualSdkCheck.value?.status).toBe('PASS')
    expect(progress.manualSdkCheck.value?.checks.map((item) => item.key)).not.toContain('AI_CODING_ACCESS')
    expect(progress.canConfirmSync.value).toBe(true)
  })

  it('does not treat a stored credential alone as proof that Starter is integrated', () => {
    const progress = createProgress()
    expect(progress.steps.value[0]).toMatchObject({ status: '待处理', done: false })
  })

  it('distinguishes platform registration from the remaining implementation and acceptance steps', () => {
    const progress = createProgress()
    progress.manualChecks.gateway = true
    progress.instances.value = [javaInstance]
    progress.checkResult.value = projectCheck()

    expect(progress.steps.value.map((step) => step.key)).toEqual([
      'starter', 'capabilities', 'identity', 'gateway', 'backend-check', 'frontend', 'acceptance',
    ])
    expect(progress.steps.value.find((step) => step.key === 'starter')?.status).toBe('已注册')
    expect(progress.steps.value.find((step) => step.key === 'gateway')?.status).toBe('已确认')
    expect(progress.completedStepCount.value).toBe(2)
  })
})
