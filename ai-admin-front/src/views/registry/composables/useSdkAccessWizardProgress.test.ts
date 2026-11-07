import { computed, reactive, ref } from 'vue'
import { describe, expect, it } from 'vitest'
import type { ProjectInstance } from '@/types/registry'
import type { ScanProject, SdkAccessCheckResponse } from '@/types/scanProject'
import { instanceMatchesSdkLanguage, useSdkAccessWizardProgress } from './useSdkAccessWizardProgress'

function createProgress(language: 'java' | 'csharp' | 'python' | 'node' | 'go' = 'java', hasOther = false) {
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
    sdkLanguage: ref(language),
    hasOtherLanguageInstances: ref(hasOther),
  })
  return {
    ...progress,
    instances,
    checkResult,
    manualChecks,
  }
}

describe('useSdkAccessWizardProgress', () => {
  it('keeps legacy Java instances separate from explicitly reported C# instances', () => {
    const instance = { id: 1, projectId: 27, projectCode: 'demo', instanceId: 'x', status: 'ONLINE' } as ProjectInstance
    expect(instanceMatchesSdkLanguage(instance, 'java')).toBe(true)
    expect(instanceMatchesSdkLanguage(instance, 'csharp')).toBe(false)
    const csharp = { ...instance, metadataJson: '{"sdkLanguage":"csharp"}' }
    expect(instanceMatchesSdkLanguage(csharp, 'csharp')).toBe(true)
    expect(instanceMatchesSdkLanguage(csharp, 'java')).toBe(false)
    expect(instanceMatchesSdkLanguage({ ...instance, metadataJson: '{invalid' }, 'java')).toBe(false)
  })

  it('does not treat a project-level check as proof of C# without a matching instance', () => {
    const progress = createProgress('csharp')
    progress.checkResult.value = {
      projectId: 27, projectCode: 'demo', overallStatus: 'PASS', checks: [],
      readiness: [{ key: 'CODE_READY', label: '代码接入', status: 'PASS', message: 'existing project' }],
    }
    expect(progress.steps.value[0]?.done).toBe(false)
    expect(progress.steps.value[4]?.done).toBe(false)
  })

  it('requires a Python instance and never treats Java or C# instances as Python', () => {
    const progress = createProgress('python')
    progress.checkResult.value = {
      projectId: 27, projectCode: 'demo', overallStatus: 'PASS', checks: [],
      readiness: [{ key: 'CODE_READY', label: '代码接入', status: 'PASS', message: 'other SDK' }],
    }
    expect(progress.steps.value[0]?.done).toBe(false)
    expect(progress.steps.value[4]?.done).toBe(false)
    const instance = { id: 1, projectId: 27, projectCode: 'demo', instanceId: 'x', status: 'ONLINE' } as ProjectInstance
    expect(instanceMatchesSdkLanguage(instance, 'python')).toBe(false)
    expect(instanceMatchesSdkLanguage({ ...instance, metadataJson: '{"sdkLanguage":"csharp"}' }, 'python')).toBe(false)
    expect(instanceMatchesSdkLanguage({ ...instance, metadataJson: '{"sdkLanguage":"python"}' }, 'python')).toBe(true)
    progress.instances.value = [{ ...instance, metadataJson: '{"sdkLanguage":"python"}' }]
    expect(progress.steps.value[2]?.done).toBe(true)
    expect(progress.steps.value[4]?.done).toBe(false)
  })

  it('requires Node instance identity and does not credit project-scoped callback evidence', () => {
    const progress = createProgress('node')
    progress.checkResult.value = {
      projectId: 27, projectCode: 'demo', overallStatus: 'PASS', checks: [],
      readiness: [{ key: 'CODE_READY', label: '代码接入', status: 'PASS', message: 'other SDK' }],
    } as SdkAccessCheckResponse
    const instance = { id: 1, projectId: 27, projectCode: 'demo', instanceId: 'x', status: 'ONLINE' } as ProjectInstance
    expect(instanceMatchesSdkLanguage({ ...instance, metadataJson: '{"sdkLanguage":"python"}' }, 'node')).toBe(false)
    expect(instanceMatchesSdkLanguage({ ...instance, metadataJson: '{"sdkLanguage":"node"}' }, 'node')).toBe(true)
    expect(progress.steps.value[0]?.done).toBe(false)
    progress.instances.value = [{ ...instance, metadataJson: '{"sdkLanguage":"node"}' }]
    expect(progress.steps.value[2]?.done).toBe(true)
    expect(progress.steps.value[4]?.done).toBe(false)
  })

  it('requires a Go instance and never credits project-scoped callback evidence', () => {
    const progress = createProgress('go')
    progress.checkResult.value = {
      projectId: 27, projectCode: 'demo', overallStatus: 'PASS', checks: [],
      readiness: [{ key: 'CODE_READY', label: '代码接入', status: 'PASS', message: 'other SDK' }],
    } as SdkAccessCheckResponse
    const instance = { id: 1, projectId: 27, projectCode: 'demo', instanceId: 'x', status: 'ONLINE' } as ProjectInstance
    for (const language of ['java', 'csharp', 'python', 'node']) {
      expect(instanceMatchesSdkLanguage({ ...instance, metadataJson: JSON.stringify({ sdkLanguage: language }) }, 'go')).toBe(false)
    }
    expect(instanceMatchesSdkLanguage({ ...instance, metadataJson: '{invalid' }, 'go')).toBe(false)
    expect(progress.steps.value[0]?.done).toBe(false)
    progress.instances.value = [{ ...instance, metadataJson: '{"sdkLanguage":"go"}' }]
    expect(progress.steps.value[2]?.done).toBe(true)
    expect(progress.steps.value[4]?.done).toBe(false)
  })

  it('does not use a different language instance to verify the Java step', () => {
    const progress = createProgress('java', true)
    progress.checkResult.value = {
      projectId: 27, projectCode: 'demo', overallStatus: 'PASS', checks: [],
      readiness: [{ key: 'CODE_READY', label: '代码接入', status: 'PASS', message: 'other SDK' }],
    }
    expect(progress.steps.value[0]?.done).toBe(false)
    expect(progress.steps.value[4]?.done).toBe(false)
  })
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
