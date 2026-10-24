<template>
  <nav class="app-sidebar glass-surface-shell glass-surface-navigation liquid-glass-host" :class="{ 'is-collapsed': collapsed }">
    <LiquidGlassLayer />
    <div class="brand">
      <span class="brand-glow" aria-hidden="true" />
      <img class="brand-logo" src="/reachai-logo-tile.png" alt="ReachAI" />
      <div v-if="!collapsed" class="brand-text">
        <span class="brand-title">ReachAI</span>
        <span class="brand-sub">企业 AI 能力中台</span>
      </div>
      <button
        class="sidebar-collapse-button"
        :class="{ 'is-collapsed-control': collapsed }"
        type="button"
        :aria-label="collapsed ? '展开侧栏' : '折叠侧栏'"
        @click="toggleSidebarCollapse"
      >
        <el-icon><component :is="collapsed ? ArrowRight : ArrowLeft" /></el-icon>
      </button>
    </div>

    <section v-if="!collapsed && !hideProjectPanel" class="sidebar-project-panel" aria-label="当前范围">
      <div class="sidebar-project-heading">
        <span class="sidebar-section-caption">当前范围</span>
        <span class="sidebar-project-pill" :class="{ active: sidebarHasResolvedScope }">
          {{ sidebarProjectPillLabel }}
        </span>
      </div>
      <el-select
        class="sidebar-project-select"
        popper-class="sidebar-project-popper"
        :model-value="sidebarProjectSelection"
        :loading="sidebarProjectLoading"
        filterable
        :placeholder="sidebarProjectPlaceholder"
        @update:model-value="handleProjectChange"
        @visible-change="handleProjectVisibleChange"
      >
        <template #prefix>
          <span class="project-status-dot" :class="{ active: sidebarHasResolvedScope }" />
        </template>
        <el-option
          v-if="usesPageProjectScope"
          :value="ALL_SCOPE_VALUE"
          label="全部项目"
          :disabled="!sidebarCanSelectAll"
        />
        <el-option v-else :value="NO_PROJECT_VALUE" label="平台范围" />
        <el-option
          v-for="project in sidebarProjectOptions"
          :key="project.id"
          :label="sidebarProjectOptionLabel(project)"
          :value="project.id"
          :disabled="sidebarProjectOptionDisabled(project)"
        >
          <div class="sidebar-project-option">
            <span>{{ project.name }}</span>
            <small>{{ sidebarProjectOptionHint(project) }}</small>
          </div>
        </el-option>
      </el-select>
      <div
        v-if="projectCatalogFeedback"
        class="sidebar-project-feedback"
        :class="projectCatalogFeedbackTone"
        :role="projectCatalogFeedbackRole"
        aria-live="polite"
      >
        <span>{{ projectCatalogFeedback }}</span>
        <button
          v-if="showProjectRetry || retryingProjectOptions"
          class="sidebar-project-retry"
          type="button"
          :disabled="sidebarProjectLoading || retryingProjectOptions"
          :aria-busy="sidebarProjectLoading || retryingProjectOptions"
          :aria-label="projectRetryLabel"
          @click="retryProjectOptions"
        >
          {{ projectRetryButtonLabel }}
        </button>
      </div>
    </section>

    <el-scrollbar class="menu-scroll" :tabindex="-1">
      <el-menu
        ref="sidebarMenuRef"
        :default-active="activeMenu"
        :default-openeds="openGroups"
        :collapse="collapsed"
        :collapse-transition="false"
        router
        class="sidebar-menu"
        @open="scheduleMenuSync"
        @close="scheduleMenuSync"
      >
        <template v-for="(entry, i) in visibleSidebarMenu" :key="entry.kind === 'item' ? entry.index : `group-${i}`">
          <li v-if="entry.kind === 'group'" class="menu-group" :class="{ 'is-first': i === 0 }" role="presentation">
            <span class="menu-group-label">{{ entry.label }}</span>
          </li>

          <el-menu-item
            v-else-if="!entry.children"
            :ref="(element: Element | ComponentPublicInstance | null) => setMenuNode(entry.index, element)"
            :index="entry.index"
            :data-sidebar-index="entry.index"
            :tabindex="tabindex(entry.index)"
            :aria-label="entry.label"
            :aria-current="activeMenu === entry.index ? 'page' : undefined"
            class="sidebar-keyboard-item"
            @focus="onMenuFocus"
            @keydown="onMenuKeydown"
          >
            <div v-if="activeMenu === entry.index" class="sidebar-selection-glass glass-surface-lens glass-surface-selection liquid-glass-host" aria-hidden="true">
              <LiquidGlassLayer :bezel="8" :depth="14" source="wallpaper" />
            </div>
            <el-icon class="menu-icon"><component :is="entry.icon" /></el-icon>
            <span class="menu-label">{{ entry.label }}</span>
          </el-menu-item>

          <el-sub-menu
            v-else
            :ref="(element: Element | ComponentPublicInstance | null) => setMenuNode(entry.index, element)"
            :index="entry.index"
            :data-sidebar-index="entry.index"
            :tabindex="tabindex(entry.index)"
            :aria-label="entry.label"
            aria-haspopup="menu"
            :aria-expanded="isMenuOpen(entry.index)"
            popper-class="sidebar-keyboard-popper"
            @focus="onMenuFocus"
            @keydown="onMenuKeydown"
          >
            <template #title>
              <div
                v-if="collapsed && entry.children.some(leaf => leaf.index === activeMenu)"
                class="sidebar-selection-glass glass-surface-lens glass-surface-selection liquid-glass-host"
                aria-hidden="true"
              >
                <LiquidGlassLayer :bezel="8" :depth="14" source="wallpaper" />
              </div>
              <el-icon class="menu-icon"><component :is="entry.icon" /></el-icon>
              <span class="menu-label">{{ entry.label }}</span>
            </template>
            <el-menu-item
              v-for="leaf in entry.children"
              :key="leaf.index"
              :ref="(element: Element | ComponentPublicInstance | null) => setMenuNode(leaf.index, element)"
              :index="leaf.index"
              :data-sidebar-index="leaf.index"
              :data-sidebar-parent="entry.index"
              :tabindex="tabindex(leaf.index)"
              :aria-label="leaf.label"
              :aria-current="activeMenu === leaf.index ? 'page' : undefined"
              class="sidebar-keyboard-item"
              @focus="onMenuFocus"
              @keydown="onMenuKeydown"
            >
              <div v-if="activeMenu === leaf.index" class="sidebar-selection-glass glass-surface-lens glass-surface-selection liquid-glass-host" aria-hidden="true">
                <LiquidGlassLayer :bezel="7" :depth="12" source="wallpaper" />
              </div>
              <span class="menu-label leaf">{{ leaf.label }}</span>
            </el-menu-item>
          </el-sub-menu>
        </template>
      </el-menu>
    </el-scrollbar>

    <footer v-if="!collapsed" ref="sidebarFooterRef" class="sidebar-footer">
      <el-popover
        :visible="Boolean(activeFooterPanel)"
        :virtual-ref="sidebarFooterRef"
        virtual-triggering
        placement="right-end"
        :fallback-placements="['top-start', 'top-end', 'bottom-start']"
        :popper-options="footerPopoverOptions"
        :offset="32"
        :width="activeFooterPanel === 'settings' ? 'min(416px, calc(100vw - 24px))' : 'min(280px, calc(100vw - 24px))'"
        :show-arrow="false"
        :persistent="false"
        popper-class="sidebar-preferences-popper"
        @after-enter="focusFooterPanel"
      >
        <div
          v-if="activeFooterPanel"
          ref="footerPopoverContentRef"
          class="sidebar-footer-popover glass-surface-overlay liquid-glass-host"
          role="dialog"
          :aria-label="activeFooterPanel === 'settings' ? '外观设置' : '个人设置'"
          @click.stop
        >
          <LiquidGlassLayer :bezel="18" :depth="24" />
          <div class="footer-popover-header">
            <div class="footer-popover-heading">
              <h2 class="footer-popover-title" tabindex="-1">{{ activeFooterPanel === 'settings' ? '外观设置' : '个人中心' }}</h2>
              <p v-if="activeFooterPanel === 'settings'">选择适合你的工作台外观</p>
            </div>
            <button class="footer-close" type="button" aria-label="关闭账户与设置" @click="closeFooterPanel">
              <el-icon><Close /></el-icon>
            </button>
          </div>
          <div class="footer-tab-panel">
            <div v-if="activeFooterPanel === 'profile'" class="profile-panel">
              <button
                class="profile-link"
                type="button"
                @click="openMyMemories"
              >
                <el-icon><Memo /></el-icon>
                <span>我的记忆</span>
              </button>
              <button
                class="profile-action"
                type="button"
                :disabled="loggingOut"
                :aria-busy="loggingOut"
                @click="handleLogout"
              >
                <el-icon><SwitchButton /></el-icon>
                <span>{{ loggingOut ? '正在退出…' : '退出账号' }}</span>
              </button>
            </div>

            <div v-else class="appearance-panel">
              <section class="appearance-section" aria-label="主题配色">
                <div class="appearance-section-heading">
                  <h3>主题配色</h3>
                  <span>配色与背景同步切换</span>
                </div>
                <div class="brand-choice-list" role="radiogroup" aria-label="主题色">
                  <button
                    v-for="option in brandOptions"
                    :key="option.value"
                    class="brand-choice"
                    :class="{ active: option.value === brand }"
                    type="button"
                    role="radio"
                    :aria-checked="option.value === brand"
                    @click="setBrand(option.value)"
                  >
                    <span
                      class="appearance-swatch"
                      :data-brand="option.value"
                      :data-brand-option="option.value"
                      :data-theme="theme"
                      aria-hidden="true"
                    />
                    <span class="brand-choice-label">{{ option.label }}</span>
                    <el-icon v-if="option.value === brand" class="selected-check"><Check /></el-icon>
                  </button>
                </div>
              </section>

              <section class="appearance-section appearance-preferences" aria-label="显示设置">
                <div v-if="darkThemeEntryReady" class="appearance-field">
                  <h3>明暗模式</h3>
                  <div class="appearance-segment" role="radiogroup" aria-label="明暗模式">
                    <button
                      v-for="option in themeModeOptions"
                      :key="option.value"
                      class="appearance-segment__item"
                      :class="{ active: option.value === themeMode }"
                      type="button"
                      role="radio"
                      :aria-checked="option.value === themeMode"
                      @click="setThemeMode(option.value)"
                    >
                      <el-icon><component :is="themeModeIcons[option.value]" /></el-icon>
                      {{ option.label }}
                    </button>
                  </div>
                </div>

                <div class="appearance-field">
                  <h3>显示密度</h3>
                  <div class="appearance-segment" role="radiogroup" aria-label="显示密度">
                    <button
                      v-for="option in densityOptions"
                      :key="option.value"
                      class="appearance-segment__item"
                      :class="{ active: option.value === density }"
                      type="button"
                      role="radio"
                      :aria-checked="option.value === density"
                      @click="setDensity(option.value)"
                    >
                      {{ option.label }}
                    </button>
                  </div>
                </div>
              </section>

              <section class="appearance-section" aria-label="玻璃效果">
                <div class="appearance-section-heading">
                  <h3>玻璃效果</h3>
                </div>
                <div class="appearance-material">
                  <label class="appearance-toggle">
                    <span>
                      <strong>玻璃质感</strong>
                      <small>{{ glassTextureEnabled ? '让界面呈现通透的玻璃效果' : '当前使用不透明表面' }}</small>
                    </span>
                    <el-switch
                      :model-value="glassTextureEnabled"
                      aria-label="玻璃质感"
                      @update:model-value="(value: string | number | boolean) => setReduceTransparency(!Boolean(value))"
                    />
                  </label>
                  <div v-if="glassTextureEnabled" class="appearance-material-style">
                    <div class="appearance-field">
                      <h3>玻璃样式</h3>
                      <div class="appearance-segment" role="radiogroup" aria-label="玻璃样式">
                        <button
                          class="appearance-segment__item"
                          :class="{ active: glassRefraction }"
                          type="button"
                          role="radio"
                          :aria-checked="glassRefraction"
                          @click="setGlassRefraction(true)"
                        >
                          液态玻璃
                        </button>
                        <button
                          class="appearance-segment__item"
                          :class="{ active: !glassRefraction }"
                          type="button"
                          role="radio"
                          :aria-checked="!glassRefraction"
                          @click="setGlassRefraction(false)"
                        >
                          毛玻璃
                        </button>
                      </div>
                    </div>
                    <p v-if="glassRefraction && !refractionSupported" class="appearance-material-hint">
                      当前浏览器显示为毛玻璃效果
                    </p>
                  </div>
                </div>
              </section>
            </div>
          </div>
          <div v-if="activeFooterPanel === 'settings'" class="appearance-footer">
            <el-icon><Check /></el-icon>
            <span>更改即时生效 · 自动保存</span>
          </div>
        </div>
      </el-popover>

      <button
        class="footer-entry"
        :class="{ active: activeFooterPanel === 'profile' }"
        type="button"
        aria-haspopup="dialog"
        :aria-expanded="activeFooterPanel === 'profile'"
        @click="toggleFooterPanel('profile')"
      >
        <el-icon><User /></el-icon>
        <span>个人</span>
      </button>
      <span class="footer-divider" aria-hidden="true" />
      <button
        class="footer-entry"
        :class="{ active: activeFooterPanel === 'settings' }"
        type="button"
        aria-haspopup="dialog"
        :aria-expanded="activeFooterPanel === 'settings'"
        @click="toggleFooterPanel('settings')"
      >
        <el-icon><Setting /></el-icon>
        <span>设置</span>
      </button>
    </footer>
  </nav>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch, type ComponentPublicInstance } from 'vue'
import { ElPopover, type MenuInstance } from 'element-plus'
import { useRoute, useRouter } from 'vue-router'
import {
  ArrowLeft,
  ArrowRight,
  Check,
  Close,
  Memo,
  Monitor,
  Moon,
  Setting,
  Sunny,
  SwitchButton,
  User,
} from '@element-plus/icons-vue'
import { logoutPlatform } from '@/api/platformAuth'
import { platformSessionUser } from '@/auth/platformSession'
import { DARK_THEME_ENTRY_READY, useTheme } from '@/composables/useTheme'
import { supportsLiquidGlassRefraction } from '@/composables/liquidGlass'
import LiquidGlassLayer from './LiquidGlassLayer.vue'
import { useProjectStore } from '@/store/project'
import { usePageProjectScope } from '@/composables/usePageProjectScope'
import {
  filterSidebarMenu,
  resolveActiveMenu,
  resolveOpenGroups,
  sidebarMenu,
} from './sidebarMenu'
import { useSidebarKeyboardNavigation } from './useSidebarKeyboardNavigation'

const props = withDefaults(defineProps<{
  collapsed?: boolean
  hideProjectPanel?: boolean
}>(), {
  collapsed: false,
  hideProjectPanel: false,
})
const emit = defineEmits<{
  (event: 'toggle-collapse'): void
}>()

const route = useRoute()
const router = useRouter()
const projectStore = useProjectStore()
const pageProjectScope = usePageProjectScope()
const {
  theme,
  brand,
  brandOptions,
  setBrand,
  themeMode,
  themeModeOptions,
  setThemeMode,
  density,
  densityOptions,
  setDensity,
  glassRefraction,
  setGlassRefraction,
  reduceTransparency,
  setReduceTransparency,
} = useTheme()
// 正向开关复用现有材质偏好，让全站表面与首屏外观保持一致。
const glassTextureEnabled = computed(() => !reduceTransparency.value)
const darkThemeEntryReady = DARK_THEME_ENTRY_READY
const themeModeIcons = { light: Sunny, dark: Moon, system: Monitor }
const footerPopoverOptions = {
  strategy: 'fixed' as const,
  modifiers: [{ name: 'preventOverflow', options: { padding: 12, altAxis: true, tether: false } }],
}
const refractionSupported = supportsLiquidGlassRefraction()
const collapsed = computed(() => props.collapsed)
const hideProjectPanel = computed(() => props.hideProjectPanel)
const activeMenu = computed(() => resolveActiveMenu(route.path, route.meta.activeMenu))
const openGroups = computed(() => resolveOpenGroups(route.path))
const visibleSidebarMenu = computed(() => filterSidebarMenu(
  sidebarMenu,
  platformSessionUser.value?.permissions ?? [],
))
const sidebarMenuRef = ref<MenuInstance>()
const { setMenuNode, tabindex, isMenuOpen, onMenuFocus, onMenuKeydown, scheduleMenuSync } = useSidebarKeyboardNavigation(
  sidebarMenuRef, visibleSidebarMenu, collapsed, activeMenu,
)
const activeFooterPanel = ref<'profile' | 'settings' | null>(null)
const loggingOut = ref(false)
const retryingProjectOptions = ref(false)
const NO_PROJECT_VALUE = '__reachai_no_project__'
const ALL_SCOPE_VALUE = 'all'
const usesPageProjectScope = computed(() => Boolean(
  pageProjectScope?.isRouteConnected.value && pageProjectScope.isActive.value,
))
const sidebarProjectOptions = computed(() => (
  usesPageProjectScope.value && pageProjectScope
    ? pageProjectScope.readableProjects.value
    : projectStore.projects
))
const sidebarCanSelectAll = computed(() => (
  usesPageProjectScope.value && pageProjectScope
    ? pageProjectScope.canSelectAll.value
    : false
))
const sidebarProjectSelection = computed(() => (
  usesPageProjectScope.value && pageProjectScope
    ? pageProjectScope.selection.value
    : projectStore.currentProject?.id ?? NO_PROJECT_VALUE
))
const sidebarProjectLoading = computed(() => (
  usesPageProjectScope.value && pageProjectScope
    ? pageProjectScope.isCatalogLoading.value
    : projectStore.loading
))
const sidebarProjectPlaceholder = computed(() => (
  usesPageProjectScope.value && pageProjectScope
    ? pageProjectScope.currentScopeLabel.value
    : '平台范围'
))
const sidebarHasResolvedScope = computed(() => (
  usesPageProjectScope.value && pageProjectScope
    ? pageProjectScope.selection.value !== null
    : projectStore.currentProject !== null
))
const sidebarProjectPillLabel = computed(() => {
  if (usesPageProjectScope.value && pageProjectScope) {
    if (pageProjectScope.selection.value === ALL_SCOPE_VALUE) {
      return `全部 ${pageProjectScope.resourceLabel.value}`
    }
    if (pageProjectScope.selection.value !== null) return '项目级'
    return '范围未确认'
  }
  return projectStore.currentProject ? '项目级' : '平台级'
})

function sidebarProjectOptionDisabled(project: { projectCode?: string | null }) {
  return Boolean(
    usesPageProjectScope.value
      && pageProjectScope?.requiresProjectCode.value
      && !project.projectCode?.trim(),
  )
}

function sidebarProjectOptionLabel(project: { name: string; projectCode?: string | null }) {
  return sidebarProjectOptionDisabled(project)
    ? `${project.name}（未配置项目编码）`
    : project.name
}

function sidebarProjectOptionHint(project: { id: number; projectCode?: string | null }) {
  return project.projectCode?.trim() || (
    usesPageProjectScope.value && pageProjectScope?.requiresProjectCode.value
      ? '未配置项目编码'
      : `ID ${project.id}`
  )
}
const projectCatalogFeedback = computed(() => {
  if (usesPageProjectScope.value && pageProjectScope) {
    if (pageProjectScope.feedbackMessage.value) return pageProjectScope.feedbackMessage.value
    if (pageProjectScope.hasCatalogError.value) return '项目列表暂时无法刷新'
    if (projectStore.status === 'loading') {
      return projectStore.hasLoadedSuccessfully ? '正在刷新项目列表…' : '正在加载项目列表…'
    }
    return ''
  }
  if (retryingProjectOptions.value) return '正在重试项目列表…'
  if (projectStore.status === 'error') {
    return projectStore.hasLoadedSuccessfully
      ? '项目列表暂时无法刷新'
      : (projectStore.errorMessage || '项目列表加载失败')
  }
  if (projectStore.status === 'ready' && !projectStore.projects.length) {
    return '暂无可用项目，当前为平台范围'
  }
  if (projectStore.status === 'loading') {
    return projectStore.hasLoadedSuccessfully ? '正在刷新项目列表…' : '正在加载项目列表…'
  }
  if (projectStore.status === 'idle') return '正在加载项目列表…'
  return ''
})
const projectCatalogFeedbackTone = computed(() => {
  if (usesPageProjectScope.value && pageProjectScope) {
    if (pageProjectScope.hasCatalogError.value || pageProjectScope.status.value === 'blocked') return 'is-error'
    return 'is-loading'
  }
  if (projectStore.status === 'error') return 'is-error'
  if (projectStore.status === 'ready' && !projectStore.projects.length) return 'is-empty'
  return 'is-loading'
})
const projectCatalogFeedbackRole = computed(() => (
  (usesPageProjectScope.value && pageProjectScope
    ? pageProjectScope.hasCatalogError.value || pageProjectScope.status.value === 'blocked'
    : projectStore.status === 'error') ? 'alert' : 'status'
))
const projectRetryLabel = computed(() => (
  usesPageProjectScope.value && pageProjectScope
    ? (pageProjectScope.recoveryLabel.value || '重试加载项目列表')
    : '重试加载项目列表'
))
const showProjectRetry = computed(() => (
  usesPageProjectScope.value && pageProjectScope
    ? pageProjectScope.recoveryAction.value === 'retry-catalog'
      || pageProjectScope.recoveryAction.value === 'retry-normalization'
    : projectStore.status === 'error'
))
const projectRetryButtonLabel = computed(() => {
  if (sidebarProjectLoading.value || retryingProjectOptions.value) return '正在重试…'
  return projectRetryLabel.value === '重试加载项目列表'
    ? '重试'
    : projectRetryLabel.value
})

function ensureProjectOptionsLoaded() {
  if (!hideProjectPanel.value && !usesPageProjectScope.value && projectStore.status === 'idle') {
    void projectStore.fetchProjects()
  }
}

onMounted(() => {
  ensureProjectOptionsLoaded()
})

watch(hideProjectPanel, () => {
  ensureProjectOptionsLoaded()
})

function toggleSidebarCollapse() {
  emit('toggle-collapse')
}

function handleProjectChange(value: number | string | null | undefined) {
  if (usesPageProjectScope.value && pageProjectScope) {
    if (value === ALL_SCOPE_VALUE) {
      void pageProjectScope.selectScope(ALL_SCOPE_VALUE)
    } else if (typeof value === 'number') {
      void pageProjectScope.selectScope(value)
    }
    return
  }
  projectStore.selectCurrentProject(
    typeof value === 'number' ? value : null,
  )
}

function handleProjectVisibleChange(visible: boolean) {
  if (visible) {
    if (usesPageProjectScope.value && pageProjectScope) {
      void pageProjectScope.refreshCatalog()
    } else {
      void projectStore.fetchProjects()
    }
  }
}

async function retryProjectOptions() {
  if (projectStore.loading || retryingProjectOptions.value) return
  retryingProjectOptions.value = true
  try {
    if (usesPageProjectScope.value && pageProjectScope) {
      if (pageProjectScope.recoveryAction.value === 'retry-normalization') {
        await pageProjectScope.retryScope()
      } else if (pageProjectScope.recoveryAction.value === 'retry-catalog') {
        await pageProjectScope.retryCatalog()
      }
    } else {
      await projectStore.fetchProjects()
    }
  } finally {
    retryingProjectOptions.value = false
  }
}

function toggleFooterPanel(panel: 'profile' | 'settings') {
  activeFooterPanel.value = activeFooterPanel.value === panel ? null : panel
}

// 浮层挂到 body，避免被侧栏的裁切和玻璃变换限制。
const sidebarFooterRef = ref<HTMLElement>()
const footerPopoverContentRef = ref<HTMLElement>()
function focusFooterPanel() {
  footerPopoverContentRef.value?.querySelector<HTMLElement>('.footer-popover-title')?.focus({ preventScroll: true })
}
function closeFooterPanel() {
  const entry = sidebarFooterRef.value?.querySelector<HTMLElement>('.footer-entry.active')
  activeFooterPanel.value = null
  entry?.focus({ preventScroll: true })
}
function closeFooterPanelOnOutsidePointer(event: PointerEvent) {
  const target = event.target as Node | null
  if (target && (sidebarFooterRef.value?.contains(target) || footerPopoverContentRef.value?.contains(target))) return
  activeFooterPanel.value = null
}
function closeFooterPanelOnEscape(event: KeyboardEvent) {
  if (event.key === 'Escape') closeFooterPanel()
}
function unbindFooterPanelDismiss() {
  document.removeEventListener('pointerdown', closeFooterPanelOnOutsidePointer, true)
  document.removeEventListener('keydown', closeFooterPanelOnEscape)
}
watch(activeFooterPanel, (panel) => {
  unbindFooterPanelDismiss()
  if (!panel) return
  document.addEventListener('pointerdown', closeFooterPanelOnOutsidePointer, true)
  document.addEventListener('keydown', closeFooterPanelOnEscape)
})
watch(collapsed, () => { activeFooterPanel.value = null })
onBeforeUnmount(unbindFooterPanelDismiss)

async function openMyMemories() {
  activeFooterPanel.value = null
  await router.push('/me/memories')
}

async function handleLogout() {
  if (loggingOut.value) return
  loggingOut.value = true
  try {
    await logoutPlatform()
    activeFooterPanel.value = null
    await router.replace('/login')
  } catch {
    // Keep the live cookie session available so the user can retry.
  } finally {
    loggingOut.value = false
  }
}
</script>

<style scoped lang="scss">
/*
 * Glass Workbench v2 玻璃侧栏（对齐 Figma：浮动玻璃卡 + 分组标题 + 精致激活态）。
 * 标题/文字/图标/分隔消费全局语义角色；交互与激活态跟随 data-brand，
 * 根表面由 glass-surface-shell 统一提供并负责透明度降级。
 */
.app-sidebar {
  --sb-radius: var(--radius-lg);
  --sb-title: var(--text-primary);
  --sb-subtitle: var(--text-muted);
  --sb-caption: var(--text-muted);
  --sb-text: var(--text-secondary);
  --sb-child: var(--text-muted);
  --sb-icon: var(--text-muted);
  --sb-divider: var(--border-divider);
  --sb-hover-bg: rgb(var(--brand-primary-rgb) / 0.08);
  --sb-parent-active-bg: rgb(var(--brand-primary-rgb) / 0.1);
  --sb-parent-active-text: var(--brand-on-surface);
  --sb-child-active-bg: rgb(var(--brand-primary-rgb) / 0.12);
  --sb-child-active-text: var(--brand-on-surface);
  --sb-active-icon: var(--brand-on-surface);
  --sb-rail: var(--brand-primary);

  height: 100%;
  display: flex;
  flex-direction: column;
  position: relative;
  border-radius: var(--sb-radius);
  overflow: hidden;
}

/* ambient 光晕：顶部冷蓝 + 底部薄荷，营造玻璃景深 */
.app-sidebar::before,
.app-sidebar::after {
  content: '';
  display: var(--glass-decoration-display);
  position: absolute;
  border-radius: 50%;
  pointer-events: none;
  z-index: 0;
}

.app-sidebar::before {
  width: 214px;
  height: 190px;
  left: -72px;
  top: -70px;
  background: rgb(var(--brand-hover-rgb) / 0.1);
  filter: blur(18px);
}

.app-sidebar::after {
  width: 210px;
  height: 220px;
  left: -52px;
  bottom: -46px;
  background: rgb(var(--brand-hover-rgb) / 0.08);
  filter: blur(23px);
}

.brand,
.sidebar-project-panel,
.menu-scroll,
.sidebar-footer {
  position: relative;
  z-index: 1;
}

/* ── 品牌区：logo + 光晕 + 主副标题，底部细分隔 ── */
.brand {
  display: flex;
  align-items: center;
  gap: 14px;
  height: 84px;
  padding: 0 46px 0 20px;
  flex-shrink: 0;
  overflow: hidden;
}

.brand::after {
  content: '';
  position: absolute;
  left: 20px;
  right: 20px;
  bottom: 0;
  height: 1px;
  background: var(--navigation-divider);
}

.brand-glow {
  position: absolute;
  left: 12px;
  top: 50%;
  transform: translateY(-50%);
  width: 50px;
  height: 50px;
  border-radius: 50%;
  background: rgb(var(--brand-primary-rgb) / 0.14);
  filter: blur(7px);
  pointer-events: none;
}

.brand-logo {
  position: relative;
  width: 40px;
  height: 40px;
  border-radius: 11px;
  display: block;
}

.brand-text {
  display: flex;
  flex-direction: column;
  justify-content: center;
  gap: 3px;
  min-width: 0;
}

.brand-title {
  font-size: 20px;
  font-weight: 700;
  line-height: 1.25;
  letter-spacing: 0.01em;
  color: var(--navigation-ink);
}

.brand-sub {
  font-size: 11.5px;
  line-height: 1.3;
  color: var(--navigation-ink-muted);
  white-space: nowrap;
}

.sidebar-collapse-button {
  position: absolute;
  top: 28px;
  right: 18px;
  width: 24px;
  height: 24px;
  display: grid;
  place-items: center;
  border: 1px solid var(--border-glass-edge);
  border-radius: 999px;
  background: var(--surface-solid-control);
  box-shadow: var(--inner-highlight), 0 1px 2px rgb(15 23 42 / 0.08);
  color: var(--sb-icon);
  cursor: pointer;
  transition: color 0.16s ease, border-color 0.16s ease, background 0.16s ease, transform 0.16s ease;

  .el-icon {
    font-size: 14px;
  }

  &:hover {
    color: var(--sb-parent-active-text);
    border-color: rgb(var(--brand-primary-rgb) / 0.24);
    background: var(--surface-solid-opaque);
    transform: translateX(-1px);
  }

  &.is-collapsed-control {
    position: relative;
    top: auto;
    bottom: auto;
    left: auto;
    right: auto;
    width: 22px;
    height: 22px;
    margin: 8px auto 0;
    transform: none;

    &:hover {
      transform: none;
    }
  }
}

.is-collapsed .brand {
  flex-direction: column;
  justify-content: flex-start;
  gap: 12px;
  height: auto;
  min-height: 84px;
  padding: 16px 0 10px;
}

.is-collapsed .brand-logo {
  flex-shrink: 0;
}

.is-collapsed .brand::after {
  display: none;
}

/* ── 当前项目选择 ── */
.sidebar-project-panel {
  flex-shrink: 0;
  padding: 20px 20px 12px;
}

.sidebar-project-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin: 0 2px 8px 4px;
}

.sidebar-project-heading .sidebar-section-caption {
  margin: 0;
}

.sidebar-section-caption {
  margin: 0 0 8px 4px;
  color: var(--navigation-ink-muted);
  font-size: 12px;
  font-weight: 500;
  line-height: 16px;
}

.sidebar-project-select {
  width: 100%;

  /* 与侧栏同一块玻璃：半透明胶囊 + 上沿高光，不再是一块不透明白底。 */
  :deep(.el-select__wrapper) {
    min-height: 42px;
    padding: 0 14px 0 14px;
    border-radius: var(--radius-pill);
    background: var(--surface-glass-frost);
    box-shadow: var(--shadow-glass-frost), 0 0 0 1px var(--border-glass-edge) inset;
    -webkit-backdrop-filter: blur(var(--glass-blur-frost)) saturate(var(--glass-saturation));
    backdrop-filter: blur(var(--glass-blur-frost)) saturate(var(--glass-saturation));
    transition: box-shadow var(--motion-duration-fast) ease, background var(--motion-duration-fast) ease;
  }

  /* 可搜索时输入框会拿到焦点；焦点提示由整颗胶囊的描边承担，不再出现方形焦点框。 */
  :deep(.el-select__input:focus-visible) {
    outline: none;
  }

  :deep(.el-select__wrapper:hover),
  :deep(.el-select__wrapper.is-focused) {
    box-shadow: var(--shadow-glass-frost), 0 0 0 1px rgb(var(--brand-primary-rgb) / 0.38) inset;
  }

  :deep(.el-select__selected-item),
  :deep(.el-select__placeholder) {
    color: var(--sb-title);
    font-size: 14px;
    font-weight: 700;
  }

  :deep(.el-select__caret) {
    color: var(--sb-icon);
  }
}

.project-status-dot {
  width: 7px;
  height: 7px;
  border-radius: 999px;
  background: color-mix(in srgb, var(--status-neutral) 50%, transparent);

  &.active {
    background: var(--status-success);
    box-shadow: 0 0 0 3px var(--status-success-soft);
  }
}

.sidebar-project-pill {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  height: 20px;
  padding: 0 8px;
  border: 1px solid var(--border-glass-edge);
  border-radius: 999px;
  background: var(--surface-glass-frost);
  -webkit-backdrop-filter: blur(var(--glass-blur-frost));
  backdrop-filter: blur(var(--glass-blur-frost));
  color: var(--status-neutral);
  font-size: 11px;
  font-weight: 650;
  white-space: nowrap;
  pointer-events: none;

  &.active {
    border-color: color-mix(in srgb, var(--status-success) 28%, transparent);
    color: var(--status-success);
  }
}

.sidebar-project-feedback {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin: 8px 4px 0;
  color: var(--sb-caption);
  font-size: 11px;
  line-height: 16px;

  > span {
    min-width: 0;
  }

  &.is-error {
    color: var(--el-color-danger);
  }

  &.is-empty {
    color: var(--sb-caption);
  }
}

.sidebar-project-retry {
  flex: 0 0 auto;
  min-height: 24px;
  padding: 0 8px;
  border: 1px solid var(--sb-divider);
  border-radius: 7px;
  background: transparent;
  color: var(--sb-parent-active-text);
  font-size: 11px;
  font-weight: 700;
  cursor: pointer;

  &:hover:not(:disabled) {
    border-color: rgb(var(--brand-primary-rgb) / 0.32);
    background: var(--sb-hover-bg);
  }

  &:focus-visible {
    outline: 2px solid rgb(var(--brand-primary-rgb) / 0.4);
    outline-offset: 2px;
  }

  &:disabled {
    cursor: wait;
    opacity: 0.68;
  }
}

:global(.sidebar-project-popper.el-select__popper.el-popper) {
  overflow: hidden;
  border: 1px solid var(--border-glass-edge);
  border-radius: var(--radius-lg);
  background: var(--surface-glass-overlay);
  box-shadow: var(--shadow-overlay);
  -webkit-backdrop-filter: blur(var(--glass-blur-overlay)) saturate(var(--glass-saturation));
  backdrop-filter: blur(var(--glass-blur-overlay)) saturate(var(--glass-saturation));
}

:global(.sidebar-project-popper .el-select-dropdown__item) {
  margin: 2px 6px;
  border-radius: var(--radius-md);
}

:global(.sidebar-project-popper .el-select-dropdown__item.is-selected) {
  background: rgb(var(--brand-selected-rgb) / 0.7);
}

:global(.sidebar-project-popper .el-popper__arrow::before) {
  border-color: var(--border-glass-edge);
  background: var(--surface-solid-overlay);
}

:global(.sidebar-project-option) {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  width: 100%;
}

:global(.sidebar-project-option small) {
  color: var(--text-muted);
  font-size: 12px;
}

/* ── 菜单区 ── */
.menu-scroll {
  flex: 1;
  min-height: 0;
  overflow: hidden;

  :deep(.el-scrollbar__bar) {
    display: none;
  }
}

/* The inset ring stays visible inside the sidebar clip and the teleported popup. */
:global(.sidebar-keyboard-item:focus-visible),
.sidebar-menu :deep(.el-sub-menu:focus-visible > .el-sub-menu__title) {
  outline: 2px solid var(--border-focus);
  outline-offset: -2px;
}

:global(.sidebar-keyboard-popper) {
  max-height: calc(100dvh - 32px);
  overflow-y: auto;
}

/* 信息架构分组小标题 */
.menu-group {
  list-style: none;
  margin: 16px 0 6px;
  padding: 0 12px;
}

.menu-group.is-first {
  margin-top: 8px;
}

.menu-group-label {
  display: block;
  padding-left: 10px;
  font-size: 12px;
  font-weight: 500;
  line-height: 16px;
  letter-spacing: 0.04em;
  color: var(--sb-caption);
}

.sidebar-menu {
  --sb-title: var(--navigation-ink);
  --sb-caption: var(--navigation-ink-muted);
  --sb-text: var(--navigation-ink-secondary);
  --sb-child: var(--navigation-ink-secondary);
  --sb-icon: var(--navigation-ink-secondary);
  --sb-parent-active-text: var(--navigation-active);
  --sb-child-active-text: var(--navigation-active);
  --sb-active-icon: var(--navigation-active);
  --sb-divider: var(--navigation-divider);
  border-right: none;
  background: transparent;
  padding: 4px 12px 18px;

  :deep(.el-menu) {
    background: transparent;
    border: none;
  }

  /* 顶级条目 & 子菜单标题 */
  :deep(.el-menu-item),
  :deep(.el-sub-menu__title) {
    height: 40px;
    line-height: 40px;
    margin: 3px 0;
    padding: 0 10px;
    border-radius: 10px;
    font-size: 14px;
    font-weight: 500;
    color: var(--sb-text);
    transition: color 0.15s ease, background 0.15s ease;

    .menu-icon {
      position: relative;
      z-index: 1;
      width: 18px;
      height: 18px;
      margin-right: 12px;
      font-size: 18px;
      color: var(--sb-icon);
      background: transparent !important;
      box-shadow: none !important;
      filter: none !important;
      transition: color 0.15s ease;
    }

    &:hover {
      background: var(--sb-hover-bg);
      color: var(--sb-title);

      .menu-icon {
        color: var(--sb-active-icon);
      }
    }
  }

  :deep(.el-sub-menu__title .el-sub-menu__icon-arrow) {
    color: var(--sb-caption);
    font-weight: 600;
    background: transparent !important;
    box-shadow: none !important;
    filter: none !important;
  }

  /* 展开中的父级：文字加深 */
  :deep(.el-sub-menu.is-opened > .el-sub-menu__title) {
    color: var(--sb-title);
  }

  /* 含激活子项的父级只做语义强调，避免与当前子项形成两个强选中块。 */
  :deep(.el-sub-menu.is-active > .el-sub-menu__title) {
    background: transparent;
    color: var(--sb-parent-active-text);
    font-weight: 600;

    .menu-icon {
      color: var(--sb-active-icon);
    }

    .el-sub-menu__icon-arrow {
      color: var(--sb-parent-active-text);
    }
  }

  /* 当前页面使用透明曲面胶囊；图标和文字不参与光学滤镜。 */
  :deep(.el-menu-item.is-active) {
    position: relative;
    background: transparent;
    border-radius: var(--glass-control-radius);
    color: var(--sb-parent-active-text);
    font-weight: 700;

    .menu-icon {
      color: var(--sb-active-icon);
    }
  }

  /* 子菜单容器：缩进 + 细引导线 */
  :deep(.el-sub-menu .el-menu) {
    position: relative;
    padding: 2px 0 4px;

    &::before {
      content: '';
      position: absolute;
      left: 20px;
      top: 2px;
      bottom: 6px;
      width: 1px;
      background: var(--sb-divider);
    }
  }

  /* 子项 */
  :deep(.el-sub-menu .el-menu-item) {
    height: 36px;
    line-height: 36px;
    margin: 2px 8px 2px 26px;
    padding: 0 10px 0 14px !important;
    border-radius: 8px;
    font-size: 13px;
    font-weight: 500;
    color: var(--sb-child);

    &:hover {
      color: var(--sb-title);
      background: var(--sb-hover-bg);
    }

    /* 子项复用同一胶囊，保留原有缩进和操作密度。 */
    &.is-active {
      position: relative;
      background: transparent;
      border-radius: var(--glass-control-radius);
      color: var(--sb-child-active-text);
      font-weight: 700;
    }
  }

  :deep(.el-menu-item),
  :deep(.el-sub-menu__title) {
    .menu-label {
      position: relative;
      z-index: 1;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
  }

  :deep(.el-menu-item.is-active:has(.sidebar-selection-glass.is-liquid-refracting)),
  :deep(.el-sub-menu__title:has(.sidebar-selection-glass.is-liquid-refracting)) {
    color: var(--text-on-wallpaper);
    text-shadow: var(--text-on-wallpaper-shadow);

    .menu-icon,
    .el-sub-menu__icon-arrow {
      color: inherit;
    }
  }
}

.sidebar-selection-glass {
  position: absolute;
  inset: 0;
  z-index: 0;
  border-color: transparent;
  border-radius: inherit;
  pointer-events: none;
  transform-origin: 30% 50%;
  animation: sidebar-droplet-settle 520ms cubic-bezier(0.34, 1.56, 0.64, 1) both;
}

/* 水滴边缘：只在左上和右下留亮边，中段淡出，避免一圈均匀描边的“框”感。 */
.sidebar-selection-glass.glass-surface-lens {
  box-shadow: var(--shadow-glass-navigation-selection);

  &::before {
    background: var(--surface-glass-navigation-selection);
  }

  &::after {
    padding: 1.5px;
    background: var(--border-glass-navigation-selection);
  }
}

/* 选中项出现时像水滴落定：先被拉长压扁，再回弹到胶囊。 */
@keyframes sidebar-droplet-settle {
  0% {
    opacity: 0;
    transform: scale(0.82, 1.14);
  }
  45% {
    opacity: 1;
    transform: scale(1.04, 0.92);
  }
  75% {
    transform: scale(0.99, 1.03);
  }
  100% {
    transform: scale(1, 1);
  }
}

@media (prefers-reduced-motion: reduce) {
  .sidebar-selection-glass {
    animation: none;
  }
}

/* ── 底部个人 / 设置入口 ── */
.sidebar-footer {
  flex-shrink: 0;
  display: grid;
  grid-template-columns: 1fr 1px 1fr;
  align-items: center;
  gap: 10px;
  margin: 0 20px;
  padding: 18px 0 20px;
  border-top: 1px solid var(--navigation-divider);
}

:global(.sidebar-preferences-popper.el-popper) {
  max-width: calc(100vw - 24px);
  padding: 0;
  border: 0;
  border-radius: var(--radius-xl);
  background: transparent;
  box-shadow: none;
}

.sidebar-footer-popover {
  position: relative;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  width: 100%;
  max-height: min(680px, calc(100dvh - 32px));
  box-sizing: border-box;
  border: 1px solid var(--border-glass-edge);
  border-radius: var(--radius-xl);
  background: var(--surface-glass-overlay);
  color: var(--text-primary);
  font-family: var(--font-family-sans);
  box-shadow: var(--shadow-overlay);
  -webkit-backdrop-filter: blur(var(--glass-blur-overlay)) saturate(var(--glass-saturation));
  backdrop-filter: blur(var(--glass-blur-overlay)) saturate(var(--glass-saturation));
}

.footer-popover-header {
  display: flex;
  flex: 0 0 auto;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 18px 20px 16px;
  border-bottom: 1px solid var(--border-divider);
}

.footer-popover-heading {
  min-width: 0;

  h2 {
    margin: 0;
    font-size: 18px;
    font-weight: 700;
    line-height: 26px;
  }

  p {
    margin: 3px 0 0;
    color: var(--text-muted);
    font-size: 12px;
    line-height: 18px;
  }
}

.footer-close {
  display: grid;
  place-items: center;
  width: 30px;
  height: 30px;
  border: 0;
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--text-muted);
  font-size: 16px;
  cursor: pointer;

  &:hover {
    background: var(--surface-hover);
    color: var(--text-primary);
  }
}

.footer-close:focus-visible,
.brand-choice:focus-visible {
  outline: 2px solid var(--border-focus);
  outline-offset: 2px;
}

.footer-tab-panel {
  flex: 1 1 auto;
  min-height: 0;
  overflow-y: auto;
  overscroll-behavior: contain;
  scrollbar-width: thin;
  padding: 20px;
}

.profile-panel {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.profile-action,
.profile-link {
  width: 100%;
  height: 38px;
  display: inline-flex;
  align-items: center;
  gap: 9px;
  padding: 0 12px;
  border: 1px solid var(--border-divider);
  border-radius: 8px;
  background: transparent;
  color: var(--text-secondary);
  font-size: 13px;
  font-weight: 700;
  cursor: pointer;
  transition: border-color 0.16s ease, background 0.16s ease, color 0.16s ease;

  .el-icon {
    color: var(--el-color-danger);
    font-size: 16px;
  }

  &:hover {
    border-color: color-mix(in srgb, var(--el-color-danger) 32%, transparent);
    background: color-mix(in srgb, var(--el-color-danger) 8%, transparent);
    color: var(--el-color-danger);
  }

  &:disabled {
    cursor: wait;
    opacity: 0.68;
  }
}

.profile-link {
  .el-icon {
    color: var(--text-secondary);
  }

  &:hover {
    border-color: var(--border-divider);
    background: color-mix(in srgb, var(--brand-primary) 8%, transparent);
    color: var(--brand-primary);

    .el-icon {
      color: var(--brand-primary);
    }
  }
}

.appearance-panel {
  display: flex;
  flex-direction: column;
  gap: 20px;

  h3 {
    margin: 0;
    color: var(--text-primary);
    font-size: 12px;
    font-weight: 600;
    line-height: 20px;
  }
}

.appearance-section-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 10px;

  > span {
    color: var(--text-muted);
    font-size: 11px;
    line-height: 18px;
  }
}

.appearance-preferences {
  display: flex;
  flex-direction: column;
  gap: 14px;
  padding-block: 18px;
  border-block: 1px solid var(--border-divider);
}

.appearance-field {
  display: flex;
  align-items: center;
  gap: 16px;

  > h3 {
    flex: 0 0 56px;
  }
}

.appearance-segment {
  display: grid;
  flex: 1;
  min-width: 0;
  grid-auto-columns: minmax(0, 1fr);
  grid-auto-flow: column;
  gap: 2px;
  padding: 3px;
  border-radius: var(--radius-md);
  background: var(--surface-hover);
  box-shadow: inset 0 0 0 1px var(--border-subtle);
}

.appearance-segment__item {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 5px;
  height: 30px;
  padding: 0 4px;
  border: 0;
  border-radius: 7px;
  background: transparent;
  color: var(--text-secondary);
  font-family: inherit;
  font-size: 12px;
  font-weight: 500;
  white-space: nowrap;
  cursor: pointer;
  transition:
    background var(--motion-duration-fast) ease,
    color var(--motion-duration-fast) ease,
    box-shadow var(--motion-duration-fast) ease;

  &:hover {
    color: var(--text-primary);
  }

  &.active {
    background: var(--surface-solid-opaque);
    color: var(--brand-on-surface);
    box-shadow: var(--shadow-panel);
  }

  &:focus-visible {
    outline: 2px solid var(--border-focus);
    outline-offset: 1px;
  }
}

.appearance-material {
  padding: 0 12px;
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-md);
  background: var(--surface-hover);
}

.appearance-material-style {
  padding-block: 12px;
  border-top: 1px solid var(--border-divider);
}

.appearance-material-hint {
  margin: 8px 0 0;
  color: var(--text-muted);
  font-size: 11px;
  line-height: 16px;
}

.appearance-toggle {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 12px 0;
  cursor: pointer;

  > span {
    display: flex;
    min-width: 0;
    flex-direction: column;
    gap: 4px;
  }

  strong {
    color: var(--text-primary);
    font-size: 12px;
    font-weight: 600;
    line-height: 18px;
  }

  small {
    color: var(--text-muted);
    font-size: 11px;
    line-height: 16px;
  }

  :deep(.el-switch) {
    flex-shrink: 0;
  }
}

.brand-choice-list {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 8px;
}

.brand-choice {
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 7px;
  min-width: 0;
  padding: 5px 5px 7px;
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-md);
  background: var(--surface-solid-control);
  color: var(--text-secondary);
  font: inherit;
  font-size: 11px;
  font-weight: 500;
  line-height: 16px;
  cursor: pointer;
  transition:
    border-color var(--motion-duration-fast) ease,
    background var(--motion-duration-fast) ease,
    color var(--motion-duration-fast) ease;

  &:hover {
    border-color: var(--border-readable);
    background: var(--surface-hover);
  }

  &.active {
    border-color: var(--brand-primary);
    background: var(--brand-soft-bg);
    color: var(--brand-on-surface);
    box-shadow: var(--inner-highlight);
  }
}

.appearance-swatch {
  position: relative;
  width: 100%;
  height: 34px;
  flex: 0 0 auto;
  overflow: hidden;
  border-radius: 5px;
  background: var(--theme-wallpaper-image) var(--theme-wallpaper-position) / cover, var(--brand-primary-gradient);
  box-shadow: var(--inner-highlight);

  &::before,
  &::after {
    content: '';
    position: absolute;
    top: 7px;
    bottom: 7px;
    border-radius: 2px;
    background: var(--surface-solid-control);
  }

  &::before {
    left: 7px;
    width: 10px;
  }

  &::after {
    left: 21px;
    right: 7px;
    opacity: 0.72;
  }
}

.brand-choice-label {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.selected-check {
  position: absolute;
  top: 3px;
  right: 3px;
  width: 16px;
  height: 16px;
  border-radius: 50%;
  background: var(--brand-primary);
  color: var(--text-on-brand);
  font-size: 11px;
}

.appearance-footer {
  display: flex;
  flex: 0 0 auto;
  align-items: center;
  justify-content: center;
  gap: 6px;
  padding: 11px 20px;
  border-top: 1px solid var(--border-divider);
  color: var(--text-muted);
  font-size: 11px;
  line-height: 18px;

  .el-icon {
    color: var(--brand-on-surface);
  }
}

@media (max-width: 460px) {
  .footer-tab-panel {
    padding: 16px;
  }

  .appearance-field {
    align-items: stretch;
    flex-direction: column;
    gap: 8px;

    > h3 {
      flex-basis: auto;
    }
  }
}

.footer-entry {
  height: 30px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  border: 0;
  border-radius: 8px;
  background: transparent;
  color: var(--navigation-ink-secondary);
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  transition: color 0.16s ease, background 0.16s ease;

  .el-icon {
    font-size: 18px;
    color: var(--navigation-ink-secondary);
  }

  &:hover,
  &.active {
    color: var(--navigation-active);
    background: var(--sb-hover-bg);

    .el-icon {
      color: var(--navigation-active);
    }
  }
}

.footer-divider {
  width: 1px;
  height: 18px;
  background: var(--navigation-divider);
}

/* ── 折叠态 ── */
.is-collapsed {
  .menu-group {
    display: none;
  }

  .sidebar-menu {
    padding: 4px 8px 16px;

    :deep(.el-menu-item),
    :deep(.el-sub-menu__title) {
      justify-content: center;
      padding: 0 !important;

      .menu-icon {
        margin-right: 0;
      }
    }

    /* 折叠时当前子页面的父级也使用胶囊，保持所属模块可辨认。 */
    :deep(.el-sub-menu.is-active > .el-sub-menu__title) {
      position: relative;
      border-radius: var(--glass-control-radius);
      background: transparent;
    }
  }
}
</style>
