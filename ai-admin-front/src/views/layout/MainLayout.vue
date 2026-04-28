<template>
  <el-container class="main-layout">
    <el-aside width="220px" class="sidebar">
      <div class="logo">
        <el-icon :size="24"><Cpu /></el-icon>
        <span class="logo-text">AI 管理平台</span>
      </div>
      <el-scrollbar>
        <el-menu
          :default-active="activeMenu"
          router
          class="sidebar-menu"
          background-color="#1d2129"
          text-color="#ffffffa6"
          active-text-color="#ffffff"
          :default-openeds="['/knowledge-group', '/model-group']"
        >
          <el-menu-item index="/dashboard">
            <el-icon><DataAnalysis /></el-icon>
            <span>概览</span>
          </el-menu-item>

          <el-menu-item index="/agent">
            <el-icon><Cpu /></el-icon>
            <span>Agent 管理</span>
          </el-menu-item>

          <el-sub-menu index="/knowledge-group">
            <template #title>
              <el-icon><Collection /></el-icon>
              <span>知识管理</span>
            </template>
            <el-menu-item index="/knowledge">知识库管理</el-menu-item>
            <el-menu-item index="/knowledge/import">文件入库</el-menu-item>
            <el-menu-item index="/retrieval">检索测试</el-menu-item>
            <el-menu-item index="/biz-index">业务索引</el-menu-item>
          </el-sub-menu>

          <el-sub-menu index="/model-group">
            <template #title>
              <el-icon><Coin /></el-icon>
              <span>模型管理</span>
            </template>
            <el-menu-item index="/model">Provider 管理</el-menu-item>
            <el-menu-item index="/model/playground">模型调试台</el-menu-item>
          </el-sub-menu>

          <el-sub-menu index="/tool-group">
            <template #title>
              <el-icon><SetUp /></el-icon>
              <span>Tool 管理</span>
            </template>
            <el-menu-item index="/tool">Tool 列表</el-menu-item>
            <el-menu-item index="/tool/retrieval">Tool 检索测试</el-menu-item>
            <el-menu-item index="/skill">Skill 管理</el-menu-item>
            <el-menu-item index="/skill/mining">Skill Mining</el-menu-item>
          </el-sub-menu>

          <el-sub-menu index="/slot-group">
            <template #title>
              <el-icon><Aim /></el-icon>
              <span>槽位提取器</span>
            </template>
            <el-menu-item index="/skill/slot/extractors">提取器 + 测试台</el-menu-item>
            <el-menu-item index="/skill/slot/dict-dept">部门字典</el-menu-item>
            <el-menu-item index="/skill/slot/dict-user">人员字典</el-menu-item>
            <el-menu-item index="/skill/slot/logs">调用日志</el-menu-item>
          </el-sub-menu>

          <el-menu-item index="/scan-project">
            <el-icon><FolderOpened /></el-icon>
            <span>扫描项目</span>
          </el-menu-item>

          <el-sub-menu index="/domain-group">
            <template #title>
              <el-icon><Compass /></el-icon>
              <span>治理 / 领域</span>
            </template>
            <el-menu-item index="/domain">领域定义</el-menu-item>
            <el-menu-item index="/domain/board">归属画布</el-menu-item>
            <el-menu-item index="/domain/classifier-test">分类器测试</el-menu-item>
          </el-sub-menu>

          <el-sub-menu index="/mcp-group">
            <template #title>
              <el-icon><Connection /></el-icon>
              <span>对外开放 / MCP</span>
            </template>
            <el-menu-item index="/mcp/visibility">暴露白名单</el-menu-item>
            <el-menu-item index="/mcp/clients">Client 凭证</el-menu-item>
            <el-menu-item index="/mcp/monitor">调用流水</el-menu-item>
            <el-menu-item index="/mcp/onboarding">接入向导</el-menu-item>
          </el-sub-menu>

          <el-sub-menu index="/a2a-group">
            <template #title>
              <el-icon><Share /></el-icon>
              <span>对外开放 / A2A</span>
            </template>
            <el-menu-item index="/a2a/endpoints">暴露 Agent</el-menu-item>
            <el-menu-item index="/a2a/monitor">会话监控</el-menu-item>
          </el-sub-menu>

          <el-sub-menu index="/settings-group">
            <template #title>
              <el-icon><Lock /></el-icon>
              <span>设置 / 护栏</span>
            </template>
            <el-menu-item index="/settings/tool-acl">Tool ACL</el-menu-item>
          </el-sub-menu>
        </el-menu>
      </el-scrollbar>
    </el-aside>

    <el-container>
      <el-header class="topbar">
        <div class="breadcrumb-area">
          <el-breadcrumb separator="/">
            <el-breadcrumb-item :to="{ path: '/' }">首页</el-breadcrumb-item>
            <el-breadcrumb-item>{{ currentTitle }}</el-breadcrumb-item>
          </el-breadcrumb>
        </div>
      </el-header>

      <el-main class="main-content">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import {
  Aim,
  Collection,
  Compass,
  Connection,
  DataAnalysis,
  Cpu,
  Coin,
  SetUp,
  Share,
  FolderOpened,
  Lock,
} from '@element-plus/icons-vue'

const route = useRoute()

const activeMenu = computed(() => {
  const path = route.path
  if (path.startsWith('/knowledge')) return '/knowledge'
  if (path.startsWith('/biz-index')) return '/biz-index'
  if (path.startsWith('/agent')) return '/agent'
  if (path.startsWith('/model/playground')) return '/model/playground'
  if (path.startsWith('/model')) return '/model'
  if (path.startsWith('/tool/retrieval')) return '/tool/retrieval'
  if (path.startsWith('/skill/mining')) return '/skill/mining'
  if (path.startsWith('/skill/slot/extractors')) return '/skill/slot/extractors'
  if (path.startsWith('/skill/slot/dict-dept')) return '/skill/slot/dict-dept'
  if (path.startsWith('/skill/slot/dict-user')) return '/skill/slot/dict-user'
  if (path.startsWith('/skill/slot/logs')) return '/skill/slot/logs'
  if (path.startsWith('/skill')) return '/skill'
  if (path.startsWith('/tool')) return '/tool'
  if (path.startsWith('/scan-project')) return '/scan-project'
  if (path.startsWith('/domain/board')) return '/domain/board'
  if (path.startsWith('/domain/classifier-test')) return '/domain/classifier-test'
  if (path.startsWith('/domain')) return '/domain'
  if (path.startsWith('/mcp/visibility')) return '/mcp/visibility'
  if (path.startsWith('/mcp/clients')) return '/mcp/clients'
  if (path.startsWith('/mcp/monitor')) return '/mcp/monitor'
  if (path.startsWith('/mcp/onboarding')) return '/mcp/onboarding'
  if (path.startsWith('/a2a/endpoints')) return '/a2a/endpoints'
  if (path.startsWith('/a2a/monitor')) return '/a2a/monitor'
  if (path.startsWith('/settings/tool-acl')) return '/settings/tool-acl'
  return path
})

const currentTitle = computed(() => (route.meta.title as string) || '')
</script>

<style scoped lang="scss">
.main-layout {
  height: 100vh;
}

.sidebar {
  background: #1d2129;
  overflow: hidden;
  display: flex;
  flex-direction: column;
}

.logo {
  display: flex;
  align-items: center;
  gap: 10px;
  height: 60px;
  padding: 0 20px;
  color: #fff;
  font-size: 16px;
  font-weight: 600;
  border-bottom: 1px solid #ffffff1a;
  flex-shrink: 0;
}

.logo-text {
  letter-spacing: 1px;
}

.sidebar-menu {
  border-right: none;

  :deep(.el-menu-item) {
    height: 48px;
    line-height: 48px;
    margin: 4px 8px;
    border-radius: 6px;

    &.is-active {
      background: #409eff !important;
    }
  }

  :deep(.el-sub-menu__title) {
    height: 48px;
    line-height: 48px;
    margin: 4px 8px;
    border-radius: 6px;
  }

  :deep(.el-sub-menu .el-menu-item) {
    padding-left: 52px !important;
    height: 42px;
    line-height: 42px;
    margin: 2px 8px;
  }
}

.topbar {
  display: flex;
  align-items: center;
  background: #fff;
  border-bottom: 1px solid #e5e6eb;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.04);
  height: 56px;
}

.breadcrumb-area {
  display: flex;
  align-items: center;
}

.main-content {
  background: #f5f7fa;
  overflow-y: auto;
}
</style>
