import {
  hasPlatformPermission,
  PLATFORM_PERMISSION_ADMIN,
  PLATFORM_PERMISSION_AGENT_WRITE,
  PLATFORM_PERMISSION_KNOWLEDGE_WRITE,
  PLATFORM_PERMISSION_MODEL_MANAGE,
  PLATFORM_PERMISSION_READ,
  PLATFORM_PERMISSION_RUNOPS_READ,
  PLATFORM_PERMISSION_TOOL_ACL_READ,
  PLATFORM_PERMISSION_WORKFLOW_WRITE,
  PLATFORM_PERMISSION_WRITE,
} from '@/auth/platformAccess'

export interface RoleShortcut {
  label: string
  detail: string
  to: string
  permission: string
}

export interface RoleShortcutGroup {
  key: 'developer' | 'operator' | 'admin' | 'auditor'
  title: string
  shortcuts: RoleShortcut[]
}

/**
 * 概览顶部按「人」给出的常用入口。分组与侧边栏一致：一个账号拥有哪些角色，就看到哪些组。
 * 这里只决定入口可见性，权限仍由路由守卫和 Control 校验。
 */
const GROUPS: Array<RoleShortcutGroup & { entry: string }> = [
  {
    key: 'developer',
    title: '开发',
    entry: PLATFORM_PERMISSION_WRITE,
    shortcuts: [
      { label: '接入项目', detail: '注册业务系统、下发 SDK 凭证、查看同步诊断', to: '/registry/projects', permission: PLATFORM_PERMISSION_WRITE },
      { label: '业务能力', detail: '维护 Java 业务方法与 HTTP API，评审能力变化', to: '/business-capabilities', permission: PLATFORM_PERMISSION_READ },
    ],
  },
  {
    key: 'operator',
    title: '运营',
    entry: PLATFORM_PERMISSION_WORKFLOW_WRITE,
    shortcuts: [
      { label: '编排 Workflow', detail: '用 AI 生成或修改流程，调试后发布', to: '/workflows', permission: PLATFORM_PERMISSION_WORKFLOW_WRITE },
      { label: '配置 Agent', detail: '选择可调用的 Workflow，设置记忆和对外开放', to: '/agent', permission: PLATFORM_PERMISSION_AGENT_WRITE },
      { label: '维护知识库', detail: '上传文件、管理业务索引', to: '/knowledge', permission: PLATFORM_PERMISSION_KNOWLEDGE_WRITE },
      { label: '调用权限', detail: '控制业务系统用户能让 Agent 调用什么', to: '/settings/tool-acl', permission: PLATFORM_PERMISSION_TOOL_ACL_READ },
      { label: '运行中心', detail: '查看运行记录、自动化和评测，定位出错的节点', to: '/runops', permission: PLATFORM_PERMISSION_RUNOPS_READ },
    ],
  },
  {
    key: 'admin',
    title: '平台管理',
    entry: PLATFORM_PERMISSION_ADMIN,
    shortcuts: [
      { label: '账号与角色', detail: '创建账号，分配开发人员、运营人员和项目范围', to: '/settings/platform-users', permission: PLATFORM_PERMISSION_ADMIN },
      { label: '模型中心', detail: '接入模型服务商并管理可用模型', to: '/model/instances', permission: PLATFORM_PERMISSION_MODEL_MANAGE },
    ],
  },
  {
    key: 'auditor',
    title: '审计',
    entry: PLATFORM_PERMISSION_RUNOPS_READ,
    shortcuts: [
      { label: '运行记录', detail: '查看 Agent 与 Workflow 的运行和 Trace', to: '/runops', permission: PLATFORM_PERMISSION_RUNOPS_READ },
    ],
  },
]

export function roleShortcutGroups(permissions: readonly string[] | null | undefined): RoleShortcutGroup[] {
  const granted = permissions ?? []
  const groups = GROUPS
    .filter(group => hasPlatformPermission(granted, group.entry))
    .map(({ key, title, shortcuts }) => ({
      key,
      title,
      shortcuts: shortcuts.filter(shortcut => hasPlatformPermission(granted, shortcut.permission)),
    }))
    .filter(group => group.shortcuts.length > 0)
  // 审计组只给纯只读账号；拥有其他组的人已能从那些组进入运行记录。
  return groups.length > 1 ? groups.filter(group => group.key !== 'auditor') : groups
}
