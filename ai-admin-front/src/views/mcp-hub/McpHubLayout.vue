<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { DataAnalysis, Monitor, Promotion } from '@element-plus/icons-vue'
import PageHeader from '@/components/common/PageHeader.vue'
import WorkbenchPage from '@/components/common/WorkbenchPage.vue'

const route = useRoute()
const items = [
  { path: '/mcp-hub/overview', label: '总览', icon: DataAnalysis },
  { path: '/mcp-hub/publications', label: '对外发布', icon: Promotion },
  { path: '/mcp-hub/call-logs', label: '调用流水', icon: Monitor },
]

const activePath = computed(() => {
  const path = route.path
  return items.find((item) => path.startsWith(item.path))?.path ?? '/mcp-hub/overview'
})
</script>

<template>
  <WorkbenchPage class="mcp-hub-layout" layout="list">
    <PageHeader
      variant="overview"
      height-preset="compact"
      domain="platform"
      title="MCP 互联中心"
      description="将能力和已发布 Workflow 安全发布为 MCP Tool。"
    />

    <nav class="hub-nav glass-surface-control" aria-label="MCP 互联中心工作区">
      <router-link
        v-for="item in items"
        :key="item.path"
        :to="item.path"
        class="hub-nav__item"
        :class="{ 'is-active': activePath === item.path }"
      >
        <el-icon aria-hidden="true"><component :is="item.icon" /></el-icon>
        <strong>{{ item.label }}</strong>
      </router-link>
    </nav>

    <router-view />
  </WorkbenchPage>
</template>

<style scoped lang="scss">
.mcp-hub-layout {
  width: min(100%, 1840px);
  margin-inline: auto;
  gap: var(--layout-page-gap);
}

.hub-nav {
  display: flex;
  width: max-content;
  max-width: 100%;
  align-self: flex-start;
  gap: 4px;
  padding: 4px;
  overflow-x: auto;
  border-radius: var(--radius-md);
  scrollbar-width: none;
}

.hub-nav::-webkit-scrollbar {
  display: none;
}

.hub-nav__item {
  display: flex;
  min-width: 112px;
  min-height: 38px;
  align-items: center;
  justify-content: center;
  gap: 9px;
  padding: 7px 16px;
  border: 1px solid transparent;
  border-radius: var(--radius-md);
  color: var(--text-secondary);
  text-decoration: none;
  transition: background var(--motion-duration-fast) ease, color var(--motion-duration-fast) ease;
}

.hub-nav__item > .el-icon {
  flex: 0 0 auto;
  font-size: 18px;
}

.hub-nav__item strong {
  overflow: hidden;
  font-size: 13px;
  font-weight: 700;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.hub-nav__item:hover,
.hub-nav__item.is-active {
  color: var(--brand-active);
  background: var(--surface-glass-selected);
}

.hub-nav__item.is-active {
  border-color: rgb(var(--brand-primary-rgb) / 0.18);
  box-shadow: var(--inner-highlight);
}

@media (max-width: 780px) {
  .hub-nav {
    width: 100%;
  }

  .hub-nav__item {
    min-width: 104px;
    flex: 1 0 auto;
    padding-inline: 12px;
  }
}
</style>
