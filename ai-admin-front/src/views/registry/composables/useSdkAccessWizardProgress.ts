import { computed, type Ref } from 'vue'
import {
  Box,
  CircleCheck,
  Connection,
  DataBoard,
  Warning,
} from '@element-plus/icons-vue'
import type { ApiAssetItem } from '@/types/apiAsset'
import type { ProjectInstance } from '@/types/registry'
import type {
  AiAccessSession,
  ScanProject,
  SdkAccessCheckResponse,
} from '@/types/scanProject'
import { formatProjectKindLabel } from '@/utils/projectLabels'
import {
  aiAccessSessionTagType,
} from '@/views/registry/sdkAccessWizardViewModel'

export type SdkAccessWizardStepKey =
  | 'overview'
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
  apiAssets: Ref<ApiAssetItem[]>
  accessSession: Ref<AiAccessSession | null>
  checkResult: Ref<SdkAccessCheckResponse | null>
  isSdkBackedProject: Readonly<Ref<boolean>>
  onlineInstanceCount: Readonly<Ref<number>>
  callableApiAssets: Readonly<Ref<ApiAssetItem[]>>
  gatewayBaseUrl: Ref<string>
  embedTokenPath: Ref<string>
  manualChecks: SdkAccessWizardManualChecks
}

export function useSdkAccessWizardProgress(deps: UseSdkAccessWizardProgressDeps) {
  const steps = computed(() => [
    {
      index: 1,
      key: 'overview' as const,
      title: '项目识别',
      desc: '确认项目类型与基础状态',
      status: deps.project.value && deps.isSdkBackedProject.value ? '已完成' : '待确认',
      done: Boolean(deps.project.value && deps.isSdkBackedProject.value),
    },
    {
      index: 2,
      key: 'starter' as const,
      title: '后端 Starter',
      desc: '复制并确认后端配置',
      status: deps.project.value?.registryCredentialConfigured || deps.manualChecks.starter ? '已完成' : '进行中',
      done: Boolean(deps.project.value?.registryCredentialConfigured || deps.manualChecks.starter),
    },
    {
      index: 3,
      key: 'gateway' as const,
      title: '网关路由',
      desc: '配置业务网关转发规则',
      status: deps.gatewayBaseUrl.value.trim() && deps.manualChecks.gateway ? '已完成' : '待处理',
      done: Boolean(deps.gatewayBaseUrl.value.trim() && deps.manualChecks.gateway),
    },
    {
      index: 4,
      key: 'backend-check' as const,
      title: '业务服务校验',
      desc: '确认实例与 API 资产',
      status: deps.onlineInstanceCount.value > 0 && deps.callableApiAssets.value.length > 0 ? '已完成' : '待处理',
      done: deps.onlineInstanceCount.value > 0 && deps.callableApiAssets.value.length > 0,
    },
    {
      index: 5,
      key: 'frontend' as const,
      title: '前端 Embed Token',
      desc: '接入短期 token broker',
      status: deps.embedTokenPath.value.trim() && deps.manualChecks.frontend ? '已完成' : '待处理',
      done: Boolean(deps.embedTokenPath.value.trim() && deps.manualChecks.frontend),
    },
    {
      index: 6,
      key: 'self-check' as const,
      title: '最终自检',
      desc: '平台真实调用业务接口',
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
      label: 'API 资产',
      value: `${deps.callableApiAssets.value.length} 可调用`,
      desc: `${deps.apiAssets.value.length} 个接口已进入目录`,
      tone: deps.callableApiAssets.value.length > 0 ? 'good' : 'neutral',
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
      label: 'API 资产',
      desc: deps.callableApiAssets.value.length > 0 ? `${deps.callableApiAssets.value.length} 个可调用接口` : '暂未检测到可调用接口',
      status: deps.callableApiAssets.value.length > 0 ? 'pass' : 'warn',
      icon: deps.callableApiAssets.value.length > 0 ? CircleCheck : Warning,
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
