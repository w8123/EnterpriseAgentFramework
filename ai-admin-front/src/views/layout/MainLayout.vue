<template>
  <el-container class="main-layout">
    <el-aside width="240px" class="sidebar">
      <div class="logo">
        <div class="logo-icon-wrap">
          <el-icon :size="22"><Cpu /></el-icon>
        </div>
        <span class="logo-text gradient-text">AI 能力中台</span>
      </div>
      <el-scrollbar class="sidebar-scroll">
        <el-menu
          :default-active="activeMenu"
          router
          class="sidebar-menu"
          :default-openeds="defaultOpenMenuGroups"
        >
          <!-- 1 概览 -->
          <el-menu-item index="/dashboard">
            <el-icon><DataAnalysis /></el-icon>
            <span>概览</span>
          </el-menu-item>

          <!-- 2 AI 注册中心（项目目录 + 能力评审 + 扫描/API 接入） -->
          <el-sub-menu index="/registry-group">
            <template #title>
              <el-icon><Connection /></el-icon>
              <span>AI 注册中心</span>
            </template>
            <el-menu-item index="/registry/projects">项目管理</el-menu-item>
            <el-menu-item index="/registry/capability-sync">能力变更评审</el-menu-item>
            <el-menu-item index="/scan-project">项目与 API 接入</el-menu-item>
          </el-sub-menu>

          <!-- 3 Agent -->
          <el-menu-item index="/agent">
            <el-icon><Cpu /></el-icon>
            <span>Agent</span>
          </el-menu-item>

          <!-- 4 Skill -->
          <el-sub-menu index="/skill-group">
            <template #title>
              <el-icon><Aim /></el-icon>
              <span>Skill</span>
            </template>
            <el-menu-item index="/skill">Skill 管理</el-menu-item>
            <el-menu-item index="/skill/mining">Skill Mining</el-menu-item>
            <el-menu-item index="/skill/slot/extractors">槽位提取器</el-menu-item>
            <el-menu-item index="/skill/slot/dict-dept">部门字典</el-menu-item>
            <el-menu-item index="/skill/slot/dict-user">人员字典</el-menu-item>
            <el-menu-item index="/skill/slot/logs">槽位调用日志</el-menu-item>
          </el-sub-menu>

          <!-- 5 Tool（含 ACL） -->
          <el-sub-menu index="/tool-group">
            <template #title>
              <el-icon><SetUp /></el-icon>
              <span>Tool</span>
            </template>
            <el-menu-item index="/tool">Tool 列表</el-menu-item>
            <el-menu-item index="/tool/retrieval">Tool 检索测试</el-menu-item>
            <el-menu-item index="/settings/tool-acl">Tool ACL</el-menu-item>
          </el-sub-menu>

          <div class="menu-divider" />

          <!-- 6 知识与检索 -->
          <el-sub-menu index="/knowledge-group">
            <template #title>
              <el-icon><Collection /></el-icon>
              <span>知识与检索</span>
            </template>
            <el-menu-item index="/knowledge">知识库管理</el-menu-item>
            <el-menu-item index="/knowledge/import">文件入库</el-menu-item>
            <el-menu-item index="/retrieval">检索测试</el-menu-item>
            <el-menu-item index="/biz-index">业务索引</el-menu-item>
          </el-sub-menu>

          <!-- 7 模型管理 -->
          <el-sub-menu index="/model-group">
            <template #title>
              <el-icon><Coin /></el-icon>
              <span>模型管理</span>
            </template>
            <el-menu-item index="/model">Provider 管理</el-menu-item>
            <el-menu-item index="/model/playground">模型调试台</el-menu-item>
          </el-sub-menu>

          <div class="menu-divider" />

          <!-- 8 对外开放：MCP + A2A -->
          <el-sub-menu index="/open-group">
            <template #title>
              <el-icon><Share /></el-icon>
              <span>对外开放</span>
            </template>
            <el-menu-item index="/mcp/visibility">MCP · 暴露白名单</el-menu-item>
            <el-menu-item index="/mcp/clients">MCP · Client 凭证</el-menu-item>
            <el-menu-item index="/mcp/monitor">MCP · 调用流水</el-menu-item>
            <el-menu-item index="/mcp/onboarding">MCP · 接入向导</el-menu-item>
            <el-menu-item index="/a2a/endpoints">A2A · 暴露 Agent</el-menu-item>
            <el-menu-item index="/a2a/monitor">A2A · 会话监控</el-menu-item>
          </el-sub-menu>

          <!-- 9 治理与运维 -->
          <el-sub-menu index="/domain-group">
            <template #title>
              <el-icon><Compass /></el-icon>
              <span>治理与运维</span>
            </template>
            <el-menu-item index="/domain">领域定义</el-menu-item>
            <el-menu-item index="/domain/board">归属画布</el-menu-item>
            <el-menu-item index="/domain/classifier-test">分类器测试</el-menu-item>
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
        <div class="topbar-actions">
          <el-tooltip :content="theme === 'dark' ? '切换日间模式' : '切换夜间模式'" placement="bottom">
            <el-button
              :icon="theme === 'dark' ? Sunny : Moon"
              circle
              size="small"
              class="topbar-btn"
              @click="toggleTheme"
            />
          </el-tooltip>
          <div class="topbar-search">
            <el-input
              placeholder="搜索功能..."
              size="small"
              :prefix-icon="Search"
              clearable
              class="search-input"
            />
          </div>
          <el-tooltip content="通知" placement="bottom">
            <el-badge :value="3" :max="99" class="notify-badge">
              <el-button :icon="Bell" circle size="small" class="topbar-btn" />
            </el-badge>
          </el-tooltip>
          <ProjectSelector />
          <el-avatar :size="32" class="user-avatar">
            <el-icon :size="16"><User /></el-icon>
          </el-avatar>
        </div>
      </el-header>

      <el-main class="main-content">
        <router-view v-slot="{ Component }">
          <transition name="page" mode="out-in">
            <component :is="Component" />
          </transition>
        </router-view>
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { useTheme } from '@/composables/useTheme'
import ProjectSelector from '@/components/ProjectSelector.vue'
import {
  Aim,
  Bell,
  Collection,
  Compass,
  Connection,
  DataAnalysis,
  Cpu,
  Coin,
  Search,
  SetUp,
  Share,
  User,
  Sunny,
  Moon,
} from '@element-plus/icons-vue'

const { theme, toggleTheme } = useTheme()

const route = useRoute()

/** 默认展开的侧边栏分组（可按需填入 index，如 '/registry-group'） */
const defaultOpenMenuGroups: string[] = []

const activeMenu = computed(() => {
  const path = route.path
  if (path.startsWith('/knowledge')) return '/knowledge'
  if (path.startsWith('/biz-index')) return '/biz-index'
  if (path.startsWith('/agent')) return '/agent'
  if (path.startsWith('/model/playground')) return '/model/playground'
  if (path.startsWith('/model')) return '/model'
  if (path.startsWith('/tool/retrieval')) return '/tool/retrieval'
  if (path.startsWith('/settings/tool-acl')) return '/settings/tool-acl'
  if (path.startsWith('/skill/mining')) return '/skill/mining'
  if (path.startsWith('/skill/slot/extractors')) return '/skill/slot/extractors'
  if (path.startsWith('/skill/slot/dict-dept')) return '/skill/slot/dict-dept'
  if (path.startsWith('/skill/slot/dict-user')) return '/skill/slot/dict-user'
  if (path.startsWith('/skill/slot/logs')) return '/skill/slot/logs'
  if (path.startsWith('/skill')) return '/skill'
  if (path.startsWith('/tool')) return '/tool'
  if (path.startsWith('/registry/capability-sync')) return '/registry/capability-sync'
  if (path.startsWith('/registry/projects')) return '/registry/projects'
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
  return path
})

const currentTitle = computed(() => (route.meta.title as string) || '')
</script>

<style scoped lang="scss">
.main-layout {
  height: 100vh;
}

.sidebar {
  background: rgba(15, 15, 25, 0.85);
  backdrop-filter: blur(24px);
  border-right: 1px solid rgba(255, 255, 255, 0.04);
  overflow: hidden;
  display: flex;
  flex-direction: column;
  position: relative;
  z-index: 10;

  &::before {
    content: '';
    position: absolute;
    top: 0;
    left: 0;
    right: 0;
    height: 120px;
    background: linear-gradient(180deg, rgba(99, 102, 241, 0.06) 0%, transparent 100%);
    pointer-events: none;
  }
}

.logo {
  display: flex;
  align-items: center;
  gap: 12px;
  height: 64px;
  padding: 0 24px;
  flex-shrink: 0;
  position: relative;
  z-index: 1;
}

.logo-icon-wrap {
  width: 36px;
  height: 36px;
  border-radius: 10px;
  background: linear-gradient(135deg, #6366f1, #8b5cf6);
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  box-shadow: 0 4px 12px rgba(99, 102, 241, 0.3);
}

.logo-text {
  font-size: 17px;
  font-weight: 700;
  letter-spacing: 0.5px;
  background: linear-gradient(135deg, #6366f1, #a78bfa);
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
  background-clip: text;
}

.sidebar-scroll {
  flex: 1;
  overflow: hidden;
}

.menu-divider {
  height: 1px;
  margin: 8px 20px;
  background: rgba(255, 255, 255, 0.04);
}

.sidebar-menu {
  border-right: none;
  padding: 0 8px;

  :deep(.el-menu-item) {
    height: 42px;
    line-height: 42px;
    margin: 2px 0;
    border-radius: 8px;
    font-size: 13px;
    color: #94a3b8;
    transition: all 0.2s ease;
    position: relative;

    .el-icon {
      font-size: 16px;
      margin-right: 8px;
    }

    &:hover {
      background: rgba(99, 102, 241, 0.08);
      color: #e2e8f0;
    }

    &.is-active {
      background: rgba(99, 102, 241, 0.12);
      color: #e2e8f0;

      &::before {
        content: '';
        position: absolute;
        left: 0;
        top: 8px;
        bottom: 8px;
        width: 3px;
        border-radius: 0 3px 3px 0;
        background: linear-gradient(180deg, #6366f1, #8b5cf6);
        box-shadow: 0 0 8px rgba(99, 102, 241, 0.4);
      }
    }
  }

  :deep(.el-sub-menu__title) {
    height: 42px;
    line-height: 42px;
    margin: 2px 0;
    border-radius: 8px;
    font-size: 13px;
    color: #94a3b8;
    transition: all 0.2s ease;

    .el-icon {
      font-size: 16px;
      margin-right: 8px;
    }

    &:hover {
      background: rgba(99, 102, 241, 0.06);
      color: #e2e8f0;
    }
  }

  :deep(.el-sub-menu .el-menu) {
    background: transparent;
    padding: 0;
  }

  :deep(.el-sub-menu .el-menu-item) {
    padding-left: 52px !important;
    height: 38px;
    line-height: 38px;
    margin: 1px 0;
    font-size: 12.5px;
  }
}

.topbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: rgba(15, 15, 25, 0.6);
  backdrop-filter: blur(16px);
  border-bottom: 1px solid rgba(255, 255, 255, 0.04);
  height: 56px;
  padding: 0 24px;
}

.breadcrumb-area {
  display: flex;
  align-items: center;
}

.topbar-actions {
  display: flex;
  align-items: center;
  gap: 16px;
}

.topbar-search {
  .search-input {
    width: 200px;
    transition: width 0.3s ease;

    &:focus-within {
      width: 280px;
    }

    :deep(.el-input__wrapper) {
      background: rgba(255, 255, 255, 0.04);
      border-radius: 8px;
    }
  }
}

.topbar-btn {
  background: rgba(255, 255, 255, 0.04);
  border-color: rgba(255, 255, 255, 0.06);
  color: #94a3b8;

  &:hover {
    background: rgba(99, 102, 241, 0.1);
    border-color: rgba(99, 102, 241, 0.2);
    color: #e2e8f0;
  }
}

.notify-badge {
  :deep(.el-badge__content) {
    background: linear-gradient(135deg, #f43f5e, #e11d48);
    border: none;
  }
}

.user-avatar {
  background: linear-gradient(135deg, #6366f1, #8b5cf6);
  cursor: pointer;
  color: #fff;
  transition: box-shadow 0.2s ease;

  &:hover {
    box-shadow: 0 0 0 3px rgba(99, 102, 241, 0.3);
  }
}

.main-content {
  background: var(--bg-primary);
  overflow-y: auto;
  position: relative;

  &::before {
    content: '';
    position: fixed;
    top: 0;
    left: 240px;
    right: 0;
    height: 300px;
    background: radial-gradient(ellipse at 50% 0%, rgba(99, 102, 241, 0.04) 0%, transparent 70%);
    pointer-events: none;
    z-index: 0;
  }
}

// ── 日间模式布局覆盖 ──
:global([data-theme="light"]) {
  .sidebar {
    background: #eef0f4;
    border-right: 1px solid #dcdfe6;
    backdrop-filter: none;

    &::before {
      background: linear-gradient(180deg, rgba(99, 102, 241, 0.05) 0%, transparent 100%);
    }
  }

  .menu-divider {
    background: #dcdfe6;
  }

  .sidebar-menu {
    :deep(.el-menu-item) {
      color: #303133;

      &:hover {
        background: rgba(99, 102, 241, 0.1);
        color: #1e293b;
      }

      &.is-active {
        background: rgba(99, 102, 241, 0.12);
        color: #1e293b;

        &::before {
          box-shadow: 0 0 8px rgba(99, 102, 241, 0.25);
        }
      }
    }

    :deep(.el-sub-menu__title) {
      color: #303133;

      &:hover {
        background: rgba(99, 102, 241, 0.08);
        color: #1e293b;
      }
    }
  }

  .topbar-search {
    .search-input {
      :deep(.el-input__wrapper) {
        background: #f0f2f5;
      }
    }
  }

  .topbar-btn {
    background: #f0f2f5;
    border-color: #dcdfe6;
    color: #475569;

    &:hover {
      background: rgba(99, 102, 241, 0.1);
      border-color: rgba(99, 102, 241, 0.25);
      color: #1e293b;
    }
  }

  .main-content {
    &::before {
      background: radial-gradient(ellipse at 50% 0%, rgba(99, 102, 241, 0.03) 0%, transparent 70%);
    }
  }
}
</style>
