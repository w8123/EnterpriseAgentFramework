<template>
  <nav class="app-sidebar glass-surface-shell" :class="{ 'is-collapsed': collapsed }">
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
      <div class="sidebar-section-caption">当前范围</div>
      <el-select
        class="sidebar-project-select"
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
      <span class="sidebar-project-pill" :class="{ active: sidebarHasResolvedScope }">
        {{ sidebarProjectPillLabel }}
      </span>
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
              <span class="menu-label leaf">{{ leaf.label }}</span>
            </el-menu-item>
          </el-sub-menu>
        </template>
      </el-menu>
    </el-scrollbar>

    <footer v-if="!collapsed" class="sidebar-footer">
      <div v-if="activeFooterPanel" class="sidebar-footer-popover" @click.stop>
        <div class="footer-tabs" role="tablist" aria-label="账户与设置">
          <button
            class="footer-tab"
            :class="{ active: activeFooterPanel === 'profile' }"
            type="button"
            role="tab"
            :aria-selected="activeFooterPanel === 'profile'"
            @click="activeFooterPanel = 'profile'"
          >
            个人
          </button>
          <button
            class="footer-tab"
            :class="{ active: activeFooterPanel === 'settings' }"
            type="button"
            role="tab"
            :aria-selected="activeFooterPanel === 'settings'"
            @click="activeFooterPanel = 'settings'"
          >
            设置
          </button>
        </div>
        <div class="footer-tab-panel" role="tabpanel">
          <div v-if="activeFooterPanel === 'profile'" class="profile-panel">
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
            <div class="appearance-heading">
              <span class="panel-kicker">界面设置</span>
              <strong>主题配色</strong>
            </div>

            <div class="appearance-subtitle">主题色</div>
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
                <span class="appearance-swatch" :data-brand-option="option.value" />
                <span class="brand-choice-label">{{ option.label }}</span>
                <el-icon v-if="option.value === brand" class="selected-check"><Check /></el-icon>
              </button>
            </div>
          </div>
        </div>
      </div>

      <button
        class="footer-entry"
        :class="{ active: activeFooterPanel === 'profile' }"
        type="button"
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
        @click="toggleFooterPanel('settings')"
      >
        <el-icon><Setting /></el-icon>
        <span>设置</span>
      </button>
    </footer>
  </nav>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch, type ComponentPublicInstance } from 'vue'
import type { MenuInstance } from 'element-plus'
import { useRoute, useRouter } from 'vue-router'
import {
  ArrowLeft,
  ArrowRight,
  Check,
  Setting,
  SwitchButton,
  User,
} from '@element-plus/icons-vue'
import { logoutPlatform } from '@/api/platformAuth'
import { platformSessionUser } from '@/auth/platformSession'
import { useTheme } from '@/composables/useTheme'
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
const { brand, brandOptions, setBrand } = useTheme()
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
// Keep the previous test/consumer-facing name while the active page scope may
// also expose the explicit `all` sentinel instead of a numeric project id.
const resolvedCurrentProjectId = computed(() => (
  usesPageProjectScope.value && pageProjectScope
    ? pageProjectScope.selection.value ?? NO_PROJECT_VALUE
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
  --sb-parent-active-text: var(--brand-active);
  --sb-child-active-bg: rgb(var(--brand-primary-rgb) / 0.12);
  --sb-child-active-text: var(--brand-active);
  --sb-active-icon: var(--brand-primary);
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
  background: var(--sb-divider);
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
  color: var(--sb-title);
}

.brand-sub {
  font-size: 11.5px;
  line-height: 1.3;
  color: var(--sb-subtitle);
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
  border: 1px solid rgba(255, 255, 255, 0.65);
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.46);
  color: #6c7d95;
  cursor: pointer;
  transition: color 0.16s ease, border-color 0.16s ease, background 0.16s ease, transform 0.16s ease;

  .el-icon {
    font-size: 14px;
  }

  &:hover {
    color: var(--sb-parent-active-text);
    border-color: rgb(var(--brand-primary-rgb) / 0.24);
    background: rgba(255, 255, 255, 0.72);
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

.sidebar-section-caption {
  margin: 0 0 8px 4px;
  color: var(--sb-caption);
  font-size: 12px;
  font-weight: 500;
  line-height: 16px;
}

.sidebar-project-select {
  width: 100%;

  :deep(.el-select__wrapper) {
    min-height: 42px;
    padding: 0 42px 0 12px;
    border-radius: 11px;
    background: rgba(255, 255, 255, 0.48);
    box-shadow:
      0 0 0 1px rgba(210, 226, 245, 0.75) inset,
      0 8px 18px rgba(37, 72, 122, 0.035);
    backdrop-filter: blur(7px);
  }

  :deep(.el-select__selected-item),
  :deep(.el-select__placeholder) {
    color: #13223a;
    font-size: 14px;
    font-weight: 700;
  }

  :deep(.el-select__caret) {
    color: #7d8da6;
  }
}

.project-status-dot {
  width: 7px;
  height: 7px;
  border-radius: 999px;
  background: #b5c0d0;

  &.active {
    background: #36c28b;
    box-shadow: 0 0 0 3px rgba(54, 194, 139, 0.14);
  }
}

.sidebar-project-pill {
  position: absolute;
  right: 34px;
  top: 30px;
  z-index: 2;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 48px;
  height: 22px;
  padding: 0 10px;
  border: 1px solid rgba(148, 163, 184, 0.22);
  border-radius: 999px;
  background: rgba(241, 245, 249, 0.82);
  color: #718096;
  font-size: 11px;
  font-weight: 700;
  pointer-events: none;

  &.active {
    border-color: rgba(47, 190, 128, 0.42);
    background: rgba(225, 250, 239, 0.75);
    color: #10a56c;
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

:global(.sidebar-project-option) {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  width: 100%;
}

:global(.sidebar-project-option small) {
  color: #8190a8;
  font-size: 12px;
}

/* ── 菜单区 ── */
.menu-scroll {
  flex: 1;
  min-height: 0;
  overflow: hidden;
}

/* The inset ring stays visible inside the sidebar clip and the teleported popup. */
:global(.sidebar-keyboard-item:focus-visible),
.sidebar-menu :deep(.el-sub-menu:focus-visible > .el-sub-menu__title) {
  outline: 2px solid var(--border-focus);
  outline-offset: -2px;
}

:global(.sidebar-keyboard-popper) {
  max-height: calc(100dvh - var(--space-8));
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

  /* 顶级 leaf 激活（如「概览」）：浅品牌底 + rail */
  :deep(.el-menu-item.is-active) {
    position: relative;
    background: var(--sb-parent-active-bg);
    color: var(--sb-parent-active-text);
    font-weight: 700;

    &::before {
      content: '';
      position: absolute;
      left: -12px;
      top: 50%;
      transform: translateY(-50%);
      width: 3px;
      height: 20px;
      border-radius: 0 2px 2px 0;
      background: var(--sb-rail);
    }

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

    /* 激活子项：浅品牌卡 + 白边 + 内高光 + 品牌色 rail */
    &.is-active {
      position: relative;
      background: var(--sb-child-active-bg);
      color: var(--sb-child-active-text);
      font-weight: 700;
      border: 1px solid rgba(255, 255, 255, 0.55);
      box-shadow:
        0 8px 18px rgba(37, 72, 122, 0.05),
        inset 0 1px 3px rgba(255, 255, 255, 0.72);

      &::before {
        content: '';
        position: absolute;
        left: -11px;
        top: 50%;
        transform: translateY(-50%);
        width: 3px;
        height: 18px;
        border-radius: 2px;
        background: var(--sb-rail);
      }
    }
  }

  :deep(.el-menu-item),
  :deep(.el-sub-menu__title) {
    .menu-label {
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
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
  border-top: 1px solid rgba(180, 205, 235, 0.32);
}

.sidebar-footer-popover {
  position: absolute;
  left: 0;
  right: 0;
  bottom: 66px;
  overflow: hidden;
  min-height: 104px;
  max-height: min(390px, calc(100vh - 136px));
  border: 1px solid rgba(210, 226, 245, 0.76);
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.78);
  box-shadow:
    0 18px 42px rgba(37, 72, 122, 0.12),
    inset 0 1px 3px rgba(255, 255, 255, 0.72);
  backdrop-filter: blur(14px);
}

.footer-tabs {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  padding: 8px;
  gap: 6px;
}

.footer-tab {
  height: 30px;
  border: 0;
  border-radius: 8px;
  background: transparent;
  color: #6d7c94;
  font-size: 13px;
  font-weight: 700;
  cursor: pointer;

  &.active {
    color: var(--sb-child-active-text);
    background: var(--sb-child-active-bg);
    box-shadow: inset 0 1px 2px rgba(255, 255, 255, 0.62);
  }
}

.footer-tab-panel {
  min-height: 50px;
  overflow: auto;
  padding: 10px 10px 12px;
  border-top: 1px solid rgba(180, 205, 235, 0.24);
}

.profile-panel {
  display: flex;
}

.profile-action {
  width: 100%;
  height: 38px;
  display: inline-flex;
  align-items: center;
  gap: 9px;
  padding: 0 12px;
  border: 1px solid var(--sb-divider);
  border-radius: 8px;
  background: transparent;
  color: var(--sb-text);
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

.appearance-panel {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.appearance-heading {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 8px;
  color: var(--sb-title);

  strong {
    font-size: 13px;
    line-height: 18px;
  }
}

.panel-kicker,
.appearance-subtitle {
  color: var(--sb-caption);
  font-size: 11px;
  font-weight: 800;
  letter-spacing: 0;
}

.appearance-subtitle {
  margin-top: 2px;
}

.brand-choice {
  border: 1px solid rgba(189, 207, 230, 0.6);
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.48);
  color: var(--sb-text);
  cursor: pointer;
  transition: border-color 0.16s ease, background 0.16s ease, color 0.16s ease, box-shadow 0.16s ease;

  &:hover {
    border-color: rgb(var(--brand-primary-rgb) / 0.28);
    background: rgb(var(--brand-selected-rgb) / 0.34);
  }

  &.active {
    border-color: rgb(var(--brand-primary-rgb) / 0.34);
    background: rgb(var(--brand-selected-rgb) / 0.72);
    color: var(--brand-active);
    box-shadow: inset 0 1px 2px rgba(255, 255, 255, 0.72);
  }
}

.brand-choice-list {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
}

.brand-choice {
  height: 34px;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
  padding: 0 8px;
  font-size: 12px;
  font-weight: 700;
}

.appearance-swatch {
  width: 12px;
  height: 12px;
  flex: 0 0 auto;
  border-radius: 50%;
  background: linear-gradient(135deg, #6366f1, #8b5cf6);
  box-shadow: 0 0 0 1px rgba(15, 23, 42, 0.08);
}

.appearance-swatch[data-brand-option='metro-green'] {
  background: linear-gradient(135deg, #0b7a59, #2d9375);
}

.appearance-swatch[data-brand-option='aurora-cyan'] {
  background: linear-gradient(135deg, #0891b2, #22d3ee);
}

.appearance-swatch[data-brand-option='nebula-violet'] {
  background: linear-gradient(135deg, #7c3aed, #ec4899);
}

.appearance-swatch[data-brand-option='coral-rose'] {
  background: linear-gradient(135deg, #db2777, #f472b6);
}

.appearance-swatch[data-brand-option='solar-gold'] {
  background: linear-gradient(135deg, #b45309, #f59e0b);
}

.appearance-swatch[data-brand-option='deep-ocean'] {
  background: linear-gradient(135deg, #1d4ed8, #06b6d4);
}

.brand-choice-label {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.selected-check {
  margin-left: auto;
  color: var(--brand-primary);
  font-size: 13px;
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
  color: #53657d;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  transition: color 0.16s ease, background 0.16s ease;

  .el-icon {
    font-size: 18px;
    color: #7d8da6;
  }

  &:hover,
  &.active {
    color: var(--sb-parent-active-text);
    background: var(--sb-hover-bg);

    .el-icon {
      color: var(--sb-active-icon);
    }
  }
}

.footer-divider {
  width: 1px;
  height: 18px;
  background: rgba(180, 205, 235, 0.45);
}

[data-theme='dark'] .sidebar-collapse-button,
[data-theme='dark'] .sidebar-project-select :deep(.el-select__wrapper),
[data-theme='dark'] .sidebar-footer-popover {
  border-color: rgba(255, 255, 255, 0.08);
  background: rgba(15, 23, 42, 0.72);
}

[data-theme='dark'] .sidebar-project-select {
  :deep(.el-select__selected-item),
  :deep(.el-select__placeholder) {
    color: #e8eefb;
  }
}

[data-theme='dark'] .sidebar-project-pill {
  border-color: rgba(148, 163, 184, 0.18);
  background: rgba(30, 41, 59, 0.72);
  color: #9aa8c0;

  &.active {
    border-color: rgba(47, 190, 128, 0.28);
    background: rgba(20, 83, 45, 0.28);
    color: #86efac;
  }
}

[data-theme='dark'] .footer-entry,
[data-theme='dark'] .footer-tab {
  color: #9aa8c0;
}

[data-theme='dark'] .brand-choice {
  border-color: rgba(255, 255, 255, 0.08);
  background: rgba(15, 23, 42, 0.5);
  color: #c3cee0;

  &:hover {
    border-color: rgb(var(--brand-primary-rgb) / 0.28);
    background: rgb(var(--brand-primary-rgb) / 0.14);
  }

  &.active {
    border-color: rgb(var(--brand-primary-rgb) / 0.34);
    background: rgb(var(--brand-primary-rgb) / 0.18);
    color: var(--brand-disabled);
    box-shadow: none;
  }
}

/* 暗色下弱化激活子项的白边与内高光 */
[data-theme='dark'] .sidebar-menu :deep(.el-sub-menu .el-menu-item.is-active) {
  border-color: rgba(255, 255, 255, 0.08);
  box-shadow: none;
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

    :deep(.el-menu-item.is-active)::before {
      left: -8px;
    }

    /* 折叠后没有子项可见，恢复父级底色以标明当前所属模块。 */
    :deep(.el-sub-menu.is-active > .el-sub-menu__title) {
      background: var(--sb-parent-active-bg);
    }
  }
}
</style>
