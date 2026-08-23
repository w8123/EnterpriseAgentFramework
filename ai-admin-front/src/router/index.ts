import { createRouter, createWebHistory } from 'vue-router'
import type { RouteLocationNormalizedLoaded, RouteRecordRaw } from 'vue-router'
import { resolvePlatformNavigation } from '@/auth/platformNavigation'

/** 项目详情动态面包屑目标：从当前路由取 projectCode。 */
const toProjectDetail = (route: RouteLocationNormalizedLoaded) => ({
  path: `/registry/projects/${route.params.projectCode}`,
})

const toProjectPageActions = (route: RouteLocationNormalizedLoaded) => ({
  path: `/registry/projects/${route.params.projectCode}/page-actions`,
})

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
        meta: { title: 'Agent', layoutMode: 'standard' },
      },
      {
        path: 'agent/:id/edit',
        name: 'AgentEdit',
        component: () => import('@/views/agent/AgentEdit.vue'),
        meta: { title: 'Agent 编辑', layoutMode: 'standard' },
      },
      {
        path: 'agent/:id/debug',
        name: 'AgentDebug',
        component: () => import('@/views/agent/AgentDebug.vue'),
        meta: {
          title: 'Agent 调试',
          layoutMode: 'edge-to-edge',
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
        meta: { title: 'Agent 评测', layoutMode: 'standard' },
      },
      {
        path: 'workflows',
        name: 'WorkflowList',
        component: () => import('@/views/workflow/WorkflowList.vue'),
        meta: { title: 'Workflow 编排', layoutMode: 'project-workbench' },
      },
      {
        path: 'workflows/:workflowId/studio',
        name: 'WorkflowStudio',
        component: () => import('@/views/workflow/WorkflowStudio.vue'),
        meta: { title: 'Workflow 编排 Studio', layoutMode: 'studio' },
      },
      {
        path: 'workflows/:workflowId/versions',
        name: 'WorkflowVersions',
        component: () => import('@/views/workflow/WorkflowVersions.vue'),
        meta: { title: 'Workflow 版本', layoutMode: 'standard' },
      },
      {
        path: 'runops',
        name: 'RunOpsList',
        component: () => import('@/views/runops/RunOpsList.vue'),
        meta: { title: '运行中心', layoutMode: 'standard' },
      },
      {
        path: 'runops/:traceId',
        name: 'RunOpsDetail',
        component: () => import('@/views/runops/RunOpsDetail.vue'),
        meta: { title: '运行详情', layoutMode: 'standard' },
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

      // ── Tool 管理 ──
      {
        path: 'tool',
        name: 'ToolList',
        component: () => import('@/views/tool/ToolList.vue'),
        meta: { title: 'Tool', layoutMode: 'standard' },
      },
      {
        path: 'tool/retrieval',
        name: 'ToolRetrievalTest',
        component: () => import('@/views/tool/ToolRetrievalTest.vue'),
        meta: { title: 'Tool 检索测试', layoutMode: 'standard' },
      },
      {
        path: 'capability',
        name: 'CapabilityKernel',
        component: () => import('@/views/capability/CapabilityKernel.vue'),
        meta: { title: '能力', layoutMode: 'standard' },
      },
      {
        path: 'capability/review',
        name: 'CapabilityReview',
        component: () => import('@/views/registry/CapabilitySyncDebug.vue'),
        meta: {
          title: '能力变更评审',
          layoutMode: 'standard',
          activeMenu: '/capability/review',
          breadcrumb: [{ title: '能力内核' }, { title: '能力变更评审' }],
        },
      },
      {
        path: 'capability/sync-snapshot',
        name: 'CapabilitySyncSnapshot',
        component: () => import('@/views/registry/CapabilitySyncDebug.vue'),
        meta: {
          title: '同步能力快照',
          layoutMode: 'standard',
          activeMenu: '/capability/sync-snapshot',
          breadcrumb: [{ title: '能力内核' }, { title: '同步能力快照' }],
        },
      },
      {
        path: 'capability/tools',
        name: 'CapabilityKernelTools',
        component: () => import('@/views/capability/CapabilityKernel.vue'),
        meta: { title: '模块工具', layoutMode: 'standard' },
      },
      {
        path: 'capability/compositions',
        name: 'CapabilityKernelCompositions',
        component: () => import('@/views/capability/CapabilityKernel.vue'),
        meta: { title: '组合', layoutMode: 'standard' },
      },
      {
        path: 'capability/interactions',
        name: 'CapabilityKernelInteractions',
        component: () => import('@/views/capability/CapabilityKernel.vue'),
        meta: { title: '交互', layoutMode: 'standard' },
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
        component: () => import('@/views/registry/CapabilitySyncDebug.vue'),
        meta: {
          title: '能力变更评审',
          layoutMode: 'standard',
          activeMenu: '/capability/review',
          breadcrumb: [
            { title: '能力内核' },
            { title: '能力变更评审' },
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

      // ── 对外开放 / MCP ──
      {
        path: 'mcp/visibility',
        name: 'McpVisibilityBoard',
        component: () => import('@/views/mcp/McpVisibilityBoard.vue'),
        meta: { title: '对外开放 · MCP 白名单', layoutMode: 'standard' },
      },
      {
        path: 'mcp/clients',
        name: 'McpClientList',
        component: () => import('@/views/mcp/McpClientList.vue'),
        meta: { title: '对外开放 · MCP Client', layoutMode: 'standard' },
      },
      {
        path: 'mcp/monitor',
        name: 'McpCallMonitor',
        component: () => import('@/views/mcp/McpCallMonitor.vue'),
        meta: { title: '对外开放 · MCP 调用流水', layoutMode: 'standard' },
      },
      {
        path: 'mcp/onboarding',
        name: 'McpOnboarding',
        component: () => import('@/views/mcp/McpOnboarding.vue'),
        meta: { title: '对外开放 · MCP 接入向导', layoutMode: 'standard' },
      },

      // ── 对外开放 / A2A ──
      {
        path: 'a2a/endpoints',
        name: 'A2aEndpointList',
        component: () => import('@/views/a2a/A2aEndpointList.vue'),
        meta: { title: '对外开放 · A2A 暴露 Agent', layoutMode: 'standard' },
      },
      {
        path: 'a2a/monitor',
        name: 'A2aSessionMonitor',
        component: () => import('@/views/a2a/A2aSessionMonitor.vue'),
        meta: { title: '对外开放 · A2A 会话监控', layoutMode: 'standard' },
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
        meta: { title: '跨域记忆擦除', layoutMode: 'standard' },
      },
      {
        path: 'settings/platform-users',
        name: 'PlatformUserSettings',
        component: () => import('@/views/settings/PlatformUserSettings.vue'),
        meta: { title: '平台用户与角色', layoutMode: 'standard' },
      },
      {
        path: 'settings/business-users',
        name: 'BusinessUserDirectory',
        component: () => import('@/views/settings/BusinessUserDirectory.vue'),
        meta: { title: '业务用户目录', layoutMode: 'standard' },
      },
      {
        path: 'settings/auth-providers',
        name: 'AuthProviderSettings',
        component: () => import('@/views/settings/AuthProviderSettings.vue'),
        meta: { title: '认证源配置', layoutMode: 'standard' },
      },
      {
        path: 'settings/tool-acl',
        name: 'ToolAclList',
        component: () => import('@/views/settings/ToolAclList.vue'),
        meta: { title: 'Tool ACL', layoutMode: 'standard' },
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
  document.title = `${(to.meta.title as string) || ''} - 睿池 ReachAI`
  return resolvePlatformNavigation(to)
})

export default router
