import { computed, type Ref } from 'vue'
import {
  Clock,
  Collection,
  Grid,
  Key,
  Link,
  Lock,
  Monitor,
  Operation,
  Star,
  Tools,
  User,
} from '@element-plus/icons-vue'
import type {
  PageActionRegistryView,
  PageRegistryView,
} from '@/api/embedOps'
import type { ProjectInstance } from '@/types/registry'
import type { ScanProject } from '@/types/scanProject'

export interface UseRegistryProjectWorkbenchDeps {
  project: Ref<ScanProject | null>
  projectCode: Ref<string>
  instances: Ref<ProjectInstance[]>
  pageRegistry: Ref<PageRegistryView[]>
  pageActions: Ref<PageActionRegistryView[]>
  aiCodingAccessEnabled: Ref<boolean>
  aiCodingAccessKey: Ref<string>
  isSdkBackedProject: Readonly<Ref<boolean>>
  formatHeartbeatDisplay: (value?: string | null) => string
  openAiCodingDialog: () => void
  goCapability: (path: string) => void
  goScanProjectDetail: () => void
  goCapabilitySync: () => void
  goWorkflowList: () => void
  goPageActionGovernance: () => void
  goContextGovernance: () => void
  goContextCandidateReview: () => void
  goPageAssistantWizard: () => void
  goSdkAccessWizard: () => void
}

export function useRegistryProjectWorkbench(deps: UseRegistryProjectWorkbenchDeps) {
  const latestHeartbeat = computed(() => {
    const raw = deps.instances.value[0]?.lastHeartbeatAt || deps.project.value?.lastScannedAt
    return deps.formatHeartbeatDisplay(raw ?? null)
  })

  const activePageActionCount = computed(() =>
    deps.pageActions.value.filter((item) => item.status === 'ACTIVE').length,
  )

  const removedPageActionCount = computed(() =>
    deps.pageActions.value.filter((item) => item.status === 'REMOVED').length,
  )

  const lastPageActionSeenAt = computed(() => {
    const raw = deps.pageActions.value[0]?.lastSeenAt || deps.pageRegistry.value[0]?.lastSeenAt
    return deps.formatHeartbeatDisplay(raw ?? null)
  })

  const healthMetrics = computed(() => [
    {
      label: '实例心跳',
      value: `${deps.instances.value.filter((item) => item.status === 'ONLINE').length} 在线`,
      desc: `最近心跳 ${latestHeartbeat.value}`,
      icon: Monitor,
      tone: deps.instances.value.some((item) => item.status === 'ONLINE') ? 'good' : 'attention',
      clickable: false,
    },
    {
      label: '能力资产',
      value: `${deps.project.value?.toolCount ?? 0} 能力`,
      desc: (deps.project.value?.toolCount ?? 0) > 0 ? '项目能力资产已形成目录' : '建议检查后端接口管理或能力上报',
      icon: Collection,
      tone: (deps.project.value?.toolCount ?? 0) > 0 ? 'good' : 'attention',
      clickable: false,
    },
    {
      label: '前端页面管理',
      value: `${deps.pageRegistry.value.length} 页面 / ${deps.pageActions.value.length} 动作`,
      desc: `${activePageActionCount.value} ACTIVE / ${removedPageActionCount.value} REMOVED`,
      icon: Link,
      tone: activePageActionCount.value > 0 ? 'good' : 'neutral',
      clickable: false,
    },
    {
      label: '最近上报',
      value: lastPageActionSeenAt.value,
      desc: '页面、动作、实例的最近观测时间',
      icon: Clock,
      tone: lastPageActionSeenAt.value !== '-' ? 'good' : 'neutral',
      clickable: false,
    },
    {
      label: 'AI Coding 接入',
      value: deps.aiCodingAccessEnabled.value && deps.aiCodingAccessKey.value.trim() ? '已启用' : '未启用',
      desc: '点击查看接入信息与复制',
      icon: Key,
      tone: deps.aiCodingAccessEnabled.value && deps.aiCodingAccessKey.value.trim() ? 'good' : 'attention',
      clickable: true,
      action: deps.openAiCodingDialog,
    },
  ])

  const workbenchGroups = computed(() => [
    {
      title: '接入与上报',
      items: [
        ...(deps.isSdkBackedProject.value
          ? [{
              title: 'SDK 接入向导',
              desc: '从后端 Starter、网关路由、业务服务校验、前端 embed token 到最终自检逐步完成接入。',
              icon: Star,
              tone: 'green',
              disabled: !deps.project.value?.id,
              action: deps.goSdkAccessWizard,
            }]
          : []),
        {
          title: '创建页面助手',
          desc: '从后端接口资产 + 前端页面动作生成 Workflow Studio 草稿。',
          icon: Star,
          tone: 'violet',
          disabled: !deps.project.value?.id,
          action: deps.goPageAssistantWizard,
        },
        {
          title: '代码扫描补充上下文',
          desc: '供 Cursor、Codex、Claude Code 扫描代码后提交项目/页面/API/Workflow 上下文候选。',
          icon: Collection,
          tone: 'green',
          disabled: !deps.project.value?.id,
          action: deps.goContextCandidateReview,
        },
      ],
    },
    {
      title: '项目资源',
      items: [
        {
          title: '后端接口管理',
          desc: '管理扫描接口、模块列表和接口图谱，处理 Tool 关联与语义文档。',
          icon: Grid,
          tone: 'blue',
          disabled: !deps.project.value?.id,
          action: deps.goScanProjectDetail,
        },
        {
          title: '前端页面管理',
          desc: '管理业务页面、可执行动作、嵌入授权和会话审计。',
          icon: Collection,
          tone: 'orange',
          disabled: !deps.project.value,
          action: deps.goPageActionGovernance,
        },
        {
          title: '上下文治理',
          desc: '维护项目背景、页面/API/Workflow 契约、规则、证据和组包预览。',
          icon: Collection,
          tone: 'green',
          disabled: !deps.project.value?.id,
          action: deps.goContextGovernance,
        },
        {
          title: '工具管理',
          desc: '查看项目下可被 Agent Runtime 调用的 Tool 清单。',
          icon: Tools,
          tone: 'blue',
          disabled: !deps.project.value?.id,
          action: () => deps.goCapability('/tool'),
        },
      ],
    },
    {
      title: '编排与发布',
      items: [
        {
          title: '能力变更评审',
          desc: '发布前处理能力快照 diff、字段变化、apply / ignore。',
          icon: Lock,
          tone: 'green',
          disabled: !deps.project.value?.id,
          action: deps.goCapabilitySync,
        },
        {
          title: 'Workflow 编排',
          desc: '查看并编辑本项目下的可执行 Workflow，进入 Studio、版本和发布链路。',
          icon: Operation,
          tone: 'blue',
          disabled: !deps.project.value?.projectCode && !deps.projectCode.value,
          action: deps.goWorkflowList,
        },
        {
          title: 'Agent管理',
          desc: '管理版本、发布状态、Trace、页面动作闭环和权限决策。',
          icon: User,
          tone: 'orange',
          disabled: !deps.project.value?.id,
          action: () => deps.goCapability('/agent'),
        },
      ],
    },
  ])

  return {
    healthMetrics,
    workbenchGroups,
  }
}
