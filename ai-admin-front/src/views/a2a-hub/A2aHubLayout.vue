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
      height-preset="standard"
      domain="platform"
      title="A2A 互联中心"
      description="统一发布、发现、信任与跨 Agent 任务治理，让每次协作都有身份、策略和运行证据。"
    >
      <template #tags>
        <el-tag type="primary" effect="plain">A2A 1.0</el-tag>
        <el-tag type="info" effect="plain">HTTP+JSON</el-tag>
      </template>
    </PageHeader>

    <nav class="hub-nav glass-surface-control" aria-label="A2A Hub 工作区">
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

    <router-view />
  </WorkbenchPage>
</template>

<style scoped lang="scss">
.a2a-hub-layout {
  width: min(100%, 1840px);
  margin-inline: auto;
  gap: var(--layout-page-gap);
}

.hub-nav {
  display: flex;
  width: 100%;
  gap: 4px;
  padding: 5px;
  overflow-x: auto;
  border-radius: var(--radius-md);
  scrollbar-width: none;
}

.hub-nav::-webkit-scrollbar {
  display: none;
}

.hub-nav__item {
  position: relative;
  display: flex;
  min-width: 132px;
  min-height: 42px;
  flex: 1 0 132px;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 8px 14px;
  border: 1px solid transparent;
  border-radius: var(--radius-md);
  color: var(--text-secondary);
  text-decoration: none;
  transition:
    background var(--motion-duration-fast) ease,
    border-color var(--motion-duration-fast) ease,
    color var(--motion-duration-fast) ease;
}

.hub-nav__item > .el-icon {
  flex: 0 0 auto;
  font-size: 18px;
}

.hub-nav__item strong {
  min-width: 0;
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

@media (max-width: 760px) {
  .hub-nav__item {
    min-width: 124px;
    flex-basis: 124px;
    padding-inline: 12px;
  }
}
</style>
