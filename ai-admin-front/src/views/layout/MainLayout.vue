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

          <el-menu-item index="/tool">
            <el-icon><SetUp /></el-icon>
            <span>Tool 管理</span>
          </el-menu-item>
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
  Collection,
  DataAnalysis,
  Cpu,
  Coin,
  SetUp,
} from '@element-plus/icons-vue'

const route = useRoute()

const activeMenu = computed(() => {
  const path = route.path
  if (path.startsWith('/knowledge')) return '/knowledge'
  if (path.startsWith('/biz-index')) return '/biz-index'
  if (path.startsWith('/agent')) return '/agent'
  if (path.startsWith('/model/playground')) return '/model/playground'
  if (path.startsWith('/model')) return '/model'
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
