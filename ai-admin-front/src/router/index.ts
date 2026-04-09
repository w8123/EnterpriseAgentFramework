import { createRouter, createWebHistory } from 'vue-router'
import type { RouteRecordRaw } from 'vue-router'

const routes: RouteRecordRaw[] = [
  {
    path: '/',
    component: () => import('@/views/layout/MainLayout.vue'),
    redirect: '/knowledge',
    children: [
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
    ],
  },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

router.beforeEach((to, _from, next) => {
  document.title = `${(to.meta.title as string) || ''} - AI 知识库管理系统`
  next()
})

export default router
