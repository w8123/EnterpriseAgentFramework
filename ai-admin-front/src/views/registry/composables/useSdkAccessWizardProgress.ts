import { computed, type Ref } from 'vue'
import {
  CircleCheck,
  Warning,
} from '@element-plus/icons-vue'
import type { ProjectInstance } from '@/types/registry'
import type { AiCodingTask } from '@/types/aiCodingTask'
import { aiCodingTaskStatusTagType } from '@/utils/aiCodingPresentation'
import type {
  ScanProject,
  SdkAccessCheckResponse,
} from '@/types/scanProject'
import type { SdkAccessLanguage } from './useSdkAccessWizardUiState'

export type SdkAccessWizardStepKey =
  | 'starter'
  | 'capabilities'
  | 'identity'
  | 'gateway'
  | 'backend-check'
  | 'frontend'
  | 'acceptance'

export interface SdkAccessWizardManualChecks {
  starter: boolean
  capabilities: boolean
  identity: boolean
  gateway: boolean
  sync: boolean
  frontend: boolean
  acceptance: boolean
}

const SDK_CHECK_KEYS = ['REGISTRY_CREDENTIAL', 'INSTANCE_HEARTBEAT', 'SDK_SYNC_CALLBACK']

export interface UseSdkAccessWizardProgressDeps {
  activeStep: Ref<SdkAccessWizardStepKey>
  project: Ref<ScanProject | null>
  instances: Ref<ProjectInstance[]>
  onboardingTask: Ref<AiCodingTask | null>
  checkResult: Ref<SdkAccessCheckResponse | null>
  isSdkBackedProject: Readonly<Ref<boolean>>
  onlineInstanceCount: Readonly<Ref<number>>
  embedTokenPath: Ref<string>
  manualChecks: SdkAccessWizardManualChecks
}

/** Missing language identifies legacy Java; malformed metadata never proves a language. */
export function instanceMatchesSdkLanguage(instance: ProjectInstance, language: SdkAccessLanguage) {
  if (!instance.metadataJson) return language === 'java'
  try {
    const metadata: unknown = JSON.parse(instance.metadataJson)
    if (!metadata || typeof metadata !== 'object' || Array.isArray(metadata)) return false
    const declared = (metadata as Record<string, unknown>).sdkLanguage
    if (declared == null) return language === 'java'
    return typeof declared === 'string' && declared.toLowerCase() === language
  } catch {
    return false
  }
}

export function useSdkAccessWizardProgress(deps: UseSdkAccessWizardProgressDeps) {
  const manualSdkCheck = computed(() => {
    if (!deps.checkResult.value) return null
    const checks = deps.checkResult.value.checks.filter((item) => SDK_CHECK_KEYS.includes(item.key))
    const passed = SDK_CHECK_KEYS.every((key) => checks.some((item) => item.key === key && item.status === 'PASS'))
    const status = checks.some((item) => item.status === 'FAIL') ? 'FAIL' : passed ? 'PASS' : 'WARN'
    return { checks, status }
  })

  // Callback evidence is project-scoped. A matching heartbeat and a review of
  // the target capabilities are required before the manual sync step is done.
  const canConfirmSync = computed(() =>
    manualSdkCheck.value?.status === 'PASS' && deps.onlineInstanceCount.value > 0,
  )

  const steps = computed(() => {
    const starterRegistered = deps.instances.value.length > 0
    const checks = deps.manualChecks
    return [
      {
        key: 'starter' as const,
        title: '后端 SDK',
        desc: '安装对应技术栈的 SDK，配置项目和后端注册凭据。',
        done: starterRegistered || checks.starter,
      },
      {
        key: 'capabilities' as const,
        title: '声明业务方法',
        desc: '选择本次开放的方法，声明参数、返回值、副作用和身份要求。',
        done: checks.capabilities,
      },
      {
        key: 'identity' as const,
        title: '接入业务身份',
        desc: '沿用业务系统登录，从可信登录态取得身份，并让业务方法使用原有权限校验。',
        done: checks.identity,
      },
      {
        key: 'gateway' as const,
        title: '网关路由',
        desc: '按业务系统的网络和认证架构配置签名回调、调用和页面对话路由。',
        done: checks.gateway,
      },
      {
        key: 'backend-check' as const,
        title: '启动、同步与自检',
        desc: '启动服务，检查实例心跳、签名回调和能力目录中的同步结果。',
        done: canConfirmSync.value && checks.sync,
      },
      {
        key: 'frontend' as const,
        title: '嵌入业务页面',
        desc: '需要页面对话时，配置 Agent、安装 Embed Chat SDK 并挂载对话。',
        done: Boolean(deps.embedTokenPath.value.trim() && checks.frontend),
      },
      {
        key: 'acceptance' as const,
        title: '真实业务验收',
        desc: '使用真实业务测试账号验证身份、授权、业务结果，以及本次接入的页面功能。',
        done: checks.acceptance,
      },
    ].map((step, index) => ({
      ...step,
      index: index + 1,
      status: step.key === 'starter' && starterRegistered ? '已注册'
        : step.done ? '已确认' : step.key === 'frontend' ? '按需接入' : '待处理',
    }))
  })

  const activeStepIndex = computed(() => steps.value.findIndex((item) => item.key === deps.activeStep.value))

  const completedStepCount = computed(() => steps.value.filter((item) => item.done).length)

  const completedPercent = computed(() =>
    steps.value.length ? Math.round((completedStepCount.value / steps.value.length) * 100) : 0,
  )

  const accessSessionTagType = computed(() => {
    return aiCodingTaskStatusTagType(deps.onboardingTask.value)
  })

  const backendChecks = computed(() => [
    {
      label: '项目类型',
      desc: deps.isSdkBackedProject.value ? 'SDK / 混合接入项目' : '当前不是 SDK 项目',
      status: deps.isSdkBackedProject.value ? 'pass' : 'fail',
      icon: deps.isSdkBackedProject.value ? CircleCheck : Warning,
    },
    {
      label: '服务端凭证',
      desc: deps.project.value?.registryCredentialConfigured ? '后端已保存对接凭证' : '需要先配置服务端对接凭证',
      status: deps.project.value?.registryCredentialConfigured ? 'pass' : 'fail',
      icon: deps.project.value?.registryCredentialConfigured ? CircleCheck : Warning,
    },
    {
      label: '实例心跳',
      desc: deps.onlineInstanceCount.value > 0 ? `${deps.onlineInstanceCount.value} 个在线实例` : '暂未检测到在线实例',
      status: deps.onlineInstanceCount.value > 0 ? 'pass' : 'warn',
      icon: deps.onlineInstanceCount.value > 0 ? CircleCheck : Warning,
    },
  ])

  return {
    steps,
    activeStepIndex,
    completedStepCount,
    completedPercent,
    accessSessionTagType,
    backendChecks,
    manualSdkCheck,
    canConfirmSync,
  }
}
