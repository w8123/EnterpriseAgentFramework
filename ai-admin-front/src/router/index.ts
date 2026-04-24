import { createRouter, createWebHistory } from 'vue-router'
import type { RouteRecordRaw } from 'vue-router'

const routes: RouteRecordRaw[] = [
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
        meta: { title: 'Agent 管理' },
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
        component: () => import('@/views/agent/AgentStudio.vue'),
        meta: { title: 'Agent Studio 画布' },
      },
      {
        path: 'agent/:id/versions',
        name: 'AgentVersions',
        component: () => import('@/views/agent/AgentVersions.vue'),
        meta: { title: 'Agent 版本管理' },
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
        path: 'model',
        name: 'ModelProvider',
        component: () => import('@/views/model/ModelProvider.vue'),
        meta: { title: 'Provider 管理' },
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
        meta: { title: 'Tool 管理' },
      },
      {
        path: 'tool/retrieval',
        name: 'ToolRetrievalTest',
        component: () => import('@/views/tool/ToolRetrievalTest.vue'),
        meta: { title: 'Tool 检索测试' },
      },
      {
        path: 'skill',
        name: 'SkillList',
        component: () => import('@/views/skill/SkillList.vue'),
        meta: { title: 'Skill 管理' },
      },
      {
        path: 'skill/mining',
        name: 'SkillMining',
        component: () => import('@/views/skill/SkillMining.vue'),
        meta: { title: 'Skill Mining' },
      },
      {
        path: 'scan-project',
        name: 'ScanProjectList',
        component: () => import('@/views/scan/ScanProjectList.vue'),
        meta: { title: '扫描项目' },
      },
      {
        path: 'scan-project/:id',
        name: 'ScanProjectDetail',
        component: () => import('@/views/scan/ScanProjectDetail.vue'),
        meta: { title: '扫描详情' },
      },

      // ── 设置 / 护栏 ──
      {
        path: 'settings/tool-acl',
        name: 'ToolAclList',
        component: () => import('@/views/settings/ToolAclList.vue'),
        meta: { title: 'Tool ACL' },
      },
    ],
  },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

router.beforeEach((to, _from, next) => {
  document.title = `${(to.meta.title as string) || ''} - AI 管理平台`
  next()
})

export default router
