<template>
  <el-container
    class="main-layout"
    :class="{ 'registry-shell': isRegistryShell, 'studio-shell': isStudioPage, 'is-dark': theme === 'dark' }"
  >
    <AppPageBackground v-if="!isStudioPage" />

    <el-aside v-if="!isStudioPage" :width="isSidebarCollapsed ? '84px' : '256px'" class="sidebar-aside">
      <AppSidebar
        :collapsed="isSidebarCollapsed"
        :hide-project-panel="hideSidebarProjectPanel"
        @toggle-collapse="toggleSidebar"
      />
    </el-aside>

    <el-container class="layout-workspace">
      <div v-if="!isStudioPage" class="breadcrumb-rail">
        <AppBreadcrumb />
      </div>

      <el-main class="main-content">
        <router-view v-slot="{ Component }">
          <transition name="page" mode="out-in">
            <component :is="Component" class="main-content-page" />
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
import { useAppStore } from '@/store/app'
import AppBreadcrumb from '@/components/common/AppBreadcrumb.vue'
import AppPageBackground from '@/components/common/AppPageBackground.vue'
import AppSidebar from '@/components/common/AppSidebar.vue'

const { theme } = useTheme()
const appStore = useAppStore()

const route = useRoute()

const isStudioPage = computed(() => route.name === 'AgentStudio' || route.name === 'WorkflowStudio')
const isSidebarCollapsed = computed(() => !isStudioPage.value && appStore.sidebarCollapsed)
const hideSidebarProjectPanel = computed(() => route.name === 'RegistryProjectList')

function toggleSidebar() {
  appStore.toggleSidebar()
}

const isProjectManagementPage = computed(() =>
  route.path.startsWith('/registry/projects') ||
  route.path.startsWith('/scan-project'),
)

const isRegistryShell = computed(() => isProjectManagementPage.value)

</script>

<style scoped lang="scss">
.main-layout {
  height: 100vh;
  position: relative;
  overflow: hidden;
  background: color-mix(in srgb, var(--brand-selected-bg) 48%, #f8fbff);
}

.layout-workspace {
  position: relative;
  z-index: 1;
  flex-direction: column;
  min-width: 0;
}

/* 侧栏容器：浮动玻璃卡的呼吸边距（Glass Workbench v2）。 */
.sidebar-aside {
  overflow: hidden;
  position: relative;
  z-index: 10;
  box-sizing: border-box;
  padding: 14px 10px 14px 14px;
  transition: width 0.18s ease;
}

.breadcrumb-rail {
  flex: 0 0 var(--reachai-workbench-breadcrumb-height, 44px);
  display: flex;
  align-items: center;
  justify-content: flex-start;
  height: var(--reachai-workbench-breadcrumb-height, 44px);
  padding: 0 24px;
  background: transparent;
}

.main-layout.is-dark {
  background: #0c1424;
}

.main-content {
  background: transparent;
  overflow-y: auto;
  position: relative;
  isolation: isolate;
}

.main-content-page {
  position: relative;
  z-index: 1;
}

/*
 * registry-shell / studio-shell 仅保留结构差异（main 区零内边距，页面自带容器）。
 * 旧的 registry-shell 顶栏视觉特例已由统一浅色顶栏取代并删除。
 */
.registry-shell,
.studio-shell {
  .main-content {
    padding: 0;
  }
}

.studio-shell {
  background: var(--bg-primary);

  .main-content {
    background: var(--bg-primary);

    &::before {
      display: none;
    }
  }
}
</style>
