import { createRouter, createWebHistory } from 'vue-router'
import type { RouteRecordRaw } from 'vue-router'
import { getPlatformToken } from '@/utils/platformAuth'

const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'Login',
    component: () => import('@/views/Login.vue'),
    meta: { title: 'Login', public: true },
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
        meta: { title: '概览' },
      },

      // ── Agent 管理 ──
      {
        path: 'agent',
        name: 'AgentList',
        component: () => import('@/views/agent/AgentList.vue'),
        meta: { title: 'Agent' },
      },
      {
        path: 'agent/:id/edit',
        name: 'AgentEdit',
        component: () => import('@/views/agent/AgentEdit.vue'),
        meta: { title: 'Agent 编辑' },
      },
      {
        path: 'agent/:id/debug',
        name: 'AgentDebug',
        component: () => import('@/views/agent/AgentDebug.vue'),
        meta: { title: 'Agent 调试' },
      },
      {
        path: 'agent/:id/studio',
        name: 'AgentStudio',
        component: () => import('@/views/agent/AgentStudioCompatibility.vue'),
        meta: { title: 'Agent Studio 画布' },
      },
      {
        path: 'agent/:id/versions',
        name: 'AgentVersions',
        component: () => import('@/views/agent/AgentVersions.vue'),
        meta: { title: 'AgentOps' },
      },
      {
        path: 'workflows',
        name: 'WorkflowList',
        component: () => import('@/views/workflow/WorkflowList.vue'),
        meta: { title: 'Workflows' },
      },
      {
        path: 'workflows/:workflowId/studio',
        name: 'WorkflowStudio',
        component: () => import('@/views/workflow/WorkflowStudio.vue'),
        meta: { title: 'Workflow Studio' },
      },
      {
        path: 'workflows/:workflowId/versions',
        name: 'WorkflowVersions',
        component: () => import('@/views/workflow/WorkflowVersions.vue'),
        meta: { title: 'Workflow Versions' },
      },
      {
        path: 'agents/:agentId/bindings',
        name: 'AgentWorkflowBindings',
        component: () => import('@/views/agent/AgentWorkflowBindings.vue'),
        meta: { title: 'Agent Workflow Bindings' },
      },
      {
        path: 'runops',
        name: 'RunOpsList',
        component: () => import('@/views/runops/RunOpsList.vue'),
        meta: { title: 'RunOps 运行中心' },
      },
      {
        path: 'runops/:traceId',
        name: 'RunOpsDetail',
        component: () => import('@/views/runops/RunOpsDetail.vue'),
        meta: { title: 'RunOps 运行详情' },
      },

      // ── 知识管理 ──
      {
        path: 'knowledge',
        name: 'KnowledgeList',
        component: () => import('@/views/KnowledgeList.vue'),
        meta: { title: '知识库管理' },
      },
      {
        path: 'knowledge/import',
        name: 'KnowledgeImport',
        component: () => import('@/views/KnowledgeImport.vue'),
        meta: { title: '文件入库' },
      },
      {
        path: 'knowledge/:code',
        name: 'KnowledgeDetail',
        component: () => import('@/views/KnowledgeDetail.vue'),
        meta: { title: '知识库详情' },
      },
      {
        path: 'knowledge/:code/file/:fileId',
        name: 'FileDetail',
        component: () => import('@/views/FileDetail.vue'),
        meta: { title: '文件详情' },
      },
      {
        path: 'retrieval',
        name: 'RetrievalTest',
        component: () => import('@/views/RetrievalTest.vue'),
        meta: { title: '检索测试' },
      },
      {
        path: 'biz-index',
        name: 'BizIndexList',
        component: () => import('@/views/BizIndexList.vue'),
        meta: { title: '业务索引管理' },
      },
      {
        path: 'biz-index/:code',
        name: 'BizIndexDetail',
        component: () => import('@/views/BizIndexDetail.vue'),
        meta: { title: '索引详情' },
      },

      // ── 模型管理 ──
      {
        path: 'model/instances',
        name: 'ModelInstances',
        component: () => import('@/views/model/ModelInstances.vue'),
        meta: { title: '模型实例' },
      },
      {
        path: 'model/playground',
        name: 'ModelPlayground',
        component: () => import('@/views/model/ModelPlayground.vue'),
        meta: { title: '模型调试台' },
      },

      // ── Tool 管理 ──
      {
        path: 'tool',
        name: 'ToolList',
        component: () => import('@/views/tool/ToolList.vue'),
        meta: { title: 'Tool' },
      },
      {
        path: 'tool/retrieval',
        name: 'ToolRetrievalTest',
        component: () => import('@/views/tool/ToolRetrievalTest.vue'),
        meta: { title: 'Tool 检索测试' },
      },
      {
        path: 'capability',
        name: 'CapabilityKernel',
        component: () => import('@/views/capability/CapabilityKernel.vue'),
        meta: { title: '能力' },
      },
      {
        path: 'capability/tools',
        name: 'CapabilityKernelTools',
        component: () => import('@/views/capability/CapabilityKernel.vue'),
        meta: { title: '模块工具' },
      },
      {
        path: 'capability/compositions',
        name: 'CapabilityKernelCompositions',
        component: () => import('@/views/capability/CapabilityKernel.vue'),
        meta: { title: '组合' },
      },
      {
        path: 'capability/interactions',
        name: 'CapabilityKernelInteractions',
        component: () => import('@/views/capability/CapabilityKernel.vue'),
        meta: { title: '交互' },
      },
      {
        path: 'capability/mining',
        name: 'CapabilityMining',
        component: () => import('@/views/capability/CapabilityMining.vue'),
        meta: { title: '能力挖掘' },
      },
      {
        path: 'capability/slot/extractors',
        name: 'SlotExtractorList',
        component: () => import('@/views/capability/slot/SlotExtractorList.vue'),
        meta: { title: '槽位提取器' },
      },
      {
        path: 'capability/slot/dict-dept',
        name: 'SlotDictDept',
        component: () => import('@/views/capability/slot/SlotDictDept.vue'),
        meta: { title: '部门字典' },
      },
      {
        path: 'capability/slot/dict-user',
        name: 'SlotDictUser',
        component: () => import('@/views/capability/slot/SlotDictUser.vue'),
        meta: { title: '人员字典' },
      },
      {
        path: 'capability/slot/logs',
        name: 'SlotExtractLogs',
        component: () => import('@/views/capability/slot/SlotExtractLogs.vue'),
        meta: { title: '槽位提取日志' },
      },
      { path: 'skill', redirect: '/capability/compositions' },
      { path: 'skill/mining', redirect: '/capability/mining' },
      { path: 'skill/slot/extractors', redirect: '/capability/slot/extractors' },
      { path: 'skill/slot/dict-dept', redirect: '/capability/slot/dict-dept' },
      { path: 'skill/slot/dict-user', redirect: '/capability/slot/dict-user' },
      { path: 'skill/slot/logs', redirect: '/capability/slot/logs' },
      {
        path: 'registry/projects',
        name: 'RegistryProjectList',
        component: () => import('@/views/registry/RegistryProjectList.vue'),
        meta: { title: '注册中心 · 项目管理' },
      },
      {
        path: 'registry/projects/:projectCode',
        name: 'RegistryProjectDetail',
        component: () => import('@/views/registry/RegistryProjectDetail.vue'),
        meta: { title: '注册中心 · 项目详情' },
      },
      {
        path: 'registry/api-assets',
        name: 'ApiAssetCatalog',
        component: () => import('@/views/registry/ApiAssetCatalog.vue'),
        meta: { title: '注册中心 · API 资产目录' },
      },
      {
        path: 'registry/capability-sync',
        name: 'CapabilitySyncDebug',
        component: () => import('@/views/registry/CapabilitySyncDebug.vue'),
        meta: { title: '能力变更评审' },
      },
      {
        path: 'registry/runtimes',
        name: 'RuntimeRegistry',
        component: () => import('@/views/registry/RuntimeRegistry.vue'),
        meta: { title: 'Runtime 纳管' },
      },
      {
        path: 'registry/projects/:projectCode/page-actions',
        name: 'EmbedOpsMonitor',
        component: () => import('@/views/settings/EmbedOpsMonitor.vue'),
        meta: {
          title: '前端页面管理',
          breadcrumb: [
            { title: '注册中心 · 项目详情' },
            { title: '前端页面管理' },
          ],
        },
      },
      {
        path: 'registry/projects/:projectCode/page-assistant',
        name: 'PageAssistantWizard',
        component: () => import('@/views/registry/PageAssistantWizard.vue'),
        meta: { title: '创建页面助手' },
      },
      {
        path: 'registry/projects/:projectCode/sdk-access',
        name: 'SdkAccessWizard',
        component: () => import('@/views/registry/SdkAccessWizard.vue'),
        meta: { title: 'SDK 接入向导' },
      },
      {
        path: 'registry/projects/:projectCode/page-actions/sessions',
        name: 'EmbedSessionAudit',
        component: () => import('@/views/settings/EmbedSessionAudit.vue'),
        meta: { title: '嵌入式会话审计' },
      },
      {
        path: 'scan-project',
        name: 'ScanProjectList',
        redirect: '/registry/api-assets',
        meta: { title: '项目与 API 接入' },
      },
      {
        path: 'scan-project/:id',
        name: 'ScanProjectDetail',
        component: () => import('@/views/scan/ScanProjectDetail.vue'),
        meta: { title: 'API 目录' },
      },

      // ── 对外开放 / MCP ──
      {
        path: 'mcp/visibility',
        name: 'McpVisibilityBoard',
        component: () => import('@/views/mcp/McpVisibilityBoard.vue'),
        meta: { title: '对外开放 · MCP 白名单' },
      },
      {
        path: 'mcp/clients',
        name: 'McpClientList',
        component: () => import('@/views/mcp/McpClientList.vue'),
        meta: { title: '对外开放 · MCP Client' },
      },
      {
        path: 'mcp/monitor',
        name: 'McpCallMonitor',
        component: () => import('@/views/mcp/McpCallMonitor.vue'),
        meta: { title: '对外开放 · MCP 调用流水' },
      },
      {
        path: 'mcp/onboarding',
        name: 'McpOnboarding',
        component: () => import('@/views/mcp/McpOnboarding.vue'),
        meta: { title: '对外开放 · MCP 接入向导' },
      },

      // ── 对外开放 / A2A ──
      {
        path: 'a2a/endpoints',
        name: 'A2aEndpointList',
        component: () => import('@/views/a2a/A2aEndpointList.vue'),
        meta: { title: '对外开放 · A2A 暴露 Agent' },
      },
      {
        path: 'a2a/monitor',
        name: 'A2aSessionMonitor',
        component: () => import('@/views/a2a/A2aSessionMonitor.vue'),
        meta: { title: '对外开放 · A2A 会话监控' },
      },

      // ── 设置 / 护栏 ──
      {
        path: 'settings/platform-users',
        name: 'PlatformUserSettings',
        component: () => import('@/views/settings/PlatformUserSettings.vue'),
        meta: { title: '平台用户与角色' },
      },
      {
        path: 'settings/business-users',
        name: 'BusinessUserDirectory',
        component: () => import('@/views/settings/BusinessUserDirectory.vue'),
        meta: { title: '业务用户目录' },
      },
      {
        path: 'settings/auth-providers',
        name: 'AuthProviderSettings',
        component: () => import('@/views/settings/AuthProviderSettings.vue'),
        meta: { title: '认证源配置' },
      },
      {
        path: 'settings/tool-acl',
        name: 'ToolAclList',
        component: () => import('@/views/settings/ToolAclList.vue'),
        meta: { title: 'Tool ACL' },
      },

      // ── 治理 / 领域 ──
      {
        path: 'domain',
        name: 'DomainList',
        component: () => import('@/views/domain/DomainList.vue'),
        meta: { title: '领域定义' },
      },
      {
        path: 'domain/board',
        name: 'DomainAssignmentBoard',
        component: () => import('@/views/domain/DomainAssignmentBoard.vue'),
        meta: { title: '领域归属画布' },
      },
      {
        path: 'domain/classifier-test',
        name: 'DomainClassifierTest',
        component: () => import('@/views/domain/DomainClassifierTest.vue'),
        meta: { title: '分类器测试' },
      },
    ],
  },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

router.beforeEach((to, _from, next) => {
  document.title = `${(to.meta.title as string) || ''} - 睿池 ReachAI`
  if (!to.meta.public && !getPlatformToken()) {
    next({ path: '/login', query: { redirect: to.fullPath } })
    return
  }
  next()
})

export default router
