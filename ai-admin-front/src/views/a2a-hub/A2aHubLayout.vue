<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import PageHeader from '@/components/common/PageHeader.vue'
import WorkbenchPage from '@/components/common/WorkbenchPage.vue'
import { LiquidGlassNav } from '@/components/common/liquid-glass'

const route = useRoute()
const items = [
  { path: '/a2a-hub/overview', label: '总览', hint: '健康与待办' },
  { path: '/a2a-hub/publications', label: '本地发布', hint: '发布与版本' },
  { path: '/a2a-hub/remote-agents', label: '远程 Agent', hint: '发现与信任' },
  { path: '/a2a-hub/tasks', label: '任务中心', hint: '协作与追踪' },
  { path: '/a2a-hub/trust', label: '信任与策略', hint: '身份与凭据' },
  { path: '/a2a-hub/developer', label: '开发与诊断', hint: '接入与合规' },
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
    </PageHeader>

    <LiquidGlassNav source="wallpaper" class="hub-nav" aria-label="A2A Hub 工作区">
      <router-link
        v-for="item in items"
        :key="item.path"
        :to="item.path"
        :class="{ 'is-active': activePath === item.path }"
        :aria-current="activePath === item.path ? 'page' : undefined"
        :title="`${item.label} · ${item.hint}`"
      >
        {{ item.label }}
      </router-link>
    </LiquidGlassNav>

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
  flex: 0 0 auto;
  align-self: flex-start;
}
</style>
