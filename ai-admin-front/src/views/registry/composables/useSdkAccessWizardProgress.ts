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

export type SdkAccessWizardStepKey =
  | 'starter'
  | 'gateway'
  | 'backend-check'
  | 'frontend'
  | 'self-check'

export interface SdkAccessWizardManualChecks {
  starter: boolean
  gateway: boolean
  frontend: boolean
}

export interface UseSdkAccessWizardProgressDeps {
  activeStep: Ref<SdkAccessWizardStepKey>
  project: Ref<ScanProject | null>
  instances: Ref<ProjectInstance[]>
  onboardingTask: Ref<AiCodingTask | null>
  checkResult: Ref<SdkAccessCheckResponse | null>
  isSdkBackedProject: Readonly<Ref<boolean>>
  onlineInstanceCount: Readonly<Ref<number>>
  gatewayBaseUrl: Ref<string>
  embedTokenPath: Ref<string>
  manualChecks: SdkAccessWizardManualChecks
}

export function useSdkAccessWizardProgress(deps: UseSdkAccessWizardProgressDeps) {
  const steps = computed(() => {
    const starterVerified = deps.instances.value.length > 0
      || deps.checkResult.value?.readiness?.some(
        (item) => item.key === 'CODE_READY' && item.status === 'PASS',
      )
    const gatewayConfirmed = Boolean(
      deps.gatewayBaseUrl.value.trim() && deps.manualChecks.gateway,
    )
    const frontendConfirmed = Boolean(
      deps.embedTokenPath.value.trim() && deps.manualChecks.frontend,
    )
    const selfCheckVerified = deps.checkResult.value?.overallStatus === 'PASS'

    return [
      {
        index: 1,
        key: 'starter' as const,
        title: '后端 Starter',
        desc: '复制并确认后端配置',
        status: starterVerified ? '已验证' : (deps.manualChecks.starter ? '已确认' : '待处理'),
        done: Boolean(starterVerified || deps.manualChecks.starter),
      },
      {
        index: 2,
        key: 'gateway' as const,
        title: '网关路由',
        desc: '配置业务网关转发规则',
        status: gatewayConfirmed ? '已确认' : '待处理',
        done: gatewayConfirmed,
      },
      {
        index: 3,
        key: 'backend-check' as const,
        title: '业务服务校验',
        desc: '确认 SDK 实例心跳',
        status: deps.onlineInstanceCount.value > 0 ? '已验证' : '待处理',
        done: deps.onlineInstanceCount.value > 0,
      },
      {
        index: 4,
        key: 'frontend' as const,
        title: '前端 Embed Token',
        desc: '接入短期 token broker',
        status: frontendConfirmed ? '已确认' : '待处理',
        done: frontendConfirmed,
      },
      {
        index: 5,
        key: 'self-check' as const,
        title: '平台接入自检',
        desc: '核对 SDK 注册、心跳与签名回调',
        status: selfCheckVerified ? '已验证' : '待处理',
        done: selfCheckVerified,
      },
    ]
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
    {
      label: '接口扫描',
      desc: '不作为 SDK 接入完成条件；请在 API 管理手动同步 SDK 接口',
      status: 'pass',
      icon: CircleCheck,
    },
  ])

  return {
    steps,
    activeStepIndex,
    completedStepCount,
    completedPercent,
    accessSessionTagType,
    backendChecks,
  }
}
