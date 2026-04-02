<template>
  <el-container class="main-layout">
    <el-aside width="220px" class="sidebar">
      <div class="logo">
        <el-icon :size="24"><DataAnalysis /></el-icon>
        <span class="logo-text">AI 知识库</span>
      </div>
      <el-menu
        :default-active="activeMenu"
        router
        class="sidebar-menu"
        background-color="#1d2129"
        text-color="#ffffffa6"
        active-text-color="#ffffff"
      >
        <el-menu-item index="/knowledge">
          <el-icon><Collection /></el-icon>
          <span>知识库管理</span>
        </el-menu-item>
        <el-menu-item index="/knowledge/import">
          <el-icon><Upload /></el-icon>
          <span>文件入库</span>
        </el-menu-item>
        <el-menu-item index="/retrieval">
          <el-icon><Search /></el-icon>
          <span>检索测试</span>
        </el-menu-item>
      </el-menu>
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
import { Collection, Upload, DataAnalysis, Search } from '@element-plus/icons-vue'

const route = useRoute()

const activeMenu = computed(() => {
  const path = route.path
  if (path.startsWith('/knowledge')) return '/knowledge'
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
