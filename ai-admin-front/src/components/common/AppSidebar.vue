<template>
  <nav class="app-sidebar glass-surface-shell" :class="{ 'is-collapsed': collapsed }">
    <div class="brand">
      <span class="brand-glow" aria-hidden="true" />
      <img class="brand-logo" src="/reachai-logo-tile.png" alt="ReachAI" />
      <div v-if="!collapsed" class="brand-text">
        <span class="brand-title">ReachAI</span>
        <span class="brand-sub">企业AI智能体中台</span>
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

    <section v-if="!collapsed && !hideProjectPanel" class="sidebar-project-panel" aria-label="当前项目">
      <div class="sidebar-section-caption">当前项目</div>
      <el-select
        class="sidebar-project-select"
        :model-value="resolvedCurrentProjectId"
        :loading="projectStore.loading"
        filterable
        placeholder="未选择项目"
        @update:model-value="handleProjectChange"
        @visible-change="handleProjectVisibleChange"
      >
        <template #prefix>
          <span class="project-status-dot" :class="{ active: Boolean(projectStore.currentProject) }" />
        </template>
        <el-option :value="null" label="未选择项目" />
        <el-option
          v-for="project in projectStore.projects"
          :key="project.id"
          :label="project.name"
          :value="project.id"
        >
          <div class="sidebar-project-option">
            <span>{{ project.name }}</span>
            <small>{{ project.projectCode || `ID ${project.id}` }}</small>
          </div>
        </el-option>
      </el-select>
      <span class="sidebar-project-pill" :class="{ active: Boolean(projectStore.currentProject) }">
        {{ projectStore.currentProject ? '运行中' : '未选择' }}
      </span>
    </section>

    <el-scrollbar class="menu-scroll">
      <el-menu
        :default-active="activeMenu"
        :default-openeds="openGroups"
        :collapse="collapsed"
        :collapse-transition="false"
        router
        class="sidebar-menu"
      >
        <template v-for="(entry, i) in sidebarMenu" :key="entry.kind === 'item' ? entry.index : `group-${i}`">
          <li v-if="entry.kind === 'group'" class="menu-group" :class="{ 'is-first': i === 0 }" role="presentation">
            <span class="menu-group-label">{{ entry.label }}</span>
          </li>

          <el-menu-item v-else-if="!entry.children" :index="entry.index">
            <el-icon class="menu-icon"><component :is="entry.icon" /></el-icon>
            <span class="menu-label">{{ entry.label }}</span>
          </el-menu-item>

          <el-sub-menu v-else :index="entry.index">
            <template #title>
              <el-icon class="menu-icon"><component :is="entry.icon" /></el-icon>
              <span class="menu-label">{{ entry.label }}</span>
            </template>
            <el-menu-item v-for="leaf in entry.children" :key="leaf.index" :index="leaf.index">
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
          <div v-if="activeFooterPanel === 'settings'" class="appearance-panel">
            <div class="appearance-heading">
              <span class="panel-kicker">界面星盘</span>
              <strong>光影与色彩</strong>
            </div>

            <div class="appearance-subtitle">光影模式</div>
            <div class="appearance-mode" role="radiogroup" aria-label="光影模式">
              <button
                class="appearance-mode-button"
                :class="{ active: theme === 'light' }"
                type="button"
                role="radio"
                :aria-checked="theme === 'light'"
                :aria-disabled="themeModeControlsDisabled"
                :disabled="themeModeControlsDisabled"
              >
                <el-icon><Sunny /></el-icon>
                <span>日曜</span>
              </button>
              <button
                class="appearance-mode-button"
                :class="{ active: theme === 'dark' }"
                type="button"
                role="radio"
                :aria-checked="theme === 'dark'"
                :aria-disabled="themeModeControlsDisabled"
                :disabled="themeModeControlsDisabled"
              >
                <el-icon><Moon /></el-icon>
                <span>月隐</span>
              </button>
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
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ArrowLeft, ArrowRight, Check, Moon, Setting, Sunny, User } from '@element-plus/icons-vue'
import { useTheme } from '@/composables/useTheme'
import { useProjectStore } from '@/store/project'
import { resolveActiveMenu, resolveOpenGroups, sidebarMenu } from './sidebarMenu'

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
const projectStore = useProjectStore()
const { theme, brand, brandOptions, setBrand } = useTheme()
const collapsed = computed(() => props.collapsed)
const hideProjectPanel = computed(() => props.hideProjectPanel)
const activeMenu = computed(() => resolveActiveMenu(route.path, route.meta.activeMenu))
const openGroups = computed(() => resolveOpenGroups(route.path))
const activeFooterPanel = ref<'profile' | 'settings' | null>(null)
const resolvedCurrentProjectId = computed(() => projectStore.currentProject?.id ?? null)
const themeModeControlsDisabled = true

function ensureProjectOptionsLoaded() {
  if (!hideProjectPanel.value && !projectStore.projects.length) {
    projectStore.fetchProjects()
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

function handleProjectChange(value: number | null | undefined) {
  projectStore.setCurrentProject(value ?? null)
}

function handleProjectVisibleChange(visible: boolean) {
  if (visible) {
    projectStore.fetchProjects()
  }
}

function toggleFooterPanel(panel: 'profile' | 'settings') {
  activeFooterPanel.value = activeFooterPanel.value === panel ? null : panel
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
  bottom: 22px;
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
  overflow: hidden;
}

/* 分组小标题（工作台 / 资产与编排 / 平台治理） */
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

  /* 含激活子项的父级：浅品牌底 + 品牌色加粗 + 箭头着色 */
  :deep(.el-sub-menu.is-active > .el-sub-menu__title) {
    background: var(--sb-parent-active-bg);
    color: var(--sb-parent-active-text);
    font-weight: 700;

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

.appearance-mode {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
}

.appearance-mode-button,
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

.appearance-mode-button {
  height: 34px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 7px;
  font-size: 12px;
  font-weight: 800;

  .el-icon {
    font-size: 15px;
  }

  &:disabled {
    cursor: not-allowed;
    opacity: 0.64;
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

[data-theme='dark'] .appearance-mode-button,
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
  }
}
</style>
