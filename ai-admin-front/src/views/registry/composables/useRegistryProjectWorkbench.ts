import { computed, type Ref } from 'vue'
import {
  Collection,
  Grid,
  Operation,
  Star,
  User,
} from '@element-plus/icons-vue'
import type { ScanProject } from '@/types/scanProject'

export interface UseRegistryProjectWorkbenchDeps {
  project: Ref<ScanProject | null>
  projectCode: Ref<string>
  isSdkBackedProject: Readonly<Ref<boolean>>
  goCapability: (path: string) => void
  goCapabilitySync: () => void
  goScanProjectDetail: () => void
  goWorkflowList: () => void
  goPageActionGovernance: () => void
  goPageAssistantWizard: () => void
  goSdkAccessWizard: () => void
}

export function useRegistryProjectWorkbench(deps: UseRegistryProjectWorkbenchDeps) {
  const workbenchGroups = computed(() => [
    {
      title: '接入与上报',
      items: [
        ...(deps.isSdkBackedProject.value
          ? [{
              title: '项目接入工作台',
              desc: '从后端 Starter、网关路由、业务服务校验、前端 Embed Token 到平台自检与浏览器验收逐步完成接入。',
              icon: Star,
              tone: 'green',
              disabled: !deps.project.value?.id,
              action: deps.goSdkAccessWizard,
            }]
          : []),
        {
          title: '业务页面工作台',
          desc: '从后端接口资产 + 前端页面动作生成 Workflow Studio 提案。',
          icon: Star,
          tone: 'violet',
          disabled: !deps.project.value?.id,
          action: deps.goPageAssistantWizard,
        },
      ],
    },
    {
      title: '项目资源',
      items: [
        {
          title: '来源变化与同步记录',
          desc: 'SDK 按策略自动接纳声明；在这里处理例外变化并查看同步记录。',
          icon: Grid, tone: 'blue', disabled: !deps.project.value?.id, action: deps.goCapabilitySync,
        },
        {
          title: '后端接口管理',
          desc: '查看来源接口、模块与语义证据；到业务方法/API 目录接纳契约并受控调用。',
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
      ],
    },
    {
      title: '编排与发布',
      items: [
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
    workbenchGroups,
  }
}
