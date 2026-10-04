import { createRouter, createWebHistory } from 'vue-router'
import type { RouteLocationNormalizedLoaded, RouteRecordRaw } from 'vue-router'
import {
  PLATFORM_PERMISSION_ADMIN,
  PLATFORM_PERMISSION_AGENT_DEBUG,
  PLATFORM_PERMISSION_AGENT_EVALUATE,
  PLATFORM_PERMISSION_AGENT_READ,
  PLATFORM_PERMISSION_AGENT_WRITE,
  PLATFORM_PERMISSION_AUTOMATION_READ,
  PLATFORM_PERMISSION_BUSINESS_USER_READ,
  PLATFORM_PERMISSION_MEMORY_ERASURE_MANAGE,
  PLATFORM_PERMISSION_READ,
  PLATFORM_PERMISSION_RUNOPS_READ,
  PLATFORM_PERMISSION_WRITE,
  PLATFORM_PERMISSION_WORKFLOW_READ,
  PLATFORM_PERMISSION_WORKFLOW_WRITE,
} from '@/auth/platformAccess'
import { resolvePlatformNavigation } from '@/auth/platformNavigation'

/** 项目详情动态面包屑目标：从当前路由取 projectCode。 */
const toProjectDetail = (route: RouteLocationNormalizedLoaded) => ({
  path: `/registry/projects/${route.params.projectCode}`,
})

const toProjectPageActions = (route: RouteLocationNormalizedLoaded) => ({
  path: `/registry/projects/${route.params.projectCode}/page-actions`,
})

const resolveAgentEditorTitle = (route: RouteLocationNormalizedLoaded) => (
  route.params.id === 'new' ? '新建 Agent' : 'Agent 编辑'
)

const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'Login',
    component: () => import('@/views/Login.vue'),
    meta: { title: 'Login', public: true },
  },
  {
    path: '/auth-unavailable',
    name: 'AuthUnavailable',
    component: () => import('@/views/AuthUnavailable.vue'),
    meta: { title: '登录状态验证不可用', public: true },
  },
  {
    path: '/access-denied',
    name: 'AccessDenied',
    component: () => import('@/views/AccessDenied.vue'),
    meta: { title: '没有访问权限' },
  },
  {
    path: '/',
    component: () => import('@/views/layout/MainLayout.vue'),
    redirect: '/dashboard',
    children: [
      // ── Dashboard ──
      {
        path: 'dashboard',
        name: 'Dashboard',
        component: () => import('@/views/dashboard/Dashboard.vue'),
        meta: { title: '概览', layoutMode: 'standard' },
      },

      // ── Agent 管理 ──
      {
        path: 'agent',
        name: 'AgentList',
        component: () => import('@/views/agent/AgentList.vue'),
        meta: {
          title: 'Agent',
          layoutMode: 'standard',
          requiredPermissions: [PLATFORM_PERMISSION_AGENT_READ],
          projectScope: {
            permission: PLATFORM_PERMISSION_AGENT_READ,
            resourceLabel: 'Agent',
            requiresProjectCode: false,
          },
        },
      },
      {
        path: 'agent/:id/edit',
        name: 'AgentEdit',
        component: () => import('@/views/agent/AgentEdit.vue'),
        meta: {
          title: resolveAgentEditorTitle,
          layoutMode: 'standard',
          requiredPermissions: [PLATFORM_PERMISSION_AGENT_WRITE],
          breadcrumb: [
            { title: '智能体与编排', to: { path: '/agent' } },
            { title: resolveAgentEditorTitle },
          ],
        },
      },
      {
        path: 'agent/:id/debug',
        name: 'AgentDebug',
        component: () => import('@/views/agent/AgentDebug.vue'),
        meta: {
          title: 'Agent 调试',
          layoutMode: 'edge-to-edge',
          requiredPermissions: [PLATFORM_PERMISSION_AGENT_DEBUG],
          breadcrumb: [
            { title: '智能体与编排', to: { path: '/agent' } },
            { title: 'Agent 调试' },
          ],
        },
      },
      {
        path: 'agent/:id/evals',
        name: 'AgentEval',
        component: () => import('@/views/agent/AgentEval.vue'),
        meta: {
          title: 'Agent 评测',
          layoutMode: 'standard',
          requiredPermissions: [PLATFORM_PERMISSION_AGENT_EVALUATE],
        },
      },
      {
        path: 'skill-market',
        name: 'SkillMarket',
        component: () => import('@/views/skill-market/SkillMarket.vue'),
        meta: { title: 'Skill 市场', layoutMode: 'standard' },
      },
      {
        path: 'skills',
        name: 'SkillCenter',
        component: () => import('@/views/skill/SkillCenter.vue'),
        meta: { title: 'Skill 管理', layoutMode: 'standard' },
      },
      {
        path: 'workflows',
        name: 'WorkflowList',
        component: () => import('@/views/workflow/WorkflowList.vue'),
        meta: {
          title: 'Workflow 编排',
          layoutMode: 'project-workbench',
          requiredPermissions: [PLATFORM_PERMISSION_WORKFLOW_READ],
          projectScope: {
            permission: PLATFORM_PERMISSION_WORKFLOW_READ,
            resourceLabel: 'Workflow',
            requiresProjectCode: true,
          },
        },
      },
      {
        path: 'workflows/:workflowId/studio',
        name: 'WorkflowStudio',
        component: () => import('@/views/workflow/WorkflowStudio.vue'),
        meta: {
          title: 'Workflow 编排 Studio',
          layoutMode: 'studio',
          requiredPermissions: [PLATFORM_PERMISSION_WORKFLOW_WRITE],
        },
      },
      {
        path: 'workflows/:workflowId/versions',
        name: 'WorkflowVersions',
        component: () => import('@/views/workflow/WorkflowVersions.vue'),
        meta: {
          title: 'Workflow 版本',
          layoutMode: 'standard',
          requiredPermissions: [PLATFORM_PERMISSION_WORKFLOW_READ],
        },
      },
      {
        path: 'runops',
        name: 'RunOpsList',
        component: () => import('@/views/runops/RunOpsList.vue'),
        meta: {
          title: '运行中心',
          layoutMode: 'standard',
          requiredPermissions: [PLATFORM_PERMISSION_RUNOPS_READ],
        },
      },
      {
        path: 'automations',
        name: 'AutomationCenter',
        component: () => import('@/views/automation/AutomationCenter.vue'),
        meta: {
          title: '自动化中心',
          layoutMode: 'standard',
          requiredPermissions: [PLATFORM_PERMISSION_AUTOMATION_READ],
        },
      },
      {
        path: 'runops/:traceId',
        name: 'RunOpsDetail',
        component: () => import('@/views/runops/RunOpsDetail.vue'),
        meta: {
          title: '运行详情',
          layoutMode: 'standard',
          requiredPermissions: [PLATFORM_PERMISSION_RUNOPS_READ],
        },
      },

      // ── 知识管理 ──
      {
        path: 'knowledge',
        name: 'KnowledgeList',
        component: () => import('@/views/KnowledgeList.vue'),
        meta: { title: '知识库管理', layoutMode: 'standard' },
      },
      {
        path: 'knowledge/import',
        name: 'KnowledgeImport',
        component: () => import('@/views/KnowledgeImport.vue'),
        meta: { title: '文件入库', layoutMode: 'standard' },
      },
      {
        path: 'knowledge/:code',
        name: 'KnowledgeDetail',
        component: () => import('@/views/KnowledgeDetail.vue'),
        meta: { title: '知识库详情', layoutMode: 'standard' },
      },
      {
        path: 'knowledge/:code/file/:fileId',
        name: 'FileDetail',
        component: () => import('@/views/FileDetail.vue'),
        meta: { title: '文件详情', layoutMode: 'standard' },
      },
      {
        path: 'retrieval',
        name: 'RetrievalTest',
        component: () => import('@/views/RetrievalTest.vue'),
        meta: { title: '检索测试', layoutMode: 'standard' },
      },
      {
        path: 'biz-index',
        name: 'BizIndexList',
        component: () => import('@/views/BizIndexList.vue'),
        meta: { title: '业务索引管理', layoutMode: 'standard' },
      },
      {
        path: 'biz-index/:code',
        name: 'BizIndexDetail',
        component: () => import('@/views/BizIndexDetail.vue'),
        meta: { title: '索引详情', layoutMode: 'standard' },
      },

      // ── 模型管理 ──
      {
        path: 'model/instances',
        name: 'ModelInstances',
        component: () => import('@/views/model/ModelInstances.vue'),
        meta: { title: '模型中心', layoutMode: 'standard', activeMenu: '/model/instances' },
      },
      {
        path: 'model/instances/:id',
        name: 'ModelInstanceDetail',
        component: () => import('@/views/model/ModelInstanceDetail.vue'),
        meta: {
          title: '模型详情',
          layoutMode: 'standard',
          activeMenu: '/model/instances',
        },
      },
      {
        path: 'model/playground',
        name: 'ModelPlayground',
        component: () => import('@/views/model/ModelPlayground.vue'),
        meta: { title: '模型调试台', layoutMode: 'edge-to-edge' },
      },

      // ── 外部 API 市场 ──
      {
        path: 'api-market',
        name: 'ApiMarket',
        component: () => import('@/views/api-market/ApiMarket.vue'),
        meta: { title: 'API 市场', layoutMode: 'standard' },
      },

      // ── Runtime 调用诊断；通用 Tool 不再作为可编辑产品资产 ──
      {
        path: 'tool/retrieval',
        name: 'ToolRetrievalTest',
        component: () => import('@/views/tool/ToolRetrievalTest.vue'),
        meta: { title: '调用候选检索', layoutMode: 'standard' },
      },
      {
        path: 'tool',
        redirect: '/capability',
        meta: { title: '能力目录', layoutMode: 'standard' },
      },
      {
        path: 'capability',
        name: 'CapabilityKernel',
        component: () => import('@/views/capability/CapabilityKernel.vue'),
        meta: {
          title: '能力目录',
          layoutMode: 'standard',
          requiredPermissions: [PLATFORM_PERMISSION_READ],
          breadcrumb: [{ title: '能力资产' }, { title: '能力目录' }],
        },
      },
      {
        path: 'business-methods',
        name: 'BusinessMethodCatalog',
        component: () => import('@/views/capability/CapabilityKernel.vue'),
        props: { catalogKind: 'business-method' },
        meta: {
          title: '业务方法',
          layoutMode: 'standard',
          requiredPermissions: [PLATFORM_PERMISSION_READ],
          projectScope: {
            permission: PLATFORM_PERMISSION_READ,
            resourceLabel: '业务方法',
            requiresProjectCode: false,
          },
          breadcrumb: [{ title: '能力资产' }, { title: '业务方法' }],
        },
      },
      {
        path: 'apis',
        name: 'HttpApiCatalog',
        component: () => import('@/views/api/HttpApiCatalog.vue'),
        meta: {
          title: 'API', layoutMode: 'standard', requiredPermissions: [PLATFORM_PERMISSION_READ],
          projectScope: { permission: PLATFORM_PERMISSION_READ, resourceLabel: 'API', requiresProjectCode: true },
          breadcrumb: [{ title: '能力资产' }, { title: 'API' }],
        },
      },
      {
        path: 'apis/:id',
        name: 'HttpApiDetail',
        component: () => import('@/views/api/HttpApiDetail.vue'),
        meta: {
          title: 'API 详情', layoutMode: 'standard', requiredPermissions: [PLATFORM_PERMISSION_READ],
          projectScope: { permission: PLATFORM_PERMISSION_READ, resourceLabel: 'API', requiresProjectCode: true },
          activeMenu: '/apis',
          breadcrumb: [{ title: '能力资产' }, { title: 'API', path: '/apis' }, { title: 'API 详情' }],
        },
      },
      {
        path: 'capability/review',
        name: 'CapabilityReview',
        component: () => import('@/views/registry/CapabilitySyncDebug.vue'),
        meta: {
          title: '能力变化',
          layoutMode: 'standard',
          requiredPermissions: [PLATFORM_PERMISSION_READ],
          activeMenu: '/capability',
          breadcrumb: [{ title: '能力目录', path: '/capability' }, { title: '能力变化' }],
        },
      },
      {
        path: 'capability/sync-snapshot',
        name: 'CapabilitySyncSnapshot',
        component: () => import('@/views/registry/CapabilitySyncDebug.vue'),
        meta: {
          title: '同步能力快照',
          layoutMode: 'standard',
          requiredPermissions: [PLATFORM_PERMISSION_WRITE],
          activeMenu: '/capability/sync-snapshot',
          breadcrumb: [{ title: '能力资产' }, { title: '同步能力快照' }],
        },
      },
      {
        path: 'capability/tools',
        name: 'CapabilityKernelTools',
        redirect: '/capability',
        meta: { title: '能力目录', layoutMode: 'standard' },
      },
      {
        path: 'capability/compositions',
        name: 'CapabilityKernelCompositions',
        redirect: '/capability',
        meta: { title: '能力目录', layoutMode: 'standard' },
      },
      {
        path: 'capability/interactions',
        name: 'CapabilityKernelInteractions',
        redirect: '/capability',
        meta: { title: '能力目录', layoutMode: 'standard' },
      },
      {
        path: 'registry/projects',
        name: 'RegistryProjectList',
        component: () => import('@/views/registry/RegistryProjectList.vue'),
        meta: {
          title: '项目中心 · 项目管理',
          layoutMode: 'project-workbench',
          hideSidebarProjectPanel: true,
          activeMenu: '/registry/projects',
          breadcrumb: [{ title: '项目中心' }, { title: '项目管理' }],
        },
      },
      {
        path: 'registry/projects/:projectCode',
        name: 'RegistryProjectDetail',
        component: () => import('@/views/registry/RegistryProjectDetail.vue'),
        meta: {
          title: '项目中心 · 项目详情',
          layoutMode: 'project-workbench',
          hideSidebarProjectPanel: true,
          activeMenu: '/registry/projects',
          breadcrumb: [
            { title: '项目中心' },
            { title: '项目管理', to: { path: '/registry/projects' } },
            { title: '项目详情' },
          ],
        },
      },
      {
        path: 'registry/capability-sync',
        name: 'CapabilitySyncDebug',
        redirect: '/capability/review',
        meta: {
          title: '能力变化',
          layoutMode: 'standard',
          requiredPermissions: [PLATFORM_PERMISSION_READ],
          activeMenu: '/capability',
          breadcrumb: [
            { title: '能力资产' },
            { title: '能力变化' },
          ],
        },
      },
      {
        path: 'registry/projects/:projectCode/page-actions',
        name: 'EmbedOpsMonitor',
        component: () => import('@/views/settings/EmbedOpsMonitor.vue'),
        meta: {
          title: '前端页面管理',
          layoutMode: 'project-workbench',
          activeMenu: '/registry/projects',
          breadcrumb: [
            { title: '项目中心' },
            { title: '项目管理', to: { path: '/registry/projects' } },
            { title: '项目详情', to: toProjectDetail },
            { title: '前端页面管理' },
          ],
        },
      },
      {
        path: 'registry/projects/:projectCode/page-assistant',
        name: 'PageAssistantWizard',
        component: () => import('@/views/registry/PageAssistantWizard.vue'),
        meta: {
          title: '业务页面工作台',
          layoutMode: 'project-workbench',
          breadcrumbShowBack: false,
          activeMenu: '/registry/projects',
          breadcrumb: [
            { title: '项目中心' },
            { title: '项目管理', to: { path: '/registry/projects' } },
            { title: '项目详情', to: toProjectDetail },
            { title: '业务页面工作台' },
          ],
        },
      },
      {
        path: 'registry/projects/:projectCode/sdk-access',
        name: 'SdkAccessWizard',
        component: () => import('@/views/registry/SdkAccessWizard.vue'),
        meta: {
          title: '项目接入工作台',
          layoutMode: 'project-workbench',
          activeMenu: '/registry/projects',
          breadcrumb: [
            { title: '项目中心' },
            { title: '项目管理', to: { path: '/registry/projects' } },
            { title: '项目详情', to: toProjectDetail },
            { title: '项目接入工作台' },
          ],
        },
      },
      {
        path: 'registry/projects/:projectCode/page-actions/sessions',
        name: 'EmbedSessionAudit',
        component: () => import('@/views/settings/EmbedSessionAudit.vue'),
        meta: {
          title: '嵌入式会话审计',
          layoutMode: 'project-workbench',
          activeMenu: '/registry/projects',
          breadcrumb: [
            { title: '项目中心' },
            { title: '项目管理', to: { path: '/registry/projects' } },
            { title: '项目详情', to: toProjectDetail },
            { title: '前端页面管理', to: toProjectPageActions },
            { title: '嵌入式会话审计' },
          ],
        },
      },
      {
        path: 'scan-project',
        name: 'ScanProjectList',
        redirect: '/registry/projects',
        meta: { title: '项目与 API 接入', layoutMode: 'project-workbench' },
      },
      {
        path: 'scan-project/:id',
        name: 'ScanProjectDetail',
        component: () => import('@/views/scan/ScanProjectDetail.vue'),
        meta: {
          title: 'API 管理',
          layoutMode: 'project-workbench',
          activeMenu: '/registry/projects',
          breadcrumb: [
            { title: '项目中心' },
            { title: '项目管理', to: { path: '/registry/projects' } },
            { title: 'API 管理' },
          ],
        },
      },

      // ── MCP 互联中心：独立产品模块 ──
      {
        path: 'mcp-hub',
        component: () => import('@/views/mcp-hub/McpHubLayout.vue'),
        redirect: '/mcp-hub/overview',
        meta: { activeMenu: '/mcp-hub', layoutMode: 'standard' },
        children: [
          {
            path: 'overview',
            name: 'McpHubOverview',
            component: () => import('@/views/mcp-hub/McpHubOverview.vue'),
            meta: { title: 'MCP 互联中心 · 总览', activeMenu: '/mcp-hub', layoutMode: 'standard' },
          },
          {
            path: 'publications',
            name: 'McpHubPublications',
            component: () => import('@/views/mcp-hub/McpPublicationList.vue'),
            meta: { title: 'MCP 互联中心 · 对外发布', activeMenu: '/mcp-hub', layoutMode: 'standard' },
          },
          {
            path: 'publications/:id',
            name: 'McpHubPublicationDetail',
            component: () => import('@/views/mcp-hub/McpPublicationDetail.vue'),
            meta: { title: 'MCP 互联中心 · 发布详情', activeMenu: '/mcp-hub', layoutMode: 'standard' },
          },
          {
            path: 'call-logs',
            name: 'McpHubCallLogs',
            component: () => import('@/views/mcp-hub/McpCallLogList.vue'),
            meta: { title: 'MCP 互联中心 · 调用流水', activeMenu: '/mcp-hub', layoutMode: 'standard' },
          },
        ],
      },

      // ── A2A 互联中心：独立产品模块 ──
      {
        path: 'a2a-hub',
        component: () => import('@/views/a2a-hub/A2aHubLayout.vue'),
        redirect: '/a2a-hub/overview',
        meta: { activeMenu: '/a2a-hub', layoutMode: 'standard' },
        children: [
          {
            path: 'overview',
            name: 'A2aHubOverview',
            component: () => import('@/views/a2a-hub/A2aHubOverview.vue'),
            meta: { title: 'A2A 互联中心 · 总览', activeMenu: '/a2a-hub', layoutMode: 'standard' },
          },
          {
            path: 'publications',
            name: 'A2aHubPublications',
            component: () => import('@/views/a2a-hub/publications/A2aPublicationList.vue'),
            meta: { title: 'A2A 互联中心 · 本地发布', activeMenu: '/a2a-hub', layoutMode: 'standard' },
          },
          {
            path: 'remote-agents',
            name: 'A2aHubRemoteAgents',
            component: () => import('@/views/a2a-hub/remote-agents/A2aRemoteAgentCatalog.vue'),
            meta: { title: 'A2A 互联中心 · 远程 Agent', activeMenu: '/a2a-hub', layoutMode: 'standard' },
          },
          {
            path: 'tasks',
            name: 'A2aHubTasks',
            component: () => import('@/views/a2a-hub/tasks/A2aTaskCenter.vue'),
            meta: { title: 'A2A 互联中心 · 任务中心', activeMenu: '/a2a-hub', layoutMode: 'standard' },
          },
          {
            path: 'trust',
            name: 'A2aHubTrust',
            component: () => import('@/views/a2a-hub/trust/A2aTrustWorkspace.vue'),
            meta: { title: 'A2A 互联中心 · 信任与策略', activeMenu: '/a2a-hub', layoutMode: 'standard' },
          },
          {
            path: 'developer',
            name: 'A2aHubDeveloper',
            component: () => import('@/views/a2a-hub/developer/A2aDeveloperConsole.vue'),
            meta: { title: 'A2A 互联中心 · 开发与诊断', activeMenu: '/a2a-hub', layoutMode: 'standard' },
          },
        ],
      },

      // ── 设置 / 护栏 ──
      {
        path: 'settings/personal-memory',
        name: 'PersonalMemory',
        component: () => import('@/views/settings/PersonalMemory.vue'),
        meta: { title: '我的记忆', layoutMode: 'standard' },
      },
      {
        path: 'settings/memory-erasure',
        name: 'MemoryErasure',
        component: () => import('@/views/settings/MemoryErasure.vue'),
        meta: {
          title: '跨域记忆擦除',
          layoutMode: 'standard',
          requiredPermissions: [PLATFORM_PERMISSION_MEMORY_ERASURE_MANAGE],
        },
      },
      {
        path: 'settings/platform-users',
        name: 'PlatformUserSettings',
        component: () => import('@/views/settings/PlatformUserSettings.vue'),
        meta: {
          title: '账号与权限',
          layoutMode: 'standard',
          requiredPermissions: [PLATFORM_PERMISSION_ADMIN],
        },
      },
      {
        path: 'settings/business-users',
        name: 'BusinessUserDirectory',
        component: () => import('@/views/settings/BusinessUserDirectory.vue'),
        meta: {
          title: '业务用户目录',
          layoutMode: 'standard',
          requiredPermissions: [PLATFORM_PERMISSION_BUSINESS_USER_READ],
        },
      },
      {
        path: 'settings/auth-providers',
        name: 'AuthProviderSettings',
        component: () => import('@/views/settings/AuthProviderSettings.vue'),
        meta: {
          title: '认证源配置',
          layoutMode: 'standard',
          requiredPermissions: [PLATFORM_PERMISSION_ADMIN],
        },
      },
      {
        path: 'settings/tool-acl',
        name: 'ToolAclList',
        component: () => import('@/views/settings/ToolAclList.vue'),
        meta: { title: '调用权限', layoutMode: 'standard' },
      },

      // ── 治理 / 领域 ──
      {
        path: 'domain',
        name: 'DomainList',
        component: () => import('@/views/domain/DomainList.vue'),
        meta: { title: '领域定义', layoutMode: 'standard' },
      },
      {
        path: 'domain/board',
        name: 'DomainAssignmentBoard',
        component: () => import('@/views/domain/DomainAssignmentBoard.vue'),
        meta: { title: '领域归属画布', layoutMode: 'standard' },
      },
      {
        path: 'domain/classifier-test',
        name: 'DomainClassifierTest',
        component: () => import('@/views/domain/DomainClassifierTest.vue'),
        meta: { title: '分类器测试', layoutMode: 'standard' },
      },
      {
        path: 'context/governance',
        name: 'ContextGovernance',
        component: () => import('@/views/context/ContextGovernance.vue'),
        meta: { title: '上下文治理', layoutMode: 'standard' },
      },
    ],
  },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

router.beforeEach(async (to) => {
  const metaTitle = to.meta.title
  const resolvedTitle = typeof metaTitle === 'function' ? metaTitle(to) : metaTitle
  document.title = `${typeof resolvedTitle === 'string' ? resolvedTitle : ''} - 睿池 ReachAI`
  return resolvePlatformNavigation(to)
})

export default router
