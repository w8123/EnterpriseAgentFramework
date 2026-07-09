import { computed, type Ref } from 'vue'
import {
  Box,
  CircleCheck,
  Connection,
  DataBoard,
  Warning,
} from '@element-plus/icons-vue'
import type { ProjectInstance } from '@/types/registry'
import type {
  AiAccessSession,
  ProjectToolInfo,
  ScanProject,
  SdkAccessCheckResponse,
} from '@/types/scanProject'
import { formatProjectKindLabel } from '@/utils/projectLabels'
import {
  aiAccessSessionTagType,
} from '@/views/registry/sdkAccessWizardViewModel'

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
  projectApiTools: Ref<ProjectToolInfo[]>
  accessSession: Ref<AiAccessSession | null>
  checkResult: Ref<SdkAccessCheckResponse | null>
  isSdkBackedProject: Readonly<Ref<boolean>>
  onlineInstanceCount: Readonly<Ref<number>>
  callableProjectApiTools: Readonly<Ref<ProjectToolInfo[]>>
  gatewayBaseUrl: Ref<string>
  embedTokenPath: Ref<string>
  manualChecks: SdkAccessWizardManualChecks
}

export function useSdkAccessWizardProgress(deps: UseSdkAccessWizardProgressDeps) {
  const steps = computed(() => [
    {
      index: 1,
      key: 'starter' as const,
      title: '后端 Starter',
      desc: '复制并确认后端配置',
      status: deps.project.value?.registryCredentialConfigured || deps.manualChecks.starter ? '已完成' : '进行中',
      done: Boolean(deps.project.value?.registryCredentialConfigured || deps.manualChecks.starter),
    },
    {
      index: 2,
      key: 'gateway' as const,
      title: '网关路由',
      desc: '配置业务网关转发规则',
      status: deps.gatewayBaseUrl.value.trim() && deps.manualChecks.gateway ? '已完成' : '待处理',
      done: Boolean(deps.gatewayBaseUrl.value.trim() && deps.manualChecks.gateway),
    },
    {
      index: 3,
      key: 'backend-check' as const,
      title: '业务服务校验',
      desc: '确认 SDK 实例心跳',
      status: deps.onlineInstanceCount.value > 0 ? '已完成' : '待处理',
      done: deps.onlineInstanceCount.value > 0,
    },
    {
      index: 4,
      key: 'frontend' as const,
      title: '前端 Embed Token',
      desc: '接入短期 token broker',
      status: deps.embedTokenPath.value.trim() && deps.manualChecks.frontend ? '已完成' : '待处理',
      done: Boolean(deps.embedTokenPath.value.trim() && deps.manualChecks.frontend),
    },
    {
      index: 5,
      key: 'self-check' as const,
      title: '最终自检',
      desc: '平台接入自检与可选调用',
      status: deps.checkResult.value?.overallStatus === 'PASS' ? '已完成' : '待处理',
      done: deps.checkResult.value?.overallStatus === 'PASS',
    },
  ])

  const activeStepIndex = computed(() => steps.value.findIndex((item) => item.key === deps.activeStep.value))

  const completedStepCount = computed(() => steps.value.filter((item) => item.done).length)

  const completedPercent = computed(() =>
    steps.value.length ? Math.round((completedStepCount.value / steps.value.length) * 100) : 0,
  )

  const accessSessionTagType = computed(() =>
    aiAccessSessionTagType(deps.accessSession.value?.status),
  )

  const overviewCards = computed(() => [
    {
      label: '接入方式',
      value: formatProjectKindLabel(deps.project.value?.projectKind || '-'),
      desc: deps.isSdkBackedProject.value ? '可使用 SDK 接入向导' : '请使用扫描项目工作台',
      tone: deps.isSdkBackedProject.value ? 'good' : 'warn',
      icon: Box,
      accent: 'access',
    },
    {
      label: '服务端凭证',
      value: deps.project.value?.registryCredentialConfigured ? '已配置' : '未配置',
      desc: '真实 secret 不会返回到浏览器',
      tone: deps.project.value?.registryCredentialConfigured ? 'good' : 'warn',
      icon: CircleCheck,
      accent: 'credential',
    },
    {
      label: 'SDK 实例',
      value: `${deps.onlineInstanceCount.value} 在线`,
      desc: `${deps.instances.value.length} 个实例已登记`,
      tone: deps.onlineInstanceCount.value > 0 ? 'good' : 'neutral',
      icon: DataBoard,
      accent: 'instances',
    },
    {
      label: '接口同步',
      value: 'API 管理手动触发',
      desc: deps.projectApiTools.value.length > 0
        ? `${deps.projectApiTools.value.length} 个接口已进入目录`
        : '接入完成后到 API 管理添加接口',
      tone: deps.projectApiTools.value.length > 0 ? 'good' : 'neutral',
      icon: Connection,
      iconText: 'API',
      accent: 'assets',
    },
  ])

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
    overviewCards,
    backendChecks,
  }
}
