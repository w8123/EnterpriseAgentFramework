<template>
  <el-container
    class="main-layout"
    :class="[`layout-${layoutMode}`, { 'is-dark': theme === 'dark' }]"
  >
    <AppPageBackground v-if="!isStudioPage" />

    <el-aside v-if="!isStudioPage" :width="sidebarWidth" class="sidebar-aside">
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

    <ExplorationStageDialog />
  </el-container>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { useTheme } from '@/composables/useTheme'
import { useAppStore } from '@/store/app'
import AppBreadcrumb from '@/components/common/AppBreadcrumb.vue'
import ExplorationStageDialog from '@/components/common/ExplorationStageDialog.vue'
import AppPageBackground from '@/components/common/AppPageBackground.vue'
import AppSidebar from '@/components/common/AppSidebar.vue'

const { theme } = useTheme()
const appStore = useAppStore()

const route = useRoute()

type LayoutMode = 'standard' | 'project-workbench' | 'edge-to-edge' | 'studio'

const layoutMode = computed<LayoutMode>(
  () => (route.meta.layoutMode as LayoutMode | undefined) ?? 'standard',
)
const isStudioPage = computed(() => layoutMode.value === 'studio')
const isSidebarCollapsed = computed(() => appStore.sidebarCollapsed)
const sidebarWidth = computed(() =>
  isSidebarCollapsed.value
    ? 'var(--layout-sidebar-collapsed-width)'
    : 'var(--layout-sidebar-expanded-width)',
)
const hideSidebarProjectPanel = computed(() => Boolean(route.meta.hideSidebarProjectPanel))

function toggleSidebar() {
  appStore.toggleSidebar()
}

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
  padding: var(--layout-sidebar-inset-block) var(--layout-sidebar-inset-end)
    var(--layout-sidebar-inset-block) var(--layout-sidebar-inset-start);
  transition: width 0.18s ease;
}

.breadcrumb-rail {
  flex: 0 0 var(--layout-breadcrumb-height);
  display: flex;
  align-items: center;
  justify-content: flex-start;
  height: var(--layout-breadcrumb-height);
  padding: 0 var(--layout-content-inline);
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
  padding: 0;
}

.main-content-page {
  position: relative;
  z-index: 1;
}

.layout-studio {
  background: var(--bg-primary);

  .main-content {
    background: var(--bg-primary);

    &::before {
      display: none;
    }
  }
}
</style>
