<template>
  <el-container
    class="main-layout"
    :class="{ 'registry-shell': isRegistryShell, 'studio-shell': isStudioPage, 'is-dark': theme === 'dark', 'sidebar-collapsed': isSidebarCollapsed }"
  >
    <el-aside v-if="!isStudioPage" :width="isSidebarCollapsed ? '72px' : '240px'" class="sidebar">
      <div class="logo">
        <div class="logo-icon-wrap">
          <img src="/reachai-icon.svg" alt="睿池 ReachAI" />
        </div>
        <span class="logo-text">AI 能力中台</span>
      </div>
      <el-scrollbar class="sidebar-scroll">
        <el-menu
          :default-active="activeMenu"
          router
          class="sidebar-menu"
          :default-openeds="defaultOpenMenuGroups"
          :collapse="isSidebarCollapsed"
          :collapse-transition="false"
        >
          <!-- 1 概览 -->
          <el-menu-item index="/dashboard">
            <el-icon><DataAnalysis /></el-icon>
            <span class="menu-label">
              <span class="menu-cn">概览</span>
              <span class="menu-en">Dashboard</span>
            </span>
          </el-menu-item>

          <!-- 2 AI 注册中心 -->
          <el-sub-menu index="/registry-group">
            <template #title>
              <el-icon><Connection /></el-icon>
              <span class="menu-label">
                <span class="menu-cn">注册中心</span>
                <span class="menu-en">Registry</span>
              </span>
            </template>
            <el-menu-item index="/registry/projects">
              <span class="menu-label leaf">
                <span class="menu-cn">项目管理</span>
                <span class="menu-en">Projects</span>
              </span>
            </el-menu-item>
            <el-menu-item index="/registry/capability-sync">
              <span class="menu-label leaf">
                <span class="menu-cn">能力变更评审</span>
                <span class="menu-en">Capability Review</span>
              </span>
            </el-menu-item>
            <el-menu-item index="/registry/runtimes">
              <span class="menu-label leaf">
                <span class="menu-cn">Runtime 纳管</span>
                <span class="menu-en">Runtimes</span>
              </span>
            </el-menu-item>
          </el-sub-menu>

          <!-- 3 Agent -->
          <el-menu-item index="/agent">
            <el-icon><Cpu /></el-icon>
            <span class="menu-label">
              <span class="menu-cn">智能体</span>
              <span class="menu-en">Agent</span>
            </span>
          </el-menu-item>

          <!-- 4 能力内核 -->
          <el-sub-menu index="/capability-group">
            <template #title>
              <el-icon><Aim /></el-icon>
              <span class="menu-label">
                <span class="menu-cn">能力内核</span>
                <span class="menu-en">Kernel</span>
              </span>
            </template>
            <el-menu-item index="/capability">
              <span class="menu-label leaf">
                <span class="menu-cn">能力模块</span>
                <span class="menu-en">Modules</span>
              </span>
            </el-menu-item>
            <el-menu-item index="/capability/compositions">
              <span class="menu-label leaf">
                <span class="menu-cn">组合</span>
                <span class="menu-en">Compositions</span>
              </span>
            </el-menu-item>
            <el-menu-item index="/capability/tools">
              <span class="menu-label leaf">
                <span class="menu-cn">工具</span>
                <span class="menu-en">Tools</span>
              </span>
            </el-menu-item>
            <el-menu-item index="/capability/interactions">
              <span class="menu-label leaf">
                <span class="menu-cn">交互</span>
                <span class="menu-en">Interactions</span>
              </span>
            </el-menu-item>
          </el-sub-menu>

          <!-- 5 Tool -->
          <el-sub-menu index="/tool-group">
            <template #title>
              <el-icon><SetUp /></el-icon>
              <span class="menu-label">
                <span class="menu-cn">工具</span>
                <span class="menu-en">Tool</span>
              </span>
            </template>
            <el-menu-item index="/tool">
              <span class="menu-label leaf">
                <span class="menu-cn">工具列表</span>
                <span class="menu-en">Tool List</span>
              </span>
            </el-menu-item>
            <el-menu-item index="/tool/retrieval">
              <span class="menu-label leaf">
                <span class="menu-cn">工具检索测试</span>
                <span class="menu-en">Retrieval Test</span>
              </span>
            </el-menu-item>
            <el-menu-item index="/settings/tool-acl">
              <span class="menu-label leaf">
                <span class="menu-cn">工具权限</span>
                <span class="menu-en">Tool ACL</span>
              </span>
            </el-menu-item>
          </el-sub-menu>

          <div class="menu-divider" />

          <!-- 6 知识与检索 -->
          <el-sub-menu index="/knowledge-group">
            <template #title>
              <el-icon><Collection /></el-icon>
              <span class="menu-label">
                <span class="menu-cn">知识检索</span>
                <span class="menu-en">Knowledge</span>
              </span>
            </template>
            <el-menu-item index="/knowledge">
              <span class="menu-label leaf">
                <span class="menu-cn">知识库管理</span>
                <span class="menu-en">Knowledge Bases</span>
              </span>
            </el-menu-item>
            <el-menu-item index="/knowledge/import">
              <span class="menu-label leaf">
                <span class="menu-cn">文件入库</span>
                <span class="menu-en">Import</span>
              </span>
            </el-menu-item>
            <el-menu-item index="/retrieval">
              <span class="menu-label leaf">
                <span class="menu-cn">检索测试</span>
                <span class="menu-en">Retrieval</span>
              </span>
            </el-menu-item>
            <el-menu-item index="/biz-index">
              <span class="menu-label leaf">
                <span class="menu-cn">业务索引</span>
                <span class="menu-en">Business Index</span>
              </span>
            </el-menu-item>
          </el-sub-menu>

          <!-- 7 模型管理 -->
          <el-sub-menu index="/model-group">
            <template #title>
              <el-icon><Coin /></el-icon>
              <span class="menu-label">
                <span class="menu-cn">模型管理</span>
                <span class="menu-en">Models</span>
              </span>
            </template>
            <el-menu-item index="/model/instances">
              <span class="menu-label leaf">
                <span class="menu-cn">模型实例</span>
                <span class="menu-en">Instances</span>
              </span>
            </el-menu-item>
            <el-menu-item index="/model/playground">
              <span class="menu-label leaf">
                <span class="menu-cn">模型调试台</span>
                <span class="menu-en">Playground</span>
              </span>
            </el-menu-item>
          </el-sub-menu>

          <div class="menu-divider" />

          <!-- 8 对外开放 -->
          <el-sub-menu index="/open-group">
            <template #title>
              <el-icon><Share /></el-icon>
              <span class="menu-label">
                <span class="menu-cn">对外开放</span>
                <span class="menu-en">Open API</span>
              </span>
            </template>
            <el-menu-item index="/mcp/visibility">
              <span class="menu-label leaf">
                <span class="menu-cn">暴露白名单</span>
                <span class="menu-en">MCP Visibility</span>
              </span>
            </el-menu-item>
            <el-menu-item index="/mcp/clients">
              <span class="menu-label leaf">
                <span class="menu-cn">客户端凭证</span>
                <span class="menu-en">MCP Clients</span>
              </span>
            </el-menu-item>
            <el-menu-item index="/mcp/monitor">
              <span class="menu-label leaf">
                <span class="menu-cn">调用流水</span>
                <span class="menu-en">MCP Monitor</span>
              </span>
            </el-menu-item>
            <el-menu-item index="/mcp/onboarding">
              <span class="menu-label leaf">
                <span class="menu-cn">接入向导</span>
                <span class="menu-en">MCP Onboarding</span>
              </span>
            </el-menu-item>
            <el-menu-item index="/a2a/endpoints">
              <span class="menu-label leaf">
                <span class="menu-cn">智能体暴露</span>
                <span class="menu-en">A2A Endpoints</span>
              </span>
            </el-menu-item>
            <el-menu-item index="/a2a/monitor">
              <span class="menu-label leaf">
                <span class="menu-cn">会话监控</span>
                <span class="menu-en">A2A Monitor</span>
              </span>
            </el-menu-item>
          </el-sub-menu>

          <!-- 9 治理与运维 -->
          <el-sub-menu index="/domain-group">
            <template #title>
              <el-icon><Compass /></el-icon>
              <span class="menu-label">
                <span class="menu-cn">治理运维</span>
                <span class="menu-en">Governance</span>
              </span>
            </template>
            <el-menu-item index="/domain">
              <span class="menu-label leaf">
                <span class="menu-cn">领域定义</span>
                <span class="menu-en">Domains</span>
              </span>
            </el-menu-item>
            <el-menu-item index="/settings/platform-users">
              <span class="menu-label leaf">
                <span class="menu-cn">平台用户</span>
                <span class="menu-en">Platform Users</span>
              </span>
            </el-menu-item>
            <el-menu-item index="/settings/business-users">
              <span class="menu-label leaf">
                <span class="menu-cn">业务用户</span>
                <span class="menu-en">Business Users</span>
              </span>
            </el-menu-item>
            <el-menu-item index="/settings/auth-providers">
              <span class="menu-label leaf">
                <span class="menu-cn">认证源</span>
                <span class="menu-en">Auth Providers</span>
              </span>
            </el-menu-item>
            <el-menu-item index="/settings/embed-ops">
              <span class="menu-label leaf">
                <span class="menu-cn">嵌入审计</span>
                <span class="menu-en">Embed Ops</span>
              </span>
            </el-menu-item>
            <el-menu-item index="/runops">
              <span class="menu-label leaf">
                <span class="menu-cn">运行中心</span>
                <span class="menu-en">RunOps</span>
              </span>
            </el-menu-item>
            <el-menu-item index="/domain/board">
              <span class="menu-label leaf">
                <span class="menu-cn">归属画布</span>
                <span class="menu-en">Assignment Board</span>
              </span>
            </el-menu-item>
            <el-menu-item index="/domain/classifier-test">
              <span class="menu-label leaf">
                <span class="menu-cn">分类器测试</span>
                <span class="menu-en">Classifier Test</span>
              </span>
            </el-menu-item>
          </el-sub-menu>
        </el-menu>
      </el-scrollbar>
    </el-aside>

    <el-container>
      <el-header v-if="!isStudioPage" class="topbar">
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
          <el-tooltip content="通知" placement="bottom">
            <el-badge :value="3" :max="99" class="notify-badge">
              <el-button :icon="Bell" circle size="small" class="topbar-btn" />
            </el-badge>
          </el-tooltip>
          <ProjectSelector v-if="!isProjectManagementPage" :compact="isRegistryShell" />
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
import { computed, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useTheme } from '@/composables/useTheme'
import { useProjectStore } from '@/store/project'
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
  SetUp,
  Share,
  User,
  Sunny,
  Moon,
} from '@element-plus/icons-vue'

const { theme, toggleTheme } = useTheme()

const route = useRoute()
const projectStore = useProjectStore()

/** 默认展开的侧边栏分组，可按需填入 index，如 '/registry-group' */
const defaultOpenMenuGroups: string[] = []

const activeMenu = computed(() => {
  const path = route.path
  if (path.startsWith('/knowledge')) return '/knowledge'
  if (path.startsWith('/biz-index')) return '/biz-index'
  if (path.startsWith('/agent')) return '/agent'
  if (path.startsWith('/model/playground')) return '/model/playground'
  if (path.startsWith('/model/instances')) return '/model/instances'
  if (path.startsWith('/model')) return '/model'
  if (path.startsWith('/tool/retrieval')) return '/tool/retrieval'
  if (path.startsWith('/settings/platform-users')) return '/settings/platform-users'
  if (path.startsWith('/settings/business-users')) return '/settings/business-users'
  if (path.startsWith('/settings/auth-providers')) return '/settings/auth-providers'
  if (path.startsWith('/settings/embed-ops')) return '/settings/embed-ops'
  if (path.startsWith('/settings/tool-acl')) return '/settings/tool-acl'
  if (path.startsWith('/capability/tools')) return '/capability/tools'
  if (path.startsWith('/capability/compositions')) return '/capability/compositions'
  if (path.startsWith('/capability/interactions')) return '/capability/interactions'
  if (path.startsWith('/capability/mining')) return '/capability/mining'
  if (path.startsWith('/capability/slot/extractors')) return '/capability/slot/extractors'
  if (path.startsWith('/capability/slot/dict-dept')) return '/capability/slot/dict-dept'
  if (path.startsWith('/capability/slot/dict-user')) return '/capability/slot/dict-user'
  if (path.startsWith('/capability/slot/logs')) return '/capability/slot/logs'
  if (path.startsWith('/capability')) return '/capability'
  // 旧书签 /skill/* 重定向后通常已进入 /capability；此处兜底避免闪烁
  if (path.startsWith('/skill/mining')) return '/capability/mining'
  if (path.startsWith('/skill/slot/extractors')) return '/capability/slot/extractors'
  if (path.startsWith('/skill/slot/dict-dept')) return '/capability/slot/dict-dept'
  if (path.startsWith('/skill/slot/dict-user')) return '/capability/slot/dict-user'
  if (path.startsWith('/skill/slot/logs')) return '/capability/slot/logs'
  if (path.startsWith('/skill')) return '/capability'
  if (path.startsWith('/tool')) return '/tool'
  if (path.startsWith('/registry/capability-sync')) return '/registry/capability-sync'
  if (path.startsWith('/registry/runtimes')) return '/registry/runtimes'
  if (path.startsWith('/registry/projects')) return '/registry/projects'
  if (path.startsWith('/scan-project')) return '/registry/projects'
  if (path.startsWith('/runops')) return '/runops'
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

const isStudioPage = computed(() => route.name === 'AgentStudio')
const isSidebarCollapsed = computed(() => isStudioPage.value)

const currentTitle = computed(() => (route.meta.title as string) || '')

const isProjectManagementPage = computed(() =>
  route.path.startsWith('/registry/projects') ||
  route.path.startsWith('/registry/runtimes') ||
  route.path.startsWith('/scan-project'),
)

const isRegistryShell = computed(() => isProjectManagementPage.value)

watch(
  isProjectManagementPage,
  (onProjectManagementPage) => {
    if (onProjectManagementPage && projectStore.currentProjectId !== null) {
      projectStore.setCurrentProject(null)
    }
  },
  { immediate: true },
)

</script>

<style scoped lang="scss">
.main-layout {
  height: 100vh;
}

.sidebar {
  background:
    radial-gradient(circle at 24px 24px, rgba(99, 102, 241, 0.28), transparent 26%),
    radial-gradient(circle at 210px 160px, rgba(14, 165, 233, 0.12), transparent 30%),
    linear-gradient(180deg, #07111f 0%, #08101c 42%, #050b14 100%);
  border-right: 1px solid rgba(148, 163, 184, 0.12);
  box-shadow: 18px 0 46px rgba(2, 6, 23, 0.22);
  overflow: hidden;
  display: flex;
  flex-direction: column;
  position: relative;
  z-index: 10;
  transition: width 0.18s ease;

  &::before {
    content: '';
    position: absolute;
    inset: 0;
    background:
      linear-gradient(90deg, rgba(255, 255, 255, 0.055) 1px, transparent 1px),
      linear-gradient(180deg, rgba(255, 255, 255, 0.04) 1px, transparent 1px);
    background-size: 32px 32px;
    mask-image: linear-gradient(180deg, rgba(0, 0, 0, 0.75), transparent 62%);
    opacity: 0.45;
    pointer-events: none;
  }

  &::after {
    content: '';
    position: absolute;
    top: 64px;
    right: 0;
    bottom: 0;
    width: 1px;
    background: linear-gradient(180deg, rgba(99, 102, 241, 0.65), transparent 40%, rgba(14, 165, 233, 0.22));
    pointer-events: none;
  }
}

.sidebar-collapsed {
  .sidebar {
    width: 72px;
  }

  .logo {
    justify-content: center;
    padding: 0;

    &::after {
      display: none;
    }
  }

  .logo-text,
  .menu-label,
  .menu-divider {
    display: none;
  }

  .logo-icon-wrap {
    width: 38px;
    height: 38px;
    border-radius: 11px;
  }

  .sidebar-menu {
    width: 72px;
    padding: 14px 8px 18px;

    :deep(.el-menu-item),
    :deep(.el-sub-menu__title) {
      justify-content: center;
      padding: 0 !important;
    }

    :deep(.el-icon) {
      margin-right: 0;
    }
  }
}

.logo {
  display: flex;
  align-items: center;
  gap: 12px;
  height: 72px;
  padding: 0 22px;
  flex-shrink: 0;
  position: relative;
  z-index: 1;
  border-bottom: 1px solid rgba(148, 163, 184, 0.10);

  &::after {
    content: 'Enterprise AI';
    position: absolute;
    left: 72px;
    bottom: 13px;
    font-size: 10px;
    line-height: 1;
    font-weight: 700;
    color: rgba(148, 163, 184, 0.64);
    letter-spacing: 0.12em;
    text-transform: uppercase;
  }
}

.logo-icon-wrap {
  width: 40px;
  height: 40px;
  border-radius: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow:
    0 12px 28px rgba(37, 99, 235, 0.32),
    inset 0 0 0 1px rgba(255, 255, 255, 0.24);
  overflow: hidden;

  img {
    display: block;
    width: 100%;
    height: 100%;
  }
}

.logo-text {
  font-size: 17px;
  font-weight: 700;
  line-height: 1.1;
  letter-spacing: 0;
  padding-bottom: 10px;
  background: linear-gradient(135deg, #ffffff 0%, #dbeafe 48%, #93c5fd 100%);
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
  margin: 12px 20px;
  background: linear-gradient(90deg, transparent, rgba(148, 163, 184, 0.18), transparent);
}

.sidebar-menu {
  border-right: none;
  padding: 14px 10px 18px;
  background: transparent;

  :deep(.el-menu) {
    background: transparent;
    border: none;
  }

  :deep(.el-menu-item),
  :deep(.el-sub-menu__title) {
    isolation: isolate;

    > span:not(.menu-label),
    .menu-label span {
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
  }

  :deep(.el-menu-item) {
    height: 48px;
    line-height: 1.15;
    margin: 4px 0;
    padding-right: 12px;
    border-radius: 8px;
    font-size: 13.5px;
    font-weight: 600;
    color: #a8b3c7;
    transition: color 0.18s ease, background 0.18s ease, transform 0.18s ease, box-shadow 0.18s ease;
    position: relative;

    .el-icon {
      width: 28px;
      height: 28px;
      margin-right: 10px;
      border-radius: 8px;
      font-size: 16px;
      color: #8da2c0;
      background: rgba(148, 163, 184, 0.08);
      box-shadow: inset 0 0 0 1px rgba(148, 163, 184, 0.07);
      transition: color 0.18s ease, background 0.18s ease, box-shadow 0.18s ease;
    }

    &:hover {
      background: rgba(99, 102, 241, 0.12);
      color: #f8fafc;
      transform: translateX(2px);

      .el-icon {
        color: #dbeafe;
        background: rgba(99, 102, 241, 0.18);
        box-shadow: inset 0 0 0 1px rgba(129, 140, 248, 0.18);
      }
    }

    &.is-active {
      background:
        linear-gradient(135deg, rgba(79, 70, 229, 0.92), rgba(37, 99, 235, 0.76)),
        linear-gradient(90deg, rgba(255, 255, 255, 0.16), transparent);
      color: #ffffff;
      box-shadow:
        0 12px 26px rgba(37, 99, 235, 0.24),
        inset 0 0 0 1px rgba(255, 255, 255, 0.13);

      &::before {
        content: '';
        position: absolute;
        left: -10px;
        top: 8px;
        bottom: 8px;
        width: 4px;
        border-radius: 0 999px 999px 0;
        background: linear-gradient(180deg, #67e8f9, #818cf8);
        box-shadow: 0 0 14px rgba(103, 232, 249, 0.55);
      }

      &::after {
        content: '';
        position: absolute;
        inset: 1px;
        border-radius: 7px;
        background: linear-gradient(90deg, rgba(255, 255, 255, 0.16), transparent 42%);
        pointer-events: none;
        z-index: -1;
      }

      .el-icon {
        color: #ffffff;
        background: rgba(255, 255, 255, 0.16);
        box-shadow: inset 0 0 0 1px rgba(255, 255, 255, 0.18);
      }
    }
  }

  :deep(.el-sub-menu__title) {
    height: 48px;
    line-height: 1.15;
    margin: 4px 0;
    padding-right: 12px;
    border-radius: 8px;
    font-size: 13.5px;
    font-weight: 700;
    color: #a8b3c7;
    transition: color 0.18s ease, background 0.18s ease, transform 0.18s ease;

    .el-icon {
      width: 28px;
      height: 28px;
      margin-right: 10px;
      border-radius: 8px;
      font-size: 16px;
      color: #8da2c0;
      background: rgba(148, 163, 184, 0.08);
      box-shadow: inset 0 0 0 1px rgba(148, 163, 184, 0.07);
      transition: color 0.18s ease, background 0.18s ease;
    }

    .el-sub-menu__icon-arrow {
      width: auto;
      height: auto;
      margin-right: 0;
      background: transparent;
      box-shadow: none;
      color: #64748b;
    }

    &:hover {
      background: rgba(99, 102, 241, 0.10);
      color: #f8fafc;
      transform: translateX(2px);

      .el-icon {
        color: #dbeafe;
        background: rgba(99, 102, 241, 0.16);
      }

      .el-sub-menu__icon-arrow {
        color: #94a3b8;
        background: transparent;
        box-shadow: none;
      }
    }
  }

  :deep(.el-sub-menu.is-opened > .el-sub-menu__title) {
    color: #f8fafc;
    background: rgba(148, 163, 184, 0.08);

    .el-icon {
      color: #c7d2fe;
      background: rgba(99, 102, 241, 0.16);
    }
  }

  :deep(.el-sub-menu .el-menu) {
    background: transparent;
    padding: 2px 0 6px 18px;
    position: relative;

    &::before {
      content: '';
      position: absolute;
      left: 22px;
      top: 2px;
      bottom: 8px;
      width: 1px;
      background: linear-gradient(180deg, rgba(99, 102, 241, 0.35), rgba(148, 163, 184, 0.08));
    }
  }

  :deep(.el-sub-menu .el-menu-item) {
    padding-left: 34px !important;
    height: 42px;
    line-height: 1.15;
    margin: 3px 0 3px 8px;
    font-size: 12.5px;
    font-weight: 600;
    color: #8fa0ba;
    background: transparent;
    box-shadow: none;

    &::before {
      left: -18px;
      top: 18px;
      bottom: auto;
      width: 6px;
      height: 6px;
      border-radius: 50%;
      background: rgba(148, 163, 184, 0.24);
      box-shadow: none;
    }

    .el-icon {
      display: none;
    }

    &:hover {
      color: #e2e8f0;
      background: rgba(99, 102, 241, 0.10);
    }

    &.is-active {
      color: #ffffff;
      background: linear-gradient(135deg, rgba(79, 70, 229, 0.72), rgba(37, 99, 235, 0.50));
      box-shadow: 0 10px 22px rgba(37, 99, 235, 0.18);

      &::before {
        background: #67e8f9;
        box-shadow: 0 0 10px rgba(103, 232, 249, 0.55);
      }
    }
  }
}

.menu-label {
  display: flex;
  flex: 1 1 auto;
  min-width: 0;
  flex-direction: column;
  justify-content: center;
  gap: 3px;
}

.menu-cn,
.menu-en {
  display: block;
  min-width: 0;
}

.menu-cn {
  color: inherit;
  font-size: 13.5px;
  font-weight: 700;
}

.menu-en {
  color: rgba(148, 163, 184, 0.72);
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 0.02em;
  text-transform: none;
}

.menu-label.leaf {
  gap: 2px;

  .menu-cn {
    font-size: 12.5px;
    font-weight: 700;
  }

  .menu-en {
    font-size: 9.5px;
    color: rgba(148, 163, 184, 0.64);
  }
}

.sidebar-menu {
  :deep(.el-menu-item:hover),
  :deep(.el-sub-menu__title:hover),
  :deep(.el-sub-menu.is-opened > .el-sub-menu__title) {
    .menu-en {
      color: rgba(219, 234, 254, 0.76);
    }
  }

  :deep(.el-menu-item.is-active) {
    .menu-en {
      color: rgba(255, 255, 255, 0.78);
    }
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

.registry-shell {
  .topbar {
    height: 56px;
    background: rgba(255, 255, 255, 0.92);
    border-bottom: 1px solid #edf0f7;
    box-shadow: 0 8px 24px rgba(17, 24, 39, 0.03);
    backdrop-filter: blur(16px);
  }

  .breadcrumb-area {
    :deep(.el-breadcrumb__inner),
    :deep(.el-breadcrumb__separator) {
      color: #667085;
      font-weight: 600;
    }

    :deep(.el-breadcrumb__item:last-child .el-breadcrumb__inner) {
      color: #101828;
    }
  }

  .topbar-btn {
    background: #fff;
    border-color: #e4e7ee;
    color: #667085;

    &:hover {
      background: #f5f3ff;
      border-color: #ddd6fe;
      color: #5b3df5;
    }
  }

  .main-content {
    padding: 0;
    background: var(--bg-primary);

    &::before {
      display: none;
    }
  }
}

.studio-shell {
  .main-content {
    padding: 0;
    background: var(--bg-primary);

    &::before {
      display: none;
    }
  }
}

.main-layout.registry-shell.is-dark {
  .topbar {
    background: rgba(15, 15, 25, 0.72);
    border-bottom: 1px solid rgba(255, 255, 255, 0.06);
    box-shadow: none;
    backdrop-filter: blur(16px);
  }

  .breadcrumb-area {
    :deep(.el-breadcrumb__inner),
    :deep(.el-breadcrumb__separator) {
      color: #94a3b8;
    }

    :deep(.el-breadcrumb__item:last-child .el-breadcrumb__inner) {
      color: #e2e8f0;
    }
  }

  .topbar-btn {
    background: rgba(255, 255, 255, 0.04);
    border-color: rgba(255, 255, 255, 0.08);
    color: #94a3b8;

    &:hover {
      background: rgba(99, 102, 241, 0.12);
      border-color: rgba(99, 102, 241, 0.35);
      color: #e2e8f0;
    }
  }
}

</style>
