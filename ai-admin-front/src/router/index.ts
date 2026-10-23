import { createRouter, createWebHistory } from 'vue-router'
import type { RouteLocationNormalizedLoaded, RouteRecordRaw } from 'vue-router'
import {
  PLATFORM_PERMISSION_ADMIN,
  PLATFORM_PERMISSION_A2A_HUB_READ,
  PLATFORM_PERMISSION_AGENT_DEBUG,
  PLATFORM_PERMISSION_AGENT_EVALUATE,
  PLATFORM_PERMISSION_AGENT_READ,
  PLATFORM_PERMISSION_AGENT_WRITE,
  PLATFORM_PERMISSION_AUTOMATION_READ,
  PLATFORM_PERMISSION_BUSINESS_USER_READ,
  PLATFORM_PERMISSION_KNOWLEDGE_READ,
  PLATFORM_PERMISSION_MCP_HUB_READ,
  PLATFORM_PERMISSION_MEMORY_ERASURE_MANAGE,
  PLATFORM_PERMISSION_MODEL_READ,
  PLATFORM_PERMISSION_RUNTIME_USER_MAPPING_MANAGE,
  PLATFORM_PERMISSION_READ,
  PLATFORM_PERMISSION_RUNOPS_READ,
  PLATFORM_PERMISSION_SKILL_READ,
  PLATFORM_PERMISSION_TOOL_ACL_READ,
  PLATFORM_PERMISSION_WORKFLOW_READ,
  PLATFORM_PERMISSION_WORKFLOW_WRITE,
} from '@/auth/platformAccess'
import { resolvePlatformNavigation } from '@/auth/platformNavigation'
import { platformSessionUser } from '@/auth/platformSession'
import { firstRunCenterPath } from '@/views/runops/runCenterTabs'
import { BUSINESS_CAPABILITY_SCOPE_QUERY_KEYS, businessCapabilityWorkspaceLocation, legacyCatalogQuery } from '@/views/capability/businessCapabilityRoutes'

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

export const routes: RouteRecordRaw[] = [
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
        meta: { title: '概览', layoutMode: 'standard', hideBreadcrumb: true, compactSidebarOnNarrow: true },
      },

      // ── Agent 管理 ──
      {
        path: 'agent',
        name: 'AgentList',
        component: () => import('@/views/agent/AgentList.vue'),
        meta: {
          breadcrumb: [{"title":"AI 资产"},{"title":"Agent"}],
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
            { title: 'AI 资产' },
            { title: 'Agent', to: { path: '/agent' } },
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
            { title: 'AI 资产' },
            { title: 'Agent', to: { path: '/agent' } },
            { title: 'Agent 调试' },
          ],
        },
      },
      {
        path: 'agent/:id/evals',
        redirect: to => ({ path: `/runops/evals/${to.params.id}`, query: to.query }),
        meta: { layoutMode: 'standard' },
      },
      {
        path: 'skill-market',
        name: 'SkillMarket',
        component: () => import('@/views/skill-market/SkillMarket.vue'),
        meta: { breadcrumb: [{"title":"AI 资产"},{"title":"Skill","to":{"path":"/skills"}},{"title":"Skill 市场"}], title: 'Skill 市场', layoutMode: 'standard', requiredPermissions: [PLATFORM_PERMISSION_SKILL_READ] },
      },
      {
        path: 'skills',
        name: 'SkillCenter',
        component: () => import('@/views/skill/SkillCenter.vue'),
        meta: { breadcrumb: [{"title":"AI 资产"},{"title":"Skill"}], title: 'Skill 管理', layoutMode: 'standard', requiredPermissions: [PLATFORM_PERMISSION_SKILL_READ] },
      },
      {
        path: 'workflows',
        name: 'WorkflowList',
        component: () => import('@/views/workflow/WorkflowList.vue'),
        meta: {
          breadcrumb: [{"title":"AI 资产"},{"title":"Workflow"}],
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
        redirect: to => `/workflows/${to.params.workflowId}/studio`,
        meta: { layoutMode: 'studio' },
      },
      {
        path: 'runops',
        name: 'RunOpsList',
        component: () => import('@/views/runops/RunOpsList.vue'),
        meta: {
          title: '运行中心 · 运行记录',
          layoutMode: 'standard',
          activeMenu: '/run-center',
          breadcrumb: [{ title: '运行与治理' }, { title: '运行中心' }, { title: '运行记录' }],
          requiredPermissions: [PLATFORM_PERMISSION_RUNOPS_READ],
        },
      },
      // ── 运行中心：对话 / 运行记录 / 评测 共用一个入口，页签见 views/runops/runCenterTabs.ts ──
      {
        path: 'run-center',
        redirect: () => firstRunCenterPath(platformSessionUser.value?.permissions),
        meta: { layoutMode: 'standard' },
      },
      {
        path: 'runops/conversations',
        name: 'RunConversations',
        component: () => import('@/views/runops/RunConversations.vue'),
        meta: {
          title: '运行中心 · 对话',
          layoutMode: 'standard',
          activeMenu: '/run-center',
          breadcrumb: [{ title: '运行与治理' }, { title: '运行中心' }, { title: '对话' }],
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
          activeMenu: '/automations',
          breadcrumb: [{ title: '运行与治理' }, { title: '自动化中心' }],
          requiredPermissions: [PLATFORM_PERMISSION_AUTOMATION_READ],
        },
      },
      {
        path: 'runops/automations',
        redirect: to => ({ path: '/automations', query: to.query, hash: to.hash }),
        meta: { layoutMode: 'standard' },
      },
      {
        path: 'runops/evals',
        name: 'EvalOverview',
        component: () => import('@/views/runops/EvalOverview.vue'),
        meta: {
          title: '运行中心 · 评测',
          layoutMode: 'standard',
          activeMenu: '/run-center',
          breadcrumb: [{ title: '运行与治理' }, { title: '运行中心' }, { title: '评测' }],
          requiredPermissions: [PLATFORM_PERMISSION_AGENT_EVALUATE],
        },
      },
      {
        path: 'runops/evals/:id',
        name: 'AgentEval',
        component: () => import('@/views/runops/AgentEval.vue'),
        meta: {
          title: 'Agent 评测',
          layoutMode: 'standard',
          activeMenu: '/run-center',
          breadcrumb: [{ title: '运行与治理' }, { title: '运行中心' }, { title: '评测', to: '/runops/evals' }, { title: 'Agent 评测' }],
          requiredPermissions: [PLATFORM_PERMISSION_AGENT_EVALUATE],
        },
      },
      {
        path: 'runops/:traceId',
        name: 'RunOpsDetail',
        component: () => import('@/views/runops/RunOpsDetail.vue'),
        meta: {
          title: '运行详情',
          layoutMode: 'standard',
          activeMenu: '/run-center',
          breadcrumb: [{ title: '运行与治理' }, { title: '运行中心' }, { title: '运行记录', to: '/runops' }, { title: '运行详情' }],
          requiredPermissions: [PLATFORM_PERMISSION_RUNOPS_READ],
        },
      },

      // ── 知识管理 ──
      {
        path: 'knowledge',
        name: 'KnowledgeList',
        component: () => import('@/views/KnowledgeList.vue'),
        meta: { breadcrumb: [{"title":"AI 资产"},{"title":"知识库"}], title: '知识库管理', layoutMode: 'standard', requiredPermissions: [PLATFORM_PERMISSION_KNOWLEDGE_READ] },
      },
      {
        // 文件入库并入知识库详情的「文件」页签；旧链接带 ?kb= 时直接打开该知识库。
        path: 'knowledge/import',
        redirect: (to) => typeof to.query.kb === 'string' && to.query.kb
          ? { path: `/knowledge/${to.query.kb}`, query: { tab: 'files' } }
          : { path: '/knowledge' },
        meta: { layoutMode: 'standard' },
      },
      {
        path: 'knowledge/:code',
        name: 'KnowledgeDetail',
        component: () => import('@/views/KnowledgeDetail.vue'),
        meta: { breadcrumb: [{"title":"AI 资产"},{"title":"知识库","to":{"path":"/knowledge"}},{"title":"知识库详情"}], title: '知识库详情', layoutMode: 'standard', requiredPermissions: [PLATFORM_PERMISSION_KNOWLEDGE_READ] },
      },
      {
        path: 'knowledge/:code/file/:fileId',
        name: 'FileDetail',
        component: () => import('@/views/FileDetail.vue'),
        meta: { breadcrumb: [{"title":"AI 资产"},{"title":"知识库","to":{"path":"/knowledge"}},{"title":"文件详情"}], title: '文件详情', layoutMode: 'standard', requiredPermissions: [PLATFORM_PERMISSION_KNOWLEDGE_READ] },
      },
      {
        // 知识检索测试并入知识库详情的「检索测试」页签。
        path: 'retrieval',
        redirect: (to) => typeof to.query.kb === 'string' && to.query.kb
          ? { path: `/knowledge/${to.query.kb}`, query: { tab: 'retrieval' } }
          : { path: '/knowledge' },
        meta: { layoutMode: 'standard' },
      },
      {
        path: 'biz-index',
        name: 'BizIndexList',
        component: () => import('@/views/BizIndexList.vue'),
        meta: { breadcrumb: [{"title":"智能化改造"},{"title":"项目管理","to":{"path":"/registry/projects"}},{"title":"业务索引"}], title: '业务索引管理', layoutMode: 'standard', requiredPermissions: [PLATFORM_PERMISSION_KNOWLEDGE_READ] },
      },
      {
        path: 'biz-index/:code',
        name: 'BizIndexDetail',
        component: () => import('@/views/BizIndexDetail.vue'),
        meta: { breadcrumb: [{"title":"智能化改造"},{"title":"项目管理","to":{"path":"/registry/projects"}},{"title":"业务索引","to":{"path":"/biz-index"}},{"title":"索引详情"}], title: '索引详情', layoutMode: 'standard', requiredPermissions: [PLATFORM_PERMISSION_KNOWLEDGE_READ] },
      },

      // ── 模型管理 ──
      {
        path: 'model/instances',
        name: 'ModelInstances',
        component: () => import('@/views/model/ModelInstances.vue'),
        meta: { breadcrumb: [{"title":"平台资源"},{"title":"模型中心"}], title: '模型中心', layoutMode: 'standard', activeMenu: '/model/instances', requiredPermissions: [PLATFORM_PERMISSION_MODEL_READ] },
      },
      {
        path: 'model/instances/:id',
        name: 'ModelInstanceDetail',
        component: () => import('@/views/model/ModelInstanceDetail.vue'),
        meta: {
          breadcrumb: [{"title":"平台资源"},{"title":"模型中心","to":{"path":"/model/instances"}},{"title":"模型详情"}],
          title: '模型详情',
          layoutMode: 'standard',
          activeMenu: '/model/instances',
          requiredPermissions: [PLATFORM_PERMISSION_MODEL_READ],
        },
      },
      {
        path: 'model/playground',
        name: 'ModelPlayground',
        component: () => import('@/views/model/ModelPlayground.vue'),
        meta: { breadcrumb: [{"title":"平台资源"},{"title":"模型调试台"}], title: '模型调试台', layoutMode: 'edge-to-edge', requiredPermissions: [PLATFORM_PERMISSION_MODEL_READ] },
      },

      // ── 外部 API 市场 ──
      {
        path: 'api-market',
        name: 'ApiMarket',
        component: () => import('@/views/api-market/ApiMarket.vue'),
        meta: { breadcrumb: [{"title":"AI 资产"},{"title":"集成与开放"},{"title":"API 市场"}],
                title: 'API 市场', layoutMode: 'standard', requiredPermissions: [PLATFORM_PERMISSION_READ],
          projectScope: { permission: PLATFORM_PERMISSION_READ, resourceLabel: 'API 接入', requiresProjectCode: true } },
      },

      {
        path: 'business-capabilities',
        name: 'BusinessCapabilityWorkbench',
        component: () => import('@/views/capability/BusinessCapabilityWorkbench.vue'),
        meta: {
          title: '业务能力',
          rootViewKey: 'business-capability-workbench',
          compactSidebarOnNarrow: true,
          layoutMode: 'standard',
          requiredPermissions: [PLATFORM_PERMISSION_READ],
          projectScope: {
            permission: PLATFORM_PERMISSION_READ,
            resourceLabel: '业务能力',
            requiresProjectCode: false,
            resetQueryKeysOnScopeChange: BUSINESS_CAPABILITY_SCOPE_QUERY_KEYS,
          },
          activeMenu: '/business-capabilities',
          breadcrumb: [{ title: 'AI 资产' }, { title: '业务能力' }],
        },
        children: [
          { path: '', name: 'BusinessCapabilityEntry', redirect: to => ({ path: '/business-capabilities/java-methods', query: to.query }) },
          {
            path: ':category(java-methods|http-apis)/groups',
            name: 'BusinessCapabilityLegacyGroups',
            redirect: to => businessCapabilityWorkspaceLocation(to.params.category === 'http-apis' ? 'http-apis' : 'java-methods', 'groups', to.query),
          },
          {
            path: ':category(java-methods|http-apis)/:workspace(sources|semantics|graph)',
            name: 'BusinessCapabilitySourceWorkbench',
            component: () => import('@/views/capability/source/BusinessCapabilitySourceWorkbench.vue'),
            meta: { title: '业务能力工具',
              projectScope: { permission: PLATFORM_PERMISSION_READ, resourceLabel: '业务能力', requiresProjectCode: false,
                resetQueryKeysOnScopeChange: BUSINESS_CAPABILITY_SCOPE_QUERY_KEYS },
              breadcrumb: [{ title: 'AI 资产' }, { title: '业务能力' }, { title: (route: RouteLocationNormalizedLoaded) =>
                ({ sources: '接入与变更', semantics: '批量整理说明', graph: '关联分析' }[String(route.params.workspace)] || '能力目录') }] },
          },
          {
            path: 'java-methods', name: 'BusinessMethodCatalog',
            component: () => import('@/views/capability/BusinessMethodCatalog.vue'),
            meta: { title: 'Java 业务方法 · 业务能力',
              breadcrumb: [{ title: 'AI 资产' }, { title: '业务能力' }, { title: 'Java 业务方法' }] },
          },
          {
            path: 'http-apis', name: 'HttpApiCatalog',
            component: () => import('@/views/api/HttpApiCatalog.vue'),
            meta: { title: 'HTTP API · 业务能力',
              projectScope: { permission: PLATFORM_PERMISSION_READ, resourceLabel: 'HTTP API', requiresProjectCode: true,
                resetQueryKeysOnScopeChange: BUSINESS_CAPABILITY_SCOPE_QUERY_KEYS },
              breadcrumb: [{ title: 'AI 资产' }, { title: '业务能力' }, { title: 'HTTP API' }] },
          },
        ],
      },
      {
        path: 'business-methods',
        meta: { layoutMode: 'standard' },
        redirect: to => ({ path: '/business-capabilities/java-methods', query: legacyCatalogQuery(to.query, 'method') }),
      },
      {
        path: 'apis',
        meta: { layoutMode: 'standard' },
        redirect: to => ({ path: '/business-capabilities/http-apis', query: legacyCatalogQuery(to.query, 'api') }),
      },
      {
        path: 'business-capabilities/http-apis/:id([1-9]\\d*)',
        name: 'HttpApiDetail',
        component: () => import('@/views/api/HttpApiDetail.vue'),
        meta: {
          title: 'HTTP API 详情', layoutMode: 'standard', requiredPermissions: [PLATFORM_PERMISSION_READ],
          compactSidebarOnNarrow: true,
          projectScope: { permission: PLATFORM_PERMISSION_READ, resourceLabel: 'HTTP API', requiresProjectCode: true,
            resetQueryKeysOnScopeChange: BUSINESS_CAPABILITY_SCOPE_QUERY_KEYS },
          activeMenu: '/business-capabilities',
          breadcrumb: [{ title: 'AI 资产' }, { title: '业务能力', to: (route: RouteLocationNormalizedLoaded) => ({ path: '/business-capabilities/http-apis', query: route.query }) }, { title: 'HTTP API 详情' }],
        },
      },
      {
        path: 'apis/:id([1-9]\\d*)',
        meta: { layoutMode: 'standard' },
        redirect: to => ({ path: `/business-capabilities/http-apis/${to.params.id}`, query: legacyCatalogQuery(to.query, 'api') }),
      },
      {
        path: 'registry/projects',
        name: 'RegistryProjectList',
        component: () => import('@/views/registry/RegistryProjectList.vue'),
        meta: {
          requiredPermissions: [PLATFORM_PERMISSION_READ],
          title: '项目管理 · 项目列表',
          layoutMode: 'project-workbench',
          hideSidebarProjectPanel: true,
          activeMenu: '/registry/projects',
          breadcrumb: [{ title: '智能化改造' }, { title: '项目管理' }],
        },
      },
      {
        path: 'registry/projects/:projectCode/sync-diagnostics',
        meta: { layoutMode: 'project-workbench' },
        redirect: to => ({
          name: 'RegistryProjectDetail',
          params: { projectCode: to.params.projectCode },
          query: { section: 'source-changes' },
        }),
      },
      {
        path: 'registry/projects/:projectCode',
        name: 'RegistryProjectDetail',
        component: () => import('@/views/registry/RegistryProjectDetail.vue'),
        meta: {
          requiredPermissions: [PLATFORM_PERMISSION_READ],
          title: '项目管理 · 项目详情',
          layoutMode: 'project-workbench',
          hideSidebarProjectPanel: true,
          activeMenu: '/registry/projects',
          breadcrumb: [
            { title: '智能化改造' },
            { title: '项目管理', to: { path: '/registry/projects' } },
            { title: '项目详情' },
          ],
        },
      },
      {
        path: 'registry/projects/:projectCode/page-actions',
        name: 'EmbedOpsMonitor',
        component: () => import('@/views/settings/EmbedOpsMonitor.vue'),
        meta: {
          requiredPermissions: [PLATFORM_PERMISSION_READ],
          title: '前端页面管理',
          layoutMode: 'project-workbench',
          activeMenu: '/registry/projects',
          breadcrumb: [
            { title: '智能化改造' },
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
          requiredPermissions: [PLATFORM_PERMISSION_READ],
          title: '业务页面工作台',
          layoutMode: 'project-workbench',
          activeMenu: '/registry/projects',
          breadcrumb: [
            { title: '智能化改造' },
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
          requiredPermissions: [PLATFORM_PERMISSION_READ],
          title: '项目接入工作台',
          layoutMode: 'project-workbench',
          activeMenu: '/registry/projects',
          breadcrumb: [
            { title: '智能化改造' },
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
          requiredPermissions: [PLATFORM_PERMISSION_READ],
          title: '嵌入式会话审计',
          layoutMode: 'project-workbench',
          activeMenu: '/registry/projects',
          breadcrumb: [
            { title: '智能化改造' },
            { title: '项目管理', to: { path: '/registry/projects' } },
            { title: '项目详情', to: toProjectDetail },
            { title: '前端页面管理', to: toProjectPageActions },
            { title: '嵌入式会话审计' },
          ],
        },
      },
      // ── MCP 互联中心：独立产品模块 ──
      {
        path: 'mcp-hub',
        component: () => import('@/views/mcp-hub/McpHubLayout.vue'),
        redirect: '/mcp-hub/overview',
        meta: { activeMenu: '/mcp-hub', layoutMode: 'standard', requiredPermissions: [PLATFORM_PERMISSION_MCP_HUB_READ] },
        children: [
          {
            path: 'overview',
            name: 'McpHubOverview',
            component: () => import('@/views/mcp-hub/McpHubOverview.vue'),
            meta: { breadcrumb: [{"title":"AI 资产"},{"title":"集成与开放"},{"title":"MCP 互联中心","to":{"path":"/mcp-hub/overview"}},{"title":"总览"}], title: 'MCP 互联中心 · 总览', activeMenu: '/mcp-hub', layoutMode: 'standard' },
          },
          {
            path: 'publications',
            name: 'McpHubPublications',
            component: () => import('@/views/mcp-hub/McpPublicationList.vue'),
            meta: { breadcrumb: [{"title":"AI 资产"},{"title":"集成与开放"},{"title":"MCP 互联中心","to":{"path":"/mcp-hub/overview"}},{"title":"对外发布"}], title: 'MCP 互联中心 · 对外发布', activeMenu: '/mcp-hub', layoutMode: 'standard' },
          },
          {
            path: 'publications/:id',
            name: 'McpHubPublicationDetail',
            component: () => import('@/views/mcp-hub/McpPublicationDetail.vue'),
            meta: { breadcrumb: [{"title":"AI 资产"},{"title":"集成与开放"},{"title":"MCP 互联中心","to":{"path":"/mcp-hub/overview"}},{"title":"发布详情"}], title: 'MCP 互联中心 · 发布详情', activeMenu: '/mcp-hub', layoutMode: 'standard' },
          },
          {
            path: 'call-logs',
            name: 'McpHubCallLogs',
            component: () => import('@/views/mcp-hub/McpCallLogList.vue'),
            meta: { breadcrumb: [{"title":"AI 资产"},{"title":"集成与开放"},{"title":"MCP 互联中心","to":{"path":"/mcp-hub/overview"}},{"title":"调用流水"}], title: 'MCP 互联中心 · 调用流水', activeMenu: '/mcp-hub', layoutMode: 'standard' },
          },
        ],
      },

      // ── A2A 互联中心：独立产品模块 ──
      {
        path: 'a2a-hub',
        component: () => import('@/views/a2a-hub/A2aHubLayout.vue'),
        redirect: '/a2a-hub/overview',
        meta: { activeMenu: '/a2a-hub', layoutMode: 'standard', requiredPermissions: [PLATFORM_PERMISSION_A2A_HUB_READ] },
        children: [
          {
            path: 'overview',
            name: 'A2aHubOverview',
            component: () => import('@/views/a2a-hub/A2aHubOverview.vue'),
            meta: { breadcrumb: [{"title":"AI 资产"},{"title":"集成与开放"},{"title":"A2A 互联中心","to":{"path":"/a2a-hub/overview"}},{"title":"总览"}], title: 'A2A 互联中心 · 总览', activeMenu: '/a2a-hub', layoutMode: 'standard' },
          },
          {
            path: 'publications',
            name: 'A2aHubPublications',
            component: () => import('@/views/a2a-hub/publications/A2aPublicationList.vue'),
            meta: { breadcrumb: [{"title":"AI 资产"},{"title":"集成与开放"},{"title":"A2A 互联中心","to":{"path":"/a2a-hub/overview"}},{"title":"本地发布"}], title: 'A2A 互联中心 · 本地发布', activeMenu: '/a2a-hub', layoutMode: 'standard' },
          },
          {
            path: 'remote-agents',
            name: 'A2aHubRemoteAgents',
            component: () => import('@/views/a2a-hub/remote-agents/A2aRemoteAgentCatalog.vue'),
            meta: { breadcrumb: [{"title":"AI 资产"},{"title":"集成与开放"},{"title":"A2A 互联中心","to":{"path":"/a2a-hub/overview"}},{"title":"远程 Agent"}], title: 'A2A 互联中心 · 远程 Agent', activeMenu: '/a2a-hub', layoutMode: 'standard' },
          },
          {
            path: 'tasks',
            name: 'A2aHubTasks',
            component: () => import('@/views/a2a-hub/tasks/A2aTaskCenter.vue'),
            meta: { breadcrumb: [{"title":"AI 资产"},{"title":"集成与开放"},{"title":"A2A 互联中心","to":{"path":"/a2a-hub/overview"}},{"title":"任务中心"}], title: 'A2A 互联中心 · 任务中心', activeMenu: '/a2a-hub', layoutMode: 'standard' },
          },
          {
            path: 'trust',
            name: 'A2aHubTrust',
            component: () => import('@/views/a2a-hub/trust/A2aTrustWorkspace.vue'),
            meta: { breadcrumb: [{"title":"AI 资产"},{"title":"集成与开放"},{"title":"A2A 互联中心","to":{"path":"/a2a-hub/overview"}},{"title":"信任与策略"}], title: 'A2A 互联中心 · 信任与策略', activeMenu: '/a2a-hub', layoutMode: 'standard' },
          },
          {
            path: 'developer',
            name: 'A2aHubDeveloper',
            component: () => import('@/views/a2a-hub/developer/A2aDeveloperConsole.vue'),
            meta: { breadcrumb: [{"title":"AI 资产"},{"title":"集成与开放"},{"title":"A2A 互联中心","to":{"path":"/a2a-hub/overview"}},{"title":"开发与诊断"}], title: 'A2A 互联中心 · 开发与诊断', activeMenu: '/a2a-hub', layoutMode: 'standard' },
          },
        ],
      },

      // ── 设置 / 护栏 ──
      // ── 个人中心 ──
      {
        path: 'me/memories',
        name: 'MyMemories',
        component: () => import('@/views/me/MyMemories.vue'),
        meta: { breadcrumb: [{"title":"个人"},{"title":"我的记忆"}], title: '我的记忆', layoutMode: 'standard' },
      },
      { path: 'settings/personal-memory', redirect: '/me/memories', meta: { layoutMode: 'standard' } },
      {
        path: 'settings/runtime-user-mappings',
        name: 'RuntimeUserMapping',
        component: () => import('@/views/settings/RuntimeUserMapping.vue'),
        meta: {
          breadcrumb: [{"title":"运行与治理"},{"title":"身份与权限"},{"title":"运行时用户映射"}],
          title: '运行时用户映射',
          layoutMode: 'standard',
          requiredPermissions: [PLATFORM_PERMISSION_RUNTIME_USER_MAPPING_MANAGE],
        },
      },
      {
        path: 'compliance/erasure',
        name: 'DataErasure',
        component: () => import('@/views/compliance/DataErasure.vue'),
        meta: {
          breadcrumb: [{"title":"运行与治理"},{"title":"合规"},{"title":"跨域数据擦除"}],
          title: '跨域数据擦除',
          layoutMode: 'standard',
          requiredPermissions: [PLATFORM_PERMISSION_MEMORY_ERASURE_MANAGE],
        },
      },
      {
        path: 'settings/platform-users',
        name: 'PlatformUserSettings',
        component: () => import('@/views/settings/PlatformUserSettings.vue'),
        meta: {
          breadcrumb: [{"title":"运行与治理"},{"title":"身份与权限"},{"title":"账号与权限"}],
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
          breadcrumb: [{"title":"运行与治理"},{"title":"身份与权限"},{"title":"业务用户目录"}],
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
          breadcrumb: [{"title":"运行与治理"},{"title":"身份与权限"},{"title":"认证源配置"}],
          title: '认证源配置',
          layoutMode: 'standard',
          requiredPermissions: [PLATFORM_PERMISSION_ADMIN],
        },
      },
      {
        path: 'settings/tool-acl',
        name: 'ToolAclList',
        component: () => import('@/views/settings/ToolAclList.vue'),
        meta: { breadcrumb: [{"title":"运行与治理"},{"title":"身份与权限"},{"title":"调用权限"}], title: '调用权限', layoutMode: 'standard', requiredPermissions: [PLATFORM_PERMISSION_TOOL_ACL_READ] },
      },
    ],
  },
  // 已删除页面的旧书签和拼错的地址回到概览，不再显示空白页。
  { path: '/:pathMatch(.*)*', name: 'NotFound', redirect: '/dashboard' },
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
