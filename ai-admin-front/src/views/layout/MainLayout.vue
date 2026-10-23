<template>
  <el-container
    class="main-layout"
    :class="[`layout-${layoutMode}`, { 'is-dark': theme === 'dark' }]"
  >
    <template v-if="isPlatformWorkspaceReady">
      <AppPageBackground v-if="!isStudioPage" />

      <el-aside v-if="!isStudioPage" :width="sidebarWidth" class="sidebar-aside">
        <AppSidebar
          :collapsed="isSidebarCollapsed"
          :hide-project-panel="hideSidebarProjectPanel"
          @toggle-collapse="toggleSidebar"
        />
      </el-aside>

      <el-container class="layout-workspace">
        <div v-if="!isStudioPage && !hideBreadcrumb" class="breadcrumb-rail">
          <AppBreadcrumb />
        </div>

        <el-main ref="mainContent" class="main-content">
          <router-view v-slot="{ Component }">
            <component :is="Component" :key="contentViewKey" class="main-content-page" />
          </router-view>
        </el-main>
      </el-container>
    </template>

    <ExplorationStageDialog v-if="isPlatformAuthenticated" />
  </el-container>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useTheme } from '@/composables/useTheme'
import { useAppStore } from '@/store/app'
import {
  isPlatformAuthenticated,
  isPlatformWorkspaceReady,
} from '@/auth/platformSession'
import AppBreadcrumb from '@/components/common/AppBreadcrumb.vue'
import ExplorationStageDialog from '@/components/common/ExplorationStageDialog.vue'
import AppPageBackground from '@/components/common/AppPageBackground.vue'
import AppSidebar from '@/components/common/AppSidebar.vue'
import { providePageProjectScope } from '@/composables/usePageProjectScope'
import { useResponsiveSidebar } from './useResponsiveSidebar'

const { theme } = useTheme()
const appStore = useAppStore()
providePageProjectScope()

const route = useRoute()
const mainContent = ref<{ $el: HTMLElement } | null>(null)
// Detail identities remount only the content; nested workbenches keep their shell.
const contentViewKey = computed(() => (
  typeof route.meta.rootViewKey === 'string' ? route.meta.rootViewKey : route.path
))

watch(contentViewKey, () => {
  const element = mainContent.value?.$el
  if (element) {
    element.scrollTop = 0
    element.scrollLeft = 0
  }
}, { flush: 'post' })

type LayoutMode = 'standard' | 'project-workbench' | 'edge-to-edge' | 'studio'

const layoutMode = computed<LayoutMode>(
  () => (route.meta.layoutMode as LayoutMode | undefined) ?? 'standard',
)
const isStudioPage = computed(() => layoutMode.value === 'studio')
const { isSidebarCollapsed, toggleSidebar } = useResponsiveSidebar({
  // 窄屏（≤768px）默认收起侧栏；个别页面可用 compactSidebarOnNarrow: false 退出。
  compactOnNarrow: () => route.meta.compactSidebarOnNarrow !== false,
  collapsedPreference: () => appStore.sidebarCollapsed,
  togglePreference: () => appStore.toggleSidebar(),
})
const sidebarWidth = computed(() =>
  isSidebarCollapsed.value
    ? 'var(--layout-sidebar-collapsed-width)'
    : 'var(--layout-sidebar-expanded-width)',
)
const hideSidebarProjectPanel = computed(() => Boolean(route.meta.hideSidebarProjectPanel))
const hideBreadcrumb = computed(() => Boolean(route.meta.hideBreadcrumb))

</script>

<style scoped lang="scss">
.main-layout {
  height: 100vh;
  position: relative;
  overflow: hidden;
  background: var(--surface-page-canvas);
}

.layout-workspace {
  position: relative;
  z-index: 1;
  flex-direction: column;
  min-width: 0;
  height: 100%;
}

/* 侧栏容器：浮动玻璃卡的呼吸边距（Glass Workbench v2）。 */
.sidebar-aside {
  overflow: hidden;
  position: relative;
  z-index: 10;
  box-sizing: border-box;
  padding: var(--layout-sidebar-inset-block) var(--layout-sidebar-inset-end)
    var(--layout-sidebar-inset-block) var(--layout-sidebar-inset-start);
  transition: width var(--motion-duration-normal) var(--motion-easing-standard);
}

.breadcrumb-rail {
  flex: 0 0 auto;
  display: flex;
  align-items: center;
  justify-content: flex-start;
  min-height: var(--layout-breadcrumb-height);
  padding: 0 var(--layout-content-inline);
  padding-top: var(--layout-heading-spacing);
  background: transparent;
}

.main-content {
  background: transparent;
  overflow-y: auto;
  position: relative;
  isolation: isolate;
  padding: 0;
  height: 100%;
  display: flex;
  flex-direction: column;
}

.main-content-page {
  position: relative;
  z-index: 1;
  flex: 1 0 auto;
  width: 100%;
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
