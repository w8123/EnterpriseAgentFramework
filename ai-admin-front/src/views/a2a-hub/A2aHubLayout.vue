<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import {
  Connection,
  DataAnalysis,
  Files,
  Key,
  Monitor,
  Promotion,
} from '@element-plus/icons-vue'
import PageHeader from '@/components/common/PageHeader.vue'
import WorkbenchPage from '@/components/common/WorkbenchPage.vue'

const route = useRoute()
const items = [
  { path: '/a2a-hub/overview', label: '总览', hint: '健康与待办', icon: DataAnalysis },
  { path: '/a2a-hub/publications', label: '本地发布', hint: '发布与版本', icon: Promotion },
  { path: '/a2a-hub/remote-agents', label: '远程 Agent', hint: '发现与信任', icon: Connection },
  { path: '/a2a-hub/tasks', label: '任务中心', hint: '协作与追踪', icon: Files },
  { path: '/a2a-hub/trust', label: '信任与策略', hint: '身份与凭据', icon: Key },
  { path: '/a2a-hub/developer', label: '开发与诊断', hint: '接入与合规', icon: Monitor },
]

const activePath = computed(() => {
  const path = route.path
  return items.find((item) => path.startsWith(item.path))?.path ?? '/a2a-hub/overview'
})
</script>

<template>
  <WorkbenchPage class="a2a-hub-layout" layout="list">
    <PageHeader
      variant="overview"
      height-preset="compact"
      presentation="plain"
      action-presentation="inline"
      domain="platform"
      title="A2A 互联中心"
      description="管理 Agent 发布、远程连接、信任策略与协作任务。"
    >
      <template #tags>
        <el-tag type="primary" effect="plain">A2A 1.0</el-tag>
        <el-tag type="info" effect="plain">HTTP+JSON</el-tag>
      </template>
      <template #meta>
        <!-- 原生 nav + router-link 保持键盘可达；外观与 LiquidGlassNav 同一套透镜胶囊。 -->
        <nav class="hub-nav glass-surface-lens glass-workspace-adaptive" aria-label="A2A Hub 工作区">
          <router-link
            v-for="item in items"
            :key="item.path"
            :to="item.path"
            class="hub-nav__item"
            :class="{ 'is-active': activePath === item.path }"
            :title="`${item.label} · ${item.hint}`"
          >
            <el-icon aria-hidden="true"><component :is="item.icon" /></el-icon>
            <strong>{{ item.label }}</strong>
          </router-link>
        </nav>
      </template>
    </PageHeader>

    <!-- 子页根节点挂 v-loading；合并玻璃加载层，避免整页刷新时盖一层白雾。 -->
    <router-view v-slot="{ Component }">
      <component :is="Component" class="glass-loading-surface" />
    </router-view>
  </WorkbenchPage>
</template>

<style scoped lang="scss">
.a2a-hub-layout {
  width: min(100%, 1840px);
  margin-inline: auto;
  gap: var(--layout-page-gap);
}

.hub-nav {
  display: inline-flex;
  max-width: 100%;
  min-width: 0;
  margin-top: 6px;
  gap: 4px;
  padding: 4px;
  overflow-x: auto;
  border-radius: var(--radius-pill);
  scrollbar-width: none;
}

.hub-nav::-webkit-scrollbar {
  display: none;
}

.hub-nav__item {
  display: inline-flex;
  min-height: var(--control-height);
  flex: 0 0 auto;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 6px 16px;
  border: 1px solid transparent;
  border-radius: var(--radius-pill);
  color: var(--text-secondary);
  text-decoration: none;
  transition:
    background var(--motion-duration-fast) ease,
    border-color var(--motion-duration-fast) ease,
    color var(--motion-duration-fast) ease;
}

.hub-nav__item > .el-icon {
  flex: 0 0 auto;
  font-size: 16px;
}

.hub-nav__item strong {
  font-size: 13px;
  font-weight: 500;
  white-space: nowrap;
}

.hub-nav__item:hover {
  color: var(--text-primary);
  background: var(--surface-hover);
}

.hub-nav__item.is-active {
  border-color: var(--border-glass-edge);
  color: var(--brand-on-surface);
  background: var(--surface-glass-inset);
  box-shadow: var(--glass-rim-double);
}

.hub-nav__item.is-active strong {
  font-weight: 650;
}

.hub-nav__item:focus-visible {
  outline: 2px solid var(--border-focus);
  outline-offset: -2px;
}

@media (max-width: 760px) {
  .hub-nav {
    width: 100%;
  }

  .hub-nav__item {
    padding-inline: 12px;
  }
}

@media (prefers-reduced-motion: reduce) {
  .hub-nav__item {
    transition: none;
  }
}

@media (forced-colors: active) {
  .hub-nav__item.is-active {
    border-color: Highlight;
    color: HighlightText;
    background: Highlight;
  }
}
</style>
